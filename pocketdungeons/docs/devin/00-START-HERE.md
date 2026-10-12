# Handoff to Devin: Pocket Dungeons roadmap build (2026-10-12)

You are finishing a build that a team of AI sub-agents started. About half of it is merged and tested. This pack tells you
what is left, how to build and test it, the rules of the codebase, and how to report back so the work can be integrated.

Read this file, then `01-TASKS.md` (the work), then `03-KNOWN-ISSUES.md` (traps). Use `02-REPORT-TEMPLATE.md` for every
task you finish.

## The project in two sentences

Pocket Dungeons is a server-side Fabric mod for Minecraft 26.2 (Java 25, Gradle, Fabric Loom). Players enter
instanced roguelike dungeons from a Home room, carry a haul of scrap out, and raise a compass level; content is mostly
data (`src/main/resources/data/pocketdungeons`) driven by Java systems. There is no client mod: everything must work with
vanilla clients (vanilla mobs, blocks, particles, sounds, titles, boss bars, scoreboard teams).

## Where the code is

- Repo root is `MrPinoys-Mods`; the mod is in the `pocketdungeons/` folder. All paths below are relative to `pocketdungeons/`
  unless they start with `docs/` at the repo root of the mod (also `pocketdungeons/docs`).
- Start from the branch `feature/act-1-2-roadmap`. It contains everything merged so far. Do NOT start from
  `master` or `feature/bug-testing-and-refinement`.
- One branch per task, named `devin/<task-id>-<topic>`, branched from the tip of `feature/act-1-2-roadmap` at the time you
  start (rebase or merge it again before you finish if it moved). Never push to `master` or `feature/act-1-2-roadmap`.
- The owner will push the starting branch to a place you can reach. If you cannot see `feature/act-1-2-roadmap`, stop and
  ask; do not rebuild it from `master`.

## Design records (read the ones your task names; they outrank older docs)

- `docs/concepts-2026-10-11.md`: the approved concepts and the roadmap.
- `docs/boss-workshop-2026-10-11.md`: every boss and finale decision.
- `docs/affix-workshop-2026-10-11.md`: affix decisions.
- `docs/haunts-draft-2026-10-11.md`: the haunt mechanic.
- `docs/wager-draft-2026-10-12.md`: the Wager and Blood design (built).
- `docs/decision-2026-10-11-endless-mine-per-act.md`: the Endless Mine per act design (built).
- `docs/roadmap-build-2026-10-11.md`: the original task table.
- `docs/decision-2026-10-07-haul-and-blood-doors.md` and `docs/decision-2026-10-09-playtest-design-pass.md`: settled law
  (scrap curve, haul, lives, the Heard meter, the one Warden rule). Do not change them.
- `docs/CODEMAP.md`: grep it before reading source (`grep -n -i "finale" docs/CODEMAP.md`).
- Skills live at the repo root in `.claude/skills/*/SKILL.md`. They were written for another tool but are plain markdown;
  read these: `finale-script`, `room-building`, `add-tests`, `tune-knobs`, `dungeon-content`, `player-text`, `add-affix`,
  `agent-brief`. Where they mention `tools/ship.sh` or `gradlew.bat`, use the Linux commands below.

## Build and test (Linux)

`tools/ship.sh` is written for Windows (it calls `gradlew.bat` and uses `A:/` paths). On Linux use Gradle directly:

```bash
cd pocketdungeons
./gradlew compileJava compileTestJava --console=plain          # compile only, fast
./gradlew test --console=plain                                  # pure (plain main()) tests, wired in build.gradle.kts
./gradlew runGameTest --console=plain                           # all gametests (a headless server), ~4 min
./gradlew -I tools/gradle/gametest-filter.init.gradle runGameTest --console=plain   # one class, see ship.sh for the property
```

- Read how `tools/ship.sh --only <Name>` sets the filter (search `FILTER` in `tools/ship.sh`) and copy that for one class.
- A green run prints `All N required tests passed` (gametests) and `BUILD SUCCESSFUL`. Quote N before and after your change.
- Pure tests are `src/test/java/pocketdungeons/*Test.java`, wired in `build.gradle.kts`. A new one must be added there.
- A new gametest class only runs if it is listed in `src/gametest/resources/fabric.mod.json`. Check that N rises.
- Do not run two Gradle test runs at once in one checkout.

## House rules (non-negotiable)

1. No em dashes and no spaced double hyphens as punctuation, anywhere you write (code comments, docs, strings, commit
   messages). Use `:`, `;`, `,`, parentheses, or two sentences. Command line flags (`--console=plain`) and `i--` are fine.
   `tools/check_dashes.sh` checks your added lines (needs bash and git; run it before you commit).
2. Source files are CRLF. Edit with a tool that preserves line endings, or use `tools/pyedit.py` (`edit`, `write`).
   Never rewrite a whole file with different endings. Keep diffs small; no drive-by reformatting.
3. Player-facing words: compass (level), scrap, haul, lives, charts. Few words, no jargon, no dashes
   (`.claude/skills/player-text/SKILL.md`).
4. Match the surrounding code: naming, comment density, idiom.
5. PLAYER DATA IS SACRED: never change an on-disk format (SavedData codecs, NBT, config JSON keys) without a reader for
   every older shape and a golden fixture test.
6. Never weaken or delete a test to make it pass. A failing test is a finding; fix the cause or report it.
7. Fairness rules for any hazard (from `docs/haunts-draft-2026-10-11.md` and the `finale-script` skill): a tell of at least
   2 seconds (40 ticks) before anything hurts; never trap a player; the exit and the pad stay reachable; a death is an
   ordinary death (a life); anything placed in the world is removed or restored when the floor ends.
8. No server deploys, no Lemon tools, no remote Minecraft servers. Everything is verified by pure tests and gametests.
9. Do not commit generated build output (`build/`, `.gradle/`, `run/`).

## Hot files (many tasks touch them): keep edits minimal and local

`PocketDungeonsMod`, `PocketDungeonsConfig` (knobs), `FinaleWave`, `FinaleScripts.registerAll()` (one line per script),
`RoomContent`, `TrialContent`, `RunLifecycle`, `src/gametest/resources/fabric.mod.json`, `build.gradle.kts`,
`docs/playtests/LIVE_CHECKS.md`, `docs/CODEMAP.md`.

- Do NOT edit `LIVE_CHECKS.md` or `CODEMAP.md`. Write a fragment `docs/playtests/live-checks/<task-id>.md` instead.
- Config knobs: add them with `python3 tools/new_knob.py <name> <default> "<description>"` (read its header) and
  regenerate the six config fixtures:
  `PD_CONFIG_REGEN=src/test/resources/config-fixtures ./gradlew configCharacterizationTest configKnobsTest`.
  Every branch that adds a knob conflicts in those fixtures; resolve by keeping both sides and regenerating.
- Each finale gametest drives `FinaleWave` state by a slot number. Slots in use: 9921 to 9925, 9961 to 9984, plus the ones
  in `FinaleScriptGameTest`. Pick a new range (9990 to 9999 and above) and grep `src/gametest` to confirm it is free.
- `StampDeterminismGameTest` pins a stamp fingerprint that changes whenever a room changes. If it fails after your room
  change, re-measure and re-pin it in its own commit, and say so.

## How to report

After each task: commit, then write `docs/reports/<task-id>.md` from `02-REPORT-TEMPLATE.md` and commit it on your branch.
When all your work is done, send the owner one summary listing every branch, its report path, and test counts. The
integrator will merge branch by branch, so keep each task on its own branch and do not depend on another Devin branch
unless the task says so.
