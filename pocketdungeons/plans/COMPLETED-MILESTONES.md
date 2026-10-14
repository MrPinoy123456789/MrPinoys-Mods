# Completed Milestones

Recent October architecture passes are recorded in full below; the earlier M0 to M78 milestones are indexed in the table at the bottom. Append new sections; do not rewrite history.

## Dungeon structure (waves W1 to W7b, verified in W8)

Built on branch `dungeon-structure` from `docs/DUNGEON_STRUCTURE_DESIGN.md`
(decisions D1 to D22) and `docs/DECISIONS.md`. Not yet
playtested; the live checks owed are L24 onward in `docs/playtests/LIVE_CHECKS.md`
The as-built notes per wave
are sections 9 to 12 of the design document.

### What changed in one paragraph

A trip is now one dungeon, not a run of unrelated themed floors. The first
staging room offers three dungeons from the leader's unlocked acts; every later
staging room offers the out edges of the node just cleared. Each door is dealt a
keystone step of +1, +2 or +3 (seeded, resource dungeons deal 0), a side edge
costs echo shards paid by the member who commits it, and clearing the final node
finishes the dungeon: one echo shard per member, a themed vault, the dungeon's
diary page on a first finish, and the HOME lever as the only way on. Clearing a
capstone opens the next act. Inside a dungeon cell only resource nodes, player
placed blocks and a short list of soft mechanic blocks break.

### Data types

- `DungeonDef` / `DungeonDefs` (`data/pocketdungeons/dungeon/*.json`, 19 files):
  id, name, act 1 to 5, kind (`story`, `resource`, `capstone`, `endless`),
  mainTheme, lootBand (min, max tier), nodePalette, merchant, diary, deviation,
  nodes (id, name, layer, theme override, signature affix, room bias, light,
  final flag) and edges (from, to, cost). Validated by `PackValidator` (3 to 6
  layers, at most 3 edges out, acyclic, one entry node, every non final node has
  a free edge, borrowed themes from the same or an earlier act, one final node
  for a capstone, 1 to 3 layers for a resource dungeon). Reloaded with the other
  content by `ContentReload` (`DungeonDefs.publish`).
- `DungeonRoomMeta` gained `nodes`, `light` (`lit`, `dim`, `dark`), `corridor`,
  `biome`, `requiresLight`, `dungeons`, `acts`, `graphRole`, `borrowableBy`,
  `maxPerDungeon`. `RoomEligibility` reads them; `RoomEligibility.narrowToCapstone`
  makes the terminal cell of a capstone's final floor draw a `graphRole: capstone`
  room.
- `BagDefinition.hidden`: five kits are offered (Guard, Ranger, Mason, Sapper,
  Shepherd); innkeeper, magician, pilgrim and plumber stay in the pack but are not
  shown. A player who already holds one keeps it working.

### Flow

1. `Keystone.offers` builds the three offers. First door: `TripDoors.dealFirst`
   over `TripDoors.eligibleFirst` (unlocked acts, never `endless`), with
   `TripDoors.pendingCapstone` guaranteed on door 1 or 2. Later doors:
   `TripDoors.dealNext` over the current node's edges, spare doors repeating a
   branch. After act 2 opens, door 3 of the first staging room is the Endless Mine.
2. `RunLifecycle.previewDoor` / `commitDoor` check `PartyDecide`, then the side
   edge's shard cost against the committing member's own pack (`SideBranchPay`).
   `Instances.commitDoor` stamps the floor with the node's theme override, room
   bias and signature affix (`NodeStamper`), and records the trip on
   `IntervalState` (`dungeonId`, `nodeId`, `path`, `finished`).
3. `RunLifecycle.advanceFloor` banks the door's step, places the completion
   chests at `LootBands.floorTier`, and on a final node sets `finished` and runs
   `finishDungeon` (shard, vault, diary, `DungeonLog.addDungeonFinished`, and for a
   capstone `DungeonProgress.onCapstoneCleared`, which opens act N+1 or sets
   `campaignComplete` for act 5).
4. HOME banks `sum of steps / 3` as before (`IntervalBanking`); going home early
   banks steps and chests only. A max omen death fails the run through
   `Instances.failRunOmen`; the record, and with it the trip, is discarded.
5. Trip state is in memory only (`IntervalState`, replaced by
   `InstanceRecord.beginInterval`); a restart forgives the trip.

### New classes

- Trip and acts: `TripDoors`, `TripView`, `DungeonMapText`, `ActProgress`,
  `DungeonProgress`, `LootBands`, `PartyDecisions`, `PartyDecide`, `SideBranchPay`.
- Nodes, break rule, light: `BreakRule`, `DungeonLight`, `NodeStamper`,
  `RoomProtection` (updated), `RoomEligibility`.
- Resource dungeons and the Mine: `ResourceBiomeSpecs`, `CowPits`,
  `EndlessMineRules` (layers, seal, loot tier, deepest floor).
- Capstones: `CapstoneFights` (gate for the pad, Warden summon, floor end),
  `BroodWave`, `SculkOmen`, `CapstoneSpecs` (Spawner Dungeon, Ancient City),
  `WitherFight`, `WitherRules`, `HerobrineFight`, `HerobrineRules`,
  `CapstoneStart`, `NetherEndSpecs`, and `mixin/WitherBossMixin`.
- Content generators: `tools/gen_resource_content.py`, `tools/gen_w7b_content.py`.

### Persistence

`DungeonLog.Campaign` (a third codec part, every key optional so an old save
loads): `acts_unlocked`, `acts_migrated`, `campaign_complete`, `trip_counter`,
`decide_whitelist`, `decide_list`, `deepest_mine_floor`. `DungeonLog.Entry` gained
`dungeons_finished`. `currentTheme` and `depth` are marked superseded and their
codec keys kept. A save with no `acts_unlocked` loads with act 1 open and, once, a
keystone of 15 or more also opens acts 2 and 3 (`Campaign.migratedFor`).

### Config keys

- Added: `echoShardsPerFinish` (1), `finishVaultChests` (2), `capstoneStartOmen` (1,
  0 to 4).
- Retired (still read and saved so an old config loads, no longer used):
  `echoShardsPerInterval`, `echoShardFloorChance` (default now 0), `fuelPerFreeRun`,
  `greaterDoorMinLevel`, `door2MinLevel`, `fuelCostPerGreaterDoor` and the kit top
  up key. A side branch's cost is authored on its edge, so there is no
  `sideBranchShardCost` key.

### Verification (W8)

`clean compileJava test compileGametestJava`, every registered JavaExec test (now
including `capstoneRulesTest` and `cowPitsTest` in `tasks.test`), `runGameTest`
(168 required tests) and `dungeonIntegrationTest` pass. `dungeon admin validate` on
a live local server reports no findings and the content load logs 0 rejected for
19 dungeons, 19 themes, 85 rooms and 25 diary entries. W8 fixes: tier 3 spawner
configs of the four W6 and W7b dungeons pointed at vanilla trial chamber equipment
tables; the Cow Pits and Mineshaft chest tables capped stack size; the kennel
gametest origin grid collided with the bridge test once there were more than 18
themes; a content reload could leave a door preview planned for a node that the
reload removed or renamed (`FloorState.previewDoorKey`).

### Pending human checks

See `docs/playtests/LIVE_CHECKS.md` L24 onward.

## Simplification (steps 1 to 18, verified in step 19)

Built on branch `feature/simplification` from `docs/DECISIONS.md`. Goal: one progress
bar, one spending currency (emeralds), one risk meter (lives); every change
removes a rule or a screen line. Not yet playtested on this branch; the live
checks owed are L45 to L53 in `docs/playtests/LIVE_CHECKS.md`.

### What changed in one paragraph

Legacy `story` and `resource` kinds became ordinary `dungeon` data (no adapter).
Floors pay scrap on each clear; over-level floors pay emeralds instead. The
door board prices side branches in scrap, not echo shards, and the GO HOME
board reads scrap carried against what it comes to. Five bags remain (Guard,
Ranger, Sapper, Lumberjack, Innkeeper); the five cut bags sit behind the
`extra_bags` content module. Stations are Salvage, Reroll (enchanting table),
Home Vendor and Bag Chest with no level gates; Gamble, Blacksmith and Lock In
are gone. The Store is a tagged villager with real `MerchantOffer`s, journaled
by `MerchantTradeMixin`. The home librarian sells rolled gear tiers (act gated,
never tier IV), Mending for 64 emeralds, and buys surplus drops; stock rerolls
on each homecoming. Dungeon Storage is a 27 slot log-backed chest that opens
from any ender chest in a live instance's rooms and survives teardown and
failure. Mobs drop no gear in the dungeon; trial keys are refused at salvage
and redeem at floor clear (1 emerald, ominous 3). Hidden ore carries no copper;
sand pockets are in; diamond and netherite pickaxes are capped at 32 and 48
durability. Pocket2 and `/dungeon memento` are hidden from players. The
Herobrine Cube is unregistered (J8 stays design-only). The kit top-up, its
journal event and its config and zone knobs are deleted; `kit_baseline` is
pack-validation data only.

### Verification (step 19)

`gradlew test`, `runGameTest` (175 tests), `dungeonIntegrationTest` and
`gradlew build dist` all pass. `build` gates on `packValidationTest` and
`dungeonIntegrationTest`, both clean; `dungeon admin validate` on the booted
server reports no findings. The jar lands at `dist/MrPinoys_Pocket_Dungeons-0.1.0.jar`.
Old saves need no migration beyond defaults for new fields.

### Pending human checks

See `docs/playtests/LIVE_CHECKS.md` sections L45 to L53. Deployment to the
Kinetic test server is an owner step.

## Haul, the Astrolabe Room and the 2026-10-09 design pass (2026-10-07 to 2026-10-10)

Added by the 2026-10-10 freshness pass from `docs/reference/BUGS.md` (PD-161 to PD-197), the two decision records
(`docs/DECISIONS.md`) and the code.
Test counts were not re-run for this entry; each PD entry names its tests. Everything after the haul model was built
but not yet played; the owed checks are `docs/playtests/LIVE_CHECKS.md` L54 to L65.

- **Haul and Blood Doors.** The spendable scrap pool became a per-trip haul that banks into the compass at home or a
  finish (`ScrapMath`, `DungeonLog.Entry`, `RunLifecycle.bankHaul`); a failed dungeon banks `failHaulKeepPercent` (50).
  A side door costs 1 or 2 lives from the party's five (`DoorLives`), never the last. `cost` on an edge is rejected.
- **Astrolabe Room.** The first staging room of a trip shows one act's dungeons as a row of 1 by 2 doors (`HallLayout`,
  `HallOffers`, `HallRoom`): turn the astrolabe for the act, a sign, doormat and copper bulb per door, a 14 by 3 wall
  that is the preview window, a DESCEND lever that glints beside the selected door, and `Play again` on a finished
  dungeon. `hallEnabled` off restores three random doors; a repeat finish pays `repeatFinishEmeraldPercent` of the emeralds.
- **Scrap curve.** A level costs `scrapCostBase + floor(compass / scrapCostEvery)` (4 and 3); a floor pays its step plus
  `scrapPerAct` per act above the first plus `scrapFinalBonus`, and half (at least 1) below the compass.
- **Sidebar and effect caps.** `SidebarDisplay` shows floor, lives, spawners, haul and compass; poison, wither, slowness
  and mining fatigue are capped (`EffectCaps`).
- **Sculk.** Any room with a sensor or shrieker listens (`PressureSources`): a Heard meter (`sculkHeardMax` 4, Ancient
  City 2), a full meter answers with darkness and a Warden whelp (`Whelp`: 15 s, one at a time, melee only), a clean
  clear pays `sculkUnheardScrap`. The Hush Gallery replaced `sensor_gallery`; the Barred Vault is a vanilla vault
  (`SpurVault`) and `SpurToll` is retired.
- **Finales and uniforms.** `finale` in a dungeon's JSON ends its last floor in a wave (`FinaleWave`, `FinaleRules`),
  with a named elite from Act 2; `mobUniform` dresses Copper Works mobs (`MobUniforms`).
- **Restless and wolves.** Restless (`Restless`) replaced Feral. The Kennels is an Act 1 dungeon where every untamed
  wolf is hostile (`HostileWolves`, `KennelSpecs`: `kennel_run`, `guard_tower`, `alphas_den`); Wolf Hollow and the Lost
  Dog are the friendly wolves; `petCap` 3 (`PetCap`).
- **Endless Mine ore.** `hiddenOre` with `deepBlocks` and `deepEvery` (`DungeonDef.HiddenOre.atDepth`).
- **Lodestone menu.** Home: Start Dungeon, Manage Room, Inspect Compass, Manage Party, View Lobbies; Leave is last and
  asks first; per-owner bans (`RoomBans`) and party history (`RoomCompany`); empty lists are notices.
- **Leaving.** Leaving a floor in progress fails the dungeon for the leaver; leaving between floors cashes out; a host
  failing fails everyone; a failed dungeon returns the party to the Home room (`RunLifecycle.leaveSettle`,
  `settleLeaderLeft`, `Instances.failReturnsHome`; PD-191 to PD-193).
- **Dimension rules.** `DungeonWorldRules` with `TrialSpawnPlacementMixin` (trial spawners ignore natural spawn rules)
  and `FireBlockNoSpreadMixin` (fire never spreads) apply only in the dungeon dimension (PD-194, PD-195).
- **Keys and gear.** Vault keys last the dungeon and settle for emeralds when the haul banks (`redeemKeys` from
  `bankHaul`; PD-196); a node's `rewards` may promise `gear:<slot>:<tier>` (`PromisedGear`; PD-197).
- **Small.** The librarian and the Deepslate and Frostworks merchants buy lapis; trimmed armour can be scrapped; the
  floor history board says FAILED, QUIT or CLEARED; the mixin count is now 18, so the one-mixin budget is gone.

## Acts 1 and 2 roadmap, Endless Mine per act, Wager and Act 3/4 floor types (2026-10-11 to 2026-10-13)

Built from the Workshop 1 to 3 decisions and the Oct 11 to 13 decision records in `docs/DECISIONS.md`:

- **FinaleScript seam (`FinaleScript`, `FinaleContext`, `FinaleScripts.registerAll()`, `FinaleWave`):** One script class per dungeon finale with hooks (`onTriggered`, `spawnWave`, `onWaveSpawned`, `onTick`, `onMobDied`, `onHalfDead`, `onWon`, `onFloorEnded`).
- **Act 1 finales and mechanics:** `MineshaftFinale` (dark phase + cave spiders at half wave), `OssuaryFinale` (six sealed coffins cracking one by one), `KennelsFinale` (handler 5 s horn windup empowering the pack), `LushFinale` + `PotionDrip` (70% harmful splash potion drip on deeper floors, giant slime boss with doubled first split, plus swamp/witch/clay/mushroom/pond rooms), `InfestationFinale` + `CreepingBricks` (mossy -> stone -> infested brick ripening and burrowing Queen), and `BroodWave` (Spawner Dungeon capstone: four caged spawners each releasing an earlier Act 1 mechanic before the Brood Mother).
- **Act 2 finales and mechanics:** `CowPits` (3-floor run with the Milking Parlour and optional Specimen 0 boss on the Big Barn), `DeepslateFinale` (Silent Deep Hush meter calling Warden whelps), `CopperClock` + `CopperFinale` (20-minute dungeon furnace countdown slowed by clearing spawners, serpentine fire grates and water vats weakening the Foreman), and `FrostFinale` (Big Freeze freeze ticks and five campfire braziers).
- **Affixes and Wager (`Wager`, `WagerGear`, `PromisedGear`):** Explosive fuse 25 ticks; Silenced `n/3` action bar; Cooked floor-clear title line; Voided skips puzzle/ordeal cells; Overclocked par bonus (+1 scrap under 360 s); Loaded trapped cache ambush (`TrappedCache`); Restless widened to all mobs (`Restless`, `RestlessRules`); new `blackout` and `blood` (costs 1 life at commit) affixes. Every affix carries a `wager` (0 to 3); the door board and floor title show `Loot n%` (`100 + 50 * wager`), which scales reward barrel rolls (`round((base + depth) * n / 100)`, shown as `N rolls`) and adds one seeded extra gear/material piece into the copper chest when above 100%.
- **Endless Mine per act (`EndlessMineRules`, `MineGuardians`, `MineDepth`, `ActProgress`):** Act 1 `endless_mine` and Act 2 `endless_mine_deep` (the Deep Mine), with a guardian every 3rd floor (floors 3, 6, 9, ...) paying guardian loot tables `act<N>_a/b/c.json` and repeating with rising hardness. Clearing floor 3 (first guardian) counts toward opening the next act.
- **Haunts and Act 3/4 floor objectives (`Haunts`, `HauntRules`, `FloorObjectives`, `FloorObjectiveRules`, `HeistObjective`, `ClaimObjective`, `HauntObjective`):** Floor-wide stalkers with a 3-floor stage ladder (floor 1 sighting, floor 2 near miss, floor 3+ strikes): `the_counted` in Ossuary (`STALK_WHEN_UNSEEN`) and `the_keeper` in Lighthouse Below (`HUNT`). Three new objective-floor dungeons: `sunken_treasury` (Act 3 `heist`: dormant spawners until the Vault Core is channelled for 5 s, waking the spawners, crew and strongboxes), `lighthouse_below` (Act 3 `haunt`: charge two beacons to open the pad and banish the invulnerable Keeper), and `gilded_seam` (Act 4 `claim`: right-click gilded veins to meet the quota while rising Tremor sends hoglin packs at 4 and The Assayer at 6, with extra veins paying bonus barrel rolls).

## M0 to M78 index

| Milestone | Summary |
|---|---|
| M0: Entry fee and safety | a third party can write a datapack against this mod without reading |
| M1: Themes foundation | 14 templates × N processor lists instead of 140 hand-authored |
| M2: The room | the selector room becomes a persistent, owned, decoratable room, and |
| M3: The calling card | other people can stand in your room. The multiplayer thesis (§2.1). |
| M4: Affixes | the ladder gets texture. Affixes stack by level, seed from the key, |
| M5: Wolves and Feral | the first kiss/curse proven end to end, and the mod's first |
| M6: Supply | "could a player progress without ever leaving?" stops being |
| M7: Recipes | server folklore. Someone comes back with *New Dungeon Discovered* |
| M9: Refactor and cleanup | the codebase can absorb the door/ladder reframe without the reframe |
| M10: Ladder reframe | the keystone level gates access; difficulty lives on the map. The |
| M11: Adventures | replace the recipe system's backward-looking tail match with a |
| Fixed alongside M11: a pre-existing boot crash in `LootTables` | Not part of the D3 plan; found while trying to get M11 live-verified, and |
| M12: Two-tier doors and fuel | door 1 becomes the free, fuel-producing, non-depleting tier; doors |
| M13: Gear loot pool | author tiered armour and weapon loot so the gear-touching sinks |
| M14: Gear reroll station | a room station rerolls one enchantment on a piece of gear, the |
| M15: Armor trims | dungeon-found armor trim templates and materials, consumed |
| M16: Gear gamble station | a room station that spends emeralds for one random piece of gear in |
| M17: The Herobrine Cube | a ritual that consumes a rare item once, permanently remembers one |
| M18: Room shell pass | the room's shell (floor, walls, ceiling, lamps) becomes immutable to |
| M19: Physical door selection | the three selector doors, a `text_display` screen above them, copper |
| M20: Visiting rework: lobby directory, no calling card | the hand-traded calling card is replaced by a lobby directory: a |
| M21: UX consolidation: one lodestone, one menu | five distinct lodestone interactions collapse into one right-click |
| M22: Sound pass | a `Chime.java` class following the spiritwolves/wondrous pattern, |
| M18-M22 review fixes | Five findings from the M18-M22 review, one commit each, all green on |
| M23: Room template editor (buildroom / saveroom) | a dev tool for hand-authoring room templates in Minecraft. An |
| M24: Room shells and prestige | players discover and unlock alternate shell materials (sandstone, |
| M25: Pocket2 Dungeon | a dungeon within a dungeon. During a run, the player finds a rare |
| M26: Lore delivery | the lore (Alex's diaries, Alex's Room, the keystone's own recovery- |
| M27: Extra features | three small features enhancing the existing loop without being |
| M28: Themed mob spawners | dungeon themes control which mobs their trial spawners roll, not |
| M29: No-backwards propagation | dungeons never wrap around behind the player's entrance room. If |
| M30: Connector variations | varied connector patterns at door openings (wide door, double |
| M31: Dungeon shell protection | during an active run, the dungeon cells outside any player |
| M32: Tutorial screen and engine label | the engine screen's title reads "ECHO SHARDS" instead of |
| M33: Guided tasks via tracker screen | ten sequential guided tasks teaching the core loops (select a |
| M34: Weekly bounties for party leaders | three weekly bounties per dungeon host (instance owner), seeded |
| M35: Anomaly rooms | rarely, a themed run contains one room that does not belong |
| M36: Critical bug fixes from the audit | close every finding from the 2026-08-31 six-pass static audit |
| M37: High-severity bug fixes from the audit | close the audit's economy and progression correctness bugs |
| M38: Medium-severity bug fixes from the audit | close the audit's gate-bypass, task-tracking, and |
| M39: Low-severity bug fixes and config validation gaps from the audit | close the remaining small correctness bugs and the three |
| M40: Dead code and stale-shipped-defaults cleanup | remove verified-dead code, the reward hall and selector room |
| M41: Documentation drift correction | make every top-level and reference doc agree with what is |
| M42: Half-built feature content and design work | implement the six half-built findings the mod owner decided to |
| M43: Refactor backlog | land the eight structural findings from the audit follow-up plan |
| M44: Test coverage for world-mutating and economy classes | add coverage for the highest-risk classes the audit found |
| M61: Vertical room span (spec 13) | A room may declare `spanY: 2` to own the 16 x 16 x 9 volume directly beneath |
| M62: Establish executable acceptance | M62 proves runner plumbing, not custody or fault coverage. Two dedicated |
| M63: make custody and teardown recoverable | Goal: no tested transition silently loses or duplicates a player's room, |
| M64: Prove rooms as played, not merely selected | Goal: every offered situation has a physically reachable answer and an |
| M65: one floor lifecycle, one silent homecoming | Goal: each floor advances once; the safe visit banks once; the last door |
| M66: Cube Recipe Reliability | A consumed catalyst changes exactly the floor previewed, or remains |
| M67: Close the live pass and teach only the verbs | M67 is a human gate milestone. The code-side work is complete; the human |
| M68: Namespaced versioned content contracts | Two packs with the same local names coexist, and reload never mixes |
| M69: Data-driven affixes | A new namespaced affix works without adding a Java enum constant. The |
| M70: Author roles and bags, keep geometry honest | An author adds a supply-room role and a bag without modifying a role |
| M71: Data recipes with a discovery floor | A pack author adds a real recipe; a player has a dependable first |
| M72: the first external pack | M72 closes the loop for a non-developer author: author, validate, distribute |
| M73: six new identities from the existing geometry | The composition space doubles without a single new `.nbt` template. Six |
| M74: six situation rooms from private lower stories | Six new situation rooms ship using private lower stories rather than a new |
| M75: whitelist-gated private visits, run mementos, retuned bounties | Players can show a physical record, pass a private address and learn from |
| M76: publish the operating envelope | An operator knows how many simultaneous floors, previews and visits this |
| M78: the Endless Mine, bounded in memory | M78 unholds the one deferred "rule-breaking dungeon" item from `ROADMAP.md` |
