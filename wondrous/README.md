# Wondrous Items

Pocket crafting stations, boots that let you fly, and tools that break a 3×3.
**Server-side only** — vanilla clients need nothing installed.

Fourth mod in the set, and the first meant to be consumed by the others.

## The items

| id | Name | Looks like | Right-click / effect |
|---|---|---|---|
| `flying_boots` | Up Up And Bye Boots | Red leather boots | Flight, while worn |
| `pocket_workbench` | Pocket Crafter | Crafting table | 3×3 crafting |
| `pocket_enderchest` | Messy Chest | Ender chest | Your ender chest |
| `pocket_anvil` | Fixing Sweetie | Anvil | Anvil — **never breaks** |
| `pocket_grindstone` | Take-Backsies Stone | Grindstone | Grindstone |
| `pocket_stonecutter` | Chop Chop Cutter | Stonecutter | Stonecutter |
| `pocket_loom` | Cutie Loom | Loom | Loom |
| `big_hole_pick` | Big Hole Pick | Diamond pickaxe | Breaks a 3×3, sneak for one block |
| `big_hole_shovel` | Big Hole Shovel | Diamond shovel | Digs a 3×3, sneak for one block |

**The `id` column is the API contract.** Quest JSON and `/wondrous give` reference
it. Display names can change freely; ids can't.

The anvil not breaking isn't a bug — with no anvil block in the world there's
nothing to degrade. No pocket enchanting table on purpose either: bookshelf power
needs a real position, so a pocket one would cap at level 1 and just look broken.

The area tools use `isCorrectToolForDrops` to filter what they break — mine stone
with the pick, get a 3×3 of stone; dirt and gravel in the plane stay put. They now
carry a negative `BLOCK_BREAK_SPEED` attribute modifier, so the 3×3 is a trade-off
rather than a strict upgrade over a normal pickaxe/shovel.

## Requirements

- Minecraft **26.2**, Fabric Loader 0.19.3+, Fabric API
- **JDK 25** to build
- **`allow-flight=true` in `server.properties`**, or the vanilla anti-fly kick
  fights the boots

## Commands

```
/wondrous list                            open to everyone
/wondrous give <player> <id> [count]      op level 2+, or console
```

`give` is gated by `Gate.mayAdminister`, which requires operator level 2
(`Commands.LEVEL_GAMEMASTERS` — the same gate vanilla puts on `/gamemode` and
`/give`). A non-op player typing it in chat will silently fail Brigadier's
`requires()` check (the subcommand just won't appear as valid). `/op <player>` them
first, or run it from the console/a command block, which always passes.

## Using it from another mod

```bash
./gradlew :api:publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("wondrous:wondrous-api:0.1.0") }
```

Plain `compileOnly`, not `modCompileOnly`/`modImplementation` — the real jar
supplies the classes at runtime, and when it's absent the calling mod carries on
without it. `mod*` Loom variants remap classes for obfuscated Minecraft, which 26.x
doesn't ship, and this Loom setup (`1.17-SNAPSHOT`) doesn't generate the `mod*`
Kotlin DSL accessors here anyway — see `dailyquests/build.gradle.kts` and
`cobbleeconomy/fabric/build.gradle.kts` for the working form.

```java
WondrousItems.get()
    .flatMap(w -> w.byId(id))
    .ifPresentOrElse(
        item -> WondrousGive.giveOrDrop(player, item.createStack()),
        ()   -> LOG.warn("Wondrous Items not installed"));
```

See `MILESTONE-3.md` for the full wiring, including the dailyquests integration
shape.

## How it works

These aren't registry entries. A pocket workbench is a vanilla
`minecraft:crafting_table` carrying `{wondrous: "pocket_workbench"}` in its
`minecraft:custom_data` — the same trick the ballot wand uses. Nothing new appears
in a synced registry, so vanilla clients connect normally.

Behaviour lives in Fabric API event callbacks, not `Item` subclasses:

- `UseBlockCallback` / `UseItemCallback` (`Stations.java`) — the pocket stations.
  Returning `SUCCESS_SERVER` is what stops them placing as blocks.
- `ServerTickEvents` + `ServerPlayerEvents` (`FlyingBoots.java`) — a 10-tick poll,
  plus a respawn hook.
- `AttackBlockCallback` + `PlayerBlockBreakEvents` (`AreaBreak.java`) — the 3×3
  tools. Uses `ServerPlayerGameMode.destroyBlock` per neighbour so drops, Fortune,
  XP, and durability are all vanilla-accurate rather than hand-rolled.

No Mixins.

## Build

```
./gradlew build
```

Jar lands in `fabric/build/libs/wondrous-0.1.0.jar`.

**Copy the Gradle wrapper from quizengine** — `gradlew`, `gradlew.bat`, and
`gradle/wrapper/` aren't in this tree.

```bash
cp /a/quizengine/gradlew /a/quizengine/gradlew.bat .
cp -r /a/quizengine/gradle .
chmod +x gradlew
```

See `NOTES.md` for what's been fixed already and what to watch for on first build.
