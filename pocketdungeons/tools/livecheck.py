"""Live check fragments, so no task edits docs/playtests/LIVE_CHECKS.md (a hot file) directly.

    python tools/livecheck.py new <id> "<title>"     write docs/playtests/live-checks/rm-<id>.md
    python tools/livecheck.py fold [--dry-run]       integrator only: append every fragment to LIVE_CHECKS.md

new: <id> is the roadmap task id (T10). The fragment has the shape of the newer entries in LIVE_CHECKS.md:
a heading, a Status line, then Changed, Do, Pass and Fail signs. Fill in every TODO. A fragment may hold more
than one "## " section when a task owes more than one check. Refuses to overwrite an existing fragment.

fold: gives each "## " section the next free number (## L67: title), appends it to the end of LIVE_CHECKS.md
(CRLF kept) and moves the fragment to docs/playtests/live-checks/folded/. A fragment that still has a TODO
is skipped and named. Fragments fold in file name order, so the numbering is the same on every run.
"""
import datetime
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from pyedit import read, save  # noqa: E402

ROOT = os.path.dirname(HERE)
PLAYTESTS = os.path.join(ROOT, 'docs', 'playtests')
LIVE = os.path.join(PLAYTESTS, 'LIVE_CHECKS.md')
FRAGMENTS = os.path.join(PLAYTESTS, 'live-checks')
FOLDED = os.path.join(FRAGMENTS, 'folded')

TEMPLATE = '''## %(title)s
- **Status:** owed (built, never played; listed here so the knowledge pack carries it)
- **Changed:** TODO what a player can now see or do, in plain words, with the PD number or the roadmap task.
- **Do:** TODO the steps to play, with how many accounts, what to set up, and what to leave alone.
- **Pass:** TODO what the player sees when it works, as signs a watcher can tick off.
- **Fail signs:** TODO what a break looks like (a disconnect, a wrong number, a missing sound or line).
'''


def new(task_id, title):
    task_id = task_id.lower()
    if not re.fullmatch(r't\d+[a-z]?', task_id):
        print('task id looks wrong: %s (expected something like T10)' % task_id)
        return 2
    if chr(0x2014) in title or ' ' + '-' * 2 + ' ' in title:
        print('the title has an em dash or a spaced double hyphen; rewrite it (CLAUDE.md)')
        return 2
    title = re.sub(r'^L\d+[.:]?\s*', '', title.strip())
    if not re.search(r'\(20\d\d-\d\d-\d\d\)', title):
        title += ' (%s)' % datetime.date.today().isoformat()
    path = os.path.join(FRAGMENTS, 'rm-%s.md' % task_id)
    if os.path.exists(path):
        print('refusing: %s exists; edit it (add another "## " section for a second check)' % path)
        return 1
    os.makedirs(FRAGMENTS, exist_ok=True)
    save(path, TEMPLATE % {'title': title}, True)
    print('wrote docs/playtests/live-checks/rm-%s.md' % task_id)
    print('fill in every TODO, commit it with your branch, and do NOT edit LIVE_CHECKS.md; the integrator folds it in.')
    return 0


def sections(text):
    """Split fragment text into its '## ' sections, each a list of lines with no trailing blanks."""
    out, cur = [], None
    for line in text.split('\n'):
        if line.startswith('## '):
            cur = [line]
            out.append(cur)
        elif cur is not None:
            cur.append(line)
    for s in out:
        while s and not s[-1].strip():
            s.pop()
    return out


def fold(dry):
    if not os.path.isdir(FRAGMENTS):
        print('no fragments (docs/playtests/live-checks does not exist)')
        return 0
    names = sorted(f for f in os.listdir(FRAGMENTS) if f.endswith('.md') and os.path.isfile(os.path.join(FRAGMENTS, f)))
    if not names:
        print('no fragments to fold')
        return 0
    live, crlf = read(LIVE)
    nxt = max([int(m.group(1)) for m in re.finditer(r'^## L(\d+)', live, re.M)] or [0]) + 1
    added, folded, skipped = [], [], []
    for name in names:
        text, _ = read(os.path.join(FRAGMENTS, name))
        secs = sections(text)
        if not secs:
            skipped.append('%s (no "## " heading)' % name)
            continue
        if 'TODO' in text:
            skipped.append('%s (still has a TODO)' % name)
            continue
        for sec in secs:
            title = re.sub(r'^##\s*(L\d+[.:]?\s*)?', '', sec[0]).strip()
            sec[0] = '## L%d: %s' % (nxt, title)
            print('%s -> L%d: %s' % (name, nxt, title))
            nxt += 1
            added.append('\n'.join(sec))
        folded.append(name)
    if added and not dry:
        body = live.rstrip('\n') + '\n\n' + '\n\n'.join(added) + '\n'
        save(LIVE, body, crlf)
        os.makedirs(FOLDED, exist_ok=True)
        for name in folded:
            dest = os.path.join(FOLDED, name)
            if os.path.exists(dest):
                os.remove(dest)
            shutil.move(os.path.join(FRAGMENTS, name), dest)
    for s in skipped:
        print('skipped %s' % s)
    print('%s %d section(s) from %d fragment(s)' % ('would fold' if dry else 'folded', len(added), len(folded)))
    return 1 if skipped else 0


def main(argv):
    if len(argv) == 3 and argv[0] == 'new':
        return new(argv[1], argv[2])
    if argv and argv[0] == 'fold' and set(argv[1:]) <= {'--dry-run'}:
        return fold('--dry-run' in argv)
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
