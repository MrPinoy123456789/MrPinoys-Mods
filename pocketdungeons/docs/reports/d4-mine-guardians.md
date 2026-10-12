# D4: T41 Act 1 Endless Mine guardians

Branch `devin/d4-mine-guardians`, off `devin/d3-lush-caves`.

The T40 engine (`MineGuardians`) already supplied the cadence, the pad gate, the `Spawn` record and the
placeholder wave. This task fills Act 1's three guardian slots with miniatures of the Act 1 bosses, one
class each, registered in one place.

## What was built

- `MineGuardianQueen` (slot 1, floors 3, 12, 21): a small Silverfish Queen. She spawns already burrowed
  under the terminal cell (dormant: invisible, invulnerable, silent, no AI), the floor cracks and dusts
  for a tell of `MineGuardianRules.tellTicks` ticks (base 60, never under 40), then she surfaces at
  `MineGuardianRules.queenScale(hardness)` size and fights. Around her, a `CreepingBricks` field plants
  `bricksFor(hardness)` bricks that hatch silverfish on the field's own clock; every released fish is
  collected by the queen's poll and tagged, so the pad stays shut while her brood lives. Named "The
  Princess".
- `MineGuardianPack` (slot 2, floors 6, 15, 24): a pillager packmaster in a ring of
  `wolvesFor(hardness)` wolves, every wolf marked `HostileWolves.mark` and the handler set as the cell's
  `HostileWolves.guard`, so the pack goes quiet when he falls, the Kennels beat for free. Named "The
  Packmaster". A raid horn sounds at spawn.
- `MineGuardianCoffins` (slot 3, floors 9, 18, 27): `coffinsFor(hardness)` upright two block polished
  blackstone coffins stood on free spots in the cell, each holding one dormant tagged skeleton inside
  the lower block. The set's poll opens them one at a time: crack tell (`tellTicks`), lid gives way,
  sleeper stands up and takes a target, then a `COFFIN_GAP_TICKS` pause before the next may crack. A
  broken lid opens its coffin on the spot, even mid tell, matching the Ossuary rule.
- `MineGuardianRules`: the pure numbers. `tellTicks` shortens a tell 20 ticks per hardness point and
  clamps at 40 (two seconds); `bricksFor`/`wolvesFor`/`coffinsFor` are `round(hardness)` driven counts
  under their own ceilings; `capped` trims every count to what `mineGuardianMobCap` leaves.
- `MineGuardiansAct1.registerAll()`: the one registration place, three `MineGuardians.register(1, slot,
  ...)` calls plus the two ticking guardians' `ServerTickEvents` hooks. Called once from
  `RitualListener`'s `SERVER_STARTED` warmup, next to the other warmups. Idempotent so tests can call it.

## Engine touches

- `MineGuardians.Spawn` gained `terminal` (the cell origin): the queen and the coffins need it to key
  their per floor state and to scan the cell for free spots. The engine also gained
  `freeSpotInCell`, a door lane aware, two block separated free floor spot picker both guardians share.
- `InstanceTeardown.forgetCellState` now calls `MineGuardianQueen.clear` and
  `MineGuardianCoffins.clear` (the `CellStateForgetTest` source scan picked them up automatically and
  passes: 9 cell keyed clears). Each `clear` drops the cell's poll state and discards any still
  dormant mob, so an invisible invulnerable sleeper can never outlive its cell. Standing bricks give
  their blocks back through the field's `close`.
- New knob `mineGuardianMobCap` (default 16, min 1): the most mobs one guardian may have standing or
  waiting. The queen counts herself plus bricks, the pack counts the packmaster plus wolves, the
  coffins count sleepers.

## Design decisions

- The guardian interface is a one shot `spawn(Spawn)`. The queen's burrow and the coffins' sequence
  need time, so each of those classes registers its own `ServerTickEvents` poll (every 5 ticks, the
  `CreepingBricks` cadence) over state keyed by terminal cell origin. The pack needs none: anger and
  the guard sweep are `HostileWolves`' own tick.
- The queen's brick adds are spawned by the field, not by the guardian's `spawn` call, so they could
  not be tagged at spawn time. The queen's poll collects every released silverfish and tags it, which
  keeps them inside the pad gate, the same outcome the finale gets through `ctx.addMob`.
- Polls are not gated on the dungeon dimension: each `Run`/`Set` carries its own `ServerLevel`, which
  is also what makes `pollForTest` work on the gametest level.
- `MineGuardianGameTest` calls `MineGuardians.clear()` in every test, so the new tests register the
  real guardians themselves through `MineGuardiansAct1.registerAll()`. The placeholder tests are
  untouched and still pass because they clear first.

## Files changed

- New: `MineGuardianRules.java`, `MineGuardianQueen.java`, `MineGuardianPack.java`,
  `MineGuardianCoffins.java`, `MineGuardiansAct1.java`, `src/test/java/pocketdungeons/MineGuardianRulesTest.java`
- Edited: `MineGuardians.java` (Spawn terminal field, `freeSpotInCell`), `InstanceTeardown.java` (two
  clears), `RitualListener.java` (registration), `PocketDungeonsConfig.java` (knob),
  `config/pocketdungeons.default.json`, six config fixtures, `build.gradle.kts` (test wiring),
  `MineGuardianGameTest.java` (three new tests)

## Tests

- `mineGuardianRulesTest`: the hardness dial (counts climb, ceilings hold, tell floor 40, queen scale
  cap, mob cap trim). Passed.
- `cellStateForgetTest`: 9 cell keyed clears, both new ones wired. Passed.
- `runGameTest`: all 318 required tests passed. Three new tests in `MineGuardianGameTest`:
  - `theYoungQueenSurfacesOutOfTheFloor`: slot 1 spawns a burrowed tagged silverfish queen, bricks
    planted at the `bricksFor` count, pad shut while dormant and while fighting, floor 12 plants more
    bricks and scales bigger, pad opens when the wave is down.
  - `thePackmasterDrivesHisWolves`: slot 2 spawns one named pillager plus `wolvesFor` wolves, all
    tagged, all wolves `HOSTILE`, pad held by the wolves alone after the handler falls.
  - `theCoffinsRiseOneAtATime`: slot 3 stands `coffinsFor` coffins with dormant tagged sleepers, one
    cracks at a time, opens after its tell, a smashed lid opens its coffin at once, pad held while any
    sleeper stands or lies sealed.
- Full pure suite: green, 180 config knobs after the new key.
- `dungeonIntegrationTest`: PASS (the `SERVER_STARTED` registration boots clean on a real server).

## What is not proven

- Look and feel: whether the burrow dust, the horn, and the coffin crack read as intended needs eyes.
- Live pacing: the sequential coffin reveal (tell 60, gap 40) and the brick ripening pace are untuned.
- The gametest world has no players: target selection at emerge/open is asserted by code shape only.
- Whether the act 2 placeholder still plays acceptably next to real act 1 guardians (it is meant to,
  by design).

## Live check list

See `docs/playtests/live-checks/d4-mine-guardians.md`.
