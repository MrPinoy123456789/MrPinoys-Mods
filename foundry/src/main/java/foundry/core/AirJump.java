package foundry.core;

/**
 * One player's double jump: when a second press of jump in the air lifts them again. Pure, so it can be tested.
 *
 * <ul>
 *   <li>Only a fresh press counts: the jump that left the ground is still held, so it has to be let go and pressed again,
 *   and a press in the first ticks off the ground is the ground jump's own.</li>
 *   <li>Once per time in the air: landing (or standing in water, riding, flying) gives it back.</li>
 *   <li>Not again until the cooldown has run out, counted from the last air jump.</li>
 * </ul>
 */
public final class AirJump {
    /** Ticks in the air before a press counts: the ground jump's own press can arrive in the tick the player lifts off. */
    static final int SETTLE = 2;
    private boolean held;
    private boolean used;
    private int airTicks;
    private long readyAt = Long.MIN_VALUE;

    /**
     * Feeds one tick and answers whether to jump now. {@code grounded} is anything that ends a time in the air (on the
     * ground, in water, riding, flying or gliding).
     */
    public boolean tick(boolean jumpHeld, boolean grounded, long now, int cooldown) {
        boolean pressed = jumpHeld && !held;
        held = jumpHeld;
        if (grounded) {
            used = false;
            airTicks = 0;
            return false;
        }
        airTicks++;
        if (!pressed || used || airTicks <= SETTLE || now < readyAt) return false;
        used = true;
        readyAt = now + cooldown;
        return true;
    }

    /** Ticks in the air this time off the ground (0 on the ground). */
    public int airTicks() {
        return airTicks;
    }

    /** True once the air jump was used this time off the ground. */
    public boolean used() {
        return used;
    }

    /** Ticks until the next air jump is allowed (0 when ready). */
    public long waiting(long now) {
        return Math.max(0, readyAt - now);
    }

    /** The push of a double jump with no drift: see {@link #push(float, boolean, boolean, boolean, boolean, double)}. */
    public static double[] push(float yaw, boolean forward, boolean backward, boolean left, boolean right) {
        return push(yaw, forward, backward, left, right, 0);
    }

    /**
     * The push of a double jump, as {x, y, z} blocks per tick, for a player facing {@code yaw} degrees (Minecraft's: 0 is
     * +z) with these movement keys held: a hop in the held direction, plus {@code drift} blocks per tick the way they
     * face whatever they hold (the Fae's little carry forward, race_traits.json jump_drift).
     */
    public static double[] push(float yaw, boolean forward, boolean backward, boolean left, boolean right, double drift) {
        double[] keys = keys(yaw, forward, backward, left, right);
        double r = Math.toRadians(yaw);
        return new double[] {keys[0] - Math.sin(r) * drift, keys[1], keys[2] + Math.cos(r) * drift};
    }

    private static double[] keys(float yaw, boolean forward, boolean backward, boolean left, boolean right) {
        double f = (forward ? 1 : 0) - (backward ? 1 : 0);
        double s = (left ? 1 : 0) - (right ? 1 : 0);
        double len = Math.sqrt(f * f + s * s);
        double up = 0.55;
        if (len == 0) return new double[] {0, up, 0};
        f /= len;
        s /= len;
        double r = Math.toRadians(yaw), sin = Math.sin(r), cos = Math.cos(r);
        double speed = 0.38;
        // forward is (-sin, cos); a strafe to the left is (cos, sin), as vanilla's moveRelative
        return new double[] {(-sin * f + cos * s) * speed, up, (cos * f + sin * s) * speed};
    }
}
