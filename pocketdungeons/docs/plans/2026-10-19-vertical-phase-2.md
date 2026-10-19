# Vertical floors, Phase 2: stair rooms (build plan, 2026-10-19)

For the agent building this. Follow the `agent-brief` skill's standing rules and read the `add-tests` and
`room-building` skills first. The decision this serves is `docs/DECISIONS.md`, section "2026-10-17: Vertical floors
(stair rooms and drops)", rules 1 to 3 and 7 and the Phase 2 row. Phase 1 (`docs/plans/2026-10-17-vertical-phase-1.md`,
report `docs/reports/rm-t05.md`) gave every `PlanCell` a story and made `PlanGeometry` story aware. Read both.

## Goal

A dungeon that opts in can put one stair room on its spine and continue the walk one story down, at most to story -2.
A dungeon that sets nothing is flat and generates, resolves and stamps exactly as before. Mineshaft opts in on its
deeper nodes. Nothing else changes for a player.

Not in this phase: drops, powder snow landings, overlapping stories, grates, the map board, the "1 down" bar line
(Phases 3 and 4). In Phase 2 two cells never share a plan column, so a column still holds one cell and the existing
`cellAt`, chunk and journal assumptions keep holding.

## The model

- A **stair room** is a `spanY: 2` room whose cell sits on story `s`. Its top story holds the way in; its lower story
  (the volume at story `s-1`) holds the way on. Its manifest mask has **8 bits**: bits 0 to 3 are the doors of its top
  story, bits 4 to 7 the doors of its lower story. `DoorMask` rotates each nibble. Every existing mask is below 0x10 and
  behaves identically.
- A cell **after** a stair room sits on story `s-1`, in the neighbouring column. The edge between them is an ordinary
  `PlanEdge` between cells of different stories that are adjacent in plan. `PlanCell.directionTo` stays null across
  stories (a Phase 1 contract); a new `PlanCell.wallTo` gives the plan direction of any adjacent-column pair, and edge
  code uses it. For a cell `c` and a neighbour `o` joined by an edge, the **door story slot** on `c` is
  `max(0, c.y - o.y)`: the stair room reaches its successor through slot 1, everything else uses slot 0.
- A stair cell has exactly two edges (the one in, the one on) and is never a branch root or a loop end, so the stair
  room needs no spare door anywhere.
- The generator builds the flat critical path exactly as today (same random draws), then picks the stair indices with a
  **separate** `Random(seed ^ constant)`, shifts the cells after a stair down one story, and runs branches, loops and
  roles with a column-uniqueness rule (no new cell in a column already holding a cell on another story). With
  `stories == 1` none of this code runs, so the main random stream and every flat layout are untouched.
- Stair cells carry the `corridor` role and are locked against the role guarantee and key budget passes.
- Solvability: a stair room `requires` nothing and `provides` nothing, so the spine stays provable with the Pilgrim seed
  exactly as today. The root-distance BFS already follows edges, so a stair cell is simply a cell on the walk; the proof
  test asserts it over a seed sweep.

## Steps and commits

1. **This plan** (first commit, before code).
2. **`DoorMask` per-story**: `edge(direction, slot)`, `story(mask, slot)`, nibble-wise `rotateClockwise`, `toLetters`
   showing the lower story after a slash. Pure test `StoryDoorMaskTest`; flat masks unchanged.
3. **`PlanCell.wallTo` and the story-aware journal key**. `FloorRooms.Room.cellKey()` keeps `x,z` for story 0 and writes
   `x,z,y` otherwise; the reader accepts both shapes. Golden fixture test `FloorRoomsKeyTest` with a recorded old-shape
   journal line. The other x-then-z comparators the Phase 1 report lists get `PlanCell.ORDER` only where they can see more
   than one story; story 0 order is identical.
4. **Generator and selector**. `LayoutGraphGenerator.generate(..., stories)` overload (the existing overloads pass 1);
   `RoomSelector.requiredMask` builds a per-story mask, `orientGatedRooms` uses `wallTo`, stair rooms are not "multi
   story" (their lower story is the way on, not a sealed bonus) and never anomaly cells. Guard: `LayoutFingerprintTest`
   keeps matching, untouched. New `StairLayoutFingerprintTest` and fixture `layout_fingerprint_stairs.txt` pin the
   opted-in layouts (stories 2 and 3) separately. `StairLayoutTest`: seed sweep, every shape valid and every plan
   solvable, columns unique, stairs only on the spine, never below story -2, no stair room on a flat dungeon.
5. **The room**. `RoomManifest.buildEntry` reads door jigsaws per story (lower story slots sit one `STORY_HEIGHT` lower in
   the template). `RoomSpec.lowerDoors`. New `StairSpecs` with four generic rooms (straight, gallery straight, turn
   north, turn south), real stairs two wide, no ladder, door lanes kept, placeholders only so the theme processors
   re-skin them. Manifests `stair_*.json`, generated `.nbt`. `RoomLibraryGameTest` lists `StairSpecs`.
6. **The stamp**. `LayoutStamper` connectors, iron door, rubble and window band take the lower story origin for a door
   on a stair room's lower story. `BedrockEnvelope` does not ring a face whose neighbour is the stair room's lower story
   (and the reverse). `InstanceRegistry.maximalBounds` reaches the deepest story. `StairEnvelopeGameTest` stamps a
   two-story Mineshaft plan and checks the bedrock envelope and the pad; `StairRoomGameTest` stamps a stair room and walks
   a mob path down it (pathfinding on the stairs, no ladder).
7. **Opt-in**. `DungeonDef.Node.stories` (default 1, 1 to 3, validated, absent key reads as 1) parsed from the node;
   `LayoutPlanner.PathProfile.stories` (default 1) carries it into `generate`; `Instances.planFloor` passes the node's
   value. `dungeon/mineshaft.json` sets `"stories": 2` on `main_drift`, `side_gallery` and `deep_face`.
8. **Live check fragment** (`tools/livecheck.py new`), the report `docs/reports/rm-t06.md`, and, only if
   `StampDeterminismGameTest`'s fingerprint moved solely because of the new rooms, one final re-pin commit.

## Guards

- `LayoutFingerprintTest` and its fixture are never edited. They must pass after every commit.
- Player data: the only saved shape touched is the `FloorRooms` journal cell key; old shape is still read, golden
  fixture covers it. No `PlanCell` is stored anywhere else.
- Unaffected by design: lobby, staging room and the exit pad hang off the entrance and terminal origins, which already
  come from `PlanGeometry.cellOrigin` (story aware since Phase 1).

## Done when

Pure suite green, the new gametests green and counted, `--quick --no-dist` green once at the end, dash check clean,
report written with the deviations and what only a live server can confirm.
