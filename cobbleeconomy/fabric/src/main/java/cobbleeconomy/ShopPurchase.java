package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.Shop;
import cobbleeconomy.core.ShopEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.Optional;

/**
 * The one purchase attempt, shared by {@code /buy} and the sgui shop screens.
 *
 * <p>Resolving the item (vanilla vs. wondrous), checking capacity, calling
 * {@link Shop#buy}, delivering the goods and writing the transaction log all happen
 * here, once. Only the chat feedback wording lives with each caller through the
 * {@code announce} flag -- the chat command always wants it, the GUI wants it on a
 * single click but not on every step of a bulk buy.
 */
final class ShopPurchase {

    private ShopPurchase() {}

    /**
     * The item an entry hands over, wondrous listings included. Package-private so
     * {@link PurchaseConfirm} can size a bulk buy against the same item this class
     * would deliver, rather than reimplementing the wondrous fork and drifting from it.
     */
    static Optional<Item> itemOf(ShopEntry entry) {
        return WondrousShop.isWondrousItemId(entry.itemId())
                ? WondrousShop.baseItem(WondrousShop.idFrom(entry.itemId()))
                : ItemBank.resolve(entry.itemId());
    }

    static Shop.PurchaseResult attempt(EconomyService economy, TransactionLog log,
                                       ServerPlayer player, ShopEntry entry, boolean announce) {
        boolean wondrous = WondrousShop.isWondrousItemId(entry.itemId());

        Optional<Item> maybeItem = itemOf(entry);
        if (maybeItem.isEmpty()) {
            // Startup already warned about this; say something useful rather than
            // charging the player for an item that cannot be created.
            if (announce) {
                player.sendSystemMessage(Messages.bad("That shop entry is misconfigured. Tell an admin."));
            }
            CobbleEconomyMod.LOG.error("Shop entry '{}' names unknown item {}",
                    entry.key(), entry.itemId());
            return new Shop.PurchaseResult(Shop.PurchaseStatus.UNAVAILABLE, entry, null);
        }
        Item item = maybeItem.get();

        long capacity = ItemBank.freeCapacity(player, item);
        Shop.PurchaseResult result = Shop.buy(economy, player.getUUID(), entry, capacity,
                (itemId, quantity) -> {
                    if (wondrous) {
                        // A fully tagged stack, not the plain item ItemBank.give would
                        // create -- the tag is the entire trick that makes it wondrous.
                        return WondrousShop.deliver(player, WondrousShop.idFrom(itemId), quantity);
                    }
                    // Components, if the listing has any -- a Spirit Stone is an echo
                    // shard plus custom_data, and the plain item does nothing.
                    long dropped = ItemBank.give(player, item, quantity,
                            ItemComponents.parse(entry.components(), player.registryAccess())
                                    .orElse(null));
                    if (dropped > 0) {
                        // Capacity was checked, so this is unreachable. If it fires,
                        // the goods are on the floor rather than gone.
                        CobbleEconomyMod.LOG.warn("Purchase for {} overflowed capacity by {}",
                                player.getName().getString(), dropped);
                    }
                    return true;
                });

        if (result.ok()) {
            log.tx(player.getName().getString() + " purchased " + entry.quantity()
                    + " " + entry.key() + " for " + entry.describePrice());
        }

        if (announce) {
            announce(economy, player, entry, result);
        }
        return result;
    }

    private static void announce(EconomyService economy, ServerPlayer player, ShopEntry entry,
                                  Shop.PurchaseResult result) {
        switch (result.status()) {
            case OK -> {
                Chime.purchased(player);
                player.sendSystemMessage(Messages.good("Purchased ")
                        .append(Messages.body(entry.quantity() + " " + ShopDisplay.displayName(entry)))
                        .append(Messages.good(" for "))
                        .append(ShopDisplay.priceComponent(entry))
                        .append(Messages.good(".")));
                for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
                    long balance = economy.getBalance(player.getUUID(), price.getKey());
                    player.sendSystemMessage(Messages.balanceLine(price.getKey(), balance));
                }
            }
            case INSUFFICIENT_FUNDS -> {
                Currency short_ = result.charge().currency();
                player.sendSystemMessage(Messages.bad("You cannot afford that. It costs "
                        + entry.describePrice() + "."));
                player.sendSystemMessage(Messages.body("You have "
                        + short_.describe(economy.getBalance(player.getUUID(), short_)) + "."));
            }
            case INSUFFICIENT_SPACE -> {
                player.sendSystemMessage(Messages.bad("Your inventory does not have enough space."));
                player.sendSystemMessage(Messages.bad("Purchase cancelled."));
            }
            case DELIVERY_FAILED -> {
                player.sendSystemMessage(Messages.bad("Could not deliver that. You have not been charged."));
                CobbleEconomyMod.LOG.error("Delivery failed for {} buying {}",
                        player.getName().getString(), entry.key());
            }
            case UNAVAILABLE -> player.sendSystemMessage(Messages.bad("That item is not for sale."));
        }
    }
}
