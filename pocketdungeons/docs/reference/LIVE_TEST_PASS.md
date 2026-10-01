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
4. **Expected:** an ominous vault drops ominous loot and never a Boss Stone
   (PD-94 removed the Kamu Totems tokens).

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
2. **Expected:** both name a fuel cost (`fuelCostPerGreaterDoor`, default 3).
   Each has its own minimum keystone level: door 2 `door2MinLevel` (default
   7), door 3 `greaterDoorMinLevel` (default 15).

### 16.3 The level gate actually refuses

1. With a fresh level-1 key, look at the staging room's doors.
2. **Expected:** only door 1 stands (playtest 2026-09-29: a door whose level
   gate is out of reach is not placed at all, and its bulb is plain wall).
   Door 2 appears once the key reaches `door2MinLevel`, door 3 at
   `greaterDoorMinLevel`.
3. Still below the gate, type `/dungeon choose 2`.
4. **Expected:** refused with a chat message naming the required level. No
   fuel is spent and no dungeon is generated.

### 16.4 The fuel gate actually refuses

1. With a keystone at or above the door's level gate but fewer than
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

1. At a keystone level below `door2MinLevel`, look for door 2.
2. **Expected:** it is not there to select; only the doors the key can reach
   stand. `/dungeon choose 2` is refused with the level named, and the run
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

## 36. Lemon agent harness and omen rules (M33)

> The Lemon harness changes and the new omen/failure/quit rules are verified
> headlessly for compile, unit tests, and game tests, but the live behaviours
> below need a real client or the Node server tool.

### 36.1 Lemon reply and think

1. In the server tool, run `server wait` to enter LLM mode for yourself.
2. In game, type a question in chat so Lemon asks it (or run
   `dungeon lemon ask <player> <question>` from the tool).
3. From the tool, run `dungeon lemon reply <player> <answer>`.
4. **Expected:** the answer appears above Lemon's head within a second or two,
   even if you are in combat. The journal line for the original `lemon_ask`
   records `answered_by: llm` and a `wait_s` value.
5. Run `dungeon lemon think <player> let me check`.
6. **Expected:** Lemon says a short "let me check" line and immediately
   vanishes. A later `dungeon lemon reply` brings Lemon back with the answer.

### 36.2 Lemon quiet mode

1. With Lemon in LLM mode and no pending question, run `dungeon lemon quiet`.
2. **Expected:** Lemon says a goodbye line and disappears. Unprompted hints
   and tutorial lines stop until you address Lemon in chat again.
3. Speak to Lemon. **Expected:** Lemon becomes visible and interactive again.

### 36.3 Lemon linger and self-wait

1. Have the server tool in `server wait`. Send a short agent `say` line.
2. **Expected:** after the last bubble is shown and there is no pending
   question, Lemon disappears within `lemonLingerSeconds` (default 4).
3. Keep `server wait` running. **Expected:** the tool's own refresh log lines
   and echoes of your commands do not wake it from wait.
4. Run `server wait --player <name>` then disconnect and reconnect the same
   player. **Expected:** LLM mode reasserts immediately on join.
5. Run `server status`. **Expected:** the online player list is never empty
   while players are connected. `server sync` returns cleanly.

### 36.4 Omen bar and cues

1. Enter a run and read the boss bar.
2. **Expected:** it leads with spawner progress, e.g. "3 Spawners remaining".
   Omen is hidden while it sits at 0.
3. Trigger each omen source (linger in an unsolved room, trip a sensor,
   trigger a shrieker, accept a bargain, descend past floor 3 for the depth
   bonus). **Expected:** each rise shows a short in-voice chat line that names
   the cause, e.g. "Something below heard that; it is sending company. Omen
   2/4."
4. Reach omen 3/4. **Expected:** the bar appends "one more fall ends the run".

### 36.5 Omen danger, not reward cuts

1. Run a dungeon at omen 2 or 3. **Expected:** extra mob waves spawn with no
   drops; mobs hit harder or have more health than a calm run. Chests still
   contain three reward items and keystone progress still advances normally
   on completion.
2. Finish an interval at high omen. **Expected:** the settlement grants levels
   and key progress just like a calm run; omen band does not reduce chest
   count or keystone progress.

### 36.6 Death failure and inventory snapshot

1. In a party run, let omen reach 4/4, then take lethal damage.
2. **Expected:** the whole party is sent to their respective return points,
   the run ends, no unbanked floors pay out, and the dungeon inventory reverts
   to what it held when the current interval began. Your keystone level and
   home room are unchanged. A `run_failed` event is written to the playtest
   journal.
3. In a solo run, die at omen 0 first. **Expected:** you are rescued, omen
   rises by one, and you continue. Repeat until omen reaches 4, then die.
   **Expected:** the run fails as above.

### 36.7 `/dungeon quit` cost

1. During a run, run `/dungeon quit`. **Expected:** the confirmation dialog
   says the cost is 1 level. Confirm. **Expected:** you return home, keystone
   level drops by 1, and no other penalty is applied.

### 36.8 PD-74 to PD-77 live checks

1. **PD-74 (mob target clear):** Die to an enderman in an encounter room.
   **Expected:** after rescue you are in the staging room and the enderman is
   not there; it did not teleport after you.
2. **PD-75 (pressure plate drops):** In an Explosive-affix run, step on a
   stone pressure plate that sits on TNT. **Expected:** the TNT primes and the
   plate disappears without dropping an item.
3. **PD-76 (fuel screen):** Stand near the engine screen. **Expected:** the
   title reads "ECHO SHARDS", the stored balance line is white, the cost line
   is gray, and the fuel name appears (no trailing blank).
4. **PD-77 (Lemon linger):** already covered in 36.3.

1. Walk a run through more than one floor. **Expected:** no swap happens
   between floors, and the bag loot carries through untouched. Every floor
   and both rooms are inside `pocketdungeons:void`, so the dimension-based
   invariant never fires. There is no per-floor code and none is needed.

---

## 36. M62 acceptance index

Append-only. This is the index M62 (`d3-handoffs/M62-handoff-completed.md`)
asks for: one disposition per existing subsection above, plus the M45–M61
omissions the checklist never got rows for. Built from this file's section
and subsection headers plus a close read of sections 24, 26 and 35, per
M62's own "do not re-read the whole file" instruction; it is a routing index
for follow-up work, not a fresh verification pass over every row.

**Disposition legend**

- `current` — still an outstanding live-only check; no automated coverage
  found, no reason to think it has been exercised since it was written.
- `superseded` — replaced by a later section; successor named.
- `passed` — headless evidence now exists; evidence named.
- `blocked` — cannot be dispositioned without more investigation than this
  pass could do without re-reading the whole file, or waits on something
  external. Not a claim that the check fails.

### Sections 1–23 (M0–M19)

Everything in this range is `current`: live-client-only checks (screen,
sound, or a right-click, per DISCOVERIES traps 10 and 26), and this pass
found no test class or later section that has since covered any of them
headlessly. One exception:

- **3.1 Calling card** — `superseded` by **24.5 The calling card is gone**.
  M20 deleted the calling-card item and command outright; 3.1 asks to
  confirm behaviour of an item that M20's own successor check (24.5) asks
  to confirm no longer exists.
- **8.4 Spirit Stone permanence** — `blocked`, explicitly on an external
  mod (`spiritwolves`) per its own title, not on anything this milestone
  can resolve.
- **14.4 High-level affix stacking** — `current`, but its own title already
  marks it a playtest note rather than a pass/fail row; carried forward
  as-is.

### 24. Visiting rework: lobby directory (M20)

24.1–24.5: `current`. The header for this section already separates what
it covers (`LobbyBrowserTest`, `DungeonLogTest` — codec round trip, row
filter, button payloads) from what it doesn't (the screen itself); the
five rows above are exactly the "doesn't" part, unchanged.

### 25. UX consolidation (M21)

25.1–25.5: `current`, same shape as 24: `LodestoneMenuTest` covers option
lists and payloads headlessly, the screens themselves do not.

### 26. Sound cues (M22)

26.1–26.4: `current`. The section's own header states client-side playback
cannot be verified headlessly at all; nothing has changed that.

### 27. M18–M22 review fixes (live)

27.1–27.2: `current`. Live fixes to live-only rows stay live-only.

### 28–34

`current` throughout (28.1–28.2, 29.1–29.4, 30.1–30.5, 31.1–31.2, 32.1–32.4,
33.1–33.2, 34.1–34.3). All are screen, click or playtest checks per their
own text; none named a headless successor.

### 35. Stash and swap (M46)

- **35.1 The real dimension key** — `passed` (partial). Evidence:
  `dungeonIntegrationTest` (M62), which boots a real dedicated server
  against this project's own bundled datapack (not `GameTestServer`, which
  DISCOVERIES trap 18 already rules out for this) and asserts
  `server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL)` is non-null, then
  forces a save and confirms `world/data` exists on disk. That is the
  dimension-key half of 35.1's three steps. The other two — the inventory
  actually emptying into a keystone-only hand, and `/dungeon exit`
  restoring it — need a real player and stay `current`.
- **35.2–35.4** — `current`, explicitly out of scope for M62 by its own
  constraints ("M62 does not run [corruption tests]; M63 does"). M63
  inherits these as its custody and fault scenarios.
- **35.5–35.8** — `current`. Right-click and cursor-drag checks
  (DISCOVERIES trap 10) and one deliberate non-check (35.8).

### M45–M61 omissions

`COMPLETED-MILESTONES.md` jumps from M44 straight to M61; M45–M60's scope
(`ROADMAP.md` "M45 through M55: Situations and Bags", plus the situation
catalogue in `SITUATIONS_AUDIT.md`) never got consolidated entries or live
rows here. The seven rows below are the ones M62's own handoff named by
name; each is `blocked` pending someone locating the actual mechanic and
writing real steps, not a claim that the feature is broken:

- **Floor bank timing** — `blocked`. Likely the door-offer bank/timer
  interaction from section 9 and 16, re-scoped under the M48 omen system
  (`ROADMAP.md`: "the global clock is replaced by omen"); no section above
  currently names omen at all, so there is nothing existing to supersede.
- **Recipes** — `blocked`. Section 7 (M7) covers the original theme/recipe
  system; unclear whether the situations round (M49 bag loot tables, M54
  Cube recipes) added recipes this file has never listed.
- **Frozen preview membership** — `blocked`. Likely `/dungeon admin
  buildroom`'s preview state (section 28.1); "frozen" and "membership"
  aren't vocabulary this file uses anywhere in sections 1–35, so this needs
  someone to find the actual command/state before a row can be written.
- **Post-selection tool depletion** — `blocked`. Reads like a limited-use
  tool consumed after a selection (buildroom/saveroom again, or a
  situation's own tool), same gap as above.
- **Return paths** — `blocked`, but with a concrete lead: M61's
  `ReturnPathValidator.validate` structurally checks a climbable route
  (ladder, water source column, soul sand bubble column, or a solid-block
  staircase) for any `spanY > 1` room at stamp time, and refuses the stamp
  if none is found. That is headless coverage of "does a route exist at
  all", already proven for Slime Pit's pilot ladder shaft
  (`plans/COMPLETED-MILESTONES.md` M61). What is still `current`, not
  `passed`, is a player actually falling in and climbing back out — the
  validator checks block geometry, not that a player's hitbox and jump
  height can use it.
- **Room preservation** — `blocked`, same lead. M61 extended teardown
  (`InstanceRegistry.maximalBounds`, `InstanceTeardown.PendingClear`,
  `Instances.clearCellSync`, `RoomProtection.isShell`) to cover a two-story
  room's full vertical extent. No live check above confirms a two-story
  room actually tears down clean (no orphaned lower-story blocks, no
  leftover bedrock) on purge or leadership change (sections 2.5, 2.6).
- **Silence about room movement** — `blocked`, and this one surfaced a real
  open question rather than just a missing row: `NEXT_ROADMAP.md` states
  "no sound for room movement" as a constraint for later work, but section
  **26.3** already documents a shipped cue — `STONE_PLACE` plays for the
  owner when the room re-stamps after a run completes. Whoever picks this
  row up needs to reconcile whether 26.3's cue is the thing "silence" means
  to remove, or whether "room movement" names something else (relocation
  between cells?) that hasn't shipped yet.

## 37. M63 remaining client rows

M63 closed most of the M44 custody gaps with gametests (the fault matrix in
`plans/COMPLETED-MILESTONES.md` names each one and the test that covers it).
These are what it could not reach headlessly, and why. Every row here is
`current`: unverified, not assumed working.

### 37.1 The gamble station's money path

`GambleStation.handleTrade` deducts emeralds, rolls the gear table, delivers
the item and credits the High Roller bounty. It is a private callback on an
SGUI `MerchantGui`, reachable only by a player clicking a trade, and
DISCOVERIES trap 10 rules that out headlessly. Calling it artificially would
assert the harness rather than the station.

Check, at a real client:

- Buy one gamble with exactly the listed cost. Confirm the emeralds are
  debited once, one item arrives, and the chat line names what arrived.
- Buy one with a full inventory. Confirm the item drops at your feet rather
  than vanishing, and that the emeralds were still debited exactly once.
- Click the same trade repeatedly and quickly. Confirm one debit per item,
  never two debits for one item and never one debit for two.
- Open the screen, have a second player take your emeralds, then click.
  Confirm the refusal spends nothing.
- Gamble as a party member, not the host. Confirm the emeralds count toward
  the **host's** High Roller bounty, which is the deliberate rule in
  `GambleStation`, and confirm the item goes to the member who paid.

### 37.2 A real process kill between the clear and the saved-data write

M63's journal repairs a stash record lost to a non-atomic
`SavedDataStorage` write, and a gametest proves the repair is correct and
idempotent. What that test stages in-process is the *state* a kill leaves,
not a kill. The reload path is unproven.

Check, on a dev server:

- Enter a dungeon with a recognisable survival inventory. Kill the server
  process (not `stop`) within a second or two of entering.
- Restart. Confirm the log carries `Repaired a lost stash record` if the
  write was lost, or nothing if it landed. Either is correct; what must not
  happen is the player logging in holding neither inventory.
- Leave the dungeon. Confirm the survival inventory comes back exactly once,
  with components intact.
- Repeat, killing during the *exit* instead. The leaving branch is
  deliberately not journalled, so the expected behaviour is different: the
  stash flag is still set and the next tick retries the restore from a clean
  state. Confirm no duplication.

### 37.3 Orphan overflow at a real dungeon entrance

The gametest for orphan overflow points the invariant at the overworld so
the overflow drops in a chunk the harness has already loaded. That exercises
the same branch, but not the real geometry: a genuine entry drops overflow
inside `pocketdungeons:void`, at the room cell, where a teardown may be
running.

Check:

- Arrange an orphan larger than 35 stacks (leave a dungeon carrying a full
  void inventory, twice, without re-entering in between).
- Re-enter. Confirm the overflow lands on the floor of the safe room and is
  recoverable, rather than falling through a cell that is mid-clear.

### 37.4 Another inventory-management mod alongside this one

An explicit interoperability check, not a compatibility claim. Server-only
branding does not make this safe.

## 38. M75: social visits, mementos and retuned bounties

Headless tests cover the pure logic (friend-row construction, memento title
and lore, bounty pool composition, sidecar round trips). The following
require a live client session with two or more players.

### 38.1 Whitelist-gated private visit

- Owner A lists their room as private and whitelists player B.
- Player B opens the lodestone menu and sees "Visit a Friend".
- Player B clicks "Visit a Friend" and sees A's room in the list.
- Player B clicks A's room and is routed to A's room via VisitService.
- Owner A removes B from the whitelist while B's friend list is open.
- Player B clicks A's room again; the list re-shows with "You are no
  longer invited to that room." instead of routing.
- A player C who is not whitelisted by A never sees A's room in their
  friend list.

### 38.2 Run memento placement and readback

- Complete a run (reach a safe visit).
- Run `/dungeon memento` and receive a written book.
- Place the book on a lectern in your room.
- Right-click the lectern and confirm the book shows the run's theme,
  keystone level, loot tier and affixes.
- Confirm the book's tooltip in the inventory shows the same details.
- Confirm a plain written book is not recognised as a memento.

### 38.3 Retuned bounty progress

- Check the bounty tracker screen after a safe visit.
- Confirm only exploration, clearing and low-omen bounties appear (no Echo
  Harvester, High Roller, Keystone Climber or Pack Hunter).
- Confirm EXPLORER progresses by 1 per safe visit.
- Confirm TIDY progresses only when all spawners on the floor are cleared.
- Confirm DEEP_DIVER progresses only at the second safe-visit depth or
  deeper.
- Confirm a solo player can complete every bounty in the pool.

Check, with a second inventory mod installed (a sorter, a backpack mod, or
anything that moves stacks on the server):

- Enter and leave a dungeon. Confirm both inventories round trip.
- Trigger the other mod's sorting while standing in the dungeon. Confirm the
  swap still restores survival exactly on the way out.
- Confirm the other mod's own slots (curios, backpack slots) behave as spec
  11.12 says they will: they are outside the 41-slot snapshot and are not
  stashed.

### 37.5 Two-story and Pocket2 child teardown

M63's teardown tests assert slot bookkeeping, which is the half that leaked.
The block-writing half needs real geometry and is unchanged from M61's
`blocked` row on room preservation, repeated here so it is not lost: confirm
a two-story room and a Pocket2 child both tear down clean, with no orphaned
lower-story blocks and no leftover bedrock, on purge and on leadership
change.

## 38. M64 acceptance: rooms as played, not merely selected

M64 closes the gap between the selector's abstract capability graph and the
physical world the player stands in. The automated evidence is in
`SITUATIONS_AUDIT.md` section 5; this section is the live-only half that
the automated tests cannot reach.

### 38.1 The spent optional tool, by hand

The gametest `spentOptionalToolStillHasExit` proves the door stays open
after the optional item is spent, in a synthetic structure. The live
check is the same shape against a real room: enter a room with an
`ITEM_ANY` or `ITEM_KEY` gate, open it with the item, drop or use the
item, and confirm the door does not close behind you.

1. Enter a dungeon with a Frame Lock or similar item-gated room.
2. Place the key item in the chest. **Expected:** the door opens.
3. Remove the item from the chest. **Expected:** the door stays open.
4. Walk through the door and back. **Expected:** the door does not close.

### 38.2 Rising Lava, by hand

The gametest proves the lever drains the lava and the room drops from
the active map. The live check is the player's experience of the room:
the lava spreads, the player pulls the lever, the lava recedes, and the
room is safe to walk back through.

1. Enter a Rising Lava room. **Expected:** lava spreads inward from the
   walls while you stand in the room.
2. Pull the lever. **Expected:** the lava drains, the room is marked
   solved, and walking back through it is safe.
3. Leave and re-enter the cell. **Expected:** the lava does not return.

### 38.3 Collapsing Bridge, by hand

The gametest proves the bridge arms and clears. The live check is the
player's experience: the bridge collapses underfoot, the player falls,
and the bridge re-extends after a delay so backtracking survives.

1. Enter a Collapsing Bridge room. **Expected:** sticky pistons hold
   planks across a gap.
2. Stand on the planks. **Expected:** after a short delay, the pistons
   retract and the planks drop.
3. Wait. **Expected:** the pistons re-extend and the planks return.
4. Walk back across. **Expected:** the bridge holds long enough to
   cross, then collapses again.

### 38.4 Return path, by hand

The gametest proves the validator accepts a ladder column, a water
column, and a staircase with headroom, and rejects a room with no
climbable route. The live check is the player actually climbing: the
validator checks block geometry, not that a player's hitbox and jump
height can use the route.

1. Enter a two-story room (`spanY > 1`). **Expected:** a climbable
   route exists from the lower floor to the upper floor.
2. Fall to the lower floor. **Expected:** you can climb back up using
   the route the validator found.
3. Check headroom on the route. **Expected:** no block prevents a
   player's hitbox from passing through.

### 38.5 Omen spur edge cases, by hand

The gametest pins down four edge cases in `OmenSources.spurTaken`. The
live check is the player's experience of the omen firing or not firing
in each case. These are documented gaps, not fixes; a live pass confirms
the behaviour matches what the test says.

1. Take some but not all loot from a spur container. **Expected:** the
   omen does not fire (false negative).
2. Take all loot and put junk back. **Expected:** the omen does not
   fire (false negative).
3. Find a spur container that rolled nothing. **Expected:** the omen
   fires on the first poll (false positive).
4. Use a barrel instead of a chest. **Expected:** the omen behaves the
   same as with a chest.

### 38.6 Supply chest guaranteed food and light, by hand

The headless test proves the supply tables now separate guaranteed food
and light from weighted treasure. The live check is the player actually
opening a supply chest and seeing food and light every time.

1. Enter a dungeon and find a supply chest (the ungated chest in a
   trial cell).
2. Open it. **Expected:** food is present every time. Light (torches)
   is present every time.
3. Repeat across several runs and tiers. **Expected:** food and light
   are always present, never absent. Treasure (iron, gold, experience
   bottles) is sometimes present, never guaranteed.

## M65 supersession: old live clock and homecoming checks

### Old live clock checks (superseded)

The old live clock checks verified that a timed-out run depletes the
keystone and the dungeon stays open in overtime. M65 removes
ordinary-floor timeout depletion entirely: the clock still ticks for
display, but no longer depletes the keystone. The omen system replaces
the clock as the penalty.

Superseded checks:
- "Wait for the clock to run out. Expected: keystone depletes, dungeon
  stays open." Now: the clock runs out with no depletion. The omen
  system determines the settlement at the safe visit.
- "Complete a floor after timeout. Expected: SPEEDRUNNER bounty does
  not fire." Now: SPEEDRUNNER is low-omen completion (band 0, 3
  chests), not finished before the clock.

### Old homecoming checks (superseded)

The old homecoming checks verified that returnToSafe teleports the
party into the safe room and plays Chime.roomRelocated. M65 replaces
this with a silent homecoming: the saved room is stamped behind the
final staging door, the door opens, and the party walks through
physically.

Superseded checks:
- "Select the safe door. Expected: teleport into the safe room,
  relocation chime plays." Now: no teleport, no chime. The party walks
  through the staging door into the room.
- "Verify the relocation message appears." Now: no message. The room
  is simply there.

### New live checks (M65)

These are live-only and cannot be verified headless:
1. Walk through the final homecoming. Expected: the party walks
   through the staging door into the room. Zero teleport.
2. Verify the room contains recognizable furnishing. Expected: the
   saved room's furniture is present, not a seed reconstruction.
3. Verify a party can participate. Expected: all members cross
   physically.
4. Verify zero sound cue at the reveal. Expected: no
   Chime.roomRelocated.
5. Verify zero explanation at the reveal. Expected: no chat message
   or UI text announcing the room move.

### Recipe checks (M66)

These are live-only and cannot be verified headless:
1. Hold a keystone in the main hand and string in the off-hand.
   Right-click the Cube. Expected: BOUNDED_SUPPLY confirmation
   message. The catalyst is consumed and escrowed.
2. Preview a door. Expected: the preview reflects the bounded supply
   effect. The recipe plan is frozen.
3. Cancel the preview (click a different door or leave). Expected:
   the escrowed string is returned to the player. A yellow message
   confirms the return.
4. Commit the preview. Expected: the dungeon stamps with the recipe
   effect. The catalyst escrow is cleared (permanently spent).
5. Hold a keystone and an amethyst shard. Right-click the Cube.
   Expected: PATH_EXTENSION confirmation. The next run is two rooms
   longer.
6. Hold a keystone and a compass. Right-click the Cube. Expected:
   COMPASS confirmation. On floor completion, the completion line
   lists the run situations by name.
7. Hold a keystone and an ominous bottle. Right-click the Cube.
   Expected: OMINOUS confirmation. The next run starts ominous.
8. Hold a keystone and wool. Right-click the Cube at keystone level
   10+. Expected: DEEP_DARK confirmation. At level below 10, the
   recipe refuses and the catalyst is not consumed.
9. Load a keystone with a legacy bag_override tag. Commit a door.
   Expected: a yellow notice names the legacy bag id and suggests
   /dungeon admin refund. The run proceeds as bounded supply.
10. Load a keystone with a legacy double_key tag. Commit a door.
    Expected: the run is two rooms longer at the committed offer
    level. No lower-key level is invented.

Headless verification (passed):
- previewFreezesRecipeMembership: recipe membership, effect set,
  and seed survive. Changed catalyst, party capability, and offer
  level invalidate the revision. Unrelated changes do not.
- deepDarkRefusesBelowTier3: Deep Dark refuses at tier 1 and 2,
  resolves at tier 3.
- legacyBagOverrideDecodesAsBoundedSupply: legacy tag decodes as
  boundedSupply, not a bag swap.
- legacyDoubleKeyDecodesAsPathExtension: legacy tag decodes as +2
  path length.
- ominousAndFeralAddAffixes: affixes added without mutating the
  base set.
- emptyRecipesResolveToNoEffects: null and empty tags resolve to
  no effects.
- infestedAndFloodedCoexist: both effects active without refusal.
- allEffectsResolveAtTier3: all nine effects resolve at tier 3.
- effectSummaryIsLegible: summary is a comma-separated list.
- fixedCatalystNoDuplicateActiveRecipes: no duplicate active
  recipes.
- catalystEscrowReadWrite: escrow write and read work without a
  server.
- freshKeystoneHasNoEscrow: no false positives on a fresh keystone.
- recipeTagsSurviveUntilCommit: reading does not clear; clearing
  removes.
- boundedSupplyMatchesString: keystone + string matches
  BOUNDED_SUPPLY. BAG_OVERRIDE no longer matches.
- pathExtensionMatchesAmethystShard: keystone + amethyst shard
  matches PATH_EXTENSION. DOUBLE_KEY no longer matches.
- legacyDoubleKeyMigratesToCurrentLevelExtension: legacy tag
  migrates to current-level +2 extension.

Live-only (blocked, unverified):
- Right-clicking the Cube with each catalyst. Blocked: no client.
- GUI interaction with the Cube. Blocked: no client.
- Player positioning for preview/commit. Blocked: no client.
- Human catalyst use and recovery. Blocked: no client.
- Study-list meaning on completion. Blocked: no client.

## 39. M67 closure: code-side pass and Q5 outcome

M67 is a human gate. The code-side work is complete; the human pass
(tester recruitment, live observation, honest dispositions) is not. This
section records what was done in code, what it found, and what remains for
the human gate. Round II waits for the human pass, not just this code-side
appendix.

### Chime audit (step 3)

All 47 `Chime.*` call sites across 9 files were audited for recipient
correctness, duplicate playback, and competition with vanilla hazard
sounds.

**Recipient correctness:** all chimes are sent to the correct player
via `player.connection.send`. The one intentionally non-self chime is
`visitorArrives(ownerPlayer)`, heard by the room owner when a visitor
enters, which is by design (M22). No recipient errors found.

**Duplicate playback:** no code path triggers the same chime twice for
the same event. The three `visitStarts` calls in `VisitService.java`
(L74, L86, L140) are on mutually exclusive branches (owned instance,
existing visit, new visit). The two `runStarts` calls (RitualListener
L363 for lever commit, DialogRouter L187 for lodestone pull) are
different entry points. No duplicates found.

**Competition with vanilla hazard sounds:** all chimes use
`SoundSource.RECORDS` (Chime.java L157), while vanilla hazard sounds
(creeper hiss, skeleton bow, zombie groan) use `SoundSource.HOSTILE` or
`SoundSource.NEUTRAL`. Different channels, low volumes (0.2 to 0.5). No
significant competition.

**Constraint violation found and fixed:** `Chime.roomRelocated` played
`STONE_PLACE` at two call sites (RunLifecycle.java L1189 for
post-completion relocation, L1580 for safe return). This violated both
the M67 constraint "No sound for room movement" and VISION.md section 4:
"Nothing explains this. No message, no sound, no lore entry." Both call
sites and the method itself are removed. The room relocation is now
silent, as the vision intended.

### Q5 outcome: omen environmental feedback

Q5 asked whether a player understands they are in an ominous run without
being told, and whether a sound-off player has a visible environmental
signal.

**Existing signals (code-verified):**
1. Chat message at run start (DARK_PURPLE): "The run is ominous. Every
   spawner and every vault bites harder, and the payout is worth more
   for it." (RunLifecycle.java L476 to L479)
2. Trial Omen potion effect (MobEffects.TRIAL_OMEN) applied to the
   player (Instances.java L511 to L515). This is a vanilla HUD icon
   that persists throughout the run. A sound-off player sees it.
3. Ominous trial spawners and vaults have visually distinct blockstates
   (vanilla behaviour, stamped by LayoutStamper).
4. Door screen affix line includes "Cooked" (the OMINOUS label).

**Improvement applied (M67):** the OMINOUS affix is now coloured
DARK_PURPLE in the door screen's affix line, matching the chat message
colour. Previously `affixLine` returned a plain string with no
formatting; it now returns a `Component` with the OMINOUS label styled
DARK_PURPLE. This gives a sound-off player a second visible signal
beyond the Trial Omen HUD icon: the purple "Cooked" text on the door
screen during the run.

**No numeric HUD added.** The handoff constraint "no numeric HUD" is
honoured. The Trial Omen effect icon and the coloured affix text are
the visible signals; both are environmental, not numeric.

**No bounded vanilla ambient cue trialled.** The existing signals
(chat, HUD icon, coloured affix, ominous spawner visuals) are assessed
as adequate environmental feedback for a sound-off player. The
escalation path (trialling one bounded vanilla ambient cue) is not
needed at this code-side stage. If the human pass finds the signals
inadequate, the escalation path remains open for the human gate.

### First-time verb fix (step 2, code side)

The idle door screen's first-time prompt (keystone level 1) said
"Select the Oak Door" without mentioning right-clicking. A first-time
player who has never interacted with a selector door may not know to
right-click it. The prompt now says "Right-click the Oak Door",
matching the returning-player prompt's "Right-click a door to preview"
clarity. The returning-player prompt and the preview-content tutorial
line ("Pull the lever to descend!") are unchanged.

### Appendix corrections

The following sections contain instructions made obsolete by M67's
code-side changes. They are not rewritten; the corrections are noted
here so a reader knows which lines to discount.

- **Section 26.3, steps 7 to 8:** "Complete a run so the room is
  re-placed behind the terminal cell. Expected: the owner hears
  STONE_PLACE as the room re-stamps." **Obsolete.** M67 removed
  `Chime.roomRelocated`. The room relocation is now silent. The
  expected behaviour is: no sound plays. This aligns with VISION.md
  section 4 and the M65 supersession note at line 2184 ("Verify zero
  sound cue at the reveal. Expected: no Chime.roomRelocated").
- **Section 34.2, step 1:** "Select the Oak Door" is now "Right-click
  the Oak Door" in the shipped code. The check's intent (a level-1
  player sees a tutorial prompt) is unchanged; the exact text is
  updated.
- **Section 36, M62 disposition index, "Silence about room movement"
  row:** was `blocked`, asking whether 26.3's STONE_PLACE cue is the
  thing "silence" means to remove. **Resolved by M67:** yes, it was.
  The cue is removed. The row is now `passed` (code-side): the
  roomRelocated chime no longer exists, and the room movement is
  silent in code. The human pass confirms the silence is heard (or
  rather, not heard) on a live client.

### Human pass: remaining rows

The code-side work is complete. The human pass remains. Every row in
the M62 disposition index (section 36) that is `current` stays
`current` until a human tester runs it on a live client. M67 does not
coerce any `current` row to `passed`. The rows M67 can disposition from
code-side evidence are:

- "Silence about room movement": `passed` (code-side). The chime is
  removed; the silence is enforced in code. The human pass confirms
  the absence of sound on a live client.
- All other `current` rows: unchanged, pending the human pass.
- All `blocked` rows: unchanged, pending investigation or the human
  pass as noted in section 36.
- All `superseded` rows: unchanged, pointing to their successors.
- All `passed` rows: unchanged, their headless evidence still stands.

The human gate closes when a tester has run every applicable `current`
row and recorded an honest disposition. Until then, Round II waits.

## 40. M73 acceptance: six new identities, data only

M73 doubles the composition space without a new `.nbt` template. The
automated evidence is the build and the four content tests; this section
is the live-only half the automated tests cannot reach.

### 40.1 Theme recognition, by hand

The whole point of M73 is that a player recognises a theme from its
composition, not from its name. The automated tests confirm the data
loads and the graph is solvable; only a human confirms the identity
reads.

1. Enter a Rootworks floor. **Expected:** moss, rooted dirt, shroomlight;
   spiders and witches; vine, string, moss loot.
2. Enter a Frostworks floor. **Expected:** packed and blue ice; strays
   and zombies; snowball and ice loot. The footing should feel slippery
   where ice replaces floor.
3. Enter a Copper Works floor. **Expected:** copper and cut copper;
   zombies and creepers; redstone and piston loot.
4. Enter an Ossuary floor. **Expected:** bone blocks and soul lanterns;
   skeletons and strays; arrow and bow loot, sustained ranged pressure.
5. Enter a Basalt Foundry floor. **Expected:** nether bricks, basalt,
   glowstone; blazes and magma cubes; magma cream and blaze rod loot.
6. Enter an Ender Archive floor. **Expected:** end stone, crying
   obsidian, end rods (dim); endermen and silverfish; ender pearl and
   chorus loot.

### 40.2 Signature rooms, by hand

Each theme has one signature room with its own processor list. The
automated tests confirm the room metadata loads; only a human confirms
the room reads as a signature.

1. Find a `rootworks_grove` room. **Expected:** mossy tee under a grove
   processor, denser overgrowth than the base theme.
2. Find a `frostworks_glaze` room. **Expected:** mossy tee under a glaze
   processor, ice glaze distinct from the base frost.
3. Find a `copper_works_forge` room. **Expected:** a forge palette
   distinct from the base copper theme.
4. Find an `ossuary_crypt` room. **Expected:** a crypt palette distinct
   from the base bone theme.
5. Find a `basalt_foundry_crucible` room. **Expected:** a crucible
   palette distinct from the base nether theme.
6. Find an `ender_archive_vault` room. **Expected:** a vault palette
   distinct from the base end theme.

### 40.3 Pair distinctness, by hand

The handoff required each pair to play differently, not just look
different. The automated tests cannot judge "plays differently"; a human
must.

1. Play a Rootworks run and a Frostworks run back to back. **Expected:**
   overgrowth versus footing reads in the roster, the loot, and the
   floor feel, not just the colour.
2. Play a Copper Works run and an Ossuary run back to back. **Expected:**
   melee and explosion management versus sustained ranged pressure.
3. Play a Basalt Foundry run and an Ender Archive run back to back.
   **Expected:** heat and splitting mobs versus displacement and
   darkness.

### 40.4 Affix deferral, noted

`jumpy` and `clingy` were requested as data-only affixes. Both are
deferred: the M69 operation set cannot express a spawner roster rewrite
(`jumpy`) or a web hazard with guaranteed shears (`clingy`) without new
engine work, and the milestone forbids smuggling engine work into data.
This is recorded in `docs/DISCOVERIES.md` trap 34. No live check applies
until the engine work lands.

### Headless verification (passed)

- packValidationTest: passed.
- adventureGraphTest: 11 themes, 11 adventure nodes, 0 rejected, all
  six new themes reachable from both entry edges.
- trialContentConfigIdTest: passed (all 36 new spawner configs
  resolve).
- graphSolvabilityTest: 0 unresolved transitions, 0 inaccessible
  exits, 0 fallback cells for standard tier sweeps.
- runGameTest: 61 tests passed. Live load confirmed 11 themes (0
  rejected), 11 adventure nodes (0 rejected), 40 core loot tables.
- build: BUILD SUCCESSFUL, full suite green.
## 41. M74 acceptance: six situation rooms, lower stories

M74 adds six situation rooms using private lower stories rather than a new
layout engine. The automated evidence is in `SITUATIONS_AUDIT.md` section 6;
this section is the live-only half the automated tests cannot reach.

### 41.1 Sump, by hand

The gametest proves the return-path validator accepts a staircase. The
live check is the player actually swimming in the sump, redirecting the
current, and climbing the dry stair.

1. Enter a Sump room. **Expected:** a shaft drops you into flooded lower
   story. Water current pushes you away from the reward chest.
2. Place loose blocks to redirect the current. **Expected:** the flow
   bends and you can swim to the chest.
3. Climb the dry staircase. **Expected:** you reach the upper floor
   without a tool.

### 41.2 Ropewalk, by hand

1. Enter a Ropewalk room. **Expected:** a plank bridge crosses a chasm.
2. Cross the bridge. **Expected:** you reach the far side.
3. Alternatively, drop into the chasm. **Expected:** you can bridge the
   gap with loose blocks and climb the far side.

### 41.3 Sorting Floor, by hand

1. Enter a Sorting Floor room. **Expected:** a water channel splits, an
   iron door blocks the exit, a stick sits on a pedestal.
2. Pick up the stick, drop it in the water, and place a block to divert
   the flow south. **Expected:** the item reaches the filter hopper and
   the door opens.
3. Walk through the door. **Expected:** the item is in the return chest
   past the door.

### 41.4 Sensor Gallery, by hand

1. Enter a Sensor Gallery room. **Expected:** three sculk sensors span
   the room, zombies patrol, an iron door blocks the exit.
2. Throw snowballs near the sensors. **Expected:** the sensors activate
   and the door opens.
3. Alternatively, wait for zombies to wander onto the sensors. **Expected:
   the door opens from mob footsteps.

### 41.5 Kennel Crossing, by hand

1. Enter a Kennel Crossing room. **Expected:** wolves behind a fence
   gate, zombies on the far side, a low cobblestone wall along the south
   side.
2. Open the fence gate. **Expected:** wolves attack the zombies.
3. Alternatively, jump over the cobblestone wall and take the bypass.
   **Expected:** you reach the exit without releasing the wolves.

### 41.6 Blaze Cellar, by hand

1. Enter a Blaze Cellar room. **Expected:** a shaft drops you into a
   lower cellar with a blaze spawner, a chest in the corner, a pot of
   snowballs on the upper floor.
2. Use snowballs or water against the blaze. **Expected:** the blaze
   takes damage and dies.
3. Loot the chest and climb the staircase. **Expected:** you reach the
   upper floor without a tool.

### Headless verification (passed)

- graphSolvabilityTest: passed (0 unresolved, 0 inaccessible exits, 0
  fallback cells for standard tier sweeps).
- dungeonRoomMetaTest: passed.
- packValidationTest: passed (no findings).
- runGameTest: 61 tests passed. Live load confirmed 61 rooms (0
  rejected), 6 anomaly rooms, 11 themes, 40 core loot tables.
- plansurvey 1000: 1000 succeeded, 0 failed.
- 15 admin builds across seeds 1 to 500: all succeeded, zero
  return-path failures.
- build: BUILD SUCCESSFUL, full suite green.

## 39. M78: the Endless Mine

Automated tests cover the policy helpers, the recipe flag, the codec round
trip and the bounded-memory load path. The following require a live client
session.

### 39.1 Voluntary cash-out

- Hold a keystone at level 5 or higher and apply the Mine recipe at the Cube
  with a raw iron ingot in the off-hand. Confirm the Mine start message names
  the risk (no final floor, previous floors close) and the cash-out.
- Commit a door and descend. Clear the floor and reach the lodestone pad.
- Confirm the checkpoint message names the mine floor number and the loot
  tier, and offers `/dungeon cashout`.
- Run `/dungeon cashout` from the staging room. Confirm the party returns to
  the room with the banked haul, no teleport, no sound, no lore on the
  successful path.
- Run `/dungeon memento` and confirm the book shows "Mine depth: N" with the
  floor count reached.
- Run `/dungeon cashout` outside a Mine and confirm the refusal message.
- Run `/dungeon cashout` while a floor is active (not in the staging room)
  and confirm the between-floors refusal.

### 39.2 Long-run constant residency

- Chain at least ten Mine floors by walking a door at each checkpoint without
  cashing out.
- Confirm the loot tier escalates every three floors and caps at tier 3.
- Confirm the forced-chunk count stays bounded (only the current floor plus
  the transition cell), not growing per floor.
- Confirm the heap does not grow per floor.
- Confirm a previous floor's cells are gone (walk back is impossible; the old
  staging room is a liminal cell).

### 39.3 Mine does not raise the power ceiling

- Compare a deep Mine cash-out (many floors, high omen sum) against an
  ordinary safe visit. Confirm the deep cash-out yields no keystone level up
  (high omen band), so the Mine is not a better keystone route than the
  ordinary loop.
- Confirm a shallow Mine cash-out (one to two floors, low omen) behaves like
  an ordinary safe visit (level up possible).

## 42. 2026-09 audit, wave 1

In-client checks for the wave 1 fixes in `docs/AUDIT_2026-09.md`. The
scenario gametests (`FloorIntervalGameTest`) cannot reach the real dungeon
dimension, so the landing spots, stamps and pad contacts below are live only.

- B1: finish two safe visits in a row taking about the same omen per floor; confirm both visits pay the same chest count and keystone change.
- B2: in a two-player party, let the guest reach the pad first and the owner second; confirm both get the completion message and chime, and both can run `/dungeon memento` after the safe visit.
- B3a: in a staging room between floors, run `/dungeon exit`, then `/dungeon`; confirm you land in the staging room (not the cleared floor's entrance), no keystone is spent and the doors still work.
- B3b: disconnect in a staging room between floors and log back in within ten minutes; confirm you are back in the staging room, your inventory did not swap, and no "closed" message appears.
- B3b: as a guest, disconnect mid-run and rejoin while the run is still open; confirm the message says you were returned, not that the dungeon closed.
- B5: stand on a lodestone you placed in your safe room, then one in the staging room; confirm nothing happens. Confirm an admin untimed run and an admin build room still exit from their dungeon pad.
- B6: put a painting, an item frame, an armour stand and a sitting tamed wolf in your room, finish an interval and come home; confirm each is back exactly once. Change the room's shell and confirm nothing duplicates.
- R1: (operator) make `world/data/pocketdungeons/rooms` read-only and pull the lever; confirm the refusal, the room still standing, no fuel spent and the preview still open.
- R3: if a safe return ever logs "Safe return for slot N failed", confirm the lever works again and the keystone did not level twice.
- R4: in a party, take the safe door while the guest stands on an old floor; confirm the floor is not cleared under them and they are moved into the room after about 30 seconds.
- R5: not stageable without a broken template; covered by code review.
- B8: check the Inspect Keystone screen on an unaffixed keystone, the door screen during a run, the Start Dungeon tooltip, the gamble task on the tracker and an admin untimed run's entry message; confirm no clock or "Kadala" wording remains.

## 43. 2026-09 audit, wave 2a: the omen bar

In-client checks for the omen bar, the omen cues and the door screen
additions. `OmenBarTextTest` covers the wording and the band arithmetic and
`FloorIntervalGameTest.omenBarFollowsThePhase` covers when the bar shows; who
sees it, and what it looks like, are live only.

- Bar at home: stand in the safe room and the staging room before choosing a door; confirm no boss bar. Preview a door from home; still none.
- Bar during a floor: pull the lever; within a second a green bar reads `Omen 0/4 | 3 chests, +1 level | Spawners 0/N, need M`. Clear a trial spawner and confirm the count moves on the bar and no "Trial spawner cleared" line appears in chat. The per-cell chime still plays when a cell's last spawner goes quiet.
- Colour and fill: take omen (linger in an unsolved cell, trip sensors, drink the bargain) and watch the fill climb toward the next band; confirm it turns yellow at the mid band and red at the high band, matching the chest count the floor then pays.
- Omen cues: linger in an unsolved cell past 150 seconds; confirm a purple action bar line ("The walls notice you lingering. Omen 1/4.") and a low note, and that a second line for dwell does not follow within 30 seconds. Set off a shrieker, a sensor room and the bargain; confirm each has its own line and sound.
- Between floors: on the pad, confirm the completion line names the band ("The omen sits uneasy: 2 chests, +1 level so far.") and the bar changes to `Floor 1 of 3 cleared | ...`. On the last floor of the interval the line ends without "so far". Take the safe door and confirm the bar is gone once home.
- Party: a second member sees the same bar; one who leaves with `/dungeon exit` loses it at once, and one who comes back (re-entry or invite) gets it back within a second. A member who disconnects and rejoins between floors sees it again.
- Mine: in an Endless Mine interval the bar reads `Mine floor N cleared` between floors, and the checkpoint line carries the omen verdict.
- Door screen: select a door; the first line reads `KEYSTONE N | FLOOR 1 OF 3` (the floor number advances on later checkpoints). Select a Greater door and confirm a `Fuel 3 of your X` line, red when the engine holds too little.
- Teardown: with the bar showing, have an operator purge the slot; confirm the bar disappears for everyone.
- Config: a `pocketdungeons.json` that still sets `timerBaseSeconds`, `door1TimerSeconds` or the other retired clock keys loads without error, logs one line naming them, and `/dungeon quit` on a floor still costs `timedOutDepletion` levels.

## 44. 2026-09 audit, wave 2b: bank anywhere, per-floor banking, reconnect grace, zone rules

In-client checks for wave 2b. `IntervalBankingTest`, `ZoneRulesTest` and
`ConfigSaveTest` cover the arithmetic, the rules block and the config round
trip; `BankAnywhereGameTest` drives the settlement, the checkpoint exit and
the owner's grace against records it builds by hand, because the gametest
server has no dungeon dimension. Everything physical below is live only.
Section 43's bar wording is superseded: the bar now reads `3 chests, key
climbs` or `1 chest, key stalls`, never a level count, and every verdict ends
"so far".

- HOME control: clear floor 1 and walk into the new staging room. Left of the three doors stand a second lever with a glowing `HOME` sign and a 3x3 black screen with an unlit copper bulb above it. The screen reads `HOME`, `Banks +N levels, M/3 kept` (or `Banks no levels`) and `C chests, calm` (uneasy, dire), in the owner's numbers. Try to break the lever, sign, screen and bulb; all refuse.
- Bank after one floor: take a Greater door, clear it, pull HOME. The party walks home through the opened doorway with no teleport; the selector doors, bulbs and HOME control are gone from that doorway; chat says `The interval banks: +1 level, 0 of 3 toward the next, 3 chests (calm).`; the key is one level up. Repeat with the free door: `+0 levels, 1 of 3 toward the next`, key unchanged; two more single free floors banked separately land the level on the third.
- Keep going: after floor 3 the bulb lights, the screen title turns green `TIME TO GO HOME` and the completion line says it is a good time to go home. Nothing forces it: pick a door. Floor 4's door screen reads `FLOOR 4, DEEP`; on commit the omen bar starts at `Omen 1/4` with a purple line and a low note ("This deep, the dungeon is already watching."). Clear floor 4; one extra chest stands stacked on the first chest spot (depth bonus) and both open. Bank: four Greater floors bank +4.
- Door screen: select each door and confirm the aqua line `+N toward your key, +S so far`, where S is the steps of the floors cleared this interval.
- Commit clears the control: pull the commit lever from a cleared staging room; the HOME lever, sign, screen and bulb disappear with the selector doors.
- Preview then HOME: select a door (the preview window opens), then pull HOME without committing; the preview closes, an armed Cube recipe's catalyst comes back, and the party goes home.
- Non-owner: a guest pulling HOME gets `Only the party leader can take the party home.`, a refusal note, and the lever does not flip.
- `/dungeon cashout`: between floors in an ordinary dungeon, it does exactly what HOME does. Mid-floor it says the way home opens between floors. In an Endless Mine interval it also names the depth (`You leave the Mine with N floors banked.`).
- Checkpoint exit: between floors, run `/dungeon exit` (or the lodestone's Leave) as the owner with a calm interval; chat says `You leave at the checkpoint, and the interval banks one band worse:` with the uneasy numbers, every member present gets their own line, and the run closes. With an uneasy interval, leaving banks no levels and keeps the carry. Mid-floor, `/dungeon exit` still leaves the run open for free re-entry and `/dungeon quit` still costs `timedOutDepletion`.
- Party banking: with a guest at a lower level and some carried progress, bank; confirm the guest climbs from their own level and their own carry.
- Reconnect grace: as the owner with a guest inside, kill the client (no clean logout) mid-floor. The guest reads `Your party leader lost their connection. The run holds for 120 seconds...` and can keep clearing; doors, the commit lever and HOME refuse them. Log back in within two minutes: you are back in the run, the guest reads `<name> is back. The run goes on.` Repeat and stay out past two minutes: at a checkpoint the guest banks one band worse and the run closes; mid-floor it just closes. A clean `/dungeon exit` by the owner mid-floor with a guest inside still ends the run at once.
- Bounties: bank after six floors in one interval and confirm Deep Diver moves. Clear every spawner on floors 1 and 2 but not floor 3, bank, and confirm Tidy does not move while Clear the Halls counts all three floors' spawners. Banking a single calm floor moves neither Speedrunner nor Explorer (both ask for three floors).
- Config round trip: set `floorsPerSafeVisit` to 4 and `ownerReconnectGraceSeconds` to 30 in `config/pocketdungeons.json`, restart twice; both values are still there and in effect (the bulb lights after floor 4). Delete one key and restart; it comes back at its default and the others are untouched.
- Endless Mine: open a Mine interval with the recipe; floors 3 and 6 pay loot tier +1 and +2 as before; the checkpoint line names the HOME lever, not `/dungeon cashout`.

## 45. 2026-09 audit, wave 2c: persistent dungeon inventory, kit granted once, kit top-up

In-client checks for wave 2c. `InventorySwapTest` covers the slot-for-slot
restore, the keystone in slot 0 and overflow kept in the record;
`KitTopUpTest` covers the top-up arithmetic and the room counter;
`CustodyGameTest` drives the swap, the migration grant and both journal
repairs with the nether standing in for the dungeon. Everything below needs
the real `pocketdungeons:void`.

- Kept in place: inside, put a pickaxe in hotbar slot 4, a helmet on, torches in the offhand, cobblestone in slot 20 and a stack on the cursor. Leave by the lodestone. Your survival inventory is exactly as it was, and no room chest gained anything. Re-enter: every item is back in its slot, the cursor stack in the first free main slot, a fresh keystone in slot 0.
- Keystone displacement: inside, move the keystone to slot 6 and put bread in slot 0. Leave and re-enter: one keystone, in slot 0; the bread in the first free main slot.
- Overflow: fill all 36 main slots inside and hold one more stack on the cursor, leave and re-enter. Chat: `1 stack does not fit in your pack. Nothing was dropped: make room, and it comes back on your next entry.` Nothing lies on the floor. Empty a slot, leave and re-enter: the stack is back.
- Untagged notice: mine a block inside and leave with it. Chat names how many stacks did not come from the run's own loot and says they stay in your dungeon pack; it is back on re-entry, and never in your survival inventory.
- Kit once: reset your key (`/dungeon resetkey`), enter, pick Mason at the bag chest: sixteen cobblestone, a pickaxe, torches and bread arrive. Put the whole kit in a room chest, leave, re-enter, repeat three times: no new kit ever appears, the chest holds one kit.
- Migration grant: on a world saved before this wave, a player who already had a bag enters once: chat says the bag is packed one last time and one kit arrives; a second entry grants nothing.
- Top-up, calm: as Mason, place eight cobblestone in a room of the floor and eat two bread, clear a calm floor, pull HOME. Chat: `The safe room restocks your kit: 8 cobblestone, 2 bread.` The new stacks carry the bag tag (they stack with the kit's).
- Top-up, stashed kit: before committing, put the whole kit in a room chest. Clear a calm floor and go home: `Your kit is whole. The safe room has nothing to add.` Put the pickaxe and cobblestone in a shulker box in the chest instead: same result.
- Top-up, laundering: place kit cobblestone, break it (it comes back untagged), go home calm: no cobblestone restocked.
- Top-up, bands: finish an uneasy interval missing eight cobblestone and the pickaxe: four cobblestone, no pickaxe. A dire interval: `The omen ran high. The safe room restocks nothing this visit.` Leave at a checkpoint from a calm interval: the restock is the uneasy one.
- Top-up, tools: break the Mason pickaxe completely, go home calm: `a fresh stone pickaxe` with its short durability. Keep a nearly broken one instead: nothing restocked, it is not repaired.
- Top-up, buckets: as Plumber, pour both buckets and go home calm: both empties are turned back into full buckets, no extra bucket appears. Leave one empty in a room chest first: that line is not restocked until the empty is carried.
- Zone scale: in a theme whose rules set `kit_top_up_scale` to 0.5, a calm visit restocks half the deficit, rounded down.
- Config: set `kitTopUpBandMid` to 1.0 with `kitTopUpBandLow` at 0.5 and restart: one error line, and all three fall back to 1.0, 0.5, 0.0.
- Journals: after any clean enter or leave, `world/data/pocketdungeons/journal` holds no `<uuid>.dat` or `<uuid>.leaving.dat` for the player.

## 46. Cell seams and teardown

In-client checks for the seam fixes. `SeamGameTest` stamps the real cells in
the gametest level and asserts every shell, seam, doorway and ticket through
lobby, cancelled preview, commit, floor advance, homecoming, cleanup, a second
commit and a purge. Everything below needs the real `pocketdungeons:void`; use
spectator mode (or dig one block into a wall in creative) to look at the
outside of a cell.

- Commit, safe side: in the lobby, commit a door. Turn round in the staging room: the wall where the room was is a whole stone brick wall with the doorway sealed, floor row and corners included, and there is bedrock behind it, never bare bedrock inside the room and never a hole.
- Commit, dungeon side: the staging room's selector wall is still stone brick after the commit, with the double doors in the doorway; it does not turn to bedrock.
- Cancelled preview: select a door, then select another (or `/dungeon quit` from the preview). The staging room's selector wall shows plain wall in the door slot, not glass, and spectating outside it shows bedrock, not the void.
- Preview window: while a preview is open, look through the glass into the entrance room. Any doorway it has onto the rest of the floor shows bedrock, not the void.
- Floor advance: clear a floor whose last rooms curl back beside the new staging room. The rooms beside the staging room keep their stone walls (no bedrock patch in them), and so does the staging room.
- Homecoming: pull HOME and walk through. The doorway between the staging room and the room is open on both walls, and after everyone has crossed and the old floor goes, spectate outside the room/staging seam: bedrock runs unbroken along both sides of the seam, including the column right at each corner of it.
- After the homecoming cleanup, spectate where the old floor and the old liminal staging rooms stood: nothing is left. `/forceload query` in the dungeon dimension lists only the room's and the staging room's chunks.
- Leave and come back: go home, leave, and re-enter. No old room, floor or staging room stands anywhere near the slot, and `/forceload query` lists only the two lobby chunks. Do the same with a homecoming still pending (leave before walking through), and again with `/dungeon admin purge` on it: either way the old floor and staging rooms are gone and their chunks released.
- Unclean shutdown: kill the server mid-floor, restart. The log names the slot being cleared, and `/forceload query` in the dungeon dimension is empty once it finishes, including cells that lay north or west of the slot origin.
- Room doorways: stand a room beside a staging room on its east or west side (a homecoming off a floor whose terminal faces that way), leave so the room is saved, and come back through a lobby: the room's east and west walls come back sealed, with no doorway onto bedrock.

## 47. Playtest fixes and balance (2026-09-27)

From `docs/playtests/2026-09-27-1.md`. Headless-verified: `./gradlew build` green (87 gametests), pack validation clean, and on the test server 60 built layouts at keystone 8 carried Elders Chamber gravel gates and Infested Wall gates (none had before). Everything below needs a player.

- Gates (PD-66): find Hold the Plate, Infested Wall, Elders Chamber and Don't Look in a run. Each has its gate on the exit side only: an iron door pair, infested stone bricks, a gravel wall, a lowered doorway top.
- Hold the Plate (PD-67): stand on the plate. The action bar counts down from 30 s; step off and it starts over. At zero the iron door opens and stays open. The room carries no hoppers, comparator or dust any more.
- Thicket (PD-68): cave spiders spawn from the spawner in the middle; the webs are about half as dense (a 3D checkerboard). Ice Run's spawner makes breezes.
- Durability (PD-69): kill a mob holding an axe or sword in a dungeon and pick it up: its durability is the dungeon cap (an iron sword shows 64 max). A chest sword or helmet shows the same. A kit pickaxe stays at its own lower value.
- Levers (PD-70): over several floors the GO HOME lever and its screen are always left of the doors and DESCEND always right, whichever wall the doors are on. Both levers work; neither can be broken.
- Spyglass (PD-71): a non-operator holding the kit spyglass can open chests and pull levers. An operator gets coordinates; crouching skips them.
- Ledge Archers (PD-72): skeletons stay on the two ledges (the ends are barred) and never on the floor or on top of the spawner. The key or emeralds land on the floor under the spawner.
- Words: the HOME sign reads GO HOME. The go-home screen reads like `GOING HOME PAYS / 3 reward chests / Key 1/3 to a level / Kit refilled`. A floor clear shows a big "Floor 2 of 3 cleared" title with "GO HOME or DESCEND" under it. During a floor the bar reads `Spawners 2/3 | Omen 1/4, 3 chests`.
- Notices: going home prints the room line ("Home is through the open door: your own room..."). Leaving the dungeon with items prints that the dungeon pack stays behind; entering with survival gear prints that it is stored. A kit restock names where the items went (in your pack, or waiting for the next descent) and flashes "Kit restocked" on the action bar.
- Loot feel over three runs of tier 1: logs show up in roughly one chest in six or seven, bones rarely, emeralds rarely, no armour trims; swords and armour pieces noticeably more often.

## 48. Lemon steps 1 and 2: the playtest journal, context, Lemon's body and driver

From `docs/LEMON_SPEC.md` (steps 1 and 2). Headless: the journal line format, the context snapshot builder and the chat prefix rule are pure tests; Lemon's spawn, despawn, invulnerability, item pickup, room capture and logout are gametests. Everything below needs a player (and for privacy, two).

- Journal: play a floor and go home. `run/world/pocketdungeons/playtest/<today>/<your uuid>.jsonl` has one JSON line per event: `session_join`, `enter_dungeon` (via `command`), `bag_chosen` on a first bag, `door_preview` per door looked at, `door_commit` with the floor's room ids, a `room_entered` for each room the first time anyone steps in, `omen_rise` with a source and room, `floor_complete` with sensible seconds, spawners and chests, then `bank` (trigger `home_lever`) and `kit_topup`. Quit and rejoin: `session_leave` then `session_join`; logging out inside adds `leave_dungeon` reason `disconnect`, and logging back in inside adds `enter_dungeon` via `rejoin`.
- Journal edge cases: die in a room (`rescue` with the damage type and room); `/dungeon quit` (`quit_floor`); `/dungeon exit` between floors (`bank` trigger `checkpoint_exit`, `leave_dungeon` reason `checkpoint_exit`); `/dungeon report the door stuck` (`report` with your position, room and the last ten events, and "Noted. Thank you." in chat).
- Context: `server context <you>` prints one line of JSON. Stand in different rooms: `room` and `room_cell` follow you, `staging_room` in the staging room, `safe_room` at home, `preview` if you walk into a previewed entrance. `rooms` lists every room of the floor with `entered` turning true as you go, spawner counts that tick up as you clear them, and `locked` true on a locked room until it opens.
- Lemon appears: `server lemon say <you> Hello there.` A small allay puffs into view near your right shoulder with a chirp, glowing yellow while the bubble is up, the line in a bubble beside it and in chat after a yellow bold `Lemon:`. It follows you smoothly within about two blocks, faces you, moves to the other shoulder or above your head in a tight corridor, never blocks a doorway (walk through it) and never sits in the middle of your view.
- Long lines: `server lemon say <you>` with three or four sentences shows them as a sequence of short bubbles, each up for a few seconds; the chat line has the whole text once.
- Vanish: about 20 seconds after the last bubble, Lemon drifts up and vanishes with a soft poof. `server lemon ask <you> How was that floor?` keeps it lit and waiting up to two minutes; your next chat line (solo) is echoed as "You to Lemon: ..." and `server chat` shows `lemon answer`.
- Solo chat: with nobody else online, anything you type goes to Lemon, is not broadcast, and without LLM mode gets "I do not know that one yet." `server chat` shows `lemon ask` and `lemon says`. `/lemon what is omen` works the same; bare `/lemon` gets "Yes?".
- LLM mode: `server lemon mode <you> llm`, then ask Lemon something. Lemon appears lit and waits; answer with `server lemon say <you> ...` within 45 seconds and the journal's `lemon_ask` says `answered_by: llm`. Leave one unanswered: after 45 seconds Lemon gives the honest line itself (`answered_by: none`, `lemon unanswered` in `server chat`). Five minutes after the last `mode llm`, `server chat` shows `lemon mode <you> guide (lapsed)`.
- Fights: get a mob to target you, then `server lemon say <you> hi`: the reply says "Held", and Lemon speaks once the fight has been over a few seconds. Ask Lemon something mid-fight: it answers at once.
- `/lemon quiet`: an unprompted `server lemon say` is not shown (the reply says so), but asking Lemon still gets an answer. `/lemon on` restores it. `server lemon quiet <you>` makes a visible Lemon vanish at once.
- Privacy (two players): each sees their own Lemon and bubble only, never the other's, however close they stand; in a party, only lines starting with "Lemon" (any case, comma or colon optional) go to Lemon, and the rest is ordinary chat everyone sees. "lemonade" stays ordinary chat.
- Leftovers: with Lemon out, go home (the room is saved) and come back: no allay or bubble in the room. Clear a floor with Lemon out: it survives the floor advance and the homecoming cleanup, and `/dungeon admin diagnostics` entity counts drop back after it vanishes. Log out, change dimension, die: Lemon is gone each time and comes back only when it next speaks. Restart the server: no allay is left anywhere.
- Mobs: mobs never attack Lemon, it takes no damage from anything (sweep it with a sword, lava, a creeper), cannot be leashed or handed an item, and never picks up drops. It does not set off sculk sensors in an omen room.

## 49. The salvage bench (2026-09-29)

From `docs/reference/SALVAGE_PROPOSAL.md`. Headless: `SalvageMathTest` pins the rates and that scrap back into the gamble never pays; `SalvageGameTest` pins what is taken, what is paid and what stays. Everything below needs a player.

- Stations: the lodestone menu's Stations picker shows the Salvage Bench (a grindstone) first, open at level 1. Take it and place it in the safe room.
- Grindstone still works: right-click it with an empty hand, or while sneaking: the vanilla grindstone opens.
- Open: right-click it holding a bow. The Salvage Bench screen opens with the bow already in the first slot and your hand empty. The grindstone button underneath reads "Mob gear: 1 for 1 XP" (more for an enchanted bow).
- Mixed load: drop in a tier-2 dungeon sword, three trial keys, an ominous key and a stack of dirt. The summary reads gear 2 emeralds, keys 3, ominous keys 3, and "Stays: 64 (Dirt: not gear or a vault key)". Click Salvage: 8 emeralds and the XP arrive, the grindstone sound plays, chat says "Salvaged for 8 emeralds, N XP.", only the dirt is left in the screen.
- Refusals: imbued gear, trimmed armour, a bag item and your keystone all stay in the screen with their reason on the summary.
- Closing: close the screen with items in it; they come back to your inventory. Log out with the screen open; on return, the items are in your inventory or on the floor where you stood, never gone.
- Journal: each Salvage click writes one `salvage` line with the counts and the payout.
- Keys to fuel (operator): set `salvageKeysPerFuel` to 3 and reload. Five keys show "Vault keys: 3 for 1 fuel (2 short of the next, they stay)"; after Salvage two keys remain and one fuel arrives.

## 50. Ordeals (2026-09-30)

From `docs/reference/ORDEALS.md`. Headless: `OrdealGameTest` pins the lever (resolves once, stays down, lights its lamp), the protected fixtures and the doused spawner; `HandlerGameTest` keeps the lava and bridge ticks. Use `/dungeon admin bias <room> 20` to roll each room. Everything below needs a player.

- Rising Lava: entering, lava creeps in from both side walls a row a second. A lever with a lamp above it stands beside the exit doorway on the far side, whichever way the room is turned. Pull it: the lava drains, a chime, "The lava drains away." on the action bar, the lamp lights. Pull again: "Already done. The lever stays down."
- Collapsing Bridge: the lever and lamp are beside the exit. Cross, pull it: "The bridge locks in place."; walk back over every segment, none drops.
- Thicket: a lever on the spawner's east face with a lamp above. Reach it through the webs and pull: smoke, an extinguish sound, "The spawner goes dark.", no more cave spiders. A torch on the spawner no longer does anything.
- Ice Run: packed ice hops rise from each doorway to a snow platform in the middle, three blocks up, with the lever on top and its lamp as the block under it. Strays spawn on the floor and shoot at you. Miss a hop: you land on the floor and cannot reach the higher hops from there; walk back to the first. Pillar three blocks from the floor instead: you can step onto the platform. Pull the lever: the spawner goes dark and the platform's lamp lights.
- Hold the Plate: hold 30 s through the waves: "The door grinds open." with the Ordeal chime; no lever.
- Fixtures: try to break any Ordeal lever or lamp, by hand, with a pickaxe, with TNT: none of them break.
- Journal: each resolution writes an `ordeal` line with the room and the seconds since it armed.
