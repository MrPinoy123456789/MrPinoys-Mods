package charter.mc.cmd;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.moderation.LordActions;
import charter.moderation.LordRules;
import charter.org.PlayerRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * What a Lord can do about their own citizens, under {@code /charter lord}. Every command works typed, so Bedrock
 * players use it the same way. The target is checked against the Lord's own kingdom by the rules in core
 * ({@link LordRules}); this layer adds the one check core cannot make, that the target is not an operator, and does
 * the part that needs the world: telling people, disconnecting, and telling the operators.
 *
 * <pre>
 *   /charter lord                                      what you can do, and who is held now
 *   /charter lord mute &lt;player&gt; &lt;time&gt; &lt;reason&gt;       mute a citizen (at most an hour)
 *   /charter lord unmute &lt;player&gt;
 *   /charter lord suspend &lt;player&gt; &lt;time&gt; &lt;reason&gt;    bar a citizen from the kingdom's land (at most 12 hours)
 *   /charter lord unsuspend &lt;player&gt;
 *   /charter lord kick &lt;player&gt; &lt;reason&gt;             send a citizen off the server for a moment
 * </pre>
 *
 * Times read as {@code 10m}, {@code 2h}, {@code 1h30m}. A reason is required: the citizen reads it, and so do the
 * operators.
 */
public final class LordCommands {

    private LordCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter").then(Commands.literal("lord")
                        .requires(s -> s.getPlayer() != null)
                        .executes(LordCommands::overview)
                        .then(Commands.literal("mute").then(target().then(
                                Commands.argument("time", StringArgumentType.word()).then(
                                        Commands.argument("reason", StringArgumentType.greedyString()).executes(LordCommands::mute)))))
                        .then(Commands.literal("unmute").then(target().executes(LordCommands::unmute)))
                        .then(Commands.literal("suspend").then(target().then(
                                Commands.argument("time", StringArgumentType.word()).then(
                                        Commands.argument("reason", StringArgumentType.greedyString()).executes(LordCommands::suspend)))))
                        .then(Commands.literal("unsuspend").then(target().executes(LordCommands::unsuspend)))
                        .then(Commands.literal("kick").then(target().then(
                                Commands.argument("reason", StringArgumentType.greedyString()).executes(LordCommands::kick)))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> target() {
        return Commands.argument("player", StringArgumentType.string())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }

    private static PlayerId id(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    /** The context every command starts from: a running Charter and a Lord at the keyboard. */
    private record Setup(ServerPlayer lord, OrgId kingdom, String kingdomName) {
    }

    private static Optional<Setup> setup(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer lord = ctx.getSource().getPlayer();
        if (lord == null) {
            fail(ctx, Text.get("lord.only_players"));
            return Optional.empty();
        }
        if (!CharterServer.running()) {
            fail(ctx, Text.get("book.not_running"));
            return Optional.empty();
        }
        Optional<OrgId> kingdom = LordRules.kingdomOf(rt().state, id(lord));
        if (kingdom.isEmpty()) {
            fail(ctx, Chat.reason(LordRules.NOT_A_LORD));
            return Optional.empty();
        }
        return Optional.of(new Setup(lord, kingdom.get(), rt().describe.org(kingdom.get())));
    }

    // ---- the overview ----

    private static int overview(CommandContext<CommandSourceStack> ctx) {
        Optional<Setup> s = setup(ctx);
        if (s.isEmpty()) return 0;
        CharterRuntime rt = rt();
        long now = rt.clock.now();
        say(ctx, Chat.header(Text.get("lord.help.header", "org", s.get().kingdomName)));
        for (String key : new String[] {"mute", "unmute", "suspend", "unsuspend", "kick"}) say(ctx, Chat.line(Text.get("lord.help." + key)));
        say(ctx, Chat.line(Text.get("lord.help.limits", "mute", Chat.duration(LordRules.muteCap(rt.knobs)),
                "suspend", Chat.duration(LordRules.suspendCap(rt.knobs)))));
        int held = 0;
        for (var member : rt.state.members(s.get().kingdom)) {
            PlayerRecord r = rt.state.knownPlayer(member.player()).orElse(null);
            if (r == null) continue;
            String name = rt.describe.player(member.player());
            if (r.mutedUntil > now) {
                held++;
                say(ctx, Chat.line(Text.get("lord.list.muted", "name", name, "time", Chat.duration(r.mutedUntil - now))));
            }
            if (r.suspendedUntil > now && s.get().kingdom.equals(r.suspendedFrom)) {
                held++;
                say(ctx, Chat.line(Text.get("lord.list.suspended", "name", name, "time", Chat.duration(r.suspendedUntil - now))));
            }
        }
        if (held == 0) say(ctx, Chat.line(Text.get("lord.list.none")));
        return 1;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, Component line) {
        ctx.getSource().sendSuccess(() -> line, false);
    }

    // ---- the actions ----

    private static int mute(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, LordActions.Kind.MUTE);
    }

    private static int suspend(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, LordActions.Kind.SUSPEND);
    }

    private static int kick(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, LordActions.Kind.KICK);
    }

    private static int unmute(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, LordActions.Kind.UNMUTE);
    }

    private static int unsuspend(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, LordActions.Kind.UNSUSPEND);
    }

    /** An argument the command may not have (a lift has no time and no reason): empty when absent. */
    private static String optional(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException absent) {
            return "";
        }
    }

    /**
     * Reads the typed arguments and hands them to {@link LordActions#run}, the path the book's Discipline pages take
     * too, so the operator exemption, the online check, the rules and the word to the operators are the same either way.
     */
    private static int act(CommandContext<CommandSourceStack> ctx, LordActions.Kind kind) {
        Optional<Setup> setup = setup(ctx);
        if (setup.isEmpty()) return 0;
        String wanted = StringArgumentType.getString(ctx, "player");
        Optional<Resolve.Who> who = Resolve.player(ctx, rt());
        if (who.isEmpty()) return fail(ctx, Text.get("lord.no_player", "name", wanted));
        String time = optional(ctx, "time");
        long millis = time.isEmpty() ? 0 : Args.duration(time);
        LordActions.Result result = LordActions.run(setup.get().lord, kind, who.get().id().value(), millis, optional(ctx, "reason"));
        if (!result.ok()) return fail(ctx, result.line());
        ctx.getSource().sendSuccess(() -> Chat.good(result.line()), false);
        return 1;
    }
}
