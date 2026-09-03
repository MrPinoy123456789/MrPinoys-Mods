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
        Map<PlanCell, Integer> depths = depths(shape);
        List<PlanCell> order = bfsOrder(shape.cells(), depths);
        Map<PlanCell, DungeonPlan.PlacedRoom> placed = new LinkedHashMap<>();

        List<PlanCell> spine = shortestPath(shape, shape.entrance(), shape.terminal());
        Set<PlanCell> onSpine = new HashSet<>(spine);
        Map<PlanCell, Integer> branches = branchGroups(shape, onSpine);
        PlanCell gateCell = designateGateCell(shape, spine, manifest, theme);

        // Derived from the plan seed rather than reusing the shape generator's
        // Random, which has already been consumed by the time resolution runs.
        Random rng = new Random(shape.seed() * 31 + 17);
        Map<String, Integer> used = new HashMap<>();

        Set<String> available = new LinkedHashSet<>(bagTags == null ? Set.of() : bagTags);
        // Provides picked at the depth being processed. They land in available
        // only once the walk moves past that depth: 6.6's same-depth rule, and
        // the reason this is two sets and not one.
        Set<String> sameDepth = new LinkedHashSet<>();
        int currentDepth = -1;

        Set<PlanCell> fallbacks = new LinkedHashSet<>();
        Set<Integer> gatedBranches = new HashSet<>();

        for (PlanCell cell : order) {
            int depth = depths.getOrDefault(cell, 0);
            if (depth != currentDepth) {
                available.addAll(sameDepth);
                sameDepth.clear();
                currentDepth = depth;
            }

            int mask = requiredMask(shape, cell);
            String role = shape.roles().get(cell);
            if (role == null) {
                return new Result(null, new Failure(cell, mask, "missing role"));
            }

            List<RoomManifest.Match> matches = manifest.queryAnyRotation(mask, role, theme);
            if (matches.isEmpty()) {
                return new Result(null, new Failure(cell, mask, role));
            }
            // Stable order in, so the weighted draw is reproducible regardless of
            // the order the manifest happens to hold its rooms in.
            matches = sortedMatches(matches);

            int branch = branches.getOrDefault(cell, SPINE_BRANCH);
            List<RoomManifest.Match> pool = solvable(matches, available, onSpine.contains(cell),
                    gatedBranches.contains(branch), cell.equals(gateCell));
            if (pool.isEmpty()) {
                pool = roleOnly(matches);
                fallbacks.add(cell);
            }

            RoomManifest.Match pick = pick(pool, depth, used, rng);
            placed.put(cell, new DungeonPlan.PlacedRoom(pick.entry().name, pick.rotation()));
            used.merge(pick.entry().name, 1, Integer::sum);
            sameDepth.addAll(pick.entry().meta.provides);
            if (DungeonRoomMeta.ACCESS_GATED.equals(pick.entry().meta.access)) {
                gatedBranches.add(branch);
            }
        }

        PlanCell anomalyCell = rollAnomaly(shape, theme, placed, depths);

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
        return new Result(plan, null, Set.copyOf(fallbacks), 0);
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
        if (eligible.isEmpty()) {
            eligible = matches;
        }

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
