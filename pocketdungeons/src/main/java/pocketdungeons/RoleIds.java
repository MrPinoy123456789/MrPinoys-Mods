package pocketdungeons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M70: the namespaced id constants for every built-in role, plus the legacy
 * bare-name to namespaced-id bridge that keeps a pre-M70 save, plan or room
 * file loading without a codec migration.
 *
 * <p>Before M70 a role was a hard-coded string the generator's own
 * {@code switch} understood ({@code "encounter"}, {@code "loot"},
 * {@code "corridor"}). M70 replaces the switch with data-driven
 * {@link RoomRoleDefinition}s keyed by namespaced id
 * ({@code "pocketdungeons:encounter"}). A third-party role is
 * {@code "theirpack:their_role"} and flows through every site the built-ins
 * do, with no Java edit.
 *
 * <p>Structural roles ({@link #ENTRANCE}, {@link #EXIT}) are engine-owned
 * and are not loaded from JSON. They are declared here so the selector can
 * qualify bare references to them the same way it qualifies population
 * role references. A pack cannot add a structural role; naming one in a
 * {@code dungeon_role} file is rejected at load.
 *
 * <p>This class is deliberately pure Java (no Minecraft imports), so
 * {@link LayoutGraphGenerator} and its plain-{@code javac} test can reach
 * the constants without dragging the server classpath in.
 */
final class RoleIds {

    // Structural roles: engine-owned, not loaded from JSON.
    static final String ENTRANCE = "pocketdungeons:entrance";
    static final String EXIT = "pocketdungeons:exit";

    // Population roles: data-driven, loaded from dungeon_role/*.json.
    static final String ENCOUNTER = "pocketdungeons:encounter";
    static final String LOOT = "pocketdungeons:loot";
    static final String CORRIDOR = "pocketdungeons:corridor";

    /**
     * The built-in population role ids in the order the generator assigned
     * them before M70, which is the stable assignment order M70 preserves.
     * A third-party definition declares its own {@code weight} field; the
     * generator assigns by weight, so this list is the weight-zero baseline.
     */
    static final List<String> BUILT_IN_POPULATION_ORDER = List.of(
            ENCOUNTER, LOOT, CORRIDOR);

    /**
     * Legacy bare name to namespaced id. {@code "encounter"} (a pre-M70
     * plan or room file) maps to {@code "pocketdungeons:encounter"}. An
     * unknown bare name has no entry and is returned as-is by
     * {@link #resolve} for the caller to test against the loaded set.
     */
    private static final Map<String, String> LEGACY = buildLegacy();

    private static Map<String, String> buildLegacy() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("entrance", ENTRANCE);
        map.put("exit", EXIT);
        map.put("encounter", ENCOUNTER);
        map.put("loot", LOOT);
        map.put("corridor", CORRIDOR);
        return map;
    }

    /**
     * Resolves a token read from a plan, room file or save into a
     * namespaced id. A token that already contains a colon is a qualified id
     * and returned as is. A legacy bare name maps to its built-in id.
     * {@code null} or blank returns {@code null}. An unknown bare name
     * returns {@code null} so the caller can reject it rather than silently
     * fall through to a generic room.
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

    private RoleIds() {}
}
