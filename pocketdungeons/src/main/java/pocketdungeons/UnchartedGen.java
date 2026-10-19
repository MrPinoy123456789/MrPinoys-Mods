package pocketdungeons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The uncharted door generator (decision 2026-10-18, "Campaign and endgame"; spec T5). The Chart Room's three
 * doors each lead to a {@link DungeonDef} built here in memory and never written to data: three floors in a
 * single line, themed from the acts the player has finished, levelled to their compass, ending in a guardian
 * finale. No Minecraft imports, so {@code UnchartedGenTest} runs with plain {@code javac}; {@link UnchartedDoors}
 * is the server side that feeds it the player and remembers what it made.
 *
 * <p>The rules, each of which the test pins:
 * <ul>
 *   <li>The id is {@code pocketdungeons:uncharted_<salt>}, the salt a hash of the owner, the trip counter, the
 *       door slot and the compass. Same inputs, same door; a trip (which raises the counter) rerolls all three.</li>
 *   <li>Three nodes on three layers, one edge each way on the main path, the last node final. The entry and final
 *       floors share one theme (the deviation guardrail); the middle floor may borrow another.</li>
 *   <li>Every theme is the main theme of an ordinary dungeon of an act the player has finished (a capstone's,
 *       a descent's and Act 0's never); the three doors start on three different themes when the player has
 *       finished three or more.</li>
 *   <li>Every floor runs at the player's compass; the door step adds to it at the board, as for any door.</li>
 *   <li>The loot band is the band of the highest act finished (D10).</li>
 * </ul>
 * The affix rule (at least half from the rule kind) lives in {@link #affixes}.
 */
final class UnchartedGen {

    private UnchartedGen() {}

    /** The id prefix of every generated dungeon. */
    static final String ID_PREFIX = DungeonDef.DEFAULT_NAMESPACE + ":uncharted_";

    /** The Chart Room's door count. */
    static final int DOORS = 3;

    /** Floors in an uncharted dungeon. */
    static final int FLOORS = 3;

    /** The node ids, entry to final. */
    static final String ENTRY_NODE = "gate";
    static final String MIDDLE_NODE = "deep";
    static final String FINAL_NODE = "guardian";

    /** The doormat block of each door slot (0 based), distinct from every shipped dungeon's token. */
    private static final String[] TOKEN_MATS = {
            "minecraft:amethyst_block", "minecraft:crying_obsidian", "minecraft:smooth_basalt"};

    /** Most salts tried when a repeated door must differ from its twin. */
    static final int MAX_ATTEMPTS = 16;

    /** Whether {@code id} (bare or namespaced) names a generated uncharted dungeon. */
    static boolean isUnchartedId(String id) {
        return id != null && DungeonDef.qualify(id).startsWith(ID_PREFIX);
    }

    /** Everything one generated dungeon is a function of. */
    record Input(UUID owner, int trip, int slot, int compass, int maxAct) {

        Input {
            slot = Math.floorMod(slot, DOORS);
            compass = Math.max(1, compass);
        }
    }

    // ---- which acts and themes the player has earned ------------------------------------------

    /** The acts 1 to {@code maxAct} whose capstone {@code finished} holds, ascending. */
    static List<Integer> finishedActs(Set<String> finished, Collection<DungeonDef> all, int maxAct) {
        List<Integer> out = new ArrayList<>();
        for (int act = DungeonDef.MIN_ACT; act <= Math.min(maxAct, DungeonDef.MAX_ACT); act++) {
            if (ActProgress.capstoneFinished(act, finished, all)) {
                out.add(act);
            }
        }
        return out;
    }

    /**
     * The dungeons whose main theme an uncharted floor may use: ordinary dungeons (kind dungeon, never a
     * capstone, a descent or a generated door) of the finished acts, one per main theme, sorted by id.
     */
    static List<DungeonDef> sources(List<Integer> acts, Collection<DungeonDef> all) {
        List<DungeonDef> out = new ArrayList<>();
        Set<String> themes = new LinkedHashSet<>();
        List<DungeonDef> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(DungeonDef::id));
        for (DungeonDef def : sorted) {
            if (def.kind() == DungeonDef.Kind.DUNGEON && acts.contains(def.act()) && def.entry() != null
                    && !isUnchartedId(def.id()) && themes.add(DungeonDef.qualify(def.mainTheme()))) {
                out.add(def);
            }
        }
        return out;
    }

    /** The loot band of the highest act in {@code acts}: the widest band any dungeon of that act declares. */
    static DungeonDef.LootBand bandOf(List<Integer> acts, Collection<DungeonDef> all) {
        int top = acts.isEmpty() ? DungeonDef.MIN_ACT : Collections.max(acts);
        int min = DungeonDef.MAX_TIER;
        int max = DungeonDef.MIN_TIER;
        boolean any = false;
        for (DungeonDef def : all) {
            if (def.act() == top && !def.kind().descends() && !isUnchartedId(def.id())) {
                min = Math.min(min, def.lootBand().min());
                max = Math.max(max, def.lootBand().max());
                any = true;
            }
        }
        return any ? new DungeonDef.LootBand(min, max) : new DungeonDef.LootBand(DungeonDef.MIN_TIER, DungeonDef.MIN_TIER);
    }

    // ---- the generator -----------------------------------------------------------------------

    /** The hash that names and seeds door {@code in}. */
    static long salt(Input in) {
        return TripDoors.seed(in.owner(), "uncharted", "slot" + in.slot(), in.trip() * 8191 + in.compass(), 77);
    }

    /** The id of the door {@code in} would generate (no data needed). */
    static String idOf(Input in) {
        return ID_PREFIX + Long.toHexString(salt(in) & 0xFFFFFFFFFFFL);
    }

    /**
     * The three doors of a trip, slot 0 to 2, or an empty list when the player has finished no act that
     * offers a theme.
     */
    static List<DungeonDef> doors(UUID owner, int trip, int compass, int maxAct, Set<String> finished,
                                  Collection<DungeonDef> all) {
        List<DungeonDef> out = new ArrayList<>();
        for (int slot = 0; slot < DOORS; slot++) {
            DungeonDef def = generate(new Input(owner, trip, slot, compass, maxAct), finished, all);
            if (def != null) {
                out.add(def);
            }
        }
        return out;
    }

    /** One generated dungeon, or {@code null} when the finished acts offer no theme. */
    static DungeonDef generate(Input in, Set<String> finished, Collection<DungeonDef> all) {
        List<Integer> acts = finishedActs(finished, all, in.maxAct());
        List<DungeonDef> sources = sources(acts, all);
        if (acts.isEmpty() || sources.isEmpty()) {
            return null;
        }
        long salt = salt(in);
        // One order for the whole trip, so the three doors start on three different themes.
        List<DungeonDef> order = new ArrayList<>(sources);
        Collections.shuffle(order, new Random(TripDoors.seed(in.owner(), "uncharted", "themes", in.trip(), 78)));
        DungeonDef main = order.get(in.slot() % order.size());
        Random random = new Random(salt);
        DungeonDef middle = order.get(random.nextInt(order.size()));
        boolean borrows = !DungeonDef.qualify(middle.mainTheme()).equals(DungeonDef.qualify(main.mainTheme()));

        int level = in.compass();
        String objective = middleObjective(salt);
        List<DungeonDef.Node> nodes = List.of(
                node(ENTRY_NODE, "The Gate", 1, "", false, level, DungeonDef.Node.OBJECTIVE_SPAWNERS, ""),
                node(MIDDLE_NODE, "The Deep", 2, borrows ? DungeonDef.qualify(middle.mainTheme()) : "", false, level,
                        objective, ""),
                node(FINAL_NODE, "The Guardian", 3, "", true, level, DungeonDef.Node.OBJECTIVE_SPAWNERS,
                        "The last floor. A guardian waits."));
        List<DungeonDef.Edge> edges = List.of(
                new DungeonDef.Edge(ENTRY_NODE, MIDDLE_NODE, 0), new DungeonDef.Edge(MIDDLE_NODE, FINAL_NODE, 0));

        Set<String> palette = new LinkedHashSet<>();
        for (DungeonDef source : borrows ? List.of(main, middle) : List.of(main)) {
            for (String block : source.nodePalette()) {
                if (!DungeonDef.isStructuralBlock(block)) {
                    palette.add(block);
                }
            }
        }
        int act = Collections.max(acts);
        return new DungeonDef(idOf(in), "Uncharted: " + prettyTheme(main.mainTheme()), act,
                DungeonDef.Kind.UNCHARTED, DungeonDef.qualify(main.mainTheme()), bandOf(acts, all),
                new ArrayList<>(palette), "", "", null, nodes, edges, null, 0, level,
                "Three floors, then a guardian.", HallLayout.HALL_ACT, TOKEN_MATS[in.slot()],
                finaleFor(act), null);
    }

    private static DungeonDef.Node node(String id, String name, int layer, String theme, boolean isFinal, int level,
                                        String objective, String notes) {
        return new DungeonDef.Node(id, name, layer, theme, "", List.of(), 0, isFinal, DungeonDef.Node.LIGHT_LIT,
                List.of(), DungeonDef.Node.INHERIT_NODE_ROOMS, level, notes, objective);
    }

    /**
     * The objective of the middle floor. Always the ordinary spawner gate today; the heavy thing (task T7, a
     * relic carried to the pad) replaces this one method with a seeded roll at {@code relicFloorChance}, and
     * nothing else in the generator changes.
     */
    static String middleObjective(long salt) {
        return DungeonDef.Node.OBJECTIVE_SPAWNERS;
    }

    /**
     * The data wave behind the final floor's guardian: what runs if the guardian itself cannot spawn
     * ({@link UnchartedFinale}). Husks and skeletons for a finished Act 2 or later, zombies before.
     */
    static DungeonDef.Finale finaleFor(int act) {
        String melee = act >= 2 ? "minecraft:husk" : "minecraft:zombie";
        return new DungeonDef.Finale(
                List.of(new DungeonDef.FinaleMob(melee, 4), new DungeonDef.FinaleMob("minecraft:skeleton", 2)), 50,
                new DungeonDef.Elite("minecraft:vindicator", "The Guardian", 60, "minecraft:iron_axe"));
    }

    /** {@code pocketdungeons:copper_works} as {@code Copper Works}. */
    static String prettyTheme(String theme) {
        String path = theme.substring(theme.indexOf(':') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }

    /** The guardian slot (1 to 3) of a generated dungeon, from its id: stable for the dungeon's life. */
    static int guardianSlot(String dungeonId) {
        return Math.floorMod(DungeonDef.qualify(dungeonId).hashCode(), 3) + 1;
    }

    // ---- affixes ------------------------------------------------------------------------------

    /**
     * The affixes the door of {@code level} deals on a generated dungeon. The count is the ordinary level
     * threshold ({@link AffixMath#seededCount}); at least half of them, rounded up, come from the rule kind
     * ({@link AffixDefinition#KIND_RULE}) when enough rule affixes are eligible, the rest from the whole pool.
     * Eligible means {@code minLevel <= level} and not incompatible with an affix already chosen (either
     * side's list). Deterministic for one {@code seed}.
     */
    static Set<String> affixes(long seed, int level, List<AffixDefinition> definitions) {
        Set<String> picked = new LinkedHashSet<>();
        int count = AffixMath.seededCount(level);
        if (count <= 0 || definitions == null) {
            return picked;
        }
        Random random = new Random(seed);
        List<AffixDefinition> rules = new ArrayList<>();
        List<AffixDefinition> rest = new ArrayList<>();
        for (AffixDefinition def : definitions) {
            if (level < def.minLevel) {
                continue;
            }
            (AffixDefinition.KIND_RULE.equals(def.kind) ? rules : rest).add(def);
        }
        Collections.shuffle(rules, random);
        Collections.shuffle(rest, random);
        int needRules = (count + 1) / 2;
        List<AffixDefinition> chosen = new ArrayList<>();
        List<AffixDefinition> leftover = new ArrayList<>();
        for (AffixDefinition def : rules) {
            if (chosen.size() < needRules && compatible(def, chosen)) {
                chosen.add(def);
            } else {
                leftover.add(def);
            }
        }
        List<AffixDefinition> others = new ArrayList<>(rest);
        others.addAll(leftover);
        Collections.shuffle(others, random);
        for (AffixDefinition def : others) {
            if (chosen.size() >= count) {
                break;
            }
            if (compatible(def, chosen)) {
                chosen.add(def);
            }
        }
        for (AffixDefinition def : chosen) {
            picked.add(def.id);
        }
        return picked;
    }

    private static boolean compatible(AffixDefinition def, List<AffixDefinition> chosen) {
        for (AffixDefinition other : chosen) {
            if (def.incompatible.contains(other.id) || other.incompatible.contains(def.id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The final affix set for the door of {@code variant} on a generated dungeon's {@code nodeId}: the
     * {@link #affixes} draw seeded from the owner, the dungeon, the node and the level. A repeat of the same
     * floor (variant above 0) draws again under a new salt until it differs from its earlier twins, up to
     * {@link #MAX_ATTEMPTS}, the way {@link DoorAffixes#deal} rerolls an ordinary door.
     */
    static Set<String> deal(UUID owner, int level, List<AffixDefinition> definitions, String dungeonId,
                            String nodeId, int variant, int pathLength) {
        List<Set<String>> earlier = new ArrayList<>();
        Set<String> last = null;
        for (int copy = 0; copy <= Math.max(0, variant); copy++) {
            last = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                long salt = TripDoors.seed(owner, dungeonId, nodeId, pathLength, 20 + copy * MAX_ATTEMPTS + attempt);
                Set<String> candidate = affixes(salt, level, definitions);
                last = candidate;
                if (!earlier.contains(candidate)) {
                    break;
                }
            }
            earlier.add(last);
        }
        return last == null ? new LinkedHashSet<>() : last;
    }

    /** The ids of the rule kind among {@code ids}, for tests and the board. */
    static Set<String> ruleIds(Set<String> ids, List<AffixDefinition> definitions) {
        Set<String> out = new TreeSet<>();
        for (AffixDefinition def : definitions) {
            if (ids.contains(def.id) && AffixDefinition.KIND_RULE.equals(def.kind)) {
                out.add(def.id);
            }
        }
        return out;
    }
}
