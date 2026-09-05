# M62 - Establish executable acceptance - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `pocketdungeons/CONVENTIONS.md`, all sections. First milestone in a new round; re-read.
- `docs/DISCOVERIES.md` traps 1 to 4 and 18 to 21. Trap 18: mock-player removal warning. Trap 19: SavedData binding warning. Both decide which test adapters are safe.
- `docs/reference/LIVE_TEST_PASS.md` sections 24, 26 and 35 only. Obsolete rows to dispose, not re-pass.
- `src/gametest/java/pocketdungeons/gametest/InventorySwapGameTest.java`, fixture setup and dimension substitution. Existing harness to extend, not replace.

## Dependencies

```bash
grep -n "spanY\|STORY_HEIGHT" pocketdungeons/src/main/java/pocketdungeons/RoomGeometry.java
grep -n "configureTests" pocketdungeons/build.gradle.kts
grep -n "InventorySwapGameTest" pocketdungeons/src/gametest/resources/fabric.mod.json
grep -rn "RisingLavaHandler\|CollapsingBridgeHandler" pocketdungeons/src/main/java/pocketdungeons/
```

First confirms M61 `spanY` in working tree. Second confirms `configureTests` enables server gametests, disables client. Third confirms existing registered class. Fourth confirms both room handlers exist. If any absent, stop and resolve.

## Goal

A failing real-world regression must be distinguishable from an untested design assertion. M62 proves runner plumbing only; M63 supplies custody and fault scenarios.

## Implementation plan

Run `build_mod` after each step.

1. **Acceptance index.** Append-only index covering every existing `LIVE_TEST_PASS.md` subsection plus M45 to M61 omissions. Each row: `current`, `superseded` with successor check, `passed` with evidence, or `blocked`. Record production save locations from the running fixture, not old checklist guesses. Store as new appendix at end of `docs/reference/LIVE_TEST_PASS.md`; do not re-read the whole file. Add missing rows for: floor bank timing, recipes, frozen preview membership, post-selection tool depletion, return paths, room preservation, silence about room movement.
2. **Test fixtures.** Add `src/gametest/java/pocketdungeons/gametest/DungeonTestFixtures.java`: bound `ItemStack` builders, controllable save/stamp failure injection, guaranteed cleanup hooks. New GameTest classes may use package `pocketdungeons` to reach package-private production methods; register full class names explicitly in `src/gametest/resources/fabric.mod.json`.
3. **Integration entrypoint.** Add `src/gametest/java/pocketdungeons/gametest/DungeonIntegrationEntrypoint.java` implementing `ModInitializer.onInitialize`. Launch isolated dedicated server with bundled datapack dimensions, validate `server.getLevel(PocketDungeons.DUNGEON_LEVEL)` non-null, smoke-test save/restart round trip. Explicit scratch world, bounded timeouts, non-zero exit on failure. No world-creation mixin. Reconfirm `makeMockServerPlayerInLevel` deprecation and normal-server dimension loading against 26.2 jar before choosing adapters; mark unresolved `UNVERIFIED` in source.
4. **Gradle task.** Add `dungeonIntegrationTest` to `build.gradle.kts` as `JavaExec` launching the integration entrypoint on an isolated dedicated server. Supplements `runGameTest`; never claims to drive a human GUI. Register so `build` depends on it alongside existing `JavaExec` tasks. Pin test discovery by expected IDs and count.
5. **Break one assertion.** Temporarily invert one new assertion in `DungeonIntegrationEntrypoint` to demonstrate execution and failure. Restore before commit. Proof that a failing regression is distinguishable from an untested assertion.

## Constraints

- Production jar contains no test entrypoint. Test classes in `src/gametest/` only.
- No world-creation mixin. Failure to load true `pocketdungeons:void` blocks the task; not permission to use Nether substitution again.
- Corruption tests on disposable copies with explicit approval. M62 does not run them; M63 does.
- Do not rebuild M46B or add Carpet. Extend the existing harness.
- No claim M44's missing tests are now covered. M62 builds runner and index; M63 fills custody and fault scenarios.

## Verification

`build_mod` with task `dungeonIntegrationTest`, then `build_mod` with task `build`. Headless only: compiles, task exits zero on green and non-zero on the broken assertion, `DUNGEON_LEVEL` loads on a real dedicated server. Live client checks for screen, sound and cursor behaviour are M67 rows in the index. Done when both test-server routes prove claimed scope and every historical check has a disposition owner.

## Completion

- Append M62 harness boundary and check index to `plans/COMPLETED-MILESTONES.md`, same style as M44 to M61. Do not re-read the whole file.
- Append disposition appendix to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M62-handoff-completed.md`.
- If a 26.2 API surface could not be confirmed, record in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
