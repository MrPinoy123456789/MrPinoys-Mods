---
name: orchestrate-build
description: Run a large Pocket Dungeons build with many parallel sub-agents without hitting the machine's limits. Covers sizing waves, the concurrency cap, file ownership, task briefs, the status table, resuming agents after a usage limit or a full disk, wrap-up instructions and reporting once at the end. Use when asked to build a roadmap or "get agents to build everything", or when agents have stalled.
---

# Orchestrating a multi-agent build

House rule for everything you write: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Every agent follows
`agent-brief`; a task prompt is the task plus one line pointing there.

## Limits learned the hard way (2026-10-11 and 12)

- **Concurrency: at most four or five agents at once.** Eight to eleven hit the account usage limit twice (HTTP 429,
  "session limit, resets at HH:MM"); every running agent died at once. Start a new agent only when one finishes.
- **The suite is slow and shared.** `tools/ship.sh --quick` takes about 4 minutes; up to three run at once on separate
  lock slots. Agents iterate with `--only <GameTestClass>` (about 20 s) and `--pure-fast` (about 1 min) and run the full
  suite once at the end.
- **Memory:** many JVMs at once exhaust the Windows paging file ("paging file too small", errno 1455, JVM native
  memory errors). Four agents plus a suite is the safe maximum. If the paging file lives on a full drive it cannot grow.
- **Disk:** drive C: filled to 0 bytes and stopped every tool (even shell output). Gradle's cache and temp now live on
  A: (`GRADLE_USER_HOME=A:/gradle-home`, `TMP=TEMP=A:/tmp`; `tools/ship.sh` sets them, direct `./gradlew` calls must
  export them). Check `Get-PSDrive` before launching a wave. Never delete the owner's files to make room; say what is
  big and let them decide.

## Plan the work so agents do not collide

1. Write the plan as a task table (id, size S/M/L, "needs", main files) in `docs/roadmap-build-<date>.md`, with a
   status table at the bottom. Group by file ownership: two tasks that edit the same non-hot file must not run together.
2. Build the shared seam first (here: the `FinaleScript` registry) and merge it before the tasks that use it.
3. List the hot files (`PocketDungeonsMod`, `PocketDungeonsConfig`, `FinaleWave`, `FinaleScripts.registerAll()`,
   `fabric.mod.json`, `build.gradle.kts`, `LIVE_CHECKS.md`, `CODEMAP.md`). Agents keep edits there minimal and never
   edit `LIVE_CHECKS.md` or `CODEMAP.md` (they write a fragment with `tools/livecheck.py new`).
4. Give every finale gametest its own slot range; colliding slots made tests read each other's mobs.
5. One integration branch in its own worktree (`feature/<name>`); one task branch per agent (`feature/rm-<id>-<topic>`),
   each merging the integration branch first. Never touch the owner's main tree.

## The task prompt

Agent type `general-purpose`, `isolation: "worktree"`, `run_in_background: true`. Include: the task id and base branch
("merge `<integration>` first, then create `<branch>`"), the report path (`docs/reports/rm-<id>.md`), the design record
rows to read, the exact behaviour and numbers, the fairness rules, the test expectations, the tools to use
(`--only`, `--pure-fast`, `check_dashes.sh`, `new_knob.py`, `new_gametest.py`, `livecheck.py`), "commit in logical steps so
a cutoff loses nothing", "run the full suite at most twice", and "say what only a live server can confirm".

## Knowing the true state

Never rely on an agent's last message. After any stop, run:

```bash
python pocketdungeons/tools/agent_status.py <integration-branch>
```

It lists each worktree as merged, unmerged, dirty (cut off mid-edit) or locked. Commit a stalled agent's uncommitted
files to its branch (`git add -A && git commit -m "WIP ..."`) before anything else so nothing is lost.
Notifications repeat: the same agent can "finish" several times with its old text. Read the latest one for new facts only.

## Resuming after a stop

Use `SendMessage` to the agent id; it resumes with its context. Say: what changed (limit reset, disk fixed), that it
should run `git status` and `git log` first, to merge the integration branch again (conflicts in the usual files: keep
both sides, regenerate fixtures), to finish and report. Resume in batches of four or five, closest-to-done first.
After a usage limit, wait until the reset time in the error before resuming.

## Merging as they finish

Merge each finished branch into the integration branch at once (`integrate-branches` skill), so conflicts stay small,
and so later agents merge a base that already has the earlier work. Update the status table in the roadmap doc.

## Wrapping up on request

When the owner says to wrap up: message every running agent to commit, stop at a clean break that compiles, write a
"Handoff" section in its report (done, unfinished, exact next steps and commands, latest test counts), and reply in under
150 words. Do not start new agents. Then compile what is left into a handoff pack (`external-handoff` skill).

## Reporting

The owner may ask for reports only at the end. Then stay quiet between merges: one line per notification at most. At the
end give: what is merged and green, what is not built, anything that changed the plan, what only a live server can
confirm, and the machine problems that mattered. Once the wave is integrated and verified, delete the temporary
`docs/roadmap-build-*.md` and `docs/reports/` files so throwaway build docs do not pile up.
