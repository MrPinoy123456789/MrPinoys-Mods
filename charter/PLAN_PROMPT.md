# Prompt: implementation plan for Charter

Copy everything below the line into a fresh session (Claude Code, opened at
`A:\MrPinoys Mods`, on branch `feature/charter-design`).

---

You are the lead engineer and game designer for **Charter**, a new server-side
Minecraft mod in the MrPinoy's Mods suite. The design is finished and agreed. Your
job is to write the **implementation plan** that gets the best possible version of
it into a playtest. **Write the plan only. Do not write mod code.** The owner will
read the plan, settle the open questions, and approve it before anything is built.

## What Charter is

An alternative to "Kingdom" servers. Organizations hold power and their
governance decides who may use it. Land is held through keeps (rooted, 3x3,
expensive) and camps (mobile, cheap), and taken by **supply, not by breaking
blocks**: a besieged keep eats its granary, food gets in only when carried through
an open side (on foot or by minecart), an encircled keep falls after 3 to 4 days,
and raids end when their camp is cut off. All power over others (treaties,
vassalage, occupation, jail, execution, bondage) is one system: signed and imposed
contracts with hard caps. Everything is written into an in-world Chronicle.

## Read first, in this order

1. `CLAUDE.md` at the workspace root. **House rule: never write an em dash or a
   double hyphen as punctuation**, in the plan, code comments, commit messages or
   player text. Check your output with the grep it gives.
2. `charter/DESIGN.md`: the whole design. §23 lists what is settled, what was
   rejected (do not reintroduce it) and what is still open. §22 is the acceptance
   test list. §24 is the tuning knobs.
3. `docs/DESIGN.md`: the suite rules. §2 and §4 are binding: pay and charge only in
   plain cobblestone and diamond items; never depend on another mod at compile
   time; server-side only, vanilla clients install nothing; config under
   `config/<mod_id>/` via read-or-create; no Mixins without a real need.
4. `ballot/` as the **reference project** for build setup: the `core/` and
   `fabric/` split (core has only the JDK on its classpath, so a Minecraft import
   fails compilation), `settings.gradle.kts`, `gradle.properties` (Minecraft 26.2,
   Loader 0.19.3, Fabric API 0.156.0+26.2, JDK 25, unobfuscated so no mappings),
   `fabric/build.gradle.kts` (Loom, `include` of core, sgui if server-side menus are
   ever needed), and the `main()` style core tests in its README. The suite design
   doc mentions a `MOD_AUDIT.md` for build traps; it is not in this checkout, so
   take conventions from `ballot` and note anything you had to guess.
5. `pocketdungeons/` for patterns worth reusing, not code to depend on: Fabric
   gametests registered in `src/gametest/resources/fabric.mod.json` (a gametest
   class not listed there never runs), display-entity wall boards, the Lemon
   playtest agent (`pocketdungeons/docs/LEMON_AGENT.md`) and the playtest
   reports in `pocketdungeons/docs/playtests/`. Read enough to judge what transfers.
6. Optional prior art: KingdomsX wiki pages in `A:\tmp\kingdomsx-ref\wikipages`
   (closed-source Spigot plugin; read `Mechanics.md` and `Peace-Treaties.md` only
   for comparison).

## What "the best possible version to be playtested" means

- **The core loop must be real, not stubbed.** A first playtest must be able to
  answer: is supply-line siege fun? Do the 3 to 4 day timings feel right? Do
  carry-in and encirclement make sense to players? Can a passive faction live
  well? So organizations, keeps, camps, protection, the granary, the full siege
  (raising, war footing, carry-in by player and cart, encirclement, presence,
  starvation and assault, the fall), core loss and the Chronicle are in.
- **Recommend the cut.** Go through DESIGN.md §21 (v0, v1, later) and say what goes
  in the first playtest build, what waits, and why. Lean toward whatever makes the
  first playtest teach the most. Justice and imposed contracts are a strong
  candidate to include because the owner cares about them.
- **Sieges take days, playtests take hours.** Plan a **time scale** knob (one
  setting that compresses every siege, spoilage, raising and grace timer together)
  so a 3.5 day siege can be played in an evening, plus operator tools to set up
  scenarios fast: spawn a faction with a stocked keep, raise a camp instantly, set
  a granary's contents, advance siege time, show supply state and perimeter.
- **Playtests must leave data.** Plan structured logging of every siege event
  (raised, besieged, encircled, deposits by player and cart, starving, fell,
  lifted) so a debrief can reconstruct what happened. The Chronicle is the player
  view; the log is the debrief view.
- **Audience:** 10 to 20 players at first, 100 or more later. Note anything that
  will not scale (per-tick chunk-crossing checks for every player and cart, for
  example) and how you would make it scale.

## What the plan must contain

Write it to `charter/PLAN.md`.

1. **The first playtest build:** a one-paragraph statement of what players will
   be able to do, then the in and out list from §21 with reasons.
2. **Architecture.**
   - The `core` model: packages and the main types (Organization, membership,
     governance, offices, grants, votes, contracts and terms, land graph, cores,
     supply and siege state, food and spoilage, Chronicle templates, the action
     engine).
   - What stays in `core` (pure JDK, unit tested) and what must live in `fabric`.
3. **The action engine:** how every consequential change flows through it,
   authority-chain validation at execution time, and how admin actions are
   recorded.
4. **Simulation and ticking:** how siege drain, spoilage, raising, grace timers,
   presence counts and the time scale run. That means tick rate, batching, what
   happens across a restart, and what happens to chunks that are not loaded.
5. **Physical detection in `fabric`:**
   - detecting player and minecart crossings into keep chunks from open
     perimeter chunks, including the carry-in snapshot
   - ender chest, pearl and teleport exclusions
   - hopper inputs into a besieged granary
   - presence counting
   - protection: Fabric API events for break, place, use, attack-entity and
     use-entity; and the Mixins needed for explosions, pistons, fire and fluids.
     List every Mixin and why no event covers it.
6. **The granary and camp store** as real containers: eating items least
   nourishing first, spoilage by type, the storage limit with fast spoilage
   beyond it, mouths per keep, food values from a data file.
7. **In-world objects and player text:**
   - how the nexus, granary, vault, charter lectern, war banner, camp store,
     contract board and Realm Chronicle are made from vanilla blocks and display
     entities, so vanilla clients see them
   - the smoke column, border particles, alerts and offline messages
   - the handful of commands and the clickable chat pages
   - the owner prefers in-world objects over menus
8. **Persistence:** world saved data plus an append-only history file (the design's
   recommendation), what is saved, how sieges and allowances survive a restart, and
   repair on load. Say if you think SQLite is needed and why.
9. **Config:** every knob in DESIGN.md §24 as a field in `config/charter/charter.json`
   with its default, plus the time scale.
10. **Testing:**
    - map every acceptance test in DESIGN.md §22 (A to W, plus K2 to K4) to a
      test type: a pure-JDK `core` test, or a Fabric gametest for anything
      needing a world (protection, carts, hoppers, chunk crossings)
    - say which tests come first
    - tests must prove behaviour, not just compile
11. **Milestones.** An ordered list where **every milestone ends in something
    runnable and checkable**, with its tests. Mark which milestones could be built
    in parallel by separate agents (separate files, no shared hot files) and which
    must be serial.
12. **Playtest plan:**
    - how the build reaches a server: the suite's `dist/` folder; the Kinetic
      test server is 714a0605, and **never 365f9682**
    - the operator scenario scripts to run
    - what the first session should try: a raid, a partial siege with runners,
      a full encirclement, a sally that breaks a camp, a jail sentence
    - the questions the debrief should answer
13. **Risks and unknowns,** each with how you would find out cheaply: performance
    of crossing checks, minecart edge cases, chunk loading during sieges,
    vanilla client limits on displays, exploits (alt accounts, ender chests,
    shulkers, logging out inside a besieged keep).
14. **Open decisions** the owner must settle before or during the build. Include
    at least:
    - D1 to D10 in DESIGN.md §23
    - **war score versus declared war goals with war exhaustion**, Stellaris
      style. Discussed and not yet decided; give your recommendation.
    - **labour terms for bondage and signed indenture.** Discussed and not yet in
      DESIGN.md: an imposed bond on the custody basis, capped like jail, and a
      signed indenture that can run longer.

    For each one, give a recommendation and say which milestone it blocks.

## Rules while you work

- Plan only. Do not create the mod's source tree or build files.
- You may read anything in the workspace. Change only `charter/PLAN.md`.
- If you find a contradiction or a gap in DESIGN.md, list it in the plan under a
  heading of its own rather than silently resolving it.
- Commit the plan on `feature/charter-design` as `MrPinoy123456789
  <334378049+MrPinoy123456789@users.noreply.github.com>`, passing the identity
  per command (`git -c user.name=... -c user.email=... commit`). Never edit the
  global git config. Do not push.
- End with a short summary: the playtest build in two sentences, the milestone
  count, the three biggest risks, and the decisions you need from the owner.
