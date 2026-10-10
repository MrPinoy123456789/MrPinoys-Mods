# Final floor rooms: the content gap (PD-173)

Owner ruling 2026-10-08: the final floor names stay ("The Great Drip Cavern"), and rooms that
sell each name get built. A node already takes `roomBias` in `dungeon/*.json`; the gap is that most
final nodes name no rooms, so they roll generic `hall_*` rooms.

## Audit (read from `dungeon/*.json` and `dungeon_room/*.json`)

Final nodes that already bias to authored rooms (6): Ancient City, Cow Pits, Herobrine, Mineshaft,
The Spawner Dungeon, The Wither's Keep.

Final nodes with no `roomBias` (12), by act, each needing at least one room that matches the name:

| Act | Dungeon | Final node | Name promises |
|---|---|---|---|
| 1 | Infestation | The Queen's Nest | a nest, a queen |
| 1 | Ossuary | The Last Rest | a burial hall |
| 1 | Lush Caves | The Great Drip Cavern | a vast dripping cavern |
| 1 | Endless Mine | The Working Face | (endless; low priority) |
| 2 | Copper Works | The Master Furnace | a great furnace |
| 2 | Deepslate | The Silent Deep | silence, depth |
| 2 | Frostworks | The Big Freeze | a freezing hall |
| 3 | Prismarine | Heart of the Monument | a monument heart |
| 3 | Drowned Vault | The Warden's Deep | (capstone) a drowned warden hall |
| 4 | Basalt Foundry | The Great Crucible | a crucible |
| 4 | Blackstone | The Bastion Keep | a bastion keep |
| 5 | Ender Archive | The Last Index | an archive stack |
| 5 | The End | The Rim of the World | an edge of the world |

(The audit printed 18 dungeons; one of the 19 files was not reported as having a leaf node.)

## What a final-floor room needs

- `graphRole: ["final"]` on the room, the dungeon in `dungeons`, and the node's `roomBias` naming it.
- A recognisable set piece in one cell (the name should be visible on arrival), and a trial spawner
  whose mob table suits it, so it can be the floor's last required spawner.
- It must not depend on items the player may not have (scarcity): no mandatory keys or crafted
  items beyond what the floor drops.
- Rooms that host a spawner must be added to `RoomSelector.ENCOUNTER_ROOMS` (`RubbleRulesTest`
  fails otherwise).

## Order of work

`PackValidator` reports a `roomBias` that names a room that does not exist, so add each room first
and its node's `roomBias` second. Not yet checked: whether `roomBias` is a hard filter or a soft
weight in the selector (`Instances` hands it to the room eligibility pass); if it can fail when
too few rooms match, land at least two rooms per final node before biasing it.

## Status (2026-10-08)

Built, one room per final node, each with `graphRole: ["final"]` and a `roomBias` on the node: Infestation
`queens_nest`, Ossuary `last_rest`, Lush Caves `great_drip_cavern`, Copper Works `master_furnace`, Deepslate
`silent_deep`, Frostworks `big_freeze`, Prismarine `monument_heart`, Drowned Vault `wardens_deep`, Basalt Foundry
`great_crucible`, Blackstone `bastion_keep`, Ender Archive `last_index`, The End `world_rim`. Not built: the Endless Mine
(`The Working Face`, low priority). Each is a straight (west and east door) room, so it competes only for straight
cells; a tee, corner or dead end variant of the most oversold floors would raise how often it appears. None has been
seen in game; check the look, the ore amounts and how often the room rolls on a final floor.

Update 2026-10-10: the Kennels (added 2026-10-09) end in `alphas_den`. The Endless Mine now hides ore in its walls and
richer ore as it deepens. Clearing a final floor's spawners now starts the dungeon's finale (a wave, and from Act 2 a
named elite) before the pad opens, so a final room should leave open ground for it; capstones keep their own fights.
