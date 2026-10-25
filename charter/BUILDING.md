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

## Found in the M0 spike

Two protection Mixin targets in PLAN.md §5.6 were renamed in 26.2:
`FlowingFluid.spreadTo` (not `canSpreadTo`) and `FarmlandBlock.fallOn` (the class
is not `FarmBlock`). Entity type constants live in `EntityTypes`, not
`EntityType`. A Mixin package may hold only Mixins: put shared interfaces elsewhere.
