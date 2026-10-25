package charter.engine;

import charter.history.HistoryEvent;
import charter.id.VoteId;

import java.util.List;

/** What the engine did with a request. */
public sealed interface Outcome {

    record Done(List<HistoryEvent> events) implements Outcome {
    }

    record Refused(Reason reason) implements Outcome {
    }

    record VoteOpened(VoteId vote) implements Outcome {
    }

    /** Preconditions met; the action may apply. Returned by {@code check}. */
    record Ok() implements Outcome {
    }

    Outcome OK = new Ok();

    static Outcome refuse(Reason reason) {
        return new Refused(reason);
    }

    default boolean done() {
        return this instanceof Done;
    }

    default boolean refused() {
        return this instanceof Refused;
    }

    /** The refusal key, or null if this is not a refusal. */
    default String reasonKey() {
        return this instanceof Refused r ? r.reason().key() : null;
    }
}
