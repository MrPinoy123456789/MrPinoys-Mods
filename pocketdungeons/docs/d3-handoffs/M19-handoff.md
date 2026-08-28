# M19 - Physical door selection: screen, bulbs, lever, engine terminal - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/ROOM_UX_PLAN.md`'s `## M19: Physical door selection`
   section: **the authoritative scope.** Goal, dependencies, scope, touch
   points, done-when, and verification notes all live there. This handoff
   does not repeat them.
3. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific): toolchain, commands,
   the one-mixin budget, conventions, and the resource-directory layout.
4. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
5. `pocketdungeons/docs/DOOR_LADDER_BRAINSTORM.md` section 10 (all
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
   `docs/LIVE_TEST_PASS.md` now, not a `PROGRESS.md` file.** That file and the
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
- **`DialogRouter`'s `doorOffer` dispatch**: the case that handles the
  dialog button payload from the door offer dialog. No longer needed
  since the lever calls `chooseOffer` directly.
- The "Take this key" button in the door offer dialog: replaced by the
  lever pull.

**What stays:**

- `Instances.chooseOffer`: still the entry point for starting a dungeon;
  only the call site changes (lever instead of dialog button).
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
are all client-interactive. Record in `docs/LIVE_TEST_PASS.md`.

**Headless-verifiable:** `isFurniture` coordinate logic (pure Java
test), `selectedStep` field codec round trip, `chooseOffer` call path
(no compile error, logic correct).

## Doc updates you owe on completion

1. `plans/COMPLETED-MILESTONES.md`: add this milestone's summary in its
   place, in the same architectural-summary style as M0-M17's entries.
2. `docs/LIVE_TEST_PASS.md`: add a numbered section for whatever in this
   milestone is client-interactive and cannot be verified headless.
3. `docs/ROADMAP.md`: this milestone's entry, in order. No checkboxes; the
   roadmap carries order, not status.
4. `docs/ROOM_UX_PLAN.md`: if you diverge from the plan's scope for this
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
