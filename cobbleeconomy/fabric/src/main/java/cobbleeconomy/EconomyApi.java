package cobbleeconomy;

import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.LeaderboardService;
import cobbleeconomy.core.ShopCatalog;

/**
 * How another mod gets hold of the economy.
 *
 * <pre>
 *   EconomyService economy = EconomyApi.economy();
 *   economy.withdraw(player.getUUID(), CurrencyRegistry.DIAMOND, 5);
 * </pre>
 *
 * <p>A static accessor rather than dependency injection because the consumers are
 * separate jars that cannot be handed anything at construction time -- the same
 * pattern the wondrous item registry uses in this project.
 *
 * <p>Callers must be prepared for these to return null if Cobble Economy is absent,
 * and should declare it as {@code suggests} rather than {@code depends} in their
 * {@code fabric.mod.json} if they want to degrade gracefully.
 */
public final class EconomyApi {

    private static volatile EconomyService economy;
    private static volatile LeaderboardService leaderboard;
    private static volatile CurrencyRegistry currencies;
    private static volatile ShopCatalog shop;

    private EconomyApi() {}

    /** Balances and transactions, or null if the mod is not loaded. */
    public static EconomyService economy() {
        return economy;
    }

    /** Rankings, or null. Use this rather than sorting balances yourself. */
    public static LeaderboardService leaderboard() {
        return leaderboard;
    }

    /** The currencies this server supports, or null. */
    public static CurrencyRegistry currencies() {
        return currencies;
    }

    /** The live shop catalog, or null. */
    public static ShopCatalog shop() {
        return shop;
    }

    /** Set once, during Cobble Economy's own initialisation. */
    public static final class Holder {
        private Holder() {}

        public static void set(EconomyService economyService, LeaderboardService leaderboardService,
                               CurrencyRegistry currencyRegistry, ShopCatalog catalog) {
            if (economy != null) {
                throw new IllegalStateException("EconomyApi already initialised");
            }
            economy = economyService;
            leaderboard = leaderboardService;
            currencies = currencyRegistry;
            shop = catalog;
        }
    }
}
