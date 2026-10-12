# RM-T20: Mineshaft finale dark phase

Branch `feature/rm-t20-mineshaft-finale` (off `feature/act-1-2-roadmap`, T11 and T12 merged in before the final suite).

## What changed
- `MineshaftFinale` (new, registered for `pocketdungeons:mineshaft` in `FinaleScripts.registerAll()`; `FinaleWave` untouched). `onHalfDead` plays a creak and shows "The beams creak." on the action bar. After the tell, `onTick` removes the torches and lanterns of the final floor's cells in one bounded scan (recording each block state), plays a snuff sound, shows "Light a torch.", and adds the extra cave spiders through `ctx.spawnNearParty`, so they count toward the wave and the pad stays shut. `onWon` and `onFloorEnded` put every light back, but never over a block a player built in its place. Player-placed torches are never in the removed set, so they work.
- Spared: lights within 4 blocks (square) of the exit pad and the terminal; rooms with `requiresLight`; lever Ordeal rooms and any cell with an armed Ordeal; rooms the manifest cannot resolve; solid lights (redstone lamp, glowstone, jack o'lantern).
- Fairness choices: the spiders glow so the dark is readable without a torch; the wave cap (`MAX_WAVE`) bounds the batch; the data wave in `mineshaft.json` is unchanged.
- `FinaleRules`: `darkSpiders`, `darkTellTicks`, `nearPad` and constants (pure).
- Knobs (`PocketDungeonsConfig`): `mineshaftDarkSpiders` (0 to 10, default 3) and `mineshaftDarkTellSeconds` (2 to 10, default 3). Config fixtures regenerated (additions only).

## Tests
- Pure: `FinaleRulesTest` extended (spider scaling, caps, tell floor of 40 ticks, pad radius, text length); `ConfigKnobsTest` (106 knobs) and `ConfigCharacterizationTest` pass.
- Gametest: new `MineshaftFinaleGameTest` (2 tests, listed in `fabric.mod.json`): tell before dark, lights spared and removed, glowing spiders added and keep the pad shut, player torch survives, restore on win without clobbering a player block, restore when the floor ends mid dark.
- Counts: see the final line of this report.

## Known limits
- If the party kills the last remaining mobs inside the tell window, the finale is won before the dark falls (nothing is darkened then).
- Solid lights are left lit; the Mineshaft rooms use torches and lanterns.

## Only a live server can confirm
Whether the dark phase reads (creak, pause, lights going out), whether it is fair in an already dim shaft with poison spiders at party sizes 1 to 4 and without torches, and that the exit area looks right. See `docs/playtests/live-checks/rm-t20.md`.

## Handoff
Done:
- feature/act-1-2-roadmap merged in (35db82fa); fixtures regenerated there.
- Cause of both failing gametests fixed in `src/gametest/java/pocketdungeons/MineshaftFinaleGameTest.java` (commit 4625f9e8). The test layout put the terminal at the cell origin, so the "far" torch at (1,2,1) was inside the dark spare radius (4) and was correctly spared. The terminal is now `origin.offset(2, 1, 14)`. Slots moved to 9941 and 9942.
- `tools/ship.sh --only MineshaftFinaleGameTest`: "All 2 required tests passed".

Unfinished:
- No clean `tools/ship.sh --quick` yet. Every attempt died of JVM memory exhaustion (paging file too small, other sessions' java processes), not test failures; the last run ended with runGameTest FAILED and a "Native memory" error in `A:/tmp/ship-agent-a9a7ffde27c8a1507.log`. Counts could not be read, so before/after gametest counts are not recorded (base was about 251).
- `economy_game_test_clear_floor_redeems_the_keys_it_finds` failed once earlier; not re-checked.

Next steps:
1. Close other java/gradle sessions, or raise the Windows pagefile.
2. `cd pocketdungeons; tools/ship.sh --quick`; read counts from the log.
3. Add counts to this report and commit.
