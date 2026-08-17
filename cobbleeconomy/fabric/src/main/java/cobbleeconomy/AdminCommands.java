package cobbleeconomy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import cobbleeconomy.core.TxResult;
import cobbleeconomy.core.Wallet;
import cobbleeconomy.dialog.DialogTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@code /cobbleeconomy}, operator only.
 *
 * <p>Everything here mints or destroys currency out of thin air, or changes what
 * things cost, which is why it is behind permission level 2 and why every call is
 * written to the transaction log with {@code [Economy/Admin]} and the operator's name.
 * When the total supply jumps overnight the first question is whether a person did it,
 * and this is what answers that.
 *
 * <p>{@code add} and {@code remove} are deliberately the same {@code deposit} and
 * {@code withdraw} the players use, not a privileged path that skips validation. An
 * admin cannot push an account negative, because no one can.
 */
public final class AdminCommands {

    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final NameCache names;
    private final TransactionLog log;
    private final ShopCatalog catalog;
    private final ShopConfig shopConfig;

    public AdminCommands(EconomyService economy, CurrencyRegistry currencies, NameCache names,
                         TransactionLog log, ShopCatalog catalog, ShopConfig shopConfig) {
        this.economy = economy;
        this.currencies = currencies;
        this.names = names;
        this.log = log;
        this.catalog = catalog;
        this.shopConfig = shopConfig;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbleeconomy")
                // 26.x replaced integer op levels with a permission system.
                // Commands.hasPermission(...) builds the Predicate<CommandSourceStack>
                // that .requires() wants, and LEVEL_GAMEMASTERS is what used to be
                // op level 2 -- the same gate vanilla puts on /gamemode and /give.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("help").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    source.sendSuccess(Messages::rule, false);
                    source.sendSuccess(() -> Messages.title("COBBLE ECONOMY ADMIN"), false);
                    source.sendSuccess(Messages::blank, false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy balance <player>", "Check a player's balances"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy set <player> <amount> <currency>", "Set a balance"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy add <player> <amount> <currency>", "Add to a balance"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy remove <player> <amount> <currency>", "Remove from a balance"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop edit", "Edit the shop in a menu"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop list", "List all shop entries"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop reload", "Reload shop.json"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop add <key> <item> <qty> <price> <currency>", "Add a shop entry"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop remove <key>", "Remove a shop entry"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop setprice <key> <price> <currency>", "Set an entry's price"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop setquantity <key> <qty>", "Set an entry's quantity"), false);
                    source.sendSuccess(() -> Messages.helpLine("/cobbleeconomy shop enable|disable <key>", "Toggle an entry"), false);
                    source.sendSuccess(Messages::rule, false);
                    return 1;
                }))
                .then(Commands.literal("balance")
                        .then(playerArg().executes(this::balance)))
                .then(Commands.literal("set")
                        .then(playerArg().then(amountArg().then(currencyArg()
                                .executes(ctx -> apply(ctx, Op.SET))))))
                .then(Commands.literal("add")
                        .then(playerArg().then(amountArg().then(currencyArg()
                                .executes(ctx -> apply(ctx, Op.ADD))))))
                .then(Commands.literal("remove")
                        .then(playerArg().then(amountArg().then(currencyArg()
                                .executes(ctx -> apply(ctx, Op.REMOVE))))))
                // Diagnostic only -- see DIALOGS_SPEC.md Part 3 and dialog/DialogTest.
                // Remove once the real dialogs it is de-risking are built.
                .then(Commands.literal("dialogtest").executes(this::dialogTest))
                .then(Commands.literal("shop")
                        .then(Commands.literal("edit").executes(this::shopEdit))
                        .then(Commands.literal("list").executes(this::shopList))
                        .then(Commands.literal("reload").executes(this::shopReload))
                        .then(Commands.literal("remove")
                                .then(entryArg().executes(this::shopRemove)))
                        .then(Commands.literal("enable")
                                .then(entryArg().executes(ctx -> shopToggle(ctx, true))))
                        .then(Commands.literal("disable")
                                .then(entryArg().executes(ctx -> shopToggle(ctx, false))))
                        .then(Commands.literal("setprice")
                                .then(entryArg()
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .then(currencyArg().executes(this::shopSetPrice)))))
                        .then(Commands.literal("setquantity")
                                .then(entryArg()
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                                .executes(this::shopSetQuantity))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("item", StringArgumentType.string())
                                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                                .then(currencyArg()
                                                                        .executes(this::shopAdd)))))))));
    }

    private enum Op { SET, ADD, REMOVE }

    private RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("player", StringArgumentType.word()).suggests(knownPlayers());
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> amountArg() {
        return Commands.argument("amount", StringArgumentType.word());
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> currencyArg() {
        return Commands.argument("currency", StringArgumentType.word())
                .suggests((ctx, builder) ->
                        SharedSuggestionProvider.suggest(currencies.suggestions(), builder));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> entryArg() {
        return Commands.argument("entry", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    List<String> keys = new ArrayList<>();
                    for (ShopEntry entry : catalog.all()) keys.add(entry.key());
                    return SharedSuggestionProvider.suggest(keys, builder);
                });
    }

    // ---- /cobbleeconomy balance <player> ------------------------------------

    private int balance(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Optional<UUID> target = resolve(source, StringArgumentType.getString(ctx, "player"));
        if (target.isEmpty()) return 0;

        UUID id = target.get();
        String display = names.nameOf(id);
        source.sendSuccess(() -> Messages.body(display + "'s balances:"), false);
        for (Currency currency : currencies.all()) {
            long balance = economy.getBalance(id, currency);
            source.sendSuccess(() -> Messages.body("  ")
                    .append(Messages.balanceLine(currency, balance)), false);
        }
        return 1;
    }

    // ---- set / add / remove -------------------------------------------------

    private int apply(CommandContext<CommandSourceStack> ctx, Op op) {
        CommandSourceStack source = ctx.getSource();
        Optional<UUID> target = resolve(source, StringArgumentType.getString(ctx, "player"));
        if (target.isEmpty()) return 0;
        Optional<Currency> maybeCurrency = resolveCurrency(ctx);
        if (maybeCurrency.isEmpty()) return 0;

        UUID id = target.get();
        Currency currency = maybeCurrency.get();
        String display = names.nameOf(id);
        String raw = StringArgumentType.getString(ctx, "amount");

        long amount;
        if (op == Op.SET) {
            // set 0 is meaningful -- it is how you clear an account -- so it does not
            // go through the positive-amounts-only parser the others use.
            try {
                amount = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                source.sendFailure(Messages.bad("'" + raw + "' is not a number."));
                return 0;
            }
            if (amount < 0) {
                source.sendFailure(Messages.bad("Balance cannot be negative."));
                return 0;
            }
        } else {
            OptionalLong parsed = EconomyCommands.parseAmount(source, raw);
            if (parsed.isEmpty()) return 0;
            amount = parsed.getAsLong();
        }

        TxResult result = switch (op) {
            case SET -> economy.set(id, currency, amount);
            case ADD -> economy.deposit(id, currency, amount);
            case REMOVE -> economy.withdraw(id, currency, amount);
        };

        if (!result.ok()) {
            source.sendFailure(Messages.bad(switch (result.status()) {
                case INSUFFICIENT_FUNDS -> display + " only has "
                        + currency.describe(result.actorBalance()) + ".";
                case OVERFLOW -> "That would exceed the maximum balance.";
                default -> "That is not a valid amount.";
            }));
            return 0;
        }

        String verb = switch (op) {
            case SET -> "set " + display + "'s " + currency.id() + " balance to " + amount;
            case ADD -> "added " + amount + " " + currency.id() + " to " + display;
            case REMOVE -> "removed " + amount + " " + currency.id() + " from " + display;
        };

        long finalBalance = result.actorBalance();
        source.sendSuccess(() -> Messages.good(capitalise(verb) + ".")
                .append(Messages.body(" Balance: "))
                .append(Messages.amount(currency, finalBalance)), true);

        // The operator's name, not "an admin" -- on a server with several operators
        // "who did this" is the entire point of the line.
        log.admin(source.getTextName() + " " + verb + " (balance " + finalBalance + ")");

        ServerPlayer online = source.getServer().getPlayerList().getPlayer(id);
        if (online != null) {
            online.sendSystemMessage(Messages.body("An administrator adjusted your "
                    + currency.displayName() + " balance."));
            online.sendSystemMessage(Messages.balanceLine(currency, finalBalance));
        }
        return 1;
    }

    // ---- /cobbleeconomy dialogtest -------------------------------------------

    /**
     * Opens {@link DialogTest}: one of every unconfirmed input control, wired to print
     * whatever the client actually submits. See {@code DIALOGS_SPEC.md} Part 3.
     */
    private int dialogTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Messages.bad("The dialog test needs a screen -- run it as a player."));
            return 0;
        }
        DialogTest.open(player);
        return 1;
    }

    // ---- /cobbleeconomy shop ------------------------------------------------

    /**
     * The whole of {@code shop.json} as a menu. Needs a player, because it is a screen --
     * console keeps the chat commands below, which do the same things through the same
     * catalog and the same {@link ShopConfig#save}.
     */
    private int shopEdit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Messages.bad("The shop editor is a screen -- run it as a player."));
            source.sendFailure(Messages.body("From console, use /cobbleeconomy shop list."));
            return 0;
        }
        ShopAdminMenu.open(player, catalog, shopConfig, currencies, log);
        return 1;
    }

    private int shopList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (catalog.size() == 0) {
            source.sendSuccess(() -> Messages.body("The shop is empty."), false);
            return 1;
        }
        for (ShopEntry entry : catalog.all()) {
            String state = !entry.enabled() ? " [disabled]" : entry.isValid() ? "" : " [INVALID]";
            source.sendSuccess(() -> Messages.body(entry.key() + ": " + entry.quantity()
                    + "x " + entry.itemId() + " for " + entry.describePrice()
                    + " (" + entry.category() + ")" + state), false);
        }
        return 1;
    }

    private int shopReload(CommandContext<CommandSourceStack> ctx) {
        ShopCatalog reloaded = shopConfig.load(ctx.getSource().registryAccess());
        catalog.clear();
        for (ShopEntry entry : reloaded.all()) catalog.put(entry);
        ctx.getSource().sendSuccess(() -> Messages.good(
                "Reloaded shop.json (" + catalog.size() + " entries)."), true);
        log.admin(ctx.getSource().getTextName() + " reloaded the shop config");
        return 1;
    }

    private int shopAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Optional<Currency> currency = resolveCurrency(ctx);
        if (currency.isEmpty()) return 0;

        String key = StringArgumentType.getString(ctx, "key").toLowerCase(java.util.Locale.ROOT);
        String itemId = StringArgumentType.getString(ctx, "item");
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        long price = LongArgumentType.getLong(ctx, "price");

        if (WondrousShop.isWondrousItemId(itemId)) {
            if (!WondrousShop.available()
                    || WondrousShop.baseItem(WondrousShop.idFrom(itemId)).isEmpty()) {
                source.sendFailure(Messages.bad("No such wondrous item: " + itemId));
                return 0;
            }
        } else if (ItemBank.resolve(itemId).isEmpty()) {
            source.sendFailure(Messages.bad("No such item: " + itemId));
            return 0;
        }

        catalog.put(ShopEntry.of(key, itemId, quantity, currency.get(), price, "Goods"));
        shopConfig.save(catalog);
        source.sendSuccess(() -> Messages.good("Added " + key + ": " + quantity + "x "
                + itemId + " for " + currency.get().describe(price) + "."), true);
        log.admin(source.getTextName() + " added shop entry " + key);
        return 1;
    }

    private int shopRemove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "entry");
        if (!catalog.remove(key)) {
            source.sendFailure(Messages.bad("No shop entry called '" + key + "'."));
            return 0;
        }
        shopConfig.save(catalog);
        source.sendSuccess(() -> Messages.good("Removed shop entry " + key + "."), true);
        log.admin(source.getTextName() + " removed shop entry " + key);
        return 1;
    }

    private int shopSetPrice(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Optional<ShopEntry> found = requireEntry(ctx);
        if (found.isEmpty()) return 0;
        Optional<Currency> currency = resolveCurrency(ctx);
        if (currency.isEmpty()) return 0;

        ShopEntry entry = found.get();
        long price = LongArgumentType.getLong(ctx, "price");
        // Replaces the whole price rather than editing one line of it. A command that
        // edits one currency of a compound price needs a way to remove a line too,
        // and that is a config-file job, not a chat-command job.
        Map<Currency, Long> newPrice = new LinkedHashMap<>();
        newPrice.put(currency.get(), price);
        catalog.put(new ShopEntry(entry.key(), entry.itemId(), entry.quantity(),
                newPrice, entry.category(), entry.enabled(), entry.components()));
        shopConfig.save(catalog);

        source.sendSuccess(() -> Messages.good(entry.key() + " now costs "
                + currency.get().describe(price) + "."), true);
        log.admin(source.getTextName() + " set " + entry.key() + " price to "
                + price + " " + currency.get().id());
        return 1;
    }

    private int shopSetQuantity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Optional<ShopEntry> found = requireEntry(ctx);
        if (found.isEmpty()) return 0;

        ShopEntry entry = found.get();
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        catalog.put(new ShopEntry(entry.key(), entry.itemId(), quantity, entry.price(),
                entry.category(), entry.enabled(), entry.components()));
        shopConfig.save(catalog);

        source.sendSuccess(() -> Messages.good(entry.key() + " now grants "
                + quantity + "x " + entry.itemId() + "."), true);
        log.admin(source.getTextName() + " set " + entry.key() + " quantity to " + quantity);
        return 1;
    }

    private int shopToggle(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSourceStack source = ctx.getSource();
        Optional<ShopEntry> found = requireEntry(ctx);
        if (found.isEmpty()) return 0;

        ShopEntry entry = found.get();
        catalog.put(new ShopEntry(entry.key(), entry.itemId(), entry.quantity(),
                entry.price(), entry.category(), enabled, entry.components()));
        shopConfig.save(catalog);

        source.sendSuccess(() -> Messages.good(entry.key() + " is now "
                + (enabled ? "enabled" : "disabled") + "."), true);
        log.admin(source.getTextName() + (enabled ? " enabled " : " disabled ") + entry.key());
        return 1;
    }

    // ---- helpers -------------------------------------------------------------

    private Optional<ShopEntry> requireEntry(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "entry");
        Optional<ShopEntry> found = catalog.find(key);
        if (found.isEmpty()) {
            ctx.getSource().sendFailure(Messages.bad("No shop entry called '" + key + "'."));
        }
        return found;
    }

    private Optional<Currency> resolveCurrency(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "currency");
        Optional<Currency> currency = currencies.resolve(raw);
        if (currency.isEmpty()) {
            ctx.getSource().sendFailure(Messages.bad("Unknown currency '" + raw + "'."));
        }
        return currency;
    }

    private Optional<UUID> resolve(CommandSourceStack source, String name) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getUUID());

        Optional<UUID> cached = names.resolve(name);
        if (cached.isEmpty()) {
            source.sendFailure(Messages.bad("No player named '" + name
                    + "' has been seen on this server."));
        }
        return cached;
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private SuggestionProvider<CommandSourceStack> knownPlayers() {
        return (ctx, builder) -> {
            List<String> options = new ArrayList<>(ctx.getSource().getOnlinePlayerNames());
            for (String name : names.knownNames()) {
                if (!options.contains(name)) options.add(name);
            }
            return SharedSuggestionProvider.suggest(options, builder);
        };
    }
}
