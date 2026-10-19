# rm-t05: vertical floors Phase 1 (story index)

Branch `feature/rm-t05-vertical-phase-1`. Nothing in this phase is visible in game, so there is no live check.

## Steps

1. Fingerprint (`LayoutFingerprintTest`, fixture `layout_fingerprint.txt`, 4618 lines): seeds 0 to 299, shape, resolved
   rooms, doors, depths, fallback cells, `PlanGeometry` origins, bounds, chunks and `cellAt` one block under the floor and
   over the ceiling, and `PlanRenderer` for seeds 0 to 9. The old story 0 hash of `PlanCell(3,-7)` is 86 (`31*x+z`).
   The first fixture did not reproduce across JVMs: `plan.rooms()`, `doors()`, `depths()` and the fallback set come from
   `Map.copyOf` and `Set.copyOf`, whose iteration order is salted per JVM. Those four facts are now printed sorted; the
   fixture was regenerated once, still on unchanged main code, and passes on fresh JVMs. Shape facts keep iteration order.
2. `PlanCell(x, y, z)` with a two argument constructor (story 0), hash `31x+z+1000003y`, `toString` unchanged for story 0,
   `ORDER` (x, z, story). `neighbor` keeps the story; `directionTo` is null across stories. `PlanEdge` compares with `ORDER`.
3. Story carried: `CellGeometry.neighbourCell`, `DungeonShape.rotateCell`. Comparators replaced by `PlanCell.ORDER`:
   ClaimObjective (2), DungeonLight, FloorRooms, GatherObjective, HauntObjective, HeistObjective, PlanGeometry, RoomSelector
   `bfsOrder`. `LayoutGraphGenerator` left flat, with a comment.
4. `PlanGeometry` gains components `minStory` and a column index. `cellOrigin` adds `y * STORY_HEIGHT`; `bounds` reaches
   `storyFloorOffset + deepest story pitch` (a safe over-cover); `chunks` is one per column; `cellAt` ignores height for a
   one cell column (the two-story room case) and otherwise picks the cell on the block's story, else the nearest above.
5. `PlanRenderer` renders stories under `story N` headers, top first; a story 0 only layout is byte identical.
6. `StoryGeometryTest` (pure).
7. `StoryEnvelopeGameTest`: two stacked cells; the upper sub-floor and the lower over-ceiling are the same single bedrock
   layer, no air gap or double layer, wall ring unbroken. It held; the envelope needed no change (`neighbourCell` keeps the
   story, so a stacked cell is not treated as missing).

## Places that still drop or ignore the story (left alone, flat until Phase 2)

- `FloorRooms.Room.cellKey()` writes `"x,z"` into the journal (a saved shape; story 0 must keep it). Phase 2 needs a story
  aware key with the old shape still read.
- Same x-then-z comparators left as is: `RelicObjective:138`, `LayoutStamper:747/787`, `RoomSelector` `adjacency` and
  `:1389`, `LayoutGraphGenerator:681/754`. Identical for story 0.
- `Instances` (~938, 1347) and `StaticLayout` build flat cells.
- `PlanGeometry.bounds` over-covers when the deepest story and the deepest multi-story room are in different cells.

## Data

No on disk shape changed.
