# Pocket Dungeons — Client Test Checklist

Mod id: `pocketdungeons`  
Entry command: `/dungeon`

## Setup

- Load the mod on a server or integrated client.
- Ensure the `pocketdungeons:void` dimension data pack is enabled.
- Have a second player available for party tests.

## 1. Basic entry and exit

1. Stand in a known overworld location and note your coordinates.
2. Run `/dungeon`.
3. **Expected:** You are teleported into a 4-room dungeon: skeleton room, zombie room, chest room with one diamond, and exit room.
4. Leave with `/dungeon exit` or by standing on the lodestone in the exit room.
5. **Expected:** You return to the exact overworld coordinates where you started.

## 2. Party invite and join

1. Player A runs `/dungeon`.
2. Player A runs `/dungeon invite <playerB>`.
3. Player B runs `/dungeon join <playerA>` before the invitation expires.
4. **Expected:** Player B is placed in the same instance and each player has their own distinct starting position.
5. Both players leave the dungeon.
6. **Expected:** Each is restored to their own original starting position.

## 3. Death ejection

1. Enter `/dungeon`.
2. Let the skeleton or zombie deal a killing blow.
3. **Expected:** No death screen, no inventory drops. You are ejected to your start position at full health.

## 4. Disconnect while inside

1. Two players enter the same dungeon.
2. One player disconnects while still inside.
3. **Expected:** The remaining player(s) are not kicked out and can continue or leave normally.

## 5. Dimension name

1. While inside the dungeon, check the current dimension name.
2. **Expected:** The dimension is named `pocketdungeons:void`.

---

## M1 — template-based rooms

M1 replaced the hand-coded room geometry with real `.nbt` structure templates
placed through jigsaw doors. Gameplay should be indistinguishable from before
these changes — these steps exist to catch anything the headless
console-driven verification couldn't see, since that only checks specific
block coordinates and entity types, not what a player actually experiences
walking through.

### 6. Visual geometry parity

1. Run `/dungeon` and walk all four rooms end to end.
2. **Expected:** Floors, walls, and ceilings are solid and continuous — no
   gaps, no floating blocks, no misaligned seams where one room's wall meets
   the next room's wall.
3. Look closely at every doorway as you pass through it.
4. **Expected:** Each opening is clean — 2 blocks wide, 3 tall, centered on
   the wall. No partial blocks, no stray blocks inside the opening.

### 7. No visible jigsaw blocks

1. Walk through all four rooms, including looking up at the ceiling and down
   at doorway thresholds.
2. **Expected:** No `minecraft:jigsaw` blocks visible anywhere. (A full-volume
   scan already confirmed zero remain programmatically — this step is a
   sanity check that nothing looks *wrong* even if the block itself is gone,
   e.g. a visibly different texture where a door used to be carved directly.)

### 8. Chest loot, played not queried

1. Walk to the loot vault room and open the chest normally (not via `/data`).
2. **Expected:** Exactly one diamond, and nothing else. Close and reopen the
   chest.
3. **Expected:** The diamond is still there (chests don't reroll their
   contents once opened) — this is really confirming the loot table
   resolved correctly on first open, not a repeat-roll test.

### 9. Mob behavior sanity

1. Let the skeleton in room 1 notice and engage you.
2. **Expected:** It behaves like a normal skeleton — strafes, shoots arrows,
   no frozen/stuck/silent behavior. (Mobs are now captured into the template
   and placed via structure placement rather than a direct spawn call, which
   is worth confirming didn't leave them in some half-initialized state.)
3. Let the zombie in room 2 notice and engage you.
4. **Expected:** Normal zombie chase/attack behavior.
5. Kill both (or let the death-rescue trigger — see test 3 above) and confirm
   no leftover NBT weirdness (e.g. `/data get entity` shows a normal mob, not
   something carrying stray template-authoring data like a `data` tag).

### 10. Repeatability across runs

1. Run `/dungeon`, walk through, exit. Run `/dungeon` again.
2. **Expected:** The second run looks identical to the first — same room
   order, same door positions, same chest contents. (Placement uses a fixed
   seed/no rotation, so this should be exact, not just similar.)

### 11. `/dungeon admin gentemplates` from a real client

1. As an op, run `/dungeon admin gentemplates`.
2. **Expected:** Readable chat confirmation, no errors. Run it again a
   second time back to back.
3. **Expected:** No errors on repeat — regenerating the templates should be
   safe to do more than once (it isn't in the middle of a live `/dungeon`
   instance's use in this test, which is a separate, riskier scenario not
   currently expected to be safe — don't run `gentemplates` while a real
   dungeon instance is open until that's explicitly verified safe).

---

## M2 — room manifest

**Read this before testing:** M2 is a backend indexing tool with no player-
facing gameplay hook yet — nothing about `/dungeon` itself changes. These
commands are operator-only diagnostics; there is no "feature" here a normal
player would ever encounter. The point of testing this in a real client
(rather than only headlessly) is mainly to confirm the chat output is
actually readable and the commands behave safely around a live game session,
not to find new gameplay.

### 12. `/dungeon admin manifest reload`

1. As an op, run `/dungeon admin manifest reload`.
2. **Expected:** Chat shows `Loaded 4 room(s) into manifest.` with no
   rejections.

### 13. `/dungeon admin manifest list`

1. As an op, run `/dungeon admin manifest list`.
2. **Expected:** Four readable lines, one per room (`entrance_hall`,
   `encounter_zombie`, `loot_vault`, `exit_hall`), each showing a footprint,
   four door-mask rotations, and a role. Confirm it's legible in chat — long
   lines can get cut off or wrap awkwardly in the chat window in a way a
   console log never shows you.

### 14. Manifest reload doesn't disturb a live dungeon

1. Run `/dungeon` to open an instance and stay inside it.
2. From another op session (or op yourself briefly), run
   `/dungeon admin manifest reload`.
3. **Expected:** Nothing happens to your active instance — you're not
   teleported, the rooms around you don't change, `/dungeon exit` still
   works normally afterward. (The manifest reload touches the overworld's
   structure/resource managers, unrelated to a live instance in the void
   dimension, but this hasn't been exercised with a real player standing
   inside a dungeon while it runs.)

---

## U4 — the lodestone ritual

Every check here is a right-click, and nothing in a headless console session
ever right-clicks a block. None of this has been verified; the code was traced
and the API shapes were confirmed against the jar, which is not the same thing.

### 15. Ritual entry consumes exactly one key

1. Place a lodestone in the overworld. Hold a stack of plain echo shards
   (`/give @s echo_shard 5`), stand outside any dungeon, and right-click it.
2. **Expected:** the charge sound plays *at the lodestone and you hear it*, a
   dungeon opens exactly as `/dungeon` would, and you come back with **4**
   shards, not 3 and not 5.

### 16. A tagged shard is not a key

1. `/give @s echo_shard[custom_data={kamutotems:{boss_stone:1}}]`, then
   right-click the lodestone holding it.
2. **Expected:** nothing consumed, no dungeon, and with kamutotems installed the
   boss stone behaves exactly as it normally does (this is the whole point of
   passing rather than failing — kamutotems' own handler still gets its turn).
3. Repeat with a boss stone that actually dropped from a tier-2 dungeon chest,
   not a hand-minted one.

### 17. The exit pad cannot eat a key

1. Inside a dungeon, walk to the exit pad and right-click the lodestone you are
   standing on *before* the tick watcher pulls you out (you have under a second,
   so try from the side rather than on top of it).
2. **Expected:** no key consumed, no second dungeon, normal exit.

### 18. Vanilla lodestone behaviour survives

1. Right-click the lodestone with an empty hand, with a wrong item, and with a
   compass.
2. **Expected:** nothing from this mod fires, and the compass still binds to the
   lodestone.
3. Sneak-right-click while holding echo shards. **Expected:** no ritual — sneak
   is the deliberate opt-out.

### 19. The kill switch and the failure path

1. Set `ritualEnabled: false`, restart, right-click with shards in hand.
   **Expected:** nothing happens, `/dungeon` still works.
2. Set `ritualKeyItem` to a typo, restart. **Expected:** one error line at boot
   naming the bad id, and the ritual is inert.
3. Break the dungeon dimension so `Instances.enter` fails, then run the ritual.
   **Expected:** the "dungeon failed to build" message and **the key is still in
   your hand** — this is the check that matters most, because eating a real item
   on a failed entry is a real loss.

---

## U5 — the dungeon log and completion payout

The stateful half (streaks, persistence, the log's numbers) was verified
headlessly through `/dungeon admin log record`. Everything below needs a player
actually standing in a dungeon and is unverified.

### 20. A completed run pays, once

1. Enter, walk to the exit pad, let the watcher pull you out.
2. **Expected:** you land at your return point with the payout in your
   inventory, and one gold line: `You escape with the loot. Run #1 - streak 1 -
   6 emeralds.` (the count scales with tier; a tier-1 run at the defaults pays
   6).
3. `/dungeon log` afterwards agrees: 1 run, streak 1.
4. Get a friend to pull you back into the *same* instance and step on the pad
   again. **Expected:** you are ejected with the plain "you leave the dungeon
   behind" message, **no second payout**, and `/dungeon log` still says 1 run.

### 21. A retreat and a death do not pay

1. Enter, and from mid-dungeon run `/dungeon exit`. **Expected:** no payout, no
   completion message, `/dungeon log` unchanged.
2. Enter and get killed inside. **Expected:** the M0 death-rescue behaviour is
   completely unchanged — ejected, inventory intact, no death screen — **and no
   payout**, and `/dungeon log` unchanged.

### 22. A full inventory does not eat the reward

1. Fill every inventory slot (including the offhand and armour) with junk that
   will not stack with the payout item, then complete a run.
2. **Expected:** the payout drops at your feet **in the overworld**, at your
   return point — never at the exit pad, which teardown is already clearing.
   Count the items on the ground: a partial fit must drop the exact remainder,
   not a whole stack and not nothing.

### 23. A party is paid per player

1. Two players enter together, both walk out on the pad.
2. **Expected:** both are paid, each exactly once, each with their own run
   number and streak.
3. One member leaves early via `/dungeon exit`: they are not paid, the instance
   survives, the other completes and is paid.

### 24. The streak across two real days

1. Complete a run today. Note `/dungeon log`.
2. Complete another run tomorrow. **Expected:** streak 2, and the completion
   line's payout is 10% larger than yesterday's for the same tier.
3. Skip a day, then complete. **Expected:** streak back to 1.

### 25. `payoutCommand`, if an operator uses one

1. Set `payoutCommand` to something observable, e.g.
   `say %player% earned %amount%`, restart, complete a run.
2. **Expected:** the substituted command runs from the console's own permission
   level, and the item payout still happens alongside it.

---

## Test Log

**2026-08-18**

- Build: passing (`.\gradlew.bat test`)
- Unit test: `DoorMaskTest` passing
- Dev server: starts successfully on `localhost:25565`; basic `/dungeon` entry and admin commands functional.
- Pending: client-side disconnect/teleport verification on a live server.
