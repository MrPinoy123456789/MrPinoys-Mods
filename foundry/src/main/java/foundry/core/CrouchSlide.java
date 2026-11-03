package foundry.core;

/**
 * One player's crouch slide: sprinting on the ground, a fresh press of sneak slides them forward. Pure, so it can be
 * tested.
 *
 * <ul>
 *   <li>Only a fresh press counts; sneak held from before (a Bedrock touch toggle left on) does not.</li>
 *   <li>The client stops sprinting the moment sneak goes down, so sprinting in any of the last {@link #SPRINT_GRACE}
 *   ticks counts.</li>
 *   <li>Only on the ground, and not again until the cooldown has run out, counted from the last slide. A press that
 *   would have slid but for the cooldown is refused, so the player can be told how long is left.</li>
 * </ul>
 */
public final class CrouchSlide {
    /** Ticks after sprinting stops in which a sneak press still slides. */
    public static final int SPRINT_GRACE = 3;
    private boolean held;
    private long sprintedAt = Long.MIN_VALUE;
    private long readyAt = Long.MIN_VALUE;
    private boolean refused;

    /** Feeds one tick and answers whether to slide now. {@code grounded} is standing on a block, not in water or riding. */
    public boolean tick(boolean sneakHeld, boolean sprinting, boolean grounded, long now, int cooldown) {
        boolean pressed = sneakHeld && !held;
        held = sneakHeld;
        refused = false;
        if (sprinting) sprintedAt = now;
        boolean running = sprintedAt != Long.MIN_VALUE && now - sprintedAt <= SPRINT_GRACE;
        if (!pressed || !running || !grounded) return false;
        if (now < readyAt) {
            refused = true;
            return false;
        }
        readyAt = now + cooldown;
        sprintedAt = Long.MIN_VALUE;
        return true;
    }

    /** True when this tick's press would have been a slide but the cooldown had not run out. */
    public boolean refused() {
        return refused;
    }

    /** Ticks until the next slide is allowed (0 when ready). */
    public long waiting(long now) {
        return Math.max(0, readyAt - now);
    }

    /** The slide's motion, {x, z} blocks per tick, for a player facing {@code yaw} degrees (Minecraft's: 0 is +z). */
    public static double[] push(float yaw, double speed) {
        double r = Math.toRadians(yaw);
        return new double[] {-Math.sin(r) * speed, Math.cos(r) * speed};
    }
}
