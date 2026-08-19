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
 */
public final class DoorMask {

    public static final int NORTH = 0x1;
    public static final int EAST  = 0x2;
    public static final int SOUTH = 0x4;
    public static final int WEST  = 0x8;

    private DoorMask() {}

    /**
     * Rotates a door mask clockwise by the given number of 90-degree turns.
     * With the bit order above, a clockwise rotation of the placed room is a
     * cyclic left rotate of the 4-bit mask.
     */
    public static int rotateClockwise(int mask, int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        if (q == 0) return mask & 0xF;
        return ((mask << q) | (mask >>> (4 - q))) & 0xF;
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

    /** Renders a mask as compass letters in N-E-S-W order, e.g. "WE", "NESW". */
    public static String toLetters(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & NORTH) != 0) sb.append('N');
        if ((mask & EAST)  != 0) sb.append('E');
        if ((mask & SOUTH) != 0) sb.append('S');
        if ((mask & WEST)  != 0) sb.append('W');
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
