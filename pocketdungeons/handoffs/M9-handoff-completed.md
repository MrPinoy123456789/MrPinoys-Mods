# M9 - Refactor and cleanup - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md` - the punctuation rule. It is enforced, and it
   applies to every line you write including javadoc and commit messages.
2. `pocketdungeons/plans/COMPLETED-MILESTONES.md` M9 - **the authoritative scope.** Every
   phase, every verified finding with its file and line number, and the
   explicit not-in-scope list at the bottom.
3. `pocketdungeons/PROGRESS.md` - the process rules at the top, and the M7
   table (which is still `WIP` and is your first problem).
4. `pocketdungeons/DISCOVERIES.md` - the 26.2 API findings. Do not rediscover
   these the hard way.
5. `pocketdungeons/DOOR_LADDER_BRAINSTORM.md` - **context only, do not
   implement.** It is the design this refactor is clearing the way for. Read it
   so you understand why the cuts fall where they do; it is not your scope.

## Goal

The codebase can absorb the door/ladder reframe without the reframe having to
be written inside a 3,000 line class. **Nothing a player can see changes.**
Every commit in this milestone is behaviour preserving.

## The single most important rule

This is a refactor, not a rewrite. **Move code, do not improve it.** If you
notice a bug, a bad name, a redundant branch or a better algorithm while moving
a method, write it down and leave it exactly as it is. Fix it in a separate,
clearly labelled commit after the move has landed.

The reason is not politeness, it is reviewability. `Instances.java` has almost
no test coverage. The only way anyone can verify a 3,000 line file was split
safely is by reading a diff that consists purely of moves. One "while I was in
there" change hidden in a move commit destroys that, and there is no test that
would catch what it broke.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. Unverified claims
   get a `WARNING UNVERIFIED` comment in the source.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   items, no custom sounds. Everything is vanilla blocks, vanilla items,
   vanilla sound events, and server-side dialogs.
3. **No em dashes and no double hyphens** in anything you write. See
   `CLAUDE.md`. Do not mass rewrite the existing ones; plenty of older javadoc
   in this mod uses them and they stay until that line is being edited anyway.
4. **`./gradlew build` green after every commit.** Not at the end. After every
   commit.
5. **`PROGRESS.md` is the only file that records status.** Claim a task as
   `WIP` with your name and the date before your first edit.

## Start here, in this order

### C0 first, and alone

The working tree is dirty with roughly 5,700 changed lines of uncommitted M5,
M6 and M7 work, including seven untracked source files and two untracked
resource trees. `git status --short` will show you.

**Do not touch anything else until this is committed.** Run `./gradlew build`,
confirm green, then commit it in coherent slices (theme foundation, recipe
system, supply tiering, feral content, loot tables), each one compiling. Update
the M7 table in `PROGRESS.md` honestly: `DONE` for what is finished, `WIP` with
a note for what is not, but the code gets committed either way.

If you find the tree does not build, stop and report that before doing anything
else. That changes the shape of this milestone.

### Then C1 through C5

Follow `plans/COMPLETED-MILESTONES.md` M9. C1 (dead code) and C2 (shell palette) are
independent and either can go first. C3 (the split) needs C1 done. C4 and C5
can land any time after C0.

For C3, do extraction 1 (`CellGeometry`) before anything else in that phase.
It is pure coordinate math with no Minecraft imports, which means it gets unit
tests, which means the rest of the split has a safety net it does not have
today.

## Verification bar

Code:

- `./gradlew build` green, all existing tests pass, plus the new
  `CellGeometryTest`.
- `Instances.java` under 600 lines. No newly created class over 500.
- `git status --short` shows nothing under `src/`.

Behaviour, once at the end, from `CLIENT_TEST_CHECKLIST.md`:

- Enter a run with a keystone, choose a door, walk the dungeon, reach the
  terminal pad, take the reward chests, leave.
- Re-enter and confirm the room came back with its contents intact.
- Visit another player's room via a calling card.

Nothing in that sequence may feel different from before M9. If something does,
you changed behaviour somewhere and need to find it.

## What you must not do

The plan has a not-in-scope list and it is load-bearing. In particular:

- **Do not delete `Affix.FRAGILE` or the recipe system.** Both are scheduled
  for removal by the door/ladder reframe. Removing them here is a behaviour
  change wearing a cleanup disguise, and it would break live saves.
- **Do not implement anything from `DOOR_LADDER_BRAINSTORM.md`.** No spawner
  gating, no mob scaling, no adventure graph, no fuel sink, no copper bulbs, no
  lever, no room skins. C2 makes skins *possible*; it does not build them.
- **Do not change loot table contents.** C4 validates that the ids resolve. It
  does not touch what is inside the files.
- **Do not run a punctuation sweep** across the existing documents.

## Doc updates you owe on completion

1. `PROGRESS.md`: the M9 table with real statuses, and one Session Log line
   (newest first).
2. `ROADMAP.md`: add M9 in its place in the order.
3. `plans/COMPLETED-MILESTONES.md` M9: if you diverge from the plan, **fix the plan first
   and say so**, per process rule 3. Never silently diverge.
4. The new `pocketdungeons/README.md` from C5.
5. Rename this file to `M9-handoff-completed.md` once M9 is actually `DONE` in
   `PROGRESS.md`.

## If you get stuck

The two most likely failure modes:

- **A move will not compile because of package-private visibility.** That is
  fine and expected; every extracted class stays in the `pocketdungeons`
  package, so package-private access still works. If you are reaching for
  `public`, you are probably splitting along the wrong seam. Re-read the C3
  table.
- **A method genuinely belongs in two of the new classes.** Put it in the one
  that is lower in the C3 table (extracted earlier) and have the later one call
  it. Extraction order in that table is dependency order.
