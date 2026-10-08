package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Small block-placing helpers for the authored room specs, in room-local coordinates (interior
 * x and z 1 to 14, y 1 to 5, the origin at the cell's corner). Rooms are authored at rotation 0
 * and the stamper rotates them, so nothing here knows about rotation.
 */
final class RoomDsl {

    private RoomDsl() {}

    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** The placeholder blocks the theme processors re-skin: wall, floor, accent and lamp. */
    static final BlockState WALL = RoomBuilder.WALL;
    static final BlockState FLOOR = RoomBuilder.FLOOR;
    static final BlockState ACCENT = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
    static final BlockState LAMP = RoomBuilder.LAMP;

    static void put(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        RoomBuilder.set(level, o.offset(x, y, z), state);
    }

    /** Fills the inclusive box. */
    static void box(ServerLevel level, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2,
                    BlockState state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    RoomBuilder.set(level, o.offset(x, y, z), state);
                }
            }
        }
    }

    /** A vertical run of one block. */
    static void column(ServerLevel level, BlockPos o, int x, int y1, int y2, int z, BlockState state) {
        box(level, o, x, y1, z, x, y2, z, state);
    }

    /** Places {@code state} at (x,z) and its three mirror images across the cell's centre lines. */
    static void quad(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        put(level, o, x, y, z, state);
        put(level, o, 15 - x, y, z, state);
        put(level, o, x, y, 15 - z, state);
        put(level, o, 15 - x, y, 15 - z, state);
    }

    static BlockState log(Direction.Axis axis) {
        return Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    /** A cave vine hanging from the ceiling at (x,z), berries on. */
    static void vines(ServerLevel level, BlockPos o, int x, int z) {
        put(level, o, x, 5, z, Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(BlockStateProperties.BERRIES, true));
        put(level, o, x, 4, z, Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true));
    }

    /** A candle cluster, lit. */
    static BlockState candle(int count) {
        return Blocks.CANDLE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, count)
                .setValue(net.minecraft.world.level.block.CandleBlock.LIT, true);
    }
}
