package cobbleeconomy.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One purchasable line in the server shop.
 *
 * <p><b>The price is a map on purpose.</b> Today every entry has exactly one currency
 * in it, and the spec says multi-currency purchases need not ship in the first
 * version. But the difference between {@code long price} plus {@code Currency} and a
 * {@code Map<Currency, Long>} is the difference between a later feature and a later
 * migration -- of the config file, the admin commands, the charge path, and the
 * rollback. The map costs nothing now and buys that outright.
 *
 * @param key        stable lookup key, what a player types after {@code /buy}
 * @param itemId     the item granted, e.g. {@code minecraft:ice}
 * @param quantity   how many are granted per purchase
 * @param price      currency to amount; every entry must be positive
 * @param category   heading it appears under in {@code /shop}
 * @param enabled    disabled entries are hidden and cannot be bought
 * @param components raw JSON for the item's data components, or null for a plain
 *                   registry item. Held as a string because this module is built
 *                   without Minecraft on the classpath and cannot name a
 *                   {@code DataComponentPatch}; the fabric module parses it. This is
 *                   what lets the shop sell an item that is a vanilla item plus a
 *                   marker -- a Spirit Stone is an echo shard with {@code custom_data}
 *                   on it, and naming {@code minecraft:echo_shard} alone would sell a
 *                   useless plain shard.
 */
public record ShopEntry(
        String key,
        String itemId,
        int quantity,
        Map<Currency, Long> price,
        String category,
        boolean enabled,
        String components) {

    public ShopEntry {
        // NOT Map.copyOf. That returns a map whose iteration order is randomised per
        // JVM run, so a two-currency price would render "500 cobblestone + 1 diamond"
        // on one restart and the reverse on the next -- and the config file would be
        // rewritten in a different order every time an admin touched the shop.
        // LinkedHashMap keeps the order the owner wrote in shop.json.
        price = Collections.unmodifiableMap(new LinkedHashMap<>(price));
    }

    /**
     * A plain entry with no component data, which is what every entry was before
     * components existed. Kept so the many existing call sites -- and the core tests --
     * did not all have to gain a trailing {@code null}.
     */
    public ShopEntry(String key, String itemId, int quantity, Map<Currency, Long> price,
                     String category, boolean enabled) {
        this(key, itemId, quantity, price, category, enabled, null);
    }

    /** A single-currency entry, which is what every real one is today. */
    public static ShopEntry of(String key, String itemId, int quantity,
                               Currency currency, long amount, String category) {
        Map<Currency, Long> price = new LinkedHashMap<>();
        price.put(currency, amount);
        return new ShopEntry(key, itemId, quantity, price, category, true, null);
    }

    /** True if this entry carries data components beyond the plain registry item. */
    public boolean hasComponents() {
        return components != null && !components.isBlank();
    }

    /** {@code 640 cobblestone} or {@code 500 cobblestone + 1 diamond}. */
    public String describePrice() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Currency, Long> line : price.entrySet()) {
            if (!out.isEmpty()) out.append(" + ");
            out.append(line.getKey().describe(line.getValue()));
        }
        return out.toString();
    }

    /** True if every price line is a positive amount in a real currency. */
    public boolean isValid() {
        if (key == null || key.isBlank() || itemId == null || itemId.isBlank()) return false;
        if (quantity <= 0 || price.isEmpty()) return false;
        for (Map.Entry<Currency, Long> line : price.entrySet()) {
            if (line.getKey() == null || line.getValue() == null || line.getValue() <= 0) return false;
        }
        return true;
    }
}
