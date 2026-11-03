package charter.api;

import java.util.List;

/** Compile stand-in for Charter's {@code charter.api.Area}; the real one ships in Charter's jar. */
public record Area(Kind kind, String kingdom, String dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                   List<Chunk> chunks) {

    public enum Kind { KEEP, OUTPOST, CLAIM }

    public record Chunk(int x, int z) {
    }
}
