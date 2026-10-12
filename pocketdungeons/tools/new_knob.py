"""Add a layer-1 runtime config knob to PocketDungeonsConfig (see the tune-knobs skill).

    python tools/new_knob.py <name> <default> "<description>" [--min N] [--max N] [--after <existingKey>]
                             [--allow-empty] [--regen] [--dry-run]

  name         the config key, camelCase, e.g. trappedCacheAmbushPercent
  default      true or false (a flag), a whole number (integer), a decimal such as 0.5 (decimal), else text
  description  the declaration's Javadoc: what it does and its unit. No dashes (house rule).
  --min/--max  bounds the validator accepts (default for numbers: at least 0, no maximum)
  --after KEY  put the declaration and accessor right after that existing knob, so a group stays together.
               Without it the position is picked from the name, so twenty branches adding knobs do not all
               append after the same line (declaration order only decides the order of keys in a new config file).
  --regen      afterwards run configCharacterizationTest with PD_CONFIG_REGEN set, which rewrites the six
               fixtures under src/test/resources/config-fixtures, then run configKnobsTest. Review that diff.

What it writes: one Knob constant with Javadoc, and the accessor named exactly like the key, both in
src/main/java/pocketdungeons/PocketDungeonsConfig.java (CRLF kept). config/pocketdungeons.default.json is a
stale partial sample that nothing reads, so it is left alone. ConfigKnobsTest checks the accessor and the
written defaults; ConfigCharacterizationTest pins the fixtures.
"""
import os
import re
import subprocess
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from pyedit import read, save  # noqa: E402

ROOT = os.path.dirname(HERE)
CONFIG = os.path.join(ROOT, 'src', 'main', 'java', 'pocketdungeons', 'PocketDungeonsConfig.java')
FIXTURES = os.path.join(ROOT, 'src', 'test', 'resources', 'config-fixtures')

DECL = re.compile(r'^    private static final Knob<(Integer|Double|Boolean|String)> ([A-Z0-9_]+) = (integer|decimal|flag|text)\("([A-Za-z0-9]+)"')


def upper_snake(name):
    return re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', name).upper()


def javadoc(text):
    one = '    /** %s */' % text
    if len(one) <= 110:
        return [one]
    words, lines, cur = text.split(), [], ''
    for w in words:
        if cur and len(cur) + 1 + len(w) > 96:
            lines.append(cur)
            cur = w
        else:
            cur = (cur + ' ' + w).strip()
    lines.append(cur)
    return ['    /**'] + ['     * ' + l for l in lines] + ['     */']


def main(argv):
    flags = {a for a in argv if a in ('--allow-empty', '--regen', '--dry-run')}
    rest, opts, i = [], {}, 0
    while i < len(argv):
        a = argv[i]
        if a in ('--min', '--max', '--after'):
            if i + 1 >= len(argv):
                print('%s needs a value' % a)
                return 2
            opts[a] = argv[i + 1]
            i += 2
        else:
            if a not in flags:
                rest.append(a)
            i += 1
    if len(rest) != 3 or not re.fullmatch(r'[a-z][A-Za-z0-9]*', rest[0]):
        print(__doc__)
        return 2
    name, default, desc = rest
    if '—' in desc or ' -- ' in desc:
        print('the description has an em dash or a spaced double hyphen; rewrite it (CLAUDE.md)')
        return 2

    if default in ('true', 'false'):
        kind, jtype, rtype, factory, lit = 'flag', 'Boolean', 'boolean', 'flag', default
    elif re.fullmatch(r'-?\d+', default):
        kind, jtype, rtype, factory, lit = 'int', 'Integer', 'int', 'integer', default
    elif re.fullmatch(r'-?\d*\.\d+', default):
        kind, jtype, rtype, factory, lit = 'decimal', 'Double', 'double', 'decimal', default
    else:
        kind, jtype, rtype, factory = 'text', 'String', 'String', 'text'
        lit = '"%s"' % default.replace('\\', '\\\\').replace('"', '\\"')

    const = upper_snake(name)
    if kind in ('int', 'decimal'):
        lo, hi = opts.get('--min', '0'), opts.get('--max')
        cond = ['v >= %s' % lo] + (['v <= %s' % hi] if hi is not None else [])
        msg = 'must be %s to %s' % (lo, hi) if hi is not None else 'must be >= %s' % lo
        decl_call = '%s("%s", %s, v -> %s, "%s")' % (factory, name, lit, ' && '.join(cond), msg)
    elif kind == 'flag':
        decl_call = 'flag("%s", %s)' % (name, lit)
    else:
        decl_call = 'text("%s", %s, %s)' % (name, lit, 'true' if '--allow-empty' in flags else 'false')
    decl = '    private static final Knob<%s> %s = %s;' % (jtype, const, decl_call)
    if len(decl) > 120 and kind in ('int', 'decimal'):
        decl = ('    private static final Knob<%s> %s = %s("%s", %s,\n'
                '            v -> %s, "%s");') % (jtype, const, factory, name, lit, ' && '.join(cond), msg)
    accessor = ['    public static %s %s() {' % (rtype, name), '        return %s.get();' % const, '    }']

    text, crlf = read(CONFIG)
    if '"%s"' % name in text:
        print('refusing: a knob or key named %s is already mentioned in PocketDungeonsConfig' % name)
        return 1
    lines = text.split('\n')
    decls = [(n, DECL.match(l)) for n, l in enumerate(lines) if DECL.match(l)]
    if not decls:
        print('could not find any simple knob declaration to anchor on')
        return 1
    if '--after' in opts:
        anchor = next((m for _, m in decls if m.group(4) == opts['--after']), None)
        if anchor is None:
            print('no simple knob with key %s (anchors: integer, decimal, flag and text knobs)' % opts['--after'])
            return 1
    else:
        anchor = decls[zlib.crc32(name.encode('utf-8')) % len(decls)][1]
    a_const, a_key = anchor.group(2), anchor.group(4)

    # Declaration: insert after the end of the anchor's statement (the first line ending in a semicolon).
    start = next(n for n, m in decls if m is anchor)
    end = next(n for n in range(start, len(lines)) if lines[n].rstrip().endswith(';'))
    new_decl = javadoc(desc) + decl.split('\n')
    lines[end + 1:end + 1] = new_decl
    # Accessor: insert after the anchor's accessor, found by its return statement.
    ret = next((n for n, l in enumerate(lines) if l.strip() == 'return %s.get();' % a_const), None)
    if ret is None:
        print('could not find the accessor of %s; add yours by hand after the others' % a_key)
        return 1
    close = next(n for n in range(ret, len(lines)) if lines[n] == '    }')
    lines[close + 1:close + 1] = [''] + accessor

    if '--dry-run' in flags:
        print('\n'.join(new_decl))
        print('... accessor after %s():' % a_key)
        print('\n'.join(accessor))
        return 0
    save(CONFIG, '\n'.join(lines), crlf)
    print('declared %s (after %s) and added accessor %s()' % (const, a_key, name))

    if '--regen' in flags:
        env = dict(os.environ, PD_CONFIG_REGEN=FIXTURES)
        gradle = os.path.join(ROOT, 'gradlew.bat')
        for task in ('configCharacterizationTest', 'configKnobsTest'):
            print('running %s ...' % task)
            r = subprocess.run([gradle, task, '--console=plain'], cwd=ROOT, env=env, capture_output=True, text=True)
            tail = (r.stdout + r.stderr).strip().splitlines()[-4:]
            print('\n'.join(tail))
            if r.returncode != 0:
                print('%s failed; see above' % task)
                return 1
        print('fixtures rewritten under src/test/resources/config-fixtures: review the git diff (expect 6 fixture files)')
    else:
        print('next:')
        print('  1. use the accessor where the constant was; add a pure test for the arithmetic;')
        print('  2. PD_CONFIG_REGEN=src/test/resources/config-fixtures ./gradlew.bat configCharacterizationTest configKnobsTest')
        print('     (or rerun this command with --regen on a fresh knob) and review the fixture diff;')
        print('  3. tools/ship.sh --pure, tools/check_dashes.sh, then say in your report that the server file')
        print('     config/pocketdungeons.json only needs the key if the owner wants a non-default value.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
