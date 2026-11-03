package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.EconomyService;
import chartercurrency.core.ShopEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;
import java.util.UUID;

/**
 * Rendering rules for a shop entry, shared by the chat listing ({@link ShopCommands})
 * and the sgui screens ({@link ShopMenu}). Kept in one place so the two interfaces
 * cannot drift into describing the same entry differently.
 */
final class ShopDisplay {

    private ShopDisplay() {}

    /**
     * The name shown in {@code /shop} and the purchase message. A wondrous entry uses
     * the item's own display name (e.g. "Big Hole Shovel") rather than the base
     * item's -- {@link #prettyName} on {@code "wondrous:big_hole_shovel"} would read
     * "Big Hole Shovel" too by coincidence of naming, but falls back to the plain
     * item's name if the mod is absent at the moment this renders.
     */
    static String displayName(ShopEntry entry) {
        if (SuiteItems.isSuiteItemId(entry.itemId())) {
            return SuiteItems.displayName(SuiteItems.idFrom(entry.itemId()))
                    .map(Component::getString)
                    .orElseGet(() -> prettyName(SuiteItems.idFrom(entry.itemId())));
        }
        // A component-marked listing names itself. Without this, a Spirit Stone would
        // list in /shop as "Echo Shard", which is the base item and not what is sold.
        return ItemComponents.itemName(entry.components())
                .orElseGet(() -> prettyName(entry.itemId()));
    }

    /** {@code minecraft:packed_ice} to {@code Packed Ice}, for display only. */
    static String prettyName(String itemId) {
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String[] words = path.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** {@code 8 Sovereigns} or {@code 2 Sovereigns + 24 Valor}, coloured, in coins a player holds. */
    static Component priceComponent(ShopEntry entry) {
        return priceComponent(entry, 1);
    }

    /** The price of {@code lots} purchases, worded as {@link #priceComponent(ShopEntry)}. */
    static Component priceComponent(ShopEntry entry, int lots) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            if (!first) out.append(Messages.body(" + "));
            out.append(coins(price.getKey(), price.getValue() * lots));
            first = false;
        }
        return out;
    }

    /** {@code 3 Sovereigns, 2 Crowns}: an amount as the coins themselves, never a Marks total. */
    static MutableComponent coins(Currency currency, long value) {
        return Component.literal(currency.inCoins(value)).withStyle(Messages.colourOf(currency));
    }

    /**
     * {@code You have: 3 Sovereigns} or {@code You have: 3 Sovereigns + 12 Valor}: the
     * buyer's own balance in each currency the price asks for. Labelled so it cannot be
     * read as a second price. With {@code markShort}, a currency the buyer lacks enough
     * of is followed by a red {@code (not enough)}.
     */
    static Component youHave(EconomyService economy, UUID player, ShopEntry entry, boolean markShort) {
        MutableComponent out = Messages.body("You have: ");
        boolean first = true;
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            if (!first) out.append(Messages.body(" + "));
            long balance = economy.getBalance(player, price.getKey());
            out.append(coins(price.getKey(), balance));
            if (markShort && balance < price.getValue()) {
                out.append(Component.literal(" (not enough)").withStyle(ChatFormatting.RED));
            }
            first = false;
        }
        return out;
    }
}
