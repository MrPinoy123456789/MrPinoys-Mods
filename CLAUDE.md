# House conventions

Applies to every mod in this workspace. Read it before writing anything a person
will read.

## Punctuation: no em dashes, no double hyphens

**Never write an em dash (the long one) or a double hyphen (`--`) as punctuation.**
Treat either one in your own output as a mistake to fix, not a style choice to
defend. This holds everywhere:

- player-facing and operator-facing strings (chat, dialogs, item lore, command
  output, log lines)
- markdown documents: specs, plans, handoffs, reports
- javadoc and code comments
- commit messages and pull request text

Reach for the punctuation that actually fits the sentence instead:

| Instead of a dash | Use | Example |
|---|---|---|
| introducing a list or an explanation | `:` | `The dungeon collapsed into its oldest shape: four rooms, heading east.` |
| joining two independent clauses | `;` | `Nobody was removed; your party had already changed.` |
| an aside, mid sentence | `,` or `( )` | `slot 3 at 128,64,0 (UNTIMED, never expires): Bob` |
| a hard break between thoughts | `.` and a new sentence | `Feral: wolves in the halls. Swing and they are lost.` |
| a label and its value | `-` or `:` | `Ominous Door - Keystone [5]` |

If a sentence seems to need a dash, it usually needs to be two sentences.

**Not covered by this rule** (these are not punctuation, leave them alone):

- command line flags: `--warning-mode`, `git log --oneline`, `gradlew --stacktrace`
- operators in code: `i--`, `count--`
- horizontal rules in markdown (`---`) and table separators
- a single hyphen in a compound word (`server-side`, `client-side`, `read-only`)
- section separator comments in existing source (`// ---- section 1 ----`)
- text somebody else wrote: a quoted spec, a vendored file, a sibling mod's docs

**Do not mass rewrite existing comments to satisfy this.** Plenty of older code in
this workspace uses `--` in javadoc; it stays until that line is being edited for
another reason. The rule governs what you write from now on.

To find violations in text you just wrote:

```bash
grep -rn -e $'—' -e ' -- ' --include='*.java' --include='*.md' .
```

## Lemon and shared playtest tooling

- `lemon/` is an independent server-side Fabric mod. Run `./gradlew.bat build`
  there for its pure tests, gametests and jar. It has no game-mod dependencies.
- Pocket Dungeons reaches Lemon only through registered commands and the
  `lemon.part` scoreboard tag. Its default test suite runs without the Lemon jar.
  To test with it, build Lemon first and pass
  `-PlemonTestJar=../lemon/build/libs/MrPinoys_Lemon-0.1.0.jar` to PD's gametest run.
- Shared RCON, Kinetic, MCP and event-filter tools live in `tools/playtest/`.
  `playtest.mjs` is canonical; `pdserver.mjs` forwards to it. Node regressions:
  `node --test tools/playtest/*.test.mjs` from the workspace root.
- Lemon runtime configuration is `config/lemon/lemon.json`. Its journal is
  `<world>/lemon/journal/<UTC date>/<uuid>.jsonl`, separate from game journals.
  The generic interviewer runbook is `lemon/docs/LEMON_AGENT.md`.
- A PD test-server deployment ships both PD and Lemon jars. Implementation work
  does not authorize deployment or restart. Follow the deploy skill's approvals.
- If the Windows ship launcher fails in `JansiLoader.cleanup` with a heap error,
  use a small dedicated temp directory via `PD_TMP` and a larger launcher heap via
  `JAVA_OPTS=-Xmx512m`; do not delete shared temp files or change project policies.

## Gametest entity queries

`ServerLevel.getChunk` loads blocks, not necessarily the chunk's entity sections.
Before immediately counting spawned mobs, load every touched chunk (including
spawn-ring overshoot) and call `level.waitForEntities(chunkPos, 0)`. Repeat before
a delayed spawn if the chunks could unload. Scope entity counts and cleanup to
the owning floor, not just a shared mob-kind tag.

`GameTestInfo.succeed` removes entities inside that test's structure bounds,
including another test's mobs. Keep long-lived arena fixtures outside neighbouring
harness cleanup bounds, and give independently stamped navigation cases separate
cells rather than reusing one cell's terrain and path cache in the same tick.
Chunk-scoped helpers such as `AoETelegraph.clear` require distinct chunks for
concurrent fixtures; different origin tiles inside one chunk are not isolated.
