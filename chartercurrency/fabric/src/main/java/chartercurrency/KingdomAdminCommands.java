package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.Denomination;
import chartercurrency.core.ManualKingdomSource;
import chartercurrency.core.Wallet;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;
import java.util.UUID;

/**
 * The op-level commands for kingdoms (manual stand-in), Mint sites and treasuries.
 * Registered under the same {@code /chartercurrency} root as the fork's admin commands;
 * Brigadier merges the two registrations.
 *
 * <p>Every action is logged with the operator's name.
 */
final class KingdomAdminCommands {

    private final Services s;
    private final TreasuryCommands treasuryCommands;

    KingdomAdminCommands(Services services, TreasuryCommands treasuryCommands) {
        this.s = services;
        this.treasuryCommands = treasuryCommands;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chartercurrency")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("kingdom")
                        .then(Commands.literal("list").executes(this::kingdomList))
                        .then(Commands.literal("set")
                                .then(player().then(Commands.argument("kingdom", StringArgumentType.string())
                                        .suggests(kingdomsAndNone())
                                        .executes(this::kingdomSet))))
                        .then(Commands.literal("lord")
                                .then(Commands.argument("kingdom", StringArgumentType.string())
                                        .suggests(kingdoms())
                                        .then(player().executes(this::kingdomLord))))
                        .then(Commands.literal("create")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> kingdomCreate(ctx, null))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> kingdomCreate(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(kingdoms())
                                        .executes(this::kingdomRemove))))
                .then(Commands.literal("mint")
                        .then(Commands.literal("bind").executes(ctx -> mintSite(ctx, true)))
                        .then(Commands.literal("unbind").executes(ctx -> mintSite(ctx, false)))
                        .then(Commands.literal("list").executes(this::mintList)))
                .then(Commands.literal("treasury")
                        .then(Commands.literal("add")
                                .then(Commands.argument("kingdom", StringArgumentType.string()).suggests(kingdoms())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                                .executes(ctx -> treasuryAdjust(ctx, true)))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("kingdom", StringArgumentType.string()).suggests(kingdoms())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                                .executes(ctx -> treasuryAdjust(ctx, false)))))
                        .then(treasuryMove("pay", false))
                        .then(treasuryMove("give", true))));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> treasuryMove(
            String name, boolean physical) {
        return Commands.literal(name)
                .then(Commands.argument("kingdom", StringArgumentType.string()).suggests(kingdoms())
                        .then(player().then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> treasuryMove(ctx, physical, null))
                                .then(Commands.argument("denomination", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider
                                                .suggest(s.currencies.denominationWords(), b))
                                        .executes(ctx -> treasuryMove(ctx, physical, "denomination"))))));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
            java.util.List<String> options = new java.util.ArrayList<>(ctx.getSource().getOnlinePlayerNames());
            for (String n : s.names.knownNames()) if (!options.contains(n)) options.add(n);
            return SharedSuggestionProvider.suggest(options, builder);
        });
    }

    private SuggestionProvider<CommandSourceStack> kingdoms() {
        return (ctx, b) -> SharedSuggestionProvider.suggest(s.kingdoms.source().kingdomIds(), b);
    }

    private SuggestionProvider<CommandSourceStack> kingdomsAndNone() {
        return (ctx, b) -> {
            java.util.List<String> all = new java.util.ArrayList<>(s.kingdoms.source().kingdomIds());
            all.add("none");
            return SharedSuggestionProvider.suggest(all, b);
        };
    }

    private String op(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getTextName();
    }

    private Optional<UUID> uuidOf(CommandContext<CommandSourceStack> ctx, String arg) {
        String name = StringArgumentType.getString(ctx, arg);
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        Optional<UUID> id = online != null ? Optional.of(online.getUUID()) : s.names.resolve(name);
        if (id.isEmpty()) {
            ctx.getSource().sendFailure(Messages.bad("No player named '" + name
                    + "' has been seen on this server."));
        }
        return id;
    }

    private boolean manualOnly(CommandSourceStack source) {
        if (s.kingdoms.manualActive()) return true;
        source.sendFailure(Messages.bad("Kingdoms come from Charter here. Edit them in Charter."));
        return false;
    }

    // ---- kingdom ------------------------------------------------------------

    private int kingdomList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var src = s.kingdoms.source();
        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("KINGDOMS (" + s.kingdoms.activeName() + ")"), false);
        if (src.kingdomIds().isEmpty()) {
            source.sendSuccess(() -> Messages.body("None yet. /chartercurrency kingdom create <id> [name]"), false);
        }
        for (String id : src.kingdomIds()) {
            var lords = src.lordsOf(id);
            String lord = lords.isEmpty() ? "vacant" : String.join(" and ", lords.stream().map(s.names::nameOf).toList());
            String members = s.kingdoms.manualActive()
                    ? String.valueOf(s.kingdoms.manual().memberCount(id)) : "n/a";
            source.sendSuccess(() -> Messages.body(id + "  " + src.displayName(id) + "  Lord: " + lord
                    + "  members: " + members + "  treasury: " + s.coin().describe(s.treasury.balance(id))), false);
        }
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    private int kingdomSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!manualOnly(source)) return 0;
        Optional<UUID> id = uuidOf(ctx, "player");
        if (id.isEmpty()) return 0;
        String kingdom = StringArgumentType.getString(ctx, "kingdom");
        String target = kingdom.equalsIgnoreCase("none") ? null : kingdom;
        if (!s.kingdoms.manual().setMember(id.get(), target)) {
            source.sendFailure(Messages.bad("No kingdom called '" + kingdom + "'."));
            return 0;
        }
        String who = s.names.nameOf(id.get());
        source.sendSuccess(() -> Messages.good(who + " is now "
                + (target == null ? "in no kingdom." : "in " + s.kingdoms.manual().displayName(target) + ".")), true);
        s.log.admin(op(ctx) + " set " + who + " to kingdom " + (target == null ? "none" : target));
        return 1;
    }

    private int kingdomLord(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!manualOnly(source)) return 0;
        Optional<UUID> id = uuidOf(ctx, "player");
        if (id.isEmpty()) return 0;
        String kingdom = StringArgumentType.getString(ctx, "kingdom");
        if (!s.kingdoms.manual().setLord(kingdom, id.get())) {
            source.sendFailure(Messages.bad("No kingdom called '" + kingdom + "'."));
            return 0;
        }
        String who = s.names.nameOf(id.get());
        source.sendSuccess(() -> Messages.good(who + " is now Lord of "
                + s.kingdoms.manual().displayName(kingdom) + "."), true);
        s.log.admin(op(ctx) + " made " + who + " Lord of " + kingdom);
        return 1;
    }

    private int kingdomCreate(CommandContext<CommandSourceStack> ctx, String nameArg) {
        CommandSourceStack source = ctx.getSource();
        if (!manualOnly(source)) return 0;
        String id = StringArgumentType.getString(ctx, "id");
        String name = nameArg == null ? id : StringArgumentType.getString(ctx, nameArg);
        if (!s.kingdoms.manual().create(id, name)) {
            source.sendFailure(Messages.bad("A kingdom with that id already exists."));
            return 0;
        }
        source.sendSuccess(() -> Messages.good("Created kingdom " + name + " (" + id.toLowerCase() + ")."), true);
        s.log.admin(op(ctx) + " created kingdom " + id);
        return 1;
    }

    private int kingdomRemove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!manualOnly(source)) return 0;
        String id = StringArgumentType.getString(ctx, "id");
        if (!s.kingdoms.manual().remove(id)) {
            source.sendFailure(Messages.bad("No kingdom called '" + id + "'."));
            return 0;
        }
        source.sendSuccess(() -> Messages.good("Removed kingdom " + id + ". Its treasury balance is kept."), true);
        s.log.admin(op(ctx) + " removed kingdom " + id);
        return 1;
    }

    // ---- mint sites ---------------------------------------------------------

    private int mintSite(CommandContext<CommandSourceStack> ctx, boolean bind) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0f, false);
        if (!(hit instanceof BlockHitResult block)) {
            ctx.getSource().sendFailure(Messages.bad("Look at a smithing table."));
            return 0;
        }
        BlockPos pos = block.getBlockPos();
        if (!player.level().getBlockState(pos).is(Blocks.SMITHING_TABLE)) {
            ctx.getSource().sendFailure(Messages.bad("That is not a smithing table."));
            return 0;
        }
        boolean changed = bind ? s.sites.bind(player.level(), pos) : s.sites.unbind(player.level(), pos);
        if (!changed) {
            ctx.getSource().sendFailure(Messages.bad(bind ? "That table is already a Mint."
                    : "That table is not a Mint."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Messages.good((bind ? "Bound" : "Unbound") + " the Mint at "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "."), true);
        s.log.admin(op(ctx) + (bind ? " bound" : " unbound") + " a Mint at " + MintSites.key(player.level(), pos));
        return 1;
    }

    private int mintList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var sites = s.sites.list();
        source.sendSuccess(() -> Messages.title("MINTS (" + sites.size() + ")"), false);
        for (String site : sites) source.sendSuccess(() -> Messages.body("  " + site), false);
        return 1;
    }

    // ---- treasury -----------------------------------------------------------

    private Optional<String> orgArg(CommandContext<CommandSourceStack> ctx) {
        String typed = StringArgumentType.getString(ctx, "kingdom");
        Optional<String> id = treasuryCommands.resolveOrg(typed);
        if (id.isEmpty()) ctx.getSource().sendFailure(Messages.bad("No kingdom called '" + typed + "'."));
        return id;
    }

    private int treasuryAdjust(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack source = ctx.getSource();
        Optional<String> org = orgArg(ctx);
        if (org.isEmpty()) return 0;
        long amount = LongArgumentType.getLong(ctx, "amount");
        boolean ok = add
                ? s.treasury.credit(org.get(), amount, "admin_add", op(ctx), "")
                : s.treasury.charge(org.get(), amount, "admin_remove", op(ctx), "");
        if (!ok) {
            source.sendFailure(Messages.bad(add ? "That would exceed the maximum balance."
                    : "The treasury holds only " + s.coin().describe(s.treasury.balance(org.get())) + "."));
            return 0;
        }
        source.sendSuccess(() -> Messages.good((add ? "Added " : "Removed ")
                + s.coin().describe(amount) + (add ? " to " : " from ") + org.get()
                + ". Balance " + s.coin().describe(s.treasury.balance(org.get())) + "."), true);
        s.log.admin(op(ctx) + (add ? " added " : " removed ") + amount + " Marks "
                + (add ? "to" : "from") + " the " + org.get() + " treasury");
        return 1;
    }

    /** {@code pay}: into the player's bank. {@code give}: as physical coin. */
    private int treasuryMove(CommandContext<CommandSourceStack> ctx, boolean physical, String wordArg) {
        CommandSourceStack source = ctx.getSource();
        Currency coin = s.coin();
        Optional<String> org = orgArg(ctx);
        if (org.isEmpty()) return 0;
        Optional<UUID> target = uuidOf(ctx, "player");
        if (target.isEmpty()) return 0;

        long count = LongArgumentType.getLong(ctx, "amount");
        Denomination named = null;
        if (wordArg != null) {
            Optional<CurrencyRegistry.Word> word = s.currencies.resolveWord(StringArgumentType.getString(ctx, wordArg));
            if (word.isEmpty() || word.get().currency() != coin) {
                source.sendFailure(Messages.bad("Only coin can leave a treasury."));
                return 0;
            }
            named = word.get().denomination();
        }
        long marks = named == null ? count : count * named.value();
        if (named != null && count > Integer.MAX_VALUE) {
            source.sendFailure(Messages.bad("That is too many coins."));
            return 0;
        }
        String who = s.names.nameOf(target.get());

        if (!physical) {
            if (s.treasury.balance(org.get()) < marks) {
                source.sendFailure(Messages.bad("The treasury holds only "
                        + coin.describe(s.treasury.balance(org.get())) + "."));
                return 0;
            }
            if (!s.treasury.charge(org.get(), marks, "pay", op(ctx), "to " + who)) return 0;
            if (!s.economy.deposit(target.get(), coin, marks).ok()) {
                s.treasury.credit(org.get(), marks, "refund", op(ctx), "pay to " + who + " failed");
                source.sendFailure(Messages.bad("Could not credit " + who + ". Nothing was changed."));
                return 0;
            }
            source.sendSuccess(() -> Messages.good("Paid " + coin.describe(marks) + " from " + org.get()
                    + " to " + who + "'s bank."), true);
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.get());
            if (online != null) {
                online.sendSystemMessage(Messages.good("Received ").append(Messages.priced(coin, marks))
                        .append(Messages.good(" from the " + s.kingdoms.source().displayName(org.get()) + " treasury.")));
            }
            s.log.admin(op(ctx) + " paid " + marks + " Marks from the " + org.get() + " treasury to " + who);
            return 1;
        }

        ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.get());
        if (online == null) {
            source.sendFailure(Messages.bad(who + " must be online to be handed coin. Use pay for their bank."));
            return 0;
        }
        int[] faces = coin.faceValues();
        int[] needed;
        if (named != null) {
            needed = new int[faces.length];
            needed[coin.denominations().indexOf(named)] = (int) count;
        } else {
            needed = Wallet.makeChange(marks, faces);
        }
        if (s.treasury.balance(org.get()) < marks) {
            source.sendFailure(Messages.bad("The treasury holds only "
                    + coin.describe(s.treasury.balance(org.get())) + "."));
            return 0;
        }
        if (!CoinOps.fits(online, coin, needed)) {
            source.sendFailure(Messages.bad(who + "'s inventory cannot hold " + coin.describe(marks)
                    + ". Nothing was changed."));
            return 0;
        }
        if (!s.treasury.charge(org.get(), marks, "give", op(ctx), "to " + who)) return 0;
        CoinOps.give(online, coin, needed);
        online.sendSystemMessage(Messages.good("Received ").append(Messages.priced(coin, marks))
                .append(Messages.good(" from the " + s.kingdoms.source().displayName(org.get()) + " treasury.")));
        source.sendSuccess(() -> Messages.good("Handed " + coin.describe(marks) + " from " + org.get()
                + " to " + who + "."), true);
        s.log.admin(op(ctx) + " gave " + marks + " Marks of coin from the " + org.get() + " treasury to " + who);
        return 1;
    }
}
