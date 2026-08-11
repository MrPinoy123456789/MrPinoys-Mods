package bounties.core;

/**
 * Pure time/rotation logic for the public bounty board.
 *
 * <p>The board is derived from the wall clock, not stored state, so a restart at
 * any moment reproduces the exact same two bounties for that moment.
 *
 * <p>The rotation is offset by 15 minutes from the top of the hour so it does not
 * collide with quizengine's scheduled trivia.
 */
public final class BountyMath {

    public static final long HALF_HOUR_MS = 30L * 60L * 1000L;
    public static final long OFFSET_MS = 15L * 60L * 1000L;

    private static final int SALT_A = 1;
    private static final int SALT_B = 2;

    private BountyMath() {}

    /**
     * Half-hour window index for the given wall-clock time.
     */
    public static long windowIndex(long epochMillis) {
        return Math.floorDiv(epochMillis + OFFSET_MS, HALF_HOUR_MS);
    }

    /**
     * The two bounties shown at the given wall-clock time.
     */
    public static Board boardAt(BountyPool pool, long epochMillis) {
        return boardAtWindow(pool, windowIndex(epochMillis));
    }

    /**
     * The two bounties shown in a specific half-hour window.
     * Exposed for tests so they can pass an index directly.
     */
    public static Board boardAtWindow(BountyPool pool, long window) {
        if (pool.isEmpty()) {
            return new Board(null, null);
        }

        // Slot A changes every hour; slot B changes every hour, 30 minutes later.
        long indexA = Math.floorDiv(window, 2);
        long indexB = Math.floorDiv(window + 1, 2);

        return new Board(
                pick(pool, indexA, SALT_A),
                pick(pool, indexB, SALT_B)
        );
    }

    /**
     * When the currently visible board will next change, in epoch millis.
     */
    public static long nextRotationAt(long epochMillis) {
        long window = windowIndex(epochMillis);
        return (window + 1) * HALF_HOUR_MS - OFFSET_MS;
    }

    private static BountyDefinition pick(BountyPool pool, long index, int salt) {
        int i = Math.floorMod(Long.hashCode(index) * 31 + salt, pool.size());
        return pool.at(i);
    }
}
