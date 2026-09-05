package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Coordinate and door-wall arithmetic for a dungeon's cell grid: no membership,
 * no records, no teardown state, just the geometry every one of those has to
 * agree on. Pure enough to be tested with plain {@code javac} the way
 * {@link DoorMask} and {@link KeystoneMath} are ({@code CellGeometryTest}).
 *
 * <p>Extracted out of {@code Instances} in M9 C3 -- the first of that split's
 * six extractions, and deliberately first: everything else {@code Instances}
 * does eventually calls into this for a wall, a neighbour or a bounding box,
 * so giving it a test net before touching the rest of the file is what makes
 * the rest of the split reviewable.
 */
final class CellGeometry {

    private CellGeometry() {}

    /**
     * The wall of {@code terminalOrigin} whose neighbouring cell exists in
     * {@code geometry} -- the entrance a party actually walked in through, read
     * back from the layout rather than assumed. Falls back to {@code SOUTH} for
     * a plan that somehow has no standing neighbour at all.
     */
    static DoorMask.Direction terminalEntranceDirection(PlanGeometry geometry, BlockPos terminalOrigin) {
        PlanCell terminalCell = geometry.cellAt(terminalOrigin.offset(
                RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
        if (terminalCell == null) {
            return DoorMask.Direction.SOUTH;
        }
        for (DoorMask.Direction dir : DoorMask.Direction.values()) {
            if (geometry.cells().contains(neighbourCell(terminalCell, dir))) {
                return dir;
            }
        }
        return DoorMask.Direction.SOUTH;
    }

    static DoorMask.Direction opposite(DoorMask.Direction dir) {
        return switch (dir) {
            case NORTH -> DoorMask.Direction.SOUTH;
            case SOUTH -> DoorMask.Direction.NORTH;
            case EAST -> DoorMask.Direction.WEST;
            case WEST -> DoorMask.Direction.EAST;
        };
    }

    /**
     * (M43.8) The vanilla {@link Direction} a fixture on {@code wall} faces
     * to look into the room, i.e. the vanilla direction opposite
     * {@code wall} itself. Was five verbatim copies of this same switch
     * across {@code RoomTemplateGenerator}'s door, lever, sign and frame
     * placement.
     */
    static Direction facingIntoRoom(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH -> Direction.SOUTH;
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
    }

    static BlockPos offsetInDirection(BlockPos origin, DoorMask.Direction dir, int distance) {
        return switch (dir) {
            case NORTH -> origin.north(distance);
            case SOUTH -> origin.south(distance);
            case EAST -> origin.east(distance);
            case WEST -> origin.west(distance);
        };
    }

    static int rotationToFace(DoorMask.Direction originalWall, DoorMask.Direction targetWall) {
        int idx = switch (originalWall) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
        };
        int targetIdx = switch (targetWall) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
        };
        return (targetIdx - idx + 4) % 4;
    }

    static void sealDoorOnWall(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        // The seal matches the cell's current shell: a swapped room (M24) keeps
        // its shell material across the doorways this closes on relocation and
        // re-lobby, instead of reverting to stone brick. A dungeon or liminal
        // cell has no known shell floor, so shellWallAt falls back to WALL.
        doorSlot(level, cellOrigin, wall, RoomBuilder.shellWallAt(level, cellOrigin));
    }

    static void openDoorOnWall(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall) {
        doorSlot(level, cellOrigin, wall, Blocks.AIR.defaultBlockState());
    }

    private static void doorSlot(ServerLevel level, BlockPos cellOrigin, DoorMask.Direction wall,
                                 BlockState state) {
        for (BlockPos pos : doorSlotPositions(cellOrigin, wall)) {
            level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
                    | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
        }
    }

    /**
     * Every world position a doorway on {@code wall} occupies: 2 wide
     * ({@code DOOR_MIN}..{@code DOOR_MAX}), 3 tall from the floor
     * ({@code DOOR_HEIGHT}), centred on the cell edge. Split out from
     * {@link #doorSlot} so the position math is testable without a
     * {@code ServerLevel} ({@code CellGeometryTest}); {@link #doorSlot} is the
     * only caller and does nothing but write each of these.
     */
    static List<BlockPos> doorSlotPositions(BlockPos cellOrigin, DoorMask.Direction wall) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT; y++) {
            for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
                positions.add(switch (wall) {
                    case NORTH -> cellOrigin.offset(i, y, 0);
                    case SOUTH -> cellOrigin.offset(i, y, RoomGeometry.CELL - 1);
                    case WEST -> cellOrigin.offset(0, y, i);
                    case EAST -> cellOrigin.offset(RoomGeometry.CELL - 1, y, i);
                });
            }
        }
        return positions;
    }

    /**
     * The directions in which {@code cellOrigin}'s neighbouring cell is part of
     * {@code geometry} -- i.e. the walls that have a real room on the other side
     * rather than the bedrock shell.
     */
    static Set<DoorMask.Direction> standingNeighbours(PlanGeometry geometry, BlockPos cellOrigin) {
        Set<DoorMask.Direction> out = new LinkedHashSet<>();
        PlanCell cell = geometry.cellAt(cellOrigin.offset(
                RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
        if (cell == null) {
            return out;
        }
        for (DoorMask.Direction dir : DoorMask.Direction.values()) {
            if (geometry.cells().contains(neighbourCell(cell, dir))) {
                out.add(dir);
            }
        }
        return out;
    }

    /**
     * Whether {@code pos} falls inside any of these cells' own volumes.
     * M61: a cell may own lower stories below its floor, so the y range extends
     * downward by the max allowed story offset. Over-matching a single-story
     * cell is harmless: it only prevents clearing into void below a keep cell.
     */
    static boolean insideAnyCell(BlockPos pos, List<BlockPos> cellOrigins) {
        int maxOffset = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);
        for (BlockPos cell : cellOrigins) {
            int dx = pos.getX() - cell.getX();
            int dy = pos.getY() - cell.getY();
            int dz = pos.getZ() - cell.getZ();
            if (dx >= 0 && dx < RoomGeometry.CELL
                    && dz >= 0 && dz < RoomGeometry.CELL
                    && dy >= -maxOffset && dy <= RoomGeometry.CEILING_Y) {
                return true;
            }
        }
        return false;
    }

    /** The plan cell one step over in {@code dir}, independent of the room manifest. */
    static PlanCell neighbourCell(PlanCell cell, DoorMask.Direction dir) {
        return switch (dir) {
            case NORTH -> new PlanCell(cell.x(), cell.z() - 1);
            case SOUTH -> new PlanCell(cell.x(), cell.z() + 1);
            case WEST -> new PlanCell(cell.x() - 1, cell.z());
            case EAST -> new PlanCell(cell.x() + 1, cell.z());
        };
    }

    /** The AABB of one cell's own 16x16 volume, floor to ceiling. */
    static AABB cellBounds(BlockPos origin) {
        return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + RoomGeometry.CELL,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + RoomGeometry.CELL);
    }
}
