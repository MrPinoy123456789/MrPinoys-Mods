# Refactor report: split `Instances` and `RoomTemplateGenerator`

Branch `refactor/split-instances`, based on `feature/bug-testing-and-refinement` (merged into the worktree first). Audit items 9 and 10. No behaviour change, no player visible string change, no on-disk format touched. All moves are verbatim code; only qualifiers changed.

## Line counts

| Class | Before | After |
|---|---|---|
| `Instances` | 3,471 | 2,979 |
| `InstanceTeardown` | 430 | 564 |
| `MobScaling` (new) | n/a | 110 |
| `InstanceAdmin` (new) | n/a | 288 |
| `RoomTemplateGenerator` | 1,409 | 707 |
| `StagingFurniture` (new) | n/a | 724 |

## What moved where

1. **`MobScaling`**: `applyMobScale` (both overloads), `applyMobScaleBonus`, `scaleAttribute` and the two modifier ids. `instanceAt` and `inDungeon` sat in the same section but are position lookups, so they stayed in `Instances`. Callers (`FeralContent`, `KennelSpecs`, `BossContent`, the two `ENTITY_LOAD` and omen wave sites in `Instances`) now call `MobScaling` directly; no forwarders.
2. **`InstanceAdmin`**: the whole admin section (`adminBuild` x2, `adminBuildRoom`, `adminSaveRoom`, `adminLayout`, `adminPurge`, `adminPurgeByOwner`, `adminList`, `adminDiagnostics`, `capOrUnlimited`). 17 external call sites (`DungeonCommands`, `RoomBuilderCommands`, `DialogRouter`, `PressureSources` javadoc, `DungeonLoadTestEntrypoint`) updated; no forwarders. It reaches back into `Instances.buildLayout`, `lobbyLayout`, `admit`, `teleport` and `sendToWorldSpawn`, which were already package visible.
3. **Teardown sections.** Neither section titled "teardown" in `Instances` held teardown code: the first is the spawner clear watcher, the second is the exit pad test plus the room and dungeon cell position lookups. They are renamed "spawner clears (M22)" and "positions: exit pads, rooms and dungeon cells". What did clearly belong in `InstanceTeardown` was the "live cells" group: `clearCellSync`, `clearCells`, `storyDepth` (private), `standingSides`, `holdCell`, `releaseCells`. Moved there (`clearCells` already called `InstanceTeardown.forgetCellState`). Call sites updated in `Instances`, `RunLifecycle` (call sites only), `FeralContent` and `SeamGameTest`. `CellStateForgetTest` named `Instances.clearCells` in a source scan; it now reads `InstanceTeardown.java` for the same check (relocation only, same assertion).
4. **`StagingFurniture`** (new, instead of `HallRoom`): the runtime half of `RoomTemplateGenerator` is about 690 lines and is not hall specific (selector doors, commit lever and sign, bulb course including `clearBulbs`, door screen, history panel, go-home control, run storage chest, post selection doors, the wall lodestone, and the Astrolabe row helpers `setHallBulb`, `placeHallLever`, `clearHallLevers`, `hallLeverPos`, `hallCandidateAlongs`, door plane helpers, `HallBulb`, `HallDoorKind`). Folding it into `HallRoom` (415 lines) would have buried the non hall furniture, so it got its own class; `HallRoom` just calls `StagingFurniture`. The generator keeps only template tooling (specs list, `generate`, scratch region building, capture and save, `column`, `floorRing`, `concat`, shared constants like `CELL`). A scan confirmed nothing left in the generator referenced a moved name. Call sites updated across 13 main and test files, including a one line static import in `BaseRoomSpecs` (`placeWallLodestone`) and call sites in `DungeonScreen` and `RitualListener`.

Kept as is: door commit and preview methods, `onTick`, `register`, the `ENTITY_LOAD` and connection wiring. `SessionState` registrations in `Instances` (`pendingReturns`, `pendingJoinRecoveries`) are untouched.

Unused imports left behind by the moves were removed from `Instances` and `RoomTemplateGenerator`.

## Commits

1. `Extract MobScaling and InstanceAdmin from Instances` (these two moves share one commit because both edit the same region of `Instances` and its imports)
2. `Move the live cell clear, envelope and ticket helpers into InstanceTeardown`
3. `Move the staging room furniture out of RoomTemplateGenerator into StagingFurniture`

## Tests

Every commit compiled all three source sets and passed the pure `test` task; the tip passed `test runGameTest` under the suite lock: BUILD SUCCESSFUL, "All 241 required tests passed". No test was weakened; no source scan test was added (the existing `CellStateForgetTest` and `RoomFurnitureTest` already pin the moved code).

## Not verifiable here

Nothing live: all moved code is exercised by the gametests, but no server was started. Merge note for the next agent: `DungeonCommands`, `BaseRoomSpecs`, `DungeonScreen` and `RitualListener` received call site or import edits only, which may textually touch lines the parallel long method agents also edit.
