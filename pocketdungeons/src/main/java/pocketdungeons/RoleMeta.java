package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M70: parses one {@code dungeon_role/*.json} file into a
 * {@link RoomRoleDefinition}. Mirrors {@link AffixMeta}'s shape: a static
 * {@link #fromJson} that reads the version, validates required fields, and
 * throws naming the file on any violation so the manifest loader can catch
 * and report it.
 *
 * <p>The supported operation set is closed ({@link RoomRoleDefinition.Operation}).
 * A JSON file that declares an operation outside this set is rejected at
 * load. A definition that declares {@code stage: "structural"} is rejected:
 * structural roles are engine-owned and a pack cannot add one, the same way
 * a pack cannot add a new door direction.
 */
final class RoleMeta {

    private RoleMeta() {}

    static RoomRoleDefinition fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        String id = fileIdentity;
        String stage = requiredString(obj, "stage", fileIdentity);
        if ("structural".equals(stage)) {
            throw new IllegalArgumentException(
                    "role '" + id + "': structural roles are engine-owned and cannot be added by data");
        }
        if (!"interior".equals(stage)) {
            throw new IllegalArgumentException(
                    "role '" + id + "': stage must be \"interior\" (structural roles are engine-owned)");
        }
        int weight = intOr(obj, "weight", 1);
        if (weight < 0) {
            throw new IllegalArgumentException("role '" + id + "': weight must be >= 0");
        }
        int minDepth = intOr(obj, "min_depth", 0);
        if (minDepth < 0) {
            throw new IllegalArgumentException("role '" + id + "': min_depth must be >= 0");
        }
        int maxDepth = intOr(obj, "max_depth", -1);
        List<String> masks = stringListOr(obj, "masks", List.of());
        RoomRoleDefinition.Operation operation = enumOr(obj, "operation",
                RoomRoleDefinition.Operation.NONE, RoomRoleDefinition.Operation.class);
        return new RoomRoleDefinition(id, stage, weight, minDepth, maxDepth, masks, operation);
    }

    private static String requiredString(JsonObject obj, String key, String fileIdentity) {
        String value = stringOrNull(obj.get(key));
        if (value == null) {
            throw new IllegalArgumentException(fileIdentity + ": missing required field: " + key);
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

    private static int intOr(JsonObject obj, String key, int fallback) {
        JsonElement el = obj.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsInt();
    }

    private static List<String> stringListOr(JsonObject obj, String key, List<String> fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            out.add(item.getAsString());
        }
        return List.copyOf(out);
    }

    private static <T extends Enum<T>> T enumOr(JsonObject obj, String key, T fallback, Class<T> type) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        String name = el.getAsString().trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(key + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()) + ", not '" + el.getAsString() + "'");
        }
    }
}
