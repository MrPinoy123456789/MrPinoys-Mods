# Dungeon structure reference

One trip is one dungeon. The data format for `dungeon/*.json` and `dungeon_room/*.json` is specified in `docs/INTEGRATION.md` sections 1.10 and 2; the settled design history lives in `docs/DECISIONS.md`.

## 1. Acts and unlock chain

The campaign is five acts following Steve's playthrough (`ActProgress`):

| Act | Name | Dungeons | Capstone | Endless Mine | Loot band |
|---|---|---|---|---|---|
| 1 | First Iron | Mineshaft, Lush Caves (`rootworks`), Infestation, The Kennels, Ossuary | Spawner Dungeon | `endless_mine` | 1 to 2 (stone, copper, iron) |
| 2 | The Deep | Cow Pits, Deepslate, Copper Works, Frostworks | Ancient City | `endless_mine_deep` | 2 to 3 (iron, diamond) |
| 3 | The Monument | Prismarine, Sunken Treasury (heist), Lighthouse Below (haunt) | Drowned Vault | (data hook ready) | 3 (diamond) |
| 4 | The Nether | Basalt Foundry, Blackstone, Gilded Seam (claim) | The Wither's Keep | (data hook ready) | 4 (netherite) |
| 5 | The End | Ender Archive, The End | Herobrine | (data hook ready) | 4 |

- **Unlocking the next act:** Act `N` opens for a member when they have finished every dungeon and capstone in Act `N - 1` and cleared floor 3 (the first guardian) of Act `N - 1`'s Endless Mine (`ActProgress`, `MineDepth`). Progress is evaluated per member present at each finish.
- **Approved, not built (2026-10-18, `docs/DECISIONS.md` "Campaign and endgame"):** the capstone opens after three of the act's dungeons, the mine stops being a gate, the compass becomes a quest line in the campaign, and the Chart Room opens after the last act on the server. Until it is built, the unlock rule above is what the code does.
- **Loot band cap:** The act caps the loot and node tiers a dungeon can pay (`LootBands`); compass + step scales mob difficulty and moves weights within the band, never above it.

## 2. Choosing a dungeon: the Astrolabe Room

The first staging room of a trip is the Astrolabe Room (`HallRoom`, `StagingFurniture`):

- Right-click the astrolabe to cycle through unlocked acts (sneak + right-click cycles back).
- Each 1x2 door on the main wall is a dungeon in that act; side doors hold the act's Endless Mine.
- Copper bulb state above each door: **lit** = open, **oxidized** = already finished (`Play again`), **dark behind an iron door** = locked (sign shows `Compass N` or `Finish Act N`).
- Opening a door reveals a preview of the dungeon's first room through the back glass wall and makes the adjacent `DESCEND` lever glint. Pulling `DESCEND` commits the door.

## 3. Dungeon graph, floors and doors

Each dungeon is a directed acyclic graph of named floor nodes (`DungeonDef`):

- **Layers and edges:** 2 to 6 layers, at most 3 outgoing edges per node. At least one outgoing edge on every non-final floor must be free (`lives: 0`). A floor with fewer than 3 outgoing edges fills the spare doors in "the Doors" staging room with the same branch at the remaining steps (+1, +2, +3).
- **Floor level:** A dungeon declares `baseLevel`; a node runs at `baseLevel + layer - 1` unless it pins its own `level` (`FloorLevels.of`). The door's level is `nodeLevel + step`.
- **Side doors and Blood:** Side edges (`lives: 1` or `2`) and the `blood` affix (`life_cost: 1`) spend party lives on commit and are refused if fewer than 1 life would remain.
- **Objectives (`objective` on a node):** Defaults to `spawners`. Act 3 and 4 introduce `heist` (channel the Vault Core in Sunken Treasury), `claim` (stake gilded veins in Gilded Seam), and `haunt` (charge two beacons in Lighthouse Below while the Keeper hunts); see `FloorObjectives` and `docs/DECISIONS.md`.
- **Finales (`finale` on a dungeon):** Most dungeons end in a last-stand wave or boss script (`FinaleScript`, `FinaleScripts.registerAll()`) when the final floor's spawners clear.

## 4. Themes and deviation guardrails

Every dungeon has one `mainTheme` that sets its identity, loot band, node palette and merchant currency, but individual floor nodes or rooms may borrow from another theme under strict guardrails (`RoomEligibility`):

1. A floor or room only borrows from a theme of the **same act or an earlier act**.
2. Loot band, ore/node tiers and merchant currency always follow the **dungeon's act and main theme**, never the borrowed theme.
3. The **entry floor and final floor** always use the dungeon's `mainTheme`.

## 5. Block breaking, resource nodes and hidden ore

- **Cell shell vs interior:** The outer bedrock/barrier envelope (`BedrockEnvelope`) and room/ordeal/objective fixtures (`RoomProtection`, `FloorObjectives.isFixture`) are unbreakable. Ordinary interior blocks break only with the matching tool; player-placed blocks break with any tool.
- **Resource nodes (`nodePalette`):** Rooms stamp `pocketdungeons:node` markers that resolve to the dungeon's themed resource blocks (logs, ores, clay, stems). Dungeons that lean on harvesting set `minNodeRooms`.
- **Hidden ore (`hiddenOre`):** Dungeons and Endless Mines can replace a fraction of interior stone/deepslate with ore veins, shifting to `deepBlocks` every `deepEvery` floors.
