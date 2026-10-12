# RM-T13 data fixes

Branch: `feature/rm-t13-data-fixes` (off `feature/act-1-2-roadmap`, merged again after T01, T11, T12 landed).

## Deepslate to four floors

- Removed `pale_gallery` and `collapsed_landing` and their edges. `silent_deep` is layer 4, still final.
- Layer 3 floors (Deep Quarry, Cracked Barracks, Tuff Cellars) each lead to `silent_deep` with a free door.
- `deep_dark_landing` moved to Tuff Cellars' `roomBias`. roomBias is a plain list with no cap (each entry is drawn at 3x weight), so it became the third entry. Tuff Cellars' note now reads "Barred vaults, fossils and a shrieker below. Iron and lapis."
- The `deviation` block and `dungeon_adventure` files name only themes, so they needed no change. Nothing else in code, tools or docs names the two removed floors, except `PromisedGearGameTest` (used `collapsed_landing` only as a seed key string; now `deep_quarry`) and historic entries in BUGS.md, LIVE_CHECKS.md and the freshness report (left as history).

### Deviation from the owner decision: the blood door

The 1 life door Deep Quarry to `silent_deep` cannot exist. The loader (`DungeonDef.exitProblems`, `edgeProblems`) requires every non-final node to have a cost 0 edge out, forbids duplicate edges, and requires edges to go layer n to n+1. With one layer 4 node, Deep Quarry's only possible edge is the free one, so a lives door to the same node would be a duplicate. I shipped the free door and dropped the blood door (`dungeonDefTest` and `tripDoorsTest` failed otherwise). A blood door needs a second layer 4 node or a validator change; the owner should decide. Also lost with Collapsed Landing: its 2 emeralds and `gear:weapon:2` promise (the LIVE_CHECKS line about it is now moot).

## Hidden ore

- Lush Caves (`rootworks.json`): `hiddenOre` pockets 1 to 2, size 1 to 3, blocks sand x3, clay x3, iron_ore x1. Sand and clay are not structural blocks, so the validator accepts them.
- Copper Works (`copper_works.json`): pockets 1 to 2, size 1 to 3, blocks coal_ore x2, iron_ore x2. Copper ore was left out on purpose: `HiddenOre.problems` rejects it (K2.8, copper left the game).
- `BoardGameTest.theBoardOnlyPromisesOreThePlanCanDeliver` assumed Copper Works had no hiddenOre; it now uses Deepslate as the palette-only dungeon beside the Ancient City.

## Tests

- New `DungeonDataFixesTest` (pure, registered in `build.gradle.kts` and wired into the check chain): every Deepslate path from first to final is exactly 4 floors, layers step by one, the removed floors are gone, layer 3 leads straight to the final floor, `deep_dark_landing` is on Tuff Cellars, and both hiddenOre blocks lists are right.
- `tools/ship.sh --quick`: before 248 required gametests; after 256 (SUITE GREEN; the rise includes tests merged in from T01, T11 and T12, since the new test is a pure test and not a gametest). The pure data tests (`dungeonDefTest`, `tripDoorsTest`, `floorNotesTest`, `dungeonDataFixesTest`) pass. `dist` is part of the quick run.
- No separate offline datapack validator exists beyond those tests; `dungeon admin validate` needs a live server.

## Live check

Fragment: `docs/playtests/live-checks/rm-t13.md` (Deepslate depth, Tuff Cellars shrieker, sand and clay in Lush Caves, coal and iron in Copper Works).
