package chatdonkey.core;

/**
 * The parts every behavior does identically: bray and announce yourself, hold
 * eye contact, fire a line on a wobbling cadence, and say the right thing on the
 * way out.
 *
 * <p>Subclasses supply only what makes them different -- how they move, and how
 * often they talk. Before this existed the six behaviors carried six copies of
 * the same line-scheduling code, which is six places to fix a cadence bug.
 */
public abstract class AbstractBehavior implements DonkeyBehavior {

    /**
     * Outrun the donkey by this much and it gives up on walking and simply
     * turns up next to you.
     *
     * <p>A donkey is faster than it was, but a player on a horse, under a speed
     * potion, or on an elytra still leaves it standing -- and an event where the
     * donkey is a dot on the horizon is not an event, it is a chat log. This is
     * the floor under every behavior: whatever you are riding, it catches up.
     *
     * <p>Set well beyond {@link LectureBehavior#TELEPORT_DISTANCE} so the
     * Lecture's own teleport stays its gag rather than being pre-empted by this.
     */
    public static final double LEASH_DISTANCE = 24.0;

    /** A beat between catch-ups, so it cannot thrash on a laggy chase. */
    public static final int LEASH_COOLDOWN_TICKS = 40;

    private final RateLimit leash = new RateLimit(LEASH_COOLDOWN_TICKS);

    private int nextLineTick;
    private int catchUps;

    /** Inclusive bounds on the gap between running lines, in ticks. */
    protected abstract int lineGapMinTicks();

    protected abstract int lineGapMaxTicks();

    /** Movement for one tick. Eye contact and lines are already handled. */
    protected abstract void onTick(EventContext ctx);

    /** Extra setup after the opening line, e.g. Roadblock's first plant. */
    protected void onStart(EventContext ctx) {
    }

    @Override
    public void start(EventContext ctx) {
        ctx.bray();
        ctx.lookAtPlayer();
        say(ctx, "open");
        nextLineTick = rollLineGap(ctx);
        onStart(ctx);
    }

    @Override
    public void tick(EventContext ctx) {
        // Every tick, and cheap: a donkey that looks away mid-performance stops
        // reading as a performance.
        ctx.lookAtPlayer();

        // Vanilla clears the chewing flag once its own eating timer lapses, so
        // the gormless open-mouthed face has to be re-asserted or it quietly
        // reverts to a normal donkey partway through the event.
        ctx.keepMouthOpen();

        // Before the behavior moves: if the player has simply gone, close the
        // gap. Silent on purpose -- a donkey that is just *there* again when you
        // thought you had lost it is funnier than one that announces itself, and
        // announcing it would tread on the Lecture teleport's joke.
        if (ctx.distanceToPlayer() > LEASH_DISTANCE
                && leash.allow(ctx.elapsedTicks())
                && ctx.teleportNearPlayer()) {
            catchUps++;
        }

        onTick(ctx);

        if (ctx.elapsedTicks() >= nextLineTick) {
            say(ctx, "during");
            nextLineTick = ctx.elapsedTicks() + rollLineGap(ctx);
        }
    }

    @Override
    public void end(EventContext ctx, EndReason reason) {
        if (reason == EndReason.ABORTED) {
            // Nobody is left to hear it -- the player logged out, died, or an
            // operator stepped in.
            return;
        }
        say(ctx, exitPool(ctx, reason));
    }

    /** Which exit pool to use. Overridden where an ending has a special case. */
    protected String exitPool(EventContext ctx, EndReason reason) {
        return reason.exitPoolSuffix(ctx.hitCount());
    }

    /**
     * Says a line from this behavior's pool for {@code moment}, falling back to
     * the shared pool of the same name. Silent if neither exists.
     */
    protected final void say(EventContext ctx, String moment) {
        String line = ctx.lines().pickFor(id(), moment, ctx.random());
        if (!line.isEmpty()) {
            ctx.say(line);
        }
    }

    private int rollLineGap(EventContext ctx) {
        int min = lineGapMinTicks();
        int max = Math.max(min, lineGapMaxTicks());
        return min + ctx.random().nextInt(max - min + 1);
    }

    /** Exposed for tests: the tick at which the next running line is due. */
    public final int nextLineTick() {
        return nextLineTick;
    }

    /** Exposed for tests and tuning: how often the donkey had to catch up. */
    public final int catchUps() {
        return catchUps;
    }
}
