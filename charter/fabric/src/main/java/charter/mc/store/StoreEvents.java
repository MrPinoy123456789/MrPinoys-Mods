package charter.mc.store;

import charter.history.HistoryEvent;

/** The store's own history records (PLAN.md §8.4). */
public final class StoreEvents {

    private StoreEvents() {
    }

    /** The actor every repair is recorded under. */
    public static final String REPAIR_ACTOR = "repair";

    /**
     * Something on load (or an admin repair) did not add up and was put right:
     * {@code kind} is a fixed key such as {@code grant_dropped}, {@code detail} says which record.
     */
    public record Repaired(long at, String kind, String detail) implements HistoryEvent {
    }
}
