---
name: room-building
description: Author new Pocket Dungeons rooms (dungeon cells) end to end. Covers the Java RoomSpec route, generating the .nbt template through a throwaway gametest, writing the dungeon_room manifest JSON, node and ore placement, final-floor rooms, theme re-skinning, door lanes, spawner and chest rules, and the checks to run. Use when asked to design, add, upgrade or fix a room, a room variation, or a final-floor room.
---

# Building a Pocket Dungeons room

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`. Read this whole file
before writing a room; it replaces rereading `RoomTemplateGenerator`, `NodeStamper` and
`RoomEligibility` (about 2000 lines).

## The three pieces of a room

1. **A Java `RoomSpec`** (name, door set, chests, spawn points, a `decor` lambda that places blocks).
   Lists live in `*Specs.java` files and are concatenated in `RoomTemplateGenerator.specs()`.
   A new spec file must be added to that list.
2. **A template** `src/main/resources/data/pocketdungeons/structure/rooms/<name>.nbt`, captured from
   the spec by `RoomTemplateGenerator`. The `.nbt` is generated, never hand-edited; an edited spec
   needs the template regenerated and committed. The manifest loader derives each room's door mask
   from the `.nbt` jigsaws, so a manifest without its `.nbt` fails to load.
3. **A manifest** `src/main/resources/data/pocketdungeons/dungeon_room/<name>.json` (hand written).
   Copy a neighbour: `mineshaft_seam.json` (nodes, narrow, dim), `lush_hollow.json` (wide, four
   doors), `cow_pens.json` (a `content` handler room).

Alternative to the Java route: `tools/room-editor` (browser editor, `Room Editor.cmd`) edits the
`.nbt` and JSON directly and validates against the game's rules. Use it for hand-tuned rooms; use the
Java route when a room is mostly symmetric fills, because it is reviewable in a diff.

## Coordinates and the cell

- A cell is 16 x 16. Floor row y=0 and ceiling row y=6 are the **shell** (immutable to players, but a
  template may place anything there). Walls are x or z = 0 and 15. Interior: x, z in 1..14, y in 1..5.
- Rooms are authored at rotation 0 and rotated by the stamper. Write in local coordinates only.
- Doors: a wall carries a door when its `Direction` is in the spec's set (`WEST` x=0, `EAST` x=15,
  `NORTH` z=0, `SOUTH` z=15). The opening is 2 wide at index 7..8, 3 high (y 1..3). The generator
  puts jigsaw blocks there and erases them at stamp time.
- **Door lane rule:** keep x in 7..8 clear for z in 1..3 and 12..14 (north and south doors) and z in
  7..8 clear for x in 1..3 and 12..14 (west and east doors). The middle is free, but every door must
  stay connected to every other door by a walkable route with 2 blocks of headroom.
- Mask shapes the planner can ask for: dead end (1 door), straight (2 opposite), corner, tee, cross.
  A room only competes for cells of its own mask, so a rarer mask means the room appears less often.

## Placeholder blocks and theme re-skinning

Each dungeon's `worldgen/processor_list/theme_<dungeon>.json` rewrites four placeholder blocks, so
author structure with these and the room matches whatever dungeon draws it:

| Placeholder (`RoomDsl`) | Block | Becomes, for example |
|---|---|---|
| `WALL` | stone_bricks | bone block (ossuary), packed ice (frostworks), cut copper (copper works) |
| `FLOOR` | polished_andesite | smooth stone, snow block, copper block |
| `ACCENT` | mossy_stone_bricks | polished blackstone bricks, blue ice, oak planks |
| `LAMP` | sea_lantern | soul lantern, glowstone, lantern, end rod |

Any other block is placed as written (a direct `STONE`, `COBBLESTONE`, `BLUE_ICE`, ore, plant). Use
direct blocks for set pieces that must look the same in every dungeon, placeholders for bulk
structure. A manifest's `processors` field overrides the run theme (the grove rooms use
`theme_grove` and `theme_rootworks_grove`).

Do not place lava (Molten is the only lava source by design), unpowered redstone lamps (they turn
off), or lit furnaces (they go out). Ice melts under light 12+; use packed or blue ice. Sand and
gravel need a solid block below. Candles, copper bulbs (`CopperBulbBlock.LIT`), campfires and
glowstone stay lit. Colored blocks use collection accessors in 26.2 (`Blocks.CONCRETE.black()`).

## Content roles, spawners and chests

- `roles` in the manifest says which jobs a room can fill: `encounter`, `loot`, `corridor`
  (a `corridor` room gets no content). Most themed rooms list all three and author both a chest and
  spawn points; `RoomContent` uses whichever the cell's role needs.
- **Encounter:** the trial spawner goes at the spawn point with the smallest local x (then z, then y)
  *after rotation*, using the run theme's spawner config. So keep every spawn point on open floor with
  air around it, and do not rely on which one wins. Chests are removed in encounter and corridor cells.
- A `content` id with a registered `Situations` handler owns its cell completely (cow rooms, combat
  and puzzle rooms). The handler must place its own spawner via `TrialContent.applyEncounter`; add
  the id to `RoomSelector.ENCOUNTER_ROOMS` if it does (`RubbleRulesTest` fails otherwise), so rubble
  never cuts off a required spawner.
- Spawn points are jigsaw blocks that replace whatever the decor put there. Do not put one inside
  solid decor.

## Resource nodes (ore and other mineable pickups)

Manifest `nodes`: `{ "block", "from":[x,y,z], "to":[x,y,z], "count", "chance" }`. At stamp time the
box is scanned: **air cells become the block, up to `count` of them chosen by seed (omit `count` or use
-1 for all), skipped entirely with probability `1 - chance`**. A cell that already holds a block is
registered as a node as it stands and is not replaced. So:

- Carve the pocket as `AIR` in the decor, then declare nodes in the same box (see `mineshaft_seam`).
- Several specs may share a box; later ones only fill the air the earlier ones left.
- Node boxes must sit inside the room's y range (up to y 5; two-story rooms may go lower). Keep them at y 1..5 for ordinary rooms.
- Any interior block in the dungeon's `nodePalette` that is baked into the template is also counted as a
  node. Baked ore is therefore always present; declared nodes can be rolled with `chance`.
- Every interior block breaks with the correct tool (`BreakRule`); a "node" only affects the
  nodes-mined counter. Scarcity is controlled by what you place, not by protection.
- Gravity blocks (sand, gravel) must sit on solid support or they fall at stamp time.

## Eligibility and selection (manifest fields that matter)

- `dungeons` (else legacy `theme`) binds the room to a dungeon and gives it a x5 weight there
  (`DUNGEON_BOOST`). `acts` limits by act. `borrowableBy` lets another dungeon use it when deviating.
- `graphRole`: `entry`, `any`, `side_reward`, `final` (only on a dungeon's final node), `capstone`
  (boss room on a capstone dungeon's terminal cell). Valid values are checked by `PackValidator`.
- A dungeon node's `roomBias` list in `dungeon/<id>.json` multiplies those rooms' weight by 3 on that
  floor. It names rooms that must exist (the validator reports a missing one), so **add the room first,
  its bias second**.
- `weight`, `minDepth`, `maxPerDungeon`, `tier`, `light` (`lit`/`dim`/`dark`; dim and dark strip lamps),
  `corridor` (`narrow`/`wide`), `window` (`bars`/`glass`/`tinted_glass`/`none`), `access` (`open` or
  `gated`; gated rooms are entered through the west wall and need an Ordeal or Lock), `biome` (tag).

## Procedure

Use the Write/Edit tools for Java and JSON. Large bash heredocs with embedded quotes and python blocks failed with
`unexpected EOF`; do not build files that way.

1. Read one neighbouring spec and manifest for the family you are adding to
   (`ResourceBiomeSpecs` for mineshaft, lush, cow and act 1 rooms; `PressureSpecs`, `KnowledgeSpecs`,
   `SpurSpecs`, `SituationSpecs`, `CapstoneSpecs` for puzzle and combat rooms).
2. Write the spec(s) in the right `*Specs.java`. Keep door lanes clear. Add the list to
   `RoomTemplateGenerator.specs()` if it is a new file. Helpers:
   - **New spec files:** `RoomDsl` (`put`, `box`, `column`, `quad`, `log`, `vines`, `candle`, and the placeholder
     constants `WALL`, `FLOOR`, `ACCENT`, `LAMP`).
   - **Inside `ResourceBiomeSpecs`:** use that file's own private helpers (they are not `RoomDsl`):
     `fill(level, o, x1,y1,z1, x2,y2,z2, state)`, `put(level, o, x,y,z, state)`, `tunnelRock(level, o, rock)` (rock on
     both sides of a west to east path z 6..8), `railLine(level, o, x1, x2, z)` (rail along z), and
     `supportFrame(level, o, x)` (two fence posts and a plank beam across z 6..8).
   - **Mineshaft idiom:** one door is `EnumSet.of(Direction.WEST)`, two opposite `WEST, EAST`, four `HORIZONTALS`.
     Fill the whole interior with `Blocks.STONE` (no node palette holds it, so rock is never a node), then carve `AIR`
     for tunnels, chambers and ore pockets, then declare ore nodes over the pockets in the manifest. Spawn points may
     sit on the edge of a door lane (the tunnel room does); they just must not block the lane.
3. Write the manifest JSON. Copy a neighbour and change `template`, roles, bindings, nodes. A room is drawn without a
   `roomBias` (its `dungeons` binding already weights it x5 in its own dungeon); add a node `roomBias` when the brief asks
   for it, to push a room harder on a specific floor (always for final-floor rooms, see `FINAL_FLOOR_ROOMS.md`).
4. Generate the template (confirmed working, 2026-10-08). Until the `.nbt` exists the startup log shows
   `Loaded N dungeon rooms (1 rejected ...)` and an error naming your room; that is expected during generation. Add a
   throwaway gametest and register it in `src/gametest/resources/fabric.mod.json` (one added line in the entrypoint
   list; the file may be dirty with other work, so remove only your own line with a targeted Edit, never
   `git checkout` it):

   ```java
   package pocketdungeons;

   import net.fabricmc.fabric.api.gametest.v1.GameTest;          // NOT net.minecraft.gametest.framework.GameTest
   import net.minecraft.gametest.framework.GameTestHelper;

   public final class GenRoomsTmpGameTest {                       // give it a unique name if another agent is generating too
       private static final String[] ROOMS = {"my_room"};
       @GameTest(maxTicks = 200)
       public void generate(GameTestHelper helper) {
           for (String room : ROOMS) {
               if (RoomTemplateGenerator.generate(helper.getLevel(), room) != 1) { helper.fail("no spec " + room); return; }
           }
           helper.runAfterDelay(60, helper::succeed);   // capture runs a tick later
       }
   }
   ```
   Run `.\gradlew.bat runGameTest`. The log says `Saved room template to` the project's
   `build/run/src/main/resources/data/pocketdungeons/structure/rooms/<name>.nbt`.
   Copy **only your own** file into `src/main/resources/data/pocketdungeons/structure/rooms/` (`build/run/...` is shared
   and may hold other rooms' templates), then **delete the throwaway class and its `fabric.mod.json` line**
   (never committed). Regenerate any room whose spec changed.
5. Verify (below), then commit the spec, the `.nbt` and the JSON together.

### Skeletons (copy, then change)

Spec, in a new `FooSpecs.java` (call `FooSpecs.list()` from `RoomTemplateGenerator.specs()`; the generator calls
`decor` once, then places chests and spawn markers itself):

```java
final class FooSpecs {
    static List<RoomSpec> list() { return List.of(fooRoom()); }

    private static RoomSpec fooRoom() {
        return new RoomSpec("foo_room", EnumSet.of(Direction.WEST, Direction.EAST))   // doors
                .chests(new BlockPos(13, 1, 13))                                       // placeholder chest
                .spawns(new BlockPos(6, 1, 4), new BlockPos(9, 1, 11))                // spawn markers on open floor
                .decor((level, o) -> {                                                 // o = the cell's corner
                    RoomDsl.box(level, o, 1, 1, 1, 14, 5, 5, Blocks.STONE.defaultBlockState());
                    RoomDsl.box(level, o, 3, 1, 3, 5, 3, 3, RoomDsl.AIR);              // carve a pocket
                });
    }
}
```

Manifest `dungeon_room/foo_room.json` (copy `mineshaft_junction.json`; this is the whole required shape):

```json
{ "template": "pocketdungeons:rooms/foo_room", "footprint": [1, 1], "roles": ["encounter", "loot", "corridor"],
  "weight": 3, "minDepth": 0, "maxPerDungeon": 2, "theme": ["mineshaft"], "dungeons": ["mineshaft"], "acts": [1],
  "light": "dim", "corridor": "narrow", "biome": "mineshaft", "tier": 1, "access": "open", "window": "bars",
  "nodes": [ { "block": "minecraft:iron_ore", "from": [3,1,3], "to": [5,3,3], "count": 3, "chance": 0.7 } ] }
```

Final-floor room: add `"graphRole": ["final"]` to the manifest, then (room first) put its name in the final node's
list in `dungeon/<id>.json`, the same shape the other nodes use:
`{ "id": "great_taproot", "name": "...", "layer": 5, "final": true, "roomBias": ["great_drip_cavern"] }`.
`graphRole: final` alone only restricts the room to final nodes; `roomBias` is what pushes it (x3).

### Block-state cheat sheet (26.2 names that differ from older versions)

- Pointed dripstone: `BlockStateProperties.VERTICAL_DIRECTION` (`Direction.UP/DOWN`, the way the tip points) and
  `BlockStateProperties.SPELEOTHEM_THICKNESS` with `net.minecraft.world.level.block.state.properties.SpeleothemThickness`
  (`TIP_MERGE, TIP, FRUSTUM, MIDDLE, BASE`). There is no `DripstoneThickness` or `PointedDripstoneBlock.TIP_DIRECTION`.
  Stalactite recipe that keeps two blocks of headroom: `DRIPSTONE_BLOCK` at y5, pointed `BASE` (DOWN) at y4, pointed
  `TIP` (DOWN) at y3. Stalagmite: pointed `TIP` (UP) at y1.
- Water cauldron: `Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3)`.
- Cave vines with berries: `BlockStateProperties.BERRIES` (see `RoomDsl.vines`); logs: `BlockStateProperties.AXIS`
  (`RoomDsl.log`); candle: `CandleBlock.CANDLES`/`LIT` (`RoomDsl.candle`); hanging lantern: `BlockStateProperties.HANGING`;
  lit campfire: `CampfireBlock.LIT`; rails: `BlockStateProperties.RAIL_SHAPE` with `RailShape`.
- **Collections:** in 26.2 coloured blocks and every copper block are collections, not `Block`s. Concrete:
  `Blocks.CONCRETE.black().defaultBlockState()`. Copper bulb:
  `Blocks.COPPER_BULB.waxed().unaffected().defaultBlockState().setValue(CopperBulbBlock.LIT, true)` (plain
  `Blocks.COPPER_BULB.defaultBlockState()` does not compile; the same goes for any `Blocks.COPPER_*`).
- `RoomDsl.AIR` is the air state for carving. If a name does not compile, find it with `javap` on
  `minecraft-merged.jar` in the Loom cache instead of guessing.

### Several agents in one tree (worked for 21 rooms, 2026-10-08)

Set the tree up first so nobody edits a shared file: one stub spec file per agent (`list()` returns an empty list),
already added to `RoomTemplateGenerator.specs()`; one pre-registered throwaway `GenRoomsTmpNGameTest` per agent (empty
`ROOMS`); and the stub class names listed in `RoomLibraryGameTest.CHECKED_SPEC_CLASSES`, which checks every room of those
files by reflection (a class that does not exist yet is skipped). Each agent then owns its spec file (rewritten in ONE
Write call), manifests, `.nbt` files, throwaway class and the dungeon JSONs it adds a `roomBias` to; it edits nothing else.
Afterwards the coordinator deletes the throwaway classes and their `fabric.mod.json` lines.

Take a gradle lock around every run (Git Bash), skipping the run if the lock cannot be had (never run gradle without it,
and never remove a lock you did not take):
`n=0; until mkdir /a/tmp/gradle.lock 2>/dev/null; do sleep 20; n=$((n+1)); [ $n -gt 60 ] && { echo lock-timeout; exit 1; }; done; <gradle ...>; rmdir /a/tmp/gradle.lock`.
Always release it. A foreground Bash call is capped at 10 minutes, so a full run behind a contended lock must use
`run_in_background` (then wait for its notification). If a session dies mid-run, the lock stays: the coordinator clears it (`rmdir /a/tmp/gradle.lock`) after confirming no
gradle process is running, then resumes the agents. A sibling's half-written file can break your compile: wait and retry,
never fix it. A run with a sibling's failing test exits 1: use `--continue` and judge your own rooms by their findings.
`Loaded N dungeon rooms (M rejected)` counts every in-flight room (a sibling's missing `.nbt` is a rejection, and a
repointed manifest, such as `grove.json`, is rejected until its template exists); do not use "one higher" as a check
in a shared tree.

### Touch points for one new room (checklist)

1. spec method in a `*Specs.java` and its `specs.add(...)` line in that file's `list()` (new spec file: also an
   `addAll` line in `RoomTemplateGenerator.specs()` and the class name in `RoomLibraryGameTest.CHECKED_SPEC_CLASSES`;
   a room added to `ResourceBiomeSpecs` goes in `RoomLibraryGameTest.NAMED` instead);
2. `dungeon_room/<name>.json`;
3. `structure/rooms/<name>.nbt` (generated, then copied);
4. only if it hosts a spawner through a `Situations` handler: `RoomSelector.ENCOUNTER_ROOMS`;
5. optionally a node `roomBias` in `dungeon/<id>.json` (after the room exists).

### Authoring notes learned from 21 rooms

- `RoomDsl` signatures: `put(level, o, x, y, z, state)`, `box(level, o, x1, y1, z1, x2, y2, z2, state)`,
  `column(level, o, x, y1, y2, z, state)`, `quad(level, o, x, y, z, state)` (mirrors across both centre lines),
  `log(axis)`, `vines(level, o, x, z)`, `candle(count)`; constants `AIR`, `WALL`, `FLOOR`, `ACCENT`, `LAMP`. A spec file
  imports `net.minecraft.core.BlockPos`, `Direction`, `net.minecraft.server.level.ServerLevel`, `Blocks`, `BlockState`
  and uses `java.util.ArrayList/EnumSet/List`.
- Decor may write the floor row (y=0) and the ceiling row (y=5 up to the shell at 6): sculk, magma, black concrete and
  hanging dripstone all worked. Only door slots and lanes are off limits.
- Nodes fill air in manifest order: with several specs on one box, earlier entries take their `count` first.
- **Every spawn point and chest must be reachable** or the connectivity check fails, so a "sealed viewing chamber"
  needs an opening; use a cobweb-filled gap in the glass (`cow_ward`). The check counts any block WITH a collision
  shape as an obstacle in a feet or head cell. Passable: air, cobwebs, rails, plants, powder snow,
  single `SNOW` layers, pressure plates. Obstacles: **carpets** (thin collision: a moss-carpet rug on the floor row of
  an aisle failed the check, so lay rugs as a block in the floor row y=0), `SNOW` of 2 or more layers, iron bars,
  barrels, hoppers, lecterns, stairs and slabs (they are walkable surfaces but block a feet cell). Sealed ore pockets
  that nobody can reach are fine; the check only covers doors, spawn points and chests.
- Interior cells start as air; chests and spawn markers are placed after the decor and override it.
- Stairs and slabs: use `BlockStateProperties.HORIZONTAL_FACING` and `HALF` (`StairBlock.FACING` did not compile);
  rail curve `RailShape.NORTH_EAST` joins the north and east neighbours; amethyst clusters take `FACING`; a ladder
  takes `HORIZONTAL_FACING`; end rods take `FACING` (default is fine). Keep chests at y=1; one at y=2 on a platform is
  untested.
- **Generic rooms** (no `dungeons`, `theme` or `acts` in the manifest, like `hall_*` and the `hall_*` variants) are
  eligible in every dungeon. They are weighted 1 (bare halls) or 2 (variants) against x5 for a room bound to the floor's
  dungeon, and a floor draws each cell from rooms that match its door mask and role. Use only the four placeholders and
  neutral blocks so they re-skin everywhere.
- **Data-coupled tests:** a new room with nodes or a handler can change tests that sample plans or count rooms:
  `BoardGameTest.theBoardOnlyPromisesOreThePlanCanDeliver` (Copper Works now always holds ore, so it also samples the Ancient City
  for the quiet case), `RubbleRulesTest`, `CowPitsTest` (cow caps), `LootRulesTest`. After adding rooms to a dungeon with
  no `hiddenOre`, run the whole suite and read any failing assertion before changing a room or a test.
- **Processor-skinned rooms:** only the four placeholders are remapped (per the table above). Direct blocks, plants,
  real `OAK_LOG` and a direct `SEA_LANTERN` survive any processor. A manifest `processors` field applies the named list
  to the whole room, so two manifests with different `processors` can share one template (`grove_tee` serves `grove`
  and `rootworks_grove`). Repointing an existing manifest at a new template keeps its other fields; the room is rejected
  at load until the `.nbt` exists.
- **Mob and handler rooms:** a `content` id needs a `Situations.register(id, (level, o, role, depth, profile, spawns,
  seed, affixes, lootSuffix, theme, voidedFloor, content) -> BlockPos or null)` handler (see
  `ResourceBiomeSpecs.registerHandlers`, which registers one per key of `CowPits.ROOM_COWS`). Spawn mobs with
  `RoomContent.spawnMobs(level, o, type, count, spawns, seed, after)`: the spawn list is shuffled, `count` mobs spawn
  (cycling the points when `count` is larger), and `after` runs once per mob in that shuffled order, so tell mobs apart
  by position (for example height), not by order. Name an entity with `setCustomName` and `setCustomNameVisible(true)`.
  The room needs an `ENCOUNTER_ROOMS` entry only if its handler calls `TrialContent.applyEncounter`.
- **Update the floor's note.** A room that changes what a floor offers (an ore room added to a `roomBias`, a final room)
  should change that node's `"notes"` in `dungeon/<id>.json` (see `dungeon-content`); `FloorNotesTest` fails a note that names
  an ore no biased room declares. To make a dungeon you do not own use your rooms, add `borrowableBy: ["<dungeon id>"]` to the
  room's manifest and put the room in the nodes' `roomBias` (Cow Pits borrows four ore rooms this way); set `minNodeRooms`
  to 0 on a dungeon that should not promise ore.
- Resource budgets are part of the design: count logs, sand and ore in the room and keep them small (groves hold 8 to 9
  logs, `root_sandbar` about 38 sand). Cow rooms are capped by `CowPits.ROOM_COWS`, `ROOM_LIMITS` and `FLOOR_CAP`.
- Editing JSON with `sed` flips line endings on the touched lines (the files are CRLF). Prefer the Edit tool.

## Verification

- Run, from Git Bash in the project dir, `./gradlew.bat test runGameTest dungeonIntegrationTest --console=plain > /a/tmp/run.txt 2>&1; echo $?`
  once at the end (about 3 minutes; the generation run is a separate `runGameTest`, about 2); add `--continue` in a
  shared tree so a sibling's failing test does not hide the rest. Do not use PowerShell's `*>`: it writes UTF-16 that
  Grep cannot read. Success is exit code 0, `All N required tests passed`, `dungeonIntegrationTest` passing, and
  `Loaded N dungeon rooms` equal to the old count plus your rooms (alone in the tree). The generation run's count
  includes your throwaway test; the final one does not. **In a shared tree with a failing sibling test** the exit code and
  the "All N required tests passed" line are unusable: success for your rooms is `Saved room template to ...<room>.nbt`
  in the generation run, no `Room library check: <your room>` line, `dungeonIntegrationTest: PASS`, and `Loaded` showing
  no rejection of your rooms.
- Read the redirected file with Grep (`BUILD`, `FAILED`, `required tests passed`, `Loaded \d+ dungeon rooms`).
  `newRoomsConnectEveryDoor` is not logged by name: a failure appears as a failed test with the finding text, and a
  pass is simply inside `All N required tests passed`.
- New gametest *classes* only run if listed in `src/gametest/resources/fabric.mod.json`; check that the reported
  test count rises. Adding a room to the connectivity check adds no test methods. The log names only failing
  rooms; a pass is silent.
- `PackValidator` and the room metadata tests catch bad manifests: unknown roles, missing rooms in a
  `roomBias`, bad `graphRole`, node boxes outside the room's y range.
- **Connectivity check (built, `RoomLibraryGameTest`):** rooms in the listed spec classes are checked automatically
  (see the checklist for where a room goes). It builds the spec exactly as
  the generator does (`RoomTemplateGenerator.buildForCheck`) and flood fills from every door (walkable = open feet
  and head cells with a solid block below, step up 1, drop up to 3). It fails on a cut-off door, an unreachable spawn
  point or chest. `everyLibraryRoomReport` walks the whole old library and only logs
  (`Room library check: ...`); its findings for gated rooms (iron door placed at stamp time), blaze_loft and the
  lower-story rooms are expected false positives, not bugs.
- Connectivity is the usual real bug (a decor block in a lane, a pocket sealing a door). Check every
  door connects to every other, and every chest and spawn point is reachable.
- Say plainly what is unverified: a room's look and feel need an in-game visit; tests do not prove it.
- Update the dungeon's `docs` entry or `docs/reference/FINAL_FLOOR_ROOMS.md` if the room closes a gap.

## Design guidance from past playtests

- Rooms that land well have **one clear idea the player reads on sight**: a room of webs, a flooded
  room, a pitch black room with endermen, silverfish in the walls, an SCP flavoured farm. Pair a gimmick
  with something to do (a puzzle, a mining reward, a fight), not decoration alone.
- Tone the owner wants throughout the mod: eerie, anomaly, "minecraft-SCP" (the Cow Pits).
- Scarcity: wood, food, sand and ore are meant to be finite. Give small amounts (a grove is "a couple of
  blocks of wood, not too much"), cap with `maxPerDungeon`, and prefer rare `chance` nodes for the good
  stuff (diamond, ancient debris).
- Dungeons that give little (Infestation, Ossuary, Lush Caves) should get rooms whose point is a
  resource their palette lacks.
