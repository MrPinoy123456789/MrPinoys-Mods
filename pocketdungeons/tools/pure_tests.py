"""Run the pure (main() based) tests in parallel instead of one Gradle task after another.

    python tools/pure_tests.py [--jobs N] [--only SUBSTRING] [--no-compile]

Gradle runs the ~100 pure test tasks of this project one at a time, each in a fresh JVM that boots Minecraft's
registries, which is most of the 3 to 4 minutes `ship.sh --pure` takes. This asks Gradle (once) for the exact
command line of every task that `test` depends on (tools/gradle/pure-plan.init.gradle), then starts those
JVMs itself, N at a time (default: half the cores, at most 6). Same classes, same classpath, same working
directory, same exit code rule as Gradle (non-zero exit fails). The authoritative run is still
`tools/ship.sh --pure` or the full suite; use this while iterating.

Prints one line per failure with the tail of its output, and a final PURE FAST GREEN / PURE FAST FAILED line.
--only runs just the tasks whose name contains the substring (case-insensitive). --no-compile reuses the plan and
the classes from the last run.
"""
import concurrent.futures
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PLAN = os.path.join(ROOT, 'build', 'pure-plan.tsv')
INIT = os.path.join(HERE, 'gradle', 'pure-plan.init.gradle')


def make_plan():
    cmd = [os.path.join(ROOT, 'gradlew.bat'), '-I', INIT, 'pureTestPlan', '--console=plain', '-q']
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        print((r.stdout + r.stderr)[-3000:])
        print('PURE FAST FAILED: could not compile or write the plan')
        return False
    return True


def run_one(entry):
    name, exe, cwd, jvm, cp, main, args = entry
    fd, argfile = tempfile.mkstemp(suffix='.args', prefix='pure-')
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        f.write('-cp\n"%s"\n' % cp.replace('\\', '/'))
    cmd = [exe] + [a for a in jvm.split('\x01') if a] + ['@' + argfile, main] + [a for a in args.split('\x01') if a]
    t0 = time.time()
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, errors='replace', timeout=600)
        out, code = r.stdout + r.stderr, r.returncode
    except subprocess.TimeoutExpired:
        out, code = 'timed out after 600 s', 124
    finally:
        os.remove(argfile)
    return name, code, out, time.time() - t0


def main(argv):
    jobs = max(1, min(6, (os.cpu_count() or 4) // 2))
    only = None
    i = 0
    while i < len(argv):
        if argv[i] == '--jobs' and i + 1 < len(argv):
            jobs = int(argv[i + 1]); i += 1
        elif argv[i] == '--only' and i + 1 < len(argv):
            only = argv[i + 1].lower(); i += 1
        elif argv[i] != '--no-compile':
            print(__doc__)
            return 2
        i += 1
    start = time.time()
    if '--no-compile' not in argv and not make_plan():
        return 1
    if not os.path.exists(PLAN):
        print('no plan at %s; run without --no-compile' % PLAN)
        return 1
    entries = [l.split('\t') for l in open(PLAN, encoding='utf-8').read().splitlines() if l.strip()]
    if only:
        entries = [e for e in entries if only in e[0].lower()]
    failed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        for name, code, out, secs in pool.map(run_one, entries):
            if code != 0:
                failed.append(name)
                tail = '\n'.join(out.strip().splitlines()[-12:])
                print('FAILED %s (exit %d, %.1fs)\n%s\n' % (name, code, secs, tail[-1500:]))
    secs = time.time() - start
    if failed:
        print('PURE FAST FAILED: %d of %d: %s (%dm%02ds, %d jobs)' % (
            len(failed), len(entries), ', '.join(failed), secs // 60, secs % 60, jobs))
        return 1
    print('PURE FAST GREEN: %d pure test tasks (%dm%02ds, %d jobs)' % (len(entries), secs // 60, secs % 60, jobs))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
