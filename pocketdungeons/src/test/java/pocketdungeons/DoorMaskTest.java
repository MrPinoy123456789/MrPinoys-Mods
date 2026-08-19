package pocketdungeons;

import java.util.EnumSet;
import java.util.Set;

public class DoorMaskTest {

    public static void main(String[] args) {
        testRotation();
        testToLetters();
        testFromEdges();
        testHasEdge();
        System.out.println("DoorMaskTest passed");
    }

    private static void testRotation() {
        int base = DoorMask.NORTH | DoorMask.EAST;
        check(DoorMask.rotateClockwise(base, 0), base);
        check(DoorMask.rotateClockwise(base, 1), DoorMask.EAST | DoorMask.SOUTH);
        check(DoorMask.rotateClockwise(base, 2), DoorMask.SOUTH | DoorMask.WEST);
        check(DoorMask.rotateClockwise(base, 3), DoorMask.WEST | DoorMask.NORTH);
        check(DoorMask.rotateClockwise(base, 4), base);

        check(DoorMask.rotateClockwise(base, -1), DoorMask.WEST | DoorMask.NORTH);
        check(DoorMask.rotateClockwise(base, 5), DoorMask.EAST | DoorMask.SOUTH);

        check(DoorMask.rotateClockwise(DoorMask.NORTH, 1), DoorMask.EAST);
        check(DoorMask.rotateClockwise(DoorMask.EAST, 1), DoorMask.SOUTH);
        check(DoorMask.rotateClockwise(DoorMask.SOUTH, 1), DoorMask.WEST);
        check(DoorMask.rotateClockwise(DoorMask.WEST, 1), DoorMask.NORTH);
    }

    private static void testToLetters() {
        check(DoorMask.toLetters(0), "none");
        check(DoorMask.toLetters(DoorMask.NORTH), "N");
        check(DoorMask.toLetters(DoorMask.EAST), "E");
        check(DoorMask.toLetters(DoorMask.SOUTH), "S");
        check(DoorMask.toLetters(DoorMask.WEST), "W");
        check(DoorMask.toLetters(DoorMask.NORTH | DoorMask.EAST), "NE");
        check(DoorMask.toLetters(DoorMask.WEST | DoorMask.EAST), "EW");
        check(DoorMask.toLetters(DoorMask.NORTH | DoorMask.EAST | DoorMask.SOUTH | DoorMask.WEST), "NESW");
    }

    private static void testFromEdges() {
        Set<DoorMask.Direction> empty = Set.of();
        check(DoorMask.fromEdges(empty), 0);

        Set<DoorMask.Direction> northEast = EnumSet.of(
                DoorMask.Direction.NORTH, DoorMask.Direction.EAST);
        check(DoorMask.fromEdges(northEast), DoorMask.NORTH | DoorMask.EAST);

        Set<DoorMask.Direction> all = EnumSet.allOf(DoorMask.Direction.class);
        check(DoorMask.fromEdges(all),
                DoorMask.NORTH | DoorMask.EAST | DoorMask.SOUTH | DoorMask.WEST);
    }

    private static void testHasEdge() {
        int mask = DoorMask.NORTH | DoorMask.SOUTH;
        check(DoorMask.hasEdge(mask, DoorMask.Direction.NORTH));
        check(DoorMask.hasEdge(mask, DoorMask.Direction.SOUTH));
        check(!DoorMask.hasEdge(mask, DoorMask.Direction.EAST));
        check(!DoorMask.hasEdge(mask, DoorMask.Direction.WEST));
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected 0b" + Integer.toBinaryString(expected)
                    + " but got 0b" + Integer.toBinaryString(actual));
        }
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    private static void check(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected \"" + expected + "\" but got \"" + actual + "\"");
        }
    }
}
