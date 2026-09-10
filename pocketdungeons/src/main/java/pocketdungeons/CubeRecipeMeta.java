package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * M71: parses one {@code cube_recipe/*.json} file into a
 * {@link CubeRecipeDefinition}. Mirrors {@link AffixMeta}'s shape: a static
 * {@link #fromJson} that reads the version, validates required fields, and
 * throws naming the file on any violation so the manifest loader can catch
 * and report it.
 *
 * <p>The supported effect set is closed ({@link RecipeEffects}); a JSON file
 * that declares an effect field this parser does not read is silently
 * ignored (Gson does not fail on unknown fields by default), so the loader
 * rejects a definition whose {@link RecipeEffects#hasAnyOperation()} is
 * false: that is the gate that catches a file which only renames an
 * existing recipe, the failure mode the handoff says must not pass
 * acceptance.
 */
final class CubeRecipeMeta {

    private CubeRecipeMeta() {}

    static CubeRecipeDefinition fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        String id = fileIdentity;
        String confirmation = requiredString(obj, "confirmation", fileIdentity);
        String catalystItem = stringOrNull(obj.get("catalyst"));
        String catalystTag = stringOrNull(obj.get("catalyst_tag"));
        if (catalystItem == null && catalystTag == null) {
            throw new IllegalArgumentException(fileIdentity
                    + ": exactly one of \"catalyst\" or \"catalyst_tag\" must be set");
        }
        if (catalystItem != null && catalystTag != null) {
            throw new IllegalArgumentException(fileIdentity
                    + ": \"catalyst\" and \"catalyst_tag\" are mutually exclusive");
        }
        int cost = intOr(obj, "cost", 1);
        if (cost < 1) {
            throw new IllegalArgumentException(fileIdentity + ": cost must be >= 1");
        }
        int priority = intOr(obj, "priority", 0);
        int minLevel = intOr(obj, "min_level", 0);
        if (minLevel < 0) {
            throw new IllegalArgumentException(fileIdentity + ": min_level must be >= 0");
        }
        RecipeEffects effects = parseEffects(obj, fileIdentity);
        if (!effects.hasAnyOperation()) {
            throw new IllegalArgumentException(
                    "recipe '" + id + "' declares no effect; a definition that bends nothing "
                            + "has no business shipping");
        }
        return new CubeRecipeDefinition(id, confirmation, catalystItem, catalystTag,
                cost, priority, minLevel, effects);
    }

    private static RecipeEffects parseEffects(JsonObject obj, String fileIdentity) {
        JsonElement effectsEl = obj.get("effects");
        if (effectsEl == null || effectsEl.isJsonNull() || !effectsEl.isJsonObject()) {
            throw new IllegalArgumentException("missing required field: effects (object)");
        }
        JsonObject e = effectsEl.getAsJsonObject();

        boolean ominous = boolOr(e, "ominous", false);
        boolean feral = boolOr(e, "feral", false);
        boolean completionStudyList = boolOr(e, "completion_study_list", false);
        boolean boundedSupply = boolOr(e, "bounded_supply", false);
        boolean endlessMine = boolOr(e, "endless_mine", false);
        int pathLengthBonus = intOr(e, "path_length_bonus", 0);
        List<String> weightedRooms = stringListOr(e, "weighted_rooms");
        List<RecipeEffects.GuaranteedRoom> guaranteedRooms = parseGuaranteedRooms(e, fileIdentity);

        return RecipeEffects.build(ominous, feral, completionStudyList, boundedSupply,
                endlessMine, pathLengthBonus, weightedRooms, guaranteedRooms);
    }

    private static List<RecipeEffects.GuaranteedRoom> parseGuaranteedRooms(JsonObject e,
                                                                            String fileIdentity) {
        JsonElement el = e.get("guaranteed_rooms");
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        List<RecipeEffects.GuaranteedRoom> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            JsonObject g = item.getAsJsonObject();
            List<String> names = stringListOr(g, "names");
            if (names.isEmpty()) {
                throw new IllegalArgumentException(fileIdentity
                        + ": a guaranteed_room group must name at least one room");
            }
            int minTier = intOr(g, "min_tier", 0);
            out.add(new RecipeEffects.GuaranteedRoom(names, minTier));
        }
        return List.copyOf(out);
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

    private static boolean boolOr(JsonObject obj, String key, boolean fallback) {
        JsonElement el = obj.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsBoolean();
    }

    private static List<String> stringListOr(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            out.add(item.getAsString());
        }
        return List.copyOf(out);
    }
}
