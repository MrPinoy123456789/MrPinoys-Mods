# M44 - Test coverage for world-mutating and economy classes - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/AUDIT_FOLLOWUP_PLAN.md`'s `## M44` section only. Six
   subsections naming what to test in each class.
2. `pocketdungeons/build.gradle.kts`: the `JavaExec` task registrations
   and the `tasks.test` `dependsOn` list. Every new test file needs a task
   added there; there is no JUnit dependency in this project.

## Dependencies

**M36 through M39 must land first.** New tests should assert post-fix
behavior; writing them against the buggy behavior means rewriting them
immediately. Grep `docs/d3-handoffs/` for `M36-handoff-completed.md`
through `M39-handoff-completed.md` and stop if any is missing.

## Goal

Cover the highest-risk classes the audit found completely untested. The
existing split is deliberate and lopsided: every pure-math helper is well
covered, every class that touches the world, the filesystem, or a player's
inventory has no test at all. Four of the audit's six critical bugs were
in that untested set.

## Implementation plan

Run `build_mod` after each subsection. One commit per test file. Follow
the existing test convention: a `main(String[])` throwing `AssertionError`,
not JUnit.

1. **44.1, `RoomStoreTest`.** The only persistence path for a player's
   built room; four `IOException` catch blocks, zero coverage. Test the
   capture and restore round-trip preserves block state, a corrupted save
   file is caught and logged rather than thrown, and `restoreFromBackup`
   actually restores. Note: `restoreFromBackup` has no caller in `main`;
   confirm during this step whether it should have one or is dead.

2. **44.2, `FuelTest`.** Test `grant` delivers through `Payout.deliver`
   (overflow drops at feet, never voids), `bank` moves inventory count to
   the banked balance, `spendBanked` cannot go negative, and, post-M36, a
   stack carrying foreign custom data does not count as fuel.

3. **44.3, station tests.** One file per station or one shared file,
   whichever fits the existing convention better. Test that each station's
   wiring calls the already-tested math correctly, that the unlock-level
   gate refuses below its threshold (post-M38), and that a spend either
   succeeds fully or refuses fully with no partial-charge state.

4. **44.4, `PayoutTest`.** Runs an arbitrary console command on
   completion, currently zero coverage of command construction. Test the
   payout command template substitutes correctly and
   `Payout.deliver`'s inventory-overflow-to-feet behavior works.

5. **44.5, `InstanceTeardownTest`.** Test the per-tick clear budget
   actually bounds work per tick (not merely that it finishes), and that
   `purge`/`retireOrPurge` take the correct branch for each of: fresh
   lingering record, re-entered lingering record (PD-10's case),
   non-keystone run, keystone run with no room yet.

6. **44.6, fix `Pocket2Test.java`.** It never references `Pocket2`; it
   tests `InstanceRecord` and `InstanceRegistry.allocateSlotNear`, while
   `Pocket2.java`'s 508 lines of child-instance entry, tick, and death
   have zero coverage. Either rename the file to what it actually tests
   and write a real `Pocket2Test`, or expand it in place.

## Constraints

- No JUnit. Match the existing pure-Java pattern; the project deliberately
  has no test framework dependency.
- Every new test file needs its own `JavaExec` task in `build.gradle.kts`
  plus an entry in `tasks.test`'s `dependsOn`. A test not wired into a
  task never runs.
- Tests that need a live `ServerLevel` are out of scope. Test the logic
  that can be isolated; if a class cannot be tested without a server,
  note that in the commit rather than building a mock world.

## Verification

Run `build_mod` with default `build`; all tasks including the new ones
must pass.

For each new test, confirm it would have caught the bug the audit found in
that class: temporarily revert the relevant M36 through M39 fix and
confirm the new test fails, then restore the fix. This is the actual
done-when; a test that passes against both the buggy and fixed code is not
covering the thing it claims to cover.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34, listing which classes gained coverage and which
remain untested by design (the live-server-only ones).

Rename this file to `M44-handoff-completed.md`.
