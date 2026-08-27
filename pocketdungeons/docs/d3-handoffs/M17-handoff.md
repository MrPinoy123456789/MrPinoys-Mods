# M17 - The Herobrine Cube (3.5): extract a power, imbue it anywhere - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M17 - The Herobrine Cube (3.5): extract a power, imbue it anywhere` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.
5. This milestone has a **blocking** jar-verification task around the
   craft-result interception point, and it is the one place in the whole plan
   where a second mixin is plausible. Read "The one-mixin budget" in the
   standing rules below and traps 14/15 in `DISCOVERIES.md` before assuming a
   mixin is necessary.

## Before you start: confirm the dependency is actually done

**Hard dependency on M15 and M11.** Confirm both have landed in
`plans/COMPLETED-MILESTONES.md` before starting:

- M15's equip-time attribute-modifier plumbing must exist and be generalised
  (read a worn-piece signal, apply a configured modifier, as two separate
  steps), since this milestone reuses it rather than building its own
  equip-time listener from scratch.
- M11's adventure graph must exist with rare nodes authored, since this
  milestone's extractable-item source is a strict subset of those nodes.

This is the last milestone in the plan's dependency graph; nothing else waits
on it.

## Goal, in one line

A crafting-table ritual that consumes a rare item once, permanently
remembers one fixed power it carried, and re-applies that power to any future
item cheaply. Two rituals: extract (consume the rare item, add its power to
the player's permanent set) and imbue (apply a previously extracted power to
an ordinary item for a small material cost). Capped at equip time so "which
powers do I run" stays a real choice, not a checklist.

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

- **Do the blocking jar work first.** Exhaust the datapack-recipe route
  before considering a mixin: a datapack recipe can consume the rare item and
  produce a marked "extracted power token" item with no Java (trap 14), and a
  recipe with no unlock advancement is craftable but invisible (trap 15). The
  open question is whether a Java listener is still needed to write the
  actual player-state change on craft-pickup; verify
  `CraftingMenu.slotChangedCraftingGrid` against the jar as the candidate
  hook, and confirm the injection fires only when vanilla produced no result,
  so no real recipe is ever shadowed.
- Reconcile the rare-item source list with M11's rare-node rewards and M16's
  gear pool before authoring anything, per the plan's explicit call to make
  this one list, not three.
- Generalise M15's equip-time listener by swapping its signal source, not by
  writing a new listener from scratch; the plan is explicit that this is
  reused plumbing, not a new pattern.
- Settle the equip cap and the reversibility flag in-milestone; both are
  named as real, deliberate calls, not defaults to skip past.

## What you must not do

- **Do not build a second mixin before exhausting the datapack-recipe and
  event route.** This is the one milestone where a second mixin is plausible,
  which makes it the one milestone where the discipline matters most. If a
  mixin is truly required, say so in your report and justify it against the
  one-mixin budget the way `CustomClickMixin`'s own javadoc justifies itself.
- **Do not let extraction sources include ordinary tier loot, gamble output,
  or reroll output.** A fixed, build-defining power is a different kind of
  reward than a stat roll; the plan is explicit that treating it the same
  flattens the whole package instead of capping it.
- **Do not ship without an equip cap.** Without one, the Cube stops being a
  build decision and becomes a checklist: extract everything, wear
  everything, done.
- **Do not silently default reversibility.** Irreversible extraction is D3's
  shape and the plan's default, but it is a real cost on a server where a
  mis-click destroys a genuinely rare drop forever; make the call visibly,
  not by omission.

## Verification bar

**Done when** (copied from the plan, verbatim): a player places a rare
extractable item in a crafting grid, the item is consumed and its power joins
their permanent set, and they can later imbue that power onto an ordinary
item at a crafting table for a material cost, with the equipped-power count
capped.

**Jar verification (blocking):** the craft-result interception point.
Confirm the Fabric API or mixin surface that lets a server-side mod substitute
a craft result before it reaches the player, without a client mod. This is
one of only two jar-verification tasks in the whole plan that can change a
milestone's shape rather than just its tuning (M15 has the other).

**Live-only:** the crafting-grid ritual, the imbued item in actual combat, and
the equip-cap enforcement. Record in `docs/LIVE_TEST_PASS.md`. The
extracted-power persistence (write and read back from `DungeonLog`), the
equip-cap count, and the power-id reconciliation against the rare-node list
are headless-verifiable.

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
6. Rename this file `M17-handoff-completed.md` once the milestone is actually
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
