package pocketdungeons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The pure half of dungeon trip door dealing (dungeon structure design D1, D3
 * to D6): what stands behind the three doors of a staging room. No Minecraft
 * imports, so {@code TripDoorsTest} runs with plain {@code javac}.
 *
 * <p>Two deals exist:
 * <ul>
 *   <li>{@link #dealFirst}: the first staging room of a trip. Each door offers a
 *       dungeon (its entry floor). Three distinct dungeons when the player can
 *       reach that many; fewer repeat across the spare doors.</li>
 *   <li>{@link #dealNext}: every later staging room. Each door is one out edge of
 *       the node just cleared. A node with fewer than three out edges fills the
 *       spare doors with a repeat of a branch (design D4), so the risk choice
 *       never vanishes.</li>
 * </ul>
 *
 * <p>Steps are dealt separately from branches: a seeded shuffle gives the three
 * doors {@code +1}, {@code +2} and {@code +3} in some order (D4). Every dungeon
 * deals them alike (D12 revised 2026-10-06): a miner's floors pay chart scrap
 * plus whatever the player mines. The seed
 * comes from the owner, the dungeon, the node and the trip's path length, so an
 * open preview and the commit behind it see the same deal, and the deal does
 * not change between renders.
 *
 * <p>Costs are scrap and ride on the edge (J1, old D5): a side edge costs its
 * authored {@code cost}; a main edge and a dungeon entry cost nothing.
 */
final class TripDoors {

    private TripDoors() {}

    /** The staging room's door count. */
    static final int DOOR_COUNT = 3;

    /**
     * One dealt door. {@code nodeId} is the floor behind it, {@code step} the
     * chart scrap steps it adds (1 to 3), {@code cost} the scrap
     * it takes. {@code dungeonId} is always set. {@code variant} is how many
     * earlier doors of the same deal lead to the same floor (0 for the first copy):
     * {@link DoorAffixes} rerolls a copy's affixes by it so no two doors are twins.
     * {@code pathLength} is the deal's seed input, kept on the door so the preview and
     * the commit behind it draw the same affixes.
     */
    record Door(String dungeonId, String nodeId, int step, int cost, int variant, int pathLength) {

        /** A door that is the first copy of its floor (variant 0), with no path length. */
        Door(String dungeonId, String nodeId, int step, int cost) {
            this(dungeonId, nodeId, step, cost, 0, 0);
        }

        boolean sideBranch() {
            return cost > 0;
        }
    }

    // ---- who may be offered ---------------------------------------------------------

    /**
     * The dungeons a trip may start in: ordinary and capstone kinds (never
     * endless, which is its own mode, D13) of an unlocked act, with one entry
     * node. Sorted by id so the deal is independent of load order.
     */
    static List<DungeonDef> eligibleFirst(Collection<DungeonDef> all, Set<Integer> unlockedActs) {
        List<DungeonDef> out = new ArrayList<>();
        for (DungeonDef def : all) {
            if (def.kind() != DungeonDef.Kind.ENDLESS && unlockedActs.contains(def.act()) && def.entry() != null) {
                out.add(def);
            }
        }
        out.sort(Comparator.comparing(DungeonDef::id));
        return out;
    }

    // ---- the deals --------------------------------------------------------------------

    /**
     * The first staging room's doors. {@code salt} varies the deal between trips
     * (the caller passes the leader's trip counter, {@code DungeonLog.Campaign#tripCounter},
     * which rises when a trip begins, even one that is quit before any clear) while
     * keeping it fixed during one staging room. Empty when {@code eligible} is empty.
     */
    static Door[] dealFirst(UUID owner, List<DungeonDef> eligible, int salt) {
        return dealFirst(owner, eligible, salt, null);
    }

    /**
     * The first staging room's doors with the capstone rule (design section 11): when
     * {@code guaranteed} is one of {@code eligible}, it is always behind door 1 or door 2 (a
     * seeded pick of the two), never only behind door 3, because the Endless Mine and an
     * operator's experimental offer both take door 3. If the shuffle already put it on door 1
     * or 2 nothing moves; if it landed on door 3 only, it swaps with the dungeon on the seeded
     * door; if it was not drawn at all it takes that door. Steps stay with their door slot.
     */
    static Door[] dealFirst(UUID owner, List<DungeonDef> eligible, int salt, DungeonDef guaranteed) {
        if (eligible.isEmpty()) {
            return new Door[0];
        }
        List<DungeonDef> shuffled = new ArrayList<>(eligible);
        Collections.shuffle(shuffled, new Random(seed(owner, "", "first", salt, 1)));
        int picked = Math.min(DOOR_COUNT, shuffled.size());
        long stepSeed = seed(owner, "", "first", salt, 2);
        DungeonDef[] chosen = new DungeonDef[DOOR_COUNT];
        for (int slot = 0; slot < DOOR_COUNT; slot++) {
            chosen[slot] = shuffled.get(slot % picked);
        }
        if (guaranteed != null && eligible.contains(guaranteed)) {
            guarantee(chosen, guaranteed, (int) Math.floorMod(seed(owner, "", "first", salt, 3), 2L));
        }
        Door[] doors = new Door[DOOR_COUNT];
        int[] steps = dealSteps(stepSeed);
        for (int slot = 0; slot < DOOR_COUNT; slot++) {
            DungeonDef def = chosen[slot];
            doors[slot] = new Door(def.id(), def.entry().id(), steps[slot], 0, 0, salt);
        }
        return assignVariants(doors);
    }

    /** Puts {@code wanted} on door {@code slot} (0 or 1) unless it already stands on door 0 or 1. */
    private static void guarantee(DungeonDef[] chosen, DungeonDef wanted, int slot) {
        if (chosen[0] == wanted || chosen[1] == wanted) {
            return;
        }
        if (chosen[2] == wanted) {
            chosen[2] = chosen[slot];
        }
        chosen[slot] = wanted;
    }

    // ---- the capstone offer rule -----------------------------------------------------

    /**
     * The capstone the first door must carry (design section 11): the capstone dungeon of the
     * lowest unlocked act whose capstone this player has not finished yet, or {@code null} when
     * every unlocked capstone is cleared (or none exists). A capstone of act N is only ever
     * offered once act N is open, because {@link #eligibleFirst} already filters by act; this
     * adds the guarantee on top, so a player who has opened an act but not cleared its capstone
     * is shown the capstone at the first door every trip until they do.
     *
     * @param finishedDungeons the ids (bare or namespaced) of the dungeons the player has finished
     */
    static DungeonDef pendingCapstone(Collection<DungeonDef> all, Set<Integer> unlockedActs,
                                      Set<String> finishedDungeons) {
        Set<String> finished = new LinkedHashSet<>();
        for (String id : finishedDungeons) {
            finished.add(DungeonDef.qualify(id));
        }
        DungeonDef best = null;
        for (DungeonDef def : all) {
            if (def.kind() != DungeonDef.Kind.CAPSTONE || def.entry() == null
                    || !unlockedActs.contains(def.act()) || finished.contains(DungeonDef.qualify(def.id()))) {
                continue;
            }
            if (best == null || def.act() < best.act()
                    || (def.act() == best.act() && def.id().compareTo(best.id()) < 0)) {
                best = def;
            }
        }
        return best;
    }

    /**
     * The doors out of {@code nodeId}: its out edges, in a seeded order, repeated
     * to fill all three doors. Empty when the node is unknown or has no edge out
     * (a final node).
     *
     * @param pathLength how many floors the trip has taken so far, part of the seed
     */
    static Door[] dealNext(UUID owner, DungeonDef def, String nodeId, int pathLength) {
        List<DungeonDef.Edge> edges = def.edgesFrom(nodeId);
        if (def.node(nodeId) == null || edges.isEmpty()) {
            return new Door[0];
        }
        long base = seed(owner, def.id(), nodeId, pathLength, 3);
        List<DungeonDef.Edge> shuffled = new ArrayList<>(edges);
        Collections.shuffle(shuffled, new Random(base));
        int[] steps = dealSteps(base ^ 0x5DEECE66DL);
        Door[] doors = new Door[DOOR_COUNT];
        for (int slot = 0; slot < DOOR_COUNT; slot++) {
            DungeonDef.Edge edge = shuffled.get(slot % shuffled.size());
            doors[slot] = new Door(def.id(), edge.to(), steps[slot], edge.cost(), 0, pathLength);
        }
        return assignVariants(doors);
    }

    /**
     * Numbers the copies: door {@code i}'s variant is how many earlier doors lead to
     * the same floor, so the first copy stays variant 0 (today's deal, unchanged).
     */
    static Door[] assignVariants(Door[] doors) {
        Door[] out = new Door[doors.length];
        for (int i = 0; i < doors.length; i++) {
            int earlier = 0;
            for (int j = 0; j < i; j++) {
                if (doors[j].dungeonId().equals(doors[i].dungeonId())
                        && doors[j].nodeId().equals(doors[i].nodeId())) {
                    earlier++;
                }
            }
            Door d = doors[i];
            out[i] = new Door(d.dungeonId(), d.nodeId(), d.step(), d.cost(), earlier, d.pathLength());
        }
        return out;
    }

    /** A seeded shuffle of {@code 1, 2, 3}: the step each door slot is dealt. */
    static int[] dealSteps(long seed) {
        List<Integer> steps = new ArrayList<>(List.of(1, 2, 3));
        Collections.shuffle(steps, new Random(seed));
        int[] out = new int[DOOR_COUNT];
        for (int i = 0; i < DOOR_COUNT; i++) {
            out[i] = steps.get(i);
        }
        return out;
    }

    /**
     * A stable, well spread seed for one deal. Strings hash by value, so the
     * result is the same across JVM runs and restarts.
     */
    static long seed(UUID owner, String dungeonId, String nodeId, int pathLength, int stream) {
        long h = owner.getMostSignificantBits() * 0x9E3779B97F4A7C15L + owner.getLeastSignificantBits();
        h = mix(h ^ dungeonId.hashCode());
        h = mix(h ^ ((long) nodeId.hashCode() << 17));
        h = mix(h ^ ((long) pathLength << 33) ^ stream);
        return h;
    }

    private static long mix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    // ---- what a door can reach --------------------------------------------------------

    /**
     * Every node reachable going forward from {@code nodeId}, itself included, in
     * breadth first order. The staging map's "this door can reach" lines.
     */
    static Set<String> reachableFrom(DungeonDef def, String nodeId) {
        Set<String> seen = new LinkedHashSet<>();
        if (def.node(nodeId) == null) {
            return seen;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        seen.add(nodeId);
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            for (DungeonDef.Edge edge : def.edgesFrom(at)) {
                if (seen.add(edge.to())) {
                    queue.add(edge.to());
                }
            }
        }
        return seen;
    }

    /** Whether {@code nodeId} exists in {@code def} and is a final floor. */
    static boolean isFinal(DungeonDef def, String nodeId) {
        DungeonDef.Node node = def == null ? null : def.node(nodeId);
        return node != null && node.isFinal();
    }

    /**
     * Whether every door out of {@code nodeId} leads to a final node: the party
     * is one floor from the end ("final floor ahead").
     */
    static boolean finalAhead(DungeonDef def, String nodeId) {
        List<DungeonDef.Edge> edges = def.edgesFrom(nodeId);
        if (edges.isEmpty()) {
            return false;
        }
        for (DungeonDef.Edge edge : edges) {
            if (!isFinal(def, edge.to())) {
                return false;
            }
        }
        return true;
    }
}
