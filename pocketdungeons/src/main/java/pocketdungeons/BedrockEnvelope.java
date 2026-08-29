package pocketdungeons;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M2 T2.3 / {@code docs/MYTHIC_PLUS_RECONCILIATION.md} §3.2.2: a one-block bedrock
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
        apply(level, geometry, Set.of());
    }

    /**
     * As {@link #apply(ServerLevel, PlanGeometry)}, but skips the sub-floor
     * bedrock layer for cells in {@code voidedCells}. The Voided affix uses
     * this so players who fall through carved floors reach the void instead
     * of bedrock. Wall rings and ceiling layers are still applied normally.
     */
    static void apply(ServerLevel level, PlanGeometry geometry, Set<PlanCell> voidedCells) {
        Set<PlanCell> occupied = Set.copyOf(geometry.cells());
        for (PlanCell cell : geometry.cells()) {
            applyToCell(level, geometry.cellOrigin(cell), occupied, cell, voidedCells);
        }
    }

    private static void applyToCell(ServerLevel level, BlockPos o, Set<PlanCell> occupied,
                                     PlanCell cell, Set<PlanCell> voidedCells) {
        boolean voided = voidedCells.contains(cell);
        // Sub-floor and over-ceiling: always, every cell. Except sub-floor
        // for voided cells: no bedrock so fallen players reach the void.
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                if (!voided) {
                    set(level, o.offset(x, -1, z));
                }
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
     * As {@link #apply}, for a cell that has no {@link PlanGeometry} yet (the
     * M2/M3 lobby's initial one-cell stamp, or a room relocated behind a
     * terminal cell). The caller instead names the sides that must be left
     * open (wherever a real connection will exist once a door is chosen).
     * The other sides get the usual ring; when the real plan is stamped, the
     * ordinary {@link #apply} runs again over the complete geometry and
     * correctly leaves the (by then genuinely occupied) reserved sides alone.
     *
     * <p>A room relocated behind a terminal cell (T2.4) has two sides that must
     * stay clear, not one: the wall a future dungeon will connect through, and
     * the wall the terminal cell is <em>already</em> standing against. The ring
     * this writes sits one block outside the cell, which is the same block as
     * the neighbour's own wall column -- so bedrocking a side that has a live
     * neighbour does not "back" that wall, it replaces it, doorway included.
     * That is the same reason {@link #apply} skips faces with an occupied
     * neighbour, expressed for a cell that has no {@link PlanGeometry} yet.
     */
    static void applyToCell(ServerLevel level, BlockPos o, Set<DoorMask.Direction> reservedSides) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                set(level, o.offset(x, -1, z));
                set(level, o.offset(x, CEILING_Y + 1, z));
            }
        }
        if (!reservedSides.contains(DoorMask.Direction.NORTH)) {
            for (int x = 0; x < CELL; x++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(x, y, -1));
                }
            }
        }
        if (!reservedSides.contains(DoorMask.Direction.SOUTH)) {
            for (int x = 0; x < CELL; x++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(x, y, CELL));
                }
            }
        }
        if (!reservedSides.contains(DoorMask.Direction.WEST)) {
            for (int z = 0; z < CELL; z++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(-1, y, z));
                }
            }
        }
        if (!reservedSides.contains(DoorMask.Direction.EAST)) {
            for (int z = 0; z < CELL; z++) {
                for (int y = -1; y <= CEILING_Y + 1; y++) {
                    set(level, o.offset(CELL, y, z));
                }
            }
        }
    }

    /**
     * Clears the bedrock ring on one face of a cell, so a dungeon
     * connection can pass through. Called when a door is chosen and
     * the dungeon is about to be stamped behind the lobby.
     */
    static void clearFace(ServerLevel level, BlockPos o, DoorMask.Direction face) {
        BlockState air = Blocks.AIR.defaultBlockState();
        switch (face) {
            case NORTH -> {
                for (int x = 0; x < CELL; x++)
                    for (int y = -1; y <= CEILING_Y + 1; y++)
                        level.setBlock(o.offset(x, y, -1), air, STAMP_FLAGS);
            }
            case SOUTH -> {
                for (int x = 0; x < CELL; x++)
                    for (int y = -1; y <= CEILING_Y + 1; y++)
                        level.setBlock(o.offset(x, y, CELL), air, STAMP_FLAGS);
            }
            case WEST -> {
                for (int z = 0; z < CELL; z++)
                    for (int y = -1; y <= CEILING_Y + 1; y++)
                        level.setBlock(o.offset(-1, y, z), air, STAMP_FLAGS);
            }
            case EAST -> {
                for (int z = 0; z < CELL; z++)
                    for (int y = -1; y <= CEILING_Y + 1; y++)
                        level.setBlock(o.offset(CELL, y, z), air, STAMP_FLAGS);
            }
        }
    }
}
