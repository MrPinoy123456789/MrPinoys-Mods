# M21 - UX consolidation: one lodestone, one menu - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/ROOM_UX_PLAN.md`'s `## M21: UX consolidation`
   section: **the authoritative scope.** Goal, dependencies, scope, touch
   points, done-when, and verification notes all live there. This handoff
   does not repeat them.
3. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific): toolchain, commands,
   the one-mixin budget, conventions, and the resource-directory layout.
4. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
5. `pocketdungeons/docs/DOOR_LADDER_BRAINSTORM.md` section 11: the design
   rationale for the menu consolidation.
6. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M17 actually
   built, for context on what this milestone extends and deletes.

## Before you start: confirm the dependencies are actually done

**Hard dependencies on M18, M19, and M20.** Check
`plans/COMPLETED-MILESTONES.md` for entries on all three, and confirm:

- **M18:** The wall lodestone exists and is the room's terminal surface.
  The menu opens on right-click of the wall lodestone.
- **M19:** Physical door selection is in place. The menu does not need
  to duplicate door selection; the doors, bulbs, and lever handle that.
  The menu's "Start Dungeon" option is for the overworld case (right-
  click any lodestone with a keystone), not for in-dungeon door
  selection.
- **M20:** The lobby browser exists as a dialog. The menu's "Browse
  Lobbies" option opens it. Without M20, the menu has a dead button.

This milestone is the capstone of the UX chain: it consolidates
interactions from M18 (wall lodestone), M19 (door selection context),
and M20 (lobby browser) into a single menu. All three must be real
before starting.

## Goal, in one line

Five distinct lodestone interactions collapse into one right-click menu
on the wall lodestone. The stand-on leave-pad is deleted. The
`hasInstance` guard inverts: right-clicking while in a dungeon opens
the in-dungeon menu instead of blocking.

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

- **Study `DialogScreens.partyRoster` and M20's `lobbyBrowser` before
  writing `lodestoneMenu`.** The menu is a `MultiActionDialog` whose
  contents depend on context (overworld vs in-dungeon). The pattern is
  the same as the party roster and lobby browser: build a list of
  buttons, each carrying an action id in its payload, send it as a
  dialog.
- **The `hasInstance` guard in `RitualListener.onUseBlock` inverts.**
  Today, right-clicking a lodestone while in a dungeon is blocked
  (the player is told to use `/dungeon exit` or step on the leave
  pad). After this milestone, right-clicking the wall lodestone while
  in a dungeon opens the in-dungeon menu (Leave, Manage Room, Inspect
  Keystone). The guard changes from "refuse if hasInstance" to "show
  in-dungeon menu if hasInstance."
- **The keystone is checked when "Start Dungeon" is clicked, not when
  the menu opens.** A player who just wants to leave or browse lobbies
  should never need to find their compass first. The menu opens on any
  right-click of the wall lodestone, with or without a keystone in
  hand. The "Start Dungeon" button checks for the keystone and refuses
  with a chat message if it is missing.
- **The terminal pad at the dungeon's end stays as a stand-on
  mechanic.** It is the completion trigger, not a navigation surface.
  It is hard to trigger accidentally (it is at the end of the dungeon,
  not in the room where the player is looting). Only the room's leave
  pad is deleted; the dungeon's terminal pad stays.
- **`/dungeon` commands stay as power-user shortcuts.** The menu is
  the primary interface; the commands are for operators and players
  who prefer typing. Both paths call the same underlying methods.
- **The menu's context detection is `player.level().dimension()
  .equals(PocketDungeonsMod.DUNGEON_LEVEL)`.** This is the same check
  `RoomProtection` already uses. If the player is in the dungeon
  dimension, show the in-dungeon menu; otherwise show the overworld
  menu.

## What you must not do

- **Do not delete the terminal pad at the dungeon's end.** Only the
  room's leave pad (`isOnRoomLeavePad`) is deleted. The terminal pad
  (`isOnExitPad`) is the completion trigger and stays.
- **Do not put door selection in the menu.** The doors, bulbs, and
  lever (M19) handle door selection. The menu is for mod navigation
  (start, leave, browse, manage, inspect), not run selection.
- **Do not require a keystone to open the menu.** The menu opens on
  any right-click of the wall lodestone. The keystone is only checked
  when "Start Dungeon" is clicked. A player without a keystone can
  still browse lobbies, manage their room, or leave a dungeon.
- **Do not remove `/dungeon` commands.** They stay as power-user
  shortcuts. Both the menu and the commands call the same methods.

## What this milestone deletes

- **`Instances.isOnRoomLeavePad`**: the method that checks whether the
  player is standing on the room's leave pad. The wall lodestone is
  now a right-click terminal, not a step-on trigger.
- **The leave-pad watcher branch in `Instances`**: the watcher logic
  that detects a player standing on the leave pad and calls
  `RunLifecycle.exit`. Replaced by the menu's "Leave" button calling
  `RunLifecycle.exit` directly.
- **The `hasInstance` refusal in `RitualListener.onUseBlock`**: the
  guard that blocks right-clicking a lodestone while in a dungeon.
  Replaced by the in-dungeon menu.

**What stays:**

- `Instances.isOnExitPad`: the terminal pad check at the dungeon's
  end. Unchanged.
- `RunLifecycle.exit`: the exit method, callable from the menu's
  "Leave" button instead of the pad watcher.
- `RunLifecycle.completeRun`: the completion method, triggered by the
  terminal pad. Unchanged.
- All `/dungeon` commands: unchanged, still callable.

## Verification bar

**Done when** (from the plan):

1. Right-click the wall lodestone in the overworld with an empty hand:
   the menu opens with Start, Browse, Manage, Inspect options.
2. Right-click the wall lodestone in a dungeon: the menu opens with
   Leave, Manage, Inspect options.
3. Clicking "Leave" exits the dungeon. No stand-on pad needed.
4. Walking over the old leave-pad position does nothing.
5. The terminal pad at the dungeon's end still works (stand-on
   completion trigger).

**Live-only:** all of it. The menu dialog, the context-dependent
contents, the button dispatches. Record in `docs/LIVE_TEST_PASS.md`.

**Headless-verifiable:** context detection logic (dimension check),
menu button construction (pure Java test), `RunLifecycle.exit` call
path (no compile error, logic correct).

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
6. Rename this file `M21-handoff-completed.md` once the milestone is
   actually landed and its `COMPLETED-MILESTONES.md` entry exists.

## If you get stuck

- **The menu has too many options for one `MultiActionDialog`.** The
  overworld menu has four options (Start, Browse, Manage, Inspect);
  the in-dungeon menu has three (Leave, Manage, Inspect). Both are
  well within the button limit. If you find yourself adding more
  options, reconsider whether they belong in the menu or in a
  sub-dialog.
- **Deleting `isOnRoomLeavePad` breaks the watcher.** The watcher has
  multiple branches (leave pad, timer, keystone reconciliation). Only
  the leave-pad branch is deleted; the other branches stay. Read the
  watcher's full structure before removing anything.
- **The `hasInstance` guard inversion causes unexpected behaviour.**
  The guard currently blocks all lodestone right-clicks while in a
  dungeon. Inverting it means the in-dungeon menu opens on every
  lodestone right-click, including the terminal pad's lodestone. If
  this is a problem, add a position check: the in-dungeon menu opens
  only on the wall lodestone (the room's terminal), not on the
  terminal pad's lodestone (which is the completion trigger).
- **"Manage Room" needs different options depending on context.** In
  the overworld, it shows whitelist, name, public/private. In a
  dungeon (post-completion, standing in your own room), it shows the
  same options. If the player is in someone else's room, "Manage
  Room" should not appear. Check `record.owner.equals(player.getUUID())`
  before adding the option.
