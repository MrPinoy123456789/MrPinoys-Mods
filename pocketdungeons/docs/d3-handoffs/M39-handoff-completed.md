# M39 - Low-severity fixes and config validation gaps - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/BUGS.md`: sections `### PD-36` through `### PD-47`.
   Authoritative root cause and fix plan for every item here.

## Dependencies

Independent of M36 through M38. Nothing here blocks or is blocked.

Grep `pathLengthMax` in `RoomSelector.validate` before PD-46: the exact
relationship between path length and grid span must be read from that
method, not assumed. The bug entry says so; it does not give the formula.

## Goal

Close the remaining small correctness bugs and the three config
cross-field validation gaps. Twelve items: PD-36 through PD-47. Batch
these; none is urgent alone.

## Implementation plan

Run `build_mod` after each step. Group into four commits as marked.

1. **Commit 1, message and output correctness.**
   - PD-36: `DungeonCommands.java:1150`, echo
     `AffixMath.join(AffixMath.parse(affixes))` instead of the raw string.
   - PD-37: strip formatting-code characters from room names in both
     `DungeonCommands.java:623` and `DialogRouter.java:228`, or restrict
     to an allowlist. Same rule in both paths.
   - PD-38: make `RunLifecycle.exit` and the six `PartyService` methods
     return `boolean`, and have each command executor return 1 or 0 from
     it. Touches several signatures; update every call site.

2. **Commit 2, resource and data handling.**
   - PD-39: `DungeonCommands.java:803`, add the matching
     `setChunkForced(..., false)` release.
   - PD-40: `GambleStation.java:314`, loop the rolled list or log a
     warning when more than one stack rolls. State which in the commit.
   - PD-41: `Keystone.reconcile`, either preserve the stack count when
     minting or validate max-stack-size 1 at config load. State which.
   - PD-42: `PayoutMath.java:33`, widen to long before multiplying. Add
     the large-value assertion to `PayoutMathTest.java`.

3. **Commit 3, unbounded maps.**
   - PD-43: register a disconnect handler in `TrimListener` and
     `PowerListener` that removes the player's UUID from the static map.
   - PD-44: add a timestamp to `pendingReturns` entries and sweep expired
     ones in the existing tick watcher pass. No new scheduled task.

4. **Commit 4, config validation.**
   - PD-45: `PocketDungeonsConfig.java:685`, raise `inviteTtlSeconds`
     lower bound to 1.
   - PD-46: cross-check `pathLengthMax` against `maxGridSpan`, clamp and
     warn, following the existing `pathLengthMin`/`pathLengthMax` repair
     pattern in the same method.
   - PD-47: warn (do not refuse) when `keystoneMaxLevel` is below
     `greaterDoorMinLevel`, `cubeUnlockLevel`, `gambleUnlockLevel`, or
     `rerollUnlockLevel`, naming which gate becomes unreachable.

## Constraints

- PD-38 changes public method signatures. If M43.2 (detach primitive) is
  in flight, coordinate: both touch `PartyService` and `RunLifecycle`.
- PD-47 warns rather than refuses. A low-level-cap server is a legitimate
  configuration; the operator just needs to know what it disables.
- PD-41 and PD-40 each have two acceptable fixes. Pick one, state why.

## Verification

Run `build_mod` with task `payoutMathTest` after commit 2, default `build`
otherwise. Each bug entry has its own `#### Verification` block. PD-42 and
the three config items are headless-verifiable; the rest need a live
server or code review.

Done when: all twelve verification blocks pass and the suite is green.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34. Do not re-read the whole file.

Mark PD-36 through PD-47 `**Status:** Fixed (YYYY-MM-DD)` in
`docs/reference/BUGS.md` and add them to the `## Fixed` summary.

Rename this file to `M39-handoff-completed.md`.
