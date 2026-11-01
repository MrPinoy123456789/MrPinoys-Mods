#!/usr/bin/env python3
"""One-off migration of loot table JSON to the 26.3 format (kept for the record, safe to rerun).

functions -> modifier, each function's "function" key -> "type", conditions list -> one condition (all_of for
several), each condition's "condition" key -> "type", bare {min,max} number providers -> uniform.
"""
import json, glob, sys

def conv(o, in_components=False):
    if isinstance(o, list):
        return [conv(x, in_components) for x in o]
    if not isinstance(o, dict):
        return o
    out = {}
    if isinstance(o.get("function"), str):
        out["type"] = o["function"]
    elif isinstance(o.get("condition"), str):
        out["type"] = o["condition"]
    elif set(o.keys()) == {"min", "max"}:
        out["type"] = "minecraft:uniform"
    for k, v in o.items():
        if k == "function" and isinstance(v, str):
            continue
        if k == "condition" and isinstance(v, str):
            continue
        if k == "functions" and isinstance(v, list):
            out["modifier"] = conv(v)
        elif k == "conditions" and isinstance(v, list):
            terms = conv(v)
            out["condition"] = terms[0] if len(terms) == 1 else {"type": "minecraft:all_of", "terms": terms}
        elif k == "components":
            out[k] = v
        else:
            out[k] = conv(v)
    return out

for f in glob.glob(sys.argv[1] + "/**/*.json", recursive=True):
    d = json.load(open(f, encoding="utf8"))
    n = conv(d)
    if n != d:
        with open(f, "w", encoding="utf8", newline="\n") as fh:
            json.dump(n, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
