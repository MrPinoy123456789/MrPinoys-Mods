package pocketdungeons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The pure half of hidden ore (design 2026-10-06-1 item 7): where the pockets go.
 * No Minecraft imports, so {@code HiddenOrePlannerTest} runs with plain {@code javac}.
 *
 * <p>A pocket is 1 to {@code sizeMax} ore blocks buried in solid rock: every block of it,
 * and all six of that block's neighbours, are solid, so it can only be found by digging.
 * A start that would touch air is skipped (another is drawn), never moved to fit. Positions
 * stay {@link #MARGIN} blocks off the wall ring: the ring itself is shell, and a connector
 * cut into it after the room is stamped (an arch, an open wall) would otherwise expose a
 * pocket that sat against it.
 */
final class HiddenOrePlanner {

    /** Blocks between a pocket and the wall ring, so no later wall cut can expose it. */
    static final int MARGIN = 2;

    /** Start positions tried per pocket before it is skipped. */
    private static final int START_ATTEMPTS = 30;

    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private HiddenOrePlanner() {}

    /** A yes or no question about the block at a position local to the room. */
    @FunctionalInterface
    interface Rock {
        boolean at(int x, int y, int z);
    }

    /**
     * The pockets for one room, seeded so a preview and a commit stamp the same ones.
     *
     * @param cell       the room's width and depth in blocks
     * @param wallHeight the top of the interior (pockets stay at y 1 to {@code wallHeight - 1})
     * @param solid      whether the block at a position is plain solid rock (not air, fluid or a
     *                   block entity)
     * @param free       whether a pocket may take the position (it is not already a node)
     * @return each pocket as the local positions {@code {x, y, z}} it fills; empty when the room has
     *         no rock to bury ore in
     */
    static List<List<int[]>> plan(long seed, int pocketsMin, int pocketsMax, int sizeMin, int sizeMax,
                                  int cell, int wallHeight, Rock solid, Rock free) {
        Random rng = new Random(seed);
        int pockets = pocketsMin + (pocketsMax > pocketsMin ? rng.nextInt(pocketsMax - pocketsMin + 1) : 0);
        int lo = MARGIN;
        int hi = cell - 1 - MARGIN;
        int top = wallHeight - 1;
        Set<Long> taken = new HashSet<>();
        List<List<int[]>> out = new ArrayList<>();
        for (int pocket = 0; pocket < pockets; pocket++) {
            int size = sizeMin + (sizeMax > sizeMin ? rng.nextInt(sizeMax - sizeMin + 1) : 0);
            int[] start = null;
            for (int attempt = 0; attempt < START_ATTEMPTS && start == null; attempt++) {
                int x = lo + rng.nextInt(hi - lo + 1);
                int y = 1 + rng.nextInt(Math.max(1, top));
                int z = lo + rng.nextInt(hi - lo + 1);
                if (buriable(x, y, z, lo, hi, top, solid, free, taken)) {
                    start = new int[]{x, y, z};
                }
            }
            if (start == null) {
                continue;
            }
            List<int[]> blocks = new ArrayList<>();
            blocks.add(start);
            taken.add(key(start[0], start[1], start[2]));
            while (blocks.size() < size) {
                List<int[]> options = new ArrayList<>();
                for (int[] at : blocks) {
                    for (int[] d : DIRECTIONS) {
                        int x = at[0] + d[0];
                        int y = at[1] + d[1];
                        int z = at[2] + d[2];
                        if (buriable(x, y, z, lo, hi, top, solid, free, taken)) {
                            options.add(new int[]{x, y, z});
                        }
                    }
                }
                if (options.isEmpty()) {
                    break;
                }
                int[] next = options.get(rng.nextInt(options.size()));
                if (taken.add(key(next[0], next[1], next[2]))) {
                    blocks.add(next);
                }
            }
            out.add(blocks);
        }
        return out;
    }

    private static boolean buriable(int x, int y, int z, int lo, int hi, int top, Rock solid, Rock free,
                                    Set<Long> taken) {
        if (x < lo || x > hi || z < lo || z > hi || y < 1 || y > top || taken.contains(key(x, y, z))) {
            return false;
        }
        if (!solid.at(x, y, z) || !free.at(x, y, z)) {
            return false;
        }
        for (int[] d : DIRECTIONS) {
            if (!solid.at(x + d[0], y + d[1], z + d[2])) {
                return false;
            }
        }
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }
}
