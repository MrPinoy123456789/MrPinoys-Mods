package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiPredicate;

/**
 * The offerings a wild lodestone can hold (decision 2026-10-30): {@code data/pocketdungeons/wild_dungeon/*.json}, each
 * naming one single floor node of an existing dungeon, the promise a player reads on the menu, and the cooldown that
 * follows a clear. The parse and validation are pure so {@code WildDungeonsTest} runs without Minecraft.
 *
 * <p>Loaded once at server start in wild mode. A bad file is rejected on its own and logged; the rest still load.
 */
final class WildDungeons {

    static final String FOLDER = "wild_dungeon";

    /** One offering. {@code dungeon} and {@code node} point into {@code dungeon/*.json}. */
    record Offering(String id, String name, String dungeon, String node, String promise, int cooldownMinutes,
                    int weight, int minDistance, int maxLevel, String type, String variety, List<Bonus> bonusRewards) {

        /** An offering with no type: a plain {@link #DUNGEON}. Kept so a test can build one in nine arguments. */
        Offering(String id, String name, String dungeon, String node, String promise, int cooldownMinutes,
                 int weight, int minDistance, int maxLevel) {
            this(id, name, dungeon, node, promise, cooldownMinutes, weight, minDistance, maxLevel, DUNGEON, "", List.of());
        }

        Offering {
            bonusRewards = bonusRewards == null ? List.of() : List.copyOf(bonusRewards);
        }

        /** Extra path length on top of the configured range: a long dungeon is a longer walk. */
        int pathBonus() {
            return LONG_DUNGEON.equals(type) ? LONG_PATH_BONUS : 0;
        }
    }

    /**
     * An item the clear pays on top of the floor's own rewards (owner, 2026-10-31: the nether and the end are off on
     * the server, so a wild dungeon is the way to get their items). {@code chance} is 0 to 1 per clear. Paid in phase 3.
     */
    record Bonus(String item, int count, double chance) {}

    /** The three kinds of offering (owner, 2026-10-31). {@code variety} says which mine, or which dungeon, within one. */
    static final String MINE = "mine";
    static final String DUNGEON = "dungeon";
    static final String LONG_DUNGEON = "long_dungeon";
    static final List<String> TYPES = List.of(MINE, DUNGEON, LONG_DUNGEON);

    /** Cells added to the planner's min and max path for a long dungeon. */
    static final int LONG_PATH_BONUS = 6;

    private static volatile Map<String, Offering> current = Map.of();

    private WildDungeons() {}

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(WildDungeons::load);
    }

    static Offering byId(String id) {
        return id == null ? null : current.get(id);
    }

    static List<Offering> all() {
        return List.copyOf(current.values());
    }

    /** Replaces the loaded set. Tests use this to seed offerings without a resource manager. */
    static void setForTesting(Map<String, Offering> offerings) {
        current = Map.copyOf(offerings);
    }

    private static void load(MinecraftServer server) {
        if (!WildMode.active()) {
            return;
        }
        loadFrom(server);
    }

    /** Reads the offering files whatever the mode is. {@link #load} gates on wild mode; gametests call this directly. */
    static void loadFrom(MinecraftServer server) {
        Map<String, Offering> loaded = new LinkedHashMap<>();
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(
                server.getResourceManager().listResources(FOLDER, id -> id.getPath().endsWith(".json")).entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        BiPredicate<String, String> nodeExists = (dungeon, node) -> {
            DungeonDef def = DungeonDefs.current().byId(dungeon);
            return def != null && def.node(node) != null;
        };
        for (Map.Entry<Identifier, Resource> entry : sorted) {
            String id = JsonPackSupport.resourceId(entry.getKey(), FOLDER);
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                Offering offering = parse(id, JsonParser.parseReader(reader).getAsJsonObject());
                List<String> problems = problems(offering, nodeExists);
                if (problems.isEmpty()) {
                    loaded.put(id, offering);
                } else {
                    PocketDungeonsMod.LOG.error("Rejected wild dungeon '{}': {}", id, problems);
                }
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error("Rejected wild dungeon '{}': {}", id, e.getMessage(), e);
            }
        }
        current = Map.copyOf(loaded);
        PocketDungeonsMod.LOG.info("Loaded {} wild dungeon offering(s)", loaded.size());
    }

    /** Reads one offering; a missing text field is a problem {@link #problems} reports, not an exception. */
    static Offering parse(String id, JsonObject json) {
        return new Offering(id,
                text(json, "name"),
                text(json, "dungeon"),
                text(json, "node"),
                text(json, "promise"),
                number(json, "cooldownMinutes", 0),
                number(json, "weight", 1),
                number(json, "minDistance", 0),
                number(json, "maxLevel", 1),
                json.has("type") ? text(json, "type") : DUNGEON,
                text(json, "variety"),
                bonuses(json));
    }

    /** Everything wrong with an offering, empty when it is usable. */
    static List<String> problems(Offering o, BiPredicate<String, String> nodeExists) {
        List<String> out = new ArrayList<>();
        if (o.name().isBlank()) {
            out.add("name is empty");
        }
        if (o.promise().isBlank()) {
            out.add("promise is empty");
        }
        if (o.cooldownMinutes() < 1) {
            out.add("cooldownMinutes must be at least 1");
        }
        if (o.weight() < 1) {
            out.add("weight must be at least 1");
        }
        if (o.minDistance() < 0) {
            out.add("minDistance must be 0 or more");
        }
        if (o.maxLevel() < 1) {
            out.add("maxLevel must be at least 1");
        }
        for (Bonus b : o.bonusRewards()) {
            if (!b.item().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || b.count() < 1 || b.count() > 64
                    || !(b.chance() > 0.0 && b.chance() <= 1.0)) {
                out.add("bad bonus reward " + b);
            }
        }
        if (!TYPES.contains(o.type())) {
            out.add("type must be one of " + TYPES);
        }
        if (o.variety().isBlank()) {
            out.add("variety is empty (which mine or dungeon this is)");
        }
        if (o.dungeon().isBlank() || o.node().isBlank()) {
            out.add("dungeon and node are required");
        } else if (!nodeExists.test(o.dungeon(), o.node())) {
            out.add("no node " + o.node() + " in dungeon " + o.dungeon());
        }
        return out;
    }

    /**
     * One offering chosen by weight among those whose {@code minDistance} the lodestone's distance from spawn meets, or
     * {@code null} when none qualifies.
     */
    static Offering pick(List<Offering> offerings, double distanceFromSpawn, Random random) {
        List<Offering> fit = new ArrayList<>();
        int total = 0;
        for (Offering o : offerings) {
            if (distanceFromSpawn >= o.minDistance()) {
                fit.add(o);
                total += o.weight();
            }
        }
        if (fit.isEmpty()) {
            return null;
        }
        Collections.sort(fit, (a, b) -> a.id().compareTo(b.id()));
        int roll = random.nextInt(total);
        for (Offering o : fit) {
            roll -= o.weight();
            if (roll < 0) {
                return o;
            }
        }
        return fit.get(fit.size() - 1);
    }

    private static List<Bonus> bonuses(JsonObject json) {
        List<Bonus> out = new ArrayList<>();
        if (json.has("bonusRewards") && json.get("bonusRewards").isJsonArray()) {
            for (com.google.gson.JsonElement element : json.getAsJsonArray("bonusRewards")) {
                if (!element.isJsonObject()) {
                    out.add(new Bonus("", 0, -1.0));
                    continue;
                }
                JsonObject o = element.getAsJsonObject();
                double chance = o.has("chance") && o.get("chance").isJsonPrimitive() ? o.get("chance").getAsDouble() : 1.0;
                out.add(new Bonus(text(o, "item"), number(o, "count", 1), chance));
            }
        }
        return out;
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static int number(JsonObject json, String key, int fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
    }
}
