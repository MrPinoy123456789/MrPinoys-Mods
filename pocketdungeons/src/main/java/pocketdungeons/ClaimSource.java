package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * The claimed land of the Charter mod, read from its {@code areas.json} (plan 4.4): the pure parse of an immutable
 * snapshot plus a thin reader. Only overworld entries count and every kind is claimed land. The reader fails closed: a
 * configured source that is missing or unreadable means nothing is placed.
 */
final class ClaimSource {

    static final String OVERWORLD = "minecraft:overworld";

    /** An immutable set of packed overworld chunk coordinates. */
    record Snapshot(Set<Long> chunks) {

        Snapshot {
            chunks = Set.copyOf(chunks);
        }

        /** Whether any claimed chunk lies within {@code bufferBlocks} of the block point. */
        boolean isClaimedNear(int x, int z, int bufferBlocks) {
            int minCx = (x - bufferBlocks) >> 4;
            int maxCx = (x + bufferBlocks) >> 4;
            int minCz = (z - bufferBlocks) >> 4;
            int maxCz = (z + bufferBlocks) >> 4;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    if (chunks.contains(pack(cx, cz))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private ClaimSource() {}

    static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Parses the file text. An entry that fails is skipped; when no entry parses out of a non empty array the whole file
     * is treated as unreadable (a format change).
     *
     * @throws IllegalArgumentException when the text is not a usable claims file
     */
    static Snapshot parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("claims file is not JSON: " + e.getMessage(), e);
        }
        if (!root.has("areas") || !root.get("areas").isJsonArray()) {
            throw new IllegalArgumentException("claims file has no areas array");
        }
        JsonArray areas = root.getAsJsonArray("areas");
        Set<Long> chunks = new HashSet<>();
        int parsed = 0;
        for (JsonElement element : areas) {
            try {
                JsonObject area = element.getAsJsonObject();
                String dimension = area.get("dimension").getAsString();
                JsonArray list = area.getAsJsonArray("chunks");
                Set<Long> mine = new HashSet<>();
                for (JsonElement pair : list) {
                    JsonArray xz = pair.getAsJsonArray();
                    mine.add(pack(xz.get(0).getAsInt(), xz.get(1).getAsInt()));
                }
                parsed++;
                if (OVERWORLD.equals(dimension)) {
                    chunks.addAll(mine);
                }
            } catch (RuntimeException e) {
                PocketDungeonsMod.LOG.warn("Skipping an unreadable claim entry: {}", e.getMessage());
            }
        }
        if (areas.size() > 0 && parsed == 0) {
            throw new IllegalArgumentException("no claim entry could be read; the format may have changed");
        }
        return new Snapshot(chunks);
    }

    /** Reads and parses a file; throws when it is missing or unusable so the caller can fail closed. */
    static Snapshot read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }
}
