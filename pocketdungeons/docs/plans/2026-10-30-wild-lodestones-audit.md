# Wild lodestones: the keystone audit (task 2.2)

Written during phase 2 on `feature/wild-lodestones`. A wild record is `untimed = true`, `wild = true`, `owner = new UUID(0, 0)`,
has `layout.keystoneLevel() > 0` (so `isKeystoneRun()` is true), `roomCellOrigin == null`, `stagingCellOrigin == null`, and
**is in `Phase.ACTIVE`** (the `InstanceRecord` javadoc says untimed records "never leave HOME"; that no longer holds for a
wild one, which `Instances.startWildFloor` sets to ACTIVE directly, because `HOME -> ACTIVE` is not an allowed `RunSession`
edge). It never goes through `advanceFloor`, `completeRun`, `commitDoor`, the safe room or `returnToSafe`.

The first line of `Instances.tickRecord` after the Pocket2 branch is `if (record.wild) { WildRun.tick(...); return; }`, so
every site below that lives in `tickRecord` after that line is unreachable for a wild record. Each row says whether a wild
record reaches the site and what happens.

## `isKeystoneRun()` sites (23 in the plan, listed as found)

| Site | Reached by a wild record? | Result |
|---|---|---|
| `Instances.java` `isKeystoneRun() && stagingCellOrigin != null` (about 2116, `hasStagingRoom`) | yes, if asked | false: no staging room. Fine |
| `Instances.tickRecord`: `rewardGraceElapsed` and the spawner and content ticks | no | `WildRun.tick` returns first and runs the content ticks itself |
| `Instances.tickMember` exit pad branch | no | not called for a wild record (`WildRun.tick` has its own member sweep) |
| `PackPots` (`isPotRecord`, about 155) | yes | false: needs `inFloorLoop()` and a staging room |
| `PartyService` (about 380) | yes | needs `player == own.owner`; the nil owner never matches. Fine |
| `Pocket2` (about 208) | no | a Pocket2 door needs a campaign rare door; the wild floor has none. Fine |
| `RunLifecycle` about 357 (`registerUntimedRun` room cell) | no | wild does not use `registerUntimedRun` |
| `RunLifecycle` about 421 (`announceUntimedEntry`) | no | only the admin untimed path |
| `RunLifecycle` about 1044, 1053 (`exit`) | only through `exit`, which wild never calls | guarded by `inFloorLoop()` and `owner` anyway |
| `RunLifecycle` about 1153 (`returnKeystone`) | via `InstanceTeardown.purge` | `floor.keystoneReturned` is a set, the nil owner has no key; `returnKeystone ... NO_CHANGE` finds no online owner and does nothing. Verified live? No: see the open list |
| `RunLifecycle` about 1198 (`completeRun`) | no | only the exit pad, which `tickMember` reads and wild does not run |
| `RunLifecycle` about 1760, 1786 (`leaveSettle`) | via `dropMember` on a disconnect | needs `inFloorLoop()`, false for untimed. A no op |
| `RunLifecycle` about 2019 (`settleInterval`) | via purge | an empty interval; the nil owner has no log entry to pay. Open: confirm no `DungeonLog` entry is created |
| `RunLifecycle` about 2459 (`dropMember` leader left) | via a disconnect | `inFloorLoop()` is false. A no op |
| `RunLifecycle` about 2527, 2533 (`watchOwnerGrace`) | no | `ownerAbsentUntilTick` is never set for the nil owner (`admit` sets it only when the player is the owner) |
| `RunLifecycle` about 2614 (`quitDoor`) | no | `/dungeon quit` is absent from the wild tree |
| `RunStorage.opensHere` (about 131) | yes | `!inFloorLoop()` returns true, so the ender chest would open Dungeon Storage. Already handled: `RitualListener` skips `dungeonStorage` in wild mode (task 1.5) |
| `SidebarDisplay` (about 75) | yes | `OmenBar.shows(record)` is false for an untimed record. Nothing shown |
| `InstanceRecord.isKeystoneRun` itself | n/a | needs a non null `layout`; a wild record is registered only after its layout exists |

## `Phase.ACTIVE` and `RunSession.require` sites

| Site | Wild effect |
|---|---|
| `CapstoneFights.tick`, `Haunts.tick`, `PotionDrip.tick` early outs on `phase != ACTIVE` | the point of `startWildFloor`: they now run, from `WildRun.tick` |
| `FinaleWave` (155), `FrostFinale` (122) | run only on a finale floor of a node that has one; a wild node is a single floor, so they act as on any ACTIVE floor. Live check |
| `Instances.spawnOmenWave` (2321) | called from omen rises (deaths in the floor loop). Not reached: `allowDeath` returns true for a wild record before any omen change |
| `OmenBar.sync` (84) | `shows()` is false first; it removes any viewers |
| `PlayerContext` (198), `PlaytestJournal` (860) | read only; journal lines for a wild player are noise at worst |
| `RunLifecycle.completeRun` (1195), `Instances.previewDoor` (1175), `commitDoor` (1572), `returnToSafe` (2184) | `RunSession.require` refuses a phase that is not theirs; none is called for a wild record |

## `stagingCellOrigin` sites

`InstanceRecord.liveCells` and `InstanceTeardown` (127) read it null-safely. `FloorRooms`, `PackPots`, `PressureSources`,
`RelicObjective` all test for null before using it (read in this pass). `DungeonCommands` 884 and 958 are campaign commands
absent from the wild tree. `admitsToStaging` is `betweenFloors(record) && stagingCellOrigin != null`; `betweenFloors` needs
`floor.completed` non empty, which a wild record never fills (the phase 3 clear must not add to it either).

## Other classes read in this pass

- `HerobrineFight` `ALLOW_DEATH` (EARLY): returns `true` unless a Herobrine fight state exists for the record's slot. A wild
  node with the Herobrine capstone would need a player death handled by the fight; none of the shipped offerings is one.
- `CapstoneFights` and `Restless` `AFTER_DEATH`: act on mob deaths in the dungeon by `dungeonRecordAt`; not player deaths.
- `Lemon.java:177` `AFTER_RESPAWN`: not read in depth; it is a presentation hook. Live check after a wild death.
- `Instances.detach` calls `Keystone.showCompass` for the leaver: skipped for a wild record (it would create a `DungeonLog`
  entry for a player who never had a compass).

## Open and not provable in a gametest

The gametest server has no `pocketdungeons:void` dimension, so `Instances.admit`'s teleport, a real `InstanceTeardown.purge`
ejection of a wild record, and the `returnKeystone ... NO_CHANGE` path for the nil owner are unexercised. They are listed in
the phase 2 report as live checks.
