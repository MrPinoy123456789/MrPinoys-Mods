package pocketdungeons;

import java.util.Set;

/**
 * Pure bit math for room door masks. No Minecraft imports: this class is
 * intentionally plain-JDK so it can be compiled and unit-tested without a
 * Minecraft classpath, matching the suite's core/fabric split discipline.
 *
 * <p>A mask is a 4-bit int:
 * <pre>
 *   bit 0 (0x1) = north edge has a door
 *   bit 1 (0x2) = east  edge has a door
 *   bit 2 (0x4) = south edge has a door
 *   bit 3 (0x8) = west  edge has a door
 * </pre>
 *
 * <p>Vertical floors phase 2: a room that owns a lower story (a stair room) carries a second
 * nibble. Bits 4 to 7 are the same four edges on the story below ({@link #LOWER_SHIFT}). Every
 * mask a one story room has is below 0x10, so nothing that existed before reads differently.
 */
public final class DoorMask {

    public static final int NORTH = 0x1;
    public static final int EAST  = 0x2;
    public static final int SOUTH = 0x4;
    public static final int WEST  = 0x8;

    /** How far the lower story's nibble sits above the top story's. */
    public static final int LOWER_SHIFT = 4;

    /** Both nibbles. */
    public static final int ALL_BITS = 0xFF;

    private DoorMask() {}

    /** The single-edge mask for {@code edge} on door story slot {@code slot} (0 top, 1 the story below). */
    public static int edge(Direction edge, int slot) {
        return bit(edge) << (LOWER_SHIFT * slot);
    }

    /** The four-bit mask of one story slot of {@code mask}. */
    public static int story(int mask, int slot) {
        return (mask >>> (LOWER_SHIFT * slot)) & 0xF;
    }

    /** True if the mask has any door on a story below the top one. */
    public static boolean hasLowerStory(int mask) {
        return (mask & ~0xF & ALL_BITS) != 0;
    }

    /**
     * Rotates a door mask clockwise by the given number of 90-degree turns.
     * With the bit order above, a clockwise rotation of the placed room is a
     * cyclic left rotate of the 4-bit mask. A lower story nibble rotates the same way.
     */
    public static int rotateClockwise(int mask, int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        if (q == 0) return mask & ALL_BITS;
        return rotateNibble(mask & 0xF, q) | (rotateNibble((mask >>> LOWER_SHIFT) & 0xF, q) << LOWER_SHIFT);
    }

    private static int rotateNibble(int nibble, int q) {
        return ((nibble << q) | (nibble >>> (4 - q))) & 0xF;
    }

    /** Builds a mask from a set of cardinal edge directions. */
    public static int fromEdges(Set<Direction> edges) {
        int mask = 0;
        for (Direction d : edges) {
            mask |= bit(d);
        }
        return mask;
    }

    /** True if the mask has a door on the given edge. */
    public static boolean hasEdge(int mask, Direction edge) {
        return (mask & bit(edge)) != 0;
    }

    /**
     * Renders a mask as compass letters in N-E-S-W order, e.g. "WE", "NESW". A mask with doors on a
     * lower story adds them after a slash, e.g. "W/E" (in on the west, out on the east one story down).
     */
    public static String toLetters(int mask) {
        String top = letters(mask & 0xF);
        if (!hasLowerStory(mask)) {
            return top;
        }
        return top + "/" + letters(story(mask, 1));
    }

    private static String letters(int nibble) {
        StringBuilder sb = new StringBuilder();
        if ((nibble & NORTH) != 0) sb.append('N');
        if ((nibble & EAST)  != 0) sb.append('E');
        if ((nibble & SOUTH) != 0) sb.append('S');
        if ((nibble & WEST)  != 0) sb.append('W');
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static int bit(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case EAST  -> EAST;
            case SOUTH -> SOUTH;
            case WEST  -> WEST;
        };
    }

    /** The four horizontal cardinal directions, independent of Minecraft's enum. */
    public enum Direction {
        NORTH, EAST, SOUTH, WEST
    }
}
