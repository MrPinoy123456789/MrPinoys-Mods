# T0 Campaign seam: report

Branch `feature/rm-t0-campaign-seam` (off `feature/campaign-endgame`, merged again before the final run, nothing to merge).
Spec: `docs/plans/2026-10-18-campaign-endgame-build-spec.md` T0 and "Shared definitions". Rule: `docs/DECISIONS.md`
"2026-10-18: Campaign and endgame".

## What changed

- **Knobs** (`tools/new_knob.py`, fixtures regenerated, `config/pocketdungeons.default.json` regenerated):
  `releaseMaxAct` (2, range 1 to 5) and `capstoneDungeonsNeeded` (3, range 1 to 6).
- **`Campaign`** (new, pure): `done(finished, all, maxAct)` plus the live forms `done(finished, all)` and
  `done(DungeonLog.Entry)`; `isCampaignTrip(InstanceRecord)` and `isCampaignTrip(DungeonDef)`; `cap`, `released`,
  `needed`. No stored flag was needed: `done` is "the capstone of act `releaseMaxAct` is in `dungeonsFinished()`", so a
  player who already finished the Ancient City is done at once and no golden fixture was added (no on-disk format moved).
- **`ActProgress`**: `capstoneReady` is "at least `capstoneDungeonsNeeded` of the act's ordinary dungeons, capped at the
  act's count" (an act of only a capstone is ready). `complete(act)` is "the capstone is finished" (an act declared with no
  capstone asks for every dungeon; an act with nothing declared asks nothing, as before). New `capstoneFinished`,
  `ordinaryDungeons`, and a pure `capstoneReady(act, finished, all, configured)`. `progressLine` reads `2 of 3 dungeons`
  (or `Act complete`); `remainingLine` reads `Act 1: 2 more dungeons.` then `Act 1: Spawner Dungeon is open.`.
  `mineLegDone` and `mineTarget` stay for the Mine's own record and `DungeonMapText`; nothing in the capstone or act rule
  reads them now. The `MineDepth` parameters are kept so callers compile.
- **Cap**: `DungeonProgress.unlockedActs` drops acts above `releaseMaxAct` (stored acts are never deleted; raising the knob
  brings them back). `onProgress` opens no act above it, and the "what is left" scan, the capstone-unlocked title and
  `HallLayout.nextAct` / `openingAct` (new `maxAct` overloads) respect it. The capstone-open chat line drops "clear it to
  open the next act" at the last act. `EndlessMineRules.opensFor` / `openMines` cap by act (new `maxAct` overloads).
- **Compass no longer gates campaign doors**: `HallLayout.statuses(act)` lost its `compass` argument and never returns
  `COMPASS_LOCKED` (the enum value stays so `HallRoom`'s switch compiles); `TripDoors.eligibleFirst` and `pendingCapstone`
  ignore `unlockLevel`; `lockedFirst` always returns an empty list; `EndlessMineRules.opensFor` / `openMines` no longer test
  `endlessMineUnlockLevel` (the Act 1 mine opens with Act 1). `HallOffers.lockedMessage` now says `Finish 3 dungeons first`
  (or `Not open yet`), never `Needs compass N`.
- **Tests rewritten to the rule** (intent kept): `ActUnlockTest`, `HallLayoutTest`, `TripDoorsTest`, `EndlessMineRulesTest`,
  and `CapstoneRulesTest` (not on the task list, but it asserted the old mine gate and compass gate). New pure cases: 3 of 5
  opens the capstone, 2 of 5 does not, the Mine is not needed, an act with 2 dungeons needs 2, a huge knob is capped,
  `releaseMaxAct` 2 hides a stored act 3 (acts, astrolabe cycle, mines), `Campaign.done`, `isCampaignTrip`.
- Live check fragment `docs/playtests/live-checks/rm-t0.md`.

## Decisions for the integrator and later tasks

- `isCampaignTrip` treats a descent (Endless Mine, Proving) as the endgame dive, so the Act 1 mine run is not a campaign
  trip even though its door opens with Act 1. `isUncharted` is a stub returning false; T5 replaces it.
- `endlessMineUnlockLevel` is now read by nothing but a comment in `Keystone`; the T6 task should retire or reuse it.
- Still reading the compass, owned by T2: `HallRoom` (`Compass N` second sign line, `COMPASS_LOCKED` case),
  `DungeonMapText` (`compass N` lines, `capstoneReady`/`mineLegDone` use), `DialogScreens` (`lockedFirst`, now empty).
- The title `Act N trials complete` (capstone unlocked) now fires after 3 dungeons, not the whole act; T2's quest-line
  wording should replace it.

## Tests

| | before | after |
|---|---|---|
| Pure test tasks (`--pure-fast`) | 124 green | 124 green (cases added inside existing classes) |
| Gametest methods (`@GameTest`) | 349 | 349 (none added or removed) |
| Full suite (`--quick --no-dist`) | not run | not green, see below |

The full suite ran twice and was not green either time, with different failures each run, none in code this task touches:
run 1 failed `heist_floor_game_test` (2) and `deepslate_finale_game_test` (1); run 2 failed
`haunt_floor_game_test_the_keeper_stalks...` and `trapped_cache_game_test_opening_the_cache...`. Every one of them passes
when run alone (`--only HeistFloorGameTest`, `DeepslateFinaleGameTest`, `HauntFloorGameTest`, `TrappedCacheGameTest`), and
`ProvingGameTest`, `HallRoomGameTest`, `GatherFloorGameTest` (the gametests that call the changed classes) pass. Timing and
shared-world flakes while other agents were running; the integrator's run decides.

## Only a live server can confirm

See `docs/playtests/live-checks/rm-t0.md`: doors of Act 1 lit at compass 1 and enterable, the capstone staying iron after
2 finishes and opening on the third with its title, the Act 1 mine open from the start, no Act 3 after the Ancient City
and a stored Act 3 staying hidden on the astrolabe, and the new `Finish 3 dungeons first` line on right click.
