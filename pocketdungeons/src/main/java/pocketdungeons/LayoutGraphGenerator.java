package pocketdungeons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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

        Set<PlanCell> cells = new HashSet<>(criticalPath);
        Set<PlanEdge> openEdges = new HashSet<>();
        for (int i = 0; i < criticalPath.size() - 1; i++) {
            openEdges.add(new PlanEdge(criticalPath.get(i), criticalPath.get(i + 1)));
        }

        PlanCell entrance = criticalPath.get(0);
        PlanCell terminal = criticalPath.get(criticalPath.size() - 1);

        // The entrance and terminal cells are held to exactly one door each. That
        // is what lets a single entrance_hall (E) and exit_hall (W) template cover
        // all four single-door masks by rotation, instead of needing one end-cap
        // room per mask family -- and it gives the player a cleaner read: one way
        // in, one way out.
        addBranches(rng, criticalPath, cells, openEdges, branchProbability);
        addLoops(rng, cells, openEdges, loopProbability, entrance, terminal);

        Map<PlanCell, String> roles = assignRoles(rng, criticalPath, cells, openEdges);

        return new DungeonShape(seed, cells, openEdges, entrance, terminal, criticalPath, roles);
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
            Map<PlanCell, List<PlanCell>> adjacency = buildAdjacency(cells, edges);
            Set<PlanCell> reachable = bfs(shape.entrance(), adjacency);
            for (PlanCell cell : cells) {
                if (!reachable.contains(cell)) {
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

        // The entrance and terminal must stay single-door: the room library ships
        // exactly one entrance template and one exit template, and they cover all
        // four single-door masks by rotation and nothing else. A second door on
        // either end cap is unresolvable content, so catch it here rather than as
        // a mystery selection failure in-game.
        Map<PlanCell, Integer> degrees = degrees(cells, edges);
        int entranceDegree = degrees.getOrDefault(shape.entrance(), 0);
        if (entranceDegree != 1) {
            problems.add("entrance cell " + shape.entrance() + " has " + entranceDegree
                    + " doors, expected exactly 1");
        }
        int terminalDegree = degrees.getOrDefault(shape.terminal(), 0);
        if (terminalDegree != 1) {
            problems.add("terminal cell " + shape.terminal() + " has " + terminalDegree
                    + " doors, expected exactly 1");
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

        return problems;
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

    private static void addBranches(Random rng, List<PlanCell> criticalPath,
                                    Set<PlanCell> cells, Set<PlanEdge> openEdges,
                                    double branchProbability) {
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
            tryGrowBranch(rng, root, depth, cells, openEdges);
        }
    }

    private static void tryGrowBranch(Random rng, PlanCell root, int depth,
                                      Set<PlanCell> cells, Set<PlanEdge> openEdges) {
        List<DoorMask.Direction> directions = shuffledDirections(rng);
        PlanCell first = null;
        for (DoorMask.Direction dir : directions) {
            PlanCell candidate = root.neighbor(dir);
            if (!cells.contains(candidate)) {
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
                if (!cells.contains(candidate)) {
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

    private static Set<PlanCell> bfs(PlanCell start, Map<PlanCell, List<PlanCell>> adjacency) {
        Set<PlanCell> visited = new HashSet<>();
        Deque<PlanCell> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            PlanCell current = queue.poll();
            for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    /** Simple mutable integer holder for recursion bookkeeping. */
    private static final class Counter {
        int value = 0;
    }

    /** Plain-Java verification entry point. No Gradle, no Minecraft. */
    public static void main(String[] args) {
        int minPathLength = 8;
        int maxPathLength = 12;

        System.out.println("=== LayoutGraphGenerator verification ===\n");

        int validCount = 0;
        int branchCountTotal = 0;
        int loopCountTotal = 0;

        for (long seed = 0; seed < 20; seed++) {
            DungeonShape shape = generate(seed, minPathLength, maxPathLength);
            if (shape == null) {
                System.out.println("seed=" + seed + " => generate() returned null (backtracking budget exceeded)");
                continue;
            }

            List<String> problems = validate(shape);
            int pathLen = shape.criticalPath().size();
            int cells = shape.cells().size();
            int pathEdges = shape.criticalPath().size() - 1;
            int branches = cells - pathLen;
            int loops = shape.openEdges().size() - pathEdges;

            String status = problems.isEmpty() ? "OK" : "INVALID: " + problems;
            System.out.println("seed=" + seed
                + " path=" + pathLen
                + " cells=" + cells
                + " branches=" + branches
                + " loops=" + loops
                + " => " + status);

            if (problems.isEmpty()) {
                validCount++;
                branchCountTotal += branches;
                loopCountTotal += loops;
            }
        }

        System.out.println("\nSummary: " + validCount + " valid shapes");
        System.out.println("Total branch cells generated: " + branchCountTotal);
        System.out.println("Total loop edges generated: " + loopCountTotal);

        System.out.println("\n=== Deliberate validation failures ===");

        // 1. Critical path step is not adjacent.
        PlanCell a = new PlanCell(0, 0);
        PlanCell b = new PlanCell(2, 0);
        DungeonShape nonAdjacent = new DungeonShape(
            999L,
            Set.of(a, b),
            Set.of(new PlanEdge(a, b)),
            a,
            b,
            List.of(a, b),
            Map.of(a, "entrance", b, "exit")
        );
        List<String> p1 = validate(nonAdjacent);
        System.out.println("non-adjacent critical path => " + p1);

        // 2. Cell is unreachable from entrance.
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell connected = new PlanCell(1, 0);
        PlanCell orphan = new PlanCell(5, 5);
        DungeonShape unreachable = new DungeonShape(
            998L,
            Set.of(entrance, connected, orphan),
            Set.of(new PlanEdge(entrance, connected)),
            entrance,
            connected,
            List.of(entrance, connected),
            Map.of(entrance, "entrance", connected, "exit", orphan, "encounter")
        );
        List<String> p2 = validate(unreachable);
        System.out.println("unreachable cell => " + p2);

        verifyLivePlayProfile();

        System.out.println("\n=== Verification complete ===");
    }

    /**
     * Bulk sweep at the config-default profile (5-8 path, 0.35 branch, 0.15 loop)
     * rather than the 8-12 defaults the survey above uses, asserting the three
     * invariants the room library depends on:
     *
     * <ul>
     *   <li>every shape validates -- which now includes the single-door end caps,
     *       the property that lets one entrance and one exit template cover all
     *       four single-door masks by rotation;</li>
     *   <li>every critical path carries at least one encounter and one loot,
     *       the guarantee pass in {@link #guaranteeOnPath}. The failure this
     *       catches is subtle: forcing a missing role can overwrite the sole cell
     *       holding the other one, trading one missing role for the other;</li>
     *   <li>no shape exceeds the 12-cell grid span budget, which is what bounds
     *       force-load tickets and teardown volume.</li>
     * </ul>
     */
    private static void verifyLivePlayProfile() {
        System.out.println("\n=== Live-play profile sweep (5-8 path, 5000 seeds) ===");

        int valid = 0;
        int missingEncounter = 0;
        int missingLoot = 0;
        int maxSpan = 0;
        int cellTotal = 0;

        for (long seed = 0; seed < 5000; seed++) {
            DungeonShape shape = generate(seed, 5, 8, 0.35, 0.15);
            if (shape == null) {
                throw new AssertionError("seed " + seed + " failed to generate a shape");
            }
            List<String> problems = validate(shape);
            if (!problems.isEmpty()) {
                throw new AssertionError("seed " + seed + " produced an invalid shape: " + problems);
            }
            valid++;
            cellTotal += shape.cells().size();

            boolean hasEncounter = false;
            boolean hasLoot = false;
            for (PlanCell cell : shape.criticalPath()) {
                String role = shape.roles().get(cell);
                hasEncounter |= ROLE_ENCOUNTER.equals(role);
                hasLoot |= ROLE_LOOT.equals(role);
            }
            if (!hasEncounter) missingEncounter++;
            if (!hasLoot) missingLoot++;

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (PlanCell cell : shape.cells()) {
                minX = Math.min(minX, cell.x());
                maxX = Math.max(maxX, cell.x());
                minZ = Math.min(minZ, cell.z());
                maxZ = Math.max(maxZ, cell.z());
            }
            maxSpan = Math.max(maxSpan, Math.max(maxX - minX + 1, maxZ - minZ + 1));
        }

        System.out.printf("%d shapes, avg %.2f cells, max grid span %d%n",
                valid, cellTotal / (double) valid, maxSpan);
        System.out.println("paths missing an encounter: " + missingEncounter
                + ", missing a loot: " + missingLoot);

        if (missingEncounter > 0 || missingLoot > 0) {
            throw new AssertionError("the critical-path role guarantee does not hold");
        }
        if (maxSpan > 12) {
            throw new AssertionError("grid span " + maxSpan + " exceeds the maxGridSpan budget of 12");
        }
    }
}
