package foundry.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.TreeMap;

/**
 * The generated datapack part: vanilla recipe JSON keyed by path under {@code data/}, deterministic like
 * {@link PackFiles}. Written to {@code config/foundry/datapack/foundry/recipes/} by the Minecraft layer and read
 * back by the game's data reload.
 */
public final class RecipeFiles {
    private RecipeFiles() {}

    /** {@code demo:gem} to {@code data/demo/recipe/gem.json}. */
    public static String recipePath(Ident id) {
        return "data/" + id.namespace() + "/recipe/" + id.path() + ".json";
    }

    public static Map<String, String> generate(Map<Ident, RecipeDef> recipes) {
        Map<String, String> out = new TreeMap<>();
        for (RecipeDef r : recipes.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", r.shaped() ? "minecraft:crafting_shaped" : "minecraft:crafting_shapeless");
            if (r.group() != null) o.addProperty("group", r.group());
            if (r.shaped()) {
                JsonObject key = new JsonObject();
                r.key().forEach(key::addProperty);
                o.add("key", key);
                JsonArray pattern = new JsonArray();
                r.pattern().forEach(pattern::add);
                o.add("pattern", pattern);
            } else {
                JsonArray ingredients = new JsonArray();
                r.ingredients().forEach(ingredients::add);
                o.add("ingredients", ingredients);
            }
            JsonObject result = new JsonObject();
            result.addProperty("id", r.result().toString());
            if (r.count() != 1) result.addProperty("count", r.count());
            o.add("result", result);
            out.put(recipePath(r.id()), o + "\n");
        }
        return out;
    }
}
