# Report c-carryover: sub-agent carry-over verification and the Jack Frost rename

Branch: devin/c-carryover   Base: feature/act-1-2-roadmap at ef261a27
Status: done

## Tests
- Gametests: 35 of 35 passed with filter `pocketdungeons-gametest:*finale*` (covers MineshaftFinaleGameTest,
  OssuaryFinaleGameTest, KennelsFinaleGameTest, DeepslateFinaleGameTest, FrostFinaleGameTest,
  LushFinaleGameTest, InfestationFinaleGameTest, FinaleWaveGameTest, FinaleScriptGameTest). Baseline on the
  merged branch was 302 required tests passed.
- Pure tests: full `test` task green (BUILD SUCCESSFUL, 121 executed) before any change;
  `FrostFinaleRulesTest`, `DeepslateFinaleRulesTest`, `CowPitsTest` re-run green (the two finale rules tests
  had never been run by their authors; both pass as written).
- `dungeonIntegrationTest` (real dedicated server boot): PASS. The regenerated `silent_deep.nbt` loads on a
  real server.
- No test fails.

## What changed
- `src/main/resources/data/pocketdungeons/dungeon/frostworks.json`: elite renamed "The Frost Warden" to
  "Jack Frost" (owner decision, one-Warden rule).
- `src/test/java/pocketdungeons/FrostFinaleRulesTest.java`: name check updated to "Jack Frost".
- `src/main/java/pocketdungeons/FinalFloorEarlySpecs.java`: javadoc updated to the new name.
- `docs/playtests/live-checks/rm-t33.md`: fragment updated to the new name.
- No code, knobs or test registrations added.

## Carry-over findings
- T33 `big_freeze.nbt`: regenerated through a throwaway gametest; the output is byte identical to the
  committed file, so the earlier memory crash did not truncate it. Palette contains the five campfires, blue
  and packed ice, plinth blackstone, powder snow and snow layers. `RoomLibraryGameTest` (door lanes, spawns,
  chest reachable) covers the spec and passed in the merged 302 green run and in this verification.
- T31 `silent_deep.nbt`: also byte identical on regeneration; `dungeonIntegrationTest` PASS. The no-room
  fallback (plain data wave) has a dedicated test: `aFloorWithoutTheRoomIsThePlainFinale` (slot 9968), green.
- T20/T21/T22: the three finale classes all pass in the filtered run (35 of 35). The T22 open judgement
  (first pillager is the handler) is left as designed.
- Economy flake: not seen in this verification; watch it in the full suite run at the end.

## Decisions I made
- Elite name "Jack Frost" exactly as the owner gave it (no "The" prefix).

## What only a live server can confirm
- Whether "Jack Frost" reads right on the boss bar next to the other elites.
- The Frostworks cold and the Deepslate hush in real play (see docs/playtests/live-checks/rm-t33.md and
  rm-t31.md).

## Conflicts expected when merging
- None: four one line edits in files no other task touches except the T33 report fragment.

## Fragments
- docs/playtests/live-checks/<task-id>.md written: no (verification only; rm-t33.md fragment was edited in
  place for the rename)
- tools/check_dashes.sh clean: yes
