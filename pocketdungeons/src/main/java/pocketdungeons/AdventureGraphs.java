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
import java.util.Set;

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
        AdventureGraphs loaded = parse(server, themeId -> ThemeManifest.current().byId(themeId) != null);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon adventure node(s) ({} rejected)",
                loaded.graph.size(), loaded.rejections.size());
        return loaded;
    }

    /**
     * M68: parses every {@code dungeon_adventure} resource into a graph
     * without publishing it, the build half of {@link ContentReload}'s atomic
     * reload. Keys nodes by namespaced id ({@code namespace:path}) and
     * qualifies each transition's theme reference the same way a room theme
     * list is qualified, so a legacy bare transition ({@code "deepslate"})
     * resolves to {@code pocketdungeons:deepslate} and a third party qualified
     * transition ({@code "mypack:deepslate"}) is used as is. The fixpoint
     * unresolved transition removal takes a {@code themeExists} predicate the
     * caller owns, so {@link ContentSnapshot} can validate transitions against
     * the snapshot's own theme set before either the themes or the graph is
     * published.
     */
    static AdventureGraphs parse(MinecraftServer server, java.util.function.Predicate<String> themeExists) {
        Map<String, AdventureGraph.Node> nodes = new HashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_adventure", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "dungeon_adventure");
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
        removeUnresolvedTransitions(nodes, rejections, themeExists);
        AdventureGraph graph = AdventureGraph.of(nodes);
        if (graph.entryThemes().isEmpty() && !nodes.isEmpty()) {
            rejections.add("no ENTRY-kind theme in the graph; a boss run would have nowhere to reset to");
        }
        return new AdventureGraphs(graph, rejections);
    }

    /**
     * M68: commits a resolved adventure graph as the live {@link #current},
     * the publish half of {@link ContentReload}'s atomic build then commit.
     */
    static void publish(AdventureGraphs adventure) {
        current = adventure;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon adventure node(s) ({} rejected)",
                adventure.graph.size(), adventure.rejections.size());
    }

    /**
     * Drops every node with a transition to a theme {@code themeExists}
     * rejects or to a theme that has no node of its own in {@code nodes},
     * appending a reason to {@code rejections} for each. Repeats until a full
     * pass removes nothing, rather than a single pass (PD-22): a single pass
     * tested {@code nodes.containsKey} against the live map while removing
     * entries from it mid-loop, so whether a dangling edge was caught
     * depended on {@code HashMap}'s iteration order, a node validated before
     * the node it points at was removed kept a dangling edge. Each pass here
     * checks every remaining node against a snapshot of the keys still
     * standing at the start of that pass, so a pass's own removals cannot
     * affect that pass's own verdicts. Package-private, and taking
     * {@code themeExists} rather than reaching for {@link ThemeManifest}
     * directly, so {@code AdventureGraphTest} can exercise the fixpoint
     * property on a plain map with no resource manager involved.
     */
    static void removeUnresolvedTransitions(Map<String, AdventureGraph.Node> nodes, List<String> rejections,
                                            java.util.function.Predicate<String> themeExists) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> stillPresent = Set.copyOf(nodes.keySet());
            for (Map.Entry<String, AdventureGraph.Node> entry : new ArrayList<>(nodes.entrySet())) {
                AdventureGraph.Node node = entry.getValue();
                boolean valid = true;
                for (AdventureGraph.Transition transition : node.next()) {
                    if (!themeExists.test(transition.theme())) {
                        rejections.add(entry.getKey() + " - transition theme not found: " + transition.theme());
                        valid = false;
                    } else if (!stillPresent.contains(transition.theme())) {
                        rejections.add(entry.getKey() + " - transition target has no adventure node: "
                                + transition.theme());
                        valid = false;
                    }
                }
                if (!valid) {
                    nodes.remove(entry.getKey());
                    changed = true;
                }
            }
        }
    }

    private static AdventureGraph.Node parseNode(String theme, JsonObject obj) {
        JsonPackSupport.parseVersion(obj, theme);
        String kindWord = JsonPackSupport.requiredString(obj, "kind");
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
                String edgeTheme = JsonPackSupport.qualify(JsonPackSupport.requiredString(edge, "theme"));
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

    static AdventureGraphs current() {
        return current;
    }

    AdventureGraph graph() {
        return graph;
    }

    List<String> rejections() {
        return rejections;
    }

}
