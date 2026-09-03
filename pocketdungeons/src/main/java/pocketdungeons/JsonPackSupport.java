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
     */
    static String baseName(Identifier location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
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
