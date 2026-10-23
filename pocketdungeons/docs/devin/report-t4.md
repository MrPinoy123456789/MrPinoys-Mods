# Report: t4 astrolabe glyph

## Branch

- Branch: `devin/t4-astrolabe-glyph`
- Base: `devin/t3-glyphs` (`0a11c4d2`)
- Tip: `2828b38d`

## Test counts

| Suite | Before | After |
|---|---|---|
| `./gradlew test` (pure) | green | green (130 pure test tasks ran) |
| `./gradlew runGameTest` (All N required tests passed) | 402 | targeted run only: HallRoomGameTest 6/6 pass; the test itself adds none (it extends an existing class), full suite not rerun on this tip |
| `./gradlew dungeonIntegrationTest` | PASS | not rerun on this tip |

## What changed

- `src/main/java/pocketdungeons/HallRoom.java`: `placeAstrolabe` now spawns a `Glyph` (anchor with the turn hit box, `pocketdungeons:turn_astrolabe`, no data) and hangs the clock `item_display` and the act `text_display` off it as glyph visuals, same positions, scales and text as before; `HallRoom.register()` registers the handler, which calls `turn(player, sneaking)`; the owner check and the "The trip owner chooses." branch are gone from `turn()`; a successful turn calls `terminalActTurned(server, record, next)`, the T6 stub; the `UseEntityCallback` path is removed; `turn()` falls back to the interacting player's level when the pocket dimension is absent so the gametest world can exercise it.
- `src/main/java/pocketdungeons/Glyph.java`: small adjustment so a caller-owned tag (`HallRoom.TAG`) stays on spawned glyph parts.
- `src/gametest/java/pocketdungeons/HallRoomGameTest.java`: new `a_guest_can_turn_the_astrolabe`: a record with owner plus guest, acts 1 and 2 unlocked, `HallRoom.turn(guest, false)` moves `record.interval.hallAct` off the starting act.

## Decisions I made

- `turn()` resolves the level as `server.getLevel(DUNGEON_LEVEL)` first, then `player.level()` when that is null. In a real run the interacting player stands in the pocket dimension, so the fallback is a no-op there; the gametest server has no pocket dimension and this keeps the guest path testable without a dimension seam.
- `terminalActTurned(server, record, act)` exists as a private stub for T6; today it only keeps the call site.

## Deviations from the spec

None.

## Only a live server can confirm

- In a party of two or more, have a non-leader right click the astrolabe: the act line should advance, doors and signs repaint, and everyone sees the act title.
- Sneak right click the astrolabe as a guest: it should step backwards.
- Click the astrolabe with only one act open: "Only one act is open." and the refused chime, unchanged.

## Expected merge conflicts

- `HallRoom.java`: any work touching the selector wall or `turn()`.
- `Glyph.java` and `GlyphHandlers.java` if parallel glyph work landed.
