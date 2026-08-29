package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

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
     * The one server-wide objective, shared by every player: {@link DisplaySlot#LIST}
     * (the tab list) shows each player's score against it, and the score is that
     * player's active task's position in {@link #ORDER} (1-based). The readable
     * label lives on the door screen ({@link #taskLine}), not here -- a tab-list
     * number is the compact, always-visible cue; the sentence is the room's job.
     */
    private static final String OBJECTIVE_NAME = "pd_task";

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
        if (server == null || activeTask(player) != task) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        int current = log.taskProgress(player.getUUID(), task.id);
        int next = Math.min(task.targetCount, current + amount);
        if (next == current) {
            return;
        }
        log.setTaskProgress(player.getUUID(), task.id, next);
        if (next < task.targetCount) {
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
        syncScoreboard(server, player);
    }

    /**
     * Sets this player's {@code pd_task} score to their active task's position
     * (1-based), creating the shared objective on first use. Once this player
     * has no active task left, their score is cleared instead; if that leaves
     * nobody tracked on the objective at all, the objective itself is removed
     * rather than sitting on the tab list empty forever.
     */
    static void syncScoreboard(MinecraftServer server, ServerPlayer player) {
        Scoreboard scoreboard = server.getScoreboard();
        Task active = activeTask(player);
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (active == null) {
            if (objective != null) {
                scoreboard.resetSinglePlayerScore(player, objective);
                if (scoreboard.listPlayerScores(objective).isEmpty()) {
                    scoreboard.removeObjective(objective);
                }
            }
            return;
        }
        if (objective == null) {
            objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
                    Component.literal("Pocket Dungeons"), ObjectiveCriteria.RenderType.INTEGER,
                    false, null);
            scoreboard.setDisplayObjective(DisplaySlot.LIST, objective);
        }
        scoreboard.getOrCreatePlayerScore(player, objective).set(active.ordinal() + 1);
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
