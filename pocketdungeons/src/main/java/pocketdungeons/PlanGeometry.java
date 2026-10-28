package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the planner's grid coordinates into world positions.
 *
 * <p>The layout graph walks from {@code (0,0)} and may head west or north, so
 * plan cells routinely have negative coordinates. This class normalises them
 * against the instance origin: the cell with the smallest {@code x} and the cell
 * with the smallest {@code z} together define the corner the layout is anchored
 * to, and every other cell is placed relative to that.
 *
 * <p><strong>One occupied column is exactly one chunk.</strong> Slot origins are
 * {@code (slot % slotsPerRow) * slotPitch}, and {@code slotPitch} is validated to
 * be a multiple of 16, so a cell boundary is always a chunk boundary. That is why
 * {@link #chunks()} returns one entry per <em>occupied</em> column rather than per
 * bounding-box cell: a 5x8 bounding box holding 12 rooms force-loads 12 chunks,
 * not 40. Cells stacked on different stories (vertical floors) share a column and
 * so share a chunk.
 */
record PlanGeometry(BlockPos origin, int minCellX, int minCellZ,
                    int spanX, int spanZ, List<PlanCell> cells, int storyFloorOffset,
                    int minStory, Map<Long, List<PlanCell>> columns) {

    static PlanGeometry of(BlockPos origin, Collection<PlanCell> cells) {
        return of(origin, cells, 0);
    }

    /**
     * M61: as {@link #of(BlockPos, Collection)}, with {@code storyFloorOffset}
     * giving how far below {@code origin.getY()} the deepest multi-story room
     * in this layout reaches ({@code (spanY-1)*STORY_HEIGHT} for its deepest
     * room, 0 for an all-single-story layout). It only widens
     * {@link #bounds()} downward so teardown, entity sweeps and protection
     * cover the lower stories; {@link #chunks()} is per cell and needs no
     * change, since a lower story shares its cell's chunk column.
     */
    static PlanGeometry of(BlockPos origin, Collection<PlanCell> cells, int storyFloorOffset) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("a layout must have at least one cell");
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minStory = 0;
        for (PlanCell cell : cells) {
            minStory = Math.min(minStory, cell.y());
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }

        // Deterministic order so stamping, clearing and any admin dump all walk
        // the same cells in the same sequence.
        List<PlanCell> ordered = new ArrayList<>(cells);
        ordered.sort(PlanCell.ORDER);

        // Column index: x and z to the cells stacked there, lowest story first (ORDER ends on y).
        Map<Long, List<PlanCell>> columns = new HashMap<>();
        for (PlanCell cell : ordered) {
            columns.computeIfAbsent(columnKey(cell.x(), cell.z()), k -> new ArrayList<>()).add(cell);
        }
        return new PlanGeometry(origin, minX, minZ,
                maxX - minX + 1, maxZ - minZ + 1, List.copyOf(ordered), storyFloorOffset,
                minStory, columns);
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Floor corner of a cell, in world space. A cell on story -1 sits one story pitch below story 0. */
    BlockPos cellOrigin(PlanCell cell) {
        return origin.offset(
                (cell.x() - minCellX) * RoomGeometry.CELL, cell.y() * RoomGeometry.STORY_HEIGHT,
                (cell.z() - minCellZ) * RoomGeometry.CELL);
    }

    /**
     * The floor corner of the story of {@code cell} that the door toward {@code other} sits on. For every
     * door but a stair room's way on this is {@link #cellOrigin}; a stair room reaches the cell one story
     * below it through the story below its own floor, one {@link RoomGeometry#STORY_HEIGHT} lower per story.
     */
    BlockPos wallOrigin(PlanCell cell, PlanCell other) {
        BlockPos origin = cellOrigin(cell);
        int drop = cell.y() - other.y();
        return drop > 0 ? origin.below(drop * RoomGeometry.STORY_HEIGHT) : origin;
    }

    /** Standing position at the centre of a cell (floor + 1). */
    BlockPos cellCentre(PlanCell cell) {
        return cellOrigin(cell).offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
    }

    /**
     * Everything the instance owns, for entity sweeps and teardown. The bottom covers the deepest
     * story plus the deepest multi-story room (a safe over-cover when they are in different cells).
     */
    AABB bounds() {
        int depth = storyFloorOffset - minStory * RoomGeometry.STORY_HEIGHT;
        return new AABB(
                origin.getX(), origin.getY() - depth, origin.getZ(),
                origin.getX() + spanX * RoomGeometry.CELL,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + spanZ * RoomGeometry.CELL);
    }

    /** One chunk per occupied column, first seen first. See the class note on why this is exact. */
    List<ChunkPos> chunks() {
        List<ChunkPos> out = new ArrayList<>(cells.size());
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (PlanCell cell : cells) {
            if (!seen.add(columnKey(cell.x(), cell.z()))) {
                continue;
            }
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

    /**
     * The occupied cell a world position falls inside, or null if it is outside
     * every cell this layout owns. The inverse of {@link #cellOrigin}, for the
     * boss bar's room-visited counter (T2): a player's feet convert to a grid
     * cell, and that cell is recorded as seen.
     */
    PlanCell cellAt(BlockPos pos) {
        int cellX = Math.floorDiv(pos.getX() - origin.getX(), RoomGeometry.CELL) + minCellX;
        int cellZ = Math.floorDiv(pos.getZ() - origin.getZ(), RoomGeometry.CELL) + minCellZ;
        List<PlanCell> column = columns.get(columnKey(cellX, cellZ));
        if (column == null) {
            return null;
        }
        if (column.size() == 1) {
            // Callers rely on height being ignored here: a spawner in a two-story room's
            // lower story resolves to the room's own cell.
            return column.get(0);
        }
        int story = Math.floorDiv(pos.getY() - origin.getY(), RoomGeometry.STORY_HEIGHT);
        // Lowest story first: take the cell on that story, else the nearest one above it
        // (a tall room owns the volume below it), else the top one.
        for (PlanCell cell : column) {
            if (cell.y() >= story) {
                return cell;
            }
        }
        return column.get(column.size() - 1);
    }
}
