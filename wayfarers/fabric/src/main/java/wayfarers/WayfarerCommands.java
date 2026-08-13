package wayfarers;

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

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /wayfarer} debug/operator tree.
 *
 * <p>Per SPEC.md §11, the command is the intended API surface for external
 * integrations. All operators may trigger; normal players never can.
 */
public final class WayfarerCommands {

    private WayfarerCommands() {}

    public static void register(WayfarersConfig config, Encounters encounters) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("wayfarer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                        .then(Commands.literal("trigger")
                                .executes(ctx -> trigger(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), null, config, encounters))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> trigger(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), null, config, encounters))
                                        .then(Commands.argument("encounter", StringArgumentType.word())
                                                .suggests(encounterIds(config))
                                                .executes(ctx -> trigger(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "encounter"),
                                                        config, encounters)))))

                        .then(Commands.literal("end")
                                .executes(ctx -> end(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), encounters))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> end(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), encounters))))

                        .then(Commands.literal("forget")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> forget(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), encounters))))

                        .then(Commands.literal("reload")
                                .executes(ctx -> reload(ctx.getSource(), config)))

                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource(), config, encounters)))));
    }

    private static SuggestionProvider<CommandSourceStack> encounterIds(WayfarersConfig config) {
        List<String> ids = new ArrayList<>();
        for (var d : config.encounters().definitions()) {
            ids.add(d.id());
        }
        return (ctx, builder) -> SharedSuggestionProvider.suggest(ids, builder);
    }

    private static int trigger(CommandSourceStack source, ServerPlayer target, String id,
                               WayfarersConfig config, Encounters encounters) {
        boolean started = (id == null) ? encounters.start(target) : encounters.start(target, id);
        if (!started) {
            source.sendFailure(Component.literal(
                    "Could not start an encounter for " + target.getName().getString()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                        "An encounter appears before " + target.getName().getString())
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int end(CommandSourceStack source, ServerPlayer target, Encounters encounters) {
        if (!encounters.isActive(target.getUUID())) {
            source.sendFailure(Component.literal(
                    target.getName().getString() + " has no active encounter."));
            return 0;
        }
        encounters.endFor(target.getUUID());
        source.sendSuccess(() -> Component.literal(
                        "Encounter ended for " + target.getName().getString())
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int forget(CommandSourceStack source, ServerPlayer target, Encounters encounters) {
        TraderRegistry.remove(target.getUUID());
        encounters.endFor(target.getUUID());
        source.sendSuccess(() -> Component.literal(
                        "Forgot " + target.getName().getString() + "'s wayfarer record")
                .withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static int reload(CommandSourceStack source, WayfarersConfig config) {
        config.reload();
        source.sendSuccess(() -> Component.literal("Reloaded wayfarers config.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int status(CommandSourceStack source, WayfarersConfig config, Encounters encounters) {
        source.sendSuccess(() -> Component.literal("Wayfarers")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("  " + encounters.activeCount() + " / "
                + config.settings().maxSimultaneous() + " active"), false);
        source.sendSuccess(() -> Component.literal("  "
                + (config.settings().enabled() ? "enabled" : "DISABLED")), false);
        return 1;
    }
}
