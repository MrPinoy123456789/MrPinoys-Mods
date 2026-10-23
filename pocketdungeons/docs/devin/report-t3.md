# Report: t3 glyphs

## Branch

- Branch: `devin/t3-glyphs`
- Base: `devin/t2-leader-grace` (`dd3984f6` chain)
- Tip: `0a11c4d2`

## Test counts

| Suite | Before | After |
|---|---|---|
| `./gradlew test` (pure) | 130 tasks | 130 tasks, green |
| `./gradlew runGameTest` (All N required tests passed) | 402 | targeted run: GlyphGameTest 4/4 pass (full suite green on the t6 tip at 413) |
| `./gradlew dungeonIntegrationTest` | PASS | PASS (on the chained tip) |

## What changed

- `src/main/java/pocketdungeons/Glyph.java`: the framework. A glyph is one `minecraft:interaction` anchor carrying a persistent data attachment plus tagged visuals; one right-click dispatcher resolves the anchor's handler id and fires it with a `GlyphContext`. `spawn`/`addVisual`/`clear`, group-tag bulk cleanup and the global `pocketdungeons_glyph` tag live here.
- `src/main/java/pocketdungeons/GlyphHandler.java`: `@FunctionalInterface`, `GlyphResult run(GlyphContext ctx)`.
- `src/main/java/pocketdungeons/GlyphContext.java`: player, level, held item, sneaking, optional `InstanceRecord`, the glyph anchor and a copied NBT view.
- `src/main/java/pocketdungeons/GlyphResult.java`: consume, refuse (shake), overlay text, sound, restyle, vanish and chained `then`/`then_data` handlers.
- `src/main/java/pocketdungeons/GlyphHandlers.java`: the named registry plus ready-made handlers `require_item`, `cycle`, `toggle`, `run_once` and `inspect`.
- `src/main/java/pocketdungeons/InstanceRecord.java`: `glyphState`, a per-run string map shared by handlers.
- `src/main/java/pocketdungeons/PocketDungeonsMod.java`: `Glyph.register()` installs the dispatcher at init.
- `src/main/java/pocketdungeons/DungeonCommands.java`: `/dungeon admin glyph require <item>` spawns the standard look for ad-hoc glyphs.
- `src/main/java/pocketdungeons/mixin/DisplayAccessor.java`: two invokers on the existing mixin (interpolation setters) for the refuse shake; no new mixin.
- `src/gametest/java/pocketdungeons/GlyphGameTest.java`: dispatch, consume, refused shake and group-tag cleanup.
- `src/gametest/resources/fabric.mod.json`: registration.

## Decisions I made

- Glyph data is a persistent attachment on the interaction entity plus a per-anchor group tag on its visuals, so teardown is a tag sweep rather than entity bookkeeping.
- `GlyphContext.data` is a copy; handlers commit state through `record.glyphState` or handler-chained writes, keeping the persistence seam in one place.
- Refuse plays a short shake driven by the display interpolation setters already exposed on `DisplayAccessor`.

## Deviations from the spec

- None.

## Only a live server can confirm

- `/dungeon admin glyph require <item>`: an interactable glyph appears; right-click with the item consumes it, with anything else it shakes and refuses.
- Break or clear the room: anchor and visuals vanish together.

## Expected merge conflicts

- `DungeonCommands.java` with other admin command additions.
- `PocketDungeonsMod.java` init block and `InstanceRecord.java` with other per-run state.
