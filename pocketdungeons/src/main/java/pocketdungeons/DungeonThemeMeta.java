package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Plain dungeon theme metadata with no Minecraft imports.
 *
 * <p>M68 adds three namespaced reference fields alongside the legacy
 * {@code loot_suffix} and {@code spawner_prefix}: {@link #lootTable},
 * {@link #normalSpawner} and {@link #ominousSpawner}. The legacy fields resolve
 * against the {@code pocketdungeons} namespace only, so a third party theme
 * using them secretly requires {@code pocketdungeons} data; the namespaced
 * fields let a third party theme point at its own namespace's loot table and
 * trial spawner configs instead. The namespaced fields take precedence when
 * present. The legacy fields are kept for backward compatibility and never
 * removed (codec migration discipline).
 */
final class DungeonThemeMeta {

    final String name;
    final String processors;
    final String roomTheme;
    final String lootSuffix;
    final String spawnerPrefix;
    /** M68: fully qualified loot table id overriding the tier base table, or {@code null}. */
    final String lootTable;
    /** M68: fully qualified normal mode trial spawner config id, or {@code null}. */
    final String normalSpawner;
    /** M68: fully qualified ominous mode trial spawner config id, or {@code null}. */
    final String ominousSpawner;

    DungeonThemeMeta(String name, String processors, String roomTheme,
                     String lootSuffix, String spawnerPrefix) {
        this(name, processors, roomTheme, lootSuffix, spawnerPrefix, null, null, null);
    }

    DungeonThemeMeta(String name, String processors, String roomTheme,
                     String lootSuffix, String spawnerPrefix,
                     String lootTable, String normalSpawner, String ominousSpawner) {
        this.name = name;
        this.processors = processors;
        this.roomTheme = roomTheme;
        this.lootSuffix = lootSuffix;
        this.spawnerPrefix = spawnerPrefix;
        this.lootTable = lootTable;
        this.normalSpawner = normalSpawner;
        this.ominousSpawner = ominousSpawner;
    }

    static DungeonThemeMeta fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        return new DungeonThemeMeta(requiredString(obj, "name"),
                requiredString(obj, "processors"), stringOrNull(obj.get("room_theme")),
                stringOrNull(obj.get("loot_suffix")),
                stringOrNull(obj.get("spawner_prefix")),
                stringOrNull(obj.get("loot_table")),
                stringOrNull(obj.get("normal_spawner")),
                stringOrNull(obj.get("ominous_spawner")));
    }

    /** Legacy call site that has no file identity; reads as version 1. */
    static DungeonThemeMeta fromJson(JsonObject obj) {
        return fromJson(obj, "<unknown theme>");
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
}
