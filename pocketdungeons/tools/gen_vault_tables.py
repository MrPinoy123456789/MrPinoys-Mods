#!/usr/bin/env python3
"""Derives the vault loot tables from the chest loot tables.

A vault is not a chest. A chest holds its roll across 27 slots and the player
opens it and takes what they want; a vault ejects every stack it rolls onto the
floor, one item entity at a time. The chest tables guarantee 13 to 22 rolls, so
pointing a vault at one buries the room in dirt, saplings and cobblestone and
loses the two stacks that actually mattered somewhere in the pile.

So the vault tables are the chest tables with the bulk taken out: the treasure,
ore, gear, curio and trim pools survive, every one of them clamped to a single
roll, and the food, torches, bones, building blocks, dirt, saplings, seeds,
arrows and the mineral tail are dropped. That lands a vault at roughly 2 to 6
stacks. Two rules beyond the filter:

  * Where a table has several pools of the same kind (the ominous tables repeat
    their treasure pool two and three times over), the richest one is kept and
    the rest are dropped: one pool per kind of thing.
  * The gear pool loses its random_chance and becomes guaranteed. It is the only
    uplift here and it is the point of the whole exercise: a vault costs a key,
    and a key-gated container that can pay out nothing but ore is worse than one
    that pays out too much.

Run from the mod root after editing anything under loot_table/chests:

    python tools/gen_vault_tables.py

Every pool in every source table must be classified below. An unrecognised one
is an error rather than a default, so a new chest pool cannot silently land in
the vaults or silently miss them.
"""

import json
import os
import sys

CHESTS = 'src/main/resources/data/pocketdungeons/loot_table/chests'
VAULTS = 'src/main/resources/data/pocketdungeons/loot_table/vaults'

# Keyed by the pool's first entry, which identifies the pool across every tier.
KEEP = {
    'minecraft:diamond': 'treasure',
    'minecraft:iron_ingot': 'ore',
    'minecraft:echo_shard': 'echo',
    'minecraft:brewing_stand': 'curio',
    'minecraft:ender_chest': 'curio',
    'minecraft:trident': 'themed_weapon',
    'minecraft:heart_of_the_sea': 'themed_treasure',
}

DROP = {
    'minecraft:bread', 'minecraft:cooked_cod', 'minecraft:golden_carrot',
    'minecraft:torch', 'minecraft:sea_lantern',
    'minecraft:bone',
    'minecraft:stone', 'minecraft:deepslate', 'minecraft:end_stone',
    'minecraft:prismarine_bricks', 'minecraft:dark_prismarine',
    'minecraft:oxidized_cut_copper',
    'minecraft:dirt', 'minecraft:oak_sapling', 'minecraft:wheat_seeds',
    'minecraft:arrow',
    'minecraft:quartz', 'minecraft:amethyst_shard',
}


def kind(pool, path, index):
    """Which bucket this pool falls in, or None if it is dropped."""
    first = pool['entries'][0].get('name')
    if first is None:
        sys.exit('%s pool %d has no leading item entry' % (path, index))
    if first.endswith('_armor_trim_smithing_template'):
        return 'trim'
    if first.endswith(('_helmet', '_chestplate', '_leggings', '_boots')):
        return 'gear'
    if first in KEEP:
        return KEEP[first]
    if first in DROP:
        return None
    sys.exit('%s pool %d leads with %s, which is in neither KEEP nor DROP. '
             'Classify it in tools/gen_vault_tables.py before regenerating.'
             % (path, index, first))


def vault_pools(table, path):
    """The kept pools, one per kind, richest wins, every one clamped to a roll."""
    best = {}
    for index, pool in enumerate(table.get('pools', [])):
        bucket = kind(pool, path, index)
        if bucket is None:
            continue
        incumbent = best.get(bucket)
        if incumbent is None or len(pool['entries']) > len(incumbent[1]['entries']):
            best[bucket] = (index, pool)

    out = []
    for index, pool in sorted(best.values()):
        pool = json.loads(json.dumps(pool))  # the source table is not ours to edit
        pool['rolls'] = 1
        pool.pop('bonus_rolls', None)
        if kind(pool, path, index) == 'gear':
            pool.pop('conditions', None)
        out.append(pool)
    return out


def main():
    if not os.path.isdir(CHESTS):
        sys.exit('run this from the mod root: %s not found' % CHESTS)
    os.makedirs(VAULTS, exist_ok=True)

    names = sorted(n for n in os.listdir(CHESTS)
                   if n.startswith('tier_') and n.endswith('.json'))
    for name in names:
        path = os.path.join(CHESTS, name)
        with open(path, encoding='utf-8') as handle:
            table = json.load(handle)

        pools = vault_pools(table, path)
        out = {'type': table.get('type', 'minecraft:chest'), 'pools': pools}
        target = os.path.join(VAULTS, name)
        with open(target, 'w', encoding='utf-8', newline='\n') as handle:
            json.dump(out, handle, indent=2)
            handle.write('\n')

        was = len(table.get('pools', []))
        print('%-22s %2d pools -> %d (%s)' % (
            name, was, len(pools),
            ', '.join(kind(p, path, i) for i, p in enumerate(pools))))


if __name__ == '__main__':
    main()
