package cobbleeconomy;

import cobbleeconomy.core.CobbleEconomy;
import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.LeaderboardService;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Cobble Economy. Cobblestone is money, diamonds are better money.
 *
 * <p>Server-side only -- vanilla clients need nothing installed. Nothing is added to a
 * synced registry, no packets are sent that a vanilla client cannot read, and both
 * currencies are ordinary vanilla items every client already has.
 *
 * <p>The shape is four layers, and they only ever point one way:
 *
 * <pre>
 *   commands  ->  EconomyService   ->  AccountStore
 *                 LeaderboardService
 *                 Shop
 *   (this jar)    (core module)        (this jar)
 * </pre>
 *
 * <p>The middle layer knows nothing about Minecraft, which is why the money rules,
 * the ranking rules and the purchase transaction are all under test in a module that
 * will not compile if someone imports the game into it. A future auction house is
 * another caller of {@code EconomyService}, not another place that understands JSON.
 */
public final class CobbleEconomyMod implements ModInitializer {

    public static final String MOD_ID = "cobbleeconomy";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private JsonAccountStore store;
    private TransactionLog log;
    private CobbleEconomy economy;
    private NameCache names;
    private LoginSnapshot loginSnapshot;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        EconomySettings settings = EconomySettings.load(configDir);
        CurrencyRegistry currencies = CurrencyRegistry.defaults();

        store = new JsonAccountStore(configDir);
        names = new NameCache(configDir);
        log = new TransactionLog(configDir, settings.transactionLogFile);
        economy = new CobbleEconomy(store);

        // The leaderboard needs last-seen names for its tie-break only; identity is
        // always the UUID, so a rename moves the label and nothing else.
        LeaderboardService leaderboard = new LeaderboardService(economy, names::nameOf);

        ShopConfig shopConfig = new ShopConfig(configDir, currencies);
        // Loaded on SERVER_STARTED, not here -- "wondrous" is only a "suggests"
        // dependency, so its onInitialize() (which populates WondrousItems.Holder)
        // is not guaranteed to have run yet. Loading here would silently drop every
        // wondrous: shop entry whenever wondrous initialises after this mod.
        ShopCatalog catalog = new ShopCatalog();

        loginSnapshot = new LoginSnapshot(leaderboard, currencies, names, settings);

        EconomyService service = economy;
        EconomyApi.Holder.set(service, leaderboard, currencies, catalog);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            new EconomyCommands(service, leaderboard, currencies, names, log, settings)
                    .register(dispatcher);
            new ShopCommands(service, catalog, log).register(dispatcher);
            new AdminCommands(service, currencies, names, log, catalog, shopConfig)
                    .register(dispatcher);
        });

        // Every join refreshes the name cache. This is what makes /pay work for
        // offline players: a UUID the server has seen is a UUID it can be paid at.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            names.see(handler.getPlayer().getUUID(), handler.getPlayer().getName().getString());
            loginSnapshot.onJoin(handler.getPlayer(), server.getTickCount());
        });

        ServerTickEvents.END_SERVER_TICK.register(server ->
                loginSnapshot.tick(server, server.getTickCount()));

        // Flush before the process leaves. The debounce window means an unclean kill
        // can cost a few seconds of transactions; a clean stop costs nothing.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOG.info("Flushing economy before shutdown");
            economy.shutdown();
            store.close();
            log.close();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ShopEntry entry : shopConfig.load(server.registryAccess()).all()) {
                catalog.put(entry);
            }
            for (Currency currency : currencies.all()) {
                LOG.info("  {}: {} accounts, {} banked in total",
                        currency.displayName(),
                        leaderboard.participants(currency),
                        economy.totalSupply(currency));
            }
            LOG.info("Cobble Economy ready -- {} shop entries", catalog.size());
        });

        LOG.info("Cobble Economy initialised (server-side only)");
    }
}
