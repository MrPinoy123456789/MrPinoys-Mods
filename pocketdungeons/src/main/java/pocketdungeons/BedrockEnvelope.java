package pocketdungeons;

import java.util.Map;
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

    /**
     * Applies the envelope to every occupied cell of {@code geometry}, skipping
     * the sub-floor bedrock layer for cells in {@code voidedCells}. The Voided
     * affix uses this so players who fall through carved floors reach the void
     * instead of bedrock. Wall rings and ceiling layers are still applied
     * normally. Pass {@link Set#of()} for a run with no voided cells.
     *
     * <p>M61: {@code cellSpanY} gives the vertical span of the room stamped in
     * each cell (defaulting to 1 for cells not in the map). A span greater than
     * 1 lowers the sub-floor bedrock to under the lowest story and extends the
     * wall rings down to cover every story; the over-ceiling layer does not
     * move. Pass {@link Map#of()} for a layout with no multi-story rooms.
     */
    static void apply(ServerLevel level, PlanGeometry geometry, Set<PlanCell> voidedCells,
                      Map<PlanCell, Integer> cellSpanY) {
        apply(level, geometry, voidedCells, cellSpanY, Set.of());
    }

    /**
     * {@link #apply}, also treating the cells at {@code standingCells} (world
     * origins outside {@code geometry}) as occupied neighbours. A floor stamped
     * against a staging room that already stands must not ring that side: the
     * ring would land in the staging room's wall column and replace it.
     */
    static void apply(ServerLevel level, PlanGeometry geometry, Set<PlanCell> voidedCells,
                      Map<PlanCell, Integer> cellSpanY, Set<BlockPos> standingCells) {
        Set<PlanCell> occupied = Set.copyOf(geometry.cells());
        for (PlanCell cell : geometry.cells()) {
            int spanY = cellSpanY.getOrDefault(cell, 1);
            applyToCell(level, geometry.cellOrigin(cell), occupied, standingCells, cell, voidedCells, spanY);
        }
    }

    private static void applyToCell(ServerLevel level, BlockPos o, Set<PlanCell> occupied,
                                     Set<BlockPos> standingCells, PlanCell cell,
                                     Set<PlanCell> voidedCells, int spanY) {
        boolean voided = voidedCells.contains(cell);
        // M61: the sub-floor sits under the lowest story, so a spanY > 1 room's
        // sub-floor drops by (spanY-1)*STORY_HEIGHT. The over-ceiling stays at
        // the cell's own top (CEILING_Y + 1); it does not move.
        int storyOffset = RoomGeometry.storyOffset(spanY);
        int subFloorY = -1 - storyOffset;
        // Sub-floor and over-ceiling: always, every cell. Except sub-floor
        // for voided cells: no bedrock so fallen players reach the void.
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                if (!voided) {
                    set(level, o.offset(x, subFloorY, z));
                }
                set(level, o.offset(x, CEILING_Y + 1, z));
            }
        }

        // Outer wall ring, one face at a time, only where there is no neighbour
        // to collide with (the class note says why an unconditional ring is
        // wrong). The ring covers every story, from under the lowest floor to
        // over the cell's ceiling.
        for (DoorMask.Direction face : DoorMask.Direction.values()) {
            PlanCell neighbour = CellGeometry.neighbourCell(cell, face);
            if (!occupied.contains(neighbour)
                    && !standingCells.contains(CellGeometry.offsetInDirection(o, face, CELL))) {
                ring(level, o, face, storyOffset);
            }
        }
    }

    /**
     * Writes one face's ring: the column one block outside {@code face}, from
     * the sub-floor ({@code depth} blocks lower for a cell that owns stories
     * below it) to over the ceiling.
     */
    private static void ring(ServerLevel level, BlockPos o, DoorMask.Direction face, int depth) {
        for (int along = 0; along < CELL; along++) {
            for (int y = -1 - depth; y <= CEILING_Y + 1; y++) {
                set(level, ringPos(o, face, along, y));
            }
        }
    }

    /** The ring position {@code along} blocks down {@code face}, one block outside the cell. */
    private static BlockPos ringPos(BlockPos o, DoorMask.Direction face, int along, int y) {
        return switch (face) {
            case NORTH -> o.offset(along, y, -1);
            case SOUTH -> o.offset(along, y, CELL);
            case WEST -> o.offset(-1, y, along);
            case EAST -> o.offset(CELL, y, along);
        };
    }

    /**
     * Puts one face's ring back after the neighbouring cell on that side was
     * cleared: the clear took the ring with it, since the ring sits in that
     * neighbour's wall column. {@code depth} reaches the lower stories of a
     * cell that owns them (0 for a single story cell).
     */
    static void applyFace(ServerLevel level, BlockPos o, DoorMask.Direction face, int depth) {
        ring(level, o, face, depth);
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
        for (DoorMask.Direction face : DoorMask.Direction.values()) {
            if (!reservedSides.contains(face)) {
                ring(level, o, face, 0);
            }
        }
    }

    /**
     * Clears the bedrock ring on one face of a cell, so a dungeon
     * connection can pass through. Called when a door is chosen and
     * the dungeon is about to be stamped behind the lobby.
     *
     * <p>Only the wall rows ({@code y=1..CEILING_Y}) are touched. The
     * margin's floor row, sub-floor row and over-ceiling row belong to the
     * neighbouring cell once it stands (its floor, and its own sub-floor and
     * over-ceiling bedrock), and clearing them only opened the neighbour's
     * envelope along the seam.
     *
     * <p>Only clears blocks that are currently bedrock. The adjacent cell's
     * own wall and ceiling blocks occupy the same one-block-outside
     * position, and an unconditional clear to air would destroy them,
     * leaving the seam between two cells open to the void. This was the
     * M67 seam bug: the staging room's north wall was rebuilt after the
     * safe room's bedrock envelope placed bedrock at the seam, then
     * clearFace destroyed both the bedrock and the wall.
     */
    static void clearFace(ServerLevel level, BlockPos o, DoorMask.Direction face) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int along = 0; along < CELL; along++) {
            for (int y = 1; y <= CEILING_Y; y++) {
                BlockPos pos = ringPos(o, face, along, y);
                if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
                    level.setBlock(pos, air, STAMP_FLAGS);
                }
            }
        }
    }
}
