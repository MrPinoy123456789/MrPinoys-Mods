package foundry.core;

/**
 * One Fae's wing dash: a press of jump in the air beats the leaf wings, a burst the way they look, and they fall slowly
 * after it until they land or press sneak. Pure, so it can be tested. The numbers are a {@link RaceTraits.Dash}.
 *
 * <ul>
 *   <li>Only a fresh press counts, and not in the first ticks off the ground (that press is the ground jump's own), the
 *   same as {@link AirJump}.</li>
 *   <li>A few charges: all back on landing (or in water, riding, gliding), and one back every {@code refill} ticks in
 *   the air, so a Fae who paces the bursts stays up. Bursts are at least {@code gap} ticks apart.</li>
 *   <li>A fresh press of sneak ends the slow fall (a fresh press, so a Bedrock touch player whose sneak is toggled on
 *   still flies).</li>
 *   <li>A hit takes a charge and drops them for a moment.</li>
 *   <li>A mace in hand folds the wings for a dive ({@link #dive}): no slow fall, and bursts steer without climbing or
 *   breaking the fall. Height the wings gave is no smash: the smash counts only the drop below where the wings first
 *   climbed this time in the air ({@link #credit}).</li>
 * </ul>
 */
public final class WingDash {
    private boolean held;
    private boolean sneaking;
    private int charges = -1;
    private int airTicks;
    private long lastBurst = Long.MIN_VALUE;
    private long refillAt = Long.MIN_VALUE;
    private long dropUntil = Long.MIN_VALUE;
    private double climbFrom = Double.NaN;
    private boolean floating;
    private boolean burstThisAir;
    private boolean refused;
    private boolean refilled;

    /**
     * Feeds one tick and answers whether to burst now. {@code grounded} is anything that ends a time in the air (on the
     * ground, in water, riding, flying or gliding).
     */
    public boolean tick(boolean jumpHeld, boolean sneakHeld, boolean grounded, long now, RaceTraits.Dash d) {
        boolean pressed = jumpHeld && !held;
        boolean sneakPressed = sneakHeld && !sneaking;
        held = jumpHeld;
        sneaking = sneakHeld;
        refused = false;
        refilled = false;
        if (charges < 0 || charges > d.charges()) charges = d.charges();
        if (grounded) {
            charges = d.charges();
            airTicks = 0;
            floating = false;
            burstThisAir = false;
            refillAt = Long.MIN_VALUE;
            climbFrom = Double.NaN;
            return false;
        }
        airTicks++;
        if (charges < d.charges()) {
            if (refillAt == Long.MIN_VALUE) {
                refillAt = now + d.refill();
            } else if (now >= refillAt) {
                charges++;
                refilled = true;
                refillAt = charges < d.charges() ? now + d.refill() : Long.MIN_VALUE;
            }
        }
        if (sneakPressed) floating = false;
        if (!pressed || airTicks <= AirJump.SETTLE) return false;
        if (charges <= 0 || (lastBurst != Long.MIN_VALUE && now < lastBurst + d.gap())) {
            refused = true;
            return false;
        }
        charges--;
        lastBurst = now;
        floating = true;
        burstThisAir = true;
        if (refillAt == Long.MIN_VALUE) refillAt = now + d.refill();
        return true;
    }

    /** A hit from a mob, a player or a projectile while in the air: one charge gone and the slow fall stops for a moment. */
    public void hit(long now, RaceTraits.Dash d) {
        if (airTicks == 0) return;
        if (charges > 0) charges--;
        dropUntil = now + d.hitDrop();
        if (refillAt == Long.MIN_VALUE && charges < d.charges()) refillAt = now + d.refill();
    }

    /**
     * Folds the wings for a dive (a mace in hand): the slow fall ends and does not come back until the next burst made
     * without the mace. Answers true when it ended a slow fall that was holding them.
     */
    public boolean dive() {
        boolean was = floating;
        floating = false;
        return was;
    }

    /** A burst that may climb began at height {@code y}; the lowest this time in the air is kept (see {@link #credit}). */
    public void climbedFrom(double y) {
        climbFrom = Double.isNaN(climbFrom) ? y : Math.min(climbFrom, y);
    }

    /**
     * The fall a mace smash may count at height {@code y}: the fall so far, but never more than the drop below the lowest
     * point a burst climbed from this time in the air, so wing height is no smash. A drop off a cliff or after a wind
     * charge, with no burst, counts in full.
     */
    public double credit(double fallDistance, double y) {
        return Double.isNaN(climbFrom) ? fallDistance : Math.min(fallDistance, Math.max(0, climbFrom - y));
    }

    /** True while the slow fall should hold them: after a burst this time in the air, not cancelled, not just hit. */
    public boolean slowFall(long now) {
        return floating && now >= dropUntil;
    }

    /** Charges left (the full count before the first tick). */
    public int charges() {
        return Math.max(0, charges);
    }

    /** Ticks in the air this time off the ground (0 on the ground). */
    public int airTicks() {
        return airTicks;
    }

    /** True once a burst was used this time off the ground. */
    public boolean burstThisAir() {
        return burstThisAir;
    }

    /** True when this tick's press would have been a burst but no charge was ready or the last burst was too recent. */
    public boolean refused() {
        return refused;
    }

    /** True when a charge came back this tick. */
    public boolean refilled() {
        return refilled;
    }

    /** Ticks until the next burst is allowed (0 when ready). */
    public long waiting(long now, RaceTraits.Dash d) {
        long wait = 0;
        if (lastBurst != Long.MIN_VALUE) wait = Math.max(wait, lastBurst + d.gap() - now);
        if (charges <= 0 && refillAt != Long.MIN_VALUE) wait = Math.max(wait, refillAt - now);
        return Math.max(0, wait);
    }

    /**
     * The motion after a burst, as {x, y, z} blocks per tick, from the motion before it and the way the player looks
     * ({@code yaw} and {@code pitch} in Minecraft's degrees: yaw 0 is +z, pitch -90 is straight up). The push across the
     * ground is {@code forward} times how level the look is; the lift is half of {@code up} looking level, all of it
     * looking straight up and none looking straight down, and none at all when {@code overCeiling}. A little of the
     * motion they had is kept, but never past {@code maxSpeed} across the ground, and a faster climb is never slowed.
     */
    public static double[] burst(double vx, double vy, double vz, float yaw, float pitch, RaceTraits.Dash d, boolean overCeiling) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double level = Math.cos(pr) * d.forward();
        double upness = -Math.sin(pr);
        double lift = overCeiling ? 0 : d.up() * (0.5 + 0.5 * upness);
        double x = vx * 0.4 - Math.sin(yr) * level;
        double z = vz * 0.4 + Math.cos(yr) * level;
        double across = Math.sqrt(x * x + z * z);
        if (across > d.maxSpeed()) {
            x = x / across * d.maxSpeed();
            z = z / across * d.maxSpeed();
        }
        return new double[] {x, Math.max(lift, overCeiling ? Math.min(vy, 0) : vy), z};
    }

    /**
     * The motion after a burst while diving with a mace: the same push across the ground as {@link #burst}, but the
     * up and down motion is left as it was, so the burst neither climbs nor slows the drop.
     */
    public static double[] dive(double vx, double vy, double vz, float yaw, float pitch, RaceTraits.Dash d) {
        double[] after = burst(vx, vy, vz, yaw, pitch, d, true);
        after[1] = vy;
        return after;
    }
}
