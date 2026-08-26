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
