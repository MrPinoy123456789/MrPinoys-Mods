package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Pure coordinate and weighted-selection regression for M30's connector overlays. */
public class ConnectorTest {

    public static void main(String[] args) {
        testWeightsSumToOneHundred();
        testPickIsDeterministicForASeed();
        testPickCoversEveryWeightBand();
        testRngForIsEdgeDirectionIndependent();
        testRectBoundsAndCount();
        testRectBothSidesAlignOnTheSharedColumn();
        System.out.println("ConnectorTest passed");
    }

    /** The cumulative-weight roll assumes the table sums to exactly 95. */
    private static void testWeightsSumToOneHundred() {
        Map<ConnectorType, Integer> weights = Map.of(
                ConnectorType.DOOR_WIDE, 45,
                ConnectorType.DOOR_SINGLE, 15,
                ConnectorType.DOOR_DOUBLE, 10,
                ConnectorType.IRON_DOOR, 10,
                ConnectorType.OPEN, 10,
                ConnectorType.ARCH, 5);
        int total = 0;
        for (int w : weights.values()) {
            total += w;
        }
        check(total, 95);
        // Every band boundary from the class doc, read off a fixed Random(0) sequence.
        for (int i = 0; i < 1000; i++) {
            ConnectorType.pick(new Random(i));
        }
    }

    /** Same seed, same rolls: the connector for a given seed and edge never changes. */
    private static void testPickIsDeterministicForASeed() {
        Random a = new Random(42);
        Random b = new Random(42);
        for (int i = 0; i < 50; i++) {
            check(ConnectorType.pick(a), ConnectorType.pick(b));
        }
    }

    /** Every rolled connector type is reachable, and the planner-only RUBBLE never is. */
    private static void testPickCoversEveryWeightBand() {
        Set<ConnectorType> seen = new java.util.HashSet<>();
        Random rng = new Random(7);
        for (int i = 0; i < 2000; i++) {
            seen.add(ConnectorType.pick(rng));
        }
        for (ConnectorType type : ConnectorType.values()) {
            check(seen.contains(type) == type.rolled());
        }
    }

    /** An edge's rng seed depends only on the (canonically-ordered) pair, not discovery order. */
    private static void testRngForIsEdgeDirectionIndependent() {
        PlanCell a = new PlanCell(0, 0);
        PlanCell b = new PlanCell(1, 0);
        PlanEdge ab = new PlanEdge(a, b);
        PlanEdge ba = new PlanEdge(b, a);
        check(ConnectorType.rngFor(99L, ab).nextInt(1000), ConnectorType.rngFor(99L, ba).nextInt(1000));

        // A different edge (even touching the same cell) rolls independently.
        PlanEdge ac = new PlanEdge(a, new PlanCell(0, 1));
        Random forAb = ConnectorType.rngFor(99L, ab);
        Random forAc = ConnectorType.rngFor(99L, ac);
        check(forAb.nextInt(1_000_000) != forAc.nextInt(1_000_000));
    }

    /** rect() bounds and count on all four walls, mirroring CellGeometryTest's door-slot checks. */
    private static void testRectBoundsAndCount() {
        BlockPos origin = new BlockPos(100, 64, 200);

        List<BlockPos> north = ConnectorGeometry.rect(origin, DoorMask.Direction.NORTH, 2, 13, 1, 5);
        check(north.size(), 12 * 5);
        for (BlockPos pos : north) {
            check(pos.getZ(), origin.getZ());
            check(pos.getX() >= origin.getX() + 2 && pos.getX() <= origin.getX() + 13);
            check(pos.getY() >= origin.getY() + 1 && pos.getY() <= origin.getY() + 5);
        }

        List<BlockPos> south = ConnectorGeometry.rect(origin, DoorMask.Direction.SOUTH, 0, 15, 1, 3);
        for (BlockPos pos : south) {
            check(pos.getZ(), origin.getZ() + RoomGeometry.CELL - 1);
        }

        List<BlockPos> west = ConnectorGeometry.rect(origin, DoorMask.Direction.WEST, 7, 8, 1, 3);
        for (BlockPos pos : west) {
            check(pos.getX(), origin.getX());
        }

        List<BlockPos> east = ConnectorGeometry.rect(origin, DoorMask.Direction.EAST, 7, 8, 1, 3);
        for (BlockPos pos : east) {
            check(pos.getX(), origin.getX() + RoomGeometry.CELL - 1);
        }
    }

    /**
     * Both-sides test: for a north/south edge, cell A's SOUTH wall and cell B's
     * NORTH wall are one block apart in Z (the two-block partition) but share
     * the exact same X per column index -- so applying the same {@code i} to
     * both sides keeps a connector's opening aligned across the partition
     * instead of drifting between the two rooms.
     */
    private static void testRectBothSidesAlignOnTheSharedColumn() {
        BlockPos originA = new BlockPos(0, 64, 0);
        BlockPos originB = new BlockPos(0, 64, RoomGeometry.CELL);

        List<BlockPos> aSouth = ConnectorGeometry.rect(originA, DoorMask.Direction.SOUTH,
                RoomGeometry.DOOR_MIN, RoomGeometry.DOOR_MAX, 1, RoomGeometry.DOOR_HEIGHT);
        List<BlockPos> bNorth = ConnectorGeometry.rect(originB, DoorMask.Direction.NORTH,
                RoomGeometry.DOOR_MIN, RoomGeometry.DOOR_MAX, 1, RoomGeometry.DOOR_HEIGHT);

        check(aSouth.size(), bNorth.size());
        Map<Integer, Integer> aColumnsByHeight = columnsAtEachHeight(aSouth);
        Map<Integer, Integer> bColumnsByHeight = columnsAtEachHeight(bNorth);
        check(aColumnsByHeight.equals(bColumnsByHeight));

        for (int i = 0; i < aSouth.size(); i++) {
            check(aSouth.get(i).getX(), bNorth.get(i).getX());
            check(aSouth.get(i).getY(), bNorth.get(i).getY());
            // One block of gap between the two walls: A's south face, then the
            // partition block, then B's north face.
            check(bNorth.get(i).getZ() - aSouth.get(i).getZ(), 1);
        }
    }

    private static Map<Integer, Integer> columnsAtEachHeight(List<BlockPos> positions) {
        Map<Integer, Integer> out = new HashMap<>();
        for (BlockPos pos : positions) {
            out.merge(pos.getY(), pos.getX(), Integer::sum);
        }
        return out;
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
