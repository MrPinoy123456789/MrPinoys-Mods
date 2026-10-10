# Code audit 2026-10-10

Branch `refactor/code-audit-2026-10-10`, cut from `8a24a23` (the tip of `feature/bug-testing-and-refinement` when this run started; that branch has since gained `a752a31`, which only adds the audit prompt, so the two merge cleanly). Scope: `src/main/java/pocketdungeons` (about 256 classes), the mixins, and the tests that guard them. Instructions: `docs/code-audit-prompt-2026-10-10.md`.

Method: five scripts over the whole tree (names referenced exactly once, unused imports and private fields, loops over live collections that call removing methods, default values in `PocketDungeonsConfig` against the shipped JSON, method length and parameter count), then reading the riskiest hits: instance teardown, every static map, every `DISCONNECT` handler, every mixin, the door commit path, `DungeonLog.Entry`, the config loader.

## Summary

| Category | Found | Done | Left |
|---|---|---|---|
| Real bugs (own commit, BUGS.md entry) | 3 | 3 (PD-198, PD-199, PD-200) | 0 |
| Stale tests (failed at the branch base) | 2 | 2 | 0 |
| Dead code (methods, constants, imports, comments) | about 96 sites | 74 removed | 22 package level helpers whose purpose is the owner's call |
| Duplicated literals that must agree (tags) | 2 | 2 | 0 |
| Data and code drift (a list that must match another) | 3 | 3 tests added | 3 proposed |
| Long methods and wide signatures | about 44 methods over 80 lines, over 20 signatures with more than 7 parameters | 0 | all (need a live server or a design decision) |
| State and lifecycle gaps | 3 groups | 2 fixed (PD-198, PD-199) | 1 group proposed |
| Mixin findings | 3 | 1 (shared tag) | 2 proposed |

Tests, from `pocketdungeons`, `.\gradlew.bat test runGameTest --console=plain`:

| | Pure `*Test` tasks | Gametests |
|---|---|---|
| Branch base `8a24a23` | 95, all pass | 241 run, **239 pass, 2 fail** |
| Branch tip | 99, all pass | 241 run, 241 pass |

The two base failures were not caused by this audit (see "Stale tests"). No gametest class was added, so the gametest count stays 241; the four new tests are pure and are wired in `build.gradle.kts` (`mapOf` and `tasks.test.dependsOn`).

## Bugs found

### PD-198: power and trim disconnect cleanup raced the server thread (Low)

`PowerListener.java:81` and `TrimListener.java:92`. Each removed the leaver from `applied`, a plain `HashMap<UUID, EnumMap<...>>`, directly inside its `ServerPlayConnectionEvents.DISCONNECT` handler. PD-12 established that this event fires on Netty's IO thread for an abrupt disconnect, while the server thread calls `applied.computeIfAbsent` for every online player every tick (`PowerListener.java:152`, `TrimListener.java:158`).

Failure scenario: a client times out in the same moment the server thread is reconciling another player's worn gear. The `remove` and the `computeIfAbsent` interleave on one `HashMap`; an entry is lost (a power or trim bonus is applied twice on the next pass, because the "currently applied" record is missing) or the table is corrupted. Rare and not reproducible on demand.

Fix: capture the UUID and run the removal in `server.execute`, as `Instances`, `Lemon` and `PlaytestJournal` already do. Test: `DisconnectHandlerTest` reads every source file and fails if a `DISCONNECT.register(...)` body does not contain `.execute(`. It found 5 handlers (`Instances`, `Lemon`, `PlaytestJournal`, and the two fixed).

### PD-199: a Silenced player's consumable count carried into their next run (Low)

`PressureSources.java:181` (`forget`) and `:440` (`SILENCED_USES`). `silencedUse` sends an omen wave on every `silencedConsumablesPerOmen`-th (default 3) consumable a Silenced member uses. `forget`, called from `Instances.detach` whenever a member leaves an instance, cleared the dwell timer but not this count.

Failure scenario: a Silenced member eats twice in run A and leaves. In run B their first consumable is use number 3 and sends a wave at once. The map also held one entry per such player for the life of the process.

Fix: `forget` also removes the count. Test: `PressureForgetTest` seeds the private map by reflection, calls `forget`, checks only the leaver's entry went.

### PD-200: a kill during the config rewrite could leave `pocketdungeons.json` truncated (Low)

`PocketDungeonsConfig.java` `load` (old lines 416 and 435) and `setModuleOverride` (old line 1446). `load` rewrote the operator's file in place (open with truncate, then write) on every boot that found a missing or retired key; `setModuleOverride` did the same. A process kill or a full disk between the truncate and the last byte leaves an empty or partial file. The next boot logs "is empty" or a parse error, runs on in-memory defaults, and leaves the broken file alone, so every tuned knob is gone. `RoomStore` and `InventoryJournal` already write through a temp file and `ATOMIC_MOVE`.

Fix: one `writeAtomically(Path, JsonObject)` helper (sibling `.tmp`, then `ATOMIC_MOVE` over the live file) used at all three sites. The boot rewrite used to run while the reader was still open on the same file; the read now completes first (an atomic move over an open file fails on Windows). Test: `ConfigSaveTest` gains a check that no `.tmp` is left behind; its existing round trips cover the content.

### Stale tests: two durability gametests failed at the branch base

`VendorGameTest.vendorOffersCarryTheCapMendingAndHalfRateBuys` and `PlaytestFixGameTest.handedOverGearIsDurabilityCapped` asserted that a handed-over stack's max damage is at most the raw table value from `DungeonTools.durabilityCap` (iron sword 64). Since 2026-10-08 both `lootDurabilityPercent` and `craftedDurabilityPercent` default to 110, so the stack is 70 and the assertions failed ("minecraft:iron_sword has 70, cap 64"). The code is right (owner ruling in the config javadoc: all gear gets 10 percent more); the tests predate it. They now compare with `DungeonTools.scaledCap(cap, max(loot%, crafted%))`, so a vanilla 250 sword still fails them. This is the only edit to an existing assertion in the run; it makes the bound match the ruling, it does not drop a check.

Related drift, not changed: `DurabilityKnobGameTest`'s javadoc and `config/pocketdungeons.default.json` both say `craftedDurabilityPercent` defaults to 100; the code says 110.

## Refactors done

One row per commit, oldest first.

| Commit | Files | Smell | Change | Covered by |
|---|---|---|---|---|
| `89c8662` | `VendorGameTest`, `PlaytestFixGameTest` | tests stale against an owner ruling | bound is the scaled cap | the two tests themselves (now pass) |
| `349802f` | `PowerListener`, `TrimListener`, `DisconnectHandlerTest`, `build.gradle.kts`, `BUGS.md` | PD-198 | defer to `server.execute` | `DisconnectHandlerTest` |
| `f61ba4a` | `KnowledgeSpecs`, `SpurSpecs`, `TraversalSpecs`, `Instances`, `DialogScreens`, `RoomManifest`, `LayoutGraphGenerator`, `OmenBarText`, `RoomBuilderCommands` | dead code | removed `fillDoorwayTop`, `sealExitDoorway`, `placePartition`, `placeInteriorDoor`, `insidePos`, `resetKeyOption`, `Instances.restoreSafeRoom`, `RoomManifest.loadAnomaly`, constants `ACTION_QUIT_DUNGEON_CONFIRM`, `GUI_ROWS`, `WARN_OMEN`, and the pre-M70 `ENCOUNTER_WEIGHT` and `LOOT_WEIGHT` (the live weights are in the role definitions at `LayoutGraphGenerator.java:650`) | compiler plus the full suite |
| `9d8500b` | 26 files | 43 unused imports | removed | compiler |
| `24212e4` | `PressureSources`, `PressureForgetTest`, `BUGS.md` | PD-199 | `forget` clears the count | `PressureForgetTest` |
| `f05322b` | `EntryWitherTest`, `build.gradle.kts` | `DungeonLog.Entry` has 27 components and 20 hand written withers, several `int`s in a row; a swapped pair compiles | test only: builds an entry with a distinct value in every component, runs each wither by reflection, fails unless exactly the named components changed; fails on a wither with no row in its table | itself |
| `7db3a85` | `PocketDungeonsMod`, `Instances`, `Whelp`, `OmenWaveNoDropsMixin` | literal `"pocketdungeons_omen_wave"` in three files; a typo in one silently turns the no-loot rule off | `PocketDungeonsMod.OMEN_WAVE_TAG` | compiler plus the full suite (a compile time constant, so the mixin loads no extra class); no test reads the tag itself |
| `ebf544f` | `PocketDungeonsMod`, `Whelp`, `SonicBoomWhelpMixin` | the mixin kept its own copy of the whelp tag | `PocketDungeonsMod.WHELP_TAG` | as above |
| `ed2b9a9` | `Restless`, `Instances`, `CustomClickMixin` | `Restless.clear` had no caller though its javadoc says it runs at server stop; three comments describing finished milestones as pending ("empty until M46", "the mod's only mixin") | removed the method, fixed the comments | compiler |
| `be45f59` | `RoomSpecRegistryTest`, `build.gradle.kts` | `RoomTemplateGenerator.specs()` is a hand written list of 24 `XxxSpecs.list()` calls; a new family class not added to it compiles and its rooms are never generated | test only: every class declaring `static List<RoomSpec> list()` must appear in `specs()` | itself |
| `0b3ade6` | `PocketDungeonsConfig`, `ConfigSaveTest`, `BUGS.md` | PD-200 | `writeAtomically` | `ConfigSaveTest` |
| `f026222` | `MechanismSpecs`, `PressureSpecs`, `TrialContent`, `KnowledgeSpecs` | dead code | removed `placeComparator`, `placeIronDoor`, `placeDispenser`, `placeLootChest`, three `WALL_X` constants, `DOOR_Z0` and `DOOR_Z1` in `PressureSpecs`, 7 imports | compiler plus the full suite |

## Refactors proposed, not done

Effort: S under an hour, M a few hours, L a day or more.

1. **Stale shipped `config/pocketdungeons.default.json` (S, data change).** Nothing in Java reads it (only docs mention it), but it is the file an operator copies. It has 27 keys against the code's 100. Six defaults have drifted: `pathLengthMin` 5 (code 8), `pathLengthMax` 8 (12), `branchProbability` 0.35 (0.55), `loopProbability` 0.15 (0.30), `planAttemptBudget` 16 (32), `keystoneMaxLevel` 25 (100), plus `craftedDurabilityPercent` 100 (110). Five keys are retired and unread: `timerBaseSeconds`, `timerPerRoomSeconds`, `threeChestPercent`, `twoChestPercent`, `lateCompletionDepletion`. Shape: regenerate it from `PocketDungeonsConfig.defaultsJson()` and add a test that fails when the two differ. Left because the prompt forbids a data change.
2. **Four copies of every config default (L).** Each of the 100 knobs has its default in the field initialiser, `applyDefaults`, the `readX` call and `defaultsJson`. A script compared all four for all 100 and found them consistent today, so this is fragility, not a bug. Shape: one `Knob<T>` descriptor (key, default, validator, message) driving load, defaults and the JSON. Touches the file every tuning skill edits, so it needs an owner go-ahead.
3. **Orphan knob `salvageEmeraldsPerTier` (S, owner ruling).** Read, validated, saved and documented in `docs/reference/SALVAGE_PROPOSAL.md`, but no code calls the accessor; `SalvageStation` says gear salvage pays materials and XP, never emeralds. Either delete the key or give it a reader.
4. **58 copies of `x.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)` (M).** `DungeonWorldRules.applies(level)` exists as "one predicate so the mixins agree" and is used by two mixins; seven other mixins (`WitherBossMixin`, `ServerExplosionMixin`, both sculk sensor mixins, `ResultSlotMixin`, `CraftedDurabilityMixin`, `BlockItemPlaceMixin`) and about 50 other sites inline the comparison. Shape: route them through one helper. Left because `applies` carries a gametest hook (`everywhere`) and routing the rest through it changes what existing gametests exercise; the owner should say whether it should.
5. **`DungeonLog.Entry` (M or L).** 27 components, 20 withers. The new `EntryWitherTest` makes the current shape safe. Shape if wanted: group into sub-records (`Compass`, `RoomSettings`, `RunStats`) so withers copy 6 fields, not 27. The record is the save format, so this needs a codec compatibility test first.
6. **Long methods and wide signatures (M each).** Over 80 lines: `DungeonCommands.register` 474, `RitualListener.onUseBlock` 320, `PocketDungeonsConfig.apply` 227, `Instances.commitDoor` 208, `RunLifecycle.commitDoor` 192, `Instances.register` 187, `Instances.previewDoor` 179, `LayoutStamper.stamp` 176 (11 parameters), `RunLifecycle.enter` 172, `BaseRoomSpecs.list` 168, `DungeonLog.Entry` 166, `Instances.onTick` 162, `DungeonScreen.previewContent` 160, `RoomValidator.validateAndReport` 155, `DungeonDef.problems` 152, `RunLifecycle.completeRun` 140, and eight more between 80 and 125. Wider than 7 parameters: `RoomContent.apply` 14, `LayoutPlanner.plan` 14, `DungeonDef.Node` 13, `TrialContent.placeRewardContainers` 12, `AffixEffects.build` 12, `Situations.apply` 12, `LayoutStamper.stamp` 11. Shape: extract validate, spend, stamp and announce phases behind a small parameter record. The door commit paths cannot be verified without a live server, so none was touched.
7. **The door commit does irreversible work before it can fail (M, needs a live test).** `RunLifecycle.commitDoor` (`:509`) seals, saves and despawns the safe room (`despawnRoomBehindStaging`, `:623`), then calls `Instances.commitDoor`, which calls `RunLifecycle.resetForNextDungeon` (`Instances.java:1472`) before stamping. If the stamp throws, `Instances.commitDoor` clears what it wrote and returns false and the player reads "The dungeon failed to build. Try another door." The room blob is saved and the catalyst restored, but the previous floor and the standing room are already gone and the phase is still `PREVIEW`. I could not establish what state a retry sees. Shape: stamp first into the preview cells, then reset and despawn, or make the failure path put the lobby back (`resetToLobby`).
8. **Per-player and per-slot statics that are never cleared (S).** Grow by one small entry per player or slot ever seen: `IronDoorLatch.lastHint`, `Locks.NEAR_HINTED`, `PlacementNotice.LAST_SOUND`. Not cleared at `SERVER_STOPPING` (matters only if one JVM loads a second world): `Instances.pendingReturns` and `pendingJoinRecoveries`, `Restless.PENDING`, `PartyRewards.PENDING`, `HostileWolves.GUARDS`, `KennelSpecs.LOST_DOGS`, `PressureSources.ARMED` and `DWELL`. Shape: one `PlayerState` registry that `Instances.handleDisconnect` and the stop hook both walk. `HostileWolves.GUARDS` and `KennelSpecs.LOST_DOGS` self-clean when the entity lookup returns null, which on a force loaded dungeon chunk is correct.
9. **`Instances` does five jobs (L).** 3,466 lines: slot and member entry, stamping and previews, quit and rescue, a 260 line admin section, mob scaling (`applyMobScale`, about 110 lines at `:2979`), and the connection and tick wiring. The M9 C3 pass already moved registry, party, teardown and storage out. Next cuts: `MobScaling` and `InstanceAdmin`. It also has two sections both titled "teardown" (`:2730` and `:2791`).
10. **`RoomTemplateGenerator` mixes tooling and runtime (M).** It generates `.nbt` templates (a gametest and admin tool) and also holds runtime code the live game calls (`clearBulbs`, the Astrolabe door row). Shape: split the runtime half into `HallRoom`.
11. **Remaining package level dead API (S, left for the owner).** Referenced nowhere in main, test or gametest: `Bags.headlineItems`, `displayName`, `blurb`, `tableId`; `CubeRecipe.hasOutstandingEscrow`; `DialogScreens.diaryReader`; `DiscoveryFloor.fire`; `DungeonCommands.memento` (the J7 hidden command, intentionally kept); `DungeonLog.setKeyProgress`; `InventoryJournal.fileForTesting` and `forgetCheckedForTesting`; `Lemon.isQuiet`; `LootBands.offerTier`; `LootTables.bagTable`; `PowerListener.activePowersOf`; `RoomDsl.quad`; `RoomEditorKit.isAnnotationTool`; `RunMemento.isMemento` and `discoveryIdOf`; `SidebarDisplay.isHidden`; `ContentModules.rejections`; `ContentReload.current`; `Situations.registered`. Also `StagingSpecs` (an empty "until M55" placeholder with one call site) and the `DungeonLog.bounties` codec field ("delete it in a later pass").
12. **Mixins (S each).** `SonicBoomWhelpMixin` selects its target with a full descriptor string, the one injector that would break silently under a mapping change. Seven mixins gate on a raw dimension comparison (see 4). `SculkSensorPlayersOnlyMixin` and `CalibratedSculkSensorPlayersOnlyMixin` repeat the same dimension gate and players-only test. None touches more than it needs.
13. **A tie between `RoomSelector.ENCOUNTER_ROOMS` and the room manifests (S).** `RubbleRulesTest` checks each listed id has a handler in the sources, not that the room still ships: `sensor_gallery` has no manifest (retired in PD-183). Shape: a test that each id has a manifest or sits in an explicit retired set. The same applies to the string ids in `PressureSources.isSpur`.
14. **Non atomic writes elsewhere (S).** `RoomBuilderCommands.saveRoom` writes the editor's template in place (operator tool, low risk); `LostAndFound` appends with `Files.writeString`.

## Map of the ten largest classes

Line counts are physical lines. Caller counts are other classes that reference the class by name.

1. **`Instances` (3,466 lines, 32 callers).** The run engine's shell: slot allocation front, entry and re-entry, lobby and staging stamping, door preview and commit stamping, quit to lobby, rescue, exit, teardown front, mob scaling, an admin section, and the connection, tick, death and level-change wiring in `register()`. Registry, party, teardown, run storage and the lifecycle verbs were already extracted to `InstanceRegistry`, `PartyService`, `InstanceTeardown` and `RunLifecycle`.
2. **`RunLifecycle` (2,383, 15).** What a member and a party do inside a run: `enter` and re-entry, the door choice and the player facing half of `commitDoor`, exit and drop rules (the leaving rules live here: `leaveSettle`, `settleLeaderLeft`), `completeRun`, `advanceFloor`, interval settlement and banking the haul. Called from `Instances`, `DialogRouter`, `DialogScreens`, `InstanceTeardown`, `PartyService`, `Pocket2`, `DungeonCommands` and a few more.
3. **`DungeonCommands` (2,082, 2 callers: `PocketDungeonsMod` registers it, `RunLifecycle` uses a helper).** The whole `/dungeon` Brigadier tree and its handlers: player verbs, party, room, admin (about 40 subcommands), stamp tests and exports. One 474 line `register()`.
4. **`DungeonLog` (1,715, 35).** The per-player save file (`dungeon_log.dat`): `Entry` with 27 components and its codec in two parts, the `Campaign` record, and sidecar maps for stashes, orphan inventories, storage, recipe discoveries, run records and floor history. Read by nearly every system that asks "what has this player earned".
5. **`RoomSelector` (1,613, 5).** Turns a floor shape into rooms: mask and role matching per cell, the root distance solvability pass, rubble plugging, forced rooms, the anomaly pick. Called from `LayoutPlanner`, `PlanRenderer`, `PackValidator`, `DungeonCommands` and `PocketDungeonsConfig`.
6. **`PocketDungeonsConfig` (1,603, 49).** The flat store of about 100 runtime knobs: fields, accessors, load and validation, defaults, the default JSON, module overrides, and (now) the atomic writer. The most referenced class in the mod.
7. **`RoomTemplateGenerator` (1,410, 18).** Authors every room template from the `*Specs` families and writes `.nbt` files; also the furniture and Astrolabe door row helpers and some runtime clears. Called by the room families, the commands and the gametests.
8. **`DialogScreens` (1,399, 5).** Builds every dialog screen (lodestone menu, party, whitelist, lobbies, diaries, bag picker, reroll) and owns the `ACTION_` string ids; `DialogRouter` handles the clicks. Called by `DialogRouter`, `DungeonCommands`, `PartyService`, `RerollStation` and `RitualListener`.
9. **`TrialContent` (1,126, 15).** Places trial spawners, vaults and reward chests into stamped rooms, configures spawners by tier and theme, tracks situation spawners and tamed mobs. Called by the situation handlers and the encounter rooms.
10. **`InventorySwap` (1,081, 11).** The dungeon inventory swap: the 42 slot snapshot, the pure core, the reconciliation pass on level change and login, and the player adapter. Called by `Instances`, `RunLifecycle`, `InventoryJournal`, `LostAndFound`, `RunStorage`, `DungeonLog` (its record types), `DungeonDrops`, `Bags` and others.

(`Lemon`, 1,042 lines and 17 callers, is the eleventh: the playtest agent's in-world body, speech and tick. Its static maps are cleared on disconnect and at stop.)

## Unverifiable without a live server

- **PD-198.** The race is timing dependent; the fix is the same pattern three other handlers already use. A live session can only show nothing went wrong.
- **PD-199.** A Silenced player's wave cadence across two runs (second run's first consumable no longer sends a wave).
- **PD-200.** The atomic move on the real host. `ATOMIC_MOVE` with `REPLACE_EXISTING` is used the same way by `RoomStore` on the Kinetic host today; a kill mid-write cannot be staged.
- **The two tag constants.** Wave mobs and whelps still drop nothing and a whelp still never sonic booms. Mixins apply at launch with `defaultRequire: 1`, and the gametest server starts with them, so a missing mixin target would have failed the suite; whether the tag test still holds on a real wave is a play check.
- **Dead code removal.** A removed method cannot change behaviour unless it was reached by reflection or a string, and none was (each name occurred once across main, test and gametest sources, and every build and test run is green), but a play session would be the only proof for rooms whose builders lost a helper (`barred_vault` and the pressure and knowledge families).

## Follow-up pass

Requested after the first report. Same branch, same rules. Where this section and the lists above disagree, this section is newer.

### Tests before and after the follow-up

| | Pure `*Test` tasks | Gametests |
|---|---|---|
| Tip of the first pass (`28608f5`) | 99, all pass | 241 of 241 pass |
| Tip of the follow-up | 101, all pass | 241 of 241 pass |

### 1. Regression review of the first pass

Each first-pass commit re-read against the risks the coordinator named.

- **Removed code reached by reflection, a mixin or data.** Every removed method was `private` or package level and had no string, reflection or data reference: the removed `ACTION_QUIT_DUNGEON_CONFIRM` ("quit_dungeon_confirm") appears in no `DialogRouter` case and no resource; `ENCOUNTER_WEIGHT` and `LOOT_WEIGHT` were superseded by the role definitions; the `*Specs` helpers were private. Mixins are not affected (the two mixins I touched only swapped a literal for a constant). No regression found.
- **A constant shared between two meanings.** `OMEN_WAVE_TAG` is read by `Whelp` as its no-loot tag on purpose (the whelp is meant to drop nothing, as before) and written by `Instances` for wave mobs; one meaning, three uses. `WHELP_TAG` is separate from it and from `CapstoneFights.WAVE_TAG` (`.brood_wave`). Both are compile time constants, so `MOD_ID + ".whelp"` is the same string as before. No regression.
- **The atomic config write on Windows.** Found a real weakness, now fixed: the move cannot replace a file another program holds open (an editor), and the failure threw into `load`'s catch, which resets every knob to defaults and so discards the operator's values `load` had just applied. The old truncate-and-write failed the same way when the file was locked, so this is not new, but the move makes a locked file the likelier failure. The rewrite now has its own try block that logs a warning and keeps the applied values. The first pass already moved the read to finish before the write, which the move needs.
- **The deferred disconnect handlers.** `server.execute` runs the removal at the next server tick, and a rejoin is set up on the server thread, so the queued removal runs before the new `ServerPlayer` is created; even if it ran after, the effect would be one redundant re-apply, because the attribute modifiers are transient. No regression found.
- **PD-199 `forget` is called from `detach` only**, so the Silenced count now resets whenever a member leaves an instance, including between runs on the same life of the process. That is the intended reading of "per run" and matches the dwell timer.

### 2. The static maps named in the prompt

| Map | Torn down | Floor end | Disconnect | Server stop |
|---|---|---|---|---|
| `HostileWolves.GUARDS` | **not before; now yes** (`forgetCellState`) | **now yes** | n/a (keyed by cell) | covered by the stop hook's purge of every slot |
| `KennelSpecs.LOST_DOGS` | **not before; now yes** | **now yes** | n/a (keyed by cell) | covered as above |
| `PressureSources.ARMED` | yes | **not before; now yes** | n/a | covered |
| `PressureSources.DWELL` / `SILENCED_USES` | n/a | n/a | yes (`detach`; `SILENCED_USES` since PD-199) | process end only |
| `Whelp.ACTIVE` | yes (`CapstoneFights.teardown`) | yes (`CapstoneFights.floorEnded`, called from `advanceFloor` and `resetToLobby`) | n/a (keyed by slot) | covered by the purge |
| `CapstoneFights.STATES`, `FinaleWave.STATES`, `WitherFight.STATES`, `HerobrineFight.STATES` | yes | yes | n/a (keyed by slot) | covered by the purge; all four tolerate a null level |
| `LibrarianNPC` | holds no static collection (only the `findAllLibrarians` method); its librarians are entities in the cell, discarded by the clear | n/a | n/a | n/a |
| `Locks.ACTIVE`, `AltarOffering.ACTIVE`, `Ordeals.ACTIVE` | yes | **not before; now yes** | n/a | covered |

So the gap was in the floor-end column: the floor-to-floor path (`RunLifecycle.resetForNextDungeon` to `Instances.clearCells`) cleared blocks and entities but not the cell keyed maps. That is **PD-201** (new bug, Medium), fixed by routing both paths through `InstanceTeardown.forgetCellState`. `CellStateForgetTest` fails if a class gains a cell keyed `clear(BlockPos)` that `forgetCellState` does not call, or if either path stops calling it.

Other statics checked and found sound: `DiaryReading.reading` drains in its own tick loop; `StaggeredTitle`, `RoomScan` and `Lemon` clear at stop; `PlaytestJournal` clears at disconnect; `PartyService` clears per player. Still not cleared at stop (matters only if one JVM loads a second world): `Restless.PENDING`, `PartyRewards.PENDING`, `RoomEditorHistory`/`RoomEditorMetadata` (operator tools, cleared on save), `SidebarDisplay.HIDDEN`. Left as reported: S, low value.

### 3. S items from "proposed, not done" done now

| Item | Commit | Change | Covered by |
|---|---|---|---|
| 8, per-player hint maps | `8ede6a9` | `Locks.NEAR_HINTED`, `IronDoorLatch.lastHint`, `PlacementNotice.LAST_SOUND` now forgotten in `Instances.handleDisconnect` | compiler plus suite |
| 8, stop hook | in PD-201 | `Instances` stop hook clears `pendingReturns` and `pendingJoinRecoveries` | compiler plus suite |
| 11, `StagingSpecs` | `4a115f1` | empty placeholder family and its one call removed | `RoomSpecRegistryTest` (23 families now) |
| 13, `ENCOUNTER_ROOMS` against manifests | `b3f5db2` | new `EncounterRoomsShipTest`; `sensor_gallery` is the single listed retirement | itself |

Left, because they are M or L or need a ruling: items 2, 4, 5, 6, 7, 9, 10, 12 and 14, and the rest of item 11. Item 11's `SITUATION_SPAWNERS` in `TrialContent` is a further finding: a write-only map (four `put`s, no read, keyed by a finite set of spawner positions, so bounded). A pure removal, but its javadoc says it is kept for future gating, so it is the owner's call.

### 4. `config/pocketdungeons.default.json` and `salvageEmeraldsPerTier`, re-verified

- **The default JSON.** Re-checked: no Java, test or gradle file reads `pocketdungeons.default.json` (only docs name it). It still has 27 keys against the code's 100, 7 drifted values and 5 retired keys. Not fixed: correcting it is a data edit (changing values and adding dozens of keys), not a pure removal, and a test tying it to the code defaults would fail until that edit is made. The edit plus the tie test is one small change for the owner to approve: regenerate the file from `defaultsJson()` and assert equality.
- **`salvageEmeraldsPerTier`.** Re-checked: the accessor has no caller in main, test or gametest; the key is read, validated, saved and documented but changes nothing. Not removed: dropping a key from `pocketdungeons.json` means adding it to `RETIRED_KEYS` (every existing file then logs a "retired keys" warning), changes what operators see, and `SALVAGE_PROPOSAL.md` lists it. Owner ruling.

### 5. Parts of the tree the first pass covered only by script

The ten-class map stands. For the other roughly 245 classes the sweeps were the same scripts run tree-wide: names referenced once (twice, once with qualified static references), unused imports and private fields, loops over live collections that remove members (none), `catch` blocks (all log or are commented as deliberate), `getPlayer`/`getLevel`/`getBlockEntity` chains (all guarded or proven non-null by the preceding `instanceof`), threading (no thread, executor or delayed callback exists in main; `ThreadLocal` is used only in `BlockItemPlaceMixin` and `PlaytestBias`, both within one call), file writes (the config was the only in-place rewrite of operator data). Mixins: all 18 re-read; every injector that changes world behaviour is gated on the dungeon dimension, `CustomClickMixin` hands off to the server thread, the four accessors only read or write named fields.

### New bug numbers

- **PD-201** (Medium): a finished floor's per-cell state outlived its cells. Fixed.
- PD-200 amended in place with the "keep the applied values" change; no new number.
