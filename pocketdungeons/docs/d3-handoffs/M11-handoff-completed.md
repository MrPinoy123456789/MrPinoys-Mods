# M11 - Adventures: the recipe system becomes a descent graph - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific), then its
   `## M11 - Adventures: the recipe system becomes a descent graph` section: **the authoritative scope.** Goal,
   dependencies, scope, touch points, done-when, open questions,
   jar-verification tasks, and detailed implementation notes all live there.
   This handoff does not repeat them.
3. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
4. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M9 actually built,
   for context on what this milestone extends.

## Before you start: confirm the dependency is actually done

None strictly, but this milestone is the door-selection mechanism M12 builds
on. If M10 has already landed, `Keystone.offers` will already reflect its
changes (no `FRAGILE`, `OMINOUS` seeded); this milestone's edits to
`Keystone.offers` build on top of whichever state it is in. Either order
works; the plan lists M11 second only because M12 needs both.

## Goal, in one line

Replace the backward-looking recipe system (`RecipeMatcher`'s ordered tail
match over the last three completed themes) with a forward-looking
`AdventureGraph`: each theme defines its possible next themes, the doors draw
from the current theme's transition set, and a boss terminator room ends the
adventure and resets to an entry-point theme. The hidden-graph folklore
dynamic replaces the hidden-recipe one.

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

- Build `AdventureGraph` and its loader first, in isolation, copying
  `DungeonRecipes.load`'s reloadable-resource shape exactly (the plan's
  implementation notes give the method-by-method mirror). Get it loading and
  validating against `ThemeManifest` before touching anything that depends on
  it.
- Add `currentTheme` to `DungeonLog.Entry` alongside the still-live
  `recentThemes` field (superseded, not deleted) before rewiring
  `Keystone.offers`.
- Rewire `Keystone.offers` to pull from the graph once both of the above
  exist and are verified independently.
- Delete `DungeonRecipes`, `RecipeMatcher`, `DungeonRecipe`, and
  `ThemeHistory` **only after** the graph is producing correct door offers.
  The plan is explicit that the recipe system is what keeps door offers
  working while the graph is half-built; deleting it early leaves nothing
  serving offers.
- The boss room is net-new content, not a reframe; treat it as its own
  sub-task and scope it to one proof encounter, not a full boss roster.

## What you must not do

- **Do not surface the graph in any dialog, chat line, or command output.**
  No "you are here," no node list, no depth counter. `DialogScreens.doorOffer`
  already shows only one door's theme and affix; keep it that way. Surfacing
  the graph is the one change that would collapse the entire word-of-mouth
  argument for building this.
- **Do not delete the recipe system before the graph is verified working.**
  See "Start here" above; this is a sequencing requirement, not a suggestion.
- **Do not build M12's fuel or tier split here.** This milestone changes what
  the doors' *theme* comes from, not what a door *costs* or *how it depletes*.
- **Do not scope a full boss roster.** One proof encounter, per the plan;
  more can follow in a later pass.

## Verification bar

**Done when** (copied from the plan, verbatim): completing a theme offers
doors drawn from that theme's transition set, a boss terminator ends the
adventure and resets to an entry theme, and the recipe system's three files no
longer exist.

**Jar verification:** none new; the plan states the graph is pure JDK and the
loading path mirrors the existing `DungeonRecipes` one.

**Live-only:** the boss room encounter and the door-offer theme selection in
practice; record both in `docs/LIVE_TEST_PASS.md`. The graph's transition
lookup, the loader's validation against `ThemeManifest`, and the
`currentTheme` codec round-trip are headless-verifiable.

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
6. Rename this file `M11-handoff-completed.md` once the milestone is actually
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
