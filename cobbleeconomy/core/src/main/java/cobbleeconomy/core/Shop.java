package cobbleeconomy.core;

import java.util.UUID;

/**
 * The purchase transaction, section 18, with the inventory abstracted to two things:
 * a number saying how much room there is, and a callback that hands over the goods.
 *
 * <p>That abstraction is what lets the rollback path be tested. The dangerous case in
 * a shop is not the happy path, it is the purchase that charges a player and then
 * fails to deliver -- and that case is almost impossible to provoke deliberately
 * inside a running server. Here it is a callback that returns false.
 *
 * <p>The ordering is the spec's, and the reason for it is that money is recoverable
 * and items are not: the charge happens last of the two irreversible steps, after
 * space has already been proven, and if delivery still fails the money goes back.
 */
public final class Shop {

    private Shop() {}

    /** Hands the goods over. Returns false if the items could not be given. */
    @FunctionalInterface
    public interface Delivery {
        boolean give(String itemId, int quantity);
    }

    public enum PurchaseStatus {
        OK,
        /** The entry is disabled, malformed, or does not exist. */
        UNAVAILABLE,
        /** The player cannot afford at least one line of the price. */
        INSUFFICIENT_FUNDS,
        /** The purchase would not fit in the player's inventory. */
        INSUFFICIENT_SPACE,
        /** Charged, delivery failed, money returned. Should never happen. */
        DELIVERY_FAILED
    }

    /**
     * @param status  what happened
     * @param entry   the entry attempted
     * @param charge  the result of the debit, or null if it never got that far
     */
    public record PurchaseResult(PurchaseStatus status, ShopEntry entry, TxResult charge) {
        public boolean ok() {
            return status == PurchaseStatus.OK;
        }
    }

    /**
     * Buy one lot of {@code entry}.
     *
     * @param freeCapacity how many of the item the player's inventory can accept
     */
    public static PurchaseResult buy(EconomyService economy, UUID player, ShopEntry entry,
                                     long freeCapacity, Delivery delivery) {
        if (entry == null || !entry.enabled() || !entry.isValid()) {
            return new PurchaseResult(PurchaseStatus.UNAVAILABLE, entry, null);
        }

        // Space before money. Checking the other way round means a player with a full
        // inventory watches their balance drop and bounce back, which looks exactly
        // like a bug even when the refund is perfect.
        if (freeCapacity < entry.quantity()) {
            return new PurchaseResult(PurchaseStatus.INSUFFICIENT_SPACE, entry, null);
        }

        TxResult charge = economy.charge(player, entry.price());
        if (!charge.ok()) {
            PurchaseStatus status = charge.status() == TxStatus.INSUFFICIENT_FUNDS
                    ? PurchaseStatus.INSUFFICIENT_FUNDS
                    : PurchaseStatus.UNAVAILABLE;
            return new PurchaseResult(status, entry, charge);
        }

        boolean delivered;
        try {
            delivered = delivery.give(entry.itemId(), entry.quantity());
        } catch (RuntimeException e) {
            // A throwing delivery is still a failed delivery. Letting this propagate
            // would leave the player charged with nothing to show for it.
            economy.refund(player, entry.price());
            throw e;
        }

        if (!delivered) {
            economy.refund(player, entry.price());
            return new PurchaseResult(PurchaseStatus.DELIVERY_FAILED, entry, charge);
        }
        return new PurchaseResult(PurchaseStatus.OK, entry, charge);
    }
}
