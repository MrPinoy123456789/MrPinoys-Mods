package pocketdungeons;

import java.util.UUID;

/**
 * Regression for {@link TaskTracker}'s sequencing, level-gate grandfathering,
 * and progress arithmetic, exercised through the pure
 * {@code activeTask(DungeonLog, UUID, int)}/{@code progress} overloads: no
 * {@code ServerPlayer} or {@code MinecraftServer} is available in this
 * headless test, the same constraint {@code DungeonLogTest} works under.
 */
public class TaskTrackerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    public static void main(String[] args) {
        testSequence();
        testLevelGateGrandfather();
        testWrongTaskIsNoOp();
        testCapsAtTarget();
        testSidecarRoundTrip();
        System.out.println("TaskTrackerTest passed");
    }

    /** Tasks 1-3 have no real level gate (minLevel 1), so a fresh player walks them in order. */
    private static void testSequence() {
        DungeonLog log = new DungeonLog();
        check(TaskTracker.activeTask(log, PLAYER, 0), TaskTracker.Task.SELECT_DOOR,
                "a fresh player's first task is selecting a door");

        progress(log, 0, TaskTracker.Task.SELECT_DOOR, 1);
        check(TaskTracker.activeTask(log, PLAYER, 0), TaskTracker.Task.DESCEND,
                "finishing SELECT_DOOR advances to DESCEND");

        progress(log, 0, TaskTracker.Task.DESCEND, 1);
        check(TaskTracker.activeTask(log, PLAYER, 0), TaskTracker.Task.COMPLETE_RUN,
                "finishing DESCEND advances to COMPLETE_RUN");

        progress(log, 1, TaskTracker.Task.COMPLETE_RUN, 1);
        check(TaskTracker.activeTask(log, PLAYER, 1), TaskTracker.Task.REROLL,
                "finishing COMPLETE_RUN advances to REROLL (minLevel 5)");
    }

    /**
     * A player already well past a task's minLevel (a veteran adopting the
     * feature mid-campaign) has that task auto-completed rather than gated
     * behind it retroactively; a player still at or below the threshold does
     * not get skipped.
     */
    private static void testLevelGateGrandfather() {
        DungeonLog log = new DungeonLog();
        // Skip straight past the ungated tasks 1-3 to land on REROLL (minLevel 5).
        progress(log, 0, TaskTracker.Task.SELECT_DOOR, 1);
        progress(log, 0, TaskTracker.Task.DESCEND, 1);
        progress(log, 0, TaskTracker.Task.COMPLETE_RUN, 1);

        check(TaskTracker.activeTask(log, PLAYER, 5), TaskTracker.Task.REROLL,
                "level exactly at minLevel is not grandfathered away");

        // A level-12 player has plainly already rerolled, visited a friend,
        // and gambled by that point (minLevels 5, 5, 10); TAME_WOLF's own
        // minLevel (10) has been passed, so it is grandfathered too;
        // GREATER_DOOR (minLevel 15) has not been reached yet.
        TaskTracker.Task active = TaskTracker.activeTask(log, PLAYER, 12);
        check(active, TaskTracker.Task.GREATER_DOOR,
                "well past several minLevels grandfathers straight to GREATER_DOOR");
        check(log.taskProgress(PLAYER, TaskTracker.Task.REROLL.id), 1,
                "grandfathered REROLL is recorded as fully complete");
        check(log.taskProgress(PLAYER, TaskTracker.Task.VISIT_FRIEND.id), 1,
                "grandfathered VISIT_FRIEND is recorded as fully complete");
        check(log.taskProgress(PLAYER, TaskTracker.Task.GAMBLE.id), 16,
                "grandfathered GAMBLE is recorded as fully complete");
        check(log.taskProgress(PLAYER, TaskTracker.Task.TAME_WOLF.id), 1,
                "grandfathered TAME_WOLF is recorded as fully complete");

        // A level-16 player has passed every minLevel (5, 5, 10, 10, 15, 15,
        // 15): every task is grandfathered, activeTask returns null, and
        // bounties unlock.
        check(TaskTracker.activeTask(log, PLAYER, 16), null,
                "past every minLevel, no guided task remains; bounties unlock");
        check(log.taskProgress(PLAYER, TaskTracker.Task.GREATER_DOOR.id), 1,
                "grandfathered GREATER_DOOR is recorded as fully complete");
        check(log.taskProgress(PLAYER, TaskTracker.Task.EXTRACT_POWER.id), 1,
                "grandfathered EXTRACT_POWER is recorded as fully complete");
        check(log.taskProgress(PLAYER, TaskTracker.Task.FEED_ENGINE.id), 3,
                "grandfathered FEED_ENGINE is recorded as fully complete");
    }

    /** A hook that names the wrong task (not currently active) must not touch the sidecar. */
    private static void testWrongTaskIsNoOp() {
        DungeonLog log = new DungeonLog();
        check(TaskTracker.activeTask(log, PLAYER, 0), TaskTracker.Task.SELECT_DOOR,
                "active task starts at SELECT_DOOR");
        progress(log, 0, TaskTracker.Task.GAMBLE, 1);
        check(log.taskProgress(PLAYER, TaskTracker.Task.GAMBLE.id), 0,
                "progressing an inactive task is a no-op");
        check(TaskTracker.activeTask(log, PLAYER, 0), TaskTracker.Task.SELECT_DOOR,
                "the inactive-task no-op leaves the active task unchanged");
    }

    /** GAMBLE's target is 16: repeated progress caps at the target rather than overshooting. */
    private static void testCapsAtTarget() {
        DungeonLog log = new DungeonLog();
        for (TaskTracker.Task task : TaskTracker.Task.values()) {
            if (task.ordinal() < TaskTracker.Task.GAMBLE.ordinal()) {
                log.setTaskProgress(PLAYER, task.id, task.targetCount);
            }
        }
        check(TaskTracker.activeTask(log, PLAYER, 10), TaskTracker.Task.GAMBLE, "GAMBLE is now active");
        for (int i = 0; i < 20; i++) {
            progress(log, 10, TaskTracker.Task.GAMBLE, 1);
        }
        check(log.taskProgress(PLAYER, TaskTracker.Task.GAMBLE.id), 16,
                "progress past the target caps at targetCount rather than overshooting");
        check(TaskTracker.activeTask(log, PLAYER, 10), TaskTracker.Task.TAME_WOLF,
                "capping at the target still advances to the next task");
    }

    /** The task-progress sidecar round trips through DungeonLog's codec. */
    private static void testSidecarRoundTrip() {
        DungeonLog log = new DungeonLog();
        progress(log, 0, TaskTracker.Task.SELECT_DOOR, 1);
        progress(log, 0, TaskTracker.Task.DESCEND, 1);
        log.setTaskProgress(PLAYER, TaskTracker.Task.REROLL.id, 1);

        com.google.gson.JsonElement encoded = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, log).result().orElseThrow();
        DungeonLog decoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, encoded).result().orElseThrow().getFirst();

        check(decoded.taskProgress(PLAYER, TaskTracker.Task.SELECT_DOOR.id), 1, "SELECT_DOOR progress round trips");
        check(decoded.taskProgress(PLAYER, TaskTracker.Task.DESCEND.id), 1, "DESCEND progress round trips");
        check(decoded.taskProgress(PLAYER, TaskTracker.Task.REROLL.id), 1, "REROLL progress round trips");
        check(decoded.taskProgress(PLAYER, TaskTracker.Task.GAMBLE.id), 0, "an untouched task decodes to 0");

        // A dungeon_log.dat written before M33 has no task_progress field at all.
        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        old.add("players", new com.google.gson.JsonArray());
        DungeonLog legacy = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, old).result().orElseThrow().getFirst();
        check(legacy.taskProgress(PLAYER, TaskTracker.Task.SELECT_DOOR.id), 0,
                "pre-M33 save defaults every task's progress to 0");
    }

    private static void progress(DungeonLog log, int level, TaskTracker.Task task, int amount) {
        TaskTracker.progressCore(log, PLAYER, level, task, amount);
    }

    private static void check(Object actual, Object expected, String what) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
