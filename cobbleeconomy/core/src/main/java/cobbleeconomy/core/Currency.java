package cobbleeconomy.core;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One kind of money, backed by one physical Minecraft item.
 *
 * <p>Cobblestone and Diamond are the first two, but nothing in the economy knows
 * that. Adding a third is a registry entry, not a code change -- which is the entire
 * reason this type exists rather than an enum.
 *
 * <p><b>No exchange rate.</b> Currencies deliberately carry no notion of relative
 * worth. There is no field here for "value", and there is no method anywhere that
 * converts one currency into another. The moment such a thing exists, the server
 * owner has lost control of pricing, because players will arbitrage against it. What
 * a diamond is worth is decided by shop prices and by players, and by nothing else.
 *
 * @param id           stable key used in JSON and commands, lowercase
 * @param displayName  title-case name for headings, e.g. {@code Cobblestone}
 * @param singular     e.g. {@code diamond}
 * @param plural       e.g. {@code diamonds}
 * @param itemId       the physical item, e.g. {@code minecraft:cobblestone}
 * @param aliases      everything a player might type, including the id itself
 */
public record Currency(
        String id,
        String displayName,
        String singular,
        String plural,
        String itemId,
        Set<String> aliases) {

    public Currency {
        id = id.toLowerCase(Locale.ROOT);
        Set<String> normalised = new LinkedHashSet<>();
        normalised.add(id);
        for (String alias : aliases) normalised.add(alias.toLowerCase(Locale.ROOT));
        aliases = Set.copyOf(normalised);
    }

    /** {@code 1 diamond} / {@code 37 diamonds}. */
    public String describe(long amount) {
        return Wallet.format(amount) + " " + (amount == 1 ? singular : plural);
    }

    public static Currency of(String id, String displayName, String singular, String plural,
                              String itemId, String... aliases) {
        return new Currency(id, displayName, singular, plural, itemId, Set.of(aliases));
    }
}
