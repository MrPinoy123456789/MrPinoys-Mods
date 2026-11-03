package chartercurrency;

import chartercurrency.core.CharterCurrency;
import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.EarnService;
import chartercurrency.core.EconomyService;
import chartercurrency.core.LeaderboardService;
import chartercurrency.core.ManualKingdomSource;
import chartercurrency.core.MintService;
import chartercurrency.core.PlayerBook;
import chartercurrency.core.Treasury;
import chartercurrency.core.ShopCatalog;
import chartercurrency.core.ShopEntry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Charter Currency. The currency system for the Kingdoms server: Coin, Valor and Renown.
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
public final class CharterCurrencyMod implements ModInitializer {

    public static final String MOD_ID = "chartercurrency";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** How often carried coin is checked for out-of-date lore ({@link CoinStacks#refresh}). */
    private static final int COIN_REFRESH_TICKS = 100;

    private JsonAccountStore store;
    private TransactionLog log;
    private CharterCurrency economy;
    private NameCache names;
    private LoginSnapshot loginSnapshot;
    private JsonStateFile<PlayerBook.File> playersFile;
    private JsonStateFile<Treasury.State> treasuryFile;
    private JsonStateFile<KingdomRuntime.File> kingdomsFile;
    private JsonStateFile<MintSites.File> minesFile;
    private JsonStateFile<BankSites.File> banksFile;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        EconomySettings settings = EconomySettings.load(configDir);
        CurrencyRegistry currencies = CurrencyConfig.load(configDir);

        store = new JsonAccountStore(configDir);
        names = new NameCache(configDir);
        log = new TransactionLog(configDir, settings.transactionLogFile);
        economy = new CharterCurrency(store);

        // The leaderboard needs last-seen names for its tie-break only; identity is
        // always the UUID, so a rename moves the label and nothing else.
        LeaderboardService leaderboard = new LeaderboardService(economy, names::nameOf);

        ShopConfig shopConfig = new ShopConfig(configDir, currencies);
        // The shop catalog itself is loaded on SERVER_STARTED, once registries exist.
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "suite_items"),
                SuiteItems.Loader::new);
        ShopCatalog catalog = new ShopCatalog();

        // State beyond balances. Each file is written atomically and refuses to load
        // over a file it cannot read, because Renown and treasuries are money too.
        playersFile = new JsonStateFile<>(configDir, "players.json", PlayerBook.File.class);
        PlayerBook book = new PlayerBook(playersFile.load(), playersFile::markDirty);
        playersFile.bind(() -> book.locked(JsonStateFile::toJson));

        treasuryFile = new JsonStateFile<>(configDir, "kingdoms_state.json", Treasury.State.class);
        Treasury treasury = new Treasury(treasuryFile.load(), treasuryFile::markDirty,
                System::currentTimeMillis);
        treasuryFile.bind(() -> treasury.locked(JsonStateFile::toJson));

        kingdomsFile = new JsonStateFile<>(configDir, "kingdoms.json", KingdomRuntime.File.class);
        ManualKingdomSource manual = new ManualKingdomSource(kingdomsFile.load().kingdoms,
                kingdomsFile::markDirty);
        kingdomsFile.bind(() -> {
            KingdomRuntime.File snapshot = new KingdomRuntime.File();
            snapshot.kingdoms = manual.snapshot();
            return JsonStateFile.toJson(snapshot);
        });
        KingdomRuntime kingdoms = new KingdomRuntime(manual);

        minesFile = new JsonStateFile<>(configDir, "mints.json", MintSites.File.class);
        MintSites sites = new MintSites(minesFile.load(), minesFile::markDirty);
        minesFile.bind(sites::json);

        MintService mintService = new MintService(book, treasury, settings.mint);
        Services services = new Services(settings, currencies, economy, leaderboard, names, log,
                treasury, book, mintService, kingdoms, sites);
        Services.current = services;
        MintListener mintListener = new MintListener(services);
        mintListener.register();
        banksFile = new JsonStateFile<>(configDir, "banks.json", BankSites.File.class);
        BankSites bankSites = new BankSites(banksFile.load(), banksFile::markDirty);
        banksFile.bind(bankSites::json);
        services.banks = bankSites;
        new BankStation(services, bankSites, mintListener).register();
        new CoinBankClick(services).register();
        TreasuryCommands treasuryCommands = new TreasuryCommands(services, mintListener);
        KingdomAdminCommands kingdomAdmin = new KingdomAdminCommands(services, treasuryCommands);
        services.earn = new EarnService(book, economy, treasury,
                currencies.byId("valor").orElse(CurrencyRegistry.VALOR),
                EarnConfigFile.loadEarn(configDir, EarnConfigFile.defaultsEarn()),
                System::currentTimeMillis);
        services.catalog = EarnConfigFile.loadCatalog(configDir, EarnConfigFile.defaultsCatalog());
        RenownCommands renownCommands = new RenownCommands(services, treasuryCommands);
        StatsCommands statsCommands = new StatsCommands(services, configDir, shopConfig, catalog);

        loginSnapshot = new LoginSnapshot(leaderboard, currencies, names, settings);

        EconomyService service = economy;
        EconomyApi.Holder.set(service, leaderboard, currencies, catalog);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            EconomyCommands economyCommands =
                    new EconomyCommands(service, leaderboard, currencies, names, log, settings);
            economyCommands.attach(services);
            economyCommands.register(dispatcher);
            renownCommands.register(dispatcher);
            statsCommands.register(dispatcher);
            new ShopCommands(service, catalog, log, settings).register(dispatcher);
            new AdminCommands(service, currencies, names, log, catalog, shopConfig)
                    .register(dispatcher);
            new ShopkeeperCommands().register(dispatcher);
            treasuryCommands.register(dispatcher);
            kingdomAdmin.register(dispatcher);
        });

        ShopkeeperInteraction.register(service, catalog, log, settings);
        ShopkeeperAnchor.register();

        // Dialog buttons arrive as bare packets with none of the wiring the sgui menus
        // are handed at construction, so the shop's screens get their services once here.
        ShopDialogs.init(service, catalog, shopConfig, currencies, log, settings);

        // Every join refreshes the name cache. This is what makes /pay work for
        // offline players: a UUID the server has seen is a UUID it can be paid at.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            boolean known = names.knows(player.getUUID());
            names.see(player.getUUID(), player.getName().getString());
            loginSnapshot.onJoin(player, known, server.getTickCount());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            loginSnapshot.tick(server, server.getTickCount());
            // Coin from an older release (other lore) would not stack with new coin, so
            // carried coin is brought up to date every few seconds. Unchanged stacks cost
            // one comparison each.
            if (server.getTickCount() % COIN_REFRESH_TICKS == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    var inv = p.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        CoinStacks.refresh(inv.getItem(i), currencies.coin());
                    }
                }
            }
        });

        // Flush before the process leaves. The debounce window means an unclean kill
        // can cost a few seconds of transactions; a clean stop costs nothing.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOG.info("Flushing economy before shutdown");
            economy.shutdown();
            store.close();
            log.close();
            playersFile.close();
            treasuryFile.close();
            kingdomsFile.close();
            minesFile.close();
            banksFile.close();
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
            LOG.info("  Kingdom treasuries: {} Marks held across {} organizations (coin supply {} Marks in all)",
                    treasury.totalSupply(), treasury.orgIds().size(), statsCommands.coinSupply());
            LOG.info("Charter Currency ready: {} shop entries, kingdoms from {}",
                    catalog.size(), kingdoms.activeName());
        });

        LOG.info("Charter Currency initialised (server-side only)");
    }
}
