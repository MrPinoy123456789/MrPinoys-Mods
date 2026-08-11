package chatdonkey.core;

/**
 * The screen event (SPEC.md section 4): the donkey opens his coat, which is full
 * of burrs, and demands the player pick them out.
 *
 * <p>The first behavior that occupies the player's <em>hands</em> rather than
 * their position, and the only one that can hold their view of the world. That
 * is allowed to get them killed (section 1, revised) -- but see
 * {@link EventContext#mayHoldScreen()}, which is how an operator dials it back.
 *
 * <p>Three loops run at once, and their interaction is the whole event:
 * <ul>
 *   <li>the player pulls burrs out;</li>
 *   <li>the donkey keeps <em>finding more</em>, on a timer;</li>
 *   <li>if the player closes the coat, the donkey reopens it, on a slower timer.</li>
 * </ul>
 *
 * <p>Closing is always allowed. The annoyance is the nagging loop, not being
 * trapped, and a player who wants to fight can close it and fight -- they will
 * just have to keep closing it.
 */
public final class BurrsBehavior extends AbstractBehavior {

    /** Burrs placed when the coat first opens. */
    public static final int MIN_BURRS = 5;
    public static final int MAX_BURRS = 9;

    /** How long after the player closes the coat before he opens it again. */
    public static final int REOPEN_TICKS = 5 * 20;

    /** How often he finds another one while the coat is open. */
    public static final int REFIND_TICKS = 8 * 20;

    /** Stay close, or the container closes itself on distance. */
    private static final double FOLLOW_DISTANCE = 2.0;
    private static final double SPEED = 1.4;
    private static final int STEER_INTERVAL_TICKS = 10;

    private final RateLimit reopens = new RateLimit(REOPEN_TICKS);
    private final RateLimit refinds = new RateLimit(REFIND_TICKS);

    private boolean seeded;
    private boolean everOpened;
    private int burrsFound;

    @Override
    public String id() {
        return "burrs";
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
        return 7 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 10 * 20;
    }

    /** Clearing the coat is a real solution, so this event can end SATISFIED. */
    @Override
    public boolean canBeSatisfied() {
        return true;
    }

    @Override
    protected void onStart(EventContext ctx) {
        Coat coat = ctx.coat();
        coat.seed(MIN_BURRS + ctx.random().nextInt(MAX_BURRS - MIN_BURRS + 1));
        seeded = true;
        tryOpen(ctx, 0);

        // Prime both timers off the opening moment. Without this their first
        // allowance is still unspent, so he would find a burr and re-open the
        // coat on the very next tick instead of after the beat -- which reads as
        // a stuck loop rather than a donkey being annoying on a rhythm.
        reopens.allow(0);
        refinds.allow(0);
    }

    @Override
    protected void onTick(EventContext ctx) {
        int elapsed = ctx.elapsedTicks();
        Coat coat = ctx.coat();

        if (elapsed % STEER_INTERVAL_TICKS == 0 && ctx.distanceToPlayer() > FOLLOW_DISTANCE) {
            ctx.steerTowardPlayer(FOLLOW_DISTANCE, SPEED);
        }

        if (coat.isOpen()) {
            // He keeps finding more. This is the joke, and the difficulty knob
            // a Twitch bridge would eventually turn (SPEC.md section 11).
            if (refinds.allow(elapsed) && elapsed > 0 && coat.addOne()) {
                burrsFound++;
                say(ctx, "refind");
            }
            return;
        }

        // Closed. Nag, and open it again.
        if (reopens.allow(elapsed) && elapsed > 0) {
            if (tryOpen(ctx, elapsed)) {
                say(ctx, "reopen");
            }
        }
    }

    /**
     * The event's own exit condition: the coat is clean.
     *
     * <p>Guarded on {@code seeded} so an event whose coat could not be prepared
     * does not report itself finished on its very first tick.
     */
    @Override
    public boolean wantsEarlyEnd(EventContext ctx) {
        return seeded && everOpened && ctx.coat().remaining() == 0;
    }

    private boolean tryOpen(EventContext ctx, int elapsed) {
        // donkeyCanKill=false: hold off while the player is fighting for their
        // life. The event carries on -- he just stops doing it at knife-point.
        if (!ctx.mayHoldScreen()) {
            return false;
        }
        ctx.coat().open();
        everOpened = true;
        return true;
    }

    /** Exposed for tests and tuning. */
    public int burrsFound() {
        return burrsFound;
    }
}
