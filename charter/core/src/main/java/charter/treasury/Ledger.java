package charter.treasury;

import java.util.EnumMap;
import java.util.Map;

/** An organization's treasury: whole item counts per currency, never negative. */
public final class Ledger {

    private final EnumMap<Currency, Long> balances = new EnumMap<>(Currency.class);

    public long balance(Currency currency) {
        return balances.getOrDefault(currency, 0L);
    }

    public void add(Currency currency, long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative deposit");
        balances.merge(currency, amount, Long::sum);
    }

    /** Takes the amount if the balance covers it; returns false and changes nothing otherwise. */
    public boolean take(Currency currency, long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative withdrawal");
        long have = balance(currency);
        if (have < amount) return false;
        balances.put(currency, have - amount);
        return true;
    }

    public Map<Currency, Long> snapshot() {
        return Map.copyOf(balances);
    }
}
