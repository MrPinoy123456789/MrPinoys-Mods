# rm-t06: vertical floors Phase 2 (stair rooms, Mineshaft opts in)

Branch `feature/rm-t06-vertical-phase-2`, off `feature/backlog-2026-10-19`. Plan: `docs/plans/2026-10-19-vertical-phase-2.md`.
Live check fragment: `docs/playtests/live-checks/rm-t06.md`.

## Result

`tools/ship.sh --quick --no-dist`: SUITE GREEN, All 411 required tests passed (5 new gametest methods). Pure suite: 135
tasks green (4 new: `StoryDoorMaskTest`, `FloorRoomCellKeyTest`, `StairLayoutTest`, `StairLayoutFingerprintTest`).
`LayoutFingerprintTest` and its fixture were never touched and matched after every commit. The stamp fingerprint in
`StampDeterminismGameTest` did not move (flat seed 7 cannot draw a stair room, whose mask has lower story bits), so no
re-pin commit was needed.

## What was built

1. **Per-story door masks** (`DoorMask`): bits 0 to 3 top story, bits 4 to 7 the story below; rotation per nibble;
   `toLetters` shows `W/E`. Every old mask is below 0x10 and reads identically (pure test compares with the old formula).
2. **`PlanCell.wallTo`** (plan direction of adjacent columns across one story) and a **story aware journal key**
   (`FloorRooms.cellKey`: `x,z` for story 0, `x,z,y` below; `parseCellKey` reads both). Golden fixture
   `floor_room_cell_keys.txt`. No reader of the key exists today, so the old shape is guaranteed by the writer and
   the parser.
3. **Generator** (`LayoutGraphGenerator.generate(..., stories)`): flat path drawn exactly as before, then stair index
   or indices from a separate `Random`, cells after a stair shift one story down, one cell per plan column (checked in
   branch and loop growth), stair cell has two doors and the corridor role, locked against the role guarantee and key
   budget passes. `stories` 1 is the old generator (test compares 300 seeds). A spine too short returns null (planner
   retries). Cap `MAX_STORIES` 3 (story -2).
4. **Selector**: per-story `requiredMask`, stair rooms are not sealed bonus stories, never anomaly cells, gated rooms
   face a stair approach via `wallTo`. Solvability: a stair room requires and provides nothing; `StairLayoutTest`
   recomputes the Pilgrim proof on 600 seeds each at 2 and 3 stories.
5. **Rooms**: `StairSpecs` (stair_straight, stair_gallery, stair_turn_north, stair_turn_south), manifests, generated
   `.nbt`. `RoomManifest` reads door jigsaws per story; `RoomSpec.lowerDoors`. Stairs are two wide, eight steps, stone
   brick stairs, no ladder, placeholders for everything else.
6. **Stamp**: `PlanGeometry.wallOrigin`; connectors, iron door, rubble, window band use the lower story origin for the
   stair room's way on. `BedrockEnvelope` skips a face whose neighbour is the stair room's lower story (and the reverse).
   `InstanceRegistry.maximalBounds` reaches story -2. `ReturnPathValidator` accepts stair blocks as steps.
7. **Opt in**: `DungeonDef.Node.stories` (default 1, 1 to 3), `PathProfile.stories`, `Instances.planFloor` passes it
   (the Proving arena stays flat). `mineshaft.json`: `main_drift`, `side_gallery`, `deep_face` set `"stories": 2`.

## Tests

- `StairLayoutTest`, `StairLayoutFingerprintTest` (+ `layout_fingerprint_stairs.txt`, separate from the flat one).
- `StairEnvelopeGameTest`: stamps a real two-story plan from the live manifest; sub-floors and over-ceilings bedrock,
  the stair doorway is not bedrock on either side and shares a floor, outer faces of the lower story are ringed, the exit
  pad stands on solid floor inside the bounds, the bounds reach the lowest sub-floor.
- `StairRoomGameTest` (4 methods): masks and Mineshaft node values; each room stamps with exactly its two doors open,
  no ladder, 16 stairs, return path check; a husk has a path from the top landing to the lower door in all four rooms;
  a husk walks the straight stair down and out the far end.

## Findings worth knowing

- A walking mob steps sideways at its height before dropping, so a stair tunnel needs **three** clear rows over each
  next step, not two. With two, the stairs under the filler layer were unwalkable (husk probe). Fixed in `StairSpecs`
  (the well in the top floor is three long, x=5..7).
- Near the bottom a mob may step off the side of the run (a 3 block drop) to reach a turn door; the path test allows it.
- Gametests that run side by side must not share an area; the stair tests take 64 block lanes. A hand ticked mob is
  used in the walk test because a chunk away from the test area may not entity tick.

## Deviations and gaps

- Stairs are plain stone brick stairs: the theme processors only remap four full-cube placeholders, so the steps do
  not re-skin. Walls, floors, accents and lamps do.
- Layout-less teardown (`maximalCellOrigins`) still clears only story 0 cell volumes plus 8 blocks below; a story -2
  leftover after a crash with no layout would not be cleared. Only the entity sweep bounds were deepened. With a layout
  (every normal run) teardown reads the real cell origins and is complete.
- `LayoutGraphGenerator.validate` does not reject two cells in one column (Phase 3 will allow it); the sweep test does.
- `PackValidator` does not yet warn when a node sets `stories` above 1 and no stair room is loaded.
- Other x-then-z comparators listed in the Phase 1 report were left (identical while columns are unique).

## Live only

One Mineshaft trip that goes down (fragment `rm-t06.md`): stair feel and light, mobs following down the stairs under
real AI, the doorway alignment and window band between the stair room and the next room, a finale on a lower story
terminal, teardown of lower stories after a live run.
