"""Builds the 3D worn models and their textures for the Kingdoms race trinkets.

Run from the foundry folder: python tools/gen_trinket_models.py. It writes, under examples/assets/kingdoms:
  models/item/worn/<name>.json   vanilla item models (cuboid elements, per face UVs, rotations, display transforms)
  textures/item/worn/<name>.png  one texture per item (see below); it must sit under textures/item/ because the
                                 client only stitches item model textures from there (the items atlas), and a
                                 texture anywhere else draws pink and black
Copy both folders into config/foundry/assets/kingdoms on the server, then restart or run /foundry pack build. Each
item's content file names its model with "worn_model", and Foundry draws it wherever the item is shown (worn display,
helmet slot, inventory, hand); the flat texture stays as the fallback and as the Bedrock icon. Run tools/check_models.py
afterwards to validate the result and write a preview picture.

Textures: the cape, pauldron, circlet and gills are skinned from each item's own flat sprite
(textures/item/<name>.png). The atlas is 48 by 32 pixels: the sprite itself at the top left, then flat swatches of the
sprite's own colours (base, trim, dark, accent, light). Faces that show the item's picture use a crop of the sprite;
the rest use a swatch, so the model reads like the sprite and every colour comes from it. The fairy wings use a
painted 32 by 64 texture (paint_wings): wing silhouettes with see through membrane, rim and veins.

Look at the result with tools/preview_model.py (it renders the models with their textures from four sides).

Model space is the usual 0 to 16 cube, front is the north face (low z).
"""
import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets", "kingdoms")
MODELS = os.path.join(ASSETS, "models", "item", "worn")
TEXTURES = os.path.join(ASSETS, "textures", "item", "worn")
SPRITES = os.path.join(ASSETS, "textures", "item")

ATLAS_W, ATLAS_H = 48, 32


def uv(x0, y0, x1, y1, w=ATLAS_W, h=ATLAS_H):
    """A pixel rectangle of the texture as a model UV rectangle (0 to 16)."""
    return [round(x0 * 16 / w, 4), round(y0 * 16 / h, 4), round(x1 * 16 / w, 4), round(y1 * 16 / h, 4)]


# Swatch cells in the atlas (each 16 by 16 pixels, the sprite is the cell at the top left).
SPRITE = (0, 0, 16, 16)
CELLS = {"A": (16, 0), "B": (32, 0), "C": (0, 16), "D": (16, 16), "L": (32, 16)}  # base, trim, dark, accent, light
FACES = ["north", "east", "south", "west", "up", "down"]

DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"translation": [0, 3, 0], "scale": [0.45, 0.45, 0.45]},
    "fixed": {"rotation": [0, 180, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.4, 0.4, 0.4]},
    "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.4, 0.4, 0.4]},
    "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [1, 2, 0], "scale": [0.45, 0.45, 0.45]},
    "firstperson_lefthand": {"rotation": [0, 90, 0], "translation": [-1, 2, 0], "scale": [0.45, 0.45, 0.45]},
}


def swatch(key):
    x, y = CELLS[key]
    return uv(x + 2, y + 2, x + 14, y + 14)


def sprite(x0, y0, x1, y1):
    """A crop of the item's sprite, in sprite pixels (0 to 16)."""
    return uv(x0, y0, x1, y1)


def cube(frm, to, region="A", rot=None, faces=None, only=None):
    """One cuboid. region is a swatch key for every face; faces maps a face to a swatch key or an explicit uv list."""
    out = {}
    for face in FACES:
        if only and face not in only:
            continue
        r = (faces or {}).get(face, region)
        out[face] = {"uv": list(r) if isinstance(r, (list, tuple)) else swatch(r), "texture": "#0"}
    el = {"from": list(frm), "to": list(to), "faces": out}
    if rot:
        axis, angle, origin = rot
        el["rotation"] = {"origin": list(origin), "axis": axis, "angle": angle}
    return el


def mirror(el):
    """The same element reflected across the x = 8 plane (a right side part made from a left one)."""
    f, t = el["from"], el["to"]
    out = {"from": [16 - t[0], f[1], f[2]], "to": [16 - f[0], t[1], t[2]], "faces": {}}
    swap = {"east": "west", "west": "east"}
    for name, face in el["faces"].items():
        out["faces"][swap.get(name, name)] = face
    if "rotation" in el:
        r = el["rotation"]
        o = list(r["origin"])
        o[0] = 16 - o[0]
        angle = -r["angle"] if r["axis"] in ("z", "y") else r["angle"]
        out["rotation"] = {"origin": o, "axis": r["axis"], "angle": angle}
    return out


def pauldron():
    """Overlapping lames over the shoulder: a cap on top, then plates stepping down and out over the arm (+x), each a
    little wider front to back than the one above so the edges show, with bronze trim along the lower edges."""
    pic = sprite(3, 4, 13, 13)  # the plate with its two straps, on the cap's top
    return [
        cube((3, 11, 3), (12, 13.5, 13), "A", faces={"up": pic}),
        cube((9.5, 9, 2.5), (13.5, 11.8, 13.5), "L"),
        cube((9.5, 8.6, 2.4), (13.6, 9.2, 13.6), "B"),
        cube((12, 6, 2), (15, 9.4, 14), "A"),
        cube((12, 5.6, 1.9), (15.1, 6.2, 14.1), "B"),
        cube((13.5, 2.5, 1.5), (16, 6.4, 14.5), "L"),
        cube((13.5, 2.1, 1.4), (16.1, 2.7, 14.6), "B"),
        cube((1.5, 9.5, 3.5), (3.5, 12.5, 12.5), "C"),
        cube((4.5, 13.5, 4.5), (10.5, 14.4, 11.5), "A", faces={"up": pic}),
        cube((5, 13.5, 5.5), (6.5, 14.3, 7), "D"),
        cube((5, 13.5, 9), (6.5, 14.3, 10.5), "D"),
    ]


def cape():
    upper = sprite(3, 2, 13, 8)
    lower = sprite(3, 8, 13, 14)
    return [
        cube((3, 13, 6), (13, 15.5, 10), "B"),
        cube((7, 12, 5.4), (9, 14.2, 6.2), "D"),
        cube((3, 6, 7.4), (13, 13, 8.6), "A", faces={"north": upper}),
        cube((3, 0, 6.8), (13, 6.2, 8.0), "A", rot=("x", 22.5, (8, 6, 8)), faces={"north": lower}),
        cube((3, 0, 7.4), (4.2, 12, 9.4), "C"),
        cube((11.8, 0, 7.4), (13, 12, 9.4), "C"),
        cube((3, 6, 8.6), (13, 6.6, 9.4), "B"),
    ]


def circlet():
    band = sprite(2, 10, 14, 13)  # the gold band with its green gem
    e = [
        cube((0.5, 9, 0.5), (15.5, 11.5, 2), "A", faces={"north": band}),
        cube((0.5, 9, 14), (15.5, 11.5, 15.5), "A"),
        cube((0.5, 9, 2), (2, 11.5, 14), "A"),
        cube((14, 9, 2), (15.5, 11.5, 14), "A"),
        cube((4, 10, 0.2), (12, 11, 0.5), "B"),
    ]
    left = [
        cube((2.2, 11.5, 6.5), (4.2, 15, 8.5), "C", rot=("z", 22.5, (3.2, 11.5, 7.5))),
        cube((0.9, 14.6, 6.7), (2.7, 18.2, 8.5), "C", rot=("z", 22.5, (1.8, 14.6, 7.6))),
        cube((2.4, 13.5, 3.5), (3.8, 15, 7), "C"),
        cube((-1, 16, 7), (1, 17.4, 8.2), "C", rot=("z", 45, (0, 16, 7.6))),
    ]
    return e + left + [mirror(x) for x in left]


def gills():
    shell = sprite(2, 3, 14, 13)  # the whole shell with its gill slits
    left_fins = [
        cube((0, 5, 7.3), (2.5, 6, 8.3), "B", rot=("z", 22.5, (2.5, 5.5, 7.8))),
        cube((0, 7.5, 7.3), (2.5, 8.5, 8.3), "B", rot=("z", 22.5, (2.5, 8, 7.8))),
        cube((0, 10, 7.3), (2.5, 11, 8.3), "B", rot=("z", 22.5, (2.5, 10.5, 7.8))),
    ]
    return [
        cube((1.5, 3, 7), (14.5, 13, 8.6), "A", faces={"north": shell}),
        cube((3, 13, 6.5), (13, 14.2, 8.6), "B"),
        cube((4, 1.8, 6.8), (12, 3, 8.4), "C"),
        cube((7.2, 3.2, 6), (8.8, 12.8, 7), "L"),
    ] + left_fins + [mirror(x) for x in left_fins]


WING_UPPER = [0, 0, 8, 16]   # the left half of the wing texture: the big upper wing, base at the bottom
WING_LOWER = [8, 0, 16, 16]  # the right half: the small lower wing, base at the top


def wings():
    """Four flat wings with a painted silhouette (see paint_wings): two big upper wings tipped up and out, two smaller
    lower wings hanging down and out, on a small spine. Each wing plate has a front and a back face."""
    both = ["north", "south"]
    upper = cube((9, 8, 7.7), (19, 28, 7.9), "A", rot=("z", -22.5, (9, 8, 7.8)), only=both,
                 faces={"north": WING_UPPER, "south": WING_UPPER})
    lower = cube((9, -6, 7.75), (16, 8, 7.95), "A", rot=("z", 45, (9, 8, 7.8)), only=both,
                 faces={"north": WING_LOWER, "south": WING_LOWER})
    # the spine takes a solid patch from the middle of the upper wing (its centre vein), so the texture has no spare corner
    spine = cube((7, 5, 7.2), (9, 13, 8.8), "A", faces={f: [3.5, 7.5, 4.5, 8.5] for f in FACES})
    return [spine, upper, lower, mirror(upper), mirror(lower)]


def paint_wings():
    """A 32 by 64 texture: an upper wing (left half) and a lower wing (right half), each a rounded teardrop of pale,
    see through membrane with a darker rim and lighter veins fanning from the base. Painted from numbers, our own art."""
    import math as m
    w, h = 32, 64
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    rim, vein = (150, 110, 205), (245, 225, 255)

    def membrane(t):
        # pink at the base to pale blue at the tip
        return (int(235 - 50 * t), int(190 + 40 * t), int(240 + 10 * t))

    def wing(ox, base_at_bottom, length, width):
        bx, by = ox + 8, (h - 3) if base_at_bottom else 2
        for y in range(h):
            for x in range(ox, ox + 16):
                t = (by - y) / length if base_at_bottom else (y - by) / length
                if t < 0 or t > 1:
                    continue
                half = width * m.sin(m.pi * min(1.0, t * 1.08)) ** 0.7 * (1 - 0.15 * t) + 0.6
                d = abs(x + 0.5 - bx)
                if d > half:
                    continue
                edge = half - d < 1.1 or t > 0.96
                angle = m.atan2(x + 0.5 - bx, max(0.5, t * length))
                on_vein = any(abs(angle - a) < 0.07 for a in (-0.5, -0.2, 0.0, 0.2, 0.5)) and t > 0.08
                if edge:
                    img.putpixel((x, y), rim + (255,))
                elif on_vein:
                    img.putpixel((x, y), vein + (235,))
                else:
                    img.putpixel((x, y), membrane(t) + (165,))

    wing(0, True, 58, 7.2)
    wing(16, False, 58, 6.0)
    return img


MODELS_BUILD = {
    "forgeman_pauldron": pauldron,
    "kingdom_cape": cape,
    "antler_circlet": circlet,
    "tidal_gills": gills,
    "fairy_wings": wings,
}

# (base, trim, dark, accent, light) swatch colours, sampled from each sprite.
PALETTES = {
    "forgeman_pauldron": ["#828791", "#be823c", "#4b4e5a", "#e6aa5a", "#b9bec8"],
    "kingdom_cape": ["#aa1e2d", "#f0c846", "#6e0f1e", "#ffeb8c", "#d74650"],
    "antler_circlet": ["#ebbe3c", "#ffeb8c", "#78502d", "#46be5a", "#a0781e"],
    "tidal_gills": ["#46aabe", "#8cdce6", "#23647d", "#32829b", "#8cdce6"],
}


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def paint(name):
    img = Image.new("RGBA", (ATLAS_W, ATLAS_H), (0, 0, 0, 0))
    img.paste(Image.open(os.path.join(SPRITES, name + ".png")).convert("RGBA"), (0, 0))
    for key, colour in zip(["A", "B", "C", "D", "L"], PALETTES[name]):
        x0, y0 = CELLS[key]
        for y in range(16):
            for x in range(16):
                img.putpixel((x0 + x, y0 + y), rgb(colour) + (255,))
    return img


def main():
    os.makedirs(MODELS, exist_ok=True)
    os.makedirs(TEXTURES, exist_ok=True)
    for name, build in MODELS_BUILD.items():
        model = {
            "textures": {"0": f"kingdoms:item/worn/{name}", "particle": f"kingdoms:item/worn/{name}"},
            "elements": build(),
            "display": DISPLAY,
        }
        with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump(model, f, indent=2)
            f.write("\n")
        if name in PALETTES:
            paint(name).save(os.path.join(TEXTURES, name + ".png"))
        elif name == "fairy_wings":
            paint_wings().save(os.path.join(TEXTURES, name + ".png"))
        print(name, len(model["elements"]), "elements")


if __name__ == "__main__":
    main()
