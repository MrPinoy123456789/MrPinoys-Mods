package chatdonkey.core;

/**
 * Circles the player, brays every three seconds, and chat-lines "song lyrics"
 * (SPEC.md section 4).
 *
 * <p>The bray is the point of this one, and it is the single deliberate
 * exception to the suite's quiet-sound rule: a loud world sound from the entity,
 * because it is a performance rather than a confirmation (SPEC.md section 8).
 * Short by design at 15-30 seconds -- a serenade that outstays its welcome is
 * just noise.
 */
public final class SerenadeBehavior extends AbstractBehavior {

    /** Far enough to circle without shoving, close enough to be inescapable. */
    public static final double ORBIT_RADIUS = 2.5;

    /** One full lap every eight seconds. */
    public static final int TICKS_PER_LAP = 160;

    public static final int BRAY_INTERVAL_TICKS = 3 * 20;

    /** Re-aim this often; the donkey walks between waypoints on its own. */
    private static final int STEER_INTERVAL_TICKS = 10;

    /** It has to hustle to keep the circle up. */
    private static final double SPEED = 1.4;

    /** How far ahead on the circle to aim, so it keeps moving rather than arriving. */
    private static final double LEAD_TICKS = 20;

    private double startAngle;

    @Override
    public String id() {
        return "serenade";
    }

    @Override
    public int minDurationSeconds() {
        return 15;
    }

    @Override
    public int maxDurationSeconds() {
        return 30;
    }

    @Override
    protected int lineGapMinTicks() {
        return 4 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 6 * 20;
    }

    @Override
    protected void onStart(EventContext ctx) {
        // Start the lap from wherever it happens to have spawned, so it does not
        // snap to a fixed compass point.
        startAngle = ctx.random().nextDouble() * Math.PI * 2.0;
    }

    @Override
    protected void onTick(EventContext ctx) {
        int elapsed = ctx.elapsedTicks();

        if (elapsed % BRAY_INTERVAL_TICKS == 0) {
            ctx.bray();
        }
        if (elapsed % STEER_INTERVAL_TICKS == 0) {
            double angle = angleAt(elapsed + LEAD_TICKS);
            ctx.steerTo(ctx.playerX() + Math.cos(angle) * ORBIT_RADIUS,
                    ctx.playerY(),
                    ctx.playerZ() + Math.sin(angle) * ORBIT_RADIUS,
                    SPEED);
        }
    }

    /** Where on the circle the donkey should be aiming at a given tick. */
    double angleAt(double tick) {
        return startAngle + (tick / TICKS_PER_LAP) * Math.PI * 2.0;
    }
}
