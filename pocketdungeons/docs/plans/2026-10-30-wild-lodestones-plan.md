# Wild lodestones: implementation plan (2026-10-30, revision 2)

Builds the record `docs/DECISIONS.md` "2026-10-30: Wild lodestones (overworld dungeon mode)". The record wins where this plan
disagrees; the builder notes any deviation in the record.

## 0. Read this first

**Status.** Nothing in `src/` is changed. This plan was written in a cloud session that cannot build the mod (the Fabric Loom
snapshot plugin does not resolve there), so **no Minecraft-touching code has been compiled**. The only code that has run is the
pure phase 0 set, verified with plain `javac` against Gson 2.10 and a one line stub of `PocketDungeonsConfig`. It is saved in
`docs/plans/2026-10-30-wild-lodestones-reference/` as `.java.txt` files (so Gradle ignores them) plus the knob diff.

**Tags used below.**
- **[V file:line]** I read it in the code and it says what the plan claims. Line numbers are as read on 2026-10-30 and may drift
  by a few lines.
- **[A]** An assumption. Verify it on a machine that builds before relying on it. Section 12 lists every one.

**House rules** (`CLAUDE.md`, `agent-brief` skill): no em dashes, no spaced double hyphens in anything written; player text per
the `player-text` skill; dialogs per `dialog-screens`; knobs per `tune-knobs`; tests per `add-tests`; commits authored
`MrPinoy123456789`; run `tools/check_dashes.sh`. Use `tools/pyedit.py` for scripted edits because some files may be CRLF.

**Do not** deploy to the live Charter server from this work. The test server is Kinetic `714a0605` only (`deploy-test-server`).

## 1. The design in one page

A server runs `mode: wild`. Lodestones (a tagged jigsaw block in a small ruin) are scattered at random across the overworld,
about `wildTargetActive` (12) at a time, never closer than `wildMinSeparation` (600) blocks and never inside claimed land. Each
holds one dungeon of one floor and shows a promise ("Iron. Deep."). Right click opens a dialog with Enter and Not now.

Entering builds (if needed) or joins the single shared live dungeon for that lodestone. No parties, no stash, no wipe; PvP is on
after a few seconds of spawn protection; death is vanilla. When the floor's clear check passes, the reward barrel and copper
chest appear and a second jigsaw "Exit" block with an item display appears beside them, and **placing it starts the cooldown**.
The dungeon stays open and enterable through the whole cooldown. At the end: 120 second warning, everyone moved out, ground items
swept into lost and found chests outside, the dungeon torn down, the lodestone `READY` for a fresh seed.

Each lodestone lives about a week, then collapses (same sweep) and a new one appears somewhere else. Claims are Charter's job;
this mod only reads where kingdoms are so it does not place lodestones inside them.

## 2. Facts verified in the code

| # | Fact | Where | Consequence for the plan |
|---|---|---|---|
| F1 | A killing blow inside the dungeon dimension is cancelled for any player with a record: `allowDeath` returns `false` and runs rescue, omen or `failRunOmen` | `Instances.java:161-204` | Add an early `return true` for wild records so death is vanilla (task 2.9) |
| F2 | The per record watcher `tickRecord` has an early out for Pocket2 children (`parentSlot >= 0`) | `Instances.java:2741-2750` | Copy that shape: `if (record.wild) { WildRun.tick(...); return; }` before anything else |
| F3 | `tickRecord` otherwise runs campaign work: owner grace, homecoming, reward grace purge, spawner cues, `CapstoneFights`, `PotionDrip`, `Haunts`, `FloorObjectives`, `ProvingWaves`, `CopperClock`, `StarterTools`, `OmenBar.sync`, then `tickMember` per member | `Instances.java:2741-2830` | `WildRun.tick` re-runs only the subset a dungeon floor needs (task 2.7) |
| F4 | `tickMember` drops a member who is offline or not in the dungeon dimension via `RunLifecycle.dropMember`, guards the void, and reads the exit pad | `Instances.java:2858-2910` | `WildRun.tick` does its own member loop using `Instances.detach`, not `dropMember` |
| F5 | `dropMember` calls `leaveSettle` (campaign haul banking), `detach`, and purges on leader leaving or empty awaiting first door | `RunLifecycle.java:2441-2465` | Avoided by F4. `leaveSettle` is also a no op for untimed records because it needs `inFloorLoop()` [V `RunLifecycle.java:1759-1765`] |
| F6 | `inFloorLoop()` is `!untimed && !adminBuild && !visitInstance && !isChild()`; `isKeystoneRun()` is `layout.keystoneLevel() > 0` | `InstanceRecord.java:314, 328` | A wild record is `untimed = true` (not in the floor loop) but keeps `keystoneLevel > 0` (mob scaling reads it), so `isKeystoneRun()` is **true**. Every call site must be audited (task 2.2) |
| F7 | `isKeystoneRun()` is called in `InstanceRecord` 1, `Instances` 4, `PackPots` 1, `PartyService` 1, `Pocket2` 1, `RunLifecycle` 13, `RunStorage` 1, `SidebarDisplay` 1: 23 sites | grep | The audit list for task 2.2 |
| F8 | `stagingCellOrigin` is referenced about 96 times (`Instances` 48, `RunLifecycle` 19, `HallRoom` 9, `DungeonScreen` 5, `RitualListener` 3 and others); `advanceFloor` returns at once when it is null | `RunLifecycle.java:1389-1392` | A wild record has `stagingCellOrigin == null`; the audit checks each site tolerates that |
| F9 | `InstanceTeardown.purge` ejects every member through `Instances.eject`, calls `returnKeystone(... NO_CHANGE)`, `RunStorage.migrateLegacy`, `OmenBar.close`, removes the slot, then `teardown` | `InstanceTeardown.java:95-135` | Wild teardown = sweep items, then `purge`. Members go to their stored `ReturnPoint` |
| F10 | `teardown` sends any `ServerPlayer` still inside the bounds to **world spawn**, and `finishClear` discards **every non player entity** in the bounds, item entities included | `InstanceTeardown.java:266-270, 335-338` | Eject people to the lodestone and sweep items into chests **before** `purge` |
| F11 | `Instances.admit` records a `ReturnPoint` (dimension, position, yaw, pitch) of where the player stood and indexes them in `InstanceRegistry.byMember` | `Instances.java` near 517 | Entering from beside the lodestone gives the right return point for free |
| F12 | `RunLifecycle.enter(... untimed=true)` is the keystone free full build: `InstanceRegistry.allocateSlot`, `originForSlot`, `Instances.buildLayout`, `registerUntimedRun` (new `InstanceRecord`, `InstanceRegistry.bySlot.put`), `Instances.admit` | `RunLifecycle.java:226-300, 348-380` | The template for `WildRun.build` |
| F13 | `Instances.buildLayout` plans with `LayoutPlanner.plan(seed, RoomManifest.current(), ...)`, force loads chunks, calls `LayoutStamper.stamp(...)`, and tears the slot down on a stamp exception | `Instances.java:476-520` | Reuse; add a wild overload that passes theme, loot band and node context |
| F14 | `LayoutStamper.stamp(StampParams)` is private. Public entry points: `stamp(level, origin, plan, keystoneLevel, affixes[, owner])` (theme null, no node context) and `stampBehindLobby(...)` (`entranceAlreadyStamped = true`, so it skips the entrance cell). `StampParams(level, origin, plan, keystoneLevel, affixes, owner, entranceAlreadyStamped, theme, standingCells, lootBand, nodeCtx)` | `LayoutStamper.java:60-125, 218-222, 291` | Add a package private `stampWild(...)` (task 2.12) |
| F15 | `NodeStamper.contextFor(dungeonId, nodeId, mine, floorNumber, mineId)` builds the node palette and light from a `DungeonDef` node | `NodeStamper.java:79-107` | Pass `contextFor(dungeonId, nodeId, false, 1)` |
| F16 | `placeFloorRewards` is private; it places a barrel and a copper chest through `TrialContent.placeRewardContainers(level, terminalOrigin, entranceDir, rolls, loot, promised)` and calls `bankFloorOmen`, `FinaleWave.rewardChests`, `FloorObjectives.bonusRolls`, `Wager.*`, `ProvingWaves.payingTable`, `MineGuardians.payingTable`, `promisedItems` | `RunLifecycle.java:1474-1519` | Make it package private and call it with `finishedNow = false`; check each helper tolerates a wild record (task 3.3) |
| F17 | The reward corner is three fixed spots 4 blocks apart, mirrored by entrance direction; spots[0] is the copper chest, spots[1] the barrel, **spots[2] is always cleared to air** | `TrialContent.java:929-936, 1051-1062` | The clear exit jigsaw goes at `origin.offset(spots[2])` (task 3.4). `completionSpots` is private; widen it |
| F18 | `padGatesRefuse(server, record, player)` is the whole clear check: objective refusal, spawner threshold, boss alive, brood, mine guardian, proving wave; it returns whether to refuse and messages the player | `RunLifecycle.java:1247-1303` | Extract a player free `padRefusal(server, record, level)` returning the message or `null` (task 3.1) |
| F19 | `DialogRouter.handle` rejects any payload whose `pd_owner` is not the clicking player, then switches on the action path | `DialogRouter.java:60-72` | Wild screens set `pd_owner` to the viewer and carry the lodestone id in a new `pd_wild` key; add cases to the switch |
| F20 | `DialogKit` offers `common`, `text`, `button`, `closeButton`, `command`, `submit(path, context)`, `notice`, `confirm`, `list`; `show(player, dialog)` sends it; `goHomeConfirm` is a good small example | `DialogKit.java:70-200`, `DialogScreens.java:229-262` | Build wild screens in the `confirm`/`notice` shape |
| F21 | `UseBlockCallback` is registered in `RitualListener.register()` and its `onUseBlock` is an ordered chain of claims that return `null` when not theirs; `RitualListener.register()` is called from `PocketDungeonsMod.onInitialize` after `Instances.register()` | `RitualListener.java:55-135`, `PocketDungeonsMod.java:52-53` | Register the wild callback **before** `RitualListener.register()` so it answers first |
| F22 | `DungeonWorldRules.inDungeon(level)` is the only allowed way to ask "is this the dungeon dimension"; `DimensionCheckTest` fails on a raw `DUNGEON_LEVEL` comparison elsewhere | `DungeonWorldRules.java` | All new code uses `inDungeon` |
| F23 | `Instances.teleport(server, player, dimension, pos, yaw, pitch)` lifts the cursor item and sets or clears `lastDeathLocation` when crossing the dimension boundary | `Instances.java:3242-3280` | Use it for leaving. Note it clears the player's recovery compass target on crossing out |
| F24 | Trial spawners scale their mob counts by nearby players natively (`total_mobs_added_per_player`) | `docs/INTEGRATION.md` 1.2 | No work for "scale by players inside" |
| F25 | `InstanceRegistry.liveInstanceCount()` counts every non admin record, and `RunLifecycle.refuseAtInstanceCap` refuses at `maxConcurrentInstances` (default 32) | `InstanceRegistry.java:48-54`, `RunLifecycle.java:335-346` | Wild records count toward that cap. Keep `wildMaxActive` below it |
| F26 | `InventorySwap.reconcile` holds the invariant "in `pocketdungeons:void` you hold the dungeon inventory, elsewhere your survival one"; `inVoid` is computed from `player.level().dimension().equals(dungeonLevel)` | `InventorySwap.java:685-725` | Wild mode: never run `enterVoid`; still run `leaveVoid` for an already stashed player |
| F27 | Config knobs are `Knob` constants with an accessor named like the key; `tools/new_knob.py` writes both; `ConfigCharacterizationTest` pins six fixtures that must be regenerated after adding knobs | `PocketDungeonsConfig.java`, `tools/new_knob.py` docstring | Task 0.1 |
| F28 | Pure tests are registered in `build.gradle.kts` as `"name" to "ClassName"` pairs in one map; gametests are registered in `src/gametest/resources/fabric.mod.json` | `build.gradle.kts:383`, `add-tests` skill | Task 0.2 |
| F29 | `AtomicFiles.writeString(path, text, charset)` writes through a temp file and a move | `AtomicFiles.java:26-42` | Used by `WildState.save` |
| F30 | Three JOIN handlers in `Instances` queue a recovery teleport for a player whose stored return point differs | `Instances.java:272-300` | Read all three before task 2.10 (relog) |
| F31 | Teardown clears a slot by `PendingClear` over the layout bounds plus `maxOffset`, and releases force load tickets; the slot number is not saved anywhere on disk | `InstanceTeardown.java:255-345` | Persist the slot number in `WildState` or scan (task 2.10) |

## 3. New files and edited files

### New (all in `src/main/java/pocketdungeons/` unless stated)

| File | Job |
|---|---|
| `WildMode` | The `mode` rule. Reference code: `...-reference/WildMode.java.txt` (verified) |
| `WildState` | Saved state, phases, cooldown and expiry arithmetic, JSON. Reference: `WildState.java.txt` (verified) |
| `WildStore` | Holds the one live `WildState` for the server, loads at server start, saves on every change, clears at stop. Also `WildClock.now()` (epoch seconds, overridable in tests) |
| `WildLodestones` | Stamps and removes the marker ruin and the jigsaw blocks; the early `UseBlockCallback`; looks up the lodestone for a clicked position |
| `WildDialogs` | The three screens (outside, inside arrival, inside clear exit) |
| `WildText` | Every player visible wild string, pure, so `WildTextTest` can pin it |
| `WildRun` | Build, enter, leave, tick, relog, restart recovery for the live dungeon of one lodestone |
| `WildClear` | The clear check on a timer, the rewards, the exit block, the cooldown start, the reset |
| `WildSweep` | Collect ground items in a slot, merge, fill lost and found chests (shared by reset and collapse) |
| `WildPlacement` | Candidate points, rejection rules, the top up loop, collapse and relocation |
| `WildLevel` | Distance from spawn to level, pure |
| `ClaimSource` | `isClaimed(x, z)`; one implementation reads the public Charter data |
| `WildRules` | PvP spawn protection, blocked items and blocked commands |
| `WildDungeons` | Loader for `data/pocketdungeons/wild_dungeon/*.json` |
| `src/main/resources/data/pocketdungeons/wild_dungeon/*.json` | The offerings (task 2.1) |
| `src/test/java/pocketdungeons/Wild*Test.java` | Section 11 |

### Edited

| File | Edit | Phase |
|---|---|---|
| `PocketDungeonsConfig.java` | 13 knobs (`config-knobs.diff.txt`) plus `wildMaxRadius`, then regenerate fixtures | 0, 4 |
| `build.gradle.kts` | Register each new pure test | 0 onward |
| `PocketDungeonsMod.java` | `WildStore.register()`, `WildLodestones.register()` **before** `RitualListener.register()`, `WildRules.register()`, `WildPlacement.register()` | 0 to 4 |
| `RitualListener.java` | In wild mode skip the campaign only claims (task 1.5) | 1 |
| `DungeonCommands.java` | Refuse player facing campaign commands in wild mode; add `/dungeon admin wild ...` | 0, 1 |
| `DialogScreens.java`, `DialogRouter.java` | Action constants, `KEY_WILD`, switch cases | 1 |
| `InstanceRecord.java` | `wild` and `wildId` fields and a `forWild` factory | 2 |
| `Instances.java` | `allowDeath` early return, `tickRecord` early out, JOIN recovery | 2 |
| `InventorySwap.java` | Wild branch in `reconcile` | 2 |
| `LayoutStamper.java` | `stampWild(...)` overload | 2 |
| `RunLifecycle.java` | Extract `padRefusal`; make `placeFloorRewards` package private; guards found by the 2.2 audit | 2, 3 |
| `TrialContent.java` | Widen `completionSpots` to package private | 3 |

## 4. State machine

```
            +----------------- enter (first) -----------------+
            |                                                 v
        READY  <----- reset / first load after restart ---  IN_PROGRESS
          ^                                                    |
          |                                       clear check passes
          |                                                    v
          +------------------ resetAt reached -------------- CLEARED
                  (warn 120s, eject, sweep, teardown)
   any phase ---- expiresAt reached ----> collapse (warn 24h and 10m, eject, sweep to chests, teardown, marker to rubble,
                                          lodestone removed, placement tops up)
```

- `READY`: no record, no slot. Enter builds.
- `IN_PROGRESS`: a live record, `untimed = true`, `wild = true`. Players come and go; the record stays up with nobody in it.
- `CLEARED`: chests and clear exit exist; `resetAt = clearedAt + cooldown`. Enterable.
- On server start every `IN_PROGRESS` or `CLEARED` lodestone is treated as reset, because the record is memory only [V
  `InstanceRecord` class comment: "In memory only. A restart forgives..."].

## 5. Phase 0: switch, knobs, store

**0.1 Knobs.** Apply `config-knobs.diff.txt` (or rerun the 13 `tools/new_knob.py` commands it came from), then
`PD_CONFIG_REGEN=src/test/resources/config-fixtures ./gradlew configCharacterizationTest configKnobsTest` and review the six
fixture diffs. Knobs: `mode` "campaign", `wildGridSize` 1500, `wildMinSeparation` 600, `wildTargetActive` 12, `wildMaxActive` 24,
`wildMinSpawnDistance` 500, `wildLifespanDays` 7, `wildCollapseWarnHours` 24, `wildResetWarnSeconds` 120,
`wildSpawnProtectionSeconds` 5, `wildLostAndFoundMaxChests` 64, `wildClaimsSource` "", `wildBlockedCommands`
"tpa,tpahere,home,back,spawn,rtp,unstick". Set `maxConcurrentInstances` above `wildMaxActive` in the wild server's config (F25).

**0.2 Reference code.** Copy `WildMode`, `WildState`, `WildStateTest` from the reference folder, dropping `.txt`. Register
`"wildStateTest" to "WildStateTest"` after `"atomicFilesTest"` (F28). Run it. It passed under plain `javac` here.

**0.3 `WildStore`.** Static `WildState state`, `Path file`. `register()`: `ServerLifecycleEvents.SERVER_STARTED` loads from
`server.getWorldPath(LevelResource.ROOT)/data/pocketdungeons-wild/lodestones.json` (the same style as `RoomStore.dir` [V
`RoomStore.java:88`]); on load failure log an error, rename the bad file to `.corrupt`, and start empty. `SERVER_STOPPED`: save
and null. `save()` after every mutation (the file is tiny). `WildClock.now()` returns epoch seconds and has a package private
override for tests. If it holds per player data, register it with `SessionState` (read `SessionState` first [A]).

**0.4 Mode gating audit.** In wild mode these must do nothing or refuse politely: `/dungeon` entry, `/dungeon key`, the keystone
commands, room commands, party commands that start a run. `/dungeon admin ...` and `/dungeon exit` stay. Read
`DungeonCommands.register()` and wrap the player facing branches with `WildMode.active()`. **Also grep every
`ServerPlayConnectionEvents.JOIN` handler** (`Instances.java:272, 291, 297`, `PlaytestJournal.java:107`, and any in `Lemon`,
`FirstVisitTutorial`, `ActZero`, `HallRoom`) and confirm each is harmless for a player who has never had a room or compass. Fix
any that creates a room, starts the prologue or hands out a compass.

**Acceptance.** With `mode: campaign` the full pure suite and gametests are unchanged in behaviour. With `mode: wild` the server
starts, `lodestones.json` is created on the first save, and `/dungeon` tells a player to find a lodestone.

## 6. Phase 1: blocks, menus, ops commands

**1.1 Markers.** `WildLodestones.stamp(level, pos, id)`: a 5 by 5 ruin (mossy stone brick floor ring, two broken pillars,
cobweb) around a jigsaw at `pos`; the jigsaw's block entity gets name `pocketdungeons:wild_entrance`, `target`
`minecraft:empty`, `pool` `minecraft:empty`, `final_state` `minecraft:air`, `joint` `aligned` [A: confirm the
`JigsawBlockEntity` setters or NBT keys in this MC version; set them the way the rest of the mod sets block entities]. The state
record, not the NBT, is the source of truth for "which lodestone is this" (`WildState.atEntrance`). Arrival exit and clear exit
use names `wild_exit_arrival` and `wild_exit_clear`. A removal method turns the ruin to rubble (cobblestone with some gravel) and
removes the jigsaw. The ops `place` command places exactly where told; the placer (phase 4) guarantees an open sky column.

**1.2 Click handler.** `WildLodestones.register()` adds a `UseBlockCallback` that, on the server and the main hand only, looks up
the position: an entrance block opens the outside screen, an exit block opens the inside screen, and both return
`InteractionResult.SUCCESS_SERVER` so vanilla never opens the jigsaw screen [V constant seen at `RitualListener.java:114`; A: is
it right for a `UseBlockCallback`]. Register it before `RitualListener.register()` (F21).

**1.3 Screens.** `WildDialogs` builds, using `DialogKit.confirm`, `notice`, `closeButton` and `submit` (F20). All carry
`pd_owner` = viewer UUID and `pd_wild` = lodestone id (F19). Esc must run the exit button (`dialog-screens` skill).

| Screen | Title | Body | Buttons |
|---|---|---|---|
| Outside, ready | the dungeon name | `<promise>` / `Ready.` / (last day only) `Collapses in 20h.` | `Enter`, `Not now` |
| Outside, in progress | same | `<promise>` / `In progress. 3 inside.` | `Enter`, `Not now` |
| Outside, cleared | same | `<promise>` / `Cleared. Resets in 2h.` | `Enter`, `Not now` |
| Inside arrival | `Leave?` | `You keep what you carry.` | `Leave`, `Stay` |
| Inside clear exit | `Exit` | `Cleared. Resets in 2h.` | `Leave`, `Stay` |

Strings live in `WildText` (pure). The count inside comes from the live record's `members` size. Time formatting uses the largest
unit only: `2h`, `45m`, `30s`. Run all text through the `player-text` skill before committing; this table is a draft.

**1.4 Router.** Add `ACTION_WILD_ENTER = "wild_enter"`, `ACTION_WILD_LEAVE = "wild_leave"` and `KEY_WILD = "pd_wild"` in
`DialogScreens`, and cases in `DialogRouter`'s switch that call `WildRun.enter(player, id)` and `WildRun.leave(player)`. Phase 1
stubs both with a chat line so the menu can be tested before phase 2.

**1.5 `RitualListener`.** Classify every claim in `onUseBlock` (`spyglassCoords`, `roomInspector`, `armedMineGuard`, `bagChest`,
`floorObjective`, `PackPots.onUse`, `dungeonStorage`, `roomContainerMask`, `roomPlacementMask`, `dungeonShellPlacementMask`,
`stations`, and whatever follows line 135 [A: I read only to there]). Rule: keep the claims that act on dungeon content
(`floorObjective`, `armedMineGuard`, any Ordeal lever); skip, in wild mode, the ones that need a room, bag, compass, storage or
pot. Write the classification in a comment above the chain.

**1.6 Ops.** `/dungeon admin wild place` (at the caller, picks an offering by weight, level 1 or an argument), `list`,
`tp <id>`, `ready <id>`, `reset <id>`, `collapse <id>`. `list` prints id, coordinates, phase, dungeon, time to reset and to
collapse, and any lost and found chests. `tp` uses `Instances.teleport` (F23) to a block beside the entrance. Follow the
existing admin subtree shape [V `DungeonCommands.java:319`].

**Acceptance.** Place one by hand; right click shows the right screen for each state; Esc closes; Not now closes; survival
cannot break it; a creeper beside it does nothing; `list` and `tp` work. Pure `WildTextTest`.

## 7. Phase 2: the run

**7.0 Record shape.** Add to `InstanceRecord`: `final boolean wild; final String wildId;` and a package private factory
`InstanceRecord.forWild(slot, origin, createdAtTick, layout, affixes, wildId)` that calls the existing 7 argument constructor
[V `InstanceRecord.java:247`] with `owner = WildRun.NIL_OWNER` (`new UUID(0, 0)`) and `untimed = true`, then sets the wild
fields. Everything else stays a plain record in `InstanceRegistry.bySlot`.

**2.1 Offerings.** `WildDungeons` loads `wild_dungeon/*.json`, each: `{ "id", "name", "dungeon": "<dungeon def id>", "node":
"<node id>", "promise": "Iron. Deep.", "cooldownMinutes": 240, "weight": 10, "minDistance": 0, "maxLevel": 8 }`. Validate at
load: the dungeon and node exist in `DungeonDefs.current()`, `cooldownMinutes >= 1`, promise non empty. Use the `dungeon-content`
skill to pick good single floor nodes; start with 4 to 6 (copper and iron, coal, gold, deepslate, one long cooldown rich one).
The `dungeon/*.json` files carry `lootBand`, `nodePalette`, `hiddenOre` and nodes with `roomBias` [V `copper_works.json`].

**2.2 The keystone audit (blocking).** Open each of the 23 `isKeystoneRun()` sites (F7) and each `stagingCellOrigin` use (F8)
and write one line per site in the PR: *reached by a wild record? what happens? guarded or fine?* Most likely to matter:
`RunLifecycle` 13, `PartyService`, `PackPots` (called from `advanceFloor`; the wild clear does not call it), `RunStorage`,
`SidebarDisplay` (decide: show nothing for a wild member). A wild record never goes through `advanceFloor`, `completeRun`,
`commitDoor` or the safe room; confirm no event path reaches them.

**2.3 Build.** `WildRun.build(server, lodestone)`:
1. `level = server.getLevel(DUNGEON_LEVEL)`; if null, refuse with a clear message as `RunLifecycle.enter` does (F12).
2. `slot = InstanceRegistry.allocateSlot()`, `origin = InstanceRegistry.originForSlot(slot)`; save `slot` on the lodestone.
3. Seed: `level.getRandom().nextLong()` (fresh each reset; owner confirmed).
4. `LayoutPlanner.plan(seed, RoomManifest.current(), cfg path, branch, loop, maxGridSpan, theme = def.mainTheme, null,
   BagTags.pilgrim(1))` exactly as `Instances.buildLayout` (F13). Extract that method's planning half into a helper both share,
   or copy it into `Instances.buildWildLayout`.
5. Force load, then `LayoutStamper.stampWild(...)` (F14, task 2.12). On `RuntimeException`, `InstanceTeardown.teardown(...)` as
   `buildLayout` does; the slot is released when the clear completes (F13 comment).
6. `InstanceRecord.forWild(...)`; `InstanceRegistry.bySlot.put`; `lodestone.markInProgress(); WildStore.save()`.
7. Place the arrival exit jigsaw one block inside the entrance cell, plus its item display.
`keystoneLevel` = `WildLevel.of(distanceFromSpawn)`. `affixes` = `AffixMath.effective(owner, level, Set.of(),
AffixManifest.current().definitions())` as `enterUntimed` does [A: confirm a nil owner is acceptable; `enterUntimed` passes a real
UUID].

**2.4 Enter.** `WildRun.enter(player, id)`: refuse if the server is not wild, the lodestone is gone, the player is already in a
record (`InstanceRegistry.hasInstance`), or `wildMaxActive` live wild records exist and this one is `READY`. If a live record
exists, join it. Else `build`. Then `Instances.admit(server, record, player)` (F11) [A: confirm `admit` teleports to
`layout.entrance()` for an untimed record and what `admitsToStaging(record)` returns]. Then `WildState.rememberPlayer`, start spawn
protection, send the entry line.

**2.5 Leave.** `WildRun.leave(player)`: `Instances.detach(server, record, uuid, player)`, then `Instances.teleport` to the stored
`ReturnPoint` (or the lodestone coordinates if gone), F23. **No inventory call.** Never use `dropMember` (F5).

**2.6 Inventory bypass.** In `InventorySwap.reconcile` (F26), after the `InventoryJournal.recoverIfNeeded` call: `if
(WildMode.active()) { if (stash.stashed() && !inVoid) leaveVoid(server, log, player, stash); return; }`. That keeps recovery for
anyone stashed by an earlier campaign and never calls `enterVoid`. Check `liftCursorBeforeCrossing` (F23) is harmless [A].

**2.7 Tick.** `Instances.tickRecord`, first lines: `if (record.wild) { WildRun.tick(server, record, now, interval); return; }`
(F2). `WildRun.tick` runs, in order: (a) its own member sweep (offline or left the dimension: `Instances.detach`; below the void
guard depth: teleport to the entrance, copying `Instances.java:2871-2878`); (b) the dungeon content ticks that make a floor
playable: `watchSpawnerClears`, `CapstoneFights.tick`, `PotionDrip.tick`, `Haunts.tick`, `FloorObjectives.tick`,
`ProvingWaves.tick` [A: decide per tick; skip `CopperClock` and `StarterTools`, which are campaign, and `OmenBar.sync`]; (c) spawn
protection expiry; (d) the clear check (phase 3). Skip owner grace, homecoming and reward grace.

**2.8 PvP and spawn protection.** `WildRules`: a `Map<UUID, Long>` of protection expiry ticks, registered with `SessionState`;
`ServerLivingEntityEvents.ALLOW_DAMAGE` returns `false` while a wild player is protected, and an `AttackEntityCallback` ends the
protection of a protected attacker. Confirm PvP is not blocked anywhere: grep `ALLOW_DAMAGE` registrations (`LibrarianNPC:136`
and `Haunts:141` are NPC rules) and `DungeonWorldRules`; the server `pvp` property is the operator's [A].

**2.9 Death.** `Instances.allowDeath` (F1): right after the record lookup, `if (record.wild) return true;`. Then verify the
`AFTER_DEATH` handlers (`CapstoneFights:108`, `Restless:62`), `HerobrineFight` `ALLOW_DEATH` (EARLY phase, line 160) and
`Lemon.java:177` `AFTER_RESPAWN` behave for a wild death [A]. Vanilla respawn puts the player at their bed or world spawn; the
inventory is vanilla (drops unless `keepInventory`). The player respawns outside the dungeon dimension, so `InventorySwap` sees
`inVoid == false` and, with 2.6, does nothing. The dead player's `InstanceRegistry.byMember` entry is cleared by the next wild
tick's member sweep ("not in the dungeon dimension", F4).

**2.10 Relog and restart.** On `ServerPlayConnectionEvents.JOIN`: if the player is in the dungeon dimension and has no live wild
record, send them to the entrance of `WildState.lastLodestoneOf(uuid)`, else `Instances.sendToWorldSpawn`. Read the three
existing JOIN handlers first (F30) so they do not fight. On `SERVER_STARTED`, after `WildStore` loads, reset every non `READY`
lodestone: sweep (phase 3 `WildSweep`), clear the slot cells with `InstanceTeardown.teardown(server, slot, origin,
InstanceLayout.forClearingOnly(origin, geometry), ...)` as `Instances.buildLayout` does on failure [V `Instances.java:508-511`]
(add `slot` to `WildState.Lodestone`, F31; the reference class does not have it yet), then `markReady`.

**2.11 Travel blocks.** `WildRules.register()`: `UseItemCallback` returning `FAIL` for `minecraft:ender_pearl` and
`minecraft:chorus_fruit` when `DungeonWorldRules.inDungeon(player.level())` and wild is active; and a check for the
`wildBlockedCommands` names, ops exempt. Hook: first look for a Fabric API event for command execution
(`ServerMessageEvents.ALLOW_COMMAND_MESSAGE` may exist [A]); if none fits, a mixin on `Commands.performCommand` with a crash
guard per the `minecraft-modding` skill and an entry in the mixin json.

**2.12 `LayoutStamper.stampWild`.** Package private overload next to `stampBehindLobby`:
```java
static InstanceLayout stampWild(ServerLevel level, BlockPos origin, DungeonPlan plan, int keystoneLevel,
                                Set<String> affixes, String theme, DungeonDef.LootBand lootBand,
                                NodeStamper.Context nodeCtx) {
    return stamp(new StampParams(level, origin, plan, keystoneLevel, affixes, null, false, theme,
            Set.of(), lootBand, nodeCtx));
}
```

**Acceptance (gametests).** `WildEnterGameTest`: a player's inventory is identical after enter, leave, enter. Two players share one
record. A wild death drops items and the player is not in a record afterwards. `WildPvpGameTest`: a protected player takes no
damage for N seconds, an attack ends it, then another player can damage them. Pure: `WildRulesTest`.

## 8. Phase 3: the clear, the cooldown, the reset

**3.1 Extract the check.** In `RunLifecycle`, split `padGatesRefuse` (F18) into `static String padRefusal(MinecraftServer
server, InstanceRecord record, ServerLevel level)` returning the first refusal message or `null`, and make `padGatesRefuse`
call it, message the player and return the boolean. The body uses `player.level()` and `record` only; the player is for
messaging [V `RunLifecycle.java:1247-1303`]. Add `PadRefusalTest` pinning both answers for a record with no spawners and a record
with a live boss.

**3.2 The watcher.** `WildClear.tick(server, record)` once a second (`now % 20`), only for `IN_PROGRESS` records: if
`padRefusal(...) == null` and the record has not cleared, `clear(record)`. **Risk:** the check could pass trivially on a floor
with zero spawners and no objective. Guard: require the layout to contain at least one trial spawner or an objective or boss;
otherwise never auto clear and log once [A: read `DifficultyProfile.spawnersCleared` for the zero case].

**3.3 The rewards.** `WildClear.clear`: `placeFloorRewards(server, level, record, tripDef, false, terminalOrigin, entranceDir, 1)`
after making it package private (F16). `tripDef` is the wild offering's `DungeonDef` (`DungeonDefs.current().byId(...)`) and must
be non null because `promisedItems` and `LootBands` read it. `bankFloorOmen(record)` and the `Wager.*` calls read `record.floor`
and `record.interval`; with a fresh `FloorState` and `IntervalState` they are zero, giving the base chest count.

**3.4 The exit block.** After the rewards: place the jigsaw `wild_exit_clear` at `terminalOrigin.offset(spots[2])` (widen
`TrialContent.completionSpots`, F17) and an item display above it (a door item plus a text display reading `Exit`). Seal the far
wall with `CellGeometry.sealDoorOnWall(level, terminalOrigin, farWall)` as `advanceFloor` does [V `RunLifecycle.java:1429-1430`].
Do not call `advanceFloor`, `summonNextStaging` or `moveStagingBeyondTerminal`.

**3.5 The cooldown.** `lodestone.markCleared(now, offering.cooldownMinutes * 60L)`; `WildStore.save()`. This is the moment the
exit block is placed. Tell everyone inside once.

**3.6 The reset.** A global tick once a second over `WildState.all()`:
- at `resetAt - wildResetWarnSeconds`: chat and action bar to members, once;
- at `resetAt`: `WildSweep.sweep(server, record, chestSite)` first (collect items, fill chests), then `InstanceTeardown.purge`
  (it ejects to each `ReturnPoint`, F9; items had to be collected first, F10), then `markReady`.
The next enter builds a fresh seed. The collapse (phase 4) reuses the same code with a different trigger.

**3.7 Sweep.** `WildSweep.sweep(server, record, chestSite)`: force load the slot chunks; collect `ItemEntity` in
`record.layout.bounds()` extended downward by the story reach `InstanceTeardown` uses (lines 255-259); merge by
`ItemStack.isSameItemSameComponents`; place double chests on free surface positions in a ring around `chestSite`, up to
`wildLostAndFoundMaxChests`; fill them; `discard()` the collected entities; if the cap is hit log `ERROR` with the number of
items lost. Record the chest positions in `lodestone.lostAndFound` and save. The dungeon's own reward chests and barrel are not
swept; they go with the dungeon.

**Acceptance (gametests).** `WildClearGameTest`: a stamped one floor dungeon with its spawners cleared yields the barrel, the
copper chest, the exit jigsaw, `CLEARED` and `resetAt = clearedAt + cooldown`, and no staging room. `WildResetGameTest`: members
are ejected to their return points, ground items land in chests, the slot is gone, the next enter builds a different seed. Pure:
`WildSweepTest` (merge, overflow into more chests, cap).

## 9. Phase 4: placement, claims, lifespan, collapse

**4.1 Candidates.** `WildPlacement.candidate(worldSeed, cellX, cellZ, generation)`: a `SplittableRandom` seeded from a hash of the
four values returns a point **uniformly anywhere in the cell** (never the centre). Pure, so `WildPlacementTest` can pin
determinism and spread (the sample mean of 10000 candidates is near the cell centre; the standard deviation is near
`cell / sqrt(12)`).

**4.2 The top up loop.** A server tick handler every 100 ticks makes at most one placement attempt: if `size <
wildTargetActive`, pick a random cell within `wildMaxRadius` (new knob, default 6000) of world spawn that has no lodestone, take
its candidate, check `wildMinSeparation` against all active lodestones and `wildMinSpawnDistance` against spawn, then the world
checks below. A rejected cell gets `generation + 1` next time. Cap attempts per minute.

**4.3 World checks** (main thread, one chunk per attempt): load the chunk with a short ticket (use the repo's `Instances.forceLoad`
pattern and release after [A: pick the repo consistent way]); surface height via `Heightmap.Types.WORLD_SURFACE`; reject water,
lava, a surface below sea level minus 3, a tree top block, no sky above, and a 9 by 9 area containing player built blocks (a tag
`pocketdungeons:wild_builder_blocks`: planks, torches, chests, doors, glass, wool, beds, furnaces, crafting tables, rails).
Overworld only.

**4.4 Claims.** `ClaimSource` reads `wildClaimsSource`: an `https://` URL fetched at most once a minute on a worker thread (never
the server thread) and cached, or a file path read the same way; empty means no check. **The format is not known.** The owner
says Charter's public data is already published; get the URL and format from the owner and write the parser then. Reject a
candidate whose point, or any point within 64 blocks, is claimed. On a fetch failure keep the last good data; with none and a
source configured, place nothing (fail closed).

**4.5 Level.** `WildLevel.of(distance)` = clamp(1 + distance / 400, 1, offering `maxLevel`). Pure, tested.

**4.6 Lifespan.** `expiresAt = createdAt + wildLifespanDays * 86400 + jitter(0..6h)`, jitter seeded by the id so a restart does
not change it. Warn at `wildCollapseWarnHours` and at 10 minutes, to members and on the action bar.

**4.7 Collapse.** At `expiresAt`: sweep with `chestSite` = the old lodestone, `InstanceTeardown.purge` if a record is live,
turn the marker to rubble, remove the lodestone from `WildState` but **keep its `lostAndFound` positions in a `retired` list** so
ops `list` still shows them; the top up loop fills the gap. The chests are plain world blocks afterwards.

**Acceptance.** `WildPlacementTest` (pure): determinism, uniform spread, separation, no lattice, generation retry.
`WildCollapseGameTest`: overflow chests, marker replaced, lodestone removed, `retired` keeps the chests.

## 10. Phase 5: docs and checks

- `docs/DECISIONS.md`: set "Status" to built and list deviations.
- `docs/playtests/LIVE_CHECKS.md`: the list in section 13.
- `python tools/gen_codemap.py`; commit the regenerated `docs/CODEMAP.md`.
- `docs/reference/BUGS.md`: any `PD-n` filed while building (highest used is PD-210).
- `docs/INTEGRATION.md`: add `wild_dungeon/*.json` as a datapack surface.
- Run `tools/ship.sh --pure` (the authoritative pure suite), the gametests, and `tools/check_dashes.sh`.

## 11. Test matrix

| Test | Kind | Phase | Covers |
|---|---|---|---|
| `WildStateTest` | pure | 0 | **written, passing under javac**: mode rule, phases, cooldown and expiry, lookups, JSON round trip, missing and bad files |
| `WildTextTest` | pure | 1 | strings short, no em dash, no double hyphen, durations |
| `WildDungeonsTest` | pure | 2 | `wild_dungeon/*.json` validation: ids exist, cooldown, promise |
| `WildRulesTest` | pure | 2 | protection arithmetic, blocked command parsing |
| `PadRefusalTest` | gametest | 3 | the extracted check matches the old answers |
| `WildSweepTest` | pure | 3 | merge, overflow, cap |
| `WildPlacementTest` | pure | 4 | determinism, uniform spread, separation, no lattice, retry |
| `WildLevelTest` | pure | 4 | distance to level, clamp |
| `WildEnterGameTest` | gametest | 2 | inventory identical, two players one record, vanilla death |
| `WildPvpGameTest` | gametest | 2 | protection holds, ends on attack, then PvP |
| `WildClearGameTest` | gametest | 3 | chests, exit jigsaw, `CLEARED`, cooldown from the exit, no staging |
| `WildResetGameTest` | gametest | 3 | eject, sweep, rebuild with a new seed |
| `WildCollapseGameTest` | gametest | 4 | overflow chests, rubble, retired list |
| `WildModeOffTest` | source scan | 0 | every wild hook starts with a `WildMode.active()` or `record.wild` guard |
| existing suites | all | every | `DimensionCheckTest`, `CellStateForgetTest`, `DisconnectHandlerTest`, config fixtures stay green |

Register pure tests in `build.gradle.kts` (F28) and gametests in `src/gametest/resources/fabric.mod.json`.

## 12. Unverified assumptions (verify first on a machine that builds)

1. Gradle builds at all (the repo pins `net.fabricmc.fabric-loom` 1.17-SNAPSHOT; it did not resolve in the cloud).
2. `InteractionResult.SUCCESS_SERVER` is the right return for a `UseBlockCallback` in this MC version.
3. Setting a jigsaw block entity's `name`, `target`, `pool`, `final_state` from code in this version.
4. A jigsaw's survival unbreakability and blast immunity (vanilla knowledge, not read from the game jar).
5. `Instances.admit` teleports to the layout entrance for an untimed record, and what `admitsToStaging(record)` returns.
6. `AffixMath.effective` accepts a nil owner, and what `Keystones.returnKeystone(... NO_CHANGE)` does for the nil owner during
   `purge` (F9).
7. `purge` reaches no campaign write for a record with `owner = nil` and `roomCellOrigin = null`: `saveRoomIfOwnerSync`,
   `RunStorage.migrateLegacy`, `PackPots`, `Wager`, `Omen`.
8. `DifficultyProfile.spawnersCleared` with zero spawners (the clear could fire instantly).
9. Whether Charter's or other mods' `UseBlockCallback` handlers run before ours (mod load order). Either order is acceptable (a
   claim that blocks strangers, or a physical wall), but test it on the real server stack.
10. A Fabric API event for command pre execution; otherwise a mixin.
11. `SessionState` requirements for new statics.
12. Which `tickRecord` content ticks a one floor dungeon needs (task 2.7b).
13. `Heightmap` and chunk loading cost of the placer on the live world.
14. The format and URL of Charter's public kingdom data.
15. That `HerobrineFight`'s `ALLOW_DEATH` listener (EARLY phase) does not also cancel a wild death.
16. That the `maxConcurrentInstances` cap and `wildMaxActive` interact as described (F25).
17. That `Instances.forceLoad` and `InstanceTeardown.teardown` can clear a slot given only its number and origin after a restart
    (F31).

## 13. Live checks (add to `docs/playtests/LIVE_CHECKS.md`)

Enter with a full inventory, leave, inventory intact. Two players in one dungeon. Die inside, respawn outside, return for the pile.
PvP after protection ends. Clear a dungeon, see the chests and the Exit block, check `list` shows the cooldown. Enter during the
cooldown. Wait through the 120 second warning with a player inside. Let a dungeon collapse with items on the floor and count the
chests. Camp the arrival block. Relog inside. Restart the server with a player inside. Check a lodestone never lands inside a
kingdom. Walk the world: are they too many, too few, too regular?

## 14. Risks

- **Hot files.** `Instances` (3300 lines), `RunLifecycle` (2650) and `InventorySwap` (1080) take small guarded edits only.
- **Hidden campaign coupling.** The 23 and about 96 sites in F7 and F8. Task 2.2 is blocking.
- **Teardown ordering.** Items vanish at `finishClear` and players go to world spawn (F10). Sweep and eject first.
- **Restart.** The slot number is not persisted (F31); add it to `WildState.Lodestone`.
- **Placement hitching the tick.** One attempt at a time, rate limited.
- **Sibling mods.** Blocking `tpa`, `home` and `unstick` from here is a patch; the clean fix is for those mods to check a flag.
- **Economy.** The cooldown is the only throttle on supply; start long.
