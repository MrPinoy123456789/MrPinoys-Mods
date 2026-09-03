# M43 - Refactor backlog from the audit - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/AUDIT_FOLLOWUP_PLAN.md`'s `## M43` section only. All
   eight subsections with their file and method references.

## Dependencies

**M36 through M39 should land first**, and 43.1 and 43.2 hard-depend on
it. Both touch the exact fields and methods those milestones fix
(`InstanceRecord`'s per-run fields, the detach and purge paths).
Refactoring around a bug before it is fixed risks re-introducing it in a
new shape.

Grep `docs/d3-handoffs/` for `M36-handoff-completed.md` through
`M39-handoff-completed.md`. If any is missing, do 43.3 through 43.8 only
and leave 43.1 and 43.2 for a later session.

## Goal

Address the structural seams the audit flagged as the root cause of more
than one bug, or as a source of silent drift between two implementations
of the same rule.

## Implementation plan

Run `build_mod` after each subsection. One commit per subsection, eight
total. Order below is by payoff; 43.3 and 43.4 are natural to do together.

1. **43.1, split per-run state out of `InstanceRecord`.** Extract the
   fourteen door-choice-reset fields into a `RunState` the door choice
   replaces wholesale, instead of the field-by-field reset block in
   `Instances.generateBehindLobby`. A forgotten field becomes a compile
   error rather than a carry-over bug (this is PD-18's root cause).

2. **43.2, one member-detach primitive.** `Instances.eject`,
   `Instances.rescue`, `RunLifecycle.dropMember`, and the offline branches
   in `InstanceTeardown.purge`/`retireOrPurge` are four partial copies
   with different coverage of save, pad, timer, omen, and the leadership
   check. Consolidate into one `detach(server, record, member, reason,
   boolean teleport)`.

3. **43.3, one `occupiedCells` set per instance.** The footprint is
   reconstructed three different ways and the room lives outside
   `layout.geometry()` after completion, which is PD-13's root cause. Add
   a single `Set<BlockPos>` on the record, updated when the footprint
   changes, read by teardown, force-load release, and the void guard.

4. **43.4, indexed spatial lookup.** Add a `Map<ChunkPos, InstanceRecord>`
   alongside `InstanceRegistry.bySlot` (natural to maintain alongside
   43.3) so `roomRecordAt`, `dungeonCellLookupAt`, and `instanceAt` stop
   doing independent linear scans. A single block break currently costs up
   to four full scans of every live instance.

5. **43.5, one datapack-loader helper.** `Diaries.load`,
   `AdventureGraphs.load`, `ThemeManifest.load`, `RoomManifest.loadFrom`
   repeat the same 20 lines. Extract a generic `JsonPackLoader<T>`, and
   move `baseName` (copy-pasted three times plus a fourth inline variant
   with an extra rule) and `requiredString` (four inconsistent versions)
   into it.

6. **43.6, wither methods on `DungeonLog.Entry`.** Fourteen mutators each
   restate all eighteen record components to change one field, roughly
   300 of the file's 738 lines. Add `withX(...)` methods or a private
   `mutate(UUID, UnaryOperator<Entry>)`. Leave the codec half alone; it
   is already well factored.

7. **43.7, shared station shape.** Extract a `StationHandler` base whose
   `onUse` template checks `unlockLevel()` before dispatching, so the
   omissions behind PD-23 and PD-25 become unwritable. Fold in the
   block-matching boilerplate and the three different custom-data readers.

8. **43.8, deduplicate geometry and facing helpers.** Four copies of
   "world position on a cell wall", five copies of the
   wall-to-opposite-facing switch in one file when `CellGeometry.opposite`
   exists, and a verbatim duplicate between `Instances.stampLobby` and
   `VisitService.createVisitInstance` whose own copied comment warns the
   two "must not drift apart".

## Constraints

- **No observable behavior change.** These are structural moves. If a
  refactor reveals it would also fix a live bug, stop, file a new
  `BUGS.md` entry, and land the behavior fix as a separate commit.
- One subsection per commit. Do not batch; each must be revertible alone.
- The full suite must be green after every commit, with no assertion
  edits. If a test needs changing, the refactor changed behavior; see the
  first constraint.

## Verification

Run `build_mod` with default `build` after each subsection. The existing
suite is the verification: 27 test files must pass unchanged.

Done when: all eight subsections are committed, the suite is green with no
assertion edits, and no subsection changed player-observable behavior.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34, noting which bugs each seam was the root cause
of so a future reader sees why these were worth doing.

Rename this file to `M43-handoff-completed.md`.
