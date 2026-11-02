package charter.mc.cmd;

import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.moderation.LordModeration;
import charter.moderation.LordRules;
import charter.moderation.actions.LordKick;
import charter.moderation.actions.LordMute;
import charter.moderation.actions.LordSuspend;
import charter.moderation.actions.LordUnmute;
import charter.moderation.actions.LordUnsuspend;
import charter.org.PlayerRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
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
 *   /charter lord mute &lt;player&gt; &lt;time&gt; &lt;reason&gt;       silence a citizen (at most an hour)
 *   /charter lord unmute &lt;player&gt;
 *   /charter lord suspend &lt;player&gt; &lt;time&gt; &lt;reason&gt;    keep a citizen off the kingdom's land (at most 12 hours)
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
        return act(ctx, true, (who, reason) -> new LordMute(who, Args.duration(StringArgumentType.getString(ctx, "time")), reason),
                (s, target, reason) -> {
                    long left = rt().state.player(id(target.online())).mutedUntil - rt().clock.now();
                    String time = Chat.duration(left);
                    tell(ctx, Text.get("lord.muted_lord", "name", target.name(), "time", time, "reason", reason));
                    target.online().sendSystemMessage(Chat.bad(Text.get("lord.muted_target", "lord", s.lord.getPlainTextName(),
                            "org", s.kingdomName, "time", time, "reason", reason)));
                    return Text.get("lord.op.muted", "lord", s.lord.getPlainTextName(), "org", s.kingdomName, "name", target.name(),
                            "time", time, "reason", reason);
                });
    }

    private static int suspend(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, true, (who, reason) -> new LordSuspend(who, Args.duration(StringArgumentType.getString(ctx, "time")), reason),
                (s, target, reason) -> {
                    long left = rt().state.player(id(target.online())).suspendedUntil - rt().clock.now();
                    String time = Chat.duration(left);
                    tell(ctx, Text.get("lord.suspended_lord", "name", target.name(), "time", time, "reason", reason));
                    target.online().sendSystemMessage(Chat.bad(Text.get("lord.suspended_target", "lord", s.lord.getPlainTextName(),
                            "org", s.kingdomName, "time", time, "reason", reason)));
                    return Text.get("lord.op.suspended", "lord", s.lord.getPlainTextName(), "org", s.kingdomName, "name", target.name(),
                            "time", time, "reason", reason);
                });
    }

    private static int kick(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, true, (who, reason) -> new LordKick(who, reason), (s, target, reason) -> {
            tell(ctx, Text.get("lord.kicked_lord", "name", target.name(), "reason", reason));
            target.online().connection.disconnect(Component.literal(Text.get("lord.kick_target", "lord", s.lord.getPlainTextName(),
                    "org", s.kingdomName, "reason", reason)));
            return Text.get("lord.op.kicked", "lord", s.lord.getPlainTextName(), "org", s.kingdomName, "name", target.name(), "reason", reason);
        });
    }

    private static int unmute(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, false, (who, reason) -> new LordUnmute(who), (s, target, reason) -> {
            tell(ctx, Text.get("lord.unmuted_lord", "name", target.name()));
            if (target.online() != null) {
                target.online().sendSystemMessage(Chat.good(Text.get("lord.unmuted_target", "lord", s.lord.getPlainTextName(), "org", s.kingdomName)));
            }
            return Text.get("lord.op.unmuted", "lord", s.lord.getPlainTextName(), "org", s.kingdomName, "name", target.name());
        });
    }

    private static int unsuspend(CommandContext<CommandSourceStack> ctx) {
        return act(ctx, false, (who, reason) -> new LordUnsuspend(who), (s, target, reason) -> {
            tell(ctx, Text.get("lord.unsuspended_lord", "name", target.name()));
            if (target.online() != null) {
                target.online().sendSystemMessage(Chat.good(Text.get("lord.unsuspended_target", "lord", s.lord.getPlainTextName(), "org", s.kingdomName)));
            }
            return Text.get("lord.op.unsuspended", "lord", s.lord.getPlainTextName(), "org", s.kingdomName, "name", target.name());
        });
    }

    private static String reasonOf(CommandContext<CommandSourceStack> ctx) {
        try {
            return LordRules.cleanReason(StringArgumentType.getString(ctx, "reason"));
        } catch (IllegalArgumentException noReasonGiven) {
            return "";
        }
    }

    private interface Make {
        Action make(PlayerId target, String reason);
    }

    private interface Effect {
        /** Carries the action out in the world and returns the line the operators are told. */
        String apply(Setup setup, Resolve.Who target, String reason);
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Chat.good(text), false);
    }

    /**
     * The one path every Lord action takes: a Lord at the keyboard, a known target, online when the action needs the
     * world (or when it puts a hold on them), not an operator, then the rules in core, then the effect and a word to
     * the operators.
     */
    private static int act(CommandContext<CommandSourceStack> ctx, boolean needsOnline, Make make, Effect effect) {
        Optional<Setup> setup = setup(ctx);
        if (setup.isEmpty()) return 0;
        String wanted = StringArgumentType.getString(ctx, "player");
        Optional<Resolve.Who> who = Resolve.player(ctx, rt());
        if (who.isEmpty()) return fail(ctx, Text.get("lord.no_player", "name", wanted));
        Resolve.Who target = who.get();
        if (needsOnline && target.online() == null) return fail(ctx, Text.get("lord.offline", "name", target.name()));
        // Operators are beyond a Lord's reach. Asked before the rules run, and the same words as any other refusal of power.
        if (target.online() != null && LordModeration.staff(target.online())) return fail(ctx, Text.get("lord.no_power", "name", target.name()));
        String reason = reasonOf(ctx);
        Outcome outcome = rt().engine.run(new Actor.Player(id(setup.get().lord)), make.make(target.id(), reason));
        if (outcome instanceof Outcome.Refused(Reason refusal)) return fail(ctx, Chat.reason(refusal));
        String note = effect.apply(setup.get(), target, reason);
        LordModeration.tellOperators(ctx.getSource().getServer(), note);
        return 1;
    }
}
