# M9 - Refactor and cleanup

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` · Status: `../PROGRESS.md`
> Successor design: `../DOOR_LADDER_BRAINSTORM.md`

**Goal:** the codebase can absorb the door/ladder reframe without the reframe
having to be written inside a 3,000 line class. Nothing a player can see
changes. Every commit in this milestone is behaviour preserving.

**Blocked on:** M7 landing (see C0). **Blocks:** every idea in
`DOOR_LADDER_BRAINSTORM.md`.

---

## Why this milestone exists

M4 through M7 each added a feature and retired part of an earlier one, and the
retirements were done by leaving the old path in place rather than removing it.
That was the right call at the time (each change stayed contained) and the bill
is now due. `Instances.java` alone holds slot allocation, party state, invites,
lobby stamping, door selection, room save and restore, visiting, completion,
payout, teardown, chunk forcing and the tick watcher, across roughly ninety
methods.

The brainstorm's sections 2.3, 2.4, 8, 10 and 11 all edit that one file. Doing
them first means doing them inside the god class, which makes the eventual split
strictly harder and the diffs unreviewable.

---

## Ground truth, verified 2026-08-25

Everything below was checked against the tree, not remembered. `./gradlew
compileJava compileTestJava test --offline` is green at the time of writing and
all twelve tests pass, so there is a working baseline to preserve.

### The working tree is not committed

`git status` shows M5, M6 and M7 work spread across staged, unstaged and
untracked files: seven untracked source files (`DungeonRecipe`,
`DungeonRecipes`, `DungeonThemeMeta`, `RecipeMatcher`, `ThemeHistory`,
`ThemeManifest`, `ThemeOfferMath`), four untracked test files, a whole
untracked `dungeon_theme/` and `dungeon_recipe/` resource tree, and about 5,700
changed lines in the loot tables. `PROGRESS.md` still marks every M7 task `WIP`.

**No refactoring may start on top of this.** A rename or a file split against an
uncommitted tree destroys the ability to tell a mechanical move from a
behaviour change, which is the only review tool this milestone has.

### Confirmed dead code

| What | Evidence | Action |
|---|---|---|
| `InstanceRecord.selectorRoom` | Never set true. All four `new InstanceRecord(...)` sites pass `false` or use an overload that defaults it. `Instances.java:974` calls it "harmless dead weight" in a comment | Delete the field, the three guards reading it (`Instances.java:344`, `:413`, `:1662`), and the seven-argument constructor overload nothing calls |
| `PocketDungeonsConfig.ritualKeyItem` / `ritualKeyCount` | Accessors at `:180` and `:184`, defaults at `:48`, `:282`, `:339`, `:489`. Zero callers. Sole surviving mention is a comment at `RitualListener.java:117`. Left over from the M0 entry fee, which was removed | Delete both fields, both accessors, both default writes, both reads, and the stale comment |
| `InstanceRecord` javadoc `{@link #affix}` at `:76` | The field is `affixes` and has been since M4. Broken javadoc link | Fix to `{@link #affixes}` |
| `Affix.FRAGILE` and `RoomTemplateGenerator.DOOR_FRAGILE` (`:361`) | Not dead yet. Scheduled for deletion by brainstorm 2.1 | **Leave alone.** Out of scope; listed so nobody deletes it here and breaks a live save |

Note for the design work that follows: `ritualKeyItem` defaulted to
`minecraft:echo_shard`. That is the same currency brainstorm section 3 wants for
the fuel sink. The config key is being deleted as dead, not reserved; if fuel
lands it should get its own key with its own name.

### Confirmed duplication

`RoomBuilder` and `RoomTemplateGenerator` each define their own `FLOOR`, `WALL`,
`CEILING`, `LAMP` and `AIR` block states (`RoomBuilder.java:52-56`,
`RoomTemplateGenerator.java:85-89`) and each has its own `buildCell`
(`RoomBuilder.java:74`, `RoomTemplateGenerator.java:535`).

This matters beyond tidiness. Brainstorm 9.4 (top slabs and stair framed light
fixtures) and 15.5 (discoverable room shells) both require changing the shell
palette, and today that is two edits in two files that can silently drift apart.
A room skin system is not buildable while the palette is duplicated.

### Stringly typed loot table paths

`TrialContent` builds loot table ids by concatenation at `:316`, `:333` and
`:362`. `resolveLootTable` (`:455`) validates the themed suffix variant against
the registry, but the `lootTable(basePath)` fallback (`:467`) does not, and the
supply path at `:333` bypasses validation entirely. A typo or a deleted table
surfaces as an empty chest at play time with no log line.

### Documentation sprawl

Seventeen markdown files at `pocketdungeons/` root, roughly 558 KB.
`UPDATE_PLAN.md` is 174 KB on its own. Several are superseded and none of them
says so: `HANDOFF.md`, `HANDOFF_DESIGN_SESSION.md`, `CONTINUE.md`,
`IMPLEMENTATION_PLAN_U8.md` and `FEATURE_PROPOSAL.md` all describe work that has
either shipped or been replaced. A new agent reading the directory cannot tell
which documents are live.

---

## The plan

Six phases. C0 first and alone. C1 and C2 are independent of each other. C3 is
the large one and depends on C1. C4 and C5 can happen at any point after C0.

### C0 - Land the working tree

Not optional and not skippable.

1. Run `./gradlew build` and confirm green.
2. Finish or explicitly park M7. If T7.0 through T7.4 are complete, mark them
   `DONE` in `PROGRESS.md` per process rule 4. If they are not, mark what is
   actually done and leave the rest `WIP` with a note, but the code still gets
   committed.
3. Commit in coherent slices, not one lump: theme foundation, recipe system,
   supply tiering, feral content, loot table expansion. Each commit compiles.
4. Confirm `git status` is clean apart from `DOOR_LADDER_BRAINSTORM.md` and the
   files this milestone adds.

**Done when:** `git status --short` shows nothing under `src/`.

### C1 - Delete confirmed dead code

One commit per row in the dead code table above. Each is a pure deletion with no
replacement. After each, `./gradlew build` must stay green.

Do not go hunting for more dead code beyond that table during this phase. The
table is the scope; anything else found gets written down for a later pass
rather than removed opportunistically, because an unlisted deletion inside a
deletion commit is invisible to review.

**Done when:** the four rows are actioned, build green, tests pass.

### C2 - One shell palette, one cell builder

1. Extract the five block states into `RoomGeometry` (which already holds `CELL`,
   `CEILING_Y` and the rest of the shell contract) or into a new `ShellPalette`
   beside it. Check first: if `RoomGeometry` has no Minecraft imports today, it
   must keep none, and the palette goes in the new class.
2. Make `RoomTemplateGenerator.buildCell` delegate to `RoomBuilder.buildCell`,
   or extract the shared body into whichever class survives. They must not both
   continue to exist.
3. Route every shell block placement through the single palette, so a future
   skin swap is one constant table.

**Done when:** `grep -l 'Blocks.STONE_BRICKS' src/main/java/pocketdungeons/*.java`
returns one file, and `/dungeon admin gentemplates` still produces
byte-identical `.nbt` output to before the change.

### C3 - Split `Instances.java`

The large one. Every step is a **move**, never a rewrite. If a method needs
changing to be moved, move it first in one commit and change it in the next.

Suggested order, smallest blast radius first:

| Order | New class | Moves from `Instances` | Why this cut |
|---|---|---|---|
| 1 | `CellGeometry` | `openDoorOnWall`, `sealDoorOnWall`, `doorSlot`, `rotationToFace`, `neighbourCell`, `standingNeighbours`, `cellBounds`, `insideAnyCell`, `offsetInDirection`, `opposite`, `terminalEntranceDirection` | Pure coordinate math. No world state, so it becomes testable with plain `javac` in the manner of `DoorMask` and `KeystoneMath`. Do this first; it gives C3 a test net it does not currently have |
| 2 | `InstanceRegistry` | `byMember`, `bySlot`, `usedSlots`, `allocateSlot`, `originForSlot`, `slotOrigin`, `hasInstance`, `maximalCellOrigins`, `maximalBounds` | Bookkeeping only. Every other extraction depends on this existing |
| 3 | `PartyService` | `party`, `invite`, `join`, `stageKick`, `confirmKick`, `notifyKicked`, `partyCompanions`, `resolveParty`, plus `invites`, `pendingParty`, `pendingKicks`, the `Invite` and `PendingKick` records | Self-contained apart from two calls back into `Instances` (`admit`, `announce`, both opened from `private` to package-visible) that `join` needs to actually seat a player. **Revised during implementation:** `dropMember` and `leadershipChanged` were listed here originally but turned out to call `purge` and `saveRoomIfOwner`, both deep run-teardown machinery, not party bookkeeping; moved to row 6 (`RunLifecycle`) instead, where they sit next to the rest of what they actually call |
| 4 | `InstanceTeardown` | both `teardown` overloads, both `purge` overloads, `retireOrPurge`, `PendingClear`, `processClears`, `finishClear`, `drainClears`, `isClearing` | Already its own async state machine with its own queue. **Correction:** `forceLoad` was listed here originally on the assumption teardown released force-load tickets through it; reading the code showed teardown always released them with an inline `level.setChunkForced` call, and `forceLoad` is called only by the stamping side (`buildLayout`, `generateBehindLobby`). Left in `Instances`, not moved |
| 5 | `VisitService` | `visit`, `findOwnedLiveRoom`, `findVisitInstance`, `createVisitInstance` | Brainstorm section 8 rewrites this entry point. Isolating it first makes that a contained change |
| 6 | `RunLifecycle` | the `enter*` overloads, `chooseOffer`, `completeRun`, `completeDungeon`, `expireTimedOut`, `exit`, `returnKeystone`, `saveRoom`, `saveRoomIfOwner`, `resetForNextDungeon`, plus `dropMember` and `leadershipChanged` (moved here from row 3, see its note) | The game loop. Every mechanic in the brainstorm edits here, which is exactly why it should not share a file with slot arithmetic |

What is left in `Instances` afterwards: the tick watcher (`onTick`,
`processJoinRecoveries`, `reconcileKeystones`, the pad checks), the admin
commands, the lobby-stamping helpers the tick watcher and admin commands both
still need (`buildLayout`, `admit`, `applyTrialOmen`, `clearTrialOmen`,
`enterLobby`, `generateBehindLobby`, `mcDirection`, `lobbyDoorDirection`,
`lobbyLayout`, `selectorDoorStep`, `clearCellSync`, `rescue`, `eject`,
`purgeIfAbandonedLobby`, `announce`, `roomOwnerAt`, `forceLoad`, `teleport`,
`sendHome`, `sendToWorldSpawn`), and the public entry points that delegate
outward.

**Correction on the size target, found during implementation:** "under 600
lines" assumed the `RunLifecycle` cluster was mostly self-contained. It is
not. `enter` alone calls `buildLayout`, `admit` and `enterLobby`;
`chooseOffer` calls `generateBehindLobby`; `resetForNextDungeon` calls
`roomOwnerAt`, `teleport` and `clearCellSync`; `completeDungeon` calls
`mcDirection`. All of those stay in `Instances` because the tick watcher and
the admin commands need them too, which means `Instances` keeps a real body
of lobby-stamping logic alongside the watcher, not just event wiring and a
free-list. Measured result: `Instances.java` 1,203 lines (from 3,008),
`RunLifecycle.java` 928 lines, both well past the original guess, and both
now the correct shape for what the file split actually needed to separate
(slot/party/teardown/visit bookkeeping from world-mutating logic), which was
the goal the byte count was only ever a proxy for.

**Rules for this phase, non-negotiable:**

- One extraction per commit. The commit message states which methods moved.
- No signature changes in a move commit. Package-private stays package-private.
- No behaviour change. If a move reveals a bug, write it down and fix it in a
  separate, clearly labelled commit after the move lands.
- `./gradlew build` green after every commit.

**Done when:** all six extractions land, each its own commit, no behaviour
change, build green, tests pass. (The original "under 600 / under 500" line
targets did not survive contact with the actual dependency graph; see the
correction above. Judge this phase by whether each class now has one
coherent responsibility, not by a byte count.)

### C4 - Harden the seams

1. **Test `CellGeometry`.** It is Minecraft free after C3, so it gets a
   `CellGeometryTest` alongside `DoorMaskTest`. Cover door slot positions on all
   four walls, `rotationToFace` for all sixteen wall pairs, and `neighbourCell`
   at grid edges. This is the only new test coverage this milestone owes, and it
   covers the code the brainstorm's physical door work (sections 9 and 10) will
   edit hardest.
2. **Validate loot table ids at startup.** A small `LootTables` holder with a
   named constant per table, plus a server-start check that every constant
   resolves in the registry, logging `ERROR` for any that does not. Replaces the
   concatenation at `TrialContent.java:316`, `:333` and `:362`.
3. **Collapse the `InstanceRecord` constructor overloads** to one, now that the
   `selectorRoom` overload is gone (C1). Four call sites.

**Done when:** `CellGeometryTest` passes, and a deliberately renamed loot table
file produces an `ERROR` line on server start rather than a silent empty chest.

### C5 - Documentation triage

No rewriting. Sorting and archiving only.

1. Create `docs/archive/` and move the superseded documents into it:
   `HANDOFF.md`, `HANDOFF_DESIGN_SESSION.md`, `CONTINUE.md`,
   `IMPLEMENTATION_PLAN_U8.md`, `FEATURE_PROPOSAL.md`, `UPDATE_PLAN.md`.
   Check each one's content before moving it; if any is still referenced by a
   live document, leave it and say why.
2. Add a two line header to each archived file: what superseded it, and the date
   it was archived.
3. Add a `README.md` at `pocketdungeons/` root listing the live documents and
   what each is for: `VISION.md` (why), `ROADMAP.md` (order), `PROGRESS.md`
   (status), `plans/` (how), `handoffs/` (start here), `INTEGRATION.md` (the
   published datapack schema), `DIALOGS_SPEC.md` (UI shapes), `DIALOGS.md`
   (what is built), `DISCOVERIES.md` (26.2 findings),
   `CLIENT_TEST_CHECKLIST.md` (the manual bar),
   `MYTHIC_PLUS_RECONCILIATION.md` (affix reasoning), and
   `DOOR_LADDER_BRAINSTORM.md` (uncommitted future design).

**Do not** mass rewrite em dashes in the archived or live documents. The root
`CLAUDE.md` rule governs new text only, and a punctuation sweep across 558 KB of
history would bury this milestone's real diffs.

**Done when:** every root markdown file is genuinely live, and a `README.md`
says what each is. **Correction, found during implementation:** the "under
ten" count assumed six candidates archived unconditionally. `PLAN.md` was a
seventh candidate by size alone, but it is referenced by four other live
documents (`VISION.md`, `MYTHIC_PLUS_RECONCILIATION.md`,
`CLIENT_TEST_CHECKLIST.md`, `DISCOVERIES.md`) as the standing technical spec,
not a superseded plan, and archiving it would break those references for no
reason. Six archived, eleven left live. Same lesson as C3's line-count
targets: judge by whether a document is actually still load-bearing, not by
a count picked before reading the documents.

---

## Task table

For copying into `PROGRESS.md`.

| # | Task | Status | Note |
|---|---|---|---|
| C0 | Land the M5/M6/M7 working tree | `TODO` | Blocks everything else in M9 |
| C1 | Delete confirmed dead code | `TODO` | Four rows, one commit each |
| C2 | One shell palette, one cell builder | `TODO` | Prerequisite for room skins |
| C3 | Split `Instances.java` | `TODO` | Six extractions, one commit each, moves only |
| C4 | Harden the seams | `TODO` | `CellGeometryTest`, loot table validation, ctor collapse |
| C5 | Documentation triage | `TODO` | Archive and index, no rewriting |

---

## Done when

- `./gradlew build` green, all tests pass, plus `CellGeometryTest`.
- `Instances.java` reduced to slot-adjacent bookkeeping plus the tick watcher,
  admin commands, and the lobby-stamping helpers those two need. The line-count
  targets in C3 did not survive contact with the real dependency graph; see
  that section's correction. Judged by responsibility, not byte count.
- `git status --short` clean under `src/`.
- No behaviour change observable in play. The verification bar is
  `CLIENT_TEST_CHECKLIST.md` run once at the end: enter a run, choose a door,
  complete it, take the reward, leave, re-enter, and visit a room. Nothing in
  that sequence should feel different from before M9.

---

## Explicitly not in scope

Named here so the milestone cannot quietly grow into the reframe.

- Spawner gating, mob scaling, the adventure graph, the fuel sink, two tier
  doors: all `DOOR_LADDER_BRAINSTORM.md`, all later.
- Deleting `Affix.FRAGILE` or the recipe system. Both are scheduled for removal
  by the reframe, and removing them here would be a behaviour change dressed as
  cleanup.
- The room template editor, the sound pass, room skins. C2 makes skins
  possible; it does not build them.
- Any change to loot table contents. C4 validates that the ids resolve; it does
  not touch what is inside them.
- Rewriting punctuation in existing documents.
