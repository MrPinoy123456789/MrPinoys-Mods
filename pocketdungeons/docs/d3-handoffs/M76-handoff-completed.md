# M76 - Publish the operating envelope - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `src/main/java/pocketdungeons/Instances.java`, methods `previewDoor`, `commitDoor`, `onTick`, `dungeonCellLookupAt`.
- `src/main/java/pocketdungeons/InstanceTeardown.java`, methods `processClears`, `finishClear`.
- `src/main/java/pocketdungeons/InstanceRegistry.java`, allocation and bounds.
- `docs/DISCOVERIES.md` traps 4 and 7 and operational notes.

## Dependencies

```bash
grep -rn "CustodyGameTest\|FloorLoopGameTest\|SituationGameTest\|CubeRecipeGameTest" pocketdungeons/src/gametest/
grep -rn "ContentSnapshot" pocketdungeons/src/main/java/pocketdungeons/
```

First confirms complete custody, floor, visit and recipe test registrations. Second confirms content-snapshot pins are in place. Record hardware, JVM, view distance, content version and entity counts before measurement. If M75 not landed, stop.

## Goal

An operator knows how many simultaneous floors, previews and visits this release can sustain and recover from.

## Implementation plan

Run `build_mod` after each step.

1. **Profile.** Profile 1, 8, 16 and 32 concurrent instances with solo and six-player parties, previews switched rapidly, two-story rooms, Pocket2 and shared visits. Then an eight-hour soak and forced recovery. Track p50, p95 and p99 tick duration, generation and clear latency, heap, loaded chunks and tickets, entity counts, queued work and recovery-log growth. Candidate threshold: p95 below 40 ms and p99 below 50 ms at the advertised cap on declared hardware; publish a lower cap if necessary.
2. **Lookup and clear optimisation.** Measure existing linear lookups and synchronous floor clears before reviving M43 rejected spatial index. If needed, add maintained lookup and occupancy indexes with registration and removal tests and time-budgeted generation and clearing, preserving M63 slot lease until clear completion. Add `src/main/java/pocketdungeons/InstanceWorkQueue.java` only if profiling warrants it.
3. **Bound work.** Bound pending previews, active visits and generation work; refuse or queue before charging fuel or catalysts. Keep all world writes on the server thread. Verify lower-story entities and handler state return to baseline after each cycle. Expose operator diagnostics, not player spam or external telemetry.
4. **Load test task.** Add `dungeonLoadTest` Gradle task with repeatable profiles and a distinct long soak invocation. Register in `build.gradle.kts`.

## Constraints

- Never improve benchmarks by disabling protection, persistence, GameTests or resource floors.
- No unsupported claim that all mixed-mod servers share one capacity number.
- All world writes on the server thread.
- These are measurement points, not promised capacity.

## Verification

`build_mod` with tasks `runGameTest`, `dungeonIntegrationTest`, `packValidationTest`, `dungeonLoadTest`, then `build`. Humans assess preview and door responsiveness at the proposed cap. Done when measured capacity table, overload behaviour and remaining version-specific hot spots are appended.

## Completion

- Append measured capacity table, overload behaviour and remaining hot spots to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Publish operating section in `docs/INTEGRATION.md`.
- Rename this file `M76-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
