# Shared handoff preamble (not a handoff itself)

This file is reference material for whoever maintains the M18-M22
handoffs in this folder. It is not meant to be pasted into a chat on its
own. Each handoff repeats the parts of this that matter for its milestone
rather than pointing back here, so a handoff stays a single self-contained
paste.

## Why these exist

`ROOM_UX_PLAN.md` is the authoritative scope for M18 through M22: goal,
dependencies, scope, touch points, done-when, open decisions, and
verification notes, all per milestone. A handoff does not repeat that
content. Its job is the session-bootstrapping layer the plan does not do
for you: what order to read things in, which prior milestones must
already be real (not just written down) before this one starts, the
standing rules that outrank the plan if they conflict, and the doc
updates the session owes on completion.

## What came before

M0-M17 (the D3 progression plan: ladder reframe, adventures, two-tier
doors, fuel, gear loot, reroll, trims, gamble, Herobrine Cube) are all
code-complete. See `plans/COMPLETED-MILESTONES.md` for the architectural
summary of each. `docs/LIVE_TEST_PASS.md` carries the outstanding live
verification items for those milestones.

M18-M22 are the Room UX pass: the room's shell becomes immutable, door
selection goes physical (no dialogs), the calling card is replaced by a
lobby directory, all lodestone interactions collapse into one right-click
menu, and a sound pass adds audio feedback. These milestones have a
strict dependency chain and must land in order.

## Dependency graph, for reference

```
M18  Room shell pass
     |
M19  Physical door selection    (needs M18)
     |
M20  Visiting rework            (needs M18; can ship before M21)
     |
M21  UX consolidation           (needs M18, M19, M20)
     |
M22  Sound pass                 (needs M19, M21; most valuable after both)
```

M18 is the foundation: the immutable shell, wall lodestone, and template
changes that everything else builds on. M19 (physical door selection)
depends on M18's furniture protection. M20 (visiting rework) depends on
M18's wall lodestone but can ship before M21. M21 (UX consolidation)
needs all three before it, since the menu replaces interactions from
each. M22 (sound) is most valuable after M19 and M21 land, since they
add the interactions that most need audio feedback.

## Maintaining these handoffs

If a milestone's scope in `ROOM_UX_PLAN.md` changes materially (a new
dependency, a scope cut, a done-when change), update that milestone's
handoff file's "before you start" and "goal" sections to match. If a
milestone lands, rename its handoff `M{n}-handoff-completed.md` so a
glance at the folder shows what is left.
