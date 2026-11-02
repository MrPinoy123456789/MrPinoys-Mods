package foundry.core;

/**
 * When to teach a Fae their leaf wings: a short hint on the action bar while they are in the air after a plain jump,
 * until they have done one double jump. Pure, so it can be tested.
 */
public final class JumpTip {
    /** Ticks in the air before the hint shows: past the ground jump's own press, near the top of the jump. */
    public static final int AIR_TICKS = 6;
    /** At most one hint this many ticks apart (30 seconds), so it teaches without nagging. */
    public static final int EVERY = 600;
    public static final String TEXT = "Fae wings: press jump again in the air for a second leap";
    public static final String WELCOME = "Your leaf wings lift a second jump: jump, then press jump again while in the air. "
            + "It comes back when you land.";

    private JumpTip() {}

    /** True when the hint should show this tick. {@code lastShown} is Long.MIN_VALUE when it never has. */
    public static boolean show(boolean learned, int airTicks, boolean usedThisAir, long now, long lastShown) {
        if (learned || usedThisAir || airTicks != AIR_TICKS) return false;
        return lastShown == Long.MIN_VALUE || now - lastShown >= EVERY;
    }
}
