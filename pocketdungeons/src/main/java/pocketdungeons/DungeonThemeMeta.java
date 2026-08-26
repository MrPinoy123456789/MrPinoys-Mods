package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Plain dungeon theme metadata with no Minecraft imports. */
final class DungeonThemeMeta {

    final String name;
    final String processors;
    final String roomTheme;
    final boolean discoverable;
    final String lootSuffix;

    DungeonThemeMeta(String name, String processors, String roomTheme, boolean discoverable,
                     String lootSuffix) {
        this.name = name;
        this.processors = processors;
        this.roomTheme = roomTheme;
        this.discoverable = discoverable;
        this.lootSuffix = lootSuffix;
    }

    static DungeonThemeMeta fromJson(JsonObject obj) {
        return new DungeonThemeMeta(requiredString(obj, "name"),
                requiredString(obj, "processors"), stringOrNull(obj.get("room_theme")),
                booleanOr(obj.get("discoverable"), true), stringOrNull(obj.get("loot_suffix")));
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringOrNull(obj.get(key));
        if (value == null) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    private static String stringOrNull(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean booleanOr(JsonElement element, boolean fallback) {
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }
}
