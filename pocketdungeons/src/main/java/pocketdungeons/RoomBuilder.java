package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Grid geometry. Implements the spec's cell contract (section 6.1) directly:
 * every room is a sealed 16x16 box that owns all four of its own walls, so two
 * neighbours produce a 2-block partition and no room ever writes into another
 * room's cells. Doorways are at fixed slots -- centred on the cell edge, floor
 * at local Y+1 -- which is what makes alignment automatic rather than computed.
 *
 * <p>Milestone 0 builds rooms from code. When {@code .nbt} templates arrive the
 * geometry contract here is what they have to match, so this class doubles as
 * the authoring jig's specification.
 */
final class RoomBuilder {

    /** Cell pitch on X and Z. */
    static final int CELL = RoomGeometry.CELL;
    /** Interior headroom: walls run local Y+1 .. Y+WALL_HEIGHT, ceiling above. */
    static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    /** Ceiling sits at this local Y; total box height including floor. */
    static final int CEILING_Y = RoomGeometry.CEILING_Y;

    /** Doorways are 2 wide, centred on the edge, and 3 tall from the floor. */
    private static final int DOOR_MIN = RoomGeometry.DOOR_MIN;
    private static final int DOOR_MAX = RoomGeometry.DOOR_MAX;
    private static final int DOOR_HEIGHT = RoomGeometry.DOOR_HEIGHT;

    /**
     * No neighbour updates while stamping -- section 8.3's update suppression --
     * and no drops. {@code UPDATE_SUPPRESS_DROPS} alone only suppresses a
     * removed block's own item drop (e.g. the "chest" item); a container's
     * *contents* are dropped by {@code BlockEntity.preRemoveSideEffects},
     * which is gated by the separate {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS}
     * flag. Without it, clearing a room's still-unopened loot chest lazily
     * unpacks its loot table right there (container access always does --
     * see {@code RandomizableContainerBlockEntity.getItem}) and scatters the
     * result as item entities, which then outlive the teardown and greet the
     * next player to be handed that slot.
     */
    private static final int STAMP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    static final BlockState FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState();
    static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CEILING = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private RoomBuilder() {}

    /** World position of a cell's floor corner (local 0,0). */
    static BlockPos cellOrigin(BlockPos instanceOrigin, int cellX, int cellZ) {
        return instanceOrigin.offset(cellX * CELL, 0, cellZ * CELL);
    }

    /** World position of the centre of a cell, standing height (floor + 1). */
    static BlockPos cellCentre(BlockPos instanceOrigin, int cellX, int cellZ) {
        return cellOrigin(instanceOrigin, cellX, cellZ).offset(CELL / 2, 1, CELL / 2);
    }

    /**
     * Stamps one sealed room: floor, four walls, ceiling, lighting, then punches
     * a doorway through each wall named in {@code doors}.
     */
    static void buildCell(ServerLevel level, BlockPos instanceOrigin,
                          int cellX, int cellZ, Set<Direction> doors) {
        BlockPos o = cellOrigin(instanceOrigin, cellX, cellZ);

        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                set(level, o.offset(x, 0, z), FLOOR);
                set(level, o.offset(x, CEILING_Y, z), CEILING);
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    set(level, o.offset(x, y, z), edge ? WALL : AIR);
                }
            }
        }

        // Ceiling lamps, one per quadrant. Sealed boxes are otherwise pitch dark,
        // and dark floors spawn their own mobs.
        set(level, o.offset(4, CEILING_Y, 4), LAMP);
        set(level, o.offset(4, CEILING_Y, CELL - 5), LAMP);
        set(level, o.offset(CELL - 5, CEILING_Y, 4), LAMP);
        set(level, o.offset(CELL - 5, CEILING_Y, CELL - 5), LAMP);

        for (Direction door : doors) {
            openDoor(level, o, door);
        }
    }

    /**
     * Stamps a blank stone-brick shell over one cell -- floor, walls and ceiling
     * all {@link #WALL}, nothing inside -- and reopens the doorways named in
     * {@code doors}.
     *
     * <p>What a room cell becomes once the player's room has been captured out
     * of it and moved elsewhere (T2.4). Deliberately a stamp and not a clear:
     * clearing wrote air through the one-block margin as well, and that margin
     * is where the bedrock envelope lives -- on any face without an occupied
     * neighbour, erasing it opens a mineable path off the edge of the world from
     * whatever room is still standing next door. Writing a room in place touches
     * nothing outside the cell, so the shell around it survives untouched.
     *
     * <p>No floor material of its own, unlike {@link #buildCell}: this is the
     * blank that replaces a room, and it is meant to read as one. It is still
     * lit, though -- the same four ceiling lamps {@link #buildCell} places. A
     * blank room is a design choice; a dark one is a mob farm, and this cell
     * sits inside a live instance the owner can walk back into.
     */
    static void buildLiminalCell(ServerLevel level, BlockPos o, Set<Direction> doors) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                set(level, o.offset(x, 0, z), WALL);
                set(level, o.offset(x, CEILING_Y, z), WALL);
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    set(level, o.offset(x, y, z), edge ? WALL : AIR);
                }
            }
        }
        set(level, o.offset(4, CEILING_Y, 4), LAMP);
        set(level, o.offset(4, CEILING_Y, CELL - 5), LAMP);
        set(level, o.offset(CELL - 5, CEILING_Y, 4), LAMP);
        set(level, o.offset(CELL - 5, CEILING_Y, CELL - 5), LAMP);

        for (Direction door : doors) {
            openDoor(level, o, door);
        }
    }

    /**
     * Opens the canonical door slot on one edge. Each room punches only its own
     * wall; the neighbour independently punches its facing wall at the same
     * coordinates, and the two openings line up because the slot is fixed.
     */
    static void openDoor(ServerLevel level, BlockPos cellOrigin, Direction door) {
        doorSlot(level, cellOrigin, door, AIR);
    }

    /**
     * The opposite of {@link #openDoor}: fills the canonical door slot back in
     * with wall. For the M2/M3 lobby, whose one connecting door is sealed until
     * a door choice generates the dungeon behind it -- there is nothing on the
     * other side yet, so an open gap would be a gap onto bedrock (T2.3), not a
     * doorway.
     */
    static void sealDoor(ServerLevel level, BlockPos cellOrigin, Direction door) {
        doorSlot(level, cellOrigin, door, WALL);
    }

    private static void doorSlot(ServerLevel level, BlockPos cellOrigin, Direction door, BlockState state) {
        for (int y = 1; y <= DOOR_HEIGHT; y++) {
            for (int i = DOOR_MIN; i <= DOOR_MAX; i++) {
                BlockPos pos = switch (door) {
                    case NORTH -> cellOrigin.offset(i, y, 0);
                    case SOUTH -> cellOrigin.offset(i, y, CELL - 1);
                    case WEST -> cellOrigin.offset(0, y, i);
                    case EAST -> cellOrigin.offset(CELL - 1, y, i);
                    default -> throw new IllegalArgumentException("door must be horizontal: " + door);
                };
                set(level, pos, state);
            }
        }
    }

    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, STAMP_FLAGS);
    }
}
