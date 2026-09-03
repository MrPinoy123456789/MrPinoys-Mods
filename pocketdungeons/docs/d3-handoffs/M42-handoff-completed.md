# M42 - Half-built feature content and design work - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `docs/reference/AUDIT_FOLLOWUP_PLAN.md`'s `## M42` section only. Six
   subsections, each with its decision already recorded (2026-08-31) and
   the implementation approach stated.
2. `docs/reference/BUGS.md`: sections `### PD-19` and `### PD-32` only.
   These are the code-side halves already handled in M37/M38; this
   milestone is the content and design half.

## Dependencies

None. Reward hall/selector room (originally 42.1) was cut, moved to M40.4.

Grep `dungeon_adventure/` before 42.3: confirm still four files
(`blackstone`, `deepslate`, `drowned_vault`, `prismarine`).

## Goal

Implement the five findings the mod owner decided to ship; record the one
they decided to leave. Decisions settled, this is implementation.

## Implementation plan

Run `build_mod` after each step. Five commits (42.7 needs no commit,
fold its note into any nearby commit's message or skip it).

1. **42.2, room-theme pairing.** Assign `theme` to each of the fifteen
   `dungeon_room/*.json` files and `room_theme` to each of the five
   `dungeon_theme/*.json` files. Start with `deepslate` pulling
   `crypt_corner` (it already sets `spawner_prefix: "crypt"`, unlinked
   today). Leave generic rooms (halls, corners, dead ends with no theme
   affinity) with `theme` unset so every theme can still select them, per
   `RoomManifest.matchesTheme`'s existing fallback.

2. **42.3, infestation adventure node.** Author
   `dungeon_adventure/infestation.json` following the shape of
   `blackstone.json`/`deepslate.json`/`prismarine.json` (not
   `drowned_vault.json`, which is boss-tier). Infestation ships tiers 1
   through 3 trial-spawner configs, so slot it as a mid-graph node with
   normal transitions in and out, not a terminal boss node.

3. **42.4, themed-ominous loot.** Restructure
   `TrialContent.resolveLootTable`: layer a themed bonus pool onto the
   base ominous table at roll time instead of a named
   `tier_N_ominous_<suffix>` table per theme. Resolve base table as
   today; if ominous and theme defines a bonus pool (follow
   `LootTables`' existing ominous-modifier pool pattern), add it. Verify
   loot table composition API against the jar if new to this file.

4. **42.5, fix `CubeStation.sortedUnlocked`.** Javadoc promises "every
   power unlocked but not yet worn out an equip cap"; body only sorts.
   Implement the filter via `PowerEquipMath.activePowers`'s existing cap
   logic, so the picker never offers a power `PowerListener` declines. No
   new powers in this commit.

5. **42.6, adventure-graph dedup.** `AdventureGraph.pick`: dedupe the
   expanded transition list by theme before taking top three, matching
   `BountyTracker.bountiesFor`'s dedupe-then-shuffle pattern. Fall back
   to a repeat only when the node has fewer than 3 distinct transitions.
   Add a 4+-transition, 2-theme test case to `AdventureGraphTest.java`.

6. **42.7, no code.** Note in step 5's commit message (or
   `COMPLETED-MILESTONES.md`'s M42 entry) that offline party-companion
   removal was reviewed and left as-is, so a future audit does not
   re-flag it.

## Constraints

- 42.2 and 42.3 are content authoring. Follow the existing JSON shapes
  exactly; do not extend the schema.
- No custom blocks, items, or registry entries (`CONVENTIONS.md`).
- 42.5's filter must not change which powers are *grantable*, only which
  are *offered* by the picker. `PowerListener`'s activation logic is
  unchanged.

## Verification

42.2: several seeds per theme, room mix visibly differs, not uniform.
42.3: seed-search the adventure graph, infestation appears as a normal
door offer. 42.4: ominous `drowned_vault` run shows the themed loot
bonus. 42.5: filter logic checked against `PowerEquipMath`'s existing
coverage, excludes a power that would exceed the equip cap. 42.6:
`AdventureGraphTest.java`'s new dedup test passes, plus a manual seed
sweep of a 3+ transition node shows no repeats.

42.2 through 42.4 need a live server; 42.5 and 42.6 are headless.

## Completion

Append the architectural summary to `plans/COMPLETED-MILESTONES.md`, same
style as M30 through M34, recording all six outcomes including 42.7's
"left as-is" note.

Mark PD-19 and PD-32 in `docs/reference/BUGS.md` as fixed once 42.2 and
42.4 land.

Rename this file to `M42-handoff-completed.md`.
