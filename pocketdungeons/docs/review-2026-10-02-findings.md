# Review findings: playtest 2026-10-02 fix batch

Reviewed 2026-10-02 by Opus 5.5 against `docs/review-2026-10-02-opus.md` (the request). Nothing was
changed in code; every item below is a finding for the next pass. Ordered by severity.

## Must fix before commit

### F1. Run storage moves dungeon items into the survival inventory (High)

**Status:** fixed 2026-10-02, pending in-game verify (L15). `returnAll` now writes to the dungeon pack
record with `keepForNextEntry`; `failRunOmen` calls `RunStorage.rollBackToInterval` (snapshot taken on
the first open each interval); `onUse` requires `record.inFloorLoop()`. Every staging room is also
stamped with the ender chest (`RoomTemplateGenerator.placeRunStorage`), gametest
`stagingRoomCarriesTheRunStorageChest`.

`RunStorage.returnAll` runs after `Instances.eject` and hands items over with `Payout.deliver`, which
puts them in the live inventory. After the eject that live inventory is the survival one
(`InventorySwap`: in the void you hold the dungeon pack, anywhere else your survival inventory). So
anything in run storage when the run closes crosses from the dungeon into survival. Offline members
are worse in the same way: `LostAndFound` is a survival recovery path.

Two follow-on holes:

- **Omen fail bypass.** `Instances.failRunOmen` restores each member's live inventory to the
  interval-start snapshot, but run storage is never rolled back. Stash the interval's loot (and
  floor echo shards) in storage, die at max omen, keep everything.
- **Visits and non-run records.** `onUse` keys on `InstanceRegistry.byMember`, which also holds visit
  records and build rooms. A visitor gets a storage on their visit record, and the visit's purge
  delivers it to survival. Answer to review question 1: no path reaches another player's storage
  (the container is keyed on the clicker's own UUID), and a player with no record still gets the
  M46 denial. But "has a record" is broader than "is in a run".

**Fix:** never deliver to the live inventory. Send each member's stacks to the dungeon pack with
`InventorySwap.keepForNextEntry(log, uuid, stacks)` (already persisted, already the sink for
`restoreIntervalSnapshot` overflow, works online or offline). In `failRunOmen`, drop or roll back the
storage the same way the pack is rolled back. Gate `onUse` on `!record.visitInstance && !record.adminBuild`.

On question 2 (crash): with the fix above, the remaining loss is a server crash mid-run, since
`record.runStorage` is in memory only. The pack survives a crash because it is saved with the player;
storage does not, so it is the one place a crash still loses items. Cheapest durable option: also write storage into the orphan record at every interval bank. A member
who leaves a party run early (detach, not purge) also waits until the whole run purges; returning on
detach would be tidier.

### F2. The fountain refills from a water bucket (High)

**Status:** fixed 2026-10-02: a drink also turns the pedestal to chiseled stone bricks, so a refilled cauldron is just a cauldron. `FountainGameTest` refills it and checks it gives nothing.

`Fountain.onUse` turns the water cauldron into an empty `CAULDRON` and leaves the chiseled pedestal.
Pour a water bucket in (vanilla, level 3) and it is a full fountain again with the same boon. Water
buckets are in `supply_tier_1..4` and the plumber bag. Infinite heals, food, and (the bad one)
infinite `CLEANSE` omen relief.

**Fix:** on use, also swap the pedestal to a neutral block (chiseled stone bricks, say), or replace
the cauldron with something that cannot be refilled. One line either way.

### F3. `DiaryReading.openBook` probably does not open, and can eat an item when it does (High)

**Status:** fixed 2026-10-02: the book goes to the client's hand slot with `ClientboundSetPlayerInventoryPacket`, then the open-book packet, then the real slot again. The server inventory is never touched and the restore list is gone. Live check L16.

- **Ordering.** `openItemGui` sends `ClientboundOpenBookPacket` immediately. The `setItemInHand`
  above it is only synced at the next `broadcastChanges`, and `openItemGui` skips that call when the
  book is already resolved (`DiaryDelivery.book` builds it with `resolved = true`). The client reads
  the book from its own hand slot, which still holds the old item, so it most likely opens nothing.
- **Item loss and duplication.** The restore writes to `MAIN_HAND` two ticks later, which is
  whatever slot is selected *then*. Scroll the hotbar in that window: the book stays in the old slot
  and the original item overwrites the item in the new slot (deleted). Log out, or stop the server,
  in the window: the restore is dropped (`player == null`) and the original item is gone; the
  player keeps a fresh tagged diary instead. `restores` is never cleared on stop, so in single
  player a stale restore can fire into the next world.

**Fix:** do not touch the server inventory at all. Send a client-only slot packet with the book for
the selected slot, then the open-book packet, then resend the real slot. The client copies the
pages when the screen opens, so nothing has to be restored server side and nothing can be lost.

## Should fix

### F4. PD-105 has a likely cause: client-side prediction (Medium)

**Status:** fixed 2026-10-02: `LemonBody.undoClientPrediction` resyncs the player's slots and Lemon's held item after every click. Live check L10 and PD-105.

The gametest cannot reproduce it because it is not server side. The client knows Lemon only as a
vanilla `Allay`. On an empty-hand main-hand click the client runs vanilla `Allay.mobInteract`, which
clears the allay's hand and calls `player.addItem(book)` locally. The player sees the book in their
hotbar and Lemon empty-handed until something resyncs. It matches the report exactly ("correctly has
the book, ... she gave me the book"), and it only started once the uncommitted `LemonBody` gave her
a `WRITABLE_BOOK`. At HEAD she held nothing.

**Fix:** after handling the click in `LemonBody.interact`, resync both sides:
`serverPlayer.inventoryMenu.sendAllDataToRemote()` and a `ClientboundSetEquipmentPacket` for Lemon's
main hand. Keep the regression test, but the live check (L-row) is the real verification.

### F5. PD-109 has a likely cause: the middle trap sits in the doorway lane (Medium)

**Status:** retracted 2026-10-02. The room selector only uses a template where its jigsaw doors match the plan exactly, and tripwire_hall's jigsaws are on the east and west walls, so doorways are never carved into the dispenser walls. A new gametest (`tripwireHallTrapsFireUnderTheTheme`) stamps the room with the playtest floor's theme and sees a trap fire. PD-109 stays open as a readability question; see BUGS.

`tripwire_hall` has three traps at x = 4, 7 and 10. Their dispensers are in the z = 0 and z = 15
walls at y = 2, and the hooks are at z = 1 and 14. The doorway lane is x = 7..8, y = 1..3
(`RoomGeometry.DOOR_MIN/MAX`), and the window band is x = 6..9. The room is a `corridor`. Whenever
a doorway is carved in a dispenser wall, the x = 7 dispenser is cut out, and the x = 7 wire runs
straight through the opening. The new gametest stamps the template without connectors, which is why
it finds all six. Move the middle trap out of x = 6..9 (for example traps at 3, 5, 10, 12, or just
two), and make the gametest stamp a layout with doors on every wall.

### F6. The failing gametest is a wrong assumption, not a bug

**Status:** fixed 2026-10-02: slime_pit expects one opening.

`sealedTwoStoryRoomsOpenToABlast` expects at least two openings between stories. I dumped
`structure/rooms/slime_pit.nbt`: the only opening is the ladder at (8, 1). The 10 by 10 slime area
at template y = 8 is a floor of slime blocks, not a shaft; the layer under it is solid stone. Change
the assertion to `>= 1` (or per room), or add a drop shaft to the template if that was the intent of
L11.

### F7. `TrialContent.genericPrefix` is fixed per cell position, not per run (Low)

It hashes only `cellOrigin`. Slot cell origins are reused, so a given cell rolls the same family,
and the same 5 percent of cells are chaotic, on every run in that slot. Normal and ominous do always
agree (one prefix per cell), and there is no preview path that reads it, so question 9 is fine.
Mix the plan seed in (`RoomContent.apply` already has it).

### F8. Silenced counts a use when it starts, not when it is consumed (Low)

`UseItemCallback` fires on the right-click that begins eating or drinking. Starting and cancelling
counts; so does every right-click on a throwable. `SILENCED_USES` is static, keyed by player, never
reset per floor or run, so a remainder carries into the next run. Reset it in the floor setup.

## Answers to the remaining questions

- **Q3 LemonArchive band + 100.** All consumers of `diaryBandsSeen` use `contains`
  (`diaryList`, `DialogRouter.readDiary`, `DiaryDelivery`, `addDiaryBand`). None count or iterate
  it except `LemonArchive.handedCount`. Safe today; a comment on the codec field would stop the next
  person from counting it. Two edges: a diary handed in by someone who never found it (traded book)
  counts toward their archive but never shows in their diary list; diaries delivered before this
  build carry no tag and cannot be handed in.
- **Q5 FloorHistory.** `FloorHistoryTest` matches the new API (`ending`, `duration`, `words`,
  `KEPT` all exist) and `gradlew test` passes. The row is 59 characters; at about 6 px a uniform
  glyph, 0.72 scale and 0.025 blocks a pixel it is about 6.4 blocks wide, inside the wall. The
  affixes column (10) fits two short names; a third is cut silently.
- **Q6 FloorStartTitle.** Fine for one server. Clear `RUNNING` (and `DiaryReading.restores`) on
  `SERVER_STOPPED`, for single player world switching.
- **Q7 SpiderUnstick.** Safe: both maps are touched from the server thread only, the
  `synchronized` is unnecessary but harmless, and `ENTITY_LOAD` fires for chunk loads as well as
  fresh spawns. Removed spiders linger in `TRACKED` until GC; an `ENTITY_UNLOAD` remove is cleaner.
  `floorBelow` ignores blocks with no collision, so a spider over lava is set down inside the lava.
- **Q8 Fountain placement.** The dead-end test is sound. The corner spots check air and a solid
  block below, so a bad corner is skipped rather than overwritten. Nothing else to add beyond F2.
- **Q10 PD-105.** See F4. On the server, no other path takes the book: `mobInteract` is PASS,
  `interact` returns before it, pickup is off, and non-owners cannot see her.
- **Q11 Config.** All ten new keys agree across field, getter, reset and write default.
  `gambleBlock` is fully gone.
- **Q12 Echo shards.** About 1 + 1.5 + 0.25 per Ordeal per three-floor interval, so roughly 2.75.
  That pays for one Greater door (3) per interval, which moves the sustainable ladder from all free
  doors to one Greater in three. Whether that is too generous is an owner call; it is a big step
  from the old "one shard on a free door finish". If it feels fast, `echoShardFloorChance` 0.33 is
  the gentlest lever.
- **Themed merchants, legacy read path.** The five field format puts the name last
  (`split(",", 5)`), so commas in new names are safe. An old four field entry whose name contained a
  comma would parse as five fields and be skipped. In practice store villagers die with the
  instance (the server stop purge), so saved worlds should not hold any.

## Housekeeping

- `LibrarianNPC.java:242` carries a `--` copied from `BlacksmithNPC`. New file, so it falls under
  the punctuation rule.
- `hs_err_pid54028.log` and `replay_pid54028.log` still need deleting, and logs, the jar and `run-*`
  stay out of any commit.
