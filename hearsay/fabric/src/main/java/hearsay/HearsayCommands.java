package hearsay;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code /hearsay} operator tree.
 *
 * <p>M2: {@code status} and {@code reload}. M6 adds {@code say}, {@code scene}
 * and player-facing {@code mute}.
 */
public final class HearsayCommands {

    private HearsayCommands() {}

    public static void register(HearsayConfig config, Listeners listeners,
                                Speech speech, Scenes scenes, Mute mute) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("hearsay")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                        .then(Commands.literal("reload")
                                .executes(ctx -> reload(ctx.getSource(), config)))

                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource(), listeners)))

                        .then(Commands.literal("say")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .executes(ctx -> say(ctx.getSource(), ctx.getSource().getServer(),
                                                        listeners, speech, EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "pool"))))))

                        .then(Commands.literal("scene")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> scene(ctx.getSource(), listeners, scenes,
                                                EntityArgument.getPlayer(ctx, "player")))))

                        .then(Commands.literal("mute")
                                .executes(ctx -> mute(ctx.getSource(), mute, ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> mute(ctx.getSource(), mute,
                                                EntityArgument.getPlayer(ctx, "player")))))));
    }

    private static int reload(CommandSourceStack source, HearsayConfig config) {
        config.reload();
        source.sendSuccess(() -> Component.literal("Hearsay config reloaded.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int status(CommandSourceStack source, Listeners listeners) {
        Map<UUID, List<Listeners.Candidate>> byPlayer = listeners.byPlayer();
        if (byPlayer.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No players online.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Hearsay status")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        MinecraftServer server = source.getServer();
        for (Map.Entry<UUID, List<Listeners.Candidate>> e : byPlayer.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            String name = player != null ? player.getName().getString() : e.getKey().toString();

            List<String> professions = new ArrayList<>();
            for (Listeners.Candidate c : e.getValue()) {
                professions.add(c.professionId());
            }
            String profs = professions.isEmpty() ? "none" : String.join(", ", professions);
            String msg = name + ": " + e.getValue().size() + " villagers (" + profs + ")";
            source.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.WHITE), false);
        }
        return 1;
    }

    private static int say(CommandSourceStack source, MinecraftServer server,
                           Listeners listeners, Speech speech,
                           ServerPlayer player, String pool) {
        List<Listeners.Candidate> candidates = listeners.byPlayer().get(player.getUUID());
        if (candidates == null || candidates.isEmpty()) {
            source.sendFailure(Component.literal("No villager within hearing range."));
            return 0;
        }
        Listeners.Candidate c = candidates.get(0);
        speech.forceSay(player, c, pool);
        source.sendSuccess(() -> Component.literal("Said a " + pool + " line to " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int scene(CommandSourceStack source, Listeners listeners,
                             Scenes scenes, ServerPlayer player) {
        List<Listeners.Candidate> candidates = listeners.byPlayer().get(player.getUUID());
        if (candidates == null || candidates.size() < 2) {
            source.sendFailure(Component.literal(player.getName().getString() + " needs two nearby villagers."));
            return 0;
        }
        if (scenes.forceStart(player.getUUID(), candidates)) {
            source.sendSuccess(() -> Component.literal("Started a scene for " + player.getName().getString())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        source.sendFailure(Component.literal("No matching scene found."));
        return 0;
    }

    private static int mute(CommandSourceStack source, Mute mute, ServerPlayer player) {
        boolean nowMuted = mute.toggle(player.getUUID());
        String msg = player.getName().getString() + (nowMuted ? " will no longer hear ambient dialogue."
                : " will hear ambient dialogue again.");
        source.sendSuccess(() -> Component.literal(msg).withStyle(nowMuted ? ChatFormatting.RED : ChatFormatting.GREEN), true);
        return 1;
    }
}
