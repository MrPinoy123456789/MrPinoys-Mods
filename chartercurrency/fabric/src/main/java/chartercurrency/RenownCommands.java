package chartercurrency;

import chartercurrency.core.EarnService;
import chartercurrency.core.KingdomSource;
import chartercurrency.core.Treasury;
import chartercurrency.core.Wallet;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The earn endpoint, the Renown commands and the Renown catalogue.
 *
 * <p>Every Valor and Renown award goes through {@code /chartercurrency earn}; Pocket
 * Dungeons reaches it through its {@code payoutCommand}. The rules are
 * {@link EarnService}, under test. Only the Lord of a kingdom (and ops) can spend its
 * Renown, and what a purchase does is whatever commands the catalogue entry lists.
 */
final class RenownCommands {

    private final Services s;
    private final TreasuryCommands treasuryCommands;

    RenownCommands(Services services, TreasuryCommands treasuryCommands) {
        this.s = services;
        this.treasuryCommands = treasuryCommands;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // earn, and the admin Renown adjustment, under the admin root
        dispatcher.register(Commands.literal("chartercurrency")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("earn")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(playerNames(c.getSource()), b))
                                .then(Commands.argument("source", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                s.earn.config().sources().keySet(), b))
                                        .executes(ctx -> earn(ctx, 0, null))
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0))
                                                .executes(ctx -> earn(ctx, IntegerArgumentType.getInteger(ctx, "level"), null))
                                                .then(Commands.argument("run", StringArgumentType.word())
                                                        .executes(ctx -> earn(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "level"),
                                                                StringArgumentType.getString(ctx, "run"))))))))
                .then(Commands.literal("renown")
                        .then(Commands.literal("add").then(renownTarget(true)))
                        .then(Commands.literal("remove").then(renownTarget(false)))));

        dispatcher.register(Commands.literal("renown")
                .executes(this::overview)
                .then(Commands.literal("catalog").executes(this::catalog))
                .then(Commands.literal("top").executes(this::top))
                .then(Commands.literal("ledger")
                        .executes(ctx -> treasuryCommands.showLedger(ctx.getSource(), "renown", 1, null))
                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                .executes(this::ledger)))
                .then(Commands.literal("buy")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        s.catalog.stream().map(Treasury.CatalogEntry::key).toList(), b))
                                .executes(ctx -> buy(ctx, ""))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .executes(ctx -> buy(ctx, StringArgumentType.getString(ctx, "options")))))));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> renownTarget(boolean add) {
        return Commands.argument("kingdom", StringArgumentType.string())
                .suggests((c, b) -> {
                    List<String> ids = new ArrayList<>(s.kingdoms.source().kingdomIds());
                    ids.add(Treasury.COMMONS);
                    return SharedSuggestionProvider.suggest(ids, b);
                })
                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                        .executes(ctx -> adjust(ctx, add)));
    }

    private List<String> playerNames(CommandSourceStack source) {
        List<String> out = new ArrayList<>(source.getOnlinePlayerNames());
        for (String n : s.names.knownNames()) if (!out.contains(n)) out.add(n);
        return out;
    }

    private KingdomSource src() {
        return s.kingdoms.source();
    }

    // ---- /chartercurrency earn ---------------------------------------------

    private int earn(CommandContext<CommandSourceStack> ctx, int level, String run) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        String sourceKey = StringArgumentType.getString(ctx, "source");

        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        Optional<UUID> id = online != null ? Optional.of(online.getUUID()) : s.names.resolve(name);
        if (id.isEmpty()) {
            source.sendFailure(Messages.bad("No player named '" + name + "' has been seen on this server."));
            return 0;
        }

        // The kingdom is asked at the moment of the award. If it cannot be answered,
        // nothing is awarded: Renown must not land in the wrong place.
        String kingdom;
        try {
            kingdom = src().kingdomOf(id.get()).orElse(null);
        } catch (RuntimeException e) {
            CharterCurrencyMod.LOG.warn("Kingdom lookup failed during an award", e);
            source.sendFailure(Messages.bad("The kingdom records cannot be reached. Nothing was awarded."));
            return 0;
        }

        String display = online != null ? online.getName().getString() : s.names.nameOf(id.get());
        EarnService.Result r = s.earn.award(id.get(), display, kingdom, sourceKey, level, run, s.settings.today());
        if (!r.ok()) {
            source.sendFailure(Messages.bad("Unknown earn source '" + sourceKey + "'. Try: "
                    + String.join(", ", s.earn.config().sources().keySet())));
            return 0;
        }

        String line = line(r, kingdom);
        if (online != null) online.sendSystemMessage(Messages.good(line));
        source.sendSuccess(() -> Messages.good("Awarded " + display + ": " + line), true);
        s.log.tx(display + " earned " + r.valor() + " Valor from " + sourceKey
                + (level > 0 ? " level " + level : "") + (run != null ? " run " + run : "")
                + "; Renown " + r.renown() + " to " + r.renownTarget() + " (" + r.outcome() + ")");
        return 1;
    }

    /** The one line the player sees (SPEC 6.5). */
    private String line(EarnService.Result r, String kingdom) {
        StringBuilder out = new StringBuilder("+" + Wallet.format(r.valor()) + " Valor");
        if (r.halved()) out.append(" (daily limit reached, half rate)");
        else if (r.hardCapped() && r.valor() == 0) out.append(" (daily limit reached)");
        out.append('.');
        switch (r.outcome()) {
            case CREDITED -> {
                if (kingdom == null) {
                    out.append(" You are not in a kingdom, so ").append(Wallet.format(r.renown()))
                            .append(" Renown went to the Commons.");
                } else {
                    out.append(" Your kingdom gained ").append(Wallet.format(r.renown())).append(" Renown.");
                }
            }
            case PROBATION -> out.append(" Renown starts counting for your kingdom in ")
                    .append(r.probationDaysLeft()).append(r.probationDaysLeft() == 1 ? " day." : " days.");
            default -> { }
        }
        return out.toString();
    }

    // ---- admin: renown add/remove ------------------------------------------

    private int adjust(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack source = ctx.getSource();
        String typed = StringArgumentType.getString(ctx, "kingdom");
        Optional<String> org = typed.equalsIgnoreCase(Treasury.COMMONS) ? Optional.of(Treasury.COMMONS)
                : treasuryCommands.resolveOrg(typed);
        if (org.isEmpty()) {
            source.sendFailure(Messages.bad("No kingdom called '" + typed + "'."));
            return 0;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        if (!s.treasury.adjustRenown(org.get(), add ? amount : -amount, source.getTextName())) {
            source.sendFailure(Messages.bad(add ? "That would exceed the maximum."
                    : "That kingdom only has " + Wallet.format(s.treasury.renown(org.get())) + " Renown."));
            return 0;
        }
        source.sendSuccess(() -> Messages.good((add ? "Added " : "Removed ") + Wallet.format(amount)
                + " Renown " + (add ? "to " : "from ") + org.get() + ". Now "
                + Wallet.format(s.treasury.renown(org.get())) + "."), true);
        s.log.admin(source.getTextName() + (add ? " added " : " removed ") + amount + " Renown "
                + (add ? "to " : "from ") + org.get());
        return 1;
    }

    // ---- /renown ------------------------------------------------------------

    private int overview(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String org = null;
        if (player != null) {
            try {
                org = src().kingdomOf(player.getUUID()).orElse(null);
            } catch (RuntimeException e) {
                source.sendFailure(Messages.bad("The kingdom records cannot be reached right now."));
                return 0;
            }
        }
        source.sendSuccess(Messages::rule, false);
        if (org == null) {
            source.sendSuccess(() -> Messages.title("RENOWN"), false);
            source.sendSuccess(() -> Messages.body("You are not in a kingdom. Renown you earn goes to the Commons ("
                    + Wallet.format(s.treasury.renown(Treasury.COMMONS)) + " so far)."), false);
        } else {
            String id = org;
            source.sendSuccess(() -> Messages.title(src().displayName(id).toUpperCase(Locale.ROOT) + " RENOWN"), false);
            source.sendSuccess(() -> Messages.body("Balance: " + Wallet.format(s.treasury.renown(id))
                    + "   Earned in all: " + Wallet.format(s.treasury.renownLifetime(id))), false);
            List<String> bought = new ArrayList<>();
            for (Treasury.CatalogEntry e : s.catalog) {
                int n = s.treasury.bought(id, e.key());
                if (n > 0) bought.add(e.name() + (n > 1 ? " x" + n : ""));
            }
            source.sendSuccess(() -> Messages.body("Bought: " + (bought.isEmpty() ? "nothing yet" : String.join(", ", bought))), false);
        }
        source.sendSuccess(() -> Messages.body("/renown catalog   /renown top   /renown ledger"), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private int catalog(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String org = null;
        if (player != null) {
            try {
                org = src().kingdomOf(player.getUUID()).orElse(null);
            } catch (RuntimeException e) {
                // show the catalogue without ownership
            }
        }
        String id = org;
        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("RENOWN CATALOGUE"), false);
        for (Treasury.CatalogEntry e : s.catalog) {
            int n = id == null ? 0 : s.treasury.bought(id, e.key());
            boolean affordable = id != null && s.treasury.renown(id) >= e.cost();
            String state = n > 0 && !e.repeatable() ? "bought"
                    : (affordable ? "affordable" : "not yet");
            source.sendSuccess(() -> Messages.body(e.key() + "  " + e.name() + "  " + Wallet.format(e.cost())
                    + " Renown  [" + state + "]"), false);
            if (!e.description().isBlank()) source.sendSuccess(() -> Messages.helpLine("", e.description()), false);
            if (e.requires() != null && !e.requires().isBlank()) {
                source.sendSuccess(() -> Messages.helpLine("", "Needs " + e.requires()), false);
            }
        }
        source.sendSuccess(() -> Messages.body("The Lord buys with /renown buy <key>"), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private int top(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> ids = new ArrayList<>(s.treasury.orgIds());
        ids.remove(Treasury.COMMONS);
        ids.removeIf(id -> s.treasury.renownLifetime(id) <= 0);
        ids.sort(Comparator.comparingLong((String id) -> s.treasury.renownLifetime(id)).reversed()
                .thenComparing(id -> id));
        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("RENOWN TOP"), false);
        if (ids.isEmpty()) source.sendSuccess(() -> Messages.body("No kingdom has earned Renown yet."), false);
        for (int i = 0; i < Math.min(10, ids.size()); i++) {
            String id = ids.get(i);
            int rank = i + 1;
            source.sendSuccess(() -> Messages.body(String.format("%2d. %s  %s", rank,
                    src().displayName(id), Wallet.format(s.treasury.renownLifetime(id)))), false);
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private int ledger(CommandContext<CommandSourceStack> ctx) {
        int page = 1;
        String kingdom = null;
        for (String t : StringArgumentType.getString(ctx, "options").trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.chars().allMatch(Character::isDigit)) page = Math.max(1, Integer.parseInt(t));
            else kingdom = t;
        }
        return treasuryCommands.showLedger(ctx.getSource(), "renown", page, kingdom);
    }

    // ---- /renown buy --------------------------------------------------------

    private int buy(CommandContext<CommandSourceStack> ctx, String options) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        boolean confirmed = false;
        String kingdomArg = null;
        for (String t : options.trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase("confirm")) confirmed = true;
            else kingdomArg = t;
        }
        boolean op = TreasuryCommands.isOp(source);

        String key = StringArgumentType.getString(ctx, "key");
        Treasury.CatalogEntry entry = s.catalog.stream()
                .filter(e -> e.key().equalsIgnoreCase(key)).findFirst().orElse(null);
        if (entry == null) {
            source.sendFailure(Messages.bad("Nothing in the catalogue is called '" + key + "'. /renown catalog"));
            return 0;
        }

        // Which organization: the player's kingdom, or a named one for ops. Commons is
        // bought from by ops only.
        String org;
        if (kingdomArg != null) {
            if (!op) {
                source.sendFailure(Messages.bad("Only ops can name a kingdom here."));
                return 0;
            }
            Optional<String> found = kingdomArg.equalsIgnoreCase(Treasury.COMMONS)
                    ? Optional.of(Treasury.COMMONS) : treasuryCommands.resolveOrg(kingdomArg);
            if (found.isEmpty()) {
                source.sendFailure(Messages.bad("No kingdom called '" + kingdomArg + "'."));
                return 0;
            }
            org = found.get();
        } else {
            try {
                org = src().kingdomOf(player.getUUID()).orElse(null);
            } catch (RuntimeException e) {
                source.sendFailure(Messages.bad("The kingdom records cannot be reached right now."));
                return 0;
            }
            if (org == null) {
                source.sendFailure(Messages.bad("You are not in a kingdom."));
                return 0;
            }
        }

        boolean allowed;
        if (Treasury.COMMONS.equals(org)) {
            allowed = op;
        } else {
            try {
                allowed = Treasury.mayWithdraw(src(), org, player.getUUID(), op);
            } catch (RuntimeException e) {
                source.sendFailure(Messages.bad("The kingdom records cannot be reached right now."));
                return 0;
            }
        }
        if (!allowed) {
            source.sendFailure(Messages.bad("Only the Lord of " + src().displayName(org)
                    + " can spend its Renown."));
            return 0;
        }

        if (!confirmed) {
            long balance = s.treasury.renown(org);
            source.sendSuccess(() -> Messages.good("Buy " + entry.name() + " for "
                    + Wallet.format(entry.cost()) + " Renown? " + src().displayName(org)
                    + " has " + Wallet.format(balance) + "."), false);
            String command = "/renown buy " + entry.key() + " confirm" + (kingdomArg != null ? " " + kingdomArg : "");
            source.sendSuccess(() -> Component.literal("[ Confirm purchase ]")
                    .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN)
                            .withBold(true)
                            .withClickEvent(new ClickEvent.RunCommand(command))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Spend the Renown")))), false);
            return 1;
        }

        Treasury.BuyStatus status = s.treasury.buy(org, entry, player.getName().getString());
        switch (status) {
            case INSUFFICIENT -> {
                source.sendFailure(Messages.bad("Not enough Renown: " + entry.name() + " costs "
                        + Wallet.format(entry.cost()) + " and you have " + Wallet.format(s.treasury.renown(org)) + "."));
                return 0;
            }
            case ALREADY_BOUGHT -> {
                source.sendFailure(Messages.bad("You already have " + entry.name() + "."));
                return 0;
            }
            case REQUIRES_MISSING -> {
                source.sendFailure(Messages.bad(entry.name() + " needs " + entry.requires() + " first."));
                return 0;
            }
            case UNKNOWN -> {
                source.sendFailure(Messages.bad("That cannot be bought."));
                return 0;
            }
            case OK -> { }
        }

        source.sendSuccess(() -> Messages.good("Bought " + entry.name() + " for " + Wallet.format(entry.cost())
                + " Renown."), false);
        s.log.tx(player.getName().getString() + " bought " + entry.key() + " for " + entry.cost()
                + " Renown for " + org);
        runCommands(source.getServer(), entry, org, player.getName().getString());
        return 1;
    }

    /**
     * Run the entry's commands as the console. A command that fails is logged loudly
     * and the purchase stays: the Renown is spent and an admin can refund with
     * {@code renown add}.
     */
    private void runCommands(MinecraftServer server, Treasury.CatalogEntry entry, String org, String buyer) {
        String name = src().displayName(org);
        for (String raw : entry.commands()) {
            String cmd = raw.replace("%kingdom_name%", name).replace("%kingdom%", org).replace("%buyer%", buyer);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                int result = server.getCommands().getDispatcher().execute(cmd, server.createCommandSourceStack());
                if (result <= 0) {
                    CharterCurrencyMod.LOG.error("CATALOGUE COMMAND REPORTED FAILURE for {} bought by {}: {}",
                            entry.key(), buyer, cmd);
                }
            } catch (Exception e) {
                CharterCurrencyMod.LOG.error("CATALOGUE COMMAND FAILED for {} bought by {} (the purchase stands; "
                        + "refund with /chartercurrency renown add if needed): {}", entry.key(), buyer, cmd, e);
            }
        }
    }
}
