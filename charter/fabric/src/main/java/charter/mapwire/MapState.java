package charter.mapwire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a receiver knows of the map: the last snapshot with every delta since applied. Immutable; {@link #apply} returns
 * a new state, so a map renderer on another thread can keep reading the old one.
 *
 * @param seq      the sequence number of the last message applied, -1 before any
 * @param kingdoms by id, in the order they arrived
 * @param markers  by id, each with its expiry on the receiver's own clock
 */
public record MapState(long seq, Map<UUID, MapModel.Kingdom> kingdoms, Map<String, Placed> markers) {

    /** Nothing received yet. */
    public static final MapState EMPTY = new MapState(-1, Map.of(), Map.of());

    /** A marker and the receiver's clock time it expires at, 0 for never. */
    public record Placed(MapModel.Marker marker, long expiresAt) {
        public boolean expired(long now) {
            return expiresAt != 0 && now >= expiresAt;
        }
    }

    public MapState {
        kingdoms = Collections.unmodifiableMap(new LinkedHashMap<>(kingdoms));
        markers = Collections.unmodifiableMap(new LinkedHashMap<>(markers));
    }

    /** Whether a delta follows on from this state. A snapshot always applies. */
    public boolean accepts(MapWire.Message message) {
        return !(message instanceof MapModel.Delta d) || d.fromSeq() == seq;
    }

    /**
     * This state with the message applied at the receiver's time {@code now}. A snapshot replaces everything; a delta
     * that does not follow on from this state (see {@link #accepts}) is ignored and this state is returned.
     */
    public MapState apply(MapWire.Message message, long now) {
        if (message instanceof MapModel.Snapshot s) {
            Map<UUID, MapModel.Kingdom> ks = new LinkedHashMap<>();
            for (MapModel.Kingdom k : s.kingdoms()) ks.put(k.id(), k);
            Map<String, Placed> ms = new LinkedHashMap<>();
            for (MapModel.Marker m : s.markers()) ms.put(m.id(), place(m, now));
            return new MapState(s.seq(), ks, ms);
        }
        MapModel.Delta d = (MapModel.Delta) message;
        if (d.fromSeq() != seq) return this;
        Map<UUID, MapModel.Kingdom> ks = new LinkedHashMap<>(kingdoms);
        for (UUID gone : d.removedKingdoms()) ks.remove(gone);
        for (MapModel.Kingdom k : d.kingdoms()) ks.put(k.id(), k);
        Map<String, Placed> ms = new LinkedHashMap<>(markers);
        for (String gone : d.removedMarkers()) ms.remove(gone);
        for (MapModel.Marker m : d.markers()) ms.put(m.id(), place(m, now));
        return new MapState(d.toSeq(), ks, ms);
    }

    private static Placed place(MapModel.Marker m, long now) {
        return new Placed(m, m.ttlMillis() <= 0 ? 0 : now + m.ttlMillis());
    }

    /** This state without the markers that have expired by {@code now}; this same state when none have. */
    public MapState prune(long now) {
        boolean any = false;
        for (Placed p : markers.values()) {
            if (p.expired(now)) {
                any = true;
                break;
            }
        }
        if (!any) return this;
        Map<String, Placed> ms = new LinkedHashMap<>();
        markers.forEach((id, p) -> {
            if (!p.expired(now)) ms.put(id, p);
        });
        return new MapState(seq, kingdoms, ms);
    }

    /** The markers still showing at {@code now}. */
    public List<MapModel.Marker> liveMarkers(long now) {
        List<MapModel.Marker> out = new ArrayList<>();
        for (Placed p : markers.values()) {
            if (!p.expired(now)) out.add(p.marker());
        }
        return out;
    }
}
