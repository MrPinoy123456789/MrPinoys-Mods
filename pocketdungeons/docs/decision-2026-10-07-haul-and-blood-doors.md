# Decision 2026-10-07: Haul and Blood Doors

Replaces the spendable scrap pool (plan 2026-10-06-2 J1, D28, D32, D33) after the
2026-10-07 playtest (PD-162): the pool and the compass read as two competing levels, an
overleveled player could not earn what a side branch charged, and nothing but emeralds was ever
at stake. The owner asked for Darkest Dungeon style risk: failing loses progress, not everything.

## The model

The player's sentence: *Clearing floors fills your haul, and bringing it home fills your compass;
if the dungeon claims you, you lose half the haul, and side doors cost a life.*

| Number | Where it lives | At risk? |
|---|---|---|
| **Compass** (`DungeonLog.Entry.highestCharts`) | the keystone item, `Compass 12: 3/5 scrap to 13` | never; only rises |
| **Chart progress** (`chartProgress`, 0 to 4) | the compass bar | never |
| **Haul** (`haul`) | the clear line, the GO HOME board, the compass lore during a trip | yes: half lost to a failed dungeon |

- **Earning.** A floor clear pays `ScrapMath.floorPay(step, floorLevel, compass)` into each
  member's haul: the dealt step at or above their compass, 1 below it. No emeralds (D28 is gone).
- **Banking.** Going home, a checkpoint exit, a grace expiry and a finish bank 100 percent of the
  haul into the bar (`RunLifecycle.bankHaul`, from the log alone, so an absent member banks too).
  A haul carried out of a trip that ended while the member was away banks in full when they next join.
- **Failing.** The fifth death banks `failHaulKeepPercent` (default 50) of every member's haul,
  present or detached, and loses the rest, besides the finish rewards.
- **Side doors.** An edge now says `lives` (1, or 2 for Frostworks) instead of `cost`. The door
  takes that many lives from the party (the trip's omen, the same lives a death spends) and is
  refused unless at least one life would remain. The old `cost` key is rejected by the loader.
- **Scrap is never spent.** There is no spendable pool.

## Migration

The compass keeps its level. The bar starts at what the old pool held past the compass's own
whole charts (`ScrapMath.migratedProgress`, clamped to 0 to 4): a migrated or spent-down player
lands on 0/5, a never spent pool of 16 at compass 3 keeps 1/5. The haul starts empty. The first
join shows one message. The old `scrap` codec key is read once and never written again.

## Supersedes

- plan-2026-10-06-2: D28 (overlevel emeralds), D32 (scrap is paid and kept on the spot; amended:
  scrap fills the haul and the bar fills at a bank, going home still loses nothing), D33 (side
  branches cost scrap; replaced: they cost lives), D34 and J2 (amended: failure also halves the haul),
  J1 (replaced in full), J3 (amended: only a death and a side door raise omen), section B "Paying a
  floor", and "Changes to plan 2026-10-06-1" item 4 (the GO HOME board showed no scrap; it now shows the haul).
- DUNGEON_STRUCTURE_DESIGN: D11 (going home banks the haul plus chests), W5 (party scrap balance),
  the Banking row of the summary table.
- Kept: D23 (the compass gates dungeons), D24 (every exit settles the same), D27 (floor level is the
  node level plus the step), D29 (act completion).
- Bugs: PD-162 is resolved by the model change; PD-166's command became `/dungeon admin compass` and `haul`.

## Knobs

- `failHaulKeepPercent` (pocketdungeons.json, default 50): the share a failed dungeon keeps.
- An edge's `lives` in `dungeon/*.json`: 1 or 2.
- `ScrapMath.SCRAP_PER_CHART` (5).

## Open until played

The price of a life (if players rarely die, a side door is nearly free), whether half is soft or
harsh on failure, whether `+1 scrap` on a floor far below the compass reads as an insult, and
whether a two life Frostworks door is ever taken.
