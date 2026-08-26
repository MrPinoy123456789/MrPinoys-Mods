# Door and Ladder Brainstorm

> A working scratchpad for the August 2026 design pass. Nothing here is
> committed; none of it is guaranteed to ship. The point is to get the ideas
> and their tradeoffs down in one place so they can be argued about, picked
> over, and either promoted to a real plan or dropped.
>
> Where an idea touches an existing load-bearing design, the conflict is named
> rather than smoothed over.
>
> Related: `VISION.md` (the hook and pillars), `MYTHIC_PLUS_RECONCILIATION.md`
> (the affix reasoning), `plans/M4-affixes.md` (the affix system several of
> these ideas rework).

---

## 1. The reframe in one sentence

> **The keystone level gates door access; the door is a map; the map's
> difficulty comes from its tier, affixes and spawner gating; and a fuel sink
> pays for the better maps.**

Today the keystone level is the difficulty (seeded affixes stack at 5/11/17)
and the loot tier (1-4 / 5-9 / 10+), the three doors are +1/+2/+3 upgrade
offers, and there is no sink. This reframe moves difficulty onto the map and
makes the level gate which maps you can reach.

---

## 2. The ideas

### 2.1 Drop Fragile, keep Ominous as +1

`Affix.FRAGILE` exists for one reason: it is the cost of taking the +3 door.
With +1-only doors (see 2.2), it has no reason to exist.

`Affix.OMINOUS` stays. It already gives better loot at higher difficulty
(`tier_N_ominous.json` tables are richer than the plain ones); the only change
is that the Ominous door is also +1, not +2. The risk it carries moves from
"extra levels, double depletion" to "harder room, better payout, same +1."

**Open question: does Ominous stay elective or become a map property?** If
doors become maps (2.2), Ominous is a property of the map you are offered, not
a modifier you elect at the door. That is the cleaner direction and it retires
`Affix.Kind.ELECTIVE` entirely: both electives gone, the enum becomes purely
seeded. The alternative keeps Ominous as a per-door toggle, which preserves
the elective machinery but leaves the doors meaning "free / free-ominous / ?",
which is where the adventure framing (2.2) has to fill the third slot.

### 2.2 Doors as adventures (replacing the recipe system)

Today the recipe system looks backward: `RecipeMatcher.matches` scans your
last three completed themes (`ThemeHistory.RECENT_LIMIT = 3`) for an ordered
tail match against a recipe table. You compose `deepslate -> prismarine ->
blackstone` to unlock `drowned_vault`. The third door's theme is overridden by
the recipe match.

The adventure reframe looks forward instead. Each theme defines its possible
next themes. You complete "desert," and the next doors are drawn from desert's
transition set (sand temple, oasis, etc.). You complete sand temple, and its
transition set offers the next descent. Eventually a node terminates in a boss
room that ends the adventure and returns you to the entry-point themes.

**What it simplifies:**

- **State.** `recentThemes` (a 3-deep ordered window in `DungeonLog`, with its
  own codec field and `ThemeHistory.push` maintenance) becomes a single
  `currentTheme` string. One theme, not three, and no windowing logic.
- **Matching.** `RecipeMatcher`'s ordered tail match and
  `DungeonRecipes.match`'s scan-over-recipes both go. A transition lookup is
  `currentTheme -> Set<nextTheme>`, one map read. `DungeonRecipes` and
  `RecipeMatcher` collapse into a single `AdventureGraph` class, smaller than
  either.
- **Authoring.** A recipe asks an author to think about sequences across
  three runs. A transition asks them to think about one theme at a time:
  "what comes after desert?" That is a simpler authoring question, and it is
  the same question vanilla structure authors already answer (a desert well
  leads to a desert temple, not to an ocean monument).

**The folklore dynamic is preserved, arguably strengthened.** The transition
graph stays hidden in-game: the doors show you three next options and nothing
else. No "you are here" map, no node list, no "X floors remaining." To reach
a pharoahs chamber you might need:

```
desert -> sand temple -> descend -> descend -> dead grave robber (rare) -> pharoahs chamber
```

You cannot look that up. You learn it by playing, node by node, and the rare
nodes (dead grave robber) are the ones that generate the stories. "How did
you get there?" is still a sequence-discovery question, the same shape of
conversation `VISION.md` 5.4 designed recipes for. Three things make it
arguably better than recipes for word-of-mouth:

1. **Depth creates longer stories.** A recipe is 3 themes. An adventure
   descent can be 5-7 floors, and the deeper you go the rarer the nodes, so
   the stories get better the further in someone got.
2. **Rare nodes create luck stories.** "I got dead grave robber on floor 3"
   is a roll, not a composition. Recipe matches are deterministic; adventure
   transitions can be weighted, so some paths are genuinely rare and the
   players who find them have something to tell.
3. **The graph is unmappable in one sitting.** A recipe table with
   3-ingredient sequences is small enough to brute-force. A branching graph
   with weighted rare nodes and 5+ depth is not. The discovery space is
   larger, so the folklore lasts longer.

The one thing that has to hold for this to work: **the graph must stay
hidden in-game.** The moment you surface it, it becomes a lookup table and
the word-of-mouth collapses. Same principle as 5.4's "you can see the items,
not the recipe," extended to a graph.

**What it changes, not simplifies:**

- **Structure.** Recipes are a flat table; adventures are a graph with
  entry-point themes (the "overworld" set you return to after a boss),
  descent nodes (themes that branch further), boss terminators (themes that
  end the adventure and reset to entry set), and depth (how many floors deep
  you are). More conceptual machinery than a recipe table, even if the code
  is smaller. The data authoring is similar in volume but different in shape:
  you author a branching tree per adventure family, not a list of ingredient
  sequences.
- **The boss room is new content.** Today there is no boss; there are trial
  spawners and a terminal pad. A boss room that terminates an adventure is a
  new room type, a new encounter, and a new completion condition. This ties
  into the spawner-gating idea (2.3): the boss could be the ultimate gated
  completion. But it is net-new content work, not a reframe.

**Where the PoE-map analogy is partial.** PoE maps are consumed when you run
them. The keystone is a remote, not a consumable: it is never spent on entry.
So the "map" lives in the door choice, not in an item you trade or stockpile.
The fuel (section 3) is what gets consumed, not the map.

**Rejected alternative: keep the recipe system.** The recipe system already
does the composition-discovery part and is already shipped. The argument for
keeping it is sunk cost plus the deterministic-match property (a recipe
always fires when you hit the sequence, which some players will read as
fairer than a weighted rare-node roll). The argument against is that it is
backward-looking, harder to author, and produces shorter stories than a
descent graph. The adventure reframe wins on authoring simplicity and
discovery depth; the recipe system wins on determinism. This is a real
tradeoff, not a clear call.

### 2.3 Trial spawner gating

Today completion fires on first pad contact, with zero spawner-clearing
required. Sprinting to the terminal works. The clock is the only thing
penalizing it, and a sprint beats the clock.

The Nephalem Rift fix: gate completion on clearing a fraction of the dungeon's
trial spawners.

**Implementation path:**

- Track spawner positions at stamp time. `TrialContent.applyEncounter` already
  knows where every spawner lands; collect them across all encounter cells
  into `InstanceRecord` (new field).
- Check state on pad contact. Vanilla `TrialSpawnerBlockEntity` exposes a
  state that reaches `COOLDOWN` once cleared. In `completeRun`, before
  stamping the reward room, verify N% of tracked spawners are cleared. If not,
  refuse completion with a message.
- A **total completion affix** raises the threshold from N% to 100%. It fits
  the existing affix pattern (curse + kiss). The curse is "you can't skip
  anything"; the kiss still needs naming. Candidates: a bonus chest, a
  guaranteed theme-material drop, or a small payout multiplier.

**Two concerns:**

- Spawner tracking is per-cell today; the collection pass is new plumbing on
  `InstanceRecord` and the stamp loop.
- The threshold matters. Too high and a missed spawner in a hard-to-reach
  spur cell blocks completion; too low and sprinting is still viable. 70-80%
  is the likely starting band, with the total-completion affix at 100%.

### 2.4 Mob strength scaling and the level cap

The cap is 25 today and the climb is fast. Raising it (to 100 is the number in
play) and scaling mob strength with it is the proposed fix.

**Implementation is straightforward.** Either inject attribute modifiers
into spawner NBT at stamp time, or apply them via an `EntityJoinLevelEvent`
listener when a mob spawns in the dungeon dimension. The listener is cleaner:
one hook, no per-spawner NBT surgery, and it scales dynamically if a level
ever changes mid-run. +1% per level is the proposed curve, so a level 100 key
faces roughly twice-strong mobs.

**The real problem is the reward plateau.** `KeystoneMath.lootTier` caps at
tier 3 at level 10, and there are only three loot tables. If difficulty
climbs to 2x at level 100 but loot is still the tier 3 table a level 10 key
gets, players hit a wall where difficulty climbs forever and rewards flatline
at level 10. That is the classic roguelite balance failure `VISION.md` 3.2
claims to win by construction.

**Three ways to fix it, in increasing order of work:**

1. Add loot tiers 4, 5, 6... with new materials per tier. This extends the
   3.6.1 provenance argument, but it needs authoring new loot tables and new
   building-block palettes for each tier. Significant content work.
2. Scale loot quantity, not tier. A level 100 key gets more chests, more
   vault drops, higher roll counts on the same tier 3 table. Easier, but
   "more of the same" does not feel like progression past level 50.
3. Make the fuel/sink the high-level reward. Premium doors at high keys drop
   the rare materials (echo shards, theme blocks, adventure ingredients) that
   low keys never see. The loot tier stays at 3, but the door selection
   scales. This ties the ladder to the sink: the reason to push a level 100
   key is access to fuel-gated doors that drop things the free door never
   offers.

Option 3 is the most coherent with the rest of this brainstorm. It means the
keystone level gates **which doors are available**, not just how hard the
same door is. Mob scaling still applies within a door, but the real
progression is door access.

**Cap-change touch points.** `keystoneMaxLevel` is config at 25.
`AffixMath.seededCount` thresholds are hardcoded at 5/11/17 and would need to
scale (more thresholds, or a percentage-based system). The intensifier bands
(Baby 1-5, Lowkey 6-10, Highkey 11-15, Menace 16-20, Unhinged 21+) cap at
"Unhinged 21+" and would need extending past 25.

---

## 3. The sink: five ideas borrowed from Diablo 3

Dungeons are a faucet with no drain today. Depletion drains ladder position,
never items. Every run adds materials and nothing removes them. A sink is
needed; the design just has not got one yet.

**Why Diablo 3 is the reference point, not just a source of individual
ideas.** Open question 1 (section 5) asks what a level-100 player is working
toward that a level-10 player is not, and D3's Nephalem Rift / Greater Rift
split answers it structurally rather than just economically: an untimed,
ungated farming tier that produces the currency a timed, gated ladder tier
consumes, so there is always something productive to do and the thing you do
produces the thing you spend. Pocket Dungeons today collapses both tiers
into one door type, which is why the keystone only ever goes up or down and
nothing is ever spent. The five ideas below are that same two-tier shape
applied at a different scale of the loop: the run itself (3.1), the gear a
run drops (3.2), the permanent power slotted into that gear (3.3), a
volume-over-certainty way to get more of that gear in the first place
(3.4), and a way to make one rare drop matter forever instead of once
(3.5), each with its own "D3 analogue" line so the lineage stays traceable
rather than asserted.

**Fuel source must stay inside the dungeon loop** per `VISION.md` 3.7:
"Nothing required for progression may live outside the dungeon loop." So the
fuel has to be obtainable from runs. Diamonds and emeralds are already in the
chest tables, so a diamond sink is 3.7-consistent as-is.

**Echo shards are the more interesting choice and they are not in the loot
yet.** The keystone is a recovery compass, which is crafted from echo shards,
so echo-shard fuel is diegetically perfect: the same item that mints the key
also powers the premium doors. It gives a near-unused vanilla item a second
purpose and stays inside the "no custom items" rule. The cost is adding echo
shards to the loot tables first (tier 2/3, weighted low), which is a content
edit, not a code change.

**The free door must always exist.** `KeystoneMath.deplete` already enforces
"a keystone never goes to zero"; the free door is the same principle applied
to fuel. The sink gates the premium path, never the baseline. A broke player
always has the vanilla door.

**The sink can self-fund if the premium rooms drop more of the fuel
currency.** If premium rooms drop more diamonds and the door costs diamonds,
the loop feeds itself and the sink does nothing. Premium rooms should drop
*different* loot (decoratives, theme blocks, the 3.6.1 provenance materials)
rather than more of the fuel currency. Or use echo shards as fuel while
premium rooms drop diamonds, so the currencies are orthogonal.

### 3.1 Two-tier doors: the Nephalem/Greater Rift split

**D3 analogue: Nephalem Rifts feed Greater Rifts.** A Nephalem Rift is
untimed, always available, costs nothing, and its only job is to drop a
Greater Rift Keystone. A Greater Rift consumes that keystone, is timed, and
pays out in ladder position and rarity. The farming tier's product is the
ladder tier's admission price. Applied here: **door 1 stays the free door
this brainstorm already has** (section 2.1's "+1, no fragile"), but it also
becomes the fuel source, not just the safe option. **Doors 2 and 3 become
the Greater tier**: timed, fuel-consuming, depleting on failure the way
every door does today, level-gated, and drawn from the adventure graph
(section 2.2). Concretely: door 1 is untimed or generously timed, spends no
fuel, and never depletes the keystone on failure, in exchange for the
modest loot a free door already implies. Doors 2 and 3 keep the clock, the
depletion, and the level gate they have today, and additionally cost fuel
(section 3's echo shards, or whichever currency question 4 settles on).

**This is what makes section 2.4's option 3 concrete rather than aspirational.**
2.4 already argues the keystone level should gate *which doors are
available*, not just how hard the same door is; the two-tier split is the
mechanism that makes that true rather than just asserted. A level-100 key
is not "the same three doors, but scarier." It is access to door-2/3 offers
a level-10 key cannot open at all, farmed for by running the free door
enough times to afford them. The free door was already load-bearing
(`KeystoneMath.deplete`'s "a keystone never goes to zero" principle, section
3's "the free door must always exist"); this reframe gives it a second job
instead of adding a fourth door to do it.

**What this does not change.** The three-door UI, the door-offer dialog (or
its physical replacement in section 10), `Keystone.offers`, and the
depletion math on doors 2/3 all stay exactly as designed elsewhere in this
document. The only new mechanical fact is that door 1 stops depleting and
starts paying fuel, and doors 2/3 start costing it.

**Open, folded into question 4:** whether door 1 needs its own, shorter
timer for pacing (an unlimited door risks becoming the only door anyone
ever takes) and how much fuel a door-1 run should typically produce
relative to what a door-2/3 run costs, so the loop neither stalls (never
enough fuel to push) nor trivializes (door 1 alone drowns the sink).

### 3.2 Gear reroll: a lapis sink, orthogonal to the fuel sink

**D3 analogue: Kanai's Cube's reroll, or the Mystic's enchant.** Both let a
player replace one property on an item they already have, spending a
material sink to do it, without touching the rest of the item. The station
below is that shape with lapis standing in for D3's crafting materials.

**The pitch in one sentence:** a room station that rerolls one enchantment on
a piece of gear, player's choice of which, costing lapis that scales with the
item's own tier. Not the fuel sink (section 3's echo shards gate the
*ladder*: which doors you can take); this one gates *gear*: how far you can
push the loot the ladder hands you. The two sinks answer different halves of
open question 1 (section 5): fuel is why level 100 is worth reaching, lapis
is why the gear it drops is worth using.

**Why lapis, not a new item.** Lapis is already a real vanilla currency tied
to gear progression (it is what enchanting spends), it is a plausible chest
drop at every tier the same way diamonds already are, and reusing it costs no
new item and no new loot table category, only new pool entries in the
existing tier tables. The "no custom items" rule stays intact for the
same reason it did for echo-shard fuel.

**Deliberately not vanilla enchanting.** The station is a separate
interaction, not an interception of the enchanting table: a themed block
(the `ConfiguredItem` pattern `Keystone`/`TrialContent` already use for
`keystoneItem`/`vaultKeyItem` covers "what block or item, configurable, with
a sane default" cleanly) right-clicked to open a reroll dialog, following the
`RitualListener` house pattern of intercepting a specific block's use rather
than replacing a vanilla one. A player's actual enchanting table, wherever
they placed it, keeps doing exactly what vanilla enchanting tables do. This
keeps the reroll additive: nothing a player already knows about Minecraft
stops working, the dungeon just hands them a second thing to do with lapis.

**What a reroll does.** The dialog lists the item's current enchantments
(`DialogKit`/`DialogScreens` again, same vanilla-dialog mechanism the door
offers and party roster already use); the player picks one. That
enchantment is replaced with a different enchantment drawn from the valid
pool for that item type (excluding the one just removed and any already on
the item, the same way vanilla's own table avoids offering a duplicate), at
a random level within that enchantment's normal range. Every other
enchantment on the item is untouched. A full-reroll ("clear everything, roll
a fresh set") was the other shape considered and rejected here: one
enchantment at a time reads as a controllable decision with a target in
mind, closer to a crafting station than a gamble, and it cannot produce a
strictly worse item than the one that walked in, which a full reroll can.

**Cost scales with item tier, not enchantment level.** The gear itself
already carries a tier signal (the loot table it came from, section 3.6.1's
provenance argument), so the reroll cost reads it off the item rather than
introducing a second tier concept. A tier-1 drop rerolls cheap; the gear a
deep key hands out costs more lapis to touch, which is the same shape as
`KeystoneMath.deplete`'s cost curve: the further in you are, the more each
decision costs, never so much that the door closes.

**Where it lives and what unlocks it.** The natural home is the player's own
room, alongside whatever else M8's D5 ("room size, station unlocks") ends up
specifying, which this reframes from a vague backlog line into a concrete
first station. Gating it behind a keystone level, a completed recipe
dungeon (section 2.2), or nothing at all (available from the first room) is
still open; the fuel-gated-door precedent in section 3 argues for *some*
gate, since an ungated sink is not much of a sink once diamonds/lapis start
overflowing the way section 3 already warns diamonds could.

**Open, same shape as section 5's fuel questions:** the exact lapis-per-tier
curve, whether the station is available from run 1 or unlocked, and whether
premium doors should avoid dropping extra lapis the way section 3 warns
against self-funding fuel with more fuel.

### 3.3 Armor trims: consumed patterns that actually do something

**The pitch in one sentence:** armor trim templates drop as dungeon loot,
applying one at a smithing table permanently consumes it (the vanilla
template-duplication recipe is removed for these, so there is no way back to
more copies), and the material used in the trim grants the piece a real
combat bonus, not just a colour.

**Why this is a sink and not just flavour.** Vanilla trims already cost
something to apply: a template, an armour piece, and seven of a trim
material at the smithing table, same shape as the reroll station's cost in
section 3.2. What vanilla also does, and what makes an applied trim
worthless as a sink, is let a player duplicate the template afterward: place
it with a diamond and seven more of the same material at a crafting table
and walk away with two templates again. If dungeon-found templates go
through that same
duplication recipe, they are not a sink, they are a one-time toll on an
otherwise infinite resource. The mechanic only works as a sink if the
loot table is the *only* source and using a trim spends it for good, which
means the duplication recipe has to be removed for these templates
specifically, or for every template server-wide if there turns out to be no
clean way to distinguish "a dungeon-found template" from "an ordinary one" at
the recipe level. **Needs verifying against the 26.2 jar before committing
to either shape**: whether trim template duplication is its own data-driven
recipe type that can be conditionally excluded, and whether a template
carries any distinguishing data (an NBT tag, a custom component) once it
leaves the loot table, or whether every copy of `minecraft:sentry_armor_trim`
is identical and the removal has to be global.

**What the bonus actually is, and why the material decides it, not the
pattern.** Vanilla ships around ten trim materials (iron, copper, gold,
lapis, emerald, diamond, netherite, redstone, quartz, amethyst) crossed with
roughly seventeen patterns, which is too many cells to author a distinct
bonus for each without the table becoming arbitrary. Splitting the axes
fixes that: **the material determines the bonus** (an attribute modifier,
following the same "material already means something" logic the 3.6.1 tier
palettes use, e.g. diamond trims toughness, netherite trims knockback
resistance, gold trims a small speed bonus, lapis loops back into section
3.1's reroll cost by trimming XP gain instead) **and the pattern stays what
vanilla already made it: cosmetic and a rarity signal**, some patterns
authored rarer than others the way the adventure graph's rare nodes already
are (section 2.2). That keeps the design to "ten bonuses, tune once" instead
of "170 combinations, tune all of them," and it means a player chases a
specific material for the bonus and a specific pattern for the look, two
separate reasons to want two different rare drops.

**Implementation shape, and the part that reaches outside the dungeon
loop.** Reading which trim, if any, sits on a worn armour piece is a
`DataComponents.TRIM` read (verify the exact component name and its shape
against 26.2), and turning that into an attribute modifier is an equip-time
or per-tick check, the same shape as `SilenceListener`'s item-use
interception but keyed on armour slots changing rather than item use. The
open design tension worth naming rather than skating past: `VISION.md` 3.7
says nothing *required for progression* may live outside the dungeon loop,
which the templates and materials satisfy (both are dungeon-loot-gated), but
a worn trim's combat bonus applies wherever the player wears the armour,
overworld included. That is consistent with the letter of 3.7 (nothing about
*reaching* the bonus requires leaving the loop) but it is a bigger
commitment than section 3.2's reroll station: this is passive combat power
active outside the dungeon dimension, not a dungeon-run modifier, and it is
worth being deliberate about that rather than backing into it.

**Open:** the exact material-to-attribute table, whether patterns are
authored with real rarity weights or just cosmetic variety, how the
duplication-recipe removal is actually implemented once verified against the
jar, and whether the combat bonus should be dungeon-only (checked against
the player's dimension, the same guard `RoomProtection` already reads) rather
than global, which would resolve the tension above at the cost of a trimmed
piece feeling like a prop the moment the player leaves.

### 3.4 A gambler: emeralds for a random piece in a chosen slot

**D3 analogue: Kadala.** Spend her currency (Blood Shards there, emeralds
here), pick a slot, get back a random item that fits it. No guarantee of
quality within the slot, which is the whole point: it is a currency sink
that trades certainty for volume, sitting next to 3.2's guaranteed,
targeted reroll rather than replacing it. A player who knows exactly what
they want rerolls it (3.2); a player who just wants more shots at
*something* for that slot gambles instead.

**This is the sink emeralds actually want.** Section 3 flagged diamonds
as risky fuel because premium doors already drop them, so a diamond-costed
door would self-fund; emeralds sit in the same already-in-the-tables
category, so the same question applies and gets the same answer here. The
output of a gamble is gear, not more currency, so there is no loop feeding
itself the way an emerald-gated door would. Emeralds get a real second job
(a currency spent here, a material found everywhere else) without the
self-funding trap, which is arguably the cleanest fit any of the four sinks
in this section has for the currency it spends. Keeping emeralds here and
echo shards or diamonds as the door fuel (section 3.1, still open per
question 4) also keeps the two ladder-scale and gear-scale sinks on
different currencies, the same orthogonality section 3's closing paragraph
already argues for.

**Slot first, tier second.** The player picks a slot (any one of the eight
equipment slots) the way Kadala's menu does, and the cost scales with which
tier's gear pool the gamble draws from, the same tier-scaling shape as
3.2's reroll cost and 3.3's trim material. A tier-1 gamble is cheap and
draws from the tier-1 gear pool; a tier-3 gamble costs more and draws from
better gear, still with no guarantee of which piece or what it rolls with.
**Checked, not just flagged: the gear pool does not exist yet.** None of
the existing `chests/tier_N*.json` tables carry an armour or weapon entry
of any kind (see question 10, section 5); every tier table is materials,
blocks, food, and consumables. A slot gamble has nothing to draw from until
that pool is authored, which is real content work, not a small addition on
top of what is already there.

**Implementation shape.** Same house pattern as the rest of this section: a
block interception (`RitualListener`'s shape) opens a dialog
(`DialogKit`/`DialogScreens`) offering the eight slots and whichever tiers
the player's keystone level has unlocked; confirming a slot and tier spends
the emeralds and draws one item from that slot's tier pool, the same
`ResourceKey<LootTable>` mechanism `TrialContent`/`LootTables` already use
for chests, just keyed by slot instead of by container.

**Open:** the exact emerald cost per tier, whether every slot costs the
same at a given tier or weapon slots cost more than armour slots the way
D3 weighs different Kadala pulls differently, and whether the gear pool (if
it needs authoring) should be a subset of the existing tier tables or its
own thing so a lucky gamble cannot simply out-produce opening the chests
the run already offered.

### 3.5 The Herobrine Cube: extract a power, imbue it anywhere

**D3 analogue: Kanai's Cube, in Diablo 2's Horadric Cube's shape.** Kanai's
Cube is where this idea's *function* comes from: consume a rare item once,
permanently remember one fixed power it carried, and re-apply that power to
any future item cheaply, without ever needing the original item again. The
Horadric Cube is where its *interface* comes from, per the clarification
this section is written against: a crafting table, not a new block or GUI,
where the right combination of items in the grid does not produce a normal
crafted result but performs a ritual instead. That is the same shape
`RitualListener` already uses for the lodestone-and-keystone interaction:
intercept a vanilla interaction (there, a right-click; here, a craft
result) and substitute mod behaviour for whatever vanilla would have done.
A crafting table anywhere still crafts normally for anything that is not a
recognised ritual combination.

**What a ritual does.** Two rituals, mirroring Kanai's Cube's two halves:

- **Extract.** Place a qualifying item (see below) in the grid; instead of
  a crafted result, its one fixed power is added to the player's
  permanently remembered set (persisted the same way `DungeonLog.Entry`
  already tracks `completedThemes`, a per-player, never-truncated
  collection) and the item itself is consumed. This is the real sink: a
  hard-won item is gone for good, in exchange for never needing to find
  another one like it again.
- **Imbue.** Place an ordinary item of the matching slot plus a smaller
  material cost (emeralds, lapis, whatever question 4/8's currency
  questions settle on) in the grid; instead of a crafted result, one
  previously extracted power (player's choice, from whatever they have
  extracted so far) is applied to that item. Cheap and repeatable, the
  point being that the expensive part already happened at extraction.

**What is extractable, and why that has to stay rare.** Not ordinary tier
loot, and not gamble (3.4) or reroll (3.2) output: a fixed, powerful,
build-defining effect is a different kind of reward than a stat roll, and
handing it out at the same rate as everything else in this section would
flatten the whole package instead of capping it. The natural source is the
rare adventure-graph nodes section 2.2 already describes (a "pharaoh's
chamber" drop, found once in a long while, down a path most players never
walk), which gives the Cube something section 2.2 does not yet have: a
concrete reason those rare nodes matter beyond a shell unlock (15.5) or a
recipe theme (2.2's own reward). This section, 3.4's gear pool, and 15.5's
shell tokens all draw on the same well of "rare adventure-node reward,"
worth reconciling into one authored list rather than three separate ones
once any of this is scoped.

**Why capped at equip time, not just rare at extraction.** D3 caps how
many extracted powers can be *worn* at once (three slots: weapon, armour,
jewellery) even though the player's extracted library can grow arbitrarily
large. Without an equip cap, the Cube stops being a build decision and
becomes a checklist: extract everything, wear everything, done. A small
cap (three slots, matching D3's, or fewer) keeps "which powers do I run"
a real choice every time a new one is found, the same shape 3.3's
material-decides-the-bonus trim design already leans on to keep decisions
meaningful rather than additive.

**Implementation shape.** The crafting-table ritual interception is new
plumbing this mod does not have yet (`RitualListener` intercepts a
right-click, not a craft result; the closest existing pattern is still the
one to study, not one to copy verbatim). Extracted powers persist per
player, the same shape as `completedThemes`. The equipped-power check at
combat time is the same attribute-modifier-on-equip shape 3.3's trim bonus
already needs, generalised to read "which of my extracted powers am I
currently slotting" instead of "what trim is on this piece." This section
depends on 3.3's implementation existing first for exactly that reason.

**Open:** the exact extractable-item source (rare adventure nodes only, or
also a very-low-weight Cube-only gamble slot in 3.4), the equip slot count
and whether it is per-armour-piece or a fixed pool independent of gear
slots, the imbue cost and currency, and whether extraction should be
reversible at all (D3's is not: once extracted, an item's power is a
permanent unlock, never an item again) or whether that is too harsh a
sink for a server where a mis-click costs a genuinely rare drop forever.

---

## 4. How it could fit together

If all of these landed, the shape would be:

| Component | Current | Idea |
|---|---|---|
| Keystone level | Gates difficulty (affixes) + loot tier | Gates door access + mob strength scaling |
| Three doors | +1 / +2 OMINOUS / +3 FRAGILE | Free vanilla / fuel-gated map / fuel-gated deeper map |
| Theme progression | Backward recipe match on last 3 themes | Forward adventure graph from current theme |
| Affixes | 2 elective + 5 seeded | 0 elective + 5+ seeded (Ominous becomes map property) |
| Completion | Touch terminal pad | Clear N% of spawners, then touch pad |
| Mob difficulty | Tier-based (1-3) | Tier-based + level-scaled (+1%/level) |
| Loot | 3 tiers, plateau at level 10 | 3 tiers + door-gated premium materials at high keys |
| Sink | None | Fuel (echo shards?) for premium doors, lapis for gear reroll, consumed armour trim templates for a real combat bonus, emeralds for a slot gamble, a rare drop extracted once via the Herobrine Cube for a permanent power |

---

## 5. Open questions, by priority

1. **What is the level-100 player working toward** that a level-10 player is
   not? If the answer is "harder fights for the same loot," the ladder loses
   meaning past the loot plateau. If the answer is "access to maps and
   materials low keys never see," the ladder stays meaningful. This is the
   load-bearing decision; everything else follows from it.
2. **Adventures or recipes?** The adventure graph is simpler to author and
   produces deeper discovery stories. The recipe system is shipped and
   deterministic. Both preserve the folklore dynamic; adventures arguably
   strengthen it. This is a real call, not a clear one.
3. **Does Ominous stay elective or become a map property?** Retiring
   `Affix.Kind.ELECTIVE` is cleaner but it is also retiring the last piece of
   the M4 door-choice mechanic.
4. **What is the fuel currency?** Echo shards are diegetically perfect but
   need loot-table work first. Diamonds are already in the tables but risk
   self-funding.
5. **What is the total-completion affix's kiss?** The curse is clear; the
   gift is not. It needs a gift per the M4 rule, or it does not ship.
6. **What is the spawner-clear threshold?** 70-80% is the starting guess; it
   needs playtesting against spur-cell edge cases.
7. **How do the affix thresholds scale past 25?** More thresholds, or a
   percentage-based system?
8. **What is the gear-reroll lapis curve, and is the station gated?**
   (section 3.2) Same shape as question 4, one level down: the fuel currency
   gates the ladder, this gates the gear the ladder hands out.
9. **Should an armour trim's combat bonus work outside the dungeon?**
   (section 3.3) The templates and materials are dungeon-gated either way;
   this is whether the bonus itself is too, and it is a bigger design
   commitment than either other sink, since it is the first idea here that
   reaches past the dungeon loop into ordinary overworld combat.
10. **The chest loot tables carry no armour or weapons at all, checked.**
    (section 3.4) `grep` across every `chests/*.json` table turns up
    materials, blocks, food, and consumables, nothing wearable or wieldable.
    A slot gamble has nothing to draw from until a tiered gear pool is
    authored first, which is real content work (per-slot, per-tier item
    pools with sensible enchantment weighting), not a small addition on top
    of what exists. This is the one prerequisite in this section that is
    not "verify against the jar" but "author new loot content before any
    code is worth writing."
11. **Where do Herobrine Cube extracts actually come from, and how many
    can be equipped at once?** (section 3.5) The rare-adventure-node source
    ties it to section 2.2, which has no authored rare-node reward list
    yet; the equip cap is the one number that decides whether the Cube is
    a real build choice or a checklist.

---

## 6. What this would rewrite in the existing design

If any of this is promoted to a real plan, these are the touch points:

- `VISION.md` 1: the "safe / greedy / reckless" framing of the three doors.
  The doors stay three; what distinguishes them changes.
- `VISION.md` 3.1.1: "the three doors are runs and stay runs, not up for
  renegotiation." The doors stay runs; the +1/+2/+3 upgrade choice goes.
- `VISION.md` 3.2: the ladder's difficulty source moves from the keystone
  level to the map.
- `VISION.md` 5.4: the recipe system's folklore argument. Adventures preserve
  the dynamic through a hidden graph rather than hidden compositions; the
  principle holds, the mechanism changes.
- `plans/M4-affixes.md`: the elective affix system. Ominous migrates, Fragile
  is deleted.
- `Keystone.offers`, `Keystones.grantOffer`, `DialogScreens.doorOffer`: the
  door-offer plumbing. The UX surface (per-door dialog, `/dungeon choose`)
  stays; what the doors offer changes.
- `DungeonRecipes`, `RecipeMatcher`, `ThemeHistory`, `DungeonLog.recentThemes`:
  the recipe system. Replaced by `AdventureGraph` and a single `currentTheme`
  field if the adventure reframe lands.
- `Instances.completeRun`: the completion gate. Today it is pad contact; it
  becomes spawner-clearance then pad contact.
- `PocketDungeonsConfig.keystoneMaxLevel`, `AffixMath` thresholds and
  intensifier bands: the cap and the scaling curves.
- `VISION.md` 3.1.1: the calling card. Replaced by a lobby directory with a
  host-set public/private boolean (section 8).
- `CallingCard.java`, `RitualListener` card branch, `callingCardItem` config:
  deleted if the visiting rework lands (section 8).
- `RoomProtection`: gains an `isShell` check for the immutable room shell
  (section 9).
- `DialogScreens.doorOffer`, `RitualListener.sendDoorOffer`: deleted if the
  physical door selection lands (section 10).
- `Instances` leave-pad watcher (`isOnRoomLeavePad`): deleted if the
  lodestone-menu consolidation lands (section 11).

---

## 8. Visiting rework: lobby directory, no calling card

Today the calling card (`CallingCard.java`) is a hand-traded compass that
routes a visitor to the owner's room. It has no directory, no list, no browse.
A room's audience is exactly the people the owner handed a compass to.

`VISION.md` 2.1 argues that visiting is what turns this from a feature into a
category, citing Habbo and Skyblock. But the calling card is the opposite of
how Habbo actually works: Habbo has a public room navigator. You browse
rooms, see occupancy, click to enter. The card is an Animal Crossing Dodo
Code. The doc borrows Habbo's structure (public space of private rooms) while
rejecting Habbo's discovery mechanism (the navigator). Its own caveat exposes
the cost: "a calling card traded by hand on a five-player server produces a
nice feature, not a category."

**The reframe: a lobby directory replaces the card.** Right-click the
lodestone terminal (section 11) and one menu option is "Browse Lobbies." It
opens a `MultiActionDialog` with one button per public room, same pattern as
`DialogScreens.partyRoster`. The button label shows the room name and
occupancy count. Clicking it calls `Instances.visit(serverPlayer, ownerUuid)`,
the same call the card path uses today.

**Privacy is a host-set boolean, not a token.** Each room has a
`publicListed` flag, defaulting to private. The host toggles it from the room
management menu. No card, no token, no hand-traded item. The host opts into
being listed; the directory shows only listed rooms.

**Two independent layers:**

| Layer | Question | Mechanism |
|---|---|---|
| Visibility | Is the room listed in the navigator? | New `publicListed` boolean, host-set, default private |
| Permissions | What can a visitor do once inside? | Existing `RoomWhitelist` (unchanged) |

A room can be public-listed (anyone can find it and walk in) but still locked
down (visitors cannot break or open containers because they are not
whitelisted). Or private-unlisted. Or fully open. The host controls both
independently.

**Room naming.** A string the host sets via a `TextInput` dialog, same pattern
as the existing whitelist-name dialog. Stored alongside the `publicListed`
flag in persistent per-owner state (`DungeonLog.Entry` or `RoomWhitelist`).

**Default-private and density.** Defaulting to private is the right call for a
room that is also your house. But it means the directory's density depends on
hosts choosing to list. The directory still beats the card because listing is
one click while hand-trading compasses is N transactions. The friction drop is
what produces the density, even with opt-in.

**What gets deleted:** `CallingCard.java` entirely, the card branch in
`RitualListener` (lines 132-140), `CallingCard.warmUp()`, the
`callingCardItem` config field, and the `Payout.deliver(owner,
CallingCard.mint(...))` delivery in `DungeonCommands`. One file deleted, four
call sites trimmed.

**What stays:** `Instances.visit` (the visit call, just invoked from a dialog
button instead of a card), `RoomWhitelist` (the permission mask, unchanged),
`RoomProtection` (the break/place guard, unchanged).

---

## 9. Room design: immutable shell, double doors, wall terminal

### 9.1 Immutable shell

Today `RoomProtection.beforeBlockBreak` checks `roomOwnerAt(pos)`, which
returns the owner for any position inside the 16x16x7 box. The owner can
break anything in that box, including the walls, floor, and ceiling. There is
no shell-vs-interior distinction.

**The proposal: the shell is immutable to everyone, including the owner.** The
shell is the floor (Y=0), the wall ring (x=0, x=15, z=0, z=15, Y=1..5), the
ceiling (Y=6), and the four ceiling lamps. The interior (x=1..14, z=1..14,
Y=1..5) is the owner's build space. The owner can build freely inside; they
cannot modify the shell.

**Implementation:** `beforeBlockBreak` gains a position test. Today it returns
`isPermitted(...)`. It becomes: return `isPermitted(...) && !isShell(pos,
roomOrigin)`. `isShell` is a pure coordinate test against the room origin, no
block-state lookup. The same test applies to placement (the existing placement
guard in `RitualListener` gains the same check).

**Paintings, item frames, carpets, wall signs, banners, buttons, torches all
work.** Paintings and item frames are entities, not blocks, so block-break and
placement hooks never fire for them. Carpets sit on top of the floor (Y=1,
interior). Wall signs hang in front of the wall (x=1, interior). Vanilla's
placement mechanics put decorations on the face of the shell, not in the
shell. `isShell` protects the block, not the face.

**What this enables: room skins.** If the shell is immutable, swapping it is a
mod-controlled operation. A "deepslate room skin" replaces `STONE_BRICKS` walls
with `DEEPSLATE_BRICKS`, `POLISHED_ANDESITE` floor with `DEEPSLATE_TILES`,
etc. The player unlocks the skin and applies it via the management menu. This
is the 3.6.1 provenance argument applied to the room itself: your room's walls
say which dungeons you have conquered.

**The cost:** players who want to modify the shell (extend the ceiling, add a
window, change the floor material) cannot. The 14x14x5 interior (980 blocks)
is the entire build space. `VISION.md` 3.1 already warns the room is capped at
one cell on purpose.

**The bedrock envelope stays.** `BedrockEnvelope` is the backstop behind the
shell. With immutable walls it is redundant for the room, but it stays for
dungeon cells, which are fully breakable.

### 9.2 Double doors on the selector opening

When a selector door is chosen, `clearSelectorDoors` removes the placeholder
door blocks and `openDoorOnWall` punches a 2-wide, 3-tall air hole through the
wall. The slot is at x=7..8, Y=1..3. That is a 2x3 opening, and it is just
air. Mobs from the first dungeon cell can walk straight into the room.

**The proposal: place double doors in the opening after it is punched.** Two
vanilla door blocks side by side, filling Y=1..2, with a lintel block at Y=3
to seal the top. The doors block mobs when closed; the player opens them to
walk through.

**The 3-tall slot vs 2-tall doors.** Vanilla doors are 2 blocks tall. The
doorway is 3 tall (`DOOR_HEIGHT = 3`). Doors fill Y=1..2; Y=3 needs a lintel
block (wall material) to seal the gap. Without it, skeletons can shoot through
the 1-block gap above the doors.

**Wooden vs iron doors.** Wooden doors open by right-click; iron doors need
redstone. Since the mod intercepts right-clicks on doors before vanilla
handles them, wooden doors that look openable but actually trigger mod logic
are the existing pattern. For the post-selection doorway, wooden doors the
player opens manually are simpler and still block mobs when closed.

### 9.3 Wall lodestone as terminal

Today the lodestone is on the floor. Moving it to a wall is a template change.
It becomes the terminal you right-click to open the mod navigation menu
(section 11), not a pad you stand on.

The terminal-pad completion trigger (at the dungeon's end) stays as a floor
lodestone. The room's wall lodestone is the menu terminal. Two different
lodestones serving two different purposes, which is clearer than one
lodestone doing both.

The wall lodestone is part of the immutable shell, so the player cannot break
it. Room skins apply to the wall it sits on, so a deepslate-skinned room has a
lodestone set into a deepslate wall.

### 9.4 Ceiling: top slabs and stair-framed light fixtures

Today the ceiling is full `STONE_BRICKS` blocks at Y=6 (`CEILING_Y`), filling
the entire 16x16. The interior headroom is Y=1..5, 5 blocks tall. The visible
ceiling surface is at Y=6.0 (the bottom face of the full block). Four
`SEA_LANTERN` blocks sit flush with the ceiling at (4,4), (4,11), (11,4),
(11,11), reading as "random glowing block in the ceiling."

**Top-half slabs for the interior ceiling.** A
`minecraft:stone_brick_slab` with `type=top` occupies Y=6.5 to Y=7.0, leaving
Y=6.0 to Y=6.5 as air. The visible ceiling jumps from Y=6.0 to Y=6.5. The
room feels half a block taller without changing `WALL_HEIGHT` or `CEILING_Y`.
The bedrock envelope at Y=7 (`CEILING_Y + 1`) still sits above the slabs,
untouched.

The edge ring at Y=6 stays full blocks. The wall ring runs Y=1..5; the
ceiling edge needs full blocks to connect cleanly with the top of the wall.
If the edge were also slabs, there would be a visible gap between the wall
top (Y=6.0) and the slab bottom (Y=6.5). Full blocks on the edge, top slabs
in the interior.

`buildCell` line 82 changes from `set(..., CEILING)` to check `edge` and
place a top slab for interior positions, full block for edge positions. One
new `BlockState` constant:

```java
private static final BlockState CEILING_SLAB = Blocks.STONE_BRICK_SLAB
        .defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
```

`buildLiminalCell` gets the same change for consistency.

**Stair-framed light fixtures.** Each lantern stays at Y=6. Four
`STONE_BRICK_STAIRS` at Y=6 on its four sides, each with the tall back
against the lantern and the short stepped side facing outward into the room.
The `FACING` blockstate points toward the short side (the direction the
stair descends), so `FACING` must point **away** from the lantern for the
tall side to touch it.

For a lantern at (4, 6, 4):

| Position | `FACING` | Tall side toward |
|---|---|---|
| (3, 6, 4) | `EAST` | (4, 6, 4) |
| (5, 6, 4) | `WEST` | (4, 6, 4) |
| (4, 6, 3) | `SOUTH` | (4, 6, 4) |
| (4, 6, 5) | `NORTH` | (4, 6, 4) |

The visual: the lantern sits in a cradle where four stairs step down away
from it, like a recessed can light or a chandelier mount.

A helper:

```java
private static void placeFixture(ServerLevel level, BlockPos lantern) {
    set(level, lantern, LAMP);
    set(level, lantern.east(),  stairFacing(Direction.WEST));
    set(level, lantern.west(),  stairFacing(Direction.EAST));
    set(level, lantern.north(), stairFacing(Direction.SOUTH));
    set(level, lantern.south(), stairFacing(Direction.NORTH));
}
```

`buildCell` and `buildLiminalCell` replace their four `set(..., LAMP)` lines
with four `placeFixture` calls. `RoomTemplateGenerator`'s room templates get
the same treatment.

**Structure rotation preserves the fixture orientation.**
`StructureTemplate` rotation rotates the `FACING` blockstate of stairs
correctly. A stair facing east, rotated 90 degrees, becomes a stair facing
south. The tall-side-toward-lantern relationship is preserved because both
the lantern position and the stair facing rotate together. After capture and
re-placement at any rotation, the fixtures still look right.

**Light level is unchanged.** Sea lanterns emit light level 15. Stairs are
transparent to light propagation, so surrounding the lantern with stairs
does not change the light output. The room stays fully lit. The stairs are
purely visual.

**`isShell` does not change.** The ceiling check is "Y == CEILING_Y" for the
entire 16x16, which already covers slabs, stairs, and lanterns regardless of
block state. No new protected positions need tracking.

**Room skins pick up the new blocks automatically.** When the shell material
swaps, the stairs and slabs swap to the matching variant: deepslate-brick
stairs and deepslate-brick slabs for a deepslate skin, etc. The stair and
slab blocks are derived from the wall material, same as the ceiling block
already is.

---

## 10. Physical door selection: doors, screen, bulbs, lever

Today right-clicking a selector door opens a `ConfirmationDialog`
(`DialogScreens.doorOffer`) showing that door's offer. Clicking "Take this
key" runs `/dungeon choose <step>`. The dialog is a popup that interrupts the
walk. You cannot see all three offers at once; you click, read, close, walk to
the next, click again.

**The proposal: the doors are the menu, the screen is the display, the lever
is the confirm. No dialogs.**

### 10.1 The screen

Black blocks (`minecraft:black_concrete`) placed on the wall above the door
area, at Y=4..5, spanning x=5..10 (6 wide x 2 tall). A `text_display` entity
with `billboard: "fixed"`, `Rotation` matching the wall face, positioned
~0.1 blocks off the wall to avoid Z-fighting. The text_display shows the
currently selected door's offer: level, theme, affix. When no door is
selected, it shows a prompt: "Right-click a door to preview."

**Hearsay's `Bubbles.java` already solved the `text_display` minefield:**

- MC-277982: `see_through=true` renders text black. Set `see_through=false`.
- Forced brightness `{block: 15, sky: 15}` for dark environments.
- Text encoding since 1.21.5: NBT object via `ComponentSerialization.CODEC`,
  not a JSON string.
- Entity tagging (`pocketdungeons_screen`) for orphan cleanup.

The dungeon screens follow Hearsay's footprints almost exactly, changing
`billboard` from `"center"` to `"fixed"` and adding `Rotation`.

The screen is re-summoned after each room placement with fresh content, never
captured with the room. It is purged by the existing entity sweep on teardown.

**Screen geometry: 2 blocks tall, 8 wide.** The wall is 5 tall (Y=1..5). The
doorway takes 3 (Y=1..3). That leaves Y=4..5: 2 blocks of screen surface
above the doorway. At roughly 2 lines of text per block, the screen carries
4 lines. The screen is 8 blocks wide (x=4..11), centered above the x=7..10
door-plus-lever cluster. At 8 wide, each line fits roughly 20-25 characters
at a readable font size.

**What the screen shows, by context:**

*Context 1: no door selected (idle)*

```
POCKET DUNGEONS
Right-click a door
Pull the lever to start
```

3 lines, one blank. A tutorial that disappears the moment the player
interacts.

*Context 2: a door is selected (previewing)*

```
KEYSTONE 7
Desert Temple
Ominous
Feral, Swarming
```

- Line 1: keystone level. Gates mob scaling, loot tier, seeded affix
  thresholds.
- Line 2: theme name. The biome, the block palette, the adventure-graph
  hint.
- Line 3: elective affix. "Ominous" or blank. The risk/reward flag that
  distinguishes doors under the +1-only reframe.
- Line 4: seeded affixes. Derived from the level via `AffixMath.effective`.
  Showing these on the preview lets the player choose the fight they want,
  not gamble on it. The hidden-graph discovery (section 2.2) is about which
  themes are available, not what the affixes are. The affixes are a
  difficulty signal, not a discovery reward.

*Context 3: dungeon active (run in progress)*

```
KEYSTONE 7
Desert Temple
Ominous, Feral, Swarming
Spawners: 3/8
```

- Line 1: keystone level. Still relevant; the player needs to remember what
  they are risking.
- Line 2: theme. Still relevant for orientation.
- Line 3: all active affixes, elective and seeded together. Today this
  appears in chat once; the screen makes it persistent and visible.
- Line 4: spawner progress (if spawner gating lands, section 2.3) or the
  timer. The timer is more urgent if both are implemented: it is the thing
  that can make you lose.

*Context 4: post-completion (room mode)*

```
Bob's Hideout
Public - 2 visitors
Whitelist: 3
```

Or if private:

```
Bob's Hideout
Private
Whitelist: 3
```

- Line 1: room name (host-set string from section 8).
- Line 2: visibility and visitor count.
- Line 3: whitelist size.
- Line 4: blank, or "Right-click terminal for menu."

*Context 5: lever pulled with no door selected*

```
Select a door first
```

One line, centered. Flashes briefly, then returns to the idle prompt. The
copper bulb above the lever stays dark. The chime plays the "refused" bass
note (section 13).

**What stays off the screen:** door number (the copper bulb shows which),
fuel cost (separate engine terminal, section 10.7), player's current level
(under +1-only, every door is +1, so the arrow adds a line for information
the player already has on their keystone item).

### 10.2 Copper bulb selection signal

Three `minecraft:copper_bulb` blocks placed above the three selector doors at
Y=4, one per door. All start dark. Right-click a door and its bulb toggles
`LIT=true`; the previous selection's bulb toggles `LIT=false`. The bulbs never
move; only their `lit` blockstate changes. One blockstate write per toggle, no
position tracking, no entity.

This is simpler and more visible than the invisible `Blocks.LIGHT` approach
spiritwolves uses. A lit copper bulb is a visible signal you can see from
across the room: three dark bulbs, one glowing, the glowing one is your
selection.

### 10.3 The lever as the fourth door

The three selector doors are at positions `{7, 8, 9}` along the wall. The
lever at position 10 makes a clean four-wide cluster:

```
x=7   x=8   x=9   x=10
[door1][door2][door3][lever]
```

Four interactables in a row, same gesture (right-click) for all of them. Three
preview, one commits. The lever is the fourth door that does something
different. This fixes the asymmetry of three doors with no clear confirm
location: the lever is just the next slot in the same row.

A copper bulb above the lever (position 10, Y=4) signals readiness: lit when a
door is selected, dark when none is. The player sees "you have picked a door,
now pull me."

### 10.4 The flow

1. Right-click a door: copper bulb above it toggles on, previous bulb off,
   screen updates to show that door's offer.
2. Right-click another door: bulbs swap, screen updates.
3. Pull the lever: if a door is selected, `Instances.chooseOffer` runs with
   the selected step and the dungeon starts. If none is selected, a message:
   "Select a door first."

No dialogs. The walk between doors is the browse. The lever is the commit.
The screen is always visible, so a new player sees "Right-click a door to
preview" and then "Pull the lever to start" without needing a tutorial.

### 10.5 What this replaces

- `DialogScreens.doorOffer`: deleted.
- `RitualListener.sendDoorOffer`: replaced with a selection-state update.
- "Take this key" button: replaced by lever pull.
- Dialog "Close" button: replaced by right-clicking a different door or walking
  away.

What stays: `Instances.chooseOffer` (called from the lever with the selected
step), `Keystone.offers` (still generates the three offers), `placeSelectorDoors`
(still places three door blocks), `selectorDoorStep` (still detects which door
was right-clicked).

### 10.6 New state and furniture

- `selectedStep` field on `InstanceRecord` (0 = none, 1/2/3 = door selected).
- Copper bulb placement at stamp time, three above the doors plus one above the
  lever.
- Lever block placed at position 10 on the selector wall.
- Screen blocks and text_display placed above the door area.
- Furniture protection: the lever, screen blocks, and wall lodestone are
  mod-placed and cannot be broken by the player. A small `isFurniture(pos,
  record)` check alongside `isShell` in `RoomProtection`.

### 10.7 The engine terminal: fuel as a separate physical surface

Fuel cost does not go on the door screen. The door screen carries run
information only; fuel is a separate terminal on a different wall, with its
own block, its own screen, and its own interaction. Two terminals, two
purposes:

- **Door terminal:** select doors, start runs, see run info.
- **Engine terminal:** feed fuel, see fuel level.

**The engine block: `minecraft:respawn_anchor`.** It already communicates
"charge level" through its blockstate (0/1/2/4 charges, visually distinct).
The mod maps diamond count to charge levels: 0 diamonds = empty, 1-3 = level
1, 4-6 = level 2, 7+ = level 3 (or whatever the sink economy demands). The
player right-clicks the anchor with diamonds, the mod consumes them and
updates the charge. The screen above shows the exact count; the block itself
shows the approximate level at a glance.

The vanilla glowstone-charge interaction is intercepted and cancelled by the
mod, same as the mod already cancels vanilla door interactions. The anchor is
a visual shell, not a vanilla respawn mechanic, in this context.

**The fuel screen:** a second `text_display` above the engine block, same
`billboard: "fixed"` pattern, showing:

```
FUEL: 7 Diamonds
Cost per premium door: 3
```

Two lines. The screen is smaller than the door screen because it carries less
information. If different premium doors cost different amounts, the screen
lists per-door costs:

```
FUEL: 7 Diamonds
Door 2: 3
Door 3: 5
```

Door 1 (the free vanilla door) has no cost line.

**Room layout:** the engine goes on a wall adjacent to the selector wall. If
the selector wall is north (doors at x=7..10), the engine could be on the
west wall at z=7..8, facing into the room. The player walks in, sees the
doors on the north wall and the engine on the west wall. Two terminals, two
screens, two purposes.

**The engine block and its screen are part of the immutable furniture.** The
player cannot break them. The `isFurniture` check covers them alongside the
lever, screen blocks, and wall lodestone.

**What this keeps clean:** the door screen never mentions fuel. The player's
decision at the doors is "which theme, which affixes, which fight." The fuel
decision is separate: walk to the engine, feed it diamonds, walk to the
doors. The two interactions do not compete for the same screen real estate or
the same mental step.

If the player pulls a premium lever without enough fuel, the refusal is on
the door screen ("Not enough fuel") with the bass chime. The player walks to
the engine, feeds it, walks back. The physical separation makes the sink feel
like a real thing you maintain, not a number in a menu.

---

## 11. UX consolidation: one lodestone, one menu

Today there are five distinct lodestone interactions, each with its own entry
point: right-click with keystone (enter), right-click with card (visit),
right-click selector door (door offer), stand on leave-pad (exit), stand on
terminal pad (complete). Plus `/dungeon exit` and `/dungeon choose` as command
shortcuts.

The leave-pad is the annoying one. `isOnRoomLeavePad` fires whenever you stand
on a lodestone inside your room cell bounds. After completion, your room is
right there with chests to loot, and the leave-pad is in the same cell. Step
on it accidentally while looting and you are ejected.

**The proposal: one right-click opens one menu.** The wall lodestone (section
9.3) is the terminal. Right-click it with anything (not just the keystone) and
the menu opens. The menu is a `MultiActionDialog` whose contents depend on
context:

**Overworld (right-click any lodestone):**
- Start Dungeon (the current auto-enter behavior, now a button; requires
  keystone)
- Browse Lobbies (the room navigator from section 8)
- Manage Room (whitelist, name, public/private toggle)
- Inspect Keystone (currently `/dungeon key` dialog)

**In dungeon (right-click the wall terminal):**
- Leave (replaces the stand-on-leave-pad mechanic and `/dungeon exit`)
- Manage Room (if in your own room, post-completion)
- Inspect Keystone

**What stays as a stand-on mechanic:** the terminal pad at the dungeon's end.
This is the only stand-on-lodestone interaction that survives. It is hard to
trigger accidentally: the terminal pad is at the end of the dungeon, behind
the last door, not in the room where you are looting. The "walk forward, never
double back" hook in `VISION.md` 1 depends on reaching the end being a
physical act, not a menu click.

**What gets deleted:** `isOnRoomLeavePad` and its branch in the watcher. The
room's lodestone stays as a physical block, but it becomes a right-click
terminal, not a stand-on trigger.

**The `hasInstance` guard inverts.** Today `RitualListener` blocks
right-clicking a lodestone while you have an instance, to prevent eating a key
to "enter" a dungeon you are already in. With the menu, right-clicking while in
a dungeon is the intended way to open the menu and click Leave. The guard
changes from "block if you have an instance" to "show the in-dungeon menu if
you have an instance."

**Right-click with anything opens the menu.** The keystone is checked when you
click "Start Dungeon," not when you open the menu. A player who just wants to
leave their room never needs to find their compass first.

**Selector doors stay as right-click interactions.** They are doors, not
lodestones, and the walk-to-a-door-and-click is the mechanic `VISION.md` 1
calls "not up for renegotiation." The menu is for mod navigation; the doors
are for run selection. Different verbs.

**`/dungeon` commands stay as power-user shortcuts.** The menu is the primary
interface; the commands are for operators, macros, and players who prefer
typing.

---

## 12. A possible implementation order, if any of this goes ahead

A sensible order, smallest and most isolated first. The first two are
independently useful regardless of whether the rest ever lands.

1. **Spawner gating (2.3).** Pure completion-logic change, no economy or
   door rework. Fixes the sprint-to-win gap on its own.
2. **Mob strength scaling (2.4).** One listener, config-driven curve. Also
   independently useful.
3. **Drop Fragile, make Ominous +1 (2.1).** Small, but it is the door rework
   in miniature and it is hard to do 2.2 without it.
4. **Adventures + fuel sink (2.2 + 3).** The big one. Depends on the
   fuel-currency decision and on the loot-plateau decision (2.4's reward
   question). This is where the reframe lives.
5. **Loot plateau fix (2.4's option 3).** Really part of 4, but called out
   because it is the part most likely to slip and the most damaging if it
   does.

Steps 1 and 2 can ship before the rest and the mod is better for them
regardless. Steps 3-5 are the rework proper and should land together or not
at all, because the door reframe only holds if the sink and the loot plateau
are solved in the same pass.

6. **Room UX pass (sections 8-11).** The visiting rework, immutable shell,
   physical door selection, and lodestone menu consolidation are largely
   independent of the door/ladder rework. They can ship before or after, in
   any order, though the door selection (10) depends on the screen and bulbs
   being placed, and the menu (11) depends on the wall lodestone (9.3).
7. **Sound pass (section 13).** Can ship at any point. Most valuable after
   the physical door selection (10) and menu consolidation (11) land, since
   those add the interactions that most need audio feedback.
8. **Room template editor (section 14).** Development tool, ships
   independently of everything else. Useful as soon as any room template
   needs hand-authoring.

---

## 13. Sound pass: audio feedback for every interaction

Pocketdungeons is nearly silent today. The only sound calls in the mod are
two `RESPAWN_ANCHOR_CHARGE` plays in `RitualListener` (lines 134, 158), both
for the ritual trigger. Every other interaction, from door selection to run
completion to keystone level up, produces no audio feedback at all.

### 13.1 The house pattern

Every sibling mod in the workspace has a `Chime.java` (or `Jingles.java` in
quizengine's case) that wraps per-player sound playback. The convention:

- A `Chime` class with static methods, one per event.
- Sounds sent via `ClientboundSoundPacket` straight down the player's
  connection, so only they hear it. `Player.playSound` excludes the player it
  is called on (the vanilla client plays its own sounds locally), so the
  direct packet is what makes per-player cues work.
- All vanilla `SoundEvents`, mostly note blocks. No custom sound files, no
  assets directory, no client mod. Server-side only stays intact.
- Volume kept low (0.12 to 0.4f). Pitch varies for character: higher pitch
  for positive cues, lower for negative.
- Single notes for frequent events. Multi-note jingles for significant ones
  (quizengine's `Jingles` queues notes against server ticks for short tunes).

### 13.2 What needs a cue

Grouped by the interaction surface they belong to:

**Door selection (section 10):**

| Event | Cue | Character |
|---|---|---|
| Right-click a selector door | `NOTE_BLOCK_BELL`, short, mid pitch | "selected" |
| Copper bulb toggles on | same as above, fires together | |
| Lever pulled with no door selected | `NOTE_BLOCK_BASS`, low, short | "refused" |
| Lever pulled, run starts | `RESPAWN_ANCHOR_CHARGE` (already exists) or a rising two-note jingle | "committed" |

**Run lifecycle:**

| Event | Cue | Character |
|---|---|---|
| Run completes (terminal pad) | `NOTE_BLOCK_BELL` + `NOTE_BLOCK_CHIME` jingle, rising | "victory" |
| Run times out | `NOTE_BLOCK_DIDGERIDOO`, low, sustained | "loss" |
| Keystone level up | `NOTE_BLOCK_CHIME`, rising pitch | "progress" |
| Keystone level depleted | `NOTE_BLOCK_BASS`, descending two notes | "setback" |
| Spawner cleared (last one in a cell) | `NOTE_BLOCK_HAT`, very quiet | "small win" |

**Room and menu (sections 8, 11):**

| Event | Cue | Character |
|---|---|---|
| Menu opens | `NOTE_BLOCK_HAT`, quiet | "interface" |
| Room listed (public toggle on) | `NOTE_BLOCK_CHIME`, mid | "shared" |
| Room unlisted (public toggle off) | `NOTE_BLOCK_HAT`, quiet | "private" |
| Visitor arrives | `NOTE_BLOCK_BELL`, two notes | "someone's here" |
| Room relocated (post-completion stamp) | `PISTON_EXTEND` or `STONE_PLACE`, positional | "structural" |

**Lobby visiting (section 8):**

| Event | Cue | Character |
|---|---|---|
| Lobby browser opens | `NOTE_BLOCK_HAT`, quiet | "interface" |
| Visit starts (teleport to room) | `ENDERMAN_TELEPORT`, low volume | "travel" |
| Visit ends (return to overworld) | `ENDERMAN_TELEPORT`, lower pitch | "return" |

### 13.3 Implementation

A `Chime.java` in `pocketdungeons` following the spiritwolves/wondrous
pattern: one static method per event, each calling a private `play` helper
that sends a `ClientboundSoundPacket` to the player's connection. No queue
needed (pocketdungeons has no multi-note jingles in the table above, unlike
quizengine), so the class is ~60 lines.

The two existing `RESPAWN_ANCHOR_CHARGE` calls in `RitualListener` stay or
migrate to `Chime.runStarts`, depending on whether the lever pull replaces
the ritual trigger. If the lever becomes the commit (section 10), the
respawn-anchor sound moves to the lever handler.

Call sites: each event in the table above maps to one existing code path
(`completeRun`, `expireTimedOut`, `Keystones.grantOffer`, `chooseOffer`,
etc.). The sound call is one line added at the end of each path, after the
state change succeeds. No new hooks, no new listeners, no new state.

### 13.4 What this does not include

- **Ambient music.** No per-dungeon music tracks. Vanilla's ambient music
  already plays in the overworld; the dungeon dimension is silent by design
  (sealed boxes, no biome music). Adding music is a separate question and a
  much larger content investment.
- **Mob sounds.** Mobs already make their own sounds via vanilla AI. The
  sound pass is for mod interactions, not mob vocalizations.
- **Custom sound files.** Everything in the table is a vanilla
  `SoundEvent`. No `.ogg` files, no `sounds.json`, no asset directory. The
  server-only rule stays intact.

---

## 14. Room template editor: Minecraft as a room authoring tool

A development tool for hand-authoring room templates of any kind: player
rooms, encounter rooms, entrance halls, exit halls, terminal cells, boss
rooms. Build in Minecraft, save to `.nbt`, let an AI read the file and
generate the matching code.

### 14.1 What already exists

The mod has two separate capture mechanisms today:

1. **`RoomTemplateGenerator.captureAndSave`** stamps a room from code specs,
   then captures it to a `.nbt` file via `StructureTemplate.fillFromWorld`,
   writing to `src/main/resources/data/pocketdungeons/structure/rooms/`. This
   is the development-time template generator, gated behind
   `/dungeon admin gentemplates`. It stamps from specs, not from player-built
   rooms.

2. **`RoomStore.capture`** captures a player's live room into a per-owner
   `StructureTemplate` blob in memory for relocation. It never writes to disk
   as a `.nbt` file; it stays in the `RoomStore` map.

The proposed tool is a third thing: a free-build admin room where you build
by hand, then save to a `.nbt` file on disk. It reuses #1's capture method
but takes #2's input (a live built room, not a code spec).

### 14.2 The flow

1. `/dungeon admin buildroom` stamps an empty 16x16x6 shell in the dungeon
   dimension with no owner, no clock, no trial spawners, no keystone. Just
   the shell: floor, walls, ceiling, lamps. The player is teleported inside.
2. The player builds whatever they want. Place blocks, add furniture, wire
   redstone, place spawners, chests, decor. No immutability: the player can
   place and break anywhere in the full 16x16x6, including walls and ceiling.
   This is a development tool where the player is the author, not the
   resident.
3. `/dungeon admin saveroom <name>` captures the cell to
   `src/main/resources/data/pocketdungeons/structure/rooms/<name>.nbt`, using
   the same `captureAndSave` method that `gentemplates` uses.
4. An AI reads the `.nbt` file, parses the block palette and positions, and
   produces whatever output is needed: a `RoomSpec`, a manifest entry, a
   decor callback, or just a description of what is there.
5. `/dungeon admin manifest reload` loads the new template into the running
   mod.

### 14.3 Why this works with the existing code

`captureAndSave` is already a generic "capture this cell to a file" method.
It takes a `ServerLevel`, a `BlockPos`, and a `Path`. It does not care how
the blocks got there. Today it is called after code stamps a room; it could
equally be called after a player builds one.

`TEMPLATE_SIZE` is `Vec3i(CELL, CEILING_Y + 1, CELL)` = 16x7x16. That
captures the full cell including the floor and ceiling.

`adminBuild` already stamps unowned instances. The new command is a variant
that stamps just the shell and teleports the player in, with no keystone, no
clock, no trial spawners, no room capture on exit.

### 14.4 What is new

Two commands:

- **`/dungeon admin buildroom`**: stamps an empty shell, teleports the
  player in, marks the instance as an admin build room. No immutability on
  the shell. No trial spawners, no clock, no keystone. The instance stays
  open until `/dungeon admin purge <slot>` or `/dungeon exit`.

- **`/dungeon admin saveroom <name>`**: captures the cell at the player's
  current position to `src/main/resources/data/pocketdungeons/structure/
  rooms/<name>_<author>_<timestamp>.nbt`, where `<author>` is the saving
  player's in-game name and `<timestamp>` is `yyyyMMdd-HHmm` (e.g.
  `desert_shrine_Kriss_20260825-1432.nbt`). The filename is human-readable
  and sortable without parsing NBT, so a directory listing shows who built
  what and when. Same `captureAndSave` call, different trigger. The file
  is compressed NBT, same format as the existing template files. Two custom
  metadata tags are also written into the NBT alongside the template's own
  fields, following the `pd_` prefix convention `RoomStore` already uses
  (`pd_captured_rotation`, `pd_version`):
  - `pd_author`: the saving player's in-game name (`player.getName().getString()`).
  - `pd_saved_at`: ISO 8601 timestamp (`Instant.now().toString()`, e.g.
    `2026-08-25T14:32:00Z`). Sortable, parseable by any language, unambiguous
    about timezone.

  The filename carries the same data in a coarser form for browsing; the NBT
  tags carry it precisely for tooling. A directory listing answers "who built
  this and when" without decompressing anything; an AI reading the file gets
  the exact ISO timestamp without parsing the filename.

Two command handlers, roughly 30 lines each. The capture method already
exists. The shell stamping already exists (`RoomBuilder.buildCell` with an
empty door set). The admin instance tracking already exists.

### 14.5 The AI-reading part

The `.nbt` file is a `StructureTemplate` save: compressed NBT with a
`blocks` list (palette + positions), an `entities` list, a `size` field, and
the custom `pd_` metadata keys. An AI can parse this with any NBT library.
The structure is:

```
size: [16, 7, 16]
blocks: {
  palette: [{Name: "minecraft:stone_bricks"}, {Name: "minecraft:air"}, ...]
  blocks: [{pos: [0,0,0], state: 0}, {pos: [1,0,0], state: 0}, ...]
}
entities: [...]
pd_author: "Kriss"
pd_saved_at: "2026-08-25T14:32:00Z"
```

The palette maps state indices to block names plus properties. The blocks
list maps positions to palette indices. An AI reading this can reconstruct
the full room: what blocks are where, what entities are present, what the
door positions are.

For the AI to produce useful output (a room spec, a manifest entry, a decor
callback), it needs to know the room geometry contract: 16x16x6, door slots
at x=7..8 on each wall, Y=1..3, etc. That contract is documented in
`RoomGeometry.java` and `RoomBuilder.java`. The AI reads the `.nbt`, maps
block positions to the geometry contract, and produces the spec.

### 14.6 The workflow this enables

1. You (or a builder, or an AI with a Minecraft client) run
   `/dungeon admin buildroom`.
2. You build a room by hand: place chests, spawners, decor, redstone,
   whatever fits.
3. You run `/dungeon admin saveroom desert_shrine`.
4. The `.nbt` file lands in `src/main/resources/data/pocketdungeons/
   structure/rooms/desert_shrine.nbt`.
5. An AI reads the `.nbt`, sees "chest at (2,1,2), spawner at (4,1,11),
   spawner at (11,1,11), sandstone walls, dead bush decor," and produces a
   `RoomSpec` or manifest entry that matches.
6. The room is now part of the mod's room library, loadable via
   `/dungeon admin manifest reload`.

Minecraft becomes the room editor. The `.nbt` file is the interchange
format. The AI is the code generator. You design in the medium that is
easiest to design in (Minecraft itself), and the AI translates the design
into the code the mod needs.

### 14.7 Scope: not just player rooms

This tool is for authoring any room template the mod uses: encounter rooms,
entrance halls, exit halls, terminal cells, boss rooms, themed variants.
The existing `RoomTemplateGenerator` stamps rooms from code specs and
captures them; this tool inverts that flow: build first, capture second,
generate the spec third. The captured `.nbt` is a faithful snapshot of
whatever was built, including the shell material, so a desert-themed room
built with sandstone walls captures with sandstone walls, and the AI can
read that to produce a themed manifest entry.

The `gentemplates` command stays for code-driven template generation. The
`buildroom`/`saveroom` pair is for hand-driven template generation. Both
write to the same directory in the same format.

---

## 15. Additional feature ideas

### 15.1 MrPinoy's Experimental Dungeon

A fourth door (or a special state on one of the three) that offers a fixed,
server-wide dungeon for a limited time. During testing and development, this
is "MrPinoy's Experimental Dungeon": a curated theme and affix combination
the operator sets by hand, flagged with a caution indicator on the door
screen so players know it is experimental content that may be unbalanced,
broken, or subject to sudden changes.

The door screen shows something like:

```
! EXPERIMENTAL !
MrPinoy's Experimental Dungeon
Theme: Desert Temple
Affixes: Feral, Swarming
Use at your own risk.
```

The copper bulb above the experimental door could use a distinct signal
pattern (fast blink, or a different bulb color if skins land) to reinforce
that this is not a normal offer.

Implementation: an operator command sets the experimental offer (theme,
affixes, optional loot override) on the server. The door screen reads it
like any other offer. No per-player daily reward tracking yet; that comes
once the feature graduates from testing. The caution label is a boolean on
the offer, rendered as a prefix on the screen text.

### 15.2 Room visitor log

A dialog accessible from the wall terminal showing the last N visitors to
your room: name, time, whether they are still inside. This gives the host a
sense of their room's traffic without being online 24/7. It is the Habbo
"who's been here" panel, implemented as a `MultiActionDialog` listing from
the same lodestone menu as the lobby browser.

Storage: a small ring buffer capped at 10 entries, pushed on each
`Instances.visit` call. Each entry stores the visitor's name and an
`Instant` timestamp. The dialog renders entries as "Kriss - 2h ago -
currently inside" or "Bob - yesterday - left."

### 15.3 Death checkpoint

If dungeons grow long enough that re-traversing from the entrance after a
death is a meaningful frustration, a checkpoint system could save the
player's position at each cleared spawner cell. On death in the dungeon
dimension, the player respawns at the last cleared cell instead of the
entrance. The death penalty stays (they still lose the fight they were in);
only the re-traversal cost is reduced.

This is contingent on dungeon length. The current layout is short enough
that re-traversal is trivial. If multi-floor or extended dungeons land
(section 2.2's adventure graph, or any future "deep dungeon" idea), this
becomes worth implementing. Until then it is deferred.

### 15.4 Room prestige

After a player has kept the same room for many completions, a vague
"prestige" concept could mark the room as long-established and give it a
small, unspecified perk. The visual marker (a different lamp, a particle
effect, a lodestone decoration) is the cheap part and carries the social
signal: "this room has been here a while." The mechanical perk is left
undefined until the loot and room-state systems are stable enough to know
what a fair bonus looks like. The point is to reward long-term room
ownership and give veterans a visible status signal, in the spirit of
`VISION.md` 3.6.1's provenance argument extended to the room itself.

### 15.5 Discoverable room shells

The immutable room shell (section 9.1) is stone bricks by default. Players
can discover and unlock alternate shell materials: sandstone, deepslate,
nether brick, copper, prismarine, whatever fits a theme. A shell swap
replaces only the immutable shell blocks (floor, edge walls, ceiling, lamp
fixtures); everything the player placed inside stays exactly where it is.
The shell is the frame, the interior is the painting, and you can reframe
the painting without touching it.

The lodestone terminal menu (section 11) gets a "Change shell" option. If
the player has unlocked no alternate shells, the option shows the default
shell and a grayed-out list, which is enough to signal that the feature
exists and that there is something to look for. Discovery does not need a
tutorial; the menu option is the tutorial.

Two unlock paths, both feeding the same shell list:

1. **Rare adventure rooms.** Some shells are gated behind a hidden
   adventure transition (section 2.2). The player must enter a specific
   sequence of doors to reach a rare room: sand temple, descend two floors,
   roll a "dead grave robber" encounter, and from there have a chance at a
   "pharaoh's chamber" whose completion chest contains a shell unlock
   token. The shell is not the reward for beating the room; it is a possible
   reward, the way a rare drop is a possible reward. The hidden graph
   preserves the folklore dynamic: players who find the path tell other
   players, and the menu option tells everyone that paths exist to find.

2. **Prestige reward.** Tied to section 15.4: reaching a prestige threshold
   on a long-held room unlocks a shell as a veteran reward. This gives the
   prestige concept a concrete, visible payoff without committing to a
   mechanical bonus that might unbalance loot.

The shell list is per-player, stored alongside `DungeonLog.Entry`. A shell
swap is a single `RoomBuilder.rebuildShell` call that replaces only the
shell blocks, reads back the interior via `RoomStore.capture` first as a
safety net, stamps the new shell, then re-places the interior. The capture
and re-place already exist; the shell swap is a new orchestration of
existing primitives.

### 15.6 Pocket² Dungeon

A dungeon within a dungeon. During a run, the player finds a special door
inside an encounter room: not a selector door, not a room door, but a
passage leading deeper. Stepping through drops them into a short, intense
sub-dungeon with a hard timer: grab as much as you can before the clock
runs out and you are ejected back to the entrance.

The name is the joke and the mechanic: a pocket dungeon inside a pocket
dungeon. Players will call it "Dungeon Dungeon" in conversation, which is
exactly the kind of folkloric shorthand that spreads by word of mouth.

The design is a Mario quick-timer room, not a full nested run. There is no
spawner gate, no completion pad, no keystone cost. There is a timer, a
stretch of rooms full of loot and danger, and a boot. When the timer hits
zero, the player is teleported back to the sub-dungeon's entrance, the
sub-dungeon closes, and they are returned to the outer run's room they
entered from. Whatever they grabbed in that window is theirs. Whatever they
did not reach is gone.

Design hooks:

- The nested door is rare. It does not appear in every run, and when it
  does, it appears inside an encounter room the player has already cleared.
  This rewards thorough exploration over speed-running to the terminal pad.
- The timer is short and fixed. Maybe 60 seconds, maybe 90. Short enough
  that the player cannot clear every room, only grab what they can reach
  and decide what is worth the detour. The timer is visible on a screen at
  the entrance and counts down the moment they step in.
- The loot is loose. Chests, loose drops, maybe a spawner or two that the
  player can choose to fight for the drop or skip to save time. The
  sub-dungeon is a loot piñata with a fuse, not a gauntlet with a gate.
- Death is not the exit. Dying inside the Pocket² ejects the player the
  same way the timer does: back to the entrance, sub-dungeon closes, back
  to the outer run. But dying costs them the loot they had not yet pocketed
  from the sub-dungeon (it collapses with the instance), and the outer
  run's death penalty still applies. The timer is the soft exit; death is
  the hard exit with a cost.
- The outer run's clock keeps ticking. Time spent in the Pocket² is time
  the outer run's timer or spawner gate is still counting. The player
  chooses: dive for bonus loot, or play it safe and finish the outer run.
- Rare drops live here. Shell unlock tokens (section 15.5), keystone
  upgrades, or other rare items that the outer run does not offer. The
  Pocket² is the path to the rare stuff, and the timer is why not everyone
  gets it.
- Discovery is hidden, like the adventure graph (section 2.2). The nested
  door's appearance depends on the current theme and the rooms traversed,
  not on a guaranteed spawn. Players who find one tell other players, and
  the next player who enters the same theme sequence knows to check cleared
  rooms before leaving.

Implementation sketch: the nested door is a special `RoomSpec` door type
placed during encounter-room generation, gated by the adventure graph's
transition logic. Stepping through creates a child `InstanceRecord` linked
to the parent, with its own cells but no spawner gate and no completion
pad, only a countdown timer. The child's tick watcher counts down; on zero
or on player death, it tears down the child instance and returns the player
to the parent instance at the room they entered from. The parent's tick
watcher continues running throughout; only the player's position and active
instance change.

---

## 16. Lore: why this world exists

**The idea, recorded in full before anything is decided about it.** A
noble adventurer, Steve, was corrupted by the idea of a perfect world and
set out to build one of his own. He sought the power of the Ender, and
absorbing the Ender Dragon's power is what turned him into Herobrine. Mad
with that power, what he built instead was a fractured, recursive
reflection of a world: Pocket Dungeons. The Herobrine Cube (section 3.5)
is his own ritual tool, the one he used to fracture the world in the first
place, which is why placing the right items in it does not craft anything
Steve ever meant to craft.

**This retroactively explains a lot that was previously just flavour.**
Echo shards mint the keystone (section 3's fuel discussion) and are the
one vanilla item whose entire purpose is navigating the deep dark, the
closest thing vanilla has to "a device for finding something buried."
Ender pearls, an ender chest, and chorus flowers already sit in the tier-3
loot tables. End stone, end stone bricks, purpur, and the "ancient city"
material palette (deepslate tiles, chiselled deepslate, sculk, sculk vein,
soul lantern, reinforced deepslate) are already the tier-3 block palette,
per `PROGRESS.md`'s T6.2 note. None of that was authored as Ender-flavoured
on purpose; it fell out of "what is rare and thematically deep-tier in
vanilla." A Herobrine-and-the-Ender-Dragon origin gives all of it a single
reason to already look the way it does, discovered after the fact rather
than planned into it.

**The real tension, named rather than smoothed over.** `VISION.md` §9
states plainly: "**Not a lore project.** The mechanics carry the weight.
The text stays funny." §4, "The trick," is more specific and calls the
silence itself the point: "**Nothing explains this.** No message, no
sound, no lore entry... The single acknowledgement is an advancement on
first completion. A toast, a shrug, no confirmation," and names this "the
most memorable thing in the design and the cheapest to accidentally
rationalise away with a UI, a message, or a loading screen." An explicit
Steve-becomes-Herobrine origin myth, told anywhere a player can read it in
full, is exactly what those two sections were written to rule out. This
brainstorm does not get to overrule a decision `VISION.md` states that
plainly; it can only surface the conflict and ask which way it resolves.

**Three ways this could actually land, in decreasing order of how much it
disturbs the existing decision:**

1. **Never surfaced to players at all.** The lore lives here and in
   whatever internal naming it inspires (`Herobrine Cube` the class name,
   commit messages, this document) and nothing in-game ever states it.
   Item names and existing flavour stay exactly as terse and unexplained
   as `VISION.md` §4 already wants. This changes nothing about the shipped
   experience and needs no decision at all; it is already true of everything
   this document does today.
2. **One object, one line, in the suite's existing voice.** The Cube
   itself gets a lore-tooltip line in the style `Unhinged Feral Keystone
   [24]` and `Cooked`/`Big L` already use: terse, funny, suggestive rather
   than explanatory. Something that rewards a player who already suspects
   something is wrong with this place without ever confirming it in full
   sentences.
3. **A real narrative surface** (an advancement description, a book, a
   structure), told in full. The one that actually conflicts with §4 and
   §9 as written.

**Decided: both tones coexist, kept separate.** Item and mechanic naming
(`Herobrine Cube`, keystone names, affix names) stays exactly as terse and
funny as `VISION.md` already wants, untouched by any of this. The lore
itself is a different register and is allowed to be a real narrative,
discoverable rather than announced: environmental and fragmentary, closer
to how vanilla tells the Deep Dark's or the End's story (no dialogue, no
exposition dump, pieced together from what is found) than to a book that
explains everything on page one. That reading keeps §4's actual point,
*the room trick specifically stays silent*, while letting the wider "why
does this place exist" question be something curious players can dig for.
Whether that fragmentary delivery becomes real structures, item lore, or
nothing shipped at all is still open; what is settled is that the lore is
allowed to exist and be found, on its own tonal register, without touching
the mechanical naming's voice. Formally updating `VISION.md` §9's "not a
lore project" line to reflect this is still a distinct step, not implied
by settling the tone question here.

### 16.1 The cosmology: a world built from fractured memory

The dungeon is not a place Herobrine built on purpose so much as a place
his fractured memory keeps building, over and over, out of the pieces of
Steve's own adventures. That is the diegetic reason runs are procedural
and thematic rather than one fixed dungeon: each generated layout is an
echo of somewhere Steve actually went, replayed chaotically rather than in
order, which is also why the adventure graph (section 2.2) is a graph
players discover rather than a story told in sequence. **The mechanical
progression already mirrors this without having been designed to:** themes
are echoes of specific adventures, rare adventure-graph nodes and
Herobrine Cube extracts (3.5) are the sharper, more intact memories buried
deeper in the chaos, and the ladder climbing further in is, diegetically,
digging further into what is left of him. Section 3's whole D3-package
answer to "what does level 100 have that level 10 doesn't" already reads
as "you have gone deeper into the wreckage"; this cosmology is why that
was true before anyone set out to make it true.

### 16.2 The arc: restore, seal, then keep it contained

Past whatever the endgame zone ends up being (the tier-3 Ender palette
already points at something End-flavoured, though nothing here commits to
a second dimension; that stays M8 D1's call), the narrative goal is not
"defeat Herobrine." It is restoration: undo what the corruption did and
return Steve to whatever he was before the Ender Dragon's power remade
him. Succeeding seals both Steve and Herobrine away, together, rather than
destroying either one. **The endgame loop is what happens after that
success, not before it:** the world Herobrine built out of his own
fractured memory does not stop existing just because he is sealed, so the
job past that point is containment. Every dungeon run after the seal is
diegetically the same work the ladder already asks for mechanically, going
back in to keep a chaotic, memory-built world from spilling back out,
which means the narrative and the mechanical answers to open question 1
now say the same thing from two different directions: mechanically, a
level-100 player has access the ladder gates; narratively, they are the
one still doing the containment work everyone else stopped needing to
worry about the moment the seal held. Neither answer was written to match
the other; they simply do.

### 16.3 Delivery: Alex's diaries

**The mechanism the "found, not announced" decision above needed and did
not have yet.** Alex, Steve's lover, went into the dungeons herself first,
trying to save him from his own madness before anyone else did. Diary
pages, found at milestones rather than handed out on a schedule, are hers:
written in the middle of an attempt the player is now, unknowingly,
continuing. `minecraft:written_book` is the entire implementation this
needs: a real vanilla item, pre-authored pages and a title, dropped instead
of crafted, same "no custom items" discipline everything else in this
document already holds to. No new item, no new mechanic, just content.

**Another retrofit, same shape as 16.1's.** `VISION.md` §3.1.1 already
says "the keystone is a recovery compass" and has since M3. Written before
any of this lore existed, for an unrelated mechanical reason (it is
crafted from echo shards and echo shards find things). It reads
differently now: not just *a* recovery compass, *Alex's* recovery
compass, or something built the same way hers was. The player is not
just delving dungeons; they are carrying the same kind of tool she carried
doing the same thing she did.

**What the diaries are for, narratively.** Not a lore dump split into
pieces; an arc of her own, found out of order the way the adventure graph
itself is discovered (2.2), so no player reads it start to finish on
purpose. Early entries read as hope: she thinks she can reach him. Middle
entries read as dread, as whatever she is finding down there tells her
more than she wanted to know about what he has become. Late entries are
where hers and the player's arc (16.2) meet: she arrives, on her own,
at "saving him is not the same as reaching him, and sealing him away may
be the only mercy left." Whether she succeeds, fails, or simply stops
writing is deliberately not decided here; any of the three sets up a
different reason the player is the one finishing this, and that choice
should be made once alongside actually authoring the entries, not now.

**Where the milestones come from.** The mod already has a named
progression ladder with no lore attached to it: `AffixMath.intensifier`'s
bands (Baby 1-5, Lowkey 6-10, Highkey 11-15, Menace 16-20, Unhinged 21+,
extended by section 2.4's cap-raise). Hanging one diary find on each new
band entered is a milestone system this document does not have to invent;
it already exists and already means something to a player who has seen the
names on their own keystone. This also means diary count is naturally
capped and naturally rare (one per band crossed, once per player, tracked
the same way `DungeonLog.Entry.completedThemes` already tracks a
no-duplicates set), rather than a random drop that could double up or
never show at all.

**Open:** the actual entry text (not written here on purpose; it needs its
own pass, not a paragraph inside a mechanics brainstorm), whether a diary
is a guaranteed find on first entry to a new band or a weighted chance
across runs at that band, and whether Alex's fate is ever stated outright
anywhere or stays exactly as unresolved as the rest of this section's
"found, not announced" decision asks everything else in section 16 to
stay.
