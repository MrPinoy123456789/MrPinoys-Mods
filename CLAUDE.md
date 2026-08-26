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
