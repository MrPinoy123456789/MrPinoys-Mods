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

    /** Tasks 1-3 have no real level gate (minLevel 1, and offer level floors at 1), so a fresh player walks them in order. */
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
        check(TaskTracker.activeTask(log, PLAYER, 1), TaskTracker.Task.FEED_ENGINE,
                "finishing COMPLETE_RUN advances to FEED_ENGINE (minLevel 2)");
    }

    /**
     * A player already well past a task's minLevel (a veteran adopting the
     * feature mid-campaign) has that task auto-completed rather than gated
     * behind it retroactively; a player still at or below the threshold does
     * not get skipped.
     */
    private static void testLevelGateGrandfather() {
        DungeonLog log = new DungeonLog();
        // Skip straight past the ungated tasks 1-3 to land on FEED_ENGINE (minLevel 2).
        progress(log, 0, TaskTracker.Task.SELECT_DOOR, 1);
        progress(log, 0, TaskTracker.Task.DESCEND, 1);
        progress(log, 0, TaskTracker.Task.COMPLETE_RUN, 1);

        check(TaskTracker.activeTask(log, PLAYER, 2), TaskTracker.Task.FEED_ENGINE,
                "level exactly at minLevel is not grandfathered away");

        // A level-18 player has plainly already fed an engine, opened a Greater
        // door, and visited a friend by that point (their minLevels are 2, 5,
        // 15); GAMBLE's own minLevel (20) has not been passed yet, so it is
        // reached normally rather than also grandfathered away.
        TaskTracker.Task active = TaskTracker.activeTask(log, PLAYER, 18);
        check(active, TaskTracker.Task.GAMBLE, "well past several minLevels grandfathers straight to GAMBLE");
        check(log.taskProgress(PLAYER, TaskTracker.Task.FEED_ENGINE.id), 3,
                "grandfathered FEED_ENGINE is recorded as fully complete");
        check(log.taskProgress(PLAYER, TaskTracker.Task.GREATER_DOOR.id), 1,
                "grandfathered GREATER_DOOR is recorded as fully complete");

        // TAME_WOLF's minLevel is 0: never grandfathered, however high the
        // level, since it is gated by the Feral theme and by being last in
        // sequence instead.
        DungeonLog wolfLog = new DungeonLog();
        for (TaskTracker.Task task : TaskTracker.Task.values()) {
            if (task != TaskTracker.Task.TAME_WOLF) {
                wolfLog.setTaskProgress(PLAYER, task.id, task.targetCount);
            }
        }
        check(TaskTracker.activeTask(wolfLog, PLAYER, 999), TaskTracker.Task.TAME_WOLF,
                "TAME_WOLF is never grandfathered away, no matter how high the level");
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
        check(TaskTracker.activeTask(log, PLAYER, 20), TaskTracker.Task.GAMBLE, "GAMBLE is now active");
        for (int i = 0; i < 20; i++) {
            progress(log, 20, TaskTracker.Task.GAMBLE, 1);
        }
        check(log.taskProgress(PLAYER, TaskTracker.Task.GAMBLE.id), 16,
                "progress past the target caps at targetCount rather than overshooting");
        check(TaskTracker.activeTask(log, PLAYER, 20), TaskTracker.Task.REROLL,
                "capping at the target still advances to the next task");
    }

    /** The task-progress sidecar round trips through DungeonLog's codec. */
    private static void testSidecarRoundTrip() {
        DungeonLog log = new DungeonLog();
        progress(log, 0, TaskTracker.Task.SELECT_DOOR, 1);
        progress(log, 0, TaskTracker.Task.DESCEND, 1);
        log.setTaskProgress(PLAYER, TaskTracker.Task.FEED_ENGINE.id, 2);

        com.google.gson.JsonElement encoded = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, log).result().orElseThrow();
        DungeonLog decoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, encoded).result().orElseThrow().getFirst();

        check(decoded.taskProgress(PLAYER, TaskTracker.Task.SELECT_DOOR.id), 1, "SELECT_DOOR progress round trips");
        check(decoded.taskProgress(PLAYER, TaskTracker.Task.DESCEND.id), 1, "DESCEND progress round trips");
        check(decoded.taskProgress(PLAYER, TaskTracker.Task.FEED_ENGINE.id), 2, "FEED_ENGINE progress round trips");
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
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
