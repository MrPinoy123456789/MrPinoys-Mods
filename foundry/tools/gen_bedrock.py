"""Builds the Bedrock side of Foundry's textured items, for servers that run Geyser.

Run from the foundry folder: python tools/gen_bedrock.py. It reads every item in examples/content that has a
texture and writes, under examples/bedrock:
  custom_mappings/foundry.json   Geyser custom item mappings (format 2): Java item model to Bedrock item
  foundry_bedrock.mcpack         the Bedrock resource pack (manifest, item_texture.json, the PNGs)
Copy the first into config/Geyser-Fabric/custom_mappings and the second into config/Geyser-Fabric/packs, set
enable-custom-content to true in Geyser's config.yml, and restart. Bedrock players then see the same icons, and a
handheld item is held like a tool. Re-run after adding or changing a textured item.
"""
import json
import os
import uuid
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "..", "examples")
OUT = os.path.join(BASE, "bedrock")
PACK_NAME = "Foundry Items"
# Fixed ids so a rebuilt pack replaces the old one on a player's device instead of stacking.
PACK_UUID = "5b0e6d0e-2a0a-4c63-9f1d-6e0f0a7c1001"
MODULE_UUID = "5b0e6d0e-2a0a-4c63-9f1d-6e0f0a7c1002"


def items():
    root = os.path.join(BASE, "content")
    for ns in sorted(os.listdir(root)):
        folder = os.path.join(root, ns, "items")
        if not os.path.isdir(folder):
            continue
        for name in sorted(os.listdir(folder)):
            if not name.endswith(".json"):
                continue
            with open(os.path.join(folder, name), encoding="utf8") as f:
                item = json.load(f)
            if item.get("texture"):
                yield item


def texture_file(item):
    ns, path = item["texture"].split(":")
    return os.path.join(BASE, "assets", ns, "textures", path + ".png")


def main():
    mappings = {}
    pack_textures = {}
    files = {}
    for item in items():
        ident = item["id"]
        ns, path = ident.split(":")
        source = texture_file(item)
        if not os.path.isfile(source):
            print("skipping", ident, "(no texture file)")
            continue
        key = f"{ns}_{path}"
        with open(source, "rb") as f:
            files[f"textures/items/{key}.png"] = f.read()
        pack_textures[ident] = {"textures": [f"textures/items/{key}"]}
        mappings.setdefault(item["base_item"], []).append({
            "type": "definition",
            "model": ident,
            "bedrock_identifier": ident,
            "display_name": item["display_name"],
            "bedrock_options": {
                "icon": ident,
                "display_handheld": bool(item.get("handheld")),
                "allow_offhand": True,
            },
            "components": {"minecraft:max_stack_size": int(item.get("stack_size", 64))},
        })

    os.makedirs(os.path.join(OUT, "custom_mappings"), exist_ok=True)
    with open(os.path.join(OUT, "custom_mappings", "foundry.json"), "w", encoding="utf8", newline="\n") as f:
        json.dump({"format_version": 2, "items": mappings}, f, indent=2, ensure_ascii=False)
        f.write("\n")

    manifest = {
        "format_version": 2,
        "header": {"name": PACK_NAME, "description": "Textures for Foundry items", "uuid": PACK_UUID,
                   "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"type": "resources", "uuid": MODULE_UUID, "version": [1, 0, 0]}],
    }
    item_texture = {"resource_pack_name": PACK_NAME, "texture_name": "atlas.items", "texture_data": pack_textures}
    pack = os.path.join(OUT, "foundry_bedrock.mcpack")
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("textures/item_texture.json", json.dumps(item_texture, indent=2))
        for name, data in sorted(files.items()):
            z.writestr(name, data)
    count = sum(len(v) for v in mappings.values())
    print(f"wrote {count} item mappings and a pack with {len(files)} textures to {OUT}")


if __name__ == "__main__":
    main()
