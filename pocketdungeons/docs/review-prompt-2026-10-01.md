# Review prompt: Pocket Dungeons, last three days of work

Paste everything below the line into a fresh, high-capability agent session opened at
`A:\MrPinoys Mods` (the workspace root; the mod is `pocketdungeons/`).

---

You are the independent reviewer for a Minecraft 26.2 Fabric mod, Pocket Dungeons
(`pocketdungeons/`, Java, server-side only). Over roughly 2026-09-28 to 2026-10-01 several
agent sessions changed a lot of code, content and docs, and a large batch of that work is
still uncommitted on branch `wip-lemon-harness`. You have not seen any of it. Your job is to
check it, find what is wrong or unfinished, and produce a prioritised report. Do not trust
the prior summaries; verify against the code and the tests.

## Ground rules

- Read `CLAUDE.md` at the workspace root first. House rule: never write an em dash or a
  spaced double hyphen as punctuation in anything you write (reports, comments, strings,
  commit text). Do not mass rewrite existing comments to comply.
- Read `pocketdungeons/CONVENTIONS.md`, `pocketdungeons/docs/reference/BUGS.md` (PD-78 to
  PD-104), `docs/playtests/LIVE_CHECKS.md`, `docs/handoff-2026-10-01-1.md`, and the playtest
  notes `docs/playtests/2026-10-01-1.md` and `2026-10-01-2.md`.
- Do not edit source files in this pass. Write findings to
  `pocketdungeons/docs/review-2026-10-01-findings.md`. If you must run things, use the
  commands under "Verifying" below. Do not commit, push or rerun
  `tools/gen_themed_content.py` (it is stale against the shipped loot tables).
- Another agent has long-running state: `tools/server/lemonwatch.mjs` and the live dev
  server may be in use. Do not start a second server on the same ports.

## Scope: what to review

1. **Everything since 2026-09-28.** Use `git log --since=2026-09-28 --stat`, plus
   `git status` and `git diff` for the uncommitted work. Ignore binary and log churn under
   `run*/`, `logs/`, `dist/`, `build/`.
2. **The 2026-10-01 fix batch** (all uncommitted, all unverified in live play):
   - PD-98: `TrialContent.cellBlockEntities` now scans down through a two-story room (the
     `blaze_cellar` spawner at y -8 was never found). `kennel_crossing` pen floor is now grass
     and its two trial spawner configs use `spawn_range` 1, because a trial spawner runs the
     mob's own placement rules and wolves need `WOLVES_SPAWNABLE_ON` ground.
   - PD-99: `rotation_lock` comparator and door repeater faced the wrong way (vanilla FACING
     is the input side). Fixed in `MechanismSpecs`, both templates recaptured.
   - PD-100: `OmenBar.repaintHeldLine`, `OmenBarText.holdTicks` (sensor line held 8 s).
   - PD-101: `SalvageStation.onUse` no longer moves the held stack.
   - PD-102: cause was PD-97 (sump return path), already fixed; check that claim.
   - PD-103: `BlacksmithNPC.findAllBlacksmiths` scan widened to 24 blocks horizontally.
   - New gametests in `HandlerGameTest` and `SalvageGameTest`; unit tests in
     `OmenBarTextTest`, `OmenMathTest`, `KeystoneMathTest`, new `StationTutorialTest`.
3. **Loot and progression redesign (also uncommitted):**
   - Loot tiers are now 1 to 4: leather/stone (copper sprinkled in), iron, diamond,
     netherite. `KeystoneMath.lootTier`: 1-4, 5-9, 10-19, 20+. `LootTables` clamps to
     `MAX_TIER` 4. Tier 4 loot tables were created by copying tier 3 and rewriting; gear,
     chest and vault tables were then rewritten by script (`/tmp/regear.py` style transform,
     not kept in the repo). Check the resulting JSON for duplicates, dead weights, wrong
     `custom_data.pocketdungeons.tier` values, themed tables that still nest the wrong base
     table, and anything that still names the old three-tier shape. `TrialContent` still
     clamps spawner tier at 3 on purpose; confirm nothing else breaks at tier 4
     (`FeralContent`, `GambleStation`, `RerollMath`, `SalvageMath`, `RunRecipePlan`,
     room `tier` gates, `DungeonScreen` text).
   - Random armour trims (`random_chance` set_components functions on armour entries) and a
     separate trim-template pool in the base and ominous chest and vault tables. Verify the
     `minecraft:trim` component shape is valid for 26.2 and actually applies when rolled
     (the startup check only proves the table loads). Note the Salvage Bench refuses trimmed
     gear; judge whether that is acceptable.
   - Ominous floors: door 2 no longer carries the OMINOUS affix (`Keystone.offers`). A floor
     now rolls ominous at commit in `Instances.commitDoor`, with chance from
     `Omen.ominousChance(bankedOmenSum, floorsBanked)`. Check: preview versus commit
     consistency, the first floor of an interval (never ominous by roll), multiplayer,
     `AffixMath`/recipe interplay, the door screen text, journaling, and tests. There is no
     gametest of the roll itself.
   - Lemon station tutorial: `StationTutorial` (Salvage, Reroll, Gamble, Cube in rank order,
     one nag per arrival home, completion on first use, stored in the `DungeonLog` task
     sidecar). Hooks are in `RunLifecycle` (two HOME arrivals), `SalvageStation`,
     `RerollStation`, `GambleStation`, `CubeStation`. Check every HOME arrival path is
     covered (including `Instances` around line 1591 and join/reconnect), that `Lemon.say`
     deferral and quiet modes behave, that it does not fight the existing `TaskTracker`
     guided tasks, and that the hint text matches how the stations really work.
4. **Backlog audit.** Build a single deduplicated backlog from `BUGS.md`, `LIVE_CHECKS.md`,
   the 2026-10-01 handoff, both playtest notes, `AGENDA.md` and `BALANCE.md`. For each item
   say: done and verified, done but unverified, open, or contradicted by the code. Known
   open items to confirm and rank:
   - PD-104: other rooms wire comparators or repeaters backwards (`SituationSpecs`
     sorting_floor, `SpurSpecs` barred_vault, ominous_bargain, the_altar). Decide which are
     driven by `Locks` and which are genuinely unsolvable.
   - Mending as an expensive emerald-paid "lock in" station action (owner direction, open
     question: is a locked item exempt from salvage and reroll). PD-89 durability on drops.
   - Economy asks: lapis (only `supply_tier_2`, weight 2), enchanting table is looted at
     tier 3 and 4, Mining Efficiency is dead weight, sand in foundry and brewing pools,
     totem of undying needs a use (omen reduction idea), blacksmith table discovery hint,
     dungeon-only ender chest, text-display HUD, stacked chest decor.
   - Carryover never verified live: PD-85, 87, 88, hold-the-plate waves, thicket web lattice,
     `room_bias_hold`, PD-81/82, HOME title, gallery decor, L1 ladders, L2 anomaly build,
     L4 stack merge, torch-drop half of L8.
5. **Process and harness changes** (`tools/server/*`, `tools/lemon/*`,
   `.claude/skills/playtest/SKILL.md`, `docs/LEMON_AGENT.md`, `Lemon.java`): skim for
   regressions and for docs that no longer match behaviour.

## Things I am least sure about (look hardest here)

- The ominous roll replaced a long-standing door promise. Is any other system (rewards,
  bounties, the Ominous Bargain, vault key type, `layout.ominous()`, `RunLifecycle` payout)
  still assuming door 2 is ominous?
- Tier 4 copies of every themed loot table were made by text replacement of `tier_3` with
  `tier_4`. Look for tables that now reference a missing or wrong path, and for
  `random_sequence` collisions.
- `TrialContent.cellBlockEntities` now includes y down to -9. Could that pick up block
  entities from a neighbouring cell or an unrelated lower story and mis-anchor, clear
  classic spawners it should not, or count extra vaults?
- Room templates (`kennel_crossing.nbt`, `rotation_lock.nbt`, `sump.nbt`) were recaptured by
  running `RoomTemplateGenerator.generate` from a throwaway gametest; the generator writes to
  `build/run/src/...` under gametests and the files were copied by hand. Confirm the NBTs in
  `src/main/resources` match the specs and that nothing else changed in them.
- The `OmenBar` held line repaints on `sync`; check its cadence is real and that a held line
  cannot overwrite a more important action bar message for long.
- Tests that pass are not proof of live behaviour. Say which claims rest only on a unit or
  game test and which on reading code.

## Verifying

From `A:\MrPinoys Mods\pocketdungeons`:

```bash
./gradlew test --console=plain
./gradlew stationTutorialTest --console=plain
./gradlew runGameTest --console=plain
```

`runGameTest` takes about two minutes and the last known result was 118 of 118 passing. A
`TODO`-free pass is not the goal; hunting for what the tests do not cover is.

## Deliverable

`pocketdungeons/docs/review-2026-10-01-findings.md`, in this order:

1. Verdict in five lines: is the working tree safe to commit as is, and what must change first.
2. Findings, most severe first. Each: file and line, what is wrong, a concrete failure
   scenario, and a suggested fix. Mark each CONFIRMED (you reproduced or proved it) or
   PLAUSIBLE.
3. The deduplicated backlog table from scope item 4, with a recommended order of work and
   which items need an owner decision rather than code.
4. A list of what you could not verify and why.
5. A suggested commit split (the working tree mixes bug fixes, a loot redesign, a tutorial,
   harness changes and generated files) so the history stays reviewable.
