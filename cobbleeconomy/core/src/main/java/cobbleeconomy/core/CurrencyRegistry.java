package cobbleeconomy.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every currency the server knows about, and the alias table that turns what a
 * player typed into one of them.
 *
 * <p>Insertion order is preserved and is the display order everywhere -- balance
 * panels, leaderboards, {@code /bank all} output. Cobblestone is registered first
 * because it is the common currency and the one players deal with constantly.
 */
public final class CurrencyRegistry {

    /** The common currency. Abundant on purpose. */
    public static final Currency COBBLESTONE = Currency.of(
            "cobblestone", "Cobblestone", "cobblestone", "cobblestone",
            "minecraft:cobblestone", "cobble", "cobblestone", "stone");

    /** The premium currency. Scarce on purpose. */
    public static final Currency DIAMOND = Currency.of(
            "diamond", "Diamond", "diamond", "diamonds",
            "minecraft:diamond", "diamonds", "dia");

    private final Map<String, Currency> byId = new LinkedHashMap<>();
    private final Map<String, Currency> byAlias = new LinkedHashMap<>();

    /** The two currencies the spec ships with. */
    public static CurrencyRegistry defaults() {
        CurrencyRegistry registry = new CurrencyRegistry();
        registry.register(COBBLESTONE);
        registry.register(DIAMOND);
        return registry;
    }

    public void register(Currency currency) {
        if (byId.containsKey(currency.id())) {
            throw new IllegalArgumentException("Duplicate currency id: " + currency.id());
        }
        byId.put(currency.id(), currency);
        for (String alias : currency.aliases()) {
            Currency clash = byAlias.putIfAbsent(alias, currency);
            if (clash != null && !clash.equals(currency)) {
                // Silently letting one currency shadow another's alias means /pay
                // moving the wrong money, so this is fatal at startup rather than
                // mysterious at runtime.
                throw new IllegalArgumentException(
                        "Alias '" + alias + "' is claimed by both "
                                + clash.id() + " and " + currency.id());
            }
        }
    }

    /** Resolve what a player typed. Case-insensitive, alias-aware. */
    public Optional<Currency> resolve(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        return Optional.ofNullable(byAlias.get(input.trim().toLowerCase(Locale.ROOT)));
    }

    /** Look up by exact id. Used when reading config and stored balances. */
    public Optional<Currency> byId(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    /** All currencies, in registration order. */
    public Collection<Currency> all() {
        return List.copyOf(byId.values());
    }

    /** Every name a player could type, for tab completion. */
    public List<String> suggestions() {
        return List.copyOf(byId.keySet());
    }
}
