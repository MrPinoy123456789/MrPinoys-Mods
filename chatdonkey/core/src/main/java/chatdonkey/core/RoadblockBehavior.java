package chatdonkey.core;

/**
 * The flagship (SPEC.md section 4): paths to a point ~2 blocks along the
 * player's look vector and re-plants itself there each time the player turns or
 * moves.
 *
 * <p>SPEC.md is explicit that the repositioning cadence "decides whether it
 * reads as comedy obstacle or broken pathfinding", so the two things that
 * control it are named constants at the top of this file and nowhere else:
 * {@link #REPLANT_INTERVAL_TICKS} is the floor on how often it may move, and
 * {@link #REPLANT_DISTANCE} is how far the ideal spot must drift before it
 * bothers.
 *
 * <p>The floor matters more than the trigger. Re-pathing every tick makes the
 * donkey vibrate; only re-pathing on a timer makes it lazy. Moving when the spot
 * has genuinely drifted, but never more than a few times a second, is the
 * behaviour that reads as a creature stubbornly getting in your way.
 */
public final class RoadblockBehavior extends AbstractBehavior {

    /** How far in front of the player to plant itself. */
    public static final double BLOCK_DISTANCE = 2.0;

    /** Never re-path more often than this, however much the player jitters. */
    public static final int REPLANT_INTERVAL_TICKS = 15;

    /** How far the ideal spot must move before it is worth re-pathing. */
    public static final double REPLANT_DISTANCE = 1.5;

    /** Brisk: it is trying to cut you off, not stroll after you. */
    private static final double SPEED = 1.3;

    private double lastTargetX;
    private double lastTargetZ;
    private boolean planted;
    private int lastReplantTick;
    private int replants;

    @Override
    public String id() {
        return "roadblock";
    }

    @Override
    public int minDurationSeconds() {
        return 20;
    }

    @Override
    public int maxDurationSeconds() {
        return 40;
    }

    @Override
    protected int lineGapMinTicks() {
        return 6 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 9 * 20;
    }

    @Override
    protected void onStart(EventContext ctx) {
        replant(ctx, targetX(ctx), targetZ(ctx));
    }

    @Override
    protected void onTick(EventContext ctx) {
        double targetX = targetX(ctx);
        double targetZ = targetZ(ctx);
        if (shouldReplant(ctx.elapsedTicks(), targetX, targetZ)) {
            replant(ctx, targetX, targetZ);
        }
    }

    /**
     * Re-path when the ideal spot has drifted, but never faster than the
     * interval floor. The very first plant always goes through.
     */
    boolean shouldReplant(int elapsedTicks, double targetX, double targetZ) {
        if (!planted) {
            return true;
        }
        if (elapsedTicks - lastReplantTick < REPLANT_INTERVAL_TICKS) {
            return false;
        }
        double dx = targetX - lastTargetX;
        double dz = targetZ - lastTargetZ;
        return (dx * dx + dz * dz) >= REPLANT_DISTANCE * REPLANT_DISTANCE;
    }

    private void replant(EventContext ctx, double targetX, double targetZ) {
        lastTargetX = targetX;
        lastTargetZ = targetZ;
        lastReplantTick = ctx.elapsedTicks();
        planted = true;
        replants++;
        ctx.steerTo(targetX, ctx.playerY(), targetZ, SPEED);
    }

    private static double targetX(EventContext ctx) {
        return ctx.playerX() + ctx.playerFacingX() * BLOCK_DISTANCE;
    }

    private static double targetZ(EventContext ctx) {
        return ctx.playerZ() + ctx.playerFacingZ() * BLOCK_DISTANCE;
    }

    /** Exposed for tests and for tuning in play. */
    public int replants() {
        return replants;
    }
}
