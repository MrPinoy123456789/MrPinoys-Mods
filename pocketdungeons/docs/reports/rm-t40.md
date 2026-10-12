# Report rm-t40: the Endless Mine, one per act

Branch `feature/rm-t40-endless-mine` (off `feature/act-1-2-roadmap`, merged again at the end). Decision record:
`docs/decision-2026-10-11-endless-mine-per-act.md`. Live check fragment: `docs/playtests/live-checks/rm-t40.md`.

## What changed

- Engine: `EndlessMineRules` rewritten (guardian cadence, A/B/C tier rule, uncapped hardness, mine lookup per act,
  floor level from the mine's base level; layers and sealing removed). New `MineGuardians`: registry
  `register(act, slot 1..3, guardian)`, a `Spawn` carrying number, slot, lap and hardness, the placeholder ring of
  vanilla mobs, tagging, the pad gate and the paying-table choice. T41 and T42 register into it.
- Wiring: guardian spawns at floor commit in `Instances.armCommittedFloor`; the pad gate is in
  `RunLifecycle.padGatesRefuse`; the guardian floor's barrel uses `endless_mine/act<N>_<a|b|c>` through the existing
  loot table override. `IntervalState.mineDungeonId` replaces `mineSealedAct`. Theme, palette (`NodeStamper`), loot band
  and level follow the mine the run is in. The astrolabe shows the mine of the shown act.
- Data: `dungeon/endless_mine_deep.json` plus theme, adventure, processor list `theme_deep_mine`; six loot tables;
  `loot_tier_every` removed from the Act 1 mine theme. `endless_mine/` is exempt from the K policy in `loot_rules.py`
  and `LootRulesTest` (copper, gold, redstone asked for by the owner) - a minimal edit outside the task's files.
- Gate and saves: `ActProgress.MineDepth` (per act record; `reaches` treats the old single record as satisfying the new
  gate: 5, 11, 17, 23). `mineTarget(act, all)` is 3 only for an act with a mine in data, so acts 3 to 5 add a mine by
  data alone. `DungeonLog.Campaign` gains `otherMineFloors`, saved as optional `mine_floors` (omitted while empty);
  `deepest_mine_floor` unchanged. Golden fixture `dungeonlog/mine_floors(.written).json`; all old fixtures unchanged.
- Knobs: `endlessGuardianEvery` 3, `endlessGuardianHardnessStep` 0.25, `endlessGuardianLapStep` 0.5 (config fixtures
  regenerated, additive).
- Tests: pure tests `EndlessMineRulesTest`, `ActUnlockTest`, `CapstoneRulesTest`, `ZoneRulesTest`, `HallDataTest`
  (specials counted per act), `DungeonDefTest`, `NodePaletteTest`, golden test. New gametest class `MineGuardianGameTest`
  (5 methods: cadence and pad gate, per act wave, registered guardian hook, tier tables, reward barrel).

## Test counts

Gametests before: 248. After (before the second merge): 253, `SUITE GREEN` from `tools/ship.sh --quick`. The final run
is quoted in the reply.

## Docs now out of date (not rewritten)

- `docs/ZONES_SPEC.md` 3.5 (depth layers) and the layer rows of section 5.
- `docs/DUNGEON_STRUCTURE_DESIGN.md` D13 and the Endless Mine part of D29 (floors 5, 11, 17, 23); its section 10 text on
  the mine as a mode.
- `docs/concepts-2026-10-11.md` Endless Mine rows (now built as engine); `docs/CODEMAP.md` (new class `MineGuardians`,
  regenerate by the integrator).

## Only a live server can confirm

Real spawning and pathing of the placeholder wave on a stamped floor, the pad gate in play, the barrel contents after a
real clear, the astrolabe door for the Deep Mine, the Deep Mine palette and hidden ore, a real old save keeping its acts,
and every tuning question (cadence feel, hardness curve, tier C faucet). The gametest world has no dungeon dimension, so
a full descent is untested end to end.
