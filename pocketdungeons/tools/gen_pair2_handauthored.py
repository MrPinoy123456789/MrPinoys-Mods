#!/usr/bin/env python3
"""Writes the hand-authored M73 Pair 2 files (Copper Works + Ossuary):
theme metadata, adventure nodes, processor lists, and signature rooms.
Run from the mod root: python tools/gen_pair2_handauthored.py
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

# ---- Pair 2: Copper Works (mechanisms) + Ossuary (ranged threats) ----

# Theme metadata
write('dungeon_theme/copper_works.json', {
    "name": "Copper Works",
    "processors": "pocketdungeons:theme_copper_works",
    "spawner_prefix": "copper_works",
    "loot_suffix": "copper_works",
    "room_theme": "copper_works",
})
write('dungeon_theme/ossuary.json', {
    "name": "Ossuary",
    "processors": "pocketdungeons:theme_ossuary",
    "spawner_prefix": "ossuary",
    "loot_suffix": "ossuary",
    "room_theme": "ossuary",
})

# Adventure nodes
write('dungeon_adventure/copper_works.json', {
    "kind": "descent",
    "next": [
        {"theme": "ossuary", "weight": 2},
        {"theme": "deepslate", "weight": 2},
        {"theme": "blackstone", "weight": 2},
    ],
})
write('dungeon_adventure/ossuary.json', {
    "kind": "descent",
    "next": [
        {"theme": "copper_works", "weight": 2},
        {"theme": "prismarine", "weight": 2},
        {"theme": "infestation", "weight": 2},
    ],
})

# Processor lists
# Copper Works: metallic copper palette (mechanism aesthetic)
write('worldgen/processor_list/theme_copper_works.json', proc_list([
    rule("stone_bricks", "cut_copper"),
    rule("polished_andesite", "copper_block"),
    rule("mossy_stone_bricks", "oxidized_copper"),
    rule("sea_lantern", "sea_lantern"),
]))

# Ossuary: bone and death palette
write('worldgen/processor_list/theme_ossuary.json', proc_list([
    rule("stone_bricks", "bone_block"),
    rule("polished_andesite", "smooth_stone"),
    rule("mossy_stone_bricks", "polished_blackstone_bricks"),
    rule("sea_lantern", "soul_lantern"),
]))

# Signature room processors
# Copper Works forge: weathered copper, darker mechanism aesthetic
write('worldgen/processor_list/theme_copper_works_forge.json', proc_list([
    rule("stone_bricks", "weathered_copper"),
    rule("polished_andesite", "copper_block"),
    rule("mossy_stone_bricks", "exposed_copper"),
    rule("sea_lantern", "sea_lantern"),
]))

# Ossuary crypt: darker bone, blackstone accents
write('worldgen/processor_list/theme_ossuary_crypt.json', proc_list([
    rule("stone_bricks", "bone_block"),
    rule("polished_andesite", "polished_blackstone"),
    rule("mossy_stone_bricks", "gilded_blackstone"),
    rule("sea_lantern", "soul_lantern"),
]))

# Signature rooms
write('dungeon_room/copper_works_forge.json', {
    "template": "pocketdungeons:rooms/crypt_corner",
    "processors": "pocketdungeons:theme_copper_works_forge",
    "footprint": [1, 1],
    "roles": ["encounter"],
    "theme": ["copper_works"],
    "weight": 2,
    "minDepth": 1,
    "maxPerDungeon": 2,
})
write('dungeon_room/ossuary_crypt.json', {
    "template": "pocketdungeons:rooms/crypt_corner",
    "processors": "pocketdungeons:theme_ossuary_crypt",
    "footprint": [1, 1],
    "roles": ["encounter"],
    "theme": ["ossuary"],
    "weight": 2,
    "minDepth": 1,
    "maxPerDungeon": 2,
})

print('done')
