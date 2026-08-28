# M12 - Two-tier doors: the Nephalem/Greater Rift split, plus fuel - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M12 - Two-tier doors: the Nephalem/Greater Rift split, plus fuel` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.
5. `pocketdungeons/docs/DISCOVERIES.md` traps 13 to 17, specifically: this
   milestone's plan section records a deferred crafted-fourth-door idea and
   points here for what was verified before deferring it. You are not
   building the fourth door; you should understand why it is not in scope.

## Before you start: confirm the dependency is actually done

**This milestone hard-depends on M10 and M11 actually being real code, not
just plan sections.** Check `plans/COMPLETED-MILESTONES.md` for entries for
both before starting. Specifically confirm:

- M10 landed: `PocketDungeonsConfig.keystoneMaxLevel` is 100, `Affix.FRAGILE`
  no longer exists, `Affix.Kind.ELECTIVE` no longer exists.
- M11 landed: `Keystone.offers` already pulls themes from an `AdventureGraph`
  and `currentTheme`, not from `DungeonRecipes`/`RecipeMatcher`.

If either is missing, stop and say so. This milestone's rewrite of
`Keystone.offers` assumes both are already in the shape M10 and M11 leave
them in; building on top of the pre-M10/M11 shape will conflict with those
milestones landing later.

## Goal, in one line

Door 1 becomes the free, fuel-producing, non-depleting farming tier. Doors 2
and 3 become the timed, fuel-consuming, depleting, level-gated Greater tier,
drawn from the adventure graph. This is what makes "the level gates which
doors are available" concrete: a level-100 key reaches door-2/3 offers a
level-10 key cannot open at all.

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

- Settle the fuel currency first (echo shards vs diamonds; the plan's "Fuel
  currency" section at the bottom has the full tradeoff table). This is a
  config default and a loot-table content decision that everything else in
  this milestone assumes.
- Rewrite `Keystone.offers` next, following the plan's implementation notes'
  call-order list exactly: `Keystone.offers` -> `DialogScreens.doorOffer` ->
  `RitualListener.sendDoorOffer` -> `RunLifecycle.chooseOffer` ->
  `Keystones.grantOffer`. The fuel spend and the level-gate refusal both live
  in `chooseOffer`, ahead of `grantOffer`, so a successful choice is atomic
  with the spend.
- Add the `freeDoor`/tier field to `InstanceRecord` before touching
  `Keystones.returnTo`'s depletion logic; door 1's "never depletes" behaviour
  needs that field to know which runs to exempt.
- Loot-table edits (fuel payout on door 1, differentiated premium-room loot)
  are JSON-only and can happen in parallel with the Java work.
- Read the "Deferred: a crafted fourth door" bullet in this milestone's plan
  section once, so you know it exists and why it is explicitly out of scope,
  then do not think about it again this milestone.

## What you must not do

- **Do not build the crafted fourth door.** It is recorded as deferred in this
  milestone's own plan section and in `DISCOVERIES.md` traps 13-17. It depends
  on this milestone and M11 settling first, not the other way around.
- **Do not let door 1 become the only door anyone takes, or let it stall the
  loop.** The plan calls out both failure modes explicitly under "Pacing the
  free door"; this is in-milestone tuning, not something to defer to a later
  pass.
- **Do not let a premium room drop more fuel.** That is a self-funding loop
  and the plan's loot-table guidance is explicit that premium rooms must drop
  *different* loot, not more of the fuel currency.
- **Do not put the reroll or gamble sinks on the fuel currency.** Lapis and
  emeralds are separate currencies by design (M14, M16); mixing them here
  breaks the orthogonality the later milestones assume.

## Verification bar

**Done when** (copied from the plan, verbatim): a broke player always has the
free door, the free door pays out fuel, doors 2/3 refuse entry without enough
fuel, and a level-10 key does not see the door-2/3 offers a level-100 key
does.

**Jar verification:** none new; the plan states the `ConfiguredItem` pattern
and the door-offer plumbing already exist.

**Live-only:** the door-offer dialogs' new fuel-cost and level-gate text, and
the actual refusal behaviour at the door. Record in `docs/LIVE_TEST_PASS.md`.
The fuel-count arithmetic, the tier field on `InstanceRecord`, and the
depletion-exemption logic are headless-verifiable.

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
6. Rename this file `M12-handoff-completed.md` once the milestone is actually
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
