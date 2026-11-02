package charterxaero.model;

import charterxaero.wire.MapModel;
import charterxaero.wire.MapState;
import charterxaero.wire.Runs;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The map as Xaero's highlighters ask about it: who holds a chunk, which of its edges are a border, and a hash per map
 * region (32 by 32 chunks) that changes whenever anything drawn in it changes. Built once per received message and
 * never changed after, so Xaero's map threads can read it while the game thread builds the next. Plain JDK.
 */
public final class KingdomIndex {

    /** Chunks per side of one Xaero map region (512 blocks). */
    public static final int REGION_CHUNKS = 32;

    public static final KingdomIndex EMPTY = new KingdomIndex(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0);

    /** One held chunk: the kingdom (an index into {@link #kingdoms()}), the kind of land, and whether it is a keep's core. */
    public record Cell(int kingdom, MapModel.LandKind kind, boolean core) {
    }

    private final List<MapModel.Kingdom> kingdoms;
    private final Map<String, Map<Long, Cell>> cells;
    private final Map<String, Map<Long, MapModel.Marker>> markerCells;
    private final Map<String, Map<Long, Integer>> regionHashes;
    private final Map<String, Set<Long>> regionsDrawn;
    private final int chunkCount;

    private KingdomIndex(List<MapModel.Kingdom> kingdoms, Map<String, Map<Long, Cell>> cells,
                         Map<String, Map<Long, MapModel.Marker>> markerCells, Map<String, Map<Long, Integer>> regionHashes,
                         Map<String, Set<Long>> regionsDrawn, int chunkCount) {
        this.kingdoms = kingdoms;
        this.cells = cells;
        this.markerCells = markerCells;
        this.regionHashes = regionHashes;
        this.regionsDrawn = regionsDrawn;
        this.chunkCount = chunkCount;
    }

    /**
     * The index of a received map at the receiver's time {@code now}.
     *
     * @param markerChunks whether live markers are drawn as a highlighted chunk too (when there is no Minimap to show
     *                     them as waypoints)
     */
    public static KingdomIndex of(MapState state, boolean markerChunks, long now) {
        List<MapModel.Kingdom> ks = List.copyOf(state.kingdoms().values());
        Map<String, Map<Long, Cell>> cells = new HashMap<>();
        Map<String, Map<Long, Integer>> hashes = new HashMap<>();
        Map<String, Set<Long>> drawn = new HashMap<>();
        int count = 0;
        for (int i = 0; i < ks.size(); i++) {
            MapModel.Kingdom k = ks.get(i);
            long core = Long.MIN_VALUE;
            String coreDimension = null;
            if (k.keep() != null) {
                core = Runs.pack(k.keep().coreChunkX(), k.keep().coreChunkZ());
                coreDimension = k.keep().dimension();
            }
            for (MapModel.Land land : k.lands()) {
                Map<Long, Cell> dim = cells.computeIfAbsent(land.dimension(), d -> new HashMap<>());
                for (long c : Runs.expand(land.runs())) {
                    boolean isCore = c == core && land.dimension().equals(coreDimension);
                    if (dim.put(c, new Cell(i, land.kind(), isCore)) == null) count++;
                }
            }
        }
        // Hash every drawn chunk into each region whose texture it can change: its own, and a neighbour's when it sits
        // on that region's edge (its owner decides the neighbour's border).
        cells.forEach((dimension, map) -> map.forEach((c, cell) -> {
            MapModel.Kingdom k = ks.get(cell.kingdom());
            int h = mixHash(Runs.x(c), Runs.z(c), k.colour(), k.id().hashCode(), cell.kind().ordinal() * 2 + (cell.core() ? 1 : 0));
            spread(hashes, drawn, dimension, Runs.x(c), Runs.z(c), h);
        }));
        Map<String, Map<Long, MapModel.Marker>> markers = new HashMap<>();
        if (markerChunks) {
            for (MapModel.Marker m : state.liveMarkers(now)) {
                int cx = Math.floorDiv(m.x(), 16);
                int cz = Math.floorDiv(m.z(), 16);
                markers.computeIfAbsent(m.dimension(), d -> new HashMap<>()).put(Runs.pack(cx, cz), m);
                spread(hashes, drawn, m.dimension(), cx, cz, mixHash(cx, cz, m.colour(), m.id().hashCode(), 99));
            }
        }
        return new KingdomIndex(ks, freeze(cells), freeze(markers), freeze(hashes), freezeSets(drawn), count);
    }

    private static void spread(Map<String, Map<Long, Integer>> hashes, Map<String, Set<Long>> drawn, String dimension,
                               int cx, int cz, int h) {
        Map<Long, Integer> dim = hashes.computeIfAbsent(dimension, d -> new HashMap<>());
        int r0x = Math.floorDiv(cx - 1, REGION_CHUNKS), r1x = Math.floorDiv(cx + 1, REGION_CHUNKS);
        int r0z = Math.floorDiv(cz - 1, REGION_CHUNKS), r1z = Math.floorDiv(cz + 1, REGION_CHUNKS);
        for (int rx = r0x; rx <= r1x; rx++) {
            for (int rz = r0z; rz <= r1z; rz++) dim.merge(Runs.pack(rx, rz), h, Integer::sum);
        }
        drawn.computeIfAbsent(dimension, d -> new HashSet<>())
                .add(Runs.pack(Math.floorDiv(cx, REGION_CHUNKS), Math.floorDiv(cz, REGION_CHUNKS)));
    }

    /** A well mixed hash of a drawn chunk; summed per region, so the order chunks come in never matters. */
    static int mixHash(int x, int z, int colour, int id, int kind) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ colour * 0x165667B19E3779F9L ^ id * 0xD6E8FEB86659FD93L ^ kind;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (int) h;
    }

    private static <V> Map<String, Map<Long, V>> freeze(Map<String, Map<Long, V>> m) {
        Map<String, Map<Long, V>> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, Collections.unmodifiableMap(v)));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Set<Long>> freezeSets(Map<String, Set<Long>> m) {
        Map<String, Set<Long>> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, Collections.unmodifiableSet(v)));
        return Collections.unmodifiableMap(out);
    }

    // ---- asking ----

    public List<MapModel.Kingdom> kingdoms() {
        return kingdoms;
    }

    /** How many chunks are held, in every dimension. */
    public int chunkCount() {
        return chunkCount;
    }

    /** The held chunk at these chunk coordinates, or null. */
    public Cell at(String dimension, int chunkX, int chunkZ) {
        Map<Long, Cell> dim = cells.get(dimension);
        return dim == null ? null : dim.get(Runs.pack(chunkX, chunkZ));
    }

    /** The kingdom holding a chunk, or null. */
    public MapModel.Kingdom kingdomAt(String dimension, int chunkX, int chunkZ) {
        Cell cell = at(dimension, chunkX, chunkZ);
        return cell == null ? null : kingdoms.get(cell.kingdom());
    }

    /** The marker drawn on a chunk (only when markers are drawn as chunks), or null. */
    public MapModel.Marker markerAt(String dimension, int chunkX, int chunkZ) {
        Map<Long, MapModel.Marker> dim = markerCells.get(dimension);
        return dim == null ? null : dim.get(Runs.pack(chunkX, chunkZ));
    }

    /** Whether anything is drawn on this chunk. */
    public boolean drawn(String dimension, int chunkX, int chunkZ) {
        return markerAt(dimension, chunkX, chunkZ) != null || at(dimension, chunkX, chunkZ) != null;
    }

    /** The border edges of a held chunk ({@link Borders} bits), 0 for a chunk nobody holds. */
    public int sides(String dimension, int chunkX, int chunkZ) {
        Map<Long, Cell> dim = cells.get(dimension);
        if (dim == null) return 0;
        Cell cell = dim.get(Runs.pack(chunkX, chunkZ));
        if (cell == null) return 0;
        return Borders.sides((x, z) -> {
            Cell n = dim.get(Runs.pack(x, z));
            return n == null ? -1 : n.kingdom();
        }, chunkX, chunkZ, cell.kingdom());
    }

    /** Whether anything is drawn inside this region (32 by 32 chunks). */
    public boolean regionDrawn(String dimension, int regionX, int regionZ) {
        Set<Long> dim = regionsDrawn.get(dimension);
        return dim != null && dim.contains(Runs.pack(regionX, regionZ));
    }

    /** A hash of everything that decides how this region is drawn, its neighbours' edge chunks included; 0 when nothing. */
    public int regionHash(String dimension, int regionX, int regionZ) {
        Map<Long, Integer> dim = regionHashes.get(dimension);
        Integer h = dim == null ? null : dim.get(Runs.pack(regionX, regionZ));
        return h == null ? 0 : (h == 0 ? 1 : h);
    }

    /**
     * The regions, by dimension (packed with {@link Runs#pack}), whose drawing differs between two indexes: where a
     * map needs to redraw, and nowhere else.
     */
    public static Map<String, Set<Long>> changedRegions(KingdomIndex before, KingdomIndex after) {
        Map<String, Set<Long>> out = new HashMap<>();
        Set<String> dims = new HashSet<>(before.regionHashes.keySet());
        dims.addAll(after.regionHashes.keySet());
        for (String dim : dims) {
            Set<Long> regions = new HashSet<>(before.regionHashes.getOrDefault(dim, Map.of()).keySet());
            regions.addAll(after.regionHashes.getOrDefault(dim, Map.of()).keySet());
            for (long r : regions) {
                if (before.regionHash(dim, Runs.x(r), Runs.z(r)) != after.regionHash(dim, Runs.x(r), Runs.z(r))) {
                    out.computeIfAbsent(dim, d -> new HashSet<>()).add(r);
                }
            }
        }
        return out;
    }
}
