package quizengine.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import quizengine.RoundType;

import java.util.Map;
import java.util.UUID;

/**
 * The command tree. {@code /quiz advance} exists specifically to prove the engine is
 * orchestrator-agnostic — it drives the same transitions the tick scheduler does.
 */
public final class QuizCommands {

    private QuizCommands() {}

    /** Registers the player input, leaderboard, content reload, and operator round-control commands. */
    public static void register(Orchestrator orchestrator, Leaderboard leaderboard,
                                Content content) {

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("quiz")

                        .then(Commands.literal("start")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("trivia").executes(ctx ->
                                        start(ctx.getSource(), orchestrator, RoundType.TRIVIA)))
                                .then(Commands.literal("quiplash").executes(ctx ->
                                        start(ctx.getSource(), orchestrator, RoundType.QUIPLASH))))

                        .then(Commands.literal("advance")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    if (!orchestrator.isRunning()) {
                                        src.sendFailure(Component.literal("No round is running."));
                                        return 0;
                                    }
                                    orchestrator.advance(src.getServer());
                                    return 1;
                                }))

                        .then(Commands.literal("cancel")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> {
                                    orchestrator.cancel(ctx.getSource().getServer());
                                    return 1;
                                }))

                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> {
                                    content.reload();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Reloaded " + content.triviaCount() + " questions and "
                                                    + content.promptCount() + " prompts."), false);
                                    return 1;
                                }))

                        .then(Commands.literal("answer")
                                .then(Commands.argument("number", IntegerArgumentType.integer(1, 9))
                                        .executes(ctx -> {
                                            ServerPlayer player =
                                                    ctx.getSource().getPlayerOrException();
                                            int number =
                                                    IntegerArgumentType.getInteger(ctx, "number");
                                            player.sendSystemMessage(
                                                    orchestrator.submit(player, number - 1));
                                            return 1;
                                        })))

                        .then(Commands.literal("submit")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer player =
                                                    ctx.getSource().getPlayerOrException();
                                            String text =
                                                    StringArgumentType.getString(ctx, "text");
                                            player.sendSystemMessage(
                                                    orchestrator.submitText(player, text));
                                            return 1;
                                        })))

                        .then(Commands.literal("vote")
                                .then(Commands.argument("number", IntegerArgumentType.integer(1, 9))
                                        .executes(ctx -> {
                                            ServerPlayer player =
                                                    ctx.getSource().getPlayerOrException();
                                            int number =
                                                    IntegerArgumentType.getInteger(ctx, "number");
                                            player.sendSystemMessage(
                                                    orchestrator.vote(player, number - 1));
                                            return 1;
                                        })))

                        .then(Commands.literal("top").executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            var top = leaderboard.top(10);
                            if (top.isEmpty()) {
                                src.sendSuccess(() -> Component.literal("Nobody has scored yet."),
                                        false);
                                return 1;
                            }
                            src.sendSuccess(() -> Component.literal("All-time leaderboard")
                                    .withStyle(ChatFormatting.GOLD), false);
                            int rank = 1;
                            for (Map.Entry<UUID, Leaderboard.Entry> e : top) {
                                final int position = rank++;
                                src.sendSuccess(() -> Component.literal(
                                        "  " + position + ". " + e.getValue().name()
                                                + " — " + e.getValue().points() + " points"), false);
                            }
                            return 1;
                        }))

                        .then(Commands.literal("help").executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            src.sendSuccess(() -> Component.literal("Quiz Engine")
                                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                            src.sendSuccess(() -> Component.literal("  /quiz answer <number>  Answer a trivia question")
                                    .withStyle(ChatFormatting.WHITE), false);
                            src.sendSuccess(() -> Component.literal("  /quiz submit <text>  Submit a Quiplash entry")
                                    .withStyle(ChatFormatting.WHITE), false);
                            src.sendSuccess(() -> Component.literal("  /quiz vote <number>  Vote for a Quiplash entry")
                                    .withStyle(ChatFormatting.WHITE), false);
                            src.sendSuccess(() -> Component.literal("  /quiz top  All-time leaderboard")
                                    .withStyle(ChatFormatting.WHITE), false);
                            if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src)) {
                                src.sendSuccess(() -> Component.literal("  /quiz start trivia|quiplash  Start a round")
                                        .withStyle(ChatFormatting.WHITE), false);
                                src.sendSuccess(() -> Component.literal("  /quiz advance  Advance the current round")
                                        .withStyle(ChatFormatting.WHITE), false);
                                src.sendSuccess(() -> Component.literal("  /quiz cancel  Cancel the current round")
                                        .withStyle(ChatFormatting.WHITE), false);
                                src.sendSuccess(() -> Component.literal("  /quiz reload  Reload questions and prompts")
                                        .withStyle(ChatFormatting.WHITE), false);
                            }
                            return 1;
                        }))));
    }

    private static int start(CommandSourceStack source, Orchestrator orchestrator,
                             RoundType type) {
        if (orchestrator.isRunning()) {
            source.sendFailure(Component.literal("A round is already running."));
            return 0;
        }
        if (!orchestrator.start(source.getServer(), type)) {
            source.sendFailure(Component.literal(
                    "Couldn't start — check the console for content loading errors."));
            return 0;
        }
        return 1;
    }
}
