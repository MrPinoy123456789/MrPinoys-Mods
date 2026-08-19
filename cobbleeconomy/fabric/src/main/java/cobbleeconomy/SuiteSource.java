package cobbleeconomy;

import java.util.Locale;

/**
 * One {@code data/<namespace>/suite_items/_source.json}, describing the mod that
 * published a group of suite items.
 *
 * <p>Purely cosmetic -- it exists so the admin browser's source picker can say
 * "Spirit Wolves" with a bone icon instead of "spiritwolves" with a barrier. A mod
 * that ships no {@code _source.json} still appears in the browser; it just gets
 * {@link #fallback}.
 *
 * @param namespace   the mod id
 * @param name        display name, or null to use the prettified namespace
 * @param icon        a vanilla item id for the picker slot, or null
 * @param description one line for the picker lore, or null
 */
public record SuiteSource(String namespace, String name, String icon, String description) {

    /**
     * What a namespace with no {@code _source.json} gets. Never null, so no caller
     * needs a null check -- {@code SuiteItems.source} always returns something
     * renderable.
     */
    public static SuiteSource fallback(String namespace) {
        return new SuiteSource(namespace, prettify(namespace), null, null);
    }

    /** The name to render: the declared one, or the prettified namespace. */
    public String displayName() {
        return name == null || name.isBlank() ? prettify(namespace) : name;
    }

    /** {@code spiritwolves -> Spiritwolves}, {@code pocket_dungeons -> Pocket Dungeons}. */
    private static String prettify(String namespace) {
        if (namespace == null || namespace.isBlank()) return "Unknown";
        StringBuilder out = new StringBuilder();
        for (String word : namespace.split("[_\\-]")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.isEmpty() ? namespace : out.toString();
    }
}
