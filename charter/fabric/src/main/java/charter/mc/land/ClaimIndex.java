package charter.mc.land;

import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.LandEvents;
import charter.mc.store.StoreEvents;
import charter.siege.SiegeEvents;
import charter.state.CharterState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Chunk to claim, one hash lookup (PLAN.md §5.1). Per dimension, packed chunk
 * coordinates to a small {@link ClaimRef}. Every protection and crossing check
 * goes through {@link #at}.
 *
 * <p>Built from the state on load and patched from history as claims change: a
 * single claim or unclaim patches its chunk; anything that moves many claims at
 * once (a core placed or lost, reassignment, grace, a fall) rebuilds, which is
 * rare and costs one pass over the claims.
 */
public final class ClaimIndex {

    /** What a lookup returns: enough to decide without touching the state. */
    public record ClaimRef(ClaimId claim, OrgId owner, ClaimKind kind, CoreId core) {
        static ClaimRef of(Claim c) {
            return new ClaimRef(c.id, c.owner, c.kind(), c.core());
        }
    }

    /** Siege events that add, remove or reassign claims. */
    private static final Set<Class<?>> REBUILD_ON = Set.of(
            StoreEvents.Repaired.class);

    private final CharterState state;
    private final Map<String, Long2ObjectOpenHashMap<ClaimRef>> byDimension = new HashMap<>();
    private int rebuilds;

    public ClaimIndex(CharterState state) {
        this.state = state;
        rebuild();
    }

    public static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    public ClaimRef at(String dimension, int chunkX, int chunkZ) {
        Long2ObjectOpenHashMap<ClaimRef> map = byDimension.get(dimension);
        return map == null ? null : map.get(pack(chunkX, chunkZ));
    }

    public ClaimRef at(ChunkKey chunk) {
        return at(chunk.dimension(), chunk.x(), chunk.z());
    }

    public int size() {
        int n = 0;
        for (Long2ObjectOpenHashMap<ClaimRef> map : byDimension.values()) n += map.size();
        return n;
    }

    /** How many full rebuilds have run, for tests. */
    public int rebuilds() {
        return rebuilds;
    }

    public void rebuild() {
        byDimension.clear();
        for (Claim claim : state.claims()) put(claim.chunk, ClaimRef.of(claim));
        rebuilds++;
    }

    /** Re-reads one chunk from the state. */
    public void refresh(ChunkKey chunk) {
        Claim claim = state.claimAt(chunk).orElse(null);
        if (claim == null) {
            Long2ObjectOpenHashMap<ClaimRef> map = byDimension.get(chunk.dimension());
            if (map != null) map.remove(pack(chunk.x(), chunk.z()));
        } else {
            put(chunk, ClaimRef.of(claim));
        }
    }

    private void put(ChunkKey chunk, ClaimRef ref) {
        byDimension.computeIfAbsent(chunk.dimension(), d -> new Long2ObjectOpenHashMap<>()).put(pack(chunk.x(), chunk.z()), ref);
    }

    /** History listener. */
    public void onEntry(History.Entry entry) {
        HistoryEvent event = entry.event();
        switch (event) {
            case LandEvents.Claimed c -> refresh(c.chunk());
            case LandEvents.Unclaimed u -> refresh(u.chunk());
            default -> {
                if (event.getClass().getEnclosingClass() == LandEvents.class || REBUILD_ON.contains(event.getClass())) rebuild();
            }
        }
    }
}
