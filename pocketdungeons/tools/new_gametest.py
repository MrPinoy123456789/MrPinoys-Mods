"""Scaffold a Fabric gametest class and register it.

    python tools/new_gametest.py <ClassName> [--dry-run]

Writes src/gametest/java/pocketdungeons/<ClassName>.java (CRLF, the idiom of HerdGameTest: one final class,
@GameTest methods taking a GameTestHelper, helper.succeed() at the end) and adds "pocketdungeons.<ClassName>"
to the "fabric-gametest" list in src/gametest/resources/fabric.mod.json, keeping that file's line endings.

The new entry goes at a position picked from the class name, not at the end of the list. Every task adds one
line to that list, and twenty branches all appending after the same last line is a merge conflict each time.
Order does not matter to Fabric. A name that does not end in GameTest gets the suffix.

An unregistered class compiles and never runs, so check the gametest count rises by the number of methods
(tools/ship.sh --only <ClassName> prints it in a few seconds).
"""
import os
import re
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from pyedit import read, save, write  # noqa: E402

ROOT = os.path.dirname(HERE)
JAVA_DIR = os.path.join(ROOT, 'src', 'gametest', 'java', 'pocketdungeons')
MOD_JSON = os.path.join(ROOT, 'src', 'gametest', 'resources', 'fabric.mod.json')

TEMPLATE = '''package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * TODO: one or two lines saying what this class pins (the PD number or the roadmap task, and the rule).
 */
public final class %(name)s {

    @GameTest
    public void todoNameThisAfterTheRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The cell origin to build on. Clear what you place; a far location needs setChunkForced and a finally.
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));

        // TODO: set up, call the code under test, then assert with a message that names the rule:
        // helper.assertTrue(condition, "what must hold");
        // helper.assertValueEqual(actual, expected, "what is compared");
        // A mock player: @SuppressWarnings("removal") ServerPlayer p = helper.makeMockServerPlayerInLevel();
        // Slow code: @GameTest(maxTicks = 200) and helper.runAfterDelay(ticks, () -> { ...; helper.succeed(); });

        helper.succeed();
    }
}
'''


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    dry = '--dry-run' in argv
    if len(args) != 1 or not re.fullmatch(r'[A-Z][A-Za-z0-9]*', args[0]):
        print('usage: python tools/new_gametest.py <ClassName> [--dry-run]   (CamelCase, e.g. TrappedCacheGameTest)')
        return 2
    name = args[0]
    if not name.endswith('GameTest'):
        name += 'GameTest'
        print('class name does not end in GameTest; using %s' % name)
    java_path = os.path.join(JAVA_DIR, name + '.java')
    if os.path.exists(java_path):
        print('refusing: %s already exists' % java_path)
        return 1

    text, crlf = read(MOD_JSON)
    entry = '"pocketdungeons.%s"' % name
    if entry in text:
        print('refusing: %s is already registered in fabric.mod.json' % entry)
        return 1
    m = re.search(r'("fabric-gametest"\s*:\s*\[\n)(.*?)(\n\s*\])', text, re.S)
    if not m:
        print('could not find the "fabric-gametest" list in %s' % MOD_JSON)
        return 1
    lines = m.group(2).split('\n')
    indent = re.match(r'\s*', lines[0]).group(0)
    # Spread the insert point by the name so concurrent branches do not all append after the same line.
    at = zlib.crc32(name.encode('utf-8')) % (len(lines) + 1)
    if at == len(lines):
        lines[-1] = lines[-1] + ','
        lines.append(indent + entry)
    else:
        lines.insert(at, indent + entry + ',')
    new_text = text[:m.start(2)] + '\n'.join(lines) + text[m.end(2):]

    if dry:
        print('would write %s and register %s after %d entries' % (java_path, entry, at))
        return 0
    write(java_path, TEMPLATE % {'name': name})
    save(MOD_JSON, new_text, crlf)
    print('wrote   src/gametest/java/pocketdungeons/%s.java' % name)
    print('added   %s to src/gametest/resources/fabric.mod.json (list position %d)' % (entry, at))
    print('next:')
    print('  1. rename todoNameThisAfterTheRule, fill in the setup and the asserts, fill in the class javadoc;')
    print('  2. tools/ship.sh --only %s     (a few seconds; "All N required tests passed" must show your count);' % name)
    print('  3. before the full suite: tools/ship.sh --pure and tools/check_dashes.sh.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
