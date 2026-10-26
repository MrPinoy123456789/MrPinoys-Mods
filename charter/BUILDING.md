# Building Charter

For whoever builds the next milestone. The plan is `PLAN.md`; the design is
`DESIGN.md`. This file is the conventions M0 and M1 set, so parallel milestones
fit together.

## Run everything

```bash
./gradlew build
```

That compiles, runs `coreTest` (pure JDK), `fabricTest` (plain Java with Gson)
and `runGameTest` (a real dedicated server), then copies the jar to `../dist/`.
Each suite prints its count. The gametest names are in
`fabric/build/gametest-report.xml`; the vanilla `minecraft:always_pass` test is
always one of them, so our count is the total minus one.

## The rules model (`core`)

- **Every consequential change is an `Action`** run by `ActionEngine.run(actor,
  action)`. One record per action, in its feature's package (`charter.<feature>.actions`).
  No central switch.
- An action says which organization's power it uses (`actingFor`), which `Power`
  it needs, what scope it needs (`need`, for spending), and whether only a vote may
  authorize it (`reserved`). The engine checks authority; the action checks its
  own preconditions in `check` (no mutation) and changes state in `apply`.
- **Authority is never stored on a player.** `AuthorityResolver` walks grant chains
  at the moment of use. Grants go to a player, an office (whoever holds it now) or
  an organization (usable by its vote, or by anyone holding the same power inside it).
- **Votes:** `engine.propose(player, action)` opens a vote of `actingFor`; a passed
  vote runs the action again as `Actor.Vote`, so it is re-checked. `engine.tick()`
  closes votes and handles absent Lords. Elections fill offices.
- **Refusals** are `Reason(key, text)`. Keep a feature's reasons beside its
  actions (see `OrgReasons`). Keys never change; text can.
- **Events** implement `charter.history.HistoryEvent`. Each feature keeps its own
  records in a `<Feature>Events` class. The interface is not sealed on purpose.
- **Ids** come from `state.ids`, never `UUID.randomUUID()`, so tests are
  deterministic and saved ids never repeat.
- **Time:** wall time comes from the engine's `Clock`; siege time from
  `state.siegeClock`. Never read the system clock in `core`.
- `Knobs` holds every tuning value with its default. Add a knob there, with a
  comment, when a feature needs one.

## Shared files (one line each, merged by the integrator)

- `core/src/test/java/charter/CoreSuite.java`: add `YourTest.run();`
- `fabric/src/test/java/charter/mc/FabricSuite.java`: same, for fabric plain tests
- `fabric/src/gametest/resources/fabric.mod.json`: list every gametest class, or
  it silently never runs
- `charter.grant.Power`: add a power only when a feature needs one, and say so
- `CharterState`: add your records in a block of their own
- `CharterMod.onInitialize`: one `register` call per feature

## Tests

Core tests are `main()` style: a class with a static `run()` and checks through
`charter.Check`. Use `charter.Fixture` for a world with a manual clock; it drives
everything through the real engine. `Fixture.Use` is a test action that needs any
power, for checking authority without building the feature. Tests must prove
behaviour: run the real path and assert an observable result.

## The siege (`charter.siege`, M5)

- `SiegeSim.step(realMillis)` scales by `timeScale`; `advance(siegeMillis)` runs
  passes of at most a siege minute. The fabric tick driver calls `step` every
  `simStepTicks`; admin `time advance` calls `advance`. Both also tick governance.
- All siege state is `state.siege` (`SiegeBook`). Granaries and camp stores are
  `Store`s whose `provisions` mirror the real chests.
- `SiegeRules` answers every question (which camps count, which sides are closed,
  whether a claim is protected right now, who holds a keep). Protection Mixins and
  events should call `protectedClaim`, not reimplement it.
- `Supply.enter` is what crossing detection reports (players and carts);
  `DepositFood` is the player action; hoppers unloading carts call
  `Supply.deposit` with the cart as carrier.
- Presence and prisoners come in through `SiegeInputs`, as counts.
- `ChooseFall` is the besieger's raze or occupy; the sim razes when time runs out.
- New-faction protection runs on wall time, so a time-scaled playtest never
  shortens a new faction's grace. Everything else in the siege is siege time.

## Found in the M0 spike

Two protection Mixin targets in PLAN.md §5.6 were renamed in 26.2:
`FlowingFluid.spreadTo` (not `canSpreadTo`) and `FarmlandBlock.fallOn` (the class
is not `FarmBlock`). Entity type constants live in `EntityTypes`, not
`EntityType`. A Mixin package may hold only Mixins: put shared interfaces elsewhere.

**Hoppers and the granary.** M8's `HopperGate` (Mixin 8) offers every hopper and
dropper insert to registered interceptors. `GranaryHopperGate` refuses every insert
into a granary or camp store while its site is under siege, because the mirror
reads the chests each step and would take hopper-fed food as provisions. M10 adds
its exception there: food unloaded from a cart that came in through an open side,
up to that cart's allowance. Test new gate rules by calling
`HopperBlockEntity.addItem` directly in one tick: the siege clock is shared by
every gametest, so a besieged world must not wait across ticks.

**Carts only move in chunks that tick entities.** The cart spike failed at random
until its rails stayed near the test area and the chunks along them were
force-loaded: a cart in a loaded but not entity-ticking chunk simply stops. M10
must not assume a supply cart keeps rolling through chunks no player is near;
that is the unloaded-chunk case in PLAN.md §4.5 and risk R3. Gametests that move
entities across chunk borders should force-load the chunks they use and release
them at the end.

## Saving, ticking and commands (`charter.mc`, M7)

- **The store** (`charter.mc.store`): `<world>/charter/state.json` (one JSON snapshot,
  written to `.tmp`, forced, moved; the previous one kept as `.bak`; an hourly copy
  in `backups/`, 24 kept) and `history.jsonl` (every history entry, one line each,
  append only, read back into `state.history` on start). An unreadable `state.json`
  is kept as `state.json.broken-<time>` and the load falls back to `.bak`, then the
  newest backup; if nothing reads, the server refuses to start.
- **Plugging in state:** `state.json` is a `formatVersion` plus one block per
  `StateBlock` key. A feature that adds records to `CharterState` registers a block
  (`Persistence.block(CoreBlocks.block("contracts", write, read))`) from its own
  register call; it never edits `CoreBlocks`. A block missing from an old file is
  simply not read; a block no jar claims is written back unchanged.
- **One register call:** `Persistence.register()` registers every feature (core
  types, then `ContractTypes` for M6). Add a new feature's types there, so the
  server, `RegistryTest` and `StoreTest` can never disagree about what is
  registered. A feature's own JSON adapters go through `Persistence.adapter`
  (M6's sealed `Term` uses `SealedAdapter`).
- **Plugging in types:** every `Action` a vote can hold and every `HistoryEvent`
  needs a store name: `Persistence.ACTIONS.register("feature.verb", Type.class)`
  and `Persistence.EVENTS.registerNested(FeatureEvents.class, HistoryEvent.class)`.
  `RegistryTest` scans core's source and fails on any type without a name. Each
  action also needs a `motion.<name>` line in `en_us.json` (its record fields are
  the placeholders), checked by the same test.
- **Repair** (`Repair`): runs after every load and on `/charter admin repair`. A
  block drops records that point at nothing; `Repair.run` handles what spans
  blocks. Each repair is a `StoreEvents.Repaired` recorded as admin by `repair`.
- **Runtime:** `CharterRuntime` wires state, engine, sim, store, history file, siege
  log, `ClaimIndex` and notices with no Minecraft types, so plain tests run it as
  the server does. `CharterServer` owns the live one; get it with
  `CharterServer.runtime()`. The sim steps every `simStepTicks` by real time since
  its last step (at most a minute), so the siege clock never runs while the server
  is down.
- **Text:** every player string is a key in `data/charter/lang/en_us.json`
  (copied to `config/charter/` on first boot), read with `Text.get(key, "name",
  value, ...)`. A core refusal can be reworded with a `reason.<key>` line.
- **Links:** pages run `/charter act <token>`; `Chat.act(player, label, hover, run)`
  issues a one-shot token bound to that player. Gametests click a link with
  `CharterCommands.tokens().newest(uuid, label)`.
- **Players:** commands accept a name or a UUID (mock players share a name).

## The granary and camp store (`charter.mc.granary`, M9)

- **Chests are the store.** `GranaryBook` (saved as the `granary` block) records which
  chest positions belong to which keep or camp, and the removals queued for a chunk
  that was not loaded. Core's `Store.provisions` is only a mirror of those chests.
- **One wrapper around every step.** `Granaries.around(Runnable)` reads loaded chests into
  the mirror, runs the sim step, then takes from the real items exactly what core removed
  (eaten or spoiled), or queues it when the chunk is not loaded. The tick driver and
  `/charter admin time advance` both go through it; anything new that calls `sim.step` or
  `sim.advance` must too.
- **Load reconciliation** runs from the chunk-load event and again when a chest is opened,
  so the queue is applied before anyone sees the items. Gametests cannot unload their
  chunk: they set `GranaryService.pretendUnloaded` and call `onChunkLoad` themselves.
- **Linking** is by placement (a member's chest in the keep core chunk; a barrel or chest
  within 3 blocks of a banner in a camp's banner chunk) or `/charter admin granary set <pos>`.
  M8's nexus and banner code may call `GranaryService.link(core, level, pos, needBanner, true)`.
- **The screen** (`GranaryScreen`) shows the real slots. Every tick the chest is compared
  with the mirror; extra food is a deposit through `DepositFood` and what core refuses is
  handed back. Outsiders cannot open a store while its keep stands; after the fall anyone can.
- **Food values** come from `config/charter/food.json` (shelf class per item, nourishment
  from the vanilla food component unless the file overrides it), filled into the sim's own
  `FoodTable` when the server starts.
- **Gametest worlds share one sim.** A test that advances time moves every other test's
  keeps too, so a world that must live across ticks has no camp; the world and Charter state
  also persist between runs, so each run uses its own block of chunks.

## Supply lines in the world (`charter.mc.supply`, M10)

- `SupplyLines` observes every online player each tick and every chest or hopper
  cart and chest boat near a site under siege every `cartScanTicks`, through one
  `Crossings` tracker. `Crossings.observe` filters movement (edge-adjacent chunk,
  within `teleportDetectBlocks`, not in transit); core's `Supply.enter` and
  `Supply.leave` decide the rest. A pearl or chorus fruit puts a player in transit
  for `TRANSIT_TICKS`.
- Carriers are `player:<uuid>` and `cart:<uuid>`. Cart allowances together are a
  site's cart pool (`Supply.cartPool`): a hopper deposits as `Supply.HOPPER` and
  draws on the pool only; a player draws on their own allowance, then the pool.
- `GranaryHopperGate` lets a single hopper item into a besieged granary only when
  the pool covers it and it fits, debiting the pool; unloads are recorded as one
  `FoodDeposited` per scan, not one per item.
- `SupplyLines.enderChestBlocked` refuses ender chests in a besieged keep or a cut
  off camp. `/charter admin supply show` lists a site's state, open sides, cart pool
  and live allowances.
- Gametests drive `SupplyLines.observe` and `observeCart` directly, in one tick,
  after moving a real player or cart.

## The siege in the world (`charter.mc.war`, M11)

- `SiegeWorld` feeds presence (`WorldInputs`, counted before each sim step) and
  prisoners (M6's `JusticeInputs`) into the sim, offers the besieger raze or occupy
  as two chat links when a keep falls, spills plunder as real items at the nexus,
  and breaks the nexus on a raze. If the keep's chunk is not loaded, the physical
  part waits for it to load (not saved across a restart).
- Siege alerts go to the whole faction, subordinates included, as a message, a
  title and a quiet bell; offline members get them through `Mail` on join.
- `Protection.holderOf` gives control of an occupied keep's nine chunks to the
  occupier.

## Gametests that move the siege clock

The siege clock is shared by every gametest. **Any gametest that advances it** (a
`time advance` command, `besiege()`, a camp raising) **runs in its own batch:**
`@GameTest(environment = "charter-gametest:long_siege")`. Batches run one after
another, so a long siege cannot age a test that is waiting across ticks in the
default batch. Every gametest run also starts from a fresh world (the build deletes
`build/run/gameTest/world` first), and tests clear a chest's spot before placing it,
so nothing an earlier test left behind leaks into a later one.
