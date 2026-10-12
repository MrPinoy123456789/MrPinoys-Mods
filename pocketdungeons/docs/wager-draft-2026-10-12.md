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

## The skew: the copper chest piece (owner clarification, 2026-10-12)

The summed wager decides what the copper chest at the end of the floor pays. It does not touch the chest and vault
tier tables.

**Rule.** A floor whose affixes wager something pays one extra piece of gear into its copper chest, seeded like
promised gear (owner, trip salt, node; `PromisedGear.extraSeed`), so the door board and the chest agree. The piece is a
weapon, a tool, a piece of armour or a material, picked evenly. The tier starts at the floor's base tier (the node's own
`gear:<slot>:<tier>` reward if it has one, else the dungeon's loot band floor) and rises by one with a chance of
`min(wagerTierChanceCap, wagerTierChancePerPoint x wager)` percent (20 per point, capped at 90), never above the dungeon's
loot band (D10). Wager 0 pays nothing extra. `wagerGearEnabled` false turns the whole thing off.

- `material` is a new gear slot: `loot_table/gear/material_1..4` (iron and lapis, then diamonds and emeralds, netherite
  scrap at tier 4). Tier 4 tool and armour tables were added so a band 4 dungeon can draw them.
- Not built: a better enchant level at wager 6 and up. The gear tables set `enchant_with_levels` in data, so a bonus
  would need a second code path; left out.
- Code: `Wager`, `WagerGear` (pure), `PromisedGear.extraPick` / `rollExtra`, `RunLifecycle.promisedItems`,
  `DungeonScreen.previewGets`. Tests: `WagerTest`, `WagerGearTest`, `WagerGearGameTest`.

### Superseded options

1. Upgrade chance on every chest draw: not chosen.
2. **Luck weights on the tier tables (was recommended): superseded by the owner's clarification of 2026-10-12.**
   No `quality` was added to any table.
3. Bonus rolls on every chest: not chosen (fights finite resources).

## Where the player sees it

The player never sees the word "wager" or its raw number. The door shows a loot percentage,
`100 + lootPercentPerWager x wager` (the knob is 50): "Loot 100%" for a floor with no affixes, "Loot 200%" at wager 2,
"Loot 400%" at wager 6.

- The door board and the deal sheet: "Loot 200%" after the affix names, white at 100 and gold above. The piece the
  floor adds is named in the line of what the floor gives, beside the other promises.
- The floor title: the affix lines, then "Loot 200%" as the last line (always shown, "Loot 100%" with no affixes).
- Display only: the percentage changes nothing by itself; the mechanics are the copper chest rule above.
- The chest or vault itself needs nothing.

## Decisions (owner, 2026-10-12)

1. Blood pays its 1 life once, when the door is committed.
2. The skew is the extra copper chest piece (was: luck weights, option 2, superseded the same day).
3. The player reads "Loot n%", never "wager".
