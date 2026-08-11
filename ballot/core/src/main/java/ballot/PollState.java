package ballot;

/**
 * Where a poll is in its life.
 *
 * <p>Transitions are always driven from outside — a command or a deadline. Nothing in
 * here decides on its own that it is time to move on.
 */
public enum PollState {
    /** Being set up. Nothing is public, stations can be bound. */
    DRAFT,
    /** Entries can be claimed, named and released. No voting yet. */
    OPEN,
    /** Entries locked. Votes accepted. */
    VOTING,
    /** Tallied. The result stands. */
    CLOSED,
    /** Retired to the archive file. */
    ARCHIVED;

    public boolean acceptsVotes() {
        return this == VOTING;
    }

    public boolean acceptsClaims() {
        return this == OPEN;
    }

    public boolean isFinished() {
        return this == CLOSED || this == ARCHIVED;
    }
}
