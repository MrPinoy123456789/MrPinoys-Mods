# Shared handoff preamble (not a handoff itself)

This file is reference material for whoever maintains the eight M10-M17
handoffs in this folder. It is not meant to be pasted into a chat on its own.
Each handoff repeats the parts of this that matter for its milestone rather
than pointing back here, so a handoff stays a single self-contained paste.

## Why these exist

`D3_PROGRESSION_PLAN.md` is the authoritative scope for M10 through M17: goal,
dependencies, touch points, done-when, open questions, jar-verification tasks,
and detailed implementation notes, all per milestone. A handoff does not
repeat that content. Its job is the session-bootstrapping layer the plan does
not do for you: what order to read things in, which prior milestones must
already be real (not just written down) before this one starts, the standing
rules that outrank the plan if they conflict, and the doc updates the session
owes on completion. This mirrors how the retired `handoffs/M9-handoff.md`
pointed at `plans/M9-cleanup.md` as "the authoritative scope" rather than
copying it in.

## What changed since the M0-M9 handoffs

`PROGRESS.md` and the `handoffs/` folder were retired once M0-M9 went
code-complete; see `README.md` and `plans/COMPLETED-MILESTONES.md`. Status for
this plan lives in two places instead of one file: `plans/COMPLETED-MILESTONES.md`
gets a new entry per landed milestone, and `docs/LIVE_TEST_PASS.md` gets a new
numbered section per milestone for whatever cannot be verified headless.
Neither file uses checkboxes; `D3_PROGRESSION_PLAN.md` itself never records
status either, per its own header.

The mod also picked up its first mixin since the M9 handoffs were written
(`mixin/CustomClickMixin`, for dialog button payloads). The "zero mixins"
standing rule from the old handoffs is gone; the current rule is "exactly one,
and that is the budget," recorded as trap 9 in `DISCOVERIES.md`.

## Dependency graph, for reference

```
M10  Ladder reframe
     |
M11  Adventures
     |
M12  Two-tier doors + fuel      (needs M10, M11)
     |
M13  Gear loot pool             (no dependency; can run parallel to M10-M12)
     |
     +-- M14  Gear reroll        (needs M13)
     +-- M15  Armor trims        (needs M13; builds plumbing M17 reuses)
     |         +-- M17  Herobrine Cube   (needs M15 and M11)
     +-- M16  Kadala gamble      (needs M13)
```

M13 is the long pole for the sink milestones and is content only, no Java: it
can and should start immediately, in parallel with M10-M12. M15 before M17 is
a code dependency, not a scheduling preference: M17 literally reuses M15's
equip-time attribute plumbing.

## Maintaining these handoffs

If a milestone's scope in `D3_PROGRESSION_PLAN.md` changes materially (a new
dependency, a scope cut, a done-when change), update that milestone's handoff
file's "before you start" and "goal" sections to match. If a milestone lands,
rename its handoff `M{n}-handoff-completed.md` the way the old scheme did, so
a glance at the folder shows what is left.
