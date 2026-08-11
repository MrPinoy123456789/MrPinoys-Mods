package chatdonkey;

import chatdonkey.core.Behaviors;
import chatdonkey.core.DonkeyBehavior;
import chatdonkey.core.EndReason;
import chatdonkey.core.Settings;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /donkey} tree (SPEC.md section 9).
 *
 * <p>{@code trigger} is not merely a debug command: SPEC.md section 11 makes it
 * the entire API surface the eventual Twitch bridge needs in order to start an
 * event, so it stays a first-class code path.
 *
 * <p>{@code grace} is likewise designed to become purchasable and overridable
 * under Twitch integration -- the bidding war between streamer and chat -- which
 * is why immunity lives in the trigger rules rather than behind a debug flag.
 */
public final class DonkeyCommands {

    /** Sentinel for "no duration override given"; the event rolls its own. */
    private static final int NO_OVERRIDE = -1;

    /**
     * Completion for the event argument.
     *
     * <p>Reads the pool at completion time rather than at registration, so
     * demand events an operator adds to {@code events.json} and loads with
     * {@code /donkey reload} tab-complete immediately.
     */
    private static SuggestionProvider<CommandSourceStack> behaviorIds(Events events) {
        return (ctx, builder) ->
                SharedSuggestionProvider.suggest(Behaviors.ids(events.pool()), builder);
    }

    private DonkeyCommands() {}

    public static void register(DonkeyConfig config, Triggers triggers, Events events) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("donkey")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                        .then(Commands.literal("trigger")
                                .executes(ctx -> trigger(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), null, NO_OVERRIDE, events))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> trigger(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), null, NO_OVERRIDE, events))
                                        .then(Commands.argument("event", StringArgumentType.word())
                                                .suggests(behaviorIds(events))
                                                .executes(ctx -> trigger(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "event"),
                                                        NO_OVERRIDE, events))
                                                // Duration override: the bridge
                                                // decides how long chat just paid for.
                                                .then(Commands.argument("seconds",
                                                                IntegerArgumentType.integer(
                                                                        1, Settings.MAX_EVENT_SECONDS))
                                                        .executes(ctx -> trigger(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "event"),
                                                                IntegerArgumentType.getInteger(ctx, "seconds"),
                                                                events))))))

                        // --- the bridge surface (SPEC.md sections 9 and 11) ---

                        .then(Commands.literal("extend")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("seconds",
                                                        IntegerArgumentType.integer(1, 300))
                                                .executes(ctx -> extend(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "seconds"),
                                                        events)))))

                        .then(Commands.literal("say")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("line",
                                                        StringArgumentType.greedyString())
                                                .executes(ctx -> say(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "line"),
                                                        events)))))

                        .then(Commands.literal("ungrace")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> ungrace(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), triggers))))

                        .then(Commands.literal("end")
                                .executes(ctx -> end(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), events))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> end(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), events))))

                        .then(Commands.literal("grace")
                                .executes(ctx -> grace(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), config, triggers, events))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> grace(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                config, triggers, events))))

                        .then(Commands.literal("reload")
                                .executes(ctx -> reload(ctx.getSource(), config)))

                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource(), config, events)))));
    }

    private static int trigger(CommandSourceStack source, ServerPlayer target,
                               String behaviorId, int seconds, Events events) {
        if (events.isActive(target.getUUID())) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " is already being bothered."));
            return 0;
        }

        boolean started;
        if (behaviorId == null) {
            started = events.start(target);
        } else {
            DonkeyBehavior behavior = Behaviors.byId(behaviorId, events.pool());
            if (behavior == null) {
                source.sendFailure(Component.literal(
                        "No such event: " + behaviorId + ". Try one of " + Behaviors.ids(events.pool()) + "."));
                return 0;
            }
            started = seconds == NO_OVERRIDE
                    ? events.start(target, behavior)
                    : events.start(target, behavior, seconds * 20);
        }

        if (!started) {
            source.sendFailure(Component.literal(
                    "No room to put a donkey near " + target.getName().getString() + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                        "A donkey has business with " + target.getName().getString() + ".")
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /**
     * Lengthens a running event. "Chat pays to extend" (SPEC.md section 11) --
     * the bribe with the sign flipped.
     */
    private static int extend(CommandSourceStack source, ServerPlayer target,
                              int seconds, Events events) {
        ActiveEvent event = events.eventFor(target.getUUID());
        if (event == null) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " has no donkey to extend."));
            return 0;
        }
        int granted = event.extendBy(seconds);
        if (granted <= 0) {
            source.sendFailure(Component.literal(
                    "That event is already at the " + Settings.MAX_EVENT_SECONDS
                            + " second ceiling."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                        "Extended " + target.getName().getString() + "'s donkey by "
                                + granted + "s.")
                .withStyle(ChatFormatting.GOLD), true);
        // Reports the seconds actually granted, so a bridge can refund the
        // difference when a redemption hits the ceiling.
        return granted;
    }

    /**
     * Puts a chat-written line in the donkey's mouth (SPEC.md section 11).
     *
     * <p>Sanitised on arrival regardless of upstream moderation -- see
     * {@link chatdonkey.core.ChatLine}.
     */
    private static int say(CommandSourceStack source, ServerPlayer target,
                           String line, Events events) {
        ActiveEvent event = events.eventFor(target.getUUID());
        if (event == null) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " has no donkey to speak through."));
            return 0;
        }
        if (!event.sayExternal(line)) {
            source.sendFailure(Component.literal(
                    "Nothing usable left in that line after sanitising."));
            return 0;
        }
        return 1;
    }

    /**
     * Revokes a grace period -- chat outbidding the streamer (SPEC.md section 7's
     * designed bidding war).
     */
    private static int ungrace(CommandSourceStack source, ServerPlayer target,
                               Triggers triggers) {
        boolean had = triggers.revokeGrace(target);
        if (!had) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " had no grace period to revoke."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                        target.getName().getString() + " is fair game again.")
                .withStyle(ChatFormatting.GOLD), true);
        target.sendSystemMessage(Component.literal(
                        "The donkeys have reconsidered. You are no longer protected.")
                .withStyle(ChatFormatting.RED));
        return 1;
    }

    /** Ends an event with no gift and no cooldown -- the operator's undo. */
    private static int end(CommandSourceStack source, ServerPlayer target, Events events) {
        if (!events.isActive(target.getUUID())) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " has no donkey."));
            return 0;
        }
        events.endFor(target.getUUID(), EndReason.ABORTED);
        source.sendSuccess(() -> Component.literal(
                        "Sent " + target.getName().getString() + "'s donkey away.")
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int grace(CommandSourceStack source, ServerPlayer target,
                             DonkeyConfig config, Triggers triggers, Events events) {
        // gracePeriodCommand is the operator's off switch for the escape hatch
        // (SPEC.md section 7). A server that wants the donkey to be genuinely
        // unavoidable turns it off.
        if (!config.settings().gracePeriodCommand()) {
            source.sendFailure(Component.literal(
                    "The grace period is disabled on this server. Good luck."));
            return 0;
        }

        int minutes = config.settings().gracePeriodMinutes();
        triggers.grantGrace(target, minutes);

        // Grace that leaves the donkey you already have is not grace.
        if (events.isActive(target.getUUID())) {
            events.endFor(target.getUUID(), EndReason.ABORTED);
        }

        source.sendSuccess(() -> Component.literal(
                        "No donkeys for " + target.getName().getString()
                                + " for " + minutes + " minutes.")
                .withStyle(ChatFormatting.AQUA), true);
        if (source.getPlayer() != target) {
            target.sendSystemMessage(Component.literal(
                            "The donkeys have agreed to leave you alone for " + minutes + " minutes.")
                    .withStyle(ChatFormatting.AQUA));
        }
        return 1;
    }

    private static int reload(CommandSourceStack source, DonkeyConfig config) {
        config.reload();
        source.sendSuccess(() -> Component.literal(
                        "Reloaded chat donkey config: " + config.lines().size() + " line pools.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int status(CommandSourceStack source, DonkeyConfig config, Events events) {
        source.sendSuccess(() -> Component.literal("Chat Donkey")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("  " + events.activeCount() + " / "
                        + config.settings().maxSimultaneousEventsServerWide() + " events running")
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal("  events: " + Behaviors.ids(events.pool()))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  "
                        + (config.settings().enabled() ? "enabled" : "DISABLED"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
