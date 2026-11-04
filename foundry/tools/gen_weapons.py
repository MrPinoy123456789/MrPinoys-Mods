#!/usr/bin/env python3
"""Writes the kingdom weapons and their upgrade recipes as Foundry content.

Every weapon comes in three tiers; each tier is its own item, and a shapeless recipe turns one tier into the next.
Run it from the foundry folder to rewrite examples/content/kingdoms; change the tables below to rebalance, then run it
again. The output is plain Foundry JSON, so nothing here is needed at runtime.

Balance (2026-11-02). Each tier's strongest attack is worth about a vanilla sword of that tier: iron (6 damage at 1.6
attacks a second, about 9.6 a second), diamond (7, 11.2) and netherite (8, 12.8). A basic ranged attack (a left click
bolt) does about 60 percent of that, as it is safe at range; the Fairies' wand, a support weapon, about 40 to 50 percent.
Abilities sit on cooldowns. Roles: the Dwarves' War Pick breaks armour up close, the Humans' Oathblade duels and
leads, the Merfolk's Tide Trident fights close, throws, drags foes in and is stronger wet, the Fae's Briarthorn Staff controls with
poison and roots, and the Fairies' Everdelle Wand supports and harries. Projectiles carry a visible model for Java
players (Bedrock players see the particle trail), and hits draw their own shapes.

    python tools/gen_weapons.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "examples", "content")
RARITY = ["uncommon", "rare", "epic"]


def sound(name, volume=1.0, pitch=1.0):
    return {"type": "play_sound", "sound": name, "volume": volume, "pitch": pitch}


def sound_at(name, volume=1.0, pitch=1.0):
    a = sound(name, volume, pitch)
    a["target"] = "impact"
    return a


def effect(name, ticks, amp=0, target=None, radius=None):
    a = {"type": "apply_effect", "effect": name, "duration": ticks, "amplifier": amp}
    if target:
        a["target"] = target
    if radius:
        a["radius"] = radius
    return a


def particles(name, count, spread=0.3, target=None):
    a = {"type": "spawn_particles", "particle": name, "count": count, "spread": spread}
    if target:
        a["target"] = target
    return a


def damage(amount, target="hit", ignore_hit_delay=False, radius=None):
    a = {"type": "damage", "amount": amount, "target": target}
    if ignore_hit_delay:
        a["ignore_hit_delay"] = True
    if radius:
        a["radius"] = radius
    return a


def knock(strength, lift, target="hit", radius=None):
    a = {"type": "knockback", "strength": strength, "lift": lift, "target": target}
    if radius:
        a["radius"] = radius
    return a


def shape(kind, count, radius, target=None, particle=None, colour=None, height=None, size=None):
    a = {"type": "particle_shape", "shape": kind, "count": count, "radius": radius}
    if particle:
        a["particle"] = particle
    if colour:
        a["colour"] = colour
    if height is not None:
        a["height"] = height
    if size:
        a["size"] = size
    if target:
        a["target"] = target
    return a


def bolt(tag, rng, speed, particle, **extra):
    a = {"type": "launch_bolt", "tag": tag, "range": rng, "speed": speed, "particle": particle}
    a.update(extra)
    return a


def use(name, cooldown, actions, sneaking, charge=0):
    m = {"name": name, "trigger": "on_use", "cooldown_ticks": cooldown,
         "conditions": [{"type": "is_sneaking", "value": sneaking}], "actions": actions}
    if charge:
        m["charge_ticks"] = charge
    return m


def on_foot(m):
    """Only when not riding and not in water (the Oathblade's Quickdraw)."""
    m["conditions"].append({"type": "on_foot", "value": True})
    return m


def hit(tag, actions, wet=False):
    m = {"trigger": "on_bolt_hit", "tag": tag, "actions": actions}
    if wet:
        m["conditions"] = [{"type": "in_water", "value": True}]
    return m


def secs(ticks):
    s = ticks / 20
    return str(int(s)) if s == int(s) else str(s)


# ---- the weapons: one function per weapon, called once per tier (0, 1, 2) ----

def azrak(t):
    pierce = [2, 3, 3][t]
    slam = [4, 5, 6][t]
    slam_cd = [240, 220, 200][t]
    skin = [120, 140, 160][t]
    skin_cd = [1800, 1700, 1600][t]
    stone = "#7A6A55"
    return dict(
        key="azrak_pick", name="Runed War Pick", base="minecraft:stone_pickaxe", damage=[7, 8, 9][t], speed=1.0,
        lore=[f"Hit: {pierce} extra damage that ignores armour.",
              f"Right click: Ground Slam, {slam} damage and a heavy shove to everything within 3 blocks. {secs(slam_cd)}s recharge.",
              f"Sneak and right click: Stone Skin, tough but slow for {secs(skin)}s. {secs(skin_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_hit_entity", "actions": [damage(pierce, ignore_hit_delay=True),
                                                     sound("minecraft:block.anvil.hit", 0.6, 1.4),
                                                     shape("burst", 10, 0.6, "hit", particle="minecraft:crit")]},
            use("Ground Slam", slam_cd, [sound("minecraft:block.anvil.land", 0.8, 0.6),
                                         sound("minecraft:entity.generic.explode", 0.4, 1.5),
                                         damage(slam, "area", radius=3), knock(0.9, 0.45, "area", 3),
                                         shape("ring", 48, 3.0, colour=stone, size=1.6),
                                         shape("ring", 32, 2.0, particle="minecraft:cloud"),
                                         shape("ring", 20, 1.0, particle="minecraft:crit")], False),
            use("Stone Skin", skin_cd, [sound("minecraft:block.stone.place", 1.0, 0.6),
                                        effect("minecraft:resistance", skin, 1), effect("minecraft:slowness", skin, 0),
                                        shape("spiral", 40, 0.7, colour=stone, size=1.2)], True),
        ],
        upgrade=[None,
                 ["minecraft:diamond", "minecraft:diamond", "minecraft:iron_block", "minecraft:iron_block"],
                 ["minecraft:netherite_ingot", "minecraft:diamond", "minecraft:diamond"]],
        upgrade_text=[None, "2 diamonds and 2 iron blocks", "a netherite ingot and 2 diamonds"],
    )


def aurelia(t):
    smite = [1, 1, 2][t]
    weak = [40, 60, 80][t]
    counter = [6, 7, 8][t]
    parry_cd = [240, 220, 200][t]
    cut = [6, 7, 8][t]
    draw_cd = [240, 220, 200][t]
    silver, steel = "#C9CED8", "#F2F4F8"
    lore = [f"Hit: {smite} extra damage and Weakness for {secs(weak)}s.",
            "Right click: Mikiri, a 0.75s parry. The next melee hit or arrow is stopped.",
            f"  You counter for {counter} damage, Weakness and Slowness. {secs(parry_cd)}s recharge, half back on a parry.",
            "Sneak, hold right click and let go: Quickdraw, a dash of up to 10 blocks.",
            f"  It cuts everything you pass for up to {cut} damage; hold 1s for full. {secs(draw_cd)}s recharge."]
    answer = [damage(counter, ignore_hit_delay=True), effect("minecraft:weakness", 40, 0, "hit"),
              effect("minecraft:slowness", 40, 0, "hit"),
              sound("minecraft:entity.player.attack.crit", 1.0, 1.4),
              shape("arc", 18, 1.4, "hit", colour=steel, size=1.0)]
    if t == 2:
        lore.append("A parry also gives you and allies within 6 blocks Strength for 5s.")
        answer += [effect("minecraft:strength", 100, 0, "area", 6), shape("ring", 32, 6.0, colour=silver, size=1.2)]
    return dict(
        key="aurelia_oathblade", name="Oathblade", base="minecraft:iron_sword", damage=[5, 6, 6][t], speed=1.6,
        lore=lore,
        mechanics=[
            {"trigger": "on_hit_entity", "actions": [damage(smite, ignore_hit_delay=True),
                                                     effect("minecraft:weakness", weak, 0, "hit"),
                                                     sound("minecraft:entity.player.attack.sweep", 0.7, 1.3),
                                                     shape("arc", 14, 1.6, colour=silver, size=0.9)]},
            use("Mikiri", parry_cd, [{"type": "parry", "window": 15, "refund": 0.5},
                                     sound("minecraft:item.armor.equip_iron", 0.9, 1.5),
                                     shape("arc", 10, 1.0, colour=silver, size=0.8)], False),
            {"trigger": "on_parry", "actions": answer},
            on_foot(dict(use("Quickdraw", draw_cd, [sound("minecraft:item.trident.riptide_1", 0.8, 1.6),
                                            {"type": "dash_strike", "range": 10, "min_range": 3, "amount": cut,
                                             "min_damage": 0.5, "effect": "minecraft:slowness", "duration": 20,
                                             "colour": steel}], True, charge=20),
                 min_charge_ticks=5)),
        ],
        upgrade=[None,
                 ["minecraft:diamond", "minecraft:diamond", "minecraft:gold_block", "minecraft:gold_block"],
                 ["minecraft:netherite_ingot", "minecraft:emerald_block", "minecraft:emerald_block"]],
        upgrade_text=[None, "2 diamonds and 2 gold blocks", "a netherite ingot and 2 emerald blocks"],
    )


def azurius(t):
    wet = [1, 1, 2][t]
    throw = [5, 6, 7][t]
    throw_cd = [300, 280, 260][t]
    soak = [80, 100, 120][t]
    pull = [2, 3, 4][t]
    pull_cd = [600, 560, 520][t]
    sea = "#3FC2CF"
    return dict(
        key="azurius_trident", name="Tide Trident", base="minecraft:bone", damage=[7, 8, 9][t], speed=1.1,
        lore=[f"Hit: {wet} extra damage in water or rain.",
              f"Right click: throw the trident, {throw} damage. It flies out, hits, and returns, soaking what it strikes. {secs(throw_cd)}s recharge.",
              f"Sneak and right click: Undertow, {pull} damage to everything within 6 blocks, dragged toward you. {secs(pull_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_hit_entity", "actions": [sound("minecraft:entity.player.splash", 0.5, 1.6),
                                                     shape("burst", 8, 0.5, "hit", particle="minecraft:splash")]},
            {"trigger": "on_hit_entity", "conditions": [{"type": "in_water", "value": True}],
             "actions": [damage(wet, ignore_hit_delay=True), shape("ring", 14, 0.8, "hit", colour=sea)]},
            use("Trident Throw", throw_cd, [sound("minecraft:item.trident.throw", 1.0, 1.0),
                                            bolt("trident_out", [20, 22, 24][t], 1.4, "minecraft:bubble_pop",
                                                 returns=True, return_tag="trident_back", radius=0.8,
                                                 model="minecraft:trident", model_scale=1.1, colour=sea)], False),
            hit("trident_out", [damage(throw), effect("minecraft:slowness", soak, 1, "hit"),
                                effect("minecraft:mining_fatigue", soak, 0, "hit"),
                                sound("minecraft:item.trident.hit", 1.0, 1.0),
                                shape("spiral", 30, 0.7, "hit", particle="minecraft:splash")]),
            hit("trident_back", [damage([2, 3, 3][t]), effect("minecraft:slowness", soak // 2, 1, "hit"),
                                 sound("minecraft:item.trident.return", 0.8, 1.2),
                                 shape("burst", 10, 0.5, "hit", particle="minecraft:splash")]),
            use("Undertow", pull_cd, [sound("minecraft:entity.elder_guardian.curse", 0.5, 1.6),
                                      sound("minecraft:ambient.underwater.enter", 1.0, 0.8),
                                      damage(pull, "area", radius=6), knock(-0.9, 0.15, "area", 6),
                                      shape("ring", 48, 6.0, colour=sea, size=1.4),
                                      shape("ring", 32, 3.5, particle="minecraft:splash"),
                                      shape("spiral", 30, 1.0, particle="minecraft:bubble_pop")], True),
        ],
        upgrade=[None,
                 ["minecraft:prismarine_crystals", "minecraft:prismarine_crystals", "minecraft:prismarine_crystals",
                  "minecraft:prismarine_crystals", "minecraft:nautilus_shell"],
                 ["minecraft:heart_of_the_sea", "minecraft:nautilus_shell", "minecraft:nautilus_shell"]],
        upgrade_text=[None, "4 prismarine crystals and a nautilus shell", "a heart of the sea and 2 nautilus shells"],
    )


def faeloria(t):
    thorn = [4, 5, 6][t]
    venom = [60, 80, 100][t]
    burst = [6, 7, 8][t]
    reach = 3.0
    root = [40, 50, 60][t]
    burst_cd = [240, 220, 200][t]
    glam = [160, 200, 240][t]
    glam_cd = [1200, 1100, 1000][t]
    leaf = "#7CD35A"
    # the staff never touches its own kingdom: its bolts fly through the Fae and the burst passes them by
    own = "race:faeloria"

    def spare(a):
        a["exclude_permission"] = own
        return a

    return dict(
        key="faeloria_staff", name="Briarthorn Staff", base="minecraft:blaze_rod", damage=0, speed=0,
        lore=[f"Left click: Thorn Bolt, {thorn} damage and {secs(venom)}s of poison.",
              f"Right click: hold for a second, then let go (on Bedrock: tap once and keep it in hand). Bramble Burst bursts where it lands. {secs(burst_cd)}s recharge.",
              f"  Every enemy within {reach:g} blocks takes {burst} damage, is poisoned and rooted for {secs(root)}s. The Fae are spared.",
              f"Sneak and right click: Glamour, invisible and swift for {secs(glam)}s. {secs(glam_cd)}s recharge."],
        mechanics=[
            {"name": "Thorn Bolt", "trigger": "on_swing", "cooldown_ticks": 16,
             "actions": [sound("minecraft:block.sweet_berry_bush.pick_berries", 1.0, 1.4),
                         spare(bolt("thorn", 36, 1.4, "minecraft:composter", model="minecraft:pointed_dripstone",
                                    model_scale=0.5, colour=leaf))]},
            hit("thorn", [damage(thorn), effect("minecraft:poison", venom, 0, "hit"),
                          shape("burst", 10, 0.5, "hit", colour=leaf)]),
            use("Bramble Burst", burst_cd, [sound("minecraft:entity.evoker.cast_spell", 1.0, 1.3),
                                            shape("spiral", 24, 0.6, particle="minecraft:spore_blossom_air"),
                                            spare(bolt("bramble", 36, 1.6, "minecraft:spore_blossom_air", radius=0.9,
                                                       model="minecraft:spore_blossom", model_scale=0.8, spin=True,
                                                       colour=leaf, bursts=True))],
                False, charge=20),
            hit("bramble", [spare(damage(burst, "impact", radius=reach)),
                            spare(effect("minecraft:poison", [100, 120, 140][t], 0, "impact", reach)),
                            spare(effect("minecraft:slowness", root, 3, "impact", reach)),
                            sound_at("minecraft:block.sweet_berry_bush.place", 1.0, 0.6),
                            sound_at("minecraft:block.azalea_leaves.break", 1.0, 0.8),
                            shape("ring", 40, reach, "impact", colour="#3F7D33", size=1.4),
                            shape("ring", 24, reach * 0.5, "impact", particle="minecraft:composter"),
                            shape("burst", 30, 1.2, "impact", particle="minecraft:spore_blossom_air")]),
            use("Glamour", glam_cd, [sound("minecraft:entity.illusioner.mirror_move", 1.0, 1.2),
                                     effect("minecraft:invisibility", glam), effect("minecraft:speed", glam, 0),
                                     shape("burst", 30, 1.0, particle="minecraft:witch")], True),
        ],
        upgrade=[None,
                 ["minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:ender_pearl"],
                 ["minecraft:echo_shard", "minecraft:echo_shard", "minecraft:ender_eye"]],
        upgrade_text=[None, "3 amethyst shards and an ender pearl", "2 echo shards and an eye of ender"],
    )


def everdelle(t):
    spark = [5, 6, 7][t]
    blast = [5, 6, 7][t]
    reach = [3.0, 3.5, 4.0][t]
    lift = [30, 40, 50][t]
    mis_cd = [280, 240, 200][t]
    heal = [160, 200, 240][t]
    heal_cd = [1500, 1400, 1300][t]
    pink = "#F59AC4"
    # the wand never touches its own kingdom: its bolts fly through Fairies and the burst passes them by
    own = "race:everdelle"

    def spare(a):
        a["exclude_permission"] = own
        return a

    return dict(
        key="everdelle_wand", name="Everdelle Wand", base="minecraft:breeze_rod", damage=0, speed=0,
        lore=[f"Left click: a sparkle bolt, {spark} damage.",
              f"Right click: Mischief, a bolt that bursts where it lands. {secs(mis_cd)}s recharge.",
              f"  Every enemy within {reach:g} blocks takes {blast} damage and floats for {secs(lift)}s. Fairies are spared.",
              f"Sneak and right click: Healing Light, regeneration for players within 5 blocks for {secs(heal)}s. {secs(heal_cd)}s recharge."],
        mechanics=[
            {"name": "Sparkle Bolt", "trigger": "on_swing", "cooldown_ticks": 16, "actions": [sound("minecraft:entity.allay.item_thrown", 1.0, 1.9),
                                                                       sound("minecraft:entity.firework_rocket.twinkle", 0.6, 1.6),
                                                                       spare(bolt("sparkle", 36, 1.4, "minecraft:end_rod",
                                                                                  model="minecraft:nether_star", model_scale=0.35,
                                                                                  spin=True, colour=pink))]},
            hit("sparkle", [damage(spark), shape("burst", 12, 0.5, "hit", colour=pink)]),
            use("Mischief", mis_cd, [sound("minecraft:entity.evoker.cast_spell", 0.7, 1.8),
                                     spare(bolt("mischief", 36, 1.2, "minecraft:witch", model="minecraft:feather",
                                                model_scale=0.6, spin=True, colour="#B48CFF", bursts=True))], False),
            hit("mischief", [spare(damage(blast, "impact", radius=reach)),
                             spare(effect("minecraft:levitation", lift, 0, "impact", reach)),
                             sound_at("minecraft:entity.firework_rocket.large_blast", 1.0, 1.4),
                             sound_at("minecraft:entity.allay.death", 0.6, 1.8),
                             shape("ring", 40, reach, "impact", colour="#B48CFF", size=1.3),
                             shape("ring", 24, reach * 0.5, "impact", particle="minecraft:cloud"),
                             shape("burst", 30, 1.2, "impact", particle="minecraft:witch")]),
            use("Healing Light", heal_cd, [sound("minecraft:entity.player.levelup", 0.7, 1.6),
                                           effect("minecraft:regeneration", heal, [0, 1, 1][t], "area", 5),
                                           shape("ring", 40, 5.0, colour=pink, size=1.3),
                                           shape("ring", 16, 2.0, particle="minecraft:heart"),
                                           shape("column", 12, 2.0, particle="minecraft:end_rod")], True),
        ],
        upgrade=[None,
                 ["minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:glow_ink_sac"],
                 ["minecraft:echo_shard", "minecraft:amethyst_block", "minecraft:glow_ink_sac"]],
        upgrade_text=[None, "3 amethyst shards and a glow ink sac", "an echo shard, an amethyst block and a glow ink sac"],
    )


WEAPONS = [azrak, aurelia, azurius, faeloria, everdelle]
DEFAULT_NS = "kingdoms"
# The Fairies' wand used to be demo:fairy_wand, demo:fairy_wand_2 and demo:fairy_wand_3. Those ids stay as placeholders
# that turn into the new wand in a player's inventory (replaced_by), so nobody loses one.
MOVED = {"demo:fairy_wand": "kingdoms:everdelle_wand_1", "demo:fairy_wand_2": "kingdoms:everdelle_wand_2",
         "demo:fairy_wand_3": "kingdoms:everdelle_wand_3"}
NUMERALS = ["", " II", " III"]
# Each weapon belongs to one kingdom: only its members hold the race node Charter grants.
GATES = {"azrak_pick": ("azrak", "Dwarves of Azrak"), "aurelia_oathblade": ("aurelia", "Humans of Aurelia"),
         "azurius_trident": ("azurius", "Merfolk of Azurius"), "faeloria_staff": ("faeloria", "Fae of Faeloria"),
         "everdelle_wand": ("everdelle", "Fairies of Everdelle")}

# The rank 1 recipe of each weapon: a cheap starter, so a player who loses one can make another. Only players of the race
# get it in their recipe book (unlock_permission); 4 items or fewer, so it fits the inventory's 2 by 2 grid.
STARTERS = {
    "azrak_pick": (["minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:cobbled_deepslate"],
                   "2 iron ingots, a copper ingot and a cobbled deepslate"),
    "aurelia_oathblade": (["minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:leather"],
                          "2 iron ingots, a gold ingot and leather"),
    "azurius_trident": (["minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:kelp", "minecraft:ink_sac"],
                        "2 iron ingots, kelp and an ink sac"),
    "faeloria_staff": (["minecraft:stick", "minecraft:iron_ingot", "minecraft:vine", "minecraft:sweet_berries"],
                       "a stick, an iron ingot, a vine and sweet berries"),
    "everdelle_wand": (["minecraft:stick", "minecraft:iron_ingot", "minecraft:feather", "#minecraft:small_flowers"],
                       "a stick, an iron ingot, a feather and any small flower"),
}


def ident(key, tier):
    return f"{DEFAULT_NS}:{key}_{tier + 1}"


def ident_texture(item_id):
    ns, path = item_id.split(":")
    return f"{ns}:item/{path}"


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    count = 0
    for build in WEAPONS:
        for t in range(3):
            w = build(t)
            item_id = ident(w["key"], t)
            ns, path = item_id.split(":")
            lore = list(w["lore"])
            if t == 0:
                lore.append("Craft: " + STARTERS[w["key"]][1] + ". Fits the 2 by 2 grid.")
            if t < 2:
                nxt = build(t + 1)
                # more than 4 items (this weapon counts) will not fit the inventory's 2 by 2 grid, so say where to craft it
                where = " at a crafting table" if 1 + len(nxt["upgrade"][t + 1]) > 4 else ""
                lore.append("Upgrade: " + nxt["upgrade_text"][t + 1] + ", crafted with this" + where + ".")
            else:
                lore.append("Fully upgraded.")
            item = {"schema_version": 1, "id": item_id, "display_name": w["name"] + NUMERALS[t], "lore": lore,
                    "base_item": w["base"], "texture": ident_texture(item_id), "held_model": f"{ns}:item/held/{path}",
                    "stack_size": 1, "glint": True,
                    "rarity": RARITY[t]}
            if w["damage"]:
                item["attack_damage"] = w["damage"]
                item["attack_speed"] = w["speed"]
                # the vanilla melee enchantments (Sharpness, Smite, Bane of Arthropods, Knockback, Looting, Fire Aspect) and
                # Excellent Enchants' weapon ones (Decapitator, Ice Aspect, Thrifty, Thunder, Venom, Wisdom, Wither); the
                # second tag only matters when Excellent Enchants is installed, and is harmless when it is not
                item["enchantable"] = {"value": [10, 12, 15][t],
                                       "as": ["minecraft:enchantable/melee_weapon", "excellentenchants:swords_axes"]}
            else:
                # the magic weapons' own: Spellpower, Quickcast and Potency (foundry/src/main/resources/data/foundry)
                item["enchantable"] = {"value": [10, 12, 15][t], "as": ["foundry:enchantable/magic"]}
            node, people = GATES[w["key"]]
            item["handheld"] = True
            item["require_permission"] = "race:" + node
            item["locked_message"] = "Only the " + people + " can wield this."
            item["keep_on_death"] = True
            item["mechanics"] = w["mechanics"]
            write(os.path.join(ROOT, ns, "items", path + ".json"), item)
            count += 1
            if t == 0:
                write(os.path.join(ROOT, ns, "recipes", path + ".json"), {
                    "schema_version": 1, "id": item_id, "type": "shapeless", "unlock_permission": "race:" + node,
                    "ingredients": STARTERS[w["key"]][0], "result": item_id})
            if t > 0:
                prev = ident(w["key"], t - 1)
                recipe = {"schema_version": 1, "id": item_id, "type": "shapeless",
                          "ingredients": [prev] + w["upgrade"][t], "result": item_id}
                write(os.path.join(ROOT, ns, "recipes", path + ".json"), recipe)
    for old, new in MOVED.items():
        ns, path = old.split(":")
        write(os.path.join(ROOT, ns, "items", path + ".json"), {
            "schema_version": 1, "id": old, "display_name": "Everdelle Wand",
            "lore": ["This wand has moved; it becomes the Everdelle Wand as soon as you carry it."],
            "base_item": "minecraft:breeze_rod", "texture": ident_texture(new), "stack_size": 1, "rarity": "rare",
            "keep_on_death": True, "replaced_by": new})
        recipe = os.path.join(ROOT, ns, "recipes", path + ".json")
        if os.path.exists(recipe):
            os.remove(recipe)
    print(f"wrote {count} items, {count} recipes and {len(MOVED)} moved placeholders")


if __name__ == "__main__":
    main()
