#!/usr/bin/env python3
"""Strip removed mods' blocks, block entities, entities and ticks from a world.

Usage: strip_mods.py <world dir> <namespace> [<namespace> ...] [--dry-run]

Works on region/, entities/ and DIM*/ copies of both. Work on a COPY of the world.
Blocks of the given namespaces become air; block entities, entities and scheduled
ticks of those namespaces are dropped. Everything else is left alone.
"""
import io, os, struct, sys, zlib, glob
import nbtlib
from nbtlib import String, Compound

def decode(raw):
    comp = raw[4]
    data = raw[5:]
    if comp == 2:
        data = zlib.decompress(data)
    elif comp == 1:
        import gzip; data = gzip.decompress(data)
    elif comp == 3:
        pass
    else:
        return None
    return nbtlib.File.parse(io.BytesIO(data))

def encode(nbt):
    buf = io.BytesIO()
    nbt.write(buf)
    body = zlib.compress(buf.getvalue())
    return struct.pack(">IB", len(body) + 1, 2) + body

def bad(name, spaces):
    return str(name).split(":", 1)[0] in spaces

def clean_chunk(nbt, spaces):
    changed = 0
    for sec in nbt.get("sections", []):
        pal = sec.get("block_states", {}).get("palette", [])
        for entry in pal:
            if bad(entry["Name"], spaces):
                entry["Name"] = String("minecraft:air")
                entry.pop("Properties", None)
                changed += 1
    for key in ("block_entities", "Entities", "entities"):
        lst = nbt.get(key)
        if lst:
            keep = [e for e in lst if not bad(e.get("id", ""), spaces)]
            if len(keep) != len(lst):
                changed += len(lst) - len(keep)
                lst[:] = keep
    for key in ("block_ticks", "fluid_ticks"):
        lst = nbt.get(key)
        if lst:
            keep = [t for t in lst if not bad(t.get("i", ""), spaces)]
            if len(keep) != len(lst):
                changed += len(lst) - len(keep)
                lst[:] = keep
    return changed

def process_region(path, spaces, dry):
    with open(path, "rb") as f:
        blob = f.read()
    if len(blob) < 8192:
        return 0
    out_chunks = {}
    total = 0
    for i in range(1024):
        off = struct.unpack(">I", b"\0" + blob[i*4:i*4+3])[0]
        cnt = blob[i*4+3]
        stamp = blob[4096+i*4:4096+i*4+4]
        if off == 0:
            continue
        raw = blob[off*4096: off*4096 + 4 + max(0, struct.unpack(">I", blob[off*4096:off*4096+4])[0])]
        if raw[4] & 128:
            print(f"  skip external chunk {i} in {path}")
            out_chunks[i] = (raw, stamp, True)
            continue
        nbt = decode(raw)
        if nbt is None:
            out_chunks[i] = (raw, stamp, True)
            continue
        n = clean_chunk(nbt, spaces)
        total += n
        out_chunks[i] = (encode(nbt) if n else raw, stamp, False)
    if total and not dry:
        header = bytearray(8192)
        body = bytearray()
        sector = 2
        for i, (raw, stamp, _) in sorted(out_chunks.items()):
            pad = (-len(raw)) % 4096
            n = (len(raw) + pad) // 4096
            header[i*4:i*4+3] = sector.to_bytes(3, "big")
            header[i*4+3] = n
            header[4096+i*4:4096+i*4+4] = stamp
            body += raw + b"\0" * pad
            sector += n
        with open(path, "wb") as f:
            f.write(header + body)
    return total

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    world, spaces = args[0], set(args[1:])
    files = glob.glob(os.path.join(world, "**", "*.mca"), recursive=True)
    grand = 0
    for p in files:
        n = process_region(p, spaces, dry)
        if n:
            print(f"{p}: {n} removed")
        grand += n
    print(f"done, {grand} things {'would be ' if dry else ''}removed across {len(files)} region files")

main()
