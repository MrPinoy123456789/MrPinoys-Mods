# M35 - Anomaly rooms - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `RoomSelector.java` lines 61-105: `resolveDetailed`. This is the hook
   point. After the plan resolves normally, a rare roll swaps one
   critical-path cell's room for an anomaly room satisfying the same mask
   and role.
2. `RoomManifest.java` lines 30-100: manifest loader. Loads
   `data/<namespace>/dungeon_room/*.json`. The anomaly set is a second
   manifest loading from `data/<namespace>/anomaly_room/*.json`, same
   loader shape, separate index.
3. `LayoutStamper.java` lines 100-110: the Pocket2 door roll. The
   anomaly roll mirrors this pattern: seeded from the plan seed, gated
   on `AdventureGraphs.current().graph().node(theme) != null`, chance
   from `PocketDungeonsConfig`.
4. `LORE-DIARIES.md` Entry 2 (The Wrong Rooms): the lore beat this
   feature delivers. The room is wrong the way Alex describes wrong:
   blocks real, placement not, palette that does not match the theme
   around it.

## Dependencies

Grep `AdventureGraphs` in `src/main/java/`. If class exists, the
rare-node gating used by the Pocket2 door is reusable here.

Grep `queryAnyRotation` in `RoomManifest.java`. If method exists, the
anomaly manifest can be queried the same way the themed one is.

## Goal

Rarely, a run contains one room that does not belong to its theme. A
dedicated anomaly room set, loaded separately from the themed room
manifest, supplies rooms whose palette and content are deliberately
foreign. The player walks through a door and the room is wrong: the
blocks are real, the placement is not, the walls are a material that
does not match anything around it. A "wrong room" beat, tied to Entry
2's tone, without any in-game text naming it.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Anomaly room manifest

1. New resource path: `data/pocketdungeons/anomaly_room/*.json`. Same
   JSON shape as `dungeon_room/*.json` (template, door jigsaw, role,
   meta). Loaded by a second `RoomManifest` instance, same loader
   logic, separate index.
2. `RoomManifest`: add a `currentAnomaly()` accessor alongside
   `current()`. The reload listener loads both from their respective
   paths. Reuses the existing validation (door jigsaw layout, template
   existence) verbatim.
3. Ship 2-3 anomaly room templates. Each one should read as wrong
   inside any themed run: foreign palette (e.g. spruce planks in a
   deepslate run, sandstone in a grove run), slightly off geometry
   (a torch on the wrong wall, a doorway that leads to a sealed face).
   No custom blocks, no custom items: vanilla blocks placed wrong.

### Step 2: Anomaly injection in RoomSelector

1. `RoomSelector.resolveDetailed`: after the plan resolves normally,
   roll the anomaly chance from the plan seed. Gated on theme having
   an adventure-graph node (same gate as the Pocket2 door: an unthemed
   run or a theme with no node never hosts an anomaly).
2. If the roll succeeds, pick one eligible cell to swap: non-entrance,
   non-terminal, on the critical path (so the player is guaranteed to
   walk through it, not find it in an optional branch they might skip).
   One anomaly per run, never more.
3. Query the anomaly manifest for a room satisfying the swapped cell's
   mask and role. If none matches, silently skip: the run stays
   normal rather than failing. The anomaly is a bonus, never a
   requirement.
4. Replace the cell's `PlacedRoom` with the anomaly pick. The rest of
   the plan is untouched: geometry, depths, roles, critical path all
   stay as resolved. Only the room template at that one cell changes.
5. Carry the anomaly cell on `DungeonPlan` (new optional field
   `anomalyCell`) so `LayoutStamper` and `RoomContent` can read it:
   the stamper applies the run theme's processors to every cell
   except this one, where it either applies no processors or a
   dedicated anomaly processor (none, if the template is
   self-sufficient). `RoomContent` skips theme-tied content (loot
   suffix, themed spawners) on the anomaly cell.

### Step 3: Config and chance

1. `PocketDungeonsConfig`: add `anomalyRoomChance` (double, default
   0.08). Same pattern as `pocket2DoorChance`. Read in
   `RoomSelector.resolveDetailed`'s roll.
2. Config validation: `0.0 <= chance <= 1.0`, same clamp as
   `pocket2DoorChance`.

### Step 4: Content and loot

1. Anomaly rooms carry their own loose chests and 0-1 spawners, placed
   by `RoomContent` with no theme suffix. Loot table:
   `pocketdungeons:chests/anomaly/`. Separate from the themed chest
   tables, so an anomaly room never drops themed gear.
2. Anomaly loot: echo shards, shell unlock tokens (M24), occasional
   rare materials. Not better than a completion chest, not worse than
   a loose chest. The reward for walking through the wrong room is
   finding it, plus a small something.
3. No keystone, no completion pad, no lodestone. The anomaly room is
   a pass-through cell on the critical path, not a destination.

## Constraints

- One anomaly per run, never more. The roll is per-run, not per-cell.
- Anomaly cell is on the critical path, never the entrance or terminal.
  The player walks through it; they do not have to find it.
- Anomaly rooms must satisfy the same door mask and role constraints as
  themed rooms. They are physically in the same layout; a room that
  cannot connect to its neighbours cannot be placed.
- If no anomaly room matches the cell's mask and role, the run stays
  normal. The anomaly is never a failure condition.
- No lore text in the room itself. The "wrongness" is visual and
  spatial: palette mismatch, off geometry. The link to Entry 2 is
  tonal, not textual.
- No new dimension, no new instance, no new slot. The anomaly room is
  a cell in the parent run's layout, stamped and torn down with it.
- Gated on adventure-graph node, same as Pocket2: unthemed runs and
  themes with no node never host an anomaly.

## Verification

- `build_mod` default `build` after each step.
- Headless: anomaly manifest loads and validates. `resolveDetailed`
  with a seeded roll produces a plan whose anomaly cell's room comes
  from the anomaly manifest. A roll that fails the gate produces a
  normal plan. A roll that passes but finds no matching anomaly room
  produces a normal plan.
- Live: walk a run, occasionally find a room whose palette does not
  match the theme. Walk through it, continue to the terminal. The
  rest of the run is unaffected. Record in `LIVE_TEST_PASS.md`.
- Done when: anomaly rooms appear rarely in themed runs, are on the
  critical path, connect to their neighbours, carry their own loot,
  and the run completes normally with one in it.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M35-handoff-completed.md` once landed.
