# Thingy Framework Rollout Plan

Server-side Fabric framework providing stable, namespaced, server-authoritative
identity for custom objects (items, blocks, stations, entities) represented as
vanilla carriers. Vanilla Minecraft objects are carriers; Thingy objects are
the semantic objects.

This plan is the product of a full codebase audit, an external review, and a
round of corrections to that review. Every finding in it is grounded in a real
file. Do not treat the phases as suggestions; the ordering is load-bearing and
several sequences have hard dependencies that are called out below.

## Core design principle

> Minecraft has carriers. Thingy has identities.
>
> A slimeball can represent a Slime Bomb. A crafting table can represent an
> Alchemy Table. A snowball can represent a Slime Bomb projectile.
>
> Thingy provides the identity and lifecycle layer that connects those
> representations together.
>
> Your mod defines what something is. Thingy handles how Minecraft carries it.

## Namespace rule

The framework's name is never the identity namespace of content it hosts.

A Pocket Crafter is a wondrous item that runs on Thingy. If Thingy is ever
replaced, `thingy:pocket_workbench` is a permanent tombstone in every world.

- Content stamps with its own namespace: `wondrous:`, `kamutotems:`,
  `spiritwolves:`, etc.
- `thingy:` is reserved for framework metadata only (SavedData ids, attribute
  modifier ids for framework-internal state, command names).
- Duplicate namespace registration fails fast and loud at init, naming both
  mod ids. Never silent last-write-wins.

## Shape preservation constraint (Phase 1)

`VirtualTag`'s reader for the `wondrous` namespace keeps `WondrousTag`'s exact
shape: key `"wondrous"`, value a bare string (`WondrousTag.java:33-47`). A live
stack is byte-identical before and after. There is nothing to upgrade.

This gives the rollback story: a failed Phase 1 swap is recovered by putting
the wondrous jar back, because stacks are unchanged. This property is what
makes the swap test safe to run in production.

No legacy alias machinery. No `/thingy migrate` command. Lazy upgrade on touch
only returns if some future namespace changes shape.

## Wondrous freeze constraint

No file in `A:/MrPinoys Mods/wondrous/` is modified during this plan. Thingy is
built fresh alongside it, copying patterns and code where useful. Wondrous is
archived only after Thingy has proven itself and all consumers have migrated.

The freeze is workable at source level but not at runtime level. During
Phases 1 through 3, both jars cannot be loaded together: both register the same
callbacks on the same stacks, opening two menus, recording two placements,
running two sweeps on the same SavedData. Because wondrous is frozen, you
cannot add a "stand down" guard to it.

Exit criteria are swap tests (one jar at a time across restarts), not
coexistence tests.

## What we are not building (and why)

- **Validation/repair/GC machinery (spec sections 38, 39, 41):** Not built up
  front for any layer. The repair strategies in the spec are guesses until
  there are real failure modes to validate them against. The one concrete
  failure mode found (kamutotems boss identity lost on crash restart) is fixed
  by persistence alone in Phase 0, not by repair.
- **VirtualProjectile layer:** Cut. BoomerangBall is one projectile with a
  static in-flight list. Generalizing from n=1 is the exact mistake this plan
  avoids for validation/repair. Port BoomerangBall as an ordinary Thingy item
  in Phase 1. Revisit when a second projectile exists.
- **SuiteItems collapse:** Rejected. `wondrous-api` is code-level lookup for
  one mod. `SuiteItems` is data-level discovery for mods that compile against
  nothing. They are different layers, not competing abstractions. Keep both.
  Thingy generates the suite_items JSON from its registry so the two cannot
  drift.
- **Behaviors module (spec section 37):** Deferred. Optional composable
  behaviors are nice-to-have, not foundational.
- **Resource pack integration (spec section 49):** Out of scope. Rendering is
  a separate optional layer.
- **Forgeability claim:** Dropped from motivation. `custom_data` is not
  anvil-writable; an anvil writes `CUSTOM_NAME`, never `custom_data` and never
  `ITEM_NAME`. The chatdonkey Burr check reads `ITEM_NAME`, which an anvil
  cannot forge. The real vector is creative-mode players and lax `/give`
  access, which is a permissions problem. The one cheap check worth folding
  into Phase 1: `VirtualItems.byId(id)` exists and its registered carrier
  equals `stack.getItem()`. Five lines, and it is the only validation with a
  real failure mode.

---

## Phase 0: Fix the BossHost crash-orphan bug in kamutotems

No framework. A targeted fix for the one validated failure mode in the whole
plan, done now so it is not held hostage to seven phases of framework.

### Problem

`BossHost` tracks bosses in in-memory `HashMap`s keyed by entity UUID
(`BossHost.java:63-66`). On orderly shutdown, `despawnAndRefundAll` runs before
`save` (`BossHost.java:212-215`), so no orphans exist. But on a crash, the
entity persists with the `kamutotems_boss` tag and no in-memory record. On
reload, `onEntityLoad` finds it and strips the tag, silently demoting the boss
to a vanilla mob (`BossHost.java:266-273`). Boss identity, loot table, aura,
and boss bar are all lost.

### Fix

Add a `SavedData` of boss records (entity UUID, owner, tier, roll, aura) to
kamutotems. On `ENTITY_LOAD`, reattach from the record instead of stripping
the tag.

### Critical constraint

Phase 0's `SavedData` is shaped as the format Thingy's `VirtualEntity`
persistence will later adopt verbatim, not as a throwaway. Phase 7 absorbs it
by reference, not by migration. No second world-data migration is created.

### Exit criteria

- A boss present at crash restart reattaches with its identity, bar, and aura
  intact.
- The `onEntityLoad` tag-strip path is deleted.
- The record format is documented in `thingy/docs/DISCOVERIES.md` as the
  Phase 7 target shape.

---

## Phase 1: Thingy VirtualItem layer + full Wondrous item parity

Build the framework's item layer and prove it against Wondrous as the flagship
consumer. Wondrous is frozen; Thingy is built fresh.

### Scope

29 items across 17 behavior files, built from `WondrousMod.onInitialize`'s
register list (lines 34-54), not from a directory listing. SmashyMortar is
dead code that collides with Mortar on `ID = "smashy_mortar"` and is never
registered; skip it.

### thingy/api module

- `VirtualItem`: interface with `id()`, `displayName()`, `createStack()`,
  `createStack(int)`.
- `VirtualItems`: registry lookup, `Optional<VirtualItems> get()`, `byId()`,
  `all()`, `isVirtual()`, `idOf()`.
- `VirtualTag`: generalized identity stamp/read with registered namespaces and
  per-namespace reader strategies. The reader strategy is registered alongside
  the namespace: "value is a bare string id", "value is a compound with an id
  field", "value is a compound, presence alone means this single item". This
  is the most under-specified part of the design and it is front-loaded in
  Phase 5 against the genuinely awkward shapes. Phase 1 only needs the
  bare-string reader for wondrous.
- `Give`: `giveOrDrop` utility, generalized from `WondrousGive`.

### thingy/fabric module

- `Definitions`: item catalogue as data, copied/adapted from wondrous.
- `ItemRegistry`: implements `VirtualItems`.
- `Stations`: right-click dispatch for menu items.
- All 29 item behavior files, including `DisenchantMenu` and `SmelterMenu`
  (these are item menus opened from the hand, not block-backed; they move in
  Phase 1, not Phase 3).
- `BoomerangBall`: ported as an ordinary Thingy item (tagged slime ball plus a
  tick loop). No projectile abstraction.
- `Aura`: promoted to a framework service. One shared per-player polling loop,
  namespaced ids instead of bare strings. Every call site in `AuraEffects`,
  `FlyingBoots`, and `Restock` uses namespaced ids.
- `RecipeGuard`: per-namespace opt-in at registration. Wondrous opts in
  (current behavior preserved exactly). No "any tagged item" default; that
  would silently make kamutotems totems and spiritwolves stones uncraftable
  as ingredients when those mods migrate.
- `WondrousCommands` equivalent as `/thingy` with `give` and `list`
  subcommands, id suggestions from the registry.
- `FlyingBoots`: respawn re-evaluation hook carried across
  (`FlyingBoots.java:49-51`). The "no player flying during the swap" criterion
  covers the migration moment; the hook covers every moment after it.

### Hard constraints

1. **Shape preservation:** `VirtualTag`'s reader for the `wondrous` namespace
   keeps `WondrousTag`'s exact shape. Live stacks are byte-identical.
2. **Attribute modifier ids stay `wondrous:`**
   (`Definitions.java:377`). Changing to `thingy:` produces double-buffs
   because `withModifierAdded` does not deduplicate by value.
3. **suite_items datapack (30 files) generated from the registry**, not
   hand-copied. No drift window between Phase 1 and Phase 2.
4. **Maven-local publish plumbing** identical to wondrous-api's arrangement.
   Every consumer needs `FabricLoader.isModLoaded("thingy")` before the first
   API class reference (the `NoClassDefFoundError`-escapes-`catch(Exception)`
   trap documented in `WondrousShop`).
5. **custom_data sibling key preservation contract** documented. CarryGlove
   writes `BLOCK_KEY`, `STATE_KEY`, `STORED_KEY` as sibling keys in the same
   `custom_data` compound the identity lives in. A future GC pass must not
   touch them.

### Exit criteria

- Swap test: one jar at a time, not coexistence. Every wondrous item behavior
  reproduced. No player flying during the swap.
- Failed swap is recoverable by putting the wondrous jar back, because stacks
  are unchanged.
- `./gradlew.bat :fabric:compileJava` passes.
- Documentation deliverables written (see Documentation section below).

---

## Phase 2: CobbleEconomy integration

Prove an external mod can consume Thingy.

### Scope

- `WondrousShop` switches from `wondrous-api` to `thingy-api` (compile-only
  dependency change).
- SuiteItems stays as-is. Thingy generates the suite_items JSON from its
  registry so the two cannot drift. Do not teach Thingy to read JSON; keep the
  authoring path code-driven and the interop path data-driven.
- Shop-listing validation pass: every listing resolved against the Thingy
  registry, unresolvable ids reported. Note that shop config already tolerates
  broken ids (three cut items are still listed in `shop.json`), so "shop still
  works" is not a parity signal.
- Explicit decision on the `wondrous:` config prefix in shop.json: keep
  accepting it forever, or migrate. Pick one and write it down.

### Exit criteria

- `WondrousShop` compiles and runs against `thingy-api`.
- Every shop listing resolves or is reported as unresolvable.
- No `wondrous-api` import remains in cobbleeconomy.

---

## Phase 3: Thingy VirtualBlock/VirtualStation layer

Build the block/station persistence layer and migrate all WondrousState
consumers as one unit.

### Scope

One indivisible migration unit: `CraftStation`, `LazySprinkler`, `LinkWand`,
`CarryGlove` (item identity only, capture logic stays), `StationMenu`,
`SprinklerMenu`. All four behavior files touch `WondrousState`; you cannot
migrate two-thirds of a `SavedData`.

`WondrousState` holds three maps in one `SavedData` under one id
(`WondrousState.java:40-42`): `craftingStations`, `sprinklers`, `links`.
LinkWand owns the third map. CarryGlove mutates link state
(`CarryGlove.java:128`). They move together.

### Implementation notes

- Port `WondrousState`'s entry-list codec shape and its NbtOps comment
  verbatim (`WondrousState.java:56-60`). `Codec.unboundedMap` silently loses
  the whole file when keys are not bare strings. A fresh clean-sheet
  `ThingyState` written by someone who read "study the patterns" will reach
  for `unboundedMap` and lose worlds.
- `CarryGlove` does not fit `VirtualBlock`. Its captured block has no virtual
  identity; it is a vanilla chest with vanilla NBT. The only virtual object in
  play is the glove. Keep its capture logic Thingy-specific. Do not build a
  "dynamic virtual block" concept for one tool.

### World-data migration (named deliverable)

Placed stations, sprinklers, and links live in a `SavedData` blob keyed
`wondrous:wondrous_state`, not in `custom_data`. An alias read on ItemStacks
never touches it. Without an explicit one-time "read the old SavedData, write
the new" step, archiving wondrous turns every placed station into an ordinary
crafting table (contents gone) and drops every hopper link.

This is the largest concrete gap in the plan. It must be a named deliverable
with a dry run against a copy of the production world.

### Exit criteria

- Migration step run against a copy of the production world. Every placed
  station, sprinkler, and link survives.
- `ThingyState` compiles and persists across restart.
- Chunk-aware sweep functional (port `WondrousState`'s `hasChunkAt` checks).

---

## Phase 4: Deprecate Wondrous

Archive the wondrous jar once all functionality runs on Thingy.

### Scope

- Gate on the world-data migration having run against a copy of the
  production world, not just on item parity.
- Archive the wondrous jar.
- Document the `/wondrous` to `/thingy` command change for operators. These
  commands are in admin muscle memory and possibly in console scripts.
- The 30 suite_items datapack files now ship from the Thingy jar. Archiving
  wondrous must not silently remove them from the merged datapack view.

### Exit criteria

- Wondrous jar removed from the server. All functionality intact via Thingy.
- No `wondrous:` import or dependency remains in any active mod.

---

## Phase 5: Migrate KamuTotems and Spiritwolves items

Prove a second and third external mod integrate cleanly. This is where the
abstraction actually gets tested; wondrous was the easy consumer because Thingy
was copied from it.

### Per-namespace reader strategy (front-loaded here)

Two shapes to handle, designed and validated in this phase:

- **kamutotems:** nested compound (`{kamutotems: {totem: true, kami: "id",
  tier: 1}}`). The id is a field inside the compound.
- **spiritwolves:** compound with mutable state, no id field
  (`{spiritwolves: {bound: bool}}`). Presence alone means "this is a Spirit
  Stone." This is the harder case and the one that validates the reader
  strategy is genuinely general, not just a copy of wondrous' bare-string
  shape.

Without this, Phase 5 either breaks every existing totem or forces a re-stamp
of the live world.

### Scope

- `Totem`, `QuestScroll`, `BossStone`, `Sigil` adopt `VirtualItem.is()` with
  `kamutotems:` namespace.
- `SpiritStone` adopts `VirtualItem.is()` with `spiritwolves:` namespace. The
  `getCompound(KEY).orElse(null)` false-positive workaround at
  `SpiritStone.java:78-81` is deleted.
- Delete the duplicated `tag()` helper across kamutotems' four files
  (`Totem.java:369-378`, `QuestScroll.java:79-88`, `BossStone.java:109-118`,
  `Sigil.java:428-437`).
- RecipeGuard: kamutotems and spiritwolves each decide for themselves whether
  virtual items are craftable as ingredients. No silent balance change ships
  inside a migration.

### Exit criteria

- Every existing kamutotems and spiritwolves item in the world is recognized
  by `VirtualItem.is()` without a re-stamp.
- The `tag()` helpers are deleted, not just unused.
- RecipeGuard opt-in decisions are explicit per namespace.

---

## Phase 6: Thingy VirtualEntity layer

Build entity persistence with `SavedData`-backed records, shaped to absorb
Phase 0's boss record format verbatim.

### Scope

- `VirtualEntity` interface and registry.
- `SavedData`-backed persistence: entity UUID, virtual identity, owner,
  state. Shaped to absorb Phase 0's format by reference, not by migration.
- Lifecycle hooks: spawn, load, unload, damage, death, remove.

### Critical design questions (must be answered in this phase)

1. **BY_PLAYER invariant:** `BossHost` maintains a one-boss-per-player
   invariant via `BY_PLAYER` map (`BossHost.java:416-419`). If Phase 6
   restores `BY_ENTITY` but not `BY_PLAYER`, a player logs in after a crash
   and can summon a second boss while the first is still alive. Specify which
   side wins on load when `boss_state.json` and the Thingy entity record
   disagree.
2. **Shutdown ordering:** Today the shutdown path writes `boss_state.json` and
   despawns entities in sequence with no coordination. Specify the ordering.
3. **Owner-relative metadata:** `FREE_CLAIMS` and `SIGIL_COUNTERS` are
   daily-reset player economy, not entity identity. They stay in
   `boss_state.json`, not in the entity record.

### Exit criteria

- A virtual entity survives crash restart with identity and state intact.
- The BY_PLAYER invariant is preserved across restart.
- Phase 0's record format is absorbed without migration.

---

## Phase 7: Migrate KamuTotems entities

`Boss`/`BossHost` become `VirtualEntity` consumers. The Phase 0 `SavedData` is
absorbed by reference.

### Scope

- `Boss` is a vanilla mob carrying a rolled construct of kamu and a boss bar
  (`Boss.java:28-31`). The vanilla mob is the carrier; the boss identity is
  the virtual entity.
- `BossHost`'s in-memory `HashMap` tracking is replaced by persistent virtual
  entity records.
- The `onEntityLoad` tag-strip-on-orphan logic (`BossHost.java:266-273`) is
  deleted, replaced by reattachment from the persistent record.
- pocketdungeons `BossContent` and wayfarers `Spawns`/`Hostile` can follow if
  desired, but are not required to prove the layer.

### Exit criteria

- A boss present at crash restart reattaches with identity, bar, and aura.
- The in-memory `BY_ENTITY` and `BY_PLAYER` maps are backed by persistence.
- `BossHost.onEntityLoad` no longer strips tags.

---

## Documentation

Each phase produces documentation as an explicit exit item. A phase is not
complete until its documentation deliverable is written. This is what prevents
the next migration from reinventing solutions.

### Living documents (all under `thingy/docs/`)

#### DISCOVERIES.md

Verified API findings, traps, and gotchas. Same shape as
`pocketdungeons/docs/DISCOVERIES.md`. Every hard-won lesson goes here so the
next mod migration does not re-derive it.

Seeded in Phase 0 with findings already in hand:

1. The NbtOps codec bug: `Codec.unboundedMap` silently loses the whole file
   when keys are not bare strings. Port the entry-list codec shape and its
   comment verbatim from `WondrousState.java:56-60`.
2. `Inventory.add(ItemStack)` returns "did I move any" and mutates the stack.
   `Give.giveOrDrop` depends on checking `stack.isEmpty()` afterwards.
3. Attribute modifier ids are stamped into live stacks with
   `Identifier.fromNamespaceAndPath`. Changing the namespace produces
   double-buffs because `withModifierAdded` does not deduplicate by value.
4. `custom_data` is not anvil-writable. The real forging vector is creative
   mode and lax `/give`. An anvil writes `CUSTOM_NAME`, never `custom_data`
   and never `ITEM_NAME`.
5. The `NoClassDefFoundError`-escapes-`catch(Exception)` trap: a compile-only
   API class touched when the mod is absent kills startup. Every consumer
   needs `FabricLoader.isModLoaded` before the first API class reference.
6. Coexistence is not survivable: two jars registering the same callbacks on
   the same stacks opens two menus, records two placements, runs two sweeps.
   Swap test, not side-by-side.

Each phase appends its own discoveries as they are found.

#### MIGRATION-PLAYBOOK.md

The step-by-step recipe for migrating a mod onto Thingy. Started after Phase 1
(wondrous is the first migration, even though it is a rebuild). Each subsequent
migration appends its section, noting where it diverged and why.

Structure per migration:

- Pre-flight: what to inventory (register calls, custom_data shapes, attribute
  modifier ids, suite_items files, SavedData blobs, command surface).
- Identity: what namespace, what reader strategy shape, whether shape
  preservation is needed.
- RecipeGuard: opt-in or opt-out decision.
- Menus: which are item menus (move with items) vs block-backed (move with
  blocks).
- World data: what SavedData needs migration, what the migration step looks
  like.
- Exit criterion: what swap test proves parity.
- What broke and how it was fixed.

#### API.md

The framework's developer-facing API documentation. Not javadoc (that lives in
source) but the guide a new mod author reads to use Thingy: how to register a
VirtualItem, how identity works, how the reader strategy works, how
RecipeGuard opt-in works, how the Aura service works, how block/station
persistence works, how entity persistence works.

Started in Phase 1 with the item layer. Phase 3 appends the block/station
layer. Phase 6 appends the entity layer.

#### AGENTS.md

Project rules for the Thingy mod itself: build commands, test commands,
verification steps, the "compile after every task" rule, the "never invent a
Minecraft API" rule. Same shape as wondrous' `HANDOFF.md` rules but scoped to
Thingy. What an agent working on Thingy reads first.

### Workspace-root convention

`CLAUDE.md` (workspace root) is appended once, in Phase 1, with cross-cutting
conventions that apply to any mod consuming Thingy:

- The `FabricLoader.isModLoaded("thingy")` guard rule before first API class
  reference.
- The namespace registration rule: fail fast and loud on duplicate, never
  silent last-write-wins.
- The custom_data sibling key preservation contract.

### Documentation deliverables by phase

| Phase | Doc deliverable |
|---|---|
| 0 | `DISCOVERIES.md` seeded with the six findings above; boss record format documented |
| 1 | `API.md` item layer; `MIGRATION-PLAYBOOK.md` wondrous section; `AGENTS.md` project rules; `CLAUDE.md` appended with cross-cutting rules |
| 2 | `MIGRATION-PLAYBOOK.md` cobbleeconomy section; `DISCOVERIES.md` appended with shop-listing validation findings |
| 3 | `API.md` block/station layer; `MIGRATION-PLAYBOOK.md` wondrous block section; `DISCOVERIES.md` appended with WondrousState migration findings |
| 4 | `MIGRATION-PLAYBOOK.md` deprecation checklist |
| 5 | `MIGRATION-PLAYBOOK.md` kamutotems and spiritwolves sections; `DISCOVERIES.md` appended with reader strategy findings |
| 6 | `API.md` entity layer; `DISCOVERIES.md` appended with entity persistence findings |
| 7 | `MIGRATION-PLAYBOOK.md` kamutotems entity section |

---

## File inventory: what moves where

Built from `WondrousMod.onInitialize` register calls (lines 34-54), not from
a directory listing.

### Phase 1 (items)

| Wondrous file | Thingy equivalent | Notes |
|---|---|---|
| `Definitions` | `Definitions` | Item catalogue, adapted |
| `ItemRegistry` | `ItemRegistry` | Implements `VirtualItems` |
| `Stations` | `Stations` | Right-click dispatch |
| `Aura` | `Aura` | Framework service, namespaced ids |
| `AuraEffects` | `AuraEffects` | 5 items, uses Aura passes |
| `FlyingBoots` | `FlyingBoots` | Respawn hook carried across |
| `Restock` | `Restock` | Uses Aura passes |
| `PeekBox` | `PeekBox` | 1 item |
| `VoidBin` | `VoidBin` | 1 item |
| `RecipeGuard` | `RecipeGuard` | Per-namespace opt-in |
| `ChuckIt` | `ChuckIt` | 1 item |
| `TidyUp` | `TidyUp` | 1 item |
| `BigLazyHoe` | `BigLazyHoe` | 1 item |
| `Growth` | `Growth` | 1 item |
| `Mortar` | `Mortar` | 1 item |
| `AreaBreak` | `AreaBreak` | 2 items, area tools |
| `BoomerangBall` | `BoomerangBall` | Ordinary item, no projectile abstraction |
| `DisenchantMenu` | `DisenchantMenu` | Item menu, not block-backed |
| `SmelterMenu` | `SmelterMenu` | Item menu, not block-backed |
| `WondrousCommands` | `ThingyCommands` | `/thingy give`, `/thingy list` |
| `WondrousMod` | `ThingyMod` | Entrypoint |
| `Chime` | `Chime` | Shared utility |
| `Visuals` | `Visuals` | Shared utility |
| `Nearby` | `Nearby` | Shared utility |
| `Gate` | `Gate` | Shared utility |
| SmashyMortar | (skipped) | Dead code, collides with Mortar |

### Phase 3 (blocks/stations)

| Wondrous file | Thingy equivalent | Notes |
|---|---|---|
| `WondrousState` | `ThingyState` | SavedData, entry-list codec ported verbatim |
| `CraftStation` | `CraftStation` | Placeable, grid state persisted |
| `LazySprinkler` | `LazySprinkler` | Placeable, passive effects |
| `LinkWand` | `LinkWand` | Owns WondrousState.links map |
| `CarryGlove` | `CarryGlove` | Item identity only, capture logic stays |
| `StationMenu` | `StationMenu` | Block-backed menu |
| `SprinklerMenu` | `SprinklerMenu` | Block-backed menu |

### Phase 4 (archived)

The wondrous jar is removed. The 30 suite_items datapack files ship from the
Thingy jar.

---

## Build and publish plumbing

Every mod in this workspace is its own Gradle build with its own wrapper.
cobbleeconomy consumes the API through maven-local coordinates:

```
compileOnly("wondrous:wondrous-api:0.1.0")
```

Thingy needs the identical arrangement:

- `thingy/api/build.gradle.kts`: `minecraft()` only, no Fabric API,
  `java-library`, `maven-publish`. Same shape as `wondrous/api/build.gradle.kts`.
- `thingy/fabric/build.gradle.kts`: Loom, `include(project(":api"))` (shaded
  in), Fabric API. Same shape as `wondrous/fabric/build.gradle.kts`.
- `./gradlew.bat :api:publishToMavenLocal` publishes `thingy:thingy-api`.
- Consumers: `compileOnly("thingy:thingy-api:<version>")`.

### The isModLoaded guard

A compile-only API class touched when the mod is absent throws
`NoClassDefFoundError`, which is an `Error`, escapes `catch (Exception)`, and
kills startup. Every Thingy consumer needs:

```java
if (!FabricLoader.getInstance().isModLoaded("thingy")) {
    return;
}
// safe to reference thingy.api.* classes now
```

before the first API class reference. This is documented in `CLAUDE.md` as a
cross-cutting rule.
