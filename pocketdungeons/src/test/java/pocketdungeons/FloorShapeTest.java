package pocketdungeons;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M54 (spec 6.5): floor shape for a hidden staging room.
 *
 * <p>Four assertions over a spread of seeds and a set of hand-built fixtures:
 *
 * <ul>
 *   <li>The terminal index falls in the 60-90% band of the generated path.</li>
 *   <li>The line-of-sight check rejects the shapes it should (terminal visible
 *       from the entrance through a straight run of open doorways) and accepts
 *       the shapes it should (run broken, or terminal off the entrance's row
 *       and column).</li>
 *   <li>Every cell is still reachable from the entrance.</li>
 *   <li>The M29 no-backwards-propagation rule still holds under the raised
 *       branch and loop probabilities.</li>
 * </ul>
 *
 * <p>Pure JDK, no Minecraft classpath. The 8-12 path profile with 0.55 branch
 * and 0.30 loop matches the config defaults M54 ships.
 */
public class FloorShapeTest {

    private static final int MIN_PATH = 8;
    private static final int MAX_PATH = 12;
    private static final double BRANCH_PROB = 0.55;
    private static final double LOOP_PROB = 0.30;
    private static final int ATTEMPT_BUDGET = 16;
    private static final int SWEEP_SEEDS = 300;

    public static void main(String[] args) {
        testTerminalIndexBand();
        testLineOfSightRejection();
        testReachabilityAndNoBackwards();
        System.out.println("FloorShapeTest passed");
    }

    // ---- terminal index in the 60-90% band ----

    /**
     * Over a spread of seeds, the terminal index (the last index of the stored
     * critical path) must fall between 60% and 90% of the generated path
     * length. Paths too short for a decoy (fewer than 5 cells) are skipped.
     */
    private static void testTerminalIndexBand() {
        int checked = 0;
        int tooShort = 0;

        for (long seed = 0; seed < SWEEP_SEEDS; seed++) {
            DungeonShape shape = generateWithRetries(seed);
            if (shape == null) {
                continue;
            }

            int fullPath = shape.generatedPathLength();
            if (fullPath < 5) {
                tooShort++;
                continue;
            }

            int terminalIndex = shape.criticalPath().size() - 1;
            int last = fullPath - 1;
            int bandMin = Math.max(1, (int) Math.ceil(0.6 * last));
            int bandMax = Math.min(last - 1, (int) Math.floor(0.9 * last));

            if (terminalIndex < bandMin || terminalIndex > bandMax) {
                throw new AssertionError("seed " + seed + ": terminal index " + terminalIndex
                        + " is outside the 60-90% band [" + bandMin + ", " + bandMax
                        + "] of generated path length " + fullPath);
            }
            checked++;
        }

        if (checked < 100) {
            throw new AssertionError("expected at least 100 shapes in the band check, got "
                    + checked + " (too short: " + tooShort + ")");
        }
        System.out.println("Terminal band: " + checked + " shapes checked, all in 60-90%");
    }

    // ---- line-of-sight check ----

    /**
     * Hand-built fixtures: a straight corridor from entrance to terminal is
     * rejected; a bent path where the terminal shares a row but the run is
     * broken is accepted; a terminal off both the entrance's row and column is
     * accepted.
     */
    private static void testLineOfSightRejection() {
        // 1. Straight horizontal corridor: entrance (0,0) to terminal (3,0).
        // All cells and edges present. Line of sight exists.
        PlanCell e0 = new PlanCell(0, 0);
        PlanCell e1 = new PlanCell(1, 0);
        PlanCell e2 = new PlanCell(2, 0);
        PlanCell e3 = new PlanCell(3, 0);
        Set<PlanCell> straightCells = new LinkedHashSet<>(List.of(e0, e1, e2, e3));
        Set<PlanEdge> straightEdges = new LinkedHashSet<>(List.of(
                new PlanEdge(e0, e1), new PlanEdge(e1, e2), new PlanEdge(e2, e3)));
        Map<PlanCell, String> straightRoles = new LinkedHashMap<>();
        straightRoles.put(e0, "entrance");
        straightRoles.put(e1, "encounter");
        straightRoles.put(e2, "loot");
        straightRoles.put(e3, "exit");
        DungeonShape straight = new DungeonShape(1, straightCells, straightEdges,
                e0, e3, List.of(e0, e1, e2, e3), straightRoles);
        assertHasProblem(straight, LayoutGraphGenerator.LINE_OF_SIGHT_MARKER,
                "a straight corridor from entrance to terminal must be rejected");

        // 2. Straight vertical corridor: entrance (0,0) to terminal (0,3).
        PlanCell v0 = new PlanCell(0, 0);
        PlanCell v1 = new PlanCell(0, 1);
        PlanCell v2 = new PlanCell(0, 2);
        PlanCell v3 = new PlanCell(0, 3);
        Set<PlanCell> vertCells = new LinkedHashSet<>(List.of(v0, v1, v2, v3));
        Set<PlanEdge> vertEdges = new LinkedHashSet<>(List.of(
                new PlanEdge(v0, v1), new PlanEdge(v1, v2), new PlanEdge(v2, v3)));
        Map<PlanCell, String> vertRoles = new LinkedHashMap<>();
        vertRoles.put(v0, "entrance");
        vertRoles.put(v1, "encounter");
        vertRoles.put(v2, "loot");
        vertRoles.put(v3, "exit");
        DungeonShape vertical = new DungeonShape(2, vertCells, vertEdges,
                v0, v3, List.of(v0, v1, v2, v3), vertRoles);
        assertHasProblem(vertical, LayoutGraphGenerator.LINE_OF_SIGHT_MARKER,
                "a vertical corridor from entrance to terminal must be rejected");

        // 3. Terminal shares a row with entrance but the run is broken: the
        // path bends away and comes back, so no unbroken run of doorways.
        PlanCell b0 = new PlanCell(0, 0);
        PlanCell b1 = new PlanCell(1, 0);
        PlanCell b2 = new PlanCell(1, 1);
        PlanCell b3 = new PlanCell(2, 1);
        PlanCell b4 = new PlanCell(2, 0);
        Set<PlanCell> bentCells = new LinkedHashSet<>(List.of(b0, b1, b2, b3, b4));
        Set<PlanEdge> bentEdges = new LinkedHashSet<>(List.of(
                new PlanEdge(b0, b1), new PlanEdge(b1, b2),
                new PlanEdge(b2, b3), new PlanEdge(b3, b4)));
        Map<PlanCell, String> bentRoles = new LinkedHashMap<>();
        bentRoles.put(b0, "entrance");
        bentRoles.put(b1, "encounter");
        bentRoles.put(b2, "corridor");
        bentRoles.put(b3, "loot");
        bentRoles.put(b4, "exit");
        DungeonShape bent = new DungeonShape(3, bentCells, bentEdges,
                b0, b4, List.of(b0, b1, b2, b3, b4), bentRoles);
        assertNoProblem(bent, LayoutGraphGenerator.LINE_OF_SIGHT_MARKER,
                "a bent path with a broken run must not trigger line of sight");

        // 4. Terminal off both the entrance's row and column.
        PlanCell d0 = new PlanCell(0, 0);
        PlanCell d1 = new PlanCell(1, 0);
        PlanCell d2 = new PlanCell(1, 1);
        Set<PlanCell> diagCells = new LinkedHashSet<>(List.of(d0, d1, d2));
        Set<PlanEdge> diagEdges = new LinkedHashSet<>(List.of(
                new PlanEdge(d0, d1), new PlanEdge(d1, d2)));
        Map<PlanCell, String> diagRoles = new LinkedHashMap<>();
        diagRoles.put(d0, "entrance");
        diagRoles.put(d1, "encounter");
        diagRoles.put(d2, "exit");
        DungeonShape diagonal = new DungeonShape(4, diagCells, diagEdges,
                d0, d2, List.of(d0, d1, d2), diagRoles);
        assertNoProblem(diagonal, LayoutGraphGenerator.LINE_OF_SIGHT_MARKER,
                "a terminal off both axes must not trigger line of sight");

        System.out.println("Line of sight: 2 rejected, 2 accepted");
    }

    // ---- reachability and no-backwards-propagation ----

    /**
     * Over a spread of seeds, every generated shape that passes the retry
     * budget must validate cleanly: every cell reachable, no cell behind the
     * entrance. The raised branch and loop probabilities (0.55 / 0.30) make
     * the no-backwards check the invariant most likely to break.
     */
    private static void testReachabilityAndNoBackwards() {
        int checked = 0;

        for (long seed = 0; seed < SWEEP_SEEDS; seed++) {
            DungeonShape shape = generateWithRetries(seed);
            if (shape == null) {
                continue;
            }

            List<String> problems = LayoutGraphGenerator.validate(shape);
            for (String problem : problems) {
                if (problem.contains(LayoutGraphGenerator.NO_BACKWARDS_MARKER)) {
                    throw new AssertionError("seed " + seed + ": a shape that passed the retry"
                            + " budget still has a backwards cell: " + problem);
                }
                if (problem.contains("unreachable")) {
                    throw new AssertionError("seed " + seed + ": " + problem);
                }
            }
            checked++;
        }

        if (checked < 100) {
            throw new AssertionError("expected at least 100 valid shapes, got " + checked);
        }
        System.out.println("Reachability and no-backwards: " + checked + " shapes, all clean");
    }

    // ---- helpers ----

    /**
     * Mirrors LayoutPlanner's retry scheme: each logical seed gets a window of
     * attemptBudget seeds. generate() returning null (line of sight or
     * backtracking budget) burns a retry, not a failure.
     */
    private static DungeonShape generateWithRetries(long logicalSeed) {
        long windowBase = logicalSeed * ATTEMPT_BUDGET;
        for (int attempt = 0; attempt < ATTEMPT_BUDGET; attempt++) {
            DungeonShape shape = LayoutGraphGenerator.generate(
                    windowBase + attempt, MIN_PATH, MAX_PATH, BRANCH_PROB, LOOP_PROB);
            if (shape == null) {
                continue;
            }
            List<String> problems = LayoutGraphGenerator.validate(shape);
            if (problems.isEmpty()) {
                return shape;
            }
            boolean onlyRetryable = problems.stream().allMatch(
                    p -> p.contains(LayoutGraphGenerator.NO_BACKWARDS_MARKER));
            if (!onlyRetryable) {
                throw new AssertionError("seed " + (windowBase + attempt)
                        + " produced an unexpected validation failure: " + problems);
            }
        }
        return null;
    }

    private static void assertHasProblem(DungeonShape shape, String marker, String message) {
        List<String> problems = LayoutGraphGenerator.validate(shape);
        boolean found = problems.stream().anyMatch(p -> p.contains(marker));
        if (!found) {
            throw new AssertionError(message + "; problems were: " + problems);
        }
    }

    private static void assertNoProblem(DungeonShape shape, String marker, String message) {
        List<String> problems = LayoutGraphGenerator.validate(shape);
        boolean found = problems.stream().anyMatch(p -> p.contains(marker));
        if (found) {
            throw new AssertionError(message + "; problems were: " + problems);
        }
    }
}
