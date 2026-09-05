# M66 - Make every Cube promise executable - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/SITUATIONS_SPEC.md` section 7. Recipe effects and guarantees.
- `src/main/java/pocketdungeons/CubeRecipe.java`, methods `match`, `apply`, `recipesOf`, `clearRecipes`.
- `src/main/java/pocketdungeons/Instances.java`, methods `previewDoor` and `commitDoor`.
- `docs/DISCOVERIES.md` traps 14 to 16. Trap 15 is the pending-tag clearing order.

## Dependencies

```bash
grep -rn "RunSession" pocketdungeons/src/main/java/pocketdungeons/RunLifecycle.java
grep -n "CubeRecipe.match" pocketdungeons/src/main/java/pocketdungeons/CubeStation.java
grep -rn "recipeTags" pocketdungeons/src/main/java/pocketdungeons/
```

First confirms M65 `RunSession` is in the lifecycle. Second confirms `CubeStation` uses `CubeRecipe.match`. Third enumerates all `recipeTags` readers, not just the declaration. If M65 not landed, stop.

## Goal

A consumed catalyst changes exactly the floor previewed, or remains recoverable without charging for nothing.

## Implementation plan

Run `build_mod` after each step. This milestone owns recipe planning; M65 owns lifecycle state. Ownership transfers explicitly from M65.

1. **RunRecipePlan.** Add `src/main/java/pocketdungeons/RunRecipePlan.java` as an immutable request resolved before planning. Supported effects: ominous, Feral, infested-room guarantee, flooded or chasm weighting, tier-eligible Deep Dark guarantee, completion study list, Store spur, bounded supply, +2 path length. Conflict order is explicit; an impossible guarantee refuses before consumption. Class identity never changes. Add `src/gametest/java/pocketdungeons/CubeRecipeGameTest.java` with `previewFreezesRecipeMembership`.
2. **Preview and commit.** Read pending recipe data before clearing it; carry seed, offer, affixes, party capabilities and recipe revision through preview and commit. Re-preview when any relevant input changes. A second click on the same offer does not farm random entrances. Keystone watcher reconciliation preserves pending work. `CubeRecipeGameTest.previewFreezesRecipeMembership` asserts exact recipe ID, effect set and seed in `RunRecipePlan` survive unrelated record changes; changed catalyst, party capability or reload invalidates the preview before spending.
3. **Wire effects.** Wire all promised effects into `LayoutPlanner.plan`, `RoomSelector.resolveDetailed` and completion reporting. Use tagged recovery escrow for catalysts, spend exactly once on successful commit, restore on refusal or cancellation through M63 transaction boundary. Unknown or contradictory tags are visible to staff, never silently spent. Test failure, retry and watcher reconciliation.
4. **Replace BAG_OVERRIDE.** Replace with `keystone + string`: one guaranteed tool cache, not a new class or full kit. Supply catalyst in the dungeon. Preserve and decode legacy tags; old `BAG_OVERRIDE` becomes a recoverable pending legacy operation for owner-approved refund, never silently reinterpreted.
5. **Replace DOUBLE_KEY.** Replace with `keystone + amethyst shard`: +2 rooms at the committed offer's level. Supply catalyst in the dungeon. `DOUBLE_KEY`'s lost lower-key level was never stored; do not invent it. Migrate to current-level extension only with explicit notice and refund choice.

## Constraints

- No gear imbue expansion, crafting mixin or alternate authoritative keystone.
- Fixed-catalyst and headline collisions are tested.
- A guarantee is part of the solvability proof, not a post-plan replacement.
- No public recipe catalogue yet.
- Nine behavioural fixtures assert actual stamped effect or rejection.

## Verification

`build_mod` with tasks `cubeStationTest`, `graphSolvabilityTest`, `keystoneOfferTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Headless: nine behavioural fixtures pass, preview membership frozen, failure and retry conserve catalyst. Live: human verifies catalyst use and study-list meaning.

## Completion

- Append recipe effect coverage and legacy pending-item migration to `plans/COMPLETED-MILESTONES.md`, including costs that cannot be reconstructed. Do not re-read the whole file.
- Append recipe rows to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M66-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
