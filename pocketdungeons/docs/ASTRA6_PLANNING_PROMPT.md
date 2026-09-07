# Handoff prompt: plan Pocket Dungeons to "one of the best mods on Minecraft"

> Copy everything below this line into a fresh Astra 6 chat. Do not paste this
> header. The agent's job is to produce a plan, not to write code.

---

You are taking over planning for **Pocket Dungeons**, a server-side-only Fabric
mod for Minecraft 26.2. The workspace is at `A:\MrPinoys Mods`; the mod repo is
`A:\MrPinoys Mods\pocketdungeons`. Your single deliverable is a plan that takes
this mod from "works and is code-complete through M61" to "one of the best mods
on Minecraft." You are not implementing anything in this pass. No source edits,
no commits, no `gradlew` runs. Read, think, and write the plan.

## What this mod is, in one paragraph

A run-based Minecraft dungeon crawler and game mode. Each player owns a
persistent, decoratable room in a void dimension. Three doors on the far wall
are runs at chosen difficulty; you spend a keystone, walk forward through 5 to 8
procedural rooms, never double back, and the last door opens into your own room
which has physically moved to the end of the corridor. Progression is a keystone
level (1 to 25) with slang-named affixes, not a gear tier. Everything you own
came out of a run. The mod is `"environment": "server"` with zero client assets,
so it composes with any client mod without compatibility work. The long-term
product is a dungeon format other servers adopt, not "a dungeon mod with
configurable JSON."

## Creative mandate

You have full creative control over this mod's rules, naming, conventions, and
design direction. The items below are the defaults the mod has shipped under
through M61. Treat them as strong priors, not walls. If your plan changes any of
them, say so explicitly in a "Departures from existing conventions" section at
the top of the plan, with the reasoning. The mod owner will review before
anything is built. What you cannot do is change them silently: every departure
must be visible and argued.

### Architectural defaults (challenge with reasoning)

These shape the mod's identity. Changing one is a big move and the plan should
treat it as such.

1. **Server-side only.** `"environment": "server"` in `fabric.mod.json`, no
   `assets/` directory, no custom blocks, no custom items, no custom sounds, no
   custom registry entries. Everything is vanilla blocks, vanilla items, vanilla
   sound events, and server-side dialogs. This is what makes the platform thesis
   (VISION 6) work: the mod composes with any client mod without compatibility
   work. If your plan needs a client-side component, argue why the platform
   thesis is worth trading for it.
2. **Self-sufficiency is a constraint, not a mode.** Nothing required for
   progression may live outside the dungeon loop. Every planned feature is
   checked against "could a player progress without ever leaving?" If your plan
   opens an overworld dependency, argue why.
3. **The mod stays quiet about the trick.** The room-moving-on-completion
   mechanic is never explained in-game. No message, no sound, no loading screen.
   If your plan breaks this silence, argue why the moment is better served by
   explanation. This is the most memorable thing in the design and the cheapest
   to accidentally rationalize away.
4. **Verify against the 26.2 jar, not memory.** Anything unverified gets a
   `UNVERIFIED` comment in source. The plan should flag where 26.2 API surface
   must be re-confirmed before a milestone starts. This is not a design choice;
   it is a correctness rule and stays in force regardless of other departures.

### Mixin policy

There is **no hard cap** on mixin classes. The mod currently has one
(`mixin/CustomClickMixin`). Mixins are powerful but fragile: they break on
Minecraft updates, they conflict with other mods, and they are harder to reason
about than Fabric events or datapack routes. Your plan should:

- Prefer Fabric events, datapack recipes, and vanilla mechanics over mixins
  wherever they can do the job. `DISCOVERIES.md` traps 14 and 15 are a worked
  example of that search paying off.
- Justify each new mixin in the plan: what it does, why no event or datapack
  route covers it, and what it will cost on a Minecraft version bump.
- Keep the total mixin surface small and focused. Five mixins with clear
  purposes is better than one mixin doing five unrelated things.
- If a milestone adds a mixin, the plan names the target class, the method, and
  the injection point.

### Style and naming (yours to change)

These are conventions, not architecture. You may rename, restructure, or replace
them. If you do, the plan says what changes and why.

- **Slang naming.** Affixes and system messages use slang (`Unhinged Feral
  Keystone [24]`, `Cooked`, `Big L`), not clinical labels. You may redefine the
  voice.
- **Codec migration discipline.** Superseded `DungeonLog.Entry` fields are
  marked superseded in javadoc and kept, not deleted on the first pass. You may
  change this if your plan includes a migration strategy for existing saves.
- **House punctuation.** No em dashes or double hyphens as punctuation in any
  text you write. Use colons, semicolons, commas, parentheses, or two sentences.
  Command-line flags and code operators (`i--`, `--offline`) are exempt. This is
  the mod owner's personal preference and stays in force for text you write in
  the plan itself; whether it applies to future in-game strings is your call.

## Authoritative reading order

Read these in this order. Each line says what to extract from the file. Paths
are relative to `A:\MrPinoys Mods\pocketdungeons`.

1. `A:\MrPinoys Mods\CLAUDE.md` - the workspace-wide punctuation and style rule.
2. `CONVENTIONS.md` - mod-specific conventions: the current mixin surface,
   status doc locations, codec migration discipline, the server-side-only
   constraint. These are defaults your plan may depart from with reasoning.
3. `docs/reference/VISION.md` - **the most important file.** The hook, the four
   rules that define the format, the five pillars (room, keystone, themes,
   self-sufficiency, the trick), the platform thesis in section 6, and the
   non-goals in section 9. Your plan must serve this document.
4. `docs/reference/ROADMAP.md` - milestone order M0 through M8 (deferred), plus
   M18 through M44 and the M45-M61 situations round. Section "M8 - Deferred" is
   the list of deliberately-held features; your plan should decide which of these
   to unhold and in what order.
5. `docs/reference/SITUATIONS_SPEC.md` - the spec the last round built. Sections
   3 (bag), 4 (situation catalogue), 5 (pressure/omen), 6 (generator changes),
   7 (Herobrine Cube as run composer), 12 (the floor loop), 13 (stories). Section
   9 lists open questions; some are now resolved by M60/M61, some are not.
6. `docs/reference/SITUATIONS_PLAN.md` - how the last round was structured
   (waves, file ownership matrix, integration gates). This is the template for
   how a parallel round should be planned.
7. `plans/COMPLETED-MILESTONES.md` - architectural summary of every completed
   milestone. Read the last third (M44 onward) carefully; skim the rest. This is
   the ground truth of what is actually built.
8. `docs/reference/MYTHIC_PLUS_RECONCILIATION.md` - the affix system's design
   reasoning and the ladder philosophy.
9. `docs/reference/LORE.md`, `LORE-STORY.md`, `LORE-DIARIES.md` - the fiction
   and where it couples to mechanics. Lore is a second register, never the point.
10. `docs/INTEGRATION.md` - the published datapack schema (`dungeon_room`,
    `dungeon_theme`, `dungeon_adventure`, `anomaly_room`, `diary`) that pack
    authors write against. The platform layer's public surface.
11. `docs/DIALOGS_SPEC.md` - menu shapes, specced before they are built.
12. `docs/DISCOVERIES.md` - verified 26.2 API findings and numbered traps. The
    plan should respect every trap and add new ones only with a worked example.
13. `docs/reference/LIVE_TEST_PASS.md` - the outstanding live client
    verification pass. The plan must account for closing this.
14. `plans/HANDOFF-SPEC.md` - the format every milestone handoff must follow.
    Your plan's per-milestone sections should conform to this.

If a file referenced above does not exist or is empty, note it and move on; do
not block on it.

## Current state, as of the M61 commit

- **Built and code-complete:** M0 through M7, M9 through M18, the M18-M22 room
  UX pass, M23-M31, the M32-M34 tutorial and bounty screens, M35 anomaly rooms,
  the M36-M44 audit follow-up (40 bug fixes plus structural cleanup and tests),
  and the entire M45-M61 situations/bags round.
- **M45-M61 specifically delivered:** the bag as player class (8 archetypes,
  chosen once via a chest in the safe room, locked to keystone until full
  reset), the stash-and-swap failsafe inventory model, the root-distance
  solvability pass, omen replacing the global run clock, bag loot tables and
  in-run scarcity, the situation catalogue (traversal, mechanism, knowledge,
  pressure families), the Herobrine Cube as run composer with nine cube
  recipes, the floor loop (safe room / staging room split, deterministic door
  previews, multi-floor chaining), and vertical room span (`spanY`, spec 13)
  with Slime Pit as the pilot two-story room.
- **Just fixed (uncommitted, in working tree):** three rooms whose vanilla
  redstone mechanisms did not work in a void dimension were replaced with
  code-driven handlers (`CollapsingBridgeHandler`, `RisingLavaHandler`) and a
  redesigned open Gallery. See `docs/ROOM_FIXES.md`.
- **Outstanding:** the live client verification pass in
  `LIVE_TEST_PASS.md` is not closed. Several M44 test gaps (anything needing a
  real `ServerLevel` or bound item components) have no coverage of any kind.
  Open questions 4, 5, 7 and 9 from `SITUATIONS_SPEC.md` section 9 remain open
  pending playtest data.

## What "one of the best mods on Minecraft" means for this mod

That bar is not "more features." It is: a server format people adopt by name,
with enough content depth and folklore dynamics that a population stays, and a
platform layer clean enough that pack authors ship dungeons around their own
mods. Use `VISION.md` as the arbiter. Specifically, the plan should address:

- **The platform thesis (VISION 6).** Data-driven affixes (today a Java enum),
  data-driven room roles (today a Java switch), the published schema, and
  pack-author tooling. The plan should sequence the moment a pack author can
  add an affix, a theme, and a recipe without compiling.
- **Content depth.** Themes beyond the proof set, more rooms in the catalogue,
  more affixes (the slang-named ladder), more cube recipes, more situations.
  The cost bomb in VISION 5.2 (140 hand-authored `.nbt` files) is solved by
  processors; the plan should exploit that.
- **Folklore dynamics (VISION 5.4).** Recipe discovery with a floor, the
  calling card traded by hand, weekly bounties, the visitability layer. Density
  is not something the mod can produce alone, but the mechanisms that make
  folklore possible are.
- **The deferred list (ROADMAP M8).** Decide, with reasoning, which to unhold:
  outdoor themes (needs a second dimension), one rule-breaking dungeon (the
  Endless Mine is cheapest), data-driven affixes, lava as a second faucet,
  multi-cell footprints, room size as progression, the elevator (public room
  directory, reconcile with the calling card's no-browse rule first).
- **Polish and correctness.** Closing the live verification pass, real test
  infrastructure for `ServerLevel`-bound code, performance under many concurrent
  instances, and the UX/sound passes that make the loop feel good.
- **The non-goals (VISION 9).** These are the mod's current identity boundaries:
  no in-run buildcraft, no drafted abilities, no synergy engine, no PvP, no
  monetisation. The strongest idea they protect is players physically
  constructing a record of their runs. Your plan may challenge a non-goal if the
  argument is strong, but the plan must say so explicitly and explain what is
  gained versus what the mod's identity loses.

## The deliverable

Produce a single planning document. Save it at
`plans/NEXT_ROADMAP.md` in the mod repo. Structure it as:

1. **Departures from existing conventions.** A short section at the top listing
   every default from the "Creative mandate" section above that your plan
   changes, with the reasoning. If you change none, say so explicitly. This is
   the first thing the mod owner reads.
2. **What "best" means for Pocket Dungeons.** A short section, grounded in
   VISION, that defines the bar in this mod's own terms. No generic "polish and
   QoL" language.
3. **Gap analysis.** What the mod has today versus the bar, organized by pillar
   (room, keystone/affixes, themes/recipes, situations/loop, platform,
   folklore, correctness/test). Each gap names the file or system it lives in.
4. **Milestone sequence.** The next round(s) of milestones, numbered continuing
   from M61. For each milestone: a one-line goal, the dependency it sits on, the
   files it owns, and a one-paragraph scope. The first round should be detailed
   enough to write handoffs from; later rounds can be sketched. Follow the
   wave/ownership-matrix discipline from `SITUATIONS_PLAN.md` if a round is
   parallelizable.
5. **What stays deferred, and why.** Explicitly call out which M8 items remain
   held and the condition that would unblock each.
6. **Risk register.** The 5 to 8 risks most likely to derail the plan, what each
   one costs, and the mitigation. Call out anywhere the 26.2 API surface is
   unverified and must be re-confirmed before a milestone starts.
7. **Open questions for the mod owner.** The decisions only the owner can make,
   each stated as a question with the trade-off laid out. Do not invent answers.

Conform each per-milestone section to `plans/HANDOFF-SPEC.md`: 2 to 4 reading
items naming specific file sections, grep-based dependency checks, milestone-
specific constraints, exact file/class/method references, the gradle task for
verification, and the completion steps. Keep the whole document under 600 lines.

## What not to do

- Do not edit, create, or delete any source file.
- Do not run `gradlew`, `git`, or any build command.
- Do not rewrite or reorganize existing docs. The only file you create is
  `plans/NEXT_ROADMAP.md`.
- Do not silently break the architectural defaults. If a feature requires a
  client asset, an overworld dependency, a new mixin, or breaking the trick's
  silence, the plan must say so in "Departures from existing conventions" with
  the reasoning. Silent departures will be rejected on review.
- Do not restate the vision. Reference it by section and build on it.
- Do not pad the plan with generic software-engineering advice. Every item
  should name a file, a system, or a spec section in this repo.

Begin by reading the files in the order given. Then write
`plans/NEXT_ROADMAP.md`. Stop when the document is complete and tell the mod
owner it is ready for review.
