# M10 - Ladder reframe: the level gates access, difficulty lives on the map - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M10 - Ladder reframe: the level gates access, difficulty lives on the map` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.

## Before you start: confirm the dependency is actually done

None. M9 is code-complete and the tree is clean; this is the first milestone
in the D3 plan and can start immediately.

## Goal, in one line

Raise the keystone cap from 25 to 100, scale mob strength with the run's
level, spread the affix thresholds and intensifier bands across the new range,
retire `Affix.Kind.ELECTIVE` (drop `FRAGILE`, migrate `OMINOUS` to seeded), and
gate run completion on clearing a fraction of the run's trial spawners. After
this milestone, a level-100 key is not "the same three doors, scarier": the
ladder has room to climb and a run cannot be sprinted past its own content.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
   (POSIX form for Git Bash: `/c/Users/Kriss/.gradle/...`). Checking a method
   exists is not the same as checking what it does. This is trap 1 in
   `DISCOVERIES.md` and it has shipped three bugs.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   items, no custom sounds. Everything is vanilla blocks, vanilla items,
   vanilla sound events, and server-side dialogs.
3. **No em dashes and no double hyphens as punctuation**, anywhere a person
   reads: chat, dialogs, item lore, command output, log lines, markdown,
   javadoc, commit messages. Use `:`, `;`, `,`, `()`, or two sentences.
   Command-line flags and code operators (`i--`) are not punctuation and stay
   as they are. Do not mass rewrite existing violations; they stay until that
   line is edited for another reason. See `A:\MrPinoys Mods\CLAUDE.md`.
4. **`./gradlew build` green after every commit**, not just at the end.
5. **The one-mixin budget is exactly one**, `mixin/CustomClickMixin`. If this
   milestone seems to need a second mixin, say so in your report and exhaust
   the Fabric-event or datapack-recipe route first (trap 9, and traps 14/15 in
   `DISCOVERIES.md` are a worked example of that search paying off).
6. **Status lives in `plans/COMPLETED-MILESTONES.md` and
   `docs/LIVE_TEST_PASS.md` now, not a `PROGRESS.md` file.** That file and the
   `handoffs/` folder were retired once M0-M9 went code-complete. Do not
   recreate either.
7. **One commit per logical change, message explains why, not just what.**
8. **`Codec` migration discipline:** a superseded `DungeonLog.Entry` field is
   marked superseded in its javadoc and its codec field kept, never deleted on
   the first pass. See "Per-player persistent state" in the plan's
   implementation-context section.

## Start here

- Cap bump first (`PocketDungeonsConfig.keystoneMaxLevel`, one line and one
  default); it is the cheapest change and unblocks nothing else, but doing it
  first means every later check in this milestone runs against the real range.
- Affix thresholds and intensifier bands next; both are pure-Java changes with
  no world dependency, and the plan's implementation notes give the exact
  functions to touch (`AffixMath.seededCount`, `AffixMath.intensifier`).
- `FRAGILE`/`OMINOUS`/`Kind.ELECTIVE` third, in the three-file order the plan
  specifies (`Affix.java`, then `AffixMath.java`, then `DungeonLog.java`).
  Grep `FRAGILE` across the tree before you consider this done; the compiler
  will not catch every `case FRAGILE` arm on its own if a switch is not
  exhaustive-checked.
- Mob scaling and the spawner-gated completion last; both are new listeners
  and both have a blocking jar-verification question (the exact
  `ServerLivingEntityEvents` hook, and whether an untouched trial spawner sits
  at `INACTIVE` and must be excluded from the clear-fraction denominator).
  Verify against the jar before writing either listener's body.

## What you must not do

- **Do not touch the door-offer plumbing.** `Keystone.offers` needs to keep
  compiling (drop the `FRAGILE` line, make door 3 a plain `EnumSet`), but the
  actual free/Greater tier split is M12's job. Do not pre-build it here.
- **Do not implement the adventure graph.** M11's job. This milestone's door
  offers keep using whatever theme-selection mechanism exists today.
- **Do not delete the `keystoneAffix` codec field** when you retire elective
  storage. Mark it superseded in its javadoc; removal waits for a migration
  pass that confirms no live save carries elective data.
- **Do not guess the spawner-clear percentage or the total-completion affix's
  kiss.** Both are explicitly open, in-milestone tuning; settle them during
  implementation, not before.

## Verification bar

**Done when** (copied from the plan, verbatim): a level-100 key is mintable,
mobs in its run hit roughly 2x strength, the keystone name reads a sensible
intensifier across the whole range, `Affix.Kind.ELECTIVE` no longer exists,
and a run that sprints to the pad without clearing spawners is refused
completion with a message.

**Jar verification required before writing code** (both are named "Verify
against the 26.2 jar" in the plan, not "Open"): the exact
`ServerLivingEntityEvents` callback that fires after a mob is placed but
before it first ticks, and whether `TrialSpawnerState.COOLDOWN` requires prior
activation or an untouched spawner needs excluding from the clear-fraction
count.

**Live-only:** the refusal message on an incomplete pad contact. Record it in
`docs/LIVE_TEST_PASS.md`; everything else in this milestone (cap, thresholds,
bands, the enum change) is headless-verifiable through the pure-Java test
tasks the plan asks for.

## Doc updates you owe on completion

1. `plans/COMPLETED-MILESTONES.md`: add this milestone's summary in its place,
   in the same architectural-summary style as M0-M9's entries.
2. `docs/LIVE_TEST_PASS.md`: add a numbered section for whatever in this
   milestone is client-interactive and cannot be verified headless (the plan's
   "Implementation notes" flag which parts those are).
3. `docs/ROADMAP.md`: this milestone's entry, in order. No checkboxes; the
   roadmap carries order, not status.
4. `docs/D3_PROGRESSION_PLAN.md`: if you diverge from the plan's scope for
   this milestone, **fix the plan first and say so.** Never silently diverge.
5. Any `DungeonLog.Entry` field this milestone supersedes gets a javadoc note
   saying so; its codec field stays until a migration confirms no live save
   carries the old data.
6. Rename this file `M10-handoff-completed.md` once the milestone is actually
   landed and its `COMPLETED-MILESTONES.md` entry exists.

## If you get stuck

- **A jar-verification task contradicts what the plan assumed.** Stop and
  report it before writing code around it. The plan names exactly which
  assumptions are unverified for this milestone; a wrong one can change the
  milestone's shape, not just its tuning.
- **You are reaching for a second mixin.** You have probably taken a wrong
  turn. Re-read "The one-mixin budget" in the plan's implementation-context
  section and the worked examples in `DISCOVERIES.md` traps 14 and 15 before
  concluding a mixin is actually necessary.
- **An "Open (tuning, in-milestone)" question is blocking you from writing
  any code at all.** That is a sign it is not actually tuning for this case;
  say so in your report rather than guessing silently.
