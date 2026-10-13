"""One table of where every sub-agent worktree stands, read from git, never from what an agent said.

    python tools/agent_status.py [BASE]

BASE is the integration branch (default feature/act-1-2-roadmap, or the current branch if that does not exist). For
each worktree it prints: the branch, commits it has that BASE does not, uncommitted files, whether it has a report in
docs/reports, and a verdict:

    merged     nothing the base lacks, nothing uncommitted: safe to remove the worktree and delete the branch
    unmerged   commits the base lacks: merge it (or read why not)
    dirty      uncommitted files: the agent was cut off mid-edit; commit them to its branch before anything else
    locked     the harness still holds a lock; `git worktree unlock PATH` before `git worktree remove --force PATH`

A cut-off agent's last message is not evidence of its state; this table is. Run it after every rate limit stop and
before cleaning up.
"""

import os
import subprocess
import sys


def git(*args, cwd=None):
    r = subprocess.run(['git', *args], cwd=cwd, capture_output=True, text=True)
    return r.stdout.strip()


def worktrees():
    out = git('worktree', 'list', '--porcelain')
    cur = {}
    for line in out.split('\n') + ['']:
        if line.startswith('worktree '):
            cur = {'path': line[9:]}
        elif line.startswith('branch '):
            cur['branch'] = line.split('refs/heads/')[-1]
        elif line.startswith('locked'):
            cur['locked'] = True
        elif line == '' and cur.get('path'):
            yield cur
            cur = {}


def main(argv):
    base = argv[0] if argv else 'feature/act-1-2-roadmap'
    if not git('rev-parse', '--verify', '--quiet', base):
        base = git('branch', '--show-current') or 'HEAD'
    print('base: %s' % base)
    rows = []
    for wt in worktrees():
        path, branch = wt['path'], wt.get('branch', '(detached)')
        ahead = git('rev-list', '--count', '%s..%s' % (base, branch)) if wt.get('branch') else '?'
        dirty = git('status', '--short', cwd=path)
        ndirty = len(dirty.split('\n')) if dirty else 0
        tag = os.path.basename(path)
        report = any(f.startswith('rm-') or f.startswith('d') for f in
                     os.listdir(os.path.join(path, 'pocketdungeons', 'docs', 'reports'))) \
            if os.path.isdir(os.path.join(path, 'pocketdungeons', 'docs', 'reports')) else False
        if wt.get('locked'):
            verdict = 'locked'
        elif ndirty:
            verdict = 'dirty'
        elif ahead not in ('0', '?'):
            verdict = 'unmerged'
        else:
            verdict = 'merged'
        rows.append((verdict, branch, ahead, ndirty, 'yes' if report else 'no', tag))
    rows.sort()
    print('%-9s %-42s %6s %6s %6s  %s' % ('verdict', 'branch', 'ahead', 'dirty', 'report', 'worktree'))
    for r in rows:
        print('%-9s %-42s %6s %6d %6s  %s' % r)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
