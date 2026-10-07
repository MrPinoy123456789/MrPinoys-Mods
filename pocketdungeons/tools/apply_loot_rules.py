#!/usr/bin/env python3
"""One-shot rewrite of the hand-authored loot tables to the K and K2 rules.

Generated tables are covered at the source instead: gen_themed_content.py and
gen_resource_content.py author legal pools now, and gen_vault_tables.py
synthesizes the vault tables from scratch. This script is for everything else:
the base chests/tier_* and supply_tier_* tables, the drowned standalones,
pocket2, anomaly, the spawner ejects, gear/*, equipment/* and the two hand
vaults. It walks them all anyway and is idempotent, so a stray regenerated
file is rewritten the same way.

On top of the shared rewrite it lands K's additions:

  * sand and gravel enter every base supply_tier_* table at cobblestone's
    weight (sand for TNT with F's gunpowder, gravel for flint),
  * lapis_lazuli enters every tier 2 to 4 chest table at about coal's weight
    (K2.10: enchanting and the reroll spend it and it essentially never
    dropped),
  * minecraft:cake 1 enters every chests/tier_* table at a golden apple's
    weight (the rare food: vault and finish chest tables only).

Run from the mod root:

    python tools/apply_loot_rules.py
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import loot_rules as rules

LOOT = 'src/main/resources/data/pocketdungeons/loot_table'


def load(path):
    with open(path, encoding='utf-8-sig') as handle:
        return json.load(handle)


def save(path, table):
    with open(path, 'w', encoding='utf-8', newline='\n') as handle:
        json.dump(table, handle, indent=2)
        handle.write('\n')


def item(name, weight=None, count=None):
    entry = {'type': 'minecraft:item', 'name': 'minecraft:' + name}
    if weight is not None:
        entry['weight'] = weight
    if count is not None:
        entry['functions'] = [{'function': 'minecraft:set_count',
                               'count': {'min': count[0], 'max': count[1]}}]
    return entry


def weight_of(table, name, fallback):
    best = fallback
    for pool in table.get('pools', []):
        for entry in pool.get('entries', []):
            if entry.get('name') == 'minecraft:' + name:
                best = max(best, entry.get('weight', 1))
    return best


def has_item(table, name):
    return any(entry.get('name') == 'minecraft:' + name
               for pool in table.get('pools', [])
               for entry in pool.get('entries', []))


def tier_of(rel):
    """The tier number of a chests/tier_N* file name, or None."""
    base = rel.rsplit('/', 1)[-1][:-5]
    if not base.startswith('tier_'):
        return None
    try:
        return int(base.split('_')[1])
    except (ValueError, IndexError):
        return None


def add_pool_entries(table, entries):
    """Append entries to the table's bulk pool: the one with the most entries,
    so an addition lands beside the materials it was weighted against rather
    than in a single-purpose pool like the trim pool."""
    pools = table.get('pools', [])
    if pools:
        bulk = max(pools, key=lambda pool: len(pool.get('entries', [])))
        bulk['entries'].extend(entries)


def main():
    if not os.path.isdir(LOOT):
        sys.exit('run this from the mod root: %s not found' % LOOT)
    changed = []
    for root, _, files in os.walk(LOOT):
        for name in sorted(files):
            if not name.endswith('.json'):
                continue
            path = os.path.join(root, name)
            rel = os.path.relpath(path, LOOT).replace(os.sep, '/')
            if rules.context_of(rel) == 'exempt':
                continue
            table = load(path)
            rewritten = rules.rewrite_table(rel, table)
            tier = tier_of(rel)
            # K2.10: lapis in every tier 2 to 4 chest table at coal's weight.
            if rel.startswith('chests/') and tier is not None and tier >= 2 \
                    and not has_item(rewritten, 'lapis_lazuli'):
                add_pool_entries(rewritten,
                                 [item('lapis_lazuli', weight_of(rewritten, 'coal', 4), (1, 2))])
            # K: sand and gravel in the supply tables at cobblestone's weight.
            if rules.context_of(rel) == 'supply' and '_cow_pits' not in rel:
                weight = weight_of(rewritten, 'cobblestone', 4)
                additions = [item('sand', weight, (1, 3)) if not has_item(rewritten, 'sand') else None,
                             item('gravel', weight, (1, 3)) if not has_item(rewritten, 'gravel') else None]
                add_pool_entries(rewritten, [e for e in additions if e])
            # K: cake is the rare food of the finish chest tables.
            if rules.is_finish_table(rel) and not has_item(rewritten, 'cake'):
                add_pool_entries(rewritten,
                                 [item('cake', weight_of(rewritten, 'golden_apple', 1))])
            if rewritten != table:
                save(path, rewritten)
                changed.append(rel)
    for rel in changed:
        print('rewrote', rel)
    print('done: %d files changed' % len(changed))


if __name__ == '__main__':
    main()
