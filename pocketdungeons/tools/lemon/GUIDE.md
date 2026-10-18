# Lemon game guide

Plain-words mechanics Lemon may explain freely. Keep each entry to what a player
needs, in the words Lemon would use. The player words are compass, scrap, haul,
lives, Loot percent and charts. Do not say keystone, omen, wager, interval or
node to a player.

## The loop

- **Starting:** type `/dungeon`, or use the wall lodestone in your room (Home
  menu: Start Dungeon, Manage Room, Inspect Compass, Manage Party, View
  Lobbies). The bag chest says there is equipment left behind; press Take it for
  the starter tools (wooden axe, pickaxe and shovel). There is one bag, not a
  choice. Entering and leaving never grant items, and nothing refills it.
- **Act 0, The First Night:** a new player's first dungeon, before Act 1. Five
  floors, no lives to lose (deaths cost nothing here), and nothing is paid until
  the last floor. The Home lever is locked until the finish; the Quit Door
  restarts the night and clears the pack. The last floor is one trial spawner.
  Finishing it banks 2 scrap, which is exactly what Compass 2 costs, and opens
  Act 1. Lives start in Act 1. Teach the dungeons in general (the door board,
  the lever, the pad at the end of a floor, scrap, compass, a furnace smelts
  ore with fuel). Do not tell a player what to gather on a given floor.
- **Alex and the diaries:** Lemon is Alex's Allay. She lost Alex somewhere in the
  pocket dungeons and came out of the compass to look. She needs Alex's diaries
  to find her, so she asks the player to keep every page.
- **Compass:** your level, shown as `Compass 10: 4/7` (scrap banked toward the
  next level, and what that level costs). It only ever goes up. The first one is
  free (`/dungeon key`); a lost one is replaced at the level you earned. A level
  costs `4 + floor(compass / 3)` scrap: compass 1 costs 4, compass 10 costs 7.
- **The Astrolabe Room:** the first room of a trip. Right-click the astrolabe to
  turn to the next open act (sneak to turn back). Each door is a dungeon; the
  side doors hold the act's Endless Mine. Clearing an act's capstone and floor 3
  (the first guardian) of that act's Endless Mine opens the next act. The copper
  bulb says how a dungeon stands: lit is open, oxidized is finished (the sign
  says Play again), dark behind an iron door is locked (the sign says the compass
  it needs, or Finish the act). Open a door to look inside through the wide
  glass wall; the DESCEND lever glints beside the door you picked. Pull it to go.
- **Floors and objectives:** most floors clear on the spawner gate (the bar
  shows `Spawners 2/3`) to open the way on. Some floors ask for something else
  instead, and the pad's refusal line says what is left: a heist floor (Sunken
  Treasury) wants the vault core channelled open for 5 seconds, a claim floor
  (Gilded Seam) wants gilded veins claimed by hand (extra veins past the quota
  wake hoglins and The Assayer, and pay extra barrel rolls), a haunt floor
  (Lighthouse Below) wants its beacon plates stood on for 3 seconds until the
  lamps light, which also banishes the Keeper for 15 seconds.
- **Doors between floors:** later floors offer three doors. The way forward is
  free. A side door or a door with the Blood affix costs 1 life (or 2 for some
  side branches), shared by the party, and is refused if it would take the last
  life. The door board shows the affixes, `Loot n%`, the barrel roll count
  (`N rolls`), and any promised piece in the copper chest.
- **Names:** players call the room between floors "the Doors"; use that name.
- **The sidebar:** shows floor, lives, spawners, haul and compass. `/dungeon
  display off` hides it. Chat is only the log; players mostly do not read it live.

## Scrap, haul, Loot percent and lives

- **Haul:** scrap carried on this trip. Each cleared floor pays into it. It is at
  risk until it is banked.
- **What a floor pays:** the door's step (+1, +2 or +3), plus 1 for each act
  above the first, plus 1 on a dungeon's last floor. A floor below your compass
  pays half (at least 1). Overclocked pays +1 extra scrap if cleared under 6:00.
- **Loot percent (`Loot n%`):** harder affixes raise the floor's Loot percent
  (100% with no affixes, higher with more or tougher affixes). It scales how many
  rolls your reward barrel gets (`N rolls` on the door board), and any floor
  above 100% also puts one extra piece of gear or material into the floor's
  copper chest, named right on the door board.
- **Banking:** going home (the HOME lever, which asks first) or finishing a
  dungeon banks the whole haul into your compass and rolls the deepest floor's
  reward count into your reward barrel. A finish also pays emeralds and vault
  chests; finishing the same dungeon again pays half the emeralds.
- **Lives (from Act 1; Act 0 has none to lose):** the party shares 5 lives per trip. A death costs one; Blood or a
  side door costs one (or two). The fifth death fails the dungeon.
- **Failing:** a failed dungeon keeps half of everyone's haul, and the party is
  sent back to the Home room (not out of the dungeon dimension), still together,
  with the pack as carried and the doors ready to choose again.
- **Leaving:** leaving a floor in progress for any reason (the lodestone's Leave,
  `/dungeon exit`, a disconnect, joining another party) fails the dungeon for the
  one who leaves: they keep half their haul. Leaving between floors cashes out in
  full. If the party's owner leaves a floor in progress, the dungeon fails for
  everyone. Leave asks first.
- **Quit Door:** owner only, in a dungeon with a door chosen. It fails the
  dungeon (half the haul), returns the party to the Home room and leaves the
  compass alone.
- **Disconnects:** the party owner has about two minutes to reconnect before the
  run ends as a failure.
- **Vault keys:** a trial key lasts the whole dungeon, so carry one from floor 2
  into floor 3. Plain and ominous keys are separate and open separate vaults.
  Keys you never use turn into emeralds when the haul banks (home, a finish or a
  failed dungeon at its share).
- **Promised gear:** some floors say on the door board that the copper chest pays
  a named piece of gear. It is the exact piece the board named.

## Danger, affixes, haunts and finales

- **Waiting:** lingering in a room that is not solved sends waves.
- **Affixes:** Swarming (more mobs), Overclocked (faster spawners, +1 scrap if
  cleared under 6:00), Silenced (every 3rd consumable sends a wave, action bar
  shows `Silenced: n/3`), Blackout (some rooms are dark; entrance, exit and lever
  rooms stay lit), Explosive (floor mines with a short fuse), Molten (floor
  lava), Loaded (opening the free tool cache chest springs a small ambush once),
  Restless (fallen mobs rise once after 2 seconds unless killed with fire),
  Voided (floor holes in plain rooms at high compass), Cooked (ominous run),
  Blood (costs 1 life when committing the door).
- **Sculk and the Heard meter:** any room with sculk sensors or shriekers
  listens. Each sensor pulse fills that room's Heard meter (the sidebar shows
  `Heard 2/4`; the Ancient City fills at 2). Only a full meter answers: darkness
  and a Warden whelp, a small melee-only Warden that hunts for 15 seconds, one at
  a time, while the meter holds still. A shriek in a room with sensors does not
  answer by itself. In a room with only a shrieker, a shriek answers at once.
  Clearing a sculk room's spawner without it ever answering pays +1 scrap. The
  real Warden wakes only on the Ancient City's last floor, on the second answer.
- **Haunts:** floors that say "Something walks here." have a floor-wide stalker
  that wakes after you spend time on the floor. Floor 1 is a sighting (it
  vanishes when approached), floor 2 is a near miss, and from floor 3 it strikes.
  In the Ossuary, The Counted moves only while nobody looks at it. In Lighthouse
  Below, The Keeper walks whether watched or not; hitting it staggers it for 3
  seconds, and lighting a beacon banishes it for 15 seconds. Haunts never enter
  the entrance room, the exit pad room, an armed Ordeal room or a finale.
- **Finales:** the last floor of most dungeons ends in a boss or last stand once
  spawners are cleared, and the exit stays shut until it is won:
  - *Mineshaft:* halfway through the wave the lights go out and cave spiders join.
  - *Lush Caves:* potions drip from the ceiling on deeper floors (step aside when
    you see sparks); the finale is a giant slime that splits into double children.
  - *Infestation:* mossy bricks ripen into stone and infested stone before
    bursting into silverfish (smash them early); The Queen burrows between phases.
  - *The Kennels:* hostile wolves; when the Alpha hits half health The Handler
    winds up a horn for 5 seconds to empower the pack (kill the handler to stop
    or undo it).
  - *Ossuary:* six sealed coffins crack open one by one as you cross the hall.
  - *Spawner Dungeon (Act 1 capstone):* four caged spawners each release one of
    the earlier Act 1 mechanics when broken; after the fourth, the Brood Mother
    arrives.
  - *Cow Pits:* three floors (Pen Gate, Milking Parlour with milk churns, Big
    Barn); feeding Specimen 0 wakes the herd for an optional boss fight and chest.
  - *Deepslate:* the Silent Deep finale runs on the Heard meter (loud fighting,
    bows and sprinting fill it and call a whelp).
  - *Copper Works:* a 20-minute furnace clock ticks down across the dungeon (each
    cleared spawner buys 60 seconds; if it hits zero the finale starts early with
    faster vents). In the Master Furnace, grates hiss before firing and luring
    the Foreman into a water vat weakens him.
  - *Frostworks:* the Big Freeze chills anyone away from the five lit campfires.
- **Wolves:** untamed wolves in the Kennels are hostile; do not try to feed them.
  Kill a guard tower's crossbow pillager and its pack goes quiet. The Lost Dog
  and Wolf Hollow wolves are the friendly ones: clear the room and the dog is
  yours (up to 3 follow you; extras sit).
- **Fire:** fire does not spread inside dungeons.
- **Endless Mine:** each act has its own Endless Mine (Act 1 `endless_mine`, Act
  2 the Deep Mine). Every third floor (3, 6, 9 and on) holds a guardian fight and
  pays a guardian loot table in the barrel (floor 3 tier A, floor 6 tier B, floor
  9 and every guardian after tier C), escalating each lap. Clearing floor 3
  counts toward opening the next act. Walls hide diggable ore that gets richer
  with depth.

## Gear and stations

- **Kit:** picked at the bag chest, given once. Resource dungeons and mines are
  the restock.
- **Durability:** tools, weapons and armour have capped durability; wood is scarce
  early, so spend it carefully.
- **Stations in your room:** an enchanting table rerolls a piece of gear for lapis;
  a grindstone is the scrap bench (all gear can be scrapped, trimmed armour too,
  except imbued pieces). Lapis also sells for emeralds to the librarian and to the
  Deepslate and Frostworks merchants. Right-clicking a held shell token unlocks
  that room shell directly.
- **Your room:** Manage Room has name, public or private, shell, visitors and
  reset room. Manage Party has invite, banned, and for each person Can build and
  Ban, plus who has played with you. A banned player cannot join your party or
  visit your room. Inspect Compass has your diaries and Reset Compass.
