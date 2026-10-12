# T50 report: Wager and Blood

Branch `feature/rm-t50-wager-blood` (base `feature/act-1-2-roadmap`, merged twice). Design: `docs/wager-draft-2026-10-12.md`
(updated with both owner corrections of 2026-10-12).

## What was built
1. **Wager field.** `AffixDefinition.wager` (0 to 10, a file with none wagers 1, rejected outside the range at load).
   Values in the JSON files as in the draft table; Feral 0. Pure `Wager.sum` and `Wager.lifeCost`.
2. **Blood.** New closed set op `life_cost` (0 to 2) in `AffixEffects` and `AffixMeta`; `dungeon_affix/blood.json`
   (label and short name "Blood", min_level 0, weight 1, wager 3, order 12); id appended to `AffixIds.BUILT_IN_ORDER`;
   colour dark red in `Keystone`. The price is `Keystone.livesCost(owner, offer)`: the edge `lives` plus the dealt
   `life_cost`. The preview gate, lever screen, action bar, board and commit all read it, and `settleCommit` takes it once
   through the existing `Omen.add` path. Refused unless a life remains. A Blood-only door announces "paid a life for the
   door"; the edge wording stays "side door".
   Dealing: the deal is not lives-aware (it must match between preview and commit), so `DoorAffixes.deal` is unchanged and
   an unaffordable Blood door is refused at the lever and preview with the existing lives line. Risk: at 1 life, every door
   that carries Blood (the three doors usually share the seeded set) is refused and the party can only go home. Owner to judge.
3. **Skew (corrected rule).** An extra piece in the copper chest. `WagerGear` (pure): slot even over weapon, tool, armour,
   material; tier chance `min(90, 20 x wager)` percent for one tier up from the base, clamped to the dungeon `lootBand`.
   `PromisedGear.extraPick` and `rollExtra` seed it from owner, trip salt and node like promised gear; it is paid in
   `RunLifecycle.promisedItems` and named on the board in `DungeonScreen.previewGets`. The base tier is the node's own
   `gear:<slot>:<tier>` reward, else `lootBand.min`. Wager 0 pays nothing. New data: `gear/material_1..4`, plus `tool_4`
   and `armour_4` (so band 4 dungeons can draw them), registered in `LootTables`. `FloorState.boardAffixes` records the set
   the board dealt, so a commit time omen roll cannot make the chest disagree with the board.
   Knobs: `wagerGearEnabled` (off switch), `wagerTierChancePerPoint` 20, `wagerTierChanceCap` 90, `lootPercentPerWager` 50.
   Not built: the better enchant at wager 6 and up (the gear tables fix `enchant_with_levels` in data). The chest and vault
   tier tables are untouched and no `quality` was added. Endless Mine and nodeless floors pay no extra piece (no node to seed on).
   `material_4` uses netherite scrap; `tools/apply_loot_rules.py` would rewrite scrap to ingot if rerun over gear tables.
4. **Legibility.** "Loot n%" (100 + 50 x wager) after the affix names on the board name line, on the deal sheet affix
   line, and as the last line of the floor title. Always shown ("Loot 100%" with no affixes). Gold above 100 on the board;
   the title uses its single style. The word wager never reaches the player.
5. **Ledger.** Row 12 added to `docs/affix-workshop-2026-10-11.md`.

## Numbers
- Wager by compass (one seeded affix at 5 to 24, two at 25 to 44, three at 45 to 64, four at 65 to 84, five at 85 and up;
  the pool averages about 2 per affix): 5 to 24: 1 to 3, average 1.9 (Loot about 195%); 25 to 44: 2 to 6, average 3.8
  (about 290%); 45 to 64: 3 to 9, average 6 (about 400%); 65 and up: 8 to 10 on average. Node signature affixes add on top.
- Luck factor: 20 percent per point to go up one tier, cap 90. Wager 2 is 40 percent, 4 is 80, 5 and up is 90. Heavy, as the
  owner asked, yet bounded by one tier and the band, so Act 1 never sees diamond.
- Edges that still carry `lives` (untouched): basalt_foundry slag_channels to gilded_annex 1; blackstone piglin_barracks to
  slag_vents 1; copper_works boiler_hall to gear_loft 1; drowned_vault drowned_nave to coral_ruins 1; ender_archive
  shelf_labyrinth to reading_rooms 1; frostworks glaze_furnaces to glaze_vault 2; infestation silverfish_nests to
  hollow_walls 1; kennels lodge_gate to bone_yard 1; ossuary charnel_steps to sealed_reliquary 1; prismarine kelp_tunnels
  to rusted_pumps 1; rootworks tangled_hollow to sapping_den 1; the_end outer_islands to end_ship 1; wither_keep
  fortress_bridges to blaze_spire 1. A Blood affix on such a door adds to the edge price.

## Tests
New: `WagerTest`, `WagerGearTest` (pure, wired in build.gradle.kts) and `WagerGearGameTest` (4 methods: the chest pays the
board's piece and wager 0 pays nothing extra; every slot and tier table rolls; a high wager draws the higher tier far more
often over 600 owners; Loot text and Blood cost). Config fixtures and `config/pocketdungeons.default.json` regenerated.
- Before (base branch, pre-merge): 261 gametests, 111 pure tasks. After my change, pre-merge: full `ship.sh --quick` GREEN,
  265 gametests, 113 pure tasks.
- After merging the newer `feature/act-1-2-roadmap` (conflict free): the full suite could NOT complete. Drive C: reached
  0 bytes free (the Gradle cache and temp live there), so compileGametestJava and several pure tasks failed with "not enough
  space on the disk". Not a code failure. Rerun `tools/ship.sh --quick` once C: has space.

## Only a live server can confirm
Whether the extra piece reads as worth the risk; whether the Loot percentages feel honest; whether a Blood door feels fair
(including the 1 life trap above); the gold and white colouring and the title line spacing; material stacks in the chest.
Live check fragment: `docs/playtests/live-checks/rm-t50.md`.
