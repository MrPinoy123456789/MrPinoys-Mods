# MrPinoy's Mods — Full Suite Audit

> **What this is.** A ground-up audit of every mod in the suite: what it is, what
> it does mechanically, what job it does for engagement and retention, how it
> interacts with the rest, and what the shared code patterns and API surfaces
> actually are. Read as: *if a pub is trying to get butts in seats, keep them
> there longer, and eventually get them spending — what does each mod contribute
> to that, and where are the holes?*
>
> **What this is not.** A replacement for `DESIGN.md` (intent and philosophy) or
> for the per-mod `SPEC.md`/`PLAN.md`/`PROGRESS.md` files (implementation detail
> and status). It also does not replace the `MOD_AUDIT.md` that `DESIGN.md` §4
> and §12 reference — that file does not exist in the tree; build conventions
> are captured here in §4 instead.
>
> **Audit date:** 2026-08-11. **Target platform:** Minecraft 26.2, Fabric Loader
> 0.19.3, Fabric API 0.156.0+26.2, JDK 25, all mods `"environment": "server"`.

---

## 0. Executive summary

**Ten mods.** `DESIGN.md` documents seven; three more have landed since
(`chatdonkey`, `cobblebending`, and the Disenchanter — which shipped *inside*
`wondrous` rather than as its own mod, leaving `disenchanter/SPEC.md` as an
orphaned design doc).

**The suite is well-built and badly surfaced.** The engineering standard is
unusually high for a hobby mod set — pure-Java `core` modules under test, no
client mod required anywhere, exactly one Mixin in ten mods, config-as-integration
instead of compile-time coupling. But almost none of that reaches a player who
hasn't been told what exists.

Scored against the "butts in seats, longer sessions, eventually spending"
framing:

| Goal | Current state | Limiting factor |
|---|---|---|
| **Get them in the door** | Weak | Nothing announces anything. No first-session onboarding of any kind. |
| **Keep them there this session** | Strong | quizengine, bounties, chatdonkey, cobblebending, spiritwolves all fire during a session. |
| **Bring them back tomorrow** | Thin | `dailyquests` alone. One riddle, capped at 3 diamonds. |
| **Bring them back this week** | Near-zero | `ballot` is 148 passing tests of unused competition infrastructure. |
| **Keep them across months** | Zero | No seasons, no resets, no collection, no titles, no lineage. |
| **Get them spending** | Moderate and improving | Shop is no longer a pure checklist (spiritwolves charges, cobblebending ammo), but most sink *shapes* are still unbuilt. |

**The three highest-leverage gaps, in order:**

1. **Discoverability** (`DESIGN.md` §7, still unsolved). Six-to-ten systems, no
   shared surface. A new player meets two of them and never learns the rest
   exists. This caps the value of every other mod simultaneously.
2. **The weekly loop.** `ballot` can already run automated build competitions
   with prizes. It is built, tested, and idle.
3. **Attachment has exactly one carrier.** `spiritwolves` proves the mechanic
   works. `chatdonkey`'s donkeys are named but never recur — the single cheapest
   attachment win available (see §2.8).

---

## 1. The suite at a glance

| Mod | Status | Loop served | Faucet / Sink | Attachment | Discoverable? |
|---|---|---|---|---|---|
| **cobbleeconomy** | Shipped, 200 core assertions | Hub | **Drain** | — | via `/shop`, `/baltop` |
| **quizengine** | Shipped, 68 engine tests | Session + daily-ish | Faucet | — | Announces itself in chat ✔ |
| **bounties** | Shipped, 31 core tests | Session | Faucet | — | No (must type `/bounty`) |
| **dailyquests** | Shipped, never compiled per README | Daily | Faucet | Streak | Join announce ✔ |
| **wondrous** | Shipped, 12 items | Sink target | Sink | — | via `/shop` |
| **ballot** | Shipped, 148 tests, **unused** | Weekly | Neutral | — | Physical blocks ✔ |
| **spiritwolves** | Shipped, v1–v4 built | Session + long-term | **Sink** | **Strongest** | via `/shop` |
| **chatdonkey** | Shipped, 353 core tests | Session (interrupt) | Faucet | Latent | **Comes to you** ✔✔ |
| **cobblebending** | Built (spec header stale) | Session | **Sink** | — | via `/shop` |
| **disenchanter** | Spec only — **shipped inside wondrous** | Utility | XP sink | — | via `/shop` |

Legend: "Discoverable" means *a player who was told nothing can still meet it.*
Only three qualify, and one of them (`chatdonkey`) qualifies because it walks up
to you.

---

## 2. Mod-by-mod

### 2.1 cobbleeconomy — the hub

**What it is.** A two-currency economy where both currencies are ordinary vanilla
items: `minecraft:cobblestone` (common) and `minecraft:diamond` (premium).
Banking, offline `/pay`, an SGUI shop, leaderboards, an append-only transaction
log.

**Mechanically.**
- `core/` is Minecraft-free: `EconomyService`, `AccountStore`, `Wallet`,
  `LeaderboardService`, `Shop`. The build enforces the split (an empty
  `build.gradle.kts` means a stray `import net.minecraft.*` fails to compile).
- Balances are **independent** — there is deliberately no exchange rate anywhere
  in the code. Relative value is set by shop prices and by players.
- `shop.json` supports a `components` block that stamps arbitrary
  `DataComponentPatch` data onto the sold stack. **This is the suite's
  integration layer** (§3.1).
- Failure directions are chosen: deposits take items *then* credit (a crash
  shrinks the economy, never inflates it); a bad `accounts.json` refuses to boot;
  a bad `shop.json` yields an empty shop.

**Engagement job.** It is the *drain*. Every other mod's payout only means
something because this exists to spend it. `/baltop` is also the suite's only
persistent status ladder.

**Retention job.** Leaderboards (moderate — permanent, so early leaders are
permanent), and the shop as a goal ladder.

**Weaknesses for the stated goals.**
- **The shop was historically a checklist** (~292 diamonds bought everything
  diamond-priced, after which diamonds were worthless). Partially fixed by
  spiritwolves/cobblebending consumables, but the *catalogue shape* is still
  static: same stock, same prices, forever.
- **No time pressure of any kind.** No rotating stock, no limited quantities, no
  sales, no restock timer. Every one of those is a proven return-visit driver and
  every one of them is a `shop.json` schema addition, not new architecture.
- **No debt ladder, no cosmetic layer, no collective goal.** Three of
  `DESIGN.md` §5's nine sink shapes, all absent, all cheap here.
- `/baltop` never resets, which means the top of the ladder stops being
  contestable for anyone who joins late — a direct disincentive for new players.

**Monetization surfaces (when that day comes).** This is the natural place: it
already has accounts, an audit log, an offline-safe transfer path, and a
component-stamping item vendor. A premium currency layered as a third
`Currency` would require no structural change.

---

### 2.2 quizengine — trivia and Quiplash

**What it is.** Scheduled trivia and Quiplash rounds. `engine/` is a pure-Java
round lifecycle (`Round`, `Phase`, `Engine`, scoring) with 68 tests; `fabric/`
handles scheduling, chat rendering, and JSON persistence.

**Mechanically.** Auto-start on a timer (`timings.json`), phases advance on
schedule or via `/quiz advance`. Quiplash has a submit window (2h default), then
voting, then settle. `AfkTracker` gates participation. Scoring is config.

**Engagement job.** The suite's strongest *session* hook — a scheduled event
means "something is about to happen", which is the single most reliable reason a
player stays logged in for another ten minutes. Roughly 48 auto rounds/day.

**Retention job.** Cadence/FOMO (moderate) and a leaderboard.

**Weaknesses.**
- **Content starvation is the binding constraint, not code.** 26 trivia
  questions against 48 rounds/day repeats within hours; 4 Quiplash prompts is
  effectively none. A repeated question is worse than no question — it teaches
  players the event is not worth stopping for. *This is the cheapest large
  engagement win in the entire suite and it requires zero engineering.*
- Admin commands are console-only (26.2 `PermissionSet` migration incomplete).
- `Orchestrator.greet()` exists for late joiners but is **not wired to a join
  event** — a player logging in mid-round is told nothing.
- Skipped rounds pay nobody, including participation.
- No cross-session persistence of a "season" — the leaderboard is flat forever.

---

### 2.3 bounties — the rotating kill board

**What it is.** A two-slot public board of kill quests, each a mob id + count +
diamond reward. Players hold up to three at once.

**Mechanically.** The board is **derived from wall-clock time, not stored** — a
new bounty every 30 minutes, slots staggered by 15 so announcements never collide
with quizengine's cadence. Restarting reproduces the identical board. Accepted
bounties survive rotation. `core/` (31 tests) names mobs by **id string**, which
is what keeps Minecraft off its classpath.

**Engagement job.** Session-loop direction. Converts "I'm bored, I'll log off"
into "I'll get four more skeletons first." Combines well with spiritwolves
(the wolf does the killing) and cobblebending (the ammo makes it a sink).

**Retention job.** Rotation/FOMO (moderate).

**Weaknesses.**
- **You must already know to type `/bounty`.** Nothing announces a rotation, so
  the FOMO mechanic has no delivery surface. A chat announcement on rotation is
  a handful of lines and would roughly double this mod's felt presence.
- **No escalation.** Every bounty is the same shape at the same difficulty band.
  No chains, no weeklies, no "board boss."
- No completion history, so there is nothing to be proud of and nothing to rank.

---

### 2.4 dailyquests — riddle of the day

**What it is.** One server-wide riddle per day. The riddle describes an item;
turning in the right item *is* the answer. Streaks pay one diamond/day, capped
at 3.

**Mechanically.** Today's quest is **derived from the date**, so a restart can't
change it. Completion is a date comparison rather than a stored flag, so nothing
needs resetting at rollover. `rolloverHourUtc` is config. `announceOnJoin`
surfaces it at login. Single-module (no core split).

**Engagement job.** It *is* the daily loop. Alone.

**Retention job.** **Streaks — loss aversion — is the strongest retention
primitive in the suite after attachment**, and it is badly underused:
- The cap is 3 diamonds. The streak keeps counting past it but pays nothing, so
  the mechanic's teeth (fear of losing a long run) are attached to a reward that
  stopped growing on day 3.
- **There is no streak protection to sell.** `DESIGN.md` §8 already names this.
  A "streak freeze" is the single most-copied monetizable retention item in
  mobile gaming, and the mechanic it protects already exists here.
- Streaks are private. No `/daily top`, no visible streak count anywhere others
  can see. Loss aversion is amplified enormously by an audience.

**Weaknesses.** Also: one riddle/day is a ~30-second daily loop. The README
notes the mod **has never been compiled or run**, with `TurnIn.lookup()` flagged
as the likely first failure.

---

### 2.5 wondrous — the utility item catalogue

**What it is.** Twelve vanilla items stamped with `custom_data` that behave like
custom items. No registry entries, so vanilla clients connect normally.

**The current roster** (README documents 9; the code has 12):

| id | What it does |
|---|---|
| `flying_boots` | Creative-style flight while worn (needs `allow-flight=true`) |
| `pocket_workbench` / `_enderchest` / `_anvil` / `_grindstone` / `_stonecutter` / `_loom` | Pocket stations, opened via `UseItemCallback` |
| `pocket_disenchanter` | **The Disenchanter** — destroys an enchanted item, writes its enchantments onto a book |
| `pocket_smelter` | Melts iron/gold gear back to nuggets, scaled by remaining durability |
| `boomerang_ball` | Thrown; recalls a pet, then returns to you |
| `big_hole_pick` / `big_hole_shovel` | 3×3 breaking, with a negative `BLOCK_BREAK_SPEED` modifier so it's a trade-off |

**Engagement job.** The suite's primary *sink target* — the reason to have money.
Also, quietly, the suite's **quality-of-life layer**, which matters more for
retention than it looks: a pocket anvil removes a friction point that ends
sessions.

**Weaknesses.**
- **One-time purchases.** Every item is bought once and owned forever. This is
  the checklist problem in its purest form. `DESIGN.md` §8 already proposes
  repair/upkeep costs — that single change converts the whole catalogue from a
  checklist into rent.
- **It is the only mod with a real compile-time dependency** (`cobbleeconomy` →
  `wondrous` api module), and it caused the suite's only load-order bug. The
  `components` pattern (§3.1) supersedes it; the README still documents the old
  way as the way to integrate.
- The Disenchanter and Smelter are substantial features buried as items in a
  catalogue nobody browses.

---

### 2.6 ballot — voting, polls, build competitions

**What it is.** Server-side voting with *physical* infrastructure: a lectern
noticeboard, jukebox ballot boxes, and signs for claiming plots. Two kinds, fixed
at creation: **Poll** (you write the options) and **Buildoff** (players claim
plots, build, then vote).

**Mechanically.**
- `core/` is the poll model with **148 passing tests**, zero dependencies (no
  Minecraft, no Gson, no logging).
- One file per poll in `config/ballot/polls/`, **filename is the id**, no index
  file (an index is a thing that can disagree with the folder).
- Each file holds current state **and** full event history — because when the
  prize is a permanent town monument, someone will dispute the result and "who
  flipped on the last day" is worth answering.
- **The suite's only Mixin**, `BlockItemMixin`, because Fabric has no
  server-side block-placement event (open request since 2020). Two injections:
  read the poll key at HEAD (the stack is consumed by RETURN), decide success at
  RETURN.

**Engagement job.** *Should* be the weekly loop and the communal-goal sink.

**Retention job (potential, unrealized).** Build competitions are the strongest
social retention format available to a Minecraft server: they create a deadline,
a reason for spectators, a reason to return to see results, and a permanent
artifact on the map that reminds everyone the event happened.

**Weaknesses.** **It is idle.** Every mechanic exists; there is no automation, no
schedule, no prize wiring, and no announcement. `DESIGN.md` calls automated
weekly build competitions "the single highest-value unbuilt thing in the suite"
and that is still true. Everything needed is a scheduler and a payout call.

---

### 2.7 spiritwolves — the attachment engine

**What it is.** A **Spirit Stone** (an echo shard with `custom_data`) binds a
tamed wolf. Lethal damage doesn't kill the wolf — it pulls it into the stone and
burns a charge. Charges are restored at a vanilla anvil with diamonds.

**Why it's technically elegant.** `max_damage = 4` makes vanilla's anvil math
(`maxDamage / 4`) resolve to exactly 1 diamond = 1 charge. **The entire repair
loop is vanilla — no event hook, no Mixin, no code.** The XP level cost applies
on top as a free second sink.

**How it evolved (v1 → v4):**

| Version | Change |
|---|---|
| v1 | Stone-bound wolf, full-NBT capture, death-save, anvil repair |
| v2 | Living journal (lore accumulates history), senses (growl on danger, mark prey), fetch, per-outing killstreak driving scale + attack |
| v3 | **Inverted the binding** — wolf now bound to the *player*, stored in a server-side `PlayerWolfRegistry` (`.dat` per player). Stone becomes a replaceable remote. One wolf per player, free. Release is a real, destructive decision. |
| v4 | **Soul-forged progression** — kills grant souls, souls grant levels, levels grant verb slots. Six equippable combat verbs (Emberfang, Venomfang, Ravenous, Bonechill, Witherbite, Blinkstrike) unlocked by *deeds* and tiered by *diamonds*. Plus Scavenger and an assist-window kill-attribution system. |

**Engagement job.** Session presence (a companion at your side), and the suite's
only genuinely long-term progression system.

**Retention job — this is the important one.** `DESIGN.md` §6 names attachment as
the strongest available mechanic, and spiritwolves is its only carrier. The
design is unusually sophisticated about it:
- The charges are a **safety net, not rent** — the mod deliberately removes the
  heartbreak of a dead pet and *sells the relief*. That is what makes attachment
  safe to form.
- The **journal** turns the item's tooltip into an autobiography. "Cheated death
  7 times. Walked back from the void." That is a retention hook made of text.
- **Release destroys everything**, which is what makes an old wolf precious.
- The killstreak is deliberately **per-outing and non-bankable**, with recall
  locks, precisely so the buff never punishes you for using the thing it buffs.

**Weaknesses.**
- **Diamond sinks here are excellent but gated behind depth** — verb attunement
  (4 and 16 diamonds) only matters to a player already invested.
- v2/v3/v4 are marked built but **large parts are not play-verified**. Balance
  (killstreak + verbs stacking) is explicitly untested.
- One wolf per player caps the collection instinct entirely — deliberate, and
  arguably correct, but it means there is nothing to *collect*.

---

### 2.8 chatdonkey — the interrupt

**What it is.** A RuneScape-style random event. A named, immortal, extremely
annoying donkey spawns near an active player, lectures them in chat for up to a
minute in **animalese** (pitched blips, one per syllable, seeded from its name),
leaves a sarcastic gift, and vanishes.

**Mechanically — and this is the most mechanically interesting mod in the suite:**
- **353 core tests.** `core/` holds the behavior state machines, the trigger
  rules, the gift table, the animalese arithmetic, and `ReadOrCreate` — all
  Minecraft-free.
- **Ten events**, five of which are pure config. Any `events.json` entry with a
  `wants` field *is* a demand event; no code. A `duplicates` + `multiplier` entry
  is a duplicator (the Magician doubles raw resources, diamonds included).
- Four player verbs: **wait**, **bribe** (a diamond — a real sink), **give** it
  what it wants (premium version pays a diamond), **groom** it (the Burrs event
  opens the donkey's own chest inventory as a real container).
- **Gifts are nonsense enchantments** — the enchantment is written straight into
  the component, bypassing applicability. A Bowl of Bane of Arthropods. Tier
  controls *absurdity*, not power.
- Better endings **stack** rather than replace.
- **You cannot outrun it.** Past 24 blocks it stops walking and simply turns up
  beside you, silently.
- `donkeyCanKill` (default true) — the donkey will hold your screen mid-fight and
  does not care what is walking up behind you. This is deliberate.
- **The command tree is deliberately the whole API**, designed so a Twitch bridge
  can drive it (`trigger`, `extend` — which returns seconds *actually* granted so
  a bridge can refund, `say` — which sanitises hostile input, `grace`/`ungrace`).

**Engagement job.** **It is the only mod that comes to the player.** Everything
else waits to be discovered; the donkey introduces itself. As a discoverability
vehicle it is already 90% built and pointed at the wrong target — it currently
delivers a joke, and could equally deliver *"there's a poll open"* or *"trivia in
twelve minutes."*

**Retention job — latent and large.**
- Donkeys are **named** (Duncan, Señor Burro, Clopsworth) and each has a distinct
  voice seeded from that name. But **names are per-event random, so no donkey ever
  recurs.** `PROGRESS.md` flags this explicitly as the open question.
  **Sticky-per-player donkeys would give the suite a second attachment carrier
  for approximately no engineering** — the voices are already per-name, the
  character is already written, the only change is persisting which name a
  player's donkey uses.
- The Twitch surface is *the* audience-growth mechanic in this suite. Chat paying
  to interrupt the streamer, or paying for the streamer's grace period, is a
  proven format and the seams are already cut.

**Weaknesses.** Nothing persists — a restart forgets every cooldown and running
event. Play-verification is thin for everything entity-facing.

---

### 2.9 cobblebending — the cobblestone sink

**What it is.** Two "Focus" items (Hurl and Wall) that spend cobblestone from
your inventory as ammunition. Hold to charge, release to fire; charge length
picks the tier.

**Why it's technically elegant.** Hold-and-release on a *vanilla client with
nothing installed* is achieved by stamping the `minecraft:consumable` component
onto a plain item with `consumeSeconds = 3600.0F` — vanilla's own
"approximately infinite" duration. The client enters the using pose and sends the
release packet; the server reads `getTicksUsingItem()`. **No Mixin.** The cooldown
HUD is the vanilla `USE_COOLDOWN` sweep, free.

- **Hurl**: three boulder tiers via `Display$BlockDisplay` moved manually with a
  per-tick raycast — deliberately *not* `FallingBlockEntity`, which would place a
  block on landing and violate the mod's core rule.
- **Wall**: per-column ground scanning so a wall hugs a slope instead of floating;
  one wall per player; recall refunds ⅔; blocks revert to their *prior state*, not
  to air.
- **Bridge**: a sustained channel that lays cobble under your feet; blocks decay
  behind you but the timer refreshes while anyone stands on them.
- Anti-grief is treated as a shipping requirement, not a nicety: only replaceable
  blocks, tracked ledger, per-player cap, no-bend radius around spawn, full revert
  on `SERVER_STOPPING`, PvP off by default.

**Engagement job.** A *power fantasy* — the category the suite otherwise
completely lacks. Everything else is quests, quizzes, and pets; this is the only
thing that makes a player feel strong.

**Retention job.** Skill expression. The two charge-tier chimes are explicitly
load-bearing: they are how a player learns the timing windows by ear, which turns
the item into something you get *better at*. Mastery is a retention mechanic the
suite has nowhere else.

**Weaknesses.**
- **`SPEC.md`'s status header says "not yet implemented"** while
  `src/main/java/cobblebending/` contains all twelve specced classes and a jar
  sits in `dist/`. The doc is stale; treat the status line as unreliable.
- It is a *cobblestone* sink, and cobblestone is the abundant currency. The spec
  names the follow-up itself: focus durability with anvil repair in diamonds,
  reusing spiritwolves' verified mechanic wholesale, converting this into a
  diamond sink too.
- No progression, no mastery ladder, no second discipline (deliberate for v1).

---

### 2.10 disenchanter — spec only, shipped elsewhere

`disenchanter/SPEC.md` is a complete, careful design for a standalone
grindstone-screen mod that destroys an enchanted item and saves its enchantments
onto a book ("you trade the body for the soul").

**It was not built as a mod.** `wondrous/DisenchantMenu.java` implements exactly
this design as the `pocket_disenchanter` item, and `SmelterMenu.java` is a second
`MenuType.GRINDSTONE` screen built on the same shape for recycling metal gear.

**Consequence.** The standalone spec is now a stale document that a future agent
will read as an unbuilt backlog item. It should either be deleted, or given a
one-line status header pointing at `wondrous`. Its `§8 Verify before writing
code` list is still valuable as a record of what was checked.

**Engagement note.** The Disenchanter is a *quality* sink — it consumes XP and
destroys gear, and it makes junk loot meaningful. That is a strong anti-boredom
mechanic (every mob drop becomes potentially interesting) hidden inside a
catalogue item.

---

## 3. How the mods interact

### 3.1 The one rule, and how it holds

`DESIGN.md` §2: **currency is the only coupling.** A mod may pay in diamonds or
cobblestone and may charge in them. It may not know the name of any other mod.

The arrows are *items*, not API calls:

```
  quizengine ──┐
  bounties   ──┤
  dailyquests──┼──►  diamonds / cobblestone  ──►  cobbleeconomy  ──►  shop  ──►  anything
  chatdonkey ──┤          (plain items)              (bank)
  spiritwolves─┘  (also a sink)
                                                       │
  wondrous     ◄─────────────────────────────────────  │  (compile-time dep — the exception)
  spiritwolves ◄── components block ───────────────────┤
  cobblebending◄── components block ───────────────────┘
```

**Direct couplings — there is exactly one.**

`cobbleeconomy` → `wondrous`, via the `wondrous` api module, because a wondrous
item is not a registry entry and `shop.json` needs `wondrous:flying_boots` to
resolve to a real stack. It is a real compile-time dependency with a
`publishToMavenLocal` step and a `suggests` in `fabric.mod.json`. It caused the
suite's only load-order bug (the shop loaded before `wondrous` initialised,
silently dropped every `wondrous:` entry, then *rewrote `shop.json` without
them*). Fixed by deferring catalog load to `SERVER_STARTED`; the coupling remains.

**The pattern that replaced it — `components`.** `spiritwolves` had the identical
problem (a Spirit Stone is an echo shard plus `custom_data`). Instead of a second
api module, shop listings gained a `components` block parsed by `ItemComponents`
into a real `DataComponentPatch` via `DataComponentPatch.CODEC` and `RegistryOps`:

```json
"spirit_stone": {
  "item": "minecraft:echo_shard", "quantity": 1, "price": 24,
  "currency": "diamond", "category": "Wondrous",
  "components": {
    "minecraft:custom_data": { "spiritwolves": { "bound": false } },
    "minecraft:item_name": "Spirit Stone"
  }
}
```

**The contract between two mods is now a string in a JSON file.** `cobblebending`
sells its Focuses the same way. This is the preferred pattern and `wondrous` is
explicitly not to be copied.

> **One open question worth resolving.** `cobblebending`'s Focus needs the
> `minecraft:consumable` component to *function*. The spec flags it: confirm the
> `components` parser can express a `consumable` block. If not, the fallback is
> for `cobblebending` to stamp it onto any correctly-marked stack it sees — which
> is arguably better anyway, since it self-heals Focuses sold by older versions.

### 3.2 Indirect interactions — where the value actually is

These are the interactions nobody wrote code for, and they are the suite's real
emergent design:

| A | B | Interaction |
|---|---|---|
| **bounties** | **spiritwolves** | The wolf does the killing. Bounty progress and soul progression advance together. Verb families (spiders, blazes, skeletons) map directly onto bounty targets. |
| **bounties** | **cobblebending** | Hurl is a combat tool; bounties are the reason to fight. Bounty diamonds pay for the cobble you burned. |
| **spiritwolves** | **cobbleeconomy** | Anvil charges are the most durable diamond sink in the suite — they scale with how recklessly you play and never complete. |
| **chatdonkey** | **wondrous** | Nonsense-enchanted gifts are junk *until* you own the pocket disenchanter, at which point they're free enchantment books. The joke becomes an economy. Neither mod knows the other exists. |
| **chatdonkey** | **everything** | The donkey interrupts you during anything. It is the only source of cross-cutting chaos. |
| **cobblebending** | **cobbleeconomy** | Cobble stops being worthless. The common currency finally has a consumption path. |
| **quizengine** / **bounties** | each other | Cadence is deliberately staggered (bounties at :15/:45, quiz on its own timer) so announcements never collide. **This is the only cross-mod coordination in the suite, and it's done by picking non-colliding constants.** |
| **dailyquests** | **cobbleeconomy** | Streak diamonds are the daily trickle into the bank. |
| **ballot** | **cobbleeconomy** | *Should* be a prize pipeline. Currently isn't wired at all. |

### 3.3 What the decoupling costs

Stated plainly, because it's the tension at the heart of every improvement below:

1. **`giveOrDrop` is duplicated in four+ mods.** Deliberate and cheap.
2. **`Chime.java` is duplicated in seven mods.** Also deliberate.
3. **No cross-mod features.** A bounty cannot grant a wondrous item.
4. **Currency round-trips through the inventory** rather than crediting a
   balance, because paying out means handing over real items.
5. **Nothing can announce anything else.** *This is the expensive one.* §7 of
   `DESIGN.md` names discoverability as the price the design pays, and it has to
   be solved deliberately because it will never emerge for free.

---

## 4. Code structures and patterns

### 4.1 The core/fabric split

Six mods use it (`cobbleeconomy`, `quizengine`, `ballot`, `bounties`,
`chatdonkey`, and `wondrous` via its api module). Four are single-module
(`dailyquests`, `spiritwolves`, `cobblebending`, and `disenchanter` as specced).

**The split is enforced by the build, not by discipline.** The `core` module's
`build.gradle.kts` is deliberately empty, so a stray `import net.minecraft.*`
fails compilation rather than quietly coupling the two.

**The test payoff is real:**

| Mod | Core tests |
|---|---|
| chatdonkey | 353 |
| cobbleeconomy | ~200 assertions |
| ballot | 148 |
| quizengine | 68 |
| bounties | 31 |

All run in about a second with **no game, no network, and no test framework** —
each is a `main()` that prints `N passed, 0 failed`.

**The trick that makes it possible:** `core` refers to Minecraft things as
**strings**. `bounties` names mobs by id; `chatdonkey`'s `Demand` names items by
id. The fabric layer resolves them. This is why the round lifecycle, the poll
model, the money rules, and the donkey's state machines are all testable.

**When single-module is correct:** when almost everything is Minecraft-facing.
`spiritwolves`' SPEC says it directly — a pure-Java module there would hold
nothing worth testing.

### 4.2 The seven repeated patterns

**1. `readOrCreate` config.** Three rules, ordered by damage:
- Missing → write defaults, log, return defaults.
- **Fails to parse → return defaults but NEVER write.** An operator's
  broken-but-recoverable edit is worth more than a tidy file.
- Parses → return it.

`chatdonkey/core/ReadOrCreate.java` is the canonical implementation, written to
be testable — the caller supplies parse and serialise as lambdas, which keeps
Gson out of `core`.

**2. `giveOrDrop`.** Add to inventory, drop at the player's feet if full.
**Never destroy a reward.** Duplicated deliberately across mods.

**3. Atomic debounced writes.** Write to `<name>.tmp`, then rename. Flush on a
tick counter (15s in cobbleeconomy, 30 ticks in spiritwolves' registry) and again
synchronously on `SERVER_STOPPING`. A crash costs seconds, never the file.

**4. `Chime.java`.** A `ClientboundSoundPacket` sent straight down one player's
connection, `SoundSource.RECORDS`, note-block sounds, volume 0.15–0.4. Present in
seven mods, each a private-constructor final class with static methods.
**These are confirmations, not fanfares.**

**5. Static final utility classes.** Nearly every fabric-side module is
`final class X { private X() {} static ... }`. No DI, no service locator, no
singletons-with-state beyond a few transient maps. `spiritwolves`' SPEC states it
as house style: "static, like every other module in the suite."

**6. Rejections are return values, not exceptions.** `ballot`'s README says it
best: a player clicking a ballot box they already used is completely ordinary,
and the message they see is the point. `TxResult`/`TxStatus`, `AcceptResult`,
`ProgressResult`, `Outcome`, `TriggerDecision` are all this shape.

**7. Chat-as-UI via `Component` click/hover events.** Clickable buttons run
hidden subcommands. `ballot` parks every button target under one opaque
`/ballot _ ...` node because Brigadier suggests whatever is runnable.
`spiritwolves`' verb panel is a full talent-tree UI made of hover text.
**Every mutating subcommand ends by re-printing the panel — the panel *is* the
UI.**

### 4.3 Failure directions are chosen, not discovered

This is the most transferable idea in the codebase. Every persistence decision
names which way it should fail:

| Situation | Chosen failure |
|---|---|
| Crash mid-deposit | Player loses items. (The reverse creates items — only one of those is farmable.) |
| Debounced write lost | Economy gets *smaller*. An economy that fails toward less currency can't be farmed. |
| `accounts.json` unreadable | **Server refuses to start.** Booting empty would look fine until the next flush wiped every balance. |
| `shop.json` unreadable | Empty shop. Nobody's money is at stake. |
| Config unparseable | Defaults in memory, **file untouched**. |
| Item won't fit | Drops at your feet, logged. Never deleted. |
| Corrupt wolf record | Renamed `.dat.corrupt`, skipped, server still boots. |
| Orphaned donkey after a crash | Reverts to an ordinary killable vanilla donkey. |
| Sanitised chat line unusable | Returns `""` → a duller donkey, never a dropped guard. |

### 4.4 Anti-exploit stance

The suite's economic rule is **structural, not economic** (`DESIGN.md` §5,
rewritten after play): don't fear big payouts, fear payouts that scale with a
*machine* rather than with playing.

Concretely:
- **The Magician duplicates raw materials only** — diamonds and emeralds
  included. Nine ingots make a block, so a dupeable *crafted* item turns every
  crafting recipe into a multiplier. There is a test asserting nothing on the
  allowlist ends in `_block`, `_helmet`, `_sword`, `_pickaxe`, `_chestplate`,
  and further tests asserting diamonds/emeralds *are* present so nobody removes
  them out of habit.
- **Intake capped at 16/click.** The payout is a multiple of the input, so an
  uncapped intake makes one right-click worth a shulker box.
- **Wallet.planRemoval returns `null` rather than a short plan**, so there is no
  partial removal for a careless caller to apply.
- **Only the main 36 inventory slots are read**, everywhere. Armour and offhand
  indices have moved between versions; a mod that trusts `getContainerSize()`
  across two versions eventually inserts cobblestone into a helmet slot. Cost: a
  stack in your offhand isn't seen by `/bank all`. The other failure is a dupe.
- **`ItemStack.is(Item)` no longer exists in 26.2** — comparison is
  `stack.getItem() == Items.DIAMOND`.
- **Chat input from outside is treated as hostile**: `§` and its code letter
  stripped *together*, control chars → spaces, whitespace collapsed, length
  capped at 120. Left in, a viewer could forge a second `<Duncan>` prefix.

### 4.5 Build conventions (the `MOD_AUDIT.md` content that has no home)

- Minecraft **26.2**, Fabric Loader **0.19.3**, Fabric API **0.156.0+26.2**,
  **JDK 25** (26.2 targets Java 25, not 21).
- **26.2 ships unobfuscated.** No Yarn, no mappings dependency. Mojang official
  names throughout (`ServerPlayer`, `Component`, `Commands`).
- `Identifier`, **not** `ResourceLocation` (renamed in 26.1). Every tutorial
  written before 26.1 uses the old name.
- Loom `1.17-SNAPSHOT`, applied in `settings.gradle.kts`, **not** in the
  `build.gradle.kts` `plugins {}` block — a Kotlin DSL `plugins {}` block is a
  restricted scope compiled before the project exists, so `property()` cannot
  resolve there. The official example mod gets away with it only because it's
  Groovy.
- `archivesName = "MrPinoys_<mod>"`, a `dist` Copy task into the shared `dist/`,
  `build` `finalizedBy("dist")`.

> **⚠ The bug every mod in this suite hit — seven times and counting.** The
> `dist` task must depend on **`jar`**, *not* `remapJar`. Loom does not register
> a `remapJar` task because 26.2 ships unobfuscated, so depending on it makes
> `./gradlew build` fail outright.

- `sgui` is `eu.pb4:sgui:2.1.0+26.2` from `https://maven.nucleoid.xyz`,
  `implementation` + `include` (shaded).
- Plain `compileOnly` for the wondrous api, **not** `modCompileOnly` — the `mod*`
  Loom variants remap for obfuscated Minecraft, which 26.x doesn't ship.

### 4.6 Minecraft / Fabric API surface actually used

**Events (Fabric API).**

| Purpose | Event | Module |
|---|---|---|
| Right-click entity | `UseEntityCallback` | events-interaction-**v0** |
| Right-click item | `UseItemCallback` | events-interaction-**v0** |
| Right-click block | `UseBlockCallback` | events-interaction-**v0** |
| Block break (area tools) | `AttackBlockCallback`, `PlayerBlockBreakEvents` | events-interaction-v0 |
| Cancel a death | `ServerLivingEntityEvents.ALLOW_DEATH` | entity-events-v1 |
| Cancel damage | `ServerLivingEntityEvents.ALLOW_DAMAGE` | entity-events-v1 |
| Kill attribution | `ServerLivingEntityEvents.AFTER_DEATH` | entity-events-v1 |
| Per-tick polling | `ServerTickEvents.END_SERVER_TICK` | lifecycle-events-v1 |
| Boot / shutdown | `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` | lifecycle-events-v1 |
| Orphan sweep | `ServerEntityEvents.ENTITY_LOAD` | **lifecycle** package, not `entity.event` |

> Note the interaction module is **v0**, not v1. This has bitten twice.

**The one Mixin in ten mods:** `ballot`'s `BlockItemMixin`, because Fabric has no
server-side block-placement event. Requests open since 2020; the ones that landed
are client-side. Suite rule: **no Mixins unless there is genuinely no Fabric
event.**

**The packless item trick — the suite's foundational technique.** Custom items
are *never* registry entries. They are vanilla items carrying
`minecraft:custom_data`:

```java
CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> { ... });
stack.get(DataComponents.CUSTOM_DATA).copyTag();
```

Nothing new appears in a synced registry, so **vanilla clients connect with
nothing installed.** Behaviour lives in event callbacks, not `Item` subclasses.

**Three component tricks worth naming, because each one removed a Mixin:**

1. **Anvil repair for free** (`spiritwolves`). `max_damage = 4` + a `REPAIRABLE`
   component holding `HolderSet.direct(Items.DIAMOND...)` makes vanilla's
   `AnvilMenu.createResult()` math (`getMaxDamage() / 4`) resolve to exactly
   1 diamond = 1 charge. Zero code. (Gotcha: `Repairable` lives in
   `net.minecraft.world.item.enchantment`, not `world.item.component`.)
2. **Hold-and-release for free** (`cobblebending`). `CONSUMABLE` with
   `consumeSeconds = 3600.0F` is vanilla's own `APPROXIMATELY_INFINITE_USE_DURATION`
   (72000 ticks). The use never self-completes, the item is never consumed, and
   the particle threshold sits at ~13 minutes so nothing ever emits. Read the
   charge with `getTicksUsingItem()`.
3. **A cooldown HUD for free** (`cobblebending`). `DataComponents.USE_COOLDOWN`
   makes the vanilla client render the sweep. That is the whole HUD.

**Entity NBT round-trip** (`spiritwolves`, reused by `cobblebending` for
`BlockDisplay`):

```java
TagValueOutput out = TagValueOutput.createWithContext(reporter, level.registryAccess());
wolf.saveWithoutId(out);                  // capture, includes UUID
ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
entity.load(in);                          // restore
```

Full-entity capture over a curated record — **more faithful and less code**, and
it preserves armour, age, anger state, and attributes for free.

**26.2 API changes that will bite anyone working from older docs:**

| Old | 26.2 |
|---|---|
| `ResourceLocation` | `Identifier` |
| `hasPermission(int)` | `Commands.hasPermission(PermissionCheck)`, levels as constants on `Commands` (`LEVEL_GAMEMASTERS` = old op 2) |
| `ItemStack.is(Item)` | gone — use `stack.getItem() == Items.X` |
| `EntityType.DONKEY` | `net.minecraft.world.entity.EntityTypes.DONKEY` |
| `...animal.horse.Donkey` | `...animal.equine.Donkey` (whole package renamed) |
| `Entity.getTags()` | `Entity.entityTags()` |
| `Wolf.setCollarColor()` / `getVariant()` | entity data components (`WOLF_VARIANT`, `WOLF_COLLAR`, `WOLF_SOUND_VARIANT`) |
| `...animal.Wolf` | `...animal.wolf.Wolf` |
| `TypedActionResult` | `InteractionResult` |
| `BlockState.blocksMotion()` | deprecated — use `isFaceSturdy(...)` / `getCollisionShape(...).isEmpty()` |
| `SoundEvents.*` uniform type | **two types**: note blocks are `Holder.Reference<SoundEvent>`, `DONKEY_AMBIENT` is a bare `SoundEvent` |

**Server-side GUIs.** `sgui` for chest-like menus (`cobbleeconomy`'s shop,
`spiritwolves`' verb panel). Vanilla `AbstractContainerMenu` subclasses over
`MenuType.GRINDSTONE` for the Disenchanter and Smelter — the client renders a
grindstone, the server applies entirely different rules, and `mayPickup` on the
output slot does the real gatekeeping rather than a click handler.
`chatdonkey`'s Burrs event avoids GUI code entirely by using the donkey's own
chest inventory: `setChest(true)` + `setTamed(true)` (required — it checks
`isTamed()`) + `openCustomInventoryScreen(player)`, with `getSlot(500 + i)` as
the public route to the protected `inventory` field.

---

## 5. Engagement and retention — the honest assessment

### 5.1 The three loops

**Session loop — "what do I do right now."** *Healthy.* bounties, quizengine,
spiritwolves, chatdonkey, cobblebending. This is the strongest layer by a
distance, and it got stronger with the last three mods.

**Daily loop — "why log in today."** *Thin.* One mod. One riddle. ~30 seconds of
content, capped at 3 diamonds. A rotating daily shop stock keyed off the same
rollover would double this layer for very little code — it hits the daily loop
*and* adds a sink shape simultaneously.

**Weekly loop — "why come back this week."** *Near-zero.* bounties board variety
is incidental. `ballot` is fully built and idle.

**Seasonal loop — "why care over months."** *Nothing.* A seasonal leaderboard
reset with a ballot-voted prize would give the whole suite an arc instead of a
flat grind.

### 5.2 Retention mechanics, scored

| Mechanic | Where | Strength | The gap |
|---|---|---|---|
| **Attachment** | spiritwolves | **Strongest** | Only one carrier. chatdonkey is a free second one. |
| **Streaks (loss aversion)** | dailyquests | Strong, crippled | Cap 3, private, nothing to sell as protection. |
| **Mastery / skill** | cobblebending | New, real | No ladder, no visibility, no second discipline. |
| **Progression** | spiritwolves v4 | Deep | Gated behind already being invested. |
| **Rotation / FOMO** | bounties, quizengine | Moderate | bounties never announces its rotation. |
| **Leaderboards** | cobbleeconomy, quizengine | Moderate | Never reset. Early leaders are permanent. |
| **Communal goals** | ballot | Latent | Not wired. |
| **Collection** | — | **Absent** | Nothing to collect anywhere in ten mods. |
| **Interruption / novelty** | chatdonkey | Strong | Nothing persists across restarts. |
| **Social / audience** | ballot, chatdonkey Twitch seams | Latent | Both unbuilt at the surface layer. |

### 5.3 Sink shapes (`DESIGN.md` §5's nine), updated

| Shape | Canonical example | Status now |
|---|---|---|
| Consumed by playing | RuneScape runes | **spiritwolves ✓, cobblebending ✓** |
| Destruction / risk | EVE ship loss | **spiritwolves ✓** |
| Voluntary debt ladder | Animal Crossing loans | ✗ |
| Time-limited stock | Nook's Cranny, Habbo rares | ✗ |
| Randomized crafting | Path of Exile | ✗ (chatdonkey's nonsense enchants are the *joke* version) |
| Cosmetic appearance layer | WoW transmog | ✗ |
| Collective goal | Stardew Community Center | partial (ballot, unwired) |
| Rate-gate + pay to exceed | Genshin resin | ✗ (dailyquests' streak cap is the shape, unpriced) |
| Player-made content from bought tools | Habbo wired furni | ✗ (**ballot buildoffs are 80% of this**) |

Four of nine shapes are still entirely absent, and two of the absent ones
(time-limited stock, cosmetic layer) are the cheapest to build in the existing
`shop.json`.

### 5.4 The unsolved problem, restated

**Six-to-ten systems, no shared surface where a player learns any of them
exists.** A new player joins and has no way to know that trivia starts in twelve
minutes, that the bounty board rotates at :15 and :45, that a poll is open, that
today's riddle is unanswered, that Spirit Stones exist, or that they can bend
cobblestone.

`DESIGN.md` §7 lists three options: the shop as catalogue (cheap, covers only
things for sale), an SGUI guide book as its own mod (the recommended answer), and
a `/whatson` command (least compatible with §2).

**A fourth option the audit surfaces: the donkey already comes to you.** It has
an animalese voice, ten behaviours, a config-driven line system, a sanitised
external `say` command, and it targets *active* players specifically. A donkey
that occasionally mentions what's happening on the server is a discoverability
system with a personality, and it needs no new architecture — only lines and a
way to feed it facts (which, per §2, would have to be a config file or a command
from outside, not an import).

---

## 6. Risks and stale documentation

Things a future agent will get wrong if nobody writes them down:

1. **`cobblebending/SPEC.md` says "not yet implemented."** It is implemented —
   all twelve classes exist and a jar is in `dist/`. Fix the header.
2. **`disenchanter/SPEC.md` describes a mod that shipped inside `wondrous`.**
   Add a status pointer or delete it.
3. **`DESIGN.md` §1's table lists seven mods.** There are ten.
4. **`MOD_AUDIT.md` is referenced by `DESIGN.md` and does not exist.** Build
   conventions live in §4.5 above and in `spiritwolves`/`cobblebending` SPECs.
5. **`wondrous/README.md` documents 9 items; the code has 12** (disenchanter,
   smelter, boomerang ball missing).
6. **`wondrous/README.md` still teaches the api-module integration pattern**,
   which `DESIGN.md` §3 says explicitly should not be copied.
7. **`dailyquests` has never been compiled or run** per its own README, with
   `TurnIn.lookup()` flagged as the likely first break. It is carrying the entire
   daily loop.
8. **Play-verification is thin across the newest work.** spiritwolves v2/v3/v4
   and most of chatdonkey past M1 are built and tested but not exercised on a
   live server with players. Balance numbers (killstreak +2.0 at streak 15, verb
   tier stacking, hurl damage) are all first guesses.
9. **quizengine content is the binding constraint** on the suite's best session
   hook, and it is a data problem, not a code problem.

---

## 7. What the suite is missing, ranked by leverage

Ordered by *value per unit of work*, not by size.

**Tier 1 — multiplies everything else**
1. **Discoverability surface** (guide book, or the donkey as herald, or both).
   Every other item on this list is worth more once this exists.
2. **Announce what already happens.** bounty rotations, poll openings, quiz
   countdowns, unanswered riddles. Chat lines against existing state.

**Tier 2 — fills an empty loop**
3. **Automated `ballot` build competitions with currency prizes.** Fixes the
   weakest layer using fully-built, fully-tested infrastructure.
4. **Rotating daily shop stock, limited quantity.** Hits the daily loop *and*
   adds the time-limited-stock sink shape in one change.
5. **Seasonal leaderboard reset with a voted prize.** Creates the missing
   seasonal arc and makes the ladder contestable again.

**Tier 3 — deepens an existing strength**
6. **Sticky-per-player donkey names.** A second attachment carrier for almost no
   engineering.
7. **Streak protection sold in the shop**, plus raising/visualising the cap.
   Monetises the strongest existing retention hook.
8. **Upkeep/repair on `wondrous` items.** Converts a checklist into rent.
9. **Focus durability with anvil repair in diamonds** (`cobblebending`), reusing
   spiritwolves' verified mechanic.

**Tier 4 — new shapes**
10. Cosmetic layer (titles, ranks, appearance). The safest infinite sink.
11. Collection mechanic of any kind — the suite has none.
12. Plot expansion tiers (the Nook ladder, infinite ceiling).
13. Twitch bridge — the only *audience acquisition* mechanic in the design.

**Content, not code** — and cheapest of all:
14. **quizengine trivia pool** (26 questions vs 48 rounds/day).
15. **quizengine Quiplash prompts** (currently 4).

---

## 8. Principles worth preserving

Whatever gets built next should not break these — they are what make the suite
maintainable by one person with an agent:

1. **Currency is the only coupling.** Everything else is a mod's own business.
2. **Config is the integration layer.** If two mods must agree, they agree
   through JSON, not a Java import.
3. **Vanilla clients install nothing.** Non-negotiable, and it is what makes the
   packless component tricks (§4.6) worth their cleverness.
4. **Sinks must never complete.** A checklist is not an economy.
5. **Be generous.** Play showed sinks out-running faucets. Judge a payout by how
   often a real player can reach it, not how big it is. Refuse only what scales
   with a machine instead of with playing.
6. **Failure directions are chosen deliberately.**
7. **Attachment beats payout.** The wolf a player names is worth more than the
   diamonds they earn.
8. **The `core` module is where the thinking goes.** Anything testable without
   Minecraft belongs there, and the empty `build.gradle.kts` is what enforces it.
