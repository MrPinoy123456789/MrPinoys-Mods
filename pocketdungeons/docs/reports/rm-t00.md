# rm-t00: tooling and skills for faster builds

Branch `feature/rm-t00-tooling`, off `feature/act-1-2-roadmap` (merged again before the final run).

## Finding: what actually binds a port

- The gametest server (`runGameTest`, Fabric `GameTestServer`) binds NO port. Its log has `Starting test server` and
  never `Starting Minecraft server on`. Its run directory is `build/run/gameTest`, already one per worktree, and
  `dist` is the worktree root `dist`, also per worktree. So gametest suites in different worktrees do not clash.
- The thing that did bind a fixed port was `dungeonIntegrationTest` (a real dedicated server on 25581). `test`
  depends on it, so even a "pure" `test` run could not run in two worktrees at once. `build.gradle.kts` now reads
  `PD_INTEGRATION_PORT` (default 25581, two lines, the only build file change) and `ship.sh` gives each run a free
  port in 25600 to 25999.
- What parallel suites still share is the machine, so the full suite takes one of 3 slots (`PD_SHIP_SLOTS`). Slot 1
  is the old directory `A:/tmp/gradle-suite.lock`, so old-script agents and new-script agents exclude each other.
  Slots 2 and 3 are `A:/tmp/gradle-suite.lock.2` and `.3`. A slot older than 45 minutes is reclaimed.
- The old log `A:/tmp/ship.log` was shared by every agent; it is now `A:/tmp/ship-<worktree>.log`.

## Tools

| Tool | What it does | Checked |
|---|---|---|
| `tools/ship.sh` | Old options kept. New: `--pure` (compile + `test`, no lock), `--pure-fast`, `--no-dist`, `--only <Name>`. Prints slot, filter, `All N required tests passed` and elapsed. | each option run |
| `--only` | Fabric has `fabric-api.gametest.filter`; an init script (`tools/gradle/gametest-filter.init.gradle`) passes it to `runGameTest` without touching the build. `HerdGameTest` becomes `pocketdungeons-gametest:herd_game_test*`; a lowercase name matches a method anywhere; a name with `:` or `*` is used as given. | HerdGameTest, a method, a scaffold probe: 1 test, ~20 s |
| `tools/pure_tests.py` | Asks Gradle (init script `pure-plan.init.gradle`) for the exact command line of every pure test task and runs them 6 at a time. | 105 tasks green |
| `tools/new_worktree.sh <id> <topic>` | `.claude/worktrees/rm-<id>`, branch `feature/rm-<id>-<topic>` off the base, merges base on rerun, prints path. | create, rerun, bad id, cleaned up |
| `tools/check_dashes.sh` | Added lines vs the merge base with `feature/act-1-2-roadmap` (commits, staged, unstaged, untracked). Flags an em dash or ` -- `; skips flags, `i--`, `git ... -- path`, a `dashes-ok` marker. Exit 1 with file:line. | tested staged, committed, untracked |
| `tools/new_gametest.py <Name>` | Scaffolds the class (HerdGameTest idiom), registers it in `fabric.mod.json` (CRLF kept) at a name-derived position so branches do not conflict. | probe class ran as 1 test |
| `tools/new_knob.py <name> <default> <desc>` | Adds the `Knob` constant and accessor to `PocketDungeonsConfig`; `--regen` rewrites the config fixtures (six files) and runs the two config tests. `config/pocketdungeons.default.json` is a stale sample nothing reads, so it is not touched. | probe knob: both config tests passed after regen; reverted |
| `tools/livecheck.py new/fold` | Fragment per task in `docs/playtests/live-checks/rm-<id>.md`; `fold` numbers sections L67 onward, appends CRLF-safe, moves to `folded/`, skips fragments with TODO, `--dry-run`. | new, refuse overwrite, skip, dry run, fold; reverted |

## Timings (machine shared with other agents, so noisy)

- `--pure` through Gradle: 3m24s, 3m50s, 4m05s (106 sequential JVMs, about 2.3 s each).
- `--pure-fast`: 1m26s and 1m51s (6 jobs). It skips the dedicated server run that `test` also does.
- Final `ship.sh --quick`: SUITE GREEN, 256 required gametests, 4m22s, slot 2 (while other agents ran).
- `--only <class>`: about 20 s against about 3 min for the whole gametest run.
- Gradle settings: `parallel` is already on and only helps multi-project builds (this has one project). The daemon is
  on by default. Configuration takes about 2 s, so the configuration cache saves almost nothing and Loom support is
  a risk: not enabled. The build cache cannot help: the pure tests are `JavaExec` tasks with no declared outputs.
  `gradle.properties` is unchanged. The real saving was running the JVMs in parallel (`pure_tests.py`).

## Skills

`agent-brief` rewritten (new base, hot files, fragments, scaffolds, run order). `add-tests` and `tune-knobs` point at
the scaffolds (and the knob fixture count is six, not three). New `add-affix`.

## Not done or to know

- `build.gradle.kts` was edited (the integration port); everything else is under `tools/` and `.claude/skills/`.
- `--pure-fast` does not replace `--pure` or the full suite; the dedicated server check only runs in those.
- Slot 1 is shared with agents still on the old script, so only 2 more slots add parallelism while they run.
- The affix symmetry rule is enforced at load by `AffixManifest`, not by a dedicated test; `add-affix` says to add
  the check to the affix's own test.
