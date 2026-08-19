# Pocket Dungeons — update plan: "every run is different, and it pays"

Implementation plan for the next player-visible update, expanding
`FEATURE_PROPOSAL.md` to the specificity of `PLAN.md`. Read `PLAN.md` first —
this document assumes M0–M3 and M4.5/M8 as shipped there.

**Milestones are numbered as this update's own sequence (U1–U5), not borrowed
from PLAN.md's M-numbers.** An earlier draft named them M4a/M4b/M6a/M9a/M5a —
one letter suffixed onto whichever PLAN.md section each discharges. That
labelling was a mistake: it implied this update follows PLAN.md's M4→M5→M6→M9
order, when the real dependency graph is `library → stamping → {difficulty,
log}`, with the ritual independent of all of it. Sorting by that borrowed
number put the milestone that gates the first shippable build (difficulty)
*after* one that's pure fast-follow (the log), which is backwards. U1–U5 below
is the actual dependency and priority order; the PLAN.md cross-reference is
kept as a footnote on each section for traceability, not as the identity.

**Dependency graph:**

```
U1 room library ──▶ U2 plan stamping ──┬──▶ U3 loot & difficulty
                                        └──▶ U5 dungeon log & payout
U4 lodestone ritual (no dependency on any of the above)
```

U1 → U2 → U3 is a hard chain: the planner scored 0 of 200 seeds against the
original four rooms (measured, PLAN.md M3), so stamping plans is pointless
until the library exists, and difficulty has nothing to scale on until a run
has a path length. **U4 is fully independent** — nothing in this update
depends on it and it depends on nothing in this update; it could have run
before U1 with no change to anything else. **U5 depends only on U2** (the
`pathLength`/`lootTier` fields `InstanceLayout` already carries) — not on U3,
so it does not need to wait for difficulty tuning to land.

**First shippable build = U1 + U2 + U3.** U4 and U5 are the fast follow, and
their relative order between themselves is arbitrary.

---

## Cross-cutting decisions, made once here

These come up in more than one milestone. Settling them up front keeps three
agents from each inventing a different answer.

### 1. Cells are chunks

Slot origins are `(slot % slotsPerRow) * slotPitch`, and `slotPitch` defaults
to 2048 — a multiple of 16 — so every slot origin is chunk-aligned and **one
16×16 cell is exactly one chunk**. Force-loading becomes "one ticket per
occupied cell", with no bounding-box slop and no partial chunks.

That only holds while `slotPitch` is a multiple of 16, which nothing currently
enforces. **Add that to the config validator** (U2): `slotPitch` must be
`> 0 && slotPitch % 16 == 0`, else log and fall back to 2048.

### 2. Rotation: pivot at zero, offset the placement position

`StructurePlaceSettings.setRotation` rotates around a pivot, and for an
**even-sized** template no integer pivot maps a 16×16 footprint back onto
itself. Vanilla's transform (`StructureTemplate.transform`) with pivot
`(0,0,0)` gives `CW90: (x,z) → (-z, x)`, `CW180: (x,z) → (-x,-z)`,
`CCW90: (x,z) → (z,-x)` — all of which leave the box partly negative.

So: **keep the pivot at `BlockPos.ZERO` and shift the placement position**, per
this table (`C = RoomGeometry.CELL = 16`):

| Rotation | placement offset from `cellOrigin` | maps (x,z) → |
|---|---|---|
| `NONE` | `(0, 0, 0)` | `(x, z)` |
| `CLOCKWISE_90` | `(C-1, 0, 0)` | `(15-z, x)` |
| `CLOCKWISE_180` | `(C-1, 0, C-1)` | `(15-x, 15-z)` |
| `COUNTERCLOCKWISE_90` | `(0, 0, C-1)` | `(z, 15-x)` |

Each of these is a bijection of `[0,15]²` onto itself, so the placed volume is
still exactly the `16 × 7 × 16` box anchored at `cellOrigin` and
`JigsawFallback.replaceRemaining(level, cellOrigin, TEMPLATE_SIZE)` needs no
change at all.

`DoorMask.rotateClockwise(mask, q)` uses `q` quarter-turns clockwise, so the
mapping to Minecraft is `q=0 → NONE`, `1 → CLOCKWISE_90`, `2 → CLOCKWISE_180`,
`3 → COUNTERCLOCKWISE_90`. **Verify this against the world before building on
it** — `/dungeon admin stamptest` (U2, Stage 5) exists for exactly that, and
if the doorway lands on the wrong wall the mapping is reversed, not the mask
math (which `DoorMaskTest` already pins).

### 3. Anything the room owns must live *in* the template

Corollary of the above: template contents (blocks, block entities, marker
entities) are transformed by `placeInWorld` and therefore land correctly at any
rotation, **for free**. Anything the mod computes from `cellOrigin` afterwards
is not — `(8, ·, 8)` is not a fixed point of the rotation, so a "cell centre"
feature authored at `(8,0,8)` moves to `(7,0,8)` under `CLOCKWISE_90`.

Two consequences, both load-bearing:

- **Chest positions and mob spawn points are authored into the template** — a
  real chest block, and jigsaw blocks named `pocketdungeons:spawn` for the mob
  points (§7 explains why jigsaws rather than the spec's markers). The stamper
  reads them back out of the *template*, transformed for the placement rotation,
  never by computing an offset from `cellOrigin`.
- **The exit pad is authored as a 2×2 lodestone block** at local
  `(7..8, 0, 7..8)` — the one interior feature that *is* rotation-invariant —
  and the exit trigger becomes "the block under the player is a lodestone and
  the player is inside the instance bounds", not a coordinate comparison. That
  is template-agnostic: a future room can put a lodestone anywhere and it works.

Player spawn at the entrance cell's centre stays a computed offset, and that is
fine — the entrance cell's interior is open floor at every rotation.

### 4. Role is applied at stamp time, not baked into the template

The library is built as a **coverage floor** (one template per door-mask
family, carrying *all three* content roles) plus a **flavour layer** (themed
variants at higher weight for the common masks). Floor rooms are authored with
both a chest and spawn points, and the stamper activates one or the other from
the cell's role:

| Cell role | What `RoomContent` does |
|---|---|
| `encounter` | spawn mobs at the spawn points; remove the chest |
| `loot` | set the chest's loot table and seed; leave the spawn points unused |
| `corridor` | remove the chest; spawn nothing |
| `entrance` / `exit` | nothing (their templates carry no chest and no spawn points) |

This is what makes 100% planner resolution cheap: every `(mask, role)`
combination the generator can emit is satisfied by exactly one floor room, and
the flavour rooms only ever *improve* on that.

### 5. Entrance and terminal cells have exactly one door

A procedural entrance could otherwise land on a 3-door mask, which would mean
authoring an entrance room per mask family. Instead, **constrain the
generator**: `addBranches` skips the first and last critical-path cells, and
`addLoops` refuses any edge touching `entrance` or `terminal`. Then
`entrance_hall` (`E`) and `exit_hall` (`W`) each cover all four single-door
masks by rotation, and the player gets a cleaner read — one way in, one way out.

### 6. Never a hard reference to kamutotems

Boss stones ship as loot-table JSON (`set_custom_data`). No import, no
`fabric.mod.json` dependency, no soft-dep reflection. If kamutotems is absent
the stone is a named echo shard and nothing breaks.

### 7. Spawn points are jigsaw blocks, not markers

The spec's §5.5 answer for "where does a mob go in a rotated room" is a
`minecraft:marker` entity, and M1 proved markers survive the
`fillFromWorld`/`placeInWorld` round trip. **Use jigsaw blocks instead.**

Author spawn points as `minecraft:jigsaw` blocks at interior floor level, named
`pocketdungeons:spawn`, with `finalState = minecraft:air`. Everything else falls
out for free:

- `RoomManifest.buildEntry` already filters to `pocketdungeons:door` when
  deriving masks and documents that other jigsaw names are deliberately ignored,
  so spawn jigsaws are invisible to the mask math with no change.
- `StructureTemplate.getJigsaws(placementPos, rotation)` returns positions
  **already transformed for that rotation**, so the stamper gets rotation-correct
  world coordinates without scanning the world or doing its own transform math.
  This is the same call `RoomManifest` already uses and trusts.
- `JigsawFallback.replaceRemaining` already runs over every placed cell and
  converts leftover jigsaws to their final state, so the spawn blocks erase
  themselves with no new cleanup code, and M1's existing "**zero
  `minecraft:jigsaw` blocks anywhere in a built instance**" regression check
  covers them automatically. **One change is required for this:** the fallback
  filters by the name `pocketdungeons:door` and would leave spawn jigsaws
  standing. Widen it to filter by *namespace*.

`RoomTemplateGenerator` gets one new helper for this and otherwise reuses the
door authoring path; there is no new API surface to verify, which is the whole
point of preferring it over the marker route.

Chests are found the same cheap way: because a cell is exactly a chunk (§1),
`level.getChunk(cellOrigin).getBlockEntities()` is a direct map lookup, not a
1,792-position scan.

---

## U1 — the room library ✅ *shipped*

*(Discharges PLAN.md M3's measured content gap.)*

**Goal:** take planner success from 0% to ~100% and give a run visual variety.
Pure content work through the existing `/dungeon admin gentemplates` pipeline,
plus two constraints on the graph generator.

**Blocking:** nothing. **Blocks:** U2, U3.

**Measured on a clean `run/`, against the 14 shipped rooms:**

| Check | Result |
|---|---|
| `manifest reload` | 14 loaded, **0 rejected** |
| `manifest list` masks | every family correct at all four rotations (see below) |
| `admin coverage` | **all 53 (mask, role) pairs satisfied, 0 holes** |
| `plansurvey 200` | **200 of 200 succeeded, 200 total attempts** — every seed on the first try |
| `gradlew test` | `DoorMaskTest`, `PlanSelectorTest`, `PipelineProofTest`, `layoutGraphTest` all pass |

M3 measured 0 of 200 against the original four rooms. The planner code did not
change; the library did.

Hand-verified rotation sets from `manifest list`, which is the check that the
mask math and the authored geometry agree:

```
entrance_hall   [E / S / W / N]           entrance
exit_hall       [W / N / E / S]           exit
hall_dead_end   [N / E / S / W]           encounter, loot, corridor
hall_straight   [EW / NS / EW / NS]       encounter, loot, corridor
hall_corner     [NE / ES / SW / NW]       encounter, loot, corridor
hall_tee        [NES / ESW / NSW / NEW]   encounter, loot, corridor
hall_cross      [NESW / NESW / NESW / NESW] encounter, loot, corridor
```

**Three deviations from the specification below, all deliberate:**

1. **`JigsawFallback` had to be widened.** It filtered to `pocketdungeons:door`
   by name, so spawn jigsaws would have survived placement and stood in the
   finished dungeon. It now filters by *namespace*, which covers door, spawn, and
   any future name, and preserves M1's "zero jigsaw blocks" invariant. A jigsaw
   belonging to another mod is still left alone. **This was a latent trap in the
   design, not in the code as written** — worth noting because the spawn-jigsaw
   decision (cross-cutting §7) reads as free and was not quite.
2. **`flooded_tee` shipped as `mossy_tee`.** Water source blocks in a template
   spread out through the doorways into the neighbouring cell on placement, and a
   contained cistern buys nothing a moss floor does not. Same mask, same roles.
3. **`hall_cross`'s two extra spawn points moved** from `(2,1,8)`/`(13,1,8)` to
   `(2,1,5)`/`(13,1,10)`. The originals sat in the west and east doorway lanes —
   the cross room has doors on all four walls, so its lane exclusions are the
   strictest in the library. Caught by applying the lane rule, which is the rule
   earning its keep.

Also worth recording: `admin coverage` checks all 15 non-zero masks against the
content roles (plus the four single-door masks against `entrance`/`exit`), which
is 53 pairs, not the 17 mask *families* the Stage 1 table counts. Both framings
are correct; the command uses the stricter one because it costs nothing.

### Stage 1 — the coverage contract

M3's census says a plan fails whenever any single cell cannot resolve, so the
library must satisfy **every** `(mask family, role)` pair the generator can
emit. With §5's entrance/terminal constraint in place that grid is exactly 17
combinations:

| Mask family | Rotations cover | `entrance` | `exit` | `encounter` | `loot` | `corridor` |
|---|---|---|---|---|---|---|
| 1-door (`N`) | `N E S W` | yes — `entrance_hall` | yes — `exit_hall` | yes | yes | yes |
| straight (`WE`) | `WE NS` | — | — | yes | yes | yes |
| corner (`NE`) | `NE ES SW NW` | — | — | yes | yes | yes |
| tee (`NES`) | `NES ESW NSW NEW` | — | — | yes | yes | yes |
| cross (`NESW`) | `NESW` | — | — | yes | yes | yes |

Five *shape* templates carrying all three content roles, plus the two existing
single-role end caps, is a complete floor. Everything beyond that is flavour.

### Stage 2 — generator constraints (`LayoutGraphGenerator`)

Three changes, all in that one file, all pure logic:

1. **`addBranches`** — skip `i == 0` and `i == criticalPath.size() - 1`, so no
   spur ever roots at the entrance or terminal cell.
2. **`addLoops`** — skip any candidate edge where
   `edge.touches(entrance) || edge.touches(terminal)`; pass both cells in.
3. **`assignRoles`** — currently a coin flip between `encounter` and `loot`.
   Replace with a weighted draw plus a guarantee pass:
   - critical-path interior cells: `encounter` 45%, `loot` 25%, `corridor` 30%
   - branch cells: the **tip** of each spur is `loot` (a spur that pays nothing
     punishes exploring); interior spur cells are `corridor`
   - **guarantee pass:** if the path has no `encounter`, force the interior cell
     nearest the terminal; if it has no `loot`, force the one nearest the
     entrance. Both are required by the proposal's "at least one encounter and
     at least one loot on the path".

Also parameterise the two probabilities — add
`generate(long seed, int minPath, int maxPath, double branchProbability, double loopProbability)`
and keep the existing 3-arg form delegating to it with today's constants, so
`PipelineProofTest` and `PlanSelectorTest` do not churn.

`LayoutGraphGenerator.validate` gains one check: **entrance and terminal each
have exactly one incident open edge**, so a regression in (1) or (2) fails
loudly in the pure-JDK test rather than surfacing as an unresolvable cell
in-game weeks later.

### Stage 3 — the floor templates

All 1x1, all on the existing 16x16x7 contract (`y=0` floor, `y=1..5` walls with
air interior, `y=6` ceiling, doors 2 wide by 3 tall at local `i` in `[7,8]`,
lamps in the four ceiling quadrants). Interior usable area is `x,z` in `[1,14]`.

> **Doorway lane rule, stated once so it is not re-derived per room.** A doorway
> only actually obstructs near its own wall. Keep `x` in `[7,8]` clear for `z` in
> `[1,3]` and `[12,14]`, and `z` in `[7,8]` clear for `x` in `[1,3]` and
> `[12,14]`. The middle of the room is free to decorate. Author to that rule and
> confirm by walking every doorway in `stamptest`.

Common furniture for the three-role floor rooms:

- **chest** — `minecraft:chest` at local `(2, 1, 2)`, facing `SOUTH`, loot table
  left pointing at `pocketdungeons:chests/tier_1` (the stamper overwrites it per
  run).
- **spawn jigsaws** — `pocketdungeons:spawn`, `finalState = minecraft:air`, at
  local `(4,1,4)`, `(11,1,4)`, `(4,1,11)`, `(11,1,11)`. That set is closed under
  rotation, which makes the stamptest check easy to eyeball.

| Template | Mask @rot0 | Roles | Contents beyond the common furniture |
|---|---|---|---|
| `hall_dead_end` | `N` | encounter, loot, corridor | back wall (`z=14` row) faced in `chiseled_stone_bricks`; only two spawn jigsaws, `(4,1,11)` and `(11,1,11)` |
| `hall_straight` | `WE` | encounter, loot, corridor | `stone_brick_wall` pillars at `(4,1..3,7)` and `(11,1..3,8)` to break sightlines |
| `hall_corner` | `NE` | encounter, loot, corridor | `polished_andesite_stairs` step ring around `(8,0,8)`, cosmetic only |
| `hall_tee` | `NES` | encounter, loot, corridor | `stone_brick_slab` shelf along the doorless wall |
| `hall_cross` | `NESW` | encounter, loot, corridor | `stone_bricks` pillars at `(5,1..5,5)`, `(10,1..5,5)`, `(5,1..5,10)`, `(10,1..5,10)`; six spawn jigsaws — the four common ones plus `(2,1,5)` and `(13,1,10)`. Not `(2,1,8)`/`(13,1,8)`: with doors on all four walls this room has the strictest lane exclusions in the library, and those two sit in them |

### Stage 4 — the flavour layer

Same shapes, higher weight, so the floor rooms become the fallback a player
rarely sees rather than the room they see six times a run.

| Template | Mask @rot0 | Roles | Weight | `minDepth` | `maxPerDungeon` | Contents |
|---|---|---|---|---|---|---|
| `encounter_zombie` *(regenerate)* | `WE` | encounter | 2 | 0 | -1 | existing geometry; **drop the baked zombie**, add the four spawn jigsaws |
| `loot_vault` *(regenerate)* | `WE` | loot | 2 | 0 | -1 | existing geometry; **move the chest from `(8,1,2)` to `(2,1,2)`** — at `(8,1,2)` it sits squarely in the north doorway lane, harmless today only because the room is never rotated |
| `crypt_corner` | `NE` | encounter | 2 | 1 | 2 | cobwebs in the two doorless corners, `chiseled_stone_bricks` sarcophagus lid at `(3..5, 1, 11..12)`, four spawn jigsaws |
| `treasure_alcove` | `N` | loot | 3 | 1 | 2 | **two** chests, at `(2,1,2)` and `(13,1,2)`; `gold_block` accent at `(8,0,12)`; no spawn jigsaws |
| `pillar_cross` | `NESW` | encounter, corridor | 2 | 2 | -1 | four full-height `deepslate_bricks` pillars, sea-lantern uplights, six spawn jigsaws |
| `mossy_tee` | `NES` | encounter, corridor | 2 | 2 | 1 | mossy stone brick floor over `x,z` in `[4,11]`, mossy corner columns; four spawn jigsaws. *(Authored as moss, not water: source blocks spread out through the doorways into the neighbouring cell.)* |
| `spawner_den` | `WE` | encounter | 3 | 2 | **1** | see Stage 5 |

Two regenerations are required beyond the table:

- **`entrance_hall` loses its baked skeleton.** The entrance is where a party
  arrives and regroups, and with procedural layouts it is also where the void
  guard bounces a falling player back to. It should be safe.
- **`exit_hall` gets a 2x2 lodestone pad** at `(7..8, 0, 7..8)` — the
  rotation-invariant square from §3 — inside the existing
  `chiseled_stone_bricks` ring.

That is 14 rooms: seven floor, seven flavour.

### Stage 5 — `spawner_den`

The one room carrying a real `minecraft:spawner`, for the vanilla-dungeon feel.
Place it at local `(3, 1, 8)` — clear of both lanes — on a `mossy_cobblestone`
plinth, with one chest at `(2,1,12)` and no spawn jigsaws: the spawner *is* the
encounter.

Spawner NBT, authored by the generator and tuned to be a fight, not a farm:

```
SpawnData:         {entity: {id: "minecraft:zombie"}}
SpawnCount:        2
SpawnRange:        4
MinSpawnDelay:     200
MaxSpawnDelay:     400
MaxNearbyEntities: 6
RequiredPlayerRange: 12
```

`MaxNearbyEntities: 6` is the anti-farm lever: the spawner stops the moment six
of its mobs are alive nearby, so an AFK player in a force-loaded instance
accumulates nothing. `maxPerDungeon: 1` caps it to one per run, `minDepth: 2`
keeps it out of the opening cells, and teardown's existing entity purge handles
whatever is still alive at the end. DESIGN.md's line on this is the relevant
one: the thing to refuse is anything that scales with a machine rather than with
play, and a capped spawner inside a run that a human has to walk out of is not
that.

**API note, verify before writing.** PLAN.md's M1 notes confirm
`BaseSpawner.SPAWN_DATA_TAG` exists as a named constant in the 26.2 jar but
record that its literal value was never checked. Do the same `javap` pass on
`SpawnerBlockEntity`/`BaseSpawner` for the load path — the supported route is
building a `CompoundTag` and handing it to the block entity's load, not poking
protected fields — and confirm with `/data get block` on a placed spawner before
calling the template correct. If the tuned NBT proves awkward,
`SpawnerBlockEntity.setEntityId(EntityType, RandomSource)` with vanilla defaults
is an acceptable degraded version, but then ship `spawnerDensEnabled: false` as
the config default until the tuning lands, because vanilla defaults *are*
farmable.

### Stage 6 — room metadata

Fourteen `data/pocketdungeons/dungeon_room/*.json` files on the existing shape:

```json
{
  "template": "pocketdungeons:rooms/hall_cross",
  "footprint": [1, 1],
  "roles": ["encounter", "loot", "corridor"],
  "weight": 1,
  "minDepth": 0,
  "maxPerDungeon": -1
}
```

Floor rooms are all `weight: 1`, `minDepth: 0`, `maxPerDungeon: -1` — they are
the guarantee, so nothing may ever filter them out (see U2 Stage 2's relaxation
rule, which depends on this). Flavour rooms use the Stage 4 numbers.

### Stage 7 — verification

Headless, same discipline as M1 through M3:

1. `/dungeon admin gentemplates` writes all 14 `.nbt` files; rebuild.
2. `/dungeon admin manifest reload` reports **14 loaded, 0 rejected**. Any
   rejection is a real authoring bug (partial door, jigsaw facing the wrong way)
   and `RoomManifest` already names which.
3. `/dungeon admin manifest list` shows the expected rotation-0 masks: `N` for
   `hall_dead_end` and `treasure_alcove`, `WE` for the straights, `NE` for the
   corners, `NES` for the tees, `NESW` for the crosses.
4. **New: `/dungeon admin coverage`.** Cross-checks the loaded manifest against
   all 17 `(mask family, role)` pairs from Stage 1 and prints every hole. This is
   the regression that stops a later room edit from silently reopening M3's
   content gap. It must report **zero holes**.
5. `/dungeon admin plansurvey 200` succeeds on **at least 95% of seeds on the
   first attempt**. Anything lower means a hole `coverage` did not catch; find it
   before starting U2.
6. `PipelineProofTest` still passes. It runs against its own synthetic library so
   it is unaffected by all of the above — which is exactly why it was kept.

**Do not start U2 until step 5 passes.** U2's `StaticLayout` fallback will
happily paper over a thin library, and the symptom players report is "every
dungeon looks the same", weeks later, with no error in the log.

---

## U2 — plan stamping and the `Instances` glue ✅ *shipped*

*(Discharges PLAN.md M4 -- the real stamper -- and the part of M7 that variable layouts force.)*

**Goal:** `/dungeon` builds the planned dungeon instead of the fixed four-room
line, at any layout size, with `StaticLayout` as the honest fallback.

**Blocked by:** U1. **Blocks:** U3.

**Measured on a clean `run/`:**

| Check | Result |
|---|---|
| `admin stamptest` | all four rotations **OK** — `NES / ESW / NSW / NEW`, observed doors equal expected, four spawn points each, none outside its cell |
| determinism | seed 4242 in slots 0 and 1 gave identical relative geometry (entrance `+8,+1,+40`, pad `+56,+0,+56`, 4x4 cells, 7 rooms) |
| jigsaw sweep | `fill 0 64 0 63 70 63 stone replace minecraft:jigsaw` → **"No blocks were filled"** across all 28,672 blocks — doors *and* spawn jigsaws |
| exit pad | all four `2x2` lodestones present in a rotated exit room |
| geometry | entrance floor solid, standing space clear, ceiling present |
| tier curve | path 5 → tier 1, path 7 → tier 2, as specified |
| teardown | every checked block air afterwards, slot returned, `admin list` empty |
| force-load release | post-purge blocks are unreachable until `forceload add` — the tickets really are gone |
| fallback | with the library removed: 16 attempts, reason logged with the exact unresolvable cell and mask, four-room static layout built, labelled `STATIC FALLBACK` in both `build` and `list` |
| `gradlew build` | all four tests pass |

**Two problems found and fixed during verification:**

1. **The manifest never loaded on its own.** M2 deliberately made the load an
   explicit admin command, and nothing ever called it at startup — so on a
   freshly started server `RoomManifest.current()` was empty, every `/dungeon`
   failed to plan, and **every player would have silently got the static
   fallback**. Now loaded on `SERVER_STARTED`, with rejections logged. Wiring it
   to `/reload` is still the open follow-up M2 described; "explicit" was never
   meant to include the server's own startup.
2. **`ChunkPos` is a record in 26.2** — `chunk.x()`, not `chunk.x`. Caught at
   compile time, noted only because the field form is what older code uses.

**One deviation:** `admin build` registers a memberless `InstanceRecord` rather
than leaving the slot recordless. Teardown needs the layout to know which cells
to clear, and `bySlot` already documents that it holds instances which
momentarily have no members. The recordless path is kept as a safety net and
clears the maximum permitted footprint instead of guessing.

**Two robustness bugs found in a dedicated review pass after shipping, both
fixed, both structural rather than cosmetic:**

1. **A stamp exception mid-plan could orphan blocks.** `LayoutStamper.stamp`
   writes cell by cell; a failure partway (a missing template, a malformed
   manifest entry) left the already-placed cells standing while `buildLayout`'s
   catch block only released the force-load tickets and returned null. The slot
   was then freed immediately by the caller, so the *next* `/dungeon` to land on
   that slot got a fresh layout stamped on top of the leftover debris with no
   plan to ever clear it — the new instance's own teardown only clears its own
   footprint. Fixed: both failure branches in `buildLayout` now route through
   `teardown` (via a new `InstanceLayout.forClearingOnly` factory carrying just
   the attempted geometry), which sweeps the partial write and only then frees
   the slot. Neither caller (`enter`, `adminBuild`) removes the slot from
   `usedSlots` on failure any more; that ownership now belongs entirely to the
   queued clear, same as every other release path.
2. **A duplicate teardown on the same slot could silently overwrite a
   different, later instance.** The tick-spread clear from Stage 6 means a slot
   stays in `usedSlots` (reserved) but out of `bySlot` (unowned) for however long
   its clear takes — seconds, not the one tick it used to be. `adminPurge` on a
   slot in that state falls into its "unowned, teardown by slot number" branch,
   and nothing stopped a second `/dungeon admin purge <slot>` from queuing a
   *second* `PendingClear` for the same slot. When the first one finished it
   freed the slot for reuse; the second, still running, kept writing air into
   whatever new dungeon had since been stamped there. Reproduced live only after
   forcing the clear window wide enough to fire commands inside it (zero-delay
   duplicate purges); at the default 8,192 blocks/tick budget a small dungeon's
   clear finishes inside a tick and the window is too narrow to hit by accident
   in normal play, but it was real and the fix closes it regardless. Fixed: a
   `isClearing(slot)` guard in `teardown` rejects a second teardown for a slot
   already mid-clear, logged at warn. Verified: three duplicate `admin purge`
   calls in immediate succession now produce exactly one `Closed dungeon` line,
   two `already has a clear in progress` warnings, and `admin list` correctly
   empty afterward with no stale clear left in the queue.

Neither bug is reachable through ordinary play without operator action or a
missing/corrupted template file, and the codebase's own bookkeeping
(`byMember`/`bySlot`) already prevents any *natural* double-teardown of the same
record — both require either an operator issuing a redundant `admin purge`, or a
stamp genuinely throwing partway through. Fixed anyway, since "operator error"
and "a template file goes missing" are both things that will eventually happen
on a long-running server, and both failure modes look invisible at the moment
they're triggered and only manifest as damage to an unrelated future dungeon.

This is the milestone PLAN.md calls M4 (real stamper) plus the part of M7
(teardown at scale) that variable layouts force. It is the largest of the five
and touches `Instances.java`, which is why every other milestone is specified to
not touch that file.

### Stage 1 — `PlanGeometry`: cells to world

New file. The planner works in grid coordinates that can go negative (the walk
starts at `(0,0)` and may head west or north), so a normalisation step sits
between the plan and the world.

```java
record PlanGeometry(BlockPos origin, int minCellX, int minCellZ,
                    int spanX, int spanZ, List<PlanCell> cells) {
    static PlanGeometry of(BlockPos origin, Collection<PlanCell> cells);
    BlockPos cellOrigin(PlanCell cell);   // origin + ((x-minX)*16, 0, (z-minZ)*16)
    BlockPos cellCentre(PlanCell cell);   // cellOrigin + (8, 1, 8)
    AABB bounds();                        // spanX*16 by CEILING_Y+1 by spanZ*16
    List<ChunkPos> chunks();              // exactly one per occupied cell (see §1)
}
```

`chunks()` returning **one chunk per occupied cell, not per bounding-box cell**
is the point of the class. A 5x8 bounding box holding 12 rooms force-loads 12
chunks, not 40.

### Stage 2 — `RoomSelector`: seeded weighted pick, `maxPerDungeon`, `minDepth`

`resolveDetailed` today sorts matches alphabetically and takes the first
(`RoomSelector.java:55-57`), so every `WE` cell in every dungeon gets the same
room. Replace that with:

1. `Random rng = new Random(shape.seed() * 31 + 17)` — derived from the plan
   seed so a seed still reproduces a dungeon exactly. Do not reuse the shape
   generator's own `Random`; it is already consumed.
2. Precompute `Map<PlanCell,Integer> depth` — BFS hop count from `shape.entrance()`
   over `openEdges`. Needed by `minDepth` and reused by U3's difficulty curve, so
   **put it on `DungeonPlan` as a new `Map<PlanCell,Integer> depths` field**
   rather than recomputing it downstream.
3. Iterate cells in the existing deterministic `sortedCells` order, keeping
   `Map<String,Integer> used`.
4. Filter `queryAnyRotation(mask, role)` to matches where
   `meta.minDepth <= depth(cell)` and (`meta.maxPerDungeon < 0` or
   `used.get(name) < meta.maxPerDungeon`).
5. **If the filter empties a non-empty match list, drop the filter and use the
   unfiltered list.** `minDepth` and `maxPerDungeon` are preferences, not
   constraints; failing a whole plan because the one legal room was already used
   twice would reintroduce M3's failure mode for no player-visible benefit. The
   floor rooms being unlimited (U1 Stage 6) means this relaxation almost never
   fires.
6. Weighted pick over the surviving matches by `meta.weight`, then increment
   `used`.

Rotation ties: a room whose mask is rotationally symmetric (`NESW`, or `WE`
matching an `NS` cell at two rotations) yields several `Match` entries for the
same entry. Weight each `Match` independently — that gives symmetric rooms a
free extra roll, which is harmless, and picking a random rotation for a
symmetric room is desirable anyway.

`RoomSelector.validate` gains a **span budget**: reject a plan whose
bounding box exceeds `maxGridSpan` on either axis (config, default 12). This is
the guard that keeps force-load tickets and teardown volume bounded; a rejected
plan costs one retry.

Gate stays as-is: `buildGate` keeps producing the placeholder, `validate` keeps
running the real BFS-with-keys, and **U2's stamper ignores `plan.gate()`
entirely.** No lock block is authored, so nothing is enforced in-world. Leave
the scaffolding alone rather than deleting it.

### Stage 3 — `LayoutStamper`

New file; `TemplateStamper` stays for the `StaticLayout` path and grows one
rotation-aware placement helper the new class shares.

```java
final class LayoutStamper {
    static InstanceLayout stamp(ServerLevel level, BlockPos origin,
                                DungeonPlan plan, DifficultyProfile profile);
}
```

Per cell, in critical-path order first then branches (so the entrance is
walkable earliest, per spec §8.3):

1. `RoomManifest.current()` lookup by `PlacedRoom.name()`, then
   `manager.get(entry.meta.template)`.
2. `Rotation rotation = ROTATIONS[placed.rotation()]` where
   `ROTATIONS = {NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90}` — the
   `q`-quarter-turns-clockwise mapping from cross-cutting decision §2.
3. `BlockPos placementPos = geometry.cellOrigin(cell).offset(ROTATION_OFFSET[q])`
   using §2's offset table.
4. `settings.setRotation(rotation)`, `setRotationPivot(BlockPos.ZERO)`,
   `setIgnoreEntities(false)`, `addProcessor(JigsawReplacementProcessor.INSTANCE)`.
5. `template.placeInWorld(level, placementPos, placementPos, settings,
   RandomSource.create(plan.seed() ^ cellHash), STAMP_FLAGS)`.
6. **Collect spawn positions before the fallback erases them:**
   `template.getJigsaws(placementPos, rotation)` filtered to
   `pocketdungeons:spawn`.
7. `RoomContent.apply(level, cell, cellOrigin, role, depth, profile, spawns, rng)`
   — U3 owns this; in U2 it is a stub that does nothing but the chest-removal
   and loot-table-retarget branches, so the milestone is independently testable.
8. `JigsawFallback.replaceRemaining(level, cellOrigin, TEMPLATE_SIZE)`.

Return an `InstanceLayout` (Stage 4). Any exception propagates: `Instances` already
has an abort path that releases the ticket and the slot, and that path is the one
thing that must not be weakened.

### Stage 4 — `InstanceLayout` and `InstanceRecord`

`Instances` currently reaches for `StaticLayout.entrance(origin)` and friends
from five places. Replace all of them with a value carried on the record.

```java
record InstanceLayout(
        BlockPos origin,
        PlanGeometry geometry,
        BlockPos entrance,      // player arrival, cellCentre of the entrance cell
        float entranceYaw,
        BlockPos exitPad,       // centre of the terminal cell, for admin output only
        AABB bounds,
        long seed,
        int pathLength,
        int roomCount,
        int lootTier,
        boolean procedural) {}
```

- `InstanceRecord` gains `final InstanceLayout layout;` (constructor arg) and
  `final Set<UUID> paid = new HashSet<>();` (U5 uses it; declare it now so U5
  does not have to reopen this file).
- `StaticLayout` gains `static InstanceLayout layout(BlockPos origin)` returning
  the M0 four-room values, `procedural = false`, `pathLength = 4`, `lootTier = 1`.
  Its existing `entrance`/`exitPad`/`bounds`/`entranceYaw` methods stay — the new
  factory just packages them.
- `entranceYaw` is derived from the entrance cell's single door direction, facing
  into the dungeon: `NORTH -> 180f`, `SOUTH -> 0f`, `WEST -> 90f`, `EAST -> -90f`.
  That last one matches `StaticLayout.entranceYaw()`'s existing `-90.0f`, which is
  a useful sanity check on the table.

Call sites to change in `Instances.java`: `admit` (:228), the void guard and exit
check in `onTick` (:472, :479), `teardown` (:515), `clear` (:551), `forceLoad`
(:651), and `adminBuild`/`adminList` for reporting.

### Stage 5 — `Instances.enter`

The new shape of `enter`, keeping every existing guard and message:

1. Existing checks (already in a dungeon, dimension present) — unchanged.
2. `long seed = level.getRandom().nextLong();`
3. `LayoutPlanner.Outcome outcome = LayoutPlanner.plan(seed, RoomManifest.current(),
   PocketDungeonsConfig.planAttemptBudget(), pathMin, pathMax, branchProb, loopProb);`
4. `int partySize = 1` at open time; parties join later, so **the difficulty
   profile is computed once, at stamp time, from party size 1**. See U3 Stage 5
   for why that is deliberate and what the alternative costs.
5. Allocate the slot, force-load `geometry.chunks()`, stamp.
6. **On planner failure:** log the reason at `warn` with the seed, stamp
   `StaticLayout` instead, and tell the player plainly — "The dungeon collapsed
   into its oldest shape." Do not fail the command. The fallback existing and
   being *visible* is what makes a library regression get reported.
7. **On stamp failure:** the existing abort path, unchanged — release the ticket,
   free the slot, "The dungeon failed to build. You have not been moved."

The entry message needs rewriting; it currently says "Four rooms, heading east."
Replace with the room count and the two rules that still hold (non-lethal, dropped
items are lost, lodestone or `/dungeon exit` to leave), and mention the seed only
on the admin path.

**`/dungeon admin build` gains an optional seed argument**
(`/dungeon admin build [seed]`) so a specific layout can be built and inspected
headlessly. Without a seed it rolls one and reports it. Reporting the seed is what
makes a bad stamp reproducible.

**Open decision from PLAN.md, resolved:** `/dungeon [seed]` stays **op-only**, via
`admin build`. Players get a random seed. Shared seeds are a nice social hook but
they also let a player scout a layout, find the tier-3 chest, and re-enter the same
seed until inventory space runs out — which is exactly DESIGN.md's "unbounded per
unit time" line. Revisit if the payout ever moves off per-instance-per-player.

### Stage 6 — force-load, bounds, and teardown at variable size

**Force-load** takes the layout: one `setChunkForced` per `geometry.chunks()`
entry, called with `true` before stamping and `false` on every release path. The
existing single `teardown` funnel means there is one place to get that right, and
it is already correct — it just needs the chunk list instead of the 4x1 loop.

**Bounds** come from `layout.bounds()`.

**Clear** is the real change. Today `clear` walks a fixed `4 * 16 x 7 x 16` box
(7,168 blocks) in one tick. A 12-room procedural layout is ~21,500 blocks, and a
worst-case 12x12 bounding box would be 258,000 — which is why the span budget in
Stage 2 exists, and why clearing must follow **occupied cells, not the bounding
box**.

Add a tick-spread clear:

```java
private record PendingClear(int slot, BlockPos origin, AABB bounds,
                            List<BlockPos> cellOrigins, int cellCursor,
                            int blockCursor, String reason) {}
```

- `teardown` builds the `PendingClear` and pushes it onto a queue instead of
  clearing inline.
- The existing tick watcher drains up to `clearBlocksPerTick` (config, default
  8,192) block writes per tick across all pending clears — one global budget, so
  three simultaneous teardowns do not stack, matching spec §8.3's reasoning for
  stamping.
- **The slot is not returned to `usedSlots` until its clear completes.** Today
  `teardown` removes it immediately; deferring it is the correctness fix that
  makes tick-spreading safe, otherwise a new `/dungeon` can be handed a slot that
  is still half-full of the last dungeon.
- Entity purge and `forceLoad(..., false)` also move to completion. The chunks
  must stay loaded while the clear runs, or the writes are silently dropped —
  the same trap §8.2 flags for stamping.
- The stray-player sweep stays **at the start**, keeping its
  `excludeFromStraySweep` argument exactly as M0's disconnect fix left it. Nothing
  about that fix may be disturbed.
- On server shutdown, drain the queue synchronously — a leaked force-load ticket
  pins chunks for the process lifetime (PLAN.md's M4 amendment).

With the default budget a 12-room layout clears in three ticks.

### Stage 7 — verification

1. **`/dungeon admin stamptest`** (new, dev-only alongside `gentemplates`) —
   stamps `hall_tee` into four adjacent cells at rotations 0, 1, 2, 3 and prints
   the door edges and spawn-jigsaw world positions it derives for each. This is
   the check that proves cross-cutting decision §2's offset table. Expected: the
   `NES` mask reads as `NES`, `ESW`, `NSW`, `NEW`; each cell's placed volume stays
   inside its own 16x16 footprint; the four spawn positions map onto each other
   across rotations. **If the doorway lands one block outside the cell, the
   offset table is wrong; if it lands on the wrong wall, the
   quarter-turn-to-`Rotation` mapping is reversed.** Those two failures look
   identical in-world and this command is what separates them.
2. `/dungeon admin build 12345` twice, in two slots, produces identical geometry
   — determinism.
3. Full M0/M1 regression against a procedural build: floor/wall/ceiling present,
   both sides of every doorway open, **`/fill ... replace minecraft:jigsaw` over
   the whole instance volume finds zero** (this now also covers the spawn
   jigsaws), lodestone pad present in the terminal cell.
4. **Seam check:** for every open edge in the plan, both facing wall segments are
   air; for every *closed* edge between two occupied adjacent cells, both are
   solid. That second half is the "no orphan connectors" property `RoomManifest`'s
   exact-match rule is supposed to guarantee, and it is only observable in-world.
   Worth scripting as a one-off `execute if block` sweep over the plan's edges.
5. `/dungeon admin purge <slot>` clears every block and entity, returns the slot,
   and releases every ticket. Confirm with a second `build` landing on the same
   slot and finding clean ground.
6. Force a planner failure (temporarily rename a floor room's `.json`) and confirm
   `/dungeon` still works, lands in the four-room static layout, and says so.
---

## U3 — tiered loot and scaled mobs

**Goal:** a run pays out in proportion to how long and how dangerous it was, and
the danger scales with the run rather than being four fixed mobs.

**Blocked by:** U2 (needs `pathLength`, `depths`, and the per-cell stamp hook).

### Stage 1 — `DifficultyProfile`, pure logic

New file, **no Minecraft imports** — same discipline as `DoorMask` and
`LayoutGraphGenerator`, so it is testable with plain `javac`.

```java
record DifficultyProfile(int pathLength, int partySize, int baseMobs, int maxMobsPerRoom) {

    static DifficultyProfile of(int pathLength, int partySize);   // pulls config defaults

    int lootTier();                       // path length -> 1..3
    int effectiveTier(int depth);         // deep cells fight one tier up
    int mobCount(int depth);              // mobs for an encounter cell at that depth
    List<WeightedEntry> mobRoster(int tier);  // entity id strings + weights
    int chestRolls();                     // bonus rolls from party size

    record WeightedEntry(String entityId, int weight) {}
}
```

**Loot tier** (the proposal's curve, unchanged):

| Path length | Tier |
|---|---|
| ≤ 5 | 1 |
| 6–7 | 2 |
| 8+ | 3 |

**Effective tier by depth** — the far end of a dungeon should bite harder than
the first room, and this is one line rather than a curve:

```
effectiveTier(depth) = min(3, lootTier() + (depth >= (pathLength * 2) / 3 ? 1 : 0))
```

**Mob count**, the proposal's formula with a floor and a ceiling:

```
mobCount(depth) = clamp(baseMobs + pathLength/3 + (partySize - 1), 1, maxMobsPerRoom)
```

`baseMobs` defaults to 2 and `maxMobsPerRoom` to 8, so the range across the whole
config space is 3 (a 5-room solo run) to 7 (an 8-room party of four), capped at 8.
Depth does not enter the count — it enters the *roster*. That keeps the two levers
independent and makes a tuning complaint ("too many" vs "too nasty") map to one
config field each.

**Rosters**, as entity id strings so this file stays Minecraft-free:

| Tier | Roster (weight) |
|---|---|
| 1 | zombie 5, skeleton 4, spider 1 |
| 2 | zombie 4, skeleton 4, husk 2, stray 2, spider 2, cave_spider 1 |
| 3 | zombie 3, skeleton 3, husk 2, stray 2, spider 2, cave_spider 1, pillager 2, vindicator 2, wither_skeleton 1 |

Nothing here can kill a player — death rescue is non-lethal — so the roster can be
mean. It deliberately excludes creepers (they would blow holes in the sealed cell
contract and let a player into the void), endermen (they teleport out of the
instance), and anything that drops a player through the floor.

**`chestRolls()`** returns `partySize - 1` extra rolls on the bonus pool, so a
party of four opening one chest gets meaningfully more than a solo player opening
the same chest. Chest loot is shared and first-come; the *per-player* generosity
lever is U5's completion payout, which pays every member individually. Say that
out loud in the entry message so a party is not surprised.

### Stage 2 — `RoomContent`

New file, the Minecraft-facing half. Called by `LayoutStamper` per cell
(U2 Stage 3 step 7).

```java
static void apply(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                  DifficultyProfile profile, List<BlockPos> spawnPoints,
                  long seed);
```

- **`encounter`** — remove the chest (see below); spawn `profile.mobCount(depth)`
  mobs, rolling each type from `profile.mobRoster(profile.effectiveTier(depth))`
  with a `RandomSource` seeded from `seed ^ cellOrigin.asLong()`. Spawn at
  `spawnPoints` in shuffled order, cycling with a small jitter if the count
  exceeds the number of spawn jigsaws. Every mob gets
  `setPersistenceRequired()` — without it they despawn out from under a player
  who backtracks. Skip any spawn position whose block is not air.
- **`loot`** — leave the chest; retarget it (below). Do not spawn mobs.
- **`corridor`** — remove the chest, spawn nothing.
- **`entrance` / `exit`** — nothing; their templates carry neither.

**Chest handling.** Because a cell is a chunk (cross-cutting §1), get the
`LevelChunk` for `cellOrigin` and iterate `getBlockEntities()`, filtering to
positions inside the cell that are `RandomizableContainer`. For each:

- retarget: `container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
  id("chests/tier_" + profile.lootTier())))` and
  `container.setLootTableSeed(seed ^ pos.asLong())`.
- remove: `level.setBlock(pos, AIR, UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS)` —
  the suppress-drops flag matters for the same reason `RoomBuilder` documents it.

**Set the loot table seed explicitly.** PLAN.md's M1 notes record that vanilla
assigns a non-zero `LootTableSeed` to a placed chest on its own even when nothing
calls `setLootTableSeed`, and warns not to assume anything about that state once
a table has real randomness. Tier 2 and 3 do have real randomness, so setting the
seed from the run seed is what keeps a seed reproducible for debugging.

**Spawner dens** need no code here: the spawner is authored into the template and
runs itself. If `spawnerDensEnabled` is false, `RoomContent` replaces any
`minecraft:spawner` it finds with `mossy_cobblestone` — a one-line kill switch
that does not require re-authoring the template or editing the manifest.

### Stage 3 — the loot tables

Four files under `data/pocketdungeons/loot_table/chests/`. The mandate is
DESIGN.md §5: *err on the side of paying out*; judge a payout by how often a real
player can reach it, not by how big it is. A dungeon run is gated by walking it,
so the ceiling per unit time is a human's, not a machine's.

`cobblestone` appears in tier 1 deliberately — it is `cobbleeconomy`'s low
currency, and a faucet that feeds the hub mod without importing it is the suite's
whole coupling model in one loot entry.

**`tier_1.json`** — replaces today's single-diamond placeholder.

```
pool 1 (rolls 1):   diamond x1                                    [guaranteed]
pool 2 (rolls 2-3): iron_ingot 4-9        w10
                    gold_ingot 2-5        w8
                    cobblestone 16-32     w6
                    coal 4-12             w6
                    emerald 1-3           w5
                    lapis_lazuli 3-8      w4
                    experience_bottle 2-5 w4
                    diamond 1             w2
pool 3 (rolls 1, 50% chance):
                    bread 3-5             w3
                    cooked_beef 2-4       w3
                    arrow 8-16            w2
                    golden_apple 1        w1
```

Keeping the guaranteed diamond preserves M1's verification story, with one
amendment: **the check becomes "the chest contains at least one diamond", not
"exactly one diamond in slot 0".** Update the regression script accordingly.

**`tier_2.json`** — tier 1's pools with better counts, plus:

```
pool 4 (rolls 1):   diamond 2-4                w6
                    emerald 4-9                w6
                    iron_block 1               w3
                    enchanted_book (enchant_with_levels 20-30, treasure allowed) w3
                    golden_apple 1-2           w2
pool 5 (rolls 1, 30% chance):  boss stone tier 1  w3
                               boss stone tier 2  w1
```

**`tier_3.json`** — tier 2's pools with better counts, plus:

```
pool 4 (rolls 2):   diamond 3-6                w6
                    emerald 8-16               w6
                    diamond_block 1            w2
                    netherite_scrap 1          w1
                    enchanted_book (enchant_with_levels 30-40, treasure allowed) w4
                    enchanted_golden_apple 1   w1
pool 5 (rolls 1, 45% chance):  boss stone tier 2  w3
                               boss stone tier 3  w1
```

**`bonus.json`** — the pool `chestRolls()` adds for a party, referenced from
tiers 2 and 3 via `minecraft:loot_table` so party scaling is one edit, not three.

**Boss stone entry**, the kamutotems hook, pure datapack:

```json
{
  "type": "minecraft:item",
  "name": "minecraft:echo_shard",
  "weight": 3,
  "functions": [
    { "function": "minecraft:set_custom_data", "tag": "{kamutotems:{boss_stone:2}}" },
    { "function": "minecraft:set_name",
      "name": { "text": "Boss Stone II", "italic": false, "color": "light_purple" },
      "target": "custom_name" },
    { "function": "minecraft:set_lore", "mode": "replace_all",
      "lore": [ { "text": "Grip it and a trial finds you.", "italic": true, "color": "gray" } ] }
  ]
}
```

`kamutotems.BossStone.tier` reads
`custom_data -> "kamutotems" -> "boss_stone"` as an int, so that SNBT is exactly
the shape it expects. With kamutotems absent it is a named echo shard and nothing
in either mod notices.

**Verify these three function shapes against the real 26.2 build before shipping**
— `set_custom_data`'s `tag` argument has been both an SNBT string and an object
across versions, and `set_name`/`set_lore` gained their `target`/`mode` fields
recently. The cheap check is `/loot give @s loot pocketdungeons:chests/tier_3`
followed by `/data get entity @s SelectedItem`; a wrong shape fails loudly at
datapack load, which is the good case, but a *silently ignored* function is the
one to watch for.

### Stage 4 — how a run reads

At the intended numbers, a solo tier-1 run (5 rooms, ~2 loot cells) yields roughly
1–2 diamonds, ~10 iron, ~5 gold, a stack of cobble and some emeralds, against
three fights of 3 mobs. A tier-3 party run yields several diamonds, a couple of
enchanted books, 20-plus emeralds and a decent shot at a boss stone, against
six-ish fights of 6–7 mobs plus a spawner den. That is generous on purpose and it
is all config-adjacent: the tables are datapack JSON an operator can edit without
touching the jar.

### Stage 5 — the party-size question, answered

`DifficultyProfile` is built **once, when the instance is stamped**, from the
opening player alone. Members invited afterwards walk into a dungeon scaled for
one. The alternatives were considered and rejected:

- **Re-stamping on join** — reruns `RoomContent` over already-explored rooms,
  respawning mobs behind the party and re-rolling chests they may have opened.
- **Spawning the difference on join** — needs a per-cell "already cleared" record
  that nothing currently keeps, and it means mobs appearing out of thin air in a
  room the party is standing in.

Instead: **invites are cheap and the fix is social.** Party up *before* running
`/dungeon` — `/dungeon invite` already works from outside an instance? It does
not (`invite` requires the inviter to be inside). So U3 adds one small thing:
`/dungeon party <player>` to pre-register a companion **before** entry, so the
opening player's party size is known at stamp time and everyone is teleported in
together. `invite`/`join` stay exactly as they are for the mid-run case, and a
mid-run joiner simply gets an easier dungeon — which is the forgiving failure
mode, not the punishing one.

If that pre-party command is judged out of scope, ship without it and let
`partySize = 1` always; the formula still works, the dungeon is just easier for
groups. **Do not** re-stamp.

### Stage 6 — verification

1. `DifficultyProfileTest` (new pure-JDK test, wired into `tasks.test` alongside
   the existing three): tier boundaries at path length 5/6/7/8; `mobCount` clamps
   at both ends; `effectiveTier` never exceeds 3; rosters are non-empty and have
   positive weights for tiers 1–3 and throw for anything else.
2. `/loot give @s loot pocketdungeons:chests/tier_1` (and 2, 3) — inspect the
   drops directly. Run each 20 times to see the spread, and confirm tier 2/3
   produce boss stones with the right `custom_data` via `/data get entity @s
   SelectedItem`.
3. `/dungeon admin build <seed>` on a seed known to give path length 8, then
   `/data get block <chest pos>` — the chest carries
   `LootTable: "pocketdungeons:chests/tier_3"` and a non-zero `LootTableSeed`.
   (Note M1's finding: the NBT key is `LootTable`, capital, not `loot_table`.)
4. Count mobs in an encounter cell with
   `execute if entity @e[type=#minecraft:...,x=..,dx=16,...]` and check it against
   `mobCount` for that depth.
5. Confirm a `corridor` cell has **no chest and no mobs**, and that a `loot` cell
   has a chest but no mobs. Those two are the role-dispatch regression.
6. `/dungeon admin purge` still clears every spawned mob — the entity sweep
   already covers this, but the mob count is now up to 8 per cell rather than 1,
   so re-run it and confirm zero entities and zero drops remain.

---

## U4 — the lodestone ritual

**Goal:** the mod becomes discoverable in-world instead of living behind a chat
command.

**Blocked by:** nothing. Fully independent of U1/U2/U3 — it touches one new file
plus a config block, and calls `Instances.enter` exactly as `/dungeon` does.

### Stage 1 — `RitualListener`

New file, ~70 lines, registered from `PocketDungeonsMod.onInitialize`.

```java
static void register() {
    UseBlockCallback.EVENT.register(RitualListener::onUseBlock);
}

private static InteractionResult onUseBlock(Player player, Level level,
                                            InteractionHand hand, BlockHitResult hit);
```

Fires only when **all** of these hold, and returns `InteractionResult.PASS`
otherwise:

1. `ritualEnabled` is true.
2. Server side, `level instanceof ServerLevel`, `player instanceof ServerPlayer`.
3. `hand == InteractionHand.MAIN_HAND` — otherwise the event fires twice.
4. The clicked block is `minecraft:lodestone`.
5. The player is **not** already in a dungeon (`Instances.hasInstance`) and not in
   `pocketdungeons:void` — otherwise stepping on the exit pad and right-clicking
   it would consume a key.
6. The held stack matches `ritualKeyItem` with `count >= ritualKeyCount`.
7. **The held stack is a plain key** — see Stage 2.

On success: shrink the stack by `ritualKeyCount`, play
`SoundEvents.RESPAWN_ANCHOR_CHARGE` (or `BEACON_ACTIVATE`) at the lodestone for
flavour, call `Instances.enter(serverPlayer)`, and return
`InteractionResult.SUCCESS`.

**Consume the key only after `enter` has succeeded.** `Instances.enter` already
has failure paths that leave the player where they stood (dimension missing,
stamp failure); eating the key on those is a real loss of a real item. Since
`enter` returns `void` today, either give it a `boolean` return or check
`Instances.hasInstance(player)` immediately after the call. The boolean is
cleaner and is a two-line change to `Instances`.

### Stage 2 — the echo shard collision

The default key item is `minecraft:echo_shard`, and **kamutotems sigils and boss
stones are also echo shards** — `Sigil.java:173` and `:193` both build
`new ItemStack(Items.ECHO_SHARD, 1)` and attach `custom_data`, and this mod's own
tier-2/tier-3 loot tables mint boss stones on the same item (U3 Stage 3).

Without a guard, right-clicking a lodestone while holding a sigil destroys it.

```java
private static boolean isPlainKey(ItemStack stack) {
    CustomData data = stack.get(DataComponents.CUSTOM_DATA);
    return data == null || data.isEmpty() || !data.copyTag().contains("kamutotems");
}
```

Any stack carrying a `kamutotems` compound is refused with a `PASS`, not a
failure — passing lets the event chain continue so kamutotems'
`UseItemCallback` (registered in `AssignedQuestHost` and `BossHost`) still gets
its turn and the sigil behaves normally. Returning `FAIL` or `SUCCESS` here would
swallow it.

Broader, and worth writing as the rule rather than a special case: **the ritual
only ever consumes a stack with no `custom_data` at all.** Another mod's tagged
echo shard is not this mod's key either. The `kamutotems` check above is the
narrow version; the strict version is `data == null || data.isEmpty()`, and the
strict version is the one to ship — it is the same amount of code and it cannot
be wrong about a mod nobody has written yet.

An operator who wants the collision gone entirely can set `ritualKeyItem` to
anything else; `minecraft:amethyst_shard` is the obvious alternative and nothing
in the suite touches it.

### Stage 3 — discoverability and cost

A lodestone costs a netherite ingot, so the *lodestone* is not the recurring
cost — the key item is. Echo shards come from ancient city loot only, which makes
them scarce in a way that is thematically right but may be too scarce to sustain
a repeatable faucet. Two mitigations, both config, neither requiring code:

- `ritualKeyCount` defaults to 1.
- An operator who finds shards too scarce points `ritualKeyItem` at something
  renewable.

`/dungeon` stays available to everyone and free. The ritual is a *flavour* entry
point and a soft sink, not a gate; making it the only way in would turn a faucet
into a locked door, which is the opposite of this update's purpose.

### Stage 4 — verification

1. Right-click a lodestone holding an echo shard, outside a dungeon: one shard
   consumed, run starts.
2. Right-click holding a shard with `custom_data` (mint one with
   `/give @s echo_shard[custom_data={kamutotems:{boss_stone:1}}]`): **nothing
   consumed**, no dungeon, and with kamutotems installed the stone behaves
   normally.
3. Right-click the *exit pad* lodestone from inside a dungeon: nothing happens
   and no key is consumed.
4. Right-click with an empty hand, and with the wrong item: `PASS`, vanilla
   lodestone behaviour intact (a compass still binds to it).
5. Set `ritualEnabled: false` and confirm the listener is inert.
6. Temporarily break the dimension so `enter` fails, and confirm the key is
   **not** consumed.

---

## U5 — the dungeon log and completion payout

**Goal:** a reason to come back tomorrow, and the economy hook that makes Pocket
Dungeons a faucet rather than a diversion.

**Blocked by:** U2 for `pathLength`/`lootTier`; the `SavedData` half is
independent of everything, including U3, and can be built first.

### Stage 1 — `DungeonLog` (`SavedData`)

New file, modelled on `wondrous/WondrousState` — that is the suite's working
example of the modern `SavedDataType` + `Codec` shape, including its hard-won
note about `Codec.unboundedMap` silently losing the whole file when keys are not
bare strings.

```java
record Entry(int runsCompleted, int bestPathLength, int streak,
             String lastCompletedDateKey) {}

final class DungeonLog extends SavedData {
    static DungeonLog forServer(MinecraftServer server);   // overworld storage
    Entry get(UUID player);
    Entry recordCompletion(UUID player, int pathLength);   // returns the new entry
}
```

- Stored on `server.overworld().getDataStorage()`, same reasoning as
  `WondrousState.forServer`: one store, not per-dimension.
- Keyed by UUID; **serialise as a list of entries**, not a map, for exactly the
  reason `WondrousState` documents.
- `lastCompletedDateKey` is an ISO date string (`LocalDate.now().toString()`),
  matching `kamutotems.core.Streak`'s convention so the two mods' streaks behave
  the same way for a player who plays both.

Streak rule, deliberately simpler than kamutotems' grace system:

```
same day as last     -> streak unchanged, runsCompleted++
exactly one day after-> streak++
any larger gap       -> streak = 1
unparseable / null   -> streak = 1
```

No grace days. Kamutotems has grace because missing a daily chain there costs a
long accumulation; here the streak only scales a payout, so the failure mode of a
reset is mild and the code is a third of the size.

`/dungeon log` prints: runs completed, current streak, longest dungeon cleared,
and the payout multiplier the streak currently earns. `/dungeon log <player>` for
operators.

### Stage 2 — completion detection

Completion means **reaching the exit pad**, not leaving. `/dungeon exit` from
room three is a retreat, and a death rescue is a failure; neither pays.

`Instances.exit` currently serves all three. Give it a reason:

```java
enum ExitReason { EXIT_PAD, COMMAND, RESCUE, PURGE, DISCONNECT }
static void exit(ServerPlayer player, ExitReason reason);
```

The tick watcher's pad check (`Instances.java:479`) becomes the cross-cutting §3
version — *the block under the player is a lodestone and the player is inside
`record.layout.bounds()`* — and calls `exit(player, ExitReason.EXIT_PAD)`. Every
other call site passes its own reason; only `EXIT_PAD` pays.

**Pay each member at most once per instance.** `InstanceRecord.paid`
(declared in U2 Stage 4) is the guard. A member who steps on the pad, is paid,
gets pulled back in by a friend and steps on it again is not paid twice. Since
that set dies with the instance, and an instance requires walking a fresh dungeon
to reach a pad again, the rate limit is a human's — DESIGN.md's actual test.

### Stage 3 — the payout

```java
final class Payout {
    static void grant(ServerPlayer player, InstanceLayout layout, int streak);
}
```

```
count = (payoutBaseCount + payoutPerTier * (layout.lootTier() - 1))
      * (100 + min(streakBonusCapPercent, streakBonusPercent * (streak - 1))) / 100
```

At the defaults (`base 6`, `perTier 4`, `10%/day`, cap `100%`): a tier-1 run on
day one pays 6 emeralds; a tier-3 run on a 10-day streak pays 28. Per player, not
per party.

Delivery uses the suite's `giveOrDrop` shape, which `kamutotems.BossStone` already
demonstrates: `player.getInventory().add(stack)`, and on failure
`player.drop(stack, false)` plus a log line. Split across stacks if `count`
exceeds the item's max stack size.

Grant it **after** the teleport out completes, at the player's return point, not
at the exit pad — an item dropped at the pad because the inventory was full would
be destroyed by the teardown that is already running. This is the one ordering
detail in the whole milestone that will silently eat rewards if it is got wrong.

`payoutCommand` (config, default empty) runs an optional server command with
`%player%` and `%amount%` substituted, for operators who would rather route the
payout through `cobbleeconomy`'s own command surface. Still no Java dependency,
still no import — the config *is* the integration layer.

### Stage 4 — messaging

On completion, one line: `You escape with the loot. Run #14 - streak 3 - 18
emeralds.` The run number and streak are the whole retention mechanic; putting
them in the completion message rather than only behind `/dungeon log` is what
makes them visible to a player who never types the command.

### Stage 5 — verification

1. Enter, walk to the pad, exit: paid once, `runsCompleted` is 1, streak 1.
2. Immediately run again the same day: `runsCompleted` 2, **streak still 1**.
3. Edit the saved `lastCompletedDateKey` back one day, complete a run: streak 2.
4. Edit it back four days: streak resets to 1.
5. `/dungeon exit` from mid-dungeon: **no payout**, `runsCompleted` unchanged.
6. Die inside: **no payout**, inventory intact, ejected — the M0 death-rescue
   behaviour must be completely unchanged by this milestone.
7. Full inventory at completion: the payout drops at the player's feet **in the
   overworld**, not in the void dimension.
8. Restart the server; `/dungeon log` still reports the same numbers.
9. Two party members both step on the pad: both paid, each once.
---

## Config: every new field in one place

`PocketDungeonsConfig` currently exposes six ints and has only a `readInt`
helper. This update needs doubles, strings and booleans, so add `readDouble`,
`readString` and `readBoolean` on the same shape — missing field or invalid value
logs and falls back to the default, file untouched, per the suite's `readOrCreate`
contract.

| Field | Default | Milestone | Validation |
|---|---|---|---|
| `slotPitch` *(existing)* | 2048 | U2 | **now also `% 16 == 0`** (cross-cutting §1) |
| `pathLengthMin` | 5 | U1 | `>= 2` |
| `pathLengthMax` | 8 | U1 | `>= pathLengthMin` |
| `branchProbability` | 0.35 | U1 | `0.0 .. 1.0` |
| `loopProbability` | 0.15 | U1 | `0.0 .. 1.0` |
| `planAttemptBudget` | 16 | U2 | `>= 1` |
| `maxGridSpan` | 12 | U2 | `>= 3` |
| `clearBlocksPerTick` | 8192 | U2 | `>= 1024` |
| `baseMobsPerEncounter` | 2 | U3 | `>= 0` |
| `maxMobsPerRoom` | 8 | U3 | `>= 1` |
| `spawnerDensEnabled` | true | U3 | — |
| `ritualEnabled` | true | U4 | — |
| `ritualKeyItem` | `minecraft:echo_shard` | U4 | parses as an `Identifier` and resolves in `BuiltInRegistries.ITEM`; else disable the ritual and log |
| `ritualKeyCount` | 1 | U4 | `>= 1` |
| `payoutItem` | `minecraft:emerald` | U5 | as `ritualKeyItem` |
| `payoutBaseCount` | 6 | U5 | `>= 0` |
| `payoutPerTier` | 4 | U5 | `>= 0` |
| `streakBonusPercent` | 10 | U5 | `>= 0` |
| `streakBonusCapPercent` | 100 | U5 | `>= 0` |
| `payoutCommand` | `""` | U5 | empty disables |

The proposal's `branchProbability` default of 0.35 is a reduction from
`LayoutGraphGenerator`'s current hardcoded 0.55, and `loopProbability` 0.15 from
0.30. With a 5–8 cell path those old numbers produce a lot of spur and loop, which
was the right call when the file had no consumer and wrong now that every extra
cell is a chunk ticket and a teardown cost.

---

## File and class map

| File | New? | Milestone | Change |
|---|---|---|---|
| `LayoutGraphGenerator.java` | exists | U1 | entrance/terminal constraints, `corridor` role, weighted roles + guarantee pass, probability parameters, one new `validate` check |
| `RoomTemplateGenerator.java` | exists | U1 | 9 new templates, 4 regenerated, spawn-jigsaw helper, spawner NBT |
| `data/.../dungeon_room/*.json` | 10 new | U1 | metadata for the new rooms; edits to the four existing |
| `data/.../structure/rooms/*.nbt` | 9 new | U1 | generated output, committed |
| `PlanGeometry.java` | **new** | U2 | grid-to-world normalisation, per-cell chunk list |
| `InstanceLayout.java` | **new** | U2 | the value `Instances` carries instead of calling `StaticLayout` statics |
| `LayoutStamper.java` | **new** | U2 | walks a `DungeonPlan`, rotation-aware placement |
| `TemplateStamper.java` | exists | U2 | extract a rotation-aware `place(...)` the new stamper shares; `stamp(level, origin)` unchanged for the fallback |
| `RoomSelector.java` | exists | U2 | seeded weighted pick, `maxPerDungeon`, `minDepth`, relaxation rule, span budget |
| `DungeonPlan.java` | exists | U2 | add `Map<PlanCell,Integer> depths` |
| `LayoutPlanner.java` | exists | U2 | overload taking path bounds and probabilities from config |
| `StaticLayout.java` | exists | U2 | add `layout(BlockPos)`; everything else stays as the fallback |
| `InstanceRecord.java` | exists | U2 | `layout` field, `paid` set |
| `Instances.java` | exists | U2 + U5 | plan/stamp/fallback glue, layout-driven force-load/bounds/clear, tick-spread teardown, `ExitReason` |
| `DungeonCommands.java` | exists | U1/U2/U5 | `admin coverage`, `admin stamptest`, `admin build [seed]`, `/dungeon log` |
| `PocketDungeonsConfig.java` | exists | all | 19 new fields, three new readers, `slotPitch % 16` |
| `DifficultyProfile.java` | **new** | U3 | pure-logic curves and rosters |
| `RoomContent.java` | **new** | U3 | role dispatch, mob spawning, chest retargeting |
| `data/.../loot_table/chests/tier_1..3.json`, `bonus.json` | 3 new + 1 rewrite | U3 | generous tiered tables, boss stones |
| `RitualListener.java` | **new** | U4 | lodestone + key item |
| `DungeonLog.java` | **new** | U5 | per-player `SavedData` |
| `Payout.java` | **new** | U5 | `giveOrDrop`, streak multiplier, optional command hook |
| `DifficultyProfileTest.java` | **new** | U3 | pure-JDK, wired into `tasks.test` |

---

## Verification, end to end

Per-milestone checks are in each section above. Before calling the update
shippable, the whole thing has to survive one pass together.

**Headless, from the console, on a fresh `run/` directory.** PLAN.md's M1 notes
record a stale prior run producing a false-positive jigsaw count — always start
these from clean.

1. `gradlew build` — the four pure-JDK tests (`DoorMaskTest`, `PlanSelectorTest`,
   `PipelineProofTest`, `DifficultyProfileTest`) all pass.
2. `/dungeon admin manifest reload` → 14 loaded, 0 rejected.
   `/dungeon admin coverage` → 0 holes.
   `/dungeon admin plansurvey 200` → ≥95% first-attempt.
3. `/dungeon admin stamptest` → all four rotations correct.
4. `/dungeon admin build 4242` → geometry intact, **zero `minecraft:jigsaw`
   blocks anywhere in the volume**, every open edge open on both sides, every
   closed edge solid on both sides, chest loot table matches the tier the path
   length implies, mob counts match `DifficultyProfile`.
5. `/dungeon admin purge <slot>` → every block and non-player entity gone, no
   item drops, slot returned, force-load tickets released. Confirm by building
   again and landing on the same slot.
6. Break the room library on purpose (rename one floor room's JSON), then
   `/dungeon` → the static four-room fallback builds, the player is told, and the
   log carries the planner's reason.
7. `/loot give` each tier 20 times; boss stones carry the right `custom_data`.
8. Restart mid-run: the join handler's orphan recovery still fires and sends the
   player home (unchanged from M0, but the layout is bigger now — confirm the
   bounds check still catches them).

**Client walkthrough**, added to `CLIENT_TEST_CHECKLIST.md`:

- Enter, clear a procedural dungeon end to end, exit on the pad, get paid.
- Confirm the entrance faces into the dungeon at every rotation (four runs).
- Die inside — ejected, inventory intact, **no payout**.
- Ritual: lodestone + echo shard starts a run; sigil in hand does not.
- Party of two, both paid, instance survives one member leaving early.
- `/dungeon log` after several runs across two real days.
- Walk every doorway in a `hall_cross` and `flooded_tee` — the lane rule holds and
  nothing is authored where a player has to jump over it.

---

## Deliberately not in this update

Recorded so they are decisions, not omissions.

- **Gate/key edges.** `DungeonPlan.Gate` scaffolding and the BFS-with-keys
  validator stay; nothing is enforced in-world. No lock block is authored, and a
  key system adds a stuck-player failure mode for a feature with no content behind
  it yet.
- **In-dungeon kamutotems boss fights.** `Boss.spawn`/`BossHost.track` would be a
  hard Java dependency; the boss bar and sigil-refund logic do not survive
  `Instances` purge/teardown; and kamutotems' open `DEATH_PROTECTION` risk
  conflicts directly with non-lethal death rescue. Boss stones as loot deliver the
  "dungeons feed my boss fights" loop now, at zero coupling. Revisit once
  kamutotems is play-verified.
- **Multi-cell footprints.** `DungeonRoomMeta.footprint` is parsed and every room
  ships `[1,1]`. Nothing in the planner or stamper handles anything else, and
  saying so is cheaper than half-supporting it.
- **Full instance persistence (M5).** Still memory-only; the join-handler orphan
  recovery covers restarts and runs are short. U5's stats `SavedData` is the
  slice that earns its keep.
- **Leaderboards, unlock trees, key items.** The streak is the entire retention
  system.
- **Mid-run difficulty rescaling for late joiners.** See U3 Stage 5.
- **Loops in practice.** They are still generated, and after U1's junction and
  cross templates they will now actually *build* rather than failing selection —
  a behaviour change worth watching in `plansurvey`'s numbers, since a loop-heavy
  layout is more rooms and therefore more chunks and more teardown than the path
  length alone suggests. `maxGridSpan` is the backstop.

---

## Suggested agent split

The chain U1 → U2 → U3 is sequential, but two of the five milestones are not.

- **Agent A: U1**, then **U2**. Owns `LayoutGraphGenerator`,
  `RoomTemplateGenerator`, the room JSON, `PlanGeometry`, `InstanceLayout`,
  `LayoutStamper`, `RoomSelector`, and — alone — `Instances.java`.
- **Agent B: U4**, from day one, then **U5** once U2 lands. Owns
  `RitualListener`, `DungeonLog`, `Payout`. Its only touch on `Instances` is the
  `ExitReason` enum and the `enter` return value, both of which should be landed
  by Agent A as part of U2 so B never edits that file.
- **U3** goes to whoever is free after U2; it is self-contained in
  `DifficultyProfile`, `RoomContent` and the loot JSON.

`PocketDungeonsConfig` is touched by everyone. Land **all 19 fields at once, up
front**, before either agent starts — they are pure data with defaults and adding
them early costs nothing, whereas three agents editing one file's `apply` and
`defaultsJson` methods in parallel is a guaranteed merge conflict over something
that has no design content in it.
