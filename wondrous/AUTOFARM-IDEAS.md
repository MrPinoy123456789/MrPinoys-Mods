# Auto-farming equipment — research and item slate

Companion to `ITEM-IDEAS.md`. Same rules: tiers are effort, not fun. Names are
noun phrases in Ellie's voice. Ids are the contract.

The question behind this doc: **what do client-side mods give players that our
server can sell them instead?** Every item below is server-side only — a vanilla
client sees a hoe with a funny name.

---

## 1. What the research says

Downloads are the honest vote. The features players install mods for, ranked by
how loudly the numbers shout:

| Feature | Reference mod | Scale | Why they love it |
|---|---|---|---|
| **Right-click harvest + replant** | RightClickHarvest | ~58M CF downloads | Removes the most repetitive action in the game. Fortune still applies. |
| " | Harvest with Ease | ~30M | Same idea, second implementation, still 30M. The demand is real. |
| **Carry blocks/mobs without breaking** | Carry On | ~71M | Moving a chest or a villager is currently a chore with no fun in it. |
| **Vein miner / tree feller** | VeinMiner, Treecapitator family | ubiquitous | One swing, whole tree. Nothing else compresses grind this hard. **Already covered** — our vein miner fells trees. |
| **Hand restock / auto tool switch** | Tweakeroo | very high, *and banned on many servers* | See §3 — this is the interesting one. |
| **Inventory sorting** | Inventory Profiles Next, Inventory Tweaks | very high | One button instead of sixty drags. |
| **Item magnet** | backpack magnet upgrades, misc. | high | Never chase a drop again. Loved everywhere it appears. |
| **Bonemeal automation** | AutoHarvest | moderate | Growth on demand; the mod even throttles itself to avoid server kicks. |
| **Waypoints** | Xaero's Minimap | most-downloaded mod on most QoL lists | Getting back to a place you liked. |
| **AoE harvest (sickle/scythe)** | Mystical Agriculture, Farming Overhaul | moderate | Big farms in one swing. |
| **Trash slot** | TrashSlot | moderate | Already spec'd as **Bye Forever Bin**. |

Two conclusions worth more than the individual items:

**Everything popular is grind compression.** Not power, not new content —
*fewer identical clicks for the same outcome.* Every top entry above is the same
product.

**Client mods can't charge for it, and half of them get you banned.** We can do
both legitimately: sell the compression, and make it the sanctioned version of
things players currently sneak in.

---

## 2. The auto-farming slate

The core ask. Priced against existing Wondrous entries (12 / 24 / 32 diamonds).

### Tier 1 — one hoe, two right-clicks

**`big_lazy_hoe` — Big Lazy Hoe** *(diamond 24)*
The whole farming loop in one item. `UseBlockCallback`, one dispatch on what you
clicked:

- **Mature crop** → harvest and replant a 3×3 around it. Fortune applies. One
  durability per block actually harvested, not per swing.
- **Dirt, grass, or bare farmland** → till a 3×3 and plant seeds from your
  inventory into every tilled block.
- **Sneak** → single block, either action. The escape hatch for working next to
  something you don't want touched.

Harvest-and-replant is the single highest-demand feature in the research set
(~58M + ~30M downloads across two mods doing only that), and planting is the
half almost nobody solves. Both in one item means one purchase does a field in
two clicks, and there's no separate sickle to explain.

Deliberately no sickle and no plain single-block hoe — a 3×3 hoe makes both
redundant, and three near-identical hoes in the shop tab is clutter, not a
ladder.

### Tier 2 — the aura pass (do the `FlyingBoots` refactor first)

**`growy_can` — Growy Watering Can** *(diamond 24, burns bone meal)*
Active. Hold right-click: random-tick the crops in a 5×5 in front of you, a few
per second, **one bone meal per tick batch** taken from your inventory. Fast,
attended, expensive.

**`lazy_sprinkler` — Set And Forget Sprinkler** *(diamond 32, burns bone meal)*
Passive. While it's in your inventory the aura pass ticks a 9×9 around you on a
slow cycle — a few seconds between pulses — pulling bone meal automatically.
Wider and cheaper per crop than the can, but you can't rush it; it grows the
field while you're doing something else.

The pair is the point: the can is for *now*, the sprinkler is for *while*. Buy
one, want the other.

**Bone meal is already a shop line** (`bone_meal`, 64 for 256 cobblestone), so
the ammo economy exists with zero new config — and it turns two farming items
into recurring cobblestone drains instead of one-time purchases (§4). Keep it
as literal bone meal in the inventory rather than a charge counter: players
already understand it, and it means bone farms feed crop farms.

### Nothing in Tier 3

**No tree feller** — the existing area tools already handle trees. **No magnet,
no auto-smelt pick, no animal feeder** — see the cut list in §6. Farming is two
items: the hoe and the water pair.

---

## 3. Beyond farming — the rest of the research, as shop items

Not what you asked for, but the research turned these up and they're cheap:

**`restock_ring` — Never Empty Ring** *(diamond 24)* — hand restock. When the
stack in your hand runs out, pull an identical stack from your inventory. This
is a **Tweakeroo feature that gets people banned on other servers.** Selling the
sanctioned version is both a good item and a good story.

**`sorting_wand` — Tidy Up Stick** *(diamond 12)* — right-click a chest to sort
it. Server-side sorting of a real container is trivial; it's the client-side
version that needs a mod. Beloved feature, near-zero code.

**`carry_glove` — Piggyback Glove** *(diamond 32)* — Carry On, at 71M downloads
the most-installed idea in the research. Sneak-right-click a chest to pick it up
*with contents* into a stack, or a mob into a stack. Contents into
`custom_data`, one carry at a time, drop on damage. Tier 4 effort, biggest name
recognition.


---

## 4. Create, stolen by end-functionality

We can't build Create — every component is a new block with a custom renderer and
contraptions are client-rendered moving structures, which is `ITEM-IDEAS.md`
Tier 5 with the volume up. But **nobody actually wants a gearbox.** They want
what the gearbox *ends up doing.* Strip the machinery and Create is a short list
of outcomes, and most of them are an item in a pocket.

| Create machine | The outcome players want | Our shape |
|---|---|---|
| Crushing Wheels, Millstone | Ore doubling; gravel→flint; bones→lots of meal | **Smashy Mortar** |
| Mechanical Crafter | Crafting that doesn't make you re-set-up every time | **Left It Out Crafter** |
| Brass tunnels, funnels, Mechanical Arm | Items land in the right chest without sorting | **Chuck It Wand** |
| Encased Fan, Mechanical Press output | The machine's product ends up in storage, not in my hands | **Put It There Wand** |
| Portable Storage Interface, Item Vault | Move a full container without emptying it | **Piggyback Glove** (§3) |
| Mechanical Harvester / Plough | The field farms itself | **Big Lazy Hoe** (§2) |
| Belts, funnels, chutes | Logistics: stuff goes where it belongs, by itself | **Put It There Wand** |
| Schematicannon | The building builds itself | see the no-list |
| Trains, contraptions | Getting around, moving base | flying boots; see the no-list |

### The items

**`smashy_mortar` — Smashy Mortar** *(diamond 32)*
Right-click holding a stack: crush it. Raw iron/gold/copper → double nuggets
(net gain under a doubling, not a full double — tune it), gravel → flint every
time, bone → 6 bone meal, blackstone → gold nuggets. Costs durability per
operation, so it's a diamond tool that wears out rather than a printing press.
**Bone → meal is the deliberate one:** it feeds the sprinkler in §2, so the
crushing branch and the growth branch buy each other. That's Create's actual
structure — machines that make each other worth owning.

**`chuck_it_wand` — Chuck It Wand** *(diamond 12)*
Right-click: every item in your inventory that already has a home in a chest
within ~8 blocks flies into that chest. Quick-stack-to-nearby-containers, which
on the client-mod side is a top-five most-loved feature and here is one radius
scan plus an inventory pass. **Cheapest Create outcome in the doc by a wide
margin** — it's the whole brass-tunnel fantasy in one click, and it makes coming
home from mining feel good. Build this one first.

**`crafting_station` — Left It Out Crafter** *(diamond 32)*
*"dont clean that up, i was using it"*

Tinkers' Construct's Crafting Station, and **not** a replacement for the Pocket
Crafter — the two trade against each other properly:

| | Pocket Crafter | Left It Out Crafter |
|---|---|---|
| Where it works | anywhere, in your inventory | only where you placed it |
| The grid | empties on close, like vanilla | **stays exactly as you left it** |
| Who it's for | mining trip, one quick recipe | your base, the project you're mid-way through |

That's a real choice rather than an upgrade, and both stay in the shop. (Drop
"Do It Again Crafter" outright — vanilla already shift-clicks a whole stack out
of the output slot, so its headline feature was one we'd be re-selling.)

**How it works without a new block.** The item is a vanilla
`minecraft:crafting_table` carrying `{wondrous: "crafting_station"}`, and it
**places normally as a crafting table** — the one Wondrous item that's meant to
place rather than being consumed by `SUCCESS_SERVER`. On placement, record the
`BlockPos` + dimension in the mod's saved data. `UseBlockCallback` checks that
set: a position in it opens the persistent menu, any other crafting table
behaves like vanilla. Nothing new enters a synced registry, and it looks like a
crafting table because it *is* one.

The parts that need care, in order of how likely they are to bite:

1. **Break handling.** `PlayerBlockBreakEvents.AFTER` — drop the station item
   back, drop the grid contents, and remove the position from the store. Miss
   this and the contents are gone or, worse, the position stays registered and
   the next table built there inherits a ghost inventory.
2. **Saved data.** Per-world store keyed by dimension + pos, holding nine
   stacks. Explosions, pistons, and world-edits all need the same cleanup path
   as breaking.
3. **The menu.** A `CraftingMenu` subclass overriding `removed()` to write the
   grid back to the store instead of dropping it, and pre-filled on open. Real
   position means `ContainerLevelAccess.at(level, pos)` works properly here,
   unlike the pocket stations' `NULL`.

**Tier 4** — block lifecycle plus saved data is the work, not the menu.

**Crafting from adjacent chests** — the other half of what Tinkers' station is
famous for — gets much easier once the station has a real position, because
"adjacent" finally means something. It still needs a hand-rolled sgui crafting
UI (3×3 in a chest-shaped GUI, recipe matching through `RecipeManager`,
shift-click and bulk-craft written by hand, no recipe book). **Ship persistence
first and treat chest-sourcing as a later addition to the same item** — a
station that remembers your grid is already worth 32 diamonds on its own.

**`link_wand` — Put It There Wand** *(diamond 32)*
*"the chest. that one. THAT one"*

Create's belts without the belts: **link a machine's output to a chest** and its
finished goods walk there on their own. A furnace bank that empties itself into
your storage, with no hopper line to build and no iron to spend.

**The interaction, which is most of the design:**

1. Right-click a furnace (or any machine) with the wand → it's **selected**, and
   you can see that it is: the block gets a glowing outline, plus a bell note
   and an action-bar line reading *"pick a chest"*.
2. Right-click a chest → the link forms. Confirmation chime, and a short trail
   of particles runs machine → chest so you see which way the goods flow.
3. Right-click the machine again to unlink. Right-click empty air to cancel a
   half-made selection.
4. **While the wand is held**, every link you own inside render distance shows
   its outlines and a slow particle trail. Put the wand away and the world looks
   normal again. This is the bit that makes it feel like a machine rather than a
   config file.

**All of that works on a vanilla client**, which is the part worth checking
before anyone panics:

- **Outlines** — spawn a `block_display` entity at the block, scaled ~1.01, on a
  scoreboard team with a colour, with the glowing flag set. Vanilla renders the
  glow outline. Remove the entity to clear it. (Particles drawing the twelve
  edges of the block via `sendParticles` also works and needs no entity
  lifecycle — cheaper, slightly less crisp. Pick one and be consistent.)
- **Trails** — `end_rod` or coloured dust particles stepped along the vector,
  slow, only while the wand is held.
- **Text** — `sendActionBar`. **Sound** — note-block bell to select, chime to
  confirm, bass to reject.

No client mod, no resource pack, no new registry entry.

**The transfer.** A scheduled pass moves items from the machine's output to the
chest at roughly hopper speed. Don't hand-code which slot is "the output" —
vanilla already answers that through `WorldlyContainer.getSlotsForFace(DOWN)`
and `canTakeItemThroughFace`, which is exactly what a hopper underneath uses.
Read the machine the way a hopper would and **every machine works, including
any modded one**, with no per-block table to maintain. Furnace, blast furnace,
smoker, brewing stand, all free.

**Rules to set deliberately:**

- **Loaded chunks only**, same as a real hopper. If it worked offline it would
  be a different item with different economics.
- **Same dimension, ~16 blocks.** Longer range is the premium version, not the
  default.
- **A per-player link cap** (8 is a reasonable start) — this is the item's one
  real performance risk, and the cap is what makes it a non-issue.
- **Break handling**, the usual trap: breaking either end kills the link, tells
  the player, and drops it from the store. A periodic validation sweep that
  discards links whose blocks no longer exist covers explosions and world-edits
  without a hook per cause.

**Tier 4** — saved data plus the visual layer is the work; the item transfer
itself is small. Name overlaps two other sticks in the shop (Chuck It Wand, Tidy
Up Stick); worth renaming one of the three before all this ships.

### The no-list, plainly

- **Schematicannon / blueprint printing.** A server-side select-and-paste wand
  *is* technically buildable — `ballot` already ships a wand — but it's WorldEdit
  in a shop item, with the griefing surface that implies, and Create's version
  is loved because you watch it place blocks one at a time. Skip.
- **Trains and moving contraptions.** Needs entities with models. No.
- **Rotational power itself.** The stress/network puzzle is the part that needs
  the visible machine. Don't simulate it with numbers in a GUI; it's tedium
  without the payoff.

### The structural lesson, which is free

Create keeps people for months because there's always a next rung: andesite →
brass → electron tubes. The Wondrous tab is twelve unrelated items at 12–32
diamonds in no order. The pairs in this doc — can → sprinkler, wand → wand →
station — are the first real rungs, and building them as pairs costs nothing but
shop copy that points up the ladder.

---

## 5. The economy warning, which is the actual design problem

Every item in §2 is a **faucet multiplier.** A sickle plus a magnet plus a
watering can is a player producing four times the crops per minute, and
`SUITE_AUDIT.md` already flags the suite as short on sinks, not short on faucets.

Three rules to build these under:

1. **Durability, not infinity.** These are diamond-tier tools that wear out. A
   worn one goes to the Melty Pocket or gets re-bought. Recurring revenue from a
   one-time item.
2. **Bone meal is the ammo.** Both growth items burn it, it's already a
   cobblestone shop line, and it's the only mechanic in this doc that takes
   currency *out* per use. If only one thing here survives contact with the
   build, make it this one.
3. **Price the ladder, not the tool.** Hoe 24, can 24, sprinkler 32 — and the
   copy on the can should make you aware the sprinkler exists.

---

## 6. The whole slate, in one table

Everything proposed in this doc. Tier is effort (same scale as
`ITEM-IDEAS.md`), price is diamonds.

### Farming (§2)

| id | Name | Tier | 💎 | What it does |
|---|---|---|---|---|
| `big_lazy_hoe` | Big Lazy Hoe | 1 | 24 | 3×3 harvest+replant, or till+plant. Sneak = one block |
| `growy_can` | Growy Watering Can | 2 | 24 | Active 5×5 growth, burns bone meal |
| `lazy_sprinkler` | Set And Forget Sprinkler | 2 | 32 | Passive 9×9 growth, slow, burns bone meal |

### Create outcomes (§4)

| id | Name | Tier | 💎 | What it does |
|---|---|---|---|---|
| `chuck_it_wand` | Chuck It Wand | 1 | 12 | Quick-stack to chests within ~8 blocks |
| `smashy_mortar` | Smashy Mortar | 2 | 32 | Crush: ore→nuggets, gravel→flint, bone→6 meal |
| `crafting_station` | Left It Out Crafter | 4 | 32 | Placed table that keeps its grid |
| `link_wand` | Put It There Wand | 4 | 32 | Link a machine's output to a chest, with outlines and trails |

### From the client-mod research (§3)

| id | Name | Tier | 💎 | What it does |
|---|---|---|---|---|
| `sorting_wand` | Tidy Up Stick | 1 | 12 | Right-click a chest to sort it |
| `restock_ring` | Never Empty Ring | 2 | 24 | Hand restock — the sanctioned Tweakeroo feature |
| `carry_glove` | Piggyback Glove | 4 | 32 | Carry a chest with contents, or a mob |

**Ten items.** Five are Tier 1–2 and land in an afternoon each; three are Tier 4
projects. Already-spec'd items from `ITEM-IDEAS.md` that aren't repeated here
(Peek Box, the Tier 0 attribute batch, Bye Forever Bin, Big Purse, Brain Juice
Jar) still stand.

**Cut, with reasons:**

| Cut | Why |
|---|---|
| One Chop Axe | our vein miner already fells trees |
| Big Swipe Sickle, single-block hoe | redundant once the hoe is 3×3 |
| Do It Again Crafter | vanilla shift-click already does it |
| Straight To The Bag | — |
| Chugging Mill | — |
| Grabby Hands | — |
| Toasty Pick | — |
| Snack Bag | — |
| Huffy Bellows | — |
| Go Bed Stone | — |
| Schematic wand, trains, rotational power | need a client mod, or lose the point without one |

Cutting Grabby Hands removes the only consumer of the generalised aura pass
besides the sprinkler — so that refactor is now justified by **one** item
(`lazy_sprinkler`) rather than a family. Still worth doing when the sprinkler is
built, but it stops being urgent.

---

## 7. If you build three

**Chuck It Wand**, **Big Lazy Hoe**, **Growy Watering Can.**

The wand is the cheapest item in the doc and the one players touch every single
session. The hoe is the most-wanted feature in the research and one afternoon's
work. The can pays the economy back in bone meal and sets up the sprinkler as
the first genuine upgrade rung in the Wondrous tab.

Then **Set And Forget Sprinkler**, and **Put It There Wand** when you have room
for a Tier 4 — it's the one item on the list with a visual identity, and the one
people will show each other.

Note that cutting the Chugging Mill leaves `dailyquests` alone again on "bring
them back tomorrow" (`SUITE_AUDIT.md`). Nothing else in this doc answers that
gap; it'll need solving somewhere else in the suite.

---

## Sources

- [RightClickHarvest (Modrinth)](https://modrinth.com/mod/rightclickharvest) ·
  [CurseForge](https://www.curseforge.com/minecraft/mc-mods/rightclickharvest)
- [Harvest with Ease](https://www.curseforge.com/minecraft/mc-mods/harvest-with-ease)
- [Right Click to Harvest — server-side Fabric](https://www.curseforge.com/minecraft/mc-mods/right-click-to-harvest)
- [Serverside Vein Miner and Treecapitator](https://modrinth.com/mod/serverside-vein-miner-and-treecapitator) ·
  [VeinMiner](https://modrinth.com/project/MnavVAzj)
- [AutoHarvest](https://modrinth.com/mod/autoharvest) ·
  [Auto Plant Crops](https://modrinth.com/mod/auto-plant-crops)
- [Tweakeroo](https://modrinth.com/mod/tweakeroo) ·
  [Tweakeroo inventory management](https://deepwiki.com/maruohon/tweakeroo/6-inventory-management)
- [Best Minecraft farming mods by downloads](https://blog.curseforge.com/best-minecraft-farming-mods/)
- [Best QoL mods by community downloads](https://blog.curseforge.com/best-minecraft-quality-of-life-mods/)
- [Sickle (SpigotMC)](https://www.spigotmc.org/resources/sickle-harvest-crops-with-a-right-click.29443/)
