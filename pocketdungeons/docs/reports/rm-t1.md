# T1 Mob curve: report

Branch `feature/rm-t1-mob-curve` (off `feature/campaign-endgame`, T0 merged). Spec: `docs/plans/2026-10-18-campaign-endgame-build-spec.md` T1.
Rule: `docs/DECISIONS.md` "2026-10-18: Campaign and endgame".

## What changed

- **Defaults** (`PocketDungeonsConfig`): `mobScaleBase` 0.65 to 0.85, `mobScalePerLevel` 0.008 to 0.02, `omenDangerScalePerOmen`
  0.15 to 0.1. New knob `omenDangerScaleActOne` (0.0, range 0 to 1) via `tools/new_knob.py`. Fixtures and
  `config/pocketdungeons.default.json` regenerated.
- **Pure rule** `DifficultyProfile.omenBonus(omen, act, actOne, fromActTwo)`: acts 0 and 1 use the Act One rate, act 2 and up the
  other. Javadoc numbers of `mobScale` and `MobScaling.applyMobScale` updated (level 8 about 1.01, level 20 1.25).
- **`MobScaling.applyMobScale(mob, level, omen, act)`** replaces the 3 argument form; the 2 argument form (omen 0) is unchanged
  in effect. `MobScaling.actOf(record)` returns the trip dungeon's act via `TripView.def`, or 2 when there is no dungeon
  (Uncharted doors and the endgame dive therefore use the Act 2 value; T5 needs no change here).
- **Callers passing the act** (one line each): `Instances` (2), `ClaimObjective` (2), `HeistObjective`, `TrappedCache` (the
  pending ambush record carries `act`).
- Not touched: `FeralContent`, `KennelSpecs` (omen 0 forms), `BossContent` and `MineGuardians` (bonus forms).
- Live check fragment `docs/playtests/live-checks/rm-t1.md`.

## Notes for the integrator

- Servers whose `pocketdungeons.json` already contains the three changed keys (a first boot writes every key) keep the old
  numbers until the keys are removed or edited. Deliberately not remapped.
- `BossContent` and the Mine guardians build on the same curve, so they also shift at a given level; worth a look at the playtest.

## Tests

| | before | after |
|---|---|---|
| Pure test tasks (`--pure-fast`) | 124 green | 124 green (cases added inside `DifficultyProfileTest`) |
| Config tests (`configCharacterizationTest`, `configKnobsTest`, `defaultConfigFileTest`) | green | green after regeneration (Gradle run) |
| Gametest methods | unchanged | unchanged (none added or removed) |
| Full suite (`--quick --no-dist`) | not run | NOT RUN, see below |

The full suite could not start. After the Gradle config tests ran, every `ship.sh` and `gradlew.bat` call died within 6 seconds
with `java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler` (log `A:/tmp/ship-agent-a3790e76a438feda5.log`).
Cause: drive C: is 100% full (169 MB free), so Windows cannot grow the pagefile and only about 2 GB of virtual memory
is free while the Gradle daemon asks for 2 GB. Per the disk rule I stopped rather than delete anything outside the worktree.
Run `tools/ship.sh --quick --no-dist` on this branch once C: has space. The change is small (a knob, defaults, one extra
argument at five call sites), but no gametest was run against it, including those that read mob health.

New pure cases in `DifficultyProfileTest`: `mobScale` at levels 3, 8, 12, 20 with the new defaults, and the act split of the omen
bonus (acts 0, 1, 2, 5, a raised Act One knob, negative omen). `DefaultConfigFileTest`, `ConfigCharacterizationTest` and
`ConfigKnobsTest` pass after regeneration.

## Only a live server can confirm

See `docs/playtests/live-checks/rm-t1.md`: Act 1 mobs do not harden after a rescue, Act 2 mobs do (about 10% per point), and
the early curve feels gentler.
