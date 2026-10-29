# Start here (Devin)

Foundry is a server-side Fabric mod (Minecraft 26.2, Java 25) that turns JSON definitions into custom items, then
furniture and more, for vanilla clients through Polymer. Authoring happens through Claude Code skills and a
validator; there is no editor UI and you must not build one.

## Where things are

- Repo: the MrPinoys Mods workspace. The mod is the `foundry/` folder. Start from branch `feature/foundry-phase0`
  (check `git log` for the tip). Name your branches `devin/<task id>-<topic>`, one per task, cut from the tip.
- Read first, in order: `foundry/README.md`, `foundry/docs/ARCHITECTURE.md`, `foundry/docs/DECISIONS.md`,
  `foundry/docs/COMPATIBILITY.md`, then the workspace `CLAUDE.md`.
- Pure logic: `foundry/src/main/java/foundry/core/`. Minecraft code: `foundry/src/main/java/foundry/mc/`.

## Commands (Git Bash or PowerShell, from `foundry/`)

```bash
./gradlew.bat coreTest      # pure tests, about 30 s the first time, expect "CoreSuite OK, 18 checks" today
./gradlew.bat build         # compile, tests, jar
./gradlew.bat runServer     # dev server; needs run/eula.txt containing eula=true, content in run/config/foundry/content
```

Write long Java and JSON files with your editor tool, not shell heredocs (they failed on this machine for files
over about 100 lines). Windows shell; paths contain a space ("MrPinoys Mods"), always quote them.

## Rules that are not negotiable

1. No em dashes and no spaced double hyphens as punctuation in anything you write: code comments, docs, player
   text, commit messages. Use `:`, `;`, commas or a new sentence. Check with
   `grep -rn -e $'—' -e ' -- ' foundry/`.
2. `foundry.core` must not import `net.minecraft`, `eu.pb4` or `net.fabricmc`. Pass environment facts in as predicates.
3. No Mixins and no packet interception. If you believe one is needed, stop, write down why, and ask.
4. No per-item or per-furniture Java. Behaviour comes from definitions plus registered mechanic types.
5. Do not push, deploy, restart or touch any server. Deployment is the owner's call (`deploy-test-server` skill).
6. Do not rename the mod, change the pinned versions, or approve open decisions D3 and D4 yourself.
7. Do not report a feature as working unless a test or a run you did proved it. Say "not verified" plainly.
8. Commit authorship must be `MrPinoys123456789` (noreply email), never any other identity.
9. New gametest classes only run if listed in `foundry/src/gametest/resources/fabric.mod.json`; confirm the
   gametest count rises after you add one.
10. Player-facing and operator-facing text follows plain language: say what happened and what to do next. Every
    validation `Problem` carries a non-empty fix.

## Order of work

F0, F1, F2, F3 can be done in this order on separate branches; F4 starts with its decision record and stops for
owner approval. F5 to F8 follow. See `01-TASKS.md`. Report with `02-REPORT-TEMPLATE.md`. Traps are in `03-KNOWN-ISSUES.md`.
