# Long method refactor report

Branch `refactor/long-methods`, cut from `feature/bug-testing-and-refinement` (merged first; tip `3d5e75d`).
Audit item: `docs/audit-2026-10-10-report.md` item 6, the methods and signatures owned by this pass.

No behaviour change, no player visible string change, no on disk format change. One commit per method or signature
(two signatures share one commit where they share records, noted below).

## Method splits

| Method | Before | After | Shape |
|---|---|---|---|
| `DungeonCommands.register` | 474 | 11 | One `registerXxx(builder)` method per command group, called in the old order: `registerPlayerVerbs`, `registerPartyVerbs`, `registerRoomVerbs`, then `registerAdmin`, which builds the `admin` literal from eight contiguous `registerAdminXxx` methods and attaches it last (Brigadier snapshots a child when `then` adds it, so the admin subtree must be whole before it is attached). Largest new method: 75 lines. |
| `RitualListener.onUseBlock` | 320 | 60 | One claim method per block kind (`stripLegacyVaultKey`, `spyglassCoords`, `roomInspector`, `armedMineGuard`, `bagChest`, `dungeonStorage`, `roomContainerMask`, `roomPlacementMask`, `dungeonShellPlacementMask`, `stations`, `stagingFurniture`, `lodestoneMenu`). Each returns null when the click is not its own; `onUseBlock` takes the first non-null answer in the original order. |
| `LayoutStamper.stamp` | 176 (11 params) | 19 | Private `StampParams` record in; a private `StampRun` holds the up front work and the accumulators; `stampCell`, `requireReturnPath`, `overlayOwnerRoom`, `layoutOf`. The public overloads and `stampBehindLobby` keep their shapes (callers in `Instances` and `Pocket2`). |
| `BaseRoomSpecs.list` | 168 | 8 | `addEndCaps`, `addCoverageFloor`, `addFlavourLayer`, `addAuthoringReference`, same spec order. |
| `RoomValidator.validateAndReport` | 155 | 24 | `countGameplayObjects`, `runChecks`, `hasSpawnJigsaw`, `report`. |
| `DungeonDef.problems` | 152 | 17 | `layerProblems`, `edgeProblems`, `exitProblems`, `themeProblems`, `reachabilityProblems`; same problems, same order, same wording. |
| `DungeonScreen.previewContent` | 160 | 70 | `previewHeader`, `previewGets`, `dealSheet`, `addNoticeLines`, `previewNames`. |
| `RoomContent.apply` | 117 | 13 | `placeContent` (anomaly chest, situation handler, role operation) and `applyAffixHazards`; the store anomaly early return stays ahead of both. |

## Wide signatures

| Signature | Before | After |
|---|---|---|
| `RoomContent.apply` | 14 | 4: `CellSite` (level, origin, role, depth, spawns, seed, content), `CellRules` (profile, affixes, loot suffix, loot table override, theme), `voidedFloor`, `anomalyCell`. The old 13 and 14 parameter forms are gone (no caller besides `LayoutStamper` and one gametest). |
| `Situations.apply` | 12 | 3: `CellSite`, `CellRules`, `voidedFloor`. The `SituationHandler` interface keeps its 12 parameter shape so none of the dozens of handlers changed. Landed in the same commit as `RoomContent.apply` because both use the two records. |
| `LayoutStamper.stamp` (private) | 11 | 1 (`StampParams`) |
| `TrialContent.placeRewardContainers` | 12 | 6: `RewardRolls` (rolls, tier, vault rolls, vault tier) and `RewardLoot` (ominous, seed, loot suffix, table override). A thin 12 parameter overload stays because `RunLifecycle.completeRun` calls it and `RunLifecycle` is out of scope for this pass. The body is split into `rollBarrelLoot`, `mergeStacks`, `fillBarrel`, `fillCopperChest`, in the old order so the loot rolls draw the same randomness. |
| `LayoutPlanner.plan` | 14 | the loop now lives in `plan(PlanRequest)` (`PathProfile` plus the other nine fields). The 14 parameter overload stays as a one line delegate because `Instances` (out of scope) calls it. |
| `AffixEffects.build` | 12 | 7: `TrialTuning` (ominous, count multiplier, cooldown, player range), `Hazard` (kind, per cell), `Pools` (bonus tool pool, decor pool). `AffixMeta` and the nine `AffixMathTest` call sites updated; bound checks unchanged in order and message. |

Left for later agents: the 12 parameter `placeRewardContainers` overload (delete once `RunLifecycle` passes the records) and the 14 parameter
`LayoutPlanner.plan` (delete once `Instances` passes a `PlanRequest`). The 8 to 10 parameter `stampBehindLobby` and
`stampEntranceOnly` public overloads were not on the list and are called from `Instances`.

## Longest methods in the touched classes

Line counts include signature and braces.

| Class | Longest before | Longest after |
|---|---|---|
| `DungeonCommands` | `register` 474 | `stampTest` 88 (pre existing), then `registerPlayerVerbs` 75 |
| `RitualListener` | `onUseBlock` 320 | `selectDoor` 86 (pre existing), then `onUseBlock` 60 |
| `LayoutStamper` | `stamp` 176 | `applyConnectors` 86 (pre existing), then `stampCell` 80 |
| `BaseRoomSpecs` | `list` 168 | `addFlavourLayer` 88 |
| `RoomValidator` | `validateAndReport` 155 | `countGameplayObjects` 55 |
| `DungeonDef` | `problems` 152 | `fromJson` 114 (pre existing) |
| `DungeonScreen` | `previewContent` 160 | `previewContent` 70 |
| `RoomContent` | `apply` 117 | `placeContent` 69 |
| `TrialContent` | `applyEncounter` 122 (pre existing) | unchanged; `placeRewardContainers` 95 became 17 |
| `LayoutPlanner` | `plan` 81 | `plan(PlanRequest)` 83 (the retry loop, moved not split) |
| `AffixEffects` | `build` 24 | `build` 30 (the added local unpacking) |

## Determinism

Seeded planning and stamping are pinned, and both pins were taken on the code before the refactor.

- `PipelineProofTest` now plans seeds 0 to 199 twice, asserts the outcomes are equal, and folds them into one fingerprint
  (`-1966436295`). The value was recorded before `LayoutPlanner` was touched and is unchanged after.
- New gametest `StampDeterminismGameTest.stampIsDeterministicAndPinned` stamps one plan twice at one origin, with no
  affixes and with `voided` plus `molten`, asserts the blocks and trial spawner anchors agree, and compares the folded block
  states to `935166682`. That value was measured twice: on the base commit's main sources (`3d5e75d`, with only the
  new test added) and on the refactored tip; both gave `935166682`. The test is registered in
  `src/gametest/resources/fabric.mod.json`, so the gametest count is now 242 (it was 241).

## Tests

| | Pure `*Test` tasks | Gametests |
|---|---|---|
| Base (`3d5e75d`) | all pass | 241 of 241 |
| Tip | all pass (`PipelineProofTest` includes the new fingerprint) | 242 of 242 |

## Not verifiable without a live server

- The `/dungeon` command tree is rebuilt from the same literals in the same order; the gametests do not walk it, so a
  player typed command check on a live server is the only proof of every subcommand and its permission gate.
- The right click handlers in `RitualListener` are covered only where gametests reach them (bag chest, stations, levers);
  the dev spyglass and the room editor inspector are operator tools with no test.
- `DungeonScreen.previewContent` text is pinned by `BoardText` tests for its parts, not as a whole; the floor board
  and sheets are worth a look when a door is selected in play.
