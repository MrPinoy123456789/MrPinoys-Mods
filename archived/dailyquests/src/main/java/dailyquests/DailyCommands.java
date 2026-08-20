package dailyquests;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /daily} — see the riddle, hand it in, check your streak.
 */
public final class DailyCommands {

    private DailyCommands() {}

    /** Registers player commands for viewing, submitting, and ranking daily quests. */
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
                                int grace = quests.settings().graceDays();
                                String today = DailyState.dayKey(
                                        quests.settings().rolloverHourUtc());
                                int left = DailyState.daysOfGraceLeft(
                                        entry.lastDay(), today, grace);
                                player.sendSystemMessage(Component.literal(left == 0
                                                ? "Turn one in today or the streak resets."
                                                : "Safe for " + left + " more day"
                                                        + (left == 1 ? "" : "s") + ".")
                                        .withStyle(left <= 1
                                                ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
                            }
                            return 1;
                        }))

                        .then(Commands.literal("top")
                                .executes(ctx -> top(ctx.getSource(), state, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> top(ctx.getSource(), state,
                                                IntegerArgumentType.getInteger(ctx, "page")))))

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
                                    src.sendSuccess(() -> Component.literal("  /daily top [page]  Streak leaderboard")
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

    private static int top(CommandSourceStack source, DailyState state, int page)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int perPage = 10;
        int total = state.size();
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        int clamped = Math.max(1, Math.min(page, pages));
        int offset = (clamped - 1) * perPage;

        List<DailyState.Entry> rows = state.topByStreak(offset, perPage + 1);
        boolean hasMore = rows.size() > perPage;
        if (hasMore) {
            rows = rows.subList(0, perPage);
        }

        source.sendSuccess(() -> Component.literal("────────────────")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("Daily Streaks")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal(
                        String.format("  %-18s %5s %5s", "Player", "Streak", "Total"))
                .withStyle(ChatFormatting.GRAY), false);

        int rank = offset;
        String viewerName = player.getName().getString();
        for (DailyState.Entry row : rows) {
            rank++;
            boolean isViewer = row.name().equals(viewerName);
            String formatted = String.format("%2d. %-17s %5d %5d",
                    rank, row.name(), row.streak(), row.totalDone());
            final Component line;
            if (isViewer) {
                line = Component.literal(formatted)
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("  <- You")
                                .withStyle(ChatFormatting.YELLOW));
            } else {
                line = Component.literal(formatted)
                        .withStyle(ChatFormatting.WHITE);
            }
            source.sendSuccess(() -> line, false);
        }
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  No streaks yet.")
                    .withStyle(ChatFormatting.GRAY), false);
        }

        source.sendSuccess(() -> Component.literal(
                        "Page " + clamped + " of " + pages
                                + (hasMore ? " (next: /daily top " + (clamped + 1) + ")" : ""))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("────────────────")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
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
