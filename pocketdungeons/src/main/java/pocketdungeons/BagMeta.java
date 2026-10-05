package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M70: parses one {@code dungeon_bag/*.json} file into a
 * {@link BagDefinition}. Mirrors {@link AffixMeta}'s shape: a static
 * {@link #fromJson} that reads the version, validates required fields, and
 * throws naming the file on any violation so the manifest loader can catch
 * and report it.
 *
 * <p>The capability tag vocabulary is closed ({@link SituationTags}); a tag
 * outside it is rejected at load with the bag and the offending tag named,
 * the same gate a room's {@code provides} and {@code requires} pass through.
 * A bag that declares no tags is allowed (Pilgrim's empty set is the whole
 * point), so there is no "declares nothing" rejection the way an affix has.
 */
final class BagMeta {

    private BagMeta() {}

    static BagDefinition fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        String id = fileIdentity;
        String label = requiredString(obj, "label", fileIdentity);
        String blurb = requiredString(obj, "blurb", fileIdentity);
        int order = intOr(obj, "order", 0);
        List<String> headline = stringListOr(obj, "headline", List.of());
        Set<String> tags = tagSetOr(obj, "tags", Set.of(), fileIdentity);
        String lootTable = stringOrNull(obj.get("loot_table"));
        if (lootTable == null) {
            // Default: derive from the bag's own namespaced id, so a bag at
            // data/mypack/dungeon_bag/my_bag.json rolls from
            // mypack:bags/my_bag unless it names its own table.
            lootTable = defaultLootTable(id);
        }
        List<BagDefinition.KitItem> baseline = kitBaselineOr(obj, fileIdentity);
        JsonElement hiddenEl = obj.get("hidden");
        boolean hidden = hiddenEl != null && !hiddenEl.isJsonNull() && hiddenEl.getAsBoolean();
        return new BagDefinition(id, label, blurb, order, headline, tags, lootTable, baseline, hidden);
    }

    /**
     * The optional {@code kit_baseline} array: {@code {"item", "count",
     * "durability"?, "empties_into"?}} per line. A line with no item, a count
     * below 1, or an item listed twice is rejected with the file named; that
     * an item id resolves is the validator's job, since this parser runs
     * without a registry.
     */
    private static List<BagDefinition.KitItem> kitBaselineOr(JsonObject obj, String fileIdentity) {
        JsonElement el = obj.get("kit_baseline");
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        List<BagDefinition.KitItem> out = new java.util.ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement line : el.getAsJsonArray()) {
            JsonObject entry = line.getAsJsonObject();
            String item = stringOrNull(entry.get("item"));
            if (item == null) {
                throw new IllegalArgumentException(fileIdentity + ": kit_baseline line with no item");
            }
            int count = intOr(entry, "count", 1);
            if (count < 1) {
                throw new IllegalArgumentException(fileIdentity + ": kit_baseline " + item
                        + " has count " + count + "; it must be at least 1");
            }
            if (!seen.add(item)) {
                throw new IllegalArgumentException(fileIdentity + ": kit_baseline lists " + item + " twice");
            }
            JsonElement durability = entry.get("durability");
            boolean isDurability = durability != null && !durability.isJsonNull() && durability.getAsBoolean();
            String emptiesInto = stringOrNull(entry.get("empties_into"));
            out.add(new BagDefinition.KitItem(item, count, isDurability, emptiesInto));
        }
        return List.copyOf(out);
    }

    /**
     * The default loot table id for a bag that does not name its own:
     * {@code <namespace>:bags/<path>}, matching the pre-M70 path the enum
     * derived for the built-ins.
     */
    static String defaultLootTable(String bagId) {
        int colon = bagId.indexOf(':');
        String namespace = colon < 0 ? PocketDungeonsMod.MOD_ID : bagId.substring(0, colon);
        String path = colon < 0 ? bagId : bagId.substring(colon + 1);
        return namespace + ":bags/" + path;
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
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            out.add(item.getAsString());
        }
        return List.copyOf(out);
    }

    private static Set<String> tagSetOr(JsonObject obj, String key, Set<String> fallback,
                                        String fileIdentity) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement item : el.getAsJsonArray()) {
            String tag = item.getAsString();
            if (!SituationTags.isKnown(tag)) {
                throw new IllegalArgumentException("bag " + fileIdentity
                        + ": unknown situation tag \"" + tag + "\"; known tags are "
                        + SituationTags.ALL.stream().sorted()
                                .reduce((a, b) -> a + ", " + b).orElse(""));
            }
            out.add(tag);
        }
        return Set.copyOf(out);
    }
}
