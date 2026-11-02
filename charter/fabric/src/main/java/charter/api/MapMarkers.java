package charter.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Markers on the players' kingdom map (supply drops, events, meeting points). Players with the Charter Maps client
 * mod see each one on Xaero's maps with its label and colour; nobody else sees anything. Safe to call from any thread.
 *
 * <pre>{@code
 * MapMarkers.add(new MapMarkers.Marker("drop_1", "supply_drop", "Supply drop", "minecraft:overworld",
 *         120, 70, -340, 0xFFAA00, "chest", System.currentTimeMillis() + 15 * 60_000));
 * MapMarkers.remove("drop_1");
 * }</pre>
 *
 * <p>Markers live in memory only: a restart forgets them, which suits things that come and go. A marker past its expiry
 * is dropped by the server within a second and by the client at the same moment.
 */
public final class MapMarkers {

    /** Most markers at once; {@link #add} refuses a new id past this. */
    public static final int MAX_MARKERS = 256;
    /** Longest id, kind, icon and dimension, in characters. */
    public static final int MAX_ID = 64;
    /** Longest label, in characters. */
    public static final int MAX_LABEL = 128;

    private static final Map<String, Marker> MARKERS = new LinkedHashMap<>();
    private static long version;

    private MapMarkers() {
    }

    /**
     * One marker.
     *
     * @param id        unique; adding the same id again replaces the marker
     * @param kind      what it is, for example {@code supply_drop}; free text, shown nowhere by itself
     * @param label     what players read beside it
     * @param dimension a dimension id such as {@code minecraft:overworld}
     * @param colour    0xRRGGBB
     * @param icon      an icon name the client may know, or empty
     * @param expiresAt wall clock milliseconds it disappears at, 0 for never
     */
    public record Marker(String id, String kind, String label, String dimension, int x, int y, int z, int colour,
                         String icon, long expiresAt) {
        public Marker {
            kind = kind == null ? "" : kind;
            label = label == null ? "" : label;
            icon = icon == null ? "" : icon;
            colour &= 0xFFFFFF;
        }

        public boolean expired(long now) {
            return expiresAt != 0 && now >= expiresAt;
        }
    }

    /**
     * Adds a marker, or replaces the one with the same id. Refused (false) when the id is blank or too long, the label
     * or another field is too long, the dimension is blank, or {@value #MAX_MARKERS} markers already stand.
     */
    public static synchronized boolean add(Marker marker) {
        if (marker == null || marker.id() == null || marker.id().isBlank() || marker.id().length() > MAX_ID) return false;
        if (marker.dimension() == null || marker.dimension().isBlank() || marker.dimension().length() > MAX_ID) return false;
        if (marker.kind().length() > MAX_ID || marker.icon().length() > MAX_ID || marker.label().length() > MAX_LABEL) return false;
        if (!MARKERS.containsKey(marker.id()) && MARKERS.size() >= MAX_MARKERS) return false;
        MARKERS.put(marker.id(), marker);
        version++;
        return true;
    }

    /** Removes the marker with this id. False when there was none. */
    public static synchronized boolean remove(String id) {
        if (MARKERS.remove(id) == null) return false;
        version++;
        return true;
    }

    /** The marker with this id, if it stands. */
    public static synchronized Optional<Marker> get(String id) {
        return Optional.ofNullable(MARKERS.get(id));
    }

    /** Every marker, oldest first. */
    public static synchronized List<Marker> list() {
        return Collections.unmodifiableList(new ArrayList<>(MARKERS.values()));
    }

    /** A number that rises whenever a marker is added, replaced or removed. */
    public static synchronized long version() {
        return version;
    }

    /** Drops every marker that has expired by {@code now}. Called by Charter; returns how many went. */
    public static synchronized int prune(long now) {
        int before = MARKERS.size();
        MARKERS.values().removeIf(m -> m.expired(now));
        int gone = before - MARKERS.size();
        if (gone > 0) version++;
        return gone;
    }

    /** Forgets every marker, when a world stops. Called by Charter. */
    public static synchronized void clear() {
        if (MARKERS.isEmpty()) return;
        MARKERS.clear();
        version++;
    }
}
