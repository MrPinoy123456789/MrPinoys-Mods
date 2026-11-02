package charter.mc.map;

import charter.api.MapMarkers;
import charter.mapwire.MapModel;
import charter.mapwire.MapWire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the server last sent to map players, and the delta that brings them to the current map. Kingdoms are compared
 * by their encoded bytes, so anything that would show differently (a claim, a keep move, a Nexus set down, a rename, a
 * new tag colour) sends that kingdom again, and nothing else does. Markers are compared as server records, so their
 * time left (which shrinks every tick) never counts as a change. No Minecraft types.
 */
public final class MapLedger {

    private long seq;
    private final Map<UUID, byte[]> kingdoms = new LinkedHashMap<>();
    private final Map<String, MapMarkers.Marker> markers = new LinkedHashMap<>();

    /** The sequence number of the map players now hold. */
    public long seq() {
        return seq;
    }

    /** The whole current map for a player who has nothing yet, at the ledger's sequence number. */
    public MapModel.Snapshot snapshot(List<MapModel.Kingdom> current, List<MapMarkers.Marker> currentMarkers, long now) {
        List<MapModel.Marker> wire = new ArrayList<>();
        for (MapMarkers.Marker m : currentMarkers) {
            if (!m.expired(now)) wire.add(toWire(m, now));
        }
        return new MapModel.Snapshot(seq, current, wire);
    }

    /**
     * Records the current map as sent and returns what changed since the last call, or null when nothing did (then the
     * sequence number stays). The first call after a fresh ledger reports every kingdom and marker.
     */
    public MapModel.Delta advance(List<MapModel.Kingdom> current, List<MapMarkers.Marker> currentMarkers, long now) {
        List<MapModel.Kingdom> changed = new ArrayList<>();
        Map<UUID, byte[]> nowKingdoms = new LinkedHashMap<>();
        for (MapModel.Kingdom k : current) {
            byte[] bytes = MapWire.kingdomBytes(k);
            nowKingdoms.put(k.id(), bytes);
            byte[] before = kingdoms.get(k.id());
            if (before == null || !Arrays.equals(before, bytes)) changed.add(k);
        }
        List<UUID> removed = new ArrayList<>();
        for (UUID id : kingdoms.keySet()) {
            if (!nowKingdoms.containsKey(id)) removed.add(id);
        }

        Map<String, MapMarkers.Marker> nowMarkers = new LinkedHashMap<>();
        for (MapMarkers.Marker m : currentMarkers) {
            if (!m.expired(now)) nowMarkers.put(m.id(), m);
        }
        List<MapModel.Marker> placed = new ArrayList<>();
        for (MapMarkers.Marker m : nowMarkers.values()) {
            if (!m.equals(markers.get(m.id()))) placed.add(toWire(m, now));
        }
        List<String> gone = new ArrayList<>();
        for (String id : markers.keySet()) {
            if (!nowMarkers.containsKey(id)) gone.add(id);
        }

        if (changed.isEmpty() && removed.isEmpty() && placed.isEmpty() && gone.isEmpty()) return null;
        kingdoms.clear();
        kingdoms.putAll(nowKingdoms);
        markers.clear();
        markers.putAll(nowMarkers);
        long from = seq;
        seq++;
        return new MapModel.Delta(from, seq, changed, removed, placed, gone);
    }

    /** Forgets what was sent, when a world stops. The sequence keeps counting, so it never repeats within a run. */
    public void reset() {
        kingdoms.clear();
        markers.clear();
    }

    /** The wire form of a server marker at {@code now}: its expiry becomes the time it has left. */
    static MapModel.Marker toWire(MapMarkers.Marker m, long now) {
        long ttl = m.expiresAt() == 0 ? 0 : Math.max(1, m.expiresAt() - now);
        return new MapModel.Marker(m.id(), m.kind(), m.label(), m.dimension(), m.x(), m.y(), m.z(), m.colour(), m.icon(), ttl);
    }
}
