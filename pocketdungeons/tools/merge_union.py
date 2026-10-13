"""Resolve merge conflicts by keeping BOTH sides of every conflict hunk, in file order.

This is right for the files that every roadmap branch appends to: the gametest list in
src/gametest/resources/fabric.mod.json, the register lines in FinaleScripts.registerAll(), the knob accessors in
PocketDungeonsConfig, and the generated config fixtures. It is WRONG for anything where the two sides edit the same
line meaningfully; read those by hand.

    python tools/merge_union.py              resolve every unmerged file under this checkout
    python tools/merge_union.py FILE...      resolve only the named files
    python tools/merge_union.py --list       show the unmerged files and how many hunks each has, change nothing

Line endings are kept (CRLF stays CRLF). After running it:
  1. a .java file can be cut mid-method (the markers sometimes fall inside one): compile with
     `./gradlew compileJava compileTestJava` and fix a missing closing brace by hand;
  2. config fixtures and the default config file are generated: regenerate them (see the integrate-branches skill);
  3. `git add -A` and commit.
Run it from anywhere inside the checkout.
"""

import re
import subprocess
import sys

HUNK = re.compile(r'<<<<<<< [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n', re.S)


def top():
    out = subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit('not inside a git checkout')
    return out.stdout.strip()


def unmerged(root):
    out = subprocess.run(['git', 'diff', '--name-only', '--diff-filter=U'], cwd=root, capture_output=True, text=True)
    return [p for p in out.stdout.split('\n') if p]


def main(argv):
    root = top()
    listing = '--list' in argv
    names = [a for a in argv if not a.startswith('--')]
    files = names if names else unmerged(root)
    if not files:
        print('no unmerged files')
        return 0
    for rel in files:
        path = rel if re.match(r'^[A-Za-z]:|^/', rel) else root + '/' + rel
        raw = open(path, encoding='utf-8', newline='').read()
        crlf = '\r\n' in raw
        text = raw.replace('\r\n', '\n')
        hunks = len(HUNK.findall(text))
        if listing:
            print('%3d hunk(s)  %s' % (hunks, rel))
            continue
        if hunks == 0:
            print('no conflict markers in %s (already resolved?)' % rel)
            continue
        text = HUNK.sub(lambda m: m.group(1) + m.group(2), text)
        if '<<<<<<<' in text or '>>>>>>>' in text:
            print('WARNING: markers remain in %s (a nested or odd hunk); resolve by hand' % rel)
        open(path, 'w', encoding='utf-8', newline='').write(text.replace('\n', '\r\n') if crlf else text)
        note = '   <- compile it: a hunk can cut a method in half' if rel.endswith('.java') else ''
        print('kept both sides: %s (%d hunk(s))%s' % (rel, hunks, note))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
