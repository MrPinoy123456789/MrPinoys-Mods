package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reloadable loader for {@code data/pocketdungeons/dungeon_adventure/*.json},
 * replacing {@code DungeonRecipes.load}. Same shape, on purpose: a
 * {@code volatile current} holder, sorted resource entries, one rejection per
 * malformed file rather than one failure for the whole pack, logged the same
 * way. One file per theme, named for the theme's own id, holding that theme's
 * {@link AdventureGraph.Node}.
 */
final class AdventureGraphs {

    private static volatile AdventureGraphs current = new AdventureGraphs(AdventureGraph.EMPTY, List.of());

    private final AdventureGraph graph;
    private final List<String> rejections;

    private AdventureGraphs(AdventureGraph graph, List<String> rejections) {
        this.graph = graph;
        this.rejections = List.copyOf(rejections);
    }

    static AdventureGraphs load(MinecraftServer server) {
        Map<String, AdventureGraph.Node> nodes = new HashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_adventure", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = baseName(resource.getKey());
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                AdventureGraph.Node node = parseNode(id,
                        JsonParser.parseReader(reader).getAsJsonObject());
                nodes.put(id, node);
            } catch (Exception exception) {
                String reason = resource.getKey() + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon adventure node '{}': {}", id, reason, exception);
            }
        }
        // Every transition target has to resolve, in both senses: a theme
        // ThemeManifest never loaded (typo, unloaded datapack) and a theme this
        // graph itself never declared a node for are both dead ends a door could
        // still be offered into.
        for (Map.Entry<String, AdventureGraph.Node> entry : new ArrayList<>(nodes.entrySet())) {
            AdventureGraph.Node node = entry.getValue();
            boolean valid = true;
            for (AdventureGraph.Transition transition : node.next()) {
                if (ThemeManifest.current().byId(transition.theme()) == null) {
                    rejections.add(entry.getKey() + " - transition theme not found: " + transition.theme());
                    valid = false;
                } else if (!nodes.containsKey(transition.theme())) {
                    rejections.add(entry.getKey() + " - transition target has no adventure node: "
                            + transition.theme());
                    valid = false;
                }
            }
            if (!valid) {
                nodes.remove(entry.getKey());
            }
        }
        AdventureGraph graph = AdventureGraph.of(nodes);
        if (graph.entryThemes().isEmpty() && !nodes.isEmpty()) {
            rejections.add("no ENTRY-kind theme in the graph; a boss run would have nowhere to reset to");
        }
        AdventureGraphs loaded = new AdventureGraphs(graph, rejections);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon adventure node(s) ({} rejected)",
                nodes.size(), rejections.size());
        return loaded;
    }

    private static AdventureGraph.Node parseNode(String theme, JsonObject obj) {
        String kindWord = requiredString(obj, "kind");
        AdventureGraph.Kind kind = switch (kindWord) {
            case "entry" -> AdventureGraph.Kind.ENTRY;
            case "descent" -> AdventureGraph.Kind.DESCENT;
            case "boss" -> AdventureGraph.Kind.BOSS;
            default -> throw new IllegalArgumentException("kind must be entry, descent or boss: " + kindWord);
        };
        List<AdventureGraph.Transition> next = new ArrayList<>();
        JsonElement nextElement = obj.get("next");
        if (nextElement != null && nextElement.isJsonArray()) {
            JsonArray array = nextElement.getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject edge = element.getAsJsonObject();
                String edgeTheme = requiredString(edge, "theme");
                int weight = edge.has("weight") ? edge.get("weight").getAsInt() : 1;
                next.add(new AdventureGraph.Transition(edgeTheme, weight));
            }
        }
        String reward = "";
        JsonElement rewardElement = obj.get("reward");
        if (rewardElement != null && !rewardElement.isJsonNull()) {
            reward = rewardElement.getAsString().trim();
            if (reward.isEmpty()) {
                throw new IllegalArgumentException("reward must not be blank when present");
            }
        }
        return new AdventureGraph.Node(theme, kind, next, reward);
    }

    private static String requiredString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank: " + key);
        }
        return value;
    }

    static AdventureGraphs current() {
        return current;
    }

    AdventureGraph graph() {
        return graph;
    }

    List<String> rejections() {
        return rejections;
    }

    private static String baseName(Identifier location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.substring(0, name.length() - 5);
    }
}
