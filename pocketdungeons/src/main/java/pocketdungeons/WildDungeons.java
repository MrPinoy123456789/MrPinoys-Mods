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
                    int weight, int minDistance, int maxLevel) {}

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
                number(json, "maxLevel", 1));
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

    private static String text(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static int number(JsonObject json, String key, int fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
    }
}
