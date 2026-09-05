# M70 - Author roles and bags, keep geometry honest - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/VISION.md` section 6.1. Author extensibility promise.
- `src/main/java/pocketdungeons/RoomContent.java`, role dispatch.
- `src/main/java/pocketdungeons/RoomSelector.java`, method `resolveDetailed`, and `src/main/java/pocketdungeons/Bags.java`, methods `byId`, `tagsFor`, `apply`.
- `docs/reference/SITUATIONS_SPEC.md` sections 3.2 and 6.6. Role and capability contracts.

## Dependencies

```bash
grep -rn "AffixDefinition\|ContentSnapshot" pocketdungeons/src/main/java/pocketdungeons/
grep -rn "Bags\.values\|Bags\.valueOf" pocketdungeons/src/main/java/pocketdungeons/
```

First confirms M69 definitions and M68 snapshot are in live consumers. Second enumerates bag enum callers. Enumerate bag UI callers in your completion notes before freezing ownership. If M69 not landed, stop.

## Goal

An author adds a supply-room role and a bag without modifying a role switch or enum.

## Implementation plan

Run `build_mod` after each step.

1. **RoomRoleDefinition and BagDefinition.** Add `src/main/java/pocketdungeons/RoomRoleDefinition.java` and `BagDefinition.java`. Retain structural entrance, terminal and four-bit geometry, but make population roles selectable by stage, depth, weight and masks. A new role composes existing bounded operations: trial encounter, tool cache or no content. Expose roles to assignment as well as dispatch; adding a handler the generator never selects is not extensibility. Bags define presentation, loot table and proven capability declarations. Existing saved class IDs migrate without reselection.
2. **Data files.** Add `src/main/resources/data/pocketdungeons/dungeon_role/*.json` and `dungeon_bag/*.json`. Ship data-only `field_cache` role in a real generated floor and a test-only foreign bag. Validate tag vocabulary, actual guaranteed providers, missing tables, food floor and power ceiling at authoring time and stamp time.
3. **Unknown-ID errors.** Unknown content or role IDs become named validation errors rather than falling through to generic rooms. Keep situation handlers as engine primitives, not a JSON scripting language; extensions cannot mint new topology by naming a role.

## Constraints

- No up or down doors, multi-cell allocation or relaxed gate proof.
- Namespaced capability extensions need registered definitions and validation, not unchecked strings.
- Full-reset class lock stays.
- No new topology by naming a role.

## Verification

`build_mod` with tasks `layoutGraphTest`, `planSelectorTest`, `graphSolvabilityTest`, `situationTagsTest`, `bagSelectionTest`, `bagTableTest`, `runGameTest`, then `build`. External role appears and behaves without Java changes. Done when the distinction between extensible population roles and fixed structural topology is published.

## Completion

- Append architectural summary to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Update `docs/INTEGRATION.md` with role and bag schema sections.
- Rename this file `M70-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
