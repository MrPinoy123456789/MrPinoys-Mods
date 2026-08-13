package wayfarers.core;

/**
 * "Has enough time passed to do this again?", in server ticks.
 *
 * <p>Used for dialogue rate-limiting: a listener must be able to hear one line
 * at a time, not a stack of overlapping encounters.
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
