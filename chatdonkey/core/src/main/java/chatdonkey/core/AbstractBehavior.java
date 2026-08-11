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

    private int nextLineTick;

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
}
