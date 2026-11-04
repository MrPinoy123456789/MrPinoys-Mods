#!/usr/bin/env python3
"""The wild lodestone look: a carved standing stone (the plinth) and four floating crystals that tell the dungeon's state.

Pocket Dungeons draws these with item displays over the bedrock block that is a wild entrance or an Exit: the plinth
laid exactly over the block, one crystal hovering above it, spinning and bobbing. They are ordinary Foundry items, so
the pack carries them and the display code looks them up by id.

    python tools/gen_lodestone_models.py            models, textures, icons and the preview sheet
    python tools/gen_lodestone_models.py --no-preview

It writes, under examples/assets/kingdoms:
  models/item/held/lodestone_<name>.json    the 3D models (each item's held_model)
  textures/item/held/lodestone_<name>.png   their baked textures (every face its own painted tile, as the weapons)
  textures/item/lodestone_<name>.png        the 16 by 16 flat icons (each item's texture, and Bedrock's)
and tools/previews/lodestone.png (plus lodestone_2x.png): the plinth with each crystal over it, three quarters view.

Geometry is in the usual model space (16 units a block). The plinth fills the block, 0 to 16 on every axis, and a hair
over (GROW) so its faces never fight the bedrock's under them; its cap, footing, gold band and corner posts stand
out a little further, never in, so no bedrock shows anywhere. A crystal is centred on (8, 8, 8), so a display that
spins it turns it about its own axis. Faces are painted by the trinket generator's bake (gen_trinket_models.py) with
painters of our own that know which face they are painting (see bake below). Runes and lit crystals carry
light_emission 15, so they glow in the dark the way the runes on the plinth should.

Display transforms. Foundry's client item definition draws the held model in every context except none (none falls
back to the flat icon), so a display must use FIXED. The fixed transform here is a 180 degree turn about y and
nothing else: an item display renderer turns its item half a turn itself, so the two cancel and the model is drawn at
true size, unrotated, its centre (8, 8, 8) on the display's position. The other contexts are set for an item in a hand,
an inventory or on the ground.
"""
import json
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageEnhance

import gen_trinket_models as g
import preview_model as pm

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets", "kingdoms")
MODELS = os.path.join(ASSETS, "models", "item", "held")
TEXTURES = os.path.join(ASSETS, "textures", "item", "held")
ICONS = os.path.join(ASSETS, "textures", "item")
PREVIEWS = os.path.join(HERE, "previews")

DENSITY = 4          # texels per model unit, as the weapons
GROW = 0.03          # how far the plinth's body stands past the block on every side
GLOW = 15            # light_emission of the runes and the lit crystals

rgb, mix, tint, clamp = g.rgb, g.mix, g.tint, g.clamp


# ---- painting: a painter gets the face it paints as well as the tile size ----

PAINTERS = {}   # material name -> painter(face, w, h, rng) returning an RGBA tile


def painter(name):
    def register(fn):
        PAINTERS[name] = fn
        return fn
    return register


def bake(name, parts):
    """g.bake, with our painters. g.bake paints the tiles in a fixed order (each part, then each of its faces), so the
    face of every tile is known before it is painted; any material we do not paint goes to the trinket painter."""
    queue = iter([(p, face) for p in parts for face in (p["only"] or g.FACES)])
    plain = g.paint_tile

    def paint(material, w, h, rng):
        p, face = next(queue)
        assert p["material"] == material, (p["material"], material)
        fn = PAINTERS.get(material)
        return fn(face, w, h, rng) if fn else plain(material, w, h, rng)

    g.paint_tile = paint
    try:
        elements, texture = g.bake(name, parts, density=DENSITY)
    finally:
        g.paint_tile = plain
    for p, el in zip(parts, elements):
        if p.get("glow"):
            el["light_emission"] = p["glow"]
    return elements, texture


def mottle(w, h, rng, cell=5.0, amp=10.0):
    """Soft blotches: a coarse random grid smoothed up to the tile, as a w by h list of offsets in -amp to amp."""
    gw, gh = max(2, int(w / cell) + 2), max(2, int(h / cell) + 2)
    small = Image.new("L", (gw, gh))
    small.putdata([rng.randint(0, 255) for _ in range(gw * gh)])
    big = small.resize((w, h), Image.BICUBIC)
    px = big.load()
    return [[(px[x, y] - 128) / 128 * amp for x in range(w)] for y in range(h)]


def stone(w, h, rng, base, amp=8, grain=3, speck=None):
    """Polished dark stone: blotchy, a fine grain, a few pale specks. Returns a list of rows of colours."""
    m = mottle(w, h, rng, amp=amp)
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            c = tint(base, m[y][x] + rng.randint(-grain, grain))
            if speck and rng.random() < 0.012:
                c = mix(c, speck, 0.45)
            row.append(c)
        rows.append(row)
    return rows


def image(rows, alpha=None):
    h, w = len(rows), len(rows[0])
    img = Image.new("RGBA", (w, h))
    img.putdata([c + ((255 if alpha is None else alpha[y][x]),) for y, r in enumerate(rows) for x, c in enumerate(r)])
    return img


# ---- the plinth ----

BLACKSTONE = rgb("#2f2c36")
BLACKSTONE_LIGHT = rgb("#423e4b")
BLACKSTONE_DARK = rgb("#1d1b22")
GROUT = rgb("#141217")
SPECK = rgb("#8e8aa0")
GOLD = rgb("#f2b636")
GOLD_LIGHT = rgb("#ffe7a0")
GOLD_DARK = rgb("#9a6514")
EMBER = rgb("#ff9a2e")
RUNE_CORE = rgb("#fff6cc")
RUNE_EDGE = rgb("#ffb22a")

BODY_LO, BODY_HI = 2.5, 13.3     # the body between the footing and the gold band under the cap
PANEL = (4.25, 11.75, 4.0, 12.0)  # the rune panel on each side, along the face (u) and up it (y), in model units
RUNE_OUT = 0.07                   # how far a rune's plane stands in front of the body


def rune_strokes(side):
    """The rune on one side, as polylines in 0..1 across the panel (left to right as seen) and 0..1 down it. Four old
    shapes, straight strokes only, like letters cut with a chisel: the road (an arrow up a stave), the home (a diamond
    on crossed legs), the star (a stave crossed and forked) and the day (two staves crossed between)."""
    if side == "north":      # the road: a stave with two chevrons rising up it
        return [[(0.5, 0.06), (0.5, 0.94)], [(0.16, 0.36), (0.5, 0.08), (0.84, 0.36)],
                [(0.2, 0.62), (0.5, 0.38), (0.8, 0.62)]]
    if side == "east":       # the home: a diamond standing on two crossed legs
        return [[(0.5, 0.06), (0.86, 0.36), (0.5, 0.66), (0.14, 0.36), (0.5, 0.06)],
                [(0.5, 0.66), (0.14, 0.94)], [(0.5, 0.66), (0.86, 0.94)]]
    if side == "south":      # the star: a stave crossed, forked up at the head and down at the foot
        return [[(0.5, 0.06), (0.5, 0.94)], [(0.12, 0.5), (0.88, 0.5)], [(0.16, 0.08), (0.5, 0.34), (0.84, 0.08)],
                [(0.16, 0.92), (0.5, 0.66), (0.84, 0.92)]]
    return [[(0.12, 0.06), (0.12, 0.94)], [(0.88, 0.06), (0.88, 0.94)],                # the day: two staves crossed
            [(0.12, 0.06), (0.88, 0.94)], [(0.88, 0.06), (0.12, 0.94)]]


def rune_mask(side, w, h):
    """The rune drawn at w by h texels: 2 for the bright core of a stroke, 1 for its rim, 0 elsewhere."""
    big = Image.new("L", (w * 4, h * 4), 0)
    d = ImageDraw.Draw(big)
    for line in rune_strokes(side):
        pts = [(x * (w - 3) * 4 + 6, y * (h - 3) * 4 + 6) for x, y in line]
        d.line(pts, fill=255, width=7, joint="curve")
        for p in pts:
            d.ellipse([p[0] - 3, p[1] - 3, p[0] + 3, p[1] + 3], fill=255)
    core = big.resize((w, h), Image.BOX).point(lambda v: 255 if v > 90 else 0)
    px = core.load()
    out = [[0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if px[x, y]:
                out[y][x] = 2
            elif any(0 <= x + dx < w and 0 <= y + dy < h and px[x + dx, y + dy]
                     for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
                out[y][x] = 1
    return out


def rune_distance(side, w, h):
    """For each texel of the rune's plane, its distance in texels to the nearest stroke (0 on a stroke)."""
    mask = rune_mask(side, w, h)
    on = [(x, y) for y in range(h) for x in range(w) if mask[y][x]]
    return [[min(math.hypot(x - a, y - b) for a, b in on) for x in range(w)] for y in range(h)]


def rune_plane_size():
    u0, u1, y0, y1 = PANEL
    return round((u1 - u0) * DENSITY), round((y1 - y0) * DENSITY)


def make_rune_painter(side):
    @painter("rune_" + side)
    def paint(face, w, h, rng):
        mask = rune_mask(side, w, h)
        rows, alpha = [], []
        for y in range(h):
            row, a = [], []
            for x in range(w):
                v = mask[y][x]
                # the stroke burns white hot along its middle, gold at its rim
                c = mix(RUNE_CORE, RUNE_EDGE, 0.15 + 0.2 * (y / max(h - 1, 1))) if v == 2 else RUNE_EDGE
                row.append(tint(c, rng.randint(-4, 4)))
                a.append(255 if v else 0)
            rows.append(row)
            alpha.append(a)
        return image(rows, alpha)
    return paint


for _side in ("north", "east", "south", "west"):
    make_rune_painter(_side)


def make_body_painter(side):
    """One side of the body: polished blackstone, darker toward the foot, a sunk panel framed by a fine gold inlay, and
    the stone around the rune warmed by its light, so the rune reads as glowing even in daylight."""
    @painter("body_" + side)
    def paint(face, w, h, rng):
        rows = stone(w, h, rng, BLACKSTONE, amp=7, speck=SPECK)
        rw, rh = rune_plane_size()
        dist = rune_distance(side, rw, rh)
        u0, u1, y0, y1 = PANEL
        span = 16 + 2 * GROW
        for y in range(h):
            my = BODY_HI - (y + 0.5) / h * (BODY_HI - BODY_LO)          # model y of this texel
            for x in range(w):
                mu = -GROW + (x + 0.5) / w * span                        # model units from the face's left edge
                c = tint(rows[y][x], -10 * (BODY_HI - my) / (BODY_HI - BODY_LO))
                inner = u0 - 0.35 <= mu <= u1 + 0.35 and y0 - 0.35 <= my <= y1 + 0.35
                frame = u0 - 0.85 <= mu <= u1 + 0.85 and y0 - 0.85 <= my <= y1 + 0.85
                if inner:
                    c = tint(mix(c, BLACKSTONE_DARK, 0.55), 0)
                    if my > y1 + 0.1:                                    # the shadowed top lip of the recess
                        c = tint(c, -8)
                    elif my < y0 - 0.1:                                  # the lit bottom lip
                        c = tint(BLACKSTONE_LIGHT, 4)
                elif frame and (abs(mu - (u0 - 0.6)) < 0.13 or abs(mu - (u1 + 0.6)) < 0.13
                                or abs(my - (y0 - 0.6)) < 0.13 or abs(my - (y1 + 0.6)) < 0.13):
                    c = tint(mix(GOLD_DARK, GOLD, 0.55), rng.randint(-6, 6))   # the gold inlay round the panel
                # the warm light the rune throws on the stone near it
                rx = (mu - u0) * DENSITY - 0.5
                ry = (y1 - my) * DENSITY - 0.5
                ix, iy = int(round(rx)), int(round(ry))
                if 0 <= ix < rw and 0 <= iy < rh:
                    d = dist[iy][ix]
                else:
                    cx, cy = min(max(ix, 0), rw - 1), min(max(iy, 0), rh - 1)
                    d = dist[cy][cx] + math.hypot(ix - cx, iy - cy)
                warm = math.exp(-d / 3.2) * 0.62 + math.exp(-d / 11.0) * 0.12
                rows[y][x] = mix(c, EMBER, warm)
        # corner studs of gold at the panel's frame
        for cu, cy_ in ((u0 - 0.6, y0 - 0.6), (u1 + 0.6, y0 - 0.6), (u0 - 0.6, y1 + 0.6), (u1 + 0.6, y1 + 0.6)):
            sx = int((cu + GROW) / span * w)
            sy = int((BODY_HI - cy_) / (BODY_HI - BODY_LO) * h)
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    if 0 <= sx + dx < w and 0 <= sy + dy < h:
                        rows[sy + dy][sx + dx] = GOLD_LIGHT if dx == dy == 0 else GOLD
        return image(rows)
    return paint


for _side in ("north", "east", "south", "west"):
    make_body_painter(_side)


@painter("post")
def paint_post(face, w, h, rng):
    """A corner post: a shade lighter than the body, fluted down its middle, its arrises caught by the light."""
    rows = stone(w, h, rng, tint(BLACKSTONE, 8), amp=6, speck=SPECK)
    for y in range(h):
        for x in range(w):
            c = tint(rows[y][x], -10 * y / h)
            if w >= 6:
                mid = (w - 1) / 2
                if abs(x - mid) < 0.8:
                    c = tint(c, -20)                                      # the flute
                elif abs(x - mid - 1.3) < 0.6:
                    c = tint(c, 12)                                       # its lit edge
                if x == 0:
                    c = tint(c, 18)
                elif x == w - 1:
                    c = tint(c, -14)
            if y == 0:
                c = tint(c, -12)
            rows[y][x] = c
    return image(rows)


@painter("footing")
def paint_footing(face, w, h, rng):
    """The footing: rougher, darker stone, its top edge worn pale."""
    rows = stone(w, h, rng, tint(BLACKSTONE_DARK, 6), amp=10, grain=5, speck=SPECK)
    for y in range(h):
        for x in range(w):
            if face in g.FACES[:4] and y == 0:
                rows[y][x] = tint(rows[y][x], 22)
            elif face in g.FACES[:4] and y == h - 1:
                rows[y][x] = tint(rows[y][x], -12)
    return image(rows)


@painter("cap")
def paint_cap(face, w, h, rng):
    """The cap's sides and underside: polished, a bright top arris, a cut line along the middle."""
    rows = stone(w, h, rng, tint(BLACKSTONE, 4), amp=6, speck=SPECK)
    if face in ("up", "down"):
        return image([[tint(c, -16) for c in r] for r in rows])
    for y in range(h):
        for x in range(w):
            c = rows[y][x]
            if y == 0:
                c = tint(c, 30)
            elif y == 1:
                c = tint(c, 14)
            elif y == h - 1:
                c = tint(c, -16)
            elif abs(y - h * 0.55) < 0.6:
                c = tint(c, -18)
            rows[y][x] = c
    return image(rows)


@painter("band")
def paint_band(face, w, h, rng):
    """The gold band under the cap: bright metal with a running chevron engraved along it."""
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            k = int(16 * math.cos(x / max(w, 1) * math.pi * 6)) + rng.randint(-5, 5)
            c = tint(GOLD, k)
            if face in g.FACES[:4] and h >= 2 and ((x + (y if (x // 4) % 2 else h - 1 - y)) % 4 == 0):
                c = tint(c, -40)
            if y == 0:
                c = mix(c, GOLD_LIGHT, 0.5)
            row.append(c)
        rows.append(row)
    return image(rows)


@painter("cap_top")
def paint_cap_top(face, w, h, rng):
    """The top of the stone: square deepslate style tiles, a gold ring inlaid round the dais and four gold points, the
    compass a lodestone is named for."""
    rows = stone(w, h, rng, tint(BLACKSTONE, 6), amp=8, speck=SPECK)
    lo, span = -0.5, 17.0
    for y in range(h):
        for x in range(w):
            mx = lo + (x + 0.5) / w * span
            mz = lo + (y + 0.5) / h * span
            c = rows[y][x]
            tx, tz = (mx + 0.5) % 4.25, (mz + 0.5) % 4.25
            if tx < 0.28 or tz < 0.28:
                c = mix(c, GROUT, 0.7)                                    # grout between the tiles
            elif tx > 3.9 or tz > 3.9:
                c = tint(c, 8)
            r = math.hypot(mx - 8, mz - 8)
            ang = math.atan2(mz - 8, mx - 8)
            if abs(r - 6.1) < 0.3:
                c = tint(mix(GOLD, GOLD_LIGHT, 0.2 if r > 6.1 else 0.0), rng.randint(-6, 6))
            elif abs(r - 6.1) < 0.55:
                c = mix(c, GROUT, 0.6)                                    # the channel the ring is set in
            # four gold points, north, east, south and west, from the dais to the ring
            for a in (0, math.pi / 2, math.pi, -math.pi / 2):
                da = abs((ang - a + math.pi) % (2 * math.pi) - math.pi)
                if 3.4 < r < 5.8 and da * r < 0.9 * (5.8 - r) / 2.4:
                    c = tint(mix(GOLD, GOLD_LIGHT, 0.35 if da < 0.08 else 0.0), rng.randint(-5, 5))
            # small points on the diagonals
            for a in (math.pi / 4, 3 * math.pi / 4, -math.pi / 4, -3 * math.pi / 4):
                da = abs((ang - a + math.pi) % (2 * math.pi) - math.pi)
                if 3.6 < r < 5.0 and da * r < 0.45 * (5.0 - r) / 1.4:
                    c = tint(GOLD_DARK, 20 + rng.randint(-5, 5))
            # warmth thrown down by the socket and the crystal over it
            c = mix(c, EMBER, 0.28 * math.exp(-max(0.0, r - 3.0) / 2.0))
            rows[y][x] = c
    return image(rows)


@painter("dais")
def paint_dais(face, w, h, rng):
    """The low round dais the crystal hangs over: dark stone rimmed in gold, its top lit from the socket."""
    rows = stone(w, h, rng, tint(BLACKSTONE, 10), amp=6, speck=SPECK)
    for y in range(h):
        for x in range(w):
            c = rows[y][x]
            if face in ("up", "down"):
                if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                    c = tint(GOLD, rng.randint(-8, 8))
                else:
                    cx, cy = (w - 1) / 2, (h - 1) / 2
                    d = math.hypot(x - cx, y - cy) / max(w / 2, 1)
                    c = mix(c, EMBER, 0.45 * max(0.0, 1 - d))
            else:
                c = tint(GOLD, -10 + rng.randint(-6, 6)) if y == 0 else tint(c, -6)
            rows[y][x] = c
    return image(rows)


@painter("socket")
def paint_socket(face, w, h, rng):
    """The socket in the dais: a coal of light."""
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            cx, cy = (w - 1) / 2, (h - 1) / 2
            d = math.hypot(x - cx, y - cy) / max(max(w, h) / 2, 1)
            row.append(tint(mix(RUNE_CORE, RUNE_EDGE, min(1.0, d * 1.1)), rng.randint(-4, 4)))
        rows.append(row)
    return image(rows)


def plinth():
    """The standing stone: a footing in two steps, a body with four fluted corner posts and a rune panel on each side,
    a gold band, an overhanging cap with a compass inlaid on its top, and a little dais the crystal hangs over."""
    e = GROW
    sides = {"north": ((-e, BODY_LO, -e), (16 + e, BODY_HI, 16 + e)),
             "east": ((-e, BODY_LO, -e), (16 + e, BODY_HI, 16 + e)),
             "south": ((-e, BODY_LO, -e), (16 + e, BODY_HI, 16 + e)),
             "west": ((-e, BODY_LO, -e), (16 + e, BODY_HI, 16 + e))}
    parts = [g.part(f, t, "body_" + s, only=[s]) for s, (f, t) in sides.items()]
    parts += [
        g.part((-0.5, 0, -0.5), (16.5, 1.3, 16.5), "footing"),
        g.part((-0.3, 1.3, -0.3), (16.3, BODY_LO, 16.3), "footing", only=["north", "east", "south", "west", "up"]),
    ]
    for x0, z0 in ((-0.25, -0.25), (13.75, -0.25), (-0.25, 13.75), (13.75, 13.75)):
        parts.append(g.part((x0, BODY_LO, z0), (x0 + 2.5, BODY_HI, z0 + 2.5), "post",
                            only=["north", "east", "south", "west"]))
    parts += [
        g.part((-0.38, BODY_HI, -0.38), (16.38, 13.85, 16.38), "band", only=["north", "east", "south", "west", "down"]),
        g.part((-0.5, 13.85, -0.5), (16.5, 16 + e, 16.5), "cap", only=["north", "east", "south", "west", "down"]),
        g.part((-0.5, 13.85, -0.5), (16.5, 16 + e, 16.5), "cap_top", only=["up"]),
        g.part((5.0, 16 + e, 5.0), (11.0, 16.55, 11.0), "dais"),
        g.part((4.4, 16 + e, 6.2), (11.6, 16.5, 9.8), "dais", only=["east", "west", "up"]),
        g.part((6.2, 16 + e, 4.4), (9.8, 16.5, 11.6), "dais", only=["north", "south", "up"]),
    ]
    socket = g.part((7.0, 16.55, 7.0), (9.0, 16.75, 9.0), "socket", only=["north", "east", "south", "west", "up"])
    socket["glow"] = GLOW
    parts.append(socket)
    # the runes: one plane in front of each side's panel, facing out
    u0, u1, y0, y1 = PANEL
    d = e + RUNE_OUT
    planes = {
        # seen from the north, the face's left is +x; from the east, +z; from the south, -x; from the west, -z
        "north": ((16 - u1, y0, -d), (16 - u0, y1, -d)),
        "south": ((u0, y0, 16 + d), (u1, y1, 16 + d)),
        "east": ((16 + d, y0, 16 - u1), (16 + d, y1, 16 - u0)),
        "west": ((-d, y0, u0), (-d, y1, u1)),
    }
    for s, (f, t) in planes.items():
        p = g.part(f, t, "rune_" + s, only=[s])
        p["glow"] = GLOW
        parts.append(p)
    return parts


# ---- the crystals ----

PALETTES = {
    # hi: the lit facets and ledges, mid: the body, lo: the shadowed foot, vein: a streak of the second colour,
    # ring: the halo's metal, ring_hi: its lit edge
    "ready": dict(hi="#fff7d2", mid="#ffc93f", lo="#c4680c", vein="#ffe58a", ring="#f2b636", ring_hi="#fff0b0", glow=GLOW),
    "ominous": dict(hi="#e6c8ff", mid="#8a3fd6", lo="#3a1670", vein="#3fe6d4", ring="#2fc9bb", ring_hi="#b6fff6", glow=GLOW),
    "dim": dict(hi="#a9a8ad", mid="#6f6e75", lo="#3c3b41", vein="#2a292e", ring="#55545b", ring_hi="#8a8990", glow=0),
    "exit": dict(hi="#ffffff", mid="#c4f5d0", lo="#5fbf86", vein="#f0fff4", ring="#e8fff0", ring_hi="#ffffff", glow=GLOW),
}

CRYSTAL_LO, CRYSTAL_HI = 2.0, 14.0     # 12 tall, centred on y 8
WAIST_LO, WAIST_HI = 6.4, 7.8          # the widest band; the upper point is the longer one, so the stone reads as rising
WAIST = 3.0                            # the half width of the upright square at the waist (6 across the flats)
TIP = 0.35                             # the half width at either point


def half_width(y):
    """The crystal's profile: straight sided points, so the facets read as flat planes cut at an angle."""
    if y <= WAIST_LO:
        return TIP + (WAIST - TIP) * (y - CRYSTAL_LO) / (WAIST_LO - CRYSTAL_LO)
    if y >= WAIST_HI:
        return TIP + (WAIST - TIP) * (CRYSTAL_HI - y) / (CRYSTAL_HI - WAIST_HI)
    return WAIST


def profile():
    """(bottom, top, half width) per layer, from the lower point to the upper: thin layers, so the steps are small, each
    as wide as the profile at its middle, snapped to a sixteenth of a unit."""
    cuts = [CRYSTAL_LO + i * 0.55 for i in range(int((WAIST_LO - CRYSTAL_LO) / 0.55) + 1)]
    cuts = [c for c in cuts if c < WAIST_LO - 0.2] + [WAIST_LO, WAIST_HI]
    top = [WAIST_HI + i * 0.6 for i in range(1, int((CRYSTAL_HI - WAIST_HI) / 0.6) + 1)]
    cuts += [c for c in top if c < CRYSTAL_HI - 0.25] + [CRYSTAL_HI]
    out = []
    for y0, y1 in zip(cuts, cuts[1:]):
        out.append((round(y0, 3), round(y1, 3), round(half_width((y0 + y1) / 2) * 16) / 16))
    return out


PROFILE = profile()
TURNED = 0.88   # the square turned 45 degrees about y, against the upright one: eight facets round the stone
RING_R, RING_Y = 5.9, 7.1


def gem_painter(state, layer, turned):
    """A painter for one layer of a crystal: a gradient up the whole stone (dark foot, bright crown), each facet lit
    across its width with a streak of light down it. Dim stones are cracked."""
    pal = {k: (rgb(v) if isinstance(v, str) else v) for k, v in PALETTES[state].items()}
    y0, y1, half = PROFILE[layer]
    name = f"gem_{state}_{layer}{'t' if turned else ''}"

    def shade(gy):
        """The body colour at height gy: the shadowed foot, the body, then up to the bright crown."""
        t = (gy - CRYSTAL_LO) / (CRYSTAL_HI - CRYSTAL_LO)
        if t < 0.55:
            return mix(pal["lo"], pal["mid"], min(1.0, t / 0.45))
        return mix(pal["mid"], pal["hi"], (t - 0.55) / 0.45 * 0.85)

    @painter(name)
    def paint(face, w, h, rng):
        rows = []
        cracks = set()
        if state == "dim" and face not in ("up", "down"):
            for _ in range(rng.randint(1, 2)):                     # a crack or two running down the facet
                x = rng.randint(0, max(0, w - 1))
                for yy in range(h):
                    cracks.add((x, yy))
                    x = min(w - 1, max(0, x + rng.choice((-1, 0, 0, 1))))
        for y in range(h):
            row = []
            for x in range(w):
                gy = y1 if face == "up" else (y0 if face == "down" else y1 - (y + 0.5) / h * (y1 - y0))
                c = shade(gy)
                if face == "up":
                    c = tint(c, 14)                                   # the small ledges catch a little more light
                elif face == "down":
                    c = tint(c, -18)
                else:
                    across = x / max(w - 1, 1)
                    c = tint(c, int(24 * (0.5 - across)) + (8 if turned else -4))
                    if abs(across - 0.3) < 0.1:
                        c = mix(c, pal["vein"], 0.5)                  # a streak of inner light down the facet
                    if y == 0:
                        c = mix(c, pal["hi"], 0.22)
                if (x, y) in cracks:
                    c = mix(c, rgb("#1e1d22"), 0.75)
                row.append(tint(c, rng.randint(-3, 3)))
            rows.append(row)
        return image(rows)
    return name


def ring_painter(state):
    pal = PALETTES[state]
    name = f"ring_{state}"

    @painter(name)
    def paint(face, w, h, rng):
        base, hi = rgb(pal["ring"]), rgb(pal["ring_hi"])
        rows = []
        for y in range(h):
            row = []
            for x in range(w):
                c = mix(base, hi, 0.55) if face == "up" else (tint(base, -30) if face == "down" else base)
                if face not in ("up", "down") and y == 0:
                    c = hi
                row.append(tint(c, rng.randint(-5, 5) + int(10 * math.cos(x / max(w, 1) * math.pi * 3))))
            rows.append(row)
        return image(rows)
    return name


def turned(p, angle=45):
    p["rotation"] = {"origin": [8, 8, 8], "axis": "y", "angle": angle}
    return p


def crystal(state):
    """A floating crystal centred on (8, 8, 8): a long eight faceted double point, with a thin halo round its waist.
    The dim (spent) stone has lost its upper point to a ragged break and two of its halo's eight bars."""
    glow = PALETTES[state]["glow"]
    parts = []
    layers = PROFILE if state != "dim" else [r for r in PROFILE if r[1] <= 10.8]
    for i, (y0, y1, half) in enumerate(layers):
        a = g.part((8 - half, y0, 8 - half), (8 + half, y1, 8 + half), gem_painter(state, i, False))
        b = turned(g.part((8 - half * TURNED, y0, 8 - half * TURNED), (8 + half * TURNED, y1, 8 + half * TURNED),
                          gem_painter(state, i, True)))
        parts += [a, b]
    if state == "dim":
        # the break: a ragged stub of the upper point left standing off centre
        n = len(layers)
        parts.append(g.part((6.9, 10.4, 7.0), (8.5, 12.4, 8.6), gem_painter(state, n, False), rot=("z", 22.5, (7.7, 10.6, 7.8))))
        parts.append(g.part((8.6, 10.5, 8.2), (9.6, 11.4, 9.2), gem_painter(state, n + 1, False), rot=("x", -22.5, (9.1, 10.6, 8.7))))
    ring = ring_painter(state)
    side = 2 * RING_R * math.tan(math.pi / 8) + 0.1          # one bar of the octagon
    gone = {1, 6} if state == "dim" else set()
    for k in range(8):
        if k in gone:
            continue
        bar = g.part((8 - side / 2, RING_Y - 0.2, 8 - RING_R - 0.36), (8 + side / 2, RING_Y + 0.2, 8 - RING_R), ring)
        # bars 0..3 sit on the four sides, 4..7 are the same bars turned 45 degrees: an octagon about the centre
        quarter = k % 4
        f, t = bar["from"], bar["to"]
        for _ in range(quarter):   # turn the box a quarter about the centre: (x, z) -> (z, 16 - x)
            f, t = [f[2], f[1], 16 - t[0]], [t[2], t[1], 16 - f[0]]
        bar["from"], bar["to"] = [round(v, 4) for v in f], [round(v, 4) for v in t]
        if k >= 4:
            turned(bar)
        parts.append(bar)
    for p in parts:
        if glow:
            p["glow"] = glow
    return parts


# ---- models, icons and the preview ----

DISPLAY_PLINTH = {
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "none": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "gui": {"rotation": [30, 225, 0], "translation": [0, -0.5, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}
DISPLAY_CRYSTAL = {
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "none": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "gui": {"rotation": [20, 30, 0], "translation": [0, 0, 0], "scale": [1.15, 1.15, 1.15]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 9, 0], "scale": [0.8, 0.8, 0.8]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2.5, 1.5], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 2.5, 1.5], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -25, 0], "translation": [1.1, 3.2, 1.1], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 25, 0], "translation": [-1.1, 3.2, 1.1], "scale": [0.68, 0.68, 0.68]},
}

STATES = ("ready", "ominous", "dim", "exit")
HOVER = 12.0   # in the preview, the crystal's centre this many model units above the plinth's top (0.75 of a block)


def model(name, parts, display):
    elements, texture = bake(name, parts)
    return {"textures": {"0": f"kingdoms:item/held/{name}", "particle": f"kingdoms:item/held/{name}"},
            "display": display, "elements": elements}, texture


def write(name, m, tex):
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf8", newline="\n") as f:
        json.dump(m, f, indent=1)
        f.write("\n")
    tex.save(os.path.join(TEXTURES, name + ".png"))


BACKGROUND = (38, 40, 48)


def cut(im):
    """The preview renderer's background made see through."""
    im = im.convert("RGBA")
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y][:3] == BACKGROUND:
                px[x, y] = (0, 0, 0, 0)
    return im


def icon(path, yaw, pitch, boost=1.0):
    """The 16 by 16 inventory icon (also Bedrock's), drawn from the 3D model: rendered large from three quarters above,
    the background cut away, shrunk, brightened by boost (the dark plinth needs it at 16 pixels), and its edge made hard."""
    m = pm.load_model(path, pm.ASSETS)
    im = cut(pm.render(m, pm.ASSETS, yaw, pitch, size=320, scale=14.0))
    im = im.crop(im.getbbox())
    side = max(im.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    out = square.resize((16, 16), Image.BOX)
    if boost != 1.0:
        alpha = out.getchannel("A")
        out = ImageEnhance.Contrast(ImageEnhance.Brightness(out.convert("RGB")).enhance(boost)).enhance(1.15).convert("RGBA")
        out.putalpha(alpha)
    out.putalpha(out.getchannel("A").point(lambda v: 255 if v > 100 else 0))
    # undo the darkening the box filter does at the edge, then outline the shape so it reads on any slot colour
    px = out.load()
    for y in range(16):
        for x in range(16):
            r, g_, b, a = px[x, y]
            if a:
                px[x, y] = (min(255, int(r * 1.08)), min(255, int(g_ * 1.08)), min(255, int(b * 1.08)), 255)
    src = out.copy()
    sp = src.load()
    for y in range(16):
        for x in range(16):
            if sp[x, y][3]:
                continue
            if any(0 <= x + dx < 16 and 0 <= y + dy < 16 and sp[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = (22, 18, 28, 255)
    return out


def shifted(elements, dy, texture_key):
    """Elements moved up by dy (with their rotation origins) and pointed at another texture key."""
    out = []
    for el in elements:
        el = json.loads(json.dumps(el))
        el["from"][1] += dy
        el["to"][1] += dy
        if "rotation" in el:
            el["rotation"]["origin"][1] += dy
        for face in el["faces"].values():
            face["texture"] = texture_key
        out.append(el)
    return out


def scene(plinth_model, crystal_model):
    """The plinth with a crystal hovering over it as the display code places it, moved down so the pair is centred on
    the renderer's (8, 8, 8)."""
    rise = 16 + HOVER - 8                 # the crystal's centre from 8 to the plinth top plus HOVER
    drop = -(16 + HOVER + 6) / 2 + 8      # centre the pair (from y 0 to the crystal's top) on 8
    return {"textures": {"0": plinth_model["textures"]["0"], "1": crystal_model["textures"]["0"]},
            "elements": shifted(plinth_model["elements"], drop, "#0") + shifted(crystal_model["elements"], rise + drop, "#1")}


LABELS = {"ready": "Ready", "ominous": "Ominous", "dim": "Cleared, cooling down", "exit": "Exit"}


def preview(plinth_model, crystals, k=1):
    cell, scale = 300 * k, 6.4 * k
    pad = 26 * k
    out = Image.new("RGB", (cell * len(crystals), cell + pad + 8 * k), (20, 20, 26))
    d = ImageDraw.Draw(out)
    for i, state in enumerate(crystals):
        im = pm.render(scene(plinth_model, crystals[state]), pm.ASSETS, 35, -22, size=cell, scale=scale)
        out.paste(im.convert("RGB"), (i * cell, pad))
        label = f"lodestone_crystal_{state}: {LABELS[state]}"
        d.text((i * cell + 8 * k, 7 * k), label, fill=(232, 228, 214), font_size=11 * k)
    return out


def main():
    os.makedirs(MODELS, exist_ok=True)
    os.makedirs(TEXTURES, exist_ok=True)
    built = {}
    m, tex = model("lodestone_plinth", plinth(), DISPLAY_PLINTH)
    write("lodestone_plinth", m, tex)
    built["plinth"] = m
    print("lodestone_plinth", len(m["elements"]), "elements,", f"{tex.width}x{tex.height}")
    crystals = {}
    for state in STATES:
        name = f"lodestone_crystal_{state}"
        cm, ctex = model(name, crystal(state), DISPLAY_CRYSTAL)
        write(name, cm, ctex)
        crystals[state] = cm
        print(name, len(cm["elements"]), "elements,", f"{ctex.width}x{ctex.height}")
    for name, yaw, pitch, boost in [("lodestone_plinth", 35, -28, 1.6)] + [(f"lodestone_crystal_{s}", 35, -18, 1.0) for s in STATES]:
        icon(os.path.join(MODELS, name + ".json"), yaw, pitch, boost).save(os.path.join(ICONS, name + ".png"))
    if "--no-preview" not in sys.argv:
        os.makedirs(PREVIEWS, exist_ok=True)
        preview(built["plinth"], crystals, 1).save(os.path.join(PREVIEWS, "lodestone.png"))
        preview(built["plinth"], crystals, 2).save(os.path.join(PREVIEWS, "lodestone_2x.png"))
        print("previews written: tools/previews/lodestone.png, lodestone_2x.png")


if __name__ == "__main__":
    main()
