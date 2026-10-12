# T31 report: Silent Deep Hush

Branch `feature/rm-t31-deepslate-hush` (merged `feature/act-1-2-roadmap` once, fixtures regenerated).

## What changed
- `DeepslateFinale` (FinaleScript) and `DeepslateFinaleRules` (pure), registered for `pocketdungeons:deepslate` in `FinaleScripts.registerAll()`.
- `PressureSources`: a small seam (`listeningRoom`, `handOver`, `release`, `takePulses`, `addHeard`, `heardOf`, `resetHeard`, `soundAnswer`) so a script owns a room's existing Heard meter; the sidebar shows the script's ceiling.
- Eight knobs: hushCeiling 16, hushPulseRise 4, hushBowRise 2, hushHitRise 2, hushDeathRise 1, hushSprintRise 1, hushQuietSeconds 15, hushWhelps 1. Fixtures and `config/pocketdungeons.default.json` regenerated.
- `silent_deep` room: 7 sensors and 1 shrieker (`FinalFloorEarlySpecs`), template `silent_deep.nbt` regenerated, floor note changed.
- Tell: heartbeat plus action bar line for 3 seconds, a warning at three quarters; then shriek, darkness, whelp(s). Never the Warden. Whelps leave on win and floor end.
- Tests: `DeepslateFinaleRulesTest` (pure, wired in build.gradle.kts), `DeepslateFinaleGameTest` (4 methods, slots 9965 to 9968; `--only` run: 4 of 4 passed). Live check: `docs/playtests/live-checks/rm-t31.md`.

## Handoff
Done: everything above, committed. `--pure-fast` passed apart from the default config file test, which was then fixed by regenerating the file; not re-run since.

Unfinished: the full `tools/ship.sh --quick --no-dist` has not gone green. Its last run failed twice with JVM "insufficient memory" crashes (runDungeonIntegrationTest and runGameTest), not test failures, because other agents were running. No full-suite count was obtained; the only count is 4 of 4 for DeepslateFinaleGameTest.

Next steps (from `pocketdungeons`): `tools/ship.sh --pure-fast`, `tools/check_dashes.sh`, then `tools/ship.sh --quick --no-dist` when memory is free; read for `SUITE GREEN`. Check that `dungeonIntegrationTest` accepts the regenerated `silent_deep.nbt`.

Live only: whether the room reads as listening; the shrieker can shriek on its own and darken without the tell (vanilla); whether one whelp is fair for a party of one; whether the default rises give a slow quiet fight and a fast loud one. Noise is sampled once per second.

Note: if the final floor drew no Silent Deep room (weighted, not guaranteed) the finale is the plain data wave.
