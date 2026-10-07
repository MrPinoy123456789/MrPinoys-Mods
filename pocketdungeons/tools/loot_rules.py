#!/usr/bin/env python3
"""The K and K2 loot policy, shared by the content generators and the one-shot
rewrite of the hand-authored tables (plan 2026-10-06-2 sections K, K2, K3).

One rule, applied everywhere an item entry can appear: a table either keeps an
item, swaps it for its replacement in the same pool at the same weight, or
drops it. The goal the owner signed off: chests feed you, vaults equip you;
food is bread, steak and rare cakes; blocks are cobblestone, sand, gravel,
wood and planks; no seeds, no redstone, no brewing, one trim, no chainmail,
no gold gear, no trident, no bones, string, leather, copper or snowballs.

`rewrite_table(rel_path, table)` is the entry point. `rel_path` is the path
under `loot_table/` (forward slashes) and decides the context:

  supply_* chests    room tools, gold and pearls live here (K2.5, K2.9)
  other chests       food and blocks and materials; no gear, no room tools
  tier_* chests      also finish tables: the rare foods are allowed
  vaults/, gear/*, equipment/*   gear allowed; room tools are not
  spawners/          plain consumables; no gear, tools or rare food
  gameplay/, bags/, affixes/, modules/   authored on purpose, left alone
"""

# Item -> replacement inside the same pool. The entry keeps its own weight and
# functions; two entries with the same name are legal and add their weights,
# which is how a replaced entry merges into its replacement.
REPLACE = {
    # Other food folds into the two kept staples (K): plain food to bread,
    # cooked food and raw beef to steak.
    'minecraft:baked_potato': 'minecraft:bread',
    'minecraft:potato': 'minecraft:bread',
    'minecraft:carrot': 'minecraft:bread',
    'minecraft:sweet_berries': 'minecraft:bread',
    'minecraft:chorus_fruit': 'minecraft:bread',
    'minecraft:apple': 'minecraft:bread',
    'minecraft:pumpkin_pie': 'minecraft:bread',
    'minecraft:beef': 'minecraft:cooked_beef',
    'minecraft:golden_carrot': 'minecraft:cooked_beef',
    'minecraft:cooked_mutton': 'minecraft:cooked_beef',
    'minecraft:cooked_chicken': 'minecraft:cooked_beef',
    'minecraft:cooked_cod': 'minecraft:cooked_beef',
    'minecraft:cooked_porkchop': 'minecraft:cooked_beef',
    # Wood variants to the two kept blocks.
    'minecraft:spruce_planks': 'minecraft:oak_planks',
    'minecraft:birch_planks': 'minecraft:oak_planks',
    'minecraft:jungle_planks': 'minecraft:oak_planks',
    'minecraft:acacia_planks': 'minecraft:oak_planks',
    'minecraft:dark_oak_planks': 'minecraft:oak_planks',
    'minecraft:mangrove_planks': 'minecraft:oak_planks',
    'minecraft:cherry_planks': 'minecraft:oak_planks',
    'minecraft:bamboo_planks': 'minecraft:oak_planks',
    'minecraft:crimson_planks': 'minecraft:oak_planks',
    'minecraft:warped_planks': 'minecraft:oak_planks',
    'minecraft:spruce_log': 'minecraft:oak_log',
    'minecraft:birch_log': 'minecraft:oak_log',
    # Light sources fold into torch (K: torch only).
    'minecraft:soul_lantern': 'minecraft:torch',
    'minecraft:lantern': 'minecraft:torch',
    'minecraft:end_rod': 'minecraft:torch',
    'minecraft:glowstone': 'minecraft:torch',
    'minecraft:sea_lantern': 'minecraft:torch',
    'minecraft:shroomlight': 'minecraft:torch',
    # Raw and scrap forms pay smelted.
    'minecraft:raw_iron': 'minecraft:iron_ingot',
    'minecraft:netherite_scrap': 'minecraft:netherite_ingot',
    # Look-alike and decorative blocks (K): the pool still pays blocks.
    'minecraft:stone': 'minecraft:cobblestone',
    'minecraft:mossy_cobblestone': 'minecraft:cobblestone',
    'minecraft:stone_bricks': 'minecraft:cobblestone',
    'minecraft:mossy_stone_bricks': 'minecraft:cobblestone',
    'minecraft:deepslate': 'minecraft:cobblestone',
    'minecraft:polished_deepslate': 'minecraft:cobblestone',
    'minecraft:chiseled_deepslate': 'minecraft:cobblestone',
    'minecraft:deepslate_bricks': 'minecraft:cobblestone',
    'minecraft:deepslate_tiles': 'minecraft:cobblestone',
    'minecraft:reinforced_deepslate': 'minecraft:cobblestone',
    'minecraft:end_stone': 'minecraft:cobblestone',
    'minecraft:end_stone_bricks': 'minecraft:cobblestone',
    'minecraft:purpur_block': 'minecraft:cobblestone',
    'minecraft:purpur_pillar': 'minecraft:cobblestone',
    'minecraft:nether_bricks': 'minecraft:cobblestone',
    'minecraft:sandstone': 'minecraft:cobblestone',
    'minecraft:prismarine': 'minecraft:cobblestone',
    'minecraft:prismarine_bricks': 'minecraft:cobblestone',
    'minecraft:dark_prismarine': 'minecraft:cobblestone',
    'minecraft:packed_ice': 'minecraft:cobblestone',
    'minecraft:ice': 'minecraft:cobblestone',
    'minecraft:blue_ice': 'minecraft:cobblestone',
    'minecraft:snow_block': 'minecraft:cobblestone',
    'minecraft:amethyst_block': 'minecraft:cobblestone',
    'minecraft:bone_block': 'minecraft:cobblestone',
    'minecraft:copper_block': 'minecraft:cobblestone',
    'minecraft:cut_copper': 'minecraft:cobblestone',
    'minecraft:oxidized_cut_copper': 'minecraft:cobblestone',
    'minecraft:iron_block': 'minecraft:cobblestone',
    'minecraft:soul_sand': 'minecraft:cobblestone',
    'minecraft:sculk': 'minecraft:cobblestone',
    'minecraft:sculk_vein': 'minecraft:cobblestone',
    'minecraft:resin_brick': 'minecraft:cobblestone',
    'minecraft:crying_obsidian': 'minecraft:cobblestone',
    'minecraft:blackstone': 'minecraft:cobblestone',
    'minecraft:basalt': 'minecraft:cobblestone',
    'minecraft:netherrack': 'minecraft:cobblestone',
    'minecraft:tuff': 'minecraft:cobblestone',
    'minecraft:calcite': 'minecraft:cobblestone',
    'minecraft:dripstone_block': 'minecraft:cobblestone',
}

# Removed outright. Their weight is lost; the pool's other entries pick it up.
CUT = {
    # Seeds, saplings, crops and gardening (K; the gardening module may allow
    # them again). Nether wart leaves with brewing.
    'minecraft:wheat', 'minecraft:hay_block', 'minecraft:poisonous_potato',
    'minecraft:sugar_cane', 'minecraft:beetroot', 'minecraft:melon_slice',
    'minecraft:nether_wart', 'minecraft:chorus_flower',
    'minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt',
    'minecraft:grass_block', 'minecraft:moss_block', 'minecraft:spore_blossom',
    'minecraft:vine',
    # Stations are crafted, not looted (K; J5).
    'minecraft:enchanting_table', 'minecraft:brewing_stand', 'minecraft:cauldron',
    # Redstone (K; players dismantle rooms for parts; the redstone module).
    'minecraft:redstone', 'minecraft:piston', 'minecraft:sticky_piston',
    'minecraft:repeater', 'minecraft:comparator', 'minecraft:rail',
    'minecraft:powered_rail', 'minecraft:detector_rail', 'minecraft:activator_rail',
    'minecraft:lightning_rod', 'minecraft:observer', 'minecraft:dispenser',
    'minecraft:dropper', 'minecraft:hopper', 'minecraft:redstone_torch',
    'minecraft:lever', 'minecraft:target', 'minecraft:tripwire_hook',
    'minecraft:daylight_detector', 'minecraft:note_block',
    # Brewing leftovers move to the alchemy module (K2.2).
    'minecraft:blaze_powder', 'minecraft:blaze_rod', 'minecraft:magma_cream',
    'minecraft:glass_bottle', 'minecraft:glowstone_dust', 'minecraft:ghast_tear',
    # Items with nothing to do, and the two dangerous toys (K2.3).
    'minecraft:quartz', 'minecraft:amethyst_shard', 'minecraft:nautilus_shell',
    'minecraft:heart_of_the_sea', 'minecraft:powder_snow_bucket',
    'minecraft:ominous_bottle', 'minecraft:wither_skeleton_skull',
    # Mob remnants the mobs themselves supply, and snowballs (K2.7, K2.12).
    'minecraft:bone', 'minecraft:string', 'minecraft:leather', 'minecraft:snowball',
    'minecraft:rotten_flesh', 'minecraft:spider_eye',
    # Copper is out as a whole tier (K2.7).
    'minecraft:copper_ingot', 'minecraft:raw_copper', 'minecraft:copper_sword',
    'minecraft:copper_axe', 'minecraft:copper_pickaxe', 'minecraft:copper_shovel',
    'minecraft:copper_hoe', 'minecraft:copper_helmet', 'minecraft:copper_chestplate',
    'minecraft:copper_leggings', 'minecraft:copper_boots',
    # Gear cuts (K2.6): no chainmail, no gold gear, no trident.
    'minecraft:chainmail_helmet', 'minecraft:chainmail_chestplate',
    'minecraft:chainmail_leggings', 'minecraft:chainmail_boots',
    'minecraft:golden_helmet', 'minecraft:golden_chestplate',
    'minecraft:golden_leggings', 'minecraft:golden_boots',
    'minecraft:golden_sword', 'minecraft:golden_axe', 'minecraft:golden_pickaxe',
    'minecraft:golden_shovel', 'minecraft:golden_hoe',
    'minecraft:trident', 'minecraft:mace',
    # Decor whose use is already covered (K).
    'minecraft:iron_bars', 'minecraft:oak_fence',
    # Netherite gear scraps stay, but the summon-key and banner odds and ends
    # have no use left.
    'minecraft:saddle', 'minecraft:netherite_upgrade_smithing_template',
}

# Sentry is the one kept trim pattern (K2.1); the rest live in the trims module.
KEPT_TRIM = 'minecraft:sentry_armor_trim_smithing_template'
TRIM_SUFFIX = '_armor_trim_smithing_template'
SEED_SUFFIXES = ('_seeds', '_sapling')

# Room tools, gold and pearls pay only from the supply tables (K2.5, K2.9).
SUPPLY_ONLY = {
    'minecraft:shears', 'minecraft:lead', 'minecraft:bucket',
    'minecraft:water_bucket', 'minecraft:lava_bucket', 'minecraft:milk_bucket',
    'minecraft:gold_ingot', 'minecraft:ender_pearl',
}
BOAT_SUFFIX = '_boat'

# Armour and weapons: chests stopped pointing at gear (K2.4); vaults keep it.
GEAR_SUFFIXES = ('_helmet', '_chestplate', '_leggings', '_boots', '_sword',
                 '_axe', '_spear')
GEAR_EXACT = {'minecraft:bow', 'minecraft:crossbow', 'minecraft:shield'}

# The rare foods pay only in vaults, the finish chest tables and piglin
# bartering (K, K2 test list).
RARE_FOOD = {'minecraft:cake', 'minecraft:golden_apple',
             'minecraft:enchanted_golden_apple'}

# The blocks a loot table may place at all (K): mining materials and light.
ALLOWED_BLOCKS = {
    'minecraft:cobblestone', 'minecraft:sand', 'minecraft:gravel',
    'minecraft:oak_log', 'minecraft:oak_planks', 'minecraft:torch',
    'minecraft:obsidian',
}

# Vanilla block-item names that read as blocks but are not in the tables'
# structural sense; anything else ending in a known block suffix is still an
# item (ingots, charms, keys are not _block/_ore names anyway).
BLOCK_HINTS = ('_block', '_bricks', '_tiles', '_ore', 'deepslate', 'stone',
               'ice', 'prismarine', 'purpur', 'sandstone', 'sculk', 'obsidian',
               'lantern', 'glowstone', 'shroomlight', 'end_rod', 'magma_block')


def context_of(rel_path):
    """Which rule set a loot_table-relative path falls under."""
    if rel_path.startswith(('bags/', 'affixes/', 'modules/', 'gameplay/')):
        return 'exempt'
    if rel_path.startswith('chests/supply_'):
        return 'supply'
    if rel_path.startswith('chests/'):
        return 'chest'
    if rel_path.startswith('vaults/'):
        return 'vault'
    if rel_path.startswith(('gear/', 'equipment/')):
        return 'equip'
    return 'spawner' if rel_path.startswith('spawners/') else 'other'


def is_finish_table(rel_path):
    """chests/tier_* tables are also the finish reward tables."""
    base = rel_path.rsplit('/', 1)[-1]
    return rel_path.startswith('chests/') and base.startswith('tier_')


def banned(name):
    """Item cut from every core table: K2.1 leaves Sentry as the one trim."""
    if name in CUT:
        return True
    if name.endswith(TRIM_SUFFIX):
        return name != KEPT_TRIM
    return name.endswith(SEED_SUFFIXES)


def is_gear(name):
    return name in GEAR_EXACT or name.endswith(GEAR_SUFFIXES)


def is_supply_only(name):
    return name in SUPPLY_ONLY or name.endswith(BOAT_SUFFIX)


def rewrite_entry(entry, context, finish):
    """None if the entry drops, else the (possibly retargeted) entry."""
    name = entry.get('name')
    if entry.get('type') != 'minecraft:item' or name is None:
        return entry
    name = REPLACE.get(name, name)
    if banned(name):
        return None
    if context == 'supply':
        if name in RARE_FOOD:
            return None
    elif context == 'chest':
        if is_gear(name) or is_supply_only(name):
            return None
        if name in RARE_FOOD and not finish:
            return None
    elif context == 'vault':
        if is_supply_only(name):
            return None
    elif context == 'equip':
        if is_supply_only(name) or name in RARE_FOOD:
            return None
    else:
        # spawners and anything else: consumables only.
        if is_gear(name) or is_supply_only(name) or name in RARE_FOOD:
            return None
    out = dict(entry)
    out['name'] = name
    return out


def rewrite_table(rel_path, table):
    """Apply the K and K2 rules to one loot table dict, in place semantics:
    returns a new table; pools left with no entries are dropped."""
    context = context_of(rel_path)
    if context == 'exempt':
        return table
    finish = is_finish_table(rel_path)
    out = dict(table)
    pools = []
    for pool in table.get('pools', []):
        entries = [e for e in
                   (rewrite_entry(entry, context, finish)
                    for entry in pool.get('entries', []))
                   if e is not None]
        if not entries and pool.get('entries'):
            continue
        new_pool = dict(pool)
        new_pool['entries'] = entries
        # D21: every torch in a chest table pays a third as often; the chance
        # sits on the entry, or on the pool when torch is its only entry
        # (ResourceNodeTest reads the carrier accordingly). A light source
        # folded into torch inherits the rule here, post-rename.
        if context in ('chest', 'supply'):
            if len(entries) == 1 and entries[0].get('name') == 'minecraft:torch':
                if not any(c.get('condition') == 'minecraft:random_chance'
                           for c in new_pool.get('conditions', [])):
                    new_pool['conditions'] = (new_pool.get('conditions') or []) + [
                        {'condition': 'minecraft:random_chance', 'chance': 0.34}]
            else:
                for entry in entries:
                    if (entry.get('name') == 'minecraft:torch'
                            and not any(c.get('condition') == 'minecraft:random_chance'
                                        for c in entry.get('conditions', []))):
                        entry['conditions'] = (entry.get('conditions') or []) + [
                            {'condition': 'minecraft:random_chance', 'chance': 0.34}]
        pools.append(new_pool)
    out['pools'] = pools
    return out
