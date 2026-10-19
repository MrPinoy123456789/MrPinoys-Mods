package pocketdungeons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a pack pot may stand inside one 16 by 16 cell (decision 2026-10-18, rule 2), as plain coordinates
 * local to the cell so a plain JVM can pin it. A pot never stands in a door lane, so it can never close a
 * way through; {@link PackPots} adds the checks that need the world (a floor underfoot, air, no chokepoint,
 * not the pad).
 *
 * <p>The lane is the doorway ({@link RoomGeometry#DOOR_MIN} to {@link RoomGeometry#DOOR_MAX}) widened by one
 * block each side ({@code 6..9}) and running {@value #LANE_DEPTH} blocks in from the wall. It is applied to
 * all four walls whether or not the cell has a door there: the cell's real door mask is not needed, and a
 * pot is a little further from a bare wall than it strictly must be.
 */
final class PackSpots {

    /** How many blocks in from a wall a door lane runs. */
    static final int LANE_DEPTH = 4;

    private static final int CELL = RoomGeometry.CELL;
    private static final int LANE_MIN = RoomGeometry.DOOR_MIN - 1;
    private static final int LANE_MAX = RoomGeometry.DOOR_MAX + 1;

    /** The middle of the door on each wall (north, south, west, east), as {@code {x, z}} in half blocks. */
    private static final int[][] DOORS = {{15, 0}, {15, 30}, {0, 15}, {30, 15}};

    private PackSpots() {}

    /** Whether the column {@code (lx, lz)} of a cell lies in the lane of one of its four doors. */
    static boolean inLane(int lx, int lz) {
        boolean northSouth = lx >= LANE_MIN && lx <= LANE_MAX && (lz < LANE_DEPTH || lz >= CELL - LANE_DEPTH);
        boolean eastWest = lz >= LANE_MIN && lz <= LANE_MAX && (lx < LANE_DEPTH || lx >= CELL - LANE_DEPTH);
        return northSouth || eastWest;
    }

    /**
     * The interior columns of a cell (1 to 14 each way) outside every door lane, nearest first to the
     * door of the cell that is nearest {@code (fromLx, fromLz)} (the spot the player fell, clamped into
     * the cell). Ties break toward the spot itself, then by coordinate, so the order is total.
     */
    static List<int[]> candidatesNearDoor(int fromLx, int fromLz) {
        final int fx = Math.max(0, Math.min(CELL - 1, fromLx));
        final int fz = Math.max(0, Math.min(CELL - 1, fromLz));
        int[] door = DOORS[0];
        long best = Long.MAX_VALUE;
        for (int[] candidate : DOORS) {
            long d = sq(candidate[0] - 2 * fx, candidate[1] - 2 * fz);
            if (d < best) {
                best = d;
                door = candidate;
            }
        }
        final int[] chosen = door;
        List<int[]> out = new ArrayList<>();
        for (int lx = 1; lx < CELL - 1; lx++) {
            for (int lz = 1; lz < CELL - 1; lz++) {
                if (!inLane(lx, lz)) {
                    out.add(new int[] {lx, lz});
                }
            }
        }
        out.sort(Comparator
                .comparingLong((int[] c) -> sq(chosen[0] - 2 * c[0], chosen[1] - 2 * c[1]))
                .thenComparingLong(c -> sq(c[0] - fx, c[1] - fz))
                .thenComparingInt(c -> c[0])
                .thenComparingInt(c -> c[1]));
        return out;
    }

    private static long sq(int dx, int dz) {
        return (long) dx * dx + (long) dz * dz;
    }
}
