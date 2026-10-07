#!/usr/bin/env python3
"""Generates the W7b data for the Act 4 and Act 5 content: the Wither's Keep, the End and Herobrine
dungeons, their themes, processor lists, adventure nodes, trial spawner configs and diary pages, the
eight new rooms' metadata, and the edits to the older Act 4 and Act 5 dungeons (node palettes, a forest
node, diary links) and to the W6 rooms (a legacy `theme` list beside `dungeons`).

Run from the mod root, any number of times (it rewrites what it owns and patches idempotently):

    python tools/gen_w7b_content.py

Room templates (.nbt) are not made here: run `dungeon admin gentemplates <room>` on a local server.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_themed_content as g

DATA = 'src/main/resources/data/pocketdungeons'


def path(*parts):
    return os.path.join(DATA, *parts)


def load(*parts):
    with open(path(*parts), encoding='utf-8-sig') as handle:
        return json.load(handle)


def write(obj, *parts):
    target = path(*parts)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'w', encoding='utf-8', newline='\n') as handle:
        json.dump(obj, handle, indent=2, ensure_ascii=False)
        handle.write('\n')


# ---- trial spawners --------------------------------------------------------------------------

SPAWNERS = {
    'wither_keep': {
        1: [('wither_skeleton', 5, 'melee'), ('blaze', 3, None)],
        2: [('wither_skeleton', 5, 'melee'), ('blaze', 4, None), ('skeleton', 2, 'ranged')],
        3: [('wither_skeleton', 6, 'melee'), ('blaze', 4, None)],
    },
    'the_end': {
        1: [('enderman', 5, None), ('endermite', 3, None), ('shulker', 1, None)],
        2: [('enderman', 5, None), ('shulker', 2, None), ('endermite', 2, None)],
        3: [('enderman', 6, None), ('shulker', 3, None)],
    },
    'herobrine': {
        1: [('enderman', 4, None), ('wither_skeleton', 4, 'melee'), ('husk', 3, 'melee')],
        2: [('enderman', 4, None), ('wither_skeleton', 5, 'melee'), ('husk', 3, 'melee')],
        3: [('enderman', 5, None), ('wither_skeleton', 6, 'melee'), ('husk', 3, 'melee')],
    },
}


def spawners():
    for prefix, roster in SPAWNERS.items():
        for tier in (1, 2, 3):
            for mode in ('normal', 'ominous'):
                obj = g.spawner_config(prefix, tier, mode == 'ominous', roster[tier])
                g.write_json(os.path.join(g.SPAWNER_DIR, '%s_tier_%d' % (prefix, tier), mode + '.json'), obj)


# ---- processor lists --------------------------------------------------------------------------

def rule(block, output, probability=None):
    predicate = {'predicate_type': 'minecraft:block_match', 'block': 'minecraft:' + block}
    if probability is not None:
        predicate = {'predicate_type': 'minecraft:random_block_match', 'block': 'minecraft:' + block,
                     'probability': probability}
    return {
        'input_predicate': predicate,
        'location_predicate': {'predicate_type': 'minecraft:always_true'},
        'output_state': {'Name': 'minecraft:' + output},
    }


def processor_list(rules):
    return {'processors': [{'processor_type': 'minecraft:rule', 'rules': rules}]}


PROCESSORS = {
    'theme_wither_keep': processor_list([
        rule('stone_bricks', 'cracked_nether_bricks', 0.2),
        rule('stone_bricks', 'nether_bricks'),
        rule('polished_andesite', 'soul_soil'),
        rule('mossy_stone_bricks', 'blackstone'),
        rule('sea_lantern', 'soul_lantern'),
    ]),
    'theme_the_end': processor_list([
        rule('stone_bricks', 'end_stone_bricks'),
        rule('polished_andesite', 'purpur_block'),
        rule('mossy_stone_bricks', 'purpur_pillar'),
        rule('sea_lantern', 'end_rod'),
    ]),
    'theme_herobrine': processor_list([
        rule('stone_bricks', 'crying_obsidian', 0.12),
        rule('stone_bricks', 'polished_blackstone_bricks'),
        rule('polished_andesite', 'obsidian'),
        rule('mossy_stone_bricks', 'blackstone'),
        rule('sea_lantern', 'end_rod'),
    ]),
}

THEMES = {
    'wither_keep': {
        'name': "The Wither's Keep",
        'processors': 'pocketdungeons:theme_wither_keep',
        'spawner_prefix': 'wither_keep',
        'loot_suffix': 'basalt_foundry',
        'room_theme': 'wither_keep',
    },
    'the_end': {
        'name': 'The End',
        'processors': 'pocketdungeons:theme_the_end',
        'spawner_prefix': 'the_end',
        'loot_suffix': 'ender_archive',
        'room_theme': 'the_end',
    },
    'herobrine': {
        'name': 'Herobrine',
        'processors': 'pocketdungeons:theme_herobrine',
        'spawner_prefix': 'herobrine',
        'loot_suffix': 'ender_archive',
        'room_theme': 'herobrine',
    },
}

# Adventure nodes: every new theme is a plain descent node (kind boss would summon the Drowned Warden).
ADVENTURE = {
    'wither_keep': {'kind': 'descent', 'next': [{'theme': 'basalt_foundry', 'weight': 2},
                                                {'theme': 'blackstone', 'weight': 1}]},
    'the_end': {'kind': 'descent', 'next': [{'theme': 'ender_archive', 'weight': 2},
                                            {'theme': 'herobrine', 'weight': 1}]},
    'herobrine': {'kind': 'descent', 'next': [{'theme': 'the_end', 'weight': 2},
                                              {'theme': 'ender_archive', 'weight': 1}]},
}


# ---- dungeons ---------------------------------------------------------------------------------

def node(node_id, name, layer, **extra):
    out = {'id': node_id, 'name': name, 'layer': layer}
    out.update(extra)
    return out


def edge(src, dst, cost=None):
    out = {'from': src, 'to': dst}
    if cost:
        out['cost'] = cost
    return out


FOREST_STEMS = ['warped_stem', 'crimson_stem', 'warped_wart_block', 'nether_wart_block']

DUNGEONS = {
    'wither_keep': {
        'name': "The Wither's Keep",
        'act': 4,
        'kind': 'capstone',
        'mainTheme': 'wither_keep',
        'lootBand': {'min': 3, 'max': 4},
        'nodePalette': ['nether_quartz_ore', 'nether_gold_ore', 'ancient_debris'] + FOREST_STEMS,
        'diary': 'entry_22',
        'deviation': {'chance': 0.2, 'themes': ['basalt_foundry', 'blackstone']},
        'nodes': [
            node('soul_gate', 'The Soul Gate', 1, light='dim'),
            node('fortress_bridges', 'The Fortress Bridges', 2, light='dim',
                 roomBias=['collapsing_bridge', 'soul_sand_valley']),
            node('warped_grove', 'The Warped Grove', 2, light='dim',
                 roomBias=['warped_forest', 'crimson_forest']),
            node('skull_cellars', 'The Skull Cellars', 3, light='dim',
                 roomBias=['wither_loft', 'soul_sand_valley']),
            node('blaze_spire', 'The Blaze Spire', 3, theme='basalt_foundry', light='dim',
                 roomBias=['blaze_loft']),
            node('withers_throne', "The Wither's Throne", 4, final=True, light='dim', roomBias=['wither_hall']),
        ],
        'edges': [
            edge('soul_gate', 'fortress_bridges'),
            edge('soul_gate', 'warped_grove'),
            edge('fortress_bridges', 'skull_cellars'),
            edge('fortress_bridges', 'blaze_spire', 1),
            edge('warped_grove', 'skull_cellars'),
            edge('warped_grove', 'blaze_spire'),
            edge('skull_cellars', 'withers_throne'),
            edge('blaze_spire', 'withers_throne'),
        ],
    },
    'the_end': {
        'name': 'The End',
        'act': 5,
        'kind': 'story',
        'mainTheme': 'the_end',
        'lootBand': {'min': 4, 'max': 4},
        'nodePalette': ['deepslate_diamond_ore', 'ancient_debris'],
        'diary': 'entry_24',
        'deviation': {'chance': 0.2, 'themes': ['ender_archive', 'blackstone']},
        'nodes': [
            node('outer_gate', 'The Outer Gate', 1, light='dim'),
            node('outer_islands', 'The Outer Islands', 2, roomBias=['end_island']),
            node('chorus_orchard', 'The Chorus Orchard', 2, roomBias=['end_island']),
            node('end_city', 'The End City', 3, roomBias=['end_city_hall']),
            node('stronghold_stacks', 'The Stronghold Stacks', 3, theme='ender_archive'),
            node('end_ship', 'The End Ship', 3, roomBias=['end_ship']),
            node('world_rim', 'The Rim of the World', 4, final=True),
        ],
        'edges': [
            edge('outer_gate', 'outer_islands'),
            edge('outer_gate', 'chorus_orchard'),
            edge('outer_islands', 'end_city'),
            edge('outer_islands', 'end_ship', 1),
            edge('chorus_orchard', 'end_city'),
            edge('chorus_orchard', 'stronghold_stacks'),
            edge('end_city', 'world_rim'),
            edge('stronghold_stacks', 'world_rim'),
            edge('end_ship', 'world_rim'),
        ],
    },
    'herobrine': {
        'name': 'Herobrine',
        'act': 5,
        'kind': 'capstone',
        'mainTheme': 'herobrine',
        'lootBand': {'min': 4, 'max': 4},
        'nodePalette': ['deepslate_diamond_ore', 'ancient_debris'],
        'diary': 'entry_25',
        'deviation': {'chance': 0.2, 'themes': ['ender_archive', 'basalt_foundry']},
        'nodes': [
            node('last_stair', 'The Last Stair', 1, light='dim'),
            node('remembered_bastion', 'The Remembered Bastion', 2, theme='blackstone', light='dim'),
            node('remembered_stronghold', 'The Remembered Stronghold', 2, theme='ender_archive', light='dim'),
            node('the_seam', 'The Seam', 3, light='dim'),
            node('the_fracture', 'The Fracture', 4, final=True, light='dim', roomBias=['fracture_hall']),
        ],
        'edges': [
            edge('last_stair', 'remembered_bastion'),
            edge('last_stair', 'remembered_stronghold'),
            edge('remembered_bastion', 'the_seam'),
            edge('remembered_stronghold', 'the_seam'),
            edge('the_seam', 'the_fracture'),
        ],
    },
}


def patch_dungeon(name, diary, grove):
    """Palette stems, the diary link and one forest node on an existing Act 4 or 5 dungeon."""
    d = load('dungeon', name + '.json')
    out = {}
    for key, value in d.items():
        out[key] = value
        if key == 'nodePalette':
            if grove is not None:
                for block in FOREST_STEMS:
                    if block not in value:
                        value.append(block)
            if diary:
                out['diary'] = diary
    if diary:
        out['diary'] = diary
    if grove is not None:
        node_id, node_name, layer, bias, froms, to = grove
        if not any(n['id'] == node_id for n in out['nodes']):
            out['nodes'].append(node(node_id, node_name, layer, roomBias=bias))
            for src in froms:
                out['edges'].append(edge(src, node_id))
            out['edges'].append(edge(node_id, to))
    write(out, 'dungeon', name + '.json')


# ---- rooms ------------------------------------------------------------------------------------

def room(template, roles, weight, max_per, dungeons, acts, light, biome, doors_note, nodes=None,
         tier=2, graph_role=None, min_depth=0):
    out = {
        'template': 'pocketdungeons:rooms/' + template,
        'footprint': [1, 1],
        'roles': roles,
        'weight': weight,
        'minDepth': min_depth,
        'maxPerDungeon': max_per,
        'theme': dungeons,
        'light': light,
        'biome': biome,
        'dungeons': dungeons,
        'acts': acts,
    }
    if graph_role:
        out['graphRole'] = graph_role
    if nodes:
        out['nodes'] = nodes
    out.update({'tier': tier, 'provides': [], 'requires': [], 'access': 'open', 'window': 'bars'})
    return out


def at(block, x, y, z):
    return {'block': 'minecraft:' + block, 'at': [x, y, z]}


def box(block, frm, to, count=None):
    out = {'block': 'minecraft:' + block, 'from': frm, 'to': to}
    if count:
        out['count'] = count
    return out


NETHER = ['basalt_foundry', 'blackstone', 'wither_keep']
ROOMS = {
    'warped_forest': room('warped_forest', ['encounter', 'loot', 'corridor'], 3, 2, NETHER, [4], 'dim',
                          'warped_forest', '4 doors', nodes=[
        box('warped_stem', [2, 1, 2], [2, 4, 2]), box('warped_stem', [13, 1, 2], [13, 4, 2]),
        box('warped_stem', [2, 1, 13], [2, 4, 13]), box('warped_stem', [5, 1, 9], [5, 4, 9]),
        box('warped_stem', [10, 1, 5], [10, 4, 5]),
        box('warped_wart_block', [1, 4, 1], [2, 5, 2], 5), box('warped_wart_block', [12, 4, 1], [14, 5, 3], 5),
        box('warped_wart_block', [4, 4, 8], [6, 5, 10], 5),
    ]),
    'crimson_forest': room('crimson_forest', ['encounter', 'loot', 'corridor'], 3, 2, NETHER, [4], 'dim',
                           'crimson_forest', '2 doors', nodes=[
        box('crimson_stem', [2, 1, 2], [2, 4, 2]), box('crimson_stem', [13, 1, 13], [13, 4, 13]),
        box('crimson_stem', [2, 1, 13], [2, 4, 13]), box('crimson_stem', [11, 1, 2], [11, 4, 2]),
        box('nether_wart_block', [1, 4, 1], [2, 5, 2], 5), box('nether_wart_block', [10, 4, 1], [12, 5, 3], 5),
        box('nether_wart_block', [1, 4, 12], [3, 5, 14], 5),
    ]),
    'soul_sand_valley': room('soul_sand_valley', ['encounter', 'corridor'], 2, 2, ['wither_keep'], [4], 'dim',
                             'soul_sand_valley', '2 doors', nodes=[
        at('nether_quartz_ore', 11, 1, 3), at('nether_quartz_ore', 12, 2, 3), at('nether_quartz_ore', 13, 1, 3),
    ]),
    'wither_hall': room('wither_hall', ['exit'], 1, -1, ['wither_keep'], [4], 'dim', 'nether_fortress',
                        '1 door', tier=1, graph_role=['capstone']),
    'end_island': room('end_island', ['encounter', 'loot', 'corridor'], 3, 2, ['the_end', 'herobrine'], [5], 'dim',
                       'end_highlands', '4 doors', nodes=[
        at('deepslate_diamond_ore', 12, 2, 2), at('ancient_debris', 3, 2, 2), at('deepslate_diamond_ore', 2, 1, 13),
    ]),
    'end_city_hall': room('end_city_hall', ['encounter', 'loot', 'corridor'], 3, 2, ['the_end'], [5], 'lit',
                          'end_city', '4 doors'),
    'end_ship': room('end_ship', ['encounter', 'loot', 'corridor'], 2, 1, ['the_end'], [5], 'lit', 'end_ship',
                     '2 doors'),
    'fracture_hall': room('fracture_hall', ['exit'], 1, -1, ['herobrine'], [5], 'dim', 'the_fracture', '1 door',
                          tier=1, graph_role=['capstone']),
}

# The W6 rooms lacked a legacy `theme` list, so the legacy (theme only) room filter, which the Endless Mine
# and admin builds still use, could draw them for any theme. The Mine's room theme is deepslate, so the four
# mineshaft rooms also name deepslate on purpose: a mine floor draws mine rooms.
W6_THEMES = {
    'mineshaft_tunnel': ['mineshaft', 'deepslate'],
    'mineshaft_crossing': ['mineshaft', 'deepslate'],
    'mineshaft_seam': ['mineshaft', 'deepslate'],
    'mineshaft_collapse': ['mineshaft', 'deepslate'],
    'lush_hollow': ['rootworks'],
    'lush_root_gallery': ['rootworks'],
    'lush_clay_pool': ['rootworks'],
    'cow_pens': ['cow_pits'],
    'hay_loft': ['cow_pits'],
    'cow_yard': ['cow_pits'],
    'burrow_tunnel': ['infestation'],
    'ossuary_passage': ['ossuary'],
}


def patch_w6_room(name, themes):
    d = load('dungeon_room', name + '.json')
    out = {}
    for key, value in d.items():
        if key == 'theme':
            continue
        out[key] = value
        if key == 'maxPerDungeon':
            out['theme'] = themes
    write(out, 'dungeon_room', name + '.json')


# ---- diaries ----------------------------------------------------------------------------------

DIARIES = {
    20: (25, 'The Ash Foundry', [
        "The portal let me through without asking my name.\n\nThe Nether is the one place he built that I did not have to learn."
        " It is exactly as the stories had it. Hot. Loud. Honest about wanting to kill you.",
        "A foundry, here, in the ash. Furnaces still lit, slag still moving, nobody tending any of it.\n\n"
        "He always liked a fire that kept going without him.",
        "I found his old boots by the crucible.\n\nHe was never a man who left things where they could be found."
        " The place put them there so that I would.",
    ]),
    21: (26, 'Gold Under Guard', [
        "A bastion. Gold stacked in rooms with no doors.\n\nThe piglins watch me as if I owe them something."
        " I think the place keeps a ledger and I am in it.",
        "He wanted this treasure once. Not for the gold. For the proof that he had been brave enough to go and take it.\n\n"
        "There is a kind of hoard that is only a trophy case for a man's fear.",
        "Nothing here is guarded against thieves. It is guarded against being forgotten.",
    ]),
    22: (27, 'Three Heads', [
        "A keep of soul sand and old brick, past the last bridge.\n\nSkeletons stand in the walls as if they had been built"
        " into them, and perhaps they were.",
        "He made a thing here once. Three skulls, four blocks of soul sand, and a patience I never had.\n\n"
        "He said a hero should know what he can kill. He never said what it costs to keep the pieces.",
        "It rises. It always rises. Do not stand near the centre when it does.\n\nI did, the first time."
        " I will not tell you what that taught me.",
    ]),
    23: (28, 'The Stacks', [
        "Shelves without a library. Books written in a hand I almost know.\n\nThe archive does not keep what happened."
        " It keeps what he was afraid to forget, which is a smaller and much stranger thing.",
        "I turned a page and it was a map of the stronghold. I turned it back and it was a shopping list.\n\n"
        "He always did write everything down. Then lose the lists.",
        "The Endermen do not look at me anymore.\n\nI think they have decided I am furniture. I find that restful.",
    ]),
    24: (29, 'The Last Island', [
        "The islands drift where the sky should be. Chorus fruit on stems that remember being plants.\n\n"
        "The quiet here is not peace. It is the quiet of something that has already finished.",
        "A city stands out on the rim with a ship tied to it, as if someone meant to leave and never did.\n\n"
        "The ship has a dragon's head on the prow. He would never have kept that.",
        "He went to the End to fix the world. I keep writing that sentence as if it explained something.\n\n"
        "It only says where he was standing when it stopped being true.",
    ]),
    25: (30, 'What I Said to Him', [
        "I did it. I walked in and I said his name, and for a moment it was the right name.\n\n"
        "He looked at me and I saw him all the way down.",
        "I could not hold him. I want to write that I chose to let go. I did not. He was simply stronger than whatever I had brought.\n\n"
        "But he left. He did not finish what he came to do, and that is the only victory I have to report.",
        "To whoever stood in that room with me: I am sorry I could not be gentler about it.\n\n"
        "You were braver than the compass deserved. I am still holding mine.",
        "I am going after him. Not to bring him home. I have stopped believing in home.\n\n"
        "To see that he is somewhere the world cannot reach. To see that the seal holds.\n\n"
        "The search continues. It always did.",
    ]),
}


def main():
    spawners()
    for name, obj in PROCESSORS.items():
        write(obj, 'worldgen', 'processor_list', name + '.json')
    for name, obj in THEMES.items():
        write(obj, 'dungeon_theme', name + '.json')
    for name, obj in ADVENTURE.items():
        write(obj, 'dungeon_adventure', name + '.json')
    for name, obj in DUNGEONS.items():
        write(obj, 'dungeon', name + '.json')
    for name, obj in ROOMS.items():
        write(obj, 'dungeon_room', name + '.json')
    for number, (band, title, pages) in DIARIES.items():
        write({'number': number, 'band': band, 'title': title, 'pages': pages}, 'diary', 'entry_%d.json' % number)

    # The new themes are reachable: listed under the deepslate entry node.
    deep = load('dungeon_adventure', 'deepslate.json')
    for theme in ('wither_keep', 'the_end', 'herobrine'):
        if not any(e['theme'] == theme for e in deep['next']):
            deep['next'].append({'theme': theme, 'weight': 1})
    write(deep, 'dungeon_adventure', 'deepslate.json')

    # Older Act 4 and Act 5 dungeons: palette stems, a forest node, diary pages.
    patch_dungeon('basalt_foundry', 'entry_20',
                  ('warped_grove', 'The Warped Grove', 3, ['warped_forest', 'crimson_forest'],
                   ['cinder_ramps', 'slag_channels'], 'great_crucible'))
    patch_dungeon('blackstone', 'entry_21',
                  ('crimson_grove', 'The Crimson Grove', 3, ['crimson_forest', 'warped_forest'],
                   ['piglin_barracks', 'hoard_corridors'], 'gilded_treasury'))
    patch_dungeon('ender_archive', 'entry_23', None)

    for name, themes in W6_THEMES.items():
        patch_w6_room(name, themes)
    print('done')


if __name__ == '__main__':
    main()
