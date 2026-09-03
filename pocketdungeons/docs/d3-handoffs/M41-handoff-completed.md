# M41 - Documentation drift correction - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/AUDIT_FOLLOWUP_PLAN.md`'s `## M41` section only. It
   lists all six subsections with the exact wrong claims and what each
   should say instead.

## Dependencies

Should run after M42 if M42's content decisions land, since 41.2 and 41.3
describe features M42.2 through M42.4 may change. Grep
`docs/d3-handoffs/` for `M42-handoff-completed.md`; if present, read
M42's `COMPLETED-MILESTONES.md` entry before writing 41.3.

Otherwise independent. No source dependency.

## Goal

Make every top-level and reference doc agree with what is actually built.
Pure documentation; this milestone changes no source file.

## Implementation plan

Run `build_mod` once at the end (nothing should change, this is a check
that no doc edit touched a source file). Six commits, one per subsection.

1. **41.1, scoreboard removal.** The `pd_task`/`pd_bounty` scoreboard,
   tab-list column, and `syncScoreboard`/`clearScoreboard` methods do not
   exist; grep confirms only four comments saying the feature was removed.
   Rewrite the display-surface description in `COMPLETED-MILESTONES.md`'s
   M33 and M34 sections to match `DungeonScreen.trackerContent`. Same
   correction in `M33-handoff-completed.md`,
   `M34-handoff-completed.md`, `ROADMAP.md`, and `ROOM_UX_PLAN.md`.
   The task-progress hooks all survive and are correct; only the display
   surface changed. Do not describe the hooks as removed.

2. **41.2, `README.md`.** Three stale claims: the completed-milestone
   range, the active-handoff pointer (points at an archived M22 file),
   and the `INTEGRATION.md` schema claim.

3. **41.3, `docs/INTEGRATION.md`.** Four fixes: the affix class name and
   constant list (it is top-level `pocketdungeons.Affix` with 8+, not
   `Keystone.Affix` with 3), the `ritualKeyItem` claim (never read; M36
   removes the key), the missing `theme` field in section 2's schema
   table, and the four undocumented datapack surfaces.

4. **41.4, handoff renames and missing entries.** Rename
   `M29-handoff.md`, `M31-handoff.md`, `M35-handoff.md` to
   `-completed.md`. Add `COMPLETED-MILESTONES.md` sections for M31 and
   M35 (M29 already has one). Reorder so M32's entry follows M30's.

5. **41.5, `docs/DIALOGS_SPEC.md`.** The status header is wrong in both
   directions: section 7 shipped, section 1 did not ship in the form
   described (M19 superseded it). Fix the broken `DIALOGS.md` link.

6. **41.6, `plans/STATION_PICKER_PLAN.md`.** Section 7 contradicts the
   "Resolved decisions" section on visitor access. The code implements
   owner-only, which is correct, so section 7 is the stale half. Also
   rename "Cube" to "Herobrine Cube" to match player-facing text.

## Constraints

- No source file changes. If a doc fix reveals the code is wrong rather
  than the doc, stop and file a `BUGS.md` entry instead of editing source.
- Superseded designs are marked superseded, not deleted (standing rule 6
  in `ROADMAP.md`). 41.1 and 41.5 both describe superseded designs; note
  what replaced them rather than removing the history.
- `docs/` at the top level stays under 50 KB total. 41.3 adds four
  surface entries; keep them short.

## Verification

Re-read each edited doc against a fresh grep of the source it describes.
Specifically confirm: no doc still names `syncScoreboard`, `pd_task`,
`pd_bounty`, `sendDoorOffer`, `Keystone.Affix`, or `dungeon_recipe` as a
live thing.

Done when: every claim in the six subsections matches the current tree.

## Completion

Append a short entry to `plans/COMPLETED-MILESTONES.md` noting this was a
documentation-only pass and listing which docs were corrected. Do not
re-read the whole file.

Rename this file to `M41-handoff-completed.md`.
