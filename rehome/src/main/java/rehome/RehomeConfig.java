package rehome;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Config at {@code config/rehome.json}. Suite's readOrCreate contract: missing
 * -&gt; write defaults; fails to parse -&gt; defaults in memory, file untouched;
 * parses -&gt; use it. See SPEC.md section 10.
 */
public final class RehomeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** item id -> profession ids that accept it, or ["*"] for any villager. */
    private static Map<String, List<String>> gifts = defaultGifts();
    private static double giftAcceptChance = 0.6;
    private static double followStartDistance = 6.0;
    private static double followTeleportDistance = 24.0;
    private static int claimWatchTicks = 200;

    private RehomeConfig() {}

    public static void load(Path configDir) {
        Path file = configDir.resolve("rehome.json");
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaultsJson(), w);
                }
                RehomeMod.LOG.info("Created default rehome.json");
                applyDefaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("rehome.json is empty");
                }
                apply(parsed);
            }
        } catch (Exception e) {
            // Defaults in memory, file untouched -- an operator's broken-but-
            // recoverable edit is worth more than a tidy file.
            RehomeMod.LOG.error("rehome.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            applyDefaults();
        }
    }

    public static Map<String, List<String>> gifts() {
        return gifts;
    }

    public static double giftAcceptChance() {
        return giftAcceptChance;
    }

    public static double followStartDistance() {
        return followStartDistance;
    }

    public static double followTeleportDistance() {
        return followTeleportDistance;
    }

    public static int claimWatchTicks() {
        return claimWatchTicks;
    }

    private static void applyDefaults() {
        gifts = defaultGifts();
        giftAcceptChance = 0.6;
        followStartDistance = 6.0;
        followTeleportDistance = 24.0;
        claimWatchTicks = 200;
    }

    private static void apply(JsonObject root) {
        Map<String, List<String>> parsedGifts = new HashMap<>();
        if (root.has("gifts") && root.get("gifts").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("gifts").entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                List<String> professions = new java.util.ArrayList<>();
                entry.getValue().getAsJsonArray().forEach(el -> professions.add(el.getAsString()));
                parsedGifts.put(entry.getKey(), professions);
            }
        }
        gifts = parsedGifts.isEmpty() ? defaultGifts() : parsedGifts;

        giftAcceptChance = root.has("giftAcceptChance") ? root.get("giftAcceptChance").getAsDouble() : 0.6;
        followStartDistance = root.has("followStartDistance") ? root.get("followStartDistance").getAsDouble() : 6.0;
        followTeleportDistance = root.has("followTeleportDistance")
                ? root.get("followTeleportDistance").getAsDouble() : 24.0;
        claimWatchTicks = root.has("claimWatchTicks") ? root.get("claimWatchTicks").getAsInt() : 200;
    }

    private static Map<String, List<String>> defaultGifts() {
        Map<String, List<String>> map = new HashMap<>();
        map.put("minecraft:bread", List.of("*"));
        map.put("minecraft:emerald", List.of("*"));
        map.put("minecraft:poppy", List.of("*"));
        map.put("minecraft:wheat", List.of("farmer"));
        map.put("minecraft:paper", List.of("librarian"));
        return map;
    }

    private static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        JsonObject giftsJson = new JsonObject();
        for (Map.Entry<String, List<String>> entry : defaultGifts().entrySet()) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            entry.getValue().forEach(arr::add);
            giftsJson.add(entry.getKey(), arr);
        }
        root.add("gifts", giftsJson);
        root.addProperty("giftAcceptChance", 0.6);
        root.addProperty("followStartDistance", 6.0);
        root.addProperty("followTeleportDistance", 24.0);
        root.addProperty("claimWatchTicks", 200);
        return root;
    }
}
