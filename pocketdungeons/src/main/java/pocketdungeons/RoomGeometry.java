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

    /**
     * M61: the vertical pitch between consecutive cell floors when a room owns
     * the volume below it ({@code DungeonRoomMeta.spanY}). A one story room
     * occupies the cell's own nine blocks (sub-floor bedrock at y=-1 through
     * over-ceiling bedrock at y=CEILING_Y+1). A second story sits directly
     * beneath, and its over-ceiling layer is the upper story's sub-floor, so
     * the two share one block and the floor-to-floor pitch is the interior
     * ({@code CEILING_Y+1}) plus one bedrock layer, not two. Hence
     * {@code CEILING_Y + 2}.
     *
     * <p>This is the offset applied to a template's capture origin and size, to
     * the door-slot y in {@link RoomManifest#canonicalDoorSlots}, and to the
     * bedrock envelope's sub-floor and wall rings.
     */
    static final int STORY_HEIGHT = CEILING_Y + 2;

    /** M61: the largest {@code spanY} a room may declare. */
    static final int MAX_SPAN_Y = 2;

    /**
     * M61: how many blocks a {@code spanY > 1} room's capture origin sits below
     * its own cell origin. Each extra story adds one {@link #STORY_HEIGHT}
     * (the floor-to-floor pitch), so a one story room has offset 0 and captures
     * from its own cell origin, byte identical to before.
     */
    static int storyOffset(int spanY) {
        return (spanY - 1) * STORY_HEIGHT;
    }

    /**
     * M45: the window band, the doorway lane widened by one block on each side
     * at eye height and nowhere else. Cells tile at 16 and each owns its whole
     * footprint, so two adjacent interiors are separated by two wall blocks,
     * one per cell. A window is therefore not something a single template can
     * author: both cells have to open the same band or the player looks at the
     * neighbour's stone. Making it a canonical slot beside the doorway is what
     * makes the two sides line up by construction rather than by agreement,
     * exactly as {@link #DOOR_MIN} already does for the doorway.
     *
     * <p>The band is {@code WINDOW_MIN..WINDOW_MAX} at {@link #WINDOW_Y} only,
     * which is one row of four. Its middle two columns are the doorway's own,
     * so the opening a connected pair actually carries is the union of the two:
     * a 2 wide by 3 tall doorway with a 1 tall band of light either side of it.
     * Whatever fills the band must be placed in the outer columns alone, or it
     * plugs the doorway at eye height.
     */
    static final int WINDOW_MIN = DOOR_MIN - 1;

    /** The far end of the window band. See {@link #WINDOW_MIN}. */
    static final int WINDOW_MAX = DOOR_MAX + 1;

    /** Eye height, so the band reads from a standing player's own line of sight. */
    static final int WINDOW_Y = 2;

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
