# M71 - Data recipes with a discovery floor - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/VISION.md` sections 5.4 and 9. Discovery secrecy and the Cube.
- `src/main/java/pocketdungeons/CubeRecipe.java` and M66's `RunRecipePlan`, match and validation paths.
- `src/main/java/pocketdungeons/TaskTracker.java`, task progression.
- `docs/DISCOVERIES.md` traps 14 and 15. Trap 15 is the pending-tag clearing order.

## Dependencies

```bash
grep -rn "RunRecipePlan\|BagDefinition\|RoomRoleDefinition" pocketdungeons/src/main/java/pocketdungeons/
grep -n "recordTheme" pocketdungeons/src/main/java/pocketdungeons/DungeonLog.java
```

First confirms M66 recipe plan and M70 definitions are in generation consumers. Second confirms theme recording in `DungeonLog`. If M70 not landed, stop.

## Goal

A pack author adds a real recipe; a player has a dependable first experiment without being handed the catalogue.

## Implementation plan

Run `build_mod` after each step.

1. **CubeRecipeDefinition and RecipeDiscovery.** Add `src/main/java/pocketdungeons/CubeRecipeDefinition.java` and `RecipeDiscovery.java`. Definitions use namespaced ID, item or tag catalyst predicate, cost, priority, eligibility and M66 typed effects. Retain code station interception because component-aware keystone validation cannot be expressed by ordinary Ingredient matching; no crafting mixin. Add a personal discovered-recipe record, ingredients encountered and completed-theme counts. Do not revive M7 tail matching alongside AdventureGraph without a separate design decision.
2. **Convert built-in recipes.** Convert all nine built-in recipes to definitions. Reject ambiguous matches and impossible guarantees. Demonstrate a foreign recipe combining existing effects with no source edit. Add three shipped experiments: snowball biases Blaze Loft, gold nugget biases Bazaar, slime ball guarantees a tier-eligible Slime Pit. Supply catalysts inside the loop; refuse impossible guarantees before spending. Each uses M66 existing weighted or guaranteed-room operation, not a new Java recipe constant. Preserve paid pending operations on reload or removal via M63 custody.
3. **Discovery floor.** Guarantee a catalyst and a terse "try this at the Cube" opportunity by the first eligible safe visit. Record only successful recipes for the discoverer. Optional handwritten books and cards can carry player knowledge; diaries are never keys or mandatory clues. Add `src/main/resources/data/pocketdungeons/cube_recipe/*.json` for built-in definitions.

## Constraints

- No undiscovered recipe browser or automatic graph map.
- Knowledge transfers by conversation, not server-wide discovery broadcasts.
- Failed experiments must not destroy essential progression supplies.
- No crafting mixin.

## Verification

`build_mod` with tasks `cubeStationTest`, `taskTrackerTest`, `dungeonLogTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Fixtures: new ID, conflicts, removal, early-player opportunity. Extend `DungeonLogTest` with discovered-recipe codec round trips, pre-discovery-save defaults, removed-pack IDs and duplicate discoveries; knowledge survives save and reload without unlocking undiscovered entries. Live: uncoached discovery followed by teaching a friend.

## Completion

- Append recipe schema and personal discovery rules to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Update `docs/INTEGRATION.md` with recipe schema section.
- Rename this file `M71-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
