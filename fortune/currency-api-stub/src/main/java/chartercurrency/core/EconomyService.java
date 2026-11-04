package chartercurrency.core;

import java.util.UUID;

/** Compile time stub: only the two calls the coin stake makes. */
public interface EconomyService {

    long getBalance(UUID player, Currency currency);

    TxResult withdraw(UUID player, Currency currency, long amount);
}
