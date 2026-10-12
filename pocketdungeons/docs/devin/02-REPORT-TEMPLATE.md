# Report template

Copy this into `docs/reports/<task-id>.md` on your task branch for every task. Keep it factual. No em dashes, no spaced
double hyphens.

```
# Report <task-id>: <title>

Branch: devin/<task-id>-<topic>   Base: feature/act-1-2-roadmap at <commit sha>
Status: done | done with known gaps | not finished

## Tests
- Gametests: N before, N after (the "All N required tests passed" line). Pure test tasks: before, after.
- New test classes (and where they are wired: build.gradle.kts, fabric.mod.json).
- Any test that fails and why. Do not hide a failure; name the test and your diagnosis.

## What changed
- Files added (one line each) and files edited outside your task's own files (one line each, why).
- Config knobs added (name, default, what it does).
- Data changed (JSON files).

## Decisions I made
- Numbers I chose and why (every "details open" in the design records is yours to settle; record them).
- Anything that departs from the task text, with the reason.

## What only a live server can confirm
- A bullet list: what to look at when a person plays it.

## Conflicts expected when merging
- Hot files you touched and what the integrator must keep from both sides.

## Fragments
- docs/playtests/live-checks/<task-id>.md written: yes/no
- tools/check_dashes.sh clean: yes/no
```

At the very end, send the owner one message: a table of branch, report path, status, test counts.
