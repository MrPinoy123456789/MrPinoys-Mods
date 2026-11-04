"""Draws the Charter Currency coin textures (16 by 16) into examples/assets/chartercurrency/textures/item.

Run from the foundry folder: python tools/gen_coins.py [preview_dir]. Each coin is a struck disc: a dark outline, a
thick edge showing below the face, a raised rim, a face, and an emblem stamped into it. With a preview_dir it also writes the coins scaled up 16 times, side by side, for people to look at.
"""
import math
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "examples", "assets", "chartercurrency", "textures", "item")

# outline, edge (the coin's thickness), rim, face, engraved
PALETTES = {
    "gold_sovereign": ("#4f3300", "#9a6400", "#ffe46b", "#f2b52a", "#b37800"),
    "iron_crown": ("#34343b", "#6e6e78", "#f4f4f6", "#c6c8ce", "#787a85"),
    "copper_mark": ("#46200f", "#86411f", "#f6ab7c", "#cf703f", "#8c3f1c"),
}

# 7 by 7 emblems, '#' stamped in: a sun for the Sovereign, a crown for the Crown, a mark (a lozenge) for the Mark
EMBLEMS = {
    "gold_sovereign": ["...#...",
                       ".#...#.",
                       "..###..",
                       "#.###.#",
                       "..###..",
                       ".#...#.",
                       "...#..."],
    "iron_crown": [".......",
                   "#..#..#",
                   "##.#.##",
                   "#######",
                   "#######",
                   ".#####.",
                   "......."],
    "copper_mark": ["...#...",
                    "..###..",
                    ".##.##.",
                    "##...##",
                    ".##.##.",
                    "..###..",
                    "...#..."],
}


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def draw(name):
    outline, edge, rim, face, engraved = (rgb(c) for c in PALETTES[name])
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    cx, top_cy, edge_cy, r = 7.5, 7.0, 8.0, 6.3

    def inside(x, y, cy, rad):
        return math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= rad

    for y in range(16):
        for x in range(16):
            on_face = inside(x, y, top_cy, r)
            on_edge = inside(x, y, edge_cy, r)
            if on_face:
                d = math.hypot(x + 0.5 - cx, y + 0.5 - top_cy)
                px[x, y] = rim if d > r - 1.3 else face
            elif on_edge:
                px[x, y] = edge
            elif inside(x, y, top_cy, r + 1) or inside(x, y, edge_cy, r + 1):
                px[x, y] = outline
    # the rim's shadow side: the lower right of the rim is struck down a shade
    for y in range(16):
        for x in range(16):
            if px[x, y] == rim and (x + 0.5 - cx) + (y + 0.5 - top_cy) > 3.5:
                px[x, y] = face
    # the emblem, stamped in the middle of the face
    rows = EMBLEMS[name]
    ox, oy = 4, 3
    for j, row in enumerate(rows):
        for i, c in enumerate(row):
            if c == "#":
                px[ox + i, oy + j] = engraved
    # a glint on the upper left of the rim
    for x, y in ((4, 2), (3, 3), (2, 4)):
        if px[x, y] == rim:
            px[x, y] = (255, 255, 255, 255)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    coins = []
    for name in PALETTES:
        img = draw(name)
        img.save(os.path.join(OUT, name + ".png"))
        coins.append(img)
    if len(sys.argv) > 1:
        sheet = Image.new("RGBA", (16 * 3 + 4 * 2, 16), (0, 0, 0, 0))
        for i, img in enumerate(coins):
            sheet.paste(img, (i * 20, 0))
        sheet = sheet.resize((sheet.width * 16, sheet.height * 16), Image.NEAREST)
        backdrop = Image.new("RGBA", sheet.size, (139, 139, 139, 255))
        backdrop.alpha_composite(sheet)
        os.makedirs(sys.argv[1], exist_ok=True)
        backdrop.save(os.path.join(sys.argv[1], "charter_coins_preview.png"))
    print("wrote", ", ".join(PALETTES), "to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
