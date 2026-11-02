"""Converts the race looks' Java worn models into Bedrock skin geometry, so Bedrock players see them too.

Bedrock clients never get the Java looks (Geyser cannot translate item displays, and a head item without a Bedrock
attachable draws nothing), so Foundry adds each look to the player's skin as Bedrock sees it: extra bones in the skin's
geometry and their texture painted into the skin image (see foundry.core.BedrockSkin and foundry.mc.BedrockLooks).

For each look this writes src/main/resources/foundry/bedrock/<item>.json (the bones, with per face uvs into the look's
own texture) and <item>.png (that texture, at most 64 by 64, so it fits a quarter of the 128 by 128 skin). The Java
side moves the uvs to whichever quarter it puts the texture in.

Axes. In Bedrock (and Java entity) geometry the wearer's left is +x and the face is -z, y up, one unit a skin pixel.
In our head item models the wearer's right is +x and the face is north (low z), 16 units the 10 pixel helmet shell, so
the head (8 pixels) spans 1.6 to 14.4. So x is mirrored. Head looks hang from the head bone (head box -4..4, 24..32).

Faces. MIRROR_FACES and ROTATION_SIGN hold the two conventions a Bedrock client has to confirm (left and right faces
swap under the mirror, and a mirrored face shows its texture flipped); the hat's feather, on the wearer's left, is the
check. Run after gen_trinket_models.py; writes nothing else.
"""

import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "..", "examples", "assets", "kingdoms", "models", "item", "worn")
TEXTURES = os.path.join(HERE, "..", "examples", "assets", "kingdoms", "textures", "item", "worn")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "foundry", "bedrock")

# The head looks: item, the bone they hang from. (Back looks follow once the head ones are confirmed on a device.)
LOOKS = {
    "dwarf_beard": "head",
    "antler_circlet": "head",
    "merfolk_fins": "head",
    "bombadil_cap": "head",
}
UNIT = 1.6              # head item model units per skin pixel
HEAD_BASE = (8.0, 1.6, 8.0)   # the model point that is the bottom centre of the head (Bedrock 0, 24, 0)
MAX = 64                # the look's texture must fit a quarter of the 128 skin
MIRROR_FACES = {"east": "west", "west": "east"}
ROTATION_SIGN = {"x": 1, "y": -1, "z": -1}  # the x mirror turns rotations about y and z the other way


def to_bedrock(p):
    """A head item model point to Bedrock geometry (pixels), with x mirrored."""
    return [round(-(p[0] - HEAD_BASE[0]) / UNIT, 4), round(24 + (p[1] - HEAD_BASE[1]) / UNIT, 4),
            round((p[2] - HEAD_BASE[2]) / UNIT, 4)]


def face_px(face, size):
    """A face's size in skin pixels, for a cube of this Bedrock size."""
    w, h, d = size
    return {"north": (w, h), "south": (w, h), "east": (d, h), "west": (d, h), "up": (w, d), "down": (w, d)}[face]


def convert(item, bone):
    with open(os.path.join(MODELS, item + ".json"), encoding="utf8") as f:
        model = json.load(f)
    src = Image.open(os.path.join(TEXTURES, item + ".png")).convert("RGBA")
    cubes = []
    regions = []   # (cube index, bedrock face, source box, flip u, flip v, texel w, texel h)
    for el in model["elements"]:
        a, b = to_bedrock(el["from"]), to_bedrock(el["to"])
        lo = [min(a[i], b[i]) for i in range(3)]
        hi = [max(a[i], b[i]) for i in range(3)]
        size = [round(hi[i] - lo[i], 4) for i in range(3)]
        cube = {"origin": lo, "size": size, "uv": {}}
        if "rotation" in el:
            r = el["rotation"]
            rot = [0.0, 0.0, 0.0]
            axis = "xyz".index(r["axis"])
            rot[axis] = r["angle"] * ROTATION_SIGN[r["axis"]]
            cube["pivot"] = to_bedrock(r["origin"])
            cube["rotation"] = rot
        for face, data in el["faces"].items():
            if data.get("texture") != "#0":
                continue
            u1, v1, u2, v2 = data["uv"]
            box = (min(u1, u2) * src.width / 16, min(v1, v2) * src.height / 16,
                   max(u1, u2) * src.width / 16, max(v1, v2) * src.height / 16)
            bface = MIRROR_FACES.get(face, face)
            # a face seen in the mirror shows its texture flipped left to right; flip it back
            regions.append((len(cubes), bface, box, not (u1 > u2), v1 > v2, face_px(bface, size)))
        cubes.append(cube)
    # pick the most texels per pixel that still packs into MAX by MAX
    for k in (4, 3, 2, 1):
        placed = pack([(max(1, round(fw * k)), max(1, round(fh * k))) for *_, (fw, fh) in regions])
        if placed:
            break
    else:
        raise SystemExit(item + ": its faces do not fit " + str(MAX) + " by " + str(MAX) + " even at one texel a pixel")
    w = max(x + tw for x, y, tw, th in placed)
    h = max(y + th for x, y, tw, th in placed)
    tex = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for (ci, bface, box, flip_u, flip_v, _), (x, y, tw, th) in zip(regions, placed):
        part = src.crop(tuple(round(v) for v in box)).resize((tw, th), Image.LANCZOS)
        if flip_u:
            part = part.transpose(Image.FLIP_LEFT_RIGHT)
        if flip_v:
            part = part.transpose(Image.FLIP_TOP_BOTTOM)
        tex.paste(part, (x, y))
        cubes[ci]["uv"][bface] = {"uv": [x, y], "uv_size": [tw, th]}
    bones = [{"name": "foundry_" + item, "parent": bone, "pivot": [0, 24, 0], "cubes": cubes}]
    return {"item": "kingdoms:" + item, "texture_size": [w, h], "bones": bones}, tex, k


def pack(sizes):
    """Shelf packs the faces into MAX by MAX, tallest first; None if they do not fit."""
    order = sorted(range(len(sizes)), key=lambda i: -sizes[i][1])
    out = [None] * len(sizes)
    x = y = shelf = 0
    for i in order:
        w, h = sizes[i]
        if x + w > MAX:
            x, y, shelf = 0, y + shelf, 0
        if y + h > MAX or w > MAX:
            return None
        out[i] = (x, y, w, h)
        x += w
        shelf = max(shelf, h)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for item, bone in LOOKS.items():
        data, tex, k = convert(item, bone)
        with open(os.path.join(OUT, item + ".json"), "w", encoding="utf8", newline="\n") as f:
            json.dump(data, f, indent=1)
            f.write("\n")
        tex.save(os.path.join(OUT, item + ".png"))
        print(item, len(data["bones"][0]["cubes"]), "cubes,", f"{tex.width}x{tex.height}", f"({k} texels a pixel)")
    with open(os.path.join(OUT, "looks.json"), "w", encoding="utf8", newline="\n") as f:
        json.dump(sorted("kingdoms:" + i for i in LOOKS), f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
