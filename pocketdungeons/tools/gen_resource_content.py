#!/usr/bin/env python3
"""Generates the W6 trial spawner configs and chest loot tables for the new
resource dungeon themes (mineshaft, cow_pits), reusing the helpers of
gen_themed_content.py. Run from the mod root:

    python tools/gen_resource_content.py

Mineshaft is layered like the other themes (base tier table plus one themed
pool). Cow Pits cannot layer: its base tables hold wheat, seeds, carrots and
potatoes, which would let a player breed cows (the floor must stay finite), so
each Cow Pits table is a filtered standalone copy of the base table plus a
themed pool of beef and leather. The supply tables get the same treatment
(TrialContent reads supply_tier_N plus the theme's loot suffix).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_themed_content as g

CHESTS = g.CHESTS_DIR
# Items that would feed or breed a cow, or grow into wheat.
FORBIDDEN_EXACT = {
    'minecraft:wheat', 'minecraft:hay_block', 'minecraft:potato', 'minecraft:poisonous_potato',
    'minecraft:carrot', 'minecraft:golden_carrot', 'minecraft:baked_potato', 'minecraft:bread',
}


def forbidden(name):
    return name in FORBIDDEN_EXACT or name.endswith('_seeds')


def filtered(node):
    """Recursively drops forbidden item entries from a loot table fragment."""
    if isinstance(node, dict):
        if node.get('type') == 'minecraft:item' and forbidden(node.get('name', '')):
            return None
        out = {}
        for key, value in node.items():
            if key == 'entries' and isinstance(value, list):
                out[key] = [e for e in (filtered(x) for x in value) if e is not None]
            elif key in ('pools', 'children') and isinstance(value, list):
                out[key] = [e for e in (filtered(x) for x in value) if e is not None]
            else:
                out[key] = value
        return out
    return node


def load(path):
    with open(path, encoding='utf-8') as handle:
        return json.load(handle)


MINESHAFT = {
    'roster': {
        1: [('zombie', 5, 'melee'), ('cave_spider', 3, None), ('creeper', 1, None)],
        2: [('zombie', 4, 'melee'), ('skeleton', 4, 'ranged'), ('cave_spider', 3, None),
            ('creeper', 1, None)],
        3: [('skeleton', 4, 'ranged'), ('cave_spider', 5, None), ('zombie', 3, 'melee'),
            ('creeper', 1, None)],
    },
    'pool_items': [
        g.item_entry('coal', 6, (2, 5)),
        g.item_entry('oak_planks', 6, (2, 6)),
        g.item_entry('rail', 4, (3, 8)),
        g.item_entry('oak_fence', 3, (2, 4)),
        g.item_entry('raw_iron', 2, (1, 3)),
        g.item_entry('stone_pickaxe', 2, damage=True),
    ],
}

COW_PITS = {
    'roster': {
        1: [('zombie', 5, 'melee'), ('skeleton', 3, 'ranged')],
        2: [('zombie', 5, 'melee'), ('skeleton', 3, 'ranged')],
        3: [('zombie', 5, 'melee'), ('skeleton', 4, 'ranged')],
    },
    'pool_items': [
        g.item_entry('beef', 6, (2, 4)),
        g.item_entry('cooked_beef', 4, (1, 3)),
        g.item_entry('leather', 6, (2, 5)),
        g.item_entry('bucket', 2, 1),
        g.item_entry('lead', 2, (1, 2)),
    ],
}


def spawners(prefix, cfg):
    for tier in (1, 2, 3):
        for mode in ('normal', 'ominous'):
            obj = g.spawner_config(prefix, tier, mode == 'ominous', cfg['roster'][tier])
            g.write_json(os.path.join(g.SPAWNER_DIR, '%s_tier_%d' % (prefix, tier), mode + '.json'), obj)


def main():
    spawners('mineshaft', MINESHAFT)
    pool = g.themed_pool(MINESHAFT['pool_items'])
    for tier in (1, 2, 3):
        g.write_json(os.path.join(CHESTS, 'tier_%d_mineshaft.json' % tier),
                     g.layered_chest('pocketdungeons:chests/tier_%d' % tier, pool))
        g.write_json(os.path.join(CHESTS, 'tier_%d_ominous_mineshaft.json' % tier),
                     g.layered_chest('pocketdungeons:chests/tier_%d_ominous' % tier, pool))

    spawners('cow_pits', COW_PITS)
    themed = g.themed_pool(COW_PITS['pool_items'])
    for tier in (1, 2, 3, 4):
        for ominous in (False, True):
            base = load(os.path.join(CHESTS, 'tier_%d%s.json' % (tier, '_ominous' if ominous else '')))
            table = filtered(base)
            table['pools'].append(themed)
            g.write_json(os.path.join(CHESTS, 'tier_%d%s_cow_pits.json' % (tier, '_ominous' if ominous else '')), table)
        base = load(os.path.join(CHESTS, 'supply_tier_%d.json' % tier))
        g.write_json(os.path.join(CHESTS, 'supply_tier_%d_cow_pits.json' % tier), filtered(base))
    print('done')


if __name__ == '__main__':
    main()
