#!/usr/bin/env python3
"""Applies the data-side tuning knobs in tools/loot_knobs.json to the shipped data files.

Some balance values live in datapack JSON that the game cannot read from pocketdungeons.json,
so they are tuned here and written into the files. Run from the mod root:

    python tools/tune_loot.py show                 # the knobs and what the files hold now
    python tools/tune_loot.py apply                # write every knob into the data
    python tools/tune_loot.py apply --check        # report files that differ, change nothing (exit 1 if any)
    python tools/tune_loot.py spawner-keys --percent 70 [--exact]
                                                   # one-off: set the key share without editing the knob file

spawnerKeyPercent: a trial spawner ejects either a vault key or emeralds when it is beaten
(loot_tables_to_eject in trial_spawner/**.json, two weights that sum to 10). The key share is
keyWeight / (keyWeight + emeraldWeight). By default a spawner that already gives more keys than the
knob asks (tier 1 spawners sit at 80 percent) is left alone, so the knob is a floor; --exact sets every
spawner to the knob, lowering the ones above it.

Files are edited in place as text (weights only), so line endings and layout are untouched.
Only the weights change. Re-running is harmless.
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KNOBS = os.path.join(ROOT, 'tools', 'loot_knobs.json')
SPAWNERS = os.path.join(ROOT, 'src', 'main', 'resources', 'data', 'pocketdungeons', 'trial_spawner')

KEY = re.compile(r'("data":\s*"pocketdungeons:spawners/(?:ominous_)?trial_key",\s*"weight":\s*)(\d+)')
EMERALDS = re.compile(r'("data":\s*"pocketdungeons:spawners/emeralds",\s*"weight":\s*)(\d+)')


def load_knobs():
    with open(KNOBS, encoding='utf-8') as handle:
        return json.load(handle)


def spawner_files():
    for folder, _, names in os.walk(SPAWNERS):
        for name in sorted(names):
            if name.endswith('.json'):
                yield os.path.join(folder, name)


def read(path):
    with open(path, encoding='utf-8-sig', newline='') as handle:
        return handle.read()


def key_weights(text):
    key, emeralds = KEY.search(text), EMERALDS.search(text)
    if not key or not emeralds:
        return None
    return int(key.group(2)), int(emeralds.group(2))


def target_weights(key, emeralds, percent, exact):
    """The (key, emerald) weights for the knob; the same total, key share rounded to a whole weight."""
    total = key + emeralds
    if total <= 0:
        return key, emeralds
    share = key * 100.0 / total
    if not exact and share >= percent:
        return key, emeralds
    wanted = max(0, min(total, round(percent * total / 100.0)))
    return wanted, total - wanted


def spawner_keys(percent, exact, check):
    changed = []
    for path in spawner_files():
        text = read(path)
        weights = key_weights(text)
        if weights is None:
            continue
        key, emeralds = weights
        new_key, new_emeralds = target_weights(key, emeralds, percent, exact)
        if (new_key, new_emeralds) == (key, emeralds):
            continue
        changed.append((os.path.relpath(path, SPAWNERS), key, emeralds, new_key, new_emeralds))
        if not check:
            text = KEY.sub(lambda m: m.group(1) + str(new_key), text, count=1)
            text = EMERALDS.sub(lambda m: m.group(1) + str(new_emeralds), text, count=1)
            with open(path, 'w', encoding='utf-8', newline='') as handle:
                handle.write(text)
    verb = 'would change' if check else 'changed'
    print('spawner key share %d percent%s: %s %d spawner config(s)' % (percent, ' (exact)' if exact else '', verb, len(changed)))
    for rel, key, emeralds, new_key, new_emeralds in changed[:12]:
        print('  %s: keys/emeralds %d/%d -> %d/%d' % (rel, key, emeralds, new_key, new_emeralds))
    if len(changed) > 12:
        print('  ... and %d more' % (len(changed) - 12))
    return len(changed)


def show():
    knobs = load_knobs()
    print('knobs in tools/loot_knobs.json:')
    for name, value in knobs.items():
        if not name.startswith('_'):
            print('  %s = %s' % (name, value))
    counts = {}
    for path in spawner_files():
        weights = key_weights(read(path))
        if weights:
            counts[weights] = counts.get(weights, 0) + 1
    print('spawner configs by keys/emeralds weights now:')
    for weights, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print('  %d/%d: %d file(s)' % (weights[0], weights[1], count))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('show')
    apply_parser = sub.add_parser('apply')
    apply_parser.add_argument('--check', action='store_true')
    apply_parser.add_argument('--exact', action='store_true')
    one = sub.add_parser('spawner-keys')
    one.add_argument('--percent', type=int, required=True)
    one.add_argument('--exact', action='store_true')
    one.add_argument('--check', action='store_true')
    args = parser.parse_args()

    if args.command == 'show':
        show()
        return 0
    if args.command == 'apply':
        percent = int(load_knobs()['spawnerKeyPercent'])
        changed = spawner_keys(percent, args.exact, args.check)
        return 1 if args.check and changed else 0
    if not 0 <= args.percent <= 100:
        print('percent must be 0 to 100')
        return 2
    changed = spawner_keys(args.percent, args.exact, args.check)
    return 1 if args.check and changed else 0


if __name__ == '__main__':
    sys.exit(main())
