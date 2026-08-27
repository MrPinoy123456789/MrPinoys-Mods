# D3 Progression Plan

> **What this is:** the design pass after M9, promoted from
> `DOOR_LADDER_BRAINSTORM.md` sections 1-4 into a sequenced, buildable plan. It
> turns the keystone ladder from "the same three doors, scarier" into a two-tier
> map system where the level gates *which doors you can reach*, and adds the five
> Diablo-3-inspired sinks that drain the faucet the dungeons have been.
>
> **What this is not:** a status file. No checkboxes here. Status lives in
> `PROGRESS.md`'s successor (the README index + `plans/COMPLETED-MILESTONES.md`).
> This document defines the milestones; it does not track them.
>
> **Relationship to the brainstorm:** the brainstorm stays the scratchpad. Where
> this plan and the brainstorm disagree, this plan wins, because the decisions
> below are the ones that were actually made. The brainstorm's open questions
> (section 5) are resolved here where they shape structure, and carried forward
> as in-milestone tuning where they do not.

---

## Decisions locked before this plan was written

These four were the forks that change the plan's structure. They were settled
with the user before drafting; the rest of the open questions are tuning and
live inside the milestones below.

1. **Scope: the full package.** Two-tier doors, the level cap and mob scaling
   that give the ladder meaning past 10, and all five sinks. This is multiple
   milestones (M10-M17), sequenced in this document.
2. **The reframe is adopted.** The keystone level gates *which doors/maps are
   available*, not just how hard the same door is. Doors become maps; the recipe
   system becomes the adventure graph; `Affix.Kind.ELECTIVE` is retired. This is
   the load-bearing decision; everything else follows from it.
3. **Ominous becomes a map property.** It is a property of the door/map you are
   offered, not a modifier you elect. Both electives go: `FRAGILE` is deleted,
   `OMINOUS` migrates, `Affix.Kind.ELECTIVE` is removed. The enum becomes purely
   seeded.
4. **Fuel currency: left open.** The two-tier door mechanism is
   currency-agnostic. Echo shards (diegetically perfect, need loot-table work)
   and diamonds (already in tables, risk self-funding) are both still in play.
   M12 specifies the mechanism and leaves the currency as a config-selected
   variable, settled during implementation. See "Fuel currency" under M12.

**Not blocked by the lore decision.** `VISION.md` §9 says "no system may depend
on the fiction to make sense." Nothing in this plan does. The Herobrine Cube
(M17) borrows a lore-flavoured *name*, but its mechanic (extract a power, imbue
it elsewhere) is self-contained and would work under any name. If the §9
"not a lore project" line is revised, the Cube can keep its name; if it is not,
the Cube ships under a neutral name and nothing else changes. The plan does not
wait on `LORE.md`.

---

## The dependency graph

```
M10  Ladder reframe          cap 25->100, mob scaling, affixes past 25,
     │                       drop Fragile, Ominous->map property, spawner gating
     │
M11  Adventures              recipe system -> adventure graph + currentTheme;
     │                       boss terminator room (new content)
     │
M12  Two-tier doors + fuel   door 1 = free/fuel-source/no-deplete;
     │                       doors 2/3 = fuel-cost/timed/depleting/level-gated
     │
M13  Gear loot pool          author tiered armour/weapon tables (CONTENT ONLY)
     │  (can run parallel to M10-M12)
     │
     ├── M14  Gear reroll (3.2, lapis)        depends on M13
     ├── M15  Armor trims (3.3)              depends on M13; builds equip-time
     │         │                              attribute plumbing M17 reuses
     │         └── M17  Herobrine Cube (3.5)  depends on M15 + M11
     └── M16  Kadala gamble (3.4, emeralds)  depends on M13
```

Two things to read off that graph before any work starts:

- **M13 is the long pole for the sinks, and it is content, not code.** None of
  the gear-touching sinks (3.2 reroll, 3.4 gamble, 3.5 Cube extraction) have
  anything to operate on until tiered armour/weapon loot tables exist. Today
  every `chests/tier_*.json` carries materials, blocks, food and consumables,
  and zero wearable or wieldable items (verified by grep across all twelve tier
  tables). M13 is pure JSON authoring with sensible enchantment weighting, and
  it can run in parallel with M10-M12 so it is ready when the sinks are.
- **M15 before M17 is not a scheduling preference, it is a code dependency.**
  The Cube's equipped-power check is the same equip-time attribute-modifier
  shape the trim bonus needs, generalised. M17 literally reuses M15's plumbing,
  so M15 must land first.

---

## M10 - Ladder reframe: the level gates access, difficulty lives on the map

**Goal:** raise the cap to 100, scale mob strength with the level, spread affix
thresholds across the new range, retire the elective affixes, and gate run
completion on spawner clearance. After M10, a level-100 key is not "the same
doors, scarier"; the ladder has room to climb and the run cannot be sprinted.

**Depends on:** nothing (M9 is code-complete, the tree is clean).

**Scope:**

- **Cap 25 -> 100.** `PocketDungeonsConfig.keystoneMaxLevel` from 25 to 100.
  `KeystoneMath.clampLevel` already takes `maxLevel` as a parameter, so the clamp
  itself needs no change; the config bump is the edit.
- **Mob strength scaling.** Apply attribute modifiers to mobs that spawn in the
  dungeon dimension, scaling with the run's keystone level. The mod already
  imports `ServerLivingEntityEvents` (`Instances.java:3`); the listener is the
  clean shape, one hook, no per-spawner NBT surgery. +1% per level is the
  starting curve, so a level-100 key faces roughly twice-strong mobs. The
  listener reads the level off the instance the mob spawned inside, not a global.
- **Affix thresholds past 25.** `AffixMath.seededCount` is hardcoded at
  `FIRST=5, SECOND=11, THIRD=17` and caps at three seeded affixes. With a 100
  cap, three thresholds leave 83 levels flat. Two options, decided in-milestone:
  add more thresholds (e.g. 5/11/17/25/40/60), or move to a percentage-based
  count (`level / 20`, capped). The percentage system is cleaner against an
  arbitrary cap; the fixed-threshold system preserves the "named band" feel.
- **Intensifier bands past 25.** `AffixMath.intensifier` caps at "Unhinged 21+".
  Extend the bands across the new range. The bands are a pure function of level
  and must stay watcher-stable (the instance watcher rewrites stale remotes in
  place; a label that rolled would churn on every reconciliation).
- **Drop `FRAGILE`, migrate `OMINOUS`.** Delete `Affix.FRAGILE` (it existed only
  as the cost of the +3 door, which the reframe removes). Move `OMINOUS` from
  `Kind.ELECTIVE` to `Kind.SEEDED`, then delete `Affix.Kind.ELECTIVE` entirely.
  `AffixMath.elective` and `DungeonLog`'s elective-affix storage become dead
  code: the elective set is always empty once the enum has no elective members.
  Mark the storage field superseded rather than deleting it on the first pass
  (save-format safety), and remove it once a migration confirms no live save
  carries elective data.
- **Spawner-gated completion.** Today `completeRun` fires on first pad contact.
  Gate it on clearing a fraction of the run's trial spawners first. Track
  spawner positions at stamp time (collect across encounter cells into
  `InstanceRecord`); on pad contact, check that N% have reached
  `TrialSpawnerState.COOLDOWN` (verified in 26.2: `TrialSpawnerBlockEntity`
  implements `TrialSpawner.StateAccessor` and exposes `getState()` returning the
  `TrialSpawnerState` enum, which has a `COOLDOWN` constant). 70-80% is the
  starting band. A total-completion seeded affix raises the threshold to 100%;
  its kiss is open (see open questions).

**Touch points:** `PocketDungeonsConfig.keystoneMaxLevel`; `Affix.java` (delete
`FRAGILE`, migrate `OMINOUS`, remove `Kind.ELECTIVE`); `AffixMath.java`
(thresholds, intensifier bands, `elective` becomes dead); `DungeonLog.java`
(elective storage superseded); `Instances.completeRun` (spawner gate); a new mob
scaling listener; `InstanceRecord` (spawner position tracking).

**Done when:** a level-100 key is mintable, mobs in its run hit roughly 2x
strength, the keystone name reads a sensible intensifier across the whole range,
`Affix.Kind.ELECTIVE` no longer exists, and a run that sprints to the pad
without clearing spawners is refused completion with a message.

**Open (tuning, in-milestone):** the threshold system (fixed vs percentage); the
exact intensifier band boundaries past 25; the spawner-clear percentage; the
total-completion affix's kiss (Q5 from the brainstorm; the curse is clear, the
gift is not, and the M4 rule says no kiss means no ship).

**Verify against the 26.2 jar:** the mob-scaling listener's exact event
signature (`ServerLivingEntityEvents` is imported already; confirm the
after-spawn hook that can mutate attributes before the mob ticks); the
`TrialSpawnerState` transition into `COOLDOWN` (does it require the spawner to
have been activated, or does an untouched spawner sit at `INACTIVE` and need
excluding from the count?).

---

## M11 - Adventures: the recipe system becomes a descent graph

**Goal:** replace the backward-looking recipe match (`RecipeMatcher`'s ordered
tail over the last three themes) with a forward-looking adventure graph. Each
theme defines its possible next themes; the doors draw from the current theme's
transition set; a boss terminator ends the adventure and returns the player to
the entry-point themes. The folklore dynamic is preserved through a hidden
graph rather than hidden compositions.

**Depends on:** nothing strictly, but it is the door-selection mechanism M12
builds on, so it lands before M12.

**Scope:**

- **`AdventureGraph` replaces `DungeonRecipes` + `RecipeMatcher`.** A transition
  lookup is `currentTheme -> weighted set of nextThemes`, one map read. The new
  class is smaller than the two it replaces. Loaded the same way
  `DungeonRecipes.load` loads today (reloadable datapack resource under a new
  `dungeon_adventure` path), with the same rejection logging.
- **`currentTheme` replaces `recentThemes`.** `DungeonLog`'s 3-deep ordered
  window (`ThemeHistory.RECENT_LIMIT = 3`, with its codec field and `push`
  maintenance) becomes a single `currentTheme` string. `ThemeHistory` is deleted.
  The `recentThemes` codec field is kept and marked superseded for save-format
  safety, then removed once migration confirms.
- **Entry-point, descent, and boss nodes.** The graph has three node kinds:
  entry-point themes (the "overworld" set you return to after a boss), descent
  nodes (themes that branch further), and boss terminators (themes that end the
  adventure and reset `currentTheme` to an entry point). Depth is how many floors
  deep you are; the deeper you go, the rarer the nodes, per the brainstorm's
  weighted-rare-node argument.
- **The boss room is new content.** Today there is no boss; there are trial
  spawners and a terminal pad. A boss terminator is a new room type, a new
  encounter, and a new completion condition. This is net-new content work, not a
  reframe. It ties into M10's spawner gating: the boss could be the ultimate
  gated completion. Scope the boss as one proof encounter in this milestone;
  more can follow.
- **The graph stays hidden in-game.** The doors show three next options and
  nothing else. No "you are here" map, no node list, no depth counter. This is
  the same principle as the recipe system's "you can see the ingredients, not
  the recipe," extended to a graph. Surfacing the graph collapses the
  word-of-mouth dynamic the whole design reaches for.

**Touch points:** `DungeonRecipes.java`, `RecipeMatcher.java`,
`ThemeHistory.java` (deleted, replaced by `AdventureGraph`); `DungeonLog.java`
(`recentThemes` -> `currentTheme`); `DungeonRecipe.java` (replaced by a
transition record); the door-offer selection path that currently calls
`DungeonRecipes.match` to override the third door's theme; a new boss room
template and `dungeon_room/*.json` entry.

**Done when:** completing a theme offers doors drawn from that theme's
transition set, a boss terminator ends the adventure and resets to an entry
theme, and the recipe system's three files no longer exist.

**Open (tuning, in-milestone):** the authored transition graph (which themes
lead to which); rare-node weights; boss encounter design; whether the boss room
is a single proof or a small set.

**Verify against the 26.2 jar:** nothing new; the graph is pure JDK and the
loading path mirrors the existing one.

---

## M12 - Two-tier doors: the Nephalem/Greater Rift split, plus fuel

**Goal:** door 1 becomes the free, fuel-producing, non-depleting farming tier;
doors 2 and 3 become the timed, fuel-consuming, depleting, level-gated Greater
tier drawn from the adventure graph. This is what makes "the level gates which
doors are available" concrete rather than aspirational: a level-100 key reaches
door-2/3 offers a level-10 key cannot open at all.

**Depends on:** M10 (level gates access) and M11 (adventure graph for door
selection).

**Scope:**

- **Door 1: free, fuel-producing, non-depleting.** Untimed or generously timed,
  spends no fuel, never depletes the keystone on failure. Its second job (the
  first is being the safe option) is producing fuel: a door-1 run pays out the
  fuel currency at a modest rate. `KeystoneMath.deplete`'s "a keystone never goes
  to zero" principle is already load-bearing; the free door is the same
  principle applied to fuel.
- **Doors 2 and 3: Greater tier.** Keep the clock, the depletion, and the level
  gate they have today, and additionally cost fuel. Drawn from the adventure
  graph (M11). Level-gated: a door-2/3 offer only appears if the keystone level
  clears its threshold, so a low key literally cannot reach the premium path.
- **Fuel currency (open variable).** The mechanism is currency-agnostic. Two
  candidates remain:
  - **Echo shards:** diegetically perfect (the keystone is a recovery compass,
    crafted from echo shards, so the same item that mints the key powers the
    premium doors). Need adding to tier 2/3 loot tables first, weighted low. A
    content edit, not a code change.
  - **Diamonds:** already in the tables, no content prerequisite. Risk:
    self-funding, since premium rooms drop diamonds. Mitigation is a loot-table
    edit either way (premium rooms must drop *different* loot, not more fuel).
  The plan settles the currency during M12 implementation by making it a
  config-selected `fuelItem` (the `ConfiguredItem` pattern `Keystone` and
  `TrialContent` already use), defaulting to whichever the loot-table work
  supports first. The two ladder-scale and gear-scale sinks stay on different
  currencies regardless (emeralds for the gamble, lapis for the reroll), per the
  orthogonality argument.
- **Loot-table edits.** Add the fuel currency to the free door's payout (and to
  tier 2/3 tables if echo shards are chosen). Ensure premium rooms drop
  *different* loot (decoratives, theme blocks, the provenance materials) rather
  than more fuel, so the loop does not feed itself.
- **Pacing the free door.** An unlimited, non-depleting door risks becoming the
  only door anyone takes. Whether door 1 gets its own shorter timer, and how
  much fuel a door-1 run produces relative to what a door-2/3 run costs, are
  settled in-milestone so the loop neither stalls (never enough fuel to push)
  nor trivializes (door 1 alone drowns the sink).
- **Deferred: a crafted fourth door.** A design pass considered a Herobrine-Cube
  ritual (8 obsidian plus flint and steel, in the shape of a nether portal) that
  crafts a vanilla door item marked with a tier, placed by the player at a
  fourth selector position and read by `RitualListener` before the placement
  consumes the marker (a door has no block entity, so `custom_data` does not
  survive becoming a block). The recipe ships with no unlock advancement, so it
  is craftable but never listed, which is a hidden-recipe discovery mechanic
  for free. Deliberately out of scope for this milestone: it is additive and
  blocks nothing here, and what the door offers depends on the adventure graph
  (M11) and the cap curve (M10) settling first. If it is picked up later, it
  should gate `lootTier` itself (`min(levelTier, clearedTier)`, both landing
  together, since a tier gate with no door to clear it strands the ladder at
  tier 1) rather than shipping as a cosmetic extra. See `DISCOVERIES.md` traps
  13 to 17 for what was verified against the 26.2 jar before deferring: the
  door-block material list, the recipe-component and hidden-recipe mechanics,
  and the placement/capture pitfalls.

**Touch points:** the door-offer plumbing (`Keystone.offers`,
`Keystones.grantOffer`, `DialogScreens.doorOffer`, the `chooseOffer` path);
`KeystoneMath.deplete` (door 1 exempt); `InstanceRecord` (fuel cost / fuel
produced); `PocketDungeonsConfig` (fuel item, door-1 timer, fuel rates); the
`chests/tier_*.json` tables (fuel currency entries, premium-room loot
differentiation).

**Done when:** a broke player always has the free door, the free door pays out
fuel, doors 2/3 refuse entry without enough fuel, and a level-10 key does not
see the door-2/3 offers a level-100 key does.

**Open (tuning, in-milestone):** the fuel currency choice; the fuel cost per
Greater door; the fuel production rate of the free door; whether door 1 has its
own timer and how long.

**Verify against the 26.2 jar:** nothing new; the `ConfiguredItem` pattern and
the door-offer plumbing already exist.

---

## M13 - Gear loot pool (content, no Java)

**Goal:** author tiered armour and weapon loot tables so the gear-touching sinks
have something to operate on. This is the one prerequisite in the whole plan
that is "author new loot content before any code is worth writing," not "verify
against the jar."

**Depends on:** nothing. Can run in parallel with M10-M12.

**Scope:**

- **Per-slot, per-tier gear pools.** Author armour and weapon entries across the
  three tiers, with sensible enchantment weighting per tier. A tier-1 pool is
  iron-grade gear with low enchantment rolls; a tier-3 pool is diamond/netherite
  with higher rolls. The pools are new JSON under
  `data/pocketdungeons/loot_table/chests/`, or new tables the sinks draw from by
  `ResourceKey<LootTable>` (the same mechanism `TrialContent`/`LootTables`
  already use for chests, keyed by slot for the gamble).
- **Provenance holds.** The gear follows the `VISION.md` §3.6.1 tier palette:
  tier 1 is stone/iron/wood, tier 2 is deepslate/copper, tier 3 is end
  stone/ancient-city materials. A piece's tier is readable off the table it came
  from, which the reroll station (M14) and the gamble (M16) both need.
- **Self-sufficiency check.** `VISION.md` §3.7: nothing required for progression
  may live outside the dungeon loop. Gear from loot tables satisfies this. The
  enchantment weighting must not assume an enchanting table exists outside the
  dungeon (it does not, under §3.7); pre-rolled enchantments on the gear are the
  supply, and the reroll station (M14) is the player's lever over them.

**Touch points:** new `chests/*.json` loot tables; possibly new
`loot_table/gear/*.json` tables for the slot-keyed pools the gamble draws from.

**Done when:** a tier-3 run can drop a diamond chestplate with enchantments, a
tier-1 run drops iron-grade gear, and the gamble (M16) and reroll (M14) have
tables to read from.

**Open (tuning, in-milestone):** the exact item and enchantment distribution per
tier; whether the gamble draws from the chest tables or its own slot-keyed
tables; enchantment weighting curves.

---

## M14 - Gear reroll station (3.2): a lapis sink, orthogonal to fuel

**Goal:** a room station that rerolls one enchantment on a piece of gear, the
player's choice of which, costing lapis that scales with the item's tier. This
is the gear-scale sink; fuel (M12) is the ladder-scale sink. The two answer
different halves of "what is the level-100 player working toward": fuel is why
level 100 is worth reaching, lapis is why the gear it drops is worth using.

**Depends on:** M13 (gear exists in the loot).

**Scope:**

- **Block interception, not vanilla enchanting.** A themed block (the
  `ConfiguredItem` pattern covers "what block, configurable, sane default")
  right-clicked opens a reroll dialog, following the `RitualListener` house
  pattern of intercepting a specific block's use rather than replacing a vanilla
  one. A player's actual enchanting table keeps doing exactly what vanilla
  enchanting tables do. The reroll is additive.
- **One enchantment at a time.** The dialog (`DialogKit`/`DialogScreens`, the
  same vanilla-dialog mechanism the door offers and party roster already use)
  lists the item's current enchantments; the player picks one. That enchantment
  is replaced with a different one drawn from the valid pool for that item type
  (excluding the one just removed and any already on the item), at a random
  level within that enchantment's normal range. Every other enchantment is
  untouched. A full reroll was considered and rejected: one at a time reads as a
  controllable decision with a target, and it cannot produce a strictly worse
  item than the one that walked in.
- **Cost scales with item tier, not enchantment level.** The gear carries a tier
  signal (the table it came from). A tier-1 drop rerolls cheap; deep-key gear
  costs more lapis. Same shape as `KeystoneMath.deplete`'s cost curve: the
  further in you are, the more each decision costs, never so much that the door
  closes.
- **Gating.** Whether the station is available from the first room or unlocked
  behind a keystone level / completed adventure is open. The fuel-gated-door
  precedent argues for *some* gate, since an ungated sink stops sinking once
  lapis overflows.

**Touch points:** a new station class (the `RitualListener` block-use shape); a
new `DialogScreens` screen (the reroll picker); `PocketDungeonsConfig` (reroll
block, lapis cost curve); the `chests/tier_*.json` tables (lapis entries, if not
already present).

**Done when:** a player right-clicks the station with a piece of gear, picks an
enchantment, pays lapis, and gets back the same item with that one enchantment
replaced and the rest untouched.

**Open (tuning, in-milestone):** the lapis-per-tier curve; whether the station is
gated and how; whether premium doors should avoid dropping extra lapis (the same
self-funding warning as fuel).

**Verify against the 26.2 jar:** the enchantment pool lookup for an item type
(how vanilla's own table avoids offering a duplicate; the data structure behind
valid-enchantments-per-item); reading and writing `DataComponents.ENCHANTMENTS`.

---

## M15 - Armor trims (3.3): consumed templates that grant a real bonus

**Goal:** armor trim templates drop as dungeon loot, applying one at a smithing
table permanently consumes it (the vanilla duplication recipe is removed for
these), and the trim material grants the piece a real combat bonus, not just a
colour. This is the first idea in the plan that reaches past the dungeon loop
into ordinary overworld combat, and that is a deliberate commitment worth naming.

**Depends on:** M13 (templates and materials in the loot).

**Scope:**

- **Templates as a sink, not a toll.** Vanilla lets a player duplicate a trim
  template after applying it (template + diamond + seven material at a crafting
  table). If dungeon-found templates go through that recipe, they are not a
  sink. The duplication recipe must be removed for these templates specifically,
  or globally if there is no clean way to distinguish a dungeon-found template
  from an ordinary one at the recipe level.
- **Material decides the bonus, pattern stays cosmetic.** Vanilla ships roughly
  ten trim materials crossed with roughly seventeen patterns, too many cells to
  author a distinct bonus for each. Split the axes: the material determines the
  bonus (an attribute modifier: diamond trims toughness, netherite knockback
  resistance, gold a small speed bonus, lapis trims XP gain, etc.) and the
  pattern stays cosmetic and a rarity signal. That is "ten bonuses, tune once"
  instead of "170 combinations, tune all."
- **Equip-time attribute plumbing.** Reading which trim sits on a worn piece is
  a `DataComponents.TRIM` read (verified in 26.2: `DataComponents.TRIM` is
  `DataComponentType<ArmorTrim>`, and `ArmorTrim` is a record with `material()`
  returning `Holder<TrimMaterial>` and `pattern()` returning
  `Holder<TrimPattern>`). Turning that into an attribute modifier is an
  equip-time or slot-change check, the same shape as `SilenceListener`'s
  item-use interception but keyed on armour slots changing. **This plumbing is
  the part M17 reuses**, generalised from "what trim is on this piece" to "which
  of my extracted powers am I slotting."
- **The overworld-combat tension.** `VISION.md` §3.7 says nothing *required for
  progression* may live outside the dungeon loop; the templates and materials
  satisfy that (both are dungeon-loot-gated). But a worn trim's bonus applies
  wherever the player wears the armour, overworld included. That is consistent
  with the letter of §3.7 but is a bigger commitment than M14's reroll station.
  The alternative is a dungeon-only bonus (checked against the player's
  dimension, the same guard `RoomProtection` already reads), which resolves the
  tension at the cost of a trimmed piece feeling like a prop outside the
  dungeon. This is a real call, settled in-milestone.

**Touch points:** the `chests/tier_*.json` tables (trim template and material
entries); a new equip-time listener (the `SilenceListener` shape, keyed on
armour slot changes); the vanilla trim-template duplication recipe (removed or
conditionally excluded); `PocketDungeonsConfig` (the material-to-attribute
table, the dungeon-only flag if chosen).

**Done when:** a dungeon-found trim template applies at a smithing table,
consumes the template, grants the piece a material-based combat bonus, and
cannot be duplicated.

**Open (tuning, in-milestone):** the material-to-attribute table; whether
patterns carry real rarity weights or are cosmetic variety; whether the bonus is
dungeon-only or global; the duplication-recipe removal mechanism.

**Verify against the 26.2 jar (blocking for this milestone):** whether trim
template duplication is its own data-driven recipe type that can be conditionally
excluded, and whether a template carries any distinguishing data (an NBT tag, a
custom component) once it leaves the loot table, or whether every copy of a given
template is identical and the removal has to be global. This was flagged in the
brainstorm and is the one jar-verification task that can change the milestone's
shape rather than just its tuning.

---

## M16 - Kadala gamble (3.4): emeralds for a random piece in a chosen slot

**Goal:** spend emeralds, pick a slot, get back a random item that fits it. No
guarantee of quality within the slot, which is the whole point: a currency sink
that trades certainty for volume, sitting next to M14's guaranteed, targeted
reroll. A player who knows what they want rerolls it; a player who just wants
more shots at *something* for that slot gambles.

**Depends on:** M13 (the gear pool exists).

**Scope:**

- **Emeralds are the cleanest fit.** The gamble's output is gear, not more
  currency, so there is no self-funding loop (unlike an emerald-gated door would
  have). Emeralds get a real second job (a currency spent here, a material found
  everywhere else) without the trap. Keeping emeralds here and echo shards or
  diamonds as the door fuel keeps the ladder-scale and gear-scale sinks on
  different currencies.
- **Slot first, tier second.** The player picks one of the eight equipment
  slots, the way Kadala's menu does. The cost scales with which tier's gear pool
  the gamble draws from. A tier-1 gamble is cheap and draws from the tier-1 pool;
  a tier-3 gamble costs more and draws from better gear, still with no guarantee
  of which piece or what it rolls.
- **The gear pool is M13's work.** The gamble draws from the slot-keyed,
  tier-keyed tables M13 authors, via the same `ResourceKey<LootTable>` mechanism
  `TrialContent`/`LootTables` already use for chests, keyed by slot instead of by
  container. If M13 chose to fold the gear into the existing chest tables, the
  gamble reads a subset; if M13 authored separate slot tables, the gamble reads
  those. Either way, M13 must land first.
- **Implementation shape.** Same house pattern as the rest: a block
  interception (`RitualListener`'s shape) opens a dialog (`DialogKit`/
  `DialogScreens`) offering the eight slots and whichever tiers the player's
  keystone level has unlocked; confirming a slot and tier spends the emeralds
  and draws one item.

**Touch points:** a new station class (the `RitualListener` block-use shape); a
new `DialogScreens` screen (slot and tier picker); `PocketDungeonsConfig`
(emerald cost per tier, slot weighting); the M13 gear tables (the draw source).

**Done when:** a player right-clicks the gamble station, picks a slot and tier,
pays emeralds, and receives one random item from that slot's tier pool.

**Open (tuning, in-milestone):** the emerald cost per tier; whether every slot
costs the same at a given tier or weapon slots cost more (D3 weighs different
Kadala pulls differently); whether the gear pool is a subset of the chest tables
or its own thing so a lucky gamble cannot out-produce opening the run's chests.

**Verify against the 26.2 jar:** nothing new beyond M13's pool work.

---

## M17 - The Herobrine Cube (3.5): extract a power, imbue it anywhere

**Goal:** a crafting-table ritual that consumes a rare item once, permanently
remembers one fixed power it carried, and re-applies that power to any future
item cheaply. Two rituals: extract (consume the rare item, add its power to the
player's permanent set) and imbue (apply a previously extracted power to an
ordinary item for a small material cost). Capped at equip time so "which powers
do I run" stays a real choice, not a checklist.

**Depends on:** M15 (the equip-time attribute-modifier plumbing, generalised)
and M11 (rare adventure-graph nodes as the extractable-item source).

**Scope:**

- **Crafting-table ritual interception (new plumbing).** `RitualListener`
  intercepts a right-click today, not a craft result. The Cube intercepts a
  craft result: the right combination of items in a crafting grid does not
  produce a normal crafted result but performs a ritual instead. A crafting
  table anywhere still crafts normally for anything that is not a recognised
  ritual combination. This is the one genuinely new plumbing pattern in the
  plan; the closest existing pattern (`RitualListener`) is the one to study, not
  copy verbatim.
- **Extract.** Place a qualifying item in the grid; its one fixed power is added
  to the player's permanently remembered set (persisted the same way
  `DungeonLog.Entry` tracks `completedThemes`, a per-player, never-truncated
  collection) and the item is consumed. This is the real sink: a hard-won item
  is gone for good, in exchange for never needing another like it.
- **Imbue.** Place an ordinary item of the matching slot plus a smaller material
  cost in the grid; one previously extracted power (player's choice) is applied
  to that item. Cheap and repeatable; the expensive part already happened at
  extraction.
- **What is extractable stays rare.** Not ordinary tier loot, not gamble (M16)
  or reroll (M14) output: a fixed, build-defining effect is a different kind of
  reward. The source is the rare adventure-graph nodes M11 describes (a
  "pharaoh's chamber" drop, found once in a long while, down a path most players
  never walk). This gives M11's rare nodes a concrete reason to matter beyond a
  shell unlock or a theme. M11's rare-node reward list, M16's gear pool, and any
  shell-token list should be reconciled into one authored list rather than three
  separate ones.
- **Equip cap.** D3 caps how many extracted powers can be worn at once (three
  slots) even though the library grows arbitrarily. Without a cap, the Cube
  becomes a checklist: extract everything, wear everything, done. A small cap
  keeps "which powers do I run" a real choice every time a new one is found.
- **Reversibility.** D3's extraction is irreversible: once extracted, an item's
  power is a permanent unlock, never an item again. Whether that is too harsh a
  sink for a server where a mis-click costs a genuinely rare drop forever is a
  real call, settled in-milestone.

**Touch points:** a new craft-result interception listener (new plumbing); a new
extracted-power persistence field on `DungeonLog.Entry` (the `completedThemes`
shape); the M15 equip-time listener (generalised to read "which extracted powers
am I slotting" instead of "what trim is on this piece"); `PocketDungeonsConfig`
(imbue cost, equip cap, reversibility flag); the M11 rare-node reward list.

**Done when:** a player places a rare extractable item in a crafting grid, the
item is consumed and its power joins their permanent set, and they can later
imbue that power onto an ordinary item at a crafting table for a material cost,
with the equipped-power count capped.

**Open (tuning, in-milestone):** the exact extractable-item source (rare
adventure nodes only, or also a very-low-weight Cube-only gamble slot in M16);
the equip slot count and whether it is per-armour-piece or a fixed pool; the
imbue cost and currency; whether extraction is reversible.

**Verify against the 26.2 jar (blocking for this milestone):** the craft-result
interception point. `RitualListener` intercepts a right-click; the Cube needs to
intercept a `CraftingMenu`/`ResultContainer` craft. Confirm the Fabric API or
mixin surface that lets a server-side mod substitute a craft result before it
reaches the player, without a client mod. This is the second jar-verification
task that can change the milestone's shape.

---

## Cross-cutting: what this rewrites in the existing design

When any of this lands, these are the touch points outside the milestones
themselves. The superseded-design rule (house rule 6) applies: mark superseded,
do not delete, where a design document is being revised rather than implemented.

- `VISION.md` §1: the "safe / greedy / reckless" framing of the three doors.
  The doors stay three; what distinguishes them changes.
- `VISION.md` §3.1.1: "the three doors are runs and stay runs, not up for
  renegotiation." The doors stay runs; the +1/+2/+3 upgrade choice goes.
- `VISION.md` §3.2: the ladder's difficulty source moves from the keystone level
  to the map. The "meta-progression grants no power" line still holds: the room
  and the number still persist, and neither makes the next run easier. Mob
  scaling makes the run *harder* at higher levels, not the player stronger.
- `VISION.md` §5.4: the recipe system's folklore argument. Adventures preserve
  the dynamic through a hidden graph rather than hidden compositions; the
  principle holds, the mechanism changes. Mark §5.4 superseded by the adventure
  graph, do not delete it.
- `VISION.md` §8: "State of play" updates as each milestone lands.
- `ROADMAP.md`: M8 (Deferred) already points at `DOOR_LADDER_BRAINSTORM.md`;
  this plan supersedes that pointer for the D3 progression work. The roadmap's
  own rule is that it carries order, not status, so it gains the M10-M17
  sequence and no checkboxes.

**What this plan does not touch:** the visiting rework (brainstorm §8), the
immutable room shell (§9), the physical door selection (§10), or the
lodestone-menu consolidation (§11). Those are separate design tracks in the
brainstorm and are not in scope here. If any of them lands first, the door-offer
plumbing this plan depends on may have moved; reconcile at that time.

---

## Fuel currency (the one open structural variable)

M12 leaves the fuel currency as a config-selected `fuelItem`. The two candidates
and their tradeoffs, recorded here so the in-milestone decision has the context:

| | Echo shards | Diamonds |
|---|---|---|
| Diegetic fit | Perfect (keystone = recovery compass, crafted from echo shards) | None |
| Already in loot | No, need adding to tier 2/3 (content edit) | Yes |
| Self-funding risk | Low (premium rooms drop different loot) | High (premium rooms drop diamonds) |
| Gives a near-unused vanilla item a second purpose | Yes | No |

The mechanism is currency-agnostic, so the decision does not block the code. It
blocks only the loot-table work in M12. Whichever is chosen, the orthogonality
rule holds: fuel (doors) and emeralds (gamble) and lapis (reroll) stay on three
different currencies.
