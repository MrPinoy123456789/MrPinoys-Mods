package chatdonkey.core;

/**
 * Follows at zero distance; if the player gets more than ten blocks away it
 * teleports directly on top of them, with a line (SPEC.md section 4).
 *
 * <p>The teleport is the joke, so it is rate-limited rather than instant -- a
 * donkey that reappears the very tick you escape reads as a bug, while one that
 * lets you *almost* get away and then pops back is funny. {@link
 * #TELEPORT_COOLDOWN_TICKS} is that beat.
 *
 * <p>Teleporting is also the only place any behavior moves the donkey directly
 * rather than steering it, which is why it is masked with particles (SPEC.md
 * section 6's fallback ladder, used here on purpose rather than as a
 * workaround).
 */
public final class ClingyBehavior extends AbstractBehavior {

    /** Zero distance: it is standing in your personal space and staying there. */
    public static final double FOLLOW_DISTANCE = 0.0;

    /** Get this far and it gives up on walking. */
    public static final double TELEPORT_DISTANCE = 10.0;

    /** A beat between teleports, so escaping feels briefly possible. */
    public static final int TELEPORT_COOLDOWN_TICKS = 40;

    private static final int STEER_INTERVAL_TICKS = 10;
    private static final double SPEED = 1.25;

    private boolean hasTeleported;
    private int lastTeleportTick;
    private int teleports;

    @Override
    public String id() {
        return "clingy";
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
        return 6 * 20;
    }

    @Override
    protected int lineGapMaxTicks() {
        return 8 * 20;
    }

    @Override
    protected void onTick(EventContext ctx) {
        int elapsed = ctx.elapsedTicks();

        if (ctx.distanceToPlayer() > TELEPORT_DISTANCE && canTeleport(elapsed)) {
            hasTeleported = true;
            lastTeleportTick = elapsed;
            teleports++;
            ctx.teleportOntoPlayer();
            say(ctx, "teleport");
            return;
        }

        if (elapsed % STEER_INTERVAL_TICKS == 0) {
            ctx.steerTowardPlayer(FOLLOW_DISTANCE, SPEED);
        }
    }

    boolean canTeleport(int elapsedTicks) {
        return !hasTeleported || elapsedTicks - lastTeleportTick >= TELEPORT_COOLDOWN_TICKS;
    }

    /** Exposed for tests and for tuning in play. */
    public int teleports() {
        return teleports;
    }
}
