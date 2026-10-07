package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Room metadata as declared in {@code data/pocketdungeons/dungeon_room/*.json}.
 * Plain data holder with a Gson parser -- no Minecraft imports.
 */
final class DungeonRoomMeta {

    final String template;
    final int footprintX;
    final int footprintZ;
    final List<String> roles;
    final int weight;
    final int minDepth;
    final int maxPerDungeon;
    final String processors;
    final List<String> theme;
    final String content;

    /** M45 (spec 6.1): minimum loot tier at which this situation may appear. */
    final int tier;

    /** M45 (spec 6.1): tool tags this room guarantees to make available. */
    final List<String> provides;

    /** M45 (spec 6.1): tool tags that must already be available before this cell. */
    final List<String> requires;

    /** M45 (spec 6.1): {@code local}, {@code omen} or null. Informational for the selector. */
    final String pressure;

    /** M45 (spec 6.1): {@code open} or {@code gated}. Never null; defaults to {@code open}. */
    final String access;

    /**
     * M61 (spec 13.2): the number of cell-sized volumes this room stacks
     * vertically, the topmost being its own cell and each one below owned
     * privately by the same room. Defaults to {@code 1} (no lower story).
     * {@code 2} means the room owns its cell plus the 16 x 16 x 9 volume
     * directly beneath it. The lower story has no doorways and is not a
     * {@link PlanCell}; see spec 13.3 and the {@code spanY} invariant in 13.4.
     */
    final int spanY;

    /**
     * Dungeon structure W4 (D19, D20): the resource nodes this room holds, placed
     * and registered at stamp time. Empty for every room that declares none.
     */
    final List<NodeSpec> nodes;

    /** Dungeon structure W4 (D17): {@link #LIGHT_LIT}, {@link #LIGHT_DIM} or {@link #LIGHT_DARK}. Never null. */
    final String light;

    /** Dungeon structure W4 (D17): {@link #CORRIDOR_NARROW} or {@link #CORRIDOR_WIDE}. Never null. */
    final String corridor;

    /** Dungeon structure W4 (D18): a biome tag such as {@code lush_cave}, or null. */
    final String biome;

    /**
     * Dungeon structure W4: a mechanic in this room needs a light level (wolves,
     * a kennel, sculk readability), so the room is never stamped dark or dim.
     */
    final boolean requiresLight;

    /**
     * Dungeon structure W4: dungeon ids that may use this room as a main theme
     * room. Empty when the file declares none; {@link #theme} then stands in
     * (see {@link #usableByMainTheme}).
     */
    final List<String> dungeons;

    /** Dungeon structure W4: acts (1 to 5) the room belongs to; empty means any act. */
    final List<Integer> acts;

    /** Dungeon structure W4: graph roles as written; {@link #GRAPH_ROLES} lists the valid ones. */
    final List<String> graphRole;

    /** Dungeon structure W4 (D6a): dungeon ids that may borrow this room as a deviation. */
    final List<String> borrowableBy;

    static final String LIGHT_LIT = "lit";
    static final String LIGHT_DIM = "dim";
    static final String LIGHT_DARK = "dark";
    static final String CORRIDOR_NARROW = "narrow";
    static final String CORRIDOR_WIDE = "wide";

    /** The values {@link #graphRole} may hold. The parser keeps what is written; PackValidator reports a stranger. */
    static final Set<String> GRAPH_ROLES = Set.of("entry", "any", "side_reward", "final", "capstone");

    /** The only value {@link #access} may take besides {@link #ACCESS_GATED}. */
    static final String ACCESS_OPEN = "open";

    /** A cell the player must solve to pass through. */
    static final String ACCESS_GATED = "gated";

    /**
     * PD-133: the wall every gated room is authored to be entered through, at
     * rotation 0. Each template family (mechanism, pressure, situation,
     * knowledge) builds its gate on the east wall and leaves the west wall
     * open, so this is the side that must face the approach once the room is
     * rotated into a cell.
     */
    static final int GATED_ENTRY_AT_ROTATION_0 = DoorMask.WEST;

    /**
     * M45: what fills the window band on this room's connected walls, one of
     * {@link #WINDOW_BARS}, {@link #WINDOW_GLASS}, {@link #WINDOW_TINTED_GLASS}
     * or {@link #WINDOW_NONE}. Never null; defaults to {@code bars}.
     *
     * <p>The band itself is a geometry contract both neighbouring cells honour
     * ({@link RoomGeometry#WINDOW_MIN}), so a template cannot place it and this
     * field only names the material.
     */
    final String window;

    /** Iron bars: the default, containing what is on the other side. */
    static final String WINDOW_BARS = "bars";

    /** Clear glass, for a room whose point is being seen into. */
    static final String WINDOW_GLASS = "glass";

    /** Tinted glass: visible, and it does not carry the neighbour's light. */
    static final String WINDOW_TINTED_GLASS = "tinted_glass";

    /** No window at all: the wall stays solid either side of the doorway. */
    static final String WINDOW_NONE = "none";

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme, String content, int tier, List<String> provides,
                    List<String> requires, String pressure, String access, String window,
                    int spanY) {
        this(template, footprintX, footprintZ, roles, weight, minDepth, maxPerDungeon, processors,
                theme, content, tier, provides, requires, pressure, access, window, spanY,
                List.of(), LIGHT_LIT, CORRIDOR_WIDE, null, false, List.of(), List.of(), List.of(),
                List.of());
    }

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme, String content, int tier, List<String> provides,
                    List<String> requires, String pressure, String access, String window,
                    int spanY, List<NodeSpec> nodes, String light, String corridor, String biome,
                    boolean requiresLight, List<String> dungeons, List<Integer> acts,
                    List<String> graphRole, List<String> borrowableBy) {
        this.template = template;
        this.footprintX = footprintX;
        this.footprintZ = footprintZ;
        this.roles = roles;
        this.weight = weight;
        this.minDepth = minDepth;
        this.maxPerDungeon = maxPerDungeon;
        this.processors = processors;
        this.theme = theme;
        this.content = content;
        this.tier = tier;
        this.provides = provides;
        this.requires = requires;
        this.pressure = pressure;
        this.access = access;
        this.window = window;
        this.spanY = spanY;
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.light = light == null ? LIGHT_LIT : light;
        this.corridor = corridor == null ? CORRIDOR_WIDE : corridor;
        this.biome = biome;
        this.requiresLight = requiresLight;
        this.dungeons = dungeons == null ? List.of() : List.copyOf(dungeons);
        this.acts = acts == null ? List.of() : List.copyOf(acts);
        this.graphRole = graphRole == null ? List.of() : List.copyOf(graphRole);
        this.borrowableBy = borrowableBy == null ? List.of() : List.copyOf(borrowableBy);
    }

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme, String content) {
        this(template, footprintX, footprintZ, roles, weight, minDepth, maxPerDungeon,
                processors, theme, content, 1, List.of(), List.of(), null, ACCESS_OPEN,
                WINDOW_BARS, 1);
    }

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme) {
        this(template, footprintX, footprintZ, roles, weight, minDepth, maxPerDungeon,
                processors, theme, null);
    }

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors) {
        this(template, footprintX, footprintZ, roles, weight, minDepth, maxPerDungeon,
                processors, List.of(), null);
    }

    static DungeonRoomMeta fromJson(JsonObject obj) {
        return fromJson(obj, "<unknown room>");
    }

    /**
     * M68: parses a room file, validating its declared {@code version} first
     * (defaulting to 1 when absent, the pre M68 generation) so an incompatible
     * generation is rejected up front with the file named rather than parsed
     * into an in memory shape this build cannot honour.
     */
    static DungeonRoomMeta fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        String template = requiredString(obj, "template");
        int[] footprint = parseFootprint(obj.get("footprint"));
        List<String> roles = parseRoles(obj.get("roles"));
        int weight = intOr(obj.get("weight"), 1);
        int minDepth = intOr(obj.get("minDepth"), 0);
        int maxPerDungeon = intOr(obj.get("maxPerDungeon"), -1);
        String processors = stringOrNull(obj.get("processors"));
        List<String> theme = parseTheme(obj.get("theme"));
        String content = stringOrNull(obj.get("content"));
        int tier = intOr(obj.get("tier"), 1);
        List<String> provides = parseTags(obj.get("provides"));
        List<String> requires = parseTags(obj.get("requires"));
        // M45 step 2: a typo here would otherwise make the room quietly
        // unselectable forever, so it fails at manifest load with the room named.
        SituationTags.validate(template, provides);
        SituationTags.validate(template, requires);
        String pressure = stringOrNull(obj.get("pressure"));
        String access = parseAccess(obj.get("access"), template);
        String window = parseWindow(obj.get("window"), template);
        int spanY = parseSpanY(obj.get("spanY"), template);
        List<NodeSpec> nodes = parseNodes(obj.get("nodes"), template);
        String light = parseChoice(obj.get("light"), template, "light", LIGHT_LIT,
                LIGHT_LIT, LIGHT_DIM, LIGHT_DARK);
        String corridor = parseChoice(obj.get("corridor"), template, "corridor", CORRIDOR_WIDE,
                CORRIDOR_NARROW, CORRIDOR_WIDE);
        String biome = stringOrNull(obj.get("biome"));
        boolean requiresLight = boolOr(obj.get("requiresLight"), false);
        List<String> dungeons = parseTags(obj.get("dungeons"));
        List<Integer> acts = parseActs(obj.get("acts"), template);
        List<String> graphRole = parseTags(obj.get("graphRole"));
        List<String> borrowableBy = parseTags(obj.get("borrowableBy"));
        return new DungeonRoomMeta(template, footprint[0], footprint[1], roles,
                weight, minDepth, maxPerDungeon, processors, theme, content,
                tier, provides, requires, pressure, access, window, spanY,
                nodes, light, corridor, biome, requiresLight, dungeons, acts, graphRole,
                borrowableBy);
    }

    private static String requiredString(JsonObject obj, String key) {
        if (!obj.has(key)) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return obj.get(key).getAsString();
    }

    private static int[] parseFootprint(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return new int[]{1, 1};
        }
        JsonArray arr = el.getAsJsonArray();
        if (arr.size() != 2) {
            throw new IllegalArgumentException("footprint must be a 2-element array [x,z]");
        }
        return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt()};
    }

    private static List<String> parseRoles(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            throw new IllegalArgumentException("roles must be a JSON array of strings");
        }
        JsonArray arr = el.getAsJsonArray();
        List<String> roles = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            // M70: qualify bare role names to the pocketdungeons namespace so
            // a room file's "encounter" matches the namespaced
            // "pocketdungeons:encounter" the data-driven generator assigns.
            // A qualified id is returned as-is, so a third-party room can
            // declare "theirpack:their_role" and match a third-party role.
            String resolved = RoleIds.resolve(e.getAsString());
            if (resolved == null) {
                throw new IllegalArgumentException("unknown role in roles array: " + e.getAsString());
            }
            roles.add(resolved);
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles array must not be empty");
        }
        return Collections.unmodifiableList(roles);
    }

    private static int intOr(JsonElement el, int defaultValue) {
        if (el == null || !el.isJsonPrimitive()) {
            return defaultValue;
        }
        return el.getAsInt();
    }

    private static List<String> parseTheme(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        JsonArray arr = el.getAsJsonArray();
        List<String> theme = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            theme.add(e.getAsString());
        }
        return Collections.unmodifiableList(theme);
    }

    /**
     * M45 (spec 6.1): {@code provides} and {@code requires}, parsed exactly like
     * {@code theme}. Absent reads as empty, which is what every room shipped
     * today wants.
     */
    private static List<String> parseTags(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        JsonArray arr = el.getAsJsonArray();
        List<String> tags = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            tags.add(e.getAsString());
        }
        return Collections.unmodifiableList(tags);
    }

    /**
     * M45 (spec 6.1): {@code open} or {@code gated}, nothing else. A third value
     * is a datapack typo, and a typo that silently reads as {@code open} would
     * quietly un-gate a room the author meant to gate, so it throws with the
     * room named.
     */
    private static String parseAccess(JsonElement el, String roomName) {
        String value = stringOrNull(el);
        if (value == null) {
            return ACCESS_OPEN;
        }
        if (!ACCESS_OPEN.equals(value) && !ACCESS_GATED.equals(value)) {
            throw new IllegalArgumentException("room " + roomName + ": access must be \""
                    + ACCESS_OPEN + "\" or \"" + ACCESS_GATED + "\", not \"" + value + "\"");
        }
        return value;
    }

    /**
     * M45: the window band's material, rejected the same way {@code access} is
     * and for the same reason. A misspelt material that fell back to the
     * default would put bars in a room the author wanted to see through.
     */
    private static String parseWindow(JsonElement el, String roomName) {
        String value = stringOrNull(el);
        if (value == null) {
            return WINDOW_BARS;
        }
        if (!WINDOW_BARS.equals(value) && !WINDOW_GLASS.equals(value)
                && !WINDOW_TINTED_GLASS.equals(value) && !WINDOW_NONE.equals(value)) {
            throw new IllegalArgumentException("room " + roomName + ": window must be one of \""
                    + WINDOW_BARS + "\", \"" + WINDOW_GLASS + "\", \"" + WINDOW_TINTED_GLASS
                    + "\" or \"" + WINDOW_NONE + "\", not \"" + value + "\"");
        }
        return value;
    }

    private static String stringOrNull(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        String value = el.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * M61 (spec 13.2): {@code spanY} defaults to 1 and may only be 1 or 2 in
     * this round. A value outside that range is a datapack typo, and a typo
     * that silently read as 1 would hide a lower story the author meant to
     * declare (or promise one the generator cannot yet honour), so it throws
     * with the room named, the same way {@code access} and {@code window} do.
     */
    private static int parseSpanY(JsonElement el, String roomName) {
        int value = intOr(el, 1);
        if (value < 1 || value > RoomGeometry.MAX_SPAN_Y) {
            throw new IllegalArgumentException("room " + roomName + ": spanY must be between 1 and "
                    + RoomGeometry.MAX_SPAN_Y + ", not " + value);
        }
        return value;
    }

    // ---- dungeon structure W4 ---------------------------------------------------

    /**
     * Whether a dungeon whose main theme is {@code mainTheme} (and whose own id is
     * {@code dungeonId}) may use this room as a main theme room. {@link #dungeons}
     * wins when the file declares it; otherwise the older {@link #theme} list
     * stands in, matched against the main theme; an empty list means any.
     * Ids are compared qualified, so a bare name and its namespaced form agree.
     */
    boolean usableByMainTheme(String dungeonId, String mainTheme) {
        if (!dungeons.isEmpty()) {
            return containsQualified(dungeons, dungeonId);
        }
        return theme.isEmpty() || containsQualified(theme, mainTheme);
    }

    /** Whether this room's acts allow {@code act}; an empty list allows every act. */
    boolean allowsAct(int act) {
        return acts.isEmpty() || acts.contains(act);
    }

    private static boolean containsQualified(List<String> ids, String wanted) {
        if (wanted == null) {
            return false;
        }
        String target = DungeonDef.qualify(wanted);
        for (String id : ids) {
            if (DungeonDef.qualify(id).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One entry of the {@code nodes} field: {@code block} placed at one cell
     * ({@code at}) or across a box ({@code from} to {@code to}, inclusive), in
     * the room's unrotated local coordinates (x, y, z from the cell floor corner).
     * {@code count} is the number of positions of a box that become nodes
     * ({@link #ALL} for every one); it is ignored for a single position.
     * {@code chance} (PD-155, playtest 2026-10-05-1) is the seeded probability the
     * group spawns at all, 1 when absent: the same room can come up rich or bare,
     * which is what makes an ore room worth a second look.
     */
    record NodeSpec(String block, int[] from, int[] to, int count, double chance) {

        /** {@code count} when every position of the box is a node. */
        static final int ALL = -1;

        /** The pre-chance shape: every node appears. */
        NodeSpec(String block, int[] from, int[] to, int count) {
            this(block, from, to, count, 1.0);
        }

        NodeSpec {
            if (block == null || block.isBlank()) {
                throw new IllegalArgumentException("node block must not be blank");
            }
            block = block.indexOf(':') >= 0 ? block.trim() : "minecraft:" + block.trim();
            if (from == null || to == null || from.length != 3 || to.length != 3) {
                throw new IllegalArgumentException("node needs a position or a box");
            }
            for (int i = 0; i < 3; i++) {
                if (from[i] > to[i]) {
                    throw new IllegalArgumentException("node box 'from' must not exceed 'to'");
                }
            }
            if (count != ALL && count < 1) {
                throw new IllegalArgumentException("node count must be at least 1");
            }
            if (!(chance > 0 && chance <= 1)) {
                throw new IllegalArgumentException("node chance must be in (0, 1]");
            }
            from = from.clone();
            to = to.clone();
        }

        int volume() {
            return (to[0] - from[0] + 1) * (to[1] - from[1] + 1) * (to[2] - from[2] + 1);
        }

        /**
         * The local positions that become nodes: the whole box, or {@code count} of
         * them chosen deterministically from {@code seed} (a seeded shuffle, so the
         * same room and seed always give the same nodes).
         */
        List<int[]> positions(long seed) {
            List<int[]> all = new ArrayList<>();
            for (int x = from[0]; x <= to[0]; x++) {
                for (int y = from[1]; y <= to[1]; y++) {
                    for (int z = from[2]; z <= to[2]; z++) {
                        all.add(new int[]{x, y, z});
                    }
                }
            }
            if (count == ALL || count >= all.size()) {
                return all;
            }
            Collections.shuffle(all, new Random(seed));
            return new ArrayList<>(all.subList(0, count));
        }
    }

    /**
     * Local coordinates ({@code x}, {@code z}) turned {@code quarterTurns} clockwise
     * the way {@link TemplateStamper} places a template: {@code (x,z)} stays,
     * {@code (15-z, x)}, {@code (15-x, 15-z)} or {@code (z, 15-x)}. Pure, so a
     * test can pin it against the stamper's table.
     */
    static int[] rotateLocal(int x, int z, int quarterTurns) {
        int last = RoomGeometry.CELL - 1;
        return switch (((quarterTurns % 4) + 4) % 4) {
            case 1 -> new int[]{last - z, x};
            case 2 -> new int[]{last - x, last - z};
            case 3 -> new int[]{z, last - x};
            default -> new int[]{x, z};
        };
    }

    /** The lowest local y a node may sit at: inside the lower story of a two story room. */
    static final int NODE_MIN_Y = -RoomGeometry.STORY_HEIGHT * (RoomGeometry.MAX_SPAN_Y - 1) + 1;

    /** The highest local y a node may sit at: the top wall row, just under the ceiling. */
    static final int NODE_MAX_Y = RoomGeometry.WALL_HEIGHT;

    private static List<NodeSpec> parseNodes(JsonElement el, String roomName) {
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException("room " + roomName + ": nodes must be an array");
        }
        List<NodeSpec> out = new ArrayList<>();
        for (JsonElement entry : el.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("room " + roomName + ": every node must be an object");
            }
            JsonObject node = entry.getAsJsonObject();
            try {
                String block = requiredString(node, "block");
                int[] from;
                int[] to;
                if (node.has("at")) {
                    from = parseVec(node.get("at"), "at");
                    to = from;
                } else if (node.has("from") && node.has("to")) {
                    from = parseVec(node.get("from"), "from");
                    to = parseVec(node.get("to"), "to");
                } else {
                    throw new IllegalArgumentException("node needs \"at\" or both \"from\" and \"to\"");
                }
                int count = node.has("count") ? node.get("count").getAsInt() : NodeSpec.ALL;
                double chance = node.has("chance") ? node.get("chance").getAsDouble() : 1.0;
                NodeSpec spec = new NodeSpec(block, from, to, count, chance);
                checkInterior(spec);
                out.add(spec);
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                throw new IllegalArgumentException("room " + roomName + ": bad node: " + e.getMessage(), e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Nodes live in the interior: the shell is unbreakable, so a node there could never be mined. */
    private static void checkInterior(NodeSpec spec) {
        int last = RoomGeometry.CELL - 1;
        if (spec.from[0] < 1 || spec.to[0] > last - 1 || spec.from[2] < 1 || spec.to[2] > last - 1) {
            throw new IllegalArgumentException("node must lie inside the room (x and z 1 to " + (last - 1)
                    + "), not in the wall ring");
        }
        if (spec.from[1] < NODE_MIN_Y || spec.to[1] > NODE_MAX_Y) {
            throw new IllegalArgumentException("node y must be " + NODE_MIN_Y + " to " + NODE_MAX_Y);
        }
    }

    private static int[] parseVec(JsonElement el, String field) {
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("\"" + field + "\" must be a 3-element array [x,y,z]");
        }
        JsonArray arr = el.getAsJsonArray();
        return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
    }

    /** A string field restricted to {@code allowed}; a typo throws with the room named, like {@code access}. */
    private static String parseChoice(JsonElement el, String roomName, String field, String fallback,
                                      String... allowed) {
        String value = stringOrNull(el);
        if (value == null) {
            return fallback;
        }
        for (String ok : allowed) {
            if (ok.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("room " + roomName + ": " + field + " must be one of "
                + String.join(", ", allowed) + ", not \"" + value + "\"");
    }

    private static boolean boolOr(JsonElement el, boolean fallback) {
        if (el == null || !el.isJsonPrimitive()) {
            return fallback;
        }
        return el.getAsBoolean();
    }

    private static List<Integer> parseActs(JsonElement el, String roomName) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        List<Integer> acts = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            int act = e.getAsInt();
            if (act < DungeonDef.MIN_ACT || act > DungeonDef.MAX_ACT) {
                throw new IllegalArgumentException("room " + roomName + ": acts must be "
                        + DungeonDef.MIN_ACT + " to " + DungeonDef.MAX_ACT + ", not " + act);
            }
            acts.add(act);
        }
        return Collections.unmodifiableList(acts);
    }
}
