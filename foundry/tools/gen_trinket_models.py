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
    "antler_tip": dict(base="#d8c7a0", pattern="ringed", noise=7, bevel=24),
    "shell": dict(base="#3aa4b6", pattern="ridges", noise=6, bevel=30),
    "slit": dict(base="#173946", pattern="plain", noise=4, bevel=10),
    "fin": dict(base="#86d8e2", pattern="fin", noise=4, bevel=0, alpha=225),
    "pearl": dict(base="#e6f0f2", pattern="sheen", noise=5, bevel=24),
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
            elif p == "dot":
                cx, cy = (w - 1) / 2, (h - 1) / 2
                d = math.hypot(x - cx, y - cy) / max(w / 2, 1)
                c = mix(rgb("#ffe0a0"), rgb("#7a4f1c"), min(1, d))
            pixel = tint(c, k)
            if m["bevel"] and w >= 3 and h >= 3:
                if y == 0 or x == 0:
                    pixel = tint(pixel, m["bevel"] if y == 0 else m["bevel"] // 2)
                elif y == h - 1 or x == w - 1:
                    pixel = tint(pixel, -m["bevel"] if y == h - 1 else -m["bevel"] // 2)
            img.putpixel((x, y), pixel + (alpha,))
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


def circlet():
    """A gold band set high on the head with a gem and leaves at the front, and two branching antlers."""
    band = [
        part((0.5, 10, 0.5), (15.5, 12, 1.5), "gold"),
        part((0.5, 10, 14.5), (15.5, 12, 15.5), "gold"),
        part((0.5, 10, 1.5), (1.5, 12, 14.5), "gold"),
        part((14.5, 10, 1.5), (15.5, 12, 14.5), "gold"),
        part((6.4, 9.4, 0.0), (9.6, 12.6, 0.6), "gold_dark"),
        part((7.1, 10.1, -0.4), (8.9, 11.9, 0.1), "gem"),
    ]
    left_leaf = part((4.2, 10.3, 0.2), (6.2, 11.7, 0.5), "leaf", rot=("z", 22.5, (5.2, 11, 0.35)))
    left = [
        part((2.4, 12, 6.6), (3.8, 15.5, 8.0), "antler", rot=("z", 22.5, (3.1, 12, 7.3))),
        part((1.2, 15, 6.7), (2.4, 19, 7.9), "antler", rot=("z", 22.5, (1.8, 15, 7.3))),
        part((0.2, 18.5, 6.8), (1.2, 21.5, 7.8), "antler_tip", rot=("z", -22.5, (0.7, 18.5, 7.3))),
        part((2.6, 15.2, 3.6), (3.4, 16.2, 6.8), "antler_tip", rot=("x", -22.5, (3, 15.7, 6.8))),
        part((-2.0, 16.6, 6.9), (1.4, 17.6, 7.7), "antler_tip", rot=("z", -22.5, (1.4, 17.1, 7.3))),
        part((1.6, 18.8, 8.0), (2.4, 20.6, 10.6), "antler_tip", rot=("x", 22.5, (2, 18.8, 8.0))),
    ]
    return band + [left_leaf, mirror(left_leaf)] + left + [mirror(p) for p in left]


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
WING_UPPER = [0, 0, 8, 10]   # pixels (0,0)-(32,40): the upper wing, base at the bottom left
WING_LOWER = [8, 0, 14, 6]   # pixels (32,0)-(56,24): the lower wing, base at the top left
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




def paint_wings():
    """A 64 by 64 texture holding the two wings, each a rounded teardrop of see through membrane that runs from its base
    toward its tip at an angle: rose at the base through lilac to pale blue at the tip, a darker rim with a lit inner
    edge, branching veins fanning from the base and a few sparkles."""
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rng = random.Random("wings")
    rim, rim_light, vein = rgb("#7a4fb0"), rgb("#c9a8f0"), rgb("#f4e6ff")

    def membrane(t):
        a = mix(rgb("#f2b6d8"), rgb("#c6a6ee"), min(1, t * 1.6))
        return mix(a, rgb("#a9dcf5"), max(0, t * 1.6 - 0.6)) if t > 0.4 else a

    def wing(box, base, angle_deg, length, width):
        x0, y0, x1, y1 = box
        ang = math.radians(angle_deg)
        ux, uy = math.cos(ang), -math.sin(ang)  # along the wing (pixel y grows downward)
        vx, vy = -uy, ux                         # across the wing
        for py in range(y0, y1):
            for px in range(x0, x1):
                rx, ry = px + 0.5 - base[0], py + 0.5 - base[1]
                along = rx * ux + ry * uy
                across = rx * vx + ry * vy
                t = along / length
                if t < 0 or t > 1:
                    continue
                half = width * math.sin(math.pi * min(1.0, t * 1.06)) ** 0.65 * (1 - 0.18 * t) + 0.9
                d = abs(across)
                if d > half:
                    continue
                angle = math.atan2(across, max(1.0, along))
                on_vein = t > 0.08 and any(abs(angle - a) < 0.04 + 0.02 * (1 - t) for a in (-0.42, -0.2, 0.0, 0.2, 0.42))
                on_cross = t > 0.35 and int(along) % 7 == 0 and d < half * 0.85
                if half - d < 1.1 or t > 0.975:
                    c, a = rim, 255
                elif half - d < 2.0:
                    c, a = rim_light, 235
                elif on_vein or on_cross:
                    c, a = vein, 230
                else:
                    c, a = tint(membrane(t), rng.randint(-6, 6)), 150
                    if rng.random() < 0.012:
                        c, a = rgb("#ffffff"), 255
                img.putpixel((px, py), c + (a,))

    # upper wing: base near the bottom left of its box, pointing up and out at 62 degrees
    wing((0, 0, 32, 40), (2, 38), 62, 40, 9.5)
    # lower wing: base near the top left of its box, pointing down and out at -48 degrees
    wing((32, 0, 56, 24), (34, 2), -48, 29, 7.0)
    return img


# Models worn on the back. An item display shows a model as it is: its north face (-z) toward the display's -z, its +x
# to the display's +x. A body display sits behind the player with its -z toward their back, so these models are built
# front first as usual and then mirrored front to back (never turned, which would also swap left and right), so their
# outer face looks out from the back while the pauldron's lames still point out over the arm.
BACK_ITEMS = {"forgeman_pauldron", "kingdom_cape", "fairy_wings"}


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


def main():
    os.makedirs(MODELS, exist_ok=True)
    os.makedirs(TEXTURES, exist_ok=True)
    built = {}
    for name, build in {"forgeman_pauldron": pauldron, "antler_circlet": circlet, "tidal_gills": gills}.items():
        built[name] = bake(name, build())
    built["kingdom_cape"] = (cape_model(), paint_cape())
    built["fairy_wings"] = (wings_model(), paint_wings())
    for name, (elements, texture) in built.items():
        if name in BACK_ITEMS:
            elements = [mirror_front_to_back(e) for e in elements]
        model = {
            "textures": {"0": f"kingdoms:item/worn/{name}", "particle": f"kingdoms:item/worn/{name}"},
            "elements": elements,
            "display": DISPLAY,
        }
        with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump(model, f, indent=2)
            f.write("\n")
        texture.save(os.path.join(TEXTURES, name + ".png"))
        print(name, len(elements), "elements,", f"{texture.width}x{texture.height}")


if __name__ == "__main__":
    main()
