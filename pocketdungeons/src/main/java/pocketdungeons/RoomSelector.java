package pocketdungeons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Resolves a {@link DungeonShape} into a {@link DungeonPlan} by querying the
 * room manifest for a concrete template/rotation that satisfies each cell's
 * required door mask and role.
 *
 * <p>Selection is a <strong>seeded weighted pick</strong>, not the first
 * alphabetical match. With one room per shape, a whole dungeon gets built out of
 * the same handful of templates -- which is the difference between a procedural
 * layout and a layout that looks procedural. The seed is derived from the plan,
 * so a seed still reproduces a dungeon exactly.
 */
final class RoomSelector {

    private static final int MAX_ROOMS = 50;

    private RoomSelector() {}

    /**
     * Attempts to resolve every cell in the shape. Returns {@link Optional#empty}
     * if any cell has no room/rotation that satisfies its required mask and role.
     */
    static Optional<DungeonPlan> resolve(DungeonShape shape, RoomManifest manifest) {
        return resolve(shape, manifest, null);
    }

    /**
     * As {@link #resolve(DungeonShape, RoomManifest)}, filtered to a requested
     * theme. A {@code null} theme matches every room; a non-null theme only
     * matches rooms that either omit the {@code theme} field or include it.
     */
    static Optional<DungeonPlan> resolve(DungeonShape shape, RoomManifest manifest, String theme) {
        Result result = resolveDetailed(shape, manifest, theme);
        return Optional.ofNullable(result.plan());
    }

    /**
     * Detailed variant that carries failure information when resolution cannot
     * find a match for a cell.
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest) {
        return resolveDetailed(shape, manifest, null);
    }

    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme) {
        return resolveDetailed(shape, manifest, theme, BagTags.pilgrim());
    }

    /**
     * M47 (SITUATIONS_SPEC 6.6): resolution with the root-distance solvability
     * pass, seeded from the party's bag.
     *
     * <p>Cells are processed in BFS order rather than grid order, and a cell at
     * root distance n may only take a room whose {@code requires} is a subset of
     * the tags reachable at a distance strictly below n: the bag's seed, plus
     * the {@code provides} of every shallower cell. That makes the solution to
     * a gate reachable before the gate on every path, spurs included, which is
     * the whole point of 6.6 over 6.2's single walk.
     *
     * <p><strong>What the model assumes.</strong> {@code available} is a boolean
     * set of tags and never a count. That is only sound while spec 6.4's
     * item-return rule holds: a gate that takes an item must give it back, so a
     * capability, once reached, stays reached at every deeper distance. The
     * catalogue audit found four templates that break the rule (findings 2.2,
     * 2.3, 2.4 and 2.5); those are template bugs and the model cannot see them.
     * The one case handled here is the tag that is consumable by construction:
     * see {@link #CONSUMABLE_TAGS}.
     *
     * <p>The three-argument form seeds the Pilgrim case (empty bag, party of
     * one), which is the strictest seed and therefore the safe default for a
     * caller with no bag to name.
     *
     * @param bagTags the depth-0 tag set, from {@link BagTags#seed}
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme,
                                  Set<String> bagTags) {
        return resolveDetailed(shape, manifest, theme, bagTags, null);
    }

    /**
     * M66: the full form with recipe effects. The recipe plan carries
     * guarantees (infested, Deep Dark, Store), weightings (flooded/chasm),
     * and the path length bonus. {@code null} means no recipe effects.
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme,
                                  Set<String> bagTags, RunRecipePlan recipePlan) {
        Map<PlanCell, Integer> depths = depths(shape);
        List<PlanCell> order = bfsOrder(shape.cells(), depths);

        Pass pass = new Pass(shape, manifest, theme, depths, order,
                bagTags == null ? Set.of() : bagTags);
        Failure failure = pass.prepare();
        if (failure != null) {
            return new Result(null, failure);
        }
        pass.run();

        Map<PlanCell, DungeonPlan.PlacedRoom> placed = pass.placed();
        Set<PlanCell> fallbacks = pass.fallbacks();
        PlanCell anomalyCell = rollAnomaly(shape, theme, placed, depths);

        // M66: apply recipe guarantees after the main pass and anomaly roll.
        // Each guarantee forces a specific room onto an eligible cell. The
        // guarantee is part of the solvability proof, not a post-plan
        // replacement: the forced room's requires must be satisfied by the
        // available tags at that cell's depth.
        if (recipePlan != null) {
            applyRecipeGuarantees(shape, manifest, theme, placed, depths, bagTags, recipePlan);
        }

        DungeonPlan plan = new DungeonPlan(
                shape.seed(),
                Set.copyOf(shape.cells()),
                Map.copyOf(shape.roles()),
                Map.copyOf(depths),
                Map.copyOf(placed),
                Set.copyOf(shape.openEdges()),
                shape.entrance(),
                shape.terminal(),
                List.copyOf(shape.criticalPath()),
                anomalyCell);
        return new Result(plan, null, Set.copyOf(fallbacks), pass.backtrackSteps());
    }

    /**
     * M35: rarely swaps one non-entrance, non-terminal critical-path cell's
     * room for one out of {@link RoomManifest#currentAnomaly()} satisfying the
     * same mask and role, mutating {@code placed} in place. Gated on the run
     * having an adventure-graph node for its theme, the same gate the Pocket2
     * door uses -- an unthemed run never hosts an anomaly. Rolled off the plan
     * seed rather than {@code rng}, which the main selection loop above has
     * already consumed by an amount that depends on how many cells the shape
     * has, and a roll whose outcome depends on room count would be a strange
     * kind of not-quite-reproducible.
     *
     * @return the swapped cell, or {@code null} if the gate was closed, the
     *         roll failed, no eligible cell existed, or no anomaly room
     *         matched the chosen cell's mask and role
     */
    private static PlanCell rollAnomaly(DungeonShape shape, String theme,
                                        Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                        Map<PlanCell, Integer> depths) {
        if (theme == null || AdventureGraphs.current().graph().node(theme) == null) {
            return null;
        }
        Random anomalyRng = new Random(shape.seed() * 47 + 91);
        if (anomalyRng.nextDouble() >= PocketDungeonsConfig.anomalyRoomChance()) {
            return null;
        }

        List<PlanCell> eligible = new ArrayList<>();
        for (PlanCell cell : shape.criticalPath()) {
            if (!cell.equals(shape.entrance()) && !cell.equals(shape.terminal())) {
                eligible.add(cell);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }

        PlanCell candidate = eligible.get(anomalyRng.nextInt(eligible.size()));
        int mask = requiredMask(shape, candidate);
        String role = shape.roles().get(candidate);
        List<RoomManifest.Match> matches = RoomManifest.currentAnomaly().queryAnyRotation(mask, role);
        if (matches.isEmpty()) {
            return null;
        }
        matches = new ArrayList<>(matches);
        matches.sort(Comparator.comparing((RoomManifest.Match m) -> m.entry().name)
                .thenComparingInt(RoomManifest.Match::rotation));

        RoomManifest.Match pick = pick(matches, depths.getOrDefault(candidate, 0), new HashMap<>(), anomalyRng);
        placed.put(candidate, new DungeonPlan.PlacedRoom(pick.entry().name, pick.rotation()));
        return candidate;
    }

    /**
     * M66: applies recipe guarantees after the main selection pass. Each
     * guarantee forces a specific room onto an eligible cell, replacing
     * whatever room the pass assigned there. The forced room's
     * {@code requires} must be satisfied by the available tags at that
     * cell's depth; if no eligible cell can host the guaranteed room, the
     * guarantee is silently skipped (the plan is still valid, just without
     * the guarantee). This is rare: the room library has coverage-floor
     * shapes for every mask.
     *
     * <p>Guarantees applied, in order:
     * <ol>
     *   <li>Infested: force {@code infested_wall} or {@code creeper_kennel}
     *       onto a non-entrance, non-terminal cell.</li>
     *   <li>Deep Dark: force {@code deep_dark_landing} onto a non-entrance,
     *       non-terminal cell (tier 3 only; RunRecipePlan already refused
     *       below tier 3).</li>
     *   <li>Store: force {@code the_store} onto a non-entrance, non-terminal
     *       cell, preferring spur cells (branches off the critical path).</li>
     * </ol>
     *
     * <p>Flooded/Chasm weighting is applied during the main selection pass
     * via the {@link Pass} constructor, not here.
     */
    private static void applyRecipeGuarantees(DungeonShape shape, RoomManifest manifest,
                                              String theme,
                                              Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                              Map<PlanCell, Integer> depths,
                                              Set<String> bagTags,
                                              RunRecipePlan recipePlan) {
        if (recipePlan.infestedGuarantee) {
            forceRoom(shape, manifest, theme, placed, depths, bagTags,
                    java.util.List.of("infested_wall", "creeper_kennel"));
        }
        if (recipePlan.deepDarkGuarantee) {
            forceRoom(shape, manifest, theme, placed, depths, bagTags,
                    java.util.List.of("deep_dark_landing"));
        }
        if (recipePlan.storeSpur) {
            forceRoom(shape, manifest, theme, placed, depths, bagTags,
                    java.util.List.of("the_store"));
        }
    }

    /**
     * M66: forces one of the named rooms onto an eligible cell. Finds a
     * non-entrance, non-terminal cell whose mask and role match one of the
     * target rooms at some rotation, and whose {@code requires} is satisfied
     * by the available tags at that depth. Replaces the cell's assigned room
     * with the target room. If no eligible cell exists, does nothing.
     */
    private static void forceRoom(DungeonShape shape, RoomManifest manifest, String theme,
                                  Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                  Map<PlanCell, Integer> depths,
                                  Set<String> bagTags,
                                  java.util.List<String> targetRoomNames) {
        Set<String> targetSet = new java.util.HashSet<>(targetRoomNames);
        for (PlanCell cell : shape.cells()) {
            if (cell.equals(shape.entrance()) || cell.equals(shape.terminal())) {
                continue;
            }
            int mask = requiredMask(shape, cell);
            String role = shape.roles().get(cell);
            if (role == null) {
                continue;
            }
            List<RoomManifest.Match> matches = manifest.queryAnyRotation(mask, role, theme);
            for (RoomManifest.Match match : matches) {
                if (!targetSet.contains(match.entry().name)) {
                    continue;
                }
                // Check requires: the forced room's requires must be a subset
                // of the available tags at this cell's depth.
                int depth = depths.getOrDefault(cell, 0);
                Set<String> available = new java.util.LinkedHashSet<>(bagTags);
                for (PlanCell other : shape.cells()) {
                    if (depths.getOrDefault(other, 0) < depth) {
                        DungeonPlan.PlacedRoom otherRoom = placed.get(other);
                        if (otherRoom != null) {
                            RoomManifest.Entry entry = manifest.byName(otherRoom.name());
                            if (entry != null) {
                                available.addAll(entry.meta.provides);
                            }
                        }
                    }
                }
                if (!available.containsAll(match.entry().meta.requires)) {
                    continue;
                }
                // Eligible: force this room onto this cell.
                placed.put(cell, new DungeonPlan.PlacedRoom(match.entry().name, match.rotation()));
                return;
            }
        }
        // No eligible cell found. The guarantee is silently skipped; the plan
        // is still valid. This is rare and logged by the caller if needed.
    }

    /**
     * Validates a resolved plan. Returns an empty list when the plan is acceptable.
     */
    static List<String> validate(DungeonPlan plan, int maxGridSpan) {
        if (maxGridSpan < 1) {
            throw new IllegalArgumentException("maxGridSpan must be positive");
        }
        List<String> problems = new ArrayList<>();
        // A plan's cell set is guaranteed non-empty by construction: PlanGeometry
        // throws on an empty set, and the graph generator's own minPathLength
        // (config-validated >= 2) guarantees at least an entrance and a
        // terminal cell. A minimum-room check here would be unreachable.
        int count = plan.cells().size();
        if (count > MAX_ROOMS) {
            problems.add("room count " + count + " above maximum " + MAX_ROOMS);
        }
        Set<PlanCell> reachable = reachableCells(plan);
        if (!reachable.contains(plan.terminal())) {
            problems.add("terminal not reachable from entrance");
        }

        // Span budget. A sprawling layout costs a force-load ticket per cell and a
        // teardown proportional to its footprint, so cap it here, where a rejection
        // costs one retry, rather than discovering it as a stutter in live play.
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlanCell cell : plan.cells()) {
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        if (spanX > maxGridSpan || spanZ > maxGridSpan) {
            problems.add("grid span " + spanX + "x" + spanZ
                    + " exceeds the maximum of " + maxGridSpan);
        }
        return problems;
    }

    /**
     * Picks one match, preferring those whose {@code minDepth} and
     * {@code maxPerDungeon} are satisfied, weighted by the room's declared weight.
     *
     * <p><strong>Both filters are preferences, not constraints.</strong> If
     * applying them empties a non-empty match list they are dropped and the pick
     * runs over everything. Failing a whole plan because the one legal room for a
     * cell had already been used twice would reintroduce M3's failure mode for no
     * player-visible benefit. Because every coverage-floor room ships unlimited
     * and at depth 0, this relaxation almost never fires -- and if it starts
     * firing often, that is a signal the floor has a hole, which
     * {@code /dungeon admin coverage} names directly.
     */
    private static RoomManifest.Match pick(List<RoomManifest.Match> matches, int depth,
                                           Map<String, Integer> used, Random rng) {
        List<RoomManifest.Match> eligible = preferred(matches, depth, used);

        // A rotationally symmetric room yields several matches for the same entry
        // and so gets an extra roll. That is harmless, and picking a random
        // rotation for a symmetric room is desirable anyway.
        int total = 0;
        for (RoomManifest.Match match : eligible) {
            total += Math.max(1, match.entry().meta.weight);
        }
        int roll = rng.nextInt(total);
        for (RoomManifest.Match match : eligible) {
            roll -= Math.max(1, match.entry().meta.weight);
            if (roll < 0) {
                return match;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    /**
     * The depth and repeat preferences {@link #pick} applies, as a fresh
     * mutable list. Extracted from {@code pick} so M47's pass can draw from the
     * same distribution more than once without re-deriving it, and it relaxes
     * to the whole list the same way and for the same reason.
     */
    private static List<RoomManifest.Match> preferred(List<RoomManifest.Match> matches, int depth,
                                                      Map<String, Integer> used) {
        List<RoomManifest.Match> eligible = new ArrayList<>(matches.size());
        for (RoomManifest.Match match : matches) {
            DungeonRoomMeta meta = match.entry().meta;
            if (meta.minDepth > depth) {
                continue;
            }
            if (meta.maxPerDungeon >= 0
                    && used.getOrDefault(match.entry().name, 0) >= meta.maxPerDungeon) {
                continue;
            }
            eligible.add(match);
        }
        return eligible.isEmpty() ? new ArrayList<>(matches) : eligible;
    }

    // ---- M47: the root-distance solvability pass (SITUATIONS_SPEC 6.6) ----

    /**
     * Tags a room hands over once rather than for good.
     *
     * <p>Audit finding 2.6: a trial key leaves the room in the player's
     * inventory and is spent at the first vault or altar that takes it, so
     * {@code provides: trial_key} is true exactly once and the boolean
     * {@code available} set cannot say so. The audit's rule, enforced in
     * {@link #solvable}: a consumable may appear in {@code provides}, and in a
     * spur's {@code requires} where losing it costs an optional reward, but
     * never in the {@code requires} of a cell on the entrance-to-staging path,
     * where losing it would cost the route.
     *
     * <p>This belongs next to the vocabulary in {@link SituationTags} and moves
     * there the next time that file is opened for another reason; M47 does not
     * own it.
     */
    static final Set<String> CONSUMABLE_TAGS = Set.of(SituationTags.TRIAL_KEY);

    /** The branch id of every cell on the entrance-to-staging path. */
    private static final int SPINE_BRANCH = -1;

    /**
     * Safety cap on the backtracking search, mirroring the constant
     * {@code LayoutGraphGenerator.generateCriticalPath} caps its own
     * backtracker with (spec 6.6 step 5 names that one). It is private over
     * there and M47 does not own that file, so the value is repeated here
     * rather than shared; whoever next opens both should hoist it.
     *
     * <p>For twenty cells with five to ten candidates each it is never
     * approached: the measured worst case over the Pilgrim sweep is three
     * figures below it.
     */
    private static final int MAX_BACKTRACK_STEPS = 50000;

    /**
     * One run of the 6.6 pass over one shape.
     *
     * <p>State lives in an object rather than in locals because backtracking
     * needs to undo, and undoing a cumulative {@code available} set built by
     * addition is the kind of thing that works for a week. Everything derived
     * is instead recomputed from the assignment prefix, which for twenty cells
     * costs nothing and cannot drift: the tags a cell may draw on, how many
     * times a room has been used already, and which branches have spent their
     * one gate are all functions of the cells resolved before it.
     */
    private static final class Pass {

        private final DungeonShape shape;
        private final RoomManifest manifest;
        private final String theme;
        private final Map<PlanCell, Integer> depths;
        private final List<PlanCell> order;
        private final Set<String> bagTags;

        private Set<PlanCell> onSpine = Set.of();
        private Map<PlanCell, Integer> branches = Map.of();
        private PlanCell gateCell;

        /** Every mask-and-role match per cell, sorted, before any tag filtering. */
        private final List<List<RoomManifest.Match>> matches = new ArrayList<>();

        private RoomManifest.Match[] assignments;
        private CellState[] states;

        /** Cells whose {@code requires} filter could not be satisfied at all. */
        private final Set<PlanCell> forced = new LinkedHashSet<>();

        private int steps;

        /** Deepest index handed an empty candidate pool during the round. */
        private int deepestEmpty = -1;

        Pass(DungeonShape shape, RoomManifest manifest, String theme,
             Map<PlanCell, Integer> depths, List<PlanCell> order, Set<String> bagTags) {
            this.shape = shape;
            this.manifest = manifest;
            this.theme = theme;
            this.depths = depths;
            this.order = order;
            this.bagTags = bagTags;
        }

        /**
         * Resolves each cell's mask and role once. Returns the legible failure
         * the pre-M47 selector returned, unchanged: a cell with no role, or a
         * mask and role the manifest has no room for at any rotation. Neither
         * is a solvability problem and neither is worth backtracking over.
         */
        Failure prepare() {
            List<PlanCell> spine = shortestPath(shape, shape.entrance(), shape.terminal());
            onSpine = new HashSet<>(spine);
            branches = branchGroups(shape, onSpine);
            gateCell = designateGateCell(shape, spine, manifest, theme);

            for (PlanCell cell : order) {
                int mask = requiredMask(shape, cell);
                String role = shape.roles().get(cell);
                if (role == null) {
                    return new Failure(cell, mask, "missing role");
                }
                List<RoomManifest.Match> found = manifest.queryAnyRotation(mask, role, theme);
                if (found.isEmpty()) {
                    return new Failure(cell, mask, role);
                }
                // Stable order in, so the weighted draw is reproducible
                // regardless of the order the manifest holds its rooms in.
                matches.add(sortedMatches(found));
            }
            assignments = new RoomManifest.Match[order.size()];
            states = new CellState[order.size()];
            return null;
        }

        /**
         * Runs the search, widening the forced-fallback set until it succeeds.
         *
         * <p>A round is a full backtracking search. If it exhausts, the cell
         * that blocked it deepest is added to {@link #forced}, where it takes a
         * role-only room with no {@code requires} at all (6.6 step 6), and the
         * search runs again. Each round forces at least one more cell and a
         * forced cell always has a candidate, so the loop terminates; in
         * practice round one succeeds and this is one iteration.
         */
        void run() {
            forceImpossibleCells();
            for (int round = 0; round <= order.size(); round++) {
                if (search()) {
                    return;
                }
                PlanCell blocker = blockingCell();
                if (blocker == null || !forced.add(blocker)) {
                    // Nothing new to give up on. Give up on everything: a floor
                    // of plain corridors is dull, and a floor that fails to
                    // resolve is a run the player does not get.
                    forced.addAll(order);
                }
            }
            throw new IllegalStateException("solvability pass did not terminate for seed "
                    + shape.seed());
        }

        /**
         * Marks, before any search runs, the cells no assignment could ever
         * satisfy.
         *
         * <p>Take the most generous view possible of a cell at depth n: assume
         * every shallower cell picked whichever of its candidates helped most,
         * so {@code available} is the bag plus the {@code provides} of every
         * candidate of every cell at depth below n. If nothing fits the cell
         * even then, nothing will, and searching for it is a search with no
         * answer at the bottom of it.
         *
         * <p>This is the difference between a fast pass and a slow one. Without
         * it a single impossible cell (a floor whose only encounter room wants
         * redstone, on a floor with no redstone above it) makes the backtracker
         * re-try every combination of every shallower cell before giving up,
         * which is what burns the step cap. With it, that cell is a fallback
         * immediately and the search only ever runs where an answer might be.
         */
        private void forceImpossibleCells() {
            for (int i = 0; i < order.size(); i++) {
                PlanCell cell = order.get(i);
                int depth = depths.getOrDefault(cell, 0);
                Set<String> optimistic = new LinkedHashSet<>(bagTags);
                for (int j = 0; j < i; j++) {
                    if (depths.getOrDefault(order.get(j), 0) >= depth) {
                        break;
                    }
                    for (RoomManifest.Match match : matches.get(j)) {
                        optimistic.addAll(match.entry().meta.provides);
                    }
                }
                // Optimistic on the placement rules too: a cell rejected here is
                // rejected on its requires alone, never on where it happens to sit.
                if (solvable(matches.get(i), optimistic, onSpine.contains(cell), false, false)
                        .isEmpty()) {
                    forced.add(cell);
                }
            }
        }

        /**
         * One backtracking search over the whole BFS order.
         *
         * @return whether every cell ended up with a room
         */
        private boolean search() {
            java.util.Arrays.fill(assignments, null);
            java.util.Arrays.fill(states, null);
            deepestEmpty = -1;
            int index = 0;
            while (index < order.size()) {
                if (states[index] == null) {
                    states[index] = new CellState(index);
                }
                RoomManifest.Match pick = states[index].next();
                if (pick != null) {
                    assignments[index] = pick;
                    index++;
                    continue;
                }
                // Nothing left here. Hand the cell back and take an earlier
                // cell's next candidate, which is what makes this better than
                // greedy: a shallow pick that stranded a deeper gate is undone
                // rather than paid for with a boring room.
                int target = retryFrom(index);
                for (int i = index; i > target && i >= 0; i--) {
                    states[i] = null;
                    assignments[i] = null;
                }
                index = target;
                if (index < 0 || ++steps > MAX_BACKTRACK_STEPS) {
                    return false;
                }
                assignments[index] = null;
            }
            return true;
        }

        /**
         * Which cell to go back to when {@code index} has run out of rooms.
         *
         * <p>The previous cell, except when this cell never had a candidate in
         * the first place. That case is a {@code requires} conflict, and only a
         * cell that could have provided one of the tags this cell wants, at a
         * <em>smaller</em> root distance, can resolve it. Anything else is a
         * cell whose choice cannot reach this one: a sibling at the same depth
         * is invisible to it by 6.6's same-depth rule, and a room providing
         * nothing it wants changes nothing.
         *
         * <p>Walking back one cell at a time through those instead is what
         * turns a shallow search into an exponential one. Measured on a
         * deliberately mean catalogue, jumping straight to the cell that could
         * actually help takes the pass from tens of milliseconds a floor, with
         * the step cap being hit, to a fraction of one.
         *
         * @return the index to retry, or {@code -1} when nothing upstream can
         *         help and the search is over
         */
        private int retryFrom(int index) {
            if (!states[index].startedEmpty) {
                return index - 1;
            }
            Set<String> wanted = new LinkedHashSet<>();
            for (RoomManifest.Match match : matches.get(index)) {
                wanted.addAll(match.entry().meta.requires);
            }
            int depth = depths.getOrDefault(order.get(index), 0);
            for (int j = index - 1; j >= 0; j--) {
                if (depths.getOrDefault(order.get(j), 0) >= depth) {
                    continue;
                }
                for (RoomManifest.Match match : matches.get(j)) {
                    if (!java.util.Collections.disjoint(match.entry().meta.provides, wanted)) {
                        return j;
                    }
                }
            }
            return -1;
        }

        /**
         * The cell to give up on after a search exhausted: the deepest one that
         * was ever handed an empty candidate pool, which is the cell the floor
         * could not solve. If that one is already forced, any cell that is not
         * will do, and the round after that forces the lot.
         */
        private PlanCell blockingCell() {
            if (deepestEmpty >= 0 && !forced.contains(order.get(deepestEmpty))) {
                return order.get(deepestEmpty);
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                if (!forced.contains(order.get(i))) {
                    return order.get(i);
                }
            }
            return null;
        }

        /**
         * 6.6 step 3: the bag's tags, plus the {@code provides} of every cell at
         * a root distance strictly below this one. The order is depth-sorted, so
         * that is a prefix scan that stops at the first cell of this cell's own
         * depth: a same-depth neighbour's provides are deliberately not visible,
         * because the player is not guaranteed to have visited it first.
         */
        private Set<String> availableFor(int index) {
            Set<String> available = new LinkedHashSet<>(bagTags);
            int depth = depths.getOrDefault(order.get(index), 0);
            for (int i = 0; i < index; i++) {
                if (depths.getOrDefault(order.get(i), 0) >= depth) {
                    break;
                }
                if (assignments[i] != null) {
                    available.addAll(assignments[i].entry().meta.provides);
                }
            }
            return available;
        }

        Map<PlanCell, DungeonPlan.PlacedRoom> placed() {
            Map<PlanCell, DungeonPlan.PlacedRoom> out = new LinkedHashMap<>();
            for (int i = 0; i < order.size(); i++) {
                RoomManifest.Match match = assignments[i];
                out.put(order.get(i), new DungeonPlan.PlacedRoom(match.entry().name, match.rotation()));
            }
            return out;
        }

        Set<PlanCell> fallbacks() {
            return Set.copyOf(forced);
        }

        int backtrackSteps() {
            return steps;
        }

        /**
         * One cell's remaining candidates, drawn from without replacement.
         *
         * <p>The first draw is the ordinary weighted pick the selector has
         * always made. Later draws only happen when the cells after this one
         * could not be solved, and they walk the same weighted distribution
         * with the already-tried rooms removed.
         */
        private final class CellState {

            private final List<RoomManifest.Match> remaining;
            private final Random rng;

            /**
             * Whether this cell had nothing to choose from the moment it was
             * reached, which means a {@code requires} conflict rather than a
             * cell that tried everything it had. {@link #retryFrom} needs the
             * difference.
             */
            private final boolean startedEmpty;

            CellState(int index) {
                PlanCell cell = order.get(index);
                List<RoomManifest.Match> all = matches.get(index);
                List<RoomManifest.Match> pool;
                if (forced.contains(cell)) {
                    pool = roleOnly(all);
                } else {
                    int branch = branches.getOrDefault(cell, SPINE_BRANCH);
                    pool = solvable(all, availableFor(index), onSpine.contains(cell),
                            gatedBefore(index).contains(branch), cell.equals(gateCell));
                }
                this.startedEmpty = pool.isEmpty();
                if (pool.isEmpty()) {
                    deepestEmpty = Math.max(deepestEmpty, index);
                }
                this.remaining = preferred(pool, depths.getOrDefault(cell, 0), usedBefore(index));
                // Seeded per cell rather than off one shared stream, so a cell
                // re-entered after an upstream change draws the same order it
                // would have drawn the first time.
                this.rng = new Random(shape.seed() * 31 + 17 + 1013L * index);
            }

            RoomManifest.Match next() {
                if (remaining.isEmpty()) {
                    return null;
                }
                int total = 0;
                for (RoomManifest.Match match : remaining) {
                    total += Math.max(1, match.entry().meta.weight);
                }
                int roll = rng.nextInt(total);
                for (int i = 0; i < remaining.size(); i++) {
                    roll -= Math.max(1, remaining.get(i).entry().meta.weight);
                    if (roll < 0) {
                        return remaining.remove(i);
                    }
                }
                return remaining.remove(remaining.size() - 1);
            }
        }

        /** How many times each room is already placed at cells before this one. */
        private Map<String, Integer> usedBefore(int index) {
            Map<String, Integer> used = new HashMap<>();
            for (int i = 0; i < index; i++) {
                if (assignments[i] != null) {
                    used.merge(assignments[i].entry().name, 1, Integer::sum);
                }
            }
            return used;
        }

        /** Which branches have already spent their one gated cell (spec 6.1). */
        private Set<Integer> gatedBefore(int index) {
            Set<Integer> gated = new HashSet<>();
            for (int i = 0; i < index; i++) {
                if (assignments[i] != null
                        && DungeonRoomMeta.ACCESS_GATED.equals(assignments[i].entry().meta.access)) {
                    gated.add(branches.getOrDefault(order.get(i), SPINE_BRANCH));
                }
            }
            return gated;
        }
    }

    /** Cells in BFS order: shallowest first, then by grid position for stability. */
    private static List<PlanCell> bfsOrder(Set<PlanCell> cells, Map<PlanCell, Integer> depths) {
        List<PlanCell> order = new ArrayList<>(cells);
        order.sort(Comparator.comparingInt((PlanCell c) -> depths.getOrDefault(c, 0))
                .thenComparingInt(PlanCell::x)
                .thenComparingInt(PlanCell::z));
        return order;
    }

    /** Neighbour lists over the open edges, each sorted so a walk is reproducible. */
    private static Map<PlanCell, List<PlanCell>> adjacency(Set<PlanEdge> edges) {
        Map<PlanCell, List<PlanCell>> adjacency = new HashMap<>();
        for (PlanEdge edge : edges) {
            adjacency.computeIfAbsent(edge.a(), k -> new ArrayList<>()).add(edge.b());
            adjacency.computeIfAbsent(edge.b(), k -> new ArrayList<>()).add(edge.a());
        }
        Comparator<PlanCell> byPosition =
                Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z);
        for (List<PlanCell> neighbors : adjacency.values()) {
            neighbors.sort(byPosition);
        }
        return adjacency;
    }

    /**
     * The shortest entrance-to-staging-room route, which is the only route the
     * player is guaranteed to take (spec 6.5) and the one the {@code access}
     * rule and the consumable rule are written against. Returns a list from
     * {@code from} to {@code to} inclusive, or just {@code from} if the two are
     * not connected, which shape validation would already have rejected.
     */
    private static List<PlanCell> shortestPath(DungeonShape shape, PlanCell from, PlanCell to) {
        Map<PlanCell, List<PlanCell>> adjacency = adjacency(shape.openEdges());
        Map<PlanCell, PlanCell> parents = new HashMap<>();
        Set<PlanCell> seen = new HashSet<>();
        Deque<PlanCell> queue = new ArrayDeque<>();
        seen.add(from);
        queue.add(from);
        while (!queue.isEmpty()) {
            PlanCell current = queue.poll();
            if (current.equals(to)) {
                break;
            }
            for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                if (seen.add(neighbor)) {
                    parents.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        if (!seen.contains(to)) {
            return List.of(from);
        }
        List<PlanCell> path = new ArrayList<>();
        for (PlanCell cell = to; cell != null; cell = parents.get(cell)) {
            path.add(cell);
            if (cell.equals(from)) {
                break;
            }
        }
        java.util.Collections.reverse(path);
        return path;
    }

    /**
     * One id per branch, for spec 6.1's "at most one gated cell per branch".
     * Every cell on the spine shares {@link #SPINE_BRANCH}; every other cell
     * takes the id of its connected component once the spine is removed, which
     * is exactly "the cells you reach by leaving the main route at one point".
     */
    private static Map<PlanCell, Integer> branchGroups(DungeonShape shape, Set<PlanCell> onSpine) {
        Map<PlanCell, List<PlanCell>> adjacency = adjacency(shape.openEdges());
        Map<PlanCell, Integer> groups = new HashMap<>();
        for (PlanCell cell : onSpine) {
            groups.put(cell, SPINE_BRANCH);
        }
        int next = 0;
        List<PlanCell> ordered = new ArrayList<>(shape.cells());
        ordered.sort(Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z));
        for (PlanCell start : ordered) {
            if (groups.containsKey(start)) {
                continue;
            }
            int id = next++;
            Deque<PlanCell> queue = new ArrayDeque<>();
            groups.put(start, id);
            queue.add(start);
            while (!queue.isEmpty()) {
                PlanCell current = queue.poll();
                for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
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
     * The spine cell that should carry the floor's guaranteed gate (spec 6.1:
     * "at least one gated cell on the critical path between entrance and
     * staging room"). Entrance and terminal are excluded: one is the way in and
     * the other is the reward. Audit finding 2.16 is why this only ever looks
     * at spine cells, since a dead-end spur's gate is one the player can walk
     * past without ever meeting.
     *
     * <p>Rolled off its own {@link Random} rather than the selection rng, which
     * has not been consumed yet at this point and whose consumption would then
     * depend on how many cells the shape has.
     *
     * @return the chosen cell, or {@code null} when no spine cell has a gated
     *         room to offer, which is every floor built from a catalogue with
     *         no gated rooms in it
     */
    private static PlanCell designateGateCell(DungeonShape shape, List<PlanCell> spine,
                                              RoomManifest manifest, String theme) {
        List<PlanCell> eligible = new ArrayList<>();
        for (PlanCell cell : spine) {
            if (cell.equals(shape.entrance()) || cell.equals(shape.terminal())) {
                continue;
            }
            String role = shape.roles().get(cell);
            if (role == null) {
                continue;
            }
            for (RoomManifest.Match match : manifest.queryAnyRotation(
                    requiredMask(shape, cell), role, theme)) {
                if (DungeonRoomMeta.ACCESS_GATED.equals(match.entry().meta.access)) {
                    eligible.add(cell);
                    break;
                }
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return eligible.get(new Random(shape.seed() * 53 + 7).nextInt(eligible.size()));
    }

    /**
     * The candidates a cell may legally take: 6.6's subset filter, plus the two
     * placement rules that ride along with it.
     *
     * <p>The gate preference is a preference and not a constraint, for the same
     * reason {@link #pick}'s depth and repeat filters are: a floor that fails to
     * resolve because the one room that could have carried its gate was also the
     * one room that fit the mask is worse than a floor with no gate on it.
     *
     * @param onSpine      whether this cell is on the entrance-to-staging path
     * @param branchGated  whether this cell's branch already has its one gate
     * @param isGateCell   whether this cell is the floor's designated gate
     */
    private static List<RoomManifest.Match> solvable(List<RoomManifest.Match> matches,
                                                     Set<String> available, boolean onSpine,
                                                     boolean branchGated, boolean isGateCell) {
        List<RoomManifest.Match> pool = withTags(matches, available, onSpine, branchGated);
        if (pool.isEmpty() && branchGated) {
            // The one-gate-per-branch rule emptied the pool on its own. It is a
            // spacing rule, and spacing is not worth a cell falling back to a
            // room that asks for nothing when a solvable room was sitting
            // right there, so it yields. On any catalogue with an open room per
            // role this never fires.
            pool = withTags(matches, available, onSpine, false);
        }
        if (!isGateCell) {
            return pool;
        }
        List<RoomManifest.Match> gated = new ArrayList<>();
        for (RoomManifest.Match match : pool) {
            if (DungeonRoomMeta.ACCESS_GATED.equals(match.entry().meta.access)) {
                gated.add(match);
            }
        }
        return gated.isEmpty() ? pool : gated;
    }

    /** 6.6's subset filter plus the consumable and one-gate-per-branch rules. */
    private static List<RoomManifest.Match> withTags(List<RoomManifest.Match> matches,
                                                     Set<String> available, boolean onSpine,
                                                     boolean branchGated) {
        List<RoomManifest.Match> pool = new ArrayList<>(matches.size());
        for (RoomManifest.Match match : matches) {
            DungeonRoomMeta meta = match.entry().meta;
            if (!available.containsAll(meta.requires)) {
                continue;
            }
            if (onSpine && !java.util.Collections.disjoint(meta.requires, CONSUMABLE_TAGS)) {
                continue;
            }
            if (branchGated && DungeonRoomMeta.ACCESS_GATED.equals(meta.access)) {
                continue;
            }
            pool.add(match);
        }
        return pool;
    }

    /**
     * 6.6 step 6's last resort: the rooms that ask for nothing. A plain corridor
     * or encounter is always solvable, so a bag can make a floor duller and
     * never impossible. If even that is empty the whole match list comes back,
     * because failing the plan here would reintroduce M3's failure mode.
     */
    private static List<RoomManifest.Match> roleOnly(List<RoomManifest.Match> matches) {
        List<RoomManifest.Match> pool = new ArrayList<>(matches.size());
        for (RoomManifest.Match match : matches) {
            if (match.entry().meta.requires.isEmpty()) {
                pool.add(match);
            }
        }
        return pool.isEmpty() ? matches : pool;
    }

    private static List<RoomManifest.Match> sortedMatches(List<RoomManifest.Match> matches) {
        List<RoomManifest.Match> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparing((RoomManifest.Match m) -> m.entry().name)
                .thenComparingInt(RoomManifest.Match::rotation));
        return sorted;
    }

    /**
     * Hop count from the entrance, over open edges.
     *
     * <p>M45 already computes this once, in the same walk the generator's
     * reachability check uses, and hands it over on the shape. This reads it
     * back rather than walking again; the walk below is kept for a shape
     * assembled by hand without distances, and for the cell an unfinished shape
     * leaves out of the map, which is defaulted rather than left null for the
     * stamper to trip over.
     */
    private static Map<PlanCell, Integer> depths(DungeonShape shape) {
        Map<PlanCell, Integer> known = shape.rootDistances();
        if (known != null && known.keySet().containsAll(shape.cells())) {
            return new HashMap<>(known);
        }
        Map<PlanCell, List<PlanCell>> adjacency = adjacency(shape.openEdges());

        Map<PlanCell, Integer> depths = new HashMap<>();
        Deque<PlanCell> queue = new ArrayDeque<>();
        depths.put(shape.entrance(), 0);
        queue.add(shape.entrance());
        while (!queue.isEmpty()) {
            PlanCell current = queue.poll();
            int next = depths.get(current) + 1;
            for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                if (depths.putIfAbsent(neighbor, next) == null) {
                    queue.add(neighbor);
                }
            }
        }
        // An unreachable cell would already have failed shape validation. Default
        // it rather than leave a null in the map for the stamper to trip over.
        for (PlanCell cell : shape.cells()) {
            depths.putIfAbsent(cell, 0);
        }
        return depths;
    }

    private static int requiredMask(DungeonShape shape, PlanCell cell) {
        Set<DoorMask.Direction> dirs = new LinkedHashSet<>();
        for (PlanEdge edge : shape.openEdges()) {
            if (!edge.touches(cell)) {
                continue;
            }
            PlanCell other = edge.other(cell);
            DoorMask.Direction dir = cell.directionTo(other);
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return DoorMask.fromEdges(dirs);
    }

    private static Set<PlanCell> reachableCells(DungeonPlan plan) {
        Set<PlanCell> reachable = new HashSet<>();
        reachable.add(plan.entrance());

        boolean changed;
        do {
            changed = false;
            for (PlanCell cell : List.copyOf(reachable)) {
                for (PlanEdge edge : plan.doors()) {
                    if (!edge.touches(cell)) {
                        continue;
                    }
                    if (reachable.add(edge.other(cell))) {
                        changed = true;
                    }
                }
            }
        } while (changed);
        return reachable;
    }

    /**
     * @param fallbackCells  M47: cells the solvability pass could not satisfy
     *                       and filled with a role-only, empty-{@code requires}
     *                       room instead. Empty on a clean resolve. A run is
     *                       never unsolvable because of a bag (6.6 step 6); it
     *                       is only less interesting, and this set says by how
     *                       much.
     * @param backtrackSteps how many times the pass gave a cell back and took
     *                       the previous cell's next candidate. Zero means the
     *                       first greedy assignment was already satisfying.
     */
    record Result(DungeonPlan plan, Failure failure, Set<PlanCell> fallbackCells,
                  int backtrackSteps) {

        Result(DungeonPlan plan, Failure failure) {
            this(plan, failure, Set.of(), 0);
        }
    }

    record Failure(PlanCell cell, int mask, String role) {}
}
