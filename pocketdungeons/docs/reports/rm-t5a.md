# rm-t5a: the uncharted seam

Branch `feature/rm-t5a-uncharted-seam`, off `feature/campaign-endgame`. Step 1 of T5 only: no Chart Room visuals,
no `UnchartedGen`, no doors, no affix kinds.

## What changed
- `DungeonDef.Kind.UNCHARTED`: parses from `"uncharted"`, does not descend (floors like a dungeon). No switch on
  `Kind` was exhaustive over the old values, so nothing else needed a new arm; every `descends()` caller treats it
  as a normal dungeon.
- `Campaign.isUncharted(def)` now answers true for that kind (null safe). `isCampaignTrip` follows.
- `HallLayout.HALL_CHART = "chart"`. `DungeonDef` manifest parsing accepts hall `act`, `special` or `chart`; a `chart`
  hall must be a descent (endless or proving). `isHallDoor` and the specials list already skip it, so a chart dungeon
  never appears in an act row. `PackValidator` treats chart like special for theme reachability.
- `HallOffers.CHART_DIVE_ID = "pocketdungeons:endless_mine_chart"` and `HallOffers.chartDive(DungeonDefs)`: the dive
  slot lookup. Null until the dungeon loads with hall `chart` and an entry; logs one debug line.
- Tests: `HallDataTest` allows a chart hall descent; `DungeonDefTest` checks the kind, hall const, dive id and that no
  shipped dungeon is uncharted.

## For T5 main
- Place `HallOffers.chartDive(...)` on the Chart Room side wall; add the Chart Room to `HallOffers.Hall`/astrolabe.
- Build generated defs with `Kind.UNCHARTED`; `Campaign.isUncharted` needs no change.
- `Kind.UNCHARTED` has no uses in `ActProgress` or `DungeonMapText` yet: hide it from the checklist there.

## For T6
- Create `dungeon/endless_mine_chart.json` with `kind: endless`, `hall: chart`, an entry node. `HallDataTest` already
  accepts it; `EndlessMineRules.openMines` will list it (it filters on `descends()`), the astrolabe specials do not.
- `HallDataTest` skips the per-act door and special counts for chart hall files.

## Tests
`--pure-fast` green (127 pure test tasks). Full suite result is in the final reply.
