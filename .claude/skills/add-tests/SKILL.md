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
