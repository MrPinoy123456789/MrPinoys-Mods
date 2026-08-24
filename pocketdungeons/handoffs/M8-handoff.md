# M8 — Deferred — Handoff

> Paste this whole file into a fresh chat to start work on **one** item promoted
> out of M8. This handoff is a template, not a single milestone — M8 has no
> "done" state of its own; each item inside it graduates independently, on
> purpose, whenever someone with authority decides it should.

## Before you do anything else

**M8 items may not be started without an explicit promotion decision from the
person directing this work.** If you were handed this file without being told
which item, and which specific reason-for-deferral no longer applies, **stop
and ask** rather than picking one yourself. `plans/M8-deferred.md` exists
precisely to stop these from being picked up casually.

If you *were* given a specific item and a stated reason it's now ready, write
that reason down in `plans/M8-deferred.md`'s promotion checklist before writing
any code — that record is what stops the next person re-deferring it by
accident, or re-deferring something else for the same now-resolved reason.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). Work happens in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order:

1. `pocketdungeons/VISION.md` and `pocketdungeons/MYTHIC_PLUS_RECONCILIATION.md`
   — general context; search both for your specific item, several are discussed
   at length outside `plans/M8-deferred.md` (e.g. outdoor themes in `VISION.md`
   §5.3, rule-breaking dungeons in §5.5)
2. `pocketdungeons/ROADMAP.md` — M8's place in the overall order
3. `pocketdungeons/plans/M8-deferred.md` — **read the entry for your specific
   item in full**, including its stated reason for deferral
4. `pocketdungeons/PROGRESS.md` — find the `## M8` table

## Standing rules (outrank everything else if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   blocks, items or registry entries.
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4).
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it.

## Process

1. Confirm the promotion decision exists and is written down (see above).
2. Write your own `plans/M<item>-<name>.md` for the promoted item, following the
   structure of `plans/M0-entry-fee.md` through `plans/M7-recipes.md` — verified
   API facts, hazards, scope table, "Done when." M8's own file only has a short
   paragraph per item; a promoted item needs the same depth of plan every other
   milestone got before it started.
3. Add a section to this milestone's row in `pocketdungeons/PROGRESS.md`
   pointing at your new plan file, same as `M0`–`M7`'s rows do.
4. Implement per your new plan.
5. Verify per your plan's own "Done when," using `tools/rcon.py` for anything
   with in-world behavior — see `f7345eb` and the M0–M7 handoffs for the level
   of positive/negative proof expected. Do not accept "it compiles" as done for
   anything touching world state or player-visible behavior.
6. Update docs:
   - `PROGRESS.md`: your item's row → `DONE` with commit hash and what was
     verified. Session Log line.
   - `plans/M8-deferred.md`: **remove the promoted item from the deferred
     list**, or mark it promoted with a pointer to its new plan file — don't
     leave a stale entry that reads as still-blocked.
   - `ROADMAP.md`: if this item is now a real milestone in the sequence rather
     than a one-off, add it to the roadmap in its correct position.
7. Commit, following this repo's commit style (`git log`, `f7345eb`).
8. **Last step:** since M8 is not a single unit of work, do **not** rename this
   file to `-completed` when one item is promoted and finished — other items
   likely remain deferred. Instead, note in your commit message and in
   `PROGRESS.md`'s Session Log which specific item was promoted and completed.
   Only rename `handoffs/M8-handoff.md` → `handoffs/M8-handoff-completed.md` if
   you are the one closing out the **last** remaining item in
   `plans/M8-deferred.md` — check the file is actually empty of open items
   before doing that.

## The items, and why each is currently held

| # | Item | Held because |
|---|---|---|
| D1 | Outdoor themes | Needs a **second dimension** — sky is per-dimension (`has_skylight: false`, `effects: minecraft:the_end`) and `Instances` is built around one level, one slot grid |
| D2 | One rule-breaking dungeon | Not data — bends `LayoutGraphGenerator` invariants; pick **the Endless Mine only**, hold the rest until it ships |
| D3 | Data-driven affixes | Wait until 5–6 affixes exist in Java (M4) and the varying knobs are actually known |
| D4 | Multi-cell footprints | `LayoutGraphGenerator` is 1×1 only; the field is parsed and silently ignored today |
| D5 | Room size as progression, station unlocks | Downstream of M2 (room must exist) and M6 (stations must be the economy first) |
| D6 | Lava as a second faucet | Molten (M4) is already the only faucet; widen only if M6 shows it actually pinches |

Full detail, including hazards specific to each, is in
`plans/M8-deferred.md` — this table is a locator, not a substitute for reading
that file's actual entry before starting.
