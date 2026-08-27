# M14 - Gear reroll station (3.2): a lapis sink, orthogonal to fuel - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M14 - Gear reroll station (3.2): a lapis sink, orthogonal to fuel` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.

## Before you start: confirm the dependency is actually done

**Hard dependency on M13.** Check `plans/COMPLETED-MILESTONES.md` for an M13
entry, and confirm the `pocketdungeons.tier` custom_data tag actually exists
on gear drops before starting; this milestone's cost curve reads that tag
directly and has nothing to scale against without it.

## Goal, in one line

A room station that rerolls one enchantment on a piece of gear (the player's
choice of which), costing lapis that scales with the item's tier. This is the
gear-scale sink, answering "why is the gear worth using," where M12's fuel is
the ladder-scale sink answering "why is level 100 worth reaching."

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

- Study `RitualListener` before writing the station. The plan is explicit
  that the both-hands gate, the shift-to-place escape hatch, and the
  `RoomProtection` denial order all have to be preserved by whatever
  intercepts the reroll block's right-click, whether that is a branch inside
  `RitualListener.onUseBlock` or a second `UseBlockCallback` registration.
- Verify the valid-enchantments-per-item data structure and the
  `DataComponents.ENCHANTMENTS` accessor shape against the jar before writing
  the reroll logic itself; the plan flags both as needing verification.
- Build the "never strictly worse" property as a pure-Java test alongside the
  reroll logic, not after. It is a correctness guarantee the plan names
  explicitly, and it is cheap to assert once the enchantment-set types exist.
- Settle the gating question (available immediately vs. level-gated) during
  implementation; the plan leaves it open but flags the fuel-gated-door
  precedent as an argument for some gate.

## What you must not do

- **Do not build a full reroll.** One enchantment at a time, chosen by the
  player, with every other enchantment on the item untouched. The plan
  explicitly considered and rejected a full reroll.
- **Do not let the reroll produce a strictly worse item.** This is a tested
  property, not a suggestion; a reroll that can downgrade the item is a
  design regression the plan calls out by name.
- **Do not intercept a vanilla smithing table's ordinary behaviour.** The
  branch fires only on the positive test (the held item carries the
  `pocketdungeons.tier` tag), the same way `Keystone.isKeystone` gates on a
  positive test rather than excluding everything else.
- **Do not put this sink on the fuel or emerald currency.** Lapis only, per
  the orthogonality argument the plan makes across M12, M14, and M16.

## Verification bar

**Done when** (copied from the plan, verbatim): a player right-clicks the
station with a piece of gear, picks an enchantment, pays lapis, and gets back
the same item with that one enchantment replaced and the rest untouched.

**Jar verification:** the enchantment pool lookup for an item type (how
vanilla's own table avoids offering a duplicate), and reading/writing
`DataComponents.ENCHANTMENTS`.

**Live-only:** the station right-click, the dialog, and the returned stack.
Record in `docs/LIVE_TEST_PASS.md`. The cost helper, the "never strictly
worse" assertion, and the lapis-count arithmetic are headless-verifiable.

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
6. Rename this file `M14-handoff-completed.md` once the milestone is actually
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
