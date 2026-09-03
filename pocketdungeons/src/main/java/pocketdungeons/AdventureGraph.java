package pocketdungeons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The descent graph M11 replaces the recipe system with: a transition lookup
 * from {@code currentTheme} to a weighted set of next themes, plus the pool of
 * entry themes a boss run resets into. No Minecraft imports, same discipline
 * as {@link AffixMath} and {@link DoorMask}: the pick arithmetic is the part
 * worth testing with plain {@code javac}, not the datapack loading around it
 * ({@link AdventureGraphs} owns that).
 *
 * <h2>Three node kinds, one shape</h2>
 *
 * <p>A node is {@code ENTRY}, {@code DESCENT}, or {@code BOSS}. Entry and
 * descent nodes carry a weighted {@link Transition} list the doors draw three
 * picks from; a boss node carries none, because reaching one ends the
 * adventure rather than continuing it. {@link #pick} never has to branch on a
 * boss node from the caller's side: {@link DungeonLog#recordTheme} resets
 * {@code currentTheme} to an entry theme the moment a boss run completes, so
 * by the time the next door offer is computed {@code currentTheme} is already
 * an entry or descent theme again.
 *
 * <h2>Depth is tracked but not yet reweighted</h2>
 *
 * <p>{@code depth} feeds the seed so a given {@code (owner, currentTheme,
 * depth)} always picks the same three themes (the watcher-stability rule every
 * seeded pick in this mod follows), but the pick itself does not yet favour
 * shallow nodes over deep ones. The brainstorm's "deeper is rarer" curve is
 * explicitly open tuning in {@code D3_PROGRESSION_PLAN.md}'s M11 section; this
 * milestone wires the plumbing depth needs without guessing the curve.
 */
final class AdventureGraph {

    /** How a theme behaves in the graph. */
    enum Kind {
        /** A theme a boss run resets into; also offers further descent. */
        ENTRY,
        /** A theme reached by descending; offers further descent. */
        DESCENT,
        /** Ends the adventure. Resets {@code currentTheme} to an entry theme. */
        BOSS
    }

    /** One weighted edge out of a node. */
    record Transition(String theme, int weight) {
        Transition {
            if (theme == null || theme.isBlank()) {
                throw new IllegalArgumentException("transition theme must not be blank");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("transition weight must be positive");
            }
        }
    }

    /**
     * One theme's place in the graph.
     *
     * @param reward (M17) the Herobrine Cube power id a rare drop from this
     *               node's encounters grants, or {@code ""} for a node with no
     *               extractable reward. A "pharaoh's chamber" node, in the
     *               plan's own words: authored sparingly, since a reward here is
     *               what makes an extractable item rare rather than ordinary
     *               tier loot. {@code AdventureGraphs} rejects a blank-but-set
     *               reward the same way it rejects a blank transition theme;
     *               most nodes simply omit the field.
     */
    record Node(String theme, Kind kind, List<Transition> next, String reward) {
        Node {
            if (theme == null || theme.isBlank()) {
                throw new IllegalArgumentException("node theme must not be blank");
            }
            next = List.copyOf(next);
            if (kind == Kind.BOSS && !next.isEmpty()) {
                throw new IllegalArgumentException("a boss node must not declare transitions: " + theme);
            }
            if (kind != Kind.BOSS && next.isEmpty()) {
                throw new IllegalArgumentException("a non-boss node must declare at least one transition: " + theme);
            }
            reward = reward == null ? "" : reward;
        }

        /** Convenience for a node with no Cube reward; every pre-M17 call site keeps compiling unchanged. */
        Node(String theme, Kind kind, List<Transition> next) {
            this(theme, kind, next, "");
        }
    }

    static final AdventureGraph EMPTY = new AdventureGraph(Map.of());

    private final Map<String, Node> nodes;
    /** Every {@code ENTRY}-kind theme, sorted, so the reset pool is deterministic to build. */
    private final List<String> entryThemes;

    private AdventureGraph(Map<String, Node> nodes) {
        this.nodes = Map.copyOf(nodes);
        List<String> entries = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.kind() == Kind.ENTRY) {
                entries.add(node.theme());
            }
        }
        Collections.sort(entries);
        this.entryThemes = List.copyOf(entries);
    }

    static AdventureGraph of(Map<String, Node> nodes) {
        return new AdventureGraph(nodes);
    }

    Node node(String theme) {
        return theme == null ? null : nodes.get(theme);
    }

    /** Total node count, for the admin reload command's summary. */
    int size() {
        return nodes.size();
    }

    List<String> entryThemes() {
        return entryThemes;
    }

    /**
     * Three next-theme offers for a door choice out of {@code currentTheme}.
     *
     * <p>Falls back to a uniform pick across every entry theme when
     * {@code currentTheme} is blank, unknown, or a boss node (a state that
     * should not reach here in practice; see the class note) -- the same "deal
     * three discoverable themes" shape {@code ThemeOfferMath.pick} used before
     * the graph existed. An empty graph (nothing loaded yet, or a fresh server
     * before the datapack resolves) returns three empty strings rather than
     * throwing, since {@code Keystone.offers} already tolerates a null theme.
     */
    List<String> pick(UUID owner, String currentTheme, int depth) {
        Node node = node(currentTheme);
        List<Transition> pool = node != null && !node.next().isEmpty()
                ? node.next() : uniformEntryPool();
        if (pool.isEmpty()) {
            return List.of("", "", "");
        }
        List<Transition> expanded = weighted(pool);
        Collections.shuffle(expanded, new Random(seed(owner, currentTheme, depth)));
        // M42.6: dedupe by theme before taking the top three, the same
        // dedupe-then-shuffle convention BountyTracker.bountiesFor already
        // uses, so one node cannot offer the same theme on two or three
        // doors. Iterating the already-shuffled, weight-expanded list and
        // keeping first occurrences preserves the weighting bias (a
        // heavier-weight theme has more chances to appear early) while
        // still producing distinct picks. A node with fewer than three
        // distinct transitions has nothing else to offer, so the modulo
        // below falls back to repeating one of them.
        List<String> distinct = new ArrayList<>();
        for (Transition transition : expanded) {
            if (!distinct.contains(transition.theme())) {
                distinct.add(transition.theme());
            }
            if (distinct.size() == 3) {
                break;
            }
        }
        List<String> offers = new ArrayList<>(3);
        for (int step = 0; step < 3; step++) {
            offers.add(distinct.get(step % distinct.size()));
        }
        return List.copyOf(offers);
    }

    /** One weighted entry theme, for a completed boss run's reset. */
    String resetTheme(UUID owner, int depth) {
        if (entryThemes.isEmpty()) {
            return null;
        }
        List<String> pool = new ArrayList<>(entryThemes);
        Collections.shuffle(pool, new Random(seed(owner, "$boss_reset", depth)));
        return pool.get(0);
    }

    private List<Transition> uniformEntryPool() {
        List<Transition> pool = new ArrayList<>(entryThemes.size());
        for (String theme : entryThemes) {
            pool.add(new Transition(theme, 1));
        }
        return pool;
    }

    /** Expands weights into repeated entries so a shuffle is a weighted pick. */
    private static List<Transition> weighted(List<Transition> transitions) {
        List<Transition> expanded = new ArrayList<>();
        for (Transition transition : transitions) {
            for (int i = 0; i < transition.weight(); i++) {
                expanded.add(transition);
            }
        }
        return expanded;
    }

    /** {@link AffixMath#seed}, salted with the theme string so a shared owner/depth does not collide. */
    private static long seed(UUID owner, String currentTheme, int depth) {
        long base = AffixMath.seed(owner, depth);
        long salt = currentTheme == null ? 0L : currentTheme.hashCode();
        return base * 0x9E3779B97F4A7C15L + salt;
    }
}
