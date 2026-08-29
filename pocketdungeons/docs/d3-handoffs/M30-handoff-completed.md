# M30 - Connector variations - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `TemplateStamper.java` lines 135-160: `place` method. Stamps the
   `.nbt` template with `JigsawReplacementProcessor.INSTANCE`, which
   resolves each `pocketdungeons:door` jigsaw to its `final_state`
   (`minecraft:air`). This is how door openings are created: the
   template bakes door jigsaws at the canonical slot, the processor
   resolves them to air at stamp time. There is no runtime carve in
   `LayoutStamper`.
2. `RoomTemplateGenerator.java` lines 904-931: `buildCell`. Authoring
   side: stamps a blank shell via `RoomBuilder.buildShell`, then places
   `pocketdungeons:door` jigsaw blocks at the canonical door slots
   (`DOOR_MIN=7`, `DOOR_MAX=8`, `DOOR_HEIGHT=3`) on each wall that
   should have a door. Each jigsaw's `final_state` is `minecraft:air`
   (line 979). The template is captured as an `.nbt` file at dev time.
3. `RoomManifest.java` lines 262-308: `buildEntry`. Load-time
   validation: every door jigsaw must sit on the canonical slot for its
   edge, and every canonical slot on a door-bearing edge must have a
   jigsaw (no partial doors). The door mask is derived from which edges
   have door jigsaws.
4. `CellGeometry.java` lines 109-122: `doorSlotPositions`. Computes the
   world positions of the canonical 2-wide, 3-tall door slot on a given
   wall. This is the position math a connector pass needs: it tells you
   exactly which blocks the jigsaw resolved to air.
5. `DungeonPlan.java`: `doors()` returns `Set<PlanEdge>`, each edge
   being an undirected connection between two adjacent cells. This is
   the set of door connections the connector pass iterates.
6. `LayoutStamper.java` lines 118-173: the per-cell stamp loop. After
   `TemplateStamper.place` stamps each cell, the door slots are air. A
   connector pass runs as a separate loop after this one, before
   `BedrockEnvelope.apply`.

## Dependencies

Grep `PlanEdge` in `src/main/java/`. If the record exists, `DungeonPlan
.doors()` gives the connector pass its edge set.

Grep `doorSlotPositions` in `CellGeometry.java`. If the method exists,
the canonical door slot positions are already computed for any wall
direction.

Grep `standingNeighbours` in `CellGeometry.java`. If the method exists,
a cell's open edges (walls with neighbours) can be determined from
geometry alone, as a fallback to iterating `plan.doors()`.

## Goal

Varied connector patterns at door openings: wide door (current default),
double door, single door, iron door, bars, open wall with pillars, arch
with lintel. Seeded per-edge. Visual variety without changing cell size,
room template format, or the canonical door slot position. Iron door
requires redstone signal to open: a gated passage puzzle.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: ConnectorType enum and per-edge dispatch

1. New `ConnectorType` enum (top-level or nested in `LayoutStamper`):
   `DOOR_WIDE`, `DOOR_DOUBLE`, `DOOR_SINGLE`, `IRON_DOOR`, `BARS`,
   `OPEN`, `ARCH`.
2. `LayoutStamper.stamp`: after the per-cell stamp loop and before
   `BedrockEnvelope.apply`, add a connector pass. Iterate
   `plan.doors()` (the `Set<PlanEdge>`). For each edge, compute the
   connector type via a seeded weighted random (Step 2), then apply it
   to both cells' sides of the wall.
3. Per-edge, per-side application: for each cell the edge touches,
   compute the wall direction (which face of this cell the edge is on),
   then call `applyConnector(level, cellOrigin, wall, type)` to overlay
   the connector pattern on top of whatever `TemplateStamper.place`
   already wrote.
4. The entrance edge (the edge touching `plan.entrance()`) is always
   `DOOR_WIDE`: no overlay, since the default air slot is already
   DOOR_WIDE. Skip it entirely.

### Step 2: Weighted random selection

1. Per edge: pick `ConnectorType` via weighted random. Weights:
   DOOR_WIDE 45, DOOR_SINGLE 15, DOOR_DOUBLE 10, IRON_DOOR 10,
   OPEN 10, ARCH 5, BARS 5.
2. Seeded from plan seed and edge identity:
   `new Random(plan.seed() ^ edge.hashCode())`. Same seed = same
   connectors, reproducible across restarts.
3. Entrance edge: always DOOR_WIDE, no roll.

### Step 3: Connector application

1. `applyConnector(level, cellOrigin, wall, type)`: overlays the
   connector pattern on the door slot and surrounding wall. The door
   slot is already air (jigsaw resolved to air). The surrounding wall
   is solid wall blocks from `RoomBuilder.buildShell`, possibly
   re-skinned by theme processors. The connector pass runs after
   processors, so it sees the final wall material.
2. `DOOR_WIDE`: no-op. The 2-wide, 3-tall slot is already air. This is
   the default and the most common.
3. `DOOR_SINGLE`: fill one column of the 2-wide slot with the wall
   block from an adjacent wall position (read at runtime to match the
   themed material). Leave the other column as air. Pick left or right
   from the edge seed.
4. `DOOR_DOUBLE`: clear 2 additional columns of wall (one each side of
   the slot) to air, making a 4-wide opening. The wall blocks outside
   the door slot are solid (from `buildShell`), so this is safe.
5. `IRON_DOOR`: place 2 iron door blocks (vanilla `Blocks.IRON_DOOR`)
   in the 2-wide slot, lower and upper halves. Closed by default.
   Requires redstone signal to open. No redstone source placed by the
   stamper: room content or the player provides it.
6. `BARS`: fill the 2-wide slot with `Blocks.IRON_BARS` from floor to
   ceiling (`y=1` to `CEILING_Y`). Extends above the 3-tall door slot.
7. `OPEN`: clear the entire wall (all 16 blocks wide, `y=1` to
   `WALL_HEIGHT`) to air. Place 2x2 pillars at the four corners of the
   removed wall, using the wall block read from an adjacent position.
8. `ARCH`: clear the wall (`y=1` to `WALL_HEIGHT - 2`) to air, leaving
   the top 2 rows (`y=WALL_HEIGHT-1` to `WALL_HEIGHT`) as lintel. The
   lintel blocks are already there from the template; this only clears
   below them.

### Step 4: BedrockEnvelope verification

1. `BedrockEnvelope.applyToCell` already skips faces with occupied
   neighbours (the faces that have doors). No change needed for any
   connector type: OPEN and ARCH remove wall blocks on faces that
   already have neighbours, and BedrockEnvelope already leaves those
   faces alone.
2. Verify: no bedrock is placed on any open edge, regardless of
   connector type. The existing occupied-neighbour check handles this.

### Step 5: Tests

1. `ConnectorType` test: each type produces the expected block
   layout at the door slot, given a known wall block.
2. Weighted random: seeded selection produces the expected type for a
   known seed and edge.
3. Integration: `adminBuild` shows varied connectors across door edges.
4. Both-sides test: both cells on an edge get the same connector type.

## Constraints

- No manifest changes. No `door_positions` field. No new room templates.
  The connector pass overlays existing templates after they are stamped.
- No offset. The door slot stays at the canonical position
  (`DOOR_MIN=7`, `DOOR_MAX=8`). The templates' doorway lane rule
  (RoomTemplateGenerator's "keep x in [7,8] clear for z in [1,3] and
  [12,14]") is authored against this position. Offsetting would carve
  through furniture placed outside the lane. If offset is wanted later,
  the lane rule must be widened first, which is a template re-authoring
  task.
- Seeded: same seed = same connectors. Per-edge, not per-cell: both
  sides of an edge get the same type.
- `DoorMask` unchanged: which faces have connections. Connector type:
  how a connection looks.
- Wall blocks outside the door slot are solid (from `RoomBuilder
  .buildShell`), so clearing them (DOOR_DOUBLE, OPEN, ARCH) is safe.
  Furniture is placed in the interior, not in the wall ring.
- IRON_DOOR: iron door blocks (vanilla). Requires redstone signal to
  open. No source placed by stamper. Depends on M31 dungeon shell
  protection: without it players mine around doors.
- BARS: iron bars (vanilla) from floor to ceiling. Extends above the
  3-tall door slot. Visually distinct from a door.
- OPEN: 2x2 pillars at four corners of the removed wall. Pillar block
  read from adjacent wall to match theme.
- ARCH: top 2 rows remain as lintel across full width. No block
  placement, only clearing below.
- Entrance edge: always DOOR_WIDE. No random.
- Room shape variety (corridors, T-shapes, subrooms) is content-only
  via `.nbt` templates. No code change needed.

## Verification

- `build_mod` default `build` after each step.
- Headless: `ConnectorType` tests pass. Weighted random test passes.
  Both-sides test passes. `adminBuild` shows varied connectors.
- Live: OPEN edge shows wall gone, pillars at corners. DOOR_SINGLE:
  narrow opening, one column filled. ARCH: lintel remains. IRON_DOOR:
  door blocks, opens with redstone. BARS: iron bars floor to ceiling.
  DOOR_DOUBLE: 4-wide opening. Record in `LIVE_TEST_PASS.md`.
- Done when: each connector type renders correctly on both sides of
  the edge, default templates unchanged, entrance edge always
  DOOR_WIDE, BedrockEnvelope needs no changes.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M30-handoff-completed.md` once landed.
