package chartercurrency.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every currency the server knows about, and the alias table that turns what a
 * player typed into one of them (and, for coin, into one of its denominations).
 *
 * <p>Insertion order is preserved and is the display order everywhere. Coin is
 * registered first because it is the currency players deal with constantly.
 */
public final class CurrencyRegistry {

    /** The physical money: three denominations, one balance in Marks. */
    public static final Currency COIN = new Currency(
            "coin", "Coin", "Mark", "Marks", null, Set.of("coin", "coins"),
            true, true,
            List.of(
                    Denomination.of("copper", "Copper Mark", "Copper Marks",
                            "minecraft:copper_nugget", 1, "mark", "marks", "copper"),
                    Denomination.of("iron", "Iron Crown", "Iron Crowns",
                            "minecraft:iron_nugget", 8, "crown", "crowns", "iron"),
                    Denomination.of("gold", "Gold Sovereign", "Gold Sovereigns",
                            "minecraft:gold_nugget", 64, "sovereign", "sovereigns", "gold", "sov")));

    /** The earned currency: a balance with no item. */
    public static final Currency VALOR = new Currency(
            "valor", "Valor", "Valor", "Valor", null, Set.of("valor"),
            false, false, List.of());

    /**
     * What a typed word means: a currency, and a denomination when the word names a
     * coin ({@code crowns}) rather than the currency as a whole ({@code coin}).
     */
    public record Word(Currency currency, Denomination denomination) {}

    private final Map<String, Currency> byId = new LinkedHashMap<>();
    private final Map<String, Word> byAlias = new LinkedHashMap<>();

    /** The currencies the spec ships with. */
    public static CurrencyRegistry defaults() {
        CurrencyRegistry registry = new CurrencyRegistry();
        registry.register(COIN);
        registry.register(VALOR);
        return registry;
    }

    /**
     * Adds a currency and all of its aliases, including its denominations' aliases,
     * rejecting anything that would make command resolution ambiguous.
     *
     * @throws IllegalArgumentException if the id or any alias is already owned
     */
    public void register(Currency currency) {
        if (byId.containsKey(currency.id())) {
            throw new IllegalArgumentException("Duplicate currency id: " + currency.id());
        }
        Map<String, Word> pending = new LinkedHashMap<>();
        for (String alias : currency.aliases()) {
            claim(pending, alias, new Word(currency, null));
        }
        for (Denomination d : currency.denominations()) {
            for (String alias : d.aliases()) {
                claim(pending, alias, new Word(currency, d));
            }
        }
        byId.put(currency.id(), currency);
        byAlias.putAll(pending);
    }

    private void claim(Map<String, Word> pending, String alias, Word word) {
        Word clash = byAlias.get(alias);
        if (clash == null) clash = pending.get(alias);
        if (clash != null && !clash.equals(word)) {
            // Silently letting one word shadow another means /pay moving the wrong
            // money, so this is fatal at startup rather than mysterious at runtime.
            throw new IllegalArgumentException("Alias '" + alias + "' is claimed by both "
                    + describe(clash) + " and " + describe(word));
        }
        pending.put(alias, word);
    }

    private static String describe(Word w) {
        return w.denomination() == null ? w.currency().id()
                : w.currency().id() + "/" + w.denomination().id();
    }

    /** Resolve what a player typed to a currency. Case-insensitive, alias-aware. */
    public Optional<Currency> resolve(String input) {
        return resolveWord(input).map(Word::currency);
    }

    /** Resolve what a player typed, keeping the denomination if the word named one. */
    public Optional<Word> resolveWord(String input) {
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

    /** The physical-money currency: the first one that has denominations, or null. */
    public Currency coin() {
        for (Currency c : byId.values()) {
            if (c.hasDenominations()) return c;
        }
        return null;
    }

    /** Every currency id, for tab completion of a currency argument. */
    public List<String> suggestions() {
        return List.copyOf(byId.keySet());
    }

    /** Every word a player could type for a denomination or currency, for tab completion. */
    public List<String> denominationWords() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Currency c : byId.values()) {
            for (Denomination d : c.denominations()) {
                out.add(d.id());
                for (String alias : d.aliases()) out.add(alias);
            }
            out.addAll(c.aliases());
        }
        return List.copyOf(out);
    }
}
