package pocketdungeons;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owner ask 2026-10-19: the telegraphed area shapes (circle, cone, line) and their tile maths. */
public class AoEShapesTest {

    public static void main(String[] args) {
        // Circles: the edge is in, a step past it is out, and the tile count is honest.
        AoEShapes.Circle one = new AoEShapes.Circle(1);
        check(one.contains(0, 0) && one.contains(1, 0) && one.contains(0, -1), "the cross of radius 1");
        check(!one.contains(1, 1), "the corner of radius 1 is out");
        eq(one.tiles().size(), 5);
        AoEShapes.Circle two = new AoEShapes.Circle(2);
        check(two.contains(2, 0) && !two.contains(2, 1), "the edge of radius 2");
        eq(two.tiles().size(), 13);
        eq(new AoEShapes.Circle(3).tiles().size(), 29);
        check(two.contains(0.4, 0.4), "a standing point near the centre");

        // Cones: the wedge runs its range to the aim and the origin tile always marks.
        AoEShapes.Cone east = new AoEShapes.Cone(1, 0, 4, 45);
        check(east.contains(0, 0), "the apex marks");
        check(east.contains(4, 0) && east.contains(2, 2) && east.contains(2, -2), "the 45 degree edges");
        check(!east.contains(4.5, 0), "past the range");
        check(!east.contains(1, 2), "outside the wedge");
        check(!east.contains(-1, 0), "behind the apex");
        check(!east.contains(0, 4), "the range does not widen the wedge");
        eq(east.tiles().size(), 15);
        AoEShapes.Cone north = new AoEShapes.Cone(0, -1, 4, 30);
        check(north.contains(0, -3) && north.contains(0, -4), "the cone runs to its aim");
        check(!north.contains(0, 3) && !north.contains(2.6, -4), "behind and off-angle");
        check(north.contains(-1.9, -3.3), "the narrow wedge's edge (29.9 degrees, in range)");
        check(!north.contains(-1.8, -3.0), "just past the narrow wedge's edge (31 degrees)");
        eq(new AoEShapes.Cone(0, 0, 4, 45).tiles().size(), 15); // a degenerate aim reads east, not a crash

        // Lines: a bar from the origin to its length, half-width either side.
        AoEShapes.Line lane = new AoEShapes.Line(1, 0, 4, 1);
        check(lane.contains(0, 0) && lane.contains(4, 1) && lane.contains(4, -1), "the bar, corners in");
        check(!lane.contains(5, 0) && !lane.contains(0, 2) && !lane.contains(-1, 0), "past it, wide of it, behind it");
        eq(lane.tiles().size(), 15);
        AoEShapes.Line diagonal = new AoEShapes.Line(1, 1, 4, 0.6);
        check(diagonal.contains(2, 2) && diagonal.contains(0.5, 0.5), "the diagonal bar");
        check(!diagonal.contains(0, 1) && !diagonal.contains(4, 4), "wide of and past the diagonal bar");

        // Every shape enumerates its own tiles, once each, in a stable order.
        for (AoEShapes.Shape shape : List.of(new AoEShapes.Circle(2.5), east, north, lane, diagonal)) {
            List<int[]> tiles = shape.tiles();
            Set<Long> seen = new HashSet<>();
            for (int[] tile : tiles) {
                check(shape.contains(tile[0], tile[1]), "an enumerated tile is inside");
                check(seen.add(((long) tile[0] << 32) | (tile[1] & 0xFFFFFFFFL)), "each tile once");
            }
        }

        System.out.println("AoEShapesTest passed");
    }

    private static void eq(long actual, long expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
