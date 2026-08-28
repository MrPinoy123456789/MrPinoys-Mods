# Room UX and Beyond: Implementation Plan

> **What this is:** the sequenced plan for everything in
> `DOOR_LADDER_BRAINSTORM.md` sections 8-16 that has not yet been built.
> M0-M17 (sections 1-5 of the brainstorm) are complete; see
> `plans/COMPLETED-MILESTONES.md`.
>
> **What this is not:** a design document. The *why* lives in the brainstorm;
> this file is the order, the dependencies, and the scope boundaries.
>
> **Relationship to the brainstorm:** the brainstorm stays the scratchpad.
> Where this plan and the brainstorm disagree, this plan wins, because the
> decisions below are the ones being committed to.

---

## What is already done

| Brainstorm section | Milestone | Status |
|---|---|---|
| 2.1 Drop Fragile, Ominous +1 | M10 | Done |
| 2.2 Adventures (graph replaces recipes) | M11 | Done |
| 2.3 Trial spawner gating | M10 | Done |
| 2.4 Mob scaling + cap raise | M10 | Done |
| 3.1 Two-tier doors + fuel | M12 | Done |
| 3.2 Gear reroll (lapis) | M14 | Done |
| 3.3 Armor trims (consumed templates) | M15 | Done |
| 3.4 Gamble station (emeralds) | M16 | Done |
| 3.5 Herobrine Cube (extract/imbue) | M17 | Done |
| 3.6 Gear loot pool (prerequisite) | M13 | Done |

---

## What remains, in build order

```
M18  Room shell pass          -- immutable shell, double doors, wall lodestone, ceiling
     |
M19  Physical door selection  -- screen, bulbs, lever, engine terminal
     |
M20  Visiting rework          -- lobby directory, delete calling card
     |
M21  UX consolidation         -- one lodestone, one menu
     |
M22  Sound pass               -- Chime.java, all audio cues

M23  Room template editor     -- independent, ships anytime
M24  Room shells & prestige   -- depends on M18
M25  Pocket2 Dungeon          -- depends on M11
M26  Lore delivery            -- mostly content, M26.4 needs jar verification
M27  Extra features           -- experimental dungeon, visitor log, death checkpoint
```

M18-M22 are the core Room UX pass. They have a strict dependency chain
and should land in order. M23-M27 are independent of each other and of
the UX chain, except where noted.

---

## M18: Room shell pass

**Brainstorm sections:** 9.1, 9.2, 9.3, 9.4

**Goal:** the room's shell becomes immutable, the lodestone moves to the
wall, the selector opening gets double doors, and the ceiling gets top
slabs and stair-framed light fixtures. All four are template/geometry
changes that touch the same code paths and should land together.

**Depends on:** nothing (M2's room system is the foundation).

**Scope:**

### 18.1 Immutable shell (9.1)

- `RoomProtection.beforeBlockBreak` gains an `isShell(pos, roomOrigin)`
  check: the shell is Y=0 (floor), the wall ring (x=0, x=15, z=0, z=15,
  Y=1..5), Y=6 (ceiling), and the four lamp positions. The interior
  (x=1..14, z=1..14, Y=1..5) is the owner's build space.
- `isShell` is a pure coordinate test against the room origin, no
  block-state lookup. The same test applies to block placement (the
  existing placement guard in `RitualListener`).
- The owner can no longer break or place in the shell. Paintings, item
  frames, carpets, wall signs, banners, buttons, torches all still work
  (they are entities or sit on the face, not in the shell).
- This enables room skins (M24): if the shell is immutable, swapping it
  is a mod-controlled operation.

### 18.2 Double doors on the selector opening (9.2)

- After `clearSelectorDoors` punches the 2-wide, 3-tall air hole, place
  two vanilla wooden door blocks side by side (Y=1..2) and a lintel
  block (wall material) at Y=3 to seal the top.
- Wooden doors open by right-click; the mod intercepts right-clicks on
  selector doors before vanilla handles them, so this is the existing
  pattern. For the post-selection doorway, the doors are just physical
  barriers the player opens manually.
- Mobs cannot walk through closed doors. The player opens, walks
  through, and the door stays open behind them.

### 18.3 Wall lodestone as terminal (9.3)

- The room's lodestone moves from the floor (NW corner) to the wall.
  This is a template change in `RoomTemplateGenerator` and
  `RoomBuilder.buildCell`: the lodestone sits at a fixed wall position
  (e.g. x=1, y=2, z=0 on the north wall), set into the wall rather than
  on the floor.
- The wall lodestone is part of the immutable shell (protected by
  `isShell`), so the player cannot break it.
- The terminal-pad completion trigger at the dungeon's end stays as a
  floor lodestone. Two different lodestones, two different purposes.
- The stand-on leave-pad mechanic (`isOnRoomLeavePad`) stays for now;
  it is replaced by the right-click menu in M21. This milestone only
  moves the block; M21 changes how it is interacted with.
- Room skins apply to the wall it sits on, so a deepslate-skinned room
  has a lodestone set into a deepslate wall (automatic from the shell
  swap in M24).

### 18.4 Ceiling: top slabs and stair-framed fixtures (9.4)

- Interior ceiling positions (not the edge ring) change from full
  `STONE_BRICKS` to `minecraft:stone_brick_slab` with `type=top`,
  gaining half a block of headroom. The edge ring stays full blocks to
  connect cleanly with the wall top.
- One new `BlockState` constant `CEILING_SLAB` in `RoomBuilder`.
- `buildCell` and `buildLiminalCell` change their ceiling placement:
  full block for edge positions, top slab for interior.
- Each of the four `SEA_LANTERN` positions gains four
  `STONE_BRICK_STAIRS` at Y=6 on its four sides, tall back toward the
  lantern, short side facing outward. A `placeFixture` helper.
- `isShell` does not change: the ceiling check is "Y == CEILING_Y" for
  the entire 16x16, which covers slabs, stairs, and lanterns.
- Structure rotation preserves fixture orientation (stairs' `FACING`
  rotates correctly with the template).
- Room skins pick up the new blocks automatically (stairs and slabs
  derive from the wall material, same as the ceiling block already is).

**Touch points:**

- `RoomProtection`: add `isShell` check.
- `RitualListener`: add `isShell` to placement guard.
- `RoomBuilder.buildCell`, `RoomBuilder.buildLiminalCell`: ceiling
  slabs, stair fixtures, wall lodestone position.
- `RoomTemplateGenerator`: update room templates for wall lodestone,
  ceiling slabs, stair fixtures. The `entrance_hall` template gets the
  same changes.
- `Instances.stampLobby`: update the lodestone position reference.
- `Instances.isOnRoomLeavePad`: update the position check (the
  lodestone is now on the wall, not the floor; the stand-on check
  stays until M21 replaces it).
- `BedrockEnvelope`: no change (the bedrock backstop stays for dungeon
  cells; it is redundant for the room shell but harmless).

**Done when:**

1. The owner cannot break the room's walls, floor, or ceiling.
2. The owner can still place and break interior blocks freely.
3. The lodestone is visibly set into the north wall, not on the floor.
4. The selector opening has wooden double doors after a door is chosen.
5. The ceiling has top slabs in the interior and stair-framed lanterns.
6. A captured and re-placed room preserves the new ceiling and fixtures
   at any rotation.

**Verification:**

- `./gradlew build` green.
- Live: break a wall block (refused), break an interior block (works),
  open the double doors, look up at the ceiling.

---

## M19: Physical door selection

**Brainstorm section:** 10 (all subsections)

**Goal:** the three selector doors, the screen above them, the copper
bulbs, and the lever replace the dialog-based door offer. No popups;
the walk between doors is the browse, the lever is the commit.

**Depends on:** M18 (furniture protection from the immutable shell).

**Scope:**

### 19.1 The screen (10.1)

- Black concrete blocks on the selector wall at Y=4..5, spanning
  x=4..11 (8 wide x 2 tall). A `text_display` entity with
  `billboard: "fixed"`, `Rotation` matching the wall face, positioned
  ~0.1 blocks off the wall.
- Follow Hearsay's `Bubbles.java` pattern exactly:
  `see_through=false`, forced brightness `{block: 15, sky: 15}`, NBT
  object via `ComponentSerialization.CODEC` (not JSON string since
  1.21.5), entity tag `pocketdungeons_screen` for orphan cleanup.
- The screen shows five contexts: idle prompt, door preview (level,
  theme, affixes), run in progress (level, theme, affixes, spawner
  count or timer), post-completion (room name, visibility, visitor
  count), and "Select a door first" refusal. The post-completion
  context's room-name and visibility lines depend on M20's fields
  (host-set name, `publicListed`); M19 ships the room-mode machinery
  with the owner, visitor count and whitelist size, and M20 fills in
  the name and visibility.
- The screen is re-summoned after each room placement with fresh
  content, never captured with the room. Purged by the existing entity
  sweep on teardown.

### 19.2 Copper bulb selection signal (10.2)

- Three `minecraft:copper_bulb` blocks at Y=4, one above each selector
  door (x=7, 8, 9). All start dark (`LIT=false`).
- Right-click a door: its bulb toggles `LIT=true`, the previous
  selection's bulb toggles `LIT=false`. One blockstate write per
  toggle.
- A fourth bulb above the lever (x=10, Y=4): lit when a door is
  selected, dark when none is. Signals "ready to commit."

### 19.3 The lever as commit (10.3, 10.4)

- A lever block at x=10 on the selector wall, making a four-wide
  cluster: `[door1][door2][door3][lever]`.
- Right-click the lever: if a door is selected, `Instances.chooseOffer`
  runs with the selected step and the dungeon starts. If none is
  selected, the screen shows "Select a door first" with the bass
  chime (M22).
- `selectedStep` field on `InstanceRecord` (0 = none, 1/2/3 = door
  selected).

### 19.5 What this replaces (10.5, 10.6)

- `DialogScreens.doorOffer`: deleted.
- `RitualListener.sendDoorOffer`: replaced with a selection-state
  update (bulb toggle + screen update).
- "Take this key" button: replaced by lever pull.
- What stays: `RunLifecycle.chooseOffer` (called from the lever),
  `Keystone.offers` (still generates three offers),
  `placeSelectorDoors` (still places three door blocks),
  `selectorDoorStep` (still detects which door was right-clicked),
  and the `/dungeon choose <step>` command as a power-user shortcut.

### 19.6 The engine terminal: fuel as a separate surface (10.7)

- A `minecraft:respawn_anchor` on a wall adjacent to the selector wall
  (e.g. west wall at z=7..8). The mod intercepts its right-click,
  consuming echo shards and updating the charge level blockstate.
- A second `text_display` above the engine block showing fuel count
  and cost per premium door.
- The engine block and its screen are part of the immutable furniture
  (`isFurniture` check alongside `isShell` in `RoomProtection`).
- If the player pulls the lever for a premium door without enough fuel,
  the door screen shows "Not enough fuel" with the bass chime.

### 19.7 New state and furniture

- `selectedStep` field on `InstanceRecord`.
- `isFurniture(pos, record)` in `RoomProtection`: checks against lever,
  screen blocks, engine block, wall lodestone. Mod-placed, cannot be
  broken by the player.
- Copper bulb placement at stamp time. Lever placement at stamp time.
- Screen blocks and `text_display` at stamp time.

**Touch points:**

- `InstanceRecord`: add `selectedStep` field.
- `RoomProtection`: add `isFurniture` check.
- `RitualListener.onUseBlock`: replace `sendDoorOffer` with
  selection-state update; add lever handler; add engine terminal
  handler.
- `Instances.stampLobby`: place bulbs, lever, screen blocks, engine
  block, summon screen `text_display`.
- `RunLifecycle.chooseOffer`: read `selectedStep` instead of dialog
  payload.
- `DialogScreens.doorOffer`: deleted.
- `DialogRouter`: nothing to remove here; the dialog used a command
  button (`/dungeon choose <step>`), not a `CustomAll` payload, so
  there was never a `doorOffer` dispatch case.
- New class: `DungeonScreen` (text_display management, following
  Hearsay's `Bubbles.java` pattern).

**Done when:**

1. Right-clicking a selector door lights its bulb and updates the
   screen with that door's offer. No dialog popup.
2. Right-clicking a different door swaps the bulb and screen content.
3. Pulling the lever with a door selected starts the dungeon.
4. Pulling the lever with no door selected shows "Select a door first"
   on the screen.
5. The engine terminal shows fuel count and accepts echo shards.
6. Pulling the lever for a premium door without enough fuel shows "Not
   enough fuel" on the door screen.
7. None of the new furniture (bulbs, lever, screen, engine) can be
   broken by the player.

---

## M20: Visiting rework

**Brainstorm section:** 8

**Goal:** the calling card is replaced by a lobby directory accessed
from the wall terminal. A host-set `publicListed` boolean replaces the
hand-traded compass. The directory is a `MultiActionDialog` with one
button per public room.

**Depends on:** M18 (wall lodestone exists as the terminal surface).
Can ship before M21 (the menu consolidation), since the directory can
be a standalone dialog invoked from a right-click on the wall
lodestone even before the full menu lands.

**Scope:**

- `DungeonLog.Entry` gains `publicListed` (boolean, default false) and
  `roomName` (string, default empty). Both persisted via
  `optionalFieldOf`, same shape as `completedThemes`.
- A `MultiActionDialog` listing every online player whose
  `publicListed` is true, with room name and occupancy count. Same
  pattern as `DialogScreens.partyRoster`. Clicking a button calls
  `Instances.visit(serverPlayer, ownerUuid)`.
- Room naming: a `TextInput` dialog, same pattern as the existing
  whitelist-name dialog. The host sets it from the room management
  menu (or a command for now, if M21 is not yet landed).
- Privacy is a host-set boolean, not a token. The host toggles
  `publicListed` from the room management menu or
  `/dungeon room public`/`/dungeon room private` commands.
- `RoomWhitelist` (the permission mask) is unchanged. A room can be
  public-listed but still locked down (visitors cannot break or open
  containers because they are not whitelisted).

**What gets deleted:**

- `CallingCard.java` entirely.
- The card branch in `RitualListener` (lines 132-140).
- `CallingCard.warmUp()`.
- `callingCardItem` config field.
- The `Payout.deliver(owner, CallingCard.mint(...))` delivery in
  `DungeonCommands`.

**What stays:**

- `Instances.visit` (the visit call, invoked from a dialog button
  instead of a card).
- `RoomWhitelist` (the permission mask, unchanged).
- `RoomProtection` (the break/place guard, unchanged).
- `VisitService.createVisitInstance` (unchanged).

**Touch points:**

- `DungeonLog.Entry`: add `publicListed`, `roomName` fields and codec.
- `DialogScreens`: add `lobbyBrowser` dialog (MultiActionDialog).
- `DialogRouter`: add `visitRoom` dispatch.
- `DungeonCommands`: add `/dungeon room public`, `/dungeon room
  private`, `/dungeon room name <text>` commands. Delete
  `/dungeon room card` and the card delivery.
- `RitualListener`: delete the card branch.
- `CallingCard.java`: deleted.
- `PocketDungeonsConfig`: delete `callingCardItem`.

**Done when:**

1. A host sets their room public with `/dungeon room public`.
2. Another player opens the lobby browser and sees the host's room
   listed with name and occupancy.
3. Clicking the room button teleports the visitor into the host's room
   (or a read-only copy if the host is away).
4. The calling card no longer exists; `/dungeon room card` is gone.
5. A private room does not appear in the browser.

---

## M21: UX consolidation: one lodestone, one menu

**Brainstorm section:** 11

**Goal:** five distinct lodestone interactions collapse into one
right-click menu on the wall lodestone. The stand-on leave-pad is
deleted. The `hasInstance` guard inverts: right-clicking while in a
dungeon opens the in-dungeon menu instead of blocking.

**Depends on:** M18 (wall lodestone), M19 (door selection, so the
menu does not need to duplicate door selection), M20 (visiting, so
"Browse Lobbies" is a menu option).

**Scope:**

- Right-click the wall lodestone with anything (not just the keystone)
  opens a `MultiActionDialog`. The menu contents depend on context:

  **Overworld (right-click any lodestone):**
  - Start Dungeon (requires keystone in main hand)
  - Browse Lobbies (M20's lobby directory)
  - Manage Room (whitelist, name, public/private toggle)
  - Inspect Keystone (currently `/dungeon key` dialog)

  **In dungeon (right-click the wall terminal):**
  - Leave (replaces the stand-on leave-pad and `/dungeon exit`)
  - Manage Room (if in your own room, post-completion)
  - Inspect Keystone

- The `hasInstance` guard in `RitualListener` inverts: instead of
  blocking right-click while in a dungeon, it shows the in-dungeon
  menu.
- The keystone is checked when "Start Dungeon" is clicked, not when
  the menu opens. A player who just wants to leave never needs to find
  their compass first.
- The terminal pad at the dungeon's end stays as a stand-on mechanic.
  It is hard to trigger accidentally (it is at the end of the dungeon,
  not in the room where you are looting).
- Selector doors stay as right-click interactions, not menu options.
  The menu is for mod navigation; the doors are for run selection.
- `/dungeon` commands stay as power-user shortcuts.

**What gets deleted:**

- `isOnRoomLeavePad` and its branch in the `Instances` watcher.
- The stand-on leave-pad mechanic. The room's lodestone is now a
  right-click terminal, not a step-on trigger.

**Touch points:**

- `RitualListener.onUseBlock`: the keystone branch becomes the menu
  branch. The `hasInstance` guard inverts. The card branch is already
  gone (M20).
- `DialogScreens`: add `lodestoneMenu` (MultiActionDialog, context-
  dependent contents).
- `DialogRouter`: add menu option dispatches (`startDungeon`,
  `browseLobbies`, `manageRoom`, `inspectKeystone`, `leaveDungeon`).
- `Instances`: delete `isOnRoomLeavePad` and its watcher branch.
- `RunLifecycle.exit`: callable from the menu's "Leave" button instead
  of the pad watcher.

**Done when:**

1. Right-click the wall lodestone in the overworld with an empty hand:
   the menu opens with Start, Browse, Manage, Inspect options.
2. Right-click the wall lodestone in a dungeon: the menu opens with
   Leave, Manage, Inspect options.
3. Clicking "Leave" exits the dungeon. No stand-on pad needed.
4. Walking over the old leave-pad position does nothing.
5. The terminal pad at the dungeon's end still works (stand-on
   completion trigger).

---

## M22: Sound pass

**Brainstorm section:** 13

**Goal:** a `Chime.java` class following the spiritwolves/wondrous
pattern, with one static method per event, each sending a
`ClientboundSoundPacket` to the player's connection. All vanilla
`SoundEvents`, no custom sound files, server-side only.

**Depends on:** M19 (door selection interactions), M21 (menu
interactions). Most valuable after those land, since they add the
interactions that most need audio feedback. Can ship earlier for the
run-lifecycle cues that already exist.

**Scope:**

A `Chime.java` class (~60 lines) with one method per event:

**Door selection (M19):**
- Right-click a selector door: `NOTE_BLOCK_BELL`, short, mid pitch.
- Lever pulled with no door selected: `NOTE_BLOCK_BASS`, low, short.
- Lever pulled, run starts: `RESPAWN_ANCHOR_CHARGE` (existing) or
  rising two-note jingle.

**Run lifecycle:**
- Run completes: `NOTE_BLOCK_BELL` + `NOTE_BLOCK_CHIME` jingle, rising.
- Run times out: `NOTE_BLOCK_DIDGERIDOO`, low, sustained.
- Keystone level up: `NOTE_BLOCK_CHIME`, rising pitch.
- Keystone level depleted: `NOTE_BLOCK_BASS`, descending two notes.
- Spawner cleared (last in a cell): `NOTE_BLOCK_HAT`, very quiet.

**Room and menu (M20, M21):**
- Menu opens: `NOTE_BLOCK_HAT`, quiet.
- Room listed (public toggle on): `NOTE_BLOCK_CHIME`, mid.
- Room unlisted (public toggle off): `NOTE_BLOCK_HAT`, quiet.
- Visitor arrives: `NOTE_BLOCK_BELL`, two notes.
- Room relocated: `PISTON_EXTEND` or `STONE_PLACE`, positional.

**Lobby visiting (M20):**
- Lobby browser opens: `NOTE_BLOCK_HAT`, quiet.
- Visit starts: `ENDERMAN_TELEPORT`, low volume.
- Visit ends: `ENDERMAN_TELEPORT`, lower pitch.

**Touch points:**

- New class: `Chime.java`.
- One line added at the end of each event's code path
  (`completeRun`, `expireTimedOut`, `Keystones.grantOffer`,
  `chooseOffer`, etc.), after the state change succeeds.
- The two existing `RESPAWN_ANCHOR_CHARGE` calls in `RitualListener`
  migrate to `Chime.runStarts` if the lever becomes the commit.

**Done when:** every event in the table above produces its cue, heard
only by the relevant player, at a sensible volume.

---

## M23: Room template editor

**Brainstorm section:** 14

**Goal:** a development tool for hand-authoring room templates in
Minecraft itself. Build, save to `.nbt`, let an AI read the file and
generate the matching code.

**Depends on:** nothing. Ships independently of everything else.

**Scope:**

- `/dungeon admin buildroom`: stamps an empty 16x16x6 shell in the
  dungeon dimension, teleports the player in, marks the instance as
  an admin build room. No immutability, no trial spawners, no clock,
  no keystone.
- `/dungeon admin saveroom <name>`: captures the cell to
  `src/main/resources/data/pocketdungeons/structure/rooms/<name>_
  <author>_<timestamp>.nbt`, using the existing `captureAndSave`
  method. Writes `pd_author` and `pd_saved_at` NBT tags.
- Two command handlers, ~30 lines each. The capture method and shell
  stamping already exist.

**Done when:** `/dungeon admin buildroom` teleports the player into
an empty shell, the player builds a room by hand, `/dungeon admin
saveroom myroom` writes a `.nbt` file to the resources directory.

---

## M24: Room shells and prestige

**Brainstorm sections:** 15.4, 15.5

**Goal:** players discover and unlock alternate shell materials
(sandstone, deepslate, nether brick, etc.) from rare adventure nodes
and long-term room ownership. A shell swap replaces only the immutable
shell blocks; the interior stays untouched.

**Depends on:** M18 (immutable shell is the prerequisite; without it,
swapping the shell would destroy player-placed blocks on the walls).

**Scope:**

- A per-player shell unlock list, stored alongside `DungeonLog.Entry`
  (same shape as `completedThemes`).
- `RoomBuilder.rebuildShell`: captures the interior via
  `RoomStore.capture`, stamps the new shell, re-places the interior.
  All three primitives already exist; this is a new orchestration.
- The lodestone terminal menu (M21) gets a "Change shell" option. If
  no alternate shells are unlocked, the option shows the default and
  a grayed-out list (the menu option is the tutorial).
- Two unlock paths:
  1. Rare adventure rooms: some shells gated behind a hidden adventure
     transition, found as a possible reward in a rare node's completion
     chest.
  2. Prestige reward: reaching a threshold of completions on a
     long-held room unlocks a shell as a veteran reward.
- Prestige tracking: a count on `DungeonLog.Entry` (completions while
  holding the same room without resetting). The visual marker (a
  different lamp, a particle effect) is the cheap part; the mechanical
  perk is left undefined until the loot and room-state systems are
  stable.

**Done when:** a player unlocks a deepslate shell, opens the menu,
selects "Change shell," and the room's walls/floor/ceiling swap to
deepslate while every interior block stays in place.

---

## M25: Pocket2 Dungeon

**Brainstorm section:** 15.6

**Goal:** a dungeon within a dungeon. During a run, the player finds a
rare door inside a cleared encounter room leading to a short, intense
sub-dungeon with a hard timer. Grab what you can before the clock
runs out.

**Depends on:** M11 (adventure graph for the rare-node gating), M10
(spawner gating, so the outer run's completion is not trivialized).

**Scope:**

- A special `RoomSpec` door type placed during encounter-room
  generation, gated by the adventure graph's transition logic. Rare,
  not guaranteed.
- Stepping through creates a child `InstanceRecord` linked to the
  parent, with its own cells but no spawner gate and no completion pad,
  only a countdown timer (60-90 seconds).
- The child's tick watcher counts down; on zero or on player death, it
  tears down the child instance and returns the player to the parent
  instance at the room they entered from.
- The outer run's clock keeps ticking. Time spent in the Pocket2 is
  time the outer run's timer is still counting.
- Loot is loose: chests, loose drops, maybe a spawner or two. Rare
  drops live here: shell unlock tokens (M24), keystone upgrades, or
  other rare items the outer run does not offer.
- Death is not the exit: dying inside the Pocket2 ejects the player
  the same way the timer does, but the outer run's death penalty still
  applies.

**Done when:** a player finds a nested door in a cleared room, steps
through, has 60 seconds to grab loot, and is ejected back to the outer
run when the timer hits zero.

---

## M26: Lore delivery

**Brainstorm section:** 16

**Goal:** the lore (Steve/Herobrine, Alex's diaries, the compass
pointing) lands as fragmentary, discoverable content, not an
exposition dump. The mechanical naming stays terse and funny per
`VISION.md` section 4; the lore is a different register on its own
tonal surface.

**Depends on:** nothing mechanically, except 26.4 (compass pointing)
which needs jar verification of POI registration for mod-placed
lodestones. 26.3 (diaries) depends on the intensifier band system
already in place.

**Scope:**

### 26.1 Cosmology (16.1, 16.2)

- No code. The cosmology (Herobrine's fractured memory, the
  containment arc) is the internal reference for content authoring.
  It retroactively explains existing palettes and mechanics without
  any in-game text.

### 26.2 VISION.md update

- Formally update `VISION.md` section 9's "not a lore project" line
  to reflect the settled decision: lore is allowed to exist and be
  found, on its own tonal register, without touching the mechanical
  naming's voice. This is a doc edit, not code.

### 26.3 Alex's diaries (16.3)

- `minecraft:written_book` items, pre-authored pages and a title,
  dropped as loot at milestone moments (one per intensifier band
  crossed, tracked the same way `completedThemes` is tracked, no
  duplicates).
- Found out of order, the way the adventure graph itself is
  discovered. Early entries read as hope, middle entries as dread,
  late entries as resolution.
- The actual entry text needs its own authoring pass; this milestone
  ships the delivery mechanism (book items as loot, band-gated) and
  one proof entry.
- `DungeonLog.Entry` gains a `diaryBandsSeen` set (which intensifier
  bands the player has already received a diary for, to prevent
  duplicates).

### 26.4 Make the compass actually point (16.4)

- **Blocking verification:** check whether mod-placed lodestones
  register in the POI manager. If they do, `tracked: true`
  self-heals on teardown. If they do not, `tracked: false` and the
  mod owns staleness.
- Point the keystone's `LODESTONE_TRACKER` at the terminal cell's pad
  for the duration of a run. Re-point at the room's own lodestone
  the moment completion stamps that room in front of the player.
- The update hook already exists: `Instances.reconcileKeystones`
  runs on the watcher interval and already rewrites every online
  player's keystone in place.
- The lore beat comes for free: the compass spins in the overworld
  (nothing to find) and steadies the moment the player enters the
  dungeon.
- **Tension to resolve:** `VISION.md` section 4 asks the room-
  relocation trick stay unexplained. A compass that quietly swings
  to a new bearing is a signal, not a message. Worth a deliberate
  call on whether this makes the trick more discoverable in a good
  way or breaks the silence.

**Done when:**

1. A player entering a new intensifier band for the first time finds
   a diary book in their next completion chest.
2. The keystone compass points at the terminal pad during a run and
   at the room's lodestone after completion.
3. The compass spins in the overworld (no target in that dimension).
4. No lore text appears in any mechanical naming (item names, affix
   names, chat messages).

---

## M27: Extra features

**Brainstorm sections:** 15.1, 15.2, 15.3

**Goal:** three small features that enhance the existing loop without
being load-bearing.

### 27.1 MrPinoy's Experimental Dungeon (15.1)

- A fourth door (or a special state on one of the three) that offers
  a fixed, operator-set dungeon for a limited time.
- An operator command sets the experimental offer (theme, affixes,
  optional loot override). The door screen shows a caution indicator.
- No per-player daily reward tracking yet; that comes once the feature
  graduates from testing.
- **Depends on:** M19 (door screen for the caution display).

### 27.2 Room visitor log (15.2)

- A dialog from the wall terminal showing the last N visitors: name,
  time, whether they are still inside.
- A small ring buffer capped at 10 entries, pushed on each
  `Instances.visit` call. Each entry stores the visitor's name and an
  `Instant` timestamp.
- **Depends on:** M20 (visiting rework), M21 (wall terminal menu).

### 27.3 Death checkpoint (15.3)

- If dungeons grow long enough that re-traversing from the entrance
  after a death is a meaningful frustration, a checkpoint system
  saves the player's position at each cleared spawner cell.
- On death in the dungeon dimension, the player respawns at the last
  cleared cell instead of the entrance.
- **Contingent on dungeon length.** The current layout is short enough
  that re-traversal is trivial. Deferred until multi-floor or extended
  dungeons land (M25's Pocket2, or any future "deep dungeon" idea).
- **Depends on:** M10 (spawner gating, for "cleared" state tracking).

---

## Open decisions to settle before each milestone

| Milestone | Decision | Brainstorm question |
|---|---|---|
| M18 | Wall lodestone exact position (which wall, which block) | 9.3 does not specify |
| M19 | Screen text encoding: confirm 26.2 `text_display` API | 10.1 says "follow Hearsay" |
| M20 | Dialog pagination: `DialogListDialog` vs SGUI | DIALOGS_SPEC.md section 7 |
| M21 | Whether `/dungeon` with no args opens the menu or starts a run | 11 says commands stay as shortcuts |
| M24 | Which shells exist at launch, and which are rare-node vs prestige | 15.5 does not enumerate |
| M25 | Timer length (60 vs 90s), loot table composition | 15.6 says "maybe 60, maybe 90" |
| M26 | Whether the compass pointing is too much signal for VISION.md section 4 | 16.4 names this tension |
| M26 | Alex's fate: succeeds, fails, or stops writing | 16.3 says "not decided here" |

---

## Dependency graph

```
M18 ──┬──> M19 ──> M21 ──> M22
      │           ^
      ├──> M20 ───┘
      │
      └──> M24

M23 (independent)

M11 ──> M25

M26 (independent, 26.4 needs jar verification)

M19 ──> M27.1
M20 ──> M27.2
M21 ──> M27.2
M10 ──> M27.3 (deferred)
```

---

## Estimated sizes

| Milestone | Size | Notes |
|---|---|---|
| M18 | Medium | Template changes + protection logic, no new systems |
| M19 | Large | New screen entity, bulb/lever state, engine terminal, deletes dialog |
| M20 | Medium | New dialog, delete calling card, persist two fields |
| M21 | Medium | Menu dialog, invert guard, delete leave-pad watcher |
| M22 | Small | One class, one line per call site |
| M23 | Small | Two commands, existing capture method |
| M24 | Medium | Shell swap orchestration, unlock tracking, menu option |
| M25 | Large | Child instance lifecycle, nested tick watcher, new room spec |
| M26 | Medium | Book loot, compass pointing, jar verification |
| M27 | Small each | Three independent features |

---

## Standing rules (unchanged from ROADMAP.md)

1. Verify against the 26.2 jar, not memory.
2. No client mod, ever.
3. Mods stay strangers.
4. Self-sufficiency is a constraint, not a mode.
5. The mod stays quiet about the trick (section 4), and everything it
   does say is slang.
6. Superseded designs are marked superseded, not deleted.
