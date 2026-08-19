package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.ShopEntry;
import net.minecraft.network.chat.Component;

import java.util.Map;

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
        if (WondrousShop.isWondrousItemId(entry.itemId())) {
            return WondrousShop.displayName(WondrousShop.idFrom(entry.itemId()))
                    .map(Component::getString)
                    .orElseGet(() -> prettyName(WondrousShop.idFrom(entry.itemId())));
        }
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

    /** {@code 640 cobblestone} or {@code 500 cobblestone + 1 diamond}, coloured. */
    static Component priceComponent(ShopEntry entry) {
        Component out = Component.empty();
        boolean first = true;
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            if (!first) out = Component.empty().append(out).append(Messages.body(" + "));
            out = Component.empty().append(out)
                    .append(Messages.priced(price.getKey(), price.getValue()));
            first = false;
        }
        return out;
    }
}
