# M15 - Armor trims (3.3): consumed templates that grant a real bonus - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M15 - Armor trims (3.3): consumed templates that grant a real bonus` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.
5. This milestone has a **blocking** jar-verification task (see "Verify
   against the 26.2 jar" in its plan section): whether trim-template
   duplication is a data-driven recipe type that can be overridden, and
   whether a template carries any distinguishing data once it leaves the loot
   table. Do the `javap` work before writing any other code; the answer
   changes this milestone's shape, not just its tuning.

## Before you start: confirm the dependency is actually done

**Hard dependency on M13.** Confirm trim templates and trim materials are
actually present in the tier loot tables, with the `pocketdungeons.tier` tag,
before starting.

## Goal, in one line

Armor trim templates drop as dungeon loot. Applying one at a smithing table
permanently consumes it (the vanilla duplication recipe is removed for these).
The trim material grants the piece a real combat bonus, not just a colour.
This is the first idea in the plan that reaches past the dungeon loop into
ordinary overworld combat, and that is a deliberate commitment, not an
accident.

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

- **Do the blocking jar work first, before any other code.** Confirm via
  `javap` whether the duplication recipe (`minecraft:smithing_trim` or
  equivalent) is a data-driven recipe type overridable by a datapack recipe
  returning no result, and whether a `TrimTemplate` item can carry a
  distinguishing component once it leaves the loot table. This decides
  whether the removal is conditional (dungeon-found templates only) or global
  (every copy of that template id). Trap 14 in `DISCOVERIES.md` (recipe
  results support arbitrary components) is the strongly preferred route;
  reach for a mixin only if the datapack route is provably insufficient, and
  say so in your report against the one-mixin budget.
- Author the material-to-attribute config table next (ten materials, one
  bonus each), since the equip-time plumbing needs somewhere to look values
  up.
- Build the equip-time attribute listener as two explicit steps: read the
  worn-piece signal, then apply the configured modifier. The plan is explicit
  that this separation matters because M17 reuses this plumbing by swapping
  the signal source, not by rewriting the modifier-application logic.
- Settle the dungeon-only-vs-global bonus question in-milestone; it is a
  config flag (`trimBonusDungeonOnly`), not a structural decision, but it is
  a real commitment either way and worth deciding deliberately rather than
  defaulting silently.

## What you must not do

- **Do not write the equip-time listener as a one-off hardcoded to trims.**
  The plan is explicit that M17 generalises this plumbing from "what trim is
  on this piece" to "which extracted power am I slotting." Keep the
  signal-read and the modifier-application as two separable steps from the
  start.
- **Do not skip the blocking jar-verification task.** It can change whether
  the duplication removal is conditional or global, which changes what you
  author in the loot tables.
- **Do not author 170 material x pattern bonus cells.** The material decides
  the bonus; the pattern stays cosmetic and a rarity signal only. This is a
  deliberate axis split, not a shortcut.
- **Do not reach for a mixin before exhausting the datapack-recipe route.**
  See "The one-mixin budget" in the standing rules below.

## Verification bar

**Done when** (copied from the plan, verbatim): a dungeon-found trim template
applies at a smithing table, consumes the template, grants the piece a
material-based combat bonus, and cannot be duplicated.

**Jar verification (blocking):** whether trim-template duplication is a
data-driven recipe type that can be conditionally excluded, and whether a
template carries any distinguishing data once it leaves the loot table. This
is one of only two jar-verification tasks in the whole plan that can change a
milestone's shape rather than just its tuning (M17 has the other).

**Live-only:** the smithing-table apply, the worn bonus in actual combat, and
the duplication refusal. Record in `docs/LIVE_TEST_PASS.md`. The
material-to-attribute config parse, modifier UUID stability, and the
equip-time signal read against a synthetic worn stack are headless-verifiable.

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
6. Rename this file `M15-handoff-completed.md` once the milestone is actually
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
