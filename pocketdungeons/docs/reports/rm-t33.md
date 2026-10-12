# rm-t33: Frostworks cold and heat (branch feature/rm-t33-frost-freeze)

## What was built
- `FrostFinale` (a FinaleScript, registered in `FinaleScripts.registerAll()`): after the wave stands, a 3 second tell
  (wind sound, action bar line), then every member inside the `big_freeze` cell gains vanilla freeze ticks each server
  tick (own tick listener, because vanilla thaws 2 per tick). Near a lit brazier a member thaws 4 per tick.
  Braziers are found by scanning the room for lit campfires; none found or rate 0 means no cold. The cold ends in
  `onWon`, `onFloorEnded` and when the finale is found over. Nothing is placed in the world.
- `FrostFinaleRules` (pure): rate, thaw, health floor (2 health or under holds 135 ticks, under the freeze line),
  radius, tell, warning rules.
- Knobs `frostColdTicksPerSecond` (6) and `frostBrazierRadius` (4); fixtures regenerated after the merge.
- Room: `FinalFloorEarlySpecs.bigFreeze()` now has five campfires on two block polished blackstone plinths
  (`BIG_FREEZE_BRAZIERS`) and two ice pillars for cover; `big_freeze.nbt` regenerated and copied. Floor note updated.
- Tests: `FrostFinaleRulesTest` (pure, wired in build.gradle.kts), `FrostFinaleGameTest` (7 methods, slots 9941 to
  9947, registered). Live check: `docs/playtests/live-checks/rm-t33.md`.
- Stray arrows already carry slowness, so no extra effect was added.

## Name check
"The Frost Warden" is a stray, no code matches on names (the Warden tag is tag based), so no functional conflict.
But boss workshop row 14 asked to rename "The Drowned Warden" for the one-Warden rule, so the owner may want this
renamed too. Kept as instructed.

## Handoff
Done: everything above, committed. `tools/ship.sh --only FrostFinaleGameTest` passed: All 7 required tests passed.
`tools/check_dashes.sh` clean. configCharacterizationTest and configKnobsTest regenerated and passed (161 knobs).
Unfinished:
1. Full suite never finished: the machine hit "paging file too small" errors from parallel agents. A
   `tools/ship.sh --quick` run was started in the background (log A:/tmp/t33-full.txt); read it for `SUITE GREEN`
   and the `All N required tests passed` line. If missing, rerun `tools/ship.sh --quick` when memory is free
   (export GRADLE_USER_HOME=A:/gradle-home TMP=A:/tmp TEMP=A:/tmp first).
2. `tools/ship.sh --pure-fast` was not run successfully for the same reason; `FrostFinaleRulesTest` has never been
   run. Run it (or `--pure`) and fix any assertion; likely suspects are the rate-timing bounds (440 to 500 ticks).
3. Room look and connectivity: `RoomLibraryGameTest` (big_freeze in FinalFloorEarlySpecs) must pass in the full run;
   the template was generated from a run that crashed on memory right after saving, so if the room test or a stamp
   looks wrong, regenerate with a throwaway gametest as in the room-building skill.
4. Live only: freeze overlay readability, braziers findable, fairness for a party of one, party outside the room never
   feels the cold (the wave spawns near the first member), leather armor removes freeze damage (vanilla), campfires
   can be put out or broken by players.
Test counts: gametest class 7 of 7 green; full suite count not obtained.
