# M24 - Room shells and prestige - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `RoomBuilder.java`: `buildShell` method. `rebuildShell` orchestrates
   capture, stamp new shell, re-place interior.
2. `DungeonLog.java`: `Entry` record. New `unlockedShells` set and
   `roomCompletions` int go here, same shape as `completedThemes`.
3. `ROOM_UX_PLAN.md`'s `## M24` section ONLY: authoritative scope.

## Dependencies

Grep `isShell` in `RoomProtection.java`. If method exists, M18's immutable
shell has landed. Without it, this milestone cannot proceed: swapping shell
blocks would destroy player-placed blocks on walls.

Grep `rebuildShell` in `RoomBuilder.java`. If absent, this milestone creates
it.

## Goal

Players discover and unlock alternate shell materials (sandstone, deepslate,
nether brick) from rare adventure nodes and long-term room ownership. Shell
swap replaces only immutable shell blocks; interior stays untouched.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Shell unlock storage

1. `DungeonLog.Entry`: add `unlockedShells` (Set<String>, default empty) and
   `roomCompletions` (int, default 0). Codec fields with safe defaults, same
   pattern as `completedThemes`.
2. `DungeonLog`: getters and setters for both.

### Step 2: `RoomBuilder.rebuildShell`

1. New method: `captureAndSave` interior -> `buildShell` with new material ->
   `RoomStore.place` interior back. All three primitives exist.
2. Shell material resolved from unlock name to block palette. Ship 3-4
  palettes: sandstone, deepslate, nether brick, plus default oak.

### Step 3: Unlock paths

1. Rare adventure nodes: add shell unlock tokens to rare-node completion
   chests. `AdventureGraph` already decides rare nodes; add a loot table
   entry.
2. Prestige: increment `roomCompletions` in `RunLifecycle.completeRun` when
   the room has not been reset since last completion. Threshold (e.g. 10)
   unlocks a shell.

### Step 4: Menu option

1. Add "Change shell" to the wall lodestone menu (M21's `DialogScreens`).
2. Lists unlocked shells. Selecting one calls `RoomBuilder.rebuildShell`.
3. If no alternate shells unlocked: show default + grayed-out list (the
   menu option is the tutorial).

## Constraints

- Shell swap preserves every interior block. Test with furniture, chests,
  stations in place.
- Unlock list is per-player, not per-room. A player carries unlocks across
  room resets.
- Prestige count resets if the room is reset (owner re-captures). Holding
  the same room is the achievement.
- No custom block registrations. Shell materials are vanilla blocks.

## Verification

- `build_mod` default `build` after each step.
- Headless: codec round-trips `unlockedShells` and `roomCompletions`.
  `rebuildShell` compiles.
- Live: unlock a shell, open menu, select "Change shell," walls swap while
  interior stays. Record in `LIVE_TEST_PASS.md`.
- Done when: shell swap preserves all interior blocks and furniture.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M24-handoff-completed.md` once landed.
