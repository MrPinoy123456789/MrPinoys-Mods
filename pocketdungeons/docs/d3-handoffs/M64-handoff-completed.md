# M64 - Prove rooms as played, not merely selected - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/SITUATIONS_SPEC.md` sections 0, 6.4, 6.6 and 13.4. Section 13.4 is an invariant, not advice.
- `docs/reference/SITUATIONS_AUDIT.md`, per-situation verdict table.
- `docs/ROOM_FIXES.md`, all three rooms. Describes invalid circuits and assumptions behind the working-tree redesigns.
- `docs/DISCOVERIES.md` traps 6, 20 and 21. Trap 20 is the doorway-plane write failure.

## Dependencies

```bash
grep -n "resolveDetailed" pocketdungeons/src/main/java/pocketdungeons/RoomSelector.java
grep -n "validate" pocketdungeons/src/main/java/pocketdungeons/ReturnPathValidator.java
grep -rn "register" pocketdungeons/src/main/java/pocketdungeons/RisingLavaHandler.java pocketdungeons/src/main/java/pocketdungeons/CollapsingBridgeHandler.java
grep -n "gatedSpawners" pocketdungeons/src/main/java/pocketdungeons/TrialContent.java
```

Confirms selector, return-path validator, both handlers and trial content gating exist. If any absent, stop. Integrate after M63 merges.

## Goal

Every offered situation has a physically reachable answer and an honest resource contract.

## Implementation plan

Run `build_mod` after each step.

1. **Failing tag-vs-supply fixtures.** Build minimal fixtures for declared tags versus actual supplies: Sapper's TNT is not reusable masonry; Shepherd's leads are not a guaranteed creature; both players must get through Plate Pair. Validate finite tools and return containers. A spent optional tool may lose treasure, never the mandatory exit: require renewable provider or tool-free fallback. Register `src/gametest/java/pocketdungeons/SituationGameTest.java` with `spentOptionalToolStillHasExit`.
2. **Rotation and handler tests.** Stamp all rotations under relevant processors and affixes. Exercise `Locks.tick`, `RisingLavaHandler.tick`, `CollapsingBridgeHandler.tick`, `ReturnPathValidator.validate`: approach from either side, departure/re-entry, later block/bucket updates, occupied piston extension, duplicate registration, purge, slot reuse. Check connected climb route and headroom, not isolated ladder. Exercise `OmenSources.arm/spurTaken` with partial loot, inserted junk, initially empty and alternate containers.
3. **Supply separation.** Split guaranteed consumable supply from weighted treasure in `src/main/resources/data/pocketdungeons/loot_table` paths. Establish food/light per accessible floor and room-engine bootstrap supplies without an overworld visit. Preserve tool scarcity. Correct metadata and template circuits only where reproduced tests require it.
4. **Coverage sweep.** Extend `GraphSolvabilityTest` with seeds 0 to 499, solo Pilgrim, every admitted tier. Zero unresolved plans or inaccessible mandatory exits; failure names seed/cell/provider chain. Record that `RoomSelector.preferred` relaxes minDepth/maxPerDungeon today; never use preferences as a safety gate.

## Constraints

- Do not mass-replace redstone with handlers. Preserve working-tree Gallery, bridge and lava redesigns.
- Voided must not delete the only required route or turn Slime Pit's lower chamber into an unadvertised exception.
- Optional consumable vaults never become mandatory capability providers.
- No new catalogue expansion in this milestone. Correct metadata only where tests require.
- Default fallback is a genuinely passable room, not silent progression loss.

## Verification

`build_mod` with tasks `graphSolvabilityTest`, `planSelectorTest`, `bagTableTest`, `dungeonRoomMetaTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Headless: 500-seed sweep passes, `SituationGameTest.spentOptionalToolStillHasExit` passes. Humans record three-way usefulness and readability. Done when Q7 has per-room verdicts and every false guarantee has a regression.

## Completion

- Append Q7 evidence and exceptions to `docs/reference/SITUATIONS_AUDIT.md` and `docs/reference/LIVE_TEST_PASS.md`. Do not re-read either whole file.
- Append architectural summary to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Rename this file `M64-handoff-completed.md`.
- Distinguish graph proof from player mastery in the summary.
