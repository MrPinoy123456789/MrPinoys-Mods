package chatdonkey.core;

/**
 * Gets in your face and stays there, lecturing (SPEC.md section 4).
 *
 * <p>This is the old Lecture and Clingy events merged. They were two behaviors
 * doing the same thing at different distances -- follow the player and talk --
 * and running as separate events meant the pool spent two slots on one idea.
 * Combined, the clinging is what makes the lecture inescapable rather than a
 * second event that happens to also follow you.
 *
 * <p>So it now closes to <em>nothing</em> rather than a polite 1.5 blocks, and
 * if the player somehow gets clear it teleports straight onto them with a line.
 * The teleport is deliberately rate-limited: reappearing the instant you escape
 * reads as a bug, while letting you <em>almost</em> get away and then popping
 * back is the joke.
 */
public final class LectureBehavior extends AbstractBehavior {

    /** Zero: it is standing in your personal space and it is staying there. */
    public static final double FOLLOW_DISTANCE = 0.0;

    /** Beyond this it stops strolling and hurries. */
    public static final double SPRINT_DISTANCE = 6.0;

    /** Get this far and it gives up on walking entirely. */
    public static final double TELEPORT_DISTANCE = 10.0;

    /** A beat between teleports, so escaping feels briefly possible. */
    public static final int TELEPORT_COOLDOWN_TICKS = 40;

    private static final double WALK_SPEED = 1.25;
    private static final double CATCH_UP_SPEED = 1.7;

    /** Re-path this often. Every tick fights vanilla's wander goals for nothing. */
    public static final int STEER_INTERVAL_TICKS = 10;

    private final RateLimit teleports = new RateLimit(TELEPORT_COOLDOWN_TICKS);

    private int teleportCount;

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
        int elapsed = ctx.elapsedTicks();
        double distance = ctx.distanceToPlayer();

        // Escaped on foot: appear on top of them and say something about it.
        if (distance > TELEPORT_DISTANCE && teleports.allow(elapsed) && elapsed > 0) {
            teleportCount++;
            ctx.teleportOntoPlayer();
            say(ctx, "teleport");
            return;
        }

        if (elapsed % STEER_INTERVAL_TICKS == 0 && distance > FOLLOW_DISTANCE) {
            ctx.steerTowardPlayer(FOLLOW_DISTANCE,
                    distance > SPRINT_DISTANCE ? CATCH_UP_SPEED : WALK_SPEED);
        }
    }

    /** Exposed for tests and tuning. */
    public int teleports() {
        return teleportCount;
    }
}
