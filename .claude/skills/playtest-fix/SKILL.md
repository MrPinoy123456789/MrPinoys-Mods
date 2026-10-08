---
name: playtest-fix
description: Turn a Pocket Dungeons playtest report into verified fixes. Reads the report and the numbered PD entries in docs/reference/BUGS.md, checks each reported bug against the code before changing anything, fixes in priority order with a regression test each, updates the BUGS.md status, and reports back per PD number with the covering tests and what only a live server can confirm. Use when given a playtest report, a list of PD numbers, or a prompt that says to fix findings.
---

# Fixing a playtest report

Companion to `/playtest` (which produces the report). House rule for everything you write (code
comments, docs, strings, commit messages): no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`.

## Inputs to read first

- The report: `docs/playtests/<date>-<n>.md` and its `.notes.md` (the live session log, with the
  timeline the findings were drawn from).
- The PD entries: `docs/reference/BUGS.md`, the newest `## <date>` section at the bottom. Each entry has
  **Reported** and **Status**. The next free number is the highest PD plus one.
- Any fix brief the owner pasted (it sets the priority order, the house rules and what NOT to do).

## Standing rules (unless the brief says otherwise)

- Do not commit, deploy or restart the server unless asked. Never touch Kinetic server 365f9682.
  Deploy is its own skill (`deploy-test-server`).
- A design question is the owner's to decide: propose two or three options with a recommendation and
  WAIT. Do not code it. Typical examples: what a quit costs, a new scrap rule, replacing a room.
  Things listed as "player design asks, do not implement without sign-off" stay unimplemented.
- Match neighbouring code style and comment density. Read the surrounding file before editing.

## Per bug

1. **Verify the claim against the code before changing anything.** Reports are written from a live
   session by an agent reading logs; the cause is often wrong or only half right. Find the code path,
   read the real condition, and say what you found. Past examples:
   - PD-177 (marker commands in the log) was by design (PD-148), not a bug.
   - PD-176 (`lemon_reply` not cancelling the fallback): the server already cleared the pending
     question; the timeline showed the reply landing after the 45 s window. Mitigated, not "fixed".
   - PD-169 (gear not swapped back) cannot happen from the swap logic, which runs every tick keyed on
     dimension; marked not reproduced, with what log line would settle it.
   - PD-171 (rubble on a required path): real, but the cause was that spawners also come from rooms,
     not only from roles.
2. **Fix the cause, not the symptom.** If a template is the problem, remember `.nbt` files are generated
   and old baked templates need a repair-at-stamp (see PD-164 `SpurToll`, PD-172 `placeHerdGold`).
3. **Add a regression test per fix** in the style previous PDs used (see the `add-tests` skill). Prefer a
   pure test when the rule can be separated from the world, a gametest when it needs a server level.
   Where a rule can regress silently (a list of rooms, a loot table shape), write a test that scans the
   sources or data (`RubbleRulesTest`, `BookLootTest`).
4. **Update `BUGS.md`** in the same change: replace the entry's `**Status:**` line with one of
   Fixed / Half fixed / Mitigated / Not reproduced / Not a bug / Open, the date, what changed, the test
   names, and what remains unverified. Keep the original **Reported** text.

## Priority and scope

Follow the brief's order (usually High before Medium, harness last). Do not widen scope: if you notice a
second problem, note it (`spawn_task` for a separate session) instead of folding it in.

## Verify, then report

Run `gradlew test runGameTest` (the `add-tests` skill has the commands and the pitfalls). Read the
tail for `BUILD SUCCESSFUL` and the gametest line `All N required tests passed`; report N, and how it
changed. Then reply in this shape, one row per PD number:

| PD | What changed | Covering tests | Unverifiable without a live server |

Be explicit about: bugs you could not reproduce, fixes that rest on reading code only, anything left
open, and any design question awaiting the owner. Do not claim a live behaviour you only tested in a
gametest.
