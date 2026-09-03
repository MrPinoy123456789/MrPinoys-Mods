# Pocket Dungeons Audit

Static audit of 78 source files (22,976 lines) at `pocketdungeons/src`, HEAD `6c68a81`.

Six parallel passes over the live source tree, covering the generation pipeline, instance lifecycle, commands and config, economy, progression and data, and whole-module cross-cutting concerns. Every finding below was checked against the code before it was written down. Nothing was modified.

## Summary

| Severity | Count |
|---|---|
| Critical | 6 |
| High | 10 |
| Medium | 12 |
| Low | 12 |
| Dead members | 17 |
| TODO markers | 0 |

The headline is not a bug, it is a pattern. This codebase has zero TODO, FIXME, or HACK markers, no empty catch blocks, and no `printStackTrace` anywhere in `src/`. All 32 catch blocks log through the mod logger with the exception attached. The javadoc is unusually thorough.

That is exactly what makes the findings below worth acting on: the gaps are unmarked, and in several cases the javadoc confidently documents an invariant the code does not hold. The most dangerous defects here are the ones where a comment says the opposite of what the code does.

---

## Critical

6 findings. Reachable in normal play, and each one either crashes, destroys player property, or leaks a resource without bound.

### C1. `/dungeon key info` throws an NPE for any player without a keystone *(Verified)*

`Keystone.findHeld` returns `null`, not an empty stack. This call site calls `.isEmpty()` on the result. All five other `findHeld` call sites in the tree correctly test `== null`. It is an unprivileged, zero-argument command, so any player can trigger it by accident. The refusal branch it guards is also unreachable even when the stack is real.

- `DungeonCommands.java:489`
- `Keystone.java:309`

### C2. A lingering quarry is never purged, so every completed run permanently consumes a slot

Teardown sets `lingering = true` and leaves the record in `bySlot` with its slot still held. The documented sole escape hatch calls `retireOrPurge`, which on an already-lingering record with a non-null `roomCellOrigin` falls past the purge branch and simply re-runs `retire`, setting the flag again. Nothing else clears it except an admin purge or server stop. Slot indices and the world footprint grow without bound, which directly contradicts the class comment promising the footprint is bounded by peak concurrency. The stale record also stays visible to `roomRecordAt`, so an abandoned cell keeps its block protection forever.

- `InstanceTeardown.java:69`
- `InstanceTeardown.java:82`
- `RunLifecycle.java:224`
- `Instances.java:52`

### C3. A stamp failure behind the lobby erases the player's own room and double-frees the slot

The catch block queues a clear whose origin maps back to exactly `record.roomCellOrigin`, so the recovery path blanks the player's persistent room. It also leaves the record in `bySlot` while `finishClear` removes the slot from `usedSlots`. The next allocation can hand out the same slot and silently overwrite the record, leaving members in `byMember` pointing at an orphan. The player sees only "The dungeon failed to build. Try another door."

- `Instances.java:666`
- `PlanGeometry.java:56`
- `InstanceTeardown.java:296`

### C4. The disconnect handler mutates non-thread-safe collections off the server thread

The handler's own comment states that `DISCONNECT` fires on Netty's IO thread for an abrupt disconnect, then mitigates exactly one thing (the room capture) through `server.execute`. Everything else runs on the disconnect thread and structurally mutates `bySlot`, `byMember`, `pendingReturns`, `pendingClears` and `usedSlots`. The server thread iterates those same collections on every tick and on every block break. Either the whole handler body belongs inside `server.execute`, or the maps need to be concurrent.

- `RunLifecycle.java:542`
- `Instances.java:157`
- `Instances.java:1057`
- `Instances.java:1340`

### C5. Force-load tickets leak across runs and survive server restarts

Every door choice force-loads the new geometry's chunks, but `resetForNextDungeon` contains no matching release. Tickets are only dropped by walking the current layout's cells, so any chunk used by run N and not by run N+1 stays loaded permanently. Because `setChunkForced` persists into the level's saved data, this survives a restart with nothing left in memory that knows to release it.

- `Instances.java:661`
- `RunLifecycle.java:596`
- `InstanceTeardown.java:293`

### C6. Fuel matches by item type only, so it destroys a sibling mod's artifacts *(Verified)*

`isFuel`, `count` and `spend` all test `stack.is(item)` and ignore custom data. The ominous chest and vault tables re-skin echo shards as kamutotomes "Boss Stone II/III" via `set_custom_data` and `set_name`. Those count as fuel: a player can bank a Boss Stone at an engine terminal, and the door payment predicate will consume them with no control over which stack is taken.

The same grep falsifies the class invariant. `Fuel`'s javadoc states "Door 1 is the only source. Nothing else in this mod's loot tables grants `fuelItem`", citing the M12 self-funding risk. Echo shards appear in 13 entries across 7 tables, and `chests/anomaly.json` pool 0 grants 1 to 3 of them unconditionally, with no chance condition. Related: `ritualKeyItem` is still shipped in the default config with the same `minecraft:echo_shard` value and is read by no Java code at all.

- `Fuel.java:22`
- `Fuel.java:56`
- `chests/anomaly.json:9`
- `pocketdungeons.default.json:16`

---

## High

10 findings.

### H1. The M34 bounty block has two defects at once: three bounties over-count, one is dead *(Verified)*

`firstCompletion` is captured on line 790 and the bounty block sits at 797 to 818, outside it, while `if (firstCompletion)` only opens at 820. So `CLEAR_HALLS`, `SPELUNKER` and `PACK_HUNTER` advance once per party member. A three-player party completes both `PACK_HUNTER` and `SPEEDRUNNER` (target 3 each) in a single run. The block's own comment claims it works "the same way it counts toward the owner's prestige", but the prestige hook is inside the guard.

Simultaneously, `record.rewardChests` has exactly one write, at line 974 inside `completeDungeon`, which is not called until line 824. The read at line 798 sees the `-1` initializer, so `timed` is always false and `SPEEDRUNNER` never progresses at all. Moving the block below the `firstCompletion` branch fixes both.

- `RunLifecycle.java:790`
- `RunLifecycle.java:798`
- `RunLifecycle.java:974`
- `InstanceRecord.java:192`

### H2. The operator `lootOverride` writes an unclamped keystone level and can permanently downgrade a player

Door 3's experimental offer uses the operator's raw integer while every other offer goes through `KeystoneMath.upgrade`, which clamps to `[1, keystoneMaxLevel]`. `grantOffer` then overwrites unconditionally, and `setKeystone` stores only `Math.max(0, level)` despite its javadoc claiming the caller clamps. `/dungeon admin experiment <theme> "" 1` takes a level 60 player to level 1 permanently. A value of 0 reaches a state `Keystone.reconcile` explicitly refuses to repair.

- `Keystone.java:144`
- `Keystones.java:107`
- `DungeonLog.java:410`
- `DungeonCommands.java:1150`

### H3. No startup reconciliation, so a crash orphans geometry and forced chunks forever

The only cleanup is `SERVER_STOPPING`, which covers a clean shutdown. After a crash, `usedSlots` is empty on restart while the blocks are still on disk. The owner's next `/dungeon` takes slot 0 and stamps the lobby over cell 0, leaving the previous run's cells standing and connected to it. The record comments call in-memory-only state deliberate, but forgiving a run in progress is not the same as sweeping what it left in the world.

- `Instances.java:216`
- `Instances.java:235`
- `InstanceRecord.java:24`

### H4. `timedOutPenaltyApplied` is per-run state that no reset path clears

The per-run reset block clears seven fields and misses this one; it is written in exactly one place and never cleared. Any second run behind the same lobby after a first run that timed out skips its own timeout penalty, arms the reward-room grace countdown on its very first watcher tick, and downgrades a genuinely late finish from `LATE` to `NO_CHANGE`.

- `Instances.java:722`
- `Instances.java:1078`
- `RunLifecycle.java:1192`
- `RunLifecycle.java:869`

### H5. The reroll station can produce a strictly worse item, contradicting its own guarantee

The replacement pool is the entire enchantment registry filtered only by `isSupportedItem`. There is no curse exclusion and no exclusive-set check, so Sharpness can become Curse of Vanishing, and Silk Touch can land beside Fortune. `isValidReroll` only compares ids, so it passes all of these. Both the station and the math class javadoc promise the result is "never strictly worse". Treasure-only enchantments are in the pool too, making the station an unintended Mending source at flat lapis cost.

- `RerollStation.java:162`
- `RerollStation.java:44`
- `RerollMath.java:43`

### H6. The room directory admits a visitor straight into the owner's live keystone run

`findOwnedLiveRoom` matches any non-lingering, non-visit record with no check on whether a run is underway, and `visit` calls `Instances.admit` directly. `statusOf` knows the difference and reports "run in progress", but its only caller never gates on it. Clicking a directory card mid-run bypasses `maxPartyMembers`, makes the visitor a full member whose keystone gets settled on exit, and inflates `members.size()` into the `PACK_HUNTER` check.

- `VisitService.java:85`
- `VisitService.java:215`
- `DialogRouter.java:366`

### H7. The entire room-theme filter is inert: no JSON supplies either half of it

The pipeline exists end to end in code, from `DungeonThemeMeta.roomTheme` through `queryAnyRotation` to `matchesTheme`. But none of the five `dungeon_theme/*.json` files declares `room_theme`, and none of the fifteen `dungeon_room/*.json` files declares `theme`. So the filter always short-circuits true and every room matches every theme. The content is nearly there: `deepslate.json` sets `"spawner_prefix": "crypt"` and `crypt_corner.json` exists, but nothing links them.

- `Instances.java:645`
- `RoomSelector.java:78`
- `RoomManifest.java:257`

### H8. Voided-cell selection is not reproducible across JVM restarts

The voided pass draws one `nextDouble()` per cell while iterating `plan.cells()`, which is a `Set.copyOf` whose iteration order is deliberately randomized per JVM instance. The same seed produces a different voided set on every restart. Every other consumer in the pipeline sorts first, so this is the one leak. Iterating the already-sorted `geometry.cells()` closes it.

- `LayoutStamper.java:212`
- `RoomSelector.java:99`

### H9. Routed dialog clicks act on a possibly disconnected player

The mixin captures `listener.player` on the netty thread and defers, and the router performs no `hasDisconnected()` check and no re-lookup by UUID. Read-only paths degrade harmlessly; mutating ones do not. `unlockShell` shrinks the held stack and then writes the unlock by UUID, so on a disconnect and relogin the token is consumed from an orphaned inventory while the unlock persists. `applyShell` mutates the world and `startDungeon` teleports. One guard at the top of `handle` closes it for every action.

- `CustomClickMixin.java:96`
- `DialogRouter.java:40`
- `DialogRouter.java:332`

### H10. Adventure graph validation is single-pass and order-dependent

The loop iterates a snapshot while calling `nodes.remove` and testing `containsKey` against the live, mutating map. A node validated before the node it points at is removed keeps a dangling edge, so whether a pack survives depends on `HashMap` order rather than on the data. The class comment promises the opposite. It needs a fixpoint loop.

- `AdventureGraphs.java:60`

---

## Medium

12 findings.

### M1. Two of three stations enforce no unlock level at use time

Only the reroll station checks its unlock level in `onUse`. `gambleUnlockLevel` and `cubeUnlockLevel` are referenced nowhere except the picker shelf, so anyone who obtains the block by any vanilla means uses the station at keystone level 1. The picker locks the icon; the station does not lock the block.

- `RerollStation.java:111`
- `GambleStation.java:108`
- `CubeStation.java:119`
- `StationPicker.java:87`

### M2. Dialog actions bypass the station block and the gate entirely

The router validates only that the owner key matches the sender. The reroll and imbue actions carry no station-proximity token and no session nonce, and `handleReroll` never re-checks the unlock level that `onUse` does. A crafted click payload rerolls anywhere, at any level, with no station block. Materials are still spent, so this is a gate bypass rather than free value, which makes M1 worse rather than better.

- `DialogRouter.java:54`
- `RerollStation.java:137`

### M3. Task progress is awarded for opening a station, not for using it

Both the reroll and gamble stations fire task progress immediately after showing the UI, before anything is charged. The gamble task is labelled "Spend Emeralds at Kadala" with a target of 16, so 16 right-clicks with an empty inventory complete it. The cube station and the ritual listener both do this correctly, firing only after the spend.

- `RerollStation.java:121`
- `GambleStation.java:114`
- `TaskTracker.java:37`
- `CubeStation.java:147`

### M4. Death rescue detaches a member by hand and skips the leadership rule

`rescue` open-codes the detach rather than routing through `dropMember`, so `leadershipChanged` is never evaluated. An owner who dies mid-run in a party is silently removed from their own instance while the party keeps running, bypassing the rule on the one path most likely to trigger it. The player is left inside the dungeon dimension in a record they no longer belong to, and the returned `ReturnPoint` is discarded.

- `Instances.java:841`
- `Instances.java:858`
- `RunLifecycle.java:1173`

### M5. An `IRON_DOOR` connector can gate the critical path with no redstone source anywhere

Only edges touching the entrance are skipped, so any other edge including the critical path can roll `IRON_DOOR` at roughly 10.5%. The stamper places closed, unpowered doors, and the comment says "room content or the player supplies it", but no room content does. A player carrying no redstone is stopped by a coin-flip connector on the way to the exit.

- `LayoutStamper.java:249`
- `ConnectorStamper.java:64`

### M6. `/dungeon admin manifest reload` reloads one of five manifests and reports full success

Startup loads themes, adventure graphs, diaries, rooms and anomaly rooms. The reload command calls only `RoomManifest.load`, then prints "Loaded N room(s) into manifest." Themes, adventure nodes, diaries and anomaly rooms stay stale, so `/dungeon admin theme list` keeps printing rejections a reload can never clear. Three other commands point operators at this one as the remedy. Found independently by two passes.

- `DungeonCommands.java:1167`
- `Instances.java:217`
- `DungeonCommands.java:1225`

### M7. Diaries never reload on `/reload`

`Diaries.load` has exactly one call site, inside `SERVER_STARTED`. The theme and room manifests both register reload listeners; diaries do not. Since `DatapackExporter` exists specifically so operators can edit the data tree and reload, edited diary entries require a full restart. `Diaries.rejections()` is also never called, so a rejected diary file is invisible outside the log.

- `Instances.java:219`
- `ThemeManifest.java:50`
- `RoomManifest.java:100`

### M8. The infestation theme has no adventure node, so it is unreachable

Four adventure files ship against five themes. `infestation` can only be reached through `/dungeon admin experiment`, which deliberately does not validate the theme id, and its six authored trial spawner configs are unreachable with it. Given the authored content behind it, this reads as unfinished wiring rather than a deliberate reserve pool.

- `dungeon_theme/infestation.json`
- `dungeon_adventure/`
- `DungeonCommands.java:1140`

### M9. Themed loot can never resolve on an ominous run

`resolveLootTable` appends the theme's `loot_suffix` to an already-decorated path, so an ominous run asks for `tier_1_ominous_drowned`. Only `tier_N_drowned` and `tier_N_ominous` ship, so it silently falls back to the untheme'd table. The fallback is intentional for a missing table, but `drowned_vault` is the boss theme and `ominous` is a common affix, so the theme's loot is probably never seen.

- `TrialContent.java:485`
- `dungeon_theme/drowned_vault.json`

### M10. Cube extraction consumes the item before writing the state

`held.shrink(1)` runs before `addExtractedPower`. A throw inside the write, or a crash between the two, destroys a rare drop and grants nothing. Every other spend in the economy is ordered validate, spend, write. Reversing these two lines makes the failure mode a harmless duplicate grant instead.

- `CubeStation.java:143`

### M11. The deferred room save in `eject` races the clear that `purge` just queued

`purge` saves synchronously precisely to beat its own queued clear, then immediately calls `eject`, whose `saveRoomIfOwner` defers through `server.execute`. That capture fires on a later tick, with `roomCellOrigin` still non-null, while the clear is blanking the room, and overwrites the good blob. It survives today only because the room cell happens to be cleared last.

- `InstanceTeardown.java:122`
- `InstanceTeardown.java:129`
- `RunLifecycle.java:565`

### M12. The lobby directory is unbounded in a construct the spec says does not scale

One button per public online room, no cap and no pagination, in a dialog that ships whole in one packet. The spec explicitly rules this out and names the suite's own convention: cap and drop to chat past the limit, as Ballot does at 8. The zero-row case is handled carefully; the many-row case is not handled at all.

- `DialogScreens.java:499`
- `DIALOGS_SPEC.md:414`

---

## Low

12 findings. Real but bounded: latent, self-healing, operator-only, or currently unreachable by construction.

| # | Finding | Detail | Location |
|---|---|---|---|
| L1 | Weekly bounty key is locale-sensitive | `String.format` with no locale. A non-Latin numbering system writes non-ASCII digits into the persisted key; a default-locale change silently resets every stored bounty. `Locale.ROOT` fixes it. The UTC and ISO week handling around it is correct. | `BountyTracker.java:127` |
| L2 | Read paths mutate persisted state | `activeTask` writes through its grandfather clause and `currentBounties` writes on materialisation. Both are reached from the pure render path for a possibly offline owner, so a screen refresh dirties the save file. | `TaskTracker.java:83`, `DungeonScreen.java:301` |
| L3 | Keystone reconcile discards stack count | `mint` always returns a count of 1. Harmless with the default recovery compass, but `keystoneItem` is a free-form config string with no max-stack validation, so pointing it at a stackable item turns the watcher into a silent destroyer of 63 keystones per stack. | `Keystone.java:291` |
| L4 | Integer overflow in chest count | `secondsRemaining * 100` overflows past 21,474,836 seconds and a negative percent awards 3 chests. `KeystoneMath.timerSeconds` deliberately computes in long, so the one guard against a huge timer feeds math that cannot hold it. | `PayoutMath.java:33` |
| L5 | Attribute listener maps never cleaned | Both listeners keep a static per-UUID map populated by `computeIfAbsent` with no removal and no disconnect hook. Entries accumulate for every player who has ever logged in. Self-healing on relog, so a leak rather than a correctness bug. | `TrimListener.java:79`, `PowerListener.java:68` |
| L6 | Room names are unvalidated text shown to others | Both the command and the routed twin trim and truncate to 16 characters and do nothing else. The value renders as a literal component, so legacy formatting codes stay active and obfuscated text is visible to everyone browsing the directory. | `DungeonCommands.java:623`, `DialogRouter.java:228` |
| L7 | Player commands return success unconditionally | `exit`, and all six party subcommands, return 1 even when the underlying service refused. The chat text is honest; the brigadier result code is not, which matters for command blocks. Most of the mod is careful here, so this reads as drift. | `DungeonCommands.java:434`, `DungeonCommands.java:508` |
| L8 | `pendingReturns` never expires | Added on every disconnect inside a dungeon, removed only on admit or a rejoin that lands in the dungeon dimension. A player who never returns leaves an entry for the process lifetime. Bounded by unique players, with no reaper. | `Instances.java:65` |
| L9 | `stamptest` force-loads four chunks and never releases | No matching `setChunkForced(false)`. The closing message says to purge by hand, which covers the blocks but not the tickets. Dev-only, but it accumulates per invocation. Same root cause as C5. | `DungeonCommands.java:803` |
| L10 | Gamble draw discards all but the first stack | `rolled.get(0)` with no loop. Silent today because every gear table is authored `"rolls": 1`, but a second pool added to any of them becomes invisible loss of paid-for loot with no log line. | `GambleStation.java:314` |
| L11 | `admin experiment` echoes the raw affix string | `AffixMath.parse` silently drops unrecognised tokens, so a typo is confirmed back to the operator as if it applied. `AffixMath.join` already exists and would make the message honest. | `DungeonCommands.java:1150` |
| L12 | `inviteTtlSeconds` accepts 0 | Validated `>= 0`, and both consumers compare against an expiry computed from it. At 0 the window is sub-millisecond, so party kick confirmation and join become permanently impossible with no diagnostic. Should be `>= 1`. | `PocketDungeonsConfig.java:685`, `PartyService.java:174` |

### Config validation gaps worth a second look

The config file is structurally in excellent shape: all 62 scalar keys parse, default, serialize and read back consistently, and the three separate default lists agree exactly. There is no divide-by-zero anywhere; the only value used as a divisor is validated. Beyond L12, three cross-field checks are missing that the file already knows how to do elsewhere:

- `pathLengthMax` is unbounded and never checked against `maxGridSpan` (a mismatch fails every seed and falls back to the static layout forever).
- `keystoneMaxLevel` is never checked against the four unlock gates it caps.
- Both timer knobs accept 0.

---

## Dead code

17 members, 0 dead classes. Verified by reference count across `src/main`, `src/test` and `src/main/resources`. There is no reflection in the tree, one entrypoint and one mixin, so name-based dispatch cannot be hiding a caller. Every class has at least one live reference; nothing is dead at class granularity.

| Member | Location | Note |
|---|---|---|
| `nodeForReward(String)` | `AdventureGraph.java:122` | Zero callers. |
| `byNumber(int)` and its index | `Diaries.java:180` | Zero callers. The collision check uses a separate local map. |
| `rewardRoomStamped()` | `InstanceRecord.java:309` | Zero callers. |
| `planOptional()` | `LayoutPlanner.java:187` | Zero callers. |
| `discoverableIds()` | `ThemeManifest.java:100` | Zero callers. See the note below. |
| `rejections()` | `Diaries.java:184` | Zero callers, unlike the room and graph equivalents which commands surface. |
| `has(server, owner)` | `RoomStore.java:94` | Zero callers. `restoreFromBackup` has none in main either. |
| `enter(ServerPlayer)` | `RunLifecycle.java:58` | Zero callers; all live entries use the keystone or untimed variants. |
| `buildCell(...)` | `RoomBuilder.java:277` | Zero callers; left behind by the move to template stamping. |
| `apply(level, geometry)` | `BedrockEnvelope.java:39` | The 2-arg overload. Only the 3-arg form is called. |
| `count(ServerPlayer)` | `Fuel.java:65` | Zero callers. `spend` has only one internal caller and should be private. |
| `WEST_ONLY`, `EAST_ONLY`, `THROUGH` | `StaticLayout.java:30` | Three constants, zero readers. |
| `REWARD_HALL`, `SELECTOR_ROOM` | `TemplateStamper.java:89` | Zero readers, and the javadoc claims they are loaded by identifier. They are not. |
| `REWARD_CHEST_SPOTS` | `TrialContent.java:385` | Zero readers. Worse, the long javadoc describing `placeCompletionChests` is attached to this dead field, leaving the real method undocumented. |
| `setPendingOffer`, `clearPendingOffer` | `DungeonLog.java:428` | Test-only. The `pending_offer` codec field is still written and read, so removing them leaves a persisted field nothing populates. |
| `MIN_ROOMS` and its branch | `RoomSelector.java:30` | Unreachable: `PlanGeometry.of` throws on an empty cell set first. |

One dead method has a player-visible consequence. The `discoverable` flag on `dungeon_theme` is parsed, stored, unit-tested, and set to `false` in shipped data by `drowned_vault.json`. Its only consumer is the zero-caller `discoverableIds()`. Setting `"discoverable": false` currently does nothing at runtime.

Separately, `LayoutGraphGenerator.java:620-804` is a plain-Java verification harness shipped inside the mod jar. All 14 `System.out` calls in the production source set live there. It belongs in `src/test`; only the gradle task pointing at `sourceSets["main"]` keeps it where it is.

---

## Half-built features

7 items. None of these carries a marker. They are only visible by following a reference to something that is not on the other end.

1. **The reward hall and selector room are generated but never stamped.** `RoomTemplateGenerator.specs()` builds both, with chests, an exit pad, three doors and a wall lodestone, documented as "stamped directly by identifier through `TemplateStamper.place`". Nothing ever calls `place` with them, and the two identifiers are dead constants. The `rewardRoomGraceSeconds` knob sits alongside. The feature is wired end to end except at the stamping seam.
2. **The Herobrine Cube's "open-ended power library" contains exactly one power.** `warden_ward`, reachable only at tier 3 in the drowned theme. So the equip cap of 3 and the whole of `PowerEquipMath.activePowers` (dedup, slot-order tiebreak, cap) are unexercisable, because you cannot hold two distinct powers. `sortedUnlocked` also promises a cap filter in its javadoc that its body does not implement, so the picker will offer a fourth power the listener then silently declines.
3. **`extractionReversible` is a config knob with no implementation.** Declared, defaulted, parsed, validated, and written into the shipped defaults. Its only two references in the entire tree are javadoc prose. The field's own comment concedes it "only documents the intent", but the config file still presents it to operators as a working switch.
4. **`ritualKeyItem` and `ritualKeyCount` are shipped but unparsed.** Present in `pocketdungeons.default.json`, read by no Java code at all. `INTEGRATION.md` understates this as "read at load but unbranched"; they are not read.
5. **Adventure graph depth is tracked, persisted and seeded, but never reweights the pick.** Honestly documented as open tuning, but it is a persisted counter whose stated purpose is unimplemented. Relatedly, `pick` can return the same theme for two or three doors, because it expands by weight and takes indices 0, 1, 2 after a shuffle. `BountyTracker` shuffles a deduplicated pool by contrast, so the two seeded picks in the codebase disagree on whether repeats are allowed.
6. **Instance persistence and checkpoint respawn are deliberately unshipped.** Both are explicit deferrals in comments. Worth restating here because the unshipped half is not just run state but world cleanup, which is what makes H3 bite.
7. **A party leader cannot remove an offline companion.** The roster screen skips offline members and `kick` takes a player argument that cannot resolve them. The only escape is `kick all`. Documented in code rather than hidden, so a known limit, but a real functional hole.

---

## Documentation drift

8 items. The docs are the weakest surface in the project, and in two places they describe a feature that was removed rather than one that is missing.

1. **The scoreboard surface is documented across six files and does not exist.** `COMPLETED-MILESTONES.md` titles M33 "Guided tasks via scoreboard" and describes a `pd_task` objective, a tab-list column, a `pd_bounty` objective and `syncScoreboard`/`clearScoreboard` methods. Grepping for any of those names returns only four comments saying the feature was removed. The task-progress hooks all survive and are correct; only the display surface changed to the in-room screen. The same stale claims are mirrored in both M33 and M34 handoffs, `ROADMAP.md` and `ROOM_UX_PLAN.md`.
2. **`DIALOGS_SPEC.md`'s status header is wrong in both directions.** It says section 7 is spec-only and blocked on a listed flag that does not exist. That flag, the shared visit method, the directory and its routing all shipped, and the spec's stale-state guard is implemented nearly verbatim. Meanwhile section 1 did not ship in the form section 1 describes: `sendDoorOffer` no longer exists and M19 replaced the whole surface with the physical screen.
3. **`README.md` is three claims stale:** milestones "M0 to M21 code-complete" against a plan file documenting through M34; an active handoff pointing at a file that does not exist (the real open ones are M29, M31, M35); and a claim that `INTEGRATION.md` documents three schemas when it documents one.
4. **M31 and M35 are fully implemented but recorded as open.** Both have complete implementations and, for M31, a passing test. Neither handoff was renamed to `-completed` and neither has a section in `COMPLETED-MILESTONES.md`. M35's handoff is still written in future tense. M29's handoff was never renamed either, though its milestone is recorded.
5. **`INTEGRATION.md`'s affix section names the wrong class and the wrong contents.** It describes `Keystone.Affix` with three constants; the real type is a top-level `pocketdungeons.Affix` with at least eight. The same paragraph says data-driven affixes are gated on five or six existing in Java first, a gate that has now passed.
6. **Four datapack surfaces are undocumented.** `dungeon_theme`, `dungeon_adventure`, `anomaly_room` and `diary` are all namespace-scanned like `dungeon_room`, but only `dungeon_room` is documented, and its schema table omits the `theme` field. `dungeon_recipe` is still documented and was deleted by M11.
7. **`STATION_PICKER_PLAN.md` contradicts itself.** Section 7 says visitors can take stations; the resolved decisions say owner-only. The code implements owner-only, which is the right call, so section 7 is the stale half and should be corrected before someone "fixes" it back.
8. **Smaller stale comments:** `LayoutPlanner`'s class javadoc says nothing stamps a procedural plan yet and the fallback is left to the caller (both wired long ago); `enterWithKeystone`'s javadoc documents a keystone shrink that no longer exists anywhere in the method; and a broken relative link in `DIALOGS_SPEC.md` points at `DIALOGS.md` from the wrong directory.

---

## Refactor seams

8 highest-value, ordered by how much of the bug list each one would have prevented structurally, rather than by size.

1. **Split per-run state out of `InstanceRecord`.** The record mixes fields that die with it (`slot`, `origin`, `owner`) against fourteen that must be reset on every door choice, cleared field by field in a block that already missed one. Extracting a `RunState` the door choice replaces wholesale makes H4 structurally impossible instead of a thing to remember.
2. **One detach primitive.** "Remove a member" exists in four partial copies with different coverage of save, pad, timer, trial omen and the leadership check. M4 lives exactly in the difference, and `dropMember`'s own fifteen-line apology comment is about this. One primitive with a "and teleport" flag collapses it.
3. **Give the run one footprint.** `layout.geometry()` is treated as the occupied cell set by teardown, force-loading and the void guard, but after completion the room lives outside that geometry. Purge patches around it with an extra origin parameter and a twelve-line comment; the other two paths do not get the patch, which is exactly C5. A single `occupiedCells` set on the record replaces all three ad hoc reconstructions.
4. **Index the spatial lookups.** Three lookups each walk every live instance independently, and three room accessors each call one of them separately, so a single block break performs up to four full scans plus a list `contains` inside each. `instanceAt` also runs per mob spawn. A `Map<ChunkPos, InstanceRecord>` collapses this to one hash lookup and gives protection a single answer instead of three that the javadoc already worries about disagreeing.
5. **One datapack loader.** Four classes repeat the same twenty lines: list, sort, parse, catch into rejections, publish to a volatile. A generic `JsonPackLoader<T>` collapses all four and fixes M7 for free, since registration becomes one line per loader. `baseName` is copy-pasted three times plus a fourth inline variant with an extra rule the others silently lack, and `requiredString` exists four times with three different contracts.
6. **Wither methods on `DungeonLog.Entry`.** Fourteen mutators each restate all eighteen record components to change one; that boilerplate is roughly 300 of the file's 738 lines. Every new field is an eighteen-site edit that a transposed argument would pass compilation. The codec half of the file is already well factored and does not need touching.
7. **A shared station shape.** All three stations are: match block, test held item, check unlock level, act, progress a task. Two of five steps are missing from two of three stations, and the missing ones are exactly those with no shared home. A template `onUse` that checks `unlockLevel()` before dispatching makes M1 and M3 impossible rather than invisible. The block-matching boilerplate and four different variants of "read a key from custom data" go with it.
8. **Deduplicate the geometry helpers.** "World position on a cell wall" is written four times, one of which differs only in its `Direction` type; the "wall to opposite facing" switch appears five times in a single file when `CellGeometry.opposite` already computes it. Separately, `stampLobby` and `createVisitInstance` are a near-verbatim duplicate including a ten-line comment copy-pasted word for word, which warns that a hardcoded direction in the two copies "must not drift apart".

The two big files split cleanly. `Instances.java` (1,825 lines) has seven seams with no coupling between them: event registration, return points and join recovery, lobby stamping, the tick watcher, spatial lookups, mob scaling, and the admin commands. `DungeonCommands.java` (1,363 lines) is roughly half admin diagnostics that share no state with the player-facing commands, and the file already demonstrates the extraction pattern with its manifest branch factory. Neither is urgent, but both are low-risk.

---

## Test coverage

27 files, 28 tasks. The test setup itself is healthy. Every one of the 27 test files is wired to a gradle task and runs under test; there are no orphaned tests, and no test asserts nothing meaningful. The gap is in what is chosen for testing.

The split is clean and deliberate: pure-arithmetic helpers are well covered, while every class that touches the world, the filesystem, or a player's inventory has no test at all. That is the same set of classes the critical findings above live in.

| Untested class | Lines | Exposure |
|---|---|---|
| `Payout.java` | 79 | Runs an arbitrary console command on completion. |
| `Fuel.java` | 134 | Currency bank and spend. Home of C6. |
| `RerollStation.java` | 214 | Takes lapis. Home of H5. |
| `CubeStation.java` | 219 | Consumes items permanently. Home of M10. |
| `RoomStore.java` | 290 | The only persistence path for a player's built room; four IO catch blocks. |
| `GambleStation.java` | 316 | Takes emeralds, returns loot. |
| `PartyService.java` | 346 | Party membership and invites. |
| `InstanceTeardown.java` | 369 | Budgeted world clearing. Home of C2 and M11. |
| `Pocket2.java` | 508 | Child instances. See the note below. |
| `RunLifecycle.java` | 1,204 | Run entry and exit, payout, timeout. Home of C4, C5, H1. |
| `PocketDungeonsConfig.java` | 1,044 | Every tunable; seven catch blocks parsing operator JSON. |
| `RoomTemplateGenerator.java` | 1,240 | World mutation and template capture to disk. |
| `DungeonCommands.java` | 1,363 | All operator commands including purge. Home of C1. |

One test is misnamed. `Pocket2Test.java` never references `Pocket2`; it exercises `InstanceRecord` and `InstanceRegistry.allocateSlotNear`. `Pocket2.java` handles child-instance entry, tick and death across 508 lines with zero coverage, behind a test bearing its name.

---

## Repository hygiene

3 items.

1. **`pocketdungeons/logs/` is tracked and should not be.** 25 log files are under version control and the directory has no `.gitignore` entry, so it keeps growing. The build, run and run-data directories are all correctly ignored, so this is the one gap. Fix is a `logs/` entry plus `git rm --cached pocketdungeons/logs`.
2. **Sibling mods leak build output into git.** 490 tracked files live under `*/build/` in `ballot/`, `bounties/` and `hearsay/`. The ignore rules are correct but were added after these were committed. Outside this mod's slice, but it is why the working tree shows so many dirty entries and why the Pocket Dungeons signal is hard to read.
3. **No `.gitattributes`.** Git reports line-ending conversion for 35 files, so normalization depends on each machine's `core.autocrlf`. Adding `* text=auto` now avoids a whole-file diff on someone else's checkout later.

One reported item did not survive verification. The cross-cutting pass reported 56 uncommitted source files in `pocketdungeons/src` (1,459 insertions, 4,834 deletions). That does not reproduce: `git diff --stat -- pocketdungeons/src` is empty and the source tree is clean against HEAD. The 88 staged entries are log files, a build script edit, and a half-finished handoff rename. Everything in this report was audited against committed source.

---

## Methodology

Six parallel passes over `pocketdungeons/src` at HEAD `6c68a81`. Slices: generation pipeline, instance lifecycle, commands and config, content and economy, progression and data, cross-cutting.

Findings deduplicated across passes; C1, C6 and H1 independently re-verified against source. No files were modified.
