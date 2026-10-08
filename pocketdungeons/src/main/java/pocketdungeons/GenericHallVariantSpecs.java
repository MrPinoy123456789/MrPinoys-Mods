package pocketdungeons;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Theme-neutral variations of the three bare generic halls (dead end, straight, cross). They use
 * only the four re-skinned placeholders plus plain stone brick pieces, carry no dungeon binding
 * and so can be drawn by every dungeon.
 */
final class GenericHallVariantSpecs {

    private GenericHallVariantSpecs() {}

    private static final BlockState BRICK_WALL = Blocks.STONE_BRICK_WALL.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(deadEndAlcoves());
        specs.add(straightColonnade());
        specs.add(crossRotunda());
        return specs;
    }

    private static BlockState stair(Direction facing, boolean upsideDown) {
        return Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, upsideDown ? Half.TOP : Half.BOTTOM);
    }

    /** West door; a low platform and a thick end wall on the east side with three recessed alcoves. */
    private static RoomSpec deadEndAlcoves() {
        return new RoomSpec("hall_dead_end_alcoves", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(14, 2, 7))
                .spawns(new BlockPos(5, 1, 4), new BlockPos(5, 1, 11),
                        new BlockPos(9, 1, 4), new BlockPos(9, 1, 11))
                .decor((level, o) -> {
                    // Vault cove: inverted stairs step the ceiling down toward the end wall.
                    for (int z = 1; z <= 14; z++) {
                        RoomDsl.put(level, o, 12, 5, z, stair(Direction.WEST, true));
                    }
                    // Platform, one step up, then the end wall above it.
                    RoomDsl.box(level, o, 12, 1, 1, 14, 1, 14, RoomDsl.FLOOR);
                    RoomDsl.box(level, o, 13, 2, 1, 14, 5, 14, RoomDsl.WALL);
                    // Three alcoves (z 3..4, 7..8, 11..12), two deep, with an accent lintel.
                    for (int z : new int[] {3, 7, 11}) {
                        RoomDsl.box(level, o, 13, 2, z, 14, 3, z + 1, RoomDsl.AIR);
                        RoomDsl.box(level, o, 13, 4, z, 14, 4, z + 1, RoomDsl.ACCENT);
                    }
                    RoomDsl.put(level, o, 14, 4, 7, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 14, 4, 8, RoomDsl.LAMP);
                    // Two pillars flanking the middle alcove.
                    RoomDsl.column(level, o, 12, 2, 5, 6, BRICK_WALL);
                    RoomDsl.column(level, o, 12, 2, 5, 9, BRICK_WALL);
                    RoomDsl.put(level, o, 12, 5, 6, RoomDsl.ACCENT);
                    RoomDsl.put(level, o, 12, 5, 9, RoomDsl.ACCENT);
                    // Hall lamps.
                    RoomDsl.put(level, o, 6, 5, 3, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 6, 5, 12, RoomDsl.LAMP);
                });
    }

    /** West to east; two pillar rows with arches, a raised central strip and side bays. */
    private static RoomSpec straightColonnade() {
        return new RoomSpec("hall_straight_colonnade", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 2))
                .spawns(new BlockPos(5, 1, 3), new BlockPos(10, 1, 3),
                        new BlockPos(5, 1, 12), new BlockPos(10, 1, 12))
                .decor((level, o) -> {
                    // Raised central strip, one step up from the doors.
                    RoomDsl.box(level, o, 4, 1, 6, 11, 1, 9, RoomDsl.FLOOR);
                    for (int z : new int[] {5, 10}) {
                        // Architrave along the pillar row.
                        RoomDsl.box(level, o, 4, 5, z, 12, 5, z, RoomDsl.ACCENT);
                        for (int x : new int[] {4, 8, 12}) {
                            RoomDsl.column(level, o, x, 1, 4, z, RoomDsl.WALL);
                        }
                        // Arch springers: inverted stairs lean into each gap.
                        RoomDsl.put(level, o, 5, 4, z, stair(Direction.EAST, true));
                        RoomDsl.put(level, o, 7, 4, z, stair(Direction.WEST, true));
                        RoomDsl.put(level, o, 9, 4, z, stair(Direction.EAST, true));
                        RoomDsl.put(level, o, 11, 4, z, stair(Direction.WEST, true));
                    }
                    // Low dividers in the bays.
                    RoomDsl.column(level, o, 8, 1, 2, 2, BRICK_WALL);
                    RoomDsl.column(level, o, 8, 1, 2, 13, BRICK_WALL);
                    // Lamps above the nave.
                    RoomDsl.put(level, o, 6, 5, 7, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 10, 5, 8, RoomDsl.LAMP);
                });
    }

    /** Four doors; eight pillars ringing a low dais, corner buttresses and an accent ceiling ring. */
    private static RoomSpec crossRotunda() {
        return new RoomSpec("hall_cross_rotunda", EnumSet.of(Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST))
                .chests(new BlockPos(2, 1, 4))
                .spawns(new BlockPos(3, 1, 3), new BlockPos(12, 1, 3),
                        new BlockPos(3, 1, 12), new BlockPos(12, 1, 12))
                .decor((level, o) -> {
                    // Dais.
                    RoomDsl.box(level, o, 6, 1, 6, 9, 1, 9, RoomDsl.FLOOR);
                    RoomDsl.box(level, o, 7, 1, 7, 8, 1, 8, RoomDsl.ACCENT);
                    // Eight pillars, with the four lane gaps left open.
                    int[][] ring = {{4, 6}, {4, 9}, {6, 4}, {9, 4}, {11, 6}, {11, 9}, {6, 11}, {9, 11}};
                    for (int[] p : ring) {
                        RoomDsl.column(level, o, p[0], 1, 4, p[1], RoomDsl.WALL);
                        RoomDsl.put(level, o, p[0], 5, p[1], RoomDsl.ACCENT);
                    }
                    // Ceiling ring joining the pillars; lamps at the diagonals.
                    for (int i = 6; i <= 9; i++) {
                        RoomDsl.put(level, o, 4, 5, i, RoomDsl.ACCENT);
                        RoomDsl.put(level, o, 11, 5, i, RoomDsl.ACCENT);
                        RoomDsl.put(level, o, i, 5, 4, RoomDsl.ACCENT);
                        RoomDsl.put(level, o, i, 5, 11, RoomDsl.ACCENT);
                    }
                    for (int[] d : new int[][] {{5, 5}, {10, 5}, {5, 10}, {10, 10}}) {
                        RoomDsl.put(level, o, d[0], 5, d[1], RoomDsl.LAMP);
                    }
                    // Angled corner buttresses.
                    buttress(level, o, 1, 1, Direction.EAST, Direction.SOUTH);
                    buttress(level, o, 14, 1, Direction.WEST, Direction.SOUTH);
                    buttress(level, o, 1, 14, Direction.EAST, Direction.NORTH);
                    buttress(level, o, 14, 14, Direction.WEST, Direction.NORTH);
                });
    }

    /** A column in the corner (cx,cz) with a stair on each side stepping away from it. */
    private static void buttress(ServerLevel level, BlockPos o, int cx, int cz,
                                 Direction awayX, Direction awayZ) {
        RoomDsl.column(level, o, cx, 1, 4, cz, RoomDsl.WALL);
        RoomDsl.put(level, o, cx + awayX.getStepX(), 1, cz, stair(awayX, false));
        RoomDsl.put(level, o, cx, 1, cz + awayZ.getStepZ(), stair(awayZ, false));
    }
}
