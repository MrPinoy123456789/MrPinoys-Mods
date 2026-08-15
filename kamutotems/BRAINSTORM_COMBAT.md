# Brainstorm — Combat Identity & Boss Encounters

**Status:** brainstorm. Nothing here is locked. No code has been written against
it. This is the raw material for a future SPEC §7.4 / §5.8 revision.

**Date:** 2026-08-13

**The three questions this doc exists to answer:**

1. Why does every kamu feel like the same kamu with a different colour?
2. Why does every boss feel like the same fight with a bigger health bar?
3. Where do healing and other continuous effects go, given that the construct
   grammar is built for discrete events?

(1) and (2) turn out to be the same question, and the answer to both is *identity
over magnitude* — §1 through §8. (3) is a different shape of problem and gets its
own answer in **§9: a second, simpler system built on SHAPE + MODIFIER.**

---

## 1. Audit — what we actually have today

### 1.1 The effect vocabulary is narrower than it looks

`EffectsMc.applyOne` implements 15 non-trigger effect ids. Counting what they
*do* rather than what they're called:

| Effect id | Kamu name | What the code actually does | Distinct? |
|---|---|---|---|
| `pierce` | Pierce | `hurt(target, 1.0×)` | — |
| `shock` | Lightning | `hurt(target, 1.5×)` | — |
| `bolt` | Bolt | `hurt(target, 2.0×)` | — |
| `burst` | Burst | `hurt(target, 2.0×)` — *literally the same case arm as `bolt`* | — |
| `shatter` | (reaction) | `hurt(target, 2.5×)` | — |
| `thermal_shock` | (reaction) | `clearFire` + `hurt(target, 3.0×)` | marginal |
| `chain` | Chain | AoE `hurt`, radius 6.0, max 1 target | — |
| `bloom` | Bloom | AoE `hurt`, radius 3.5, max 4 targets — *same case arm as `chain`* | — |
| `conduct` | (reaction) | AoE `hurt`, radius 5.0, unlimited | — |
| `fire` | Fire | `igniteForSeconds` | ✅ |
| `frost` | Rime | `SLOWNESS` effect | ✅ |
| `venom` | Poison | `POISON` effect | ✅ |
| `knock` | Heave | `push` away from source | ✅ |
| `blink` | Blink | teleport *the caster* forward | ✅ |
| `leech` | Leech | `hurt` + heal source by 35% | ✅ |

**Nine of fifteen effects are the function `hurt()` with a different constant.**
Three more are `hurt()` in a loop with a different radius. The player's mental
model of the catalog collapses to "which one has the biggest number," which is
exactly the failure mode being flagged — it just isn't Fire/Poison/Wither, it's
Pierce/Shock/Bolt/Burst.

This is the real finding. The redundancy critique is *more* true of the current
catalog than the version that prompted it, because at least Fire and Poison have
different tick behaviour in vanilla; `bolt` and `burst` share a `case` arm.

### 1.2 Bosses do not use their kamu

`Boss.kamu` is assigned in the constructor (`Boss.java:49`) and read by exactly
one thing: `buildBarName`, which prints the names on the boss bar. The boss
never casts them.

That is a direct contradiction of SPEC §7.1, which claims the boss bar naming is
"the suite's only tutorial — a player who fights a boss carrying Rime learns what
Rime does before they ever own it." Today the player learns a *word*, not a
behaviour. And SPEC §7.2's claim that the drop is "legible (you saw it used
against you)" is currently false: nothing was used against them.

### 1.3 Boss difficulty is four numbers

`Boss.spawn` scales max health, attack damage, armour, knockback resistance and
movement speed by tier. That's it. SPEC §7.1 already half-admits the problem
("without knockback resist a boss is trivially chain-knocked into a corner, which
makes every fight the same fight regardless of tier") — but knockback resistance
only fixes *one* degenerate strategy. Bow-kiting a tier IV still works, standing
in a 2-block corridor still works, and the fight has no beats: no phases, no
telegraphs, no moment where the player has to change what they're doing.

### 1.4 What is genuinely good and must survive any redesign

- **Reactions.** `ReactionEngine.defaults()` — thermal_shock, melt, conduct,
  shatter, toxic_flame, tainted — is the most interesting thing in the mod. It's
  the only place where two kamu combine into something that isn't a sum.
- **The construct-as-sentence framing.** Delivery plus modifiers reads as language.
- **Config-first, fail-open loading.** Everything below should be tunable in
  JSON without a rebuild.
- **Server-side, zero-Mixin, vanilla clients.** Non-negotiable constraint, and
  it shapes every proposal in §5 and §6.

---

## 2. The design principle

Every augment should answer:

> **"What changes about the fight when I equip this?"**

and never:

> **"How does this version of damage-over-time work?"**

Corollary: an augment whose answer is "I deal ~18% more damage" has no identity
and should either be given one or merged into another augment. Magnitude is a
*tuning* axis, not a *design* axis.

Second corollary, and this is the one that makes the whole thing cohere:

> **An augment's identity is the same whether the player wields it or the boss
> wields it.**

If Fire means "get out of there" when you use it, it must mean "get out of
there" when a boss uses it on you. That single rule turns the boss fight into
the tutorial SPEC §7.1 already promises, for free — you don't have to author
boss abilities *and* player abilities, you author one vocabulary and point it in
both directions.

**Use it as an admission test, not just an aspiration.** An augment that only
makes sense in one direction does not belong in the roster at all. `Blink` is the
worked example: caster-mobility reads fine on a player and is either useless or
infuriating on a boss, so it fails and is cut (§4.5).

**And where a boss genuinely needs a behaviour the vocabulary won't carry, that
is a job for the mob type, not a new kamu.** If a boss should teleport, make its
base mob an enderman (§5.6). Choosing the creature is free, already config, and
costs the shared vocabulary nothing — whereas a kamu that exists only to give
bosses one trick is a permanent asymmetry in a system whose whole value is that
both sides speak the same language.

### 2.1 The admission law

Every proposed kamu must clear all four. **There are no special cases, and a
kamu that needs one is not a kamu.**

| # | Test |
|---|---|
| 1 | It answers **"what changes about the fight when I equip this?"** — not "how much damage" |
| 2 | It has a **construct reading** — something it does on an attack |
| 3 | It has an **aura reading** — something it is, continuously or on a condition |
| 4 | Both readings work **identically for a player and for a boss** |

Tests 2 and 3 are what emptied §4.5 and closed both exclusivity buckets in §9.4.
Test 4 is what cut Blink. Test 1 is what cut everything else.

The payoff is that the catalog is **one list**. There is no player roster and
boss roster, no construct roster and aura roster, no table of which kamu are
legal where. Seven kamu, four auras, three deliveries, and every combination is
defined.

### 2.2 Two layers: kamu, and attack patterns

The law raises an obvious question — if a boss and a player run the same kamu
through the same code, what actually makes a boss fight different from swinging a
sword?

**Not the kamu. The attack pattern.**

| Layer | Player | Boss |
|---|---|---|
| **What the effect does** | the kamu | **the same kamu, same code** |
| **How it is delivered** | your weapon, and the delivery slot (Hit / Echo / Splash) | the mob's attack pattern — its vanilla AI, its wind-ups, its phases |

The delivery slot is *the player's attack-pattern vocabulary*. A boss's attack
pattern is its mob AI. Same role in the design, different source — and that is
the whole difference between the two sides.

**This makes boss-fight mechanics a separate discipline from Kamuy.** Phases,
telegraphed wind-ups, charge goals, minion waves, per-tier mob choice — none of
that is kamu work. It is monster design, and it would be worth doing even if the
kamu system did not exist. Conversely, every hour spent on the kamu system
improves boss fights for free, because the boss speaks the same vocabulary.

Two practical consequences:

- **They should be planned and built separately.** Bundling them hides the fact
  that one is a shared-vocabulary problem and the other is an AI problem, and
  they fail in completely different ways.
- **`EffectsMc` must never branch on whether the caster is a player.** If it
  does, the law is broken and the boss has stopped being a tutorial. Branching on
  *which system called* — construct versus aura — is fine and expected; that is
  the two readings, and it applies to players and bosses alike.

---

## 3. Research — what other mods do

Searched and reviewed 2026-08-13.

| Source | The idea worth stealing |
|---|---|
| **L_Ender's Cataclysm** | Multi-phase bosses where the phase change is a *rule* change, not a stat change. The Ender Guardian's phase 2 shatters its own helmet — it **trades defence for speed**. Also: ground-pound earthquakes and persistent void runes that chip health per second, i.e. **zone denial**, so standing still and trading hits is not a strategy. |
| **Mowzie's Mobs** | Attacks read as *intent*: wind-up → marked ground → payoff. Widely cited as having the best boss AI in the modding scene, and it earns that above its models and animations. Umvuthi's solar strikes are the reference. |
| **Bosses'Rise** | Souls-like: a **fixed, learnable rotation**. Fairness comes from repeatability — the player is allowed to memorise the fight. Randomised movesets feel unfair, not deep. |
| **Custom Boss Template** (datapack) | Proof the entire thing works with **zero client mod**: 10 HP-threshold phases, a timed attack ticker, minion summon waves, boss turns to face nearest player. Pure vanilla, pure server-side. This is our closest technical analogue. |
| **Custom Mobs Framework** | "Stalking" transition phases that **suppress** normal combat goals, and teleport-behind overrides that pre-empt the current attack. Phases as goal-set swaps. |
| Game design writing on telegraphing (Game Developer / Chaotic Stupid) | The canonical charge-up: **particle accumulation → distinctive rising sound → bright flash → payoff.** Three beats, always the same three beats. |

**The constraint that shapes everything:** vanilla clients install nothing, so
there are no custom models and no custom animations. The entire telegraph budget
is:

- particles (`level.sendParticles` — proven across the suite, e.g.
  `spiritwolves/AbilityProcs.java:184`, `spiritwolves/Tricks.java:392`)
- sound (`playSound` — proven, `spiritwolves/Senses.java:101`)
- boss bar colour and overlay (`ServerBossEvent` — already held per boss)
- title / subtitle / actionbar text
- mob equipment and potion-effect particles, which are visible client-side for free
- **positioning** — a mob that visibly backs off and stands still *is* a wind-up

That's less than a modded client gets, but it is enough. The datapack scene
proves it. Sound is doing most of the work; commit to distinct sounds per
attack early rather than treating audio as polish.

---

## 4. The augment identity pass

### 4.1 The core roster

Each augment gets **one signature mechanic**. Not three. The temptation to stack
five debuffs on Poison is the thing to resist — a signature that a player can
state in one sentence is worth more than a bundle they have to read patch notes
to understand.

| Augment | Vanilla anchor | Identity | Signature mechanic | Player sentence |
|---|---|---|---|---|
| **Fire** | Fire | **Pure damage** | Sets the target on fire. Nothing else — the highest DoT in the roster, and no secondary effect at all | "You are burning." |
| **Poison** | Poison | **Attrition** | Applies poison. That is the whole augment — vanilla poison already weakens, so nothing needs bolting on | "You are sick." |
| **Wither** *(new)* | Wither | **Decay / anti-recovery** | Blocks natural regeneration, suppresses incoming healing, strips absorption | "You cannot recover." |
| **Ice** | Powder snow / freezing | **Control** | Light freezing damage and a slow that deepens on repeat application. Sets up `shatter` with Lightning; reacts with fire into `thermal_shock` / `melt` | "You are not going anywhere." |
| **Lightning** | Lightning | **Impact** | Burst damage and a knockback **jolt**. No damage over time and no chaining — the jolt is the whole secondary. Doubles in wet conditions | "Get back." |
| **Heal** | Regeneration | **Sustain** | On a construct, converts damage dealt into health — lifesteal. As an aura, health over time | "I outlast you." |
| **Absorption** | Absorption | **Mitigation** | On a construct, hitting things grants absorption hearts. As an aura, the same hearts held or pulsed | "I planned for this." |

**Every element is a vanilla status effect, and they trade damage against a
secondary effect.** That is the whole axis. It is deliberately not a propagation
model or a spreading model — those were invented complexity, and none of them
survive the question "what does this look like in the damage numbers?"

| Element | Vanilla status | DoT | Secondary effect |
|---|---|---|---|
| **Fire** | Fire | **Highest** | **None** — that's what it buys |
| **Ice** | Freezing | Low | Slows |
| **Poison** | Poison | Low | Weakens; cannot kill (vanilla poison floors at ½ heart) |
| **Wither** | Wither | Moderate | Blocks healing — *and it can kill*, unlike poison |
| **Lightning** | — | Burst, not DoT | Knockback jolt |

**Fire is the pure-damage option and that is its identity.** No spreading, no
fire patches, no lingering ground effect — just vanilla "on fire," ticking harder
than anything else because it does nothing else. A player choosing Fire is
choosing damage over utility, and that is a real choice they can make without
reading anything.

This axis also settles what a new element would have to justify: a slot on this
table. "More damage *and* a secondary effect" is not an element, it's a power
creep, and "neither" is not an element at all. That is why §4.5 is empty.

### 4.2 Wither — the new wither augment

Currently absent from the catalog. Worth adding because it occupies a genuinely
empty mechanical niche: **every other augment adds pressure; Wither removes
options.**

Escalation path (this is the pattern all upgrade paths should follow — deepen
the fantasy, never "+2 damage"):

1. Applies vanilla Wither.
2. Duration extends.
3. **Suppresses natural regeneration** on the target.
4. **Reduces incoming healing** by a percentage (this is the mechanic that makes
   Wither matter in PvP and against healing bosses).
5. Strips absorption hearts.
6. Small damage burst whenever the withered target takes damage from any source.

Note the undead interaction as a possible flavour hook: in vanilla, Wither heals
undead. Inverting that — Wither makes undead *vulnerable* rather than healed — is
thematically strong ("this is corruption that even the corrupt can't stomach")
but it's a vanilla-expectation violation and should be a config flag, off by
default, rather than a baked assumption.

### 4.3 Heal — one kamu, two readings

Heal is the keystone that makes Wither mean something: an anti-healing augment in a
game with no healing is a solution without a problem.

**But it is not a construct augment.** This section originally weighed three
framings — reactive-on-condition, heal-nearest-ally, and regeneration-over-time.
§9 answers all three better, because healing has no honest delivery — it isn't
something that lands — and the aura system is built for exactly that:

- **Behaviour:** regeneration-over-time. It makes Wither's counterplay razor-sharp —
  Wither *shuts Heal off* — and the direct opposition of two augments is good design
  language.
- **The reactive framing** is now the `Rebuke` aura (§9.3), which after §4.4's
  slot merge is the only "when you're hit" mechanic in the design.
- **The ally-heal framing** is now the `Bloom` aura (§9.3).

**`Leech` is cut, and Heal absorbs it.** They were one idea split across two
words: Leech converted damage dealt into health, Heal restores health, and both
answer "I outlast you." Now a single kamu carries both readings, split by which
system it is slotted into:

| Heal, slotted as | Does |
|---|---|
| a **construct** modifier | converts damage you deal into health — this is what Leech was |
| an **aura** modifier | health over time, on the aura's condition |

That split is not a special case. It is exactly the construct/aura distinction the
whole design rests on: an event versus a state, the same identity at two tempos.
Heal is the clearest demonstration of it in the roster, which is a good reason to
make it the kamu a new player meets first.

**The risk that survives: Heal plus high-tier gear is how you get an unkillable
player**, and the risk is now *concentrated* rather than spread across two kamu.
It wants a cooldown or a diminishing-returns curve on the lifesteal ratio, and it
should be the first thing retuned after play. Wither is the designed counter
(§4.2) and it is the reason Wither exists.

### 4.4 The slot model — WHEN and HOW become one slot

**Decision: the WHEN slot and the ACTION slot merge.** The construct drops from
four slots to three:

```
  BEFORE                                  AFTER
  [ WHEN ] [ ACTION ] [ MOD ] [ MOD ]     [ DELIVERY ] [ MOD ] [ MOD ]
```

They were always the same question asked twice. WHEN asked "at what moment does
the effect happen"; ACTION asked "in what form does it arrive." A player choosing
between them was choosing two halves of one decision, and the panel made them
fill in both.

**One slot answers both: how and when your effect lands, relative to your attack.**

| Delivery | Timing | Form |
|---|---|---|
| **Hit** | with the strike | single target — the baseline |
| **Echo** | **1 second after** the hit | single target. The weapon lands clean first, then the effect arrives |
| **Splash** | with the hit | area, and weaker for it |

**Echo is the interesting one** and it's the proof the merged slot is the right
shape — it is a *timing* idea and a *delivery* idea at once, so it could not have
been expressed in either old slot alone. It also creates real tactical texture:
the effect lands a second later at the target, so it still fires if you've
disengaged, and it stacks differently against a fleeing target than Hit does.

#### Delivery changes what a self-benefiting modifier is worth

`Heal` and `Absorption` return something to *you* rather than doing something to
the target, so delivery hits them differently from the elements — and this is
where Splash earns a role beyond "AoE damage."

| Delivery | Heal / Absorption |
|---|---|
| **Hit** | full value, from the one target |
| **Splash** | **weaker per target, but it sums across everyone hit** |
| **Echo** | full value, arriving with the delay rather than on the swing |

**Splash is the scaling delivery.** Against one enemy it is the worst choice;
against six it is the best by a distance. `Splash + Heal` is a wade-into-the-pack
build, and it makes crowd size a resource rather than a threat — which is a fight
the mod currently has no answer to.

Note the asymmetry, because it matters for implementation: **for damage
modifiers, Splash simply scales down per target** — spreading damage over six
mobs is already its own reward, and summing it would double-dip. Only the
self-benefiting modifiers accumulate back to the caster.

**This wants a target cap.** Uncapped, `Splash + Heal` in a mob farm or a raid is
unbounded self-healing. Cap the number of targets that contribute (six is the
first guess) and make it config — the cap, not the per-target value, is the lever
to reach for first.

`Echo + Heal` is the quiet one worth noticing: the heal arrives a second after
the blow, so it rewards committing to a swing you expect to trade on. It also
means the sustain lands *after* the counter-attack rather than before it, which
is a genuinely different feel from `Hit + Heal` at the same total value.

**This resolves three open problems at once:**

- **`bolt` / `burst` / `pierce` are absorbed.** `burst` becomes `Splash` — same
  idea, better name. `bolt` is cut: "at range" is a property of your weapon, not
  of the totem, and the totem is deliberately weapon-agnostic (SPEC §5.5). `pierce`
  is cut with them; "ignores armour" is a damage number, and §2 has been rejecting
  those all the way through this doc.
- **`On Strike` stops being redundant.** It was a kamu that duplicated leaving the
  WHEN slot empty. Now `Hit` is the baseline *delivery* — the thing you compare
  Echo and Splash against — which is a real role.
- **`On Wounding` is cut, and `Rebuke` owns "when you're hit"** (§9.3). That
  removes the last cross-system duplicate, and it retires the self-targeting bug
  in `DamageFunnel.resolveFor(victim, victim, "on_hurt")` rather than fixing it.
  `On Slaying` is cut with it: there is no vanilla Minecraft term for an on-kill
  delivery, and Hit / Echo / Splash is a clean three without one.

**The two systems now have the same architecture**, which is the real prize:

| | Delivery slot | Content slots |
|---|---|---|
| **Construct** | Hit · Echo · Splash | 2 modifiers |
| **Aura** | Bloom · Focus · Momentum · Rebuke | 1 modifier |

Both read as *delivery + content*. A player who learns one has learned the shape
of the other, and §9.1's "two grammars" claim gets weaker in the best way — it's
now one grammar at two tempos.

**What this costs in code.** This is the most invasive change in the doc and it is
not a config tweak:

- `SlotRole` loses `ACTION` and `TRIGGER`, gains `DELIVERY`.
- `Category` loses `ACTION` and `TRIGGER`, gains `DELIVERY`.
- `Construct.SLOT_COUNT` goes 4 → 3; `TRIGGER_INDEX` disappears, along with
  `triggerKamuId()` and `actionAndModifiers()`.
- `DamageFunnel`'s trigger gating (`want.equals(event)`) becomes delivery
  dispatch, and `Echo` needs a delayed-effect queue that nothing in the mod
  currently has.
- `SlotMenu` drops a slot.

Old totems are unreadable afterwards, which is fine — PLAN_V2 §5 already says old
data is disposable and migration code is not to be written.

### 4.4b The leftover damage effects

The `hurt()`-with-a-constant cluster is now almost entirely resolved: `chain` is
cut outright and `Splash` covers multi-target (§4.5), `bloom` becomes an aura (§9.3), and
`bolt`, `burst` and `pierce` are absorbed or cut by the slot merge above. What
remains is only the reaction outcomes that share their shape, which are tuning
constants rather than kamu. The options below are kept for the reasoning.

**Option 1 — Merge.** `bolt` and `burst` share a case arm; they should be one
kamu. This reduces the catalog but makes every remaining entry mean something.
Downside: fewer things to collect, and the drop economy in SPEC §7.2 assumes a
pool size (§7 #1).

**Option 2 — Differentiate.** Give each a real identity:
- `pierce` → **ignores armour** (that's what piercing means, and it's mechanically
  distinct from raw damage against a high-armour boss)
- `bolt` → **ranged / at-distance**, the only one that works without contact
- `burst` → **delayed detonation** — damage lands a second later, at the target's
  *current* position, so it rewards prediction
- `shock` → **impact** — burst damage plus a knockback jolt, per §4.1

**Option 3 — Reframe as HOW, not WHAT.** The construct grammar already has slots.
Some of these are better modelled as *delivery* modifiers on another kamu than as
standalone kamu: "Fire, delivered as a Bolt" is a better sentence than "Bolt" and
"Fire" as separate rolls.

**Recommendation:** Option 2 for the elements, Option 1 for `bolt`/`burst`
specifically (they're the same case arm; that's a bug, not a design). Option 3 is
the most elegant and the most disruptive — worth a separate doc if it's ever
seriously considered, because it changes the slot model.

Worth noticing that Option 3 is **what §9.3 did to Bloom** — took a kamu whose
identity was really a delivery pattern and promoted it to being the delivery. If
that reframing works as well for `bolt` and `burst` as it did there, Option 3 is
less disruptive than it looks.

### 4.5 Niches still empty — future augment candidates

Vanilla mechanics with an unclaimed mechanical identity:

**There are none left, and that is the finding.** Every candidate this section
once held was either a duplicate of an existing augment or failed §2's test. The
list is kept as a record so the same ideas don't get re-proposed.

| Was | Verdict |
|---|---|
| **Weight** | **Merged with Rime into `Ice`.** They were the same augment twice — in Minecraft, cold *is* the slow, so "freezing damage that slows" and "slowness" were never two ideas. Rime was also an obscure word for a plain effect, so the merged kamu takes the name a player can read. It keeps the frost reactions (`shatter`, `thermal_shock`, `melt`) that §1.4 requires survive. |
| **Frailty** | **Folded into Poison**, which is now simply "applies poison." Vanilla poison already weakens the target; a separate weakening augment was describing poison's own side effect as if it were a new idea. |
| **Heave / Lift** | **Both cut — Lightning's jolt covers displacement.** Once Lightning delivers a knockback with every hit, a dedicated knockback augment is a utility kamu with no damage and no reason to exist. With chaining also cut, displacement is now Lightning's *entire* secondary effect, which makes the merge cleaner still. |
| **Hollow** | **Cut.** Hunger and sprint-drain only apply to players. In a mod whose subject is boss fights, an augment inert against 95% of what you fight is a trap pick, not a niche. |
| **Absorption** | **Moved to the aura system** (§9.4). It grants absorption — a state, not an event — which puts it alongside Heal, not in the construct roster. It was previously listed in both, which was a contradiction. |
| **Swift** | **Cut.** Flat movement speed is magnitude, not identity — the same failure that cut Fortune. |
| **Blink** | **Cut — it fails §2's symmetry test.** Caster-mobility reads fine on a player and is either useless or infuriating on a boss, so it could never be one vocabulary pointed both ways. A boss that should teleport gets an enderman as its base mob (§5.6) — that is what the mob-type knob is for. |
| **Bloom** | **Promoted out of the roster: it is a mechanic, not a kamu.** "Hits everything close" is a delivery pattern, and §9.3 makes it *the* delivery pattern for every bane aura. Leaving it in §4.1 as well would have one word meaning two things — the exact confusion this pass exists to remove. |
| **Chain** | **Cut, and chaining with it.** Lightning briefly carried a chain-to-nearest behaviour; that is gone too. `Splash` (§4.4) already hits everything close, which is near enough to chaining that keeping both was one multi-target idea expressed twice — and Splash expresses it in the delivery slot, where multi-target belongs. Lightning keeps only the jolt. |
| **Echo** | **Cut.** "The same thing again, weaker" is a multiplier wearing a mechanic's clothes, and it fails §2 for the same reason. |
| **Blind** | **Cut.** Aggro loss overlaps the stealth territory already moved out of this mod (§9.4), and accuracy reduction is not a thing vanilla mobs meaningfully have. |

**The rule this list kept rediscovering, and the reason it is now empty:** before
adding an augment, check whether an existing one's escalation path already covers
it. Most "new" identities turn out to be tier 3 of something already on the
roster. The roster in §4.1 is the roster.

---

## 5. Boss encounters

### 5.1 The one change that matters most

**Make the boss run the construct engine.**

Give the boss a brain, ticked from `BossHost.onTick`, that on a cooldown selects
one carried kamu and fires it at its target through the same `EffectsMc.apply`
path the player's totem uses in `DamageFunnel.java:140-144`.

Everything falls out of this:

- A Ice-bearer actually chills you. A Poison-bearer actually poisons you.
- A tier IV boss carrying four kamu has **four distinct attacks**, authored by
  `BossRoll`, with zero new ability code.
- The boss bar naming becomes the tutorial SPEC §7.1 claims it is.
- The drop becomes "legible / aspirational / scaling" as SPEC §7.2 claims — you
  wanted that kamu *because it was used on you*.
- Reactions teach themselves: a boss carrying Fire + Ice chains fire→frost into
  `thermal_shock` **on the player**, which is the fastest possible way to teach
  that reactions exist.

This is why §2's second corollary matters. One vocabulary, pointed both ways.

**Known hazards:**

- `EffectsMc.applyOne` takes `ServerPlayer source` and several arms assume it —
  `leech` healed `source` and `knock` pushed away from `source.position()`.
  Firing with a boss as source needs the signature widened
  to `LivingEntity` or a parallel path, or you get a boss healing the player it's
  attacking.
- Boss attacks damaging a player will proc that player's `Rebuke` aura (§9.3) —
  the construct's `on_hurt` trigger is gone as of §4.4. That's *correct and
  desirable*, but a boss's frost tick could machine-gun it, which is exactly what
  Rebuke's internal cooldown (§9.6 limiter 3) exists to stop. Confirm the cooldown
  is enforced on the receiving side, not just the sending side.
- Bosses drawing from the element+behaviour pool only (SPEC §7.1) means the boss
  vocabulary is a strict subset of the player's. Good — actions and triggers stay
  player-only, as designed.

### 5.2 Telegraph, or it isn't difficulty

Every boss special gets the same three beats, always in the same order, so the
fight is learnable (the Bosses'Rise lesson):

1. **Wind-up (~30 ticks, config).** Particles accumulate at the boss, matched to
   the kamu's element. Boss bar shifts colour. Boss stops advancing — the pause
   *is* the animation, and it's the one thing that reads clearly with no client
   mod.
2. **Sound.** One distinctive, rising cue per augment. This does most of the
   communicative work and should be picked deliberately, not late.
3. **Payoff.** The effect lands.

Without this, §5.1 is not a boss fight — it's unblockable chip damage arriving
from an invisible source. Ship them together or not at all.

### 5.3 Phases

At HP thresholds (66% / 33%, config), change the *rules*, not the numbers —
the Cataclysm lesson:

- Attack cooldown shortens.
- Knockback resistance **drops** while movement speed rises (the armour-shatter
  trade — the boss becomes more dangerous *and* more vulnerable, which is a far
  better feeling than "now it has more armour").
- Fires two kamu per cycle instead of one, which makes reactions between its own
  carried kamu start happening.

Announce it loudly: boss bar colour change, title text, a distinct sound. A phase
change the player doesn't notice is wasted work.

Tier I gets one phase break. Tier IV gets three.

### 5.4 Movement — real AI goals, no Mixin

`Mob.getGoalSelector()` is public in 26.2 (`Mob.java:1417`) and
`removeAllGoals(predicate)` at `1421`. So the vanilla melee goal can be stripped
and custom goals installed **through plain API — no Mixin required**, which keeps
`ballot` as the suite's only Mixin holder as PLAN_V2 §1 requires.

Highest-value goals:

- **A leap / charge that closes distance.** This kills bow-kiting, which is
  currently the dominant and only necessary tier-IV strategy.
- **A periodic reposition** so the fight isn't a corner-hug.
- **Face-nearest-player** (the datapack template's trick) so the boss never
  presents its back to a second attacker.

### 5.5 Minions

Tier III+ summons a small add wave on phase break. The tagging and cleanup
plumbing already exists — `BossHost.java:90` tags the boss entity and
`BossHost.cleanup` sweeps it; adds would ride the same tag.

Design note: adds should be *thematic to the carried kamu*, not generic. A
Poison-bearer summoning cave spiders reads as intentional; summoning zombies
reads as filler.

### 5.6 Mob type per tier

`boss.mob` is a single hardcoded config id (`Boss.java:96-98`). Making it a
per-tier list is nearly free and buys a lot: a tier-IV wither skeleton or ravager
reads as a different creature *before it does anything*. First impressions are
cheap here.

**This knob does more work than it looks like, and §2 leans on it.** Mob choice
is where boss-only behaviour belongs — the vanilla mob already brings its own
movement, attacks and quirks for free. A boss that should teleport is an
enderman; one that should charge is a ravager; one that should fly is a phantom.
That is why `Blink` could be cut outright (§4.5) rather than made boss-legal: the
shared kamu vocabulary stays symmetric, and asymmetric behaviour comes from
picking a different creature. **Reach for this list before reaching for a new
kamu.**

### 5.7 A boss that heals

Once Heal exists (§4.3), a boss that heals itself becomes available — and it's
the single best argument for Wither existing. "This boss regenerates; bring
something that stops that" is a real preparation decision, and preparation
decisions are what SPEC §7's blind-purchase sigil economy is trying to create.

Gate it to tier III+ so it never blocks the free daily.

### 5.8 Boss names — the register is wrong and should be funny

**Current state:** `Boss.buildBarName` joins the carried kamu display names with
commas in dark purple. A tier IV reads `Fire, Rime, Poison, Lightning`. That is a
*list*, not a name, and the register is solemn-mystical throughout.

**That register is wrong for Minecraft, and vanilla proves it.** Mojang's own
player-facing text is relentlessly unserious — the advancement list contains
"Sniper Duel," "Hot Tourist Destinations," "It's a Sign!" and "We Need to Go
Deeper"; there is a vindicator named Johnny as a permanent easter egg; the death
messages are jokes. Vanilla's *code* is serious and its *player-facing strings*
are not. Aiming for solemn mysticism is the tonal mistake, not the meme register.

**And the aura system is already accidentally in the joke.** "Aura" in current
slang means presence/charisma — aura points, aura farming. `Bloom of Wither` is
already a bit. Leaning in costs nothing because the vocabulary arrived there on
its own.

#### The template

The target form, from the brief: **`<Mob epithet> of <Intensifier> <Aura>`**

> **Chicken Jockey of W Aura**

Which decomposes as:

| Slot | Source | Carries |
|---|---|---|
| Mob epithet | the boss's entity type (§5.6) | what it looks like |
| Intensifier | **the tier** | how hard it is |
| Aura | its aura (§9.9) | what it does to you |

**The intensifier should encode tier.** This is the part that makes the joke do
work instead of just being a joke — the player reads difficulty off the vibe word
before they read the health bar:

| Tier | Intensifier |
|---|---|
| I | `Mid` |
| II | `Aight` |
| III | `W` |
| IV | `Goated` |

"Mid" for the free daily is self-aware in the right way.

#### Word lists

Mob epithets, keyed to entity type so §5.6's per-tier mob selection feeds this:

| Mob | Epithets |
|---|---|
| Zombie | Chicken Jockey · Zombie With a Job · Unemployed Zombie |
| Skeleton | Bone Daddy · Skeleton Who Peaked in High School |
| Creeper | Sussy Creeper · Creeper Aw Man |
| Wither Skeleton | Wither Skeleton of All Time |
| Piglin | Piglin Who Owes You Money |
| Enderman | Enderman of Culture · Enderman (He's Just Like Me Fr) |
| Vindicator | Johnny's Cousin |
| Ravager | Certified Ravager |

Aura names in meme register — and note that two of these describe the mechanic
*better* than the serious name does:

| Mechanical | Meme | Why it works |
|---|---|---|
| Bloom | **Bloom** | Keep it. It pulses like a beacon and the word already says so. |
| Focus | **Locked In** | "Locked in" means standing still and concentrating. That is literally the charge meter. |
| Momentum | **Zoomies** | Charges while you move. |
| Rebuke | **Uno Reverse** | Damage comes back at the attacker. Exact. |

Modifier names in meme register:

| Mechanical | Meme |
|---|---|
| Wither | No Heals · Skill Issue |
| Lightning | Zeus Wannabe · **Yeet** |
| Ice | Chill Guy · Touch Grass |
| Heal | Rent Free · Healing Arc |
| Absorption | Built Different |
| Poison | Food Poisoning |
| Fire | This Is Fine |

`Ice Guy` for Ice and `Yeet` for Lightning are the standouts — both are accurate
mechanical descriptions that happen to be funnier than the serious word.

#### The one real cost, and the fix

**SPEC §7.1 makes the boss bar the mod's only tutorial** — "a player who fights a
boss carrying Rime learns what Rime does before they ever own it." A pure meme
name breaks that: `Chicken Jockey of W Aura` tells you nothing about the fact that
it carries Fire and Poison.

Don't choose between them. **Funny title, mechanical subtitle:**

```
  Chicken Jockey of W Aura          <- boss bar, gold
  Fire · Poison · Wither               <- second line or bar suffix, purple
```

The joke lands and the tutorial survives. This is also just how vanilla does it —
the advancement is called "Sniper Duel," and the description underneath tells you
it means killing a skeleton from 50 metres.

#### Memes rot — so put the words in config

The obvious objection is that this ages badly; chicken jockey is a 2025 movie
meme and will be a fossil. The answer is architectural, and it matches §1.4's
config-first principle: **the name generator is a template plus word lists in
JSON, not strings in `Boss.java`.** Then the register is a content decision
refreshable without a rebuild, the serious mechanical vocabulary stays stable in
code and config keys, and a server that wants the solemn version just ships
different word lists.

**Keep the mechanical names in code and config keys.** `Focus`, `Rebuke`, `Wither`
stay as ids. The meme layer is presentation. That way the joke is swappable, the
system is greppable, and nobody has to write `aura: "locked_in"` in a config
file three years from now.

#### Where else the register applies

This is a player-facing-text decision, not a doc decision. Design docs stay
plain — that is what they are for. But the same treatment is available for the
refusal strings, the phase-break announcements (§5.3), the discovery book, and
the quest text. A phase break that announces **"he's locked in"** is doing the
same job as a solemn one and doing it better.

---

## 6. Reactions under the new vocabulary

Reactions are the best thing in the mod and the new augments should extend the
table, not sit outside it. Candidates:

| A | B | Outcome | Rationale |
|---|---|---|---|
| `wither` | `heal` | **suppressed** — Heal does nothing | The clean opposition. Teaches counterplay in one interaction. |
| `wither` | `heal` | Lifesteal ratio slashed | Consistent: Wither is anti-recovery, whatever the source. |
| `fire` | `wither` | **cauterise** — burst damage, Wither cleared | Fire burns out corruption. Gives Wither a counter so it isn't oppressive. |
| `poison` | `ice` | **congeal** — poison stops ticking but duration freezes and resumes | Weird, memorable, cheap. |

**`shatter` has to be rekeyed, and it lands better than before.** The existing
rule is `frost` + `knock`, and cutting Heave removes `knock` as a kamu — so
`shatter` would break. Rekey it to **`ice` + `lightning`**: Lightning's jolt (§4.1) is
the impact, and a frozen target struck by a jolt shattering is a cleaner reading
than a frozen target shoved. It also means Lightning's jolt isn't cosmetic — it's load-
bearing for the reaction table.

That consumes the `lightning` + `ice` pair, so the previously proposed "conduction
through frozen targets" rule is dropped. One reaction per pair; `shatter` is the
better of the two.

Existing rules to keep otherwise untouched: `thermal_shock`, `melt`, `conduct`,
`toxic_flame`.

**`tainted` also has to be rekeyed.** The existing rule is `leech` + `venom`; with
Leech folded into Heal and `venom` renamed, it becomes **`heal` + `poison`** —
healing corrupted by poison, which is what the name always meant.

---

## 7. Open questions

*Construct-side and boss-side. Aura-side questions live in §9.11 — the two lists
are kept apart because they gate different stages in §8, not because the topics
are unrelated.*

1. **Does the catalog still have enough entries for the drop economy?** SPEC §7.2
   leans on pool size for duplicate rates, and this doc has been a net *subtractor*:
   merging `bolt`/`burst`, cutting Frailty, Lift, Hollow, Thorns, Swift, Echo,
   Blind, Blink and Chain, promoting Bloom out of the roster into
   the aura mechanism, and moving Absorption and Heal out of the construct roster
   entirely, merging Rime with Weight into Ice, and cutting Heave once Lightning took over displacement. Wither is the only addition. **The boss pool is smaller than SPEC
   §7.2 assumed** and duplicate
   rates will rise accordingly. Either accept it (duplicates are tradeable, which
   SPEC §7.2 already calls the whole duplicate design) or re-tune. This subsumes
   §9.11 #13.
2. ~~**Are Wither and Heal boss-eligible?**~~ **RESOLVED by §9.9's blacklist.** Heal
   yes — that's §5.7. Wither is tier-capped rather than banned, for exactly the
   reason this question raised.
3. ~~**Is `blink` on a boss a good idea or a nightmare?**~~ **RESOLVED — Blink is
   cut** (§4.5). It failed §2's symmetry test, and boss teleportation is now a
   mob-type decision: use an enderman. The original question follows, for the
   reasoning that produced the cut.
   <br>~~It's caster-mobility, and
   a teleporting boss is either great (Custom Mobs Framework's teleport-behind) or
   infuriating. Currently `blink` is `HostType.TOTEM` only, which sidesteps this.
   Worth revisiting deliberately rather than by default.
4. **How much does telegraphing cost in packet volume?** 30 ticks of particles per
   attack per boss, with multiple bosses active, on a server. Probably fine;
   should be measured, not assumed.
5. **Do phases interact badly with the sigil refund?** A boss despawned at 20% HP
   on logout refunds a full sigil (`BossHost.refund`). Fine today. With phases,
   the player has consumed most of the fight's content. Chosen failure direction
   is player-favourable and should probably stay, but it's now exploitable —
   worth a look.
6. **Does the daily boss stay solo-beatable?** SPEC §7.1 says tier I is "the free
   daily, still beatable solo." Adding real attacks, a phase break and a closing
   charge could quietly break that. Tier I should probably get telegraphs and one
   phase but *not* minions or a second kamu per cycle.

---

## 8. Rough sequencing

Not a commitment, just the dependency order.

| Stage | Work | Why here |
|---|---|---|
| **1** | §5.1 boss brain + §5.2 telegraphs | Together they *are* the fight. Either alone is worse than neither: brain without telegraph is unfair, telegraph without brain has nothing to announce. |
| **2** | §4.4 fix the redundant damage effects | Cheapest large gain in perceived variety, and it makes stage 1's boss movesets actually differ from each other. |
| **3** | §4.2 Wither + §6 its reactions | New identity with a real mechanical niche. Note §4.3 Heal is superseded — see below. |
| **4** | §9 auras: the four auras, modifiers, magnitude budget, second face on the totem | Where Heal actually belongs. Rides the existing totem item and charge economy, so it adds no new plumbing. |
| **5** | §5.3 phases + §5.4 AI goals | Turns an enemy into a boss. |
| **6** | §9.7 the aura→construct tag crossover | Needs both systems live and both tuned before the combo layer is safe to add. |
| **7** | §5.5 minions, §5.6 per-tier mobs, §5.7 healing boss, §9.9 boss auras | Depth and texture. |
| **7b** | §5.8 boss name generator + meme word lists in config | Can land any time after §5.6 — it keys off mob type. Cheapest perceived-quality win in the doc; it's word lists and a template. |
| **8** | §4.5 further augments, §9.8 the stationed-totem shrine | Only once both identity models are proven in play. |

**Supersession note:** §4.3 proposed Heal as a construct augment with three
framings. §9 answers that question better — Heal is the canonical aura modifier,
and "regeneration-over-time" (§4.3 Option C) survives as its behaviour while the
reactive framing (Option A) and the ally-heal framing (Option B) are
replaced by the `Rebuke` and `Bloom` auras respectively. §4.3 is
kept for the reasoning, not the conclusion.

---

## 9. The second system — Auras

**Premise:** healing and other continuous effects do not belong in the construct
grammar. A second, deliberately simpler system carries them — **the Aura**: one
aura and one modifier. Two parts, no trigger, no chain.

### 9.1 Why a second system rather than more kamu

The construct grammar is built for *events*. A delivery plus two modifiers
resolves on a hit or a kill, burns through `DamageFunnel`, and costs charges.
Everything about it assumes a discrete moment.

Healing, protection, and utility have no moment. Cramming them into a four-slot
event grammar produces awkward results — every delivery in §4.4 is defined
relative to a hit, and "regeneration, delivered as a Hit" is nonsense. Forcing
a continuous effect through a delivery slot makes the player build a Rube Goldberg
construct for something that should just be *on*.

So the design language splits cleanly:

| | **Constructs** (existing) | **Auras** (new) |
|---|---|---|
| Grammar | DELIVERY + 2 modifiers — 3 slots (§4.4) | SHAPE + 1 modifier — 2 slots |
| Tense | Something **happens** | Something **is true** |
| Question | "What happens when I hit?" | "What is true while I stand here?" |
| Structure | delivery + content | delivery + content — *the same architecture* |
| Timing | Discrete, event-driven | Continuous, ticked |
| Combination | Reaction chains, ordering matters | None — flat, one pair |
| Cost | Charges, burned on death | Magnitude budget (§9.6) |
| Part of speech | **Verbs** | **Adjectives** |

Constructs are what you *do*. Auras are what you *are*. A player who owns both
should be able to state their build in two sentences, not one paragraph.

**Two grammars, mostly one vocabulary.** The split above is about *grammar* —
event vs. continuous — not about needing a second set of nouns. §9.4 reuses most
of the §4 augment roster directly: Fire, Ice, Poison, Lightning, Heal and
Absorption each already
have an identity from §4.1, and that identity is the aura reading too.
A player who has learned what Fire means at the construct level has already
learned what `Bloom of Fire` means. This is the same "one vocabulary, pointed
both ways" idea §2's second corollary uses for player vs. boss — applied here to
construct vs. aura instead.

### 9.2 The grammar

```
  [ SHAPE ]  +  [ MODIFIER ]
   who/where     what's true
```

That's the whole thing. `Focus of Absorption`. `Bloom of Wither`. `Focus of Heal`.
The name of the aura *is* the two parts read aloud, which is the same property
that makes constructs legible.

**The modifier carries polarity, not the aura.** An earlier draft gave each aura a
BOON/BANE affinity and split the radius aura in two (Nimbus for allies, Pall for
enemies). That was one distinction too many. A radius aura does not need to know
whether it is friendly — the modifier already knows. `Heal` is obviously
for allies; `Wither` is obviously for enemies. Let the modifier decide who it
lands on and the aura becomes pure delivery.

This is a real simplification, not just fewer rows: it removes the
`Aura.accepts(Modifier)` validation layer entirely. There is exactly **one**
illegal pairing left (§9.4), which a single check covers.

### 9.3 The four Auras

**"Aura" is the name of the whole passive system**, the way "Construct" names the
active one. The four options in the first slot are *auras*:

| Aura | How it delivers | Fantasy |
|---|---|---|
| **Bloom** | **Pulses on an interval** to everything in range — allies, enemies, or both, depending on the modifier | "Stay close. Or don't." |
| **Focus** | You only. Strength is a **meter** that charges while you stand still and drains as you move | "I hold." |
| **Momentum** | You only. The **inverse meter** — charges while you move, drains when you stop | "Don't stop." |
| **Rebuke** | Discharges when something damages you | "Touching me was a choice." |

**There is no always-on aura, and that is deliberate.** An earlier draft had
`Self` — flat, permanent, unconditional — and it was the worst entry on the list
precisely because it asked nothing. Every aura now has a condition you play into:
a rhythm to time, a stance to hold, a pace to keep, or a hit to take. The passive
system stopped being passive.

Cutting `Self` also removes the balance trap it created. Focus and Momentum had to
be tuned *above* a free permanent baseline or nobody would ever take them, which
meant either they were overtuned or Self was the default forever. With Self gone,
Focus and Momentum are compared against each other, which is the comparison that
actually has a decision in it.

**These are the mechanical names — ids, config keys, code.** The player-facing
register is a separate, funnier layer defined in §5.8: `Focus` displays as
**Locked In**, `Momentum` as **Zoomies**, `Rebuke` as **Uno Reverse**. Keep the
ids stable regardless of what the display layer is doing.

**Cut, and why.** `Self` per above. `Nimbus` and `Pall` merged into `Bloom` — a
radius aura does not need to know whether it is friendly, because the modifier
already does. `Wake`, `Tether` and `Bulwark` were distinctions the player would
have to learn without a matching gain in what they could express. `Cairn` was cut
as too expensive: an unattended, ticking, area effect that survives logout drags a
hard cap, a lifetime and chunk-unload semantics along with it.

#### Bloom — the beacon model

**Bloom pulses. It does not tick continuously.** Every interval it fires once at
everything in range, with a wind-up before each pulse.

The vanilla precedent is the **beacon**, and it is worth following closely: a
beacon does not hold an effect on you, it re-applies one periodically to
everything in its radius. Players already understand that shape, it already reads
at a distance, and it costs nothing to explain.

| Tier | Interval |
|---|---|
| 1 | ~30 s |
| 2 | ~22 s |
| 3 | **~15 s** |

**Tier buys frequency, not power.** That gives the fusion ladder (SPEC §5.7) an
obvious job on the aura face, and it gives balance a single honest lever: **if
Bloom is too strong, raise the interval.** One number, no re-tuning of effects,
no cascade into the construct system.

**Unlike an earlier draft, Bloom pulses for boons *and* banes alike.** The
previous rule — banes pulse, boons hold — created two behaviours where the beacon
gives one. A pulsed buff is exactly what players expect from a beacon, and it
keeps `Bloom of Fire` (allies resist, enemies burn) as a single coherent event
rather than one continuous effect stapled to one periodic one.

**Every pulse is telegraphed**, and the argument is §5.2's argument about boss
attacks. A silent recurring area effect is unreadable — it happens to you, you
cannot react, and the only decision is "leave eventually." A telegraphed pulse has
a rhythm: wind-up particles, a sound, then the payoff. Range becomes a timing
problem, and someone fighting a Bloom-bearer can dive between pulses or back off
as one charges. **The telegraph budget already exists** — the same
particles-then-sound-then-payoff pattern §5.2 defines, reused unchanged.

This lands §9.9 properly too. A boss with a continuously-ticking hostile field is
a tax on the whole fight. A boss whose pulse lands every fifteen seconds on a
visible wind-up is giving the player something to play around.

#### Focus and Momentum — the two meters

Both are self-only, both scale their strength by a charge meter, and they are
exact inverses:

| | Charges | Drains |
|---|---|---|
| **Focus** | standing still | moving |
| **Momentum** | moving | standing still |

A meter rather than a switch, in both cases. Binary would make the aura flicker
and punish a single step; a meter degrades gracefully, rewards *committing* to a
stance rather than freezing or twitching, and gives the player something to read.

| | Behaviour |
|---|---|
| Fill time | ~4–5 s of the favoured state (config) |
| Drain time | ~2 s of the opposite state (config) — faster than it fills, so switching genuinely costs |
| Strength | proportional to charge; 60 % charged is 60 % of the effect |

**They must be balanced against each other, not against a baseline**, since there
is no baseline any more. Same ceiling, same fill and drain rates, opposite
triggers — the choice is about how *you* fight, not which number is bigger. A
player who holds ground takes Focus; a player who kites takes Momentum. Neither is
correct.

Two consequences worth noting:

- These are the mod's first real **skill expression**. Every other aura is
  set-and-forget once chosen; Focus and Momentum ask the player to read the fight
  and decide what kind of fight it is.
- Momentum resolves the mobility gap left by cutting Blink and Swift. It doesn't
  grant movement — it *rewards* it, which is better, because it makes the player's
  own positioning the thing that powers the totem.

#### Rebuke — the reactive

Discharges its modifier when you take damage. Boon modifiers land on you; bane
modifiers land on the attacker.

- `Rebuke of Absorption` — struck, so you harden. The classic panic button,
  automated.
- `Rebuke of Heal` — struck, so you heal a burst.
- `Rebuke of Fire` — struck, so the attacker burns.
- `Rebuke of Ice` — struck, so the attacker is slowed and cannot chase.

**This overrides §9.10's "no triggers" rule, deliberately and narrowly.** The rule
existed to stop the aura system growing a second WHEN slot and turning into a
worse copy of the construct grammar. Rebuke does not do that: **the trigger is
baked into the aura and is not selectable.** There is no WHEN slot, there is no
list of events, there is one reactive delivery among four. The system is still two
slots.

**Rebuke is now the only "when you're hit" mechanic in either system.** It used to
overlap the construct's `on_hurt` trigger; §4.4's slot merge cuts that trigger and
hands the whole niche here. Two things fall out of it:

- The distinction that had to be argued for is now structural. Rebuke turns on a
  *state*; there is no competing mechanic that fires a discrete effect on being
  wounded, so nothing has to stay crisp by discipline.
- It retires a live bug rather than fixing one.
  `DamageFunnel.resolveFor(victim, victim, "on_hurt")` passes the victim as both
  source *and* target, so a hostile modifier on that trigger currently lands on
  the player. That code path goes away with the trigger.
### 9.4 Modifiers — mostly the same kamu as §4

**Most of the attack roster works as an aura modifier.** A kamu that means
something as a WHAT effect almost always means the *same thing, held constant*
as an aura modifier — Fire burns on a hit and burns continuously; Ice slows on
a hit and slows continuously. Rather than author two vocabularies, the aura
system draws its modifiers from §4's augment roster wherever the identity
survives being made continuous, and only invents a new word where nothing in §4
covers the niche.

**One definition per kamu, shared by both systems.** The aura layer does not get
its own description of what Wither does — Wither is defined once, in §4.2, and the aura
reading is that same definition held continuous. §4.1 already gives every augment
a one-sentence identity ("Get out of there," "You are becoming weaker," …); that
sentence is the aura reading too, in the present tense instead of the moment.

So the table below deliberately **records only what the aura layer adds**, which
is polarity — who the effect lands on. For what each one *does*, follow the
reference. Restating it here would create a second source of truth that drifts
the first time a kamu is retuned.

| Kamu | Polarity | Defined in |
|---|---|---|
| **Fire** | Dual | §4.1 |
| **Ice** | Dual | §4.1 |
| **Poison** | Dual | §4.1 |
| **Lightning** | Dual | §4.1 |
| **Wither** | Bane | §4.2 |
| **Heal** | Boon | §4.3 |
| **Absorption** | Boon | §4.1 |

**Dual** means the §4 identity splits cleanly into a friendly and a hostile half —
Fire is fire resistance one way and burning the other. **Bane** means it only
reads pointed outward. The resolution table below turns polarity plus aura into
who actually gets hit, and §9.3's bloom rule turns polarity into *how* — banes
pulse, boons hold.

**Heal and Absorption are the Boon-polarity kamu**, and they are boons in both
systems — they return something to you rather than doing something to a target.
Everything else on the §4 roster is offensive by construction, which is why the
table is mostly Dual and Bane.

**Lightning joined this list once its chain was cut.** A chaining burst needed a
single origin moment and so had no continuous reading; a *jolt* has one — pulsed,
it shoves everything in range away from you, and held, it means you cannot be
shoved. That gives it both halves and makes it Dual like the other elements.

Its friendly reading also restores a niche that went empty when Heave was cut:
**knockback resistance and no fall damage**. Footing is back, without a word of
its own.

**This is also the answer to open question #14.** Polarity is a property *of the
kamu*, not a separate aura-side registry: one `Kamu` record gains a polarity field
alongside `allowedHosts`, and the aura system reads it. There is no second catalog.

**Construct-only — deliberately no aura reading.** This table is the exception to
the rule above: it records *reasoning about the aura layer*, which genuinely lives
here and nowhere else.

| Kamu | Why it doesn't translate |
|---|---|
| **Lightning** | The friendly reading was damage reflection, which is cut from the design entirely (below). The hostile reading is closer than it first looked — §9.3's bloom *is* the discrete jolt a chain needs to travel, so "every few seconds, a jolt chains between enemies in range" is coherent in a way it wasn't before the bloom existed. It still doesn't earn a slot: chaining *within* a radius, when the bloom already hits everything in that radius, is a distinction without a difference. Lightning's propagation only means something when there's a single origin point — a hit. |
| **Pierce / Bolt / Burst** | These are the `hurt()`-with-a-constant cluster §4.4 already flags as needing real identities. A passive "deal damage over time" aura is the exact magnitude-without-identity mistake §1.1 opens the whole doc with — giving them an aura reading now would just relocate the problem. Revisit only after §4.4 gives them one. |

**The pattern behind every cut above: damage-shaped kamu have no aura reading,
because damage is the delivery, not the content.** Lightning, Pierce, Bolt,
and Burst all failed the same way — asked for a continuous reading, each
one becomes "damage things near me on a rhythm," and §9.3's bloom *is* that. It
is the mechanism by which any bane lands, so a kamu whose whole identity is
"deals area damage" has nothing left to contribute once the mechanism exists.

This is §1.1's finding resurfacing one layer up. Nine of fifteen construct effects
were `hurt()` with a different constant; the aura layer compresses them even
harder, because a continuous effect can't differentiate itself by *timing*,
*range* or *delivery* the way a discrete one can — those are the aura's shape and
its bloom, not its modifier. **Useful rule going forward: if a kamu's aura reading
is "deals damage," it doesn't have one.** The aura system wants states; the bloom
supplies the damage.

**Damage reflection is cut from the design.** Reflect punishes the player for
taking the action the game is asking them to take, with no read and no
counterplay — and server-side, with no client feedback, there is no honest way to
communicate that it happened. `Thorns` is removed from §4.5's candidate list and
from the aura roster, and Lightning's friendly reading is gone with it. Nothing else in
either system depends on it.

**Aura-only — there is nothing left in this bucket.** Every modifier in the aura
system is now a §4 kamu with a construct reading as well. `Absorption` was the
last aura-only entry, and giving it a construct reading (hitting things shields
you, §4.4) moved it out.

That is the strongest form the merge argument can take: **the second system costs
zero new vocabulary.** A player learns six kamu, and those same six kamu are the
whole aura roster too. Nothing exists that only makes sense in one place.

**And there is no exception in the other direction either.** `Lightning` was the
last construct-only kamu; cutting its chain gave it an aura reading (§9.4), so
now **all seven kamu work in both systems.** One roster, two grammars, zero
special cases. That is as far as the merge can go.

**Three. That is the whole cost of the second system in new vocabulary,** and it
is the strongest argument for the merge in §9.4. An earlier draft of this table
carried Breath, Sight, Quiet, Dread and Fortune as well — water breathing, night
vision, stealth, spawn suppression, drop bonus. All five are cut:

- **None of them changed a fight.** They were utility riding in a combat system,
  and §2's test ("what changes about the fight when I equip this?") has no good
  answer for night vision.
- **The suite has better homes for them.** `wondrous`, `wayfarers` and
  `spiritwolves` are where exploration, travel and stealth actually live. Per
  DESIGN.md §3, those mods can carry these ideas without either side depending on
  the other, and they will fit better there than as a peacetime annex bolted to a
  boss-fight mod.
- **Fortune was already the weakest entry** — a flat multiplier that duplicated
  the Fortune and Looting enchantments and failed §2's test outright. It goes
  with them.

The cost of this cut, stated honestly: **the aura system no longer gives the mod
a peacetime identity.** An earlier draft leaned on that as a selling point. It
isn't one any more — auras are now purely a combat system, and the mod stays 100%
combat until something else changes that. That is the right trade for scope, but
it should not be quietly forgotten.

Resolution by shape — unchanged by the merge:

| | Bloom (pulsed radius) | Focus (still) | Momentum (moving) | Rebuke (on hurt) |
|---|---|---|---|---|
| **Boon** | you | you + allies | you | you |
| **Bane** | *illegal* | enemies | *illegal* | the attacker |
| **Dual** | friendly half | **both halves** | friendly half | hostile half, on the attacker |

**Bane + Focus and Bane + Momentum are the only illegal pairings** — a self-targeted
curse. One check covers it, and the panel can simply grey those out. That is the
entire validation surface for the system, and the vocabulary merge doesn't change
it — Wither was already the only bane-only entry.

**The tuning consequence of sharing a kamu across two grammars.** A dual-purpose
kamu is now balanced on two different axes at once — its construct power (burst,
per-hit, gated by charges) and its aura power (sustained, always-on, gated by the
§9.6 magnitude budget). These should not be the same number pulled from the same
config field. `Fire`'s ignite-on-hit duration and `Bloom of Fire`'s ambient
damage-over-time need independent tuning knobs even though they're the same
`effectId`, or buffing the construct for PvE accidentally buffs the aura for
everyone standing near a fire-build player. Worth deciding early whether this is
one kamu definition with two power fields, or one kamu definition plus a
per-context multiplier — the latter is less config surface and probably the
better default.

### 9.5 The matrix

4 auras × 7 modifiers — every one of them already familiar from the construct
side — is a wide combination space built from very little genuinely new content.
A sample, still name-themselves:

| Pair | Reads as | Does |
|---|---|---|
| `Focus of Absorption` | "I hold this line" | Absorption that builds the longer you stand your ground — the tank build |
| `Focus of Heal` | "Let me catch my breath" | Between-fight recovery; makes disengaging and planting a real mid-fight play |
| `Momentum of Heal` | "I heal by running" | Sustain that rewards kiting — the exact inverse build, and the reason both exist |
| `Momentum of Absorption` | "Catch me first" | Hit-and-run tank; hardest while repositioning, softest when cornered |
| `Momentum of Fire` | "Fire is not my problem" | Fire resistance while moving — the lava-and-Nether traversal build |
| `Bloom of Heal` | "Stand near me" | The party heal, pulsed like a beacon — no support augment needed |
| `Bloom of Wither` | "Nothing heals near me" | Anti-regen field — shuts down healing bosses (§5.7) |
| `Bloom of Fire` | "Fire knows whose side it's on" | Dual — every pulse resists for allies and ignites for enemies |
| `Bloom of Ice` | "Nothing escapes me" | The melee-catcher — each pulse re-slows anything still in range |
| `Bloom of Poison` | "The air is bad here" | Pulses poison on the interval; anything that lingers gets sicker |
| `Bloom of Lightning` | "Back off, all of you" | Pulses damage and shoves everything in range away — the get-off-me build |
| `Focus of Lightning` | "I will not be moved" | Knockback immunity and no fall damage while planted — the footing build Heave used to cover |
| `Rebuke of Absorption` | "Struck, so hardened" | Automated panic button |
| `Rebuke of Fire` | "Touching me burns" | Passive melee deterrent |
| `Rebuke of Ice` | "You will not chase me" | Disengage tool — the counter to being swarmed |
| `Rebuke of Lightning` | "Hit me and be thrown" | Struck, so the attacker is knocked back — the panic-space button |
| `Rebuke of Wither` | "Wound me and forget healing" | Struck, so the attacker's regeneration and incoming heals shut off for a window |

**Every one of these answers a combat question.** That is a deliberate narrowing:
the utility/exploration modifiers this table used to carry — water breathing,
night vision, stealth, spawn suppression — are cut. They were the weakest
justification for the system, because none of them changed a fight, and the suite
has better homes for them (`wondrous`, `wayfarers`, `spiritwolves`) where
exploration is the actual subject. Auras earn their place here by changing
encounters, not by being a second enchantment table.

### 9.6 The magnitude budget — how this stays balanced

An always-on effect with no limiter is free power. Constructs are limited by
charges burned on death; that lever doesn't fit something continuous.

**Every aura pays for its strength with a condition.** There is no free baseline
to measure against — that was `Self`, and cutting it (§9.3) is what makes this
table honest:

| Aura | What it costs you | Magnitude |
|---|---|---|
| **Focus** | mobility — you have to stand still | 0 % → **150 %** on the charge meter |
| **Momentum** | position — you have to keep moving | 0 % → **150 %** on the charge meter |
| **Bloom** | concentration and immediacy — spread over everyone in range, and only on the pulse | 55 % per pulse |
| **Rebuke** | uptime — it exists only in the moment after a hit | burst, on a cooldown (different unit — see below) |

**Focus and Momentum share a ceiling on purpose.** They are inverses, so the
choice must be about *how you fight*, not which number is bigger. If one is
stronger the other is dead. 150 % is the first guess; what matters is that they
move together whenever it's retuned.

**Bloom's real lever is the interval, not the percentage** (§9.3): 30 s at tier 1
down to 15 s at tier 3. That gives balance one honest knob — if Bloom is too
strong, raise the interval — without touching effect magnitudes or disturbing the
construct system. Prefer it to the 55 % figure when tuning.

Rebuke doesn't fit the percentage model because it isn't continuous. It wants two
knobs of its own: **burst size** and **internal cooldown**. The cooldown is the
real limiter — without one, a boss's fast attack sequence procs it every tick and
Rebuke is the strictly best aura in every fight.

A player who holds ground takes `Focus`. A player who kites takes `Momentum`. A
player who wants to help the team takes `Bloom` and accepts a pulse instead of a
constant. A player who expects to get hit takes `Rebuke`. Nobody has to read a
balance table to feel the tradeoff — each one names a way of fighting.

Secondary limiters, in preference order:

1. **One aura active at a time.** Simplest, strongest, makes the choice matter
   every session. Strongly preferred.
2. **Auras of the same modifier don't stack** between players — the strongest
   applies. Necessary anti-blob rule regardless of anything else.
3. **Rebuke has an internal cooldown**, per above. Non-optional.
4. Tier scaling via the existing `Slot.tier` 1–3 model, so the fusion ladder in
   SPEC §5.7 works on aura parts too without new machinery.

### 9.7 The crossover — this is the best idea in this section

**Auras set the environment. Constructs cash it in.**

`DamageFunnel.resolveFor` already builds a tag set from world and target state
before resolving — `burning`, `wet`, `frozen`, plus `raining` / `night` / `nether`
(`DamageFunnel.java:120-129`). `ReactionEngine` already keys off those tags:
`conduct` fires on `shock` + `tag:wet`.

So if an aura writes tags onto the entities inside it, the two systems talk
without merging:

- `Bloom of Fire` tags everything near you `burning` → your construct's `frost`
  now reacts into `thermal_shock` on contact.
- `Bloom of Ice` tags them `frozen` → your `lightning` jolt becomes `shatter`.
- A wet aura would make `shock` conduct on demand.

That turns the aura into **setup** and the construct into **payoff**, which is a
real combo system arising from two simple systems rather than one complicated
one. It costs almost nothing to build: the tag set and the reaction table already
exist and neither needs to change shape.

It also gives the aura system a combat identity that isn't just "passive stats,"
which is the failure mode every passive system falls into.

**Design caution:** this makes reactions reliable rather than opportunistic.
Right now `thermal_shock` fires when the world happens to cooperate. With an Aura
it fires *always*, which is a large effective damage increase. The reaction
multipliers in `EffectsMc` (`thermal_shock` at 3.0×) were tuned for occasional,
not constant, and would need revisiting.

### 9.8 Where auras live — DECIDED: the same totem

**Auras live on the Kamu Totem, as a second face of the same item.** No second
item, no new drop table, no new inventory slot. The Station gets a second panel
("Aura" alongside "Carving") and that is the whole delivery story.

This was originally listed as option B and argued against. That argument was
wrong, and specifically it was wrong about the thing that mattered most.

**What the decision buys:**

- **It resolves the death-penalty hole (§9.11 #2) outright.** A separate Charm
  item would have needed its own limiter or `TotemCharges` would have stopped
  meaning anything — an always-on aura surviving on a second item makes death
  free. On the same item, **the existing charge system covers both systems for
  nothing.** A dormant totem at zero charges drops its aura along with its
  construct. No new mechanic, no new bookkeeping, and the death penalty gets
  *stronger* rather than weaker.
- **One item to lose, one item to protect.** That's a clearer stake than two,
  and it makes the totem worth defending in a way a pair of items never would be.
- **It reuses everything.** `Totem.is`, `Totem.find`, `Totem.chargesRemaining`,
  the anvil-combine guard in `TotemCharges.tick`, the lore refresh in
  `Totem.refreshLore`, the imbue/remove economy, the Station routing — all of it
  already exists and already targets one item. A second item would have needed a
  parallel copy of each.
- **It keeps the integration surface at one item id.** INTEGRATION.md's whole
  premise is that another mod hands over an item id plus `custom_data`. One
  totem carrying both systems is one id to document, not two.
- **No inventory pressure.** The suite already competes for hotbar space.

**What it costs, and what to do about it:**

- *Power concentration.* One item now carries the mod's entire identity. Mitigate
  with the §9.6 limiter — one aura active — and by keeping aura magnitudes
  genuinely modest. The aura is the adjective; the construct stays the verb.
- *Losing a totem loses both systems.* This is now a feature, not a bug — see the
  death-penalty point above — but it raises the stakes on totem loss, so the
  charge curve and repair cost want re-checking once auras are in.
- *Two systems, one panel budget.* The Station hub already routes Carving,
  Fusion, Discoveries, Naming, Journal and Quests. Aura is a seventh entry.
  Watch that the hub doesn't become a wall of buttons; Aura probably belongs
  *inside* the Carving flow as a second tab rather than as a peer entry.
- *Trading.* A separate Charm would have traded independently, feeding the
  secondary market SPEC §7.2 wants. Aura kamu still trade as loose kamu before
  they're imbued, so the market survives — it just trades parts, not assembled
  charms.

**The Station variant survives, and gets better.** A totem *placed into* a Kamu
Station projects its aura as a `Cairn` around that station while it sits there.
Because there is no separate charm, stationing your totem means **giving it up
for as long as it's stationed** — you trade your carried power for held ground.
That is a far more interesting decision than slotting a spare charm into a block,
and it turns the station into the shrine the name always implied. Worth building
once the carried case is proven.

### 9.9 Bosses with auras

Auras are the answer to a problem §5 doesn't solve: how a boss changes the fight
*before it attacks*.

- A boss with `Bloom of Wither` means **you cannot heal in its arena.** That's a
  preparation decision, made before the fight, which is exactly what the blind
  sigil purchase in SPEC §7.3 is trying to create.
- A boss with `Bloom of Ice` means you cannot disengage — it directly kills the
  bow-kiting problem in §5.4 without needing a charge goal.
- A boss with `Bloom of Absorption` protects its own minions (§5.5), making the adds a
  priority target instead of a distraction.

**Not every modifier should be boss-legal.** The blacklist so far:

| Modifier | Why a boss may not have it |
|---|---|
| **Wither** | Tier-capped rather than banned. At tier IV, "you cannot heal" plus tier-IV damage is a difficulty spike of a different kind than intended (§7 open question #2). |

The general test: **a boss aura should change how the player fights, never punish
them for fighting.** `Bloom of Ice` says "you can't run" — a rule the player can
plan around. Anything that says "attacking costs you" has no play in it, which is
why reflect was cut from the design entirely (§9.4).

**Boss bane auras bloom, same as the player's** (§9.3), and this is what makes
them fair. A boss whose Poison pulses on a four-second wind-up is giving the
player a rhythm to read — dive between blooms, back off as one charges. The same
aura ticking continuously would be an unreadable damage tax on the whole fight.

Name the aura on the boss bar alongside the carried kamu, for the same tutorial
reason SPEC §7.1 gives. And a phase break (§5.3) that *changes the boss's aura*
is a rule change the player will feel instantly.

### 9.10 What deliberately stays out

Guarding against this system growing a second construct grammar:

- **No selectable triggers.** *(Amended.)* The original rule was "no triggers at
  all." `Rebuke` (§9.3) is a deliberate, bounded exception: the trigger is baked
  into the shape and cannot be chosen, so there is still no WHEN slot and no event
  list. The rule that actually matters is the one that survives — **the aura
  system never gets a slot whose job is to pick a moment.** If a fifth shape is
  ever proposed that fires on kill, or on block-break, stop: that's a WHEN slot
  wearing a disguise, and it's the construct grammar growing back.
- **No reaction chains between auras.** One shape, one modifier, flat. The
  crossover in §9.7 is with *constructs*, not between auras.
- **No third slot.** The moment there's a third slot this stops being the simple
  system and starts being a worse copy of the complex one.
- **No more shapes.** Four. The pressure to add a fifth will be constant and
  every individual case will sound reasonable; §9.3 already cut four that did.
- **No aura-only kamu tier ladder.** Reuse `Slot.tier` and the existing fusion
  economy.
- **No stacking auras.** §9.6 limiter 1.
- **No inventing a modifier that duplicates a §4 augment under a new name.**
  §9.4's merge exists specifically to stop the aura system from growing its own
  parallel vocabulary. Before adding a new aura-only word, check whether a
  construct-side augment already means it and would only need a continuous
  reading.

### 9.11 Open questions

1. **Do banes make the player a walking mob-griefer?** A `Bloom of Poison` poisons
   everything nearby, including villagers, pets, and other players' farms. Needs
   a target filter, and the filter needs to be config, and it will be argued about.
2. ~~**Do auras break the charge economy?**~~ **RESOLVED by §9.8.** Auras live on
   the totem, so `TotemCharges` already governs them: at zero charges the totem
   goes dormant and the aura stops with the construct. No new limiter needed.
   The only follow-on is that death now costs *more* than it used to, so the
   charge curve and repair cost want a re-check once auras are in.
3. ~~**Cairn persistence vs. server load.**~~ **RESOLVED by §9.3** — Cairn is cut.
   No unattended ticking effects, no chunk-unload semantics, no per-player cap.
   This was the most expensive item on the original list and it took the most
   open questions with it.
9. **Focus and Momentum's shared ceiling (§9.6).** 150 % is a guess. With `Self`
   cut there is no baseline to check it against, so the only real question is
   whether the two stay balanced *against each other* — if either is clearly
   better, the other is dead weight. Needs play, not reasoning.
10. **Does Rebuke's cooldown read to the player?** With no client mod there's no
    cooldown UI. An audible cue when Rebuke comes back up is probably the whole
    answer, but it's the kind of thing that's obvious to the developer and
    invisible to everyone else.
11. **Are the charge meters legible?** Focus and Momentum are the mod's first
    continuous state the player needs to *see*, and now there are two of them
    moving in opposite directions. Actionbar text is the cheap option; particle
    density at the feet is the pretty one. Probably both — and they must read
    differently enough that a player never mistakes which one they have.
4. **Does §9.7 make reactions mandatory?** If `Bloom of Fire` + `frost` is
   strictly better than any other build, the aura system has collapsed the
   construct system into one answer. Wants a deliberate anti-synergy or a
   diminishing return on tag-driven reactions.
5. **Is "one aura active" too austere?** It's the cleanest limiter, and with §9.8
   it's also nearly free — one totem, one aura face, one active aura. The cost is
   that every un-slotted aura kamu sits in a chest. Trading loose kamu (SPEC §7.2)
   absorbs some of that; watch it in play.
7. **Does the totem now hold too much?** One item carrying two systems is the
   direct consequence of §9.8 and the main thing to watch. If aura magnitudes
   creep, the totem stops being *a* build and becomes *the* build. The §9.6
   budget is the lever; keep it tight.
8. **Where does the Aura panel go in the Station hub?** Seventh top-level entry,
   or a second tab inside Carving? Leaning tab — the hub is already at six and
   PLAN_V2 §1 makes the hub the spine, not a directory.
6. **Do dual modifiers (§9.4) confuse more than they save?** The same item behaving
   oppositely is elegant to a designer and potentially baffling to a player who
   slots Fire expecting fire and gets fire *resistance*. Lore text has to carry
   this, and lore text is not read.
12. **Does sharing a kamu across both grammars double-count it in tuning passes?**
    §9.4 flags that a dual-purpose kamu needs independent construct-power and
    aura-power numbers even though it's one `effectId`. If they're accidentally
    read from the same config field, buffing Fire for PvE silently buffs every
    `Bloom of Fire` player standing near a campfire. Needs to be a deliberate
    config shape decision before the first dual-purpose kamu ships, not a bug
    found after.
13. **Merged into §7 #1** — the drop economy now has one question covering both
    the shrinking pool and the rising per-drop value, since they push the same
    number in opposite directions and have to be judged together.
14. ~~**Is a kamu allowed to be construct-only, aura-only, or dual by config?**~~
    **RESOLVED by §9.4.** Polarity (dual / bane / boon / none) is a property *of
    the `Kamu` record*, sitting alongside `allowedHosts`, and the aura system
    reads it. There is no second catalog and no second description — a kamu is
    defined once and both grammars share that definition. The follow-on is that
    `Kamu` gains one field and `KamuCatalog.defaults()` gains one column; the
    aura layer adds no registry of its own.

---

## 10. Sources

- [L_Ender's Cataclysm](https://modrinth.com/mod/l_enders-cataclysm)
- [Bosses'Rise](https://modrinth.com/mod/bossesrise)
- [Custom Boss Template (datapack)](https://modrinth.com/datapack/custom-boss-template)
- [Custom Mobs Framework](https://www.curseforge.com/minecraft/mc-mods/custom-mobs-framework)
- [Mowzie's Mobs — mob overview](https://www.sportskeeda.com/minecraft/all-mobs-minecraft-mowzie-s-mobs-mod)
- [Enemy Attacks and Telegraphing — Game Developer](https://www.gamedeveloper.com/design/enemy-attacks-and-telegraphing)
- [15 Best Minecraft Mods That Add New Bosses (2026)](https://8bittoast.com/minecraft-mods-bosses/)
