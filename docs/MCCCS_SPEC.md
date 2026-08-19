# Minecraft/ NOITA Combinatorial Components System
## Core Engine & Application Specification

**Status:** Design Specification  
**Target:** Minecraft Java Edition — Server-Side Mod  
**Primary Goal:** Create a reusable server-side system where players combine a small number of intuitive components to produce surprising, discoverable interactions without requiring players to understand graphs, scripting, or programming.

---

# 1. Design Philosophy

The system is built around one simple player-facing idea:

> **Find components. Combine them. Discover what happens.**

The original underlying system uses directed graphs, triggers, payloads, branching, loops, modifiers, and reaction resolution. That architecture is powerful, but exposing those concepts directly to Minecraft players creates an unnecessary programming burden. The original system's separation between data, graph resolution, and execution should therefore be retained internally while the player-facing model is dramatically simplified.

The simplified system should preserve three qualities:

1. **Combinatorial depth** — small numbers of components create many possible behaviors.
2. **Contextual interaction** — components can behave differently depending on what they interact with.
3. **Discovery** — players should not need to know every combination in advance.

The player should feel like they are experimenting with Minecraft alchemy, not programming a computer.

---

# 2. Player-Facing Mental Model

Every construct follows the basic pattern:

**DO + WITH + WHEN**

### DO — Action

What happens?

Examples:

- Strike
- Shoot
- Throw
- Dash
- Explode
- Heal
- Summon
- Place
- Teleport

### WITH — Modifiers

How does it behave?

Examples:

- Fire
- Ice
- Shock
- Pierce
- Chain
- Homing
- Explosive
- Bigger
- Faster
- Lifesteal
- Echo
- Mirror

### WHEN — Trigger

When does it happen?

Examples:

- On Use
- On Hit
- On Kill
- On Damage
- On Block Break
- On Sneak
- On Jump
- On Enter
- On Interact

The player should be able to read a construct as a sentence:

> **When I kill something, explode and knock nearby enemies back.**

Internally, this can resolve into the much more sophisticated event/graph machinery.

---

# 3. Core Player Constraint: Three Component Slots

The default construct contains a maximum of **three component slots**.

```text
┌─────────────────────────┐
│        CONSTRUCT        │
│                         │
│       [ ACTION ]        │
│           ↓             │
│      [ MODIFIER ]       │
│           ↓             │
│      [ MODIFIER ]       │
│                         │
│ WHEN: [ OPTIONAL ]      │
└─────────────────────────┘
```

The three-slot limit is the primary accessibility constraint.

It prevents the system from becoming a programming language while retaining a large combinatorial space.

A component may occupy one of three conceptual roles:

- Action
- Modifier
- Trigger

The UI should guide players toward sensible configurations rather than requiring them to understand valid graph topology.

Advanced progression may unlock:

- 4-slot constructs
- 5-slot constructs
- special Echo behavior
- special Mirror behavior
- conditional components
- multi-target behaviors

These are progression rewards, not baseline complexity.

---

# 4. Core Engine Architecture

The core engine retains the original system's architectural separation:

```text
Minecraft Event
      ↓
Application Host
      ↓
Construct
      ↓
Resolver
      ↓
Effect Context
      ↓
Reaction / Effect Engine
      ↓
Minecraft World
```

The engine must remain independent from individual applications wherever practical.

The core should know about:

- components
- actions
- modifiers
- triggers
- events
- contexts
- effects
- reactions
- resolution
- validation

It should not inherently know that a construct is a sword, spell, trap, NPC, quest, or machine.

---

# 5. Core Data Model

## 5.1 Component

```text
Component
- Id
- DisplayName
- Category
- Rarity
- Complexity
- EffectId
- Parameters
- Tags
```

A component is a data definition.

An owned component is an instance of that definition.

The original system uses a catalog that maps identifiers to factories producing fresh modifier instances. The same model should be retained.

---

# 6. Component Categories

## 6.1 Action

An Action represents the primary operation.

Examples:

```text
Strike
Shoot
Throw
Dash
Explode
Heal
Summon
Teleport
Place
```

An action contains:

```text
Action
- Delivery
- BaseCost
- BasePower
- Parameters
```

The original system's `SkillData` already provides a useful model for this, including delivery archetype, base cost, base damage, and free-form shape parameters.

---

# 7. Modifier

A Modifier changes the behavior of an action.

Examples:

### Elemental

- Fire
- Ice
- Water
- Shock
- Acid

### Physical

- Pierce
- Knockback
- Bounce
- Homing
- Chain

### Scaling

- Stronger
- Faster
- Bigger
- Longer

### Reactive

- Lifesteal
- Detonate
- Echo
- Mirror

The original system's modifier model is appropriate here: behavior is identified by an effect ID and parameters rather than requiring a unique class for every modifier.

---

# 8. Trigger

A Trigger specifies when a construct activates.

Examples:

```text
OnUse
OnHit
OnKill
OnDamage
OnBlockBreak
OnJump
OnSneak
OnInteract
OnEnter
OnExit
OnProjectileHit
OnDeath
```

Player-facing terminology should use **WHEN** rather than "Trigger."

Internally, the engine may retain `Trigger` terminology.

---

# 9. Effect Registry

All component behavior is dispatched through a central registry.

```text
EffectId → EffectHandler
```

Example:

```text
fire       → FireEffect
ice        → IceEffect
pierce     → PierceEffect
chain      → ChainEffect
explode    → ExplosionEffect
echo       → EchoEffect
mirror     → MirrorEffect
lifesteal  → LifestealEffect
```

This preserves the original system's principle that modifier behavior should live in a small dispatch table rather than being hardcoded into component objects.

Adding a basic modifier should therefore normally require:

1. One catalog entry.
2. One effect implementation, if behavior is genuinely new.
3. Optional application-specific handling.

---

# 10. Construct

A Construct is the simplified player-facing equivalent of the original Rite.

```text
Construct
- Id
- HostType
- Slots[]
- Trigger?
- Complexity
- Metadata
```

Example:

```text
Action: Shoot
Modifier: Fire
Modifier: Pierce
Trigger: OnUse
```

The Construct is immutable while being resolved.

---

# 11. Resolution Model

The resolver converts a simple Construct into an internal execution plan.

```text
Resolve(Construct, HostContext)
    ↓
Validate
    ↓
Build Execution Context
    ↓
Apply Trigger
    ↓
Apply Modifiers
    ↓
Resolve Interactions
    ↓
Produce Effects
```

The resolver should return:

```text
ResolutionResult
- Valid
- Cost
- Effects
- Reactions
- Faults
- Discoveries
```

The original system already uses a pure resolver that converts a build structure into ordered discharges and faults. The simplified system should preserve the deterministic nature of that resolution process.

---

# 12. Context

Every resolution occurs within a Context.

```text
Context
- Source
- Target
- Location
- World
- CurrentEvent
- ActiveElements
- ActiveEffects
- PreviousEffects
- Environment
- Tags
```

Context is critical.

The same component should be capable of producing different outcomes depending on context.

For example:

```text
Fire + Oil
```

may produce a firestorm.

```text
Fire + Water
```

may produce steam.

```text
Fire + Ice
```

may melt or thermally shock.

This contextual behavior is what makes combinations more interesting than simple additive stats.

---

# 13. Reaction Engine

The Reaction Engine resolves interactions between components and the current world state.

The original system's reaction table provides the correct conceptual model: ordered interactions are evaluated against the target's substance and existing coatings/statuses.

Example:

```text
Fire + Oil
→ Firestorm

Shock + Water
→ Conduct

Ice + PhysicalHit
→ Shatter

Water + Oil
→ Wash

Acid + Metal
→ Corrosion
```

Reactions should be data-driven.

```text
ReactionRule
- A
- B
- Conditions
- Outcome
- Priority
- Terminal
```

This permits applications to add new reactions without changing the core engine.

---

# 14. Ordering

Component order should matter.

For example:

```text
Fire → Ice
```

does not necessarily need to equal:

```text
Ice → Fire
```

The exact behavior should depend on the application.

Order creates a second dimension of experimentation without requiring additional UI complexity.

Players naturally learn:

> "Oh. The order matters."

---

# 15. Hidden Complexity

The engine may support significantly more complexity than the player interface exposes.

Internally, it may support:

- branching
- payloads
- event propagation
- chained effects
- loops
- recursive effects
- conditional execution
- delayed execution
- multiple discharges
- reaction cascades

These concepts should **not** be mandatory concepts for ordinary players.

The original graph architecture's Split, Loop, Trigger, and Payload concepts can remain useful internally or as advanced components.

---

# 16. Advanced Components

Once the basic system is established, advanced components can introduce controlled complexity.

## Echo

> Repeat the previous effect.

Example:

```text
Fire + Echo
```

→ Fire happens twice.

## Mirror

> Copy the previous effect onto another target.

## Split

> Apply the effect to multiple targets.

## Delay

> Wait before executing.

## Chain

> Continue onto another target.

## Catalyst

> Strengthen the interaction between adjacent components.

## Chaos

> Randomize one part of the construct.

## Void

> Remove or cancel the previous component.

These should feel like **special discoveries**, rather than basic vocabulary.

---

# 17. Complexity Budget

Every construct has a Complexity value.

Example:

```text
Fire             1
Pierce           1
Explosion        2
Chain            2
Echo             3
Mirror           3
```

A basic artifact might support:

> Complexity 3

An advanced artifact:

> Complexity 8

This is preferable to exposing raw energy calculations.

The original system's energy economy can still exist internally as an execution/balance mechanism, but players should primarily see an understandable complexity budget. The original system already treats interaction/build complexity as an important power limiter.

---

# 18. Energy

Energy remains an internal balancing mechanism.

It should:

- limit sustained output
- prevent infinite chains
- make powerful effects expensive
- allow regeneration
- prevent extremely powerful combinations from being spammed

The exact additive-cost model from the original engine can be retained internally.

The UI should instead communicate:

```text
Complexity: ★★★☆☆
Power:      ★★★★☆
Cost:       Moderate
```

rather than exposing implementation details.

---

# 19. Discovery System

Discovery is a first-class mechanic.

When a player creates a previously unseen interaction, the server records it.

Example:

```text
NEW DISCOVERY

🔥 FIRESTORM

Oil + Fire

"Something went very wrong."
```

The player can maintain a **Discovery Book** containing:

- discovered components
- discovered reactions
- discovered constructs
- rare combinations
- first-discoverer records

The exact recipe can optionally remain hidden.

---

# 20. Discovery Tiers

### Known

The player has discovered the combination.

### Hinted

The player knows that two components interact but not how.

### Unknown

The player has never encountered it.

### Secret

The server intentionally provides no recipe hint.

This allows server administrators to hide rare discoveries.

---

# 21. Application Framework

The Core Engine should expose an application interface:

```text
IConstructHost
```

A host provides:

```text
- Valid components
- Available triggers
- Context builder
- Effect adapters
- Validation rules
- Result application
```

The same core engine can then support many systems.

---

# 22. Application: Weapons

Weapons are the most direct application.

Example:

```text
Bow
+ Fire
+ Pierce
```

→ Piercing Fire Arrows.

```text
Sword
+ Shock
+ Chain
```

→ Lightning that jumps between enemies.

```text
Axe
+ Lifesteal
+ OnKill
```

→ Killing an enemy restores health.

The original system already treats weapons as chassis whose power comes primarily from combinations rather than raw stats.

---

# 23. Application: Spells

A spell is simply another Construct Host.

Example:

```text
Shoot
+ Fire
+ Explosion
```

→ Explosive Fireball.

```text
Throw
+ Ice
+ Chain
```

→ Chaining Ice Bomb.

```text
Teleport
+ Echo
```

→ Teleport twice.

---

# 24. Application: Tools

Tools modify Minecraft actions.

### Pickaxe

```text
Block Break
+ Pierce
+ Echo
```

→ Break additional connected blocks.

### Axe

```text
Block Break
+ Chain
```

→ Continue through connected logs.

### Shovel

```text
Block Break
+ Area
```

→ Clear an area.

### Hoe

```text
Block Interact
+ Growth
+ Echo
```

→ Accelerate nearby crops.

---

# 25. Application: Armor

Armor reacts to events affecting the player.

### Fire Armor

```text
WHEN: OnFire
DO: Fire Resistance
```

### Revenge Armor

```text
WHEN: OnDamage
DO: Shock
```

### Berserker Armor

```text
WHEN: Low Health
DO: Strength + Speed
```

### Reactive Armor

```text
WHEN: OnProjectileHit
DO: Deflect
```

---

# 26. Application: Artifacts

Artifacts are persistent constructs.

An artifact might be:

```text
WHEN: OnKill
DO: Heal
WITH: Echo
```

Artifacts are particularly suitable for rare and unusual combinations because they can have small but powerful component budgets.

---

# 27. Application: Potions and Alchemy

Ingredients become components.

Instead of fixed recipes, players experiment.

Examples:

```text
Water + Fire
→ Steam

Oil + Fire
→ Firestorm

Ice + Water
→ Freeze

Poison + Fire
→ Toxic Flame
```

The potion system becomes an extension of the Reaction Engine.

---

# 28. Application: Mob Mutations

Players can modify mobs with behavioral components.

Example:

```text
Zombie
+ Fire
+ OnDamage
```

→ Damaging the zombie causes a fire effect.

```text
Spider
+ Speed
+ Poison
```

→ Fast poisonous spider.

```text
Skeleton
+ Ice
+ OnDeath
```

→ Ice explosion on death.

The host determines which components are legal for a particular mob.

---

# 29. Application: NPC Behaviors

NPCs can have simple behavioral constructs.

```text
WHEN: PlayerNearby
DO: Greeting
```

More advanced:

```text
WHEN: PlayerSteals
DO: IncreaseSuspicion
```

Then:

```text
WHEN: SuspicionHigh
DO: RefuseService
```

NPCs can therefore have emergent behavior without requiring every NPC to be individually scripted.

---

# 30. Application: Traps

A trap is a Construct triggered by the environment.

```text
WHEN: PlayerEnters
DO: Explosion
WITH: Delay
```

Or:

```text
WHEN: PlayerEnters
DO: Spawn
WITH: Zombies + Delay
```

Or:

```text
WHEN: PlayerEnters
DO: LockDoors
WITH: Spawn + Fire
```

---

# 31. Application: Dungeon Mechanics

Dungeon creators can use the same system.

Example:

```text
Player Enters
      ↓
Spawn Mobs
      ↓
On Kill
      ↓
Open Door
```

A more advanced encounter:

```text
Player Enters
      ↓
Spawn Wave
      ↓
On Kill
      ↓
Spawn Next Wave
      ↓
On Final Kill
      ↓
Boss
```

The system can therefore become a server-side encounter scripting framework.

---

# 32. Application: Quests

Quest objectives become events and effects.

Example:

```text
Enter Village
→ Talk to NPC
→ Receive Item
→ Enter Cave
→ Kill Target
→ Retrieve Item
→ Return
→ Reward
```

Branching can remain hidden from the player.

```text
Help Villagers
→ Reward A

Betray Villagers
→ Reward B
```

---

# 33. Application: Machines

Machines can consume and transform components.

```text
Input
→ Process
→ Reaction
→ Output
```

Example:

```text
Water
+ Lava
→ Obsidian
```

A more elaborate custom system:

```text
Ore
+ Heat
+ Pressure
→ Refined Material
```

The same reaction infrastructure can therefore power both combat and crafting.

---

# 34. Application: Farming

Environmental events can become triggers.

```text
Rain
→ Water
→ Growth
```

or:

```text
Night
→ Moonlight
→ Growth
```

Plants can have combinations of environmental and biological properties.

---

# 35. Application: Environmental Shrines

World structures can respond to combinations of conditions.

Example:

```text
Thunderstorm
+
Night
+
PlayerHoldingAmethyst
→ Activate Shrine
```

Another:

```text
Full Moon
+
PlayerInBiome
+
Specific Item
→ Open Portal
```

This creates discovery-based world secrets.

---

# 36. Application: Server Events

The engine can drive temporary server-wide mechanics.

Example:

```text
Blood Moon
→ Zombies
+ Speed
+ Fire Resistance
+ OnKill
→ Spawn Reinforcements
```

Or a server event might temporarily modify existing reaction rules.

---

# 37. Application: Social / Reputation Systems

The same reaction concept can be applied to social states.

Example:

```text
Trusted + Betrayed
→ Vengeful
```

```text
Feared + Respected
→ Intimidating
```

NPCs can respond to these resulting states.

This should remain an optional advanced application rather than part of the base system.

---

# 38. Application: Programmable Items

A particularly powerful late-game application is allowing players to create reusable Constructs.

A player might create:

### "Executioner's Charm"

```text
WHEN: OnKill
DO: Heal
WITH: Echo
```

### "Storm Bow"

```text
WHEN: OnUse
DO: Shoot
WITH: Shock + Chain
```

### "Mining Greed"

```text
WHEN: OnBlockBreak
DO: Echo
WITH: OreOnly
```

Constructs can then be stored, traded, sold, or embedded into items.

---

# 39. What Players Should NOT Need to Understand

The following concepts should remain implementation details:

- directed graphs
- node convergence
- payload edges
- discharge objects
- resolver traversal
- energy summation
- effect dispatch
- event propagation
- execution contexts
- terminal reaction rows
- recursive resolution

The player should understand:

> **What does this component do?**

and:

> **What happens if I put it with that component?**

---

# 40. Recommended Progression

## Stage 1 — Simple

Players receive:

- 1 Action
- 1 Modifier

They learn:

> Components combine.

## Stage 2 — Interaction

Players receive a second modifier slot.

They discover:

> Components interact.

## Stage 3 — Reactions

Players discover contextual combinations.

They learn:

> Order and environment matter.

## Stage 4 — Triggers

Players unlock:

> WHEN

They discover:

> Abilities don't have to happen immediately.

## Stage 5 — Advanced Components

Players discover:

- Echo
- Chain
- Mirror
- Split
- Delay
- Catalyst

They begin creating genuinely strange behaviors.

## Stage 6 — Mastery

Players have enough components to intentionally engineer complex constructs.

---

# 41. Server-Side Requirements

The entire core engine must be server authoritative.

The client should not need a custom mod.

The server owns:

- component definitions
- construct definitions
- resolution
- validation
- reactions
- discoveries
- cooldowns
- energy
- effects
- persistence

The player interface should use vanilla-compatible server-side mechanisms wherever possible:

- inventory GUIs
- item displays
- chat
- boss bars
- action bars
- books
- vanilla particles
- sounds
- item names/lore
- server-controlled entities

A client-side mod may optionally enhance visualization but must not be required.

---

# 42. Determinism

The core resolver should be deterministic.

Given:

```text
Construct
+
Context
+
Seed
```

it should produce the same resolution.

This makes the engine testable and prevents client/server disagreement.

The original architecture explicitly targets deterministic, headless-testable logic.

---

# 43. Validation

Before execution, every Construct must be validated.

Examples:

```text
No Action
→ Invalid

Unknown component
→ Invalid

Too much complexity
→ Invalid

Trigger without compatible action
→ Warning / Invalid depending on host

Unsupported component for host
→ Invalid
```

Validation should produce player-friendly messages.

Bad:

> `RiteFault: Payload has no attack`

Good:

> **This effect needs something to activate. Try adding an action.**

---

# 44. Component Compatibility

Not every component should work everywhere.

Each component may specify:

```text
AllowedHosts
RequiredTags
ForbiddenTags
```

Example:

**Fire**

```text
Weapon ✓
Spell ✓
Potion ✓
NPC ✗
Quest ✗
```

**Greeting**

```text
NPC ✓
Quest ✓
Weapon ✗
```

This prevents nonsensical combinations while keeping the core generic.

---

# 45. Balance Philosophy

Balance should primarily come from:

1. Complexity budget.
2. Energy/cooldown.
3. Component rarity.
4. Contextual limitations.
5. Host restrictions.
6. Reaction availability.

Avoid making the system primarily about:

> +5 damage versus +7 damage.

The interesting question should be:

> **What behavior can I create?**

not:

> **Which component has the biggest number?**

---

# 46. Content Design Rule

Every new component should ideally satisfy at least one of these:

### Combines

It interacts meaningfully with another component.

### Transforms

It changes how another component behaves.

### Reacts

It produces a special outcome under a condition.

### Enables

It makes a previously impossible combination possible.

### Surprises

It has an unintuitive but discoverable interaction.

Avoid adding components that are merely:

> "Fire, but +10% stronger."

---

# 47. Example Component Set

A small initial content set could be:

### Actions

- Strike
- Shoot
- Throw
- Dash
- Explode

### Elements

- Fire
- Ice
- Water
- Shock
- Poison

### Behaviors

- Pierce
- Chain
- Homing
- Knockback
- Lifesteal

### Triggers

- OnUse
- OnHit
- OnKill
- OnDamage
- OnBlockBreak

### Advanced

- Echo
- Mirror
- Split
- Delay
- Catalyst

This is only ~25 components, yet the possible combinations are already substantial.

---

# 48. Example Player Experience

A player finds three items:

**Fire Essence**

> "Ignites things."

**Echo Rune**

> "Repeats the previous effect."

**Arrow Core**

> "Launches a projectile."

They create:

```text
Arrow Core
+
Fire
+
Echo
```

The server produces:

> 🔥 **DOUBLE FIRE ARROW**

The player then tries:

```text
Arrow
+
Fire
+
Chain
```

and discovers:

> 🔥⚡ **CHAINING FLAME**

They try:

```text
Arrow
+
Fire
+
Explosion
```

and get:

> 💥 **FIREBOMB**

Then:

```text
Arrow
+
Fire
+
OnKill
```

and discover:

> 🔥 **BURNING EXECUTION**

The system becomes a toy that players experiment with.

---

# 49. The Core Principle

The system should have a **simple surface and a deep substrate**.

### Surface

```text
DO
WITH
WHEN
```

### Substrate

```text
Events
→ Context
→ Resolver
→ Effects
→ Reactions
→ Cascades
→ World
```

This is the central architectural decision.

Do **not** simplify the engine until it loses its combinatorial behavior.

Instead:

> **Keep the sophisticated machinery underneath and give the player a tiny vocabulary for manipulating it.**

---

# 50. Final Architecture

```text
                    ┌──────────────────┐
                    │  Minecraft Event │
                    └────────┬─────────┘
                             ↓
                    ┌──────────────────┐
                    │  Construct Host  │
                    └────────┬─────────┘
                             ↓
                  ┌──────────────────────┐
                  │      Construct       │
                  │                      │
                  │  ACTION              │
                  │      ↓               │
                  │  MODIFIER            │
                  │      ↓               │
                  │  MODIFIER            │
                  │                      │
                  │  WHEN (optional)     │
                  └──────────┬───────────┘
                             ↓
                    ┌──────────────────┐
                    │     Resolver     │
                    └────────┬─────────┘
                             ↓
                    ┌──────────────────┐
                    │      Context     │
                    └────────┬─────────┘
                             ↓
              ┌────────────────────────────┐
              │       Effect Registry      │
              └─────────────┬──────────────┘
                            ↓
              ┌────────────────────────────┐
              │       Reaction Engine      │
              └─────────────┬──────────────┘
                            ↓
              ┌────────────────────────────┐
              │      Minecraft Effects     │
              └────────────────────────────┘
```

The same engine can then power:

**Weapons → Spells → Tools → Armor → Artifacts → Potions → Mobs → NPCs → Traps → Dungeons → Quests → Machines → Farming → Shrines → Server Events.**

That is the real payoff.

You're not building thirteen systems.

You're building **one interaction engine** and giving Minecraft objects different ways to host it.

The player's experience remains extremely simple:

> **Put things together. Try it. Discover something weird.**