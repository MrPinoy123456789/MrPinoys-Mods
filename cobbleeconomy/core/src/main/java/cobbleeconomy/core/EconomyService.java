package cobbleeconomy.core;

import java.util.Map;
import java.util.UUID;

/**
 * The economy. Every system that touches money goes through this interface.
 *
 * <p>Balances are per currency and completely independent: no method here converts
 * one currency into another, and none ever will. {@link #charge} takes several
 * currencies at once, but it debits each from its own balance -- it is a way to make
 * a compound price atomic, not an exchange rate.
 *
 * <p>Implementations must be safe to call from any thread.
 */
public interface EconomyService {

    /** Balance of one currency. Unknown players have 0 and are not created by asking. */
    long getBalance(UUID player, Currency currency);

    /** Every non-zero balance a player holds, keyed by currency id. */
    Map<String, Long> getBalances(UUID player);

    /** Add to a balance. Rejects non-positive amounts and overflow. */
    TxResult deposit(UUID player, Currency currency, long amount);

    /** Remove from a balance. Rejects non-positive amounts and insufficient funds. */
    TxResult withdraw(UUID player, Currency currency, long amount);

    /** Move one currency between two accounts, atomically. */
    TxResult transfer(UUID from, UUID to, Currency currency, long amount);

    /** Overwrite one balance outright. Admin only; rejects negative values. */
    TxResult set(UUID player, Currency currency, long amount);

    /**
     * Debit several currencies at once, all or nothing. This is what a shop price
     * costing 500 cobblestone and 1 diamond runs through: the player is charged both
     * or neither, never one.
     *
     * @param price currency to amount; amounts must be positive
     * @return OK, or the first failure encountered with nothing debited
     */
    TxResult charge(UUID player, Map<Currency, Long> price);

    /** Refund a compound price. Used to unwind a purchase that could not be delivered. */
    void refund(UUID player, Map<Currency, Long> price);

    /**
     * Every non-zero balance in one currency, for ranking. Returned as a snapshot;
     * mutating it does not affect the economy.
     */
    Map<UUID, Long> balances(Currency currency);

    /** Sum of every banked balance in one currency. Saturates rather than wrapping. */
    long totalSupply(Currency currency);
}
