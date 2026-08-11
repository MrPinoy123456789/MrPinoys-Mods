package quizengine;

/**
 * Every balance constant in one place. Loaded from config by the orchestrator and
 * handed to the round at construction, so settling stays a pure function of its inputs.
 *
 * @param participation  paid to anyone who submitted, in any settled round
 * @param triviaCorrect  paid on top of participation for a correct trivia answer
 * @param writerBase     paid to the writer of a winning Quiplash option
 * @param perVote        paid to that writer per vote the winning option received
 * @param voterBase      paid to each voter who backed a winning option
 * @param voterPool      split evenly among all voters who backed a winning option
 * @param quorumOptions  minimum distinct options that must be picked for a Quiplash
 *                       round to be worth voting on; below this the round is skipped
 */
public record ScoringConfig(
        int participation,
        int triviaCorrect,
        int writerBase,
        int perVote,
        int voterBase,
        int voterPool,
        int quorumOptions
) {
    public ScoringConfig {
        if (quorumOptions < 1) {
            throw new IllegalArgumentException("quorumOptions must be at least 1");
        }
    }

    /** A starting point. Retune once real players have touched it. */
    public static ScoringConfig defaults() {
        return new ScoringConfig(1, 5, 10, 3, 2, 12, 2);
    }
}
