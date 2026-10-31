package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.Denomination;
import chartercurrency.core.KingdomSource;
import chartercurrency.core.Treasury;
import chartercurrency.core.Wallet;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code /treasury} and {@code /mint}: the player-facing side of kingdom coin.
 *
 * <p>Anyone in the kingdom can deposit; only the Lord (and ops) can withdraw, decided by
 * {@link Treasury#mayWithdraw}. Every movement is in the ledger, so the treasury can be
 * spent but never quietly. This class moves coin; the rights belong to Charter.
 */
final class TreasuryCommands {

    private static final int PER_PAGE = 8;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Services s;
    private final MintListener mintListener;

    TreasuryCommands(Services services, MintListener mintListener) {
        this.s = services;
        this.mintListener = mintListener;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mint").executes(ctx -> {
            mintListener.showAllowance(ctx.getSource().getPlayerOrException());
            return 1;
        }));

        dispatcher.register(Commands.literal("treasury")
                .executes(ctx -> overview(ctx, null))
                .then(Commands.literal("deposit")
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> deposit(ctx, null))
                                .then(Commands.argument("denomination", StringArgumentType.word())
                                        .suggests(words())
                                        .executes(ctx -> deposit(ctx, "denomination")))))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> withdraw(ctx, null, null))
                                .then(Commands.argument("denomination", StringArgumentType.word())
                                        .suggests(words())
                                        .executes(ctx -> withdraw(ctx, "denomination", null))
                                        .then(Commands.argument("kingdom", StringArgumentType.word())
                                                .suggests(kingdoms())
                                                .executes(ctx -> withdraw(ctx, "denomination", "kingdom"))))))
                .then(Commands.literal("ledger")
                        .executes(ctx -> ledger(ctx, ""))
                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                .executes(ctx -> ledger(ctx, StringArgumentType.getString(ctx, "options")))))
                .then(Commands.argument("kingdom", StringArgumentType.word())
                        .suggests(kingdoms())
                        .executes(ctx -> overview(ctx, "kingdom"))));
    }

    // ---- which organization -------------------------------------------------

    static boolean isOp(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    private KingdomSource kingdomSource() {
        return s.kingdoms.source();
    }

    /** A named organization (ops only) or the player's own kingdom. Complains on failure. */
    private Optional<String> orgFor(CommandSourceStack source, String named) {
        if (named != null) {
            if (!isOp(source)) {
                source.sendFailure(Messages.bad("Only ops can name a kingdom here."));
                return Optional.empty();
            }
            Optional<String> id = resolveOrg(named);
            if (id.isEmpty()) source.sendFailure(Messages.bad("No kingdom called '" + named + "'."));
            return id;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Messages.bad("Name a kingdom: this needs one from the console."));
            return Optional.empty();
        }
        try {
            Optional<String> id = kingdomSource().kingdomOf(player.getUUID());
            if (id.isEmpty()) source.sendFailure(Messages.bad("You are not in a kingdom."));
            return id;
        } catch (RuntimeException e) {
            CharterCurrencyMod.LOG.warn("Kingdom lookup failed", e);
            source.sendFailure(Messages.bad("The kingdom records cannot be reached right now."));
            return Optional.empty();
        }
    }

    /** Match a typed kingdom id or display name against what the source knows. */
    Optional<String> resolveOrg(String typed) {
        for (String id : kingdomSource().kingdomIds()) {
            if (id.equalsIgnoreCase(typed)
                    || kingdomSource().displayName(id).equalsIgnoreCase(typed)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    // ---- /treasury ----------------------------------------------------------

    private int overview(CommandContext<CommandSourceStack> ctx, String kingdomArg) {
        CommandSourceStack source = ctx.getSource();
        Optional<String> org = orgFor(source, kingdomArg == null ? null
                : StringArgumentType.getString(ctx, kingdomArg));
        if (org.isEmpty()) return 0;
        Currency coin = s.coin();
        String name = kingdomSource().displayName(org.get());

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title(name.toUpperCase(Locale.ROOT) + " TREASURY"), false);
        source.sendSuccess(() -> Messages.body("Balance: ")
                .append(Messages.priced(coin, s.treasury.balance(org.get()))), false);
        source.sendSuccess(() -> Messages.body("Mint tax collected, all time: ")
                .append(Messages.priced(coin, s.treasury.taxCollected(org.get()))), false);
        List<Treasury.Entry> recent = s.treasury.ledger(org.get(), "coin");
        if (!recent.isEmpty()) {
            source.sendSuccess(() -> Messages.body("Recent:"), false);
            for (int i = 0; i < Math.min(5, recent.size()); i++) {
                Treasury.Entry e = recent.get(i);
                source.sendSuccess(() -> Messages.body("  " + line(e)), false);
            }
        }
        source.sendSuccess(() -> Messages.body("/treasury deposit <amount> [crowns]   /treasury ledger"), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private static String line(Treasury.Entry e) {
        String when = WHEN.format(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()));
        String unit = "renown".equals(e.currency) ? "Renown" : "Marks";
        String sign = e.delta >= 0 ? "+" : "";
        return when + "  " + sign + Wallet.format(e.delta) + " " + unit + "  " + e.kind
                + (e.actor == null || e.actor.isBlank() ? "" : " by " + e.actor);
    }

    // ---- /treasury deposit --------------------------------------------------

    private int deposit(CommandContext<CommandSourceStack> ctx, String wordArg) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        Currency coin = s.coin();
        Optional<String> org = orgFor(source, null);
        if (org.isEmpty()) return 0;

        Denomination only = null;
        if (wordArg != null) {
            Optional<CurrencyRegistry.Word> word = s.currencies.resolveWord(
                    StringArgumentType.getString(ctx, wordArg));
            if (word.isEmpty() || word.get().currency() != coin) {
                source.sendFailure(Messages.bad("Only coin can go in the treasury."));
                return 0;
            }
            only = word.get().denomination();
        }

        String raw = StringArgumentType.getString(ctx, "amount");
        boolean all = raw.equalsIgnoreCase("all");
        long amount = 0;
        if (!all) {
            OptionalLong parsed = EconomyCommands.parseAmount(source, raw);
            if (parsed.isEmpty()) return 0;
            amount = parsed.getAsLong();
        }

        String[] error = new String[1];
        var plan = CoinOps.plan(player, coin, all, amount, only, error);
        if (plan == null) {
            source.sendFailure(Messages.bad(error[0] == null ? "Could not deposit that." : error[0]));
            return 0;
        }
        // Coins first, treasury second; the same order as /bank.
        if (!CoinOps.remove(player, coin, plan)) {
            source.sendFailure(Messages.bad("Could not deposit that. Nothing was changed."));
            return 0;
        }
        long credited = plan.credited();
        if (!s.treasury.credit(org.get(), credited, "deposit", player.getName().getString(), "")) {
            CoinOps.give(player, coin, plan.take());
            source.sendFailure(Messages.bad("Could not deposit that. Nothing was changed."));
            return 0;
        }
        if (plan.change() > 0) CoinOps.give(player, coin, plan.changeCoins());

        source.sendSuccess(() -> Messages.good("Deposited ").append(Messages.priced(coin, credited))
                .append(Messages.good(" into the " + kingdomSource().displayName(org.get()) + " treasury.")), false);
        if (plan.change() > 0) {
            source.sendSuccess(() -> Messages.body("Change: " + coin.describe(plan.change())), false);
        }
        s.log.tx(player.getName().getString() + " deposited " + credited + " Marks into the "
                + org.get() + " treasury (balance " + s.treasury.balance(org.get()) + ")");
        return 1;
    }

    // ---- /treasury withdraw -------------------------------------------------

    private int withdraw(CommandContext<CommandSourceStack> ctx, String wordArg, String kingdomArg)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        Currency coin = s.coin();
        boolean op = isOp(source);

        Optional<String> org = orgFor(source,
                kingdomArg == null ? null : StringArgumentType.getString(ctx, kingdomArg));
        if (org.isEmpty()) return 0;

        // The one permission check: the Lord, or an op. Charter owns who the Lord is.
        boolean allowed;
        try {
            allowed = Treasury.mayWithdraw(kingdomSource(), org.get(), player.getUUID(), op);
        } catch (RuntimeException e) {
            CharterCurrencyMod.LOG.warn("Lord lookup failed", e);
            source.sendFailure(Messages.bad("The kingdom records cannot be reached right now."));
            return 0;
        }
        if (!allowed) {
            source.sendFailure(Messages.bad("Only the Lord of " + kingdomSource().displayName(org.get())
                    + " can take coin from its treasury."));
            return 0;
        }

        Denomination named = null;
        if (wordArg != null) {
            Optional<CurrencyRegistry.Word> word = s.currencies.resolveWord(
                    StringArgumentType.getString(ctx, wordArg));
            if (word.isEmpty() || word.get().currency() != coin) {
                source.sendFailure(Messages.bad("Only coin can be taken from the treasury."));
                return 0;
            }
            named = word.get().denomination();
        }
        OptionalLong parsed = EconomyCommands.parseAmount(source, StringArgumentType.getString(ctx, "amount"));
        if (parsed.isEmpty()) return 0;

        int[] faces = coin.faceValues();
        int[] needed;
        long marks;
        if (named != null) {
            int idx = coin.denominations().indexOf(named);
            if (parsed.getAsLong() > Integer.MAX_VALUE) {
                source.sendFailure(Messages.bad("That is too many coins."));
                return 0;
            }
            needed = new int[faces.length];
            needed[idx] = (int) parsed.getAsLong();
            marks = parsed.getAsLong() * faces[idx];
        } else {
            marks = parsed.getAsLong();
            needed = Wallet.makeChange(marks, faces);
        }

        long balance = s.treasury.balance(org.get());
        if (balance < marks) {
            source.sendFailure(Messages.bad("The treasury holds only " + coin.describe(balance) + "."));
            return 0;
        }
        if (!CoinOps.fits(player, coin, needed)) {
            source.sendFailure(Messages.bad("Your inventory cannot hold " + coin.describe(marks) + "."));
            source.sendFailure(Messages.bad("Withdrawal cancelled."));
            return 0;
        }
        if (!s.treasury.charge(org.get(), marks, "withdraw", player.getName().getString(), "")) {
            source.sendFailure(Messages.bad("The treasury holds only "
                    + coin.describe(s.treasury.balance(org.get())) + "."));
            return 0;
        }
        long dropped = CoinOps.give(player, coin, needed);
        if (dropped > 0) {
            CharterCurrencyMod.LOG.warn("Treasury withdrawal for {} overflowed capacity by {}",
                    player.getName().getString(), dropped);
        }
        long taken = marks;
        source.sendSuccess(() -> Messages.good("Took ").append(Messages.priced(coin, taken))
                .append(Messages.good(" from the " + kingdomSource().displayName(org.get()) + " treasury.")), false);
        source.sendSuccess(() -> Messages.body("Treasury now: ")
                .append(Messages.priced(coin, s.treasury.balance(org.get()))), false);
        s.log.tx(player.getName().getString() + " withdrew " + marks + " Marks from the "
                + org.get() + " treasury (balance " + s.treasury.balance(org.get()) + ")");
        return 1;
    }

    // ---- /treasury ledger ---------------------------------------------------

    /** Options in any order: a currency (coin or renown), a page number, a kingdom (ops). */
    private int ledger(CommandContext<CommandSourceStack> ctx, String options) {
        CommandSourceStack source = ctx.getSource();
        String filter = null;
        int page = 1;
        String kingdom = null;
        for (String token : options.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.equalsIgnoreCase("coin") || token.equalsIgnoreCase("renown")) {
                filter = token.toLowerCase(Locale.ROOT);
            } else if (token.chars().allMatch(Character::isDigit)) {
                page = Math.max(1, Integer.parseInt(token));
            } else {
                kingdom = token;
            }
        }
        return showLedger(source, filter, page, kingdom);
    }

    /** The ledger page for the player's own kingdom, or a named one for ops. */
    int showLedger(CommandSourceStack source, String filter, int page, String kingdom) {
        Optional<String> org = kingdom != null && kingdom.equalsIgnoreCase(Treasury.COMMONS) && isOp(source)
                ? Optional.of(Treasury.COMMONS) : orgFor(source, kingdom);
        if (org.isEmpty()) return 0;

        List<Treasury.Entry> entries = s.treasury.ledger(org.get(), filter);
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages);
        int from = (page - 1) * PER_PAGE;
        String title = kingdomSource().displayName(org.get()).toUpperCase(Locale.ROOT) + " LEDGER";
        int shownPage = page;
        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title(title), false);
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Messages.body("Nothing recorded yet."), false);
        }
        for (int i = from; i < Math.min(entries.size(), from + PER_PAGE); i++) {
            Treasury.Entry e = entries.get(i);
            source.sendSuccess(() -> Messages.body(line(e)), false);
        }
        source.sendSuccess(() -> Messages.body("Page " + shownPage + " of " + pages
                + "   /treasury ledger [coin|renown] [page]"), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    // ---- completion ---------------------------------------------------------

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> words() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(s.currencies.denominationWords(), builder);
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> kingdoms() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(kingdomSource().kingdomIds(), builder);
    }
}
