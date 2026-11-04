package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.EarnService;
import chartercurrency.core.MintService;
import chartercurrency.core.ShopCatalog;
import chartercurrency.core.ShopEntry;
import chartercurrency.core.Treasury;
import chartercurrency.core.Wallet;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /chartercurrency stats} and {@code /chartercurrency reload}.
 *
 * <p>Stats read the same state the rest of the mod writes: banked balances, kingdom
 * treasuries, the rolling daily totals in {@code players.json}. Coin carried in
 * inventories is not counted anywhere and the output says so.
 */
final class StatsCommands {

    private final Services s;
    private final java.nio.file.Path configDir;
    private final ShopConfig shopConfig;
    private final ShopCatalog catalog;

    StatsCommands(Services services, java.nio.file.Path configDir, ShopConfig shopConfig, ShopCatalog catalog) {
        this.s = services;
        this.configDir = configDir;
        this.shopConfig = shopConfig;
        this.catalog = catalog;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chartercurrency")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("stats").executes(this::stats))
                .then(Commands.literal("reload").executes(this::reload)));
    }

    /** The coin supply: every banked balance plus every kingdom treasury. */
    long coinSupply() {
        long banked = s.economy.totalSupply(s.coin());
        long treasuries = s.treasury.totalSupply();
        return banked > Long.MAX_VALUE - treasuries ? Long.MAX_VALUE : banked + treasuries;
    }

    private int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Currency coin = s.coin();
        long today = s.settings.today();

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("CHARTER CURRENCY STATS"), false);

        long banked = s.economy.totalSupply(coin);
        long treasuries = s.treasury.totalSupply();
        source.sendSuccess(() -> Messages.body("Coin supply: " + Wallet.format(banked + treasuries)
                + " Marks (banked " + Wallet.format(banked) + ", kingdom treasuries "
                + Wallet.format(treasuries) + "). Coin carried in inventories is not counted."), false);
        source.sendSuccess(() -> Messages.body("Minted: " + Wallet.format(s.book.stat(MintService.STAT_MINTED, today, 1))
                + " Marks today, " + Wallet.format(s.book.stat(MintService.STAT_MINTED, today, 7)) + " this week."), false);
        source.sendSuccess(() -> Messages.body("Mint tax: collected "
                + Wallet.format(s.book.stat(MintService.STAT_TAX_PAID, today, 1)) + " today ("
                + Wallet.format(s.book.stat(MintService.STAT_TAX_PAID, today, 7)) + " this week), destroyed "
                + Wallet.format(s.book.stat(MintService.STAT_TAX_DESTROYED, today, 1)) + " today ("
                + Wallet.format(s.book.stat(MintService.STAT_TAX_DESTROYED, today, 7)) + " this week)."), false);

        Currency valor = s.currencies.byId("valor").orElse(null);
        if (valor != null) {
            source.sendSuccess(() -> Messages.body("Valor in accounts: "
                    + Wallet.format(s.economy.totalSupply(valor))), false);
        }
        for (EarnService.Source src : s.earn.config().sources().values()) {
            long earned = s.book.stat("valor-" + src.key(), today, 1);
            if (earned > 0) {
                source.sendSuccess(() -> Messages.body("  Valor today from " + src.key() + ": "
                        + Wallet.format(earned)), false);
            }
        }

        List<String> ids = new ArrayList<>(s.treasury.orgIds());
        ids.sort(Comparator.naturalOrder());
        source.sendSuccess(() -> Messages.body("Kingdoms:"), false);
        if (ids.isEmpty()) source.sendSuccess(() -> Messages.body("  nothing recorded yet"), false);
        for (String id : ids) {
            String name = Treasury.COMMONS.equals(id) ? "Commons" : s.kingdoms.source().displayName(id);
            source.sendSuccess(() -> Messages.body("  " + name + ": Renown " + Wallet.format(s.treasury.renown(id))
                    + " (earned " + Wallet.format(s.treasury.renownLifetime(id)) + "), treasury "
                    + Wallet.format(s.treasury.balance(id)) + " Marks, tax collected "
                    + Wallet.format(s.treasury.taxCollected(id))), false);
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    /** Reload everything except accounts.json and the coin denominations. */
    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            EconomySettings fresh = EconomySettings.load(configDir);
            s.settings.timezone = fresh.timezone;
            s.settings.mint = fresh.mint;
            s.settings.coinModels = fresh.coinModels;
            CoinStacks.models = fresh.coinModels;
            s.settings.shop.confirmAt = fresh.shop.confirmAt;
            s.mint.setRules(fresh.mint);

            s.earn.setConfig(EarnConfigFile.loadEarn(configDir, s.earn.config()));
            s.catalog = EarnConfigFile.loadCatalog(configDir, s.catalog);

            catalog.clear();
            for (ShopEntry entry : shopConfig.load(source.getServer().registryAccess()).all()) {
                catalog.put(entry);
            }
        } catch (RuntimeException e) {
            CharterCurrencyMod.LOG.error("Reload failed", e);
            source.sendFailure(Messages.bad("Reload failed; see the server log. Nothing was half applied "
                    + "that was not already in memory."));
            return 0;
        }
        source.sendSuccess(() -> Messages.good("Reloaded settings, earn.json, renown_catalog.json and the shop. "
                + "currencies.json needs a restart."), true);
        s.log.admin(source.getTextName() + " reloaded the configuration");
        return 1;
    }
}
