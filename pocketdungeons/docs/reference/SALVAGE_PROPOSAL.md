# Salvage bench: a sink for surplus gear and vault keys

Status: built 2026-09-29 (`SalvageStation`, `SalvageMath`; tests `SalvageMathTest`, `SalvageGameTest`; live checks in `LIVE_TEST_PASS.md` section 49). Pending in-game verify. Source: playtest 2026-09-29 #1 (A3), 19:50:
"I'm accumulating too much gear and vault keys, I should have a way to scrap
them or redeem them for something."

## What the surplus actually is

The leave logs from 2026-09-29 show what piles up: 3 to 4 trial keys at every
exit, and gear in two kinds.

- **Tagged gear**: loot-table gear (reward chests, vaults, the gamble) carries
  `custom_data.pocketdungeons.tier` 1 to 3. Duplicates of slots already worn.
- **Untagged gear**: mob drops. One exit held four bows, a crossbow, a stone
  sword and a leather chestplate. Skeletons and zombies drop these without end.

Keys pile up by design: a trial spawner ejects a key 60% of the time (emeralds
the other 40%), chests roll keys too, and there is exactly one vault per loot
cell (`TrialContent.applyLoot`), so the key budget always exceeds the vaults.

## The economy it plugs into

| Flow | Amount |
|---|---|
| Spawner eject | 60% one trial key, 40% 2 to 5 emeralds (1.4 emeralds expected) |
| Spawners per floor | 2 to 5 seen in play, about 3.5 |
| Emerald income, tier 1 | about 5 per floor, about 15 per 3-floor interval (chests and vaults add almost none) |
| Gamble (unlocks at level 10) | 6 emeralds per tier, weapons 1.5x (tier 1: 6 or 9) |
| Merchant | 1 to 10 emeralds per item |
| Fuel | 1 per free-door interval; a gated door costs 3 |

## The design

One station, the **salvage bench**: a `minecraft:grindstone` in the safe
room, claimed the way the reroll station claims its smithing table (only when
the player holds something salvageable; sneaking falls through to vanilla, so
the grindstone still disenchants). Right-clicking opens a 3-row SGUI screen:
drop items in, the bottom row shows the total, one **Salvage** button pays out.
Closing without salvaging hands everything back. Bulk, because clearing four
bows one click at a time is the chore the player complained about.

| In | Out | Why this number |
|---|---|---|
| Tagged gear, tier N | N emeralds | A tier-N gamble costs 6N, so scrap back into gamble is always 6 to 1, never a loop that pays. |
| Trial key | 1 emerald | Below the 1.4 a spawner's emerald eject is worth, so a key never beats the emeralds it displaced. |
| Ominous trial key | 3 emeralds | Rarer, opens a richer vault. |
| Untagged gear and tools | XP only, as a grindstone gives | Mob drops are unlimited; paying emeralds for them would let a skeleton farm print currency. |
| Anything else | refused, left in the screen | Not a bin. |

**Refused outright:** imbued gear (a Cube power on it) and trimmed armour.
Both carry an investment the player made on purpose; a misclick should not
destroy it. The screen says why.

**Unlock: level 1.** The surplus starts in the first interval, and before the
gamble opens at level 10 the merchant already takes emeralds.

**Every rate lives in `pocketdungeons.json`** (`salvageEmeraldsPerTier` 1,
`salvageKeyEmeralds` 1, `salvageOminousKeyEmeralds` 3, `salvageUnlockLevel` 1,
`salvageBlock`), next to the gamble and reroll costs.

## What it does to income

Surplus seen per interval: about 3 spare tagged tier-1 pieces and 3 spare
keys. That salvages to about 6 emeralds against a base of about 15, a 40% top
up: one extra tier-1 gamble every interval or so, never a replacement for
running floors. At tier 3 the gear side grows with the tier while keys stay
flat, which is the right direction since deeper keys are what vaults want.

## Deliberately left out

- **Keys to fuel.** Tempting (it ties surplus to door choice), but at 3 spare
  keys an interval even 4 keys per fuel nearly doubles fuel income (1 per
  interval today) the same week door 2 drops to level 7. Ship emeralds only,
  watch door 2 use, and add `salvageKeysPerFuel` (off by default) if doors
  stay starved.
- **Quality-based pay.** Paying more for better rolls rewards scrapping good
  gear, which is backwards. Tier only.
- **A salvage command.** Stations are blocks you use; a command is invisible.

## Owner decisions (2026-09-29)

1. Untagged mob gear gives XP only, no emeralds.
2. Keys trade for emeralds only; `salvageKeysPerFuel` ships off by default.
3. The bench is a grindstone, claimed only when holding something salvageable.
