#!/usr/bin/env python3
"""Writes the hand-authored M73 Pair 1 files (Rootworks + Frostworks):
theme metadata, adventure nodes, processor lists, and signature rooms.
Run from the mod root: python tools/gen_pair1_handauthored.py
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

# ---- Pair 1: Rootworks (overgrowth) + Frostworks (footing) ----

# Theme metadata
write('dungeon_theme/rootworks.json', {
    "name": "Rootworks",
    "processors": "pocketdungeons:theme_rootworks",
    "spawner_prefix": "rootworks",
    "loot_suffix": "rootworks",
    "room_theme": "rootworks",
})
write('dungeon_theme/frostworks.json', {
    "name": "Frostworks",
    "processors": "pocketdungeons:theme_frostworks",
    "spawner_prefix": "frostworks",
    "loot_suffix": "frostworks",
    "room_theme": "frostworks",
})

# Adventure nodes (descent kind, reachable from entry themes)
write('dungeon_adventure/rootworks.json', {
    "kind": "descent",
    "next": [
        {"theme": "frostworks", "weight": 2},
        {"theme": "deepslate", "weight": 2},
        {"theme": "prismarine", "weight": 2},
    ],
})
write('dungeon_adventure/frostworks.json', {
    "kind": "descent",
    "next": [
        {"theme": "rootworks", "weight": 2},
        {"theme": "blackstone", "weight": 2},
        {"theme": "infestation", "weight": 2},
    ],
})

# Processor lists (decorative shell blocks only)
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

# Rootworks: overgrowth palette
write('worldgen/processor_list/theme_rootworks.json', proc_list([
    rule("stone_bricks", "mossy_stone_bricks"),
    rule("polished_andesite", "moss_block"),
    rule("mossy_stone_bricks", "mossy_cobblestone"),
    rule("sea_lantern", "shroomlight"),
]))

# Frostworks: footing palette (ice floors are slippery)
write('worldgen/processor_list/theme_frostworks.json', proc_list([
    rule("stone_bricks", "packed_ice"),
    rule("polished_andesite", "snow_block"),
    rule("mossy_stone_bricks", "blue_ice"),
    rule("sea_lantern", "sea_lantern"),
]))

# Signature room processors (room-specific precedence, distinct from theme)
# Rootworks grove: denser overgrowth with hanging roots
write('worldgen/processor_list/theme_rootworks_grove.json', proc_list([
    rule("stone_bricks", "mossy_cobblestone"),
    rule("polished_andesite", "moss_block"),
    rule("mossy_stone_bricks", "rooted_dirt"),
    rule("sea_lantern", "shroomlight"),
]))

# Frostworks glaze: pure ice footing, no snow cover
write('worldgen/processor_list/theme_frostworks_glaze.json', proc_list([
    rule("stone_bricks", "blue_ice"),
    rule("polished_andesite", "packed_ice"),
    rule("mossy_stone_bricks", "packed_ice"),
    rule("sea_lantern", "sea_lantern"),
]))

# Signature rooms (reuse existing geometry, room-specific processor)
write('dungeon_room/rootworks_grove.json', {
    "template": "pocketdungeons:rooms/mossy_tee",
    "processors": "pocketdungeons:theme_rootworks_grove",
    "footprint": [1, 1],
    "roles": ["corridor"],
    "theme": ["rootworks"],
    "weight": 2,
    "minDepth": 1,
    "maxPerDungeon": 1,
})
write('dungeon_room/frostworks_glaze.json', {
    "template": "pocketdungeons:rooms/mossy_tee",
    "processors": "pocketdungeons:theme_frostworks_glaze",
    "footprint": [1, 1],
    "roles": ["corridor"],
    "theme": ["frostworks"],
    "weight": 2,
    "minDepth": 1,
    "maxPerDungeon": 1,
})

print('done')
