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

    DungeonRoomMeta(String template, int footprintX, int footprintZ, List<String> roles,
                    int weight, int minDepth, int maxPerDungeon, String processors,
                    List<String> theme, String content) {
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
        return new DungeonRoomMeta(template, footprint[0], footprint[1], roles,
                weight, minDepth, maxPerDungeon, processors, theme, content);
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

    private static String stringOrNull(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        String value = el.getAsString().trim();
        return value.isEmpty() ? null : value;
    }
}
