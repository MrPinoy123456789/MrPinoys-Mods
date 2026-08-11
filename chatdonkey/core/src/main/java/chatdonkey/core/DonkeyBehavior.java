package chatdonkey.core;

/**
 * One event's script -- one verb each (SPEC.md section 4).
 *
 * <p>M1 implements {@link LectureBehavior} only. The remaining five are M3.
 */
public interface DonkeyBehavior {

    /** The id used for config keys and {@code lines.json} pool names. */
    String id();

    /** Inclusive lower bound of this behavior's duration, in seconds. */
    int minDurationSeconds();

    /** Inclusive upper bound of this behavior's duration, in seconds. */
    int maxDurationSeconds();

    /** Spawn positioning and the opening line. */
    void start(EventContext ctx);

    /** Called every server tick while the event is active. */
    void tick(EventContext ctx);

    /** True when the behavior's own exit condition is met, e.g. the Food Critic was fed. */
    default boolean wantsEarlyEnd(EventContext ctx) {
        return false;
    }

    /**
     * Whether feeding this donkey a carrot means anything.
     *
     * <p>Only the Food Critic says yes. For every other behavior a carrot is
     * just a taming attempt, which SPEC.md section 10 wants refused with a line
     * rather than quietly accepted.
     */
    default boolean wantsTreats() {
        return false;
    }

    /** The exit line. The gift and despawn are the runner's job, not the behavior's. */
    void end(EventContext ctx, EndReason reason);
}
