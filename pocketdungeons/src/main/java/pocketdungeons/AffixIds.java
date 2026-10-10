package pocketdungeons;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M69: the namespaced id constants for every built-in affix, plus the legacy
 * bare-name to namespaced-id bridge that keeps a pre-M69 save or keystone item
 * tag loading without a codec migration.
 *
 * <p>Before M69 an affix was a Java enum constant ({@code Affix.OMINOUS}) and
 * the on-disk form was the enum's lowercased name ({@code "ominous"}). M69
 * replaces the enum with data-driven {@link AffixDefinition}s keyed by
 * namespaced id ({@code "pocketdungeons:ominous"}). A third-party affix is
 * {@code "theirpack:their_affix"} and flows through every site the built-ins
 * do, with no Java constant added.
 *
 * <p>This class is deliberately pure Java (no Minecraft imports), so
 * {@link AffixMath} and its plain-{@code javac} test can reach the constants
 * without dragging the server classpath in.
 */
final class AffixIds {

    static final String OMINOUS = "pocketdungeons:ominous";
    static final String FERAL = "pocketdungeons:feral";
    static final String SWARMING = "pocketdungeons:swarming";
    static final String OVERCLOCKED = "pocketdungeons:overclocked";
    static final String MOLTEN = "pocketdungeons:molten";
    static final String SILENCED = "pocketdungeons:silenced";
    static final String EXPLOSIVE = "pocketdungeons:explosive";
    static final String VOIDED = "pocketdungeons:voided";
    static final String LOADED = "pocketdungeons:loaded";
    static final String RESTLESS = "pocketdungeons:restless";

    /**
     * The built-in ids in the order the enum declared them, which is the
     * stable render and seed order M69 preserves. A third-party definition
     * declares its own {@code order} field; the manifest sorts by order then
     * id, so this list is the order-zero baseline.
     */
    static final java.util.List<String> BUILT_IN_ORDER = java.util.List.of(
            OMINOUS, FERAL, SWARMING, OVERCLOCKED, MOLTEN, SILENCED, EXPLOSIVE, VOIDED, LOADED, RESTLESS);

    /**
     * Legacy bare enum name to namespaced id. {@code "ominous"} (a pre-M69
     * save) maps to {@code "pocketdungeons:ominous"}. An unknown bare name
     * has no entry and is dropped by {@link AffixMath#parse}, the same
     * login-safe lenience the enum parser already had.
     */
    private static final Map<String, String> LEGACY = buildLegacy();

    private static Map<String, String> buildLegacy() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ominous", OMINOUS);
        map.put("feral", FERAL);
        map.put("swarming", SWARMING);
        map.put("overclocked", OVERCLOCKED);
        map.put("molten", MOLTEN);
        map.put("silenced", SILENCED);
        map.put("explosive", EXPLOSIVE);
        map.put("voided", VOIDED);
        map.put("loaded", LOADED);
        map.put("restless", RESTLESS);
        return map;
    }

    /**
     * Resolves a token read from a save or keystone tag into a namespaced id.
     * A token that already contains a colon is a qualified id and returned as
     * is. A legacy bare name maps to its built-in id. {@code null} or blank
     * returns {@code null}. An unknown bare name returns {@code null} so the
     * caller can drop it rather than throw.
     */
    static String resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return LEGACY.get(trimmed.toLowerCase(java.util.Locale.ROOT));
    }

    private AffixIds() {}
}
