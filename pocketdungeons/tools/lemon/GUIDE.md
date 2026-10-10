# Lemon game guide

Plain-words mechanics Lemon may explain freely. Rewritten 2026-10-10 against the
code after the haul, Astrolabe Room, sculk, finale and leaving-rule changes. The
owner corrects and extends it. Keep each entry to what a player needs, in the
words Lemon would use.

**This guide wins.** The "owner decisions" section further down this briefing is
the September log and still talks about keystones, bands and refills. Where it
disagrees with this guide, trust this guide. The player words are compass, scrap,
haul, lives and charts. Do not say keystone, omen, interval or node to a player.

## The loop

- **Starting:** type `/dungeon`, or use the wall lodestone in your room (Home
  menu: Start Dungeon, Manage Room, Inspect Compass, Manage Party, View
  Lobbies). Choose a bag at the bag chest; that is when the kit is given, once.
  Entering and leaving never grant items, and nothing refills a kit.
- **Compass:** your level, shown as `Compass 10: 4/7` (scrap banked toward the
  next level, and what that level costs). It only ever goes up. The first one is
  free (`/dungeon key`); a lost one is replaced at the level you earned. A level
  costs `4 + floor(compass / 3)` scrap: compass 1 costs 4, compass 10 costs 7.
- **The Astrolabe Room:** the first room of a trip. Right-click the astrolabe to
  turn to the next open act (sneak to turn back). Each door is a dungeon. The
  copper bulb says how it stands: lit is open, oxidized is finished (the sign
  says Play again), dark behind an iron door is locked (the sign says the compass
  it needs, or Finish the act). Open a door to look inside through the wide
  glass wall; the DESCEND lever glints beside the door you picked. Pull it to go.
- **Floors and spawners:** clear the floor's spawner gate (the bar shows
  `Spawners 2/3`) to open the way on.
- **Doors between floors:** later floors offer three doors. The way forward is
  free. A side door costs 1 or 2 lives, shared by the party, and is never sold if
  it would take the last life. The door board says the price and what the floor
  offers.
- **Names:** players call the room between floors "the Doors"; use that name.
- **The sidebar:** shows floor, lives, spawners, haul and compass. `/dungeon
  display off` hides it. Chat is only the log; players mostly do not read it live.

## Scrap, haul and lives

- **Haul:** scrap carried on this trip. Each cleared floor pays into it. It is at
  risk until it is banked.
- **What a floor pays:** the door's step (+1, +2 or +3), plus 1 for each act
  above the first, plus 1 on a dungeon's last floor. A floor below your compass
  pays half (at least 1). An Endless Mine floor pays more the deeper it goes.
- **Banking:** going home (the HOME lever, which asks first) or finishing a
  dungeon banks the whole haul into your compass and rolls reward chests into your reward barrel. A finish also pays emeralds and
  vault chests; finishing the same dungeon again pays half the emeralds.
- **Lives:** the party shares 5 lives per trip. A death costs one; a side door
  costs one or two. The fifth death fails the dungeon.
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
  a named piece of gear. It is the same piece the board named.

## Danger

- **Waiting:** lingering in a room that is not solved sends waves.
- **Sculk and the Heard meter:** any room with sculk sensors or shriekers
  listens. Each sensor pulse fills that room's Heard meter (the sidebar shows
  `Heard 2/4`; the Ancient City fills at 2). Only a full meter answers: darkness
  and a Warden whelp, a small melee-only Warden that hunts for 15 seconds, one at
  a time, while the meter holds still. A shriek in a room with sensors does not
  answer by itself. In a room with only a shrieker, a shriek answers at once.
  Clearing a sculk room's spawner without it ever answering pays +1 scrap. The
  real Warden wakes only on the Ancient City's last floor, on the second answer.
- **Finales:** the last floor of most dungeons ends in a last stand. When the
  spawners are cleared, a wave stands up around the party (and from Act 2 a named
  elite leads it). The exit stays shut until all of it is dead. The Copper Works
  mobs wear copper gear; none of it drops.
- **Restless:** an affix. The dead get up once. Fire keeps them down.
- **The Kennels:** pillagers raise wolves there, and every untamed wolf in the
  dungeon is hostile; do not try to feed them. Kill a guard tower's crossbow
  pillager and its pack goes quiet. The Lost Dog and Wolf Hollow wolves are the
  friendly ones: clear the room and the dog is yours. You keep at most 3 tamed
  wolves standing with you; the extras sit.
- **Fire:** fire does not spread inside dungeons.
- **Mining:** the Endless Mine hides ore in the walls and holds richer ore the
  deeper you go.

## Gear and stations

- **Kit:** picked at the bag chest, given once. Resource dungeons are the restock.
- **Durability:** tools, weapons and armour have capped durability; wood is scarce
  early, so spend it carefully.
- **Stations in your room:** an enchanting table rerolls a piece of gear for lapis;
  a grindstone is the scrap bench (all gear can be scrapped, trimmed armour too,
  except imbued pieces). Lapis also sells for emeralds to the librarian and to the
  Deepslate and Frostworks merchants.
- **Your room:** Manage Room has name, public or private, shell, visitors and
  reset room. Manage Party has invite, banned, and for each person Can build and
  Ban, plus who has played with you. A banned player cannot join your party or
  visit your room. Inspect Compass has your diaries and Reset Compass.
