# M19 - Physical door selection: screen, bulbs, lever, engine terminal - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/reference/ROOM_UX_PLAN.md`'s `## M19: Physical door selection`
   section: **the authoritative scope.** Goal, dependencies, scope, touch
   points, done-when, and verification notes all live there. This handoff
   does not repeat them.
3. `pocketdungeons/docs/reference/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific): toolchain, commands,
   the one-mixin budget, conventions, and the resource-directory layout.
4. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
5. `pocketdungeons/docs/reference/DOOR_LADDER_BRAINSTORM.md` section 10 (all
   subsections): the design rationale for the physical UI.
6. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M17 actually
   built, for context on what this milestone extends.
7. **Hearsay's `Bubbles.java`**: the `text_display` entity pattern this
   milestone's screen follows. Read it before writing `DungeonScreen`.

## Before you start: confirm the dependency is actually done

**Hard dependency on M18.** Check `plans/COMPLETED-MILESTONES.md` for an
M18 entry, and confirm:

- `RoomProtection.isShell` exists and protects the room's shell blocks.
- The wall lodestone is in place (M18 moved it from the floor).
- The selector opening has double doors (M18 placed them).

This milestone adds furniture (bulbs, lever, screen, engine block) that
must be protected from player breaking. Without M18's `isShell`
infrastructure, the furniture protection has no foundation.

Also confirm M12's fuel system (`Fuel.java`, `PocketDungeonsConfig
.fuelItem()`, `fuelCostPerGreaterDoor()`) exists and works, since the
engine terminal reads and displays fuel state.

## Goal, in one line

The three selector doors, a `text_display` screen above them, copper
bulbs as selection indicators, and a lever as the commit replace the
dialog-based door offer. A separate engine terminal (respawn anchor)
manages fuel. No popups; the walk between doors is the browse, the
lever is the commit.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
   (POSIX form for Git Bash: `/c/Users/Kriss/.gradle/...`). Checking a method
   exists is not the same as checking what it does. This is trap 1 in
   `DISCOVERIES.md` and it has shipped three bugs.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   items, no custom sounds. Everything is vanilla blocks, vanilla items,
   vanilla sound events, and server-side dialogs.
3. **No em dashes and no double hyphens as punctuation**, anywhere a person
   reads: chat, dialogs, item lore, command output, log lines, markdown,
   javadoc, commit messages. Use `:`, `;`, `,`, `()`, or two sentences.
   Command-line flags and code operators (`i--`) are not punctuation and stay
   as they are. Do not mass rewrite existing violations; they stay until that
   line is edited for another reason. See `A:\MrPinoys Mods\CLAUDE.md`.
4. **`./gradlew build` green after every commit**, not just at the end.
5. **The one-mixin budget is exactly one**, `mixin/CustomClickMixin`. If this
   milestone seems to need a second mixin, say so in your report and exhaust
   the Fabric-event or datapack-recipe route first (trap 9, and traps 14/15
   in `DISCOVERIES.md` are a worked example of that search paying off).
6. **Status lives in `plans/COMPLETED-MILESTONES.md` and
   `docs/reference/LIVE_TEST_PASS.md` now, not a `PROGRESS.md` file.** That file and the
   `handoffs/` folder were retired once M0-M9 went code-complete. Do not
   recreate either.
7. **One commit per logical change, message explains why, not just what.**
8. **`Codec` migration discipline:** a superseded `DungeonLog.Entry` field is
   marked superseded in its javadoc and its codec field kept, never deleted on
   the first pass. See "Per-player persistent state" in the plan's
   implementation-context section.

## Start here

- **Read Hearsay's `Bubbles.java` first.** The screen is a
  `text_display` entity, and `Bubbles` is the working reference for
  summoning, positioning, updating, and cleaning up display entities in
  this codebase. Follow its pattern exactly: `see_through=false`,
  forced brightness, NBT object via `ComponentSerialization.CODEC` (not
  JSON string since 1.21.5), entity tag for orphan cleanup.
- **Verify `text_display` entity NBT shape against the 26.2 jar.** The
  `text_display` entity's fields (`billboard`, `brightness`,
  `transformation`, `text`, `line_width`, `background`) must be
  confirmed against the actual jar, not assumed from memory or from
  Hearsay's usage (which may be on a different MC version).
- **The `isFurniture` check is separate from `isShell`.** Furniture is
  mod-placed blocks the player cannot break: lever, screen blocks
  (black concrete), engine block (respawn anchor), copper bulbs. It
  is a positional check against the room origin, the same shape as
  `isShell`, but covering different positions. Both checks run in
  `RoomProtection.beforeBlockBreak` and the placement guard in
  `RitualListener`.
- **`selectedStep` is a new field on `InstanceRecord`.** Default 0
  (no selection). Set to 1/2/3 when a door is right-clicked. Reset to
  0 when the dungeon starts (after `chooseOffer`) or when the room is
  re-stamped. Persisted via the codec, same shape as `chosenStep`.
- **The engine terminal is a separate surface from the door screen.**
  The door screen shows offer details (level, theme, affixes). The
  engine screen shows fuel count and cost per premium door. Two
  `text_display` entities, two positions, two update paths. The
  separation is deliberate: "which fight" and "can I afford it" are
  different decisions.
- **`chooseOffer` reads `selectedStep` instead of a dialog payload.**
  The lever's right-click handler calls `chooseOffer` with the
  `selectedStep` from `InstanceRecord`, not from a dialog button
  payload. This is the same method; only the call site changes.
- **The screen is re-summoned after each room placement.** It is never
  captured with the room (it is an entity, not a block). The existing
  entity sweep on teardown handles cleanup.
- **Corrections to the plan and to older notes in this handoff.** The
  real offer-choice entry point is `RunLifecycle.chooseOffer(ServerPlayer,
  int step)` (line 382), not `Instances.chooseOffer`. The `/dungeon
  choose <step>` command calls it (DungeonCommands line 322). The lever
  calls the same method with `record.selectedStep`. Also, the current
  `DialogScreens.doorOffer` uses a command button (`/dungeon choose
  <step>`), not a `DialogRouter` payload dispatch, so there is no
  `doorOffer` dispatch case to delete; removing the dialog method and
  `sendDoorOffer` is enough. Fix `ROOM_UX_PLAN.md` line 263 and the
  "What stays" list below if you touch those lines.

## Detailed implementation plan

Build in this order. Each step is one commit (or a small, related
group). Run `./gradlew build` after each.

### Step 1: `selectedStep` field on `InstanceRecord`

`InstanceRecord` is in-memory only (no codec), so this is a one-line
field add plus reset points.

1. Add `int selectedStep;` (default 0) to `InstanceRecord`, next to
   `chosenStep` (line ~82). Javadoc: 0 means no door selected, 1/2/3
   means that selector door was right-clicked.
2. Reset it to 0 in `Instances.stampLobby` (line 420) when a room is
   re-stamped, and in `RunLifecycle.chooseOffer` (line 382) after
   `generateBehindLobby` succeeds (line 426). The reset in
   `chooseOffer` fires after the run starts, so the next room placement
   starts with no selection.
3. No codec change. No migration. The field is transient.

### Step 2: `isFurniture` check in `RoomProtection`

A positional check against the room origin, the same shape as `isShell`
(line 74), covering different positions. Both checks run in
`beforeBlockBreak` (line 42) and the placement guard in
`RitualListener` (line 105).

1. Add `isFurniture(BlockPos pos, BlockPos roomOrigin)` to
   `RoomProtection`. It returns true for:
   - The lever position: `x=10, y=2, z=1` (one block in front of the
     selector wall, beside the third door). Adjust to match the actual
     lever placement from step 4.
   - The four copper bulb positions: `x=7,8,9,10, y=4, z=1` (above each
     selector door and above the lever).
   - The screen blocks: black concrete at `y=4..5, x=4..11, z=0` (on
     the selector wall itself).
   - The engine block: respawn anchor on an adjacent wall (e.g. west
     wall at `x=0, y=2, z=7`). Adjust to match step 4.
   - The engine screen blocks: black concrete above the engine block.
2. Wire `isFurniture` into `beforeBlockBreak`: after the `isShell`
   check, add `|| isFurniture(pos, roomOrigin)` to the refusal
   condition. The owner cannot break furniture.
3. Wire `isFurniture` into the placement guard in
   `RitualListener.onUseBlock` (line 105): add `isFurniture` alongside
   `isShell` so the owner cannot place blocks on furniture positions.
4. The mod itself (`Instances.stampLobby`, `RoomTemplateGenerator`)
   bypasses `RoomProtection` (it writes directly via `RoomBuilder.set`),
   so furniture placement is not blocked by the check.

### Step 3: `DungeonScreen.java` (text_display management)

A new class following Hearsay's `Bubbles.java` pattern exactly. Read
`hearsay/fabric/src/main/java/hearsay/Bubbles.java` first.

1. Verify `text_display` entity NBT shape against the 26.2 jar before
   writing anything. Confirm fields: `billboard`, `brightness`,
   `transformation`, `text`, `line_width`, `background`, `view_range`,
   `see_through`, `shadow`, `alignment`, `text_opacity`, `Tags`, `Pos`.
   Use `javap -cp` / `unzip -l` on the jar (POSIX form in the standing
   rules).
2. Create `DungeonScreen.java` with:
   - `static final String TAG = "pocketdungeons_screen"` for orphan
     cleanup (the existing entity sweep on teardown kills tagged
     entities).
   - `summon(ServerLevel, BlockPos screenPos, Direction wallFace,
     Component text)`: creates a `Display.TextDisplay` via
     `EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND)`,
     builds a `CompoundTag` with `billboard: "fixed"`, `see_through:
     false`, forced `brightness {block: 15, sky: 15}`, `text` encoded
     via `ComponentSerialization.CODEC` against
     `level.registryAccess().createSerializationContext(NbtOps.INSTANCE)`
     (not a JSON string since 1.21.5), `Tags` carrying `TAG`, `Pos`
     offset ~0.1 blocks off the wall, `Rotation` matching the wall face.
     Load via `TagValueInput.create(...)` + `display.load(in)` inside a
     `ProblemReporter.ScopedCollector` (Bubbles lines 129 to 133). Then
     `level.addFreshEntity(display)`.
   - `update(ServerLevel, BlockPos screenPos, Direction wallFace,
     Component text)`: clear the old display at that position (entity
     sweep by tag near the pos), then `summon` a fresh one. Re-summoning
     is simpler than mutating a live entity's NBT and matches the
     plan's "re-summoned after each room placement" rule.
   - `clear(ServerLevel, BlockPos screenPos)`: kill tagged
     `text_display` entities near the position.
3. Two screen positions, two update paths:
   - **Door screen**: above the selector doors, on the selector wall at
     `y=4..5, x=4..11`. Shows offer preview, idle prompt, "Select a
     door first", "Not enough fuel", run-in-progress, post-completion.
   - **Engine screen**: above the engine block on its wall. Shows fuel
     count and cost per premium door.
4. Five door-screen content contexts (plan 19.1):
   - Idle: "Right-click a door to preview. Pull the lever to start."
   - Door preview: level, theme, affixes for `selectedStep`.
   - Run in progress: level, theme, affixes, spawner count or timer.
   - Post-completion: room name, visibility, visitor count.
   - Refusal: "Select a door first" or "Not enough fuel".
   Build these as `Component` literals in `DungeonScreen` (or in a
   helper on `DialogScreens` if that fits better; the plan names
   `DungeonScreen` as the new class).

### Step 4: Place furniture at stamp time

In `Instances.stampLobby` (line 420) and `VisitService.createVisitInstance`
(line 99), after the wall lodestone and selector doors are placed:

1. Three `minecraft:copper_bulb` at `y=4` above each selector door
   (`x=7, 8, 9` on the selector wall), all `LIT=false`. Use
   `RoomBuilder.set` with `Blocks.COPPER_BULB.defaultBlockState()` (no
   `LIT` override needed; default is unlit).
2. A fourth `copper_bulb` at `x=10, y=4` above the lever position, also
   `LIT=false`. This is the "ready to commit" indicator.
3. A lever at `x=10, y=2` on the selector wall (one block in front of
   it, beside the third selector door). Use
   `Blocks.LEVER.defaultBlockState()` with `FACING` and `FACE=WALL`
   matching the wall.
4. Screen blocks: `minecraft:black_concrete` at `y=4..5, x=4..11` on
   the selector wall (8 wide, 2 tall). These are the physical backdrop
   for the door screen's `text_display`.
5. Engine block: `minecraft:respawn_anchor` on an adjacent wall (e.g.
   west wall at `x=0, y=2, z=7`). Set `CHARGES=0` blockstate.
6. Engine screen blocks: `black_concrete` above the engine block (2
   tall, 2 wide).
7. Summon both `text_display` screens via `DungeonScreen.summon` with
   their idle content.
8. Add a `placeFurniture(ServerLevel, BlockPos, DoorMask.Direction)`
   helper to `RoomTemplateGenerator` (alongside
   `placeSelectorDoors`/`placeWallLodestone`) so the geometry is in one
   place and rotates correctly. Call it from `stampLobby` and
   `createVisitInstance`.
9. Add `clearFurniture(...)` to remove bulbs, lever, screen blocks,
   engine block before `RoomStore.capture` (capture hygiene, same as
   `clearPostSelectionDoors` at line 444). Re-place after capture if
   the room persists.

### Step 5: Replace `sendDoorOffer` with selection-state update

In `RitualListener.onUseBlock`, the selector-door branch (line 144):

1. Replace `sendDoorOffer(serverPlayer, step)` with a call to a new
   `selectDoor(serverPlayer, step)` method.
2. `selectDoor(ServerPlayer, int step)`:
   - Read the `InstanceRecord` for the player. If null or not
     `awaitingDoorChoice`, do nothing (the door is not a selector right
     now).
   - Set `record.selectedStep = step`.
   - Toggle bulbs: set the bulb for `step` to `LIT=true`, set the
     previously-selected bulb (if any) to `LIT=false`. Use
     `RoomBuilder.set` with the `COPPER_BULB` `LIT` property. One
     blockstate write per toggle, with `UPDATE_CLIENTS` (verify the
     flag combination against `RoomBuilder.set`'s existing usage).
   - Light the fourth bulb (ready-to-commit) at `x=10, y=4`.
   - Update the door screen: build the offer preview `Component` from
     `Keystone.offers(...)` for the selected step, call
     `DungeonScreen.update(...)` with the door-screen position and
     wall face.
3. Delete `RitualListener.sendDoorOffer` (line 233).

### Step 6: Lever right-click handler

Add a lever branch in `RitualListener.onUseBlock`, ahead of the
selector-door branch (so a lever click is not mistaken for a door
click):

1. Detect the lever: check the clicked block is `Blocks.LEVER` and the
   position matches the lever furniture position for the player's room
   origin. A `leverStep(ServerPlayer, BlockPos)` helper on `Instances`
   (parallel to `selectorDoorStep`) returns `true` if the click is on
   the room's commit lever.
2. If `record.selectedStep == 0`: update the door screen to show
   "Select a door first" and return `SUCCESS_SERVER`. (The bass chime
   is M22; leave a `// M22: Chime.noSelection(player)` comment.)
3. If `record.selectedStep > 0`: pre-check fuel for greater doors. Read
   the offer for `selectedStep` from `Keystone.offers(...)`. If it is a
   greater door and `Fuel.count(player) < fuelCostPerGreaterDoor()`,
   update the door screen to show "Not enough fuel" and return
   `SUCCESS_SERVER`. Do not call `chooseOffer`; the refusal stays
   client-visible on the screen, not just in chat.
4. Otherwise: call `RunLifecycle.chooseOffer(player,
   record.selectedStep)`. If it returns `true`, the run started
   (`chooseOffer` resets `selectedStep` to 0 from step 1). If it
   returns `false`, update the door screen with the refusal reason
   (the chat message from `chooseOffer` is the fallback; the screen
   is the primary surface).
5. Return `SUCCESS_SERVER` to prevent vanilla from toggling the lever
   visually (or allow it; the lever state is cosmetic. Decide and
   document).

### Step 7: Engine terminal handler

Add an engine branch in `RitualListener.onUseBlock`, ahead of the
selector-door branch:

1. Detect the engine block: check the clicked block is
   `Blocks.RESPAWN_ANCHOR` and the position matches the engine
   furniture position. An `engineTerminalAt(ServerPlayer, BlockPos)`
   helper on `Instances`.
2. On right-click: if the held item is `Fuel.FUEL_ITEM` (echo shard by
   default), call `Fuel.grant(player, 1)`, increment the anchor's
   `CHARGES` blockstate (capped at 4), update the engine screen with
   the new fuel count. Return `SUCCESS_SERVER` to prevent vanilla's
   Nether-charging behaviour.
3. If the held item is not fuel, just update the engine screen with
   the current fuel count and cost (a "view" click). Return
   `SUCCESS_SERVER`.
4. The engine screen reads `Fuel.count(player)` and
   `PocketDungeonsConfig.fuelCostPerGreaterDoor()` for its content.

### Step 8: Delete the dialog path

1. Delete `DialogScreens.doorOffer` (line 91).
2. Delete `RitualListener.sendDoorOffer` (already done in step 5).
3. The `/dungeon choose <step>` command (DungeonCommands line 65)
   stays as a power-user shortcut (M21 keeps commands). It calls
   `RunLifecycle.chooseOffer` directly, which still works. Do not
   delete it.
4. There is no `DialogRouter.doorOffer` dispatch to remove (the dialog
   used a command button, not a payload). Confirm with a grep before
   assuming otherwise.

### Step 9: Teardown and re-stamp hygiene

1. In the teardown path (the entity sweep in `Instances`), confirm
   tagged `pocketdungeons_screen` displays are killed. They should be,
   since the sweep already handles tagged entities; verify.
2. In `Instances.stampLobby` on re-stamp: `clearFurniture`, re-place
   furniture, re-summon screens. The `selectedStep` reset (step 1)
   fires here too.
3. In `VisitService.createVisitInstance`: the same `placeFurniture`
   call so visit copies get the physical UI. (Visitors do not choose
   doors, but the screen should show post-completion or idle context
   for the visited room.)

### Verification

- `./gradlew build` green after each step.
- Headless: `isFurniture` coordinate logic (pure Java test),
  `selectedStep` field add (compiles, no codec change),
  `chooseOffer` call path (lever calls it with `selectedStep`).
- Live: right-click a door (bulb lights, screen updates), right-click
  another (bulb swaps), pull lever with no selection ("Select a door
  first"), pull lever with selection (run starts), engine terminal
  accepts echo shards and shows fuel, lever on a greater door with no
  fuel ("Not enough fuel"), try to break each furniture block
  (refused). Record in `docs/reference/LIVE_TEST_PASS.md`.

## What you must not do

- **Do not capture the screen entity with the room.** `text_display`
  entities are transient; they are re-summoned at stamp time and purged
  on teardown. Capturing them would serialize a position-dependent
  entity into a rotation-aware template, which is fragile.
- **Do not use a dialog for door selection.** The whole point of this
  milestone is replacing `DialogScreens.doorOffer` with a physical UI.
  If you find yourself adding a new dialog for door selection, you have
  taken a wrong turn.
- **Do not let the player break the furniture.** Bulbs, lever, screen
  blocks, and engine block are all mod-placed and must be protected by
  `isFurniture`. A player breaking the lever would soft-lock door
  selection.
- **Do not put fuel management on the door screen.** The engine terminal
  is separate. Mixing "which fight" and "can I afford it" on one screen
  is the design error the brainstorm explicitly calls out (section 10.7).

## What this milestone deletes

- **`DialogScreens.doorOffer`**: the entire method and its dialog
  construction. The physical screen replaces it.
- **`RitualListener.sendDoorOffer`**: the method that sends the door
  offer dialog to the player. Replaced by a selection-state update
  (bulb toggle + screen update).
- **`DialogRouter`'s `doorOffer` dispatch**: the current `doorOffer`
  dialog uses a command button (`/dungeon choose <step>`), not a
  `DialogRouter` payload dispatch, so there is nothing to remove here.
  Confirm with a grep before assuming otherwise; if a dispatch was
  added in the meantime, delete it.
- The "Take this key" button in the door offer dialog: replaced by the
  lever pull.

**What stays:**

- `RunLifecycle.chooseOffer`: still the entry point for starting a
  dungeon; only the call site changes (lever instead of dialog button
  or `/dungeon choose`). The `/dungeon choose <step>` command stays
  as a power-user shortcut.
- `Keystone.offers`: still generates three offers; the screen displays
  them instead of the dialog.
- `Instances.placeSelectorDoors`: still places three door blocks; the
  bulbs go above them.
- `Instances.selectorDoorStep`: still detects which door was
  right-clicked; the right-click now toggles a bulb instead of sending
  a dialog.

## Verification bar

**Done when** (from the plan):

1. Right-clicking a selector door lights its bulb and updates the screen
   with that door's offer. No dialog popup.
2. Right-clicking a different door swaps the bulb and screen content.
3. Pulling the lever with a door selected starts the dungeon.
4. Pulling the lever with no door selected shows "Select a door first"
   on the screen.
5. The engine terminal shows fuel count and accepts echo shards.
6. Pulling the lever for a premium door without enough fuel shows "Not
   enough fuel" on the door screen.
7. None of the new furniture (bulbs, lever, screen, engine) can be
   broken by the player.

**Jar verification:** `text_display` entity NBT shape (fields, types,
serialization), `ClientboundAddEntityPacket` or equivalent for
summoning display entities server-side, `copper_bulb` blockstate
properties (`lit`, `powered`).

**Live-only:** all of it. The screen, bulbs, lever, and engine terminal
are all client-interactive. Record in `docs/reference/LIVE_TEST_PASS.md`.

**Headless-verifiable:** `isFurniture` coordinate logic (pure Java
test), `selectedStep` field codec round trip, `chooseOffer` call path
(no compile error, logic correct).

## Doc updates you owe on completion

1. `plans/COMPLETED-MILESTONES.md`: add this milestone's summary in its
   place, in the same architectural-summary style as M0-M17's entries.
2. `docs/reference/LIVE_TEST_PASS.md`: add a numbered section for whatever in this
   milestone is client-interactive and cannot be verified headless.
3. `docs/reference/ROADMAP.md`: this milestone's entry, in order. No checkboxes; the
   roadmap carries order, not status.
4. `docs/reference/ROOM_UX_PLAN.md`: if you diverge from the plan's scope for this
   milestone, **fix the plan first and say so.** Never silently diverge.
5. Any `DungeonLog.Entry` field this milestone supersedes gets a javadoc
   note saying so; its codec field stays until a migration confirms no
   live save carries the old data.
6. Rename this file `M19-handoff-completed.md` once the milestone is
   actually landed and its `COMPLETED-MILESTONES.md` entry exists.

## If you get stuck

- **`text_display` entities do not render or render in the wrong
  position.** Verify the NBT shape against the jar and compare with
  Hearsay's `Bubbles.java`. The `transformation` field (a 4x4 matrix)
  controls position offset; a wrong matrix puts the text inside the
  wall or too far off it.
- **Copper bulb blockstate writes do not propagate to clients.**
  `level.setBlock` with `UPDATE_CLIENTS` should work; verify the flag
  combination against `RoomBuilder.set`'s existing usage. If the bulb
  is placed via `StructureTemplate` rather than direct `setBlock`, the
  blockstate may need a separate update call.
- **The lever's right-click is intercepted by vanilla door behaviour.**
  The lever is a separate block from the doors; vanilla should not
  intercept it. If it does, check the `RitualListener.onUseBlock` order:
  the lever branch must be ahead of any door-related branch.
- **The engine terminal (respawn anchor) triggers vanilla charging
  behaviour.** The anchor's vanilla right-click charges it with
  glowstone in the Nether. The mod's intercept must fire before
  vanilla's own handler. Check `RitualListener.onUseBlock`'s return
  value: returning `SUCCESS` or `CONSUME` prevents vanilla from
  processing the same interaction.
