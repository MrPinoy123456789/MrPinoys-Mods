package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * M33: the guided task line. Ten sequential tasks teaching the core loops
 * (selecting a door, descending, completing a run, feeding the engine,
 * visiting a friend, a Greater door, the three stations, taming a wolf), one
 * active at a time: the lowest one this player has not yet finished.
 *
 * <p>Modeled after dailyquests' quest line: a visible objective, a count, and
 * automatic detection from the mechanic itself rather than a turn-in step.
 * Unlike a daily quest there is no reset and no reward beyond the teaching --
 * once every task is done, {@link #activeTask} returns {@code null} for good.
 */
final class TaskTracker {

    private TaskTracker() {}

    /** One guided task. Order here is the sequence: the lowest incomplete one is active. */
    enum Task {
        SELECT_DOOR("select_door", "Select a Door", 1, 1),
        DESCEND("descend", "Descend", 1, 1),
        COMPLETE_RUN("complete_run", "Complete a Run", 1, 1),
        FEED_ENGINE("feed_engine", "Feed the Engine", 3, 2),
        VISIT_FRIEND("visit_friend", "Visit a Friend", 1, 5),
        GREATER_DOOR("greater_door", "Open a Greater Door", 1, 15),
        GAMBLE("gamble", "Spend Emeralds at Kadala", 16, 20),
        REROLL("reroll", "Reroll an Enchantment", 1, 25),
        EXTRACT_POWER("extract_power", "Extract a Power", 1, 30),
        // No level gate (0): this one is gated by the Feral theme and by
        // being last in sequence instead, so it is never grandfathered away
        // by minLevelBelowCurrent below.
        TAME_WOLF("tame_wolf", "Tame a Wolf", 1, 0);

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
     * below the player's current keystone level is skipped (and recorded as
     * complete) on the way past it: a player already several levels beyond
     * where a task would normally introduce them to it has plainly already
     * learned that lesson, and gating them behind it retroactively would be
     * busywork, not teaching. {@code minLevel == 0} opts a task out of that
     * grandfather clause entirely.
     */
    static Task activeTask(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return null;
        }
        DungeonLog log = DungeonLog.forServer(server);
        int level = log.get(player.getUUID()).keystoneLevel();
        for (Task task : ORDER) {
            if (log.taskProgress(player.getUUID(), task.id) >= task.targetCount) {
                continue;
            }
            if (task.minLevel > 0 && level > task.minLevel) {
                log.setTaskProgress(player.getUUID(), task.id, task.targetCount);
                continue;
            }
            return task;
        }
        return null;
    }

    /**
     * Advances the active task by {@code amount}, if this player has one.
     * A no-op if {@code player} has already finished every task, or if the
     * currently active task is not the one the caller thinks it is progressing
     * (every hook names its own task implicitly by calling this at all, but
     * the active task can only ever be advanced, never targeted directly, so
     * a hook firing while a different task is active is simply ignored).
     */
    static void progress(ServerPlayer player, int amount) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Task active = activeTask(player);
        if (active == null) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        int current = log.taskProgress(player.getUUID(), active.id);
        int next = Math.min(active.targetCount, current + amount);
        if (next == current) {
            return;
        }
        log.setTaskProgress(player.getUUID(), active.id, next);
        if (next < active.targetCount) {
            return;
        }

        Task upNext = activeTask(player);
        if (upNext != null) {
            player.sendSystemMessage(Component.literal(
                    active.label + " complete. Next: " + upNext.label + ".")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    active.label + " complete. That's every guided task done.")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** The active task's line for the door screen, e.g. "Feed the Engine 2/3", or {@code null} once every task is done. */
    static Component taskLine(ServerPlayer player) {
        Task active = activeTask(player);
        if (active == null) {
            return null;
        }
        MinecraftServer server = player.level().getServer();
        int progress = server == null ? 0
                : DungeonLog.forServer(server).taskProgress(player.getUUID(), active.id);
        return Component.literal(active.label + " " + progress + "/" + active.targetCount)
                .withStyle(ChatFormatting.AQUA);
    }
}
