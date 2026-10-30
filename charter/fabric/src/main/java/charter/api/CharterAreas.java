package charter.api;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.Core;
import charter.org.Organization;
import charter.state.CharterState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The public map of land held by keeps and outposts, for other mods that place things at random (dungeons, camps,
 * events) and must keep clear of kingdoms. Read it before placing something:
 *
 * <pre>{@code
 * if (CharterAreas.intersects("minecraft:overworld", minX, minZ, maxX, maxZ)) return; // choose another spot
 * }</pre>
 *
 * <p>Every method is read only and safe to call from any thread, worldgen threads included: the map is an immutable
 * snapshot that Charter replaces whole whenever land changes. A snapshot never changes after it is published, so keep
 * an {@link Area} as long as you like, and use {@link #version()} to notice that the map has moved on. Before a world is
 * running the map is empty. The same data is written to {@code <world>/charter/areas.json} for anything that is not Java.
 */
public final class CharterAreas {

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private CharterAreas() {
    }

    // ---- reading ----

    /** Whether any kingdom holds this chunk. */
    public static boolean isHeld(String dimension, int chunkX, int chunkZ) {
        return areaAt(dimension, chunkX, chunkZ).isPresent();
    }

    /** Whether any kingdom holds the chunk this block is in. */
    public static boolean isHeldAtBlock(String dimension, int blockX, int blockZ) {
        return isHeld(dimension, blockX >> 4, blockZ >> 4);
    }

    /** The area holding this chunk, if any. */
    public static Optional<Area> areaAt(String dimension, int chunkX, int chunkZ) {
        Long2ObjectOpenHashMap<Area> map = snapshot.byChunk.get(dimension);
        return map == null ? Optional.empty() : Optional.ofNullable(map.get(pack(chunkX, chunkZ)));
    }

    /**
     * Whether a box of blocks touches any held chunk. Both corners are inclusive and may be given in either order.
     * Use it with the bounds of whatever you are about to place.
     */
    public static boolean intersects(String dimension, int blockX1, int blockZ1, int blockX2, int blockZ2) {
        Long2ObjectOpenHashMap<Area> map = snapshot.byChunk.get(dimension);
        if (map == null || map.isEmpty()) return false;
        int minX = Math.min(blockX1, blockX2) >> 4;
        int maxX = Math.max(blockX1, blockX2) >> 4;
        int minZ = Math.min(blockZ1, blockZ2) >> 4;
        int maxZ = Math.max(blockZ1, blockZ2) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (map.containsKey(pack(x, z))) return true;
            }
        }
        return false;
    }

    /** Every area in every dimension. */
    public static List<Area> areas() {
        return snapshot.all;
    }

    /** Every area in one dimension. */
    public static List<Area> areas(String dimension) {
        return snapshot.byDimension.getOrDefault(dimension, List.of());
    }

    /** A number that rises each time the map is replaced, 0 before the first. */
    public static long version() {
        return snapshot.version;
    }

    // ---- publishing: Charter's own ----

    /** Builds the map from the state and publishes it. Called by Charter, not by other mods. */
    public static void publish(CharterState state) {
        snapshot = build(state, snapshot.version + 1);
    }

    /** Empties the map, when a world stops. Called by Charter, not by other mods. */
    public static void clear() {
        snapshot = new Snapshot(snapshot.version + 1, List.of(), Map.of(), Map.of());
    }

    static Snapshot build(CharterState state, long version) {
        Map<String, List<Claim>> byKey = new LinkedHashMap<>();
        Map<String, Area.Kind> kinds = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        for (Claim claim : state.claims()) {
            CoreId core = claim.core();
            Core held = core == null ? null : state.core(core).orElse(null);
            OrgId faction = state.root(claim.owner);
            String kingdom = state.org(faction).map(Organization::name).orElse("?");
            String key;
            Area.Kind kind;
            if (held == null) {
                key = "claim:" + faction.toString() + ":" + claim.chunk.dimension();
                kind = Area.Kind.CLAIM;
            } else {
                key = "core:" + held.id + ":" + claim.chunk.dimension();
                kind = held.isKeep() ? Area.Kind.KEEP : Area.Kind.OUTPOST;
            }
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(claim);
            kinds.put(key, kind);
            owners.put(key, kingdom);
        }

        List<Area> all = new ArrayList<>();
        Map<String, Long2ObjectOpenHashMap<Area>> byChunk = new HashMap<>();
        Map<String, List<Area>> byDimension = new HashMap<>();
        for (Map.Entry<String, List<Claim>> e : byKey.entrySet()) {
            List<Claim> claims = e.getValue();
            String dimension = claims.get(0).chunk.dimension();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            List<Area.Chunk> chunks = new ArrayList<>();
            for (Claim c : claims) {
                chunks.add(new Area.Chunk(c.chunk.x(), c.chunk.z()));
                minX = Math.min(minX, c.chunk.x());
                maxX = Math.max(maxX, c.chunk.x());
                minZ = Math.min(minZ, c.chunk.z());
                maxZ = Math.max(maxZ, c.chunk.z());
            }
            chunks.sort(Comparator.comparingInt(Area.Chunk::x).thenComparingInt(Area.Chunk::z));
            Area area = new Area(kinds.get(e.getKey()), owners.get(e.getKey()), dimension, minX, minZ, maxX, maxZ,
                    Collections.unmodifiableList(chunks));
            all.add(area);
            byDimension.computeIfAbsent(dimension, d -> new ArrayList<>()).add(area);
            Long2ObjectOpenHashMap<Area> map = byChunk.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
            for (Area.Chunk c : chunks) map.put(pack(c.x(), c.z()), area);
        }
        all.sort(Comparator.comparing(Area::dimension).thenComparing(Area::kingdom).thenComparing(a -> a.kind().ordinal()));
        Map<String, List<Area>> frozen = new HashMap<>();
        byDimension.forEach((d, list) -> {
            list.sort(Comparator.comparing(Area::kingdom).thenComparing(a -> a.kind().ordinal()));
            frozen.put(d, Collections.unmodifiableList(list));
        });
        return new Snapshot(version, Collections.unmodifiableList(all), Collections.unmodifiableMap(frozen), byChunk);
    }

    private static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    /** One published map. Never changed after it is built. */
    record Snapshot(long version, List<Area> all, Map<String, List<Area>> byDimension, Map<String, Long2ObjectOpenHashMap<Area>> byChunk) {
        static final Snapshot EMPTY = new Snapshot(0, List.of(), Map.of(), Map.of());
    }
}
