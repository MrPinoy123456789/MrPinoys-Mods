package foundry.core;

/**
 * One player's swim dash: when a press of jump while swimming throws them forward. Pure, so it can be tested.
 *
 * <ul>
 *   <li>Only a fresh press while swimming counts: jump held from before they started swimming does not.</li>
 *   <li>Not again until the cooldown has run out, counted from the last dash.</li>
 * </ul>
 */
public final class SwimDash {
    private boolean held;
    private long readyAt = Long.MIN_VALUE;
    private boolean refused;

    /** Feeds one tick and answers whether to dash now. {@code swimming} is in the water and swimming. */
    public boolean tick(boolean jumpHeld, boolean swimming, long now, int cooldown) {
        boolean pressed = jumpHeld && !held;
        held = jumpHeld;
        refused = false;
        if (!swimming || !pressed) return false;
        if (now < readyAt) {
            refused = true;
            return false;
        }
        readyAt = now + cooldown;
        return true;
    }

    /** True when this tick's press would have been a dash but the cooldown had not run out. */
    public boolean refused() {
        return refused;
    }

    /** Ticks until the next dash is allowed (0 when ready). */
    public long waiting(long now) {
        return Math.max(0, readyAt - now);
    }
}
