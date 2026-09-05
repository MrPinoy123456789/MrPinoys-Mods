# Situations catalogue audit

Answers spec open question 7. Audited at base commit `76b4553`, before the
first catalogue ships, so M50 to M53 can author from the table rather than
from prose.

**What was audited.** Every situation in `SITUATIONS_SPEC.md` section 4
(4.1 traversal, 4.2 mechanism, 4.3 knowledge, 4.4 combat variants, 4.5
spurs) plus the three pressure rooms in 5.1. Thirty four situations.

**Against what.**

1. **The three-way test** (spec 0). Each hazard must be at least two of:
   hazard to the player, weapon the player turns on mobs, resource the
   player carries forward. Recorded as H, W, R. Rooms with no hazard at
   all (locks, trades) are marked `n/a`, not failed.
2. **The item-return rule** (spec 6.4). Any gate whose solution puts an
   item into a container returns that item past the door. Load-bearing
   for 6.6: `available` is a boolean set of tags and stays sound only
   while gates return their inputs.
3. **Readable from the doorway** (spec 4 preamble). Visible before the
   player commits, through the 2 x 3 opening or through an iron bar,
   glass or tinted glass window.

**Also judged against what a template can actually express**: a 16 x 16 x 6
cell with a 14 x 14 x 5 interior, doorways 2 wide and 3 tall at floor
level on slots 7 to 8, sub-floor bedrock at y = -1 (`BedrockEnvelope`),
server-side only, vanilla redstone, no skylight. Those constraints kill
several things the catalogue assumes; see section 5.

Tags come from the closed vocabulary: `blocks`, `water`, `lava`, `lead`,
`mob`, `trial_key`, `boat`, `gold`, `snowballs`, `shears`, `pearl`,
`wind_charge`, `milk`, `bow`, `redstone`. `requires` is an AND under spec
6.6, so every list here is short on purpose.

---

## 1. Master table

Tiers marked with an asterisk are assigned by this audit: sections 4.4 and
4.5 ship no tier annotation.

| Situation | Section | Tier | Access | Provides | Requires | Pressure | Build | Three-way | Item-return | Visibility |
|---|---|---|---|---|---|---|---|---|---|---|
| Chasm | 4.1 | 1 | open | `lava` | - | none | template | H, W, R | N/A | doorway |
| Flooded Hall | 4.1 | 1 | open | `water` | - | local | template | H, R | N/A | glass |
| Powder Snow Field | 4.1 | 2 | open | - | - | local | template | H, W, R | N/A | doorway |
| Thicket | 4.1 | 1 | open | - | - | local | template + spawner | H, W, R | N/A | doorway |
| Ice Run | 4.1 | 2 | open | `wind_charge` | - | local | template + spawner | H, W, R | N/A | doorway |
| Frame Lock | 4.2 | 1 | gated | - | - | omen | template | n/a | NEEDS FIX | doorway |
| Rotation Lock | 4.2 | 2 | gated | - | - | none | template | n/a | N/A | doorway |
| Plate Pair | 4.2 | 1 | gated | - | `mob` | none | template | n/a | N/A | doorway |
| Item Plate | 4.2 | 1 | gated | - | - | none | template | n/a | OK | doorway |
| Gallery | 4.2 | 2 | gated | `blocks` | - | none | template | H, W | NEEDS FIX | doorway |
| Tripwire Hall | 4.2 | 1 | open | - | - | local | template | H, W, R | N/A | doorway |
| Flow Puzzle | 4.2 | 2 | gated | `blocks`, `water` | - | none | template | n/a | NEEDS FIX | doorway |
| Pot Room | 4.2 | 1 | open | `trial_key` | - | omen | template | H, R | N/A | doorway |
| Bazaar | 4.3 | 2 | open | `gold` | - | none | template | H, R | N/A | doorway |
| Don't Look | 4.3 | 2 | open | - | - | none | template | H, R | N/A | tinted glass |
| The Herd | 4.3 | 1 | open | - | - | none | template | H only, FAIL | N/A | doorway |
| Deep Dark Landing | 4.3 | 3 | open | - | - | omen | template | H, W | N/A | tinted glass |
| Elder's Chamber | 4.3 | 3 | gated | `water` | - | local | template | H, R | N/A | glass |
| Blaze Loft | 4.3 | 2 | open | `lava` | - | none | template | H, W, R | N/A | doorway |
| Infested Wall | 4.3 | 2 | gated | `blocks` | - | none | template | H only, FAIL | N/A | doorway |
| Breeze Arena | 4.4 | 2* | gated | `wind_charge`, `lava` | - | none | template + spawner | H, W, R | N/A | doorway |
| Bogged Marsh | 4.4 | 2* | open | - | - | local | template + spawner | H, W | N/A | doorway |
| Ledge Archers | 4.4 | 1* | open | - | - | local | template + spawner | H, R | N/A | doorway (internal bars) |
| The Raid | 4.4 | 3* | gated | - | - | none | template + spawner | H, R | N/A | doorway |
| Slime Pit | 4.4 | 1* | open | - | - | none | template + spawner | H, R | N/A | doorway |
| Wither Loft | 4.4 | 3* | gated | - | - | local | template + spawner | H, R | N/A | doorway |
| Creeper Kennel | 4.4 | 2* | open | - | - | none | template + spawner | H, W, R | N/A | doorway |
| Barred Vault | 4.5 | 2* | open | - | `trial_key` | omen | template + spawner | H, R | NEEDS FIX | doorway |
| Ominous Bargain | 4.5 | 3* | open | - | - | omen | template | n/a | N/A | doorway |
| The Altar | 4.5 | 2* | open | `trial_key` | - | none | template | n/a | N/A | doorway |
| The Store | 4.5 | 1* | open | - | - | none | template + content | n/a | N/A | doorway |
| Rising Lava | 5.1 | 2 | open | `lava` | - | local | template | H, W, R | N/A | doorway |
| Collapsing Bridge | 5.1 | 2 | open | `lava` | - | local | template | H, W, R | N/A | doorway |
| Hold the Plate | 5.1 | 1 | gated | - | - | local | template + spawner | H, R | N/A | doorway |

Counts: 34 situations. 9 gated, 25 open. Three-way: 22 pass, 2 fail, 10
have no hazard and are `n/a`. Item-return: 4 NEEDS FIX, 1 OK, 29 N/A.
Visibility: 30 readable through the doorway as written, 2 need glass, 2
need tinted glass, 0 need iron bars in a shared wall (Ledge Archers' bars
are interior furniture, not a wall window).

---

## 2. Findings

Ordered roughly by how much damage each does if it ships unfixed.

### 2.1 Frame Lock's mechanism does not exist in vanilla

A comparator reading an item frame outputs 1 to 8 from the item's
**rotation** and 0 when empty. It cannot tell a copper ingot from a
prismarine shard. "One pot contains the key item, frame it, door opens"
is not buildable, and Rotation Lock is the same redstone done correctly,
which is why the two rooms read as duplicates.

**Fix**: build Frame Lock as a vanilla item filter, not a frame. A hopper
line whose filter slots hold the theme's key item, comparator off the
sorted output, iron door off the comparator. The pots, the theme reading
and the "players who read the theme go to the right pot first" payoff all
survive unchanged. Keep the name.

### 2.2 Frame Lock does not return the key, and 6.4 says it does

Spec 6.4 credits Frame Lock with "chest on the far side of the door. You
get it back". That sentence is Item Plate's, quoted from 4.2. Frame Lock's
own entry returns nothing. Under 2.1's filter rebuild the key ends up in a
container, so the rule now clearly bites.

**Fix**: hopper the sorted output through the wall into a chest past the
door. Same pattern as Item Plate. Correct 6.4's attribution while you are
there.

### 2.3 Flow Puzzle eats the item

The item goes into the hopper and the spec never gives it back. Two
consecutive rooms wanting the same tag would break exactly the way 6.4
warns.

**Fix**: chain the receiving hopper through the wall into a chest past the
door. Two extra blocks.

### 2.4 The Gallery consumes the capability that solves it

Arrows, snowballs and eggs are spent hitting the targets and land in a pit
the player is on the wrong side of. Not a container, so the letter of 6.4
is satisfied and the spirit is not: `bow` and `snowballs` are in
`available` at n and gone at n+1, which is precisely what the boolean
model forbids.

**Fix**: hopper floor under the target pit, feeding a chest past the door.
Recovers arrows, snowballs and eggs alike.

### 2.5 Barred Vault, Ominous Bargain and the vault block

`DISCOVERIES.md` trap 2: `VaultBlockEntity$Server.tryInsertKey` rolls the
loot table first and returns early on an empty roll, **after** taking the
player's attention and before `unlock()`. U8 replaced vaults with doors
for this reason. Both 4.5 vault spurs walk straight back into it, and a
trial key is scarce under section 3.

**Fix**: neither spur uses a `minecraft:vault`. Barred Vault becomes an
iron door with a trial-key item filter and a chest behind it, the same
mechanism as 2.1. Ominous Bargain keeps the ominous bottle (which is the
actual decision) and puts its reward behind the same door. If a real vault
is kept for the block's look, the loot table must be provably non-empty at
every tier and that has to be a test, not an assertion.

### 2.6 Pot Room's trial key over-promises under the boolean model

The key leaves the room with the player and is spent at the next vault or
altar. `provides: trial_key` is therefore true once, not forever, and 6.6
will happily place two consumers downstream of one Pot Room.

**Fix**: keep `provides: trial_key` on Pot Room and The Altar, and let **no
room `require` it**. Barred Vault's `trial_key` in the table above is the
single exception and it is a spur, so a player who arrives without a key
loses an optional reward rather than a route. Add that as a rule to 6.6:
consumable tags may appear in `provides` and in spur `requires`, never in
a critical-path `requires`.

### 2.7 Plate Pair's requirement is an OR and the model only has AND

4.2 says it requires "`provides: lead` or `provides: mob`". Under 6.6
`requires` is an AND, so the two-tag form would demand both. A lead with
no mob is useless and a mob with no lead is only useful if it can be told
to sit, which needs a tamed wolf, which needs bones.

**Fix**: `requires: [mob]`, single tag, defined as "a leashable mob is
obtainable at lower BFS depth, or party size is 2 or more". Rooms that
ship a lead also declare `mob` only if they ship something to lead. This
is the round's only hard requires and the Pilgrim test should be run
against it before the other seven mechanism rooms are authored.

### 2.8 `mob` must not be satisfiable by an unleashable mob

Creeper Kennel is the obvious trap. If it declares `provides: mob` the
generator will place Plate Pair behind it and hand the player three
creepers and no lead point. Same for silverfish, blazes, endermen and
breezes.

**Fix**: `mob` means leashable. None of the 4.4 combat rooms provides it.
Say so in 6.1's tag glossary rather than leaving it to each author.

### 2.9 The Herd fails the three-way test

Twelve zombified piglins you must not touch is a hazard and nothing else.
There is no weapon use inside the cell and no resource unless you fight,
which is the wrong path by construction.

**Fix**: sink gold blocks into the floor among the herd. A player who
mines quietly carries `gold` forward, which is exactly what the Bazaar
wants, and the risk of mining next to a neutral mob pack is the decision
the room was missing. `provides: gold`, resource limb satisfied, identity
untouched.

### 2.10 Infested Wall fails the three-way test and blocks Pilgrim outright

Silverfish are a hazard only, and every infested variant in vanilla
(stone, cobblestone, the stone brick family, deepslate) needs a pickaxe.
Only the Mason bag carries one. A Pilgrim meeting a gated Infested Wall on
the critical path is stuck, permanently, which will present as a generator
bug.

**Fix**: put a stone pickaxe in a pot in the room. The wall then supplies
the tool that breaks it, the mined stone bricks become `provides: blocks`
(resource limb satisfied), and the room stays exactly what it was. Do not
make the wall hand-breakable: that removes the room.

### 2.11 Elder's Chamber's slow path is not a path

"Mine through with Fatigue III, about 45 seconds per block" assumes a
pickaxe. Without one the soft wall is not slow, it is infinite.

**Fix**: build the soft wall out of gravel or dirt. Hand-breakable, still
punishing under Fatigue III, and the milk-clears-it and kill-the-guardian
routes stay strictly better. Note that the audit does **not** add
`requires: [milk]`: that would make the room unreachable for seven of the
eight bags.

### 2.12 Item Plate assumes the player owns an item

Pilgrim carries bread, so in practice this always works. It stops working
the moment a player arrives having eaten it.

**Fix**: one decorated pot in the room. Breaking it drops something
throwable. One block, closes the hole permanently.

### 2.13 Gallery's slow path is free

See 5.2: a pit is one block deep. "Bridge the pit and punch the targets"
costs nothing when the pit is a step.

**Fix**: floor the trench with lava at y = 0 rather than air. The slow path
becomes a real commitment, and the lava adds the weapon and resource limbs
the room is thin on.

### 2.14 The Altar's comparator ladder has two rungs, not many

A comparator on a hopper reads fill fraction across five slots. One
stackable item reads 1; one non-stackable item reads 3. That is the whole
resolution unless the altar is fed multiple items.

**Fix**: design the payout table for two bands, stackable and
non-stackable, and let quantity drive the rest ("feed it more food, get
more"). Every bag ships food (section 3.2 rule 1), so The Altar is the one
spur that is universally usable, which is worth keeping.

### 2.15 Section 4.4 implies every combat room is gated

The spawner-clear gate on the `encounter` role would gate all seven, which
contradicts 6.1's "most cells are open" and directly contradicts Ledge
Archers ("running past is the intended fight") and Creeper Kennel ("not a
fight, a tool").

**Fix**: the split in the table above. Gated where the fight is the
reward (Breeze Arena, The Raid, Wither Loft); open where the mobs are
terrain or a tool (Bogged Marsh, Ledge Archers, Slime Pit, Creeper
Kennel). M52's clear-gate change already counts only gated encounter
cells, so this is a data decision, not a code one.

### 2.16 `access` is meaningless on a dead-end spur

All four 4.5 spurs are marked `open` above because a dead end's only exit
is the way in and is always reachable. The selector must not count a spur
toward "at least one gated cell on the critical path", or a floor can
satisfy 6.1 while having no gate the player ever meets.

### 2.17 Fluid `provides` are bucket-conditional

`lava` and `water` are listed for every room with a reachable source
block, because that is what a template can honestly offer. But 6.1 defines
`provides` as "guarantees to drop", and a source block is worth nothing
without a bucket, which only Plumber and Innkeeper carry.

**Fix**: 6.6 treats fluid tags as conditional on a bucket already being in
`available`, or 6.1 renames them (`lava_source`, `water_source`) and
templates that hand out an actual bucket use the plain tag. Either way,
decide before the Pilgrim test runs, because Pilgrim never has a bucket
and will otherwise be told it has water.

### 2.18 Vocabulary gaps worth one line each

Arrows (Tripwire Hall, Ledge Archers), string and cobweb (Thicket), powder
snow (Powder Snow Field), gunpowder (Creeper Kennel), prismarine (Elder's
Chamber) and slimeballs (Slime Pit) are all real carried-forward resources
with no tag. That is why six rooms show `provides: -` while passing the
resource limb. Not urgent: an untagged resource is still fun, it is just
invisible to the generator. If one tag is added, make it `pickaxe`, which
is the only gap that changes solvability (see 2.10 and 2.11).

---

## 3. Milestone assignment

The plan's section 4 split holds. Assignments below, with the exceptions
flagged.

**M50 traversal** (5): Chasm, Flooded Hall, Powder Snow Field, Thicket,
Ice Run. As planned.

**M51 mechanism** (8): Frame Lock, Rotation Lock, Plate Pair, Item Plate,
Gallery, Tripwire Hall, Flow Puzzle, Pot Room. As planned. This milestone
absorbs findings 2.1 through 2.4, 2.7, 2.12 and 2.13, which is most of the
audit's repair work. Author Plate Pair and Frame Lock first: one tests
M47, the other is a rebuild.

**M52 knowledge and combat** (14): Bazaar, Don't Look, The Herd, Deep Dark
Landing, Elder's Chamber, Blaze Loft, Infested Wall, plus Breeze Arena,
Bogged Marsh, Ledge Archers, The Raid, Slime Pit, Wither Loft, Creeper
Kennel. As planned.

**M53 pressure and spurs** (7): Rising Lava, Collapsing Bridge, Hold the
Plate, Barred Vault, Ominous Bargain, The Altar, The Store (weighting
only). As planned.

Filed under the wrong family, or filed correctly but with a seam:

- **Pot Room** is in M51 but has no mechanism. Its redstone is one sculk
  sensor and its whole output is an omen source and a trial key. Leave it
  in M51 for file ownership, but its sensor wiring belongs to M48's source
  table and the two agents are concurrent. Same seam for Frame Lock's
  sensor pulses and Barred Vault's cleared-vault omen source. Someone
  should own the omen source contract before wave 2 starts, or three
  template agents will each invent one.
- **Collapsing Bridge** is a traversal room wearing a pressure room's
  clothes. Keeping it in M53 is right because the piston work is
  self-contained, but its floor becomes lava (see 5.4), which puts it on
  M50's Chasm palette. Trivial, worth one message between the two agents.
- **Trial spawner ownership collides.** M52 owns `trial_spawner/**` as a
  glob while M50 owns `trial_spawner/cave_spider_*` and `breeze_*`. M53
  owns no spawner path at all, yet Hold the Plate and Barred Vault both
  need one. Fix the matrix before the wave starts: give M53
  `trial_spawner/spur_*` and `trial_spawner/hold_*`, and narrow M52's glob
  to the seven 4.4 configs by name.
- **`RoomBuilder.java` and `RoomGeometry.java` have no wave-2 owner**, and
  the window-slot fix in 5.1 needs one of them. Either that fix lands in
  M45 before the wave or the visibility clause cannot be met by the two
  rooms that need glass and the two that need tinted glass.

---

## 4. Open problems

Things the cell, the doorway, the server-side constraint or the
no-vertical-traversal rule make impossible or awkward as written.

### 4.1 A window in a shared wall needs both templates to agree

Cells tile at 16 and each cell owns its full 16 x 16 footprint including
both wall rings (`CellGeometry.insideAnyCell`, `BedrockEnvelope`). Between
two neighbouring interiors there are therefore **two** wall blocks, one
owned by each cell. A template that carves a glass window at x = 15 looks
straight into the neighbour's stone at x = 16, and no template can know
who its neighbour will be. Doorways work only because both cells punch
their own half at the same fixed slots.

Three options, in order of preference:

1. **Standard window slot in the shell.** `RoomBuilder.stampShell` punches
   a 1 x 1 opening at i = 6, y = 2 on every wall that carries a doorway,
   filled with glass by default in every room. A situation template then
   swaps only its own half for tinted glass or iron bars and readability
   holds against any neighbour. This is the fix worth doing; it is a
   shell change, not a template change, and it must land before M50.
2. **Read through the doorway plane.** A gated room fills its own half of
   the doorway (y = 1 to 3, i = 7 to 8) with bars or glass. Fully
   unilateral, needs no neighbour cooperation, and is exactly the Barred
   Vault pattern. Works only where the doorway is meant to be blocked.
3. Accept that 30 of 34 situations are readable through the open doorway
   and that the four that are not ship slightly less readable.

### 4.2 There are no pits

Sub-floor bedrock sits at y = -1. The deepest hole a template can carve is
one block, by removing the floor at y = 0. Every pit in the catalogue is
therefore a step: Gallery's pit, Chasm's channel, Collapsing Bridge's pit,
Blaze Loft's "lava below the ledge", Slime Pit's name.

**Consequence for authors**: a "pit" is a one-block trench and is only a
hazard if it contains something. Lava at y = 0 is the general answer and
happens to satisfy the weapon and resource limbs of the three-way test at
the same time. Powder snow and magma are the softer variants. Ledges go
**up**, not down: the interior is five tall, so a ledge at y = 3 leaves
two blocks of headroom, which is enough for a skeleton or a blaze.

### 4.3 Fluids leak out of floor-level doorways

Doorways are at floor level, so any room holding water or lava at y = 0 or
y = 1 drains into its neighbours. Overworld lava spreads three blocks and
water seven, and both doorways of a 14-wide room are within that range of
almost anything.

- **Chasm, Rising Lava, Collapsing Bridge, Blaze Loft, Breeze Arena**:
  kerb the lava. A one-block stone lip at y = 1 along both sides of the
  channel contains it completely and costs nothing.
- **Flooded Hall, Elder's Chamber**: full-height water cannot be held by a
  kerb. Hold it with a closed iron door in the doorway plane, glass window
  beside it, and a button. The player sees the flood, opens the door, and
  the release floods the neighbour's floor one block deep while the room
  itself stays full because every block in it is a source. That is
  acceptable and it is also a good moment.
- **Bogged Marsh**: waist-deep water will spill and the marsh is not worth
  a containment build. Use **mud** instead. It slows the player the same
  way, does not flow, and keeps the room's identity exactly.

### 4.4 Collapsing Bridge must build its own one-way, and probably should not

Spec 2 now says plainly that backtracking is allowed and that a cleared
cell is the player's safe ground (12.3). A bridge that retracts
permanently walls the player out of everything behind it, which breaks
12.3 for the whole floor, not just the room.

**Fix**: the pistons re-extend on a repeater delay. The bridge is then a
timing hazard (you cannot stop halfway) rather than a permanent one-way,
which is the identity the room actually wants, and backtracking survives.
Pair it with 4.2's lava floor so stepping off has a cost.

### 4.5 Rising Lava permanently seals its own cell

The lava it places stays placed. A player who crosses, explores ahead and
comes back finds a cell full of lava. Same class of problem as 4.4.

**Fix**: run the lava down a kerbed channel that leaves a one-block dry
margin along one wall, so the cleared room is crossable afterwards, or
place Rising Lava on dead-end spurs only. The channel is the smaller
change and preserves the "cross fast or pillar high" decision. Note that
pillaring is capped: the interior is five tall, so the highest useful
stand is y = 4 with one block of headroom.

### 4.6 A door cannot be on a ledge

Slime Pit's twist is "the bounce reaches a ledge the door is on". Every
doorway is at floor level on slots 7 to 8 (`RoomGeometry.DOOR_MIN`,
`isDoorSlot`) and a room's door mask must match the cell exactly. There is
no such thing as a raised door.

**Fix**: the bounce reaches a ledge the room's **reward** is on. The
door stays where the geometry puts it.

### 4.7 Nothing here needs a mixin, a custom block or a daylight sensor

Confirmed across all 34: every situation is expressible as an `.nbt` plus
a `dungeon_room` JSON plus, in eleven cases, a `trial_spawner` config. The
only Java in the round is The Store's existing `RoomContent` branch. Two
rooms lean on datapack data rather than code (Bazaar's `piglin_bartering`
override, the 4.4 `spawn_potentials` table), which is data by
`DISCOVERIES.md` trap 14's reasoning. No situation wants a daylight
sensor. The one-mixin budget (trap 9) is not touched.

## 5 Q7 evidence: rooms as played, not merely selected

M64 closes the gap between the selector's abstract capability graph and the
physical world the player actually stands in. The selector proves that a
cell's `requires` is a subset of what upstream `provides` and the bag carry;
that is a graph proof, not a play proof. A room can pass the graph and still
strand the player if the item it claims to provide is weighted treasure
rather than guaranteed supply, or if the only exit depends on a finite tool
the player already spent.

### 5.1 Tag versus guaranteed supply

`SituationSupplyTest` (offline, pure JDK) pins down four cases where the
selector's boolean capability model diverges from the physical world:

- **Sapper TNT is finite.** The Sapper bag is tagged `blocks`, but TNT is
  not reusable masonry. A room that requires `blocks` and expects the
  player to build with TNT is a room the player cannot clear after the
  first blast. The test asserts the selector does not treat Sapper TNT as
  a persistent `blocks` provider on the mandatory spine.
- **Shepherd leads do not guarantee a mob.** The Shepherd bag is tagged
  `mob`, but a lead is not a creature. The test asserts that a solo
  Shepherd's `mob` tag does not satisfy a `mob` gate the way a party of
  two does.
- **Plate Pair needs two players.** A solo Pilgrim has no `mob` tag, so
  the `plate_pair` corridor's `requires: mob` must fall back to a
  role-only room. A party of two grants `mob` through party size, so the
  same cell takes `plate_pair`. The test exercises both paths.
- **Spent optional tools never gate the spine.** A finite tool on an
  optional spur may influence treasure but must never be the only exit.
  The test asserts that removing a tool provider leaves the plan
  solvable: the mandatory spine does not depend on it.

### 5.2 The spent optional tool GameTest

`SituationGameTest.spentOptionalToolStillHasExit` (live, Fabric GameTest)
places an iron door and a chest in a structure, arms an `ITEM_ANY` lock,
inserts a stick, waits for `Locks.tick` to open the door, removes the
stick, waits again, and asserts the door stays open. The door is the
exit; the stick is the optional tool; the lock's persistence-after-spend
rule is what keeps the exit open. This is the block-level proof that
6.4's item-return rule is not the only thing standing between the player
and a sealed room.

### 5.3 Handler lifecycle: arm, tick, clear

`HandlerGameTest` (live, Fabric GameTest) exercises the static-map
lifecycle directly:

- **Locks**: `ITEM_KEY` opens for the correct item only, stale locks
  purge when their door is removed, duplicate arming replaces the old
  lock, and a cleared slot can be re-armed.
- **RisingLavaHandler**: pulling the lever drains the lava and drops the
  room from the active map, and a room whose lever is removed is purged
  as stale.
- **CollapsingBridgeHandler**: a room whose pistons are removed is purged
  as stale, and duplicate arming replaces the old bridge.
- **ReturnPathValidator**: a ladder column validates, a water column
  validates, a staircase with headroom validates, and a room with no
  climbable route does not validate.

### 5.4 Omen spur edge cases

`OmenGameTest` (live, Fabric GameTest) pins down four edge cases in
`OmenSources.spurTaken`, which currently reads `container.isEmpty()`:

- **Partial loot**: the container is not empty, so the spur does not
  fire. False negative: the player took reward but the omen does not
  notice.
- **Inserted junk**: the container is not empty, so the spur does not
  fire. False negative: the reward was taken but junk masks it.
- **Initially empty**: the container was never filled, so the spur
  fires on the first poll. False positive: the omen fires without the
  player taking anything.
- **Alternate container**: a barrel works the same as a chest. Correct
  by construction.

These are documented, not fixed. A fix is a separate milestone; the
test pins down what the code does today so a fix can prove it changed.

### 5.5 Supply separation: guaranteed consumables versus weighted treasure

`SupplySeparationTest` (offline, pure JDK plus Gson) reads each supply
chest table and verifies food and light are in guaranteed pools (rolls
= 1) while treasure stays weighted. The supply chest is the only
container in a run that is gated on nothing, so a player who fights
badly still walks out with food and light. That contract only holds
while food and light are guaranteed to roll, which they were not when
they shared a single weighted pool with iron ingots and experience
bottles.

The supply tables (`chests/supply_tier_1.json`,
`chests/supply_tier_2.json`, `chests/supply_tier_3.json`) were split
into three pools: guaranteed food, guaranteed light, and weighted
everything else. Treasure stays weighted, so tool scarcity and treasure
rarity are preserved.

### 5.6 Graph solvability sweep: seeds 0 through 499, every admitted tier

`GraphSolvabilityTest.testTierSweep` runs the 6.6 invariant over seeds
0 through 499 for each admitted loot tier (1, 2, 3), using solo Pilgrim.
Each tier uses a manifest that includes only rooms whose `tier` field
admits them at that level. The sweep asserts:

- zero unresolved plans (every floor resolves)
- zero inaccessible mandatory exits (the terminal is reachable from the
  entrance through open edges, checked by `RoomSelector.validate`)
- the 6.6 subset invariant holds on every non-fallback cell

Results: 496 floors per tier, 0 fallback cells, 0 unresolved, 0
inaccessible exits. The Pilgrim sweep (596 floors, 6873 cells) remains
unchanged.

### 5.7 What is proven and what is not

**Graph proof** (automated, headless): the selector's 6.6 subset
invariant holds over 500 seeds and three tiers. Every cell's `requires`
is a subset of what upstream `provides` plus the bag. The terminal is
reachable. No consumable gates the spine. No unsatisfiable room lands.

**Physical reachability** (automated, live world): the handler tests
prove the blocks do what the metadata claims. The door opens, the lava
drains, the bridge collapses and re-extends, the return path climbs.
The spent-tool test proves the exit stays open after the optional item
is spent.

**Human player mastery** (not automated): readability, route-finding,
combat difficulty, and the moment-to-moment experience of playing the
room. The graph proof says the room is solvable; the physical proof
says the blocks work; neither says a player will understand the room on
first sight. That is a live-play check, not a test.
