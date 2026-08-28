# M25 - Pocket2 Dungeon - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `Instances.java` lines 80-160: `InstanceRecord` and slot management.
   Child instance needs its own slot linked to parent.
2. `RunLifecycle.java` lines 177-375: enter/exit lifecycle. Child instance
   teardown reuses exit logic without the completion path.
3. `ROOM_UX_PLAN.md`'s `## M25` section ONLY: authoritative scope.

## Dependencies

Grep `AdventureGraph` in `src/main/java/`. If class exists, M11's rare-node
gating is in place.

Grep `InstanceRecord` in `Instances.java`. If record has `slot` and `origin`
fields, child instances can be linked.

## Goal

Dungeon within a dungeon. During a run, player finds a rare door in a
cleared encounter room leading to a short, intense sub-dungeon with a hard
timer (60-90s). Grab what you can before the clock runs out.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Child instance record

1. `InstanceRecord`: add optional `parentSlot` and `returnPos` fields.
   Child instance has own slot, own cells, no spawner gate, no completion
   pad.
2. `Instances`: allocate child slot adjacent to parent. Link child to
   parent via `parentSlot`.

### Step 2: Nested door placement

1. During encounter-room generation, `LayoutStamper` or `RoomContent`:
   place a special door block in cleared encounter rooms, gated by
   `AdventureGraph` transition logic. Rare, not guaranteed.
2. Door interaction: right-click creates child instance, teleports player
   in, records `returnPos` at door location.

### Step 3: Countdown timer

1. Child `InstanceRecord`: add `deadlineTick` field.
2. `Instances.onTick`: check child instances for deadline. On zero or
   player death: tear down child, teleport player to `returnPos` in parent
   instance.
3. Outer run clock keeps ticking. Time in Pocket2 is time the outer run
   counts.

### Step 4: Loot and content

1. Child dungeon: 3-5 cells, loose chests, loose drops, maybe 1-2 spawners.
   No completion pad, no keystone.
2. Rare drops: shell unlock tokens (M24), keystone upgrades, other rare
   items. Separate loot table: `pocketdungeons:chests/pocket2/`.
3. Death in Pocket2: eject to parent, outer run death penalty still applies.

## Constraints

- Child has no spawner gate and no completion pad. Only exit is timer or
  death.
- One child per parent. Player cannot nest Pocket2 inside Pocket2.
- Child instance torn down on parent teardown.
- Timer length: 60s default, configurable in `PocketDungeonsConfig`.
- No new dimension. Child instance lives in same dungeon dimension, own
  slot.

## Verification

- `build_mod` default `build` after each step.
- Headless: child `InstanceRecord` round-trips, tick watcher compiles.
- Live: find nested door, step through, 60s countdown, ejected back to
  parent room. Record in `LIVE_TEST_PASS.md`.
- Done when: player enters Pocket2, timer expires, returns to parent
  instance at entry point.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M25-handoff-completed.md` once landed.
