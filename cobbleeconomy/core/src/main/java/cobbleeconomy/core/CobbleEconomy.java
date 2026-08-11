package cobbleeconomy.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The default {@link EconomyService}: an in-memory account map that is the single
 * source of truth, with an {@link AccountStore} behind it as a sink.
 *
 * <p><b>Why memory is authoritative.</b> The alternative -- read the file, modify,
 * write it back -- is the classic lost-update race, and the moment two players type
 * {@code /pay} in the same tick it silently eats one of the transactions. Here the
 * file is only ever written from memory and only ever read at boot.
 *
 * <p><b>Locking.</b> Every mutator synchronizes on {@code lock}. Minecraft runs
 * commands on the server thread, so contention is nil and this costs nothing -- but
 * transfers must hold the lock across both sides, and {@link #charge} must hold it
 * across every currency in a compound price, and that is what makes them atomic.
 *
 * <p><b>Zero balances are not stored.</b> An account that drops to 0 in a currency
 * loses that entry; an account with nothing at all is removed. A server that has seen
 * ten thousand players does not carry ten thousand rows of nothing, and the
 * leaderboard's "balance &gt; 0" rule falls out of the storage model for free.
 */
public final class CobbleEconomy implements EconomyService {

    private final Map<UUID, Map<String, Long>> accounts = new HashMap<>();
    private final AccountStore store;
    private final Object lock = new Object();

    public CobbleEconomy(AccountStore store) {
        this.store = store;
        for (Map.Entry<UUID, Map<String, Long>> e : store.load().entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            Map<String, Long> balances = new LinkedHashMap<>();
            for (Map.Entry<String, Long> b : e.getValue().entrySet()) {
                if (b.getValue() != null && b.getValue() > 0) balances.put(b.getKey(), b.getValue());
            }
            if (!balances.isEmpty()) accounts.put(e.getKey(), balances);
        }
    }

    // ---- reads -------------------------------------------------------------

    @Override
    public long getBalance(UUID player, Currency currency) {
        if (player == null || currency == null) return 0L;
        synchronized (lock) {
            Map<String, Long> balances = accounts.get(player);
            if (balances == null) return 0L;
            return balances.getOrDefault(currency.id(), 0L);
        }
    }

    @Override
    public Map<String, Long> getBalances(UUID player) {
        if (player == null) return Map.of();
        synchronized (lock) {
            Map<String, Long> balances = accounts.get(player);
            return balances == null ? Map.of() : Map.copyOf(balances);
        }
    }

    @Override
    public Map<UUID, Long> balances(Currency currency) {
        if (currency == null) return Map.of();
        synchronized (lock) {
            Map<UUID, Long> out = new HashMap<>();
            for (Map.Entry<UUID, Map<String, Long>> e : accounts.entrySet()) {
                Long value = e.getValue().get(currency.id());
                if (value != null && value > 0) out.put(e.getKey(), value);
            }
            return out;
        }
    }

    @Override
    public long totalSupply(Currency currency) {
        long sum = 0L;
        for (long v : balances(currency).values()) {
            if (sum > Long.MAX_VALUE - v) return Long.MAX_VALUE;
            sum += v;
        }
        return sum;
    }

    // ---- writes ------------------------------------------------------------

    @Override
    public TxResult deposit(UUID player, Currency currency, long amount) {
        if (player == null) return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, 0L);
        if (currency == null) return TxResult.fail(TxStatus.UNKNOWN_CURRENCY, null, amount, 0L);
        synchronized (lock) {
            long current = getBalance(player, currency);
            if (amount <= 0) return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, current);
            if (current > Long.MAX_VALUE - amount) {
                return TxResult.fail(TxStatus.OVERFLOW, currency, amount, current);
            }
            long updated = current + amount;
            commit(player, currency, updated);
            return TxResult.ok(currency, amount, updated);
        }
    }

    @Override
    public TxResult withdraw(UUID player, Currency currency, long amount) {
        if (player == null) return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, 0L);
        if (currency == null) return TxResult.fail(TxStatus.UNKNOWN_CURRENCY, null, amount, 0L);
        synchronized (lock) {
            long current = getBalance(player, currency);
            if (amount <= 0) return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, current);
            if (current < amount) {
                return TxResult.fail(TxStatus.INSUFFICIENT_FUNDS, currency, amount, current);
            }
            long updated = current - amount;
            commit(player, currency, updated);
            return TxResult.ok(currency, amount, updated);
        }
    }

    @Override
    public TxResult transfer(UUID from, UUID to, Currency currency, long amount) {
        if (from == null || to == null) {
            return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, getBalance(from, currency));
        }
        if (currency == null) return TxResult.fail(TxStatus.UNKNOWN_CURRENCY, null, amount, 0L);
        synchronized (lock) {
            long fromBalance = getBalance(from, currency);
            long toBalance = getBalance(to, currency);

            // Checked before the amount so that paying yourself nonsense reports the
            // mistake you actually made rather than the arithmetic.
            if (from.equals(to)) {
                return TxResult.fail(TxStatus.SELF_TRANSFER, currency, amount, fromBalance, toBalance);
            }
            if (amount <= 0) {
                return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, fromBalance, toBalance);
            }
            if (fromBalance < amount) {
                return TxResult.fail(TxStatus.INSUFFICIENT_FUNDS, currency, amount, fromBalance, toBalance);
            }
            if (toBalance > Long.MAX_VALUE - amount) {
                return TxResult.fail(TxStatus.OVERFLOW, currency, amount, fromBalance, toBalance);
            }

            // Both sides inside one lock, and only ever this one currency. A diamond
            // payment cannot touch cobblestone because it never reads that key.
            long newFrom = fromBalance - amount;
            long newTo = toBalance + amount;
            commit(from, currency, newFrom);
            commit(to, currency, newTo);
            return TxResult.ok(currency, amount, newFrom, newTo);
        }
    }

    @Override
    public TxResult set(UUID player, Currency currency, long amount) {
        if (player == null) return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, 0L);
        if (currency == null) return TxResult.fail(TxStatus.UNKNOWN_CURRENCY, null, amount, 0L);
        synchronized (lock) {
            long current = getBalance(player, currency);
            if (amount < 0) return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount, current);
            commit(player, currency, amount);
            return TxResult.ok(currency, amount, amount);
        }
    }

    @Override
    public TxResult charge(UUID player, Map<Currency, Long> price) {
        if (player == null || price == null || price.isEmpty()) {
            return TxResult.fail(TxStatus.INVALID_AMOUNT, null, 0L, 0L);
        }
        synchronized (lock) {
            // Validate the whole price before debiting any of it. A player who can
            // afford the cobblestone but not the diamond must lose neither.
            for (Map.Entry<Currency, Long> line : price.entrySet()) {
                Currency currency = line.getKey();
                long amount = line.getValue() == null ? 0L : line.getValue();
                if (currency == null) {
                    return TxResult.fail(TxStatus.UNKNOWN_CURRENCY, null, amount, 0L);
                }
                if (amount <= 0) {
                    return TxResult.fail(TxStatus.INVALID_AMOUNT, currency, amount,
                            getBalance(player, currency));
                }
                long balance = getBalance(player, currency);
                if (balance < amount) {
                    return TxResult.fail(TxStatus.INSUFFICIENT_FUNDS, currency, amount, balance);
                }
            }
            for (Map.Entry<Currency, Long> line : price.entrySet()) {
                Currency currency = line.getKey();
                commit(player, currency, getBalance(player, currency) - line.getValue());
            }
            Currency first = price.keySet().iterator().next();
            return TxResult.ok(first, price.get(first), getBalance(player, first));
        }
    }

    @Override
    public void refund(UUID player, Map<Currency, Long> price) {
        if (player == null || price == null) return;
        synchronized (lock) {
            for (Map.Entry<Currency, Long> line : price.entrySet()) {
                if (line.getKey() == null || line.getValue() == null || line.getValue() <= 0) continue;
                long current = getBalance(player, line.getKey());
                long restored = current > Long.MAX_VALUE - line.getValue()
                        ? Long.MAX_VALUE : current + line.getValue();
                commit(player, line.getKey(), restored);
            }
        }
    }

    /** Apply a validated balance and tell the store. Callers must hold {@code lock}. */
    private void commit(UUID player, Currency currency, long balance) {
        Map<String, Long> balances = accounts.get(player);
        if (balances == null) {
            if (balance <= 0) return;
            balances = new TreeMap<>();
            accounts.put(player, balances);
        }
        if (balance <= 0) {
            balances.remove(currency.id());
        } else {
            balances.put(currency.id(), balance);
        }
        if (balances.isEmpty()) accounts.remove(player);
        store.put(player, Map.copyOf(balances));
    }

    /** Flush pending writes. Called on server shutdown. */
    public void shutdown() {
        synchronized (lock) {
            store.flushNow();
        }
    }
}
