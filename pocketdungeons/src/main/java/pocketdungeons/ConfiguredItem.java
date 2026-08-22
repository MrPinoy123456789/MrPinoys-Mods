package pocketdungeons;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/**
 * A config field that names an item, resolved lazily and cached against the
 * string it came from.
 *
 * <p>{@link PocketDungeonsConfig} deliberately keeps item ids as strings and
 * carries no Minecraft imports, so resolution has to happen somewhere at the use
 * site. Doing it per use would re-parse an identifier on every right-click and
 * log a bad id once per click; doing it once at load would bet on registry
 * availability. This is the middle: resolve on first ask, log once, and re-resolve
 * only if the configured string actually changes.
 */
final class ConfiguredItem {

    private final String field;
    private final Supplier<String> configured;
    private final String consequence;

    private String resolvedFrom;
    private Item resolved;
    private boolean resolvedOnce;

    /**
     * @param consequence what an operator loses if this id does not resolve,
     *                    appended to the error so the log line is actionable
     */
    ConfiguredItem(String field, Supplier<String> configured, String consequence) {
        this.field = field;
        this.configured = configured;
        this.consequence = consequence;
    }

    /** The resolved item, or null if the configured id is not a known item. */
    Item get() {
        String id = configured.get();
        if (resolvedOnce && id.equals(resolvedFrom)) {
            return resolved;
        }
        resolvedFrom = id;
        resolvedOnce = true;
        Identifier parsed = Identifier.tryParse(id);
        // BuiltInRegistries.ITEM is a DefaultedRegistry: getValue answers with air
        // for anything missing, so an unknown id has to be rejected by the lookup
        // itself rather than by inspecting what came back.
        resolved = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
        if (resolved == null) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' names '{}', which is not a "
                    + "known item; {}", field, id, consequence);
        }
        return resolved;
    }
}
