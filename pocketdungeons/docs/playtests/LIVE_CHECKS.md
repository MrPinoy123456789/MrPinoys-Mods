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

- **Status:** owed
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

- **Status:** owed (every session, until it holds). Failed 2026-10-02-2.md: at least 8 asks hit the 45 s fallback, but the cause was `wait` and the shared cursor hiding `lemon ask` lines (PD-118), not slow replies. A direct `tail -F latest.log` monitor fixed it (every later ask answered in 4 to 6 s). Retest after PD-118 is fixed.
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

- **Status:** passed 2026-10-01 (`2026-10-01-2.md`): a full session on the new `lemonwatch.mjs` loop printed only clean `no new events (timeout)` lines while the server was up. Keep watching for one more session before retiring.
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

- **Status:** owed (2026-10-02-1.md: fixes were in the running build but not specifically steered to; hold-the-plate worked well, no commit/build failures, but `kennel_crossing`, `blaze_cellar`, `rotation_lock` and the blacksmith duplication were not directly observed).
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

- **Status:** partial, 2026-10-02-2.md: a dead-end fountain was used once (food boon, `fountain` event at 20:42 local) and worked; `echo_shards` events seen with sources `floor`, `interval` and `ordeal`. No stack-merge complaint. Failed on design: the player does not want the `floor` source (0.5 per floor); he wants one shard per full interval plus Ordeal chance only. Not seen: armour drop volume, ender chest as obsidian, the Guard's Bag, fountain reuse.
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

- **Status:** owed, 2026-10-02-2.md: not settled. Discovery failed: the player asked how to make a lectern and did not know the station picker hands it out. He then rejected the "Lock in" idea ("there's no lock in"): he wants the librarian to sell a Mending book. Run storage and the librarian itself were not seen.
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

- **Status:** owed, 2026-10-02-2.md: the player asked for a merchant ("I haven't seen a merchant yet"); with consent the run was steered with `dungeon admin bias the_store 20` but no Store showed before the wrap. Bias cleared. Retry with a bias held on door selection (`room_bias_hold`). 2026-10-03-2.md: bias x20 then x50 over about 7 floors, still no Store (PD-137); the vanilla trading screen was never seen. Still owed. PD-137 fixed 2026-10-03: the Store could never be placed (a one-door room with only the corridor role); it is a loot room now, so a bias should place it on most floors.
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

- **Status:** owed
- **Changed:** 2026-10-03: every gated room (rotation_lock, frame_lock, plate_pair, item_plate, flow_puzzle, hold_the_plate, sorting_floor, sensor_gallery and the knowledge rooms) is turned so its open side faces the cell you arrive from. sorting_floor and sensor_gallery used to be dead ends with a door into the wall; they are real pass-through gates now, with regenerated templates.
- **Do:** bias `rotation_lock`, then `sorting_floor` and `sensor_gallery` (ask first). Walk in from the previous room.
- **Pass:** the iron door is always on the far side; the room can be entered and solved; solving opens the way on.
- **Fail signs:** a closed iron door facing the way in, a gate that opens nothing, a sensor gallery door that never opens.

## L21. 2026-10-03: party members in the leader's rooms (PD-131, PD-132)

- **Status:** owed
- **Changed:** 2026-10-03 (owner request): a party member may open chests, use every station and place blocks in the leader's safe and staging rooms; a lobby-directory guest still may not build. Clicking a station with a block in hand opens it. The bag chest stays until the door is chosen, and clicking it with a bag already chosen names your bag.
- **Do:** with two players, have the companion craft and salvage holding a stack of blocks, place a block in the leader's room, and click the bag chest before and after picking.
- **Pass:** every station opens every time; blocks place; a bagless companion can always pick a bag after the leader did.
- **Fail signs:** a station that refuses, a placement refused for a companion, the bag chest missing for a bagless member.

## L22. 2026-10-03: balance batch

- **Status:** owed
- **Changed:** 2026-10-03: the first floor of an interval has at most 2 encounter cells (`firstFloorMaxEncounters`); chests carry fewer logs and more planks; a trial spawner ejects one key per party, and a vault opens once per party but pays one loot roll per member (owner rule; `PartyRewards`); the floor history rows are 20 percent bigger and the heading sits higher; a tamed spawner wolf counts as defeated; a chest leather cap salvages; Disenchant works with an empty bench; salvage gives the material back, measured against the reduced dungeon maximum: leather, iron and chainmail (iron), gold, copper, diamond and netherite (scrap) give 2 for a chestplate or leggings at 75 percent or more and 1 from 25 percent, other pieces 1 from 25 percent; wooden tools give a plank at 75 percent or more or 2 sticks from 25 percent; stone tools a cobblestone and shields a plank from 25 percent; kit can be scrapped (only the keystone is refused); scrapped gear pays no emeralds, only its materials and the XP a vanilla grindstone would give (none when unenchanted); the journal writes an inventory_snapshot (pack, run storage, ender chest, kept pack) at each floor clear, bank and exit.
- **Do:** watch floor 1 length and spawner count, chest contents, and a two-player vault. Ask whether the history board reads well.
- **Pass:** floor 1 shows 2 spawners or fewer and runs well under 300 s; in a party of two, one key per spawner and one vault opening that ejects about twice a solo vault; the board text fits its backdrop.
- **Fail signs:** history rows wider than the panel, two keys from one spawner, a vault the second player can still open, a party vault that pays like a solo one, floor 1 with 3 or more spawners.
