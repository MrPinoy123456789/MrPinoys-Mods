package pocketdungeons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M71: the namespaced id constants for every built-in Cube recipe, plus the
 * legacy bare-name to namespaced-id bridge that keeps a pre-M71 keystone tag
 * loading without a codec migration.
 *
 * <p>Before M71 a Cube recipe was a Java enum constant ({@link CubeRecipe})
 * and the on-keystone form was the enum's tag key ({@code "ominous"}). M71
 * replaces the enum with data-driven {@link CubeRecipeDefinition}s keyed by
 * namespaced id ({@code "pocketdungeons:ominous"}). A third-party recipe is
 * {@code "theirpack:their_recipe"} and flows through every site the
 * built-ins do, with no Java constant added.
 *
 * <p>The nine built-in recipes are the ones M59 shipped with a catalyst.
 * The two legacy tags ({@code bag_override}, {@code double_key}) have no
 * catalyst of their own: M66 already migrated them to {@code bounded_supply}
 * and {@code path_extension} respectively, and M71 carries that forward by
 * resolving a legacy bare tag to its replacement id.
 *
 * <p>This class is deliberately pure Java (no Minecraft imports), so the
 * headless tests can reach the constants without dragging the server
 * classpath in.
 */
final class RecipeIds {

    static final String OMINOUS = "pocketdungeons:ominous";
    static final String FERAL = "pocketdungeons:feral";
    static final String BOUNDED_SUPPLY = "pocketdungeons:bounded_supply";
    static final String INFESTED = "pocketdungeons:infested";
    static final String FLOODED = "pocketdungeons:flooded";
    static final String DEEP_DARK = "pocketdungeons:deep_dark";
    static final String COMPASS = "pocketdungeons:compass";
    static final String PATH_EXTENSION = "pocketdungeons:path_extension";
    static final String STORE = "pocketdungeons:store";

    /**
     * The built-in ids in the order the enum declared them, which is the
     * stable match and render order M71 preserves. A third-party definition
     * declares its own {@code priority} field; the match path checks by
     * priority then id, so this list is the priority-zero baseline.
     */
    static final List<String> BUILT_IN_ORDER = List.of(
            OMINOUS, FERAL, BOUNDED_SUPPLY, INFESTED, FLOODED, DEEP_DARK,
            COMPASS, PATH_EXTENSION, STORE);

    /**
     * Legacy bare tag key to namespaced id. {@code "ominous"} (a pre-M71
     * keystone tag) maps to {@code "pocketdungeons:ominous"}. The two M66
     * legacy tags map to their replacements: {@code bag_override} to
     * {@code bounded_supply}, {@code double_key} to {@code path_extension}.
     * An unknown bare name has no entry and is dropped by the caller, the
     * same login-safe lenience the enum parser already had.
     */
    private static final Map<String, String> LEGACY = buildLegacy();

    private static Map<String, String> buildLegacy() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ominous", OMINOUS);
        map.put("feral", FERAL);
        map.put("bounded_supply", BOUNDED_SUPPLY);
        map.put("bag_override", BOUNDED_SUPPLY);
        map.put("infested", INFESTED);
        map.put("flooded", FLOODED);
        map.put("deep_dark", DEEP_DARK);
        map.put("compass", COMPASS);
        map.put("path_extension", PATH_EXTENSION);
        map.put("double_key", PATH_EXTENSION);
        map.put("store", STORE);
        return map;
    }

    /**
     * Resolves a token read from a keystone tag into a namespaced id. A
     * token that already contains a colon is a qualified id and returned as
     * is. A legacy bare name maps to its built-in id (or its M66
     * replacement). {@code null} or blank returns {@code null}. An unknown
     * bare name returns {@code null} so the caller can drop it rather than
     * throw.
     */
    static String resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return LEGACY.get(trimmed.toLowerCase(Locale.ROOT));
    }

    private RecipeIds() {}
}
