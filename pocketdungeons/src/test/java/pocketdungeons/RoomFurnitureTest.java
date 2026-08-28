package pocketdungeons;

import net.minecraft.core.BlockPos;

/**
 * Pure coordinate regression for {@link RoomProtection#isFurniture} (M19
 * 19.7): the mod-placed furniture is the four copper bulbs and the commit
 * lever in the row in front of the selector wall, the black concrete door
 * screen set into that wall, and the engine block with its screen on the
 * wall to the left of the selector wall. Nothing else in the room is
 * furniture. Runs headless like {@link RoomShellTest}.
 *
 * <p>The positions here must stay in lockstep with
 * {@code RoomTemplateGenerator.placeFurniture} and
 * {@code RoomTemplateGenerator.clearFurniture}: the three read the same
 * wall-relative coordinates. A failure of this test after a geometry edit
 * means the protection and the placement have drifted apart.
 */
public class RoomFurnitureTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    public static void main(String[] args) {
        for (DoorMask.Direction wall : DoorMask.Direction.values()) {
            testBulbsAndLever(wall);
            testDoorScreenBlocks(wall);
            testEngineOnLeftWall(wall);
            testInteriorIsNeverFurniture(wall);
        }
        testOutsideRoomIsNeverFurniture();
        testFurnitureDependsOnSelectorWall();
        testOffsetOrigin();
        System.out.println("RoomFurnitureTest passed");
    }

    /** The four bulbs at Y=4 and the lever at Y=2, one block in front of the wall. */
    private static void testBulbsAndLever(DoorMask.Direction wall) {
        for (int along = 7; along <= 10; along++) {
            check(isFurniture(doorPlane(wall, along, 4)), "bulb " + wall + " " + along);
        }
        check(isFurniture(doorPlane(wall, 10, 2)), "lever " + wall);
        // The door row itself (Y=1..2, along 7..9) is a door, not furniture.
        for (int along = 7; along <= 9; along++) {
            check(!isFurniture(doorPlane(wall, along, 1)), "door " + wall + " " + along);
            check(!isFurniture(doorPlane(wall, along, 2)), "door " + wall + " " + along);
        }
    }

    /** The 8x2 black concrete screen is set into the wall itself, above the door row. */
    private static void testDoorScreenBlocks(DoorMask.Direction wall) {
        for (int along = 4; along <= 11; along++) {
            for (int y = 4; y <= 5; y++) {
                check(isFurniture(wallRing(wall, along, y)), "screen " + wall + " " + along + " " + y);
            }
        }
        // The screen stops at 11; 12 is plain protected wall, not furniture.
        check(!isFurniture(wallRing(wall, 12, 4)), "screen edge " + wall);
    }

    /** The engine anchor and its screen sit on the wall to the left of the selector wall. */
    private static void testEngineOnLeftWall(DoorMask.Direction wall) {
        DoorMask.Direction engineWall = RoomGeometry.leftOf(wall);
        check(isFurniture(wallRing(engineWall, 7, 2)), "engine " + wall);
        for (int along = 7; along <= 8; along++) {
            for (int y = 4; y <= 5; y++) {
                check(isFurniture(wallRing(engineWall, along, y)), "engine screen " + wall);
            }
        }
    }

    /** Interior build space is never furniture, whatever the selector wall. */
    private static void testInteriorIsNeverFurniture(DoorMask.Direction wall) {
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = 1; y <= 5; y++) {
                    if (isFurniture(new BlockPos(x, 64 + y, z), ORIGIN, wall)) {
                        // The only interior cells that are furniture are the
                        // bulb row and the lever on the door plane, one block
                        // in front of the selector wall. The engine and its
                        // screen sit in the wall ring, never in the interior.
                        int along = alongOf(wall, x, z);
                        int perp = (wall == DoorMask.Direction.NORTH || wall == DoorMask.Direction.SOUTH) ? z : x;
                        boolean onDoorPlane = perp == doorPlaneOf(wall);
                        boolean bulb = onDoorPlane && y == 4 && along >= 7 && along <= 10;
                        boolean lever = onDoorPlane && y == 2 && along == 10;
                        if (!bulb && !lever) {
                            throw new AssertionError("interior should never be furniture: "
                                    + wall + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }

    /** Outside the room's own 16x16x7 box, nothing is furniture. */
    private static void testOutsideRoomIsNeverFurniture() {
        check(!isFurniture(new BlockPos(-1, 64, 0), ORIGIN, DoorMask.Direction.SOUTH));
        check(!isFurniture(new BlockPos(16, 64, 0), ORIGIN, DoorMask.Direction.SOUTH));
        check(!isFurniture(new BlockPos(0, 64, 16), ORIGIN, DoorMask.Direction.SOUTH));
        check(!isFurniture(new BlockPos(0, 63, 7), ORIGIN, DoorMask.Direction.SOUTH));
        check(!isFurniture(new BlockPos(0, 71, 7), ORIGIN, DoorMask.Direction.SOUTH));
    }

    /** The same world position is furniture under one selector wall and plain wall under another. */
    private static void testFurnitureDependsOnSelectorWall() {
        // (15, 2, 7) is the engine block when the selector wall is SOUTH
        // (engine on the east wall), and nothing at all when it is NORTH
        // (engine on the west wall).
        check(isFurniture(new BlockPos(15, 66, 7), ORIGIN, DoorMask.Direction.SOUTH));
        check(!isFurniture(new BlockPos(15, 66, 7), ORIGIN, DoorMask.Direction.NORTH));
        // (0, 2, 7) is the engine block when the selector wall is NORTH.
        check(isFurniture(new BlockPos(0, 66, 7), ORIGIN, DoorMask.Direction.NORTH));
        check(!isFurniture(new BlockPos(0, 66, 7), ORIGIN, DoorMask.Direction.SOUTH));
    }

    /** The test is against the room origin, not world zero: offsets must shift the box. */
    private static void testOffsetOrigin() {
        BlockPos origin = new BlockPos(100, 32, -200);
        check(isFurniture(origin.offset(7, 4, 14), origin, DoorMask.Direction.SOUTH));
        check(isFurniture(origin.offset(10, 2, 14), origin, DoorMask.Direction.SOUTH));
        check(isFurniture(origin.offset(15, 2, 7), origin, DoorMask.Direction.SOUTH));
        check(!isFurniture(origin.offset(5, 2, 5), origin, DoorMask.Direction.SOUTH));
    }

    private static boolean isFurniture(BlockPos pos, BlockPos origin, DoorMask.Direction wall) {
        return RoomProtection.isFurniture(pos, origin, wall);
    }

    // ---- geometry mirrors of the protected positions -------------------------

    private static int doorPlaneOf(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH, WEST -> 1;
            case SOUTH, EAST -> RoomGeometry.CELL - 2;
        };
    }

    private static int alongOf(DoorMask.Direction wall, int x, int z) {
        return switch (wall) {
            case NORTH, SOUTH -> x;
            case EAST, WEST -> z;
        };
    }

    private static BlockPos doorPlane(DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> ORIGIN.offset(along, 64 + y, 1);
            case SOUTH -> ORIGIN.offset(along, 64 + y, RoomGeometry.CELL - 2);
            case EAST -> ORIGIN.offset(RoomGeometry.CELL - 2, 64 + y, along);
            case WEST -> ORIGIN.offset(1, 64 + y, along);
        };
    }

    private static BlockPos wallRing(DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> ORIGIN.offset(along, 64 + y, 0);
            case SOUTH -> ORIGIN.offset(along, 64 + y, RoomGeometry.CELL - 1);
            case EAST -> ORIGIN.offset(RoomGeometry.CELL - 1, 64 + y, along);
            case WEST -> ORIGIN.offset(0, 64 + y, along);
        };
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Expected furniture at " + what);
        }
    }
}
