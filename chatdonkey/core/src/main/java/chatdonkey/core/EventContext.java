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
     * What the player has fed the donkey this event, or {@code null} if nothing.
     * Only the Food Critic cares (SPEC.md section 4).
     */
    Treat fedTreat();

    /** Sends a chat line to the target player and anyone within 16 blocks. */
    void say(String line);

    /**
     * Paths the donkey toward the player, stopping once inside
     * {@code stopDistance} blocks. A no-op when already close enough.
     */
    void steerTowardPlayer(double stopDistance, double speed);

    /** Paths the donkey to a specific spot -- Roadblock, Serenade, False Alarm. */
    void steerTo(double x, double y, double z, double speed);

    /** Drops the donkey directly on the player, particles and all -- Clingy. */
    void teleportOntoPlayer();

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

    /** The configured line pools, including any per-event override. */
    LinePools lines();

    /** The event's RNG. Seeded in tests, plain {@code Random} in play. */
    Random random();
}
