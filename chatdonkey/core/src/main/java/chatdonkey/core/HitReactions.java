package chatdonkey.core;

/**
 * Counts player hits on the event donkey and rate-limits its indignant replies
 * to one per three seconds (SPEC.md section 4).
 *
 * <p>The rate limit matters more than it looks: without it, a player holding
 * down attack turns the chat into a wall of donkey, which stops being funny
 * about half a second in.
 *
 * <p>The count itself is the entire "consequence" system. Three hits costs the
 * player one gift tier and nothing else -- a worse punchline, never an actual
 * loss (SPEC.md section 1's load-bearing rule).
 */
public final class HitReactions {

    /** One line per three seconds, however hard the player is swinging. */
    public static final int LINE_COOLDOWN_TICKS = 3 * 20;

    private final RateLimit voice = new RateLimit(LINE_COOLDOWN_TICKS);

    private int count;

    /**
     * Records a hit.
     *
     * @return true if the donkey should say something about it right now
     */
    public boolean record(int currentTick) {
        count++;
        return voice.allow(currentTick);
    }

    public int count() {
        return count;
    }

    /** Whether the player has earned the grudge treatment (SPEC.md section 5). */
    public boolean isGrudge() {
        return count >= GiftTable.HITS_FOR_GRUDGE;
    }
}
