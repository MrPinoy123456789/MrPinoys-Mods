# Wager: affix values and the loot skew (draft, 2026-10-12)

Owner direction, 2026-10-12: "Blood" becomes an affix that simply costs 1 life. Every affix has its own value based on its difficulty or wager; the sum of the values heavily skews the loot odds.

Status: approved 2026-10-12 with the choices below. Built by task T50.

## The rule in the player's words

*The harder the floor's affixes, the better the loot. Every affix says how much it wagers; the door shows the total.*

## Wager values (proposed, to tune)

| Affix | Wager | Why |
|---|---|---|
| Swarming | 1 | more bodies, nothing new to learn |
| Overclocked | 1 | faster spawners, but it pays a par bonus |
| Silenced | 1 | a cost on healing, shown as n/3 |
| Blackout | 2 | sight pressure |
| Explosive | 2 | floor mines |
| Molten | 2 | floor lava |
| Loaded | 2 | an ambush on a free cache |
| Restless | 2 | every mob rises once |
| Voided | 3 | falls, compass 45+ |
| Cooked | 3 | the whole run ominous |
| Blood | 3 | costs 1 life |

A floor's wager is the sum over its affixes. Compass 5 to 24 carries one seeded affix, 25 to 44 two, and so on, so a typical sum runs 1 to 3 early and 6 to 9 late.

## Blood

An affix that costs 1 life, nothing else. It follows the old blood door rule: refused unless at least one life would remain after the cost (`docs/decision-2026-10-07-haul-and-blood-doors.md`). It replaces the `lives` edges in the dungeon files (the Deepslate blood door could not be built under the loader; this removes that problem).

## Skew options (stay inside the act's loot band, D10)

1. **Upgrade chance.** Each chest or vault draw has a chance to come from the next tier's table, capped at the act's band. Chance `min(0.9, 0.2 x wager)`: wager 1 is 20 percent, 3 is 60 percent, 5 and up is 90 percent. Simple and very visible, but it caps out at the band.
2. **Luck weights (recommended).** The wager becomes loot luck: entries marked rare in the tier tables get heavier by a factor per point, the item count stays the same. Wager 3 roughly doubles rare entries, wager 6 roughly quadruples them. It stays inside the band and keeps scarcity (no extra items).
3. **Bonus rolls.** Every 2 wager adds a roll to each chest. More items, same rarity. It fights the finite resources value, so it is the weakest fit.

## Where the player sees the number

- The door board and preview: "Wager 5" next to the affix names.
- The floor title: the affix lines, then "Wager 5".
- The chest or vault itself needs nothing.

## Decisions (owner, 2026-10-12)

1. Blood pays its 1 life once, when the door is committed.
2. The skew is luck weights (option 2).
