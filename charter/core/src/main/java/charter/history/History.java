package charter.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The in-memory history, append only. The fabric side drains new entries to the
 * history file and the Chronicle; nothing is ever edited or removed here.
 */
public final class History {

    /** One recorded event, with who caused it and whether it was an admin action. */
    public record Entry(HistoryEvent event, String actor, boolean admin) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Consumer<Entry>> listeners = new ArrayList<>();

    public void record(HistoryEvent event, String actor, boolean admin) {
        Entry entry = new Entry(event, actor, admin);
        entries.add(entry);
        for (Consumer<Entry> listener : listeners) {
            listener.accept(entry);
        }
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Entries recorded from index {@code from} on, for draining to disk. */
    public List<Entry> since(int from) {
        return Collections.unmodifiableList(entries.subList(Math.min(from, entries.size()), entries.size()));
    }

    public int size() {
        return entries.size();
    }

    /** Called for every new entry, after it is recorded. */
    public void listen(Consumer<Entry> listener) {
        listeners.add(listener);
    }

    /** True if any recorded event is of this type. */
    public boolean has(Class<? extends HistoryEvent> type) {
        return entries.stream().anyMatch(e -> type.isInstance(e.event()));
    }

    /** Every recorded event of this type, oldest first. */
    public <T extends HistoryEvent> List<T> of(Class<T> type) {
        return entries.stream().map(Entry::event).filter(type::isInstance).map(type::cast).toList();
    }
}
