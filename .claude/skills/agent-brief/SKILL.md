---
name: agent-brief
description: The standing rules every Pocket Dungeons sub-agent follows (dash rule, branch and worktree discipline, no push or deploy, the suite command and lock, CRLF-safe edits, report shape). Use when writing a prompt for a sub-agent so the prompt is the task plus one line pointing here, instead of the rules pasted in full.
---

# Sub-agent standing rules

In a prompt, write the task, the branch name and the report path, then: "Follow `.claude/skills/agent-brief/SKILL.md`."
An agent reading this file should treat everything below as its standing orders.

## Setup
- You work in your own git worktree. First run `git merge feature/bug-testing-and-refinement` (the worktree may start
  older), then create your branch (named in the task) from the result. Never push, never touch `master` or another
  agent's branch, never deploy, never run `tools/server/pdserver.mjs`, never use the Lemon tools or any server.
- Read `CLAUDE.md` at the repo root and the docs the task names. `docs/CODEMAP.md` says which class does what; grep it
  before reading source.

## Writing rules
- No em dashes and no spaced double hyphens as punctuation in anything you write (code comments, docs, commit
  messages). Do not mass rewrite old text for this. Commit messages end with
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Match the surrounding code: naming, comment density, idiom. No drive-by reformatting.
- Player-facing text follows the `player-text` skill. A refactor changes no string, no behaviour.

## Editing
- Source files are CRLF. Use the Edit tool, or a small script file that imports `tools/pyedit.py`
  (`edit`, `write`, `remove_method`); never a large shell heredoc with embedded quotes.
- One commit per logical step. Never weaken or delete a test to make it pass.
- PLAYER DATA IS SACRED: never change an on-disk format (SavedData codecs, NBT, JSON config keys) without a reader
  for every older shape and a golden fixture test.

## Running the suite
- `tools/ship.sh --quick` (from `pocketdungeons`) takes the lock, runs `test runGameTest dist`, and prints only the
  result lines. Read for `SUITE GREEN` and the `All N required tests passed` line. Do not run gradle test tasks any
  other way while another agent is running (they bind the same server port).
- A new gametest class only runs if it is listed in `src/gametest/resources/fabric.mod.json`; check that N rises.
- Plain `compileJava compileTestJava` and the pure `test` tasks do not need the lock.

## Finishing
- Write the report file the task names and commit it. Reply in under 300 words: branch, report path, test counts
  before and after, what changed, and what only a live server can confirm.
- Stay inside the files the task owns; if you must touch another file, keep the edit minimal and say so.
