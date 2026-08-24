# M4 — Affixes — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/MYTHIC_PLUS_RECONCILIATION.md` — §4, §4.1, §5.0, §5.5, §7.1,
   §7.3 are all directly load-bearing for this milestone; read the whole
   document once, this is the section it was mostly written for
2. `pocketdungeons/ROADMAP.md` — where M4 sits
3. `pocketdungeons/plans/M4-affixes.md` — **the authoritative scope**
4. `pocketdungeons/PROGRESS.md` — find the `## M4` table

## Goal

The ladder gets texture. Affixes stack by level, seed from the key (never a
weekly rotation — that was cut, see §4), and **every one of them hands the
player something.**

**Blocked on:** nothing hard. **Blocks:** M5 — Feral is an affix, and can't be
tested end to end until the affix-set machinery exists.

## The one rule that governs every line of code in this milestone

> **Every affix bends a rule and pays for it with a gift.** (§5.0)

If you find yourself implementing an affix that only takes something away, stop
and either find its kiss or don't ship it. This is not a nice-to-have — §5.5
already worked out that Molten's kiss is "it's the only lava faucet in the game"
and Silenced's kiss is "the mobs can't hear you either." Both curse and kiss are
specified; implement both halves of each affix, not just the punishing half.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.**
2. **No client mod, ever.**
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4) — doesn't
   directly apply here, but keystone naming (T4.4) must stay a **pure function**
   of `(level, affixSet)` with no randomness, because the instance watcher
   rewrites stale keystones in place and any nondeterminism there will churn.
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it. §4.1 (5/11/17 thresholds), §7.1 (depletion
   takes the max, capped at 2x) and §5.5 (Molten/Silenced kisses) are all
   settled — implement them, don't re-derive them.

## Process

1. Read every doc above in full.
2. In `PROGRESS.md`, set each M4 task to `WIP` with your name/date first.
3. Implement per `plans/M4-affixes.md`.
   - T4.1's storage change needs **no codec migration** — `DungeonLog` already
     stores the affix as `Codec.STRING.optionalFieldOf("keystone_affix", "")`;
     comma-join a set into the same field. Verify old-save compatibility as part
     of your test, don't just trust the plan's claim.
   - Only three sites branch on the affix today (`Instances.java:360`,
     `Instances.java:1137`, `Keystones.java:63` at time of writing — confirm
     current line numbers, the file has moved before). Refactor each to
     `contains(...)`. If you find a fourth site the plan missed, that's real
     information — note it in the plan file.
4. Verify:
   - `./gradlew build` green, including whatever pure-Java regression test
     covers `KeystoneMath`/`Keystone` today (see `src/test/java/pocketdungeons/`
     for the existing style — `KeystoneMathTest`, `DifficultyProfileTest` — and
     extend or add one for the affix set rather than only checking it by hand).
   - Live-server proof via `tools/rcon.py` for at least: a level-16 key renders
     as `Menace Cooked Keystone [16] [Swarming]` (or whatever the actual seeded
     affix is at that level — confirm the format matches T4.4, not just that
     something renders); two affixes both touching depletion produce the capped
     max, not a product (construct the case deliberately, don't rely on natural
     play to hit it).
   - For each of the five affixes, write down in one sentence what a player
     would say it costs them and what it gives them. If you can't write the
     second half of that sentence, the affix isn't done — see the rule above.
5. Update docs:
   - `PROGRESS.md`: every M4 task → `DONE`/`CUT` with commit hash and what was
     verified. If Molten or Silenced's kiss turns out not to work as designed in
     practice, that's a real finding — mark it `BLOCKED` or note the deviation,
     don't silently ship a worse version. Session Log line. Move "Current
     milestone" to `M5`.
   - Naming table and threshold values already live in
     `MYTHIC_PLUS_RECONCILIATION.md` §4.1/§6 — update those in place if
     implementation surfaced a correction, rather than letting the doc and the
     code drift apart.
6. Commit, following this repo's commit style (`git log`, `f7345eb`).
7. **Last step, after everything is committed:** `git mv
   handoffs/M4-handoff.md handoffs/M4-handoff-completed.md` and commit that.

## Scope

| # | Task |
|---|---|
| T4.1 | `Keystone.Affix` enum → stackable set; comma-join into the existing field, no codec migration |
| T4.2 | Level thresholds **5 / 11 / 17** decide *how many*; the key seeds *which* — no weekly rotation |
| T4.3 | Depletion takes the `max` across the active set, hard-capped at 2× |
| T4.4 | Naming: `<intensifier> <affix> Keystone [<level>]`, rendered in enum order, always |
| T4.5 | Swarming, Overclocked, Molten, Silenced — each with its kiss implemented, not just its curse |

## Done when

- [ ] A level-16 key reads `Menace Cooked Keystone [16] [Swarming]`
- [ ] Old saves holding `"ominous"` load as a one-element set, no migration
- [ ] Two affixes that both touch depletion produce `max`, not a product
- [ ] Each of the five affixes can be described to a player as "it does X, and
      you get Y" without hesitating
- [ ] The keystone name is stable across a watcher reconciliation
- [ ] `./gradlew build` green
