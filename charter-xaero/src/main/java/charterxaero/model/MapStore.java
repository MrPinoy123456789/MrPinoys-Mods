package charterxaero.model;

import charterxaero.wire.MapState;
import charterxaero.wire.MapWire;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What this client knows of the server's kingdom map, and the index Xaero's highlighters read. Messages are applied on
 * the game thread; the state and index are swapped whole (volatile), so a map thread reading the old index while a new
 * one is built is safe. Plain JDK, unit tested.
 */
public final class MapStore {

    /** Told after every change, on the thread that made it, with the index before and after. */
    public interface Listener {
        void changed(KingdomIndex before, KingdomIndex after, MapState state);
    }

    /** What happened to a received message. */
    public enum Outcome { APPLIED, UNCHANGED, OUT_OF_ORDER, BAD }

    private volatile MapState state = MapState.EMPTY;
    private volatile KingdomIndex index = KingdomIndex.EMPTY;
    private volatile boolean markerChunks;
    private volatile String lastError = "";
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public MapState state() {
        return state;
    }

    public KingdomIndex index() {
        return index;
    }

    /** The reason the last bad message was dropped, or empty. */
    public String lastError() {
        return lastError;
    }

    public void listen(Listener listener) {
        listeners.add(listener);
    }

    /** Whether markers are drawn as highlighted chunks too (when no Minimap shows them as waypoints). */
    public void markerChunks(boolean on, long now) {
        if (markerChunks == on) return;
        markerChunks = on;
        reindex(state, now);
    }

    /**
     * Reads and applies one message's bytes at this client's time {@code now}. A malformed message, or a delta that
     * does not follow on from what is held, changes nothing; the next snapshot (on the next join) puts things right.
     */
    public synchronized Outcome receive(byte[] bytes, long now) {
        MapWire.Message message;
        try {
            message = MapWire.read(bytes);
        } catch (MapWire.BadMessage bad) {
            lastError = bad.getMessage();
            return Outcome.BAD;
        }
        if (!state.accepts(message)) {
            lastError = "a delta arrived out of order";
            return Outcome.OUT_OF_ORDER;
        }
        MapState next = state.apply(message, now);
        if (next.equals(state)) {
            state = next;
            return Outcome.UNCHANGED;
        }
        reindex(next, now);
        return Outcome.APPLIED;
    }

    /** Drops expired markers. Cheap when none expired. */
    public synchronized void prune(long now) {
        MapState next = state.prune(now);
        if (next != state) reindex(next, now);
    }

    /** Forgets everything, on leaving a server. */
    public synchronized void clear() {
        lastError = "";
        reindex(MapState.EMPTY, 0);
    }

    private synchronized void reindex(MapState next, long now) {
        KingdomIndex before = index;
        KingdomIndex after = next.kingdoms().isEmpty() && next.markers().isEmpty()
                ? KingdomIndex.EMPTY : KingdomIndex.of(next, markerChunks, now);
        state = next;
        index = after;
        for (Listener l : listeners) l.changed(before, after, next);
    }
}
