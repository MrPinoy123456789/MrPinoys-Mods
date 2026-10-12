# Roadmap build 2026-10-11

Owner approved the Workshop 3 roadmap (`docs/concepts-2026-10-11.md`) on 2026-10-11 and asked for sub-agents to build it.
Design records: `docs/affix-workshop-2026-10-11.md`, `docs/boss-workshop-2026-10-11.md`, `docs/concepts-2026-10-11.md`,
`docs/haunts-draft-2026-10-11.md`. Those win over any older doc.

## Rules of this build

- Integration branch: `feature/act-1-2-roadmap`, in its own worktree. Agents never touch the owner's main tree.
- One task = one agent = one branch `feature/rm-<id>-<topic>` off the integration branch, one report `docs/reports/rm-<id>.md`.
- Standing rules for every agent: `.claude/skills/agent-brief/SKILL.md`.
- Owner constraints that stand: no push, no deploy, no server, no Lemon.
- Decisions with "details open" in the ledgers are the agent's to settle with the numbers in the task, then record the
  numbers and the reasoning in its report. Anything that would change a settled design (leaving rules, scrap curve,
  haul model, Heard meter, the one Warden rule) stops the agent and goes in the report as a question.
- A task that needs a live playtest to judge says so in its report and adds a live check fragment (below).

## Hot files (many tasks touch them): keep edits minimal and local

`PocketDungeonsMod` (register calls), `PocketDungeonsConfig` (knobs), `FinaleWave`, `RoomContent`, `TrialContent`,
`RunLifecycle`, `src/gametest/resources/fabric.mod.json`, `docs/playtests/LIVE_CHECKS.md`, `docs/CODEMAP.md`.

- Do not edit `LIVE_CHECKS.md` or `CODEMAP.md` directly. Write a fragment `docs/playtests/live-checks/rm-<id>.md`; the
  integrator folds fragments in. Regenerate the code map only on the integration branch.
- Finale behaviour goes in one class per dungeon, registered through the finale script registry (task T01), not
  inside `FinaleWave`.

## Tasks

Size: S, M, L. "Needs" lists tasks that must be merged first.

| Id | Task | Size | Needs | Main files |
|---|---|---|---|---|
| T00 | Tooling and skills for faster builds | M | none | `tools/`, `.claude/skills/` |
| T01 | Finale script registry (hooks for phases) | M | none | `FinaleWave`, new `FinaleScripts` |
| T10 | Quick affix fixes: Explosive fuse 25 ticks, Silenced `n/3` action bar, Cooked line on floor clear title, Restless `min_level` 5, Voided skips puzzle and ordeal cells | S | none | `RoomContent` (one constant), `PressureSources`, `LayoutStamper`, `restless.json` |
| T11 | Overclocked pays for speed (360 s par, +1 scrap, shown on the title); Loaded becomes trapped chests (ambush on first open) | M | none | `FloorPay`, `RoomContent` (cache), new `TrappedCache`, `loaded.json`, `overclocked.json` |
| T12 | Restless widened to every mob, theme-matched rise; new Blackout affix | M | none | `Restless`, `RestlessRules`, `AffixEffects`, `AffixMeta`, `DungeonLight`, new `blackout.json` |
| T13 | Data fixes: Deepslate run 5 to 4 floors (drop layer 4, move the blood door), `hiddenOre` for Lush Caves and Copper Works | S | none | `dungeon/deepslate.json`, `rootworks.json`, `copper_works.json` |
| T20 | Mineshaft dark phase finale | S | T01 | new `MineshaftFinale`, `mineshaft.json` |
| T21 | Ossuary coffins finale | S to M | T01 | new `OssuaryFinale`, `last_rest` room |
| T22 | Kennels handler windup and pack empower | S to M | T01 | new `KennelsFinale`, `kennels.json` |
| T23 | Lush Caves engine: potion drip (70 percent harmful), giant slime boss, speed up in the fight | M | T01 | new `PotionDrip`, new `LushFinale`, `rootworks.json` |
| T24 | Infestation engine: creeping bricks, giant silverfish boss with burrow | M | T01 | new `CreepingBricks`, new `InfestationFinale` |
| T25 | Lush Caves rooms: swamp floor, witch havens, bogged, clay shelves with vines, mushrooms, pond dead ends | M to L | T23 | `*Specs.java`, `dungeon_room/*.json`, `rootworks.json` |
| T30 | Cow Pits: Specimen 0 boss and the Milking Parlour floor | M | T01 | `CowPits`, `ResourceBiomeSpecs`, `cow_pits.json` |
| T31 | Deepslate Hush rule in the Silent Deep | S to M | T01, T13 | new `DeepslateFinale`, `silent_deep` room |
| T32 | Copper Works ticking clock, vents and vats | M | T01 | new `CopperClock`, `CopperFinale`, `master_furnace` room |
| T33 | Frostworks freeze ticks and braziers | S | T01 | new `FrostFinale`, `big_freeze` room |
| T34 | Haunt director and the first haunt, The Counted (Ossuary) | M | none | new `Haunts*`, new `dungeon_haunt/*.json` |
| T40 | Endless Mine per act: guardian every 3 floors, tiers A B C, floor 3 act gate | M | none | `ZoneRules`, `ActProgress`, `endless_mine.json` |
| T41 | Act 1 Endless Mine guardians (minis of the Act 1 bosses) | L | T40, T20, T21, T22, T23, T24 | new `MineGuardians` |
| T42 | Act 2 Endless Mine and its guardians | L | T40, T30, T31, T32, T33 | new data, `MineGuardians` |
| T43 | Spawner Dungeon capstone: spawner as phase, brood mother | L | T20, T21, T22, T23, T24 | `CapstoneFights`, `BroodWave` |

Not in this build: Acts 3 to 5, the Ancient City change (none), Molten, Swarming, the Wither's Keep decision.

## Waves

1. T00, T01, T10, T11, T12, T13, T34, T40 in parallel (disjoint files).
2. After T01 merges: T20, T21, T22, T23, T24, T30, T31, T32, T33.
3. After wave 2: T25, T41, T42, T43.
4. Integration: fold live check fragments into `LIVE_CHECKS.md`, regenerate `CODEMAP.md`, one full `tools/ship.sh`.

## Status

Filled in by the integrator as tasks merge.

| Id | Branch | State | Tests after |
|---|---|---|---|
