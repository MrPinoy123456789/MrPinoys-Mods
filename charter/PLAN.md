# Charter: implementation plan

**Status:** plan for approval, 2026-10-24. Nothing built. Written against
`charter/DESIGN.md` as committed at `dacc2e58`.
**Goal:** the best possible first playtest build of the supply-line siege, with
enough of governance and justice to make the politics real.

Section numbers below (§n) refer to `charter/DESIGN.md` unless they say otherwise.

---

## 1. The first playtest build

**What players can do.** Found a Commune or a Lordship, invite members, vote,
name officers and a Lord, and grant powers down a tree of organizations. Place a
nexus to root a 3x3 keep with a granary, vault and charter lectern, grow farms and
roads outward as outlying claims, and plant war banners to raise camps. Raise a
camp in the open under a smoke column, then besiege a keep or raid a farm: the
granary eats real food item by item, runners carry food in on foot or by minecart
through open sides, a ring of camps encircles and starves the keep, defenders
present slow the drain, attackers present speed it, a sally breaks a camp, and a
starving keep can be assaulted. When it falls the besieger razes or occupies it;
plunder spills; farms reassign or orphan with grace. A sheriff can arrest a
trespasser on their own land, jail them in a cell where they eat from the
granary, fine them, or execute them (a short respawn delay and banishment). Every
one of these events lands in the faction's lectern book, the Realm Chronicle at
spawn, and a structured siege log for the debrief. Operators can compress time
and set up any of this in minutes.

### In and out

| From §21 | Item | Verdict | Why |
|---|---|---|---|
| v0 | Organizations, tree, membership, Commune and Lordship, offices, grants, timed votes, automatic succession | **In** | Siege authority (who may raise a camp, who may open the granary) is grants. Without them the siege is just a timer |
| v0 | Treasury and vault | **In** | Keep and banner prices, plunder and fines all need it. Small |
| v0 | Keeps, camps, outlying claims, adjacency, capacity, range, member cap, protection | **In** | The board the siege is played on |
| v0 | Full supply siege: raising, war footing, carry-in, encirclement, presence, starvation and assault, the fall, truce | **In** | The question the playtest exists to answer |
| v0 | Core loss, orphan grace, new-faction protection | **In** | Without reassignment a fallen keep leaves a broken map; protection is a one-line check that ops can waive |
| v0 | Action engine, history, Chronicle | **In** | The Chronicle is how players read what happened; the history file feeds the debrief |
| v1 | Occupation (imposed contract on the occupation basis) | **In** | It is half of the fall (§10.8). A fall with only raze tests half the endgame |
| v1 | Signed and imposed contract **model** (terms, bases, caps, lifecycle) | **In, core only** | Justice and occupation are imposed contracts. Building the model once, fully tested, is cheaper than building justice twice |
| v1 | Justice: arrest, cells, confinement, execution, banishment, custody fine, pardon, weekly cap | **In** | The owner cares about it, it is the first real use of imposed contracts, and a jail sentence is one of the scenarios the first session must try |
| v1 | Notoriety (rise, decay, drain effect) | **In, minimal** | Imposed contracts cost notoriety and the drain bound includes it. One number per faction; no posture label yet |
| v1 | Signed contracts with escrow, peace treaties, conditional surrender, war score | **Out** | Blocked by the war score versus war goals decision (D11). The first playtest records every war event per pair of factions so either model can be scored afterwards |
| v1 | Contract board and sellsword jobs | **Out** | Needs escrow and signed contracts. Sellsword running is still possible in play: D7 deposit grants let an outsider carry food in |
| v1 | Secession with a People fork | **Out** | Needs a population that has lived together. Test E waits |
| v1 | Settling (camp to keep), posture labels, trophies, dormancy | **Out** | Settling and dormancy run on 3 and 30 day clocks a playtest will not reach; labels and trophies are cosmetic. Trophies are cheap and can ride along if a milestone finishes early |
| Later | Unrest and distance costs; Monarchy, Council, Company presets | **Out** | Needs tribute contracts and a larger server |
| New | Labour terms (imposed bond, signed indenture) | **Out of the first build, decision needed** | See D12. The imposed bond could ride on the justice milestone if the owner wants it tested |

The cut leans toward the siege loop and toward justice because those are the two
things a first session can actually exercise in an evening. Everything that only
matters after days of play (settling, dormancy, secession, unrest) waits until a
server has lived that long.

## 2. Architecture

### 2.1 Build layout (copied from `ballot`)

```
charter/
  settings.gradle.kts        include("core"), include("fabric")
  gradle.properties          minecraft 26.2, loader 0.19.3, fabric api 0.156.0+26.2
  core/                      java-library, no dependencies at all, JDK 25
    src/main/java/charter/...
    src/test/java/charter/CoreSuite.java    main() runner, wired as `coreTest`
  fabric/                    Loom, implementation + include(project(":core")), sgui
    src/main/java/charter/mc/...
    src/main/resources/fabric.mod.json, charter.mixins.json, data/charter/...
    src/test/java/charter/mc/...            plain Java tests with Gson (store, config)
    src/gametest/java/charter/gametest/...
    src/gametest/resources/fabric.mod.json  mod id charter-gametest, lists every class
```

`core` keeps Minecraft off its classpath, so an import of a Minecraft type fails
compilation. `fabric` adds the gametest source set the way `pocketdungeons` does
(`fabricApi.configureTests` with `enableGameTests = true`, client gametests off).
The jar is `MrPinoys_Charter-<version>.jar`, copied to the suite's `dist/` by a
`dist` task finalizing `build`, as in `ballot`.

Guessed, because `MOD_AUDIT.md` is not in this checkout: the "remapJar trap" the
suite doc mentions. `ballot` uses `jar` (not `remapJar`) as the `dist` input
because 26.x is unobfuscated; Charter copies that and verifies in M0 that the jar
in `dist/` loads on a dedicated server.

### 2.2 The `core` model

Everything is plain Java records and classes, keyed by permanent ids, with no
Minecraft types. Items are identified by their registry id string
(`"minecraft:bread"`), chunks by a `ChunkKey(String dimension, int x, int z)`.

| Package | Main types | Notes |
|---|---|---|
| `charter.id` | `OrgId`, `PlayerId`, `CoreId`, `ClaimId`, `ContractId`, `GrantId`, `VoteId`, `OfficeId`, `ChunkKey` | UUID wrappers so a `PlayerId` can never be passed where an `OrgId` is meant |
| `charter.time` | `Clock`, `SiegeClock`, `Duration` helpers | Two clocks: wall time (votes, membership age, real-time sentences) and siege time (scaled, see §4 of this plan). Both injected, so tests drive time directly |
| `charter.org` | `Organization`, `OrgTree`, `Membership`, `MemberRecord`, `Role`, `People`, `OrgStatus` | The tree enforces one parent and no cycles on every change. `People` is identity only |
| `charter.gov` | `Governance`, `Preset` (`COMMUNE`, `LORDSHIP`), `DecisionRule`, `ActionCategory`, `Office`, `Vote`, `VoteMethod`, `Electorate`, `Succession` | Presets are factories that fill `Governance`; nothing branches on preset after founding |
| `charter.grant` | `Grant`, `GrantScope`, `Power`, `AuthorityResolver`, `DescendantCache` | `AuthorityResolver` walks grantor chains at execution time (§3 of this plan) |
| `charter.treasury` | `Ledger`, `Currency` (`COBBLESTONE`, `DIAMOND`), `Deposit` | Integer item counts only; never a rate between the two |
| `charter.land` | `Core`, `CoreKind` (`KEEP`, `CAMP`), `CampPhase` (`RAISING`, `ACTIVE`, `PACKED`), `Claim`, `ClaimKind` (`KEEP_CORE`, `KEEP_RING`, `CAMP`, `OUTLYING`), `LandGraph`, `Perimeter`, `Reassignment` | `LandGraph` owns adjacency, hop assignment (fewest hops, older core wins ties), capacity, range, the active member cap, perimeters and reassignment after a core is lost |
| `charter.food` | `FoodTable`, `FoodEntry` (nourishment, `ShelfClass`), `Provisions` (item id to count), `EatPlan`, `SpoilagePlan`, `Mouths` | Pure maths over item counts. The fabric side turns a chest into a `Provisions` and applies the plans back |
| `charter.siege` | `SiegeSim`, `KeepSupply` (`SUPPLIED`, `BESIEGED`, `ENCIRCLED`), `SiegeState`, `CampSupply` (`OPEN`, `CUT_OFF`, `EMPTY`), `Hold`, `DrainModel`, `Presence`, `Allowance`, `AllowanceLedger`, `Truce`, `FallChoice` (`RAZE`, `OCCUPY`), `Notoriety` | One `step(delta)` advances every drain, timer and state transition, and returns events |
| `charter.contract` | `Contract`, `ContractState`, `Consent` (`SIGNED`, `IMPOSED`), `Basis` (`CUSTODY`, `WAR_SCORE`, `OCCUPATION`), `Term` (sealed: `Payment`, `Fine`, `Delivery`, `GrantTerm`, `Access`, `Confinement`, `Banishment`, `RespawnDelay`, `RelationLock`, `ClaimLimit`, `Cede`, `Release`), `BasisCaps`, `PunishmentLedger` | The whole model is built and tested; only custody and occupation are reachable in the first build |
| `charter.chronicle` | `HistoryEvent` (sealed, one record per event kind), `Template`, `TemplateSet`, `Calendar`, `ChronicleRenderer` | Templates are data; the renderer fills names, places and numbers. The calendar turns siege time into "the second week of Frostfall" |
| `charter.engine` | `Action` (interface), `ActionContext`, `ActionEngine`, `Outcome` (`Done` or `Refused(reason)`), `Effects`, `Actor` (`Player`, `Admin`, `System`) | §3 of this plan |
| `charter.state` | `CharterState` | The root aggregate: every org, grant, vote, core, claim, contract, siege, allowance and the two clocks. The only mutable root, so a snapshot is one object |
| `charter.config` | `Knobs` | Every §24 value as a plain record with defaults. `fabric` fills it from JSON |

### 2.3 What stays in `core`, what must live in `fabric`

**In `core`, pure JDK, unit tested:** every rule in the design that can be
stated over ids, chunk coordinates and item counts. That is the grant chain,
votes and succession, the land graph and perimeters, encirclement, mouths and
storage limits, eating and spoilage plans, the full siege state machine with
presence and notoriety inputs, allowances (as ledgers of item counts with expiry),
contract caps and the weekly ledger, notoriety, and the Chronicle text. Acceptance
tests A to W live here wherever they can (§10 of this plan).

**In `fabric`, because it needs the world:**

- persistence (Gson, files in the world folder) and config loading
- the tick driver that calls `SiegeSim.step` and the chunk claim index
- reading chests into `Provisions` and applying `EatPlan` and `SpoilagePlan` to
  real item stacks
- crossing detection for players and carts, hopper gating, ender chest and pearl
  exclusions, presence counting from player positions
- protection: Fabric API events and the Mixins in §5 of this plan
- blocks, display entities, particles, books, chat pages, sgui screens, sounds
- commands and admin tools, the siege telemetry log

The rule of thumb: `fabric` observes and applies; `core` decides. A fabric
handler never computes a rule; it builds an `Action` or a sim input and hands it
to `core`.

## 3. The action engine

Every consequential change is an `Action`: found, invite, admit, expel, open
vote, cast vote, appoint, grant, revoke, deposit, withdraw, place nexus, plant
banner, claim, unclaim, choose raze or occupy, arrest, sentence, pardon, release,
and every admin override. Siege transitions that nobody chooses (a camp finishing
its raising, a keep falling, a grace timer ending) are not actions; `SiegeSim`
emits them as events through the same recording path.

```java
interface Action {
    ActionCategory category();            // what governance rule applies
    Optional<Power> power();               // what grant is needed, if any
    Outcome check(ActionContext ctx);      // preconditions and costs, no mutation
    Effects apply(ActionContext ctx);      // mutation, returns history events
}
```

`ActionEngine.run(actor, action)` does, in order:

1. **Resolve** actor and target ids against `CharterState`. A missing id is a
   plain refusal ("That faction no longer exists"), never an exception.
2. **Authority.** `AuthorityResolver` walks from the actor (player, then their
   offices, then their organization) up each grant's grantor to its source: an
   organization's own reserved power or a passed vote. Every link is checked at
   this moment: not revoked, not expired, in scope (spending limit, target,
   region), and the grantor still holds what it passed on. Nothing is copied onto
   the recipient, so a revoke ends everything below it at once (test F). Only
   descendant sets of the tree are cached, invalidated on any tree change.
3. **Governance.** If the category needs a vote and none has passed, the engine
   opens one instead (or refuses, if the actor cannot propose). A passed vote is
   stored as a pending action and re-runs through steps 1 to 4 when it executes.
4. **Check** preconditions and costs (`check`). Costs are taken from the item
   ledger or the player's inventory only after every check passes.
5. **Apply** and collect `HistoryEvent`s.
6. **Record.** Events go to the in-memory history, the append-only history file
   (§8 of this plan) and the Chronicle renderer; the state is marked dirty for the
   next snapshot.

Failed actions return one plain sentence for the player, from a fixed list of
reason keys so the player-text pass can reword them in one place.

**Admin actions** run through the same engine with `Actor.Admin(playerId or
"console")`. They skip step 2 and 3 only, still pass `check` (an admin cannot put
a claim inside another claim by accident), and every resulting event carries
`admin: true`. The Chronicle renders admin events with an "by order of the
crown's clerk" voice only if the server opts in; by default they appear in the
history and the siege log but not in player-facing books, so test scenarios do
not pollute the story.

**Threading.** The engine is only called on the server thread (§18.3), so actions
are serialized. Anything that waits (a vote, a raising camp, a sentence) is
re-checked when it resumes.

**Hot file avoidance.** There is no central switch over action kinds. Each action
is its own class in its feature's package, so parallel milestones add actions
without touching a shared file.

## 4. Simulation and ticking

### 4.1 Two clocks and the time scale

- **Wall clock:** real milliseconds. Drives votes (48 hours), membership age (3
  days), the 14 day active window, the 7 day absent Lord rule, the found and
  secede cooldown, and real-time sentences (D9).
- **Siege clock:** a persisted counter of siege milliseconds. Each sim step adds
  `realElapsed * timeScale`. Drives camp raising, the granary and camp store
  drains, spoilage, holds and their refill, camp sally protection, carry-in
  windows, orphan grace, truce, new-faction protection, the banner move
  cooldown, and occupation.

`timeScale` (default 1) is one knob that compresses every siege, spoilage,
raising and grace timer together. At 24 a 3.5 day siege takes 3.5 hours. Ops can
also add siege time directly with `/charter admin time advance <duration>`, which
runs the sim forward in steps of at most one siege hour so nothing is skipped.

Governance clocks stay real by default; tests and playtests skip them with admin
tools (`/charter admin age <player> <days>`, `/charter admin vote close <id>`).
Whether sentences scale too is D13.

**The siege clock does not advance while the server is down.** A restart or a
crash never drains a granary; it only pauses the war. This is the simplest
behaviour that makes test H true and removes a class of "the server was offline
for six hours and my keep fell" complaints. It is D14 in case the owner wants
real-time sieges instead.

### 4.2 Tick rate and batching

| Work | Rate | Cost |
|---|---|---|
| Player chunk-change check (crossings, banishment, confinement) | every server tick, per online player | One comparison of the player's current chunk with the last one. Work only happens on a change, then one hash lookup |
| Cart scan | every 5 ticks, only around keeps and camps whose state is besieged or cut off | One entity query per active site over its 5x5 (keep) or perimeter box |
| Sim step | every 100 ticks (5 seconds real) | `SiegeSim.step(delta)` over active sites only |
| Item application (eat and spoil the real chest) | in the sim step, only for loaded containers | At most 54 slots per linked chest |
| Presence | in the sim step | One pass over online players, each mapped to a claim by hash lookup |
| Perimeter and encirclement state | on change only | Recomputed when a claim, camp phase or core changes, never per tick |
| Displays (time left on nexus and granary) | every 20 ticks for sites within 64 blocks of a player | Text update only when the value changes |

Drain is fractional. Each keep and camp holds a nourishment debt that the step
adds to; whole items are removed only when the debt covers the cheapest item in
the eat order. Spoilage keeps a fractional debt per item type the same way. This
makes the drain independent of the step size, which is what lets the time scale
and `time advance` produce the same result as real time (a test proves this).

**Active sites only.** A keep with no camp touching its ring is `SUPPLIED` and is
skipped by the drain (spoilage still runs, at a slow cadence: once a siege
minute). An outlying claim is visited only while raided or refilling. With 100
players and perhaps 30 keeps, the step touches a handful of sites.

### 4.3 What each step does

1. Advance the siege clock.
2. Read presence: attackers in each besieging camp's claims, defenders in each
   keep's chunks and in claims next to each camp, attackers inside starving keep
   chunks. AFK players (no movement for 5 minutes) do not count.
3. Camp phases: raising timers finish into `ACTIVE` and emit the alert.
4. Supply state: for each keep touched by an active camp with food in its store,
   `BESIEGED` or `ENCIRCLED`; for each camp, `OPEN` or `CUT_OFF`.
5. Drains: keep granary (mouths, presence, notoriety, the 0.5 to 2 bound, assault
   below the starvation line), camp stores (30 or 60 an hour), raided holds, camp
   sally protection, hold refill for connected unraided claims.
6. Spoilage for every granary and store, by type, fast past the storage limit.
7. Timers: allowances, orphan grace, truce, new-faction protection, occupation
   cap, sentences.
8. Transitions: a granary at zero falls the keep (the besieger gets the raze or
   occupy choice); a camp store at zero stops besieging; an empty hold unclaims
   the claim; grace expiry unclaims contested claims.
9. Emit events to history, the Chronicle and the siege log.

### 4.4 Restarts

All siege state is in `CharterState` and saved (§8 of this plan): the siege
clock, every site's state and debts, raising timers, allowances with their item
counts and expiry, grace timers, truces, sentences. On start, the first step
recomputes supply states from the land graph rather than trusting the saved
labels, so a restart can never leave a keep "encircled" by a camp that no longer
exists.

### 4.5 Chunks that are not loaded

Nothing is force-loaded. Each granary and camp store has a **mirror** in core: its
`Provisions` (item id to count) as last read from the real chest. While the chunk
is loaded the mirror is refreshed from the chest every step. While it is unloaded
the mirror is authoritative: the sim eats and spoils against it and queues the
removals. When the chunk loads, the queued removals are applied to the real
items before anyone can open the chest. Nothing can be added to an unloaded
chest (hoppers and players need the chunk loaded), so the mirror cannot drift.

Holds, timers and the land graph never need a chunk loaded. Banners, nexus blocks
and displays are only touched when their chunk is loaded; a keep that falls while
unloaded has its nexus broken and plunder spilled on the next load (the fall is
recorded at once, the physical consequences are queued).

## 5. Physical detection in `fabric`

### 5.1 The claim index

`ClaimIndex`: per dimension, a `Long2ObjectOpenHashMap` (fastutil ships with
Minecraft) from packed chunk coordinates to a small `ClaimRef` (claim id, owner
org, kind, core id). Rebuilt from `CharterState` on load and patched on every
claim change. Every protection check and crossing check is one lookup. Claims are
overworld only by default (`claimDimensions` knob), which keeps Charter out of
other mods' dimensions.

### 5.2 Player crossings and the carry-in snapshot

Every tick, per online player, compare `player.chunkPosition()` with the last
recorded chunk. On a change into a chunk of a keep that is `BESIEGED` (or a camp
chunk whose camp store can be fed):

1. The crossing counts only if the previous chunk is **edge-adjacent** to the new
   one and is an **open** perimeter chunk of that site. A jump of more than one
   chunk (teleport, a skipped sample) or a diagonal step does not count.
2. It does not count if the player is flagged **in transit by teleport**: set when
   they throw an ender pearl or eat a chorus fruit (`UseItemCallback`) and cleared
   when the pearl entity is gone or 3 seconds pass; also set on dimension change
   (`ServerEntityWorldChangeEvents`) and respawn (`ServerPlayerEvents.AFTER_RESPAWN`).
3. If it counts, the mod takes a **snapshot**: food in the main inventory, offhand,
   and inside shulker boxes and bundles carried, as item id to count. It replaces
   any earlier allowance the player holds for that site (re-entering does not
   stack), and expires after the carry-in window (10 siege minutes).
4. A player riding a cart or boat is checked the same way; the cart's own
   contents are handled in §5.3.

**Deposit rule.** While besieged, food goes into the granary only through the
granary screen (§6), and each item placed must be covered by an allowance of the
same item id: first the player's own, then the keep's cart pool. Matching by item
id, not by nourishment, closes the trade of carried steak for stockpiled bread.

**Ender chests.** An ender chest cannot be opened inside a besieged keep's chunks
or a cut off camp's chunks (`UseBlockCallback`, refusal message). Otherwise a
runner could walk in with one stack, deposit from the ender chest, walk out and
repeat. Taking food out of an ender chest *outside*, in an open perimeter chunk,
and walking it in is a legitimate run.

**Logging out.** Logging in inside a keep is not a crossing, so food carried by
someone who logged out inside earns nothing. Allowances persist across a restart
and a relog, since they were earned.

### 5.3 Carts

Every 5 ticks, for each besieged keep and each camp that can be fed, query
`AbstractMinecart` entities with a container (chest and hopper minecarts) in the
site's box. Track each cart's last chunk by UUID in a map that is pruned when the
site is no longer active. On a crossing into a keep chunk that passes the same
adjacency and openness test as a player:

- note the cart's food as item id to count and add it to the keep's **cart pool**,
  tagged with the cart's UUID and an expiry (the same 10 siege minutes)
- when that cart leaves the keep's chunks, subtract whatever food is still aboard
  from its pool entry, so a cart cannot roll in full, roll out full and roll in
  again to mint allowance
- a cart that crossed from an enemy chunk, or was loaded inside, adds nothing

The pool is spent by hopper inserts into the granary (§5.4) and by hand deposits
once the depositing player's own allowance is used up. Rolling a cart in carries
the same food a runner would, so letting anyone inside unload it by hand is
faithful to the design.

Carts move at most 8 blocks a second on vanilla rails (0.4 blocks a tick), so a
5 tick sample moves 2 blocks and cannot skip a 16 block chunk. If the 26.x
experimental faster minecarts are enabled the sample rate drops to every tick
for active sites (R2 in §13 of this plan).

Chest boats and donkeys with chests are not mentioned in the design (see §15 of
this plan); the recommendation is to treat chest boats exactly like carts and
ignore animal inventories in the first build.

### 5.4 Hopper inputs into a besieged granary

One Mixin on the static `HopperBlockEntity.addItem(Container source, Container
destination, ItemStack stack, Direction face)` (exact signature checked against
26.2 in M0). Droppers use the same method, so one injection covers both. If the
destination is a granary or camp store chest whose site is besieged or cut off,
the insert is cut to what the site's cart pool covers for that item id, and the
pool is debited. In peace nothing is changed. There is no Fabric API event for
hopper transfers.

### 5.5 Presence

In each sim step, one pass over online, non-spectator, non-AFK players: look up
their chunk, and if it belongs to a site that matters, count them as attacker or
defender by their organization's relation to the site's owner (members of the
owner or its subordinates defend; members of a besieging faction attack;
outsiders and sellswords count for whoever granted them the "garrison" or
"assault" power, D7 style). Counts feed `DrainModel`, which applies 25% per player
and the 0.5 to 2 bound. Cost is O(online players) per 5 seconds.

### 5.6 Protection

**Fabric API events (no Mixin):**

| Behaviour | Event |
|---|---|
| Breaking blocks | `PlayerBlockBreakEvents.BEFORE` |
| Using blocks: containers, doors, buttons, levers, the granary, vault, lectern, ender chests | `UseBlockCallback` |
| Using items aimed into a claim: buckets, flint and steel, fire charges, spawn eggs | `UseItemCallback` plus the hit result's target position |
| Attacking entities: carts, item frames, armor stands, animals in a protected farm | `AttackEntityCallback` |
| Using entities: opening chest carts, item frames, leashing, riding | `UseEntityCallback` |
| Starting to break (stops the crack animation and drops for creative-like abuse) | `AttackBlockCallback` |

Rule: a player may act in a claim if they hold a grant for it (members by
default everywhere except the core chunk); outsiders may not. Walking is never
blocked. A claim being raided keeps full protection until its hold is empty
(§9.5); a camp is unprotected while raising.

**Mixins, each because no event exists:**

| # | Target | Why there is no event | What it does |
|---|---|---|---|
| 1 | `BlockItem.place` (wrap, like `ballot`) | Fabric API has no server-side block place event; `UseBlockCallback` would mean guessing the final position from faces, sneaking and replaceable blocks, which is exactly why `ballot` and `pocketdungeons` both have this Mixin | Refuses placement in a foreign claim; detects placement of the nexus, war banner and cell marker items so they become cores and fixtures |
| 2 | `ServerExplosion.calculateExplodedPositions` (return) | Explosions do not fire break events | Removes positions in claimed chunks that the explosion's source may not break (pattern from `pocketdungeons`' `ServerExplosionMixin`). Entity damage is untouched |
| 3 | `PistonBaseBlock.moveBlocks` (head, cancellable) or the `PistonStructureResolver` result | Piston pushes and pulls fire no event | Refuses a move if any moved or destroyed position, or any destination, lies in a claim owned by someone other than the owner of the piston's chunk |
| 4 | `FireBlock` spread and burn-out | Fire ticking has no event | Fire cannot spread into, or burn blocks in, a claim owned by someone other than the owner of the fire's chunk |
| 5 | `LavaFluid.randomTick` ignition | Same | Lava cannot start fires in a foreign claim |
| 6 | `FlowingFluid.canSpreadTo` | Fluid flow has no event | Water and lava do not flow across a border into a claim with a different owner |
| 7 | `DispenserBlock.dispenseFrom` | Dispensers fire no event; a dispenser outside a border can place lava or fire into a claim | Refuses to dispense into a claim the dispenser's chunk owner does not own |
| 8 | `HopperBlockEntity.addItem` (static) | Container transfers fire no event | §5.4: besieged granary gating |
| 9 | `VehicleEntity.hurtServer` (minecarts and boats) | `AttackEntityCallback` covers melee only; arrows, tridents and fire charges break carts through `hurt` | Projectiles from an outsider cannot break carts in a claim (test K4) |
| 10 | `FarmBlock.fallOn` (optional, D19) | Trampling fires no event | Outsiders cannot trample farmland in a protected claim. Recommended: raided farms are the prize only once their hold is empty |

Ten small Mixins, listed with these reasons in the README as the suite asks. The
first spike in M0 confirms each target exists by that name and signature in 26.2.

Not protected, on purpose: mob griefing (endermen, ravagers) follows the vanilla
`mobGriefing` rule; withers breaking blocks by contact are a server rule problem,
not a claim problem. Both are noted in §13 of this plan.

## 6. The granary and camp store

### 6.1 Real containers

- **The granary** is one or more vanilla chests in the keep's core chunk. The
  first chest a member with the granary power places after the nexus becomes the
  granary; chests placed touching it extend it, up to `granaryMaxChests` (default
  4 double chests, 216 slots). See §15 of this plan for why one chest is not
  enough: a full preserved granary for 20 mouths is 3,360 bread, 53 stacks.
- **The camp store** is a barrel placed by the mod next to the banner when the
  camp is planted (a barrel cannot become a double chest, so the store stays
  small). It breaks with the banner while raising; after that it is protected
  like the camp.
- Each container is registered by position in `CharterState`, with a text display
  above it.

**Opening.** `UseBlockCallback` on a granary or store cancels the vanilla screen
and opens an sgui screen bound to the real container (the `ballot` dependency
already does this; vanilla clients see an ordinary chest window). The screen:

- shows the real slots, so what you see is what is stored
- accepts only food items (anything in `food.json`)
- in peace, accepts any food from anyone with the deposit power
- while besieged, accepts each item only against an allowance (§5.2)
- allows withdrawal only with the withdraw power; outsiders cannot open it at all
  while the keep stands
- after the keep falls, opens to whoever holds the core chunk (it is loot)

Hoppers and droppers feed the real chest directly, gated by Mixin 8.

### 6.2 Eating, least nourishing first

`EatPlan.plan(provisions, debt, foodTable)` sorts the contents by nourishment per
item ascending, ties broken by faster spoilage first, then item id (so the result
is deterministic). It removes whole items while the debt covers them and carries
the remainder. Bread added in an emergency (5 each) is eaten before steak (8).

### 6.3 Spoilage

Every food has a `ShelfClass` and a daily loss rate:

| Class | Default loss per siege day | Examples |
|---|---|---|
| `PRESERVED` | 2% | bread, dried kelp, baked potato, cookie, pumpkin pie, honey bottle |
| `COOKED` | 10% | cooked meats and fish, rabbit stew, mushroom stew, golden carrot |
| `FRESH` | 30% | raw meat and fish, carrots, potatoes, beetroot, apples, melon slices, sweet berries |

`SpoilagePlan` computes, per item type, the fractional loss over the step and
removes whole items as the per-type debt allows. Past the **storage limit**
(84 hours of the keep's drain, in nourishment) the excess spoils at 50% a day on
top. The excess is taken from the most perishable items first, which is the most
forgiving reading and easy to explain ("the fresh food rots first").

Special foods with effects (golden apples, suspicious stew, chorus fruit, rotten
flesh, spider eyes, pufferfish, poisonous potatoes) are **not provisions**: the
screen refuses them. Golden apples are too valuable to be a siege food and the
rest are not food a garrison would eat.

### 6.4 Mouths, storage and drain

- Mouths per keep: `max(minMouths, activeMembers(owner) / keepsOwned)` plus one
  per prisoner held in that keep's cells (test V).
- Drain: `mouths * drainPerMouth` nourishment an hour, times the presence and
  notoriety multiplier, bounded 0.5 to 2, times the assault factor below the
  starvation line (§15 of this plan asks how assault and the bound combine).
- Storage limit: `mouths * drainPerMouth * storageHours`, recomputed whenever
  active membership changes. Prisoners do not raise it (recommendation; §15).
- Camp store: `campBurn` an hour while besieging or raiding, `campBurnCutOff`
  when cut off. It does not eat while raising (recommendation; §15).

### 6.5 Food values from a data file

`data/charter/food.json` in the jar, copied to `config/charter/food.json` on
first boot so servers can edit it:

```json
{
  "minecraft:bread":        { "nourishment": 5, "shelf": "PRESERVED" },
  "minecraft:dried_kelp":   { "nourishment": 1, "shelf": "PRESERVED" },
  "minecraft:baked_potato": { "nourishment": 5, "shelf": "PRESERVED" },
  "minecraft:cooked_beef":  { "nourishment": 8, "shelf": "COOKED" },
  "minecraft:beef":         { "nourishment": 3, "shelf": "FRESH" },
  "minecraft:carrot":       { "nourishment": 3, "shelf": "FRESH" }
}
```

Nourishment defaults to the vanilla food component's nutrition, so the file only
needs the shelf class; M3 ships a complete table for every vanilla food and a
test that every item in the table exists in the 26.2 registry (run as a
gametest, since only `fabric` can see the registry). A variety bonus is out of
scope (D2).

## 7. In-world objects and player text

Everything is a vanilla block or a display entity, so vanilla clients see it with
no resource pack. Custom items follow the suite pattern: a vanilla item plus a
component patch (`custom_data` with a `charter` tag, a custom name and lore),
published in `SUITE_ITEMS.md` style so other mods can sell them.

### 7.1 Objects

| Object | Made from | Behaviour |
|---|---|---|
| **Nexus** | A tagged **lodestone** item. Placing it (Mixin 1) runs the place-keep action; on refusal the block is not placed and the item stays | A text display above it: faction name, "Provisions: 3 days, 6 hours", supply state. A slow ring of end rod particles while besieged. Unbreakable while the keep stands; breaks on raze |
| **Granary** | Vanilla chests in the core chunk (§6) | Text display: "Granary", provisions in time left, a warning line past the storage limit ("Overstocked: food is spoiling") |
| **Vault** | A **barrel** marked by the first vault placement in the core chunk | sgui screen with two slots: put cobblestone or diamonds in to deposit, click a row to withdraw with the spend power. Displays are public, so its text display says only "Vault"; the balance is on the faction page |
| **Charter lectern** | A vanilla **lectern** in the core chunk holding a generated **written book** | The book is regenerated on change: faction page, open votes (with page text telling players to use `/charter vote`), and the faction's Chronicle. Vanilla clients read books natively |
| **War banner** | A tagged banner item carrying the People's pattern | Placing it plants a camp (Mixin 1). Raising: the banner, the store barrel and a **signal campfire** (campfire on a hay bale) placed under or beside it, all breakable by anyone. Picking up (break by a member with the power, after raising) packs the camp and returns the item with the cooldown recorded |
| **Camp store** | A barrel next to the banner (§6) | Text display with time left |
| **Cell marker** | A tagged **chain** item placed inside a keep | Marks a cell: the enclosed air space around it, flood filled up to 64 blocks, recomputed when placed. A cell that is not enclosed is refused with a reason |
| **Realm Chronicle** | A lectern at spawn placed with `/charter admin realm-chronicle here` | Holds the server's Chronicle as a written book, newest first, last 50 pages |
| **Contract board** | Not in the first build | Planned as a wall of text displays in the `pocketdungeons` wall board style |

### 7.2 Signals

- **Smoke column.** While a camp raises: the real signal campfire (vanilla smoke,
  about 24 blocks tall, seen by anyone within their particle range), plus
  `CAMPFIRE_SIGNAL_SMOKE` particles sent by the server with the long-distance
  flag every second, a column 64 blocks tall, to players within 256 blocks.
  Vanilla clients cull particles by their own settings; R5 in §13 of this plan
  checks how far it reads in practice.
- **Border particles.** While a player holds a war banner or a compass, dust
  particles along the chunk edges within 2 chunks, sent only to that player,
  every 10 ticks, coloured: own land green, foreign land red, open perimeter
  chunks of a besieged keep yellow.
- **Alerts.** When a camp becomes active against your land: a chat line with a
  clickable "Show me" (runs `/charter map` centred on the camp), a title, and a
  quiet `Chime` (volume 0.3). Also on: keep encircled, keep starving, camp cut
  off, keep fell, your claim emptied, you were sentenced.
- **Offline messages.** Alerts for a faction are stored per member (last 20) and
  shown as a short digest on join: "While you were away: the Red Company raised a
  camp east of Ashfall (2 hours ago). Provisions: 2 days, 9 hours."

### 7.3 Commands and chat pages

Visible commands, kept to the four in §19 plus two that need a name typed:

| Command | Does |
|---|---|
| `/charter` | Your faction page: members, offices, keeps with time left, camps, open votes, recent Chronicle lines. Every action you may take is a clickable link |
| `/charter found <name> <commune or lordship>` | Founds an organization |
| `/charter map` | An 11 by 11 chunk map in chat; hover shows owner, kind, hold or provisions |
| `/charter vote` | Open votes you may cast, with clickable choices |
| `/charter invite <player>` | Needs a name, so it is a command |
| `/charter grant <player or office> <power>` | Needs names; the page links fill it in with `suggest_command` |

Clickable links run a hidden `/charter act <token>` with a one-shot token bound to
the player and the action, in the `ballot` style, so a typed token from someone
else does nothing.

Admin commands live under `/charter admin` (permission level 2): `time scale`,
`time advance`, `age`, `vote close`, `faction spawn`, `camp raise-now`,
`granary set`, `supply show`, `perimeter show`, `scenario run`, `realm-chronicle
here`, `repair`. Details in §12 of this plan.

### 7.4 Justice in the world

- **Arrest** is physical: an officer with the arrest power uses a **lead** on a
  player standing in the officer's faction's land. The target is in custody and
  is led (vanilla leash visuals are not available on players, so custody is a
  tether: the prisoner is pulled toward the officer if more than 6 blocks away).
  Custody lapses after 5 minutes if no sentence is given.
- **Sentencing** opens a chat page for the officer listing the terms the custody
  basis allows with their caps already applied (confinement duration, fine,
  execution). Clicking imposes the contract through the engine.
- **Confinement:** the prisoner is moved into the chosen cell. Each tick check
  (already running for crossings): if the prisoner is outside the cell and still
  in the keep's chunks, they **escaped** (resistance, no breach, Chronicle line);
  if they are far away by a jump (a teleport or command from another mod), they
  are returned to the cell. Pearls and chorus fruit are refused while confined.
- **Execution:** the prisoner is killed by the mod (`/kill` style damage source
  "executed"), respawns into a wait: spectator mode is not used (it scouts);
  instead adventure mode at their respawn point with slowness and blindness and
  a boss bar countdown, at most 1 hour. Then banishment applies.
- **Banishment:** on entering a chunk of the banishing faction's land, the player
  is pushed back to their previous chunk position with a message.
- **Fine:** taken from the prisoner's own vault deposit, capped (D10); a player
  with no deposit pays nothing (recommendation, D10).

### 7.5 Player text

All player-facing strings live in `data/charter/lang/en_us.json` (server-side
text, not a resource pack), and Chronicle templates in
`data/charter/chronicle/templates.json`, both copied to `config/charter/` on first
boot so a server can rewrite the voice. The `player-text` skill's rules (short,
plain, no dashes) apply; a source-scanning test fails the build on an em dash or
a spaced double hyphen in either file.

## 8. Persistence

### 8.1 What is saved, where

All under the world folder, `<world>/charter/`:

| File | Contents | Written |
|---|---|---|
| `state.json` | `CharterState`: organizations, tree, memberships with join times, People, governance, offices, grants, votes (with fixed electorates), treasuries, cores, claims, holds, granary and store registrations and mirrors, siege states and debts, raising timers, allowances and cart pools, grace timers, truces, protections, contracts, punishment ledger, notoriety, cells, pending offline messages, both clocks | Atomically (write `state.json.tmp`, fsync, move) on every world save, on server stop, and every 5 minutes if dirty |
| `history.jsonl` | One `HistoryEvent` per line, append only, flushed per event | On every event |
| `siege-log/<yyyy-mm-dd>.jsonl` | The debrief log (§8.3) | On every siege event, flushed |

This is world saved data in the sense that it lives with the world and moves with
it, but it does not use Minecraft's `SavedData` class. Reason: the model is a core
type with no Minecraft dependency, and Gson in `fabric` can write it with a plain
Java round trip test that runs without a server, as `ballot`'s `PollStore` does.
Pushing the whole model through Minecraft codecs would duplicate the schema in a
second form for no gain. If the owner prefers `SavedData`, only `CharterStore`
changes.

A `formatVersion` field and a migration step per version are there from day one.

### 8.2 Restart behaviour

- The siege clock resumes where it stopped (§4.1 of this plan), so a restart
  neither lifts a siege nor drains a granary.
- Allowances, cart pools (by cart UUID), raising timers and grace timers are in
  siege time and simply continue.
- Votes and sentences are in wall time: a vote whose timer ran out while the
  server was down closes on the first tick; a sentence that ran out releases the
  prisoner on join.
- Supply states are recomputed on the first step, never trusted from the file.
- Granary mirrors are reconciled with the real chest when its chunk loads (§4.5
  of this plan).

### 8.3 The siege log

`siege-log` is the debrief view; the Chronicle is the player view. Same format
idea as `pocketdungeons`' playtest journal: one JSON object per line, UTF-8,
common fields `t` (UTC), `siegeT` (siege clock), `ev`, `site`, `owner`,
`attacker`, plus event fields. Events:

`camp_planted`, `camp_raising_broken`, `camp_active`, `camp_packed`,
`camp_store_deposit`, `camp_cut_off`, `camp_reopened`, `camp_empty`,
`camp_destroyed`, `keep_besieged`, `keep_encircled`, `keep_ring_broken`,
`keep_lifted`, `keep_starving`, `assault_presence`, `keep_fell`, `fall_choice`,
`plunder`, `crossing` (player or cart, counted or refused with the reason:
`not_adjacent`, `from_enemy`, `teleport`, `not_open`), `allowance_granted`,
`deposit` (who, item, count, which allowance paid), `deposit_refused`,
`hopper_gated`, `presence` (counts and multiplier, once a siege minute while they
change), `drain_hour` (hourly snapshot: provisions, debt, multiplier, mouths),
`spoiled` (hourly, per class), `raid_started`, `hold_empty`, `claim_reassigned`,
`claim_orphaned`, `grace_saved`, `grace_expired`, `truce_started`, `arrest`,
`sentence`, `escape`, `release`, `execution`, `banish_push`, `admin` (every
admin command with its arguments).

A reader tool in M14 (`charter/tools/siege_report.py`) turns a day's log into a
timeline per siege: when it started, how provisions fell, every crossing and
deposit, and how it ended. Keep 30 days of files; delete older ones on start.

### 8.4 Repair on load

Repair rather than crash, and log every repair as an `admin` history event with
`actor: "repair"`:

- drop grants whose grantor or source is gone
- re-parent organizations whose parent is missing to top level
- orphan claims whose core is missing (they enter grace)
- drop allowances and cart pools for sites that no longer exist
- unregister granaries, stores and cells whose block is gone (checked when the
  chunk loads)
- close votes whose organization is gone
- release prisoners whose cell or keep is gone

`state.json` that fails to parse is copied to `state.json.broken-<time>` and the
server starts with the last good backup (`state.json.bak`, the previous snapshot,
kept on every write). It never silently starts empty.

### 8.5 SQLite

Not needed. History at 100 players is a few thousand lines a day; the Chronicle
pages are rendered from the tail, and the faction books from a per-faction index
built on load. Revisit only if `history.jsonl` passes about 100 MB or a feature
needs queries across months (a war score ledger spanning seasons, for example).

## 9. Config

`config/charter/charter.json`, written with defaults on first boot, logged, never
overwritten after a parse failure (the server then runs with defaults and says so
in the log). Durations are in the unit named by the key. Siege-time durations are
marked (S); they are all scaled by `timeScale`.

```json
{
  "timeScale": 1.0,
  "claimDimensions": ["minecraft:overworld"],

  "keepPriceDiamonds": 64,
  "warBannerPriceCobblestone": 256,
  "keepCapacityClaims": 40,
  "keepRangeHops": 8,
  "campCapacityClaims": 4,
  "campRangeHops": 2,
  "claimsPerActiveMember": 6,
  "keepGapChunks": 4,

  "drainPerMouthPerHour": 10,
  "minMouthsPerKeep": 3,
  "storageLimitHours": 84,
  "spoilagePerDayPreserved": 0.02,
  "spoilagePerDayCooked": 0.10,
  "spoilagePerDayFresh": 0.30,
  "spoilagePerDayPastLimit": 0.50,
  "granaryMaxChests": 8,

  "campBurnPerHour": 30,
  "campBurnCutOffPerHour": 60,
  "outlyingHoldHours": 6,
  "outlyingRefillHours": 24,
  "campSallyProtectionHours": 1,

  "presencePerPlayer": 0.25,
  "multiplierMin": 0.5,
  "multiplierMax": 2.0,
  "notorietyMaxMultiplier": 1.5,
  "presenceAfkMinutes": 5,
  "starvationThreshold": 0.25,
  "assaultMultiplier": 4.0,

  "campRaisingHours": 2,
  "carryInWindowMinutes": 10,
  "truceDays": 3,
  "newFactionProtectionDays": 3,
  "bannerMoveCooldownMinutes": 30,
  "orphanGraceMinutes": 30,
  "plunderShare": 0.25,
  "occupationCapDays": 7,

  "settleDays": 3,
  "absentLordDays": 7,
  "voteEligibilityDays": 3,
  "voteDurationHours": 48,
  "activeWindowDays": 14,
  "foundSecedeCooldownDays": 7,
  "dormancyDays": 30,

  "confinementMinMinutes": 60,
  "confinementMaxHours": 24,
  "executionRespawnDelayMaxMinutes": 60,
  "banishmentMaxHours": 24,
  "weeklyPunishmentCapHours": 24,
  "sameFactionSamePlayerCooldownDays": 3,
  "custodyFineCapDiamonds": 16,
  "custodyLapseMinutes": 5,
  "imposedVassalageCapDays": 14,
  "imposedNotorietyPerSeverity": 1.0,
  "scaleSentencesWithTimeScale": false,

  "smokeColumnRangeBlocks": 256,
  "offlineMessagesKept": 20,
  "siegeLog": true,
  "siegeLogRetentionDays": 30,
  "simStepTicks": 100,
  "cartScanTicks": 5
}
```

Siege-time keys (S): every key from `drainPerMouthPerHour` through
`occupationCapDays`, the spoilage rates, and the custody lapse. Wall-time keys:
the governance block. Sentences are wall time unless
`scaleSentencesWithTimeScale` is true (D13). Knobs for features out of the first
build (settling, dormancy, imposed vassalage) are present so the file does not
change shape later; they do nothing yet and the README says so.

`granaryMaxChests` counts single chests, so the default 8 is four double chests.
`food.json` (§6.5 of this plan) and the template and text files are separate
files in the same folder.

## 10. Testing

### 10.1 Test types

- **Core tests:** plain `main()` classes in `core/src/test`, run by one
  `CoreSuite` through the `coreTest` Gradle task (`ballot` style; Gradle's `test`
  task is disabled because there are no JUnit tests). They drive `CharterState`,
  `ActionEngine` and `SiegeSim` with a fake clock. Fast, no server.
- **Fabric plain tests:** `main()` classes in `fabric/src/test` with Gson on the
  classpath: store round trip, repair, config read-or-create, text files free of
  dashes. Task `fabricTest`.
- **Gametests:** Fabric gametests in `fabric/src/gametest`, run by `runGameTest`.
  For anything that needs a world: protection, placement, carts, hoppers, chunk
  crossings, real chest eating. **Every gametest class must be listed in
  `src/gametest/resources/fabric.mod.json`**, or it silently never runs; the
  milestone check is that the reported test count rose.

`check` depends on all three. A milestone is not done until the count of tests in
each suite is reported and has risen by the expected number.

**Behaviour, not compilation.** Every test sets up a scenario, runs the real code
path (an action through the engine, a step of the sim, a cart on real rails) and
asserts an observable result: a number of hours, an item count, a refusal reason,
a block still standing. A test that only constructs objects does not count.

### 10.2 Acceptance tests mapped

| Test | Type | How it is proved |
|---|---|---|
| **A Commune** | Core | Four players found a commune; a spend vote passes and the ledger moves; an officer vote passes and the officer can act; each member attempting an unvoted spend is refused with a reason |
| **B Lordship** | Core | A Lord with a spending limit spends under it and is refused over it; the Lord cannot amend the charter; members vote the Lord out and the Lord's next action is refused; an absent Lord loses the office after 7 days and a succession vote opens |
| **C Delegated command** | Core | A guard sub-org granted "raise camps" raises one; its withdraw over the limit and its attempt to appoint the monarch are refused |
| **D Sellswords** | Core, **deferred** | Needs signed contracts. In the first build, a core test proves the D7 half: an outsider with a deposit grant can deposit into a besieged granary against their allowance, and loses it the moment the grant is revoked |
| **E Identity fork** | Core, **deferred** | Needs secession |
| **F Contested authority** | Core | Revoking the guard's root grant refuses every derived action at once; the guard's fort stays its own core; the guard can then plant a camp against the kingdom's keep and the keep becomes besieged |
| **G Grant limits** | Core | Granting a power over another kingdom's treasury is refused at the grant action |
| **H Persistence** | Fabric plain, plus one gametest | Plain: build a state with every kind of record (structure, People, balances, contracts, grants, claims, granary mirrors, a running siege with debts, allowances, cart pools, grace timers), write, read, compare field by field; a corrupted file falls back to `.bak`; repair cases each drop the right record. Gametest: save and reload `CharterState` through the real `CharterStore` in a running server |
| **I Raising** | Core plus gametest | Core: raising lasts 2 siege hours, the alert event fires only on `ACTIVE`. Gametest: an outsider breaks a raising banner; after `ACTIVE` the same break is refused |
| **J Endurance** | Core | A granary at its storage limit with preserved food, no deposits, nobody present, lasts 84 hours (within the tolerance agreed for spoilage, §15) for 3 mouths and for 30; with the maximum attackers present and nobody inside, never less than 42 hours. Run at time scale 1 and 24 and with `time advance` to prove they agree |
| **K Carry-in** | Core plus gametest | Core: allowance maths, item-id matching, expiry, replacement on re-entry. Gametest: a mock player walked from an open perimeter chunk into the keep can deposit; food from a chest inside, an ender chest (refused to open), a pearl landing, a teleport, and a walk in from a camp chunk cannot |
| **L Encirclement** | Core plus gametest | Core: with all twelve perimeter chunks claimed by besiegers the state is `ENCIRCLED` and no allowance can be granted; removing one camp reopens exactly its sides. Gametest: a deposit attempt during encirclement is refused |
| **M Starvation gate** | Core | Attackers inside a keep chunk change nothing above 25%; below it the drain is four times base |
| **N Camp supply** | Core | An empty store stops a camp counting as besieging; a camp whose every perimeter chunk is hostile is `CUT_OFF`, refuses allowances and burns at 60 an hour |
| **K2 Carts** | Gametest | A chest minecart on real rails rolls in from an open perimeter chunk and a hopper unloads it into the granary; a cart that rolls in across a camp chunk, or is loaded inside, is gated by the hopper Mixin; in peace both fill it. A cart rolling in and out full mints nothing |
| **K3 Granary** | Core plus gametest | Core: eat order, spoilage per class, fast spoilage past the limit, storage and drain following active members per keep. Gametest: the real chest loses the right items over a sim step; after the fall an outsider can open it |
| **K4 Rail protection** | Gametest | An outsider cannot break rails or carts in a claim (melee and arrow); on neutral land both work |
| **O Raid** | Core | A camp beside a farm empties its 6 hour hold in 6 siege hours; the hold refills only while connected and unraided; once empty the claim is unclaimed |
| **P Keep loss** | Core | Two linked keeps; one falls; connected claims move to the other up to capacity, farthest drop first; the rest go contested for 30 minutes; a camp planted to link them saves them |
| **Q New faction** | Core | A faction under 3 days old cannot be besieged or raided; the protection ends when it plants a camp against anyone |
| **R Truce** | Core | A lifted siege bars the same attacker from planting against that keep for 3 days; another faction is not barred |
| **S Imposed caps** | Core | A 3 day confinement is cut to 1; a fine over 16 is cut; a term the custody basis does not allow (cede) is refused |
| **T Resistance** | Core plus gametest, **half deferred** | Core and gametest: escaping a cell and starving out an occupation leave no breach mark. The signed treaty half waits for treaties; a core test on the contract model proves a signed contract broken by one party is marked `BREACHED` |
| **U Weekly cap** | Core | A player jailed 16 hours by one faction and sentenced to 16 by another serves 8; a third sentence that week is refused |
| **V Prisoners eat** | Core plus gametest | Each prisoner adds a mouth; prisoners are released when the keep falls |
| **W Occupation** | Core | An occupied keep stays recorded to its owner, the occupier holds access and the granary, control returns when the cap runs out or when the defenders starve the occupiers out |

Plus tests the design implies but does not list:

- the time scale and `time advance` agree with real time (part of J)
- unloaded chunk mirror: a granary whose chunk is unloaded for a siege day loses
  the same items as one that stayed loaded. Gametests cannot unload their own
  structure, so this is a core test of the mirror plus a gametest that applies a
  queued removal list to a real chest as the load hook would
- each protection Mixin: explosion, piston, fire, lava, water, dispenser,
  trampling (one gametest each)
- presence: AFK players do not count; the multiplier never leaves 0.5 to 2
- text files contain no em dash and no spaced double hyphen (fabric plain)

### 10.3 Which tests come first

1. **J, M, N, O** in core, in M5: the siege maths is the core of the playtest and
   the cheapest place to find a wrong number.
2. **L and K core halves**, also M5: the perimeter and allowance rules decide
   whether carry-in makes sense.
3. **The M0 spike gametests**: a cart crossing detected on real rails, the hopper
   Mixin target present, a mock player chunk crossing. These retire the biggest
   technical risks before anything is built on them.
4. **A, B, C, F, G** in M1: the engine everything else uses.
5. **K, K2, K3** gametests in M9 and M10, then the rest in milestone order.

## 11. Milestones

Every milestone ends in something runnable and checkable, with its tests passing
and the test counts reported. "Parallel" means separate agents in separate
worktrees can build them at once without touching the same files.

**Hot files** (one owner at a time, merged by the integrator): `CharterMod.java`
(each milestone adds one `register` line), `charter.mixins.json`, the gametest
`fabric.mod.json`, `CoreSuite.java` (one line per test class), `en_us.json` and
`templates.json` (each milestone adds keys in its own block). All config knobs are
written in M0 so `Knobs` and `charter.json` are not hot after it.

| # | Milestone | Ends in | Tests | Order |
|---|---|---|---|---|
| **M0** | **Scaffold and spikes.** `charter/` with `core` and `fabric` copied from `ballot`; gametest source set from `pocketdungeons`; `Knobs` with every knob; config read-or-create; empty `CharterMod`; `dist` task. Three throwaway spike gametests: a chest minecart crossing a chunk border on real rails is seen by a 5 tick scan; a mock player chunk change is seen; every Mixin target in §5.6 resolves in 26.2 (a Mixin config with all ten injecting a no-op) | `gradlew build` puts `MrPinoys_Charter-0.1.0.jar` in `dist/`; `runServer` boots and writes `config/charter/charter.json`; `runGameTest` reports 3 tests | Config read-or-create (fabric plain), 3 spikes | **Serial**, first |
| **M1** | **Core: governance and the engine.** ids, clocks, org tree, membership, Commune and Lordship, offices, votes, grants, `AuthorityResolver`, `ActionEngine`, treasury ledger, history events | `coreTest` runs | A, B, C, F (authority half), G, vote edge cases (fixed electorate, ties fail, quorum of active members, eligibility) | **Serial**, after M0 |
| **M2** | **Core: land graph.** cores, claims, adjacency, hop assignment, capacity, range, member cap, keep gap, perimeters, open and closed sides, reassignment, orphan grace | `coreTest` | P, L (graph half), placement refusals | **Parallel** with M3 and M4, after M1 |
| **M3** | **Core: food.** `FoodTable`, `Provisions`, `EatPlan`, `SpoilagePlan`, mouths and storage | `coreTest` | K3 (core half), spoilage per class, eat order ties | **Parallel** with M2 and M4 |
| **M4** | **Core: Chronicle.** `HistoryEvent` kinds for every M1 to M6 event, templates, calendar, renderer | `coreTest` prints a sample Chronicle | Golden text tests per template; no dashes in templates | **Parallel** with M2 and M3 |
| **M5** | **Core: the siege.** `SiegeSim`: raising, war footing, drain model with presence and notoriety, starvation and assault, camp stores and cut off, raids and holds, the fall with raze and occupy, plunder, truce, new-faction protection, allowances and cart pools, time scale and advance | `coreTest` runs a scripted full siege and prints its timeline | I (core), J, K (core), L, M, N, O, Q, R, time scale agreement | **Serial**, after M2 and M3 |
| **M6** | **Core: contracts and justice rules.** `Contract`, `Term`, `Basis`, caps, lifecycle, `PunishmentLedger`, notoriety, custody, occupation as a contract, prisoner mouths | `coreTest` | S, T (core), U, V (core), W, D7 half of D | **Parallel** with M5 (needs only M3's `Mouths`) |
| **M7** | **Fabric foundation.** `CharterStore` (state, history, repair, backups), the sim tick driver, `ClaimIndex`, `/charter`, `found`, `invite`, `grant`, `vote`, chat pages and tokens, the vault, offline messages, siege log writer, admin `time`, `age`, `vote close`, `repair` | On `runServer`: found a commune, invite, vote a spend, restart, everything is still there | H (plain and gametest), store repair cases, a gametest that runs a vote end to end through commands | **Serial**, after M1 and M5 (it drives the sim) |
| **M8** | **Fabric: land and protection.** nexus placement, banner placement and raising (signal campfire, smoke column), claim and unclaim by clickable map, border particles, all protection events and Mixins 1 to 7, 9, 10, admin `faction spawn`, `camp raise-now`, `perimeter show` | On `runServer`: place a keep, claim farms, an outsider cannot grief them, a camp raises and becomes protected | I (gametest), K4, one gametest per Mixin, placement refusals | **Parallel** with M9 after M7 |
| **M9** | **Fabric: granary and camp store.** linked chests, the sgui screen, real eating and spoilage, mirrors and load reconciliation, displays with time left, `food.json`, admin `granary set` | A stocked keep under an admin-forced siege visibly eats its chest, least nourishing first | K3 (gametest), food table registry check, mirror load test | **Parallel** with M8 |
| **M10** | **Fabric: supply lines.** player crossings and snapshots, cart scan and pools, hopper Mixin 8, ender chest and pearl exclusions, deposit rule in the screen, `supply show` | A runner walks food in through an open side and deposits it; a cart line feeds a besieged granary; a tunnel under a camp does not | K, K2, L (gametest), cart in and out mints nothing | **Serial**, after M8 and M9 |
| **M11** | **Fabric: the siege in the world.** presence counting, alerts and offline digests, the fall (raze or occupy choice for the besieger, nexus break, plunder spill, granary as loot), reassignment and grace in the world, occupation control, truce | A complete scripted siege runs on a dev server from raise to fall, both endings | V (release half), W gametest, a full siege gametest at time scale 600 | **Serial**, after M10 |
| **M12** | **Fabric: justice.** cells, arrest with a lead, sentencing page, confinement and escape, execution with respawn wait, banishment, fine, pardon | On `runServer`: arrest, jail, escape, and an execution with banishment | T (gametest), U and V in the world, cell flood fill refusals | **Parallel** with M11 (touches only justice files plus hot-file lines) |
| **M13** | **Fabric: Chronicle in the world.** charter lectern books, Realm Chronicle lectern, refresh on change | A lectern shows a readable Chronicle of a siege | Book page limits, regeneration on event | **Parallel** with M10 to M12 after M7 |
| **M14** | **Playtest kit.** scenario files and `scenario run`, `siege_report.py`, a load check (100 fake claims, 10 active sieges, 40 simulated players in core; tick time measured in a dev server), README with the Mixin list, deploy steps, final `check` | `dist/` jar deployed to the test server and the five scenarios each run once by the builder | All suites green with counts reported; load check numbers recorded | **Serial**, last |

**Count:** 15 milestones, M0 to M14.

**Critical path:** M0, M1, M2 or M3, M5, M7, M8 or M9, M10, M11, M14. M4, M6, M12
and M13 run beside it. With up to three agents at once the parallel groups are
{M2, M3, M4}, {M5, M6}, {M8, M9, M13} and {M11, M12}.

If the owner wants an earlier look, the natural first internal playtest is after
M11: the siege loop complete, without justice or lecterns.

## 12. Playtest plan

### 12.1 Getting the build onto a server

1. `charter/gradlew.bat check dist` puts `MrPinoys_Charter-<version>.jar` in the
   suite's `dist/` folder. Check the jar is newer than the last source edit
   before uploading (the `deploy-test-server` skill's stale jar rule).
2. Target: the Kinetic test server **`714a0605`**. **Never `365f9682`.** Check
   `pocketdungeons/tools/server/kinetic.env` names `714a0605` before any upload,
   start or stop.
3. Upload: `pdserver.mjs deploy` only matches the Pocket Dungeons jar name, so
   either the jar is uploaded by hand through the Kinetic panel, or the tool
   gains a `--jar <pattern>` option. That is a change to a file in another mod's
   folder and needs the owner's say (D15).
4. The test server already runs Pocket Dungeons. Charter claims only the
   overworld by default and the dungeons live in their own dimension, so they
   should coexist, but the first boot is checked for both mods loading and for
   Pocket Dungeons' lobby area not being claimable (an op places a protected
   admin claim over spawn and the lobby with `faction spawn` as a "Crown" org).
5. Restart with the deploy, check the log for `Charter loaded`, config written,
   no Mixin failures, then run `/charter admin supply show` as a smoke check.

### 12.2 Session settings

- `timeScale` 24: a full 84 hour granary lasts 3.5 hours; a 2 hour raising takes
  5 minutes; a 6 hour farm hold takes 15 minutes; the 10 minute carry-in window
  becomes 25 seconds of real time, which is too short, so the session also sets
  `carryInWindowMinutes` to 240 (10 real minutes at scale 24). D13 asks whether
  windows should be exempt from the scale instead.
- `newFactionProtectionDays` waived per faction with an admin command for the
  scenarios; left on for any faction players found themselves.
- `siegeLog` on.

### 12.3 Operator scenario scripts

Scenario files in `config/charter/scenarios/*.json`, run with `/charter admin
scenario run <name> [here]`. Each creates factions with named members (real
players assigned by the op), places keeps and camps at offsets from the op's
position, stocks granaries and stores, and sets clocks. Every scenario step is
logged as `admin`.

| Scenario | Setup | Tries |
|---|---|---|
| `raid` | Faction A: a keep and three farm claims with crops and animals. Faction B: a camp raised instantly next to the farthest farm, store stocked | The farm's hold drains in 15 real minutes; defenders sally; does B get the crops before A cuts the camp off by claiming around it? |
| `partial-siege` | A keep with the granary at 50%, two B camps on one side, three sides open. A stocked chest outside the perimeter, rails to the keep on one open side | Runners carry food in on foot and by cart; B tries to intercept. Does carry-in feel like play or like chores? |
| `encirclement` | A keep at 30% and three B camps covering all twelve perimeter chunks | Nothing gets in. Defenders try a tunnel and a pearl (both refused, with reasons). Does the keep hit starving, does assault happen, does it fall within the evening? |
| `sally` | The `encirclement` setup with defenders standing beside one camp | The camp's sally protection drains in about 2.5 minutes at scale 24; breaking it opens a side. Then a runner gets food in |
| `jail` | A keep with a cell. Faction A has a Sheriff office. An outsider and a member of B trespass | Arrest with a lead, sentence (confinement, then a fine), an escape attempt, an execution with banishment. Does the prisoner's mouth show on the granary? |

### 12.4 What the first session should try, in order

1. **A raid** (`raid`), 20 minutes. Learn the map, borders, holds.
2. **A partial siege with runners** (`partial-siege`), 45 minutes. The central
   question: is running food fun, and is catching runners fun?
3. **A full encirclement** (`encirclement`), 60 minutes, to the fall. The
   besieger picks raze the first time; if time allows, a rerun with occupy.
4. **A sally that breaks a camp** (`sally`), 20 minutes.
5. **A jail sentence** (`jail`), 20 minutes.

Each part ends with a two minute in-game check-in from the op ("what were you
trying to do just then?"), noted with a time so it lines up with the siege log.
Lemon (`pocketdungeons/docs/LEMON_AGENT.md`) is Pocket Dungeons specific and is
not ported for the first session; its approach (an agent reading the event
stream and asking short questions at the right moment) is the model for a later
Charter companion.

### 12.5 Debrief questions

Answered from the siege log, `siege_report.py`, the Chronicle and the players.

- **Supply.** Did players understand which sides were open without being told?
  How many crossings were refused, and for which reasons? Were any refusals a
  surprise? Did carts get used?
- **Timing.** At scale 24, did 3.5 hours feel like a siege or like waiting?
  Scaled back to real time, does 3 to 4 days sound right to the players? Where did
  the drain multiplier actually sit (the log has it every siege minute)?
- **Presence.** Did defenders stand on the walls because it mattered? Did
  attackers camp? Did the 0.5 to 2 bound ever bind?
- **Encirclement.** Was three camps the right cost? Did anyone try to break the
  ring, and how?
- **Passive play.** Could the raided faction have lived well by farming and
  claiming roads? What would they have needed?
- **The fall.** Did raze and occupy each feel like a real choice? Was plunder
  worth the siege?
- **Justice.** Was arrest possible in practice? Did the prisoner have anything to
  do? Did anyone feel punished unfairly?
- **Text.** Which alerts or refusals confused someone? Which Chronicle lines did
  people read aloud?
- **Bugs and exploits** found, filed as numbered entries in the style of
  `pocketdungeons/docs/reference/BUGS.md`, with harness bugs filed the same way.

## 13. Risks and unknowns

| # | Risk | How to find out cheaply | Fallback |
|---|---|---|---|
| R1 | **Crossing checks cost too much** at 100 players | The per-tick check only compares chunk coordinates; measure in M14 with a core benchmark (100 players, 30 keeps, a random walk) and a dev server tick profile with 20 bots from the gametest mock player API | Check every 4 ticks for players outside any active site's 5x5 box, every tick only inside it |
| R2 | **Minecart edge cases:** fast minecarts (26.x experiment) skipping chunks, carts pushed by players, carts broken mid-crossing, cart chains, hopper minecarts pulling from the granary from below | M0 spike plus a gametest per case in M10. Read the 26.2 minecart behaviour code for the experimental speed | Scan every tick inside active sites; refuse allowances for a cart whose sampled jump is more than one chunk |
| R3 | **Chunk loading during sieges:** a keep nobody is near unloads; cart lines through unloaded chunks stop | The mirror design (§4.5) is tested in M9; a dev-server test leaves a besieged keep unloaded for a scaled day and compares items | Force-load only besieged core chunks (one chunk per besieged keep), as a knob |
| R4 | **Vanilla client limits on displays:** text displays render only within entity range; books cap at 100 pages of 1,024 characters; chat lines wrap | M8 and M13 checks on a vanilla client | Keep only the latest 50 Chronicle pages in a book, older pages in "volume" books on request |
| R5 | **The smoke column is not seen** past the client's particle and render distance | Stand 128, 192 and 256 blocks away in M8 with default client settings | Add a tall column of block displays (wool smoke puffs) that render as entities at longer range |
| R6 | **Alt accounts:** alts as defenders slow the drain, alts inflate active members (more storage, more mouths, more claims), alts vote | Vote eligibility (3 days) and the AFK rule already limit it; the playtest log shows presence by player | Count presence only for members of at least N days; cap defender presence at a share of mouths |
| R7 | **Ender chests** as a supply line | Blocked inside besieged sites (§5.2); a gametest proves it | None needed |
| R8 | **Shulker boxes:** a full shulker of steak is 27 stacks in one slot, so one runner carries a granary | It is counted honestly; the question is balance, so the playtest watches it | Count shulker contents at a discount, or refuse shulkers in snapshots (knob) |
| R9 | **Logging out inside a besieged keep** to keep a defender present, or to dodge capture as a runner | Logged-out players are never present and never hold new allowances; the log shows logouts | None needed |
| R10 | **Pearls and elytra** carrying runners over a camp: an elytra flight from an open chunk is legitimate, from a camp chunk is not; sampling might miss a fast flight | Gametest with a mock player moved 30 blocks a tick | Treat any crossing with more than 16 blocks of movement since the last tick as a teleport |
| R11 | **Protection holes** the Mixins miss: sculk, ravagers, withers, sand and gravel falling across a border, tree growth, item frames and paintings broken by projectiles | A list of each, one gametest where cheap, accepted otherwise and noted in the README | Add a Mixin only for a hole players actually use |
| R12 | **Mixin targets renamed in 26.2** (the design names come from earlier versions) | M0 spike compiles and applies all ten | Find the new target before M8 starts |
| R13 | **Granary capacity:** even four double chests may be too small for a large faction's 84 hours of bread (§15) | Compute per faction size in M3 and print it in the README | Raise `granaryMaxChests`, or value preserved food higher |
| R14 | **Coexistence with Pocket Dungeons** on the shared test server | Boot both in a local `runServer` with both jars before uploading | Run Charter on its own world on the same server, or a second server (D15) |
| R15 | **Time scale distortions:** at scale 24 the carry-in window and custody lapse are seconds long, so the playtest can misjudge timing | §12.2 overrides; the debrief asks about it | D13 |

The three biggest, in the order to retire them: **R2** (carts are the supply line
made physical; if they are unreliable the siege idea is hard to read), **R12**
(every protection feature waits on the Mixin targets), and **R1** (the design
promises 100 players; a cheap benchmark now avoids a rewrite later).

## 14. Open decisions

Each has a recommendation and the milestone it blocks. Where it blocks nothing in
the first build, it says so.

### From DESIGN.md §23

| # | Question | Recommendation | Blocks |
|---|---|---|---|
| D1 | Can several factions besiege the same keep at once? | **Yes,** as the design recommends. All their camps count toward encirclement; the drain is the keep's, not per camp; truce applies per attacker | M2 (perimeter openness), M5 |
| D2 | Food values and shelf lives | **Nourishment from the vanilla food component, shelf class from `food.json`,** classes and rates as in §6.3 of this plan. No variety bonus yet | M3 |
| D3 | Unrest formula | **Defer;** decide when tribute contracts exist | Nothing in the first build |
| D4 | Plunder share | **25%** of the treasury, spilled as items in the core chunk | M11 |
| D5 | Persistence engine | **World folder JSON snapshot plus append-only history,** not `SavedData` and not SQLite (§8 of this plan) | M7 |
| D6 | Ships | **A camp replanted at a dock;** no moving claims. Chest boats count as carts for carry-in (§15) | M10 (chest boats only) |
| D7 | Can a player carry food in for a faction they do not belong to? | **Yes, with a deposit grant** from the keep's owner. That grant also lets them count as garrison if it includes the garrison power | M6, M10 |
| D8 | Mod name | **Keep Charter** for the first jar; renaming later changes the mod id and config folder, so decide before the first public server | M0 (mod id) |
| D9 | Real time or online time for sentences | **Real time** with the 1 day cap | M12 |
| D10 | Custody fine | **Yes, capped at 16 diamonds,** from the prisoner's own vault deposit; a player with no deposit pays nothing | M6, M12 |

### War score versus declared war goals (D11)

**The choice.** The design has war score: points earned per pair of enemies,
spent on terms at the peace table. The alternative, Stellaris style: when a
faction first raises a camp against another it **declares a war goal** (cede
these claims, tribute for N days, vassalage, release prisoners, humiliation).
Both sides accumulate **war exhaustion** from what the war costs them: camps
lost, claims lost, keeps fallen, food burned in sieges, prisoners taken, and time.
When one side's exhaustion reaches 100%, the other can **enforce** the declared
goal (attacker wins) or a **status quo peace** (defender wins) as an imposed
contract. Before that, both can sign any treaty they like.

**Recommendation: declared war goals with war exhaustion.** Reasons:

- It fits the supply model. Exhaustion from food burned and camps lost measures
  the thing Charter is about; war score from "claims taken" rewards the one
  action the design most wants to be slow.
- It tells the defender what is at stake on day one, which is what lets a
  passive faction decide to negotiate instead of starve, and makes conditional
  surrender ("these chunks on Saturday unless the siege lifts") the natural
  shape of a peace.
- It stops point farming without the per-pair bookkeeping argument: you cannot
  farm exhaustion from a fake enemy without actually spending camps and food.
- It gives wars an end that is not total destruction, the main failure of
  kingdom servers.
- It replaces the **war score basis** with a **war goal basis** in §15.4; the
  term vocabulary, caps and notoriety are unchanged.

Costs: one more thing to declare (a chat page at banner planting) and a new
basis. Keep the declaration small: at most two goals per war, chosen from the
term vocabulary with server caps.

**Blocks:** the treaty milestone after the first playtest, not the first build.
The first build records every war event per pair of factions (camps raised and
lost, claims raided, keeps fallen, food burned, prisoners) in the history, so
both models can be scored against the real first playtest before the owner
commits. The custody rule "jailing an outsider gives war score against the
jailers" (§15.5) is recorded as an event only.

### Labour terms: bondage and signed indenture (D12)

**The proposal.** Two new terms in the shared vocabulary:

- **Bond (imposed, custody basis):** instead of a cell, the prisoner is
  **tethered** to the jailer's land (pushed back like banishment in reverse when
  they try to leave) and given build and use access there so they can work. It is
  capped like confinement (1 hour to 1 day), counts against the same weekly cap,
  and the bonded player is a mouth on the granary like any prisoner. Resisting
  (walking out past the tether by force, or being freed) is not a breach.
- **Indenture (signed):** a contract a player signs freely to work for an
  organization for longer than any sentence: tethered to named land, with access,
  and a **delivery quota** and a **wage** (both existing terms). Leaving early is
  a breach on the player's record.

**Recommendation:**

- **Bond: build it, behind a knob (`labourTerms`, default off), in M12** if the
  owner wants it in the first playtest; otherwise in the release after. It is one
  new term (`Tether`) on top of confinement and access, with every cap the custody
  basis already has. It gives a prisoner something to do, which the debrief
  question on justice will probably ask for.
- **Indenture: defer to the signed contracts milestone,** with three rules:
  a hard cap (recommend 7 days), a wage term that must be present and paid from
  escrow, and **no indenture may be offered to a player who is in custody or was
  sentenced in the last 24 hours**, so a sentence can never be converted into a
  long signed servitude. Either side can end it early for a buyout set in the
  contract.
- The Chronicle names both parties of every bond and indenture.

**Blocks:** M12 if the bond is wanted in the first build (the cell and tether
code share the confinement check); the signed contracts milestone for indenture.
A gap to settle with it: whether goods a bonded player gathers belong to them or
to the jailer. Recommendation: to them, since only the quota is enforceable.

### Raised by this plan

| # | Question | Recommendation | Blocks |
|---|---|---|---|
| D13 | Which timers does `timeScale` cover? | All siege, spoilage, raising, grace and carry-in timers; **not** governance (votes, membership age, active window); sentences only if `scaleSentencesWithTimeScale` is set. For playtests, override the carry-in window rather than exempting it | M5, M12 |
| D14 | Does siege time run while the server is down? | **No.** The siege clock pauses with the server | M5, M7 |
| D15 | How the jar reaches `714a0605`, and whether Charter shares it with Pocket Dungeons | Add a `--jar` pattern to `pdserver.mjs deploy` (a small change in `pocketdungeons/tools`, needs the owner's OK) and share the server, overworld only | M14 |
| D16 | Granary capacity (see §15) | Linked chests up to `granaryMaxChests` (8 single chests) | M9 |
| D17 | Who is "the enemy" for open and closed sides | A keep's perimeter chunk is closed if claimed by any faction with an active camp touching that keep's ring. A camp's perimeter chunk is closed if claimed by the faction it is besieging or raiding, or by any faction with an active camp against the camp's owner. Neutral third-party claims are open in both cases (§15) | M2 |
| D18 | Does execution drop the prisoner's items? | **No:** the executed player keeps their inventory (the drama is the respawn wait and banishment, not loot) | M12 |
| D19 | Farmland trampling in claims | **Protect it** (Mixin 10) until the hold is empty | M8 |
| D20 | Does a camp eat while raising? | **No;** only while besieging or raiding | M5 |

## 15. Gaps and contradictions found in DESIGN.md

Listed rather than silently resolved. Each says what this plan assumes until the
owner decides; none changes DESIGN.md.

1. **Endurance test J versus spoilage.** §10.3 and test J say a full granary of
   preserved food lasts 84 hours. Preserved food spoils at about 2% a day
   (§24), so a full granary loses roughly 7% over a 3.5 day siege and lasts
   about 79 hours. Assumed: J passes within 7% of 84 hours. Alternatives: the
   storage limit is computed with spoilage included (a slightly bigger granary),
   or preserved food does not spoil while besieged.
2. **Test J versus assault.** J says "never less than 42 hours whoever is
   present", but §10.7 lets attackers inside a starving keep drain it four times
   faster, and the 25% below the line would then last about 2.6 hours at the
   2 times bound. Assumed: J's floor applies to presence outside the keep; the
   assault factor multiplies on top of the 0.5 to 2 bound, up to 8 times in
   total. Needs the owner's confirmation of how the two combine.
3. **Starvation threshold of what?** "Below 25%" is not said to be of the
   storage limit or of the contents at the start of the siege. Assumed: 25% of
   the storage limit, which is a fixed 21 hours of drain.
4. **Camp protection against a sally.** §9.5 says a claim stays protected until
   its granary, store or hold is empty. §10.6 says defenders present drain a
   camp's protection in about an hour, and §10.3 says a camp with an empty store
   only stops besieging. That is a third quantity, and the presence rule (a
   multiplier on a base rate) cannot drive it because a camp's protection has no
   base drain. Assumed: each camp claim has a **sally hold** of 1 hour that drains
   only while defenders stand next to it (each defender adds one unit of drain,
   attackers present subtract), refills when nobody is there, and the camp's
   claims lose protection when it empties. An empty store stops the camp
   besieging but leaves it protected until its sally hold empties or its owner
   packs it.
5. **Who is hostile.** §10.4 says a perimeter chunk is open "unless an enemy
   faction claims it"; §10.10 says a camp is cut off by chunks "claimed by the
   defenders, or by anyone hostile to the camp". There is no war or relation
   state in v0 to define enemy or hostile. Assumed: D17. Without it, a neutral
   neighbour's farm would close a keep's side.
6. **Granary capacity.** A full preserved granary for twenty mouths is 16,800
   nourishment, 3,360 bread, 53 stacks: a whole double chest. Thirty mouths
   (test J) needs 79 stacks of bread and does not fit. The design says "a chest".
   Assumed: linked chests (D16).
7. **Weekly punishment cap.** Confinement can be 1 day and the weekly cap is 1
   day, so one sentence uses a whole week. It is not said whether banishment and
   respawn delay count toward the cap. Assumed: confinement and respawn delay
   count; banishment does not (it restricts land, not play). Test U is written
   against that.
8. **Prisoners and storage.** Prisoners add mouths (test V); it is not said
   whether they raise the storage limit. Assumed: they do not, so prisoners make a
   keep fall sooner and cost real food, as §15.5 intends.
9. **Execution mechanics.** Whether the mod kills the player, whether items drop,
   and what the respawn wait looks like are not specified. Assumed: D18, and the
   wait in §7.4 of this plan.
10. **Arrest and cells.** "The officer still has to catch the player" and "a cell
    sits in a keep" have no mechanics. Assumed: a lead used on the player in the
    officer's faction's land, and cells marked with a tagged chain (§7.4 of this
    plan).
11. **Camps and the land cap.** §9.1 caps claims per active member "on top of
    its keeps"; whether camp claims count is not said. Assumed: camp claims count
    toward the cap, the banner chunk does not.
12. **Chest boats and animals with chests.** §10.4 covers players and minecarts
    only. A chest boat on a river into a coastal keep is the same thing as a cart.
    Assumed: chest boats count like carts; donkeys, mules and llamas are ignored
    in the first build (their food is not noted).
13. **Dimensions.** The design does not say where claims may be made. Assumed:
    overworld only, a knob.
14. **"84 hours whatever the faction's size" and notoriety.** A high notoriety
    keep drains up to 1.5 times faster, so it lasts about 56 hours. Consistent
    with §13.2, but the 84 hour claim in §10.3 should say "at zero notoriety".
15. **Siege time while the server is down.** Not addressed. Assumed: paused
    (D14).
16. **War score with no treaties.** §15.5 says jailing an outsider gives war
    score, which is only spendable through treaties (v1). Recorded as an event
    only in the first build.
17. **Test D in v0.** Test D needs contracts, which are v1, but sits among the
    original spec's tests without a note. Deferred, with the D7 half tested.
18. **`docs/DESIGN.md` §4 points to `MOD_AUDIT.md`** for build traps; it is not
    in this checkout. Conventions were taken from `ballot` (§2.1 of this plan).
