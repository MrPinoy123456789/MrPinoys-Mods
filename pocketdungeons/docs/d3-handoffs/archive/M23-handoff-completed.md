# M23 - Room template editor - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `Instances.java` lines 380-460: `stampLobby` and existing shell-stamping
   logic. `buildroom` reuses the shell geometry without the room-store
   capture.
2. `RoomStore.java`: `captureAndSave` method. `saveroom` calls this directly
   with the admin build room's cell origin.
3. `PocketDungeonsCommands.java`: admin command registration pattern. New
   commands register alongside existing `admin` subcommands.

## Dependencies

Grep `captureAndSave` in `src/main/java/`. If method exists in `RoomStore`,
capture path is ready.

Grep `admin` in `PocketDungeonsCommands.java`. If subcommand dispatch exists,
new commands slot in directly.

## Goal

Dev tool for hand-authoring room templates in Minecraft. Build, save to
`.nbt`, let an AI read the file and generate matching code. Two commands,
~30 lines each.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: `/dungeon admin buildroom`

1. Register `buildroom` subcommand in `PocketDungeonsCommands.java`.
2. Handler: stamp empty 16x16x6 shell in dungeon dimension at next available
   slot. Teleport player in. Mark instance as admin build room (tag on
   `InstanceRecord`: `adminBuild = true`).
3. No immutability, no trial spawners, no clock, no keystone. Reuse
   `RoomBuilder.buildShell` for geometry. Skip `RoomStore.place`.

### Step 2: `/dungeon admin saveroom <name>`

1. Register `saveroom` subcommand with one string arg.
2. Handler: call `RoomStore.captureAndSave` with cell origin, name, and
   author from player UUID. Output path:
   `src/main/resources/data/pocketdungeons/structure/rooms/<name>_<author>_<timestamp>.nbt`.
3. Write `pd_author` and `pd_saved_at` NBT tags on the captured structure.
4. Teleport player back to overworld spawn. Tear down the admin instance.

## Constraints

- Admin commands only. No permission bypass: op-or-higher, same as existing
  `admin` subcommands.
- No immutability on build room. Player can break/place freely inside shell.
- No trial spawners, no loot, no completion pad. This is a build tool, not a
  dungeon.
- One admin build room per player. Starting `buildroom` while one exists
  tears down the old one.

## Verification

- `build_mod` default `build` after each step.
- Headless: commands register, handler compiles.
- Live: `buildroom` teleports into empty shell, player builds,
  `saveroom myroom` writes `.nbt` to resources dir. Record in
  `LIVE_TEST_PASS.md`.
- Done when: `saveroom` produces a `.nbt` file loadable by the existing
  room manifest system.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M23-handoff-completed.md` once landed.
