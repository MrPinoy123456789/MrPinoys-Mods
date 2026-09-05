# M63 - Make custody and teardown recoverable - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `src/main/java/pocketdungeons/InventorySwap.java`, methods `enterVoid`, `leaveVoid`, `snapshotPlayer`, orphan restoration.
- `src/main/java/pocketdungeons/RoomStore.java`, methods `capture`, `save`, `place`.
- `docs/DISCOVERIES.md` traps 2 to 4, 8, 18 and 19. Trap 19 is the SavedData binding warning; custody durability depends on it.
- `docs/reference/SITUATIONS_SPEC.md` sections 11.10 and 11.12.

## Dependencies

```bash
grep -n "dungeonIntegrationTest" pocketdungeons/build.gradle.kts
grep -n "setStash\|setOrphan" pocketdungeons/src/main/java/pocketdungeons/DungeonLog.java
grep -n "saveRoomIfOwnerSync" pocketdungeons/src/main/java/pocketdungeons/RunLifecycle.java
grep -n "processClears" pocketdungeons/src/main/java/pocketdungeons/InstanceTeardown.java
```

First confirms M62's integration task exists. Rest confirm custody sidecars, room save hook and teardown path are present. If any absent, stop.

## Goal

No tested transition can silently lose or duplicate a player's room, currency or either inventory.

## Implementation plan

Run `build_mod` after each step.

1. **Reproduce failures first.** Build failing fixtures for: entry/exit with full inventories and cursor, orphan overflow, partial room delivery, station stale click, child teardown, slot reuse. Use `DungeonTestFixtures` from M62. Register `src/gametest/java/pocketdungeons/CustodyGameTest.java`, `EconomyGameTest.java`, `TeardownGameTest.java` in `fabric.mod.json`.
2. **Conservation assertions.** Add exact-count/component assertions for `Fuel.isFuel/bank/spendBanked/grant`, `Payout.deliver`, `RoomStore.capture/place`, `RerollStation.handleReroll`, `GambleStation.onUse`, `CubeStation.onUse`, `InstanceTeardown.processClears`. Verify no wrong-recipient reward, no second delivery after retry.
3. **Save-success propagation.** `RoomStore.save` success must reach `RunLifecycle.saveRoom` callers before any room clear. Do not overwrite a good backup with a failed or empty capture. Slot release waits for all cells, entities and tickets, including preview, staging, lower story and Pocket2 child.
4. **Operation journal.** Reconfirm `SavedDataStorage` and player-save durability in 26.2 jar. If the save boundary cannot be proven atomic, add `src/main/java/pocketdungeons/InventoryJournal.java`: operation ID, source/destination snapshot, prepared/committed state, durable write before mutation, startup reconciliation before inventory access, idempotent remainder delivery. Never infer committed transfer from item equality. Test process termination at every boundary, not just graceful `stop`.

## Constraints

- No global per-tick disk flush.
- No survival backup cleared on an ambiguous outcome. Preserve unknown item components.
- Full reset while a stash exists must restore or retain custody first.
- Another inventory-management mod is an explicit interoperability test, not guaranteed compatibility by server-only branding.
- No "failsafe under all circumstances" claim in completion docs.

## Verification

`build_mod` with tasks `inventorySwapTest`, `roomStoreTest`, `payoutTest`, `dungeonLogTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Headless: failure injection conserves every item/room or halts safely with a recoverable record. Client checks remain for real menus and cursor teleports. Done when all M44 gaps have named tests or explicit remaining client rows.

## Completion

- Append recovery protocol, fault matrix and residual disk-corruption limits to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Append remaining client rows to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M63-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
