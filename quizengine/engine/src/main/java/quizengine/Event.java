package quizengine;

import java.util.UUID;

/**
 * What the engine emits for the orchestrator to react to. Rejection is an event,
 * not an exception — nothing on the hot path throws.
 */
public sealed interface Event {

    record Rejected(UUID player, Reason reason) implements Event {}

    record SubmissionAccepted(UUID player, int option) implements Event {}

    record VoteAccepted(UUID player, int option) implements Event {}

    record PhaseChanged(Phase from, Phase to) implements Event {}

    record Settled(RoundResult result) implements Event {}

    enum Reason {
        WRONG_PHASE,
        OPTION_OUT_OF_RANGE,
        ALREADY_SUBMITTED,
        BLANK_SUBMISSION,
        SUBMISSION_TOO_LONG,
        DUPLICATE_SUBMISSION,
        ALREADY_VOTED,
        SELF_VOTE,
        OPTION_NOT_SUBMITTED,
        NOT_APPLICABLE_TO_ROUND_TYPE
    }
}
