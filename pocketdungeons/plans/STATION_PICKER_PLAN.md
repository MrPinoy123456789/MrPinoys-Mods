# Station Picker: progressive unlock, SGUI chest, player-placeable blocks

## Problem

The four sinks (M14 reroll, M15 trims, M16 gamble, M17 Herobrine Cube) are fully
implemented as functional code, but a player has no in-game way to discover
them, learn what they do, or obtain the station blocks. The stations are
configured vanilla blocks (smithing table, emerald block, beacon) that the
mod intercepts on right-click, but nothing places them in the room loop and
nothing tells the player they exist. D5 ("room size as progression, station
unlocks") was never promoted.

## Design

A chest-style SGUI opened from the wall-lodestone menu. Three station block
icons sit in chest slots; the player clicks one to take the block item and
place it in their room themselves. Stations unlock progressively by keystone
level, so a new player is not overwhelmed. Each icon's lore is a
mini-tutorial: what the station does, what it costs, and either the unlock
requirement (locked) or "click to take" (unlocked).

### Why SGUI, not the vanilla dialog the mod's other screens use

The mod's existing dialogs are action pickers: choose a door, choose an
enchantment, choose a slot. The player is selecting an action to perform
right now. The station picker is different: it gives the player a physical
block to carry and place. A chest where you see the smithing table sitting
in a slot, hover it to read what it does, and click to pick it up is the same
interaction the player will then have with the block once it is placed. The
UI teaches the mechanic by being the mechanic. The vanilla dialog cannot do
that; it can show a button labelled "Reroll Station" but the player is
pressing a button, not picking up an object.

This screen is the only one that gives items rather than performing actions,
so it does not set a precedent that forces SGUI onto the other five pickers.
They stay on the vanilla dialog where they belong.

### Why player-placeable, not protected fixtures

The stations are vanilla blocks. A player places them wherever they want in
their room, breaks and re-places them freely. If they lose one (dropped in
lava, left in a torn-down dungeon), they reopen the picker and take another.
No capture hygiene needed: the blocks are not part of the room blob, and
nothing about the station's function depends on where the block is placed.

## Unlock levels

| Station | Config key | Default | Rationale |
|---|---|---|---|
| Reroll | `rerollUnlockLevel` | 5 | Already exists. Available early; gear starts dropping immediately. |
| Gamble | `gambleUnlockLevel` | 10 | New. Needs a gear pool to exist (M13) and emeralds to accumulate. |
| Herobrine Cube | `cubeUnlockLevel` | 15 | New. Needs rare adventure-node rewards (M11) and extracted powers to mean something. |

The 5/10/15 spread matches the brainstorm's own suggestion and gives roughly
one new station per tier of progression. An operator can flatten or steepen
the curve in `pocketdungeons.json`.

## Files to change

### 1. `build.gradle.kts`: add SGUI dependency

Add the Nucleoid maven repository and the SGUI dependency with jar-in-jar
include, matching the pattern in `ballot/fabric/build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
}

dependencies {
    // existing deps...
    implementation("eu.pb4:sgui:2.1.0+26.2")
    include("eu.pb4:sgui:2.1.0+26.2")
}
```

### 2. `fabric.mod.json`: declare SGUI dependency

Add to the `depends` block:

```json
"sgui": ">=2.1.0"
```

The `include` (jar-in-jar) bundles it, but the loader still needs the
dependency declared.

### 3. `PocketDungeonsConfig.java`: add two unlock-level keys

Following the exact pattern of the existing `rerollUnlockLevel`:

**Field declarations** (in the gamble section, after `gambleWeightedSlot`):
```java
private static int gambleUnlockLevel = 10;
```

**Field declarations** (in the cube section, after `cubeBlock`):
```java
private static int cubeUnlockLevel = 15;
```

**Accessor methods** (after `gambleWeightedSlot()` and `cubeBlock()`
respectively):
```java
public static int gambleUnlockLevel() { return gambleUnlockLevel; }
public static int cubeUnlockLevel() { return cubeUnlockLevel; }
```

**Defaults reset** (in the `defaults` method, after the existing gamble and
cube lines):
```java
gambleUnlockLevel = 10;
cubeUnlockLevel = 15;
```

**JSON parse** (in `apply`, after the existing gamble and cube reads):
```java
gambleUnlockLevel = readInt(root, "gambleUnlockLevel", 10, v -> v >= 1, "must be >= 1");
cubeUnlockLevel = readInt(root, "cubeUnlockLevel", 15, v -> v >= 1, "must be >= 1");
```

**JSON serialize** (in `defaultsJson`, after the existing gamble and cube
lines):
```java
root.addProperty("gambleUnlockLevel", 10);
root.addProperty("cubeUnlockLevel", 15);
```

### 4. New file: `StationPicker.java`

An SGUI chest screen. The shape, following `ballot/mc/Menus.java`:

```
StationPicker.open(ServerPlayer player)
```

- `SimpleGui` with `MenuType.GENERIC_9x3`, title "Stations".
- Reads the player's keystone level from `DungeonLog`.
- Three `GuiElementBuilder` elements in the middle row (slots 11, 13, 15,
  spaced for readability), one per station.
- Each element uses the station's configured block item as its icon
  (resolved via the same `ConfiguredItem` pattern the station handlers
  already use, or directly via `BuiltInRegistries.ITEM` from the config
  string).
- **Locked station:** grey name, lore lines describing what it does plus
  "Unlocks at keystone level N." No click callback (or a callback that
  sends a chat message and does nothing else).
- **Unlocked station:** white name, lore lines describing what it does
  plus "Click to take." Click callback gives the player one block item
  via `player.getInventory().placeItemBackInInventory(new ItemStack(item))`,
  sends a chat confirmation, and closes the GUI.

**Lore text per station** (the mini-tutorial):

Reroll (smithing table):
- "Rerolls one enchantment on a piece of gear."
- "Costs lapis, scaled by the gear's tier."
- "Right-click the block with gear in hand."
- Locked: "Unlocks at keystone level 5."
- Unlocked: "Click to take."

Gamble (emerald block):
- "Trades emeralds for a random piece of gear in a chosen slot."
- "Costs emeralds, scaled by tier. No guarantee of quality."
- "Right-click the block to open the slot picker."
- Locked: "Unlocks at keystone level 10."
- Unlocked: "Click to take."

Herobrine Cube (beacon):
- "Extract powers from rare items. Imbue them onto gear."
- "Extract consumes the item permanently. Imbue costs iron."
- "Right-click with a rare item to extract, or with gear to imbue."
- Locked: "Unlocks at keystone level 15."
- Unlocked: "Click to take."

### 5. `DialogScreens.java`: add the menu option and action constant

**Action constant** (alongside the existing `ACTION_DIARIES` etc.):
```java
static final String ACTION_STATIONS = "stations";
```

**Menu options** (in `menuOptions`, both overworld and in-dungeon lists):
add one entry:
```java
new MenuOption("Stations", "Take a station block for your room",
        ACTION_STATIONS)
```

Placement: after "Manage Room", before "Inspect Keystone" in both lists.
The stations are available in and out of the dungeon (a player may want to
grab one before starting a run), so the option appears in both menus.

### 6. `DialogRouter.java`: dispatch the new action

**New case** in the action switch (alongside `ACTION_DIARIES`):
```java
case DialogScreens.ACTION_STATIONS -> StationPicker.open(player);
```

This is the one screen that opens an SGUI instead of a vanilla dialog. The
router does not need to know the difference; it just calls the method.

### 7. `LodestoneMenuTest.java`: update the option counts

The test currently asserts 5 options for the overworld menu and 5 for the
in-dungeon owner menu. Adding "Stations" to both makes them 6. The test's
option-count assertions and index-based label checks need updating:

- Overworld: 6 options. "Stations" at index 3 (after Manage Room, before
  Inspect Keystone). Inspect Keystone moves to index 4, Diaries to 5.
- In-dungeon owner: 6 options. "Stations" at index 3 (after Change Shell,
  before Inspect Keystone). Inspect Keystone moves to 4, Diaries to 5.
- In-dungeon visitor: unchanged at 3 options (Leave, Inspect Keystone,
  Diaries). Stations is owner-only (see "Resolved decisions" below); a
  visitor does not see it regardless of their own keystone level or
  unlocks, since the station blocks come from the room's own lodestone,
  which is the owner's terminal.

The dialog button-count assertions (lines 74, 84) also need updating from
5 to 6.

## What is not in this plan

- **No room placement logic.** The stations are not placed automatically
  at stamp time. The player takes the block from the chest and places it
  themselves. This is the deliberate choice from the design discussion.
- **No capture hygiene.** Because the stations are player-placeable vanilla
  blocks, they are not part of the room blob. If a player leaves a station
  in a dungeon that gets torn down, they lose it and take another from the
  picker. No `clearFurniture`-style cleanup is needed.
- **No `RoomProtection` changes.** The stations are not protected fixtures.
  A player can break them with any tool, same as any vanilla block.
- **No new custom items.** The station blocks are vanilla items
  (smithing table, emerald block, beacon). The "no custom items" rule
  (ROADMAP rule 2) stays intact.
- **No trim station.** Trims (M15) have no station block; the bonus is
  passive on worn armour. The picker shows only the three block-based
  stations (reroll, gamble, cube).

## Verification

1. `.\gradlew.bat compileJava` compiles with the new SGUI dependency.
2. `.\gradlew.bat test` passes, including the updated `LodestoneMenuTest`.
3. Manual check (operator): place a wall lodestone, right-click, see
   "Stations" in the menu, click it, see the chest with three icons.
   With keystone level 0, all three are locked. With level 5+, reroll is
   clickable. With level 10+, reroll and gamble. With level 15+, all
   three. Clicking an unlocked station gives the block item and closes
   the chest.

## Resolved decisions

- **Owner-only.** Party members visiting another player's room do not see
  the Stations option. The wall-lodestone menu gates it the same way it
  gates Change Shell: `roomOwner` must be true. A visitor's keystone level
  and unlocks are their own, but the station blocks are taken from the
  room's lodestone, which is the owner's terminal.
- **Herobrine Cube at 15.** Kept as-is. Low-level recipes for extractable
  items can be authored to give the Herobrine Cube something to do
  before deep rare nodes.
- **Gamble block changed to `minecraft:waxed_oxidized_copper_chest`.**
  Verified against the 26.2 jar. Replaces `minecraft:emerald_block` as the
  default `gambleBlock`, which a player might already use for storage or
  decoration. The waxed oxidized copper chest is visually distinct,
  unlikely to conflict with player builds, and thematically fits a
  "gambling chest" metaphor. The `GambleStation` already fully claims its
  block on right-click, so the vanilla chest UI never opens; the block is
  purely the station's physical presence.
