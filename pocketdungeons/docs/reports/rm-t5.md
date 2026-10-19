# rm-t5: the Chart Room and uncharted doors

Branch `feature/rm-t5-chart-room`, off `feature/campaign-endgame` (T0 to T4, T5a and T6 merged).

## Built
- `UnchartedGen` (pure): the generated dungeon (id `pocketdungeons:uncharted_<salt>`, kind UNCHARTED, 3 nodes on 3 layers,
  entry and final share a theme, middle may borrow), themes from ordinary dungeons of finished acts, loot band of the
  highest finished act, every floor at the compass, affix draw with at least half rule affixes (respecting minLevel and
  incompatible), repeat doors reroll. `middleObjective(salt)` is the T7 seam (returns spawners today).
- `UnchartedDoors`: bounded registry (cleared at stop), `DungeonDefs.byId` falls back to it, `TripView.def` reads
  `IntervalState.generatedDef` first (set in `Instances.startCommittedFloor`), party compass is the lowest member's.
- Chart Room: `HallLayout.CHART_ACT`, `nextPosition`, `HallOffers.Hall.chartOpen`, `HallRoom` signs (`Uncharted` plus
  theme), astrolabe text, titles. First dive offer uses `EndlessMineRules.diveFloorLevel(.., 1)`.
- `UnchartedFinale` (via `FinaleScripts.forDungeon` fallback): MineGuardians guardian for the final floor's act and a
  door-seeded slot, adopted into the finale, strongest mob leads the boss bar, `BossFight` phases (guard at 2/3,
  enraged at 1/3); data wave is the fallback if no guardian spawns.
- Affix `kind` (rule or stat, default stat) in `AffixDefinition`/`AffixMeta`, tagged on the shipped affixes.
- Uncharted never recorded as finished (`RunLifecycle.finishDungeon`), never in `ActProgress.actDungeons` or the checklist.
- Board title adds `Level N` for uncharted doors; map text says Uncharted.

## Deviations
- `cooked` is not a file: the Cooked affix is `ominous.json`, tagged rule. Rule set: blackout, restless, molten, explosive,
  voided, ominous, blood. Feral stays stat (never seeded, min level 999).
- The dive stands at an end of the main row, not a side wall (the room has one selector wall).
- Door step is 1 to 3 as the hall deals it today. No new config knobs (floor count, rule share are constants).
- Guardian hardness is `hardness(slot)` at lap 0; difficulty comes from the floor level scale.
- Hot-file touches: `Instances` (one line), `Keystone.offers` (uses `TripView.def`), `FinaleScripts` (fallback),
  `RunLifecycle` (first-finish guard), `DungeonScreen`/`DungeonMapText` (uncharted wording), `ActProgress` (filter),
  `FloorSoakGameTest` (split `soakDef`, no assertion changed).
- `sessionStateTest` fails on the base (PackPots, MineGuardians statics), not from this branch.

## Tests
- Pure `UnchartedGenTest` (affix kinds, determinism, distinct doors, finished-act themes, level, shape, band, rule share,
  sweep of 1800 dungeons through the validator). Pure green: 128 tasks before the final merge.
- Gametests `ChartRoomGameTest` (4) and `UnchartedSoakGameTest` (1: planner soak over generated floors, 14 dungeons, 0 hard).
- Full suite before the T6 merge: SUITE GREEN, 383. After the T6 merge: SUITE GREEN, 386.

## Live only
Commit and stamp in the real dungeon dimension, the guardians in real cells, party compass, the look of the signs and
board, scrap banking and a failed uncharted run (pack and pot). See `docs/playtests/live-checks/rm-t5.md`.
