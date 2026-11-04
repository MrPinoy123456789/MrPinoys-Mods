#!/usr/bin/env python3
"""Writes the wild dungeon war supply loot tables (owner, 2026-11-02).

Kingdoms need gold, golden apples, iron, emeralds and nether wart for war, so a wild floor's chests, supply
chests, vaults and reward barrel pay those (plus a trial key now and then, so vaults stay openable).
`WildLoot.SUFFIX` makes a wild floor resolve `chests/tier_2` as `chests/tier_2_wild` and so on.

Holding a dungeon must compete with farms (owner, 2026-11-02, option A): the farmable supplies pay about five
times what they first did, golden apples come by the stack (farms cannot make apples), and from tier 3
(level 10 up) a war tier pays what no farm makes: enchanted golden apples, diamonds, netherite scrap, ancient
debris and Protection IV and Sharpness V books.

Gold is the kingdom server's money and its sink, with too few sources (owner, 2026-11-04: "more gold ore in
dungeons, gold ingots and gold sovereign as rewards"). So gold ingots are exempt from the supply nerf and weigh
heavier, and the tables pay Gold Sovereigns: Charter Currency's 64 Mark coin, a gold nugget carrying the
`{"chartercurrency":{"coin":"gold"}}` marker, the item name and the lore line the Mint gives it, so it banks and
stacks like a struck one. Every vault always pays a few; chests and supply chests pay one now and then.

Tune the numbers below and run `python tools/gen_wild_loot.py` from `pocketdungeons/`. Counts are per roll;
each tier row is (min, max), and None leaves the item out of that tier. The tables are 26.3 format
(`modifier`, `condition`). The script prints the expected yield of a typical clear so a change can be checked.
"""
import json
import os

ROOT = os.path.join('src', 'main', 'resources', 'data', 'pocketdungeons', 'loot_table')

PROTECTION = ('minecraft:book', {'minecraft:protection': 4})
SHARPNESS = ('minecraft:book', {'minecraft:sharpness': 5})

# Charter Currency's Gold Sovereign (chartercurrency/SPEC.md 4.1). The marker is what makes it money; the name and
# the lore match CoinStacks.make so a looted Sovereign stacks with a struck one.
SOVEREIGN = 'gold_sovereign'
SOVEREIGN_MODIFIERS = [
    {'type': 'minecraft:set_components', 'components': {'minecraft:custom_data': {'chartercurrency': {'coin': 'gold'}}}},
    {'type': 'minecraft:set_name', 'target': 'item_name', 'name': {'text': 'Gold Sovereign'}},
    {'type': 'minecraft:set_lore', 'mode': 'replace_all',
     'lore': [{'text': 'Charter coin, worth 64 Marks', 'color': 'gray', 'italic': False}]},
]

# Item, weight, counts for tiers 1 to 4.
CHEST = [
    ('minecraft:iron_ingot', 10, [(10, 22), (14, 30), (20, 40), (28, 52)]),
    ('minecraft:gold_ingot', 12, [(6, 14), (10, 22), (14, 30), (20, 40)]),
    (SOVEREIGN, 4, [(1, 2), (1, 3), (2, 4), (3, 6)]),
    ('minecraft:emerald', 7, [(5, 12), (8, 18), (12, 26), (16, 34)]),
    ('minecraft:nether_wart', 6, [(10, 22), (14, 32), (22, 48), (32, 64)]),
    ('minecraft:golden_apple', 6, [(1, 3), (2, 4), (3, 6), (4, 8)]),
    ('minecraft:trial_key', 2, [(1, 1), (1, 1), (1, 1), (1, 1)]),
    # The war tier: level 10 up.
    ('minecraft:enchanted_golden_apple', 1, [None, None, (1, 1), (1, 1)]),
    ('minecraft:diamond', 4, [None, None, (2, 5), (3, 8)]),
    ('minecraft:netherite_scrap', 2, [None, None, (1, 1), (1, 2)]),
    ('minecraft:ancient_debris', 1, [None, None, (1, 1), (1, 2)]),
    (PROTECTION, 1, [None, None, (1, 1), (1, 1)]),
    (SHARPNESS, 1, [None, None, (1, 1), (1, 1)]),
]
# Rolls per chest for tiers 1 to 4; an ominous chest rolls one more and weighs golden apples heavier.
CHEST_ROLLS = [(2, 3), (2, 3), (2, 4), (3, 4)]
OMINOUS_APPLE_WEIGHT = 3

# A supply chest is the small one: nether wart and a little metal.
SUPPLY = [
    ('minecraft:nether_wart', 5, [(10, 30), (14, 40), (20, 50), (30, 60)]),
    ('minecraft:iron_ingot', 4, [(5, 15), (10, 20), (10, 25), (15, 30)]),
    ('minecraft:gold_ingot', 4, [(5, 10), (5, 15), (10, 20), (10, 25)]),
    (SOVEREIGN, 1, [(1, 1), (1, 2), (1, 2), (2, 3)]),
]

# A vault pays bigger stacks of the metals and emeralds and always a stack of golden apples.
VAULT_METALS = [
    ('minecraft:iron_ingot', 5, [(20, 40), (30, 50), (40, 64), (48, 64)]),
    ('minecraft:gold_ingot', 6, [(16, 32), (24, 40), (32, 56), (40, 64)]),
    ('minecraft:emerald', 4, [(12, 24), (16, 32), (24, 48), (32, 64)]),
]
# Every vault always pays Gold Sovereigns; an ominous vault pays half as many again.
VAULT_SOVEREIGNS = [(2, 4), (3, 6), (4, 8), (6, 12)]
VAULT_APPLES = [(2, 4), (3, 6), (4, 8), (6, 12)]
# The chance a vault pays an enchanted golden apple, plain and ominous.
VAULT_NOTCH_CHANCE = [(0.0, 0.0), (0.0, 0.0), (0.5, 0.75), (1.0, 1.0)]
# From tier 3 a vault rolls once on the war tier.
VAULT_WAR = [
    ('minecraft:diamond', 4, [None, None, (4, 8), (6, 12)]),
    ('minecraft:netherite_scrap', 2, [None, None, (1, 2), (2, 3)]),
    ('minecraft:ancient_debris', 1, [None, None, (1, 2), (2, 3)]),
    (PROTECTION, 1, [None, None, (1, 1), (1, 1)]),
    (SHARPNESS, 1, [None, None, (1, 1), (1, 1)]),
]

# Every count of the war supplies is multiplied by this (owner, 2026-11-02: "nerf the supplies for now 50%").
# The war tier, trial keys and Sovereigns are not scaled. Counts round half up and never drop below 1.
SUPPLY_SCALE = 0.5
SUPPLIES = {'minecraft:iron_ingot', 'minecraft:emerald', 'minecraft:nether_wart', 'minecraft:golden_apple'}
# Gold ingots have their own scale (owner, 2026-11-04: gold needs more sources), so the nerf no longer halves them.
GOLD_SCALE = 1.0
GOLD = 'minecraft:gold_ingot'

# The boss at the end of every wild dungeon (owner, 2026-11-04), one roll per kill, shared by the party: the owner's
# list (gold, emeralds, diamonds, ancient debris, Sovereigns, a heavy core now and then), graded by loot tier so a
# level 2 mine on a two hour cooldown does not pay what a level 12 long dungeon does. Tier 3 matches the owner's
# numbers most closely; tier 4 (level 20) is unreachable today. Counts are not touched by SUPPLY_SCALE or GOLD_SCALE.
# Valor (5 to 20) is paid by WildBoss.VALOR_COMMAND, not here.
BOSS = [
    ('minecraft:gold_ingot', [(8, 16), (12, 24), (16, 32), (24, 48)]),
    ('minecraft:emerald', [(8, 16), (12, 24), (16, 32), (24, 48)]),
    ('minecraft:diamond', [(1, 3), (2, 4), (4, 8), (6, 10)]),
    ('minecraft:ancient_debris', [(0, 1), (0, 2), (1, 3), (2, 4)]),
    (SOVEREIGN, [(8, 20), (12, 32), (20, 48), (32, 80)]),
]
# The chance a kill drops a heavy core (the mace's core, which no farm makes) by tier.
BOSS_HEAVY_CORE = [0.02, 0.04, 0.07, 0.10]
# An ominous kill (an Ominous Bottle was spent on the lodestone) pays half again of every count and of the core chance.
BOSS_OMINOUS_PERCENT = 150


def boss_entry(item, lo, hi):
    """One boss stack. Unlike entry(), a range that can be zero (no debris) still gets its count."""
    sovereign = item == SOVEREIGN
    out = {'type': 'minecraft:item', 'name': 'minecraft:gold_nugget' if sovereign else item}
    modifiers = [dict(m) for m in SOVEREIGN_MODIFIERS] if sovereign else []
    if (lo, hi) != (1, 1):
        modifiers.append({'type': 'minecraft:set_count',
                          'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}})
    if modifiers:
        out['modifier'] = modifiers
    return out


def boss(tier, ominous):
    pools = []
    for item, counts in BOSS:
        lo, hi = counts[tier - 1]
        if ominous:
            lo, hi = lo * BOSS_OMINOUS_PERCENT // 100, hi * BOSS_OMINOUS_PERCENT // 100
        pools.append({'rolls': 1, 'entries': [boss_entry(item, lo, hi)]})
    core = BOSS_HEAVY_CORE[tier - 1] * (BOSS_OMINOUS_PERCENT / 100 if ominous else 1.0)
    pools.append(chance_pool(round(core, 4), {'rolls': 1, 'entries': [boss_entry('minecraft:heavy_core', 1, 1)]}))
    return {'type': 'minecraft:chest', 'pools': pools}


# A typical clear, for the printed estimate only: the barrel's chest rolls plus the room chests, one vault and
# two supply chests.
CLEAR_CHESTS, CLEAR_VAULTS, CLEAR_SUPPLIES = 6, 1, 2


def scaled(name, counts):
    lo, hi = counts
    scale = SUPPLY_SCALE if name in SUPPLIES else GOLD_SCALE if name == GOLD else None
    if scale is None:
        return lo, hi
    lo = max(1, int(lo * scale + 0.5))
    return lo, max(lo, int(hi * scale + 0.5))


def entry(item, weight, counts):
    name, enchantments = item if isinstance(item, tuple) else (item, None)
    lo, hi = scaled(name, counts)
    sovereign = name == SOVEREIGN
    out = {'type': 'minecraft:item', 'name': 'minecraft:gold_nugget' if sovereign else name, 'weight': weight}
    modifiers = [dict(m) for m in SOVEREIGN_MODIFIERS] if sovereign else []
    if hi > 1:
        modifiers.append({'type': 'minecraft:set_count',
                          'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}})
    if enchantments:
        modifiers.append({'type': 'minecraft:set_enchantments', 'enchantments': enchantments})
    if modifiers:
        out['modifier'] = modifiers
    return out


def rolls(lo, hi):
    return lo if lo == hi else {'type': 'minecraft:uniform', 'min': lo, 'max': hi}


def entries(rows, tier, ominous=False):
    out = []
    for item, weight, counts in rows:
        if counts[tier - 1] is None:
            continue
        if ominous and item == 'minecraft:golden_apple':
            weight += OMINOUS_APPLE_WEIGHT
        out.append(entry(item, weight, counts[tier - 1]))
    return out


def chest(tier, ominous):
    lo, hi = CHEST_ROLLS[tier - 1]
    if ominous:
        lo, hi = lo + 1, hi + 1
    return {'type': 'minecraft:chest', 'pools': [{'rolls': rolls(lo, hi), 'entries': entries(CHEST, tier, ominous)}]}


def supply(tier):
    return {'type': 'minecraft:chest', 'pools': [{'rolls': rolls(1, 2), 'entries': entries(SUPPLY, tier)}]}


# The vault's nether wart stack is the chest's.
NETHER_WART_CHEST = next(counts for item, _, counts in CHEST if item == 'minecraft:nether_wart')


def sovereigns(tier, ominous):
    lo, hi = VAULT_SOVEREIGNS[tier - 1]
    return (lo + lo // 2, hi + hi // 2) if ominous else (lo, hi)


def chance_pool(chance, pool):
    if chance < 1.0:
        pool['condition'] = {'type': 'minecraft:random_chance', 'chance': chance}
    return pool


def vault(tier, ominous):
    metal_rolls = (2, 3) if ominous else (1, 2)
    pools = [{'rolls': rolls(*metal_rolls), 'entries': entries(VAULT_METALS, tier)},
             {'rolls': 1, 'entries': [entry('minecraft:golden_apple', 1, VAULT_APPLES[tier - 1])]},
             {'rolls': 1, 'entries': [entry('minecraft:nether_wart', 1, NETHER_WART_CHEST[tier - 1])]},
             {'rolls': 1, 'entries': [entry(SOVEREIGN, 1, sovereigns(tier, ominous))]}]
    notch = VAULT_NOTCH_CHANCE[tier - 1][1 if ominous else 0]
    if notch > 0:
        pools.append(chance_pool(notch, {'rolls': 1,
                                         'entries': [entry('minecraft:enchanted_golden_apple', 1, (1, 1))]}))
    war = entries(VAULT_WAR, tier)
    if war:
        pools.append({'rolls': 1, 'entries': war})
    return {'type': 'minecraft:chest', 'pools': pools}


def expected(table, out, times):
    """Adds a table's expected item counts, times over, into out."""
    for pool in table['pools']:
        r = pool['rolls']
        mean_rolls = r if isinstance(r, (int, float)) else (r['min'] + r['max']) / 2
        chance = pool.get('condition', {}).get('chance', 1.0)
        total = sum(e.get('weight', 1) for e in pool['entries'])
        for e in pool['entries']:
            count = 1
            for m in e.get('modifier', []):
                if m['type'] == 'minecraft:set_count':
                    count = (m['count']['min'] + m['count']['max']) / 2
            name = e['name']
            if any(m['type'] == 'minecraft:set_components' for m in e.get('modifier', [])):
                name = 'gold_sovereign'
            for m in e.get('modifier', []):
                if m['type'] == 'minecraft:set_enchantments':
                    name = 'book (' + ', '.join(m['enchantments']) + ')'
            out[name] = out.get(name, 0) + times * chance * mean_rolls * e.get('weight', 1) / total * count


def estimate(tier):
    out = {}
    expected(chest(tier, False), out, CLEAR_CHESTS)
    expected(vault(tier, False), out, CLEAR_VAULTS)
    expected(supply(tier), out, CLEAR_SUPPLIES)
    return out


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
        write(f'boss/tier_{tier}_wild.json', boss(tier, False))
        write(f'boss/tier_{tier}_ominous_wild.json', boss(tier, True))
    print('wrote 28 wild loot tables')
    print(f'expected yield of a plain clear ({CLEAR_CHESTS} chest rolls, {CLEAR_VAULTS} vault, '
          f'{CLEAR_SUPPLIES} supply chests):')
    for tier in range(1, 5):
        items = estimate(tier)
        print(f'  tier {tier}: ' + ', '.join(f'{name.removeprefix("minecraft:")} {n:.1f}'
                                            for name, n in sorted(items.items(), key=lambda kv: -kv[1])))
    print('expected yield of one boss kill, plain then ominous:')
    for tier in range(1, 5):
        for ominous in (False, True):
            items = {}
            expected(boss(tier, ominous), items, 1)
            print(f'  tier {tier}{" ominous" if ominous else ""}: ' + ', '.join(
                f'{name.removeprefix("minecraft:")} {n:.2f}' for name, n in items.items()))


if __name__ == '__main__':
    main()
