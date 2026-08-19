package pocketdungeons;

import net.minecraft.core.Direction;

/**
 * Shared constants and helpers describing the authored cell geometry contract.
 * Extracted from {@link RoomTemplateGenerator} so the manifest loader can derive
 * door masks from templates without duplicating the edge/slot mapping.
 */
final class RoomGeometry {

    static final int CELL = 16;
    static final int WALL_HEIGHT = 5;
    static final int CEILING_Y = WALL_HEIGHT + 1;

    static final int DOOR_MIN = 7;
    static final int DOOR_MAX = 8;
    static final int DOOR_HEIGHT = 3;

    private RoomGeometry() {}

    /** Which wall a coordinate on the cell boundary sits on. */
    static Direction wallDirection(int x, int z) {
        if (z == 0) return Direction.NORTH;
        if (z == CELL - 1) return Direction.SOUTH;
        if (x == 0) return Direction.WEST;
        if (x == CELL - 1) return Direction.EAST;
        return null;
    }
}
