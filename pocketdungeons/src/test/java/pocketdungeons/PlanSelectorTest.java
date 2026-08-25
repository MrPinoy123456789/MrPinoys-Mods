package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-Java verification for RoomSelector/DungeonPlan/PlanRenderer.
 * Does not launch Minecraft; constructs a fake RoomManifest directly.
 */
public class PlanSelectorTest {

    public static void main(String[] args) {
        testStraightResolves();
        testBranchFailsLegibly();
        testBudgetValidation();
        testConfiguredGridSpan();
        testThemeFilter();
        System.out.println("PlanSelectorTest passed");
    }

    private static void testStraightResolves() {
        DungeonShape shape = straightShape(4);
        RoomManifest manifest = makeManifest();
        Optional<DungeonPlan> opt = RoomSelector.resolve(shape, manifest);
        if (opt.isEmpty()) {
            throw new AssertionError("expected straight 4-room shape to resolve");
        }
        DungeonPlan plan = opt.get();
        if (plan.cells().size() != 4) {
            throw new AssertionError("expected 4 cells, got " + plan.cells().size());
        }

        DungeonPlan.PlacedRoom entrance = plan.rooms().get(new PlanCell(0, 0));
        DungeonPlan.PlacedRoom exit = plan.rooms().get(new PlanCell(3, 0));
        if (entrance == null || !"entrance_hall".equals(entrance.name()) || entrance.rotation() != 0) {
            throw new AssertionError("unexpected entrance placement: " + entrance);
        }
        if (exit == null || !"exit_hall".equals(exit.name()) || exit.rotation() != 0) {
            throw new AssertionError("unexpected exit placement: " + exit);
        }

        List<String> problems = RoomSelector.validate(plan, 12);
        if (!problems.isEmpty()) {
            throw new AssertionError("validation failed: " + problems);
        }

        System.out.println("--- straight success ---");
        System.out.println(PlanRenderer.renderAscii(plan));
        System.out.println("---");
    }

    private static void testBranchFailsLegibly() {
        DungeonShape shape = tShape();
        RoomManifest manifest = makeManifest();
        Optional<DungeonPlan> opt = RoomSelector.resolve(shape, manifest);
        if (opt.isPresent()) {
            throw new AssertionError("expected T-shape to fail resolution");
        }

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest);
        RoomSelector.Failure failure = result.failure();
        if (failure == null) {
            throw new AssertionError("expected a failure record");
        }
        if (!new PlanCell(1, 0).equals(failure.cell())) {
            throw new AssertionError("expected failure at (1,0), got " + failure.cell());
        }
        String letters = DoorMask.toLetters(failure.mask());
        if (!"ESW".equals(letters)) {
            throw new AssertionError("expected mask ESW, got " + letters);
        }

        System.out.println("--- branch failure ---");
        System.out.println(PlanRenderer.renderFailure(shape, failure));
        System.out.println("---");
    }

    private static void testBudgetValidation() {
        DungeonShape huge = straightShape(60);
        RoomManifest manifest = makeManifest();
        Optional<DungeonPlan> opt = RoomSelector.resolve(huge, manifest);
        if (opt.isEmpty()) {
            throw new AssertionError("expected 60-cell straight shape to resolve");
        }
        List<String> problems = RoomSelector.validate(opt.get(), 12);
        if (problems.isEmpty()) {
            throw new AssertionError("expected budget validation to fail for 60 rooms");
        }
        if (!problems.get(0).contains("above maximum")) {
            throw new AssertionError("expected max-room failure, got " + problems);
        }
    }

    private static void testConfiguredGridSpan() {
        DungeonPlan plan = RoomSelector.resolve(straightShape(4), makeManifest()).orElseThrow();
        if (!RoomSelector.validate(plan, 3).stream()
                .anyMatch(problem -> problem.contains("exceeds the maximum of 3"))) {
            throw new AssertionError("expected configured span 3 to reject a four-cell plan");
        }
        if (!RoomSelector.validate(plan, 4).isEmpty()) {
            throw new AssertionError("expected configured span 4 to accept a four-cell plan");
        }
    }

    private static void testThemeFilter() {
        DungeonShape shape = straightShape(3);
        RoomManifest manifest = makeThemedManifest();

        Optional<DungeonPlan> noTheme = RoomSelector.resolve(shape, manifest);
        if (noTheme.isEmpty()) {
            throw new AssertionError("expected unfiltered plan to resolve");
        }

        Optional<DungeonPlan> themed = RoomSelector.resolve(shape, manifest, "deepslate");
        if (themed.isPresent()) {
            throw new AssertionError("expected deepslate theme to filter out the unthemed encounter room");
        }

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, "deepslate");
        if (!"encounter".equals(result.failure().role())) {
            throw new AssertionError("expected theme failure to name encounter role, got " + result.failure());
        }
    }

    private static DungeonShape straightShape(int n) {
        Set<PlanCell> cells = new LinkedHashSet<>();
        List<PlanCell> critical = new ArrayList<>();
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            PlanCell c = new PlanCell(i, 0);
            cells.add(c);
            critical.add(c);
            String role;
            if (i == 0) {
                role = "entrance";
            } else if (i == n - 1) {
                role = "exit";
            } else if (i % 2 == 1) {
                role = "encounter";
            } else {
                role = "loot";
            }
            roles.put(c, role);
        }
        Set<PlanEdge> edges = new LinkedHashSet<>();
        for (int i = 0; i < n - 1; i++) {
            edges.add(new PlanEdge(critical.get(i), critical.get(i + 1)));
        }
        return new DungeonShape(0, cells, edges, critical.get(0), critical.get(n - 1),
                critical, roles);
    }

    private static DungeonShape tShape() {
        Set<PlanCell> cells = new LinkedHashSet<>();
        List<PlanCell> critical = new ArrayList<>();
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            PlanCell c = new PlanCell(i, 0);
            cells.add(c);
            critical.add(c);
            String role = switch (i) {
                case 0 -> "entrance";
                case 3 -> "exit";
                case 1 -> "encounter";
                default -> "loot";
            };
            roles.put(c, role);
        }
        Set<PlanEdge> edges = new LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            edges.add(new PlanEdge(critical.get(i), critical.get(i + 1)));
        }
        PlanCell branch = new PlanCell(1, 1);
        cells.add(branch);
        edges.add(new PlanEdge(critical.get(1), branch));
        roles.put(branch, "loot");
        return new DungeonShape(1, cells, edges, critical.get(0), critical.get(3),
                critical, roles);
    }

    private static RoomManifest makeManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.add(new RoomManifest.Entry("entrance_hall",
                meta("entrance_hall", List.of("entrance")), DoorMask.EAST));
        entries.add(new RoomManifest.Entry("encounter_zombie",
                meta("encounter_zombie", List.of("encounter")), DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("loot_vault",
                meta("loot_vault", List.of("loot")), DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("exit_hall",
                meta("exit_hall", List.of("exit")), DoorMask.WEST));
        return RoomManifest.create(entries, List.of());
    }

    private static RoomManifest makeThemedManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.add(new RoomManifest.Entry("entrance_hall",
                meta("entrance_hall", List.of("entrance"), List.of("deepslate")), DoorMask.EAST));
        entries.add(new RoomManifest.Entry("encounter_zombie",
                meta("encounter_zombie", List.of("encounter"), List.of("prismarine")),
                DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("exit_hall",
                meta("exit_hall", List.of("exit"), List.of("deepslate")), DoorMask.WEST));
        return RoomManifest.create(entries, List.of());
    }

    private static DungeonRoomMeta meta(String template, List<String> roles) {
        return new DungeonRoomMeta(template, 1, 1, roles, 1, 0, -1, null);
    }

    private static DungeonRoomMeta meta(String template, List<String> roles, List<String> theme) {
        return new DungeonRoomMeta(template, 1, 1, roles, 1, 0, -1, null, theme);
    }
}
