# Build notes — consolidated

This is the full assembled mod: skeleton, boots, six stations, two area tools,
commands, and the API module. Every file in it has been through at least one build
pass earlier in development except the three that were reconstructed for this
package (`ItemRegistry.java`, `Gate.java`, `Stations.java` — see below).

## Fixed during earlier builds, already applied here

1. **`property()` in a `plugins {}` block doesn't work.** The block evaluates
   before the project exists. Both build files use a literal Loom version.
2. **Brigadier paren miscount.** `WondrousCommands` builds its tree as named
   locals, not one chained expression.
3. **`CommandSourceStack.sendSystemMessage` doesn't exist.** Use
   `sendSuccess(() -> component, false)`.
4. **`ServerEntityWorldChangeEvents` doesn't exist** in this Fabric API build.
   `FlyingBoots` relies on the 10-tick poll for dimension changes instead of a
   dedicated hook.
5. **`Abilities.mayfly`** — lowercase f.
6. **`InteractionResultHolder` is gone.** Callbacks return `InteractionResult`
   directly; `SUCCESS_SERVER` is what consumes a station's block interaction.
7. **`DyedItemColor(int)`** — one constructor arg.

## Reconstructed for this package, not yet build-tested in this exact form

`ItemRegistry.java`, `Gate.java`, and `Stations.java` were rewritten from scratch
to match the current 7-field `Definitions.Def` (with the `area` component added
for the pick/shovel). They're straightforward — `ItemRegistry` and `Gate` are
unchanged in substance from earlier verified versions, and `Stations` only gained
a comment update — but this exact combination of all 8 files hasn't been compiled
together as one build. Worth a `./gradlew build` before assuming it's clean.

## Signatures not in the original recon — watch on first build of AreaBreak.java

1. `AttackBlockCallback` parameter order — expected `(player, level, hand, pos, direction)`
2. `PlayerBlockBreakEvents.AFTER` parameters — expected `(level, player, pos, state, blockEntity)`
3. `ItemStack.isCorrectToolForDrops(BlockState)` — may want `Level`/`BlockPos` too

`BlockState.getDestroySpeed`, `Level.getBlockEntity`, `BlockPos.offset(int,int,int)`,
and `ServerPlayerGameMode.destroyBlock(BlockPos)` are all confirmed or long-stable.

## Not available in 26.2

`MinecraftServer.isFlightAllowed()` doesn't exist. `WondrousMod` logs an
unconditional reminder about `allow-flight=true` instead of a conditional warning.

## Still open

The op check. `Gate.mayAdminister` is console-only (`!source.isPlayer()`). This is
**the most likely reason `/wondrous give` "does nothing"** — running it as a player
in chat fails Brigadier's `requires()` silently rather than with an error message.
Run it from the server console.

## Test gate — everything

**Boots:** give via console → wear → fly → remove mid-air, land unhurt → creative
round-trip → die/respawn wearing them, flight back fast.

**Six stations:** each opens its menu; **none place as a block** when right-clicked
on the ground (this is the one that fails quietly if `SUCCESS_SERVER` isn't
consuming correctly); anvil renames/repairs/never breaks; ender chest matches a
real one; an ordinary crafting table still places normally.

**Two area tools:** 3×3 on a wall carves into the wall; 3×3 on a floor carves into
the floor; sneak = one block; adjacent dirt/gravel survives a stone break; adjacent
chest survives; Fortune applies across the whole break; bedrock survives; tool
breaking mid-swing doesn't crash.

**Give:** from console, `/wondrous give <you> pocket_workbench`, confirm it lands
in inventory (or drops at your feet if full) and opens correctly.
