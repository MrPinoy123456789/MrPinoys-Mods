"""Renders vanilla item model JSON (cuboid elements) with its textures to PNG, so a model can be looked at, not just read.

Run from the foundry folder:
  python tools/preview_model.py                  every model under examples/assets/*/models/item/worn, one contact sheet
  python tools/preview_model.py path/model.json  one model
  python tools/preview_model.py --selftest       renders a marked test cube to check the renderer's face orientation
  python tools/preview_model.py --root <assets> minecraft:block/anvil ...   renders vanilla models (with parents) as a check
Output goes to tools/previews/ (contact_sheet.png, plus one PNG per model). Views: front, back, side, three quarters.

The renderer follows the vanilla rules: model space 0 to 16 with the front on the north face (low z); a face's uv
rectangle [u1, v1, u2, v2] is in 0 to 16 whatever the texture size and maps vertex by vertex in vanilla's own order, so
u1 greater than u2 flips; an element rotation is a right handed turn about the axis through its origin; faces are single
sided (back faces are culled, as for a block model); texels with almost no alpha are skipped; each face is shaded by its
normal (up 1.0, down 0.5, north and south 0.8, east and west 0.6). It is a sketch of the game's look, not the game.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "examples", "assets")
OUT = os.path.join(HERE, "previews")

# The game's missing texture: a 2 by 2 checker of magenta and black. Used for a texture that is absent or outside the
# folders the client stitches into the atlas (textures/item/ and textures/block/), exactly what a player would see.
MISSING = np.array([[[248, 0, 248, 255], [0, 0, 0, 255]], [[0, 0, 0, 255], [248, 0, 248, 255]]], dtype=np.uint8).repeat(8, 0).repeat(8, 1)

SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}


def corners(face, f, t):
    """The four vertices of a face in vanilla's order: vertex 0 takes (u1, v1), 1 takes (u1, v2), 2 (u2, v2), 3 (u2, v1)."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {
        "down": [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
        "up": [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
        "north": [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
        "south": [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
        "west": [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
        "east": [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
    }[face]


def default_uv(face, f, t):
    """The uv vanilla gives a face that names none: the element's own extent on that face."""
    if face in ("up", "down"):
        return [f[0], f[2], t[0], t[2]]
    if face in ("north", "south"):
        return [f[0], 16 - t[1], t[0], 16 - f[1]]
    return [f[2], 16 - t[1], t[2], 16 - f[1]]


def rotate_point(p, rot):
    if not rot:
        return np.array(p, dtype=float)
    o = np.array(rot["origin"], dtype=float)
    v = np.array(p, dtype=float) - o
    a = math.radians(rot["angle"])
    c, s = math.cos(a), math.sin(a)
    if rot["axis"] == "x":
        v = np.array([v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c])
    elif rot["axis"] == "y":
        v = np.array([v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c])
    else:
        v = np.array([v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]])
    return v + o


def extra_atlas_textures():
    """Vanilla textures our pack adds to the items atlas (assets/minecraft/atlases/items.json single sources)."""
    file = os.path.join(ASSETS, "minecraft", "atlases", "items.json")
    if not os.path.isfile(file):
        return set()
    with open(file, encoding="utf8") as f:
        return {s["resource"] for s in json.load(f).get("sources", []) if s.get("type") in ("minecraft:single", "single")}


# Vanilla textures are not in our assets; point VANILLA_ASSETS at an extracted client jar's assets folder to see them.
VANILLA = os.environ.get("VANILLA_ASSETS", "")


def texture_file(ref, root):
    ns, path = ref.split(":") if ":" in ref else ("minecraft", ref)
    return os.path.join(root, ns, "textures", path + ".png")


def load_model(ref_or_path, root):
    """A model with its parents merged: textures from every level (the child wins), elements and display from the nearest level."""
    if os.path.isfile(ref_or_path):
        with open(ref_or_path, encoding="utf8") as f:
            model = json.load(f)
    else:
        ns, path = ref_or_path.split(":") if ":" in ref_or_path else ("minecraft", ref_or_path)
        file = os.path.join(root, ns, "models", path + ".json")
        if not os.path.isfile(file):
            return {}
        with open(file, encoding="utf8") as f:
            model = json.load(f)
    if "parent" in model:
        parent = load_model(model["parent"], root)
        textures = dict(parent.get("textures", {}))
        textures.update(model.get("textures", {}))
        merged = dict(parent)
        merged.update(model)
        merged["textures"] = textures
        if "elements" not in model and "elements" in parent:
            merged["elements"] = parent["elements"]
        return merged
    return model


def face_texture(model, ref, root, cache):
    """Follows #key references to a texture id and loads it (first frame only for an animated strip)."""
    for _ in range(10):
        if not ref.startswith("#"):
            break
        ref = model.get("textures", {}).get(ref[1:], "")
    if not ref or ref.startswith("#"):
        return None
    if ref not in cache:
        file = texture_file(ref, root)
        full = ref if ":" in ref else "minecraft:" + ref
        if full.startswith("minecraft:") and not os.path.isfile(file) and VANILLA:
            file = texture_file(full, VANILLA)
        in_atlas = ref.split(":", 1)[-1].startswith(("item/", "block/")) or full in extra_atlas_textures()
        cache[ref] = np.array(Image.open(file).convert("RGBA")) if os.path.isfile(file) and in_atlas else MISSING
        if cache[ref] is not None and cache[ref].shape[0] > cache[ref].shape[1] and cache[ref].shape[0] % cache[ref].shape[1] == 0 \
                and os.path.isfile(file + ".mcmeta"):
            cache[ref] = cache[ref][: cache[ref].shape[1]]
    return cache[ref]


def render(model, root, yaw, pitch, size=288, scale=8.0):
    """One view as an RGBA image. yaw 0 looks at the front (the north face), 180 at the back."""
    cache = {}
    img = np.zeros((size, size, 4), dtype=np.uint8)
    img[..., :3] = (38, 40, 48)
    img[..., 3] = 255
    depth = np.full((size, size), 1e9)
    cy, cp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cx, sx = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def to_screen(p):
        x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
        x2, z2 = x * cy + z * cp, -x * cp + z * cy
        y3, z3 = y * cx - z2 * sx, y * sx + z2 * cx
        return size / 2 - x2 * scale, size / 2 - y3 * scale, z3

    for el in model.get("elements", []):
        for fname, face in el.get("faces", {}).items():
            quad = [rotate_point(c, el.get("rotation")) for c in corners(fname, el["from"], el["to"])]
            # backface culling: the face normal (from the quad's winding) against the view direction
            n = np.cross(quad[1] - quad[0], quad[2] - quad[1])
            nx, ny, nz = n
            nx2, nz2 = nx * cy + nz * cp, -nx * cp + nz * cy
            nz3 = ny * sx + nz2 * cx
            # the cross product of vanilla's vertex order is the outward normal; skip faces that point away from the viewer
            if nz3 > -1e-6:  # facing away, or exactly edge on
                continue
            texture = face_texture(model, face.get("texture", ""), root, cache)
            if texture is None:
                continue
            h, w = texture.shape[:2]
            u1, v1, u2, v2 = face.get("uv") or default_uv(fname, el["from"], el["to"])
            steps_u = max(2, int(np.linalg.norm(quad[3] - quad[0]) * scale * 2.5))
            steps_v = max(2, int(np.linalg.norm(quad[1] - quad[0]) * scale * 2.5))
            shade = SHADE[fname]
            for iu in range(steps_u):
                a = (iu + 0.5) / steps_u
                for iv in range(steps_v):
                    b = (iv + 0.5) / steps_v
                    # a runs from vertex 0 to vertex 3 (u1 to u2), b from vertex 0 to vertex 1 (v1 to v2)
                    p = quad[0] + (quad[3] - quad[0]) * a + (quad[1] - quad[0]) * b
                    sxp, syp, z = to_screen(p)
                    px, py = int(sxp), int(syp)
                    if not (0 <= px < size and 0 <= py < size) or z >= depth[py, px]:
                        continue
                    u = u1 + (u2 - u1) * a
                    v = v1 + (v2 - v1) * b
                    tx = min(w - 1, max(0, int(u / 16 * w)))
                    ty = min(h - 1, max(0, int(v / 16 * h)))
                    r, g, bl, al = texture[ty, tx]
                    if al < 20:
                        continue
                    depth[py, px] = z
                    img[py, px, :3] = (int(r * shade), int(g * shade), int(bl * shade))
    return Image.fromarray(img, "RGBA")


VIEWS = [("front", 0, 0), ("back", 180, 0), ("side", 90, 0), ("three quarters", 35, -25), ("top", 0, -89)]


def sheet(model, root, label):
    imgs = [render(model, root, y, p) for _, y, p in VIEWS]
    out = Image.new("RGB", (imgs[0].width * len(imgs), imgs[0].height + 16), (20, 20, 26))
    d = ImageDraw.Draw(out)
    for i, (im, (name, _, _)) in enumerate(zip(imgs, VIEWS)):
        out.paste(im.convert("RGB"), (i * im.width, 16))
        d.text((i * im.width + 6, 3), f"{label}  {name}", fill=(230, 230, 230))
    return out


def selftest():
    """A cube whose faces are drawn with an arrow and a letter, to check orientation and flips against vanilla's rules."""
    tex = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(tex)
    d.rectangle([0, 0, 15, 15], fill=(200, 60, 60, 255))
    d.rectangle([0, 0, 15, 3], fill=(255, 240, 80, 255))  # the top edge of the texture is yellow
    d.rectangle([0, 0, 3, 15], fill=(80, 200, 255, 255))  # the left edge is cyan
    d.rectangle([16, 0, 31, 15], fill=(60, 160, 80, 255))
    folder = os.path.join(OUT, "_selftest", "kingdoms", "textures", "t")
    os.makedirs(folder, exist_ok=True)
    tex.save(os.path.join(folder, "marked.png"))
    model = {"textures": {"0": "kingdoms:t/marked"}, "elements": [
        {"from": [3, 3, 3], "to": [13, 13, 13], "faces": {f: {"uv": [0, 0, 8, 8], "texture": "#0"} for f in NORMAL}}]}
    out = sheet(model, os.path.join(OUT, "_selftest"), "selftest")
    out.save(os.path.join(OUT, "selftest.png"))
    print("selftest.png written: on the front view the north face shows its yellow edge on top and cyan on the left (viewed from "
          "the front, the cube's left is its +x side); the back view shows the south face the same way")


def main():
    os.makedirs(OUT, exist_ok=True)
    if "--selftest" in sys.argv:
        selftest()
        return
    targets = []
    root = ASSETS
    if "--root" in sys.argv:
        root = sys.argv[sys.argv.index("--root") + 1]
        refs = [a for a in sys.argv[1:] if a not in ("--root", root)]
        targets = [(r, r.replace(":", "_").replace("/", "_")) for r in refs]
    elif len(sys.argv) > 1:
        targets = [(os.path.abspath(sys.argv[1]), None)]
    else:
        for ns in sorted(os.listdir(ASSETS)):
            folder = os.path.join(ASSETS, ns, "models", "item", "worn")
            if os.path.isdir(folder):
                for name in sorted(os.listdir(folder)):
                    # the "antlers on a helmet" models use vanilla textures; render one by path with VANILLA_ASSETS set
                    if name.endswith(".json") and "_on_" not in name:
                        targets.append((os.path.join(folder, name), name[:-5]))
    sheets = []
    for path, name in targets:
        model = load_model(path, root)
        label = name or os.path.basename(path)[:-5]
        s = sheet(model, root, label)
        s.save(os.path.join(OUT, label + ".png"))
        sheets.append(s)
        print("rendered", label, len(model.get("elements", [])), "elements")
    if len(sheets) > 1:
        w = sheets[0].width
        total = Image.new("RGB", (w, sum(s.height for s in sheets)), (20, 20, 26))
        y = 0
        for s in sheets:
            total.paste(s, (0, y))
            y += s.height
        total.save(os.path.join(OUT, "contact_sheet.png" if root == ASSETS else "reference_sheet.png"))
        print("contact_sheet.png written")


if __name__ == "__main__":
    main()
