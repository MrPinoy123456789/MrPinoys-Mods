package chatdonkey.core;

/**
 * Sprints circles around the player shouting about a creeper, or lava, or "the
 * void" -- none of which exist (SPEC.md section 4).
 *
 * <p>The shortest of the six at 10-20 seconds, and the most talkative. The joke
 * is entirely in the panic, and panic does not survive being drawn out.
 *
 * <p>It is also the behavior that most needs SPEC.md section 1's rule holding:
 * the donkey is screaming about a threat it cannot create. Nothing here touches
 * the player's health, and the alarm is always false.
 */
public final class FalseAlarmBehavior extends AbstractBehavior {

    /** Wider than the serenade -- it is running, not performing. */
    public static final double ORBIT_RADIUS = 3.0;

    /** One frantic lap every four seconds. */
    public static final int TICKS_PER_LAP = 80;

    private static final int STEER_INTERVAL_TICKS = 8;

    /** Full sprint. */
    private static final double SPEED = 1.7;

    private static final double LEAD_TICKS = 16;

    private double startAngle;

    @Override
    public String id() {
        return "falsealarm";
    }

    @Override
    public int minDurationSeconds() {
        return 10;
    }

    @Override
    public int maxDurationSeconds() {
        return 20;
    }

    @Override
    protected int lineGapMinTicks() {
        return 2 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 4 * 20;
    }

    @Override
    protected void onStart(EventContext ctx) {
        startAngle = ctx.random().nextDouble() * Math.PI * 2.0;
    }

    @Override
    protected void onTick(EventContext ctx) {
        int elapsed = ctx.elapsedTicks();
        if (elapsed % STEER_INTERVAL_TICKS != 0) {
            return;
        }
        double angle = startAngle + ((elapsed + LEAD_TICKS) / TICKS_PER_LAP) * Math.PI * 2.0;
        ctx.steerTo(ctx.playerX() + Math.cos(angle) * ORBIT_RADIUS,
                ctx.playerY(),
                ctx.playerZ() + Math.sin(angle) * ORBIT_RADIUS,
                SPEED);
    }
}
