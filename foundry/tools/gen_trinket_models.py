"""Builds the 3D worn models and their textures for the Kingdoms race trinkets.

Run from the foundry folder: python tools/gen_trinket_models.py. It writes, under examples/assets/kingdoms:
  models/item/worn/<name>.json   vanilla item models (cuboid elements, per face UVs, rotations, display transforms)
  textures/worn/<name>.png       one texture per item (see below)
Copy both folders into config/foundry/assets/kingdoms on the server, then restart or run /foundry pack build. Each
item's content file names its model with "worn_model", and Foundry draws it wherever the item is shown (worn display,
helmet slot, inventory, hand); the flat texture stays as the fallback and as the Bedrock icon. Run tools/check_models.py
afterwards to validate the result and write a preview picture.

Textures: the cape, pauldron, circlet and gills are skinned from each item's own flat sprite
(textures/item/<name>.png). The atlas is 48 by 32 pixels: the sprite itself at the top left, then flat swatches of the
sprite's own colours (base, trim, dark, accent, light). Faces that show the item's picture use a crop of the sprite;
the rest use a swatch, so the model reads like the sprite and every colour comes from it. The fairy wings use the
elytra texture from the "Fairy Wings Elytra" pack (CC BY-NC-SA 4.0, see examples/assets/CREDITS.md), copied unchanged
to textures/worn/fairy_wings.png, which this script never overwrites.

Model space is the usual 0 to 16 cube, front is the north face (low z).
"""
import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets", "kingdoms")
MODELS = os.path.join(ASSETS, "models", "item", "worn")
TEXTURES = os.path.join(ASSETS, "textures", "worn")
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
    pic = sprite(3, 4, 13, 13)  # the plate with its two straps
    return [
        cube((2, 7, 2), (14, 9, 14), "A", faces={"up": pic, "north": swatch("L")}),
        cube((3, 9, 3), (13, 11, 13), "B"),
        cube((4, 11, 4), (12, 12.5, 12), "A", faces={"up": pic}),
        cube((12, 6, 3), (15, 8.5, 13), "A", rot=("z", -22.5, (12, 8, 8)), faces={"up": "L"}),
        cube((1, 6, 3), (4, 8.5, 13), "A", rot=("z", 22.5, (4, 8, 8)), faces={"up": "L"}),
        cube((5, 12.5, 5), (6.5, 13.5, 6.5), "D"),
        cube((9.5, 12.5, 5), (11, 13.5, 6.5), "D"),
        cube((5, 12.5, 9.5), (6.5, 13.5, 11), "D"),
        cube((9.5, 12.5, 9.5), (11, 13.5, 11), "D"),
        cube((6.5, 0, 6), (9.5, 7, 7), "C"),
        cube((7, 3, 5.5), (9, 5, 6), "D"),
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


def wings():
    """Each wing is the elytra pack's leaf, a plate hanging from the spine and tipped out 45 degrees."""
    leaf = [9, 1, 11, 11]  # elytra.png pixels x 36 to 44, y 2 to 22, as a 64 by 32 texture
    leaf_flipped = [9, 11, 11, 1]
    both = ["north", "south"]
    upper = cube((9, 8, 7.6), (16, 25, 8.0), "A", rot=("z", -45, (9, 8, 7.8)), only=both,
                 faces={"north": leaf, "south": leaf})
    lower = cube((9, -4, 7.7), (14, 8, 8.1), "A", rot=("z", 45, (9, 8, 7.9)), only=both,
                 faces={"north": leaf_flipped, "south": leaf_flipped})
    spine = cube((7, 5, 7.2), (9, 13, 8.8), "A", faces={"north": [9, 4, 10, 8], "south": [9, 4, 10, 8], "east": [9, 4, 10, 8],
                                                         "west": [9, 4, 10, 8], "up": [9, 4, 10, 8], "down": [9, 4, 10, 8]})
    return [spine, upper, lower, mirror(upper), mirror(lower)]


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
            "textures": {"0": f"kingdoms:worn/{name}", "particle": f"kingdoms:worn/{name}"},
            "elements": build(),
            "display": DISPLAY,
        }
        with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump(model, f, indent=2)
            f.write("\n")
        if name in PALETTES:
            paint(name).save(os.path.join(TEXTURES, name + ".png"))
        print(name, len(model["elements"]), "elements")


if __name__ == "__main__":
    main()
