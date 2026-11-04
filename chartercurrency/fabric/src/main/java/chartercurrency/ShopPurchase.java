package chartercurrency;

import chartercurrency.core.Analytics;
import chartercurrency.core.Currency;
import chartercurrency.core.EconomyService;
import chartercurrency.core.Shop;
import chartercurrency.core.ShopEntry;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
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
        if (SuiteItems.isSuiteItemId(entry.itemId())) {
            return SuiteItems.baseItem(SuiteItems.idFrom(entry.itemId()));
        }
        return ItemBank.resolve(entry.itemId());
    }

    static Shop.PurchaseResult attempt(EconomyService economy, TransactionLog log,
                                       ServerPlayer player, ShopEntry entry, boolean announce) {
        Optional<Item> maybeItem = itemOf(entry);
        if (maybeItem.isEmpty()) {
            // Startup already warned about this; say something useful rather than
            // charging the player for an item that cannot be created.
            if (announce) {
                player.sendSystemMessage(Messages.bad("That shop entry is misconfigured. Tell an admin."));
            }
            CharterCurrencyMod.LOG.error("Shop entry '{}' names unknown item {}",
                    entry.key(), entry.itemId());
            return new Shop.PurchaseResult(Shop.PurchaseStatus.UNAVAILABLE, entry, null);
        }
        Item item = maybeItem.get();

        long capacity = ItemBank.freeCapacity(player, item);
        Shop.PurchaseResult result = Shop.buy(economy, player.getUUID(), entry, capacity,
                (itemId, quantity) -> {
                    if (SuiteItems.isSuiteItemId(itemId)) {
                        // The suite loader already validated the definition and its
                        // components; delivery builds the same stamped stack the GUI icon
                        // shows.
                        return SuiteItems.deliver(player, SuiteItems.idFrom(itemId), quantity, player.registryAccess());
                    }
                    // Components, if the listing has any -- a Spirit Stone is an echo
                    // shard plus custom_data, and the plain item does nothing.
                    long dropped = ItemBank.give(player, item, quantity,
                            ItemComponents.parse(entry.components(), player.registryAccess())
                                    .orElse(null));
                    if (dropped > 0) {
                        // Capacity was checked, so this is unreachable. If it fires,
                        // the goods are on the floor rather than gone.
                        CharterCurrencyMod.LOG.warn("Purchase for {} overflowed capacity by {}",
                                player.getName().getString(), dropped);
                    }
                    return true;
                });

        if (result.ok()) {
            log.tx(player.getName().getString() + " purchased " + entry.quantity()
                    + " " + entry.key() + " for " + entry.describePrice(), purchaseEvent(player, entry));
            grantOnboarding(player);
        }

        if (announce) {
            announce(economy, player, entry, result);
        }
        return result;
    }

    /**
     * The analytics line for a purchase: the listing, how many items it delivers, and what
     * was paid in each currency. A single currency price is also the line's currency and
     * amount, so a coin purchase reads like every other coin line.
     */
    static Analytics.Event purchaseEvent(ServerPlayer player, ShopEntry entry) {
        java.util.Map<String, Long> price = new java.util.LinkedHashMap<>();
        entry.price().forEach((currency, amount) -> price.put(currency.id(), amount));
        Analytics.Event event = Analytics.Event.of(Analytics.SHOP_PURCHASE)
                .player(player.getUUID(), player.getName().getString());
        if (price.size() == 1) {
            var only = price.entrySet().iterator().next();
            event.currency(only.getKey()).amount(only.getValue());
        } else {
            event.currency("compound");
        }
        return event.put("item", entry.key()).put("itemId", entry.itemId())
                .put("quantity", entry.quantity()).put("price", price);
    }

    /** First purchase only -- {@code award} is a no-op once the criterion is already met. */
    private static void grantOnboarding(ServerPlayer player) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("chartercurrency", "first_purchase"));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "code_triggered");
        }
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
                player.sendSystemMessage(ShopDisplay.youHave(economy, player.getUUID(), entry, false));
            }
            case INSUFFICIENT_FUNDS -> {
                player.sendSystemMessage(Messages.bad("You cannot afford that. It costs "
                        + entry.describePrice() + "."));
                player.sendSystemMessage(ShopDisplay.youHave(economy, player.getUUID(), entry, true));
            }
            case INSUFFICIENT_SPACE -> {
                player.sendSystemMessage(Messages.bad("Your inventory does not have enough space."));
                player.sendSystemMessage(Messages.bad("Purchase cancelled."));
            }
            case DELIVERY_FAILED -> {
                player.sendSystemMessage(Messages.bad("Could not deliver that. You have not been charged."));
                CharterCurrencyMod.LOG.error("Delivery failed for {} buying {}",
                        player.getName().getString(), entry.key());
            }
            case UNAVAILABLE -> player.sendSystemMessage(Messages.bad("That item is not for sale."));
        }
    }
}
