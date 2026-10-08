#!/usr/bin/env python3
"""Writes the vault loot tables under the K2 split: chests feed you, vaults
equip you.

The old generator derived vaults by filtering the chest tables, which worked
while chests carried a gear pool to keep. K2.4 took gear out of the chests, so
the vault recipe is authored here directly instead. A vault pays:

  * gear: one piece from the five slot tables of the vault's tier, guaranteed;
    a key-gated container that can pay out nothing but ore is worse than one
    that pays out too much (the rule the old filter encoded),
  * treasure: the vault's mineral take, scaled by tier; ominous rolls twice,
  * a sentry trim template about one roll in three: the one pattern K2.1 kept
    in core loot, so a vault is where a player still picks it up,
  * rarely, the rare foods: cake and golden apples live in vaults and the
    finish chests only (K).

The _drowned vaults are single-entry references to the base table of the same
tier and flag: the drowned theme's chest tables are themselves references, so
a vault with the same contents is the honest description.

Run from the mod root:

    python tools/gen_vault_tables.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_themed_content as g

VAULTS = 'src/main/resources/data/pocketdungeons/loot_table/vaults'
GEAR_SLOTS = ('helmet', 'chestplate', 'leggings', 'boots', 'weapon')


def entry(name, weight=None, count=None):
    out = {'type': 'minecraft:item', 'name': 'minecraft:' + name}
    if weight is not None:
        out['weight'] = weight
    if count is not None:
        out['functions'] = [{'function': 'minecraft:set_count',
                             'count': {'min': count[0], 'max': count[1]}}]
    return out


def vault_table(tier, ominous):
    """The vault recipe for one tier and flag."""
    pools = [
        # Gear: one slot's piece, guaranteed (see module docstring).
        {'rolls': 2 if ominous else 1,
         'entries': [{'type': 'minecraft:loot_table',
                      'value': 'pocketdungeons:gear/%s_%d' % (slot, tier)}
                     for slot in GEAR_SLOTS]},
        # Treasure: the vault's mineral take.
        {'rolls': {'min': 1, 'max': 2} if ominous else 1,
         'entries': [
             entry('iron_ingot', 6, (1, 3)),
             entry('coal', 4, (2, 5)),
             entry('lapis_lazuli', 3, (1, 3)),
             entry('emerald', 3, (1, 2)),
             entry('diamond', 3, (1, 2)),
             entry('book', 2),
         ] + ([entry('netherite_ingot', 1)] if tier >= 4 else [])},
        # The one trim pattern, about a third of vaults.
        {'rolls': 1, 'conditions': [
            {'condition': 'minecraft:random_chance',
             'chance': 0.5 if ominous else 0.34}],
         'entries': [entry('sentry_armor_trim_smithing_template')]},
        # The rare foods: vaults and the finish chests only.
        {'rolls': 1, 'conditions': [
            {'condition': 'minecraft:random_chance',
             'chance': 0.5 if ominous else 0.35}],
         'entries': [
             entry('golden_apple', 3),
             entry('cake', 2),
         ] + ([entry('enchanted_golden_apple', 1)] if tier >= 3 else [])},
    ]
    return {'type': 'minecraft:chest', 'pools': pools}


def ref_table(target):
    """A vault that is another vault's table outright, for the drowned suffix."""
    return {'type': 'minecraft:chest',
            'pools': [{'rolls': 1, 'entries': [
                {'type': 'minecraft:loot_table', 'value': target}]}]}


def main():
    if not os.path.isdir(VAULTS):
        sys.exit('run this from the mod root: %s not found' % VAULTS)
    for tier in (1, 2, 3, 4):
        for ominous in (False, True):
            suffix = '_ominous' if ominous else ''
            name = 'tier_%d%s.json' % (tier, suffix)
            g.write_json(os.path.join(VAULTS, name), vault_table(tier, ominous))
            for drowned in ('_drowned',):
                g.write_json(os.path.join(VAULTS, 'tier_%d%s%s.json' % (tier, suffix, drowned)),
                             ref_table('pocketdungeons:vaults/tier_%d%s' % (tier, suffix)))
    print('done')


if __name__ == '__main__':
    main()
