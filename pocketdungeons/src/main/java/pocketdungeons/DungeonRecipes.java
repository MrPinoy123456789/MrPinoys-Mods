package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reloadable ordered dungeon recipes, loaded after dungeon themes. */
final class DungeonRecipes {

    private static volatile DungeonRecipes current = new DungeonRecipes(List.of(), List.of());

    private final List<DungeonRecipe> recipes;
    private final List<String> rejections;

    private DungeonRecipes(List<DungeonRecipe> recipes, List<String> rejections) {
        this.recipes = List.copyOf(recipes);
        this.rejections = List.copyOf(rejections);
    }

    static DungeonRecipes load(MinecraftServer server) {
        List<DungeonRecipe> recipes = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_recipe", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = baseName(resource.getKey());
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                DungeonRecipe recipe = DungeonRecipe.fromJson(id,
                        JsonParser.parseReader(reader).getAsJsonObject());
                validateThemes(recipe);
                recipes.add(recipe);
            } catch (Exception exception) {
                String reason = resource.getKey() + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon recipe '{}': {}", id, reason, exception);
            }
        }
        DungeonRecipes loaded = new DungeonRecipes(recipes, rejections);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon recipes ({} rejected)",
                recipes.size(), rejections.size());
        return loaded;
    }

    private static void validateThemes(DungeonRecipe recipe) {
        for (String theme : recipe.themes()) {
            if (ThemeManifest.current().byId(theme) == null) {
                throw new IllegalStateException("theme not found: " + theme);
            }
        }
        if (ThemeManifest.current().byId(recipe.result()) == null) {
            throw new IllegalStateException("theme not found: " + recipe.result());
        }
    }

    static DungeonRecipes current() {
        return current;
    }

    String match(List<String> recentThemes) {
        for (DungeonRecipe recipe : recipes) {
            if (RecipeMatcher.matches(recentThemes, recipe.themes())) {
                return recipe.result();
            }
        }
        return null;
    }

    List<String> rejections() {
        return rejections;
    }

    private static String baseName(Identifier location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.substring(0, name.length() - 5);
    }
}
