# Decision 2026-10-11: the Endless Mine, one per act

Task T40 of `docs/roadmap-build-2026-10-11.md`. Owner decisions from `docs/concepts-2026-10-11.md` (the Endless Mine
rows and the act gate row). This is the engine and the data model; the real guardians are tasks T41 and T42.

## The model, in the player's words

Every act has its own Endless Mine. A guardian waits on every third floor (3, 6, 9 and on), and that floor is the
checkpoint that pays: the first guardian pays tier A, the second tier B, the third and every one after pays tier C.
Every guardian is harder than the last, with no cap. Clearing the first guardian floor of an act's mine counts
toward opening the next act. There are no depth layers and no sealed shaft any more.

## The numbers

| What | Value | Lives in | At risk |
|---|---|---|---|
| Guardian cadence | every 3rd floor | knob `endlessGuardianEvery` (3) | nothing; the party can go HOME at any checkpoint |
| Guardian number n | floor / 3 | `EndlessMineRules.guardianNumber` | |
| Lap slot | `(n - 1) mod 3 + 1` (the act's three guardians repeat) | `EndlessMineRules.slot`, `lap` | |
| Hardness | `1 + 0.25 (n - 1) + 0.5 lap`, no cap | knobs `endlessGuardianHardnessStep` (0.25), `endlessGuardianLapStep` (0.5) | guardian stats, count, damage (each guardian defines its own use) |
| Loot tier | guardian 1 A, guardian 2 B, guardian 3 and later C | `EndlessMineRules.tierIndex`, tables `loot_table/endless_mine/act<N>_<a/b/c>.json` | |
| Act 1 tier A | coal, iron, copper (plus cobblestone, torch) | `act1_a.json` | |
| Act 1 tier B | A plus gold and lapis | `act1_b.json` | |
| Act 1 tier C | B plus redstone, plus a 15 percent diamond | `act1_c.json` | |
| Act 2 tier A | iron, gold, lapis (plus cobblestone, torch) | `act2_a.json` | |
| Act 2 tier B | iron, gold, lapis, redstone, fatter piles | `act2_b.json` | |
| Act 2 tier C | A and B stock, fattest piles, plus a 30 percent diamond (1 or 2) | `act2_c.json` | |
| Floors between guardians | the ordinary floor haul, at the mine's loot band minimum | `LootBands.floorTier` | |
| Act gate | the act's mine cleared to floor 3 (its first guardian) | `ActProgress.mineTarget`, `MineDepth.reaches` | |
| Act 1 mine | `endless_mine`, band 1 to 2, base level 1, door at compass 3 | `dungeon/endless_mine.json` | |
| Act 2 mine | `endless_mine_deep` (the Deep Mine): Deepslate, tuff, dripstone; band 2 to 3, base level 6, door at compass 6 | `dungeon/endless_mine_deep.json`, `dungeon_theme/endless_mine_deep.json`, `worldgen/processor_list/theme_deep_mine.json` | |

Quantities in the tier tables are placeholders for tuning. The placeholder guardian is a ring of vanilla mobs
(zombies in the Act 1 mine, skeletons in the Deep Mine), `round(1 + 2 hardness)` of them (3 at hardness 1, capped at
24), their stats multiplied by `ordinary mob scale x (1 + hardness)`.

## The rules

1. A mine is a dungeon json of kind `endless` with an `act`, hall `special`. The astrolabe shows the mine of the act it
   is turned to, once that act is open and the compass reaches the mine's level (`baseLevel`, so the Deep Mine opens
   where the Act 2 dungeons do). Acts 3 to 5 get a mine by adding a dungeon json, a theme, an adventure, and tier
   tables `act<N>_a/b/c`; no Java changes. Until a mine exists for an act, that act asks nothing of a mine.
2. Every floor of a mine is stamped in that mine's own theme, palette, hidden ore and loot band. The run remembers which
   mine it is in (`IntervalState.mineDungeonId`, in memory like the rest of the interval).
3. On floor `k * 3` the terminal cell holds the guardian. The registered guardian for the act and the lap slot spawns;
   with none registered the placeholder wave does. Its pad stays shut ("The guardian still stands. Finish it first.")
   until every tagged mob is dead.
4. Clearing a guardian floor fills the reward barrel from the guardian's tier table for the mine's act (the table
   replaces the tier table for that floor; the chest count is unchanged). A floor between guardians fills it from the
   ordinary tier table as before.
5. The party can go HOME at any checkpoint as before. Banking is the same average-of-doors rule; the Mine never raises
   the power ceiling.
6. The deepest cleared floor is kept per mine. The Act 1 mine keeps the old `deepest_mine_floor` field; later mines
   live in `mine_floors`. "Reaching floor 3" means clearing floor 3, the guardian floor, since the record is written
   when a floor is cleared.
7. A mine floor's level is the mine's `baseLevel` plus the floors down plus the dealt step, clamped to the compass
   maximum.

## Migration

- Saves: `mine_floors` is a new optional map (act number to floor), left out of the written save while empty, so every
  existing fixture still reads and writes byte for byte as before. `deepest_mine_floor` is unchanged and is the Act 1
  mine's record. A malformed act key in `mine_floors` is dropped; a key for act 1 is ignored. Golden fixture
  `src/test/resources/dungeonlog/mine_floors.json` pins the new shape; the existing fixtures pin the old ones.
- Acts: a player who already passed an old layer threshold keeps the act. `MineDepth.reaches` treats the old record as
  satisfying the new gate: floor 5 of the old mine for act 1 (and so any record of 3 or more), floor 11 for act 2,
  17 for act 3, 23 for act 4. Opened acts are stored sets and are never taken away.
- A shaft that was sealed at a layer boundary is not sealed any more; the in-memory sealing state is gone.

## Supersedes

- `docs/ZONES_SPEC.md` section 3.5 (the four depth layers, floors 1 to 5, 6 to 11, 12 to 17, 18 and on) and the
  mine payout rows of its section 5 in so far as they name layers.
- `docs/DUNGEON_STRUCTURE_DESIGN.md` D13 (each depth layer needs its act; the loot tier climbing every 3 floors within a
  layer's band) and the Endless Mine part of D29 (the mine reached the bottom of the act's layer, floors 5, 11, 17, 23).
  The mine still opens in Act 1 on `endlessMineUnlockLevel`.
- The Mine theme's `loot_tier_every: 3` zone rule (removed from `dungeon_theme/endless_mine.json`).
- `docs/concepts-2026-10-11.md`: the Endless Mine row, the act gate row and the Act 1 and Act 2 Endless Mine rows are now
  built as an engine; the guardians themselves are T41 and T42.

## Knobs

- `endlessGuardianEvery` (3), `endlessGuardianHardnessStep` (0.25), `endlessGuardianLapStep` (0.5) in
  `pocketdungeons.json`. The act gate follows `endlessGuardianEvery`.
- `endlessMineUnlockLevel` (3): the compass for the Act 1 mine's door. Later mines use their `baseLevel`.
- Loot: the six tier tables under `loot_table/endless_mine/` (quantities, weights, the diamond chances).
- Per mine data: `lootBand`, `baseLevel`, `nodePalette`, `hiddenOre` in the dungeon json; the palette in the theme and
  its processor list.
- `tools/tune_loot.py` tunes spawner key weights only; it does not touch these tables. They are exempt from the K and K2
  loot policy on purpose (`endless_mine/` in `tools/loot_rules.py` and `LootRulesTest`), because copper, gold and
  redstone are the owner's asked-for mine payout.

## Open until played

- Does a guardian every 3 floors feel like a rhythm or a chore? Is the first guardian (floor 3, about three floors of
  work) a fair gate for the next act?
- Should the gate count arriving on floor 3 rather than clearing it? Today it is clearing, because the record is written
  at a floor clear and the guardian floor is the paying checkpoint.
- Is a ring of plain mobs a fair stand-in, and does the hardness curve (1, 1.25, 1.5, then 2.25, 2.5, 2.75, then 3.5)
  climb too slowly or too fast? Do ordinary floors between guardians still feel worth the descent without the old
  per-floor tier climb?
- Tier C pays on every guardian from the third on, so deep mining is a steady faucet. Is that too much raw material?
- The Deep Mine palette (tuff bricks 30 percent in place of stone bricks, dripstone block 8 percent in the floor) is
  a first pass and unseen.
