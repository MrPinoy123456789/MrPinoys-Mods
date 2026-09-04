# Bag Selection and resetkey Plan

Bags become the player's class: a starting inventory chosen once via a chest in
the safe room, locked to the keystone until a full reset. This plan covers the
bag selection chest, the DungeonLog bag field, the bag application on void
entry, the resetkey full-reset fix, and the room generator wiring.

## Design summary

- A player's bag is stored on their `DungeonLog.Entry` as a bag id string.
- On the first dungeon entry (or the first after a reset), a bag chest appears
  in the safe room. Right-clicking it opens a dialog menu listing all eight
  bags. The player picks one, confirms, receives the kit, and the chest
  vanishes.
- On every void entry, if the player has a bag assigned and no orphan record,
  the bag is applied fresh. If an orphan exists, the orphan is restored
  instead (it already holds the player's items from the previous session).
- The bag id feeds into `BagTags.seed` for the room generator's solvability
  pass, replacing the current `BagTags.pilgrim()` default.
- `/dungeon resetkey` becomes a full campaign reset: keystone progress, themes,
  fuel, run stats, bag, and orphan are all cleared. Unlockables (shells, diary
  bands, room name, public listing, recent visitors, task progress, bounties)
  are preserved.

## Phase 1: DungeonLog bag field

### 1.1 Add `bag` field to `Entry`

File: `DungeonLog.java`

- Add `String bag` as the 19th field on the `Entry` record (after
  `diaryBandsSeen`). Default: `""` (no bag assigned).
- Add null-guard in the compact constructor: `bag = bag == null ? "" : bag;`
- Add `Entry withBag(String bag)` wither.
- Update the `PartB` codec record and its `Codec.STRING` field:
  `optionalFieldOf("bag", "")`.
- Update every existing `withX` method to pass `bag` through unchanged (19
  withers, each adding `bag` to the constructor call).

### 1.2 Add mutator/accessor

File: `DungeonLog.java`

- `String bagOf(UUID player)` - returns the player's bag id, or `""`.
- `void setBag(UUID player, String bagId)` - sets the bag id on the entry,
  clamped to a non-null string. `""` clears it.

### 1.3 Update `NONE` default

The `NONE` default entry (line 252-254) needs `bag` set to `""`.

## Phase 2: resetkey full reset

### 2.1 Add a `resetCampaign` method on `DungeonLog`

File: `DungeonLog.java`

A new method that clears all keystone progress fields on a player's entry while
preserving unlockables:

Clears:
- `keystoneLevel` -> 0
- `keystoneAffix` -> `""`
- `pendingOfferLevel` -> 0
- `runsCompleted` -> 0
- `bestPathLength` -> 0
- `bestKeystoneLevel` -> 0
- `recentThemes` -> empty list
- `completedThemes` -> empty map
- `currentTheme` -> `""`
- `depth` -> 0
- `extractedPowers` -> empty set
- `fuel` -> 0
- `roomCompletions` -> 0
- `bag` -> `""`

Preserves:
- `unlockedShells` (room shells are unlockables)
- `diaryBandsSeen` (diary entries are unlockables)
- `roomName` (room setting, not keystone progress)
- `publicListed` (room setting)
- `recentVisitors` (log, not progress)
- `taskProgress` (meta-progression tracking)
- `bounties` (weekly, not keystone progress)

Also clears:
- `orphans` map entry for this player (via `setOrphan(player, OrphanRecord.NONE)`)
- `stashes` map entry for this player (via `setStash(player, StashRecord.NONE)`)

Implementation: build a fresh `Entry` from the preserved fields of the existing
entry, with all cleared fields set to defaults. One `entries.put` call, one
`setDirty`.

### 2.2 Rewrite `resetOwnKey`

File: `DungeonCommands.java`

The player-facing `/dungeon resetkey`:

1. If in an instance, quit the door (existing logic).
2. If in the dungeon dimension, exit to the overworld (so the stash/orphan
   clearing does not conflict with the live inventory swap).
3. Call `DungeonLog.resetCampaign(server, player)`.
4. Clear keystones from inventory and ender chest (existing `clearKeystones`).
5. Clear bag-tagged items from the player's inventory (new helper, since the
   player might be carrying bag items if they were in the void).
6. Deliver a fresh keystone [1].
7. Send a message explaining what was reset and what was kept.

### 2.3 Update the admin `resetKey`

File: `DungeonCommands.java`

The admin `/dungeon admin resetkey <target>` should also call
`resetCampaign` instead of just `setKeystone(target, 0, ...)`, so a moderation
reset is the same full reset a player can do themselves.

## Phase 3: Bag selection chest

### 3.1 Place the bag chest in the safe room

File: `Instances.java`

In `stampRoomShell` (line 635), after the furniture is placed, check whether the
owner has a bag assigned (`DungeonLog.bagOf(owner)`). If not, place a chest at a
fixed local coordinate in the safe room (e.g. `origin.offset(8, 1, 8)`) with a
custom NBT tag identifying it as the bag chest:

- Block: `minecraft:chest`
- Block entity NBT: `pocketdungeons:bag_chest` set to `1` (via `CustomData` on
  the block entity, or a marker tag in the block entity's NBT).

The chest is a transient fixture like selector doors: it is not part of the
room blob and must be cleared before `RoomStore.capture`.

### 3.2 Clear the bag chest on room save

File: `RunLifecycle.java`

In `saveRoom` (the method that clears selector doors and furniture before
capture), add a step to clear the bag chest block at its fixed coordinate if it
exists. Same pattern as `RoomTemplateGenerator.clearSelectorDoors`.

### 3.3 Intercept right-click on the bag chest

File: `RitualListener.java`

In `onUseBlock`, before the `RoomProtection.denyContainerUse` check (line 101),
add a check: if the block is a chest and it carries the `pocketdungeons:bag_chest`
tag, open the bag selection dialog instead of the chest UI, and return
`InteractionResult.SUCCESS_SERVER`.

The dialog is opened via `DialogKit.show(player, DialogScreens.bagPicker(player))`.

### 3.4 Build the bag picker dialog

File: `DialogScreens.java`

A `MultiActionDialog` titled "Choose your bag" listing all eight bags. Each
button carries the bag id in a `CompoundTag` context under a new key
`KEY_BAG_ID`, and dispatches `ACTION_SELECT_BAG`.

Body text explains: "Your bag is your class. You keep it until you reset your
keystone. Choose carefully."

Each button label is the bag's display name (e.g. "Mason's Bag"), and the
tooltip is the bag's blurb (e.g. "Stone, a pick, and the patience to use
them.").

New constants:
- `KEY_BAG_ID = "bag_id"`
- `ACTION_SELECT_BAG = "select_bag"`
- `ACTION_CONFIRM_BAG = "confirm_bag"`

### 3.5 Build the bag confirmation dialog

File: `DialogScreens.java`

A `ConfirmationDialog` titled "Confirm bag" showing the selected bag's name and
blurb, with a "Confirm" button that dispatches `ACTION_CONFIRM_BAG` carrying
the bag id, and a "Cancel" button that returns to the bag picker.

### 3.6 Route the bag actions

File: `DialogRouter.java`

Two new cases in `handle`:

- `ACTION_SELECT_BAG`: read `KEY_BAG_ID` from the payload, look up the bag,
  show `DialogScreens.bagConfirm(player, bagId)`.
- `ACTION_CONFIRM_BAG`: read `KEY_BAG_ID`, re-validate the player is in the
  dungeon dimension and has no bag assigned, then:
  1. `DungeonLog.setBag(player, bagId)`
  2. `Bags.apply(player, bagId)`
  3. Remove the bag chest block from the world
  4. Send a confirmation message
  5. Do NOT re-show the menu (the player is in the safe room and can now pick
     a door)

## Phase 4: Bag application on void entry

### 4.1 Apply the bag in `enterVoid`

File: `InventorySwap.java`

In `enterVoid` (line 534), after `applyKeystoneItem` and before
`restoreOrphanIfAny`, add:

```java
String bagId = log.bagOf(player.getUUID());
if (!bagId.isEmpty()) {
    InventorySwap.OrphanRecord orphan = log.orphanOf(player.getUUID());
    if (orphan.items().isEmpty()) {
        Bags.apply(player, bagId);
    }
}
```

The bag is only applied if there is no orphan to restore. If an orphan exists,
it already holds the player's dungeon items (including leftover bag items and
loot), so restoring the orphan replaces the bag application. A fresh bag is
applied on the next clean entry (after a successful room delivery clears the
orphan).

### 4.2 Update the `enterVoid` javadoc

The existing comment at line 530-532 says "The bag is not applied here. It is
applied when the player picks a door (spec 11.8)." Replace with: "The bag is
applied here, from the player's DungeonLog entry, if they have a bag assigned
and no orphan to restore. The bag is no longer per-door; it is the player's
class, chosen once via the bag chest in the safe room."

## Phase 5: Room generator wiring

### 5.1 Thread bag tags through `LayoutPlanner.plan`

File: `LayoutPlanner.java`

Add a `Set<String> bagTags` parameter to the full `plan` overload (the 10-arg
form at line 97). Pass it through to `RoomSelector.resolveDetailed(shape,
manifest, theme, bagTags)` at line 136.

Update all shorter overloads to pass `BagTags.pilgrim()` as the default, so
existing callers (tests, admin commands) are unaffected.

### 5.2 Pass the owner's bag tags from `generateBehindLobby`

File: `Instances.java`

In `generateBehindLobby` (line 776), read the owner's bag from `DungeonLog` and
compute `BagTags.seed(bagId, record.members.size())`. Pass the result to
`LayoutPlanner.plan`.

### 5.3 Pass the owner's bag tags from `buildLayout`

File: `Instances.java`

In `buildLayout` (line 421), the untimed/admin path, do the same: read the
owner's bag and pass `BagTags.seed` through to `LayoutPlanner.plan`.

## Phase 6: Tests and verification

### 6.1 Update `LodestoneMenuTest`

No changes needed; the bag picker is a separate dialog, not part of the
lodestone menu.

### 6.2 Add `BagSelectionTest`

A headless test for `DialogScreens.bagPicker` and `bagConfirm`:
- The picker lists all 8 bags.
- Each button carries the correct bag id.
- The confirm dialog carries the bag id through.

### 6.3 Add `ResetCampaignTest`

A test for `DungeonLog.resetCampaign`:
- Create an entry with all fields populated.
- Call `resetCampaign`.
- Verify keystone progress fields are cleared.
- Verify unlockables are preserved.
- Verify orphan and stash are cleared.

### 6.4 Update `BagTableTest`

No changes needed; the bag loot tables are unchanged.

### 6.5 Build and run

```
./gradlew compileJava
./gradlew build --offline
```

## Open questions for the user

1. **Bag chest appearance**: should it be a standard chest, or a different
   block (e.g. a barrel, a shulker box) to visually distinguish it from loot
   chests?

2. **Party members**: does each party member choose their own bag, or does the
   owner's bag apply to everyone? The spec says "the same for everyone who
   picked the same door," but with the new design there is no per-door bag.
   If each member chooses independently, each needs their own bag chest in the
   safe room.

3. **Existing players**: players who already have keystone progress but no bag
   assigned. Should they get the bag chest on their next dungeon entry, or
   should they be grandfathered with a default bag (e.g. Pilgrim)?

4. **Bag chest position**: `origin.offset(8, 1, 8)` is a rough guess for the
   center of the safe room floor. The exact coordinate should be verified
   against the room template's open floor space.
