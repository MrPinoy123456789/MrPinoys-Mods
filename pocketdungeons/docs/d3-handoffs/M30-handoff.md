# M30 - Connector variations - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `LayoutStamper.java` lines 60-164: `stamp` method. Punches centered
   2x3 hole per door edge. Replace with connector pattern dispatch.
2. `RoomGeometry.java` lines 12-18: `CELL`, `DOOR_MIN`, `DOOR_MAX`,
   `DOOR_HEIGHT` constants. Door carving bounds.
3. `BedrockEnvelope.java`: `applyToCell` skips door faces. OPEN/ARCH
   faces need same treatment.

## Dependencies

Grep `sealDoor\|openDoor` in `RoomBuilder.java`. Both assume centered
2-wide opening. Need offset-aware variants.

Grep `DOOR_MIN\|DOOR_MAX` in `RoomGeometry.java`. Constants define
current centered door bounds.

## Goal

Replace fixed centered 2x3 door carve with varied connector patterns:
wide door, double door, single door, iron door, bars, open wall
with pillars, arch with lintel. Random offset along wall. Seeded.
Visual variety without changing cell size or room template format.
Iron door requires redstone signal to open: gated passage puzzle.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Connector type enum and carving

1. Add `ConnectorType` enum to `LayoutStamper` or new top-level enum:
   `DOOR_WIDE`, `DOOR_DOUBLE`, `DOOR_SINGLE`, `IRON_DOOR`, `BARS`,
   `OPEN`, `ARCH`.
2. In `LayoutStamper.stamp`, replace centered 2x3 carve with
   `carveConnector(level, origin, face, type, offset)` method.
3. `DOOR_WIDE`: 2-wide (offset, offset+1), height 3. Current default.
4. `DOOR_DOUBLE`: 4-wide (offset to offset+3), height 3.
5. `DOOR_SINGLE`: 1-wide (offset), height 3.
6. `IRON_DOOR`: 2-wide, iron door blocks. Requires redstone signal.
   No redstone source placed: room content or player provides it.
7. `BARS`: 2-wide, iron bars floor to ceiling.
8. `OPEN`: full 16-wide, full wall height. 2x2 pillars at corners.
9. `ARCH`: full 16-wide, top 2 rows remain as lintel.
10. Offset: random int in [1, 14] for DOOR types (keeps opening inside
    wall). OPEN/ARCH ignore offset.

### Step 2: Weighted random selection

1. Per door edge: pick `ConnectorType` via weighted random. Weights:
   DOOR_WIDE 45, DOOR_SINGLE 15, DOOR_DOUBLE 10, IRON_DOOR 10,
   OPEN 10, ARCH 5, BARS 5.
2. Seeded from plan seed: `rng.nextInt(100)` against cumulative weights.
3. Entrance edge (lobby-to-dungeon): always DOOR_WIDE, offset 7.
4. No manifest changes. No `door_positions` field. Templates unchanged.

### Step 3: BedrockEnvelope update

1. `applyToCell`: faces with OPEN or ARCH skip bedrock, same as door
   faces. One extra condition in existing door-face check.
2. `clearFace`: verify full-width clear works for OPEN (not just 2-wide).

### Step 4: RoomBuilder offset

1. `sealDoor`: accept offset param. Fill at offset, not always 7-8.
2. `openDoor`: accept offset. Clear at offset.
3. `LayoutStamper.stampBehindLobby`: pass DOOR_WIDE offset 7 for
   lobby edge.

### Step 5: Tests

1. `LayoutStamper` test: each connector type carves correct blocks at
   given offset.
2. Weighted random: seeded selection produces expected type.
3. Integration: `adminBuild` shows varied connectors across door edges.

## Constraints

- No manifest changes. No `door_positions` field. No new class.
- Templates must have solid wall on door-masked faces. Interior shape
  irrelevant to carving. Floor and ceiling extend to cell boundary.
- Seeded: same seed = same connectors.
- OPEN: 2x2 pillars at four corners of removed wall. Stone brick.
- ARCH: top 2 rows remain as lintel across full width.
- DOOR_SINGLE: vanilla door block (1x2).
- IRON_DOOR: iron door blocks (1x2 each, 2-wide total). Requires
  redstone signal to open. No source placed by stamper. Depends on
  M31 dungeon shell protection: without it players mine around doors.
- BARS: iron bars floor to ceiling.
- Entrance edge: always DOOR_WIDE offset 7. No random.
- `DoorMask` unchanged: which faces have connections. Connector type:
  how connection looks.
- Room shape variety (corridors, T-shapes, subrooms) is content-only
  via `.nbt` templates. No code change needed.

## Verification

- `build_mod` default `build` after each step.
- Headless: `LayoutStamper` connector tests pass. Weighted random test
  passes. `adminBuild` shows varied connectors.
- Live: OPEN edge shows wall gone, pillars at corners. DOOR_SINGLE:
  narrow opening offset from center. ARCH: lintel remains. Record in
  `LIVE_TEST_PASS.md`.
- Done when: each connector type renders correctly, default templates
  unchanged, entrance edge always DOOR_WIDE.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M30-handoff-completed.md` once landed.
