# Pocket Dungeons — build plan

**Mod id** `pocketdungeons` · **jar** `MrPinoys_Pocket_Dungeons-0.1.0.jar` · **entry** `/dungeon`

Server-side only, vanilla clients unmodified, same 26.2 / loader 0.19.3 / Loom 1.17 /
Gradle 9.5.1 baseline as the rest of the suite. Built against the technical spec
("On-Demand Dungeon Instances", v2); section references below are to that document.

---

## M0 — fixed dungeon, end to end ✅ *shipped*

Four rooms in a line: skeleton, zombie, chest with one diamond, exit pad.
No generation, no templates, no persistence. The point is to prove the pipeline
that every later milestone hangs off — dimension, slot allocation, force-loading,
stamping, teleport in, teardown, teleport out — before any of it has to be
procedural.

| Piece | File | Status |
|---|---|---|
| Void dimension | `data/pocketdungeons/dimension/void.json` | loads as `pocketdungeons:void` |
| Grid geometry, sealed cells, fixed door slots (§6.1) | `RoomBuilder.java` | verified in-world |
| The four-room layout and its contents | `StaticLayout.java` | verified in-world |
| Slot grid + free-list (§9), force-loading (§8.2), teardown (§12) | `Instances.java` | verified in-world |
| Lifecycle: disconnect, rejoin-after-purge, left-the-dimension, void guard (§11) | `Instances.java` | client-tested, one bug found and fixed |
| Death rescue — a killing blow ejects instead of killing, inventory intact | `Instances.java` | written, needs a client walkthrough |
| `/dungeon`, `/dungeon exit`, `/extract`, `/dungeon admin {list,build,purge}` (§10) | `DungeonCommands.java` | admin verified from console |

**Verified headlessly** by driving the server console: floor, ceiling, walls,
both sides of a doorway, wall beside the doorway, chest present with exactly one
diamond in slot 0, lodestone exit pad, skeleton and zombie alive and persistent,
`admin list` reporting the slot, `admin purge` clearing every block and every
non-player entity, slot returned to the free-list.

**Client-tested, most paths clean.** Teleport in, exit-pad trigger, `/dungeon
exit`, and death rescue all confirmed working. Party invite/join is the one
remaining path not yet exercised with a real second player.

**Bug found and fixed via client testing: disconnect-while-inside corrupted
the next login.** Disconnecting inside a dungeon correctly ejected the player
on reconnect, but the *following* `/dungeon` call hung the client on
"Loading terrain..." forever. Server log at the time of the hang:

```
Entity ServerPlayer[...] removed=CHANGED_DIMENSION ... wasn't found in
section SectionPos{...} (destroying due to UNLOADED_WITH_PLAYER)
```

Root cause: `teardown`'s "never leave a stray player behind" safety net
(§12) scans the instance bounds for any `ServerPlayer` and teleports them to
world spawn. The `DISCONNECT` handler triggers this same teardown path via
`dropMember` → `closeIfEmpty` → `purge`, synchronously, while the
disconnecting player's own entity can still be physically present in those
bounds — Fabric's `DISCONNECT` event doesn't guarantee the entity has
already been removed from the level. The sweep then found *the player who
was in the middle of disconnecting* and issued a second, redundant
`teleportTo` on their connection while it was already tearing down. That
race is exactly what corrupts the entity's tracked-chunk-section bookkeeping
(the log line above), which then breaks the *next* session's initial chunk
send.

Fix: `dropMember`, `closeIfEmpty`, `purge`, and `teardown` now thread an
`excludeFromStraySweep` UUID through the chain, defaulting to `null`
everywhere except the disconnect path, which passes the departing member's
own UUID. Their connection handles their own exit; the safety net exists for
*other* stray players, not the one the teardown was triggered for. Verified
the normal admin build/purge path is unaffected (regression-tested
headlessly); the disconnect fix itself needs a real client disconnect to
close out — the mechanism is confirmed correct by code reading and matches
the observed log exactly, but hasn't yet been re-tested end-to-end.

**Second, deeper bug found immediately after: the disconnect fix above did
not fully resolve it.** After the fix, the player could no longer even log
back into the server at all -- every join hung on "Loading terrain..." -- and
separately, exiting a dungeon normally (not via disconnect) sometimes also
produced a stuck loading screen that resolved into a broken half-loaded state
(void rendering, but ambient sound from the player's real overworld
location; a subsequent manual teleport fixed it). Server log matching the
hang:

```
Fetching packet for removed entity ServerPlayer[...] removed=CHANGED_DIMENSION
```

**Root cause, more fundamental than the first fix:** every cross-dimension
move in this mod -- dungeon entry, exit, death-rescue ejection, disconnect
purge, world-spawn fallback -- called `Entity.teleportTo(ServerLevel, x, y,
z, Set<Relative>, yaw, pitch, boolean)` directly. That is a low-level
positioning primitive, not the mechanism a real dimension change is supposed
to go through. Vanilla portals and respawn use `TeleportTransition` via
`ServerPlayer.teleport(TeleportTransition)` instead, which runs the correct
client-side handshake (including guarding against acting on an
already-removed entity, confirmed by reading its bytecode -- it no-ops
cleanly if `isRemoved()`). Calling the raw primitive skips that handshake,
which is what left clients stuck waiting for a loading-screen signal that
never arrived, and in the worst case with the connection in a state where
the server was still trying to serve packets for an entity it had already
torn down.

Fix: `teleport(...)` and `sendToWorldSpawn(...)` -- the two helpers every
cross-dimension move in the mod already funneled through -- now build a
`TeleportTransition` and call `player.teleport(...)` instead of
`teleportTo(...)`. The one remaining `teleportTo` call left in the codebase
(the void-guard fallback in the tick watcher) is same-dimension only and
correctly left alone -- no dimension change, no loading screen involved.

**Second, independent layer of defense:** the JOIN-handler recovery path
(sending a player home if their dungeon closed while they were offline) used
to teleport synchronously, inside the `JOIN` event itself -- i.e. during the
connection handshake, racing the client's own initial dimension load
regardless of which teleport primitive is used. It now queues the recovery
and fires it `JOIN_RECOVERY_DELAY_TICKS` (20, one second) later via the tick
loop, after that initial load has had time to settle.

**Verified:** clean build, and the admin build/purge path (which doesn't
touch a player entity) still passes headlessly with no errors. **Not yet
verified:** the actual fix for the login hang and the exit-glitch, both of
which need your client. If the login hang persists even after this fix,
that would point to something deeper still -- possibly server-side player
data left in a bad state from the earlier broken sessions, which a
`TeleportTransition`-based fix can't retroactively repair. Worth knowing
before retesting: if you're still stuck loading in, check whether your
player's last-saved position is inside `pocketdungeons:void` at a slot that
no longer has a force-load ticket -- if so, that chunk still has to generate
fresh (cheap for an empty void, but worth ruling out as a separate issue
from the teleport-handshake bug above).

**M0 limitations, all deliberate:**

- Instances live in memory only. A restart loses them; the join handler catches
  the resulting orphan and sends the player home. `SavedData` is M5.
- Teardown clears in a single tick. Fine for 7,168 blocks, not for a 12×12 plan.
- No config file. Slot pitch, room size, and timers are constants.
- One layout, built from code. No `.nbt`, no planner, no seed.
- **Single player per instance.** Ownership is one UUID; see M4.5.

---

## M1 — templates instead of code ✅ *shipped*

Implemented by Windsurf per this plan, verified by me on a clean headless
dev server afterward:

- **Generator** (`RoomTemplateGenerator.java`) builds all five templates
  (`entrance_hall`, `encounter_zombie`, `loot_vault`, `exit_hall`, plus the
  `jig_room` reference) in a scratch region far outside the real slot grid,
  correctly deferring capture by one tick past spawning mobs/placing the
  chest — the timing trap flagged in this plan was avoided. Driven by
  `/dungeon admin gentemplates`, kept as a permanent dev command as planned.
- **Stamper** (`TemplateStamper.java`) loads the four dungeon templates via
  `StructureTemplateManager`, places them with `JigsawReplacementProcessor.
  INSTANCE`, then runs `JigsawFallback` regardless as the safety net.
  `StaticLayout.stamp` kept its exact signature, so `Instances.java` needed
  no changes at all — the plan's blast-radius containment held.
- **Verified on a clean world** (a stale prior test run gave a false-positive
  1024-jigsaw count the first time around — always start these checks from a
  fresh `run/` directory): floor/wall/ceiling/door geometry identical to M0,
  chest carries a real `loot_table` pointer instead of a hardcoded item,
  skeleton/zombie/exit pad all present, `/fill ... replace minecraft:jigsaw`
  across the *entire* instance volume found **zero** leftover jigsaw blocks,
  and `admin purge` still clears everything including entities with no
  stray drops.

**One more spec drift found, same shape as M0's `Items`-not-`items`:** the
chest's loot-table pointer field is `LootTable` (capital), not lowercase
`loot_table` as §5.1 states for 1.21.5+. Confirmed via `/data get block` on
a real placed chest: `{LootTable: "pocketdungeons:chests/tier_1", ...}`.
The mod code was already using the typed `RandomizableContainer.
setLootTable(ResourceKey)` API rather than raw NBT keys, so this only
matters for anyone hand-editing a chest's NBT directly during authoring —
worth remembering for §4/§5.1 documentation once real room authoring starts.

Also observed: a placed chest gets a non-zero `LootTableSeed` automatically
even though nothing in the mod ever calls `setLootTableSeed` — vanilla
assigns one itself. Harmless here since `tier_1`'s table has a single 100%
entry, but worth knowing before a room ever ships a table with real
randomness: don't assume an unset seed means "always rerolls identically" is
the only state a fresh chest can be in.

**Scope, deliberately narrow:** convert the *existing* M0 four-room dungeon
(entrance/skeleton, encounter/zombie, loot vault, exit) from `RoomBuilder`'s
block-by-block code into real `.nbt` structure templates placed through the
structure manager, with real jigsaw-block doorways per §4's authoring
convention. Same grid contract, same four rooms, same headless test suite —
the only thing that should change is *how* the geometry gets into the world.
Building out the spec's full ten-room library and the planner that picks among
them is M2/M3, after the template pipeline itself is proven.

Windsurf can execute this whole milestone without a Minecraft client: rather
than hand-authoring templates in a creative-mode sandbox (§4's normal
workflow), write a generator that builds each room programmatically — reusing
`RoomBuilder`'s existing geometry logic almost unchanged — into a scratch
region of the dev server's void dimension, captures it with
`StructureTemplate.fillFromWorld`, and saves it to disk as `.nbt`. That
produces real, spec-conformant template files with zero manual building.
Verified working end-to-end for this project's own marker round-trip test
earlier — same technique, just capturing more than one entity.

### Stage 1 — settle §13's one still-open assumption

Test whether `net.minecraft.world.level.levelgen.structure.templatesystem.
JigsawReplacementProcessor.INSTANCE`, added to a `StructurePlaceSettings` via
`addProcessor(...)`, actually converts a jigsaw block to its "Turns into"
state when placed via `StructureTemplate.placeInWorld` outside a real
worldgen jigsaw run. Confirmed present in the 26.2 jar with a public
`INSTANCE` singleton — untested behaviourally.

Write the mod's own fallback regardless (it's ~20 lines): after placement,
scan the placed bounds for `minecraft:jigsaw` blocks whose block entity's
`getName()` is `pocketdungeons:door`, read `getFinalState()`, and
`level.setBlock(pos, parsed-block-state, ...)` to replace it directly. Use
`JigsawReplacementProcessor.INSTANCE` if the test confirms it works (one line,
free); use the fallback pass either way as a safety net — cheap insurance
against a future MC version changing this vanilla behaviour.

### Stage 2 — room template generator

New class, e.g. `RoomTemplateGenerator.java`, driven by a temporary (or
permanent — see below) admin command. For each of the four M0 rooms:

1. Stamp the room's blocks into a scratch region of `pocketdungeons:void`
   (reuse `RoomBuilder.buildCell`'s floor/wall/ceiling/lamp logic verbatim).
2. At each door slot, place a `minecraft:jigsaw` block instead of carving
   air directly. Set its block entity via the confirmed API:
   `jigsaw.setName(Identifier.fromNamespaceAndPath("pocketdungeons", "door"))`,
   `jigsaw.setFinalState("minecraft:air")`. Orientation: `JigsawBlock.
   ORIENTATION` with a `FrontAndTop` value whose front matches the door's
   outward direction and top is `UP` (e.g. `FrontAndTop.NORTH_UP` for a north
   doorway) — confirmed these constants exist for all four cardinal fronts.
3. Place room content: skeleton/zombie via `EntityTypes.SKELETON/ZOMBIE.spawn`
   as `RoomBuilder` already does (harmless to capture as a normal entity,
   same as any structure-authored mob); the reward chest via
   `RandomizableContainerBlockEntity`'s `RandomizableContainer.setLootTable(
   ResourceKey.create(Registries.LOOT_TABLE, ...))` instead of a hardcoded
   `ItemStack` (see Stage 3); the lodestone exit pad as-is.
4. Capture: `new StructureTemplate().fillFromWorld(level, scratchOrigin,
   new Vec3i(16,7,16), true, List.of())` (`true` = include entities —
   confirmed this actually works, just remember the tick-timing trap: don't
   capture in the same tick you spawned the mobs/placed the chest; run
   the spawn and the capture as separate command executions, same fix used
   for the marker test).
5. Save: `structureTemplateManager.save(Identifier.fromNamespaceAndPath(
   "pocketdungeons", "rooms/<name>"))` after registering the template via
   `getOrCreate(id)` and copying the built template's data into it (or build
   directly against the instance `getOrCreate` returns, whichever the
   `StructureTemplateManager` API makes cleaner — check its exact shape,
   this wasn't verified in this session). Target files:
   `src/main/resources/data/pocketdungeons/structure/rooms/entrance_hall.nbt`,
   `encounter_zombie.nbt`, `loot_vault.nbt`, `exit_hall.nbt`.
6. Also generate one **jig room**: an empty 16x16 sealed box with jigsaw
   doors on all four sides and nothing else, saved as `rooms/jig_room.nbt` —
   not used by the dungeon itself, it's the reference template future room
   authors copy (per §4's explicit call for this).
7. Clear the scratch region after each capture (same programmatic clear
   `Instances.clear` already uses).

Note names deliberately diverge from the spec's example list
(`entrance_hall`, `corridor_i`, `junction_t`, etc.) where M0's actual four
rooms don't match those roles one-to-one — `encounter_zombie` and
`exit_hall` are pocketdungeons-specific for now; reconcile naming with the
spec's canonical set in M2 once the room manifest exists to carry a `roles`
field independent of the file name.

Whether to keep this generator as a permanent `/dungeon admin gentemplates`
command or delete it once the four files exist: **keep it.** Unlike the
marker round-trip test (pure diagnostic, discarded), this produces real
shipped assets, and it'll be needed again the moment room geometry changes or
M2 needs more templates. Guard it behind the existing `admin` permission node.

### Stage 3 — loot table

`src/main/resources/data/pocketdungeons/loot_table/chests/tier_1.json` — a
single guaranteed diamond, matching M0's exact contents so the existing
verification story (`/data get block` shows one diamond) still holds. Wire
the generator's chest step to `setLootTable(ResourceKey.create(
Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("pocketdungeons",
"chests/tier_1")))` per §5.1 — confirmed field name and predicate type this
session (`RandomizableContainer.LOOT_TABLE_TAG`/`setLootTable`).

### Stage 4 — the real stamper

New class, e.g. `TemplateStamper.java`, replacing `StaticLayout.stamp`'s call
into `RoomBuilder`. For each of the four cells:

1. `structureTemplateManager.get(Identifier.fromNamespaceAndPath(
   "pocketdungeons", "rooms/<name>"))` to load the template (falls back to
   `getOrCreate` semantics — check exact manager API).
2. Build `StructurePlaceSettings` — rotation `NONE` for all four cells (M0's
   layout has no rotation needs; §6.2's four-rotation precompute is M2's
   concern once room selection needs to satisfy an arbitrary door mask).
   `setIgnoreEntities(false)`. `addProcessor(JigsawReplacementProcessor.
   INSTANCE)` if Stage 1 confirmed it works.
3. `template.placeInWorld(level, cellOrigin, cellOrigin, settings,
   RandomSource.create(seed), <update flags>)`.
4. Run the Stage 1 fallback jigsaw-replacement pass over the placed bounds
   regardless of whether the vanilla processor also ran (idempotent — if the
   vanilla processor already converted the block, the fallback finds nothing
   and does nothing).

`Instances.java`'s force-loading, slot allocation, teardown, and lifecycle
code do **not** change — this milestone's blast radius is `RoomBuilder.java`
(deleted or reduced to just the generator's geometry helper) and
`StaticLayout.java` (now loads+places templates instead of building blocks
directly). `Instances.enter`/`adminBuild` still just calls
`StaticLayout.stamp(level, origin)` — same signature, new implementation
underneath.

### Stage 5 — verification

Re-run the exact M0 headless regression (same `execute if block`/`if entity`
checks used throughout this project): floor/wall/ceiling present, both sides
of each doorway open (now via jigsaw replacement, not direct carving —
**explicitly check no `minecraft:jigsaw` blocks remain anywhere in a built
instance**, since a leftover jigsaw block is exactly the failure Stage 1's
fallback exists to catch), chest present and its loot table produces exactly
one diamond, skeleton and zombie alive and persistent, lodestone exit pad
present, `admin purge` still clears everything including drop suppression.

Do **not** consider this milestone done until that full suite passes with
templates the same way it already passed with `RoomBuilder`'s raw code.

**Verified against the local 26.2 jar (javap, no wiki needed):**
- `lock` is confirmed an item predicate: `net.minecraft.world.LockCode` wraps a
  `net.minecraft.advancements.predicates.ItemPredicate` directly. The spec's
  §5.2 example is the right shape.
- `EquipmentSlot` constants: `MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY,
  SADDLE` — `BODY` and `SADDLE` are new since older equipment docs, relevant if
  a room ever arms a horse or camel.
- `BaseSpawner.SPAWN_DATA_TAG` exists as a named constant (not a bare string
  literal in call sites), consistent with the spec's `spawn_data` naming — not
  independently confirmed by literal string value, worth a quick in-game
  `/data get block` check on a real spawner before relying on it.
- **Confirmed (code-level round-trip test against the real API, not the GUI):**
  `minecraft:marker` entities survive `StructureTemplate.fillFromWorld(...,
  includeEntities=true, ...)` followed by `placeInWorld(...)`. Spawned a marker
  with `data:{dungeon_spawn:{entity:"pocketdungeons:test",role:"boss"}}`,
  captured it, discarded the original, placed the template 20 blocks away, and
  the marker reappeared at the correctly-transformed position with the `data`
  compound byte-for-byte intact. This is the exact mechanism the real stamper
  will use, so §5.5's marker-scan design is sound.

  **One sharp edge found along the way, worth remembering for the real
  stamper:** a freshly `addFreshEntity`'d entity is not immediately queryable
  via `Level.getEntitiesOfClass` — the level's spatial entity index only
  updates at a tick boundary. Capturing a structure (`fillFromWorld`) in the
  same tick an entity was added to the world will silently capture zero
  entities, no error. Not a concern for the real flow (room templates are
  authored well after their entities have settled), but a trap if the mod
  ever programmatically spawns-then-immediately-captures within a single tick.

  A manual Structure Block GUI attempt at this same test (see conversation)
  failed to reproduce the marker, most likely due to the "Include entities"
  toggle or the bounding box being set wrong by hand — GUI operator error, not
  a real limitation. The code-level test is the trustworthy one since it
  exercises the exact API path the mod will use, not a human-driven proxy.

## M2 — room manifest ✅ *shipped*

**Scope, deliberately narrow, same discipline as M1:** build the manifest —
door-mask derivation, per-room metadata, validation, indexing (§6.2, §6.3) —
against the four real templates M1 already produced. No new templates, no
multi-cell footprints (nothing in the current template set is bigger than
1×1), no wiring into a planner (that's M3, and it's the manifest's only
consumer, so building the manifest without a caller yet is intentional —
prove the index is correct in isolation first, same reasoning as M1 proving
placement before content).

**Scoped down from the spec on purpose:** §6.2 says derive the manifest "on
resource reload," implying a real `ResourceReloadListener` synced with data
pack `/reload`. That's real complexity (reload ordering against vanilla's own
structure-template reload, `IdentifiableResourceReloadListener` dependency
declarations) that has nothing to do with proving the door-mask math is
correct. Build the manifest as an explicit, re-runnable load callable from an
admin command instead — matches how every milestone so far has been verified
headlessly. Wiring it to fire automatically on `/reload` is a small follow-up
once the load logic itself is trusted; don't block on it now.

### Stage 1 — `DoorMask`: pure bit math, no Minecraft types

New file, zero Minecraft imports (same discipline as `ballot/core` — a stray
Minecraft import should fail to compile, not just fail review). A door mask
is a 4-bit int: bit 0 = door on the north edge, bit 1 = east, bit 2 = south,
bit 3 = west (this bit order matters for the rotation math below — keep it).

- `int rotateClockwise(int mask, int quarterTurns)` — physically rotating a
  placed structure 90° clockwise moves whatever was its north wall to where
  its east wall used to be. With the bit order above that's a 4-bit cyclic
  *left* rotate: `((mask << quarterTurns) | (mask >>> (4 - quarterTurns))) &
  0xF`, `quarterTurns` in `0..3`. Precompute `[rotateClockwise(mask, 0..3)]`
  once per room at load time — this is §6.2 step 4's "near-free" rotation
  set.
- `int fromEdges(Set<Direction> edges)` and `boolean hasEdge(int mask,
  Direction edge)` — small helpers, `Direction` here can stay a Minecraft
  type if it's more convenient than reinventing a 4-value enum; use your
  judgement, but keep the actual bit-manipulation methods free of any
  Minecraft dependency so they're unit-testable the way `ballot/core` is
  (`javac -d build $(find ...)`, no Gradle, no Minecraft classpath).

### Stage 2 — room metadata

`DungeonRoomMeta.java`: a plain data holder matching §6.3's JSON shape
exactly — `template` (structure id string), `footprint` (two ints, default
`[1,1]`), `roles` (list of strings), `weight` (int, default 1), `minDepth`
(int, default 0), `maxPerDungeon` (int, default -1 for unlimited),
`processors` (nullable string). Gson-based `fromJson(JsonObject)` parser,
same style as `PocketDungeonsConfig`'s JSON handling — no Minecraft imports
needed here either, this is just data.

Write the four real metadata files, one per M1 template (skip `jig_room` —
it's the authoring reference, never selected, no metadata):

```
data/pocketdungeons/dungeon_room/entrance_hall.json
data/pocketdungeons/dungeon_room/encounter_zombie.json
data/pocketdungeons/dungeon_room/loot_vault.json
data/pocketdungeons/dungeon_room/exit_hall.json
```

Roles: `entrance_hall` → `["entrance"]`, `encounter_zombie` → `["encounter"]`,
`loot_vault` → `["loot"]`, `exit_hall` → `["exit"]` (`"exit"` is a
pocketdungeons-specific role, not in the spec's example list — the spec's
room set doesn't have a dedicated exit room, M0/M1's does). `footprint` is
`[1,1]` for all four. `weight` can just be `1` uniformly for now — nothing
selects by weight until M3 exists.

### Stage 3 — connector derivation

`RoomManifest.java` — the loader and index. Per room, given its structure
id:

1. `structureTemplateManager.get(id)` (confirmed API, already used in
   `TemplateStamper`) to load the `.nbt`.
2. `template.getJigsaws(BlockPos.ZERO, Rotation.NONE)` — confirmed to exist
   and to be exactly the right tool: it returns `JigsawBlockInfo` records
   (`.info().pos()`, `.info().state()`, `.name()`) already palette-resolved,
   with `pos=ZERO, rotation=NONE` giving the template's raw authored
   coordinates. No manual raw-block-list scanning needed.
3. Keep only jigsaws whose `.name()` equals `Identifier.fromNamespaceAndPath(
   "pocketdungeons", "door")` — filters out any future non-door jigsaw use
   (`pocketdungeons:gate`, `pocketdungeons:secret`, per §4's "Name" field
   note) from door-mask derivation without treating them as errors.
4. For each surviving jigsaw, derive its edge the same way
   `RoomTemplateGenerator.wallDirection(x, z)` already does (x==0 → WEST,
   x==15 → EAST, z==0 → NORTH, z==15 → SOUTH, for a 16-wide single-cell
   room) — don't reimplement this, factor `wallDirection` out of
   `RoomTemplateGenerator` into somewhere both classes can call it, or
   duplicate the four-line switch verbatim with a comment pointing at the
   original. Cross-check: the jigsaw's `JigsawBlock.getFrontFacing(state)`
   should equal the derived edge direction — if it doesn't, that's an
   authoring mistake (jigsaw placed on the right wall but facing the wrong
   way) and belongs in the "reject loudly" pile below, not silently
   corrected.
5. **Validate canonical door slots, matching §6.2's explicit requirement.**
   For each edge that has *any* qualifying jigsaw, all 6 canonical positions
   for that edge (2 wide × 3 tall, the same `DOOR_MIN..DOOR_MAX`/
   `DOOR_HEIGHT` constants `RoomTemplateGenerator` already uses) must be
   jigsaw blocks with matching name and orientation. A partial door (some
   slots jigsaw, some not) is a load-time error for that room: log loudly,
   **exclude the room from the index**, keep loading the rest of the
   manifest. One malformed room must never take down every other room's
   availability.
6. Build the door mask from validated edges via `DoorMask.fromEdges(...)`,
   precompute all four rotations.
7. Merge with the room's `DungeonRoomMeta` (Stage 2). Index by
   `(footprint, mask-at-rotation-0, roles)` in a `Map`-based structure —
   exact key shape is an implementation choice, but `RoomManifest` needs at
   minimum a query method shaped like "give me rooms matching this required
   door mask, optionally filtered by role" for M3 to call later, since that
   query is the entire reason this milestone exists.

### Stage 4 — admin surface and verification

`/dungeon admin manifest reload` — runs the Stage 3 load, reports room count
loaded and room count rejected (with reasons) in chat/console.
`/dungeon admin manifest list` — one line per loaded room: name, footprint,
door mask at all 4 rotations (print as compass letters, e.g. `E`, `WE`, `W`,
`NESW`, not raw ints — this is a debugging tool, make it readable), roles.

Verify headlessly, same discipline as every prior milestone:

- `manifest reload` against the real four templates loads all four, zero
  rejected. `manifest list` shows the expected masks: `entrance_hall` → `E`
  only at rotation 0; `encounter_zombie` and `loot_vault` → `WE`;
  `exit_hall` → `W`. Confirm the four-rotation precompute is actually
  correct by checking `entrance_hall`'s mask at each of the 4 rotations by
  hand against what a 90°-turned door should read (`E`→`S`→`W`→`N` as
  rotation goes 0→90→180→270, given the bit convention in Stage 1 — if your
  implementation doesn't match this, the bit-order convention or the
  rotation direction is backwards, fix it before building anything on top).
- **Deliberately break one room and confirm it's rejected, not silently
  wrong.** Easiest reproducible way: temporarily point `loot_vault.json`'s
  `template` field at a nonexistent identifier, confirm `manifest reload`
  logs a clear error and `manifest list` shows 3 rooms, not 4 (or a crash).
  Then revert. If you can cheaply construct a template with a genuinely
  malformed door (partial slot) do that too — a missing template is the easy
  failure mode to test, a malformed door is the one the spec specifically
  calls out as worth reject-loudly discipline for, so it's worth proving
  both paths if the time cost is low.

Do not consider this milestone done until `manifest list`'s output for the
four real rooms is hand-verified correct, and the deliberate-breakage test
shows a clean rejection with the rest of the manifest still loading.

## M3 — layout planner ✅ *shipped*

Both halves delivered and integrated. `LayoutGraphGenerator` (graph shape),
`RoomSelector`/`DungeonPlan`/`PlanRenderer` (resolution + rendering),
`LayoutPlanner` (retry glue, mine), `/dungeon admin plan <seed>` and
`/dungeon admin plansurvey <count>`.

**The planner works. The room library does not yet support it — measured, not
guessed:**

- Against the four real rooms: **0 of 200 seeds** produce a buildable dungeon
  (3200 attempts, full retry budget each). Failures are exactly the expected
  kind — no room satisfies some cell's required door mask.
- Against a synthetic library covering all 16 masks, the *same planner code*
  scores **200 of 200, first attempt every time** (`PipelineProofTest`). So
  the pipeline is correct end to end and the shortfall is content.

**Exactly what content is missing** (census over 3157 cells from 200 generated
shapes — only 40.5% of cells have a mask any current room can satisfy):

| Needed mask | Cells | Have a room? |
|---|---|---|
| `E` `W` `N` `S` (dead ends) | 772 | only as `entrance`/`exit` roles |
| `EW` `NS` (corridors) | 507 | yes |
| `NE` `ES` `SW` `NW` (corners) | 1028 | **no** |
| `NES` `ESW` `NSW` `NEW` (T-junctions) | 740 | **no** |
| `NESW` (crossroads) | 110 | **no** |

Because a plan needs *every* cell to resolve, a 40.5% per-cell rate over ~16
cells is what produces the 0% whole-plan rate.

**Rotation makes this cheap to fix:** a mask's four rotations cover all four
of its rotational variants, so **three new templates** — one corner, one
T-junction, one crossroads — cover 9 of the 10 missing masks. Add a dead-end
template carrying `encounter`/`loot` roles (the existing 1-door rooms are
locked to `entrance`/`exit`) and coverage is complete. That is the real
prerequisite for M4, and it is content work, not planner work.

**One correctness bug found and fixed during review.** `queryAnyRotation`
matched a room whose mask was a *superset* of the cell's requirement. Under
§6.1's contract every room owns its own walls, so a room with more doors than
its cell needs punches a 2x3 hole through an outer wall into open void —
§7.4's "no orphan connectors", the failure the spec describes as "a visible
hole in a wall". Now matches exactly. The spec does permit the alternative
(over-select, then have a finalize pass wall off leftovers) but explicitly
prefers the planner solving it, and no finalize pass exists. The now-dead
`query(int, String)` overload, which had the same flaw and zero callers, was
removed rather than left as a trap.

**Deliberately not done:** `/dungeon` still builds the fixed four-room
dungeon. Wiring procedural plans into live play needs the tick-budgeted
stamper that places arbitrary cells at arbitrary rotations — that is M4, and
`LayoutPlanner` reports failure honestly rather than silently substituting
`StaticLayout` so M4 can decide the fallback policy itself.

**Verified:** `gradlew build` runs `DoorMaskTest`, `PlanSelectorTest`, and
`PipelineProofTest`; `/dungeon admin plan` and `plansurvey` exercised on a
live dev server; the M0/M1 build+purge regression re-run afterward (geometry
intact, zero leftover jigsaw blocks, clean teardown) to confirm no regression.

---

**Original split for two parallel agents, at the natural seam in §7.2's
algorithm:**
graph *shape* (steps 2-5: critical path, branches, loops, role assignment) is
a pure graph problem with no dependency on which actual rooms exist; *content
resolution* (steps 6-8: door masks, template selection against
`RoomManifest`, gating, validation) is entirely about turning a shape into
real rooms. The hand-off contract between them —
`PlanCell.java`, `PlanEdge.java`, `DungeonShape.java` — already exists,
written and verified (compiles standalone with plain `javac`, no Minecraft
classpath, same discipline as `DoorMask`) ahead of either agent starting, so
neither has to invent it or wait on the other.

**Known scope limit, accepted deliberately:** the room library has exactly
four templates — `entrance` (E), `encounter`/`loot` (both WE, interchangeable
for selection purposes), `exit` (W). No junction/T/X room exists yet (§7.3
calls for "at least one omni-directional junction template as the guaranteed
fallback" — that's future content work, not in scope here). This means
**any branch or loop cell needing a 3- or 4-way mask will fail template
selection**, and that's fine — the retry-with-new-seed and eventual
fallback-to-`StaticLayout` machinery (§7.2 step 8) is supposed to handle
exactly this, and proving it works *under real, frequent failure* is a more
rigorous test than if selection always trivially succeeded. `/dungeon admin
plan <seed>` existing is what makes a failing seed's exact cause legible
instead of a mystery.

**Retry loop and final integration is mine to write**, once both halves
report back — it's ~20 lines of glue (call shape generation, validate, call
resolution, validate, retry with `seed+1` up to a budget, fall back to
`StaticLayout.stamp` if the budget's exhausted) and touching it needs both
halves to already exist, so there's no parallel work left to hand out there.

### Agent A — graph shape

Owns: `LayoutGraphGenerator.java` (new). Consumes only `PlanCell`/`PlanEdge`
— no `RoomManifest`, no Minecraft types anywhere in this file.

- `static DungeonShape generate(long seed, int minPathLength, int maxPathLength)`
  — deterministic RNG from `seed` (`java.util.Random` is fine, this is pure
  logic). Self-avoiding random walk on the cell grid from `(0,0)` for the
  critical path, length in `[minPathLength, maxPathLength]` (spec's v1
  default is 8–12 — use that as the caller's default, don't hardcode it
  inside `generate`). Backtrack on dead ends; if the walk can't reach the
  target length after reasonable backtracking, that's a shape-generation
  failure the caller retries with a different seed — return `null` or throw,
  your call, just document which.
- Attach branch spurs off path cells (depth 1-3), weighted toward the middle
  of the path, at some configurable probability.
- Add loop edges between adjacent-but-unconnected cells at some configurable
  probability — this is what makes a layout not a tree, don't skip it even
  though the room library can't yet build most of what it produces (see
  scope note above).
- Role assignment: `entrance` at path cell 0, `exit` at the terminal path
  cell, the rest of the path and every branch cell gets `encounter` or
  `loot` (both satisfy the same WE mask — split roughly evenly, weight
  doesn't matter yet since both current templates have weight 1).
- `static List<String> validate(DungeonShape shape)` — returns problem
  descriptions, empty list means valid. Cover: every edge's both cells exist
  in `shape.cells()` (no orphan connectors); BFS from `entrance` reaches
  every cell in `shape.cells()` (reachability); every cell in `criticalPath`
  is actually adjacent to the next (path integrity). "No overlap" is
  structurally guaranteed by `cells` being a `Set<PlanCell>` — still worth a
  one-line comment saying so rather than silently skipping it, so nobody
  wonders later why there's no explicit check.

Do not touch `RoomManifest.java`, `DungeonCommands.java`, or any file outside
`LayoutGraphGenerator.java` plus whatever small test harness you want for
your own verification (a plain-`javac` runnable check, matching how
`DoorMask` was verified, is the right bar — no Gradle needed for this file).

### Agent B — content resolution, validation, and the debug renderer

Owns: `RoomSelector.java`, `PlanRenderer.java` (new files), plus additive
changes to `RoomManifest.java` (extend `query` — see below) and
`DungeonCommands.java` (the new `/dungeon admin plan <seed>` subcommand).
Depends on `RoomManifest` (M2, already shipped) and the `DungeonShape`
contract (already written — see files listed above) but **not** on
`LayoutGraphGenerator`'s actual code; build and test against a hand-built
`DungeonShape` fixture (a handful of cells/edges/roles constructed directly
in a test, no need to wait for Agent A to finish).

- `RoomManifest.query(int, String)` currently only checks a room's mask *at
  rotation 0*. Extend it (new overload, e.g.
  `queryAnyRotation(int requiredMask, String role)` returning matches paired
  with which rotation quarter-turn satisfied them, via
  `Entry.maskAtRotation(int)` which already exists) — don't break the
  existing `query` signature, nothing calls it yet but no reason to churn it
  unnecessarily.
- Design and build `DungeonPlan.java` yourself — final resolved output,
  roughly: seed, `Map<PlanCell, PlacedRoom>` (room name + rotation),
  `Set<PlanEdge> doors`, entrance/terminal cells, critical path, plus
  whatever gating fields you build (see below). This is the one piece of
  the contract not pre-specified — you're both its sole producer and sole
  consumer this milestone, so use your judgement, but keep it a plain data
  record with no Minecraft imports, matching everything else in this
  milestone.
- `RoomSelector.resolve(DungeonShape, RoomManifest)` → `Optional<DungeonPlan>`.
  For each cell: compute its required door mask from `shape.openEdges()`
  (use `PlanCell.directionTo` + `DoorMask.fromEdges` — both already exist,
  don't reimplement edge-to-compass-direction mapping). Query the manifest
  for a room matching `(mask at some rotation, cell's role)`. If nothing
  matches for *any* cell, resolution fails — return `Optional.empty()`, the
  top-level retry loop (mine) handles it from there. Record the rotation
  that satisfied each match.
- Gating: keep this genuinely light for M3. No template currently has a
  `lock` block or a key item (that's real content authoring, not built
  yet), so there's nothing in-game to actually enforce a lock even if the
  plan records one. Pick at most one edge on the critical path as a
  placeholder gate and one upstream cell as the key location, store it in
  `DungeonPlan`, and don't spend more effort than that — it's data-only
  scaffolding for when real lock/key content exists, not a feature anyone
  can experience yet.
- `RoomSelector.validate(DungeonPlan)` → problem list, same shape as Agent
  A's `validate`. Cover: solvability (BFS from entrance, boss/terminal cell
  reachable — trivial with the lightweight gating above, but write the
  actual BFS-with-keys algorithm correctly since it's what real gating will
  need later, don't just return "always solvable"); budget (total room
  count within some sane min/max you choose, doesn't need to be
  config-driven yet).
- `PlanRenderer.renderAscii(DungeonPlan)` → `String`. A grid of characters,
  one row per `z`, showing each occupied cell's role initial (`e`ntrance,
  `n`(encounter), `l`oot, `x`it) and door connections between adjacent
  cells (e.g. a `-` or `|` between cells that have an open edge, blank
  where they don't). This is a debugging tool — legibility in a chat/console
  window matters more than elegance. Also render the **failure** case:
  if resolution failed, show the shape with a marker on whichever cell
  couldn't find a matching room and what mask/role it needed — that's the
  actual point of this command per the milestone's design (§7.3's fallback
  reasoning: "almost every layout bug is obvious in text and miserable
  in-world").
- `/dungeon admin plan <seed>` in `DungeonCommands.java`: runs shape
  generation + resolution for the given seed (no retry — a single seed's
  outcome, success or failure, is exactly what this command is for) and
  prints the ASCII render. Does not stamp anything.

Verify headlessly per this project's usual pattern: try a handful of seeds,
confirm straight/no-branch shapes resolve successfully and render correctly,
confirm a shape with a branch or loop cell *fails* resolution in a legible
way (this is the expected, correct outcome given the scope limit above — a
seed that always succeeds despite having branches would actually suggest
something's wrong, e.g. branches aren't really being generated).

## M4 — real stamper

Tick-budgeted placement, entrance first, processor lists, block-update
suppression, a single queue with a global per-tick block budget so three
concurrent `/dungeon` calls don't stack (§8.3). Marker resolution and the
finalize pass (§8.4).

**Amendment to §8.2:** force-load tickets must be owned by the `InstanceRecord`
and released on every path that frees the slot, including the abort paths. A
leaked ticket pins 144 chunks for the server's lifetime. M0 already routes every
release through one `teardown`.

## M4.5 — parties ✅ *shipped*

`InstanceRecord` holds a member map (UUID → `ReturnPoint`) instead of a single
owner. `/dungeon invite <player>` (2-minute TTL, `maxPartyMembers` cap) and
`/dungeon join <leader>` bring people in; the instance stays open until the
*last* member leaves; death-rescue and the void guard apply per member;
teardown ejects each member to their own recorded return point. Verified by
build/purge headless regression only — the actual invite/join handshake between
two real clients is still on the client-test checklist, not yet walked through.

Trial spawners (§5.3) already scale to player count, so the content side of
co-op needs no extra work now that membership exists.

## M5 — persistence and lifecycle

`SavedData` on the void dimension, the `ORPHANED` grace timer, startup sweep,
idle timeout, death interception, disconnect-during-stamp abort (§11).

## M6 — content

Loot tables, trial spawners, classic spawners, marker-driven boss summons, keys
and locked gates driven by the planner's solvability guarantee (§5, §7.4).

## M7 — teardown at scale

Tick-spread programmatic clear.

**Amendment to §12:** the spec prefers deleting region-file chunks and letting
them regenerate, but there is no supported way to drop a chunk from a live
region file while the dimension is loaded. The programmatic clear is the one
that ships; region deletion is viable only as an offline sweep at startup.
Budget accordingly.

## M8 — config and tuning ✅ *shipped (partial)*

`PocketDungeonsConfig.java` on the suite's `readOrCreate` contract (missing →
write defaults; unparseable → defaults in memory, file untouched), same shape
as `RehomeConfig`. Currently exposes `slotPitch`, `slotsPerRow`,
`watchIntervalTicks`, `voidGuardDepth`, `maxPartyMembers`, `inviteTtlSeconds` —
wired into `Instances.java` and verified to actually govern slot placement
(edited the config to `slotPitch: 4096, slotsPerRow: 2` and confirmed the
second admin-built slot landed at x=4096 and the third wrapped to z=4096).

Still hardcoded, to move here once the planner exists: path length, room-count
bounds, density curves, per-tick block budget.

## M9 — the ritual (§15)

Only once M1–M8 are stable. A `minecraft:consumable` component with a long
`consume_seconds` gives hold detection, the vanilla hold animation, and clean
cancellation for free, and its duration is a genuine loading screen for the
tick-budgeted stamp. `/dungeon` stays as the op/debug entry point.

---

## Open decisions

1. **Is `/dungeon [seed]` a player feature or op-only?** Shared seeds are a real
   social hook; they also let players farm a known-good loot layout.
2. **Items dropped inside are lost at teardown.** M0 warns on entry. The
   alternatives are ejecting inventory on death or a grace chest at the return
   point.
3. **Does combat block the ritual entirely,** or merely interrupt it?
