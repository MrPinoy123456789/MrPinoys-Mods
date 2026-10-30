package foundry.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns recipe JSON text into a {@link RecipeDef}, reporting every problem found instead of stopping at the first. */
public final class RecipeParser {
    private RecipeParser() {}

    public record Result(RecipeDef def, List<Problem> problems) {}

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

        int version = intField(o, "schema_version", RecipeDef.SCHEMA_VERSION, source, problems);
        if (version != RecipeDef.SCHEMA_VERSION) {
            problems.add(Problem.error(source, "schema_version", "Unsupported schema version " + version + ".",
                    "Use schema_version 1."));
        }
        Ident id = identField(o, "id", source, problems);
        String type = stringField(o, "type", null, source, problems);
        if (type == null || !RecipeDef.TYPES.contains(type)) {
            problems.add(Problem.error(source, "type", "Missing or unknown recipe type.",
                    "Use \"shaped\" or \"shapeless\"."));
            type = null;
        }
        String group = o.has("group") ? stringField(o, "group", null, source, problems) : null;
        Ident result = identField(o, "result", source, problems);
        int count = intField(o, "count", 1, source, problems);
        if (count < 1 || count > 99) {
            problems.add(Problem.error(source, "count", "count must be 1 to 99.", "Use a value in that range."));
        }

        List<String> pattern = new ArrayList<>();
        Map<String, String> key = new LinkedHashMap<>();
        List<String> ingredients = new ArrayList<>();
        if ("shaped".equals(type)) {
            shaped(o, pattern, key, source, problems);
        } else if ("shapeless".equals(type)) {
            shapeless(o, ingredients, source, problems);
        }

        if (problems.stream().anyMatch(Problem::blocking)) return new Result(null, problems);
        return new Result(new RecipeDef(version, id, type, List.copyOf(pattern), Map.copyOf(key),
                List.copyOf(ingredients), result, count, group), problems);
    }

    private static void shaped(JsonObject o, List<String> pattern, Map<String, String> key,
            String source, List<Problem> out) {
        if (!o.has("pattern") || !o.get("pattern").isJsonArray()) {
            out.add(Problem.error(source, "pattern", "A shaped recipe needs a pattern list.",
                    "Use \"pattern\": [\"XX\", \"# \"]."));
        } else {
            List<String> rows = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("pattern")) rows.add(e.isJsonPrimitive() ? e.getAsString() : "?");
            if (rows.isEmpty() || rows.size() > RecipeDef.MAX_GRID) {
                out.add(Problem.error(source, "pattern", "A pattern needs 1 to " + RecipeDef.MAX_GRID + " rows.",
                        "The crafting grid is 3 by 3."));
            }
            int width = rows.isEmpty() ? 0 : rows.get(0).length();
            for (String row : rows) {
                if (row.isEmpty() || row.length() > RecipeDef.MAX_GRID) {
                    out.add(Problem.error(source, "pattern", "Every pattern row must be 1 to " + RecipeDef.MAX_GRID
                            + " characters.", "The crafting grid is 3 wide."));
                }
                if (row.length() != width) {
                    out.add(Problem.error(source, "pattern", "Every pattern row must be the same length.",
                            "Pad short rows with spaces."));
                }
            }
            pattern.addAll(rows);
        }
        if (!o.has("key") || !o.get("key").isJsonObject()) {
            out.add(Problem.error(source, "key", "A shaped recipe needs a key object.",
                    "Use \"key\": { \"#\": \"minecraft:stick\" }."));
        } else {
            for (var entry : o.getAsJsonObject("key").entrySet()) {
                String symbol = entry.getKey();
                if (symbol.length() != 1 || symbol.equals(" ")) {
                    out.add(Problem.error(source, "key", "A key symbol must be one character and not a space.",
                            "Use a letter such as \"X\" or \"#\"."));
                    continue;
                }
                String value = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : null;
                if (!ingredientOk(value)) {
                    out.add(Problem.error(source, "key", "Key " + symbol + " is not an item id or #tag.",
                            "Use a namespaced id such as minecraft:stick, or a tag such as #minecraft:planks."));
                    continue;
                }
                key.put(symbol, value);
            }
        }
        if (!pattern.isEmpty()) {
            for (String symbol : key.keySet()) {
                if (pattern.stream().noneMatch(row -> row.contains(symbol))) {
                    out.add(Problem.error(source, "key", "Key " + symbol + " never appears in the pattern.",
                            "Use it in a row, or remove it."));
                }
            }
            for (String row : pattern) {
                for (char c : row.toCharArray()) {
                    if (c != ' ' && !key.containsKey(String.valueOf(c))) {
                        out.add(Problem.error(source, "pattern", "The pattern uses \"" + c
                                + "\" which has no key.", "Add it to the key object, or use a space for empty."));
                    }
                }
            }
        }
    }

    private static void shapeless(JsonObject o, List<String> ingredients, String source, List<Problem> out) {
        if (!o.has("ingredients") || !o.get("ingredients").isJsonArray()) {
            out.add(Problem.error(source, "ingredients", "A shapeless recipe needs an ingredients list.",
                    "Use \"ingredients\": [\"minecraft:paper\", \"minecraft:leather\"]."));
            return;
        }
        for (JsonElement e : o.getAsJsonArray("ingredients")) {
            String value = e.isJsonPrimitive() ? e.getAsString() : null;
            if (!ingredientOk(value)) {
                out.add(Problem.error(source, "ingredients", "Every ingredient must be an item id or #tag.",
                        "Use a namespaced id such as minecraft:stick, or a tag such as #minecraft:planks."));
                continue;
            }
            ingredients.add(value);
        }
        if (ingredients.isEmpty()) {
            out.add(Problem.error(source, "ingredients", "A shapeless recipe needs at least one ingredient.",
                    "Add up to " + RecipeDef.MAX_INGREDIENTS + " item ids."));
        }
        if (ingredients.size() > RecipeDef.MAX_INGREDIENTS) {
            out.add(Problem.error(source, "ingredients", "At most " + RecipeDef.MAX_INGREDIENTS + " ingredients.",
                    "The crafting grid holds 9."));
        }
    }

    private static boolean ingredientOk(String value) {
        if (value == null) return false;
        if (value.startsWith("#")) return Ident.parse(value.substring(1)) != null;
        return Ident.parse(value) != null;
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

    private static Ident identField(JsonObject o, String key, String src, List<Problem> out) {
        if (!o.has(key)) {
            out.add(Problem.error(src, key, "Missing required field " + key + ".",
                    "Add " + key + " as a namespaced id such as myserver:crystal_sword."));
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
