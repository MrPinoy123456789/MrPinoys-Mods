# MrPinoy's Mods — Improvement Roadmap

> **What this is.** A phased, drastic-improvement plan targeting, in priority
> order: (1) a new player discovers and engages with ≥5 of the 10 systems in
> their first session, (2) daily and weekly return rates, (3) session length.
>
> **Non-negotiable constraints inherited from `DESIGN.md`:** vanilla clients
> install nothing; currency is the only cross-mod coupling; config is the
> integration layer; no Mixins without justification.
>
> **Written:** 2026-08-11, against `SUITE_AUDIT.md` (same date) — with the
> audit's claims verified against source. Two of them were wrong (§0).

---

## 0. Corrections to SUITE_AUDIT.md — verified against source

The audit was checked against the code before this plan was written. Findings:

1. **WRONG — audit §2.2: "`Orchestrator.greet()` exists for late joiners but is
   not wired to a join event."** It *is* wired:
   `quizengine/fabric/.../QuizMod.java` registers
   `ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
   orchestrator.greet(handler.getPlayer()))`. A player joining mid-round *is*
   told about it. (It only fires during `SUBMITTING`, so a player joining
   between rounds still learns nothing — that's the remaining gap, and it is
   much smaller than the audit claims.)

2. **WRONG — audit §2.3: "Nothing announces a rotation" for bounties.**
   `BountyMod.java` broadcasts *"A new bounty is available on the bounty
   board!"* in gold on every window change, and broadcasts completions in
   green with the bounty description. The audit's proposed "handful of lines"
   fix already exists. The real gap is that the announcement doesn't say *what*
   the bounty is or make `/bounty` clickable — a polish item, not a missing
   system.

3. **CONFIRMED** — `dailyquests` has never been compiled or run (its README
   says so; `TurnIn.lookup()` flagged as first likely break). It carries the
   entire daily loop.

4. **CONFIRMED** — `ballot` has no scheduler, automation, or prize wiring.
   148 core tests, idle.

5. **CONFIRMED** — quiz content lives in hot-reloadable `trivia.json` /
   `prompts.json` (`Content.java`, `/quiz reload`). Expanding the pool is
   purely a data edit.

6. **CONFIRMED** — chatdonkey names are per-event random picks from the
   `names` pool (`EventContext.donkeyName()` — "per-event name"); animalese
   voices are deterministic per-name (`Animalese.voiceSeed(name)`), so sticky
   names get stable voices for free.

7. **CONFIRMED** — `Currency` is a plain record with no exchange-rate concept;
   `shop.json` entries already carry a per-entry `currency` field. A third
   currency is structurally cheap.

**Why corrections 1 and 2 matter strategically:** the audit undercounts what
already self-announces. Today, a first-session player plausibly meets — with
zero changes — quizengine (auto rounds + join greet), bounties (rotation
broadcast), chatdonkey (walks up to you), and dailyquests (join announce, *if
it runs*). That is four of the target five. This reframes the whole Tier-1
argument (§1).

---

## 1. Challenging the audit's Tier-1 ranking

The audit ranks **discoverability** as the #1 constraint. That is *partly*
right and importantly wrong, in three ways:

**First: half of Tier-1 is already built.** Tier-1 item #2 ("announce what
already happens") lists bounty rotations and quiz join-greetings as unbuilt.
Both exist (§0.1, §0.2). The remaining announcement gaps are small polish, not
a missing layer.

**Second: the actual first-session constraint is the *shop-gated half* of the
suite.** The four systems that announce themselves are all faucets/events. The
systems a new player never meets — spiritwolves, cobblebending, wondrous,
the disenchanter, and cobbleeconomy itself — are all reached *only* through
`/shop`, and **nothing anywhere tells a player `/shop` exists.** The
discoverability problem is not "ten systems, no shared surface"; it is "one
command gates five systems and has no signpost." That is a far cheaper problem
than the audit's framing suggests, and it means a full guide-book mod is
over-engineered for outcome (1).

**Third: reliability and content precede discoverability.** Two of the four
self-announcing systems are compromised:

- `dailyquests` has *never been compiled*. If it crashes or silently fails,
  the daily loop is zero and the join-announce surface is gone.
- quizengine repeats its 26 questions within hours. The audit itself says a
  repeated question "teaches players the event is not worth stopping for."
  Discoverability multiplies the value of what's discovered — **multiplying a
  system that disappoints players is negative leverage.**

**Revised ranking:**

1. **Make what self-announces worth engaging with** — compile/verify
   dailyquests, fill the quiz content pool. (Hours of work; protects the four
   free first-session touches.)
2. **Signpost the shop-gated half** — first-join hint, herald lines,
   richer bounty/quiz announcements. (This is the real discoverability fix.)
3. **The weekly loop** — ballot automation. (Unchanged from the audit; it is
   the biggest retention gap and the infrastructure is built and tested.)

Discoverability stays near the top — but as a *signposting* problem solved
with existing surfaces, not a *new shared surface* problem requiring a new
mod. §3 resolves the architecture question this raises.

---

## 2. The phased plan

Legend per item: **[owner | code/config/content | feeds]**.
Outcome mapping: O1 = first-session breadth, O2 = return rates, O3 = session
length.

### Phase 1 — highest leverage per unit of work (days, not weeks)

**P1.1 — Compile, run, and play-verify `dailyquests`.** ✅
[dailyquests | code | streaks → O2-daily]
What changes: build it, fix `TurnIn.lookup()` and whatever else breaks, run it
on a live server for a day. It carries the entire daily loop and one of the
four free first-session announcements. Nothing else in the daily loop matters
until this runs.

**P1.2 — Trivia pool 26 → 300+, Quiplash prompts 4 → 100+.** ✅
[quizengine | content | cadence/FOMO → O3, O2-daily]
What changes: `trivia.json` and `prompts.json` only. Hot-reloadable via
`/quiz reload`, zero engineering. The audit is right that this is the cheapest
large win in the suite; it is also a *precondition* for signposting quiz
harder (see §1, third point).

**P1.3 — First-join signpost for `/shop`.** ✅
[cobbleeconomy | code (small) | goal ladder → O1]
What changes: on a player's *first* join (a flag in the existing account
store), send 2–3 chat lines: what the currencies are, that `/shop` exists,
one hook line ("wolves that cheat death are in there"). This single change
unlocks discovery of the five shop-gated systems. No coupling — cobbleeconomy
describes only its own shop; the *listings* (already config) are what name
the other systems.

**P1.4 — Herald lines in chatdonkey's `lines.json` (static).** ✅
[chatdonkey | config only | interruption/novelty → O1]
What changes: add lines to existing pools that mention, in-character, things
the donkey has "heard about": *"They sell ghost-wolf stones in the shop, you
know. Not that YOU could afford one."* / *"There's a riddle board. You look
like someone who loses at riddles."* This is pure config against the built
line system, needs no facts contract, and cannot violate §2 — an operator
writing flavor text is not a mod dependency. The donkey already targets
active players and cannot be outrun; it is the best delivery vehicle in the
suite and this costs an evening of writing.

**P1.5 — Enrich the existing bounty announcement.** ✅
[bounties | code (small) | rotation/FOMO → O3]
What changes: the rotation broadcast (already built, §0.2) gains the bounty's
description and reward, and a clickable component running `/bounty`. Chat-as-UI
is already house style (audit §4.2.7).

**P1.6 — Streak visibility + raise the cap.** ✅
[dailyquests | code (small) + config | loss aversion → O2-daily]
What changes: broadcast streak milestones ("X is on a 7-day streak"), add
`/daily top`, raise or ladder the 3-diamond cap (config). The audit is right
that loss aversion needs an audience and a growing stake; both are cheap once
P1.1 makes the mod real.

**P1.7 — Fix stale docs.** ✅
[repo | content | agent/maintainer velocity]
`cobblebending/SPEC.md` status header (it *is* implemented);
`disenchanter/SPEC.md` gets a one-line pointer to `wondrous` (keep §8's
verify-list); `DESIGN.md` §1 table 7 → 10 mods; `wondrous/README.md` 9 → 12
items and stop teaching the api-module pattern. Also: fold the two audit
corrections (§0) back into `SUITE_AUDIT.md` so it stops asserting false gaps.

### Phase 2 — fills the empty loops (a week or two each)

**P2.1 — Automated weekly buildoffs with prizes.**
[ballot | code | communal goals + deadline/FOMO → O2-weekly]
What changes: a scheduler in ballot's fabric layer (its core model is done —
148 tests): open a buildoff every Friday, close voting Sunday, announce
winner, pay diamonds via `giveOrDrop` (offline-safe: ballot already persists
per-poll state; pay on next join if offline). Theme list is config. This is
the single highest-value item in the plan for O2-weekly and it reuses the
suite's most-tested idle infrastructure.

**P2.2 — Rotating daily stock + limited quantities in `shop.json`.**
[cobbleeconomy | code + config | time-limited stock sink + daily FOMO → O2-daily]
Schema design in §4.1. Reuses dailyquests' date-derivation trick so stock
survives restarts without stored state. Hits the daily loop and the first
missing sink shape in one change.

**P2.3 — Sticky per-player donkey names.**
[chatdonkey | code (small) | attachment → O2, O3]
What changes: persist one name per player (chatdonkey already has
`ReadOrCreate`; a tiny `donkeys.json` map of player UUID → name). Voices are
already deterministic per name (§0.6), so *your* donkey sounds like your
donkey forever. Second attachment carrier for almost nothing; also fixes
"nothing persists across restarts" for the piece that matters most.

**P2.4 — The facts contract + donkey as live herald.**
[chatdonkey + one-line writers in each mod | code | discoverability → O1, O3]
Architecture and §2-amendment argument in §3. Ships *after* P1.4 proves the
herald concept with static lines.

**P2.5 — Streak freeze sold in the shop.**
[dailyquests + shop config | code (small) + config | loss aversion → O2-daily; pre-monetization §5]
What changes: dailyquests recognises a `custom_data`-stamped item ("Riddle
Insurance") in the turn-in flow: consume it instead of breaking a streak.
Sold via the `components` pattern — no coupling. Priced in diamonds now;
becomes a premium listing later by editing one field (§5).

### Phase 3 — structural (each is its own project)

**P3.1 — Seasonal reset with a ballot-voted prize.**
[cobbleeconomy + ballot (via config, no coupling) | code | contestable ladders → O2-monthly]
What changes: `/baltop` and the quiz leaderboard archive to a named season and
reset on a config cadence; final standings broadcast; the season's prize is
chosen by a ballot poll (an operator/scheduler creates the poll — ballot never
knows why). Fixes "early leaders are permanent," the audit's correct
observation that the ladder actively repels late joiners.

**P3.2 — Voluntary debt ladder.**
[cobbleeconomy | code (core-testable arithmetic) | debt sink → O2]
Design in §4.3.

**P3.3 — Collective goal.**
[cobbleeconomy | code + config | communal sink → O2-weekly]
Design in §4.4.

**P3.4 — Cosmetic layer, attached to what players already love.**
[spiritwolves (+ shop config) | code + content | cosmetic sink + attachment → O2]
Design in §4.2.

**P3.5 — Upkeep economics: wondrous rent + focus durability.**
[wondrous, cobblebending | code | consumed-by-playing sink → O2]
What changes: wondrous items gain the `max_damage` + `REPAIRABLE`(diamond)
components — spiritwolves' *verified, zero-Mixin* anvil trick wholesale
(audit §4.6.1). cobblebending Focuses the same. Converts both catalogues from
checklist to rent using an already-proven mechanic. Deliberately Phase 3: do
not add costs to items before Phase 1–2 gives players more income surfaces
and reasons to care.

**P3.6 — Config-driven currency list.**
[cobbleeconomy | code (small) | pre-monetization §5]
What changes: the `Currency` set moves from constructed constants to
`currencies.json` via `readOrCreate`. No third currency ships — the *slot*
ships. See §5.

---

## 3. Resolving the discoverability tension against §2

**The tension.** `DESIGN.md` §2: a mod "may not know the name of any other
mod." Live discoverability ("a poll is open", "trivia in 12 minutes") needs
information to cross mod boundaries.

**Options considered:**

1. **Guide-book mod reading a config each mod doesn't know about** (DESIGN §7
   option 2). Rejected as the *primary* answer: it is a new mod, its content
   is static (so it can't say "a poll is open *now*"), it requires the player
   to *open* it — the exact failure mode the suite already has (bounties'
   board is excellent and invisible behind `/bounty`), and §1 showed the real
   gap is signposting, which P1.3/P1.4 solve more cheaply. A guide book also
   duplicates what shop lore text already does for the sink half.

2. **Donkey-as-herald, fed by operator config only.** This is Phase 1 (P1.4):
   zero architectural cost, but static — the donkey can say Spirit Stones
   exist, not that a buildoff closes tonight.

3. **A shared "facts" JSON contract.** Each mod *writes* a small status file;
   a herald *reads* them. **This is the pick for live discoverability**, with
   the donkey as the reading surface.

**The chosen architecture — the facts contract:**

- A well-known directory: `config/facts/`.
- Each participating mod writes `config/facts/<own-modid>.json` — atomically,
  debounced, via the write pattern every mod already uses (audit §4.2.3). A
  fixed, tiny schema:

```json
{
  "facts": [
    { "line": "A buildoff is open — voting closes Sunday.",
      "expires": "2026-08-16T20:00:00Z",
      "weight": 3 }
  ]
}
```

- Rules: a mod may only ever **write its own file** and may **never read the
  directory**. The herald (chatdonkey's fabric layer, behind a
  `heraldEnabled` config default-off) reads all files, drops expired facts,
  and mixes surviving lines into the donkey's existing lecture pools at a
  configured rate. Malformed file → skipped (the `readOrCreate` failure
  direction: a quiet donkey, never a crash). Missing directory → the feature
  simply never fires.

**Why this passes §2's spirit, argued honestly:**

- No mod names another mod. A mod names a *directory convention* — exactly as
  it already names `config/<mod_id>/` and the shared `dist/` folder.
- The contract is a string in a JSON file — which `DESIGN.md` §3 *explicitly
  celebrates* as the preferred pattern ("the contract between two mods is now
  a string in a JSON file") and §9.2 canonises ("config is the integration
  layer").
- Add/remove independence survives: remove any mod and its facts file goes
  stale and expires; remove chatdonkey and the files are harmlessly unread.
  No load order, no compile deps, no service lookup.

**Should §2 be amended? Yes — narrowly.** §2's plain text ("may not know the
name of any other mod") is stricter than its intent (no compile-time coupling,
no load-order risk, independent shippability). The `components` pattern
already breached the plain text — `spiritwolves` appears as a string in
cobbleeconomy's config — and `DESIGN.md` blessed it. Proposed amendment,
appended to §2:

> **Amendment (facts).** A mod may additionally *publish* facts about itself
> to `config/facts/<own-modid>.json` in the shared facts schema. It may never
> read that directory, never depend on a reader existing, and never change
> behavior based on whether its facts were consumed. Publishing is
> fire-and-forget, like dropping items.

The asymmetry (write-own, never-read-others) is what keeps this §2-safe: no
mod's behavior can ever depend on another mod's presence.

**Status: §2 has been amended, and more generally than proposed here.** Rather
than a facts-specific clause, `DESIGN.md` §2 now states that "know the name of"
means *in code*, and that a mod may publish self-naming data which others read
without naming anybody. That covers this contract and suite items
(`SUITE_ITEMS.md`) under one rule, so no facts-specific amendment is needed.

**Relationship to suite items.** The two are siblings with the same asymmetry
and a deliberate difference in *where* the published data lives:

| | **Facts** | **Suite items** |
|---|---|---|
| Publishes | Live status, expires | Item definitions, permanent |
| Written | At runtime, by the mod | Never — shipped in the jar |
| Lives in | `config/facts/<modid>.json` | `data/<modid>/suite_items/` |
| Read by | One herald (chatdonkey) | Any consumer, via datapack merge |

Facts *must* be runtime writes because the content is live — a poll's deadline
is not knowable at build time. Suite items must *not* be, because the content is
static and a runtime write would put two mods in contention over one file. Do
not unify them; the difference is the whole design in each case.

**Which mods publish, initially:** ballot (poll/buildoff open + deadline),
quizengine (next round ETA), dailyquests (riddle unanswered), bounties
(current board summary). Each is ≤20 lines against state the mod already has.

---

## 4. The four missing sink shapes — concrete designs

All four are `shop.json` schema extensions or small features in existing
mods. **No new mods.** Owner for the schema work is cobbleeconomy in every
case, because the shop parser and the `components` stamper already exist.

### 4.1 Time-limited stock (P2.2)

`shop.json` entry extensions:

```json
"honey_block_sale": {
  "item": "minecraft:honey_block", "quantity": 4, "price": 2,
  "currency": "diamond", "category": "Today Only",
  "stock": 8,
  "rotation": { "pool": "daily", "slots": 3 }
}
```

- `stock`: server-wide purchasable quantity per rotation window. Persisted in
  a small `stock.json` beside `shop.json` (atomic debounced writes; failure
  direction: lost write → *more* stock sold cheap, never money destroyed —
  consistent with "fail toward the player").
- `rotation.pool` + `slots`: all entries sharing a pool form a set; today's
  visible subset of `slots` entries is **derived from the date** (dailyquests'
  restart-proof trick, audit §2.4) — no stored rotation state at all.
- `rolloverHourUtc` shared convention with dailyquests so "the daily reset"
  is one moment, not two.

Feeds: daily return (check the shop after reset), scarcity FOMO, and finally
gives `/baltop` hoarders a reason to spend.

### 4.2 Cosmetic layer (P3.4)

Attach cosmetics to the suite's attachment carriers — things players already
love — rather than inventing a standalone cosmetics system:

- **Wolf cosmetics (spiritwolves).** Sell `custom_data`-stamped tokens via
  the `components` pattern: collar colors beyond dye (uses the 26.2
  `WOLF_COLLAR` entity data component, audit §4.6), name styles (colored/
  formatted wolf name via `CUSTOM_NAME` component), and **journal covers**
  (a lore-line flourish on the Spirit Stone). Applied by right-clicking the
  token on your wolf — `UseEntityCallback`, the event spiritwolves already
  handles. Server-side entity data only; vanilla clients render all of it.
- **Donkey grooming (chatdonkey, later).** Once P2.3 makes donkeys sticky,
  sell saddles/carpets for *your* donkey the same way.

Zero power creep, infinite ceiling (new colors/styles are config + one token
definition), and every purchase deepens attachment — the suite's strongest
retention mechanic buying more of itself.

### 4.3 Voluntary debt ladder (P3.2)

A `/loan` feature in cobbleeconomy — *core-module arithmetic*, fully testable
in the house style:

- Tiers in `loans.json`: e.g. borrow 32 / 128 / 512 diamonds; each tier only
  unlocks after the previous is repaid (the Animal Crossing ladder).
- Repayment: a configurable fraction (default 25%) of every *deposit* is
  garnished toward the loan until cleared. No interest by default (interest
  is a config field, default 0 — the sink is the *commitment*, not the rake).
- While indebted: `/baltop` shows a debt marker (public stakes = loss
  aversion), and taking a new loan is blocked.
- Failure direction: garnishment applies on the credit side of the existing
  deposit path, so a crash loses the player's garnished payment *into* the
  void, never mints money — consistent with "the economy fails smaller."

Why it's a sink: it pulls *future* faucet output into a *present* purchase,
which is the one sink shape that works on players who currently have nothing
— exactly the new players outcome (1) targets. Pair with the shop: big-ticket
items (16+ diamonds) show "financeable" in lore.

### 4.4 Collective goal (P3.3)

A `shop.json` entry type, not a new mod:

```json
"village_beacon": {
  "collective": true,
  "goal": 5000, "currency": "cobblestone",
  "category": "Community",
  "lore": "When the pot fills, the server votes on the prize."
}
```

- Anyone contributes any amount; a progress bar renders in the shop UI (sgui
  already draws the shop) and milestone broadcasts fire at 25/50/75/100%.
- On completion: cobbleeconomy broadcasts and *an operator (or the P2.1
  scheduler) opens a ballot poll* to choose the reward. cobbleeconomy never
  calls ballot — the handoff is human/config, preserving §2.
- The pot is append-only in the existing transaction log, so disputes are
  answerable (ballot's own design argument, reused).

Why this and not extending ballot: ballot's buildoffs already cover the
*event* half of communal goals (P2.1); this covers the *economic* half, and
the money infrastructure (accounts, logs, atomic writes) lives in
cobbleeconomy already.

---

## 5. Pre-monetization surfaces

Nothing is monetized now. The rule for this section: **build the surface so
that flipping it later is a config edit, not a redesign.** In every case the
mechanism is the same: the shop's per-entry `currency` field already exists,
so anything *sold through the shop* becomes premium-sellable the moment a
premium currency exists.

| Surface | Build now (Phase ref) | Sellable later via |
|---|---|---|
| **Third currency slot** | `currencies.json` config-driven currency list (P3.6). Ship with the same two currencies. | Add one JSON entry for a premium currency; grant it out-of-band. Zero code. |
| **Streak freeze** | Item recognition in dailyquests + shop listing in diamonds (P2.5) | Change the listing's `currency` field. The most-copied retention purchase in mobile gaming, already flagged by `DESIGN.md` §8. |
| **Cosmetics** | Wolf/donkey cosmetic tokens via `components` (P3.4) | Same — `currency` field per listing. Infinite, power-neutral catalogue. |
| **Time-limited stock** | Rotation + stock schema (P2.2) | Premium-currency-only rotating rares (the Habbo shape). Config only. |
| **Donkey interruptions** | chatdonkey's command tree is already the API (`trigger`, `extend` returns granted seconds for refunds, `say` sanitises input, `grace`/`ungrace`) | A Twitch/web bridge driving those commands. The seams are cut; the bridge is deliberately *not* in this plan (see §6). |
| **Bounty slots** | Make the 3-held-slot cap a config value, and add a slot-expansion item recognised via `custom_data` (small; fold into P1.5) | Sell the 4th/5th slot. This is the rate-gate + pay-to-exceed shape. |
| **Debt forgiveness / early unlock** | Debt ladder (P3.2) with tier-unlock as data | Sell a tier skip. Deliberately last — monetizing debt needs care. |

What deliberately does **not** get a surface: anything that sells power
(fang tiers, focus tiers, payout multipliers). The suite's economy design
(structural anti-exploit, generosity principle) survives cosmetic/convenience
monetization; it does not survive pay-for-power.

---

## 6. Kill / descope list

Assume limited time. Ordered by how confidently you should drop it:

1. **KILL: the standalone guide-book mod (mod #11).** §1 and §3 argue the
   signposting stack (first-join hint + herald + shop lore + enriched
   announcements) covers outcome (1) without a new mod, new content surface,
   or new maintenance burden. Revisit only if, after Phase 2, new players
   measurably still miss systems.
2. **KILL: `disenchanter` as a standalone mod.** It shipped inside wondrous.
   Keep the SPEC as a pointer + verify-list (P1.7). Never build it.
3. **KILL: cobblebending's second discipline / mastery ladder.** The power
   fantasy works as-is for O3. Focus durability (P3.5) is the only
   cobblebending work worth doing this year.
4. **KILL: plot expansion tiers** (DESIGN §8 backlog). Requires a land/claim
   system the suite doesn't have — that's a new mod plus grief-management
   scope. The debt ladder (P3.2) delivers the same "Nook ladder" psychology
   on existing infrastructure.
5. **DESCOPE: the Twitch bridge.** It is *audience acquisition*, and the
   suite's problem is retention of players it already gets. The seams exist
   and don't rot (the command tree is tested). Build it when there's a
   streamer to point it at, not before.
6. **DESCOPE: a generic collection mechanic.** The audit correctly notes
   collection is absent, but bolting one on (badges? museum?) is a whole new
   system. The cosmetic catalogue (P3.4) plus buildoff monuments (P2.1) give
   partial collection pressure for free. Revisit in a future season.
7. **DESCOPE: spiritwolves v4 balance polish.** Don't tune killstreak/fang
   stacking numbers in the abstract — they're first guesses (audit §6.8) and
   tuning unplayed numbers is waste. Play-verify during Phase 1–2 live time,
   then tune from observations. Config edits, no dev time reserved.
8. **DESCOPE: migrating wondrous off its api-module coupling.** `DESIGN.md`
   §3 already says rewriting it buys nothing. Freeze it, don't extend it, and
   sell any *new* wondrous-adjacent items via `components`.
9. **DON'T BUILD: `/whatson`.** Strictly dominated by the facts contract +
   herald (§3) — same information, worse delivery (player must ask), worse
   §2 story.

---

## 7. Sequencing rationale, restated in one paragraph

Phase 1 spends days making the four self-announcing systems *true and good*
(dailyquests runs, quiz doesn't repeat) and signposting the shop-gated five
(first-join hint, herald flavor lines, richer announcements) — that alone
plausibly delivers outcome (1). Phase 2 fills the two empty loops with the
suite's own idle, tested infrastructure (ballot's competition model for
weekly, date-derived shop rotation for daily) and buys the second attachment
carrier for pennies (sticky donkeys), then formalises live discoverability
with the facts contract once static heralding has proven the voice. Phase 3
is the structural economy work — seasons, debt, collective goals, cosmetics,
upkeep — each of which deepens return rates but none of which should delay
the cheap wins in front of it. Monetization surfaces ride along as config
seams (§5) and cost nearly nothing extra to leave open.
