# M69 - Affixes that pack authors can actually add - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/MYTHIC_PLUS_RECONCILIATION.md` sections 4, 5.0 and 7.1. Affix design reasoning.
- `src/main/java/pocketdungeons/AffixMath.java`, methods `seededFor`, `effective`, `name`.
- `src/main/java/pocketdungeons/RoomContent.java`, method `apply`, and `src/main/java/pocketdungeons/TrialContent.java`, method `applyEncounter`.
- `docs/INTEGRATION.md`, M68 version and reload contract.

## Dependencies

```bash
grep -rn "ContentSnapshot" pocketdungeons/src/main/java/pocketdungeons/
grep -rn "Affix\.\|EnumSet<Affix>\|Set<Affix>" pocketdungeons/src/main/java/pocketdungeons/
```

First confirms M68 snapshot is in loaders and preview consumers. Second enumerates every affix-typed reference before changing types. Freeze the exhaustive caller list in your completion notes. If M68 not landed, stop.

## Goal

A new namespaced affix works without adding a Java enum constant.

## Implementation plan

Run `build_mod` after each step.

1. **AffixDefinition and AffixEffects.** Add `src/main/java/pocketdungeons/AffixDefinition.java` and `AffixEffects.java`. Definitions contain ID, label, blurb, stable order, eligibility, weight, incompatibilities, bounded parameters, curse and gift. Initial operations cover trial count, cooldown and range, validated roster overrides, ominous state, neutral wolf spawn, bounded interior hazard placement, consumable rule, guaranteed bonus tool and decor pools. Define operation order and protected route and provider volumes. Existing behaviours become built-in definitions.
2. **Unify post-content affix phase.** `RoomContent.apply` currently returns early for situation handlers, so effects must explicitly apply or be declared incompatible rather than silently disappear. Migrate legacy names to IDs. Freeze built-in ordering and seeding with fixtures. Keep max depletion capped at 2. Pin a content revision for a presented offer; affixes cannot change underneath a watcher or preview. Pool updates take effect only at a documented new-offer boundary.
3. **Data-only Loaded affix.** Add `Loaded`: more trial bodies with a guaranteed bonus tool pool. Rework Molten and Explosive gifts honestly. Voided needs a real collectible gift and route exclusions. Silenced must not remove a room's only milk or food-dependent survival route. Validate selected combinations after hazards and processors, not only before stamping.
4. **JSON schema and data.** Add `src/main/resources/data/pocketdungeons/dungeon_affix/*.json` for built-in definitions. JSON that only renames an existing enum fails acceptance.

## Constraints

- No arbitrary command or script effects, custom attributes or client assets.
- No weekly affix rotation.
- Do not make all bags equivalent to accommodate unsafe affixes.
- Genuinely new operations still require reviewed engine work.

## Verification

`build_mod` with tasks `affixMathTest`, `keystoneMathTest`, `keystoneOfferTest`, `difficultyProfileTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Fixtures: old names and seeds, new third-party ID, exclusion combinations, reload. Live: curse and gift comprehension. Done when supported operations and extension limits are documented. This is the first no-compile affix gate.

## Completion

- Append supported operations and extension limits to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Update `docs/INTEGRATION.md` with affix schema section.
- Rename this file `M69-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
