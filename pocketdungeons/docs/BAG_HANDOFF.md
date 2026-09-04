# Handoff: Bag Selection and resetkey Implementation

You are continuing work on the Pocket Dungeons Minecraft mod (Fabric, server-only, Java 21). The workspace is at `A:\MrPinoys Mods`, the repo at `A:\MrPinoys Mods\pocketdungeons`. Read `A:\MrPinoys Mods\CLAUDE.md` before writing anything.

## House rules

- No em dashes or double hyphens as punctuation in any text you write (comments, javadocs, player-facing strings, markdown). Use colons, semicolons, commas, parentheses, or two sentences instead. Command-line flags and code operators are exempt.
- Do not mass-rewrite unrelated old comments.
- Follow existing code style: compact code, idiomatic Java, no excessive error handling.
- Use the `.devin/` directory for new configuration, never `.claude/` or `.cursor/`.

## The task

Implement bags as the player's "class": a starting inventory chosen once via a chest in the safe room, locked to the keystone until a full reset. The full plan is in `A:\MrPinoys Mods\pocketdungeons\docs\BAG_PLAN.md`. Read it first.

## What is already done

Prior work in this repo already completed:
- The inventory persistence fix (PD-65): dungeon inventory survives all exit paths via orphan-first persistence. `InventorySwap.leaveVoid` stashes an orphan before delivery, clears it only on full delivery.
- The `/dungeon quit` redesign: `RunLifecycle.quitDoor` applies the keystone penalty then calls `Instances.resetToLobby` instead of ejecting the player. The free-door (door 1) depletion exemption was removed, so all doors deplete on quit/timeout/late completion.
- The "Quit Door" button was added to the lodestone menu with a confirmation dialog.
- `Bags.java` already exists with all 8 archetypes, `Bags.apply(player, bagId)`, `Bags.byId`, `Bags.tagsFor`, and headline items. The `apply` method is written but never called.
- `BagTags.java` already exists with `BagTags.seed(bagId, partySize)` and `BagTags.pilgrim()`.
- Bag loot tables exist at `src/main/resources/data/pocketdungeons/loot_table/bags/{mason,plumber,sapper,magician,ranger,shepherd,innkeeper,pilgrim}.json`.
- `BagTableTest.java` validates the loot tables.

## What needs to be done

Follow the 6 phases in `BAG_PLAN.md`. Here are the key implementation details:

### Phase 1: DungeonLog bag field

File: `src/main/java/pocketdungeons/DungeonLog.java`

The `Entry` record is at line 142. It has 18 fields. Add `String bag` as the 19th, after `diaryBandsSeen`. Default `""`.

- Compact constructor (line 149): add `bag = bag == null ? "" : bag;`
- There are 12 wither methods (lines 174-244). Each constructs a new Entry with all fields. Every one needs `bag` added to the constructor call. They currently pass `diaryBandsSeen` as the last field; add `bag` after it.
- Add `Entry withBag(String bag)` wither.
- The codec uses a `PartB` record (line 323). Add `bag` to it and `Codec.STRING.optionalFieldOf("bag", "")` to the codec.
- The `NONE` default (line 252) needs `bag` set to `""`.
- Add `String bagOf(UUID player)` and `void setBag(UUID player, String bagId)`.

### Phase 2: resetkey full reset

File: `src/main/java/pocketdungeons/DungeonLog.java`

Add `void resetCampaign(UUID player)`:
- Read the existing entry.
- Build a new Entry preserving: `unlockedShells`, `diaryBandsSeen`, `roomName`, `publicListed`, `recentVisitors`.
- Clear everything else to defaults: `runsCompleted=0`, `bestPathLength=0`, `bestKeystoneLevel=0`, `keystoneLevel=0`, `keystoneAffix=""`, `pendingOfferLevel=0`, `recentThemes=List.of()`, `completedThemes=Map.of()`, `currentTheme=""`, `depth=0`, `extractedPowers=Set.of()`, `fuel=0`, `roomCompletions=0`, `bag=""`.
- Call `setOrphan(player, InventorySwap.OrphanRecord.NONE)` and `setStash(player, InventorySwap.StashRecord.NONE)`.
- `entries.put(player, newEntry); setDirty();`

File: `src/main/java/pocketdungeons/DungeonCommands.java`

Rewrite `resetOwnKey` (line 1211):
1. If in an instance, call `RunLifecycle.quitDoor(player)`.
2. If in the dungeon dimension, call `RunLifecycle.exit(player, ExitReason.COMMAND)` to get them to the overworld first (so stash/orphan clearing does not conflict with live inventory swap).
3. Call `DungeonLog.resetCampaign(server, player)`.
4. Call `clearKeystones(player)` (existing method, line 1227).
5. Clear bag-tagged items from inventory: iterate slots, check `InventorySwap.isBagTagged(stack)`, set to EMPTY.
6. `Payout.deliver(player, Keystone.mint(1))`.
7. Send a message: "Keystone progress reset. Bag cleared. Keystone [1] in hand. Your unlocked shells and diary entries are preserved."

Also update admin `resetKey` (line 1186) to call `resetCampaign` instead of just `setKeystone`.

### Phase 3: Bag selection chest

**3.1 Place the chest**

File: `src/main/java/pocketdungeons/Instances.java`

In `stampRoomShell` (line 635), after `RoomTemplateGenerator.placeFurniture`, check `DungeonLog.bagOf(owner)`. If empty, place a chest at `origin.offset(8, 1, 8)` with a marker tag. Use `RoomBuilder.set` to place the chest block, then set the block entity's NBT with `pocketdungeons:bag_chest: 1`. Look at how `RoomTemplateGenerator.placeWallLodestone` places and tags a block for the pattern.

**3.2 Clear the chest on room save**

File: `src/main/java/pocketdungeons/RunLifecycle.java`

In `saveRoom` (search for `static void saveRoom`), which already clears selector doors and furniture before `RoomStore.capture`, add a step to clear the bag chest at `origin.offset(8, 1, 8)` if the block there is a chest.

**3.3 Intercept right-click**

File: `src/main/java/pocketdungeons/RitualListener.java`

In `onUseBlock` (line 71), before the `RoomProtection.denyContainerUse` check at line 101, add:
- If the block is a chest and has the `pocketdungeons:bag_chest` tag, open `DialogScreens.bagPicker(player)` via `DialogKit.show`, and return `InteractionResult.SUCCESS_SERVER`.

**3.4 Bag picker dialog**

File: `src/main/java/pocketdungeons/DialogScreens.java`

New constants:
```java
static final String KEY_BAG_ID = "bag_id";
static final String ACTION_SELECT_BAG = "select_bag";
static final String ACTION_CONFIRM_BAG = "confirm_bag";
```

`static Dialog bagPicker(ServerPlayer player)`: A `DialogKit.list` titled "Choose your bag" with one button per `Bags` enum constant. Each button label is the bag's display name, tooltip is the blurb. Each button carries `KEY_OWNER` and `KEY_BAG_ID` in a `CompoundTag`, dispatching `ACTION_SELECT_BAG` via `DialogKit.submit`. Body text: "Your bag is your class. You keep it until you reset your keystone."

`static Dialog bagConfirm(ServerPlayer player, String bagId)`: A `DialogKit.confirm` titled "Confirm bag" showing the bag name and blurb. Confirm button dispatches `ACTION_CONFIRM_BAG` carrying `KEY_BAG_ID`. Cancel button returns to the picker via `ACTION_SELECT_BAG` (or a back button that re-shows `bagPicker`).

**3.5 Route the actions**

File: `src/main/java/pocketdungeons/DialogRouter.java`

Add two cases in the `switch` in `handle` (around line 87):
- `ACTION_SELECT_BAG`: read `KEY_BAG_ID`, show `DialogScreens.bagConfirm(player, bagId)`.
- `ACTION_CONFIRM_BAG`: read `KEY_BAG_ID`, re-validate the player is in the dungeon dimension and has no bag assigned, then:
  1. `DungeonLog.forServer(server).setBag(player.getUUID(), bagId)`
  2. `Bags.apply(player, bagId)`
  3. Remove the bag chest block from the world (set to air at `record.roomCellOrigin.offset(8, 1, 8)`)
  4. Send confirmation message

### Phase 4: Bag application on void entry

File: `src/main/java/pocketdungeons/InventorySwap.java`

In `enterVoid` (line 534), after `applyKeystoneItem(server, log, player)` (line 540) and before `restoreOrphanIfAny(log, player, slots)` (line 541), add:

```java
String bagId = log.bagOf(player.getUUID());
if (!bagId.isEmpty()) {
    InventorySwap.OrphanRecord orphan = log.orphanOf(player.getUUID());
    if (orphan.items().isEmpty()) {
        Bags.apply(player, bagId);
    }
}
```

Update the javadoc comment at lines 530-532 that says "The bag is not applied here." Replace with: "The bag is applied here, from the player's DungeonLog entry, if they have a bag assigned and no orphan to restore."

### Phase 5: Room generator wiring

**5.1 LayoutPlanner**

File: `src/main/java/pocketdungeons/LayoutPlanner.java`

The full `plan` overload is at line 97 (10-arg form). Add `Set<String> bagTags` as the 11th parameter. At line 136, change `RoomSelector.resolveDetailed(shape, manifest, theme)` to `RoomSelector.resolveDetailed(shape, manifest, theme, bagTags)`.

All shorter overloads (lines 52, 56, 68, 76) pass through to the next one up. Add `BagTags.pilgrim()` as the default `bagTags` at the bottom of the chain so existing callers are unaffected.

**5.2 generateBehindLobby**

File: `src/main/java/pocketdungeons/Instances.java`

In `generateBehindLobby` (line 776), before the `LayoutPlanner.plan` call at line 795, compute:
```java
String bagId = DungeonLog.forServer(server).bagOf(record.owner);
Set<String> bagTags = BagTags.seed(bagId, record.members.size());
```
Pass `bagTags` to `LayoutPlanner.plan`.

**5.3 buildLayout**

File: `src/main/java/pocketdungeons/Instances.java`

In `buildLayout` (line 421), do the same: read the owner's bag, compute `BagTags.seed`, pass to `LayoutPlanner.plan` at line 425.

### Phase 6: Tests

Add `src/test/java/pocketdungeons/BagSelectionTest.java`: verify `DialogScreens.bagPicker` lists all 8 bags with correct ids, and `bagConfirm` carries the bag id.

Add `src/test/java/pocketdungeons/ResetCampaignTest.java`: create an Entry with all fields populated, call `resetCampaign`, verify keystone progress cleared and unlockables preserved.

Build:
```
./gradlew.bat compileJava
./gradlew.bat build --offline
```

Both should succeed. The PowerShell exit code may be 1 even when Gradle prints "BUILD SUCCESSFUL"; trust the Gradle output.

## Key file paths

- `src/main/java/pocketdungeons/DungeonLog.java` - persistent state, Entry record
- `src/main/java/pocketdungeons/DungeonCommands.java` - commands including resetkey
- `src/main/java/pocketdungeons/InventorySwap.java` - void inventory swap, enterVoid, leaveVoid
- `src/main/java/pocketdungeons/Instances.java` - lobby creation, generateBehindLobby, buildLayout, stampRoomShell
- `src/main/java/pocketdungeons/RunLifecycle.java` - enter, chooseOffer, quitDoor, saveRoom
- `src/main/java/pocketdungeons/Bags.java` - bag enum, apply method, tags
- `src/main/java/pocketdungeons/BagTags.java` - seed tags for room generator
- `src/main/java/pocketdungeons/RoomSelector.java` - resolveDetailed with bagTags
- `src/main/java/pocketdungeons/LayoutPlanner.java` - plan method chain
- `src/main/java/pocketdungeons/DialogScreens.java` - dialog builders
- `src/main/java/pocketdungeons/DialogRouter.java` - action routing
- `src/main/java/pocketdungeons/DialogKit.java` - dialog primitives
- `src/main/java/pocketdungeons/RitualListener.java` - right-click interception
- `src/main/java/pocketdungeons/RoomTemplateGenerator.java` - placeFurniture, placeSelectorDoors
- `src/test/java/pocketdungeons/BagTableTest.java` - existing bag loot table tests
- `src/test/java/pocketdungeons/LodestoneMenuTest.java` - existing menu test

## Open questions (ask the user before implementing if unclear)

1. Each party member should choose their own bag independently (each needs their own bag chest in the safe room). Confirm with user.
2. Existing players with keystone progress but no bag get the bag chest on their next dungeon entry. Confirm with user.
3. Bag chest position `origin.offset(8, 1, 8)` is a guess; verify against the room template's open floor space.
