# rm-t01: PD-218 window bands dropped on room edges

Branch: feature/rm-t01-window-bands (off feature/backlog-2026-10-19).

## The rule
`LayoutStamper.applyWindowBand` suppresses a whole band when exactly one of the two rooms on an edge resolves to window `none`, and logs "Window band suppressed ... disagree". The rule is correct and unchanged.

## Cause
A room with no `window` field defaults to `bars`. Every one of the 18 Act 0 rooms of `first_night` (treeline, stone_rise, black_seam, rust_ravine: run, bend, fork, nook; cave_mouth: run, nook) set `none`, while the shared rooms they can neighbour (hall_cross_rotunda, exit_hall, rotation_lock and the other halls, about 60 rooms eligible for any act 0 floor) use bars. The six logged edges are samples of that.

## Fix (data only)
- The 18 Act 0 manifests: `"window": "none"` to `"window": "bars"`. More than the six named rooms, because the siblings meet the same shared halls.
- `rotation_lock` already says `bars`; no edit needed.
- Test `WindowBandAgreementTest` (pure, wired in build.gradle.kts): for each dungeon builds the pool via `RoomEligibility.themeEligible` (superset of the planner's pool) and fails on any pool holding both a `none` room and a material room. It failed with 1238 pairs before the data fix, passes after (26 dungeons, 167 rooms).
- BUGS.md PD-218 Status filled in.

## Left open, on purpose
`admirals_vault` (sunken_treasury) and `seam_heart` (gilded_seam) are final floor vaults with `none` beside the shared halls; same bug class, outside this task's ownership. They sit in the test's `KNOWN_OPEN` set, which also fails if an entry stops being `none`, so the list only shrinks. `the_proving` (kind proving, one fixed room per floor) is skipped.

## Tests
`tools/ship.sh --pure-fast`: 129 pure tasks green (128 before plus the new one). Full `--quick` not run (data and pure test only).

## Live
Only a server can confirm that bars read well on the outdoor Act 0 rooms and that the log no longer prints the suppression warning on Act 0 floors.
