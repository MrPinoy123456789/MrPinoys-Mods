---
name: tune-bags
description: Change Pocket Dungeons bags (the kit a player picks at the bag chest). Covers the three places a kit change must land (dungeon_bag JSON, loot_table/bags JSON, tests), which bags are actually offered versus module gated, the spec rules BagTableTest enforces (food, no armour, stone weapon cap, bag tag, Pilgrim bread only), file format traps (BOM, CRLF), and how a change reaches players. Use when asked to add or remove kit items, rebalance a bag, add a bag, or make a cut bag offered again.
---

# Tweaking bags

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`.

## Which bags exist, which are offered

Ten bag definitions ship in `src/main/resources/data/pocketdungeons/dungeon_bag/`. Only **five are offered** at the
bag chest (decision L1 / D40, pinned by `BagTableTest.checkOfferedAreTheFive`):

| Offered | Cut (claimed by the `extra_bags` content module, `default: false`) |
|---|---|
| lumberjack, sapper, ranger, innkeeper, guard | pilgrim, shepherd, mason, magician, plumber |

The cut five still load, validate and are tested, but a player cannot pick them unless the `extra_bags` module is on
(`content_module/extra_bags.json`). So a change to a cut bag changes nothing in play. Say which side a bag is on when
you report. Do not assume "all kits" reaches players until you have checked this list.

The picker (`DialogScreens.bagOptions`) lists `BagDefinition.offered(...)`: every loaded definition minus `hidden`.
A `hidden: true` bag is not offered but still works for a player who already holds it.

## A kit change lands in three places

A kit is granted once, when the bag is chosen (`Bags.apply` rolls the loot table). There is no top up.

1. **`dungeon_bag/<id>.json`**: `kit_baseline` (the declared contents: `item`, `count`, `durability` true for tools,
   optional `empties_into`) and `headline` (the three items the door frames show; keep to three).
2. **`loot_table/bags/<id>.json`**: the table that is really rolled. One pool per item, `rolls: 1`, with
   `set_count` for stacks. Tools and armour-like gear carry `custom_data.pocketdungeons.bag = 1`.
3. **Tests**: see below. Update the one that pinned the old behavior rather than loosening it.

`PackValidator.kitBaselineFindings` fails the pack if the baseline asks for more of an item than one roll of the
table gives, if a durability item is not marked `durability: true` (or the reverse), or if an item id is unknown. So
the two files must agree. A bag that already has the item (mason and innkeeper have torches) needs its count raised
in both files, not a second entry.

## Rules the tests enforce (`BagTableTest`, spec 3.2 and 11.9)

- every bag carries food;
- no armour in any bag;
- no weapon better than stone;
- every non stackable item has `custom_data.pocketdungeons.bag`, and a stackable one must not;
- **Pilgrim is bread and nothing else** ("every tool must come from a room"). Adding anything to Pilgrim fails
  `checkPilgrimIsBreadOnly`; that is an owner design call, so ask before changing it and update the test in the same
  commit if approved;
- exactly the five bags above are offered, the other five are module claimed.

Other tests that read bags: `BagSelectionTest`, `PackValidatorTest`, `CubeRecipeGameTest` and the
`pocketdungeons-gametest` foreign bag fixture. Run the full suite (`tools/ship.sh`, see `add-tests`) after a kit edit.

## File format traps

- Some bag JSON starts with a UTF-8 BOM (`mason.json`); keep it. Strip it before `json.loads` (`utf-8-sig`) and write
  it back.
- Line endings differ per file (`guard.json` is LF, most loot tables are CRLF). Read the bytes, keep the style, and
  round trip with `json.dumps(indent=2)` only after confirming it reproduces the file exactly. `dungeon_bag` files
  are single line JSON; edit them as text.
- Loot tables are two space indented, one object per pool.

## Adding a bag

1. New `dungeon_bag/<id>.json` and `loot_table/bags/<id>.json` (copy `guard`).
2. Built in bags need a constant in `BagIds` (the legacy bare name bridge) and an entry in `BagTableTest.BAG_IDS` and
   either `OFFERED` or the `extra_bags` list.
3. Tags come from the closed `SituationTags` vocabulary; an unknown tag is rejected at load.
4. `order` sets the picker position; ties break by id.

## Making a cut bag offered

Remove it from `extra_bags.json`'s `bags` list, add it to `BagTableTest.OFFERED` and fix the test's "five" wording.
That is a design change to L1 (D40): use `design-brief` and record it in `docs/DECISIONS.md` first.

## Reaching players

The data is in the jar, so a kit change needs `gradlew dist` and a deploy (`deploy-test-server`). It never changes a
kit a player already holds; it applies to the next bag pick, which happens after `/dungeon resetkey`. Say that in the
report. Live check owed: pick the bag and read the inventory.
