package chatdonkey.core;

/**
 * "Has enough time passed to do this again?", in server ticks.
 *
 * <p>Used wherever a player action the donkey reacts to can be repeated faster
 * than the donkey should reply -- hitting it, and right-clicking it with
 * something it refuses. Both flood chat without this, and the animalese makes a
 * flood considerably worse than it looks on paper.
 *
 * <p>The {@code used} flag exists rather than a sentinel start tick because
 * subtracting a {@code MIN_VALUE} sentinel from a small tick number overflows
 * and wraps negative, which silently swallows the first reaction.
 */
public final class RateLimit {

    private final int cooldownTicks;

    private int lastTick;
    private boolean used;

    public RateLimit(int cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }

    /**
     * @return true if the action may happen now, in which case the cooldown
     *         restarts; false if it is still cooling down
     */
    public boolean allow(int currentTick) {
        if (used && currentTick - lastTick < cooldownTicks) {
            return false;
        }
        lastTick = currentTick;
        used = true;
        return true;
    }
}
