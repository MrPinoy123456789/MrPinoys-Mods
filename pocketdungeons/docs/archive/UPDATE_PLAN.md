> Archived 2026-08-25 (M9 C5): the update it planned (U1-U8) shipped;
> superseded by the milestone plans under `plans/` and their status in
> `PROGRESS.md`.

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

**U6 was appended after U1–U3 shipped** and is not part of that dependency
graph. It reworks the encounter and loot loop onto vanilla's trial chambers —
trial spawners, vaults, trial keys and the ominous state — and in doing so
supersedes most of U3's difficulty machinery. It depends on U1 and U2 only, and
touches U4 and U5 at one point each. Read it as a second update sharing this
document, not as the sixth step of the first one.

**U7 is the meta on top of U6** — a Mythic+ keystone that carries a level, is
spent to start a run, upgrades against a clock and depletes on failure. It adds
no dungeon content: U1's library, U2's planner and U6's trials are all unchanged
by it, and the only new server state is a pending keystone for a player who was
offline when theirs came back. `U6 → U7` is the second half of that second
update, and U7 is the last thing this document plans.

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

## U3 — tiered loot and scaled mobs ✅ *shipped*

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

**Scope note, confirmed by reading `kamutotems.BossStone`/`BOSS_EGG_SPEC.md`
directly:** pocketdungeons' job ends at dropping this item. Right-clicking it
in kamutotems exchanges it for a rolled sigil (a spawn-egg item per the boss
egg spec), which the player then uses or dispenses *elsewhere* to actually
summon the boss mob. **No kamu boss ever spawns inside the dungeon itself,
by design** -- the flavor text ("Grip it and a trial finds you") describes a
portable trigger, not an in-room encounter. If a future milestone wants an
actual boss fight inside a dungeon room, that is new scope, not a gap in
U3's implementation of the existing plan.

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

### Shipped: what was built, verified, and where it deviates

**Verified against the real 26.2 jar via `javap` before writing any loot JSON**
(the plan flagged this as the risky part): `SetCustomDataFunction` takes a raw
`CompoundTag` (SNBT string in JSON, as written), `SetNameFunction` has a
`target` field of type `SetNameFunction.Target` (`CUSTOM_NAME`/`ITEM_NAME`),
`SetLoreFunction` has a `mode` field of type `ListOperation`, `SetItemCountFunction`
takes a `NumberProvider` (plain `{"min":x,"max":y}` JSON), `EnchantWithLevelsFunction`
takes `levels` + optional `options` (omitted here, matching "treasure allowed").
`EntityType.spawn(ServerLevel, BlockPos, EntitySpawnReason)` and
`Mob.setPersistenceRequired()` both exist with the signatures the plan assumed;
`EntitySpawnReason.TRIGGERED` was picked for scripted dungeon spawns (not
`SPAWNER`, `NATURAL`, or `COMMAND`). All confirmed correct — nothing needed a
retry after the fact.

**Live-verified against a headless dev server**, not just `javap`-checked:

| Check | Result |
|---|---|
| `DifficultyProfileTest` | pure-JDK, wired into `tasks.test`, passes (tier boundaries, `mobCount` clamp at both ends, `effectiveTier` cap, non-empty positive-weight rosters, `mobRoster` throws outside 1–3) |
| `loot insert` into a scratch chest, x1 (tier 1) / x20 (tier 2) / x20 (tier 3) | tier 1: guaranteed diamond present. Tier 2: Boss Stone I rolled with correct `custom_data`/`custom_name`/`lore`. Tier 3: **both** Boss Stone II and Boss Stone III rolled, plus `diamond_block`, `netherite_scrap`, `enchanted_golden_apple`, `enchanted_book`, and bonus.json's items (via the `minecraft:loot_table` reference) — confirming the nested-table reference resolves correctly |
| `admin build` (seed 0, path 7 → tier 2) + `admin cellreport` (new dev-only command, see below) | every `loot` cell's chest carries `ResourceKey[... / pocketdungeons:chests/tier_2]` and a large non-zero seed; every `corridor` cell has 0 chests and 0 mobs; every `encounter` cell had **exactly 4 mobs** — matching `mobCount(7,1) = 2 + 7/3 + 0 = 4` exactly, constant across cells as designed |
| `admin purge` | slot released cleanly (`adminLayout` returns null immediately after); the existing entity sweep in `finishClear` is unchanged by U3 and already covers up to 8 mobs/cell |
| server log, whole run | zero exceptions, zero unexpected warnings (one pre-existing stale-config warning about `clearBlocksPerTick` from an old `run/` config file, unrelated to this milestone) |

**A real bug was found and fixed by the live check, not the code review:** the
first pass at `RoomContent.spawnMobs` jittered overflow mobs (once a room's
authored spawn points ran out) by ±1 block and *skipped* the mob if the
jittered position wasn't air. Small rooms with few spawn jigsaws near a wall
(e.g. `encounter_zombie`'s 2 authored points against a `mobCount` of 4) missed
this often enough that a live `cellreport` showed encounter cells with 2–3
mobs instead of 4 — a silent, systematic undercount of the tuned difficulty
curve. Fixed by falling back to the exact, always-air jigsaw position when
jitter misses (`RoomContent.jitterOrFallBack`), re-verified live: every
encounter cell in the next build reported exactly 4. **Lesson consistent with
U2's finding:** a live check on a state-machine-adjacent piece of logic (here,
"what happens when demand exceeds supply of spawn points") caught something a
diff review or the pure-logic test could not, because `DifficultyProfileTest`
only exercises `DifficultyProfile` in isolation and has no notion of a room's
authored spawn-point count.

**Added beyond the plan's text, both judged in-scope by Stage 5's own
wording:** `/dungeon party <player>` (`Instances.party`) pre-registers a
companion before entry so the difficulty curve is computed from the real party
size at stamp time, exactly as Stage 5 describes; `invite`/`join` are
unchanged and still only work from inside an instance. `/dungeon admin
cellreport <slot>` (dev-only, alongside `stamptest`/`gentemplates`) is a new
introspection command: without a client attached there was no other way to
confirm per-cell chest/mob role dispatch or read a chest's loot-table
NBT back out, so this is what Stage 6 items 3–5 above actually ran against.

**A second real bug, found by the user in an actual client after this section
was first written, not by any of the above:** `encounter`/`corridor` cells
showed loose item entities on the floor where their (removed) placeholder
chest used to be -- the chest itself was gone, but its would-be loot was
lying around. Root cause, confirmed with `javap` bytecode inspection of
`LevelChunk.setBlockState` and `BlockEntity.preRemoveSideEffects`:
`Block.UPDATE_SUPPRESS_DROPS` (32) only suppresses a removed block's *own*
item drop (e.g. the "chest" item you'd get from breaking one by hand); a
container's *contents* are dropped separately by
`BlockEntity.preRemoveSideEffects` calling `Containers.dropContents`, gated
by a different flag entirely, `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` (256),
which neither `RoomContent.FLAGS` nor `RoomBuilder.STAMP_FLAGS` ever
included. Worse, `Containers.dropContents` iterates the container via
`getItem()`, and `RandomizableContainerBlockEntity.getItem()` **lazily
unpacks its pending loot table on first access** -- so breaking a
never-opened placeholder chest (every `encounter`/`corridor` chest, by
construction, since nobody ever gets the chance to open one) generated its
loot right there and scattered it as the chest vanished. `RoomBuilder.set`
(used by the whole-instance teardown clear in `Instances.PendingClear`) had
the identical bug, silently wasting a loot-table roll into short-lived item
entities on every purge -- harmless in the end state since the entity sweep
in `finishClear` discards them anyway, but fixed for the same reason. Both
now OR in `Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`. Re-verified live: an
`execute store result` item-entity census across a freshly built instance's
full footprint (which necessarily includes several encounter/corridor cells)
returned zero matches. **Lesson:** `UPDATE_SUPPRESS_DROPS`'s name is a false
friend for anything that carries an inventory -- it does not mean "nothing
drops," it means "the block itself doesn't drop." This is exactly the kind
of thing that survives a headless-server smoke test (no chest ever gets
*opened* by console commands, so the lazy-unpack path never fires there) and
only shows up once a real player actually walks through an encounter room --
worth remembering for U4/U5 verification too.

**Two more real bugs, found by the user actually playing a build, not by any
verification pass above:**

1. **Enchanted books came out with zero enchantments, every time.** Root
   cause, confirmed with `javap` bytecode inspection of
   `EnchantmentHelper.selectEnchantment`: it reads the stack's
   `DataComponents.ENCHANTABLE` component and returns an empty candidate list
   immediately if it's null. `minecraft:enchanted_book` does not carry that
   component -- only `minecraft:book` does, since a plain book is what you'd
   put in an enchanting table. The vanilla pattern (and what
   `EnchantmentHelper.enchantItem` itself is written for) is to loot-roll a
   **`minecraft:book`** and let `enchant_with_levels` convert it to an
   enchanted book with real stored enchantments as part of its own logic --
   there's a literal `stack.is(Items.BOOK) -> new ItemStack(ENCHANTED_BOOK)`
   branch for exactly this. `tier_2.json`/`tier_3.json` used
   `minecraft:enchanted_book` directly, skipping that conversion entirely.
   Fixed by changing both entries' `name` to `minecraft:book`; re-verified
   live, 10/10 fresh rolls now carry real `stored_enchantments`. **This is
   exactly the kind of function-shape mistake the plan's own Stage 3 flagged
   as needing verification** -- the `javap` pass on `EnchantWithLevelsFunction`
   confirmed its top-level shape was right, but didn't catch that the
   *input item* was wrong, since that's a runtime data-flow question, not a
   codec-shape one. A single actual roll inspected for its enchantment
   component (not just its presence in the chest) would have caught this
   during U3's own verification pass -- noted for next time.
2. **Chests felt roughly 4x too generous.** Real cause, once single (not
   stress-test) rolls were compared side by side: `bonus.json`'s pool was
   wired into `tier_2`/`tier_3` as an **unconditional** extra pool -- it fires
   on every single chest, solo or party, not just for a real party. This was
   already a documented, deliberate scope cut (`chestRolls()` exists as
   tested pure logic but was never wired to gate it), and it turned out to
   be a bigger generosity add than intended: on top of `tier_2`/`tier_3`'s
   own four pools, every chest got a guaranteed extra 1-2 rolls from a pool
   whose items (diamond, emerald, iron, gold, experience bottles) already
   overlap the base pools, compounding rather than adding variety. Fixed by
   removing the `bonus.json` reference from both tables' pool lists --
   `bonus.json` itself is untouched and still a reasonable file for a future
   properly party-gated bonus (still U5-adjacent territory, same as before).
   Re-verified live: single-roll tier_2/tier_3 chests now land at 3-5
   distinct stacks, matching Stage 4's original narrative much more closely.

**Deviation, deliberately scoped down:** `DifficultyProfile.chestRolls()`
exists and is unit-tested (`partySize - 1`), and `bonus.json` is wired into
`tier_2`/`tier_3` via a `minecraft:loot_table` reference exactly as the plan's
Stage 3 describes -- but `chestRolls()`'s int value is **not** wired into
`RoomContent` to dynamically scale how many times `bonus.json` rolls per
party size. Datapack loot tables can't read live party size, and the only way
to make the roll count dynamic would be to stop lazily retargeting the chest
(`setLootTable` + `setLootTableSeed`, which Stage 2 requires for the
reproducible-seed contract) and instead pre-generate the chest's contents at
stamp time. That trade-off wasn't in the plan's text and Stage 6's
verification checklist never exercises `chestRolls()` at all, so it was left
as pure logic for a future milestone (most likely U5's payout, which already
pays per-member individually) to consume instead of building undocumented
machinery for it now.

---

## U4 — the lodestone ritual ✅ *shipped*

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

### Shipped: what was built, verified, and where it deviates

**Verified against the real 26.2 jar via `javap` before writing code:**
`InteractionResult` is an interface with `SUCCESS`, `SUCCESS_SERVER`, `CONSUME`,
`FAIL`, `PASS` — server-side success is `SUCCESS_SERVER`, which is what the rest
of the suite already returns from `UseBlockCallback`. `CustomData` has both
`isEmpty()` and `copyTag()`, so the strict "no custom_data at all" test costs one
call and no tag copy. `ItemStack.is(Item)` is now inherited from
`TypedInstance.is(T)` rather than declared on `ItemStack` — it still compiles, but
grepping `javap ItemStack` for it finds nothing, which is worth knowing before
concluding it was removed. `Level.playSound(null, BlockPos, SoundEvent,
SoundSource, float, float)` is the suite's existing null-first-arg shape and
broadcasts to everyone tracking the position.

**The one shape that mattered most, checked by reading bytecode rather than
signatures:** `ServerPlayer.teleport(TeleportTransition)` is `aload_0 … areturn`
throughout — the same `ServerPlayer` instance survives a cross-dimension teleport
(`removePlayerImmediately` + `unsetRemoved` + `addDuringTeleport`, no
recreation). That is what makes "call `Instances.enter`, then `shrink` the stack
we already had a reference to" correct rather than a stale-object bug. Had the
player been recreated, the key would have been taken from a dead inventory.

**Deviations from the plan, all deliberate:**

- **`isPlainKey` ships the strict version**, as Stage 2 itself recommended: any
  `custom_data` at all disqualifies a stack, not just a `kamutotems` compound.
- **`Instances.enter` now returns `boolean`.** Stage 1 offered this or a
  `hasInstance` re-check; the boolean is what shipped, and it is a four-line
  change (signature, four early `return false`, one trailing `return true`).
- **A sneak guard was added** that the plan does not mention. Right-clicking
  while sneaking passes, matching `kamutotems.Station`: sneaking is how vanilla
  says "act on what I am holding, not on this block", and it is the escape hatch
  for an operator who points `ritualKeyItem` at something placeable.
- **The sound plays before entry, not after.** The plan implies after; after is
  wrong, because by then the player has been teleported to another dimension and
  is the one person who cannot hear it. Only the *key consumption* has to wait for
  success.
- **`ritualKeyItem` is resolved once at `SERVER_STARTED` as well as lazily**, so a
  typo is an error line at boot rather than something a player discovers by
  right-clicking a lodestone and having nothing happen.
- **Item-id resolution was extracted into `ConfiguredItem`** (new file), shared by
  `ritualKeyItem` and U5's `payoutItem`. Both need identical parse-cache-log-once
  behaviour and the same `DefaultedRegistry.getValue`-answers-with-air caveat.

**Verified live on a headless dev server:**

| Check | Result |
|---|---|
| `ritualKeyItem: "minecraft:not_a_real_item"` | logs `ritualKeyItem 'minecraft:not_a_real_item' is not a known item; the lodestone ritual is disabled until it is fixed. /dungeon still works.` at startup, before `Done (`; server boots normally and `/dungeon` is unaffected |
| `ritualKeyItem: "minecraft:echo_shard"` (default) | resolves silently; zero ritual-related log lines across a full boot |
| boss stone identity, re-checked | `chests/tier_2.json` mints boss stones as `minecraft:echo_shard` + `set_custom_data {kamutotems:{boss_stone:N}}` — so the shipped `isPlainKey` refuses exactly the item the plan was worried about |

**What could not be verified here, and why.** Every remaining Stage 4 check is a
right-click, and nothing in a console session ever right-clicks a block. Items
1–6 of Stage 4 are therefore in `CLIENT_TEST_CHECKLIST.md`, not verified. This is
the same blind spot that hid U3's container-drop bug; it is named rather than
papered over.

---

## U5 — the dungeon log and completion payout ✅ *shipped*

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

### Shipped: what was built, verified, and where it deviates

**Verified against the real 26.2 jar via `javap`:** `SavedDataType` is a record
of `(Identifier, Supplier<T>, Codec<T>, DataFixTypes)`, `SavedDataStorage`
exposes `computeIfAbsent(SavedDataType<T>)`, and `SavedData` has
`setDirty()`/`isDirty()` — the `WondrousState` shape transfers unchanged.
`Commands.performPrefixedCommand(CommandSourceStack, String)` and
`MinecraftServer.createCommandSourceStack()` are what `payoutCommand` runs
through.

**A real bug caught by reading `Inventory.add`'s bytecode rather than trusting
its name.** `add(ItemStack)` loops `addResource` until the stack is empty *or it
stops making progress*, then returns "did I move **any** of this" — and it
mutates the passed stack down to whatever is left over. So a payout that only
half fits returns `true` and leaves the remainder in the stack. The first draft
used the suite's usual `if (!player.getInventory().add(stack))` idiom, which
would have **silently destroyed** that remainder — and a nearly-full inventory at
the end of a run is precisely when it happens, which is Stage 5's own check 7.
Shipped code tests `stack.isEmpty()` afterwards instead. Worth noting that
`if (!add(stack))` appears in at least eight other files across this suite
(`bounties/Rewards`, `chatdonkey/Rewards`, `cobbleeconomy/ItemBank`,
`kamutotems/AssignedQuestHost`, `ballot/BallotCommands`, …) with the same hole.

**Deviations from the plan, all deliberate:**

- **`ExitReason` has two constants, not five.** The plan assumed `Instances.exit`
  served the rescue, purge and disconnect paths too; it does not — `rescue`,
  `purge` and `dropMember` each eject directly and never route through `exit`.
  Shipping `RESCUE`/`PURGE`/`DISCONNECT` would have documented a dispatch that
  does not exist. Only `EXIT_PAD` and `COMMAND` reach `exit`, and only `EXIT_PAD`
  pays. Death rescue therefore cannot pay by construction, not by a check.
- **The arithmetic lives in `PayoutMath`** (new file, no Minecraft imports),
  covering both the streak transition and the payout count, with
  `PayoutMathTest` wired into `tasks.test`. Same split as `DifficultyProfile`,
  and for the same reason: it is the part that is easy to get subtly wrong.
- **A tier-3 run on a 10-day streak pays 26, not the 28 Stage 3's prose claims.**
  Stage 3's *formula* is `streakBonusPercent * (streak - 1)`, which makes day ten
  a 90% bonus: `(6 + 4*2) * 1.90 = 26.6`, floored to 26. Day *eleven* is where the
  100% cap is reached and 28 lands. The formula is the normative half and the
  prose is the half that drifted; both numbers are asserted in `PayoutMathTest`
  so the discrepancy cannot quietly move again.
- **`recordCompletion` has a date-injectable overload**, driven by two new
  dev-only commands, `/dungeon admin log record <name> <pathLength> <date>` and
  `/dungeon admin log show <name>`. The streak rule's only honest test otherwise
  spans several real days; this walks a whole history from a console in one
  session, including across a restart.
- **`payoutCommand` output is not suppressed.** A broken operator command should
  be visible to whoever configured it.
- **`lastCompletedDateKey` is stored as `""` rather than null** for "never", so
  the codec needs no optional-string special case; blank is treated as absent
  everywhere it is read.

**Verified live on a headless dev server:**

| Check | Result |
|---|---|
| Stage 5 items 1–4, driven through `admin log record` | first completion → runs 1, streak 1. Next day → runs 2, streak 2. Same day again → runs 3, **streak still 2**, `bestPathLength` unchanged by a shorter run. Six-day gap → runs 4, streak **reset to 1**, `bestPathLength` raised to 8 |
| clock-went-backwards (`2026-08-21` then `2026-08-20`) | treated as a gap: streak 1, not an increment |
| unparseable date key (`not-a-date`) | streak 1, entry still written and readable — no exception, no lost file |
| Stage 5 item 8, restart | server stopped and restarted: alice/bob/carol all report identical numbers, and a further completion the next day continues the streak (1 → 2) rather than restarting |
| where the file actually lands | `world/dimensions/minecraft/overworld/data/pocketdungeons/dungeon_log.dat` — **not** `world/data/`, which is where 26.2 keeps the vanilla server-level stores. Worth recording: "no file in `world/data`" looks exactly like a save that silently failed |
| `/dungeon log` with no history | "… has not finished a dungeon yet.", return code 0 |
| U1–U3 regression, same build | manifest 14/0, coverage 53/53 with 0 holes, `plansurvey 200` → 200/200 first attempt, `admin build 4242` → 7 rooms, path 5, tier 1, bounds correct |
| `gradlew build` | all five pure-JDK tests pass, including the new `PayoutMathTest` |

**Cross-cutting interactions traced by hand, not just diffed:**

- **Payout ordering.** `exit` ejects first and pays second, so `Payout.grant`'s
  drop-what-does-not-fit lands at the player's return point in the overworld,
  never on the exit pad inside a dungeon that `closeIfEmpty` → `purge` →
  `teardown` is about to clear. This is the plan's own "will silently eat rewards
  if it is got wrong" detail, and the bytecode check above is what makes the
  ordering provably safe rather than probably safe.
- **The tick watcher's iteration.** `onTick` copies both `bySlot.values()` and
  `record.members.keySet()` before iterating, and `purge` is only reachable once
  `members` is empty — so a two-member party both standing on the pad pays A,
  leaves B a live member, then pays B and purges on B's own iteration. Both paid,
  each once, no concurrent-modification hazard, no double teardown.
- **The `paid` set.** Lives on `InstanceRecord` and dies with the instance, so a
  member who is paid, is pulled back in by a friend, and walks the pad again is
  ejected normally with the plain "you leave the dungeon behind" message and no
  second payout — `pay` returns false and the completion message is skipped.
- **The exit pad is a lodestone, and so is U4's ritual trigger.** `RitualListener`
  refuses when `Instances.hasInstance` is true *or* the player is in
  `pocketdungeons:void`, so standing on the pad and right-clicking it cannot cost
  a key. The dimension half of that test covers the orphan-recovery case, where a
  player is in the void with no live record.

**What could not be verified here, and why.** Every remaining Stage 5 check needs
a player standing in a dungeon: payout on a real pad exit, no payout on
`/dungeon exit`, no payout on death, a full inventory dropping at the return
point, and two party members each paid once. Those are in
`CLIENT_TEST_CHECKLIST.md`. The logic behind each was traced above, and the
`Inventory.add` bug the trace found is the argument for doing that tracing at all.

---

## U6 — the trial rework: vanilla mechanics instead of ours ✅ *shipped*

**Goal:** stop re-implementing what 1.21's trial chambers already do well. Pocket
Dungeons keeps the parts vanilla has no answer for — the private dimension, the
slot grid, the layout planner, the streak and the payout — and hands the
*encounter and reward loop* over to trial spawners, vaults and the ominous
system. A run stops being "walk in, kill four zombies, open a free chest" and
becomes a pocket trial chamber: fight the spawner, take the key it ejects, spend
it on a vault, and decide before you go in whether you want the ominous version.

**Blocked by:** U1 and U2 (templates and the per-cell stamp hook). **Supersedes
most of U3** — see the division-of-labour table below — and leaves U4 and U5
untouched apart from one payout multiplier and one ritual branch.

**Assumption stated up front:** "trials" here means vanilla's trial chambers
(trial spawner, vault, trial key, ominous bottle / Trial Omen), not a separate
mod in the suite. Everything below is written against those blocks.

### Stage 1 — what the mod stops doing

The point of this milestone is deletion as much as addition. Settle the division
once so nobody re-adds a lever vanilla already owns:

| Concern | U3's answer | U6's answer |
|---|---|---|
| How many mobs in an encounter | `DifficultyProfile.mobCount(depth)`, party size folded in | trial spawner `total_mobs` + `total_mobs_added_per_player`; vanilla counts the players itself |
| Late joiners rescaling mid-run | punted (U3 Stage 5) | free — the spawner re-detects players every activation |
| Which mobs | `mobRoster(tier)` picked in Java, spawned by `RoomContent` | the same rosters, moved into `data/pocketdungeons/trial_spawner/tier_N/*.json` |
| Mobs despawning behind you | `setPersistenceRequired()` on every spawn | the spawner tracks its own mobs and will not over-spawn |
| Pacing inside a room | none — everything spawns at once | `simultaneous_mobs`, `ticks_between_spawn` |
| Loot delivery | one shared chest, first-come | a vault per loot room, **per player, once each** |
| Party generosity | `chestRolls() = partySize - 1` bonus rolls on a shared chest | deleted; the vault pays every member the full table |
| Depth escalation | `effectiveTier(depth)` bumps the roster one tier | the deep third of the run is stamped **ominous** |
| Boss stones | tier-2/3 chest pools | ominous vault only |

`DifficultyProfile` survives and stays pure-logic, but shrinks to almost nothing:
it is still the one place a path length becomes a tier, and the tier is now just
an index into six data files (Stage 3). `RoomContent` gets *smaller* too — role
dispatch and block retargeting remain, mob spawning goes away entirely.

**`chestRolls()` and the `partySize` term in `mobCount` are deleted, not
deprecated.** Leaving them in place while vanilla also scales by player count
double-counts the party in both directions at once, and that is exactly the kind
of quiet compounding that makes a tuning complaint impossible to diagnose.

### Stage 2 — authoring: spawners and vaults live in the template

Cross-cutting §3 applies unchanged: anything the room owns is authored into the
`.nbt`, so `placeInWorld` transforms it for free. Two new authored features in
`RoomTemplateGenerator`, alongside the existing door and spawn jigsaws:

- **`minecraft:trial_spawner`** at the cell's encounter anchor, carrying
  `normal_config` / `ominous_config` as **registry ids**, not inline blobs (see
  the note on vanilla's own pieces below).
- **`minecraft:vault`** at the loot anchor, replacing the authored chest in
  floor rooms that carry the `loot` role.

**Do not hand-author either block's NBT — stamp vanilla's own micro-pieces.**
Measured against the 26.2 server jar, the trial chamber structure ships 191
pieces, and while the structural ones are far too big for a 16×7×16 cell
(chambers 11×12×11, corridors 19×20×19, intersections up to 23 wide and **37
tall**), the *atomic* ones fit with room to spare:

| Vanilla piece family | Size | Count | Use here |
|---|---|---|---|
| `trial_chambers/spawner/**` | 3×2×3 (some 3×1×3) | 19 | the encounter anchor |
| `trial_chambers/reward/vault` | 3×4×3 | 1 | the loot anchor |
| `trial_chambers/reward/ominous_vault` | 3×4×3 | 1 | the ominous loot anchor |
| `trial_chambers/chests/**` | 3×2×3, 3×1×3 | 2 | supply chest, if wanted |
| `trial_chambers/decor/**` | 1×2×1, 2×2×1 | 29 | cell dressing |

These are loaded from the vanilla data pack at runtime through the same
`StructureTemplateManager` the mod already uses for its own rooms — **nothing is
copied into this mod's resources**, and the block entities arrive pre-configured
and known-good rather than hand-typed. `TemplateStamper.place(...)` already takes
a rotation and an origin, so placing `minecraft:trial_chambers/spawner/melee/
zombie` at a spawn jigsaw's transformed position is a call, not a feature.

Each of those pieces carries a `minecraft:jigsaw` block of its own (named
`minecraft:spawner`, `minecraft:reward_connector`, `minecraft:ominous_vault`)
with `final_state = minecraft:air`. `JigsawFallback.replaceRemaining` filters by
namespace after U1's widening, so it will **not** clear a `minecraft:`-namespaced
jigsaw. Widen it once more to "any jigsaw left inside the instance bounds", or
the M1 regression check — zero `minecraft:jigsaw` blocks anywhere in a built
instance — starts failing on the borrowed pieces. This is the one code change the
borrowing costs.

The existing `pocketdungeons:spawn` jigsaws stay. They are no longer mob spawn
points — the trial spawner has its own `spawn_range` — but they remain the
rotation-safe way to find *where the spawner should be*: author the trial
spawner at one of them, and `getJigsaws(placementPos, rotation)` keeps giving
rotation-correct coordinates with no new transform math. Nothing about
cross-cutting §7 changes.

Field names, for the fields the stamper still writes — verified against the 26.2
server jar's own pieces, not from memory:

```
trial_spawner: required_player_range, target_cooldown_length,
               normal_config / ominous_config {
                 spawn_range, total_mobs, simultaneous_mobs,
                 total_mobs_added_per_player, simultaneous_mobs_added_per_player,
                 ticks_between_spawn, spawn_potentials[], loot_tables_to_eject[],
                 items_to_drop_when_ominous }
       blockstate: trial_spawner_state, ominous

vault:         config { activation_range, deactivation_range,
                        key_item, loot_table, override_loot_table_to_display },
               shared_data { display_item }
       blockstate: vault_state, ominous, facing
```

`/dungeon admin stamptest` is the harness for this and needs one new assertion:
after a rotated stamp, the trial spawner and the vault are inside the cell, on
the floor, and their block entities still carry their configs. A block entity
that survives rotation but loses its NBT is the failure mode worth pinning, and
it is invisible to the geometry checks stamptest already runs.

### Stage 3 — `TrialContent`, written at stamp time

New file, called from `LayoutStamper` per cell where `RoomContent` is called
today; `RoomContent` keeps role dispatch and delegates.

```java
static void applyEncounter(ServerLevel level, BlockPos cellOrigin, int depth,
                           DifficultyProfile profile, boolean ominousRun, long seed);
static void applyLoot(ServerLevel level, BlockPos cellOrigin, int depth,
                      DifficultyProfile profile, boolean ominousRun, long seed);
```

Both find their block entity the cheap way — a cell is a chunk (cross-cutting
§1), so `level.getChunk(cellOrigin).getBlockEntities()` is a map lookup — and
write:

- **encounter:** `normal_config` and `ominous_config` to
  `pocketdungeons:tier_N/normal` and `pocketdungeons:tier_N/ominous`, where
  `tier = profile.lootTier()`. **`minecraft:trial_spawner` is a data-driven
  registry** — vanilla's own zombie piece stores nothing but the string
  `minecraft:trial_chamber/melee/zombie/normal` — so the three tiers ship as six
  JSON files under `data/pocketdungeons/trial_spawner/`, U3's rosters become
  their `spawn_potentials`, and the Java side sets two strings. `DifficultyProfile`
  therefore does *not* grow a `spawnerConfig(tier)`; it grows nothing at all, and
  keeps only `lootTier()`. Set the `ominous` blockstate per the rule below.
- **loot:** `config.loot_table` to `pocketdungeons:chests/tier_N` — **U3's loot
  JSON is reused unchanged**, which is most of why this milestone is cheap —
  `config.key_item` to `vaultKeyItem`, and the `ominous` blockstate per the same
  rule. Ominous vaults point at `pocketdungeons:chests/tier_N_ominous`, the only
  new tables (Stage 5).

**Ominous by depth**, replacing `effectiveTier`:

```
ominous(depth) = ominousRun || depth >= (pathLength * 2) / 3
```

The far third of a run is ominous whether or not the player paid for it, which
keeps the ramp U3 wanted, and an ominous *run* makes the whole thing ominous
from the entrance.

**The one vanilla mechanism not to trust blind.** In vanilla, Bad Omen becomes
Trial Omen on entering a trial chamber *structure*, and there is no such
structure in `pocketdungeons:void` — that conversion will not fire here. So:

1. The mod grants `minecraft:trial_omen` directly (Stage 5), never Bad Omen.
2. **Independently**, the stamper writes the `ominous` blockstate itself, so an
   ominous room is ominous because the mod stamped it that way, not because a
   detection path inside vanilla happened to notice a nearby player's effect.

(2) is the load-bearing one. Do it in that order and the feature does not rest on
an assumption about how `TrialSpawner` decides to go ominous — the kind of
internal that moves between versions. **Spike (2) in-world before writing any
other part of this milestone**: if a stamped `ominous` blockstate does not stick,
the whole ominous half needs a different mechanism, and it is much better to
learn that in an afternoon than after fourteen rooms have been regenerated.

**Kill switch, same shape as `spawnerDensEnabled`.** With `trialsEnabled: false`,
`TrialContent` replaces the authored vault with a plain chest and the trial
spawner with a classic `minecraft:spawner` (or with `mossy_cobblestone`, if
`spawnerDensEnabled` is also false), and `RoomContent` falls back to its U3 spawn
path. One flag rolls the whole milestone back without re-authoring a single
template, and it is the only way to keep U3's behaviour testable side by side
while tuning.

### Stage 4 — the key loop

This is the actual design change, and it is worth naming: **loot stops being
free.** Today a loot room is a chest you walk up to. After U6:

1. An encounter room's trial spawner ejects a **trial key** on completion
   (`loot_tables_to_eject`: one key plus a small consumables pool).
2. A loot room's vault wants that key.
3. Every party member holding one gets the full table, once each. Nobody races
   anybody to a chest.

**Ominous is a second, separate key.** Vanilla's `reward/ominous_vault` piece
asks for `minecraft:ominous_trial_key`, not `minecraft:trial_key`, and its
ominous spawner configs eject `minecraft:spawners/ominous/trial_chamber/key`
rather than the plain one. So `vaultKeyItem` is really two fields —
`vaultKeyItem` and `ominousVaultKeyItem` — and an ominous cell must eject the
matching one, or the run generates vaults nobody can open. Since the ominous rule
is per *cell depth*, a run can legitimately contain both kinds at once: the near
two-thirds pay plain keys into plain vaults, the deep third pays ominous keys
into ominous vaults, and `loot <= encounter` has to hold **per kind**, not just
overall.

Two consequences to design around, and the planner already has the levers for
both:

- **The generator must not produce a run with more loot cells than encounter
  cells.** Otherwise a player reaches a vault with no key and reads it as a bug.
  Add `loot <= encounter` as a check in `LayoutGraphGenerator.validate`,
  alongside the guarantee pass U1 added, and let the role-weighting pass retry
  rather than shipping a run that cannot be fully opened.
- **Keys carried out of a run are a faucet.** A player who clears three
  encounters and opens one vault leaves with two trial keys, and vanilla trial
  chambers will happily take them. That is fine and arguably good — a
  cross-content faucet is DESIGN.md §5's whole posture — but decide it rather
  than discover it. `vaultKeyItem` is config; an operator who wants the loop
  sealed points it at something mod-flavoured, and `TrialContent` writes that one
  field into both the ejection table and the vault config, so the two can never
  drift.

### Stage 5 — the ominous run

The opt-in stakes lever, and the only place U4 and U5 are touched.

- **Ritual branch (U4).** Right-clicking the lodestone with an ominous bottle
  instead of the key item starts an **ominous run**: `Instances.enter` takes a
  `boolean ominous`, the player is granted `minecraft:trial_omen` on arrival, and
  every cell stamps ominous. The bottle is consumed on the same "only after
  `enter` succeeded" rule U4 Stage 1 establishes, and the same
  `custom_data`-must-be-empty rule from U4 Stage 2 applies unchanged.
- **Command branch.** `/dungeon ominous` for players without a lodestone, gated
  on `ominousRequiresBottle` (default true) so an operator chooses whether the
  stakes cost anything.
- **Payout (U5).** An ominous run multiplies the completion payout by
  `ominousPayoutPercent` (default 150), applied after the streak bonus, and the
  completion message says which run it was.
- **Loot.** Three new tables, `chests/tier_1..3_ominous`: each tier's table plus
  the boss-stone pool U3 put in tier 2 and 3. Boss stones move here entirely —
  they become the reason to run ominous — and cross-cutting §6 still holds, since
  they are still nothing but `set_custom_data` on an echo shard.

Grant Trial Omen with a duration longer than any plausible run, and **clear it on
exit whatever the `ExitReason`**. A player who leaves a dungeon still carrying
Trial Omen takes it into the overworld, and that is a real-world effect this mod
has no business exporting.

### Stage 6 — what this does to the sealed cell

Trial spawners can roll **breeze** and **bogged** at tier 3, which is part of the
point of using them, and both need a check against M0's sealed-cell contract:

- **Breeze** fires wind charges. Wind charges do not break blocks, but they do
  activate buttons, levers, doors and trapdoors, and they shove players around.
  Sealed cells contain none of the former and the doorways are open arches, so
  the worst case is a player being pushed through a doorway — which is a fight,
  not a bug. Breeze is in.
- **Bogged** is a skeleton with poison arrows and behaves like `stray`, which is
  already in the tier-2 roster. In.
- **Creepers stay out**, for exactly M0's reason: a hole in a sealed cell is a
  hole into the void. Vanilla's own trial chamber spawn potentials include them,
  so the rosters must be authored by hand and **never copied from
  `minecraft:spawners/trial_chamber/*`**.

Also confirm teardown still cleans up. Spawner mobs are ordinary entities and the
existing "every non-player entity in the volume" purge covers them, but **ejected
items on the floor are destroyed by teardown**, same as any dropped item. That is
already true of chest loot a player leaves behind, so it is not a regression — it
just becomes far easier to hit, because a vault ejects onto the floor rather than
into an inventory. Worth one line in the entry message, and worth confirming that
a player who exits with items still airborne loses only those.

### Stage 7 — verification

Headless, from the console, on a fresh `run/`:

1. `/dungeon admin build 4242` — every encounter cell has exactly one trial
   spawner whose config matches `DifficultyProfile` for that tier; every loot
   cell has exactly one vault pointing at `pocketdungeons:chests/tier_N`; still
   zero `minecraft:jigsaw` blocks anywhere in the volume.
2. The same build with a seed whose path length is 8: cells at depth ≥ 5 are
   `ominous=true`, cells below it are not.
3. `/dungeon admin coverage` and `plansurvey 200` unchanged from U2's numbers,
   plus the new `loot <= encounter` invariant holding across all 200 seeds.
4. `trialsEnabled: false` → chests and classic spawners, U3 behaviour intact.
5. `/dungeon admin purge <slot>` → spawners, vaults, their mobs and any ejected
   items all gone; slot returned; force-load tickets released.

Client walkthrough, appended to `CLIENT_TEST_CHECKLIST.md`:

6. Solo run: clear a spawner, watch it eject a key, spend the key on a vault, get
   the tier table.
7. Party of two: both open the same vault and **both** get a full reward; a
   second attempt by the same player gets nothing and reads as already claimed.
8. Reach a vault with no key: the vault is inert and legible as locked, not
   broken.
9. Ominous run via the bottle: spawners visibly ominous from the first room, the
   ominous vault drops a boss stone, the payout is 1.5×, and Trial Omen is
   **gone** the moment the player is back in the overworld — check after an
   exit-pad completion, after `/dungeon exit`, and after a death rescue.
10. Die inside during a spawner fight: ejected, inventory intact, no payout, and
    the instance's mobs do not follow.

### Deliberately not in this milestone

- **Vanilla trial chamber *structure* generation.** The small pieces are
  borrowed (Stage 2); the layout stays ours. Three measured reasons, kept here so
  the question does not get re-opened from memory:
  - **It cannot fit the grid.** Chamber pieces are 11×12×11, corridors 19×20×19,
    intersections up to 23 wide and 37 tall. A cell is 16×7×16. There is no
    subset of the structural pool that a door mask can describe.
  - **It cannot fit the slot.** `trial_chambers.json` is `size: 20` with
    `max_distance_from_center: 116` — up to a ~230-block sprawl, hundreds of
    chunk tickets against the current handful, and a teardown volume orders of
    magnitude past `clearBlocksPerTick`.
  - **It would not be sealed.** `terrain_adaptation: encapsulate` runs during
    chunk generation, not on the manual placement path, so in `pocketdungeons:
    void` a chamber is a floating shell whose unfilled jigsaw stubs are holes
    into the void — against M0's one hard safety contract.

  If it is ever wanted, it is a *second instance model* — "deep chamber" as an
  alternate run type with its own bounds, sealing pass and teardown budget — not
  a room source for this one. Two things to know before starting that: the public
  entry point is `JigsawPlacement.generateJigsaw(ServerLevel, Holder<
  StructureTemplatePool>, Identifier target, int maxDepth, BlockPos, boolean)`,
  and it passes `PoolAliasLookup.EMPTY`, so the four alias-only spawner pools
  (`spawner/contents/{melee,ranged,slow_ranged,small_melee}` — they exist only as
  `pool_aliases` in the structure JSON, with no file on disk; only
  `contents/breeze.json` is real) never resolve and every chamber comes out with
  empty spawner sockets. Getting them requires calling the public
  `JigsawPlacement.addPieces(Structure.GenerationContext, …, PoolAliasLookup
  .create(bindings, pos, seed), …)` directly, with a hand-built
  `GenerationContext` and the bindings read out of vanilla's structure JSON.
- **Ominous ejection-wave tuning.** Defaults first; tune after play, and only
  through the config fields added here.
- **Maces and heavy cores.** Vanilla gates them behind ominous vaults in real
  trial chambers, and minting them from a repeatable pocket dungeon is a much
  larger economy decision than this milestone should make quietly.
  `tier_3_ominous` is where that argument goes when someone wants to have it.
- **Deleting U3's `RoomContent` spawn path.** It stays as the
  `trialsEnabled: false` fallback. Removing it saves perhaps sixty lines and
  costs the only rollback.

### Shipped: what was built, verified, and where it deviates

**The spike passed, which is why the rest of this exists.** Stage 3 asks for the
ominous blockstate to be proven before anything else is written, on the grounds
that a failure there needs a different mechanism for half the milestone. It was
run first, twice over:

- **Bytecode.** `TrialSpawnerBlock.getTicker`'s server lambda reads
  `BlockStateProperties.OMINOUS` off the blockstate *every tick* and passes it as
  the third argument to `TrialSpawner.tickServer`, whose first two instructions
  are `aload_0 / iload_3 / putfield isOminous` — assigned unconditionally, with no
  player check anywhere in the path. `activeConfig()` then branches on that field
  alone. So a stamped `ominous=true` **is** the authority.
- **In-world.** `setblock minecraft:trial_spawner[ominous=true]` in
  `pocketdungeons:void`, no player in any dimension, ten seconds of ticking:
  still `ominous=true`, config intact. The same pass confirmed `normal_config`
  and `ominous_config` accept registry-id strings, that an ominous vault takes
  both an `ominous` blockstate and a config in one write, and — for U7 — that a
  `custom_data`-tagged `minecraft:trial_key` survives as a vault's `key_item`
  with its components byte-intact.

One thing the spike found that is worth keeping: **a misspelt trial-spawner
config id does not throw.** The codec drops the field and the block silently
keeps `FullConfig.DEFAULT` (`data get` then answers "Found no elements matching
normal_config"). That is why `admin cellreport` reads the ids *back out of the
block entity* rather than trusting the write, and why the check below is "the id
reads back", not "the write did not error".

Also recorded, because it bounds how long the flag lives: vanilla's
`TrialSpawnerState.COOLDOWN` calls `removeOminous` when the cooldown finishes,
which clears the blockstate. At the default `trialSpawnerCooldownTicks` of 36,000
that is half an hour after a spawner is beaten — far longer than a run — so it
never fires in practice, but a server that lowers that value will see cleared
spawners revert to their normal look. Nothing *re-applies* ominous, so the flag
only ever decays, never flickers.

**Live-verified on a headless dev server, fresh `run/` each pass:**

| Check | Result |
|---|---|
| U1/U2 regression, unchanged by this milestone | manifest **14 loaded, 0 rejected**; `admin coverage` **all 53 (mask, role) pairs, 0 holes**; `plansurvey 200` → **200/200 on the first attempt** |
| trial spawners placed and configured | every `encounter` cell carries exactly one, `normal=pocketdungeons:tier_N/normal` reading back off the block entity — so the six data files really did load into the `minecraft:trial_spawner` registry |
| vaults placed and configured | every `loot` cell carries exactly one, `loot=pocketdungeons:chests/tier_N` (or `tier_N_ominous`), `key=` the configured item |
| ominous by depth | path 5 → deep third is depth ≥ 3; shallow spawner `ominous=false`, deep spawner `ominous=true`, matching vault ominous-ness, in the same build |
| ominous by run | `admin build 4242 1 true` → **every** spawner and vault ominous from the entrance |
| **zero `minecraft:jigsaw` blocks** | nine `/fill … replace minecraft:jigsaw` sweeps covering the full 192×7×192 slot envelope — **"No blocks were filled"** every time. The M1 regression holds with `JigsawFallback` now filtering on bounds rather than namespace |
| `spawner_den` conversion | its authored `minecraft:spawner` **becomes** the trial spawner at the same position; a `/fill … replace minecraft:spawner` over the whole instance finds none left |
| `treasure_alcove`'s second chest | first container → vault, second → `pocketdungeons:chests/supply` with a derived seed |
| every new loot table, by `/loot insert` | `spawners/trial_key` → trial key; `spawners/ominous_trial_key` → ominous trial key; `spawners/consumables`, `chests/supply` → their pools; `tier_1_ominous` and `tier_3_ominous` → Boss Stones I/II/III with the exact `{kamutotems:{boss_stone:N}}` custom data, correct name and lore; **`tier_2` plain, rolled three times, produced no boss stone at all** — they really did move |
| enchanted books, U3's regression | `tier_3_ominous` rolls carry real `stored_enchantments` — the `minecraft:book`-not-`enchanted_book` fix survived the table rewrite |
| `empty.json` | inserts nothing and logs nothing. It exists because `VaultConfig` requires a loot-table id |
| `trialsEnabled: false` | chests back at `pocketdungeons:chests/tier_N` with seeds, mobs back in encounter cells, **zero** trial spawners and **zero** vaults in the whole instance, jigsaw invariant still clean |
| teardown | trial spawner, vault, choice vaults, floor — all air after `admin purge`; zero non-player entities; slot returned and reusable, a second build on the same slot finding clean ground |
| `gradlew build` | six pure-JDK tests pass, including the new `KeystoneMathTest` |

**Deviations from the plan, all deliberate.**

1. **The spawner and the vault are placed at stamp time, not authored into the
   templates.** Stage 2 called for authoring both into every `.nbt` and
   regenerating the fourteen-room library. They are written by `TrialContent`
   instead, and the reason cross-cutting §3 demanded template authoring is still
   satisfied: **both anchors come from rotation-transformed sources.** The
   spawner lands on a `pocketdungeons:spawn` jigsaw position, which
   `getJigsaws(placementPos, rotation)` already returns rotation-correct; the
   vault replaces an authored chest, whose position `placeInWorld` already
   transformed. §3's actual rule — "read them back out of the template,
   transformed for the placement rotation, never by computing an offset from
   `cellOrigin`" — holds either way. What it buys is large: the fourteen shipped
   `.nbt` files and their measured 53/53 coverage and 200/200 plan success are
   **untouched**, and `trialsEnabled: false` rolls back to U3 *exactly* rather
   than approximately, because U3's geometry is still the geometry on disk. What
   it costs is nothing that was not already true: the configs were always going
   to be written per run, because a tier is only known at run time.
2. **Vanilla's micro-pieces are not borrowed.** Stage 2's argument for stamping
   `trial_chambers/spawner/**` and `reward/vault` was that the block entities
   arrive pre-configured and known-good. Since `TrialContent` overwrites both
   configs anyway, that buys nothing, while the pieces bring their own 3×2×3 and
   3×4×3 of geometry into cells whose furniture positions are hand-tuned against
   the doorway lane rule. `JigsawFallback` was widened regardless — see below —
   so the option stays open at zero cost.
3. **`JigsawFallback` now filters on the bounds, not the namespace.** The plan
   asked for this to cover borrowed `minecraft:`-named jigsaws. It shipped even
   though nothing is borrowed yet, because the pass only ever runs over a
   16×7×16 cell the stamper wrote this tick inside `pocketdungeons:void` — there
   is no player-built jigsaw in there to destroy — and because the alternative is
   remembering to widen it on the day someone does borrow a piece.
4. **One key kind per run — the biggest change, and it was found in-world, not in
   review.** Stage 4 states that "`loot <= encounter` has to hold **per kind**,
   not just overall", and leaves the planner to solve it. The first headless pass
   showed why that is a trap: `ominousAt` is a function of *depth*, so a plain run
   legitimately contains both plain and ominous cells, and therefore both plain
   and ominous vaults — each wanting a *different item* that only a matching
   spawner ejects. `balanceKeyBudget` balances the total and says nothing about
   the split, so a shape whose loot cells are all deep and whose encounter cells
   are all shallow mints plain keys for ominous vaults. Reachable, and it reads to
   a player as a vault that never opens.

   Rather than teach the planner a second, per-kind budget, **the key became a
   property of the run**: a plain run mints plain keys everywhere, an ominous run
   mints ominous keys everywhere, and the deep-third ramp survives intact as a
   harder fight (`ominous` blockstate, ominous spawner config) and a better loot
   table (`tier_N_ominous`). That needed one new data file per tier —
   `tier_N/ominous_plain_key.json`, identical to `tier_N/ominous.json` except for
   which key it ejects — and makes `loot <= encounter` sufficient *exactly as the
   plan writes it*. Verified both ways: a plain run's vaults all read
   `key=minecraft:trial_key` with every spawner on `ominous_plain_key`; an
   ominous run's all read `key=minecraft:ominous_trial_key` with every spawner on
   `ominous`.
5. **`loot <= encounter` is enforced by a rebalance pass, not by rejection.**
   Stage 4 asks for a `validate` check that makes the generator retry. Rejection
   would spend the whole plan budget on shapes that are one role-flip away from
   fine, and would quietly reintroduce M3's failed-plan rate on exactly the
   branchy layouts U1 exists to support — spur tips are forced to `loot`, so a
   branchy shape routinely starts out with five loot cells and two encounters.
   `LayoutGraphGenerator.balanceKeyBudget` instead promotes a corridor to an
   encounter (the generous direction, per DESIGN.md §5), failing that converts an
   off-path loot cell, and failing that an on-path one. It is deterministic,
   always succeeds, and costs no retries. The `validate` check ships anyway as the
   regression guard that the pass ran — and it earned its keep immediately: seed
   78 of the 5,000-seed `layoutGraphTest` sweep produced four loot cells and one
   encounter and caught the first version of the pass, which gave up when every
   loot cell was on the critical path.
6. **`DifficultyProfile` kept `mobCount`/`effectiveTier`/`mobRoster`; only
   `chestRolls()` was deleted.** Stage 1's table says all four go. But the same
   milestone keeps U3's spawn path as the `trialsEnabled: false` rollback, and
   that path *calls* the first three — deleting them would delete the rollback the
   plan explicitly preserves. `chestRolls()` really is gone: it was already
   unwired (U3's own shipped notes record that), and leaving a party multiplier
   beside vanilla's `total_mobs_added_per_player` is precisely the double-count
   Stage 1 refuses. The surviving `partySize` term in `mobCount` is **not** a
   double-count: the two paths are mutually exclusive and only one is ever live,
   which is now said out loud in the javadoc and asserted in
   `DifficultyProfileTest`.
7. **`ConfiguredItem` gained no new mechanism but two new users.** `vaultKeyItem`
   and `ominousVaultKeyItem` resolve through it and warm up at `SERVER_STARTED`
   alongside `ritualKeyItem`, so a typo in either is a boot-time log line rather
   than a vault nobody can open.

**What could not be verified here, and why.** Everything left needs a player
standing in a room. A trial spawner does not activate without one — the headless
entity census across a freshly built instance correctly reports **zero** mobs,
which is the right answer and also the reason the fight itself, the key ejection,
the vault opening, the per-player once-each reward and the Trial Omen grant are
all unverified. They are in `CLIENT_TEST_CHECKLIST.md`. This is the same blind
spot that hid U3's container-drop bug and its enchanted-book bug; naming it is
the most this session can honestly do about it.


---

## U7 — keystones: the Mythic+ meta ✅ *shipped*

**Goal:** a reason to run the dungeon a hundred times instead of five. The
dungeon itself does not change — U1's library, U2's planner and U6's trials all
stay exactly as they are — and a **keystone item** wraps it: an item that carries
a level, is consumed to start a run, upgrades when you beat the clock, and
depletes when you don't.

**Blocked by:** U6 for the reward vaults (Stage 3 leans on `VaultBlockEntity`
being configurable at stamp time). Everything else is additive.

**The one-line version:** WoW's Mythic+ is not a run structure, it is a meta
*around* a dungeon, so this milestone adds no dungeon content at all.

### Stage 0 — why there is no run state

Earlier drafts of this idea grew a roguelike map — a layered DAG, branching
doors, an in-progress `RunState` in `SavedData`, and a leave-and-resume flow so a
long run could survive a logout. All of it is dropped, and it is worth recording
why, because the reasoning is the whole argument for this design:

**The keystone is the save file.** It is an item with `custom_data`; vanilla
persists items already. There is no run to resume because there is no run — there
is a key in your pocket and a dungeon you can spend it on. That deletes the map,
the `RunState` record, the safe-room bookkeeping, the "can I log out here" rule,
and the restart-mid-run problem, in exchange for one item and one integer.

The only server-side state this milestone adds is a *pending* keystone for a
player who was offline when their key came back (Stage 5).

### Stage 1 — the keystone item

```java
final class Keystone {
    static ItemStack mint(int level);             // config item + custom_data + name/lore
    static OptionalInt levelOf(ItemStack stack);  // empty if not ours
    static boolean isKeystone(ItemStack stack);
}
```

`custom_data` is `{pocketdungeons:{keystone:1,level:N}}` on `keystoneItem`
(default `minecraft:trial_key`), with a display name of `Keystone [N]` and lore
naming the affix if it has one. Level is clamped to
`[1, keystoneMaxLevel]` on every mint, so no arithmetic anywhere else has to
worry about the bounds.

**The collision question is already answered.** Vanilla's vault matches keys with
`ItemStack.isSameItemSameComponents` — verified in `VaultBlockEntity$Server`'s
bytecode, components compared, not just the item — so a tagged keystone will not
open an ordinary trial vault, and an ordinary trial key will not open anything
this milestone mints. The two key systems cannot be confused by accident, and
that is enforced by vanilla rather than by mod-side checking.

**`RitualListener` inverts.** U4 Stage 2 established "the ritual only ever
consumes a stack with no `custom_data` at all", which was the right rule when the
key was a plain echo shard. It now becomes: consume **only** a stack carrying
`pocketdungeons.keystone`, and `PASS` on everything else — kamutotems sigils
included, for exactly U4's reasoning. Lodestone plus keystone is the font.

**Where the first one comes from.** `/dungeon key` mints a level 1 keystone, free
and unlimited, but only for a player holding none and with none pending. That
cannot dead-end a player, cannot be farmed (a level 1 key is worth less than the
walk), and keeps DESIGN.md §5's posture. `/dungeon` with no keystone tells the
player to run it.

### Stage 2 — the timer

The timer is what makes level 12 *harder* rather than merely longer, and without
it the ladder is a loot slider with extra steps.

```
timerSeconds = timerBaseSeconds + timerPerRoomSeconds * layout.pathLength()
```

**Derived from the dungeon's size, not the key's level** — that is the Mythic+
shape: a higher key does not shorten the clock, it stiffens what stands between
you and the end of it. At the defaults (`180` base, `60` per room) a 5-room
dungeon allows 8 minutes and an 8-room dungeon 11.

`RunTimer` owns a `ServerBossEvent` per instance — server-side, so vanilla
clients render it with no client mod:

- title `Keystone [7] — 6:42 — 3/8 rooms`
- progress is time remaining over total
- colour steps green → yellow → red at 50% and 20%
- every party member is added to the same bar; a member who leaves is removed

**Expiry does not end the run.** The bar turns red, reads `OVER TIME`, and the
run continues to whatever end the player walks to. All that changes is the payout
at the exit pad: an over-time completion returns the keystone at its current
level (or `overtimeDepletion` below it, default `0`) instead of offering an
upgrade. This is how Mythic+ actually behaves, it is kinder than ejecting
somebody at the buzzer, and it means the mod needs no timer-expiry ejection path
at all — the timer is pure tension.

### Stage 3 — the upgrade offer, and how vanilla enforces "pick one"

Reaching the exit pad in time grants a **completion token** — the configured
token item carrying `{pocketdungeons:{token:1,level:N}}`, one per player — and
the exit room is stamped with **three vaults**, all configured with that exact
token as their `keyItem`.

Because the player holds exactly one token and the vault consumes it,
**vanilla enforces the choice with no mod-side sealing code**: open one, and the
other two can never be opened. Each party member carries their own token and
makes their own choice, which falls out of the same mechanic for free.

Each vault's `getSharedData().setDisplayItem(...)` shows the keystone it would
give, so the player reads all three offers before committing — the API is public,
verified in the 26.2 jar alongside `setConfig` and `getServerData()`.

| Offer | Keystone | Character |
|---|---|---|
| Safe | `level + 1`, same affix | the default step |
| Ominous | `level + 2`, ominous affix | U6 already builds the whole ominous half |
| Fragile | `level + 3`, fragile | depletes double on any failure |

The vault's own `loot_table` points at `pocketdungeons:empty` — a real file with
no pools, because the config requires an id — and **the keystone is granted from
Java**, on detecting the player's UUID in `getServerData().getRewardedPlayers()`
from the instance tick that already runs. A static loot table cannot mint
`level + 2` when the level is only known at runtime; `set_custom_data` is the
right tool for boss stones (cross-cutting §6) and the wrong one here.

Grant the keystone with `Payout`'s delivery helper, **not** raw
`Inventory.add(stack)` — U5's shipped notes record that `add` returns "did I move
any of this" and mutates the stack down to the remainder, so the suite's usual
idiom silently destroys what does not fit. A keystone is a single item, so this
only bites on a completely full inventory, which is precisely when a player has
just finished a run.

### Stage 4 — depletion

Every failure mode collapses to one rule and one number. The keystone was
consumed at the font, so *the mod always hands one back* — there is no state
where a player who owned a key ends up with nothing.

| Outcome | `ExitReason` | Keystone returned |
|---|---|---|
| Exit pad, in time | `EXIT_PAD` | the offer they choose, `level + 1..3` |
| Exit pad, over time | `EXIT_PAD` | `level - overtimeDepletion` (default `0`, so: unchanged) |
| Death inside | rescue path | `level - depletionOnDeath` (default 2) |
| `/dungeon exit` | `COMMAND` | `level - depletionOnExit` (default 1) |
| Disconnect inside | disconnect path | `level - depletionOnDisconnect` (default 3) |
| Server purge / crash | `PURGE` | **unchanged** — never punish the player for the server |

Fragile keystones double every depletion figure above, which is the whole cost of
having taken the `+3`.

All results clamp at level 1: a keystone never disappears and never goes to zero.
A bad night costs levels, never progress, which is the same instinct behind U5's
grace-free but mild streak reset.

**U5's shipped notes matter here.** `ExitReason` has two constants, not five —
`rescue`, `purge` and `dropMember` eject directly and never route through
`exit`. So depletion cannot hang off `ExitReason` alone; it needs a single
`Keystones.returnTo(player, level, reason)` called from each of those four sites.
Wiring it to `exit` only would silently skip death and disconnect, which are two
of the four rows in that table.

### Stage 5 — the offline player

A player who disconnects mid-run is not there to be handed anything, and this is
the only piece of server state the milestone adds.

`DungeonLog.Entry` gains `pendingKeystoneLevel` (`0` = none), written when a
depleted key cannot be delivered, and drained by a join handler that mints and
grants it on the player's next login. `DungeonLog` is already a `SavedData` with
a Codec and already keyed by UUID, so this is one field and one call site, and it
survives a restart because that store already does.

Guard it: if a player somehow already holds a keystone when a pending one drains,
grant both. Two keystones is not a bug worth writing code to prevent — a lost one
is.

### Stage 6 — what the level actually drives

`DifficultyProfile` re-keys from `pathLength` to keystone level, and stays the
one place the curve lives:

| Reads | Was | Now |
|---|---|---|
| `lootTier()` | path length 5/6-7/8+ | level bands 1–4 / 5–9 / 10+ |
| trial spawner config | tier | tier, unchanged — U6's six data files still cover it |
| ominous | deep third of the run | deep third **or** `level >= ominousFromLevel` (default 10) **or** the ominous affix |
| `Payout` count | `payoutBaseCount + payoutPerTier * (tier-1)` | the same, times `100 + payoutPerLevelPercent * level` |

`pathLength` keeps exactly one job — it sizes the timer (Stage 2). That split is
deliberate: the planner decides how *big* a dungeon is, the keystone decides how
*hard* it is, and a tuning complaint maps to one of the two without ambiguity.

**Level is capped at `keystoneMaxLevel`, default 25.** WoW's ladder is unbounded
because its rewards stop mattering long before its numbers do; here tier 3 is the
last loot table, so past the cap the only thing still moving is the payout
multiplier and the bragging line in `/dungeon log`. Capping it is honest about
where the content ends.

### Stage 7 — verification

Headless:

1. `/dungeon key` with an empty inventory mints `Keystone [1]`; run it again while
   holding it and it refuses with a reason.
2. Lodestone + keystone consumes it and enters; lodestone + a kamutotems sigil
   does not, and the sigil still works (U4 Stage 4 check 2, unchanged).
3. `/dungeon admin build` at levels 3, 7 and 12: loot tables tier 1/2/3, ominous
   off/off/on.
4. Exit-pad completion in time: three vaults, each displaying its keystone; open
   one, get that keystone, **the other two are inert**. Confirm by trying.
5. Complete over time: bar reads `OVER TIME`, one vault path is skipped, keystone
   comes back unchanged.
6. Die at level 8: keystone comes back at 6, inventory intact, no payout.
7. Disconnect mid-run at level 8, log back in: `Keystone [5]` on arrival, and the
   same after a server restart before that login.
8. Fail a fragile level 8 by dying: comes back at 4, not 6.
9. Fail at level 1 by every route: still level 1, never 0, never gone.
10. `/dungeon admin purge` while a player is inside: keystone returns
    **unchanged**.

Client walkthrough, appended to `CLIENT_TEST_CHECKLIST.md`:

11. The boss bar is visible, counts down, changes colour, and names the level and
    room count; it disappears on exit.
12. Party of two: one bar for both, both get their own token, both choose their
    own keystone, and one member disconnecting does not affect the other's timer.
13. Full inventory when the keystone is granted: it drops at the return point in
    the overworld, not in the void.

### Deliberately not in this milestone

- **Affixes beyond ominous and fragile.** Rotating weekly affixes are the obvious
  next thing and the obvious way to double this milestone's size. Two affixes
  prove the mechanic; the third is a content decision made after play.
- **Leaderboards and weekly vaults.** U5's streak is still the entire retention
  system, and now the keystone level is a second, better one.
- **Party key averaging.** The keystone that opens the font is the one that
  counts; other members ride along and receive their own token and offer. Whether
  a rider's *own* key should also advance is a real question, but it is a rule
  about parties, not about keystones, and it can be answered without touching any
  of this.
- **The roguelike map.** A layered DAG, branching doors as portals, an
  in-progress `RunState` and a leave-and-resume flow were all designed and
  dropped in favour of this. The keystone does what the map was for — persistent,
  escalating, player-chosen progression — with one item instead of a second
  instance model. Recorded here so the idea is a decision rather than an
  oversight.
- **Vanilla chamber pieces as whole arenas.** Still live, still independent: that
  is a question about what a room is, and this milestone is about what a run is.
  See U6's "Deliberately not" for the measured version.

### Shipped: what was built, verified, and where it deviates

**The mechanic the whole milestone rests on was checked before anything was
built.** Stage 3 claims vanilla enforces "pick exactly one" because
`VaultBlockEntity$Server` matches keys with `ItemStack.isSameItemSameComponents`.
Confirmed in-world during U6's spike: a vault configured with a
`custom_data`-tagged `minecraft:trial_key` reads the tag straight back out of its
own NBT, components intact. And confirmed in a real build below: all three choice
vaults carry `key=minecraft:trial_key` with
`keytag={pocketdungeons:{level:3,token:1}}` — identical stacks, which is what
makes one token open exactly one of them and permanently seal the other two with
no mod-side sealing code.

**Live-verified on a headless dev server, fresh `run/`:**

| Check | Result |
|---|---|
| level bands drive the tier | `admin build 4242 3` → tier 1, `… 7` → tier 2, `… 12` → tier 3. Path length was 5 in all three, so this is the keystone deciding and not the planner |
| `ominousFromLevel` | level 12 (≥ 10) → the build line reads `OMINOUS` and every spawner and vault in the instance is ominous; levels 3 and 7 are ominous only in the deep third |
| explicit ominous | `admin build 4242 1 true` → ominous from the entrance at level 1 |
| three choice vaults, in the terminal cell | present in every keystone build, `loot=pocketdungeons:empty`, all three keyed to the same token, one flagged `ominous=true` as the middle offer's signal |
| choice vaults are absent without a keystone | `admin build 4242 0 false` → terminal cell empty. A run nobody paid for offers nothing, which is the honest behaviour for `/dungeon admin build` |
| the token's components survive the write | `keytag={pocketdungeons:{level:3,token:1}}` read back off the vault config, identical across all three |
| `KeystoneMathTest` | new pure-JDK test wired into `tasks.test`: level bands at 4/5/9/10, depletion at every configured value, the never-zero floor by every route, fragile doubling, `upgrade` clamping at `keystoneMaxLevel`, the timer's 480 s and 660 s worked examples, `formatClock` including the over-time `0:00`, and both new payout multipliers |
| `DungeonLog` is backward-compatible | `best_keystone` and `pending_keystone` are `optionalFieldOf`, so a `dungeon_log.dat` written before this milestone loads unchanged rather than being discarded |
| teardown | the three choice vaults are cleared with everything else; slot returned |
| `gradlew build` | all six pure-JDK tests pass |

**Deviations from the plan, all deliberate.**

1. **Reaching the exit pad no longer ejects you — it completes the run.** This is
   the one shipped, client-tested behaviour U7 changes, and the plan does not
   mention it at all. It has to change: Stage 3 puts three vaults in the exit room
   and grants the token that opens them *at the pad*, and before this the pad paid
   and threw you out in the same instant, so the token would have arrived in the
   overworld with the vaults left behind in a dungeon already being torn down.
   Now the **first** pad contact pays, logs the completion, stops the clock and
   hands over the token; the **second** contact leaves. Contact is tracked as an
   edge (`InstanceRecord.onPad`), not a state, so standing still on the pad does
   not eject you on the next watcher tick. A run opened without a keystone —
   `/dungeon admin build` — keeps U5's single-contact behaviour exactly.
2. **A completed run can never be charged a failure.** Found by tracing rather
   than by testing: with (1) in place, a player who completes and then types
   `/dungeon exit` to walk out of the exit room would have been charged
   `depletionOnExit`, and one who is killed by a spawner mob still chasing them
   around that room would have been charged `depletionOnDeath` — after finishing.
   `Instances.returnKeystone` now upgrades any non-`SERVER` outcome to the
   completion outcome once `record.completed` holds that member. The `SERVER`
   exemption is left alone because it already costs nothing.
3. **`/dungeon` requires a keystone.** Stage 1 says `/dungeon` with no keystone
   should tell the player to run `/dungeon key`, which only makes sense if the
   command spends one — so it does, through the same
   `Instances.enterWithKeystone` the lodestone uses, with the same consume-only-
   after-`enter`-succeeded rule. This supersedes U4 Stage 3's "`/dungeon` stays
   available to everyone and free": the *lodestone* is the optional part now, not
   the key. `/dungeon admin build` is unaffected and still needs nothing.
4. **The affix does not survive a failure.** Stage 4 says fragile doubles every
   depletion; it does not say what the returned keystone carries. It comes back
   plain. The affix was the price of the extra levels the player already banked
   when they took the offer, and carrying Fragile forward forever would compound
   one bad night into every night after it.
5. **The three choice vaults are placed at cell-relative positions, which
   cross-cutting §3 warns against — and it is safe here for a stated reason.** The
   exit room authors no chest and no spawn point to hang them on. They go at three
   of `{5,10} × {5,10}` at `y = 1`, a set that is **closed under the rotation
   transform** (`15 − 5 = 10`), so any fixed choice of three lands on three
   distinct interior floor positions at every rotation. They may not be the *same*
   three across rotations, which does not matter — the three offers are
   interchangeable. All four corners are clear of the doorway lanes (`x,z` in
   `[7,8]`) and of the 2×2 lodestone pad.
6. **The vault claim is read out of NBT, not out of `getRewardedPlayers()`.**
   Stage 3 assumes that method is reachable. It is not — `VaultServerData` is
   package-private in `net.minecraft.world.level.block.entity.vault`, and only
   `addToRewardedPlayers` is public. `TrialContent.rewardedPlayers` goes through
   `BlockEntity.saveWithoutMetadata` (public) and decodes
   `server_data.rewarded_players` with `UUIDUtil.CODEC_LINKED_SET` (public),
   which is the codec vanilla itself writes it with. Reading the codec rather than
   reflecting a field means a rename shows up as an empty set and a decode warning
   in the log, not as a compile that quietly does the wrong thing.
7. **`Payout.deliver` was extracted rather than reusing `giveOrDrop`.** U5's
   shipped notes record that `Inventory.add` returns "did I move *any* of this"
   and mutates the stack down to the remainder, so the suite's usual
   `if (!add(stack))` idiom silently destroys overflow. Every keystone hand-back
   goes through `Payout.deliver`, which tests `stack.isEmpty()` afterwards. A
   keystone is one item, so this only bites on a completely full inventory —
   precisely the state a player is in when they have just finished a run.
8. **`DifficultyProfile` keeps the path-length curve as a fallback.** Stage 6 says
   the tier re-keys to the keystone level. It does, whenever there is one; a run
   with `keystoneLevel == 0` (`/dungeon admin build`) still uses U3's path-length
   bands rather than being left tierless. Both halves are asserted in
   `DifficultyProfileTest`, including that the level bands are `KeystoneMath`'s
   and not a second copy of them.
9. **The boss bar advances on the instance watcher's interval, not every tick.**
   `RunTimer.tick(interval)` is called from the existing `onTick`, which runs
   every `watchIntervalTicks` (default 20). The bar only needs to be right to the
   second, and this adds no new tick handler.
10. **`/dungeon ominous` and the ritual's bottle branch both consume the bottle
    only after entry succeeds**, matching U4's rule for the key. The ritual reads
    the bottle from the **off hand**, so the main hand stays free for the keystone
    and neither choice has to be made by juggling.

**What could not be verified here, and why.** Everything that makes a keystone a
keystone needs a player: minting one with `/dungeon key`, spending it on a
lodestone, the boss bar rendering and counting down, reaching the pad, opening one
of three vaults and finding the other two inert, and every row of the depletion
table. A headless console cannot right-click a block or hold an item, so none of
it is claimed as verified — the logic behind each was traced by hand (the two
ordering bugs in deviations 1 and 2 are what that tracing found) and the steps are
in `CLIENT_TEST_CHECKLIST.md`. The offline-keystone path (Stage 5) is the one
worth walking first: it is the only new persistent state in the milestone, and its
failure mode is a lost keystone rather than a visible error.

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
| `trialsEnabled` | true | U6 | — (false restores U3 chests + classic spawners) |
| `vaultKeyItem` | `minecraft:trial_key` | U6 | as `ritualKeyItem`; written into both the vault config and the spawner ejection table |
| `ominousVaultKeyItem` | `minecraft:ominous_trial_key` | U6 | as above, for ominous cells — vanilla's ominous vault will not take a plain trial key |
| `ominousRequiresBottle` | true | U6 | — |
| `ominousPayoutPercent` | 150 | U6 | `>= 100` |
| `trialSpawnerCooldownTicks` | 36000 | U6 | `>= 0` (only matters if a player re-enters a cleared room) |
| `keystoneItem` | `minecraft:trial_key` | U7 | as `ritualKeyItem`; the `custom_data` is what distinguishes it, not the item |
| `keystoneTokenItem` | `minecraft:trial_key` | U7 | as above; may be the same item, the tag differs |
| `keystoneMaxLevel` | 25 | U7 | `>= 1` |
| `depletionOnDeath` | 2 | U7 | `>= 0` |
| `depletionOnExit` | 1 | U7 | `>= 0` |
| `depletionOnDisconnect` | 3 | U7 | `>= 0` |
| `overtimeDepletion` | 0 | U7 | `>= 0`; 0 returns the key unchanged |
| `timerEnabled` | true | U7 | false hides the bar and never depletes for time |
| `timerBaseSeconds` | 180 | U7 | `>= 0` |
| `timerPerRoomSeconds` | 60 | U7 | `>= 0` |
| `payoutPerLevelPercent` | 5 | U7 | `>= 0` |
| `ominousFromLevel` | 10 | U7 | `>= 1` |

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
| `DungeonCommands.java` | exists | U1/U2/U5 | `admin coverage`, `admin stamptest`, `admin build [seed]`, `/dungeon log [player]`, dev-only `admin log record|show` |
| `PocketDungeonsConfig.java` | exists | all | 19 new fields, three new readers, `slotPitch % 16` |
| `DifficultyProfile.java` | **new** | U3 | pure-logic curves and rosters |
| `RoomContent.java` | **new** | U3 | role dispatch, mob spawning, chest retargeting |
| `data/.../loot_table/chests/tier_1..3.json`, `bonus.json` | 3 new + 1 rewrite | U3 | generous tiered tables, boss stones |
| `RitualListener.java` | **new** | U4 | lodestone + key item |
| `ConfiguredItem.java` | **new** | U4/U5 | shared parse-cache-log-once resolution for `ritualKeyItem` and `payoutItem` |
| `DungeonLog.java` | **new** | U5 | per-player `SavedData` |
| `Payout.java` | **new** | U5 | `giveOrDrop`, streak multiplier, optional command hook |
| `PayoutMath.java` | **new** | U5 | pure streak transition and payout arithmetic |
| `PayoutMathTest.java` | **new** | U5 | pure-JDK, wired into `tasks.test` |
| `DifficultyProfileTest.java` | **new** | U3 | pure-JDK, wired into `tasks.test` |
| `TrialContent.java` | **new** | U6 | trial spawner + vault configuration at stamp time, ominous blockstate, `trialsEnabled` fallback |
| `RoomTemplateGenerator.java` | exists | U6 | author `trial_spawner` and `vault` into the floor rooms; regenerate the library |
| `data/.../structure/rooms/*.nbt` | regenerated | U6 | committed output of the above |
| `data/.../loot_table/chests/tier_1..3_ominous.json` | 3 new | U6 | tier table + the boss-stone pool, which moves out of `tier_2`/`tier_3` |
| `data/.../loot_table/spawners/trial_key.json` | **new** | U6 | what an encounter spawner ejects |
| `RoomContent.java` | exists | U6 | delegates encounter/loot to `TrialContent`; its spawn path survives only as the kill-switch fallback |
| `DifficultyProfile.java` | exists | U6 | `mobCount`/`effectiveTier`/`mobRoster`/`chestRolls` all deleted; only `lootTier()` survives |
| `data/.../trial_spawner/tier_1..3/{normal,ominous}.json` | 6 new | U6 | the rosters as data; `minecraft:trial_spawner` is a registry, so the block stores only the id |
| `JigsawFallback.java` | exists | U6 | clear *any* leftover jigsaw in bounds, not just `pocketdungeons:` — vanilla's borrowed pieces carry `minecraft:`-named ones |
| `LayoutGraphGenerator.java` | exists | U6 | one new `validate` check: `loot <= encounter` |
| `RitualListener.java` | exists | U6 | ominous-bottle branch |
| `Instances.java` | exists | U6 | `enter(player, boolean ominous)`, Trial Omen grant, clear-on-exit for every `ExitReason` |
| `DungeonCommands.java` | exists | U6 | `/dungeon ominous`, stamptest block-entity assertions |
| `Payout.java` | exists | U6 | `ominousPayoutPercent`, applied after the streak bonus |
| `Keystone.java` | **new** | U7 | mint/parse the keystone and completion token, clamp the level |
| `Keystones.java` | **new** | U7 | `returnTo(player, level, reason)` — the one depletion call site, wired to all four exit paths |
| `RunTimer.java` | **new** | U7 | per-instance `ServerBossEvent`, countdown, over-time flag |
| `KeystoneMathTest.java` | **new** | U7 | pure-JDK: level bands, depletion clamps, fragile doubling, timer arithmetic |
| `data/.../loot_table/empty.json` | **new** | U7 | no pools; the choice vaults need a valid id but grant from Java |
| `RitualListener.java` | exists | U7 | inverts to "keystones only"; reads the level from `custom_data` |
| `Instances.java` | exists | U7 | carry the keystone level on the record, start/stop the timer, hand the token at the pad |
| `DungeonLog.java` | exists | U7 | `pendingKeystoneLevel` field plus the join-handler drain |
| `DifficultyProfile.java` | exists | U7 | re-keyed from `pathLength` to keystone level |
| `TrialContent.java` | exists | U7 | configure the three exit-room choice vaults, `displayItem` per offer |
| `DungeonCommands.java` | exists | U7 | `/dungeon key`, level in `/dungeon log` |

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

---

# U8 — the timer is the run: free re-entry, a reward room, and a door you choose

**Goal:** make the clock the only thing that can take a keystone level away, and
turn the end of a run into two rooms a player actually wants to reach — a reward
room whose chest count is the score, and a private selector room where the next
key is chosen from three doors.

**Blocked by:** U7's state-as-truth rewrite (shipped: the keystone level lives in
`DungeonLog`, the item is a remote). **Supersedes** most of U7's exit handling and
all of U6's ominous entry paths.

**Status: specified, not built.**

## Stage 0 — what this deletes

The reason to do this is subtraction. Settle it up front so none of it gets
re-added:

| Concern | Today | U8 |
|---|---|---|
| Disconnect mid-run | `depletionOnDisconnect` (3), pending-keystone parking | nothing. The timer does not care where you are |
| `/dungeon exit` | `depletionOnExit` (1) | nothing. Leaving is a break, not a forfeit |
| Death inside | `depletionOnDeath` (2) | nothing. You are ejected and can walk back in |
| Losing a level | four exit sites, four outcomes | **one**: the clock ran out |
| Becoming ominous | depth, `ominousFromLevel`, affix, `/dungeon ominous`, off-hand bottle | **one**: the door you took last run |
| Payout | `base + perTier` x streak% x level% x ominous150% | **chests**: 1-3 by time, contents by key level |
| Streak | `PayoutMath.nextStreak`, `/dungeon log` | **deleted** — the keystone level is the ladder |
| Choosing an upgrade | three vaults in the terminal cell | three doors in a private selector room |

`Keystones.Outcome` collapses from six constants to two: the run was timed, or it
was not. `depletionOnDeath`, `depletionOnExit`, `depletionOnDisconnect`,
`ominousRequiresBottle`, `ominousFromLevel`, `streakBonusPercent`,
`streakBonusCapPercent`, `payoutBaseCount`, `payoutPerTier`, `payoutPerLevelPercent`
and `ominousPayoutPercent` all go. `Payout`/`PayoutMath` lose their item grant;
`payoutCommand` is decided in Stage 5.

## Stage 1 — the run outlives the player

The load-bearing change, and everything else depends on it.

**The timer ticks whether or not anyone is inside.** `Instances.onTick` currently
guards `record.timer.tick(interval)` on `!record.members.isEmpty()`; drop that
guard. A run is a wall-clock commitment, not a presence-gated one.

**Membership stops being lifetime.** `closeIfEmpty` goes away. An instance now
ends on exactly one of:

- **expiry** — the clock ran out and the run was never completed. Deplete the
  keystone one level, tear the instance down, and tell the owner wherever they are.
- **completion + grace** — the owner reached the exit pad. Hold the instance open
  for `rewardRoomGraceSeconds` (default 600) so the reward room can be looted at
  leisure, then tear down.

**Re-entry is free.** `/dungeon` or the compass on a lodestone, while the caller
already owns a live instance, teleports them back into it rather than opening a
new one. This is the `hasInstance` branch that currently refuses.

**A restart forgives.** Instances are memory-only (PLAN.md M5 is still
deliberately unshipped) so a restart loses every live run. That is the `SERVER`
outcome and it must never deplete — the existing rule, unchanged. Runs are ~10
minutes, so this is rare, and honest is better than persistent here.

**What this removes:** the disconnect depletion path, the pending-keystone drain,
`record.onPad`'s two-contact edge tracking, and every "what if they log out"
question U7 Stage 5 exists to answer.

## Stage 2 — the reward room

**Appended to the dungeon**, one cell past the terminal cell, and the exit-pad
lodestone teleports into it rather than ejecting. The player is still inside the
instance; they have simply reached the end of it.

**Chest count is the score.** Measured against the run's own timer, so it scales
with dungeon size for free:

| Finished within | Chests |
|---|---|
| 60% of the clock | 3 |
| 80% of the clock | 2 |
| the clock | 1 |
| over the clock | 0 — and the key delevels |

"Three-chesting a floor" is the bragging line, and it is legible the instant a
player walks in and counts.

**Chest contents scale off the keystone level**, reusing U6's existing
`chests/tier_N` and `tier_N_ominous` tables via `DifficultyProfile.lootTier()`.
An ominous key's reward room rolls the ominous tables. No new loot JSON is
required, which is most of why this stage is cheap.

**Leaving** is a second lodestone in the reward room, or `/dungeon exit`. Neither
costs anything.

**The planner never sees it.** An earlier draft of this stage gave the reward room
a `reward` role and had the planner place it past the terminal cell. That is
wasted risk: the room is reached by **teleport**, so it has no doors, so it has no
mask, so there is nothing for the planner to match on. A role would be a formality
that puts the one subsystem with hard measured guarantees behind it — 53/53
coverage, 200/200 plan success — in the blast radius of a cosmetic addition.

Instead it is one authored `.nbt` with no doors and no `dungeon_room` metadata,
stamped at a fixed offset inside the same slot and loaded directly through
`TemplateStamper.place`, which takes a template `Identifier` and never consults
the manifest. `StaticLayout` already works exactly this way. The room is still
"appended" in every sense a player can observe: same slot, same teardown, same
grace timer.

Teleporting also means the reward room cannot be peeked at early, and a party
arriving at different times each sees it fresh.

## Stage 3 — the selector room and the three doors

**A separate, private instance.** Only the key owner has any business there, and
decoupling it from the dungeon is what lets a player come back to it later. It is
a single static room — no planner, no rotation, no library lookup.

**Reached with the remote.** Right-clicking a lodestone while a choice is pending
takes you there instead of into a dungeon. The pending choice lives on
`DungeonLog.Entry` (a new `pendingOfferLevel`), so it survives a logout, a
restart, and losing the compass.

**Three doors, not three vaults.** Vanilla's vault will not unlock on an empty
loot roll — see `DISCOVERIES.md` — and a door has no such opinion. Right-clicking
one is intercepted (`UseBlockCallback`, as the choice vaults already are),
vanilla's own open/close is cancelled, and the player is sent a chat message
describing the offer with a **clickable accept**:

```
Ominous Door -- Keystone [9], ominous.
Every room runs ominous. The reward room rolls the ominous tables.
                                                   [ Take this key ]
```

`[ Take this key ]` is a `ClickEvent` running a command. **Verify the 26.2
`ClickEvent` shape with `javap` before writing it** — it became a sealed
interface with record subtypes in recent versions and the old constructor form
will not compile.

**Doors carry the affix visually**, which is what makes the choice readable
without lore text:

| Offer | Door | Affix |
|---|---|---|
| `+1` | oak | none |
| `+2` | copper (oxidised) | ominous |
| `+3` | ? | fragile |

Copper doors and their four oxidation states are the obvious expansion ladder
when a third and fourth affix arrive. **Open: which door for fragile.**

**Choosing is one-shot and immediate** — write the level and affix to
`DungeonLog`, clear the pending offer, reconcile the remote, and send the player
home. Walking out without choosing leaves the offer pending; the compass brings
them back.

## Stage 4 — the remote becomes a recovery compass

`keystoneItem` default changes from `minecraft:trial_key` to
`minecraft:recovery_compass`. This is not cosmetic: the trial key is the item
this mod's own spawners eject for its own vaults, and moving the remote off it
removes the collision that U7's entire `isSameItemSameComponents` argument
existed to reason about. Nothing else in the suite touches recovery compasses.

The remote is still never consumed and still displays server state, refreshed by
the reconcile pass shipped with the U7 rewrite.

## Stage 5 — config after the cull

Removed: `depletionOnDeath`, `depletionOnExit`, `depletionOnDisconnect`,
`overtimeDepletion`, `ominousRequiresBottle`, `ominousFromLevel`,
`ominousPayoutPercent`, `payoutBaseCount`, `payoutPerTier`,
`payoutPerLevelPercent`, `streakBonusPercent`, `streakBonusCapPercent`.

Added:

| Field | Default | Validation |
|---|---|---|
| `rewardRoomGraceSeconds` | 600 | `>= 0` |
| `threeChestPercent` | 60 | `1..100` |
| `twoChestPercent` | 80 | `> threeChestPercent`, `<= 100` |
| `timedOutDepletion` | 1 | `>= 0` — the only depletion left |

**Open:** whether `payoutItem`/`payoutCommand` survive at all. The chests replace
the item grant outright; `payoutCommand` is the only remaining hook for an
operator routing rewards through `cobbleeconomy`, and it costs one config read.

## Stage 6 — verification

Headless:

1. Timer ticks with the instance empty — open a run, leave, confirm the bar's
   remaining time still falls and the instance expires on its own.
2. Expiry deplete: let a run time out with nobody inside; the owner's
   `DungeonLog` level drops by one and the slot is returned.
3. Re-entry: `/dungeon` while owning a live instance lands back in the same slot,
   not a new one.
4. Reward room chest counts at each threshold, driven by a forced clock.
5. `/dungeon admin purge` mid-run still leaves the key **unchanged**.

Client, appended to `CLIENT_TEST_CHECKLIST.md`:

6. Complete fast, count three chests; complete slow, count one.
7. Leave mid-run, come back, finish — no penalty for the break.
8. Take each of the three doors and confirm the next run matches the affix.
9. Walk out of the selector room without choosing, then return with the compass.
10. Time out while offline; log in and find the key one level lower.

## Deliberately not in this milestone

- **Instance persistence across a restart.** Still M5, still unshipped. A restart
  forgives the run rather than resuming it.
- **Affixes beyond ominous and fragile.** The door mechanic is built to take more;
  which ones is a content decision after play.
- **Death costing anything.** Under a wall clock, dying already costs the walk
  back. A death penalty on top is double-charging the same mistake.

## Shipped

T1–T16 of `IMPLEMENTATION_PLAN_U8.md` landed in one session; **T17 (deleting
the U3 rollback path) landed in the following session**, on an explicit
go-ahead. T19's headless half ran; its client half could not.

**Measured, headless, on a fresh `run/`:**

| Check | Result |
|---|---|
| `gradlew build` | all six tests pass (`DoorMaskTest`, `PlanSelectorTest`, `PipelineProofTest`, `DifficultyProfileTest`, `PayoutMathTest`, `KeystoneMathTest`) |
| `admin gentemplates` | writes all 16 `.nbt` files, including the two new ones, cleanly |
| `admin manifest reload` | **14 loaded, 0 rejected** — unchanged, as T9 requires: neither new room carries `dungeon_room` metadata |
| `admin coverage` | all 53 `(mask, role)` pairs satisfied — unchanged |
| `admin plansurvey 200` | 200/200 — unchanged |
| `admin stamptest` | all four rotations OK |
| `/place template pocketdungeons:rooms/reward_hall` and `.../selector_room` | both load cleanly from a bare console command with no errors or warnings |
| `admin build 4242 5 false` | keystone level 5, tier 2, seed reported, builds and reports correctly |
| `admin purge` | slot returns cleanly |
| config round-trip | deleting `pocketdungeons.json` and rebooting regenerates exactly the surviving fields (`rewardRoomGraceSeconds`, `threeChestPercent`, `twoChestPercent`, `timedOutDepletion` present; every deleted field absent), `keystoneItem` defaults to `minecraft:recovery_compass` |

**What could not be verified headlessly:** every behaviour that needs a player
to actually walk somewhere or click something -- free re-entry teleporting a
real player back into a live instance, the reward room's teleport and its
chest count against a real elapsed clock, the selector room's door-click
interception, `/dungeon choose` end to end, a real timeout firing while a
player is genuinely offline, and the room-visited counter (T2) advancing as
someone walks between cells. All of these are now in
`CLIENT_TEST_CHECKLIST.md` §36 and §44–49, unverified.

**T17, measured headless after landing:** `admin build` with no keystone
(`keystoneLevel 0`) now stamps trial spawners and vaults exactly like a
keystone run -- confirmed via `admin cellreport`, which shows a real
`trial_spawner` in an encounter cell of a keystoneless build. There is no
longer a second code path to diverge from it. `gradlew build` stays green (all
six tests, `DifficultyProfileTest` now covering only the tier curve),
`admin manifest reload`/`coverage`/`plansurvey 200`/`stamptest` are all
unchanged, and the config round-trips again with `baseMobsPerEncounter`,
`maxMobsPerRoom`, `spawnerDensEnabled` and `trialsEnabled` gone from both the
regenerated file and the checked-in `config/pocketdungeons.default.json`
(updated to match -- it had drifted since before U6/U7 and was still missing
those milestones' fields entirely before this pass).

**Deviations from `IMPLEMENTATION_PLAN_U8.md`, and why:**

1. **The reward room's and selector room's exact-position door/pad math is not
   read back out of the template at stamp time**, unlike every planner-placed
   room. Both rooms are always stamped at a fixed offset and rotation `0`, so
   there is no rotation to transform against -- cross-cutting section 3's rule
   ("read positions back out of the placed template") only exists to survive
   rotation, and it is safe to compute these positions directly from the
   record's own origin for the same reason `TrialContent.applyChoiceVaults`
   already did for the (now-deleted) exit-room vaults.
2. **`record.paid` (the old completion-payment guard) was deleted rather than
   kept alongside the new chest system.** T8 says to delete `Payout.grant` and
   its caller `Instances.pay`; once `pay()` was gone, nothing read `record.paid`
   any more -- `record.completed` already guards a member completing more than
   once, since `onTick` only calls `completeRun` when `!record.completed.contains(member)`.
3. **`PayoutMath` kept the chest-count arithmetic**, per T8's explicit "your
   call, but say which."
4. **`RoomContent.apply`'s `depth` parameter is now completely unused.** It fed
   the U3 rollback branch's `effectiveTier`/`mobCount` calls, both deleted in
   T17. Left in the signature rather than threading its removal back through
   `LayoutStamper` and `DungeonPlan.depths()` -- `depths()` itself is still
   needed there for `RoomSelector`'s `minDepth` room filtering, so only the one
   now-dead parameter on `RoomContent.apply` is affected, and T17 does not ask
   for a signature change.
5. **`ominous_plain_key.json` was provably dead**, not just "may be redundant"
   (T16's open decision #4) -- with the depth ramp gone,
   `TrialContent.ominousConfigId`'s cell-is-ominous-but-run-is-not branch could
   no longer occur. **Decided (delete):** the three JSON files are gone,
   `ominousConfigId` is gone, and `TrialContent.applyEncounter`/`applyLoot`
   collapsed their now-redundant `ominous`/`ominousRun` pair down to one
   boolean while at it, since the two had been identical since T16.
6. **A late completion (finished after the clock, chests already at zero)
   now depletes the keystone**, closing the gap between this document's own
   Stage 2 table ("over the clock: 0 chests -- *and the key delevels*") and
   `IMPLEMENTATION_PLAN_U8.md`'s T4a/T10, which specified the chest side of
   that row but never the delevel arithmetic. **Decided:** deplete by
   `lateCompletionDepletion` (new config field, default 2, `>= 0`), floored at
   1 and doubled by Fragile exactly like `timedOutDepletion` -- via a third
   `Keystones.Outcome` (`LATE`, alongside `TIMED_OUT` and `NO_CHANGE`). The
   door offer that follows is computed from the *post-depletion* level, which
   is the intended mitigation: reaching the door at all turns a `-2` penalty
   into a net `-1` even at the cheapest (`+1`, no-affix) offer. Settled through
   the existing `returnKeystone`/`keystoneReturned` machinery so a later
   `exit()` from the reward room cannot re-write the depleted level back to
   what the run started at.
