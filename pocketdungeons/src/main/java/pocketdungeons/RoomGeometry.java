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

    /**
     * M19: the wall on the viewer's left when they stand inside the room and
     * face {@code wall}. The engine terminal sits there so it is visible from
     * the selector doors: facing the doors, the engine is beside you, never
     * behind you.
     */
    static DoorMask.Direction leftOf(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH -> DoorMask.Direction.WEST;
            case SOUTH -> DoorMask.Direction.EAST;
            case EAST -> DoorMask.Direction.NORTH;
            case WEST -> DoorMask.Direction.SOUTH;
        };
    }

    /**
     * The wall on the viewer's right when they stand inside the room and face
     * {@code wall}. This is the wall opposite the engine terminal
     * ({@link #leftOf}), so the tracker screen hangs there: facing the
     * selector doors, the engine is on your left and the tracker on your
     * right, neither behind you.
     */
    static DoorMask.Direction rightOf(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH -> DoorMask.Direction.EAST;
            case SOUTH -> DoorMask.Direction.WEST;
            case EAST -> DoorMask.Direction.SOUTH;
            case WEST -> DoorMask.Direction.NORTH;
        };
    }
}
