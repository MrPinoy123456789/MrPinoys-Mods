package charter.id;

/** A chunk column in a dimension, the unit of every claim (DESIGN.md §9.1). */
public record ChunkKey(String dimension, int x, int z) {
    public ChunkKey {
        if (dimension == null || dimension.isEmpty()) throw new IllegalArgumentException("ChunkKey needs a dimension");
    }

    /** The chunk sharing an edge in the given direction: dx and dz are each -1, 0 or 1. */
    public ChunkKey offset(int dx, int dz) {
        return new ChunkKey(dimension, x + dx, z + dz);
    }
}
