# Audit follow-up plan

Authoritative scope for M36 through M44, the follow-up to the 2026-08-31
six-pass static audit. `docs/reference/BUGS.md` (PD-9 through PD-48)
carries the authoritative root-cause and fix-plan detail for every bug;
this doc does not repeat it, only groups it into milestones and gives
scope for the non-bug work (M40 through M44).

## M36: Critical bug fixes from the audit

**Goal:** close every audit finding that crashes a player, destroys a
player's room, or leaks a resource without bound.

**Depends on:** nothing. Do this first.

**Scope:** `docs/reference/BUGS.md` PD-9 through PD-14, PD-48. Fix in
that order: PD-10 (lingering-quarry purge) changes the same
`InstanceTeardown.retireOrPurge` branch that PD-14's reconciliation pass
needs to reason about, so land PD-10 first. PD-11 depends on the same
`InstanceTeardown.purge` path being correct, so it benefits from PD-10
landing first too, though it is not a hard blocker.

**Touch points:** `DungeonCommands.java`, `InstanceTeardown.java`,
`Instances.java`, `RunLifecycle.java`, `Fuel.java`,
`chests/anomaly.json`, `chests/pocket2.json`, `pocketdungeons.default.json`.

**Done when:** all seven bugs' verification steps in `BUGS.md` pass, and
the full test suite plus `compileJava` are green.

---

## M37: High-severity bug fixes from the audit

**Goal:** close the audit's economy/progression correctness bugs and the
generation-pipeline reproducibility bugs.

**Depends on:** nothing structural; independent of M36.

**Scope:** `docs/reference/BUGS.md` PD-15 through PD-22.

**Touch points:** `RunLifecycle.java`, `Keystone.java`, `Keystones.java`,
`DungeonLog.java`, `DungeonCommands.java`, `RerollStation.java`,
`RerollMath.java`, `VisitService.java`, `RoomManifest.java`,
`LayoutStamper.java`, `CustomClickMixin.java`, `DialogRouter.java`,
`AdventureGraphs.java`, `AdventureGraphTest.java`.

**Done when:** all eight bugs' verification steps pass. PD-19
(room-theme filter) only needs the code-side null-safety of the fix
verified here; the content decision of which rooms belong to which
theme is M42's job, not this milestone's.

---

## M38: Medium-severity bug fixes from the audit

**Goal:** close the audit's gate-bypass, task-tracking, and
operator-tooling-correctness bugs.

**Depends on:** nothing structural; independent of M36 and M37.

**Scope:** `docs/reference/BUGS.md` PD-23 through PD-35. PD-24 depends on
PD-23 landing first for the gamble/cube half of its fix (both are in this
milestone, so sequence them within it).

**Touch points:** `RerollStation.java`, `GambleStation.java`,
`CubeStation.java`, `DialogRouter.java`, `Instances.java`,
`RunLifecycle.java`, `LayoutStamper.java`, `ConnectorStamper.java`,
`DungeonCommands.java`, `Diaries.java`, `ThemeManifest.java`,
`InstanceTeardown.java`, `DialogScreens.java`.

**Done when:** all thirteen bugs' verification steps pass. PD-31 and
PD-32 only need the mechanical/scalability half of their fix verified
here (see M42 for the actual content).

---

## M39: Low-severity bug fixes and config validation gaps from the audit

**Goal:** close the remaining small correctness and misconfiguration
bugs. Lowest priority of the four bug-fix milestones; batch and land
together rather than one at a time.

**Depends on:** nothing.

**Scope:** `docs/reference/BUGS.md` PD-36 through PD-47.

**Touch points:** `DungeonCommands.java`, `DialogRouter.java`,
`PartyService.java`, `RunLifecycle.java`, `GambleStation.java`,
`Keystone.java`, `PayoutMath.java`, `PayoutMathTest.java`,
`TrimListener.java`, `PowerListener.java`, `Instances.java`,
`PocketDungeonsConfig.java`.

**Done when:** all twelve items' verification steps pass.

---

## M40: Dead code and stale-shipped-defaults cleanup

**Goal:** remove verified-dead code, the reward hall and selector room
(superseded, per the mod owner: folded into the final room and the
player room), and the `discoverable` flag (cut, per the mod owner).

**Depends on:** nothing. Independent of M36 through M39; the removed
members are not touched by any bug fix in this batch (cross-checked
against `BUGS.md`'s touch points).

**Scope:**

### 40.1 Dead members

Remove, after a final grep confirms zero callers remain (re-verify at
implementation time; the codebase moves):

- `AdventureGraph.nodeForReward(String)`
- `Diaries.byNumber(int)` and its backing `byNumber` index
- `Diaries.rejections()` (unless M38's diary-reload fix wires it into a
  command first, in which case it is no longer dead; check M38's status
  before removing)
- `InstanceRecord.rewardRoomStamped()`
- `LayoutPlanner.planOptional()`
- `RoomStore.has(server, owner)`
- `RunLifecycle.enter(ServerPlayer)`
- `RoomBuilder.buildCell(...)` (the overload with zero callers, not
  `RoomTemplateGenerator`'s own private `buildCell`)
- `BedrockEnvelope.apply(ServerLevel, PlanGeometry)` (the 2-arg overload)
- `Instances.dungeonRecordAt` and its private helper
  `dungeonRecordAndCellAt` (test-only, and the body is a no-op ternary)
- `Fuel.count(ServerPlayer)`
- `StaticLayout.WEST_ONLY`, `EAST_ONLY`, `THROUGH`
- `TrialContent.REWARD_CHEST_SPOTS`
- `RoomSelector.MIN_ROOMS` and its unreachable branch

Also demote `Fuel.spend(ServerPlayer, int)` to `private` (one internal
caller only, not truly dead but wrongly visible).

### 40.2 Relocate the generation-pipeline test harness

`LayoutGraphGenerator.java` lines 620-804 (the `main` method and its
`System.out` calls) is a plain-Java verification harness, not production
code. Move it to `src/test/java/pocketdungeons/LayoutGraphGeneratorHarness.java`
(or fold its assertions into `PipelineProofTest.java` if they overlap).
Update the `layoutGraphTest` gradle task in `build.gradle.kts` to point at
`sourceSets["test"]` instead of `sourceSets["main"]`.

### 40.3 Remove the `discoverable` flag

**Decided 2026-08-31: cut.** No theme-listing surface exists to consume
it. Delete the `discoverable` field from `DungeonThemeMeta`, its parsing,
`ThemeManifest.discoverableIds()`, the field's coverage in
`DungeonThemeMetaTest.java`, and the `"discoverable": false` line in
`dungeon_theme/drowned_vault.json`.

### 40.4 Remove the reward hall and selector room

**Decided 2026-08-31: cut.** Per the mod owner, these were an earlier
design superseded by the final room and the player's own room; they were
never finished being torn out. `RoomTemplateGenerator.specs()` builds
both `reward_hall` and `selector_room` templates in full (chests, exit
pad, doors, wall lodestone) with a javadoc claiming they are "stamped
directly by identifier through `TemplateStamper.place`," but nothing
calls `place` with either identifier.

Remove: the `reward_hall` and `selector_room` entries from
`RoomTemplateGenerator.specs()` (and their spec-building code, not just
the identifiers), `TemplateStamper.REWARD_HALL` and `SELECTOR_ROOM`, and
any structure files under `structure/rooms/` that exist only to back
these two specs. Grep `reward_hall` and `selector_room` across
`src/main/resources` first; if either identifier still appears in
shipped JSON (a dungeon_room entry, a loot table name) after removing the
Java side, that reference is also dead and should go with it.

**Touch points:** the sixteen files above, `build.gradle.kts`,
`dungeon_theme/drowned_vault.json`, `DungeonThemeMetaTest.java`,
`RoomTemplateGenerator.java`, `TemplateStamper.java`.

**Done when:** `compileJava` and the full test suite are still green with
zero new warnings about unused members in the removed set, and grepping
`discoverable`, `reward_hall`, and `selector_room` across
`src/main/java` and `src/main/resources` returns nothing.

---

## M41: Documentation drift correction

**Goal:** make every top-level and reference doc agree with what is
actually built. Pure documentation; no source changes.

**Depends on:** none of the bug-fix milestones; can run in parallel with
any of them. Should run *after* M42 if M42's content decisions change
what "built" means for the affected features (room theming, infestation,
themed loot), otherwise the doc fix would need a second pass.

**Scope:**

### 41.1 Remove the scoreboard documentation

`plans/COMPLETED-MILESTONES.md`'s M33 section (titled "Guided tasks via
scoreboard") and M34 section both describe a `pd_task`/`pd_bounty`
scoreboard objective, tab-list column, and `syncScoreboard`/
`clearScoreboard` methods that do not exist; the mod replaced this with
the in-room tracker screen. Rewrite both sections' display-surface
description to match `DungeonScreen.java`'s actual tracker content
(`trackerContent`, referenced at `DungeonScreen.java:140,292`). Same
correction in `docs/d3-handoffs/M33-handoff-completed.md`,
`M34-handoff-completed.md`, `docs/reference/ROADMAP.md` (M33/M34
sections), and `docs/reference/ROOM_UX_PLAN.md` (M33/M34 sections).

### 41.2 Fix `README.md`

- Line claiming "All milestones M0-M21 are code-complete" → update to
  reflect the actual completed range (check `COMPLETED-MILESTONES.md`
  for the current high-water mark at time of writing).
- Line pointing at `docs/d3-handoffs/M22-handoff.md` as "the active
  handoff" → that file is archived as
  `docs/d3-handoffs/archive/M22-handoff-completed.md`; point instead at
  whichever of M29, M31, M35 is still genuinely open once M31/M35 are
  corrected per 41.4 below (only M29 may remain open after that).
- Line claiming `docs/INTEGRATION.md` documents `dungeon_room`,
  `dungeon_theme`, and `dungeon_recipe` schemas → it documents only
  `dungeon_room`; `dungeon_recipe` was deleted by M11. Fix per 41.3.

### 41.3 Fix `docs/INTEGRATION.md`

- Section 3.1 names `Keystone.Affix` with three constants (`NONE`,
  `OMINOUS`, `FRAGILE`); the real type is the top-level
  `pocketdungeons.Affix` with at least eight constants. Correct the class
  name and the constant list, and remove the "gated on 5-6 existing in
  Java first" caveat since that gate has passed.
- Section 3.3 claims `ritualKeyItem` "still appears in
  `config/pocketdungeons.json` and is read at load." It is never read by
  any Java code (see `BUGS.md` PD-48). Correct this, and note the key is
  slated for removal under M36.
- Section 2's schema table omits the `theme` field that
  `DungeonRoomMeta.java` parses and `LayoutStamper` consumes; add it.
- Section 1 ("The five extensible surfaces") lists five but the mod has
  four more namespace-scanned datapack surfaces:
  `dungeon_theme`, `dungeon_adventure`, `anomaly_room`, `diary`. Add
  short entries for each, matching the existing five's format.

### 41.4 Rename completed handoffs and record missing milestone entries

- `docs/d3-handoffs/M31-handoff.md` → rename to
  `M31-handoff-completed.md`; the milestone (dungeon shell protection) is
  fully implemented and tested (`DungeonShellProtectionTest`). Add an M31
  section to `plans/COMPLETED-MILESTONES.md` in the style of its
  neighbors.
- `docs/d3-handoffs/M35-handoff.md` → same treatment. It is fully
  implemented (anomaly rooms) but still written in future tense and has
  no `COMPLETED-MILESTONES.md` entry.
- `docs/d3-handoffs/M29-handoff.md` → same treatment; M29 has a
  `COMPLETED-MILESTONES.md` entry already but the handoff was never
  renamed.
- Fix `COMPLETED-MILESTONES.md`'s ordering: M32's entry currently
  appears before M30's; reorder so entries are sequential.

### 41.5 Fix `docs/DIALOGS_SPEC.md`'s status header

The header claims section 7 (the room directory/elevator) is spec-only,
blocked on a `listed` flag and a shared visit method that do not exist.
Both shipped (`DungeonLog.Entry.publicListed`, `VisitService.visit`).
Conversely, section 1 (a per-door notice dialog) did not ship in the form
the spec describes; M19 replaced it with the physical `DungeonScreen`
text display, and `sendDoorOffer` no longer exists in the tree. Rewrite
the status header to match both facts. Also fix the broken relative link
to `DIALOGS.md` (should point at `docs/reference/DIALOGS.md`).

### 41.6 Fix `plans/STATION_PICKER_PLAN.md`'s internal contradiction

Section 7 says visitors can use stations at index 1; the "Resolved
decisions" section says owner-only, and the code implements owner-only
(`DialogScreens.java:531`). Correct section 7 to match the resolved
decision so a future reader does not "fix" the code back to match the
stale section. Also correct the station name: section 4 calls it "Cube",
the code and player-facing text call it "Herobrine Cube".

**Touch points:** `README.md`, `docs/INTEGRATION.md`,
`docs/DIALOGS_SPEC.md`, `plans/COMPLETED-MILESTONES.md`,
`plans/STATION_PICKER_PLAN.md`, `docs/reference/ROADMAP.md`,
`docs/reference/ROOM_UX_PLAN.md`, `docs/d3-handoffs/M29-handoff.md`,
`M31-handoff.md`, `M35-handoff.md`, `M33-handoff-completed.md`,
`M34-handoff-completed.md`.

**Done when:** every doc listed above matches what a fresh reading of the
current source tree actually shows. No source files change in this
milestone.

---

## M42: Half-built feature content and design work

**Goal:** implement the six half-built findings the mod owner decided to
ship, and record the one they decided to leave. All decisions were made
2026-08-31; this milestone is implementation-ready, not decision-gated.
(The reward hall and selector room, originally 42.1, were cut and moved
to M40.4 since "cut" makes them dead code, not new work.)

**Depends on:** none of M36 through M41. 42.2 and 42.3 should land before
M41's doc pass if the doc pass is meant to describe the finished state.

**Scope:**

### 42.2 Room-theme content pairing (`BUGS.md` PD-19)

**Decided: author it.** No shipped JSON sets `room_theme` (on
`dungeon_theme`) or `theme` (on `dungeon_room`), so the theme-aware room
filter never filters. Assign each of the fifteen `dungeon_room/*.json`
files a `theme`, and each of the five `dungeon_theme/*.json` files a
`room_theme` pulling the rooms that should feel native to it. Start from
the pairing the content already half-suggests: `deepslate.json` sets
`spawner_prefix: "crypt"` and `crypt_corner.json` exists unlinked, so
`deepslate` should pull `crypt_corner` (and any other crypt-flavored
rooms) preferentially. For rooms with no obvious theme affinity (the
generic halls, corners, dead ends), leave `theme` unset so they remain
selectable by every theme, per `RoomManifest.matchesTheme`'s existing
fallback behavior.

### 42.3 Infestation adventure node (`BUGS.md` PD-31)

**Decided: author it.** `dungeon_theme/infestation.json` has six
authored trial-spawner configs (`trial_spawner/infestation_tier_{1,2,3}/
{normal,ominous}.json`) and no `dungeon_adventure/infestation.json`, so
the theme is unreachable outside the admin command. Author the node file
following the shape of the four existing ones (`blackstone`, `deepslate`,
`drowned_vault`, `prismarine`). Pick its graph position and tier based on
the tier of its trial-spawner configs (infestation ships tiers 1 through
3, so it is not boss-tier like `drowned_vault`; slot it alongside
`blackstone`/`deepslate`/`prismarine` as a mid-graph node with normal
transitions in and out).

### 42.4 Themed-ominous loot resolution (`BUGS.md` PD-32)

**Decided: restructure to layer.** `TrialContent.resolveLootTable`
currently asks for a table like `chests/tier_1_ominous_drowned`, which
does not exist for any theme carrying a `loot_suffix`, so themed loot
silently falls back to the generic ominous table. Restructure
`resolveLootTable` so the ominous modifier and a theme's bonus pool
compose at roll time instead of requiring one named table per
tier-times-theme combination: resolve the base table (themed or not) as
today, then, when the run is ominous, layer in a themed-ominous bonus
pool (small, additive, following whatever pattern `LootTables`' existing
ominous-modifier pool uses) if the resolved theme defines one. This
avoids a combinatorial table count as more themed suffixes are added
(42.3 adds one now; more may follow).

### 42.5 Herobrine Cube power library

**Decided: defer new powers, fix the javadoc now.** `defaultPowerBonuses()`
and every loot table's `cubeReward` field define exactly one power
(`warden_ward`), reachable only in the `drowned` theme at tier 3, so
`PowerEquipMath.activePowers`'s 3-power dedup/cap logic is untestable in
practice. Do not author new powers in this milestone. Fix
`CubeStation.sortedUnlocked`'s javadoc, which currently promises a cap
filter ("every power a player has unlocked but not yet worn out an equip
cap on") that the method body does not implement, since it only sorts
the set. Either implement the filter it already claims to have (cheap,
since `PowerEquipMath.activePowers` already computes the cap logic
elsewhere and can be reused), or rewrite the javadoc to describe what the
method actually does. Prefer implementing the filter: it is a small,
self-contained fix and closes the gap between the picker offering a power
and `PowerListener` silently declining to activate it.

### 42.6 Adventure-graph pick duplicate-door prevention

**Decided: dedupe.** `AdventureGraph.pick` expands transitions by weight
and takes indices 0/1/2 after a shuffle with no dedup, so one node can
currently offer the same theme on two or three doors.
`BountyTracker.bountiesFor` already shuffles a deduplicated pool
elsewhere in the codebase; match that convention. Dedupe the expanded
transition list by theme before taking the top three, falling back to
allowing a repeat only when the node has fewer than three distinct
transitions total (a node with one real transition legitimately has
nothing else to offer).

### 42.7 Offline party companion removal

**Decided: leave it.** `/dungeon party kick <target>` cannot resolve an
offline player and the roster screen skips them; `kick all` is the only
escape. No code change. This decision is recorded here so a future audit
does not re-flag it as an open question.

**Touch points:** `dungeon_theme/*.json`, `dungeon_room/*.json`,
`dungeon_adventure/*.json`, `TrialContent.java`, `LootTables.java`,
`CubeStation.java`, `PowerEquipMath.java`, `AdventureGraph.java`,
`BountyTracker.java`.

**Done when:** 42.2 produces visibly different room mixes per theme in a
seed sweep, 42.3 makes infestation reachable as a normal door offer,
42.4 shows a themed bonus in an ominous drowned_vault run's loot, 42.5's
`sortedUnlocked` either filters correctly or its javadoc matches its
body (verify with a test asserting the filter, if implemented), 42.6
shows no duplicate door offers across a seed sweep of nodes with 3+
transitions.

---

## M43: Refactor backlog from the audit

**Goal:** address the structural seams the audit flagged as the root
cause of more than one bug, or as a source of drift risk (two
implementations of the same rule that can silently diverge). Lower
priority than M36 through M39; land opportunistically, one subsection at
a time, each as its own commit.

**Depends on:** M36 through M39 landed first is strongly preferred for
43.1 and 43.2 specifically, since both touch the exact fields/methods
those milestones' fixes also touch (`InstanceRecord`'s per-run fields,
`InstanceTeardown`'s detach/purge paths). Refactoring around a bug
before it is fixed risks re-introducing it in a new shape. 43.3 through
43.8 are independent and can land any time.

**Scope:**

### 43.1 Split per-run state out of `InstanceRecord`

`InstanceRecord` mixes fields that die with the record (`slot`, `origin`,
`owner`, `untimed`) with roughly fourteen that must be reset on every
door choice, currently cleared field-by-field in
`Instances.generateBehindLobby`'s reset block (which is exactly where
PD-18's `timedOutPenaltyApplied` bug lives). Extract the second group
into a `RunState` object (or similar) that the door-choice path replaces
wholesale rather than field-by-field, so a forgotten field becomes a
compile error (missing from the new `RunState`'s constructor) instead of
a silent carry-over bug.

### 43.2 One member-detach primitive

Four call sites independently reconstruct "remove a member from a
record": `Instances.eject`, `Instances.rescue` (missing the leadership
check, PD-26), `RunLifecycle.dropMember` (the complete version), and the
offline branches inside `InstanceTeardown.purge`/`retireOrPurge` (missing
`onPad`/`timer` cleanup). Consolidate into one `detach(server, record,
member, reason, boolean teleport)` used by all four call sites.

### 43.3 One `occupiedCells` set per instance

`record.layout.geometry()` is treated as the instance's footprint by
teardown, force-loading, and the void guard, but after a run completes
the room lives at `record.roomCellOrigin`, outside that geometry.
`InstanceTeardown.purge` patches around this with an extra parameter and
a long comment; `retireOrPurge` and `resetForNextDungeon` do not get the
same patch (this is PD-13's root cause). Add a single `Set<BlockPos>
occupiedCells` maintained on the record whenever the footprint changes,
and have every consumer (teardown, force-load release, void guard) read
it instead of reconstructing the footprint three different ways.

### 43.4 Indexed spatial lookup

`roomRecordAt`, `dungeonCellLookupAt`, and `instanceAt` in `Instances.java`
each independently walk every live instance; `roomOwnerAt`,
`roomOriginAt`, and `roomDungeonDoorAt` each call `roomRecordAt`
separately, so a single block break can perform up to four full scans.
Add a `Map<ChunkPos, InstanceRecord>` maintained alongside
`InstanceRegistry.bySlot`, updated wherever a record's footprint changes
(natural to build alongside 43.3), and have the lookup methods use it
instead of a linear scan.

### 43.5 One shared datapack-loader helper

`Diaries.load`, `AdventureGraphs.load`, `ThemeManifest.load`, and
`RoomManifest.loadFrom` each repeat the same ~20-line pattern: list
resources, sort by key, parse each with a try/catch into a rejections
list, publish to a volatile field. Extract a generic
`JsonPackLoader<T>(String folderName, BiFunction<String, JsonObject, T> parse)`
and have all four call it. Landing this makes PD-30 (diaries never
reload) a one-line registration fix instead of a bespoke listener.
`baseName(Identifier)` (copy-pasted three times plus a fourth inline
variant with an extra rule the others lack) and `requiredString` (four
inconsistent implementations) belong in the same helper class.

### 43.6 Wither methods on `DungeonLog.Entry`

Fourteen mutators in `DungeonLog.java` each restate all eighteen record
components to change one field, roughly 300 of the file's 738 lines.
Replace with `withX(...)` wither methods on `Entry`, or route every
mutator through a private `mutate(UUID, UnaryOperator<Entry>)`. The
codec half of the file is already well-factored and needs no change.

### 43.7 Shared station shape

`RerollStation`, `GambleStation`, and `CubeStation` each independently
implement "match block, test held item, check unlock level, act,
progress a task." PD-23 and PD-25 exist because two of the five steps
are missing from two of the three stations. Extract a common
`StationHandler` interface or base with an `onUse` template that checks
`unlockLevel()` before dispatching, so a missing check becomes impossible
to write rather than easy to omit. The block-matching boilerplate and the
"read a key from custom data" helpers (implemented three different ways
across the three files) belong in the same extraction.

### 43.8 Deduplicate geometry and facing helpers

"World position on a cell wall" is implemented four times
(`CellGeometry.doorSlotPositions`, `ConnectorGeometry.wallPos`,
`RoomTemplateGenerator.wallRingPos`, `RoomBuilder.doorSlot`); the
"wall-to-opposite-facing" switch appears five times in
`RoomTemplateGenerator.java` alone when `CellGeometry.opposite` already
computes it. Separately, `Instances.stampLobby` and
`VisitService.createVisitInstance` duplicate an 8-call, 10-line-comment
sequence verbatim, including a warning that a hardcoded direction in the
two copies "must not drift apart", the literal risk a shared
`stampRoomShell(level, server, owner, origin)` method removes.

**Touch points:** `InstanceRecord.java`, `Instances.java`,
`RunLifecycle.java`, `InstanceTeardown.java`, `InstanceRegistry.java`,
`Diaries.java`, `AdventureGraphs.java`, `ThemeManifest.java`,
`RoomManifest.java`, `DungeonLog.java`, `RerollStation.java`,
`GambleStation.java`, `CubeStation.java`, `CellGeometry.java`,
`ConnectorGeometry.java`, `RoomTemplateGenerator.java`, `RoomBuilder.java`,
`VisitService.java`.

**Done when:** each subsection is its own commit, the full test suite
stays green after each, and no subsection changes observable behavior
(these are structural moves, not feature or bug-fix commits: if a
subsection's refactor reveals it would also fix a live bug, stop, file it
as a new `BUGS.md` entry, and land the behavior fix separately from the
structural move).

---

## M44: Test coverage for world-mutating and economy classes

**Goal:** add coverage for the highest-risk classes the audit found
completely untested, prioritizing persistence and currency/economy
classes over pure world-mutation ones (the former have no recovery path
if wrong; the latter are visually obvious when wrong).

**Depends on:** M36 through M39 landed first. New tests should assert
the post-fix behavior; writing them against pre-fix behavior means
rewriting them immediately after.

**Scope:**

### 44.1 `RoomStore`

The only persistence path for a player's built room; four `IOException`
catch blocks with no coverage. Test: capture/restore round-trip
preserves block state, a corrupted save file is caught and logged rather
than thrown, `restoreFromBackup` actually restores from the backup file
(currently has no caller in `main` either; confirm during 44.1 whether
it should, per M43.2's detach consolidation, or is dead and belongs in a
future M40-style cleanup pass).

### 44.2 `Fuel`

Currency bank/spend, home of PD-48's fix. Test: `grant` delivers via
`Payout.deliver` (overflow drops at feet, does not void), `bank` moves
inventory count into the banked balance atomically from the caller's
perspective, `spendBanked` cannot go negative, and, once PD-48 lands,
a stack carrying foreign custom data does not count as fuel.

### 44.3 `RerollStation`, `GambleStation`, `CubeStation`

Money/consumption paths. Test each station's core math against
`RerollMath`/`GambleMath` (already tested) to confirm the station wiring
calls them correctly, that the unlock-level gate (once PD-23 lands)
actually refuses below the threshold, and that a spend either succeeds
fully or refuses fully (no partial-charge state).

### 44.4 `Payout`

Runs an arbitrary console command on completion; currently zero coverage
of the command-construction path. Test that a configured payout command
template is substituted correctly and that `Payout.deliver`'s
inventory-overflow-to-feet behavior works as documented.

### 44.5 `InstanceTeardown`

Budgeted world clearing; home of several of this audit's critical bugs.
Test the per-tick clear budget actually bounds work per tick (not just
that it eventually finishes), and that `purge`/`retireOrPurge`'s
room-preserving vs. room-clearing branches (once PD-10/PD-11 land) take
the correct branch for each of: fresh lingering record, re-entered
lingering record, non-keystone run, keystone run with no room yet.

### 44.6 Fix `Pocket2Test.java`

Currently exercises `InstanceRecord` and `InstanceRegistry.allocateSlotNear`
despite its name; `Pocket2.java` (child-instance entry, tick, death, 508
lines) has zero real coverage. Either rename the existing file to
whatever it actually tests and write a real `Pocket2Test.java`, or expand
it in place to cover `Pocket2.openChild`/`Pocket2.onTick`/death handling
alongside what it already tests.

**Touch points:** new test files under `src/test/java/pocketdungeons/`
for each of 44.1 through 44.5, plus the `build.gradle.kts` `JavaExec`
task registrations for each (follow the existing pattern: one task per
test file, added to `tasks.test`'s `dependsOn` list).

**Done when:** all six subsections have a passing test task wired into
`tasks.test`, and each test's assertions would have caught the specific
bug this audit found in that class (verify this by temporarily
reverting the relevant M36-M39 fix and confirming the new test fails).
