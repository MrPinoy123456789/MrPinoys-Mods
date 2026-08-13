# MrPinoy's Wayfarers — Build Spec

> **Status:** design draft, 2026-08-12. Not implemented. The config schema (§5)
> is the load-bearing part of this document — everything else is an instance of
> it.
>
> **Verification:** every Minecraft signature marked ✅ was checked against
> `minecraft-merged.jar` / `minecraft-merged-deobf-26.2.jar` in the Gradle cache
> on 2026-08-12. Where something is *not* verified it says so, and §15 lists
> what must be checked before code is written.

---

## 1. The problem this solves

Nothing in the suite rewards leaving your base. Every system is either typed
into chat (`/shop`, `/bounty`, `/daily`) or delivered to you (quiz rounds, the
donkey). Ten mods and not one reason to walk somewhere.

Wayfarers puts encounters in the world at a distance you have to choose to
close: traders, patrols, the odd stranger, the occasional corpse. You find them
because you were out there.

> **The load-bearing rule: the player must always decide to approach. A wayfarer
> never follows, never blocks, and never demands attention. If it can reach you,
> it is a donkey, not a wayfarer.**

**The second thing it buys, which is nearly free.** A wayfarer's `sells` block
uses the same listing shape as `shop.json`, including the `components` field
(`DESIGN.md` §3). So a trader can sell a Spirit Stone, a Hurl Focus, or a
Wondrous item **without knowing any of those mods exist** — and a player can
meet a Spirit Stone in a swamp before they ever learn `/shop` is a command.
That makes this a second distribution channel for the whole suite and a
discoverability surface at the same time, at zero coupling cost.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Initiative | **The player approaches. Always.** | §1. This is the entire distinction from `chatdonkey` |
| Lifetime | **Ephemeral.** Every encounter expires | No world persistence, no cleanup debt |
| Blocks | **Never places one. Not one.** | §4 — this removes an entire class of problems |
| Bodies | **Vanilla mobs only** | Packless. No fake-player entities in v1 |
| Payment | **Physical diamonds and cobblestone only** | §7 |
| Economy coupling | **None. Never call `EconomyApi`** | §7 — and the README actively invites the mistake |
| Templates in code | **Two: `patient` and `hostile`** | Everything else is config composition (§5) |
| Speech delivery | **Floating bubbles (`TextDisplay`), not chat** | §5.3. Positional, and it keeps chat for players |
| Discovery | **A standing bubble is the lure** | §8.1 — the reason anyone walks over |
| Persistence | **Per-player record only** (the recurring trader) | No world state, no block ledger |
| Cross-mod | **None** | Sells other mods' items via `components` strings, per `DESIGN.md` §3 |
| Mixins | **Zero** | Nothing here needs one |
| Client requirement | **None.** `"environment": "server"` | Non-negotiable, suite rule |

---

## 3. Why this is not chatdonkey

The two mods spawn a named entity near a player and give it dialogue. They are
distinguished by one axis, and it must stay sharp or the donkey stops being
special:

| | chatdonkey | wayfarers |
|---|---|---|
| Spawns at | **4–8 blocks** — in your face | **28–64 blocks** — over there |
| Movement | Follows, re-paths, teleports, cannot be outrun | **Never moves toward the player** |
| Attention | Takes it, whether you want to give it or not | Waits to be given it |
| Duration | 30–60 seconds | **15 minutes** (§8) |
| Player's choice | How to make it stop | Whether to bother |
| Persists | Nothing, ever | One per-player record (§9) |

**The expiry is load-bearing.** At the donkey's 30–60 seconds a trader is
useless — you cannot spot a stall, realise you have no diamonds, run home and
come back. Fifteen minutes is chosen so that round trip is possible and slightly
tense. It is config; it is the first number to tune in play.

---

## 4. The mod never places a block

Killing the structure-based encounters removed the whole anti-grief problem, and
this is written down so nobody proposes a nice little campsite later.

No placement means: no tracked-block ledger, no prior-`BlockState` capture, no
revert-on-decay, no `SERVER_STOPPING` cleanup pass, no per-player block cap, no
no-spawn radius around builds, and no interaction with any claims mod.
`cobblebending`'s §8 is the entire cost avoided.

Everything is entities. Entities are tagged, tracked in memory, and
`discard()`ed. An entity orphaned by a crash reverts to an ordinary vanilla mob,
which is the same harmless failure `chatdonkey` chose.

---

## 5. The encounter schema — the load-bearing section

**An encounter is a JSON object. Adding one is config plus dialogue lines.**
This is `chatdonkey`'s `DemandBehavior` lesson (`PROGRESS.md`: *"Demand events
are now a template, not a class"*) applied from day one rather than at
milestone five.

`config/wayfarers/encounters.json`:

```json
{
  "encounters": {

    "lost_trader": {
      "weight": 20,
      "template": "patient",
      "body": { "type": "minecraft:wandering_trader",
                "name": "Ostvold", "nameVisible": true },
      "expireMinutes": 15,
      "dialogue": "lost_trader",
      "gossipChance": 0.4,
      "sells": [
        { "item": "minecraft:echo_shard", "quantity": 1,
          "price": 20, "currency": "diamond", "stock": 1,
          "components": {
            "minecraft:custom_data": { "spiritwolves": { "bound": false } },
            "minecraft:item_name": "Spirit Stone"
          }}
      ],
      "buys": []
    },

    "swamp_witch": {
      "weight": 10,
      "template": "patient",
      "body": { "type": "minecraft:witch", "name": "Mother Ash" },
      "biomes": ["minecraft:swamp", "minecraft:mangrove_swamp"],
      "dialogue": "swamp_witch",
      "coin": { "currency": "diamond", "amount": 6 },
      "buys": [
        { "tag": "enchanted", "price": 1, "currency": "diamond" },
        { "item": "minecraft:rotten_flesh", "quantity": 32,
          "price": 1, "currency": "diamond" }
      ]
    },

    "pillager_patrol": {
      "weight": 12,
      "template": "hostile",
      "body": { "type": "minecraft:pillager", "count": 3, "name": "Patrol" },
      "parley": { "demand": 4, "currency": "diamond",
                  "placatedBy": "minecraft:golden_helmet" },
      "drops": "patrol_table"
    }

  }
}
```

### 5.1 Blocks, and which template accepts them

| Block | `patient` | `hostile` | What it does |
|---|---|---|---|
| `body` | ✔ | ✔ | Entity type, count, name, nameplate visibility |
| `dialogue` | ✔ | ✔ | Which pool in `lines.json` |
| `weight` | ✔ | ✔ | Weighted selection, `0` disables without deleting |
| `expireMinutes` | ✔ | — | Hostile encounters resolve immediately |
| `sells` | ✔ | — | Listings, identical shape to `shop.json` |
| `buys` | ✔ | — | Reverse listings; capped by `coin` |
| `coin` | ✔ | — | What the buyer can spend before they're out |
| `wants` | ✔ | — | One item that ends the encounter well |
| `transform` | ✔ | — | The entity becomes something else (§6.4) |
| `inventory` | ✔ | — | A chest the player can empty (§6.5) |
| `wager` | ✔ | — | Stake, odds, prize, consequence |
| `once` | ✔ | ✔ | Once per player, ever (§6.6) |
| `parley` | — | ✔ | The pre-fight demand and what placates it |
| `threat` | ✔ | ✔ | Hostiles that spawn alongside (§6.3) |
| `drops` | — | ✔ | Which loot table on death |
| `biomes` / `nightOnly` / `weather` | ✔ | ✔ | Spawn conditions |
| `gossipChance` | ✔ | ✔ | Odds a line is drawn from the gossip pool (§9.3) |
| `recurring` | ✔ | — | This one remembers you (§9) |

**Unknown blocks are ignored, not fatal.** An entry naming a `template` this
build does not have is skipped with a warning. Same failure direction as
`chatdonkey`'s `EventPool`.

### 5.2 The two Java templates

**`patient`** — spawn, stand, look at the player when they're close, speak on a
cadence, respond to right-click with whatever blocks are configured, expire.
Covers the Wayfarer, the Zombie Villager, the Stray Donkey, the Marvel and the
Wager: five of the seven.

**`hostile`** — spawn, parley on approach or right-click, then either stand down
or fight. Covers the Patrol and the Caged Villager.

If a third class ever seems necessary, that is the signal to check whether it is
really a missing config block.

### 5.3 Dialogue

**This must be built in M1, not retrofitted.** If `LinePools` ships as
"pick one random string," ordered scripts and two-speaker scenes are a rewrite
rather than an addition.

A pool is a list of **entries**. An entry is either a plain string — one line,
said once — or a `script` object. Plain strings remain the common case and the
schema stays backward-compatible with `chatdonkey`'s.

```json
{
  "lost_trader.open": [
    "Ah! A customer! Or perhaps... a friend.",
    "You look like someone who appreciates fine merchandise."
  ],

  "skeleton_philosopher.open": [
    { "script": [
      { "say": "I don't understand why everyone is afraid of me." },
      { "wait": 40 },
      { "say": "I'm merely misunderstood." }
    ]}
  ],

  "creeper_watch.open": [
    { "script": [
      { "narrate": "The creeper watches you from across the field." },
      { "wait": 60 },
      { "say": "..." },
      { "wait": 80 },
      { "narrate": "It is gone.", "action": "vanish" }
    ]}
  ],

  "two_traders.scene": [
    { "script": [
      { "speaker": 0, "say": "I think Gerald is sick." },
      { "speaker": 1, "say": "Who's Gerald?" },
      { "speaker": 0, "say": "The blue sheep." },
      { "speaker": 1, "say": "You have seventeen blue sheep." },
      { "wait": 30 },
      { "speaker": 0, "say": "...Exactly." }
    ]}
  ]
}
```

**Step keys.** `say` (spoken, attributed to a body), `narrate` (italic grey, no
speaker), `wait` (ticks before the next step; a sensible default applies when
absent), `speaker` (index into `body.count`, default 0), `action` (see below).

**`"..."` is a legitimate line.** Silence with a beat around it is content, and
it is some of the cheapest content available.

#### Narration is text, plus a closed verb list

Some narration implies behaviour — *"It follows"*, *"The golem walks away"*.
`action` exists for that and it accepts **only** a fixed set:
`look_at_player`, `look_away`, `walk_away`, `vanish`, `swing`, `shake_head`.

> **Adding a verb is a code change, deliberately.** The moment `action` can
> express arbitrary behaviour this file becomes a scripting language and the
> work turns into debugging JSON instead of writing jokes. Six verbs cover
> everything currently written; if a seventh is genuinely needed, add it in Java.

`walk_away` and `vanish` are the two that end an encounter early — they must
route through the normal expiry path so nothing is orphaned.

#### Delivery — the bubble is the primary channel

**Speech appears as a floating text bubble above the speaker's head, not as a
chat message.** `Display$TextDisplay` renders on a fully vanilla client with
nothing installed, and it is *positional* — the player reads it where the speaker
is standing, which is what makes ambient dialogue work in games that have voice
acting. Chat is the secondary channel, not the default.

**Verified NBT keys** ✅ on `Display` / `Display$TextDisplay`:

| Key / flag | Use |
|---|---|
| `TAG_TEXT` | The line |
| `TAG_BILLBOARD` | Set so the bubble always faces the reader |
| `TAG_VIEW_RANGE` | How far away it is legible — load-bearing, see §8 |
| `TAG_BRIGHTNESS` | **Not optional.** At ambient light a bubble is unreadable at night |
| `FLAG_SEE_THROUGH` | Legible through terrain, or not — a feel choice |
| `FLAG_USE_DEFAULT_BACKGROUND`, `FLAG_SHADOW`, align flags | Presentation |

**Construction has the same gotcha as `cobblebending`'s boulder:**
`Display$TextDisplay` has **no public setters**. Build a `CompoundTag` and
`load()` it, exactly as that mod documents for `BlockDisplay`.

**Attachment:** mount the bubble as a **passenger** on the speaker
(`startRiding`) rather than repositioning it every tick, so vanilla handles the
following for free. *(verify — §15.)*

#### What a bubble costs that a chat line does not

Four things change, and each is a way to get this wrong:

1. **It is a world object, not a message.** Everyone in range sees the same
   bubble. There is no such thing as showing one to a single player, so the
   budget below is **per speaker and per area**, never per listener. A shared
   overheard moment is arguably better than a private one, but the model is
   genuinely different.
2. **Length stops being free.** Chat happily takes a paragraph; a floating
   bubble does not — a three-line bubble is a billboard obscuring the view.
   **Hard cap around 60 characters per bubble.** Longer lines either stay in chat
   or are split across sequential bubbles using the `wait` steps the script
   system already has. That pacing mechanism was built for comic timing and works
   here unchanged.
3. **Dwell replaces scrollback.** A bubble vanishes; chat persists. Dwell time
   scales with length — roughly reading speed plus a floor — and is config.
4. **Every bubble is a synced entity.** Cap concurrent bubbles per encounter and
   server-wide. Always despawn on a timer *and* on encounter end, so a crash
   leaves at most a few seconds of litter.

#### Which channel gets what

| Step kind | Channel | Why |
|---|---|---|
| `say` | **Bubble** | It has a speaker to float above |
| `narrate` | **Chat**, italic grey | It has no speaker. Nothing to attach it to |
| Trade confirmations, refusals | Chat | The player needs to be able to check them |
| Long line (>60 chars) | Split across bubbles, or chat | §2 above |

The `say`/`narrate` split falls out naturally: speech goes above the speaker's
head, narration goes to chat because there is no head to put it over.

#### The budget

In order of how much damage getting them wrong does:

1. **A running script holds the floor** for its whole duration. Two scripts must
   never interleave — the timing *is* the joke, and interleaving destroys both.
2. **One bubble per speaker at a time.** A new line replaces the current bubble
   rather than stacking a second one above it.
3. **Cap concurrent bubbles in an area**, so an encounter with `body.count: 3`
   does not produce three simultaneous billboards.
4. **Say nothing to a player who has taken damage in the last few seconds.** A
   punchline during a creeper attack is noise. `chatdonkey` already tracks damage
   recency via `AFTER_DAMAGE` for `donkeyCanKill`; same pattern. Because bubbles
   are per-world this is a *suppression* rule for the encounter, not a per-player
   filter — if any nearby player is in combat, hold the line.
5. **Chat lines** (narration, confirmations) remain rate-limited per listening
   player, since those genuinely are per-player messages.

`RateLimit` is pure arithmetic and belongs in `core` under test.

#### Shuffle bag, not random

**Draw lines without replacement, reshuffle only when the pool is exhausted.**
Random selection from ten lines produces a repeat almost immediately; a shuffle
bag guarantees a player hears all ten first.

This is the single cheapest defence against the failure everyone remembers from
ambient dialogue in big-budget games — the line you have heard four hundred
times. The bag is per pool per player, lives in `LinePools`, and is
transient: forgetting it on restart is fine and costs nothing.

#### Conventions worth protecting

- **Recurring names across pools.** A name that appears in more than one pool as
  a running joke is deliberate, not an inconsistency, and must survive editing.
  Record recurring names in a comment block at the top of `lines.json`.
- **The player-as-natural-disaster register.** Lines where NPCs discuss the
  player as an unpredictable weather event get their own pool rather than being
  diluted into general chatter. It is the strongest voice available and it stays
  a deliberate choice.
- **No animalese.** That is the donkey's voice and it stays the donkey's (§12).

---

## 6. The seven encounters

### 6.1 The Wayfarer *(patient)*

The chassis. Stands, talks, and carries any combination of `sells`, `buys` and
`gossipChance`. Empty all three and it is a stranger with an opinion, which is
still worth shipping.

**Bodies:** `WanderingTrader` (canonically lost, so it needs no explanation),
`Villager` with a profession, `Witch` in a swamp, a talking animal for the plain
weird ones.

The three earlier drafts of this list — a stall, a specialist buyer, a gossip —
are all this entry with different blocks filled in.

### 6.2 The Pillager Patrol *(hostile)*

Three pillagers. On approach they **parley before they fight** — that is what
makes it a decision rather than a mob spawn:

- Wearing gold (`placatedBy`) → they let you past with a line. This reuses the
  rule vanilla already taught the player with piglins, and players will discover
  it the same way: by noticing.
- Carrying the demanded currency → pay and pass, or refuse.
- Refuse, or attack first → a real fight with a real drop table.

**Guards.** Never spawns within a configurable radius of world spawn. Never for
a player under a configurable play-time threshold. Never in peaceful difficulty.
These are the difference between tension and a new player quitting.

### 6.3 The Caged Villager *(hostile)*

A villager and a `threat` of illagers closing in, on a timer. Keep them alive
and they pay you; fail and they don't. `Vindicator` named **`Johnny`** attacks
every mob in range ✅ (`Vindicator$VindicatorJohnnyAttackGoal` confirmed present),
so the aggro is free and needs no goal code.

Occasionally this is a `trap` — the villager is bait and the reward is the
fight. Knowing it is sometimes a trap is what makes the honest ones tense.

### 6.4 The Zombie Villager *(patient + `wants` + `transform`)*

Hand over one golden apple. **No weakness potion, no timer** — the consume
animation and sound carry the drama, and the cure is instant.

Implementation is a discard-and-respawn rather than vanilla's timed conversion,
which is both simpler and instant:

```java
VillagerData data = zombieVillager.getVillagerData();   // ✅ public
Villager cured = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
cured.setVillagerData(data);                            // ✅ public
cured.snapTo(...); level.addFreshEntity(cured);
zombieVillager.discard();
```

Sounds ✅: `SoundEvents.ZOMBIE_VILLAGER_CURE` then `ZOMBIE_VILLAGER_CONVERTED`.
Both are **bare `SoundEvent`, not `Holder.Reference`** — so they go through
`Level.playSound(...)`, not the `Chime` packet path. This is the split
`chatdonkey` documents; it is a vanilla API inconsistency, not ours.

The cured villager then behaves as a Wayfarer for the remaining expiry — trades
once, thanks you, and leaves with the rest of them.

### 6.5 The Stray Donkey *(patient + `inventory`)*

A chested donkey with no owner, standing where somebody clearly stopped. The
story is in the chest: some loot and a **written book** in a dead man's
handwriting. `DataComponents.WRITTEN_BOOK_CONTENT` ✅ — Minecraft's own text
format, and the player keeps it.

All four container calls are already proven by `chatdonkey`'s Burrs event:
`setChest(true)`, `setTamed(true)` (required — the screen checks `isTamed()`),
`openCustomInventoryScreen(player)`, and `getSlot(500 + i)` as the public route
to the protected inventory field. No Mixin, no access widener.

**Decision: the player keeps the donkey.** It is a plain vanilla donkey once the
encounter ends — leadable, rideable, ordinary. A free pack animal is a good
reward and costs nothing to implement, since not-discarding is less work than
discarding.

**The item-loss guard is unconditional**, copied from `chatdonkey`'s `Events.end`:
whatever is in the chest when the encounter expires is returned or dropped at the
donkey. A real container means a player can put their own things in it.

### 6.6 The Marvel *(patient + `once`)*

Once per player, ever, tracked in the §9 record. Weight low enough that most
players never see one. **No reward beyond a keepsake with a line of lore** — the
moment it pays out it becomes something to farm and stops being a story.

Candidates: an enderman standing motionless holding a dragon egg; a wandering
trader with no trades who only stares; a lit ruined portal with a figure beside
it; a villager wearing the player's own skin (packless via `ballot`'s
`Heads.java` — a base64 texture in a `GameProfile`, no resource pack).

### 6.7 The Wager *(patient + `wager`)*

Stake diamonds or cobblestone on something small. The native version uses the
**ominous bottle**: take it and the prize doubles, but a raid is now your
problem. Every player already has a relationship with Bad Omen, so the risk needs
no explanation.

House edge slightly against the player, so it is a sink. Questions can be drawn
from `quizengine`'s pool shape for free content — **by copying the JSON format,
not by importing anything.**

---

## 7. Payment, and the economy boundary

> **Locked: a wayfarer takes and pays only in physical `minecraft:diamond` and
> `minecraft:cobblestone`, from and to the player's inventory.**

### 7.1 Never call `EconomyApi`

`cobbleeconomy/README.md` has a *"For other mods"* section that shows exactly
this:

```java
EconomyService economy = EconomyApi.economy();
economy.withdraw(player.getUUID(), CurrencyRegistry.DIAMOND, 5);
```

**Do not use it. Not with `suggests`, not with a null check, not "just for the
balance display."** This is written here because an implementing agent will find
that README section and reasonably conclude it is the intended path.

The player withdraws from the bank with `/withdraw`, carries the items, and hands
them over. That is `DESIGN.md` §2 working as designed — the arrows are items, not
API calls — and it is what keeps this mod installable on a server with no
economy mod at all.

### 7.2 What the rule buys

**Risk, for free.** Withdrawing 30 diamonds and walking into the wild to spend
them means you can lose them. Pair that with §6.2 and the patrol is now taking a
toll from someone visibly carrying shopping money. That tension exists nowhere
else in the suite and nobody had to design it.

**Honest UI.** The trader can only see what you are carrying, so "you have 4
diamonds" means exactly that. No balance lookup, no lie.

### 7.3 The cobblestone ceiling

Physical payment caps cobble prices hard. A player can carry 2304 cobblestone
only by carrying nothing else; realistically they have a few stacks spare.

> **Cobble prices in `encounters.json` must stay under 640 (ten stacks).** Above
> that the listing is theoretically purchasable and practically not. Log a
> warning at config load for any cobble price over the cap rather than silently
> shipping a dead listing.

Diamonds have no such problem, which is why the interesting listings are priced
in them.

### 7.4 Taking and giving

Read **only the main 36 slots** — hotbar plus three rows. This is the suite rule
(`cobbleeconomy` documents why: armour and offhand indices have moved between
versions, and a mod that trusts `getContainerSize()` across two versions
eventually inserts cobblestone into a helmet slot).

**Count first, remove second.** A purchase that cannot be paid in full does not
partially execute. Payouts use the local `giveOrDrop` copy — add to inventory,
drop at feet if full, never destroy.

---

## 8. Spawning and expiry

**Placement** reuses `chatdonkey`'s `DonkeySpawn.findFooting()` / `isClear()`
search wholesale — copied, not shared (`DESIGN.md` §2: four copies of eight lines
beats a library). Ten candidates, real footing, never in lava, water, or a wall,
silently skipped if none works.

The numbers change:

| | chatdonkey | wayfarers |
|---|---|---|
| `MIN_DISTANCE` | 4 | **28** |
| `MAX_DISTANCE` | 8 | **64** |

Twenty-eight is roughly the far edge of comfortable render at ground level: far
enough that walking over is a decision, close enough to notice.

### 8.1 The bubble as the lure — how the player notices at all

Spawning something 28–64 blocks away raises an obvious problem: **at that
distance a villager standing in a field is scenery.** The player has no reason to
look, and an encounter nobody walks to is an encounter that never happened.

`TAG_VIEW_RANGE` (§5.3) solves it. An idle encounter shows a **standing bubble**
— short, three or four words, visible from further away than its speech bubbles
are:

> `"..."` · `"Hm."` · `"Wares."` · `"Careful, traveller."`

A scrap of text on the horizon is a lure. The player sees something over there
and walks toward it because they are curious, which is exactly the behaviour §1
asks for. **This turns the discovery problem into the discovery mechanic**, and
it is a better answer than the distance band alone.

Rules:

- The standing bubble is drawn from an `idle` pool, is capped shorter than a
  speech bubble (~20 characters), and does not count against the §5.3 budget —
  it is not a line, it is a sign.
- Its `view_range` is set generously so it is legible at spawn distance. Speech
  bubbles use a shorter range so conversation stays local.
- It is **replaced** by speech bubbles while the player is close, and returns
  when they step away.
- Hostile encounters get no standing bubble. A pillager patrol advertising itself
  from 60 blocks is not an ambush, it is a signpost.
- `SPEC.md` §1 still holds — the bubble does not follow, does not ping, does not
  appear on a compass. It sits there and can be ignored completely.

**The two view ranges are the numbers to tune first**, alongside the distance
band. Too short and nobody finds anything; too long and the world reads as
littered with floating text.

**Trigger** follows `chatdonkey`'s `TriggerRules` shape: a per-player roll on an
interval, gated on recent movement (a stationary player is not exploring),
per-player cooldown, and a server-wide simultaneous cap. Add one gate the donkey
does not need: **do not spawn an encounter within N blocks of one already
running for that player**.

**Expiry** is `expireMinutes` (default 15), *or* the player being more than ~128
blocks away for a continuous minute, whichever comes first. On expiry the entity
is discarded quietly — no farewell line, no chime. Something you didn't go and
look at should simply not be there any more.

**Orphan sweep.** Copy `chatdonkey`'s: an entity tag, a startup sweep, and
`ServerEntityEvents.ENTITY_LOAD` for stragglers in unloaded chunks. Note the trap
that mod documented: `ENTITY_LOAD` fires from *inside* `addFreshEntity`, so a
candidate must be **queued and judged on the next tick**, or the sweep bins your
own encounter the instant it spawns.

---

## 9. The recurring trader

The only thing this mod persists, and the reason it persists is attachment —
`DESIGN.md` §8: *the wolf a player names is worth more than the diamonds they
earn.* One named trader who comes back is the suite's second attachment carrier
after `spiritwolves`.

### 9.1 Storage

`world/data/wayfarers/<playerUuid>.dat`, NBT, one record per player, following
`spiritwolves`' `PlayerWolfRegistry` exactly: load all on `SERVER_STARTED` into a
transient map, `markDirty` on mutation, flush dirty records on a tick counter,
flush all on `SERVER_STOPPING`, atomic `.tmp`-then-move writes, and a malformed
file renamed `.dat.corrupt` and skipped rather than crashing the server.

```json
{
  "trader": { "id": "ostvold", "meetings": 4, "lastMetAt": 0, "spentWith": 61 },
  "marvelsSeen": ["enderman_egg"],
  "encountersMet": 23
}
```

### 9.2 What recurrence means

Nothing persists **in the world**. The trader is as ephemeral as everything else;
only the *relationship* survives. Each meeting:

- He greets you by reference to the last one — *"You again. The stone I sold you,
  is it still humming?"*
- `meetings` gates better stock. Meeting one is junk; meeting five is worth
  planning around.
- `spentWith` is the ladder: this is roadmap P3.2's escalating-patron psychology
  with no world fixture and no debt mechanic.

**He is never guaranteed.** Weighted like everything else, slightly favoured as
`meetings` rises. A trader you can summon is a shop.

### 9.3 Gossip

`gossipChance` draws a line from a gossip pool instead of the encounter's own
dialogue. In v1 the pool is **static, operator-authored text in
`lines.json`** — the same shape as Phase 1's donkey herald, and no different in
kind from an operator writing any other flavour line.

When the facts contract lands (`ROADMAP.md` §3), this is the second and better
consumer: the donkey delivers *urgent* facts because it interrupts; a wayfarer
delivers *ambient* ones because you walked over to hear them. **Keep the
selection behind one method so that swap is a one-site change.**

---

## 10. Edge cases — decided, not discovered

| Case | Behaviour |
|---|---|
| Player kills a patient encounter | It dies. It is a vanilla mob. No penalty, no reward, no drop table — and the dialogue pool gets a `murdered` moment so it is at least acknowledged. |
| Player attacks a *trader* | He stops trading, says something, and leaves early. Not immortal — immortality is the donkey's gag and it belongs to the donkey. |
| Player logs out mid-encounter | Encounter expires immediately, entity discarded. No cooldown penalty. |
| Player dies mid-encounter | Same. Dying is not a way to skip a cooldown. |
| Player changes dimension | Encounter ends as abandoned — the `chatdonkey` portal bug, fixed in advance. |
| Two players find the same encounter | Both may interact. `stock` is per-encounter, not per-player: first come, first served. |
| Stock runs out | Listing greys out with a reason. The trader stays until expiry. |
| Buyer's `coin` runs out | *"That's me cleaned out."* Buying stops, the trader stays. This is the §5 rate-limit and `DESIGN.md` §5's structural guard. |
| Purchase with a full inventory | Refused before payment is taken, with a reason. Never partially executed. |
| Encounter spawns in a player's base | Possible and acceptable — it places no blocks and expires. Add a config radius around world spawn only. |
| Server restart mid-encounter | Entity is swept as an orphan on next load. The player's record survives; the encounter does not. |
| Marvel already seen | Never selected again for that player. Checked against the record before spawning. |
| Peaceful difficulty | `hostile` encounters never spawn. |

---

## 11. Bodies — packless appearance

Vanilla mobs, custom-named, optionally equipped. **No fake-player entities in
v1** — proper humanoid NPCs need packet-level entity work and it is the one part
of this mod that would be genuinely hard.

**Verified class locations in 26.2** — the subpackage reorganisation caught four
more:

| Entity | 26.2 package |
|---|---|
| `Villager` | `net.minecraft.world.entity.npc.villager.Villager` ✅ |
| `WanderingTrader` | `net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader` ✅ |
| `ZombieVillager` | `net.minecraft.world.entity.monster.zombie.ZombieVillager` ✅ |
| `Pillager`, `Vindicator` | `net.minecraft.world.entity.monster.illager.*` ✅ |
| `Donkey`, `TraderLlama` | `net.minecraft.world.entity.animal.equine.*` ✅ |
| `IronGolem` | `net.minecraft.world.entity.animal.golem.IronGolem` ✅ |
| `Witch` | `net.minecraft.world.entity.monster.Witch` ✅ |

Per `chatdonkey`, the per-entity constants live on **`EntityTypes`**, not
`EntityType`.

### 11.1 The name tricks, and the trap in them

Verified present in 26.2:

- **`Dinnerbone` / `Grumm`** ✅ — found in `LivingEntityRenderer.class`. It is on
  the *base* renderer, so it flips **any living entity**.
- **`jeb_`** ✅ — `SheepRenderer.class`. Rainbow sheep, sheep only.
- **`Johnny`** ✅ — `Vindicator$VindicatorJohnnyAttackGoal`.

> **The trap.** The effect keys off the entity's custom name, so an upside-down
> NPC's nameplate reads "Grumm" and you cannot have an inverted trader called
> Marla. The fix is `setCustomNameVisible(false)` ✅ with the real name delivered
> in chat — which is house style everywhere in this suite anyway. Hence
> `body.nameVisible` in §5.

### 11.2 Suppressing vanilla interaction

A villager will open its trade screen, a donkey will let you mount it, a llama
will spit. Every interaction path must return a consuming `InteractionResult`
from `UseEntityCallback` so vanilla never runs.

`chatdonkey/PROGRESS.md` calls this **the highest-risk item in that mod** and it
is the same risk here. The solution is already written and play-tested there —
copy it rather than rediscovering it. Note the interaction module is
**`fabric-events-interaction-v0`**, not v1.

---

## 12. Sound

Two paths, because vanilla is inconsistent about it and `chatdonkey` documented
the split the hard way:

- **`Chime.java`** — quiet per-player confirmations via `ClientboundSoundPacket`,
  `SoundSource.RECORDS`, volume 0.15–0.4. Note-block sounds are
  `Holder.Reference<SoundEvent>`, which is what that packet wants.
- **`Level.playSound(...)`** — mob sounds. `VILLAGER_YES` / `VILLAGER_NO` /
  `VILLAGER_TRADE`, `WANDERING_TRADER_*`, `ZOMBIE_VILLAGER_CURE` /
  `_CONVERTED` are all ✅ present and all **bare `SoundEvent`**.

| Moment | Sound |
|---|---|
| Purchase | `VILLAGER_TRADE` / `WANDERING_TRADER_TRADE` |
| Refused | `VILLAGER_NO`, plus a `Chime` deny note |
| Cure | `ZOMBIE_VILLAGER_CURE` → `ZOMBIE_VILLAGER_CONVERTED` |
| Parley stood down | `Chime`, `NOTE_BLOCK_BELL`, 0.3 |
| Marvel spotted | Nothing. Silence is the point |

**No animalese.** That is the donkey's voice and it must stay the donkey's.

---

## 13. Module layout

Core/fabric split, because the selection, pricing, stock, wager odds and
trigger rules are all pure arithmetic and belong under test.

```
wayfarers/
  core/src/main/java/wayfarers/core/
      EncounterDefinition.java   — the §5 schema as a record
      EncounterPool.java         — weighted selection, biome/time/once filters
      Listing.java               — a sells/buys row; shop.json's shape
      Purchase.java              — count-first pricing, stock, coin depletion
      TriggerRules.java          — cooldowns, caps, activity gate
      Wager.java                 — odds and payout
      LinePools.java             — dialogue with pool fallback, shuffle bag (§5.3)
      Script.java                — §5.3 steps, speaker attribution, beats
      RateLimit.java             — the §5.3 budget, floor claiming, bubble caps
      ReadOrCreate.java          — copied from chatdonkey
      TraderRecord.java          — the §9 record, no NBT
  core/src/test/java/wayfarers/core/WayfarersTest.java
  fabric/src/main/java/wayfarers/
      WayfarersMod.java          — entrypoint
      Spawns.java                — footing search, distance band
      Encounters.java            — active registry, tick, expiry
      Patient.java               — template 1
      Hostile.java               — template 2
      TradeGui.java              — sgui, modelled on cobbleeconomy's ShopMenu
      Payment.java               — 36-slot count/remove, giveOrDrop
      Bodies.java                — entity construction, names, equipment
      Dialogue.java              — script playback, narration rendering, action verbs
      Bubbles.java               — TextDisplay construction, attachment, dwell, despawn
      TraderRegistry.java        — .dat persistence, PlayerWolfRegistry-shaped
      OrphanSweep.java           — copied from chatdonkey
      WayfarerCommands.java      — /wayfarer tree
      Chime.java
```

Config at `config/wayfarers/`: `encounters.json`, `lines.json`, `settings.json`.
All via `readOrCreate` — write defaults if missing, **never overwrite a file that
failed to parse**.

Commands, gated `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`:
`/wayfarer trigger [player] [encounter]`, `/wayfarer end [player]`,
`/wayfarer reload`, `/wayfarer status`, `/wayfarer forget <player>`.
The command tree is the whole external API, per `chatdonkey`'s design.

---

## 14. Build configuration

Copy `chatdonkey`'s (it has the core/fabric split already) and change names.

- Minecraft **26.2**, Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"`
- **No Mixins**
- Loom `1.17-SNAPSHOT`, applied in `settings.gradle.kts`, **not** in the
  `build.gradle.kts` `plugins {}` block
- `archivesName = "MrPinoys_wayfarers"`
- `sgui` `eu.pb4:sgui:2.1.0+26.2` from `https://maven.nucleoid.xyz`,
  `implementation` + `include`
- `dist` Copy task into the shared `dist/`, `build` `finalizedBy("dist")`
- `Identifier`, not `ResourceLocation`. Mojang names, no Yarn.

> **⚠ The bug every mod in this suite hit — eight times now.** The `dist` task
> must depend on **`jar`**, *not* `remapJar`. 26.2 ships unobfuscated so Loom
> registers no `remapJar` task, and depending on it fails the build outright.

---

## 15. Verify before writing code

1. **sgui trade screen.** `cobbleeconomy`'s `ShopMenu` is the model
   (`GENERIC_9x*`, `GuiElementBuilder`). Confirm a buy *and* sell layout reads
   clearly in one screen, or split them across two.
2. **`UseEntityCallback` suppression** for `Villager`, `WanderingTrader`,
   `Donkey` and `TraderLlama` specifically. Highest-risk item (§11.2).
3. **`VillagerData` round-trip** — is `setVillagerData` enough to preserve
   profession, level and biome type, or is `setVillagerDataFinalized(true)` also
   needed to stop the cured villager re-rolling?
4. **`EntityTypes` constants** for every body in `encounters.json`, by id lookup
   rather than a hardcoded switch.
5. **Ominous bottle / Bad Omen** — the component or effect name in 26.2, and
   whether a raid can be started server-side without a village nearby.
6. **`WrittenBookContent`** construction — pages, author, title, and whether
   generation/resolution matters for a book that is never signed by a player.
7. **`TraderLlama` caravan behaviour** — does a spawned trader auto-leash llamas,
   and does that fight the placement search?
8. **Loot tables** for `drops` — resolving a table by `Identifier` and rolling it
   server-side.
9. **`Display$TextDisplay` end to end** (§5.3) — spawn one via `CompoundTag` +
   `load()`, confirm a vanilla client renders it, and confirm `billboard`,
   `view_range` and `brightness` behave as the tag names suggest. **This is the
   single biggest implementation risk in the mod**; if bubbles do not work, the
   fallback is chat and §8.1's lure needs replacing.
10. **Passenger attachment** — does `startRiding` on a `TextDisplay` follow the
    mob cleanly, or does it need per-tick repositioning? Check the visual offset
    above the head too.
11. **Legibility at distance** — what `view_range` actually makes a 20-character
    bubble readable at 28 and at 64 blocks, and whether that changes with the
    client's render distance or GUI scale. §8.1's whole mechanic rests on this.
12. **Bubble entity cost** — spawn/despawn churn with several encounters running.
    If it is expensive, reuse one bubble per speaker and rewrite its text rather
    than respawning.

---

## 16. Definition of done — v1

Ship **three encounters only**: the Wayfarer, one Pillager Patrol, and the Stray
Donkey. They exercise dialogue, gossip, trading, payment, persistence, weighted
selection, combat and the parley — the entire framework — without the Marvel,
the Wager, the cure or the caged villager, none of which teach anything new.

- [ ] `./gradlew build` clean; `MrPinoys_wayfarers-0.1.0.jar` in `dist/`
- [ ] An encounter spawns 28–64 blocks away, on real ground, never in lava,
      water or a wall
- [ ] **It never moves toward the player** — the §1 rule, watched in play
- [ ] It expires after 15 minutes, silently, and after a 128-block absence
- [ ] Right-click opens the trade screen; vanilla trading never appears
- [ ] A `script` entry plays in order with its beats intact; two scripts never
      interleave
- [ ] `say` renders as a bubble above the speaker; `narrate` goes to chat as
      italic grey
- [ ] A bubble is legible at night (`brightness` set) and always faces the reader
- [ ] A line over the character cap splits across sequential bubbles rather than
      rendering a billboard
- [ ] One bubble per speaker — a new line replaces it rather than stacking
- [ ] Bubbles despawn on their timer **and** on encounter end; killing the server
      mid-line leaves no permanent floating text
- [ ] **A standing bubble is visible and legible from spawn distance** — the §8.1
      lure, and the thing to watch hardest in play
- [ ] Hostile encounters show no standing bubble
- [ ] The same line is not repeated until its pool is exhausted (shuffle bag)
- [ ] Nothing is said while a nearby player is in combat
- [ ] A purchase takes exactly the right physical diamonds from the main 36
      slots, or is refused whole
- [ ] A full inventory refuses the purchase **before** payment is taken
- [ ] The buyer stops buying when `coin` is exhausted and says so
- [ ] A cobble price over 640 logs a warning at config load
- [ ] **No import of `cobbleeconomy` anywhere.** No `suggests`, no `depends`
- [ ] A trader selling a Spirit Stone via `components` delivers a working stone
      with `spiritwolves` installed, and is skipped cleanly without it
- [ ] The patrol parleys before fighting; gold armour changes the greeting
- [ ] The patrol never spawns near world spawn or for a brand-new player
- [ ] The stray donkey's chest returns everything on expiry, including items the
      player put in
- [ ] The player keeps the donkey
- [ ] The recurring trader remembers meetings across a restart; a corrupt `.dat`
      is quarantined and the server still boots
- [ ] Killing an encounter is possible and produces a line, not an exception
- [ ] Restart mid-encounter leaves no orphan; the sweep does not bin a
      just-spawned one
- [ ] A fully vanilla client sees all of it with nothing installed

---

## 17. Out of scope for v1

Fake-player NPCs, any block placement, escorts and followers, quest chains,
reputation, schedules and day cycles, encounters that move between chunks,
player-to-wayfarer bartering of non-currency items, the facts contract (§9.3
ships static), and **any interaction with the other ten mods** beyond selling
their items through a `components` string in a config file.

Three encounters, two classes, one persisted record. Prove that finding
something in the world is worth walking to before building six more of them.
