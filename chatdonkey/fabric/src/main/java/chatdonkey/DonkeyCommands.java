package chatdonkey;

import chatdonkey.core.Behaviors;
import chatdonkey.core.DonkeyBehavior;
import chatdonkey.core.EndReason;
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

    private static final SuggestionProvider<CommandSourceStack> BEHAVIOR_IDS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(Behaviors.ids(), builder);

    private DonkeyCommands() {}

    public static void register(DonkeyConfig config, Triggers triggers, Events events) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("donkey")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                        .then(Commands.literal("trigger")
                                .executes(ctx -> trigger(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), null, events))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> trigger(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), null, events))
                                        .then(Commands.argument("event", StringArgumentType.word())
                                                .suggests(BEHAVIOR_IDS)
                                                .executes(ctx -> trigger(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "event"),
                                                        events)))))

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
                               String behaviorId, Events events) {
        if (events.isActive(target.getUUID())) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " is already being bothered."));
            return 0;
        }

        boolean started;
        if (behaviorId == null) {
            started = events.start(target);
        } else {
            DonkeyBehavior behavior = Behaviors.byId(behaviorId);
            if (behavior == null) {
                source.sendFailure(Component.literal(
                        "No such event: " + behaviorId + ". Try one of " + Behaviors.ids() + "."));
                return 0;
            }
            started = events.start(target, behavior);
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
        source.sendSuccess(() -> Component.literal("  behaviors: " + Behaviors.ids())
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  "
                        + (config.settings().enabled() ? "enabled" : "DISABLED"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
