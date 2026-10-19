package pocketdungeons;

import java.util.Comparator;

/**
 * One cell in the instance-local layout grid, independent of world coordinates
 * -- {@code RoomGeometry}/{@code RoomBuilder} convert a {@code PlanCell} to a
 * world {@code BlockPos} at stamp time. Pure data, no Minecraft imports, so the
 * planner (M3) stays unit-testable outside the game, same discipline as
 * {@link DoorMask}.
 *
 * <p>{@code y} is the story: 0 is the entrance story, -1 one story down, -2 two
 * down (vertical floors, phase 1). Every layout today is story 0, and
 * {@link #PlanCell(int, int)} builds exactly that.
 */
record PlanCell(int x, int y, int z) {

    /** Order by x, then z, then story. For story 0 that is the order the generator has always used. */
    static final Comparator<PlanCell> ORDER =
            Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z).thenComparingInt(PlanCell::y);

    /** A cell on the entrance story. */
    PlanCell(int x, int z) {
        this(x, 0, z);
    }

    /**
     * Story 0 keeps the hash the two-component record had ({@code 31 * x + z}), because
     * HashSets and HashMaps of cells drive generation order and a new hash would reshuffle
     * every seed. Pinned by {@code LayoutFingerprintTest}. Written next to {@link #equals}
     * so the two are read together.
     */
    @Override
    public int hashCode() {
        return 31 * x + z + 1_000_003 * y;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanCell c && c.x == x && c.y == y && c.z == z;
    }

    @Override
    public String toString() {
        return y == 0 ? "PlanCell[x=" + x + ", z=" + z + "]"
                : "PlanCell[x=" + x + ", y=" + y + ", z=" + z + "]";
    }

    /** The adjacent cell on the same story. */
    PlanCell neighbor(DoorMask.Direction direction) {
        return switch (direction) {
            case NORTH -> new PlanCell(x, y, z - 1);
            case SOUTH -> new PlanCell(x, y, z + 1);
            case EAST -> new PlanCell(x + 1, y, z);
            case WEST -> new PlanCell(x - 1, y, z);
        };
    }

    /**
     * The compass direction from this cell to an adjacent cell on the same story, or null if
     * not adjacent or on a different story.
     */
    DoorMask.Direction directionTo(PlanCell other) {
        if (other.y != y) return null;
        int dx = other.x - x;
        int dz = other.z - z;
        if (dx == 1 && dz == 0) return DoorMask.Direction.EAST;
        if (dx == -1 && dz == 0) return DoorMask.Direction.WEST;
        if (dx == 0 && dz == 1) return DoorMask.Direction.SOUTH;
        if (dx == 0 && dz == -1) return DoorMask.Direction.NORTH;
        return null;
    }
}
