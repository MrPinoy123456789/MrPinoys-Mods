---
name: dungeon-content
description: Author and change Pocket Dungeons data content: dungeon definitions (dungeon/*.json nodes, edges, lives, roomBias, hiddenOre, nodePalette), loot tables and the K/K2 loot policy, trial spawner configs, and the python generators in tools/. Lists the silent failure modes (old keys rejected, wrong base items, generated files that get overwritten) and the validation commands. Use when adding or editing a dungeon, a node, an edge, a loot table or a spawner config. For room geometry use room-building instead.
---

# Dungeon and loot content

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`. Data lives under
`src/main/resources/data/pocketdungeons/`: `dungeon/`, `dungeon_room/`, `dungeon_theme/`, `dungeon_affix/`,
`loot_table/`, `trial_spawner/`, `worldgen/processor_list/`, `structure/rooms/`. JSON schemas for some
formats are in `docs/schema/` (`dungeon_room`, `dungeon_theme`, `dungeon_adventure`, `dungeon_bag`,
`anomaly_room`, `diary`).

## Dungeon definitions (`dungeon/<id>.json`)

Top level: `name`, `act` (1 to 5), `baseLevel`, `kind` (`dungeon`, `capstone`, `endless`), `mainTheme` (the
theme id whose rooms and processors the floors use), `lootBand {min,max}`, `nodePalette` (block ids that
count as mineable nodes), optional `deviation {chance, themes[]}` (a floor sometimes borrows another theme),
`diary`, `minNodeRooms` (the floor must place that many node-bearing rooms), `hiddenOre`
(`pocketsMin/Max`, `sizeMin/Max`, optional `blocks[]`; buries ore in solid rock, a block listed twice is
twice as likely), `nodes[]`, `edges[]`.

- **Node:** `id`, `name` (shown to the player as the floor name), `layer`, optional `theme` (borrow a
  theme for this floor), `signatureAffix`, `roomBias[]` (rooms drawn at 3x weight here; each must exist),
  `roomCount`, `light` (`lit`/`dim`/`dark`), `rewards[]` (`{item,count}`), `final: true` on the last floor,
  `merchant`.
- **Edge:** `from`, `to`, and optionally `lives` (0 to 2, default 0). `lives` is what a side door costs in party
  lives (Haul and Blood Doors, 2026-10-07). **The old `cost` key is rejected by the loader**; do not write it.
  A door is refused if it would take the last life.
- Edges form a graph from the first node to the `final` node; the loader validates reachability and names.
  A final node's name is a promise to the player (PD-173): back it with rooms that sell it
  (`graphRole: ["final"]`, see `docs/reference/FINAL_FLOOR_ROOMS.md` and the `room-building` skill).
- Resource-poor dungeons (Infestation, Ossuary, Lush Caves) get `hiddenOre` or ore rooms; a dungeon with
  neither never buries anything in walls, and its palette nodes are invisible (PD-161, PD-147 family).

## Where a dungeon stands in the Astrolabe Room

The first staging room shows one act's dungeons as a row of doors, in order of `baseLevel` (the compass they need),
then by id. Every dungeon JSON carries `"token": { "mat": "minecraft:<block>" }`, the doormat laid in front of its
door; give each dungeon a block nobody else uses (`HallDataTest` checks it is present and distinct). The row holds
at most six dungeons per act. `"hall": "special"` (the Endless Mine only today) puts a dungeon at an end of the row
once its own rule opens it, instead of in its act's row. A capstone shows an iron door until its act is finished.

## Dungeon and floor notes (the reason to choose a door)

The small grey line at the bottom of the door board is the reason a player would pick this door over another
(owner, 2026-10-08). Write it for that:

- Fields: `"notes"` on the dungeon (shown on its entry floor, which is also the choice of dungeon) and `"notes"` on every other
  node. At most 90 characters, one short sentence group, no dash punctuation (the loader refuses both). The Endless
  Mine keeps its own code line.
- Say what THIS floor offers that its siblings do not: its signature set piece or rooms ("Glow berries and sandbars"),
  its ore, a hazard that is also the draw ("Molten floors: the only lava you will find"), a vault that needs a key
  ("A barred vault. Bring a trial key."), a detour, or that it is the quiet choice. The last floor says "The last floor." and
  names its final room's draw.
- Do not repeat the deal sheet (price in lives, rewards) or the lines the board already prints (affixes, light).
- Be honest. Floors are random, so say what the floor's biased rooms offer ("Burial niches of bone, coal and iron"), never a
  guarantee the planner cannot keep. `FloorNotesTest` fails if a note names coal, iron, gold, lapis, diamond, redstone,
  sand, clay or bone that no biased room declares (or, for a dungeon note, that no room bound to the dungeon or its palette has),
  if two floors of a dungeon share a note, or if an Act 1 or 2 floor has none.
- When a room or `roomBias` change what a floor offers, change its note in the same commit.
- Derived fallback (`BoardText.notesLine`) still covers a floor with no note: dark, dim, last floor, the palette's ores.

## Validation

After any data change run, in order:
1. `.\gradlew.bat test --console=plain` (the data tests: `DungeonDefTest`, `PackValidator`-backed tests,
   `LootRulesTest`, `FloorNotesTest`, `TripDoorsTest` and the JSON-reading ones).
   The floor soak (`FloorSoakGameTest`, `PD_SOAK_SEEDS=150 ./gradlew.bat runGameTest`) plans every Act 1 and 2 floor over
   many seeds; run it after changing rooms, `roomBias`, `borrowableBy` or `minNodeRooms`.
2. `.\gradlew.bat dungeonIntegrationTest --console=plain` (boots the real server with the datapack; prints PASS).
3. On a running server `dungeon admin validate` (via `pdserver.mjs cmd dungeon admin validate`) lists every
   cross-resource finding as file, field, cause and a reproducible seed. It catches a `roomBias` naming a room
   that does not exist, an unknown role or `graphRole`, a bad theme or affix id, and node boxes out of range.

## Loot tables (the K and K2 policy)

The owner signed off one policy for item entries: chests feed you, vaults equip you. Food is bread, steak and
rare cakes; blocks are cobblestone, sand, gravel, wood and planks; **no seeds, no redstone, no brewing items,
one trim, no chainmail, no gold gear, no trident, no bones, string, leather, copper or snowballs** in loot
tables; supply chests carry room tools, gold and pearls; vaults may carry gear. This governs **loot tables
only**; world blocks (ores, webs, cows, bone blocks placed by rooms) are not covered, so a room may offer
those. `tools/loot_rules.py` holds the rule (`rewrite_table(rel_path, table)`) and `LootRulesTest` pins the
shipped tables against it. If a table you add trips the test, fix the table, not the test.

Silent failures to avoid:
- `enchant_with_levels` only enchants a plain `minecraft:book`. Naming `minecraft:enchanted_book` as the
  item rolls a book with no enchantments (PD-175). `BookLootTest` scans for it.
- A trial key and an ominous trial key are different items (PD-170); anything keyed to "the trial key" must
  decide whether it accepts both (`Locks.matches`).
- Cow Pits tables must stay free of wheat, seeds, carrots and potatoes (the floor is a finite larder).
- A misspelt trial spawner config id does not throw: the codec drops the field and the spawner keeps the
  default config. `admin cellreport` reads the ids back.

## Generated files: edit the generator, not the output

`tools/*.py` overwrite their output in place:
- `gen_themed_content.py` (spawner configs and layered chest tables for the themed dungeons),
  `gen_resource_content.py` (mineshaft and Cow Pits), `gen_vault_tables.py` (all vault tables),
  `gen_w7b_content.py` (capstone content), `apply_loot_rules.py` (rewrites the hand-authored tables to the policy;
  idempotent). Run from the mod root, for example `python tools/gen_themed_content.py`.
- A hand edit to a generated file is lost on the next run. Hand-authored files (themes, adventures, processor
  lists, signature rooms, affixes, the two hand vaults, the `spur_*` vaults) are safe.
- The vanilla ominous sky-drop trial spawner tables (130 `trial_spawner` JSONs) come from
  `gen_themed_content.py`; they hold splash potions on purpose, not loot.

## Trial spawner configs

`trial_spawner/<prefix>_tier_N/{normal,ominous}.json`, chosen from the run theme's `spawner_prefix` (or the
theme's `normal_spawner`/`ominous_spawner`). Situation rooms use a fixed prefix (`barred_vault/normal`).
Ominous mode swaps `trial_key` for `ominous_trial_key`. The generators scale mob counts and cooldown per tier.

## Docs to keep in step

A change to a dungeon's economy or rules updates `docs/reference/` (the relevant table or `BUGS.md` status) and,
for a model change, a dated `docs/decision-<date>-<topic>.md`. The Lemon knowledge pack
(`tools/lemon/out/pack.md`) and `docs/INTEGRATION.md` still describe the pre-haul scrap model until someone
updates them; do not copy their scrap wording.
