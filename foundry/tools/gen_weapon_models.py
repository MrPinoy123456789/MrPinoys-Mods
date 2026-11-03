#!/usr/bin/env python3
"""The race weapons' 3D models: the hand, the ground, item frames and shelves draw these (each weapon's held_model),
and the flat icon (drawn here from the model) stays as the fallback and for Bedrock.

Each weapon is built standing up along y at the middle of the model (x 8, z 8), from the butt at y -3 to the tip at
y 19, then every element is turned 45 degrees about z through the middle, so it lies along the diagonal the way a
vanilla tool's picture does and the vanilla handheld poses (the models' parent, minecraft:item/handheld) hold it right.
Faces are painted per material by the trinket generator's bake (gen_trinket_models.py). Each tier is the same weapon,
richer: plain, then better metal and a gem, then the finest with inlays.

    python tools/gen_weapon_models.py
"""
import json
import os

import gen_trinket_models as g

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets", "kingdoms")
MODELS = os.path.join(ASSETS, "models", "item", "held")
TEXTURES = os.path.join(ASSETS, "textures", "item", "held")

g.MATERIALS.update({
    "haft": dict(base="#5a3a22", pattern="ringed", noise=8, bevel=20),
    "grip": dict(base="#3b2416", pattern="leather", noise=8, bevel=18),
    "grip_black": dict(base="#1c1a1e", pattern="leather", noise=6, bevel=16),
    "stone_head": dict(base="#7d7d78", pattern="brushed", noise=12, bevel=28),
    "steel": dict(base="#b9bec8", pattern="brushed", noise=6, bevel=36),
    "netherite": dict(base="#3d3639", pattern="brushed", noise=6, bevel=30),
    "copper_trim": dict(base="#b8693e", pattern="sheen", noise=6, bevel=30),
    "blade_dark": dict(base="#24222a", pattern="brushed", noise=5, bevel=24),
    "rune_blue": dict(base="#4fd7ff", pattern="sheen", noise=3, bevel=48),
    "rune_gold": dict(base="#ffb52e", pattern="sheen", noise=3, bevel=48),
    "ruby": dict(base="#d8283c", pattern="sheen", noise=3, bevel=48),
    "prismarine": dict(base="#5fa79a", pattern="ridges", noise=6, bevel=26),
    "prismarine_dark": dict(base="#3b6f69", pattern="ridges", noise=6, bevel=24),
    "sea_gem": dict(base="#2f7fe0", pattern="sheen", noise=3, bevel=48),
    "briar": dict(base="#4a3524", pattern="ringed", noise=10, bevel=18),
    "thorn": dict(base="#2f5a22", pattern="plain", noise=6, bevel=10),
    "bud": dict(base="#6fbf3f", pattern="leaf", noise=6, bevel=14),
    "amethyst": dict(base="#a875e0", pattern="sheen", noise=3, bevel=48),
    "bloom": dict(base="#f07ab8", pattern="flower", noise=4, bevel=0),
    "ivory": dict(base="#efe6d2", pattern="sheen", noise=4, bevel=20),
    "ribbon": dict(base="#f59ac4", pattern="sheen", noise=4, bevel=16),
    "crystal": dict(base="#ffc4e6", pattern="sheen", noise=3, bevel=48),
    "crystal_gold": dict(base="#ffe07a", pattern="sheen", noise=3, bevel=48),
    "wing": dict(base="#e6f4ff", pattern="plain", noise=3, bevel=0, alpha=170),
    "katana_steel": dict(base="#8e95a3", pattern="brushed", noise=5, bevel=30),
    "hamon": dict(base="#eef3fa", pattern="sheen", noise=3, bevel=40),
    "silk_red": dict(base="#8e1b26", pattern="leather", noise=6, bevel=16),
    "ray_skin": dict(base="#e8e2d2", pattern="dot", noise=6, bevel=14),
})


def box(x0, x1, y0, y1, mat, d=0.8, z=8.0):
    """A cube across x from x0 to x1, up y from y0 to y1, d deep on each side of z."""
    return g.part((x0, y0, z - d), (x1, y1, z + d), mat)


# ---- the weapons, standing up (butt at y -3, tip at y 19), one function per weapon, tier 0 to 2 ----

def war_pick(t):
    head = ["stone_head", "steel", "netherite"][t]
    trim = ["copper_trim", "iron_dark", "gold"][t]
    parts = [
        box(7.2, 8.8, -3, 12, "haft", 0.8),
        box(6.9, 9.1, -1.5, 0.5, "grip", 1.1), box(6.9, 9.1, 2, 4, "grip", 1.1),
        box(6.8, 9.2, -3.6, -2.6, trim, 1.2),
        box(6.6, 9.4, 11, 12.8, trim, 1.4),
        box(6.4, 9.6, 12.6, 16.4, head, 1.5),                       # the eye of the head
        box(3.6, 6.4, 13.0, 16.0, head, 1.2), box(1.2, 3.6, 13.6, 15.4, head, 0.9),   # the spike, tapering left
        box(0.4, 1.2, 14.0, 15.0, head, 0.6),
        box(9.6, 13.4, 12.6, 16.4, head, 1.4), box(13.4, 14.4, 12.2, 16.8, trim, 1.6),  # the hammer and its face
    ]
    if t >= 1:
        parts.append(box(7.4, 8.6, 13.8, 15.2, ["", "rune_blue", "rune_gold"][t], 1.7))
    if t == 2:
        parts += [box(2.2, 3.2, 15.4, 15.8, "gold", 0.92), box(10.4, 12.6, 16.4, 16.8, "gold", 1.42)]
    return parts


def oathblade(t):
    """A katana: a wrapped grip, a round guard, and a long curved single edged blade whose tip sweeps back toward the
    spine (+x), its temper line (the hamon) bright along the edge (-x), ending in an angled point. Rank 1 plain steel and
    black silk over white ray skin; rank 2 a gold guard and crimson silk; rank 3 a blackened blade with a glowing edge
    and gold fittings."""
    blade = ["katana_steel", "katana_steel", "blade_dark"][t]
    hamon = ["hamon", "hamon", "rune_blue"][t]
    fitting = ["iron_dark", "gold", "gold"][t]
    silk = ["grip_black", "silk_red", "grip_black"][t]
    parts = [
        box(7.3, 8.7, -2.6, 2.4, "ray_skin", 0.6),                                      # the grip under the wrap
        box(7.05, 8.95, -3.4, -2.6, fitting, 0.75),                                     # the pommel cap
        box(7.15, 8.85, 2.0, 2.6, fitting, 0.68),                                       # the collar under the guard
        box(6.0, 10.0, 2.6, 3.2, fitting, 1.6), box(6.5, 9.5, 2.6, 3.2, fitting, 2.0),  # the round guard
        box(7.2, 8.85, 3.2, 4.0, "copper_trim" if t == 0 else "gold", 0.36),            # the blade collar
    ]
    for y in (-2.2, -1.0, 0.2, 1.4):                                                    # the silk wrap, crossing
        parts.append(box(7.25, 8.75, y, y + 0.7, silk, 0.66))
    # the blade: set back toward the spine a quarter pixel at a time along a gentle curve (a run at one setback is one
    # box, so the steps stay few and small), then over the last three the edge sweeps up to a straight spine to make the point
    y, top, n = 4.0, 18.6, 16
    runs = []
    for i in range(n):
        y1 = top if i == n - 1 else round(y + (top - 4.0) / n, 2)
        u = (i + 0.5) / n
        spine = 8.8 + round(1.0 * min(u, (n - 3.5) / n) ** 2 * 4) / 4   # straight along the spine through the point
        width = 1.5 if i < n - 3 else [1.1, 0.75, 0.4][i - (n - 3)]
        if runs and runs[-1][2] == spine and runs[-1][3] == width:
            runs[-1][1] = y1
        else:
            runs.append([y, y1, spine, width])
        y = y1
    for y0, y1, spine, width in runs:
        x0 = spine - width
        parts.append(box(x0, spine, y0, y1, blade, 0.22))
        parts.append(box(x0 - 0.01, x0 + min(0.45, width / 2), y0, y1, hamon, 0.23))  # the bright edge
    parts.append(box(spine - 0.25, spine, top, top + 0.5, blade, 0.15))                # the very point
    if t >= 1:
        parts += [box(7.1, 7.35, -1.6, -1.1, fitting, 0.7), box(8.65, 8.9, 0.6, 1.1, fitting, 0.7)]   # grip ornaments
    if t == 2:
        parts += [box(7.6, 8.4, -4.0, -3.4, "ruby", 0.4),                              # a red cord knot at the pommel
                  box(7.75, 8.25, -6.0, -4.0, "silk_red", 0.18)]
    return parts


def tide_trident(t):
    prong = ["prismarine", "prismarine", "gold"][t]
    band = ["copper_trim", "gold", "gold"][t]
    parts = [
        box(7.4, 8.6, -10, 13, "prismarine_dark", 0.6),
        box(7.1, 8.9, -6, -5, band, 0.9), box(7.1, 8.9, 1.5, 2.5, band, 0.9), box(7.1, 8.9, 7, 8, band, 0.9),
        box(4.4, 11.6, 13, 14.2, band, 0.8),
        box(7.3, 8.7, 14.2, 19, prong, 0.7), box(7.6, 8.4, 19, 20.6, prong, 0.4),
        box(4.4, 5.6, 14.2, 17.6, prong, 0.6), box(4.6, 5.4, 17.6, 18.8, prong, 0.35), box(5.6, 6.4, 16.4, 17.2, prong, 0.4),
        box(10.4, 11.6, 14.2, 17.6, prong, 0.6), box(10.6, 11.4, 17.6, 18.8, prong, 0.35), box(9.6, 10.4, 16.4, 17.2, prong, 0.4),
    ]
    if t >= 1:
        parts.append(box(7.3, 8.7, 12.8, 14.4, ["", "pearl", "sea_gem"][t], 1.0))
    if t == 2:
        parts.append(box(7.2, 8.8, -10.6, -9.6, "gold", 0.8))
    return parts


def briar_staff(t):
    top = ["bud", "amethyst", "bloom"][t]
    parts = [
        box(7.3, 8.7, -3, 2, "briar", 0.7), box(7.6, 9.0, 2, 7, "briar", 0.7), box(7.1, 8.5, 7, 12, "briar", 0.7),
        box(7.5, 8.9, 12, 15, "briar", 0.7),
        box(6.5, 7.3, 0.5, 1.1, "thorn", 0.3), box(9.0, 9.8, 4.0, 4.6, "thorn", 0.3), box(6.3, 7.1, 9.0, 9.6, "thorn", 0.3),
        box(8.9, 9.7, 13.0, 13.6, "thorn", 0.3),
        box(5.4, 7.6, 14.4, 15.6, "briar", 0.5), box(5.0, 6.2, 15.6, 18.6, "briar", 0.5),     # the left fork
        box(8.8, 10.8, 14.8, 16.0, "briar", 0.5), box(10.0, 11.2, 16.0, 18.2, "briar", 0.5),   # the right fork
        box(6.6, 9.4, 15.8, 18.4, top, 1.1),                                                  # held in the fork
        box(4.2, 5.4, 18.0, 19.0, "bud", 0.6), box(10.8, 12.0, 17.6, 18.6, "bud", 0.6),       # leaves at the tips
    ]
    if t == 2:
        parts += [box(7.4, 8.6, 18.4, 19.6, "flower_centre", 0.7), box(5.8, 6.6, 17.2, 18.2, "bloom", 0.5)]
    return parts


def everdelle_wand(t):
    star = ["crystal", "crystal", "crystal_gold"][t]
    parts = [
        box(7.5, 8.5, -2, 12.5, "ivory", 0.5),
        box(7.2, 8.8, -2.6, 2, "silver", 0.8),
        box(7.0, 9.0, 2, 2.6, "silver", 0.9),
        box(8.5, 9.1, 4, 5, "ribbon", 0.55), box(6.9, 7.5, 6.5, 7.5, "ribbon", 0.55), box(8.5, 9.1, 9, 10, "ribbon", 0.55),
        box(7.2, 8.8, 12.4, 17.8, star, 0.6), box(5.2, 10.8, 14.4, 15.8, star, 0.6),           # the star's cross
        box(7.6, 8.4, 17.8, 18.8, star, 0.4), box(4.4, 5.2, 14.8, 15.4, star, 0.35), box(10.8, 11.6, 14.8, 15.4, star, 0.35),
        box(6.2, 7.2, 13.4, 14.4, star, 0.45), box(8.8, 9.8, 13.4, 14.4, star, 0.45),
        box(6.2, 7.2, 15.8, 16.8, star, 0.45), box(8.8, 9.8, 15.8, 16.8, star, 0.45),
    ]
    if t >= 1:
        parts += [box(3.0, 6.0, 15.6, 18.6, "wing", 0.05), box(10.0, 13.0, 15.6, 18.6, "wing", 0.05)]
    if t == 2:
        parts += [box(7.5, 8.5, 14.6, 15.6, "rune_gold", 0.75), box(7.0, 9.0, 12.0, 12.6, "gold", 0.7)]
    return parts


WEAPONS = {"azrak_pick": war_pick, "aurelia_oathblade": oathblade, "azurius_trident": tide_trident,
           "faeloria_staff": briar_staff, "everdelle_wand": everdelle_wand}


def model(name, parts):
    elements, texture = g.bake(name, parts, density=4)
    for el in elements:
        el["rotation"] = {"origin": [8, 8, 8], "axis": "z", "angle": -45}
    return {"parent": "minecraft:item/handheld",
            "textures": {"0": f"kingdoms:item/held/{name}", "particle": f"kingdoms:item/held/{name}"},
            "elements": elements}, texture


ICONS = os.path.join(ASSETS, "textures", "item")


def icon(path):
    """The 16 by 16 inventory icon (also Bedrock's), drawn from the 3D model seen as the game's inventory shows it (from
    +z, handle bottom left): rendered large, the background cut away, shrunk, and its edge made hard."""
    m = pm.load_model(path, pm.ASSETS)
    im = pm.render(m, pm.ASSETS, 180, 0, size=256, scale=11.0).convert("RGBA")
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y][:3] == (38, 40, 48):
                px[x, y] = (0, 0, 0, 0)
    im = im.crop(im.getbbox())
    side = max(im.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    out = square.resize((16, 16), Image.BOX)
    out.putalpha(out.getchannel("A").point(lambda v: 255 if v > 100 else 0))
    return out


def main():
    os.makedirs(MODELS, exist_ok=True)
    os.makedirs(TEXTURES, exist_ok=True)
    for key, build in WEAPONS.items():
        for t in range(3):
            name = f"{key}_{t + 1}"
            m, tex = model(name, build(t))
            with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
                json.dump(m, f, indent=1)
                f.write("\n")
            tex.save(os.path.join(TEXTURES, name + ".png"))
            print(name, len(m["elements"]), "elements,", f"{tex.width}x{tex.height}")


if __name__ == "__main__":
    main()
