package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Plain ordered dungeon recipe data with no Minecraft imports. */
record DungeonRecipe(String id, List<String> themes, String result) {

    DungeonRecipe {
        themes = List.copyOf(themes);
        if (themes.isEmpty()) {
            throw new IllegalArgumentException("themes array must not be empty");
        }
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("missing required field: result");
        }
    }

    static DungeonRecipe fromJson(String id, JsonObject obj) {
        JsonElement element = obj.get("themes");
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("themes must be a JSON array of strings");
        }
        JsonArray array = element.getAsJsonArray();
        List<String> themes = new ArrayList<>(array.size());
        for (JsonElement theme : array) {
            String value = theme.getAsString().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("themes must not contain blank ids");
            }
            themes.add(value);
        }
        JsonElement result = obj.get("result");
        return new DungeonRecipe(id, themes,
                result == null || result.isJsonNull() ? null : result.getAsString().trim());
    }
}
