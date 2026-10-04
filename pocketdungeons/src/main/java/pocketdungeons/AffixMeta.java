package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M69: parses one {@code dungeon_affix/*.json} file into an
 * {@link AffixDefinition}. Mirrors {@link DungeonThemeMeta}'s shape: a static
 * {@link #fromJson} that reads the version, validates required fields, and
 * throws naming the file on any violation so the manifest loader can catch
 * and report it.
 *
 * <p>The supported operation set is closed. A JSON file that declares an
 * operation field this parser does not read is silently ignored (Gson does
 * not fail on unknown fields by default), so the schema documents every
 * supported field and the loader rejects a definition whose
 * {@link AffixEffects#hasAnyOperation()} is false: that is the gate that
 * catches a file which only renames an existing enum, the failure mode the
 * handoff says must not pass acceptance.
 */
final class AffixMeta {

    private AffixMeta() {}

    static AffixDefinition fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        String id = fileIdentity;
        String label = requiredString(obj, "label", fileIdentity);
        String blurb = requiredString(obj, "blurb", fileIdentity);
        int order = intOr(obj, "order", 0);
        int minLevel = intOr(obj, "min_level", 0);
        int weight = intOr(obj, "weight", 1);
        int depletionMultiplier = intOr(obj, "depletion_multiplier", 1);
        if (depletionMultiplier < 1 || depletionMultiplier > 2) {
            throw new IllegalArgumentException("depletion_multiplier must be 1 or 2");
        }
        Set<String> incompatible = stringSetOr(obj, "incompatible", Set.of());

        AffixEffects effects = parseEffects(obj, fileIdentity);
        if (!effects.hasAnyOperation()) {
            throw new IllegalArgumentException(
                    "affix '" + id + "' declares no operation; a definition that bends nothing "
                            + "has no business shipping");
        }
        // A contraction of at most five letters, and only for longer names: a label
        // of five letters or fewer is already short and keeps itself (owner, 2026-10-02).
        String shortName = label;
        if (label.length() > 5 && obj.has("short_name") && obj.get("short_name").isJsonPrimitive()) {
            shortName = obj.get("short_name").getAsString().trim();
            if (shortName.isEmpty() || shortName.length() > 5) {
                throw new IllegalArgumentException("short_name must be 1 to 5 characters: " + shortName);
            }
        }
        return new AffixDefinition(id, label, shortName, blurb, order, minLevel, weight,
                depletionMultiplier, incompatible, effects);
    }

    private static AffixEffects parseEffects(JsonObject obj, String fileIdentity) {
        JsonElement effectsEl = obj.get("effects");
        if (effectsEl == null || effectsEl.isJsonNull() || !effectsEl.isJsonObject()) {
            throw new IllegalArgumentException("missing required field: effects (object)");
        }
        JsonObject e = effectsEl.getAsJsonObject();

        boolean ominous = boolOr(e, "ominous", false);
        double trialCountMultiplier = doubleOr(e, "trial_count_multiplier", 1.0);
        double cooldownFactor = doubleOr(e, "cooldown_factor", 1.0);
        int playerRange = intOr(e, "player_range", 14);
        AffixEffects.ConsumableRule consumableRule = enumOr(e, "consumable_rule",
                AffixEffects.ConsumableRule.ALLOW, AffixEffects.ConsumableRule.class);
        boolean neutralWolfSpawn = boolOr(e, "neutral_wolf_spawn", false);
        AffixEffects.HazardKind hazardKind = enumOr(e, "hazard_kind",
                AffixEffects.HazardKind.NONE, AffixEffects.HazardKind.class);
        int hazardsPerCell = intOr(e, "hazards_per_cell", 0);
        boolean voidedFloor = boolOr(e, "voided_floor", false);
        boolean extraTrialBodies = boolOr(e, "extra_trial_bodies", false);
        String bonusToolPool = stringOrNull(e.get("bonus_tool_pool"));
        String decorPool = stringOrNull(e.get("decor_pool"));

        return AffixEffects.build(ominous, trialCountMultiplier, cooldownFactor, playerRange,
                consumableRule, neutralWolfSpawn, hazardKind, hazardsPerCell, voidedFloor,
                extraTrialBodies, bonusToolPool, decorPool);
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

    private static double doubleOr(JsonObject obj, String key, double fallback) {
        JsonElement el = obj.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsDouble();
    }

    private static boolean boolOr(JsonObject obj, String key, boolean fallback) {
        JsonElement el = obj.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsBoolean();
    }

    private static Set<String> stringSetOr(JsonObject obj, String key, Set<String> fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement item : el.getAsJsonArray()) {
            String resolved = AffixIds.resolve(item.getAsString());
            if (resolved != null) {
                out.add(resolved);
            }
        }
        return out;
    }

    private static <T extends Enum<T>> T enumOr(JsonObject obj, String key, T fallback, Class<T> type) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        String name = el.getAsString().trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(key + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()) + ", not '" + el.getAsString() + "'");
        }
    }
}
