# Prompt for Devin

Paste everything below the line.

---

You are building a feature set for **Pocket Dungeons**, a server-side Fabric mod (Java, Minecraft 26.2, Fabric API
0.156.0+26.2, no client mod and no resource pack). The repository is a workspace; the mod is in `pocketdungeons/`.

**What you are building:** the party now plays the party leader's campaign (a crew model, like REPO or Lethal Company);
the floor history board in the staging room becomes a 12 by 5 block terminal wall with three clickable tabs
(Objectives, Progress, History) and a stained glass window per act; a reusable clickable world object ("glyph") that
runs a named handler on right click; the astrolabe rebuilt on it and usable by anyone; act rewards (title, music disc,
window kit, decor kit, and for Act 1 a room shell) for every player present when an act completes; and a 10 minute
reconnect grace for the party leader.

**Everything is already designed.** Your job is to build it exactly as specified, not to redesign it. The UI words,
colours, positions, scales, pixel art and behaviour are in the spec.

Start by reading, in order:

1. `pocketdungeons/docs/devin/00-START-HERE.md` (orientation, branches, Linux build and test commands, house rules)
2. `pocketdungeons/docs/devin/01-SPEC.md` (the build spec)
3. `pocketdungeons/docs/devin/02-TASKS.md` (six tasks, T1 to T6, in order, one branch each, chained)
4. `pocketdungeons/docs/devin/04-KNOWN-ISSUES.md` (traps)
5. `pocketdungeons/docs/devin/terminal-mockup.html` (open it in a browser: a to-scale picture of the terminal; where
   it and the spec differ, the spec's numbers win)

Rules that matter most:

- **Never write an em dash or a spaced double hyphen (` -- `) as punctuation**: not in code, comments, strings, docs or
  commit messages. Use a colon, a semicolon, a comma, parentheses or a new sentence.
- One branch per task, `devin/<id>-<topic>`, each created from the previous task's branch. Do not push to `master`,
  merge, rewrite history, or deploy to any server.
- Before changing anything, run `./gradlew build test runGameTest dungeonIntegrationTest --console=plain` from
  `pocketdungeons/` on the base commit and record the counts. Every task ends green on all four, with its new tests
  added and registered (a gametest class not listed in `src/gametest/resources/fabric.mod.json` never runs; check the
  count rises).
- No new mixins. Do not invent player-facing words: if the spec lacks one, write `TODO(owner)` and list it.
- If something in the spec cannot work as written (an API missing, a wall position already taken), stop that part,
  explain what you found and what you would do, and carry on with the rest.

Deliverables: the six branches; a report per task at `pocketdungeons/docs/devin/report-<id>.md` on that task's branch
using `03-REPORT-TEMPLATE.md`; and `pocketdungeons/docs/devin/report-final.md` on the last branch summarising all six,
with the final test counts, every deviation, every `TODO(owner)`, and the list of live checks for the owner to play.
