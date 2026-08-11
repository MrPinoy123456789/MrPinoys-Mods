package chatdonkey.core;

/**
 * Why a trigger check did or did not fire. Every rejection is a distinct value
 * rather than a bare {@code false} so the reason is testable and loggable --
 * "no donkey ever appears" is otherwise very hard to diagnose in play.
 */
public enum TriggerDecision {

    /** {@code enabled} is false in settings.json. */
    DISABLED,
    /** Less than {@code checkIntervalSeconds} since this player's last check. */
    NOT_TIME_YET,
    /** Already mid-event -- one donkey per player. */
    ALREADY_IN_EVENT,
    /** Session younger than {@code minSessionMinutesBeforeFirst}. */
    SESSION_TOO_YOUNG,
    /** No meaningful movement within {@code requireRecentActivitySeconds}. */
    INACTIVE,
    /** Within {@code cooldownMinutesPerPlayer} of the last event ending. */
    ON_COOLDOWN,
    /** The player bought immunity with {@code /donkey grace}. */
    IN_GRACE_PERIOD,
    /** {@code maxSimultaneousEventsServerWide} events are already running. */
    SERVER_BUSY,
    /** Everything passed, but the {@code chancePerCheck} roll came up short. */
    ROLL_FAILED,
    /** Spawn the donkey. */
    TRIGGER;

    public boolean fires() {
        return this == TRIGGER;
    }

    /** True when the check actually ran, i.e. the per-player interval should reset. */
    public boolean consumedCheck() {
        return this != DISABLED && this != NOT_TIME_YET;
    }
}
