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

    /** {@code demo:gem} to {@code data/demo/advancement/recipes/foundry/gem.json}: what unlocks the recipe in the book. */
    public static String advancementPath(Ident id) {
        return "data/" + id.namespace() + "/advancement/recipes/foundry/" + id.path() + ".json";
    }

    /**
     * The item whose pickup unlocks a recipe, like vanilla's "has the ingredient" advancements: the first ingredient
     * of a shapeless recipe, the first key of a shaped one in reading order. An upgrade recipe lists the item being
     * upgraded first, so the upgrade appears in the book when its player first holds the item.
     */
    public static String unlockItem(RecipeDef r) {
        if (!r.shaped()) return r.ingredients().isEmpty() ? null : r.ingredients().get(0);
        for (String row : r.pattern()) {
            for (char c : row.toCharArray()) {
                if (c != ' ' && r.key().containsKey(String.valueOf(c))) return r.key().get(String.valueOf(c));
            }
        }
        return null;
    }

    static String advancement(RecipeDef r) {
        JsonObject has = new JsonObject();
        has.addProperty("trigger", "minecraft:inventory_changed");
        JsonObject itemTest = new JsonObject();
        itemTest.addProperty("items", unlockItem(r));
        JsonArray items = new JsonArray();
        items.add(itemTest);
        JsonObject hasConditions = new JsonObject();
        hasConditions.add("items", items);
        has.add("conditions", hasConditions);

        JsonObject recipeUnlocked = new JsonObject();
        recipeUnlocked.addProperty("trigger", "minecraft:recipe_unlocked");
        JsonObject unlockedConditions = new JsonObject();
        // Since 26.3 the trigger takes a list of recipes under "recipes".
        JsonArray unlocked = new JsonArray();
        unlocked.add(r.id().toString());
        unlockedConditions.add("recipes", unlocked);
        recipeUnlocked.add("conditions", unlockedConditions);

        JsonObject criteria = new JsonObject();
        criteria.add("has_item", has);
        criteria.add("has_the_recipe", recipeUnlocked);
        JsonArray requirement = new JsonArray();
        requirement.add("has_the_recipe");
        requirement.add("has_item");
        JsonArray requirements = new JsonArray();
        requirements.add(requirement);
        JsonArray recipes = new JsonArray();
        recipes.add(r.id().toString());
        JsonObject rewards = new JsonObject();
        rewards.add("recipes", recipes);

        JsonObject o = new JsonObject();
        o.addProperty("parent", "minecraft:recipes/root");
        o.add("criteria", criteria);
        o.add("requirements", requirements);
        o.add("rewards", rewards);
        return o + "\n";
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
            // Without an advancement the recipe works but never shows in the recipe book or raises "New recipe".
            if (unlockItem(r) != null) out.put(advancementPath(r.id()), advancement(r));
        }
        return out;
    }
}
