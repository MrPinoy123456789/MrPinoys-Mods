# MrPinoy's Kamu Totems — Build Spec

> **Status:** **implemented and build-verified 2026-08-13; not play-verified.**
> All three hosts are written, `./gradlew build` succeeds, the core engine passes
> 113 assertions with no game and no test framework, and
> `MrPinoys_kamutotems-0.1.0.jar` is in `dist/`. The §16 verify list has been run
> against `minecraft-merged-deobf-26.2.jar` and its findings are recorded there.
> The §18 definition-of-done boxes remain **unchecked pending play**.
>
> **One risk is deliberately still open** (§16.1): stripping `DEATH_PROTECTION`
> compiles and is well-formed, but that vanilla then declines to consume the
> stack on death can only be proven on a live server. If it fails, a player loses
> their totem and every kamu in it. **Test that first.**
>
> **This mod retires two others.** `cobblebending` retires when §6 ships;
> `dailyquests` retires when §8 ships. See §20 for the migration. **Neither has
> been touched** — both still build and run.

---

## 0. Vocabulary

The words are load-bearing — they are what the player sees, and they are also
the class names. Fixed here so nothing drifts.

| Term | Means | In code |
|---|---|---|
| **Totem** | The offhand item. A rechargeable totem of undying that houses a Kamuy | `Totem`, `HostType.TOTEM` |
| **Kamu** | A spirit dropped by a boss. The **collectible unit** — what the source design called a "component" | `Kamu`, `KamuCatalog` |
| **Kamuy** | The **named power that lives in a player's totem**, made of the kamu imbued into it. One per player, unique, and it remembers | `Kamuy` |
| **Construct** | The host-agnostic arrangement a resolver evaluates. Engine vocabulary, never player-facing | `Construct` |
| **Dormant** | A totem at zero charges. Its Kamuy sleeps; nothing is lost | — |
| **Sigil** | The item that summons a boss | `Sigil` |
| **Reaction** | What happens when two kamu meet in the right order | `ReactionRule` |
| **Tier** | A kamu's strength, 1–3. Raised only by fusion | `Slot.tier` |
| **Fusion** | Two identical kamu of the same tier become one of the next | `Fusion` |
| **Imbue** | Slotting a kamu into a totem. Costs cobblestone | `ImbueCost` |

> **Why `Kamu` and not `Component`.** Beyond the theme: nearly every
> Minecraft-facing file in this suite imports
> `net.minecraft.network.chat.Component`. A core class of the same name would
> force fully-qualified references across the whole fabric module. The rename
> removes a real collision, and it does not collide with `DataComponents`,
> `DataComponentPatch`, or `shop.json`'s `components` block — all of which keep
> their Minecraft meaning throughout this document.

Kamu is both singular and plural. *One kamu, three kamu.*

> **Kamu and Kamuy are not the same word, and the difference is the whole
> design.** A **kamu** is loot: it drops, it stacks, it trades, it fuses. A
> **Kamuy** is the thing a player *names* — the accumulated power in their totem,
> which no other player has, and which carries a history. Kamu are fungible.
> A Kamuy is not.
>
> In code: `Kamu` is a catalog definition, `Slot` is an owned kamu at a tier, and
> `Kamuy` is the named, persistent, per-player wrapper around a `Construct`.
> Getting these three confused is the most likely source of a rewrite, so the
> names are deliberately distinct at a glance.

---

## 1. The problem this solves

The suite has ten mods and no **collection** mechanic ([SUITE_AUDIT.md §5.2](../SUITE_AUDIT.md)
scores it *Absent*), one carrier for attachment, and a daily loop made of a
single 30-second riddle. It also has four of the nine sink shapes entirely
unbuilt ([DESIGN.md §5](../DESIGN.md)).

Kamu Totems is **one interaction engine with three hosts**, and the three hosts
form a closed loop:

```
   boss drops a kamu it was carrying  ──▶  kamu slots into the totem
            ▲                                          │
            │                                          ▼
   diamonds buy more boss attempts        totem makes you strong enough
            ▲                                   for the next boss
            └──────────  dying burns totem charges  ◀──────────┘

   the daily quest chain gives you a reason to be logged in at all
```

**What each host is for:**

| Host | What it is | Suite job |
|---|---|---|
| **Totem** | Offhand item that modifies whatever attack you make, and doubles as a rechargeable totem of undying | Sink (charges), power fantasy, the collection vault |
| **Boss** | Summonable elite carrying random kamu drawn from the same pool | Faucet (kamu), sink (attempts), the tutorial |
| **Quest** | Multi-segment daily chain built from the engine's triggers | Daily loop, streak |

**Sink shapes hit** ([DESIGN.md §5](../DESIGN.md) table): *destruction / risk* ✓
(charges burn on death), *rate-gate + pay to exceed* ✓ (one free boss a day,
diamonds for more), *randomized crafting* ✓ (a bought sigil is sealed until you
pay for it), *collection* ✓ — the first in the suite.

**And the shop sells exactly one kind of thing: a boss fight.** No kamu, no
totem, no shortcut. Everything that makes a player stronger is earned off a
boss's corpse; diamonds buy only the *opportunity* to try. That is what keeps
the collection loop from decaying into a price list.

### The Kamuy is the point

Kamu are loot. **The Kamuy is a character.**

The suite's strongest retention mechanic is attachment, and
[SUITE_AUDIT.md §5.2](../SUITE_AUDIT.md) records that `spiritwolves` is its only
carrier — a gap the audit ranks in its top tier. A named power that a player
built themselves out of things they personally killed for, that no one else has,
that gets stronger as they play and **remembers what it did**, is a second
carrier of exactly that kind.

The design borrows the wolf's two load-bearing tricks deliberately:

- **It has a name the player chose.** Named on first imbue, changeable, theirs.
- **It keeps a journal.** The totem's lore accumulates its own history —
  *"Ember-of-Nine-Winters. Forged 14 days ago. Has drunk from 31 spirits. Pulled
  you back from death 7 times."* That is a retention hook made entirely of text,
  and it costs nothing but a few counters.

This is also why **dormancy never destroys anything** (§6.4) and why removal
returns the kamu intact (§5.8). A player must be able to experiment with their
Kamuy without ever risking it. Attachment only forms where loss is impossible;
`spiritwolves` proved that by removing the heartbreak and selling the relief.

> **The load-bearing rule: at zero charges the player is a vanilla player, never
> worse than one.** No debuff, no penalty, no kamu lost. The totem goes dormant
> and the player has lost a *bonus*. This is the difference between a regression
> to baseline and a death spiral, and it is what keeps a bad night from becoming
> a quit. See §6.4.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Mod id | **`kamutotems`** | Matches suite style — lowercase, no separator |
| Hosts in v1 | **Three: totem, boss, quest** | Each exercises a different half of the engine (§4) |
| Module split | **`core/` + `fabric/`** | The resolver is pure logic. [DESIGN.md §9.8](../DESIGN.md) |
| Slots per totem | **Three** | The accessibility constraint. Non-negotiable in v1 |
| Kamu pool v1 | **15** | 3 actions, 4 elements, 5 behaviours, 3 triggers (§5.5) |
| Totem charges | **16, `max_damage = 16`** | 1 diamond = 4 charges. The arithmetic is forced — §6.4 |
| Charges burn on | **Death only. Never ability use** | One number, one meaning |
| Kamu acquisition | **Boss drops only. Never sold** | The shop must not short-circuit the collection loop |
| Kamu tiers | **1–3.** t1+t1→t2, t2+t2→t3 | A t3 costs four drops of the same kamu. Long tail, no ceiling problem |
| Bosses drop | **Tier 1 only** in v1 | Fusion is the *only* ladder. One way up is enough to learn |
| Imbue / remove cost | **Cobblestone**, scaled by tier | §5.8 — and it is how this mod inherits `cobblebending`'s job |
| Totem acquisition | **Free, granted on first join** | Identity, not a purchase. Follows from the row below |
| **One Kamuy per player**, named by them | Bound to the *player*, not the item | §5.3. Losing an item must never cost a relationship |
| **The shop sells exactly one thing** | **Tiered boss sigils** | Everything else is earned. §15 |
| Sigil contents | **Hidden until purchased**, then shown in the item's lore | The gamble is *which* boss, not *whether* you can read it |
| Duplicate kamu | **Tradeable. No conversion** | `/pay` and the player economy absorb them for free |
| Free boss | **One per player per day, date-derived** | Same seed for everyone — a topic of conversation |
| Quest structure | **3 light segments, date-derived** | Per [dailyquests tuning](../dailyquests/) — light asks, higher base reward |
| Streak | **Preserved from `dailyquests`, with weekly grace** | Strongest retention primitive in the suite. Do not drop it in the migration |
| Client requirement | **None.** `"environment": "server"` | Non-negotiable |
| Mixins | **Zero** | If a host needs one, cut the host |
| Cross-mod | **None.** Sells via `shop.json` `components` | [DESIGN.md §3](../DESIGN.md) |

---

## 3. Core loop

1. Player is **given a Kamu Totem on first join** — a vanilla item stamped with
   `custom_data`, worn in the **offhand**. It is not sold and never has been.
2. The totem starts with three empty slots and 16 charges.
3. Player summons the **free daily boss** (whose kamu everyone can see in
   advance), or buys a **sigil** for a higher tier — whose kamu are *unknown
   until the moment of purchase*, then printed in the sigil's lore. The kamu a
   boss carries are named on its boss bar during the fight — *this is where the
   player learns what kamu exist*.
4. Boss dies → drops **one of the kamu it was actually carrying**.
5. Player slots the kamu into the totem. Now every attack they make — sword,
   bow, fists, a steak — carries it.
6. Player dies → the totem saves them instead of the death, and burns a charge.
7. At **0 charges the totem goes dormant**: no save, no attack modification,
   kamu intact. A vanilla anvil and diamonds bring it back.
8. Meanwhile the **daily quest chain** runs three light segments a day, paying
   diamonds and keeping a streak.

---

## 4. Architecture — the engine has two halves

This is the decision the whole mod rests on, so it goes first.

The three hosts do **not** use the engine the same way:

| Host | Leans on | Barely touches |
|---|---|---|
| **Totem** | Effects, reactions, slot ordering | Triggers — almost always "on hit" |
| **Quest** | Event matching and counting | Effects — the reward is currency |
| **Boss** | Both — kamu are effects, phases are triggers |

The naive model makes `Trigger` an optional field on `Construct`. That is
correct for the totem and **wrong for the quest**, where a segment is nothing
*but* a trigger plus a required count. Building it that way bolts a second,
parallel quest system onto the side of an effect engine.

**So `EventMatcher` is a first-class core concept**, used two ways:

```
EventMatcher                            used by
- EventId          ("entity_killed")    ─┬─ QuestSegment  (+ requiredCount, + progress)
- Predicate        (target = "zombie")   └─ Construct     (as its WHEN clause)
- RequiredCount    (int)
```

One matcher implementation, one set of tests, two hosts. The rest of the engine:

```
Minecraft Event
      ↓
IKamuHost              (totem / boss / quest — supplies context + applies results)
      ↓
Construct               (immutable during resolution)
      ↓
Resolver                (pure, deterministic, headless-testable)
      ↓
EffectContext
      ↓
Effects → ReactionEngine
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

### 5.1 Kamu

```
Kamu
- Id                 "fire"
- DisplayName        "Ember"
- Category           ACTION | ELEMENT | BEHAVIOUR | TRIGGER
- Rarity             COMMON | UNCOMMON | RARE
- Complexity         int
- EffectId           "fire"            → dispatch key into EffectsMc
- Parameters         Map<String,Double>
- Tags               Set<String>
- AllowedHosts       Set<HostType>
```

A `Kamu` is a **definition**; an owned kamu is an instance. The catalog maps
id → factory, so adding a kamu is one catalog entry plus (only if the behaviour
is genuinely new) one effect implementation.

### 5.2 Typed slots — DO + WITH + WHEN

The three slots are **not interchangeable**. The source specification's §3 shape:

```
┌─────────────────────────────┐
│   [ ACTION ]                │   what happens
│        ↓                    │
│   [ MODIFIER ]              │   how it behaves
│        ↓                    │
│   [ MODIFIER ]              │
└─────────────────────────────┘
      WHEN: [ TRIGGER ]           when it happens (outside the box)
```

`SlotRole.accepts()` decides what fits where, and the resolver rejects a
mismatch in prose: *"Ember doesn't belong in the Action slot."*

**Every non-WHEN slot is always occupied.** Two baseline kamu — `strike`
(Action) and `plain` (Modifier) — hold the slots when nothing better is in
them. They are complexity 0, emit nothing, are never dropped by a boss, and
return automatically when a real kamu is popped out. *Empty* and *default* are
the same behaviour; only one of them is legible to a player, and a panel with
holes in it invites the question "is this broken?"

WHEN stays genuinely optional — empty means **on hit**.

> **⚠ Found in play, 2026-08-13: WHEN was never implemented.**
> `Construct.trigger()` had **zero call sites**. `DamageFunnel` resolved the
> construct on *every* hit regardless of the slotted trigger, and trigger kamu
> emitted no-op effects. So `on_kill` + `leech` — the Executioner's Charm of
> §26, the headline example in both specs — healed the player on every hit
> instead of on kill. The kamu looked slotted. It did nothing.
>
> Untyped slots are what let that hide: a trigger sitting in a generic slot is
> indistinguishable from a working one. Now the funnel reads the WHEN slot and
> only resolves on a match, `on_kill` is wired to `AFTER_DEATH`, `on_hurt` to
> the player-as-victim path, and the resolver emits no effect for the WHEN slot
> at all.

### 5.2b Construct — the engine's unit

```
Construct
- HostType           TOTEM | BOSS | QUEST
- Slots[]            ordered, max 3
- Trigger?           EventMatcher
- Complexity         derived
```

**Immutable while being resolved.** Ordering is significant (§5.6). A boss has
one, a quest chain has one, and a player's totem has one — the resolver does not
care which.

### 5.3 Kamuy — the named power in a player's totem

A `Kamuy` is a `Construct` **plus identity**. It is what makes the totem host
different from the other two: bosses and quests have constructs, but only a
player has a Kamuy.

```
Kamuy
- Name               player-chosen, sanitised
- Construct          the three slots and their trigger
- BornDateKey        when it was first named
- KamuDrunk          total kamu ever imbued
- Saves              deaths it has prevented
- BossesSlain        bosses killed while it was awake
```

**Naming.** The player is prompted to name their Kamuy the first time they imbue
a kamu — not on first join, because an empty totem is not yet anything. Until
then it is *"an unnamed spirit."* Renaming is free and always allowed.

> **Sanitise the name like hostile input**, per the `chatdonkey` rule
> ([SUITE_AUDIT.md §4.4](../SUITE_AUDIT.md)): strip `§` together with its code
> letter, control characters to spaces, collapse whitespace, cap the length. A
> name is rendered in item lore and possibly in chat, and a player who can forge
> a colour code can forge a lot more than a colour.

**The journal** is the counters above rendered into the totem's lore. Keep it to
three or four lines, and write them as the Kamuy's own voice rather than a stat
block — `spiritwolves`' journal is the reference and it works because it reads
like a biography, not a character sheet.

**One Kamuy per player.** It travels with the player, not with the item: if the
totem is lost, `/totem` re-issues one and the Kamuy is still theirs, with its
name and history intact. Losing an item should never cost a relationship.

### 5.4 Resolution

```
ResolutionResult
- Valid              boolean
- Faults[]           player-friendly strings, never exception text
- Effects[]          ordered
- Reactions[]        fired reaction rule ids
- Discoveries[]      newly-seen combination keys
```

Validation messages are **player-facing prose**, never internals. Not
`RiteFault: payload has no attack` — *"This needs something to set it off. Try
adding a trigger."*

Determinism: `resolve(construct, context, seed)` must produce an identical
result for identical inputs. That is what makes the whole thing testable without
a game.

### 5.5 The v1 kamu pool — 15

**Actions (3).** Slot 1 may hold an Action *or* a Modifier. **If no Action is
present, the player's own attack is the action** — this is what makes the totem
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
| `shock` | Arc | 1 | Small instant bonus damage, partially ignores armour |
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

Fifteen kamu, three slots, order-significant: enough combinations that no
player will see them all, few enough that the pool is learnable.

> **Naming note.** Display names are deliberately plain English rather than
> borrowed deity names. The kamu framing carries the theme; inventing
> authentic-sounding names for spirits that don't exist would be worse than not
> trying. If you later want thematic names, change `displayName` in
> `components.json` — no code depends on it.

### 5.6 Reaction rules — data-driven, order-significant

```
ReactionRule
- A, B               kamu effect ids, or "tag:<x>" to match a context tag
- Conditions         context predicates
- Outcome            effect id
- Priority           int
- Terminal           boolean — stops further reaction evaluation
```

Shipped v1 set, in `config/kamutotems/reactions.json` so operators can extend it
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

### 5.6b The three states of a kamu

A kamu is always in exactly one of three states, with one deliberate action
between each:

```
   item  ──right-click──▶  bound (pool)  ──click──▶  slotted
         ◀──shift-click──                ◀──click──
```

- **Item** — loose in the world. Drops from bosses, trades between players,
  and is the only form that **fuses** (§5.7).
- **Bound** — held by your Kamuy but not yet placed. Right-clicking a kamu
  item binds it.
- **Slotted** — in the construct, doing work. Costs cobblestone in, cobblestone
  out (§5.8).

> **Why binding exists.** The panel used to read the player's live inventory and
> imbue straight from it, which made *"did that actually consume my kamu?"*
> impossible to answer at a glance — and it was the first thing play-testing
> asked. Binding first means the panel only ever shows state it owns, and every
> transition is something the player did on purpose.

### 5.7 Tiers and fusion

A kamu in the catalog is a **definition**. A kamu a player owns is a
definition **plus a tier**, 1 to 3. Tier is not a separate id — `fire` at tier 3
is the same kamu with a bigger multiplier, so the pool stays 15 and the
reaction table stays 6.

```
t1 fire  +  t1 fire   →   t2 fire
t2 fire  +  t2 fire   →   t3 fire
```

**Rules:**

- Both inputs must be the **same kamu id** *and* the **same tier**. No
  cross-kamu fusion, no mixed-tier fusion.
- **Both inputs are consumed, one output is produced.** A t3 therefore costs
  four tier-1 drops of that exact kamu.
- **Tier 3 is the cap.** Fusing two t3 is refused with a plain message, not
  silently eaten.
- Tier scales the effect's `parameters` by a multiplier (config, default
  1.0 / 1.6 / 2.5). **The multiplier is applied in the resolver**, so it is pure,
  deterministic and testable — not scattered through the fabric layer.

**Fusion is a crafting recipe.** Sneak + right-click a crafting table while
holding a kamu to open the **Kamu Forge**: put two identical kamu of the same
tier in the grid, take one of the next tier out. Both inputs are consumed only
when the output is actually taken, so a disconnect mid-fusion loses nothing.

It operates on the **item** form, before binding (§5.6b) — which is coherent,
because a boss drops items and you fuse them before committing them to a Kamuy.

> **It cannot be a datapack recipe, and that is not a shortcut.** A kamu is a
> vanilla item carrying `custom_data`; vanilla recipe matching cannot see
> components, and a custom recipe serializer is synced to clients, which would
> break the non-negotiable "vanilla clients install nothing" rule. The suite
> already solved this exact problem: `wondrous`' Disenchanter and Smelter are
> `MenuType.GRINDSTONE` screens with entirely different server-side rules.
> `KamuForge` is the same trick over `MenuType.CRAFTING` — the client renders an
> ordinary crafting table and the server decides what it does.

Not the anvil: that is already doing charge repair in diamonds, and one station
doing two unrelated jobs is how players get confused about what a diamond
bought.

**Why fusion rather than a shop conversion.** Duplicates were previously left to
the player market alone. Fusion gives them a *floor* value without ever letting
diamonds buy power: trading is still how you find the match, and fusion is what
the match is for. The two mechanics feed each other instead of competing.

### 5.8 The cobblestone cost — imbue and remove

**Imbuing a kamu into a totem costs cobblestone. Removing it costs cobblestone
too.** Both scale with the kamu's tier.

| Action | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|---|
| **Imbue** | 32 | 96 | 256 |
| **Remove** | 16 | 48 | 128 |

All six numbers are config. Removal **returns the kamu intact** — you are
paying to change your mind, not destroying anything.

**Three jobs this one rule does:**

1. **It inherits `cobblebending`'s reason to exist.** That mod is the suite's
   only cobblestone sink ([SUITE_AUDIT.md §2.9](../SUITE_AUDIT.md)), and
   §20 retires it. Without this cost, retiring it would quietly make the common
   currency worthless again. This is not a nice-to-have; it is the handover.
2. **It gives the three-slot limit teeth.** A build you can rearrange for free is
   not a decision. A build that costs a stack of cobble to change is one you
   think about.
3. **It is a sink that scales with engagement**, not with a machine — the players
   who reconfigure most are the ones most invested. That is the shape
   [DESIGN.md §5](../DESIGN.md) asks for.

> **Tune this downward if in doubt.** Cobblestone is abundant and the suite's
> stated bias is generosity ([DESIGN.md §9.5](../DESIGN.md)). The failure mode
> that matters is not "players spend too little cobble" — it is **a player who
> never experiments because rearranging feels expensive**, which would strangle
> the discovery mechanic this whole mod is built around. If play shows
> hesitation at the panel, cut the numbers in half.

---

## 6. Host #1 — the Totem

### 6.1 Item construction

⚠ **Host item is unverified — see §16.** Primary candidate is
`minecraft:totem_of_undying`: now thematically exact, already
`max_stack_size = 1`, and visible in the offhand on a vanilla client. The risk
is that its vanilla `DEATH_PROTECTION` component must be **stripped**, or
vanilla will consume the stack on death and destroy the player's totem and every
kamu in it.

Fallback if stripping doesn't hold: `minecraft:nautilus_shell` — plain `Item`,
no `use`/`useOn` override to fight with. Less thematic, zero risk.

```
minecraft:custom_data   { "kamutotems": { "totem": true, "slots": ["fire","chain",null] } }
minecraft:item_name     "Kamu Totem"  →  "Dormant Totem" at 0 charges
minecraft:max_damage    16
minecraft:damage        <charges consumed>
minecraft:max_stack_size 1
minecraft:repairable    HolderSet.direct(Items.DIAMOND)
minecraft:lore          rendered build + charge count
```

> ✅(inherited) `Repairable` lives in `net.minecraft.world.item.enchantment`,
> **not** `world.item.component`. This cost `spiritwolves` time.

### 6.2 Attack-agnostic — one funnel, not four hooks

The totem must modify *whatever the player did*: sword, bow, fists, a steak, a
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

**Sneak + right-click** a block with the totem performs a **Scan**, which is the
input verb for the quest host's scan segments (§8). Sneak + right-click in air
opens the discovery book (§9). One item, three hosts, no new interaction
vocabulary beyond the one modifier key.

> **Found in integration, 2026-08-13: a plain right-click, not sneak +
> right-click.** The totem lives in the offhand (§6.1), and Minecraft's client
> automatically retries an interaction against the offhand whenever the main
> hand's attempt returns `PASS` — which is nearly every plain right-click:
> empty hand, most tools, most blocks with nothing to interact with. Without a
> gate, a totem worn in the offhand silently swallows almost every right-click
> in the game, which is exactly what happened on first play-test: the player
> could not right-click *anything* without triggering Scan or the book.
>
> Vanilla shields and maps don't hit this because they define their own `use()`
> semantics; a plain custom item gets no such protection for free. **The fix is
> sneak + right-click**, the standard convention for a deliberate offhand-trinket
> gesture, and both `onUseItem` and `onUseBlock` now gate on
> `player.isShiftKeyDown()` before checking the held stack at all.

### 6.4 Charges, the save, and the anvil

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
3. Damage the totem by 1.
4. Heal, and apply **real-totem-grade** regeneration + absorption. A stingy save
   reads as the mod being broken.
5. Loud chime, particles, and an actionbar naming the charges left.

**At 0 charges:**

- No save. The player dies normally.
- **No attack modification** — the construct does not resolve at all.
- **Kamu are not lost.** The build stays in the item.
- Item renames to `Dormant Totem`, lore says how to fix it, and the player gets
  one chat line — not a repeated nag.

**Repair** is entirely vanilla: place the totem and diamonds in an anvil. No
event hook, no code. The XP level cost applies on top as a free second sink
✅(inherited).

> ⚠ **The anvil combine exploit.** Vanilla lets two damaged items of the same
> type repair each other. Two totems would repair for free *and* silently
> discard one of the two kamu sets. This needs an explicit block, and it only
> surfaces once a player owns a spare. Treat it as a shipping requirement, not a
> polish item — it is the same class of bug as the `_block` duplication guard in
> `chatdonkey` ([SUITE_AUDIT.md §4.4](../SUITE_AUDIT.md)).

### 6.5 The slot UI

An `sgui` chest menu doing two jobs: **place** and **read**. (Fusion moved to the Kamu Forge, §5.7.)

```
┌──────────────────────────────────────────────┐
│  Ember-of-Nine-Winters          ← the Kamuy  │
│  [ Ember II ] [ Chain I ] [  empty  ]        │  ← the three slots
│                                              │
│  When I strike:  Ember II, then Chain I      │
│  Complexity ★★★☆☆        Charges 11/16       │
│                                              │
│  FUSE:  [ in ] [ in ] → [ out ]              │  ← two identical, same tier
│                                              │
│  your kamu ────────────────────────────     │
│  [Ember I ×3] [Rime I] [Arc II] [Leech I]    │
└──────────────────────────────────────────────┘
```

**Every cost is shown before it is charged.** Hovering a kamu in your inventory
shows `Imbue: 32 cobblestone`; hovering a slotted one shows
`Remove: 16 cobblestone`. A player must never discover a price by paying it.

If the player lacks the cobblestone the action is refused with a plain message
and **nothing is consumed** — rejections are return values, not exceptions, per
[SUITE_AUDIT.md §4.2](../SUITE_AUDIT.md).

Per suite convention, **every mutating action re-prints the panel — the panel
*is* the UI** ([SUITE_AUDIT.md §4.2](../SUITE_AUDIT.md)).

---

## 7. Host #2 — Bosses

### 7.1 Structure

A boss is a vanilla mob carrying a `Construct`, with boosted attributes, a boss
bar, and **the kamu it carries named on that bar**. Naming them is not flavour:
it is the suite's only tutorial. A player who fights a boss carrying Rime learns
what Rime does before they ever own it, which makes the "Hinted" discovery tier
fall out of combat for free.

| Tier | Cost | Kamu carried | Availability | Contents known before you commit? |
|---|---|---|---|---|
| **I** | Free | 1–2 | **Earned by finishing the daily chain** (§8) | **Yes** — public, same for everyone |
| **II** | diamonds | 2 | Purchasable | **No** — revealed on purchase |
| **III** | diamonds | 3 | Purchasable | **No** — revealed on purchase |
| **IV** | diamonds | 4 | Purchasable | **No** — revealed on purchase |

**The free tier is known and the paid tiers are gambles.** That contrast is the
design: the daily boss is a scheduled, discussable, communal event, and a bought
sigil is a sealed envelope. Both are worth doing for different reasons.

Bosses draw from the **element + behaviour** pool only (9 of the 15) — actions
and triggers are player vocabulary, not monster vocabulary. The two baseline
default kamu (§5.2) are never rolled: they are furniture, not loot.

**Difficulty scales geometrically**, config-driven:

| Tier | Health | Damage | Armour | Knockback resist |
|---|---|---|---|---|
| I | 80 | ×1.8 | 4 | 20% |
| II | 160 | ×2.6 | 8 | 40% |
| III | 320 | ×3.4 | 12 | 60% |
| IV | 640 | ×4.2 | 16 | 80% |

The first pass was far too soft — tier IV was a 60 HP zombie with double damage,
for 28 diamonds. Knockback resistance matters as much as the numbers: without
it a boss is trivially chain-knocked into a corner, which makes every fight the
same fight regardless of tier. All four curves are config; these are first
guesses and will need retuning in play.

The free daily boss is **derived from the date**, so it is the same boss for
everyone that day. That turns it from a private roll into a thing players talk
about, and it inherits `dailyquests`' best property: nothing to store, nothing
to reset, restart-proof.

### 7.2 The drop — this is the kamu faucet

On death, a boss drops **one of the kamu it was actually carrying**, chosen at
random from its rolled set. Not a random pull from the global pool.

**Always at tier 1.** Fusion (§5.7) is the only way up, which keeps one ladder
instead of two and makes every drop equally useful — a duplicate is progress,
not consolation. A config flag allows a small tier-2 chance at boss tier IV;
leave it off for v1 until the fusion economy has been watched in play.

This is the whole acquisition story, and it does three jobs at once: the drop is
*legible* (you saw it used against you), *aspirational* (you wanted it during
the fight), and *scaling* (a tier IV boss shows you four kamu you don't own and
gives you one).

**Duplicates are tradeable, and that is the entire duplicate design.** The suite
already has `/pay` and a player economy. "I've got a spare Chain, who wants it"
is a social hook that costs zero engineering. Only add a dust/shard conversion
if trading visibly fails to absorb the surplus in play.

### 7.3 Sigils — the blind purchase and the reveal

A **Sigil** item, bought from `/shop` per tier. **This is the only thing the
shop sells.**

**The shop cannot roll the boss.** A per-purchase random roll is not something a
static `shop.json` `components` block can express, and making it expressible
would mean coupling `cobbleeconomy` to this mod — which [DESIGN.md §3](../DESIGN.md)
forbids. So:

1. `shop.json` sells an **unrolled** sigil: `{ "sigil": 2, "rolled": false }`.
2. `kamutotems` sweeps player inventories on a tick counter for unrolled sigils,
   rolls `BossRoll.forSeed(...)`, writes the result into `custom_data`, and
   **rewrites the item's lore to name every kamu the boss carries.**
3. From then on the sigil is a known quantity the player can hold, use later, or
   trade.

This is the same self-healing stamp pattern §15 already identified for the
`consumable` component, and it means a sigil sold by an older version of the
shop still works.

**The seed is derived and stored**, so the roll is reproducible for debugging:
`seed = hash(playerUuid, purchaseCounter, worldSeed)`. Store the seed alongside
the roll — a roll you cannot reproduce is a support ticket you cannot answer.

> **The gamble is honest.** The player learns the contents *before* fighting,
> not after. A sigil whose kamu they already own is not wasted — they can hold
> it, or sell it to someone who wants those kamu. **Sigils being tradeable is
> what makes the blind purchase fair**, and it creates a real secondary market
> for free. Do not make sigils soulbound.

Right-click a sigil on the ground to summon. Rules:

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
`EventMatcher` (§4). Per the established tuning philosophy for that mod — light
asks, forgiving grace, higher base reward — the segments are deliberately small.

| Segment kind | Matcher | Example |
|---|---|---|
| **Kill** | `entity_killed` + entity id | "Put down 6 skeletons" |
| **Turn in** | `item_turned_in` + item id | "Hand over 8 wheat" |
| **Scan** | `block_scanned` + block id | "Scan 3 iron ore" (§6.3 — the totem is the scanner) |

> **⚠ Each block position counts once per day.** Found in play, 2026-08-13: the
> same diamond ore could be scanned three times to finish a three-scan segment.
> Scanning is the only quest verb with **no natural cost** — a kill consumes a
> mob and a turn-in consumes items, but a block can be right-clicked forever. A
> scan is therefore identified by *where* it is, not just *what* it is. Scanning
> a second, different ore is progress; scanning the same one again is not, and
> says so.

Three light segments beats one riddle for a reason worth stating: **partial
completion becomes a real state.** "You're 2 of 3 through, and your streak is at
19" is far stronger loss aversion than a binary riddle, and it costs nothing
extra to build once the matcher exists.

**The chain pays out as it goes.** Each finished segment grants a small diamond
reward and a chime; the *last* one grants a **Sigil of the First Trial** — the
free daily boss, as an item. That is what ties the daily loop to the boss loop:
the fight is earned by showing up and doing the day's work, rather than being a
command you had to already know existed. It is also why the shop's sigils start
at the Second Trial — the First is never sold.

Unlike the bought sigils, the First Trial arrives **already rolled and
readable**: the daily boss is public knowledge, the same for everyone that day.
The paid tiers are the gamble; this one is the communal event.

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

A player-owned book, opened via `/kamu book` or by right-clicking the totem in
air. Four tiers:

| Tier | Meaning |
|---|---|
| **Known** | Discovered — full text |
| **Hinted** | Seen carried by a boss, or half of a reaction fired |
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
| The save | `ServerLivingEntityEvents.ALLOW_DEATH` | entity-events-v1 |
| `on_kill`, boss death, quest kills | `ServerLivingEntityEvents.AFTER_DEATH` | entity-events-v1 |
| Scan verb, sigil summon | `UseBlockCallback` | events-interaction-**v0** |
| Totem in air → book | `UseItemCallback` | events-interaction-**v0** |
| Boss tick, charge polling | `ServerTickEvents.END_SERVER_TICK` | lifecycle-events-v1 |
| Boot / shutdown, boss cleanup | `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` | lifecycle-events-v1 |
| Orphaned boss after a crash | `ServerEntityEvents.ENTITY_LOAD` | **lifecycle** package |

> The interaction module is **v0**, not v1. This has now bitten three mods.

---

## 11. Commands

Admin commands gated with `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.

- `/totem` — open the slot panel; re-issues a totem to a player who has none
- `/totem name <text>` — name or rename your Kamuy (sanitised, §5.3)
- `/kamuy` — your Kamuy's journal: name, age, kamu drunk, saves, bosses slain
- `/kamu book` — open the discovery book
- `/daily` — today's chain and progress
- `/daily top` — streak leaderboard
- `/boss summon <tier>` — consumes a sigil
- `/kamutotems give <targets> <kamu>` — admin
- `/kamutotems reload` — re-read config
- `/kamutotems cleanup` — force-despawn tracked bosses (ops, for when
  playtesting goes wrong)

---

## 12. Sound cues

The shared `Chime.java` pattern — `ClientboundSoundPacket` to the player's
connection, `SoundSource.RECORDS`, quiet. Confirmations, not fanfares.

| Event | Sound | Volume |
|---|---|---|
| Kamu slotted | `NOTE_BLOCK_CHIME` | 0.2 |
| Reaction fires (first time ever) | `NOTE_BLOCK_BELL` | 0.35 |
| Reaction fires (subsequently) | `NOTE_BLOCK_CHIME`, high | 0.15 |
| New discovery recorded | `NOTE_BLOCK_BELL`, ascending pair | 0.4 |
| **The save** | `TOTEM_USE` | **loud — this one is a fanfare, deliberately** |
| Totem goes dormant | `NOTE_BLOCK_DIDGERIDOO`, low | 0.3 |
| Quest segment complete | `NOTE_BLOCK_HAT` | 0.2 |
| Quest chain complete | `NOTE_BLOCK_BELL` | 0.35 |
| Boss kamu announced | `NOTE_BLOCK_BASS`, one per kamu | 0.25 |

> The save is the one place the suite's "confirmations, not fanfares" rule is
> broken on purpose. A quiet save reads as a bug.

---

## 13. Module layout

Core/fabric split, per [DESIGN.md §9.8](../DESIGN.md) — the resolver is exactly
the kind of thinking that belongs in `core`.

```
a:\MrPinoys Mods\kamutotems\
    build.gradle.kts
    settings.gradle.kts
    gradle/ + gradlew                 (copy from spiritwolves)
    core/
        build.gradle.kts              ← DELIBERATELY EMPTY. This is the enforcement
        src/main/java/kamutotems/core/
            Kamu.java                 — collectible definition record
            KamuCatalog.java          — id → factory
            Slot.java                 — an owned kamu at a tier
            Fusion.java               — t1+t1→t2, refusals as return values
            ImbueCost.java            — the cobblestone table
            Construct.java            — immutable, ordered slots
            Kamuy.java                — the named per-player power + journal
            KamuyName.java            — hostile-input sanitiser
            EventMatcher.java         — §4, the shared half
            Resolver.java             — pure, deterministic
            ResolutionResult.java     — Valid / Faults / Effects / Reactions / Discoveries
            ReactionRule.java         — data-driven, order-significant
            ReactionEngine.java       — with the hard depth cap
            Context.java              — source/target/tags, all as strings
            QuestChain.java           — segments, progress, streak arithmetic
            BossRoll.java             — deterministic kamu rolling from a seed
            DiscoveryBook.java        — the four tiers
        src/test/java/kamutotems/core/
            KamuTotemsTest.java       — main(), prints "N passed, 0 failed"
    fabric/
        src/main/java/kamutotems/
            KamuTotemsMod.java        — entrypoint, event + command registration
            Totem.java                — stack construction, custom_data read/write
            TotemCharges.java         — damage, dormancy, anvil guard
            DamageFunnel.java         — §6.2, including the re-entrancy guard
            EffectsMc.java            — core EffectId → actual Minecraft effects
            Boss.java                 — spawn, attributes, boss bar, kamu naming
            BossDrops.java            — the kamu drop
            Sigil.java                — summon item
            DailyChain.java           — host binding for the quest
            SlotMenu.java             — sgui panel
            BookMenu.java             — discovery book
            KamuTotemsConfig.java     — readOrCreate
            KamuTotemsCommands.java
            Chime.java
        src/main/resources/
            fabric.mod.json
            reactions.json            — default reaction table
            components.json           — default kamu catalog
```

Config at `config/kamutotems/`, generated on first boot via `readOrCreate` —
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
- `archivesName = "MrPinoys_kamutotems"`
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

**The shop sells three listings and nothing else — sigils II, III and IV.**

No kamu are ever sold. No totem is ever sold. If a player can buy a kamu, the
boss loop is decorative, and the one collection mechanic in the whole suite
becomes a price list.

```json
"sigil_ii": {
  "item": "minecraft:echo_shard",
  "quantity": 1, "price": 6, "currency": "diamond", "category": "Kamu Totems",
  "components": {
    "minecraft:custom_data": { "kamutotems": { "sigil": 2, "rolled": false } },
    "minecraft:item_name": "Sealed Sigil — Second Trial",
    "minecraft:lore": ["The spirits within are not yet known."]
  }
}
```

Tiers III and IV are the same listing with a different `sigil` value and price.
`kamutotems` rolls the boss and rewrites `item_name` and `lore` on first sight
(§7.3).

**The totem is granted, not sold.** On first join, if a player has never held
one, give them a Kamu Totem via `giveOrDrop`. `/totem` re-issues a *dormant,
empty* totem to a player who has none — losing it is a setback, not a wall,
and a totem with no kamu in it is worth nothing anyway.

> **Open question, inherited from `cobblebending`.** The `components` parser must
> express `max_damage`, `repairable`, and (if the totem-of-undying host is used)
> the *removal* of `death_protection`. Since the totem is no longer a shop item
> this matters less than it did — but the sigil still needs its lore rewritten
> per-purchase, which the parser certainly cannot do. **The stamp-on-sight
> pattern in §7.3 is therefore the primary mechanism, not a fallback**, and it
> covers both cases.

---

## 16. Verify before writing code

> **Checked 2026-08-13** against `minecraft-merged-deobf-26.2.jar` in the Loom
> cache, during integration. Results are recorded inline below. Nothing in this
> list invalidated a design decision; two items changed an implementation, and
> exactly one remains provable only on a live server.

Nothing in §6 is safe until these are checked against
`minecraft-merged-deobf-26.2.jar`. In priority order — the first three can each
invalidate a design decision.

1. **Can `DEATH_PROTECTION` be stripped from `totem_of_undying`,** and does a
   stripped totem then survive a lethal hit without being consumed? If not, drop
   to `nautilus_shell` and lose the theme. *Blocks §6.1.*
2. **Does `ALLOW_DEATH` fire before vanilla's own totem check?** A player with a
   real totem of undying in the main hand and a Kamu Totem in the offhand must
   not burn both. *Blocks §6.4.*
3. **Does `ALLOW_DAMAGE` re-enter when a construct's own effect deals damage?**
   Almost certainly yes. Confirm the shape of the re-entrancy guard before
   building the funnel. *Blocks §6.2.*
4. **Can two totems be combined on a vanilla anvil,** and what happens to the
   kamu of the sacrificed one? Confirm the block works. *Shipping requirement,
   §6.4.*
5. Does `max_damage` on a normally-undamageable item render a durability bar on
   a vanilla client? (The charge display depends on it.)
6. Is `Repairable` still in `net.minecraft.world.item.enchantment`?
   ✅(inherited) but re-confirm, it is a strange location.
7. `sgui` 2.1.0+26.2 slot-click semantics for a menu that must reject invalid
   kamu placements.
8. Boss bar API surface for a custom name that changes as kamu are revealed.

### Findings — 2026-08-13

| # | Result |
|---|---|
| **1** | **✅ half / ⚠ half.** `DataComponents.DEATH_PROTECTION` and `ItemStack.remove(DataComponentType)` both exist, so the strip compiles and is well-formed. **That vanilla then declines to consume the stack can only be proven on a live server** — and it is the single highest-risk item in the mod: if it fails, a player loses their totem *and* every kamu in it on their first death. Test this before anything else. The `nautilus_shell` fallback is still open and costs only theme. |
| **2** | **✅ — and it resolves itself.** `checkTotemDeathProtection` is a *private* method on `LivingEntity`, called from `hurtServer`, which runs **before** `die()`. Fabric's `ALLOW_DEATH` hooks `die()`. So a real totem saves first and our hook is never reached; with no real totem, `die()` runs and ours fires. Exactly one burns, with **no ordering code at all**. |
| **3** | **✅ guarded.** `DamageFunnel` holds a per-UUID `ConcurrentHashMap.newKeySet()`, entered before resolution and released in a `finally` — re-entry is impossible even if an effect throws. Still worth one live swing with `bolt` slotted. |
| **4** | **✅.** `AnvilMenu` exposes `INPUT_SLOT` / `ADDITIONAL_SLOT` / `RESULT_SLOT` as public constants; the guard now uses those names rather than the literal `0/1/2` the first draft assumed. |
| **5** | **✅ signature.** `MAX_DAMAGE`, `MAX_STACK_SIZE`, `REPAIRABLE` all exist and are set on the stack. Whether the client *draws the bar* is cosmetic — play-verify. |
| **6** | **✅.** Confirmed at `net/minecraft/world/item/enchantment/Repairable.class`. Still a strange location; still correct. |
| **7** | **✅ resolves.** `eu.pb4:sgui:2.1.0+26.2` resolves from `maven.nucleoid.xyz`, shades into the jar, and both menus compile against it. Click *semantics* are behavioural — play-verify. |
| **8** | **✅.** `setName(Component)`, `setProgress`, `addPlayer`, `removeAllPlayers` all present. The class is at `net.minecraft.server.level.ServerBossEvent` (**not** `server.bossevents`), and its constructor takes a **`UUID` first**. |

### Other 26.2 corrections found during integration

Recorded because each is a plausible guess that is wrong, and the next mod in
this suite will hit them too:

| Assumed | Actual in 26.2 |
|---|---|
| `Level.isDay()` | **`Level.isBrightOutside()`** |
| `server.getWorldData().worldGenOptions().seed()` | **`ServerLevel.getSeed()`** (via `server.overworld()`) |
| `Level.getSharedSpawnPos()` | **`level.getRespawnData().pos()`** |
| `Registry.getHolderOrThrow(Identifier)` | `Registry.get(Identifier)` returning `Optional<Holder.Reference<T>>`. Better: use the `SoundEvents` constants and skip the registry |
| `Items.GRAY_STAINED_GLASS_PANE` | Gone. `Items.STAINED_GLASS_PANE` is a `ColorCollection<Item>`; plain `Items.GLASS_PANE` still exists |
| `ServerPlayConnectionEvents` in `fabric.api.event.lifecycle.v1` | **`fabric.api.networking.v1`** — with `PacketSender` from the same package |
| a nested `ServerPlayConnectionEvents.Handler` type | the handler parameter is **`ServerGamePacketListenerImpl`** |

`SoundEvents` is still not a uniform type — note-block entries are
`Holder.Reference<SoundEvent>`, others are bare `SoundEvent`. `Chime.play`
overloads both so no caller has to care.

---

## 17. Failure directions — chosen, not discovered

Per [SUITE_AUDIT.md §4.3](../SUITE_AUDIT.md), every persistence and error
decision names which way it should fail.

| Situation | Chosen failure |
|---|---|
| Totem at 0 charges | **Dormant, never punitive.** Player is a vanilla player. §1 |
| Totem data unparseable | Totem goes dormant, kamu preserved in the raw tag, **file untouched** |
| Kamuy record corrupt | Renamed `.dat.corrupt`, skipped, **server still boots** — the `spiritwolves` wolf-record rule |
| Kamuy record missing but player has kamu | Rebuild an *unnamed* Kamuy from the totem's slots. Never delete a build to fix a name |
| Kamuy name fails sanitisation | Falls back to *"an unnamed spirit."* Never a dropped guard, never raw input |
| Reaction cascade exceeds depth cap | **Truncates silently**, logs once. Never throws mid-effect |
| `reactions.json` unparseable | Defaults in memory, file untouched. Reactions still work |
| `components.json` unparseable | **Server refuses to start.** Booting with an empty catalog would silently erase every player's totem build on the next write |
| Boss orphaned by a crash | Reverts to an ordinary vanilla mob, no boss bar, killable |
| Boss despawned by shutdown/logout | **Sigil refunded** |
| Crash mid-fusion | **Both inputs survive, no output.** The reverse creates a kamu from nothing, and only one of those is farmable |
| Crash mid-imbue | Cobblestone is taken **first**, then the kamu moves. A crash costs the player cobble; the reverse duplicates the kamu |
| Player can't afford imbue/remove | Refused, **nothing consumed**, plain message. Never a partial charge |
| Fusion at tier 3 | Refused with a message. **Never silently consumes the inputs** |
| Sigil roll never stamped (crash between purchase and sweep) | Stays unrolled and **rolls on the next sweep**. An unrolled sigil is never consumable |
| Sigil `custom_data` unreadable | Treated as unrolled and **re-rolled**. The player keeps a usable sigil; the alternative is a dead item they paid for |
| Kamu drop won't fit inventory | Drops at the player's feet, logged. Never deleted |
| Quest progress write lost | Progress rolls **back**, never forward. A player redoing a segment is annoyed; a player skipping one is a faucet |
| Streak state unreadable | Treated as **grace day**, not a break. Never punish a player for a disk error |

Note the split on the two config files: reactions failing open is harmless,
the kamu catalog failing open is data loss. That asymmetry is the point.

---

## 18. Definition of done

**Core (no game required)**

- [ ] `KamuTotemsTest.main()` prints `N passed, 0 failed`
- [ ] `core/build.gradle.kts` is empty and a stray `import net.minecraft.*` fails to compile
- [ ] `resolve()` is deterministic — same construct + context + seed, same result, 1000 iterations
- [ ] Order matters: `fire→frost` and `frost→fire` produce different results
- [ ] A reaction loop hits the depth cap and truncates rather than hanging
- [ ] Every validation fault is prose a player could read
- [ ] Quest chain streak arithmetic handles the weekly grace, rollover, and a clock jumping backwards

**Totem**

- [ ] Totem is visible in the offhand on a fully vanilla client
- [ ] A construct fires identically for a sword, a bow, bare fists, and a steak
- [ ] **A construct that deals damage does not re-trigger itself** (§16.3)
- [ ] Lethal damage with charges left saves the player and burns exactly one
- [ ] **The save is not consumed by vanilla** — the totem still exists afterwards
- [ ] A real totem of undying in the main hand plus a Kamu Totem in the offhand burns only one
- [ ] At 0 charges: no save, no attack modification, **kamu intact**
- [ ] Every player is given a totem on first join, and `/totem` re-issues one
- [ ] The player is prompted to name their Kamuy on **first imbue**, not first join
- [ ] A `§`-injected or 200-character name is sanitised, not rendered
- [ ] **Losing the totem does not lose the Kamuy** — `/totem` returns it named,
      with its history and its slots intact
- [ ] The journal counters advance: kamu drunk, saves, bosses slain
- [ ] The totem's lore reads like a biography, not a stat block
- [ ] Imbuing charges cobblestone, and the price is visible before the click
- [ ] Removing charges cobblestone and **returns the kamu intact**
- [ ] Too little cobblestone → refused, **nothing consumed**, plain message
- [ ] t1 + t1 of the same kamu fuses to t2; t2 + t2 to t3
- [ ] Two *different* kamu do not fuse; two *different tiers* do not fuse
- [ ] Fusing at tier 3 is refused and **consumes nothing**
- [ ] A tier-3 kamu is visibly stronger than tier 1 in play
- [ ] Anvil + diamonds restores 4 charges per diamond
- [ ] **Two Kamu Totems cannot be combined on an anvil**
- [ ] Dormant totem renames itself and says why, once, not repeatedly

**Boss**

- [ ] Boss bar names every kamu it carries
- [ ] Boss drops exactly one kamu it was actually carrying
- [ ] The free daily boss is identical for every player on a given date
- [ ] Free boss is claimable exactly once per player per day across a restart
- [ ] Tier IV carries 4 distinct kamu, never a duplicate
- [ ] Shutdown despawns every boss and refunds every sigil
- [ ] **A sigil bought from `/shop` arrives sealed** — lore says the spirits are unknown
- [ ] It is rolled and its lore rewritten within one sweep interval
- [ ] The rewritten lore names every kamu the boss will carry
- [ ] Two sigils of the same tier bought by the same player roll differently
- [ ] An unrolled sigil cannot be used to summon
- [ ] A rolled sigil can be traded to another player and still works
- [ ] **Nothing in `/shop` grants a kamu or a totem** — sigils only

**Quest**

- [ ] Three segments generate from the date and survive a restart unchanged
- [ ] All three segment kinds complete (kill, turn in, scan)
- [ ] Scanning with the totem advances a scan segment
- [ ] Streak survives one missed day per rolling 7
- [ ] `/daily top` shows other players' streaks

**Suite**

- [ ] `./gradlew build` succeeds; `MrPinoys_kamutotems-0.1.0.jar` lands in `dist/`
- [ ] Zero Mixins
- [ ] Zero compile-time dependencies on any other mod
- [ ] A fully vanilla client can do all of the above with nothing installed

---

## 19. Explicitly out of scope for v1

Every application in the source specification except the three hosts here:
weapons-as-chassis, tools, armor, potions/alchemy, mob mutation, NPC behaviour,
traps, dungeon encounters, machines, farming, environmental shrines, server
events, social/reputation, and player-authored programmable items.

Also out: the advanced kamu family (Echo, Mirror, Split, Delay, Catalyst,
Chaos, Void), 4- and 5-slot totems, the complexity-budget *economy* (the star
display stays, the budget enforcement doesn't), the energy layer beyond plain
cooldowns, PvP tuning beyond an on/off flag, streak protection as a sold item,
and any interaction with the other nine mods beyond currency.

Three hosts, fifteen kamu, six reactions, three slots. **Prove that a player
who finds a kamu wants to go find another one** before building anything on top
of it.

---

## 20. Migration — retiring two mods

Build order is **totem → boss → quest**, and the two retirements happen at
different times. Neither mod is deleted before its replacement is play-verified.

**Phase 1 — Totem.** Ships the core engine and host #1. Before deleting
`cobblebending`, port these into the engine as primitives — they are the two
genuinely valuable things in that mod and they are proven in play:

- The `minecraft:consumable` / `consumeSeconds = 3600.0F` hold-and-release trick
  ([cobblebending/Focus.java](../cobblebending/src/main/java/cobblebending/Focus.java))
  — becomes a **charge-time delivery** available to any Action kamu.
- `BentBlocks`' tracked-block ledger and **revert-to-prior-state**
  ([cobblebending/BentBlocks.java](../cobblebending/src/main/java/cobblebending/BentBlocks.java))
  — required by any effect that touches the world, and it is the anti-grief
  spine. Do not rewrite this from scratch.

Hurl, Wall and Bridge then return as **shipped starter constructs**, not
hardcoded features. `cobblebending` retires once they do.

> **The cobblestone sink must land in the same release that retires
> `cobblebending`, not after it.** That mod is the suite's only cobblestone
> drain; the imbue/remove costs of §5.8 are what replace it. Ship the retirement
> without the costs and the common currency is worthless again for however long
> the gap lasts.

**Phase 2 — Boss.** Gives the totem a supply line and the shop a new sink.
Nothing retires here.

**Phase 3 — Quest.** Exercises the matcher half of the engine, which phases 1
and 2 do not touch. **`dailyquests` keeps running untouched until this lands** —
the daily loop is never empty, not for a single release. Port the streak state
rather than resetting it; a player who has a 40-day streak and loses it to a
migration will not start a new one.

> **⚠ The two cannot overlap, and the failure is silent.** Both mods register
> `/daily`, and Brigadier **merges** a duplicate root rather than rejecting it —
> so with both installed, `/daily`, `/daily turnin` and `/daily top` resolve to
> whichever mod loaded second, with no crash and no log line. Players would also
> carry two unrelated streaks and two daily payouts.
>
> The quest host is therefore **`quest.enabled = false` by default** (added
> during integration, 2026-08-13). Phases 1 and 2 run with it off and
> `dailyquests` installed; phase 3 sets it true **and removes `dailyquests` in
> the same restart**. The startup log states which mode is active.
>
> `cobblebending` has no such conflict — no shared command, no shared item — so
> its retirement is gated only on its replacement constructs being ready.

> The reason for this order: the totem proves the effect engine on a live server
> while `dailyquests` covers the daily loop, and the quest host — the one that
> replaces a working mod — is built last, against an engine that two hosts have
> already shaken out.
