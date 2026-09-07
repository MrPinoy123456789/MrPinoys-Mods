# M68 - Namespaced, versioned content contracts - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `pocketdungeons/CONVENTIONS.md`, all sections. First milestone in Round II; re-read.
- `docs/INTEGRATION.md` sections 1 to 3. Current datapack schema and loader contracts.
- `src/main/java/pocketdungeons/JsonPackSupport.java`, method `baseName`, plus loader `load` methods. This is the namespace-discarding method to replace.
- `docs/DISCOVERIES.md` traps 1, 6, 8, 18 and 19.

## Dependencies

```bash
grep -n "baseName" pocketdungeons/src/main/java/pocketdungeons/ThemeManifest.java pocketdungeons/src/main/java/pocketdungeons/AdventureGraphs.java pocketdungeons/src/main/java/pocketdungeons/Diaries.java pocketdungeons/src/main/java/pocketdungeons/RoomManifest.java
grep -n "resolveProcessors" pocketdungeons/src/main/java/pocketdungeons/TemplateStamper.java
```

First confirms `baseName` is used across all four loaders. Second confirms processor resolution entry point. If M67 gate not passed, stop.

## Goal

Two packs with the same local names coexist, and reload never mixes incompatible generations.

## Implementation plan

Run `build_mod` after each step. Round II is strictly serial: M68 before M69 before M70 before M71 before M72.

1. **Schemas.** Publish versioned schemas for `dungeon_room`, `dungeon_theme`, `dungeon_adventure`, `anomaly_room` and `diary` in `docs/schema/*.schema.json`. Include `spanY`, content dispatch and tag semantics. Use namespace plus relative resource path as identity. Add explicit namespaced loot-table and normal or ominous spawner-config references alongside legacy suffix and prefix fields. A theme in another namespace must not secretly require data under `pocketdungeons`.
2. **ContentSnapshot and ContentReload.** Add `src/main/java/pocketdungeons/ContentSnapshot.java` and `ContentReload.java`. Parse and validate a candidate cross-resource snapshot with file, field and ID errors, plus deterministic duplicate resolution matching pack priority. Reject incompatible required graphs or coverage while keeping the last valid PD snapshot. Optional rejected rooms remain reported if required coverage survives. Legacy unqualified built-ins map to `pocketdungeons`; third-party legacy references require a unique alias or explicit migration map, never last-file-wins. Preserve old saved fields and unknown references for recovery.
3. **Active-floor pinning.** Pin active floors to resolved PD definitions. Vanilla reloadable registries also change, so validate their references, prohibit new generation during reload, and invalidate or restage previews only after a coherent reload. Reconfirm 26.2 reload-listener order and holder lifetimes before implementation.

## Constraints

- Ordinary maps, no synced or custom registry.
- No removal of legacy codec fields.
- `stories` is not an alias silently substituted for shipped `spanY`.
- Cannot roll back vanilla registries by retaining a PD map.

## Verification

`build_mod` with tasks `dungeonRoomMetaTest`, `dungeonThemeMetaTest`, `adventureGraphTest`, `dungeonLogTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Fixtures: two namespaces, invalid cross-reference, pack removal, active-floor reload, legacy-save round trip. Done when exact compatibility boundaries and rollback behaviour are published in `docs/INTEGRATION.md`.

## Completion

- Append architectural summary to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Publish exact compatibility boundaries and rollback behaviour in `docs/INTEGRATION.md`.
- Rename this file `M68-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
