package wayfarers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import wayfarers.core.Body;
import wayfarers.core.EncounterDefinition;
import wayfarers.core.EncounterPool;
import wayfarers.core.LinePools;
import wayfarers.core.Listing;
import wayfarers.core.ReadOrCreate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator-facing config for Wayfarers.
 *
 * <p>Uses {@link ReadOrCreate} from core so the file lifecycle rules are tested,
 * while the actual JSON parsing stays in the fabric module where Gson lives.
 */
public final class WayfarersConfig {

    private static final String DEFAULT_ENCOUNTERS = """
            {
              "encounters": [
                {
                  "id": "lost_trader",
                  "weight": 20,
                  "template": "patient",
                  "biomes": [],
                  "nightOnly": false,
                  "weather": [],
                  "once": false,
                  "recurring": true,
                  "body": {
                    "type": "minecraft:wandering_trader",
                    "name": "Wayfarer",
                    "nameVisible": true,
                    "count": 1
                  },
                  "dialogue": "lost_trader",
                  "gossipChance": 0.2,
                  "sells": [
                    {
                      "id": "spirit_stone",
                      "item": "minecraft:echo_shard",
                      "quantity": 1,
                      "price": 2,
                      "currency": "diamond",
                      "stock": 5,
                      "components": {
                        "minecraft:custom_data": { "spiritwolves": { "bound": false } },
                        "minecraft:item_name": "Spirit Stone",
                        "minecraft:lore": ["Use on a tamed wolf to bind its soul."]
                      }
                    }
                  ],
                  "expireMinutes": 15
                },
                {
                  "id": "pillager_patrol",
                  "weight": 10,
                  "template": "hostile",
                  "biomes": [],
                  "nightOnly": false,
                  "weather": [],
                  "once": false,
                  "recurring": false,
                  "body": {
                    "type": "minecraft:pillager",
                    "name": "Pillager",
                    "nameVisible": true,
                    "count": 3
                  },
                  "wants": "minecraft:emerald",
                  "coin": 5,
                  "drops": "minecraft:chests/pillager_outpost",
                  "expireMinutes": 5
                },
                {
                  "id": "stray_donkey",
                  "weight": 8,
                  "template": "patient",
                  "biomes": [],
                  "nightOnly": false,
                  "weather": [],
                  "once": false,
                  "recurring": false,
                  "body": {
                    "type": "minecraft:donkey",
                    "name": "Stray Donkey",
                    "nameVisible": true,
                    "count": 1
                  },
                  "expireMinutes": 15
                }
              ]
            }
            """;

    private static final String DEFAULT_SETTINGS = """
            {
              "enabled": true,
              "checkIntervalSeconds": 120,
              "minSessionMinutes": 1,
              "cooldownMinutes": 25,
              "recentActivitySeconds": 60,
              "maxSimultaneous": 2,
              "proximityBlocks": 128,
              "chance": 0.05,
              "expireMinutes": 15,
              "despawnDistanceBlocks": 128,
              "despawnFarMinutes": 1,
              "hostileMinPlayMinutes": 60,
              "hostileWorldSpawnBlocks": 128
            }
            """;

    private static final String DEFAULT_LINES = """
            {
              "lost_trader.open": ["You again. The stone I sold you, is it still humming?"],
              "lost_trader.during": ["...", "Keep your eyes on the road, not the coin."],
              "lost_trader.deny": ["Hands off the wares, friend."],
              "lost_trader.attack": ["That's the last time I trust a stranger!"],
              "lost_trader.idle": ["Wares.", "Trade?", "..."],
              "pillager_patrol.open": ["Hold. Empty your hands or pay the toll."],
              "pillager_patrol.placated": ["The gold glitters. Pass, while you can."],
              "pillager_patrol.deny": ["Wrong answer."],
              "pillager_patrol.attack": ["Take them!"],
              "stray_donkey.open": ["A donkey, alone on the road."],
              "stray_donkey.during": ["It stamps the dirt, waiting."],
              "stray_donkey.deny": ["It shies away."],
              "gossip": ["I heard the western road is flooded."]
            }
            """;

    private final Path dir;
    private EncounterPool encounters = defaultEncounters();
    private Settings settings = defaultSettings();
    private LinePools lines = defaultLines();

    public WayfarersConfig(Path configDir) {
        this.dir = configDir.resolve(WayfarersMod.MOD_ID);
    }

    /** Reloads encounters, settings, and dialogue pools using the suite's non-destructive file policy. */
    public void reload() {
        Path encountersFile = dir.resolve("encounters.json");
        ReadOrCreate.Result<EncounterPool> e = ReadOrCreate.load(
                encountersFile, defaultEncounters(), WayfarersConfig::parseEncounters, pool -> DEFAULT_ENCOUNTERS, WayfarersMod.LOG::info);
        this.encounters = e.value();

        Path settingsFile = dir.resolve("settings.json");
        ReadOrCreate.Result<Settings> s = ReadOrCreate.load(
                settingsFile, defaultSettings(), WayfarersConfig::parseSettings, set -> DEFAULT_SETTINGS, WayfarersMod.LOG::info);
        this.settings = s.value();

        Path linesFile = dir.resolve("lines.json");
        ReadOrCreate.Result<LinePools> l = ReadOrCreate.load(
                linesFile, defaultLines(), WayfarersConfig::parseLines, set -> DEFAULT_LINES, WayfarersMod.LOG::info);
        this.lines = l.value();

        Drops.forgetWarnings();
    }

    public EncounterPool encounters() { return encounters; }
    public Settings settings() { return settings; }
    public LinePools lines() { return lines; }

    private static EncounterPool defaultEncounters() {
        return parseEncounters(DEFAULT_ENCOUNTERS);
    }

    private static Settings defaultSettings() {
        return parseSettings(DEFAULT_SETTINGS);
    }

    private static LinePools defaultLines() {
        return parseLines(DEFAULT_LINES);
    }

    private static EncounterPool parseEncounters(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("encounters");
        List<EncounterDefinition> defs = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String id = getString(obj, "id");
            int weight = getInt(obj, "weight", 0);
            String template = getString(obj, "template");
            List<String> biomes = getStringList(obj, "biomes");
            boolean nightOnly = getBool(obj, "nightOnly", false);
            List<String> weather = getStringList(obj, "weather");
            boolean once = getBool(obj, "once", false);
            boolean recurring = getBool(obj, "recurring", false);
            Body body = parseBody(obj.getAsJsonObject("body"));
            int expire = getInt(obj, "expireMinutes", 15);
            String dialogue = getString(obj, "dialogue");
            double gossip = getDouble(obj, "gossipChance", 0.0);
            List<Listing> sells = parseListings(obj.getAsJsonArray("sells"));
            List<Listing> buys = parseListings(obj.getAsJsonArray("buys"));
            int coin = getInt(obj, "coin", 0);
            String wants = getString(obj, "wants");
            String drops = getString(obj, "drops");
            Map<String, Object> extras = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (!Set.of("id", "weight", "template", "biomes", "nightOnly",
                        "weather", "once", "recurring", "body", "expireMinutes",
                        "dialogue", "gossipChance", "sells", "buys", "coin", "wants",
                        "drops").contains(entry.getKey())) {
                    extras.put(entry.getKey(), entry.getValue().toString());
                }
            }
            if (id == null || id.isBlank() || body == null) continue;
            known.add(template);
            defs.add(new EncounterDefinition(id, weight, template, biomes, nightOnly, weather,
                    once, recurring, body, expire, dialogue, gossip, sells, buys, coin, wants,
                    drops, extras));
        }
        return new EncounterPool(defs, known);
    }

    private static Body parseBody(JsonObject obj) {
        if (obj == null) return null;
        String type = getString(obj, "type");
        String name = getString(obj, "name");
        boolean visible = getBool(obj, "nameVisible", true);
        int count = getInt(obj, "count", 1);
        return new Body(type, name, visible, count);
    }

    private static List<Listing> parseListings(JsonArray arr) {
        if (arr == null) return List.of();
        List<Listing> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            Listing l = parseListing(el.getAsJsonObject());
            if (l != null) out.add(l);
        }
        return out;
    }

    private static Listing parseListing(JsonObject obj) {
        String id = getString(obj, "id");
        String item = getString(obj, "item");
        if (item == null || item.isBlank()) return null;
        if (id == null || id.isBlank()) id = item;
        String tag = getString(obj, "tag");
        int quantity = getInt(obj, "quantity", 1);
        int price = getInt(obj, "price", 0);
        String currency = getString(obj, "currency");
        int stock = getInt(obj, "stock", 1);
        JsonElement comp = obj.get("components");
        String components = (comp == null) ? "" : comp.toString();

        if ("cobble".equalsIgnoreCase(currency) && price > 640) {
            WayfarersMod.LOG.warn("Cobble listing price {} for {} exceeds the 640 budget sanity limit", price, item);
        }

        return new Listing(id, item, tag, quantity, price, currency, stock, components);
    }

    private static LinePools parseLines(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Map<String, List<String>> pools = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            List<String> lines = new ArrayList<>();
            for (JsonElement e : entry.getValue().getAsJsonArray()) {
                if (e.isJsonPrimitive()) lines.add(e.getAsString());
            }
            if (!lines.isEmpty()) pools.put(entry.getKey(), lines);
        }
        return new LinePools(pools);
    }

    private static Settings parseSettings(String text) {
        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
        return new Settings(
                getBool(obj, "enabled", true),
                getInt(obj, "checkIntervalSeconds", 120),
                getInt(obj, "minSessionMinutes", 1),
                getInt(obj, "cooldownMinutes", 25),
                getInt(obj, "recentActivitySeconds", 60),
                getInt(obj, "maxSimultaneous", 2),
                getInt(obj, "proximityBlocks", 128),
                getDouble(obj, "chance", 0.05),
                getInt(obj, "expireMinutes", 15),
                getInt(obj, "despawnDistanceBlocks", 128),
                getInt(obj, "despawnFarMinutes", 1),
                getInt(obj, "hostileMinPlayMinutes", 60),
                getInt(obj, "hostileWorldSpawnBlocks", 128));
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsInt(); } catch (NumberFormatException ex) { return fallback; }
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsDouble(); } catch (NumberFormatException ex) { return fallback; }
    }

    private static boolean getBool(JsonObject obj, String key, boolean fallback) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsBoolean(); } catch (Exception ex) { return fallback; }
    }

    private static List<String> getStringList(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement el : e.getAsJsonArray()) {
            if (el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }

    public record Settings(
            boolean enabled,
            int checkIntervalSeconds,
            int minSessionMinutes,
            int cooldownMinutes,
            int recentActivitySeconds,
            int maxSimultaneous,
            int proximityBlocks,
            double chance,
            int expireMinutes,
            int despawnDistanceBlocks,
            int despawnFarMinutes,
            /** Total play time a player needs before a hostile encounter can find them (§M5). */
            int hostileMinPlayMinutes,
            /** No hostile encounter starts within this many blocks of world spawn (§M5). */
            int hostileWorldSpawnBlocks) {}
}
