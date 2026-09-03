# M40 - Dead code and stale-shipped-defaults cleanup - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/AUDIT_FOLLOWUP_PLAN.md`'s `## M40` section only. It
   lists all sixteen dead members, the harness relocation, and the two
   settled removals (`discoverable`, reward hall/selector room).

## Dependencies

One cross-milestone check before deleting anything: grep
`Diaries.rejections` in `src/main/java/`. If M38's PD-30 fix wired it
into a command, it is no longer dead; skip that line item. Re-verify
every other member with a fresh grep before removing it. The audit was
taken at HEAD 6c68a81 and the tree has moved.

No other gating. `discoverable` and the reward hall/selector room are
both settled as cuts (mod owner, 2026-08-31); nothing in this milestone
waits on M42 anymore.

## Goal

Remove verified-dead members, move the generation test harness out of the
production source set, remove the `discoverable` flag entirely (cut, no
consumer to wire), and remove the reward hall and selector room (cut,
superseded by the final room and the player's own room).

## Implementation plan

Run `build_mod` after each step. Four commits.

1. **Commit 1, dead member removal.** Delete the fourteen members listed
   in the plan doc's 40.1, minus `Diaries.rejections()` if the
   Dependencies check above says it is live. For each, grep
   `src/main/java` and `src/test/java` for the name and confirm zero hits
   outside the declaration before deleting. Also demote
   `Fuel.spend(ServerPlayer, int)` to `private` (one internal caller).

   Watch for false positives the audit already cleared but re-check
   anyway: `GambleStation.onTrade` is an sgui interface override,
   `PocketDungeonsMod.onInitialize` is the `fabric.mod.json` entrypoint,
   and `LayoutGraphGenerator.main` is the `layoutGraphTest` task target.
   None of those three are dead.

2. **Commit 2, harness relocation.** Move `LayoutGraphGenerator.java`
   lines 620-804 (the `main` method plus its 14 `System.out` calls, the
   only ones in the production source set) into
   `src/test/java/pocketdungeons/LayoutGraphGeneratorHarness.java`, or
   fold its assertions into `PipelineProofTest.java` if they duplicate.
   Repoint the `layoutGraphTest` task in `build.gradle.kts` at
   `sourceSets["test"]`.

3. **Commit 3, remove `discoverable`.** Delete the `discoverable` field
   from `DungeonThemeMeta`, its parsing, `ThemeManifest.discoverableIds()`,
   its coverage in `DungeonThemeMetaTest.java`, and the
   `"discoverable": false` line in `dungeon_theme/drowned_vault.json`.

4. **Commit 4, remove the reward hall and selector room.** Delete the
   `reward_hall` and `selector_room` specs from
   `RoomTemplateGenerator.specs()` (the spec-building code, not just the
   identifiers), and `TemplateStamper.REWARD_HALL`/`SELECTOR_ROOM`. Grep
   `reward_hall` and `selector_room` across `src/main/resources` after
   the Java-side removal; delete any structure file or JSON entry that
   existed only to back these two specs.

## Constraints

- Deletions only. If removing a member reveals a live bug, stop, file it
  in `docs/reference/BUGS.md` as a new PD entry, and fix it separately.
- No behavior change in commits 1 and 2. The test suite must be green
  before and after with no assertion edits.
- Commits 3 and 4 remove player-facing generation content (reward hall,
  selector room). Confirm in a live server that a generated dungeon still
  reaches its terminal cell correctly without them before considering
  this milestone done; the final room and player room are the intended
  replacements per the mod owner, but verify no dangling reference to the
  removed rooms remains in the generation pipeline.

## Verification

Run `build_mod` with default `build` after each commit, and confirm
`layoutGraphTest` still runs after commit 2. After commit 4, generate a
dungeon and confirm no missing-template error and no reference to
`reward_hall`/`selector_room` in logs.

Done when: the suite is green, no production source file contains a
`System.out` call, and grepping `discoverable`, `reward_hall`, and
`selector_room` across `src/main/java` and `src/main/resources` returns
nothing.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34. Do not re-read the whole file.

Rename this file to `M40-handoff-completed.md`.
