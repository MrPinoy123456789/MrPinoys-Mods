package foundry.core;

import java.util.List;
import java.util.Map;

/**
 * A recipe definition, version 1 of the recipe schema. Fields mirror schema/recipe.v1.schema.json; keep the two
 * in step. Ingredients and keys are item ids, or {@code #tag} entries that validation only shape-checks.
 */
public record RecipeDef(
        int schemaVersion,
        Ident id,
        String type,
        List<String> pattern,
        Map<String, String> key,
        List<String> ingredients,
        Ident result,
        int count,
        String group) {

    public static final int SCHEMA_VERSION = 1;
    public static final List<String> TYPES = List.of("shaped", "shapeless");
    public static final int MAX_GRID = 3;
    public static final int MAX_INGREDIENTS = 9;

    public boolean shaped() {
        return type.equals("shaped");
    }
}
