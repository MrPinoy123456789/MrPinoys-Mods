package charter.api;

import java.util.List;

/**
 * One piece of land a kingdom holds, as other mods see it. A keep is one area, each outpost is one area, and
 * land held with no keep or outpost behind it is one area per kingdom. Chunk coordinates are Minecraft's own: block
 * x divided by 16, rounded down.
 *
 * @param kind      what holds the land
 * @param kingdom   the kingdom's name
 * @param dimension a dimension id such as {@code minecraft:overworld}
 * @param minChunkX the smallest chunk x of the area
 * @param minChunkZ the smallest chunk z of the area
 * @param maxChunkX the largest chunk x of the area
 * @param maxChunkZ the largest chunk z of the area
 * @param chunks    every chunk the area covers
 */
public record Area(Kind kind, String kingdom, String dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                   List<Chunk> chunks) {

    /** What holds the land. */
    public enum Kind {
        /** A kingdom's keep: its whole permanent plot. */
        KEEP,
        /** An outpost and the chunks it holds. */
        OUTPOST,
        /** Land held by a kingdom that no keep or outpost currently backs. */
        CLAIM
    }

    /** A chunk by its coordinates. */
    public record Chunk(int x, int z) {
    }

    /** The smallest block x inside the bounding box of this area. */
    public int minBlockX() {
        return minChunkX * 16;
    }

    /** The smallest block z inside the bounding box of this area. */
    public int minBlockZ() {
        return minChunkZ * 16;
    }

    /** The largest block x inside the bounding box of this area. */
    public int maxBlockX() {
        return maxChunkX * 16 + 15;
    }

    /** The largest block z inside the bounding box of this area. */
    public int maxBlockZ() {
        return maxChunkZ * 16 + 15;
    }
}
