package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-Java verification harness for {@link LayoutGraphGenerator}. No
 * Gradle, no Minecraft: a 20-seed printed survey against the 8-12 path
 * profile, two deliberate validation failures, and a 5000-seed sweep at the
 * config-default profile asserting the invariants the room library depends
 * on.
 *
 * <p>M40: this used to be {@code LayoutGraphGenerator.main}, shipped inside
 * the mod jar as production source. Moved here unchanged; the
 * {@code layoutGraphTest} Gradle task now points at the test source set
 * instead of main.
 */
final class LayoutGraphGeneratorHarness {

    private LayoutGraphGeneratorHarness() {}

    public static void main(String[] args) {
        int minPathLength = 8;
        int maxPathLength = 12;

        System.out.println("=== LayoutGraphGenerator verification ===\n");

        int validCount = 0;
        int branchCountTotal = 0;
        int loopCountTotal = 0;

        for (long seed = 0; seed < 20; seed++) {
            DungeonShape shape = LayoutGraphGenerator.generate(seed, minPathLength, maxPathLength);
            if (shape == null) {
                System.out.println("seed=" + seed + " => generate() returned null (backtracking budget exceeded)");
                continue;
            }

            List<String> problems = LayoutGraphGenerator.validate(shape);
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
        List<String> p1 = LayoutGraphGenerator.validate(nonAdjacent);
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
        List<String> p2 = LayoutGraphGenerator.validate(unreachable);
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
     *       the guarantee pass in {@code LayoutGraphGenerator.guaranteeOnPath}.
     *       The failure this catches is subtle: forcing a missing role can
     *       overwrite the sole cell holding the other one, trading one missing
     *       role for the other;</li>
     *   <li>no shape exceeds the 12-cell grid span budget, which is what bounds
     *       force-load tickets and teardown volume.</li>
     * </ul>
     */
    private static void verifyLivePlayProfile() {
        System.out.println("\n=== Live-play profile sweep (5-8 path, 5000 seeds) ===");

        int valid = 0;
        int unresolved = 0;
        int missingEncounter = 0;
        int missingLoot = 0;
        int maxSpan = 0;
        int cellTotal = 0;

        // Each logical seed gets its own non-overlapping window of attemptBudget
        // seeds to retry through, mirroring LayoutPlanner.plan's seed+attempt
        // scheme -- a shape with cells behind the entrance is expected, unlucky
        // output of the backtracker's free branch/loop placement, not a generator
        // bug, so it burns a retry here exactly as it would in live play.
        int attemptBudget = LayoutPlanner.DEFAULT_ATTEMPT_BUDGET;
        for (long logicalSeed = 0; logicalSeed < 5000; logicalSeed++) {
            long windowBase = logicalSeed * attemptBudget;
            DungeonShape shape = null;

            for (int attempt = 0; attempt < attemptBudget; attempt++) {
                long attemptSeed = windowBase + attempt;
                DungeonShape candidate = LayoutGraphGenerator.generate(attemptSeed, 5, 8, 0.35, 0.15);
                if (candidate == null) {
                    continue;
                }
                List<String> problems = LayoutGraphGenerator.validate(candidate);
                if (problems.isEmpty()) {
                    shape = candidate;
                    break;
                }
                boolean onlyBackward = problems.stream()
                        .allMatch(p -> p.contains(LayoutGraphGenerator.NO_BACKWARDS_MARKER));
                if (!onlyBackward) {
                    throw new AssertionError("seed " + attemptSeed + " produced an invalid shape: " + problems);
                }
            }

            if (shape == null) {
                unresolved++;
                continue;
            }
            valid++;
            cellTotal += shape.cells().size();

            boolean hasEncounter = false;
            boolean hasLoot = false;
            for (PlanCell cell : shape.criticalPath()) {
                String role = shape.roles().get(cell);
                hasEncounter |= LayoutGraphGenerator.ROLE_ENCOUNTER.equals(role);
                hasLoot |= LayoutGraphGenerator.ROLE_LOOT.equals(role);
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

        double resolutionRate = valid / (double) (valid + unresolved);
        System.out.printf("resolved %d/%d logical seeds (%.1f%%) within the %d-attempt budget%n",
                valid, valid + unresolved, resolutionRate * 100.0, attemptBudget);
        if (resolutionRate < 0.95) {
            throw new AssertionError("plan resolution rate " + resolutionRate
                    + " fell below the 95% budget after adding the no-backwards check");
        }

        if (missingEncounter > 0 || missingLoot > 0) {
            throw new AssertionError("the critical-path role guarantee does not hold");
        }
        if (maxSpan > 12) {
            throw new AssertionError("grid span " + maxSpan + " exceeds the maxGridSpan budget of 12");
        }
    }
}
