# M18-M22 code review - Handoff

> Paste this whole file into a fresh chat to start the review.

## Read before writing anything

Read ONLY these. Do not read reference files unless a finding below points
you at one.

1. `pocketdungeons/CONVENTIONS.md`: mod-specific rules (one-mixin budget,
   no client mod, codec migration).
2. `docs/DISCOVERIES.md`: verified 26.2 API findings, including the traps
   that have shipped bugs before.

## Goal

Review the M18-M22 code (21 changed files, ~1,876 lines added, ~343 removed)
for correctness, convention adherence, and regressions. Report findings as a
numbered list with file, line, severity, and fix. Do not fix anything unless
asked.

## Scope

The review covers the diff from `28345fc` (last commit before M18) to `HEAD`.
To see the full diff:

```
git diff 28345fc..HEAD -- src/main/java/
```

The changed files, grouped by milestone:

**M18 (room shell pass):**
- `RoomTemplateGenerator.java` (+227): immutable shell, template changes
- `RoomProtection.java` (+178): furniture protection
- `RoomGeometry.java` (+15): shell geometry
- `RoomBuilder.java` (+49): stamping changes
- `Instances.java`: stamp-time furniture placement

**M19 (physical door selection):**
- `DungeonScreen.java` (+360, new): text_display entity management
- `RitualListener.java` (+245): lever handler, bulb toggle, screen updates
- `InstanceRecord.java` (+25): `selectedStep` field
- `Instances.java`: furniture and screen placement at stamp

**M20 (visiting rework):**
- `DungeonLog.java` (+77): `publicListed`, `roomName` fields and codec
- `DialogScreens.java` (+309): lobby directory dialog
- `DungeonCommands.java` (+67): room public/private/name commands
- `VisitService.java` (+57): lobby-directory click routing
- `CallingCard.java` (-110, deleted): replaced by lobby directory

**M21 (UX consolidation):**
- `DialogRouter.java` (+113, new): lodestone menu dispatch
- `DialogScreens.java`: lodestone menu and manage-room screens
- `RitualListener.java`: wall-lodestone right-click opens menu
- `RoomSelector.java`: room leave pad removed

**M22 (sound pass):**
- `Chime.java` (+109, new): per-player sound cues
- `RitualListener.java`: `runStarts` migration, cue call sites
- `RunLifecycle.java` (+65): run-lifecycle cue call sites
- `Keystones.java` (+4): keystone cue call sites
- `Instances.java`: spawner-clear and stamp cue call sites
- `VisitService.java`: visit cue call sites

## What to check

### Correctness

1. **M18 shell immutability:** `RoomTemplateGenerator` and `RoomProtection`
   enforce that the shell and furniture cannot be modified by players after
   stamping. Verify the protection covers all break/interact paths, not just
   the obvious ones.
2. **M19 door selection state:** `InstanceRecord.selectedStep` is the
   physical door selection state. Verify it resets on run end, not just on
   instance teardown. Check the lever handler in `RitualListener` for the
   `selectedStep == 0` refusal case.
3. **M20 codec migration:** `DungeonLog.Entry` gained `publicListed` and
   `roomName` fields. Per `CONVENTIONS.md`, the codec must be backward
   compatible: new fields with defaults, old fields kept. Verify the codec
   in `DungeonLog` handles a log entry that predates these fields.
4. **M21 menu dispatch:** `DialogRouter` routes lodestone menu options.
   Verify every menu option has a dispatch case and that no old interaction
   path (calling card, leave pad) still exists as dead code.
5. **M22 per-player cues:** Every `Chime` call sends via
   `player.connection.send()`, not `level.playSound()`. The one exception is
   `visitorArrives`, sent to the owner. Verify no cue broadcasts to all
   nearby players. Verify the two old `RESPAWN_ANCHOR_CHARGE` calls in
   `RitualListener` were removed or migrated, not duplicated.

### Convention adherence

6. **One mixin:** `pocketdungeons.mixins.json` still lists only
   `CustomClickMixin`. No new mixin classes were added.
7. **No client code:** No `net.minecraft.client.*` imports in any changed
   file. No `assets/` directory was created. `fabric.mod.json` still says
   `"environment": "server"`.
8. **No em dashes or `--` as punctuation** in any new or changed line
   (strings, comments, javadoc, log lines).
9. **No comments removed** unless the line was being edited for another
   reason.

### Regressions

10. **`CallingCard.java` deletion:** M20 deleted it. Verify no remaining
    references to `CallingCard` in any Java file.
11. **Room leave pad deletion:** M21 removed it. Verify no remaining
    references to the leave pad or its watcher in any Java file.
12. **`PocketDungeonsConfig.java` (-8 lines):** M20 removed config fields.
    Verify no code references the removed fields.

## Verification

Run these via `build_mod`:

1. `build_mod` with task `build` (full compile + all tests).
2. `build_mod` with task `roomShellTest` (M18).
3. `build_mod` with task `roomFurnitureTest` (M18).
4. `build_mod` with task `lobbyBrowserTest` (M20).
5. `build_mod` with task `lodestoneMenuTest` (M21).

All must pass. If any fails, include the failure in the findings.

## Output format

Report findings as:

```
## Findings

1. [severity] file:line - description
   Fix: ...

2. [severity] file:line - description
   Fix: ...
```

Severity: `critical` (breaks functionality or violates a hard constraint),
`warning` (works but fragile or non-idiomatic), `note` (style or minor).

If a check category has no findings, say "No findings" for that category.

## Constraints

- Do not fix anything. Report only.
- Do not read `plans/COMPLETED-MILESTONES.md` or `docs/reference/` files.
  The scope is the code diff and the test results.
- Do not redesign the architecture. This is a review, not a refactor
  proposal.
- Use `read_mod_reference` if you need to compare a pattern against a
  sibling mod, rather than reading the sibling mod's whole source tree.
