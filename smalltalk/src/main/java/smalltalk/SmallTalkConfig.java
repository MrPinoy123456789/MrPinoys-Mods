package smalltalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config at {@code config/smalltalk.json}. Suite's readOrCreate contract:
 * missing -&gt; write defaults; fails to parse -&gt; defaults in memory, file
 * untouched; parses -&gt; use it. See SPEC.md section 14.
 */
public final class SmallTalkConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static int giftCooldownTicks = 24000;
    private static int familiarityDecayPerWeek = 1;
    private static int maxTrackedPlayersPerVillager = 8;
    private static int acquaintanceRadius = 48;
    private static boolean returnGifts = true;
    private static boolean sneakSkipsDialogue = true;
    private static boolean requestsEnabled = false;
    private static int maxActiveTasks = 3;
    /** SPEC.md section 12.3: per-resident daily-cadence gate; mirrors {@code giftCooldownTicks}. */
    private static int requestCooldownTicks = 24000;
    /** category name -> item ids that count as belonging to it (SPEC.md section 2's quirks). */
    private static Map<String, List<String>> itemCategories = defaultCategories();

    private SmallTalkConfig() {}

    /** Loads the operator configuration, preserving an existing file that fails to parse. */
    public static void load(Path configDir) {
        Path file = configDir.resolve("smalltalk.json");
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaultsJson(), w);
                }
                SmallTalkMod.LOG.info("Created default smalltalk.json");
                applyDefaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("smalltalk.json is empty");
                }
                apply(parsed);
            }
        } catch (Exception e) {
            // Defaults in memory, file untouched -- an operator's broken-but-
            // recoverable edit is worth more than a tidy file.
            SmallTalkMod.LOG.error("smalltalk.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            applyDefaults();
        }
    }

    public static int giftCooldownTicks() { return giftCooldownTicks; }
    public static int familiarityDecayPerWeek() { return familiarityDecayPerWeek; }
    public static int maxTrackedPlayersPerVillager() { return maxTrackedPlayersPerVillager; }
    public static int acquaintanceRadius() { return acquaintanceRadius; }
    public static boolean returnGifts() { return returnGifts; }
    public static boolean sneakSkipsDialogue() { return sneakSkipsDialogue; }
    public static boolean requestsEnabled() { return requestsEnabled; }
    public static int maxActiveTasks() { return maxActiveTasks; }
    public static int requestCooldownTicks() { return requestCooldownTicks; }
    public static Map<String, List<String>> itemCategories() { return itemCategories; }

    private static void applyDefaults() {
        giftCooldownTicks = 24000;
        familiarityDecayPerWeek = 1;
        maxTrackedPlayersPerVillager = 8;
        acquaintanceRadius = 48;
        returnGifts = true;
        sneakSkipsDialogue = true;
        requestsEnabled = false;
        maxActiveTasks = 3;
        requestCooldownTicks = 24000;
        itemCategories = defaultCategories();
    }

    private static void apply(JsonObject root) {
        giftCooldownTicks = getInt(root, "giftCooldownTicks", 24000);
        familiarityDecayPerWeek = getInt(root, "familiarityDecayPerWeek", 1);
        maxTrackedPlayersPerVillager = getInt(root, "maxTrackedPlayersPerVillager", 8);
        acquaintanceRadius = getInt(root, "acquaintanceRadius", 48);
        returnGifts = getBool(root, "returnGifts", true);
        sneakSkipsDialogue = getBool(root, "sneakSkipsDialogue", true);
        requestsEnabled = getBool(root, "requestsEnabled", false);
        maxActiveTasks = getInt(root, "maxActiveTasks", 3);
        requestCooldownTicks = getInt(root, "requestCooldownTicks", 24000);

        Map<String, List<String>> parsedCategories = new LinkedHashMap<>();
        if (root.has("itemCategories") && root.get("itemCategories").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("itemCategories").entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                entry.getValue().getAsJsonArray().forEach(el -> ids.add(el.getAsString()));
                parsedCategories.put(entry.getKey(), ids);
            }
        }
        itemCategories = parsedCategories.isEmpty() ? defaultCategories() : parsedCategories;
    }

    private static Map<String, List<String>> defaultCategories() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("FOOD", List.of("minecraft:bread", "minecraft:apple", "minecraft:cooked_salmon",
                "minecraft:cake", "minecraft:cookie", "minecraft:carrot"));
        map.put("FLOWERS", List.of("minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower",
                "minecraft:sunflower", "minecraft:orchid"));
        map.put("ORES_GEMS", List.of("minecraft:emerald", "minecraft:diamond", "minecraft:amethyst_shard",
                "minecraft:lapis_lazuli", "minecraft:quartz"));
        map.put("BOOKS_PAPER", List.of("minecraft:book", "minecraft:paper", "minecraft:written_book",
                "minecraft:map"));
        // Safe to include again now that gifting only happens through a deliberate
        // dialog pick (SPEC.md section 6.2) rather than a bare right-click while
        // holding an item -- the earlier exclusion existed only because that older
        // flow could consume a tool a player was holding for an unrelated reason.
        map.put("TOOLS", List.of("minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel",
                "minecraft:fishing_rod"));
        map.put("REDSTONE", List.of("minecraft:redstone", "minecraft:repeater", "minecraft:comparator",
                "minecraft:piston"));
        map.put("DECORATION", List.of("minecraft:flower_pot", "minecraft:painting", "minecraft:item_frame",
                "minecraft:candle"));
        map.put("MUSIC", List.of("minecraft:note_block", "minecraft:jukebox", "minecraft:music_disc_cat"));
        return map;
    }

    private static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("giftCooldownTicks", 24000);
        root.addProperty("familiarityDecayPerWeek", 1);
        root.addProperty("maxTrackedPlayersPerVillager", 8);
        root.addProperty("acquaintanceRadius", 48);
        root.addProperty("returnGifts", true);
        root.addProperty("sneakSkipsDialogue", true);
        root.addProperty("requestsEnabled", false);
        root.addProperty("maxActiveTasks", 3);
        root.addProperty("requestCooldownTicks", 24000);

        JsonObject categories = new JsonObject();
        for (Map.Entry<String, List<String>> entry : defaultCategories().entrySet()) {
            JsonArray arr = new JsonArray();
            entry.getValue().forEach(arr::add);
            categories.add(entry.getKey(), arr);
        }
        root.add("itemCategories", categories);
        return root;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) ? obj.get(key).getAsInt() : fallback;
    }

    private static boolean getBool(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : fallback;
    }
}
