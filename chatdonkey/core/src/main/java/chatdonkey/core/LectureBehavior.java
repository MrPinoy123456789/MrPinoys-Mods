package chatdonkey.core;

/**
 * Follows at 1-2 blocks, holds eye contact, and fires a sass line every 6-8
 * seconds for 30-60 seconds (SPEC.md section 4).
 *
 * <p>The plainest of the six, and the one M1 shipped to prove the loop.
 */
public final class LectureBehavior extends AbstractBehavior {

    /** Close enough to be in the player's face; not so close it shoves them. */
    public static final double FOLLOW_DISTANCE = 1.5;

    /** Beyond this the donkey has lost the argument and hurries to catch up. */
    public static final double SPRINT_DISTANCE = 6.0;

    private static final double WALK_SPEED = 1.0;
    private static final double CATCH_UP_SPEED = 1.35;

    /** Re-path this often. Every tick fights vanilla's wander goals for nothing. */
    public static final int STEER_INTERVAL_TICKS = 10;

    @Override
    public String id() {
        return "lecture";
    }

    @Override
    public int minDurationSeconds() {
        return 30;
    }

    @Override
    public int maxDurationSeconds() {
        return 60;
    }

    @Override
    protected int lineGapMinTicks() {
        return 6 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 8 * 20;
    }

    @Override
    protected void onTick(EventContext ctx) {
        if (ctx.elapsedTicks() % STEER_INTERVAL_TICKS != 0) {
            return;
        }
        double distance = ctx.distanceToPlayer();
        if (distance > FOLLOW_DISTANCE) {
            ctx.steerTowardPlayer(FOLLOW_DISTANCE,
                    distance > SPRINT_DISTANCE ? CATCH_UP_SPEED : WALK_SPEED);
        }
    }
}
