package chatdonkey.core;

import java.util.Random;

/**
 * The seam between a behavior's rules and the Minecraft world (SPEC.md
 * section 4).
 *
 * <p>Deliberately expressed in primitives: a behavior can ask how far away the
 * player is and order the donkey to steer, look, bray, or speak, but it cannot
 * touch an entity. That keeps every behavior testable in {@code core} against a
 * fake context, and it is the same seam the Twitch bridge will eventually drive
 * with duration and line-pool overrides (SPEC.md section 11).
 */
public interface EventContext {

    /** Ticks since the event started. */
    int elapsedTicks();

    /** Total length of this event, rolled from the behavior's duration range. */
    int durationTicks();

    /** The donkey's per-event name, already picked from the {@code names} pool. */
    String donkeyName();

    /** Horizontal-and-vertical distance from donkey to target player, in blocks. */
    double distanceToPlayer();

    /** How many times the player has hit the donkey this event. */
    int hitCount();

    /**
     * What the player has handed over this event, or {@code null} if nothing.
     * Only demand events care (SPEC.md section 4).
     */
    Offering fedOffering();

    /** What this event wants, if it is a demand event. Never null. */
    Demand demand();

    /** Sends a chat line to the target player and anyone within 16 blocks. */
    void say(String line);

    /**
     * Paths the donkey toward the player, stopping once inside
     * {@code stopDistance} blocks. A no-op when already close enough.
     */
    void steerTowardPlayer(double stopDistance, double speed);

    /** Paths the donkey to a specific spot -- Roadblock, Serenade, False Alarm. */
    void steerTo(double x, double y, double z, double speed);

    /** Drops the donkey directly on the player, particles and all -- the Lecture teleport. */
    void teleportOntoPlayer();

    /**
     * Moves the donkey to a valid spot a few blocks from the player.
     *
     * <p>The catch-up teleport every behavior gets when the player simply
     * outruns it. Deliberately <em>near</em> rather than on top, so it stays
     * distinct from the Lecture's own teleport-onto-you move.
     *
     * @return false if nowhere suitable was found
     */
    boolean teleportNearPlayer();

    /** Where the player is, in world coordinates. */
    double playerX();

    double playerY();

    double playerZ();

    /**
     * The player's horizontal facing as a unit vector.
     *
     * <p>Horizontal only: Roadblock plants itself in front of the player's feet,
     * and looking at the sky should not send the donkey into orbit.
     */
    double playerFacingX();

    double playerFacingZ();

    /** Where the donkey is, in world coordinates. */
    double donkeyX();

    double donkeyZ();

    /** Points the donkey's head at the player. */
    void lookAtPlayer();

    /** A loud world bray -- a performance, not a confirmation (SPEC.md section 8). */
    void bray();

    /** Starts a random music disc playing from the donkey. Serenade only. */
    void startMusic();

    /**
     * Plays one note of the donkey's own accompaniment, deliberately off-key.
     *
     * @param step which note of the melody, so the caller controls the tune
     */
    void singNote(int step);

    /** Re-asserts the permanently gormless chewing face. */
    void keepMouthOpen();

    /** The donkey's chest inventory, for the Burrs event. */
    Coat coat();

    /**
     * Whether the donkey may take over the player's screen right now.
     *
     * <p>Always true on a default server: the donkey getting a player killed is
     * the feature (SPEC.md section 1). Returns false only when an operator has
     * set {@code donkeyCanKill: false} and the player has taken damage recently
     * -- the event continues either way, the donkey just stops holding their
     * view while they are fighting for their life.
     */
    boolean mayHoldScreen();

    /** The configured line pools, including any per-event override. */
    LinePools lines();

    /** The event's RNG. Seeded in tests, plain {@link Random} in play. */
    Random random();

    /** Chance that a 'during' line is pulled from the shared herald pool. */
    double heraldChance();
}
