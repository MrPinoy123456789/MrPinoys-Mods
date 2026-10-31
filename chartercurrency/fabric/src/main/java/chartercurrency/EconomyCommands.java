package chartercurrency;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.Denomination;
import chartercurrency.core.EconomyService;
import chartercurrency.core.LeaderboardService;
import chartercurrency.core.TxResult;
import chartercurrency.core.Wallet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The player-facing commands.
 *
 * <p>This layer holds no economic logic. It parses text, calls {@link EconomyService}
 * or {@link LeaderboardService}, and renders the answer. The coin arithmetic (which
 * coins to take for a deposit, how to make change, whether a withdrawal fits) is in
 * {@link Wallet}, under test.
 *
 * <p>Amounts are parsed as strings rather than with Brigadier's numeric argument so
 * that {@code abc} and {@code -500} each get a sentence explaining what went wrong
 * instead of a red parse error pointing at a caret.
 *
 * <p>Coin amounts are in Marks unless a denomination word follows the number:
 * {@code /withdraw 200} is 200 Marks and {@code /withdraw 3 sovereigns} is three coins.
 * Valor is accepted in the same slot so it can be refused with a sentence rather than
 * a parse error.
 */
public final class EconomyCommands {

    private static final String VALOR_NOT_ITEM =
            "Valor is not an item. It is earned, and it stays in your account.";
    private static final String VALOR_NOT_PAYABLE =
            "Valor cannot be paid to other players.";

    private final EconomyService economy;
    private final LeaderboardService leaderboard;
    private final CurrencyRegistry currencies;
    private final NameCache names;
    private final TransactionLog log;
    private final EconomySettings settings;
    private final Currency coin;

    public EconomyCommands(EconomyService economy, LeaderboardService leaderboard,
                           CurrencyRegistry currencies, NameCache names,
                           TransactionLog log, EconomySettings settings) {
        this.economy = economy;
        this.leaderboard = leaderboard;
        this.currencies = currencies;
        this.names = names;
        this.log = log;
        this.settings = settings;
        this.coin = currencies.coin();
    }

    /** Services that arrive after construction: the kingdom lookup behind /balance. */
    private Services services;

    void attach(Services services) {
        this.services = services;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(balanceCommand("balance"));
        dispatcher.register(balanceCommand("bal"));

        // /bank, /bank all [denomination], /bank <amount> [denomination]
        dispatcher.register(Commands.literal("bank")
                .executes(this::panel)
                .then(Commands.literal("help").executes(this::panel))
                .then(Commands.literal("all")
                        .executes(ctx -> deposit(ctx, true, null))
                        .then(Commands.argument("denomination", StringArgumentType.word())
                                .suggests(wordSuggestions())
                                .executes(ctx -> deposit(ctx, true, "denomination"))))
                .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(ctx -> deposit(ctx, false, null))
                        .then(Commands.argument("denomination", StringArgumentType.word())
                                .suggests(wordSuggestions())
                                .executes(ctx -> deposit(ctx, false, "denomination")))));

        // /withdraw <amount> [denomination]
        dispatcher.register(Commands.literal("withdraw")
                .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(ctx -> withdraw(ctx, null))
                        .then(Commands.argument("denomination", StringArgumentType.word())
                                .suggests(wordSuggestions())
                                .executes(ctx -> withdraw(ctx, "denomination")))));

        // /pay <player> <amount> [denomination]
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(playerSuggestions())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> pay(ctx, null))
                                .then(Commands.argument("denomination", StringArgumentType.word())
                                        .suggests(wordSuggestions())
                                        .executes(ctx -> pay(ctx, "denomination"))))));

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

            if (settings.showRankOnBalance && settings.leaderboardsEnabled && balance > 0) {
                int rank = leaderboard.rankOf(player.getUUID(), currency);
                if (rank > 0) {
                    source.sendSuccess(() -> Messages.body("  Rank: #" + rank), false);
                }
            }
        }
        if (services != null) {
            // The kingdom and its Renown, asked of Charter (or the stand-in) right now.
            try {
                Optional<String> kingdom = services.kingdoms.source().kingdomOf(player.getUUID());
                if (kingdom.isPresent()) {
                    String id = kingdom.get();
                    String name = services.kingdoms.source().displayName(id);
                    source.sendSuccess(() -> Messages.body("Kingdom: " + name + "   Renown: "
                            + Wallet.format(services.treasury.renown(id))), false);
                } else {
                    source.sendSuccess(() -> Messages.body("Kingdom: none"), false);
                }
            } catch (RuntimeException e) {
                CharterCurrencyMod.LOG.warn("Kingdom lookup failed for /balance", e);
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
        ctx.getSource().sendSuccess(() -> Messages.balanceLine(currency.get(), balance), false);
        return 1;
    }

    // ---- /bank with no arguments -------------------------------------------

    private int panel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("BANK"), false);
        source.sendSuccess(Messages::blank, false);
        for (Currency currency : currencies.all()) {
            long balance = economy.getBalance(player.getUUID(), currency);
            source.sendSuccess(() -> Messages.balanceLine(currency, balance), false);
        }
        long carried = carriedMarks(player);
        source.sendSuccess(() -> Messages.body("Carrying: ")
                .append(Messages.priced(coin, carried)), false);
        source.sendSuccess(Messages::blank, false);
        source.sendSuccess(() -> Messages.helpLine("/bank all", "Deposit every coin you carry"), false);
        source.sendSuccess(() -> Messages.helpLine("/bank <amount> [crowns]", "Deposit an amount"), false);
        source.sendSuccess(() -> Messages.helpLine("/withdraw <amount> [crowns]", "Take coins out"), false);
        source.sendSuccess(() -> Messages.helpLine("/pay <player> <amount> [crowns]", "Send coin"), false);
        source.sendSuccess(() -> Messages.helpLine("/shop", "What the server sells"), false);
        if (settings.leaderboardsEnabled) {
            source.sendSuccess(() -> Messages.helpLine("/baltop", "Richest players"), false);
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    // ---- /bank --------------------------------------------------------------

    /**
     * Coin to bank: coins first, balance second.
     *
     * <p>If the server dies between the two the player has lost coins, which is bad but
     * is not money created from nothing. Only one of those failure modes is worth
     * farming.
     */
    private int deposit(CommandContext<CommandSourceStack> ctx, boolean all, String wordArg)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        Denomination only = null;
        if (wordArg != null) {
            Optional<CurrencyRegistry.Word> word = resolveWord(source,
                    StringArgumentType.getString(ctx, wordArg));
            if (word.isEmpty()) return 0;
            if (word.get().currency() != coin) {
                source.sendFailure(Messages.bad(VALOR_NOT_ITEM));
                return 0;
            }
            only = word.get().denomination();
        }

        List<Denomination> denominations = coin.denominations();
        int[] faces = coin.faceValues();
        int[] counts = new int[faces.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = (int) Math.min(Integer.MAX_VALUE, countOf(player, denominations.get(i)));
        }

        Wallet.DepositPlan plan;
        long amount;
        if (all) {
            int[] usable = counts.clone();
            if (only != null) {
                for (int i = 0; i < usable.length; i++) {
                    if (!denominations.get(i).equals(only)) usable[i] = 0;
                }
            }
            amount = 0;
            for (int i = 0; i < usable.length; i++) amount += (long) usable[i] * faces[i];
            if (amount <= 0) {
                source.sendFailure(Messages.bad(only == null
                        ? "You are not carrying any coin."
                        : "You are not carrying any " + only.shortPlural() + "."));
                if (only == null) plainNuggetNote(source, player);
                return 0;
            }
            plan = Wallet.planDeposit(usable, faces, amount);
        } else {
            OptionalLong parsed = parseAmount(source, StringArgumentType.getString(ctx, "amount"));
            if (parsed.isEmpty()) return 0;
            if (only != null) {
                // "3 crowns": exactly three Iron Crowns, no other coin touched.
                int idx = denominations.indexOf(only);
                long n = parsed.getAsLong();
                if (counts[idx] < n) {
                    source.sendFailure(Messages.bad("You only carry " + only.describe(counts[idx]) + "."));
                    return 0;
                }
                int[] take = new int[faces.length];
                take[idx] = (int) n;
                amount = n * faces[idx];
                plan = new Wallet.DepositPlan(take, amount, 0, new int[faces.length]);
            } else {
                amount = parsed.getAsLong();
                plan = Wallet.planDeposit(counts, faces, amount);
                if (plan == null) {
                    long carried = carriedMarks(player);
                    source.sendFailure(Messages.bad("You only carry " + coin.describe(carried) + "."));
                    return 0;
                }
            }
        }
        if (plan == null) {
            source.sendFailure(Messages.bad("Could not bank that. Nothing was changed."));
            return 0;
        }

        // Take the coins, all or nothing across denominations.
        List<int[]> removed = new ArrayList<>();
        for (int i = 0; i < plan.take().length; i++) {
            if (plan.take()[i] <= 0) continue;
            Denomination d = denominations.get(i);
            if (!ItemBank.removeExactly(player, coinMatch(d), plan.take()[i])) {
                giveBack(player, denominations, removed);
                source.sendFailure(Messages.bad("Could not bank that. Nothing was changed."));
                return 0;
            }
            removed.add(new int[] {i, plan.take()[i]});
        }

        long credited = plan.credited();
        TxResult result = economy.deposit(player.getUUID(), coin, credited);
        if (!result.ok()) {
            // The bank refused after the coins were gone. Hand them straight back.
            giveBack(player, denominations, removed);
            source.sendFailure(Messages.bad("Could not bank that. Nothing was changed."));
            return 0;
        }

        // Change comes back as coins. giveOrDrop: nothing is ever deleted.
        if (plan.change() > 0) {
            for (int i = 0; i < plan.changeCoins().length; i++) {
                if (plan.changeCoins()[i] <= 0) continue;
                long dropped = ItemBank.give(player,
                        CoinStacks.make(denominations.get(i), coin, 1), plan.changeCoins()[i]);
                if (dropped > 0) {
                    CharterCurrencyMod.LOG.warn("Change for {} overflowed capacity by {}",
                            player.getName().getString(), dropped);
                }
            }
        }

        source.sendSuccess(() -> Messages.good("Deposited ")
                .append(Messages.priced(coin, credited))
                .append(Messages.good(".")), false);
        if (plan.change() > 0) {
            source.sendSuccess(() -> Messages.body("Change: " + coin.describe(plan.change())), false);
        }
        long balance = result.actorBalance();
        source.sendSuccess(() -> Messages.balanceLine(coin, balance), false);
        if (all && only == null) plainNuggetNote(source, player);
        log.tx(player.getName().getString() + " deposited " + credited + " coin"
                + (plan.change() > 0 ? " (change " + plan.change() + ")" : "")
                + " (balance " + balance + ")");
        return 1;
    }

    private void plainNuggetNote(CommandSourceStack source, ServerPlayer player) {
        if (ItemBank.count(player, stack -> CoinStacks.isPlainNugget(stack, coin)) > 0) {
            source.sendSuccess(() -> Messages.body(
                    "Plain nuggets are not coin and were left alone."), false);
        }
    }

    private void giveBack(ServerPlayer player, List<Denomination> denominations, List<int[]> removed) {
        for (int[] r : removed) {
            ItemBank.give(player, CoinStacks.make(denominations.get(r[0]), coin, 1), r[1]);
        }
    }

    // ---- /withdraw <amount> [denomination] ---------------------------------

    private int withdraw(CommandContext<CommandSourceStack> ctx, String wordArg)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        Denomination named = null;
        if (wordArg != null) {
            Optional<CurrencyRegistry.Word> word = resolveWord(source,
                    StringArgumentType.getString(ctx, wordArg));
            if (word.isEmpty()) return 0;
            if (word.get().currency() != coin || !word.get().currency().withdrawable()) {
                source.sendFailure(Messages.bad(VALOR_NOT_ITEM));
                return 0;
            }
            named = word.get().denomination();
        }
        OptionalLong parsed = parseAmount(source, StringArgumentType.getString(ctx, "amount"));
        if (parsed.isEmpty()) return 0;

        List<Denomination> denominations = coin.denominations();
        int[] faces = coin.faceValues();
        int[] needed;
        long marks;
        if (named != null) {
            int idx = denominations.indexOf(named);
            needed = new int[faces.length];
            if (parsed.getAsLong() > Integer.MAX_VALUE) {
                source.sendFailure(Messages.bad("That is too many coins."));
                return 0;
            }
            needed[idx] = (int) parsed.getAsLong();
            marks = parsed.getAsLong() * faces[idx];
        } else {
            marks = parsed.getAsLong();
            needed = Wallet.makeChange(marks, faces);
        }

        long balance = economy.getBalance(player.getUUID(), coin);
        if (balance < marks) {
            source.sendFailure(Messages.bad("Insufficient funds. Your balance is "
                    + coin.describe(balance) + "."));
            return 0;
        }

        // Space before money, jointly across denominations: they compete for the same
        // empty slots, so each one's own free capacity would overstate the room.
        long[] room = new long[faces.length];
        long[] need = new long[faces.length];
        int maxStack = 64;
        for (int i = 0; i < faces.length; i++) {
            ItemStack proto = CoinStacks.make(denominations.get(i), coin, 1);
            room[i] = ItemBank.partialRoom(player, proto);
            need[i] = needed[i];
            maxStack = Math.min(maxStack, proto.getMaxStackSize());
        }
        if (!Wallet.fitsAll(ItemBank.emptySlots(player), room, need, maxStack)) {
            source.sendFailure(Messages.bad("Your inventory cannot hold " + coin.describe(marks) + "."));
            source.sendFailure(Messages.bad("Withdrawal cancelled."));
            return 0;
        }

        TxResult result = economy.withdraw(player.getUUID(), coin, marks);
        if (!result.ok()) {
            source.sendFailure(Messages.bad(explain(result)));
            return 0;
        }

        for (int i = 0; i < needed.length; i++) {
            if (needed[i] <= 0) continue;
            long dropped = ItemBank.give(player, CoinStacks.make(denominations.get(i), coin, 1), needed[i]);
            if (dropped > 0) {
                // Capacity was checked, so this should be unreachable. The coins are on
                // the floor rather than deleted, and the log says so.
                source.sendSuccess(() -> Messages.body(Wallet.format(dropped)
                        + " did not fit and was dropped at your feet."), false);
                CharterCurrencyMod.LOG.warn("Withdrawal for {} overflowed capacity by {}",
                        player.getName().getString(), dropped);
            }
        }

        long taken = marks;
        source.sendSuccess(() -> Messages.good("Withdrew ")
                .append(Messages.priced(coin, taken))
                .append(Messages.good(".")), false);
        source.sendSuccess(() -> Messages.balanceLine(coin, result.actorBalance()), false);
        log.tx(player.getName().getString() + " withdrew " + marks + " coin"
                + " (balance " + result.actorBalance() + ")");
        return 1;
    }

    // ---- /pay <player> <amount> [denomination] -----------------------------

    private int pay(CommandContext<CommandSourceStack> ctx, String wordArg) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();

        long multiplier = 1;
        if (wordArg != null) {
            Optional<CurrencyRegistry.Word> word = resolveWord(source,
                    StringArgumentType.getString(ctx, wordArg));
            if (word.isEmpty()) return 0;
            if (!word.get().currency().payable()) {
                source.sendFailure(Messages.bad(VALOR_NOT_PAYABLE));
                return 0;
            }
            if (word.get().denomination() != null) multiplier = word.get().denomination().value();
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        OptionalLong parsed = parseAmount(source, StringArgumentType.getString(ctx, "amount"));
        if (parsed.isEmpty()) return 0;
        long amount;
        try {
            amount = Math.multiplyExact(parsed.getAsLong(), multiplier);
        } catch (ArithmeticException e) {
            source.sendFailure(Messages.bad("That is not a valid amount."));
            return 0;
        }

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
        TxResult result = economy.transfer(sender.getUUID(), targetId, coin, amount);
        if (!result.ok()) {
            source.sendFailure(Messages.bad(explain(result)));
            return 0;
        }

        String display = online != null ? online.getName().getString() : names.nameOf(targetId);

        source.sendSuccess(() -> Messages.good("Paid ")
                .append(Messages.body(display)).append(Messages.good(" "))
                .append(Messages.priced(coin, amount))
                .append(Messages.good(".")), false);
        source.sendSuccess(() -> Messages.body("Remaining balance: ")
                .append(Messages.priced(coin, result.actorBalance())), false);

        if (online != null) {
            Chime.paymentReceived(online);
            online.sendSystemMessage(Messages.good("Received ")
                    .append(Messages.priced(coin, amount))
                    .append(Messages.good(" from "))
                    .append(Messages.body(sender.getName().getString()))
                    .append(Messages.good(".")));
            online.sendSystemMessage(Messages.balanceLine(coin, result.targetBalance()));
        } else {
            source.sendSuccess(() -> Messages.body(display
                    + " is offline; the funds have been deposited into their bank."), false);
        }

        log.tx(sender.getName().getString() + " paid " + display + " " + amount + " coin");
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
        source.sendSuccess(() -> Messages.body("/baltop <coin|valor> for the full list, "
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
                    ? "Nobody holds any " + currency.displayName() + " yet."
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
                    .append(Messages.priced(currency, balance)), false);
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

    private Predicate<ItemStack> coinMatch(Denomination d) {
        return stack -> CoinStacks.isCoin(stack, d);
    }

    private long countOf(ServerPlayer player, Denomination d) {
        return ItemBank.count(player, coinMatch(d));
    }

    /** Marks carried as marked coins in the main 36 slots. */
    private long carriedMarks(ServerPlayer player) {
        long total = 0;
        for (Denomination d : coin.denominations()) total += countOf(player, d) * d.value();
        return total;
    }

    private Optional<Currency> resolveCurrency(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "currency");
        Optional<Currency> currency = currencies.resolve(raw);
        if (currency.isEmpty()) {
            ctx.getSource().sendFailure(Messages.bad("Unknown currency '" + raw
                    + "'. Try: " + String.join(", ", currencies.suggestions())));
        }
        return currency;
    }

    /** A denomination or currency word, or a complaint. */
    private Optional<CurrencyRegistry.Word> resolveWord(CommandSourceStack source, String raw) {
        Optional<CurrencyRegistry.Word> word = currencies.resolveWord(raw);
        if (word.isEmpty()) {
            source.sendFailure(Messages.bad("Unknown denomination '" + raw
                    + "'. Try: marks, crowns, sovereigns or coin."));
        }
        return word;
    }

    /**
     * Parses a positive amount, or explains the problem and returns empty. Never
     * throws; a typo is not an error condition, it is a sentence.
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
        String currency = result.currency() == null ? "currency" : result.currency().displayName();
        return switch (result.status()) {
            case INSUFFICIENT_FUNDS -> result.currency() == null
                    ? "Insufficient funds."
                    : "Insufficient funds. You have " + result.currency().describe(result.actorBalance()) + ".";
            case SELF_TRANSFER -> "You cannot pay yourself.";
            case INVALID_AMOUNT -> "That is not a valid amount.";
            case OVERFLOW -> "That would exceed the maximum balance.";
            case UNKNOWN_CURRENCY -> "That currency does not exist on this server.";
            case NOT_PAYABLE -> currency + " cannot be paid to other players.";
            case OK -> "";
        };
    }

    // ---- tab completion -----------------------------------------------------

    private SuggestionProvider<CommandSourceStack> currencySuggestions() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(currencies.suggestions(), builder);
    }

    private SuggestionProvider<CommandSourceStack> wordSuggestions() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(currencies.denominationWords(), builder);
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
