package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * One dungeon of the structure design (D1 to D22): a named graph of floors
 * ("nodes") with one main theme, belonging to an act. The unit a trip plays.
 * No Minecraft imports, same discipline as {@link AdventureGraph}: parsing a
 * {@link JsonObject} (Gson only) and every structural rule is plain Java, so
 * {@code DungeonDefTest} runs without a server. {@link DungeonDefs} owns the
 * datapack loading around it.
 *
 * <p>Shape and construction are checked in the constructors (a malformed value
 * throws {@link IllegalArgumentException}); the graph rules that need a whole
 * dungeon, and a theme to act lookup across dungeons, live in {@link #problems}.
 *
 * @param nodePalette block ids the dungeon's resource nodes use (D19)
 * @param merchant    the themed merchant id, or an empty string for none
 * @param diary       the diary page a first clear grants, or an empty string for none
 * @param deviation   how a floor may borrow another theme (D6a), or null
 */
record DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
                  List<String> nodePalette, String merchant, String diary, Deviation deviation,
                  List<Node> nodes, List<Edge> edges) {

    /** What a dungeon is for. */
    enum Kind {
        STORY, RESOURCE, CAPSTONE, ENDLESS;

        static Kind parse(String word) {
            return switch (word) {
                case "story" -> STORY;
                case "resource" -> RESOURCE;
                case "capstone" -> CAPSTONE;
                case "endless" -> ENDLESS;
                default -> throw new IllegalArgumentException(
                        "kind must be story, resource, capstone or endless: " + word);
            };
        }
    }

    static final int MIN_LAYERS = 3;
    static final int MAX_LAYERS = 6;
    static final int MAX_EDGES_OUT = 3;
    static final int MIN_ACT = 1;
    static final int MAX_ACT = 5;
    static final int MIN_TIER = 1;
    static final int MAX_TIER = 4;
    static final String DEFAULT_NAMESPACE = "pocketdungeons";

    /**
     * Dungeon structure W5 (node palettes): common structural blocks that must never be in a
     * {@code nodePalette}, because every interior block of a palette block is a mineable node and
     * these would make walls mineable. Bare names (minecraft namespace); {@code *_planks} is
     * matched by suffix in {@link #isStructuralBlock}.
     */
    static final Set<String> STRUCTURAL_BLOCKS = Set.of(
            "stone", "cobblestone", "deepslate", "tuff", "ice", "packed_ice", "blue_ice", "end_stone",
            "prismarine", "prismarine_bricks", "dark_prismarine", "blackstone", "basalt", "netherrack",
            "stone_bricks", "deepslate_bricks", "deepslate_tiles");

    /** Whether a palette block id (namespaced or bare) is a common structural block, not a resource. */
    static boolean isStructuralBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        String id = blockId.trim();
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String name = colon < 0 ? id : id.substring(colon + 1);
        return "minecraft".equals(ns) && (STRUCTURAL_BLOCKS.contains(name) || name.endsWith("_planks"));
    }

    /** The loot tiers this dungeon's act allows (D10), inclusive. */
    record LootBand(int min, int max) {
        LootBand {
            if (min < MIN_TIER || max > MAX_TIER || min > max) {
                throw new IllegalArgumentException("lootBand must satisfy " + MIN_TIER + " <= min <= max <= "
                        + MAX_TIER + ": " + min + " to " + max);
            }
        }

        int clamp(int tier) {
            return Math.max(min, Math.min(max, tier));
        }
    }

    /** D6a: the chance a floor deviates and the themes it may borrow from. */
    record Deviation(double chance, List<String> themes) {
        Deviation {
            if (!(chance >= 0 && chance <= 1)) {
                throw new IllegalArgumentException("deviation chance must be between 0 and 1: " + chance);
            }
            themes = List.copyOf(themes);
        }
    }

    /**
     * One floor variant. {@code theme} is empty when the node uses the main
     * theme; {@code roomCount} is 0 when the floor size is the default;
     * {@code roomBias} may be empty (draw from the dungeon's room pool).
     *
     * <p>{@code light} (W4, D17) is {@code lit}, {@code dim} or {@code dark}, default
     * {@code lit}: the minimum darkness every room of this floor is stamped with. A
     * dark node darkens every room that does not say {@code requiresLight}, and the
     * Feral affix is not dealt on it.
     */
    record Node(String id, String name, int layer, String theme, String signatureAffix,
                List<String> roomBias, int roomCount, boolean isFinal, String light,
                List<Reward> rewards) {

        static final String LIGHT_LIT = "lit";
        static final String LIGHT_DIM = "dim";
        static final String LIGHT_DARK = "dark";

        /** A node with the default light ({@code lit}) and no promised rewards. */
        Node(String id, String name, int layer, String theme, String signatureAffix,
             List<String> roomBias, int roomCount, boolean isFinal) {
            this(id, name, layer, theme, signatureAffix, roomBias, roomCount, isFinal, LIGHT_LIT,
                    List.of());
        }

        /**
         * One item a floor promises on its door and pays into the copper chest
         * when it is cleared (2026-10-05 rework). {@code item} is a namespaced
         * item id, {@code count} 1 or more.
         */
        record Reward(String item, int count) {
            Reward {
                if (item == null || item.isBlank()) {
                    throw new IllegalArgumentException("reward item must not be blank");
                }
                if (count < 1) {
                    throw new IllegalArgumentException("reward count must be >= 1: " + item);
                }
                item = item.trim();
            }

            /** {@code "echo shard"} style words for the board, from the item id's path. */
            String displayName() {
                String path = item.substring(item.indexOf(':') + 1);
                return (count > 1 ? count + " " : "") + path.replace('_', ' ');
            }
        }

        Node {
            light = light == null || light.isEmpty() ? LIGHT_LIT : light;
            if (!LIGHT_LIT.equals(light) && !LIGHT_DIM.equals(light) && !LIGHT_DARK.equals(light)) {
                throw new IllegalArgumentException("node light must be lit, dim or dark: " + id + " has " + light);
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("node id must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("node name must not be blank: " + id);
            }
            if (layer < 1) {
                throw new IllegalArgumentException("node layer must be >= 1: " + id);
            }
            theme = theme == null ? "" : theme;
            signatureAffix = signatureAffix == null ? "" : signatureAffix;
            roomBias = List.copyOf(roomBias);
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
            if (roomCount < 0) {
                throw new IllegalArgumentException("node roomCount must be >= 1 when present: " + id);
            }
        }

        boolean overridesTheme() {
            return !theme.isEmpty();
        }
    }

    /** A directed edge between two nodes; {@code cost} is echo shards (0 is a main path edge). */
    record Edge(String from, String to, int cost) {
        Edge {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                throw new IllegalArgumentException("edge endpoints must not be blank");
            }
            if (cost < 0) {
                throw new IllegalArgumentException("edge cost must be >= 0: " + from + " to " + to);
            }
        }

        boolean free() {
            return cost == 0;
        }
    }

    DungeonDef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("dungeon id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dungeon name must not be blank: " + id);
        }
        if (act < MIN_ACT || act > MAX_ACT) {
            throw new IllegalArgumentException("act must be " + MIN_ACT + " to " + MAX_ACT + ": " + act);
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required: " + id);
        }
        if (mainTheme == null || mainTheme.isBlank()) {
            throw new IllegalArgumentException("mainTheme must not be blank: " + id);
        }
        if (lootBand == null) {
            throw new IllegalArgumentException("lootBand is required: " + id);
        }
        nodePalette = List.copyOf(nodePalette);
        merchant = merchant == null ? "" : merchant;
        diary = diary == null ? "" : diary;
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a dungeon needs at least one node: " + id);
        }
        Set<String> ids = new HashSet<>();
        for (Node node : nodes) {
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("duplicate node id '" + node.id() + "' in " + id);
            }
        }
        for (Edge edge : edges) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                throw new IllegalArgumentException("edge references an unknown node: " + edge.from()
                        + " to " + edge.to() + " in " + id);
            }
        }
    }

    Node node(String nodeId) {
        for (Node node : nodes) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    /** The number of layers: the highest node layer. */
    int layers() {
        int max = 0;
        for (Node node : nodes) {
            max = Math.max(max, node.layer());
        }
        return max;
    }

    /** The one node on layer 1, or null if there is not exactly one. */
    Node entry() {
        Node found = null;
        for (Node node : nodes) {
            if (node.layer() == 1) {
                if (found != null) {
                    return null;
                }
                found = node;
            }
        }
        return found;
    }

    List<Node> finals() {
        List<Node> out = new ArrayList<>();
        for (Node node : nodes) {
            if (node.isFinal()) {
                out.add(node);
            }
        }
        return out;
    }

    List<Edge> edgesFrom(String nodeId) {
        List<Edge> out = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.from().equals(nodeId)) {
                out.add(edge);
            }
        }
        return out;
    }

    // ---- validation ------------------------------------------------------------

    /**
     * Every structural rule violation of this dungeon, as readable reasons;
     * empty when the dungeon is sound. {@code actOfTheme} maps a qualified theme
     * id to the act of the dungeon whose main theme it is, or a value below 1
     * when no dungeon has that main theme (D6a: a borrowed theme must resolve
     * and be of the same or an earlier act).
     */
    List<String> problems(ToIntFunction<String> actOfTheme) {
        List<String> out = new ArrayList<>();
        boolean endless = kind == Kind.ENDLESS;
        int layerCount = layers();

        // D12: a resource dungeon is 1 to 3 floors, so it may be shorter than the story minimum.
        int minLayers = endless || kind == Kind.RESOURCE ? 1 : MIN_LAYERS;
        if (layerCount < minLayers || layerCount > MAX_LAYERS) {
            out.add("layers must be " + minLayers + " to " + MAX_LAYERS
                    + " but the highest node layer is " + layerCount);
        }
        for (int layer = 1; layer <= layerCount; layer++) {
            boolean any = false;
            for (Node node : nodes) {
                any |= node.layer() == layer;
            }
            if (!any) {
                out.add("layer " + layer + " has no node (layers must be contiguous from 1)");
            }
        }

        int entries = 0;
        for (Node node : nodes) {
            if (node.layer() == 1) {
                entries++;
            }
        }
        if (entries != 1) {
            out.add("exactly one entry node is required on layer 1 but found " + entries);
        }

        Map<String, Node> byId = new HashMap<>();
        for (Node node : nodes) {
            byId.put(node.id(), node);
        }
        Set<String> seenEdges = new HashSet<>();
        Map<String, Integer> outCount = new LinkedHashMap<>();
        for (Edge edge : edges) {
            if (!seenEdges.add(edge.from() + ">" + edge.to())) {
                out.add("duplicate edge " + edge.from() + " to " + edge.to());
            }
            outCount.merge(edge.from(), 1, Integer::sum);
            Node from = byId.get(edge.from());
            Node to = byId.get(edge.to());
            if (to.layer() != from.layer() + 1) {
                out.add("edge " + edge.from() + " (layer " + from.layer() + ") to " + edge.to() + " (layer "
                        + to.layer() + ") must go from layer n to layer n+1");
            }
        }
        for (Map.Entry<String, Integer> count : outCount.entrySet()) {
            if (count.getValue() > MAX_EDGES_OUT) {
                out.add("node " + count.getKey() + " has " + count.getValue() + " edges out; the most allowed is "
                        + MAX_EDGES_OUT);
            }
        }
        if (hasCycle()) {
            out.add("the graph has a cycle");
        }

        List<Node> finals = finals();
        if (!endless && finals.isEmpty()) {
            out.add("a " + kind.name().toLowerCase() + " dungeon needs a final node");
        }
        if (kind == Kind.CAPSTONE && finals.size() != 1) {
            out.add("a capstone dungeon needs exactly one final node but has " + finals.size());
        }
        for (Node node : nodes) {
            List<Edge> outgoing = edgesFrom(node.id());
            if (node.isFinal()) {
                if (!outgoing.isEmpty()) {
                    out.add("final node " + node.id() + " must not have edges out");
                }
                continue;
            }
            boolean freeExit = false;
            for (Edge edge : outgoing) {
                freeExit |= edge.free();
            }
            boolean exempt = endless && node.layer() == layerCount && outgoing.isEmpty();
            if (!freeExit && !exempt) {
                out.add("non-final node " + node.id() + " needs at least one cost 0 edge out");
            }
        }

        for (Node node : nodes) {
            if ((node.layer() == 1 || node.isFinal()) && node.overridesTheme()) {
                out.add((node.layer() == 1 ? "entry" : "final") + " node " + node.id()
                        + " must use the main theme (no theme override)");
            }
            if (node.overridesTheme()) {
                int themeAct = actOfTheme.applyAsInt(node.theme());
                if (themeAct < 1) {
                    out.add("node " + node.id() + " borrows theme " + node.theme()
                            + " which no dungeon has as its main theme");
                } else if (themeAct > act) {
                    out.add("node " + node.id() + " borrows theme " + node.theme() + " from act " + themeAct
                            + ", later than this dungeon's act " + act);
                }
            }
        }
        if (deviation != null) {
            for (String theme : deviation.themes()) {
                int themeAct = actOfTheme.applyAsInt(theme);
                if (themeAct < 1) {
                    out.add("deviation theme " + theme + " is not the main theme of any dungeon");
                } else if (themeAct > act) {
                    out.add("deviation theme " + theme + " is from act " + themeAct
                            + ", later than this dungeon's act " + act);
                }
            }
        }

        Node entry = entry();
        if (entry != null) {
            Set<String> fromEntry = reach(entry.id(), false);
            for (Node node : nodes) {
                if (!fromEntry.contains(node.id())) {
                    out.add("node " + node.id() + " is not reachable from the entry node");
                }
            }
            if (!endless) {
                Set<String> toFinal = new HashSet<>();
                for (Node node : finals) {
                    toFinal.addAll(reach(node.id(), true));
                }
                for (Node node : nodes) {
                    if (fromEntry.contains(node.id()) && !node.isFinal() && !toFinal.contains(node.id())) {
                        out.add("node " + node.id() + " is reachable but cannot reach a final node");
                    }
                }
            }
        }
        return out;
    }

    private Set<String> reach(String start, boolean backwards) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            for (Edge edge : edges) {
                String from = backwards ? edge.to() : edge.from();
                String to = backwards ? edge.from() : edge.to();
                if (from.equals(at) && seen.add(to)) {
                    queue.add(to);
                }
            }
        }
        return seen;
    }

    private boolean hasCycle() {
        Map<String, Integer> indegree = new HashMap<>();
        for (Node node : nodes) {
            indegree.put(node.id(), 0);
        }
        for (Edge edge : edges) {
            indegree.merge(edge.to(), 1, Integer::sum);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) {
                queue.add(node);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String at = queue.poll();
            visited++;
            for (Edge edge : edgesFrom(at)) {
                if (indegree.merge(edge.to(), -1, Integer::sum) == 0) {
                    queue.add(edge.to());
                }
            }
        }
        return visited != nodes.size();
    }

    // ---- JSON ------------------------------------------------------------------

    /**
     * Parses one dungeon file. {@code id} is the namespaced resource id; theme
     * and affix references are qualified to the pocketdungeons namespace when
     * bare. Throws {@link IllegalArgumentException} on a missing or malformed
     * field; structural rules are {@link #problems}.
     */
    static DungeonDef fromJson(String id, JsonObject obj) {
        int version = obj.has("version") && !obj.get("version").isJsonNull() ? obj.get("version").getAsInt() : 1;
        if (version != 1) {
            throw new IllegalArgumentException(id + ": unsupported content version " + version
                    + " (this build supports version 1 only)");
        }
        String name = requiredString(obj, "name");
        int act = requiredInt(obj, "act");
        Kind kind = Kind.parse(requiredString(obj, "kind"));
        String mainTheme = qualify(requiredString(obj, "mainTheme"));

        JsonObject band = obj.has("lootBand") && obj.get("lootBand").isJsonObject()
                ? obj.getAsJsonObject("lootBand") : null;
        if (band == null) {
            throw new IllegalArgumentException("lootBand object with min and max is required");
        }
        LootBand lootBand = new LootBand(requiredInt(band, "min"), requiredInt(band, "max"));

        List<String> palette = new ArrayList<>();
        for (JsonElement element : optionalArray(obj, "nodePalette")) {
            palette.add(blockId(element.getAsString()));
        }
        String merchant = optionalString(obj, "merchant");
        String diary = optionalString(obj, "diary");

        Deviation deviation = null;
        if (obj.has("deviation") && obj.get("deviation").isJsonObject()) {
            JsonObject dev = obj.getAsJsonObject("deviation");
            List<String> themes = new ArrayList<>();
            for (JsonElement element : optionalArray(dev, "themes")) {
                themes.add(qualify(element.getAsString().trim()));
            }
            double chance = dev.has("chance") ? dev.get("chance").getAsDouble() : 0.0;
            deviation = new Deviation(chance, themes);
        }

        List<Node> nodes = new ArrayList<>();
        for (JsonElement element : optionalArray(obj, "nodes")) {
            JsonObject node = element.getAsJsonObject();
            List<String> bias = new ArrayList<>();
            for (JsonElement room : optionalArray(node, "roomBias")) {
                bias.add(room.getAsString().trim());
            }
            String theme = optionalString(node, "theme");
            String affix = optionalString(node, "signatureAffix");
            List<Node.Reward> rewards = new ArrayList<>();
            for (JsonElement rewardElement : optionalArray(node, "rewards")) {
                JsonObject reward = rewardElement.getAsJsonObject();
                String item = requiredString(reward, "item").trim();
                rewards.add(new Node.Reward(
                        item.indexOf(':') >= 0 ? item : "minecraft:" + item,
                        reward.has("count") ? requiredInt(reward, "count") : 1));
            }
            nodes.add(new Node(requiredString(node, "id"), requiredString(node, "name"),
                    requiredInt(node, "layer"),
                    theme.isEmpty() ? "" : qualify(theme),
                    affix.isEmpty() ? "" : qualify(affix),
                    bias,
                    node.has("roomCount") ? requiredInt(node, "roomCount") : 0,
                    node.has("final") && node.get("final").getAsBoolean(),
                    node.has("light") && !node.get("light").isJsonNull()
                            ? node.get("light").getAsString().trim() : Node.LIGHT_LIT,
                    rewards));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonElement element : optionalArray(obj, "edges")) {
            JsonObject edge = element.getAsJsonObject();
            edges.add(new Edge(requiredString(edge, "from"), requiredString(edge, "to"),
                    edge.has("cost") ? requiredInt(edge, "cost") : 0));
        }
        return new DungeonDef(id, name, act, kind, mainTheme, lootBand, palette, merchant, diary,
                deviation, nodes, edges);
    }

    /** Bare ids belong to the pocketdungeons namespace, same rule as {@code JsonPackSupport.qualify}. */
    static String qualify(String reference) {
        if (reference == null || reference.isBlank() || reference.indexOf(':') >= 0) {
            return reference;
        }
        return DEFAULT_NAMESPACE + ":" + reference;
    }

    /** Block ids default to the minecraft namespace. */
    private static String blockId(String raw) {
        String id = raw.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("nodePalette entries must not be blank");
        }
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static String requiredString(JsonObject obj, String field) {
        JsonElement element = obj.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank: " + field);
        }
        return value;
    }

    private static String optionalString(JsonObject obj, String field) {
        JsonElement element = obj.get(field);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank when present: " + field);
        }
        return value;
    }

    private static int requiredInt(JsonObject obj, String field) {
        JsonElement element = obj.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return element.getAsInt();
    }

    private static JsonArray optionalArray(JsonObject obj, String field) {
        JsonElement element = obj.get(field);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("field must be an array: " + field);
        }
        return element.getAsJsonArray();
    }
}
