package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * M2 T2.3 / {@code MYTHIC_PLUS_RECONCILIATION.md} §3.2.2: a one-block bedrock
 * shell just outside every cell's existing shell, so a player who digs through
 * a sealed room's polished-andesite floor or stone-brick ceiling meets bedrock
 * instead of dropping into open void -- the dungeon deliberately has no
 * break restrictions (T2.5's quarry depends on that staying true), so this is
 * the backstop, not a lock.
 *
 * <p>Does <strong>not</strong> fill the negative space between cells -- that
 * volume is enormous, and every cell already owns all four of its own walls.
 * An unconditional ring would put bedrock <em>between</em> two connected
 * rooms, so the outer wall ring is added only on faces with no adjacent
 * occupied cell. {@code voidGuardDepth} stays on as the backstop for whatever
 * this misses (a reward room or lingering quarry that predates it, an admin
 * layout with a hole in its cell set); this costs nothing extra to keep.
 */
final class BedrockEnvelope {

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final int STAMP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private static final int CELL = RoomGeometry.CELL;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;

    private BedrockEnvelope() {}

    /** Applies the envelope to every occupied cell of {@code geometry}. */
    static void apply(ServerLevel level, PlanGeometry geometry) {
        Set<PlanCell> occupied = Set.copyOf(geometry.cells());
        for (PlanCell cell : geometry.cells()) {
            applyToCell(level, geometry.cellOrigin(cell), occupied, cell);
        }
    }

    private static void applyToCell(ServerLevel level, BlockPos o, Set<PlanCell> occupied, PlanCell cell) {
        // Sub-floor and over-ceiling: always, every cell.
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                set(level, o.offset(x, -1, z));
                set(level, o.offset(x, CEILING_Y + 1, z));
            }
        }

        // Outer wall ring, one face at a time, only where there is no neighbour
        // to collide with -- see the class note for why an unconditional ring
        // is wrong.
        if (!occupied.contains(new PlanCell(cell.x(), cell.z() - 1))) { // north
            for (int x = 0; x < CELL; x++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(x, y, -1));
                }
            }
        }
        if (!occupied.contains(new PlanCell(cell.x(), cell.z() + 1))) { // south
            for (int x = 0; x < CELL; x++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(x, y, CELL));
                }
            }
        }
        if (!occupied.contains(new PlanCell(cell.x() - 1, cell.z()))) { // west
            for (int z = 0; z < CELL; z++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(-1, y, z));
                }
            }
        }
        if (!occupied.contains(new PlanCell(cell.x() + 1, cell.z()))) { // east
            for (int z = 0; z < CELL; z++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(CELL, y, z));
                }
            }
        }
    }

    private static void set(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, BEDROCK, STAMP_FLAGS);
    }

    /**
     * As {@link #apply}, for the M2/M3 lobby's initial one-cell stamp: no
     * dungeon plan exists yet, so there is no {@link PlanGeometry} to compute
     * neighbours from -- the caller instead names the one side that must be
     * left open (wherever {@code reservedSide} will connect to once a door is
     * chosen). The other three sides get the usual ring; when the real plan is
     * stamped, the ordinary {@link #apply} runs again over the complete
     * geometry and correctly leaves the (by then genuinely occupied)
     * reserved side alone.
     */
    static void applyToLobbyCell(ServerLevel level, BlockPos o, DoorMask.Direction reservedSide) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                set(level, o.offset(x, -1, z));
                set(level, o.offset(x, CEILING_Y + 1, z));
            }
        }
        if (reservedSide != DoorMask.Direction.NORTH) {
            for (int x = 0; x < CELL; x++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(x, y, -1));
                }
            }
        }
        if (reservedSide != DoorMask.Direction.SOUTH) {
            for (int x = 0; x < CELL; x++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(x, y, CELL));
                }
            }
        }
        if (reservedSide != DoorMask.Direction.WEST) {
            for (int z = 0; z < CELL; z++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(-1, y, z));
                }
            }
        }
        if (reservedSide != DoorMask.Direction.EAST) {
            for (int z = 0; z < CELL; z++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(CELL, y, z));
                }
            }
        }
    }
}
