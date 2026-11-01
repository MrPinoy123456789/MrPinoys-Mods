"""Checks the 3D worn models and draws a preview picture of them.

Run from the foundry folder: python tools/check_models.py [--preview out.png]. For every model under
examples/assets/*/models/item/worn it checks the things a vanilla client would silently get wrong: every face names a
texture that the model defines and that exists as a PNG, UVs stay inside 0 to 16 and are not empty, each cuboid has
from below to on every axis and stays inside the -16 to 32 limit, rotations use a legal axis and angle with an origin,
display transforms use real context names, and the cuboid count stays modest. Exit code 1 when anything is wrong.
The preview shows each model from the front, the side and above as flat coloured silhouettes (an orthographic sketch,
not the game's lighting), enough to see proportions and whether parts overlap; only a live client shows the real look.
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets")
LEGAL_ANGLES = {-45, -22.5, 0, 22.5, 45}
FACE_NAMES = {"north", "east", "south", "west", "up", "down"}
CONTEXTS = {"none", "thirdperson_lefthand", "thirdperson_righthand", "firstperson_lefthand", "firstperson_righthand",
            "head", "gui", "ground", "fixed", "on_shelf"}
MAX_ELEMENTS = 32


def models():
    for ns in sorted(os.listdir(ASSETS)):
        folder = os.path.join(ASSETS, ns, "models", "item", "worn")
        if os.path.isdir(folder):
            for name in sorted(os.listdir(folder)):
                if name.endswith(".json"):
                    yield ns, name[:-5], os.path.join(folder, name)


def texture_file(ref):
    ns, path = ref.split(":") if ":" in ref else ("minecraft", ref)
    return os.path.join(ASSETS, ns, "textures", path + ".png")


def check(ns, name, path):
    problems = []
    with open(path, encoding="utf8") as f:
        model = json.load(f)
    tex = model.get("textures", {})
    for key, ref in tex.items():
        if not ref.startswith("#") and not os.path.isfile(texture_file(ref)):
            problems.append(f"texture {key} -> {ref} has no PNG")
    els = model.get("elements", [])
    if not els:
        problems.append("no elements")
    if len(els) > MAX_ELEMENTS:
        problems.append(f"{len(els)} elements is more than {MAX_ELEMENTS}")
    for i, el in enumerate(els):
        where = f"element {i}"
        f_, t_ = el.get("from"), el.get("to")
        if not (f_ and t_ and len(f_) == 3 and len(t_) == 3):
            problems.append(f"{where}: from/to missing")
            continue
        for a in range(3):
            if not f_[a] < t_[a] and not (f_[a] == t_[a]):
                problems.append(f"{where}: from is not below to on axis {a}")
            if min(f_[a], t_[a]) < -16 or max(f_[a], t_[a]) > 32:
                problems.append(f"{where}: outside the -16 to 32 limit on axis {a}")
        rot = el.get("rotation")
        if rot:
            if rot.get("axis") not in ("x", "y", "z"):
                problems.append(f"{where}: bad rotation axis")
            if rot.get("angle") not in LEGAL_ANGLES:
                problems.append(f"{where}: rotation angle {rot.get('angle')} is not one of {sorted(LEGAL_ANGLES)}")
            if "origin" not in rot:
                problems.append(f"{where}: rotation has no origin")
        faces = el.get("faces", {})
        if not faces:
            problems.append(f"{where}: no faces")
        for fname, face in faces.items():
            if fname not in FACE_NAMES:
                problems.append(f"{where}: unknown face {fname}")
            ref = face.get("texture", "")
            if not ref.startswith("#") or ref[1:] not in tex:
                problems.append(f"{where}.{fname}: texture {ref} is not defined by the model")
            uv = face.get("uv")
            if not uv or len(uv) != 4 or any(v < 0 or v > 16 for v in uv):
                problems.append(f"{where}.{fname}: uv {uv} is outside 0 to 16")
            elif uv[0] == uv[2] or uv[1] == uv[3]:
                problems.append(f"{where}.{fname}: uv {uv} is empty")
    for ctx in model.get("display", {}):
        if ctx not in CONTEXTS:
            problems.append(f"display context {ctx} is not real")
    return model, problems


def rotate(point, rot):
    if not rot:
        return point
    ox, oy, oz = rot["origin"]
    x, y, z = point[0] - ox, point[1] - oy, point[2] - oz
    a = math.radians(rot["angle"])
    c, s = math.cos(a), math.sin(a)
    if rot["axis"] == "x":
        y, z = y * c - z * s, y * s + z * c
    elif rot["axis"] == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + ox, y + oy, z + oz)


def hull(points):
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def average(texture, uv):
    w, h = texture.size
    ua, ub = sorted((uv[0], uv[2]))
    va, vb = sorted((uv[1], uv[3]))
    x0, y0, x1, y1 = (int(ua / 16 * w), int(va / 16 * h), max(int(ub / 16 * w), int(ua / 16 * w) + 1), max(int(vb / 16 * h), int(va / 16 * h) + 1))
    px = [texture.getpixel((x, y)) for x in range(x0, x1) for y in range(y0, y1)]
    n = max(len(px), 1)
    return tuple(sum(p[i] for p in px) // n for i in range(3))


def preview(items, out):
    cell = 224
    scale = 4.5
    img = Image.new("RGB", (cell * 3, cell * len(items)), (34, 36, 44))
    d = ImageDraw.Draw(img)
    for row, (name, model, texture) in enumerate(items):
        # view: (which axis is horizontal, which is vertical, which is depth, flip horizontal)
        views = [(0, 1, 2, False), (2, 1, 0, False), (0, 2, 1, False)]
        for col, (h, v, depth, flip) in enumerate(views):
            ox, oy = col * cell + cell // 2, row * cell + cell // 2
            drawn = []
            for el in model["elements"]:
                f_, t_ = el["from"], el["to"]
                corners = [(x, y, z) for x in (f_[0], t_[0]) for y in (f_[1], t_[1]) for z in (f_[2], t_[2])]
                corners = [rotate(c, el.get("rotation")) for c in corners]
                face = next(iter(el["faces"].values()))
                colour = average(texture, face["uv"])
                pts = [((c[h] - 8) * scale + ox, (8 - c[v]) * scale + oy) if v != 2 else ((c[h] - 8) * scale + ox, (c[2] - 8) * scale + oy)
                       for c in corners]
                drawn.append((sum(c[depth] for c in corners) / 8, hull(pts), colour))
            drawn.sort(key=lambda t: -t[0] if depth == 2 else t[0])
            for _, poly, colour in drawn:
                if len(poly) >= 3:
                    d.polygon(poly, fill=colour, outline=(20, 20, 20))
            d.text((col * cell + 6, row * cell + 4), f"{name} {['front', 'side', 'top'][col]}", fill=(230, 230, 230))
    img.save(out)


def main():
    bad = 0
    shown = []
    for ns, name, path in models():
        model, problems = check(ns, name, path)
        tex = Image.open(texture_file(next(iter(model["textures"].values())))).convert("RGBA")
        shown.append((name, model, tex))
        status = "ok" if not problems else "PROBLEMS"
        print(f"{ns}:{name}: {len(model['elements'])} elements, {status}")
        for p in problems:
            print("   ", p)
        bad += len(problems)
    if "--preview" in sys.argv:
        preview(shown, sys.argv[sys.argv.index("--preview") + 1])
        print("preview written")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
