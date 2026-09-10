package pocketdungeons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M70: the namespaced id constants for every built-in bag, plus the legacy
 * bare-name to namespaced-id bridge that keeps a pre-M70 save or dialog
 * payload loading without a codec migration.
 *
 * <p>Before M70 a bag was a Java enum constant ({@code Bags.MASON}) and the
 * on-disk form was the enum's lowercased name ({@code "mason"}). M70 replaces
 * the enum with data-driven {@link BagDefinition}s keyed by namespaced id
 * ({@code "pocketdungeons:mason"}). A third-party bag is
 * {@code "theirpack:their_bag"} and flows through every site the built-ins
 * do, with no Java constant added.
 *
 * <p>This class is deliberately pure Java (no Minecraft imports), so the
 * headless tests can reach the constants without dragging the server
 * classpath in.
 */
final class BagIds {

    static final String MASON = "pocketdungeons:mason";
    static final String PLUMBER = "pocketdungeons:plumber";
    static final String SAPPER = "pocketdungeons:sapper";
    static final String MAGICIAN = "pocketdungeons:magician";
    static final String RANGER = "pocketdungeons:ranger";
    static final String SHEPHERD = "pocketdungeons:shepherd";
    static final String INNKEEPER = "pocketdungeons:innkeeper";
    static final String PILGRIM = "pocketdungeons:pilgrim";

    /**
     * The built-in ids in the order the enum declared them, which is the
     * stable picker order M70 preserves. A third-party definition declares
     * its own {@code order} field; the manifest sorts by order then id, so
     * this list is the order-zero baseline.
     */
    static final List<String> BUILT_IN_ORDER = List.of(
            MASON, PLUMBER, SAPPER, MAGICIAN, RANGER, SHEPHERD, INNKEEPER, PILGRIM);

    /**
     * Legacy bare enum name to namespaced id. {@code "mason"} (a pre-M70
     * save) maps to {@code "pocketdungeons:mason"}. An unknown bare name
     * has no entry and is dropped by the caller, the same login-safe
     * lenience the enum parser already had.
     */
    private static final Map<String, String> LEGACY = buildLegacy();

    private static Map<String, String> buildLegacy() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("mason", MASON);
        map.put("plumber", PLUMBER);
        map.put("sapper", SAPPER);
        map.put("magician", MAGICIAN);
        map.put("ranger", RANGER);
        map.put("shepherd", SHEPHERD);
        map.put("innkeeper", INNKEEPER);
        map.put("pilgrim", PILGRIM);
        return map;
    }

    /**
     * Resolves a token read from a save or dialog payload into a namespaced
     * id. A token that already contains a colon is a qualified id and
     * returned as is. A legacy bare name maps to its built-in id.
     * {@code null} or blank returns {@code null}. An unknown bare name
     * returns {@code null} so the caller can drop it rather than throw.
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

    private BagIds() {}
}
