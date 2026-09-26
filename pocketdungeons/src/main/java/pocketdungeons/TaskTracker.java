package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * M33: the guided task line. Ten sequential tasks teaching the core loops
 * (selecting a door, descending, completing a run, the reroll and gamble
 * stations, visiting a friend, taming a Feral wolf, a Greater door, the Cube,
 * and finally feeding the engine), one active at a time: the lowest one this
 * player has not yet finished.
 *
 * <p>The line spans levels 1 through 15: the three stations unlock at 5, 10
 * and 15, and the engine tutorial is the capstone at 15. Once every task is
 * done, {@link #activeTask} returns {@code null} for good and the tracker
 * screen hands off to {@link BountyTracker}'s weekly bounties.
 *
 * <p>Modeled after dailyquests' quest line: a visible objective, a count, and
 * automatic detection from the mechanic itself rather than a turn-in step.
 * Unlike a daily quest there is no reset and no reward beyond the teaching.
 */
final class TaskTracker {

    private TaskTracker() {}

    /** One guided task. Order here is the sequence: the lowest incomplete one is active. */
    enum Task {
        SELECT_DOOR("select_door", "Select a Door", 1, 1),
        DESCEND("descend", "Descend", 1, 1),
        COMPLETE_RUN("complete_run", "Complete a Run", 1, 1),
        REROLL("reroll", "Reroll an Enchantment", 1, 5),
        VISIT_FRIEND("visit_friend", "Visit a Friend", 1, 5),
        GAMBLE("gamble", "Spend Emeralds at the Gamble Station", 16, 10),
        // Gated by the Feral theme, but also level-gated at 10 so a player
        // who never rolls Feral is not blocked past that point: the
        // grandfather clause below auto-completes it once level > 10.
        TAME_WOLF("tame_wolf", "Tame a Wolf", 1, 10),
        GREATER_DOOR("greater_door", "Open a Greater Door", 1, 15),
        EXTRACT_POWER("extract_power", "Extract a Power", 1, 15),
        // The capstone: feeding the engine is the last guided task. After
        // this, the tracker screen hands off to weekly bounties.
        FEED_ENGINE("feed_engine", "Feed the Engine", 3, 15);

        final String id;
        final String label;
        final int targetCount;
        final int minLevel;

        Task(String id, String label, int targetCount, int minLevel) {
            this.id = id;
            this.label = label;
            this.targetCount = targetCount;
            this.minLevel = minLevel;
        }
    }

    private static final Task[] ORDER = Task.values();

    /**
     * The lowest task {@code player} has not finished, or {@code null} once
     * every task is done. A task whose {@link Task#minLevel} sits strictly
     * below {@code level} is skipped (and recorded as complete) on the way
     * past it: a player already several levels beyond where a task would
     * normally introduce them to it has plainly already learned that lesson,
     * and gating them behind it retroactively would be busywork, not
     * teaching. {@code minLevel == 0} opts a task out of that grandfather
     * clause entirely.
     *
     * <p>Pure logic over {@code log}, no {@code ServerPlayer} or
     * {@code MinecraftServer} needed, so {@code TaskTrackerTest} can exercise
     * progression and level gating headlessly the same way
     * {@code DungeonLogTest} exercises {@link DungeonLog} itself.
     */
    static Task activeTask(DungeonLog log, UUID player, int level) {
        for (Task task : ORDER) {
            if (log.taskProgress(player, task.id) >= task.targetCount) {
                continue;
            }
            if (task.minLevel > 0 && level > task.minLevel) {
                log.setTaskProgress(player, task.id, task.targetCount);
                continue;
            }
            return task;
        }
        return null;
    }

    /** {@link #activeTask(DungeonLog, UUID, int)} for a live player, reading their server and keystone level. */
    static Task activeTask(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return null;
        }
        DungeonLog log = DungeonLog.forServer(server);
        return activeTask(log, player.getUUID(), log.get(player.getUUID()).keystoneLevel());
    }

    /**
     * Advances {@code task} by {@code amount} in {@code log}, if it is
     * {@code player}'s active task at {@code level} right now; otherwise a
     * no-op. Returns whether the sidecar actually changed, for
     * {@link #progress(ServerPlayer, Task, int)} to decide whether the
     * completion messaging below is warranted. Package-visible (rather than
     * private) so {@code TaskTrackerTest} can drive it directly.
     */
    static boolean progressCore(DungeonLog log, UUID player, int level, Task task, int amount) {
        if (activeTask(log, player, level) != task) {
            return false;
        }
        int current = log.taskProgress(player, task.id);
        int next = Math.min(task.targetCount, current + amount);
        if (next == current) {
            return false;
        }
        log.setTaskProgress(player, task.id, next);
        return true;
    }

    /**
     * Advances {@code task} by {@code amount}, if it is this player's active
     * task right now. A no-op otherwise: a hook fires whenever its own
     * mechanic happens (descending, feeding the engine, ...), regardless of
     * what the player's actual active task is, so every call site names the
     * task it means to progress and this checks that against
     * {@link #activeTask} before touching anything -- a player who opens a
     * door while GAMBLE happens to be active must not have that counted as
     * gamble progress.
     */
    static void progress(ServerPlayer player, Task task, int amount) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = player.getUUID();
        int level = log.get(id).keystoneLevel();
        if (!progressCore(log, id, level, task, amount) || log.taskProgress(id, task.id) < task.targetCount) {
            return;
        }

        Task upNext = activeTask(player);
        if (upNext != null) {
            player.sendSystemMessage(Component.literal(
                    task.label + " complete. Next: " + upNext.label + ".")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    task.label + " complete. That's every guided task done.")
                    .withStyle(ChatFormatting.AQUA));
        }
        // The tracker screen replaces the old scoreboard sidebar. It picks
        // the active task or, once every task is done, the weekly bounties on
        // its own, so a single refresh covers the handoff too.
        DungeonScreen.refreshTracker(server, id);
    }

    /**
     * The active task's progress line, e.g. "Feed the Engine 2/3", or
     * {@code null} once every task is done. Pure over {@code log} so the
     * tracker screen can render it for an offline owner the same way the join
     * chat reminder renders it for a live one.
     */
    static Component taskLine(DungeonLog log, UUID player, int level) {
        Task active = activeTask(log, player, level);
        if (active == null) {
            return null;
        }
        int progress = log.taskProgress(player, active.id);
        return Component.literal(active.label + " " + progress + "/" + active.targetCount)
                .withStyle(ChatFormatting.AQUA);
    }

    /** {@link #taskLine(DungeonLog, UUID, int)} for a live player. */
    static Component taskLine(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return null;
        }
        DungeonLog log = DungeonLog.forServer(server);
        return taskLine(log, player.getUUID(), log.get(player.getUUID()).keystoneLevel());
    }
}
