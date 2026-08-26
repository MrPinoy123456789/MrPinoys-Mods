package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

public class CellGeometryTest {

    public static void main(String[] args) {
        testDoorSlotPositions();
        testRotationToFace();
        testOpposite();
        testOffsetInDirection();
        testNeighbourCell();
        testInsideAnyCell();
        testCellBounds();
        testStandingNeighboursAndTerminalEntrance();
        System.out.println("CellGeometryTest passed");
    }

    /** Door slot positions on all four walls: 2 wide, 3 tall, centred on the edge. */
    private static void testDoorSlotPositions() {
        BlockPos origin = new BlockPos(100, 64, 200);

        List<BlockPos> north = CellGeometry.doorSlotPositions(origin, DoorMask.Direction.NORTH);
        check(north.size(), 6);
        for (BlockPos pos : north) {
            check(pos.getZ(), origin.getZ());
            check(pos.getX() >= origin.getX() + RoomGeometry.DOOR_MIN
                    && pos.getX() <= origin.getX() + RoomGeometry.DOOR_MAX);
            check(pos.getY() >= origin.getY() + 1 && pos.getY() <= origin.getY() + RoomGeometry.DOOR_HEIGHT);
        }

        List<BlockPos> south = CellGeometry.doorSlotPositions(origin, DoorMask.Direction.SOUTH);
        for (BlockPos pos : south) {
            check(pos.getZ(), origin.getZ() + RoomGeometry.CELL - 1);
        }

        List<BlockPos> west = CellGeometry.doorSlotPositions(origin, DoorMask.Direction.WEST);
        for (BlockPos pos : west) {
            check(pos.getX(), origin.getX());
            check(pos.getZ() >= origin.getZ() + RoomGeometry.DOOR_MIN
                    && pos.getZ() <= origin.getZ() + RoomGeometry.DOOR_MAX);
        }

        List<BlockPos> east = CellGeometry.doorSlotPositions(origin, DoorMask.Direction.EAST);
        for (BlockPos pos : east) {
            check(pos.getX(), origin.getX() + RoomGeometry.CELL - 1);
        }

        // Every wall carries the same count: DOOR_HEIGHT rows * (DOOR_MAX - DOOR_MIN + 1) columns.
        int expectedCount = RoomGeometry.DOOR_HEIGHT * (RoomGeometry.DOOR_MAX - RoomGeometry.DOOR_MIN + 1);
        check(north.size(), expectedCount);
        check(south.size(), expectedCount);
        check(west.size(), expectedCount);
        check(east.size(), expectedCount);
    }

    /** All sixteen (originalWall, targetWall) pairs -- the clockwise step count between them. */
    private static void testRotationToFace() {
        DoorMask.Direction[] dirs = DoorMask.Direction.values();
        // NORTH=0, EAST=1, SOUTH=2, WEST=3 in clockwise order -- same wall is 0 steps,
        // and the table is symmetric under "same relative step" for every starting wall.
        for (DoorMask.Direction from : dirs) {
            check(CellGeometry.rotationToFace(from, from), 0);
        }

        check(CellGeometry.rotationToFace(DoorMask.Direction.NORTH, DoorMask.Direction.EAST), 1);
        check(CellGeometry.rotationToFace(DoorMask.Direction.NORTH, DoorMask.Direction.SOUTH), 2);
        check(CellGeometry.rotationToFace(DoorMask.Direction.NORTH, DoorMask.Direction.WEST), 3);
        check(CellGeometry.rotationToFace(DoorMask.Direction.EAST, DoorMask.Direction.SOUTH), 1);
        check(CellGeometry.rotationToFace(DoorMask.Direction.EAST, DoorMask.Direction.WEST), 2);
        check(CellGeometry.rotationToFace(DoorMask.Direction.EAST, DoorMask.Direction.NORTH), 3);
        check(CellGeometry.rotationToFace(DoorMask.Direction.SOUTH, DoorMask.Direction.WEST), 1);
        check(CellGeometry.rotationToFace(DoorMask.Direction.SOUTH, DoorMask.Direction.NORTH), 2);
        check(CellGeometry.rotationToFace(DoorMask.Direction.SOUTH, DoorMask.Direction.EAST), 3);
        check(CellGeometry.rotationToFace(DoorMask.Direction.WEST, DoorMask.Direction.NORTH), 1);
        check(CellGeometry.rotationToFace(DoorMask.Direction.WEST, DoorMask.Direction.EAST), 2);
        check(CellGeometry.rotationToFace(DoorMask.Direction.WEST, DoorMask.Direction.SOUTH), 3);

        // Every result stays in [0, 3] across all sixteen pairs.
        for (DoorMask.Direction from : dirs) {
            for (DoorMask.Direction to : dirs) {
                int rotation = CellGeometry.rotationToFace(from, to);
                check(rotation >= 0 && rotation <= 3);
            }
        }
    }

    private static void testOpposite() {
        check(CellGeometry.opposite(DoorMask.Direction.NORTH), DoorMask.Direction.SOUTH);
        check(CellGeometry.opposite(DoorMask.Direction.SOUTH), DoorMask.Direction.NORTH);
        check(CellGeometry.opposite(DoorMask.Direction.EAST), DoorMask.Direction.WEST);
        check(CellGeometry.opposite(DoorMask.Direction.WEST), DoorMask.Direction.EAST);
        // Involution: opposite(opposite(d)) == d for every direction.
        for (DoorMask.Direction dir : DoorMask.Direction.values()) {
            check(CellGeometry.opposite(CellGeometry.opposite(dir)), dir);
        }
    }

    private static void testOffsetInDirection() {
        BlockPos origin = new BlockPos(0, 64, 0);
        check(CellGeometry.offsetInDirection(origin, DoorMask.Direction.NORTH, 16), new BlockPos(0, 64, -16));
        check(CellGeometry.offsetInDirection(origin, DoorMask.Direction.SOUTH, 16), new BlockPos(0, 64, 16));
        check(CellGeometry.offsetInDirection(origin, DoorMask.Direction.EAST, 16), new BlockPos(16, 64, 0));
        check(CellGeometry.offsetInDirection(origin, DoorMask.Direction.WEST, 16), new BlockPos(-16, 64, 0));
    }

    /** Neighbour cells at and across the origin, including negative grid coordinates. */
    private static void testNeighbourCell() {
        PlanCell origin = new PlanCell(0, 0);
        check(CellGeometry.neighbourCell(origin, DoorMask.Direction.NORTH), new PlanCell(0, -1));
        check(CellGeometry.neighbourCell(origin, DoorMask.Direction.SOUTH), new PlanCell(0, 1));
        check(CellGeometry.neighbourCell(origin, DoorMask.Direction.EAST), new PlanCell(1, 0));
        check(CellGeometry.neighbourCell(origin, DoorMask.Direction.WEST), new PlanCell(-1, 0));

        // A cell already negative on both axes -- the layout graph routinely
        // produces these (PlanGeometry's class doc), so the arithmetic has to
        // keep working past zero in both directions, not just away from it.
        PlanCell negative = new PlanCell(-3, -5);
        check(CellGeometry.neighbourCell(negative, DoorMask.Direction.NORTH), new PlanCell(-3, -6));
        check(CellGeometry.neighbourCell(negative, DoorMask.Direction.WEST), new PlanCell(-4, -5));
    }

    private static void testInsideAnyCell() {
        BlockPos cellOrigin = new BlockPos(0, 64, 0);
        List<BlockPos> cells = List.of(cellOrigin);

        // Floor corner: inside.
        check(CellGeometry.insideAnyCell(cellOrigin, cells));
        // Opposite corner of the same cell, at the ceiling: still inside.
        check(CellGeometry.insideAnyCell(
                cellOrigin.offset(RoomGeometry.CELL - 1, RoomGeometry.CEILING_Y, RoomGeometry.CELL - 1), cells));
        // One block past the cell on every axis: outside.
        check(!CellGeometry.insideAnyCell(cellOrigin.offset(RoomGeometry.CELL, 0, 0), cells));
        check(!CellGeometry.insideAnyCell(cellOrigin.offset(0, RoomGeometry.CEILING_Y + 1, 0), cells));
        check(!CellGeometry.insideAnyCell(cellOrigin.offset(-1, 0, 0), cells));
        check(!CellGeometry.insideAnyCell(cellOrigin.offset(0, -1, 0), cells));

        // No cells at all: nothing is inside anything.
        check(!CellGeometry.insideAnyCell(cellOrigin, List.of()));
    }

    private static void testCellBounds() {
        BlockPos origin = new BlockPos(0, 64, 0);
        AABB bounds = CellGeometry.cellBounds(origin);
        check(bounds.minX == 0 && bounds.minY == 64 && bounds.minZ == 0);
        check(bounds.maxX == RoomGeometry.CELL);
        check(bounds.maxY == 64 + RoomGeometry.CEILING_Y + 1);
        check(bounds.maxZ == RoomGeometry.CELL);
    }

    /** A three-cell straight corridor: standingNeighbours and terminalEntranceDirection agree. */
    private static void testStandingNeighboursAndTerminalEntrance() {
        BlockPos origin = new BlockPos(0, 64, 0);
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell middle = new PlanCell(0, 1);
        PlanCell terminal = new PlanCell(0, 2);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(entrance, middle, terminal));

        BlockPos middleOrigin = geometry.cellOrigin(middle);
        Set<DoorMask.Direction> neighbours = CellGeometry.standingNeighbours(geometry, middleOrigin);
        check(neighbours.size(), 2);
        check(neighbours.contains(DoorMask.Direction.NORTH));
        check(neighbours.contains(DoorMask.Direction.SOUTH));

        BlockPos terminalOrigin = geometry.cellOrigin(terminal);
        Set<DoorMask.Direction> terminalNeighbours = CellGeometry.standingNeighbours(geometry, terminalOrigin);
        check(terminalNeighbours.size(), 1);
        check(terminalNeighbours.contains(DoorMask.Direction.NORTH));

        // The terminal cell's only standing neighbour is north, so the party
        // walked in from the north and the far wall to open is south.
        check(CellGeometry.terminalEntranceDirection(geometry, terminalOrigin), DoorMask.Direction.NORTH);

        // A cell with no standing neighbour at all falls back to SOUTH.
        BlockPos isolated = origin.offset(1000, 0, 1000);
        check(CellGeometry.terminalEntranceDirection(geometry, isolated), DoorMask.Direction.SOUTH);
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
