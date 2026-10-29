package foundry.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Turns item JSON text into an {@link ItemDef}, reporting every problem found instead of stopping at the first. */
public final class ItemParser {
    private ItemParser() {}

    public record Result(ItemDef def, List<Problem> problems) {}

    public static Result parse(String source, String json) {
        List<Problem> problems = new ArrayList<>();
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                problems.add(Problem.error(source, "file", "The file is not a JSON object.",
                        "Wrap the definition in { } braces."));
                return new Result(null, problems);
            }
            o = root.getAsJsonObject();
        } catch (RuntimeException e) {
            problems.add(Problem.error(source, "file", "The file is not valid JSON: " + e.getMessage(),
                    "Check for a missing comma, quote or bracket."));
            return new Result(null, problems);
        }

        int version = intField(o, "schema_version", ItemDef.SCHEMA_VERSION, source, problems);
        if (version != ItemDef.SCHEMA_VERSION) {
            problems.add(Problem.error(source, "schema_version",
                    "Unsupported schema version " + version + ".", "Use schema_version 1."));
        }
        Ident id = identField(o, "id", true, source, problems);
        Ident base = identField(o, "base_item", true, source, problems);
        Ident model = identField(o, "model", false, source, problems);
        Ident texture = identField(o, "texture", false, source, problems);
        if (model != null && texture != null) {
            problems.add(Problem.error(source, "texture", "An item cannot have both model and texture.",
                    "Keep texture to let Foundry build a flat model, or keep model to use your own."));
        }
        String name = stringField(o, "display_name", null, source, problems);
        if (name == null || name.isBlank()) {
            problems.add(Problem.error(source, "display_name", "The item has no display name.",
                    "Add a display_name such as \"Crystal Sword\"."));
        }
        List<String> lore = new ArrayList<>();
        if (o.has("lore")) {
            if (!o.get("lore").isJsonArray()) {
                problems.add(Problem.error(source, "lore", "lore must be a list of text lines.",
                        "Use [\"line one\", \"line two\"]."));
            } else {
                for (JsonElement e : (JsonArray) o.get("lore")) lore.add(e.getAsString());
            }
        }
        int stack = intField(o, "stack_size", 64, source, problems);
        int durability = intField(o, "durability", 0, source, problems);
        boolean glint = o.has("glint") && o.get("glint").getAsBoolean();
        String rarity = stringField(o, "rarity", "common", source, problems);

        var mechanics = foundry.core.mechanics.MechanicParser.parse(source, id == null ? source : id.toString(),
                o.get("mechanics"), problems);

        if (problems.stream().anyMatch(Problem::blocking)) return new Result(null, problems);
        return new Result(new ItemDef(version, id, name, List.copyOf(lore), base, model, texture, stack, durability, glint, rarity, List.copyOf(mechanics)),
                problems);
    }

    private static int intField(JsonObject o, String key, int dflt, String src, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be a whole number.", "Use a number such as " + dflt + "."));
            return dflt;
        }
    }

    private static String stringField(JsonObject o, String key, String dflt, String src, List<Problem> out) {
        if (!o.has(key)) return dflt;
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be text.", "Put the value in quotes."));
            return dflt;
        }
    }

    private static Ident identField(JsonObject o, String key, boolean required, String src, List<Problem> out) {
        if (!o.has(key)) {
            if (required) {
                out.add(Problem.error(src, key, "Missing required field " + key + ".",
                        "Add " + key + " as a namespaced id such as myserver:crystal_sword."));
            }
            return null;
        }
        String text = stringField(o, key, null, src, out);
        Ident id = Ident.parse(text);
        if (id == null) {
            out.add(Problem.error(src, key, "\"" + text + "\" is not a valid identifier.",
                    "Use lowercase letters, digits and underscores in the form namespace:name."));
        }
        return id;
    }
}
