package unstick;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config at {@code config/unstick.json}. Same contract as the rest of the
 * suite: missing -> write defaults; fails to parse -> defaults in memory, file
 * untouched; parses -> use it.
 *
 * <p>Tuning notes: {@code stuckTicks} is how long a villager must be navigating
 * without making {@code moveThreshold} blocks of horizontal progress before it
 * counts as wedged. 100 ticks (5s) is long enough that legitimate pauses
 * (waiting for a door, yielding to another mob) do not trip it, while genuine
 * pathfinder wedges persist indefinitely and clear it easily. {@code
 * rescueDistance} is the teleport nudge toward the destination; kept small so
 * the rescue is barely visible. {@code cooldownTicks} stops a rescued villager
 * from being re-nudged before vanilla has had a chance to re-path from the new
 * position.
 */
public final class UnstickConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static int stuckTicks = 100;
    private static double moveThreshold = 0.3;
    private static double rescueDistance = 2.0;
    private static int cooldownTicks = 60;

    private UnstickConfig() {}

    /** Loads {@code unstick.json}, creating it only when it does not yet exist. */
    public static void load(Path configDir) {
        Path file = configDir.resolve("unstick.json");
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaultsJson(), w);
                }
                UnstickMod.LOG.info("Created default unstick.json");
                applyDefaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("unstick.json is empty");
                }
                apply(parsed);
            }
        } catch (Exception e) {
            // Defaults in memory, file untouched; an operator's broken-but-
            // recoverable edit is worth more than a tidy file.
            UnstickMod.LOG.error("unstick.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            applyDefaults();
        }
    }

    public static int stuckTicks() {
        return stuckTicks;
    }

    public static double moveThreshold() {
        return moveThreshold;
    }

    public static double rescueDistance() {
        return rescueDistance;
    }

    public static int cooldownTicks() {
        return cooldownTicks;
    }

    private static void applyDefaults() {
        stuckTicks = 100;
        moveThreshold = 0.3;
        rescueDistance = 2.0;
        cooldownTicks = 60;
    }

    private static void apply(JsonObject root) {
        stuckTicks = root.has("stuckTicks") ? root.get("stuckTicks").getAsInt() : 100;
        moveThreshold = root.has("moveThreshold") ? root.get("moveThreshold").getAsDouble() : 0.3;
        rescueDistance = root.has("rescueDistance") ? root.get("rescueDistance").getAsDouble() : 2.0;
        cooldownTicks = root.has("cooldownTicks") ? root.get("cooldownTicks").getAsInt() : 60;
    }

    private static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("stuckTicks", 100);
        root.addProperty("moveThreshold", 0.3);
        root.addProperty("rescueDistance", 2.0);
        root.addProperty("cooldownTicks", 60);
        return root;
    }
}
