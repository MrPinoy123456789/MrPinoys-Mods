package charterxaero.model;

/** Which edges of a chunk are a kingdom's outer border. Plain JDK, unit tested. */
public final class Borders {

    /** The edge towards smaller z. */
    public static final int NORTH = 1;
    /** The edge towards larger x. */
    public static final int EAST = 2;
    /** The edge towards larger z. */
    public static final int SOUTH = 4;
    /** The edge towards smaller x. */
    public static final int WEST = 8;

    private Borders() {
    }

    /** What the index knows of a chunk's owner: a kingdom number, or -1 for nobody. */
    @FunctionalInterface
    public interface Owner {
        int at(int chunkX, int chunkZ);
    }

    /**
     * The border edges of the chunk at {@code (x, z)} owned by {@code kingdom}: every side whose neighbour belongs to
     * another kingdom or to nobody. 0 for a chunk well inside.
     */
    public static int sides(Owner owner, int x, int z, int kingdom) {
        int s = 0;
        if (owner.at(x, z - 1) != kingdom) s |= NORTH;
        if (owner.at(x + 1, z) != kingdom) s |= EAST;
        if (owner.at(x, z + 1) != kingdom) s |= SOUTH;
        if (owner.at(x - 1, z) != kingdom) s |= WEST;
        return s;
    }
}
