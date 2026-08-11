package cobbleeconomy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.LeaderboardService;
import cobbleeconomy.core.TxResult;
import cobbleeconomy.core.Wallet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The player-facing commands.
 *
 * <p>This layer holds no economic logic. It parses text, calls {@link EconomyService}
 * or {@link LeaderboardService}, and renders the answer. Every rule about what is
 * allowed lives in the core module, which is why the same rules apply to the shop and
 * to any future system that never goes near a command.
 *
 * <p>Amounts are parsed as strings rather than with Brigadier's numeric argument so
 * that {@code all}, {@code abc} and {@code -500} each get a sentence explaining what
 * went wrong instead of a red parse error pointing at a caret.
 *
 * <p>The canonical syntax is {@code <amount> <currency>}, as the spec recommends, and
 * the currency is always optional in one specific place: when the server has exactly
 * one currency there is nothing to disambiguate. On a two-currency server every
 * command that moves money names the currency, because guessing which money a player
 * meant is the one mistake here that is unrecoverable.
 */
public final class EconomyCommands {

    private final EconomyService economy;
    private final LeaderboardService leaderboard;
    private final CurrencyRegistry currencies;
    private final NameCache names;
    private final TransactionLog log;
    private final EconomySettings settings;

    public EconomyCommands(EconomyService economy, LeaderboardService leaderboard,
                           CurrencyRegistry currencies, NameCache names,
                           TransactionLog log, EconomySettings settings) {
        this.economy = economy;
        this.leaderboard = leaderboard;
        this.currencies = currencies;
        this.names = names;
        this.log = log;
        this.settings = settings;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // ---- /balance, /balance <currency> --------------------------------
        dispatcher.register(balanceCommand("balance"));
        dispatcher.register(balanceCommand("bal"));

        // ---- /bank, /bank all, /bank all <currency>, /bank <amount> <currency>
        dispatcher.register(Commands.literal("bank")
                .executes(this::panel)
                .then(Commands.literal("help").executes(this::panel))
                .then(Commands.literal("all")
                        .executes(this::depositEverything)
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(currencySuggestions())
                                .executes(this::depositAllOf)))
                .then(Commands.argument("amount", StringArgumentType.word())
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(currencySuggestions())
                                .executes(this::deposit))));

        // ---- /withdraw <amount|all> <currency> -----------------------------
        dispatcher.register(Commands.literal("withdraw")
                .then(Commands.argument("amount", StringArgumentType.word())
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(currencySuggestions())
                                .executes(this::withdraw))));

        // ---- /pay <player> <amount> <currency> -----------------------------
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(playerSuggestions())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .suggests(currencySuggestions())
                                        .executes(this::pay)))));

        // ---- /baltop, /baltop <currency>, /baltop me -----------------------
        if (settings.leaderboardsEnabled) {
            dispatcher.register(Commands.literal("baltop")
                    .executes(this::baltopAll)
                    .then(Commands.literal("me").executes(this::myRank))
                    .then(Commands.argument("currency", StringArgumentType.word())
                            .suggests(currencySuggestions())
                            .executes(ctx -> baltopOne(ctx, 1))
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(ctx -> baltopOne(ctx,
                                            IntegerArgumentType.getInteger(ctx, "page"))))));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> balanceCommand(String name) {
        return Commands.literal(name)
                .executes(this::balanceAll)
                .then(Commands.argument("currency", StringArgumentType.word())
                        .suggests(currencySuggestions())
                        .executes(this::balanceOne));
    }

    // ---- /balance -----------------------------------------------------------

    private int balanceAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("BANK"), false);
        source.sendSuccess(Messages::blank, false);
        for (Currency currency : currencies.all()) {
            long balance = economy.getBalance(player.getUUID(), currency);
            source.sendSuccess(() -> Messages.balanceLine(currency, balance), false);

            // Rank is opt-in because it doubles the length of the panel, and on a
            // server with many currencies that turns a glance into a scroll.
            if (settings.showRankOnBalance && settings.leaderboardsEnabled && balance > 0) {
                int rank = leaderboard.rankOf(player.getUUID(), currency);
                if (rank > 0) {
                    source.sendSuccess(() -> Messages.body("  Rank: #" + rank), false);
                }
            }
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private int balanceOne(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Currency> currency = resolveCurrency(ctx);
        if (currency.isEmpty()) return 0;

        long balance = economy.getBalance(player.getUUID(), currency.get());
        ctx.getSource().sendSuccess(() -> Messages.body(
                currency.get().displayName() + " Balance: ")
                .append(Messages.amount(currency.get(), balance)), false);
        return 1;
    }

    // ---- /bank with no arguments -------------------------------------------

    private int panel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("COBBLE BANK"), false);
        source.sendSuccess(Messages::blank, false);
        for (Currency currency : currencies.all()) {
            long balance = economy.getBalance(player.getUUID(), currency);
            long carried = itemFor(currency)
                    .map(item -> ItemBank.count(player, item)).orElse(0L);
            source.sendSuccess(() -> Messages.balanceLine(currency, balance)
                    .append(Messages.body("   (carrying " + Wallet.format(carried) + ")")), false);
        }
        source.sendSuccess(Messages::blank, false);
        source.sendSuccess(() -> Messages.helpLine("/bank all", "Deposit every currency"), false);
        source.sendSuccess(() -> Messages.helpLine("/bank all <currency>", "Deposit one"), false);
        source.sendSuccess(() -> Messages.helpLine("/bank <amount> <currency>", "Deposit"), false);
        source.sendSuccess(() -> Messages.helpLine("/withdraw <amount> <currency>", "Take out"), false);
        source.sendSuccess(() -> Messages.helpLine("/pay <player> <amount> <currency>", "Send"), false);
        source.sendSuccess(() -> Messages.helpLine("/shop", "What the server sells"), false);
        if (settings.leaderboardsEnabled) {
            source.sendSuccess(() -> Messages.helpLine("/baltop", "Richest players"), false);
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    // ---- /bank all ----------------------------------------------------------

    /**
     * Section 9, option A: {@code /bank all} with no currency banks everything.
     *
     * <p>Requiring a currency here would have been simpler, but {@code /bank all} is
     * the command the whole mod exists to provide -- the one a player types when their
     * inventory is choked with cobblestone. Making them type it twice for two
     * currencies loses the thing that made it worth building.
     */
    private int depositEverything(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        Map<Currency, Long> deposited = new LinkedHashMap<>();
        for (Currency currency : currencies.all()) {
            Optional<Item> item = itemFor(currency);
            if (item.isEmpty()) continue;
            long carried = ItemBank.count(player, item.get());
            if (carried <= 0) continue;

            // Each currency is its own transaction. A failure on diamonds must not
            // roll back a cobblestone deposit that already succeeded -- they are
            // independent balances and independent items.
            if (depositOne(player, currency, item.get(), carried)) {
                deposited.put(currency, carried);
            }
        }

        if (deposited.isEmpty()) {
            source.sendFailure(Messages.bad("You are not carrying any bankable currency."));
            return 0;
        }

        source.sendSuccess(() -> Messages.good("Deposited:"), false);
        for (Map.Entry<Currency, Long> line : deposited.entrySet()) {
            source.sendSuccess(() -> Messages.body("  ")
                    .append(Messages.priced(line.getKey(), line.getValue())), false);
        }
        for (Currency currency : deposited.keySet()) {
            long balance = economy.getBalance(player.getUUID(), currency);
            source.sendSuccess(() -> Messages.balanceLine(currency, balance), false);
        }
        return 1;
    }

    private int depositAllOf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Currency> currency = resolveCurrency(ctx);
        if (currency.isEmpty()) return 0;
        Optional<Item> item = itemFor(currency.get());
        if (item.isEmpty()) return 0;

        long carried = ItemBank.count(player, item.get());
        if (carried <= 0) {
            ctx.getSource().sendFailure(Messages.bad(
                    "You are not carrying any " + currency.get().plural() + "."));
            return 0;
        }
        return finishDeposit(ctx.getSource(), player, currency.get(), item.get(), carried);
    }

    // ---- /bank <amount> <currency> -----------------------------------------

    private int deposit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        Optional<Currency> currency = resolveCurrency(ctx);
        if (currency.isEmpty()) return 0;
        Optional<Item> item = itemFor(currency.get());
        if (item.isEmpty()) return 0;

        String raw = StringArgumentType.getString(ctx, "amount");
        long amount;
        if (raw.equalsIgnoreCase("all")) {
            amount = ItemBank.count(player, item.get());
            if (amount <= 0) {
                source.sendFailure(Messages.bad(
                        "You are not carrying any " + currency.get().plural() + "."));
                return 0;
            }
        } else {
            OptionalLong parsed = parseAmount(source, raw);
            if (parsed.isEmpty()) return 0;
            amount = parsed.getAsLong();
        }

        long carried = ItemBank.count(player, item.get());
        if (carried < amount) {
            source.sendFailure(Messages.bad("You only have "
                    + currency.get().describe(carried) + "."));
            return 0;
        }
        return finishDeposit(source, player, currency.get(), item.get(), amount);
    }

    private int finishDeposit(CommandSourceStack source, ServerPlayer player,
                              Currency currency, Item item, long amount) {
        if (!depositOne(player, currency, item, amount)) {
            source.sendFailure(Messages.bad("Could not bank that. Nothing was changed."));
            return 0;
        }
        long balance = economy.getBalance(player.getUUID(), currency);
        source.sendSuccess(() -> Messages.good("Deposited ")
                .append(Messages.priced(currency, amount))
                .append(Messages.good(".")), false);
        source.sendSuccess(() -> Messages.balanceLine(currency, balance), false);
        return 1;
    }

    /**
     * Physical to bank: items first, balance second.
     *
     * <p>If the server dies between the two the player has lost items -- which is bad,
     * but it is not currency created from nothing, and only one of those two failure
     * modes is worth farming.
     */
    private boolean depositOne(ServerPlayer player, Currency currency, Item item, long amount) {
        if (!ItemBank.removeExactly(player, item, amount)) return false;

        TxResult result = economy.deposit(player.getUUID(), currency, amount);
        if (!result.ok()) {
            // The bank refused after the items were already gone. Hand them straight
            // back. Reachable in practice only through an overflowing balance.
            ItemBank.give(player, item, amount);
            return false;
        }
        log.tx(player.getName().getString() + " deposited " + amount + " " + currency.id()
                + " (balance " + result.actorBalance() + ")");
        return true;
    }

    // ---- /withdraw <amount|all> <currency> ---------------------------------

    private int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        Optional<Currency> maybeCurrency = resolveCurrency(ctx);
        if (maybeCurrency.isEmpty()) return 0;
        Currency currency = maybeCurrency.get();
        Optional<Item> maybeItem = itemFor(currency);
        if (maybeItem.isEmpty()) return 0;
        Item item = maybeItem.get();

        long balance = economy.getBalance(player.getUUID(), currency);
        long capacity = ItemBank.freeCapacity(player, item);
        String raw = StringArgumentType.getString(ctx, "amount");
        long amount;

        if (raw.equalsIgnoreCase("all")) {
            // "all" resolves to as much as will actually fit. This is not a partial
            // transaction -- the amount is decided before anything moves, and then
            // that amount happens atomically. Erroring out instead would make
            // /withdraw all useless to anyone carrying a pickaxe and some torches.
            if (balance <= 0) {
                source.sendFailure(Messages.bad("You have no banked "
                        + currency.plural() + "."));
                return 0;
            }
            amount = Math.min(balance, capacity);
            if (amount <= 0) {
                source.sendFailure(Messages.bad("Your inventory is full."));
                return 0;
            }
            if (amount < balance) {
                long fits = amount;
                source.sendSuccess(() -> Messages.body("Taking what fits: "
                        + Wallet.format(fits) + " of " + Wallet.format(balance) + "."), false);
            }
        } else {
            OptionalLong parsed = parseAmount(source, raw);
            if (parsed.isEmpty()) return 0;
            amount = parsed.getAsLong();
        }

        if (balance < amount) {
            source.sendFailure(Messages.bad("Insufficient funds. Your balance is "
                    + currency.describe(balance) + "."));
            return 0;
        }

        // Inventory safety: refuse outright rather than part-filling.
        if (capacity < amount) {
            long wanted = amount;
            source.sendFailure(Messages.bad("Your inventory cannot hold "
                    + currency.describe(wanted) + "."));
            source.sendFailure(Messages.bad("Free space: " + Wallet.format(capacity)));
            source.sendFailure(Messages.bad("Withdrawal cancelled."));
            return 0;
        }

        TxResult result = economy.withdraw(player.getUUID(), currency, amount);
        if (!result.ok()) {
            source.sendFailure(Messages.bad(explain(result)));
            return 0;
        }

        long dropped = ItemBank.give(player, item, amount);
        if (dropped > 0) {
            // Capacity was checked, so this should be unreachable. If it ever fires,
            // the items are on the floor rather than deleted, and the log says so.
            source.sendSuccess(() -> Messages.body(Wallet.format(dropped)
                    + " did not fit and was dropped at your feet."), false);
            CobbleEconomyMod.LOG.warn("Withdrawal for {} overflowed capacity by {}",
                    player.getName().getString(), dropped);
        }

        long amountTaken = amount;
        source.sendSuccess(() -> Messages.good("Withdrew ")
                .append(Messages.priced(currency, amountTaken))
                .append(Messages.good(".")), false);
        source.sendSuccess(() -> Messages.balanceLine(currency, result.actorBalance()), false);
        log.tx(player.getName().getString() + " withdrew " + amount + " " + currency.id()
                + " (balance " + result.actorBalance() + ")");
        return 1;
    }

    // ---- /pay <player> <amount> <currency> ---------------------------------

    private int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        Optional<Currency> maybeCurrency = resolveCurrency(ctx);
        if (maybeCurrency.isEmpty()) return 0;
        Currency currency = maybeCurrency.get();

        String targetName = StringArgumentType.getString(ctx, "player");
        OptionalLong parsed = parseAmount(source, StringArgumentType.getString(ctx, "amount"));
        if (parsed.isEmpty()) return 0;
        long amount = parsed.getAsLong();

        // Online first so a player who just joined is payable before their name has
        // been written to disk; the cache covers everyone else.
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(targetName);
        Optional<UUID> target = online != null
                ? Optional.of(online.getUUID())
                : names.resolve(targetName);

        if (target.isEmpty()) {
            // Reject rather than guess. Paying a UUID we invented would move real
            // money into an account nobody can log into.
            source.sendFailure(Messages.bad("No player named '" + targetName
                    + "' has been seen on this server."));
            return 0;
        }

        UUID targetId = target.get();
        TxResult result = economy.transfer(sender.getUUID(), targetId, currency, amount);
        if (!result.ok()) {
            source.sendFailure(Messages.bad(explain(result)));
            return 0;
        }

        String display = online != null ? online.getName().getString() : names.nameOf(targetId);

        source.sendSuccess(() -> Messages.good("Paid ")
                .append(Messages.body(display)).append(Messages.good(" "))
                .append(Messages.priced(currency, amount))
                .append(Messages.good(".")), false);
        source.sendSuccess(() -> Messages.body("Remaining balance: ")
                .append(Messages.amount(currency, result.actorBalance())), false);

        if (online != null) {
            Chime.paymentReceived(online);
            online.sendSystemMessage(Messages.good("Received ")
                    .append(Messages.priced(currency, amount))
                    .append(Messages.good(" from "))
                    .append(Messages.body(sender.getName().getString()))
                    .append(Messages.good(".")));
            online.sendSystemMessage(Messages.balanceLine(currency, result.targetBalance()));
        } else {
            source.sendSuccess(() -> Messages.body(display
                    + " is offline; the funds have been deposited into their bank."), false);
        }

        log.tx(sender.getName().getString() + " paid " + display + " " + amount + " " + currency.id());
        return 1;
    }

    // ---- /baltop ------------------------------------------------------------

    private static final int PER_PAGE = 10;

    /** The combined view: a short board for every currency at once. */
    private int baltopAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID viewer = viewerOf(source);

        source.sendSuccess(Messages::rule, false);
        boolean anything = false;
        for (Currency currency : currencies.all()) {
            List<LeaderboardService.Rank> rows = leaderboard.top(currency, 3);
            if (rows.isEmpty()) continue;
            anything = true;
            source.sendSuccess(() -> Messages.title(boardTitle(currency)), false);
            sendRows(source, currency, rows, viewer);
            source.sendSuccess(Messages::blank, false);
        }
        if (!anything) {
            source.sendSuccess(() -> Messages.body("Nobody has banked anything yet."), false);
        }
        source.sendSuccess(() -> Messages.body("/baltop <currency> for the full list, "
                + "/baltop me for your rank"), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private int baltopOne(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack source = ctx.getSource();
        Optional<Currency> maybeCurrency = resolveCurrency(ctx);
        if (maybeCurrency.isEmpty()) return 0;
        Currency currency = maybeCurrency.get();
        UUID viewer = viewerOf(source);

        int offset = (page - 1) * PER_PAGE;
        List<LeaderboardService.Rank> rows = leaderboard.top(currency, PER_PAGE, offset);
        if (rows.isEmpty()) {
            source.sendFailure(Messages.bad(page == 1
                    ? "Nobody has banked any " + currency.plural() + " yet."
                    : "No players on page " + page + "."));
            return 0;
        }

        int participants = leaderboard.participants(currency);
        int totalPages = Math.max(1, (participants + PER_PAGE - 1) / PER_PAGE);

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title(boardTitle(currency)), false);
        source.sendSuccess(Messages::blank, false);
        sendRows(source, currency, rows, viewer);
        source.sendSuccess(Messages::blank, false);
        source.sendSuccess(() -> Messages.body("Page " + page + " of " + totalPages
                + "   Banked in total: " + Wallet.format(economy.totalSupply(currency))), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    /** {@code /baltop me}: the question the top-3 view cannot answer. */
    private int myRank(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID viewer = viewerOf(source);
        if (viewer == null) {
            source.sendFailure(Messages.bad("Only a player has a rank."));
            return 0;
        }

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("YOUR RANK"), false);
        source.sendSuccess(Messages::blank, false);
        for (Currency currency : currencies.all()) {
            long balance = economy.getBalance(viewer, currency);
            int rank = leaderboard.rankOf(viewer, currency);
            source.sendSuccess(() -> Messages.body(currency.displayName()), false);
            source.sendSuccess(() -> Messages.body("  Rank: ")
                    .append(Component.literal(rank > 0
                            ? "#" + rank + " of " + leaderboard.participants(currency)
                            : "unranked").withStyle(Messages.colourOf(currency))), false);
            source.sendSuccess(() -> Messages.body("  Balance: ")
                    .append(Messages.amount(currency, balance)), false);
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private void sendRows(CommandSourceStack source, Currency currency,
                          List<LeaderboardService.Rank> rows, UUID viewer) {
        for (LeaderboardService.Rank row : rows) {
            boolean isViewer = row.player().equals(viewer);
            source.sendSuccess(() -> Messages.rankLine(row.rank(), names.nameOf(row.player()),
                    currency, row.balance(), isViewer), false);
        }
    }

    private static String boardTitle(Currency currency) {
        return currency.displayName().toUpperCase(java.util.Locale.ROOT) + " TOP";
    }

    private static UUID viewerOf(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? null : player.getUUID();
    }

    // ---- shared -------------------------------------------------------------

    private Optional<Currency> resolveCurrency(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "currency");
        Optional<Currency> currency = currencies.resolve(raw);
        if (currency.isEmpty()) {
            ctx.getSource().sendFailure(Messages.bad("Unknown currency '" + raw
                    + "'. Try: " + String.join(", ", currencies.suggestions())));
        }
        return currency;
    }

    /**
     * The currency's item, or a complaint. Absent only if a currency names an item the
     * registry does not have, which is a server misconfiguration rather than anything
     * a player did.
     */
    private Optional<Item> itemFor(Currency currency) {
        Optional<Item> item = ItemBank.itemFor(currency);
        if (item.isEmpty()) {
            CobbleEconomyMod.LOG.error("Currency {} names unknown item {}",
                    currency.id(), currency.itemId());
        }
        return item;
    }

    /**
     * Parses a positive amount, or explains the problem and returns empty. Never
     * throws -- a typo is not an error condition, it is a sentence.
     */
    static OptionalLong parseAmount(CommandSourceStack source, String raw) {
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            source.sendFailure(Messages.bad("'" + raw + "' is not a number."));
            return OptionalLong.empty();
        }
        if (value == 0) {
            source.sendFailure(Messages.bad("Amount must be more than zero."));
            return OptionalLong.empty();
        }
        if (value < 0) {
            source.sendFailure(Messages.bad("Amount must be positive."));
            return OptionalLong.empty();
        }
        return OptionalLong.of(value);
    }

    /** Turn a rejected result into something worth reading. */
    static String explain(TxResult result) {
        String currency = result.currency() == null ? "currency" : result.currency().plural();
        return switch (result.status()) {
            case INSUFFICIENT_FUNDS -> "Insufficient funds. You have "
                    + Wallet.format(result.actorBalance()) + " " + currency + ".";
            case SELF_TRANSFER -> "You cannot pay yourself.";
            case INVALID_AMOUNT -> "That is not a valid amount.";
            case OVERFLOW -> "That would exceed the maximum balance.";
            case UNKNOWN_CURRENCY -> "That currency does not exist on this server.";
            case OK -> "";
        };
    }

    // ---- tab completion -----------------------------------------------------

    private SuggestionProvider<CommandSourceStack> currencySuggestions() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(currencies.suggestions(), builder);
    }

    /** Online players first, since that is who you are usually paying. */
    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (ctx, builder) -> {
            List<String> options = new ArrayList<>(ctx.getSource().getOnlinePlayerNames());
            for (String name : names.knownNames()) {
                if (!options.contains(name)) options.add(name);
            }
            return SharedSuggestionProvider.suggest(options, builder);
        };
    }
}
