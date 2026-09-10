#!/usr/bin/env python3
"""Writes the hand-authored M73 Pair 3 files (Basalt Foundry + Ender Archive):
theme metadata, adventure nodes, processor lists, and signature rooms.
Run from the mod root: python tools/gen_pair3_handauthored.py
"""
import json
import os

BASE = 'src/main/resources/data/pocketdungeons'

def write(rel, obj):
    path = os.path.join(BASE, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')
    print('wrote', path)

def rule(inp, out_name, props=None):
    output = {"Name": out_name}
    if props:
        output["Properties"] = props
    return {
        "input_predicate": {"predicate_type": "minecraft:block_match", "block": "minecraft:" + inp},
        "location_predicate": {"predicate_type": "minecraft:always_true"},
        "output_state": output,
    }

def proc_list(rules):
    return {"processors": [{"processor_type": "minecraft:rule", "rules": rules}]}

# ---- Pair 3: Basalt Foundry (heat) + Ender Archive (displacement/darkness) ----

# Theme metadata
write('dungeon_theme/basalt_foundry.json', {
    "name": "Basalt Foundry",
    "processors": "pocketdungeons:theme_basalt_foundry",
    "spawner_prefix": "basalt_foundry",
    "loot_suffix": "basalt_foundry",
    "room_theme": "basalt_foundry",
})
write('dungeon_theme/ender_archive.json', {
    "name": "Ender Archive",
    "processors": "pocketdungeons:theme_ender_archive",
    "spawner_prefix": "ender_archive",
    "loot_suffix": "ender_archive",
    "room_theme": "ender_archive",
})

# Adventure nodes
write('dungeon_adventure/basalt_foundry.json', {
    "kind": "descent",
    "next": [
        {"theme": "ender_archive", "weight": 2},
        {"theme": "blackstone", "weight": 2},
        {"theme": "deepslate", "weight": 2},
    ],
})
write('dungeon_adventure/ender_archive.json', {
    "kind": "descent",
    "next": [
        {"theme": "basalt_foundry", "weight": 2},
        {"theme": "prismarine", "weight": 2},
        {"theme": "infestation", "weight": 2},
    ],
})

# Processor lists
# Basalt Foundry: nether heat palette
write('worldgen/processor_list/theme_basalt_foundry.json', proc_list([
    rule("stone_bricks", "nether_bricks"),
    rule("polished_andesite", "basalt", {"axis": "y"}),
    rule("mossy_stone_bricks", "blackstone"),
    rule("sea_lantern", "glowstone"),
]))

# Ender Archive: end darkness palette (end_rod is dimmer than sea_lantern)
write('worldgen/processor_list/theme_ender_archive.json', proc_list([
    rule("stone_bricks", "end_stone_bricks"),
    rule("polished_andesite", "end_stone"),
    rule("mossy_stone_bricks", "obsidian"),
    rule("sea_lantern", "end_rod"),
]))

# Signature room processors
# Basalt Foundry crucible: magma block floor (heat hazard on contact)
write('worldgen/processor_list/theme_basalt_foundry_crucible.json', proc_list([
    rule("stone_bricks", "nether_bricks"),
    rule("polished_andesite", "magma_block"),
    rule("mossy_stone_bricks", "magma_block"),
    rule("sea_lantern", "glowstone"),
]))

# Ender Archive vault: crying obsidian (darker, purple tears)
write('worldgen/processor_list/theme_ender_archive_vault.json', proc_list([
    rule("stone_bricks", "end_stone_bricks"),
    rule("polished_andesite", "end_stone"),
    rule("mossy_stone_bricks", "crying_obsidian"),
    rule("sea_lantern", "end_rod"),
]))

# Signature rooms
write('dungeon_room/basalt_foundry_crucible.json', {
    "template": "pocketdungeons:rooms/treasure_alcove",
    "processors": "pocketdungeons:theme_basalt_foundry_crucible",
    "footprint": [1, 1],
    "roles": ["loot"],
    "theme": ["basalt_foundry"],
    "weight": 3,
    "minDepth": 1,
    "maxPerDungeon": 2,
})
write('dungeon_room/ender_archive_vault.json', {
    "template": "pocketdungeons:rooms/treasure_alcove",
    "processors": "pocketdungeons:theme_ender_archive_vault",
    "footprint": [1, 1],
    "roles": ["loot"],
    "theme": ["ender_archive"],
    "weight": 3,
    "minDepth": 1,
    "maxPerDungeon": 2,
})

print('done')
