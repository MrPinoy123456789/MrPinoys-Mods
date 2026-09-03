# M33 - Guided tasks via tracker screen - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.
>
> **Note:** the scoreboard sidebar described below was replaced during
> implementation by a physical tracker screen in the player's room
> (`DungeonScreen.updateTracker`). The task logic, sidecar, and hooks
> are unchanged; only the display surface differs. See
> `plans/COMPLETED-MILESTONES.md` for the as-built description.

## Read before writing anything

1. `DungeonScreen.java` lines 134-150: `idleContent`,
   `previewContent`. Task lines append here.
2. `DungeonLog.java` lines 127-131: `Entry` record. Task
   progress needs sidecar, not Entry field.
3. `RunLifecycle.java` lines 389, 750: `chooseOffer`,
   `completeRun`. Hook points.
4. `RitualListener.java` lines 168, 288: `Fuel.bank`,
   `selectDoor`. Hook points.
5. `GambleStation.java` line 75, `RerollStation.java` line
   103, `CubeStation.java` line 119: `onUse` returns.

## Dependencies

Grep `Scoreboard` in `src/main/java/`: no hits. Vanilla
`server.getScoreboard()`, `Criteria.DUMMY`, `ObjectiveScore`.
Stable, no jar check.

Grep `DungeonLog.Entry`: 16 fields. Task progress as sidecar
`Map<UUID, Map<String, Integer>>`, codec `task_progress`,
default empty. Avoids Entry migration.

Grep `VisitService.visit`: one call in DialogScreens.

## Goal

Guided task system teaching core loops via scoreboard. Tasks
sequential, level-gated, one active at a time. Progress on
door screen. Inspired by dailyquests: visible objective,
count, automatic detection.

## Task list (sequential, lowest incomplete is active)

1. Select a Door (lvl 1, x1): `selectDoor`.
2. Descend (lvl 1, x1): `chooseOffer` success.
3. Complete a Run (lvl 1, x1): `completeRun`.
4. Feed the Engine (lvl 2, x3): engine handler after `Fuel.bank`.
5. Visit a Friend (lvl 5, x1): `VisitService.visit`.
6. Open a Greater Door (lvl 15, x1): `chooseOffer`, `step>=2`.
7. Spend Emeralds at Kadala (lvl 20, x16): `GambleStation.onUse`.
8. Reroll an Enchantment (lvl 25, x1): `RerollStation.onUse`.
9. Extract a Power (lvl 30, x1): `CubeStation.onUse` extract.
10. Tame a Wolf (Feral, x1): `EntityTameEvent` in dungeon dim.

Tasks 1-3: tutorial. 4-10: guides. Task below minLevel:
auto-completed.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: TaskTracker and DungeonLog sidecar

1. New `TaskTracker.java`: enum `Task(id, label, targetCount,
   minLevel)`. `progress(server, player, amount)`,
   `activeTask(player)`, `taskLine(player)`.
2. `progress`: increments via DungeonLog sidecar, checks
   completion, advances, chat on completion. Auto-completes
   tasks below keystoneLevel.
3. `taskLine`: `Component` `"Feed the Engine 2/3"` in
   `ChatFormatting.AQUA`, or null.
4. `DungeonLog`: add `Map<UUID, Map<String, Integer>>
   taskProgress`. Codec: optional `task_progress`, default
   empty. `taskProgress(player, taskId)`,
   `setTaskProgress(player, taskId, count)`.

### Step 2: Scoreboard integration

1. `syncScoreboard(server, player)`: `Objective` `pd_task`,
   `Criteria.DUMMY`, slot `LIST`. Score: active task number.
   Display name: `"Pocket Dungeons"`.
2. On completion: remove, create next. All done: remove.
3. Per-player label on door screen (Step 4), not scoreboard.

### Step 3: Hook into existing events

1. `selectDoor` line 288: `progress(server, player, 1)`.
2. `chooseOffer` line 389: progress `DESCEND`. If `step>=2`,
   progress `GREATER_DOOR`.
3. `completeRun` line 750: progress `COMPLETE_RUN`.
4. RitualListener line 168: after `Fuel.bank`, progress
   `FEED_ENGINE`.
5. `GambleStation.onUse` before return: progress `GAMBLE`.
6. `RerollStation.onUse` before return: progress `REROLL`.
7. `CubeStation.onUse` extract: progress `EXTRACT_POWER`.
8. `VisitService.visit` success: progress `VISIT_FRIEND`.
9. `EntityTameEvent`: wolf tamed in dungeon dim, progress
   `TAME_WOLF`.

### Step 4: Door screen and login sync

1. `idleContent`, `previewContent`, `runContent`: append
   `TaskTracker.taskLine(owner)`.
2. `syncScoreboard` on each screen update.
3. Player join: `syncScoreboard`, chat with active task.

## Constraints

- One active task: lowest incomplete. Sequential, no skip.
- Task below minLevel: auto-completed.
- Progress persists via DungeonLog sidecar.
- Scoreboard: server-wide, one objective. Label on door screen.
- Wolf tame: dungeon dimension only.
- No new commands or dialogs. `TaskTracker` new class.
  Existing classes: `progress` calls at hook points only.

## Verification

- `build_mod` default `build` after each step.
- Headless: `TaskTrackerTest`: progression, level gating,
  sidecar round-trip. Existing tests pass.
- Live: new player sees "Select a Door 0/1". Right-click:
  "Descend 0/1". Pull lever: "Complete a Run 0/1". Level 2:
  "Feed the Engine 0/3". Record in `LIVE_TEST_PASS.md`.
- Done when: tasks surface on door screen, progress via
  scoreboard, advance automatically, persist across restarts.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M33-handoff-completed.md` once landed.
