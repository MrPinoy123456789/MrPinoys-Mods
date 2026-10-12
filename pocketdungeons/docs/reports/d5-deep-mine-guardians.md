# D5: T42 Act 2 Endless Mine guardians

Branch `devin/d5-deep-mine-guardians`, off `devin/d4-mine-guardians`.

The T40 engine (`MineGuardians`) and the D4 Act 1 guardians already proved the shape: one class per
slot, registered per act, keyed by terminal cell origin, every spawned mob tagged so the pad stays
shut. This task fills Act 2's three guardian slots with miniatures of the Act 2 bosses: the Silent
Deep's Hush, the Copper Works' Foreman, and the Frostworks' cold.

## What was built

- `MineGuardianSentry` (slot 1, floors 3, 12, 21): a tagged bow skeleton named "The Deep Sentry"
  standing among decor sculk sensors. The cell runs under its own Heard meter, counted the way
  `DeepslateFinale` counts it: `DeepslateFinaleRules.Rises` for bow shots, hits taken, sprinting and
  guardian mob deaths, plus `quietRise` once every `hushQuietSeconds` seconds of fighting. The ceiling
  is `MineGuardianRules.sentryCeiling(hardness, hushCeiling)`, falling a quarter faster per hardness
  point but never under a quarter of the base or under 3, so the fight stays readable. A full meter
  runs the finale's own tell (heartbeat plus "The deep heard you.", `TELL_TICKS` 60), then darkness on
  the members in the cell and a tagged `Whelp` sent at the nearest one; while a whelp is out, or the
  wave is at `mineGuardianMobCap`, the meter is deaf. The sensors and the whelp's spawn spot come from
  `freeSpotInCell`, so nothing ever blocks the pad.
- `MineGuardianForeman` (slot 2, floors 6, 15, 24): a tagged vindicator named "The Foreman" over a
  floor of vents he swaps in at spawn: `ventsFor(hardness)` copper grates in place of floor blocks plus
  one water cauldron vat, all on free spots. The vents fire in `CopperClockRules.ventOrder` on the same
  pinned rhythm `CopperFinale` uses: `CopperFinale.hiss` and rising `smoke` exactly
  `CopperClockRules.TELL_TICKS` before `burn`, the fire pinned to the poll grid so the two second tell
  can never come out short. The cycle is `ventPeriodTicks(copperVentPulseSeconds * 20, hardness)`, a
  second faster per hardness point and never under `MIN_PERIOD_TICKS`. Once a second a Foreman in his
  vat takes `copperForemanVatDamage` drown damage and `copperForemanVatWeakSeconds` of Weakness plus
  Slowness, with the finale's own lesson line shown once. When he falls the vents quiet and the floor
  is given back block for block.
- `MineGuardianFrost` (slot 3, floors 9, 18, 27): `straysFor(hardness)` tagged strays, the leader
  named "The Frostbite", over `BRAZIERS` (3) lit campfires stood on free spots. After a tell of
  `tellTicks(FrostFinaleRules.TELL_TICKS, hardness)` (wind sound plus "The cold is coming. Find a
  fire.", never under 40 ticks) a `FrostFinale.Run` sized to the cell feeds the real
  `FrostFinale.coldTick` every game tick: members in the cell gain freeze ticks at
  `frostColdTicksPerSecond`, thaw fast within `frostBrazierRadius` of a lit brazier, and the same
  health ceiling makes the cold never lethal. The last stray down lifts the cold at once.
- `MineGuardiansAct2.registerAll()`: the one registration place, three `MineGuardians.register(2,
  slot, ...)` calls plus the three ticking guardians' `ServerTickEvents` hooks. Called once from
  `RitualListener`'s `SERVER_STARTED` warmup next to `MineGuardiansAct1.registerAll()`. Idempotent.
- `MineGuardianRules` gained the Act 2 numbers: `SENSORS`, `BRAZIERS`, `SENTRY_MIN_CEILING`,
  `MAX_VENTS`, `MAX_STRAYS`, `sentryCeiling`, `ventsFor`, `ventPeriodTicks`, `straysFor`.

## The name

The task doc still says slot 3 is "the Frost Warden". The owner's one Warden decision (the carry-over
rename of the Frostworks elite to Jack Frost, commit `3efabcb5` on `devin/c-carryover`) reserves
"Warden" for the sculk Warden, so the guardian is named "The Frostbite": a small kin of Jack Frost
for a small freeze, and never a Warden name. The stale `frostworks.json` name on this branch's
lineage is the integrator's reconciliation, not D5's.

## Engine touches

- `CopperFinale` widened `hiss`, `smoke` and `burn` to package visibility so the Foreman reuses the
  vent's tell, smoke and pulse verbatim. No behaviour change.
- `InstanceTeardown.forgetCellState` now calls `MineGuardianSentry.clear`, `MineGuardianForeman.clear`
  and `MineGuardianFrost.clear` (the `CellStateForgetTest` source scan picked all three up: 12 cell
  keyed clears). The sentry's `clear` takes the decor sensors back, the Foreman's restores the floor
  grates and the vat, the Frostbite's thaws tracked members and removes her campfires.
- `MineGuardianSentry.answer` falls back to `freeSpotInCell` for the whelp's spot when
  `Instances.randomSpawnNear` finds no open ring around the hearer, so a packed floor cannot silently
  swallow the answer.
- No new knobs: every dial is an existing one (`hush*`, `whelp*`, `copperVentPulseSeconds`,
  `copperForemanVatDamage`, `copperForemanVatWeakSeconds`, `frostColdTicksPerSecond`,
  `frostBrazierRadius`, `mineGuardianMobCap`), so the fights tune with their parents.

## Design decisions

- Same architecture as D4: the guardian interface is a one shot `spawn(Spawn)`; each Act 2 class that
  needs time registers its own tick over `SessionState.clearedAtStop` maps keyed by terminal cell
  origin. The sentry polls every 20 ticks (a second at a time, the finale's watch cadence), the
  Foreman every 5 (the tell pinned to that grid, the pattern the Copper Works gametest proved), the
  Frostbite every game tick (vanilla thaws two freeze ticks a tick, so a slower write would saw
  tooth, the same reason `FrostFinale` has its own tick).
- The sentry's meter is the guardian's own, not `PressureSources`: the mine cell has no armed sculk
  room, so there are no sensor pulses to hand over and nothing to release. The sensors are decor. The
  finale's meter handling is still reused as arithmetic: `SculkOmen.heardAfter`,
  `DeepslateFinaleRules.Rises`, `quietRise`, `full`, `nearlyFull`, `answerTick`, `answerDue`.
- The Frostbite's cold is `FrostFinale.coldTick` verbatim, fed a `FrostFinale.Run` built for the
  terminal cell rather than a scripted finale context: the braziers are the campfires she stands at
  spawn, not a room scan, so the cold cannot be switched off by an empty scan and its relief is
  guaranteed by construction. `coldApplies` is not re-checked: a `frostColdTicksPerSecond` of 0 makes
  the rise 0 on its own.
- The vat is a water cauldron rather than the finale's stamped one: the cell has no authored vat, so
  the Foreman brings his own and takes it back on clear.

## Files changed

- New: `MineGuardianSentry.java`, `MineGuardianForeman.java`, `MineGuardianFrost.java`,
  `MineGuardiansAct2.java`
- Edited: `MineGuardianRules.java` (Act 2 numbers), `MineGuardianRulesTest.java` (Act 2 assertions),
  `MineGuardianGameTest.java` (three new tests), `CopperFinale.java` (three helpers to package
  visibility), `InstanceTeardown.java` (three clears), `RitualListener.java` (registration)

## Tests

- `mineGuardianRulesTest`: extended with the Act 2 dial (sentry ceiling falls but floors, vents and
  strays grow and cap, vent period shortens but never under `MIN_PERIOD_TICKS`). Passed.
- `cellStateForgetTest`: 12 cell keyed clears, all three new ones wired. Passed.
- `runGameTest`: all 321 required tests passed. Three new tests in `MineGuardianGameTest`:
  - `theDeepSentryListens`: slot 1 spawns one tagged bow skeleton among decor sensors, the meter
    fills past the (hardness scaled) ceiling, the tell runs, and a tagged whelp answers; the pad
    stays shut while any of them live, and a lap later the ceiling is lower.
  - `theForemansVentsPulse`: slot 2 spawns a tagged vindicator over `ventsFor` grates plus a vat, the
    first vent tells on the pinned grid and fires exactly `TELL_TICKS` later, and a later Foreman
    fields more vents.
  - `theFrostbiteBringsTheCold`: slot 3 fields `straysFor` tagged strays among lit braziers, the
    tell never under two seconds, the cold then freezes a member in the cell, and the last stray
    down lifts it at once.
- Full pure suite: green.
- `dungeonIntegrationTest`: PASS, 134 rooms and 21 dungeons loaded, 0 rejected (the `SERVER_STARTED`
  registration boots clean on a real server).

## What is not proven

- Look and feel: whether the decor sensors, the heartbeat tell, the vent floor and the campfires read
  as intended needs eyes.
- Live pacing: the sentry's ceiling at high laps, the vent rhythm against a moving player, and the
  strays' arrow pressure under the cold are untuned.
- The whelp's hunt (anger, expiry, re-pointing) is `Whelp`'s own and is covered by its finale tests;
  this task only proves it is spawned, tagged and tracked.
- The vat lesson overlay fires once per floor on a 20 tick check; whether the Foreman can actually be
  kited into it reliably is a playtest question.
- The frostworks.json name on this lineage still says "The Frost Warden"; reconciling the carry-over
  rename with this branch is the integrator's merge job.

## Live check list

See `docs/playtests/live-checks/d5-deep-mine-guardians.md`.
