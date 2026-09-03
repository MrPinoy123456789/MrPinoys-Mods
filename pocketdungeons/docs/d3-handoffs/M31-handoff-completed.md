# M31 - Dungeon shell protection - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `RoomProtection.java` lines 42-66: `beforeBlockBreak`. Checks
   `Instances.roomOwnerAt` (player room only). Add dungeon cell check.
2. `Instances.java` lines 1247-1261: `roomRecordAt`. Iterates
   `InstanceRegistry.bySlot`, checks `roomCellOrigin` bounds. Add
   parallel `dungeonRecordAt`.
3. `RitualListener.java` lines 107-122: placement denial. Same
   `roomOwnerAt` check. Add dungeon cell check.

## Dependencies

Grep `PlanGeometry` in `src/main/java/`. If class exists, enumerates
cell positions. Needed for containment check.

Grep `completed` in `InstanceRecord.java`. If `Set<UUID> completed`
exists, non-empty means run finished: protection lifts.

## Goal

During active runs, dungeon cells are block-break and block-place
protected. After first completion, protection lifts automatically.
Player room protection (M18) unchanged. Enables M30 IRON_DOOR as
gated passage: players must power redstone, cannot mine around it.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: `Instances.dungeonRecordAt`

1. New method: `static InstanceRecord dungeonRecordAt(BlockPos pos)`.
2. Iterate `InstanceRegistry.bySlot`. For each record with non-null
   `layout` and `record.completed.isEmpty()`: check if `pos` inside
   any cell in `record.layout.geometry().cells()`.
3. Skip room cell (`record.roomCellOrigin`): covered by `roomRecordAt`.
4. Return matching record or null.

### Step 2: `RoomProtection` dungeon check

1. In `beforeBlockBreak`, after existing `roomOwner` check, add:
   `InstanceRecord dr = Instances.dungeonRecordAt(pos)`. If non-null:
   return false.
2. All blocks inside dungeon cells denied, not just shell. No per-cell
   shell test needed.

### Step 3: `RitualListener` placement denial

1. In placement section (lines 107-122), after `placementRoomOwner`
   check, add: `Instances.dungeonRecordAt(placementPos)`. If non-null:
   return `InteractionResult.FAIL`.
2. Allow chest open, lever/button use, spawner interaction. Only
   block break and block place denied.

### Step 4: Automatic lift on completion

1. `dungeonRecordAt` checks `record.completed.isEmpty()`. Once first
   completion happens, returns null, protection lifts. No explicit
   removal call.
2. Verify: after `completeRun` sets `record.completed`, players mine
   freely. Player room still protected by `roomRecordAt` + `isShell`.

### Step 5: Protection-lift indicator

1. When `completeRun` fires and `record.completed` goes non-empty,
   send a chat message to all members in the dungeon dimension:
   `Component.literal("The dungeon's shell has weakened. You can break blocks now.")`
   styled `ChatFormatting.GREEN`.
2. Add a `Chime` call (or reuse existing) for a short positive sound
   cue on completion, distinct from the keystone-level-up chime.
3. Message sent once, at the moment of first completion, not repeated.

### Step 6: Tests

1. `RoomProtection`: break denied inside dungeon cell during active
   run. Allowed after completion.
2. `dungeonRecordAt`: returns record inside dungeon cell, null
   outside, null after completion.
3. `adminBuild` dungeon: wall break denied. Complete run. Wall break
   allowed.

## Constraints

- All blocks inside dungeon cells protected during active runs, not
  just shell. Interior, spawners, chests unbreakable.
- Trial spawner activation, chest opening, lever/button use allowed.
  Only block break and block place denied.
- Player room protection (M18) unchanged.
- Protection lifts via `record.completed.isEmpty()` check. No
  explicit removal call.
- Protection-lift indicator: one chat message + sound on first
  completion. Not repeated on subsequent completions or re-entry.
- `dungeonRecordAt` skips room cell: avoids double-check with
  `roomRecordAt`.
- Cell bounds check is 16x16x7 per cell. Bedrock margin not checked:
  bedrock unbreakable in vanilla.

## Verification

- `build_mod` default `build` after each step.
- Headless: `RoomProtection` test passes. `dungeonRecordAt` test
  passes. `adminBuild`: wall break denied during run, allowed after.
- Live: enter dungeon, break wall: denied. Complete run, break wall:
  allowed. Player room walls still denied. Record in
  `LIVE_TEST_PASS.md`.
- Done when: dungeon blocks unbreakable during active runs, breakable
  after completion, player room protection unchanged, players notified
  when protection lifts.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M31-handoff-completed.md` once landed.
