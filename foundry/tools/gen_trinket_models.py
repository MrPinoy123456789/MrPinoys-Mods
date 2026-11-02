"""Builds the 3D worn models and their textures for the Kingdoms race trinkets.

Run from the foundry folder: python tools/gen_trinket_models.py. It writes, under examples/assets/kingdoms:
  models/item/worn/<name>.json   vanilla item models (cuboid elements, per face UVs, rotations, display transforms)
  textures/item/worn/<name>.png  one texture per item. It must sit under textures/item/: the client stitches item
                                 model textures only from there (the items atlas), and anywhere else draws pink and black.
Copy both folders into config/foundry/assets/kingdoms on the server, then restart or run /foundry pack build. Each
item's content file names its model with "worn_model". Look at the result with tools/preview_model.py and check it
with tools/check_models.py.

How the textures are made: every face of every cuboid gets its own tile in the texture, sized to the face at
DENSITY texels per model unit, and painted by the part's material (brushed iron, bronze, leather, gold, gem, antler,
shell, fin...) with grain, highlights and bevelled edges. So the detail follows the shape, and no two faces share
pixels. The cape is the vanilla cape: the same 10 by 16 by 1 box and the vanilla 64 by 32 cape texture layout (any
cape PNG drawn for vanilla fits it). The fairy wings use a painted wing texture with a real silhouette.

Model space is the usual 0 to 16 cube, front is the north face (low z). Everything is made from numbers in this file,
so it can be reproduced and adjusted.
"""
import json
import math
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets", "kingdoms")
MODELS = os.path.join(ASSETS, "models", "item", "worn")
TEXTURES = os.path.join(ASSETS, "textures", "item", "worn")

DENSITY = 4  # texels per model unit
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


# ---- colours and materials ----

def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def clamp(v):
    return max(0, min(255, int(v)))


def tint(c, k):
    return tuple(clamp(x + k) for x in c)


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


# Each material: base colour, light and dark accents, a surface pattern, noise amount, bevel strength, alpha.
MATERIALS = {
    "iron": dict(base="#8b909a", pattern="brushed", noise=7, bevel=34),
    "iron_light": dict(base="#a9aeb8", pattern="brushed", noise=6, bevel=30),
    "iron_dark": dict(base="#5d616b", pattern="brushed", noise=6, bevel=26),
    "bronze": dict(base="#b8793a", pattern="sheen", noise=8, bevel=40),
    "rivet": dict(base="#d7a35a", pattern="dot", noise=0, bevel=0),
    "leather": dict(base="#6e4529", pattern="leather", noise=10, bevel=22),
    "gold": dict(base="#e0ad38", pattern="engraved", noise=6, bevel=46),
    "gold_dark": dict(base="#a8771f", pattern="sheen", noise=6, bevel=30),
    "gem": dict(base="#2fbf63", pattern="gem", noise=0, bevel=0),
    "leaf": dict(base="#4f9a3a", pattern="leaf", noise=8, bevel=18),
    "antler": dict(base="#6f4c2e", pattern="ringed", noise=8, bevel=24),
    "pole": dict(base="#1f1d22", pattern="brushed", noise=5, bevel=22),
    "silver": dict(base="#c9ced8", pattern="sheen", noise=5, bevel=42),
    "silver_dark": dict(base="#6f747f", pattern="sheen", noise=5, bevel=30),
    "antler_tip": dict(base="#d8c7a0", pattern="ringed", noise=7, bevel=24),
    "shell": dict(base="#3aa4b6", pattern="ridges", noise=6, bevel=30),
    "slit": dict(base="#173946", pattern="plain", noise=4, bevel=10),
    "fin": dict(base="#86d8e2", pattern="fin", noise=4, bevel=0, alpha=225),
    "pearl": dict(base="#e6f0f2", pattern="sheen", noise=5, bevel=24),
    "beard": dict(base="#8a4b22", pattern="hair", noise=10, bevel=20),
    "petal_white": dict(base="#f6f2ff", pattern="flower", noise=4, bevel=0),
    "petal_pink": dict(base="#f59ac4", pattern="flower", noise=4, bevel=0),
    "petal_blue": dict(base="#8fb8ff", pattern="flower", noise=4, bevel=0),
    "flower_centre": dict(base="#f6c93c", pattern="dot", noise=0, bevel=0),
    "wreath": dict(base="#3f7d33", pattern="leaf", noise=10, bevel=16),
    "beard_dark": dict(base="#5e3116", pattern="hair", noise=8, bevel=18),
    "beard_light": dict(base="#b0652e", pattern="hair", noise=10, bevel=18),
    "bead": dict(base="#c9923f", pattern="sheen", noise=4, bevel=40),
}


def paint_tile(material, w, h, rng):
    m = MATERIALS[material]
    base = rgb(m["base"])
    alpha = m.get("alpha", 255)
    img = Image.new("RGBA", (w, h))
    for y in range(h):
        row = rng.randint(-m["noise"], m["noise"]) if m["pattern"] == "brushed" else 0
        for x in range(w):
            k = rng.randint(-m["noise"], m["noise"]) if m["noise"] else 0
            c = base
            p = m["pattern"]
            if p == "brushed":
                k = k // 2 + row
            elif p == "sheen":
                k += int(22 * math.cos((x + y * 0.6) / max(w, 1) * math.pi * 1.6))
            elif p == "engraved":
                if (x + y) % 6 == 0 and 1 <= y < h - 1:
                    k -= 38
                k += int(14 * math.cos(x / max(w, 1) * math.pi * 2))
            elif p == "leather":
                k += rng.choice([0, 0, -8, 6])
                if y in (1, h - 2) and x % 3 != 2 and h >= 5:
                    c = rgb("#cfa86a")  # stitching
                    k = 0
            elif p == "ringed":
                k += -18 if y % 4 == 0 else (8 if y % 4 == 2 else 0)
            elif p == "ridges":
                band = (y // 2) % 3
                k += (18, 0, -16)[band]
                if (x * 3 + y) % 11 == 0:
                    c = rgb("#d6f4f7")  # a pearly fleck
            elif p == "fin":
                t = x / max(w - 1, 1)
                c = mix(rgb("#bdeff3"), rgb("#2c8fa3"), t)
                k += -22 if x % 3 == 0 else 0
            elif p == "leaf":
                if abs(x - w / 2) < 0.6:
                    k += 30  # the midrib
            elif p == "gem":
                d = (x + y) / max(w + h - 2, 1)
                c = mix(rgb("#9cf5b8"), rgb("#13702f"), d)
                if x == y or x == y + 1:
                    c = rgb("#e8fff0")
            elif p == "hair":
                # strands: each column a slightly different shade, with a darker parting every few texels
                k += ((x * 7 + 3) % 5 - 2) * 6 + (-22 if x % 4 == 0 else 0) + (8 if (x + y // 3) % 7 == 0 else 0)
            elif p == "dot":
                cx, cy = (w - 1) / 2, (h - 1) / 2
                d = math.hypot(x - cx, y - cy) / max(w / 2, 1)
                c = mix(rgb("#ffe0a0"), rgb("#7a4f1c"), min(1, d))
            a = alpha
            if p == "flower":
                # five rounded petals around a small gold centre; outside the petals is see through
                cx, cy = (w - 1) / 2, (h - 1) / 2
                dx, dy = x - cx, y - cy
                r = math.hypot(dx, dy) / max(w / 2, 1)
                petal = 0.62 + 0.38 * abs(math.cos(2.5 * math.atan2(dy, dx)))
                if r > petal:
                    a = 0
                elif r < 0.25:
                    c = rgb("#f6c93c")
                else:
                    k += int(-18 * r)
            pixel = tint(c, k)
            if m["bevel"] and w >= 3 and h >= 3:
                if y == 0 or x == 0:
                    pixel = tint(pixel, m["bevel"] if y == 0 else m["bevel"] // 2)
                elif y == h - 1 or x == w - 1:
                    pixel = tint(pixel, -m["bevel"] if y == h - 1 else -m["bevel"] // 2)
            img.putpixel((x, y), pixel + (a,))
    return img


# ---- geometry ----

def part(frm, to, material, rot=None, only=None):
    p = {"from": list(frm), "to": list(to), "material": material, "only": only}
    if rot:
        axis, angle, origin = rot
        p["rotation"] = {"origin": list(origin), "axis": axis, "angle": angle}
    return p


def mirror(p):
    """The same part reflected across the x = 8 plane."""
    f, t = p["from"], p["to"]
    out = dict(p)
    out["from"], out["to"] = [16 - t[0], f[1], f[2]], [16 - f[0], t[1], t[2]]
    if "rotation" in p:
        r = p["rotation"]
        o = list(r["origin"])
        o[0] = 16 - o[0]
        out["rotation"] = {"origin": o, "axis": r["axis"], "angle": -r["angle"] if r["axis"] in ("z", "y") else r["angle"]}
    return out


def face_size(face, f, t):
    dx, dy, dz = t[0] - f[0], t[1] - f[1], t[2] - f[2]
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[face]


def bake(name, parts):
    """Gives every face its own painted tile (shelf packed, one texel of padding) and returns the elements and texture."""
    rng = random.Random(name)
    tiles = []
    for i, p in enumerate(parts):
        for face in (p["only"] or FACES):
            fw, fh = face_size(face, p["from"], p["to"])
            w, h = max(1, round(fw * DENSITY)), max(1, round(fh * DENSITY))
            tiles.append((i, face, w, h))
    size = 32
    while True:
        placed, x, y, shelf = {}, 0, 0, 0
        ok = True
        for i, face, w, h in sorted(tiles, key=lambda t: -t[3]):
            if x + w + 2 > size:
                x, y, shelf = 0, y + shelf, 0
            if y + h + 2 > size or w + 2 > size:
                ok = False
                break
            placed[(i, face)] = (x + 1, y + 1, w, h)
            x += w + 2
            shelf = max(shelf, h + 2)
        if ok:
            break
        size *= 2
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    elements = []
    for i, p in enumerate(parts):
        faces = {}
        for face in (p["only"] or FACES):
            px, py, w, h = placed[(i, face)]
            tile = paint_tile(p["material"], w, h, rng)
            # a one texel border copied from the tile's edge, so far away mipmaps do not bleed in a neighbour
            padded = tile.resize((w + 2, h + 2), Image.NEAREST)
            padded.paste(tile, (1, 1))
            img.paste(padded, (px - 1, py - 1))
            faces[face] = {"uv": [round(px * 16 / size, 4), round(py * 16 / size, 4),
                                  round((px + w) * 16 / size, 4), round((py + h) * 16 / size, 4)], "texture": "#0"}
        el = {"from": p["from"], "to": p["to"], "faces": faces}
        if p.get("name"):
            el["name"] = p["name"]
        if "rotation" in p:
            el["rotation"] = p["rotation"]
        elements.append(el)
    return elements, img


# ---- the five trinkets ----

def pauldron():
    """Overlapping iron lames over the right shoulder (+x is outward), bronze edged, riveted, with a leather strap."""
    return [
        part((3, 11, 3), (12, 13.5, 13), "iron"),
        part((4.5, 13.5, 4.5), (10.5, 14.4, 11.5), "iron_light"),
        part((9.5, 9, 2.5), (13.5, 11.8, 13.5), "iron_light"),
        part((9.5, 8.6, 2.4), (13.6, 9.2, 13.6), "bronze"),
        part((12, 6, 2), (15, 9.4, 14), "iron"),
        part((12, 5.6, 1.9), (15.1, 6.2, 14.1), "bronze"),
        part((13.5, 2.5, 1.5), (16, 6.4, 14.5), "iron_dark"),
        part((13.5, 2.1, 1.4), (16.1, 2.7, 14.6), "bronze"),
        part((1.5, 9.5, 3.5), (3.5, 12.5, 12.5), "leather"),
        part((2, 3, 6.2), (3.2, 9.5, 9.8), "leather"),
        part((1.6, 5.2, 6.8), (2.1, 7.2, 9.2), "bronze"),
        part((5, 13.5, 5.5), (6.5, 14.3, 7), "rivet"),
        part((5, 13.5, 9), (6.5, 14.3, 10.5), "rivet"),
        part((10.4, 11.2, 4), (11.6, 12.2, 5.2), "rivet"),
        part((10.4, 11.2, 10.8), (11.6, 12.2, 12), "rivet"),
    ]


ANTLER_SCALE = 1.1  # 10% bigger than first drawn
ANTLER_BASE = (3.1, 14.6, 7.3)  # where the left antler meets the top of the head (the head's top is about y 14.4 to 15)


def grown(p, base, k):
    """A part moved and scaled by k about base (its from, to and rotation origin), so a whole antler grows from its root."""
    def at(v):
        return [round(base[i] + (v[i] - base[i]) * k, 4) for i in range(3)]
    out = dict(p)
    out["from"], out["to"] = at(p["from"]), at(p["to"])
    if "rotation" in p:
        out["rotation"] = dict(p["rotation"], origin=at(p["rotation"]["origin"]))
    return out


def circlet():
    """Just the antlers: two branching antlers rising from the top of the head, each with a burr at its root, a main beam
    leaning out, a tine forward, a tine outward, a tine back and a pale tip. No band, gem or leaves."""
    lift = 2.6  # first drawn on a band at y 12; moved up so the roots sit on top of the head
    left = [
        part((2.2, 12 - 0.4, 6.4), (4.0, 12.4, 8.2), "antler"),  # the burr where the antler meets the head
        part((2.4, 12, 6.6), (3.8, 15.5, 8.0), "antler", rot=("z", 22.5, (3.1, 12, 7.3))),
        part((1.2, 15, 6.7), (2.4, 19, 7.9), "antler", rot=("z", 22.5, (1.8, 15, 7.3))),
        part((0.2, 18.5, 6.8), (1.2, 21.5, 7.8), "antler_tip", rot=("z", -22.5, (0.7, 18.5, 7.3))),
        part((2.6, 15.2, 3.6), (3.4, 16.2, 6.8), "antler_tip", rot=("x", -22.5, (3, 15.7, 6.8))),
        part((-2.0, 16.6, 6.9), (1.4, 17.6, 7.7), "antler_tip", rot=("z", -22.5, (1.4, 17.1, 7.3))),
        part((1.6, 18.8, 8.0), (2.4, 20.6, 10.6), "antler_tip", rot=("x", 22.5, (2, 18.8, 8.0))),
    ]
    lifted = [grown(p, (0, 0, 0), 1.0) for p in left]
    for p in lifted:
        p["from"][1] += lift
        p["to"][1] += lift
        if "rotation" in p:
            p["rotation"]["origin"][1] += lift
    left = [grown(p, ANTLER_BASE, ANTLER_SCALE) for p in lifted]
    return left + [mirror(p) for p in left] + wreath()


WREATH_Y = 14.4  # the top of the head; the helmet versions lift the wreath onto the helmet (see write_helmet)


def wreath():
    """A woodland wreath around the top of the head: a band of leaves, with small flowers set into it (white, pink and
    blue, five petals each), in the gaps between and around the antlers. Each part is named so it can be lifted."""
    y = WREATH_Y
    band = [
        part((1.2, y - 0.6, 0.8), (14.8, y + 0.4, 1.8), "wreath"),
        part((1.2, y - 0.6, 14.2), (14.8, y + 0.4, 15.2), "wreath"),
        part((0.8, y - 0.6, 1.8), (1.8, y + 0.4, 14.2), "wreath"),
        part((14.2, y - 0.6, 1.8), (15.2, y + 0.4, 14.2), "wreath"),
    ]
    flowers = []
    spots = [((5.0, 0.4), "petal_white"), ((7.6, 0.2), "petal_pink"), ((10.2, 0.4), "petal_blue"),
             ((0.4, 4.6), "petal_pink"), ((13.2, 4.6), "petal_white"), ((0.4, 11.0), "petal_blue"),
             ((13.2, 11.0), "petal_pink"), ((6.4, 13.6), "petal_white"), ((9.2, 13.8), "petal_blue")]
    for (fx, fz), petal in spots:
        flowers.append(part((fx, y + 0.3, fz), (fx + 2.4, y + 0.6, fz + 2.4), petal, only=["up", "down"]))
        flowers.append(part((fx + 0.9, y + 0.5, fz + 0.9), (fx + 1.5, y + 0.9, fz + 1.5), "flower_centre"))
    for el in band + flowers:
        el["name"] = "wreath"
    return band + flowers


def gills():
    """Gill frills on both sides of the neck and jaw, just outside the head and behind the eyes: a scaled membrane with
    three dark slits, three fins sweeping back and a crest fin above. Nothing sits in front of the face."""
    left = [
        part((-0.7, 2.2, 7.4), (0, 8.8, 12.8), "shell"),
        part((-0.9, 3.4, 8.2), (-0.7, 4.0, 12.2), "slit"),
        part((-0.9, 5.0, 8.2), (-0.7, 5.6, 12.2), "slit"),
        part((-0.9, 6.6, 8.2), (-0.7, 7.2, 12.2), "slit"),
        part((-3.6, 2.6, 10.0), (-0.7, 3.8, 10.4), "fin", rot=("y", 22.5, (-0.7, 3.2, 10.2))),
        part((-4.2, 4.4, 10.6), (-0.7, 5.8, 11.0), "fin", rot=("y", 22.5, (-0.7, 5.1, 10.8))),
        part((-3.6, 6.4, 11.2), (-0.7, 7.6, 11.6), "fin", rot=("y", 22.5, (-0.7, 7.0, 11.4))),
        part((-1.1, 8.8, 8.2), (-0.5, 10.6, 13.4), "fin"),
        part((-0.4, 1.2, 8.4), (0.3, 2.2, 12.0), "pearl"),
    ]
    return left + [mirror(p) for p in left]


def beard():
    """A thick Dwarf beard, in the head's model space like the antlers (the head spans about 1.6 to 14.4, its face is the
    north side at z 1.6, the chin at y 1.6): a full beard over the jaw and chin, falling below the chin onto the chest in
    three steps to a forked, bead bound tip, a broad drooping moustache, and thick sideburns running back along the jaw.
    It sits a little in front of the face (and of a helmet's front, which is at z 0)."""
    left = [
        part((1.0, 1.6, 1.4), (2.2, 8.0, 8.0), "beard"),                     # sideburn along the jaw
        part((3.4, 3.6, -0.8), (5.2, 6.0, 0.6), "beard_dark", rot=("z", -22.5, (5.2, 6.0, -0.1))),  # moustache droop
    ]
    centre = [
        part((2.2, 0.4, -0.6), (13.8, 5.4, 2.2), "beard"),                   # the full beard over jaw and chin
        part((2.6, 1.0, 1.6), (13.4, 3.4, 7.0), "beard_dark"),               # under the chin, back toward the neck
        part((3.2, -3.0, -0.8), (12.8, 0.6, 1.8), "beard"),                  # first fall below the chin
        part((4.4, -6.0, -0.9), (11.6, -2.8, 1.6), "beard_light"),           # second fall
        part((5.2, -8.6, -0.7), (7.8, -5.8, 1.2), "beard"),                  # the fork, left
        part((8.2, -8.6, -0.7), (10.8, -5.8, 1.2), "beard"),                 # the fork, right
        part((5.6, -9.4, -0.8), (7.4, -8.4, 1.3), "bead"),                   # a bead on each fork
        part((8.6, -9.4, -0.8), (10.4, -8.4, 1.3), "bead"),
        part((4.6, 5.2, -1.0), (11.4, 6.6, 0.6), "beard_dark"),              # the moustache
    ]
    return centre + left + [mirror(x) for x in left]


def paint_beard_icon():
    """The flat 16 by 16 icon for the beard item (its inventory picture before the 3D model takes over, and the fallback)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    hair, dark, light, bead = rgb("#8a4b22"), rgb("#5e3116"), rgb("#b0652e"), rgb("#c9923f")
    rows = {2: (3, 12), 3: (2, 13), 4: (2, 13), 5: (2, 13), 6: (3, 12), 7: (3, 12), 8: (4, 11), 9: (4, 11), 10: (5, 10),
            11: (5, 7), 12: (5, 7), 13: (5, 7)}
    for y, (a, b) in rows.items():
        for x in range(a, b + 1):
            img.putpixel((x, y), (light if (x + y) % 5 == 0 else hair if x % 3 else dark) + (255,))
    for y in (11, 12, 13):
        for x in (8, 9, 10):
            img.putpixel((x, y), hair + (255,))
    for x in range(4, 12):
        img.putpixel((x, 4), dark + (255,))  # the moustache
    img.putpixel((6, 14), bead + (255,))
    img.putpixel((9, 14), bead + (255,))
    return img


# ---- the Aurelian war banner ----
# A standard worn on the back: a black iron pole from the small of the back to well above the head, a silver spear
# finial, a silver crossbar with a tassel at each end, and a swallow tailed black banner hung from the crossbar, carrying
# the crowned skull. The solid parts are baked like the other models (texture #0); the cloth has its own painted texture (#1).
BANNER_CLOTH = os.path.join(TEXTURES, "aurelia_banner_cloth.png")
CLOTH_W, CLOTH_H = 52, 80   # pixels of the cloth picture (13 by 20 model units at 4 texels each)
CLOTH_TEX = 128              # the cloth texture is 128 by 128; the picture sits at its top left

# The crowned skull, as pixel art (each character one pixel, drawn at twice this size on the cloth).
SKULL = """\n..H......H......H..
.HSH....HSH....HSH.
.SSS....SSS....SSS.
.SSSS..SSSSS..SSSS.
.SSSSSSSSSSSSSSSSS.
.sHSSSHSSSSSHSSSHs.
.sssssssssssssssss.
..OBBBBBBCBBBBBBO..
.OBWWBBBBBCBBBBBBO.
.OBWBBBBBCBBBBBBbO.
.OBBBBBBBBBBBBBBbO.
.OBDDDBBBBBBBDDDbO.
.OBDDDDBBBBBDDDDbO.
.OBDDDDBBBBBDDDDbO.
.OBBDDBBBBBBBDDBbO.
..OBBBBBBDBBBBBbO..
...ObBBBDDDBBBbO...
...OBTDTDTDTDTbO...
...ObTDTDTDTDTbO...
....OOOOOOOOOOO....
......OOOOOOO......"""
# A solid silver crown on a stern skull: eye sockets and nose in the banner's own black (they read as cut through the
# cloth), sockets only slightly slanted, a crack down the brow and a straight row of teeth.
SKULL_COLOURS = {
    "H": "#ffffff", "S": "#c9ced8", "s": "#6c717c",
    "O": "#050304", "W": "#f8f6f0", "D": "#1a181c", "T": "#ece6d6", "b": "#9f9888", "C": "#3a2a2a",
}
SKULL_BONE = "#d9d4c7"


def banner():
    """The solid parts of the banner (pole, finial, crossbar, tassels); the cloth is added in banner_model."""
    left_tassel = [
        part((0.4, 21.5, 7.3), (1.6, 26.0, 8.7), "silver_dark"),
        part((0.1, 20.4, 7.0), (1.9, 21.6, 9.0), "silver"),
    ]
    return [
        part((7.3, -10, 7.3), (8.7, 29.5, 8.7), "pole"),
        part((6.6, 29.0, 6.6), (9.4, 30.4, 9.4), "silver"),
        part((7.4, 30.4, 7.4), (8.6, 32.0, 8.6), "silver", rot=("y", 45, (8, 31, 8))),
        part((0.6, 26.0, 7.0), (15.4, 27.4, 9.0), "silver"),
        part((6.8, -10, 6.8), (9.2, -8.8, 9.2), "silver_dark"),
    ] + left_tassel + [mirror(t) for t in left_tassel]


def banner_model():
    elements, texture = bake("aurelia_banner", banner())
    cu = lambda x: round(x * 16 / CLOTH_TEX, 4)
    picture = [cu(0), cu(0), cu(CLOTH_W), cu(CLOTH_H)]
    mirrored = [picture[2], picture[1], picture[0], picture[3]]
    hem = [cu(2), cu(1), cu(CLOTH_W - 2), cu(3)]  # a strip of the top hem, for the cloth's thin edges
    cloth = {"from": [1.5, 6.0, 6.4], "to": [14.5, 26.0, 6.8], "faces": {
        "north": {"uv": picture, "texture": "#1"}, "south": {"uv": mirrored, "texture": "#1"},
        "east": {"uv": hem, "texture": "#1"}, "west": {"uv": hem, "texture": "#1"},
        "up": {"uv": hem, "texture": "#1"}, "down": {"uv": hem, "texture": "#1"}}}
    return elements + [cloth], texture


def paint_banner_cloth():
    """The cloth: a black field shaded in soft folds, a silver border with a dark inner line, silver flourishes in the
    corners, rings along the top hem, a swallow tail cut into the bottom, and the crowned skull with a faint blood red
    glow around it."""
    rng = random.Random("aurelia")
    img = Image.new("RGBA", (CLOTH_TEX, CLOTH_TEX), (0, 0, 0, 0))
    w, h = CLOTH_W, CLOTH_H
    # black and silver: a black field, a silver border with a dark inner line, silver rings and flourishes
    field, deep, gold, gold_d, hem = rgb("#1a181c"), rgb("#050405"), rgb("#c9ced8"), rgb("#6f747f"), rgb("#0c0b0d")
    glow = rgb("#3c0a12")  # a faint blood red glow behind the skull
    tail = 14  # rows of the swallow tail
    for y in range(h):
        for x in range(w):
            # the swallow tail: a V cut up into the bottom of the cloth
            if y >= h - tail and abs(x + 0.5 - w / 2) < (y - (h - tail)) * (w / 2) / tail:
                continue
            fold = 9 * math.sin(x / w * math.pi * 3.0) + 5 * math.sin(x / w * math.pi * 7.0 + 1)
            light = -abs(y - h * 0.4) / h * 14
            c = tint(field, fold + light + rng.randint(-5, 5))
            img.putpixel((x, y), c + (255,))
    # gold border (2 px) with a dark inner line, following the tail
    def inside(x, y):
        if not (0 <= x < w and 0 <= y < h):
            return False
        return img.getpixel((x, y))[3] > 0
    border = []
    for y in range(h):
        for x in range(w):
            if not inside(x, y):
                continue
            edge = min([d for d in range(1, 5) if not all(inside(x + dx, y + dy) for dx in range(-d, d + 1)
                                                         for dy in range(-d, d + 1))] or [9])
            border.append((x, y, edge))
    for x, y, edge in border:
        if y < 4:
            continue
        if edge <= 2:
            img.putpixel((x, y), tint(gold if (x + y) % 6 else gold_d, rng.randint(-8, 8) + (14 if edge == 1 else 0)) + (255,))
        elif edge == 3:
            img.putpixel((x, y), deep + (255,))
    # the top hem, darker, with gold rings where it hangs on the crossbar
    for y in range(4):
        for x in range(w):
            img.putpixel((x, y), tint(hem, rng.randint(-4, 4)) + (255,))
    for cx in range(5, w - 3, 8):
        for x, y in [(cx, 0), (cx + 1, 0), (cx - 1, 1), (cx + 2, 1), (cx - 1, 2), (cx + 2, 2), (cx, 3), (cx + 1, 3)]:
            img.putpixel((x, y), gold + (255,))
    # corner flourishes
    for (cx, cy) in [(7, 9), (w - 8, 9), (7, h - tail - 4), (w - 8, h - tail - 4)]:
        for dx, dy in [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2)]:
            img.putpixel((cx + dx, cy + dy), (gold if abs(dx) + abs(dy) < 2 else gold_d) + (255,))
    # the crowned skull, drawn at twice size, with a dark shadow one step down and right
    art = SKULL.splitlines()
    aw, ah = max(len(r) for r in art) * 2, len(art) * 2
    ox, oy = (w - aw) // 2, 11
    for pass_shadow, (sx, sy) in [(True, (-2, 0)), (True, (2, 0)), (True, (0, 2)), (True, (0, -2)), (False, (0, 0))]:
        for r, row in enumerate(art):
            for c, ch in enumerate(row):
                if ch == ".":
                    continue
                colour = rgb(SKULL_COLOURS.get(ch, SKULL_BONE))
                for dy in range(2):
                    for dx in range(2):
                        x, y = ox + c * 2 + dx + sx, oy + r * 2 + dy + sy
                        if 0 <= x < w and 0 <= y < h:
                            if pass_shadow:
                                img.putpixel((x, y), glow + (255,))
                            else:
                                img.putpixel((x, y), tint(colour, rng.randint(-6, 6) if ch in "BSb" else 0) + (255,))
    return img


def paint_banner_icon():
    """The banner item's flat 16 by 16 icon: the pole, the black swallow tailed cloth with a silver top and a pale skull."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    pole, red, gold, bone = rgb("#1f1d22"), rgb("#1a181c"), rgb("#c9ced8"), rgb("#d9d4c7")
    for y in range(0, 16):
        img.putpixel((7, y), pole + (255,))
    for x in range(2, 14):
        img.putpixel((x, 2), gold + (255,))
    for y in range(3, 13):
        for x in range(3, 13):
            if y >= 10 and abs(x + 0.5 - 8) < (y - 9) * 1.6:
                continue
            img.putpixel((x, y), red + (255,))
    for x, y in [(6, 5), (7, 5), (8, 5), (9, 5), (6, 6), (9, 6), (6, 7), (7, 7), (8, 7), (9, 7), (7, 8), (8, 8)]:
        img.putpixel((x, y), bone + (255,))
    for x in (6, 8):
        img.putpixel((x, 4), gold + (255,))
    return img


# The cape: the vanilla cape box (10 by 16 by 1) and the vanilla cape layout on a 64 by 32 texture. In pixels:
# up (1,0)-(11,1), down (11,0)-(21,1), side (0,1)-(1,17), outside (1,1)-(11,17), side (11,1)-(12,17), inside (12,1)-(22,17).
CAPE_PX = {"up": (1, 0, 11, 1), "down": (11, 0, 21, 1), "west": (0, 1, 1, 17), "north": (1, 1, 11, 17),
           "east": (11, 1, 12, 17), "south": (12, 1, 22, 17)}


def cape_model():
    faces = {f: {"uv": [x0 / 4, y0 / 2, x1 / 4, y1 / 2], "texture": "#0"} for f, (x0, y0, x1, y1) in CAPE_PX.items()}
    return [{"from": [3, 0, 7.5], "to": [13, 16, 8.5], "faces": faces}]


def paint_cape():
    """A royal cape in the vanilla layout: deep red cloth with folds, a gold border and a gold crown emblem outside,
    a darker lining inside."""
    rng = random.Random("cape")
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    red, dark, gold, gold_d = rgb("#a51c2b"), rgb("#5e0d18"), rgb("#e7b43c"), rgb("#9c6c18")
    for x in range(64):
        for y in range(32):
            if not (y < 17 and x < 22):
                continue
            fold = 14 * math.sin((x - 1) / 10 * math.pi * 3)  # three soft folds down the cape
            c = tint(red, fold + rng.randint(-5, 5))
            if 12 <= x < 22:  # the lining
                c = tint(dark, fold / 2 + rng.randint(-4, 4))
            img.putpixel((x, y), c + (255,))
    for x in range(1, 11):  # outside: gold border along the sides and the hem, and below the collar
        for y in range(1, 17):
            if x in (1, 10) or y in (15, 16) or y == 2:
                img.putpixel((x, y), tint(gold, rng.randint(-10, 10) + (12 if y == 2 else 0)) + (255,))
    for x, y in [(4, 6), (6, 5), (8, 6), (4, 7), (5, 7), (6, 7), (7, 7), (8, 7), (4, 8), (5, 8), (6, 8), (7, 8), (8, 8)]:
        img.putpixel((x + 0, y + 1), gold + (255,))  # the crown
    for x, y in [(4, 10), (5, 10), (6, 10), (7, 10), (8, 10)]:
        img.putpixel((x, y), gold_d + (255,))
    img.putpixel((6, 8), rgb("#2fbf63") + (255,))  # a gem in the crown
    for x in range(1, 21):  # the top and bottom edges
        img.putpixel((x, 0), tint(gold if x < 11 else red, 10) + (255,))
    return img


# The wing texture is 64 by 64. Each wing is painted already tipped (the upper one up and out, the lower one down and
# out), so the plates need no tilt of their own and can use their one rotation to swing back from the body instead.
WING_UPPER = [0, 0, 8, 10]   # pixels (0,0)-(64,80) of the 128 texture: the upper wing, base at the bottom left
WING_LOWER = [8, 0, 14, 6]   # pixels (64,0)-(112,48): the lower wing, base at the top left
WING_SWEEP = 22.5            # degrees each wing swings back from the plane of the back, so they open in a V


def wings_model():
    """Four flat wings and nothing else (no spine between them). Each plate stands in the plane of the back and is then
    turned about the vertical by WING_SWEEP where it meets the back, tips away from the body, so from above the wings make a V. Each plate has a
    front and a back face."""
    # A flat plate seen from behind shows its texture mirrored, so each plate's two faces carry mirror image uvs: the
    # wing's outline then matches the plate from both sides (with one uv on both faces, the side away from the viewer
    # showed the wing pointing the wrong way).
    def mirrored(uv):
        return [uv[2], uv[1], uv[0], uv[3]]

    def plate(frm, to, uv, origin):
        return {"from": list(frm), "to": list(to),
                "rotation": {"origin": list(origin), "axis": "y", "angle": WING_SWEEP},
                "faces": {"north": {"uv": list(uv), "texture": "#0"}, "south": {"uv": mirrored(uv), "texture": "#0"}}}

    def flip(el):
        f, t = el["from"], el["to"]
        r = el["rotation"]
        n = {"north": {"uv": mirrored(el["faces"]["north"]["uv"]), "texture": "#0"},
             "south": {"uv": mirrored(el["faces"]["south"]["uv"]), "texture": "#0"}}
        return {"from": [16 - t[0], f[1], f[2]], "to": [16 - f[0], t[1], t[2]], "faces": n,
                "rotation": {"origin": [16 - r["origin"][0], r["origin"][1], r["origin"][2]], "axis": r["axis"], "angle": -r["angle"]}}

    upper = plate((9, 8, 7.7), (25, 28, 7.9), WING_UPPER, (9, 8, 7.8))
    lower = plate((9, -4, 7.75), (21, 8, 7.95), WING_LOWER, (9, 8, 7.8))
    return [upper, lower, flip(upper), flip(lower)]




def paint_leaf_wings():
    """The Fae's wings: two leaves, solid (not see through, unlike the Fairies'), on the same plates as the fairy wings.
    Each is a pointed leaf with a serrated edge, deep green at the base to light green, touched with amber at the tip, a
    pale midrib, side veins angled toward the tip, a darker rim and a few drops of dew. The lower leaf is a lighter,
    yellower green. 128 by 128, the same layout as the fairy wings."""
    size = 128
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rng = random.Random("fae-leaves")

    def leaf(box, base, angle_deg, length, width, stops):
        x0, y0, x1, y1 = box
        ang = math.radians(angle_deg)
        ux, uy = math.cos(ang), -math.sin(ang)
        vx, vy = -uy, ux

        def colour(t):
            for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
                if t <= t1:
                    return mix(rgb(c0), rgb(c1), (t - t0) / (t1 - t0))
            return rgb(stops[-1][1])

        for py in range(y0, y1):
            for px in range(x0, x1):
                rx, ry = px + 0.5 - base[0], py + 0.5 - base[1]
                along, across = rx * ux + ry * uy, rx * vx + ry * vy
                t = along / length
                if t < 0 or t > 1:
                    continue
                half = width * math.sin(math.pi * t) ** 0.85 * (1 - 0.1 * t) + 0.6
                half *= 1 + 0.08 * abs(math.sin(t * 26))  # serrated edge
                d = abs(across)
                if d > half:
                    continue
                c = colour(t)
                c = tint(c, int(10 * (1 - d / max(half, 1))))  # a little lighter toward the midrib
                if d < 0.7 and t < 0.97:
                    c = mix(rgb("#d6ec9a"), c, 0.2)  # the midrib
                else:
                    # side veins: from the midrib out toward the tip, every few texels
                    for k in range(4, int(length), 7):
                        if along > k and abs(d - (along - k) * 0.85) < 0.55 and t < 0.92:
                            c = mix(rgb("#bcd98a"), c, 0.35)
                            break
                if half - d < 1.2:
                    c = mix(rgb("#1f3f1a"), c, 0.3)  # the rim
                elif rng.random() < 0.006:
                    c = rgb("#e8fbff")  # a drop of dew
                img.putpixel((px, py), tint(c, rng.randint(-5, 5)) + (255,))

    leaf((0, 0, 64, 80), (4, 76), 62, 80, 17.0,
         [(0.0, "#2c5f26"), (0.55, "#4f9a3a"), (0.85, "#8cc14a"), (1.0, "#d9a441")])
    leaf((64, 0, 112, 48), (68, 4), -48, 52, 11.0,
         [(0.0, "#3f7a2c"), (0.6, "#7fb84a"), (0.9, "#b9cf55"), (1.0, "#e0b04a")])
    return img


def paint_wings():
    """A 128 by 128 texture holding the two wings. Each is a see through membrane running from its base toward its tip:
    rose at the base, through lilac and aqua, to pale gold at the tip; a luminous rim with a soft violet inner line; a
    branching vein network fanning from the base; a butterfly eye spot on the upper wing; a swallowtail on the lower
    wing; and scattered sparkles. The edge is gently scalloped. Painted from numbers, our own art."""
    size = 128
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rng = random.Random("wings-v2")
    stops = [(0.0, "#ffb3dc"), (0.35, "#d9b3ff"), (0.7, "#a6f0ff"), (1.0, "#fff3c2")]

    def membrane(t):
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t <= t1:
                return mix(rgb(c0), rgb(c1), (t - t0) / (t1 - t0))
        return rgb(stops[-1][1])

    def wing(box, base, angle_deg, length, width, tail=False, eye=False):
        x0, y0, x1, y1 = box
        ang = math.radians(angle_deg)
        ux, uy = math.cos(ang), -math.sin(ang)
        vx, vy = -uy, ux
        mains = [-0.42, -0.2, 0.0, 0.2, 0.42]

        def shape(t):
            if t < 0 or t > 1:
                return -1
            half = width * math.sin(math.pi * min(1.0, t * 1.04)) ** 0.62 * (1 - 0.12 * t) + 1.0
            half *= 1 + 0.05 * math.sin(t * 34)  # scalloped edge
            return half

        for py in range(y0, y1):
            for px in range(x0, x1):
                rx, ry = px + 0.5 - base[0], py + 0.5 - base[1]
                along, across = rx * ux + ry * uy, rx * vx + ry * vy
                t = along / length
                half = shape(t)
                in_tail = False
                if tail and half < 0 and 1.0 <= t <= 1.32:
                    # the swallowtail: a slim streamer past the tip, curling slightly to one side
                    tt = (t - 1.0) / 0.32
                    centre = 3.0 * tt * tt
                    half = 2.6 * (1 - tt) + 0.6
                    across -= centre
                    in_tail = True
                if half < 0 or abs(across) > half:
                    continue
                d = abs(across)
                side = across / max(half, 1)
                col = membrane(min(1.0, t))
                col = mix(col, rgb("#ffffff"), 0.22 * (1 - abs(side)))  # lighter down the middle
                a = 172
                # veins: thin lines fanning from the base (by distance from each line, so they stay one pixel wide),
                # each splitting in two past the middle, and two soft cross veins
                on_vein = False
                if t > 0.06:
                    for m in mains:
                        lines = [m] if t < 0.5 else [m - 0.1 * (t - 0.5) / 0.5, m + 0.1 * (t - 0.5) / 0.5]
                        if any(abs(across - along * math.tan(l)) < 0.55 for l in lines):
                            on_vein = True
                for ct in (0.55, 0.8):
                    if abs(t - ct - 0.04 * side * side) * length < 0.5 and abs(side) < 0.9:
                        on_vein = True
                if on_vein:
                    col, a = mix(rgb("#7a48c0"), col, 0.3), 230
                # the eye spot on the upper wing: gold ring, violet ring, pale centre
                if eye:
                    ex, ey = 0.68 * length, 0.22 * width
                    r = math.hypot((along - ex) * 0.8, across - ey)  # a little longer than wide
                    if r < 7.5:
                        k = r / 7.5
                        if k < 0.3:
                            col, a = mix(rgb("#ffffff"), rgb("#fff3c2"), k / 0.3), 240
                        elif k < 0.62:
                            col, a = mix(rgb("#8d4ee0"), rgb("#5a2ca8"), (k - 0.3) / 0.32), 235
                        elif k < 0.86:
                            col, a = rgb("#f2c84b"), 240
                        else:
                            col, a = mix(rgb("#f2c84b"), col, (k - 0.86) / 0.14), 200
                # the rim: luminous, with a violet line just inside
                edge = half - d
                if edge < 1.4 or (t > 0.985 and not in_tail):
                    col, a = mix(rgb("#fff7ff"), membrane(min(1.0, t)), 0.25), 255
                elif edge < 2.6:
                    col, a = rgb("#9a6ad8"), 240
                elif rng.random() < 0.01:
                    col, a = rgb("#ffffff"), 255  # a sparkle
                img.putpixel((px, py), tint(col, rng.randint(-4, 4)) + (a,))

    # upper wing: base near the bottom left of its box, pointing up and out at 62 degrees
    wing((0, 0, 64, 80), (4, 76), 62, 80, 19.0, eye=True)
    # lower wing: base near the top left, pointing down and out at -48 degrees, ending in a swallowtail
    wing((64, 0, 112, 48), (68, 4), -48, 50, 13.0, tail=True)
    return img


# Models worn on the back. An item display shows a model as it is: its north face (-z) toward the display's -z, its +x
# to the display's +x. A body display sits behind the player with its -z toward their back, so these models are built
# front first as usual and then mirrored front to back (never turned, which would also swap left and right), so their
# outer face looks out from the back while the pauldron's lames still point out over the arm.
BACK_ITEMS = {"forgeman_pauldron", "kingdom_cape", "fairy_wings", "aurelia_banner", "fae_wings"}


def mirror_front_to_back(el):
    """The element reflected across the z = 8 plane: north and south faces swap, and turns about x or y reverse."""
    f, t = el["from"], el["to"]
    out = {"from": [f[0], f[1], 16 - t[2]], "to": [t[0], t[1], 16 - f[2]], "faces": {}}
    swap = {"north": "south", "south": "north"}
    for name, face in el["faces"].items():
        out["faces"][swap.get(name, name)] = face
    if "rotation" in el:
        r = el["rotation"]
        o = list(r["origin"])
        o[2] = 16 - o[2]
        out["rotation"] = {"origin": o, "axis": r["axis"], "angle": -r["angle"] if r["axis"] in ("x", "y") else r["angle"]}
    return out


# ---- the antlers on a helmet ----
# A helmet is drawn by the armour layer only when its equippable component names an equipment asset; any other head item
# is drawn by the head layer as its item model, at 0.625 scale, so a 16 unit model is exactly the 10 pixel helmet shell
# around the 8 pixel head. So the server can serve a copy of the player's helmet without the asset and with one of these
# models: the vanilla helmet shell, textured from the vanilla armour texture, with the antlers on top. One model per
# helmet material. The vanilla armour textures are added to the items atlas by assets/minecraft/atlases/items.json
# (Polymer merges atlas files), so nothing of Mojang's is copied into the pack.
HELMET_MATERIALS = ["leather", "chainmail", "iron", "gold", "diamond", "netherite", "turtle_scute", "copper"]
HELMETS = os.path.join(ASSETS, "items")
ATLAS = os.path.join(HERE, "..", "examples", "assets", "minecraft", "atlases", "items.json")

# The head part of the vanilla armour texture (64 by 32, box layout at 0,0 for an 8 by 8 by 8 box), as model uvs (0 to 16):
# a pixel x is u = x / 4, a pixel y is v = y / 2. The front, sides and back keep the texture's own orientation; the top is
# turned so its front edge meets the front face.
HELMET_UV = {
    "north": [2, 4, 4, 8],   # front, pixels (8,8)-(16,16)
    "east": [0, 4, 2, 8],    # the wearer's right, pixels (0,8)-(8,16)
    "west": [4, 4, 6, 8],    # the wearer's left, pixels (16,8)-(24,16)
    "south": [6, 4, 8, 8],   # back, pixels (24,8)-(32,16)
    "up": [4, 4, 2, 0],      # top, pixels (8,0)-(16,8)
    "down": [6, 0, 4, 4],    # bottom, pixels (16,0)-(24,8)
}


def helmet_shell(texture_key, tinted, grow=0.0):
    faces = {}
    for face, uv in HELMET_UV.items():
        faces[face] = {"uv": list(uv), "texture": texture_key}
        if tinted:
            faces[face]["tintindex"] = 0
    return {"from": [-grow, -grow, -grow], "to": [16 + grow, 16 + grow, 16 + grow], "faces": faces}


def write_wing_halves():
    """Each pair of wings as two models, the wearer's left pair and right pair, so each side can flap on its own
    (WornDisplays serves them as two displays turning about the spine). Same plates and texture, split at x = 8."""
    for wings in ("fairy_wings", "fae_wings"):
        write_halves(wings)


def write_halves(wings):
    with open(os.path.join(MODELS, wings + ".json"), encoding="utf8") as f:
        whole = json.load(f)
    for side, keep in (("left", lambda el: el["to"][0] <= 8), ("right", lambda el: el["from"][0] >= 8)):
        name = f"{wings}_{side}"
        model = dict(whole, elements=[el for el in whole["elements"] if keep(el)])
        with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump(model, f, indent=2)
            f.write("\n")
        os.makedirs(HELMETS, exist_ok=True)
        with open(os.path.join(HELMETS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump({"model": {"type": "minecraft:model", "model": f"kingdoms:item/worn/{name}"}}, f, indent=2)
            f.write("\n")
    print(wings, "split into left and right halves")


def write_helmets(head_items):
    """For each head item (name to its baked elements), one "on a helmet" model per material and its client item."""
    os.makedirs(HELMETS, exist_ok=True)
    sources = []
    for mat in HELMET_MATERIALS:
        sources.append({"type": "minecraft:single", "resource": f"minecraft:entity/equipment/humanoid/{mat}"})
        if mat == "leather":
            sources.append({"type": "minecraft:single", "resource": "minecraft:entity/equipment/humanoid/leather_overlay"})
    for head, head_elements in head_items.items():
        for mat in HELMET_MATERIALS:
            write_helmet(head, head_elements, mat)
    os.makedirs(os.path.dirname(ATLAS), exist_ok=True)
    with open(ATLAS, "w", encoding="utf8", newline="\n") as f:
        json.dump({"sources": sources}, f, indent=2)
        f.write("\n")
    print(", ".join(head_items), "on", len(HELMET_MATERIALS), "helmet materials, atlas sources", len(sources))


def write_helmet(head, head_elements, mat):
    """One "head item on a helmet" model (the vanilla helmet shell with the head item on top) and its client item."""
    textures = {"0": f"kingdoms:item/worn/{head}", "1": f"minecraft:entity/equipment/humanoid/{mat}",
                "particle": f"minecraft:entity/equipment/humanoid/{mat}"}
    elements = [helmet_shell("#1", mat == "leather")]
    if mat == "leather":
        # the undyed overlay (the leather helmet's trim), a hair outside the dyed shell so the two never flicker
        textures["2"] = "minecraft:entity/equipment/humanoid/leather_overlay"
        elements.append(helmet_shell("#2", False, grow=0.02))
    # the wreath sits on the head's top; on a helmet it goes on the helmet's top (the shell is 16 high), not inside it
    lifted = []
    for el in head_elements:
        if el.get("name") == "wreath":
            el = json.loads(json.dumps(el))
            el["from"][1] += 16 - WREATH_Y + 0.05
            el["to"][1] += 16 - WREATH_Y + 0.05
            if "rotation" in el:
                el["rotation"]["origin"][1] += 16 - WREATH_Y + 0.05
        lifted.append(el)
    model = {"textures": textures, "elements": elements + lifted, "display": DISPLAY}
    name = f"{head}_on_{mat}"
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
        json.dump(model, f, indent=2)
        f.write("\n")
    item = {"model": {"type": "minecraft:model", "model": f"kingdoms:item/worn/{name}"}}
    if mat == "leather":
        item["model"]["tints"] = [{"type": "minecraft:dye", "default": -6265536}]
    with open(os.path.join(HELMETS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
        json.dump(item, f, indent=2)
        f.write("\n")


def main():
    os.makedirs(MODELS, exist_ok=True)
    os.makedirs(TEXTURES, exist_ok=True)
    built = {}
    for name, build in {"forgeman_pauldron": pauldron, "antler_circlet": circlet, "tidal_gills": gills, "dwarf_beard": beard}.items():
        built[name] = bake(name, build())
    built["kingdom_cape"] = (cape_model(), paint_cape())
    built["aurelia_banner"] = banner_model()
    built["fairy_wings"] = (wings_model(), paint_wings())
    built["fae_wings"] = (wings_model(), paint_leaf_wings())
    for name, (elements, texture) in built.items():
        if name in BACK_ITEMS:
            elements = [mirror_front_to_back(e) for e in elements]
        model = {
            "textures": {"0": f"kingdoms:item/worn/{name}", "particle": f"kingdoms:item/worn/{name}",
                         **({"1": "kingdoms:item/worn/aurelia_banner_cloth"} if name == "aurelia_banner" else {})},
            "elements": elements,
            "display": DISPLAY,
        }
        with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump(model, f, indent=2)
            f.write("\n")
        texture.save(os.path.join(TEXTURES, name + ".png"))
        print(name, len(elements), "elements,", f"{texture.width}x{texture.height}")
    write_wing_halves()
    paint_beard_icon().save(os.path.join(ASSETS, "textures", "item", "dwarf_beard.png"))
    paint_leaf_wings().crop((0, 0, 112, 80)).resize((22, 16), Image.LANCZOS).crop((3, 0, 19, 16)).save(
        os.path.join(ASSETS, "textures", "item", "fae_wings.png"))
    paint_banner_cloth().save(BANNER_CLOTH)
    paint_banner_icon().save(os.path.join(ASSETS, "textures", "item", "aurelia_banner.png"))
    write_helmets({"antler_circlet": built["antler_circlet"][0], "dwarf_beard": built["dwarf_beard"][0]})


if __name__ == "__main__":
    main()
