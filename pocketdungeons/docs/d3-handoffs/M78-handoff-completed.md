# M78 - The Endless Mine, bounded in memory - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `pocketdungeons/CONVENTIONS.md`, server-only and migration sections. First milestone in Round IV; re-read.
- `docs/reference/VISION.md` section 5.5. The rule-breaking dungeon promise.
- `docs/reference/ROADMAP.md`, M8 Deferred. The original deferred item this milestone unholds conditionally.
- M65's lifecycle and M76's measured queue and slot contract in `plans/COMPLETED-MILESTONES.md`. Read only those two entries, not the whole file.

## Dependencies

```bash
grep -rn "RunSession" pocketdungeons/src/main/java/pocketdungeons/RunLifecycle.java
grep -rn "InstanceWorkQueue\|dungeonLoadTest" pocketdungeons/src/main/java/pocketdungeons/ pocketdungeons/build.gradle.kts
```

First confirms M65 `RunSession` transition methods exist. Second confirms M76 bounded work-queue and slot leases and `dungeonLoadTest` recovery fixtures exist. If M77 adoption evidence is absent or owner approval and repeat-player demand are missing, stop. This milestone may remain unbuilt.

## Goal

Prove one special dungeon rule without maintaining an infinite loaded corridor.

## Implementation plan

Run `build_mod` after each step. Freeze exact shared caller ownership when this handoff is written; `RunLifecycle`, `RunSession`, `LayoutPlanner`, `InstanceRegistry` and `DungeonLog` are shared with M65 and M76 contracts.

1. **EndlessMineRules.** Add `src/main/java/pocketdungeons/EndlessMineRules.java`. A discovered recipe opens a Mine with no compulsory final floor: each checkpoint offers continue or a safe exit. This interprets "keep extending" as unbounded sequential floors, not an infinitely retained world. Only the current floor, transition and bounded prepared work remain loaded; previous floors cannot be revisited. Current-floor topology and return-path guarantees stay unchanged. The special rule is optional cash-out frequency and escalating local composition, not an inventory-wipe survival mode or leaderboard.
2. **Separate transition policy.** Implement the special transition policy separately from normal three-floor settlement. Publish risk and reward rules at the commitment surface without explaining spatial movement; no silent consequence changes. Apply M63 journal recovery and M65 silent physical exit.
3. **Mine reward.** Reward a displayable Mine record or material, not a higher permanent power ceiling. Compare ordinary-loop value against the Mine to prevent it becoming the only sensible supply route. Add dedicated adventure, theme and recipe data and lifecycle and load fixtures.

## Constraints

- No second dimension, infinite resident chunks, ranked depth, compulsory lore finale or expansion into Inversion, Labyrinth or Descent.
- If demand is absent, leave M78 unbuilt.
- Previous floors cannot be revisited.
- No silent consequence changes at the commitment surface.

## Verification

`build_mod` with tasks `floorShapeTest`, `graphSolvabilityTest`, `runGameTest`, `dungeonIntegrationTest`, `dungeonLoadTest`, then `build`. Human: voluntary cash-out plus long-run constant-residency evidence. Done when it is appended whether the exception improved repeat play enough to retain. No automatic sequel milestones.

## Completion

- Append whether the exception improved repeat play enough to retain to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Append Mine rows to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M78-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
