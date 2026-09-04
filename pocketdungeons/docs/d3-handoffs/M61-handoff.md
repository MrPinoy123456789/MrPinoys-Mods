# M61 - Stories - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/SITUATIONS_SPEC.md` section 13 (all of it, about 90 lines).
  It is the authoritative scope. 13.4 is an invariant, not advice.
- `docs/DISCOVERIES.md` traps 20 and 21. Trap 20 is the failure mode this
  milestone is most likely to reintroduce.
- `RoomTemplateGenerator.templateOutDir` and `buildCellWithPalette`, for how a
  template is built and captured today.
- `BedrockEnvelope.applyToCell`, for the sub-floor and wall ring loops.

## Dependencies

```bash
grep -n "stories" pocketdungeons/src/main/java/pocketdungeons/DungeonRoomMeta.java
grep -rn "CEILING_Y + 1" pocketdungeons/src/main/java/pocketdungeons/
grep -n "canonicalDoorSlots" pocketdungeons/src/main/java/pocketdungeons/RoomManifest.java
```

First grep should be empty (nothing built yet). Second lists every place that
assumes a cell's vertical extent; you must account for all of them. Third is
the door slot math that shifts when a template's anchor moves.

## Goal

A room may declare `stories: 2` and own the 16 x 16 x 9 volume directly
beneath its own cell, private to it. Ship exactly one room using it. The other
48 templates must regenerate byte identical.

## Implementation plan

1. **Schema.** Add `stories` (int, default 1) to `DungeonRoomMeta`: the record,
   the codec, the constructor chain. Reject anything outside 1..2 the way
   `access` is rejected.
2. **Spec plumbing.** Add `stories(int)` to `RoomSpec`, defaulting to 1.
3. **Anchor and size.** In `RoomTemplateGenerator`, a template's capture origin
   becomes `cellOrigin.below((stories - 1) * STORY_HEIGHT)` and its size grows
   by the same amount, where `STORY_HEIGHT` is `CEILING_Y + 2` (interior plus
   both bedrock layers). One story rooms must produce an identical anchor to
   today: assert this by regenerating and diffing.
4. **Door slot offset.** `RoomManifest.canonicalDoorSlots` and the mask
   derivation read template local y. Offset both by the same `(stories - 1) *
   STORY_HEIGHT`. Trap 20 lives here.
5. **Envelope.** `BedrockEnvelope.applyToCell` puts the sub-floor layer under
   the *lowest* story and extends the wall rings to cover every story. The
   over-ceiling layer does not move.
6. **Bounds.** `PlanGeometry.bounds` and `InstanceRegistry.maximalBounds` both
   build an `AABB` whose floor is `origin.getY()`. Lower that floor by the
   deepest `stories` in the layout so teardown, entity sweeps and protection
   cover the lower story. Note `PlanGeometry.chunks` is per cell and needs no
   change: a lower story shares its cell's chunk column.
7. **Return path check.** At stamp time, for any room with `stories` greater
   than 1, assert a climbable column exists from the lowest story floor to the
   entrance floor (ladder, water, soul sand, or a stair of solid blocks). Log
   an error naming the room and refuse to stamp it if absent. Spec 13.4.
8. **The pilot room.** Give Slime Pit `stories: 2`: the bounce reaches the
   upper floor, the reward sits on the lower one, and a ladder is the return.
   One room only. Do not convert Gallery, Chasm or Blaze Loft in this
   milestone.

## Constraints

- The lower story has no doorways and is not a `PlanCell`. If you find
  yourself editing `LayoutPlanner`, `RoomSelector`, `DungeonShape` or
  `DoorMask`, stop: the milestone is scoped to avoid all four, and spec 13.5
  says why.
- The door mask stays four bits and the coverage floor stays at 53 pairs.
- Never write into the doorway plane at x=15 (trap 20).

## Verification

`.\gradlew.bat build --offline`, then headless via the dev server driver
described in `docs/DISCOVERIES.md` under "Dev server testing harness":

- `/dungeon admin gentemplates`, rebuild, restart. Expect `Loaded 49 dungeon
  rooms` with no rejection line.
- `/dungeon admin coverage` must still report all 53 (mask, role) pairs.
- `/dungeon admin plansurvey 30` must report 30 of 30.
- Door jigsaw audit: decompress every `.nbt` and assert exactly three
  `pocketdungeons:door` jigsaws per declared door. A one story room that
  regenerated differently is a bug in step 3.
- Live: fall into Slime Pit's lower story and climb back out.

## Completion

- Append the architectural summary to `plans/COMPLETED-MILESTONES.md`.
- Mark M61 done in `docs/reference/SITUATIONS_PLAN.md` section 6, in the same
  style as M59 and M60.
- Rename this file `M61-handoff-completed.md`.
- If the return path check catches an authoring mistake, add it to
  `docs/DISCOVERIES.md` as a numbered trap.
