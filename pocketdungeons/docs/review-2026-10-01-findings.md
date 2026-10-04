# Review of the 2026-09-28 to 2026-10-01 work (findings)

Reviewer pass on branch `wip-lemon-harness`, working tree as of 2026-10-02. No
source was edited. Commands run: `./gradlew test stationTutorialTest` (all
green), `./gradlew runGameTest` (118 of 118 passed), `node
tools/server/wait-filter.test.mjs` (27 of 27 passed). Everything else came from
reading code, data, shipped room templates (parsed block by block), the 26.2
game jar (`javap` on `Wolf` and `TrialSpawner`), the server logs and the
playtest journal.

## 1. Verdict

1. Not safe to commit as one lump, and two fixes claimed as done are not done: PD-98's kennel half (wolves still cannot spawn: the pen is too dark) and PD-104 (worse than filed: two spur rooms cannot be solved at all).
2. Fix before committing: the station tutorial nag swallows any pending player question (`Lemon.deliver` resolves it), and its hint text sends players to craft blocks the lodestone "Stations" menu already hands out ("Build a beacon").
3. Fix before committing: `ZoneRules.MAX_LOOT_TIER` is still 3, so an Endless Mine run at level 20+ is pushed down from tier 4 to tier 3.
4. The loot JSON is structurally clean (no missing refs, no wrong tier tags, trims parse). The open risks are economy, not data: lapis, dead ender chests, random trims feeding M15 bonuses.
5. Commit in the split in section 5. Mark PD-98 (kennel) and PD-104 open in `BUGS.md`. Keep PD-99, 100, 101 and 103 as "fixed, live check owed".

## 2. Findings, most severe first

### F1. PD-98 kennel: wolves still cannot spawn, because the pen is too dark (High, CONFIRMED by bytecode and template; light value computed by hand)

- **Where:** `SituationSpecs.java:424` (grass pen), `structure/rooms/kennel_crossing.nbt`, `trial_spawner/kennel_crossing/{normal,ominous}.json`, test `HandlerGameTest.java:454` (`kennelPenLetsWolvesSpawn`).
- **What is wrong:** in 26.2, `TrialSpawner.spawnMob` always calls `SpawnPlacements.checkSpawnRules` (custom spawn rules are an extra check, not a bypass). `Wolf.checkWolfSpawnRules` needs `WOLVES_SPAWNABLE_ON` below **and** `Animal.isBrightEnoughToSpawn`, which is raw brightness above 8. The dungeon dimension has `has_skylight: false`, so only block light counts. The kennel's only light is four sea lanterns at (4,6,4), (11,6,4), (4,6,11) and (11,6,11). The pen spawn spots are (7..8, 1, 7..9). The nearest lantern is 10 to 11 blocks away by light path, so block light there is 4 or 5.
- **Failure scenario:** a player biases `kennel_crossing` for L9, stands in the room, and nothing spawns, exactly as on 2026-10-01-2. The new gametest passes because it checks only that the blocks are grass with air above. It never ticks the spawner or checks light.
- **Fix:** put light in or on the pen. A lantern on a fence post is about 3 blocks from every spot (light 12). Then rewrite the test to stamp the room in the dungeon dimension (or under a roof), activate the spawner with a player in range, and assert that a `Wolf` entity appears. Until then, keep PD-98 open for the kennel and leave the L9 kennel row owed.

### F2. PD-104 is worse than filed: Barred Vault and Ominous Bargain cannot be solved, and The Altar has no redstone (Medium, CONFIRMED from the shipped templates)

From parsing `barred_vault.nbt`, `ominous_bargain.nbt`, `the_altar.nbt` and `sorting_floor.nbt`:

- **barred_vault, ominous_bargain** (`SpurSpecs.java:201`, `:240`):
  - The filter hopper at (8,1,7) faces south into the iron door at (8,1,8). The door is not a container, so a fed item never reaches the chest at (8,1,10).
  - The comparator at (9,1,10) faces NORTH, so it reads the dust at (9,1,9). It never reads the chest, which sits to its west.
  - The two dust blocks run north to south beside the door with `east/west=none`, so they would not power it anyway.
  - **Failure scenario:** a player feeds a trial key or a gold ingot and the door never opens. The spur reward and both omen effects (the Bargain's set-to-4 and the Barred Vault's relief, both keyed on taking from that chest in `OmenSources`) are unreachable.
- **the_altar** (`SpurSpecs.java:280`): the template holds two hoppers and the dropper, but no comparator, repeater or dust. They were placed with nothing solid under them at y 1 and did not survive the capture. Even as written, the comparator at (9,2,8) faces SOUTH and reads the repeater, not the hopper to its west. Feeding food never fires the dropper.
- **sorting_floor** (`SituationSpecs.java:296`): the comparator is backwards (FACING NORTH, chest to the south). The dust line runs north to south and does not point into the door at x 15. A bigger problem: the template has doorway jigsaws only on the west wall, although the spec says ENTRANCE and EXIT. The planner likely never places it as a corridor. It appears in no playtest journal. Latent today; if its east jigsaws are ever restored, the shut iron door would block the critical path whenever the connector roll leaves it in place.
- **Which are driven by `Locks`:** none. `Locks.arm` is called only from `MechanismSpecs` (item_plate, gallery, flow puzzle, frame lock, plate pair). All four rooms rely on their own redstone.
- **Fix:** flipping FACING alone is not enough. Each room needs:
  - the filter hopper chained into the chest, for example hopper to hopper around the door frame;
  - the comparator directly behind the chest, facing the chest;
  - the output run into a block next to the door, as `rotation_lock`'s repeater-into-wall now does;
  - for The Altar, support blocks under its redstone.

  Then add a solve gametest per room, in the shape of `rotationLockSolvesAtEveryRotation`. Journal check: none of the four rooms has ever stamped in a logged session, so no player has hit this yet.

### F3. The station tutorial nag swallows the player's pending Lemon question (Medium, CONFIRMED by reading)

- **Where:** `StationTutorial.java:126` calls `Lemon.say`. `Lemon.java:356` (`deliver`) calls `resolvePending(player, state, now, "llm")` before showing any line.
- **Failure scenario:** in a Lemon session the player asks Lemon something, then pulls GO HOME before the agent answers. `nagMembers` runs on the HOME transition. Because a question is pending, the nag counts as "prompted" and is shown at once. `resolvePending` then:
  - journals the question as answered by `llm`, although the answer is a tutorial line;
  - clears `pending`;
  - zeroes `thinkingUntil`, which undoes the PD-79 `think` hold that `lemonwatch.mjs` now fires on every ask.

  The agent's real answer still arrives, but L6 latency data and the journal are wrong, and the player sees a non-answer first.
- **Fix:** give server-originated lines a path that never resolves pending questions, or skip the nag while `pending` is non-empty and retry on the next arrival.

### F4. The tutorial hint text does not match how stations are obtained (Medium, CONFIRMED by reading)

- **Where:** `StationTutorial.java:30`, `:40`, `:51`, `:61`.
- **What is wrong:**
  - `StationPicker` gives the owner every unlocked station block for free from the wall lodestone's "Stations" menu. The hints instead say "Craft a grindstone", "Craft a smithing table" and "Build a beacon".
  - A beacon needs a nether star, and no loot table in the mod contains one.
  - The gamble line names only the Blacksmith, not the gamble block the picker hands out.
  - Every hint fires for every party member, including a visitor in someone else's room, while the picker is owner-only.
- **Failure scenario:** a level 15 player is told on every arrival home to build a beacon. They cannot get one in the dungeon, so the nag repeats forever, since a step completes only on use.
- **Fix:** point every line at the lodestone's Stations menu ("Take a grindstone from your lodestone's Stations menu"), and nag only the room owner.

### F5. `ZoneRules.MAX_LOOT_TIER` is still 3: Endless Mine floors at level 20+ drop to tier 3 (Medium, CONFIRMED by reading)

- **Where:** `ZoneRules.java:91`, used at `:183` and from `RunLifecycle` completion chests.
- **Failure scenario:** at keystone 20 the base tier is 4. `lootTier(4, n)` returns `min(3, 4 + n/3)`, which is 3. Endless Mine completion chests and the "Loot tier N" line both say tier 3.
- **Fix:** use `LootTables.MAX_TIER`. Add a `ZoneRulesTest` case for base tier 4.

### F6. A failed commit leaves a stale preview whose entrance cell is gone (Medium, PLAUSIBLE)

- **Where:** `Instances.java:1421` (failure path). The commit-failure branch is at `RunLifecycle.java:650`.
- **What is wrong:** on a stamp exception, `commitDoor` clears every plan cell, the previewed entrance included. It leaves `phase = PREVIEW`, `previewPlan`, `previewCellOrigin` and `previewRecipePlan` set. By then the previous floor has also been reset. `RunLifecycle` refunds the catalyst and clears the keystone's recipe tags.
- **Failure scenario:** the player pulls the commit lever again on the same door. A same-door preview click returns early ("frozen preview is still valid"), and `commitDoor` stamps with `entranceAlreadyStamped`. The floor comes out with no entrance room behind the staging doorway. It also still carries the recipe effects from `previewRecipePlan`, whose catalyst was refunded. On 2026-10-01 the player happened to switch doors first (journal 07:51:20), which rebuilt the preview, so this was not hit.
- **PD-102 itself:** CONFIRMED as PD-97. `run/logs/2026-10-01-2.log.gz` line 263 has `room pocketdungeons:sump has spanY 2 but no climbable return path`, and the 01:13:52 disconnect is a normal "member disconnected" close.
- **Fix:** in the failure branch, call `clearPreview(level, record, false)`, or null the preview fields and drop `previewRecipePlan`, so the next lever pull needs a fresh preview. Then add a gametest that forces a stamp failure and retries.

### F7. Random armour trims feed the M15 trim bonuses, and "Mining Efficiency" is the copper trim, not an enchant (Medium, CONFIRMED by reading)

- **Where:** gear tables `gear/{helmet,chestplate,leggings,boots}_{1..4}.json` (five `random_chance` 0.03 trims each, so about 14 percent of armour drops are trimmed), `PocketDungeonsConfig.java:271-281` (`defaultTrimBonuses`), `TrimListener`.
- **What is wrong:**
  - A worn trim grants its material's attribute: gold is +0.02 movement speed per piece, which is about +20 percent of base each; copper is +0.5 `mining_efficiency`.
  - The lore line from `BonusLore.line` is humanised from the attribute path, so a copper trim reads "+0.5 Mining Efficiency". The player said "Just got my first Trim piece" at 20:51:54 and "Mining Efficiency is kind of lame" at 20:52:47.
  - The handoff's ask to "trim Mining Efficiency from enchant pools" is aimed at the wrong system. The vanilla enchant is named "Efficiency".
  - Gold (23 entries) and copper (17 entries) are the two most common rolled materials, and `trimBonusDungeonOnly` defaults to false.
- **Failure scenario:** loot armour now hands out free speed and a dead stat that used to take a template plus a smithing table.
- **Fix (owner decision):** drop copper from rolled trims or give it a dungeon-relevant bonus. Decide whether rolled trims grant bonuses at all, or use cosmetic-only materials. Weigh four gold-trimmed pieces at +80 percent base speed.

### F8. Salvage refuses every trimmed piece, now that about 1 in 7 armour drops are trimmed at random (Low to Medium, CONFIRMED by reading; owner judgement)

- **Where:** `SalvageStation.java:121`.
- **What is wrong:** the refusal was written for player-applied trims (template and material spent). With random trims, a junk leather helmet with a 3 percent iron trim cannot be salvaged and has no other sink. Rerolling does not care about trims.
- **Fix:** mark loot-rolled trims (for example `custom_data.pocketdungeons.loot_trim`) and let salvage take those, or pay a template-sized bonus for trimmed pieces. As is, the refusal is not acceptable for loot that the player never invested in.

### F9. The held omen line paints over newer, more important action bar lines (Low, CONFIRMED by reading)

- **Where:** `OmenBar.java:186` (hold set), `OmenBar.repaintHeldLine`.
- **What the cadence is:** real. `sync` runs every watch tick (20 ticks). The repaint gate is 40 ticks, so the SENSOR line is resent about every 2 seconds for 8 seconds.
- **What is wrong:**
  - A later cue from another source (a shriek, a dwell rise) does not clear `heldLine`. Within 2 seconds the sensor line paints over it.
  - The same happens to "Hold the plate: Ns" (`HoldThePlateOrdeal`), ordeal results (`Ordeals.java:98`) and "Kit restocked".
  - The held line goes to every member in the dimension, not only those in the sensor room, and keeps repainting after the floor clears.
- **Failure scenario:** a party fights while a sensor pulses. A shriek line ("the next fight...") or the plate countdown flickers back to the sensor text for up to 8 seconds.
- **Fix:** clear `heldLine` when any other cue fires or the phase leaves ACTIVE, and skip the repaint when another overlay was sent this second.

### F10. The ominous roll: what still assumes a door-2 promise, and smaller gaps (Low, CONFIRMED by reading unless noted)

Checked and fine:
- rewards and payout (`RunLifecycle` reads `record.floor.affixes`);
- trial omen and vault keys (`layout.ominous()` comes from the commit affix set);
- the journal (`door_commit` logs the rolled affix);
- the run screen (refreshed after commit);
- the recipe path (an ominous recipe skips the roll);
- multiplayer (the roll is per record and the message goes to every member);
- the first floor of an interval (`floorOmens` is empty, so the chance is 0).

Still wrong or open:
- **Door 2 still looks ominous:** it is still a crimson door, `DOOR_OMINOUS` (`RoomTemplateGenerator.java:311`, `:442`). `AGENDA.md:92` says "door 2 is ominous". `SITUATIONS_SPEC.md:1059` and `:1110` say "door 2 (tier 2, ominous)".
- **Door 2 lost its identity:** it is now a GREATER door at +2 for the same fuel as door 3 at +3. A6 asks whether door choices feel meaningful, and door 2 is now usually dominated. This is an owner decision.
- **Preview and commit can disagree (PLAUSIBLE):** the entrance cell is stamped at preview time with the pre-roll affixes and skipped at commit. If the entrance cell's role carries a spawner, vault or chest, that one cell stays non-ominous on an ominous floor.
- **The commit message misses the roll:** it reads "<name>. The door opens." from the pre-roll `granted` set and arrives after the purple "this one is Ominous" line.
- **The Bargain now forces the next floor:** drinking the Ominous Bargain sets the floor's omen to 4. If that is the only banked floor, the next floor is ominous with chance 1.0.
- **No test of the roll:** there is no gametest of the roll in `commitDoor`. Only the formula is unit-tested (`OmenMathTest`).

### F11. Lapis starves the Reroll Station, which the tutorial now points at from level 5 (Medium economy, CONFIRMED by reading)

- **What is wrong:**
  - Lapis has one loot source: `supply_tier_2`, weight 2, count 1 or 2. The Store NPC may also stock it.
  - A reroll costs 4 lapis per gear tier (`rerollLapisPerTier`): 8 at tier 2, 16 at tier 4.
  - The tutorial's REROLL step and the guided task `REROLL` both send players there.
- **Fix:** add lapis to the tier 2 to 4 chest and vault pools, or price rerolls in emeralds.

### F12. Dead weight and smaller loot notes (Low, CONFIRMED from the JSON)

- **Ender chests are dead loot:** `ender_chest` weight 4 in `chests/tier_{3,4}{,_ominous}` and `vaults/tier_{3,4}{,_ominous}`. They are blocked in the dimension (`RitualListener` M46), and home is in the dimension.
- **Enchanting table is still loot:** `enchanting_table` weight 3 at tiers 3 and 4. This is the owner ask to make it craft-only.
- **Netherite before tier 4:**
  - netherite gear appears only in tier 4 (`KeystoneMath.NETHERITE_LEVEL` 20);
  - `netherite_scrap` sits in `vaults/spur_ominous_bargain` and in tier 4;
  - `netherite_ingot` weight 2 in `chests/tier_4`.
- **Durability on drops (answers PD-89):** every gear table applies `set_damage` uniform 0.1 to 0.6, and `DungeonDrops` then caps max durability (diamond sword 96, netherite 128). A looted diamond sword therefore has about 10 to 58 uses left. That explains "broke very quickly". It is intended tuning, but it explains the third durability report.
- **Duplicates predate this batch:**
  - `supply_tier_N` pool 2 has the same block entry twice;
  - `equipment/tier_2_melee` has `iron_sword` twice and `tier_2_ranged` has `bow` twice.
- **Themed tables lack the trim pool:** the drowned tables (`chests/tier_N_drowned`, `vaults/tier_N_drowned`) are standalone and carry armour trims but no trim-template pool. The ominous drowned tables nest the plain ominous base, so they lose the drowned theme. Both predate this batch, and tier 4 copies the pattern consistently.
- **The generator is stale:** `tools/gen_themed_content.py` knows nothing of tier 4. Its only change is the glass bottle line. It must not be rerun.

### F13. Torches drop nothing in the player's own room (Low, CONFIRMED by reading; owner decision)

- **Where:** `DungeonDrops.java:79`.
- **What is wrong:** the rule checks only the dimension. The home room is in the dungeon dimension, so a player who moves a decorative torch at home loses it. That contradicts the HOME line "everything you place is kept".
- **Fix:** exempt positions inside the owner's room cell, if that is the intent.

### F14. Harness and documentation drift (Low, CONFIRMED by reading)

- `docs/LEMON_AGENT.md` and `.claude/skills/playtest/SKILL.md` never mention `tools/server/lemonwatch.mjs`. Section 4 still says "then go back to `wait`", while the handoff says never to call `wait_events` while lemonwatch runs, because the cursor is shared.
- lemonwatch auto-fires `lemon_think` on every ask, so the player sees "Let me check. Back in a moment." even before trivial answers. That is acceptable, but document it.
- `docs/playtests/2026-10-01-2.md:1` is titled "#1".
- `docs/playtests/2026-10-01-2.md:5` contains an em dash (house rule).
- `BALANCE.md:43` attributes the morning basalt_foundry floor (session 2026-10-01-1) to `2026-10-01-2.md`.
- The PD-98 entry in `BUGS.md` says the 21:27:11 warning "was not kennel_crossing" but blaze_cellar, while the handoff and playtest notes still say kennel. The code agrees with BUGS.md: the blaze_cellar spawner sits at y -8. Reconcile the notes.
- `RunRecipePlan.java:189` prints "keystone level " + minTier*5. Tier 3 starts at level 10, not 15, and tier 2 at 5, not 10. This predates the batch, but it is now visible next to tier 4.
- `CONVENTIONS.md` still says "exactly one mixin class", while the tree has `ResultSlotMixin`, `CraftedDurabilityMixin`, `BlockItemPlaceMixin`, `ServerExplosionMixin` and more. Long-standing drift: update the doc or the budget.
- Untracked `nul` at the repo root is a Windows `> nul` artifact; delete it, do not commit it.

### Checked and found sound

- **PD-99:** `rotation_lock.nbt` differs from HEAD in exactly two blocks (the comparator and the repeater facing WEST). `rotationLockSolvesAtEveryRotation` passes at all four rotations.
- **PD-97 and PD-78:** `sump.nbt` differs from HEAD only in the removed pillar and the new ladder column at (14, -7..0, 1). `everyMultiStoryRoomHasAReturnPath` passes.
- **`kennel_crossing.nbt`:** differs only in the six grass blocks.
- **`TrialContent.cellBlockEntities` widening:** the floor now reaches `-storyOffset(MAX_SPAN_Y)`, which is -8. It cannot pick up a neighbouring cell, because a cell is one chunk column and every slot sits at `BASE_Y`, so nothing is stacked below a cell. It does now find lower-story vaults and spawners in two-story rooms, which is the intent. `clearClassicSpawners` on blaze_cellar replaces only the classic spawner that `encounterAnchor` converts.
- **PD-101:** the bench opens with an empty input and never touches the hand. Sneak still gives the vanilla grindstone. The test passes.
- **PD-103:** the 48 by 24 by 48 box covers the staging room. Slots are far apart, so it cannot catch another room's blacksmith.
- **Tier 4 elsewhere:**
  - `FeralContent` clamps coats;
  - `GambleStation` offers tier 4 at 24 emeralds (36 for weapons), which is payable;
  - `RerollMath` and `SalvageMath` are linear;
  - room `tier` fields are never enforced (display only);
  - `TrialContent` still clamps spawner tier at 3, as intended.
- **Loot JSON across 128 tables:**
  - every `pocketdungeons:` reference resolves;
  - no cross-tier nesting;
  - every `custom_data.pocketdungeons.tier` matches its file;
  - no `random_sequence` collisions;
  - no tier 4 file names tier 3;
  - every `minecraft:trim` uses only `pattern` and `material`, and both are valid ids;
  - the gametest server loaded every table with no parse error (the "All 50 core loot tables verified present" check ran).
- **Stacked chest decor:** intentional. `TrialContent.placeCompletionChests` stands depth-bonus chests on top of the first spots, and that session's floor 4 paid 4 chests.

## 3. Deduplicated backlog

Status key: **V** done and verified live, **D** done but unverified (tests or reading only), **O** open, **X** contradicted by the code.

| # | Item | Source | Status | Notes |
|---|---|---|---|---|
| 1 | PD-98 kennel wolves spawn | BUGS, L9 | **X** | F1: pen too dark; the test checks blocks only |
| 2 | PD-98 blaze_cellar spawner found | BUGS, L9 | D | `situationCombatRoomsStampATrialSpawner` |
| 3 | PD-104 spur and sorting redstone | BUGS | O (worse) | F2: barred_vault and ominous_bargain unsolvable; the_altar has no redstone; sorting_floor has no east jigsaw |
| 4 | Station tutorial swallows Lemon questions | new | O | F3 |
| 5 | Station tutorial hint text and owner-only | new | O | F4 |
| 6 | Endless Mine tier cap 3 | new | O | F5 |
| 7 | Failed commit leaves a stale preview | PD-102 follow-up | O | F6; PD-102 itself = PD-97, confirmed |
| 8 | PD-99 rotation_lock | BUGS, L9 | D | gametest at four rotations |
| 9 | PD-100 sensor line held | BUGS, L9 | D | F9 overwrite caveat |
| 10 | PD-101 bench leaves held item | BUGS, L9 | D | gametest |
| 11 | PD-103 blacksmith scan | BUGS, L9 | D | gametest |
| 12 | PD-78 and PD-97 return paths (L1) | BUGS, LIVE_CHECKS | D | gametests; ladder climb owed live |
| 13 | PD-79 Lemon answers in time (L6) | BUGS, LIVE_CHECKS | D | still failed live 2026-10-01-2 (several unanswered); F3 makes the measurement noisy |
| 14 | PD-80 quiet waits (L7) | BUGS, LIVE_CHECKS | V | one more session to retire |
| 15 | PD-81 quit cost 1 | BUGS, handoff | D | config default changed; never seen live |
| 16 | PD-82 Lemon refuses items | BUGS, handoff | D | |
| 17 | PD-85 preview side window | BUGS, handoff | D | |
| 18 | PD-87 trial keys stack and work | BUGS, handoff | D | `TrialKeyLootTest` (16 key entries in 128 tables) |
| 19 | PD-88 flush plate | BUGS, handoff | D | |
| 20 | PD-89 crafted gear cap | BUGS | D | craft path capped |
| 21 | PD-89 drop durability question | handoff | answered | F12: `set_damage` 0.1 to 0.6 on a 96 cap |
| 22 | PD-90 `room_bias_hold` | BUGS, handoff | D | never exercised |
| 23 | PD-91 rising lava | BUGS | D | |
| 24 | PD-92 max-omen dup | BUGS | V | |
| 25 | PD-93 anomaly room stamps (L2) | BUGS, LIVE_CHECKS | D | |
| 26 | PD-94 no Boss Stones (L3) | BUGS, LIVE_CHECKS | V (caveat) | |
| 27 | PD-95 stack merge (L4) | BUGS, LIVE_CHECKS | D (partial) | |
| 28 | PD-96 bench discovery (L5) | BUGS, LIVE_CHECKS | V (caveat) | stranger discovery unproven |
| 29 | L8 torch drop half | LIVE_CHECKS | D | F13 home-room side effect |
| 30 | L8 foundry bottles | LIVE_CHECKS | V | |
| 31 | Hold-the-plate waves | handoff | D | |
| 32 | Thicket web lattice and light-shutoff | handoff | O (proposal) | |
| 33 | HOME title, gallery decor | handoff | D | |
| 34 | Netherite gating | handoff | D | tier 4 at level 20; scrap in bargain vault |
| 35 | Ominous roll replaces door 2 | design batch | D | F10: crimson door, docs, door 2 identity, no roll test |
| 36 | Random trims and trim pool | design batch | D, needs decision | F7, F8 |
| 37 | Lapis scarcity | handoff, AGENDA A3 | O | F11; Reroll costs lapis |
| 38 | Enchanting table craft-only | handoff | O, owner | still weight 3 in tiers 3 and 4 |
| 39 | Mining Efficiency dead | handoff | **X** | F7: copper trim bonus, not an enchant |
| 40 | Mending as paid lock-in | owner direction | O, owner | open: is a locked item exempt from salvage and reroll |
| 41 | Totem of undying use | handoff | O, owner | no loot table emits totems |
| 42 | Sand in foundry and brewing pools | handoff | O | |
| 43 | Blacksmith table discovery hint | handoff | partial | tutorial REROLL line covers it once F4 is fixed |
| 44 | Dungeon-only ender chest | handoff | O, owner | ender chest loot is dead weight now (F12) |
| 45 | Text-display HUD | handoff | O, owner | idea |
| 46 | Stacked chest decor | handoff | **X** | intentional depth-bonus stacking |
| 47 | Mob speed scaling reads unfair? | 2026-10-01-2 | O, owner | `applyMobScaleBonus` MOVEMENT_SPEED |
| 48 | Docs drift | new | O | F14 |

**Recommended order of work:**

1. Before committing: F3, F4, F5, and mark F1 and PD-104 open in `BUGS.md` and L9. All three are small edits.
2. F1 kennel light plus a real spawn test.
3. F2: rewire barred_vault, ominous_bargain and the_altar, add solve tests, and decide sorting_floor's jigsaws.
4. F6 failed-commit cleanup.
5. F9 held-line priority.
6. F11 lapis (with the owner).
7. The live session: L1, L2, L4, L6, L8, L9, PD-85, 87, 88, room_bias_hold.
8. Owner decisions in one sitting: 36 (trim bonuses, salvage of trims), 38, 40, 41, 44, 45, 47, and door 2's identity (F10). These need decisions, not code.

## 4. What I could not verify, and why

- **No live behaviour checked:** no server started (another agent owns the ports and `lemonwatch.mjs`). Every "D" row above rests on gametests or reading.
- **Rests only on a test:** PD-99, PD-101 and PD-103 (gametests), the sump and blaze_cellar return paths (gametests), PD-100 (`OmenBarTextTest` checks only `holdTicks`; nothing tests the repaint), StationTutorial ordering (unit test only; no test of the hooks or the nag).
- **Rests only on reading:** the ominous roll in `commitDoor` (no test), F3, F4, F5, F6, F9, F13, the tier-4 sweep.
- **Kennel light is hand-computed:** from the template's sea lantern positions, assuming plain propagation through air. Fence-gate and processor effects were not simulated. The wolf rule and the trial spawner's unconditional `checkSpawnRules` are confirmed from 26.2 bytecode.
- **Trims applying when rolled:** not observed. The table loads, which proves the component shape. That `set_components` with `random_chance` lands the trim rests on vanilla loot semantics. No test rolls a gear table with a fixed seed.
- **F6 depends on the lever path:** whether a second commit-lever pull right after a failure reaches `commitDoor` without passing `previewDoor` first. I traced the code, but did not reproduce it.
- **Committed batches only partly reviewed:** `f7d9cce` and `72edf56` (ordeals, salvage, the PD-92 to PD-96 batch) were reviewed only where the uncommitted work touches them.
- **Entrance cell roles:** whether the entrance cell can carry an encounter or loot role (F10 preview and commit mismatch) was not traced through `LayoutPlanner`.
- **Ender chest loot:** whether it can leave the dungeon pack and be used at home in survival was not checked against `InventorySwap`.

## 5. Suggested commit split

Exclude from every commit:

- churn under `run*/`, `logs/`, `build/`;
- `dist/*.jar` (commit only for a release);
- `nul`;
- `.claude/launch.json` (local);
- `server.bat`;
- the `valheim-*` folders, `VALHEIM-SERVER-SIDE.md` and `docs/PROMPT_valheim*`: other projects, so commit them separately;
- `docs/review-prompt-2026-10-01.md`, unless you want it kept.

`HandlerGameTest.java` and `SituationSpecs.java` carry hunks for several fixes. Stage them with `git add -p`, or fold commits 3 to 5 into one.

1. **Lemon harness (PD-79 and lemonwatch):**
   - `Lemon.java` (`fallbackDue`), `LemonGameTest`;
   - `tools/server/pdserver.mjs`, `wait-filter.mjs`, `wait-filter.test.mjs`, `lemonwatch.mjs`;
   - `docs/LEMON_AGENT.md` (add lemonwatch first, F14), `.claude/skills/playtest/SKILL.md`, `tools/lemon/build-pack.mjs`.
2. **Torches drop nothing and foundry bottles (L8):** `DungeonDrops.java`, the `LootTagGameTest` torch test, `gen_themed_content.py`. The basalt-foundry bottle JSON is mixed with the regear script output, so put it in commit 8 and say so.
3. **Two-story return paths and spawner scan (PD-97, PD-102, PD-98 blaze half):** `SituationSpecs` sump hunk, `sump.nbt`, the `TrialContent` scan, and the `sumpTemplateHasAReturnPath`, `everyMultiStoryRoomHasAReturnPath` and `situationCombatRoomsStampATrialSpawner` tests.
4. **rotation_lock wiring (PD-99):** `MechanismSpecs.java`, `rotation_lock.nbt`, `rotationLockSolvesAtEveryRotation`.
5. **Kennel pen (PD-98 kennel):** the `SituationSpecs` kennel hunk, `kennel_crossing.nbt`, the two kennel spawner configs, `kennelPenLetsWolvesSpawn`. Hold this one until F1 is fixed, or commit it with BUGS saying "still dark".
6. **Small fixes (PD-100, 101, 103):**
   - `OmenBar`, `OmenBarText` and `OmenBarTextTest`;
   - the `SalvageStation` open-path hunk and `SalvageGameTest`;
   - `BlacksmithNPC` and its test.
7. **Loot tiers 1 to 4 (code):** `KeystoneMath`, `LootTables`, `KeystoneMathTest`, plus the F5 `ZoneRules` fix.
8. **Loot tiers 1 to 4 (data):** every `loot_table/**` change and the 26 new tier-4 files. Describe the regear transform in the message, since the script is not in the repo.
9. **Ominous roll at commit:** `Keystone.java`, `Omen.java`, the `Instances.java` roll hunk, `OmenMathTest`, plus the door-2 crimson door and doc fixes from F10.
10. **Lemon station tutorial:** `StationTutorial.java`, `StationTutorialTest`, `build.gradle.kts`, the hooks in `RunLifecycle`, `SalvageStation` (`used` line), `RerollStation`, `GambleStation` and `CubeStation`, after F3 and F4.
11. **Docs:** `BUGS.md` (with the corrections from this review), `LIVE_CHECKS.md`, `AGENDA.md`, `BALANCE.md`, the two 2026-10-01 playtest notes, `handoff-2026-10-01-1.md`, this findings file, and a regenerated `tools/lemon/out/pack.md` last, so the pack matches the docs.
