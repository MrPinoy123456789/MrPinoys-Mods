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
        List<PlanCell> order = sortedCells(shape.cells());
        Map<PlanCell, DungeonPlan.PlacedRoom> placed = new LinkedHashMap<>();
        Map<PlanCell, Integer> depths = depths(shape);

        // Derived from the plan seed rather than reusing the shape generator's
        // Random, which has already been consumed by the time resolution runs.
        Random rng = new Random(shape.seed() * 31 + 17);
        Map<String, Integer> used = new HashMap<>();

        for (PlanCell cell : order) {
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
            matches = new ArrayList<>(matches);
            matches.sort(Comparator.comparing((RoomManifest.Match m) -> m.entry().name)
                    .thenComparingInt(RoomManifest.Match::rotation));

            RoomManifest.Match pick = pick(matches, depths.getOrDefault(cell, 0), used, rng);
            placed.put(cell, new DungeonPlan.PlacedRoom(pick.entry().name, pick.rotation()));
            used.merge(pick.entry().name, 1, Integer::sum);
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
        return new Result(plan, null);
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

    /** Hop count from the entrance, over open edges. */
    private static Map<PlanCell, Integer> depths(DungeonShape shape) {
        Map<PlanCell, List<PlanCell>> adjacency = new HashMap<>();
        for (PlanEdge edge : shape.openEdges()) {
            adjacency.computeIfAbsent(edge.a(), k -> new ArrayList<>()).add(edge.b());
            adjacency.computeIfAbsent(edge.b(), k -> new ArrayList<>()).add(edge.a());
        }

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

    private static List<PlanCell> sortedCells(Set<PlanCell> cells) {
        List<PlanCell> order = new ArrayList<>(cells);
        order.sort(Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z));
        return order;
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

    record Result(DungeonPlan plan, Failure failure) {}

    record Failure(PlanCell cell, int mask, String role) {}
}
