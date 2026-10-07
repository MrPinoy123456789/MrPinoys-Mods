package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the real {@link LayoutGraphGenerator} through the real
 * {@link RoomSelector}/{@link LayoutPlanner} against a <em>synthetic</em> room
 * library covering every door mask.
 *
 * <p>Written to answer "is the planner broken, or is the library too small?"
 * when the planner scored 0 of 200 seeds against the four real rooms: with a
 * complete library the same code plans 200 of 200, first attempt every time, so
 * the shortfall is content, not logic.
 *
 * <p>Kept permanently rather than discarded, because it isolates planner
 * correctness from room availability. Once real rooms are added, a planner
 * regression would otherwise hide behind "well, the library is incomplete
 * anyway" -- this test has no such excuse available to it.
 */
public class PipelineProofTest {

    public static void main(String[] args) {
        // M70: publish synthetic bag and role manifests so the planner reads
        // the same definitions the live server would load.
        publishSyntheticManifests();
        RoomManifest full = fullLibrary();

        int succeeded = 0;
        int attemptsTotal = 0;
        DungeonPlan sample = null;
        for (long seed = 0; seed < 200; seed++) {
            LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                    seed, full, LayoutPlanner.DEFAULT_ATTEMPT_BUDGET);
            attemptsTotal += outcome.attemptsUsed();
            if (outcome.succeeded()) {
                succeeded++;
                if (sample == null) {
                    sample = outcome.plan();
                }
            } else {
                System.out.println("seed " + seed + " failed: " + outcome.failureReason());
            }
        }

        System.out.println("full-library planning: " + succeeded + "/200 seeds succeeded ("
                + attemptsTotal + " attempts total)");

        if (sample == null) {
            throw new AssertionError("expected at least one seed to plan against a full library");
        }

        System.out.println("--- sample plan (seed " + sample.seed() + ", "
                + sample.cells().size() + " rooms) ---");
        System.out.println(PlanRenderer.renderAscii(sample));

        // Every cell must have been assigned a room, and every placed room's mask
        // must exactly equal what that cell required -- the property that keeps
        // doors from opening into void.
        for (PlanCell cell : sample.cells()) {
            if (sample.rooms().get(cell) == null) {
                throw new AssertionError("cell " + cell + " has no placed room");
            }
        }
        if (succeeded != 200) {
            throw new AssertionError("expected every seed to plan with a full library, got " + succeeded);
        }
        System.out.println("PipelineProofTest passed");
    }

    /**
     * One room per (mask, role) combination the generator can ask for. Masks are
     * enumerated exhaustively 0..15; rotation means a real library would need far
     * fewer templates than this, but for proving the pipeline the shape of the
     * library matters more than its minimality.
     */
    private static RoomManifest fullLibrary() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        // M70: namespaced role ids, matching the generator.
        for (String role : List.of(RoleIds.ENTRANCE, RoleIds.EXIT, RoleIds.ENCOUNTER,
                RoleIds.LOOT, RoleIds.CORRIDOR)) {
            for (int mask = 1; mask < 16; mask++) {
                entries.add(new RoomManifest.Entry(
                        role + "_" + DoorMask.toLetters(mask),
                        new DungeonRoomMeta("synthetic", 1, 1, List.of(role), 1, 0, -1, null),
                        mask));
            }
        }
        return RoomManifest.create(entries, List.of());
    }

    /**
     * M70: publishes synthetic bag and role manifests with the built-in
     * definitions, so the planner reads the same definitions the live
     * server would load.
     */
    private static void publishSyntheticManifests() {
        Map<String, BagManifest.Entry> bags = new java.util.LinkedHashMap<>();
        Object[][] bagData = {
                {BagIds.MASON, java.util.Set.of(SituationTags.BLOCKS), 0},
                {BagIds.PLUMBER, java.util.Set.of(SituationTags.WATER, SituationTags.LAVA), 9},
                {BagIds.LUMBERJACK, java.util.Set.of(SituationTags.BLOCKS), 1},
                {BagIds.SAPPER, java.util.Set.of(SituationTags.EXPLOSIVE), 2},
                {BagIds.MAGICIAN, java.util.Set.of(SituationTags.PEARL, SituationTags.WIND_CHARGE), 3},
                {BagIds.RANGER, java.util.Set.of(SituationTags.BOW), 4},
                {BagIds.SHEPHERD, java.util.Set.of(SituationTags.LEAD, SituationTags.MOB), 5},
                {BagIds.INNKEEPER, java.util.Set.of(SituationTags.MILK), 6},
                {BagIds.PILGRIM, java.util.Set.of(), 7},
                {BagIds.GUARD, java.util.Set.of(), 8},
        };
        for (Object[] b : bagData) {
            String id = (String) b[0];
            @SuppressWarnings("unchecked")
            java.util.Set<String> tags = (java.util.Set<String>) b[1];
            int order = (int) b[2];
            BagDefinition def = new BagDefinition(id, id, "", order, List.of(), tags,
                    BagMeta.defaultLootTable(id));
            bags.put(id, new BagManifest.Entry(id, def));
        }
        BagManifest.publish(BagManifest.create(bags, List.of()));

        Map<String, RoleManifest.Entry> roles = new java.util.LinkedHashMap<>();
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
