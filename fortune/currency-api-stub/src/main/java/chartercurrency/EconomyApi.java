package chartercurrency;

import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.EconomyService;

/** Compile time stub of Charter Currency's accessor. The real class answers at run time. */
public final class EconomyApi {

    private EconomyApi() {}

    /** Balances and transactions, or null if Charter Currency has not started. */
    public static EconomyService economy() {
        throw new UnsupportedOperationException("stub");
    }

    /** The currencies this server supports, or null. */
    public static CurrencyRegistry currencies() {
        throw new UnsupportedOperationException("stub");
    }
}
