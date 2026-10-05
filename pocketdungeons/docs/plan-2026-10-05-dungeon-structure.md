# Build plan 2026-10-05: dungeon structure

Builds `docs/DUNGEON_STRUCTURE_DESIGN.md` (decisions D1 to D22, D6a, D16a) in
eight sequential waves. Each wave is one builder agent working in this tree;
the next wave starts only after the previous one compiles and its tests pass.
Follow `CONVENTIONS.md` and the root `CLAUDE.md` (no em dashes, no double
hyphens as punctuation). Do not commit; the owner commits.

## Builder defaults for the open questions

The design left these open. Builders use these defaults so the waves agree;
each is a config value or a data field so it can be changed after playtest.

| Open question | Default |
|---|---|
| Node: authored or generated | Authored in the dungeon JSON (name, optional theme override, optional signature affix, room bias list, optional room count). A node without a room bias draws from the dungeon's room pool. |
| Steps on a final or capstone floor | Random like any floor. |
| Side branch cost | 1 shard (config `sideBranchShardCost` default, overridable per edge). |
| Capstone mobs | Vanilla mobs only, server-side. Spawner Dungeon: classic spawners plus a brood wave of cave spiders and skeletons. Ancient City: every sculk sensor and shrieker raises omen; at omen 4 a Warden is summoned (a real vanilla Warden, the one place it may spawn) and the floor must be finished by reaching the terminal, not by killing it. Wither: a summoned Wither with explosion block damage blocked as today (`ServerExplosionMixin`). Herobrine: a named, buffed, invisible-name-tag zombie "Steve" in netherite with phases; at low player health a scripted rescue: an "Alex" villager-shaped NPC (same tech as `StoreNPC`) appears, he teleports away with a message, the floor completes. |
| Endless Mine height | Keep the y 0 to 256 dimension; each floor is restamped at working height as `ZONES_SPEC.md` 3.2 says. |
| Darkness and wolves | Rooms with `requiresLight: true` are never stamped dark; the Feral affix is not dealt on a floor whose node is `dark`. |
| Cow Pits finite rule | No wheat, seeds, carrots or potatoes on the floor and cows spawned as adults with breeding blocked (age lock); 6 to 10 cows per floor; pays beef and leather. Act 2. |
| Five kits | Guard, Ranger, Mason, Sapper, Shepherd (the most used and most distinct). The other four bag files stay in the pack but are hidden from the bag chest (a `hidden: true` field), not deleted. |
| Diary pages | One short page per dungeon (about 15), written in Alex's voice per `LORE-DIARIES.md`, reusing the existing 7 where they fit. |
| Migration | `currentTheme` and `depth` in `DungeonLog` are marked superseded (codec kept). Every existing player starts with Act 1 unlocked; a player with keystone 15 or more also gets Acts 2 and 3 unlocked once, so live testers are not reset to the start. |
| Loot band per act | Act 1 tiers 1 to 2, Act 2 tiers 2 to 3, Act 3 tier 3, Act 4 tiers 3 to 4, Act 5 tier 4. The keystone tier is clamped into the band. |
| Node and tool tiers | Coal, copper, stone: stone pickaxe. Iron, lapis: stone. Gold, redstone, diamond: iron. Ancient debris: diamond. Logs: any axe. Vanilla tiers, enforced by the existing correct-tool check. |
| Party whitelist | Off by default; `/dungeon party decide add|remove|list <player>` and `/dungeon party decide whitelist on|off`. |

## Waves

### W1 Foundation (Stage 0 and the data model)

- Remove the per-floor shard roll (`echoShardFloorChance` default 0 and the
  roll removed from `RunLifecycle`).
- New `dungeon` data type (`data/pocketdungeons/dungeon/*.json`) and loader
  (`DungeonDefs`, same reload pattern as `AdventureGraphs`): id, name, act,
  kind (`story`, `resource`, `capstone`, `endless`), mainTheme, lootBand,
  nodePalette, merchant, diary, deviation, nodes (id, name, layer, theme
  override, signature affix, room bias, final flag), edges (from, to, cost).
- Pack validator rules: 3 to 6 layers; at most 3 edges out; acyclic; one entry
  node on layer 1; every non-final node has at least one free edge; entry and
  final nodes use the main theme; borrowed themes are from the same or an
  earlier act; capstone dungeons have exactly one final node.
- One dungeon JSON per existing theme (Stage 1), act assigned per the design
  table, 3 to 5 layers, nodes named for the theme.
- Unit tests for the parser and validator (plain `javac` style tests, as
  `AdventureGraphTest`).

### W2 Trip flow (Stage 1 mechanics)

- Trip state on the instance: dungeon id, node id, path taken.
- The first door of a trip offers dungeons (three, from unlocked acts); later
  doors are the current node's edges. Seeded random steps +1/+2/+3 per door;
  spare doors repeat a branch at the other steps. Side edges cost shards.
- Remove Greater doors, `door2MinLevel`, `greaterDoorMinLevel`, the free
  door's shard payout and level gating of doors (config keys kept, marked
  unused).
- Clearing a final node finishes the dungeon: 1 echo shard per member, a
  themed vault (extra chests at the act's top tier), first-clear diary page;
  the trip then banks. Going home early banks steps and chests only.
- Node theme override (D6a) feeds the floor's processors and spawners.
- Staging room map: the dungeon graph as a written screen (current node, the
  final node, edge costs, reachable nodes), plus door labels (floor name, step,
  affix, loot tier, cost). Later steps are not shown.
- Journal events: `dungeon_chosen`, `node_entered`, `edge_taken`,
  `dungeon_finished`.

### W3 Acts, loot bands and party

- `DungeonLog` per player: acts unlocked, dungeons cleared, diary pages.
  Migration per the defaults.
- Capstone final node clear unlocks the next act for every member present.
- Act loot band clamps the loot tier (D10).
- Party: the leader's unlocked acts set the offers; any member may pull doors,
  choose branches and pull HOME unless the leader's decide whitelist is on.

### W4 Resource nodes, break rule, light

- Room metadata: `nodes`, `light`, `corridor`, `biome`, `requiresLight`,
  `dungeons`, `acts`, `graphRole`, `borrowableBy` (parsed, validated).
- Break rule: inside a dungeon cell only node blocks (the dungeon's
  `nodePalette` blocks placed by a room's `nodes`, tracked like player-placed
  blocks) and the player's own blocks break. Update `RoomProtection` and the
  shell protection tests.
- Light: `dark` rooms strip torches and lanterns at stamp time (processor or
  post-stamp pass); validator warns on dark plus `requiresLight`.
- Loot: fewer torches, more planks and coal across chest tables.

### W5 Kits

- Five kits shown (defaults), others `hidden`. Kit granted once. Remove the
  top-up at bank and the bag chest refill (`KitTopUp`, kit station), keeping
  codec fields per the migration rule. Update the go-home screen text.

### W6 Resource dungeons, Lush Caves, Endless Mine

- Mineshaft (Act 1, resource): new room specs (rail tunnels, support beams,
  ore seams as nodes), dungeon JSON, steps forced to +0, no finish shard.
- Cow Pits (Act 2, resource): room specs and the finite cow rule.
- Rootworks rebuilt as Lush Caves (Act 1): lush cave room specs with oak log
  and azalea nodes, moss, glow berries for light.
- Narrow and dark pass on Act 1 rooms (corridor walls inside cells).
- Endless Mine as a mode: opens after Act 1; depth layers sealed until their
  act; deepest floor recorded on the history board; the Cube recipe retired.
- Regenerate templates with a local server: `gradlew runServer` in the
  background, then `python tools/rcon.py "dungeon admin gentemplates <room>"`.

### W7 Capstones and the End

- Spawner Dungeon (Act 1), Ancient City (Act 2), Drowned Vault stays Act 3,
  Wither (Act 4), the End dungeon and the Herobrine fight with Alex's rescue
  (Act 5), per the defaults. Room specs, dungeon JSONs, the fight handlers.
- Warped Forest room for the Nether act (warped stem nodes).

### W8 Verification and docs

- `compileJava`, `test`, every registered JavaExec test task,
  `compileGametestJava`, the integration test if the machine allows.
- Template regeneration complete; pack validator clean.
- Docs: `plans/COMPLETED-MILESTONES.md` (append), `docs/reference/LIVE_TEST_PASS.md`
  (append a section of live checks), `AGENDA.md` live checks for the four
  Stage 1 hypotheses.

## Report each wave returns

Files changed, what was built, tests and tasks run with their result, any
default it had to change and why, and anything left undone.

## Outcome

Built on branch `dungeon-structure`, verified in W8 (2026-10-05). As built notes
are sections 9 to 12 of `docs/DUNGEON_STRUCTURE_DESIGN.md`; not yet playtested.

- W1 Foundation: built. `DungeonDef` and `DungeonDefs`, the validator rules, one
  dungeon JSON per theme, the per-floor shard roll removed (`echoShardFloorChance`
  default 0, key kept).
- W2 Trip flow: built. `TripDoors`, `TripView`, `DungeonMapText`, `finishDungeon`.
  Deviation: a finished dungeon settles through the ordinary HOME lever (only HOME
  is offered) rather than banking by itself.
- W3 Acts, loot bands, party: built. `ActProgress`, `DungeonProgress`, `LootBands`,
  `PartyDecisions` and `PartyDecide`; the migration rule as in the defaults.
- W4 Nodes, break rule, light: built. `BreakRule`, `DungeonLight`, `NodeStamper`.
  Deviation: soft mechanic blocks (pots, cobweb, infested blocks, plugs) stay
  breakable besides nodes and player blocks; the room selector reads `dungeons`,
  `acts`, `graphRole` and `borrowableBy` only from W6 and W7a on.
- W5 Kits and fixes: built. `BagDefinition.hidden` (five kits), `SideBranchPay`,
  `RoomEligibility`. The kit top up and bag chest refill are retired, their codec
  fields kept.
- W6 Resource dungeons, Lush Caves, Endless Mine: built. Deviation: the Endless Mine
  takes door 3 of the first staging room once act 2 is open (no fourth door slot),
  and the narrow pass was done by new dedicated Act 1 rooms, not by editing the
  shared generic halls.
- W7a Spawner Dungeon and Ancient City: built. Deviation: a capstone of an open act
  is guaranteed on door 1 or 2 of the first staging room (`pendingCapstone`) so the
  Mine's door 3 never hides it.
- W7b Warped and crimson forest, Wither, the End, Herobrine: built. Deviation: a
  second mixin, `WitherBossMixin`, stops the Wither breaking blocks (the one mixin
  budget in `CONVENTIONS.md` was already well past in this tree); the capstone start
  omen (D16a) is a config key `capstoneStartOmen`.
- W8 Verification and docs: done. All tests, the game tests, the integration test
  and the pack validator pass; `capstoneRulesTest` and `cowPitsTest` are wired into
  `tasks.test`; four defects fixed (spawner equipment tables, stack size caps in two
  chest table sets, a gametest origin collision, a stale door preview after a
  content reload). Template regeneration is complete for every shipped room.
