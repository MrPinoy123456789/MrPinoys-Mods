# M32 - Tutorial screen and engine label - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `DungeonScreen.java` lines 134-138: `idleContent()`. No-arg,
   returns static text. Add `owner` param for level check.
2. `DungeonScreen.java` lines 140-150: `previewContent`. Already
   takes `owner`, reads `DungeonLog.Entry.keystoneLevel()`. Append
   tutorial line when level 1.
3. `DungeonScreen.java` lines 205-214: `engineContent`. Title line
   says `"ENGINE"`. Change to `"ECHO SHARDS"`.
4. `DungeonScreen.java` lines 92-108: `updateDoor` and `summonDoor`.
   Both pass `content` through. Callers that pass `idleContent()`
   must now pass owner.

## Dependencies

Grep `idleContent()` in `src/main/java/`. Four call sites:
`Instances.stampLobby` (has `owner`), `RunLifecycle` lines 525-526
(has `record.owner`), `RunLifecycle` line 1002 (has `record.owner`),
`RoomBuilder` line 415 (has `record.owner`).

Grep `engineContent` in `src/main/java/`. Three call sites: all
pass either `null` or a `ServerPlayer`. No signature change needed.

Grep `DungeonLog.Entry` `keystoneLevel` in `src/main/java/`.
Returns int. Level 1 = first-time player.

## Goal

Two changes to `DungeonScreen` text content. (1) Engine screen
title says "ECHO SHARDS" not "ENGINE". (2) At keystone level 1,
door screen shows tutorial prompts: "Select the Oak Door" when
idle, "Pull the lever to descend!" when a door is selected.
Tutorial disappears at level 2+.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Engine screen label

1. `DungeonScreen.engineContent` line 211: change
   `Component.literal("ENGINE")` to
   `Component.literal("ECHO SHARDS")`.
2. No other changes. `fuelName` already resolves to "Echo Shard"
   from `Fuel.item()`.

### Step 2: Add owner param to idleContent

1. `DungeonScreen.idleContent`: add `UUID owner` param. When
   `owner` non-null and `DungeonLog.forServer(level.getServer())`
   `.get(owner).keystoneLevel() <= 1`: return
   `Component.literal("POCKET DUNGEONS").withStyle(GOLD)`
   `.append("\nSelect the Oak Door\nThen pull the lever to descend")`.
   Else: return current text.
2. Problem: `idleContent` is static and has no `ServerLevel`.
   Add `ServerLevel level` param alongside `UUID owner`.
   Both nullable: `stampLobby` passes `(level, owner)`,
   `RunLifecycle` passes `(level, record.owner)`.
3. Update all four call sites:
   - `Instances.stampLobby` line 465:
     `idleContent(level, owner)`
   - `RunLifecycle` line 525-526:
     `idleContent(level, record.owner)`
   - `RunLifecycle` line 1002:
     `idleContent(level, record.owner)`
   - `RoomBuilder` line 415:
     `idleContent(level, record.owner)`

### Step 3: Tutorial line in previewContent

1. `DungeonScreen.previewContent` lines 148-149: after building
   the existing `KEYSTONE`/theme/affix lines, if
   `offerLevel <= 1`: append
   `Component.literal("\nPull the lever to descend!")`
   `.withStyle(ChatFormatting.GREEN)`.
2. No signature change: `previewContent` already takes `owner`
   and derives `offerLevel` from `DungeonLog`.

## Constraints

- Tutorial text only at keystone level 1. Level 2+ gets current
  idle and preview text unchanged.
- Engine label change is one string literal. No logic change.
- `idleContent` gains `ServerLevel` and `UUID` params, both
  nullable. Null owner = current behavior (no tutorial).
- `previewContent` signature unchanged.
- `engineContent` signature unchanged.

## Verification

- `build_mod` default `build` after each step.
- Headless: no new test needed. Existing tests pass.
- Live: level-1 player sees "Select the Oak Door" on idle screen,
  "Pull the lever to descend!" on preview screen. Level-2 player
  sees current text. Engine screen says "ECHO SHARDS". Record in
  `LIVE_TEST_PASS.md`.
- Done when: engine screen reads "ECHO SHARDS", level-1 door
  screen shows tutorial prompts, level 2+ unchanged.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M32-handoff-completed.md` once landed.
