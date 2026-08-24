# M7 — Recipes — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` — §5.4 (the design this milestone implements)
2. `pocketdungeons/ROADMAP.md` — where M7 sits
3. `pocketdungeons/plans/M7-recipes.md` — **the authoritative scope**
4. `pocketdungeons/PROGRESS.md` — find the `## M7` table

## Goal

Server folklore. Someone comes back with *New Dungeon Discovered* and their
friends have to ask what they did.

**Blocked on:** M1 (themes must exist) and, informally, enough real play for
`DungeonLog` to have accumulated meaningful theme history to test against — if
this is a fresh dev world with no play history, you'll need to generate some
via `tools/rcon.py` rather than testing against an empty log.
**Blocks:** nothing downstream.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.**
2. **No client mod, ever.**
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4) — and this
   milestone has its own version of the same discipline: **do not build a
   recipe hint system, a progress bar, or a recipe book.** The plan is explicit
   about this (T7.3) — the goal is word of mouth, and a UI that tells players
   what to try converts folklore into a checklist and kills the feature. If
   you're tempted to add "helpful" UI here, don't; that's the one wrong turn
   this milestone can take.
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it.

## Process

1. Read every doc above in full.
2. In `PROGRESS.md`, set each M7 task to `WIP` with your name/date first.
3. Implement per `plans/M7-recipes.md`.
   - T7.1's `DungeonLog` codec change needs `optionalFieldOf` + a default, same
     trick the affix field uses (see M4's work if it's landed, or the existing
     affix field in `DungeonLog` if not) — so old saves migrate silently. Verify
     this explicitly rather than assuming the pattern transferred correctly.
   - T7.2's recipe table must load via `listResources` across **all**
     namespaces, matching how `dungeon_room` already works — this is what makes
     it a platform feature (`VISION.md` §6) rather than a private one. Make it
     `/reload`-driven from day one; M0 already built the plumbing for this
     pattern, reuse it rather than re-deriving.
4. Verify:
   - `./gradlew build` green.
   - Live-server proof via `tools/rcon.py`: complete three specific themes in a
     specific order via repeated `/dungeon admin build`-style runs (or however
     the dev harness lets you force a theme choice), confirm the recipe
     dungeon becomes available; **then confirm the same three themes in a
     different order do NOT trigger it** — order-sensitivity is the core claim
     of this milestone and needs an explicit negative test, not an assumption.
   - Confirm a player can see which themes they've completed (query however
     the dungeon log is surfaced) but cannot see the recipe combinations
     themselves.
   - Add a recipe as a JSON file in a scratch datapack, `/reload`, confirm it's
     live with no restart.
5. Update docs:
   - `PROGRESS.md`: every M7 task → `DONE` with commit hash and what was
     verified. Session Log line. Move "Current milestone" forward — check
     whether M8 items are ready for promotion, though M8 requires an explicit
     decision per its own file, don't promote anything from there automatically.
6. Commit, following this repo's commit style (`git log`, `f7345eb`).
7. **Last step, after everything is committed:** `git mv
   handoffs/M7-handoff.md handoffs/M7-handoff-completed.md` and commit that.

## Scope

| # | Task |
|---|---|
| T7.1 | Record the last N completed themes in `DungeonLog` — `optionalFieldOf` + default, no migration |
| T7.2 | Recipe table as datapack JSON: `[theme, theme, theme] → dungeon id` — order matters, `/reload`-driven |
| T7.3 | Discovery floor — ingredients (completed themes) visible, combinations not — **no hint system** |
| T7.4 | First recipe dungeon — cheap and data-shaped, not a new generator ruleset |

## Done when

- [ ] Completing three specific themes in order offers a dungeon that is not in
      the normal pool
- [ ] The same three themes in a different order do **not**
- [ ] A player can see which themes they have completed, and cannot see the
      combinations
- [ ] A pack author adds a recipe with a JSON file and `/reload`
- [ ] Old saves load with no migration step
- [ ] `./gradlew build` green
