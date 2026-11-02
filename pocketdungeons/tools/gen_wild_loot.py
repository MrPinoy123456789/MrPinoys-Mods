#!/usr/bin/env python3
"""Writes the wild dungeon war supply loot tables (owner, 2026-11-02).

Kingdoms need gold, golden apples, iron, emeralds and nether wart for war, so a wild floor's chests, supply
chests, vaults and reward barrel pay those and nothing else (plus a trial key now and then, so vaults stay
openable). `WildLoot.SUFFIX` makes a wild floor resolve `chests/tier_2` as `chests/tier_2_wild` and so on.

Tune the numbers below and run `python tools/gen_wild_loot.py` from `pocketdungeons/`. Counts are per roll;
each tier row is (min, max). The tables are 26.3 format (`modifier`, `condition`).
"""
import json
import os

ROOT = os.path.join('src', 'main', 'resources', 'data', 'pocketdungeons', 'loot_table')

# Item, weight, counts for tiers 1 to 4.
CHEST = [
    ('minecraft:iron_ingot', 10, [(2, 5), (3, 7), (4, 9), (6, 12)]),
    ('minecraft:gold_ingot', 8, [(1, 3), (2, 5), (3, 7), (4, 9)]),
    ('minecraft:emerald', 7, [(1, 3), (2, 4), (3, 6), (4, 8)]),
    ('minecraft:nether_wart', 6, [(2, 5), (3, 8), (5, 12), (8, 16)]),
    ('minecraft:golden_apple', 3, [(1, 1), (1, 1), (1, 2), (1, 2)]),
    ('minecraft:trial_key', 2, [(1, 1), (1, 1), (1, 1), (1, 1)]),
]
# Rolls per chest for tiers 1 to 4; an ominous chest rolls one more.
CHEST_ROLLS = [(2, 3), (2, 3), (2, 4), (3, 4)]

# A supply chest is the small one: nether wart and a little metal.
SUPPLY = [
    ('minecraft:nether_wart', 5, [(2, 6), (3, 8), (4, 10), (6, 12)]),
    ('minecraft:iron_ingot', 4, [(1, 3), (2, 4), (2, 5), (3, 6)]),
    ('minecraft:gold_ingot', 2, [(1, 2), (1, 3), (2, 4), (2, 5)]),
]

# A vault pays bigger stacks of the metals and emeralds, and nearly always a golden apple.
VAULT_METALS = [
    ('minecraft:iron_ingot', 5, [(6, 10), (8, 14), (10, 18), (14, 24)]),
    ('minecraft:gold_ingot', 4, [(4, 8), (6, 10), (8, 14), (10, 18)]),
    ('minecraft:emerald', 4, [(3, 6), (4, 8), (6, 10), (8, 14)]),
]
VAULT_APPLE_CHANCE = [0.6, 0.7, 0.8, 0.9]


def entry(item, weight, counts):
    lo, hi = counts
    out = {'type': 'minecraft:item', 'name': item, 'weight': weight}
    if hi > 1:
        out['modifier'] = [{'type': 'minecraft:set_count',
                            'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}}]
    return out


def rolls(lo, hi):
    return lo if lo == hi else {'type': 'minecraft:uniform', 'min': lo, 'max': hi}


def chest(tier, ominous):
    lo, hi = CHEST_ROLLS[tier - 1]
    if ominous:
        lo, hi = lo + 1, hi + 1
    weights = [(item, weight + (2 if ominous and item == 'minecraft:golden_apple' else 0), counts[tier - 1])
               for item, weight, counts in CHEST]
    return {'type': 'minecraft:chest',
            'pools': [{'rolls': rolls(lo, hi), 'entries': [entry(i, w, c) for i, w, c in weights]}]}


def supply(tier):
    return {'type': 'minecraft:chest',
            'pools': [{'rolls': rolls(1, 2), 'entries': [entry(i, w, c[tier - 1]) for i, w, c in SUPPLY]}]}


def vault(tier, ominous):
    metal_rolls = (2, 3) if ominous else (1, 2)
    apple = {'rolls': 1, 'condition': {'type': 'minecraft:random_chance',
                                       'chance': 1.0 if ominous else VAULT_APPLE_CHANCE[tier - 1]},
             'entries': [entry('minecraft:golden_apple', 1, (1, 2) if tier >= 3 else (1, 1))]}
    wart = {'rolls': 1, 'entries': [entry('minecraft:nether_wart', 1, CHEST[3][2][tier - 1])]}
    return {'type': 'minecraft:chest',
            'pools': [{'rolls': rolls(*metal_rolls),
                       'entries': [entry(i, w, c[tier - 1]) for i, w, c in VAULT_METALS]},
                      apple, wart]}


def write(rel, table):
    path = os.path.join(ROOT, rel)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(table, f, indent=2)
        f.write('\n')


def main():
    for tier in range(1, 5):
        write(f'chests/tier_{tier}_wild.json', chest(tier, False))
        write(f'chests/tier_{tier}_ominous_wild.json', chest(tier, True))
        write(f'chests/supply_tier_{tier}_wild.json', supply(tier))
        write(f'vaults/tier_{tier}_wild.json', vault(tier, False))
        write(f'vaults/tier_{tier}_ominous_wild.json', vault(tier, True))
    print('wrote 20 wild loot tables')


if __name__ == '__main__':
    main()
