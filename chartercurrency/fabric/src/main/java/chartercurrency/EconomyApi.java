package chartercurrency;

import chartercurrency.core.Analytics;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.ExternalCharge;
import chartercurrency.core.TxResult;
import chartercurrency.core.TxStatus;
import java.util.UUID;
import chartercurrency.core.EconomyService;
import chartercurrency.core.LeaderboardService;
import chartercurrency.core.ShopCatalog;

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
 * <p>Callers must be prepared for these to return null if Charter Currency is absent,
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

    /**
     * Takes marks of coin from a player's bank for another mod, and records it: a line in
     * transactions.log and an analytics event, with the reason, then flushes the account file.
     * This is what keeps supply snapshots balanced when Fortune stakes coin. Returns the
     * withdrawal result; a failure changes and logs nothing.
     */
    public static TxResult chargeExternal(UUID player, String playerName, long marks, String reason) {
        EconomyService service = economy;
        Services s = Services.current;
        if (service == null || s == null) {
            return new TxResult(TxStatus.INVALID_AMOUNT, null, marks, 0, 0);
        }
        TxResult result = service.withdraw(player, s.coin(), marks);
        if (!result.ok()) return result;
        String name = playerName == null || playerName.isBlank() ? s.names.nameOf(player) : playerName;
        s.log.tx(ExternalCharge.line(name, marks, reason),
                Analytics.Event.of(Analytics.EXTERNAL_CHARGE).player(player, name)
                        .currency(Analytics.COIN).amount(marks).balanceAfter(result.actorBalance())
                        .put("reason", reason));
        s.flushMoney.run();
        return result;
    }

    /** The same, naming the player from the name cache. */
    public static TxResult chargeExternal(UUID player, long marks, String reason) {
        return chargeExternal(player, null, marks, reason);
    }

    /** Set once, during Charter Currency's own initialisation. */
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
