# M37 - High-severity bug fixes from the audit - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/BUGS.md`: sections `### PD-15` through `### PD-22`.
   Authoritative root cause and fix plan for every item here.
2. `docs/DISCOVERIES.md`: only for PD-17's enchantment exclusive-set and
   curse-tag API, which needs jar verification on 26.2.

## Dependencies

Independent of M36. Both can be in flight; they touch different methods
except `Instances.java`, where M36 edits `generateBehindLobby` and the
`SERVER_STARTED` block and this milestone edits none of them.

Grep `firstCompletion` in `RunLifecycle.java` to confirm PD-15's quoted
block still matches before editing.

## Goal

Close the audit's economy and progression correctness bugs plus the
generation-pipeline reproducibility bugs. Eight items: PD-15 through PD-22.

## Implementation plan

Run `build_mod` after each step. One commit per step.

1. **PD-15**: `RunLifecycle.completeRun`. Move the whole
   `if (record.isKeystoneRun())` bounty block from line 797 down into the
   existing `if (firstCompletion)` block, placed after the
   `completeDungeon(server, record);` call. Fixes both the per-member
   over-count and the always-false `timed` in one move.
2. **PD-16**: two clamps. `DungeonCommands.java:1150`, validate the
   loot-level argument against 1 through `keystoneMaxLevel()` and refuse
   out of range. `DungeonLog.setKeystone` (line 410), clamp instead of
   only flooring, making its existing javadoc true.
3. **PD-17**: `RerollStation.java:162` pool loop. Exclude curses, exclude
   candidates sharing an exclusive set with an enchantment already on the
   item, and decide on treasure-only enchantments. Verify the tag and
   exclusive-set API against the 26.2 jar before writing; the audit did
   not confirm which tag names exist on this version.
4. **PD-18**: `VisitService.visit`, call `statusOf` first and refuse with
   the message it already builds, before `Instances.admit`.
5. **PD-19**: code-side only in this milestone. Confirm
   `RoomManifest.matchesTheme` behaves correctly when both fields are
   null (it does today, it short-circuits true). The content pairing that
   makes the filter actually filter is M42.2, not this milestone.
6. **PD-20**: `LayoutStamper.java:212`, iterate a sorted view instead of
   `plan.cells()` directly.
7. **PD-21**: `DialogRouter.handle`, add `if (player.hasDisconnected())
   return;` as the first statement.
8. **PD-22**: `AdventureGraphs.java:60`, replace the single validation
   pass with the fixpoint loop quoted in the bug entry. Add the
   fixpoint assertion to `AdventureGraphTest.java`.

## Constraints

- PD-15's move must not reorder anything else inside `firstCompletion`.
  `completeDungeon` must still run before the bounty block reads
  `record.rewardChests`.
- PD-17 must not starve the reroll pool. If exclusions empty the pool for
  a normal enchanted item, the station must refuse cleanly, not throw or
  return the input unchanged while charging.
- PD-19 is a no-op-confirming step. Do not author theme or room JSON here.

## Verification

Run `build_mod` with task `adventureGraphTest` after step 8, default
`build` otherwise. Each bug entry has its own `#### Verification` block.
PD-15, PD-20, PD-22 are headless-verifiable; the rest need a live server.

Done when: all eight verification blocks pass and the suite is green.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34. Do not re-read the whole file.

Mark PD-15 through PD-22 `**Status:** Fixed (YYYY-MM-DD)` in
`docs/reference/BUGS.md` and add them to the `## Fixed` summary. PD-19
should be marked partially fixed, noting M42.2 owns the content half.

Rename this file to `M37-handoff-completed.md`.
