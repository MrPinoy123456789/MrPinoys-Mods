package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M47 (SITUATIONS_SPEC 6.3 and 6.6): the root-distance solvability invariant,
 * asserted over every cell of every floor in a seed sweep, and over one
 * hand-built fixture per edge case the spec lists.
 *
 * <p>The invariant:
 *
 * <blockquote>A cell at distance n may only {@code require} capabilities that
 * are {@code provides} of some cell at distance &lt; n, or of the bag.</blockquote>
 *
 * <p>The sweep runs it as spec 6.3's Pilgrim test: an empty bag and a party of
 * one, which is the strictest seed there is, so a floor that passes here is
 * solvable for all eight bags. Spurs are included, which is the whole reason
 * 6.6 exists and 6.2 was not enough: a gated cell three cells down an optional
 * branch is a cell some player will stand in front of.
 *
 * <p>Every failure prints the seed and the cell. A solvability failure that
 * does not is a failure nobody can reproduce.
 *
 * <p>Pure JDK. No server, no Minecraft world: {@link LayoutGraphGenerator}
 * makes the shapes and the manifests below are built by hand.
 */
public class GraphSolvabilityTest {

    /** Spec 6.3 and the plan's "done when": at least 500 seeds on the Pilgrim pass. */
    private static final int SWEEP_SEEDS = 600;

    /** A tag nothing in the sweep manifest provides and no bag carries. */
    private static final String UNREACHABLE_TAG = SituationTags.BOAT;

    public static void main(String[] args) {
        // M70: publish a synthetic bag manifest so BagTags.seed reads the
        // same tags the live server would load from dungeon_bag/*.json. The
        // headless test has no Minecraft server, so the manifest is built by
        // hand from the built-in ids and their pre-M70 tag sets.
        publishSyntheticBagManifest();
        publishSyntheticRoleManifest();
        testBagSeed();
        testPilgrimSweep();
        testTierSweep();
        testScarceSweep();
        testLoopTakesTheShorterDistance();
        testBagIsDepthZero();
        testSameDepthProvidesDoNotCount();
        testMultipleRequiresMeansAllOfThem();
        testAvailableIsABooleanSet();
        testBacktrackingRecoversWhatGreedyLoses();
        testUnsatisfiableRequiresNeverLands();
        testConsumableNeverGatesTheSpine();
        testAccessPlacement();
        System.out.println("GraphSolvabilityTest passed");
    }

    // ---- the seed the pass starts from ----

    private static void testBagSeed() {
        if (!BagTags.pilgrim().isEmpty()) {
            throw new AssertionError("the Pilgrim seed must be empty, got " + BagTags.pilgrim());
        }
        if (!BagTags.seed("pilgrim", 1).isEmpty()) {
            throw new AssertionError("a solo Pilgrim carries nothing, got " + BagTags.seed("pilgrim", 1));
        }
        if (!BagTags.seed("pilgrim", 2).equals(Set.of(SituationTags.MOB))) {
            throw new AssertionError("party size 2 grants mob and nothing else for Pilgrim, got "
                    + BagTags.seed("pilgrim", 2));
        }
        if (!BagTags.seed("plumber", 1).contains(SituationTags.WATER)) {
            throw new AssertionError("the Plumber's bag carries water, got " + BagTags.seed("plumber", 1));
        }
        if (!BagTags.seed("nonesuch", 3).equals(Set.of(SituationTags.MOB))) {
            throw new AssertionError("an unknown bag seeds nothing but the party's own mob, got "
                    + BagTags.seed("nonesuch", 3));
        }
    }

    // ---- spec 6.3: the Pilgrim sweep ----

    /**
     * Builds a floor per seed, resolves it with an empty bag and a party of
     * one, and asserts the 6.6 invariant on every cell. Also asserts that the
     * room whose {@code requires} nothing in the manifest can satisfy is never
     * placed anywhere, on any seed, which is the plan's "a cell with
     * unsatisfiable requires provably never lands anywhere in the graph".
     */
    private static void testPilgrimSweep() {
        RoomManifest manifest = sweepManifest();
        Set<String> bag = BagTags.pilgrim();

        int floors = 0;
        int cells = 0;
        int fallbackCells = 0;
        int backtrackedFloors = 0;
        int maxSteps = 0;
        int taggedPlacements = 0;
        long nanos = 0;

        for (long seed = 0; seed < SWEEP_SEEDS; seed++) {
            DungeonShape shape = LayoutGraphGenerator.generate(seed, 8, 12);
            if (shape == null) {
                continue;
            }
            long start = System.nanoTime();
            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, bag);
            nanos += System.nanoTime() - start;

            if (result.plan() == null) {
                throw new AssertionError("seed " + seed + ": resolution failed at "
                        + result.failure());
            }
            floors++;
            cells += shape.cells().size();
            fallbackCells += result.fallbackCells().size();
            maxSteps = Math.max(maxSteps, result.backtrackSteps());
            if (result.backtrackSteps() > 0) {
                backtrackedFloors++;
            }
            taggedPlacements += assertInvariant(seed, shape, result.plan(), manifest, bag,
                    result.fallbackCells());

            for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> entry : result.plan().rooms().entrySet()) {
                if (entry.getValue().name().startsWith("unreachable_")) {
                    throw new AssertionError("seed " + seed + ": cell " + entry.getKey()
                            + " took " + entry.getValue().name()
                            + ", whose requires nothing on this floor can satisfy");
                }
            }
        }

        if (floors < 500) {
            throw new AssertionError("expected at least 500 resolvable floors, got " + floors);
        }
        if (taggedPlacements == 0) {
            throw new AssertionError("no room with a non-empty requires was ever placed, so the "
                    + "sweep proved nothing about the filter");
        }
        System.out.printf(
                "Pilgrim sweep: %d floors, %d cells, %d placements with a non-empty requires, "
                + "%d fallback cells, %d floors backtracked (max %d steps), %.1f ms total, "
                + "%.3f ms per floor%n",
                floors, cells, taggedPlacements, fallbackCells, backtrackedFloors, maxSteps,
                nanos / 1_000_000.0, nanos / 1_000_000.0 / floors);
    }

    /**
     * M64: the sweep run for every admitted loot tier (1, 2, 3), using solo
     * Pilgrim. Each tier uses a manifest that includes only rooms whose
     * {@code tier} field admits them at that level: tier 1 sees only tier-1
     * rooms, tier 2 sees tiers 1 and 2, tier 3 sees all.
     *
     * <p>The invariant is the same as the Pilgrim sweep: zero unresolved plans
     * and zero inaccessible mandatory exits (the terminal cell must be
     * reachable from the entrance through open edges, which
     * {@link RoomSelector#validate} checks).
     *
     * <p><strong>RoomSelector.preferred relaxes minDepth/maxPerDungeon today;
     * never use preferences as safety gates.</strong> The depth and repeat
     * filters in {@code preferred} are dropped when they would empty a non-empty
     * match list, which is the right call for variety but means they cannot be
     * relied on to prevent an unsolvable placement. The 6.6 subset filter is
     * the safety gate, not the preferences.
     */
    private static void testTierSweep() {
        for (int tier = 1; tier <= 3; tier++) {
            RoomManifest manifest = tierManifest(tier);
            Set<String> bag = BagTags.pilgrim();
            int floors = 0;
            int fallbackCells = 0;

            for (long seed = 0; seed < 500; seed++) {
                DungeonShape shape = LayoutGraphGenerator.generate(seed, 8, 12);
                if (shape == null) {
                    continue;
                }
                RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, bag);

                if (result.plan() == null) {
                    throw new AssertionError("tier " + tier + " seed " + seed
                            + ": resolution failed at " + result.failure());
                }
                floors++;
                fallbackCells += result.fallbackCells().size();

                // Zero inaccessible mandatory exits: the terminal must be
                // reachable from the entrance through open edges.
                List<String> problems = RoomSelector.validate(result.plan(), 16);
                for (String problem : problems) {
                    throw new AssertionError("tier " + tier + " seed " + seed
                            + ": " + problem);
                }

                assertInvariant(seed, shape, result.plan(), manifest, bag,
                        result.fallbackCells());
            }

            if (floors < 400) {
                throw new AssertionError("tier " + tier + ": expected at least 400 resolvable "
                        + "floors in seeds 0-499, got " + floors);
            }
            System.out.printf(
                    "Tier %d sweep: %d floors, %d fallback cells, zero unresolved, "
                            + "zero inaccessible exits%n",
                    tier, floors, fallbackCells);
        }
    }

    /**
     * The same sweep against a mean catalogue: one provider that is outweighed
     * nine to one by a room providing nothing, and consumers that outweigh the
     * plain room twenty to one. Greedy strands a gate here constantly, so this
     * is where the backtracker actually runs and where its cost gets measured.
     * The spec's estimate is "milliseconds"; the printed line is the number.
     */
    private static void testScarceSweep() {
        RoomManifest manifest = scarceManifest();
        Set<String> bag = BagTags.pilgrim();

        int floors = 0;
        int fallbackCells = 0;
        int backtrackedFloors = 0;
        int maxSteps = 0;
        long nanos = 0;
        long slowestNanos = 0;
        long slowestSeed = -1;

        for (long seed = 0; seed < 300; seed++) {
            DungeonShape shape = LayoutGraphGenerator.generate(seed, 8, 12);
            if (shape == null) {
                continue;
            }
            long start = System.nanoTime();
            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, bag);
            long elapsed = System.nanoTime() - start;
            nanos += elapsed;
            if (elapsed > slowestNanos) {
                slowestNanos = elapsed;
                slowestSeed = seed;
            }

            if (result.plan() == null) {
                throw new AssertionError("seed " + seed + ": resolution failed at "
                        + result.failure());
            }
            floors++;
            fallbackCells += result.fallbackCells().size();
            maxSteps = Math.max(maxSteps, result.backtrackSteps());
            if (result.backtrackSteps() > 0) {
                backtrackedFloors++;
            }
            assertInvariant(seed, shape, result.plan(), manifest, bag, result.fallbackCells());
        }

        if (backtrackedFloors == 0) {
            throw new AssertionError("the scarce catalogue should have forced the backtracker to "
                    + "run; no floor backtracked, so this sweep measured nothing");
        }
        System.out.printf(
                "Scarce sweep: %d floors, %d fallback cells, %d floors backtracked (max %d steps), "
                + "%.1f ms total, %.3f ms per floor, slowest %.3f ms at seed %d%n",
                floors, fallbackCells, backtrackedFloors, maxSteps, nanos / 1_000_000.0,
                nanos / 1_000_000.0 / floors, slowestNanos / 1_000_000.0, slowestSeed);
    }

    /**
     * Asserts the invariant on one resolved floor, cell by cell.
     *
     * @return how many placed rooms had a non-empty {@code requires}, so the
     *         caller can prove the sweep exercised the filter rather than
     *         passing because every room asked for nothing
     */
    private static int assertInvariant(long seed, DungeonShape shape, DungeonPlan plan,
                                       RoomManifest manifest, Set<String> bag,
                                       Set<PlanCell> fallbacks) {
        Map<PlanCell, Integer> distances = shape.rootDistances();
        int tagged = 0;

        for (PlanCell cell : shape.cells()) {
            DungeonPlan.PlacedRoom room = plan.rooms().get(cell);
            if (room == null) {
                throw new AssertionError("seed " + seed + ": cell " + cell + " has no room");
            }
            DungeonRoomMeta meta = manifest.byName(room.name()).meta;
            if (fallbacks.contains(cell)) {
                // 6.6 step 6: this cell had no satisfiable candidate and took a
                // role-only room instead. The invariant is knowingly waived
                // here, and the only thing worth asserting is that the waiver
                // was necessary: a room asking for nothing would have been
                // taken if the manifest had one for this mask and role.
                if (!meta.requires.isEmpty() && hasUndemandingRoom(manifest, shape, cell)) {
                    throw new AssertionError("seed " + seed + ": cell " + cell + " fell back to "
                            + room.name() + ", which requires " + meta.requires
                            + ", when a room requiring nothing was available");
                }
                continue;
            }
            if (meta.requires.isEmpty()) {
                continue;
            }
            tagged++;

            int depth = distances.get(cell);
            Set<String> available = new LinkedHashSet<>(bag);
            for (PlanCell other : shape.cells()) {
                if (distances.get(other) < depth) {
                    available.addAll(manifest.byName(plan.rooms().get(other).name()).meta.provides);
                }
            }
            if (!available.containsAll(meta.requires)) {
                List<String> missing = new ArrayList<>(meta.requires);
                missing.removeAll(available);
                throw new AssertionError("seed " + seed + ": cell " + cell + " at root distance "
                        + depth + " took " + room.name() + ", which requires " + meta.requires
                        + " but " + missing + " is provided by nothing at a smaller distance"
                        + " (available: " + available + ")");
            }
        }
        return tagged;
    }

    /**
     * Whether the manifest holds a room with an empty {@code requires} that
     * fits this cell's walls and role, which is what 6.6 step 6's fallback
     * reaches for before it gives up and takes anything at all.
     */
    private static boolean hasUndemandingRoom(RoomManifest manifest, DungeonShape shape,
                                              PlanCell cell) {
        for (RoomManifest.Match match : manifest.queryAnyRotation(mask(shape, cell),
                shape.roles().get(cell), null)) {
            if (match.entry().meta.requires.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The door mask a cell needs, from its open edges. */
    private static int mask(DungeonShape shape, PlanCell cell) {
        Set<DoorMask.Direction> dirs = new LinkedHashSet<>();
        for (PlanCell neighbor : neighbors(shape, cell)) {
            DoorMask.Direction dir = cell.directionTo(neighbor);
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return DoorMask.fromEdges(dirs);
    }

    // ---- spec 6.6's edge cases, one fixture each ----

    /**
     * <strong>Loops.</strong> A cell reachable two ways takes the shorter
     * distance, which is the strictest reading.
     *
     * <p>The fixture is a six-cell ring. The encounter cell sits two steps
     * clockwise from the entrance and four steps anticlockwise; the only room
     * that provides water is the loot cell at distance three. Under the shorter
     * distance the encounter cell cannot have water and must take the plain
     * room. Under the longer one it could, so a pass that used the wrong
     * distance would place the water room here and fail this test.
     */
    private static void testLoopTakesTheShorterDistance() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell east = new PlanCell(1, 0);
        PlanCell corner = new PlanCell(2, 0);
        PlanCell far = new PlanCell(2, 1);
        PlanCell back = new PlanCell(1, 1);
        PlanCell south = new PlanCell(0, 1);

        Set<PlanCell> cells = new LinkedHashSet<>(
                List.of(entrance, east, corner, far, back, south));
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                new PlanEdge(entrance, east), new PlanEdge(east, corner),
                new PlanEdge(corner, far), new PlanEdge(far, back),
                new PlanEdge(back, south), new PlanEdge(south, entrance)));
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(entrance, RoleIds.ENTRANCE);
        roles.put(east, RoleIds.CORRIDOR);
        roles.put(corner, RoleIds.ENCOUNTER);
        roles.put(far, RoleIds.LOOT);
        roles.put(back, RoleIds.CORRIDOR);
        roles.put(south, RoleIds.CORRIDOR);

        DungeonShape shape = new DungeonShape(7, cells, edges, entrance, far,
                List.of(entrance, east, corner, far), roles);

        if (shape.rootDistances().get(corner) != 2) {
            throw new AssertionError("the ring's encounter cell is two steps one way and four the "
                    + "other; BFS must call it 2, got " + shape.rootDistances().get(corner));
        }
        if (shape.rootDistances().get(far) != 3) {
            throw new AssertionError("expected the loot cell at distance 3, got "
                    + shape.rootDistances().get(far));
        }

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        entries.addAll(everyMask("spring", List.of("loot"), List.of(SituationTags.WATER), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("flooded_fight", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        String placed = result.plan().rooms().get(corner).name();
        if (!placed.startsWith("plain_fight")) {
            throw new AssertionError("cell " + corner + " is at root distance 2 and the only water "
                    + "is at distance 3, so it must take the plain room; got " + placed);
        }
        assertInvariant(7, shape, result.plan(), manifest, BagTags.pilgrim(),
                result.fallbackCells());
    }

    /**
     * <strong>The bag.</strong> Its tags are depth 0, so they reach every cell.
     * The same floor that falls back for a Pilgrim resolves cleanly for a
     * Plumber, whose bag carries the water the gate wants.
     */
    private static void testBagIsDepthZero() {
        DungeonShape shape = line(3, "encounter");
        PlanCell gate = new PlanCell(1, 0);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        // The only encounter room on this floor wants water, so which bag is
        // carrying shows up as a fallback rather than as a coin toss between
        // two legal rooms.
        entries.addAll(everyMask("flooded_fight", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result pilgrim = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        if (!pilgrim.fallbackCells().contains(gate)) {
            throw new AssertionError("an empty bag cannot open a water gate at distance 1, so "
                    + gate + " should have fallen back; fallbacks were " + pilgrim.fallbackCells());
        }

        Set<String> plumber = BagTags.seed("plumber", 1);
        RoomSelector.Result carried = RoomSelector.resolveDetailed(shape, manifest, null, plumber);
        if (carried.fallbackCells().contains(gate)) {
            throw new AssertionError("the Plumber's water is available at every distance, so the "
                    + "water gate at " + gate + " must be selectable; it fell back");
        }
        if (!carried.plan().rooms().get(gate).name().startsWith("flooded_fight")) {
            throw new AssertionError("expected the water gate at " + gate + ", got "
                    + carried.plan().rooms().get(gate).name());
        }
        assertInvariant(0, shape, carried.plan(), manifest, plumber, carried.fallbackCells());
    }

    /**
     * <strong>Same-depth provides.</strong> Two cells at the same distance
     * might be reachable from each other, but the player is not guaranteed to
     * visit one before the other, so the invariant plays safe: the sibling's
     * water is invisible at distance 1 and visible at distance 2.
     */
    private static void testSameDepthProvidesDoNotCount() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell sibling = new PlanCell(1, 0);
        PlanCell peer = new PlanCell(0, 1);
        PlanCell below = new PlanCell(0, 2);

        Set<PlanCell> cells = new LinkedHashSet<>(List.of(entrance, sibling, peer, below));
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                new PlanEdge(entrance, sibling), new PlanEdge(entrance, peer),
                new PlanEdge(peer, below)));
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(entrance, RoleIds.ENTRANCE);
        roles.put(sibling, RoleIds.LOOT);
        roles.put(peer, RoleIds.ENCOUNTER);
        roles.put(below, RoleIds.CORRIDOR);

        DungeonShape shape = new DungeonShape(11, cells, edges, entrance, below,
                List.of(entrance, peer, below), roles);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("spring", List.of("loot"), List.of(SituationTags.WATER), List.of()));
        entries.addAll(everyMask("flooded_fight", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER)));
        entries.addAll(everyMask("cistern", List.of("corridor"), List.of(),
                List.of(SituationTags.WATER)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        if (!result.fallbackCells().contains(peer)) {
            throw new AssertionError("cell " + peer + " shares distance 1 with the room that "
                    + "provides water, so it must not see it and must fall back; fallbacks were "
                    + result.fallbackCells());
        }
        if (result.fallbackCells().contains(below)) {
            throw new AssertionError("cell " + below + " is at distance 2 and the water is at 1, "
                    + "so it must see it; it fell back instead");
        }
        if (!result.plan().rooms().get(below).name().startsWith("cistern")) {
            throw new AssertionError("expected the water room at " + below + ", got "
                    + result.plan().rooms().get(below).name());
        }
        assertInvariant(11, shape, result.plan(), manifest, BagTags.pilgrim(),
                result.fallbackCells());
    }

    /**
     * <strong>Multiple requires.</strong> A room asking for water and redstone
     * needs both. 6.1 reads as an intersection ("at least one of which") and
     * 6.6 overrides it with a subset test; this is the case that tells the two
     * apart.
     */
    private static void testMultipleRequiresMeansAllOfThem() {
        DungeonShape shape = line(3, "encounter");
        PlanCell provider = new PlanCell(1, 0);
        PlanCell consumer = new PlanCell(2, 0);
        Map<PlanCell, String> roles = new LinkedHashMap<>(shape.roles());
        roles.put(provider, RoleIds.LOOT);
        roles.put(consumer, RoleIds.ENCOUNTER);
        shape = new DungeonShape(13, shape.cells(), shape.openEdges(), shape.entrance(),
                consumer, shape.criticalPath(), roles);

        List<RoomManifest.Entry> half = new ArrayList<>();
        half.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        half.addAll(everyMask("spring", List.of("loot"), List.of(SituationTags.WATER), List.of()));
        half.addAll(everyMask("pump_room", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER, SituationTags.REDSTONE)));
        RoomManifest onlyWater = RoomManifest.create(half, List.of());

        RoomSelector.Result halfWay = RoomSelector.resolveDetailed(shape, onlyWater, null,
                BagTags.pilgrim());
        if (!halfWay.fallbackCells().contains(consumer)) {
            throw new AssertionError("water alone must not satisfy requires [water, redstone], so "
                    + consumer + " should have fallen back; fallbacks were "
                    + halfWay.fallbackCells());
        }

        List<RoomManifest.Entry> both = new ArrayList<>();
        both.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        both.addAll(everyMask("spring", List.of("loot"),
                List.of(SituationTags.WATER, SituationTags.REDSTONE), List.of()));
        both.addAll(everyMask("pump_room", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER, SituationTags.REDSTONE)));
        RoomManifest manifest = RoomManifest.create(both, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        if (result.fallbackCells().contains(consumer)) {
            throw new AssertionError("both tags upstream must satisfy requires [water, redstone]; "
                    + consumer + " fell back instead");
        }
        if (!result.plan().rooms().get(consumer).name().startsWith("pump_room")) {
            throw new AssertionError("expected the two-tag room at " + consumer + ", got "
                    + result.plan().rooms().get(consumer).name());
        }
        assertInvariant(13, shape, result.plan(), manifest, BagTags.pilgrim(),
                result.fallbackCells());
    }

    /**
     * <strong>Consumption.</strong> {@code available} is a boolean set and not
     * a count, so one upstream {@code provides: redstone} satisfies two
     * consecutive rooms that require it. That is only sound because of spec
     * 6.4's item-return rule, which is a template rule and cannot be asserted
     * from here; what can be asserted is that the model behaves as 6.6 says.
     */
    private static void testAvailableIsABooleanSet() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell wiring = new PlanCell(1, 0);
        PlanCell firstDoor = new PlanCell(2, 0);
        PlanCell secondDoor = new PlanCell(3, 0);

        Set<PlanCell> cells = new LinkedHashSet<>(
                List.of(entrance, wiring, firstDoor, secondDoor));
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                new PlanEdge(entrance, wiring), new PlanEdge(wiring, firstDoor),
                new PlanEdge(firstDoor, secondDoor)));
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(entrance, RoleIds.ENTRANCE);
        roles.put(wiring, RoleIds.LOOT);
        roles.put(firstDoor, RoleIds.ENCOUNTER);
        roles.put(secondDoor, RoleIds.ENCOUNTER);

        DungeonShape shape = new DungeonShape(17, cells, edges, entrance, secondDoor,
                List.of(entrance, wiring, firstDoor, secondDoor), roles);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("workshop", List.of("loot"), List.of(SituationTags.REDSTONE),
                List.of()));
        entries.addAll(everyMask("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        if (!result.fallbackCells().isEmpty()) {
            throw new AssertionError("one upstream redstone must serve both doors; fell back at "
                    + result.fallbackCells());
        }
        for (PlanCell door : List.of(firstDoor, secondDoor)) {
            if (!result.plan().rooms().get(door).name().startsWith("iron_door")) {
                throw new AssertionError("cell " + door + " should have taken the redstone door; got "
                        + result.plan().rooms().get(door).name());
            }
        }
        assertInvariant(17, shape, result.plan(), manifest, BagTags.pilgrim(),
                result.fallbackCells());
    }

    /**
     * <strong>Backtracking.</strong> The fixture spec 6.6 describes in prose: a
     * shallow cell whose two candidates provide different things, and a deeper
     * cell that needs the less likely one.
     *
     * <p>The water room outweighs the redstone room nine to one, so a greedy
     * pass takes water at distance 1 nine times in ten and strands the redstone
     * gate at distance 2 on a boring fallback. The pass must instead undo the
     * shallow pick. Run over two hundred floors: every one of them ends with
     * the gate placed and nothing fallen back, and the backtrack counter says
     * how often the first pick had to be given up, which is the part a greedy
     * pass cannot do.
     */
    private static void testBacktrackingRecoversWhatGreedyLoses() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell supply = new PlanCell(1, 0);
        PlanCell gate = new PlanCell(2, 0);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("spring", List.of("loot"), List.of(SituationTags.WATER),
                List.of(), DungeonRoomMeta.ACCESS_OPEN, 9));
        entries.addAll(everyMask("workshop", List.of("loot"), List.of(SituationTags.REDSTONE),
                List.of(), DungeonRoomMeta.ACCESS_OPEN, 1));
        entries.addAll(everyMask("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        int backtracked = 0;
        int floors = 200;
        for (long seed = 0; seed < floors; seed++) {
            Set<PlanCell> cells = new LinkedHashSet<>(List.of(entrance, supply, gate));
            Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                    new PlanEdge(entrance, supply), new PlanEdge(supply, gate)));
            Map<PlanCell, String> roles = new LinkedHashMap<>();
            roles.put(entrance, RoleIds.ENTRANCE);
            roles.put(supply, RoleIds.LOOT);
            roles.put(gate, RoleIds.ENCOUNTER);
            DungeonShape shape = new DungeonShape(seed, cells, edges, entrance, gate,
                    List.of(entrance, supply, gate), roles);

            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                    BagTags.pilgrim());
            if (!result.fallbackCells().isEmpty()) {
                throw new AssertionError("seed " + seed + ": a satisfying assignment exists "
                        + "(workshop then iron_door) and the pass fell back at "
                        + result.fallbackCells());
            }
            String atSupply = result.plan().rooms().get(supply).name();
            String atGate = result.plan().rooms().get(gate).name();
            if (!atSupply.startsWith("workshop") || !atGate.startsWith("iron_door")) {
                throw new AssertionError("seed " + seed + ": expected workshop at " + supply
                        + " and iron_door at " + gate + ", got " + atSupply + " and " + atGate);
            }
            if (result.backtrackSteps() > 0) {
                backtracked++;
            }
            assertInvariant(seed, shape, result.plan(), manifest, BagTags.pilgrim(),
                    result.fallbackCells());
        }

        if (backtracked == 0) {
            throw new AssertionError("the weighted draw should have taken the water room first on "
                    + "most of the " + floors + " floors, and backtracking should have undone it; "
                    + "no floor backtracked at all, so this fixture proves nothing");
        }
        System.out.println("Backtracking fixture: " + backtracked + " of " + floors
                + " floors took the water room first and had to give it back");
    }

    /**
     * A room whose {@code requires} nothing on the floor can satisfy is never
     * placed, on any of the sweep's floors or on a fixture where it is the only
     * alternative to a plain room.
     */
    private static void testUnsatisfiableRequiresNeverLands() {
        DungeonShape shape = line(4, "encounter");

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("unreachable_dock", List.of("encounter"), List.of(),
                List.of(UNREACHABLE_TAG), DungeonRoomMeta.ACCESS_OPEN, 500));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> entry : result.plan().rooms().entrySet()) {
            if (entry.getValue().name().startsWith("unreachable_dock")) {
                throw new AssertionError("cell " + entry.getKey() + " took a room requiring "
                        + UNREACHABLE_TAG + ", which nothing on this floor provides");
            }
        }
    }

    /**
     * Audit finding 2.6: a trial key is spent, and a boolean set cannot say so.
     * The rule is that a consumable may appear in a spur's {@code requires},
     * where losing it costs an optional reward, and never in the
     * {@code requires} of a cell on the entrance-to-staging path, where losing
     * it would cost the route.
     *
     * <p>The fixture puts a key at distance 1 and a room that wants one at
     * distance 2 twice over: once on the spine, once down a spur. The spine
     * cell must refuse it and the spur cell must take it.
     */
    private static void testConsumableNeverGatesTheSpine() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell pots = new PlanCell(1, 0);
        PlanCell onPath = new PlanCell(2, 0);
        PlanCell spur = new PlanCell(1, 1);

        Set<PlanCell> cells = new LinkedHashSet<>(List.of(entrance, pots, onPath, spur));
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                new PlanEdge(entrance, pots), new PlanEdge(pots, onPath),
                new PlanEdge(pots, spur)));
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(entrance, RoleIds.ENTRANCE);
        roles.put(pots, RoleIds.LOOT);
        roles.put(onPath, RoleIds.ENCOUNTER);
        roles.put(spur, RoleIds.CORRIDOR);

        DungeonShape shape = new DungeonShape(23, cells, edges, entrance, onPath,
                List.of(entrance, pots, onPath), roles);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("pot_room", List.of("loot"), List.of(SituationTags.TRIAL_KEY),
                List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("the_altar", List.of("encounter"), List.of(),
                List.of(SituationTags.TRIAL_KEY), DungeonRoomMeta.ACCESS_OPEN, 500));
        entries.addAll(everyMask("barred_vault", List.of("corridor"), List.of(),
                List.of(SituationTags.TRIAL_KEY)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());

        String atPath = result.plan().rooms().get(onPath).name();
        if (!atPath.startsWith("plain_fight")) {
            throw new AssertionError("a consumable tag may never gate a cell on the "
                    + "entrance-to-staging path; cell " + onPath + " took " + atPath);
        }
        String atSpur = result.plan().rooms().get(spur).name();
        if (!atSpur.startsWith("barred_vault")) {
            throw new AssertionError("a spur may spend the key, so cell " + spur
                    + " should have taken barred_vault; got " + atSpur);
        }
        if (result.fallbackCells().contains(spur)) {
            throw new AssertionError("the spur's vault is satisfiable and should not have been a "
                    + "fallback; fallbacks were " + result.fallbackCells());
        }
        assertInvariant(23, shape, result.plan(), manifest, BagTags.pilgrim(),
                result.fallbackCells());
    }

    /**
     * Spec 6.1's placement rule: at most one gated cell per branch, and at
     * least one on the shortest entrance-to-staging-room path. Audit finding
     * 2.16 is why the second half only counts spine cells: a dead-end spur's
     * gate is one the player can walk past without ever meeting.
     */
    private static void testAccessPlacement() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        entries.addAll(everyMask("hoard", List.of("loot"), List.of(), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("locked_fight", List.of("encounter", "loot", "corridor"),
                List.of(), List.of(), DungeonRoomMeta.ACCESS_GATED, 8));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        int floors = 0;
        for (long seed = 0; seed < 60; seed++) {
            DungeonShape shape = LayoutGraphGenerator.generate(seed, 8, 12);
            if (shape == null) {
                continue;
            }
            floors++;
            DungeonPlan plan = RoomSelector.resolveDetailed(shape, manifest, null,
                    BagTags.pilgrim()).plan();

            List<PlanCell> spine = spine(shape);
            int onSpine = 0;
            Map<Integer, Integer> perBranch = new LinkedHashMap<>();
            Map<PlanCell, Integer> groups = branchIds(shape, new LinkedHashSet<>(spine));

            for (PlanCell cell : shape.cells()) {
                String name = plan.rooms().get(cell).name();
                if (!name.startsWith("locked_fight")) {
                    continue;
                }
                if (spine.contains(cell)) {
                    onSpine++;
                } else {
                    perBranch.merge(groups.get(cell), 1, Integer::sum);
                }
            }

            if (onSpine != 1) {
                throw new AssertionError("seed " + seed + ": expected exactly one gated cell on "
                        + "the entrance-to-staging path, got " + onSpine);
            }
            for (Map.Entry<Integer, Integer> branch : perBranch.entrySet()) {
                if (branch.getValue() > 1) {
                    throw new AssertionError("seed " + seed + ": branch " + branch.getKey()
                            + " has " + branch.getValue() + " gated cells, and 6.1 allows one");
                }
            }
        }
        if (floors < 40) {
            throw new AssertionError("expected the access sweep to cover at least 40 floors, got "
                    + floors);
        }
    }

    // ---- fixtures ----

    /**
     * A straight corridor of {@code n} cells: entrance, then {@code middleRole}
     * for everything but the last, which is the exit and the terminal.
     */
    private static DungeonShape line(int n, String middleRole) {
        Set<PlanCell> cells = new LinkedHashSet<>();
        List<PlanCell> path = new ArrayList<>();
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        // M70: namespaced role ids, matching the generator.
        String entrance = RoleIds.ENTRANCE;
        String exit = RoleIds.EXIT;
        String middle = RoleIds.resolve(middleRole);
        if (middle == null) {
            middle = middleRole;
        }
        for (int i = 0; i < n; i++) {
            PlanCell cell = new PlanCell(i, 0);
            cells.add(cell);
            path.add(cell);
            roles.put(cell, i == 0 ? entrance : (i == n - 1 ? exit : middle));
        }
        Set<PlanEdge> edges = new LinkedHashSet<>();
        for (int i = 0; i < n - 1; i++) {
            edges.add(new PlanEdge(path.get(i), path.get(i + 1)));
        }
        return new DungeonShape(3, cells, edges, path.get(0), path.get(n - 1), path, roles);
    }

    /**
     * One entry per door mask, so a fixture never fails because the room that
     * carried the tag under test did not fit the cell's walls. Fifteen entries
     * per room type, which is cheap and takes the geometry out of the argument.
     */
    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles,
                                                      List<String> provides, List<String> requires) {
        return everyMask(name, roles, provides, requires, DungeonRoomMeta.ACCESS_OPEN, 1);
    }

    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles,
                                                      List<String> provides, List<String> requires,
                                                      String access, int weight) {
        List<RoomManifest.Entry> out = new ArrayList<>();
        for (int mask = 1; mask <= 15; mask++) {
            out.add(new RoomManifest.Entry(name + "_" + DoorMask.toLetters(mask),
                    meta(name, roles, provides, requires, access, weight), mask));
        }
        return out;
    }

    private static DungeonRoomMeta meta(String template, List<String> roles, List<String> provides,
                                        List<String> requires, String access, int weight) {
        // M70: qualify bare role names to namespaced ids, the same way
        // DungeonRoomMeta.parseRoles does for JSON files, so the test's
        // hand-built rooms match the namespaced ids the generator assigns.
        return new DungeonRoomMeta(template, 1, 1, qualify(roles), weight, 0, -1, null, List.of(), null,
                1, provides, requires, null, access, DungeonRoomMeta.WINDOW_BARS, 1);
    }

    /**
     * M70: qualifies a list of bare role names to namespaced ids. A name
     * that already contains a colon is returned as-is.
     */
    private static List<String> qualify(List<String> roles) {
        List<String> out = new ArrayList<>(roles.size());
        for (String role : roles) {
            String resolved = RoleIds.resolve(role);
            out.add(resolved == null ? role : resolved);
        }
        return List.copyOf(out);
    }

    /**
     * The room set the Pilgrim sweep resolves against: a plain room for every
     * role so a floor always resolves, a handful of providers, a handful of
     * consumers including one that wants two tags at once, and one room whose
     * requirement nothing on the floor can ever meet.
     */
    private static RoomManifest sweepManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("hoard", List.of("loot"), List.of(), List.of()));

        entries.addAll(everyMask("spring", List.of("loot", "corridor"),
                List.of(SituationTags.WATER), List.of()));
        entries.addAll(everyMask("workshop", List.of("loot"),
                List.of(SituationTags.REDSTONE), List.of()));
        entries.addAll(everyMask("quarry", List.of("corridor"),
                List.of(SituationTags.BLOCKS), List.of()));
        entries.addAll(everyMask("pot_room", List.of("loot"),
                List.of(SituationTags.TRIAL_KEY), List.of()));

        entries.addAll(everyMask("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE), DungeonRoomMeta.ACCESS_GATED, 6));
        entries.addAll(everyMask("flow_puzzle", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("pump_room", List.of("loot"), List.of(),
                List.of(SituationTags.WATER, SituationTags.REDSTONE),
                DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("soft_wall", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("barred_vault", List.of("corridor"), List.of(),
                List.of(SituationTags.TRIAL_KEY), DungeonRoomMeta.ACCESS_OPEN, 6));

        entries.addAll(everyMask("unreachable_dock", List.of("encounter", "loot", "corridor"),
                List.of(), List.of(UNREACHABLE_TAG), DungeonRoomMeta.ACCESS_OPEN, 20));
        return RoomManifest.create(entries, List.of());
    }

    /**
     * A tier-specific manifest for the M64 tier sweep. Rooms whose
     * {@code tier} field is above the given tier are excluded, mirroring how
     * the catalogue admits rooms by loot tier: tier 1 sees only tier-1 rooms,
     * tier 2 sees tiers 1 and 2, tier 3 sees all.
     *
     * <p>The room set is the same shape as {@link #sweepManifest}: a plain room
     * for every role, providers for each tag, consumers including a
     * multi-tag one, and an unreachable room. The tier-2 and tier-3 manifests
     * add higher-tier rooms that provide additional tags, so the sweep
     * exercises the filter at each tier level.
     */
    private static RoomManifest tierManifest(int tier) {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        // Tier-1 rooms: always available.
        entries.addAll(everyMaskTier("hall", List.of("entrance"), List.of(), List.of(), 1));
        entries.addAll(everyMaskTier("way_out", List.of("exit"), List.of(), List.of(), 1));
        entries.addAll(everyMaskTier("passage", List.of("corridor"), List.of(), List.of(), 1));
        entries.addAll(everyMaskTier("plain_fight", List.of("encounter"), List.of(), List.of(), 1));
        entries.addAll(everyMaskTier("hoard", List.of("loot"), List.of(), List.of(), 1));

        entries.addAll(everyMaskTier("spring", List.of("loot", "corridor"),
                List.of(SituationTags.WATER), List.of(), 1));
        entries.addAll(everyMaskTier("workshop", List.of("loot"),
                List.of(SituationTags.REDSTONE), List.of(), 1));
        entries.addAll(everyMaskTier("quarry", List.of("corridor"),
                List.of(SituationTags.BLOCKS), List.of(), 1));
        entries.addAll(everyMaskTier("pot_room", List.of("loot"),
                List.of(SituationTags.TRIAL_KEY), List.of(), 1));

        entries.addAll(everyMaskTier("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE), DungeonRoomMeta.ACCESS_GATED, 6, 1));
        entries.addAll(everyMaskTier("flow_puzzle", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER), DungeonRoomMeta.ACCESS_OPEN, 6, 1));
        entries.addAll(everyMaskTier("pump_room", List.of("loot"), List.of(),
                List.of(SituationTags.WATER, SituationTags.REDSTONE),
                DungeonRoomMeta.ACCESS_OPEN, 6, 1));
        entries.addAll(everyMaskTier("soft_wall", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS), DungeonRoomMeta.ACCESS_OPEN, 6, 1));
        entries.addAll(everyMaskTier("barred_vault", List.of("corridor"), List.of(),
                List.of(SituationTags.TRIAL_KEY), DungeonRoomMeta.ACCESS_OPEN, 6, 1));

        // Tier-2 rooms: available at tier 2 and 3.
        if (tier >= 2) {
            entries.addAll(everyMaskTier("ledge_archers", List.of("encounter"),
                    List.of(SituationTags.BOW), List.of(), 2));
            entries.addAll(everyMaskTier("ice_run", List.of("corridor"),
                    List.of(SituationTags.WIND_CHARGE), List.of(), 2));
            entries.addAll(everyMaskTier("breeze_arena", List.of("encounter"), List.of(),
                    List.of(SituationTags.WIND_CHARGE), DungeonRoomMeta.ACCESS_GATED, 6, 2));
        }

        // Tier-3 rooms: available at tier 3 only.
        if (tier >= 3) {
            entries.addAll(everyMaskTier("elders_chamber", List.of("corridor"),
                    List.of(SituationTags.WATER), List.of(), 3));
            entries.addAll(everyMaskTier("the_raid", List.of("encounter"), List.of(),
                    List.of(SituationTags.MOB), DungeonRoomMeta.ACCESS_GATED, 6, 3));
        }

        entries.addAll(everyMaskTier("unreachable_dock", List.of("encounter", "loot", "corridor"),
                List.of(), List.of(UNREACHABLE_TAG), DungeonRoomMeta.ACCESS_OPEN, 20, 1));
        return RoomManifest.create(entries, List.of());
    }

    /** One entry per door mask, with a tier field. */
    private static List<RoomManifest.Entry> everyMaskTier(String name, List<String> roles,
                                                          List<String> provides, List<String> requires,
                                                          int tier) {
        return everyMaskTier(name, roles, provides, requires, DungeonRoomMeta.ACCESS_OPEN, 1, tier);
    }

    private static List<RoomManifest.Entry> everyMaskTier(String name, List<String> roles,
                                                          List<String> provides, List<String> requires,
                                                          String access, int weight, int tier) {
        List<RoomManifest.Entry> out = new ArrayList<>();
        for (int mask = 1; mask <= 15; mask++) {
            out.add(new RoomManifest.Entry(name + "_" + DoorMask.toLetters(mask),
                    tierMeta(name, roles, provides, requires, access, weight, tier), mask));
        }
        return out;
    }

    private static DungeonRoomMeta tierMeta(String template, List<String> roles, List<String> provides,
                                            List<String> requires, String access, int weight, int tier) {
        return new DungeonRoomMeta(template, 1, 1, qualify(roles), weight, 0, -1, null, List.of(), null,
                tier, provides, requires, null, access, DungeonRoomMeta.WINDOW_BARS, 1);
    }

    /**
     * One scarce provider against a popular room that provides nothing, and an
     * encounter role whose only room wants what the provider has.
     *
     * <p>The second half is what makes the backtracker run. A cell only
     * backtracks on an <strong>empty</strong> candidate set (6.6 step 5), so as
     * long as some room for the cell's mask and role asks for nothing, the pass
     * takes it and moves on. Every catalogue with a plain corridor in it is
     * therefore mostly backtrack-free, which is why the Pilgrim sweep above
     * never backtracks and why this one has to remove the plain encounter room
     * to measure the search at all.
     */
    private static RoomManifest scarceManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        entries.addAll(everyMask("hoard", List.of("loot"), List.of(), List.of(),
                DungeonRoomMeta.ACCESS_OPEN, 9));
        entries.addAll(everyMask("workshop", List.of("loot"), List.of(SituationTags.REDSTONE),
                List.of(), DungeonRoomMeta.ACCESS_OPEN, 1));
        entries.addAll(everyMask("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE), DungeonRoomMeta.ACCESS_GATED, 20));
        return RoomManifest.create(entries, List.of());
    }

    // ---- the two graph walks the access assertion needs of its own ----

    /** The shortest entrance-to-terminal route, recomputed here so the test does
     *  not assert the selector's arithmetic with the selector's own arithmetic. */
    private static List<PlanCell> spine(DungeonShape shape) {
        Map<PlanCell, PlanCell> parents = new LinkedHashMap<>();
        Set<PlanCell> seen = new LinkedHashSet<>();
        List<PlanCell> queue = new ArrayList<>();
        seen.add(shape.entrance());
        queue.add(shape.entrance());
        for (int i = 0; i < queue.size(); i++) {
            PlanCell current = queue.get(i);
            for (PlanCell neighbor : neighbors(shape, current)) {
                if (seen.add(neighbor)) {
                    parents.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        List<PlanCell> path = new ArrayList<>();
        for (PlanCell cell = shape.terminal(); cell != null; cell = parents.get(cell)) {
            path.add(cell);
            if (cell.equals(shape.entrance())) {
                break;
            }
        }
        return path;
    }

    /** Connected components of the graph with the spine removed. */
    private static Map<PlanCell, Integer> branchIds(DungeonShape shape, Set<PlanCell> onSpine) {
        Map<PlanCell, Integer> groups = new LinkedHashMap<>();
        for (PlanCell cell : onSpine) {
            groups.put(cell, -1);
        }
        int next = 0;
        for (PlanCell start : shape.cells()) {
            if (groups.containsKey(start)) {
                continue;
            }
            int id = next++;
            List<PlanCell> queue = new ArrayList<>();
            groups.put(start, id);
            queue.add(start);
            for (int i = 0; i < queue.size(); i++) {
                for (PlanCell neighbor : neighbors(shape, queue.get(i))) {
                    if (!groups.containsKey(neighbor)) {
                        groups.put(neighbor, id);
                        queue.add(neighbor);
                    }
                }
            }
        }
        return groups;
    }

    /**
     * Neighbours in grid order. The sort matters: {@link #spine} has to break a
     * tie between two equally short routes the same way the selector does, or
     * the two would disagree about which cells are on the path whenever a loop
     * offers a second one.
     */
    private static List<PlanCell> neighbors(DungeonShape shape, PlanCell cell) {
        List<PlanCell> out = new ArrayList<>();
        for (PlanEdge edge : shape.openEdges()) {
            if (edge.touches(cell)) {
                out.add(edge.other(cell));
            }
        }
        out.sort(java.util.Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z));
        return out;
    }

    // ---- M70: synthetic manifests for the headless test ----

    /**
     * Publishes a synthetic {@link BagManifest} built from the eight built-in
     * {@link BagIds} with their pre-M70 tag sets, so {@link BagTags#seed}
     * reads the same tags the live server would load from
     * {@code dungeon_bag/*.json}. The headless test has no Minecraft server.
     */
    private static void publishSyntheticBagManifest() {
        Map<String, BagManifest.Entry> entries = new LinkedHashMap<>();
        Object[][] bags = {
                {BagIds.MASON, java.util.Set.of(SituationTags.BLOCKS), 0},
                {BagIds.PLUMBER, java.util.Set.of(SituationTags.WATER, SituationTags.LAVA), 1},
                {BagIds.SAPPER, java.util.Set.of(SituationTags.BLOCKS), 2},
                {BagIds.MAGICIAN, java.util.Set.of(SituationTags.PEARL, SituationTags.WIND_CHARGE), 3},
                {BagIds.RANGER, java.util.Set.of(SituationTags.BOW), 4},
                {BagIds.SHEPHERD, java.util.Set.of(SituationTags.LEAD, SituationTags.MOB), 5},
                {BagIds.INNKEEPER, java.util.Set.of(SituationTags.MILK), 6},
                {BagIds.PILGRIM, java.util.Set.of(), 7},
        };
        for (Object[] b : bags) {
            String id = (String) b[0];
            @SuppressWarnings("unchecked")
            Set<String> tags = (Set<String>) b[1];
            int order = (int) b[2];
            BagDefinition def = new BagDefinition(id, id, "", order, List.of(), tags,
                    BagMeta.defaultLootTable(id));
            entries.put(id, new BagManifest.Entry(id, def));
        }
        BagManifest.publish(BagManifest.create(entries, List.of()));
    }

    /**
     * Publishes a synthetic {@link RoleManifest} with the three built-in
     * population roles, so the selector's unknown-role check passes for the
     * built-in ids the generator assigns.
     */
    private static void publishSyntheticRoleManifest() {
        Map<String, RoleManifest.Entry> entries = new LinkedHashMap<>();
        Object[][] roles = {
                {RoleIds.ENCOUNTER, 45, RoomRoleDefinition.Operation.TRIAL_ENCOUNTER},
                {RoleIds.LOOT, 25, RoomRoleDefinition.Operation.TOOL_CACHE},
                {RoleIds.CORRIDOR, 30, RoomRoleDefinition.Operation.NONE},
        };
        for (Object[] r : roles) {
            String id = (String) r[0];
            int weight = (int) r[1];
            RoomRoleDefinition.Operation op = (RoomRoleDefinition.Operation) r[2];
            RoomRoleDefinition def = new RoomRoleDefinition(id, "interior", weight, 0, -1, List.of(), op);
            entries.put(id, new RoleManifest.Entry(id, def));
        }
        RoleManifest.publish(RoleManifest.create(entries, List.of()));
    }
}
