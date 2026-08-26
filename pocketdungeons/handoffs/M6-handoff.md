# M6 — Supply — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` — §3.6.1 (provenance) and all of §3.7 (this
   milestone exists to make §3.7's constraint literally true, not aspirational)
2. `pocketdungeons/ROADMAP.md` — where M6 sits
3. `pocketdungeons/plans/COMPLETED-MILESTONES.md` M6 — **the authoritative scope**
4. `pocketdungeons/PROGRESS.md` — find the `## M6` table — also check T5.3 in
   the `## M5` table, since your T6.1 unblocks it

## Goal

*"Could a player progress without ever leaving?"* stops being aspirational and
starts being true.

> **Self-sufficiency is a constraint, not a mode.** There is no "no-overworld
> mode" to build. One design, nothing tuned twice.

**Blocked on:** M1 (tiered palettes in T6.2 need themes to exist first).
**Blocks:** M7 in practice (recipes are more interesting once the tables
they're built from are real), and directly unblocks M5's T5.3.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.**
2. **No client mod, ever.**
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode** — this is the milestone
   where that rule is tested hardest. Every table change should be checked
   against: does this still work if the overworld is gone entirely?
5. **The mod stays quiet about the closed loop** (`VISION.md` §4) — not
   directly relevant here.
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it. Lava is Molten's job (M4) — do not add a
   second lava faucet here; see D6 in `DOOR_LADDER_BRAINSTORM.md` §15.7 for when that's
   allowed to be revisited.

## Process

1. Read every doc above in full.
2. In `PROGRESS.md`, set each M6 task to `WIP` with your name/date first.
3. Implement per `plans/COMPLETED-MILESTONES.md` M6.
   - T6.1's rule — **guaranteed floors for consumables, weighted rolls for
     treasure** — should be a floor pass over the existing tier tables, not a
     separate table. A floor that lives in a different file from the rolls is a
     floor that drifts out of sync the first time someone edits one and not the
     other.
   - T6.3's grove/garden room is deliberately **no Java** — one template, one
     `dungeon_room/*.json` entry. If you find yourself writing code for this
     task, you've probably over-scoped it; check the plan again.
4. Verify:
   - `./gradlew build` green.
   - Live-server proof via `tools/rcon.py`: run several dungeons at each tier
     and confirm the floor items (food, torches, bones, building blocks, seeds,
     dirt) appear in **every** run, not just most — a "usually appears" result
     on a guaranteed floor is a bug, not noise. For the weighted treasure rolls,
     confirm they still vary run to run as expected.
   - Confirm a tier-3 run visibly yields building blocks a tier-1 run cannot —
     compare loot side by side from two RCON-driven builds at different tiers.
   - The real test for this milestone: **actually try to progress with the
     overworld unreachable.** Simulate it if you can't literally empty the
     world — confirm nothing added elsewhere in the mod silently assumes an
     overworld exists (check T6.5's plumbing items especially: entry, exit,
     join, stray fallback).
5. Update docs:
   - `PROGRESS.md`: every M6 task → `DONE` with commit hash and what was
     verified. **Also update T5.3 in the `## M5` table** — it was `BLOCKED` on
     this milestone; once your T6.1 lands, either unblock it there (if another
     agent will pick it up) or, if you have time, just finish T5.3 yourself
     since it's now trivial. Session Log line. Move "Current milestone" to `M7`.
6. Commit, following this repo's commit style (`git log`, `f7345eb`).
7. **Last step, after everything is committed:** `git mv
   handoffs/M6-handoff.md handoffs/M6-handoff-completed.md` and commit that.

## Scope

| # | Task |
|---|---|
| T6.1 | Guaranteed floors for consumables, weighted rolls for treasure |
| T6.2 | Tiered building blocks in the loot tables, paired with M1's per-theme palettes |
| T6.3 | Grove/garden room type — one template, one JSON, no Java — plus guaranteed seeds and dirt |
| T6.4 | Nether/End *products*, not ingredients — station unlocks become the economy |
| T6.5 | Plumbing: `/dungeon` as first-class entry, no-op exit, join and stray fallback prefer the room |

## Done when

- [ ] A server with a completely emptied overworld is playable start to finish
- [ ] Every consumable in the floor table appears in every run, at every tier
- [ ] A tier-3 run visibly yields blocks a tier-1 run cannot
- [ ] Wood is obtainable without leaving, via a room type and no Java
- [ ] Nothing was tuned twice for "standalone" versus "suite"
- [ ] `./gradlew build` green
