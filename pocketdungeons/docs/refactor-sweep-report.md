# Refactor sweep report: dimension check, session statics, dead API

Branch `refactor/dimension-statics-sweep`, based on `feature/bug-testing-and-refinement` (merged again at the end, which brought the `DungeonLog.Entry` regrouping and the restored `DungeonLog.setKeyProgress`). No behaviour change and no player visible string change. No on-disk format touched.

## Per item

1. **One dimension check (audit item 4).** `DungeonWorldRules.inDungeon(Level)` and a `ResourceKey<Level>` overload, no test hook. 60 raw `dimension().equals(DUNGEON_LEVEL)` style comparisons (including seven mixins, the two `Instances` crossing checks and one `!=` in `RunLifecycle`) now call it. `applies` is `everywhere || inDungeon(level)` and is still used only by the two mixins that had it. `getLevel(DUNGEON_LEVEL)` retrievals and `teleport(..., DUNGEON_LEVEL, ...)` calls are untouched, as are the two `dungeonLevel` fields in `DungeonDrops` and `InventorySwap` (they are test hooks). Test: `DimensionCheckTest` fails on any raw comparison outside `DungeonWorldRules`.
2. **Statics registry (item 8).** `SessionState` with `perPlayer(map)` (dropped on disconnect, emptied at stop) and `clearedAtStop(map or list)`; a class registers a collection where it declares it. `Instances.handleDisconnect` calls `SessionState.forgetPlayer` (still on the server thread, PD-198) in place of the three separate `forgetPlayer` calls (those methods are deleted), and the stop hook calls `SessionState.clearAll`. Registered per player: `IronDoorLatch.lastHint`, `Locks.NEAR_HINTED`, `PlacementNotice.LAST_SOUND`. Registered at stop: `Instances.pendingReturns` and `pendingJoinRecoveries`, `Restless.PENDING`, `PartyRewards.PENDING`, `HostileWolves.GUARDS`, `KennelSpecs.LOST_DOGS`, `PressureSources.ARMED`, `DWELL` and `SILENCED_USES`, `Whelp.ACTIVE`. `DWELL` and `SILENCED_USES` are deliberately stop only, so a reconnecting owner keeps them as before. Test: `SessionStateTest` (behaviour, plus a source scan that every static `Map`/`List`/`Set` with a fresh initialiser is registered or named in an allowlist with a reason; 13 registered, 42 handled elsewhere). The allowlist marks four **KNOWN GAP** entries that are still not cleared at stop (`InventoryJournal.checked`, `SidebarDisplay.SHOWN` and `HIDDEN`, `RunStorage.live`).
   `Instances.java` edits were limited to two field declarations, the stop hook and the disconnect handler body.
3. **Dead API (item 11).** Removed: `Bags.displayName`, `blurb`, `tableId`; `DiscoveryFloor` (the class held only `fire`); `LootBands.offerTier`; `RoomDsl.quad`; `RunMemento.isMemento` and `discoveryIdOf`; `ContentReload.current`; `Situations.registered`; and a javadoc link to the already removed `DialogScreens.diaryReader`. Kept: `DungeonCommands.memento` (intentional), `DungeonLog.setKeyProgress` (a test uses it), `InventoryJournal.forgetCheckedForTesting` (`CustodyGameTest` calls it), `ContentModules.rejections` (`ContentSnapshot` calls it through a `ContentModules` reference; a first attempt to delete it failed to compile). A scan for main methods whose name occurs once in the whole tree found nothing further. Unused imports removed: **12** (5 gametest, 4 test, 3 main in `Bags`). Covered by the compiler for all three source sets and the full suite.
4. **Mixins (item 12).** `SonicBoomWhelpMixin` selects `checkExtraStartConditions` by name (Mixin applied at launch with `defaultRequire: 1`, so a bad selector fails the whole suite). The two sculk sensor mixins share `SculkHearing.ignores(level, context)`, which holds the dimension gate and the players-only test once.
5. **Ties (item 13).** `PressureSources.SPUR_ROOMS` names the spur content ids (`isSpur` uses it, null safe). `EncounterRoomsShipTest` now checks `ENCOUNTER_ROOMS` and `SPUR_ROOMS` against shipped manifests or the `RETIRED` set. `RoomSelector` has no further list of room ids (its other sets name tags).
6. **Atomic writes (item 14).** `AtomicFiles.write` and `writeString` (sibling `.tmp`, `ATOMIC_MOVE`, temp removed on failure). `RoomBuilderCommands.saveRoom` (template `.nbt` and metadata `.json`) and `LostAndFound.write` use it; the bytes are identical. Test: `AtomicFilesTest` (exact bytes, replace, failed write keeps the old file, `LostAndFound.write` leaves one `.log` and no temp). `PocketDungeonsConfig.writeAtomically` was left alone (not this task's file).

## Test counts

| | Pure `*Test` tasks | Gametests |
|---|---|---|
| Start of this task | 101 | 241 of 241 |
| Tip (full `test runGameTest`, BUILD SUCCESSFUL) | 104, all pass | 241 of 241 pass |

## Unverifiable without a live server

- Whether `SonicBoomWhelpMixin` still suppresses a real sonic boom (it applies at launch; no gametest fires a Warden).
- The atomic move on the Kinetic host for the editor save (same call `RoomStore` uses today).
