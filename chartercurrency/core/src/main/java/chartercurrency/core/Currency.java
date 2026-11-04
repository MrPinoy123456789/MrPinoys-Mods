package chartercurrency.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One kind of money. Coin has physical denominations; Valor has no item at all.
 *
 * <p><b>No exchange rate.</b> Currencies deliberately carry no notion of relative
 * worth. There is no field here for a rate between currencies, and there is no method
 * anywhere that converts one currency into another. The fixed ratios between a
 * currency's own denominations (a Sovereign is 64 Marks) are packaging of one
 * currency, not an exchange rate between two.
 *
 * @param id            stable key used in JSON and commands, lowercase
 * @param displayName   title-case name for headings, e.g. {@code Coin}
 * @param singular      the base unit, e.g. {@code Mark}
 * @param plural        e.g. {@code Marks}
 * @param itemId        a single backing item, or null when the currency has none or
 *                      is carried as denominations
 * @param aliases       everything a player might type, including the id itself
 * @param withdrawable  whether /withdraw may turn a balance into items
 * @param payable       whether /pay may move a balance between players
 * @param denominations physical coins in any order; empty when the currency is a
 *                      plain number
 */
public record Currency(
        String id,
        String displayName,
        String singular,
        String plural,
        String itemId,
        Set<String> aliases,
        boolean withdrawable,
        boolean payable,
        List<Denomination> denominations) {

    public Currency {
        id = id.toLowerCase(Locale.ROOT);
        Set<String> normalised = new LinkedHashSet<>();
        normalised.add(id);
        for (String alias : aliases) normalised.add(alias.toLowerCase(Locale.ROOT));
        aliases = Set.copyOf(normalised);
        denominations = List.copyOf(denominations);
    }

    /** A single-item currency, withdrawable and payable. Kept for simple callers. */
    public static Currency of(String id, String displayName, String singular, String plural,
                              String itemId, String... aliases) {
        return new Currency(id, displayName, singular, plural, itemId, Set.of(aliases),
                true, true, List.of());
    }

    /** True if this currency is carried as marked coins. */
    public boolean hasDenominations() {
        return !denominations.isEmpty();
    }

    /** Denominations ordered largest face value first, for greedy change. */
    public List<Denomination> largestFirst() {
        List<Denomination> out = new ArrayList<>(denominations);
        out.sort(Comparator.comparingInt(Denomination::value).reversed());
        return out;
    }

    /** Face values in the order of {@link #denominations()}. */
    public int[] faceValues() {
        int[] out = new int[denominations.size()];
        for (int i = 0; i < out.length; i++) out[i] = denominations.get(i).value();
        return out;
    }

    /**
     * {@code 24 Valor} for a plain currency. Coin is always told in its largest coin, the
     * Gold Sovereign (owner, 2026-11-04): {@code 2,261.5 Sovereigns}, {@code 1 Sovereign},
     * {@code 0 Sovereigns}. Smaller coin is never named in an amount; a remainder is a
     * decimal Sovereign ({@link Wallet#sovereigns}).
     */
    public String describe(long amount) {
        if (!hasDenominations()) return Wallet.format(amount) + " " + (amount == 1 ? singular : plural);
        Denomination top = largestFirst().get(0);
        return Wallet.inUnits(amount, top.value()) + " "
                + (amount == top.value() ? top.shortSingular() : top.shortPlural());
    }

    /**
     * How the shop and the Bank name a price or a balance. Since every coin amount is told
     * in Sovereigns, this is {@link #describe}; it is kept as its own name for the call
     * sites that mean "as coins".
     */
    public String inCoins(long amount) {
        return describe(amount);
    }
}
