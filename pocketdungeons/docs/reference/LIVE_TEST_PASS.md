# Pocket Dungeons — Live Test Pass

> All milestones M0–M9 are code-complete (`./gradlew build` green, unit tests
> passing). **Nothing below has been verified with a live client.** This file
> consolidates every deferred live check into one pass.
>
> Mod id: `pocketdungeons` · Entry command: `/dungeon`
> Dimension: `pocketdungeons:void`
>
> **Setup:** Load the mod on a server or integrated client. Ensure the
> `pocketdungeons:void` dimension datapack is enabled. Have a second player
> available for party and visiting tests.
>
> **Fixed boot crash (found and fixed during M11's build verification, and
> independently reproduced on a real 169-mod production server):**
> `LootTables.validateAtStartup` used to call `level.registryAccess()
> .lookupOrThrow(Registries.LOOT_TABLE)`, which throws
> `IllegalStateException: Missing registry` unconditionally: loot tables are a
> reloadable, datapack-driven registry held on
> `MinecraftServer.reloadableRegistries()`, not on a `ServerLevel`'s frozen
> dynamic registry access. This crashed every server on boot, before this
> file's checks could ever run, and also broke `TrialContent.resolveLootTable`
> at runtime for any themed loot suffix (`drowned_vault`'s `_drowned` tables).
> Both now go through the corrected `LootTables.exists(MinecraftServer,
> ResourceKey)`. Confirmed against this session's own dev harness: the server
> now boots clean (`All 9 core loot tables verified present`), and
> `/dungeon admin build <seed> 60` and `/dungeon admin list` both work headlessly.
> This was pre-existing and unrelated to M10/M11's own changes, not a
> regression either introduced.

---

## 1. Entry and exit

### 1.1 Basic entry

1. Stand in a known overworld location and note your coordinates.
2. Run `/dungeon`.
3. **Expected:** You are teleported into your room — either your saved room
   (if you have one) or `entrance_hall` on a first visit. One connecting door
   is sealed. Three selector doors face you. No timer is running yet.
4. Choose any door. **Expected:** the seal opens, the dungeon generates
   behind the lobby, and the timer starts now — not before.

### 1.2 Basic exit

1. Walk to the exit room and stand on the lodestone pad.
2. **Expected:** You are teleported to your room at the terminal cell, reward
   chests spawn behind a sealed door, the door opens, and you walk into your
   decorated room (if any).
3. Leave the dungeon via `/dungeon exit` or the room's lodestone.
4. **Expected:** You return to your overworld starting position.

### 1.3 Dimension name

1. While inside the dungeon, check the current dimension name.
2. **Expected:** `pocketdungeons:void`.

### 1.4 Death ejection

1. Enter a dungeon and let a mob deal a killing blow.
2. **Expected:** No death screen, no inventory drops. You are ejected to your
   start position at full health.

### 1.5 Disconnect while inside

1. Two players enter the same dungeon.
2. One player disconnects while still inside.
3. **Expected:** The remaining player(s) are not kicked out and can continue
   or leave normally.

---

## 2. The room (M2)

### 2.1 Room persistence — the closed loop

1. Decorate your room (place blocks, item frames, armour stands).
2. Complete a dungeon run. **Expected:** the room is captured, cleared, and
   re-stamped at the terminal cell. You walk through the opened door into your
   decorated room — decorations intact.
3. Walk back to the entrance cell. **Expected:** it is empty — the room moved,
   the old cell was cleared.
4. Run another dungeon and complete it. **Expected:** the room moves again,
   decorations still intact.

### 2.2 Room backup and restore

1. Decorate your room, then complete a run so the blob is saved.
2. As an op, `/dungeon admin baserestore <player>`.
3. **Expected:** the room reverts to the backup state (the decoration from
   before the last save). A fresh `.bak` is created by the restore itself.
4. Corrupt or delete the live `.dat` file manually, then run baserestore.
   **Expected:** recovery works from `.bak`.

### 2.3 Permission mask

1. As the room owner, break a block and open a lootable container.
   **Expected:** both work.
2. As a visitor (not on whitelist), try to break a block.
   **Expected:** denied.
3. As a visitor, try to open a lootable container (chest, barrel).
   **Expected:** denied.
4. As a visitor, use a station (brewing stand, furnace, etc.) and an ender
   chest. **Expected:** both work.
5. `/dungeon room whitelist add <player>` then have that player try to break.
   **Expected:** now works. Remove with `whitelist remove` and confirm denied
   again.

### 2.4 Bedrock envelope

1. Inside a dungeon, look down through the floor and up at the ceiling.
   **Expected:** bedrock below and above every cell.
2. Look at outer walls on faces with no adjacent cell.
   **Expected:** bedrock ring. On faces with an adjacent cell, no bedrock —
   the doorway is open.

### 2.5 Lingering quarry

1. Complete a run but do not leave the dungeon area. Walk back toward the
   entrance.
2. **Expected:** the finished dungeon blocks are still standing (lingering).
   You can mine them.
3. Run `/dungeon` again. **Expected:** the lingering instance is purged first,
   then a fresh run begins.

### 2.6 Purge on leadership change

1. Player A (owner) and Player B enter together as a party.
2. Player A disconnects or leaves the dimension while Player B is still inside.
3. **Expected:** the entire instance is purged. Player B is ejected.

---

## 3. Visiting (M3)

### 3.1 Calling card

1. Run `/dungeon room card`. **Expected:** you receive a compass item (calling
   card) with your name as custom name and a glint.
2. Find another player's lodestone in the overworld. Right-click it while
   holding the calling card.
3. **Expected:** the lodestone charge sound plays, the card is not consumed,
   and you are teleported into the owner's room (or a read-only copy if the
   owner is away).

### 3.2 Visitor permissions

1. As a visitor in someone else's room, try to break a block.
   **Expected:** denied (you are not on the whitelist).
2. Try to open a lootable container. **Expected:** denied.
3. Use a station or ender chest. **Expected:** works.

### 3.3 Visit instance lifecycle

1. Owner is away. Visitor uses calling card on lodestone.
   **Expected:** a read-only copy of the room is stamped, door sealed, selector
   doors placed. Visitor can choose a door and run a dungeon from the owner's
   room layout.
2. A second visitor uses a calling card on the same owner's lodestone.
   **Expected:** admitted into the same visit instance (refcounted).
3. Both visitors leave. **Expected:** the visit instance is purged.

### 3.4 Owner returns during a visit

1. Visitors are in a visit instance. The owner runs `/dungeon`.
   **Expected:** the owner enters their own live instance, not the visit copy.
   The visit instance continues independently.

---

## 4. Combat and trials

### 4.1 Trial spawner activation

1. Enter an encounter room. **Expected:** one `minecraft:trial_spawner` on the
   floor, glowing, with a spinning mob inside — not mobs already standing
   around.
2. Stand near it. **Expected:** it detects you, activates, and spawns in waves.
3. Kill everything. **Expected:** it ejects an item (trial key or consumables)
   and goes to cooldown.
4. **Watch for:** any creeper. There must never be one.

### 4.2 Vault loot loop

1. Find a loot room. **Expected:** a `minecraft:vault`, not a chest.
2. Approach without a key. **Expected:** locked and inert.
3. Use a trial key. **Expected:** vault opens, ejects loot, cannot be reopened
   by you.

### 4.3 Ominous run

1. Hold a keystone in your main hand and a `minecraft:ominous_bottle` in your
   off hand. Right-click a lodestone.
2. **Expected:** bottle consumed, Trial Omen granted, every spawner and vault
   is ominous from the entrance onward.
3. **Expected:** completion payout is 1.5x, and the completion line says the
   run was ominous.
4. **Expected:** an ominous vault drops a Boss Stone.

### 4.4 Trial Omen does not escape

1. Finish an ominous run on the pad. **Expected:** no Trial Omen in overworld.
2. Start another, leave with `/dungeon exit`. **Expected:** no Trial Omen.
3. Start another, get killed. **Expected:** ejected, no Trial Omen.

### 4.5 Death during a spawner fight

1. Die to a trial spawner's mobs.
2. **Expected:** ejected with inventory, no death screen, no payout, mobs do
   not follow you out.

---

## 5. Affixes (M4)

### 5.1 Seeded affixes

1. Run `/dungeon` at keystone level 5, 11, and 17.
2. **Expected:** at each threshold, one more seeded affix appears in the
   keystone name. The same level on the same player always rolls the same
   affixes (seeded from `hash(owner, level)`).

### 5.2 Naming

1. Check the keystone item name at various levels.
2. **Expected:** format is `<intensifier> <affix> Keystone [<level>]` with
   additional affixes in a bracketed subtitle. Pure function of level and
   affix set — no randomness in the name.

### 5.3 Swarming

1. Run a dungeon with the Swarming affix.
2. **Expected:** more mobs per spawner wave than a normal run.

### 5.4 Overclocked

1. Run a dungeon with the Overclocked affix.
2. **Expected:** spawner cooldowns are shorter — mobs spawn faster.

### 5.5 Molten

1. Run a dungeon with the Molten affix.
2. **Expected:** lava/magma blocks stamped in cell interiors, clear of doors
   and spawn anchors. The dungeon is still passable.

### 5.6 Silenced

1. Run a dungeon with the Silenced affix.
2. **Expected:** cannot consume food/potions (`CONSUMABLE` denied). Spawners
   have tighter `required_player_range` — they activate only when you are
   closer.

### 5.7 Depletion

1. Let a keystone time out (or complete late).
2. **Expected:** depletion takes the `max` across the affix set, capped at 2x.
   The keystone level drops by the depletion amount, not more.

---

## 6. Supply and loot (M6)

### 6.1 Guaranteed floors

1. Open any loot chest (vault or supply) at any tier.
2. **Expected:** every chest contains food, torch, bone, tier-appropriate
   blocks, dirt, sapling, and seeds — the seven floor categories. No floor
   miss across many opens.

### 6.2 Tiered building blocks

1. Open chests at tier 1, 2, and 3.
2. **Expected:** tier 1 yields stone/wood/iron/moss; tier 2 yields
   deepslate/copper/prismarine/crying obsidian; tier 3 yields end
   stone/ancient-city/rare decoratives. No cross-tier bleed.

### 6.3 Grove room

1. Run several dungeons until you find a grove room (about 12% chance).
2. **Expected:** oak logs where stone bricks were, grass instead of polished
   andesite, persistent oak leaves, sea lanterns. No water. A sapling is
   guaranteed, not weighted.

### 6.4 Nether/End products

1. Open a tier 2 chest. **Expected:** 40% chance of a workshop item (brewing
   stand, cauldron, anvil, etc.).
2. Open a tier 3 chest. **Expected:** one ungated product per chest (ender
   chest, enchanting table, obsidian, etc.) — guaranteed, not weighted.

### 6.5 Self-sufficiency

1. On a server with the overworld removed (or stripped), run dungeons
   exclusively.
2. **Expected:** the game is fully playable — food, wood, stone, tools, and
   progression items are all obtainable from dungeon loot alone.

---

## 7. Themes and recipes (M7)

### 7.1 Themed doors

1. Complete a run and reach the door-offer stage.
2. **Expected:** the three doors show different theme names (e.g. deepslate,
   prismarine, blackstone). The themes are seeded from `(owner, level)` —
   same player, same level, same offers.

### 7.2 Theme application

1. Choose a themed door and run the dungeon.
2. **Expected:** the dungeon rooms visibly reflect the chosen theme's
   processor list (different block palette than an unthemed run).

### 7.3 Recipe match

1. Complete three runs in sequence: deepslate, prismarine, blackstone.
2. **Expected:** on the third run's door offers, door 3 shows
   `drowned_vault` instead of a random theme — the recipe
   `[deepslate, prismarine, blackstone] → drowned_vault` matched.
3. Run the drowned vault dungeon. **Expected:** loot chests use
   `chests/tier_N_drowned` tables (or fall back to unsuffixed if not loaded).

### 7.4 Discovery floor

1. Run `/dungeon log`.
2. **Expected:** lists completed themes with counts. Does not show the
   three-run window, recipe matches, or hints.

### 7.5 Room is never themed

1. After completing a themed run, walk into your room.
2. **Expected:** your room's blocks are unchanged — the theme's processors
   were not applied to the room cell.

---

## 8. Feral wolves (M5)

### 8.1 Spawning

1. Run a dungeon with the Feral affix (appears at level 5+).
2. **Expected:** neutral wolves in corridor and loot cells, not in encounter
   cells (those have trial spawners). Wolves are pinned to their cell — they
   do not cross into the next room.

### 8.2 Coats by tier

1. Run Feral dungeons at tier 1, 2, and 3.
2. **Expected:** tier 1 wolves have pale/woods/ashen coats; tier 2 have
   spotted/rusty/chestnut; tier 3 have snowy/black/striped. Exclusive bands,
   no overlap.

### 8.3 Taming

1. Feed a wolf a bone (bones are a guaranteed floor item).
2. **Expected:** 1-in-3 chance per bone. An angered wolf refuses the bone.
   A successfully tamed wolf sits and stays sat.

### 8.4 Spirit Stone permanence (requires spiritwolves mod)

1. Tame a wolf in a Feral run. Bind it to a Spirit Stone.
2. **Expected:** the coat variant survives the binding. (This requires both
   mods loaded — no combined dev environment exists in this repo, so this
   is the one test that needs a real two-mod server.)

---

## 9. Timer and door offers

### 9.1 The clock

1. Enter a dungeon with a keystone. **Expected:** a boss bar appears, titled
   `Keystone [N] - m:ss - x/y rooms`, counting down.
2. **Expected:** green → yellow at half → red at a fifth.
3. Walk into new rooms. **Expected:** the `x/y` counter advances.
4. Let it run out. **Expected:** `OVER TIME` displayed; the instance closes
   on its own after a grace period, and the keystone is depleted.

### 9.2 Reward chests

1. Complete a run using under 60% of the clock. **Expected:** three reward
   chests.
2. Complete using 60–80%. **Expected:** two chests.
3. Complete using 80–100%. **Expected:** one chest.
4. Complete after the clock runs out. **Expected:** zero chests, keystone
   depleted by `lateCompletionDepletion` (default 2, floored at 1).

### 9.3 Door offers

1. Complete a run. **Expected:** the room is at the terminal, and the next
   run's door offers are visible (three doors in the room).
2. Choose a door. **Expected:** keystone level changes by +1, +2, or +3
   depending on which door. The +2 door is ominous; the +3 door is fragile.
3. Walk out without choosing. **Expected:** keystone unchanged, offer still
   pending. Re-entering `/dungeon` takes you back to the room with the offers
   still visible.

### 9.4 Latecomer timer

1. Player A enters a dungeon. Player B is invited and joins mid-run.
2. **Expected:** Player B sees the boss bar with the true remaining time,
   not a fresh clock.

---

## 10. Party play

### 10.1 Invite and join

1. Player A runs `/dungeon`. Player A runs `/dungeon invite <playerB>`.
2. Player B runs `/dungeon join <playerA>` before the invitation expires.
3. **Expected:** Player B is placed in the same instance. Each player has
   their own distinct starting position.
4. Both leave. **Expected:** each is restored to their own original starting
   position.

### 10.2 Party payout

1. Two players enter together, both complete on the pad.
2. **Expected:** both are paid, each exactly once, each with their own run
   number and streak.

### 10.3 Party member leaves early

1. Two players enter. One leaves via `/dungeon exit`.
2. **Expected:** the leaver is not paid. The instance survives. The remaining
   player completes and is paid.

---

## 11. Admin commands

### 11.1 Manifest reload

1. As an op, run `/dungeon admin manifest reload`.
2. **Expected:** chat shows the room count, no rejections.

### 11.2 Manifest list

1. As an op, run `/dungeon admin manifest list`.
2. **Expected:** readable lines, one per room, showing footprint, door masks,
   and roles. Confirm legibility in chat (not cut off or wrapping badly).

### 11.3 Manifest reload during a live dungeon

1. Run `/dungeon` and stay inside. From another op session, run
   `/dungeon admin manifest reload`.
2. **Expected:** nothing happens to the active instance — no teleport, no
   room changes, exit still works.

### 11.4 Baserestore

1. As an op, `/dungeon admin baserestore <player>`.
2. **Expected:** the player's room reverts to the backup. Works for offline
   players (resolved via `GameProfileArgument`).

### 11.5 Purge

1. As an op, `/dungeon admin purge` an admin-built instance (from
   `/dungeon admin build`).
2. **Expected:** the instance is purged — members ejected, slot returned,
   force-load tickets released. (This was a live bug fix: admin-built
   instances with null owner used to NPE on purge.)

---

## 12. Refactor regression (M9)

> M9 split `Instances.java` into six classes. Nothing a player can see should
> change. Run these after the full pass to confirm no behaviour drifted.

1. Enter a run, choose a door, walk the dungeon, reach the terminal pad, take
   the reward, leave.
2. Re-enter and confirm the room came back with its contents intact.
3. Visit another player's room via a calling card.
4. **Expected:** nothing feels different from before the refactor. If
   something does, a behaviour change was introduced during a move commit.

---

## 13. Visual geometry

### 13.1 Room geometry

1. Run `/dungeon` and walk all rooms end to end.
2. **Expected:** floors, walls, and ceilings are solid and continuous — no
   gaps, no floating blocks, no misaligned seams at doorways.
3. Look at every doorway. **Expected:** 2 blocks wide, 3 tall, centered. No
   partial or stray blocks.

### 13.2 No jigsaw blocks

1. Walk through all rooms, including ceiling and doorway thresholds.
2. **Expected:** no `minecraft:jigsaw` blocks visible anywhere.

### 13.3 Mob behavior

1. Let the skeleton in an encounter room engage you.
2. **Expected:** normal strafing, shooting, no frozen/stuck/silent behavior.
3. Let a zombie engage. **Expected:** normal chase/attack.

### 13.4 Repeatability

1. Run `/dungeon`, walk through, exit. Run again.
2. **Expected:** the second run's room layout looks consistent with the first
   (same templates, same door positions — placement is deterministic per
   seed).

---

## 14. Ladder reframe (M10)

> M10 raises the keystone cap to 100, scales mob strength with level, drops
> `FRAGILE`/`Affix.Kind.ELECTIVE`, migrates `OMINOUS` to seeded, and gates run
> completion on clearing a fraction of the run's trial spawners. The cap
> bump, the affix threshold and intensifier math, and the enum change are
> already covered by `affixMathTest` and `difficultyProfileTest`, which run
> headlessly. Everything below needs a live client.

### 14.1 Mob strength scaling

1. Use `/dungeon admin build <seed> 80` (or any high level) and let a skeleton
   or zombie in an encounter room engage you.
2. **Expected:** noticeably tougher than a level-1 run: more hits to kill, and
   harder-hitting attacks. A `/dungeon admin build <seed> 1` mob should feel
   close to vanilla.
3. Check the mob does not spawn already damaged: at full health the moment it
   is first visible.
4. Build a Feral run at a high level and let a wolf engage a hostile mob, or
   check its attributes directly. **Expected:** wolves are scaled the same as
   trial-spawner mobs; they spawn synchronously at stamp time, on a different
   code path, and are worth checking separately.
5. Fight through a full run without disconnecting, then reconnect mid-fight
   against an already-damaged mob (or otherwise force a chunk reload while a
   mob is below full health). **Expected:** the mob keeps its current damage;
   it does not heal back to its scaled max on the reload.

### 14.2 Spawner-clear completion gate

1. Enter a keystone run, skip past every encounter room without engaging a
   trial spawner, and step onto the terminal pad.
2. **Expected:** the run refuses to complete. A chat message states how many
   spawners are cleared out of the total, and the player is not teleported
   anywhere. The clock keeps running.
3. Go back and clear enough spawners to reach the configured threshold
   (`spawnerClearThreshold`, default 0.75), then step onto the pad again.
4. **Expected:** the run completes normally this time: reward chests, the
   door to the room, the usual completion message.

### 14.3 Level-100 keystone name

1. Mint or admin-build a level-100 key and read its item name.
2. **Expected:** an intensifier band appropriate to 91-100 (`Transcendent`) and
   up to five affix words in enum order, none of them `Cooked`'s old `Fragile`
   sibling (it no longer exists).

### 14.4 High-level affix stacking (playtest, not a pass/fail check)

A level-85+ run can carry all five non-`OMINOUS` seeded affixes at once
(Swarming, Overclocked, Molten, Silenced, Feral) on top of ~2x mob strength.
This combination has never been played. Run one and note whether it reads as
"hard" or "unfair": a Silenced (no consumables) Swarming (more mobs) Molten
(lava underfoot) run with Overclocked spawners leaves very little room to
recover from a mistake. If it plays as unfair rather than hard, the fix is
tuning (a cap on simultaneous seeded affixes, or softer per-affix numbers at
the high end), not a code defect; record what you find here either way.

---

## 15. Adventures (M11)

> M11 replaces the recipe system with `AdventureGraph`: doors draw their
> three themes from the current theme's transition set, and a boss-themed run
> ends with a fight instead of a walk to the pad. The graph's own arithmetic
> (`pick`, `resetTheme`, node validation) is covered headlessly by
> `AdventureGraphTest` and `KeystoneOfferTest`; everything below needs a live
> client, and none of it can be checked until the boot-time blocker noted at
> the top of this file is fixed.

### 15.1 Descent offers follow the graph

1. Complete a run themed `deepslate` and read the three door offers.
2. **Expected:** the three themes come from `deepslate.json`'s `next` list
   (`prismarine` weighted 3, `blackstone` weighted 2, so `prismarine` should
   turn up more often across repeated runs, not enforced on any one roll).
3. Complete a run themed `blackstone` (reachable from either entry theme).
4. **Expected:** doors can include `drowned_vault`, the boss theme, alongside
   `deepslate`/`prismarine`.

### 15.2 The graph stays hidden

1. Look at every door-offer dialog and chat line across a full run.
2. **Expected:** no "you are here," no node list, no depth counter, anywhere.
   A door shows only its own theme and affix, same as before this milestone.

### 15.3 The boss encounter

1. Take a door offering `drowned_vault` and walk the run to its terminal
   cell.
2. **Expected:** a mob named "The Drowned Warden" (a scaled
   `minecraft:ravager`) is standing in the terminal cell from the moment you
   arrive, not spawned when you step on the pad.
3. Step on the terminal pad while the boss is still alive.
4. **Expected:** the run refuses to complete, with a chat message naming the
   boss. The pad stays contactable; killing the boss and stepping on the pad
   again should complete the run normally.
5. Kill the boss first, then step on the pad.
6. **Expected:** the run completes immediately, same as any other run once
   its spawners are cleared.

### 15.4 Boss run's theme reset

1. Complete a `drowned_vault` (boss) run and check the next door offers.
2. **Expected:** the offers come from an entry theme's transition set
   (`deepslate` or `prismarine`), not from `drowned_vault`'s own (a boss node
   has none) and not stuck on the boss theme.

---

## 16. Two-tier doors and fuel (M12)

> M12 splits the three doors into a free, non-depleting, fuel-paying tier
> (door 1) and a fuel-costed, level-gated Greater tier (doors 2/3, drawn from
> the adventure graph same as before). Everything below needs a live client;
> there is no headless way to right-click a door or read a dialog's body text
> (`DISCOVERIES.md` trap 10).

### 16.1 Door 1 reads as free and pays out

1. Open a lobby and read door 1's dialog.
2. **Expected:** it says it is free and names the fuel amount it pays out
   (`fuelPerFreeRun`, default 1 echo shard), and says it never depletes the
   keystone.
3. Take door 1, let its clock run out without reaching the pad (or reach the
   pad late).
4. **Expected:** the keystone comes back at the same level it went in at, no
   `"Your keystone is depleted"` message, regardless of how the run ended.
5. Complete door 1 on time.
6. **Expected:** you receive `fuelPerFreeRun` echo shards (or whatever
   `fuelItem` is configured to) on completion, every time, not occasionally.

### 16.2 Doors 2/3 read as costed and gated

1. Open a lobby and read door 2 and door 3's dialogs.
2. **Expected:** both name a fuel cost (`fuelCostPerGreaterDoor`, default 3)
   and a minimum keystone level (`greaterDoorMinLevel`, default 15).

### 16.3 The level gate actually refuses

1. With a keystone below `greaterDoorMinLevel` (a fresh level-1 key, by
   default), take door 2 or door 3.
2. **Expected:** refused with a chat message naming the required level. No
   fuel is spent, no dungeon is generated, and the door is still there to
   try again (or to pick a different door).

### 16.4 The fuel gate actually refuses

1. With a keystone at or above `greaterDoorMinLevel` but fewer than
   `fuelCostPerGreaterDoor` echo shards in inventory, take door 2 or door 3.
2. **Expected:** refused with a chat message naming the cost. No echo shards
   are spent.
3. Farm door 1 until you have enough fuel, then retake the same door.
4. **Expected:** the door opens, exactly `fuelCostPerGreaterDoor` echo shards
   are removed from your inventory, and not before the dungeon actually
   finishes generating (check inventory count right after taking the door: a
   failed generation attempt should refund rather than eat the cost, since
   the spend happens after `generateBehindLobby` succeeds).

### 16.5 Door 1's own clock

1. Take door 1 and watch the boss bar's timer.
2. **Expected:** `door1TimerSeconds` (default 300s = 5:00) flat, not the
   usual base-plus-per-room formula doors 2/3 use.

---

## 17. Gear reroll station (M14)

> M14 depends on M13's tiered gear loot and its `pocketdungeons.tier`
> custom_data tag. Everything below needs a live client; there is no headless
> way to right-click a station or read a dialog (`DISCOVERIES.md` trap 10).
> The cost curve (`RerollMath.cost`) and the "never strictly worse" set-swap
> property (`RerollMath.isValidReroll`) are headless-verified by
> `rerollMathTest`; this section is only the parts that are not.

### 17.1 The station opens only for tagged gear

1. Right-click the configured `rerollBlock` (default `minecraft:smithing_table`)
   holding an item with no `pocketdungeons.tier` tag (a plain vanilla item, or
   a vanilla armor piece bought/crafted, not looted from a run).
2. **Expected:** vanilla's own smithing table screen opens, unchanged. The mod
   never intercepts.
3. Right-click the same block holding a piece of gear looted from a run (tier
   1-3, tagged by M13's loot functions).
4. **Expected:** the reroll picker dialog opens instead of the vanilla
   smithing screen, listing the item's current enchantments.

### 17.2 The level gate

1. With a keystone below `rerollUnlockLevel` (default 5), right-click the
   station holding tagged gear.
2. **Expected:** refused with a chat message naming the required level. No
   dialog opens, and the click does not fall through to vanilla's smithing
   screen either.

### 17.3 One enchantment at a time, cost scales with tier

1. Pick an enchantment to reroll on a tier-1 item.
2. **Expected:** the dialog names a lapis cost of `rerollLapisPerTier` (default
   4); a tier-2 item names double that, tier-3 triple.
3. With fewer lapis lazuli than the cost, pick an enchantment.
4. **Expected:** refused with a chat message naming the cost; nothing is
   spent, nothing on the item changes, and the picker reopens so another
   enchantment (or the same one, once more lapis is on hand) can still be
   tried.
5. With enough lapis, pick an enchantment.
6. **Expected:** exactly the cost in lapis lazuli is removed; the chosen
   enchantment is gone from the item; every other enchantment that was on the
   item before is still there, unchanged; and exactly one new enchantment
   (not the one just removed, not one already on the item) has appeared, at a
   level within its own normal range. Inspect the returned stack's
   `DataComponents.ENCHANTMENTS` directly, not just that the dialog claims
   success (`DISCOVERIES.md`'s carried lesson: check generated data, not that
   the action ran).
7. Repeat on an item with only one enchantment slotted for a while, or on a
   tier whose registry pool is nearly exhausted, to see the "nothing else
   fits this item" refusal path (`RerollStation.handleReroll`'s empty-pool
   case) at least once.

---

## 18. Gear loot pool (M13)

> **Mostly already verified headlessly, unlike every other section in this
> file.** M13 is content, and `/loot insert` plus `/data get block` can draw a
> real stack and read its actual components with no client attached, which is
> exactly the inspection `DISCOVERIES.md`'s carried lesson asks for ("check the
> generated data, not just that generation ran"). What was confirmed against a
> running server, drawing from the real tables:
>
> - `gear/helmet_1` gave `minecraft:iron_helmet` carrying
>   `enchantments {unbreaking 1, protection 2}` and
>   `custom_data {pocketdungeons: {tier: 1b}}`
> - `gear/chestplate_3` gave `minecraft:diamond_chestplate`,
>   `{protection 3}`, `tier 3b`
> - `gear/weapon_2` gave `minecraft:iron_sword`, `{knockback 1}`, `tier 2b`
> - `gear/boots_3` gave `minecraft:diamond_boots`, `{fire_protection 3}`,
>   `tier 3b`
> - `chests/tier_3` rolled a `minecraft:diamond_axe` with
>   `{efficiency 4, unbreaking 3}` and `tier 3b` alongside its ordinary loot,
>   confirming runs drop gear and not only the gamble tables do
>
> Note the tier marker serialises as an NBT **byte** (`tier: 3b`), not an int,
> because the value fits in one. This is harmless to an int read:
> `CompoundTag.getIntOr` tests `instanceof NumericTag` and calls
> `intValue()`, and `ByteTag` is a `NumericTag` (verified in bytecode). Do not
> "fix" it by widening the JSON, and do not read it with anything that demands
> an `IntTag` specifically.

### 18.1 Gear actually reaches a player's hands

1. Run a real tier-1 dungeon and open the reward chests and vaults.
2. **Expected:** iron-grade gear turns up sometimes but not in every chest
   (the gear pool sits behind a `random_chance` of 0.35 at tier 1, 0.45 at
   tier 2, 0.55 at tier 3, plus 0.15 on the ominous variants). Leather and
   chainmail appear as the lesser rolls.
3. Run a tier-3 dungeon. **Expected:** diamond-grade gear, occasionally
   netherite, with visibly better enchantments than the tier-1 run's.

### 18.2 Enchantments are slot-correct

1. Collect a spread of gear drops across several runs, including at least one
   bow or crossbow.
2. **Expected:** no nonsense pairings. A bow never carries Protection, a
   helmet never carries Power. This is structural rather than authored (see
   the plan's landed correction: `enchant_with_levels` only picks what the
   item supports), so a violation here means the mechanism regressed, not
   that one table has a typo.

### 18.3 Ominous runs pay better

1. Run the same tier ominous and non-ominous.
2. **Expected:** gear shows up noticeably more often on the ominous run.

### 18.4 Themed runs still drop gear

1. Complete a `drowned_vault` run, which resolves to the `_drowned` loot
   tables rather than the base ones.
2. **Expected:** gear still drops. The gear pool was added to all nine chest
   tables (base, `_ominous` and `_drowned` at each tier), not just the six
   registered in `LootTables.ALL`. The `_drowned` variants are optional by
   design and unregistered, so it would have been easy to leave the boss
   theme as the one path in the game that drops no gear at all.

---

## 19. Armor trims (M15)

> The recipe-level mechanics (the duplication override loading and replacing
> its vanilla original, the loot pools drawing real templates and materials
> with no stray `custom_data`) are confirmed headlessly, the same way M13's
> loot content was: `/loot insert` into a real chest plus `/data get block`
> on the result. What is not headlessly checkable is anything that needs a
> GUI or a worn item's live effect (`DISCOVERIES.md` trap 10). That is this
> section.

### 19.1 Applying a trim still works, and consumes the template as vanilla always did

1. Find a dungeon-dropped trim template and material (or `/give` them for the
   test). Apply the trim to an armour piece at a smithing table.
2. **Expected:** works exactly like vanilla: the piece is trimmed, the
   material is consumed, and so is the template (this milestone changes
   nothing about the `minecraft:smithing_trim` recipe itself, only the
   separate duplication recipe below).

### 19.2 The duplication recipe actually refuses

1. With a trim template, its pattern's original block (e.g. a copper block
   for `bolt`), and a diamond, attempt the vanilla duplication recipe (template
   in the shaped 3x3, per `data/minecraft/recipe/<pattern>_armor_trim_
   smithing_template.json` in the 26.2 jar) in a crafting table.
2. **Expected:** no result. The override recipe requires `minecraft:barrier`
   in the block's position instead of the original material, and a survival
   player cannot obtain barriers, so a real crafting-grid attempt never
   completes it, permanently, for a template found anywhere (not only a
   dungeon-found one, since removal landed as global; see
   `COMPLETED-MILESTONES.md`'s M15 entry for why).
3. Confirm the recipe still shows in the recipe book (or `/recipe give` if
   testing as an op) so the "recipe not craftable" reading is because the
   ingredient is unobtainable, not because the recipe failed to register.

### 19.3 The worn bonus applies in combat

1. Wear a piece trimmed with a diamond-material trim (armour toughness
   bonus) and check the attribute value (F3 or an attribute-inspecting
   command) with the piece on versus off.
2. **Expected:** the configured bonus (`PocketDungeonsConfig.trimBonuses`,
   default `+1.0 armor_toughness` for diamond) is present only while worn,
   and disappears within one `watchIntervalTicks` window of removing the
   piece.
3. Swap the same slot to a different trim material (e.g. netherite,
   knockback resistance) without removing the piece from that slot in
   between (trim it fresh, or swap to another already-trimmed piece).
   **Expected:** the old attribute's bonus is gone and the new attribute's
   bonus is present; no slot ever carries two stacked bonuses from a
   material swap.
4. Take noticeably reduced knockback wearing a netherite-trimmed piece, or
   the analogous effect for whichever material is easiest to test live.

### 19.4 The dungeon-only flag, if flipped

1. With `trimBonusDungeonOnly` set to `true` in `pocketdungeons.json`, wear a
   trimmed piece outside the dungeon dimension.
2. **Expected:** no bonus applied. Enter `pocketdungeons:void`.
   **Expected:** the bonus appears within one watch-tick window, with no
   need to re-equip the piece.
3. Revert to the shipped default (`false`) and confirm the bonus applies
   everywhere again.

---

## 20. Gear gamble station (M16)

> The cost curve, the weighted-slot multiplier, and the tier-unlock gate are
> confirmed headlessly (`gambleMathTest`, and `KeystoneMath.lootTier` is the
> same function a run's own loot already resolves against). What is not
> headlessly checkable is the station right-click, the picker dialog, and
> the delivered item's live components (`DISCOVERIES.md` trap 10). That is
> this section.

### 20.1 The station always claims its block

1. Right-click the configured `gambleBlock` (default `minecraft:emerald_block`)
   with an empty hand, then again holding an arbitrary item.
2. **Expected:** both clicks open the gamble picker, unlike the reroll
   station (M14), which only opens for tiered gear. Nothing about the click
   is gated on what is held.

### 20.2 The picker offers only unlocked tiers

1. At a low keystone level (below the tier-2 threshold in
   `KeystoneMath.lootTier`), open the picker.
2. **Expected:** only tier-1 buttons for each of the five slots (helmet,
   chestplate, leggings, boots, weapon).
3. Raise the keystone level (complete runs, or `/dungeon admin` if such a
   command exists for testing) past the tier-3 threshold and reopen.
   **Expected:** tier-1, tier-2 and tier-3 buttons for every slot now
   appear.

### 20.3 The weapon slot costs more

1. Compare the emerald cost shown on a `weapon` button against a
   `chestplate` button at the same tier.
2. **Expected:** the weapon button's cost is `gambleSlotMultiplier` (default
   1.5x) the other slot's cost at that tier, per `GambleMath.cost`.

### 20.4 A gamble spends emeralds and delivers real gear

1. With enough emeralds, click a slot/tier button.
2. **Expected:** the shown cost in emeralds is removed from the inventory,
   and one item matching the slot (a helmet-type item for `helmet`, a
   weapon-type item for `weapon`, etc.) at roughly the tier's material and
   enchantment level (per M13's authored tables) is delivered, with the
   `pocketdungeons.tier` custom_data tag set. Inspect the delivered stack's
   components (`/data get entity <player> Inventory` or similar), not just
   that an item appeared (the carried lesson from M13's own verification).

### 20.5 A short emerald count refuses cleanly

1. With fewer emeralds than a button's shown cost, click it.
2. **Expected:** a chat message names the required amount, nothing is
   spent, and the picker reopens with the notice shown.

### 20.6 A gamble never out-produces a run's own chests

1. Compare a handful of gamble draws at tier 3 against opening several
   tier-3 chests in a real run.
2. **Expected:** qualitatively the same rate and spread of gear; the gamble
   draws from the same `gear/<slot>_<tier>` tables the chests fold in, not a
   richer or more generous pool.

## 21. The Herobrine Cube (M17)

### 21.1 Getting the extractable item

1. Complete a `drowned_vault` (boss-themed) run enough times, or use
   `/loot insert`/`/give` in a dev session, to obtain a "Warden's Ward"
   (`minecraft:heart_of_the_sea`, 5% chance in `tier_3_drowned.json`).
2. **Expected:** the item shows the light-purple custom name and the
   "Extract at the Herobrine Cube." lore line.

### 21.2 Extract

1. Place a `cubeBlock` (default `minecraft:beacon`) somewhere reachable.
2. Hold the Warden's Ward and right-click the block.
3. **Expected:** the item is consumed one for one, a chat message confirms
   the extraction, and re-checking (`/dungeon log` or similar, if such a
   command surfaces it) shows `warden_ward` in the player's permanent set.
4. Right-click again holding a second Warden's Ward (if available).
   **Expected:** a "you have already extracted this power" message; the
   item is not consumed.

### 21.3 Imbue

1. Hold a piece of tiered gear (any `pocketdungeons.tier`-tagged item, M13's
   loot) with no power yet, and right-click the Cube block.
2. **Expected:** the imbue picker opens, listing "Imbue warden_ward" and the
   iron ingot cost.
3. With enough iron ingots, click it.
4. **Expected:** the ingots are spent, the held item is now marked
   `custom_data.pocketdungeons.power = "warden_ward"`, and the picker
   reopens confirming the imbue. A short ingot count refuses cleanly with a
   chat message naming the required amount, and nothing is spent.
5. Right-click the Cube again holding the now-imbued item.
   **Expected:** falls straight through to vanilla's own beacon screen (not
   tiered-and-unpowered any more, so neither positive test fires).

### 21.4 The equip cap in combat

1. Extract and imbue enough powers to exceed `equipCap` (default 3) across
   armour and main hand.
2. **Expected:** only the first three distinct powers encountered in slot
   order (head, chest, legs, feet, main hand) actually apply their
   attribute bonus (check the relevant vanilla stat, e.g. knockback
   resistance for `warden_ward`); the excess imbued item still functions as
   an ordinary piece of gear, just with no bonus active.
3. Unequip one of the active three.
   **Expected:** the previously-excess power's bonus now applies on the
   next watch tick.

### 21.5 A non-qualifying item at the Cube block does nothing special

1. Right-click the Cube block holding an untagged vanilla item (plain dirt,
   an unenchanted un-tiered sword, etc.).
2. **Expected:** vanilla's own beacon screen opens, exactly as if this mod
   were not installed.

---

## 22. Room shell pass (M18)

### 22.1 The shell is immutable

1. As the room owner, try to break a wall block, a floor block, a ceiling
   block, a ceiling lamp, or the wall lodestone.
2. **Expected:** every break is refused; the block stays in place.
3. Try to place a block into the shell (against a wall from inside, on the
   floor, against the ceiling).
4. **Expected:** placement is refused.
5. Break and place blocks in the interior (x=1..14, z=1..14, Y=1..5).
6. **Expected:** both still work for the owner; a visitor (not whitelisted)
   is still refused, exactly as before M18.

### 22.2 Decorations still work

1. Place a torch, a wall sign, a banner, a button, an item frame and a
   painting against the room's walls; place a carpet on the floor.
2. **Expected:** all place and can be removed again. The shell check protects
   the block in the shell, not the face, so face-hanging decoration is
   unaffected.

### 22.3 The wall lodestone

1. Enter your room. **Expected:** a lodestone is set into the north wall at
   eye height (local x=1, y=2, z=0), not standing on the floor.
2. Stand on the floor block directly in front of it.
3. **Expected:** the stand-on leave-pad still ejects you from the dungeon
   (the mechanic stays until M21 replaces it).
4. Try to break it. **Expected:** refused, it is part of the shell.

### 22.4 Double doors after choosing a door

1. Enter the lobby and choose a door.
2. **Expected:** the three selector doors are gone; the punched doorway now
   holds two wooden doors side by side (Y=1..2) with a wall lintel above
   (Y=3).
3. Right-click the doors. **Expected:** they open like normal vanilla doors
   (the mod stops intercepting the click once a run is underway).
4. Let mobs reach the doorway while the doors are closed.
   **Expected:** they cannot walk through into the room.
5. Complete the run and walk back to the old room cell.
   **Expected:** it is a blank room cell; the doors did not bake into the
   room blob.

### 22.5 The ceiling

1. Look up at the ceiling from inside the room.
2. **Expected:** the interior ceiling reads as top-half slabs (half a block
   of extra headroom); the edge ring is full blocks; the four sea lanterns
   are each framed by four stairs, tall side against the lantern.
3. Complete a run so the room relocates, then enter again.
4. **Expected:** the new ceiling and fixtures are preserved, and the stair
   orientation stays correct at whatever rotation the room landed at.

## 23. Physical door selection (M19)

Everything in M19 is client-interactive; none of it has been verified
headless. The screen text, the bulb blockstates, and the lever/engine
interactions all need a live client.

### 23.1 The furniture is there and unbreakable

1. Enter a fresh lobby. **Expected:** on the selector wall (the wall the
   doors face away from the room), reading left to right: three selector
   doors; a copper bulb above each and a fourth above where the lever sits;
   a lever beside the third door; a black concrete screen (8 wide, 2 tall)
   set into the wall above the door row. On the wall to the left of the
   doors (facing them): a respawn anchor at eye height with a small black
   concrete screen above it.
2. Try to break each copper bulb, the lever, the door screen blocks, the
   engine block, and the engine screen blocks.
3. **Expected:** every break is refused, the same as the shell.
4. Try to place a block onto a bulb or lever position.
5. **Expected:** placement is refused.

### 23.2 The door screen renders

1. Look at the door screen from inside the room.
2. **Expected:** black concrete backdrop with readable text: "POCKET
   DUNGEONS", "Right-click a door to preview", "Pull the lever to start".
   The text must read normally (not mirrored), sit within the 8x2 backdrop,
   and stay visible from across the room. If it renders too small, too large,
   offset, or mirrored, tune the transformation scale and the anchor
   y-offset in `DungeonScreen.show` and the yaw in `DungeonScreen.yawFor`
   (the shipped values are derived from the 26.2 renderer but unverified).

### 23.3 Selecting a door

1. Right-click door 1.
2. **Expected:** door 1's bulb lights, the ready bulb above the lever
   lights, and the door screen switches to door 1's offer (keystone level,
   theme, affixes). No dialog opens anywhere.
3. Right-click door 2.
4. **Expected:** door 1's bulb goes dark, door 2's bulb lights, and the
   screen shows door 2's offer.
5. Right-click door 1 again.
6. **Expected:** the selection stays on door 1; the screen re-renders door
   1's offer.

### 23.4 The lever

1. Pull the lever (right-click it) without selecting a door.
2. **Expected:** the door screen shows "Select a door first" in red; nothing
   else happens; the lever does not visibly toggle.
3. Select a door, then pull the lever.
4. **Expected:** the dungeon generates behind the lobby, the timer starts,
   the bulbs go dark, and the door screen switches to the run context
   (keystone level, theme, affixes, clock).
5. During the run, walk back to the room.
6. **Expected:** the door screen still shows the run context; the engine
   screen shows the viewer's fuel count and "Cost per premium door: 3".

### 23.5 The engine terminal

1. Right-click the engine block holding an echo shard.
2. **Expected:** one shard is consumed from your inventory, the anchor's
   charge level rises by one (cap 4), and the engine screen updates. No
   vanilla respawn-charging happens (you are not in the Nether anyway).
3. Right-click the engine holding anything else.
4. **Expected:** nothing is consumed; the engine screen refreshes with the
   current fuel count and cost.

### 23.6 Fuel refusal

1. Complete a run so the room re-arms behind the terminal cell; select door
   2 or 3 (a greater door) with fewer than 3 echo shards in your inventory.
2. Pull the lever.
3. **Expected:** the door screen shows "Not enough fuel" and the run does
   not start.
4. Feed shards to the engine, then pull the lever again.
5. **Expected:** the run starts and the shards are spent.

### 23.7 Level gate refusal

1. Select a greater door at a keystone level below `greaterDoorMinLevel`.
2. Pull the lever.
3. **Expected:** the door screen shows the level-gate refusal and the run
   does not start.

### 23.8 Visiting a room

1. Have a second player use a calling card for your room while you are away.
2. **Expected:** the visit copy shows the door screen in room mode (your
   name, the visitor count, the whitelist size) and the engine screen with
   the cost line and no fuel count.

### 23.9 Teardown hygiene

1. Start a run, then have the instance tear down (timeout or admin purge).
2. **Expected:** no `text_display` entities are left in the room cell or the
   dungeon; a `text_display` with tag `pocketdungeons_screen` should not
   exist anywhere in the slot after teardown (check with
   `/execute in pocketdungeons:void run data get entity @e[tag=pocketdungeons_screen,limit=1]`
   or similar).

## 24. Visiting rework: lobby directory (M20)

Everything client-interactive in M20 needs a live client and has not been
verified headless. The codec round trip, the row filter, and the button
payloads are covered by `LobbyBrowserTest` and `DungeonLogTest`; what
follows is the part only a player with a screen can confirm.

### 24.1 Listing a room

1. With two players online, have player A run `/dungeon room public` in the
   overworld.
2. **Expected:** A gets "Your room is now listed in the lobby directory."
3. Have A run `/dungeon room name Cozy Den`.
4. **Expected:** A gets "Room name set to Cozy Den."

### 24.2 Opening the lobby directory

1. As player A, enter your own room (right-click the wall lodestone with a
   keystone, or `/dungeon`), then right-click the wall lodestone with an
   empty hand or a non-keystone item.
2. **Expected:** the lobby directory dialog opens, listing B's room if B has
   run `/dungeon room public`, with the room name and an occupancy count on
   each button.
3. Right-click the wall lodestone holding the keystone.
4. **Expected:** the dungeon ritual still runs (or the re-entry path fires);
   the directory does not open. The keystone branch stays ahead.
5. As a player who has never listed their room, open the directory.
6. **Expected:** their room does not appear (default private).

### 24.3 Visiting a listed room

1. Have B set their room public and be standing in it.
2. As A, open the directory and click B's room button.
3. **Expected:** A teleports into B's live room and gets the visit chat line.
   B's room shows status "open" in the directory.
4. Have B start a run; re-open the directory.
5. **Expected:** B's room shows "run in progress"; clicking it still joins
   the live room.
6. Have B leave and go to the overworld (or log off), then re-open the
   directory.
7. **Expected:** B's room shows "away"; clicking it stamps a read-only visit
   copy from B's saved blob (or joins an existing visit copy if another
   visitor is already there).

### 24.4 Unlisting and stale clicks

1. Have B run `/dungeon room private`.
2. Re-open the directory as A.
3. **Expected:** B's room is gone from the list.
4. With the directory open, have B unlist their room (or log off), then click
   B's button.
5. **Expected:** the visit is refused with a chat reason and the directory is
   re-shown with a reason line ("That room is not open any more."), not
   dropped to a closed screen.

### 24.5 The calling card is gone

1. Run `/dungeon room card`.
2. **Expected:** the command no longer exists (unknown subcommand). No
   calling-card item can be obtained anywhere, and any pre-M20 card in a
   chest behaves like a plain compass (its branch in `RitualListener` is
   deleted).

## 25. UX consolidation: one lodestone, one menu (M21)

Everything client-interactive in M21 needs a live client and has not been
verified headless. The option lists and the button payloads are covered by
`LodestoneMenuTest`; what follows is the part only a player with a screen
can confirm. Remember the context rule: the room is stamped in the dungeon
dimension, so "the overworld menu" means "right-click a lodestone while not
in the dungeon", and the in-dungeon menu opens on the room's wall terminal.

### 25.1 The overworld menu

1. In the overworld, right-click any lodestone with an empty hand.
2. **Expected:** the menu opens with four options: Start Dungeon, Browse
   Lobbies, Manage Room, Inspect Keystone.
3. Right-click a lodestone holding the keystone.
4. **Expected:** the menu still opens; nothing is consumed and no dungeon
   starts yet.
5. Click "Start Dungeon" without a keystone in your main hand.
6. **Expected:** chat refusal "Hold a keystone to start a dungeon." No
   dungeon opens.
7. Hold a keystone in your main hand and click "Start Dungeon".
8. **Expected:** the entry cue plays, "The lodestone pulls you under."
   appears, and you are teleported into your room lobby.
9. Click "Browse Lobbies".
10. **Expected:** M20's lobby directory dialog opens.
11. Click "Manage Room".
12. **Expected:** the manage-room dialog opens with the whitelist remove
    buttons, "Add a player...", "Set room name...", and the public/private
    toggle.
13. Click "Inspect Keystone" while carrying a keystone.
14. **Expected:** the `/dungeon key info` screen opens. Without a keystone:
    chat refusal "You are not carrying a keystone."

### 25.2 The in-dungeon menu

1. Enter your room lobby (in the dungeon), then right-click the wall
   lodestone with an empty hand.
2. **Expected:** the in-dungeon menu opens with Leave, Manage Room, Inspect
   Keystone.
3. Click "Leave".
4. **Expected:** you exit the dungeon with no stand-on pad needed, the same
   as `/dungeon exit`.
5. During a run, walk back to the room and right-click the wall lodestone.
6. **Expected:** the in-dungeon menu opens with Leave, Manage Room, Inspect
   Keystone.
7. As a party member or visitor in someone else's room, right-click the wall
   lodestone.
8. **Expected:** the menu opens with Leave and Inspect Keystone only; no
   Manage Room.

### 25.3 The pads

1. Complete a run; step on the terminal pad at the dungeon's end.
2. **Expected:** completion still works exactly as before (spawner gate,
   room relocation, reward chests).
3. Walk over the old leave-pad position in the room (the floor in front of
   the wall lodestone).
4. **Expected:** nothing happens; you are not ejected.
5. Right-click the terminal pad's lodestone instead of the wall lodestone.
6. **Expected:** no menu opens; standing on it still completes the run.

### 25.4 Room management

1. From either menu, open Manage Room.
2. Click "Room is private" (or "Room is public").
3. **Expected:** the label flips and the manager re-shows; the lobby
   directory lists or unlists the room accordingly.
4. Click "Set room name...", type a name, click "Set".
5. **Expected:** the manager re-shows with the new name in the body line;
   the lobby directory shows it.
6. Click "Add a player..." and add an online player.
7. **Expected:** the add screen works exactly as it does from
   `/dungeon room whitelist`.

### 25.5 Commands still work

1. Run `/dungeon`, `/dungeon exit`, `/dungeon key`, `/dungeon choose 1`,
   `/dungeon room public`, `/dungeon room name X`, `/dungeon room whitelist`.
2. **Expected:** all still function as before; the menu is an alternative
   surface, not a replacement.

---

## 26. Sound cues (M22)

> All of this is live-only: the cues are client-side playback, so nothing
> here can be verified headlessly. The volumes and pitches below are the
> shipped starting points; tune them during this pass if any cue reads too
> loud, too quiet, or wrong in character. Every cue is per-player
> (`player.connection.send`), so a second player standing nearby should hear
> nothing; the one exception is visitor arrives, which the owner hears.

### 26.1 Door selection (M19)

1. In your lobby, right-click selector door 1.
2. **Expected:** a short mid-pitch bell (`NOTE_BLOCK_BELL`) plays for you
   only, and the bulb lights.
3. Pull the commit lever with no door selected.
4. **Expected:** a low short bass (`NOTE_BLOCK_BASS`) plays and the door
   screen shows "Select a door first".
5. Select a door and pull the lever to start the run.
6. **Expected:** the run-start cue (`RESPAWN_ANCHOR_CHARGE`) plays for you
   only, the same cue previously broadcast to the whole room; nobody else
   hears it.

### 26.2 Run lifecycle

1. Complete a run on the terminal pad.
2. **Expected:** a rising bell-then-chime jingle (`NOTE_BLOCK_BELL` then
   `NOTE_BLOCK_CHIME`) plays.
3. Start a run and let the clock run out (or use a low-level key on a
   Greater door in the dev environment to force it).
4. **Expected:** a low sustained didgeridoo (`NOTE_BLOCK_DIDGERIDOO`)
   plays for the owner only, alongside the timeout chat line. An offline
   owner hears nothing (the cue is inside the `owner != null` guard).
5. Bank a door offer that raises the keystone level.
6. **Expected:** a rising `NOTE_BLOCK_CHIME` plays.
7. Time out or finish late on a door 2/3 run so the keystone depletes.
8. **Expected:** a descending two-note `NOTE_BLOCK_BASS` (pitch 0.8 then
   0.6) plays. A free-door (door 1) run never depletes, so it never plays
   this cue.
9. Clear every trial spawner in a cell.
10. **Expected:** a very quiet `NOTE_BLOCK_HAT` plays once, to each member
    standing in that cell at the moment the last spawner enters COOLDOWN.
    Leaving and re-entering the cell does not replay it; the run's
    completed state silences it.

### 26.3 Room and menu (M20, M21)

1. Right-click the wall lodestone to open the navigation menu.
2. **Expected:** a quiet `NOTE_BLOCK_HAT` plays.
3. In Manage Room, toggle the room public.
4. **Expected:** a mid `NOTE_BLOCK_CHIME` plays; `/dungeon room public`
   plays the same cue. Toggling private (or `/dungeon room private`)
   plays a quiet `NOTE_BLOCK_HAT` instead.
5. With a second player, have them visit your room through the lobby
   directory.
6. **Expected:** you (the owner) hear a two-note `NOTE_BLOCK_BELL`; the
   visitor hears their own visit-start cue (`ENDERMAN_TELEPORT`), not the
   owner's.
7. Complete a run so the room is re-placed behind the terminal cell.
8. **Expected:** the owner hears `STONE_PLACE` as the room re-stamps.

### 26.4 Lobby visiting (M20)

1. Open Browse Lobbies from the menu.
2. **Expected:** a quiet `NOTE_BLOCK_HAT` plays.
3. Click a listed room to visit.
4. **Expected:** `ENDERMAN_TELEPORT` plays to you as you arrive; the owner
   hears the two-note visitor bell.
5. Leave the visit (menu Leave, `/dungeon exit`, or disconnect).
6. **Expected:** `ENDERMAN_TELEPORT` at the lower pitch (0.7) plays to you
   as you return.

## 27. M18-M22 review fixes (live)

Fixes 1 and 2 change click behavior that no headless test can exercise; the
other three fixes are compile- and unit-test-verified.

### 27.1 Stale directory click (fix 1)

1. Owner A lists their room (`/dungeon room public`). Player B opens
   Browse Lobbies and leaves the directory open.
2. A sets the room private (or logs off).
3. B clicks A's row.
4. **Expected:** no teleport; the directory re-shows with a yellow line:
   "That room is no longer public." (or "The room owner is offline." when
   A logged off).
5. Repeat with A still public and online; the click must visit as before.

### 27.2 Free re-entry without the keystone in hand (fix 2)

1. Owner starts a run, then leaves the dungeon mid-run (`/dungeon exit`),
   with the keystone in inventory, not the main hand.
2. Right-click a lodestone, choose Start Dungeon.
3. **Expected:** the player is teleported straight back into the run for
   free, with "You step back into your dungeon." and no "Hold a keystone"
   refusal. `/dungeon` must behave identically.

## 28. Room template editor (M23)

Development-only, op-gated commands that write `.nbt` files into the mod's
own `src/main/resources` tree; they need a Loom dev server whose working
directory resolves `templateOutDir()` to the project.

### 28.1 buildroom

1. As an operator in the overworld, run `/dungeon admin buildroom`.
2. **Expected:** you teleport into an empty 16x16x6 shell at a fresh slot in
   `pocketdungeons:void`, standing at its centre, lit by the four ceiling
   lamps. No doors, no selector furniture, no timer, no keystone.
3. Break and place blocks freely, shell included (floor, walls, ceiling,
   lamps).
4. **Expected:** nothing is protected and nothing drops weirdly.
5. Run `/dungeon admin list`. **Expected:** the slot is marked
   `(BUILD ROOM)` and names you.
6. Run `/dungeon admin buildroom` again. **Expected:** the old shell is torn
   down and you land in a fresh shell at a new slot.
7. Run `buildroom` while inside a live dungeon (e.g. your own lobby).
   **Expected:** a refusal: "You are inside a live instance."

### 28.2 saveroom

1. Build something in the shell, then run `/dungeon admin saveroom myroom`.
2. **Expected:** you are teleported to the overworld spawn; the file
   `myroom_<playername>_<timestamp>.nbt` exists under
   `pocketdungeons/src/main/resources/data/pocketdungeons/structure/rooms/`;
   `/dungeon admin list` no longer shows the slot.
3. Load the file through the room manifest: add a `dungeon_room` JSON
   referencing the template (or point an existing entry at it) and reload.
   **Expected:** no rejection, and the room count in `admin list` reflects
   it.
4. Door jigsaws: place `pocketdungeons:door` jigsaws in the shell's wall
   slots, save again, and confirm the manifest derives the expected door
   mask.
5. Run `saveroom` with no build room open. **Expected:** a refusal: "No
   build room is open for you."

## 29. Room shells and prestige (M24)

Headless coverage is green (codec round trips, palette registry, menu
construction), but the actual world swap needs a live client. The frame must
swap while furniture, chests, stations and the leave pad stay exactly where
they are.

### 29.1 The menu option is the tutorial

1. Enter your room (open a dungeon from the overworld lodestone).
2. Right-click the wall lodestone (local x=1, y=2, north wall).
3. **Expected:** the in-dungeon menu shows Leave, Manage Room, Change Shell,
   Inspect Keystone.
4. Click Change Shell. **Expected:** the picker shows "Current shell: the
   default", an Apply Oak button, and grayed-out lines for Sandstone
   ("Found in rare rooms"), Deepslate ("Found in rare rooms") and Nether
   Brick ("Hold your room through 10 completions").
5. Click Apply Oak. **Expected:** a chat line "Your room's frame is now
   Oak.", the room's walls/floor/ceiling become oak planks, and everything
   inside stays in place. The wall lodestone still opens the menu.
6. Place a chest, a station and a piece of furniture first, then repeat the
   swap. **Expected:** all of it survives, contents included.

### 29.2 The rare-node token

1. Reach a drowned vault run (deepslate entry, blackstone descent, drowned
   vault boss) and complete it.
2. Open the completion chests. **Expected:** with the 10% token chance one
   chest holds a "Sandstone Shell" or "Deepslate Shell" (a renamed
   sandstone/deepslate block, lore "Unlock it from your room's menu.").
3. Hold the token and open Change Shell. **Expected:** an Unlock button for
   that shell appears ("Consumes the token you are holding").
4. Click it. **Expected:** the token is consumed, the chat confirms the
   unlock, and the shell now has an Apply button in the picker.
5. Leave the room and come back (or complete another run). **Expected:** the
   unlock is still there: it is per-player and permanent.
6. Apply the shell. **Expected:** walls/floor/ceiling swap to that material;
   the interior is untouched.
7. Hold an ordinary sandstone block (no token marker). **Expected:** no
   Unlock button: the marker, not the item, is what the picker reads.

### 29.3 Prestige

1. Complete 10 runs while keeping the same room; never run resetroom.
2. **Expected:** on the tenth completion a gold chat line announces the
   Nether Brick shell, and Change Shell lists it.
3. Apply it. **Expected:** the frame becomes nether bricks, interior intact.
4. Run `/dungeon admin resetroom <you>`. **Expected:** the prestige count
   restarts (the Nether Brick shell stays unlocked: unlocks are permanent,
   the count is the streak).

### 29.4 Constraints

- Every swap preserves furniture, chests, stations and the leave pad.
- A swap never drops items (drops are suppressed on every stamp).
- A non-owner or a visit instance never sees Change Shell.
- The swap works from a fresh lobby, mid-run, and in the post-completion
  room (ee door open onto the terminal cell).

## 30. Pocket2 Dungeon (M25)

Live verification of the nested sub-dungeon. Headless coverage is green (child
record linkage, slot allocation, full build), but finding the door, stepping
through, and being returned on the clock need a live client.

### 30.1 Find the door

1. Open a keystone run from the overworld lodestone (any theme with an
   adventure-graph node).
2. Clear the first encounter room you reach (all trial spawners at COOLDOWN).
3. **Expected:** with the ~20% per-run roll some runs have a 2-wide iron door
   set into a sealed wall of that room. Runs that did not roll one simply have
   no door (rare, not guaranteed).
4. Right-click the door mid-fight (before clearing). **Expected:** a refusal:
   "The door stays shut while the room still fights."
5. Right-click after clearing. **Expected:** a gold line "A rare door opens
   into a pocket of the dungeon. 60 seconds to grab what you can." and you are
   teleported to the pocket's entrance.

### 30.2 The pocket

1. **Expected:** a second boss bar reads "Pocket - 1:00 - x/y rooms".
2. Walk the pocket: 4-5 cells, loose chests drawing from
   pocketdungeons:chests/pocket2, and 1-2 trial spawners. No vaults, no
   completion pad, no keystone bar.
3. Open a chest. **Expected:** with the table's roll, a "Sandstone Shell" or
   "Deepslate Shell" token, echo shards, or valuables.
4. Stand on any lodestone inside the pocket. **Expected:** nothing happens:
   there is no completion pad.

### 30.3 The clock

1. Wait out the 60 seconds.
2. **Expected:** "The pocket closes; you are back at the door.", a return
   teleport to the stand spot just inside the door in the parent room, and the
   pocket's boss bar disappears. The outer run's clock kept ticking the whole
   time.
3. Re-enter immediately. **Expected:** a refusal: "The door is already open
   elsewhere." while the child still exists (or a fresh pocket once the old
   one is gone).

### 30.4 Death in the pocket

1. Enter a pocket and take lethal damage inside it.
2. **Expected:** no death screen, full reset, "The pocket throws you out; you
   are back at the door." and you return to the parent at the door with your
   inventory intact. The outer run's keystone settlement is NO_CHANGE, the
   same as any dungeon death.

### 30.5 Parent teardown

1. Enter a pocket, then have the owner leave the party or purge the parent
   slot with /dungeon admin purge <parent>.
2. **Expected:** the pocket is torn down with the parent (its slot freed, no
   orphaned blocks), and any member still inside is sent home to their
   original return point.

## 31. Extra features (M27)

Live verification of 27.1's experimental door and 27.2's visitor log.
Headless coverage is green (offer substitution, ring-buffer codec
round-trip), but the caution indicator and the wall terminal screen need a
live client.

### 31.1 Experimental dungeon

1. As an operator, run `/dungeon admin experiment <theme>` for a loaded
   theme id.
2. **Expected:** a chat line confirming door 3 now offers the experimental
   dungeon.
3. Open a run, reach the door selector, and right-click door 3. **Expected:**
   the door screen shows the experimental theme and a red "CAUTION:
   EXPERIMENTAL" line under the affixes.
4. Pull the lever with door 3 selected. **Expected:** the run generates at
   the experimental theme with no fuel spent and no level gate, even below
   the Greater-door minimum level.
5. Run `/dungeon admin experiment clear`. **Expected:** door 3 reverts to
   its normal Greater offer for the next run.
6. Repeat step 1 with an `affixes` argument and a `lootOverride` level.
   **Expected:** the run generates with those affixes, and the loot/mob
   difficulty matches the override level rather than the player's own
   keystone level.

### 31.2 Room visitor log

1. As one player, visit another player's public room from the lobby
   directory.
2. As the room's owner, open the wall terminal, Manage Room, "Recent
   visitors...". **Expected:** the visitor's name at the top of the list,
   a "just now" or "Nm ago" timestamp, and "(still inside)" while they are
   standing in the room.
3. Have the visitor leave (or log off). **Expected:** the same entry no
   longer shows "(still inside)".
4. Visit the same room 11 times (from 11 different accounts, or the same
   one repeatedly). **Expected:** the list never exceeds 10 entries; the
   oldest visit drops off.
5. As a visitor (not the owner), confirm there is no way to reach the
   Recent visitors screen: Manage Room is not offered at all when you are
   not the owner.

## 32. Themed mob spawners (M28)

Live verification that a theme's `spawner_prefix` actually changes which
mobs a trial spawner rolls. Headless coverage (`TrialContentConfigIdTest`)
only proves the id string is built correctly; it cannot prove the game
resolves that id to the right roster.

### 32.1 Deepslate (crypt roster)

1. Start a Deepslate-themed dungeon (`/dungeon admin experiment deepslate`
   or by keystone roll).
2. Clear several encounter cells. **Expected:** every trial spawner ejects
   only zombies and skeletons, never spiders or any other mob.
3. Repeat with the run at tier 2 and tier 3. **Expected:** the mob mix
   stays zombie/skeleton only; equipment on them still scales with tier.

### 32.2 Infestation (spider roster)

1. Start an Infestation-themed dungeon.
2. Clear several encounter cells. **Expected:** every trial spawner ejects
   only spiders and cave spiders, unequipped.

### 32.3 Swarming on a themed run

1. Start a Deepslate or Infestation run with the Swarming affix active.
2. Clear an encounter cell. **Expected:** more bodies than the untouched
   tier count, but still only that theme's mobs, not the default tier mix
   sneaking back in.

### 32.4 Unthemed run unchanged

1. Start a run on a theme that does not set `spawner_prefix` (e.g.
   Blackstone or Prismarine).
2. Clear an encounter cell. **Expected:** the same default tier mob mix as
   before M28.

## 33. No-backwards propagation (M29)

Live verification that a dungeon never places a room behind the player's
entrance room. Headless coverage (the `LayoutGraphGenerator` sweep) only
proves the grid math; it cannot prove what the stamped rooms look like
from inside the entrance.

### 33.1 Each door direction

1. Generate and enter a dungeon whose entrance door opens NORTH. **Expected:**
   no room is ever reachable directly south of the entrance room; walking
   the layout, every room lies north of (or level with) the entrance on
   the door's axis.
2. Repeat for SOUTH, EAST, and WEST entrance doors (reroll or use the
   admin experiment command to force each orientation if available).
   **Expected:** same result, mirrored to the matching reverse axis each
   time (south-opening: nothing north; east-opening: nothing west;
   west-opening: nothing east).

### 33.2 Branchy layouts

1. Generate several dungeons with branches and loops present (not just a
   straight critical path). **Expected:** branch and loop rooms obey the
   same rule as critical-path rooms; nothing wraps around to be reachable
   from a wall that borders the entrance room's exterior.

## 34. Tutorial screen and engine label (M32)

Live verification that the door screen's first-time prompt and the
engine screen's renamed title actually render for a real player.

### 34.1 Engine screen label

1. Stand at a room's engine screen (any keystone level). **Expected:**
   the title line reads "ECHO SHARDS", not "ENGINE".

### 34.2 Level-1 tutorial prompts

1. As a player at keystone level 1 (no completed runs yet), look at the
   idle door screen. **Expected:** "Select the Oak Door" instead of the
   normal "Right-click a door to preview" text.
2. Select a door. **Expected:** the preview screen shows the normal
   KEYSTONE/theme/affix lines plus "Pull the lever to descend!" in green.

### 34.3 Level 2+ unchanged

1. As a player at keystone level 2 or higher, repeat 34.2. **Expected:**
   the idle screen shows the normal "Right-click a door to preview" text
   and the preview screen shows no added tutorial line.

## 35. Stash and swap (M46)

The failsafe inventory model of `SITUATIONS_SPEC` section 11. This is the
riskiest thing the mod does: it takes custody of a player's real survival
inventory on a live world, and getting it wrong loses gear permanently.

What is already covered elsewhere, and therefore not repeated here:

- `InventorySwapTest` (headless) covers the slot layout, the 42 slot round
  trip, the cursor slot, a truncated backup, the invariant, the flag as
  deduplication, the untagged diversion, and the Lost and Found ring buffer.
- `InventorySwapGameTest` (`./gradlew runGameTest`) covers entry, exit, the
  issue #22 sequence, the issue #25 respawn sequence, and the cursor case,
  against a real server and a real `ServerPlayer`.

Everything below is what neither of those can reach. **M46 does not merge
until a human has walked this list once on a dev server.**

Two limits the gametests work around, which is precisely why 35.1 and 35.5
exist:

- A gametest server has no datapack dimensions at all: `GameTestServer`
  bakes an empty `LEVEL_STEM` registry against the flat world preset, so
  `pocketdungeons:void` is never created there and the gametests point the
  invariant at the nether instead. Nothing automated has ever seen the real
  dimension key.
- Nothing headless right-clicks a block (DISCOVERIES trap 10), so the ender
  chest cancellation has never been exercised by a click.

### 35.1 The real dimension key

1. `/dungeon` into a run as a player carrying a recognisable survival
   inventory (armour worn, something in the offhand, a full hotbar).
   **Expected:** the inventory empties the moment the room loads, and the
   keystone is in hotbar slot 0. Nothing is dropped on the floor.
2. `/data get entity @s Inventory` while in the room. **Expected:** the
   keystone and nothing else.
3. `/dungeon exit`. **Expected:** every slot comes back exactly as it was,
   armour on, offhand filled, hotbar in the same order.

### 35.2 A real server restart mid-run

The one case no test can stage: the backup has to survive the process
dying. This is the check that proves `SavedData` is actually carrying the
inventory rather than an in-memory map that happens to work.

1. Enter a run with a recognisable survival inventory. Pick a door and take
   the bag, so the live inventory is bag loot rather than survival.
2. From the console, `save-all flush`, then `stop`. Wait for the process to
   exit fully.
3. Confirm the stash is on disk before restarting:
   ```
   ls -l world/data/pocketdungeons_dungeon_log.dat
   ls -l world/data/pocketdungeons/lostandfound/<your-uuid>/
   ```
   **Expected:** both exist, and the newest `.log` in the lostandfound
   folder has an `ENTERING pocketdungeons:void` cause line and your
   survival inventory under `BEGIN LOST+FOUND CONTENT`.
4. Start the server. Log back in. **Expected:** you are still in the room
   or the dungeon, still holding the bag loot, and nothing has been
   restored. The invariant holds: in the void, stashed.
5. `/dungeon exit`. **Expected:** the bag loot goes to your room, and the
   survival inventory that was saved before the restart comes back whole.

### 35.3 Restart while standing outside the void, still flagged

The other half of 35.2, and the issue #22 shape against a real restart.

1. Enter a run, so the flag is set.
2. From the console:
   ```
   execute as <player> in minecraft:overworld run tp <player> <x> <y> <z>
   ```
   **Expected:** survival is restored within a tick. That is layer 1 doing
   it, so it should be instant and invisible.
3. To exercise layer 2 instead, do the same thing with the server stopped
   between the two halves: enter a run, `stop` the server, edit nothing,
   restart, and log in from a position outside the void (use
   `/spawnpoint` beforehand if needed). **Expected:** the join handler
   restores survival before you can act, and the dungeon inventory is
   delivered to your room rather than dropped in the overworld.

### 35.4 Deliberate DungeonLog corruption, and the Lost and Found

This is the check that proves the recovery layer is real. Do it on a dev
world, never on the live one.

1. Enter a run with a recognisable survival inventory. Note what you were
   carrying.
2. `save-all flush`, then `stop`.
3. Destroy the primary store:
   ```
   mv world/data/pocketdungeons_dungeon_log.dat /tmp/dungeon_log.dat.bak
   ```
4. Start the server and log in. **Expected:** the mod treats you as having
   no stash at all (no record reads as "not stashed"), so you keep whatever
   your live inventory holds and your survival inventory is gone from the
   primary store. This is the failure being simulated; it is not a bug.
5. Read the recovery copy:
   ```
   ls world/data/pocketdungeons/lostandfound/<your-uuid>/ | tail -3
   cat world/data/pocketdungeons/lostandfound/<your-uuid>/<newest>.log
   ```
   **Expected:** the file names sort newest last by plain text order, the
   newest entry has cause `ENTERING pocketdungeons:void`, and the content
   block lists 42 lines: `slot 0` through `slot 40`, then
   `slot 41 (cursor)`. Every non-empty slot carries the item id, the count,
   and the full stack as SNBT.
6. Hand the items back from that file with `/give`, using the SNBT on each
   line. **Expected:** the items come back with their components intact
   (enchantments, damage, custom names).
7. Restore the primary store (`mv` it back) before doing anything else.

### 35.5 The ender chest, by hand

Nothing headless can right-click, so this has never been run.

1. Inside `pocketdungeons:void`, place an ender chest in your room and
   right-click it. **Expected:** nothing opens. No GUI, no chest sound.
2. Right-click a normal chest or barrel in the same room. **Expected:** it
   opens as before, subject to the usual room permission mask.
3. Right-click an ender chest in the overworld. **Expected:** it opens
   normally. The cancellation is scoped to the dungeon dimension only.

### 35.6 The cursor item, by hand

The gametests cover the reconcile path. The teleport path is a vanilla
behaviour this mod cannot get ahead of without a mixin, and it needs a real
client to produce at all.

1. Open your inventory, pick a stack up onto the mouse cursor, and while
   holding it have somebody else run
   `execute as <you> in pocketdungeons:void run tp <you> <x> <y> <z>`.
   **Expected:** the stack is not destroyed. It is either folded back into
   a free inventory slot before the swap (vanilla's own handling) or
   captured in slot 41 of the backup, and either way it is in your survival
   inventory when you come back out.
2. Repeat with a completely full inventory (all 36 main slots occupied).
   **Expected:** worst case, the stack is dropped as an item entity at the
   origin position rather than deleted. Note where it lands. This is
   vanilla MC-258705 and is out of this mod's reach; the point of the check
   is to confirm the item still exists somewhere.

### 35.7 Untagged items diverted, not restored

1. Inside a run, have an operator `/give` you something that carries no
   `pocketdungeons.bag` tag, for example
   `/give <you> minecraft:netherite_sword`.
2. `/dungeon exit`. **Expected:** a yellow chat line telling you the item
   was left in your room, a `WARN` line in the server log naming the stack,
   the sword in one of your room's containers (or on the room floor), and
   your survival inventory restored without it.

### 35.8 The multi-floor loop is deliberately untouched

Not a check so much as a thing to confirm nobody "fixed". Spec section 12
makes a run a multi-floor loop, and inventory is meant to carry across
floors and only be delivered to the safe room on `/dungeon exit` or the
safe door.

1. Walk a run through more than one floor. **Expected:** no swap happens
   between floors, and the bag loot carries through untouched. Every floor
   and both rooms are inside `pocketdungeons:void`, so the dimension-based
   invariant never fires. There is no per-floor code and none is needed.
