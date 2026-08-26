package pocketdungeons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Affix set arithmetic: parsing, the level thresholds, the seeded pick, the
 * depletion multiplier and the keystone's name. No Minecraft imports -- same
 * discipline as {@link KeystoneMath}, {@link DoorMask} and
 * {@link DifficultyProfile}, and for the same reason: this is the part that is
 * easy to get subtly wrong and trivial to test with plain {@code javac}.
 *
 * <h2>Two questions, one mechanism</h2>
 *
 * <p><em>How many</em> affixes a key carries is the level thresholds
 * {@code 5 / 11 / 17} ({@link #seededCount}). <em>Which</em> ones fill those slots
 * is seeded from the key itself ({@link #seededFor}). A door choice adds one more
 * on top of both, electively, at any level.
 *
 * <p>There is deliberately <strong>no weekly rotation</strong>
 * ({@code docs/MYTHIC_PLUS_RECONCILIATION.md} section 4). A wall-clock seed would make
 * the keystone's name drift under the instance watcher, which rewrites stale
 * remotes in place; seeding from the key keeps the name a pure function of
 * {@code (level, affixSet)}.
 *
 * <h2>Why the seeded affixes are not persisted</h2>
 *
 * <p>{@link DungeonLog} stores the <em>elective</em> affixes only. The seeded ones
 * are re-derived from {@code (owner, level)} on every read, which costs nothing
 * and buys two things: no codec field to migrate, and a depleted key that
 * correctly stops carrying affixes its new level no longer earns.
 */
final class AffixMath {

    /** Levels at which the first, second and third seeded affix arrive. */
    private static final int FIRST = 5;
    private static final int SECOND = 11;
    private static final int THIRD = 17;

    private AffixMath() {}

    // ---- storage ------------------------------------------------------------

    /**
     * Parses the comma-joined form {@link DungeonLog} and the item tag store.
     *
     * <p>Lenient by design, and that is what makes M4 migration-free: a save
     * written before affixes stacked holds {@code "ominous"}, which reads back as
     * a one-element set, and {@code ""} reads back as an empty one. An unknown
     * word -- a renamed affix, a hand-edited save -- is dropped rather than
     * thrown on, because the alternative is a player who cannot log in.
     */
    static EnumSet<Affix> parse(String joined) {
        EnumSet<Affix> set = EnumSet.noneOf(Affix.class);
        if (joined == null || joined.isBlank()) {
            return set;
        }
        for (String part : joined.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            for (Affix affix : Affix.values()) {
                if (affix.name().equalsIgnoreCase(name)) {
                    set.add(affix);
                    break;
                }
            }
        }
        return set;
    }

    /** The inverse of {@link #parse}, in enum order so the string is stable. */
    static String join(Set<Affix> affixes) {
        StringBuilder out = new StringBuilder();
        for (Affix affix : ordered(affixes)) {
            if (!out.isEmpty()) {
                out.append(",");
            }
            out.append(affix.name().toLowerCase());
        }
        return out.toString();
    }

    /** The set in enum declaration order -- the only order anything renders in. */
    static List<Affix> ordered(Set<Affix> affixes) {
        List<Affix> out = new ArrayList<>();
        if (affixes == null || affixes.isEmpty()) {
            return out;
        }
        for (Affix affix : Affix.values()) {
            if (affixes.contains(affix)) {
                out.add(affix);
            }
        }
        return out;
    }

    // ---- thresholds ---------------------------------------------------------

    /**
     * How many seeded affixes a key of this level carries: {@code 5 / 11 / 17}.
     *
     * <p>Not {@code 5 / 10 / 15}. That leaves levels 15-25 flat -- ten levels in
     * which nothing about a run changes, sitting exactly where the most invested
     * players live. These three spread the changes across the whole ladder, with a
     * largest gap of six.
     */
    static int seededCount(int level) {
        if (level >= THIRD) {
            return 3;
        }
        if (level >= SECOND) {
            return 2;
        }
        return level >= FIRST ? 1 : 0;
    }

    /**
     * The seeded affixes for one player's key at one level.
     *
     * <p>Takes {@code owner} and {@code level} rather than a seed so that no caller
     * can feed it run randomness by accident: the answer has to be the same every
     * time the watcher asks, and the layout's seed is freshly rolled per run.
     */
    static EnumSet<Affix> seededFor(UUID owner, int level) {
        EnumSet<Affix> picked = EnumSet.noneOf(Affix.class);
        int count = seededCount(level);
        if (count <= 0) {
            return picked;
        }
        List<Affix> pool = new ArrayList<>();
        for (Affix affix : Affix.values()) {
            if (affix.kind == Affix.Kind.SEEDED) {
                pool.add(affix);
            }
        }
        Collections.shuffle(pool, new Random(seed(owner, level)));
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            picked.add(pool.get(i));
        }
        return picked;
    }

    /**
     * Everything riding on a key: what the player opted into, plus what the level
     * hands them. This is what every consumer actually wants.
     */
    static EnumSet<Affix> effective(UUID owner, int level, Set<Affix> elective) {
        EnumSet<Affix> set = seededFor(owner, level);
        if (elective != null) {
            set.addAll(elective);
        }
        return set;
    }

    /** Only the affixes a player chose, which is all {@link DungeonLog} stores. */
    static EnumSet<Affix> elective(Set<Affix> affixes) {
        EnumSet<Affix> set = EnumSet.noneOf(Affix.class);
        if (affixes != null) {
            for (Affix affix : affixes) {
                if (affix.kind == Affix.Kind.ELECTIVE) {
                    set.add(affix);
                }
            }
        }
        return set;
    }

    /**
     * A stable, well-spread seed for {@code (owner, level)}.
     *
     * <p>{@code java.util.Random} correlates badly on low-entropy seeds and the
     * pick here is a shuffle of five elements, so the UUID's two halves and the
     * level go through a SplitMix64-style finaliser before they reach the
     * constructor rather than straight into it.
     */
    static long seed(UUID owner, int level) {
        long mixed = owner == null ? 0L
                : owner.getMostSignificantBits() * 31L + owner.getLeastSignificantBits();
        mixed = mixed * 0x9E3779B97F4A7C15L + level;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    // ---- depletion ----------------------------------------------------------

    /**
     * What a failure costs, as a multiplier over the whole set.
     *
     * <p><strong>The max, capped at two. Never the sum, never the product.</strong>
     * A level-20 key must not shed most of a ladder on one bad night: loss aversion
     * already runs at roughly twice the felt weight of an equivalent gain, so a
     * doubled depletion is felt as roughly quadrupled, and a product of two
     * doubling affixes would be unrecoverable in an evening.
     */
    static int depletionMultiplier(Set<Affix> affixes) {
        int max = 1;
        if (affixes != null) {
            for (Affix affix : affixes) {
                max = Math.max(max, affix.depletionMultiplier);
            }
        }
        return Math.min(2, max);
    }

    // ---- naming -------------------------------------------------------------

    /**
     * The intensifier band for a level: Baby 1-5, Lowkey 6-10, Highkey 11-15,
     * Menace 16-20, Unhinged 21 and up.
     *
     * <p>Kamu Totems' <em>convention</em>, with entirely separate words -- same
     * machinery, no shared code and no shared vocabulary, per
     * ({@code kamutotems/INTEGRATION.md}'s stranger rule).
     */
    static String intensifier(int level) {
        if (level <= 5) {
            return "Baby";
        }
        if (level <= 10) {
            return "Lowkey";
        }
        if (level <= 15) {
            return "Highkey";
        }
        return level <= 20 ? "Menace" : "Unhinged";
    }

    /**
     * {@code <intensifier> <affix> Keystone [<level>]}, with any remaining affixes
     * in a bracketed subtitle -- the {@code <title> [<subtitle>]} shape
     * {@code BossNames.build} uses next door.
     *
     * <p>A pure function of its arguments, with no randomness anywhere in it. The
     * watcher rewrites remotes in place, so a label that rolled anything would
     * churn on every reconciliation.
     */
    static String name(int level, Set<Affix> affixes) {
        String head = intensifier(level);
        List<Affix> ordered = ordered(affixes);
        if (ordered.isEmpty()) {
            return head + " Keystone [" + level + "]";
        }
        String title = head + " " + ordered.get(0).label + " Keystone [" + level + "]";
        if (ordered.size() == 1) {
            return title;
        }
        StringBuilder subtitle = new StringBuilder();
        for (int i = 1; i < ordered.size(); i++) {
            if (i > 1) {
                subtitle.append(", ");
            }
            subtitle.append(ordered.get(i).label);
        }
        return title + " [" + subtitle + "]";
    }
}
