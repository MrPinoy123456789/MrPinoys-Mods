# Situations and Bags: parallel implementation plan

> Authoritative scope for M45 through M59, the milestone round that builds
> `SITUATIONS_SPEC.md`. Written for a fleet of implementation agents working
> concurrently. Companion to `HANDOFF-SPEC.md` (how each milestone's handoff is
> written) and `ROADMAP.md` (order, not status).
>
> Revision 2, against the spec that adds section 12 (the floor loop), 6.5 and
> 6.6 (floor shape and the root-distance invariant), 12.7 (door preview), the
> `access` field, and the item-return rule.
>
> Status: plan. M45 and after are in flight.

---

## 0. What changed in revision 2, and what it costs

Revision 1 planned a spec that changed what is inside a cell. The spec now also
changes what a run *is*: a continuous loop of floors, a safe room that despawns
while you are in a dungeon, a staging room you have to find, and a preview
window you look through before committing. That is a second body of work roughly
the size of the first, and it lands almost entirely in the four files the mod
guards most closely: `RunLifecycle`, `Instances`, `RitualListener`, `RoomBuilder`.

Four consequences for the schedule:

1. **The catalogue and the loop are independent and should run concurrently.**
   Templates touch `RoomTemplateGenerator`'s family files and datapack JSON.
   The loop touches lifecycle. They meet only at the staging room template.
2. **The loop itself is a serial spine.** Split, then preview, then chaining.
   Three milestones that all want `RitualListener` and `RunLifecycle`, and no
   seam removes that: they are edits to the same state machine, not to
   neighbouring functions. Planning them as parallel would be a lie.
3. **The selector milestone got harder.** 6.2's "requires intersects available"
   became 6.6's "requires is a subset of available at strictly lower BFS depth",
   with backtracking search over the whole graph. It also now needs BFS depths
   exposed on `DungeonShape` and a bigger, mazier floor from
   `LayoutGraphGenerator`. Still pure JDK, still the best-tested milestone in
   the round, but it is now two milestones (M47 and M54), not one.
4. **The quiet cell is gone.** Spec 5.4 replaces it with the staging room.
   Revision 1's M53 loses that item.

Peak concurrency is six agents in wave 2. The serial floor is M45, one wave-1
session, one wave-2 session, and the three-milestone loop spine.

---

## 1. The rule that makes this work

> **An agent may write only the files its milestone owns.** If it needs a change
> in a file another milestone owns, it stops and reports, rather than making the
> change.

The ownership matrix in section 7 is the contract. It is enforced by scope, not
by tooling, so every handoff repeats its own row verbatim. A milestone that
discovers it needs a foreign file has found a seam M45 missed, which is a
planning bug worth hearing about immediately.

Each agent works in its own git worktree off the wave's base commit. Nothing
merges mid-wave. An integrator merges the wave in the stated order, runs the
full build, and only then opens the next wave.

---

## 2. Wave 0: M45, the seam milestone

**Goal.** Create every extension point the later waves need, so that no two
concurrent agents ever open the same file. No player-visible behaviour changes.
The build is green, every existing test passes, regenerated templates are byte
identical, and a `/dungeon` run plays exactly as it does today.

This is the only milestone in the round that touches the hot files for the
catalogue tracks, and it touches each one once.

### Steps

1. **`DungeonRoomMeta` schema (spec 6.1).** Add `tier` (int, default 1),
   `provides` (string list, default empty), `requires` (string list, default
   empty), `pressure` (string, nullable), and `access` (string, default `open`,
   values `open` or `gated`) to the record, the constructor chain, and
   `fromJson`. Nothing reads them yet. Extend `DungeonRoomMetaTest` with a round
   trip per field, a defaults case, and a rejection case for an `access` value
   that is neither `open` nor `gated`. Document all five in
   `docs/INTEGRATION.md`'s `dungeon_room` table.

2. **`SituationTags` (new file).** The closed tool-tag vocabulary as constants:
   `blocks`, `water`, `lava`, `lead`, `mob`, `trial_key`, `boat`, `gold`,
   `snowballs`, `shears`, `pearl`, `wind_charge`, `milk`, `bow`, `redstone`. A
   `validate(List<String>)` that throws on an unknown tag, called from
   `DungeonRoomMeta.fromJson`, so a typo in a datapack fails at manifest load
   with the room name in the message rather than silently making a room
   unselectable. Closed vocabulary on purpose: an open one cannot be tested, and
   spec 6.3 is a test.

3. **`DungeonShape` BFS depths (spec 6.6 step 1).** `LayoutGraphGenerator`
   already runs BFS from the entrance for its reachability check. Expose that
   depth as a `Map<PlanCell, Integer> rootDistances()` on `DungeonShape`,
   computed once where the reachability check already walks the graph. M47
   consumes it; nothing else changes. Add a `LayoutGraphTest` case asserting the
   entrance is 0, every cell is present, and a loop cell takes the shorter of
   its two distances.

4. **`Situations` (new file), the content dispatcher.** A
   `Map<String, SituationHandler>` and a `SituationHandler` functional interface
   taking the same arguments `RoomContent.apply` already has. Wave 2 agents
   register into it from their own files. `RoomContent.apply` gains exactly one
   branch, ahead of the role switch: `if (Situations.apply(...)) return null;`.
   This is the only edit `RoomContent.java` receives in the entire round, and
   the reason four template agents can all ship `content` handlers without
   meeting.

5. **`RoomSpec` promotion and the spec-list seam.** Lift the private inner
   `RoomSpec` class out of `RoomTemplateGenerator` into its own package-private
   file, unchanged. Move the current body of `specs()` into
   `BaseRoomSpecs.list()`. `RoomTemplateGenerator.specs()` becomes a
   concatenation of `BaseRoomSpecs`, `TraversalSpecs`, `MechanismSpecs`,
   `KnowledgeSpecs`, `PressureSpecs`, `SpurSpecs` and `StagingSpecs`, each a new
   file returning `List.of()` for now. `anomalySpecs()` is untouched. Verify by
   regenerating templates and confirming the .nbt files are byte identical to
   the ones in the tree; if they are not, the move changed something and must be
   fixed before the wave opens.

6. **The window band, in the geometry contract rather than in `RoomSpec`.**
   Revision 2 planned a `RoomSpec.window(...)` builder for spec 4's
   readable-from-the-doorway rule. The catalogue audit killed it and the code
   agrees: cells tile at 16 and each template owns its full footprint including
   its own wall ring, so between two interiors there are two wall blocks, one
   per cell (`BedrockEnvelope`'s javadoc calls this "the neighbour's own wall
   column"). A template carving glass at its x=15 looks into the neighbour's
   stone at x=16, and no template knows its neighbour. A per-template window
   primitive compiles, looks right in isolation, and is a blank wall in play.

   The window has to be punched symmetrically by the shell stamper, the way
   doorways already are: `RoomGeometry` gains `WINDOW_MIN`, `WINDOW_MAX` and
   `WINDOW_Y` constants placing a 4 wide by 1 tall band at eye height centred on
   the existing 2 by 3 doorway lane, and `RoomBuilder` cuts the union of doorway
   and band in one pass where it already cuts the doorway. A face with no
   connected neighbour keeps its solid wall and bedrock ring. The material is a
   `dungeon_room` field, `window`, with values `bars`, `glass`, `tinted_glass`
   or `none`, defaulting to `bars`. `RoomSpec` gets no window method at all:
   authors choose the material in JSON and the shell does the placement.

   **The risk to check first:** `DoorMask` derives masks from authored openings.
   If widening the opening changes what it infers, every existing template stops
   matching and the whole room library silently breaks. Verify before touching
   the shell, and keep the band out of the range the mask reader inspects.

7. **`InventorySwap` stub and its hooks.** A new `InventorySwap` class with
   `reconcileAll(MinecraftServer)` and `reconcile(ServerPlayer)` that do nothing.
   Call `reconcileAll` from the existing `END_SERVER_TICK` lambda in
   `Instances.register()`, after `processJoinRecoveries`, and `reconcile` from
   the existing `JOIN` handler. Register
   `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` calling
   `reconcile`. Verify the event class and signature against the 26.2 jar in
   this step; the spec assumes it exists and the assumption is load bearing for
   M46. This is the only edit `Instances.java` receives before wave 3.

8. **`InstanceRecord` fields.** Add `int omen` (default 0),
   `List<String> situations` (the names a floor's cells resolved to, for the
   compass Cube recipe and the completion line), `int floorIndex` (default 0),
   and `BlockPos stagingCellOrigin` (default null). All four unused. Wave 3 is
   the only consumer, and adding them now keeps wave 3 off this file's history
   while wave 2 is running.

**Done when:** build green, all existing tests pass, regenerated templates byte
identical, `/dungeon` plays unchanged.

**Agents:** one. This milestone cannot be parallelised and must not be skipped.

---

## 3. Wave 1: four agents, disjoint

Base: M45.

### M46: Stash and swap (spec 11)

The single riskiest milestone in the round and the one that must be right first,
because it owns other people's survival inventories.

**Owns:** `InventorySwap.java`, `LostAndFound.java` (both new),
`DungeonLog.java`, `RunLifecycle.java`, `RitualListener.java`,
`src/test/java/pocketdungeons/InventorySwapTest.java`.

**Scope:** the reconciliation pass of spec 11.5 exactly as written, the 42 slot
snapshot including the cursor slot, `closeContainer` before every snapshot, the
Lost and Found ring buffer of 20 text entries per player, the untagged-item
diversion of 11.9, and the ender chest cancellation of 11.11 in
`RitualListener`'s existing `UseBlockEvent` handler.

**Two amendments to the spec, both to be applied:**

- **Storage is a sidecar, not `Entry` fields.** Spec 11.4 puts `survivalStashed`
  and `survivalBackup` on `DungeonLog.Entry`. `Entry` is already an eighteen
  field record split across `PART_A_CODEC` and `PART_B_CODEC` to stay under the
  `RecordCodecBuilder` arity limit, and every `withX` helper copies the whole
  record. A 42 stack list does not belong in a value copied on every fuel
  change. Use the M34 bounties pattern instead: a `Map<UUID, StashRecord>` field
  on `DungeonLog` with its own `PLAYER_STASH_CODEC` entry in `DungeonLog.CODEC`,
  exactly as `PLAYER_BOUNTIES_CODEC` does. The invariant, the flag and the
  semantics are unchanged; only the storage shape moves.
- **Verify `ItemStack.OPTIONAL_CODEC` under `SavedDataType`.** It is registry
  aware, and `DungeonLog.TYPE` passes a bare `Codec`. Confirm against the jar
  that `SavedDataStorage` supplies registry ops when saving. If it does not,
  fall back to storing each slot as a `CompoundTag` produced by
  `ItemStack.save`, which is what the Lost and Found log needs anyway. Settle
  this in step one, before writing the swap.

**One scope note for the loop.** Spec 12.6 says inventory carries across floors
and is delivered to the safe room only on `/dungeon exit` or the safe door. The
invariant in 11.3 is dimension-based, so it already gets this right: every floor
and both rooms are in `pocketdungeons:void`, so no swap fires between floors.
M46 needs to change nothing for the loop, but it must say so in its completion
note, because it looks like a gap and is not.

**Testing, and the honest position on the gametest.** Spec 10 step 1 calls a
Carpet fake-player gametest mandatory. This repo has no gametest infrastructure
at all: no `fabric-gametest-api`, no Carpet dependency, no gametest entrypoint.
Standing that up is its own milestone (M46B) and must not be inlined here or M46
becomes two jobs. M46 ships:

- `InventorySwapTest`, pure JDK, over a small `SlotView` abstraction the swap
  logic is written against, covering slot order, the 42 slot round trip, the
  cursor slot, the flag as deduplication (calling the swap twice is a no-op the
  second time), and the untagged-item diversion.
- A `LIVE_TEST_PASS.md` appendix listing the five scenarios of spec 10 step 1 as
  manual checks, each with the exact commands to run.

M46B converts that checklist into automation. M46 does not merge until the
manual checklist has been walked once on a live server by a human.

### M46B: Gametest harness

**Owns:** `build.gradle.kts`, `src/gametest/**` (new source set),
`fabric.mod.json`.

Stands up `fabric-gametest-api-v1` and a `fabric-gametest` entrypoint, a
`gametest` source set, and one trivial passing test to prove the wiring. Adds
the Carpet fake-player dependency only if the API check shows vanilla's own
mock-player helper cannot carry the scenarios; prefer vanilla and record the
finding in `DISCOVERIES.md`.

Also owns test-task registration for the whole round, since it is the only
milestone that opens `build.gradle.kts`: register `inventorySwapTest`,
`bagTableTest`, `graphSolvabilityTest`, `omenMathTest`, `situationTagsTest`,
`floorShapeTest`, with their `tasks.test` dependencies. The milestones that fill
those tests never touch the build file.

### M47: The root-distance solvability pass (spec 6.2, 6.3, 6.6)

**Owns:** `RoomSelector.java`, `RoomManifest.java`, `BagTags.java` (new),
`src/test/java/pocketdungeons/GraphSolvabilityTest.java`.

Pure JDK, zero Minecraft runtime, the cleanest milestone in the round to verify.
Consumes M45's `DungeonShape.rootDistances()`.

**Scope, and note where it differs from revision 1:** implement 6.6, not 6.2.
Process every cell in BFS order, not just the critical path. Maintain a
cumulative `available` seeded from the bag's tags (plus `mob` at party size two
or more, treated as depth 0). For a cell at depth n, filter candidates to those
whose `requires` is a **subset** of the union of `provides` from all cells at
depth strictly less than n. Weighted pick as today. On an empty candidate set,
**backtrack** to the previous cell in BFS order and take its next candidate,
capped by the existing `MAX_BACKTRACK_STEPS`. Only when backtracking is
exhausted does the cell fall back to a role-only pick with empty `requires`.

Also from 6.1: the `access` placement rule. At most one `gated` cell per branch,
and at least one on the shortest entrance-to-staging-room path.

The edge cases in 6.6 are the specification and each one is a test: loops take
the shorter distance, the bag is depth 0, same-depth `provides` do not count,
multiple `requires` means all of them, and `available` is a boolean set (correct
only because of the 6.4 item-return rule, which M55's audit enforces).

`RoomManifest` gains a tag-aware overload of `queryAnyRotation`; the existing
signature stays, so nothing else in the mod moves.

**The Pilgrim test is the deliverable.** `GraphSolvabilityTest` builds shapes
from `LayoutGraphGenerator` across a spread of seeds, resolves each with an
empty bag and party size one, and asserts the 6.6 invariant for every cell in
the graph, spurs included. A failure prints the seed and the cell.

**Done when:** the Pilgrim test passes over at least 500 seeds,
`planSelectorTest` still passes, backtracking demonstrably recovers an
assignment a greedy pass would have lost (a hand-built fixture, not a seed), and
a cell with unsatisfiable `requires` provably never lands anywhere in the graph.

### M49: Bags and scarcity data (spec 3)

**Owns:** `data/pocketdungeons/loot_table/bags/*.json` (new), the existing
`loot_table/chests/*` and `loot_table/vaults/*` files, `LootTables.java`,
`Bags.java` (new), `src/test/java/pocketdungeons/BagTableTest.java`.

Mostly datapack work, which is why it parallelises without friction.

**Scope:** the eight bag tables of spec 3.2 (Mason, Plumber, Sapper, Magician,
Ranger, Shepherd, Innkeeper, Pilgrim), each carrying `minecraft:max_stack_size`
and `minecraft:max_damage` loot functions per 3.1 and a `pocketdungeons.bag`
byte in `CUSTOM_DATA` per 11.9. `Bags.java` maps a bag id to its table, its
display name, its three headline items for the door frames, and its tag set for
M47's `available` seed. In-run chest and vault tables shift from gear to tools
per 3.4, keeping the guaranteed food and torch floors.

**Scope boundary:** M49 does not apply a bag to a player. Application needs
M46's inventory ownership and lands at the wave 1 integration gate. Per open
question 12 the default is option (a): the bag is chosen once at the safe room's
staging room and persists until the next safe room visit, so `Bags.java` exposes
selection and application as separate calls from the start.

**Done when:** `bagTableTest` proves every bag id resolves to a parsable table,
every bag has food, no bag carries armour or a weapon above stone, and the
Pilgrim table is empty but for bread. Those are spec 3.2's authoring rules
turned into assertions, which is the cheapest place they will ever be enforced.

### Wave 1 integration gate

Serial, one integrator, after all four merge in the order M46B, M47, M49, M46.

1. Full build, every test task, on the merged tree.
2. Wire bag application into the door commit path: on commit, roll the bag table
   into the player's inventory leaving the keystone in slot zero, and name the
   bag in the door screen. Fifteen lines that need three milestones present at
   once, which is why they live here and not in any of them.
3. Walk M46's live checklist on a dev server. Human step.
4. Play one full `/dungeon` run with a Mason bag against the existing room
   catalogue and confirm scarcity changes how it feels. Spec 10 step 2's own
   acceptance test, worth doing before twenty new rooms exist.

---

## 4. Wave 2: six agents

Base: wave 1 merged. Four template agents, the omen agent, and the floor shape
agent. Genuinely disjoint file sets, which is the payoff from M45 steps 5 and 6.

Every template milestone has the same shape: author `RoomSpec` entries in its
own specs file, write the matching `dungeon_room/*.json` with `content`, `tier`,
`access`, `provides`, `requires` and `pressure`, add any `trial_spawner` config,
and register any `content` handler into `Situations`. None of them runs
`gentemplates`; capture is the wave gate (section 8).

Every template milestone also carries the same two acceptance clauses, from spec
0, 4 and 6.4:

> Each hazard the milestone ships passes at least two of hazard, weapon,
> resource. Every situation is readable from its doorway, through the 2 x 3
> opening or through an iron bar or glass window the template adds. Every gated
> template that takes an item into a container returns it to the player after
> the gate opens. Where any of the three does not hold, the completion note says
> which situation and why the exception is correct.

The third clause is not decoration: M47's whole model treats `available` as a
boolean set, and it is correct only while the item-return rule holds. A template
that eats a trial key silently breaks the solvability guarantee for every room
downstream of it.

### M50: Traversal (spec 4.1)

**Owns:** `TraversalSpecs.java`,
`dungeon_room/{chasm,flooded_hall,thicket,powder_snow_field,ice_run}.json`,
and the cave spider and breeze spawner configs, which M52 authors on its behalf
(see M52's spawner directory note).

Chasm, Flooded Hall, Thicket, Powder Snow Field, Ice Run. Three are pure
template; Thicket and Ice Run need a spawner config. Flooded Hall's drowned go
through `spawnMobs`, not a trial spawner, so the room is not an `encounter` and
does not enter the clear gate. Flooded Hall is also the spec's own example of a
glass window rather than bars: light and fluid must be contained.

All five are `access: open` except where the template physically blocks the exit.

Watch: the water in Flooded Hall must survive `StructureTemplate` capture and
restamp. Verify with one room before authoring the other four.

### M51: Mechanism (spec 4.2)

**Owns:** `MechanismSpecs.java`,
`dungeon_room/{frame_lock,rotation_lock,plate_pair,item_plate,gallery,tripwire_hall,flow_puzzle,pot_room}.json`.

Eight rooms, all vanilla redstone, no Java beyond the specs file. The largest
template milestone and the one that carries the item-return rule hardest: Frame
Lock, Item Plate and Flow Puzzle all take an item into a container, and all
three need the return chest on the far side of the door.

Watch two things. First, spec 6.4: doorways are two wide and three tall while an
iron door pair is two by two, so every gated template fills the top doorway
block with a wall block. Get that wrong once and it is wrong in eight rooms.
Second, Plate Pair is the round's only hard `requires` (`mob` or `lead`), and
under 6.6 it must find that at strictly lower BFS depth on every branch, not
just the critical path. Author it early and run M47's Pilgrim test against it
before writing the other seven.

### M52: Knowledge, combat variety, and the clear gate (spec 4.3, 4.4, 6.4)

**Owns:** `KnowledgeSpecs.java`, its `dungeon_room/*.json`, the whole of
`trial_spawner/**`, and `TrialContent.java`.

**Spawner directory ownership.** M50 (cave spider, breeze) and M53 (Hold the
Plate, Barred Vault) both need spawner configs and neither may write that
directory. M52 owns it and authors every config in the round, including theirs.
The two of them specify what they need in their handoff and M52 ships it. This
is the audit's catch: revision 2's matrix gave the same paths to M50 and M52 and
gave M53 none at all.

Bazaar, Don't Look, The Herd, Deep Dark Landing, Elder's Chamber, Blaze Loft,
Infested Wall, plus the seven combat variants of 4.4 which are pure
`spawn_potentials` data on existing spawner configs.

Also carries the clear-gate change, now in its open-question-11 form:
`spawnerClearThreshold` counts only `gated` encounter cells on the shortest
entrance-to-staging-room path, which by construction the player has already
cleared. Filter where `InstanceRecord.spawnerCellsByCell` is built. Without it,
a floor full of skippable open cells cannot be completed at all, which is the
direct contradiction spec 12 introduces.

Watch: piglins need `IsImmuneToZombification`, this dimension is not the Nether,
and the Bazaar is a `piglin_bartering` override. The shrieker in Deep Dark
Landing needs `can_summon: true` or the room has no teeth. Don't Look wants
tinted glass, not bars, for its window: bars would leak light into a room whose
whole point is the dark.

### M53: Pressure and spurs (spec 4.5, 5.1)

**Owns:** `PressureSpecs.java`, `SpurSpecs.java`, their `dungeon_room/*.json`,
`loot_table/vaults/spur_*`.

Rising Lava, Collapsing Bridge, Hold the Plate, Barred Vault, Ominous Bargain,
The Altar. The Store spur is the existing M35 anomaly and moves from
critical-path swap to spur candidate, which is a weighting change, not new code.

The quiet cell that revision 1 planned here is **gone**: spec 5.4 replaces it
with the staging room, and there is no separate breather cell.

Collapsing Bridge is the one room that has to build its own one-way movement out
of pistons, because spec 2 now says plainly that backtracking is allowed and
nothing in the engine seals a door behind the player.

### M48: Omen (spec 5.2, 5.3, 5.4)

**Owns:** `Omen.java` (new), `RunTimer.java`, `RunLifecycle.java`,
`src/test/java/pocketdungeons/OmenMathTest.java`.

`Omen.java` is pure math and holds all of it: the source table (dwell, sensor
pulses, shrieks, the bottle, a cleared Barred Vault), clamped 0 to 4 per floor,
the per-floor sum, and the finish table. Under 5.4 the finish table is keyed on
the **sum across floors between two safe room visits**, with thresholds 0 to 5,
6 to 15, 16 to 20 for a five floor loop, scaled to `floorsPerSafeVisit`.
`OmenMathTest` covers every row, the clamp at each end, and the scaling at 3 and
5 floors.

Dwelling counts only while the player is in a cell whose situation is unsolved.
A cleared cell and the staging room are free. This is the single change that
makes spec 12.3's retreat-and-think loop possible, and getting it wrong pushes
the player to stand in the dangerous room.

`RunTimer` loses the boss bar and keeps recording elapsed time for the
completion line and the diary. `RunLifecycle.completeRun` reads the finish table
instead of the clock. Applying the level to the player rides the existing
`TRIAL_OMEN` path in `Instances`; the dwell timer rides the tick already
running.

Per 5.2 there is no bar and no message. Per open question 5, if playtest shows
nobody connects dawdling to escalation, the fallback is one ambient sound, not a
number. Do not build the fallback now.

**Sequencing note:** M48 owns `RunLifecycle` in wave 2 and M46 owned it in wave
1, which is why M48 is not in wave 1. Wave 3 takes the file back.

### M54: Floor shape (spec 6.5)

**Owns:** `LayoutGraphGenerator.java`, `DungeonShape.java`,
`PocketDungeonsConfig.java`, `src/test/java/pocketdungeons/FloorShapeTest.java`.

Pure JDK and pure geometry, disjoint from every other milestone in the round.

**Scope:** `targetLength` up to 8 to 12 cells at tier 1 with
`branchProbability` and `loopProbability` up to match, all as config values, so
a floor is a small maze rather than a spine with stubs. Terminal placed at a
critical-path index between 60 and 90 percent of the path rather than at the
end, with the cells past it a decoy branch. A new `validate` check: the terminal
must not share a row or column with the entrance through an unbroken run of open
doorways, so the staging room is never visible from the front door.

`FloorShapeTest` asserts, over a spread of seeds, that the terminal index falls
in the band, that the line-of-sight check rejects the shapes it should, that
every cell is still reachable, and that the M29 no-backwards-propagation rule
still holds. That last one matters: M54 raises branch and loop probability, and
M29 is the invariant most likely to break under it.

**Done when:** floors are mazes, the terminal is off the end, no seed produces a
staging room visible from the entrance, and `layoutGraphTest` still passes.

### Wave 2 integration gate

Serial, one integrator.

1. Merge M54, M48, M50, M51, M52, M53 in that order. Full build, all tests.
2. **One `admin gentemplates` run** on the merged tree, capturing every new .nbt
   at once. The round's hardest serialisation point: the command regenerates the
   whole library from a live dev server and cannot be split across agents or
   worktrees. Diff the output and confirm no pre-existing template changed.
3. Run M47's Pilgrim test against the full catalogue on M54's bigger floors.
   First time it sees real `requires` data on a real maze, and the most likely
   place the round breaks.
4. Audit the catalogue against the three clauses in this section's preamble.
   Spec open question 7 asks for exactly this and it is cheapest here, while the
   templates are fresh, not in M59.
5. Play four floors and answer spec build-order step 3's question: is finding
   the terminal fun on a 16 x 16 cell grid, or is it a walk? Everything in wave
   3 assumes the answer is yes.

---

## 5. Wave 3: the loop spine, serial

Base: wave 2 merged. Three milestones, one agent at a time, because they are
successive edits to one state machine. A fourth agent runs M58 alongside them on
files none of the three touch.

**Two blocking decisions before M57 starts** (spec open questions 9 and 12):

- **Q9, server restart mid-loop.** Rebuild floor N from its seed with the party
  at the entrance, or end the run and deliver inventory to the safe room. The
  second is simpler and safe. Plan assumes the second unless told otherwise.
- **Q12, bag persistence across floors.** Plan assumes option (a): chosen once
  at the safe room's staging room, persists to the next safe room visit.
  Inter-floor doors select level, affix and theme only.

### M55: Safe room and staging room split (spec 12.6)

**Owns:** `RoomBuilder.java`, `RunLifecycle.java`, `Instances.java`,
`RitualListener.java`, `InstanceRecord.java`, `StagingSpecs.java`,
`dungeon_room/staging_*.json`.

`stampRoomShell` splits into `stampSafeRoom` (saved room blob plus bedrock
envelope, no selector doors, no furniture) and `stampStagingRoom` (selector
doors or the safe door, lodestone, furniture, Cube socket, no saved blob).
`resetForNextDungeon` goes away: the safe room is despawned and re-stamped, not
reset. `InstanceRecord.stagingCellOrigin` (already added in M45) becomes live
alongside `roomCellOrigin`. The party-wide `beginRun` gate lands here: every
member must stand in the staging room before the host can commit, with the
waiting chat line for those who have not, and solo players skipping the wait.
Join recovery routes reconnects to the staging room during an active dungeon and
to the safe room otherwise.

This is the milestone that closes the scarcity back door, and spec build-order
step 7 says plainly it must land before the test group sees multi-floor loops.

### M56: Door preview (spec 12.7)

**Owns:** `RitualListener.java`, `BedrockEnvelope.java`, `Instances.java`.

`chooseLobbyDoor`'s successor splits into `previewDoor` (generate only the
entrance cell from the door's seed, replace the door with an unbreakable window)
and `commitDoor` (generate the rest of the layout around the already-stamped
entrance, remove the window, open the doorway). Switching doors purges the
current preview cell and stamps the new one; at most one preview exists at a
time. Window material follows the same containment rule as spec 4: bars when
mobs and projectiles must be held, glass or tinted glass when light or fluid
must be. The lever is disabled while a preview generates.

The determinism requirement is the whole milestone: the previewed entrance must
be the entrance the party walks into. Generate it from the run seed, and on
commit build around it rather than re-stamping it.

### M57: Floor chaining (spec 12.2, 12.4)

**Owns:** `RunLifecycle.java`, `Instances.java`, `InstanceRegistry.java`,
`PocketDungeonsConfig.java`.

`completeRun` becomes `advanceFloor` (old dungeon despawns, new dungeon plus
terminal staging room generates, party teleports, inventory carries, nothing
delivered) and `returnToSafe` (safe room stamps from `RoomStore`, dungeon and
staging room purge, omen sum settles the keystone). `floorIndex` on the record
goes live. `floorsPerSafeVisit` config defaults to **3** for the test group, not
5: spec 12.4 says start at 3 and open question 8 agrees. Every Nth staging room
carries one safe door instead of three dungeon doors. Join recovery becomes
floor-index aware. Q9's answer determines the restart path.

The keystone banks per safe visit, not per floor. Finishing floor 3 and leaving
banks nothing. That is deliberate and it is the one real cost the loop keeps for
walking away.

### M58: The rest of the catalogue (spec 10 step 9)

Runs concurrently with M55 through M57, because it touches only the family spec
files and datapack JSON that wave 2 established, and none of the lifecycle files
wave 3 owns. Two or three templates per merge, playtested between, per the
spec's own pacing. Same three acceptance clauses as wave 2.

---

## 6. Tail

**M59: Cube recipes (spec 7).** Nine recipes writing tags into the keystone's
custom data, read at generation. Needs the catalogue wide and the loop working,
so it is genuinely last. Owns `CubeStation.java`, `Keystone.java`.

**Done (commit `1f2eab6`).** `CubeRecipe.java` implements all nine recipes.
`CubeStation.onUse` checks for a keystone in the main hand and a matching
catalyst in the off-hand. Recipe tags are read at `commitDoor` time and
stored on `InstanceRecord.recipeTags`. OMINOUS and FERAL add affixes;
BAG_OVERRIDE overrides the bag in `DungeonLog` and restores it on
`returnToSafe`. The remaining recipe effects (situation guarantees, path
length, completion listing) store tags for the planner to read; planner
wiring is a playtest-driven refinement step.

**M60: Open questions and the five-floor example.** Resolves questions 1, 2, 3,
6, 8, 10 and 11 from what wave 3's playtests showed, and writes spec 12.5's
five-floor example run, which cannot be written before three floors are
playable. Deliberately last: every answer is an observation, and there is
nothing to observe until the loop is in the ground.

**Done.** Questions 1, 2, 3, 6, 8, 10 and 11 are resolved in spec section 9
with implementation references. The five-floor example is written as spec
section 8.6. Questions 4, 5, 7 and 9 remain open until playtesting produces
data.

**M61: Stories (spec 13).** Resolves open question 4. A room may own the
16 x 16 volume beneath its own cell, private to it, with no doorways and no
presence in the layout graph. Gives the catalogue the pits that audit 4.2
says do not exist: Gallery's pit, Chasm's channel, Slime Pit's bounce,
Blaze Loft's lava below the ledge.

Deliberately scoped as a room feature, not a layout feature. The planner
stays two dimensional and the door mask stays four bits, so `LayoutPlanner`,
`RoomSelector`, `DungeonShape` and the coverage floor are untouched. Spec
13.5 records why the larger vertical-layout version is not adopted.

Owns `DungeonRoomMeta.java`, `RoomSpec.java`, `RoomTemplateGenerator.java`,
`BedrockEnvelope.java`, `RoomManifest.java` (door slot offset only), and one
family spec file for the pilot room. Ships one room, not the catalogue: the
point of the milestone is to prove the anchor, the envelope and the return
path check on a single template before any other room declares `stories`.

Handoff: `docs/d3-handoffs/M61-handoff.md`.

---

## 7. File ownership matrix

The contract. A cell names the milestone that may write that file in that wave.
Blank means no milestone in that wave touches it.

| File | M45 | Wave 1 | Wave 2 | Wave 3 | Tail |
|---|---|---|---|---|---|
| `RoomContent.java` | own | | | | |
| `RoomTemplateGenerator.java`, `RoomSpec.java` | own | | | | |
| `RoomBuilder.java`, `RoomGeometry.java` | own (window band) | | | M55, M56 | |
| `DungeonRoomMeta.java`, `SituationTags.java` | own | | | | |
| `build.gradle.kts`, `fabric.mod.json`, `src/gametest/**` | | M46B | | | |
| `DungeonLog.java` | | M46 | | | |
| `RunLifecycle.java` | | M46 | M48 | M55, M57 | |
| `RitualListener.java` | | M46 | | M55, M56 | |
| `Instances.java` | own | | | M55, M56, M57 | |
| `InstanceRecord.java` | own | | | M55 | |
| `InventorySwap.java`, `LostAndFound.java` | stub | M46 | | | |
| `RoomSelector.java`, `RoomManifest.java`, `BagTags.java` | | M47 | | | |
| `LayoutGraphGenerator.java`, `DungeonShape.java` | own (depths) | | M54 | | |
| `PocketDungeonsConfig.java` | | | M54 | M57 | |
| `loot_table/bags/*`, `loot_table/chests/*`, `loot_table/vaults/*` | | M49 | M53 spur tables only | | |
| `LootTables.java`, `Bags.java` | | M49 | | | |
| `RunTimer.java`, `Omen.java` | | | M48 | | |
| `TraversalSpecs.java` | stub | | M50 | M58 | |
| `MechanismSpecs.java` | stub | | M51 | M58 | |
| `KnowledgeSpecs.java`, `TrialContent.java` | stub | | M52 | M58 | |
| `trial_spawner/**` | | | M52 owns the directory; M50 and M53 request additions through it | | |
| `PressureSpecs.java`, `SpurSpecs.java` | stub | | M53 | M58 | |
| `StagingSpecs.java` | stub | | | M55 | |
| `BedrockEnvelope.java` | | | | M55, M56 | |
| `InstanceRegistry.java` | | | | M57 | |
| `Situations.java` | registry | | M50 to M53 registrations | M58 | |
| `CubeStation.java`, `Keystone.java` | | | | | M59 |

`Situations.java` is the one file four wave-2 agents all append to. Each adds a
single registration line in its own family's block: a two-line merge conflict at
worst, resolved by the integrator.

---

## 7b. The catalogue audit, and what it blocks

`SITUATIONS_AUDIT.md` is the per-situation table the four template milestones
implement from: 34 situations, each with its tier, `access`, `provides`,
`requires`, `pressure`, build cost, three-way verdict, item-return verdict and
window material. Read it instead of re-deriving any of that from the spec.

Its findings changed this plan in three places (M45 step 6, M52's ownership of
the whole spawner directory, and the matrix rows for `RoomBuilder` and
`RoomGeometry`) and it carries four results that block or reshape wave 2. None
of them is a reason to delay wave 1.

1. **Frame Lock is not buildable as specified.** A comparator on an item frame
   reads the item's rotation, 1 to 8, and zero when empty. It cannot tell a
   copper ingot from a prismarine shard, so "frame the right item and the door
   opens" has no vanilla mechanism, and Frame Lock and Rotation Lock are
   currently the same room. It has to be rebuilt as a hopper item filter. This
   is M51's largest single item and its handoff must carry the rebuild, not the
   spec's description.
2. **There are no pits.** `BedrockEnvelope` stamps a sub-floor bedrock layer at
   y=-1, so any hole is one block deep. Gallery's pit, Chasm's channel,
   Collapsing Bridge's pit, Blaze Loft's lava below and Slime Pit's name all
   assume depth the cell cannot have. Lava at y=0 is the general substitute and
   fixes several three-way failures at the same time. Every template milestone
   needs this in its handoff or four agents will independently author holes that
   the shell fills in.
3. **Two gated rooms are impassable for the Pilgrim bag.** Infested Wall and
   Elder's Chamber both assume a pickaxe, and only Mason carries one. There is
   no `pickaxe` tag in the vocabulary, so M47's Pilgrim test cannot catch it as
   a `requires` violation: it presents in play as a stuck player. The audit's
   fixes are a stone pickaxe in a pot for Infested Wall and a gravel or dirt
   soft wall for Elder's Chamber. Either way M52 owns both.
4. **Four gated rooms break the item-return rule** (Frame Lock, Flow Puzzle,
   Gallery, Barred Vault), which is the rule M47's boolean `available` model
   depends on. The audit gives each a two-block fix, all of the same shape: a
   hopper through the wall into a chest past the door. These are not optional
   polish; each one silently over-promises solvability to the generator.

Three spec corrections fall out of it and should be made before the templates
ship: 6.4 credits Frame Lock with Item Plate's return chest; Plate Pair's
"`lead` or `mob`" is an OR that 6.6's subset model cannot express and should
collapse to a single leashable-`mob` tag; and `trial_key` is consumable, which
the boolean model cannot represent, so it may appear in `provides` and in spur
`requires` but never in a critical-path `requires`.

---

## 8. What cannot be parallelised

Name these in every handoff so no agent waits on them or works around them.

1. **Template capture.** `admin gentemplates` runs against a live dev server and
   rewrites the whole `structure/rooms` directory. Once per wave, at the gate,
   by the integrator. Agents author `RoomSpec` code and never run it.
2. **Live verification.** No agent can drive a client. Everything an agent
   cannot check goes into `LIVE_TEST_PASS.md` as a numbered manual item with its
   commands. M46's checklist is a merge blocker; the rest are not.
3. **The wave 3 spine.** M55, M56 and M57 are successive edits to one state
   machine across `RunLifecycle`, `Instances` and `RitualListener`. No seam
   splits them. Running them concurrently would produce three incompatible
   versions of the same flow.
4. **The wave 1 gate's bag application.** Needs M46 and M49 in the same tree.
   Fifteen lines, belongs to the integrator.
5. **Playtesting between template milestones.** Spec 10 step 9 wants two or
   three templates a milestone with playtesting between. Wave 2 ships twenty-odd
   at once, deliberately, and pays for it with a longer gate. If the gate's four
   floors go badly, the correction is to cut templates, not to re-plan the wave.

---

## 9. Risks, and what each one costs

| Risk | Signal | Response |
|---|---|---|
| `AFTER_PLAYER_CHANGE_LEVEL` is absent or differs in 26.2 | M45 step 7 jar check fails | Fall back to the tick pass alone. Spec 11.7 shows every row is still caught, one tick later. M46 is unaffected. |
| `ItemStack.OPTIONAL_CODEC` cannot serialise under `SavedDataType` | M46 step 1 | Store each slot as a `CompoundTag`. Half a day, no design change. |
| M54's higher branch and loop probability breaks M29 | `FloorShapeTest` or `layoutGraphTest` | Tune probabilities down until M29 holds. M29 is a hard invariant; floor size is a preference. |
| Backtracking in M47 is slower than the spec assumes | `GraphSolvabilityTest` runtime | Cap depth before capping correctness. The spec's own estimate (20 cells, 5 to 10 candidates, milliseconds) is plausible but unmeasured. |
| A template violates the item-return rule and the boolean `available` model silently over-promises | Wave 2 gate step 4, or a stuck player in playtest | This is the failure mode that looks like a generator bug and is a template bug. The gate audit is the only cheap place to catch it. |
| Finding the terminal is a walk, not a hunt | Wave 2 gate step 5 | Wave 3 is built on the answer being yes. If it is no, stop and re-plan section 12 before M55, not after M57. |
| Floors of 8 to 12 cells across 3 floors runs 25 to 50 minutes | Wave 3 playtest | `floorsPerSafeVisit` and `targetLength` are both config. Start at 3 floors of 8 and move up only if runs feel short. |
| Stash and swap ends decorating the safe room with survival items | M46 design review | Real consequence, not a bug: once the mod owns the void inventory, nothing carried from survival enters the safe room. `VISION.md` 3.1 assumes decoration; confirm intent before M46 merges. If it must survive, the exception is a whitelist in the entering branch, argued for rather than discovered. |

---

## 10. Handoff generation

Each milestone becomes one `docs/d3-handoffs/M{n}-handoff.md` per
`HANDOFF-SPEC.md`. Three additions specific to this round, since these handoffs
are read by agents working concurrently rather than in sequence:

1. **Inline the milestone's ownership row** from section 7, with the rule from
   section 1 stated in one line. An agent that does not know what it may not
   touch will touch it.
2. **Name the base commit and the wave**, since a wave-1 agent must not build on
   another wave-1 agent's work even if it can see the branch.
3. **State the serial steps the milestone does not perform**: no `gentemplates`,
   no live testing, no merging. Otherwise every template agent independently
   discovers that its .nbt is missing and tries to fix it.

Reading lists stay at four items. For every wave-2 template milestone the list
is the same three: `CONVENTIONS.md`, `SITUATIONS_SPEC.md`'s own section for that
family, and `docs/DISCOVERIES.md`. The situation entries themselves get inlined
into the handoff, because an agent that has to re-read the catalogue every turn
is paying for it every turn.
