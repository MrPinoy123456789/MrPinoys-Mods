# Shared handoff preamble (not a handoff itself)

This file is reference material for whoever maintains the handoffs in this
folder. It is not meant to be pasted into a chat on its own. Each handoff is
self-contained.

## Handoff format

Handoffs follow the format defined in `plans/HANDOFF-SPEC.md`. Read that spec
before writing or updating any handoff. The key rules:

- No "Where this is" section (the plugin covers workspace info).
- No "Standing rules" section (the plugin and `CONVENTIONS.md` cover rules).
- Reading list: 2 to 4 items, each naming a specific section of a file.
- Dependency checks: grep for specific classes, not file reads.
- Constraints: milestone-specific only.
- Implementation plan: exact file/class/method references, one step per
  commit.
- Verification: name the gradle task, distinguish headless from live.
- Completion: append-only for large files.
- Target: under 3 KB, hard limit 5 KB.

## What the plugin covers (do not repeat in handoffs)

The `dsh-fabric-modding` plugin injects: toolchain (Java 25, Loom, Kotlin
DSL, Mojang mappings), project layout, entrypoints and registries, mixin
format, jar verification, build verification via `build_mod`, server-side
constraint, commit style, house style (no em dashes), context discipline, and
the workspace scan (all mods, versions, entrypoints, CONVENTIONS.md
detection).

## What CONVENTIONS.md covers (do not repeat in handoffs)

`pocketdungeons/CONVENTIONS.md` covers: the one-mixin budget, status doc
locations, codec migration discipline, and the stricter no-client-mod
constraint for this mod.

## Dependency graph

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

M23  Room template editor       (independent)
M24  Room shells and prestige   (needs M18)
M25  Pocket2 Dungeon            (needs M11)
M26  Lore delivery              (independent; 26.4 needs jar verification)
M27  Extra features             (27.1 needs M19; 27.2 needs M20, M21; 27.3 deferred)
M28  Themed mob spawners        (independent)
M29  No-backwards propagation    (independent)
M30  Connector variations       (independent)
M31  Dungeon shell protection   (independent, M30 IRON_DOOR benefits from it)
```

M18 is the foundation: the immutable shell, wall lodestone, and template
changes. M19 (physical door selection) depends on M18's furniture protection.
M20 (visiting rework) depends on M18's wall lodestone but can ship before
M21. M21 (UX consolidation) needs all three. M22 (sound) is most valuable
after M19 and M21 land.

## Maintaining these handoffs

If a milestone's scope in `docs/reference/ROOM_UX_PLAN.md` changes
materially, update that milestone's handoff to match. If a milestone lands,
rename its handoff `M{n}-handoff-completed.md`.

When planning a new round of milestones, follow
`plans/HANDOFF-SPEC.md` for every document you write or update.
