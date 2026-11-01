package pocketdungeons;

/** The level of a wild dungeon from its distance to world spawn (plan 4.5). Pure. */
final class WildLevel {

    /** Blocks of distance per extra level. */
    static final double BLOCKS_PER_LEVEL = 400.0;

    private WildLevel() {}

    /** 1 + distance / 400, at least 1 and at most {@code maxLevel} (itself at least 1). */
    static int of(double distanceFromSpawn, int maxLevel) {
        int level = 1 + (int) (Math.max(0.0, distanceFromSpawn) / BLOCKS_PER_LEVEL);
        return Math.max(1, Math.min(level, Math.max(1, maxLevel)));
    }
}
