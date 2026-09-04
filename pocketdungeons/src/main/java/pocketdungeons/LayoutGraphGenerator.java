package pocketdungeons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generates the abstract graph shape of a dungeon instance: a self-avoiding
 * critical path, optional branch spurs, optional loop edges, and role
 * assignment. This class is intentionally pure JDK (no Minecraft imports) so
 * it can be compiled and verified without a Minecraft classpath.
 *
 * <p>This is the "Agent A" half of M3. Its output is a {@link DungeonShape}
 * that the content-resolution half (Agent B) turns into actual placed rooms.
 *
 * <p>The generation algorithm is deterministic for a given {@code seed}.
 */
public final class LayoutGraphGenerator {

    /**
     * Probability that a path cell attempts to grow a branch spur. Only the
     * default for the 3-argument {@link #generate} -- callers with a config
     * pass their own through the 5-argument overload.
     */
    private static final double BRANCH_PROBABILITY = 0.55;

    /** Probability that an adjacent-but-unconnected pair of cells becomes a loop edge. */
    private static final double LOOP_PROBABILITY = 0.30;

    /** Role weights for interior critical-path cells, out of 100. */
    private static final int ENCOUNTER_WEIGHT = 45;
    private static final int LOOT_WEIGHT = 25;
    // corridor takes the remaining 30.

    static final String ROLE_ENTRANCE = "entrance";
    static final String ROLE_EXIT = "exit";
    static final String ROLE_ENCOUNTER = "encounter";
    static final String ROLE_LOOT = "loot";
    static final String ROLE_CORRIDOR = "corridor";

    private static final int MIN_BRANCH_DEPTH = 1;
    private static final int MAX_BRANCH_DEPTH = 3;

    /** Safety cap on the recursive backtracking search for the critical path. */
    private static final int MAX_BACKTRACK_STEPS = 50000;

    private LayoutGraphGenerator() {}

    /**
     * Generates a dungeon shape for the given seed.
     *
     * @param seed            the random seed
     * @param minPathLength   minimum number of cells on the critical path (inclusive)
     * @param maxPathLength   maximum number of cells on the critical path (inclusive)
     * @return a {@link DungeonShape}, or {@code null} if the backtracker could
     *         not build a self-avoiding critical path of the requested length
     *         within the backtracking budget
     * @throws IllegalArgumentException if {@code minPathLength < 2} or
     *         {@code maxPathLength < minPathLength}
     */
    public static DungeonShape generate(long seed, int minPathLength, int maxPathLength) {
        return generate(seed, minPathLength, maxPathLength, BRANCH_PROBABILITY, LOOP_PROBABILITY);
    }

    /**
     * As {@link #generate(long, int, int)}, with the branch and loop rates supplied
     * by the caller. Both are config-driven in live play; the 3-argument form keeps
     * the original constants so the pure-JDK tests are not tied to a config file.
     */
    public static DungeonShape generate(long seed, int minPathLength, int maxPathLength,
                                        double branchProbability, double loopProbability) {
        if (minPathLength < 2) {
            throw new IllegalArgumentException("minPathLength must be at least 2: " + minPathLength);
        }
        if (maxPathLength < minPathLength) {
            throw new IllegalArgumentException(
                "maxPathLength (" + maxPathLength + ") must be >= minPathLength (" + minPathLength + ")");
        }

        Random rng = new Random(seed);
        int targetLength = minPathLength + rng.nextInt(maxPathLength - minPathLength + 1);

        List<PlanCell> criticalPath = generateCriticalPath(rng, targetLength);
        if (criticalPath == null) {
            return null;
        }

        // Compute the entrance direction from the first step. Branches must
        // not place cells behind the entrance (the no-backwards-propagation
        // rule, spec M29). The critical path itself is still checked by
        // validate(), which lets the planner retry; but preventing branches
        // from going behind the entrance eliminates the most common cause.
        DoorMask.Direction entranceDir = entranceDirectionOf(criticalPath);

        int fullPathLength = criticalPath.size();

        // M54 (spec 6.5): the terminal is placed at a critical-path index between
        // 60% and 90% of the path, not at the end. The cells past it are a decoy
        // branch: still in the cells set and still connected by open edges, but
        // not part of the stored critical path. The player who walks the longest
        // corridor does not automatically find the staging room.
        int terminalIndex = pickTerminalIndex(rng, fullPathLength);

        Set<PlanCell> cells = new HashSet<>(criticalPath);
        Set<PlanEdge> openEdges = new HashSet<>();
        for (int i = 0; i < criticalPath.size() - 1; i++) {
            openEdges.add(new PlanEdge(criticalPath.get(i), criticalPath.get(i + 1)));
        }

        PlanCell entrance = criticalPath.get(0);
        PlanCell terminal = criticalPath.get(terminalIndex);

        // The stored critical path ends at the terminal. The cells past it
        // (terminalIndex+1 .. end) are decoy cells: still connected through the
        // terminal, which now carries two doors (one back along the path, one
        // forward into the decoy). The entrance stays single-door; addBranches
        // and addLoops both skip the terminal, so no extra edges land on it.
        List<PlanCell> storedPath = new ArrayList<>(criticalPath.subList(0, terminalIndex + 1));

        addBranches(rng, storedPath, cells, openEdges, branchProbability, entranceDir);
        addLoops(rng, cells, openEdges, loopProbability, entrance, terminal);

        Map<PlanCell, String> roles = assignRoles(rng, storedPath, cells, openEdges);

        // M45 (spec 6.6): the one BFS from the entrance. validate() reads it back
        // off the shape for its reachability check rather than walking again.
        Map<PlanCell, Integer> distances = rootDistances(cells, openEdges, entrance);

        // M54 (spec 6.5): the staging room is never visible from the front door.
        // A shape where the terminal shares a row or column with the entrance
        // through an unbroken run of open doorways is rejected here so the caller
        // retries with the next seed, the same way it retries on a
        // no-backwards-propagation shape.
        if (hasLineOfSight(cells, openEdges, entrance, terminal)) {
            return null;
        }

        return new DungeonShape(seed, cells, openEdges, entrance, terminal, storedPath, roles,
                distances, fullPathLength);
    }

    /**
     * Validates a dungeon shape, returning a list of problem descriptions.
     * An empty list means the shape is valid.
     *
     * <p>Checks performed:
     * <ul>
     *   <li>Every edge references two cells that exist in {@code cells()}.</li>
     *   <li>BFS from the entrance reaches every cell.</li>
     *   <li>The critical path is contiguous and each step has a matching open edge.</li>
     *   <li>Every cell has a role, and roles are only assigned to real cells.</li>
     *   <li>The entrance has exactly one door; the terminal has one or two (M54:
     *       two when a decoy branch runs past it).</li>
     *   <li>No cell sits behind the entrance (M29 no-backwards-propagation).</li>
     *   <li>The terminal is not in line of sight from the entrance (M54 spec 6.5:
     *       no straight run of open doorways between them).</li>
     * </ul>
     */
    public static List<String> validate(DungeonShape shape) {
        List<String> problems = new ArrayList<>();
        if (shape == null) {
            problems.add("shape is null");
            return problems;
        }

        Set<PlanCell> cells = shape.cells();
        Set<PlanEdge> edges = shape.openEdges();

        // No overlap is structurally guaranteed because cells is a Set<PlanCell>.

        for (PlanEdge edge : edges) {
            if (!cells.contains(edge.a())) {
                problems.add("edge " + edge + " references missing cell " + edge.a());
            }
            if (!cells.contains(edge.b())) {
                problems.add("edge " + edge + " references missing cell " + edge.b());
            }
        }

        if (!cells.contains(shape.entrance())) {
            problems.add("entrance cell " + shape.entrance() + " is not in cells()");
        } else if (!cells.contains(shape.terminal())) {
            problems.add("terminal cell " + shape.terminal() + " is not in cells()");
        } else {
            // M45: the shape carries the BFS depths this check used to compute
            // for itself, so a cell missing from the map is an unreachable cell.
            Map<PlanCell, Integer> reachable = shape.rootDistances();
            for (PlanCell cell : cells) {
                if (!reachable.containsKey(cell)) {
                    problems.add("cell " + cell + " is unreachable from entrance");
                }
            }

            // Ensure the terminal is reachable through the graph (redundant with BFS,
            // but the critical-path-specific checks below guarantee adjacency).
        }

        List<PlanCell> path = shape.criticalPath();
        for (int i = 0; i < path.size() - 1; i++) {
            PlanCell a = path.get(i);
            PlanCell b = path.get(i + 1);
            if (a.directionTo(b) == null) {
                problems.add("critical path step " + i + " is not adjacent: " + a + " -> " + b);
            } else {
                PlanEdge expected = new PlanEdge(a, b);
                if (!edges.contains(expected)) {
                    problems.add("critical path step " + i + " has no open edge: " + a + " -> " + b);
                }
            }
        }

        // The entrance must stay single-door: the room library ships exactly one
        // entrance template, and it covers all four single-door masks by rotation
        // and nothing else. A second door on the entrance is unresolvable content.
        // The terminal (M54) may carry one or two doors: one back along the
        // critical path, and optionally one forward into the decoy branch when
        // the terminal is placed partway along the path rather than at the end.
        Map<PlanCell, Integer> degrees = degrees(cells, edges);
        int entranceDegree = degrees.getOrDefault(shape.entrance(), 0);
        if (entranceDegree != 1) {
            problems.add("entrance cell " + shape.entrance() + " has " + entranceDegree
                    + " doors, expected exactly 1");
        }
        int terminalDegree = degrees.getOrDefault(shape.terminal(), 0);
        if (terminalDegree < 1 || terminalDegree > 2) {
            problems.add("terminal cell " + shape.terminal() + " has " + terminalDegree
                    + " doors, expected 1 or 2");
        }

        Map<PlanCell, String> roles = shape.roles();
        for (PlanCell cell : cells) {
            if (!roles.containsKey(cell)) {
                problems.add("cell " + cell + " has no assigned role");
            }
        }
        for (PlanCell cell : roles.keySet()) {
            if (!cells.contains(cell)) {
                problems.add("role assigned to cell " + cell + " which is not in cells()");
            }
        }

        // U6's key budget. Every loot cell is a vault and every vault wants a key
        // only an encounter cell's spawner ejects, so a shape with more vaults
        // than spawners hands a player a lock with no key. balanceKeyBudget makes
        // this true by construction; the check is here so a regression in that
        // pass fails loudly in the pure-JDK sweep instead of surfacing weeks later
        // as "one vault in my dungeon never opens".
        int loot = countRole(roles, ROLE_LOOT);
        int encounter = countRole(roles, ROLE_ENCOUNTER);
        if (loot > encounter) {
            problems.add("shape has " + loot + " loot cell(s) but only " + encounter
                    + " encounter cell(s); every vault needs a key");
        }

        // A dungeon must never wrap around behind the player's entrance room --
        // it reads as the entrance suddenly having rooms on both sides of a wall
        // that was solid a moment ago. The entrance sits at the origin and the
        // check runs pre-rotation, but DungeonShape.rotate is a rigid turn about
        // that same origin, so a shape with nothing behind the entrance here has
        // nothing behind it after rotation either.
        DoorMask.Direction entranceDir = shape.entranceDirection();
        if (entranceDir != null) {
            for (PlanCell cell : cells) {
                if (isBehindEntrance(cell, entranceDir)) {
                    problems.add("cell " + cell + " " + NO_BACKWARDS_MARKER + " " + entranceDir + " axis");
                }
            }
        }

        // M54 (spec 6.5): the staging room is never visible from the front door.
        // The terminal must not share a row or column with the entrance through
        // an unbroken run of open doorways. generate() filters these out before
        // returning, so a shape that reaches this check with a violation was
        // hand-built (or constructed by a path the generator rejected).
        if (hasLineOfSight(cells, edges, shape.entrance(), shape.terminal())) {
            problems.add("terminal " + shape.terminal() + " " + LINE_OF_SIGHT_MARKER
                    + " " + shape.entrance());
        }

        return problems;
    }

    /**
     * Marks a no-backwards-propagation problem so callers (namely
     * {@link LayoutPlanner}) can tell it apart from a genuine generator bug and
     * retry instead of hard-failing.
     */
    static final String NO_BACKWARDS_MARKER = "is behind the entrance on the";

    /**
     * Marks a line-of-sight problem (spec 6.5): the terminal is visible from
     * the entrance through a straight run of open doorways. {@code generate}
     * filters these out before returning, so a shape that reaches
     * {@code validate} with this marker was hand-built for testing.
     */
    static final String LINE_OF_SIGHT_MARKER = "is in line of sight from the entrance";

    /**
     * Picks the terminal index at 60-90% of the full path (spec 6.5). The
     * cells past it become a decoy branch. The minimum uses ceil so the stored
     * critical path always has at least two interior cells for the
     * encounter/loot role guarantee; paths too short for that (fewer than 5
     * cells) keep the terminal at the end.
     */
    private static int pickTerminalIndex(Random rng, int pathLength) {
        int last = pathLength - 1;
        if (last < 4) {
            return last;
        }
        int minN = Math.max(1, (int) Math.ceil(0.6 * last));
        int maxN = Math.min(last - 1, (int) Math.floor(0.9 * last));
        if (minN > maxN) {
            return last;
        }
        return minN + rng.nextInt(maxN - minN + 1);
    }

    /**
     * Whether the terminal shares a row or column with the entrance through an
     * unbroken run of open doorways (spec 6.5). The entrance is the root of the
     * graph; a straight corridor from it to the staging room makes the room
     * visible from the front door, which is exactly what the staging room must
     * never be.
     */
    private static boolean hasLineOfSight(Set<PlanCell> cells, Set<PlanEdge> edges,
                                          PlanCell entrance, PlanCell terminal) {
        int ex = entrance.x(), ez = entrance.z();
        int tx = terminal.x(), tz = terminal.z();

        if (tx == ex && tz != ez) {
            int step = Integer.signum(tz - ez);
            PlanCell prev = entrance;
            for (int z = ez + step; z != tz; z += step) {
                PlanCell cur = new PlanCell(ex, z);
                if (!cells.contains(cur) || !edges.contains(new PlanEdge(prev, cur))) {
                    return false;
                }
                prev = cur;
            }
            return edges.contains(new PlanEdge(prev, terminal));
        }

        if (tz == ez && tx != ex) {
            int step = Integer.signum(tx - ex);
            PlanCell prev = entrance;
            for (int x = ex + step; x != tx; x += step) {
                PlanCell cur = new PlanCell(x, ez);
                if (!cells.contains(cur) || !edges.contains(new PlanEdge(prev, cur))) {
                    return false;
                }
                prev = cur;
            }
            return edges.contains(new PlanEdge(prev, terminal));
        }

        return false;
    }

    private static List<PlanCell> generateCriticalPath(Random rng, int targetLength) {
        List<PlanCell> path = new ArrayList<>(targetLength);
        Set<PlanCell> visited = new HashSet<>(targetLength * 2);
        path.add(new PlanCell(0, 0));
        visited.add(path.get(0));

        Counter steps = new Counter();
        boolean success = extendPath(rng, path, visited, targetLength, steps);
        return success ? path : null;
    }

    private static boolean extendPath(Random rng, List<PlanCell> path, Set<PlanCell> visited,
                                      int targetLength, Counter steps) {
        if (path.size() == targetLength) {
            return true;
        }
        if (steps.value++ > MAX_BACKTRACK_STEPS) {
            return false;
        }

        PlanCell current = path.get(path.size() - 1);
        List<DoorMask.Direction> directions = shuffledDirections(rng);
        for (DoorMask.Direction dir : directions) {
            PlanCell next = current.neighbor(dir);
            if (visited.add(next)) {
                path.add(next);
                if (extendPath(rng, path, visited, targetLength, steps)) {
                    return true;
                }
                path.remove(path.size() - 1);
                visited.remove(next);
            }
        }
        return false;
    }

    /**
     * The entrance direction, determined by the first step of the critical
     * path. {@code null} if the path has only one cell (no steps).
     */
    private static DoorMask.Direction entranceDirectionOf(List<PlanCell> criticalPath) {
        if (criticalPath.size() < 2) {
            return null;
        }
        PlanCell entrance = criticalPath.get(0);
        PlanCell first = criticalPath.get(1);
        int dx = first.x() - entrance.x();
        int dz = first.z() - entrance.z();
        if (dx == 1) return DoorMask.Direction.EAST;
        if (dx == -1) return DoorMask.Direction.WEST;
        if (dz == 1) return DoorMask.Direction.SOUTH;
        if (dz == -1) return DoorMask.Direction.NORTH;
        return null;
    }

    /**
     * Whether {@code cell} is behind the entrance relative to {@code entranceDir}.
     * The entrance is at (0,0); "behind" is the half-plane opposite the
     * entrance direction.
     */
    private static boolean isBehindEntrance(PlanCell cell, DoorMask.Direction entranceDir) {
        return switch (entranceDir) {
            case EAST -> cell.x() < 0;
            case WEST -> cell.x() > 0;
            case NORTH -> cell.z() > 0;
            case SOUTH -> cell.z() < 0;
        };
    }

    private static void addBranches(Random rng, List<PlanCell> criticalPath,
                                    Set<PlanCell> cells, Set<PlanEdge> openEdges,
                                    double branchProbability, DoorMask.Direction entranceDir) {
        double center = (criticalPath.size() - 1) / 2.0;
        // Skip index 0 and the last index: no spur ever roots at the entrance or
        // the terminal, so both keep a single-door mask.
        for (int i = 1; i < criticalPath.size() - 1; i++) {
            double distanceFromCenter = Math.abs(i - center) / center;
            double weight = 0.25 + 0.75 * (1.0 - distanceFromCenter);
            if (rng.nextDouble() >= branchProbability * weight) {
                continue;
            }

            PlanCell root = criticalPath.get(i);
            int depth = MIN_BRANCH_DEPTH + rng.nextInt(MAX_BRANCH_DEPTH - MIN_BRANCH_DEPTH + 1);
            tryGrowBranch(rng, root, depth, cells, openEdges, entranceDir);
        }
    }

    private static void tryGrowBranch(Random rng, PlanCell root, int depth,
                                      Set<PlanCell> cells, Set<PlanEdge> openEdges,
                                      DoorMask.Direction entranceDir) {
        List<DoorMask.Direction> directions = shuffledDirections(rng);
        PlanCell first = null;
        for (DoorMask.Direction dir : directions) {
            PlanCell candidate = root.neighbor(dir);
            if (!cells.contains(candidate)
                    && (entranceDir == null || !isBehindEntrance(candidate, entranceDir))) {
                first = candidate;
                break;
            }
        }
        if (first == null) {
            return;
        }

        cells.add(first);
        openEdges.add(new PlanEdge(root, first));
        PlanCell current = first;

        for (int d = 1; d < depth; d++) {
            directions = shuffledDirections(rng);
            PlanCell next = null;
            for (DoorMask.Direction dir : directions) {
                PlanCell candidate = current.neighbor(dir);
                if (!cells.contains(candidate)
                        && (entranceDir == null || !isBehindEntrance(candidate, entranceDir))) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            cells.add(next);
            openEdges.add(new PlanEdge(current, next));
            current = next;
        }
    }

    private static void addLoops(Random rng, Set<PlanCell> cells, Set<PlanEdge> openEdges,
                                 double loopProbability, PlanCell entrance, PlanCell terminal) {
        // Only consider east/south directions to visit each unordered adjacent
        // pair exactly once.
        DoorMask.Direction[] loopDirs = {DoorMask.Direction.EAST, DoorMask.Direction.SOUTH};
        for (PlanCell a : cells) {
            for (DoorMask.Direction dir : loopDirs) {
                PlanCell b = a.neighbor(dir);
                if (!cells.contains(b)) {
                    continue;
                }
                PlanEdge edge = new PlanEdge(a, b);
                if (openEdges.contains(edge)) {
                    continue;
                }
                // A loop edge onto either end cap would give it a second door.
                if (edge.touches(entrance) || edge.touches(terminal)) {
                    continue;
                }
                if (rng.nextDouble() < loopProbability) {
                    openEdges.add(edge);
                }
            }
        }
    }

    /**
     * Roles for every cell.
     *
     * <p>Interior critical-path cells draw {@code encounter}/{@code loot}/
     * {@code corridor} by weight -- corridors exist so a run does not read as an
     * unbroken string of fights. Off-path cells split on degree: the <em>tip</em>
     * of a spur (degree 1) is always {@code loot}, because a spur that pays
     * nothing punishes the player for exploring it, while the cells leading to it
     * are corridor.
     *
     * <p>A final guarantee pass forces at least one {@code encounter} and one
     * {@code loot} onto the critical path, so a run always has both regardless of
     * how the weighted draw fell.
     */
    private static Map<PlanCell, String> assignRoles(Random rng, List<PlanCell> criticalPath,
                                                       Set<PlanCell> cells, Set<PlanEdge> openEdges) {
        Map<PlanCell, String> roles = new HashMap<>(cells.size());
        PlanCell entrance = criticalPath.get(0);
        PlanCell terminal = criticalPath.get(criticalPath.size() - 1);

        roles.put(entrance, ROLE_ENTRANCE);
        roles.put(terminal, ROLE_EXIT);

        for (int i = 1; i < criticalPath.size() - 1; i++) {
            int roll = rng.nextInt(100);
            String role = roll < ENCOUNTER_WEIGHT ? ROLE_ENCOUNTER
                    : roll < ENCOUNTER_WEIGHT + LOOT_WEIGHT ? ROLE_LOOT
                    : ROLE_CORRIDOR;
            roles.put(criticalPath.get(i), role);
        }

        Map<PlanCell, Integer> degrees = degrees(cells, openEdges);
        for (PlanCell cell : cells) {
            if (roles.containsKey(cell)) {
                continue;
            }
            roles.put(cell, degrees.getOrDefault(cell, 0) <= 1 ? ROLE_LOOT : ROLE_CORRIDOR);
        }

        guaranteeOnPath(roles, criticalPath);
        balanceKeyBudget(roles, criticalPath);
        return roles;
    }

    /**
     * Makes sure the run mints at least as many keys as it has vaults to spend
     * them on: {@code loot <= encounter} (U6 Stage 4).
     *
     * <p>Under U6 a loot room is a vault, and a vault wants a trial key that only
     * an encounter room's spawner ejects. A layout with more loot cells than
     * encounter cells hands a player a vault they cannot open, which reads as a
     * bug rather than as a choice. Spur tips are forced to {@code loot} by the
     * pass above, so a branchy shape can easily produce five loot cells and two
     * encounters.
     *
     * <p>The plan called for {@code validate} to reject such a shape and let the
     * caller retry with a new seed. This rebalances instead: promoting a corridor
     * (or, failing that, demoting the loot cell furthest from the entrance)
     * <strong>always</strong> succeeds, is deterministic, and costs no retries --
     * whereas rejection would spend the whole plan budget on shapes that are one
     * role-flip away from being fine, and would quietly reintroduce M3's
     * failed-plan rate on exactly the branchy layouts U1 was built to support.
     * {@code validate}'s check stays as the regression guard that this pass ran.
     */
    private static void balanceKeyBudget(Map<PlanCell, String> roles, List<PlanCell> criticalPath) {
        List<PlanCell> ordered = new ArrayList<>(roles.keySet());
        ordered.sort((a, b) -> a.x() != b.x()
                ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));

        // Each iteration either raises the encounter count or lowers the loot
        // count by one, so this terminates in at most cells() steps.
        while (countRole(roles, ROLE_LOOT) > countRole(roles, ROLE_ENCOUNTER)) {
            // 1. Promote a corridor. This adds a fight rather than removing a
            //    reward, which is the generous direction and the one DESIGN.md's
            //    "err on the side of paying out" argues for.
            PlanCell target = firstWithRole(ordered, roles, ROLE_CORRIDOR, criticalPath, false);
            // 2. Otherwise convert an off-path loot cell, so the guaranteed
            //    on-path loot survives as long as possible.
            if (target == null) {
                target = firstWithRole(ordered, roles, ROLE_LOOT, criticalPath, true);
            }
            // 3. Last resort: an on-path loot cell. Reached when the weighted draw
            //    happened to fill a whole critical path with loot -- rare, but real
            //    (seed 78 of the 5,000-seed sweep did exactly that with four loot
            //    cells and one encounter). Losing a vault is a smaller wrong than
            //    shipping a vault nobody can open.
            if (target == null) {
                target = firstWithRole(ordered, roles, ROLE_LOOT, criticalPath, false);
            }
            if (target == null) {
                return;
            }
            roles.put(target, ROLE_ENCOUNTER);
        }
    }

    private static int countRole(Map<PlanCell, String> roles, String role) {
        int count = 0;
        for (String assigned : roles.values()) {
            if (role.equals(assigned)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The first cell carrying {@code role}, in the deterministic grid order.
     *
     * @param offPathOnly restrict to cells that are not on the critical path
     */
    private static PlanCell firstWithRole(List<PlanCell> ordered, Map<PlanCell, String> roles,
                                          String role, List<PlanCell> criticalPath,
                                          boolean offPathOnly) {
        for (PlanCell cell : ordered) {
            if (!role.equals(roles.get(cell))) {
                continue;
            }
            if (offPathOnly && criticalPath.contains(cell)) {
                continue;
            }
            return cell;
        }
        return null;
    }

    /**
     * Forces the critical path to carry at least one encounter and one loot cell.
     * The encounter is placed nearest the terminal and the loot nearest the
     * entrance, so a forced pair never lands on the same cell unless the path has
     * only one interior cell to give -- in which case encounter wins and there is
     * nothing more to do.
     */
    private static void guaranteeOnPath(Map<PlanCell, String> roles, List<PlanCell> criticalPath) {
        int interior = criticalPath.size() - 2;
        if (interior < 1) {
            return;
        }

        boolean hasEncounter = false;
        boolean hasLoot = false;
        for (int i = 1; i <= interior; i++) {
            String role = roles.get(criticalPath.get(i));
            hasEncounter |= ROLE_ENCOUNTER.equals(role);
            hasLoot |= ROLE_LOOT.equals(role);
        }

        if (interior == 1) {
            // One cell to give: it can satisfy one guarantee, never both. Only
            // promote it if it is currently satisfying neither.
            if (!hasEncounter && !hasLoot) {
                roles.put(criticalPath.get(1), ROLE_ENCOUNTER);
            }
            return;
        }

        // Each force has to avoid overwriting the role the other guarantee is
        // relying on -- a path holding a single loot cell and no encounter would
        // otherwise trade one missing role for the other.
        if (!hasEncounter) {
            roles.put(criticalPath.get(pickTarget(roles, criticalPath, interior, true, ROLE_LOOT)),
                    ROLE_ENCOUNTER);
        }
        if (!hasLoot) {
            roles.put(criticalPath.get(pickTarget(roles, criticalPath, interior, false, ROLE_ENCOUNTER)),
                    ROLE_LOOT);
        }
    }

    /**
     * The interior index to overwrite: scanning from the terminal end when
     * {@code fromTerminal}, from the entrance end otherwise, taking the first cell
     * not currently holding {@code protectedRole}. Falls back to the end of the
     * scan if every interior cell holds it.
     */
    private static int pickTarget(Map<PlanCell, String> roles, List<PlanCell> criticalPath,
                                  int interior, boolean fromTerminal, String protectedRole) {
        if (fromTerminal) {
            for (int i = interior; i >= 1; i--) {
                if (!protectedRole.equals(roles.get(criticalPath.get(i)))) {
                    return i;
                }
            }
            return interior;
        }
        for (int i = 1; i <= interior; i++) {
            if (!protectedRole.equals(roles.get(criticalPath.get(i)))) {
                return i;
            }
        }
        return 1;
    }

    private static Map<PlanCell, Integer> degrees(Set<PlanCell> cells, Set<PlanEdge> openEdges) {
        Map<PlanCell, Integer> degrees = new HashMap<>(cells.size());
        for (PlanEdge edge : openEdges) {
            degrees.merge(edge.a(), 1, Integer::sum);
            degrees.merge(edge.b(), 1, Integer::sum);
        }
        return degrees;
    }

    private static List<DoorMask.Direction> shuffledDirections(Random rng) {
        List<DoorMask.Direction> directions = new ArrayList<>(List.of(DoorMask.Direction.values()));
        Collections.shuffle(directions, rng);
        return directions;
    }

    private static Map<PlanCell, List<PlanCell>> buildAdjacency(Set<PlanCell> cells, Set<PlanEdge> edges) {
        Map<PlanCell, List<PlanCell>> adjacency = new HashMap<>();
        for (PlanCell cell : cells) {
            adjacency.put(cell, new ArrayList<>());
        }
        for (PlanEdge edge : edges) {
            if (cells.contains(edge.a()) && cells.contains(edge.b())) {
                adjacency.get(edge.a()).add(edge.b());
                adjacency.get(edge.b()).add(edge.a());
            }
        }
        return adjacency;
    }

    /**
     * M45 (SITUATIONS_SPEC 6.6): BFS depth from the entrance for every cell
     * reachable from it. The entrance is 0; a cell reachable by two routes gets
     * the shorter, which is what a breadth-first walk produces without being
     * asked. A cell absent from the returned map is unreachable, which is the
     * reachability check {@link #validate} performs.
     *
     * <p>This is the graph's only walk from the entrance. It runs once, in
     * {@link #generate}, and the result rides on the {@link DungeonShape}.
     *
     * @return an insertion-ordered map, shallowest cell first
     */
    static Map<PlanCell, Integer> rootDistances(Set<PlanCell> cells, Set<PlanEdge> edges,
                                                PlanCell entrance) {
        Map<PlanCell, Integer> depths = new LinkedHashMap<>();
        if (entrance == null || !cells.contains(entrance)) {
            return depths;
        }
        Map<PlanCell, List<PlanCell>> adjacency = buildAdjacency(cells, edges);
        Deque<PlanCell> queue = new ArrayDeque<>();
        depths.put(entrance, 0);
        queue.add(entrance);
        while (!queue.isEmpty()) {
            PlanCell current = queue.poll();
            int next = depths.get(current) + 1;
            for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                if (!depths.containsKey(neighbor)) {
                    depths.put(neighbor, next);
                    queue.add(neighbor);
                }
            }
        }
        return depths;
    }

    /** Simple mutable integer holder for recursion bookkeeping. */
    private static final class Counter {
        int value = 0;
    }
}
