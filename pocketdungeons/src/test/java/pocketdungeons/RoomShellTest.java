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
        // M45B: Blocks (and so the window materials) need the same bootstrap
        // ShellPaletteTest runs. Everything above is pure coordinate math and
        // does not, so it stays ahead of the cost.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        testWindowBandSitsOutsideTheDoorwayLane();
        testWindowBandIsShellAndInsideTheWall();
        testWindowBandColumnsAlignAcrossAnEdge();
        testWindowMaterialsMapToVanillaBlocks();
        testConnectorsThatOpenTheBandGetNoBand();
        System.out.println("RoomShellTest passed");
    }

    // ---- M45B: the window band ----------------------------------------------

    /**
     * The band is columns 6 and 9 and nothing else. Its middle two columns are
     * the doorway's own, so a fill that reached them would plug a doorway at
     * eye height.
     */
    private static void testWindowBandSitsOutsideTheDoorwayLane() {
        check(RoomGeometry.WINDOW_MIN == 6);
        check(RoomGeometry.WINDOW_MAX == 9);
        check(RoomGeometry.WINDOW_Y == 2);
        check(RoomGeometry.WINDOW_MIN < RoomGeometry.DOOR_MIN);
        check(RoomGeometry.WINDOW_MAX > RoomGeometry.DOOR_MAX);
        // The two columns a band may actually fill, once the doorway lane is
        // skipped, are exactly the outer pair.
        int filled = 0;
        for (int i = RoomGeometry.WINDOW_MIN; i <= RoomGeometry.WINDOW_MAX; i++) {
            if (i >= RoomGeometry.DOOR_MIN && i <= RoomGeometry.DOOR_MAX) {
                continue;
            }
            check(i == RoomGeometry.WINDOW_MIN || i == RoomGeometry.WINDOW_MAX);
            filled++;
        }
        check(filled == 2);
    }

    /**
     * Every band position is a wall-ring block of its own cell: inside the
     * shell, never in the interior, and never one step outside the cell where
     * {@link BedrockEnvelope}'s ring lives. That is what keeps the band from
     * breaching the envelope on any face.
     */
    private static void testWindowBandIsShellAndInsideTheWall() {
        BlockPos origin = new BlockPos(0, 64, 0);
        for (DoorMask.Direction wall : DoorMask.Direction.values()) {
            for (int i : new int[]{RoomGeometry.WINDOW_MIN, RoomGeometry.WINDOW_MAX}) {
                BlockPos pos = ConnectorGeometry.wallPos(origin, wall, i, RoomGeometry.WINDOW_Y);
                check(RoomProtection.isShell(pos, origin));
                int dx = pos.getX() - origin.getX();
                int dz = pos.getZ() - origin.getZ();
                check(dx >= 0 && dx <= RoomGeometry.CELL - 1);
                check(dz >= 0 && dz <= RoomGeometry.CELL - 1);
                // On the cell's own wall plane, not the block beyond it.
                check(dx == 0 || dx == RoomGeometry.CELL - 1
                        || dz == 0 || dz == RoomGeometry.CELL - 1);
            }
        }
    }

    /**
     * Two cells sharing an edge cut the same world columns, so the pair reads
     * through as one window rather than as two mismatched holes. The two wall
     * planes are one block apart across the partition; everything else about
     * the position matches.
     */
    private static void testWindowBandColumnsAlignAcrossAnEdge() {
        BlockPos north = new BlockPos(0, 64, 0);
        BlockPos south = north.offset(0, 0, RoomGeometry.CELL);
        for (int i : new int[]{RoomGeometry.WINDOW_MIN, RoomGeometry.WINDOW_MAX}) {
            BlockPos a = ConnectorGeometry.wallPos(north, DoorMask.Direction.SOUTH, i,
                    RoomGeometry.WINDOW_Y);
            BlockPos b = ConnectorGeometry.wallPos(south, DoorMask.Direction.NORTH, i,
                    RoomGeometry.WINDOW_Y);
            check(a.getX() == b.getX());
            check(a.getY() == b.getY());
            check(b.getZ() - a.getZ() == 1);
        }
        BlockPos west = new BlockPos(0, 64, 0);
        BlockPos east = west.offset(RoomGeometry.CELL, 0, 0);
        for (int i : new int[]{RoomGeometry.WINDOW_MIN, RoomGeometry.WINDOW_MAX}) {
            BlockPos a = ConnectorGeometry.wallPos(west, DoorMask.Direction.EAST, i,
                    RoomGeometry.WINDOW_Y);
            BlockPos b = ConnectorGeometry.wallPos(east, DoorMask.Direction.WEST, i,
                    RoomGeometry.WINDOW_Y);
            check(a.getZ() == b.getZ());
            check(a.getY() == b.getY());
            check(b.getX() - a.getX() == 1);
        }
    }

    /** Each authored value names a vanilla block, and {@code none} names nothing. */
    private static void testWindowMaterialsMapToVanillaBlocks() {
        check(RoomBuilder.windowMaterial(DungeonRoomMeta.WINDOW_BARS)
                .is(net.minecraft.world.level.block.Blocks.IRON_BARS));
        check(RoomBuilder.windowMaterial(DungeonRoomMeta.WINDOW_GLASS)
                .is(net.minecraft.world.level.block.Blocks.GLASS_PANE));
        check(RoomBuilder.windowMaterial(DungeonRoomMeta.WINDOW_TINTED_GLASS)
                .is(net.minecraft.world.level.block.Blocks.TINTED_GLASS));
        check(RoomBuilder.windowMaterial(DungeonRoomMeta.WINDOW_NONE) == null);
        // A value the parser would have rejected still leaves the wall alone
        // rather than throwing mid-stamp.
        check(RoomBuilder.windowMaterial("not_a_window") == null);
    }

    /**
     * A band may only go where the connector leaves columns 6 and 9 solid.
     * The three that clear them (double doors, the open middle and the arch)
     * would otherwise get bars at eye height inside an opening a player is
     * meant to walk through.
     */
    private static void testConnectorsThatOpenTheBandGetNoBand() {
        check(ConnectorStamper.leavesWindowBandSolid(ConnectorType.DOOR_WIDE));
        check(ConnectorStamper.leavesWindowBandSolid(ConnectorType.DOOR_SINGLE));
        check(ConnectorStamper.leavesWindowBandSolid(ConnectorType.IRON_DOOR));
        check(!ConnectorStamper.leavesWindowBandSolid(ConnectorType.DOOR_DOUBLE));
        check(!ConnectorStamper.leavesWindowBandSolid(ConnectorType.OPEN));
        check(!ConnectorStamper.leavesWindowBandSolid(ConnectorType.ARCH));
        // The claim above, checked against the geometry the stampers use
        // rather than restated: DOOR_SINGLE only ever fills a doorway column,
        // and DOOR_DOUBLE clears the band's own two.
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos band = ConnectorGeometry.wallPos(origin, DoorMask.Direction.NORTH,
                RoomGeometry.WINDOW_MIN, RoomGeometry.WINDOW_Y);
        check(!ConnectorGeometry.rect(origin, DoorMask.Direction.NORTH,
                RoomGeometry.DOOR_MIN, RoomGeometry.DOOR_MIN, 1, RoomGeometry.DOOR_HEIGHT)
                .contains(band));
        check(ConnectorGeometry.rect(origin, DoorMask.Direction.NORTH,
                RoomGeometry.DOOR_MIN - 1, RoomGeometry.DOOR_MIN - 1, 1, RoomGeometry.DOOR_HEIGHT)
                .contains(band));
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
