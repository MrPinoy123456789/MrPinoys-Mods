# D6: T43 Spawner Dungeon capstone

Branch `devin/d6-spawner-capstone`, off `devin/d5-deep-mine-guardians`.

The boss workshop's decision 6b: "spawner as phase" in the Brood Chamber. The room's four caged
spawners are not decoration; each one frees one earlier Act 1 mechanic the moment it is done, so the
capstone replays the act against the party before the mother arrives. This is the last roadmap
implementation task: the Warden, Wither and Herobrine capstones already shipped.

## What was built

- `BroodPhaseRules`: the pure half. `ORDER` fixes one mechanic per cage in
  `CapstoneFights.spawnerPositions` order ((x, z), then y): cage 0 raises the Ossuary's coffins,
  cage 1 plants the Infestation's creeping bricks, cage 2 fails the Mineshaft's lights and adds
  spiders, cage 3 starts the Lush drip. Headcounts scale with party (`coffinsFor`,
  `brickSeedsFor`, `darkSpidersFor`, each with a hard max), `room` does the cap arithmetic, the
  drip halves its interval once the mother is up, and `motherHealth`, `motherDamage`,
  `MOTHER_SCALE` (2.6) and `windupTicks` fix her stats. The party still picks the real fight order
  by which cage it breaks first.
- `BroodPhases`: the per slot runtime, driven from `CapstoneFights.tickBrood` on the watch tick. A
  cage is done when its spawner block is gone or the wave's exhaust rule doused it; release is a
  one shot per cage, and every mob the phases make carries `WAVE_TAG` so the existing pad gate and
  floor end sweep already count and clear them.
  - Coffins reuse `MineGuardianCoffins.stand` (refactored out of `spawn`, see below) with the
    brood's own tag added instead of guardian scaling: the lids crack on the same tell clock and
    the sleepers stand on the same open path.
  - Bricks reuse `CreepingBricks.open`/`Field`: seeds queue from `freeSpotInCell`, one plants per
    watch tick, and `releaseBudget` tracks `broodMobCap` room so a full chamber bursts none.
  - The dark is the Mineshaft pass narrowed to the terminal cell: the real `MineshaftFinale.TELL`
    and `HINT` lines, `FinaleRules.darkTellTicks(mineshaftDarkTellSeconds)` of warning, then
    `DungeonLight.isLightSource` blocks out except near the pad and the cell middle
    (`FinaleRules.nearPad`), plus `darkSpidersFor` glowing tagged cave spiders. Removed lights are
    remembered and restored at floor end.
  - The drip picks a member in the room and calls `PotionDrip.release` with `PotionDripRules.pick`
    (same harmful percent, same harming health floor), on the Lush interval via
    `potionDripIntervalSeconds`, halved under the mother.
- The brood mother: when the last cage is done `CapstoneFights.tickBrood` already releases the
  retinue wave; `BroodPhases.motherArrives` puts her beside it. A tagged cave spider, scaled 2.6,
  party-scaled health and damage, marked `UNSCALED_TAG` so the wave's own scaling leaves her stats
  alone. She carries a red `ServerBossEvent` bar (refreshed to members each tick, dropped when she
  falls), a raid horn plus overlay windup at half health from `KennelsFinaleRules.step` with
  `TRIGGER_HEALTH_FRACTION`, and on EMPOWER every brood mob but her grows by
  `empoweredScale`, glows and re-aims (original scales remembered so SHRINK puts them back if she
  dies mid windup). The pack anger refresh re-targets brood mobs while empowered.
- `broodMobCap` (default 24): one knob over the whole chamber: wave, mother, coffins, fish and
  spiders all count. `releaseWave` now clamps skeletons then spiders into `room(cap, alive)`, and
  each mechanic releases at most the room that is left.
- Cleanup: `BroodPhases.floorEnded`/`teardown` run from `CapstoneFights`' own endings. They drop
  the boss bar, close the brick field (restores standing bricks), put the lights back, clear the
  coffin set, and remove the slot's `Phases`. Tagged mobs and brews go with the existing
  `discardTagged` and `PotionDrip` sweeps; `PHASES` is a `SessionState.clearedAtStop` map.

## Engine touches

- `CapstoneFights`: `State` and `state` widened to package visibility for the phase controller;
  `tickBrood` and the pad refusal gained member-injected overloads (the gametest seam, since the
  harness has no dungeon level for `onlineMembers`); `waveAlive` factored into `broodAlive(level,
  terminal)` with the box inflated a cell so a mob that chased a member out still holds the pad;
  `releaseWave` is capped; `floorEnded` and `teardown` call the phase cleanup. The Warden, Wither
  and Herobrine paths are untouched.
- `MineGuardianCoffins`: `spawn` split so `stand(level, terminal, count, tellTicks, setup)` is the
  reusable entry. The Act 1 guardian calls it exactly as before (hardness counts, hardness tell,
  `MineGuardians.scale` setup); the chamber calls it with its own count and a setup that only adds
  `WAVE_TAG`. `TELL_FLOOR_TICKS` still floors the crack clock either way.
- New knob `broodMobCap` added via `tools/new_knob.py` after `mineGuardianMobCap`; config fixtures
  and `config/pocketdungeons.default.json` regenerated (181 knobs).

## Tests

- `broodPhaseRulesTest` (new pure test, wired into `build.gradle.kts`): the order is
  coffins/bricks/dark/drip with no mechanic past index 3, headcounts grow and cap, `room` never
  goes negative, the drip halves but never under two seconds, mother stats scale with party, the
  windup never under two seconds.
- `BroodChamberGameTest` (new, slots 9948 and 9949; the first picks collided with
  `MineshaftFinaleGameTest`/`FrostFinaleGameTest` on 9941 and wiped their in flight state, moved):
  - `eachCageFreesItsOwnMechanic`: a hand laid chamber with the real `BROOD_SPAWNERS` positions.
    Breaking cage 0 stands exactly `coffinsFor` coffins and nothing else; cage 1 opens the brick
    field with a seed already planted; cage 2 schedules the dark behind its tell (lights hold,
    then the torch is out and spiders tagged); cage 3 schedules the drip and a tagged brew falls
    on the member. The scaffold clears the structure's barrier band first: `PotionDrip.release`
    wants three of air over the head.
  - `thePadHoldsUntilTheBroodIsDead`: all four cages down brings the wave plus the mother with a
    boss bar; the pad refuses while any tagged brood stands; her half health starts the Kennels
    windup, then the pack empowers (originals remembered); everything dead lifts the bar and the
    pad opens.
- `capstoneRulesTest`, `cellStateForgetTest` and the rest of the pure suite: green.
- `runGameTest`: all 323 required tests passed.
- `dungeonIntegrationTest`: PASS on a real server boot.

## What is not proven

- Read and feel: whether the four mechanics replaying one by one reads as the act revisited, and
  whether the mother's scale, nameplate and bar land as a boss, needs eyes in the real chamber.
- Pacing: the release order is the player's choice, so some orders may be much harder (drip under
  darkness first, coffins last under a full room). Untuned beyond the party headcounts.
- `broodMobCap` 24 is a first guess; a full chamber's feel under cap is untested live.
- The windup horn, the empowerment look and the double speed drip against a moving party are mock
  player verified only.
- The chamber NBT is unchanged: this task reuses the shipped `brood_chamber` template and its
  caged spawners. If the room's real spawner count differs from `BROOD_SPAWNERS`' four the cage
  order still holds by position, but the count is a data check for the live run.

## Live check list

See `docs/playtests/live-checks/d6-spawner-dungeon.md`.
