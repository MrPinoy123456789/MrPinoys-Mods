# M13 - Gear loot pool (content, no Java) - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M13 - Gear loot pool (content, no Java)` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.

## Before you start: confirm the dependency is actually done

None. This milestone can run in parallel with M10, M11, and M12, and should
start as early as possible: it is the long pole for every gear-touching sink
(M14, M15, M16, and M17's extraction source), none of which have anything to
operate on until this lands.

## Goal, in one line

Author tiered armour and weapon loot tables so the gear-touching sinks have
something to operate on. This is the one milestone in the whole plan that is
"author new loot content before any code is worth writing," not a jar
verification or a code change.

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

- Confirm the `set_enchantments` and `set_components`/`custom_data` loot
  function shapes against the jar before authoring anything at scale; both
  are named in the plan as things to verify first, since a misspelt id in a
  loot function is silently dropped (trap 6's cousin) rather than erroring.
- Decide the chest-tables-vs-slot-keyed-tables question early, since it
  changes what you author: the plan recommends dedicated slot-keyed
  `gear/<slot>_<tier>.json` tables so M16's gamble cannot out-produce a run's
  own chests, with the entries also folded into the `chests/tier_*` tables so
  runs drop gear directly. Vanilla loot tables have no inheritance, so this
  means authoring the entries twice, not referencing them once.
- Tag every piece with the `pocketdungeons.tier` custom_data marker as you
  author it. M14 and M16 both key their cost curves off this tag; retrofitting
  it after the fact means re-touching every table you already wrote.
- Register every new table path constant in `LootTables` and its `ALL`
  startup check as you go, not at the end, so a missing table is a boot-time
  error rather than a silently empty chest.

## What you must not do

- **Do not write Java.** The plan is explicit: this milestone is JSON
  authoring, with the only Java-adjacent touch being the `LootTables`
  constant registration.
- **Do not assume an enchanting table exists outside the dungeon.** Per
  `VISION.md` section 3.7, nothing required for progression may live outside
  the dungeon loop; enchantments must be pre-rolled on the gear, not assumed
  addable later.
- **Do not skip the tier tag** on any entry. Downstream milestones scale cost
  off it and have no fallback if it is missing.
- **Do not just check that a chestplate appeared.** This is the carried
  lesson in `DISCOVERIES.md`: prior bugs survived passes that confirmed an
  item type but never inspected its components. Inspect a drawn stack's
  `DataComponents.ENCHANTMENTS` and `CUSTOM_DATA` directly.

## Verification bar

**Done when** (copied from the plan, verbatim): a tier-3 run can drop a
diamond chestplate with enchantments, a tier-1 run drops iron-grade gear, and
the gamble (M16) and reroll (M14) have tables to read from.

**Jar verification:** the `set_enchantments` function's `enchantments` map and
`levels` provider form, and the `set_components`/`custom_data` loot function
shape, both before authoring at scale.

**Live-only:** none strictly, though a live loot-drop pass is worth doing
before calling this done, since a headless run cannot open a chest by hand.
The component inspection (enchantments, custom_data) can be done via a
temporary debug command run headlessly against a generated stack; note in
`docs/LIVE_TEST_PASS.md` if you instead verify it by hand.

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
6. Rename this file `M13-handoff-completed.md` once the milestone is actually
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
