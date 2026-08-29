package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/**
 * Overlays a {@link ConnectorType}'s look onto a door opening after
 * {@link TemplateStamper#place} has already stamped the room and its
 * {@code pocketdungeons:door} jigsaw resolved the canonical slot to air
 * (M30). {@link ConnectorGeometry} gives the pure position math; this class
 * only decides the block at each one, reading the wall's live material so a
 * theme's re-skin is respected instead of hardcoding stone brick.
 */
final class ConnectorStamper {

    private static final int DOOR_MIN = RoomGeometry.DOOR_MIN;
    private static final int DOOR_MAX = RoomGeometry.DOOR_MAX;
    private static final int DOOR_HEIGHT = RoomGeometry.DOOR_HEIGHT;
    private static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    private static final int CELL = RoomGeometry.CELL;

    private static final int STAMP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState IRON_BARS = Blocks.IRON_BARS.defaultBlockState();

    private ConnectorStamper() {}

    /**
     * Applies {@code type} to one cell's side of a door edge. {@code fillNearColumn}
     * decides which door-slot column {@link ConnectorType#DOOR_SINGLE} keeps
     * solid; the caller rolls it once per edge and passes the same value to
     * both sides, so the opening lines up across the two-block partition
     * instead of reading as two independent, mismatched holes.
     */
    static void apply(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall,
                      ConnectorType type, boolean fillNearColumn) {
        switch (type) {
            case DOOR_WIDE -> { } // the jigsaw already resolved this slot to air
            case DOOR_SINGLE -> {
                int column = fillNearColumn ? DOOR_MIN : DOOR_MAX;
                fill(level, ConnectorGeometry.rect(cellOrigin, wall, column, column, 1, DOOR_HEIGHT),
                        wallBlockAt(level, cellOrigin, wall));
            }
            case DOOR_DOUBLE -> {
                fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MIN - 1, DOOR_MIN - 1, 1, DOOR_HEIGHT), AIR);
                fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MAX + 1, DOOR_MAX + 1, 1, DOOR_HEIGHT), AIR);
            }
            case IRON_DOOR -> applyIronDoor(level, cellOrigin, wall);
            case BARS -> fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MIN, DOOR_MAX, 1, WALL_HEIGHT), IRON_BARS);
            case OPEN -> applyOpen(level, cellOrigin, wall);
            case ARCH -> fill(level, ConnectorGeometry.rect(cellOrigin, wall, 0, CELL - 1, 1, WALL_HEIGHT - 2), AIR);
        }
    }

    /**
     * Two iron doors side by side, closed and unpowered by default -- gated
     * behind a redstone signal the stamper does not provide (room content or
     * the player supplies it). The door slot is 3 tall but a door is only 2,
     * so the top row is capped with the wall's own material rather than left
     * open above a closed door.
     */
    private static void applyIronDoor(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        Direction facing = Instances.mcDirection(wall);
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState cap = wallBlockAt(level, cellOrigin, wall);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MIN, DOOR_MAX, 1, 1), lower);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MIN, DOOR_MAX, 2, 2), upper);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, DOOR_MIN, DOOR_MAX, 3, 3), cap);
    }

    /**
     * Clears the middle of the wall, leaving a 2-wide pillar standing at each
     * end (floor to wall-top) so the opening reads as a framed archway rather
     * than a missing wall.
     */
    private static void applyOpen(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        BlockState wallBlock = wallBlockAt(level, cellOrigin, wall);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, 2, CELL - 3, 1, WALL_HEIGHT), AIR);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, 0, 1, 1, WALL_HEIGHT), wallBlock);
        fill(level, ConnectorGeometry.rect(cellOrigin, wall, CELL - 2, CELL - 1, 1, WALL_HEIGHT), wallBlock);
    }

    /** A wall position well outside the door slot, safe to read the theme's live material from. */
    private static BlockState wallBlockAt(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        return level.getBlockState(ConnectorGeometry.wallPos(cellOrigin, wall, DOOR_MIN - 2, 2));
    }

    private static void fill(ServerLevel level, List<BlockPos> positions, BlockState state) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, state, STAMP_FLAGS);
        }
    }
}
