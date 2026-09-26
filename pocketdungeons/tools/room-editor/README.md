# Pocket Dungeons Room Editor

A local, browser based editor for the mod's room templates
(`data/pocketdungeons/structure/rooms/*.nbt`) and their metadata
(`data/pocketdungeons/dungeon_room/*.json`). It renders rooms with the real
block models and textures from your own Minecraft client jar, lets you edit
blocks, doors and metadata, checks the room against the rules the game
enforces, and saves files the game loads unchanged.

## Run it

Requires Node 22 and npm 10.

```
cd pocketdungeons/tools/room-editor
npm install
npm run dev
```

Open the address Vite prints (http://localhost:5178 by default).

**Browser:** use a Chromium browser (Chrome or Edge). Saving uses the File
System Access API, which Firefox and Safari do not have. In those browsers
"Open read only" still works: you can view and edit, and Save becomes
Download.

Other scripts: `npm run build` (type check plus a production build into
`dist/`), `npm test` (the headless checks below), `npm run typecheck`.

## 1. Load your client jar

Click **Load client jar** and pick your own 26.2 client jar:

- `%APPDATA%\.minecraft\versions\26.2\26.2.jar` (the launcher puts it there
  once you have played 26.2), or
- `%USERPROFILE%\.gradle\caches\fabric-loom\26.2\minecraft-client.jar` (Loom's copy).

The editor reads the blockstates, block models and textures out of it in the
browser and builds a texture atlas. It then keeps the extracted files in this
browser's IndexedDB so the next visit can use **Use cached assets** instead of
picking the jar again. **Forget cache** removes them.

### Asset licensing rule

Block textures, models and blockstates are Mojang's. They are only ever read
at runtime from a jar you already own:

- never copied into this repository or committed,
- never bundled into `dist/` or any build output,
- never fetched from a third party mirror.

The only copy the tool makes is the IndexedDB cache inside your own browser
profile. Keep it that way: do not add extracted assets to the repo, and do not
add a feature that downloads them. `.gitignore` covers `node_modules/`, `dist/`
and local caches.

## 2. Open the pack folder

Click **Open folder** and pick one of:

- `pocketdungeons/src/main/resources/data/pocketdungeons` (the namespace folder),
- `pocketdungeons/src/main/resources` (the resources root),
- a world datapack such as `world/datapacks/pocketdungeons_rooms`.

Allow read and write access when the browser asks. The room list shows every
`dungeon_room` and `anomaly_room` file, plus templates with no metadata, and
flags missing templates and unreadable JSON. Several metadata files can share
one template (the anomaly store rooms do); the status line says so when you
open one.

## 3. View

- **Camera:** drag with the left or right button to orbit, Shift plus right
  drag (or middle drag) to pan, wheel to zoom. A left click without dragging
  runs the current tool.
- **Layers to y:** hides everything above a layer so interiors are reachable
  (`[` and `]`).
- **Overlays:** the canonical door slots for each wall (green when the six
  jigsaws are complete and face the wall, red when partial, grey outline on
  walls without a door), the window band (the outer columns at eye height the
  stamper cuts beside a doorway), the bedrock envelope (the one block shell
  outside the template, off limits), and the cell grid. A two story room
  (`spanY: 2`, 16 x 15 x 16) shows an orange line at the upper story's floor.
- **Rotate view:** previews the four rotations `TemplateStamper` places the
  room at, with the rotated door mask in the status line. Editing is off
  while a rotation other than 0 is shown.

## 4. Edit

| Tool | Key | What it does |
|---|---|---|
| Place | 1 | Click a face to place the brush block next to it |
| Remove | 2 | Click a block to replace it with air |
| Pick | 3 | Copy a block into the brush, block entity data included |
| Select | 4 | Click two corners; Shift+click moves the second corner |
| Paste | 5 | Click to paste the clipboard (R rotates the clipboard) |
| Inspect | 6 | Read a block's block entity NBT (read only) |

With a selection: **Fill** (F), **Clear** (Delete), **Copy** (Ctrl+C),
**Rotate** 90 degrees clockwise in place (R), **Mirror E-W** (M),
**Mirror N-S** (N). **Undo** and **Redo** are Ctrl+Z and Ctrl+Y. Rotation and
mirroring also turn the block states (facing, axis, sign rotation, fence
sides, rail shapes, jigsaw orientation, stair, door and chest handedness).

The **Palette** tab lists every block in the jar with a search box and a
property editor built from the blockstate file. Properties a blockstate file
does not list (for example `waterlogged`) can be added by hand; anything left
out takes the block's default when the game loads the template. Presets place
a door jigsaw (it faces whichever wall it lands on), a spawn jigsaw, or a
tier 1 loot chest.

**Doors** N, E, S, W in the toolbar stamp or seal a wall's six canonical door
jigsaws in one step.

Block entities and entities already in the template are carried through
untouched. Changing a block's properties keeps its block entity; replacing it
with a different block drops it. **Clear** drops entities whose block
position is inside the cleared box. The **Inspector** tab shows block entity
NBT and the entity list, read only.

## 5. Metadata

The **Metadata** tab is a form generated from
`docs/schema/dungeon_room.schema.json` and validated against it as you type
(the schema is imported at build time, so it is the one source of truth).
There is also a raw JSON box. Changing `spanY` resizes the template: going to
2 moves the room up into the upper story and gives the new lower story the
generator's plain shell; going to 1 drops the lower story.

**New room** asks for a unique id (lowercase `a-z`, `0-9`, `_`, `-`), the span
and the doors, then creates a stone brick shell template and a metadata file
with the in-game editor's defaults (`loot` role, weight 1).

## 6. Save

**Save** (Ctrl+S) writes the template as a gzipped structure file and the
metadata as JSON in the style of the shipped files. Palette, size,
DataVersion, block entities, entities and any extra root tags (`pd_author`,
`pd_saved_at`) are kept; an unedited cell keeps its exact original NBT.

Before overwriting, the old file is copied into a `versions/` folder beside
it, the convention `/dungeon roombuilder save` uses:
`structure/rooms/versions/<name>_<UTC time>.nbt`. JSON backups get a `.bak`
suffix (`dungeon_room/versions/<name>_<UTC time>.json.bak`) because the room
manifest lists `dungeon_room/` recursively and would load a plain `.json`
backup as an extra live room.

### Validation

The **Checks** tab runs cheap client side checks: blocks outside the box,
spanY versus template height, the manifest's door rules (door jigsaw on a cell
edge, facing its wall, all six slots present), the in-game save gate's rules
(roles, doors unless entrance, encounter and loot content), the doorway lane
and lower story doors. Click a finding to highlight its blocks.

The authority is the game's own Java. From the `pocketdungeons` folder:

```
gradlew validateRooms
gradlew validateRooms -ProomsDir=C:\path\to\another\resources
```

This boots the game's registries headlessly (no server), parses every room
with `DungeonRoomMeta.fromJson`, reads and data-fixes each template the way
the game's template loader does, and calls the real
`RoomManifest.buildEntry`. It prints each room's door mask and any rejection
in the game's own words, and fails the build on errors. It cannot confirm a
`processors` list that another pack or vanilla provides; it says so.

## Round trip into the game

1. Save in the editor.
2. Get the files in front of the game:
   - **Source tree** (a dev run with `gradlew runServer` or `runClient`): the
     game reads `build/resources/main`, so run `gradlew processResources`
     (or restart the run) after saving.
   - **World datapack** (any server): open `world/datapacks/pocketdungeons_rooms`
     in the editor instead; no rebuild needed. The folder needs a
     `pack.mcmeta`; the in-game room builder writes one the first time it
     saves.
3. In game: `/reload` (or `/dungeon admin manifest reload`), then
   `/dungeon admin manifest list` to see loaded and rejected rooms.
4. Playtest: `/dungeon roombuilder load <name>` stamps the room into a build
   shell; `/dungeon roombuilder validate` runs the in-game checks there.

Do not re-save a two story room with `/dungeon roombuilder save`: it captures
16 x 7 x 16 and would cut off the lower story. Save those from this editor.

## Headless checks

`npm test` runs, against the real files (read only) and your jar:

- every room template round tripped through the editor model and the save
  path, compared as NBT trees and as decompressed bytes;
- the jar's blockstates, models and textures loaded into the atlas and model
  set, and every block of every room meshed through deepslate;
- the client side checks over all rooms, editing operations, the metadata
  schema, and the versions backup naming.

`ROOMS_DIR` and `MC_JAR` override the default locations. To prove the game
accepts editor output, point `PACK_OUT` at a **copy** of the resources folder:
`PACK_OUT=C:\tmp\pack npm test` rewrites every template in the copy through
the editor and adds a few test rooms; then run
`gradlew validateRooms -ProomsDir=C:\tmp\pack`.

The dev server also has an automation harness (dev only, never built): start
it with `MC_JAR`, `PACK_DIR` (a read only pack copy) and optionally `SHOT_DIR`,
then open `/?dev=1&room=<name>` to load both without pickers.

## Layout

- `src/core/`: format and rules, no DOM. `template.ts` (structure NBT model),
  `geometry.ts` (cell contract mirrored from `RoomGeometry`), `validate.ts`,
  `edit.ts` (operations and undo), `transform.ts` (state rotation), `jar.ts`,
  `png.ts`, `resources.ts` (atlas and deepslate resources), `meta.ts`.
- `src/ui/`: the app, the WebGL viewer and overlays, panels, folder access.
- `java/`: the headless validator the `validateRooms` Gradle task compiles.
- `test/`: the headless checks.
