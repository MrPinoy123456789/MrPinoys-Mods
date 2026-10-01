# Ordeals

An Ordeal is a room built around a danger the player has to get past, and a
way to end it. Named "Ordeal" rather than "Trial" because trial already means
vanilla trial spawners, trial keys and vaults all through this mod, and
"Situation" is the code's name for every room handler.

Source: owner, 2026-09-30, after playtest 2026-09-29-3. Code: `Ordeal.java`
(the contract), `Ordeals.java` (the runtime), one class per kind.

## The three parts

Every Ordeal has:

- **Objective:** what the player has to do.
- **Danger:** what stands in the way. It runs until the Ordeal is resolved.
- **Resolution:** what ends it, for good. For most Ordeals that is a lever
  with a redstone lamp touching it: pulling the lever ends the danger and the
  lamp shows the room is done from anywhere in it.

| Ordeal | Objective | Danger | Resolution | Class |
|---|---|---|---|---|
| Rising Lava | reach the lever at the exit | lava closing in from both side walls | the lever drains the lava | `RisingLavaOrdeal` |
| Collapsing Bridge | cross to the far side | planks drop away under you | the far-side lever locks the bridge in place | `CollapsingBridgeOrdeal` |
| Thicket | reach the spawner through the webs | cobwebs and cave spiders | the lever on the spawner shuts it off | `SpawnerOrdeal.THICKET` |
| Ice Run | climb the floating ice to the platform in the middle, or pillar up to it | strays on the floor shooting you off the ice; a miss drops you to the floor and back to the start | the lever on the platform shuts the spawner off | `SpawnerOrdeal.ICE_RUN` |
| Hold the Plate | hold the plate for 30 seconds | waves raised by the plate, and the room's spawner | the hold completes and the exit opens (no lever) | `HoldThePlateOrdeal` |

## Conventions

- **The lever and lamp pair is reserved for Ordeals.** It means "this ends the
  danger" wherever a player meets it. Decor must not mimic it: the gallery's
  targets and lamps read as a lock (playtest 2026-09-29-1).
- **One way.** A pulled Ordeal lever stays down; a second click is claimed and
  refused ("Already done"). An Ordeal is never un-resolved.
- **Protected.** While the room is armed its lever and lamp cannot be broken
  by a player or blown up (`RoomProtection`, `DungeonTools.isShellProtected`).
- **Where the lever goes.** At the end of the danger, so reaching it is the
  objective. A room the layout can turn round (a lever that belongs at the
  exit) gets its lever at stamp time from `LayoutStamper.applyDirectionalGates`,
  beside the exit doorway; a room that reads the same either way (a centred
  spawner) bakes it into the template.
- **The lamp touches the lever.** Vanilla redstone lights it from the lever
  and keeps it lit; no code sets it.
- **Resolution feedback is shared.** A chime, the Ordeal's own line on every
  player's action bar in the room, and an `ordeal` journal event.

## Vertical Ordeals

Ice Run (owner design, 2026-09-30) is the first room that asks the player to
climb. Its course is three steps up (hops at y=1 and y=2, the platform at
y=3, top at y=4 under a ceiling at 6), so a fall lands on the floor below the
higher hops and the course starts over. Looted blocks are the other answer:
three of them pillar a player from the floor onto the platform. A vertical
Ordeal should keep both routes: the skill route and the resource route.

## Adding an Ordeal

1. Subclass `Ordeal<S>`: `arm` reads the stamped room into a state `S` (scan
   the world, never trust authored coordinates), `stale` says when the cell is
   gone, `tickDanger` steps the danger, `resolve` ends it and returns the line
   to show. A lever Ordeal returns its lever from `lever`; one without a lever
   answers `objectiveMet`.
2. Place the lever with `Ordeals.placeWallLever` (on a wall, lamp above) or
   `Ordeals.placeFloorLever` (standing on its lamp).
3. Arm it with `Ordeals.arm` from the room's situation handler, or from
   `applyDirectionalGates` when the lever belongs at the exit.
4. Add a row to the table above and a check to `LIVE_TEST_PASS.md`.

Teardown, the tick, the lever click and protection come with the framework.
