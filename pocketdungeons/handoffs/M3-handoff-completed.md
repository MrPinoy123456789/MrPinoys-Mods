# M3 — The calling card — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` — §2.1 (why this is the thesis, not a nice-to-have)
   and §3.1.1 (the mechanism this milestone builds)
2. `pocketdungeons/ROADMAP.md` — where M3 sits
3. `pocketdungeons/plans/M3-calling-card.md` — **the authoritative scope**
4. `pocketdungeons/PROGRESS.md` — find the `## M3` table

**Hard prerequisite: confirm M2 is `DONE` in `PROGRESS.md` before starting
anything here.** M3 has no independent value without a persistent, permission-
masked room to visit — check M2's table, not just the roadmap arrow, since the
roadmap can be stale relative to actual progress.

## Goal

Other people can stand in your room.

> Skyblock was a challenge map for a year and became a *mode* the moment it went
> multiplayer. Hypixel's version won by adding a public layer beside the private
> one. This milestone is that step for Pocket Dungeons — it's the reason M2 got
> built, not a feature that happens to come after it.

**Blocked on:** M2, completely. **Blocks:** nothing downstream, but it's the
reason most of the rest of this document exists.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `LodestoneTracker` and
   `CUSTOM_DATA` were already verified present in 26.2 during the design pass —
   confirm again if you're relying on their exact shape, jars do change.
2. **No client mod, ever.**
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4) — doesn't
   directly apply to visiting, but keep the same discipline: no fanfare.
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it.

## Process

1. Read the docs above in full, plus whatever M2 actually shipped — its plan
   file may have been edited during implementation, so check `git log
   pocketdungeons/plans/M2-the-room.md` for late corrections, not just the
   original text.
2. In `PROGRESS.md`, set each M3 task to `WIP` with your name/date first.
3. Implement per `plans/M3-calling-card.md`.
   - **Owner UUID, not a `GlobalPos`.** This is stated three times across the
     docs because it's the one detail most likely to get "simplified" back into
     a position by mistake — a room blob moves between slots, so a stored
     position goes stale the moment it does.
   - **Read-only visits.** If the owner is away and a visit instance is live,
     writes from visitors must never propagate back to the owner's persisted
     blob. Decide and enforce this explicitly; don't let it fall out of
     whatever's convenient.
4. Verify:
   - `./gradlew build` green.
   - Live-server proof via `tools/rcon.py`: mint a card, use it on a lodestone
     while the owner is offline, confirm the visitor lands in a stamped copy;
     have a second "visitor" join and confirm they land in the *same* instance,
     not a second copy (this is the one behavior that makes or breaks the whole
     milestone — verify it explicitly, don't infer it from the refcount logic
     compiling).
   - Confirm visiting while the owner is online routes to the owner's live room,
     not a stale copy.
   - Confirm a non-keystone, non-card item used on a lodestone still falls
     through to other mods' handlers (test with any kamutotems item, or a plain
     stick if none is handy) — this is the PASS-through discipline
     `RitualListener` already has for the keystone check; the card check must
     match it.
5. Update docs:
   - `PROGRESS.md`: every M3 task → `DONE` with commit hash and what was
     verified. Session Log line. Move "Current milestone" to `M4`.
   - If the refcounting/write-back design needed a real decision beyond what
     the plan sketched, record it in `MYTHIC_PLUS_RECONCILIATION.md` §7 style.
6. Commit, following this repo's commit style (`git log`, `f7345eb`).
7. **Last step, after everything is committed:** `git mv
   handoffs/M3-handoff.md handoffs/M3-handoff-completed.md` and commit that.

## Scope

| # | Task |
|---|---|
| T3.1 | Mint a calling card — plain compass + `CUSTOM_DATA` owner UUID |
| T3.2 | Use-on-lodestone opens a way in that is not one of the three doors |
| T3.3 | One shared visit instance per owner, refcounted; route home if the owner is in |
| T3.4 | Visitor permissions — reuses M2's whitelist mask wholesale |

## Done when

- [ ] Two players holding cards to the same room stand in it together
- [ ] Neither can break a block or open the owner's chest
- [ ] Both can use the stations and the ender chest
- [ ] A card handed to a third player works without the owner online
- [ ] Visiting while the owner is home puts the visitor in the owner's live
      room, not a copy
- [ ] Nothing a visitor does survives their visit
- [ ] A non-card item used on a lodestone still reaches other mods' handlers
- [ ] `./gradlew build` green
