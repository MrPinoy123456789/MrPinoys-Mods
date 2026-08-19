package pocketdungeons;

/**
 * One cell in the instance-local layout grid, independent of world coordinates
 * -- {@code RoomGeometry}/{@code RoomBuilder} convert a {@code PlanCell} to a
 * world {@code BlockPos} at stamp time. Pure data, no Minecraft imports, so the
 * planner (M3) stays unit-testable outside the game, same discipline as
 * {@link DoorMask}.
 */
record PlanCell(int x, int z) {

    PlanCell neighbor(DoorMask.Direction direction) {
        return switch (direction) {
            case NORTH -> new PlanCell(x, z - 1);
            case SOUTH -> new PlanCell(x, z + 1);
            case EAST -> new PlanCell(x + 1, z);
            case WEST -> new PlanCell(x - 1, z);
        };
    }

    /** The compass direction from this cell to an adjacent cell, or null if not adjacent. */
    DoorMask.Direction directionTo(PlanCell other) {
        int dx = other.x - x;
        int dz = other.z - z;
        if (dx == 1 && dz == 0) return DoorMask.Direction.EAST;
        if (dx == -1 && dz == 0) return DoorMask.Direction.WEST;
        if (dx == 0 && dz == 1) return DoorMask.Direction.SOUTH;
        if (dx == 0 && dz == -1) return DoorMask.Direction.NORTH;
        return null;
    }
}
