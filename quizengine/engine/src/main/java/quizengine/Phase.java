package quizengine;

/**
 * Lifecycle of a round.
 *
 * <p>Trivia:   SUBMITTING -> SETTLED
 * <p>Quiplash: SUBMITTING -> CLOSED -> VOTING -> SETTLED (or SKIPPED)
 *
 * <p>CLOSED is the gap between submissions closing and voting opening. The engine
 * does nothing during it; it exists so the orchestrator has somewhere to display
 * the collected answers before asking anyone to judge them.
 */
public enum Phase {
    SUBMITTING,
    CLOSED,
    VOTING,
    SETTLED,
    SKIPPED;

    public boolean isTerminal() {
        return this == SETTLED || this == SKIPPED;
    }
}
