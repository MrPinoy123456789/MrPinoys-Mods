package charter.mc.store;

import charter.history.HistoryEvent;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a load or an admin repair put right (PLAN.md §8.4). Collected while the
 * state is built and recorded to history once the history file is listening, so
 * every repair lands in {@code history.jsonl} as an admin event by "repair".
 */
public final class Repairs {

    private final long now;
    private final List<HistoryEvent> found = new ArrayList<>();

    public Repairs(long now) {
        this.now = now;
    }

    public long now() {
        return now;
    }

    public void note(String kind, String detail) {
        found.add(new StoreEvents.Repaired(now, kind, detail));
    }

    /** Events the rules produced while repairing (land reassigned or contested). */
    public void add(List<HistoryEvent> events) {
        found.addAll(events);
    }

    public List<HistoryEvent> found() {
        return Collections.unmodifiableList(found);
    }

    public int count() {
        return (int) found.stream().filter(StoreEvents.Repaired.class::isInstance).count();
    }

    public void recordTo(CharterState state) {
        for (HistoryEvent event : found) state.history.record(event, StoreEvents.REPAIR_ACTOR, true);
    }
}
