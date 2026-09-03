# M38 - Medium-severity bug fixes from the audit - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/BUGS.md`: sections `### PD-23` through `### PD-35`.
   Authoritative root cause and fix plan for every item here.
2. `plans/STATION_PICKER_PLAN.md`: the "Resolved decisions" section only,
   for PD-23's unlock-level intent. Ignore its section 7, which is stale
   (M41.6 fixes it).

## Dependencies

Independent of M36 and M37.

If M43.7 (shared station shape) has already landed, PD-23 and PD-25 become
one edit in the shared base instead of two per station. Grep for
`StationHandler` in `src/main/java/pocketdungeons/`; if absent, do the
per-station edits described below.

## Goal

Close the audit's gate-bypass, task-tracking, and operator-tooling bugs.
Thirteen items: PD-23 through PD-35.

## Implementation plan

Run `build_mod` after each step. Group into commits as marked.

1. **PD-23 then PD-24** (one commit, in this order). Add the unlock-level
   check `RerollStation.onUse` already has to `GambleStation.onUse` and
   `CubeStation.onUse`. Then re-check the same level in the dialog
   handlers (`RerollStation.handleReroll` and the gamble/cube
   equivalents), since the dialog path is a second entry point into the
   same action.
2. **PD-25** (one commit). Move `TaskTracker.progress` in
   `RerollStation.java:121` to `handleReroll` after the lapis spend, and
   in `GambleStation.java:114` to `handleTrade` after the emerald debit.
3. **PD-26** (one commit). `Instances.rescue`, replace the hand-rolled
   detach block with a `RunLifecycle.dropMember` call so the leadership
   check runs. Keep the returned `ReturnPoint` rather than discarding it.
4. **PD-27 then PD-28** (one commit, both iron-door). PD-27: pick option 1
   or option 2 from the bug entry and state which in the commit message.
   PD-28: alternate the hinge and fix the facing convention in
   `ConnectorStamper.applyIronDoor`.
5. **PD-29 then PD-30** (one commit, both manifest reload). PD-30 first:
   register `Diaries.load` against the datapack reload listener following
   `ThemeManifest.java:50`'s pattern. Then PD-29: make
   `DungeonCommands.manifestReload` call all five loaders and report all
   five counts.
6. **PD-31, PD-32** (no code here). Both are content decisions owned by
   M42.3 and M42.4. Confirm the code-side fallback behaves (silent
   fallback, no crash) and move on. Do not author JSON in this milestone.
7. **PD-33** (one commit). `CubeStation.java:143`, swap the two lines so
   the state write precedes `held.shrink(1)`.
8. **PD-34** (one commit). `InstanceTeardown.purge`, null
   `record.roomCellOrigin` after the synchronous save so `eject`'s
   deferred `saveRoomIfOwner` no-ops via its existing null guard.
9. **PD-35** (one commit). `DialogScreens.lobbyBrowser`, cap rows at 8 and
   append an overflow line. Leave the zero-row path unchanged.

## Constraints

- PD-27's two options are not equivalent. Option 2 (lever on the frame)
  changes what every iron-door connector looks like; option 1 changes
  generation. State the choice and its reason in the commit.
- PD-34 depends on M36's PD-11 not having already restructured `purge`.
  If M36 landed, re-read `purge` before editing.
- Do not author theme, adventure, or loot JSON in this milestone.

## Verification

Run `build_mod` with default `build`. Each bug entry has its own
`#### Verification` block. PD-28, PD-33, PD-34 are code-review or headless;
the rest need a live server.

Done when: all eleven implemented items' verification blocks pass (PD-31
and PD-32 are deferred to M42, not verified here) and the suite is green.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34. Do not re-read the whole file.

Mark PD-23 through PD-30 and PD-33 through PD-35 `**Status:** Fixed
(YYYY-MM-DD)` in `docs/reference/BUGS.md` and add to the `## Fixed`
summary. Leave PD-31 and PD-32 open, noting M42 owns them.

Rename this file to `M38-handoff-completed.md`.
