# Vertical floors, Phase 1: the story index (build plan, 2026-10-17)

For the agent building this. Follow the `agent-brief` skill's standing rules and read the `add-tests`
skill first. The decision this serves is `docs/DECISIONS.md`, section "2026-10-17: Vertical floors
(stair rooms and drops)". Read it before starting.

## Goal

Give every plan cell a **story** index so later phases can stack cells, **without changing anything a
player or a test can see today**. Every existing layout has story 0. When this phase is done, every seed
generates, stamps and resolves byte-for-byte as before, and the geometry code is ready for cells at
story -1 and -2.

Not in this phase: stair rooms, per-story door masks, generator changes, drops, map text. Do not start
them.

## Branch and commits

- Branch `feature/vertical-floors` off `master`. Do not push or deploy. Author `MrPinoy123456789`.
- Commit 1 is the fingerprint test on **unchanged** code. Commits 2 onward make the change. The
  fingerprint must pass unchanged after every commit.

## Step 1: pin today's behaviour first (commit 1, before touching `PlanCell`)

Write a pure test `src/test/java/pocketdungeons/LayoutFingerprintTest.java` and wire it into
`build.gradle.kts` in both places (task map and `dependsOn`, see `add-tests`).

It builds a fingerprint string over a seed sweep and compares it with a committed fixture,
`src/test/resources/layout_fingerprint.txt`. For seeds 0 to 299, on the synthetic manifest that
`GraphSolvabilityTest` already builds (copy its fixture helpers; do not depend on the live datapack):

1. `LayoutGraphGenerator.generate(seed, 6, 10)`: every cell, every open edge, entrance, terminal and
   roles, in the shape's own iteration order (iteration order is what must not change).
2. `RoomSelector.resolveDetailed(...)` on that shape with `BagTags.pilgrim()`: the placed room and
   rotation per cell, in iteration order, plus the fallback cells.
3. `PlanGeometry.of(new BlockPos(0, 64, 0), cells)`: `cells()` order, every `cellOrigin`, `bounds()`,
   `chunks()`, and `cellAt` for each cell's centre, for one block under the floor (y 63, the case a
   two-story room's lower story relies on) and one block over the ceiling.
4. `PlanRenderer` output for the first 10 seeds.

Use one line per item so a diff points at the change. Add a `-Dfingerprint.write=true` switch that
rewrites the fixture, and generate the fixture **once, on unchanged code**. Commit the test and fixture
alone. Never regenerate the fixture after Step 2 starts; if it stops matching, the change is wrong.

## Step 2: `PlanCell` gains `y` (commit 2)

`src/main/java/pocketdungeons/PlanCell.java`:

- `record PlanCell(int x, int y, int z)`, where `y` is the story: 0 is the entrance story, -1 one story
  down. Keep a second constructor `PlanCell(int x, int z)` that means story 0. About 180 test call sites
  and most main sites use it and stay unchanged.
- **Keep the hash identical for story 0.** HashSets and HashMaps of cells drive generation order, so a
  changed hash reshuffles every seed. The current record hash for `(x, z)` is the JDK record formula
  `31 * x + z`. Override `hashCode()` so that story 0 gives exactly that, for example
  `31 * x + z + 1_000_003 * y`, and override `equals` to compare all three (records do, but write it next
  to the hash so they are read together). **Verify the old formula on unchanged code first**: add a check
  in `LayoutFingerprintTest` in Step 1 that prints `new PlanCell(3, -7).hashCode()` and record the value
  in the fixture. If it is not `31 * 3 + (-7)`, use whatever it is.
- `toString()` must stay `PlanCell[x=3, z=-7]` for story 0 (logs, `PlanRenderer`, any test message), and
  read `PlanCell[x=3, y=-1, z=-7]` otherwise.
- `neighbor(direction)` keeps the story. `directionTo(other)` returns null when the stories differ.
- Add `static final Comparator<PlanCell> ORDER`: x, then z, then y. For story 0 that is today's order.
- Add `PlanCell atStory(int story)` and `boolean onEntranceStory()` only if a later step needs them; do
  not add helpers nobody calls.

`src/main/java/pocketdungeons/PlanEdge.java`: `compare` becomes x, then z, then y (unchanged for story
0). The self-edge check stays. Nothing else; edges between stories come in Phase 3.

## Step 3: carry the story everywhere a cell is rebuilt (commit 3)

Every place that builds a `PlanCell` from another cell's x and z silently drops the story. Fix each to
keep it:

| File | Site | Change |
|---|---|---|
| `CellGeometry.java` | lines ~216 to 219, the four neighbour cases | pass `cell.y()` |
| `DungeonShape.java` | `rotateCell` (~line 133) | keep `cell.y()`; rotation is about the vertical axis only |
| `PlanRenderer.java` | ~lines 53, 63, 67 | see Step 5 |
| `LayoutGraphGenerator.java` | ~380, 393, 408 | leave at story 0 (the generator is flat until Phase 2), but add a one-line comment saying so |
| `Instances.java` ~938, `StaticLayout.java` ~55 | | leave; single flat layouts |

Then replace the nine hand-written comparators with `PlanCell.ORDER`:
`ClaimObjective` (~182, ~357), `DungeonLight` (~78), `FloorRooms` (~54), `GatherObjective` (~144),
`HauntObjective` (~121), `HeistObjective` (~184), `PlanGeometry` (~62), `RoomSelector` (~1277; read the
whole comparator there, it may sort by depth first: keep that and only swap the x, z tail).

Search once more for anything else that takes `.x()` and `.z()` off a cell and builds a key, a string or
a position from them without the story: `grep -rn "\.x()" src/main/java | grep -i cell`. Fix or note each.

## Step 4: story-aware `PlanGeometry` (commit 4)

`src/main/java/pocketdungeons/PlanGeometry.java`:

1. `cellOrigin(cell)`: add `cell.y() * RoomGeometry.STORY_HEIGHT` to the y offset (y is 0 or negative,
   so a story -1 cell sits one pitch below). A story -1 cell's origin then coincides with the lower story
   of a `spanY: 2` room in the same column, which is the cell contract M61 already uses.
2. `bounds()`: the bottom must cover the deepest story as well as the deepest `spanY` room. Compute
   `minStory` in `of(...)` and use `max(storyFloorOffset, -minStory * STORY_HEIGHT + that cell's own span
   offset)`. Keep the field and the factory signatures; callers pass `storyFloorOffset` as today. For an
   all-story-0 layout the result is unchanged.
3. `chunks()`: cells that share a column share a chunk. Return each chunk once, in first-seen order. For a
   flat layout that is the same list as today. Update the class javadoc ("one chunk per occupied cell")
   to say one chunk per occupied **column**.
4. `cellAt(pos)`: today it ignores the block's y, and callers rely on that: a spawner in a two-story
   room's lower story must resolve to the room's cell (`Pocket2`, `PlayerContext`, `Instances`). Keep
   that for a column with one cell. For a column with several cells, pick the cell whose story contains
   the block's y (`floorDiv(pos.y - origin.y, STORY_HEIGHT)`, clamped), and if that story has no cell,
   the nearest cell **above** it (a `spanY` room owns the volume below it). Build a column index
   (`Map<Long, List<PlanCell>>` keyed by x and z) once in `of(...)` rather than scanning per call; keep
   the record's components as they are and hold the index in a private static cache or a derived field
   computed lazily, whichever compiles cleanly with a record. If neither is clean, convert the index into
   a component and update the few constructors.
5. `cellCentre` follows `cellOrigin`; nothing to do.

## Step 5: `PlanRenderer` (commit 4, same commit)

Group cells by story. A layout with only story 0 renders exactly as today (the fingerprint checks it).
With more stories, render each story under a header line `story 0`, `story -1`, and so on, top first.

## Step 6: tests for the new behaviour (commit 5)

Add a pure test `src/test/java/pocketdungeons/StoryGeometryTest.java` (wire it in `build.gradle.kts`):

- `PlanCell`: story 0 hash equals the pinned old hash; `new PlanCell(1, 2)` equals `new PlanCell(1, 0, 2)`;
  cells differing only in story are unequal; `neighbor` keeps the story; `directionTo` across stories is
  null; `ORDER` sorts story 0 exactly as x then z.
- `PlanEdge`: an edge between (0,-1,0) and (1,-1,0) is canonical in both orders.
- `PlanGeometry` with a hand-built layout: cells (0,0,0), (1,0,0) and (1,-1,0):
  - `cellOrigin((1,-1,0))` is `cellOrigin((1,0,0))` minus `STORY_HEIGHT` in y;
  - `bounds()` reaches one pitch below the floor;
  - `chunks()` has 2 entries, not 3;
  - `cellAt` at story 0 height in column x=1 returns (1,0,0), at story -1 height returns (1,-1,0);
  - with a layout of only (0,0,0), `cellAt` one block under the floor still returns (0,0,0).
- `DungeonShape` rotation keeps the story of every cell.

## Step 7: bedrock envelope check (no code unless it fails)

`BedrockEnvelope.applyToCell` places the sub-floor at `-1 - storyOffset` and rings per face. In a stacked
column the upper cell's sub-floor and the lower cell's over-ceiling must be the same block layer
(`STORY_HEIGHT = CEILING_Y + 2` is designed for that). Add one gametest method (scaffold with
`python tools/new_gametest.py StoryEnvelope`): build a `PlanGeometry` with (0,0,0) and (0,-1,0), apply the
envelope, and assert the shared layer is bedrock and that no air gap or double layer sits between the two
interiors. If the envelope's neighbour logic (`occupied`, `standingCells`) treats the stacked cell as
missing and rings it wrongly, report it; do not redesign the envelope in this phase.

## Done when

- The fingerprint fixture from Step 1 matches unchanged.
- `StoryGeometryTest` and the envelope gametest pass.
- Full run green, from Git Bash:
  `./gradlew.bat test runGameTest dungeonIntegrationTest --console=plain > /a/tmp/run.txt 2>&1; echo $?`
  Exit 0, `All N required tests passed` with N up by your gametest methods, `dungeonIntegrationTest` PASS.
- Dash check from `CLAUDE.md` over everything you wrote.
- `python tools/gen_codemap.py` rerun if any class comment changed.

## Report

Per step: what changed, the test that covers it, every place you found that dropped the story and how you
fixed it, and anything in Step 7 that did not hold. Say plainly that nothing in this phase is visible in
game, so there is no live check yet.
