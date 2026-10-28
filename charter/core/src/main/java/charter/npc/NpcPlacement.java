package charter.npc;

import charter.config.Knobs;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.LandGraph;
import charter.state.CharterState;

import java.util.Collection;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Where the raiders may build (N5), as a pure function of a seeded random and the world's facts, so a
 * test can fix the seed. A site is never inside a claim, stands at least {@code keepGapChunks} from every
 * keep, and at least {@code npcPlayerClearChunks} from any player claim and any online player. The terrain
 * (dry, flat enough, generated) is the caller's to judge, through {@code terrainOk}.
 */
public final class NpcPlacement {

    public static final int ATTEMPTS = 20;

    private NpcPlacement() {
    }

    /** The first of up to {@link #ATTEMPTS} random candidates between the two rings that passes, or empty. */
    public static Optional<ChunkKey> choose(Random rng, String dimension, ChunkKey spawn, int minRing, int maxRing,
                                            CharterState state, Knobs knobs, Collection<ChunkKey> players,
                                            Predicate<ChunkKey> terrainOk) {
        for (int i = 0; i < ATTEMPTS; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double distance = minRing + rng.nextDouble() * Math.max(1, maxRing - minRing);
            ChunkKey candidate = new ChunkKey(dimension, spawn.x() + (int) Math.round(Math.cos(angle) * distance),
                    spawn.z() + (int) Math.round(Math.sin(angle) * distance));
            if (acceptable(candidate, state, knobs, players) && terrainOk.test(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Every rule except the terrain. */
    public static boolean acceptable(ChunkKey center, CharterState state, Knobs knobs, Collection<ChunkKey> players) {
        if (new LandGraph(state, knobs).keepProblemForNew(center).isPresent()) return false;
        int clear = knobs.npcPlayerClearChunks;
        for (Claim claim : state.claims()) {
            boolean raiders = state.org(claim.owner).map(o -> o.npc).orElse(false);
            if (!raiders && near(center, claim.chunk, clear)) return false;
        }
        for (ChunkKey player : players) {
            if (near(center, player, clear)) return false;
        }
        return true;
    }

    private static boolean near(ChunkKey a, ChunkKey b, int chunks) {
        return a.dimension().equals(b.dimension()) && Math.abs(a.x() - b.x()) < chunks && Math.abs(a.z() - b.z()) < chunks;
    }

    /** 0 near, 1 middle, 2 far, by distance in chunks from the world spawn. */
    public static int tier(ChunkKey spawn, ChunkKey site, Knobs knobs) {
        int d = Math.max(Math.abs(site.x() - spawn.x()), Math.abs(site.z() - spawn.z()));
        return d < knobs.npcNearChunks ? 0 : d < knobs.npcMiddleChunks ? 1 : 2;
    }
}
