# Payoff rooms: build plan (2026-10-17)

For the agent building this. Follow the `agent-brief` skill's standing rules. Read the `room-building`
and `add-tests` skills before you start; this plan assumes them and does not repeat them.

## Why this exists

The solvability pass in `RoomSelector` is sound, but it has almost nothing to work on. About 20 rooms
`provide` a capability tag (`ledge_archers` gives `bow`, `the_herd` gives `gold`, `breeze_arena` gives
`wind_charge`, `gallery` gives `blocks`, and so on), and only two rooms in 164 `require` one
(`barred_vault`: `trial_key`, `plate_pair`: `mob`). Every playable bag but Starter is hidden, and Starter
carries no tags, so rooms are the only source of capabilities.

This build adds side rooms that cash in a tag found earlier on the same floor, and makes the selector
place them when that tag really is available. The main path stays free of requirements. Nothing here
may make a floor unfinishable.

## Owner rules that bind this build

- **No hopper mechanisms.** The hopper toll was named the worst room of a session (PD-186). On
  2026-10-17 the owner said the problem was the hopper interface, not paying gold. Pay with a vanilla
  vault block (walk up, use the item), as `SpurVault` does for the Barred Vault.
- **Tangible over menus.** No dialog screens. Everything is a block the player uses.
- **Main path untouched.** These rooms are dead ends off the spine. Their `requires` may name a tool
  nobody carries; a shut bonus room is a sign, not a dead end.
- **Scarcity.** Do not place blocks that drop gold (gold blocks, raw gold blocks, gilded blackstone,
  gold ore) as decoration. Rewards stay modest.
- **Dash rule** (`A:\MrPinoys Mods\CLAUDE.md`) for every comment, javadoc, doc and commit message.

## Branch and commits

- Branch `feature/payoff-rooms` off `master`. Do not push, do not deploy.
- One commit per part below, tests green before the next. Author `MrPinoy123456789` (see the
  `agent-brief` skill for the exact `-c` flags).

## Part 1: favour payoff rooms in the selector

**Goal.** A dead-end cell off the spine draws a room whose `requires` is satisfied by tags provided
closer to the entrance three times as often as its declared weight says. Spine rules do not change.

**Where.** `src/main/java/pocketdungeons/RoomSelector.java`, inner class `CellState` (around line 1170)
and its `weightOf` (around line 1238).

**What already exists, reuse it.**
- `availableFor(int index)` (around line 1130) returns the seed tags plus the `provides` of every cell at a
  strictly smaller root distance. That is exactly "reachable before this cell", so do not write a second
  version. For a cell off the spine the seed is `bagTags` (which includes `mob` for a party of two or more).
- `weightOf` already multiplies a recipe's `weightedRooms` by 3. Add the payoff factor next to it.

**Change.**
1. Add a constant beside `MAX_BACKTRACK_STEPS`:
   `static final int PAYOFF_WEIGHT_FACTOR = 3;` with a javadoc saying what it does and why 3 (a nudge, the
   same size as the recipe bias, never a guarantee).
2. In the `CellState` constructor, compute `availableFor(index)` once, keep it in a field (for example
   `available`), and pass that same set to `solvable(...)` so it is not computed twice. Also keep a
   `boolean offSpine = !onSpine.contains(cell)`.
3. In `weightOf`, after the existing base and recipe factor:
   if `offSpine`, the match's `meta.requires` is not empty, and `available.containsAll(meta.requires)`,
   multiply by `PAYOFF_WEIGHT_FACTOR`. Rooms whose requires are not satisfied keep their weight: do not
   penalise them.
4. Forced cells (`forced.contains(cell)`, which use `roleOnly`) need no change; `roleOnly` only returns
   rooms with empty `requires`, so the factor never applies there.

**Expect test churn.** This changes draws wherever a spur candidate has satisfied requires (today only
`barred_vault` with an upstream `trial_key`, and `plate_pair` in a party). Tests that pin a room at a
seed may shift. Read each failure: if it pins a specific room by seed, re-pin it and say so in the
commit; if it asserts a rule (solvability, rubble, caps), the change is wrong, so stop and report.

**Test.** Add to `src/test/java/pocketdungeons/GraphSolvabilityTest.java` (it already has the fixture
helpers `everyMask`, `meta` and a hand-built spur fixture near line 868 that checks a spur takes
`barred_vault`; copy that shape):
- `payoffRoomFavouredWhenProviderUpstream`: a fixture where a cell at depth 1 on the spine can only take
  a room that `provides: ["gold"]`, and one dead-end spur cell deeper has two equal-weight candidates,
  one `requires: ["gold"]`, one with no requires. Over 400 seeds the gold room's share must be above
  0.65 (expected 0.75).
- `payoffRoomNotFavouredWithoutProvider`: the same fixture with the provider swapped for a plain room.
  The gold room's share must sit between 0.35 and 0.65 (expected 0.5; it is still allowed, as a shut
  bonus).
- `sameDepthProviderDoesNotCount`: the provider at the same root distance as the spur. Share as in the
  previous test. This pins the "reachable before" rule.
Print the measured share in each failure message.

## Part 2: the Gilded Vault (gold)

**Goal.** A dead-end room with a vanilla vault behind iron bars that takes one gold ingot and pays a
better reward. Each party member can open it once with their own ingot (vanilla vault behaviour).

**Files.**
1. `src/main/java/pocketdungeons/SpurVault.java`: add an overload
   `static void apply(ServerLevel level, BlockPos origin, boolean ominous, Item key, String lootTable)`
   holding today's loop, and make the existing `apply(level, origin, ominousFloor)` call it with
   `keyFor(ominousFloor)` and `LOOT_TABLE`. Add `static final String GILDED_LOOT_TABLE = "vaults/gilded_vault";`
   and `static final Item GILDED_KEY = Items.GOLD_INGOT;`. Barred Vault behaviour must not change.
2. New spec file `src/main/java/pocketdungeons/PayoffSpecs.java`, same shape as `SpurSpecs`
   (`list()`, `registerHandlers()`, door `Direction.NORTH`, authored at rotation 0). First room:
   `gilded_vault`. Decor: copy Barred Vault's dais (chiseled stone bricks at x 7..9, y 1, z 11), a
   `Blocks.VAULT` facing NORTH at (8, 1, 10), iron bars at x 7 and 9, y 1..2, z 10. Dress it with the
   `ACCENT` placeholder and lit candles, never gold-bearing blocks. No spawner, no spec chest.
3. Handler in `PayoffSpecs.registerHandlers()`: `Situations.register("gilded_vault", ...)` that calls
   `SpurVault.apply(level, o, false, SpurVault.GILDED_KEY, SpurVault.GILDED_LOOT_TABLE)` and returns
   `null` (returning null owns the cell, so the corridor pass leaves it alone). Always non-ominous.
4. Wire the file in: `specs.addAll(PayoffSpecs.list());` in `RoomTemplateGenerator.specs()`,
   `PayoffSpecs.registerHandlers();` in `TrialContent.warmUp()` next to `SpurSpecs.registerHandlers();`,
   and `"PayoffSpecs"` in `RoomLibraryGameTest.CHECKED_SPEC_CLASSES`.
5. Manifest `src/main/resources/data/pocketdungeons/dungeon_room/gilded_vault.json`, copied from
   `barred_vault.json` with: `"template": "pocketdungeons:rooms/gilded_vault"`, `"content": "gilded_vault"`,
   `"requires": ["gold"]`, `"maxPerDungeon": 1`, and **no** `pressure` field (the omen relief life is
   the Barred Vault's alone). Keep `roles: ["loot"]`, `minDepth: 2`, `tier: 2`, `access: open`,
   `window: bars`. No `dungeons` binding: it is a generic room, eligible everywhere.
6. Loot table `src/main/resources/data/pocketdungeons/loot_table/vaults/gilded_vault.json`, type
   `minecraft:vault`, worth clearly more than one gold ingot but less than the Barred Vault: one pool,
   2 to 3 rolls, from iron ingots (2 to 5), emeralds (1 to 3), arrows (4 to 8, they are scarce), and an
   iron sword or iron pickaxe with `enchant_with_levels` 5 to 15, the `pocketdungeons` custom data
   (`tier: 2, bag: 1`) and `set_damage` 0 to 0.4, copied from `barred_vault.json`. No trial key, no
   enchanted book with `enchant_with_levels` (`BookLootTest` rejects it).
7. Generate the `.nbt` with the throwaway gametest from the `room-building` skill, copy only
   `gilded_vault.nbt`, delete the throwaway.

**Verify in code before relying on it.** That vanilla `VaultBlockEntity` takes exactly the key stack's
count (one ingot) and pays each player once. Read the 26.2 vault server logic with `javap` if unsure;
if it differs, say so in the report.

**Test.** Add a method to `src/gametest/java/pocketdungeons/VaultRoomGameTest.java`:
`gildedVaultTakesGold`: place a `Blocks.VAULT`, call the new overload, assert the config's key item is
`Items.GOLD_INGOT`, the loot table path is `vaults/gilded_vault`, and the block is not ominous. Rerun the
two existing Barred Vault methods unchanged.

**Record the decision.** Add a dated section to `docs/DECISIONS.md`, in the shape of the 2026-10-09 one:
"2026-10-17: gold may open a vault". Body: the owner said the hopper interface, not the gold, made the
toll room the worst room; gold returns as a vanilla vault key in the Gilded Vault; hoppers stay out.
Supersedes: the gold half of 2026-10-09 item 6 (`ominous_bargain` stays toll free). Open question: is
one ingot the right price once players can find gold (supply chests and `the_herd`'s gold blocks, which
need an iron pickaxe).

## Part 3: Mason's Shortcut (blocks)

**Goal.** A cache the player can see but only reach by placing blocks.

**Spec** `masons_shortcut` in `PayoffSpecs`, door NORTH, placeholders for structure so it re-skins:
- A raised walkway, top surface at y 4, across the near half of the room, reached by a stair of full
  blocks (one block rise per step).
- A gap of at least 5 blocks (a sprint jump clears 4) between the walkway and a far platform, top
  surface at y 4, at the back wall. The chest sits on the far platform at about (8, 4, 13), placed **in
  decor** with the `placeChest` pattern from `SpurSpecs`, table `vaults/spur_payoff`.
- The floor under the gap is ordinary floor at y 1, and it must lead back to the door by a walkable
  route with two blocks of headroom (leave a side passage past the walkway). A fall is at most 3
  blocks, so it never hurts.
- Door lane rule: x 7..8 clear for z 1..3.
- Pillaring 3 blocks from the gap floor next to the platform also works. That is intended: it is still
  spending blocks.

**Why the chest goes in decor.** `RoomLibraryGameTest` flood fills from the doors and fails on any
**spec** chest it cannot walk to. The cache is meant to be unreachable on foot, so it must not be a
`.chests(...)` entry. The handler returning `null` stops the corridor pass from stripping it.

**Handler** `masons_shortcut`: returns `null`.

**Manifest** `masons_shortcut.json`: copy `gilded_vault.json`, `"requires": ["blocks"]`,
`"content": "masons_shortcut"`, `tier: 1`, `minDepth: 2`, `maxPerDungeon: 1`, `window: bars`.

## Part 4: Windlift (wind charge)

**Goal.** A cache on a high ledge that a wind charge jump reaches.

**Spec** `windlift` in `PayoffSpecs`, door NORTH:
- Solid ledge filling y 1..3 at the back (about x 5..10, z 12..14), top surface y 4, chest on it at about
  (8, 4, 13), placed in decor (same reason as Part 3), table `vaults/spur_payoff`.
- Two blocks of headroom above the ledge (y 4 and 5, shell at 6).
- Mark the launch spot with one distinct floor block at the foot of the ledge (for example
  `Blocks.CHISELED_TUFF`, with `TUFF_BRICKS` trim so the room reads as trial chamber, where wind charges
  come from). No signs, no text.
- Dropping off the ledge is a 3 block fall: safe. Nothing in the room needs the charge to get back.
- Pillaring 3 blocks also reaches it; intended.

**Handler** `windlift`: returns `null`.

**Manifest** `windlift.json`: as Part 3 with `"requires": ["wind_charge"]`, `"content": "windlift"`.

**Shared loot table** `vaults/spur_payoff.json`, type `minecraft:chest`, smaller than the Gilded Vault:
2 to 3 rolls from iron ingots (1 to 3), emeralds (1 to 2), arrows (3 to 6), bread (2 to 4), and a stone
or iron tool with the `tier: 1, bag: 1` custom data and `set_damage` as in other tier 1 tables. Run
`LootRulesTest` and `BookLootTest`.

**Tests for Parts 3 and 4.** The library connectivity check covers the doors once `PayoffSpecs` is in
`CHECKED_SPEC_CLASSES`. Add one gametest method per room (in a new `PayoffRoomGameTest`, scaffolded with
`python tools/new_gametest.py PayoffRoom`): stamp the spec, assert the chest exists at its position with
the `vaults/spur_payoff` table, and assert there is a walkable route from the gap floor (Mason) or the
ledge foot (Windlift) back to the door. Check the gametest count rises by the number of methods.

## Part 5 (optional, only after 1 to 4 are green): spawner quota proof

**Goal.** Prove what the 2026-10-17 review could not: that the spawners reachable without any tool
always meet the floor clear threshold.

**Facts.** `spawnerClearThreshold` is 0.75 (`PocketDungeonsConfig`, around line 265). An untouched
spawner counts against the total. `RoomSelector` already finds encounter cells (around lines 167 to 182:
cells with a `TRIAL_ENCOUNTER` role, plus rooms matched by `hostsEncounter`).

**Test** in `GraphSolvabilityTest`'s seed sweep, for every resolved `SPAWNERS` plan with the Pilgrim seed:
walk from the entrance over cells whose placed room's `requires` is satisfied by `availableFor`-style
tags (seed plus provides of shallower cells), treating rubble edges as blocked. Count encounter cells
reached. Assert `reached >= ceil(0.75 * total)`.

**If it fails on any seed, do not change the selector.** Report the seeds, the cells and the blocking
room. That is a design decision for the owner.

## Out of scope for this build

- `TARGET_HIT` lock and the bow or snowball rooms (needs a new `Locks.Kind`; check first whether
  `ledge_archers` ever gives the player a bow, and whether the existing snowball sensor room in
  `SituationSpecs` around line 321 already covers it).
- Demolitionist's Cut (lead a creeper from `creeper_kennel` to a marked wall).
- Water detection rooms.
- Any change to bags, `provides` tags or spine rules.

## Verification and report

- Full run once at the end, from Git Bash:
  `./gradlew.bat test runGameTest dungeonIntegrationTest --console=plain > /a/tmp/run.txt 2>&1; echo $?`
  Success: exit 0, `All N required tests passed`, `dungeonIntegrationTest` PASS, and
  `Loaded N dungeon rooms` up by 3 with no rejection.
- Run the dash check from `CLAUDE.md` over everything you wrote.
- Add the live checks to `docs/playtests/LIVE_CHECKS.md`: the Gilded Vault takes one ingot and pays out
  and reads as a vanilla vault; the Mason's gap cannot be jumped; a wind charge reaches the Windlift
  ledge; each room shows its cache through the window from the corridor.
- Report per part: what changed, the tests that cover it, any re-pinned seeds and why, the measured
  shares from Part 1, and what only a live visit can confirm (look, feel, whether the cache reads from
  outside).
