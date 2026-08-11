# MrPinoy's Mods — Suite Design Philosophy

> **What this is.** The top-level design document for the whole mod suite: why the
> mods are built the way they are, the one rule that binds them together, and how
> the suite is meant to keep players coming back.
>
> **What this is not.** A status report. Per-mod status, checkpoints, and known
> issues live in `MOD_AUDIT.md`. This document is about *intent*, and it should
> outlive any particular mod's implementation.

---

## 1. The suite

| Mod | What it is | Primary role |
|---|---|---|
| **cobbleeconomy** | Two-currency economy, bank, shop, leaderboard | **The hub** |
| **quizengine** | Trivia + Quiplash rounds | Faucet, session loop |
| **bounties** | Rotating kill-quest board | Faucet, session loop |
| **dailyquests** | Riddle of the day, streaks | Faucet, daily loop |
| **wondrous** | 9 custom utility items | Sink target |
| **ballot** | Voting, polls, build competitions | Weekly loop |
| **spiritwolves** | Wolves bound to a Spirit Stone | Sink, attachment |

---

## 2. The binding philosophy: currency is the only coupling

**Every mod is completely decoupled from every other mod. The only thing they
share is the currency.**

Diamonds are the premium currency and cobblestone is the common one. Any mod may
*pay out* in them and any mod may *charge* in them, but no mod ever needs to know
another mod exists. `cobbleeconomy` sits in the middle purely because it is the
thing that can **store** currency and **spend** it in a shop.

```
   quizengine ──┐
   bounties  ───┤
   dailyquests ─┼──▶  diamonds / cobblestone  ──▶ cobbleeconomy ──▶ shop ──▶ anything
   (mod #8)  ───┘         (plain items)              (bank)
```

The arrows are *items*, not API calls. A quiz round hands a player diamonds the
same way a chest does. `cobbleeconomy` banks whatever the player brings it. There
is no registration, no service lookup, no event bus, and no load order to get
wrong.

### Why this is the right trade

**What it buys:**

- Any mod can be added or removed from the server without touching another.
- No load-order bugs. (The one time this suite *did* couple two mods, it produced
  exactly that bug — see §3.)
- Each mod is independently testable and independently shippable.
- A new mod is a weekend, not a negotiation with five existing codebases.
- The currency is the interface, and it is one every player already understands.

**What it costs:**

- `giveOrDrop` is duplicated in four mods. Deliberate. Four copies of eight lines
  is cheaper than a shared library that every mod must version against.
- No cross-mod features. A bounty cannot grant a wondrous item directly. This is
  a *feature* of the design, not a limitation to be worked around: if a reward
  needs to be something other than currency, that is a signal it belongs inside
  the mod that owns it.
- Currency is physical items, so payouts round-trip through the inventory rather
  than crediting a balance. Accepted cost of not depending on `cobbleeconomy`.

### The rule, stated plainly

> **A mod may pay in diamonds or cobblestone, and may charge in diamonds or
> cobblestone. It may not know the name of any other mod.**

---

## 3. The two exceptions, and how one of them was closed

Decoupling has been broken exactly once, and it is instructive.

**`wondrous` ← `cobbleeconomy`.** The shop needs to sell wondrous items, but a
wondrous item is not a registry entry — it is a vanilla item stamped with
`custom_data`. So `shop.json` names it `wondrous:flying_boots`, and
`WondrousShop.java` resolves it through the `wondrous` api module. That is a real
compile-time dependency, a `publishToMavenLocal` step in the build order, and a
`suggests` in `fabric.mod.json`.

It also caused the only load-order bug in the suite: the shop loaded before
`wondrous` initialised, silently dropped every `wondrous:` entry, and rewrote
`shop.json` without them. Fixed by deferring catalog load to `SERVER_STARTED` —
but the underlying coupling remained.

**How the second one was avoided.** `spiritwolves` had the same problem: a Spirit
Stone is an echo shard plus `custom_data`, so naming `minecraft:echo_shard` in the
shop would sell a useless plain shard. The obvious move was to copy the wondrous
pattern — an api module, a `spiritwolves:` prefix, another integration file.

Instead, shop listings gained an optional **`components`** block:

```json
"spirit_stone": {
  "item": "minecraft:echo_shard",
  "quantity": 1, "price": 24, "currency": "diamond", "category": "Wondrous",
  "components": {
    "minecraft:custom_data": { "spiritwolves": { "bound": false } },
    "minecraft:item_name": "Spirit Stone"
  }
}
```

The config file describes the item completely, so `cobbleeconomy` sells it without
knowing `spiritwolves` exists, and `spiritwolves` stays perfectly standalone. The
contract between them is a string in a JSON file.

**This is now the preferred pattern.** Any future mod that sells a
component-marked item uses `components`. The `wondrous` integration stays as-is
because it works and rewriting it buys nothing — but it should not be copied.

---

## 4. The contract for a new mod

Everything a mod #8 must do to belong to this suite:

1. **Pay in plain diamonds or cobblestone items**, using the `giveOrDrop` pattern
   (add to inventory, drop at feet if full). Never destroy a reward.
2. **Charge in plain diamonds or cobblestone items** if it charges at all.
3. **Never depend on another mod** at compile time. No `depends`, no api module,
   no shared classes.
4. **Sell through `shop.json` `components`**, never through an integration file.
5. **Own its own config** under `config/<mod_id>/`, generated on first boot via
   `readOrCreate` — write defaults if missing, log it, never overwrite a file that
   failed to parse.
6. **Be server-side only.** `"environment": "server"`. Vanilla clients install
   nothing.
7. **No Mixins** unless there is genuinely no Fabric API event. (`ballot` has the
   only one in the suite.)
8. **Quiet per-player sound cues** via the shared `Chime.java` pattern, volume
   0.15–0.4. These are confirmations, not fanfares.

Build conventions (Loom, JDK 25, the shared `dist/` folder, and the `remapJar`
trap that has now bitten six mods) are documented in `MOD_AUDIT.md`.

---

## 5. Faucets and sinks

The economy is a bathtub. The minigame mods are taps; `cobbleeconomy` is the
drain. If the taps out-run the drain, currency inflates and every reward stops
meaning anything.

### Current faucets

| Source | Diamonds per active player |
|---|---|
| quizengine | 48 auto rounds/day × up to 3 | ~24–144/day |
| bounties | 3 held slots, 1–20 each | ~10–30/day |
| dailyquests | streak cap 3 | 3/day |
| mining | unbounded | — |

**Playtesting says the faucet rate is acceptable.** The problem is the drain.

### Revised after play: the taps are running dry, not hot

The table above is theoretical maximum, not what a session actually produces. In
practice a player has to be present for the right minigame at the right moment,
and most of those ceilings are never approached.

**The observed state is the opposite of the worry: sinks comfortably out-run
faucets, to the point of having to hand out diamonds during testing to keep
anything else testable.** The spiritwolves charges, the shop, and the wondrous
items between them drain faster than four minigames fill.

So, plainly, so it stops being re-litigated in every new mod:

> **Do not design a new mod around fear of diamond inflation.** A new event that
> pays a diamond for something the player earned is *fine*. Err on the side of
> paying out. If a faucet ever genuinely runs too hot it shows up in play within
> a day and every payout number in this suite is config — turning one down is a
> file edit, whereas a mod that shipped stingy just feels bad and quietly never
> gets used.

This does **not** license unbounded payouts. The things still worth refusing are
structural, not economic:

- **Anything that scales with a machine rather than with play** — an AFK farm, a
  redstone loop, or a duplication rule that a crafting recipe can be laundered
  through. Rate-limited-by-a-human is the line, not the size of the number.
- **Anything unbounded per unit time.** A cooldown or a roll is what keeps a
  generous payout from becoming a printer.

Judge a payout by *how often a real player can reach it*, not by how big it is.

### The nine shapes of a sink

Every durable money sink in game design is one of these. A suite missing a shape
is missing a class of spender:

| Shape | Canonical example | Status |
|---|---|---|
| Voluntary debt ladder | Animal Crossing house loans | ✗ |
| Time-limited stock | Nook's Cranny, Habbo rares | ✗ |
| Consumed by playing | RuneScape runes, WoW flasks | **spiritwolves** ✓ |
| Destruction / risk | EVE ship loss, repair bills | **spiritwolves** ✓ |
| Randomized crafting | Path of Exile | ✗ |
| Cosmetic appearance layer | WoW transmog | ✗ |
| Collective goal | Stardew Community Center | partial (`ballot`) |
| Rate-gate + pay to exceed | Genshin resin | ✗ |
| Player-made content from bought tools | Habbo wired furni | ✗ |

The core problem the shop had: **it was a checklist.** Roughly 292 diamonds bought
every diamond-priced item in the catalogue, permanently, after which diamonds were
worthless and three mods stopped paying meaningful rewards.

`spiritwolves` is the first structural answer — a Spirit Stone's charges are
consumed by *dying*, so the cost scales with how recklessly a player plays, and it
never completes. That is the shape worth repeating.

### The design rule

> **Minigames are faucets. `cobbleeconomy` is the drain. Any new mod should
> either add a tap or add a drain — and on current evidence the suite needs
> *taps* at least as much as drains, so a generous payout is the safer error.**

The earlier version of this rule said the suite "currently needs drains", which
was true of the *shop-as-checklist* era and stopped being true once spiritwolves
and the wondrous items landed. It is left recorded here because it was quoted at
several design decisions that should now be revisited if they were made cautious
on its account.

---

## 6. Engagement and retention

Retention is three nested loops. A suite that only serves one of them feels thin
no matter how good that one is.

### Session loop — "what do I do right now"

- **bounties** — accept, hunt, complete
- **quizengine** — trivia every 30 minutes
- **spiritwolves** — a companion at your side

Healthy. This is the strongest layer.

### Daily loop — "why log in today"

- **dailyquests** — riddle of the day, streak

Thin — one mod carries it. A rotating daily shop stock keyed off the same day
rollover would double this layer's surface for very little code.

### Weekly loop — "why come back this week"

- **bounties** board variety
- **ballot** — polls, build competitions

**The weakest layer, and the biggest opportunity.** `ballot` is 148 passing tests
of fully-built competition infrastructure that is barely used. Automated weekly
build competitions are the single highest-value unbuilt thing in the suite.

### Seasonal loop — "why care over months"

Nothing. A seasonal leaderboard reset with a `ballot`-voted prize would give the
whole suite an arc instead of a flat grind.

### Retention mechanics in play

| Mechanic | Where | Strength |
|---|---|---|
| Streaks (loss aversion) | dailyquests | Strong, underused — no streak protection to sell |
| Leaderboards | cobbleeconomy, quizengine | Moderate; no reset means early leaders are permanent |
| Rotation / FOMO | bounties, quizengine cadence | Moderate |
| **Attachment** | **spiritwolves** | **Strongest available, newly added** |
| Collection | — | Absent |
| Communal goals | ballot (latent) | Latent |

**Attachment is worth naming separately.** A named wolf a player has kept alive
for a month is a stronger reason to log in than any payout, and `spiritwolves` is
the suite's first mechanic that creates it. Its charges being a *safety net* rather
than a rent is what makes the attachment safe to form — the design deliberately
removes the heartbreak and sells the relief.

---

## 7. The unsolved problem: discoverability

Six mods, and **no shared surface where a player learns any of them exists.**

A new player joins and has no way to know that trivia starts in twelve minutes,
that the bounty board rotates at :15 and :45, that there is an open poll, that
today's riddle is unanswered, or that Spirit Stones exist at all. Most players
will discover two of six systems and never meet the rest.

This is a direct consequence of §2 — decoupling means nothing announces anything
else — and it is the price the design pays. It has to be solved *deliberately*
rather than emerging for free.

Options, cheapest first:

1. **The shop as the catalogue.** Already the de-facto answer for Spirit Stones —
   a player browsing `/shop` sees the name and lore and learns the feature exists.
   Cheap, already built, but only covers things that are for sale.
2. **A guide book (SGUI).** A browsable in-game manual with items, patterns, and
   explanations. This is the real answer: it solves discoverability for all six
   mods at once, and it can stay decoupled by being *its own mod* that reads a
   config file each mod does not know about.
3. **A `/whatson` command.** Live status across everything currently running.
   Requires either coupling or a shared config — least compatible with §2.

Option 2 is the recommended direction, and it is arguably mod #8.

---

## 8. Backlog

Ordered by value, not effort.

**Drains (the suite's stated need)**

- Cosmetic furniture catalogue — safest infinite sink, zero power creep
- Repair/upkeep costs on `wondrous` items — turns one-time purchases into rent
- Daily rotating shop stock, limited quantity — hits daily loop *and* sink
- Plot expansion tiers — the Nook ladder, infinite ceiling
- Cosmetic ranks / titles

**Loops**

- Automated `ballot` build competitions with currency prizes — fixes the weakest layer
- Seasonal leaderboard reset
- Streak protection sold in the shop — monetises the strongest existing retention hook

**Discoverability**

- SGUI guide book (§7)

**Content, not code**

- quizengine Quiplash prompts — currently 4
- quizengine trivia pool — 26 questions against 48 rounds/day repeats within hours

---

## 9. Principles, condensed

1. **Currency is the only coupling.** Everything else is a mod's own business.
2. **Config is the integration layer.** If two mods must agree on something, they
   agree through a JSON file, not a Java import.
3. **Minigames are taps, the shop is the drain.** Know which one you are building.
4. **Sinks must never complete.** A checklist is not an economy.
5. **Be generous.** Play showed sinks out-running faucets, not the reverse. Judge
   a payout by how often a real player can reach it, not by how large it is —
   and refuse only what scales with a machine instead of with playing (§5).
6. **Failure directions are chosen deliberately.** Rewards drop at your feet
   rather than vanish; a bad shop file empties the shop rather than crashing the
   server; a bad accounts file refuses to start rather than wiping balances.
7. **Vanilla clients install nothing.** Non-negotiable.
8. **Attachment beats payout.** The wolf a player names is worth more than the
   diamonds they earn.
