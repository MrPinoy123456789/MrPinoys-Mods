# Door commit refactor report

Branch `refactor/door-commit`, cut from `feature/bug-testing-and-refinement` (merged first; base `74e3512`).
Audit items: `docs/audit-2026-10-10-report.md` items 6 (the long methods left in `Instances` and `RunLifecycle`) and 7 (the door
commit does irreversible work before it can fail).

Steps 1 and 2 change no logic, no player visible string and no on disk format. Step 3 changes one failure path and nothing else.

## 1. The two leftover overloads are gone

| Removed | Callers moved |
|---|---|
| `TrialContent.placeRewardContainers` (12 parameters) | `RunLifecycle.advanceFloor` now passes `RewardRolls` and `RewardLoot`; the argument expressions keep their order, so the loot draws the same randomness |
| `LayoutPlanner.plan` (14 parameters) | `Instances.planFloor` (was inside `previewDoor`) and `FloorSoakGameTest` build a `PlanRequest` with a `PathProfile`; the 13 parameter overload now builds the request itself instead of delegating to the deleted one |

## 2. Long methods split by phase

Line counts include signature and braces. Public and package signatures are unchanged (new private helpers only; `Instances.resetToLobby`
gained one overload that takes the level, `RunLifecycle.saveAndDespawnRoom` and `stampDoor` are package level so the new test can reach
them).

| Method | Before | After | Phases |
|---|---|---|---|
| `Instances.commitDoor` | 208 | 90 | `commitAffixes`, `stampBehindStaging`, `restorePreviewWindow`, `newFloorState`, `startCommittedFloor`, `announceOminousRoll`, `armCommittedFloor`, `refreshStagingAfterCommit` |
| `Instances.register` | 188 | 8 | `registerTick`, `registerLevelChange`, `registerDeathRescue` (+ `allowDeath`), `registerMobScaling`, `registerConnectionHandlers`, `registerServerLifecycle`, in the old registration order |
| `Instances.previewDoor` | 181 | 85 | `refuseRecipe`, `previewStillValid`, `planFloor`, `planOriginBehindStaging`, `stampPreview` |
| `Instances.onTick` | 162 | 26 | `tickRecord`, `rewardGraceElapsed`, `tickMember` (each old `continue` became a `return`) |
| `Instances.resetToLobby` (3 parameter form) | 110 | 59 | `clearRunCells`, `restampSafeRoom`, `stampLobbyStaging`, `pullMembersIntoRoom` |
| `RunLifecycle.commitDoor` | 192 | 34 | `checkCommitGates` (validate), `saveAndDespawnRoom`, `armedKeystone`, `stampDoor` (stamp), `settleCommit` (spend and announce) |
| `RunLifecycle.enter` | 172 | 84 | `closeFinishedRunOf`, `refuseAtInstanceCap`, `registerUntimedRun`, `announceUntimedEntry` |
| `RunLifecycle.completeRun` | 140 | 49 | `padGatesRefuse`, `announceFloorCleared`, `journalFloorComplete` |
| `RunLifecycle.advanceFloor` | 114 | 55 | `recordMineDepth`, `placeFloorRewards`, `summonNextStaging` |

Longest method per class: `Instances` 208 before, 91 after (`rescue`, not on the list, under 100). `RunLifecycle` 192 before, 90 after
(`settleInterval`). Nothing over 100 remains in either file.

## 3. The door commit failure path

### What was wrong

`RunLifecycle.commitDoor` seals, saves and despawns the safe room, then `Instances.commitDoor` clears the previous floor and the
standing cells (`resetForNextDungeon`) before it stamps. If the stamp threw, `Instances.commitDoor` cleared what it wrote and returned
false; the catalyst was refunded and the player read "The dungeon failed to build. Try another door." but the room and the previous
floor were already gone, `record.roomCellOrigin` was null and the phase was still `PREVIEW`.

### The fix

* `Instances.commitDoor`: when the stamp throws, it now calls `resetToLobby(server, level, record, false)`, the path `/dungeon quit` uses
  (restores the saved room blob at the slot origin, stamps a fresh staging room, re-arms the doors, pulls members into the room,
  returns the record to `HOME`). Its first step is `clearPreview(..., true)`, which cancels the preview and refunds the escrowed catalyst.
  `resetForNextDungeon` is now called with the level `commitDoor` already holds (`resetForNextDungeon(server, level, record)`); in
  production that is the same object the two argument form looked up.
* `RunLifecycle.stampDoor`: if `Instances.commitDoor` refuses for any other reason after the room was despawned (a content reload in
  flight, a stale preview key) and the room is still gone, it puts the lobby back the same way. A stamp failure has already done so, so
  the check `roomCellOrigin == null` keeps it from running twice.
* The failure message and the catalyst refund by the committing player's own keystone are unchanged; the refund is idempotent
  (`restoreCatalyst` clears the escrow), so the second refund finds nothing.
* Test hook: `LayoutStamper.failBehindLobbyForTesting` (package level static, false in production) makes `stampBehindLobby` throw before it
  writes anything.

### The test

`DoorCommitGameTest` (registered in `src/gametest/resources/fabric.mod.json`), 2 tests:

* `failedStampPutsTheLobbyBack`: stamps a real lobby (room, staging room, bag chest), arms a keystone with a 3 bone escrow, previews a
  door with the real `Instances.previewDoor`, saves and despawns the room with the real `RunLifecycle.saveAndDespawnRoom`, injects the stamp
  failure, calls the real `RunLifecycle.stampDoor`. It asserts: the call reports failure; the record is `HOME` and `canChooseDoor`; the
  record's room is the slot origin again; the staging room stands; the preview plan and cell are gone; a fingerprint of every block in
  the room cell and the staging cell equals the fingerprint taken before the commit; the 3 bones are back in the inventory and the
  keystone escrow is empty; and a fresh `previewDoor` then stamps and returns the record to `PREVIEW`.
* `aWorkingStampStartsTheRun`: the same scene with the hook off commits: phase `ACTIVE`, room stays despawned, nothing refunded. This pins
  that the hook is off by default and that the split `commitDoor` still commits.

Run against the OLD behaviour (hook and test in, fix out), the suite finished `244 GAME TESTS COMPLETE`, `1 required tests failed`:
`failedStampPutsTheLobbyBack`, "the record is HOME after the failure, was PREVIEW". `aWorkingStampStartsTheRun` passed. With the fix:
`All 244 required tests passed`.

### What the test cannot prove

* **Where the party stands.** The gametest server has no `pocketdungeons:void` (DISCOVERIES trap 18), so `Instances.teleport` into
  the dungeon dimension falls back to sending the player home and the members-in-dungeon checks are false. `pullMembersIntoRoom` is
  unchanged `/dungeon quit` code, but no gametest sees a player moved. Live check.
* **The real `commitDoor(player)` gate.** Its "waiting at the Doors for" check needs the party inside the dungeon level, so the test drives the
  save and despawn phase and the stamp phase directly and not the gates before them (which this change does not touch).
* **`resetForNextDungeon` clearing the previous floor in the test.** In the gametest it is called with the test level, so the previous floor
  clear is exercised only for floor 0 (nothing to clear). A failure on floor 2 or later is by the same code path but unproven; see below.
* **A real exception from deep in the stamper.** The hook throws before any block is written. The existing catch already clears whatever a
  real partial stamp wrote; the new part is only what happens after it.

### Behaviour to know about

* A stamp failure on floor 2 or later also goes through `resetToLobby`: the party lands in the Home room and the interval ends without
  a penalty and without `settleSafeVisit`. Scrap for cleared floors was already paid at each clear (J1), so the loss is the interval's
  floor count and omen, not the haul. The brief asked for the lobby; a gentler variant (stay in the staging room, `FLOOR_CLEARED`, preview
  cleared) would keep the interval, and is a one line change in `Instances.commitDoor` if the owner prefers it.
* If the owner is not the player who pulled the lever, the cancel refund now goes to the owner's held keystone, the same as a switched or
  cancelled preview; before, only the committing player's keystone was checked.

## Tests

| | Pure `*Test` tasks | Gametests |
|---|---|---|
| Base (`74e3512`) | all pass | 242 of 242 |
| After the splits (`f91d850`) | all pass | 242 of 242 |
| Tip | all pass | 244 of 244 |

## Not verifiable without a live server

* A player pulling the lever on a real server: the gates, the party teleport into the room after a failed stamp, and the message order.
* A real stamp exception (the hook is a stand in), and a floor 2 plus failure.
* The `register` split: event registration order is preserved by construction, but no gametest fires `ALLOW_DEATH`, the join and
  disconnect events or `SERVER_STARTED`. A join, a death in a run and a restart on the test server confirm it.
