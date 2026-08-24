# Mythic+ Spec — Reconciliation with Shipped Pocket Dungeons

> Reconciles the external "Minecraft Mythic+ Dungeon System" design spec against
> what `pocketdungeons` actually ships (U8, MC 26.2) and against the affix
> direction settled in the wolves/affix design pass.
>
> **Verdict in one line:** the spec's *core loop* is already built and shipped;
> its *instance architecture* section is wrong about how this mod works and
> should be discarded; its *affix catalogue* is the genuinely valuable part and
> is largely cheap, because most of it is numbers in a JSON file the mod already
> generates at stamp time.

---

## 1. Triage summary

| Spec section | Verdict |
|---|---|
| Core Loop | **Already shipped**, with one rejected premise (§2) |
| World / Instance Architecture — per-party worlds | **Discard** — contradicts the shipped slot-grid design (§3.1) |
| World / Instance Architecture — player rooms | **Adopt in reduced form** — own milestone, cheaper than it looks (§3.2) |
| Lifecycle Rules | **Discard the instance-lifecycle half** — premise is false here (§3.3) |
| Affix System | **Adopt, with re-basing** — the best part of the document (§5) |
| Affix rotation cadence (open item) | **Resolved** (§4) |

---

## 2. Core loop: already shipped

Everything the spec describes as the loop exists today.

| Spec line | Shipped as |
|---|---|
| Key tied to a difficulty level | `Keystone` + `DungeonLog`, levels 1–`keystoneMaxLevel` (25) |
| Using key at entrance locks difficulty, starts timer | `RitualListener` lodestone → `Instances.enterWithKeystone` → `RunTimer` |
| Timer | `timerBaseSeconds: 180` + `timerPerRoomSeconds: 60`, `rewardRoomGraceSeconds: 600` |
| Complete under timer upgrades the key | `KeystoneMath.upgrade`, `Keystones.Outcome` |
| Missing the timer depletes it | `timedOutDepletion: 1`, `lateCompletionDepletion: 2` |
| Affixes on top of base scaling | `Keystone.Affix` = `NONE` / `OMINOUS` / `FRAGILE` |

### 2.1 One rejected premise: "a Trial Key item dropped from vaults"

The spec wants the key to be a **trial key item that drops from vaults** and
carries the difficulty. This mod tried that and inverted it deliberately.

`Keystone`'s class javadoc is explicit: the keystone is **"a remote, not a save
file."** Level and affix live in `DungeonLog` (a `SavedData` keyed by UUID); the
item in the inventory renders that state and is the affordance for spending it,
but is never the authority. That inversion deleted the reconciliation path, the
offline-delivery path, the duplicate-keystone special case, and the completion
token.

**Do not re-adopt the spec's model.** Two copies of the remote open the same run,
which is exactly why duplicates need no special case. Reverting to item-as-truth
reintroduces every problem U7 removed.

The vault-drop framing is also already occupied: `trial_key` /
`ominous_trial_key` are the *vault opening* items (`vaultKeyItem`,
`ominousVaultKeyItem`), ejected by trial spawners via
`pocketdungeons:spawners/trial_key`. The keystone is a `recovery_compass`. These
are two different items doing two different jobs, and the spec conflates them.

### 2.2 Leaderboards

Not shipped, and `FEATURE_PROPOSAL.md` explicitly defers them. `DungeonLog` is
per-player `SavedData`; a leaderboard needs a server-wide structure that does not
exist yet. Keep deferred — orthogonal to affixes.

---

## 3. Instance architecture: discard

This is the section that most needs rectifying. The spec reasons from an
architecture this mod does not use, and reaches conclusions that are wrong here.

### 3.1 "A freshly created isolated per-party world slot, so no claim registry is needed"

**False for this mod, and inverted.** Pocket Dungeons uses **one** dimension —
`pocketdungeons:void` — subdivided by a **slot grid**: `slotPitch: 2048`,
`slotsPerRow: 64`, with `allocateSlot()` over a `usedSlots` `TreeSet` and a
free-list returning slots on teardown.

The slot registry is not overhead the spec found a way to avoid — **it is the
collision solution**, and it is load-bearing. Dynamic per-party dimension
creation is not something vanilla Fabric gives you cheaply, and the mod would
gain nothing from it.

**Consequence:** the spec's claim that generation can use "a normal branching/BSP
algorithm with full spatial freedom" does not follow. `LayoutGraphGenerator`
plans on a bounded cell grid (`maxGridSpan: 12`, `planAttemptBudget: 16`, 5–8
room path plus 0–2 spurs) because rooms are 1×1-footprint templates joined by
jigsaw door anchors. That constraint is what lets fourteen `.nbt` files cover
every (mask, role) combination. Free-form BSP would throw that away.

### 3.2 Persistent per-player rooms — adopt, in reduced form

Not shipped, and its own milestone — but **cheaper than it first appears**, and
worth taking. Revised after a closer look at the code.

**Persist as data, not as residency.** A base does not have to live in the world
fighting force-loading and eager teardown. `StructureTemplate` exposes
`fillFromWorld(...)`, `save(CompoundTag)`, `load(...)` and `placeInWorld(...)` —
all verified in the 26.2 jar, and `TemplateStamper`/`RoomManifest` already use
three of them. A player base is therefore just another structure template,
authored by the player rather than shipped in the jar:

- **on exit** — `fillFromWorld` the cell, `save`, stash the blob in a `SavedData`
  beside `DungeonLog`
- **on entry** — allocate a slot, `placeInWorld` the stored blob (or the default
  template on first visit)
- the slot returns to the free-list exactly as it does today

This costs **one NBT blob per player and zero standing tick budget**. No
permanent slot claims, no eviction policy — §3.3's verdict survives intact. The
*resident*-base version would have broken it: `allocateSlot()` is a linear scan
from 0 over a `usedSlots` set that would grow with headcount and never shrink,
and slot exhaustion would have made the spec's LRU section relevant again.

**The precedent already ships.** The selector room is this exact shape —
`InstanceRecord.selectorRoom`, its own `selectorRoomLayout(origin)`, its own
enter path, its own `purgeIfAbandonedSelectorRoom`. An owned, non-dungeon room
in the void with a distinct lifecycle is a solved problem here. A base is that
plus a save-back step.

The grid already matches the spec's language too: `originForSlot` is
chunk-aligned and `slotPitch` is validated as a multiple of 16, so **a cell
literally is a chunk**.

**Branching from it is nearly free.** `LayoutGraphGenerator` already places the
entrance at cell 0. Stamp the player's base into cell 0 instead of
`entrance_hall` and the planner does not change at all — reachability, the
≥1-encounter / ≥1-loot guarantees and `RoomSelector.validate` all hold untouched.

**The real cost is the build mask.** `RoomManifest` validates door jigsaw layout
and footprint, so a decorated base must keep its shell, its door anchors and its
1×1 footprint or the stamp fails. The mod has **no block-break restrictions
today** — there is no `PlayerBlockBreakEvents` registration anywhere — so
protecting the shell while leaving the interior free is new machinery, and it is
the part that will feel bad if it is wrong. The spec's own instinct is right:
ship a default template with anchors pre-placed and immutable, decorate inward
only.

**Cut the adjacent-party-bases half.** Multi-room alignment, the per-member
10-minute save-back grace, "room persists when the player leaves the party" and
leader-transfer rules are where the remaining expense lives. **One base per
player; a party enters the leader's base and runs from there.** Roughly 80% of
the value at 30% of the cost.

**Two cautions.**

1. This is a **new data-loss risk class**. The mod has never stored
   player-authored content. Losing a run is forgivable; losing someone's base is
   not. Backup-on-write and an `admin baserestore` are not optional here the way
   they would be for run state.
2. It **changes the loop's shape** — from a clean excursion (overworld → dungeon
   → overworld via `ReturnPoint`) to a hub model (overworld → base → dungeon →
   base → overworld). More steps, but also a real answer to the "get them in the
   door: Weak" finding in `docs/SUITE_AUDIT.md`, and the obvious home for the
   suite's other surfaces (the Kamu Station fletching table, the dungeon log, a
   wolf kennel).

**Sequencing:** ship affixes first — they are numeric edits to files the mod
already generates. This is a milestone with a new persistence class behind it.

### 3.2.1 Settled decisions

**Adjacent party bases: cut.** One base per player. A party enters the host's
base and runs from there.

**The base replaces the selector room.** The merge is nearly free:
`selectorDoorStep` already computes the three door positions from *hardcoded
offsets* (`dz == 8`, `dy == 1 || 2`), with a comment noting the room is always
stamped at rotation 0 so there is no transform to account for. Keep those three
positions and the leave-lodestone at `origin.offset(7, 0, 12)` as fixed anchors
in the base template, let the player decorate around them, and `selectorDoorStep`
needs **no change at all**.

Because the base is stamped fresh from the saved blob on every entry, stamp the
**door blocks conditionally** — oak / crimson / copper materialise only when
`pendingOfferLevel > 0`, plain wall otherwise. Doors appearing in your own base
because you earned an offer beats being teleported into a room you did not ask
for.

> ⚠ **Ownership bug to avoid.** `selectorDoorStep` gates on
> `byMember.get(player.getUUID())` and `record.selectorRoom` — nothing about
> ownership. With a party in the host's base, a guest passes that check and can
> click the host's doors, spending *their* offer. Gate on
> `player.getUUID().equals(record.owner)`.

**Base permissions: owner + whitelist only, for breaking blocks and opening
containers. The dungeon stays fully breakable by anyone** (no change — that is
shipped behaviour).

- Container protection slots into `RitualListener.onUseBlock`, which already owns
  the mod's `UseBlockCallback` and already branches on instance state.
- Block breaking is new machinery — there is no `PlayerBlockBreakEvents`
  registration anywhere in the mod today.
- The whitelist lives in the same `SavedData` as the base blob, keyed by owner.
- **Capture at purge time, not on owner exit.** If the host leaves before their
  guests, an owner-exit capture would save the room while guests are still in it.

### 3.2.2 Bedrock envelope — stop players falling out of the dungeon

`RoomBuilder` stamps sealed 16×16 boxes with a `POLISHED_ANDESITE` floor and
`STONE_BRICKS` walls and ceiling — all trivially mineable, and the dungeon
deliberately has no break restrictions. A player digs through the floor and drops
into open void. `voidGuardDepth: 10` catches them below y=54, but only on the
`watchIntervalTicks: 20` cycle, so it is a real fall and an ugly recovery.

**Do not fill the negative space** — that volume is enormous. Add a one-block
bedrock envelope *outside* the existing shell:

| Layer | Where | Why |
|---|---|---|
| Sub-floor | local Y−1 under every cell | keeps the visible polished andesite intact |
| Over-ceiling | one above the ceiling | stops pillaring out onto the roof |
| Outer wall ring | **only** on faces with no adjacent occupied cell | an unconditional ring would put bedrock *between* rooms, since every cell already owns all four of its own walls |

`PlanGeometry` holds the cell list and `DoorMask` the door directions, so the
neighbour test is already computable. Cost is a few hundred blocks per cell —
against `clearBlocksPerTick: 8192`, roughly one tick's work for a whole layout.

Keep the void guard as the backstop regardless; it costs nothing and covers
whatever the envelope misses.

### 3.2.3 The resulting loop

The base changes the shape of the loop. Recorded here as the target, since it
differs from what ships today in two deliberate ways.

| Step | Mechanism |
|---|---|
| Overworld lodestone + keystone | → your base |
| Three doors, rendered from `Keystone.offers(level)` | → the run's level + affix |
| **Use key on a door** | → dungeon generated, `RunTimer` starts, base is cell 0 |
| Complete on time | reward room, `Payout`, level banked |
| Exit pad | → back to your base |
| Miss the timer | depletion (`timedOutDepletion` / `lateCompletionDepletion`) |
| Base lodestone | → overworld, via `ReturnPoint` |

**Change 1 — the door replaces the lodestone as the dungeon entrance.** Today
these are separate: the lodestone starts a run (`RitualListener` checks
`Blocks.LODESTONE`), and the three doors only grant the *next* keystone after a
completion — choose, get sent home, then go spend it at a lodestone. Collapsing
them is one action instead of two, and the code is already shaped for it:
`Keystone.offers(level)` returns exactly three, the room has exactly three doors,
and `selectorDoorStep` already maps a position to 1/2/3.

It also **preserves the affix prep window**. Because the base is persistent and
the doors are a standing fixture, a player sees "door 2 is Feral", leaves to
gather bones, and comes back to commit. Better than a chat message, which can be
missed or scrolled past.

**Change 2 — the exit pad returns to the base, not the overworld.**
`ReturnPoint` still handles the base → overworld leg.

### 3.2.4 The closed loop — move the room, do not copy it

> Supersedes an earlier two-copy design (stamp the base at cell 0 *and* the
> terminal cell, teleport across the threshold). Moving is better on three
> counts, and retires a whole bug class.

On completion the base is **captured from cell 0, cleared from cell 0, and
re-stamped at the terminal cell** behind a closed door. The player opens it and
walks into their own room. No teleport, no second copy — the room genuinely
moved.

**Why this beats the two-copy version:**

1. **It is one room.** The anomaly is real rather than a sleight of hand.
2. **The corruption-capture hazard disappears.** The earlier design needed a hard
   rule that save-back must never read the terminal display copy. With one copy
   in existence there is nothing to confuse.
3. **The finished dungeon lingers as a quarry**, which is the material supply
   chain `VISION.md` §3.6.1 identifies as missing for the decorate pillar.

#### Order of operations — non-negotiable

**Capture → persist → clear → stamp.** The blob must be written to `SavedData`
*before* cell 0 is touched. Between the clear and the stamp the base exists only
in memory; a crash in that window loses it outright. Persisting first makes the
base reconstructible from disk at every point. See §3.2's data-loss caution.

#### Hazards

| Hazard | Fix |
|---|---|
| **Party stragglers.** `completeRun` fires on the *first* member to reach a pad, and there can be six. A member standing in cell 0 when it clears lands on the bedrock sub-floor in an empty box. | Sweep cell 0 for players and pull them forward before clearing. `teardown` already has a "never leave a stray player behind" sweep; `finishClear` has the entity-sweep pattern. |
| **The exit pad must sit *before* the final door.** If it is inside the base, the player opens the door onto void before completion fires and the trick is dead. | Pad in the final room, base stamped in behind the closed door. |
| **Force-loading a lingering dungeon** would extend the per-tick cost §3.3 exists to avoid. | **Release the tickets, keep the blocks.** Blocks persist in region files unloaded; chunks load when a player walks near. Force-loading only exists so mobs and spawners tick, and a completed dungeon has no live encounters. `finishClear` already calls `setChunkForced(..., false)` per cell — run that path *without* the clear. |

#### Reuse and knobs

`PendingClear` is already a budgeted, tick-spread clear (`advance(level, budget)`
/ `done()`); a **one-cell** `PendingClear` is exactly the primitive needed —
roughly 2,000 blocks against `clearBlocksPerTick: 8192`, so under a tick.

- `rewardRoomGraceSeconds: 600` is the post-completion window today; mining wants
  it longer or unbounded.
- The lingering dungeon needs a purge trigger when the next run starts.
- The slot stays claimed while it lingers — bounded at one per player.

#### What the player finds

Walking back afterwards, **cell 0 is empty**. The mine is there; their room is
not. That enforces "you cannot go back the way you came" without the mod saying
anything, which is the silent-vs-slang rule below.

**The mod says nothing about this.** No message, no sound cue, no lore. Vanilla
never explains the Deep Dark or the ancient cities either; the silence is the
effect. Everything the mod *does* say stays in the suite's slang voice
(`Unhinged Feral Keystone [24]`, *Cooked*, *Big L*) — the split is
**silent-vs-slang**, not clinical-vs-slang.

The single acknowledgement is an **advancement** on first completion — the
Minecraft-native wink, and `spiritwolves` already ships an `advancement/`
directory as precedent. A toast, a shrug of a description, no confirmation.

**Escalating wrongness (optional, scales with affix count).** At low levels the
terminal room is exactly the player's base. As affixes stack, apply a corruption
pass to the terminal copy — a chest a block off, a torch that is not theirs,
colder lighting. Silent, never announced.

> ⚠ **Corruption must never be captured.** Save-back reads the *real* base
> instance. If it ever reads the terminal display copy, the corruption becomes
> permanent and the mod has silently vandalised a player's build — the
> unforgivable failure mode flagged in §3.2.

**The base is entered, not spawned into.** Making it the respawn/login point
would turn Pocket Dungeons into the server hub and displace the overworld, which
fights the rest of the suite — the Kamu Station, `wayfarers` and `cobbleeconomy`
all live out there. Overworld lodestone in, base lodestone out.

**The keystone stays the authority.** It is tempting to let the door define the
run's level and affix; do not. The doors are *rendered from* the current level
via `Keystone.offers(level)`, and `DungeonLog` remains the truth — see §2.1. The
door is an affordance, exactly as the item is.

> ⚠ **Do not bump the level twice.** The door picked *is* the upgrade: running
> door 2 means running at level+2, and completing banks it rather than adding
> again. Gate the +N doors on `pendingOfferLevel > 0` so a player cannot pick
> door 3, fail, and re-pick it indefinitely. With no completion behind them, the
> doors offer a re-run at the current level only.

### 3.3 "Instances are cheap and long-lived; evict under slot pressure via LRU"

**The premise is false here.** Pocket Dungeons instances are **force-loaded** —
`setChunkForced(...)` across the instance's chunks. Force-loaded chunks cost
server tick time continuously, whether or not anyone is inside. That is precisely
why the shipped design tears down eagerly instead of idling:

- last member leaves → `dropMember` → `closeIfEmpty` → `purge`
- a watcher on `watchIntervalTicks: 20` sweeps for void-fall, departure, expiry
- teardown clears blocks at `clearBlocksPerTick: 8192` and discards every
  non-player entity in bounds
- server stop purges everything

An LRU eviction policy solves a problem this architecture does not have: with 64
slots per row and eager teardown, slot exhaustion is not the binding constraint —
tick budget is. **Discard the whole eviction section.**

### 3.4 The one lifecycle idea worth keeping

The spec's rule that **the dungeon is deleted outright when leadership changes
unexpectedly, rather than transferring ownership**, is a good instinct and
matches something this codebase learned the hard way. `PLAN.md` documents a
disconnect-during-teardown race that corrupted the next login, rooted in exactly
this kind of ownership/teardown ambiguity. `InstanceRecord` carries an `owner`
UUID and `Keystones.returnTo` is member-scoped, so ownership transfer would be
real work with real edge cases.

"Purge and let them re-key" is cheaper and safer, and `purge()` already does the
whole job. **Adopt this rule.** Note it is stricter than what ships today
(purge-when-empty), so it is a behaviour change, not a no-op.

---

## 4. Affix rotation vs. level thresholds — resolved: thresholds only

The spec wants a **weekly rotation**. The direction is **stacking by keystone
level** (5+ → one affix, 10+ → two, 15+ → three).

**The rotation is cut.** An earlier pass of this section adopted both, on the
reasoning that they answer different questions and that together they are the
real WoW model. Research reversed it:

- Blizzard **removed the seasonal affix outright** in Dragonflight Season 2,
  stating that the variety it added "was no longer needed" because dungeon
  rotation already kept the content fresh.
- Patch 11.0.2 then **deleted** Afflicted, Entangling, Incorporeal and Storming
  in a wholesale overhaul. The direction of travel is fewer affixes, not more
  churn over them.

WoW needed a rotation because it ships a **fixed pool of hand-built dungeons**.
We ship a procedural generator plus themes and recipes (`VISION.md` §5), which is
strictly more variety than a dungeon pool — so the rotation buys freshness we
already have, in exchange for owning a server-wide time seed that drifts across
timezones. Bad trade.

What remains answers both questions with one mechanism:

| Question | Answered by |
|---|---|
| *How many* affixes are active? | Keystone level thresholds (deterministic, per-key) |
| *Which* affixes fill those slots? | Seeded from the keystone itself — same source as the layout |
| What extra affix did I opt into? | The selector-room door choice (elective, costs levels) |

Seeding from the key rather than the calendar keeps the name a pure function of
the key (§6), keeps two players' keys interestingly different on the same
evening, and needs no new persistence at all.

### 4.1 Threshold spacing — worth a second look

Current Mythic+ activates affixes at **5 / 7 / 10 / 12**, and Blizzard has been
pushing those thresholds *later* over time so that players acclimate to a dungeon
before more mechanics arrive, and so complexity matches the reward on offer.
⚠ The specific "second affix 4→7, third 7→14" figures are from patch reporting
rather than the wiki; the 5/7/10/12 list is wiki-verified.

Our 5 / 10 / 15 on a 1–25 ladder picks the same direction but spaces it wider:
four changes inside WoW's first twelve levels, three inside our first fifteen.
Worse, 5 / 10 / 15 leaves **levels 15–25 completely flat** — ten levels in which
nothing about a run changes, sitting exactly where the most invested players live.

**Decision: 5 / 11 / 17.** Three thresholds spread across the whole ladder rather
than crowded into its first half. The largest gap drops from ten levels to six,
the top of the ladder gets a change of its own, and the elective door still lets
anyone volunteer for one more at any level. WoW's density is not the target,
because WoW has no elective axis — we do, and it carries part of the load.

**Storage is already stack-ready.** `DungeonLog` stores the affix as
`Codec.STRING.optionalFieldOf("keystone_affix", "")` and `Keystone.mint` writes a
plain string tag. Comma-join a set into the same field: old saves holding
`"ominous"` parse as a one-element set and `""` as empty. No codec migration.

Only three sites actually branch on the affix today — `Instances:360` and
`Instances:1137` (`== OMINOUS`) and `Keystones:63` (`== FRAGILE`) — so the
refactor to `contains(...)` is small.

---

## 5. Affix catalogue — triage against real code

The spec's stated principle — *reuse existing Minecraft mechanics and change
their trigger condition or intensity, rather than inventing new systems* — is
already this mod's ethos (`environment: server`, no custom blocks or items, no
`assets/` directory, vanilla trial spawners and vaults). **Keep the principle
verbatim.**

### 5.0 The design rule: every affix hands you something

**Kiss/curse is the rule here, not the exception.** Blizzard's own answer to
affix fatigue was not more affixes — it was affixes players can *use*: the
level-5 slot in the current system (Xal'atath's Bargain) buffs the *party*
rather than the enemy.

We arrived at one by accident. **Feral gives you wolves.** That is the model, and
every affix in the catalogue below should be read against it: bend a rule, and
pay for it with a gift.

Two things fall out, and both are worth more than the affixes themselves:

1. **It dissolves "do rewards scale with affix count?"** (old §7.2). If every
   affix carries its own payment, reward scaling is intrinsic to the affix and
   needs no separate multiplier bolted onto `DifficultyProfile.lootTier()`.
2. **Nobody parks below a threshold.** The failure mode that question was
   worrying about — players settling just under 15 to dodge a third tax — cannot
   happen if the third slot hands them a present.

It is also the funnier design, which is the point of §6. *Unhinged Feral* should
read as a boast, not a tax bracket.

### 5.1 The big finding: most of these are numbers in a file we already write

`TrialContent` writes a trial-spawner config at stamp time, per tier, from
`data/pocketdungeons/trial_spawner/tier_N/{normal,ominous}.json`. That file
already contains:

```
total_mobs, simultaneous_mobs, total_mobs_added_per_player,
simultaneous_mobs_added_per_player, ticks_between_spawn, spawn_potentials[]
```

Any affix expressible as "more mobs / different mobs / faster waves" is a numeric
edit to a document the mod already generates per run. That is the cheapest affix
surface available and it should be mined first.

### 5.2 The spawner-triggered category needs re-basing

**All four spawner affixes assume classic destructible spawners.** This mod runs
**trial spawners**, and `TrialContent` deliberately *replaces* any authored
classic spawner ("two spawners in one room is two difficulty curves"). Trial
spawners are hardness-50, drop nothing, and are not the play pattern — they
**exhaust into cooldown**, they are not destroyed.

- **Overclocked** → re-base onto `trialSpawnerCooldownTicks` (36000), which the
  mod already writes per stamp. Multiply it down. Nearly free.
- **Vengeful** → re-base from "spawner destroyed" to "spawner entered cooldown".
- **Unstable Core** → drop. Loses its meaning without destruction.
- **Linked Spawners / Bonded** → cross-spawner shared state; expensive, defer.

### 5.3 Feasibility table

| Affix | Cost | Notes |
|---|---|---|
| **Swarming** | Trivial | `total_mobs` / `simultaneous_mobs` in the JSON we already write |
| **Overclocked** | Trivial | Scale `trialSpawnerCooldownTicks`, already written per stamp |
| **Volatile** | Low | Add creepers to `spawn_potentials`; slow variants need spawn-data components |
| **Reinforced** | Low | Vanilla zombie reinforcement attribute, set in spawn data |
| **Molten** / **Frostbitten** | Low | Stamp-time block placement, same path `RoomContent`/`TrialContent` already use |
| **Blighted** | Low | Death listener → place wither rose; `ALLOW_DEATH` is already registered |
| **Silenced** | Low | One item-use listener; no custom items needed |
| **Famished** | Low | Tick handler on instance members |
| **Fragile Gear** | Low | ⚠ **name collision** — see §5.4 |
| **Checkered Floor** | Medium | Needs a per-instance ticker; the `watchIntervalTicks` watcher is the hook. High spectacle |
| **Collapsing** | Medium | Chunked block clearing already exists (`clearBlocksPerTick: 8192`) and it pairs naturally with the run timer |
| **Enraged** | Medium | Damage listener + effect application |
| **Locked Down** / **Greedy** | Medium | Vault-open detection; vaults are placed via `TrialContent.applyLoot` |
| **Vengeful** | Medium | Only after re-basing to cooldown-entry (§5.2) |
| **Phasing** | ⚠ Risky | Teleporting mobs can leave sealed cells and break the room-geometry invariant |
| **Bomber** | Hard | Breeze fire rate is internal AI, not a config knob |
| **Bonded** / **Linked** / **Unstable Core** | Hard / drop | See §5.2 |

### 5.4 Name collision — must fix

The spec's **"Fragile Gear"** (doubled durability loss) collides with the shipped
**`FRAGILE`** affix (doubles keystone depletion on failure). Two different
mechanics, near-identical names, both surfaced to the player on the same item.

Rename the incoming one. Under the naming convention (§6) the shipped one is
already **"Big L"**, so the durability affix can take a separate word entirely.

### 5.5 Recommended first wave

Eight affixes is enough to fill three threshold slots plus an elective. Take the
cheapest five and stop — but each one now owes a **kiss** as well as a curse
(§5.0), and the kiss is what needs designing, because the curse is already a
number in a file:

| # | Affix | Curse | Kiss — the part still to design |
|---|---|---|---|
| 1 | **Feral** | Wolves are hostile until caught | You keep the wolves |
| 2 | **Swarming** | `total_mobs` / `simultaneous_mobs` up | More bodies is more drops |
| 3 | **Overclocked** | `trialSpawnerCooldownTicks` scaled down | Faster waves means a faster clear on the clock |
| 4 | **Molten** | Stamp-time lava and magma hazards | **It is the lava faucet** |
| 5 | **Silenced** | You cannot use consumables | **Neither can they hear you** |

**Molten's kiss: it is the only source of lava in the game.** A sealed dungeon has
none, and `VISION.md` §3.7.1 names lava as part of the renewable engine Skyblock
proves you need — it gates furnace fuel and, with water, obsidian, and therefore
the ender chest and the whole Nether-adjacent product chain (§3.7.3). Making one
affix the faucet for it costs **zero new mechanics**: the hazard blocks *are* the
reward, and a player who wants fuel has to opt into the affix that burns them. An
affix that is load-bearing for the supply chain is not a tax.

**Silenced's kiss: the mobs are deaf too.** Curse and kiss are the same sentence
— you lose consumables, they lose hearing — which is the ideal shape for the
rule. It also returns something the design explicitly mourns: §3.3 concedes that
procedural layouts kill Mythic+'s route mastery, and this hands back a *tactical*
route decision in its place. Sneak the encounter or fight it, with no potions to
fall back on. That is the most interesting choice in the affix list, and it comes
from one item-use listener plus a spawner-config change.

Both were open questions in an earlier pass. Both are closed, and the rule in
§5.0 survived its own test.

Plus shipped: `NONE`, `OMINOUS`, `FRAGILE`.

---

## 6. Naming

Affix names follow the Kamu Totems *convention* with **entirely separate
vocabulary** — same machinery, no shared code, no shared words, per
`kamutotems/INTEGRATION.md`'s stranger rule.

Template: `<intensifier> <affix> Keystone [<level>]`, with the remaining affixes
appended as a bracketed subtitle, mirroring `BossNames.build`'s
`<title> [<subtitle>]` shape.

Intensifier ladder, banded on keystone level: **Baby** (1–5), **Lowkey** (6–10),
**Highkey** (11–15), **Menace** (16–20), **Unhinged** (21–25).

| Affix | Label |
|---|---|
| `NONE` | *(blank)* |
| `OMINOUS` | Cooked |
| `FRAGILE` | Big L |
| `FERAL` | Feral |

Render the set in **enum declaration order**, always. The name must stay a pure
function of `(level, affixSet)` — the instance watcher rewrites stale keystones
in place, and any randomness in the label would churn on every reconciliation.

---

## 7. Decisions — nothing open

Every question this document once carried is answered. They are kept here with
their reasoning so none of them is reopened by accident.

### 7.1 Depletion takes the max, capped at 2

`Big L` doubles depletion. Stacking a second depletion-touching affix
multiplicatively would let a level-20 key shed most of a ladder in one bad night.

**Decision: the depletion multiplier is the `max` across the active affix set,
hard-capped at 2× — never the sum, never the product.** Two reasons, the second
stronger than the first:

1. Mythic+ depletes a flat amount regardless of how many affixes are up; its
   affix set never touches the depletion rate at all. We already deviate by
   having `Big L`, and one deviation is enough.
2. **§5.0 makes compounding incoherent.** If every affix pays its own way with a
   kiss, none of them should *also* compound a punishment. Loss aversion runs at
   roughly twice the felt weight of an equivalent gain, so a doubled depletion is
   felt as roughly quadrupled. That is a quit, not a challenge.

### 7.2 The leadership purge is adopted

Confirmed as a deliberate behaviour change from today's purge-when-empty.
`PLAN.md` documents a disconnect-during-teardown race that corrupted the next
login, rooted in exactly the ownership/teardown ambiguity that transferring
leadership would institutionalise. `InstanceRecord` already carries an `owner`
and `purge()` already does the whole job, where ownership transfer is real work
with real edge cases.

**Nobody loses anything by it** — exit keeps inventory and no-death ejection is
already the rule, so a purge costs the party their *run*, not their stuff. Lands
in **M2**, where ownership semantics are being touched anyway.

### 7.3 Molten and Silenced both earn their kiss

Answered in §5.5. Molten is the lava faucet; Silenced deafens the mobs it
silences you against. Neither needed a new mechanic, and §5.0 stands unamended.

### 7.4 Wolves — verified, not assumed

Read out of the 26.2 bytecode rather than tuned by guess:

| Fact | Verified behaviour |
|---|---|
| Catch rate | `tryToTame` is `random.nextInt(3) == 0` — **exactly 1 in 3 per bone** |
| Angry wolves | `mobInteract` checks `isAngry()` and **refuses the bone entirely** |
| On success | `tame(player)`, then `setOrderedToSit(true)` — **the wolf sits down** |

Three consequences, all forced by the code:

1. **Feral wolves spawn neutral, never angered.** Do not reach for
   `startPersistentAngerTimer()`. An angry wolf is an untameable wolf, so
   angering them converts the kiss into nothing.
2. **The readable rule is "don't hit it, feed it."** Vanilla wolves anger when
   attacked, so a player who swings first is locked out until the timer expires.
   That is a real choice — fight it or catch it — and it needs no code to enforce.
3. **A caught wolf sits, and stays sat.** It will not follow through the dungeon
   until told to stand. Either accept that or clear the sit flag on tame.
   **Decision: accept it.** It costs no code, it stops a six-player party
   trailing eighteen wolves through a timed run, and "collect them on the way
   back" fits forward-only traversal.

Bone supply is therefore a **guaranteed floor**, not a weighted roll — 1-in-3
odds against a weighted bone drop is a taming economy that fails silently.
`VISION.md` §3.7.4 already demanded this; wolves are what make it load-bearing.

### 7.5 Closed by earlier decisions

- ~~**Do rewards scale with affix count?**~~ Dissolved by §5.0 — if every affix
  pays its own way, there is nothing to scale and no threshold to park below.
- ~~**Weekly rotation seed source.**~~ Dissolved by §4 — the rotation is cut, so
  there is no wall-clock seed to own.
- ~~**Affix threshold spacing.**~~ Decided in §4.1 — **5 / 11 / 17**.
