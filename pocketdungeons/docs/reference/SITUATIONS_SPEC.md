# Situations and Bags: a run made of problems, not corridors

> Design spec. Status: proposal, nothing here is built. Companion to
> `VISION.md` (what the mod is for) and `DOOR_LADDER_BRAINSTORM.md` (the
> keystone economy). This document covers what goes inside a run's cells,
> what the player carries into them, and what supplies pressure once the
> global clock is gone.

---

## 0. What playtesting established

The run is fun as an improvement over vanilla trial chambers. The room works
as the ship (Lethal Company) or the elevator (Kletka): a place to stand
between runs, not a thing to fix. The Herobrine Cube is the run controller
and stays.

One thing about the room does change. Today the room and the selection
doors are one cell: the player stands in their stash room, picks a door,
and the dungeon generates behind them. With scarcity (section 3) and the
continuous loop (section 12), that design leaks. The player can dip into
their stash mid-run by walking back, and the room has to stay loaded the
whole time. The spec splits the room into two roles (section 12): a
**safe room** that holds stashes, item upgrades, and stations, and is
only loaded during safe visits; and a **staging room** that holds the
selection doors, is created fresh at the end of every dungeon floor
(like Barony's hatch), and is the only way to select the next floor or
return to the safe room. The safe room accumulates; the staging room is
transient.

What is missing is variety and decisions. A run today is a sequence of
`encounter`, `loot` and `corridor` cells; the only in-run decision is
whether to fight the spawner now or later, and the only pressure is a
boss-bar clock over the whole run. This spec replaces both:

| Today | Proposed |
|---|---|
| Cells are fights, chests or hallways | Cells are **situations**: a problem with several Minecraft solutions |
| The player brings whatever they own | The player carries a **bag**: a small, deliberate, scarce kit |
| One clock over the run | **Local pressure** per room, plus a run-level escalation that punishes dwelling |
| Completion gated by spawner clear percentage | Completion gated by each room's own **gate**, physical where possible |
| A run is one small dungeon, then the room | A **continuous loop**: safe room, staging room, dungeon, staging room, dungeon, ... staging room, safe room (section 12) |

The principle, borrowed from the expedition brainstorm and applied here:

> **The generator creates the situation. The player discovers the solution.**

A situation never ships with an intended answer. It ships with a set of
things that happen to work, and one slow answer that always works.

**The three-way test.** Every hazard in a situation should pass at least
two of these three checks: it is a hazard to the player, it is a weapon
the player can turn on mobs, and it is a resource the player can carry
forward. A lava channel that only burns the player is a one-way hazard.
A lava channel that also kills mobs chasing the player and produces
obsidian the player can mine and use is a situation. The catalogue in
section 4 is written with this test in mind; future templates should be
checked against it before they ship. The pattern is borrowed from
Barony, where traps hit enemies, dropped items disable spike traps, and
arrow traps yield quivers if disarmed. The lesson: a hazard that is only
a hazard is a chore; a hazard that is also a tool is a decision.

---

## 1. Vocabulary

- **Situation**: the problem a cell poses. Authored as a template plus a
  `content` id. Replaces the idea that a cell is "an encounter" or "loot".
- **Bag**: the inventory the player has inside the run. Small, capped, and
  the same for everyone who picked the same door.
- **Tool**: any item that changes how a situation is solved. Water bucket,
  TNT, shears, a gold boot, sixteen snowballs. Never a custom item.
- **Gate**: the condition that lets the player leave the cell forward. An
  iron door on the exit wall, a spawner going to cooldown, a plate held.
- **Slow path**: the tool-free way through a situation. Always exists,
  always costs more time, often costs omen (section 5).
- **Pressure**: whatever makes the slow path unattractive. Local (this room
  is filling with lava) or run-level (the dungeon is waking up).
- **Provides / requires**: a room may drop a tool (`provides`) or need one
  (`requires`). The selector guarantees every `requires` on the critical
  path is preceded by a matching `provides` or covered by the bag.

---

## 2. Engine constraints this spec lives inside

Taken from `RoomGeometry`, `INTEGRATION.md` and `RoomContent`:

- A cell is **16 x 16**, walls **5 high**, ceiling at Y=6. Interior is
  14 x 14 x 5. There is no vertical traversal; situations are horizontal.
- Doorways are **2 wide, 3 tall, at floor level**, on canonical slots
  (x or z = 7..8) of a cell edge. A room's door mask must match the cell
  exactly.
- **Backtracking is allowed.** M29 ("no-backwards propagation") is a
  *layout* rule in `LayoutGraphGenerator.validate`: no cell may sit
  behind the entrance axis, so the dungeon never wraps around the player
  room. It does not seal doors behind the player, and nothing in the code
  does. A cleared cell stays walkable and is the player's safe ground
  (section 12.3). An earlier draft of this spec read M29 as "forward
  only, no reverse"; that was wrong, and the rooms that want one-way
  movement (Collapsing Bridge) have to build it with pistons.
- The generator already produces a **graph**, not a corridor: a critical
  path of `targetLength` cells, branches of depth 1 to 3 hung off it at
  `branchProbability`, and optional loops at `loopProbability`. Bigger
  floors are a parameter change, not a new generator.
- Roles are a Java `switch` (`entrance`, `exit`, `encounter`, `loot`,
  `corridor`). The `content` field on `DungeonRoomMeta` is already parsed
  and already dispatched in `RoomContent.apply` (the `store` anomaly). This
  spec uses `content` as the situation id and leaves roles alone.
- Trial spawner configs accept any entity with NBT. Chest and vault loot
  tables are plain vanilla tables and can set components.
- Server-side only. No custom blocks, items or client mod. Redstone, water,
  lava, mob behaviour and item components are the entire toolbox.
- The dimension has no skylight: daylight sensors are dead, everything else
  works.

---

## 3. The bag

### 3.1 Why the bag comes first

Every traversal situation in section 4 is trivial for a player carrying
five stacks of cobblestone from their survival world. Scarcity is not a
tuning value; it is the precondition for any of this being a decision.
Two mechanisms, use both:

**Stack caps inside the run.** Bag items and in-run loot carry
`minecraft:max_stack_size` and, for tools, `minecraft:max_damage`
components set by loot functions. A stack of blocks is 8. A pickaxe has 12
uses. This is data, not Java, and it makes "you found more blocks" a real
event.

**Stash and swap.** Detailed in section 11 below. The short version: the
survival inventory is backed up the moment the player enters
`pocketdungeons:void` and restored the moment they leave it, and this is
enforced as a continuous invariant on every server tick, not as a pair of
entry/exit events that can desync. Nobody loses survival items, which
matters for the current test group playing on a live survival world.

### 3.2 Bag archetypes

Each bag is a loot table (`pocketdungeons:bags/<id>`) so pack authors can
add their own. Contents below are the shipped defaults. The right-hand
column is design intent and is never shown to players.

| Bag | Contents | Quietly solves |
|---|---|---|
| **Mason** | 2 x 8 cobblestone, stone pickaxe (12 uses), 4 torches | Chasm, Infested Wall, blocking sensors, pillaring over snow |
| **Plumber** | water bucket, lava bucket, 4 bread | Chasm (obsidian), Thicket (fire), Endermen, Blaze Loft, Flooded Hall (air pocket via source removal) |
| **Sapper** | 3 TNT, flint and steel, 8 bread | Pot Room, Infested Wall, mob packs, the soft wall in any room that has one |
| **Magician** | 3 ender pearls, 4 wind charges, 4 bread | Chasm, Gallery (wind charges hit targets), Deep Dark Landing, skipping a Bazaar entirely |
| **Ranger** | bow, 12 arrows, spyglass, 4 bread | Gallery, ledge archers, killing the plate-holder mob from range |
| **Shepherd** | 4 leads, 8 bones, 4 bread | Plate Pair (drag a mob), Feral wolves, Creeper Kennel steering |
| **Innkeeper** | milk bucket, golden apple, 8 bread, 4 torches | Elder's Chamber, Wither Loft, Bogged marsh poison |
| **Pilgrim** | 8 bread | Nothing. Every tool must come from a room. The hardest bag and the one that most tests the generator. |

Rules for authoring a bag:

1. Every bag includes food. Hunger is not the puzzle. This rule exists
   because Barony's hunger system is its most-criticised mechanic: a
   scarcity that generates chores (eat, find food, eat) instead of
   decisions. Every scarce resource in a bag should be tested the same
   way. If running low on it creates a choice (use the last TNT here or
   save it), it earns its slot. If it creates a chore (eat bread every 30
   seconds), it does not.
2. A bag never contains armour or a weapon better than stone. Combat is
   solved with the room, not the kit (Ranger's bow is a puzzle tool that
   happens to shoot skeletons).
3. A bag must not solve every situation in the catalogue. Pilgrim solves
   none; the others should each solve roughly a third.
4. No bag is balanced against another. Everyone at the same door has the
   same one.

### 3.3 How a bag is chosen

Bags are a fourth axis on a door alongside level, affix and theme. The door
dialog names the bag, and its contents are visible in the door's item
frames before the player commits (three frames per door, one per headline
item). This is the only pre-run information the player gets.

The Herobrine Cube can override a door's bag (section 7).

### 3.4 In-run loot is tools, not gear

Vaults and chests inside a run stop dropping armour and weapons as their
primary output. They drop **tools for later rooms**: another 8 blocks, a
second water bucket, 16 snowballs, a gold boot, a lead, a trial key. Gear
moves to the completion payout and the Cube. The consequence is that every
chest matters *now*, and the run pays forward into itself.

Guaranteed floors (`VISION.md` 3.7.4) stay: food and torches every chest.

---

## 4. Situation catalogue

Format for each entry:

- **See**: what the player sees on entering.
- **Works**: tools and knowledge that solve it quickly. Never exhaustive
  on purpose.
- **Slow path**: the always-available answer.
- **Gate**: how the exit opens.
- **Pressure**: what makes dawdling cost.
- **Build**: what it takes. `template` means a `.nbt` plus a
  `dungeon_room` JSON; `content` means a new branch in `RoomContent.apply`
  (or a successor dispatcher); `spawner` means a `trial_spawner` config.

Situations carry a `tier` (1 to 3) matching `KeystoneMath.lootTier`, and
optional `provides` / `requires` lists (section 6).

Two rules apply to every entry, added after section 12 reframed the run
as a walkable floor:

- **Readable from the doorway.** The situation must be visible before
  the player steps in: the spawner, the lava, the plate, the bars. If the
  template cannot show it through the 2 x 3 doorway, it adds an iron-bar
  window at eye height in the shared wall. The decision to enter is
  always informed. Barred Vault already does this; it becomes the norm.
  Iron bars and glass are the two materials used for visibility in
  shared walls:
  - **Iron bars** for situations where the player should see in but
    mobs, items, or hazards should not pass through. A skeleton
    gallery, a barred vault, a pit with spiders. Bars are see-through
    but block movement and projectiles.
  - **Glass** (regular or tinted) for situations where the player
    should see the full room but the room's contents (water, lava,
    gas, light) must be contained. A flooded room, a gas chamber, a
    dark room where light leak would spoil it. Tinted glass also
    blocks light, so a preview into a dark room stays dark.
  Both materials are unbreakable in dungeon walls (bedrock-backed or
  replaced with bedrock-variants via the existing `BedrockEnvelope`
  mechanism). A template chooses bars or glass based on what it needs
  to contain, not on aesthetics.
- **Open or gated.** An `open` cell's exits are reachable without
  engaging its content; the content is the reward for engaging. A
  `gated` cell must be solved to pass. Most cells on a floor are open.
  Gated cells sit at chokepoints the selector chooses (section 6.1). A
  player who runs past every open cell reaches the gated one with an
  empty bag. That is the trade.

### 4.1 Traversal

**Chasm** (tier 1)
- See: a 6-wide lava channel across the room. Exit door on the far side.
  A one-block ledge runs along one wall at floor level, half of it under
  a dripping lava fall.
- Works: 8 blocks bridge it. Water on the lava makes obsidian or cobble,
  a bridge for free. A pearl. A wind charge under your own feet at the
  edge. A boat does not (no water).
- Slow path: the ledge. Walkable, hot, one mistake is a swim.
- Gate: none. Physical.
- Pressure: none needed; the lava is the pressure.
- Build: template only.

**Flooded Hall** (tier 1)
- See: the room is full of water to the ceiling from two blocks past the
  door. Exit door underwater on the far wall. Two drowned inside. The
  shared wall has a glass window at eye height so the player sees the
  flood before stepping in. Glass, not bars, because water must be
  contained.
- Works: a door placed underwater is an air pocket. Removing the water
  source (it is one source block in the ceiling centre, findable) drains it
  over time. A turtle helmet or water breathing, if you have one.
- Slow path: swim it. Fourteen blocks is within one breath if you do not
  stop to fight.
- Gate: none.
- Pressure: breath.
- Build: template only. Drowned via `spawnMobs`, not a trial spawner, so
  the room is not an `encounter`.

**Powder Snow Field** (tier 2)
- See: the floor from wall to wall is powder snow, two deep, with stone
  islands every four blocks.
- Works: leather boots walk on it. A bucket scoops a path (and now you
  hold powder snow, which is a tool for a later Bazaar). Blocks placed on
  the islands make a bridge.
- Slow path: wade and hop islands. Freezing damage starts at seven
  seconds.
- Gate: none.
- Pressure: freezing.
- Build: template only.

**Thicket** (tier 1)
- See: cobwebs floor to ceiling. A cave spider spawner in the middle.
- Works: shears, a sword, fire (flint and steel, lava bucket, fire charge).
  Fire also burns the spiders.
- Slow path: punch through. Slow and you get bitten.
- Gate: none.
- Pressure: poison.
- Build: template plus a `spawner` config for cave spiders.

**Ice Run** (tier 2)
- See: a corridor of blue ice with a breeze on a ledge either side.
- Works: a boat from a pot in the previous room crosses it in three
  seconds, faster than the breeze can knock you. Sprinting is nearly as
  good. Blocks placed on the ice kill the slide.
- Slow path: walk it and take the hits.
- Gate: none.
- Pressure: knockback into the wall spikes (pointed dripstone on the
  walls).
- Build: template plus `spawner` for breeze. `provides: boat` on the
  previous cell (section 6).

### 4.2 Mechanism

**Frame Lock** (tier 1)
- See: iron door on the exit wall. An empty item frame beside it, a
  comparator behind. Four decorated pots in the corners.
- Works: one pot contains the key item (a specific tool the theme names:
  a copper ingot in deepslate, a prismarine shard in prismarine). Frame
  it, door opens. Players who read the theme go to the right pot first.
- Slow path: break every pot. Each break is a sculk sensor pulse
  (section 5.1).
- Gate: iron door via comparator.
- Pressure: sensor pulses.
- Build: template only. The frame lock is vanilla redstone.

**Rotation Lock** (tier 2)
- See: same as Frame Lock but the frame already holds an item. A pattern
  on the floor (arrows in copper trapdoors) shows which way.
- Works: rotate the frame to the comparator level the door wants. Eight
  positions, one right.
- Slow path: try them all. Eight clicks.
- Gate: iron door via comparator at a specific signal strength.
- Build: template only.

**Plate Pair** (tier 1)
- See: two stone pressure plates six blocks apart. Iron door. Nothing
  else.
- Works: two players. One player and a mob on a lead. One player and a
  wolf told to sit on the far plate. A dropped item stack does not (stone
  plates ignore items; the wooden variant is a different room).
- Slow path: none if solo with no mob. This is the one situation that
  **requires** `provides: lead` or `provides: mob` upstream, or a party.
  The generator must respect that (section 6).
- Gate: iron door, AND of two plates.
- Build: template only.

**Item Plate** (tier 1)
- See: one oak pressure plate under a two-block-high hopper column with a
  chest on top. Iron door.
- Works: drop any item on the plate. Wooden plates trigger on items. The
  plate is under a hopper, so your item is swallowed and delivered to the
  chest on the far side of the door. You get it back.
- Slow path: stand on it yourself and the door closes when you step off.
  So: throw an item.
- Gate: iron door via plate.
- Build: template only.

**Gallery** (tier 2)
- See: three target blocks across a pit. Iron door. A redstone lamp per
  target.
- Works: arrows, snowballs, eggs, a thrown trident, a wind charge (wind
  charges do activate targets). Three hits within a few seconds (a hopper
  clock resets the latch).
- Slow path: bridge the pit and punch the targets in sequence before the
  latch resets. Tight.
- Gate: iron door via three latched lamps.
- Build: template only.

**Tripwire Hall** (tier 1)
- See: a long room with string across it at knee height every three
  blocks. Dispensers in the walls.
- Works: shears cut the string without triggering it. Jumping clears
  each. Sprinting through and eating the arrows works if you have the
  health.
- Slow path: walk into each one and take an arrow.
- Gate: none.
- Pressure: arrows.
- Build: template only. Dispensers hold 3 arrows each so the room runs dry
  for a party.

**Flow Puzzle** (tier 2)
- See: a water source in one corner, a hopper in the opposite corner
  behind a fence, an item on a pedestal, two loose stone blocks on the
  floor. Iron door.
- Works: pick up the item, place the two blocks to bend the water toward
  the hopper, drop the item in the stream. Hopper receives, door opens.
- Slow path: the item can also be thrown over the fence directly if you
  stand in exactly the right spot. Finding the spot is the slow path.
- Gate: iron door via hopper comparator.
- Build: template only.

**Pot Room** (tier 1)
- See: forty decorated pots on shelves. One holds the trial key the next
  room's vault needs. A sculk sensor in the ceiling centre.
- Works: TNT clears all forty at once (one pulse instead of forty).
  Sneaking near the sensor mutes your footsteps but not the pot breaks.
  Wool on the sensor mutes it entirely. A pot's contents also drop when
  you shift-click; pots with contents rattle differently, and observant
  players learn it.
- Slow path: break them one by one.
- Gate: none; the key is the point.
- Pressure: each pulse advances the run's omen (section 5.2).
- Build: template only. `provides: trial_key`.

### 4.3 Knowledge

**Bazaar** (tier 2)
- See: eight piglins around a table of gold blocks. Exit door open behind
  them.
- Works: wear any gold armour piece and walk through. Throw a gold ingot
  and they barter (a shipped `piglin_bartering` override drops tools, not
  vanilla junk). Hit nothing.
- Slow path: fight eight piglins.
- Gate: none.
- Build: template plus `spawnMobs`. Piglins need `IsImmuneToZombification`
  set; this dimension is not the Nether.

**Don't Look** (tier 2)
- See: pitch dark. Four endermen. The exit is a two-block-high gap, which
  an enderman cannot path through.
- Works: a carved pumpkin. Water on the floor (they will not stand in
  it). Looking at their feet.
- Slow path: fight four endermen in a 14 x 14 box.
- Gate: none.
- Build: template plus `spawnMobs`.

**The Herd** (tier 1)
- See: twelve zombified piglins milling about.
- Works: touch nothing, walk through.
- Slow path: there is no slow path, there is only the wrong path.
- Gate: none.
- Build: template plus `spawnMobs`.

**Deep Dark Landing** (tier 3)
- See: sculk floor, four sensors, one shrieker by the exit, dark. The
  shared wall has a tinted glass window at eye height so the player
  sees the sculk and the dark without light leaking in. Tinted glass,
  not bars, because light containment is the point: a regular window
  would light the room enough to spoil the sneaking.
- Works: sneak the whole way. Wool blocks on the sensors. Snowballs
  thrown at the far wall pull the sensors' attention. A warden is summoned
  on the third shriek and follows you into the next rooms.
- Slow path: run it and take the shrieks. Two is survivable.
- Gate: none.
- Pressure: shriek count, and the warden.
- Build: template only. The shrieker must have `can_summon: true`.

**Elder's Chamber** (tier 3)
- See: a flooded room with an elder guardian. Mining Fatigue III the
  moment you enter. The exit is bricked with a one-block soft wall.
  Glass window in the shared wall, same as Flooded Hall: water
  containment.
- Works: milk clears fatigue and you mine the wall. Kill the guardian
  (slow underwater without a trident). A trident from an earlier drowned
  room makes it a fight you can win.
- Slow path: mine through with Fatigue III. About 45 seconds per block.
- Gate: the soft wall.
- Build: template plus `spawnMobs`.

**Blaze Loft** (tier 2)
- See: three blazes on a ledge, lava below the ledge, exit under them.
- Works: snowballs deal 3 to blazes. A water bucket thrown on the ledge.
  Run under and let them burn out their attention.
- Slow path: bow or sprint.
- Gate: none.
- Build: template plus `spawnMobs`. `provides: snowballs` upstream is
  encouraged, not required.

**Infested Wall** (tier 2)
- See: the exit is bricked over with stone bricks. Some are infested. No
  visible difference.
- Works: TNT clears the wall and kills the silverfish. Hitting an infested
  block with Silk Touch does not release it (vanilla). Mining the wall
  band nearest the door first, since the template puts the infested blocks
  in the outer band.
- Slow path: mine and fight silverfish.
- Gate: the wall.
- Build: template only.

### 4.4 Combat rooms, kept but varied

The existing `encounter` role and its spawner-clear gate stay for these.
What changes is `spawn_potentials`, all data:

| Room | Spawner | The twist |
|---|---|---|
| **Breeze Arena** | breeze x2 | Platforms over lava; knockback is the danger, not damage |
| **Bogged Marsh** | bogged x3 | Waist-deep water slows the player; poison arrows |
| **Ledge Archers** | skeleton x4 behind iron bars, 3 up | Iron bars contain the skeletons (see-through but no projectiles out). You cannot reach them without blocks or a pearl; running past is the intended fight |
| **The Raid** | vindicator x3, evoker x1 | Totem of undying is the loot |
| **Slime Pit** | slime x4 | Slime block floor; the bounce reaches a ledge the door is on |
| **Wither Loft** | wither skeleton x3 | Wither effect; the Innkeeper bag pays off |
| **Creeper Kennel** | creeper x3 behind a fence gate, none spawn free | Not a fight. A tool. Open the gate and steer one at the soft wall |

### 4.5 Gambles (spurs)

Spurs exist already (`branchProbability`) and are visible from the
critical path. Each spur is one of:

**Barred Vault**: a vault behind iron bars. A trial spawner between you
and it. The spur costs a fight and pays a tool. The spawner is visible
from the doorway, so the decision is informed.

**Ominous Bargain**: an ominous vault and an ominous bottle on a
pedestal. Drink and the rest of the run is ominous (all spawners, all
vaults). Better tools, worse everything else. The bottle is the only way
into ominous mid-run.

**The Altar**: a hopper under an item frame, a dropper behind. Feed it
food and it pays a trial key; feed it something rarer and the comparator
tier pays something rarer back. Vanilla item-count logic.

**The Store**: the existing M35 anomaly, unchanged, now a spur candidate
rather than a critical-path swap.

---

## 5. Pressure without a global clock

### 5.1 Local pressure

Every situation in section 4 that lists a pressure supplies it with its
own physics: breath, freezing, poison, arrows, lava. Three rooms exist
purely to be timers:

**Rising Lava** (tier 2): a hopper clock feeds dispensers loaded with
lava buckets. The floor gets a new lava block every twelve seconds along
a fixed path from the entrance side. Cross fast or pillar and cross high.

**Collapsing Bridge** (tier 2): a bridge over a pit. Observers behind
each segment retract it after you step off. Forward only, enforced by
pistons instead of by a mixin.

**Hold the Plate** (tier 1): a plate in the room centre, a hopper clock
of 30 seconds, a trial spawner. Stand on it until the clock lands and the
door opens. Step off and the clock resets. A per-room timer with no Java.

### 5.2 Run-level pressure: the dungeon wakes

Replace keystone depletion by clock with **omen accumulation**. The run
tracks an integer omen, 0 to 4:

| Source | Omen |
|---|---|
| Every 90 seconds spent in one cell beyond the first 60 | +1 |
| Each sculk sensor pulse in a Pot Room or Landing | +1 per 5 pulses |
| Each shriek | +1 |
| Drinking the Ominous Bargain | set to 4 |
| Clearing a spur's Barred Vault | -1 |

Omen maps directly onto the vanilla Trial Omen amplifier on the player
(`Instances` already applies `TRIAL_OMEN`), so spawners the player has not
yet reached escalate to ominous as omen rises. There is no bar, no
message. The rooms ahead get worse. The player notices the second ominous
spawner and understands.

Keystone consequences on completion:

| Finish omen | Keystone |
|---|---|
| 0 to 1 | +1 level, three reward chests |
| 2 to 3 | +1 level, two chests |
| 4 | +0 level, one chest |
| Abandoned via `/dungeon exit` | depletes as today |

Death ejection stays penalty-free for now (test group constraint, revisit
per `VISION.md` 3.4).

### 5.3 What to do with `RunTimer`

Keep the class, drop the boss bar. The timer still records total run time
for the completion line, the diary, and future leaderboard use. It stops
being a gate.

### 5.4 Omen is per floor, dwelling is per uncleared cell

Two adjustments once a run is a multi-floor loop (section 12):

- **Omen resets on the staging room.** Each floor starts at omen 0. The
  keystone consequence in 5.2 is computed from the *sum* of the floors'
  finishing omens between two safe room visits (0 to 20 for 5 floors),
  with the thresholds scaled to match: 0 to 5, 6 to 15, 16 to 20. A bad
  floor is recoverable; five bad floors are not.
- **Dwelling only counts in uncleared cells.** The +1 per 90 seconds
  applies while the player is in a cell whose situation is unsolved.
  Standing in a cleared cell, or in the staging room, is free. Without
  this the retreat-and-think loop that section 12.3 relies on is
  punished, and the player is pushed to stand in the dangerous room
  rather than the safe one.

The safe breather Barony gets from its transition floors is the staging
room itself (12.2). There is no separate quiet cell.

---

## 6. Generator changes

### 6.1 Schema additions to `dungeon_room`

| Field | Type | Default | Notes |
|---|---|---|---|
| `content` | string | none | Already parsed. Becomes the situation id dispatched by `RoomContent` |
| `tier` | int | `1` | Minimum loot tier for this situation to appear |
| `provides` | string[] | `[]` | Tool tags this room guarantees to drop (`blocks`, `water`, `lead`, `trial_key`, `boat`, `gold`, `snowballs`, `shears`, `mob`) |
| `requires` | string[] | `[]` | Tool tags at least one of which must be available before this cell |
| `pressure` | string | none | `local`, `omen` or none; informational, used by the selector to space pressure rooms apart |
| `access` | string | `open` | `open` (exits reachable without engaging) or `gated` (must solve to pass). The selector places at most one gated cell per branch and at least one on the critical path between entrance and staging room |

Tags, not item ids. `water` is satisfied by a water bucket or a bag that
has one; `mob` is satisfied by any upstream room that spawns a leashable
mob or by party size 2+.

### 6.2 The provides/requires pass

In `RoomSelector.resolveDetailed`, after roles are resolved, walk the
critical path in depth order carrying a set `available` seeded from the
chosen bag's tags (plus `mob` if party size is 2 or more). For each cell:

1. Filter candidate rooms to those whose `requires` intersects
   `available` or is empty.
2. Weighted pick as today.
3. Add the pick's `provides` to `available`.

If a cell has no candidate, fall back to the role-only pick with an empty
`requires` (a plain corridor or encounter). A run is never unsolvable
because of a bag; it is only less interesting.

Spur cells run the same pass but their `provides` do **not** feed the
critical path's `available`, because a spur is optional. They may feed
the cells after the spur's branch point as a bonus, which is what makes
the spur worth taking.

This pass walks one path. Section 6.6 generalizes it to the whole graph
using root distance, so that spur cells and off-path gated cells are
checked too. The 6.2 pass is the 6.6 pass restricted to the shortest
entrance-to-staging-room path; 6.6 is what should be implemented, and
6.2 is kept here as the conceptual stepping stone.

### 6.3 Pilgrim as the generator's test

Run the 6.6 graph pass with an empty bag and party size 1. Every gated
cell in the graph must still have a slow path or an upstream `provides`
within its root distance. A `Plate Pair` at n = 3 with no `lead` or
`mob` at n < 3 under Pilgrim is a generator bug, not a design choice.
Add this as a pure-JDK test alongside `LayoutGraphGenerator`'s.

### 6.4 Gates

Two kinds, and the selector should know which:

- **Physical**: iron door plus redstone, soft wall, lava, water. Lives in
  the template. Nothing for Java to check. The doorway is 2 x 3 and an
  iron door pair is 2 x 2, so templates fill the top block of the doorway
  with a wall block.
- **Java**: the existing spawner-clear check in `RunLifecycle` (line 763
  area) and the boss check. Keep for `encounter` cells only.

`spawnerClearThreshold` becomes per-run: count only `encounter` cells,
not situations.

**The item-return rule.** If a gate's solution involves placing an item
in a container (hopper, dropper, item frame, comparator), the template
must return that item to the player after the gate opens. The standard
pattern is a chest or hopper output on the far side of the door, within
reach once the door opens. Frame Lock already does this ("chest on the
far side of the door. You get it back"). This rule makes every
gate-required capability reusable: the player carries the tool forward
to the next room that needs it. Without it, two consecutive rooms
requiring the same tag would break, because the first room consumes the
only item and the second room is stuck.

This is why the 6.6 invariant can treat `available` as a boolean set of
tags rather than tracking item counts. As long as gates return their
input items, a capability in `available` stays available at all deeper
distances. The spec's catalogue must be audited against this rule before
shipping: every gated template that takes an item must also return it.

### 6.5 Floor shape for a hidden staging room

For the staging room to be something the player *finds* (section 12),
the terminal must not be visible from the entrance and must not be the
obvious far end of a straight line. Three generator parameter changes,
no new algorithm:

- `targetLength` for the critical path goes up (8 to 12 cells at tier 1,
  tuned by playtest) and `branchProbability` and `loopProbability` go
  up with it, so the floor is a small maze, not a spine with stubs.
- The terminal is placed at a critical-path index between 60% and 90% of
  the path, not at the end. The cells past it are a decoy branch. The
  player who walks the longest corridor does not automatically find the
  staging room.
- `validate` gains a check: the terminal cell must not share a row or
  column with the entrance within line of sight (no straight run of open
  doorways between them).

`RoomSelector` keeps the provides/requires pass; it now walks the
*shortest* path from entrance to staging room, not the full critical
path, because that is the only route the player is guaranteed to take.

### 6.6 The root-distance solvability invariant

The entrance cell is the **root** of the floor's graph. Every other cell
is at a graph distance n from the root, where n is the shortest path
length (in edges) from the entrance to that cell. The root is n = 0;
cells directly connected to it are n = 1; and so on. `LayoutGraphGenerator`
already runs BFS from the entrance and checks that every cell is
reachable; the distance is the BFS depth.

The invariant:

> **A cell at distance n may only `require` capabilities that are
> `provides` of some cell at distance < n, or of the bag.**

If a cell at n = 2 requires `redstone` (an iron door that needs a
comparator signal, say), there must be a cell at n = 0 or n = 1 that
`provides` redstone, or the bag must carry it. The solution is always
reachable before the gate, on every path, not just the critical path.

Why this is stronger than the current 6.2 pass:

- 6.2 walks one path (the shortest entrance-to-staging-room path) and
  carries `available` forward. A spur that branches at n = 1 and
  reaches a gated cell at n = 3 is not checked by 6.2 at all today.
- 6.6 checks every cell in the graph against its own root distance. A
  spur's gated cell at n = 3 must still find its `requires` in a cell
  at n < 3, even though the spur is optional and not on the critical
  path. The player who takes the spur is never stuck.

How it works in the selector:

1. After the layout graph is generated and roles are assigned, compute
   BFS depth from the entrance for every cell. This is already
   available from `LayoutGraphGenerator.validate`'s reachability check;
   expose it as a `Map<PlanCell, Integer>` on `DungeonShape`.
2. Process cells in BFS order (n = 0, 1, 2, ...). Maintain a cumulative
   `available` set seeded from the bag's tags (plus `mob` if party size
   >= 2).
3. For each cell at depth n, filter candidate rooms to those whose
   `requires` is a subset of the union of `available` from all cells at
   depth < n. This is the 6.2 pass generalized to the whole graph, not
   just one path.
4. Weighted pick from the filtered candidates. Add the pick's
   `provides` to `available` at depth n, so cells at n + 1 can see it.
5. If a cell has no candidate after filtering, **backtrack**: return to
   the previous cell in BFS order and try its next candidate. This is
   the same pattern `LayoutGraphGenerator.generateCriticalPath` already
   uses, with `MAX_BACKTRACK_STEPS` as the safety cap. The existing
   constant (50000) is more than enough for 20 cells.
6. If backtracking exhausts all options, fall back to the role-only
   pick with empty `requires` (a plain corridor or encounter). A run is
   never unsolvable because of a bag; it is only less interesting.

Why backtracking, not pure greedy:

A greedy pass makes irrevocable choices in BFS order. If a cell at
depth 2 picks a room that provides `water` but not `redstone`, and a
cell at depth 3 requires `redstone`, greedy falls back to a boring
room even though a satisfying assignment exists (swap the depth-2 pick
for a room that provides `redstone`). Backtracking finds that
assignment. For 20 cells with 5 to 10 candidate rooms each, the search
is cheap: the branching factor is small and the depth is shallow, so
even worst-case backtracking completes in milliseconds. The safety cap
prevents pathological cases.

Spur cells follow the same rule. A spur at depth n is processed in BFS
order like any other cell. Its `provides` feed `available` at its depth,
so cells at greater depth on other branches can benefit from it. This
is what makes a spur worth taking: it may provide a tool that unlocks
a gated cell on a different branch at greater depth, and the player who
skipped the spur has to find another source or take the slow path.

What this does not change:

- The critical path still exists and is still guaranteed.
- The staging room is still the terminal, placed at 60 to 90% of the
  critical path (6.5).
- Open cells still do not require anything. The invariant only
  constrains cells with non-empty `requires`.
- The Pilgrim test (6.3) still applies: run the whole graph pass with
  an empty bag and party size 1, and every gated cell must still have
  a slow path or an upstream `provides` within its root distance.

Edge cases:

- **Loops.** A loop edge means a cell is reachable via two paths. Its
  root distance is the shorter one. The invariant uses the shorter
  distance, which is the strictest: if a cell is n = 2 via one path
  and n = 4 via a loop, its `requires` must be satisfiable at n < 2.
- **The bag.** The bag's tags are treated as n = 0 (root-level
  `provides`). Anything the bag carries is available to every cell
  regardless of distance.
- **Party size.** `mob` is available at n = 0 if party size >= 2, same
  as today. A solo Pilgrim does not get it.
- **Multiple `requires`.** A cell with `requires: [water, redstone]`
  needs both to be available at distance < n, not just one. The
  filter checks that `requires` is a subset of `available`, not that
  it intersects.
- **Same-depth provides.** A cell at depth n cannot use `provides`
  from another cell at depth n, only from depth < n. Two cells at the
  same depth might be reachable from each other via a loop, but the
  player is not guaranteed to visit one before the other, so the
  invariant plays safe.
- **Consumption.** `available` is a boolean set of tags, not a count.
  This is correct only because of the item-return rule in 6.4: every
  gate that takes an item must return it after solving. As long as
  that rule holds, a capability in `available` stays available at all
  deeper distances, and two consecutive rooms requiring `redstone`
  are both satisfiable from one upstream `provides: redstone`. If a
  template violates the item-return rule (consumes the item without
  returning it), the boolean model breaks silently: the algorithm
  says the second room is solvable, but in play it is not. The
  catalogue audit in 6.4 is what prevents this.

---

## 7. The Herobrine Cube as run composer

The Cube is a crafting-like interface for shaping runs. Recipes are
discovered, never listed, and expressed as "put these in, take a keystone
out." Proposed recipe set:

| Input | Result on the next run |
|---|---|
| keystone + ominous bottle | ominous from the start (exists) |
| keystone + bone | Feral (exists as affix) |
| keystone + any bag's headline item | overrides the door's bag with that bag |
| keystone + TNT | at least one Infested Wall or Creeper Kennel guaranteed |
| keystone + water bucket | Flooded and Chasm rooms weighted up |
| keystone + wool | Deep Dark Landing guaranteed, tier permitting |
| keystone + compass | the completion line lists the run's situations by name afterward (a study aid, not a map) |
| two keystones | path length +2 at the lower key's level |
| keystone + emerald | a Store spur guaranteed |

Each recipe writes a tag into the keystone's custom data that the door
reads at generation. The Cube's existing extract/imbue stays as is.

---

## 8. Example runs

Seeds and cell diagrams are illustrative. `E` entrance, `X` exit, `S`
spur. Each critical-path cell is named by its situation.

### 8.1 "Plumber, tier 1, solo"

Bag: water bucket, lava bucket, 4 bread. Party: 1. Path length 6.

```
E - Thicket - Chasm - Pot Room - Frame Lock - Bogged Marsh - X
                         |
                    S: Barred Vault
```

- **Thicket.** Cobwebs, cave spiders. The Plumber has a lava bucket. Pour
  it at the doorway and the webs burn in a line to the far wall; so do two
  spiders. Pick the lava back up (it is still a source). Twenty seconds.
  A player who did not think of fire punches through and eats poison.
- **Chasm.** Lava channel. The Plumber pours water on the lava and gets a
  cobble bridge for free, then picks the water up again. This is the run's
  first "oh" moment for a player who did not know the rule.
- **Pot Room.** Forty pots, a trial key in one. The Plumber has no TNT.
  Slow path: break pots. The sensor pulses. After twenty pots, omen 1. The
  player finds the key in pot twenty-three. Spawners ahead are now
  ominous-eligible.
- **Spur: Barred Vault.** Visible from the Pot Room doorway: a vault, a
  spawner, iron bars. The player has a trial key and omen 1. Clearing the
  spur spends the key, pays a stack of 8 cobble and 16 snowballs, and
  drops omen back to 0. The player takes it. Ninety seconds.
- **Frame Lock.** Iron door, empty frame, four pots. Deepslate theme; the
  copper ingot is in the north pot (theme rule the player may or may not
  know). Player breaks two pots, frames the ingot, door opens.
- **Bogged Marsh.** Encounter. Waist-deep water, three bogged. The player
  now has cobble from the spur and builds a two-block platform to fight
  from above the water. The spawner ejects a trial key nobody needs now.
- **X.** Pad. Omen 0. Three chests. Total 7:40.

Alternative line: skip the spur, keep the key for the Frame Lock room's
own vault (there is none, the player finds out), reach the Marsh with no
blocks and fight in the water. About the same time, much less fun.
Nobody is told which is right.

### 8.2 "Pilgrim, tier 2, solo"

Bag: 8 bread. Party: 1. Path length 7. The generator must feed the run.

```
E - Item Plate - Tripwire Hall - Flooded Hall - Plate Pair - Ice Run - Blaze Loft - X
                      |                              |
                S: Altar                        S: Store
```

Provides/requires trace, as the selector saw it:

| Cell | Requires | Provides | Available after |
|---|---|---|---|
| Item Plate | (none) | (none) | bread |
| Tripwire Hall | (none) | `shears` (a pot by the exit) | bread, shears |
| Flooded Hall | (none) | `trident` (one drowned carries it, 30%) | + maybe trident |
| Plate Pair | `mob` or `lead` | | **fails**: nothing upstream |
| (re-pick) Rotation Lock | (none) | `boat` (a pot) | + boat |
| Ice Run | `boat` (soft; encouraged) | | |
| Blaze Loft | (none) | | |

The Plate Pair was rejected because Pilgrim solo had no `mob` and no
upstream `lead`. Rotation Lock took the cell. That is the pass working.

- **Item Plate.** Throw a bread on the oak plate. Door opens, hopper eats
  the bread, chest on the far side gives it back. First lesson: your
  items are tools.
- **Tripwire Hall.** No shears yet; they are in the pot at the far end.
  Jump each wire. Three arrows taken. Shears acquired for next time.
- **Spur: Altar.** Feed it bread, get a trial key. The player has 7
  bread. Spends one. Now has a key and no idea what for yet.
- **Flooded Hall.** Swim it. Drowned has a trident, player kills it from
  the doorway with... nothing. Skips. The trident stays in the water.
- **Rotation Lock.** Copper trapdoor arrows on the floor point north-east.
  Rotate the frame five clicks. Door. Boat in the pot.
- **Spur: Store.** Sells a water bucket for 12 emeralds; the player has
  none. Sells 4 snowballs for 3 bread. Player buys. Now 3 bread.
- **Ice Run.** Boat down the blue ice. Breeze knocks the boat once; it
  keeps sliding. Four seconds.
- **Blaze Loft.** Four snowballs, three blazes at 3 damage each. Not
  enough to kill even one (blaze has 20 HP). Snowballs buy the seconds to
  sprint under the ledge. Two hits of fire. Player has 3 bread and eats
  two.
- **X.** Omen 0. Three chests. 9:10. Player arrives with shears, a boat,
  a trial key and one bread. All of it goes into the room, and the trial
  key is a decoration until someone works out it opens the room's own
  vault next time (section 9, open question 3).

### 8.3 "Shepherd, tier 1, party of 4"

Bag (each player): 4 leads, 8 bones, 4 bread. Path length 6, rooms
scaled for four.

```
E - Plate Pair - The Herd - Hold the Plate - Creeper Kennel - Infested Wall - X
```

- **Plate Pair.** Two players stand on two plates. Door opens. The other
  two walk through and the plates go off; the door closes on the two
  standing. The door only opens from the plate side. Puzzle: one player
  leashes a zombified piglin from the next room? Not possible, the next
  room is behind the door. Actual answer: leash a **wolf** (Feral is on this key)
  from the entrance corridor and sit it on plate two. Or: the two
  plate-holders throw a bone at the wolf that followed them. The room is
  authored so that a lead and a mob are both `available`; Shepherd
  supplies the lead, Feral supplies the mob.
- **The Herd.** Twelve zombified piglins. Four players. Someone always
  hits one. The party learns what "touch nothing" means the expensive
  way, once.
- **Hold the Plate.** A 30-second hopper clock; the spawner is configured
  for four players and runs eleven zombies simultaneous. One player holds
  the plate, three defend. Leads let two players tie a zombie to a fence
  post and reduce the count.
- **Creeper Kennel.** Three creepers behind a gate, a soft wall on the
  exit side. No fight. One player opens the gate, one player stands by the
  soft wall and steps back at the hiss. Wall gone. Or: leash a creeper
  (leads work on creepers? they do not; leads work on most mobs but not
  hostile monsters). The party discovers that, and uses a bone-tamed wolf
  to bait it instead.
- **Infested Wall.** Party has no pick. Four players punching stone
  brick is 4x slow path. The Kennel had a second creeper. Somebody goes
  back for it: two cells, the Herd in between, and the creeper has to be
  baited the whole way. The party votes no. Slow path it is. Silverfish
  times four. Loud, funny, 90 seconds. Omen +1 from dwell. (This is the
  section 12.3 decision in miniature: the retreat is allowed, and it is
  priced.)
- **X.** Omen 1. Three chests each. 8:00.

### 8.4 "Magician, tier 3, duo, Deep Dark theme"

Bag: 3 pearls, 4 wind charges, 4 bread. Path length 8.

```
E - Deep Dark Landing - Ledge Archers - Rising Lava - Elder's Chamber - Don't Look - Collapsing Bridge - Raid - X
                                |
                          S: Ominous Bargain
```

- **Landing.** Sensors and a shrieker. Pearl straight to the exit
  doorway: a pearl landing is a vibration, one pulse, no shriek. Player
  two sneaks and takes 40 seconds. Pearls: 2 left.
- **Ledge Archers.** Four skeletons behind bars three blocks up. Wind
  charge under your feet puts you on the ledge. Kill them in melee where
  they cannot kite. Or run past: the spawner-clear gate does not count
  Ledge Archers as an encounter (they are `spawnMobs`, not a trial
  spawner). Duo runs past.
- **Spur: Ominous Bargain.** Omen is 0. Drinking sets it to 4 and every
  spawner ahead is ominous, including the Raid. The ominous vault holds a
  tier-3 tool table (a trident, an enchanted golden apple, 16 obsidian).
  The duo argues. Player one drinks. This is the run.
- **Rising Lava.** Twelve-second lava advance. Wind charge jump clears
  the middle. Player two, out of tricks, sprints and pillars nothing
  (Magician has no blocks). Takes one lava tick. Eats.
- **Elder's Chamber.** Fatigue III. No milk. Slow path: mine the soft
  wall at 45 seconds a block. Or: the ominous vault gave a trident.
  Player one kills the elder guardian with it (Fatigue does not stop
  attacking). Two minutes of fight, then mine the wall at full speed.
- **Don't Look.** Four endermen, dark. Pearl past them to the 2-high gap.
  Player two has no pearls left and walks staring at the floor. Makes it.
- **Collapsing Bridge.** Pistons retract behind you. A wind charge
  panicked at the wrong moment sends player one off the bridge into the
  pit; the pit is one block deep and floored (Voided is not on this key).
  Embarrassing, not fatal. Climb out at the far side: the pit has a
  staircase, the slow path.
- **Raid.** Ominous now. Vindicators and two evokers instead of one. The
  fight the bottle promised. Totem drops.
- **X.** Omen 4. One chest. +0 keystone. Fifteen minutes. The duo has a
  trident, a totem, obsidian, and the best story of the evening. The
  omen table says this run was "bad". The design says it was correct.

### 8.5 A failure case, on purpose: "Sapper, tier 1, solo"

Bag: 3 TNT, flint and steel, 8 bread.

```
E - Pot Room - Chasm - Frame Lock - Spawner Den - X
```

- **Pot Room.** Three TNT. Player uses one. Forty pots gone in one pulse,
  trial key on the floor. Elegant.
- **Chasm.** Lava, no blocks, no water. TNT does nothing to lava. Player
  tries: places TNT on the near edge to "blast a bridge". Lava does not
  care. Two TNT left. Slow path: the wall ledge. Player falls in once,
  climbs out at the near side (lava is one deep at the edge; the template
  guarantees the near bank is survivable). Second attempt succeeds. Sixty
  seconds. Omen stays 0 (under the dwell threshold).
- **Frame Lock.** Player has learned. Does not TNT the four pots (the
  ingot would survive but so would the temptation). Opens the right pot.
- **Spawner Den.** Encounter. One TNT into a pack of zombies. Spawner
  clear gate met. Player exits with one TNT.
- **X.** Omen 0. Three chests. 6:30. The lesson: the bag is not the
  answer, it is one of the answers, and knowing when it is not one is the
  skill.

---

## 11. Stash and swap: the failsafe inventory model

> The design goal is one sentence: **if the player is in
> `pocketdungeons:void`, they have the dungeon inventory; anywhere else,
> they have their survival inventory. No mismatch is possible, because
> there is no pair of events to desync.**

This section replaces the brief "stash and swap" paragraph in 3.1 with the
full mechanism. It is written against the engine as it exists today:
`DungeonLog extends SavedData` for per-player persistence, the
`ServerTickEvents.END_SERVER_TICK` hook already registered in
`Instances.register()`, `ServerPlayConnectionEvents.JOIN` already
registered for login recovery, and `ServerLivingEntityEvents.ALLOW_DEATH`
already intercepting death inside dungeons (nobody dies; they are
ejected).

### 11.1 What other mods do, and the bugs they have

Research into existing per-dimension inventory mods, with particular
attention to their issue trackers. The findings below are what drove the
design in this section.

**Thomilist/dimensional-inventories** (Fabric, the closest analogue):
uses `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` as the
primary swap hook, plus `ServerPlayerEvents.AFTER_RESPAWN` for respawn
across dimension pools. Saves inventory (main 36, armor 4, offhand 1,
ender chest 27), XP, health, hunger, saturation, exhaustion, and gamemode
to per-pool JSON files via GSON. The swap implementation
(`InventoryModuleState`) uses `NonNullList<ItemStack>` per section and
directly copies slots via `loadFromPlayer` / `applyToPlayer`. Ships a
**Lost and Found** system: when a swap fails or data is lost, the full
inventory contents are written to a timestamped text log file and the
player is sent a red chat message. Ships a gametest
(`DimensionPoolChangeOnRespawnTest`) using Carpet's fake player to verify
transitions are handled exactly once on respawn across dimensions.

Its issue tracker has three bugs directly relevant to us:

**Issue #25 (closed, fixed): inventory duplication on cross-dimension
respawn.** A Fabric API change in `0.125.0+1.21.5`
(commit `3ce78663`) made `AFTER_PLAYER_CHANGE_LEVEL` also fire on respawn
in a different dimension, where previously only `AFTER_RESPAWN` fired.
The mod's transition handler was registered on both events, so a
cross-dimension respawn triggered the swap twice: the first swap saved
the destination pool inventory and loaded the origin pool inventory, the
second swap saved the origin pool inventory (now containing the items
that were just loaded) and loaded the destination pool inventory again.
Result: items from the origin pool were duplicated into the destination
pool. The fix was the `transitionAlreadyHandled` deduplication check: a
`Map<Entity, TransitionInfo>` tracking the last origin/destination pair
per entity, skipping if the same transition is processed twice. This is
fragile (keyed on the entity object, which can be recreated on respawn)
and is a patch for a specific double-fire, not a general solution. Any
new double-fire path reintroduces the bug.

**Issue #22 (open, unfixed): items lost forever after logout, login, and
respawn.** A player enters a custom dimension, stays 5 minutes, logs out,
logs back in, is teleported by another mod (Origins), `/kill`s
themselves, and respawns with no items. The survival inventory is gone.
This is the exact scenario the tick invariant in 11.3 catches: somewhere
in the logout/login/teleport/death/respawn sequence, the event handler
did not fire (or fired on the wrong entity, or fired before the player
was fully loaded), and the survival backup was never written or was
overwritten. The Lost and Found system logs the loss, but the items are
still gone. This bug has been open since March 2025.

**Issue #16 (open, unfixed): riding an entity through a portal across
pools.** Riding a boat, minecart, donkey, or pig into a nether portal
when the destination is in a different dimension pool: the vehicle
disappears but the player does not travel. The player is left in the
origin dimension, but the entity-level handler may have already cleared
the vehicle. This is not an inventory loss bug per se, but it shows that
dimension change events have ordering issues with entity passengers that
the mod does not handle.

**Vanilla bug MC-258705 / MC-267272 (open): cursor-held item lost on
dimension change.** When a player has an item picked up with the mouse
cursor in an open inventory UI and changes dimension (portal, command,
end portal), the cursor item is dropped at the origin dimension's portal
coordinates or vanishes entirely. The cursor item is `player.containerMenu.getCarried()`,
slot 42, and it is NOT part of the 41-slot inventory that
`InventoryModuleState` snapshots. Any per-dimension inventory mod that
snapshots the 41 slots and clears the inventory on dimension change
silently destroys the cursor item if the player has their inventory open
during the transition. dimensional-inventories does not handle this.
Neither does any other mod found in this research. This is a vanilla bug,
but it becomes our bug the moment we clear the inventory on a dimension
change the player did not initiate through a door.

**GoidaInvRestore** (NeoForge, inventory backup tool, not per-dimension):
notable for two patterns. First, a **pre-restore safety net**: every
restore first backs up the current state, so a restore is itself
reversible. Second, it covers **Curios slots, Cosmetic Armor Reworked
layers, and equipped Sophisticated Backpacks contents** when those mods
are present, by checking their slot registries at snapshot time.

**misode/inv-restore** (Fabric): takes snapshots on death, join,
disconnect, and **dimension change**. Stores up to 50 per player. This is
a staff recovery tool, not a live swap, but the snapshot-on-dimension-change
concept confirms the event as the right trigger for the common case.

The pattern across all of them: **event-based swap, plus a log-based
recovery mechanism for when the swap goes wrong.** None of them prevent
desync; they all accept it and provide recovery. Issue #22 is the proof
that recovery is not enough: the items are logged, but they are still
gone. The three-layer design below (event + tick + Lost and Found) is
the prevention layer they all lack, plus the recovery layer they all
have.

### 11.2 The three-layer approach: event, tick, and Lost and Found

The obvious implementation is: on door entry, save survival, load bag; on
exit, save bag loot to room, load survival. This is a **paired event**
model, and paired events desync. The research in 11.1 shows three concrete
ways this fails:

- **The event does not fire.** Issue #22: a player logs out in a custom
  dimension, logs back in, is teleported by another mod, dies, and
  respawns. Somewhere in that sequence the dimension change event did
  not fire (or fired on the wrong entity), and the survival inventory
  was never saved. Items lost forever. The Lost and Found logged it. The
  items are still gone.
- **The event fires twice.** Issue #25: a Fabric API change made
  `AFTER_PLAYER_CHANGE_LEVEL` also fire on cross-dimension respawn, on
  top of `AFTER_RESPAWN`. The swap ran twice and duplicated items. The
  fix was a fragile deduplication map keyed on entity objects that are
  recreated on respawn.
- **The event fires but the snapshot is incomplete.** Vanilla bug
  MC-258705: the item held on the mouse cursor in an open inventory UI
  is not part of the 41-slot inventory snapshot. Any dimension change
  while the inventory is open silently destroys the cursor item.

Each one is a bug report that starts "I was in a dungeon and then..." The
fix is three layers, each covering a distinct failure mode:

**Layer 1: the event handler.** Register
`ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL`. When a
player's dimension changes between `pocketdungeons:void` and anything
else, perform the swap immediately. This covers `/dungeon`, `/dungeon
exit`, death ejection, nether portals, and any vanilla dimension change.
The player never sees a one-tick flash of the wrong inventory. This is
the common path and it is fast.

**Layer 2: the tick invariant.** The reconciliation pass from 11.5
below runs every tick as the safety net. If the event handler already
processed the transition, the invariant holds and the tick pass is a
no-op (one dimension check per player). If something bypassed the event
(issue #22: logout/login/teleport/death sequence where the event did
not fire, or fired on the wrong entity), the tick pass catches it
within one tick. This is the layer that prevents issue #22. Without it,
we are relying on the event firing correctly in every scenario, and the
research proves it does not.

**Layer 3: the Lost and Found.** Section 11.10. Every swap writes a
text log. If `SavedData` corrupts or the swap throws, the log is the
recovery source. This is the layer dimensional-inventories has and we
adopt.

**Deduplication.** The tick pass checks the `survivalStashed` flag, not
the event history. If the event handler set the flag, the tick pass sees
the invariant holds and does nothing. There is no "did we already handle
this transition" map to maintain, because the flag is the deduplication.
This is simpler than dimensional-inventories' `transitionAlreadyHandled`
record and cannot itself desync. If the event fires twice (issue #25),
the first call sets the flag, the second call sees the invariant holds
and does nothing. No duplication.

**The cursor item.** Before any swap that clears the inventory, return
the cursor item to the inventory: `player.containerMenu.setCarried(ItemStack.EMPTY)`
after `player.containerMenu.getCarried()` is stored in the next free
slot (or dropped if the inventory is full). Also close any open
container: `player.closeContainer()`. This prevents the vanilla
MC-258705 cursor item loss. The snapshot is then 42 slots: 41 inventory
plus 1 cursor, and the cursor is restored as a cursor on the other side
if the player had a container open (which they should not, since the
dimension change closes it).

### 11.3 The invariant

One boolean state per player, persisted in `DungeonLog.Entry`:

> **`survivalStashed`**: true means "this player's survival inventory is
> held in `survivalBackup` and must be restored when they next appear
> outside `pocketdungeons:void`."

The invariant the system enforces, every tick, for every online player:

```
survivalStashed == (player is in pocketdungeons:void)
```

If both sides agree, do nothing. If they disagree, fix it. There is no
"entry handler" and no "exit handler." There is one reconciliation pass
that runs on every server tick and on join, and it converges the state
to the invariant regardless of how the player got where they are.

### 11.4 Storage

Two new fields on `DungeonLog.Entry`, saved with the same
`RecordCodecBuilder` pattern as every other field there:

| Field | Codec | Default | Meaning |
|---|---|---|---|
| `survivalStashed` | `Codec.BOOL` | `false` | The invariant flag. True means the backup is held. |
| `survivalBackup` | `ItemStack.OPTIONAL_CODEC.listOf()` | empty list | The player's full survival inventory: 36 main, 4 armor, 1 offhand, 1 cursor, serialized as a flat list of 42 stacks. Empty slots are `ItemStack.EMPTY`. |

Why `DungeonLog` and not player NBT:

- `DungeonLog` is already the mod's per-player persistence layer, already
  saved to the world's `data/` directory, already keyed by UUID, already
  migrated across versions with `optionalFieldOf` defaults. Adding two
  fields is the same shape as `fuel` and `extractedPowers`.
- Player NBT (via mixin into `ServerPlayer.saveAdditional`) would work but
  introduces a mixin the mod does not currently have, and the mod's
  stated constraint is server-side-only with no client mod. `SavedData` is
  cleaner.
- The backup must survive a server crash and restart. `SavedData` is
  persisted to disk on `setDirty()`. An in-memory map is not.

The backup is written as a flat list of 42 `ItemStack` slots in a fixed
order: indices 0 to 35 are the main inventory (hotbar 0 to 8, then main
9 to 35), 36 to 39 are armor (boots, leggings, chestplate, helmet), 40 is
offhand, 41 is the cursor item (`player.containerMenu.getCarried()`).
This is the same order `PlayerInventory`'s `getNonEquipmentItems` and
`getArmorItems` / `getOffhandItem` produce, plus the cursor slot that
dimensional-inventories misses (vanilla MC-258705). Save and restore are
direct slot copies with no reordering logic. The cursor slot is restored
to the player's container only if a container is open; otherwise it is
placed in the first free main slot.

### 11.5 The reconciliation pass

Runs from the existing `ServerTickEvents.END_SERVER_TICK` hook in
`Instances.register()`, alongside `onTick` and `processJoinRecoveries`.
Also called once immediately from the existing
`ServerPlayConnectionEvents.JOIN` handler, so a player who logs in
desynchronised is fixed before they can act. The event handler
(`AFTER_PLAYER_CHANGE_LEVEL`) calls the same swap logic, so the common
path is handled immediately and the tick pass is the safety net.

```
for each online ServerPlayer player:
    boolean inVoid = player.level().dimension().equals(DUNGEON_LEVEL)
    DungeonLog.Entry entry = DungeonLog.forServer(server).get(player.uuid)
    boolean stashed = entry.survivalStashed()

    if (inVoid && !stashed):
        // ENTERING: save survival, clear inventory, apply dungeon kit
        player.closeContainer()  // cursor item returns to inventory
        entry.setSurvivalBackup(snapshot(player))  // 42 slots incl cursor
        entry.setSurvivalStashed(true)
        clearInventory(player)
        applyKeystoneItem(player, entry)
        // bag is applied on door pick, not here; in the room the
        // player has only the keystone display item and empty slots

    else if (!inVoid && stashed):
        // LEAVING: deliver void inventory to room, restore survival
        player.closeContainer()
        deliverToRoom(player, snapshot(player))  // 42 slots incl cursor
        clearInventory(player)
        restoreInventory(player, entry.survivalBackup())  // 42 slots
        entry.setSurvivalBackup(emptyList)
        entry.setSurvivalStashed(false)

    else:
        // invariant holds; no action
```

That is the entire mechanism. Four branches, two of which are no-ops.
`player.closeContainer()` runs before the snapshot to return the cursor
item to the inventory (vanilla MC-258705). The snapshot captures 42
slots: the 41 inventory slots plus the cursor slot, which is empty after
`closeContainer` but is captured for safety.

### 11.6 What each branch does

**Entering (`inVoid && !stashed`):**

1. `player.closeContainer()` closes any open inventory UI. This returns
   the cursor item to the inventory (vanilla closes containers on
   dimension change, but calling it explicitly before the snapshot
   guarantees the cursor item is in a slot, not on the mouse).
2. `snapshot` copies all 42 slots (41 inventory plus cursor) from the
   player's live inventory into `survivalBackup`. This is the sacred
   copy. It is written once, on entry, and never touched again until the
   player leaves.
3. `clearInventory` sets all 41 slots to `ItemStack.EMPTY` and clears
   the cursor. The survival items are gone from the live inventory; they
   exist only in the backup.
4. `applyKeystoneItem` places the keystone display item (a recovery
   compass carrying the player's level, same as the existing watcher
   reconcile does today) in hotbar slot 0. The player is now standing in
   their room with a keystone and 40 empty slots.
5. The bag is **not** applied here. It is applied when the player picks a
   door (section 11.8). In the room, the player has the keystone and
   empty slots. They can open room chests, take out decoration blocks,
   and place them. Those blocks become part of the room blob and persist
   via `RoomStore` as they do today.

**Leaving (`!inVoid && stashed`):**

1. `player.closeContainer()` closes any open inventory UI and returns
   the cursor item to the inventory.
2. `deliverToRoom` takes the player's current void inventory (42 slots
   including cursor) and delivers it to the room's containers, the same
   mechanism that delivers reward chest contents today. Overflow is
   dropped at the room's lodestone pad. If the player has no room (first
   run, room not yet built), items are dropped at the player's current
   position. Nothing is voided silently.
3. `clearInventory` empties all 41 slots and the cursor.
4. `restoreInventory` copies `survivalBackup` back into the live
   inventory, slot for slot. The cursor slot (index 41) is restored to
   the container if one is open, or to the first free main slot.
5. The backup is cleared and the flag is set to false. The player is now
   in the overworld (or wherever they were sent) with their survival
   inventory, exactly as it was.

**Invariant holds (`inVoid && stashed`, or `!inVoid && !stashed`):**

Nothing happens. The player is in the correct state. This is the common
case, and it costs one dimension check per player per tick.

### 11.7 Why this is failsafe

Every edge case is a case where the invariant is violated for at most one
tick, and the next tick fixes it. No survival items are ever lost because
the backup is the source of truth, not the live inventory.

| Scenario | Event layer | Tick layer |
|---|---|---|
| Normal `/dungeon` entry | swaps immediately | no-op, correct |
| Normal `/dungeon exit` | swaps immediately | no-op, correct |
| Death ejection (teleport out) | swaps immediately | no-op, correct |
| `/execute in ... run tp` into void | fires (goes through `ServerPlayer.teleport`) | no-op, correct |
| `/execute in ... run tp` out of void | fires | no-op, correct |
| Server crash mid-run | (server down) | n/a |
| Rejoin after crash, still in void | not fired (already in dimension) | no-op, correct. Player has whatever inventory was saved with the player file. |
| Rejoin after crash, outside void | not fired (already in dimension) | restore survival, deliver void items to room |
| Rejoin into a purged slot (sent to overworld by join recovery) | may fire if join recovery teleports | catches if not |
| Admin `/dungeon admin build` (enters void) | swaps immediately | no-op, correct |
| Nether portal in void (if one ever exists) | swaps immediately | no-op, correct |
| **Issue #22 analogue: logout in void, login, teleported by another mod, dies, respawns outside void** | may not fire (event depends on the other mod's teleport path and respawn ordering) | catches on the tick after respawn: player is outside void, flag is still stashed, restores survival |
| **Issue #25 analogue: cross-dimension respawn fires both `AFTER_RESPAWN` and `AFTER_PLAYER_CHANGE_LEVEL`** | first call swaps and sets flag, second call sees invariant holds, no-op | no-op, correct |
| **MC-258705 analogue: player has inventory open with cursor item during dimension change** | `closeContainer` runs before snapshot, cursor item returns to inventory, snapshot captures it | n/a |
| Player in void, another op manually clears their inventory | n/a | no-op (the backup is untouched; the live inventory being wrong is the player's problem, not a survival loss) |
| Two mods both try to manage inventory | n/a | no-op. If the other mod restores survival items into the live inventory while in void, the next tick does not notice (it only checks the flag, not item contents). See 11.9 for the tag-based belt-and-braces check. |
| `DungeonLog` file corrupted or deleted | n/a | n/a. Lost and Found log (11.10) is the recovery source. |

The issue #22 row is the reason the tick layer exists. Without it, a
player whose dimension change event does not fire (because another mod
teleported them, or because the event fired on the wrong entity during
respawn, or because a Fabric API change altered event firing order) loses
their survival inventory permanently. The tick layer catches it within
one tick of the player being in the wrong state, because it does not
depend on any event firing at all. It only depends on the player's
current dimension and the persisted flag.

The issue #25 row is the reason the flag is the deduplication. Without
it, a double-firing event runs the swap twice and duplicates or loses
items. The flag makes the second fire a no-op.

The MC-258705 row is the reason `closeContainer` runs before the
snapshot. Without it, the cursor item is silently destroyed.

The only way to lose survival items is if `DungeonLog` itself is
corrupted or deleted, which is the same failure mode as losing a
keystone level or fuel balance. The backup is no more fragile than any
other per-player state the mod already trusts `SavedData` with. Section
11.10 adds a Lost and Found log as the last-resort recovery path for that
case, following the pattern dimensional-inventories established.

### 11.8 The bag, applied on door pick

The reconciliation handles survival versus void. The bag is a separate,
simpler operation that happens when the player picks a door:

1. Player is in the room (in void, stashed, keystone in slot 0, rest
   empty).
2. Player right-clicks a door. The door's dialog names the bag.
3. On confirmation, `generateBag(player, bagId)` runs the bag's loot
   table and places the results in the player's hotbar and main
   inventory, leaving the keystone in slot 0.
4. The dungeon generates behind the lobby, the seal opens, the player
   walks in with the bag.

The bag is **not persisted**. If the player disconnects mid-run, their
live inventory (bag items plus anything picked up) is saved by vanilla's
player save. On rejoin, they are in void, stashed, and the
reconciliation sees the invariant holds. They keep whatever they had. If
the run was torn down while they were offline, they are in their room
with their bag items and can exit normally (items delivered to room,
survival restored).

If the player exits void and re-enters for a new run, the bag is
generated fresh. The old bag is gone (delivered to room on exit). This is
correct: the bag is per-run, the survival inventory is per-player.

### 11.9 Belt and braces: item tagging

The reconciliation above trusts the `survivalStashed` flag, not item
contents. For the case where another mod or a vanilla mechanic injects
items into the live inventory mid-run, add a secondary check:

Every bag item and every item generated by in-run loot tables carries a
`pocketdungeons.bag` byte in its `CUSTOM_DATA` component, set by loot
functions. This is the same tagging pattern M13 already uses for
`pocketdungeons.tier` on gear.

The secondary check, run as part of the leaving branch:

1. Before restoring survival, scan the live inventory for any stack
   **without** the `pocketdungeons.bag` tag and **without** the keystone's
   own tag. These are items that appeared in the void inventory without
   the mod's involvement.
2. Deliver those to the room (they might be legitimate room items the
   player was holding) and log a warning.
3. Restore survival.

This catches the "another mod put a netherite sword in my inventory while
I was in a dungeon" case without ever touching the survival backup. The
backup is restored whole; the unexpected items are diverted to the room.

The tag is also how the leaving branch knows what to deliver to the room
versus what to silently clear: bag-tagged items and the keystone go to
the room; untagged items go to the room with a warning (they should not
be there, but they might be legitimate).

### 11.10 Lost and Found: the last-resort recovery layer

Borrowed from Thomilist/dimensional-inventories, which ships exactly this
pattern. The idea: even with the tick invariant, `SavedData` can corrupt,
a disk can fill, an admin can delete a file. When that happens, the
survival inventory is gone from the primary store. The Lost and Found is
the secondary store: a plain-text log file written at every swap, holding
the full inventory contents, recoverable by hand.

**What gets logged.** Every time the entering or leaving branch runs
(either from the event handler or the tick pass), before the live
inventory is cleared, write a log entry to
`world/data/pocketdungeons/lostandfound/<uuid>/<timestamp>.log`:

```
--- BEGIN LOST+FOUND METADATA ---
2026-08-31T14:23:01.234Z
ENTERING pocketdungeons:void
player: PlayerA
uuid: 550e8400-e29b-41d4-a716-446655440000
keystone level: 14
--- END LOST+FOUND METADATA ---

--- BEGIN LOST+FOUND CONTENT ---
slot 0: minecraft:diamond_sword x1 {enchantments:{sharpness:5,unbreaking:3}}
slot 1: minecraft:cooked_beef x32
slot 2: (empty)
...
slot 36: minecraft:netherite_boots x1 {enchantments:{protection:4}}
slot 37: (empty)
slot 38: (empty)
slot 39: (empty)
slot 40: minecraft:shield x1
--- END LOST+FOUND CONTENT ---
```

For the leaving branch, the cause line is `LEAVING pocketdungeons:void`
and the content is the void inventory being delivered to the room.

**Why text, not NBT.** The whole point is recoverability when everything
else is broken. A `.dat` NBT file that cannot be parsed is useless. A
text file that cannot be parsed is still readable by a human who can
`/give` the items back. The slot-by-slot format is verbose (41 lines per
entry) but unambiguous, and a server with 20 active players generates at
most 40 entries per hour (one entry per swap in, one per swap out), so
volume is not a concern. The log includes 42 slots (41 inventory plus
cursor) to match the snapshot format.

**Retention.** Keep the last 20 entries per player (a ring buffer, same
shape as GoidaInvRestore's `maxRecordsPerPlayer`). Older entries are
deleted on the next write. This bounds disk usage without losing the
recent history that matters for recovery.

**When to alert.** If the entering or leaving branch throws an exception
(disk full, file locked, `SavedData` write fails), the Lost and Found
write happens first, so the log is on disk before the crash. The player
is sent a red chat message: "An inventory error occurred. Your items
have been logged for recovery. Contact server staff." This is
dimensional-inventories' `DATA_LOSS_MESSAGE` pattern, adopted verbatim in
spirit.

**The pre-restore safety net.** Borrowed from GoidaInvRestore: before
the leaving branch restores the survival backup, it writes a Lost and
Found entry of the current void inventory. If the restore itself fails
halfway (the backup is corrupt, a slot copy throws), the void inventory
that was about to be replaced is already on disk. Restores are
reversible.

**What this does not do.** The Lost and Found is not an automatic
restore. It is a log for a human to read and manually `/give` from. An
automatic restore from a text log would require parsing item
specifications back into `ItemStack`s, which is the same complexity as
the swap itself and defeats the purpose of having a separate recovery
path. The operator reads the log, identifies the lost items, and restores
them by hand. This is rare enough (it requires `SavedData` corruption)
that manual recovery is acceptable.

### 11.11 The ender chest

The ender chest is a leak path: a player with netherite gear in their
ender chest can access it from the room and bypass scarcity entirely.
This is not a stash-and-swap problem (the ender chest is separate from
the player inventory), but it is the most obvious workaround for the
scarcity the bag creates.

Proposed fix, cheapest first: prevent `minecraft:ender_chest` from being
opened inside `pocketdungeons:void` by cancelling the right-click in the
same `UseBlockEvent` handler the mod already uses for stations. The
ender chest block can still exist in the room for decoration; it just
does not open. This is one check, no mixin, no custom block.

If the ender chest is needed for room storage (it is currently open to
visitors per `VISION.md` 3.1), replace it with a named barrel that
respects the room's permission mask. The ender chest's cross-dimensional
property is exactly what makes it a leak.

### 11.12 What this does not handle

- **Curios / trinket slots from other mods.** If another mod adds
  inventory slots (baubles, curios, trinkets), the 41-slot snapshot does
  not cover them. A player wearing a curio that gives flight in survival
  keeps it in the dungeon. This is acceptable for now: the mod is
  server-side-only and does not assume other mods' inventory slots
  exist. If it becomes a problem, the snapshot grows to cover whatever
  slot registry the other mod exposes. GoidaInvRestore (NeoForge) proves
  the pattern: it checks for Curios, Cosmetic Armor Reworked, and
  Sophisticated Backpacks at snapshot time and includes their slots. The
  same conditional-inclusion approach works here when the need arises.
- **Experience and hunger.** These are not inventory and are not
  stashed. A player who enters void with full hunger and 30 levels keeps
  them. This is fine: the bag includes food, and XP is not a scarcity
  lever the design pulls.
- **The room's own containers.** Chests and barrels in the room are part
  of the room blob, not the player inventory. They are unaffected by the
  swap. A player can store items in room chests, exit, re-enter, and the
  items are still in the chests. This is correct and is how decorating
  works.

### 11.13 Implementation footprint

| What | Where | Size |
|---|---|---|
| `survivalStashed`, `survivalBackup` fields | `DungeonLog.Entry` + codec | 2 fields, same pattern as `fuel` |
| Event handler | `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` registration in `Instances.register()` | ~20 lines, calls the same swap logic as the tick pass |
| Tick reconciliation pass | New method in `Instances`, called from the existing `END_SERVER_TICK` lambda and the `JOIN` handler | ~40 lines |
| `snapshot` / `clearInventory` / `restoreInventory` / `closeContainer` | New helper class `InventorySwap` | ~80 lines, 42-slot copies including cursor item handling |
| `deliverToRoom` | Extends the existing reward delivery path in `RunLifecycle` | ~30 lines, same `Payout.deliver` pattern |
| Lost and Found logger | New class `LostAndFound`, writes text logs to `world/data/pocketdungeons/lostandfound/<uuid>/` | ~80 lines, ring buffer of 20 entries per player |
| `pocketdungeons.bag` tag | Loot functions on bag tables and in-run chest tables | Data, no Java |
| Ender chest block | `UseBlockEvent` cancellation in void | ~5 lines |

No mixins. No client mod. No custom items. No new dimensions. The entire
mechanism is one `SavedData` field pair, one event handler, one tick-pass
method, one inventory helper class, one log class, and one block
cancellation, wired into hooks that are already registered or are
standard Fabric API.

---

## 12. The loop: staging rooms, dungeons, and safe visits

### 12.1 Why

Barony's floor loop is the part of that game worth stealing whole. A
floor is a map, not a corridor. Somewhere on it is the ladder down. The
player explores until they find it, and from that moment every other
room on the floor is *optional*: loot it, or take the ladder with what
you have. Skipping costs loot you will want on the next floor, not the
run. The decision the player makes all game is "is this room worth it,
given I can leave right now for free."

PD today has no costless forward option. Every cell on the critical path
must be solved, and the only escapes (walk back to the room,
`/dungeon exit`) abandon the run. Section 4's open/gated split and
section 5.4's free dwelling in cleared cells are half of the fix. The
other half is structural: the run has to be long enough that "skip now,
pay later" has a later to pay in, and each floor has to be wide enough
that the staging room at the end is found, not walked to.

### 12.2 The loop

The game is a continuous loop. There is no "expedition" as a discrete
data structure; there is just "what floor am I on" and "is the safe
room loaded right now."

```
safe room  ->  staging  ->  dungeon 1  ->  staging  ->  dungeon 2  ->  staging  ->  dungeon 3  ->  staging  ->  dungeon 4  ->  staging  ->  safe room  ->  repeat
```

At any given moment, exactly one of these is loaded:

- **Safe room visit**: the safe room is loaded. No dungeon, no staging
  room. The player sorts loot, uses stations, builds.
- **Dungeon floor**: one dungeon floor and one staging room (at its end)
  are loaded. The safe room is despawned. The player explores the
  dungeon, finds the staging room, and the party selects the next door.

The staging room is the hatch. There is no separate hatch cell, no
trapdoor, no pit. The staging room is a 16 x 16 room placed at the
terminal of the dungeon's critical path (section 6.5). It has three
selection doors, the Herobrine Cube socket, and dungeon selection
options (affix preview, theme preview, bag preview). The player finds
it by exploring the floor, the same way Barony's player finds the
stairs down.

Every Nth staging room (configurable, `floorsPerSafeVisit`, default 5)
is a **safe staging room**: instead of three dungeon doors, it has one
**safe door**. Selecting the safe door stamps the safe room and
teleports the party into it. The dungeon and staging room are purged.
The player is now in a safe room visit.

The flow:

1. **Safe room visit.** The safe room is loaded. The player sorts loot,
   uses stations, builds. No time pressure, no omen, no dungeon.
2. **Enter staging.** When ready, the party walks through a doorway in
   the safe room into a staging room stamped adjacent to it. The
   staging room waits until **every party member** is inside it.
   Players still in the safe room see a chat line: "Waiting for [names]
   to enter the staging room." A player who walks back into the safe
   room resets the wait. Solo players skip the wait entirely.
3. **Commit.** The host selects and commits a door. The safe room is
   saved via `RoomStore` and despawned (forced chunk tickets released).
   A new dungeon generates with a fresh staging room at its terminal.
   The party is teleported to the dungeon's entrance cell. The bag is
   applied (section 11.8; see open question 12 for whether the bag
   persists across floors or is re-chosen each staging room).
4. **Play the floor.** Explore, solve or skip situations, find the
   staging room at the end. Backtracking is allowed (section 2).
   Cleared cells and the staging room are free of dwelling omen (5.4).
5. **Next floor or safe room.** The party congregates in the staging
   room. The host selects a door. If this is a dungeon staging room,
   the old dungeon despawns and a new one generates with a fresh
   staging room at its terminal. If this is a safe staging room (every
   Nth), the host selects the safe door, the safe room stamps, and the
   party is teleported into it. Back to step 1.

### 12.3 What the player is doing on a floor

1. Enter. The entrance cell is open and empty.
2. Explore. Every doorway shows what is behind it (section 4 rule). Open
   cells can be crossed without engaging. Gated cells cannot.
3. Find the staging room. It is at the terminal of the critical path
   (section 6.5), behind at least one gated cell.
4. Decide. Standing in the staging room at omen 0 with a half-empty
   bag: go back for the Barred Vault two cells behind (known fight,
   known reward, free to retreat to the staging room if it goes
   wrong), or select the next door.
5. Select. The party congregates, the host commits, the old floor
   despawns, the next floor (or safe room) opens.

Step 4 is the game. It needs three things the spec now provides:
backtracking is physically allowed (section 2), the staging room and
cleared cells are free of dwelling omen (5.4), and the spur's contents
were readable from its doorway before the player ever committed
(section 4).

### 12.4 Cost and caveats

This is the largest structural change in the spec and it should be
called that.

- **Session length.** Four to five floors of 8 to 12 cells is 32 to 60
  cells. A run today is 8 to 12. Even with most cells open and
  skippable, expect 25 to 50 minutes between safe room visits. The
  test group plays on a live survival world; make the floor count a
  config value (`floorsPerSafeVisit`, default 5) and start playtesting
  at 3.
- **Only one floor loaded at a time.** When the host commits a door in
  a staging room, the old dungeon despawns and the new one generates.
  This is cleaner than the previous "expedition" model which kept a
  staging room loaded for the whole run. Now there are exactly two
  things loaded during a dungeon floor: the floor itself and its
  terminal staging room.
- **Mid-loop persistence.** A disconnect on floor 3 must resume on
  floor 3, with floor 3 and its staging room still standing.
  `InstanceRegistry` and the join-recovery path in `Instances` already
  hold an instance for an offline member; the record grows a
  `floorIndex`. A server restart mid-loop is the same problem the
  current single-run instance already has and is solved the same way
  (or not; see open question 9).
- **`/dungeon exit` mid-loop** abandons the current run. Inventory is
  delivered to the safe room by the stash-and-swap leaving branch
  (11.6), so nothing is lost, but the keystone does not bank. This is
  the one place the spec keeps a real cost for leaving, and it is the
  reason the staging room has to be a genuinely safe place to stop and
  think.
- **Death ejection** mid-loop is the same as `/dungeon exit` under the
  current no-penalty test rule. When death cost returns (`VISION.md`
  3.4), "how many floors deep" is the obvious scalar.
- **Keystone per safe visit, not per floor.** One level for finishing
  all floors between two safe room visits, computed from summed omen
  (5.4). Finishing floor 3 and leaving banks nothing. This is
  deliberate: banking per floor turns the loop back into short runs
  with a loading screen between.
- **Generator work** is section 6.5. **Lifecycle work** is a new
  `advanceFloor` in `RunLifecycle` that reuses `completeRun`'s first
  half and `Instances.create`. **No new dimension, no new block, no
  mixin.** The staging room is a template; the party-wide gate is
  detected by the same watcher that detects pad contact today.

### 12.5 What this does to the example runs

Section 8's runs are written as single floors. They stay valid as
descriptions of *one floor of* the loop; 8.1 "Plumber, tier 1, solo" is
floor 1 of a Plumber run. What they do not yet show is the cross-floor
decision (skip the Pot Room on floor 2 because the Plumber bag is
already down to one bucket and floor 4 is Deep Dark). One full
five-floor example run should be written once the first three floors
are playable, not before.

### 12.6 The safe room and the staging room

Today the room and the selection doors are one cell. `stampRoomShell`
stamps the owner's saved room, the selector doors, the lodestone, and
the furniture in a single 16 x 16 footprint. `resetForNextDungeon` saves
the room, clears the dungeon cells around it, and re-seals. The room
persists across runs, but it stays loaded the entire time the dungeon is
active, and nothing stops a player from walking back into it mid-run.

With scarcity and the continuous loop that design leaks in two ways.
First, the stash is reachable mid-run, so scarcity has a back door.
Second, the room is loaded for the whole run, which is 25 to 50 minutes
of chunk tickets spent on a cell the player is not supposed to be in.

The split:

- **Safe room** (the current room cell): persistent, player-owned, holds
  stashes, item upgrades, stations, and whatever the player has built
  there. Block edits accumulate across visits; `resetForNextDungeon` no
  longer touches it. The selection doors, the Herobrine Cube socket, and
  the dungeon selection furniture are removed from this cell. This cell
  is saved and **despawned** (tickets released, chunk unloaded) the
  moment the host commits a door in the adjacent staging room. It is
  re-stamped from `RoomStore` when the party selects a safe door in a
  safe staging room.
- **Staging room** (a fresh cell each time): the dungeon's terminal room
  and the only place a door can be selected. Holds the three selection
  doors (or one safe door, on every Nth staging room), the Herobrine
  Cube socket, and dungeon selection options. It has no stash and no
  items. It is created in two ways:
  - **From the safe room**: stamped adjacent to the safe room when the
    party is ready to start a run. The doorway between them is open
    until the host commits a door.
  - **At the end of a dungeon floor**: generated as the terminal cell of
    the dungeon's critical path (section 6.5). The party finds it by
    exploring. This replaces the old "hatch cell" concept. There is no
    hatch, no trapdoor, no pit. The staging room IS the exit.

What is loaded at any given time:

```
Safe room visit:
  [loaded]
  safe room

Dungeon floor:
  [loaded]           [loaded]
  dungeon floor N <-> staging room (terminal)

  (safe room is despawned)
```

The flow in detail:

1. **Safe room visit.** The safe room is loaded. No dungeon, no staging
   room. The player sorts loot, uses stations, builds.
2. **Enter staging.** When ready, the party walks through a doorway in
   the safe room into a staging room stamped adjacent to it. The
   staging room waits until every party member is inside it. Players
   still in the safe room see a chat line. Solo players skip the wait.
3. **Browse.** The host selects a door (copper lantern lights above
   it). The first room of that dungeon generates and the door is
   replaced with an unbreakable window looking into it. The party can
   read the situation before committing. See section 12.7.
4. **Commit.** The host pulls the lever. The safe room is saved via
   `RoomStore` and despawned. The rest of the dungeon generates. The
   window becomes the entrance, the staging room opens into the
   dungeon, and the party walks in. The bag is applied (section 11.8).
5. **Play the floor.** Explore, solve or skip situations, find the
   staging room at the end.
6. **Next floor.** The party congregates in the staging room. The host
   browses doors (12.7), pulls the lever. The old dungeon despawns. A
   new dungeon generates with a fresh staging room at its terminal. The
   party is teleported to the new entrance. Inventory carries. Nothing
   is delivered to the safe room.
7. **Safe door.** On every Nth staging room, there is one safe door
   instead of three dungeon doors. The host selects it. The safe room
   is stamped from `RoomStore`. The party is teleported into it. The
   dungeon and staging room are purged. Back to step 1.

Disconnect and reconnect:

- **During a safe room visit** (no active dungeon): the player
  reconnects into the safe room.
- **During a dungeon floor** (dungeon active, safe room despawned): the
  player reconnects into the staging room at the dungeon's terminal, or
  into the dungeon entrance if the staging room has not been reached
  yet. This is the same join-recovery path that today returns a
  disconnected player to the instance.

What this changes in the code:

- `stampRoomShell` splits into `stampSafeRoom` (room + bedrock envelope,
  no selector doors, no furniture) and `stampStagingRoom` (selector
  doors or safe door, lodestone, furniture, Herobrine Cube socket, no
  saved room blob).
- `resetForNextDungeon` is removed or renamed. The safe room is no
  longer reset between runs; it is despawned and re-stamped. The
  dungeon cells are purged when the next floor generates, not as part
  of a room reset.
- `completeRun` is replaced by two paths: `advanceFloor` (old dungeon
  despawns, new dungeon + staging room generate) and `returnToSafe`
  (safe room stamps, dungeon + staging room purge). The current
  "reward room" and "door offer" steps move into the staging room.
- A new `beginRun` gate handles the first door selection from the safe
  room's adjacent staging room: it checks that all party members are in
  the staging room, saves and despawns the safe room, then generates
  floor 1 with its terminal staging room.
- `InstanceRecord` grows a `stagingCellOrigin` alongside the existing
  `roomCellOrigin`, and a `floorIndex` to track how many floors have
  been cleared since the last safe room visit. The safe room's origin
  is what `RoomStore` saves and restores; the staging room's origin is
  the terminal of the current dungeon.
- Join recovery checks whether an active dungeon exists. If yes, the
  player lands in the staging room or dungeon entrance. If no, the
  player lands in the safe room.

Why the party-wide gate matters: without it, one player staying in the
safe room keeps it loaded, and the scarcity back door stays open. The
gate makes "we are committing" a real decision, not a default. It also
matches the floor-advance party rule: the loop moves everyone, and so
does the entry into it.

### 12.7 Door preview: the first room window

Section 4's "readable from the doorway" rule lets the player assess a
situation before stepping into it. The staging room extends the same
principle to the dungeon itself: before the party commits to a floor,
they can see what the first room looks like.

The codebase already has the browse-and-commit flow: `RitualListener`
intercepts a right-click on a selector door, lights the copper bulb
above it (`RoomTemplateGenerator.bulbAlongForStep`), and puts the
offer on the door screen. The commit lever beside the third door
starts the run. Today the "preview" is a text display. This spec
extends it to a physical preview.

How it works:

1. **Select.** The host right-clicks a selection door. The copper bulb
   above it lights (existing behaviour). The door is now "selected"
   but not committed.
2. **Preview.** Only the first room (the entrance cell) of that
   dungeon generates. The selection door is replaced with an
   unbreakable window looking into that first room. The window
   material follows the same rule as doorway visibility in section 4:
   iron bars if the room's contents should be visible but contained
   (mobs, projectiles), tinted glass if light or fluid must be
   contained (dark rooms, flooded rooms). Bedrock-backed so it cannot
   be broken. The party can see the situation, the theme, the
   lighting, and any visible hazards or mobs through the window.
3. **Switch.** The host right-clicks a different door. The previous
   preview room is purged, the window reverts to a door, the previous
   bulb darkens, and the new door's first room generates in its place.
   The party can browse all three doors before committing.
4. **Commit.** The host pulls the lever. The rest of the dungeon
   generates behind the previewed first room. The window becomes the
   entrance (the glass is removed, the opening becomes a walkable
   doorway). The party walks in. The bag is applied.

What this costs:

- **One extra cell generated per door selection.** The entrance cell
  is generated on select and purged on switch or commit. At most one
  preview cell exists at a time. This is cheap: one 16 x 16 stamp and
  one purge per door browse.
- **The entrance cell must be deterministic from the door's seed.**
  The first room is generated from the same seed that will generate
  the full dungeon, so the previewed room is the actual entrance the
  party walks into on commit. No bait-and-switch.

What this changes in the code:

- `chooseLobbyDoor` (or its successor in the staging room) splits into
  two phases: `previewDoor` (generate entrance cell, replace door with
  window) and `commitDoor` (generate the rest of the dungeon, replace
  window with doorway).
- The entrance cell is stamped from the dungeon's seed at the
  `previewDoor` step. On `commitDoor`, the rest of the layout generates
  around it without re-stamping the entrance.
- The window is a temporary block placement (iron bars or tinted
  glass, chosen by the same containment rule as section 4, with
  bedrock behind) that is removed on commit. It uses the same
  `BedrockEnvelope` mechanism that seals doors today.
- Switching doors purges the current preview cell and stamps the new
  one. The lever is disabled while a preview is generating.
- The existing `RitualListener` door-click branch grows the
  `previewDoor` call; the existing lever-pull branch grows the
  `commitDoor` call. The bulb lighting and darkening logic stays.

Why this matters: the staging room is a commitment point. Without a
preview, the party is choosing blind, and the "readable from the
doorway" principle stops at the dungeon entrance. With a preview, the
party can see the theme, the first situation, and the lighting before
they spend a floor on it. This is the same information Barony gives by
letting the player open a door and look before stepping through.

---

## 9. Open questions

1. **Stash-and-swap and the ender chest.** A player's ender chest is
   accessible in the safe room. Does it stay accessible mid-run? If yes,
   scarcity leaks. Proposal: the void dimension blocks ender chest
   opening outside the safe room cell. The safe room is despawned during
   dungeon floors (12.6), so this is mostly a belt-and-braces check
   against the staging room and any loaded dungeon cells.
2. **Party bags.** Same bag per player, or one shared bag split across the
   party? Same bag is simpler and section 8.3 assumes it. A shared bag
   (one water bucket for four players) is more interesting and much more
   argument-prone. Playtest both.
3. **Leftover tools at the pad.** A trial key or a boat carried out is a
   decoration in the safe room today. Should the safe room's own vault
   accept a carried-out key? That gives carried tools a second life and
   makes "what do I bring home" a decision. Cheap; the vault block is
   already placed.
4. **Height.** Everything here is horizontal because cells are 16 x 16 x
   6. A two-cell-tall variant (`footprint` is already reserved for
   multi-cell) would unlock shafts, drops and the levitation rooms this
   spec had to leave out. Not needed for the first catalogue.
5. **Omen visibility.** The design says no bar, no message. If playtesting
   shows players cannot connect their dawdling to the ominous spawners,
   the fallback is a single ambient sound on each omen increment, not a
   number.
6. **Staging room chest.** The old hatch cell had a chest with a 1 in 8
   mimic rule. The staging room replaces the hatch. Does the staging
   room have a chest? If yes, keep the mimic rule. If no, the staging
   room is purely a selection room with no loot. Lean toward no chest:
   the staging room is a decision point, not a reward, and loot belongs
   in the dungeon cells the player explored (or skipped) on the way
   there.
7. **Three-way test coverage and item-return audit.** Section 0
   proposes that every hazard should be at least two of hazard, weapon,
   resource. Section 6.4 requires every gated template that takes an
   item to return it after solving. Both rules are written into the
   catalogue but have not been audited cell by cell. Before the first
   catalogue ships, run each template through both tests and either
   rewrite or document the exception.
8. **Floor count and floor size.** Section 12 says `floorsPerSafeVisit`
   default 5, floors of 8 to 12 cells. Both are config. The real
   constraint is session length for the test group; 3 floors of 8 is the
   starting point and the numbers go up only if runs feel short.
9. **Server restart mid-loop.** Floors are instances in
   `InstanceRegistry`. Does a restart rebuild floor 3 from its seed with
   the party at the entrance (loses floor progress, keeps inventory via
   stash-and-swap), or does the run simply end with inventory delivered
   to the safe room? The second is simpler and safe; the first is
   nicer. Decide before building `advanceFloor`.
10. **Can the player go back to a previous floor?** Once the host
    commits a door in a staging room, the old dungeon despawns. If
    floor 2 had a Barred Vault the player skipped, they cannot return
    for it from floor 3. That is the intended pressure, but confirm in
    playtest that it reads as a decision and not as a trap.
11. **Open cells and the spawner-clear gate.** `completeRun` today
    refuses the pad until `spawnerClearThreshold` is met. If most cells
    are open and skippable, that gate contradicts section 12. Proposal:
    the threshold counts only `gated` encounter cells on the shortest
    entrance-to-staging-room path, which by construction the player has
    already cleared.
12. **Does the bag persist across floors or is it re-chosen at each
    staging room?** The previous expedition model chose the bag once
    for the whole run. The new loop has a door selection at every
    staging room. Two options: (a) the bag is chosen at the first
    staging room (from the safe room) and persists until the next safe
    room visit; the doors at inter-floor staging rooms only select
    dungeon parameters (level, affix, theme), not the bag. (b) The bag
    is re-chosen at every staging room, which gives more player agency
    but breaks cross-floor scarcity (the player can swap from Plumber
    to Sapper mid-run). Option (a) preserves the scarcity design in
    section 3; option (b) is more flexible but less tense. Default to
    (a) until playtesting says otherwise.

---

## 10. Build order

Each step is playable on its own and is a natural milestone boundary.

1. **Stash and swap (section 11).** `DungeonLog` fields, event handler on
   `AFTER_PLAYER_CHANGE_LEVEL`, tick reconciliation pass in the existing
   tick hook, `InventorySwap` helper (42 slots including cursor), Lost and
   Found logger, ender chest block. No bags yet: the player enters void
   with an empty inventory (plus keystone) and plays the existing dungeon
   with whatever they find in chests. Confirm survival inventory is
   preserved across every exit path (pad, `/dungeon exit`, death ejection,
   disconnect, `/execute in` teleport) before adding anything else.
   Verify the Lost and Found log writes correctly by corrupting a
   `DungeonLog` entry on purpose and confirming the log file has the
   inventory.
   
   **Gametest (mandatory for this step).** Following
   dimensional-inventories' `DimensionPoolChangeOnRespawnTest` pattern,
   write a gametest using Carpet's fake player that verifies:
   - Player enters void, survival is stashed, inventory is empty plus
     keystone.
   - Player exits void, survival is restored exactly (42 slots, including
     cursor item placed back).
   - Player enters void, disconnects, reconnects, is teleported out by
     `/execute in minecraft:overworld run tp`, and survival is restored
     on the next tick (the issue #22 scenario).
   - Player enters void, is killed (death ejection fires), respawns
     outside void, survival is restored (the issue #25 scenario, verify
     it does not duplicate).
   - Player enters void with inventory open and cursor item held, cursor
     item is preserved (the MC-258705 scenario).
2. **Bag plumbing.** `bags/*.json` loot tables, bag application on door
   pick, `pocketdungeons.bag` tag on loot functions, door dialog names
   the bag. Stack-cap loot functions on the existing chest tables. Play
   the existing dungeon with a Mason bag and confirm scarcity changes
   behaviour.
3. **Wide floor, terminal staging room (section 6.5, 12.2, 12.7).**
   Generator parameters up, terminal placed off the end of the critical
   path, line-of-sight check in `validate`, staging room template at the
   terminal (three selection doors, no hatch, no trapdoor, no pit).
   Door preview: selecting a door generates the entrance cell and
   replaces the door with an unbreakable window; pulling the lever
   generates the rest and opens the entrance. Still a single floor:
   the lever runs `completeRun` and goes home. This step is playable
   with the existing encounter and loot cells and answers the first
   question that matters: is *finding* the staging room fun in a 16 x
   16 cell grid, or is it a walk? Everything after this step assumes
   yes.
4. **Six templates, one per category.** Chasm, Frame Lock, Pot Room, Hold
   the Plate, Barred Vault (spur), Bazaar. All buildable in the M23 room
   editor. `content` dispatch for Bazaar and Barred Vault; the other four
   are pure templates. Each tagged `open` or `gated`, each readable from
   its doorway.
5. **Provides/requires graph pass (section 6.6).** Expose BFS depth
   from entrance on `DungeonShape`, process cells in BFS order with
   cumulative `available`, backtracking search when a cell has no
   candidate (same pattern as `generateCriticalPath`, reusing
   `MAX_BACKTRACK_STEPS`), fallback to empty-`requires` room only when
   backtracking exhausts. Pilgrim pure-JDK test: empty bag, party size
   1, every gated cell still solvable. `access` placement rule from
   6.1.
6. **Omen accumulation** replacing the clock gate. Drop the boss bar.
   Reward chests by omen. Dwelling counts only in uncleared cells (5.4).
   Keep `RunTimer` recording.
7. **Safe room / staging room split (section 12.6).** Split
   `stampRoomShell` into `stampSafeRoom` and `stampStagingRoom`, move
   selector doors and furniture into the staging room, add the
   `stagingCellOrigin` to `InstanceRecord`, implement the party-wide
   `beginRun` gate (host commits door, safe room saved and despawned,
   dungeon generates with terminal staging room), re-stamp the safe
   room when the safe door is selected, update join recovery to route
   reconnects to the staging room during an active dungeon and to the
   safe room otherwise. Remove or rename `resetForNextDungeon`; the safe
   room is no longer reset between runs. Must be done before the test
   group sees multi-floor loops, otherwise the stash is reachable
   mid-run and scarcity does not hold.
8. **Floor chaining (section 12).** `advanceFloor` in `RunLifecycle`,
   `floorIndex` on the instance record, `floorsPerSafeVisit` config
   defaulting to 3 for the test group, per-floor omen summed at the
   safe door, join recovery aware of the floor index, safe staging room
   with safe door on every Nth floor. Decide open question 9 first.
   Depends on step 7.
9. **The rest of the catalogue**, two or three templates a milestone,
   playtesting between. Write the first full five-floor example run
   (12.5) once three floors are playable.
10. **Cube recipes**, once the catalogue is wide enough for a recipe to
    meaningfully steer it.
