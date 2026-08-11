package chatdonkey.core;

/**
 * How an event finished. Selects the exit line pool and the gift tier
 * (SPEC.md sections 4 and 5).
 *
 * <p>M1 only ever produces {@link #WAITED} and {@link #ABORTED}; the rest are
 * declared now because the Twitch bridge (SPEC.md section 11) drives this exact
 * enum and M2 should not have to widen it.
 */
public enum EndReason {

    /** The player sat through the whole thing. Standard gift. */
    WAITED,
    /** Right-clicked with a diamond -- M2. */
    BRIBED,
    /** Met the event's demand, e.g. fed the Food Critic -- M2. */
    SATISFIED,
    /** Player logged out, died, or an operator ended it. No gift, no cooldown penalty. */
    ABORTED;

    /** Whether this ending hands over a gift at all (SPEC.md section 6, cleanup). */
    public boolean givesGift() {
        return this != ABORTED;
    }

    /** Whether this ending starts the player's cooldown. */
    public boolean startsCooldown() {
        return this != ABORTED;
    }

    /** The {@code lines.json} pool suffix for this ending's exit line. */
    public String exitPoolSuffix() {
        return switch (this) {
            case WAITED -> "exit_waited";
            case BRIBED -> "exit_bribed";
            case SATISFIED -> "exit_satisfied";
            case ABORTED -> "exit_waited";
        };
    }

    /**
     * The exit pool, accounting for how rude the player was.
     *
     * <p>A player who hit the donkey three times gets the grudge send-off no
     * matter how the event ended -- including after paying a diamond, which is
     * exactly the joke (SPEC.md section 4).
     */
    public String exitPoolSuffix(int hitCount) {
        return hitCount >= GiftTable.HITS_FOR_GRUDGE ? "exit_grudge" : exitPoolSuffix();
    }
}
