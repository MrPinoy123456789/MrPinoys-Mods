---
name: tune-knobs
description: Tune Pocket Dungeons balance without rewriting code. Lists every tuning knob (runtime config keys in pocketdungeons.json, data-side knobs applied by tools/tune_loot.py, dungeon and room JSON values, and hard-coded constants that deserve to become knobs), says where each lives, what it does, its default and how to change and verify it, and how to add a new knob. Use when asked to make something pay more or less, change odds, durability, lives, scrap, spawner or ore amounts, or to add a tunable.
---

# Tuning knobs

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`.

## The four layers (pick the cheapest that works)

| Layer | Where | Applies | Use for |
|---|---|---|---|
| 1. Runtime config | `config/pocketdungeons.json` on the server (defaults in `PocketDungeonsConfig.java`) | on the next server start (it is read once at boot; there is no reload command) | numbers the code reads: payouts, caps, chances, timers |
| 2. Data knobs | `tools/loot_knobs.json`, applied to the shipped data by `python tools/tune_loot.py apply` | on the next deploy (the data is in the jar) | values that live in datapack JSON the game cannot read config for |
| 3. Content data | `dungeon/*.json`, `dungeon_room/*.json`, `loot_table/**`, `trial_spawner/**` | on the next deploy | per dungeon or room values: weights, ore counts, lives, loot bands |
| 4. Code constants | `static final` values in Java | on the next build and deploy | structural values; promote to layer 1 when they get tuned twice |

Rule of thumb: if the owner is likely to ask for a different number again, make it layer 1 or 2, not layer 4.
A key missing from an existing `pocketdungeons.json` takes its default, so shipping a new default does not need the
server file edited; a key that IS in the file overrides the default, so check the server's file when a deploy does not
seem to change a value (the Kinetic panel file manager shows `config/pocketdungeons.json`; `pdserver.mjs` has no config
command).

## The knobs set on 2026-10-08

1. **`salvageMaterialBonus`** (layer 1, default `1`, range 0 to 20). Every salvaged piece pays this many more of its
   material in every wear band: what paid 0 pays 1, 1 pays 2, 2 pays 3. `0` restores the old table.
   Code: `SalvageStation.materialsBack`, `SalvageMath.withBonus`. Tests: `SalvageMathTest.testBonus` and `SalvageGameTest`, which reads the knob so it still passes at any value.
2. **`spawnerKeyPercent`** (layer 2, `tools/loot_knobs.json`, default `60`). When a trial spawner is beaten it ejects a vault
   key or emeralds; the key share is key weight over total weight. `python tools/tune_loot.py apply` raises every spawner
   config below the knob to it (5/5 became 6/4); a spawner already above it (tier 1 spawners are 8/2) is left alone, so
   the knob is a **floor**. For an exact value on every spawner: `python tools/tune_loot.py apply --exact` or
   `python tools/tune_loot.py spawner-keys --percent 50 --exact`. `python tools/tune_loot.py show` prints the weights now;
   `apply --check` changes nothing and exits 1 if files differ. `tools/gen_themed_content.py` reads the same knob, so
   regenerating does not undo it. Test: `SpawnerEjectOddsTest` fails if any spawner is under the knob.
3. **`lootDurabilityPercent`** (layer 1, default `110`) and **`craftedDurabilityPercent`** (default `110`). Dungeon gear is
   capped to the table in `DungeonTools.durabilityCap` (iron sword 64, diamond sword 128 ...). Gear from the mod's own chests,
   vaults and gear tables (loot table ids in the `pocketdungeons:` namespace) gets the table times `lootDurabilityPercent`;
   crafted gear and everything else gets it times `craftedDurabilityPercent`. Both are 110, so all dungeon gear has 10
   percent more than the table (owner, 2026-10-08); set either to 100 to take its share back to the table. Gear that took the higher loot cap is not cut back when it later drops as an item. To change the base table itself,
   edit `durabilityCap` (layer 4). Tests: `DungeonToolsTest.testLootDurabilityKnob` (the arithmetic) and `DurabilityKnobGameTest`
   (item stacks; a pure `main()` cannot build one because item components are not bound there).

## Existing runtime knobs (layer 1)

All are in `PocketDungeonsConfig.java` with their default; the field's Javadoc says more. Grouped by what a tuner wants.

- **Economy and rewards:** `finishEmeralds` 8, `finishVaultChests` 2, `failHaulKeepPercent` 50 (share of the haul a failed
  dungeon keeps), `salvageKeyEmeralds` 1, `salvageOminousKeyEmeralds` 3, `salvageMaterialBonus` 1, `ordealEmeraldChance` 0.25,
  `rerollLapisPerTier` 4, `equipCap` 3, `keystoneMaxLevel` 100.
- **Difficulty:** `mobScaleBase` 0.65, `mobScalePerLevel` 0.008, `omenDangerScalePerOmen` 0.15, `spawnerClearThreshold` 0.75
  (share of spawners that must clear to pass), `trialSpawnerCooldownTicks` 36000, `breezeHpMultiplier` 0.5,
  `firstFloorMaxEncounters` 2.
- **Affix strength:** `swarmingMobFactor` 1.5, `overclockedCooldownFactor` 0.4, `silencedConsumablesPerOmen` 3,
  `silencedPlayerRange` 6, `chaoticSpawnerChance` 0.05, `moltenHazardsPerCell` 4, `explosiveHazardsPerCell` 4,
  `feralWolvesPerCell` 2, `voidedCellChance` 0.3.
- **Floor shape and rarity:** `pathLengthMin` 8, `pathLengthMax` 12, `branchProbability` 0.55, `loopProbability` 0.30,
  `maxGridSpan` 12, `planAttemptBudget` 32, `anomalyRoomChance` 0.08, `fountainChance` 0.15, `pocket2DoorChance` 0.2.
- **Pacing and progress:** `floorsPerSafeVisit` 3, `endlessMineUnlockLevel` 3, `timedOutDepletion` (superseded by the quit
  rule; kept for old files), `afkSeconds` 300, `ownerReconnectGraceSeconds` 120, `rewardRoomGraceSeconds` 600.
- **Durability and salvage:** `lootDurabilityPercent` 110, `craftedDurabilityPercent` 110, `salvageMaterialBonus` 1.
- **Effect caps:** `poisonMaxSeconds` 10, `witherMaxSeconds` 8, `slownessMaxSeconds` 6, `miningFatigueMaxSeconds` 30 (0 = off): the longest
  each effect lasts on a player inside a dungeon (`EffectCaps`). Darkness is not capped (the shrieker uses it).
- **Astrolabe Room:** `hallEnabled` true (off restores the three random doors at the first door), `repeatFinishEmeraldPercent` 50
  (what a repeat finish of a dungeon pays of the finish emeralds; the first finish pays all).
- **Trip sidebar:** `sidebarEnabled` true, `sidebarRepaintTicks` 20 (`SidebarDisplay`; players hide theirs with `/dungeon display off`).
- **Capacity:** `maxPartyMembers` 6, `maxConcurrentInstances` 32, `maxConcurrentVisits` 16, `maxConcurrentPreviews` 16.
- **Lemon timings:** `lemonFallbackSeconds` 45, `lemonThinkSeconds` 90, `lemonLlmLapseSeconds` 300, `lemonIdleSeconds` 20.

## Data values that are knobs (layers 2 and 3)

- **Per spawner (tier and theme):** mob counts and cooldowns are generated from the `NORMAL`/`OMINOUS` tables in
  `tools/gen_themed_content.py`; edit the table and rerun the script, never the output files.
- **Per room (`dungeon_room/*.json`):** `weight`, `maxPerDungeon`, `minDepth`, `light`, and node `count`/`chance` (how much ore).
- **Per dungeon (`dungeon/*.json`):** `lootBand`, `baseLevel`, `hiddenOre` (pockets and sizes), `nodePalette`, `minNodeRooms`,
  an edge's `lives` (the price of a blood door, 0 to 2), a node's `roomBias` (x3 weight on that floor) and `roomCount`.
- **Loot tables:** weights and counts in `loot_table/**`; the K/K2 policy and its generators are in `dungeon-content`.
  Add a loot-table value to `tools/loot_knobs.json` and `tune_loot.py` when it needs tuning more than once.

## Hard-coded values worth promoting to knobs

Not knobs today. Each is a `static final` or a literal; promote one with the recipe below when the owner asks to move it.
- `ScrapMath.SCRAP_PER_CHART` 5 (scrap per compass level), `ScrapMath.floorPay` (the step at or above the compass, else 1).
- `Omen.LIVES` 5 and `DoorLives.MAX_LIVES` 2 (party lives and the most a door can cost); lives scaling with party size is a
  standing player ask.
- `PlateRelayOrdeal`: `CHARGE_SECONDS` 3, `LIVE_SECONDS` 15, `BASE_CHARGES` 5, `MAX_CHARGES` 8, `ALIVE_CAP` 6, `SPAWN_CAP` 16.
- `RoomSelector.RUBBLE_CHANCE` 0.35 (floors with a rubble plug); `RoomEligibility.DUNGEON_BOOST` 5 and `ROLE_BOOST` 3;
  the x3 `roomBias` boost in `RoomSelector`.
- `SalvageMath.band` thresholds (75 and 25 percent wear) and the per-band material counts.
- `CowPits.FLOOR_CAP` 13, `FLOOR_MIN` 6, `ROOM_COWS`, `ROOM_LIMITS`.
- `DungeonTools.durabilityCap` table (the base durability of every capped item).
- Title and cue timings (`StaggeredTitle`: 100-tick delay, 90-tick stay) and the omen bar cue cooldowns.
- Candidates the player has asked for or the soak suggests: witch poison duration (player: about 10 s), vault uses per
  player vs per party, the finish key redemption rate (J7), the number of spawners a floor needs (`spawnerClearThreshold`
  is already a knob), the cow ward and groves' wood and sand budgets (room JSON counts), and the share of floors that roll
  a themed room (`DUNGEON_BOOST`).

## How to tune (the procedure)

1. Say the number in plain words ("a worn sword pays 1 instead of 0") and find the layer above.
2. Change it: edit the default in `PocketDungeonsConfig.java` (layer 1) AND tell the owner if the server's
   `config/pocketdungeons.json` carries an override; or edit `tools/loot_knobs.json` and run `python tools/tune_loot.py apply`
   (layer 2); or edit the JSON / generator (layers 3 and 4).
3. Run the tests: `./gradlew.bat test runGameTest dungeonIntegrationTest --continue --console=plain > /a/tmp/run.txt 2>&1`
   (see `add-tests`). Update the test that pins the old number; add one if none does.
4. Record it: a line in `docs/playtests/BALANCE.md` (what, old, new, why) and, for a model change, a `design-brief` decision doc.
5. Deploy with `deploy-test-server` and add a live check to `docs/playtests/LIVE_CHECKS.md` when the effect can only be felt in play.

## Adding a layer-1 knob (five places in `PocketDungeonsConfig.java`)

1. the `private static int name = DEFAULT;` field with a Javadoc line saying what it does and the unit;
2. the public getter;
3. the reset block (`name = DEFAULT;` where the other defaults reset);
4. the read: `name = readInt(root, "name", DEFAULT, v -> v >= LOW && v <= HIGH, "must be LOW to HIGH");` (`readDouble`,
   `readBoolean`, `readString` exist);
5. the defaults writer: `root.addProperty("name", DEFAULT);`.
Then use the getter where the constant was, and add a pure test for the arithmetic (a `static` method on plain ints, as
`SalvageMath.withBonus` and `DungeonTools.scaledCap` are). A retired knob goes into `RETIRED_KEYS` instead of being deleted
so old config files still load.
