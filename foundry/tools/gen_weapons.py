#!/usr/bin/env python3
"""Writes the kingdom weapons and their upgrade recipes as Foundry content.

Every weapon comes in three tiers; each tier is its own item, and a shapeless recipe turns one tier into the next.
Run it from the foundry folder to rewrite examples/content/kingdoms and the fairy wand tiers; change the tables below
to rebalance, then run it again. The output is plain Foundry JSON, so nothing here is needed at runtime.

    python tools/gen_weapons.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "examples", "content")
RARITY = ["uncommon", "rare", "epic"]
RARITY_OVERRIDE = {"fairy_wand": ["rare", "epic", "epic"]}   # the wand was rare before it had tiers


def sound(name, volume=1.0, pitch=1.0):
    return {"type": "play_sound", "sound": name, "volume": volume, "pitch": pitch}


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


def damage(amount, target="hit", ignore_hit_delay=False):
    a = {"type": "damage", "amount": amount, "target": target}
    if ignore_hit_delay:
        a["ignore_hit_delay"] = True
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


def hit(tag, actions):
    return {"trigger": "on_bolt_hit", "tag": tag, "actions": actions}


def secs(ticks):
    s = ticks / 20
    return str(int(s)) if s == int(s) else str(s)


# ---- the weapons: one function per weapon, called once per tier (0, 1, 2) ----

def azrak(t):
    pierce = [2, 3, 4][t]
    rock_cd = [240, 220, 200][t]
    skin = [120, 140, 160][t]
    skin_cd = [1800, 1700, 1600][t]
    return dict(
        key="azrak_pick", name="Runed War Pick", base="minecraft:stone_pickaxe", damage=[6, 7, 8][t], speed=[1.2, 1.25, 1.3][t],
        lore=[f"Hit: {pierce} extra damage that ignores armour.",
              f"Right click: Rockfall, a heavy blast at close range. {secs(rock_cd)}s recharge.",
              f"Sneak and right click: Stone Skin, tough but slow for {secs(skin)}s. {secs(skin_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_hit_entity", "actions": [damage(pierce, ignore_hit_delay=True),
                                                     sound("minecraft:block.anvil.hit", 0.6, 1.4)]},
            use("Rockfall", rock_cd, [sound("minecraft:block.stone.break", 1.0, 0.7),
                                      bolt("rockfall", 6, 1.0, "minecraft:crit", radius=0.7)], False),
            hit("rockfall", [damage([5, 6, 7][t]), effect("minecraft:slowness", [40, 60, 80][t], 1, "hit"),
                             particles("minecraft:crit", 14, 0.4, "hit")]),
            use("Stone Skin", skin_cd, [sound("minecraft:block.stone.place", 1.0, 0.6),
                                        effect("minecraft:resistance", skin, 1), effect("minecraft:slowness", skin, 0),
                                        particles("minecraft:ash", 24, 0.6)], True),
        ],
        upgrade=[None,
                 ["minecraft:diamond", "minecraft:diamond", "minecraft:iron_block", "minecraft:iron_block"],
                 ["minecraft:netherite_ingot", "minecraft:diamond", "minecraft:diamond"]],
        upgrade_text=[None, "2 diamonds and 2 iron blocks", "a netherite ingot and 2 diamonds"],
    )


def aurelia(t):
    smite = [2, 3, 4][t]
    weak = [60, 80, 100][t]
    lance_cd = [300, 280, 260][t]
    rally = [160, 200, 240][t]
    rally_cd = [1200, 1100, 1000][t]
    return dict(
        key="aurelia_oathblade", name="Oathblade", base="minecraft:iron_sword", damage=[7, 8, 9][t], speed=1.6,
        lore=[f"Hit: {smite} extra damage and Weakness for {secs(weak)}s.",
              f"Right click: Holy Lance, a bolt of light. {secs(lance_cd)}s recharge.",
              f"Sneak and right click: Rally, Strength and Speed for you and allies within 6 blocks for {secs(rally)}s. {secs(rally_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_hit_entity", "actions": [damage(smite, ignore_hit_delay=True),
                                                     effect("minecraft:weakness", weak, 0, "hit"),
                                                     sound("minecraft:entity.player.attack.crit", 0.8, 1.2)]},
            use("Holy Lance", lance_cd, [sound("minecraft:entity.blaze.shoot", 0.8, 1.6),
                                         bolt("holy_lance", [12, 14, 16][t], 1.4, "minecraft:end_rod")], False),
            hit("holy_lance", [damage([4, 5, 6][t]), effect("minecraft:weakness", [80, 100, 120][t], 0, "hit"),
                               particles("minecraft:end_rod", 16, 0.4, "hit")]),
            use("Rally", rally_cd, [sound("minecraft:item.goat_horn.sound.0", 1.0, 1.0),
                                    effect("minecraft:strength", rally, 0, "area", 6),
                                    effect("minecraft:speed", rally, 0, "area", 6),
                                    particles("minecraft:happy_villager", 30, 2.0)], True),
        ],
        upgrade=[None,
                 ["minecraft:diamond", "minecraft:diamond", "minecraft:gold_block", "minecraft:gold_block"],
                 ["minecraft:netherite_ingot", "minecraft:emerald_block", "minecraft:emerald_block"]],
        upgrade_text=[None, "2 diamonds and 2 gold blocks", "a netherite ingot and 2 emerald blocks"],
    )


def azurius(t):
    jet = [3, 4, 5][t]
    throw_cd = [300, 280, 260][t]
    soak = [80, 100, 120][t]
    mist = [400, 500, 600][t]
    mist_cd = [1200, 1100, 1000][t]
    return dict(
        key="azurius_trident", name="Tide Trident", base="minecraft:stick", damage=[5, 6, 7][t], speed=1.1,
        lore=[f"Left click: Water Jet, {jet} damage at range.",
              f"Right click: throw the trident. It flies out, hits, and returns, soaking what it strikes. {secs(throw_cd)}s recharge.",
              f"Sneak and right click: Riptide Mist, water breathing and speed in water for {secs(mist)}s. {secs(mist_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_swing", "cooldown_ticks": 13, "actions": [sound("minecraft:entity.dolphin.splash", 0.8, 1.5),
                                                                       bolt("water_jet", 25, 1.3, "minecraft:bubble")]},
            hit("water_jet", [damage(jet), particles("minecraft:splash", 14, 0.4, "hit")]),
            use("Trident Throw", throw_cd, [sound("minecraft:item.trident.throw", 1.0, 1.0),
                                            bolt("trident_out", [20, 22, 24][t], 1.4, "minecraft:bubble_pop",
                                                 returns=True, return_tag="trident_back", radius=0.8)], False),
            hit("trident_out", [damage([4, 5, 6][t]), effect("minecraft:slowness", soak, 1, "hit"),
                                effect("minecraft:mining_fatigue", soak, 0, "hit"),
                                sound("minecraft:item.trident.hit", 1.0, 1.0), particles("minecraft:splash", 20, 0.5, "hit")]),
            hit("trident_back", [damage([2, 3, 3][t]), effect("minecraft:slowness", soak // 2, 1, "hit"),
                                 sound("minecraft:item.trident.return", 0.8, 1.2), particles("minecraft:splash", 10, 0.4, "hit")]),
            use("Riptide Mist", mist_cd, [sound("minecraft:entity.dolphin.play", 1.0, 1.0),
                                          effect("minecraft:water_breathing", mist), effect("minecraft:dolphins_grace", mist),
                                          particles("minecraft:bubble", 30, 0.8)], True),
        ],
        upgrade=[None,
                 ["minecraft:prismarine_crystals", "minecraft:prismarine_crystals", "minecraft:prismarine_crystals",
                  "minecraft:prismarine_crystals", "minecraft:nautilus_shell"],
                 ["minecraft:heart_of_the_sea", "minecraft:nautilus_shell", "minecraft:nautilus_shell"]],
        upgrade_text=[None, "4 prismarine crystals and a nautilus shell", "a heart of the sea and 2 nautilus shells"],
    )


def faeloria(t):
    thorn = [2, 3, 3][t]
    burst_cd = [300, 280, 260][t]
    glam = [160, 200, 240][t]
    glam_cd = [1800, 1700, 1600][t]
    return dict(
        key="faeloria_staff", name="Briarthorn Staff", base="minecraft:stick", damage=0, speed=0,
        lore=[f"Left click: Thorn Bolt, {thorn} damage and poison.",
              f"Right click: hold for a second, then let go. Bramble Burst. {secs(burst_cd)}s recharge.",
              f"Sneak and right click: Glamour, invisible and swift for {secs(glam)}s. {secs(glam_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_swing", "cooldown_ticks": 13, "actions": [sound("minecraft:block.sweet_berry_bush.pick_berries", 1.0, 1.4),
                                                                       bolt("thorn", [30, 32, 34][t], 1.3, "minecraft:composter")]},
            hit("thorn", [damage(thorn), effect("minecraft:poison", [60, 80, 100][t], 0, "hit"),
                          particles("minecraft:happy_villager", 10, 0.4, "hit")]),
            use("Bramble Burst", burst_cd, [sound("minecraft:entity.evoker.cast_spell", 1.0, 1.3),
                                            particles("minecraft:spore_blossom_air", 24, 0.6),
                                            bolt("bramble", 30, 1.6, "minecraft:spore_blossom_air", radius=0.9)], False, charge=20),
            hit("bramble", [damage([6, 7, 8][t]), effect("minecraft:poison", [100, 120, 140][t], 0, "hit"),
                            effect("minecraft:slowness", 40, 0, "hit"), particles("minecraft:composter", 24, 0.5, "hit")]),
            use("Glamour", glam_cd, [sound("minecraft:entity.illusioner.mirror_move", 1.0, 1.2),
                                     effect("minecraft:invisibility", glam), effect("minecraft:speed", glam, 0),
                                     particles("minecraft:witch", 24, 0.6)], True),
        ],
        upgrade=[None,
                 ["minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:ender_pearl"],
                 ["minecraft:echo_shard", "minecraft:echo_shard", "minecraft:ender_eye"]],
        upgrade_text=[None, "3 amethyst shards and an ender pearl", "2 echo shards and an eye of ender"],
    )


def fairy(t):
    heal_cd = [1500, 1400, 1300][t]
    mis_cd = [400, 360, 320][t]
    return dict(
        key="fairy_wand", name="Fairy Wand", base="minecraft:stick", damage=0, speed=0,
        lore=[f"Left click: a sparkle bolt, {[3, 4, 5][t]} damage.",
              f"Right click: Mischief, a bolt that hurts, blinds and slows. {secs(mis_cd)}s recharge.",
              f"Sneak and right click: Healing Light, regeneration for players nearby. {secs(heal_cd)}s recharge."],
        mechanics=[
            {"trigger": "on_swing", "cooldown_ticks": 13, "actions": [sound("minecraft:entity.allay.item_thrown", 1.0, 1.9),
                                                                       sound("minecraft:entity.firework_rocket.launch", 0.35, 2.0),
                                                                       sound("minecraft:entity.firework_rocket.twinkle", 0.7, 1.6),
                                                                       bolt("sparkle", 40, 1.2, "minecraft:end_rod")]},
            hit("sparkle", [damage([3, 4, 5][t]), particles("minecraft:end_rod", 12, 0.3, "hit")]),
            use("Mischief", mis_cd, [sound("minecraft:entity.evoker.cast_spell", 0.7, 1.8),
                                     bolt("mischief", 6, 0.8, "minecraft:witch")], False),
            hit("mischief", [damage([2, 3, 4][t]), effect("minecraft:blindness", [60, 80, 100][t], 0, "hit"),
                             effect("minecraft:slowness", [60, 80, 100][t], 0, "hit"), particles("minecraft:witch", 16, 0.4, "hit")]),
            use("Healing Light", heal_cd, [sound("minecraft:entity.player.levelup", 0.7, 1.6),
                                           effect("minecraft:regeneration", 120, [0, 1, 1][t], "area", 5),
                                           particles("minecraft:happy_villager", 30, 2.0),
                                           particles("minecraft:end_rod", 20, 1.5)], True),
        ],
        upgrade=[None,
                 ["minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:amethyst_shard", "minecraft:glow_ink_sac"],
                 ["minecraft:echo_shard", "minecraft:amethyst_block", "minecraft:glow_ink_sac"]],
        upgrade_text=[None, "3 amethyst shards and a glow ink sac", "an echo shard, an amethyst block and a glow ink sac"],
    )


WEAPONS = [azrak, aurelia, azurius, faeloria, fairy]
NAMESPACE = {"fairy_wand": "demo"}      # the wand already lives under demo; new weapons get their own namespace
DEFAULT_NS = "kingdoms"
NUMERALS = ["", " II", " III"]


def ident(key, tier):
    ns = NAMESPACE.get(key, DEFAULT_NS)
    return f"{ns}:{key}" if tier == 0 and ns == "demo" else f"{ns}:{key}_{tier + 1}"


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
            if t < 2:
                nxt = build(t + 1)
                lore.append("Upgrade: " + nxt["upgrade_text"][t + 1] + ", crafted with this.")
            else:
                lore.append("Fully upgraded.")
            item = {"schema_version": 1, "id": item_id, "display_name": w["name"] + NUMERALS[t], "lore": lore,
                    "base_item": w["base"], "stack_size": 1, "glint": True,
                    "rarity": RARITY_OVERRIDE.get(w["key"], RARITY)[t]}
            if w["damage"]:
                item["attack_damage"] = w["damage"]
                item["attack_speed"] = w["speed"]
            item["mechanics"] = w["mechanics"]
            write(os.path.join(ROOT, ns, "items", path + ".json"), item)
            count += 1
            if t > 0:
                prev = ident(w["key"], t - 1)
                recipe = {"schema_version": 1, "id": item_id, "type": "shapeless",
                          "ingredients": [prev] + w["upgrade"][t], "result": item_id}
                write(os.path.join(ROOT, ns, "recipes", path + ".json"), recipe)
    print(f"wrote {count} items and {count - len(WEAPONS)} recipes")


if __name__ == "__main__":
    main()
