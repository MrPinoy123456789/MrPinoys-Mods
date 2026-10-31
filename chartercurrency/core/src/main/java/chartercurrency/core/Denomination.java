package chartercurrency.core;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One physical coin of a currency: a base item, a face value in the currency's base
 * unit, and the words a player may type for it.
 *
 * <p>Identity of the real item is the custom_data marker, never the item or the name;
 * that rule lives in the fabric module. Here a denomination is only data.
 *
 * @param id       stable key used in the marker and in JSON, lowercase ({@code gold})
 * @param singular full name for item names and lore ({@code Gold Sovereign})
 * @param plural   plural of the full name ({@code Gold Sovereigns})
 * @param itemId   the base item ({@code minecraft:gold_nugget})
 * @param value    face value in the base unit (Marks)
 * @param aliases  everything a player might type, the id included
 */
public record Denomination(
        String id,
        String singular,
        String plural,
        String itemId,
        int value,
        Set<String> aliases) {

    public Denomination {
        id = id.toLowerCase(Locale.ROOT);
        Set<String> normalised = new LinkedHashSet<>();
        normalised.add(id);
        for (String alias : aliases) normalised.add(alias.toLowerCase(Locale.ROOT));
        aliases = Set.copyOf(normalised);
        if (value <= 0) throw new IllegalArgumentException("Face value must be positive: " + id);
    }

    public static Denomination of(String id, String singular, String plural, String itemId,
                                  int value, String... aliases) {
        return new Denomination(id, singular, plural, itemId, value, Set.of(aliases));
    }

    /** {@code Sovereign}: the last word of the full name, used in compact displays. */
    public String shortSingular() {
        return lastWord(singular);
    }

    /** {@code Sovereigns}. */
    public String shortPlural() {
        return lastWord(plural);
    }

    /** {@code 1 Sovereign} / {@code 3 Sovereigns}, in the compact form. */
    public String describe(long count) {
        return Wallet.format(count) + " " + (count == 1 ? shortSingular() : shortPlural());
    }

    private static String lastWord(String name) {
        int space = name.lastIndexOf(' ');
        return space < 0 ? name : name.substring(space + 1);
    }
}
