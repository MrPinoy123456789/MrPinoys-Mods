package charter.history;

/**
 * Something that happened, recorded for the history file, the siege log and the
 * Chronicle. Each feature defines its own event records in its own package; this
 * interface is deliberately not sealed, so parallel milestones add event kinds
 * without editing a shared file. The Chronicle renders by record type.
 */
public interface HistoryEvent {
    /** Wall time of the event, in milliseconds. */
    long at();
}
