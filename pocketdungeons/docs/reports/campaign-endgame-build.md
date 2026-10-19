# Campaign and endgame: build report (2026-10-18)

Scope: Part A (T0 to T8). Part B not built (the scope line said Part A only). Branch `feature/campaign-endgame`,
nothing pushed, `master` untouched. Final `tools/ship.sh --quick --no-dist`: **SUITE GREEN, 394 required tests**
(base 357). `tools/check_dashes.sh` clean.

## Per task

| Task | Built | Tests |
|---|---|---|
| T0 Campaign seam | `Campaign`, knobs `releaseMaxAct` 2 and `capstoneDungeonsNeeded` 3, capstone after three dungeons, mine no longer gates, acts capped | pure rewrites; no new gametests |
| T1 Mob curve | base 0.85, per level 0.02, omen bonus per act (`omenDangerScaleActOne` 0, Act 2 on 0.1) | pure `DifficultyProfileTest` cases |
| T2 Quest-line compass | `QuestLine`, no campaign scrap, board and sidebar changes, wake beat, migration flag `quest_line_seen` with golden fixture | +5 gametests (`QuestCompassGameTest`) |
| T3 Lives grow | `Omen.maxLives`, knob `livesPerExtraMember`, fixed at the first door | `FloorLoopGameTest` +1, pure extensions |
| T4 Pack and pot | `PackHalf`, `PackPots`, pot persistence in `DungeonLog` (`pack_pots`, golden fixture) plus a journal file, Dungeon Storage at the Doors only | +15 gametests, pure `PackHalfTest`, `PackSpotsTest` |
| T5 Chart Room | seam (T5a) then `UnchartedGen`, three doors, rule/stat affix `kind`, guardian finale | `UnchartedGenTest` (1800 plan sweep), `ChartRoomGameTest`, `UnchartedSoakGameTest` |
| T6 Endgame dive | `endless_mine_chart.json`, Proving moved to hall `chart`, arena floors every third floor | `ProvingGameTest` 9, `EndlessMineRulesTest` chart case |
| T7 One heavy thing | `relic` objective, `heavy_core` Relic, refusals, pot drop, return to plinth | +8 gametests (`RelicFloorGameTest`) |
| T8 | live checks folded, code map, INTEGRATION.md, tune-knobs, decision "Built" line | n/a |

Test counts: base 357 (after T0 357, T3 363, T4 378, T6 381, T5 386, T7 394).

## Deviations from the spec

- **Pots persist (T4).** The decision said "Pots are floor state and never persist". The hard rule that pot contents
  must never be lost to a crash or restart wins: contents are saved with the owner and returned in full to Dungeon
  Storage if the run is gone. The decision text is amended. A fall inside the Doors or Home makes no pot; a floor with
  no safe spot makes no pot (the pack stays); a sealed Ordeal room is a last choice, not excluded; an offline member
  keeps their pack on a fail (their player file cannot be edited), their pots still settle.
- **T2:** the knob `compassCostFirstLevel` does not exist; the real one, `firstLevelScrapCost`, was retired, plus
  `actZeroScrap` and `endlessMineUnlockLevel`. Compass 1 costs 4 again, so three scrap-curve test expectations returned
  to their earlier numbers. Scrap is decided by trip, so a finished player earns none replaying Act 1.
- **T3:** mob danger from omen still caps at four deaths.
- **T5:** `cooked` is the `ominous` affix in the rule set. The dive sits at one end of the main row. No new knobs
  (floor count and rule share are constants).
- **T6:** Mine guardians keep their own phase machinery, not `BossFight` (it needs a `FinaleContext`). The dive level
  ignores the door step. The dive has no start text of its own.
- **T7:** if a floor has no spot for the plinth it falls back to a spawner floor. A relic dropped in an unloaded chunk
  could be returned to the plinth leaving a second copy; only one relic pays, once. Three new mixins
  (`BlockItemPlaceMixin` edit, `RelicSlotMixin`, `RelicMenuMixin`) are new server-side behaviour to review.
- **Live check numbering:** L104 to L106 already described the quest line, the pack and the Chart Room, and are now
  marked built. The task fragments folded as L107 to L114; the relic is **L114**, not L107.
- Orchestration: T5 was split into T5a (seam) and T5 main so T6 could start in parallel.

## Live-only checks (L104 to L114)

L104 to L106 and L109 to L112: quest-line text after each finish, pot placement beside a real door, take-only menu,
a fail halving pots and packs, a clean restart with a pot standing, Dungeon Storage refusal mid-floor, Chart Room
signs and the three doors, guardian phases in real cells. L107, L108, L110, L113, L114: campaign gates and the
capstone title, Act 1 omen feel, party lives (6 for two), dive arena floors in order, relic carry, refusals, rescue
into the pot, pad opening without it. A mixed-compass party on an uncharted door.

## Lemon knowledge pack

`tools/lemon/out/pack.md` still describes the old rules (for example line 579, `Compass N: p/price`, and Act 0's
scrap tutorial step, which can no longer fire). Not regenerated; no Lemon tools were run.

## Machine problems

Drive C: is full (47 MB free at the end) and `A:/tmp` holds over 650000 files, which made Gradle's startup die in the
jansi cleanup. Fixed by an optional `PD_TMP` in `tools/ship.sh` (use `PD_TMP=A:/tmp-ci`). Stale Gradle daemons also
needed `gradlew --stop`. Gametests `haunt_floor...keeper_stalks`, `trapped_cache...springs_one_ambush` and
`heist...vault_cracks_open` flake in full runs and pass alone; the final run was green.
