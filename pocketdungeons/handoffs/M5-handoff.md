# M5 — Wolves and Feral — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone spans two mods:
the work happens in `pocketdungeons/`, and it must interoperate with
`spiritwolves/` **without importing anything from it.**

Read, in order, before writing anything:

1. `pocketdungeons/MYTHIC_PLUS_RECONCILIATION.md` — §7.4, the verified wolf
   bytecode facts this whole milestone is built on
2. `pocketdungeons/ROADMAP.md` — where M5 sits
3. `pocketdungeons/plans/M5-wolves-feral.md` — **the authoritative scope**
4. `pocketdungeons/PROGRESS.md` — find the `## M5` table
5. `kamutotems/INTEGRATION.md` — the suite's "mods stay strangers" rule; read
   this even though it's a different mod, because M5 is the first milestone
   that actually crosses a mod boundary and the rule is what stops it becoming
   a dependency

## Goal

The first kiss/curse (§5.0) proven end to end, and the mod's first cross-mod
surface — verified to have **zero coupling**, not just designed to have zero.

**Blocked on:** M4 (needs the affix set to exist so Feral has somewhere to
live). **Blocks:** nothing downstream, but T5.3 (bones as a guaranteed floor)
is itself blocked on M6's T6.1 — check `PROGRESS.md` before assuming you can
finish this milestone standalone.

## The three facts everything here is built on — do not re-derive, do not guess

Read directly out of the 26.2 bytecode during the design pass
(`Wolf.mobInteract` / `Wolf.tryToTame`), and they are not obvious from playing
the game casually:

| Fact | Verified behaviour |
|---|---|
| Catch rate | `tryToTame` is `random.nextInt(3) == 0` — **exactly 1 in 3 per bone** |
| Angry wolves | `mobInteract` checks `isAngry()` and **refuses the bone entirely** |
| On success | `tame(player)`, then `setOrderedToSit(true)` — **the wolf sits down** |

If your build's jar differs from what's documented here (versions do move),
re-verify with `javap -p -c` on `Wolf.mobInteract` / `Wolf.tryToTame` before
writing tuning code — don't carry a stale assumption forward.

Forced consequences, already decided, do not relitigate:
1. Feral wolves must spawn **neutral, never angered** — no
   `startPersistentAngerTimer()`. An angry wolf is untameable, which deletes
   the kiss.
2. The readable in-game rule is **"don't hit it, feed it"** — this needs no
   code, it falls out of vanilla anger-on-hit behaviour for free.
3. A caught wolf **sits and stays sat** — accept this (§7.4's decision), don't
   clear the sit flag on tame. It's what stops a party trailing eighteen wolves
   through a timed run.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory** — especially here.
2. **No client mod, ever.**
3. **Mods stay strangers.** No `import` from `spiritwolves` anywhere in
   `pocketdungeons`, and vice versa. If you find yourself wanting one, the
   design has a gap — go back to `kamutotems/INTEGRATION.md` and
   `MYTHIC_PLUS_RECONCILIATION.md` §7.4 rather than reaching for the import.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4) — not
   directly relevant here, but keep the same discipline: wolves don't need
   announcing either.
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it.

## Process

1. Read every doc above in full, including `kamutotems/INTEGRATION.md`.
2. In `PROGRESS.md`, set each M5 task to `WIP` with your name/date first. T5.3
   should already show `BLOCKED` on T6.1 — leave it that way unless M6 has since
   shipped; check its `PROGRESS.md` status, don't assume from the roadmap.
3. Implement per `plans/M5-wolves-feral.md`.
   - T5.1 **reintroduces a direct entity-spawn path** that was deliberately
     deleted in an earlier milestone (T17, per the plan) because "two spawners
     in one room is two difficulty curves." Keep this narrow — a single
     Feral-only call site, not a general-purpose spawner. Read `RoomContent.java`
     and its history before adding anything here.
4. Verify:
   - `./gradlew build` green.
   - Live-server proof via `tools/rcon.py`: a Feral run spawns coat-appropriate
     wolves pinned to their cells; hitting one first makes it refuse a bone
     (confirm this explicitly — don't just assume the vanilla `isAngry()` check
     does what you expect, watch it happen); feeding an un-angered one with
     bones eventually tames it (the 1-in-3 rate means you may need several
     attempts — don't mistake a run of bad luck for a bug).
   - **Grep your own diff and the built jar's dependency list for any reference
     to `spiritwolves`** before calling this done. Zero references is a
     pass/fail check, not a style preference.
   - If you have a way to test with `spiritwolves` actually loaded alongside
     (check if the suite has a combined dev environment, or load both mods'
     built jars into one `run/mods/`), confirm a caught wolf's coat survives
     Spirit Stone binding. If you can't easily test both mods together, note
     that explicitly in `PROGRESS.md` rather than silently skipping it.
5. Update docs:
   - `PROGRESS.md`: every M5 task → `DONE`/`BLOCKED` with commit hash and what
     was verified. Session Log line. Move "Current milestone" to `M6` if M5 is
     fully done (T5.3 may still be legitimately blocked — that's fine, note it).
6. Commit, following this repo's commit style (`git log`, `f7345eb`).
7. **Last step, after everything is committed:** `git mv
   handoffs/M5-handoff.md handoffs/M5-handoff-completed.md` and commit that.
   If T5.3 is genuinely blocked, don't rename until it's resolved — a partial
   milestone doesn't get marked completed by renaming the handoff early.

## Scope

| # | Task |
|---|---|
| T5.1 | Untamed wolves at stamp time, pinned with `setHomeTo` — a narrow, Feral-only spawn path |
| T5.2 | 9 coats gated on `DifficultyProfile.lootTier()` via `WOLF_VARIANT` |
| T5.3 | Bones as the catch resource — **blocked on M6 T6.1**, the guaranteed-floor loot pass |
| T5.4 | Run-scoped by default; optional permanence via Spirit Stone — zero code on either side |

## Done when

- [ ] A Feral run spawns coat-appropriate wolves, pinned to their cells
- [ ] A player tames one with bones acquired in the same run
- [ ] Hitting a wolf first makes it untameable, and that reads as a rule rather
      than a bug
- [ ] Binding a caught wolf to a Spirit Stone preserves its coat
- [ ] No `spiritwolves` import appears anywhere in `pocketdungeons`
- [ ] `./gradlew build` green
