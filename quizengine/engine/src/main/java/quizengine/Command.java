package quizengine;

import java.util.UUID;

/**
 * Everything that can happen to a round. Note the absence of any notion of time:
 * CloseSubmissions arrives because a tick counter fired, or an admin typed a
 * command, or a test called it directly, and the engine cannot tell the difference.
 */
public sealed interface Command {

    /** Trivia: pick one of the round's fixed options. */
    record Submit(UUID player, int option) implements Command {}

    /**
     * Quiplash: write your own answer. The text becomes a new option on the round,
     * appended in arrival order, which is what makes the submitter its writer.
     */
    record SubmitText(UUID player, String text) implements Command {}

    record Vote(UUID player, int option) implements Command {}

    record CloseSubmissions() implements Command {}

    record OpenVoting() implements Command {}

    record Settle() implements Command {}
}
