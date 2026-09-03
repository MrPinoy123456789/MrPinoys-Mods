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

M28  Themed mob spawners      -- independent, ships anytime
M29  No-backwards propagation -- independent, ships anytime
M30  Connector variations     -- independent
M31  Dungeon shell protection -- independent, M30 IRON_DOOR benefits from it
```

M18-M22 are the core Room UX pass. They have a strict dependency chain
and should land in order. M23-M31 are independent of each other and of
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

1. Right-click a lodestone while not in the dungeon (the room is stamped in
   the dungeon dimension, so "overworld" means any lodestone outside it)
   with an empty hand: the menu opens with Start, Browse, Manage, Inspect
   options.
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
- The room-wide `RESPAWN_ANCHOR_CHARGE` broadcast in
  `DialogRouter.startDungeon` (moved there by M21) migrates to
  `Chime.runStarts`, a per-player packet heard only by the player who
  started the run.
- **Landed divergence: the spawner-cleared cue is a small watcher, not
  a one-liner.** There is no per-cell spawner-clear event anywhere in
  the codebase to hook, so the plan's "no new hooks, no new listeners,
  no new state" does not apply to that one cue. `InstanceRecord` gains
  an in-memory `clearedCells` set (dies with the instance, never
  persisted), and `Instances.onTick`'s per-record loop watches
  `layout.trialSpawners()` grouped by cell; the moment a cell's every
  spawner sits at `COOLDOWN`, the cue fires to each member standing in
  that cell. One field and one watcher block, no codec, no migration.

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

## M28: Themed mob spawners

**Goal:** dungeon themes control which mobs spawn from trial spawners, not
just wall blocks. A `spawner_prefix` field on `DungeonThemeMeta` selects
themed spawner configs (`{prefix}_tier_{n}/{normal,ominous}.json`) instead
of the default `tier_{n}` files. Two proof-of-concept themes: Crypt
(zombies+skeletons only) and Infestation (spiders only).

**Depends on:** nothing (M1's theme system and M10's trial spawner configs
are the foundation).

**Scope:**

### 28.1 `spawner_prefix` on `DungeonThemeMeta`

- Optional field, parallel to `lootSuffix`. Null = default tier configs.
- `TrialContent.configId` gains prefix param: null/empty produces
  `pocketdungeons:tier_{n}/...`, non-null produces
  `pocketdungeons:{prefix}_tier_{n}/...`.
- Theme string threaded from `LayoutStamper.stamp` through `RoomContent.apply`
  to `TrialContent.applyEncounter`.
- Swarming `writeInlineConfig` path uses prefix too.

### 28.2 Themed spawner configs

- 6 JSON files per theme (tier 1-3, normal + ominous).
- Copy counts/ticks/eject from existing `tier_{n}` files. Only
  `spawn_potentials` changes.
- Crypt: zombie (weight 5) + skeleton (weight 4). Equipment: reuse
  `pocketdungeons:equipment/tier_{n}_melee` and `_ranged`.
- Infestation: spider (weight 3) + cave spider (weight 2). No equipment.
  New `dungeon_theme/infestation.json`, reuses deepslate processors.

**Done when:** each theme with `spawner_prefix` spawns only its themed mobs;
themes without prefix behave as before.

**Touch points:** `DungeonThemeMeta`, `RoomContent.apply`,
`TrialContent.applyEncounter`, `TrialContent.configId`,
`TrialContent.writeInlineConfig`, `LayoutStamper.stamp`, theme JSONs, new
spawner config JSONs.

---

## M29: No-backwards propagation

**Goal:** dungeons never wrap behind the player room. If the dungeon door
opens SOUTH, no cell may exist at z < 0 relative to the entrance. A
validation check in `LayoutGraphGenerator.validate` rejects shapes where
any cell is behind the entrance on the entrance axis. The retry budget
handles rejected shapes.

**Depends on:** nothing (existing layout pipeline is the foundation).

**Scope:**

### 29.1 Validation check

- `LayoutGraphGenerator.validate`: compute `entranceDirection()`, check no
  cell is behind (0,0) on that axis. Property is rotation-invariant.
- `isBehind(PlanCell, Direction)`: EAST -> x < 0, WEST -> x > 0, NORTH ->
  z > 0, SOUTH -> z < 0.
- Applies to all cells: critical path, branches, loops.

### 29.2 Config toggle

- `PocketDungeonsConfig.noBackwardsPropagation` (boolean, default true).
- Optional: skip check if false, for testing or exotic layouts.

**Done when:** no valid shape has a cell behind the entrance; plan
resolution rate stays above 95%.

**Touch points:** `LayoutGraphGenerator.validate`, `LayoutPlanner.plan`,
`PocketDungeonsConfig`.

---

## M30: Connector variations

**Goal:** varied connector patterns at door openings: wide door
(current default), double door, single door, iron door, bars, open
wall with pillars, arch with lintel. Seeded per-edge. Visual variety
without changing cell size, room template format, or the canonical
door slot position. Room shape variety (corridors, T-shapes, subrooms,
dividers) is content-only via `.nbt` templates; no code change needed.

**Depends on:** nothing. IRON_DOOR benefits from M31 (shell
protection) so players cannot mine around gated doors.

**Scope:**

### 30.1 ConnectorType enum and per-edge dispatch

- `ConnectorType` enum: `DOOR_WIDE` (2-wide, current default),
  `DOOR_DOUBLE` (4-wide), `DOOR_SINGLE` (1-wide), `IRON_DOOR`
  (2-wide, requires redstone), `BARS` (iron bars floor to ceiling),
  `OPEN` (full width, 2x2 pillars at corners), `ARCH` (full width,
  lintel).
- `LayoutStamper.stamp`: after the per-cell stamp loop, before
  `BedrockEnvelope.apply`, add a connector pass. Iterate
  `plan.doors()` (`Set<PlanEdge>`). For each edge, compute the
  connector type via seeded weighted random, then apply to both
  cells' sides of the wall.
- The connector pass overlays existing templates after
  `TemplateStamper.place` has resolved door jigsaws to air. No
  template changes, no manifest changes.
- No offset: door slot stays at canonical position (`DOOR_MIN=7`,
  `DOOR_MAX=8`). Templates' doorway lane rule is authored against
  this position.

### 30.2 Weighted random selection

- Per edge: weighted random type. Weights: DOOR_WIDE 45,
  DOOR_SINGLE 15, DOOR_DOUBLE 10, IRON_DOOR 10, OPEN 10, ARCH 5,
  BARS 5.
- Seeded from plan seed and edge identity. Same seed = same
  connectors.
- Entrance edge: always DOOR_WIDE, no roll.
- Per-edge, not per-cell: both sides of an edge get the same type.

### 30.3 Connector application

- `applyConnector(level, cellOrigin, wall, type)`: overlays the
  connector pattern on the door slot and surrounding wall. Door
  slot is already air (jigsaw resolved). Surrounding wall is solid
  wall blocks from `RoomBuilder.buildShell`, possibly re-skinned by
  theme processors. Connector pass runs after processors.
- DOOR_WIDE: no-op (slot is already air).
- DOOR_SINGLE: fill one column with wall block read from adjacent
  position. Leave other column as air.
- DOOR_DOUBLE: clear 2 additional wall columns to air (4-wide
  total).
- IRON_DOOR: place iron door blocks in the 2-wide slot. Requires
  redstone. No source placed.
- BARS: iron bars from floor to ceiling.
- OPEN: clear entire wall, place 2x2 pillars at corners.
- ARCH: clear wall below top 2 rows, leaving lintel.

### 30.4 BedrockEnvelope

- No change needed. `BedrockEnvelope.applyToCell` already skips
  faces with occupied neighbours (the faces that have doors).
  OPEN and ARCH remove wall blocks on faces that already have
  neighbours, and BedrockEnvelope already leaves those faces alone.

**Done when:** each connector type renders correctly on both sides
of the edge, default templates unchanged, entrance edge always
DOOR_WIDE, BedrockEnvelope needs no changes.

**Touch points:** `LayoutStamper.stamp` (new connector pass),
`DungeonPlan.doors()` (edge iteration), `CellGeometry
.doorSlotPositions` (slot positions), new `ConnectorType` enum.

---

## M31: Dungeon shell protection

**Goal:** during active runs, all dungeon cells are block-break and
block-place protected. Players cannot mine walls to bypass doors or
shortcuts. After first completion, protection lifts on dungeon cells
so players can mine the dungeon freely. Player room protection (M18)
unchanged. Enables M30 `IRON_DOOR` connector as a real gate: without
protection, players just mine around iron doors.

**Depends on:** nothing. M30 `IRON_DOOR` benefits from this but M31
can ship independently.

**Scope:**

### 31.1 `Instances.dungeonRecordAt`

- New method: checks if `BlockPos` is inside any active run's dungeon
  cells. Iterates `InstanceRegistry.bySlot`, checks
  `record.layout.geometry().cells()`, skips room cell, skips completed
  runs (`record.completed.isEmpty()`).

### 31.2 `RoomProtection` dungeon check

- `beforeBlockBreak`: after existing room check, call
  `dungeonRecordAt`. If non-null: return false (deny break).
- `RitualListener` placement: same check for `placementPos`. Deny
  placement inside active dungeon cells. Allow chest open, lever/button
  use, spawner interaction.

### 31.3 Automatic lift on completion

- `dungeonRecordAt` checks `record.completed.isEmpty()`. Once first
  completion happens, method returns null, protection lifts. No
  explicit removal call needed.

### 31.4 Protection-lift indicator

- On first completion (`completeRun`), send a green chat message to
  all dungeon members: "The dungeon's shell has weakened. You can
  break blocks now." Plus a short positive sound cue.
- Sent once at the moment of first completion. Not repeated on
  re-entry or subsequent completions.

**Done when:** dungeon blocks unbreakable during active runs, breakable
after completion, player room protection unchanged, players notified
when protection lifts.

**Touch points:** `Instances.dungeonRecordAt` (new),
`RoomProtection.beforeBlockBreak`, `RitualListener` placement section,
`RunLifecycle.completeRun` (protection-lift message).

---

## M32: Tutorial screen and engine label

**Goal:** two `DungeonScreen` text changes. (1) Engine screen
label says "ECHO SHARDS" not "ENGINE". (2) At keystone level 1,
door screen shows tutorial prompts: "Select the Oak Door" when
idle, "Pull the lever to descend!" when a door is selected.
Tutorial disappears at level 2+.

**Depends on:** nothing. M19 screens must exist (landed).

**Scope:**

### 32.1 Engine screen label

- `DungeonScreen.engineContent` line 211: change
  `Component.literal("ENGINE")` to
  `Component.literal("ECHO SHARDS")`. One string literal.

### 32.2 Door screen tutorial at level 1

- `DungeonScreen.idleContent`: add `ServerLevel` and `UUID owner`
  params (both nullable). When owner non-null and
  `DungeonLog.forServer(level.getServer()).get(owner).keystoneLevel() <= 1`:
  return "POCKET DUNGEONS\nSelect the Oak Door\nThen pull the lever to descend".
  Else: current text.
- `DungeonScreen.previewContent`: after existing content, if
  `offerLevel <= 1`: append green
  "\nPull the lever to descend!". No signature change.
- Four `idleContent()` call sites updated to pass
  `(level, owner)`: `Instances.stampLobby`, `RunLifecycle`
  lines 525-526 and 1002, `RoomBuilder` line 415.

**Done when:** engine screen reads "ECHO SHARDS", level-1 door
screen shows tutorial prompts, level 2+ unchanged.

**Touch points:** `DungeonScreen.idleContent`,
`DungeonScreen.previewContent`, `DungeonScreen.engineContent`,
`Instances.stampLobby`, `RunLifecycle` (two sites),
`RoomBuilder` (one site).

---

## M33: Guided tasks via tracker screen

**Goal:** sequential task system teaching core loops. Ten
tasks, each level-gated, one active at a time (lowest
incomplete). Progress shown on door screen and a physical
tracker screen in the player's room. Inspired by archived
dailyquests mod's turn-in pattern: visible objective, count,
automatic detection. The tracker screen replaces the
originally planned scoreboard sidebar: instead of a global
per-player sidebar, the progress is a third physical screen
in the room, visible only to whoever is standing in it.

**Depends on:** nothing. M19 screens and M32 tutorial text
must exist (landed or planned).

**Scope:**

### 33.1 TaskTracker and DungeonLog sidecar

- New `TaskTracker.java`: enum `Task(id, label, targetCount,
  minLevel)`. `progress`, `activeTask`, `taskLine` methods.
- `DungeonLog`: sidecar `Map<UUID, Map<String, Integer>>
  taskProgress`, codec `task_progress`, default empty.
- `progress`: increments sidecar, checks completion,
  advances, chat on completion. Auto-completes tasks below
  player's keystoneLevel.

### 33.2 Tracker screen

- `DungeonScreen.updateTracker` / `refreshTracker`: a
  physical screen on the wall opposite the engine screen
  (the selector wall's right), showing the owner's active
  task with its progress. Replaces the originally planned
  `pd_task` scoreboard objective.
- On completion: the screen picks up the next active task
  automatically. All done: the screen hands off to
  `BountyTracker`'s weekly bounties (M34).

### 33.3 Hook into existing events

- `selectDoor`, `chooseOffer`, `completeRun`, `Fuel.bank`,
  `GambleStation.onUse`, `RerollStation.onUse`,
  `CubeStation.onUse` extract, `VisitService.visit`,
  `EntityTameEvent` (wolf, dungeon dim).

### 33.4 Door screen and login sync

- `idleContent`, `previewContent`, `runContent`: append
  `TaskTracker.taskLine(owner)`.
- Player join: refresh tracker screen, chat with active task.

**Task list:**

1. Select a Door (lvl 1, x1)
2. Descend (lvl 1, x1)
3. Complete a Run (lvl 1, x1)
4. Feed the Engine (lvl 2, x3)
5. Visit a Friend (lvl 5, x1)
6. Open a Greater Door (lvl 15, x1)
7. Spend Emeralds at Kadala (lvl 20, x16)
8. Reroll an Enchantment (lvl 25, x1)
9. Extract a Power (lvl 30, x1)
10. Tame a Wolf (Feral, x1)

**Done when:** tasks surface on door screen and tracker
screen, advance automatically, persist across restarts.

**Touch points:** `TaskTracker` (new), `DungeonLog`
(sidecar), `DungeonScreen` (three content methods +
tracker screen), `RitualListener.selectDoor`,
`RunLifecycle.chooseOffer`, `RunLifecycle.completeRun`,
`GambleStation.onUse`, `RerollStation.onUse`,
`CubeStation.onUse`, `VisitService.visit`,
`EntityTameEvent` listener, `PocketDungeonsMod` join
handler.

---

## M34: Weekly bounties for party leaders

**Goal:** three weekly bounties per dungeon host (instance
owner), seeded from owner UUID and ISO week key. Party members
contribute progress; all online members get rewards on
completion. Inspired by archived dailyquests mod's turn-in
pattern, adapted to dungeon activities and party play.

**Depends on:** nothing. M33 tracker screen reusable.
M19 screens must exist (landed).

**Scope:**

### 34.1 BountyTracker and DungeonLog sidecar

- New `BountyTracker.java`: enum `Bounty(id, label,
  targetCount)`. `weekKey()`: `YearWeek.now(UTC)`,
  `"yyyy-Www"`. `bountiesFor(owner)`: three seeded from
  `owner.hashCode() ^ weekKey`.
- `BountyState(weekKey, bountyId, progress, completed)`.
- `progress(server, owner, bountyId, amount, members)`:
  increments, checks completion, `Payout.deliver` to online
  members, owner +1 shard bonus.
- `DungeonLog`: sidecar `Map<UUID, List<BountyState>>`,
  codec `bounties`, default empty. Stale weekKey resets.

### 34.2 Tracker screen

- The M33 tracker screen now also shows bounty lines below
  the task line once the tutorial tasks are done. Replaces
  the originally planned `pd_bounty` scoreboard sidebar.
- `DungeonScreen.refreshTracker` runs whenever the door
  screen recomputes its bounty lines.

### 34.3 Hook into events

- `completeRun`: `CLEAR_HALLS` by spawners, `SPEEDRUNNER`
  if timed, `SPELUNKER` if `step>=2`, `PACK_HUNTER` if
  `members.size()>=2`.
- RitualListener after `Fuel.bank`: `ECHO_HARVESTER`.
- `GambleStation.onUse`: `HIGH_ROLLER` by emeralds.
- `DungeonLog.setKeystone`: `KEYSTONE_CLIMBER` by delta.

### 34.4 Door screen and login

- `idleContent`, `previewContent`: append `bountyLine(owner)`
  after task line (M33).
- Join: refresh tracker screen, chat with bounties.
- Completion: broadcast to party in green.

**Bounty pool (three per week per owner):**

1. Clear the Halls: clear 20 spawners this week.
2. Echo Harvester: bank 9 echo shards.
3. Speedrunner: complete 3 timed runs.
4. High Roller: spend 32 emeralds at gamble station.
5. Spelunker: complete 2 runs via Greater door.
6. Pack Hunter: complete 3 runs with 2+ members.
7. Keystone Climber: gain 3 keystone levels.

Reward: 2 echo shards + 4 emeralds per online member.
Owner +1 shard bonus.

**Done when:** three weekly bounties per owner, party
contribution, shared rewards, tracker screen, door screen,
week reset.

**Touch points:** `BountyTracker` (new), `DungeonLog`
(sidecar), `DungeonScreen` (two content methods + tracker
screen), `RunLifecycle.completeRun`, `RitualListener`
engine handler, `GambleStation.onUse`,
`DungeonLog.setKeystone`, `PocketDungeonsMod` join
handler.

---

## M35: Anomaly rooms

**Goal:** rarely, a themed run contains one room that does not
belong to its theme. A dedicated anomaly room set, loaded
separately from the themed room manifest, supplies rooms whose
palette and content are deliberately foreign. The player walks
through a door and the room is wrong: blocks real, placement
not, walls of a material that matches nothing around it. A
"wrong room" beat, tied to Entry 2's tone, without any in-game
text naming it.

**Depends on:** M11 (adventure graph for rare-node gating).
The room selection pipeline (`RoomSelector.resolveDetailed`)
and manifest loader (`RoomManifest`) must exist, both landed
early.

**Scope:**

### 35.1 Anomaly room manifest

- New resource path: `data/pocketdungeons/anomaly_room/*.json`.
  Same JSON shape as `dungeon_room/*.json`.
- `RoomManifest.currentAnomaly()`: second manifest instance,
  same loader, separate index, same validation.
- Ship 2-3 anomaly room templates. Foreign palette, slightly
  off geometry. No custom blocks or items: vanilla blocks
  placed wrong.

### 35.2 Anomaly injection in RoomSelector

- `RoomSelector.resolveDetailed`: after normal resolution,
  roll anomaly chance from plan seed. Gated on
  `AdventureGraphs.current().graph().node(theme) != null`,
  same gate as the Pocket2 door.
- If the roll succeeds, pick one critical-path cell (non-
  entrance, non-terminal) and swap its room for an anomaly
  room satisfying the same mask and role. One per run.
- If no anomaly room matches, silently skip: the run stays
  normal. Anomaly is a bonus, never a requirement.
- `DungeonPlan` carries `anomalyCell` so `LayoutStamper` and
  `RoomContent` can skip theme-tied processors, loot suffix,
  and themed spawners on that cell.

### 35.3 Config and chance

- `PocketDungeonsConfig.anomalyRoomChance` (double, default
  0.08). Same pattern as `pocket2DoorChance`.

### 35.4 Content and loot

- Anomaly rooms carry own loose chests and 0-1 spawners, no
  theme suffix. Loot table:
  `pocketdungeons:chests/anomaly/`.
- Loot: echo shards, shell unlock tokens, rare materials.
  Not better than completion chest, not worse than loose
  chest.
- No keystone, no completion pad, no lodestone. Pass-through
  cell on the critical path, not a destination.

**Done when:**

1. Anomaly rooms appear rarely in themed runs, on the
   critical path, connecting to their neighbours.
2. The room's palette and geometry read as wrong inside any
   themed run.
3. The run completes normally with an anomaly room in it.
4. Unthemed runs and themes with no adventure-graph node never
   host an anomaly.
5. No lore text in the room itself; the link to Entry 2 is
   tonal only.

**Touch points:** `RoomManifest` (second instance),
`RoomSelector.resolveDetailed`, `DungeonPlan` (new field),
`LayoutStamper` (processor skip), `RoomContent` (theme skip),
`PocketDungeonsConfig` (chance), new anomaly room templates
and loot table.

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

M28 (independent)

M29 (independent)

M30 (independent)

M31 (independent, M30 IRON_DOOR benefits from it)

M32 (independent, needs M19 screens)

M33 (independent, needs M19 screens + M32 tutorial)

M34 (independent, M33 tracker screen reusable)

M11 ──> M35
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
| M28 | Small | New field on theme meta, configId prefix logic, 12 JSON files |
| M29 | Small | Validation check in LayoutGraphGenerator, config toggle |
| M30 | Small | Connector carving in LayoutStamper, enum, BedrockEnvelope tweak |
| M31 | Small | dungeonRecordAt in Instances, protection check in RoomProtection + RitualListener |
| M32 | Small | Two string changes in DungeonScreen, idleContent gains owner param |
| M33 | Medium | New TaskTracker class, DungeonLog sidecar, tracker screen, 9 hook points |
| M34 | Medium | New BountyTracker class, DungeonLog sidecar, tracker screen, 4 hook points |
| M35 | Medium | Second manifest loader, RoomSelector injection, DungeonPlan field, 2-3 templates + loot table |

---

## Standing rules (unchanged from ROADMAP.md)

1. Verify against the 26.2 jar, not memory.
2. No client mod, ever.
3. Mods stay strangers.
4. Self-sufficiency is a constraint, not a mode.
5. The mod stays quiet about the trick (section 4), and everything it
   does say is slang.
6. Superseded designs are marked superseded, not deleted.
