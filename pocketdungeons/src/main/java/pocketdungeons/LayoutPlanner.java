package pocketdungeons;

import java.util.List;
import java.util.Optional;

/**
 * Top-level planner: ties graph generation ({@link LayoutGraphGenerator}) to
 * template resolution ({@link RoomSelector}) with the section 7.2 step 8 retry
 * budget, so a seed whose shape happens to be unbuildable with the current room
 * library is retried rather than hard-failing.
 *
 * <p>Pure computation, no Minecraft imports -- runnable and testable outside the
 * game, same discipline as the two halves it composes.
 *
 * <p><strong>What happens when the budget is exhausted is deliberately left to
 * the caller.</strong> The spec's answer is "fall back to a hand-authored static
 * layout so {@code /dungeon} never hard-fails" (section 7.2 step 8), and that
 * fallback already exists as {@link StaticLayout} -- but wiring it up is a
 * stamping decision, and nothing stamps a procedural plan yet (that's M4). This
 * class reports failure honestly and lets M4 decide; it does not silently
 * substitute anything.
 */
final class LayoutPlanner {

    /**
     * Section 7.2's v1 difficulty profile: a critical path of 8-12 cells. These
     * are the <em>test</em> defaults only -- live play reads {@code pathLengthMin}
     * and {@code pathLengthMax} from config, which ship at 5-8. The proposal
     * shrank the range deliberately: 12 rooms is more walking than the room
     * library's variety currently sustains, and every extra cell is a force-load
     * ticket and a teardown cost.
     */
    static final int DEFAULT_MIN_PATH = 8;
    static final int DEFAULT_MAX_PATH = 12;

    /** As above: test defaults, not the live-play profile. */
    static final double DEFAULT_BRANCH_PROBABILITY = 0.55;
    static final double DEFAULT_LOOP_PROBABILITY = 0.30;

    /** How many consecutive seeds to try before giving up. */
    static final int DEFAULT_ATTEMPT_BUDGET = 16;

    private LayoutPlanner() {}

    /**
     * Plans a dungeon starting from {@code seed}, incrementing the seed on each
     * failed attempt up to the budget.
     *
     * @return the first plan that both resolved and validated, or an outcome
     *         describing why every attempt failed
     */
    static Outcome plan(long seed, RoomManifest manifest, int attemptBudget) {
        return plan(seed, manifest, attemptBudget, DEFAULT_MIN_PATH, DEFAULT_MAX_PATH);
    }

    static Outcome plan(long seed, RoomManifest manifest, int attemptBudget,
                        int minPath, int maxPath) {
        return plan(seed, manifest, attemptBudget, minPath, maxPath,
                DEFAULT_BRANCH_PROBABILITY, DEFAULT_LOOP_PROBABILITY);
    }

    /**
     * Full form, with the branch and loop rates supplied by the caller -- in live
     * play, from config. Kept separate from the shorter overloads so the pure
     * planner tests are not tied to a config file.
     */
    static Outcome plan(long seed, RoomManifest manifest, int attemptBudget,
                        int minPath, int maxPath,
                        double branchProbability, double loopProbability) {
        String lastReason = "no attempts were made";

        for (int attempt = 0; attempt < attemptBudget; attempt++) {
            long attemptSeed = seed + attempt;

            DungeonShape shape = LayoutGraphGenerator.generate(
                    attemptSeed, minPath, maxPath, branchProbability, loopProbability);
            if (shape == null) {
                lastReason = "shape generation exhausted its backtracking budget";
                continue;
            }

            List<String> shapeProblems = LayoutGraphGenerator.validate(shape);
            if (!shapeProblems.isEmpty()) {
                // A shape that generates but fails its own validator is a generator
                // bug, not bad luck -- surface it rather than burning the budget.
                return new Outcome(null, attempt + 1, attemptSeed,
                        "generated shape failed graph validation: "
                                + String.join("; ", shapeProblems));
            }

            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest);
            if (result.plan() == null) {
                RoomSelector.Failure failure = result.failure();
                lastReason = "no room satisfies cell " + failure.cell()
                        + " (mask " + DoorMask.toLetters(failure.mask())
                        + ", role " + failure.role() + ")";
                continue;
            }

            List<String> planProblems = RoomSelector.validate(result.plan());
            if (!planProblems.isEmpty()) {
                lastReason = "plan failed validation: " + String.join("; ", planProblems);
                continue;
            }

            return new Outcome(result.plan(), attempt + 1, attemptSeed, null);
        }

        return new Outcome(null, attemptBudget, seed + attemptBudget - 1, lastReason);
    }

    /**
     * @param plan          the validated plan, or null if every attempt failed
     * @param attemptsUsed  how many seeds were tried
     * @param finalSeed     the seed of the last attempt (the successful one, on success)
     * @param failureReason why the last attempt failed, or null on success
     */
    record Outcome(DungeonPlan plan, int attemptsUsed, long finalSeed, String failureReason) {

        boolean succeeded() {
            return plan != null;
        }

        Optional<DungeonPlan> planOptional() {
            return Optional.ofNullable(plan);
        }
    }
}
