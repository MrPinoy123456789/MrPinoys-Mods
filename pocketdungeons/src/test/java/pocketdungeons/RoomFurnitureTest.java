package pocketdungeons;

import net.minecraft.core.BlockPos;

/**
 * Pure coordinate regression for {@link RoomProtection#isFurniture} (M19
 * 19.7): the mod-placed furniture is the three selector doors at Y=1..2
 * standing one block in front of the selector wall, the commit lever and
 * its sign in the same row beside the third door, the three copper bulbs
 * and the black concrete door screen set into that wall, and the engine
 * block with its screen on the wall to the left of the selector wall, and
 * the go-home control at the far left of the doors (its lever and sign in the door row,
 * its screen and bulb in the wall). Nothing else in the room is furniture. Runs headless like
 * {@link RoomShellTest}.
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
            testHomeControl(wall);
            testHistoryPanelOnLeftWall(wall);
            testInteriorIsNeverFurniture(wall);
            testPreviewSideWindowIsClear(wall);
        }
        testOutsideRoomIsNeverFurniture();
        testFurnitureDependsOnSelectorWall();
        testOffsetOrigin();
        System.out.println("RoomFurnitureTest passed");
    }

    /**
     * The lever at Y=2 and its sign at Y=3, one block in front of the wall,
     * the three selector doors at Y=1..2 in the same row, and the three
     * bulbs at Y=3 set into the wall itself, one over each door.
     */
    private static void testBulbsAndLever(DoorMask.Direction wall) {
        for (int along = 7; along <= 9; along++) {
            check(isFurniture(wallRing(wall, along, 3), wall), "bulb " + wall + " " + along);
        }
        check(isFurniture(doorPlane(wall, seen(wall, 10), 2), wall), "lever " + wall);
        check(isFurniture(doorPlane(wall, seen(wall, 10), 3), wall), "lever sign " + wall);
        // Nothing stands over the lever in the wall any more: the fourth bulb
        // came out when the sign went up.
        check(!isFurniture(wallRing(wall, seen(wall, 10), 3), wall), "no bulb over the lever " + wall);
        // The three selector doors themselves (Y=1..2, along 7..9) are
        // furniture: the owner cannot break them. The air above them at Y=3
        // in front of the wall is not furniture.
        for (int along = 7; along <= 9; along++) {
            check(isFurniture(doorPlane(wall, along, 1), wall), "selector door " + wall + " " + along);
            check(isFurniture(doorPlane(wall, along, 2), wall), "selector door " + wall + " " + along);
            check(!isFurniture(doorPlane(wall, along, 3), wall), "over door " + wall + " " + along);
        }
    }

    /** The 10x2 black concrete screen is set into the wall itself, above the door row. */
    private static void testDoorScreenBlocks(DoorMask.Direction wall) {
        check(RoomTemplateGenerator.DOOR_SCREEN_ALONG_MIN + RoomTemplateGenerator.DOOR_SCREEN_ALONG_MAX == 15,
                "the screen is centred on the wall's middle, boundary 8.0");
        for (int along = RoomTemplateGenerator.DOOR_SCREEN_ALONG_MIN;
             along <= RoomTemplateGenerator.DOOR_SCREEN_ALONG_MAX; along++) {
            for (int y = 4; y <= 5; y++) {
                check(isFurniture(wallRing(wall, along, y), wall), "screen " + wall + " " + along + " " + y);
            }
        }
        // The screen runs 3..12; 1 and 14 either side are plain protected
        // wall, and the go-home bulb (absolute 2, or 13 mirrored) stays out of
        // the span on every wall.
        check(!isFurniture(wallRing(wall, 14, 4), wall), "screen edge " + wall);
        check(!isFurniture(wallRing(wall, 1, 4), wall), "screen edge " + wall);
        int bulb = RoomGeometry.mirrorsAlong(wall) ? 13 : 2;
        check(bulb < RoomTemplateGenerator.DOOR_SCREEN_ALONG_MIN || bulb > RoomTemplateGenerator.DOOR_SCREEN_ALONG_MAX,
                "the go-home bulb is outside the screen " + wall);
    }

    /**
     * The go-home control stands at the far end of the selector wall from
     * the commit lever: a lever at along 2, Y=2 with its sign at Y=3 in the
     * door row, and in the wall a 3x3 screen at along 3..5, Y=1..3 and a bulb
     * at along 2, Y=4 over the lever. The positions must agree with
     * {@code RoomTemplateGenerator}'s {@code HOME_*} constants.
     */
    private static void testHomeControl(DoorMask.Direction wall) {
        check(RoomTemplateGenerator.HOME_LEVER_ALONG == 2 && RoomTemplateGenerator.HOME_BULB_ALONG == 2
                && RoomTemplateGenerator.HOME_BULB_Y == 4, "placement constants match the protected positions");
        check(isFurniture(doorPlane(wall, seen(wall, 2), 2), wall), "home lever " + wall);
        check(isFurniture(doorPlane(wall, seen(wall, 2), 3), wall), "home sign " + wall);
        // The Astrolabe Room's row (design pass 2026-10-09) protects its own door places, which include the
        // ones the home control uses in a cleared floor's staging room. The two never stand in the same room
        // (the row is only in a trip's first staging room), so the protection does not tell them apart.
        // Playtest 2026-09-29 (A5): the door row between the home lever and the nearest three-door selector
        // door is still empty, at least four blocks of it on every wall, so a misclick never lands on the lever.
        int nearestDoor = RoomGeometry.mirrorsAlong(wall) ? seen(wall, 9) : 7;
        check(nearestDoor - RoomTemplateGenerator.HOME_LEVER_ALONG >= 4,
                "home lever well clear of the doors " + wall);
        // The hall row: its doors (Y1..2), the sign in front of each (Y1, one block inward) and the bulbs (Y3).
        for (int rel : HallLayout.DOOR_SPACES) {
            check(isFurniture(doorPlane(wall, seen(wall, rel), 1), wall), "hall door " + wall + " " + rel);
            check(isFurniture(doorPlane(wall, seen(wall, rel), 2), wall), "hall door top " + wall + " " + rel);
            check(isFurniture(wallRing(wall, seen(wall, rel), 3), wall), "hall bulb " + wall + " " + rel);
        }
        for (int along = 3; along <= 5; along++) {
            for (int y = 1; y <= 3; y++) {
                check(isFurniture(wallRing(wall, seen(wall, along), y), wall), "home screen " + wall + " " + along + " " + y);
            }
        }
        check(isFurniture(wallRing(wall, seen(wall, 2), 4), wall), "home bulb " + wall);
        check(!isFurniture(wallRing(wall, seen(wall, 2), 2), wall), "home screen edge " + wall);
        check(!isFurniture(wallRing(wall, seen(wall, 6), 1), wall), "home screen edge " + wall);
    }

    /**
     * The floor history board sits on the wall to the left of the selector
     * wall, where the echo shard engine was: a panel along 4..11, rows 2..5.
     */
    private static void testHistoryPanelOnLeftWall(DoorMask.Direction wall) {
        DoorMask.Direction historyWall = RoomGeometry.leftOf(wall);
        for (int along = 4; along <= 11; along++) {
            for (int y = 2; y <= 5; y++) {
                check(isFurniture(wallRing(historyWall, along, y), wall), "history panel " + along + "," + y + " " + wall);
            }
        }
        check(!isFurniture(wallRing(historyWall, 3, 3), wall), "panel edge " + wall);
        check(!isFurniture(wallRing(historyWall, 12, 3), wall), "panel edge " + wall);
        check(!isFurniture(wallRing(historyWall, 7, 1), wall), "below the panel " + wall);
    }

    /**
     * Interior build space is never furniture, whatever the selector wall,
     * except for the selector doors, the lever, and the lever sign on the
     * door plane one block in front of the selector wall.
     */
    private static void testInteriorIsNeverFurniture(DoorMask.Direction wall) {
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = 1; y <= 5; y++) {
                    if (isFurniture(new BlockPos(x, 64 + y, z), ORIGIN, wall)) {
                        // The only interior cells that are furniture are on
                        // the door plane, one block in front of the selector
                        // wall: the three selector doors (along 7..9, Y=1..2),
                        // the commit and go-home levers (seen along 10 and 2,
                        // Y=2), and their signs (Y=3). The bulbs, the engine
                        // and the screens sit in the wall ring, never in the
                        // interior.
                        int along = alongOf(wall, x, z);
                        int seenAlong = seen(wall, along);
                        int perp = (wall == DoorMask.Direction.NORTH || wall == DoorMask.Direction.SOUTH) ? z : x;
                        boolean onDoorPlane = perp == doorPlaneOf(wall);
                        boolean selectorDoor = onDoorPlane && (y == 1 || y == 2) && along >= 7 && along <= 9;
                        boolean lever = onDoorPlane && y == 2 && (seenAlong == 10 || seenAlong == 2);
                        boolean sign = onDoorPlane && y == 3 && (seenAlong == 10 || seenAlong == 2);
                        // The Astrolabe Room's row: its doors on the door plane and the sign one block inward.
                        boolean hallAlong = HallLayout.isHallAlong(RoomGeometry.mirrorsAlong(wall), along);
                        boolean hallDoor = onDoorPlane && (y == 1 || y == 2) && hallAlong;
                        int inward = doorPlaneOf(wall) == 1 ? 2 : RoomGeometry.CELL - 3;
                        boolean hallSign = perp == inward && y == 1 && hallAlong;
                        if (!selectorDoor && !lever && !sign && !hallDoor && !hallSign) {
                            throw new AssertionError("interior should never be furniture: "
                                    + wall + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }

    /**
     * PD-85: the door preview's side window column (along 6, Y=1..3) is clear
     * on every selector wall, in the wall and in the row in front of it, so
     * nothing mod-placed stands between the player and the glass. The go-home
     * screen and the commit lever are viewer mirrored and must never land
     * there.
     */
    private static void testPreviewSideWindowIsClear(DoorMask.Direction wall) {
        int along = RoomBuilder.PREVIEW_WINDOW_ALONG;
        check(along < RoomGeometry.DOOR_MIN, "side window beside the door slot, not in it");
        // The Astrolabe Room's row (design pass 2026-10-09) stands a door and a bulb at this column on every
        // wall (space 6 or 9 of its pattern), so the window's top course holds a bulb and a door stands in
        // front of it. The wall column below the bulb stays clear, and the doorway slot itself is the view.
        for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT - 1; y++) {
            check(!isFurniture(wallRing(wall, along, y), wall), "side window clear in the wall " + wall + " " + y);
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
        check(isFurniture(origin.offset(7, 3, 15), origin, DoorMask.Direction.SOUTH));
        check(isFurniture(origin.offset(13, 2, 14), origin, DoorMask.Direction.SOUTH)); // the home lever
        check(isFurniture(origin.offset(13, 3, 14), origin, DoorMask.Direction.SOUTH)); // its sign
        check(isFurniture(origin.offset(15, 2, 7), origin, DoorMask.Direction.SOUTH));
        check(!isFurniture(origin.offset(5, 2, 5), origin, DoorMask.Direction.SOUTH));
    }

    private static boolean isFurniture(BlockPos pos, BlockPos origin, DoorMask.Direction wall) {
        return RoomProtection.isFurniture(pos, origin, wall);
    }

    /** The wall-relative overload used by the per-wall geometry helpers below. */
    private static boolean isFurniture(BlockPos pos, DoorMask.Direction wall) {
        return RoomProtection.isFurniture(pos, ORIGIN, wall);
    }

    // ---- geometry mirrors of the protected positions -------------------------

    /** PD-70: a viewer-relative along (counted from the viewer's left) as the absolute one. */
    private static int seen(DoorMask.Direction wall, int along) {
        return RoomGeometry.viewerAlong(wall, along);
    }

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
        // ORIGIN is already at Y=64, so the offsets here are relative to it:
        // the door row sits at local Y, and the room-local y is origin.y + y.
        return switch (wall) {
            case NORTH -> ORIGIN.offset(along, y, 1);
            case SOUTH -> ORIGIN.offset(along, y, RoomGeometry.CELL - 2);
            case EAST -> ORIGIN.offset(RoomGeometry.CELL - 2, y, along);
            case WEST -> ORIGIN.offset(1, y, along);
        };
    }

    private static BlockPos wallRing(DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> ORIGIN.offset(along, y, 0);
            case SOUTH -> ORIGIN.offset(along, y, RoomGeometry.CELL - 1);
            case EAST -> ORIGIN.offset(RoomGeometry.CELL - 1, y, along);
            case WEST -> ORIGIN.offset(0, y, along);
        };
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Expected furniture at " + what);
        }
    }

    private static void check(boolean condition) {
        check(condition, "the position under test");
    }
}
