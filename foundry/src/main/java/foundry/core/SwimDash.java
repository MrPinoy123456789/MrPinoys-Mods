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

    /** Feeds one tick and answers whether to dash now. {@code swimming} is in the water and swimming. */
    public boolean tick(boolean jumpHeld, boolean swimming, long now, int cooldown) {
        boolean pressed = jumpHeld && !held;
        held = jumpHeld;
        if (!swimming || !pressed || now < readyAt) return false;
        readyAt = now + cooldown;
        return true;
    }
}
