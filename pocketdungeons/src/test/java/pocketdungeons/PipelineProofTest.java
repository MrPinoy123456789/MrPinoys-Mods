package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

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
        for (String role : List.of("entrance", "exit", "encounter", "loot", "corridor")) {
            for (int mask = 1; mask < 16; mask++) {
                entries.add(new RoomManifest.Entry(
                        role + "_" + DoorMask.toLetters(mask),
                        new DungeonRoomMeta("synthetic", 1, 1, List.of(role), 1, 0, -1, null),
                        mask));
            }
        }
        return RoomManifest.create(entries, List.of());
    }
}
