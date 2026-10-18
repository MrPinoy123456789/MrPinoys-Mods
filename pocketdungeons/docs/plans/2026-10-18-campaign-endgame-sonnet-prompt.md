# Prompt: build the campaign and endgame (for Sonnet)

Paste everything below the line into a fresh Claude Code session (Sonnet) started in `A:\MrPinoys Mods`.

---

**Scope: Part A only.**
(Change this line to "Scope: Parts A and B." to also build review items 1, 3, 7 and 9. Saying so is the owner's
approval of those items.)

You are the orchestrator for a multi-agent build of Pocket Dungeons, a server-side Fabric mod for Minecraft in
`A:\MrPinoys Mods\pocketdungeons`. Parties of 1 to 4 run procedural dungeons built from hand-made rooms. The owner has
approved a campaign and endgame redesign; your job is to build all of it in scope, verified and merged on one
integration branch, without pushing, deploying or touching `master`.

## Read first, in this order

1. `A:\MrPinoys Mods\CLAUDE.md`: never write an em dash or a spaced double hyphen as punctuation, anywhere.
2. `pocketdungeons/docs/DECISIONS.md`, the last section, "2026-10-18: Campaign and endgame". This is the rule.
3. `pocketdungeons/docs/plans/2026-10-18-campaign-endgame-build-spec.md`. This is the build: tasks T0 to T8 (and B1 to
   B4 if in scope), waves, file ownership, exact player strings, tests and the definition of done.
4. The skills you will run under: `.claude/skills/orchestrate-build/SKILL.md` (you), `.claude/skills/agent-brief/SKILL.md`
   (every sub-agent), `.claude/skills/integrate-branches/SKILL.md` (merging), and as each task needs them `add-tests`,
   `tune-knobs`, `add-affix`, `player-text`, `room-building`, `dungeon-content`, `finale-script`.
5. `pocketdungeons/docs/CODEMAP.md` before reading any source. The spec cites `file:line` from 2026-10-18; lines drift,
   so grep before you edit. The code decides.

## How to run it

- Base branch `feature/campaign-endgame-design` (verify its tip contains the build spec and the 2026-10-18 decision). Make the integration branch
  `feature/campaign-endgame` in its own worktree. Never work in the owner's main checkout.
- Run the waves in the spec's order. At most four sub-agents at once (`general-purpose`, `isolation: "worktree"`,
  `run_in_background: true`); start the next only when one finishes. A task starts only when its "needs" are merged.
- Each sub-agent prompt is: the task id and section of the spec, the branch (`feature/rm-<id>-<topic>`, made with
  `tools/new_worktree.sh` and `PD_BASE=feature/campaign-endgame`), the report path `docs/reports/rm-<id>.md`, the files it
  owns, and the line "Follow `.claude/skills/agent-brief/SKILL.md`." Tell each one: commit in logical steps, iterate
  with `tools/ship.sh --only <Class>` and `--pure-fast`, run the full suite at most twice, write live checks as
  fragments, and say what only a live server can confirm.
- Merge each finished branch into the integration branch at once, run its tests, and keep a status table at the bottom
  of the spec file (task, branch, merged, tests, notes).
- After any stop or usage limit: `python pocketdungeons/tools/agent_status.py feature/campaign-endgame`, commit any
  stalled agent's work as WIP, and resume agents with `SendMessage` in batches of four, closest to done first.
- Machine limits: check free space on C: and A: before each wave; Gradle cache and temp stay on A:. If a write fails for
  space or the paging file, stop the wave and tell the owner what is big; delete nothing of theirs.

## Hard rules

- **Player data is sacred.** Any change to `DungeonLog`, NBT or config keys needs a reader for every older shape and a
  golden fixture. The pot contents must never be lost to a crash or restart (spec T4 step 5).
- **Never soft-lock.** No floor may need an item the player might not have; a pot never blocks a path; the relic is never
  the gate.
- **Tangible over menus.** Choices are blocks in the world. The only new menu is the pot's container view.
- **Use the spec's exact strings.** If a string must change, say why in the report.
- Do not weaken or delete a test to pass. Do not run any server, Lemon tool, deploy or `pdserver.mjs`. Do not push.
- Commits are authored `MrPinoy123456789` (`-c user.name=MrPinoy123456789
  -c user.email=MrPinoy123456789@users.noreply.github.com`) and end with
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- If the spec and the decision disagree, follow the decision and record the deviation. If something in the spec is
  impossible in vanilla 26.2 server-side, build the nearest feasible version and record it; do not invent new design.

## Done means

Everything in scope merged into `feature/campaign-endgame`; `tools/ship.sh --quick --no-dist` prints `SUITE GREEN` with
more tests than the base; `tools/check_dashes.sh` clean; `docs/reports/campaign-endgame-build.md` written (per task:
built, deviations, test counts, live-only checks L104 to L107); the decision gets a one-line "Built" status; the
temporary `docs/reports/rm-*.md` files are deleted once folded into the final report.

Report to the owner once, at the end, in under 300 words: what is merged and green, what is not built and why, every
deviation from the spec, what only a live playtest can confirm, and any machine problems that mattered. Between merges
stay quiet: one line per finished task at most.
