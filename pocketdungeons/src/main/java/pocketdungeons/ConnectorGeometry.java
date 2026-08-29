package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure coordinate math for {@link ConnectorType} overlays: which world
 * positions on a cell's wall a rectangle of (column, height) touches. No
 * Minecraft state or level writes -- {@link ConnectorStamper} is the
 * ServerLevel-writing twin, the same split {@link CellGeometry} keeps for
 * the canonical door slot ({@code doorSlotPositions} vs {@code doorSlot}).
 */
final class ConnectorGeometry {

    private static final int CELL = RoomGeometry.CELL;

    private ConnectorGeometry() {}

    /**
     * Every position in {@code [iFrom, iTo] x [yFrom, yTo]} on {@code wall}.
     * {@code i} runs along X for the north/south walls and along Z for
     * east/west -- the same convention as {@link CellGeometry#doorSlotPositions}
     * -- so the same {@code i} lands on the same world column for both cells
     * sharing an edge, regardless of which of the two walls it is measured on.
     */
    static List<BlockPos> rect(BlockPos cellOrigin, DoorMask.Direction wall, int iFrom, int iTo, int yFrom, int yTo) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = yFrom; y <= yTo; y++) {
            for (int i = iFrom; i <= iTo; i++) {
                positions.add(wallPos(cellOrigin, wall, i, y));
            }
        }
        return positions;
    }

    /** One world position on {@code wall} at column {@code i}, height {@code y}. */
    static BlockPos wallPos(BlockPos cellOrigin, DoorMask.Direction wall, int i, int y) {
        return switch (wall) {
            case NORTH -> cellOrigin.offset(i, y, 0);
            case SOUTH -> cellOrigin.offset(i, y, CELL - 1);
            case WEST -> cellOrigin.offset(0, y, i);
            case EAST -> cellOrigin.offset(CELL - 1, y, i);
        };
    }
}
