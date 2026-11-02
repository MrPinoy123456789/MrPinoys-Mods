package foundry.core;

/**
 * One Fairy's powered flight, the middle way between gliding only and rockets. Pure, so it can be tested.
 *
 * <ul>
 *   <li>Takeoff leap: on the ground, holding sneak, a fresh press of jump springs them high into the air, and they glide
 *   from the top of it. It has its own cooldown.</li>
 *   <li>Wingbeats: while gliding, a fresh press of jump beats the wings: a lift and a little push forward. There are only
 *   a few; all come back after a moment on the ground, and one at a time, slowly, in the air. So a Fairy can climb out
 *   of a dive or over a hill, but cannot stay up for ever or keep pace with rockets.</li>
 * </ul>
 */
public final class WingFlight {
    /** What to do this tick. */
    public enum Act { NONE, LEAP, BEAT }

    /** Ticks on the ground before the wingbeats all come back. */
    static final int LANDED = 10;

    private boolean held;
    private int charges = -1;
    private int grounded;
    private long leapReady = Long.MIN_VALUE;
    private long refillAt = Long.MIN_VALUE;

    /**
     * Feeds one tick and answers what to do. {@code gliding} is the elytra glide; {@code grounded} is standing on
     * something (not in water, not riding).
     */
    public Act tick(boolean jumpHeld, boolean sneakHeld, boolean onGround, boolean gliding, long now, RaceTraits.Flight f) {
        if (charges < 0) charges = f.beats();
        boolean pressed = jumpHeld && !held;
        held = jumpHeld;
        if (onGround) {
            if (++grounded >= LANDED) charges = f.beats();
            refillAt = Long.MIN_VALUE;
        } else {
            grounded = 0;
            // slowly, one at a time, in the air
            if (charges < f.beats()) {
                if (refillAt == Long.MIN_VALUE) refillAt = now + f.refill();
                else if (now >= refillAt) {
                    charges++;
                    refillAt = charges < f.beats() ? now + f.refill() : Long.MIN_VALUE;
                }
            }
        }
        if (!pressed) return Act.NONE;
        if (onGround && sneakHeld && f.leap() > 0 && now >= leapReady) {
            leapReady = now + f.leapCooldown();
            return Act.LEAP;
        }
        if (gliding && charges > 0 && f.beatLift() > 0) {
            charges--;
            if (refillAt == Long.MIN_VALUE) refillAt = now + f.refill();
            return Act.BEAT;
        }
        return Act.NONE;
    }

    /** Wingbeats left. */
    public int charges() {
        return Math.max(0, charges);
    }

    /** Ticks until the takeoff leap is ready again (0 when ready). */
    public long leapWait(long now) {
        return Math.max(0, leapReady - now);
    }

    /**
     * The motion after a wingbeat, as {x, y, z}, from the motion before it and the way the Fairy looks ({@code lookX},
     * {@code lookZ}, the horizontal part of the look). The lift sets the climb (any fall is cancelled first, so a beat
     * always rises), the push adds speed the way they look, and the horizontal speed is capped at {@code maxSpeed} so
     * beats never stack into rocket speed.
     */
    public static double[] beat(double vx, double vy, double vz, double lookX, double lookZ, RaceTraits.Flight f, double maxSpeed) {
        double len = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double nx = len == 0 ? 0 : lookX / len, nz = len == 0 ? 0 : lookZ / len;
        double x = vx + nx * f.beatPush(), z = vz + nz * f.beatPush();
        double h = Math.sqrt(x * x + z * z);
        if (h > maxSpeed) {
            x = x / h * maxSpeed;
            z = z / h * maxSpeed;
        }
        return new double[] {x, Math.max(0, vy) + f.beatLift(), z};
    }
}
