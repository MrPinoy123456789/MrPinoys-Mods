package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/**
 * M43.5: the one piece of {@code Diaries.load}, {@code AdventureGraphs.load},
 * {@code ThemeManifest.load} and {@code RoomManifest.loadFrom}'s shared
 * ~20-line loader shape (list resources, sort by key, parse each with a
 * per-file try/catch into a rejections list, publish to a volatile field)
 * that was byte-for-byte identical across three of the four and worth a
 * single home: turning a resource {@link Identifier} into the bare file
 * name a datapack entry is keyed by. The rest of the loader shape stays
 * where it is in each class, since each one's parse step, rejection
 * wording, and published object differ enough that a fully generic loader
 * would need to abstract those differences away rather than remove real
 * duplication.
 *
 * <p>{@link #requiredString} is the second piece pulled in here: it was
 * byte-for-byte identical between {@code AdventureGraphs} and
 * {@code Diaries} (missing-field check, trim, blank-after-trim check).
 * {@code DungeonThemeMeta} and {@code DungeonRoomMeta} each have a
 * same-named helper too, but with different behavior (no trim, no blank
 * check), so those two are left alone rather than folded in here: unifying
 * them would change what a malformed datapack entry does, which is outside
 * M43's structural-only scope.
 */
final class JsonPackSupport {

    private JsonPackSupport() {}

    /**
     * {@code data/<namespace>/<folder>/sub/dir/entry_name.json} to
     * {@code entry_name}: the last path segment, with the {@code .json}
     * extension stripped. {@code listResources} is always called with a
     * {@code .json}-suffix filter, so the extension is always present in
     * practice; still checked rather than assumed.
     *
     * <p>M68: kept for callers that still want the bare segment, but the
     * namespaced identity a manifest keys by is now {@link #resourceId}, which
     * keeps the namespace. {@code baseName} discards the namespace and is
     * therefore the wrong key for any manifest that must let two packs with the
     * same local name coexist.
     */
    static String baseName(Identifier location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    /**
     * M68: the namespaced identity of a datapack resource, used as the manifest
     * key so two packs with the same local name in different namespaces both
     * load. Turns {@code data/<namespace>/<folder>/sub/dir/entry_name.json}
     * into {@code <namespace>:sub/dir/entry_name}: the namespace plus the path
     * relative to the content type folder, with the {@code .json} suffix
     * stripped. A file at the folder root has no {@code sub/dir/} prefix, so
     * {@code data/mypack/dungeon_room/hall_tee.json} is {@code mypack:hall_tee}.
     *
     * <p>The {@code folder} prefix (e.g. {@code "dungeon_room"}) is stripped so
     * the identity matches the references content authors write: a theme file at
     * {@code data/pocketdungeons/dungeon_theme/deepslate.json} is
     * {@code pocketdungeons:deepslate}, which is what an adventure graph
     * transition's {@code "theme": "deepslate"} qualifies to. Without this
     * strip the identity would be {@code pocketdungeons:dungeon_theme/deepslate}
     * and no existing reference would resolve.
     */
    static String resourceId(Identifier location, String folder) {
        String path = location.getPath();
        String prefix = folder + "/";
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - 5);
        }
        return location.getNamespace() + ":" + path;
    }

    /**
     * M68: resolves a content reference read from a datapack file into a
     * namespaced id. A reference that already contains a colon is qualified and
     * returned as is. A legacy unqualified reference (no colon) maps to the
     * {@code pocketdungeons} namespace, the rule the M68 schema set publishes:
     * legacy unqualified built ins map to {@code pocketdungeons}, and a bare
     * name that is not a {@code pocketdungeons} built in is left as
     * {@code pocketdungeons:<name>} for the caller to test against the loaded
     * set and report as an unresolved legacy reference rather than silently
     * last file wins.
     */
    static String qualify(String reference) {
        if (reference == null || reference.isBlank()) {
            return reference;
        }
        if (reference.indexOf(':') >= 0) {
            return reference;
        }
        return PocketDungeonsMod.MOD_ID + ":" + reference;
    }

    /**
     * M68: reads the {@code version} field a versioned content contract
     * declares, defaulting to {@code 1} when absent (a pre M68 file carries no
     * version and is generation 1). Throws, naming the file id, when the
     * declared version is not the single generation this round supports, so a
     * future generation file is rejected up front rather than parsed into an
     * incompatible in memory shape.
     */
    static int parseVersion(com.google.gson.JsonObject obj, String fileIdentity) {
        com.google.gson.JsonElement element = obj.get("version");
        if (element == null || element.isJsonNull()) {
            return 1;
        }
        int version = element.getAsInt();
        if (version != 1) {
            throw new IllegalArgumentException(fileIdentity + ": unsupported content version " + version
                    + " (this build supports version 1 only)");
        }
        return version;
    }

    /** A trimmed, non-blank string field, or an {@link IllegalArgumentException} naming {@code key}. */
    static String requiredString(JsonObject obj, String key) {
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
}
