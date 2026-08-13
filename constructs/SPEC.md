# MrPinoy's Constructs — Build Spec

> **Status:** design specification, **nothing built**. No Minecraft signature in
> this document has been verified against the 26.2 merged jar yet — §16 is the
> list of things that must be checked *before* code is written, and every claim
> below that depends on one is marked ⚠. Claims inherited from
> `spiritwolves`/`cobblebending`, which were verified against
> `minecraft-merged-deobf-26.2.jar` on 2026-08-08, are marked ✅(inherited).
>
> **This mod retires two others.** `cobblebending` retires when §6 ships;
> `dailyquests` retires when §8 ships. See §20 for the migration.

---

## 1. The problem this solves

The suite has ten mods and no **collection** mechanic ([SUITE_AUDIT.md §5.2](../SUITE_AUDIT.md)
scores it *Absent*), one carrier for attachment, and a daily loop made of a
single 30-second riddle. It also has four of the nine sink shapes entirely
unbuilt ([DESIGN.md §5](../DESIGN.md)).

Constructs is **one interaction engine with three hosts**, and the three hosts
form a closed loop:

```
   boss drops an affix it was wearing  ──▶  affix slots into the relic
            ▲                                          │
            │                                          ▼
   diamonds buy more boss attempts        relic makes you strong enough
            ▲                                   for the next boss
            └──────────  dying burns relic charges  ◀──────────┘

   the daily quest chain gives you a reason to be logged in at all
```

**What each host is for:**

| Host | What it is | Suite job |
|---|---|---|
| **Relic** | Offhand item that modifies whatever attack you make, and doubles as a rechargeable totem | Sink (charges), power fantasy, the collection vault |
| **Boss** | Summonable elite with random affixes drawn from the same pool | Faucet (components), sink (attempts), the tutorial |
| **Quest** | Multi-segment daily chain built from the engine's triggers | Daily loop, streak |

**Sink shapes hit** ([DESIGN.md §5](../DESIGN.md) table): *destruction / risk* ✓
(charges burn on death), *rate-gate + pay to exceed* ✓ (one free boss a day,
diamonds for more), *randomized crafting* ✓ (affix rolls), *collection* ✓ — the
first in the suite.

> **The load-bearing rule: at zero charges the player is a vanilla player, never
> worse than one.** No debuff, no penalty, no component loss. The relic goes
> dormant and the player has lost a *bonus*. This is the difference between a
> regression to baseline and a death spiral, and it is what keeps a bad night
> from becoming a quit. See §6.4.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Mod id | **`constructs`** | The engine's central noun. `relics` was the alternative — warmer, but it names one host of three |
| Hosts in v1 | **Three: relic, boss, quest** | Each exercises a different half of the engine (§4) |
| Module split | **`core/` + `fabric/`** | The resolver is pure logic. [DESIGN.md §9.8](../DESIGN.md) |
| Slots per construct | **Three** | The accessibility constraint. Non-negotiable in v1 |
| Component pool v1 | **15** | 3 actions, 4 elements, 5 behaviours, 3 triggers (§5.4) |
| Relic charges | **16, `max_damage = 16`** | 1 diamond = 4 charges. The arithmetic is forced — §6.4 |
| Charges burn on | **Death only. Never ability use** | One number, one meaning |
| Component acquisition | **Boss drops one affix it was wearing** | Legible, aspirational, and teaches before it gives |
| Duplicate affixes | **Tradeable. No conversion** | `/pay` and the player economy absorb them for free |
| Free boss | **One per player per day, date-derived** | Same seed for everyone — a topic of conversation |
| Quest structure | **3 light segments, date-derived** | Per [dailyquests tuning](../dailyquests/) — light asks, higher base reward |
| Streak | **Preserved from `dailyquests`, with weekly grace** | Strongest retention primitive in the suite. Do not drop it in the migration |
| Client requirement | **None.** `"environment": "server"` | Non-negotiable |
| Mixins | **Zero** | If a host needs one, cut the host |
| Cross-mod | **None.** Sells via `shop.json` `components` | [DESIGN.md §3](../DESIGN.md) |

---

## 3. Core loop

1. Player buys a **Relic** from `/shop` — a vanilla item stamped with
   `custom_data`, worn in the **offhand**.
2. The relic starts with three empty slots and 16 charges.
3. Player summons the **free daily boss** (or buys a higher tier). The boss's
   affixes are announced by name on its boss bar — *this is where the player
   learns what components exist*.
4. Boss dies → drops **one of the affixes it was actually wearing**, as a
   component item.
5. Player slots the component into the relic. Now every attack they make —
   sword, bow, fists, a steak — carries it.
6. Player dies → the relic saves them instead of the death, and burns a charge.
7. At **0 charges the relic goes dormant**: no totem save, no attack
   modification, components intact. A vanilla anvil and diamonds bring it back.
8. Meanwhile the **daily quest chain** runs three light segments a day, paying
   diamonds and keeping a streak.

---

## 4. Architecture — the engine has two halves

This is the decision the whole mod rests on, so it goes first.

The three hosts do **not** use the engine the same way:

| Host | Leans on | Barely touches |
|---|---|---|
| **Relic** | Effects, reactions, slot ordering | Triggers — almost always "on hit" |
| **Quest** | Event matching and counting | Effects — the reward is currency |
| **Boss** | Both — affixes are effects, phases are triggers |

The naive model makes `Trigger` an optional field on `Construct`. That is
correct for the relic and **wrong for the quest**, where a segment is nothing
*but* a trigger plus a required count. Building it that way bolts a second,
parallel quest system onto the side of an effect engine.

**So `EventMatcher` is a first-class core concept**, used two ways:

```
EventMatcher                            used by
- EventId          ("entity_killed")    ─┬─ QuestSegment  (+ requiredCount, + progress)
- Predicate        (target = "zombie")   └─ Construct     (as its WHEN clause)
- RequiredCount    (int)
```

One matcher implementation, one set of tests, two hosts. The rest of the engine
is as the source specification describes it:

```
Minecraft Event
      ↓
IConstructHost          (relic / boss / quest — supplies context + applies results)
      ↓
Construct               (immutable during resolution)
      ↓
Resolver                (pure, deterministic, headless-testable)
      ↓
EffectContext
      ↓
EffectRegistry → ReactionEngine
      ↓
Minecraft World
```

**The core module must never import `net.minecraft.*`.** Enforced by an empty
`core/build.gradle.kts`, per suite convention — a stray import fails to compile
rather than quietly coupling the halves. `core` refers to Minecraft things as
**strings** (`"minecraft:zombie"`, `"minecraft:diamond"`), exactly as
`bounties` and `chatdonkey` do.

---

## 5. Core data model

All pure Java, all under test, no Gson, no logging, no framework — a `main()`
that prints `N passed, 0 failed`.

### 5.1 Component

```
Component
- Id                 "fire"
- DisplayName        "Ember"
- Category           ACTION | ELEMENT | BEHAVIOUR | TRIGGER
- Rarity             COMMON | UNCOMMON | RARE
- Complexity         int
- EffectId           "fire"            → dispatch key into EffectRegistry
- Parameters         Map<String,Double>
- Tags               Set<String>
- AllowedHosts       Set<HostType>
```

A component is a **definition**; an owned component is an instance. The catalog
maps id → factory, so adding a component is one catalog entry plus (only if the
behaviour is genuinely new) one effect implementation.

### 5.2 Construct

```
Construct
- HostType           RELIC | BOSS | QUEST
- Slots[]            ordered, max 3
- Trigger?           EventMatcher
- Complexity         derived
```

**Immutable while being resolved.** Ordering is significant (§5.5).

### 5.3 Resolution

```
ResolutionResult
- Valid              boolean
- Faults[]           player-friendly strings, never exception text
- Effects[]          ordered
- Reactions[]        fired reaction rule ids
- Discoveries[]      newly-seen combinations
```

Validation messages are **player-facing prose**, never internals. Not
`RiteFault: payload has no attack` — *"This needs something to set it off. Try
adding a trigger."*

Determinism: `resolve(construct, context, seed)` must produce an identical
result for identical inputs. That is what makes the whole thing testable without
a game.

### 5.4 The v1 component pool — 15

**Actions (3).** Slot 1 may hold an Action *or* a Modifier. **If no Action is
present, the player's own attack is the action** — this is what makes the relic
weapon-agnostic (§6.2).

| Id | Name | Complexity | Does |
|---|---|---|---|
| `bolt` | Bolt | 2 | Fires a projectile along the player's aim |
| `burst` | Burst | 2 | Small radial detonation at the impact point. **Damages entities only, never breaks blocks** |
| `blink` | Blink | 2 | Short forward translocation of the caster |

**Elements (4).**

| Id | Name | Complexity | Does |
|---|---|---|---|
| `fire` | Ember | 1 | Ignites, applies burning |
| `frost` | Rime | 1 | Slows; stacks toward a Frozen status |
| `shock` | Arc | 1 | Small instant bonus damage, ignores armour partially |
| `venom` | Blight | 1 | Damage over time, does not kill on its own |

**Behaviours (5).**

| Id | Name | Complexity | Does |
|---|---|---|---|
| `pierce` | Pierce | 1 | Passes through the first target to the next |
| `chain` | Chain | 2 | Jumps to one nearby target at reduced power |
| `knock` | Heave | 1 | Adds knockback |
| `leech` | Leech | 2 | Heals the attacker for a fraction dealt |
| `bloom` | Bloom | 2 | Widens the effect to a small radius |

**Triggers (3).**

| Id | Name | Complexity | Fires on |
|---|---|---|---|
| `on_hit` | On Strike | 0 | Any damage the player causes. **The implicit default** |
| `on_kill` | On Slaying | 1 | The player kills something |
| `on_hurt` | On Wounding | 1 | The player takes damage |

Fifteen components, three slots, order-significant: enough combinations that no
player will see them all, few enough that the pool is learnable.

### 5.5 Reaction rules — data-driven, order-significant

```
ReactionRule
- A, B               component ids or context tags
- Conditions         context predicates
- Outcome            effect id
- Priority           int
- Terminal           boolean — stops further reaction evaluation
```

Shipped v1 set, in `config/constructs/reactions.json` so operators can extend it
without a code change:

| A → B | Outcome |
|---|---|
| `fire` → `frost` | **Thermal Shock** — burst of bonus damage, consumes both |
| `frost` → `fire` | **Melt** — the frost is simply removed. *Order matters, and this pair is the one that teaches it* |
| `shock` → target is wet (rain, water, recent splash) | **Conduct** — arcs to all nearby |
| `frost` → `knock` | **Shatter** — frozen target takes bonus damage, knockback suppressed |
| `venom` → `fire` | **Toxic Flame** — DoT intensity doubles, duration halves |
| `leech` → `venom` | **Tainted** — healing halved, damage doubled |

> **Cascade safety.** `Terminal` is doing all the work of preventing an infinite
> reaction chain, and it is one bad config edit away from failing. The resolver
> gets a **hard depth cap** on top of it (config, default 4). Chosen failure
> direction: the cascade **truncates silently** and logs once. It never throws
> mid-effect, because half an applied effect is worse than a short one.

---

## 6. Host #1 — the Relic

### 6.1 Item construction

⚠ **Host item is unverified — see §16.** Primary candidate is
`minecraft:totem_of_undying`: thematically exact, already `max_stack_size = 1`,
and visible in the offhand on a vanilla client. The risk is that its vanilla
`DEATH_PROTECTION` component must be **stripped**, or vanilla will consume the
stack on death and destroy the player's relic and every component in it.

Fallback if stripping doesn't hold: `minecraft:nautilus_shell` — plain `Item`,
no `use`/`useOn` override to fight with. Less thematic, zero risk.

```
minecraft:custom_data   { "constructs": { "relic": true, "slots": ["fire","chain",null] } }
minecraft:item_name     "Relic"  →  "Dormant Relic" at 0 charges
minecraft:max_damage    16
minecraft:damage        <charges consumed>
minecraft:max_stack_size 1
minecraft:repairable    HolderSet.direct(Items.DIAMOND)
minecraft:lore          rendered build + charge count
```

> ✅(inherited) `Repairable` lives in `net.minecraft.world.item.enchantment`,
> **not** `world.item.component`. This cost `spiritwolves` time.

### 6.2 Attack-agnostic — one funnel, not four hooks

The relic must modify *whatever the player did*: sword, bow, fists, a steak, a
hoe. Agnostic to the **item** is easy. Agnostic to the **delivery** is the work,
because melee, projectiles and block-breaking are three different events with
three different attribution paths.

**Decision: fund the funnel.** Resolve once per *"the player caused damage to a
living entity"*, via `ServerLivingEntityEvents.ALLOW_DAMAGE`, and derive the
attacker from the damage source rather than from the input event.

| Approach | Cost |
|---|---|
| **ALLOW_DAMAGE funnel** ✅ chosen | One code path. Catches melee, arrows, thrown items, indirect damage uniformly. Loses the ability to distinguish *how* the hit landed |
| Per-input events | `AttackEntityCallback` + projectile hit + `PlayerBlockBreakEvents`. Four paths, four attribution bugs, more control than v1 needs |

`on_kill` uses `ServerLivingEntityEvents.AFTER_DEATH`; `on_hurt` uses the same
ALLOW_DAMAGE funnel with the player as victim rather than source.

> ⚠ **Verify: does the funnel double-fire?** A construct containing `bolt` deals
> damage, which re-enters ALLOW_DAMAGE. A re-entrancy guard (a per-tick
> "currently resolving for this player" flag) is required, and it is the single
> most likely source of an infinite loop in this mod.

### 6.3 Block interaction — the Scan verb

Right-clicking a block with the relic in hand performs a **Scan**, which is the
input verb for the quest host's scan segments (§8). One item, three hosts, no
new interaction vocabulary to teach.

### 6.4 Charges, the totem save, and the anvil

**The charge count is pinned by vanilla arithmetic.** A vanilla anvil repairs
`maxDamage / 4` per material unit ✅(inherited, `spiritwolves`). So:

| `max_damage` | Damage per charge | Charges | Diamonds for full | Feel |
|---|---|---|---|---|
| 4 | 1 | 4 | 4 (1 dmd = 1 charge) | The Spirit Stone exactly. Four deaths and you're dark |
| **16** ✅ chosen | **1** | **16** | **4 (1 dmd = 4 charges)** | A bad *night* takes you down, not a bad fight |
| 16 | 4 | 4 | 4 | Row 1 with extra steps |

"Many charges **and** 1 diamond = 1 charge" is arithmetically unavailable
through vanilla. Sixteen is chosen because the feeling to produce is *"I've been
dying all evening"*, not *"I died four times"* — four is tight enough to start a
death spiral.

**The save.** On lethal damage with ≥1 charge:

1. Cancel the death — `ServerLivingEntityEvents.ALLOW_DEATH` ✅(inherited;
   this is precisely how `spiritwolves` saves a wolf).
2. **Not** `DataComponents.DEATH_PROTECTION` — ⚠ vanilla's death-protection
   consumes the stack, which is the one outcome that must never happen.
3. Damage the relic by 1.
4. Heal, and apply **real-totem-grade** regeneration + absorption. A stingy save
   reads as the mod being broken.
5. Loud chime, particles, and an actionbar naming the charges left.

**At 0 charges:**

- No totem save. The player dies normally.
- **No attack modification** — the construct does not resolve at all.
- **Components are not lost.** The build stays in the item.
- Item renames to `Dormant Relic`, lore says how to fix it, and the player gets
  one chat line — not a repeated nag.

**Repair** is entirely vanilla: place the relic and diamonds in an anvil. No
event hook, no code. The XP level cost applies on top as a free second sink
✅(inherited).

> ⚠ **The anvil combine exploit.** Vanilla lets two damaged items of the same
> type repair each other. Two relics would repair for free *and* silently
> discard one of the two component sets. This needs an explicit block, and it
> only surfaces once a player owns a spare. Treat it as a shipping requirement,
> not a polish item — it is the same class of bug as the `_block` duplication
> guard in `chatdonkey` ([SUITE_AUDIT.md §4.4](../SUITE_AUDIT.md)).

### 6.5 The slot UI

An `sgui` chest menu: three slots, the component inventory below, a live preview
row rendering the resolved build as a sentence and a star line:

```
When I strike:  Ember, then Chain
Complexity ★★☆☆☆     Charges 11/16
```

Per suite convention, **every mutating action re-prints the panel — the panel
*is* the UI** ([SUITE_AUDIT.md §4.2](../SUITE_AUDIT.md)).

---

## 7. Host #2 — Bosses

### 7.1 Structure

A boss is a vanilla mob with a `Construct` attached, boosted attributes, a boss
bar, and **its affixes named on that bar**. Naming them is not flavour: it is
the suite's only tutorial. A player who fights a Rime boss learns what Rime does
before they ever own it, which makes the source spec's "Hinted" discovery tier
fall out of combat for free.

| Tier | Cost | Affixes rolled | Availability |
|---|---|---|---|
| **I** | Free | 1–2 | **Once per player per day**, date-derived |
| **II** | diamonds | 2 | Purchasable |
| **III** | diamonds | 3 | Purchasable |
| **IV** | diamonds | 4 | Purchasable |

Affixes are rolled from the **element + behaviour** pool only (9 of the 15) —
actions and triggers are player vocabulary, not monster vocabulary.

The free daily boss is **derived from the date**, so it is the same boss for
everyone that day. That turns it from a private roll into a thing players talk
about, and it inherits `dailyquests`' best property: nothing to store, nothing
to reset, restart-proof.

### 7.2 The drop — this is the component faucet

On death, a boss drops **one of the affixes it was actually wearing**, chosen at
random from its rolled set. Not a random pull from the global pool.

This is the whole acquisition story, and it does three jobs at once: the drop is
*legible* (you saw it used against you), *aspirational* (you wanted it during
the fight), and *scaling* (a tier IV boss shows you four components you don't
own and gives you one).

**Duplicates are tradeable, and that is the entire duplicate design.** The suite
already has `/pay` and a player economy. "I've got a spare Chain, who wants it"
is a social hook that costs zero engineering. Only add a dust/shard conversion
if trading visibly fails to absorb the surplus in play.

### 7.3 Summoning

A **Sigil** item, bought from `/shop` per tier. Right-click a sigil on the
ground to summon. Rules:

- Not within the no-summon radius of spawn (config).
- One active boss per player.
- The boss despawns on `SERVER_STOPPING` and on player logout, and the sigil is
  **refunded** — chosen failure direction: the player loses nothing when the
  server does something they didn't ask for.
- Tier I sigil is granted free once per day, not sold.

---

## 8. Host #3 — Daily quests

Replaces `dailyquests` wholesale. Keep everything that mod got right and
discard the riddle.

**What carries over:**

- **Date-derivation.** Today's chain is derived from the date, never stored.
  Restart-proof, no rollover reset, `rolloverHourUtc` stays config
  (`dailyquests/Quests.java`, `DailyState.java` are the reference).
- **The streak**, which is the suite's second-strongest retention primitive and
  the only sellable one.
- `announceOnJoin`.

**What changes:** the riddle becomes a **three-segment chain**, built from
`EventMatcher` (§4). Per the established tuning philosophy for this mod — light
asks, forgiving grace, higher base reward — the segments are deliberately small.

| Segment kind | Matcher | Example |
|---|---|---|
| **Kill** | `entity_killed` + entity id | "Put down 6 skeletons" |
| **Turn in** | `item_turned_in` + item id | "Hand over 8 wheat" |
| **Scan** | `block_scanned` + block id | "Scan 3 iron ore" (§6.3 — the relic is the scanner) |

Three light segments beats one riddle for a reason worth stating: **partial
completion becomes a real state.** "You're 2 of 3 through, and your streak is at
19" is far stronger loss aversion than a binary riddle, and it costs nothing
extra to build once the matcher exists.

**Streak rules:**

- Higher base reward than the old 1 diamond/day, and the cap rises — the old
  cap of 3 attached the mechanic's teeth to a reward that stopped growing on day
  three.
- **Weekly grace: one missed day per rolling 7 is forgiven.** A streak that
  dies to one bad evening stops being something anyone protects.
- Streak is **public** — `/daily top`. Loss aversion is amplified enormously by
  an audience, and the old mod kept it private.
- Streak protection remains sellable later; do not build it in v1, just don't
  design it out.

---

## 9. Discovery

A player-owned book, opened via `/constructs book` or by right-clicking the
relic in air. Four tiers, per the source spec:

| Tier | Meaning |
|---|---|
| **Known** | Discovered — full text |
| **Hinted** | Seen used against you by a boss, or half of a reaction fired | 
| **Unknown** | Never encountered — silhouette only |
| **Secret** | Operator-hidden. No hint ever |

First-discoverer records are kept per reaction. **This is the suite's first
collection mechanic** — protect it during scope cuts, it is the strongest part
of the whole design.

---

## 10. Events

| Purpose | Event | Module |
|---|---|---|
| Damage funnel (§6.2) | `ServerLivingEntityEvents.ALLOW_DAMAGE` | entity-events-v1 |
| Totem save | `ServerLivingEntityEvents.ALLOW_DEATH` | entity-events-v1 |
| `on_kill`, boss death, quest kills | `ServerLivingEntityEvents.AFTER_DEATH` | entity-events-v1 |
| Scan verb, sigil summon | `UseBlockCallback` | events-interaction-**v0** |
| Relic in air → book | `UseItemCallback` | events-interaction-**v0** |
| Boss tick, charge polling | `ServerTickEvents.END_SERVER_TICK` | lifecycle-events-v1 |
| Boot / shutdown, boss cleanup | `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` | lifecycle-events-v1 |
| Orphaned boss after a crash | `ServerEntityEvents.ENTITY_LOAD` | **lifecycle** package |

> The interaction module is **v0**, not v1. This has now bitten three mods.

---

## 11. Commands

Admin commands gated with `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.

- `/relic` — open the slot panel
- `/constructs book` — open the discovery book
- `/daily` — today's chain and progress
- `/daily top` — streak leaderboard
- `/boss summon <tier>` — consumes a sigil
- `/constructs give <targets> <component>` — admin
- `/constructs reload` — re-read config
- `/constructs cleanup` — force-despawn tracked bosses (ops, for when
  playtesting goes wrong)

---

## 12. Sound cues

The shared `Chime.java` pattern — `ClientboundSoundPacket` to the player's
connection, `SoundSource.RECORDS`, quiet. Confirmations, not fanfares.

| Event | Sound | Volume |
|---|---|---|
| Component slotted | `NOTE_BLOCK_CHIME` | 0.2 |
| Reaction fires (first time ever) | `NOTE_BLOCK_BELL` | 0.35 |
| Reaction fires (subsequently) | `NOTE_BLOCK_CHIME`, high | 0.15 |
| New discovery recorded | `NOTE_BLOCK_BELL`, ascending pair | 0.4 |
| **Totem save** | `TOTEM_USE` | **loud — this one is a fanfare, deliberately** |
| Relic goes dormant | `NOTE_BLOCK_DIDGERIDOO`, low | 0.3 |
| Quest segment complete | `NOTE_BLOCK_HAT` | 0.2 |
| Quest chain complete | `NOTE_BLOCK_BELL` | 0.35 |
| Boss affix announced | `NOTE_BLOCK_BASS`, one per affix | 0.25 |

> The totem save is the one place the suite's "confirmations, not fanfares" rule
> is broken on purpose. A quiet save reads as a bug.

---

## 13. Module layout

Core/fabric split, per [DESIGN.md §9.8](../DESIGN.md) — the resolver is exactly
the kind of thinking that belongs in `core`.

```
a:\MrPinoys Mods\constructs\
    build.gradle.kts
    settings.gradle.kts
    gradle/ + gradlew                 (copy from spiritwolves)
    core/
        build.gradle.kts              ← DELIBERATELY EMPTY. This is the enforcement
        src/main/java/constructs/core/
            Component.java            — definition record
            ComponentCatalog.java     — id → factory
            Construct.java            — immutable, ordered slots
            EventMatcher.java         — §4, the shared half
            Resolver.java             — pure, deterministic
            ResolutionResult.java     — Valid / Faults / Effects / Reactions / Discoveries
            EffectRegistry.java       — id → handler dispatch table
            ReactionRule.java         — data-driven, order-significant
            ReactionEngine.java       — with the hard depth cap
            Context.java              — source/target/tags, all as strings
            QuestChain.java           — segments, progress, streak arithmetic
            BossRoll.java             — deterministic affix rolling from a seed
            Discovery.java            — the four tiers
        src/test/java/constructs/core/
            ConstructsTest.java       — main(), prints "N passed, 0 failed"
    fabric/
        src/main/java/constructs/
            ConstructsMod.java        — entrypoint, event + command registration
            Relic.java                — stack construction, custom_data read/write
            RelicCharges.java         — damage, dormancy, anvil guard
            DamageFunnel.java         — §6.2, including the re-entrancy guard
            EffectsMc.java            — core EffectId → actual Minecraft effects
            Boss.java                 — spawn, attributes, boss bar, affix naming
            BossDrops.java            — the affix drop
            Sigil.java                — summon item
            DailyChain.java           — host binding for the quest
            SlotMenu.java             — sgui panel
            BookMenu.java             — discovery book
            ConstructsConfig.java     — readOrCreate
            ConstructsCommands.java
            Chime.java
        src/main/resources/
            fabric.mod.json
            reactions.json            — default reaction table
            components.json           — default catalog
```

Config at `config/constructs/`, generated on first boot via `readOrCreate` —
write defaults if missing, log it, **never overwrite a file that failed to
parse**. Every number in §5–§8 is a config key.

---

## 14. Build configuration

Copy from `spiritwolves`/`cobblebending` and change the names.

- **Minecraft 26.2**, Fabric Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"` in `fabric.mod.json`
- **No Mixins**
- Loom `1.17-SNAPSHOT`, applied in `settings.gradle.kts`, **not** in the
  `build.gradle.kts` `plugins {}` block
- `archivesName = "MrPinoys_constructs"`
- `sgui` is `eu.pb4:sgui:2.1.0+26.2` from `https://maven.nucleoid.xyz`,
  `implementation` + `include` (shaded)
- A `dist` `Copy` task into the shared `dist/`, `build` `finalizedBy("dist")`

> **⚠ The bug every mod in this suite has hit:** the `dist` task must depend on
> **`jar`**, *not* `remapJar`. Loom registers no `remapJar` task because 26.2
> ships unobfuscated, so depending on it makes `./gradlew build` fail outright.
> Eight mods and counting. Do not repeat it.

- No Yarn mappings. Mojang official names throughout.
- `Identifier`, **not** `ResourceLocation` (renamed in 26.1).

---

## 15. Shop integration

Through `cobbleeconomy`'s `shop.json` `components` block. Never an integration
file, never an api module ([DESIGN.md §3](../DESIGN.md)).

```json
"relic": {
  "item": "minecraft:totem_of_undying",
  "quantity": 1, "price": 24, "currency": "diamond", "category": "Constructs",
  "components": {
    "minecraft:custom_data": { "constructs": { "relic": true, "slots": [null, null, null] } },
    "minecraft:item_name": "Relic",
    "minecraft:max_damage": 16,
    "minecraft:max_stack_size": 1
  }
},
"sigil_ii": {
  "item": "minecraft:echo_shard",
  "quantity": 1, "price": 6, "currency": "diamond", "category": "Constructs",
  "components": {
    "minecraft:custom_data": { "constructs": { "sigil": 2 } },
    "minecraft:item_name": "Sigil of the Second Trial"
  }
}
```

> **Open question, inherited from `cobblebending`.** The `components` parser must
> express `max_damage`, `repairable`, and (if the totem host is used) the
> *removal* of `death_protection`. Removal in particular is likely beyond what
> the parser can say. **The robust fallback is the one `cobblebending` already
> identified: have `constructs` stamp and strip components on any correctly
> marked stack it sees.** That self-heals relics sold by older versions, and it
> should probably just be the primary approach rather than the fallback.

---

## 16. Verify before writing code

Nothing in §6 is safe until these are checked against
`minecraft-merged-deobf-26.2.jar`. In priority order — the first three can each
invalidate a design decision.

1. **Can `DEATH_PROTECTION` be stripped from `totem_of_undying`,** and does a
   stripped totem then survive a lethal hit without being consumed? If not, drop
   to `nautilus_shell` and lose the theme. *Blocks §6.1.*
2. **Does `ALLOW_DEATH` fire before vanilla's own totem check?** A player with a
   real totem in the main hand and a relic in the offhand must not burn both.
   *Blocks §6.4.*
3. **Does `ALLOW_DAMAGE` re-enter when a construct's own effect deals damage?**
   Almost certainly yes. Confirm the shape of the re-entrancy guard before
   building the funnel. *Blocks §6.2.*
4. **Can two relics be combined on a vanilla anvil,** and what happens to the
   components of the sacrificed one? Confirm the block works. *Shipping
   requirement, §6.4.*
5. Does `max_damage` on a normally-undamageable item render a durability bar on
   a vanilla client? (The charge display depends on it.)
6. Is `Repairable` still in `net.minecraft.world.item.enchantment`?
   ✅(inherited) but re-confirm, it is a strange location.
7. `sgui` 2.1.0+26.2 slot-click semantics for a menu that must reject invalid
   component placements.
8. Boss bar API surface for a custom name that changes as affixes are revealed.

Record the answers **in this file**, marked ✅ or ❌ with the date, the way
`cobblebending` §4 does. A future agent reading an unmarked claim will assume it
was checked.

---

## 17. Failure directions — chosen, not discovered

Per [SUITE_AUDIT.md §4.3](../SUITE_AUDIT.md), every persistence and error
decision names which way it should fail.

| Situation | Chosen failure |
|---|---|
| Relic at 0 charges | **Dormant, never punitive.** Player is a vanilla player. §1 |
| Relic data unparseable | Relic goes dormant, components preserved in the raw tag, **file untouched** |
| Reaction cascade exceeds depth cap | **Truncates silently**, logs once. Never throws mid-effect |
| `reactions.json` unparseable | Defaults in memory, file untouched. Reactions still work |
| `components.json` unparseable | **Server refuses to start.** Booting with an empty catalog would silently erase every player's relic build on the next write |
| Boss orphaned by a crash | Reverts to an ordinary vanilla mob, no boss bar, killable |
| Boss despawned by shutdown/logout | **Sigil refunded** |
| Component drop won't fit inventory | Drops at the player's feet, logged. Never deleted |
| Quest progress write lost | Progress rolls **back**, never forward. A player redoing a segment is annoyed; a player skipping one is a faucet |
| Streak state unreadable | Treated as **grace day**, not a break. Never punish a player for a disk error |

Note the split on the two config files: reactions failing open is harmless,
components failing open is data loss. That asymmetry is the point.

---

## 18. Definition of done

**Core (no game required)**

- [ ] `ConstructsTest.main()` prints `N passed, 0 failed`
- [ ] `core/build.gradle.kts` is empty and a stray `import net.minecraft.*` fails to compile
- [ ] `resolve()` is deterministic — same construct + context + seed, same result, 1000 iterations
- [ ] Order matters: `fire→frost` and `frost→fire` produce different results
- [ ] A reaction loop hits the depth cap and truncates rather than hanging
- [ ] Every validation fault is prose a player could read
- [ ] Quest chain streak arithmetic handles the weekly grace, rollover, and a clock jumping backwards

**Relic**

- [ ] Relic is visible in the offhand on a fully vanilla client
- [ ] A construct fires identically for a sword, a bow, bare fists, and a steak
- [ ] **A construct that deals damage does not re-trigger itself** (§16.3)
- [ ] Lethal damage with charges left saves the player and burns exactly one
- [ ] **The save is not consumed by vanilla** — the relic still exists afterwards
- [ ] A real totem in the main hand plus a relic in the offhand burns only one
- [ ] At 0 charges: no save, no attack modification, **components intact**
- [ ] Anvil + diamonds restores 4 charges per diamond
- [ ] **Two relics cannot be combined on an anvil**
- [ ] Dormant relic renames itself and says why, once, not repeatedly

**Boss**

- [ ] Boss bar names every affix it is wearing
- [ ] Boss drops exactly one affix it was actually wearing
- [ ] The free daily boss is identical for every player on a given date
- [ ] Free boss is claimable exactly once per player per day across a restart
- [ ] Tier IV rolls 4 distinct affixes, never a duplicate
- [ ] Shutdown despawns every boss and refunds every sigil

**Quest**

- [ ] Three segments generate from the date and survive a restart unchanged
- [ ] All three segment kinds complete (kill, turn in, scan)
- [ ] Scanning with the relic advances a scan segment
- [ ] Streak survives one missed day per rolling 7
- [ ] `/daily top` shows other players' streaks

**Suite**

- [ ] `./gradlew build` succeeds; `MrPinoys_constructs-0.1.0.jar` lands in `dist/`
- [ ] Zero Mixins
- [ ] Zero compile-time dependencies on any other mod
- [ ] A fully vanilla client can do all of the above with nothing installed

---

## 19. Explicitly out of scope for v1

Every application in the source specification except the three hosts here:
weapons-as-chassis, tools, armor, potions/alchemy, mob mutation, NPC behaviour,
traps, dungeon encounters, machines, farming, environmental shrines, server
events, social/reputation, and player-authored programmable items.

Also out: the advanced component family (Echo, Mirror, Split, Delay, Catalyst,
Chaos, Void), 4- and 5-slot constructs, the complexity-budget *economy* (the
star display stays, the budget enforcement doesn't), the energy layer beyond
plain cooldowns, PvP tuning beyond an on/off flag, streak protection as a sold
item, and any interaction with the other nine mods beyond currency.

Three hosts, fifteen components, six reactions, three slots. **Prove that a
player who finds a component wants to go find another one** before building
anything on top of it.

---

## 20. Migration — retiring two mods

Build order is **relic → boss → quest**, and the two retirements happen at
different times. Neither mod is deleted before its replacement is play-verified.

**Phase 1 — Relic.** Ships the core engine and host #1. Before deleting
`cobblebending`, port these into the engine as primitives — they are the two
genuinely valuable things in that mod and they are proven in play:

- The `minecraft:consumable` / `consumeSeconds = 3600.0F` hold-and-release trick
  ([cobblebending/Focus.java](../cobblebending/src/main/java/cobblebending/Focus.java))
  — becomes a **charge-time delivery** available to any Action.
- `BentBlocks`' tracked-block ledger and **revert-to-prior-state**
  ([cobblebending/BentBlocks.java](../cobblebending/src/main/java/cobblebending/BentBlocks.java))
  — required by any effect that touches the world, and it is the anti-grief
  spine. Do not rewrite this from scratch.

Hurl, Wall and Bridge then return as **shipped starter constructs**, not
hardcoded features. `cobblebending` retires once they do.

**Phase 2 — Boss.** Gives the relic a supply line and the shop a new sink.
Nothing retires here.

**Phase 3 — Quest.** Exercises the matcher half of the engine, which phases 1
and 2 do not touch. **`dailyquests` keeps running untouched until this lands** —
the daily loop is never empty, not for a single release. Port the streak state
rather than resetting it; a player who has a 40-day streak and loses it to a
migration will not start a new one.

> The reason for this order: the relic proves the effect engine on a live server
> while `dailyquests` covers the daily loop, and the quest host — the one that
> replaces a working mod — is built last, against an engine that two hosts have
> already shaken out.
