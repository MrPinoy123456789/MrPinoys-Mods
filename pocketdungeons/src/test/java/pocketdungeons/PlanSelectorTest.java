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
        // M70: publish synthetic role manifest so the selector's unknown-role
        // check passes for the built-in ids.
        publishSyntheticRoleManifest();
        testStraightResolves();
        testBranchFailsLegibly();
        testBudgetValidation();
        testConfiguredGridSpan();
        testThemeFilter();
        testRubbleNeedsAnExplosiveBeforeIt();
        testTierGate();
        testResourceFloorGuaranteesANodeRoom();
        System.out.println("PlanSelectorTest passed");
    }

    /**
     * Rubble doorways ({@link RoomSelector#pickRubbleEdges}): none without an
     * explosive, never on the entrance's door, at most one per plan, and with
     * a creeper room at depth 2 only on a door whose deeper side is past it.
     */
    private static void testRubbleNeedsAnExplosiveBeforeIt() {
        PlanCell c0 = new PlanCell(0, 0);
        PlanCell c1 = new PlanCell(1, 0);
        PlanCell c2 = new PlanCell(2, 0);
        PlanCell c3 = new PlanCell(3, 0);
        PlanEdge e01 = new PlanEdge(c0, c1);
        PlanEdge e12 = new PlanEdge(c1, c2);
        PlanEdge e23 = new PlanEdge(c2, c3);
        Set<PlanEdge> doors = Set.of(e01, e12, e23);
        Map<PlanCell, Integer> depths = Map.of(c0, 0, c1, 1, c2, 2, c3, 3);

        int picked = 0;
        for (long seed = 0; seed < 300; seed++) {
            if (!RoomSelector.pickRubbleEdges(seed, doors, depths, c0, Map.of(), Set.of()).isEmpty()) {
                throw new AssertionError("no explosive anywhere, yet seed " + seed + " placed rubble");
            }
            Set<PlanEdge> bag = RoomSelector.pickRubbleEdges(seed, doors, depths, c0, Map.of(),
                    Set.of(SituationTags.EXPLOSIVE));
            if (bag.size() > 1 || bag.contains(e01)) {
                throw new AssertionError("seed " + seed + " placed " + bag + "; at most one, never the entrance's");
            }
            picked += bag.size();
            Set<PlanEdge> creeper = RoomSelector.pickRubbleEdges(seed, doors, depths, c0,
                    Map.of(c2, List.of(SituationTags.EXPLOSIVE)), Set.of());
            if (!creeper.isEmpty() && !creeper.equals(Set.of(e23))) {
                throw new AssertionError("seed " + seed + " put rubble at " + creeper
                        + " before the creeper room it needs");
            }
        }
        if (picked < 50 || picked > 160) {
            throw new AssertionError("with the Sapper's TNT about a third of plans should get rubble, got "
                    + picked + " of 300");
        }

        // Sealed two-story cells: all of them with the Sapper's TNT, none without
        // an explosive, and with a creeper room at depth 2 only those deeper.
        Set<PlanCell> twoStory = Set.of(c1, c3);
        if (!RoomSelector.pickSealedCells(twoStory, depths, Map.of(), Set.of()).isEmpty()) {
            throw new AssertionError("no explosive, yet a floor was sealed");
        }
        if (!RoomSelector.pickSealedCells(twoStory, depths, Map.of(), Set.of(SituationTags.EXPLOSIVE))
                .equals(twoStory)) {
            throw new AssertionError("with the Sapper's TNT every two-story cell is sealed");
        }
        if (!RoomSelector.pickSealedCells(twoStory, depths, Map.of(c2, List.of(SituationTags.EXPLOSIVE)), Set.of())
                .equals(Set.of(c3))) {
            throw new AssertionError("a creeper room at depth 2 can only open floors deeper than it");
        }
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
        if (!RoleIds.ENCOUNTER.equals(result.failure().role())) {
            throw new AssertionError("expected theme failure to name encounter role, got " + result.failure());
        }
    }

    /**
     * PD-122: room tier is capped at the floor's loot tier. With only a tier 3
     * room for a slot the plan still resolves (fallback), but when a tier 1 room
     * is available it is chosen for a tier 1 offer.
     */
    private static void testTierGate() {
        DungeonShape shape = straightShape(4);
        RunRecipePlan tier1Plan = new RunRecipePlan(false, false, false, false, false, 0,
                List.of(), List.of(), 0L, 1, Set.of(), Set.of(), 0, Set.of());

        // With only a tier 3 room for the loot slot, the tier 1 cap must fall back.
        RoomManifest manifest = makeTieredManifest(3);
        DungeonPlan plan = RoomSelector.resolveDetailed(shape, manifest, null, Set.of(), tier1Plan).plan();
        if (plan == null) {
            throw new AssertionError("expected straight shape to resolve with tier fallback");
        }
        DungeonPlan.PlacedRoom loot = plan.rooms().get(new PlanCell(2, 0));
        if (loot == null || !"loot_tier3".equals(loot.name())) {
            throw new AssertionError("tier 1 cap should fall back to tier 3 when no tier 1 room exists, got " + loot);
        }

        // When a tier 1 room is available it is preferred over the tier 3 room.
        manifest = makeTieredManifest(1, 3);
        plan = RoomSelector.resolveDetailed(shape, manifest, null, Set.of(), tier1Plan).plan();
        if (plan == null) {
            throw new AssertionError("expected straight shape to resolve with a tier 1 room available");
        }
        loot = plan.rooms().get(new PlanCell(2, 0));
        if (loot == null || !"loot_tier1".equals(loot.name())) {
            throw new AssertionError("tier 1 cap should prefer the tier 1 room, got " + loot);
        }
    }

    /**
     * PD-149 (playtest 2026-10-05-1): a resource dungeon's floor must place at
     * least one room that stamps nodes. With a manifest whose only node room is
     * outnumbered by generic halls, every seed still lands one.
     */
    private static void testResourceFloorGuaranteesANodeRoom() {
        DungeonShape shape = straightShape(5);
        RoomManifest manifest = makeResourceManifest();
        RoomEligibility.Floor floor = new RoomEligibility.Floor("test_mine", "test_mine", null, 1,
                false, "test_mine", "", false, false, false, true);
        for (long seed = 0; seed < 40; seed++) {
            DungeonShape seeded = new DungeonShape(seed, shape.cells(), shape.openEdges(),
                    shape.entrance(), shape.terminal(), shape.criticalPath(), shape.roles());
            DungeonPlan plan = RoomSelector.resolveDetailed(seeded, manifest, null, Set.of(), null, floor).plan();
            if (plan == null) {
                throw new AssertionError("resource floor " + seed + " did not resolve");
            }
            boolean oreRoom = false;
            for (DungeonPlan.PlacedRoom room : plan.rooms().values()) {
                if ("ore_room".equals(room.name())) {
                    oreRoom = true;
                }
            }
            if (!oreRoom) {
                throw new AssertionError("resource floor " + seed + " placed no node room");
            }
        }
        // PD-149 (reopened 2026-10-06-1): a floor of corners has no cell the
        // straight ore room fits. The plan still resolves, but says it is short,
        // so the planner tries another layout; a straight floor is not short.
        RoomManifest withCorners = makeResourceManifest(true);
        RoomSelector.Result bent = RoomSelector.resolveDetailed(stairShape(), withCorners, null, Set.of(), null,
                floor);
        if (bent.plan() == null) {
            throw new AssertionError("a floor of corners did not resolve");
        }
        if (!bent.resourceShort()) {
            throw new AssertionError("a floor no ore room fits must say it is short");
        }
        if (RoomSelector.resolveDetailed(shape, withCorners, null, Set.of(), null, floor).resourceShort()) {
            throw new AssertionError("a straight floor that holds the ore room is not short");
        }

        // A non-resource floor never forces the room in: the guarantee is scoped.
        RoomEligibility.Floor story = new RoomEligibility.Floor("test_mine", "test_mine", null, 1,
                false, "test_mine", "", false, false, false, false);
        DungeonPlan plan = RoomSelector.resolveDetailed(shape, manifest, null, Set.of(), null, story).plan();
        if (plan == null) {
            throw new AssertionError("story floor did not resolve");
        }
    }

    /** A manifest of generic halls plus one node-bearing room bound to the test dungeon. */
    private static RoomManifest makeResourceManifest() {
        return makeResourceManifest(false);
    }

    /** As above; {@code corners} adds a generic corner hall, so a bent floor resolves. */
    private static RoomManifest makeResourceManifest(boolean corners) {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        if (corners) {
            entries.add(new RoomManifest.Entry("hall_corner",
                    meta("hall_corner", List.of(RoleIds.ENCOUNTER, RoleIds.LOOT)),
                    DoorMask.NORTH | DoorMask.EAST));
        }
        entries.add(new RoomManifest.Entry("entrance_hall",
                meta("entrance_hall", List.of(RoleIds.ENTRANCE)), DoorMask.EAST));
        for (int i = 0; i < 4; i++) {
            entries.add(new RoomManifest.Entry("hall_" + i,
                    meta("hall_" + i, List.of(RoleIds.ENCOUNTER, RoleIds.LOOT)),
                    DoorMask.EAST | DoorMask.WEST));
        }
        entries.add(new RoomManifest.Entry("ore_room",
                new DungeonRoomMeta("ore_room", 1, 1, List.of(RoleIds.ENCOUNTER, RoleIds.LOOT),
                        1, 0, -1, null, List.of(), null, 1, List.of(), List.of(), null,
                        DungeonRoomMeta.ACCESS_OPEN, DungeonRoomMeta.WINDOW_BARS, 1,
                        List.of(new DungeonRoomMeta.NodeSpec("minecraft:coal_ore",
                                new int[]{3, 1, 5}, new int[]{5, 3, 5}, 3)),
                        "lit", "wide", null, false, List.of("test_mine"), List.of(), List.of(),
                        List.of()),
                DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("exit_hall",
                meta("exit_hall", List.of(RoleIds.EXIT)), DoorMask.WEST));
        return RoomManifest.create(entries, List.of());
    }

    private static RoomManifest makeTieredManifest(int... tiers) {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.add(new RoomManifest.Entry("entrance_hall",
                new DungeonRoomMeta("entrance_hall", 1, 1, List.of(RoleIds.ENTRANCE),
                        1, 0, -1, null, List.of(), null),
                DoorMask.EAST));
        entries.add(new RoomManifest.Entry("encounter_tier1",
                new DungeonRoomMeta("encounter_tier1", 1, 1, List.of(RoleIds.ENCOUNTER),
                        1, 0, -1, null, List.of(), null),
                DoorMask.EAST | DoorMask.WEST));
        for (int tier : tiers) {
            String name = tier == 1 ? "loot_tier1" : "loot_tier" + tier;
            entries.add(new RoomManifest.Entry(name,
                    new DungeonRoomMeta(name, 1, 1, List.of(RoleIds.LOOT),
                            1, 0, -1, null, List.of(), null, tier, List.of(), List.of(), null,
                            DungeonRoomMeta.ACCESS_OPEN, DungeonRoomMeta.WINDOW_BARS, 1),
                    DoorMask.EAST | DoorMask.WEST));
        }
        entries.add(new RoomManifest.Entry("exit_hall",
                new DungeonRoomMeta("exit_hall", 1, 1, List.of(RoleIds.EXIT),
                        1, 0, -1, null, List.of(), null),
                DoorMask.WEST));
        return RoomManifest.create(entries, List.of());
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
                role = RoleIds.ENTRANCE;
            } else if (i == n - 1) {
                role = RoleIds.EXIT;
            } else if (i % 2 == 1) {
                role = RoleIds.ENCOUNTER;
            } else {
                role = RoleIds.LOOT;
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

    /** Entrance, two corners, exit: a staircase with no straight cell. */
    private static DungeonShape stairShape() {
        PlanCell c0 = new PlanCell(0, 0);
        PlanCell c1 = new PlanCell(1, 0);
        PlanCell c2 = new PlanCell(1, 1);
        PlanCell c3 = new PlanCell(2, 1);
        List<PlanCell> critical = List.of(c0, c1, c2, c3);
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(c0, RoleIds.ENTRANCE);
        roles.put(c1, RoleIds.ENCOUNTER);
        roles.put(c2, RoleIds.LOOT);
        roles.put(c3, RoleIds.EXIT);
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(new PlanEdge(c0, c1), new PlanEdge(c1, c2),
                new PlanEdge(c2, c3)));
        return new DungeonShape(0, new LinkedHashSet<>(critical), edges, c0, c3, critical, roles);
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
                case 0 -> RoleIds.ENTRANCE;
                case 3 -> RoleIds.EXIT;
                case 1 -> RoleIds.ENCOUNTER;
                default -> RoleIds.LOOT;
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
        roles.put(branch, RoleIds.LOOT);
        return new DungeonShape(1, cells, edges, critical.get(0), critical.get(3),
                critical, roles);
    }

    private static RoomManifest makeManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.add(new RoomManifest.Entry("entrance_hall",
                meta("entrance_hall", List.of(RoleIds.ENTRANCE)), DoorMask.EAST));
        entries.add(new RoomManifest.Entry("encounter_zombie",
                meta("encounter_zombie", List.of(RoleIds.ENCOUNTER)), DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("loot_vault",
                meta("loot_vault", List.of(RoleIds.LOOT)), DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("exit_hall",
                meta("exit_hall", List.of(RoleIds.EXIT)), DoorMask.WEST));
        return RoomManifest.create(entries, List.of());
    }

    private static RoomManifest makeThemedManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.add(new RoomManifest.Entry("entrance_hall",
                meta("entrance_hall", List.of(RoleIds.ENTRANCE), List.of("deepslate")), DoorMask.EAST));
        entries.add(new RoomManifest.Entry("encounter_zombie",
                meta("encounter_zombie", List.of(RoleIds.ENCOUNTER), List.of("prismarine")),
                DoorMask.EAST | DoorMask.WEST));
        entries.add(new RoomManifest.Entry("exit_hall",
                meta("exit_hall", List.of(RoleIds.EXIT), List.of("deepslate")), DoorMask.WEST));
        return RoomManifest.create(entries, List.of());
    }

    private static DungeonRoomMeta meta(String template, List<String> roles) {
        return new DungeonRoomMeta(template, 1, 1, roles, 1, 0, -1, null);
    }

    private static DungeonRoomMeta meta(String template, List<String> roles, List<String> theme) {
        return new DungeonRoomMeta(template, 1, 1, roles, 1, 0, -1, null, theme);
    }

    /**
     * M70: publishes a synthetic role manifest with the three built-in
     * population roles, so the selector's unknown-role check passes for the
     * built-in ids the test's shapes assign.
     */
    private static void publishSyntheticRoleManifest() {
        Map<String, RoleManifest.Entry> roles = new LinkedHashMap<>();
        Object[][] roleData = {
                {RoleIds.ENCOUNTER, 45, RoomRoleDefinition.Operation.TRIAL_ENCOUNTER},
                {RoleIds.LOOT, 25, RoomRoleDefinition.Operation.TOOL_CACHE},
                {RoleIds.CORRIDOR, 30, RoomRoleDefinition.Operation.NONE},
        };
        for (Object[] r : roleData) {
            String id = (String) r[0];
            int weight = (int) r[1];
            RoomRoleDefinition.Operation op = (RoomRoleDefinition.Operation) r[2];
            RoomRoleDefinition def = new RoomRoleDefinition(id, "interior", weight, 0, -1, List.of(), op);
            roles.put(id, new RoleManifest.Entry(id, def));
        }
        RoleManifest.publish(RoleManifest.create(roles, List.of()));
    }
}
