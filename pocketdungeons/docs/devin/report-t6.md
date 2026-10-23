# Report: t6 terminal

## Branch

- Branch: `devin/t6-terminal`
- Base: `devin/t5-act-data-rewards` (`5573f26e`)
- Tip: `5470829d`

## Test counts

| Suite | Before | After |
|---|---|---|
| `./gradlew test` (pure) | 131 tasks | 132 tasks, green (adds `TerminalSourceTest`; `RoomFurnitureTest` updated to the 12 by 5 region) |
| `./gradlew runGameTest` (All N required tests passed) | 402 | All 413 required tests passed (adds `TerminalGameTest`, 3 tests) |
| `./gradlew dungeonIntegrationTest` | PASS | PASS |

## What changed

- `src/main/java/pocketdungeons/Terminal.java`: new. Owns the terminal: stamps the 12 by 5 wall panel (concrete frame, screen, six act windows with sea lantern backlight when the act is open), summons per-glyph interaction entities for the three tabs and the act arrow, and renders Objectives (leader campaign quest line plus act checklist), Progress (acts open and done, dungeons, Endless Mine depth vs target, lives, haul, current run) and the existing History ledger. `turn` hook redraws the act page; `clear` drops entities by group tag.
- `src/main/java/pocketdungeons/StagingFurniture.java`: history panel bounds widened to along 2 to 13, rows 1 to 5; stamps the terminal wall.
- `src/main/java/pocketdungeons/RoomProtection.java`: the expanded terminal region is protected furniture.
- `src/main/java/pocketdungeons/DungeonScreen.java`: history constants widened for reuse; `summonHistory`/`updateHistory` removed (Terminal owns them).
- `src/main/java/pocketdungeons/FloorHistory.java`: refresh delegates to `Terminal.redraw`.
- `src/main/java/pocketdungeons/Instances.java`, `RunLifecycle.java`, `VisitService.java`: history callsites repointed to `Terminal`.
- `src/main/java/pocketdungeons/HallRoom.java`: astrolabe `turn` calls the terminal act-turn hook so the window and act page refresh.
- `src/main/java/pocketdungeons/InstanceRecord.java`: `terminalAct` field and terminal glyph-state map.
- `src/main/java/pocketdungeons/PocketDungeonsMod.java`: `Terminal.register()` wires the named glyph handlers at init.
- `src/main/java/pocketdungeons/DungeonProgress.java`, `ActRewards.java`: terminal redraw on act open and act complete so Progress stays current.
- `src/gametest/java/pocketdungeons/TerminalGameTest.java`: wall stamps and clears back to shell; an open act lights its window backlight; tab clicks switch views and refused clicks shake.
- `src/test/java/pocketdungeons/TerminalSourceTest.java`: source scan for the terminal conventions (glyph handlers registered, no `UseEntityCallback`, group tag cleanup, no forbidden dashes).
- `src/test/java/pocketdungeons/RoomFurnitureTest.java`, `build.gradle.kts`, `src/gametest/resources/fabric.mod.json`: bounds updated, registrations.

## Decisions I made

- The act-window arrow glyph is always spawned; clicks on locked acts are refused (shake) rather than silently ignored, matching the spec's clickable-object behaviour.
- `Terminal` reuses `DungeonScreen` display helpers (`scaleTransformation`, `encodeText`) and the shared history constants instead of duplicating them.
- Objectives and Progress read from `Campaign.unlockedActs`, `ActProgress`, `QuestLine`, `DungeonMapText`, `DungeonLog.Entry` and the live record; nothing invents parallel state.
- The terminal cell is keyed like other cell state so `InstanceTeardown.forgetCellState` clears it with the room.

## Deviations from the spec

- None.

## Only a live server can confirm

- Stand in the staging room: the wall left of the selector shows a 12 by 5 terminal with Objectives, Progress and History tabs and six act windows; lit windows are open acts.
- Click each tab: the screen switches views; clicking a locked act's arrow shakes and stays put.
- Turn the astrolabe as a guest: the terminal redraws to the new act page.
- Finish an act: its window lights and Progress updates without a relog.
- Break the staging room down and restamp it: no stray text displays or interaction entities remain.

## Expected merge conflicts

- `DungeonScreen.java`, `FloorHistory.java` with any screen or ledger work.
- `StagingFurniture.java` and `RoomProtection.java` with other staging-room furniture changes.
- `build.gradle.kts` and `src/gametest/resources/fabric.mod.json` test registration blocks.
