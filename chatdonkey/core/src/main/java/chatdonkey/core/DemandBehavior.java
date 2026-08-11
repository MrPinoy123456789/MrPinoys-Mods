package chatdonkey.core;

/**
 * Follows the player demanding a specific item, and leaves early and happy if it
 * gets one (SPEC.md section 4).
 *
 * <p>This is the Food Critic generalised. That event was the strongest of the
 * seven precisely because it is the only one with an exit the player can
 * <em>solve</em> — and because bringing the fancy version pays a diamond, which
 * turns an interruption into a small opportunity. Both of those are properties of
 * the shape, not of carrots, so the shape is now the thing and the carrot is one
 * configuration of it.
 *
 * <p>Every demand event is therefore data: an id, a duration, a weight, an item
 * it wants, and a fancier item it would rather have. Adding an eighth demand is
 * an {@code events.json} entry and some lines — no code at all.
 */
public final class DemandBehavior extends AbstractBehavior {

    /** Close enough to be obviously begging. */
    public static final double FOLLOW_DISTANCE = 2.0;

    private static final double WALK_SPEED = 1.3;
    private static final double CATCH_UP_SPEED = 1.75;
    private static final double SPRINT_DISTANCE = 6.0;

    public static final int STEER_INTERVAL_TICKS = 10;

    private final String id;
    private final Demand demand;
    private final int minSeconds;
    private final int maxSeconds;

    public DemandBehavior(String id, Demand demand, int minSeconds, int maxSeconds) {
        this.id = id;
        this.demand = demand;
        this.minSeconds = minSeconds;
        this.maxSeconds = maxSeconds;
    }

    @Override
    public String id() {
        return id;
    }

    public Demand demand() {
        return demand;
    }

    @Override
    public int minDurationSeconds() {
        return minSeconds;
    }

    @Override
    public int maxDurationSeconds() {
        return maxSeconds;
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

    /** Given what it asked for: leave early and happy. */
    @Override
    public boolean wantsEarlyEnd(EventContext ctx) {
        return ctx.fedOffering() != null;
    }

    @Override
    public boolean canBeSatisfied() {
        return demand.exists();
    }

    /** The fancy version deserves its own reaction, if the operator wrote one. */
    @Override
    protected String exitPool(EventContext ctx, EndReason reason) {
        if (reason == EndReason.SATISFIED
                && ctx.fedOffering() == Offering.PREMIUM
                && ctx.hitCount() < GiftTable.HITS_FOR_GRUDGE
                && ctx.lines().has(LinePools.key(id(), "exit_golden"))) {
            return "exit_golden";
        }
        return super.exitPool(ctx, reason);
    }
}
