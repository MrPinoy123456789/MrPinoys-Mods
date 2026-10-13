---
name: add-tests
description: Add and run tests for the Pocket Dungeons mod. Explains the two test kinds (pure-JDK main() tests wired in build.gradle.kts, and Fabric gametests registered in src/gametest/resources/fabric.mod.json), how to build an InstanceRecord or mock player in a gametest, the source-scanning test pattern, and how to run the suites and read the result. Use whenever a change needs a regression test, a new test class, or a test run.
---

# Tests in Pocket Dungeons

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`.

## Pick the kind

| Kind | Where | Use when |
|---|---|---|
| **Pure test** | `src/test/java/pocketdungeons/<Name>Test.java`, a class with `main(String[])` | The rule can be a static method on plain data (math, text, selection, JSON, source scans). Fast. Minecraft classes are on the classpath, so `BlockPos`, `Blocks`, `RoomSelector`, `InstanceRecord` can be referenced; there is no server level. |
| **Gametest** | `src/gametest/java/pocketdungeons/<Name>GameTest.java`, `@GameTest` methods | It needs a `ServerLevel`, a mock player, block entities, or the real Lemon/Instances registries. |

Prefer extracting the decision into a small static method and testing that purely; add a gametest for
the wiring only when the wiring is the bug.

## Pure test

1. Write the class: `public class FooTest { public static void main(String[] args) { ...; System.out.println("FooTest passed"); } }`
   Fail with `throw new AssertionError("what")`. Keep a private `check(boolean, String)` helper.
2. Wire it in `build.gradle.kts` in **two places** (forgetting either means it never runs):
   - add `"fooTest" to "FooTest"` to the map of task names (the `for ((taskName, mainClass) in mapOf(` list,
     with a comment naming the PD or milestone);
   - add `dependsOn("fooTest")` in the block of `dependsOn(...)` lines under the `test` task.
3. Run one: `.\gradlew.bat fooTest --console=plain`. Run all: `.\gradlew.bat test --console=plain`
   (about 2 minutes). The run's working directory is the project root, so tests can read
   `src/main/resources/...` with relative paths (see `SupplySeparationTest`).

### Source and data scanning tests

When a rule can silently regress because a list lives in code or data, scan it:
- `RubbleRulesTest` regexes `src/main/java` for `applyEncounter(... "<room>", true|false)` and checks
  each room is in `RoomSelector.ENCOUNTER_ROOMS`.
- `BookLootTest` walks every loot table JSON and rejects `enchanted_book` with `enchant_with_levels`.
When parsing JSON with Gson, test `isJsonPrimitive()` before `getAsString()`: a `name` key can hold an
object (it did, and crashed the first version).

## Gametest

Scaffold first: `python tools/new_gametest.py <Name>` (from `pocketdungeons`) writes the class in the idiom of
`HerdGameTest` and registers it in `fabric.mod.json` for you (CRLF safe, at a spread position so parallel branches do
not conflict). Then `tools/ship.sh --only <Name>` runs only that class in seconds and prints
`All N required tests passed`; N is your method count. The steps below are what the scaffold does for you.

1. Write the class with `@GameTest` methods taking a `GameTestHelper`. Finish with `helper.succeed()`;
   use `helper.assertTrue/assertValueEqual` (they fail the test with your message). Give slow tests
   `@GameTest(maxTicks = N)` and use `helper.runAfterDelay(ticks, () -> ...)`; a failure inside a delayed
   lambda must be rethrown or `helper.fail(...)`.
2. **Register the class** in `src/gametest/resources/fabric.mod.json` under the entrypoint list
   (`"pocketdungeons.FooGameTest"`). An unregistered class compiles and never runs. After adding, check the
   reported test count rose by the number of methods you added (editing an existing test, such as adding a room to
   `RoomLibraryGameTest.CHECKED`, adds none).
3. Run: `.\gradlew.bat runGameTest --console=plain`. The tail shows
   `========= N GAME TESTS COMPLETE` and `All N required tests passed :)`. Filter the output, it is long.

### Recipes that already work

- Mock player: `@SuppressWarnings("removal") ServerPlayer p = helper.makeMockServerPlayerInLevel();`
  then `p.setGameMode(GameType.SURVIVAL)` and `p.teleportTo(level, x, y, z, Set.of(), 0F, 0F, false)`. The mock is
  in the player list, so server-wide code sees it. Always `Lemon.forget(...)` / unregister in `finally`.
- Build a run record (see `BankAnywhereGameTest`, `RescueGameTest`): a `PlanGeometry` of one cell, an
  `InstanceLayout`, then `new InstanceRecord(slot, origin, tick, layout, Set.of(), owner, false)`; put members with
  `record.members.put(uuid, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0, 0))`; register in
  `InstanceRegistry.bySlot/usedSlots/byMember`; **always unregister in a `finally`**. Use an unused slot number.
- The gametest world has **no dungeon dimension**: teleports to it fall back to sending the player home, and code
  that needs `DUNGEON_LEVEL` cannot be exercised end to end. Test the pieces (`LibrarianNPC.restock(level, record)`
  was added for this reason).
- Placing blocks: use `helper.absolutePos(BlockPos.ZERO)` for a cell origin and clear what you place. For a far
  location, force chunks (`level.setChunkForced`) and release them in `finally` (`HiddenOreGameTest` or `SeamGameTest`; `TollRoomGameTest` was retired with the toll rooms).
- Lemon: `Lemon.setMode(player, true)` then `Lemon.heard(...)`; `Lemon.view(player)` reads pending state.
- Ordeals and locks tick on their own period; call `INSTANCE.tickDanger(level, origin, state)` directly in a loop
  (`PlateRelayGameTest`) instead of waiting game time.

## Other suites

- `.\gradlew.bat dungeonIntegrationTest` boots a real dedicated server with the bundled datapack and loads the
  real dimension; run it after changes to content loading, the dimension or startup. It prints `PASS`.
- `.\gradlew.bat build` also compiles everything; `dist` writes the jar to `A:\MrPinoys Mods\dist`.

## Running fast

- `tools/ship.sh --pure-fast`: the pure tests six at a time (about 1.5 minutes instead of 4), no lock. `--pure` is the
  same through Gradle. `tools/pure_tests.py --only <substring>` runs just matching pure tasks.
- `tools/ship.sh --only <GameTestClass or method>`: gametests by name, a few seconds.
- `tools/ship.sh --quick --no-dist`: the full suite, once, at the end. It waits for one of 3 slots.

## Reading results and reporting

Piping gradle through `Select-String` in PowerShell can report exit code 255 even when the build is green, and
`Select-Object -First N` cuts the interesting lines off. PowerShell's `*>` redirect writes UTF-16, which Grep and
`grep` cannot read. Use Git Bash instead, in the project dir:
`./gradlew.bat test runGameTest dungeonIntegrationTest --console=plain > /a/tmp/run.txt 2>&1; echo $?`
(exit 0 means green), then Grep the file for `BUILD`, `FAILED`, `AssertionError`, `All N required tests passed` and
`Loaded N dungeon rooms`. Only one gradle run at a time per project directory; if other agents share the tree, use the
lock described in the `room-building` skill.

A green run says `BUILD SUCCESSFUL`. Quote the gametest count. If a test fails, read the first failure and
fix the cause; do not delete or loosen the assertion. Say what tests cannot prove (look and feel, live
timing, remote-server behaviour) and leave that to a live check (`docs/playtests/LIVE_CHECKS.md`).


## Passes alone, fails in the full run (added 2026-10-12)

`tools/ship.sh --only <Class>` runs one class in about 20 seconds and can hide an interaction. Four tests passed alone and
failed in the full suite during the roadmap build. The causes, in order of how often they bit:

1. **Shared finale state by slot number.** Every finale gametest drives `FinaleWave` state through a record slot. Two
   classes using the same slot unregister each other's script or read each other's mobs (symptoms: "already registered",
   a `Cow cannot be cast to Wolf`, an occupant that never wakes). Grep `src/gametest` for the slot constants and pick an
   unused range. In use at the end of the roadmap build: 9921 to 9925, 9941 to 9942, 9961 to 9984.
2. **Anything seeded from the test's absolute world position.** Plan seeds mix in the cell origin, and tests are placed at
   different positions in a full run. A single late sample ("at tick 2320 every brick is still there") passes at one
   position and fails at another. Poll in small steps and record what ever happened, or assert on the seeds themselves.
3. **A random owner id meeting a growing affix pool.** `UUID.randomUUID()` plus an offer at compass 5 or more draws one
   seeded affix; once Blood joined the pool some ids drew it and a "plain door costs 0 lives" test failed. Use compass 4 or
   lower when an offer must carry no seeded affix (the first affix slot is at compass 5), or name the affixes explicitly.
4. **Stray entities.** The shared test world can hold mobs from earlier tests near yours; count or look up your own by
   tag, not "the first entity".
5. **Cleanup that is not in a `finally`.** Unregister scripts and call `FinaleWave.teardown(level, slot)` even when the
   test fails, or one failure cascades.

To reproduce, run the full `tools/ship.sh --quick --no-dist` and read the first failing test name in the log, then run
the two or three classes around it with `--only` using a substring both ids share.

A test the repo does not run by default can rot unseen: `sessionStateTest` failed on the base branch for a long time
because it is not part of the `test` task. New static state holders must go through `SessionState.clearedAtStop`
or be allowlisted there.
