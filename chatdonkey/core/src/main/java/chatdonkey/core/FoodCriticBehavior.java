package chatdonkey.core;

/**
 * Follows the player demanding a carrot (SPEC.md section 4). Feeding it any
 * carrot ends the event early with a better gift; a golden carrot earns the
 * best one.
 *
 * <p>The one behavior with an exit the player can actually *solve*, which is
 * what makes it worth having next to the bribe: a second verb that costs a
 * carrot rather than a diamond.
 */
public final class FoodCriticBehavior extends AbstractBehavior {

    /** Close enough to be obviously begging. */
    public static final double FOLLOW_DISTANCE = 2.0;

    private static final double WALK_SPEED = 1.05;
    private static final double CATCH_UP_SPEED = 1.4;
    private static final double SPRINT_DISTANCE = 6.0;

    public static final int STEER_INTERVAL_TICKS = 10;

    @Override
    public String id() {
        return "foodcritic";
    }

    @Override
    public int minDurationSeconds() {
        return 30;
    }

    @Override
    public int maxDurationSeconds() {
        return 45;
    }

    @Override
    protected int lineGapMinTicks() {
        return 5 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 7 * 20;
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

    /** Fed: leave early and happy (SPEC.md section 4). */
    @Override
    public boolean wantsEarlyEnd(EventContext ctx) {
        return ctx.fedTreat() != null;
    }

    @Override
    public boolean wantsTreats() {
        return true;
    }

    /** A golden carrot deserves its own reaction, if the operator wrote one. */
    @Override
    protected String exitPool(EventContext ctx, EndReason reason) {
        if (reason == EndReason.SATISFIED
                && ctx.fedTreat() == Treat.GOLDEN_CARROT
                && ctx.hitCount() < GiftTable.HITS_FOR_GRUDGE
                && ctx.lines().has(LinePools.key(id(), "exit_golden"))) {
            return "exit_golden";
        }
        return super.exitPool(ctx, reason);
    }
}
