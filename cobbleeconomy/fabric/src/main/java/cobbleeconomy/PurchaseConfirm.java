package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.ShopEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a purchase is big enough to ask about first, and how big it actually is.
 *
 * <p>The rules live here rather than in either interface because {@code /buy} and the
 * sgui shop must agree on what counts as expensive. If they disagreed, the cheaper
 * answer would simply be the one everyone used, and the prompt would be decoration.
 *
 * <p>What is worth confirming is a config decision, not a calculation -- the same
 * reasoning that keeps prices out of Java. See
 * {@link EconomySettings.ShopSettings#confirmAt}.
 */
final class PurchaseConfirm {

    private PurchaseConfirm() {}

    /**
     * True if spending {@code lots} of this entry crosses a confirmation threshold.
     *
     * @param lots how many lots are about to be bought; 1 for a single click or
     *             {@code /buy}, the planned count for a shift-click bulk buy
     */
    static boolean required(ShopEntry entry, int lots, EconomySettings settings) {
        if (entry == null || lots <= 0) return false;
        for (Map.Entry<Currency, Long> line : entry.price().entrySet()) {
            long threshold = settings.shop.confirmAt(line.getKey().id());
            if (threshold <= 0) continue;
            long total;
            try {
                total = Math.multiplyExact(line.getValue(), (long) lots);
            } catch (ArithmeticException overflow) {
                // A price that cannot be multiplied out is not a price anyone meant to
                // pay by accident. Ask.
                return true;
            }
            if (total >= threshold) return true;
        }
        return false;
    }

    /** The whole cost of {@code lots} lots, currency by currency. */
    static Map<Currency, Long> total(ShopEntry entry, int lots) {
        Map<Currency, Long> out = new LinkedHashMap<>();
        for (Map.Entry<Currency, Long> line : entry.price().entrySet()) {
            out.put(line.getKey(), line.getValue() * lots);
        }
        return out;
    }

    /**
     * How many lots a shift-click would actually manage: whichever runs out first of
     * money, inventory space, or the caller's own cap.
     *
     * <p>This is an estimate taken before anything is charged, purely so the prompt can
     * name a real total instead of "as many as you can". The bulk buy itself still stops
     * on the first genuine failure, so being wrong here costs nothing -- it is the same
     * balances and the same inventory, one tick apart, on the server thread.
     */
    static int plannedLots(EconomyService economy, ServerPlayer player, ShopEntry entry, int cap) {
        Optional<Item> item = ShopPurchase.itemOf(entry);
        if (item.isEmpty() || entry.quantity() <= 0) return 0;

        long lots = cap;
        for (Map.Entry<Currency, Long> line : entry.price().entrySet()) {
            long price = line.getValue();
            if (price <= 0) continue;
            lots = Math.min(lots, economy.getBalance(player.getUUID(), line.getKey()) / price);
        }
        lots = Math.min(lots, ItemBank.freeCapacity(player, item.get()) / entry.quantity());
        return (int) Math.max(0, lots);
    }
}
