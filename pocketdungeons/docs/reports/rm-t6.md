# rm-t6: the endgame dive

Branch `feature/rm-t6-endgame-dive`, off `feature/campaign-endgame` (T0 to T4 and T5a merged).

## What changed
- `dungeon/endless_mine_chart.json` (kind `endless`, hall `chart`, act 2, the Deep Mine's hidden ore and palette) with its own
  `dungeon_theme` and `dungeon_adventure` files that reuse the Deep Mine's processors (`theme_deep_mine`), room theme and
  descent. A separate theme id was needed: `DungeonDefTest` requires one dungeon per theme.
- `the_proving.json`: hall `special` to `chart`; file, kind and rooms kept. No astrolabe lists it and `openMines` skips every
  chart hall descent.
- `EndlessMineRules`: `isDive`, `diveAct` (highest finished act = `releaseMaxAct`), `diveLevel` (compass + floor, no cap),
  `diveCompass` (lowest member's compass), `actOf` answers the dive act, `openMines` skips chart hall dungeons.
- `ProvingWaves`: `armDiveFloor`, `isDiveGuardianFloor`, `isArenaFloor`, `spawnWave`; `tick` now runs the dive's beats
  (next wave, then the guardian through `MineGuardians.spawnIfGuardianFloor`, then the reveal); `padRefusal` covers the arena.
- New knob `provingWavesPerFloor` (default 2; the other Proving knobs keep their names and now drive the dive's arena).
- Floors 1, 2, 4, 5 are the plain mining floors; floors 3, 6, 9 are two cell arena floors (gate + `proving_arena`), tier A/B/C
  tables from the dive act via the existing `MineGuardians.payingTable`. Act mines are untouched.

## Hooks outside my files (minimal)
- `Instances`: `planFloor` (arena path length, room theme `the_proving`, room bias `proving_arena` on a dive guardian floor) and
  `armCommittedFloor` (one branch calling `ProvingWaves.armDiveFloor`).
- `Keystone.offers`: dive floors use `EndlessMineRules.diveFloorLevel` instead of the clamped mine level.
- `ActProgress.mineOf`: skips the dive (same act, id sorts before `endless_mine_deep`).
- `FloorState`: three in-memory fields (`arenaWavesLeft`, `arenaGuardianCalled`, `arenaFloorLevel`). No save format change.
- Tests: `NodePaletteTest` file count 26 to 27; `DungeonDefTest` shipped-data expectation (Proving no longer an act door);
  config fixtures and `config/pocketdungeons.default.json` regenerated for the knob.

## For T5 (the Chart Room)
Use `HallOffers.chartDive`; the dive's first floor level should be `EndlessMineRules.diveLevel(compass, 1)` so the board agrees
with `Keystone.offers` for later floors.

## Deviations
- "The act's guardian under `BossFight`": the Mine guardians keep their own phase and tick machinery (they are not
  `FinaleScript`s, and `BossFight` needs a `FinaleContext`). The dive calls them through the registry unchanged.
- Dive level ignores the door step (compass + floor exactly), so the three doors share a level.
- No dive specific start or checkpoint text; the Mine's lines apply.

## Tests
Pure: 127 tasks green. Gametests: `ProvingGameTest` 9 (was 6: three new, one adapted), `MineGuardianGameTest` 11 green.
`EndlessMineRulesTest` gained `testEndgameDive`. Full suite `--quick --no-dist`: 381 passed (one earlier run died on a native
malloc failure, a machine limit, and passed on rerun).

## Live only
`docs/playtests/live-checks/rm-t6.md`: the real door preview on floor 3 stamps a gate and ring, waves then guardian then pad,
and door boards show compass + floor.
