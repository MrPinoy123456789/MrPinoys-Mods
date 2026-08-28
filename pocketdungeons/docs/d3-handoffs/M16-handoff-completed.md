# M16 - Kadala gamble (3.4): emeralds for a random piece in a chosen slot - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M16 - Kadala gamble (3.4): emeralds for a random piece in a chosen slot` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.

## Before you start: confirm the dependency is actually done

**Hard dependency on M13.** Confirm which shape M13 chose (gear folded into
chest tables, or dedicated slot-keyed tables) before starting; it decides
exactly which tables this milestone draws from.

## Goal, in one line

Spend emeralds, pick a slot, get back a random item that fits it, with no
guarantee of quality within the slot. This is the volume-over-certainty sink,
sitting next to M14's guaranteed, targeted reroll: a player who knows what
they want rerolls it, a player who wants more shots at *something* gambles.

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

- Confirm which loot-table shape M13 actually authored (folded into
  `chests/tier_*` or dedicated `gear/<slot>_<tier>` tables) before writing the
  draw logic; this milestone's implementation notes assume the dedicated-table
  shape is cleaner specifically so a gamble draw cannot out-produce opening the
  run's own chests.
- Build the station the same way M14's reroll station was built: a
  `RitualListener`-shaped block interception, a `DialogScreens` picker, a
  `ResourceKey<LootTable>` draw. If M14 has already landed, its station is the
  concrete pattern to copy rather than re-deriving the shape from the plan's
  prose.
- Gate the tier picker on the player's keystone level, per the
  level-gates-access principle M10 and M12 establish; a low-level player
  should see only tier-1 gambles.

## What you must not do

- **Do not let the gamble out-produce the run's own chests.** The plan calls
  this out directly as the reason to prefer dedicated slot-keyed tables over
  drawing from the general chest pool.
- **Do not put this sink on fuel or lapis.** Emeralds only. The plan's
  orthogonality argument spans M12, M14, and M16 together; breaking it here
  breaks the argument for all three.
- **Do not guarantee any quality within the drawn slot.** The lack of a
  guarantee is the entire point of this sink existing alongside M14's
  guaranteed reroll.

## Verification bar

**Done when** (copied from the plan, verbatim): a player right-clicks the
gamble station, picks a slot and tier, pays emeralds, and receives one random
item from that slot's tier pool.

**Jar verification:** none new beyond M13's pool work.

**Live-only:** the station right-click, the slot/tier dialog, and the
delivered item. Record in `docs/LIVE_TEST_PASS.md`. The cost helper, the
tier-unlock gate, and the table-id resolution against the registry are
headless-verifiable.

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
6. Rename this file `M16-handoff-completed.md` once the milestone is actually
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
