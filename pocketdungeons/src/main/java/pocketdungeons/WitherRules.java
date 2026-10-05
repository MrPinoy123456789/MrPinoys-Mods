package pocketdungeons;

/**
 * Dungeon structure W7b: the pure rules of the Act 4 capstone, the Wither. No Minecraft imports, so
 * {@code BossRulesTest} runs with plain {@code javac}. {@code WitherFight} reads the world and acts
 * on them.
 *
 * <p>A real vanilla Wither is summoned when the first member enters the boss room. It has no block
 * damage here: the explosion mixin blocks every blast block inside a dungeon cell (the spawn blast
 * and the skulls), and a second mixin stops its own block breaking. It cannot leave the room: any
 * tick it is found outside {@link #INSET} of the cell edge it is put back. The pad stays shut until
 * it is dead.
 */
final class WitherRules {

    private WitherRules() {}

    /** Wither max health for a solo party (vanilla is 300). */
    static final double BASE_HEALTH = 240.0;
    /** Extra max health per member past the first. */
    static final double HEALTH_PER_EXTRA_MEMBER = 120.0;
    /** Ceiling on the scaled max health, so a large party does not meet an endless fight. */
    static final double MAX_HEALTH = 720.0;

    /** Blocks in from the cell edge the Wither must stay: its head, wings and the doorway lanes stay in. */
    static final double INSET = 1.5;
    /** The cell is this wide; matches {@link RoomGeometry#CELL}. */
    static final int CELL = 16;
    /** The tallest feet height inside the cell (relative to the cell origin) before it is put back down. */
    static final double MAX_FEET_Y = 2.5;
    /** The lowest feet height; below the floor means it got through, so it is put back. */
    static final double MIN_FEET_Y = 0.5;

    /** The Wither's max health for a party of {@code party} (anyone below one counts as one). */
    static double maxHealth(int party) {
        int extra = Math.max(1, party) - 1;
        return Math.min(MAX_HEALTH, BASE_HEALTH + HEALTH_PER_EXTRA_MEMBER * extra);
    }

    /**
     * Whether a Wither at {@code (rx, ry, rz)}, relative to the boss cell's origin, has left the room
     * and must be put back: outside the cell footprint shrunk by {@link #INSET}, or outside its
     * allowed height band.
     */
    static boolean outsideRoom(double rx, double ry, double rz) {
        return rx < INSET || rx > CELL - INSET || rz < INSET || rz > CELL - INSET
                || ry < MIN_FEET_Y || ry > MAX_FEET_Y;
    }

    /**
     * The pad's answer: {@code null} when it may complete the floor, else the line to show. The pad
     * is shut until the Wither has been summoned and then killed. A Wither that never appeared (the
     * spawn failed) does not hold the floor forever.
     */
    static String padRefusal(boolean summoned, boolean spawnFailed, boolean alive) {
        if (spawnFailed) {
            return null;
        }
        if (!summoned) {
            return "The pad is cold. Something in this room has not woken yet.";
        }
        return alive ? "The Wither still stands. Finish it first." : null;
    }

    /** Whether a member standing in the room should wake the Wither now. */
    static boolean shouldSummon(boolean finalFloor, boolean alreadySummoned, int membersInRoom) {
        return finalFloor && !alreadySummoned && membersInRoom > 0;
    }
}
