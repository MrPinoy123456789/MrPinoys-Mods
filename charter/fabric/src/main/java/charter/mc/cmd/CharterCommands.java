package charter.mc.cmd;

import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.MemberRecord;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The typed surface (PLAN.md §7.3). Kept to what needs a name typed; everything
 * else is a link on a page.
 *
 * <pre>
 *   /charter                                   your faction page
 *   /charter found &lt;name&gt; &lt;commune|lordship&gt;
 *   /charter invite &lt;player&gt;
 *   /charter grant &lt;player or office&gt; &lt;power&gt;
 *   /charter vote                              open votes you may cast
 *   /charter vault                             the treasury
 *   /charter vault deposit|withdraw &lt;currency&gt; &lt;amount&gt;
 *   /charter act &lt;token&gt;                       what every link runs
 *   /charter admin ...                         operators (AdminCommands)
 * </pre>
 */
public final class CharterCommands {

    /** The tokens behind every link on every page. */
    static final Tokens<ServerPlayer> TOKENS = new Tokens<>();

    /** The link tokens, for gametests that click a link the way a player would. */
    public static Tokens<ServerPlayer> tokens() {
        return TOKENS;
    }

    private CharterCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .executes(ctx -> page(ctx, Pages::faction))
                        .then(Commands.literal("found")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"commune", "lordship"}, b))
                                                .executes(CharterCommands::found))))
                        .then(Commands.literal("invite")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                        .executes(CharterCommands::invite)))
                        .then(Commands.literal("grant")
                                .then(Commands.argument("to", StringArgumentType.string())
                                        .then(Commands.argument("power", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        Arrays.stream(Power.values()).map(p -> p.name().toLowerCase(Locale.ROOT)), b))
                                                .executes(CharterCommands::grant))))
                        .then(Commands.literal("vote").executes(ctx -> page(ctx, Pages::votes)))
                        .then(Commands.literal("vault")
                                .executes(ctx -> page(ctx, Pages::vault))
                                .then(Commands.literal("deposit").then(currencyAmount(true)))
                                .then(Commands.literal("withdraw").then(currencyAmount(false))))
                        .then(Commands.literal("act")
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(CharterCommands::act)))
                        .then(AdminCommands.tree())));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> currencyAmount(boolean deposit) {
        return Commands.argument("currency", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"cobblestone", "diamond"}, b))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> deposit ? deposit(ctx) : withdraw(ctx)));
    }

    // ---- shared ----

    interface PageFn {
        void show(ServerPlayer player, CharterRuntime rt);
    }

    private static int page(CommandContext<CommandSourceStack> ctx, PageFn page) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        page.show(player, CharterServer.runtime());
        return 1;
    }

    static PlayerId id(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    static Optional<OrgId> orgOf(CharterRuntime rt, ServerPlayer player) {
        return rt.state.membership(id(player)).map(MemberRecord::org);
    }

    /**
     * Runs an action as the player and says what happened. A refusal that a vote
     * could get past comes with a link to propose it as one.
     */
    static int perform(ServerPlayer player, Action action, String doneText) {
        CharterRuntime rt = CharterServer.runtime();
        rt.state.touch(id(player), player.getPlainTextName(), rt.clock.now());
        Outcome outcome = rt.engine.run(new Actor.Player(id(player)), action);
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            player.sendSystemMessage(Chat.bad(Chat.reason(reason)));
            boolean voteCould = reason.key().equals(Reason.NEEDS_VOTE.key()) || reason.key().equals(Reason.RESERVED.key());
            if (voteCould) {
                player.sendSystemMessage(Component.literal("  ").append(Chat.act(player, Text.get("link.propose"),
                        Text.get("link.propose_hover", "about", rt.describe.action(action)), p -> propose(p, action))));
            }
            return 0;
        }
        player.sendSystemMessage(Chat.good(doneText));
        return 1;
    }

    static void propose(ServerPlayer player, Action action) {
        CharterRuntime rt = CharterServer.runtime();
        Outcome outcome = rt.engine.propose(id(player), action);
        switch (outcome) {
            case Outcome.Refused(Reason reason) -> player.sendSystemMessage(Chat.bad(Chat.reason(reason)));
            case Outcome.VoteOpened(var vote) -> {
                Vote v = rt.state.vote(vote).orElseThrow();
                String text = v.open()
                        ? Text.get("vote.opened", "number", Pages.number(v), "about", rt.describe.action(action))
                        : Text.get("vote.settled_at_once", "number", Pages.number(v), "result", Pages.result(v));
                player.sendSystemMessage(Chat.good(text));
                player.sendSystemMessage(Component.literal("  ").append(Chat.run(Text.get("link.votes"), "/charter vote")));
            }
            default -> {
            }
        }
    }

    private static int act(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        var run = TOKENS.take(StringArgumentType.getString(ctx, "token"), player.getUUID(), System.currentTimeMillis());
        if (run.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("link.expired")));
            return 0;
        }
        run.get().accept(player);
        return 1;
    }

    // ---- found, invite, grant ----

    private static int found(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        Preset preset;
        try {
            preset = Preset.valueOf(StringArgumentType.getString(ctx, "preset").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("found.bad_preset")));
            return 0;
        }
        int done = perform(player, new Found(id(player), name, preset), Text.get("found.done", "name", name.strip()));
        if (done == 1) Pages.faction(player, CharterServer.runtime());
        return done;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        if (org.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("page.no_org")));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        Optional<PlayerId> target = player(rt, name, ctx.getSource());
        if (target.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("invite.no_player", "name", name)));
            return 0;
        }
        return perform(player, new InviteMember(org.get(), target.get()), Text.get("invite.done", "player", rt.describe.player(target.get())));
    }

    /** A player online by name or UUID, else one the rules have seen by that name or UUID, so offline players can be named. */
    static Optional<PlayerId> player(CharterRuntime rt, String name, CommandSourceStack source) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(name);
        if (online == null) {
            try {
                online = source.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(name));
            } catch (IllegalArgumentException notAUuid) {
                // a name, then
            }
        }
        if (online != null) {
            rt.state.touch(id(online), online.getPlainTextName(), rt.clock.now());
            return Optional.of(id(online));
        }
        return rt.state.allPlayers().stream()
                .filter(p -> p.name.equalsIgnoreCase(name) || p.id.value().toString().equalsIgnoreCase(name))
                .findFirst().map(p -> p.id);
    }

    private static int grant(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        if (org.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("page.no_org")));
            return 0;
        }
        Power power;
        try {
            power = Power.valueOf(StringArgumentType.getString(ctx, "power").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("grant.bad_power")));
            return 0;
        }
        String to = StringArgumentType.getString(ctx, "to");
        Optional<Holder> holder = holder(rt, org.get(), to, ctx.getSource());
        if (holder.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("grant.no_target", "name", to)));
            return 0;
        }
        return perform(player, new charter.grant.actions.GrantPower(org.get(), power, holder.get(), GrantScope.ANY, false, 0),
                Text.get("grant.done", "to", rt.describe.holder(holder.get()), "power", charter.mc.Describe.power(power)));
    }

    /** An office of the organization by name, else a player online or known by name. */
    private static Optional<Holder> holder(CharterRuntime rt, OrgId org, String name, CommandSourceStack source) {
        Optional<Office> office = rt.state.officeNamed(org, name);
        if (office.isPresent()) return Optional.of(new Holder.OfOffice(office.get().id));
        return player(rt, name, source).map(Holder.OfPlayer::new);
    }

    // ---- the vault ----

    static Optional<Currency> currency(String name) {
        try {
            return Optional.of(Currency.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int deposit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        Optional<Currency> currency = currency(StringArgumentType.getString(ctx, "currency"));
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        if (org.isEmpty() || currency.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get(org.isEmpty() ? "page.no_org" : "vault.bad_currency")));
            return 0;
        }
        int carried = count(player, currency.get());
        if (carried < amount) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("vault.not_carried", "count", carried,
                    "currency", charter.mc.Describe.currency(currency.get(), carried))));
            return 0;
        }
        int done = perform(player, new Deposit(org.get(), currency.get(), amount), Text.get("vault.deposited",
                "amount", amount, "currency", charter.mc.Describe.currency(currency.get(), amount)));
        if (done == 1) take(player, currency.get(), amount);
        return done;
    }

    private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        Optional<Currency> currency = currency(StringArgumentType.getString(ctx, "currency"));
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        if (org.isEmpty() || currency.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get(org.isEmpty() ? "page.no_org" : "vault.bad_currency")));
            return 0;
        }
        // The items reach the player through the Withdrawn event (Notices), the same way a vote's payout does.
        return perform(player, new Withdraw(org.get(), currency.get(), amount, id(player)), Text.get("vault.withdrawn",
                "amount", amount, "currency", charter.mc.Describe.currency(currency.get(), amount)));
    }

    static int count(ServerPlayer player, Currency currency) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && CharterServer.itemId(stack).equals(currency.itemId)) n += stack.getCount();
        }
        return n;
    }

    static void take(ServerPlayer player, Currency currency, int amount) {
        int left = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !CharterServer.itemId(stack).equals(currency.itemId)) continue;
            int n = Math.min(left, stack.getCount());
            stack.shrink(n);
            left -= n;
        }
        player.getInventory().setChanged();
    }
}
