# M36 - Critical bug fixes from the audit - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `pocketdungeons/CONVENTIONS.md` (first milestone in this round)
2. `docs/reference/BUGS.md`: sections `### PD-9` through `### PD-14`, and
   `### PD-48`. These carry the authoritative root cause and fix plan for
   every item here. Do not re-investigate; implement what they specify.
3. `docs/DISCOVERIES.md`: only if PD-13/PD-14 need `setChunkForced` or
   forced-chunk saved-data API details not already in the bug entries.

## Dependencies

None. This is the first audit-follow-up milestone.

Grep `retireOrPurge` in `src/main/java/pocketdungeons/InstanceTeardown.java`
to confirm the lingering branch still matches PD-10's quoted code before
editing; the audit was taken at HEAD 6c68a81.

## Goal

Close every audit finding that crashes a player, destroys a player's room,
or leaks a resource without bound. Seven bugs: PD-9 through PD-14, PD-48.

## Implementation plan

Run `build_mod` after each step. One commit per step.

1. **PD-9**: `DungeonCommands.java:489`, change
   `Keystone.findHeld(player).isEmpty()` to `== null`. One line.
2. **PD-10**: `InstanceTeardown.retireOrPurge`, add the
   `if (record.lingering) { purge(...); return; }` early branch per the
   bug entry. Do this before steps 3 and 6: both reason about the same
   purge path.
3. **PD-11**: `Instances.java:666` catch block. Stop clearing
   `record.roomCellOrigin` (pass `record.origin`, not `planOrigin`), and
   remove the record from `InstanceRegistry.bySlot` so the slot is not
   double-freed. Prefer routing through `InstanceTeardown.purge`.
4. **PD-12**: `RunLifecycle.java:542`, wrap the whole DISCONNECT handler
   body in `server.execute`, moving the body into a new
   `handleDisconnect(server, player)`. The existing inner `server.execute`
   around the room capture becomes redundant; remove it.
5. **PD-13**: `Instances.generateBehindLobby`, capture the previous
   layout's chunks before the new layout is built, release them with
   `forceLoad(level, oldChunks, false)`, then force-load the new set.
6. **PD-14**: startup reconciliation pass on `SERVER_STARTED`
   (`Instances.java:216`). Largest step; see PD-14's three numbered
   sub-parts. Reuse `processClears`'s per-tick budget, do not block
   startup.
7. **PD-48**: three parts per the bug entry. Marker-based fuel matching in
   `Fuel.isFuel`/`count`/`spend`; remove echo shards from
   `chests/anomaly.json` pool 0 and `chests/pocket2.json`; delete the dead
   `ritualKeyItem`/`ritualKeyCount` keys from
   `config/pocketdungeons.default.json`.

## Constraints

- PD-48 part 1 must not break existing banked balances. Players holding
  pre-fix echo shards should still be able to bank them, or the fix needs
  a migration note in the commit message. Decide and state which.
- PD-14 runs before any player can enter a dungeon. Do not let a
  reconciliation failure prevent server start; log and continue.
- Do not fix PD-15 through PD-47 here. They are M37 through M39.

## Verification

Run `build_mod` with default `build`. Each bug entry in `BUGS.md` has its
own `#### Verification` block; PD-9, PD-10, PD-13 are headless-verifiable,
PD-11, PD-12, PD-14, PD-48 need a live server.

Done when: all seven verification blocks pass and the full test suite is
green.

## Completion

Append this milestone's architectural summary to
`plans/COMPLETED-MILESTONES.md` in the same style as M30 through M34's
entries. Do not re-read the whole file.

In `docs/reference/BUGS.md`, change each of PD-9 through PD-14 and PD-48
from `**Status:** Open` to `**Status:** Fixed (YYYY-MM-DD)` and add them
to the `## Fixed` summary near the top.

Rename this file to `M36-handoff-completed.md`.
