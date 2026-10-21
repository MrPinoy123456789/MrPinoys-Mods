# Report: rm-t222 brick tell (PD-222)

Branch: `feature/rm-t222-brick-tell` (worktree `.claude/worktrees/rm-t222`).

## Task

Owner report from playtest 2026-10-19-2: the custom blocks that change state
and eventually spawn silverfish should have a visual indicator "like a sparkle".
`CreepingBricks` bricks stage mossy -> stone -> infested -> burst over about
13 s, but the only tells were transition moments (crack burst and a sound at
plant and each swap) and a once-a-second shake on the last stage. For most of
its life an armed brick read as scenery.

## What changed

- `CreepingRules.tellTicks(stage)`: per-stage tell cadence, MOSSY 30, STONE 15,
  INFESTED 10 ticks. The cadence tightens as the brick ripens so the countdown
  reads without texture watching; all periods are multiples of the 5-tick poll.
- `CreepingBricks.Brick`: new `lastTell` field, set at plant.
- `CreepingBricks.Field.tick`: after the stage swap / shake branch, a brick
  whose tell period has lapsed emits `sparkle`: two END_ROD particles at the
  block centre with a small spread and minimal speed.
- New `sparkle` helper beside `crack`; class javadoc gained the PD-222 clause.
- `CreepingRulesTest`: asserts the cadence strictly decreases across stages and
  every period is a positive multiple of the poll.
- Live-check fragment `docs/playtests/live-checks/rm-t59.md` for the visual pass.
- No sound added, no per-stage particle variety, no knob: per the approved plan.

The tell rides `Field.tick`, so it covers the seeded rooms (Chewed Corridors,
Silverfish Nests) and `InfestationFinale`'s adds phase alike. It also makes
PD-220 diagnosable live: no sparkles means nothing planted; sparkles without
silverfish means the burst path is broken.

## Verification

- `tools/check_dashes.sh`: clean against master.
- `CreepingRulesTest` compiled and run standalone with javac: **passed**
  (caught an off-contract period of 8 on INFESTED; fixed to 10, a poll
  multiple).
- `./gradlew.bat compileJava --no-daemon` (512/768 MB caps): **BUILD SUCCESSFUL**.
- NOT run: `tools/ship.sh --pure-fast` started a 2 GiB Gradle daemon that the
  OS killed on native malloc (hs_err_pid25764.log: "There is insufficient
  memory for the Java Runtime Environment to continue"), matching the machine's
  reported memory pressure. The full `--quick --no-dist` suite was left to the
  integrator for the same reason; only the two files above changed and
  compileJava plus the covering pure test are green.

## Live-only

How the sparkle actually reads: density, size, and whether END_ROD reads as
"something alive inside" rather than magic on stone brick. Also whether a room
at cap reads as busy rather than noisy.
