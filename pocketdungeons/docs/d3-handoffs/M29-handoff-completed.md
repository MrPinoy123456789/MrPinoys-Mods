# M29 - No-backwards propagation - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `LayoutGraphGenerator.java` lines 129-224: `validate` method. New
   no-backwards check goes here, alongside existing structural checks.
2. `LayoutGraphGenerator.java` lines 226-260: `generateCriticalPath` and
   `extendPath`. Understand how entrance direction is determined by first
   step from (0,0).
3. `DungeonShape.java` lines 95-109: `entranceDirection()` method. Used to
   compute which axis "behind" falls on.

## Dependencies

Grep `entranceDirection` in `DungeonShape.java`. If method exists, entrance
direction is computable for the check.

Grep `validate` in `LayoutGraphGenerator.java`. If static method exists at
line 129, check goes there.

## Goal

Dungeons never wrap behind the player room. If the dungeon door opens SOUTH
(positive Z), no cell may exist at z < 0 relative to the entrance. Prevents
dungeons from surrounding the player room on the door's reverse axis.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Add no-backwards check to `validate`

1. In `LayoutGraphGenerator.validate`, after existing checks, add:
   - Compute `entranceDir = shape.entranceDirection()`.
   - If null, skip (existing check already flags this).
   - For each cell in `cells`, test `isBehind(cell, entranceDir)`.
   - If any cell is behind, add problem: `"cell " + cell + " is behind the
     entrance on the " + entranceDir + " axis"`.
2. `isBehind(PlanCell, DoorMask.Direction)`:
   - EAST: `cell.x() < 0`
   - WEST: `cell.x() > 0`
   - NORTH: `cell.z() > 0`
   - SOUTH: `cell.z() < 0`
3. Property preserved under rotation: `DungeonShape.rotate` is rigid, so
   pre-rotation check guarantees post-rotation constraint.

### Step 2: Update verification sweep

1. In `LayoutGraphGenerator.verifyLivePlayProfile` (line 688): add
   no-backwards assertion to the 5000-seed sweep. If any shape has a cell
   behind the entrance, throw `AssertionError`.
2. Run `LayoutGraphGenerator.main` to verify: some seeds that previously
   generated valid shapes will now fail validation. The retry budget in
   `LayoutPlanner.plan` (16 attempts) handles this. Verify the failure rate
   is low enough that plans still resolve within budget.

### Step 3: Config toggle (optional)

1. `PocketDungeonsConfig`: add `noBackwardsPropagation` (boolean, default
   true). If false, skip the check in `validate`.
2. Pass through `LayoutPlanner.plan` to `validate`. Currently `validate` is
   static and takes only `DungeonShape`; add an overload or parameter.

## Constraints

- Check applies to ALL cells (critical path, branches, loops), not just
  the path.
- `validate` runs pre-rotation. The property is rotation-invariant: if no
  cell is behind pre-rotation, no cell is behind post-rotation.
- Retry budget (16 attempts) must still produce valid plans. If failure
  rate is too high, increase `DEFAULT_ATTEMPT_BUDGET` in `LayoutPlanner`.
- Do not constrain the generator itself (modifying `extendPath` to prevent
  backward steps). Post-generation validation is simpler and lets the
  backtracker explore freely.

## Verification

- `build_mod` default `build` after each step.
- Headless: run `LayoutGraphGenerator.main`. 5000-seed sweep passes
  no-backwards assertion. Verify plan resolution rate stays above 95%.
- Live: enter dungeon from each door direction. Confirm no rooms appear
  behind the player room. Record in `LIVE_TEST_PASS.md`.
- Done when: no valid shape has a cell behind the entrance, and plan
  resolution rate stays above 95%.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M29-handoff-completed.md` once landed.
