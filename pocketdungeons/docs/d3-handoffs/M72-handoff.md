# M72 - The first external pack, not another internal demo - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `pocketdungeons/CONVENTIONS.md`, status-document section.
- `docs/INTEGRATION.md`, complete M68 to M71 contract.
- `src/main/java/pocketdungeons/DatapackExporter.java`, methods `export`, `copyDirectory`, `writePackMeta`.
- `src/main/java/pocketdungeons/RoomTemplateGenerator.java`, method `captureRoomToFile`, and `src/main/java/pocketdungeons/DungeonCommands.java`, author commands.

## Dependencies

```bash
grep -rn "CubeRecipeDefinition\|AffixDefinition\|RoomRoleDefinition" pocketdungeons/src/main/java/pocketdungeons/
grep -rn "docs/schema" pocketdungeons/docs/INTEGRATION.md
```

First confirms M69, M70 and M71 definitions exist. Second confirms schema references are in integration docs. Verify `docs/schema` exists and all public paths are represented. If M71 not landed, stop.

## Goal

A non-developer can author, validate, distribute and upgrade a pack using a release jar.

## Implementation plan

Run `build_mod` after each step.

1. **PackValidator.** Add `src/main/java/pocketdungeons/PackValidator.java`. Export a small namespaced starter rather than force authors to override all bundled data. Add release-safe author workspace export for buildroom and saveroom, door and return-path validation, and actionable reports for coverage, reachable adventure nodes, recipe eligibility, missing loot and spawners, and guarantees. Default export refuses overwrite; replacement requires explicit destination-specific confirmation and backup. Keep existing bulk export available safely.
2. **Validation task and command.** Add `packValidationTest` Gradle task and matching `/dungeon admin` validation command with file, field, cause and reproducible seed. Verify 26.2 pack metadata format from the jar; test packaged-jar and development-resource export. Add `packValidationTest` to `build.gradle.kts` and register so `build` depends on it.
3. **Mod-root LICENSE.** Copy the existing workspace-root MIT licence to `pocketdungeons/LICENSE` for standalone distribution.
4. **Independent author exercise.** Have an independent author create one affix, theme, role and recipe, deliberately break a reference, fix it from the diagnostic, and upgrade a legacy fixture. Their report decides whether the API is beta-ready. No required Java imports, npm setup or client resource pack.

## Constraints

- Do not overwrite operator edits or export player data.
- No source-tree-only workflow advertised as pack tooling.
- Modded entities and items work only when the server and connecting clients already support those mods; Pocket Dungeons itself adds no client requirement.
- Build and task registration serially by the integrator.

## Verification

`build_mod` with tasks `packValidationTest`, `dungeonIntegrationTest`, `runGameTest`, then `build`. Human author acceptance using the published jar and docs only. Done when versioned compatibility examples and independently observed authoring failures and fixes are published.

## Completion

- Append architectural summary and independently observed authoring failures and fixes to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Publish versioned compatibility examples in `docs/INTEGRATION.md`.
- Rename this file `M72-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
