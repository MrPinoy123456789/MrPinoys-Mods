package pocketdungeons;

import net.minecraft.core.BlockPos;

/**
 * Pure coordinate regression for {@link RoomProtection#isShell} (M18 9.1): the
 * shell is the floor row, the wall ring, the ceiling row (slabs, stairs and
 * lamps included) and nothing else; the interior (x=1..14, z=1..14, Y=1..5) is
 * the owner's build space. Runs headless like {@link CellGeometryTest}.
 */
public class RoomShellTest {

    public static void main(String[] args) {
        testInteriorIsNeverShell();
        testFloorAndCeilingAreShell();
        testWallRingIsShell();
        testOutsideRoomIsNeverShell();
        testOffsetOrigin();
        System.out.println("RoomShellTest passed");
    }

    /** Every interior position (x=1..14, z=1..14, Y=1..5) is the build space. */
    private static void testInteriorIsNeverShell() {
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = 1; y <= 5; y++) {
                    check(!RoomProtection.isShell(new BlockPos(x, 64 + y, z), origin));
                }
            }
        }
    }

    /** Floor (Y=0) and ceiling (Y=6) rows are shell over the whole 16x16, lamps included. */
    private static void testFloorAndCeilingAreShell() {
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                check(RoomProtection.isShell(new BlockPos(x, 64, z), origin));
                check(RoomProtection.isShell(new BlockPos(x, 70, z), origin));
            }
        }
        // The four lamp positions sit in the ceiling row, so they are shell too.
        for (int x : new int[]{4, 11}) {
            for (int z : new int[]{4, 11}) {
                check(RoomProtection.isShell(new BlockPos(x, 70, z), origin));
            }
        }
    }

    /** The wall ring (x=0, x=15, z=0, z=15 at Y=1..5) is shell; the corners double-count. */
    private static void testWallRingIsShell() {
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int y = 1; y <= 5; y++) {
            for (int x = 0; x <= 15; x++) {
                check(RoomProtection.isShell(new BlockPos(x, 64 + y, 0), origin));
                check(RoomProtection.isShell(new BlockPos(x, 64 + y, 15), origin));
            }
            for (int z = 0; z <= 15; z++) {
                check(RoomProtection.isShell(new BlockPos(0, 64 + y, z), origin));
                check(RoomProtection.isShell(new BlockPos(15, 64 + y, z), origin));
            }
        }
    }

    /** Anything outside the room's own 16x16x7 box is never shell. */
    private static void testOutsideRoomIsNeverShell() {
        BlockPos origin = new BlockPos(0, 64, 0);
        check(!RoomProtection.isShell(new BlockPos(-1, 64, 0), origin));
        check(!RoomProtection.isShell(new BlockPos(16, 64, 0), origin));
        check(!RoomProtection.isShell(new BlockPos(0, 64, -1), origin));
        check(!RoomProtection.isShell(new BlockPos(0, 64, 16), origin));
        check(!RoomProtection.isShell(new BlockPos(0, 63, 0), origin)); // below the floor
        check(!RoomProtection.isShell(new BlockPos(0, 71, 0), origin)); // above the ceiling
    }

    /** The test is against the room origin, not world zero: offsets must shift the box. */
    private static void testOffsetOrigin() {
        BlockPos origin = new BlockPos(100, 32, -200);
        check(RoomProtection.isShell(origin.offset(7, 0, 7), origin));
        check(!RoomProtection.isShell(origin.offset(7, 1, 7), origin));
        check(RoomProtection.isShell(origin.offset(0, 3, 8), origin));
        check(RoomProtection.isShell(origin.offset(1, 6, 1), origin));
        check(!RoomProtection.isShell(origin.offset(1, 6, 16), origin));
        check(!RoomProtection.isShell(origin.offset(-1, 0, 0), origin));
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }
}
