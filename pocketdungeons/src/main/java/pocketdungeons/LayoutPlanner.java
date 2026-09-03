package pocketdungeons;

import java.util.List;

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
    static final int DEFAULT_MAX_GRID_SPAN = 12;

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
                DEFAULT_BRANCH_PROBABILITY, DEFAULT_LOOP_PROBABILITY,
                DEFAULT_MAX_GRID_SPAN, null);
    }

    /**
     * Full form, with the branch and loop rates supplied by the caller -- in live
     * play, from config. Kept separate from the shorter overloads so the pure
     * planner tests are not tied to a config file.
     */
    static Outcome plan(long seed, RoomManifest manifest, int attemptBudget,
                        int minPath, int maxPath,
                        double branchProbability, double loopProbability,
                        int maxGridSpan) {
        return plan(seed, manifest, attemptBudget, minPath, maxPath,
                branchProbability, loopProbability, maxGridSpan, null);
    }

    static Outcome plan(long seed, RoomManifest manifest, int attemptBudget,
                        int minPath, int maxPath,
                        double branchProbability, double loopProbability,
                        int maxGridSpan, String theme) {
        return plan(seed, manifest, attemptBudget, minPath, maxPath,
                branchProbability, loopProbability, maxGridSpan, theme, null);
    }

    /**
     * As the 9-argument {@link #plan}, but the generated shape is rotated
     * (M2/M3's lobby) so its entrance's one door faces
     * {@code requiredEntranceDirection} before it is resolved against the
     * manifest. {@code null} leaves the shape exactly as generated, matching
     * every other caller.
     *
     * <p>This changes no probabilities and produces no new shapes -- rotation
     * is a rigid relabelling (see {@link DungeonShape#rotate}) -- it only picks
     * which of the four equally-likely orientations the generator's random
     * choice gets presented in, so a lobby whose one door is already standing
     * in the world always finds a matching connection.
     */
    static Outcome plan(long seed, RoomManifest manifest, int attemptBudget,
                        int minPath, int maxPath,
                        double branchProbability, double loopProbability,
                        int maxGridSpan, String theme, DoorMask.Direction requiredEntranceDirection) {
        String lastReason = "no attempts were made";

        for (int attempt = 0; attempt < attemptBudget; attempt++) {
            long attemptSeed = seed + attempt;

            DungeonShape shape = LayoutGraphGenerator.generate(
                    attemptSeed, minPath, maxPath, branchProbability, loopProbability);
            if (shape == null) {
                lastReason = "shape generation exhausted its backtracking budget";
                continue;
            }
            if (requiredEntranceDirection != null) {
                shape = rotateToEntranceDirection(shape, requiredEntranceDirection);
            }

            List<String> shapeProblems = LayoutGraphGenerator.validate(shape);
            if (!shapeProblems.isEmpty()) {
                boolean onlyBackward = shapeProblems.stream()
                        .allMatch(p -> p.contains(LayoutGraphGenerator.NO_BACKWARDS_MARKER));
                if (!onlyBackward) {
                    // A shape that generates but fails its own validator for any other
                    // reason is a generator bug, not bad luck -- surface it rather than
                    // burning the budget.
                    return new Outcome(null, attempt + 1, attemptSeed,
                            "generated shape failed graph validation: "
                                    + String.join("; ", shapeProblems));
                }
                // Cells behind the entrance are an expected, unlucky outcome of the
                // backtracker's free branch/loop placement, not a generator bug --
                // burn a retry and try the next seed instead of hard-failing.
                lastReason = "generated shape had cells behind the entrance: "
                        + String.join("; ", shapeProblems);
                continue;
            }

            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, theme);
            if (result.plan() == null) {
                RoomSelector.Failure failure = result.failure();
                lastReason = "no room satisfies cell " + failure.cell()
                        + " (mask " + DoorMask.toLetters(failure.mask())
                        + ", role " + failure.role() + ")"
                        + (theme != null ? " for theme " + theme : "");
                continue;
            }

            List<String> planProblems = RoomSelector.validate(result.plan(), maxGridSpan);
            if (!planProblems.isEmpty()) {
                lastReason = "plan failed validation: " + String.join("; ", planProblems);
                continue;
            }

            return new Outcome(result.plan(), attempt + 1, attemptSeed, null);
        }

        return new Outcome(null, attemptBudget, seed + attemptBudget - 1, lastReason);
    }

    /** Rotates {@code shape} by whatever quarter-turn count puts its entrance edge on {@code direction}. */
    private static DungeonShape rotateToEntranceDirection(DungeonShape shape, DoorMask.Direction direction) {
        DoorMask.Direction current = shape.entranceDirection();
        if (current == null) {
            return shape; // no outgoing edge at all -- LayoutGraphGenerator.validate catches this separately
        }
        int currentBit = DoorMask.fromEdges(java.util.Set.of(current));
        int targetBit = DoorMask.fromEdges(java.util.Set.of(direction));
        for (int q = 0; q < 4; q++) {
            if (DoorMask.rotateClockwise(currentBit, q) == targetBit) {
                return shape.rotate(q);
            }
        }
        return shape; // unreachable: rotateClockwise cycles through all 4 single bits
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
    }
}
