package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.EarnService;
import chartercurrency.core.EconomyService;
import chartercurrency.core.LeaderboardService;
import chartercurrency.core.MintService;
import chartercurrency.core.PlayerBook;
import chartercurrency.core.Treasury;

/** The wired-up services, handed to the command classes instead of long constructors. */
final class Services {

    /** Set once the mod has started; the Charter entrypoint classes read it. */
    static volatile Services current;

    final EconomySettings settings;
    final CurrencyRegistry currencies;
    final EconomyService economy;
    final LeaderboardService leaderboard;
    final NameCache names;
    final TransactionLog log;
    final Treasury treasury;
    final PlayerBook book;
    final MintService mint;
    final KingdomRuntime kingdoms;
    final MintSites sites;
    EarnService earn;
    /** Analytics lines and snapshots; set once the mod has wired it, null before. */
    AnalyticsRecorder analytics;
    /** The Banks; set once the mod has wired them, null before. */
    BankSites banks;
    /**
     * Writes balances and treasuries to disk now. Called after anything that hands out goods
     * (withdraw, shop delivery, treasury give, a stake), because the 15 second debounce is only
     * safe when the money moved before the goods (EC-6). Never null.
     */
    volatile Runnable flushMoney = () -> { };
    volatile java.util.List<Treasury.CatalogEntry> catalog = java.util.List.of();

    Services(EconomySettings settings, CurrencyRegistry currencies, EconomyService economy,
             LeaderboardService leaderboard, NameCache names, TransactionLog log, Treasury treasury,
             PlayerBook book, MintService mint, KingdomRuntime kingdoms, MintSites sites) {
        this.settings = settings;
        this.currencies = currencies;
        this.economy = economy;
        this.leaderboard = leaderboard;
        this.names = names;
        this.log = log;
        this.treasury = treasury;
        this.book = book;
        this.mint = mint;
        this.kingdoms = kingdoms;
        this.sites = sites;
    }

    Currency coin() {
        return currencies.coin();
    }
}
