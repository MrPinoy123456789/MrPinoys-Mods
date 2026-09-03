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

    /** The only value {@link #access} may take besides {@link #ACCESS_GATED}. */
    static final String ACCESS_OPEN = "open";

    /** A cell the player must solve to pass through. */
    static final String ACCESS_GATED = "gated";

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme, String content, int tier, List<String> provides,
                    List<String> requires, String pressure, String access) {
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
    }

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme, String content) {
        this(template, footprintX, footprintZ, roles, weight, minDepth, maxPerDungeon,
                processors, theme, content, 1, List.of(), List.of(), null, ACCESS_OPEN);
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
        return new DungeonRoomMeta(template, footprint[0], footprint[1], roles,
                weight, minDepth, maxPerDungeon, processors, theme, content,
                tier, provides, requires, pressure, access);
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
            roles.add(e.getAsString());
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

    private static String stringOrNull(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        String value = el.getAsString().trim();
        return value.isEmpty() ? null : value;
    }
}
