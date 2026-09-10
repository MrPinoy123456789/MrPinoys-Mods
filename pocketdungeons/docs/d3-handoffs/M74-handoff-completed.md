# M74 - Six situations, two at a time - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/SITUATIONS_SPEC.md` sections 0, 4 and 13. Situation invariants and admission rules.
- `docs/reference/SITUATIONS_AUDIT.md`, M64 verdicts. Per-room baseline before adding new rooms.
- `docs/DISCOVERIES.md` traps 20 and 21. Trap 20 is the doorway-plane write failure.
- `src/main/java/pocketdungeons/KnowledgeSpecs.java`, method `slimePit`, and `src/main/java/pocketdungeons/ReturnPathValidator.java`, method `validate`.

## Dependencies

```bash
grep -n "spanY(2)" pocketdungeons/src/main/java/pocketdungeons/KnowledgeSpecs.java
grep -rn "spentOptionalToolStillHasExit" pocketdungeons/src/gametest/
```

First confirms M61 spanY=2 is in `KnowledgeSpecs`. Second confirms M64 physical regression IDs are registered. If M72 not landed or M64 not integrated, stop. Parallel with M73; no foreign-file edits. Integrator owns handler registration if a reviewed event-backed handler is unavoidable.

## Goal

More situations worth remembering, using private lower stories rather than a new layout engine.

## Implementation plan

Run `build_mod` after each step. Build two rooms, capture once through the integrator, test all rotations, then play before approving the next pair.

1. **Pair 1: Sump and Ropewalk.** Sump offers water and current redirection with a dry stair return. Ropewalk offers a high crossing and a slower lower path. Add `src/main/resources/data/pocketdungeons/dungeon_room/{sump,ropewalk}.json`, matching `trial_spawner/situation_*` and `loot_table/situations/*`. Use `spanY: 2` for at most Sump and Ropewalk; prove permanent return path after all processors and hazards.
2. **Pair 2: Sorting Floor and Sensor Gallery.** Sorting Floor routes a returned item through water. Sensor Gallery lets wool or a thrown distraction turn sensors against mobs. Add corresponding room, spawner and loot files. Each owes doorway readability, two useful solutions, a real slow path and two-of-three utility.
3. **Pair 3: Kennel Crossing and Blaze Cellar.** Kennel Crossing offers a steerable contained hazard with a tool-free bypass. Blaze Cellar offers the water or snowball advantage over a lower-story threat with a permanent stair. Use `spanY: 2` for Blaze Cellar; prove permanent return path. Add corresponding files.
4. **Measure and constrain.** Measure repeated situation pairs and pressure streaks in real plans. Add at most a bounded composition constraint in a later serial selector change if evidence requires it; never invalidate guaranteed providers simply to avoid repetition.

## Constraints

- No extra door-mask bits, footprint enlargement or decorative reskin counted as a new puzzle.
- M73 owns theme spawners; this milestone owns situation spawners only.
- Do not claim hypothetical mob or redstone behaviour without jar and live confirmation.
- Each room owes doorway readability, two useful solutions, a real slow path and two-of-three utility, with explicit supply and return contracts.

## Verification

`build_mod` with tasks `graphSolvabilityTest`, `dungeonRoomMetaTest`, `runGameTest`, `packValidationTest`, then `build`. Integrator captures and runs cross-theme, cross-affix and cross-rotation checks. Humans test each pair before continuing. Done when six per-room verdicts are appended or rejected candidates are explained. Do not force the target count.

## Completion

- Append six per-room verdicts or explain which candidates failed admission to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Append situation rows to `docs/reference/LIVE_TEST_PASS.md` and `docs/reference/SITUATIONS_AUDIT.md`. Do not re-read either whole file.
- Rename this file `M74-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
