package pocketdungeons;

import java.util.List;

/** Ordered tail matching for dungeon theme recipes. */
final class RecipeMatcher {

    private RecipeMatcher() {}

    static boolean matches(List<String> recentThemes, List<String> ingredients) {
        if (recentThemes == null || ingredients == null || recentThemes.size() < ingredients.size()) {
            return false;
        }
        int offset = recentThemes.size() - ingredients.size();
        for (int i = 0; i < ingredients.size(); i++) {
            if (!ingredients.get(i).equals(recentThemes.get(offset + i))) {
                return false;
            }
        }
        return true;
    }
}
