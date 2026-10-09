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
 * @param hiddenOre   ore pockets buried in the solid rock of every room the dungeon stamps
 *                    (design 2026-10-06-1 item 7), or null for none
 * @param minNodeRooms how many node-bearing rooms every floor must place (the PD-149
 *                     guarantee as data); a node may override it
 * @param baseLevel    the level of the entry floor (design D26); a floor at layer L
 *                     defaults to {@code baseLevel + L - 1}, a node may override with
 *                     {@code level}
 */
record DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
                  List<String> nodePalette, String merchant, String diary, Deviation deviation,
                  List<Node> nodes, List<Edge> edges, HiddenOre hiddenOre, int minNodeRooms,
                  int baseLevel, String notes, String hall, String tokenMat, Finale finale,
                  MobUniform mobUniform) {

    /**
     * The last stand a dungeon's final floor ends in (design pass 2026-10-09, Q4): a wave of {@code mobs}
     * that grows {@code perMemberPercent} for each member past the first, and from Act 2 a named
     * {@code elite} with a boss bar. Never on a capstone, which has its own fight.
     */
    record Finale(List<FinaleMob> mobs, int perMemberPercent, Elite elite) {

        Finale {
            mobs = List.copyOf(mobs);
        }

        int total() {
            int total = 0;
            for (FinaleMob mob : mobs) {
                total += mob.count();
            }
            return total;
        }
    }

    /** One kind of mob in a finale wave, by entity type id. */
    record FinaleMob(String type, int count) {}

    /** The named leader of a finale: a mob type, its name, its health at a party of one, and what it holds. */
    record Elite(String type, String name, int health, String mainhand) {}

    /**
     * What the mobs of a dungeon wear (design pass 2026-10-09, Q5): {@code armourPieces} random pieces of
     * {@code armour} on melee mobs, {@code rangedArmourPieces} on ranged ones, and {@code weapon} in the
     * hand of a melee mob, each mob wearing it with probability {@code chance}. Nothing it wears drops.
     */
    record MobUniform(List<String> armour, int armourPieces, int rangedArmourPieces, String weapon,
                      double chance) {

        MobUniform {
            armour = List.copyOf(armour);
            weapon = weapon == null ? "" : weapon;
        }
    }

    /** A dungeon with no finale and no mob uniform: the shape this record had before wave D. */
    DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
               List<String> nodePalette, String merchant, String diary, Deviation deviation,
               List<Node> nodes, List<Edge> edges, HiddenOre hiddenOre, int minNodeRooms,
               int baseLevel, String notes, String hall, String tokenMat) {
        this(id, name, act, kind, mainTheme, lootBand, nodePalette, merchant, diary, deviation, nodes, edges,
                hiddenOre, minNodeRooms, baseLevel, notes, hall, tokenMat, null, null);
    }

    /**
     * A dungeon that stands in its act's row (hall {@code act}) with no doormat token: the shape this record
     * had before the Astrolabe Room.
     */
    DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
               List<String> nodePalette, String merchant, String diary, Deviation deviation,
               List<Node> nodes, List<Edge> edges, HiddenOre hiddenOre, int minNodeRooms,
               int baseLevel, String notes) {
        this(id, name, act, kind, mainTheme, lootBand, nodePalette, merchant, diary, deviation, nodes, edges,
                hiddenOre, minNodeRooms, baseLevel, notes, HallLayout.HALL_ACT, "");
    }

    /** A dungeon with no note: the shape this record had before dungeon notes. */
    DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
               List<String> nodePalette, String merchant, String diary, Deviation deviation,
               List<Node> nodes, List<Edge> edges, HiddenOre hiddenOre, int minNodeRooms,
               int baseLevel) {
        this(id, name, act, kind, mainTheme, lootBand, nodePalette, merchant, diary, deviation, nodes, edges,
                hiddenOre, minNodeRooms, baseLevel, "");
    }

    /**
     * The checked form of a note: trimmed, at most {@link Node#MAX_NOTES} characters, and free of the
     * dash punctuation the house style forbids. {@code null} reads as no note.
     */
    static String checkedNotes(String notes, String owner) {
        String text = notes == null ? "" : notes.trim();
        if (text.length() > Node.MAX_NOTES) {
            throw new IllegalArgumentException("notes of " + owner + " run " + text.length() + " characters; at most "
                    + Node.MAX_NOTES + ": " + text);
        }
        if (text.indexOf('\u2014') >= 0 || text.contains(" -- ")) {
            throw new IllegalArgumentException("notes of " + owner + " use dash punctuation: " + text);
        }
        return text;
    }

    /** A dungeon with no hidden ore and no node room guarantee. */
    DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
               List<String> nodePalette, String merchant, String diary, Deviation deviation,
               List<Node> nodes, List<Edge> edges) {
        this(id, name, act, kind, mainTheme, lootBand, nodePalette, merchant, diary, deviation, nodes, edges,
                null, 0, 1, "");
    }

    /** A dungeon with hidden ore and no node room guarantee. */
    DungeonDef(String id, String name, int act, Kind kind, String mainTheme, LootBand lootBand,
               List<String> nodePalette, String merchant, String diary, Deviation deviation,
               List<Node> nodes, List<Edge> edges, HiddenOre hiddenOre) {
        this(id, name, act, kind, mainTheme, lootBand, nodePalette, merchant, diary, deviation, nodes, edges,
                hiddenOre, 0, 1, "");
    }

    /** What a dungeon is for (design D12, revised 2026-10-06). */
    enum Kind {
        DUNGEON, CAPSTONE, ENDLESS;

        static Kind parse(String word) {
            return switch (word) {
                case "dungeon" -> DUNGEON;
                case "capstone" -> CAPSTONE;
                case "endless" -> ENDLESS;
                default -> throw new IllegalArgumentException(
                        "kind must be dungeon, capstone or endless: " + word);
            };
        }
    }

    static final int MIN_LAYERS = 2;
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

    /**
     * Whether a block id is a copper ore (K2.8): copper left the game as a
     * whole tier, so a node or palette must not hand it back.
     */
    static boolean isCopperOre(String blockId) {
        if (blockId == null) {
            return false;
        }
        String id = blockId.trim();
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String name = colon < 0 ? id : id.substring(colon + 1);
        return "minecraft".equals(ns) && (name.equals("copper_ore") || name.equals("deepslate_copper_ore"));
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
     * Ore buried in a room's solid rock, found by digging (design 2026-10-06-1 item 7). Each
     * room gets {@code pocketsMin} to {@code pocketsMax} pockets, each {@code sizeMin} to
     * {@code sizeMax} blocks of one of {@code blocks} (the dungeon's non-structural
     * {@code nodePalette} when none are declared); a block listed twice is twice as likely.
     * {@link HiddenOrePlanner} picks where.
     */
    record HiddenOre(int pocketsMin, int pocketsMax, int sizeMin, int sizeMax, List<String> blocks) {

        static final int MAX_POCKETS = 6;
        static final int MAX_SIZE = 6;

        HiddenOre {
            blocks = List.copyOf(blocks);
        }

        /** The blocks a pocket may be made of: the declared ones, else the non-structural palette. */
        List<String> blocksOr(List<String> palette) {
            if (!blocks.isEmpty()) {
                return blocks;
            }
            List<String> out = new ArrayList<>();
            for (String block : palette) {
                if (!isStructuralBlock(block)) {
                    out.add(block);
                }
            }
            return out;
        }

        /** What is wrong with this declaration, as readable reasons. */
        List<String> problems(List<String> palette) {
            List<String> out = new ArrayList<>();
            if (pocketsMin < 0 || pocketsMax < pocketsMin || pocketsMax > MAX_POCKETS) {
                out.add("hiddenOre pockets must satisfy 0 <= pocketsMin <= pocketsMax <= " + MAX_POCKETS + ": "
                        + pocketsMin + " to " + pocketsMax);
            }
            if (sizeMin < 1 || sizeMax < sizeMin || sizeMax > MAX_SIZE) {
                out.add("hiddenOre size must satisfy 1 <= sizeMin <= sizeMax <= " + MAX_SIZE + ": "
                        + sizeMin + " to " + sizeMax);
            }
            List<String> usable = blocksOr(palette);
            if (usable.isEmpty()) {
                out.add("hiddenOre has no blocks (declare blocks, or give the dungeon a nodePalette)");
            }
            for (String block : usable) {
                if (isStructuralBlock(block)) {
                    out.add("hiddenOre block " + block + " is a structural block, which would make walls mineable");
                }
                if (isCopperOre(block)) {
                    out.add("hiddenOre block " + block + " is copper ore, which left the game (K2.8)");
                }
            }
            return out;
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
     * Feral affix is not dealt on it. {@code minNodeRooms} overrides the dungeon's own
     * node room guarantee for this floor; {@link #INHERIT_NODE_ROOMS} means inherit.
     * {@code level} (D26) overrides the floor's level; {@link #UNSET_LEVEL} means
     * {@code baseLevel + layer - 1} ({@link FloorLevels}).
     */
    record Node(String id, String name, int layer, String theme, String signatureAffix,
                List<String> roomBias, int roomCount, boolean isFinal, String light,
                List<Reward> rewards, int minNodeRooms, int level, String notes) {

        /**
         * The longest floor or dungeon note, in characters. The note is the one line of the door board that
         * says why a player would choose this floor over another; it wraps, but it must stay one sentence.
         */
        static final int MAX_NOTES = 90;

        static final String LIGHT_LIT = "lit";
        static final String LIGHT_DIM = "dim";
        static final String LIGHT_DARK = "dark";

        /** {@link #minNodeRooms} value meaning "use the dungeon's own {@code minNodeRooms}". */
        static final int INHERIT_NODE_ROOMS = -1;

        /** {@link #level} value meaning "derive the floor level from the layer". */
        static final int UNSET_LEVEL = -1;

        /** A node with the default light ({@code lit}) and no promised rewards. */
        Node(String id, String name, int layer, String theme, String signatureAffix,
             List<String> roomBias, int roomCount, boolean isFinal) {
            this(id, name, layer, theme, signatureAffix, roomBias, roomCount, isFinal, LIGHT_LIT,
                    List.of(), INHERIT_NODE_ROOMS, UNSET_LEVEL, "");
        }

        /** A node with light and rewards, inheriting the dungeon's node room guarantee. */
        Node(String id, String name, int layer, String theme, String signatureAffix,
             List<String> roomBias, int roomCount, boolean isFinal, String light,
             List<Reward> rewards) {
            this(id, name, layer, theme, signatureAffix, roomBias, roomCount, isFinal, light, rewards,
                    INHERIT_NODE_ROOMS, UNSET_LEVEL, "");
        }

        /** A node with no note: the shape this record had before floor notes. */
        Node(String id, String name, int layer, String theme, String signatureAffix,
             List<String> roomBias, int roomCount, boolean isFinal, String light,
             List<Reward> rewards, int minNodeRooms, int level) {
            this(id, name, layer, theme, signatureAffix, roomBias, roomCount, isFinal, light, rewards,
                    minNodeRooms, level, "");
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
            notes = checkedNotes(notes, "node " + id);
            roomBias = List.copyOf(roomBias);
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
            if (roomCount < 0) {
                throw new IllegalArgumentException("node roomCount must be >= 1 when present: " + id);
            }
            if (minNodeRooms < INHERIT_NODE_ROOMS) {
                throw new IllegalArgumentException("node minNodeRooms must be >= 0 when present: " + id);
            }
            if (level < UNSET_LEVEL || level == 0) {
                throw new IllegalArgumentException("node level must be >= 1 when present: " + id);
            }
        }

        boolean overridesTheme() {
            return !theme.isEmpty();
        }
    }

    /**
     * A directed edge between two nodes; {@code cost} is the lives a side door takes from the party
     * (0 is a main path edge, 1 or 2 a side branch). The file key is {@code lives}.
     */
    record Edge(String from, String to, int cost) {
        Edge {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                throw new IllegalArgumentException("edge endpoints must not be blank");
            }
            if (cost < 0 || cost > DoorLives.MAX_LIVES) {
                throw new IllegalArgumentException("edge lives must be 0 to " + DoorLives.MAX_LIVES + ": "
                        + from + " to " + to);
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
        if (minNodeRooms < 0) {
            throw new IllegalArgumentException("minNodeRooms must be >= 0: " + id);
        }
        if (baseLevel < 1) {
            throw new IllegalArgumentException("baseLevel must be >= 1: " + id);
        }
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

    /**
     * The compass level at which this dungeon unlocks (D23): a dungeon whose
     * unlock level is above the leader's compass is not offered at the first
     * door and is never the guaranteed capstone. Settled equal to
     * {@link #baseLevel} (plan 2026-10-06-2 section B).
     */
    int unlockLevel() {
        return baseLevel;
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

    /**
     * How many node-bearing rooms a floor of {@code node} must place (the PD-149
     * guarantee, now data instead of a kind): the node's own
     * {@code minNodeRooms} when declared, else the dungeon's.
     */
    int minNodeRooms(Node node) {
        return node != null && node.minNodeRooms() >= 0 ? node.minNodeRooms() : minNodeRooms;
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

        // D12 (revised): every dungeon but the endless one is 2 to 6 floors; length is flavour.
        int minLayers = endless ? 1 : MIN_LAYERS;
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
        // D26: difficulty never eases as a path deepens; a node's level (declared or
        // derived from its layer) must be at least the level of every node feeding it.
        Map<String, Integer> effectiveLevels = new HashMap<>();
        for (Node node : nodes) {
            effectiveLevels.put(node.id(), FloorLevels.of(this, node));
        }
        for (Edge edge : edges) {
            int fromLevel = effectiveLevels.get(edge.from());
            int toLevel = effectiveLevels.get(edge.to());
            if (toLevel < fromLevel) {
                out.add("node " + edge.to() + " (level " + toLevel + ") drops below "
                        + edge.from() + " (level " + fromLevel + ") along its edge");
            }
        }
        if (hasCycle()) {
            out.add("the graph has a cycle");
        }

        List<Node> finals = finals();
        if (!endless && finals.isEmpty()) {
            out.add("dungeon " + id + " needs a final node");
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

        if (hiddenOre != null) {
            out.addAll(hiddenOre.problems(nodePalette));
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
        String hall = optionalString(obj, "hall");
        if (hall == null || hall.isBlank()) {
            hall = HallLayout.HALL_ACT;
        }
        if (!hall.equals(HallLayout.HALL_ACT) && !hall.equals(HallLayout.HALL_SPECIAL)) {
            throw new IllegalArgumentException(id + ": hall must be act or special: " + hall);
        }
        String tokenMat = "";
        if (obj.has("token") && obj.get("token").isJsonObject()) {
            tokenMat = blockId(requiredString(obj.getAsJsonObject("token"), "mat"));
        }
        Finale finale = parseFinale(obj, id, kind);
        MobUniform mobUniform = parseUniform(obj, id);
        int minNodeRooms = obj.has("minNodeRooms") ? requiredInt(obj, "minNodeRooms") : 0;
        int baseLevel = requiredInt(obj, "baseLevel");

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

        HiddenOre hiddenOre = null;
        if (obj.has("hiddenOre") && obj.get("hiddenOre").isJsonObject()) {
            JsonObject hidden = obj.getAsJsonObject("hiddenOre");
            List<String> blocks = new ArrayList<>();
            for (JsonElement element : optionalArray(hidden, "blocks")) {
                blocks.add(blockId(element.getAsString()));
            }
            hiddenOre = new HiddenOre(
                    hidden.has("pocketsMin") ? requiredInt(hidden, "pocketsMin") : 0,
                    hidden.has("pocketsMax") ? requiredInt(hidden, "pocketsMax") : 2,
                    hidden.has("sizeMin") ? requiredInt(hidden, "sizeMin") : 1,
                    hidden.has("sizeMax") ? requiredInt(hidden, "sizeMax") : 3,
                    blocks);
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
                    rewards,
                    node.has("minNodeRooms") ? requiredInt(node, "minNodeRooms") : Node.INHERIT_NODE_ROOMS,
                    node.has("level") ? requiredInt(node, "level") : Node.UNSET_LEVEL,
                    optionalString(node, "notes")));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonElement element : optionalArray(obj, "edges")) {
            JsonObject edge = element.getAsJsonObject();
            if (edge.has("cost")) {
                throw new IllegalArgumentException("edge 'cost' was scrap and is gone; a side door costs 'lives' (1 or 2)");
            }
            edges.add(new Edge(requiredString(edge, "from"), requiredString(edge, "to"),
                    edge.has("lives") ? requiredInt(edge, "lives") : 0));
        }
        return new DungeonDef(id, name, act, kind, mainTheme, lootBand, palette, merchant, diary,
                deviation, nodes, edges, hiddenOre, minNodeRooms, baseLevel,
                checkedNotes(optionalString(obj, "notes"), "dungeon " + id), hall, tokenMat, finale, mobUniform);
    }

    /** Entity and item ids default to the minecraft namespace; this is the same rule as {@link #blockId}. */
    private static String gameId(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("an id must not be empty");
        }
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static Finale parseFinale(JsonObject obj, String id, Kind kind) {
        if (!obj.has("finale") || !obj.get("finale").isJsonObject()) {
            return null;
        }
        if (kind == Kind.CAPSTONE) {
            throw new IllegalArgumentException(id + ": a capstone has its own fight and takes no finale");
        }
        JsonObject f = obj.getAsJsonObject("finale");
        List<FinaleMob> mobs = new ArrayList<>();
        for (JsonElement element : optionalArray(f, "mobs")) {
            JsonObject mob = element.getAsJsonObject();
            int count = requiredInt(mob, "count");
            if (count < 1 || count > 20) {
                throw new IllegalArgumentException(id + ": a finale mob count must be 1 to 20: " + count);
            }
            mobs.add(new FinaleMob(gameId(requiredString(mob, "type")), count));
        }
        if (mobs.isEmpty()) {
            throw new IllegalArgumentException(id + ": a finale needs at least one mob");
        }
        int perMember = f.has("perMemberPercent") ? requiredInt(f, "perMemberPercent") : 50;
        if (perMember < 0 || perMember > 100) {
            throw new IllegalArgumentException(id + ": perMemberPercent must be 0 to 100: " + perMember);
        }
        Elite elite = null;
        if (f.has("elite") && f.get("elite").isJsonObject()) {
            JsonObject e = f.getAsJsonObject("elite");
            int health = requiredInt(e, "health");
            if (health < 20 || health > 200) {
                throw new IllegalArgumentException(id + ": elite health must be 20 to 200: " + health);
            }
            String name = checkedNotes(requiredString(e, "name"), "elite of " + id);
            elite = new Elite(gameId(requiredString(e, "type")), name, health,
                    e.has("mainhand") ? gameId(requiredString(e, "mainhand")) : "");
        }
        Finale finale = new Finale(mobs, perMember, elite);
        if (finale.total() > 30) {
            throw new IllegalArgumentException(id + ": a finale for one holds at most 30 mobs: " + finale.total());
        }
        return finale;
    }

    private static MobUniform parseUniform(JsonObject obj, String id) {
        if (!obj.has("mobUniform") || !obj.get("mobUniform").isJsonObject()) {
            return null;
        }
        JsonObject u = obj.getAsJsonObject("mobUniform");
        List<String> armour = new ArrayList<>();
        for (JsonElement element : optionalArray(u, "armour")) {
            armour.add(gameId(element.getAsString()));
        }
        int pieces = u.has("armourPieces") ? requiredInt(u, "armourPieces") : 1;
        int ranged = u.has("rangedArmourPieces") ? requiredInt(u, "rangedArmourPieces") : 2;
        if (pieces < 0 || pieces > 4 || ranged < 0 || ranged > 4) {
            throw new IllegalArgumentException(id + ": armour piece counts must be 0 to 4");
        }
        double chance = u.has("chance") ? u.get("chance").getAsDouble() : 1.0;
        if (chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException(id + ": uniform chance must be 0 to 1: " + chance);
        }
        String weapon = u.has("weapon") ? gameId(requiredString(u, "weapon")) : "";
        return new MobUniform(armour, pieces, ranged, weapon, chance);
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
