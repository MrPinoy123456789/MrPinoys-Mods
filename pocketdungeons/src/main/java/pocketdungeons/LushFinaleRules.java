package pocketdungeons;

/**
 * The pure rules of the Lush Caves finale (roadmap T23): what a split gives, how many slimes may stand at
 * once, and the sizes the boss steps down through when the room is too small for it. No Minecraft imports, so
 * {@code LushFinaleRulesTest} runs with plain {@code javac}.
 */
final class LushFinaleRules {

    private LushFinaleRules() {}

    /** The size a slime's children have: half, as vanilla does. A slime of size 1 does not split. */
    static int childSize(int parentSize) {
        return parentSize / 2;
    }

    /** Whether a slime of this size splits when it dies. */
    static boolean splits(int size) {
        return size > 1;
    }

    /** How many more slimes fit under the cap with {@code alive} standing. */
    static int room(int alive, int cap) {
        return Math.max(0, cap - Math.max(0, alive));
    }

    /** How many of the {@code vanilla} children of a split are kept: all that fit under the cap. */
    static int keep(int vanilla, int alive, int cap) {
        return Math.min(Math.max(0, vanilla), room(alive, cap));
    }

    /**
     * The extra children the first split tops up to double what vanilla gave: {@code vanilla} more, held to
     * what is left under the cap after the kept children stand.
     */
    static int topUp(int vanilla, int kept, int alive, int cap) {
        return Math.min(Math.max(0, vanilla), room(alive + kept, cap));
    }

    /** The next size down when the boss does not fit: two smaller, never under 2. */
    static int smallerSize(int size) {
        return Math.max(2, size - 2);
    }

    /** Ticks a dead slime waits for its children to appear (vanilla spawns them as the body is removed, about 20 ticks after death). */
    static final int SPLIT_WINDOW_TICKS = 80;

    // ---- the boss's slime pools ---------------------------------------------------------------------

    /** Ticks after the wave stands before the first pool, and between pools after that. */
    static final int POOL_FIRST_TICKS = 120;
    static final int POOL_INTERVAL_TICKS = 220;

    /** The tiles one pool covers (a circle of this radius) and the marked warning before it lands. */
    static final double POOL_RADIUS = 2.0;
    static final int POOL_WARN_TICKS = 30;

    /** How long a pool stands and how often it applies its dissolve. */
    static final int POOL_LIVE_TICKS = 200;
    static final int POOL_TICK = 20;

    /** The slowness and damage one tick in a pool applies; stepping out ends both. */
    static final int POOL_SLOW_TICKS = 60;
    static final float POOL_DAMAGE = 1.0f;
}
