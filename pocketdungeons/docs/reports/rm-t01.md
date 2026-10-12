# RM-T01: finale script seam

Branch `feature/rm-t01-finale-scripts` (base `feature/act-1-2-roadmap` merged first).

## Result

`tools/ship.sh --quick`: SUITE GREEN. Required gametests: 248 before, 253 after (5 new in `FinaleScriptGameTest`).
`FinaleWaveGameTest` and `FinaleRulesTest` are unchanged and pass.

## What was built

- `FinaleScript` (interface, no-op defaults, `FinaleScript.NONE`): `onTriggered`, `spawnWave` (return true to replace
  the data wave), `onWaveSpawned`, `onTick`, `onMobDied`, `onHalfDead`, `onWon`, `onFloorEnded`.
- `FinaleContext`: server, level, record, finale, online members, party size, tick count; `mobIds`, `liveMobs`,
  `alive`, `total`, `elite`; `addMob`, `spawnNearParty`, `freeSpotNearParty`, `spawnElite` (boss bar helper);
  `scriptState(Class, Supplier)`; `tell`.
- `FinaleScripts`: registry keyed by dungeon id. `register`, `unregister`, `has`, `forDungeon`, `forRecord`
  (never null; returns `NONE`). Later tasks add one line each in `FinaleScripts.registerAll()`; `PocketDungeonsMod`
  has the single `FinaleScripts.registerAll()` call after `CapstoneFights.register()`.
- `FinaleRules.halfDead(alive, total)`: `total > 0 && alive <= total / 2`.
- `FinaleWave`: `State` is now package-private and holds the script, the dead set, the half-fired flag and the script
  state map (dropped with the state; `STATES` is now `SessionState.clearedAtStop`). `tick` was split: `tick` does the
  lookups, `step(server, level, record, finale, members, due, name)` is the state machine (needed because the
  gametest world has no dungeon level). The elite spawn moved unchanged into `spawnElite`; `spawn`, `provoke`,
  `alive`, `freeSpotNear` are package-private.
- Skill `.claude/skills/finale-script/SKILL.md`; `docs/CODEMAP.md` regenerated.

## Behaviour notes

- Hook order per tick: bar update, `onMobDied` per new death, `onHalfDead` once, `onTick` (only while something
  lives), then the win check, which recounts alive so a script that adds a mob in a hook keeps the finale open.
- With no script the death, half and tick work is skipped entirely, so behaviour is as before.
- A hook that throws is logged and skipped; a throwing `spawnWave` falls back to the data wave. A buggy script cannot
  keep the pad shut.
- A wave of one is half dead only when it is dead (1/2 is 0), so `onHalfDead` then fires on the same tick as `onWon`.
- `onFloorEnded` fires only if the finale triggered, before the mobs are discarded. It also fires on teardown.
- Capstones keep `CapstoneFights`. Capstone scripts (T43) will reuse the same interface through a small adapter later.

## Tests

- Pure: `FinaleScriptsTest` (half-dead rule, registry lookup), wired in `build.gradle.kts`.
- Gametest `FinaleScriptGameTest`, registered in `fabric.mod.json`: hook order, an extra mob keeps the finale open, a
  replaced wave with an elite and bar, a throwing hook, and a dungeon with no script.

## Touched outside the task files

- `SessionStateTest`: allowlist entries for `FinaleScripts.SCRIPTS` and `PocketDungeonsConfig.KNOBS`, and removal of
  `FinaleWave.STATES` (now registered). `sessionStateTest` was already failing on `KNOBS` on the base branch and is
  not a dependency of the `test` task, so the suite never caught it; I only allowlisted it and did not wire it in.
- `PocketDungeonsMod`: one line.

## Only a live server can confirm

The real `FinaleWave.tick` path (dungeon level, real spawner clear, real boss bar display, title timing) is not run
by gametests; `step` is. A first scripted dungeon should be played once.
