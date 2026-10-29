package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The pure rules of the Infestation's creeping bricks (roadmap T24), with no Minecraft imports so
 * {@code CreepingRulesTest} runs with plain javac. {@link CreepingBricks} applies them to the world.
 *
 * <p>A brick has four beats: mossy stone brick, stone brick, infested stone brick, then a burst. Its stage is a
 * function of the ticks since it was planted, so nothing but the planting time has to be remembered.
 */
final class CreepingRules {

    static final int MOSSY = 0;
    static final int STONE = 1;
    static final int INFESTED = 2;
    /** Past the last stage: the brick breaks and releases its silverfish. */
    static final int BURST = 3;

    /** Rooms that grow bricks (Chewed Corridors and Silverfish Nests, decision 3b). The Lush Caves wall is not here. */
    static final Set<String> SEEDED_ROOMS = Set.of(
            "pocketdungeons:burrow_tunnel", "pocketdungeons:gnawed_vein", "pocketdungeons:slime_pit");

    /** Ticks between one seed starting and the next, so a room does not erupt at once. */
    static final int SEED_GAP_TICKS = 40;
    /** Extra random ticks on a seed's start, 0 to this minus one. */
    static final int SEED_JITTER_TICKS = 20;
    /** Seeds are at least this many blocks apart (chebyshev). */
    static final int SEED_SPACING = 3;

    private CreepingRules() {}

    /** The stage a brick planted {@code elapsed} ticks ago has reached: 0 mossy, 1 stone, 2 infested, 3 burst. */
    static int stageAt(long elapsed, long mossyTicks, long stoneTicks, long infestedTicks) {
        if (elapsed < mossyTicks) {
            return MOSSY;
        }
        if (elapsed < mossyTicks + stoneTicks) {
            return STONE;
        }
        if (elapsed < mossyTicks + stoneTicks + infestedTicks) {
            return INFESTED;
        }
        return BURST;
    }

    /** How long a brick lives from planting to its burst. */
    static long lifeTicks(long mossyTicks, long stoneTicks, long infestedTicks) {
        return mossyTicks + stoneTicks + infestedTicks;
    }

    /**
     * Ticks between one live-tell sparkle and the next on a brick at {@code stage} (PD-222). The cadence
     * tightens as the brick ripens, so the countdown reads without watching textures: an easy mossy shimmer,
     * a quicker pulse once it sets, a fast twinkle while it squirms.
     */
    static int tellTicks(int stage) {
        return switch (stage) {
            case MOSSY -> 30;
            case STONE -> 15;
            default -> 10;
        };
    }

    /** How many more bricks may be planted given what grows in the room and on the floor (never negative). */
    static int slots(int activeInRoom, int roomCap, int activeOnFloor, int floorCap) {
        return Math.max(0, Math.min(roomCap - activeInRoom, floorCap - activeOnFloor));
    }

    /**
     * Whether a cell-local spot is on a door lane, which stays clear: x 7..8 within three of the north and
     * south walls, z 7..8 within three of the west and east walls. Symmetric, so rotation does not matter.
     */
    static boolean inDoorLane(int lx, int lz) {
        boolean northSouth = lx >= 7 && lx <= 8 && (lz <= 3 || lz >= 12);
        boolean westEast = lz >= 7 && lz <= 8 && (lx <= 3 || lx >= 12);
        return northSouth || westEast;
    }

    /** Whether {@code roomId} (namespaced or bare) grows bricks in a run of the Infestation theme. */
    static boolean seeds(String roomId, String theme) {
        if (roomId == null || theme == null || !theme.endsWith("infestation")) {
            return false;
        }
        String qualified = roomId.contains(":") ? roomId : "pocketdungeons:" + roomId;
        return SEEDED_ROOMS.contains(qualified);
    }

    /**
     * Picks up to {@code wanted} candidate indexes, deterministic in {@code seed}, no two closer than
     * {@code spacing} blocks (chebyshev). The result is in pick order.
     */
    static int[] pickSpread(long seed, int[] xs, int[] zs, int wanted, int spacing) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, new Random(seed));
        List<Integer> picked = new ArrayList<>();
        for (int index : order) {
            if (picked.size() >= wanted) {
                break;
            }
            boolean clear = true;
            for (int other : picked) {
                if (Math.max(Math.abs(xs[index] - xs[other]), Math.abs(zs[index] - zs[other])) < spacing) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                picked.add(index);
            }
        }
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = picked.get(i);
        }
        return out;
    }

    /** Ticks after the room is entered at which seed number {@code index} starts, deterministic in the seed. */
    static long startDelay(long seed, int index) {
        long mixed = (seed ^ (index * 0x9E3779B97F4A7C15L)) * 0xBF58476D1CE4E5B9L;
        int jitter = (int) ((mixed >>> 33) % SEED_JITTER_TICKS);
        return (long) index * SEED_GAP_TICKS + jitter;
    }
}
