#!/usr/bin/env python3
"""Generates the M73 trial spawner configs and layered loot tables for the
six new themes (rootworks, frostworks, copper_works, ossuary, basalt_foundry,
ender_archive).

The spawner configs follow the crypt/infestation pattern: a spawner_prefix
composes to <prefix>_tier_N/{normal,ominous}, scaling mob counts and cooldown
per tier and swapping trial_key for ominous_trial_key in ominous mode.

The loot tables are layered (the M73 "layered ominous loot" technique): each
themed table references the matching base table via a minecraft:loot_table
entry and appends one themed pool carrying the theme's decorative signature,
tool economy and a rare weighted treasure. Vaults are intentionally not
generated: a vault is the universal key-gated gear reward, so it falls back to
its base table, and the theme's identity lives in the completion chests.

Run from the mod root:

    python tools/gen_themed_content.py

Re-running overwrites the generated files in place; hand-authored files
(themes, adventures, processor lists, signature rooms, affixes) are not
touched.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import loot_rules

SPAWNER_DIR = 'src/main/resources/data/pocketdungeons/trial_spawner'
CHESTS_DIR = 'src/main/resources/data/pocketdungeons/loot_table/chests'

# Tier scaling: (total_mobs, simultaneous_mobs, total_added, sim_added, ticks)
NORMAL = {1: (4, 2, 2, 1, 40), 2: (6, 3, 2, 1, 30), 3: (8, 4, 3, 1, 20)}
# Ominous adds +2 total, +1 simultaneous over normal, same ticks and adds.
OMINOUS = {1: (6, 3, 2, 1, 40), 2: (8, 4, 2, 1, 30), 3: (10, 5, 3, 1, 20)}

# Equipment loot tables per tier, keyed by melee/ranged. All three tiers use
# our own untrimmed tables (PD-106), matching the crypt configs.
EQUIP = {
    1: {'melee': 'pocketdungeons:equipment/tier_1_melee',
        'ranged': 'pocketdungeons:equipment/tier_1_ranged'},
    2: {'melee': 'pocketdungeons:equipment/tier_2_melee',
        'ranged': 'pocketdungeons:equipment/tier_2_ranged'},
    3: {'melee': 'pocketdungeons:equipment/tier_3_melee',
        'ranged': 'pocketdungeons:equipment/tier_3_ranged'},
}


def mob_entry(entity_id, weight, equip_kind=None, tier=1):
    """One spawn_potentials entry. equip_kind is None, 'melee' or 'ranged'."""
    data = {'entity': {'id': 'minecraft:' + entity_id}}
    if equip_kind is not None:
        data['equipment'] = {
            'loot_table': EQUIP[tier][equip_kind],
            'slot_drop_chances': 0.0,
        }
    return {'data': data, 'weight': weight}


def spawner_config(prefix, tier, ominous, roster):
    """roster: list of (entity_id, weight, equip_kind) for this tier."""
    scale = OMINOUS[tier] if ominous else NORMAL[tier]
    total, sim, tot_add, sim_add, ticks = scale
    spawns = [mob_entry(eid, w, ek, tier) for eid, w, ek in roster]
    eject_key = ('pocketdungeons:spawners/ominous_trial_key' if ominous
                 else 'pocketdungeons:spawners/trial_key')
    return {
        'spawn_range': 4,
        'total_mobs': float(total),
        'simultaneous_mobs': float(sim),
        'total_mobs_added_per_player': float(tot_add),
        'simultaneous_mobs_added_per_player': float(sim_add),
        'ticks_between_spawn': ticks,
        'spawn_potentials': spawns,
        'loot_tables_to_eject': [
            {'data': eject_key, 'weight': 5},
            {'data': 'pocketdungeons:spawners/emeralds', 'weight': 5},
        ],
        # K: our own ominous drops; vanilla's pays ominous bottles and cut trims.
        'items_to_drop_when_ominous':
            'pocketdungeons:spawners/items_to_drop_when_ominous',
    }


# PD-95: only items that stack to 1 carry the bag tag. On a stackable item the
# tag stops it merging with the same item from anywhere else.
UNSTACKABLE = {
    'minecraft:' + n for n in (
        'diamond_sword', 'iron_sword', 'stone_sword', 'wooden_sword', 'netherite_sword',
        'diamond_axe', 'iron_axe', 'wooden_axe', 'stone_pickaxe', 'iron_pickaxe',
        'iron_shovel', 'bow', 'crossbow', 'trident', 'shears', 'shield', 'spyglass',
        'flint_and_steel', 'water_bucket', 'lava_bucket', 'milk_bucket',
        'powder_snow_bucket', 'oak_boat', 'enchanted_book', 'golden_boots',
    )
} | {
    'minecraft:%s_%s' % (mat, piece)
    for mat in ('leather', 'chainmail', 'iron', 'diamond', 'netherite')
    for piece in ('helmet', 'chestplate', 'leggings', 'boots')
}


def item_entry(name, weight, count=None, damage=False, custom_data=None):
    """One themed-pool item entry with optional count/damage, tagged if it stacks to 1."""
    functions = []
    if count is not None:
        if isinstance(count, tuple):
            functions.append({'function': 'minecraft:set_count',
                              'count': {'type': 'minecraft:uniform',
                                        'min': count[0], 'max': count[1]}})
        else:
            functions.append({'function': 'minecraft:set_count', 'count': count})
    mine = {'bag': 1} if 'minecraft:' + name in UNSTACKABLE or custom_data else {}
    if custom_data:
        mine.update(custom_data)
    components = {'minecraft:custom_data': {'pocketdungeons': mine}} if mine else {}
    if components:
        functions.append({'function': 'minecraft:set_components',
                          'components': components})
    if damage:
        functions.append({'function': 'minecraft:set_damage',
                          'damage': {'type': 'minecraft:uniform',
                                     'min': 0.1, 'max': 0.6}})
    entry = {'type': 'minecraft:item', 'name': 'minecraft:' + name}
    if functions:
        entry['functions'] = functions
    if weight is not None:
        entry['weight'] = weight
    return entry


def themed_pool(items):
    """items: list of item_entry dicts. One pool, one roll."""
    return {'rolls': 1, 'entries': items}


def layered_chest(base_path, pool, rel_name=None):
    """A layered chest table: base table reference + themed pool, rewritten
    through the K and K2 rules so a themed pool cannot smuggle a cut item back."""
    table = {
        'type': 'minecraft:chest',
        'pools': [
            {'rolls': 1, 'entries': [
                {'type': 'minecraft:loot_table', 'value': base_path}]},
            pool,
        ],
    }
    if rel_name is not None:
        table = loot_rules.rewrite_table('chests/' + rel_name, table)
    return table


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as handle:
        json.dump(obj, handle, indent=2)
        handle.write('\n')
    print('wrote', path)


# ---- Per-theme config ------------------------------------------------------
# roster: dict tier -> list of (entity_id, weight, equip_kind|None)
# pool_items: list of item_entry(...) for the themed pool (same across tiers).
# Under K and K2 the signature is expressed in legal items: the wart, rods and
# bottles a foundry used to pay are the alchemy module's loot now, and the
# module carries its own tables for these targets.

THEMES = {
    'rootworks': {
        'roster': {
            1: [('spider', 5, None), ('witch', 3, None)],
            2: [('spider', 5, None), ('witch', 3, None)],
            3: [('cave_spider', 5, None), ('witch', 3, None)],
        },
        'pool_items': [
            item_entry('oak_log', 5, (1, 3)),
            item_entry('oak_planks', 4, (2, 6)),
            item_entry('arrow', 3, (3, 9)),
            item_entry('bread', 2, (1, 2)),
        ],
    },
    'frostworks': {
        'roster': {
            1: [('stray', 5, 'ranged'), ('zombie', 4, 'melee')],
            2: [('stray', 5, 'ranged'), ('zombie', 4, 'melee')],
            3: [('stray', 5, 'ranged'), ('zombie', 4, 'melee')],
        },
        'pool_items': [
            item_entry('cooked_beef', 5, (1, 2)),
            item_entry('cobblestone', 4, (1, 3)),
            item_entry('torch', 3, (1, 2)),
            item_entry('flint_and_steel', 1, damage=True),
        ],
    },
    'copper_works': {
        'roster': {
            1: [('zombie', 5, 'melee'), ('creeper', 3, None)],
            2: [('zombie', 5, 'melee'), ('creeper', 3, None)],
            3: [('zombie', 5, 'melee'), ('creeper', 3, None)],
        },
        'pool_items': [
            item_entry('iron_ingot', 5, (1, 3)),
            item_entry('coal', 4, (2, 5)),
            item_entry('iron_pickaxe', 2, damage=True),
            item_entry('obsidian', 1),
        ],
    },
    'ossuary': {
        'roster': {
            1: [('skeleton', 5, 'ranged'), ('stray', 3, 'ranged')],
            2: [('skeleton', 5, 'ranged'), ('stray', 3, 'ranged')],
            3: [('skeleton', 5, 'ranged'), ('stray', 3, 'ranged')],
        },
        'pool_items': [
            # Playtest 2026-09-29-2: arrows stay plentiful where archers live;
            # the bow itself is vault gear now (K2.4), not chest loot.
            item_entry('arrow', 8, (4, 12)),
            item_entry('cooked_beef', 4, (1, 2)),
            item_entry('iron_ingot', 2, (1, 3)),
            item_entry('emerald', 1),
        ],
    },
    'basalt_foundry': {
        'roster': {
            1: [('blaze', 4, None), ('magma_cube', 3, None)],
            2: [('blaze', 4, None), ('magma_cube', 3, None)],
            3: [('blaze', 5, None), ('magma_cube', 3, None)],
        },
        'pool_items': [
            item_entry('obsidian', 4, (1, 2)),
            item_entry('iron_ingot', 4, (1, 2)),
            item_entry('coal', 4, (2, 4)),
            item_entry('netherite_ingot', 1),
        ],
    },
    'ender_archive': {
        'roster': {
            1: [('enderman', 4, None), ('silverfish', 3, None)],
            2: [('enderman', 4, None), ('silverfish', 3, None)],
            3: [('enderman', 5, None), ('silverfish', 3, None)],
        },
        'pool_items': [
            item_entry('obsidian', 4, (1, 2)),
            item_entry('experience_bottle', 4, (1, 2)),
            item_entry('book', 3, (1, 2)),
            item_entry('emerald', 2),
        ],
    },
}


def main():
    if not os.path.isdir(SPAWNER_DIR):
        sys.exit('run this from the mod root: %s not found' % SPAWNER_DIR)
    for theme, cfg in THEMES.items():
        prefix = theme
        suffix = '_' + theme
        # Spawner configs: <prefix>_tier_N/{normal,ominous}
        for tier in (1, 2, 3):
            roster = cfg['roster'][tier]
            for mode in ('normal', 'ominous'):
                obj = spawner_config(prefix, tier, mode == 'ominous', roster)
                path = os.path.join(SPAWNER_DIR, '%s_tier_%d' % (prefix, tier),
                                    mode + '.json')
                write_json(path, obj)
        # Layered chest tables: chests/tier_N<_ominous><suffix>
        pool = themed_pool(cfg['pool_items'])
        for tier in (1, 2, 3):
            base_normal = 'pocketdungeons:chests/tier_%d' % tier
            base_ominous = 'pocketdungeons:chests/tier_%d_ominous' % tier
            write_json(os.path.join(CHESTS_DIR, 'tier_%d%s.json' % (tier, suffix)),
                       layered_chest(base_normal, pool, 'tier_%d%s.json' % (tier, suffix)))
            write_json(os.path.join(CHESTS_DIR,
                                   'tier_%d_ominous%s.json' % (tier, suffix)),
                       layered_chest(base_ominous, pool,
                                     'tier_%d_ominous%s.json' % (tier, suffix)))
    print('done')


if __name__ == '__main__':
    import sys
    main()
