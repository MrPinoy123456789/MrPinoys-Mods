package dailyquests;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /daily} — see the riddle, hand it in, check your streak.
 */
public final class DailyCommands {

    private DailyCommands() {}

    public static void register(Quests quests, DailyState state, TurnIn turnIn) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("daily")

                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            show(player, quests, state);
                            return 1;
                        })

                        .then(Commands.literal("turnin").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            Component problem = turnIn.attempt(player);
                            if (problem != null) {
                                player.sendSystemMessage(problem);
                                return 0;
                            }
                            return 1;
                        }))

                        .then(Commands.literal("streak").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            DailyState.Entry entry = state.entryOf(player.getUUID());
                            if (entry == null) {
                                player.sendSystemMessage(Component.literal(
                                                "You haven't done a daily yet.")
                                        .withStyle(ChatFormatting.GRAY));
                            } else {
                                player.sendSystemMessage(Component.literal(
                                                "Streak " + entry.streak()
                                                        + ", " + entry.totalDone() + " total")
                                        .withStyle(ChatFormatting.GOLD));
                            }
                            return 1;
                        }))

                        .then(Commands.literal("help")
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    src.sendSuccess(() -> Component.literal("Daily Quests")
                                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                                    src.sendSuccess(() -> Component.literal("  /daily  Show today's riddle")
                                            .withStyle(ChatFormatting.WHITE), false);
                                    src.sendSuccess(() -> Component.literal("  /daily turnin  Hand in the item")
                                            .withStyle(ChatFormatting.WHITE), false);
                                    src.sendSuccess(() -> Component.literal("  /daily streak  Check your streak")
                                            .withStyle(ChatFormatting.WHITE), false);
                                    return 1;
                                }))

                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> {
                                    quests.reload();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Reloaded " + quests.size() + " quests."), false);
                                    return 1;
                                }))));
    }

    /** Shows today's riddle. Never the answer — that only appears on a turn-in. */
    static void show(ServerPlayer player, Quests quests, DailyState state) {
        String today = DailyState.dayKey(quests.settings().rolloverHourUtc());
        Quests.Quest quest = quests.forDay(today);

        if (quest == null) {
            player.sendSystemMessage(Component.literal("No quest today.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal("Today's riddle")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal(quest.riddle())
                .withStyle(ChatFormatting.WHITE));

        if (state.hasCompleted(player.getUUID(), today)) {
            player.sendSystemMessage(Component.literal("You've already solved it today.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.literal("[Hand it in]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.YELLOW)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/daily turnin"))));
        }

        int done = state.completedToday(today);
        if (done > 0) {
            player.sendSystemMessage(Component.literal(done + " solved so far today")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
