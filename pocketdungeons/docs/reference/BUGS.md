# Pocket Dungeons: Bug and Design Ledger

Active and awaiting-live-check entries are in full below; closed entries are indexed in the table at the bottom. Continue `PD-n` numbering when filing new entries (highest used: `PD-210`).

## Open, partial and awaiting live check

### PD-56: Every trial key this mod grants is permanently non-functional: the M49 bag tag breaks vanilla's strict vault-key match (High)

**Reported:** 2026-09-03
**Severity:** High (the mod's own trial keys cannot open any vault, ever,
which breaks the trial-key economy the situations round's Pot Room /
spur-vault design depends on, not just an item-count nuisance)
**Status:** Partially fixed live (vault-side drops removed); the deeper
tagging bug is open, record only, no further fix until told to proceed.
It needs a design call, not a mechanical one (see below).

Follow-up to the earlier "trial key doesn't work on other vaults"
exchange, which this replaces with the real, verified cause. The
original vanilla-single-use explanation was true but incomplete: a
freshly *mod-granted* trial key was reported as not working on *any*
vault, including a brand new one, and not stacking with other trial
keys the player held.

Root cause, verified against the 26.2 jar's bytecode, not guessed:
`VaultBlockEntity$Server.isValidToInsert` checks
`ItemStack.isSameItemSameComponents(heldStack, vaultConfig.keyItem())`,
a strict match on item type *and every component*. `VaultConfig.keyItem()`
is a plain `minecraft:trial_key` with no components. Every `trial_key`
this mod's loot tables grant carries a `minecraft:set_components`
function stamping `custom_data.pocketdungeons.bag = 1` on it (the M49
tag `InventorySwap.isBagTagged` reads to know an item came from the
run). That tag makes `isSameItemSameComponents` return `false`
unconditionally: a mod-granted trial key cannot open *any* vault,
freshly opened or otherwise, and it cannot stack with an untagged key
either, for the same reason (stacking also requires identical
components). This is not a per-vault bug; it is the item itself being
broken at the moment it is created, confirmed with `javap -c` on
`VaultBlockEntity$Server`, not inferred.

Checked and ruled out: `minecraft:ominous_trial_key` is never granted by
any loot table in this mod (`grep` across `chests/` and `vaults/` found
zero matches), so it is not affected. Plain `trial_key` is granted from
17 files total: all 9 tier/drowned/ominous variants under both
`chests/` and `vaults/`.

#### Fix

**Done, live:** the `trial_key` entry was removed outright from all 9
`loot_table/vaults/*.json` files (tier 1 through 3, base/drowned/ominous),
per the user's explicit "I'd rather not receive a trial key from a vault
anyways." This also resolves PD-54's vault-should-be-gear-or-emeralds
finding for this one item specifically. Verified: no `trial_key`
reference remains anywhere under `loot_table/vaults/`, and every touched
file still parses as valid JSON.

**Still open, and deliberately not touched:** the same broken tag is
still on every `trial_key` granted from `loot_table/chests/*.json` (9
files), where trial keys are supposed to stay per the original design
(Pot Room `provides: trial_key`, gated-chest tool rewards). Those keys
are just as non-functional as the vault-granted ones were. Stripping the
tag outright is not a safe mechanical fix on its own: the tag is also
what `InventorySwap.isOurs` (spec 11.9's "belt and braces" check) uses
to decide an item legitimately came from the run rather than from
somewhere suspicious, on the way out of the void. An untagged trial key
in a player's inventory when they leave would trip the "these items came
into the dungeon from outside the run" warning and get diverted, even
though it is entirely legitimate loot. This needs one of:
1. Exempt `trial_key` (and any other vanilla item whose function depends
   on exact component equality) from the `set_components` tagging pass
   specifically, and teach `InventorySwap.isOurs` to also recognize a
   plain, untagged `trial_key`/`ominous_trial_key` as "ours" by item type
   alone, the same way it already special-cases `Keystone.isKeystone`.
2. Find a tagging mechanism that does not touch `custom_data` at all (a
   different component vanilla's vault check ignores, if one exists):
   unconfirmed whether such a component exists; not checked.
3. Track "this trial key came from this run" out of band (e.g., in
   `DungeonLog` or the instance record) instead of on the item, and stop
   tagging `trial_key` entirely.
Whoever picks this up should also decide whether the same tagging
pattern is silently breaking any *other* vanilla item this mod grants
whose function depends on component equality (a comparator reading an
item frame, a specific enchanted book match, etc.); `trial_key` is
simply the one a player happened to notice and report, and the tagging pass
was applied uniformly to everything, so it is worth an audit rather than
treating this as an isolated case.

#### Verification

Vault removal: confirmed by grep (no `trial_key` reference remains under
`loot_table/vaults/`) and by parsing all 9 touched files as JSON. Live
item, not yet walked: open a vault, confirm it never offers a trial key
as a reward. The deeper chest-side bug is unverified beyond the jar
bytecode read; a live check would be opening a chest for a tagged trial
key and confirming it still fails to open a fresh vault.

---

### PD-58: Crash/restart recovery holds the void inventory instead of dropping it on the ground

**Reported:** 2026-09-03 ("seems if I log off and restart the server
while being in a pocket dungeon, when I log back in it puts my items
on the ground in the overworld instead of restoring them in the
dungeon when I reenter"). Traced to spec 11.6's own documented
last-resort fallback firing correctly, not a defect: a crash or a hard
restart wipes `InstanceRegistry` (in-memory only), and
`Instances.processJoinRecoveries` teleports the player out on rejoin
through a raw `teleport` call that never runs through `Instances.detach`,
so `InventorySwap`'s leaving branch has no room and no cached slot to
find. Confirmed nothing was actually lost: the items land on the
ground, visible, exactly as spec 11.6 promises ("nothing is voided
silently").

The user's follow-up reframed this correctly: dropping was never
required just because there was no room to speak of. "If we're able to
drop them on the ground, why can't we instead just save them into the
pocket dungeon inventory?" The specific old instance is genuinely gone
(a fresh `/dungeon` after a restart opens a brand new one, not the old
one), so restoring *into that instance* is not possible. Holding the
items and handing them back the next time the player enters *any*
dungeon is possible, and is a straightforward extension of the
`PauseRecord` mechanism PD-57 already built.

#### Fix

New `InventorySwap.OrphanRecord` (list of items, no slot: unlike
`PauseRecord` there is no instance to match against) with its own
`DungeonLog` sidecar (`orphans`, mirroring `stashes`/`pauses`
exactly). `RunLifecycle.deliverVoidInventory` gained a `knownInstance`
parameter, set by `InventorySwap.leaveVoid` from whether
`Instances.consumeLastDetachedSlot` returned anything at all:

- `knownInstance` true, no room found: a normal, tracked exit from a
  run that legitimately has no room (untimed, `/dungeon admin build`).
  Unchanged: drop at the player's still-visible feet.
- `knownInstance` false: nothing in this mod ever saw the player
  leave. `InventorySwap.stashOrphan` holds the items (slot 0 stripped
  the same way `PauseRecord` strips it, same reasoning as PD-57: a
  future `applyKeystoneItem` owns slot 0) instead of dropping them.

`InventorySwap.enterVoid` gained `restoreOrphanIfAny`, run after the
existing pause restore: on the player's next entry into any dungeon,
whatever was orphaned is placed into their inventory and the record is
cleared, with a chat line telling them what happened
("Your items from a dungeon that closed while you were away have been
returned to you."). The Lost and Found entry for the original leave is
written before this branch runs either way, unaffected.

#### Verification

Full test suite green (`./gradlew build --offline`). No dedicated
regression test: same reasoning as PD-50 and PD-57, this is
state-machine behaviour with no new pure-logic surface, so it is a
live-play item. Walk: enter a dungeon holding some items, kill the
server process (not a graceful shutdown, to skip the disconnect path
the same way a real crash would), restart, log back in, confirm no
items on the ground; enter any dungeon and confirm the held items
appear in the main inventory with the return message, and that slot 0
still holds exactly one keystone.

---

### PD-73: A layout with Sump fails to stamp: "no climbable return path" (Medium)

**Found:** 2026-09-27, while verifying PD-66 on the test server (`dungeon admin build 39 8`).
**Severity:** Medium (the build is refused and cleared, so nobody is stranded; a player would see the dungeon fail to open).
**Status:** Already fixed (2026-10-16 review). This is the same defect PD-97 fixed on 2026-10-03 (commit e433efb1): sump's return path was a solid pillar, now a ladder column at (14, z 1) plus a water source that alone satisfies the check, and the template was regenerated. `sumpTemplateHasAReturnPath` verifies all four rotations and `everyMultiStoryRoomHasAReturnPath` sweeps every spanY > 1 room in the manifest; both are green. The intermittent once-in-60 failure was the pillar, not rotation.
**Actual:** `LayoutStamper.stamp` (LayoutStamper.java:246) throws `room pocketdungeons:sump has spanY 2 but no climbable return path from its lower story to the upper floor (spec 13.4)` and the plan is cleared. Seen once in 60 builds at keystone 8.
**Next step:** check the Sump spec and template for its ladder or stair column, and whether a rotation drops it.


Source: `docs/playtests/2026-09-27-3.md`.

### PD-104: other rooms wire comparators and repeaters backwards (Medium)

**Reported:** 2026-10-01, found while fixing PD-99.
**Status:** Closed, stale (2026-10-16 review). Every room named has been reworked since the report. `sorting_floor` no longer ships a comparator at all; `Locks` (ITEM_KEY stick) reads the return chest and opens the door under PD-133, covered by `sortingFloorIsAClosedGateWithConfinedWater`. `barred_vault` was redesigned 2026-10-09 into an open room with a vault block on a dais: no door, hopper or comparator. `ominous_bargain` lost its gold toll the same day: the chest stands open. `the_altar` still has its comparator and repeater, but they are scenery: PD-143 moved the payout to `AltarOffering`, which reads the offering hopper and pays the key out of the dropper (`theAltarPaysOneKeyForAFoodOfferingOnly`). No door anywhere is driven by these parts.
**Likely cause:** vanilla `FACING` on a comparator or repeater is the input side. `SituationSpecs.sortingFloor` (comparator at 14,1,9 facing NORTH, chest at 14,1,10 to its south), `SpurSpecs.barredVault` and `ominousBargain` (comparator at 9,1,10 facing NORTH, chest to its west) and `theAltar` pass an "output direction" like `rotation_lock` did. If these doors are meant to open from the redstone rather than from `Locks`, they never will.
**Fix idea:** check which of these rooms `Locks` already drives; for the rest, flip the facing, recapture, and add a solve gametest like `rotationLockSolvesAtEveryRotation`.

### PD-113: An enderman teleports out of its room and keeps a trial spawner from clearing (High)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "I think at least one enderman teleported out of bound and now I can't complete the floor."
**Severity:** High (the floor cannot be completed; a manual console cleanup was needed).
**Status:** Fixed in code 2026-10-16, live check owed. `TrialContent.releaseStrays` runs beside `releaseTamed` on the watch tick: any mob a trial spawner tracks that the level no longer holds, or that stands outside the floor's bounds, is dropped from the tracked set (and discarded when found), so the spawner can reach cooldown. Test `strayMobIsReleasedFromItsSpawner` covers it. Unverified live: a real enderman escaping mid-floor then the gate clearing on its own.
**Repro:** ender_archive floor 3, spawners 2 of 3, the open one the hall_tee at cell 3,1. Two endermen were alive: one at (-11.6, 39, 14.5), one at (128.2, 72, 414.2), over 400 blocks away. After killing both, the gate read 3 of 3 and the player finished the floor.
**Expected:** a trial spawner reaches cooldown once its mobs are dead, or the mob cannot leave the instance.
**Actual:** the gate counts only spawners in `COOLDOWN` (`TrialContent.countCleared`), and vanilla holds a spawner out of cooldown while a tracked mob is alive, so an enderman that teleports far away stalls it.
**Fix idea:** cancel enderman teleports that land outside the cell or instance bounds, or count a spawner cleared when its remaining mobs are outside the floor.

### PD-114: The frame_lock exit opens onto a nether brick wall (High, needs repro)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "It correctly opens the door after being rotated 8 times, but on the other side of the door is netherbricks blocking my path." He said a second room had the same problem ("both ordeal rooms had this problem").
**Severity:** High (a gated route that opens onto solid wall).
**Status:** Not reproduced (2026-10-16). On current code the doorway planes are provably sound: a new sweep (`everyDoorEdgeLeavesAWalkablePlane`) stamps ten seeded floors and fails on any plan edge whose either side's doorway plane is walled at walking height; zero violations. Door jigsaws resolve to air at the canonical slot on every face the mask declares, connectors cannot fill both columns (RUBBLE is planner-only and is cobble/tuff/deepslate), window bands skip the door columns, and no seal path runs mid-floor. Two plausible historical causes: before the PD-144 fix landed (2026-10-05) an IRON_DOOR connector could stack a second door set on a gated room's edge, and a DOOR_SINGLE connector walls one of the two columns (a half-blocked doorway is still passable). If it recurs live, stand at the wall and read the actual block: shell material means the plane never opened, a door means a stacked connector, bars or gravel mean a deliberate gate.
**Expected:** the unlocked door leads into the next room.
**Actual:** a solid wall stands behind it.
**Fix idea:** `dungeon admin bias frame_lock 20` and stand at the exit with the player; compare the room's door slots with the plan's connectors at every rotation (a gametest like the multi-story one).

### PD-119: No join event and no llm mode when the player is already online (Medium, harness)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`.
**Repro:** the player joined at 19:22:37, before the loop's `server sync`; no `join` event reached the agent, `lemon mode llm` failed with "No player was found" while the server had no players, and the first greeting was held for a fight until 19:36:56.
**Fix idea:** have `wait --player` assert llm mode as soon as the player is online (not only on a join event), and have `status` report whether Lemon is in llm mode for each player.

### PD-128: Lemon agent tooling gaps seen on the remote server (Medium, harness)

**Reported:** 2026-10-03 (agent notes).
- PD-118 recurred: the ask "How do I pick a door?" at 06:20:03 never reached `wait_events` (`answered_by` none, `wait_s` 91) and was found only in `context` recent.
- `lemon_mode llm` lapses after 300 s, so the agent must refresh about every 4 minutes or the asks fall to the scripted guide (seen 06:44).
- `lemon_say` with no `player` returns "No player was found" in remote mode.
- `session_notes` is read-only (it returns the newest playtest file), so an agent cannot save notes through it.
**Fix idea:** have `wait` assert llm mode on its own, make `player` optional when exactly one is online, and add a `note` tool or say so plainly in the tool description.

### PD-109 update

A second live sighting on 2026-10-03: in `tripwire_hall` (frostworks floor 1, keystone 3) the player found no dispensers and "bedrock through one of the sides", which supports the shell overwrite cause above.

### PD-148: Lemon harness and log noise (Low, harness)
**Reported:** 2026-10-04-1.
- `context.recent` is capped at 20 events, so the 05:37 `bank` row had scrolled out by the time the player asked how his keystone changed; the operator could not state the levels gained. A "last bank" summary field would fix it.
- The server console receives `pdmark-<id>` lines about every 40 s and logs "Unknown or incomplete command" for each (harness markers sent as commands).
- The PD-139 "Block-attached entity at invalid position: BlockPos{x=9, y=65, z=1000710}" error fired again at 03:50:22, with no creative mode break this time (the player entered a room at 03:50:27, five seconds later; cause not found).
**Status:** Partly fixed 2026-10-05. The context snapshot carries `last_bank` (the latest `bank` event, read back from the journal, so it survives the 20 event `recent` window). The `pdmark-...` console lines: a remote command costs a marker command after it, and the llm keepalive sent one every minute; keepalive and `say` now fire without a reply (`lemon ... --quiet`), so the lines stay only for commands whose reply is read. Not done: the "Block-attached entity at invalid position" error, cause still unknown. Unit test `JournalFormatTest` covers `last_bank`; the console change has no automated test.

### PD-163: MCP lemon tools intermittently report "No player was found" (Medium, harness)
**Reported:** 2026-10-07-1, ~00:36:40 and ~00:50:5x. `lemon_reply` returned "No player was found" while `status` showed MrPinoy123456789 online; the first failure let the ask hit `lemon unanswered` at 00:37:23 (45 s fallback) even though a reply was attempted inside the window. A minute later the same call succeeded. On the second occurrence `lemon_say` failed the same way before a retry landed. Both times the player was mid-fight or just out of one.
**Status:** Mitigated (not reproduced; 2026-10-07). On the remote path a command's reply is read back out of the shared server log between a size mark and a marker command, and the MCP server shells out to a separate `pdserver.mjs` process per call, so two commands in flight (a `lemon_reply` and the llm keepalive's `lemon mode`, or a `wait_events` poll) could read each other's feedback: a stray "No player was found" from one lands in the other's reply window, which fits a success that reads as a failure and a failure that was not the player's. Every panel command, `run` and `fire`, now takes a cross process lock file in the temp directory (stale after 30 s) while its reply is read. If a real "No player was found" recurs with the player online, capture the matching lines of `latest.log`; the command's own reply and the server's disagree only if the selector truly failed.

### PD-169: Quit leaves the player in the overworld wearing dungeon gear (Medium)
**Reported:** 2026-10-07-2, ~03:52. After `/dungeon quit` on Ossuary floor 3 and leaving the lobby, he stood in the overworld still holding the dungeon pack while `survival_stashed: 2` showed his survival inventory parked. A relog reconciled it.
**Status:** Open, not reproduced. `InventorySwap.reconcile` runs every tick and on every level change, keyed on the dimension alone, so a lasting mismatch of overworld plus dungeon gear cannot come from the swap logic; `survival_stashed: 2` is also the normal reading while still inside the dungeon dimension. Needs the server log around 03:52 (an `Inventory swap failed` line would explain it) or a repeat with `survival_stashed` read in the overworld.

### PD-173: Floor identity is invisible and oversold (Medium)
**Reported:** 2026-10-07-2. 04:03: "each new floor just says the dungeon name, when it should also say the floor name." Confirmed: `FloorStartTitle.titleFor` renders `themeName` only, so every floor's big title repeats the dungeon name. At 04:27 he had to ask Lemon "what's this floor called?" on a floor he had just committed (great_taproot). Then 04:28: "it doesn't look like a drip cavern, it looks like a generic dungeon": "The Great Drip Cavern" generated eight generic hall_* rooms plus lush_hollow, so the named final floor has no distinct identity.
**Status:** Title half fixed (2026-10-08): the floor-start title is the floor's node name, with the dungeon name under it on the first floor of a trip (`FloorStartTitle`, test `FloorTitleTest`). Content half: owner ruling 2026-10-08 is to bias final nodes toward rooms that sell their name, which needs more rooms designed first. Open as a content task; until then named final floors can still generate generic halls.

### PD-176: lemon_reply does not cancel the unanswered fallback (Medium, harness)
**Reported:** 2026-10-07-2. At least five times (03:28:43, 04:04:17, 04:06:30, 04:14:18, 04:45:37) a `lemon_reply` that returned success still produced a `lemon unanswered` journal entry when the 45 s window expired; the delivered reply and the fallback both fire, and the log claims nobody answered. Distinct from PD-163 (which is the "No player was found" resolution fault). Also seen: an ask answered via ambient chat (`lemon heard`) leaves the formal ask pending to timeout.
**Status:** Mitigated (2026-10-08), root cause not shown. The server already clears every pending question when a reply lands (`LemonGameTest.aDeliveredReplyClearsThePendingQuestion`); the notes' own timeline has the reply at 04:06:5x after the fallback at 04:06:30, so these look like replies slower than the 45 s window (remote command round trips) rather than a cancel that failed. A reply that lands within two minutes of the fallback giving up is now logged `Lemon late reply` and journaled as `lemon_ask` with `answered_by: llm_late`, so the journal stops reading as unanswered. Raising `lemonFallbackSeconds` or calling `lemon_think` first is the harness side.

### PD-181: Three random doors conflict with the act system; players want to choose act and dungeon (High, design)
**Reported:** 2026-10-08-1, 04:00: "The 3 random doors doesn't really work with the new dungeon act system" then "players should be able to choose the act and dungeon". Confirmed as his single top pick at wrap (05:43): asked for "the first thing you'd change", he answered "Redesigning the dungeon selection." The random-offer model predates the act graph; under acts the offer can hide the dungeon he wants or push a dungeon he has finished, and the Spawner-Dungeon-first-door rule is a band-aid over the same problem.
**Status:** Wave B built 2026-10-09 (code, awaiting live check): the Astrolabe Room. The first staging room of a trip shows one act at a time as a row of 1 by 2 doors on the owner's pattern (`HallLayout`: spaces 2, 4, 6, 9, 11 and 13 filled middle outward, dungeons left to right by compass, the doorway slot at 7 and 8 empty); the owner right-clicks the astrolabe (a clock display over a copper plinth, with a click box) to turn to the next open act, sneak to turn back. Each door has the dungeon's token block as a doormat, a waxed sign naming it, and a copper bulb for its state (lit, oxidized when finished, dark with an iron door when locked by compass or by an unfinished act). Selecting a door dims the other bulbs and previews the dungeon exactly as before; the lever commits. The Endless Mine stands at an end of the same row once it opens. `Keystone.offers` asks `HallOffers` first (knob `hallEnabled`, default true; off restores the three random doors), `/dungeon reroll` says the doors are no longer dealt, and a repeat finish pays `repeatFinishEmeraldPercent` (50) of the finish emeralds. Tests: `HallLayoutTest`, `HallDataTest`, `HallRoomGameTest` (both wall orientations: doors, iron when locked, signs, plinth, three astrolabe parts, dismantle; offers match the act), `RoomFurnitureTest` updated. Deviations from the design reply, for the owner: special doors share the main row (its two ends) instead of the side walls; the wide glass preview wall is not built (the existing slot window and side window show the first room); the DESCEND lever stays where it was rather than on the astrolabe plinth; any member may open a door (the existing party decide rules apply) rather than only wishing; the astrolabe does not animate, it plays a chime and particles and shows an Act title; no Next line on a lobby sidebar yet; `act_turned` and `dungeon_selected` journal events are not written. Unverified live: all of it, especially how the row, signs and astrolabe read on screen and that the door previews still show the first room.
**Update 2026-10-10 (freshness pass):** three deviations listed above were closed after this entry: the whole 14 by 3 wall behind the doors is now the preview window with the bulbs kept (commit 1511eba); the DESCEND lever and its sign stand beside the selected door only, and the lever glints until the trip starts (commits 69938bc and b159f73); and a finished dungeon's sign reads Play again. Special doors still share the main row. Still unverified live: all of it (L55).

### PD-182: Scrap progression is flat; owner proposes rising costs and scaled rewards (Medium, design)
**Reported:** 2026-10-08-1, 05:02: "make it so each level requires more scrap than the previous and then make higher level floors reward more scrap appropriately." Current model charges a flat scrap-per-chart and pays flat per floor, so high-level floors feel identical to floor 1 and deep runs are not better paid.
**Status:** Wave C built 2026-10-09 (code, awaiting live check): the scrap curve. Going from compass c to c+1 costs `scrapCostBase + floor(c / scrapCostEvery)` (4 and 3: compass 1 costs 4, 5 costs 5, 10 costs 7, 25 costs 12, 50 costs 20), and a big haul crosses the rising prices level by level (`ScrapMath.bank`). A floor pays its dealt step plus `scrapPerAct` for each act above the first plus `scrapFinalBonus` on a final floor (`FloorPay.bonus`); an Endless Mine floor pays +1 per `endlessDepthEvery` floors down, capped at `endlessDepthMax`, instead of the act bonus; below the player's compass a floor pays `belowCompassPercent` (50) of that, at least 1. The bar shows the live price everywhere (`Compass 10: 4/7`: compass lore, sidebar, bank line, `/dungeon` compass command). Migration: a saved bar that already meets the new price settles into a level when it loads (`DungeonLog.Entry`), so a compass never drops; the pool-era conversion clamps to the new bar. The floor-pay chat line now says "(below your compass)". Tests: `ScrapMathTest` (cost table, rising-price banking, bonus, half below compass, migration), `DungeonLogTest`. Not built: the one-time "Levels now cost more" title on first join (it needs a persisted flag; the compass lore shows the new price instead). Unverified live: levels per trip at compass 10 to 15 (target about 2 per dungeon in the right act) and how "+1 scrap (below your compass)" reads.

### PD-183: Sculk mechanics read as two systems; sensor_gallery should be replaced (Medium, design)
**Reported:** 2026-10-08-1, 02:20: "two things, I don't like how these sculks act differently then the ones that spawn enemies, two this room needs to be replaced with something else" and "sculk-like things like the shrieker and sensor should read as stealth mechanics I think". The sensor gallery's sensors feed the omen bar while shriekers elsewhere spawn mobs; one block family does two unrelated things and the room itself did not land.
**Status:** Wave E built 2026-10-09 (code, awaiting live check): sculk is one stealth rule. Any room that holds a sculk sensor or shrieker listens (`PressureSources`): each sensor pulse fills the room's Heard meter, and a full meter (`sculkHeardMax` 4, the Ancient City `sculkHeardMaxAncient` 2) is an answer: darkness on the party, a wave, a Heard title, and the meter starts again; a shriek is an answer at once. A room with a spawner that is cleared without ever answering pays `sculkUnheardScrap` (1) into the haul of the members in it ("Unheard. +1 scrap"). The Warden wakes at the `ancientWardenAnswers`-th (2) answer on the Ancient City's final floor. The sidebar shows `Heard 2/4` while a member stands in a sculk room. `sensor_gallery` is retired (its manifest is gone; the spec and template stay for the situation tests) and the **Hush Gallery** replaces it in the Deepslate and Ancient City biases: a hall with a loud dripstone strip under two shriekers and a longer quiet wool ring, six sensors between, a spawner at the far end, nothing needed to cross. Tests: `SculkHeardTest`, `CapstoneRulesTest`, `SidebarLinesTest`, `RoomLibraryGameTest`. A full meter now makes the room's nearest shrieker scream through vanilla `tryShriek` (`PressureSources.shriekAt`); vanilla's own sensor-to-shrieker relay (8 blocks, wool blocks it) also shrieks and is counted by the same shriek handler. A sculk answer sends a **Warden whelp** (`Whelp`) in place of an omen wave: half-size Warden, melee only (`SonicBoomWhelpMixin`), 20 hp, 4 damage, speed 0.3, always angry at the nearest member, no loot, gone after 15 s, one at a time per instance, and the Heard meter cannot rise while it is out (config `whelpHealth`, `whelpDamage`, `whelpSpeed`, `whelpSeconds`). Unverified live: whether it skips the roar and how fast 0.3 feels against the player. Not built: the catalyst adding to the meter. Unverified live: whether `Heard 2/4` reads as stealth, and whether +1 scrap is worth sneaking for.

### PD-184: Capstone floors end flat; no finale fight (Medium, design)
**Reported:** 2026-10-08-1, 05:17: "There wasn't a boss for copper works" and 05:28: "There should be some extra challenge in the last floor". The Spawner Dungeon has its brood wave, but ordinary story dungeons (Copper Works cleared at 05:16) end on a normal floor with no escalation.
**Status:** Wave D built 2026-10-09 (code, awaiting live check): the finale. An ordinary dungeon whose JSON has a `finale` ends its final floor in a last stand (`FinaleWave`): when the final floor's spawners are cleared the title says the floor stirs, `finaleCountdownSeconds` (3) later a wave of the dungeon's own mobs stands up around the party, sized to it (`FinaleRules`: +`perMemberPercent` per member past the first, never over 40), and from Act 2 a named elite leads it with a red boss bar (health grows `finaleEliteHealthPercentPerMember` per extra member). The terminal pad stays shut until all of it is dead (the brood chamber's gate), and a won finale pays `finaleRewardChests` (1) extra reward chest. Authored: Mineshaft, Infestation, Ossuary, Rootworks (waves), Copper Works (The Foreman), Deepslate (The Deep Sentry), Frostworks (The Frost Warden); Cow Pits stays short; capstones keep their own fights and the loader refuses a finale on one. Knob `finaleEnabled`. Tests: `FinaleRulesTest`, `FinaleDataTest`, `FinaleWaveGameTest`. **Reworked 2026-10-09 (owner direction):** the Kennels are pillagers raising wolves to be cruel. Every untamed wolf in a Kennels instance goes for the nearest player in sight and keeps going (`HostileWolves`); only a tamed wolf, the Lost Dog's wolf and a pack whose guard has fallen are calm (tag `calm_wolf`). The Kennel Run's gates stand open and its wolves charge; no more feeding. New room **Guard Tower**: a pillager with a crossbow on a fenced tower with a ladder, wolves loose on the floor; kill the guard and the pack goes quiet. The Kennels' spawners (`kennels_tier_N`) are pillagers and wolves, with vindicators from tier 2 (no evokers, no ravagers); the finale adds a pillager handler. Feral is retired: no signature affix anywhere, its cube recipe gated out (`min_level` 999); Wolf Hollow in Rootworks is now the lost wolves floor (bias `lost_dog`). Differences from the design reply: no iron-door seal on the cell (the pad is the gate), the wave stands up around the party instead of at the last spawner's room, and no slime size is set. Unverified live: whether it lands like the brood wave ("barely enough") and whether an Act 1 wave is enough of an ending.

### PD-185: Copper Works mobs should wear copper gear (Low, content)
**Reported:** 2026-10-08-1, 04:55: "could we thematically make it so that all mobs wear two pieces of copper, including weapons?" scoped at 04:55: "just in this copper dungeon".
**Status:** Wave D built 2026-10-09 (code, awaiting live check): a dungeon can carry a `mobUniform` (armour pieces to draw from, pieces for melee and ranged mobs, a melee weapon, a chance). Copper Works has one: melee mobs (zombie family, vindicator, piglins) wear one random copper piece and a copper sword, skeletons and strays and pillagers keep their bow and wear two pieces, creepers and spiders wear nothing; nothing it wears drops. Applied when a mob loads into the dungeon (`MobUniforms.apply` from the entity-load hook), so the finale wave wears it too; mobs placed while a floor is being stamped, before the trip is known, do not. Knob `mobUniformEnabled`. Tests: `MobUniformsTest`, `FinaleDataTest`, `FinaleWaveGameTest`. Unverified live: whether copper armour lengthens a Copper Works clear noticeably.

### PD-186: Hopper-key toll room named worst room of the session (Low, design)
**Reported:** 2026-10-08-1, 05:43, in answer to "worst room tonight?": "The room with the key in the hopper to open the iron doors." That describes the `SpurToll` HOPPER_KEY rooms (barred_vault, ominous_bargain) or the Frame Lock room; all three ask the player to throw a specific item into a hopper to open an iron door.
**Status:** Wave E built 2026-10-09 (code, awaiting live check): the hopper-key toll is gone. `barred_vault` is now a vanilla vault block on a dais behind iron bars (`SpurVault`): an ominous floor stamps an ominous vault that takes the ominous trial key, any other floor the plain key, and the reward is the room's own vault table (`vaults/barred_vault`); using the key on it pays the relief life (`PressureSources.vaultPaid`). `ominous_bargain` lost its gold toll and its partition: the chest stands open behind the bottle, and taking from it is the bargain. `SpurToll` and its `TollRoomGameTest` are retired; `Locks.Kind.HOPPER_KEY` has no users left and stays in the code. The floor never needs the key, so the soft-lock rule holds. Tests: `VaultRoomGameTest`. Unverified live: that a trial key used on the vault pays out and reads as a vanilla vault.

### PD-187: Player wants a persistent floor/scrap display, not chat (Medium, design)
**Reported:** 2026-10-08-1, 02:14: "Is it possible to create a HUD? like maybe having the floor information and Chart/scrap information in the top left or right corner of the screen?" then 02:22: "persistent text should be more than enough". Same session at 02:12: "I never read any of the run messages in chat, I usually use them as a log if I think I've missed some information."
**Status:** Open, design. Server-side options: a scoreboard sidebar, a boss-bar style persistent text display, or an in-world display at the player. A real HUD needs a client mod; the player ruled persistent text sufficient. Design decided 2026-10-09 (`docs/DECISIONS.md`, wave A); not yet built. Wave A built 2026-10-09: `SidebarDisplay` and `SidebarLines` (floor, lives, party, spawners, haul or banked, compass; per-player packets on a private scoreboard; `/dungeon display on|off`; knobs `sidebarEnabled`, `sidebarRepaintTicks`). Tests: `SidebarLinesTest`. Lobby sidebar and the "Next:" line wait for the Astrolabe Room (wave B). Unverified live: how it sits next to the omen bar.

### PD-188: Feral affix feels out of place; wants wolf-themed content and a replacement (Low, design)
**Reported:** 2026-10-08-1, 02:31: "Feral seems out of place, we should instead make a wolf themed dungeon, make a wolf/pet themed floor for another dungeon, and create a rare generic wolf themed room. And then create a new affix to replace feral."
**Status:** Wave F built 2026-10-09 (code, awaiting live check): **Restless** replaces Feral in the rolls. A new affix operation `undead_rise_chance` (`AffixEffects.withUndeadRise`): a slain undead mob (zombie family, skeletons, wither skeletons, zombified piglins) on a Restless floor rises once more where it fell after two seconds, with souls and a groan first; 30 percent, 50 on an ominous floor; fire keeps it down and a risen mob does not rise again (`Restless`, `RestlessRules`). Feral stays as a floor's own signature and as the bone cube recipe, but is no longer rolled (`min_level` 999). Wolf content: **The Kennels**, a new Act 1 dungeon (Lodge Gate, Kennel Rows, Hunting Grounds, Bone Yard, The Alpha's Den; theme `kennels` on the Ossuary's spawners and loot; diary entry 26, band 10; the Spawner Dungeon capstone now waits for it too); a wolf floor, the **Wolf Hollow**, in Rootworks (it was to be the Ossuary's Hound Crypt, but the Ossuary's entry floor already has the most exits a floor may have); three rooms: `kennel_run` (pens with stray wolves), `alphas_den` (the Kennels' final room) and the rare generic **Lost Dog** (clear the camp and the dog is yours; any Act 1 or 2 dungeon may draw it). The Kennels' finale is five angry wolves led by The Alpha. Pets: a pack cap, `petCap` 3 per player, the extras sit (`PetCap`). Tests: `RestlessRulesTest`, `FinaleDataTest`, `FloorNotesTest`, `RoomLibraryGameTest` (the new rooms' connectivity). Differences from the design reply: the Kennels' stone and wood come from a copy of the Ossuary's processor list with spruce for the Ossuary's bone; no tier-by-tier new loot. Unverified live: whether Restless reads from the board and whether fire counterplay is reachable without fire aspect.

### PD-189: Mine-themed floors set an ore expectation the content does not meet (Low, design)
**Reported:** 2026-10-08-1, 02:49 on Endless Mine floor 1: "Still yet to see any hidden ores in this room." Hidden ore is Mineshaft-only (`hiddenOre` in the dungeon def); the player generalises it to anything that looks like a mine. Also 02:43 on `deep_shaft_landing`: "This room is very good, but it didn't have enough ore in it."
**Status:** Wave G built 2026-10-09 (code, awaiting live check): the Endless Mine now hides ore (`hiddenOre`: one or two pockets of one to three coal or iron in the rock of every room) and gets richer with depth: every `deepEvery` (4) floors down the mix gains deepslate iron and gold and a room may bury one more pocket, up to two more (`DungeonDef.HiddenOre.atDepth`, `NodeStamper.contextFor(..., floorNumber)`). Its door-board line now reads "Ore hides in the walls. Deeper pays better." `deep_shaft_landing` holds more ore (iron 3 to 5, plus two coal). The per-floor block cap from the reply was not built (rooms are capped by their pockets). `FloorNotesTest` already fails a floor note that names an ore its floor cannot deliver. Tests: `HiddenOreDepthTest`, `BoardTextTest`. Unverified live: that the first Endless Mine floor shows ore in the walls and that digging finds it.

### PD-190: Players do not read run messages in chat live (Medium, design)
**Reported:** 2026-10-08-1, 02:12: "I never read any of the run messages in chat, I usually use them as a log if I think I've missed some information." Every system that delivers critical state through chat lines (floor pay, key redemption, omen notices, milestone fanfare) is invisible in the moment for this player.
**Status:** Open, design. Audit which critical lines are chat-only and move them to titles, the omen bar, action bar, boards or the persistent display from PD-187. Chat stays as the log, by his own framing. Design decided 2026-10-09 (`docs/DECISIONS.md`, wave A); not yet built. Wave A built 2026-10-09 (first slice): a failed dungeon shows a title ("The dungeon claims you", what was kept), a bank shows an action bar line, the finished dungeon title carries the fanfare. The rest of the audit (shop and salvage results, refusals, key redemption) is still chat-only.

### PD-191: A player should be able to join another party from inside their own dungeon (Medium, design)
**Reported:** 2026-10-09, owner: "Someone should be able to join another party from within their own dungeon." Today `PartyService.join` refuses when the player already has an instance ("You are already in a dungeon. Use /dungeon exit first.") and `invite` refuses a target who is in any dungeon (`InstanceRegistry.byMember`), so a player mid-run must quit first, and a quit is a fail for the haul.
**Status:** Built 2026-10-09. The owner's leaving rule (2026-10-09): **leaving a floor in progress for any reason (exit, kick, disconnect, joining another party, going to a different lobby) is a failed dungeon for the leaver, the fail share (`failHaulKeepPercent`); leaving between floors for any reason cashes out in full.** There is no separate forfeit: cashing out is the only thing that keeps the whole haul. Joining another party is leaving the current one, so the same rule applies. An owner who leaves a floor in progress fails the dungeon for everyone; between floors the run ends as at a checkpoint. `PartyService.invite` accepts a target in a different dungeon and warns them; `join` validates the invite, then `leaveOwnDungeon`. Tests: `JoinFromDungeonGameTest`. Unverified live: the full `/dungeon invite` then `/dungeon join` flow between two real accounts and the invite wording.

### PD-208: Mine guardians die before their mechanics register for an overleveled player (Medium, tuning/readability)
**Reported:** 2026-10-13-1, deep mine floors 3 and 6, player at compass 15 in full diamond. Deep Sentry slain at 05:40:19 with no "the deep heard you" whelp answer during its own fight ("I fought the whelp a couple times but didnt get caught during the deep sentry fight"). Foreman slain at 05:56:13; asked about the vents and the water vat: "I didnt see any of that" and then "The foreman was just a very fast pillager". Both guardians are alive long enough to be killed but not long enough to be themselves.
**Status:** Owed. Two unverified roots: (a) tuning, the guardians' HP/damage budgets let a geared player burst them under the mechanic's windup; (b) the Foreman's vent/vat decor may not have placed or pulsed at all on that floor, which would be a bug not a tuning miss. Check a floor 12 repeat (or a biased Foreman rerun on a less-geared account) before tuning, and confirm the vent decor actually spawns live.

### PD-211: The door board's reward line dumps the whole node palette (Medium, UX)
**Reported:** 2026-10-16-1, blackstone door previews at level 21-23. "Two of these doors have way too much text"; the rewards part read verbatim "2 scrap, gilded blackstone, nether gold, ancient debris, warped stem, crimson stem, warped wart block, nether wart block, enchanted diamond pickaxe" and "most of those are useless too".
**Likely cause:** `DungeonScreen.previewGets` stacks scrap, every `BoardText.paletteWords` entry for the node's ore palette, the node rewards, and the wagered copper-chest piece onto one line. A deep palette alone is six to eight words.
**Status:** Fixed 2026-10-16, owner-ruled. `BoardText.paletteWords` now collapses same-purpose blocks: ores read as the metal (`nether_gold_ore` becomes `gold`), logs/stems/hyphae become `wood`, sand and red sand become `sand`, and low-purpose blocks (gilded blackstone, amethyst, clay, wart, foliage) are dropped entirely. Covered by `BoardTextTest`.

### PD-212: Crimson roots pop loose on their own in crimson_forest (Medium)
**Reported:** 2026-10-16-1, `crimson_forest` on a blackstone floor 3: "crimson roots not properly on the ground and loose able to be picked up", confirmed "on their own" (no blast). Verified the baked template is correct: every `crimson_roots` sits on `crimson_nylium`, and `theme_blackstone` only remaps stone_bricks, polished_andesite and sea_lantern, so neither the bake nor the theme explains the pop. A post-stamp pass or placement order is dropping or replacing the support.
**Status:** Open, repro live once. Check what writes the cell floor or decor after `TemplateStamper` on a loot-role cell (window band, pressure sources, room content) and whether the stamp's block-update flags pop the roots at placement.

### PD-213: A released stray mob lingers and stays audible after PD-113 (Medium)
**Reported:** 2026-10-16-1, first live run on the releaseStrays jar: "I can hear enderman even though they were from another floor, they're probably on the roof of the room or something." The release did its gate job (the run kept clearing floors), but the escaped enderman itself survived: `TrialContent.releaseStrays` only discards a stray it can find, and a stray on the shell roof may still sit inside `layout.bounds()`. Either way the entity persists where the player can hear it.
**Status:** Fixed 2026-10-16, owner-ruled. The problem is endermen specifically (the only trial mob that teleports out of rooms), so rather than widening the stray sweep the enderman was removed from all trial spawner configs: `ender_archive`, `the_end` and `herobrine`, all three tiers, normal and ominous (18 files). `TrialContent.releaseStrays` is unchanged. Covered by `TrialSpawnerPoolTest`. Owner noted enderman will get a new purpose later. Unverified live: whether any other stray can escape a room (none known; all remaining pool mobs are ground-bound).

### PD-214: elders_chamber's water sheet bleeds into the next room (Medium, UX)
**Reported:** 2026-10-16-1: "the room with water along the entire floor bleeds into the adjacent room, there should be a perimeter wall containing that water to that room without blocking players from entering". The room is `elders_chamber` (the flooded elder-guardian cell, not sorting_floor): `KnowledgeSpecs.eldersChamber` laid a water sheet at y=1 across x 2..13, z 2..13 and the flow reached the west/east door lanes.
**Status:** Fixed 2026-10-16, owner-authored. The room was rebuilt in the room editor and mirrored into `KnowledgeSpecs.eldersChamber`: a stone-brick ring at y=1 (x=1 and x=14, deepened one block beside each lane) holds the sheet, and the lane gaps are closed by bottom stone-brick stairs at x=2 and x=13, z=7..8. Flowing water cannot waterlog a stair, so the lip blocks the sheet but not the player. The sheet now spans z=1..14 inside the ring. `still_pond` got the same treatment (brick bank at x=3 and z=1/z=14, decor rearranged inside). `HandlerGameTest`'s lane check learned that a step-over at floor level is enterable. Templates regenerated and verified identical to the editor saves. Covered by `PlaytestFixGameTest.eldersChamberKeepsItsWaterInside`. Unverified live: the visual read of the lip in game and whether the step-over feels natural.

## Closed ledger

| ID | Summary | Resolution |
|---|---|---|
| PD-1 | Timer boss bar keeps ticking after dungeon completion (UI) | Fixed (2026-08-27) |
| PD-2 | Same affixes every run at the same keystone level | Fixed |
| PD-3 | Feral wolves spawn angry, are glitchy, and do not attack | Fixed (2026-08-27), parts 1 and 2. Part 3 (`setHomeTo` radius) fixed 2026-09-03: player reported wolves sti... |
| PD-4 | Stale FRAGILE references after M10 reframe | Fixed (2026-08-27) |
| PD-5 | resetroom deletes room but lobby regenerates without proper shell | Fixed (2026-08-27) |
| PD-6 | Lobby bedrock envelope leaves selector door side open to void | Fixed (2026-08-27) |
| PD-7 | Timeout closes the dungeon instead of just downgrading the key | Fixed (2026-08-27) |
| PD-8 | Room changes lost when dungeon closes via timeout or teardown | Fixed (2026-08-27) |
| PD-9 | `/dungeon key info` throws an NPE for any player without a keystone (Critical) | Fixed (2026-08-31) |
| PD-10 | Lingering quarries are never purged, leaking a slot per completed run (Critical) | Fixed (2026-08-31) |
| PD-11 | Stamp failure behind the lobby erases the player's room and double-frees the slot (Critical) | Fixed (2026-08-31) |
| PD-12 | Disconnect handler mutates instance state off the server thread (Critical) | Fixed (2026-08-31) |
| PD-13 | Force-load tickets leak across runs and survive server restarts (Critical) | Fixed (2026-08-31) |
| PD-14 | No startup reconciliation after a crash orphans geometry and forced chunks (Critical) | Fixed (2026-08-31) |
| PD-15 | M34 bounty block over-counts three bounties and never advances a fourth (High) | Fixed (2026-08-31) |
| PD-16 | Operator lootOverride writes an unclamped keystone level (High) | Fixed (2026-08-31) |
| PD-17 | Reroll station can produce a strictly worse enchantment (High) | Fixed (2026-08-31) |
| PD-18 | Room directory admits a visitor into the owner live keystone run (High) | Fixed (2026-08-31) |
| PD-19 | Room-theme filter is inert, no shipped JSON supplies either half (High) | Fixed (2026-08-31); M42.2 paired four rooms (crypt_corner; |
| PD-20 | Voided-cell selection is not reproducible across JVM restarts (High) | Fixed (2026-08-31) |
| PD-21 | Routed dialog clicks act on a possibly disconnected player (High) | Fixed (2026-08-31) |
| PD-22 | Adventure graph validation is single-pass and order-dependent (High) | Fixed (2026-08-31) |
| PD-23 | Two of three stations enforce no unlock level at use time (Medium) | Fixed (2026-08-31) |
| PD-24 | Dialog actions bypass the station block and the level gate entirely (Medium) | Fixed (2026-08-31) |
| PD-25 | Task progress is awarded for opening a station, not for using it (Medium) | Fixed (2026-08-31) |
| PD-26 | Death rescue detaches a member by hand and skips the leadership rule (Medium) | Fixed (2026-08-31) |
| PD-27 | IRON_DOOR connector can gate the critical path with no redstone source in the mod (Medium) | Fixed (2026-08-31) |
| PD-28 | Iron door pair does not alternate hinge and uses the wrong facing convention (Medium) | Fixed (2026-08-31) |
| PD-29 | manifest reload command reloads one of five manifests (Medium) | Fixed (2026-08-31) |
| PD-30 | Diaries never reload on /reload (Medium) | Fixed (2026-08-31) |
| PD-31 | infestation theme has no adventure node and is unreachable (Medium) | Fixed (2026-08-31); M42.3 authored |
| PD-32 | Themed loot table suffix never resolves on an ominous run (Medium) | Fixed (2026-08-31); M42.4 added six small loot tables that |
| PD-33 | Cube extraction consumes the item before writing the state (Medium) | Fixed (2026-08-31) |
| PD-34 | Deferred room save in eject races the clear that purge just queued (Medium) | Fixed (2026-08-31) |
| PD-35 | Lobby directory is unbounded, contradicting the suite own pagination convention (Medium) | Fixed (2026-08-31) |
| PD-36 | admin experiment echoes the raw affix string, not what was actually applied (Low) | Fixed (2026-08-31) |
| PD-37 | Room names are unvalidated legacy-formatting text shown to other players (Low) | Fixed (2026-08-31) |
| PD-38 | Player-facing commands return success unconditionally (Low) | Fixed (2026-08-31) |
| PD-39 | stamptest force-loads chunks and never releases the tickets (Low) | Fixed (2026-08-31) |
| PD-40 | Gamble draw silently discards every stack past the first (Low) | Fixed (2026-08-31) |
| PD-41 | Keystone reconcile discards stack count on a stackable configured item (Low) | Fixed (2026-08-31) |
| PD-42 | Integer overflow in PayoutMath chestCount (Low) | Fixed (2026-08-31) |
| PD-43 | Attribute listener maps grow without a disconnect hook (Low) | Fixed (2026-08-31) |
| PD-44 | pendingReturns never expires (Low) | Fixed (2026-08-31) |
| PD-45 | Config accepts inviteTtlSeconds of 0, silently disabling invites and kick confirmations (Low) | Fixed (2026-08-31) |
| PD-46 | pathLengthMax is never cross-checked against maxGridSpan (Low) | Fixed (2026-08-31) |
| PD-47 | keystoneMaxLevel is never cross-checked against the level gates it caps (Low) | Fixed (2026-08-31) |
| PD-48 | Fuel matches by item type only and destroys custom-data items from other mods (Critical) | Fixed (2026-08-31) |
| PD-49 | `timedOutPenaltyApplied` still not reset between runs behind the same lobby (High) | Fixed (2026-08-31) |
| PD-50 | Void inventory is dropped on the ground on exit, and not restored on re-entry (Critical) | Fixed (2026-09-03) |
| PD-51 | Station blocks are usable-gated but not obtainably-gated (Medium) | Fixed (2026-09-03) |
| PD-52 | The blacksmith villager can open the room door and let dungeon mobs in (Medium-High) | Fixed (2026-09-03) |
| PD-53 | `/dungeon admin resetroom` leaves the player with an unselectable stone brick shell (Low) | Fixed (2026-09-03). User confirmed the reset behavior itself |
| PD-54 | Chests and vaults yield far more items than intended, and vaults carry a resources pool that should be chest-only (Medium) | Fixed (2026-09-03), user-directed and confirmed a second |
| PD-55 | Every iron door connector ships with its own lever attached, defeating the point of a locked door (Low-Medium) | Fixed. Resolved 2026-10-06 by PD-160: the lever moved off the frame onto another wall of the same cell (sha... |
| PD-57 | Free re-entry duplicated the keystone compass once per re-entry (High) | Fixed (2026-09-03), same session as the report |
| PD-59 | Reward room still felt loot-heavy after PD-54; durability items always spawned pristine; coal too common | Fixed (2026-09-03). Item 1 was flagged rather than fixed |
| PD-60 | Individual stacks still read as too generous even at 1-2 items per chest | Fixed (2026-09-03). |
| PD-61 | PD-60's cut was not aggressive enough | Fixed (2026-09-03). |
| PD-62 | Player permanently locked in a room, trapped by an iron door with no lever on their side (High) | Fixed (2026-09-03). |
| PD-63 | Trial-spawner and exit-pad completion silently stops registering for a player who died mid-run (High) | Fixed (2026-09-03). |
| PD-64 | No self-service way to despawn a stuck or unwanted run and start over | Fixed (2026-09-03). |
| PD-65 | Void inventory lost on every exit, including "Leave Dungeon" (Critical) | Fixed. The void inventory is now persisted as an |
| PD-66 | Directional gates are never placed in any room (High) | Fixed 2026-09-27. `applyDirectionalGates` qualifies the name before matching (`JsonPackSupport.qualify`). V... |
| PD-67 | Hold the Plate's redstone cannot open its door (High once PD-66 is fixed) | Fixed 2026-09-27. The count moved into code (`HoldThePlateHandler`): stand on the plate 30 s, the action ba... |
| PD-68 | Thicket's spawner never spawns (Medium) | Fixed 2026-09-27. The Thicket and Ice Run handlers reconfigure the classic spawner at stamp time (`ClassicS... |
| PD-69 | Tools from mobs or loot keep full vanilla durability (Medium) | Fixed 2026-09-27. `DungeonDrops` caps every item that appears in the dungeon (loot table drops and dropped ... |
| PD-70 | HOME and Descend levers swap sides between floors (Medium) | Fixed 2026-09-27. The levers, the go-home screen and its bulb go through `RoomGeometry.viewerAlong`, which ... |
| PD-71 | The spyglass is an ungated dev tool that eats block clicks (Low) | Fixed 2026-09-27. The coordinate tool is operator only, and crouching skips it. |
| PD-72 | Ledge Archers: skeletons spawn on the floor and loot ejects out of reach (High) | Fixed 2026-09-27. The ledges are barred at both ends and the spawner top is barred (template regenerated), ... |
| PD-74 | A death rescue does not shake off the mob; an enderman follows into the staging room (High) | Fixed (2026-09-28). |
| PD-75 | Explosive affix pressure plates drop as free items (Low) | Fixed (2026-09-28). |
| PD-76 | ECHO SHARDS engine screen: overlapping stale text, all gold, no fuel name (Low) | Fixed (2026-09-28). |
| PD-77 | Lemon lingers up to a minute after the agent has answered (Low) | Fixed (2026-09-28). |
| PD-78 | `blaze_cellar` fails to stamp because it has no climbable return path (High) | Fixed 2026-09-30, live roll pending. The room's "staircase" (`SituationSpecs.blazeCellar`) was a solid ston... |
| PD-79 | `server wait` drops the player's questions to Lemon (High) | Fixed 2026-10-01, live check owed (`LIVE_CHECKS.md` L6). Diagnosis from the 2026-09-30 and 2026-10-01 logs:... |
| PD-80 | `server wait` often reports `server is down (timeout)` while the server is up (Low) | Fixed again 2026-09-30, verified by one live quiet wait in `2026-09-30-2.md` and two more in `2026-10-01-1.... |
| PD-81 | `/dungeon quit` still costs 2 keystone levels; the decided cost is 1 (Medium) | Fixed 2026-09-29 (configs updated; code default already 1). |
| PD-82 | Lemon can be handed equippable items; allay give animates client-side (Low) | Fixed 2026-09-29 (pending restart to verify in game). |
| PD-83 | `breeze_arena` breezes hop off the spawn platform into lava and die (Medium) | Fixed 2026-09-29, verified in game 2026-09-29-2 ("Breeze arena was good"; floor logged 383 durability spent... |
| PD-84 | GO HOME lever stands beside door 3; a player reaching for a door banked by accident (High) | Fixed 2026-09-29, verified in game 2026-09-29-2: two deliberate `home_lever` banks journal `trigger: home_l... |
| PD-85 | The door-preview glass sits behind the selector doors; the doors block the view (Medium) | Fixed 2026-09-29 (pending in-game look). The preview now also cuts a side window, 1 wide by 3 tall, at abso... |
| PD-86 | `thicket` cave spider spawner never fires (Medium) | Fixed 2026-09-29, verified in game 2026-09-29-3 ("yes in a good way, it works": cave spiders spawn; the roo... |
| PD-87 | A trial key does not stack with other trial keys and does not work (Medium) | Fixed 2026-09-29. Cause confirmed: the nine chest tables (`chests/tier_N`, `_drowned`, `_ominous`) and two ... |
| PD-88 | The explosive hazard's plate support makes the trap untriggerable by accident (Low) | Fixed 2026-09-29 (pending in-game look). The stone block was not the intended design but its fallback: `fin... |
| PD-89 | Crafted diamond gear sidesteps the durability economy (Medium) | Fixed 2026-09-30, pending in-game verify. Not a design gap: a craft-path leak. The mod already caps crafted... |
| PD-90 | `room_bias` has no lockout, slow apply feedback, no timeout, and the door sign does not show it (Low) | Fixed 2026-09-30, pending in-game verify. (1) Lockout: `/dungeon admin bias hold <player> [seconds]` (MCP `... |
| PD-91 | Rising Lava never armed in a real run; its lava never spread (Medium) | Fixed 2026-09-30, pending in-game verify. |
| PD-92 | Max-omen ejection duplicates interval-start items into the kept pack (High) | Fixed 2026-09-30, verified in game 2026-10-01. `restoreIntervalSnapshot` restores only the live inventory; ... |
| PD-93 | An anomaly room in the plan cannot stamp because the stamper reads the wrong manifest (High) | Fixed 2026-10-01, live commit pending. `LayoutStamper.entryAt` resolves the anomaly cell's room from `RoomM... |
| PD-94 | Ominous loot grants inert Boss Stones when Kamu Totems is absent (Medium) | Fixed 2026-10-01 by removing the integration (owner decision). The eight Boss Stone entries (`kamutotems:{b... |
| PD-95 | Bag-tagged common loot does not stack with identical plain loot (Medium) | Fixed 2026-10-01, partially verified in game. Only items that stack to 1 carry the bag tag now: 645 bare `{... |
| PD-96 | Salvage Bench is implemented but has no in-game discovery path (Medium) | Fixed 2026-10-01, live use observed but discovery still unverified. In the dungeon dimension, any non-sneak... |
| PD-97 | `sump` fails to stamp because it has no climbable return path (High) | Fixed 2026-10-01, live commit owed (`LIVE_CHECKS.md` L1). The cause matched PD-78: `SituationSpecs.sump` bu... |
| PD-98 | `kennel_crossing` situation room stamps with no spawner at all (High) | Fixed 2026-10-01, pending in-game verify. The 21:27:11 log line was not kennel_crossing: the kennel stamped... |
| PD-99 | `rotation_lock` comparator appears not to open the door (Medium) | Fixed 2026-10-01, pending in-game verify. The player was right: a comparator or repeater `FACING` is the IN... |
| PD-100 | Omen sensor line flashes too briefly to read (Low) | Fixed 2026-10-01, pending in-game verify. The sensor line is repainted on the action bar every 2 seconds fo... |
| PD-101 | Salvage Bench swallows the held item on open (Medium) | Fixed 2026-10-01, pending in-game verify. `SalvageStation.onUse` no longer moves the held stack; it only op... |
| PD-102 | Commit stamp failed behind the staging room, then disconnect in PREVIEW (High) | Fixed 2026-10-01 (cause was PD-97). The logged exception is `IllegalStateException: room pocketdungeons:sum... |
| PD-103 | Blacksmiths wander into the staging room and multiply (Medium) | Fixed 2026-10-01, pending in-game verify. `BlacksmithNPC.findAllBlacksmiths` now scans 24 blocks each way h... |
| PD-105 | Lemon's journal can be taken from her menu (Low) | Fixed 2026-10-02 (second pass), pending in-game verify. Cause (review 2026-10-02, F4): client-side predicti... |
| PD-106 | Mob spawners drop too much trimmed armour (Medium) | Fixed 2026-10-02, pending in-game verify. Cause: five tier 3 spawner configs (`copper_works`, `crypt`, `fro... |
| PD-107 | A single spawner mixes unrelated mob types (Medium) | Fixed 2026-10-02, pending in-game verify. Cause: themed spawners were already two mob types, but themeless ... |
| PD-108 | Some gear cannot be salvaged (Medium) | Fixed 2026-10-02, pending in-game verify. Cause: almost certainly trimmed vanilla trial chamber armour, whi... |
| PD-109 | Tripwire traps in the tripwire room are deleted by the room shell (Medium) | Fixed 2026-10-03 as PD-136 (the corridor role removed the dispensers). Earlier notes: open, not reproduced ... |
| PD-110 | Spiders climb walls and get stuck in room corners (Low) | Fixed 2026-10-02, pending in-game verify. Chose the tick check over template geometry (no template recaptur... |
| PD-111 | Spawner gate message should state how many remain, not just "go finish the rest" (Low) | Fixed 2026-10-02. The gate now says "Not yet: clear N more trial spawner(s).", using `DifficultyProfile.spa... |
| PD-112 | SENSOR omen line needs a sound cue (Low) | Fixed 2026-10-02, pending in-game verify. A quiet `SCULK_CLICKING` already played; it is now `WARDEN_NEARBY... |
| PD-115 | Tridents cannot be salvaged (Medium) | Fixed in code 2026-10-13, live check owed. `classify` always took the trident (damageable, so MOB_GEAR), bu... |
| PD-116 | Selecting a staging door replaces its light with the preview glass; door 3 has no glass (Medium) | Fixed in code 2026-10-13, live check owed. `windowDoor` filled the slot (alongs 7..8, Y1..3) with glass, wh... |
| PD-117 | The floor history board's row text is still too small (Low) | Fixed in code 2026-10-13, live check owed. `HISTORY_SCALE` 0.86 to 1.03 (about 20 percent); ten rows still ... |
| PD-118 | Lemon tooling hides and delays player questions (High, harness) | Fixed in code 2026-10-13, live check owed on the real panel. The cursor moved into `tools/server/chat-curso... |
| PD-120 | The session had no Lemon MCP tools (Low, process) | Fixed 2026-10-02: `.mcp.json` at the workspace root registers `lemon` (`tools/server/mcp.mjs --admin`), and... |
| PD-121 | A failed first run empties a new player's kit (High) | Fixed 2026-10-03. The interval snapshot is now retaken at the first door commit, after the bag has been app... |
| PD-122 | Keystone 1 rolls tier 2 and 3 rooms (Medium) | Fixed 2026-10-03. Room selection now enforces `room.tier <= lootTier(offerLevel)`. |
| PD-123 | The collapsing_bridge ordeal never arms (Medium) | Fixed 2026-10-03. |
| PD-124 | The explosive affix's TNT breaks room mechanisms (Medium) | Fixed 2026-10-03. |
| PD-125 | hold_the_plate with ranged skeletons pins the player (Low) | Fixed 2026-10-03. |
| PD-126 | A protected door gives no reason (Low, needs repro) | Fixed 2026-10-03. |
| PD-127 | The salvage bench's wording reads as a rule (Low) | Fixed 2026-10-03. |
| PD-129 | Home room greets as "Uncharted" (Low) | Fixed 2026-10-03, live check owed. Cause confirmed: entering through the lodestone lands in the home lobby,... |
| PD-130 | Public room still says nobody may enter (Low) | Fixed 2026-10-03. The listing and the whitelist are two lines now (`DialogScreens.listingLine`, `whitelistL... |
| PD-131 | Bag chest vanishes after first pick; party member cannot use it (High) | Fixed 2026-10-03, live check owed. The server log shows SirAegerus joining slot 0 at 03:52:47 and again at ... |
| PD-132 | Party member intermittently locked out of stations and building (High) | Fixed 2026-10-03, live check owed. Two causes. (1) `RoomProtection.isPermitted` allowed only the owner and ... |
| PD-133 | Entrance leads only into a sealed rotation_lock (Blocker) | Fixed 2026-10-03, live check owed. Cause: every gated room is a straight pass-through authored with its ent... |
| PD-134 | Tamed wolf does not count as defeated (Medium) | Fixed 2026-10-03. A trial spawner finishes only when every mob it spawned is dead, and a tamed wolf never d... |
| PD-135 | Salvage bench refuses a leather cap; disenchant needs an item (Medium) | Fixed 2026-10-03. Reason found in the tables: chest loot is tagged `{tier, bag}` (the bag tag keeps it in t... |
| PD-136 | Tripwire hall bedrock holes (Medium) | Fixed 2026-10-03, also closes PD-109. `RoomContent.containers` returned every randomizable container, so th... |
| PD-137 | Store bias never produced a Store (Medium) | Fixed 2026-10-03. Cause: the four spur rooms (`the_store`, `the_altar`, `barred_vault`, `ominous_bargain`) ... |
| PD-138 | Lemon harness gaps (Medium, harness) | Fixed 2026-10-03. Party chat now also logs `Lemon heard <speaker> text`, which `wait` prints in place of th... |
| PD-139 | Log noise (Low) | Fixed 2026-10-03 where it was ours. "Room tier gap": the designated gate cell narrowed its pool to gated ro... |
| PD-140 | The Store is entered from the wrong side (Medium) | Fixed 2026-10-05. It was not the rotation: a one-door spur has one rotation per cell and its template turns... |
| PD-141 | The Store's vendor wanders out of the shop (Medium) | Fixed 2026-10-05. The shopkeeper is `NoAI` and stays where it spawns; it still turns to a customer. Gametes... |
| PD-142 | Store purchase and vendor naming do not feel vanilla (Low) | Fixed 2026-10-05, owner calls taken. (1) The vendor is named for what he buys: the first of his currencies ... |
| PD-143 | The Altar's redstone falls off; its key is free (High) | Fixed 2026-10-05. Support blocks stand under the comparator, repeater and dust at x 9, the dropper starts e... |
| PD-144 | sorting_floor is not a working gate (High) | Fixed 2026-10-05, three causes. The filter hopper was pre-loaded with four stacks of sticks, which it pushe... |
| PD-145 | kennel_crossing spawner spawns nothing on ender_archive (High) | Fixed 2026-10-05. The light guess was right, and it was every theme, not only ender_archive: the pen stood ... |
| PD-146 | Gear above the dungeon durability cap (Medium) | Fixed 2026-10-05. The entry was the Store: it sells iron swords and helmets, and a sale reached the pack th... |
| PD-147 | collapsing_bridge arrives collapsed and is wooden on a fire floor (Medium) | Fixed 2026-10-05. Two causes. The bridge was oak over a lava floor, which lava lights; it is crimson planks... |
| PD-149 | Resource dungeon floors can roll zero nodes (High) | Fixed again 2026-10-06, live check owed. Not reproduced in a running server (the session that fixed it had ... |
| PD-150 | Run storage reads as wiped when the run closes offline (Medium) | Fixed 2026-10-05, live check owed. Mechanism confirmed, no data loss: `InstanceTeardown.purge` calls `RunSt... |
| PD-151 | Door screen text is too much and too technical (Low) | Fixed 2026-10-05, live check owed. The door screen drops the "KEYSTONE n" restatement and the step line rea... |
| PD-152 | Floor-start title restates the keystone (Low) | Fixed 2026-10-05, live check owed. `OmenBarText.previewFloor` now reads "MINESHAFT: FLOOR 2" (and "MINESHAF... |
| PD-153 | Spare doors are identical when steps are flat (Medium) | Superseded 2026-10-06. The owner rejected the label after seeing it live: "if they are the same then they s... |
| PD-154 | Dark floors arrive with no warning (Low) | Fixed 2026-10-05, live check owed. The door screen reads the node's `light` and shows one line: "Dark floor... |
| PD-155 | Mineshaft ore rooms are too generous (Low, balance) | Fixed 2026-10-05, live check owed. `NodeSpec` gained an optional `chance` (seeded probability the whole poc... |
| PD-156 | Vanilla warnings leak into MCP tool replies (Low, harness) | Fixed 2026-10-05. `wait-filter.mjs` exports `isVanillaNoise`, and `remoteCommand` in `pdserver.mjs` drops t... |
| PD-157 | Diary book pages clip text (Medium) | Fixed 2026-10-06, live check owed. `DiaryDelivery.book` fits each authored page onto as many book pages as ... |
| PD-158 | Omen bar does not track interval omen (Medium) | Fixed 2026-10-06, live check owed. Cause: `OmenBar.sync` filled the bar with `Omen.bandProgress(sum, floorC... |
| PD-159 | Store left-click can take stock without delivering (High) | Fixed 2026-10-06, live check owed. Both faults lived in the trading screen: the clean-up ran on the output ... |
| PD-160 | Iron door connector with no reachable opener (High) | Fixed 2026-10-06, live check owed. The reported door was almost certainly not the connector: `TraversalSpec... |
| PD-161 | Door board advertises ore the floor never had (Medium) | Fixed (honest board, 2026-10-07). `DungeonScreen.previewContent` asks `planHoldsNodes` of the plan the prev... |
| PD-162 | Scrap lore reads off the spendable pool, not the level (Medium) | Fixed by the model change (2026-10-07): "Haul and Blood Doors", see docs/DECISIONS.md. The compass is the o... |
| PD-164 | barred_vault is broken and its filter hopper is lootable (High) | Fixed (2026-10-07). The old template could not work: the hopper pointed into the door block, the comparator... |
| PD-165 | First-clear milestone title not noticed on either finish (Medium) | Fixed, verified live 2026-10-08 (`2026-10-08-1.md`): on the Spawner Dungeon capstone finish he reported "'T... |
| PD-166 | No admin command can grant or set scrap (Low, harness) | Fixed, then reshaped by Haul and Blood Doors (2026-10-07): `/dungeon admin compass <player> set <level> [pr... |
| PD-167 | Owner death ends the run for the whole party (High) | Fixed (2026-10-08). `Instances.rescue` detaches and re-admits a player who has a room to be rescued into in... |
| PD-168 | Quit and purge exits never settle the haul (High, design) | Fixed for quits (2026-10-08, owner ruling: a quit is a fail). `RunLifecycle.applyQuitPenalty` banks every m... |
| PD-170 | barred_vault toll rejects ominous trial keys (High) | Fixed (2026-10-08). `Locks.matches` accepts an ominous trial key for the trial key toll (and takes it), the... |
| PD-171 | Rubble sat on the route toward the required vault (Medium) | Fixed (2026-10-08). Cause: `RoomSelector` only treated cells with a TRIAL_ENCOUNTER role as required, but s... |
| PD-172 | the_herd's sunk gold is unmineable (Medium) | Fixed (2026-10-08). `KnowledgeSpecs.placeHerdGold` stands the four gold blocks on the floor (y=1) and, at s... |
| PD-174 | Floor-clear title line runs off screen (Medium) | Fixed (2026-10-08). The bar title is the headline plus lives; the loot outcome is dropped (it is in the com... |
| PD-175 | Vault ejects a blank enchanted book (Medium) | Fixed (2026-10-08). Cause: `spur_barred_vault` and `spur_ominous_bargain` named `minecraft:enchanted_book` ... |
| PD-177 | pdmark marker commands error in latest.log (Low, harness) | Not a bug (2026-10-08). `pdserver.mjs remoteCommand` sends an unknown `pdmark-*` command after each command... |
| PD-178 | Witch poison still lasts about 30 seconds (Medium) | Fixed 2026-10-08 (code, awaiting live check). New `PoisonCap` polls every 5 ticks and replaces any poison l... |
| PD-179 | GO HOME board shows "Haul 0 scrap" after the finish auto-bank (Medium) | Fixed 2026-10-08 (code, awaiting live check). Instant payout kept. The finish now records what it banked pe... |
| PD-180 | "Home 4 chests" end line is unreadable (Low) | Fixed 2026-10-08 (code, awaiting live check). The HOME title subtitle is now "Banked 7 scrap. 4 chests in y... |
| PD-192 | Leaving mid-floor was a free, full bank for riders and for a party's owner (High) | Fixed 2026-10-09 (code, awaiting live check). The owner's leaving rule (2026-10-09): **leaving a floor in p... |
| PD-193 | A failed dungeon boots the party out and the lobby has to be reopened (Medium) | Fixed 2026-10-09 (code, awaiting live check). When the run has a staging room (`Instances.failReturnsHome`)... |
| PD-194 | Kennels wolf trial spawners soft-lock floors in stone rooms (High) | Fixed 2026-10-10 (code, awaiting live check). Read from the 26.2 bytecode: `TrialSpawner.spawnMob` checks t... |
| PD-195 | Fire spread destroys Kennels rooms (High) | Fixed 2026-10-10 (code, awaiting live check). The ignition source was never identified from the log (a burn... |
| PD-196 | Vault keys should last the dungeon's life, not one floor (Medium, design) | Fixed 2026-10-10 (code, awaiting live check), owner ruling: vault keys last the dungeon's life and settle f... |
| PD-197 | Copper chests should be able to pay armour or items (Low, content) | Fixed 2026-10-10 (code, awaiting live check), owner ruling: a floor can promise a rolled piece of gear. A n... |
| PD-198 | Power and trim disconnect cleanup raced the server thread (Low) | Fixed 2026-10-10 (code, awaiting live check). Both handlers now capture the UUID and hand the removal to `s... |
| PD-199 | A Silenced player's consumable count carried into their next run (Low) | Fixed 2026-10-10 (code, awaiting live check). `forget` now removes the player's count too, so the count liv... |
| PD-200 | A kill during the config rewrite could leave pocketdungeons.json truncated (Low) | Fixed 2026-10-10 (code, awaiting live check). One `writeAtomically` helper (sibling `.tmp`, then `ATOMIC_MO... |
| PD-201 | A finished floor's per-cell state outlived its cells (Medium) | Fixed 2026-10-10 (code, awaiting live check). One `InstanceTeardown.forgetCellState(BlockPos)` now clears a... |
| PD-202 | The shipped default config had drifted and a dead knob remained (Low) | Fixed 2026-10-10. The default file is regenerated from what the code writes, and `DefaultConfigFileTest` fa... |
| PD-203 | A failed door stamp left the party with no room and a stale preview (Medium) | Fixed 2026-10-10 (code, awaiting live check). A failed stamp on the first door puts the Home room back (`re... |
| PD-204 | Kennels spawner wolves are passive, not hostile (High) | Fixed 2026-10-10 (code, awaiting live check). Root cause: `Door` and `IntervalState.dungeonId` store the na... |
| PD-205 | rising_lava enters the player into lava; its warning lamp sits apart from the lever (High) | Fixed 2026-10-10 (code, awaiting live check). Owner clarification: the intended design is a straight corrid... |
| PD-206 | Shell token claim flow is not intuitive (Medium, UX) | Fixed 2026-10-10 (code, awaiting live check). Took the simplest option: right-clicking a shell token now un... |
| PD-207 | Door board shows two different "loot" wordings that read as the same thing (Low, UX) | Fixed in code 2026-10-13, live verification owed. The owner merged the two into one system (docs/DECISIONS.... |
| PD-209 | Gilded Seam veins gave no sign they were the objective or how to claim them (High, UX) | Fixed in code 2026-10-15, live check owed. Veins were click-to-claim fixtures: a silent verb on an unmarked block, and the tool protection hid even that. Now any tool or fist breaks the gilded blackstone and the break is the claim, unclaimed veins sparkle, and the pad says "Break N more gilded veins." The same pass lit the sealed vault core and unlit beacon bulbs the same way (`ClaimObjective`, `HeistObjective`, `HauntObjective`, `FloorObjectives`). |
| PD-210 | The Keeper never damages the party on early floors (High) | Fixed in code 2026-10-15, live check owed. `HauntRules.decideHunt` reused the stalker's stage ladder, so a keeper on a sighting or near-miss floor could only ever advance or fake a hit; on Lighthouse Below's early landings it was toothless. It now strikes at reach on every floor once the tell has run, still can never kill, and a keeper that wakes in reach arms its tell when the freeze starts.
