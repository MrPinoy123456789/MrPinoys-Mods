---
name: agent-brief
description: The standing rules every Pocket Dungeons sub-agent follows (dash rule, branch and worktree discipline, no push or deploy, the suite commands and lock, hot files, scaffolds, CRLF-safe edits, report shape). Use when writing a prompt for a sub-agent so the prompt is the task plus one line pointing here, instead of the rules pasted in full.
---

# Sub-agent standing rules

In a prompt, write the task, the branch name and the report path, then: "Follow `.claude/skills/agent-brief/SKILL.md`."
An agent reading this file should treat everything below as its standing orders.

## Setup
- The base for the roadmap build is `feature/act-1-2-roadmap` (the task may name another; the task wins).
  Preferred: `tools/new_worktree.sh <id> <topic>` from the repo root makes `.claude/worktrees/rm-<id>` on
  `feature/rm-<id>-<topic>` off the base and prints the path. If you were handed a worktree instead, first run
  `git merge feature/act-1-2-roadmap`, then create your branch (named in the task). Merge the base again before your
  final run. Never push, never touch `master` or another agent's branch, never deploy, never run
  `tools/server/pdserver.mjs`, never use the Lemon tools or any server.
- Read `CLAUDE.md` at the repo root and the docs the task names. `docs/CODEMAP.md` says which class does what; grep it
  before reading source. `docs/roadmap-build-2026-10-11.md` has the task table and the rules of this build.

## Hot files (many tasks touch them): keep edits minimal and local
`PocketDungeonsMod` (register calls), `PocketDungeonsConfig` (knobs), `FinaleWave`, `RoomContent`, `TrialContent`,
`RunLifecycle`, `src/gametest/resources/fabric.mod.json`, `docs/playtests/LIVE_CHECKS.md`, `docs/CODEMAP.md`.
- NEVER edit `LIVE_CHECKS.md` or `CODEMAP.md` directly. For a live check write a fragment:
  `python tools/livecheck.py new <id> "<title>"`, fill the TODOs, commit it. The integrator folds fragments in.
- Add a gametest with `python tools/new_gametest.py <Name>` (writes the class, registers it at a spread position so
  branches do not conflict). Add a config knob with `python tools/new_knob.py ...` (see `tune-knobs`). A new affix
  operation follows `add-affix`.

## Writing rules
- No em dashes and no spaced double hyphens as punctuation in anything you write (code comments, docs, commit
  messages). Do not mass rewrite old text for this. Commit messages end with
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. `tools/check_dashes.sh` checks your added lines.
- Match the surrounding code: naming, comment density, idiom. No drive-by reformatting.
- Player-facing text follows the `player-text` skill. A refactor changes no string, no behaviour.

## Editing
- Source files are CRLF. Use the Edit tool, or a small script file that imports `tools/pyedit.py`
  (`edit`, `write`, `remove_method`); never a large shell heredoc with embedded quotes.
- One commit per logical step. Never weaken or delete a test to make it pass. Never `git reset --hard` with
  uncommitted work in the tree.
- PLAYER DATA IS SACRED: never change an on-disk format (SavedData codecs, NBT, JSON config keys) without a reader
  for every older shape and a golden fixture test.

## Running the suite (from `pocketdungeons`, in this order)
1. `tools/ship.sh --pure-fast` while iterating (pure tests 6 at a time, no lock, about half the time), or
   `tools/ship.sh --pure` (the same tasks through Gradle, also runs the dedicated server check; no lock).
2. `tools/ship.sh --only <GameTestClass>` runs just your gametest class (or one method name) in seconds; it takes a
   slot but is short.
3. `tools/check_dashes.sh` (exit 1 lists file:line).
4. Once, at the end: `tools/ship.sh --quick --no-dist`. It waits for one of 3 slots (the old lock directory
   `A:/tmp/gradle-suite.lock` is slot 1), prints `SUITE GREEN (All N required tests passed)` and the elapsed time.
   Do not run `gradlew test` or `runGameTest` by hand beside it; use these scripts.
- A new gametest class only runs if it is listed in `src/gametest/resources/fabric.mod.json` (the scaffold does it);
  check that N rises.

## Finishing
- Write the report file the task names and commit it. Reply in under 300 words: branch, report path, test counts
  before and after, what changed, and what only a live server can confirm.
- Stay inside the files the task owns; if you must touch another file, keep the edit minimal and say so.

## Disk (added 2026-10-12)
Drive C: filled up during the first big build and stopped every agent. `tools/ship.sh` now keeps Gradle's cache and
temp files on A: (`GRADLE_USER_HOME=A:/gradle-home`, `TMP=TEMP=A:/tmp`). When you call `./gradlew.bat` directly, export
the same three variables first (`export GRADLE_USER_HOME=A:/gradle-home TMP=A:/tmp TEMP=A:/tmp`). Never delete files
outside your worktree to free space; if a write fails with ENOSPC, stop and say so in your report.


## Machine limits and concurrency (added 2026-10-12)

- Do not run more than one Gradle test run in your own checkout at a time, and never edit `tools/ship.sh`'s lock.
- Prefer `--only <Class>` (about 20 s) while iterating, `--pure-fast` for the pure tests (about 1 min), and the full
  `--quick` at most twice per task. A full run is about 4 minutes and takes a lock slot.
- If a command fails with "no space left on device" or "paging file too small", stop and say so in your report; the
  orchestrator decides what to free. Never delete files outside your own worktree.
- Commit in logical steps as you go. A usage limit can stop every agent at once, and uncommitted work is the only
  thing a stop can lose.
- If you are told to wrap up, commit, stop at a clean break that compiles, and add a "Handoff" section to your report
  (done, unfinished, exact next commands, latest test counts).
