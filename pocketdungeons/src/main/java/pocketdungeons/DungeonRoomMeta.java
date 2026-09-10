package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** The only value {@link #access} may take besides {@link #ACCESS_GATED}. */
    static final String ACCESS_OPEN = "open";

    /** A cell the player must solve to pass through. */
    static final String ACCESS_GATED = "gated";

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
        return new DungeonRoomMeta(template, footprint[0], footprint[1], roles,
                weight, minDepth, maxPerDungeon, processors, theme, content,
                tier, provides, requires, pressure, access, window, spanY);
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
}
