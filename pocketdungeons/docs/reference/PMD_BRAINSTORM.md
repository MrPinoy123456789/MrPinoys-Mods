# Pokemon Mystery Dungeon Brainstorm

A scratchpad of ideas borrowed from the Pokemon Mystery Dungeon series and
filtered through Pocket Dungeons' hard constraints: server-side only, vanilla
blocks and items only, no power-creep meta, the room is the progression, and
the clock is the only thing that can take something from you. Nothing here is
specced or committed; it is raw material for future milestones. Generated
2026-09-07.

Each idea names the existing system it would reuse, so a future handoff can
scope it without re-deriving the touch points.

---

## 1. SOS / Rescue mail

The signature PMD social mechanic, reframed for this design.

### What it is in PMD

A player who falls in a dungeon sends an SOS. Another player takes the rescue
mail, enters a short instance routed to the fallen player's location, and
extracts them. The rescued player recovers their progress.

### What it would be here

A player whose run ends in a **depleting timeout** (the only real failure
state, per U8 Stage 1: the clock is the only thing that can take something
from you) leaves behind an SOS. Another player takes the rescue and enters a
short rescue instance routed to the failed run's terminal cell, extracts the
fallen player's dropped keystone level, and hands it back.

### Why it fits the thesis

- The keystone is already a **recovery compass** (`VISION.md` §3.5). A rescue
  compass is a recovery compass pointed at somebody else's failed run instead
  of your own. Same minting pattern as `Keystone.mint`: `CustomData.update`
  under the mod's root tag, plus a `CUSTOM_NAME`.
- It is the failure-state counterpart to the **calling card** (`VISION.md`
  §3.1.1), which already routes a compass to another player's room and already
  spreads by hand-traded compass. The address spreads by trade the same way
  recipes travel by rumour (§5.4). A rescue card is minted by the fallen
  player and given to one rescuer, not listed or browsed.
- It directly serves the visitability thesis (§2.1, §3.1.1): a category forms
  when other people can walk into yours. Rescue is the *failure-state* version
  of visiting, and it reuses the same slot-allocation, force-loading and
  eager-teardown path `VisitService.createVisitInstance` already runs.
- It gives the timer real social stakes without adding death. The cost of
  failing is not just your key level; it is that someone has to come get you,
  or you eat the depletion alone.

### Existing primitives it reuses

| Primitive | Source | Role in a rescue |
|---|---|---|
| `Keystone.mint` + `CUSTOM_DATA` root tag | `Keystone.java` | mint a rescue compass carrying the fallen owner UUID and run id |
| `VisitService.visit` routing | `VisitService.java` | the rescue instance is a visit variant: stamp from a saved blob, refcount, admit, teardown |
| `InstanceRegistry.allocateSlot` + forced chunks | `Instances.java` | the rescue instance takes a free slot exactly as a visit copy does |
| `Instances.rescue` | `Instances.java:1495` | already ejects a player to a safe room on death; the social rescue is the *inter-player* analogue, not a replacement |
| `RunLifecycle.returnKeystone` + `Keystones.Outcome` | `RunLifecycle.java` | the rescue's payoff is a `Keystones.returnTo` call that restores the fallen player's level |
| `DungeonLog` sidecar | `DungeonLog.java` | persist the SOS record (owner, run id, recoverable level, expiry) the same way bounties persist |

### Open design questions

- **What exactly is recoverable.** The cleanest answer, given U8: the
  depleted keystone level only. Loot is already kept (a killing blow ejects
  with inventory intact, and a timeout does not strip items), so the rescue
  recovers the *number*, not the *stuff*. This keeps the rescue from
  duplicating gear and keeps it on thesis (the room and the number are the two
  things that persist).
- **Who can take it.** Hand-traded compass only, matching the calling card.
  No directory, no broadcast. A rescue card decays after some real-time window
  (a day? a week?) so the social obligation has an edge.
- **What the rescue run looks like.** A short, fixed-shape instance: a few
  rooms at the failed run's tier and affixes, terminating at the cell the
  owner failed in. The rescuer does not get the owner's loot; the rescuer gets
  a small bounty-style payout (echo shards) for showing up. This is the
  `BountyTracker.deliverRewards` shape, not a new reward pipe.
- **Whether the owner has to be online.** PMD's rescue is asynchronous (mail).
  Here the cleanest version is *synchronous*: the owner is ejected to their
  room with the depletion applied, and the rescue, whenever it happens, banks
  the level back via `DungeonLog`. The owner does not have to be present.
  Asynchronous is the right scope because the server is the persistent party.

### What it deliberately is not

- Not a second keystone. The rescue compass is a plain compass with
  `CUSTOM_DATA`, the same way a calling card is a plain compass. One item
  type, two purposes, distinguished by the tag.
- Not a way to dodge depletion. The depletion happens at timeout, full stop.
  The rescue restores the level *after*, so failing still hurts in the moment.
  The rescue is a recovery path, not an insurance policy.
- Not a party mechanic. A party member who fails with you is already covered
  by the existing run lifecycle. The SOS is for the solo player who failed
  alone, or the party that wiped, and the rescuer is someone who was *not* in
  the run.

### Rough scope

New class `RescueService` (parallel to `VisitService`), a `DungeonLog` sidecar
key `rescues`, a mint extension on `Keystone` (or a sibling `RescueCard`
helper), and a routing branch in `RitualListener` alongside the calling-card
branch. No mixin, no custom item, no client mod. One milestone's work once the
room and visit milestones it depends on are done.

---

## 2. Job board mission archetypes

PMD's bulletin board has typed missions: rescue, escort, item retrieval,
outlaw hunt, exploration. Pocket Dungeons already has weekly bounties (M34,
`BountyTracker`). PMD's archetypes are a ready-made taxonomy for expanding
bounty variety.

### Why it fits

`BountyTracker.Bounty` is already a closed enum of seven, picked three-per-week
per owner from a seeded shuffle. Adding archetypes is *content work on an
existing system*: new enum entries, new detection calls in `RunLifecycle`, no
new infrastructure. Several PMD archetypes already exist in the brainstorm
under other names, so this is partly a relabelling and partly new detection.

### Mapping

| PMD archetype | Existing or new | Detection site |
|---|---|---|
| Rescue | new (ties to §1 above) | `RescueService` completion |
| Escort | new | a villager or allay entity surviving K rooms; `RunLifecycle.completeRun` checks a survival flag |
| Item retrieval | new | a named item in the run's loot, turned in at the room; `RitualListener` on the lodestone with the item held |
| Outlaw hunt | maps to "Nemesis mobs" in `ROGUELITE_CONTENT_BRAINSTORM.md` | an elite that killed you returns named and buffed; kill detection in `RunLifecycle` |
| Exploration | maps to the T4 silence room and T7 heist | reach the exit without triggering sculk / without sprinting; a `RunLifecycle` flag set by the situation |

### What to keep from PMD, what to drop

- **Keep:** typed missions with distinct verbs (rescue vs retrieve vs hunt).
  Typed bounties read as a job board, where the current seven all reduce to
  "do more of X." Distinct verbs are what make a bulletin board feel like a
  bulletin board.
- **Keep:** the reward is the existing `BountyTracker` payout (echo shards +
  emeralds, owner bonus). No new currency.
- **Drop:** PMD's rank ladder for rescue points. That is a power-creep meta
  and `VISION.md` §3.2 forbids it. The rescue *count* can be a diary-style
  record (knowledge, not power), never a stat that unlocks anything.
- **Drop:** the personality test that picks your starter. Not applicable.

### Rough scope

Pure enum and detection additions to `BountyTracker` and `RunLifecycle`, plus
situation flags for the exploration and escort types. No new classes for the
bounty side; §1's `RescueService` is the only new class this archetype needs.

---

## 4. Monster House room template

A PMD set-piece: a room that is wall-to-wall enemies with a high-value item in
the centre. The tension is that you cannot leave without fighting through.

### What it would be here

A `dungeon_room/*.json` entry plus a situation, no new Java. The room carries
four to six trial spawners and a vault or chest in the centre, with the exits
gated until the spawners are cleared (the existing `access: "gated"` field and
the trial-spawner clear logic `TrialContent` already runs).

### Why it fits

- The `dungeon_room` schema already has `roles` (a closed set of five:
  entrance, exit, encounter, loot, corridor), `access: "gated"`, `pressure:
  "local" | "omen"`, and a `content` situation id. A monster house is an
  `encounter` room with `access: "gated"` and a custom situation that spawns
  multiple trial spawners. Nothing in the schema forbids it.
- It is the combat-heavy counterpart to the grove / garden room type called
  out in `VISION.md` §3.7.3 as the fix for the wood gap. Both are one template
  and one `dungeon_room/*.json` entry, no Java.
- It gives the `encounter` role a recognisable set-piece the way the grove
  gives `loot` one, which makes the composition space (§5) read more
  distinctly per tier.

### Open design questions

- **How many spawners before it stops being a fight and starts being a
  grind.** PMD's monster houses are overwhelming because the genre is
  turn-based; here four to six trial spawners is already a lot given the
  trial-spawner eject-on-clear behaviour. Start at four and tune.
- **Whether the centre item is a vault or a chest.** A vault needs a trial
  key, which means the player has to clear at least one spawner before the
  reward is reachable. That is the right shape: the reward is gated by the
  fight, not by a key the player brought in. Use a vault.
- **Whether it gets its own situation tag.** The `provides` / `requires` tag
  vocabulary is a closed set of fifteen. A monster house does not need a new
  tag; it is an `encounter` room with `provides: ["mob"]` and a situation that
  the generator already understands. No schema change.

### Rough scope

One room template structure, one `dungeon_room/*.json`, one situation entry in
the shipped situations namespace. No code, no schema change. This is the
cheapest idea in the set.

---

## 6. Recycle station (Spinda's Cafe)

PMD lets you feed unwanted items into a cafe to get a random useful one.
Pocket Dungeons' loot economy will generate junk: excess cobble, low-tier
gear that is beneath a high-keystone player, duplicate enchant books.

### What it would be here

A third room station, alongside the reroll station (`RerollStation`), the
gamble station (`GambleStation`), and the blacksmith NPC (`BlacksmithNPC`).
Feed N junk items in, get one weighted-random useful item out. Built from a
vanilla block (composter is the obvious diegetic choice, or a second
wandering-trader-style NPC).

### Why it fits

- It gives the room another **load-bearing station** (reinforces `VISION.md`
  §3.1: the room is the engine, dungeons are the faucet). A recycler turns the
  dungeon's waste stream into something repeatable, which is exactly the
  faucet-vs-engine split §3.7.1 draws.
- It reduces decoration-starving (§3.6.1): a player drowning in cobble can
  trade it toward a palette block they actually want, without a crafting
  table.
- It is the same shape as `GambleStation`: a configured block, a level gate
  via `StationSupport.levelTooLow`, an SGUI screen or a simple right-click
  consume-and-payout, and a `LootTables` roll for the output. The
  `GambleStation.handleTrade` pattern (intercept the trade, run the real draw,
  deliver via `Payout.deliver`) is almost a literal template.
- It can count toward the existing `HIGH_ROLLER` bounty the same way the
  gamble station does, so no new bounty wiring.

### Open design questions

- **Input currency.** Two options: (a) any item counts as one unit of junk,
  N units buy one roll; (b) a whitelist of "junk" tags (cobblestone-class,
  low-tier gear) counts, and the roll quality scales with the input's tier.
  (b) is more interesting but needs a tag whitelist; (a) is one afternoon's
  work. Start with (a), grow into (b).
- **Output table.** A new `recycle/<tier>` loot table family, parallel to
  `gear/<slot>_<tier>`. The output should be weighted toward building blocks
  and consumables (the §3.7.4 "guaranteed floors for consumables" rule),
  not toward gear, so the recycler does not become a second gamble station.
- **Block choice.** The composter is the most diegetic vanilla block for
  "feed stuff in, get stuff out," but it has a vanilla interaction (bone
  meal). `GambleStation` already solves this by picking an unlikely block
  (`waxed_oxidized_copper_chest`) so the claim is never surprising. Follow
  that pattern: pick a block an operator is unlikely to use for its vanilla
  purpose, or fully claim it the way the gamble station does.
- **One mixin budget.** `CONVENTIONS.md` allows exactly one mixin class. A
  recycler station should not need one: `RitualListener.onUseBlock` already
  dispatches to stations ahead of the lodestone branch, the same way
  `GambleStation.onUse` hooks in. No new event surface.

### Rough scope

One new class `RecycleStation` (parallel to `GambleStation`), one
`ConfiguredItem` for the block, one `LootTables` roll helper, one new loot
table family. No mixin, no custom item, no client mod. Slightly more than the
gamble station was at M16, because of the input-currency question, but the
same shape.

---

## What was considered and rejected

- **Recruitment of defeated mobs.** PMD's core loop. The vanilla-native
  version already lives in `ROGUELITE_CONTENT_BRAINSTORM.md` as the allay /
  wolf / cat room pet. Do not build a full recruitment system; the pet stable
  is the right scope.
- **IQ / gummy stat progression.** A permanent stat-up meta. `VISION.md` §3.2
  is explicit: the two things that persist are a room and a number, and
  neither makes the next run easier. A gummy-style power tree violates the
  no-power-creep rule by construction. The *knowledge* reframing (bestiary,
  diary hints, already in the brainstorm under "Knowledge as Progression") is
  the safe version.
- **Belly / hunger as a tight resource.** Minecraft hunger already exists. A
  "Famine" affix (faster drain) is fine; a separate belly stat is not worth
  the UI and violates the no-custom-hud constraint.
- **Type matchups, linked moves, move sets.** No move system; not applicable.
- **Turn-based movement.** Not applicable.
- **Kecleon shop with theft consequence.** A neutral merchant in a dungeon
  room that swarms you if you steal. pocketdungeons already has the
  blacksmith NPC for the buy side. The steal-or-pay risk is an affix-shaped
  decision (a room template with a wandering-trader NPC, a chest, and an
  aggro rule), not a station. Worth a future room template, but lower-leverage
  than the four above, so it is not in this set.
