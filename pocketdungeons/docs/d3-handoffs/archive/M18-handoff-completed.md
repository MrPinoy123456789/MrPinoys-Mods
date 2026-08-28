# M18 - Room shell pass: immutable shell, double doors, wall lodestone, ceiling - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/reference/ROOM_UX_PLAN.md`'s `## M18: Room shell pass`
   section: **the authoritative scope.** Goal, dependencies, scope, touch
   points, done-when, and verification notes all live there. This handoff
   does not repeat them.
3. `pocketdungeons/docs/reference/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific): toolchain, commands,
   the one-mixin budget, conventions, and the resource-directory layout.
4. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
5. `pocketdungeons/docs/reference/DOOR_LADDER_BRAINSTORM.md` sections 9.1-9.4: the
   design rationale for each change in this milestone.
6. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M17 actually
   built, for context on what this milestone extends.

## Before you start: confirm the dependency is actually done

**Hard dependency on M2.** Check `plans/COMPLETED-MILESTONES.md` for an M2
entry, and confirm `RoomProtection`, `RoomBuilder.buildCell`, and
`RoomTemplateGenerator` exist and work. This milestone modifies all three.
M0-M17 are all code-complete; nothing else needs checking.

## Goal, in one line

The room's shell (floor, walls, ceiling, lamps) becomes immutable to the
owner, the lodestone moves from the floor to the wall, the selector
opening gets physical double doors, and the ceiling gets top slabs with
stair-framed light fixtures. All four are template/geometry changes that
touch the same code paths and should land together.

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

- **Read `RoomBuilder.buildCell` and `RoomBuilder.buildLiminalCell` first.**
  Every change in this milestone touches one or both of these methods. The
  shell palette, ceiling placement, lamp positions, and lodestone position
  all live here. Understand the current geometry before changing it.
- **The `isShell` check is a pure coordinate test**, not a block-state
  lookup. The shell is defined by position relative to the room origin:
  Y=0 (floor), the wall ring (x=0, x=15, z=0, z=15, Y=1..5), Y=6
  (ceiling), and the four lamp positions. The interior
  (x=1..14, z=1..14, Y=1..5) is the owner's build space. Write it as a
  static method on `RoomProtection` that takes `(BlockPos, BlockPos
  roomOrigin)` and returns boolean.
- **The wall lodestone position is an open decision.** The plan says
  "e.g. x=1, y=2, z=0 on the north wall" but does not commit. Pick a
  position that is visible, reachable for right-click, and does not
  conflict with the selector door wall. The selector doors are on one
  wall; the lodestone should be on an adjacent wall.
- **Structure rotation preserves fixture orientation.** Stairs' `FACING`
  rotates correctly with the template. Verify this against the jar if
  you are unsure; `StructureTemplate`'s rotation handling is in
  `net.minecraft.world.level.levelgen.structure.templatesystem
  .StructureTemplate`.
- **The `entrance_hall` template gets the same changes.** It is the
  room template stamped when no saved room exists. It must match the
  new geometry or the first-run experience will look wrong.
- **The stand-on leave-pad mechanic stays for now.** This milestone
  moves the lodestone block; M21 changes how it is interacted with.
  `isOnRoomLeavePad` needs its position check updated (the lodestone is
  now on the wall, not the floor), but the stand-on trigger itself is
  not deleted until M21.

## What you must not do

- **Do not make the shell immutable to the mod itself.** `RoomBuilder`,
  `RoomTemplateGenerator`, and `Instances.stampLobby` must still be able
  to place and replace shell blocks. The immutability is a player
  protection, not a mod-internal constraint.
- **Do not change the room's 16x16x6 footprint.** The shell is the
  same size; only the block types and the lodestone position change.
- **Do not delete `isOnRoomLeavePad` in this milestone.** It is M21's
  job. This milestone only updates its position reference to match the
  new wall lodestone.
- **Do not add room skins in this milestone.** M24 builds on the
  immutable shell, but the shell swap mechanism is separate work. This
  milestone makes the shell immutable; M24 makes it swappable.

## What this milestone deletes

Nothing. M18 is purely additive and modificative: new block states, new
protection checks, moved positions. No classes or methods are removed.

## Verification bar

**Done when** (from the plan):

1. The owner cannot break the room's walls, floor, or ceiling.
2. The owner can still place and break interior blocks freely.
3. The lodestone is visibly set into the north wall, not on the floor.
4. The selector opening has wooden double doors after a door is chosen.
5. The ceiling has top slabs in the interior and stair-framed lanterns.
6. A captured and re-placed room preserves the new ceiling and fixtures
   at any rotation.

**Live-only:** breaking a wall block (refused), breaking an interior
block (works), opening the double doors, looking up at the ceiling.
Record in `docs/reference/LIVE_TEST_PASS.md`.

**Headless-verifiable:** `isShell` coordinate logic (pure Java test),
`buildCell` output (no compile error, template stamps without crash).

## Doc updates you owe on completion

1. `plans/COMPLETED-MILESTONES.md`: add this milestone's summary in its
   place, in the same architectural-summary style as M0-M17's entries.
2. `docs/reference/LIVE_TEST_PASS.md`: add a numbered section for whatever in this
   milestone is client-interactive and cannot be verified headless.
3. `docs/reference/ROADMAP.md`: this milestone's entry, in order. No checkboxes; the
   roadmap carries order, not status.
4. `docs/reference/ROOM_UX_PLAN.md`: if you diverge from the plan's scope for this
   milestone, **fix the plan first and say so.** Never silently diverge.
5. Rename this file `M18-handoff-completed.md` once the milestone is
   actually landed and its `COMPLETED-MILESTONES.md` entry exists.

## If you get stuck

- **The wall lodestone position conflicts with something.** The selector
  doors are on one wall; the lodestone should be on an adjacent wall, not
  the same one. If every wall has a conflict, consider the wall opposite
  the selector doors (the player faces the doors, the lodestone is behind
  them).
- **Structure rotation breaks the stair fixtures.** Verify
  `StructureTemplate.placeInWorld`'s rotation handling against the jar.
  If stairs do not rotate correctly, use `Mirror`/`Rotation`-aware
  blockstate placement explicitly.
- **The `isShell` check is too aggressive.** Paintings, item frames,
  carpets, wall signs, banners, buttons, and torches are entities or
  sit on the face of a wall, not in the wall. The check must not block
  their placement; only block placement (via `RitualListener`'s
  placement guard) and block breaking (via `RoomProtection`'s break
  guard) are affected.
