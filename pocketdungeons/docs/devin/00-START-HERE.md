# Start here

**Pocket Dungeons** is a server-side Fabric mod (Java, no client mod, no resource pack) for a Minecraft multiplayer
server: players take parties through instanced dungeons floor by floor, across a five act campaign. This handoff turns
it into a crew game (the party plays the leader's campaign), replaces the floor history board with a room-wide
terminal, and adds a reusable clickable world object ("glyph").

## Read, in this order

1. `docs/devin/01-SPEC.md`: **the build spec.** Every player-facing word, colour, position and pixel is in it.
2. `docs/devin/02-TASKS.md`: the task list, order, branches and acceptance per task.
3. `docs/devin/04-KNOWN-ISSUES.md`: traps that have bitten agents before.
4. The last section of `docs/DECISIONS.md` ("2026-10-21: One campaign per party, the terminal, and glyphs"): the
   approved decision. The spec wins where they differ in detail.
5. `CLAUDE.md` at the workspace root: the punctuation rule (no em dashes, no ` -- ` as punctuation, anywhere).
6. `.claude/skills/add-tests/SKILL.md`: how the two kinds of test work and how to register them.
7. `.claude/skills/agent-brief/SKILL.md`: hot files, scaffolds and writing rules (ignore its Windows only commands).
8. `docs/CODEMAP.md` when you need to find a class.

## Where the code is

- Mod: `pocketdungeons/` (Gradle project). Java under `src/main/java/pocketdungeons/`, data under
  `src/main/resources/data/pocketdungeons/`, gametests under `src/gametest/`, pure tests under `src/test/`.
- The classes you will touch most: `DungeonScreen`, `StagingFurniture`, `FloorHistory`, `HallRoom`,
  `DungeonProgress`, `DungeonLog`, `RunLifecycle`, `Instances`, `InstanceRecord`, `EndlessMineRules`,
  `PocketDungeonsConfig`, `PocketDungeonsMod`, `RoomBuilder`, `DialogScreens`, `Payout`.

## Branches

- Start from the tip of `master` you were given. Record its sha in your report.
- One branch per task, named `devin/<id>-<topic>` (ids in `02-TASKS.md`). Each task branches from the previous task's
  branch (a chain), so the last branch holds everything.
- Do not push to `master`, do not merge, do not rewrite history, do not deploy to any server.

## Build and test (Linux)

Run from `pocketdungeons/`:

```bash
./gradlew build --console=plain
./gradlew test --console=plain
./gradlew runGameTest --console=plain
./gradlew dungeonIntegrationTest --console=plain
```

- `test` runs the pure-JDK `main()` suites. Each new pure test class must be added to the list in
  `build.gradle.kts` and to `tasks.test`'s `dependsOn` (see the add-tests skill).
- `runGameTest` prints `All N required tests passed`. **A new gametest class runs only if it is listed in
  `src/gametest/resources/fabric.mod.json`.** Check that N rises by the number of methods you added.
- Record the counts on the base commit before you change anything, and again at the end of every task.

## House rules

- No em dashes and no ` -- ` as punctuation in code, comments, docs, strings or commit messages. Command flags like
  `--console=plain` are fine.
- Match the surrounding code: naming, comment density, javadoc style. No drive-by reformatting.
- Files are CRLF in places; keep each file's existing line endings.
- No new mixins. If you believe one is unavoidable, stop and say why in the report.
- Do not decide anything the spec leaves to the owner: names of things, new player-facing words, balance numbers.
  Use `TODO(owner)` and list it.
- Live checks: do not edit `docs/playtests/LIVE_CHECKS.md`; put live checks in your report (template in `03`).

## How to report

Fill `docs/devin/03-REPORT-TEMPLATE.md` once per task as `docs/devin/report-<id>.md` on that task's branch, and a
final summary `docs/devin/report-final.md` on the last branch.
