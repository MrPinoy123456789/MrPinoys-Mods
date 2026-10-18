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

## L16. 2026-10-02: diaries read in the book screen

- **Status:** owed
- **Changed:** 2026-10-02: the lodestone menu (and Lemon's menu) Diaries entries now open the vanilla book screen, with the pages in canonical order, instead of the dialog. The book is shown in the hand slot on the player's screen only, for the moment the screen opens; the server inventory is never touched (review F3).
- **Do:** with consent, ask the player to open Diaries from the menu holding something recognisable. Watch for the held item flickering, being lost or turning into a book.
- **Pass:** the book screen opens, closing it leaves the original item in hand.
- **Fail signs:** the held item replaced or duplicated, a diary book left in the hotbar, no screen opens (the client reading the old slot before the book arrives).

## L17. 2026-10-02: Lemon's diary archive

- **Status:** owed
- **Changed:** 2026-10-02: right-clicking Lemon holding a found diary book makes her keep it (the entry is recorded for good, she tells a tip, and holding every entry pays 8 emeralds at a dungeon finish since echo shards were retired, `lemon_archive` in the emeralds events). Books found before this change are not tagged; the Diaries menu entries still read in the book screen either way.
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

- **Status:** partial and failed (note 2026-10-10: `sensor_gallery` is retired and gone from the room list, the Hush Gallery replaced it, so only rotation_lock and sorting_floor remain to bias; sensor halls are L58), 2026-10-04-1.md: rotation_lock (frostworks) and sensor_gallery (frostworks, ender_archive) were entered with no closed door in the way, solves not watched; sorting_floor FAILED (PD-144: pre solved, two door sets, water everywhere, free sticks). Owed: a watched solve of each, and sorting_floor after the fix. Earlier: owed
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

## L24. 2026-10-05: the dungeon feels connected (Stage 1 hypothesis 1, A6)

- **Status:** partial pass 2026-10-05 (`2026-10-05-1.md`): asked directly after one Mineshaft trip, "yes, it felt good"; he had already named a floor unprompted ("The Main Drift"). Caveat: one resource dungeon only, and floors 1 and 2 were all generic halls (PD-149), so the pass leans on the structure rather than themed rooms.
- **Changed:** 2026-10-05: a trip is one dungeon. The first staging room offers three dungeons from the unlocked acts; later doors are the edges of the node just cleared; the floors share a main theme with at most one borrowed theme (`docs/DUNGEON_STRUCTURE_DESIGN.md` section 8).
- **Do:** discovery check, watch only. Play a full dungeon with the owner or a new player and do not explain the structure. Afterwards ask him to name the dungeon and its floors, and note unprompted comments on place and theme. Journal events: `dungeon_chosen`, `node_entered`, `edge_taken`.
- **Pass:** he names the dungeon and at least two floors, and says it feels like one place.
- **Fail signs:** "still feels random"; one theme over five floors reads as samey (51 of 61 rooms are generic, so watch for it); he cannot say which dungeon he was in.

## L29. 2026-10-05: the dungeon map and the first doors

- **Status:** partial 2026-10-05 (`2026-10-05-1.md`): the Spawner Dungeon stood on a door 1 or 2 slot on the first trip (pass on the offer rule). The map/door screen was seen but read as too much text (PD-151); comprehension unconfirmed.
- **Changed:** 2026-10-05: right-clicking the floor history board (or `/dungeon map`) shows the dungeon graph: current node, final node, edge costs, reachable nodes. Before a trip it lists the three dungeons on offer. The Spawner Dungeon stands on door 1 or 2 until cleared.
- **Do:** open the board on a fresh trip and mid trip; start several trips as a player who has not cleared Act 1.
- **Pass:** the map is read and understood; the Spawner Dungeon is on door 1 or 2 on every trip until cleared; after the clear the next act's capstone takes over.
- **Fail signs:** the map is never opened; the Spawner Dungeon on door 3 or missing; text wider than the dialog.

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
- **Changed:** 2026-10-05 (D13; since 2026-10-09 the Mine is a special door at an end of the Astrolabe Room row, see L55): after Act 1's capstone the first staging room shows the Endless Mine on door 3; layers open by act; the deepest floor shows on the history board.
- **Do:** with act 2 open, enter the Mine and go down to floors 6, 12 and 18 as the acts allow; read the sealed line and the history board.
- **Pass:** floors 1 to 5 enter freely; a sealed layer offers only HOME with the act named; the deepest floor persists across trips.
- **Fail signs:** the Mine on door 3 before act 2; a shaft that lets you past a sealed layer; the Mine hiding the capstone door.

## L39. 2026-10-06: diary entry 8 reads whole

- **Status:** owed
- **Changed:** 2026-10-06 (PD-157, `BookPages`): a long diary page is split over as many book pages as it needs, at a paragraph, then a sentence, then a word.
- **Do:** Finish the Mineshaft (or have the owner hand over entry 8, The First Pick) and open the book.
- **Pass:** Every sentence is there, none cut off at the foot of a page, no blank page.
- **Fail signs:** Text that stops at the bottom of page 1; a blank page.

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

## L45. 2026-10-06: resource doors show scrap and ore

- **Status:** partial 2026-10-07 (`2026-10-07-1.md`): the two-sheet board replaced this wording; on Copper Works the right sheet read "Boiler Hall, Explosive, 6 emeralds iron coal, lootx3" (emerald pay while overleveled, ore as plain words, loot count in green). But PD-161: the ore promise lied, all four floors had `nodes_total` 0. Mineshaft doors still unseen this build; em-dash-era "MINESHAFT - floor N of 3" title not rechecked. Earlier: owed
- **Changed:** 2026-10-06 (design items 1 and 6, `DungeonScreen.previewContent`): Mineshaft floors pay chart scrap like any floor, and the door board shows the ore as plain words.
- **Do:** Preview each Mineshaft door. Read the title, the name, the cyan line and the loot line. On a capstone door read the title width.
- **Pass:** Title `MINESHAFT - floor N of 3` (middle dot) in yellow; cyan line `2 scrap` then coal, copper, iron, gold; the rolls line (`3 rolls` at Loot 100%) in green; dim or dark on its own line. Titles fit the backdrop; the Wither's Keep and Spawner Dungeon titles measure about 8.4 and 9.3 blocks on an 8 block backdrop, so note how they look.
- **Fail signs:** No scrap on a resource door; labels like Rewards: or Resources:; a title that runs off the panel.

## L47. 2026-10-06: hidden ore is found by digging

- **Status:** owed
- **Changed:** 2026-10-06 (design item 7, `HiddenOrePlanner`): Mineshaft rock holds a few buried pockets of one to three ore blocks, counted as nodes.
- **Do:** Dig into the walls of mineshaft_seam and other Mineshaft rooms with the right pick.
- **Pass:** Some digs find a pocket of 1 to 3 ore; none shows from the corridor before it is dug; `nodes_total` counts it and `nodes_mined` rises when it is mined.
- **Fail signs:** Ore visible from inside the room before digging; ore in the wall ring.

## L49. 2026-10-06: five bags at the lectern

- **Status:** owed
- **Changed:** 2026-10-06 (L1, `BagIds.CORE`): the bag picker offers Guard, Ranger, Sapper, Lumberjack and Innkeeper. Pilgrim, Shepherd, Mason, Magician and Plumber sit behind the `extra_bags` content module, off by default.
- **Do:** Open the bag lectern on a fresh world and read the five blurbs. Kit out as each bag and check the kit matches the blurb.
- **Pass:** Exactly five bags; Sapper's kit holds a stone pickaxe, Lumberjack's an axe and oak logs, Innkeeper's a Rolling Pin named item; no module, no cut bag appears.
- **Fail signs:** Nine or ten bags; a missing kit item; Plumber in the list.

## L51. 2026-10-06: the stations that remain

- **Status:** owed, 2026-10-07-1.md: not exercised (no station used this session). Incidental: the safe room's station list showed crafting_table, damaged_anvil, furnace, grindstone, smithing_table and no enchanting table; if reroll lives at an enchanting table the home set may be missing it (unconfirmed, the list may only count placed blocks). Earlier: owed
- **Changed:** 2026-10-06 (J5, `RitualListener`, `StationTutorial`): four stations remain: Salvage at the grindstone (it was the anvil when this was written), Reroll at the enchanting table, the Home Vendor, the Bag Chest. No level gates. Gamble, Blacksmith and Lock In are gone.
- **Do:** Visit a station room in the dungeon and try each station. Put a scrapable drop on the anvil; put a gear piece on the enchanting table.
- **Pass:** Salvage opens regardless of level; the enchanting table rerolls; no gamble screen, no blacksmith villager, no lock-in prompt.
- **Fail signs:** A "too low level" refusal; the smithing table opening reroll; the Cube answering a use.

## L52. 2026-10-06: the librarian sells rolled gear

- **Status:** partial 2026-10-10 (`2026-10-09-1.md`): Mending offer confirmed live: `shop_purchase` enchanted_book from the home Librarian for 64 emeralds (the L52 price point). Buy-back used heavily same trip (40 rotten flesh, 8 lapis). Offer count and restock-on-homecoming still unverified. Earlier: partial 2026-10-08 (`2026-10-07-2.md`): he built the lectern (and enchanting table, grindstone, smithing table) in his safe room and bought rolled gear from the Librarian: `shop_purchase` diamond_leggings for 18 emeralds. Offer count, Mending price and restock-on-homecoming still unverified. Earlier: owed, 2026-10-07-1.md: not exercised
- **Changed:** 2026-10-06 (J5a, `LibrarianNPC`, `VendorMath`, `VendorStock`): the home librarian is a real villager whose offers are rolled gear tiers up to the owner's act, plus Mending for 64 emeralds, plus a buy-back of surplus drops.
- **Do:** Open the librarian after finishing an Act 1 floor and again after an Act 2 unlock. Buy a gear offer.
- **Pass:** Act 1 shows six offers (tiers I and II); Act 2 adds tier III for nine; never tier IV; every reroll on a homecoming changes the stock; Mending costs 64 emeralds.
- **Fail signs:** Vanilla librarian enchanted book trades; stock that never changes; tier IV before a capstone.

## L54. 2026-10-07: Haul and Blood Doors

- **Status:** partial 2026-10-08 (`2026-10-08-1.md`): the model is no longer invisible: three lever banks and three finishes all journaled correctly, and lives pressure landed ("It was definitely pressure" at lives 2, two rescues). New legibility breaks: "Haul 0 scrap" on the GO HOME board after a finish (PD-179) and "Home 4 chests" unreadable (PD-180). Still untested: blood door taken, last-life refusal, disconnect/grace, party compasses. Earlier: partial 2026-10-08 (`2026-10-07-2.md`): every bank path verified in the journal (home lever x2, finish "banked 5", fifth-death fail "banked 4 of 8"), compass lore correct in and out of trip, GO HOME board shows per-member haul, lives, unfinished and the half-loss line, and floor clears pay zero emeralds. Model mechanics pass. Failed checks: blood door never taken (lives:1 edge previewed only), last-life refusal untested, disconnect/grace untested, milestone title not reached (first:false finishes), migration join message unverified. New breaks filed: owner death purges the party run at any lives count (PD-167), quit and purge never settle the haul (PD-168), quit strands dungeon gear in the overworld (PD-169), ominous keys cannot pay the vault toll (PD-170). Player's one-sentence explain: "I can't tell yet, feels like it doesn't exist": the model works but is invisible; his proposal "pull the lever is the payout ritual" is logged in the report. Earlier: owed
- **Note 2026-10-10:** read the `/5` in the examples below as the live price; a level now costs `4 + floor(compass / 3)` scrap (L56), and the leaving rules are L64.
- **Changed:** 2026-10-07 (`docs/DECISIONS.md`, PD-162): scrap rides in a per-trip haul that banks into the compass bar at home or a finish and is half lost to a failed dungeon; a side door costs the party a life; floors below your compass pay 1; no overlevel emeralds.
- **Do:** (1) As a migrated compass 12 player: read the compass lore (`Compass 12: 0/5 scrap to 13`) and the one time join message; clear a Copper Works floor and read the clear line (`+1 scrap (you are above this floor). Haul 1.`). (2) Take a side door and watch Lives drop; try it at Lives 1. (3) Go home with a haul. (4) Fail on purpose with a haul. (5) Party of two with different compasses. (6) Disconnect mid trip, rejoin inside the grace, then let a grace expire. (7) Count emeralds over the session and deaths per trip. (8) Ask the player to explain the system in one sentence.
- **Pass:** the lore, clear line, GO HOME board (`Haul N scrap`, `Lives N`, `Unfinished: ...`, footer while a haul is at risk) and door board (`Costs 1 life`) agree; going home says `Home. Banked N scrap: compass X, y/5 to Z.`; a failure says `Half your haul made it out: banked A of B scrap.`; the last life is never for sale; emeralds no longer come from floor clears.
- **Fail signs:** the player still asks which of two numbers is their level; a side door is taken without thought (the price of a life is too low); a failure feels like nothing; an absent member's haul vanishes.

## L55: the Astrolabe Room (wave B, 2026-10-09)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Does a first-time player find the astrolabe unprompted, and does right-click turn to the next open act (sneak turns back)?
- Do the sign, doormat and bulb tell a dungeon and its state apart without reading the door screen? Does the oxidized bulb read as finished and the iron door as locked?
- Does opening a door still show the first room through the window, and does the lever still descend into the room that was previewed?
- Does the Endless Mine door appear at an end of the row when its compass is reached?
- Is a repeat finish paying half the emeralds, and does `/dungeon reroll` explain itself?
- With `hallEnabled` false, do the three random doors come back?
- Does the whole wall behind the doors read as one wide window onto the dungeon you picked? Does the DESCEND lever glint beside the selected door and nowhere else, and stop once the trip starts?
- Does a finished dungeon's sign say Play again?

## L56: the scrap curve (wave C, 2026-10-09)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Does the compass lore read `Compass N: p/price`, and does the price rise as the compass climbs?
- At compass 10 to 15, does one trip through a dungeon of the right act gain about 2 levels?
- Does a floor below your compass reading `+1 scrap (below your compass)` feel fair rather than an insult?
- Does a final floor pay a visible extra scrap, and a deeper act pay more than Act 1?
- Does an Endless Mine trip pay more the deeper it goes?
- Compass 1 costs 4 scrap and compass 10 costs 7: does the bar show the live price everywhere (lore, sidebar, bank line)?

## L57: finales and uniforms (wave D, 2026-10-09)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Copper Works: clearing the last floor's spawners brings the title, then The Foreman and his crew; does it land like the brood wave ("barely enough"), and does the boss bar read?
- An Act 1 dungeon (Infestation, Ossuary): is a wave with no elite enough of an ending? Does the pad refuse until it is dead, saying why?
- In a party, does the wave grow without becoming a slog? Does a won finale pay the extra chest?
- Copper Works mobs: do zombies and husks wear one copper piece and a copper sword, skeletons two pieces, and does nothing drop?
- Does the copper gear lengthen a Copper Works clear noticeably?

## L58: sculk and the vault (wave E, 2026-10-09)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Does `Heard 2/4` on the sidebar read as stealth? Does a full meter (darkness, a Warden whelp, the Heard title) feel like the sculk answering? Does a shriek in a room with sensors leave the meter alone, and does the meter hold still while the whelp is out?
- Hush Gallery: can the wool ring be crossed without filling the meter? Is the straight dripstone way noisy enough to tempt and punish? Does clearing it unheard pay +1 scrap?
- Ancient City: does the Warden wake on the second answer, and is crossing the final floor unheard a win?
- Barred Vault: does the trial key work on the vault with no explanation, and does an ominous floor's vault want the ominous key? Does it give the relief life?
- Ominous Bargain: is taking from the open chest still readable as the bargain?

## L59: wolves and Restless (wave F, 2026-10-09)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Does Restless read from the door board ("the dead get up once. Burn them.")? Do you see the souls and hear the groan before the mob stands? Is fire reachable as counterplay?
- The Kennels: is it worth choosing before the Spawner Dungeon? Does The Alpha's finale land?
- Wolf Hollow (Rootworks) and the Lost Dog: does clearing the camp tame the dog with no bones? Does a fourth wolf sit with the pack-full message?
- Does the Spawner Dungeon capstone now correctly wait for the Kennels?
- (Reworked 2026-10-09: the Kennels wolves are hostile and cannot be fed, so the taming checks above apply to Wolf Hollow and the Lost Dog only. The Kennels themselves are L61.)

## L60: ore (wave G, 2026-10-09)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Endless Mine: do the walls hide coal and iron, and does digging find it? Is it richer four floors down?
- Deepslate: does `deep_shaft_landing` show enough ore on the landing?

## L61: the Kennels rework (2026-10-09)
- **Status:** partial 2026-10-10 (`2026-10-10-1.md`): the Kennels now runs end to end (3 floors to Alpha's Den finish, wolves spawn on stone floors, rooms unburned: PD-194/PD-195 verified fixed). New breaks: spawner wolves are passive not hostile (PD-204), rising_lava door axis dumps the player into lava and its lamp is decoupled from the lever (PD-205), shell token claim flow is unintuitive (PD-206). Kennel Run charge, Guard Tower calm line, strays and The Alpha's feel still unjudged. Earlier: failed 2026-10-10 (`2026-10-09-1.md`): soft-locked floor and fire spread; session ended on his call.
- Do the Kennel Run wolves charge as you enter? Is the room survivable at Act 1 level?
- Guard Tower: can you run past, shoot the guard, and does "The pack goes quiet." fire when it falls? Does the ladder climb work with wolves biting?
- Do the Kennels spawners (pillagers, wolves, vindicators from tier 2) read as breeders and their dogs? Any raid banner, Bad Omen or patrol behaviour from the pillagers?
- Lost wolves (Wolf Hollow, Lost Dog): still friendly and tameable?
- Warden whelp (L58): does it skip the roar, is 0.3 speed about the player, does the Heard meter hold while it is out?

## L62: promised gear and carried keys (2026-10-10)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- Deepslate Collapsed Landing's door names an enchanted weapon beside its 2 emeralds; the copper chest holds that same piece after the clear.
- A vault key found on floor 2 opens a vault on floor 3 of the same dungeon, and is paid as emeralds when you bank (Home or finish), not at the floor clear.
- A failed dungeon pays half the key emeralds, matching the haul share.

## L63: the lodestone menu and bans (2026-10-09)
- **Status:** owed (built, never played)
- **Changed:** the Home menu is Start Dungeon, Manage Room, Inspect Compass, Manage Party, View Lobbies; in a dungeon Leave is last and asks first (Esc only closes). Manage Room holds name, public or private, shell, visitors and reset room. Inspect Compass holds diaries and reset compass. Manage Party holds invite, banned and, per person, Can build and Ban, with the people who played with you listed.
- **Do:** with consent, open each screen in turn from the wall lodestone, in your Home room and inside a dungeon. Ban a friend with two accounts, then try to invite them, have them join your party and visit your room. Unban.
- **Pass:** no screen is a dead end or closes on an unmade change; Invite and Banned show a plain notice when empty (no disconnect); Esc never leaves or resets anything; a banned player cannot be invited, cannot join and cannot visit; building rights show a star and need a confirm; found diaries and shells come first.
- **Fail signs:** a disconnect on opening a list, Leave as the Esc action, a banned player still getting in.

## L64: leaving, failing and the Home room (2026-10-09)
- **Status:** partial 2026-10-13 (`2026-10-13-1.md`): a mid-floor disconnect exercised the path
  for real (`leave_dungeon` reason `disconnect` on drowned_vault floor 1, the journal's last
  events). What we cannot see from outside: whether the half-haul banked and the run failed for
  him, and whether he lands in the Home room on rejoin. Verify on his next join. Multiplayer
  cases (rider/host leaves, fifth death) still unobserved.
- **Changed:** leaving a floor in progress for any reason (menu Leave, exit, disconnect, joining another party) fails the dungeon for the leaver, who keeps the fail share (50 percent) of their haul; leaving between floors cashes out in full; a host who leaves a floor fails it for everyone; a failed dungeon sends the party back to the Home room instead of out (PD-191 to PD-193).
- **Do:** with two accounts: a rider leaves mid-floor; a rider leaves between floors; the host leaves mid-floor; the party dies on its fifth life; a rider joins another party from inside a dungeon.
- **Pass:** the haul banks half, all, half for everyone, half, half in each case; after a failed dungeon the party stands together in the Home room with the doors ready and no lobby to reopen; the leaver is told what they kept.
- **Fail signs:** a full bank from a mid-floor leave, the party booted out after a fail, a rider stuck in the dungeon after the host left.

## L65: small changes of 2026-10-09
- **Status:** owed (built, never played)
- **Changed:** the librarian and the Deepslate and Frostworks merchants buy lapis for emeralds; trimmed armour can be scrapped at the grindstone; the floor history board says FAILED, QUIT or CLEARED only; the trip sidebar shows floor, lives, spawners, haul and compass (`/dungeon display off` hides it).
- **Do:** sell lapis; scrap a trimmed piece; finish, quit and fail one dungeon each and read the board; read the sidebar through a floor without prompting.
- **Pass:** lapis sells; the trimmed piece scraps; the board rows read as above; the player can say their floor and haul without looking at chat.
- **Fail signs:** a trimmed piece refused, a board row with another word, the sidebar hiding behind the omen bar or never repainting.

## L66: PD-204 to PD-206 fixes (2026-10-10)
- **Status:** owed (fixed in code 2026-10-10, all suites green; never played)
- Kennels: spawner wolves charge the player they can see (PD-204: `Instances.inDungeon` now matches namespaced ids); Lost Dog and guard-downed packs stay calm.
- rising_lava: the lava starts on the two side walls (never on the entrance or exit wall) and closes on the middle lane; the warning lamp sits beside the exit lever at the same height (PD-205).
- Shell tokens: right-click a held token to unlock the shell directly; the token lore says so (PD-206).
- **Fail signs:** a Kennels wolf ignoring the player, lava starting on a doorway wall, the lamp floating above the lever, a used token doing nothing.

## L67: Cow Pits: the Milking Parlour and Specimen 0 (2026-10-12)
- **Status:** owed (built, never played)

Play a full Cow Pits run and check:

- The dungeon is exactly three floors: Pen Gate, the Milking Parlour, the Big Barn.
- Parlour rooms read as a clinical dairy: white tile, barred stalls, numbered specimens
  ("Specimen 101" and friends). Churns (barrels) each hold 2 milk buckets; a bucket used on a
  stall cow does nothing.
- On the Big Barn, find the zero_bay room: Specimen 0 on a pedestal behind bars, the sign
  "Specimen 0 / (do not feed)" in front.
- Right click Specimen 0 with any food (not wheat, not a bucket): the food is consumed, a moo
  plays, the title "Specimen 0 wakes." shows, then about 3 seconds later the herd turns.
- Specimen 0 grows (default 3x), shows the boss bar, melee attacks; every tagged cow on the
  floor joins in. The pad stays shut until the herd is dead, then pays the finale chest.
- Leave the cow alone instead: the pad opens with no fight and no extra chest.
- Wheat is still refused on every cow, and baby cows still cannot appear.
- Note whether zero_bay actually appears on the final floor often enough.

## L68: Copper Works: the furnace clock, vents and vats (2026-10-12)
- **Status:** owed (built, never played)

Play a full Copper Works run and check:

- On floor one, a title line explains the clock ("The furnace is counting down."
  plus "Every cleared spawner buys it time."), and a timer rides the action bar
  counting down from 20:00.
- Clear a spawner: the clock jumps up by 60 seconds (config: copperClockBonusSeconds).
- The action bar timer turns dark red inside the last two minutes.
- Let the clock hit zero (or set copperClockMinutes low): nobody takes damage or
  dies; the title reads "The furnace blows." / "The Foreman does not wait." Ride
  to the final floor: the finale starts on its own with no five-second drum and
  the wave stands almost at once. Clearing more spawners there does not restart
  the countdown.
- With the clock blown, the vents pulse noticeably faster (about 2x).
- On master_furnace: six floor grates in a serpentine band hiss and smoke for a
  full two seconds, then burst into flame and ignite whoever stands on the tile
  (about three seconds of burn). The sweep order is the same every pulse.
  Standing on the south exit row never burns.
- Two water cauldrons stand in the room. Lure or knock the Foreman into one: he
  splashes, takes a point of drown damage each second, and carries Weakness plus
  Slowness for about four seconds. The first time, chat reads "The water bites
  him."
- The reward pad and the exit door stay reachable for the whole fight.
- Time the run before zero instead: the normal spawner-clear finale still works,
  countdown included.

## L69: Lush Caves: the swamp rooms (2026-10-12)
- **Status:** owed (built, never played)

Play a Lush Caves run (or two, the pool is random) and check floors 1 and 2:

- The swamp reads as a swamp: watch for the new rooms on Moss Gate, Glow Berry
  Hollow, Clay Burrow and Wolf Hollow.
- The witch haven: a plank hut on the marsh with a brewing cauldron inside. A
  witch lives in the hut; the room's trial spawner throws mostly witches (plus
  spiders; bogged on ominous). Fight is fair at floor 1.
- The clay shelves: terraced clay along one wall with vines down the step
  faces. Climb it (one block steps); a chest waits on the top shelf. The clay
  itself is diggable.
- The fungal hollow: two giant mushrooms (one red, one brown) over mycelium and
  podzol, a spore blossom under the red cap.
- The still pond: a dead end room of water with a low clay bar mid-pond holding
  a chest. Wade or swim to it; lily pads on the water.
- The hollow log: a fallen log the path runs straight through; a nook in the
  bark hides a chest.
- Bogged marsh turns up on Glow Berry Hollow runs: mud floor, bogged archers.
- The Sapping Den floor: still silverfish flavoured (burrow tunnels now, no
  infested wall), and it stays a deviation floor inside Lush Caves.

## L70: Floor 3, the young queen
- **Status:** owed (built, never played)

- [ ] Entering floor 3 of the Act 1 mine, the terminal room shows crack dust and a rumble, then a
      silverfish about one and a half times normal size named "The Princess" surfaces and fights.
- [ ] Mossy bricks around the room ripen and hatch silverfish adds while she fights.
- [ ] The exit pad refuses while she or any of her brood lives: "The guardian still stands. Finish it
      first."
- [ ] With the last fish dead the pad opens and the floor pays the tier A guardian table.

## L71: Floor 6, the packmaster
- **Status:** owed (built, never played)

- [ ] A named pillager ("The Packmaster") waits with a ring of wolves; a horn sounds on entry.
- [ ] The wolves are angry from the start, not just on hit.
- [ ] Killing the packmaster first quiets the pack ("The pack goes quiet") but the pad stays shut
      until the wolves are dead too.
- [ ] Tier B guardian loot on clear.

## L72: Floor 9, the coffins
- **Status:** owed (built, never played)

- [ ] Upright dark coffins stand around the terminal room; "Stone lids shift in the dark." plays.
- [ ] They crack open one at a time, never two at once, and each sleeper stands up and hunts.
- [ ] Breaking a coffin's blocks opens it early.
- [ ] The pad stays shut until every sleeper is dead; tier C guardian loot on clear.

## L73: Lap two
- **Status:** owed (built, never played)

- [ ] Floors 12, 15, 18 repeat queen, pack, coffins in order, noticeably harder: more bricks, more
      wolves, more coffins, faster tells (but tells never feel under two seconds).

## L74: Edges
- **Status:** owed (built, never played)

- [ ] Leaving and re-entering a guardian floor mid fight does not duplicate or lose mobs.
- [ ] `mineGuardianMobCap` lowered to a small number trims waves without errors.
- [ ] Act 2's mine still plays the plain placeholder wave (skeletons) on its guardian floors.

## L75: Floor 3, the Deep Sentry

- **Status:** partial 2026-10-13 (`2026-10-13-1.md`): the sentry spawned named on the terminal
  floor and was slain by the player (log: `Skeleton[The Deep Sentry] died` at 05:40:19), and the
  pad held to floor end. The meter never reached the whelp answer during his fight (he burst it
  down at compass 15, full diamond; PD-208), though whelps answered silent_deep sensors elsewhere
  on the floor. Tier A loot on clear not confirmed.
- [ ] Entering floor 3 of the Deep Mine (Act 2 endless mine), the terminal room holds sculk sensors
      and a named bow skeleton, "The Deep Sentry".
- [ ] Fighting fills an invisible meter: shooting a bow, taking hits and sprinting push it faster,
      and "The sculk stirs." shows as it nears full.
- [ ] A full meter plays a heartbeat and "The deep heard you.", then about three seconds later
      darkness lands and a small Warden (a whelp, not the Warden) comes for the nearest player.
- [ ] While the whelp is out the meter is quiet; a second answer only comes after it is gone.
- [ ] The exit pad refuses while the sentry or any answer lives: "The guardian still stands. Finish
      it first." Tier A guardian loot on clear.

## L76: Floor 6, the Foreman

- **Status:** partial 2026-10-13 (`2026-10-13-1.md`): the Foreman spawned named and was slain
  (log: `Vindicator[The Foreman] died` at 05:56:13), and the floor paid normally after. The
  player saw no vents or vat at all ("I didnt see any of that", "just a very fast pillager");
  unverified whether the decor placed but went unnoticed under a fast kill, or never placed
  (PD-208). Vents, sweep, vat weaken and tier B loot all unobserved.
- [ ] The terminal floor shows copper grates and one water cauldron; a named vindicator, "The
      Foreman", stands among them.
- [ ] A vent hisses and smokes for two seconds, then fires; standing on it ignites.
- [ ] The vents sweep the floor in a serpentine order, faster the deeper the guardian.
- [ ] Pushing or kiting the Foreman into the cauldron splashes, weakens and slows him ("The vat
      weakens him. Keep him in it." shows once).
- [ ] When he dies the vents stop and the floor looks untouched (no leftover grates or cauldron);
      tier B guardian loot on clear.

## L77: Floor 9, the Frostbite

- **Status:** owed 2026-10-13 (`2026-10-13-1.md`): the player banked after floor 6 and has never
  descended past F6 in either mine. Not observed.
- [ ] Lit campfires stand around the terminal room and a named stray, "The Frostbite", hunts with
      her kin.
- [ ] A wind sound and "The cold is coming. Find a fire." play, then about three seconds later the
      freeze starts: the blue overlay builds on anyone away from a fire.
- [ ] Standing near a lit campfire thaws fast; breaking or dousing a fire removes that shelter.
- [ ] The cold cannot kill on its own (the same health floor as the Frostworks finale).
- [ ] Killing the last stray lifts the cold at once and the pad opens; tier C guardian loot.

## L78: Lap two and edges
- **Status:** owed (built, never played)

- [ ] Floors 12, 15, 18 repeat Sentry, Foreman, Frostbite noticeably harder: the meter fills sooner,
      more vents firing faster, more strays, faster tells (never under about two seconds).
- [ ] Leaving and re-entering a guardian floor mid fight does not duplicate or lose mobs, and does
      not restart a tell that already ran.
- [ ] `mineGuardianMobCap` lowered keeps the wave sizes sane (the sentry's answer waits instead of
      spawning over the cap).
- [ ] Act 1's mine still plays its own guardians (queen, packmaster, coffins) untouched.
- [ ] The Frostbite is never announced, named or described as a Warden anywhere.

## L79: The Brood Chamber
- **Status:** owed (built, never played)

- [ ] The final floor of the Spawner Dungeon is the Brood Chamber: four caged spawners, and the
      exit pad refuses entry to the next floor while any cage stands.
- [ ] Each cage freed releases exactly one earlier Act 1 mechanic, and the tell names it: coffins
      cracking ("Stone lids shift in the dark."), creeping bricks ("The mortar twitches."), the
      dark ("The beams creak." then "Light a torch."), then the drip ("Something drips from
      above.").
- [ ] A cage doused by the wave's exhaust rule frees its mechanic the same as a broken one.
- [ ] Breaking cages in different orders changes the fight: whichever cage goes first frees its
      mechanic first, and every earlier mechanic keeps running until the floor ends.
- [ ] The dark phase spares the torches near the pad and the room's middle; lights come back when
      the floor ends (win or loss).

## L80: The Brood Mother
- **Status:** owed (built, never played)

- [ ] The fourth cage calls "The Brood Mother hauls herself out of the dark." plus a title card,
      and a scaled up cave spider with a red boss bar arrives beside the last wave.
- [ ] Her bar fills the screen top, tracks her health, and vanishes when she dies.
- [ ] At half health a raid horn plays and "The Brood Mother shrieks for her brood!"; a few
      seconds later every surviving brood mob grows, glows and turns on the party. If she dies
      during the windup the pack stays its old size.
- [ ] Once she is up, the drip falls about twice as fast.
- [ ] The exit pad refuses until the mother and every tagged brood mob are dead, including any
      that chased a player out of the room.

## L81: Edges and tuning
- **Status:** owed (built, never played)

- [ ] A full chamber does not pile mobs over `broodMobCap`: new coffins, fish, spiders and wave
      mobs wait for room rather than crowding in.
- [ ] Leaving and re-entering the floor mid fight does not duplicate the mother, the bar, or any
      phase's mobs.
- [ ] The chamber works solo and at a party of four or more; her health and the headcounts scale
      but the fight stays readable.
- [ ] The other three capstones (Ancient City Warden, Wither Keep, Herobrine) play unchanged.

## L82: T10. Affix quick changes (2026-10-11)
- **Status:** owed (built, never played)


- [ ] Explosive: step on a floor mine. The fuse is a little longer (25 ticks, about 1.25 s) before the blast; you can still step off in time.
- [ ] Silenced: on a Silenced run, eat or drink three consumables. The action bar shows "Silenced: 1/3", "Silenced: 2/3", then "Silenced: 3/3" with the wave. Only the user sees it; a party mate sees nothing.
- [ ] Cooked: clear a floor that carries Cooked. The big text shows the usual "GO HOME or DESCEND" and, a beat later, "Cooked: ominous loot". Clear a floor without Cooked: no extra line.
- [ ] Restless: the affix does not appear before compass 5.
- [ ] Voided: first compass 45 trip with Voided. No puzzle room (Frame Lock, Rising Lava, Ropewalk and so on) has its floor ripped open; holes appear only in plain halls and open fight or loot rooms.

## L83: Overclocked
- **Status:** owed (built, never played)

1. Take a door whose floor carries Overclocked. The floor title lists OVERCLOCKED, then "Under 6:00: +1 scrap".
2. Clear the floor in under 6 minutes of the commit. The pay line reads one scrap more than the same floor without the affix (act and step as dealt). The haul agrees.
3. Repeat and clear it slower than 6 minutes. The pay is the plain dealt step.
4. Spawners still come back fast (cooldown factor 0.4 unchanged).
5. Check the knobs `overclockedParSeconds` and `overclockedBonus` in `config/pocketdungeons.json`. A value there overrides the default. The affix blurb names 6 minutes and +1 in fixed words, so change the blurb with the knob.

## L84: Loaded
- **Status:** owed (built, never played)

1. On a Loaded floor, find the tool chest in a loot room. Walk up to it: nothing happens until you open it.
2. Open it. You hear the soul-escape sound, smoke puffs at several spots near the chest, and "Trapped." shows red on the action bar. About a second later 3 mobs (zombies and skeletons) stand at those spots and come for you. The chest still holds its tools and bread or torches.
3. Close it and open it again: no second ambush.
4. Party of 2 and 3: 4 and 5 mobs.
5. The mobs stand in the room corner by the chest, never in the middle cross where the doors line up. Walk out of the room by every door: none is blocked.
6. The ambush mobs drop nothing.

## L85: Restless widened and Blackout (roadmap T12, 2026-10-11)
- **Status:** owed (built, never played)
- **Changed:** Restless now raises any fallen mob, not only the undead, as the floor theme's stand-in (wolves as skeletons, spiders as cave spiders, Frostworks as strays, drowned in the Drowned Vault and Prismarine, and so on). Bosses, elites, finale and wave mobs, pets and a mob that has already risen never rise; fire keeps them down; the 2 second groan and soul puff stay. New affix Blackout (short name Blkt, level 0): about 30 percent of the plain rooms lose all their lights. The entrance, the exit, rooms that need light and the lever Ordeal rooms stay lit.
- **Do:** run a floor with Restless (a Kennels or Ossuary floor is quickest) and kill a wolf or spider away from fire; then run a floor with Blackout and walk the whole floor.
- **Pass:** the groan and souls show, and 2 seconds later a skeleton or cave spider stands where the wolf or spider fell; it does not rise again; a burning one stays down; finale wave mobs and the boss never rise. Under Blackout some rooms are fully dark, the same rooms every time the same floor is stamped, and the entrance, exit, lever Ordeal rooms and kennel rooms stay lit; the affix shows as Blackout on the keystone and the title.
- **Fail signs:** a boss or finale wave mob rising, a tamed wolf rising, a dark lever Ordeal room (lever or lamp unreadable), a dark exit, a risen mob that rises again, Blackout darkening nothing or everything.

## L86: RM-T13 data fixes (Deepslate depth, Lush Caves and Copper Works ore)
- **Status:** owed (built, never played)

- Deepslate is four floors: Shaft Head, then Lantern Stairs or Sculk Shallows, then Deep Quarry, Cracked Barracks or Tuff Cellars, then The Silent Deep. Each layer 3 door board leads straight to the Silent Deep, and a full run takes four floors.
- Tuff Cellars can roll the Deep Dark Landing (its note now says a shrieker waits below); the room still appears and the shrieker works there.
- Lush Caves: mining the walls on a floor turns up diggable sand and clay now and then (one to two buried pockets per floor), plus a little iron.
- Copper Works: a floor with no ore room still shows coal and iron buried in the walls (one to two pockets); the board's ore promise matches what the floor holds.

## L87: Mineshaft dark phase (roadmap T20, 2026-10-11)
- **Status:** owed (built, never played)
- **Changed:** The Deep Face finale now has a dark phase. When half the wave is dead the beams creak (sound plus the action bar line "The beams creak."); 3 seconds later (knob `mineshaftDarkTellSeconds`, never under 2) every torch and lantern on the floor goes out, the action bar says "Light a torch.", and a second batch of glowing cave spiders joins (knob `mineshaftDarkSpiders`, 3 for a party of one, +50 percent per extra member, at most 10). The pad stays shut until they are dead. Lights come back when the finale is won or the floor ends. Spared: lights within 4 blocks of the exit pad, rooms that need light, lever Ordeal rooms.
- **Do:** run a Mineshaft to The Deep Face, clear the spawners, kill half the wave, then fight on in the dark. Place your own torch. Win, and look again.
- **Pass:** the creak and action bar come first with a clear pause; then the shaft goes dark in one go, the pad area stays lit, spiders are visible by their glow, a placed torch lights its patch, the fight is survivable with poison at party sizes 1 to 4, and every light is back after the win.
- **Fail signs:** no pause before the dark, a dark pad or lever, spiders invisible or a flood of them, poison deaths with no counterplay, a party with no torches stuck in a fight it cannot see, lights missing after the win or after leaving.

## L88: Ossuary coffins finale (roadmap T21, 2026-10-11)
- **Status:** owed (built, never played)
- **Changed:** The Last Rest (Ossuary final floor) now holds six sealed coffins (polished blackstone, 2 high, 3 long, a chiseled cap at the head). When the floor's spawners are cleared and the countdown ends, the finale wave lies in the coffins instead of standing up around you: the skeletons and zombies rise one coffin at a time. A coffin cracks when you come within 4 blocks, or 15 seconds after the last one cracked (a skipped coffin opens behind you). Each coffin cracks for 2.5 seconds (stone crack sound climbing in pitch, dust off the lid) before the lid breaks and its mobs stand up and come for you. At most 6 coffin mobs are awake at once; two coffins never crack together (3 seconds apart at least). A solo player faces 7 mobs in 6 coffins (one holds two).
- **Do:** play an Ossuary run to the final floor (Compass 4), clear the spawner, and walk through the hall. Then try again and stand still in a corner, and again with a friend. Mine a lid with a pickaxe before the wave. Kill the last mob.
- **Pass:** the lids are visible before the wave and nothing hostile shows before the countdown ends. Coffins open one at a time with a clear tell. A coffin you ignore still opens within about 15 seconds of the previous one. Mining a lid frees its mobs at once. The pad stays shut until every coffin mob is dead and opens after; no coffin mob rises a second time with Restless on the floor. Every door lane is walkable and the exit hall is untouched. Leaving the floor and coming back (a new stamp) shows sealed coffins again.
- **Fail signs:** all coffins opening together, a coffin that never opens, visible or audible sleepers before their lid breaks, a mob stuck inside a coffin (check a coffin with two occupants), the pad opening with a coffin still sealed, a door lane blocked by a coffin, a crash or log error from OssuaryFinale, a coffin mob dying to a fall or suffocation.
- **Knobs:** code constants in `OssuaryRules` (`MAX_COFFINS` 8, `COFFIN_WAVE_CAP` 24, `AWAKE_CAP` 6, `WAKE_RADIUS` 4.0, `SKIP_DELAY_TICKS` 300, `MIN_GAP_TICKS` 60, `CRACK_TICKS` 50). They are not config keys; change the number and rebuild.

## L89: Kennels handler (roadmap T22, 2026-10-12)
- **Status:** owed (built, never played)
- **Changed:** In the Kennels finale the pillager is now "The Handler". When the Alpha is at half health the handler raises its arm and a horn sounds; 5 seconds later the wolves of the pack (the Alpha too) grow to 1.4 times size, turn angry and glow red. Killing the handler during the 5 seconds stops it; killing it afterwards shrinks the pack back and ends the glow.
- **Do:** run the Kennels to The Alpha's Den, clear the spawners, and hurt the Alpha to half. Try three runs: let the windup finish; kill the handler in the windup; kill the handler after the pack has grown. Also try killing the handler before the Alpha reaches half.
- **Pass:** the arm-up pose and the horn are clear a full 5 seconds before anything changes; "Kill the handler!" shows on the action bar; the red outline reads through walls and is red, not gold; grown wolves still path through the den doors and gaps and can hit you; a cancel leaves the pack normal; a late kill shrinks it and the outline goes; with the handler already dead nothing happens; no red outline or team left after leaving or finishing the floor.
- **Fail signs:** the pose not showing on the pillager, a horn too quiet to notice, wolves stuck in doorways at 1.4, the outline staying after the shrink, wolves going passive, the handler rising as a Restless mob, a team listed under `/team list` after the run.

## L90: RM-T23 Lush Caves potion drip and giant slime
- **Status:** owed (built, never played)

- Floors 1 and 2 of a Lush Caves run drip nothing, not even after a long wait. From floor 3 a splash potion falls on you about every 45 seconds (varies by a third either way); floor 4 is a little faster.
- The fall reads: you hear a brewing sound, see witch sparks and drips on the spot you stand on, and the potion comes down slowly over about 2 seconds. Stepping a few blocks aside makes it miss you. A low ceiling (under 3 blocks of air) skips the drip.
- Fairness at compass 1: about 7 in 10 brews are harmful (poison, slowness, weakness, nausea), the rest good (regeneration, speed, jump boost). Nothing hurts instantly and nothing kills; poison stops at half a heart. Judge whether 70 percent harmful feels fair or needs a lower `potionDripHarmfulPercent`.
- Nothing drips in the entrance room, on or near the lodestone pad, in the safe or staging rooms, or while someone stands on the pad.
- The final floor, after the spawners are cleared: "stirs" title, then one giant slime (The Great Drip) with a boss bar, and the drip is about twice as frequent for the fight. Check it fits the room and is not stuck in a wall.
- Kill the giant slime: it splits into twice the usual number of slimes (4 to 8 half size slimes instead of 2 to 4). Kill one of those: it splits the normal way. The pad message keeps counting down slimes left, and the pad opens only when every slime is dead; the drip goes back to normal after the win.
- A real slime death: confirm the pad never opens in the second after the giant slime dies (before its children appear), and that no slimes are left behind, invisible or uncounted.
- Leave the floor mid fight (or end the run): no potions keep falling and no slimes remain on the next floor.

## L91: Infestation: creeping bricks and The Queen (2026-10-12)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- **Changed:** RM-T24. In the Infestation, Chewed Corridors rooms (burrow tunnel, gnawed vein) and the Silverfish Nests slime pit grow creeping bricks when a member walks in: a mossy stone brick appears, turns to stone brick, then infested stone brick, then bursts into one silverfish. The final floor's wave is now one giant silverfish, The Queen, who burrows, sends up bricks that burst into silverfish, and rises again.
- **Do:** 1 account (2 to feel the party scaling). Run the Infestation. Walk into a gnawed vein or burrow tunnel and watch the first brick. Smash one with a pickaxe at each stage. Let one burst. Reach The Queen's Nest, clear its spawners, and fight The Queen through at least two burrows. Leave one brick alone in the adds phase and kill The Queen with silverfish still alive. Leave the floor mid adds phase once (quit door) and re-enter the lobby.
- **Pass:** A brick is readable by sound and cracks at each change (moss thud, stone knock, silverfish chitter, shaking and rattling just before the burst). A smashed brick leaves air, no item, no silverfish. A burst leaves no item and one silverfish. No more than the room cap of bricks grows at once. The Queen is large, named, with a boss bar. About 3 seconds before she burrows she rears up with a roar and dust. She vanishes under the floor in a dust puff and cannot be hurt. The floor cracks for about 2 seconds, then bricks appear around the arena, none on a member or on the pad. About 2 seconds before she returns the ground heaves at the spot she rises. The pad stays shut until she and every silverfish are dead. Killing her removes the bricks and the silverfish and opens the pad.
- **Fail signs:** A brick that never changes, changes with no sound, or drops an item. A silverfish from a brick you smashed. Bricks stacking up past the cap or blocking a door lane or the pad. The Queen burrowing with no warning, taking hits while burrowed, visible while burrowed, stuck underground after the adds phase, or surfacing inside a wall. Silverfish piling past the cap. The finale ending with The Queen alive, or the pad opening with bricks or silverfish left. Blocks left mossy or infested after leaving the floor. A silverfish hiding in the stone and vanishing (it counts as dead). Adds phase feeling unfair: tune `infestationBricksPerPhase`, `infestationSilverfishCap`, `infestationAddsMaxSeconds`, `creepingMossySeconds`.

## L92: Silent Deep Hush rule (roadmap T31, 2026-10-12)
- **Status:** owed (built, never played)
- **Changed:** The Silent Deep room now holds seven sculk sensors and one shrieker. Once its spawner is cleared and the finale starts, the room's Heard meter (the sidebar line) runs the fight: bow shots, hits you take, deaths of the wave, sprinting, sensor pulses and, slowly, time all fill it (ceiling 16). At three quarters the action bar says the sculk stirs. When it fills, a heartbeat and "The deep heard you." run for 3 seconds, then the shrieker screams, darkness settles and one Warden whelp comes. The meter is deaf while the whelp is out, then starts again. Never the real Warden. The Deep Sentry and the data wave are unchanged. Winning removes the whelp. A final floor that drew no Silent Deep room runs the plain finale.
- **Do:** one account, then two. Reach the Silent Deep from any layer 3 floor, clear the spawner, then fight three ways: (1) sneak and use melee only, (2) sprint around the room shooting a bow, (3) stand still and let the wave come. Watch the Heard line on the sidebar. Leave the knobs (hushCeiling, hushPulseRise, hushBowRise, hushHitRise, hushDeathRise, hushSprintRise, hushQuietSeconds, hushWhelps) at their defaults first.
- **Pass:** the room reads as listening on sight (sensors on the plinth corners and out on the floor, the shrieker on the obelisk, no sensor in a door lane or on a spawn point); a quiet fight rises slowly and usually stays under the ceiling, a loud one fills it within about half a minute; the warning line comes before the fill; the heartbeat and line are clear a full 3 seconds before the dark; one whelp for a party of one is survivable with a torch and a door; the meter does not rise while the whelp is out; the pad opens after the last mob dies and the whelp is gone; nothing blocks the way to the pad.
- **Fail signs:** the answer with no tell, darkness before the heartbeat (the shrieker can shriek on its own at close range, which darkens in vanilla), a whelp that cannot be escaped or killed, two answers back to back, the meter rising during the countdown, the whelp surviving the win, a sensor on a spawn point or in a door lane, a Warden that is not a small whelp, a ceiling that fills in the first ten seconds or never.

## L93: Frostworks: The Big Freeze cold and braziers
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- **Changed:** roadmap T33. When the Frostworks finale wave stands, The Big Freeze turns cold: after a 3 second tell (wind sound, action bar "The cold is coming. Find a fire.") every member inside the room gains vanilla freeze ticks (blue overlay, slowdown, then 1 damage every 2 seconds when fully frozen). Five lit campfires on two block plinths are the warm spots; within 4 blocks of one a member thaws in about 2 seconds. A member at 1 heart is held under the freeze line. Two ice pillars give cover from Jack Frost's arrows. The cold ends at once on the win and when the floor ends.
- **Do:** one account. Start a Frostworks run and reach the final floor with a `big_freeze` room (use a seed or `/dungeon admin` to force it; the room only rolls on a straight cell). Clear the floor's spawners, stand INSIDE The Big Freeze when the wave spawns. Stand away from the braziers for 30 seconds, then walk to one. Repeat with health at 1 heart (let a zombie hit you, or `/damage`). Then kill the wave. Try the knobs `frostColdTicksPerSecond` (default 6) and `frostBrazierRadius` (default 4) if it feels wrong. Also try once with leather boots on.
- **Pass:** the wind and the action bar line come at least 2 seconds before the screen edge frosts; the overlay builds slowly and the walk slows; the five campfires are easy to find from anywhere in the room and the flames cannot be stood in; standing by one clears the overlay in about 2 seconds; at 1 heart the cold never kills you; the overlay is gone the instant the last mob dies; the pad opens as normal.
- **Fail signs:** no tell, or the overlay starting before the wind; a brazier you can climb onto or a flame you can be burned by; braziers missing or unlit after stamping (then the log says "no cold this run"); the whole room warm (radius too big) or no spot warm; death from freeze damage; the overlay lingering after the win or after leaving; a party that fights outside the room never feeling the cold (open question: the wave spawns near the first member, wherever they are).

## L94: RM T34: Haunts, The Counted in the Ossuary (2026-10-11)
- **Status:** owed (built, tests green; never played)
- **Changed:** the Ossuary (3 floors, compass 4 and up) is haunted. The floor title adds "Something walks here." A skeleton called The Counted wakes after about 90 seconds on the floor and moves only while nobody looks at it. Floor 1: it is seen at the edge of a room, never attacks, and vanishes when approached (three times, then it gives up for the floor). Floor 2: it reaches you and misses. Floor 3: it strikes for 3 health and slows you for 3 seconds, then stays away 40 seconds. The first tell writes "It moves when you look away." to chat and the action bar, once per trip. Its diary page (Entry 27, "The Counted") comes with the Ossuary page on the first finish. Never in the entrance room, the pad room, an Ordeal room or a finale. A killed Counted stays gone for the floor and drops nothing. Knobs: `haunts*` in `pocketdungeons.json`.
- **Do:** run the Ossuary three floors in one trip with a compass of 4 or more, floor 1 first. On each floor stay in the open for two minutes. Once it shows, look at it, look away, look back; walk toward it; stand in a doorway with it in the next room.
- **Pass:** the title shows the line on all three floors; the blue soul flames and the bone knock are noticeable in the dark rooms (dim floors) before it is close; the chat line appears once; on floor 1 it never hits and goes when you come within about 7 blocks; on floor 2 you hear a swipe and take nothing; on floor 3 the hit is 3 health with slowness and never kills a player at 1 to 3 health; it freezes the moment it is on your screen and creeps when it is not, including when you look at it only through a doorway or across a corner; it never appears in the first room or the pad room; it is gone when the floor ends.
- **Fail signs:** the tell is invisible in a dim room; it moves while you watch it; it stays frozen when only a wall is between you; it hits on floor 1 or 2; a hit takes more than the knob; it follows you into the pad room; it is still standing on the next floor; it drops bones or xp.
- **Tune:** `hauntLookConeDegrees` (60 is the half angle of the view that freezes it; raise it if it creeps while visible at the screen edge), `hauntWakePressure` (90 seconds), `hauntRetreatSeconds` (40), `hauntStrikeDamage` (3), `hauntTellSeconds` (2).

## L95: Endless Mine per act (2026-10-12)
- **Status:** partial 2026-10-13 (`2026-10-13-1.md`): the Deep Mine was directly reachable and
  ran floors 1-6; it read as distinct on sight ("Different rooms than what I remember").
  Guardians on F3 and F6 spawned and gated the pad (L75/L76 partial). Tier barrels not opened
  on camera; F9+ unobserved; the act-gated door offer itself was not watched from the Astrolabe.
- **Changed:** T40. Each act has its own Endless Mine (Act 1 `endless_mine`, Act 2 the Deep Mine). A guardian waits on every third floor (placeholder wave of zombies, or skeletons in the Deep Mine); its floor pays tier A, then B, then C for every guardian after. Clearing floor 3 counts toward opening the next act. No layers, no sealed shaft.
- **Do:** One account at compass 3 or more. Open the Astrolabe Room, take the Endless Mine door, clear floors 1 to 9 (go home and return as needed). Then, with Act 2 open and compass 6, turn the astrolabe to Act 2 and enter the Deep Mine. Also load a pre-change save that had passed floor 5 or 11 and check its acts.
- **Pass:** Floors 1, 2, 4, 5 have no wave; floors 3, 6, 9 do and the pad refuses ("The guardian still stands") until the wave is dead. The barrel on floor 3 holds only coal, iron, copper (and cobblestone, torch); floor 6 adds gold and lapis; floor 9 adds redstone and sometimes a diamond. Checkpoint line names the guardian and tier. Clearing floor 3 of the Act 1 mine counts as the Mine leg of Act 1. The Deep Mine looks like deepslate with tuff and dripstone and opens in Act 2. An old save keeps its acts.
- **Fail signs:** A wave on a non-third floor, a guardian that cannot be reached or never shuts the pad, a barrel with the wrong tier, a mine door missing from the Act 2 astrolabe, an old save losing an act, a sealed-shaft message, a stuck or empty Deep Mine floor.

## L96: Wager: Loot percent, the extra copper chest piece, Blood (2026-10-12)
- **Status:** partial 2026-10-13 (`2026-10-13-1.md`): the Loot percent line is on the board and
  was read, but read as ambiguous against `loot x3` ("What is loot 250% and loot x3?", PD-207).
  Fixed in code same day: Loot n% is now the multiplier the barrel pays (round((base + depth)
  x n/100) rolls), and the green line reads the scaled count as `8 rolls`, so one loot system.
  Extra copper chest piece and Blood door unobserved.
- **Changed:** T50, amended 2026-10-13 (docs/DECISIONS.md). Every door board and the floor title end the affix list with "Loot n%" (100 plus 50 per wager point; a floor with no affixes reads "Loot 100%", white at 100, gold above on the board). The percent is the floor's loot multiplier: the barrel pays round((base + depth bonus) x n/100) rolls, and the board's green line shows that count as "N rolls". A floor whose affixes wager something pays one extra piece (weapon, tool, armour or material) into its copper chest, and the door board names it beside the other things the floor gives. Blood is a new affix that takes one life when the door is committed (never the last life).
- **Do:** Solo or two accounts, a compass at 5 or above so doors carry affixes (compass 25 and 45 give two and three affixes). Open the Astrolabe Room or a staging room, right-click each of the three doors and read the board. Note the Loot percent, the "N rolls" line (it should be the percent of the base 3 plus depth, rounded), and the extra piece it names. Commit a door with at least two affixes, clear the floor, count the barrel rolls and open the copper chest. Then find a Blood door (it is one of the seeded affixes, so it comes with the compass level): commit it with 5 lives, again at 2 lives, and with 1 life.
- **Pass:** Every board shows "Loot n%" beside the affix names and in the affix line of the deal sheet, and "N rolls" where "loot x3" used to sit; the two numbers agree (Loot 250% on base 3 reads 8 rolls). The floor title shows the percent as the last line. The barrel holds the rolls the board promised. The copper chest holds the exact piece the board named (same item, same enchants), and a "Loot 100%" floor pays no extra piece. High Loot floors name a better tier piece far more often than low ones, and never above the dungeon loot band (Act 1 stays stone and iron). The Blood door board says "Costs 1 life"; committing takes one life ("paid a life for the door", Lives drops by 1); at 1 life the lever refuses with the lives line and nothing is spent. The piece reads as worth the risk at Loot 400% and above.
- **Fail signs:** The board still says "loot x3" or shows two different loot wordings; the barrel count does not match the rolls line or the percent; the chest piece differs from the board (a different item or no enchant where the board said enchanted); a "Loot 100%" floor pays an extra piece; a "wager" word or a raw number appears; a tier above the band (netherite in Act 1); Blood takes a life twice, or takes the last life; every door on a floor is refused because all three carry Blood at low lives (a trap: tell the owner); the title line is missing or overlaps another title.

## L97: Heist floor: the vault cracks and wakes (2026-10-13)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- **Changed:** Sunken Treasury floors (act 3) hide their spawners behind reinforced deepslate and open quiet. A heavy core on a gold plinth in the last room is the vault: hold a use on it for 5 seconds (`heistCoreChannelSeconds`) and the floor wakes, the stand-ins restore every spawner with its config intact, and a drowned crew rises around the core. Waxed oxidized copper strongboxes (2, plus one per two members past the first) open as barrels paying the floor's tier chest table. The pad refuses "The vault is sealed." until the crack.
- **Do:** with consent, enter the Sunken Treasury (act 3, compass 13). Walk a floor before touching the core. Channel it once cleanly, then take a hit mid-channel and start again. Open every strongbox after the crack and count the crew.
- **Pass:** no spawner runs while the vault sleeps; a hit or stepping more than about 4 blocks off the core resets the channel ("Interrupted."); on the crack the stand-ins restore the spawners with their stamped configs (the right mobs and keys), each strongbox pays once, the crew arrives in a ring (`heistCrewBase` 2 plus one per member; an ominous floor raises the same wave twice) and the pad opens with "The vault is open."
- **Fail signs:** a live spawner before the crack; a strongbox that pays twice or never; a crew of the wrong size; a pad still sealed after the crack; a floor that never placed a core and still hid its spawners (that should have fallen back to an ordinary spawner floor).

## L98: Claim floor: the seam fights back (2026-10-13)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- **Changed:** Gilded Seam floors (act 4) hide gilded blackstone veins: `claimQuota` (3) demanded plus `claimExtraVeins` (3) more for digging past it. A right-click claims a vein: it spends to plain blackstone, the bar's "Veins n/3" rises, Tremor rises by one and the floor growls. From Tremor `claimWaveTremor` (4) every further claim raises a hoglin pack around the vein; at `claimHunterTremor` (6) the floor sends The Assayer once, a named piglin brute at double scaled health near the last room. Veins claimed past the quota each add one barrel roll at the bank.
- **Do:** enter the Gilded Seam (compass 17). Claim veins one at a time and read the bar. Keep claiming past the quota to cross Tremor 4 and 6, then bank and count the rolls.
- **Pass:** claimed veins turn to blackstone and never un-claim; from the fourth claim on, each claim rings hoglins around the vein (1 plus one per two members); The Assayer arrives once at Tremor 6 and never twice; the pad opens at the quota; a floor cleared with two extra veins pays two extra rolls.
- **Fail signs:** a vein that will not claim or that claims twice; a pack or The Assayer ahead of their Tremor counts; The Assayer twice on one floor; the pad opening under quota or staying shut at it; extra claims paying nothing; a hoglin or brute that zombifies (the dungeon dimension is not piglin safe; they arrive immune).

## L99: Haunt floor: the Keeper walks (2026-10-13)
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- **Changed:** Lighthouse Below floors (act 3) carry the Keeper (haunt `the_keeper`, HUNT behaviour). About `hauntHuntGraceSeconds` (20) seconds after the floor opens it wakes and walks toward the party whether watched or not. It cannot be killed: a hit never lands, it staggers for `hauntStunSeconds` (3) and is immune for 5 more. Two beacons stand in the side rooms, a heavy plate beside an unlit copper bulb: standing on a plate for `beaconChargeSeconds` (3) lights it and drives the Keeper off for `beaconBanishSeconds` (15). The pad refuses ("The lamp is dark. Light N more beacons.") until both are lit.
- **Do:** enter the Lighthouse Below (compass 14). Stand in the open until the grace runs out. Let the Keeper approach; watch it, hit it once, hit it again inside the immunity window. Stand a beacon plate through the charge. Try to draw it into the entrance room or the pad room.
- **Pass:** it wakes after the grace, not on the pressure clock; looking at it never holds it; the first hit staggers it and a fast second does not; a lit beacon banishes it and the bulb stays lit; the stage ladder still holds (sightings early, strikes from the strike floor); it never enters the entrance or the terminal room; the pad opens when the second beacon lights.
- **Fail signs:** the Keeper frozen by a stare or hurt by a hit; a stunlock with no immunity gap; a beacon that will not charge or does not banish; the Keeper inside the entrance or the pad room; a Keeper that vanishes and never returns.

## L100: Stray trial mobs release their spawner (2026-10-15)
- **Status:** partial 2026-10-16 (`2026-10-16-1.md`): the gate job verified live. A stray enderman from an earlier floor was heard on a room roof mid-run and every floor still cleared, so the release works. Residue filed as PD-213: the stray entity survives and stays audible, so release is not yet cleanup.
- **Changed:** `TrialContent.releaseStrays` sweeps each gated spawner's tracked mobs on the watch tick; a mob gone from the level or outside the floor bounds is dropped from the waiting set (counts as defeated) and a found stray is discarded.
- **Do:** run an `ender_archive` or other enderman floor, let one teleport out of the room, and check the floor still clears and the stray is gone (not just ungated).
- **Pass:** the spawner reaches cooldown with the stray outside the floor, and no enderman noise persists on roofs after the floor ends.
- **Fail signs:** the floor stalls on an escaped mob (the original PD-113), or the stray remains audible/visible after release (PD-213, seen once already).

## L101: The Proving, a wave-arena descent (decision 2026-10-16)
- **Status:** owed (built 2026-10-16; not yet played live; arena look and feel unverified)
- **Changed:** `the_proving` is a new dungeon of kind `proving`, offered beside the Endless Mine on the astrolabe's special row (act 1, compass gate as the Mine's). Each floor is two rooms, gate plus arena, with one wave spawned in the arena when the floor commits. Wave size is `provingWaveBaseSize` (5) + `provingWaveSizePerFloor` (2) per floor + `provingWaveSizePerMember` (2) per extra member, capped at `provingWaveMaxSize` (20); every `provingChampionEvery` (3) floors the last spawn is a named Champion scaled half again. The pad refuses while the wave stands ("The wave still stands. Finish it first."), the checkpoint offers a door deeper or the HOME lever, and pay climbs the act's loot tiers (floor 1 pays tier A, floor 2 B, floor 3+ C).
- **Do:** enter the Proving from the astrolabe. Fight floor 1, use the door deeper, reach floor 3 (a champion floor), and bank with the HOME lever. Try it at low and high compass and with a second member.
- **Pass:** the offer appears on the special row; each floor is a gate and an arena with one wave; the pad refuses while any wave mob lives and opens when it falls; floor 3 sends a named Champion; the checkpoint prints the Proving line and banking pays the climb ("You leave the Proving with N floors banked.").
- **Fail signs:** no offer on the special row; a floor that stalls after the wave falls (pad stuck shut); a wave mob that cannot reach the players; waves or pay that do not deepen; a Proving floor that counts toward act completion or shows as a normal door.

## L102: Phased boss floors, Copper Works pilot (decision 2026-10-16)
- **Status:** owed (built 2026-10-16; not yet fought live)
- **Changed:** `BossFight` is the shared phased spine: an elite's health is cut into phases by fraction bounds; crossing a bound staggers the boss (`bossPhaseStaggerSeconds`, 3s invulnerable and mindless, title lines plus the ravager-stunned sound) and arms the next phase when the stagger ends. Phases advance one at a time, so burst compresses transitions but cannot skip a phase. Copper Works pilot: phase 1 baseline, phase 2 wakes the vents (`copperVentPulseSeconds`, `copperVentBlownPercent`, `copperVentBurnSeconds`), phase 3 quickens them and sharpens the vat (`copperForemanVatDamage` x `copperForemanVatCorneredMultiplier` when cornered, `copperForemanVatWeakSeconds`, `copperVentCorneredPercent`).
- **Do:** clear Copper Works and fight the Foreman at ordinary gear, then again overleveled. Watch the phase tells, when the vents first fire, and whether the vat weakness reads.
- **Pass:** all three phases play in order even under burst; each transition shows its title and the stagger is readable; vents stay silent in phase 1 and fire from phase 2; the pad still opens only on the kill. PD-208's failure mode (an elite dying before its mechanic registers) cannot recur: a staggered boss is invulnerable while the next phase arms.
- **Fail signs:** a phase skipped by burst; the stagger reading as "the game ate my crit"; vents firing in phase 1 or with no readable rhythm; the boss dying before phase 3's tell.

## L103: Payoff rooms: Gilded Vault, Mason's Shortcut, Windlift (plan 2026-10-17)
- **Status:** owed (built 2026-10-17; not yet visited live)
- **Changed:** three dead-end side rooms cash in a tag found earlier on the same floor, and a spur cell draws them three times as often when the tag is provided upstream. Gilded Vault (`gold`): a vanilla vault that takes one gold ingot. Mason's Shortcut (`blocks`): a chest across a five block gap, reached by placing blocks. Windlift (`wind_charge`): a chest on a three block ledge, reached by a wind charge jump. Main path unchanged.
- **Do:** find each room on a floor where its tag was provided earlier (gold from `the_herd`, blocks from a gallery, wind charges from the breeze arena), and once where it was not.
- **Pass:** the Gilded Vault takes exactly one ingot, pays once per player and reads as a vanilla vault; the Mason's gap cannot be jumped; a wind charge from the chiseled tuff reaches the Windlift ledge; each room shows its cache through the window from the corridor; a room whose tag was missing is shut but never blocks the floor.
- **Fail signs:** a payoff room on the main path; the vault taking more than one ingot or paying twice; the Mason's gap jumpable at a sprint; the Windlift ledge reachable without a charge or blocks; the cache invisible from the corridor.

## Done

- **PD-92** max-omen ejection keeps each stack once: passed 2026-10-01 (`2026-10-01-1.md`).
- **L2. PD-93: an anomaly room builds**: passed 2026-10-04 (`2026-10-04-1.md`): `anomaly_store_straight` stood on prismarine floor 3, was entered at 04:25 and built; no manifest error.
- **L3. PD-94: ominous loot without Boss Stones**: passed 2026-10-01 (`2026-10-01-2.md`): after a feral+ominous basalt_foundry run with 3 chests, `context full` shows no Boss Stone echo shard in the pack. Caveat: unproven whether every ominous chest was opened.
- **L5. PD-96: the Salvage Bench is found without help**: passed 2026-10-01 (`2026-10-01-2.md`): the player opened the bench on his own and immediately gave UI feedback (PD-101, right-click should not deposit the held item). Caveat: he already knew the feature existed, so stranger-first-time discovery is still unproven.
- **L7. PD-80: quiet waits report correctly**: passed 2026-10-07 (`2026-10-07-1.md`): clean quiet waits on the remote Kinetic server too, no false `server is down`. Earlier: passed 2026-10-01 (`2026-10-01-2.md`): a full session on the new `lemonwatch.mjs` loop printed only clean `no new events (timeout)` lines while the server was up. Keep watching for one more session before retiring.
- **L15. 2026-10-02: librarian lock in and run storage**: retired 2026-10-10 (Lock in is gone and its config keys are retired; Mending is a 64 emerald librarian offer, see L52; run storage passed in L48). Was: owed, 2026-10-04-1.md: run storage persists across floor clears and two banks (27 books and 54 emeralds still there), the "wiped" claim at 04:16 did not reproduce; librarian, lectern and Lock in not seen. Earlier: owed, 2026-10-02-2.md: not settled. Discovery failed: the player asked how to make a lectern and did not know the station picker hands it out. He then rejected the "Lock in" idea ("there's no lock in"): he wants the librarian to sell a Mending book. Run storage and the librarian itself were not seen.
- **L22. 2026-10-03: balance batch**: retired by W5 (design D14: five kits, granted once, no refill). Was: owed (partial), 2026-10-04-1.md. Passed: floor 1 had 2 spawners twice; inventory_snapshot written at every floor clear and bank; salvage gave materials, no emeralds, XP only from the enchanted piece. Failed on the clock: floor 1 took 828 s and 731 s (player was chatting to Lemon, two puzzles slowed him). Not tested: party vault, one key per spawner, history board text. Earlier: owed
- **L23. 2026-10-04: the bag chest is a kit station (RETIRED 2026-10-05, dungeon structure W5)**: retired 2026-10-05 (W5: five kits, granted once, no refill; the bag chest refill is gone). Was: owed, 2026-10-04-1.md: `kit_refill` fired on both banks (04:28:48, 05:37:50); the second overwrote an unused shield and stone sword (leftovers replaced, not stacked). Not seen: two players, the chest itself in play. The owner is now leaning toward removing refills (4 or 5 starting kits, no refill), so this row may be retired by an owner decision. Earlier: owed
- **L25. 2026-10-05: finishing pulls (Stage 1 hypothesis 2, A7 and A2)**: pass 2026-10-08 (`2026-10-08-1.md`): three finishes in one session (Infestation 4 floors, Spawner Dungeon capstone 3 floors, Copper Works 4 floors), all unprompted; the Spawner milestone title landed ("The Spawner Dungeon Cleared Go Home worked", PD-165 verified live). Remaining caveat: the finish's flat ending now reads as missing a finale (PD-184). Earlier: partial pass 2026-10-07 (`2026-10-07-1.md`): two more unprompted first finishes (Copper Works 4 floors, Cow Pits 2 floors), diary pages 13 and 15 arrived, vault_chests 2 each. New caveat: he does not recall any "<Dungeon> cleared" milestone title on either (PD-165). Earlier: partial pass 2026-10-06 (`2026-10-06-1.md`): he finished the Mineshaft (3 floors, `dungeon_finished`, first clear) and the diary page arrived (entry 8, which he read and reported clipped, PD-157). Caveat: `dungeon_finished` journaled `shards:0`; under the new barrel model vault rolls merge so `vault_chests:0` is expected, but confirm the finish shard paid. Earlier: owed, 2026-10-05-1.md: inconclusive, quit on the final floor for real-life reasons.
- **L26. 2026-10-05: dungeon length is right (Stage 1 hypothesis 3, A8)**: pass 2026-10-07 (`2026-10-07-1.md`): Copper Works 4 floors in about 25 minutes of play (clears 119/77/81/187 s) and Cow Pits 2 floors in about 8 minutes; no drag complaint, both finished unprompted. Earlier: pass 2026-10-06 (`2026-10-06-1.md`): full Mineshaft clear (3 floors) took about 30 minutes door to bank (entry ~04:58, `dungeon_finished` 05:28), floors at 364 s and 633 s bookends with chat inflation. No drag complaint. Earlier: owed, 2026-10-05-1.md.
- **L27. 2026-10-05: shards buy branches (Stage 1 hypothesis 4, A3 and A6)**: superseded 2026-10-07 (Haul and Blood Doors: side doors cost lives, see L54; the shard wording is dead). Was: owed, 2026-10-07-1.md: still not exercised (no priced edge taken), but the currency has moved to scrap: a side-branch refusal "2 scrap short" was seen live (PD-162: it names the gap, not the holding). This row's shard wording is stale. Earlier: owed, 2026-10-05-1.md: not exercised. The Mineshaft has only free main edges, so no side branch was ever offered; he holds 6 echo shards (carried over).
- **L28. 2026-10-05: random steps and the doors screen**: superseded by design 2026-10-08 (`2026-10-08-1.md`): he rejected the three-random-door model entirely ("The 3 random doors doesn't really work with the new dungeon act system", "players should be able to choose the act and dungeon"; his top pick for "first thing you'd change" at wrap). The door-screen question is now PD-181: replace the front offer with act/dungeon selection. Earlier: still owed 2026-10-06 (`2026-10-06-1.md`): the new one-layout board was seen and read (he asked what "Same as door 1" meant, then rejected duplicates outright, superseding PD-153: "if they are the same then they should have different affixes"). Door-choice weighing still not observed closely. Earlier: failed for resource dungeons 2026-10-05 (`2026-10-05-1.md`).
- **L30. 2026-10-05: the break rule and dark rooms**: rule reverted, delight confirmed 2026-10-06 (`2026-10-06-1.md`): interiors are mineable with the correct tool everywhere again (pre-ebc37e3 semantics, shell and fixtures still protected). On the seam room: "one of my favourites so far ... It feels like minecraft", and "the durability of the tools balances the abundance of cobblestone". Dark floors not specifically re-observed. Earlier: failed on design 2026-10-05.
- **L37. 2026-10-05: an old save loads into the new structure**: passed 2026-10-05 (`2026-10-05-1.md`): his pre-branch save (keystone 9) loaded with no decode errors, kept pack and history intact, and the first-trip offers were Act 1 only (Spawner Dungeon, Lush Caves, Mineshaft). The keystone-15 act-2/3 grant was not exercised (he is at 9).
- **L38. 2026-10-06: the Store is one row you click**: passed 2026-10-08 (`2026-10-08-1.md`): used unprompted many times across the session (7 `shop_sale` events on one floor, more at home); verdict "The shop works perfectly". Sold-out and short-price edge cases not specifically probed but nothing failed in normal use.
- **L40. 2026-10-06: a Mineshaft floor 1 has ore**: passed 2026-10-08 (`2026-10-08-1.md`): Mineshaft floor 1 rolled `nodes_total` 31, `nodes_mined` 25, `floor_pay` scrap 1; the ore is real and mined. Only one floor 1 sampled (the check asked for three), but the zero-node failure mode did not appear.
- **L44. 2026-10-06: doors behind one edge differ in affixes**: passed 2026-10-07 (`2026-10-07-1.md`): one Doors screen dealt Ominous, Silenced and Feral over steps 2/1/3 on the same node, and an earlier pair dealt Overclocked and Molten; no "Same as door" line seen. Earlier: owed
- **L46. 2026-10-06: GO HOME reads scrap and charts**: wording superseded; seen live 2026-10-07 (`2026-10-07-1.md`): the board now shows title plus "Lives N" (and "Unfinished: vault, page" while owed) per J1; the player saw it and asked for his scrap count on it ("The home board should be showing me my current scrap"), a design note now folded into PD-162. Earlier: owed
- **L48. 2026-10-06: Dungeon Storage survives a logout and a teardown**: passed 2026-10-07 (`2026-10-07-1.md`): he used it unprompted and heavily (stashed 64 emeralds plus gear and mats mid-trip); the same contents rode through the 00:04 disconnect plus purge, two banked trips and two dungeon finishes (`inventory_snapshot` shows the stacks carried across all of it). The safe-room ender chest opening the same storage was not separately watched, but the storage is a `DungeonLog` record now, so the chest is only a door to it. Earlier: owed
- **L50. 2026-10-06: the Store is a villager's trade screen**: pass 2026-10-08 (`2026-10-07-2.md`): completed trades now journaled from two vendors: `shop_sale` x2 (Web Trader, 1 emerald for 3 string) and `shop_purchase` (Wandering Merchant, bow for 3 emeralds; home Librarian, diamond leggings for 18). Earlier: partial 2026-10-07 (`2026-10-07-1.md`, screen opened, no journaled trade); owed
- **L53. 2026-10-06: keys settle on the clear line**: superseded in part 2026-10-10 (PD-196: vault keys now last the dungeon and settle for emeralds when the haul banks, see L62; the floor clear no longer takes keys). Was: partial 2026-10-08 (`2026-10-07-2.md`): key redemption on the clear line confirmed by `floor_pay` emerald payloads of 3, 4 and 1 emeralds alongside scrap (matching the J7 rates: 1 plain, 3 ominous). New related break: ominous trial keys cannot pay the barred_vault toll, so on ominous floors keys can only ever redeem (PD-170). Mob gear drops not rechecked this session. Earlier: owed, 2026-10-07-1.md
