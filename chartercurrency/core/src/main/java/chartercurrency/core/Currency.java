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
     * {@code 24 Valor} for a plain currency. For coin, the denominations are spelled
     * out with the base-unit total always shown: {@code 3 Sovereigns, 2 Crowns,
     * 5 Marks (213 Marks)}. Zero parts are omitted. A balance that is only Marks is not
     * repeated in brackets.
     */
    public String describe(long amount) {
        String total = Wallet.format(amount) + " " + (amount == 1 ? singular : plural);
        if (!hasDenominations() || amount <= 0) return total;

        StringBuilder out = new StringBuilder();
        long remaining = amount;
        int parts = 0;
        boolean onlyBase = true;
        for (Denomination d : largestFirst()) {
            long count = remaining / d.value();
            remaining -= count * d.value();
            if (count <= 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(d.describe(count));
            parts++;
            if (d.value() != 1) onlyBase = false;
        }
        if (remaining > 0) {
            // Only reachable when a config has no face value 1; keep the sum honest.
            if (out.length() > 0) out.append(", ");
            out.append(Wallet.format(remaining)).append(' ').append(remaining == 1 ? singular : plural);
            parts++;
            onlyBase = false;
        }
        if (parts == 0 || (parts == 1 && onlyBase)) return total;
        return out + " (" + total + ")";
    }
}
