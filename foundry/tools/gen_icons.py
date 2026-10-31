"""Draws a 16x16 pixel-art icon for every kingdom weapon tier into examples/assets/<ns>/textures/item.

Run from the foundry folder: python tools/gen_icons.py. Needs Pillow. The icons are original, drawn pixel by pixel here,
so there is nothing to license. Higher tiers add trim and a glow. Re-run gen_weapons.py after adding a weapon.
"""
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "examples", "assets")

WOOD = [(107, 74, 43), (139, 99, 58)]
OUT = (24, 20, 28)


def new():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def line(img, a, b, color):
    (x0, y0), (x1, y1) = a, b
    steps = max(abs(x1 - x0), abs(y1 - y0), 1)
    for i in range(steps + 1):
        x = round(x0 + (x1 - x0) * i / steps)
        y = round(y0 + (y1 - y0) * i / steps)
        if 0 <= x < 16 and 0 <= y < 16:
            img.putpixel((x, y), color + (255,) if len(color) == 3 else color)


def dot(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color + (255,))


def outline(img):
    """A dark edge around the opaque pixels, so the icon reads on any background."""
    src = img.copy()
    for x in range(16):
        for y in range(16):
            if src.getpixel((x, y))[3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and src.getpixel((nx, ny))[3]:
                    img.putpixel((x, y), OUT + (255,))
                    break
    return img


def pick(t):
    img = new()
    line(img, (2, 14), (11, 5), WOOD[0])
    line(img, (3, 14), (12, 5), WOOD[1])
    steel = [((120, 120, 128), (170, 170, 178)), ((150, 152, 165), (205, 208, 220)), ((70, 200, 210), (170, 245, 250))][t]
    arc = [(4, 6), (5, 4), (6, 3), (8, 2), (10, 2), (12, 3), (13, 5), (14, 7)]
    for a, b in zip(arc, arc[1:]):
        line(img, a, b, steel[0])
        line(img, (a[0], a[1] + 1), (b[0], b[1] + 1), steel[0])
    for a, b in zip(arc[1:-1], arc[2:-1]):
        line(img, a, b, steel[1])
    if t >= 1:
        for p in ((9, 5), (8, 6)):
            dot(img, *p, (230, 170, 60))   # the rune
    if t == 2:
        dot(img, 10, 4, (255, 255, 255))
        dot(img, 6, 5, (255, 255, 255))
    return outline(img)


def blade(t):
    img = new()
    metal = [((190, 195, 205), (235, 238, 245)), ((200, 205, 215), (250, 250, 255)), ((150, 200, 255), (225, 245, 255))][t]
    line(img, (5, 10), (13, 2), metal[0])
    line(img, (6, 10), (14, 3), metal[0])
    line(img, (6, 9), (13, 2), metal[1])
    dot(img, 14, 1, metal[1])
    gold = (225, 180, 60) if t else (150, 120, 70)
    line(img, (3, 9), (6, 12), gold)      # crossguard
    line(img, (4, 9), (7, 12), gold)
    line(img, (3, 13), (5, 11), WOOD[0])  # grip
    line(img, (2, 14), (3, 13), gold)     # pommel
    if t >= 1:
        dot(img, 7, 9, (255, 240, 170))
    if t == 2:
        for p in ((10, 4), (11, 5), (9, 3)):
            dot(img, *p, (255, 255, 255))
        dot(img, 5, 7, (120, 190, 255))
        dot(img, 15, 4, (120, 190, 255))
    return outline(img)


def trident(t):
    img = new()
    teal = [((60, 150, 170), (110, 210, 220)), ((50, 130, 190), (110, 190, 245)), ((40, 110, 200), (140, 230, 255))][t]
    line(img, (2, 14), (11, 5), WOOD[0])
    line(img, (3, 14), (12, 5), WOOD[1])
    line(img, (8, 4), (13, 9), teal[0])   # the crossbar
    line(img, (9, 4), (14, 9), teal[0])
    line(img, (11, 2), (14, 5), teal[1])  # centre prong
    line(img, (13, 1), (15, 1), teal[1])
    line(img, (8, 4), (7, 1), teal[1])    # left prong
    line(img, (13, 9), (15, 8), teal[1])  # right prong
    if t >= 1:
        dot(img, 11, 7, (120, 255, 235))
    if t == 2:
        for p in ((7, 0), (15, 7), (12, 2)):
            dot(img, *p, (255, 255, 255))
        dot(img, 10, 6, (120, 255, 235))
    return outline(img)


def staff(t):
    img = new()
    bark = [((90, 80, 50), (120, 105, 62)), ((80, 90, 50), (110, 130, 62)), ((70, 100, 60), (100, 150, 80))][t]
    line(img, (1, 15), (11, 5), bark[0])
    line(img, (2, 15), (12, 5), bark[1])
    leaf = [(70, 160, 70), (90, 190, 80), (120, 220, 110)][t]
    for x, y in ((11, 5), (12, 4), (13, 3), (12, 3), (13, 4), (14, 4), (12, 2), (11, 3), (14, 2), (13, 2)):
        dot(img, x, y, leaf)
    glow = [(200, 120, 160), (235, 110, 170), (255, 150, 210)][t]
    dot(img, 13, 3, glow)
    if t >= 1:
        dot(img, 12, 2, glow)
        dot(img, 14, 4, glow)
    if t == 2:
        for p in ((15, 1), (10, 2), (15, 5), (11, 6)):
            dot(img, *p, (255, 220, 240))
    return outline(img)


def wand(t):
    img = new()
    line(img, (3, 13), (10, 6), [(235, 225, 245), (240, 225, 250), (250, 235, 190)][t])
    line(img, (4, 13), (11, 6), [(180, 160, 205), (190, 160, 215), (225, 185, 90)][t])
    star = [(255, 140, 200), (190, 130, 255), (255, 215, 90)][t]
    for p in ((12, 4), (12, 3), (12, 5), (11, 4), (13, 4)):
        dot(img, *p, star)
    dot(img, 12, 4, (255, 255, 255))
    if t >= 1:
        for p in ((12, 2), (14, 4), (12, 6), (10, 4)):
            dot(img, *p, star)
    if t == 2:
        for p in ((14, 2), (10, 2), (14, 6), (15, 3)):
            dot(img, *p, (255, 255, 255))
    return outline(img)


ICONS = [("kingdoms", "azrak_pick", pick), ("kingdoms", "aurelia_oathblade", blade),
         ("kingdoms", "azurius_trident", trident), ("kingdoms", "faeloria_staff", staff),
         ("demo", "fairy_wand", wand)]


# Coins, the currencies to come: body, rim, shine, and the stamp in the middle.
COINS = {
    "copper_coin": ((196, 112, 68), (140, 72, 44), (236, 160, 112), (150, 80, 50)),
    "iron_coin": ((184, 188, 198), (118, 122, 134), (232, 235, 242), (126, 130, 142)),
    "gold_coin": ((244, 200, 56), (176, 124, 24), (255, 238, 150), (186, 134, 28)),
}


def coin(body, rim, shine, stamp):
    img = new()
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d <= 6.2:
                img.putpixel((x, y), (rim if d > 5.0 else body) + (255,))
    for p in ((4, 6), (4, 5), (5, 4), (6, 3), (5, 5)):
        dot(img, *p, shine)
    for p in ((7, 6), (8, 6), (7, 7), (8, 7), (7, 8), (8, 8), (7, 9), (8, 9), (6, 7), (9, 8)):
        dot(img, *p, stamp)
    return outline(img)


def main():
    count = 0
    coin_folder = os.path.join(ROOT, "kingdoms", "textures", "item")
    os.makedirs(coin_folder, exist_ok=True)
    for name, colours in COINS.items():
        coin(*colours).save(os.path.join(coin_folder, name + ".png"))
        count += 1
    for ns, key, draw in ICONS:
        folder = os.path.join(ROOT, ns, "textures", "item")
        os.makedirs(folder, exist_ok=True)
        for t in range(3):
            name = key if (ns == "demo" and t == 0) else f"{key}_{t + 1}"
            draw(t).save(os.path.join(folder, name + ".png"))
            count += 1
    print(f"wrote {count} icons")


if __name__ == "__main__":
    main()
