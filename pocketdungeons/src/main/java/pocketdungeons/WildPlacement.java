package pocketdungeons;

import java.util.SplittableRandom;

/**
 * Where a wild lodestone may stand (plan 4.1 and 4.2): a candidate point uniformly anywhere in a grid cell, chosen from
 * the world seed, the cell and a generation so the same inputs give the same point and a rejected cell tries another.
 * Never the cell centre, and never a lattice. Pure.
 */
final class WildPlacement {

    private WildPlacement() {}

    /** The candidate block {x, z} in cell ({@code cellX}, {@code cellZ}) of side {@code cell} blocks. */
    static int[] candidate(long worldSeed, int cellX, int cellZ, int generation, int cell) {
        long h = worldSeed;
        h = h * 0x9E3779B97F4A7C15L + cellX;
        h = h * 0x9E3779B97F4A7C15L + cellZ;
        h = h * 0x9E3779B97F4A7C15L + generation;
        SplittableRandom random = new SplittableRandom(h);
        return new int[] {cellX * cell + random.nextInt(cell), cellZ * cell + random.nextInt(cell)};
    }

    /** The cell index of a block coordinate. */
    static int cellOf(int blockCoordinate, int cell) {
        return Math.floorDiv(blockCoordinate, cell);
    }

    /** Whether the point is at least {@code minSeparation} blocks (flat distance) from every given point. */
    static boolean separated(int x, int z, Iterable<int[]> others, int minSeparation) {
        long limit = (long) minSeparation * minSeparation;
        for (int[] other : others) {
            long dx = (long) x - other[0];
            long dz = (long) z - other[1];
            if (dx * dx + dz * dz < limit) {
                return false;
            }
        }
        return true;
    }

    /** Whether the point lies between the minimum and maximum flat distance from spawn. */
    static boolean withinRing(int x, int z, int spawnX, int spawnZ, int minDistance, int maxRadius) {
        double d = Math.hypot((double) x - spawnX, (double) z - spawnZ);
        return d >= minDistance && d <= maxRadius;
    }
}
