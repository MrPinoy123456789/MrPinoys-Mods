# Pocket Dungeons — Purpose, Hook, and Platform Thesis

> Not a technical spec. `PLAN.md` covers how it is built; the external
> "On-Demand Dungeon Instances" document covers the mechanics;
> `MYTHIC_PLUS_RECONCILIATION.md` holds the design decisions and their reasoning.
> **This document answers what the mod is for and why it should exist.**

---

## 1. The hook

> **Your room has three doors. Everything you own came from behind them.**

You spawn in a room that belongs to you. Three doors stand at the far wall, and
each one is a run at a difficulty you choose — the safe one, the greedy one, the
reckless one. You spend your keystone on a door and walk through.

You go forward. You never double back. Five to eight rooms later you open the
last door and step into **your own room**, at the far end of a corridor that only
ever went one direction.

The mod does not comment on this.

You unpack, repair, restock, look at the three doors again, and go.

---

## 2. The thesis: this is a game mode, not a content mod

**Skyblock proved one thing, and it is the only thing we take from it.**

> Seal the player in an instance, change the fundamental verbs, and you do not
> get a challenge map — you get a **game mode**: something people adopt by name
> and go looking for.

Nothing else transfers. We are not taking scarcity, bootstrapping, cobblestone
generators or excavation, and StoneBlock is cited only as evidence that the trick
generalises past one map. What we take is the *shape of the proposition* — the
world is not the world any more, and the verbs are not the same verbs.

Pocket Dungeons makes that proposition with different verbs:

| Verb | Minecraft | Pocket Dungeons |
|---|---|---|
| Where content comes from | Explore | **Run** |
| How you get things | Craft | **Receive** |
| How you get stronger | Gear tier | **Key level** |
| Where you build | Anywhere, in a shared world | **A room that is yours** |
| What failure costs | Your stuff | **Your key level** |

Five swaps and one sealed instance. Everything downstream follows.

> Superseded: earlier drafts of this section led with **Factions**. Factions is a
> rules overlay on an *intact* world — you still lived in it, mined it and
> crafted normally. It changed what the world was for; it did not change where
> you live or where materials come from. The analogy was doing work its mechanics
> could not back, and everything it argued is argued better below.

**The four rules that define the format:**

1. You live in an instance, not a world.
2. Progress is a key level, not a gear tier.
3. Runs are timed and disposable. Your room is permanent.
4. Everything you own came out of a run.

Everything else in this document follows from those four.

### 2.1 The seal is not what made it a category

Skyblock shipped on **4 September 2011** as a downloadable single-player map, and
for roughly a year that is all it was — a challenge map with a good idea in it.
It became a *mode* when it went **multiplayer** in 2012. It became the biggest
thing in Minecraft when Hypixel launched their version on **11 June 2019**, and
the headline change there was **public islands alongside the private starting
island.**

> A sealed instance with swapped verbs is a mode. It becomes a **category** when
> other people can walk into yours.

Habbo reaches the same conclusion from the other end: players return after years
away because their room, their friends and their furniture are still sitting
there — inside what was structurally a *public* space made of private rooms.

**This is the load-bearing sentence for §3.1.** Letting people visit a room is
not a nice-to-have sitting on the backlog. It is the step that turns a good
instance generator into a thing with a name, and every precedent this design
claims says so.

**One correction to how far that claim reaches:** Hypixel did not become the
biggest thing in Minecraft because it added public islands. It added public
islands **on top of a population it already had.** Visitability is necessary
and this design bets correctly on it, but it is not sufficient by itself --
folklore dynamics need density, and a calling card traded by hand on a
five-player server produces a nice feature, not a category. The mechanism this
milestone builds is right regardless of scale; the *category*-forming outcome
the vision gestures at additionally requires a server that already has people
on it, which is not something any of these documents can produce on their
own.

### 2.2 How we describe it

Internally the lineage is Mythic+, Nephalem Rifts, REPO, Habbo and Skyblock, and
§3 is organised around it. **That scaffolding is for us, not for players.** The
product is more interesting than its references, and "Minecraft Mythic+" is a
smaller idea than what this actually is.

The category we want people to name:

> *"That Minecraft server where everyone has their own dungeon house."*

Public one-liner: **a run-based Minecraft dungeon crawler.** Genre shorthand,
when one is needed: a Minecraft roguelite dungeon crawler. It is a roguelite
loosely — discrete procedural runs, run modifiers, persistent meta-progression —
but it deliberately has no in-run buildcraft, and we should not chase that label
by adding any. Doing so would pull the design away from its strongest idea.

---

## 3. Pillars

### 3.1 The room — the actual product

Persistent, decoratable, owned. Break rights and container rights belong to the
owner and whoever they whitelist. Visitors use the stations but take nothing.

**This is the centre of the design, not the thing that survives between
dungeons.** The procedural generator gets attention because it is technically
interesting; the room is what makes Pocket Dungeons itself. The loop is:

```
Room → Door → Run → Loot → Room → decorate → show someone → Door
```

**Your gear says what you acquired. Your room says what you did.**

That is a character sheet made of blocks, and it is far more socially interesting
than an inventory. Two players stand in a room and one asks *where did you get
that* — and the answer is a run, at a depth, under an affix. The room is a
physical record of a play history that cannot be bought, traded or crafted.

**The upside here is the least claimed, and §2.1 says why.** A decoratable room
generates retention only once other people can see it. Today the room is private
— visible to a party, mid-run, and nowhere else. Classic Skyblock was a private
island too, and it took Hypixel adding a *public* layer next to the private one
to produce the largest mode in the game.

That is not an open question any more. **Visitable rooms are the thesis**, and
the mechanism is the top unbuilt item in §8 rather than a maybe.

> ⚠ **The room is capped at one cell, on purpose, for now.** `RoomManifest`
> validates every room -- including a player's own -- against a fixed shell,
> door anchors and a 1×1 footprint; a decorated room that drifts from any of
> those fails to stamp. Growing it is D4/D5 in `plans/M8-deferred.md`, both
> genuinely deferred, neither scheduled. The room-as-museum argument in §3.6.1
> holds at one cell's worth of shelf space; it does not yet scale past that,
> and nothing on the roadmap currently promises it will.

#### 3.1.1 The calling card — how visiting works

§2.1 makes visiting the thesis, so the spec owes a mechanism. This is it, and it
costs no custom item and no client mod.

**The keystone is a recovery compass. A calling card is a plain compass.** One
points at your runs; the other points at somebody else's room. Same minting
pattern as `Keystone.mint` — `CustomData.update(DataComponents.CUSTOM_DATA, ...)`
under the mod's root tag, plus a `CUSTOM_NAME`.

> ⚠ The card stores an **owner UUID, not a position.** A room is a
> `StructureTemplate` blob that gets stamped into whatever slot is free, so a
> `GlobalPos` would be stale the moment the room moved. `LODESTONE_TRACKER` may
> still be attached with `tracked: false` purely for the glint and the vanilla
> item name — verified present in 26.2 as
> `LodestoneTracker(Optional<GlobalPos>, boolean)` — but it is decoration, not
> the address.

**The address spreads by hand.** You mint a card and give it away. It cannot be
searched, listed or browsed, so a room's audience is exactly the set of people
its owner handed a compass to, and cards travel by trade the same way recipes
travel by rumour (§5.4). That is the folklore dynamic this design keeps reaching
for, and it arrives here for free.

**Where the door is.** The three doors are runs and stay runs — the hook in §1 is
not up for renegotiation. A card is used **on the lodestone**, the block the mod
already owns for the ritual, and it opens a way in that is not one of the three.

**One shared visit instance per owner, refcounted.** First visitor stamps the
room blob into a free slot; the last one out releases it. If the owner is home,
the card routes to the owner's live room instead of a second copy — two people
holding cards to the same room end up in the same room, which is the entire
point. This reuses `allocateSlot`, force-loading and the eager-teardown path
exactly as they already work.

**Visitors take nothing.** §3.1's rule already covers it: break rights and
container rights belong to the owner and their whitelist, stations are open to
all, the ender chest is open to all. A visitor is simply someone not on the
whitelist.

**Cost, honestly:** this is small *only after* the room milestone lands. It needs
the room to be a persisted blob with an owner and a permission mask, and none of
that exists yet. See the roadmap — M2 before M3, always.

---

### 3.2 The ladder — from Mythic+

A keystone from 1 to 25. Beat the timer and it goes up; miss it and it depletes.
Affixes stack as the level climbs.

> Superseded: **the weekly affix rotation is cut.** Blizzard deleted their own
> seasonal affix in Dragonflight S2 on the grounds that dungeon rotation already
> supplied enough freshness. Our freshness comes from a procedural generator plus
> themes and recipes (§5) — strictly more variety than a fixed dungeon pool — so
> the rotation was solving a problem this design does not have, at the cost of a
> server-wide time-seed nobody wanted to own. Reasoning in
> `MYTHIC_PLUS_RECONCILIATION.md` §4.

**Why it works here:** the timer is gear-agnostic. Enchanted netherite cuts the
combat tax but not the routing tax — you still have to cross five to eight rooms
before the clock runs out. That is why the ladder stays meaningful without ever
gating anyone's equipment.

**And the meta-progression grants no power.** The standing rule for the genre is
that persistent upgrades must enhance a run without trivialising it, which is the
balance most roguelites spend their whole life fighting. We win it by
construction: the two things that persist are **a room and a number**, and
neither makes the next run easier. There is no power-creep vector in this design
to mistune.

### 3.3 The run — from Nephalem Rifts

Procedurally generated, disposable, ownerless. A self-avoiding walk of 5–8 rooms
with 0–2 spurs, assembled from templates on a cell grid. Completing it puts three
upgrade offers in front of you, which is Urshi's beat by another name.

**What we trade away:** Mythic+ depth comes from route mastery, and procedural
layouts make that impossible. Replay value has to come from the *composition*
space instead — affixes, themes, recipes (§5) — rather than from geometry.

### 3.4 The party — from REPO

Up to six players, one host, one instance. Go in together, come out together.

**Where we deliberately differ:** REPO's tension is total loss. Pocket Dungeons
has no death at all — a killing blow ejects you with your inventory intact. The
clock is the only thing that can take something from you.

That is a decision, not an oversight, and it produces a cleaner question for
repeatable content:

> *"Can I survive this?"* becomes *"Can I finish this before it costs me the run?"*

The design literature backs the swap: loss aversion runs at roughly twice the
pull of an equivalent gain, and *time lost* is an accepted substitute for *items
lost* when a game wants stakes without a punishment spiral. Depletion is a real
loss — of ladder position — so this pillar needs no further defending.

### 3.5 The parts — from vanilla Minecraft

Trial spawners, vaults, trial keys, ominous states, lodestones, recovery
compasses, echo shards. **No custom blocks. No custom items. No assets
directory. No client mod.**

The recovery compass — an item whose entire vanilla purpose is pointing at where
you last died — is the keystone. That was not chosen for irony, but it earns its
place.

### 3.6 On "mine and craft"

The game is named for two verbs this mode appears to drop. It prioritises
**crawling** and **decorating** instead — which is the verb swap §2 is about, not
a hole in the design.

Mining and dungeon running are already the same shape: descend, go deeper for
better materials, carry them home, build with them. Depth still gates quality.
What changed is that a keystone does the gating instead of a Y-level. Crafting is
the one genuinely displaced verb — players receive rather than make — and §3.6.1
is how the world says so out loud.

> Compressed from a longer version that argued the point at length against an
> objection nobody had raised, and leaned on Factions and on scarcity. Both are
> gone from the thesis; see §2.

### 3.6.1 Provenance — the answer to "number go up"

The ladder's real risk is abstraction. Minecraft players read progression
physically — iron → diamond → netherite, overworld → Nether → End. A keystone
level is a number, and a number alone will feel like nothing happened.

**The world has to communicate the climb.** Loot tables and room palettes tier by
depth, using vanilla's existing range:

| Tier | Palette |
|---|---|
| 1 | stone, wood, iron, moss |
| 2 | deepslate, copper, prismarine, crying obsidian |
| 3 | end stone, ancient-city materials, rare decoratives |

Two consequences follow, and both are content work rather than code:

1. **Put building blocks in the loot tables, tiered.** Not as filler — as the
   point. A tier-3 run yields materials a tier-1 run never does, exactly the way
   ore tiers work.
2. **Vary room template palettes by tier**, so dismantling a deep dungeon yields
   blocks a shallow one does not. This makes the decorate pillar *run on* the
   crawl pillar rather than sit beside it.

The result is not "the dungeon gave me blocks." It is **your room is a physical
record of your dungeon history** — a museum of how deep you have been. That is
what a netherite chestplate says in vanilla, said in a way only this mode can say
it, and it is why §3.1 and §3.6 are the same argument.

It also protects against decoration starving: crafting gives players agency over
what they get, loot gives them what the designer chose, and **decorating is the
activity that most needs a wide palette.** In suite mode the overworld covers the
gap; under §3.7 it is the whole supply chain.

### 3.7 Self-sufficiency — a constraint, not a mode

There is no "no-overworld mode." There is a **design constraint**:

> **Nothing required for progression may live outside the dungeon loop.**

Hold that and a server owner who empties the overworld gets the standalone
experience for free, while a suite server gets both. One design, no divergence,
nothing tuned twice.

#### 3.7.1 Skyblock is the proof, not the model

Skyblock settled one question and we are only claiming that one, in line with §2:
can a player progress in a sealed space with no world? **Yes, if a small
renewable engine is seeded.** A tree, dirt, water, lava, ice — everything derives
from those.

We are not inheriting *how* Skyblock seeds it, and nothing below is borrowed. The
split is ours:

| | Role | Source |
|---|---|---|
| **Dungeons** | The faucet — one-way materials | Loot tables, mob drops, mining the walls |
| **The room** | The engine — renewable production | Farm, tree, breeding |

Dungeons hand out *stuff*; the room makes stuff *repeatable*. This also makes the
room functionally load-bearing rather than decorative, which reinforces §3.1.

#### 3.7.2 Already covered

Tier-1 spawners run skeletons, zombies and spiders, and vanilla drops carry more
than expected: **bones** (taming, bone meal), **string → wool**, **iron** from
zombies, basic sustenance from flesh and arrows.

The **lingering dungeon is a renewable block source** — the walls regenerate
every run, so stone bricks, polished andesite and sea lanterns are effectively
infinite. Building material is solved.

> Note: the dimension sets `bed_works: false` and `respawn_anchor_works: false`,
> so wool is decoration, not a respawn chain. Immaterial in practice — nobody
> dies, and join teleports to the room — but the audit should not lean on it.

#### 3.7.3 Structurally missing

- **Wood.** The real gap: no trees, and wood gates crafting tables, sticks,
  chests and tool handles. Best fix is diegetic — a **grove / garden room type**
  (dirt, saplings, water, light). One template and one `dungeon_room/*.json`
  entry, no Java, and a strange overgrown chamber deep underground fits the tone.
- **Seeds and dirt.** Once a player has dirt, water and seeds the room becomes
  their farm and food stops being a loot problem permanently. This is the
  Skyblock bootstrap and the highest-leverage thing to guarantee.
- **Nether / End ingredients.** Blaze rods, ender pearls, obsidian. Loot tables
  must supply the *product* (ender chest, brewing stand) rather than ingredients,
  because the crafting chain is unreachable. This is exactly where **station
  unlocks stop being arbitrary and become the economy.**

#### 3.7.4 The rule that makes the tables work

> **Guaranteed floors for consumables. Weighted rolls for treasure.**

"You can get X" and "you can always get more X" are different properties. A
weighted chance at bones is fine for flavour and fatal for a taming economy.
Anything consumed repeatedly — food, torches, bones, building blocks — needs a
guaranteed per-run minimum. Treasure stays random.

#### 3.7.5 The plumbing

Four defaults assume an overworld and should prefer the room. None of this is a
mode.

| | Today | Needs |
|---|---|---|
| Entry | Overworld lodestone | `/dungeon` as a first-class route, not a fallback |
| Exit | Room lodestone → overworld | Absent or no-op when there is nowhere to go |
| Join | Vanilla spawns in the overworld | Teleport to the room — `ServerPlayConnectionEvents.JOIN` is already registered with a delayed recovery |
| Stray fallback | `getRespawnData()` → world spawn | Prefer the room |

---

## 4. The trick

One mechanic in this design belongs to nobody else.

On completion the room is **captured from the entrance cell, cleared from it, and
re-stamped at the terminal cell** behind a closed door. The player opens it and
walks into their own room. No teleport, no second copy — the room genuinely
moved. Walk back afterwards and the entrance cell is empty: the mine is there,
their room is not.

**Nothing explains this.** No message, no sound, no lore entry. Vanilla never
explains the Deep Dark or the ancient cities either — the silence *is* the
effect. Everything the mod does say stays in the suite's voice:
`Unhinged Feral Keystone [24]`, *Cooked*, *Big L*.

The single acknowledgement is an advancement on first completion. A toast, a
shrug, no confirmation.

**Protect this idea.** It is the most memorable thing in the design and the
cheapest to accidentally rationalise away with a UI, a message, or a loading
screen. Implementation detail and hazards live in
`MYTHIC_PLUS_RECONCILIATION.md` §3.2.4.

---

## 5. Themes and recipes

Replay value cannot come from geometry (§3.3). It comes from **composition
space** — the same engine producing runs with identities.

### 5.1 Themes are a third axis on the doors

Each door already carries **level + affix**. Add **theme** and the doors become a
composer: your last three door choices *are* a recipe. No new interface, no new
command — `selectorDoorStep` and `/dungeon choose <1|2|3>` already do the work.

Themes and affixes are orthogonal and should stay that way:

- **Theme** = what it looks like and what spawns. Plain, evocative names.
- **Affix** = what rules bend. Slang names (§4).

### 5.2 The cost bomb, and the fix already in the schema

Fourteen templates cover all 53 (mask, role) combinations today. Naively, ten
themes means **140 hand-authored `.nbt` files**. That number kills the feature.

`DungeonRoomMeta.java:45` already parses a `processors` field — **and nothing
uses it.** It appears nowhere in `TemplateStamper` or `LayoutStamper`. The hook
is reserved and unwired.

Vanilla structure processors do exactly what themes need: `minecraft:rule`
rewrites blocks at placement. One `hall_straight.nbt` plus a per-theme processor
list becomes deepslate, prismarine, blackstone or end stone. **140 templates
collapses to 14 templates plus N processor lists** — and processor lists are
datapack JSON, so pack authors can ship themes without touching our `.nbt` files.

**This is the highest-leverage unwired thing in the codebase.** Do it before
anything else in this direction.

### 5.3 Theme families, costed separately

| Family | Examples | Cost |
|---|---|---|
| **Biome** | lush cave, dripstone, frozen, jungle, badlands, swamp, cherry, pale garden | Cheap — processors + `spawn_potentials` |
| **Realm** | Nether, End, Deep Dark | Cheap — same, plus mob tables |
| **Outdoor** | village street, forest path, ruined camp, flooded town | **Milestone** — see below |

**Outdoor is the expensive one and must not be bundled with the cheap themes.**
The dimension is `has_skylight: false`, `ambient_light: 0.0`,
`effects: minecraft:the_end`. A village at sunset needs real sky, and that is a
**per-dimension** property — it cannot vary per room. Both means a second
dimension, and `Instances` is built around one level with one slot grid
(`bySlot`, `allocateSlot`, `originForSlot`).

Worth doing eventually: the village-street-then-the-doors-close moment is the
best beat available to this design. But it is a milestone, not a theme.

### 5.4 Recipes

A sequence of themes unlocks a dungeon that is not otherwise offered.
`Cave → Deepslate → Ancient City` produces something `Village → Village → Village`
does not. **Order matters**, so the same three themes in a different sequence are
a different result.

The data side is small: add a `theme` field to `DungeonRoomMeta` alongside
`roles` (symmetric parse), filter on it in `RoomSelector`, and store the last N
run themes in `DungeonLog` (a `Codec` addition with `optionalFieldOf` and a
default, so old saves migrate). Recipes are then a datapack table of
`[theme, theme, theme] → dungeon id` — which means **pack authors ship their own
recipes**, folding straight into §6.

**Discovery needs a floor.** Fully hidden recipes means most players find zero.
Make the *ingredients* visible even when the combinations are not — the dungeon
log already records runs, so surfacing which themes a player has completed lets
them reason without being told. Exactly how Minecraft handles it: you can see the
items, not the recipe.

The goal is server folklore. Someone comes back with *New Dungeon Discovered*,
their friends ask what they did, and the answer spreads by word of mouth rather
than by patch notes.

### 5.5 Deferred: rule-breaking dungeons

The Endless Mine, the Inversion, the Labyrinth, the Descent. These are not data —
forward-only traversal, the terminal exit, and `RoomSelector.validate`'s
reachability guarantee are `LayoutGraphGenerator` invariants, and each special
ruleset is a generator variant with its own failure modes.

Pick **one** to prove the pattern. The Endless Mine is cheapest, since "do not
place a terminal, keep extending" bends the least. Hold the rest until it ships.

---

## 6. The platform layer

Because the mod is `"environment": "server"` with no assets, **it composes with
any client mod without compatibility work.** The player installs whatever they
like; the server runs Pocket Dungeons; the dungeons are populated from JSON that
never had to know what those mods are.

A pack author can already, without writing Java:

- override `trial_spawner/tier_N/*.json` — `spawn_potentials` accepts **any**
  entity id with arbitrary NBT
- override the `tier_1..3` chest tables
- set `payoutCommand`, which executes an arbitrary command on completion
- contribute rooms from their own namespace — `RoomManifest` scans **all**
  namespaces for `dungeon_room/*.json`
- re-point `keystoneItem` / `vaultKeyItem` to fit their fiction

**The pitch:** *bring your own mods — we generate the dungeons.* A Cobblemon
dungeon server, a tech-mod dungeon server and a vanilla one are the same mod
wearing different loot tables, processors and recipes.

The distinction that matters: the long-term product is **a dungeon format other
servers adopt**, not "a dungeon mod that happens to have configurable JSON."

### 6.1 What still blocks it

1. `RoomManifest` is not `/reload`-driven — its own javadoc admits this. Datapack
   authors iterate by reloading. **Blocker, and small.**
2. Affixes are a Java enum and room roles are a Java `switch`. A pack author
   cannot add either without compiling. **The real investment — do it after five
   or six affixes exist in Java and the varying knobs are known.**
3. No `INTEGRATION.md`, no published `dungeon_room` schema, no `LICENSE` file at
   the repo root. **Entry fee, not polish.**

---

## 7. Audiences

| Who | What they get |
|---|---|
| **Players** | A ladder to climb, a room to fill, provenance to show — none of it obtainable any other way |
| **Server owners** | A server format, not a plugin. Drop-in, vanilla clients, no resource pack |
| **Pack authors** | A dungeon engine that generates around *their* content |

---

## 8. State of play

**Shipped:** void dimension and slot grid; procedural layout (5–8 rooms, 0–2
spurs); fourteen templates; trial spawners and vaults at tiers 1–3; run timer;
keystone 1–25 with three affixes; selector room with three doors; parties of six;
no-death ejection; force-loading and eager teardown; server-side only.

**Designed, not built:** wolves and the Feral affix; stackable affixes on level
thresholds; the slang naming convention; the persistent room replacing the
selector room; the closed loop; the bedrock envelope; door-as-entrance.

**The top unbuilt item, and it is not close:** **a way to visit someone else's
room.** §2.1 is the argument for why this outranks every other line on this page;
§3.1.1 is the mechanism. It is gated on the room milestone and nothing else.

**Constraint, not backlog:** self-sufficiency (§3.7). Every decision is checked
against "could a player progress without ever leaving?"

**Backlogged:** wiring `processors` (§5.2 — do first); themes and recipes; the
platform work in §6.1; room size as progression; station unlocks; outdoor themes;
one rule-breaking dungeon.

---

## 9. Non-goals

- **Not a client mod.** No assets, no resource pack, no custom registry entries.
  This constraint is load-bearing for §6 and is not negotiable for convenience.
- **Not a survival replacement.** It is a mode. The overworld stays available;
  §3.7 only guarantees it is never *required*.
- **Not competitive.** No PvP, no forced scarcity, no ranked pressure.
- **Not monetised.** `payoutCommand` is an operator hook, not a storefront.
- **Not a lore project.** The mechanics carry the weight. The text stays funny.
- **Not chasing the roguelite label.** No in-run buildcraft, no drafted
  abilities, no synergy engine. Adding them would trade the strongest idea —
  players physically constructing a record of their runs — for a weaker one that
  other games already do better.
