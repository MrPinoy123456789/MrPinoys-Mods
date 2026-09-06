# M65 - One floor lifecycle, one silent homecoming - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/SITUATIONS_SPEC.md` sections 5.4 and 12.3 to 12.7. Settlement and floor-loop invariants.
- `docs/reference/VISION.md` sections 3.1 and 4. The physical homecoming and the loop it serves.
- `src/main/java/pocketdungeons/RunLifecycle.java`, methods `completeRun`, `completeDungeon`, `returnToSafe`, `resetForNextDungeon`.
- `src/main/java/pocketdungeons/Instances.java`, methods `commitDoor`, `onTick`, join and startup recovery.

## Dependencies

```bash
grep -rn "CustodyGameTest\|SituationGameTest" pocketdungeons/src/gametest/
grep -n "floorIndex\|floorOmens\|clearPreviousRunState" pocketdungeons/src/main/java/pocketdungeons/InstanceRecord.java
```

First confirms M63 and M64 tests are registered. Second confirms floor-state fields in `InstanceRecord`. Owner must resolve restart and abandonment policy before this starts.

## Goal

Each floor advances once; the safe visit banks once; the last door opens onto the player's actual room without announcing it.

## Implementation plan

Run `build_mod` after each step. This milestone owns the lifecycle state machine; M66 depends on it. No concurrent edits to `RunLifecycle.java` or `Instances.java` from another agent.

1. **RunSession.** Add `src/main/java/pocketdungeons/RunSession.java` with explicit HOME, PREVIEW, ACTIVE, FLOOR_CLEARED, SAFE_RETURN and RECOVERY transitions. Existing `completeRun/completeDungeon/commitDoor/returnToSafe` become callers of that transition model. `advanceFloor` is a proposed extraction. Separate floor loot and theme observations from interval-level key settlement, prestige and payout hooks. Validate phase before membership, visiting, protection, reset and reward work. Add `src/gametest/java/pocketdungeons/FloorLoopGameTest.java` with `transitionTableRejectsIllegalEdges` and `safeVisitSettlesExactlyOnce`.
2. **Remove hidden overtime.** Remove ordinary-floor timeout depletion from `Instances.onTick`; keep elapsed time and a distinct explicit Pocket2 countdown. Apply omen bands to completed-floor count only at final settlement: +1/+1/+0. Update `BountyTracker` timed-run objective to low-omen completion. Test repeats, later party pad contacts, floor resets and every outcome at floor counts 1, 3 and 5.
3. **Staging readiness.** Enforce staging readiness for every advance, not only when `roomCellOrigin != null`. Handle offline and late members without erasing custody record. Preview and commit use same generation request. Use conservative live capabilities from participating party, never original bag enum as proof on later floors. Visitors are not runners; `VisitService` tests HOME explicitly rather than `awaitingDoorChoice`.
4. **Silent homecoming.** Reserve a free in-slot cell behind the final staging door. Stamp and validate the saved room there while the door is closed, then open it for a physical walk. Do not teleport, play `Chime.roomRelocated`, explain the move, or clear occupied floor cells before safe arrival. Persist before clear; failed home stamp leaves staging usable. Release floor after all members cross or an explicitly handled exit. Clear accumulated omen only after settlement. Close restart route through M63 recovery, never seed-rebuild looted rooms.

## Constraints

- No new dimension, no safe-room stash during ACTIVE, no forced client loading screen.
- No penalty changes by implication. Owner settles stakes before this milestone starts.
- Keep three run doors and the M61 lower-story contract.
- Preserve earned legacy levels, gear and skins.
- Normal completion feedback separated from the silent spatial reveal.

## Verification

`build_mod` with tasks `omenMathTest`, `bountyTrackerTest`, `taskTrackerTest`, `pocket2Test`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Headless: every `RunSession` edge and duplicate or reordered completion request rejected or accepted correctly. Live: human walks final homecoming with a recognisable furnishing and a party; records zero teleport, sound or explanation at the reveal.

## Completion

- Append approved settlement and restart table to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Append supersession mapping for old live clock and homecoming checks to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M65-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
