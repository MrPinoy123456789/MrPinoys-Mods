package pocketdungeons;

import java.util.List;

/**
 * Pure-JDK regression for {@link TrappedCache}'s rules: the ambush size, and that the spots an ambush may use
 * never touch a door lane or the chest (affix workshop 2026-10-11, row 4).
 */
public class TrappedCacheTest {

    public static void main(String[] args) {
        testSize();
        testLanes();
        testColumns();
        System.out.println("TrappedCacheTest passed");
    }

    private static void testSize() {
        check(TrappedCache.ambushSize(1), 3, "a party of one meets 3");
        check(TrappedCache.ambushSize(2), 4, "each extra member adds one");
        check(TrappedCache.ambushSize(4), 6, "a party of four meets 6");
        check(TrappedCache.ambushSize(6), 8, "a party of six reaches the cap");
        check(TrappedCache.ambushSize(20), 8, "the cap holds");
        check(TrappedCache.ambushSize(0), 3, "an empty party reads as one");
        check(TrappedCache.ambushSize(-3), 3, "so does a negative one");
    }

    /** The lane is the band through the middle of the cell on both axes, a doorway wide plus one each side. */
    private static void testLanes() {
        for (int along = 0; along < RoomGeometry.CELL; along++) {
            for (int across = RoomGeometry.DOOR_MIN; across <= RoomGeometry.DOOR_MAX; across++) {
                if (!TrappedCache.inLane(across, along) || !TrappedCache.inLane(along, across)) {
                    throw new AssertionError("a doorway column must be a lane: " + across + "," + along);
                }
            }
        }
        if (TrappedCache.inLane(2, 2) || TrappedCache.inLane(13, 13) || TrappedCache.inLane(2, 13)) {
            throw new AssertionError("the corners are not lanes");
        }
    }

    private static void testColumns() {
        int[][] chests = {{2, 2}, {13, 13}};
        for (int[] chest : chests) {
            List<int[]> columns = TrappedCache.candidateColumns(chest[0], chest[1]);
            if (columns.size() < TrappedCache.MAX_SIZE) {
                throw new AssertionError("the chest at " + chest[0] + "," + chest[1] + " leaves only "
                        + columns.size() + " spots; the biggest ambush needs " + TrappedCache.MAX_SIZE);
            }
            for (int[] c : columns) {
                if (TrappedCache.inLane(c[0], c[1])) {
                    throw new AssertionError("a spot sits on a lane: " + c[0] + "," + c[1]);
                }
                if (c[0] == chest[0] && c[1] == chest[1]) {
                    throw new AssertionError("a spot sits on the chest");
                }
                if (c[0] < 1 || c[0] > 14 || c[1] < 1 || c[1] > 14) {
                    throw new AssertionError("a spot sits in the wall ring: " + c[0] + "," + c[1]);
                }
                if (Math.abs(c[0] - chest[0]) > TrappedCache.RADIUS || Math.abs(c[1] - chest[1]) > TrappedCache.RADIUS) {
                    throw new AssertionError("a spot is too far from the chest");
                }
            }
        }
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
