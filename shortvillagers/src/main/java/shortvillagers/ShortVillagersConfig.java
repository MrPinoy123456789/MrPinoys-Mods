package shortvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config at {@code config/shortvillagers.json}. Same contract as the rest of
 * the suite: missing -> write defaults; fails to parse -> defaults in memory,
 * file untouched; parses -> use it.
 *
 * <p>The only knob is {@code targetHeight}. Vanilla villagers are 1.95; the
 * default of 1.9 clears carpet (0.0625) under a 2-block ceiling with 0.0375
 * of margin. Going lower (1.8, the pre-1.9 value) gives more margin but
 * shrinks the click/projectile hitbox more. Going above 1.9375 risks
 * re-triggering the carpet bug.
 */
public final class ShortVillagersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static double targetHeight = 1.0;

    private ShortVillagersConfig() {}

    /** Loads {@code shortvillagers.json}, creating it only when it does not yet exist. */
    public static void load(Path configDir) {
        Path file = configDir.resolve("shortvillagers.json");
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaultsJson(), w);
                }
                ShortVillagersMod.LOG.info("Created default shortvillagers.json");
                applyDefaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("shortvillagers.json is empty");
                }
                apply(parsed);
            }
        } catch (Exception e) {
            // Defaults in memory, file untouched; an operator's broken-but-
            // recoverable edit is worth more than a tidy file.
            ShortVillagersMod.LOG.error("shortvillagers.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            applyDefaults();
        }
    }

    public static double targetHeight() {
        return targetHeight;
    }

    private static void applyDefaults() {
        targetHeight = 1.0;
    }

    private static void apply(JsonObject root) {
        targetHeight = root.has("targetHeight") ? root.get("targetHeight").getAsDouble() : 1.0;
    }

    private static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("targetHeight", 1.0);
        return root;
    }
}
