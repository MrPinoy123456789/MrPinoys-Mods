# Pocket Dungeons — discoveries log

Bugs, dead code, and behavior gaps found while reviewing and testing the U1–U7
update, outside of what `UPDATE_PLAN.md`'s own "Shipped:" sections already
recorded. Newest first. Each entry: what's wrong, where, and whether it's
fixed yet.

---

## Open

### Reward room and selector room could not be inspected in-world this session
**Where:** the new `reward_hall`/`selector_room` templates and everything that
stamps them (`Instances.stampRewardRoom`, `Instances.enterSelectorRoom`).
**What:** headless console verification confirmed both `.nbt` files generate
cleanly and load without error via `/place template`, and the full build/test
suite is green, but nothing that requires a player to actually stand somewhere
or click something (a real completion, the reward-room teleport, the chest
count against a real clock, a door click, `/dungeon choose`, a real timeout
while offline, free re-entry) could be driven from a console with no player
attached. Attempts to `/data get block` inside the placed templates at a
scratch position 1,000,000 blocks from spawn consistently returned "That
position is not loaded" even after a successful `/forceload add` and a
successful `/place template` at the same coordinates — a console-harness
limitation worth investigating before relying on that technique again, not
a mod bug (the templates placed correctly; only reading them back rejected).
**Status:** all of U8's interactive behaviour is now itemized in
`CLIENT_TEST_CHECKLIST.md` §36 and §44–49, unverified. T17 (deleting the U3
rollback path) went ahead anyway on explicit instruction, since it does not
touch any of this unverified surface — it only removes the mob-spawning
fallback that trials already superseded.
**Found:** U8 implementation session (2026-08-22).

---

### `dropMember` detached a member only halfway
**Where:** `Instances.dropMember`, versus `Instances.eject`.
**What:** `eject` clears `members`, `byMember`, `onPad`, the boss bar and Trial
Omen. `dropMember` -- the path taken on disconnect, on going offline, and on
leaving the dimension by any route other than a normal exit -- cleared only the
first two. Survivable while an instance died with its last member; U8 Stage 1
made instances outlive everyone, so each leftover now persists for the rest of
the run:

- **Trial Omen escaped to the overworld.** `clearTrialOmen` had exactly one call
  site (`eject`). An admin teleport out of an ominous run, or a disconnect,
  carried the effect out. U6 Stage 5 calls this the one effect the mod must never
  export, and `CLIENT_TEST_CHECKLIST.md` §31 only ever tested the three *normal*
  exits.
- **The boss bar broadcast to disconnected players.** `ServerBossEvent` holds a
  `Set<ServerPlayer>` and prunes nothing itself (verified by `javap`); the timer
  now ticks with nobody inside, so every `refresh()` pushed packets toward a dead
  connection for the rest of the run. Pre-U8 the timer stopped when members hit
  zero, so this window was ~nothing.
- **`onPad` kept a stale UUID.** Contact is read as an edge, so a member who left
  standing on the pad and walked back in (free re-entry, U8 Stage 1) would find
  the pad inert until they stepped off and on again.

**Fixed:** `dropMember` now takes the `ServerPlayer` (nullable -- the offline
case has none) and performs the same detachment as `eject` minus the teleport.
`processJoinRecoveries` also clears Trial Omen, for the crash/hard-restart case
that fires no disconnect event at all (2026-08-23).

### Instances leaked forever with `timerEnabled: false`
**Where:** `Instances.onTick`'s lifetime block, and the deleted `closeIfEmpty`.
**What:** the timeout branch is gated on `record.timer != null`, and the timer was
only built when the `timerEnabled` config was on. With it off, an uncompleted
keystone run had **no end condition at all** once U8 deleted `closeIfEmpty` --
the slot stayed in `usedSlots` and its chunks stayed force-loaded for the process
lifetime, which is the exact failure mode PLAN.md's M4 amendment warns about.
**Fixed:** `timerEnabled` is gone as a config. Every keystone run is timed, always.
An operator who wants a clockless dungeon asks for one explicitly with
`/dungeon admin untimed [level] [ominous]`; that instance never expires by design,
so its creation and its teardown are both logged at `INFO` and
`/dungeon admin list` marks it `UNTIMED, never expires`. Visibility is the
safeguard, in place of a config nobody would remember setting (2026-08-23).

## Fixed

### `TrialContent`'s `/ominous_plain_key` trial-spawner branch and data files
**Where:** `TrialContent.ominousConfigId`, and
`data/pocketdungeons/trial_spawner/tier_*/ominous_plain_key.json`.
**What:** U8 deleted the depth ramp that used to make the far third of a
*plain* run ominous on its own (`TrialContent.ominousAt`). A cell's ominous
state is now always identical to the whole run's, so the branch that used to
fire for "this cell is ominous but the run is not" (`ominous_plain_key`) could
no longer be reached by any run. This was `UPDATE_PLAN.md`'s U8 open decision
#4, flagged there as "may become redundant."
**Fixed:** decision made (delete) — the three JSON files are gone,
`ominousConfigId` is gone, and `TrialContent.applyEncounter`/`applyLoot` drop
the now-redundant second `ominousRun` boolean they used to carry alongside
`ominous` (the two were always equal since T16; collapsing to one removes the
duplication rather than just the dead branch behind it). Every trial spawner's
`ominous_config` field now reads `.../ominous` directly (2026-08-22).

### A run completed after the clock did not cost anything
**Where:** `Instances.completeRun`.
**What:** `UPDATE_PLAN.md`'s own U8 Stage 2 chest table says a late-but-
finished completion should delevel the keystone ("over the clock: 0 chests --
*and the key delevels*"), but `IMPLEMENTATION_PLAN_U8.md`'s T4a/T10 never
specified the arithmetic -- only a full timeout (never completed) depleted
anything. Implemented as written at the time, and flagged as an open gap
rather than guessed at.
**Fixed:** decision made -- a late completion now depletes the run's keystone
by `lateCompletionDepletion` (default 2, config-exposed, floored at 1 and
doubled by Fragile exactly like `timedOutDepletion`), via a new
`Keystones.Outcome.LATE`. The door offer is still granted, computed from the
*post-depletion* level, which is what makes the mechanic self-mitigating: even
the plain `+1` door nets only a net `-1` against the `-2` penalty. Settled
through the existing `returnKeystone`/`keystoneReturned` guard so a later
`exit()` from the reward room does not re-write the level back to its
pre-depletion value (2026-08-22).

### The three choice vaults, and the whole vault-based upgrade mechanic
**Where:** `TrialContent.applyChoiceVaults`, `Instances.tryClaimOffer`,
`InstanceRecord.choiceVaults`/`keystoneGranted`/`completedInTime`, and the
`RitualListener` branch that routed to it.
**What:** U7 shipped three vaults in the exit room that vanilla would never
unlock (`VaultBlockEntity$Server.tryInsertKey` rolls the loot table first and
bails on an empty result — see the superseded entries this replaces, below).
A same-session fix intercepted the click mod-side instead, which worked but
was never verified live before U8 superseded the whole mechanic outright: the
upgrade choice is now three real doors in a private selector room (U8 Stage
3), reached by the compass rather than a token item, and validated against
`DungeonLog`'s persisted pending offer rather than an in-world claim.
**Fixed:** the vaults, the click interceptor, and every field that only
existed to support them are deleted (2026-08-22, U8 implementation).

<details>
<summary>Original vault-mechanic findings (superseded, kept for history)</summary>

**The three choice vaults can never be opened — U7's core mechanic is broken.**
`TrialContent.applyChoiceVaults` configured them with `pocketdungeons:empty`
(`"pools": []`); vanilla's `VaultBlockEntity$Server.tryInsertKey` calls
`resolveItemsToEject` (which rolls the vault's loot table) and returns early if
the result is empty — before consuming the key, before `unlock()`, and before
`addToRewardedPlayers()`. Confirmed in bytecode: the empty-list check sits at
offset 75-85, the unlock path starts at 86. Found in a live client test.

**Choice vault display items were probably being wiped**, for the same reason:
`VaultBlockEntity$Server.tick`'s `cycleDisplayItemFromLootTable` re-rolls the
same empty table every tick, overwriting the `setDisplayItem` call
`placeVault` made at stamp time.

**`trialsEnabled: false` no longer cleared the choice vaults** — both call
sites (`Instances.java:306` and `:1333`) stamped them unconditionally, with no
`TrialContent.enabled()` guard, breaking U6's "zero vaults in the whole
instance" kill-switch promise. Fixed in the same-session interim fix
(`TrialContent.enabled()` guard added at both sites) before U8 deleted the
call sites entirely.

</details>

### The boss bar's room counter never moves
**Where:** `RunTimer.java` (`notePresence`), never called from `Instances.java`.
**What:** the bar title reads `Keystone [N] — m:ss — x/y rooms`. The clock half
works; `x` (rooms visited) was permanently stuck at 0 for the whole run, because
nothing ever called `notePresence` to advance it.
**Fixed:** `PlanGeometry.cellAt(BlockPos)` (new) converts a player's position
back to a plan cell; `Instances.onTick`'s per-member loop records it into a new
`InstanceRecord.visited` set and calls `record.timer.notePresence(record.visited.size())`
on first entry to each cell (2026-08-22, T2). **Unverified in a client** —
headless testing cannot walk a player through rooms; see
`CLIENT_TEST_CHECKLIST.md` §36.

### Orphaned `bonus.json` loot table
**Where:** `data/pocketdungeons/loot_table/chests/bonus.json`.
**What:** referenced by nothing — no loot table, no Java. Left over from U3's
`chestRolls()` party-bonus design, which was unwired for being too generous,
then the whole mechanic was deleted outright in U6 for double-counting
vanilla's own party scaling. The file just never got removed.
**Fixed:** deleted (2026-08-21).

### Dead gate/key planner scaffolding
**Where:** `DungeonPlan.Gate`, `RoomSelector.buildGate`, the BFS-with-keys
branch in `RoomSelector.reachableCells`.
**What:** a "locked door, key elsewhere" concept sketched into the planner
before U1 shipped, never wired to anything in-world (no lock block ever
authored). U6 then built the real version of this idea — trial keys and
vaults — making the placeholder redundant rather than forward-looking.
**Fixed:** removed; `reachableCells` collapsed to a plain reachability walk.
Verified against the 5,000-seed `layoutGraphTest` sweep, still 0 failures
(2026-08-21).

### `/extract` command alias
**Where:** `DungeonCommands.java`, top-level `Commands.literal("extract")`.
**What:** duplicate of `/dungeon exit`, kept only for compatibility with an
early spec's vocabulary that nothing else still depends on. Squatted a
generic English word at the top level in a suite with a dozen other mods.
**Fixed:** removed (2026-08-21).

---

## Ruled out (looked like problems, weren't)

### `/dungeon party` and the `partySize` thread
**Looked like:** dead plumbing, since `DifficultyProfile.mobCount`'s
`partySize` term was only consumed by the U3 rollback path, and U6 moved live
mob-count scaling onto vanilla's own `total_mobs_added_per_player`.
**Actually:** `/dungeon party` is load-bearing — `resolveParty` is the real
mechanism that teleports pre-registered companions into the dungeon together
at open time, independent of difficulty math. Left alone.
**Update, T17 (2026-08-22):** the other half of this entry's premise is now
gone rather than just superseded — `DifficultyProfile.mobCount`,
`RoomContent.spawnMobs` and the `partySize` thread into `DifficultyProfile`
were deleted outright once the trial-spawner loop was confirmed live. `/dungeon
party` itself is untouched, exactly as `UPDATE_PLAN.md`'s T17 note promised:
it stays because it is real functionality independent of difficulty scaling,
not because anything still reads `partySize` for a mob count.
