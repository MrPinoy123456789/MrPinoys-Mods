# M73 - Six new identities from the existing geometry - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `pocketdungeons/CONVENTIONS.md`, server-only section. First milestone in Round III; re-read.
- `docs/reference/VISION.md` sections 3.6.1 and 5.1 to 5.3. Theme identity and provenance.
- `docs/INTEGRATION.md`, processor precedence, affix and adventure schemas.
- `src/main/java/pocketdungeons/TemplateStamper.java`, method `resolveProcessors`.

## Dependencies

```bash
grep -n "packValidationTest" pocketdungeons/build.gradle.kts
grep -rn "dungeon_theme" pocketdungeons/src/main/resources/data/pocketdungeons/
```

First confirms M72 validation task is registered. Second confirms the five current theme nodes remain reachable. If M72 not landed, stop. Parallel with M74; no foreign-file edits.

## Goal

Double the recognisable composition space without multiplying template files.

## Implementation plan

Run `build_mod` after each step. No `*Specs.java`, generic loot tables or M74 `situation_*` spawners in this milestone.

1. **Three reviewed pairs.** Ship Rootworks and Frostworks: overgrowth versus footing. Ship Copper Works and Ossuary: mechanisms versus ranged threats. Ship Basalt Foundry and Ender Archive: heat versus displacement and darkness. Add `src/main/resources/data/pocketdungeons/dungeon_theme/{rootworks,frostworks,copper_works,ossuary,basalt_foundry,ender_archive}.json`, matching `dungeon_adventure` files, `worldgen/processor_list/theme_*`, `trial_spawner/theme_*` and `loot_table/themes/*`. Every identity has palette, roster, tool economy, a rare decorative signature and at least one meaningful graph transition.
2. **Decorative processors.** Create processors that target decorative shell blocks only, excluding mechanisms, return routes, doors and provider blocks. Reuse existing `.nbt` geometry; exercise room-specific processor precedence and layered ominous loot.
3. **Data-only affixes.** Add `jumpy` and `clingy` affixes as data-only definitions using M69 operations. `Jumpy` trades a breeze-heavy roster for wind charges; `Clingy` trades webs for guaranteed shears and recoverable string. If either needs a new operation, defer it rather than smuggle engine work into data authoring.
4. **Review each pair before the next.** Across twelve floors per theme, identify the decisions that differ, not just colour. Target six new themes and two new affixes, but reject a pair that plays identically; catalogue count is not the acceptance criterion.

## Constraints

- No sky-themed promise in an End-effects void.
- No new gear tier.
- Decoration comes from repeatable dungeon sources; rare treasure remains weighted.
- No new `*Specs.java` or situation spawners; M74 owns those.

## Verification

`build_mod` with tasks `packValidationTest`, `adventureGraphTest`, `trialContentConfigIdTest`, `graphSolvabilityTest`, `runGameTest`, then `build`. Human: theme recognition, recipe routes and decision comparison. Done when each theme's supply and provenance role and reviewed composition evidence are appended.

## Completion

- Append each theme's supply, provenance role and composition evidence to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Append theme rows to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M73-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
