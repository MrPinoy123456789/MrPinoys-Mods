package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the planner's grid coordinates into world positions.
 *
 * <p>The layout graph walks from {@code (0,0)} and may head west or north, so
 * plan cells routinely have negative coordinates. This class normalises them
 * against the instance origin: the cell with the smallest {@code x} and the cell
 * with the smallest {@code z} together define the corner the layout is anchored
 * to, and every other cell is placed relative to that.
 *
 * <p><strong>One occupied cell is exactly one chunk.</strong> Slot origins are
 * {@code (slot % slotsPerRow) * slotPitch}, and {@code slotPitch} is validated to
 * be a multiple of 16, so a cell boundary is always a chunk boundary. That is why
 * {@link #chunks()} returns one entry per <em>occupied</em> cell rather than per
 * bounding-box cell: a 5x8 bounding box holding 12 rooms force-loads 12 chunks,
 * not 40.
 */
record PlanGeometry(BlockPos origin, int minCellX, int minCellZ,
                    int spanX, int spanZ, List<PlanCell> cells) {

    static PlanGeometry of(BlockPos origin, Collection<PlanCell> cells) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("a layout must have at least one cell");
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlanCell cell : cells) {
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }

        // Deterministic order so stamping, clearing and any admin dump all walk
        // the same cells in the same sequence.
        List<PlanCell> ordered = new ArrayList<>(cells);
        ordered.sort(Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z));

        return new PlanGeometry(origin, minX, minZ,
                maxX - minX + 1, maxZ - minZ + 1, List.copyOf(ordered));
    }

    /** Floor corner of a cell, in world space. */
    BlockPos cellOrigin(PlanCell cell) {
        return origin.offset(
                (cell.x() - minCellX) * RoomGeometry.CELL, 0,
                (cell.z() - minCellZ) * RoomGeometry.CELL);
    }

    /** Standing position at the centre of a cell (floor + 1). */
    BlockPos cellCentre(PlanCell cell) {
        return cellOrigin(cell).offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
    }

    /** Everything the instance owns, for entity sweeps and teardown. */
    AABB bounds() {
        return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + spanX * RoomGeometry.CELL,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + spanZ * RoomGeometry.CELL);
    }

    /** One chunk per occupied cell. See the class note on why this is exact. */
    List<ChunkPos> chunks() {
        List<ChunkPos> out = new ArrayList<>(cells.size());
        for (PlanCell cell : cells) {
            BlockPos cellOrigin = cellOrigin(cell);
            out.add(new ChunkPos(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4));
        }
        return out;
    }

    /** Floor corner of every occupied cell, in the same order as {@link #cells()}. */
    List<BlockPos> cellOrigins() {
        List<BlockPos> out = new ArrayList<>(cells.size());
        for (PlanCell cell : cells) {
            out.add(cellOrigin(cell));
        }
        return out;
    }
}
