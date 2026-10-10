# Live checks owed

Fixes that pass their tests but still need one look in real play. Whoever runs
the next live session (the Lemon agent, or the `/playtest` interviewer) treats
the `owed` rows as goals for that session, alongside the research questions in
`AGENDA.md`. The knowledge pack (`tools/lemon/build-pack.mjs`) lists every
`owed` row, so an agent on the MCP server sees them without reading the repo.

How to work them:

- **Never spend the player's time on a check they did not agree to.** Ask once,
  at a natural break, whether they mind trying something for a minute. A no
  is fine; the check stays owed.
- **Never change the player's game state without asking** (inventory, keystone,
  config). Room bias is allowed: it only steers what the next door plans, and
  the bias command announces itself in chat.
- **Discovery checks are watch only.** Do not tell the player the answer, or
  the check is spoiled for good. Note what they do.
- Record each attempt in the session notes with the time, what you saw and the
  evidence (log line, `context` field, journal event), then update the row
  here: `passed <date> (<notes file>)`, `failed <date>: <one line>`, or leave it
  `owed` with a line saying what blocked it. A failure also goes in
  `docs/reference/BUGS.md` under the bug's id.

Commands below are `server cmd "<command>"` on the CLI, or the admin console
tool on the MCP server (`mcp.mjs --admin`).

---

## L1. PD-78 and PD-97: two-story rooms build

- **Status:** owed
- **Fixed:** 2026-09-30 (blaze_cellar) and 2026-10-01 (sump): both had a solid
  pillar where the climb back up should be; both now have a ladder column. A
  gametest now checks every multi-story room at every rotation.
- **Do:** `dungeon admin bias blaze_cellar 20`, let the player open a door and
  commit. Then `dungeon admin bias clear` and the same for `sump`. Ask the player
  first; it costs them nothing but a door.
- **Pass:** the door builds (no "The dungeon failed to build"), `context` shows
  the room on the floor, and the player can climb the ladder from the lower
  story back up. Ask them to try the ladder if they go down.
- **Fail signs:** `IllegalStateException ... no climbable return path` in the
  log, or the player stuck below.

## L2. PD-93: an anomaly room builds

- **Status:** passed 2026-10-04 (`2026-10-04-1.md`): `anomaly_store_straight` stood on prismarine floor 3, was entered at 04:25 and built; no manifest error.
- **Fixed:** 2026-10-01: the stamper read anomaly rooms from the wrong manifest,
  so any door that rolled one failed to build.
- **Do:** nothing to steer; about 8 percent of themed plans roll one. Watch
  `context` for a room named `pocketdungeons:anomaly_...` on a preview or floor.
- **Pass:** a door whose plan holds an anomaly room commits and builds.
- **Fail signs:** `manifest has no room named pocketdungeons:anomaly_` in the log.
- **If it never comes up:** say so in the notes; raising `anomalyRoomChance` in
  the server config is the owner's call, not yours.

## L3. PD-94: ominous loot without Boss Stones

- **Status:** passed 2026-10-01 (`2026-10-01-2.md`): after a feral+ominous basalt_foundry run with 3 chests, `context full` shows no Boss Stone echo shard in the pack. Caveat: unproven whether every ominous chest was opened.
- **Fixed:** 2026-10-01: the Kamu Totems integration was removed; ominous chests
  and vaults no longer drop Boss Stones.
- **Do:** watch an ominous floor (the door shows the Ominous affix).
- **Pass:** after the player opens ominous chests and vaults, `context` shows no
  echo shard named "Boss Stone" that was not already in their pack before.
- **Fail signs:** a new Boss Stone in the inventory.

## L4. PD-95: fresh stackable loot merges

- **Status:** owed
- **Fixed:** 2026-10-01: stackable rewards no longer carry the bag tag; old
  tagged stacks lose it the next time the player enters.
- **Do:** watch. If the player mentions stacks that will not merge, check
  `context full=true` for the two stacks.
- **Pass:** iron ingots, food, arrows and similar from chests or vaults land in
  the same stack as the player's existing ones. Since 2026-10-02 there are no
  stack caps (loot uses vanilla sizes), so blocks, torches and arrows also merge
  with a plain stack of 64; a stack that will not merge is a failure, and an
  old capped stack in the pack should be back to its vanilla size on entry.
- **Fail signs:** a stackable item with `pocketdungeons:{bag:1}` custom data in
  `context`.

## L5. PD-96: the Salvage Bench is found without help

- **Status:** passed 2026-10-01 (`2026-10-01-2.md`): the player opened the bench on his own and immediately gave UI feedback (PD-101, right-click should not deposit the held item). Caveat: he already knew the feature existed, so stranger-first-time discovery is still unproven.
- **Fixed:** 2026-10-01: in the dungeon, any grindstone use that is not a sneak
  opens the Salvage Bench; outside the dungeon grindstones are vanilla.
- **Do:** nothing. Do not mention salvage or the grindstone. If the player asks
  where to spend surplus gear or keys, answer honestly and note that they had to
  ask.
- **Pass:** the player opens the bench on their own (a salvage journal event, or
  they mention it) and understands what it pays.
- **Also note:** whether they find the Disenchant button, and whether a
  grindstone outside the dungeon still behaves as vanilla if they use one.

## L6. PD-79: Lemon answers in time

- **Status:** partial again 2026-10-08 (`2026-10-08-1.md`): about 25 player asks, but five hit the 45 s fallback and journaled `llm_late` and one went fully unanswered (wait_s 117). Every miss was agent-side latency between watcher polls, not a server fault; `lemon_think` auto-hold works but cannot beat an agent that is between polls. Harness fix wanted: the watcher should auto-reply a hold line, not just think. Earlier: partial again 2026-10-07 (`2026-10-07-1.md`): most asks answered in seconds, but `lemon_reply` intermittently returned "No player was found" with the player online (PD-163), and one ask hit `lemon unanswered` over it; a second raced the 45 s fallback after slow investigation. Keep watching. Earlier: passed 2026-10-04 (`2026-10-04-1.md`): about 30 player asks, `wait` returned each in 3 to 13 s, no `lemon unanswered` for a player question. Keep watching one more session; earlier failure (PD-118) not seen.
- **Fixed:** 2026-10-01: `lemon think` now really holds the question (it used to
  be ignored, so the fallback fired at 45 seconds anyway), and `wait` no longer
  skips a log line caught mid-write. Most past timeouts were the agent
  investigating before answering: see `docs/LEMON_AGENT.md` section 4, step 4.
- **Do:** for every `lemon ask`, call `lemon reply` or `lemon think` first, then
  investigate. Write down the ask time, the time `wait` returned it and the
  reply time.
- **Pass:** no `lemon unanswered` line for a player question across a session.
- **Fail signs:** `lemon unanswered` in the events. Note whether `wait` returned
  the ask late (a harness bug) or you replied late.

## L7. PD-80: quiet waits report correctly

- **Status:** passed 2026-10-07 (`2026-10-07-1.md`): clean quiet waits on the remote Kinetic server too, no false `server is down`. Earlier: passed 2026-10-01 (`2026-10-01-2.md`): a full session on the new `lemonwatch.mjs` loop printed only clean `no new events (timeout)` lines while the server was up. Keep watching for one more session before retiring.
- **Fixed:** 2026-09-30; two clean quiet waits on 2026-10-01-1.
- **Do:** nothing extra.
- **Pass:** a quiet `wait` prints `no new events (timeout)` while the server is
  up. Record any `server is down (timeout)` with `server status` run right after.

## L8. Torches drop nothing; foundry chests carry glass bottles

- **Status:** owed
- **Changed:** 2026-10-01 (owner decision): in the dungeon a broken torch of
  any kind (wall, soul, copper; the player's own too) drops nothing, by hand or
  by its support breaking. Basalt Foundry chests now also hold glass bottles,
  so nether wart and magma cream from the same pool brew Fire Resistance.
- **Do:** watch. If the player breaks a torch, note whether they comment. On a
  basalt_foundry floor, note whether bottles turn up and whether they brew.
- **Pass:** no torch item appears after a torch breaks (`context` inventory
  count does not rise); a bottle shows up in foundry loot within a few chests.
- **2026-10-01-2.md:** bottles confirmed in foundry loot ("Yes, I got them");
  he asked for sand in the same pool. Torch breaking was not observed, so that
  half stays owed.
- **Ask after, not before:** how losing torches felt (fair cost, or annoying),
  and whether Fire Resistance changed how they approach the foundry.

## L9. 2026-10-01 fix batch

- **Status:** owed, 2026-10-04-1.md: kennel_crossing FAILED on an ender_archive floor, six grass blocks and no wolf (PD-145). blaze_cellar and the rest not observed. Earlier: owed (2026-10-02-1.md: fixes were in the running build but not specifically steered to; hold-the-plate worked well, no commit/build failures, but `kennel_crossing`, `blaze_cellar`, `rotation_lock` and the blacksmith duplication were not directly observed).
- **Fixed:** 2026-10-01: PD-98 (blaze_cellar spawner found; kennel pen is grass so wolves spawn), PD-99 (rotation_lock redstone faced the wrong way), PD-100 (sensor omen line held 8 s), PD-101 (bench open leaves the held item alone), PD-103 (blacksmith scan covers the staging room).
- **Do:** bias `kennel_crossing`, `blaze_cellar` and `rotation_lock` in turn (ask first). For the bench, right-click it holding a sword. Watch for a second blacksmith near the staging room.
- **Pass:** wolves appear inside the kennel pen and the gate lets them out; blaze_cellar shows a spawner counter; rotation_lock opens on frame position 8; the sensor line stays readable; the held item stays in hand; one blacksmith only.

## L10. 2026-10-02 changes: Lemon, echo shards, floor history

- **Status:** partial, passed 2026-10-02-1.md (keystone summon and echo-shard Greater doors), failed UI details.
- **2026-10-02-1.md:** keystone right-click summoned Lemon (`lemon summoned` event) and opened her menu. Echo shards were correctly subtracted on a Greater door. Floor history board is visible and read, but needs formatting changes: large "FLOOR HISTORY" heading, ~20% bigger text, aligned columns, date only, progress notation `1/3` yellow, `2/3` yellow, `3/3` green, `4+/3` orange, short affix nicknames only on affixes, not floor names. Lemon's journal item can be taken from the menu (PD-105).
- **Follow-up 2026-10-02:** the board formatting changes and PD-105 (Lemon's journal) were worked the same day; see L12 and L14. Status stays partial until they are seen in play.
- **Changed:** 2026-10-02. Right-clicking the keystone in the main hand
  summons Lemon (or moves her to a fresh spot); Lemon holds a journal, and
  right-clicking her opens the lodestone menu. Greater doors take echo shards
  from the pack at the commit lever (no engine bank); an old balance is handed
  back as shards. The engine bay is now the floor history board. The guided
  task line, the weekly bounties and the tracker screen are gone.
- **Do:** watch. If the player has never summoned Lemon, ask once at a break
  whether they have tried right-clicking their keystone (that one is not a
  discovery check). Note the board after a floor clear, a quit and a max-omen
  failure.
- **Pass:** a summon logs `lemon summoned`; the menu opens from Lemon; a
  Greater door with too few shards says "Not enough echo shards"; with enough,
  the shards leave the pack on the pull; the board lists the newest floor at
  the top with its level, affixes, time and how it ended (room and cause for a
  failure).
- **Ask after:** whether the board is readable from where they stand, and
  whether they read it at all.

## L11. 2026-10-02: rubble doorways and sealed two-story rooms

- **Status:** owed (not encountered in 2026-10-02-1.md; player did not pick the Sapper's Bag or roll a creeper kennel).
- **Changed:** 2026-10-02. With the Sapper's Bag, or after a creeper kennel, a
  door can be plugged with rubble and the three two-story rooms' way down is
  sealed with it. Any explosion within reach clears it and hurts nothing.
- **Do:** with consent, the player picks the Sapper's Bag; then
  `dungeon admin bias sump 20` (or `slime_pit`, `blaze_cellar`). Discovery is
  watch only: do not say "use TNT" unless asked.
- **Pass:** mining the rubble names what moves it; TNT or a creeper beside it
  clears it with no damage and no broken blocks; the floor can be cleared
  without opening a sealed room; once open, the ladder climbs back out.
- **Fail signs:** a door that never clears after a blast, a player or block
  hurt by the clearing blast, or a floor that will not complete while a seal
  stands.

## L12. 2026-10-02 fix batch (PD-105 to PD-112)

- **Status:** owed
- **Changed:** 2026-10-02: spawner mobs no longer carry trimmed vanilla armour and armour drops are rarer (PD-106); themeless rooms draw from narrow mob families, with a rare chaotic spawner (PD-107); the salvage bench says why it refuses gear (PD-108); spiders stuck high on a wall are set back on the floor (PD-110); the spawner gate says how many more to clear (PD-111); omen gains are audible and the sensor line plays a warden sound (PD-112). PD-105 (Lemon keeps her journal) and PD-109 (tripwire hall traps) could not be reproduced; see their BUGS entries.
- **Do:** watch. For PD-109 bias `tripwire_hall` (ask first) and look at the room before entering: six hooks, three strings across, six dispensers. For PD-105 right-click Lemon with an empty hand and note whether the book moves.
- **Pass:** a tier 3 room drops little or no trimmed armour; a themeless room spawns one family (undead, bones or spiders); the gate line names a count; the sculk line has a warden sound; no spider sits in a corner for long; the tripwire traps are present and fire; Lemon keeps the book.

## L13. 2026-10-02: economy changes (stack caps, echo shards, armour, fountain)

- **Status:** partial, 2026-10-07-1.md: another dead-end fountain use (food boon, journal `fountain` 00:47:25), unremarked on. 2026-10-02-2.md: a dead-end fountain was used once (food boon, `fountain` event at 20:42 local) and worked; `echo_shards` events seen with sources `floor`, `interval` and `ordeal`. No stack-merge complaint. Failed on design: the player does not want the `floor` source (0.5 per floor); he wants one shard per full interval plus Ordeal chance only. Not seen: armour drop volume, ender chest as obsidian, the Guard's Bag, fountain reuse.
- **Changed:** 2026-10-02: no stack caps; echo shards from every full interval, from floors (50 percent) and from Ordeals (25 percent); fewer armour drops; ender chests in loot are obsidian; dead-end fountains (15 percent); a Guard's Bag (sword, shield, bread).
- **Do:** watch. Count echo shards gained per interval from the `echo_shards` journal events. Ask the player whether shards now feel plentiful. If a dead end holds a fountain (a water cauldron on a chiseled block: quartz heals, sandstone feeds, deepslate cleanses omen) note whether they find and use it. Offer the Guard's Bag only if the player asks about kits.
- **Pass:** `echo_shards` events with sources `interval`, `floor` and `ordeal`; blocks, torches and arrows stack to 64; no ender chest in loot; the fountain empties after one use, gives its boon, and its pedestal turns to plain chiseled stone bricks.
- **Fail signs:** shards granted twice for one interval, a capped stack in the pack, a fountain that can be drunk twice (including after pouring a water bucket into it), a floor that never completes because of a fountain.

## L14. 2026-10-02: Silence, omen sound, floor-start title, floor history board

- **Status:** partial, 2026-10-02-2.md: floor-start title passed ("The name and affixes ... was good", wants a slightly longer delay between lines); the board passes on formatting but the rows should be about 20 percent bigger (PD-117). Silence ran last session, not this one (this session had Molten, Overclocked and Feral). Omen sound and the board's fit from the wall are still unconfirmed.
- **Changed:** 2026-10-02: Silenced runs charge 1 omen per 3 consumables used instead of blocking them; every omen gain makes a sound (at most once a second); committing a floor shows its name and then each affix with a thud; the floor history board has a large heading, bigger text, fixed-width columns (uniform font), date only, `1/3` style progress (yellow, yellow, green, orange past the interval) and short affix names (Cooked, Feral, Swarm, OC'd, Molten, Mute, Boom, Void, Loaded).
- **Do:** watch. On a Silenced floor note what the player does with food and potions. Ask after a floor start whether the title reads well, and whether the board is readable from where they stand. The board width depends on the uniform font and is untested in play: if rows run off the wall, lower `HISTORY_SCALE` in `DungeonScreen`.
- **Pass:** a Silenced consumable raises omen after the third use with the SILENCE line and a sound; the title shows the floor name then affixes one per beat; the board columns line up and fit the wall.
- **Fail signs:** rows wrapping or clipped, a title that never clears, an omen sound on every tick.
- **Short names (owner rule 2026-10-02):** contractions of at most five letters, only for names longer than five: Ck'd (Cooked), Swrm (Swarming), OC'd (Overclocked), Mltn (Molten), Slncd (Silenced), Xplsv (Explosive), Vd'd (Voided), Ld'd (Loaded). Feral is already short and keeps its name. Replace the list in the line above.

## L15. 2026-10-02: librarian lock in and run storage

- **Status:** owed, 2026-10-04-1.md: run storage persists across floor clears and two banks (27 books and 54 emeralds still there), the "wiped" claim at 04:16 did not reproduce; librarian, lectern and Lock in not seen. Earlier: owed, 2026-10-02-2.md: not settled. Discovery failed: the player asked how to make a lectern and did not know the station picker hands it out. He then rejected the "Lock in" idea ("there's no lock in"): he wants the librarian to sell a Mending book. Run storage and the librarian itself were not seen.
- **Changed:** 2026-10-02 (owner decisions): Mending no longer drops or rerolls onto gear. A lectern placed in the room spawns a librarian; right-clicking the librarian holding gear offers "Lock in" for 32 emeralds (`lockInEmeralds`), which adds Mending and a "Locked in" lore line. Locked gear is refused by the salvage bench and the reroll station. The gamble block no longer exists. Every staging room now has an ender chest set into the wall to the right of the selector doors (rebuilt with the room every interval); it, or any ender chest in the dungeon, opens Run Storage (27 private slots kept for the run) so it feels vanilla while gated behind the scenes. When the run closes the contents go into the dungeon pack, never the survival inventory, and a max-omen death rolls the storage back to the interval start like the pack; the bag chest at the safe room centre is now a waxed oxidized copper chest (swapped with the ender chest). The blacksmith is now the only way to gamble. The station picker lists Run Storage and the Lectern.
- **Do:** with consent, give the player a lectern and emeralds (or watch for them to take one from the station picker at keystone level 5). Watch whether they find the librarian, understand the price, and whether locked gear surprises them at the bench. For storage, watch whether they notice the chest in the staging room wall without being told, whether they use it, and whether the items are in the dungeon pack on the next entry after the run closes.
- **Pass:** a lectern spawns exactly one librarian; the trade preview shows Mending and the cost; the emeralds leave and the held piece keeps its enchantments plus Mending; the bench says "locked in, kept safe"; the staging room chest is there on every interval for every member; stored items are in the dungeon pack on the next entry and never in the survival inventory; no Mending in chest loot.
- **Fail signs:** two librarians, the librarian turning into another profession while keeping the name, stored items lost on a purge, stored items in the survival inventory, the chest missing or facing into the wall, stashed loot surviving a max-omen death, Mending on a chest drop.

## L16. 2026-10-02: diaries read in the book screen

- **Status:** owed
- **Changed:** 2026-10-02: the lodestone menu (and Lemon's menu) Diaries entries now open the vanilla book screen, with the pages in canonical order, instead of the dialog. The book is shown in the hand slot on the player's screen only, for the moment the screen opens; the server inventory is never touched (review F3).
- **Do:** with consent, ask the player to open Diaries from the menu holding something recognisable. Watch for the held item flickering, being lost or turning into a book.
- **Pass:** the book screen opens, closing it leaves the original item in hand.
- **Fail signs:** the held item replaced or duplicated, a diary book left in the hotbar, no screen opens (the client reading the old slot before the book arrives).

## Done

- **PD-92** max-omen ejection keeps each stack once: passed 2026-10-01
  (`2026-10-01-1.md`), Lost and Found and the restored pack held each stack once.

## L17. 2026-10-02: Lemon's diary archive

- **Status:** owed
- **Changed:** 2026-10-02: right-clicking Lemon holding a found diary book makes her keep it (the entry is recorded for good, she tells a tip, and holding every entry grants one extra echo shard per completed interval, `lemon_archive` in the `echo_shards` events). Books found before this change are not tagged; the Diaries menu entries still read in the book screen either way.
- **Do:** with consent, once the player holds a diary, ask whether they would give it to Lemon (do not say how: watch). Note the tip and whether they find it useful.
- **Pass:** the book leaves the hand, Lemon speaks a tip and the count, a duplicate is refused and kept.
- **Fail signs:** the book is consumed without a line, or the click opens the menu instead.

## L18. 2026-10-02: themed merchants

- **Status:** partial, 2026-10-04-1.md: the Store appeared unsteered on floor 1 and as an anomaly store on prismarine floor 3 (PD-137 fix works). Frost Peddler priced stock in bones, rotten flesh and emeralds and the owner liked the prices. Faults: entered backwards (PD-140), vendor wanders off (PD-141), item teleports on purchase and name follows the theme not the stock (PD-142). Not seen: a reload, a wrong item taken. Still owed for those. Earlier: owed, 2026-10-02-2.md: the player asked for a merchant ("I haven't seen a merchant yet"); with consent the run was steered with `dungeon admin bias the_store 20` but no Store showed before the wrap. Bias cleared. Retry with a bias held on door selection (`room_bias_hold`). 2026-10-03-2.md: bias x20 then x50 over about 7 floors, still no Store (PD-137); the vanilla trading screen was never seen. Still owed. PD-137 fixed 2026-10-03: the Store could never be placed (a one-door room with only the corridor role); it is a loot room now, so a bias should place it on most floors.
- **Changed:** 2026-10-02 (another agent, see `docs/reference/THEMED_MERCHANTS.md`): the Store's merchant is chosen by the floor's theme and prices stock in that floor's mob drops (bones, string, blaze rods, magma cream, ender pearls and so on). Themes without a merchant keep the emerald shopkeeper. The four game tests pass; the live behaviour is unseen.
- **Do:** bias a Store room on an ossuary or basalt_foundry floor (ask first). Watch whether the player brings the right drops and whether the prices read as fair.
- **Pass:** the merchant is named for the theme, the shop shows drop prices, buying takes the drops, a saved shop survives a reload.
- **Fail signs:** a purchase that takes the wrong item, a shop that sells its own currency, a villager that loses its stock on reload.

## L19. 2026-10-02: placement notices

- **Status:** owed
- **Changed:** 2026-10-02 (owner request): placing a block anywhere in the dungeon outside a safe room (the staging room, a floor, a visit's copy of a room) shows a red action bar line, "This will not last. Only a safe room keeps what you build.", with the wrong tool note. Placing a block in a safe room shows a green line, "Saved with your safe room. It will be here when you come back." ("this safe room" for a guest), with a chime. The sound plays at most once a second; build rooms are silent.
- **Do:** watch the first time the player places a block in the staging room or on a floor, and the first time they decorate the safe room. No setup needed.
- **Pass:** red line and low note in the staging room and on floors; green line and chime in the safe room; a block placed in the safe room is still there after a run and a return; a row of blocks does not play a row of notes.
- **Fail signs:** green in the staging room, red in the safe room, a green block that is gone on return, chat spam, no sound.

## L20. 2026-10-03: gated puzzle rooms face the approach (PD-133)

- **Status:** partial and failed, 2026-10-04-1.md: rotation_lock (frostworks) and sensor_gallery (frostworks, ender_archive) were entered with no closed door in the way, solves not watched; sorting_floor FAILED (PD-144: pre solved, two door sets, water everywhere, free sticks). Owed: a watched solve of each, and sorting_floor after the fix. Earlier: owed
- **Changed:** 2026-10-03: every gated room (rotation_lock, frame_lock, plate_pair, item_plate, flow_puzzle, hold_the_plate, sorting_floor, sensor_gallery and the knowledge rooms) is turned so its open side faces the cell you arrive from. sorting_floor and sensor_gallery used to be dead ends with a door into the wall; they are real pass-through gates now, with regenerated templates.
- **Do:** bias `rotation_lock`, then `sorting_floor` and `sensor_gallery` (ask first). Walk in from the previous room.
- **Pass:** the iron door is always on the far side; the room can be entered and solved; solving opens the way on.
- **Fail signs:** a closed iron door facing the way in, a gate that opens nothing, a sensor gallery door that never opens.

## L21. 2026-10-03: party members in the leader's rooms (PD-131, PD-132)

- **Status:** owed, 2026-10-04-1.md: solo session, not exercised. Earlier: owed
- **Changed:** 2026-10-03 (owner request): a party member may open chests, use every station and place blocks in the leader's safe and staging rooms; a lobby-directory guest still may not build. Clicking a station with a block in hand opens it. The bag chest stays until the door is chosen, and clicking it with a bag already chosen names your bag.
- **Do:** with two players, have the companion craft and salvage holding a stack of blocks, place a block in the leader's room, and click the bag chest before and after picking.
- **Pass:** every station opens every time; blocks place; a bagless companion can always pick a bag after the leader did.
- **Fail signs:** a station that refuses, a placement refused for a companion, the bag chest missing for a bagless member.

## L22. 2026-10-03: balance batch

- **Status:** retired by W5 (design D14: five kits, granted once, no refill). Was: owed (partial), 2026-10-04-1.md. Passed: floor 1 had 2 spawners twice; inventory_snapshot written at every floor clear and bank; salvage gave materials, no emeralds, XP only from the enchanted piece. Failed on the clock: floor 1 took 828 s and 731 s (player was chatting to Lemon, two puzzles slowed him). Not tested: party vault, one key per spawner, history board text. Earlier: owed
- **Changed:** 2026-10-03: the first floor of an interval has at most 2 encounter cells (`firstFloorMaxEncounters`); chests carry fewer logs and more planks; a trial spawner ejects one key per party, and a vault opens once per party but pays one loot roll per member (owner rule; `PartyRewards`); the floor history rows are 20 percent bigger and the heading sits higher; a tamed spawner wolf counts as defeated; a chest leather cap salvages; Disenchant works with an empty bench; salvage gives the material back, measured against the reduced dungeon maximum: leather, iron and chainmail (iron), gold, copper, diamond and netherite (scrap) give 2 for a chestplate or leggings at 75 percent or more and 1 from 25 percent, other pieces 1 from 25 percent; wooden tools give a plank at 75 percent or more or 2 sticks from 25 percent; stone tools a cobblestone and shields a plank from 25 percent; kit can be scrapped (only the keystone is refused); scrapped gear pays no emeralds, only its materials and the XP a vanilla grindstone would give (none when unenchanted); the journal writes an inventory_snapshot (pack, run storage, ender chest, kept pack) at each floor clear, bank and exit.
- **Do:** watch floor 1 length and spawner count, chest contents, and a two-player vault. Ask whether the history board reads well.
- **Pass:** floor 1 shows 2 spawners or fewer and runs well under 300 s; in a party of two, one key per spawner and one vault opening that ejects about twice a solo vault; the board text fits its backdrop.
- **Fail signs:** history rows wider than the panel, two keys from one spawner, a vault the second player can still open, a party vault that pays like a solo one, floor 1 with 3 or more spawners.

## L23. 2026-10-04: the bag chest is a kit station (RETIRED 2026-10-05, dungeon structure W5)

- **Status:** owed (partial), 2026-10-04-1.md: `kit_refill` fired on both banks (04:28:48, 05:37:50); the second overwrote an unused shield and stone sword (leftovers replaced, not stacked). Not seen: two players, the chest itself in play. The owner is now leaning toward removing refills (4 or 5 starting kits, no refill), so this row may be retired by an owner decision. Earlier: owed
- **Changed:** 2026-10-04 (owner decision): the bag chest stands in the safe room for good. With no bag, clicking it picks one (the kit goes into the pack, as before). With a bag, it opens the player's own 27 slots, "Your Kit". Every trip home that banked a floor fills those slots with a fresh full kit, overwriting whatever was left; the old top-up into the pack is gone. Each party member has their own slots. Journal event `kit_refill`. Fix 2026-10-04 (owner report): the chest is placed once, saves with the room and can be mined and set down anywhere in the safe room (not elsewhere); a new one appears only if the room has none and the owner is not carrying one. Death now reads "You come to at the Doors, with the feeling of a bad omen." (players call the staging room the Doors).
- **Do:** after a trip home, have each player open the bag chest; leave something in it, go out and come home again.
- **Pass:** a moved chest stays where it was put and still opens the kit, with no second chest at the centre; a full fresh kit after each banked trip; leftovers replaced, not added to; two players see their own kits; the chest is there on every return.
- **Fail signs:** an empty chest after a banked trip, leftovers stacking up, one player seeing the other's kit, the chest missing, the kit landing in the pack.

## L24. 2026-10-05: the dungeon feels connected (Stage 1 hypothesis 1, A6)

- **Status:** partial pass 2026-10-05 (`2026-10-05-1.md`): asked directly after one Mineshaft trip, "yes, it felt good"; he had already named a floor unprompted ("The Main Drift"). Caveat: one resource dungeon only, and floors 1 and 2 were all generic halls (PD-149), so the pass leans on the structure rather than themed rooms.
- **Changed:** 2026-10-05: a trip is one dungeon. The first staging room offers three dungeons from the unlocked acts; later doors are the edges of the node just cleared; the floors share a main theme with at most one borrowed theme (`docs/DUNGEON_STRUCTURE_DESIGN.md` section 8).
- **Do:** discovery check, watch only. Play a full dungeon with the owner or a new player and do not explain the structure. Afterwards ask him to name the dungeon and its floors, and note unprompted comments on place and theme. Journal events: `dungeon_chosen`, `node_entered`, `edge_taken`.
- **Pass:** he names the dungeon and at least two floors, and says it feels like one place.
- **Fail signs:** "still feels random"; one theme over five floors reads as samey (51 of 61 rooms are generic, so watch for it); he cannot say which dungeon he was in.

## L25. 2026-10-05: finishing pulls (Stage 1 hypothesis 2, A7 and A2)

- **Status:** pass 2026-10-08 (`2026-10-08-1.md`): three finishes in one session (Infestation 4 floors, Spawner Dungeon capstone 3 floors, Copper Works 4 floors), all unprompted; the Spawner milestone title landed ("The Spawner Dungeon Cleared Go Home worked", PD-165 verified live). Remaining caveat: the finish's flat ending now reads as missing a finale (PD-184). Earlier: partial pass 2026-10-07 (`2026-10-07-1.md`): two more unprompted first finishes (Copper Works 4 floors, Cow Pits 2 floors), diary pages 13 and 15 arrived, vault_chests 2 each. New caveat: he does not recall any "<Dungeon> cleared" milestone title on either (PD-165). Earlier: partial pass 2026-10-06 (`2026-10-06-1.md`): he finished the Mineshaft (3 floors, `dungeon_finished`, first clear) and the diary page arrived (entry 8, which he read and reported clipped, PD-157). Caveat: `dungeon_finished` journaled `shards:0`; under the new barrel model vault rolls merge so `vault_chests:0` is expected, but confirm the finish shard paid. Earlier: owed, 2026-10-05-1.md: inconclusive, quit on the final floor for real-life reasons.
- **Changed:** 2026-10-05: clearing a final node pays one echo shard per member, a themed vault of two top tier chests and, on a first finish, a diary page. Going home early pays none of it.
- **Do:** discovery check. Count floors per trip (it used to be three every time) and note why he went on or went home at each staging room. Journal events: `door_commit`, `bank`, `dungeon_finished`. Ask about the vault and the page only after he has seen them.
- **Pass:** he plays past three floors to finish at least once, or names the vault or the page as the reason.
- **Fail signs:** he still banks at three floors and never finishes (shorten dungeons or move the vault earlier); the vault is missed because the HOME lever is pulled first.

## L26. 2026-10-05: dungeon length is right (Stage 1 hypothesis 3, A8)

- **Status:** pass 2026-10-07 (`2026-10-07-1.md`): Copper Works 4 floors in about 25 minutes of play (clears 119/77/81/187 s) and Cow Pits 2 floors in about 8 minutes; no drag complaint, both finished unprompted. Earlier: pass 2026-10-06 (`2026-10-06-1.md`): full Mineshaft clear (3 floors) took about 30 minutes door to bank (entry ~04:58, `dungeon_finished` 05:28), floors at 364 s and 633 s bookends with chat inflation. No drag complaint. Earlier: owed, 2026-10-05-1.md.
- **Changed:** 2026-10-05: 3 to 6 layers per dungeon (the Act 1 story dungeons have 3 to 4), one floor per layer; resource dungeons have 1 to 3.
- **Do:** time a full dungeon from the first door to HOME with the journal (`node_entered` to `dungeon_finished`). Note seconds per floor (floor 1 was the slowest at 218 to 828 s) and where he slows or asks to stop.
- **Pass:** a full Act 1 dungeon fits in about 45 minutes or less with no strong complaint of drag.
- **Fail signs:** more than about 45 minutes for one dungeon (shorten floors, not layers); a floor that stalls on digging for a gated room.

## L27. 2026-10-05: shards buy branches (Stage 1 hypothesis 4, A3 and A6)

- **Status:** owed, 2026-10-07-1.md: still not exercised (no priced edge taken), but the currency has moved to scrap: a side-branch refusal "2 scrap short" was seen live (PD-162: it names the gap, not the holding). This row's shard wording is stale. Earlier: owed, 2026-10-05-1.md: not exercised. The Mineshaft has only free main edges, so no side branch was ever offered; he holds 6 echo shards (carried over).
- **Changed:** 2026-10-05: a side edge costs echo shards (authored per edge, usually 1), paid by the member who pulls the lever; shards come only from finishing a dungeon and from Ordeals.
- **Do:** watch shards held at each staging room, side branches taken and shards left unspent. Journal: `edge_taken` with its cost.
- **Pass:** he takes a side branch at least once and weighs it aloud, with some shards spent and some kept.
- **Fail signs:** a branch is never affordable (raise income); every branch is always taken (raise the cost); the action bar balance line is missed.

## L28. 2026-10-05: random steps and the doors screen

- **Status:** superseded by design 2026-10-08 (`2026-10-08-1.md`): he rejected the three-random-door model entirely ("The 3 random doors doesn't really work with the new dungeon act system", "players should be able to choose the act and dungeon"; his top pick for "first thing you'd change" at wrap). The door-screen question is now PD-181: replace the front offer with act/dungeon selection. Earlier: still owed 2026-10-06 (`2026-10-06-1.md`): the new one-layout board was seen and read (he asked what "Same as door 1" meant, then rejected duplicates outright, superseding PD-153: "if they are the same then they should have different affixes"). Door-choice weighing still not observed closely. Earlier: failed for resource dungeons 2026-10-05 (`2026-10-05-1.md`).
- **Changed:** 2026-10-05: each door is dealt +1, +2 or +3 (seeded, the same for a preview and its commit); a resource dungeon deals 0. A door shows its floor name, step, loot tier and shard cost; later steps are never shown.
- **Do:** at three staging rooms, ask which door he is picking and why; note whether he always takes +3.
- **Pass:** the choice reads as meaningful, the door text is read, and the same door shows the same step on a second look.
- **Fail signs:** "the floor I want rolled +3"; +3 taken every time because it is free; a step that changes between preview and commit.

## L29. 2026-10-05: the dungeon map and the first doors

- **Status:** partial 2026-10-05 (`2026-10-05-1.md`): the Spawner Dungeon stood on a door 1 or 2 slot on the first trip (pass on the offer rule). The map/door screen was seen but read as too much text (PD-151); comprehension unconfirmed.
- **Changed:** 2026-10-05: right-clicking the floor history board (or `/dungeon map`) shows the dungeon graph: current node, final node, edge costs, reachable nodes. Before a trip it lists the three dungeons on offer. The Spawner Dungeon stands on door 1 or 2 until cleared.
- **Do:** open the board on a fresh trip and mid trip; start several trips as a player who has not cleared Act 1.
- **Pass:** the map is read and understood; the Spawner Dungeon is on door 1 or 2 on every trip until cleared; after the clear the next act's capstone takes over.
- **Fail signs:** the map is never opened; the Spawner Dungeon on door 3 or missing; text wider than the dialog.

## L30. 2026-10-05: the break rule and dark rooms

- **Status:** rule reverted, delight confirmed 2026-10-06 (`2026-10-06-1.md`): interiors are mineable with the correct tool everywhere again (pre-ebc37e3 semantics, shell and fixtures still protected). On the seam room: "one of my favourites so far ... It feels like minecraft", and "the durability of the tools balances the abundance of cobblestone". Dark floors not specifically re-observed. Earlier: failed on design 2026-10-05.
- **Changed:** 2026-10-05 (D17, D20, D21): inside a dungeon cell only resource nodes (with the right tool tier), player placed blocks and soft mechanic blocks break. Rooms can be `dim` or `dark`; torches are rarer in chests.
- **Do:** try to dig a wall, floor and ceiling; mine a node with the wrong and the right tool; solve a room that expects digging (infested wall, thicket webs, flow puzzle); walk a dark node with and without a torch.
- **Pass:** wall breaks refuse with one throttled action bar line; nodes break with the right tool; no room is impassable for lack of a break; a dark room is tense but playable and the kennel rooms stay lit.
- **Fail signs:** a room that needed digging and cannot be passed (the owner's "inaccessible rooms" worry); a refusal line that spams; Feral wolves on a dark node.

## L31. 2026-10-05: the Spawner Dungeon and Ancient City capstones

- **Status:** half passed 2026-10-08 (`2026-10-08-1.md`): Spawner Dungeon cleared live at floor omen 4, lives 1, all 4 spawners then the brood wave; `dungeon_finished` journaled and the milestone title landed ("The Spawner Dungeon Cleared Go Home worked"). Difficulty verdict: "just barely enough resources to succeed". Ancient City still owed. Earlier: owed, 2026-10-07-1.md: he cleared two Act 2 dungeons tonight (Copper Works, Cow Pits), so Act 2 is open for him, but whether that came from a legitimate capstone clear or bypassed gating is unverified (no /dungeon map opened, dungeonsFinished unread). Neither capstone was entered. Earlier: owed, 2026-10-05-1.md: the Spawner Dungeon was offered correctly (step 2, Overclocked, door 1 or 2) but he chose the Mineshaft; never entered.
- **Changed:** 2026-10-05: Spawner Dungeon (Act 1): four classic spawners, then a final wave; the pad stays shut until both stages are done. Ancient City (Act 2): every sculk sensor and shrieker raises omen, a real Warden arrives at omen 4 and does not gate the pad.
- **Do:** solo and in a party of two, clear each. Note the brood size, how the spawner break and the exhaust rule feel, and what the omen bar does in the Ancient City.
- **Pass:** the pad opens only after the brood, a clear unlocks the next act with a title, the Warden arrives and the party can still finish by reaching the terminal.
- **Fail signs:** the pad opens early or never opens; the Warden spawns twice or elsewhere; a brood that is trivial or unwinnable for two.

## L32. 2026-10-05: the Wither and the Herobrine fight

- **Status:** owed, 2026-10-05-1.md: unreachable; Acts 4 and 5 stay locked until the earlier capstones are cleared and there is no admin command to unlock acts.
- **Changed:** 2026-10-05: the Wither (Act 4) has 240 health plus 120 per extra member, is held inside its room and breaks no blocks. Herobrine (Act 5) is the Steve fight in phases (melee, summons, blink). The End (Act 5) is an ordinary story dungeon.
- **Do:** fight the Wither solo and with two players and look at the room afterwards. Fight Steve through the phases.
- **Pass:** the Wither cannot leave the room or carve the walls, the pad opens on its death and a nether star drops; Steve's phases show their titles and the fight is winnable but tense.
- **Fail signs:** a broken wall or a Wither in the next cell; a phase that never changes.

## L33. 2026-10-05: Herobrine's rescue scene

- **Status:** owed, 2026-10-05-1.md: unreachable with L32 (Act 5); no admin unlock exists and editing the save is off the table.
- **Changed:** 2026-10-05 (D16): at low health Alex arrives, Steve speaks and teleports away, the party is healed and the floor completes; the campaign ends with "The search continues".
- **Do:** trigger it each of the four ways: a member at 25 percent health, a member's killing blow, Steve at 10 percent health, Steve's killing blow.
- **Pass:** nobody dies, the scene runs about 430 ticks, Alex has the slim Alex skin, the pad opens afterwards, and the diary page and the campaign line arrive.
- **Fail signs:** a member dies during the scene; Alex or Steve left in the room after the floor ends; the pad never opens; the scene fires twice.

## L34. 2026-10-05: party decide whitelist

- **Status:** owed, 2026-10-05-1.md: solo session, SirAegerus never joined.
- **Changed:** 2026-10-05 (D15): any member may pull doors, choose branches and pull HOME by default. `/dungeon party decide whitelist on` limits it to the leader and the players added with `decide add`; `/dungeon quit` stays owner only.
- **Do:** with two players, have the companion pull HOME with the whitelist off, then on, then after being added.
- **Pass:** off: the companion can; on: refused with "The party leader has limited who decides here"; listed: allowed. Watch whether a companion ends a trip the leader wanted to continue.
- **Fail signs:** a refused leader; a companion who can still pull a lever with the whitelist on; the door screen not saying why.

## L35. 2026-10-05: Cow Pits finite cows and resource dungeon rewards

- **Status:** partial, 2026-10-07-1.md: Cow Pits finished for the first time (2 floors, `dungeon_finished first:true`, diary entry_15 arrived in the pack). Finite-cow limit not exercised (no breeding attempted). Copper Works x4 floors rolled `nodes_total` 0 with its palette advertised on the doors (PD-161). Earlier: owed, 2026-10-05-1.md: Mineshaft half exercised. Two of three floors rolled zero nodes (PD-149) and the one themed ore room was too generous (PD-155). Diary page not seen (no finish). Cow Pits untouched: it is Act 2.
- **Changed:** 2026-10-05 (D11, D12): resource dungeons deal step 0, pay no shard or vault, and give a diary page on the first finish. Cow Pits has 6 to 10 adult cows, no wheat and no breeding.
- **Do:** run Mineshaft and Cow Pits; try to breed or feed cows, count the beef and leather, and note ore mined against durability spent (a farming risk).
- **Pass:** cows cannot be multiplied, the haul is real but bounded, the key does not climb, the diary page arrives on the first finish only.
- **Fail signs:** an infinite cow loop; durability not a real cost so the resource dungeon is farmed; a shard or vault from a resource dungeon.

## L36. 2026-10-05: Endless Mine seal and the deepest floor

- **Status:** owed, 2026-10-05-1.md: not seen; the Mine only appears on door 3 once Act 2 is unlocked, and the Act 1 capstone was not cleared.
- **Changed:** 2026-10-05 (D13): after Act 1's capstone the first staging room shows the Endless Mine on door 3; layers open by act; the deepest floor shows on the history board.
- **Do:** with act 2 open, enter the Mine and go down to floors 6, 12 and 18 as the acts allow; read the sealed line and the history board.
- **Pass:** floors 1 to 5 enter freely; a sealed layer offers only HOME with the act named; the deepest floor persists across trips.
- **Fail signs:** the Mine on door 3 before act 2; a shaft that lets you past a sealed layer; the Mine hiding the capstone door.

## L37. 2026-10-05: an old save loads into the new structure

- **Status:** passed 2026-10-05 (`2026-10-05-1.md`): his pre-branch save (keystone 9) loaded with no decode errors, kept pack and history intact, and the first-trip offers were Act 1 only (Spawner Dungeon, Lush Caves, Mineshaft). The keystone-15 act-2/3 grant was not exercised (he is at 9).
- **Changed:** 2026-10-05: `DungeonLog` gained campaign fields; `currentTheme` and `depth` are superseded; a pre-branch player starts with Act 1 and, if their keystone is 15 or more, also Acts 2 and 3 once. The kit is granted once with no top up.
- **Do:** start the server on a copy of a pre-branch world; join as a keystone 15 or higher player and as a low one, one of whom had chosen a now hidden bag.
- **Pass:** no stack trace on load; the high player sees Act 2 and 3 dungeons and the low one only Act 1; the hidden bag still gives its kit.
- **Fail signs:** a decode error in the log; a high player reset to Act 1; a kit refill at the bank.

## L38. 2026-10-06: the Store is one row you click

- **Status:** passed 2026-10-08 (`2026-10-08-1.md`): used unprompted many times across the session (7 `shop_sale` events on one floor, more at home); verdict "The shop works perfectly". Sold-out and short-price edge cases not specifically probed but nothing failed in normal use.
- **Changed:** 2026-10-06 (PD-159, `StoreNPC.openShop`): the Store opens a one row shop; a click takes one, the stock is claimed with the delivery, and a bought stack is the plain item.
- **Do:** Open a Store. Left click and right click an item. Buy a log twice with a log already in the pack. Stand with too little of the currency. Buy a line out.
- **Pass:** Both clicks buy one and hand it over; the log stacks with the one you had; the price reads red when you cannot pay; a sold out line becomes a gray pane named sold out and refuses.
- **Fail signs:** An item that does not arrive but costs stock; a bought stack that will not stack; a price that stays gold when short.

## L39. 2026-10-06: diary entry 8 reads whole

- **Status:** owed
- **Changed:** 2026-10-06 (PD-157, `BookPages`): a long diary page is split over as many book pages as it needs, at a paragraph, then a sentence, then a word.
- **Do:** Finish the Mineshaft (or have the owner hand over entry 8, The First Pick) and open the book.
- **Pass:** Every sentence is there, none cut off at the foot of a page, no blank page.
- **Fail signs:** Text that stops at the bottom of page 1; a blank page.

## L40. 2026-10-06: a Mineshaft floor 1 has ore

- **Status:** passed 2026-10-08 (`2026-10-08-1.md`): Mineshaft floor 1 rolled `nodes_total` 31, `nodes_mined` 25, `floor_pay` scrap 1; the ore is real and mined. Only one floor 1 sampled (the check asked for three), but the zero-node failure mode did not appear.
- **Changed:** 2026-10-06 (PD-149, `LayoutPlanner.plan`): a resource floor whose plan fits none of its ore rooms retries the next seed.
- **Do:** Start the Mineshaft three times and read the floor_complete line of floor 1 each time.
- **Pass:** `nodes_total` is above 0 on floor 1 every time, and the player can see ore.
- **Fail signs:** A floor 1 of only generic halls with `nodes_total` 0.

## L41. 2026-10-06: a flooded hall entered from outside

- **Status:** owed
- **Changed:** 2026-10-06 (PD-160, `IronDoorLatch`): the flooded hall's containment doors are latches. The button they used to have sat in the water and was washed off.
- **Do:** Bias to `flooded_hall` and enter it from the west door, then from the east. Click the iron door from the dry side.
- **Pass:** Both leaves open on a click from either side, stay open about three seconds, then close; no button anywhere; the water stays in.
- **Fail signs:** A door that will not open from outside; water pouring out through an open door for longer than three seconds.

## L42. 2026-10-06: the connector lever is on another wall

- **Status:** owed
- **Changed:** 2026-10-06 (PD-160, PD-55, `ConnectorStamper.applyIronDoor`): a connector iron door's lever moves off the frame to another wall of the near room, and the far room gets a stone button.
- **Do:** Find a connector iron door (about one door edge in ten). Look for the lever in the room before it; walk through, turn round and find the button.
- **Pass:** The lever is on a different wall at head height and works the door; the stone button on the far side opens it too; clicking the shut door with neither says The lever is in this room.
- **Fail signs:** A lever on the frame again; a lever in water or inside a wall; a far side with no way to open.

## L43. 2026-10-06: the omen bar is full and red at 4/4

- **Status:** owed
- **Changed:** 2026-10-06 (PD-158, `OmenBar.sync`): during a floor the bar fills by floor omen over 4 and colours green (0 to 1), yellow (2 to 3), red (4).
- **Do:** Raise the floor omen to 2, 3 and 4 by dying, eating and lingering, and watch the bar.
- **Pass:** The fill steps in quarters and is full at 4/4; the colour goes green, yellow, red; one more fall ends the run shows from omen 3.
- **Fail signs:** A part filled bar at 4/4.

## L44. 2026-10-06: doors behind one edge differ in affixes

- **Status:** passed 2026-10-07 (`2026-10-07-1.md`): one Doors screen dealt Ominous, Silenced and Feral over steps 2/1/3 on the same node, and an earlier pair dealt Overclocked and Molten; no "Same as door" line seen. Earlier: owed
- **Changed:** 2026-10-06 (design item 3, `DoorAffixes`): doors that lead to the same floor reroll their seeded affixes until no two are twins.
- **Do:** At a node with one edge, at compass 5 or higher, preview the three doors.
- **Pass:** The three doors show different affixes (at compass 5 only the step differs, by design); no `Same as door` line.
- **Fail signs:** Two doors with the same floor, step, affixes and price.

## L45. 2026-10-06: resource doors show scrap and ore

- **Status:** partial 2026-10-07 (`2026-10-07-1.md`): the two-sheet board replaced this wording; on Copper Works the right sheet read "Boiler Hall, Explosive, 6 emeralds iron coal, lootx3" (emerald pay while overleveled, ore as plain words, loot count in green). But PD-161: the ore promise lied, all four floors had `nodes_total` 0. Mineshaft doors still unseen this build; em-dash-era "MINESHAFT - floor N of 3" title not rechecked. Earlier: owed
- **Changed:** 2026-10-06 (design items 1 and 6, `DungeonScreen.previewContent`): Mineshaft floors pay chart scrap like any floor, and the door board shows the ore as plain words.
- **Do:** Preview each Mineshaft door. Read the title, the name, the cyan line and the loot line. On a capstone door read the title width.
- **Pass:** Title `MINESHAFT - floor N of 3` (middle dot) in yellow; cyan line `2 scrap` then coal, copper, iron, gold; `loot x3` in green; dim or dark on its own line. Titles fit the backdrop; the Wither's Keep and Spawner Dungeon titles measure about 8.4 and 9.3 blocks on an 8 block backdrop, so note how they look.
- **Fail signs:** No scrap on a resource door; labels like Rewards: or Resources:; a title that runs off the panel.

## L46. 2026-10-06: GO HOME reads scrap and charts

- **Status:** wording superseded; seen live 2026-10-07 (`2026-10-07-1.md`): the board now shows title plus "Lives N" (and "Unfinished: vault, page" while owed) per J1; the player saw it and asked for his scrap count on it ("The home board should be showing me my current scrap"), a design note now folded into PD-162. Earlier: owed
- **Changed:** 2026-10-06 (design item 2, `IntervalBanking.homeScreen`): the GO HOME board is a title and a body: scrap carried, then what it comes to.
- **Do:** Go home with 3, 5, 7 and 10 scrap carried (or read the board at each step of a trip).
- **Pass:** 3 scrap over 2 more for a chart (gold); 5 scrap over 1 chart (green); 7 scrap over 1 chart, a middle dot, 2 scrap lost (gray); 10 scrap over 2 charts. Nothing carried reads no scrap yet. The banked chat line says N scrap lost.
- **Fail signs:** The old Take home or DUNGEON CLEARED: GO HOME wording on the wall.

## L47. 2026-10-06: hidden ore is found by digging

- **Status:** owed
- **Changed:** 2026-10-06 (design item 7, `HiddenOrePlanner`): Mineshaft rock holds a few buried pockets of one to three ore blocks, counted as nodes.
- **Do:** Dig into the walls of mineshaft_seam and other Mineshaft rooms with the right pick.
- **Pass:** Some digs find a pocket of 1 to 3 ore; none shows from the corridor before it is dug; `nodes_total` counts it and `nodes_mined` rises when it is mined.
- **Fail signs:** Ore visible from inside the room before digging; ore in the wall ring.

## L48. 2026-10-06: Dungeon Storage survives a logout and a teardown

- **Status:** passed 2026-10-07 (`2026-10-07-1.md`): he used it unprompted and heavily (stashed 64 emeralds plus gear and mats mid-trip); the same contents rode through the 00:04 disconnect plus purge, two banked trips and two dungeon finishes (`inventory_snapshot` shows the stacks carried across all of it). The safe-room ender chest opening the same storage was not separately watched, but the storage is a `DungeonLog` record now, so the chest is only a door to it. Earlier: owed
- **Changed:** 2026-10-06 (design item 5 and J6, `RunStorage`): the storage chest is a 27 slot container saved in `DungeonLog`; any ender chest in a live instance's safe room or at the Doors opens it, and nothing moves it when a run ends.
- **Do:** Put items in the staging room's ender chest (titled Dungeon Storage), log out and back in, finish or fail a run, start another and open it again. Try the same from an ender chest inside the safe room at the far end.
- **Pass:** The items are still in their slots after the logout, the teardown and a max omen death; the safe room chest opens the same storage as the one at the Doors.
- **Fail signs:** An empty storage after a run closes; a stored item in the pack instead; an ender chest in the dungeon opening the vanilla ender chest.

## L49. 2026-10-06: five bags at the lectern

- **Status:** owed
- **Changed:** 2026-10-06 (L1, `BagIds.CORE`): the bag picker offers Guard, Ranger, Sapper, Lumberjack and Innkeeper. Pilgrim, Shepherd, Mason, Magician and Plumber sit behind the `extra_bags` content module, off by default.
- **Do:** Open the bag lectern on a fresh world and read the five blurbs. Kit out as each bag and check the kit matches the blurb.
- **Pass:** Exactly five bags; Sapper's kit holds a stone pickaxe, Lumberjack's an axe and oak logs, Innkeeper's a Rolling Pin named item; no module, no cut bag appears.
- **Fail signs:** Nine or ten bags; a missing kit item; Plumber in the list.

## L50. 2026-10-06: the Store is a villager's trade screen

- **Status:** pass 2026-10-08 (`2026-10-07-2.md`): completed trades now journaled from two vendors: `shop_sale` x2 (Web Trader, 1 emerald for 3 string) and `shop_purchase` (Wandering Merchant, bow for 3 emeralds; home Librarian, diamond leggings for 18). Earlier: partial 2026-10-07 (`2026-10-07-1.md`, screen opened, no journaled trade); owed
- **Changed:** 2026-10-06 (J4, `StoreNPC`, `MerchantThemes`): each themed merchant is a tagged villager with real `MerchantOffer`s. Leftover drops go in as bundles, the pool pays out in the merchant's currency.
- **Do:** Trade at two different themed merchants; buy something and watch the journal.
- **Pass:** The vanilla merchant screen opens, not a chest menu; offers are real trades; a completed trade writes a `shop_sale` journal row with the item.
- **Fail signs:** A chest interface; an offer that cannot complete; trades that vanish after a floor.

## L51. 2026-10-06: the stations that remain

- **Status:** owed, 2026-10-07-1.md: not exercised (no station used this session). Incidental: the safe room's station list showed crafting_table, damaged_anvil, furnace, grindstone, smithing_table and no enchanting table; if reroll lives at an enchanting table the home set may be missing it (unconfirmed, the list may only count placed blocks). Earlier: owed
- **Changed:** 2026-10-06 (J5, `RitualListener`, `StationTutorial`): four stations remain: Salvage at the anvil, Reroll at the enchanting table, the Home Vendor, the Bag Chest. No level gates. Gamble, Blacksmith and Lock In are gone.
- **Do:** Visit a station room in the dungeon and try each station. Put a scrapable drop on the anvil; put a gear piece on the enchanting table.
- **Pass:** Salvage opens regardless of level; the enchanting table rerolls; no gamble screen, no blacksmith villager, no lock-in prompt.
- **Fail signs:** A "too low level" refusal; the smithing table opening reroll; the Cube answering a use.

## L52. 2026-10-06: the librarian sells rolled gear

- **Status:** partial 2026-10-10 (`2026-10-09-1.md`): Mending offer confirmed live: `shop_purchase` enchanted_book from the home Librarian for 64 emeralds (the L52 price point). Buy-back used heavily same trip (40 rotten flesh, 8 lapis). Offer count and restock-on-homecoming still unverified. Earlier: partial 2026-10-08 (`2026-10-07-2.md`): he built the lectern (and enchanting table, grindstone, smithing table) in his safe room and bought rolled gear from the Librarian: `shop_purchase` diamond_leggings for 18 emeralds. Offer count, Mending price and restock-on-homecoming still unverified. Earlier: owed, 2026-10-07-1.md: not exercised
- **Changed:** 2026-10-06 (J5a, `LibrarianNPC`, `VendorMath`, `VendorStock`): the home librarian is a real villager whose offers are rolled gear tiers up to the owner's act, plus Mending for 64 emeralds, plus a buy-back of surplus drops.
- **Do:** Open the librarian after finishing an Act 1 floor and again after an Act 2 unlock. Buy a gear offer.
- **Pass:** Act 1 shows six offers (tiers I and II); Act 2 adds tier III for nine; never tier IV; every reroll on a homecoming changes the stock; Mending costs 64 emeralds.
- **Fail signs:** Vanilla librarian enchanted book trades; stock that never changes; tier IV before a capstone.

## L53. 2026-10-06: keys settle on the clear line

- **Status:** partial 2026-10-08 (`2026-10-07-2.md`): key redemption on the clear line confirmed by `floor_pay` emerald payloads of 3, 4 and 1 emeralds alongside scrap (matching the J7 rates: 1 plain, 3 ominous). New related break: ominous trial keys cannot pay the barred_vault toll, so on ominous floors keys can only ever redeem (PD-170). Mob gear drops not rechecked this session. Earlier: owed, 2026-10-07-1.md
- **Changed:** 2026-10-06 (J7, `DungeonDrops`, `RunLifecycle.redeemKeys`): mobs drop no gear in the dungeon, and trial keys never leave their floor. The salvage bench refuses keys; at a floor clear each unused key becomes emeralds.
- **Do:** Kill dungeon mobs and check the drops; put a trial key in the salvage input; clear a floor holding a plain and an ominous trial key.
- **Pass:** No weapons, armour or bows drop; the bench refuses the key; the clear line pays 1 emerald for the plain key and 3 for the ominous, and the keys are gone.
- **Fail signs:** A mob dropping a bow; a key salvaging at the bench; a key still in hand after the clear.

## L54. 2026-10-07: Haul and Blood Doors

- **Status:** partial 2026-10-08 (`2026-10-08-1.md`): the model is no longer invisible: three lever banks and three finishes all journaled correctly, and lives pressure landed ("It was definitely pressure" at lives 2, two rescues). New legibility breaks: "Haul 0 scrap" on the GO HOME board after a finish (PD-179) and "Home 4 chests" unreadable (PD-180). Still untested: blood door taken, last-life refusal, disconnect/grace, party compasses. Earlier: partial 2026-10-08 (`2026-10-07-2.md`): every bank path verified in the journal (home lever x2, finish "banked 5", fifth-death fail "banked 4 of 8"), compass lore correct in and out of trip, GO HOME board shows per-member haul, lives, unfinished and the half-loss line, and floor clears pay zero emeralds. Model mechanics pass. Failed checks: blood door never taken (lives:1 edge previewed only), last-life refusal untested, disconnect/grace untested, milestone title not reached (first:false finishes), migration join message unverified. New breaks filed: owner death purges the party run at any lives count (PD-167), quit and purge never settle the haul (PD-168), quit strands dungeon gear in the overworld (PD-169), ominous keys cannot pay the vault toll (PD-170). Player's one-sentence explain: "I can't tell yet, feels like it doesn't exist": the model works but is invisible; his proposal "pull the lever is the payout ritual" is logged in the report. Earlier: owed
- **Changed:** 2026-10-07 (`docs/decision-2026-10-07-haul-and-blood-doors.md`, PD-162): scrap rides in a per-trip haul that banks into the compass bar at home or a finish and is half lost to a failed dungeon; a side door costs the party a life; floors below your compass pay 1; no overlevel emeralds.
- **Do:** (1) As a migrated compass 12 player: read the compass lore (`Compass 12: 0/5 scrap to 13`) and the one time join message; clear a Copper Works floor and read the clear line (`+1 scrap (you are above this floor). Haul 1.`). (2) Take a side door and watch Lives drop; try it at Lives 1. (3) Go home with a haul. (4) Fail on purpose with a haul. (5) Party of two with different compasses. (6) Disconnect mid trip, rejoin inside the grace, then let a grace expire. (7) Count emeralds over the session and deaths per trip. (8) Ask the player to explain the system in one sentence.
- **Pass:** the lore, clear line, GO HOME board (`Haul N scrap`, `Lives N`, `Unfinished: ...`, footer while a haul is at risk) and door board (`Costs 1 life`) agree; going home says `Home. Banked N scrap: compass X, y/5 to Z.`; a failure says `Half your haul made it out: banked A of B scrap.`; the last life is never for sale; emeralds no longer come from floor clears.
- **Fail signs:** the player still asks which of two numbers is their level; a side door is taken without thought (the price of a life is too low); a failure feels like nothing; an absent member's haul vanishes.

## L55: the Astrolabe Room (wave B, 2026-10-09)
- Does a first-time player find the astrolabe unprompted, and does right-click turn to the next open act (sneak turns back)?
- Do the sign, doormat and bulb tell a dungeon and its state apart without reading the door screen? Does the oxidized bulb read as finished and the iron door as locked?
- Does opening a door still show the first room through the window, and does the lever still descend into the room that was previewed?
- Does the Endless Mine door appear at an end of the row when its compass is reached?
- Is a repeat finish paying half the emeralds, and does `/dungeon reroll` explain itself?
- With `hallEnabled` false, do the three random doors come back?

## L56: the scrap curve (wave C, 2026-10-09)
- Does the compass lore read `Compass N: p/price`, and does the price rise as the compass climbs?
- At compass 10 to 15, does one trip through a dungeon of the right act gain about 2 levels?
- Does a floor below your compass reading `+1 scrap (below your compass)` feel fair rather than an insult?
- Does a final floor pay a visible extra scrap, and a deeper act pay more than Act 1?
- Does an Endless Mine trip pay more the deeper it goes?

## L57: finales and uniforms (wave D, 2026-10-09)
- Copper Works: clearing the last floor\u0027s spawners brings the title, then The Foreman and his crew; does it land like the brood wave ("barely enough"), and does the boss bar read?
- An Act 1 dungeon (Infestation, Ossuary): is a wave with no elite enough of an ending? Does the pad refuse until it is dead, saying why?
- In a party, does the wave grow without becoming a slog? Does a won finale pay the extra chest?
- Copper Works mobs: do zombies and husks wear one copper piece and a copper sword, skeletons two pieces, and does nothing drop?
- Does the copper gear lengthen a Copper Works clear noticeably?

## L58: sculk and the vault (wave E, 2026-10-09)
- Does `Heard 2/4` on the sidebar read as stealth? Does a full meter (darkness, a wave, the Heard title) feel like the sculk answering?
- Hush Gallery: can the wool ring be crossed without filling the meter? Is the straight dripstone way noisy enough to tempt and punish? Does clearing it unheard pay +1 scrap?
- Ancient City: does the Warden wake on the second answer, and is crossing the final floor unheard a win?
- Barred Vault: does the trial key work on the vault with no explanation, and does an ominous floor's vault want the ominous key? Does it give the relief life?
- Ominous Bargain: is taking from the open chest still readable as the bargain?

## L59: wolves and Restless (wave F, 2026-10-09)
- Does Restless read from the door board ("the dead get up once. Burn them.")? Do you see the souls and hear the groan before the mob stands? Is fire reachable as counterplay?
- The Kennels: is it worth choosing before the Spawner Dungeon? Do the Kennel Run's stray wolves take bones without being hit? Does The Alpha's finale land?
- Wolf Hollow (Rootworks) and the Lost Dog: does clearing the camp tame the dog with no bones? Does a fourth wolf sit with the pack-full message?
- Does the Spawner Dungeon capstone now correctly wait for the Kennels?

## L60: ore (wave G, 2026-10-09)
- Endless Mine: do the walls hide coal and iron, and does digging find it? Is it richer four floors down?
- Deepslate: does `deep_shaft_landing` show enough ore on the landing?

## L61: the Kennels rework (2026-10-09)
- **Status:** failed 2026-10-10 (`2026-10-09-1.md`): the Kennels cannot be played through. A `kennels_tier_2` spawner queued wolves it could never place on a stone hall floor and soft-locked the gate (PD-194; admin removed it, grass+light proved the cause), and on the second trip fire spread burned the wooden kennel rooms (PD-195). Session ended on his call. Kennel Run, Guard Tower, the calm line, strays and The Alpha all remain owed.
- Do the Kennel Run wolves charge as you enter? Is the room survivable at Act 1 level?
- Guard Tower: can you run past, shoot the guard, and does "The pack goes quiet." fire when it falls? Does the ladder climb work with wolves biting?
- Do the Kennels spawners (pillagers, wolves, vindicators from tier 2) read as breeders and their dogs? Any raid banner, Bad Omen or patrol behaviour from the pillagers?
- Lost wolves (Wolf Hollow, Lost Dog): still friendly and tameable?
- Warden whelp (L58): does it skip the roar, is 0.3 speed about the player, does the Heard meter hold while it is out?
