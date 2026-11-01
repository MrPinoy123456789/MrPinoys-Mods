"""Builds the 3D worn models and their textures for the Kingdoms race trinkets.

Run from the foundry folder: python tools/gen_trinket_models.py. It writes, under examples/assets/kingdoms:
  models/item/worn/<name>.json   vanilla item models (cuboid elements, per face UVs, rotations, display transforms)
  textures/worn/<name>.png       one 32 by 32 texture per item, four 16 by 16 pixel regions (see REGIONS)
Copy both folders into config/foundry/assets/kingdoms on the server (the same place the flat item textures live), then
restart or run /foundry pack build. Each item's content file names its model with "worn_model", and Foundry draws it
wherever the item is shown (worn display, helmet slot, inventory, hand); the flat texture stays as the fallback and as
the Bedrock icon. Run tools/check_models.py afterwards to validate the result and write a preview picture.

Model space is the usual 0 to 16 cube, front is the north face (low z). Everything here is a plain script so the art
can be reproduced and adjusted: change a number, re-run, re-check.
"""
import json
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets", "kingdoms")
MODELS = os.path.join(ASSETS, "models", "item", "worn")
TEXTURES = os.path.join(ASSETS, "textures", "worn")

# UV rectangles (in the 0 to 16 model space) of the four texture regions: base, trim, dark, accent.
REGIONS = {"A": [0, 0, 8, 8], "B": [8, 0, 16, 8], "C": [0, 8, 8, 16], "D": [8, 8, 16, 16]}
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


def cube(frm, to, region="A", rot=None, faces=None, only=None):
    """One cuboid. region is a REGIONS key for every face, faces overrides per face, only keeps just those faces."""
    out = {}
    for face in FACES:
        if only and face not in only:
            continue
        r = (faces or {}).get(face, region)
        out[face] = {"uv": list(REGIONS[r]), "texture": "#0"}
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
    e = [
        cube((2, 7, 2), (14, 9, 14)),
        cube((3, 9, 3), (13, 11, 13), "B"),
        cube((4, 11, 4), (12, 12.5, 12)),
        cube((12, 6, 3), (15, 8.5, 13), rot=("z", -22.5, (12, 8, 8))),
        cube((1, 6, 3), (4, 8.5, 13), rot=("z", 22.5, (4, 8, 8))),
        cube((5, 12.5, 5), (6.5, 13.5, 6.5), "D"),
        cube((9.5, 12.5, 5), (11, 13.5, 6.5), "D"),
        cube((5, 12.5, 9.5), (6.5, 13.5, 11), "D"),
        cube((9.5, 12.5, 9.5), (11, 13.5, 11), "D"),
        cube((6.5, 0, 6), (9.5, 7, 7), "C"),
        cube((7, 3, 5.5), (9, 5, 6), "D"),
    ]
    return e


def cape():
    return [
        cube((3, 13, 6), (13, 15.5, 10), "B"),
        cube((7, 12, 5.4), (9, 14.2, 6.2), "D"),
        cube((3, 6, 7.4), (13, 13, 8.6)),
        cube((3, 0, 6.8), (13, 6.2, 8.0), rot=("x", 22.5, (8, 6, 8))),
        cube((3, 0, 7.4), (4.2, 12, 9.4), "C"),
        cube((11.8, 0, 7.4), (13, 12, 9.4), "C"),
        cube((3, 6, 8.6), (13, 6.6, 9.4), "B"),
    ]


def circlet():
    e = [
        cube((0.5, 9, 0.5), (15.5, 11.5, 2)),
        cube((0.5, 9, 14), (15.5, 11.5, 15.5)),
        cube((0.5, 9, 2), (2, 11.5, 14)),
        cube((14, 9, 2), (15.5, 11.5, 14)),
        cube((4, 10, 0.2), (12, 11, 0.5), "B"),
        cube((7, 9.4, 0.1), (9, 11.1, 0.5), "D"),
    ]
    left = [
        cube((2.2, 11.5, 6.5), (4.2, 15, 8.5), "A", rot=("z", 22.5, (3.2, 11.5, 7.5))),
        cube((0.9, 14.6, 6.7), (2.7, 18.2, 8.5), "B", rot=("z", 22.5, (1.8, 14.6, 7.6))),
        cube((2.4, 13.5, 3.5), (3.8, 15, 7), "B"),
        cube((-1, 16, 7), (1, 17.4, 8.2), "B", rot=("z", 45, (0, 16, 7.6))),
    ]
    return e + left + [mirror(x) for x in left]


def gills():
    left_fins = [
        cube((0, 5, 7.3), (2.5, 6, 8.3), "B", rot=("z", 22.5, (2.5, 5.5, 7.8))),
        cube((0, 7.5, 7.3), (2.5, 8.5, 8.3), "B", rot=("z", 22.5, (2.5, 8, 7.8))),
        cube((0, 10, 7.3), (2.5, 11, 8.3), "B", rot=("z", 22.5, (2.5, 10.5, 7.8))),
    ]
    return [
        cube((2, 4, 7), (14, 12, 8.5)),
        cube((3, 12, 6.5), (13, 13.5, 8.5), "B"),
        cube((4, 2.5, 6.8), (12, 4, 8.3)),
        cube((7, 3, 5.5), (9, 13, 7), "D"),
        cube((3.5, 5.5, 6.6), (5.5, 10.5, 7), "C"),
        cube((10.5, 5.5, 6.6), (12.5, 10.5, 7), "C"),
    ] + left_fins + [mirror(x) for x in left_fins]


def wings():
    plates = ["north", "south"]
    right = [
        cube((9, 11.5, 7.4), (28, 12.6, 8.2), "B", rot=("z", 22.5, (9, 12, 7.8)), only=plates),
        cube((9, 5, 7.6), (26, 11.5, 8.0), "A", rot=("z", 22.5, (9, 12, 7.8)), only=plates),
        cube((9, 6.5, 7.5), (23, 7.5, 8.3), "B", rot=("z", -22.5, (9, 7, 7.9)), only=plates),
        cube((9, 0.5, 7.7), (22, 6.5, 8.1), "C", rot=("z", -22.5, (9, 7, 7.9)), only=plates),
        cube((14, 8, 7.5), (22, 8.8, 8.1), "D", rot=("z", 22.5, (9, 12, 7.8)), only=plates),
    ]
    return [cube((6.5, 6, 7.2), (9.5, 14, 8.8), "B")] + right + [mirror(x) for x in right]


MODELS_BUILD = {
    "forgeman_pauldron": pauldron,
    "kingdom_cape": cape,
    "antler_circlet": circlet,
    "tidal_gills": gills,
    "fairy_wings": wings,
}

# (base, trim, dark, accent) colours with their alpha and surface pattern for each item's four regions.
PALETTES = {
    "forgeman_pauldron": [((112, 118, 128), 255, "speckle"), ((176, 120, 58), 255, "stripes"),
                          ((82, 54, 36), 255, "grain"), ((214, 220, 232), 255, "speckle")],
    "kingdom_cape": [((38, 62, 140), 255, "weave"), ((226, 184, 62), 255, "stripes"),
                     ((24, 40, 100), 255, "weave"), ((250, 220, 110), 255, "speckle")],
    "antler_circlet": [((110, 78, 48), 255, "grain"), ((222, 208, 176), 255, "grain"),
                       ((66, 44, 28), 255, "grain"), ((92, 170, 84), 255, "speckle")],
    "tidal_gills": [((40, 150, 160), 255, "scales"), ((226, 232, 236), 255, "scales"),
                    ((18, 52, 84), 255, "speckle"), ((232, 112, 98), 255, "speckle")],
    "fairy_wings": [((190, 236, 245), 120, "veins"), ((150, 130, 214), 255, "stripes"),
                    ((130, 170, 214), 150, "veins"), ((255, 255, 255), 255, "speckle")],
}


def shade(colour, amount):
    return tuple(max(0, min(255, c + amount)) for c in colour)


def paint(name):
    rng = random.Random(name)
    img = Image.new("RGBA", (32, 32))
    for i, (colour, alpha, pattern) in enumerate(PALETTES[name]):
        ox, oy = (i % 2) * 16, (i // 2) * 16
        for y in range(16):
            for x in range(16):
                amount = 0
                if pattern == "speckle":
                    amount = rng.choice([-14, -6, 0, 0, 0, 8, 16])
                elif pattern == "grain":
                    amount = -10 if (y + (x // 5)) % 3 == 0 else rng.choice([0, 0, 6])
                elif pattern == "weave":
                    amount = 10 if (x + y) % 2 == 0 else -6
                elif pattern == "stripes":
                    amount = 12 if y % 4 == 0 else (-10 if y % 4 == 2 else 0)
                elif pattern == "scales":
                    amount = 14 if (x + (y % 4) * 2) % 8 == 0 else (-12 if (x + (y % 4) * 2) % 8 == 4 else 0)
                elif pattern == "veins":
                    amount = 40 if (x * 2 + y) % 9 == 0 or (x - y * 2) % 11 == 0 else rng.choice([-6, 0, 0, 6])
                a = alpha
                if pattern == "veins" and amount >= 40:
                    a = min(255, alpha + 110)
                img.putpixel((ox + x, oy + y), shade(colour, amount) + (a,))
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
        paint(name).save(os.path.join(TEXTURES, name + ".png"))
        print(name, len(model["elements"]), "elements")


if __name__ == "__main__":
    main()
