# GPT-6 Astra cross-file review prompt

> Paste this prompt into an Astra session that has access to the
> local filesystem. Astra can read source files, run grep, and follow
> imports to verify findings against actual code. Do not guess at
> dependencies; read them.

## What you are reviewing

Pocket Dungeons is a server-side Fabric mod for Minecraft 26.2. It
implements a run-based dungeon game mode: each player owns a persistent
room, three doors offer dungeon runs, players spend keystones, progress
through procedural rooms without backtracking, and return to their
physically relocated room.

The mod went through milestones M71 through M78 recently. These
milestones added data-driven recipes (M71), pack validation (M72), new
room identities (M73), situation rooms (M74), social features (M75),
performance measurement (M76), and the Endless Mine (M78). The code
passed 61 headless gametests and a full build, but headless tests cannot
catch cross-file integration bugs where a change in one file breaks a
dependency in another.

Repository root: `A:\MrPinoys Mods\pocketdungeons`
Source root: `src/main/java/pocketdungeons/`

## How to review

Start by reading the files below, then follow every import, caller, and
field reference to verify your findings against the actual code. Do not
report a bug unless you have read the code path that triggers it. If a
dependency lives in a file not listed below, read it.

Key entry points to read first:

```
src/main/java/pocketdungeons/ContentReload.java
src/main/java/pocketdungeons/ContentSnapshot.java
src/main/java/pocketdungeons/InstanceRegistry.java
src/main/java/pocketdungeons/Instances.java
src/main/java/pocketdungeons/RunLifecycle.java
src/main/java/pocketdungeons/CubeRecipe.java
src/main/java/pocketdungeons/CubeRecipeManifest.java
src/main/java/pocketdungeons/CubeRecipeDefinition.java
src/main/java/pocketdungeons/CubeRecipeMeta.java
src/main/java/pocketdungeons/RecipeEffects.java
src/main/java/pocketdungeons/RecipeDiscovery.java
src/main/java/pocketdungeons/RecipeIds.java
src/main/java/pocketdungeons/DiscoveryFloor.java
src/main/java/pocketdungeons/RoomSelector.java
src/main/java/pocketdungeons/PackValidator.java
src/main/java/pocketdungeons/AffixManifest.java
src/main/java/pocketdungeons/BagManifest.java
src/main/java/pocketdungeons/RoomManifest.java
src/main/java/pocketdungeons/RunSession.java
src/main/java/pocketdungeons/DungeonLog.java
src/main/java/pocketdungeons/Payout.java
src/main/java/pocketdungeons/InstanceTeardown.java
src/main/java/pocketdungeons/LayoutStamper.java
src/main/java/pocketdungeons/TemplateStamper.java
```

The full source set is 151 files under `src/main/java/pocketdungeons/`.
You can list them and read any of them. The test sources are under
`src/test/java/pocketdungeons/` (headless unit tests) and
`src/gametest/java/pocketdungeons/` (server gametests). Reading the
tests will tell you what is already covered.

## Architecture context

Key subsystems and their interactions:

- **ContentReload** registers a Fabric
  `SimpleSynchronousResourceReloadListener` that fires on the server
  thread. It parses every content surface (rooms, themes, affixes,
  bags, roles, recipes) into a `ContentSnapshot`, validates it, and
  publishes atomically. A failed candidate is not published; the last
  valid snapshot stands.

- **ContentSnapshot** holds the parsed manifests. It checks built-in
  coverage gates: every built-in room, affix, bag, role, and recipe id
  must be present. A candidate that fails the gate is rejected.

- **Instances** is the main runtime (2,711 lines). It manages instance
  records, door previews, floor generation, homecoming, teardown, and
  the tick loop. It reads from `InstanceRegistry.bySlot` and
  `InstanceRegistry.byMember`.

- **RunLifecycle** handles the run state machine: enter, commit, exit,
  return to safe, homecoming, recovery. It transitions `RunSession`
  phases and calls into `Instances` for physical world changes.

- **CubeRecipe** (M71 rewrite) matches a catalyst against the live
  `CubeRecipeManifest`, applies the recipe by writing the recipe id
  and escrow into the keystone's `CustomData`, shrinks the off-hand
  stack, records the discovery, and records the ingredient. The escrow
  is restored on cancel and cleared on commit.

- **CubeRecipeManifest** loads `data/<namespace>/cube_recipe/*.json`
  via `ResourceManager.listResources`, validates catalyst references
  against `BuiltInRegistries.ITEM`, checks for duplicate catalyst
  declarations, and publishes through `ContentSnapshot`.

- **DiscoveryFloor** fires once per player on lobby entry at the Cube
  unlock level, delivers a bone catalyst, sends a terse message, and
  marks the floor delivered in `RecipeDiscovery` (a sidecar on
  `DungeonLog`).

- **RoomSelector** generates dungeon plans: picks rooms for cells,
  applies recipe guarantees (forced rooms), validates the plan, and
  checks the return path. M71 generalized the guarantee and weighting
  from hardcoded booleans to data-driven lists.

- **PackValidator** (M72) validates a candidate content snapshot,
  exports a starter pack, and exports an author workspace. It uses
  `FileSystems.newFileSystem` for the `jar:` protocol path.

- **RunSession** is the phase state machine. `derivePhase`
  reconstructs the phase from a persisted record after a server
  restart.

- **DungeonLog** is the per-server persistent state. It holds task
  progress, bounties, and the M71 `RecipeDiscovery` sidecar (discovered
  recipes, ingredients encountered, floor delivered flag).

## What to look for

Focus on cross-file bugs that headless tests miss. For each category,
read the relevant code paths end to end before reporting.

1. **State consistency across reload.** A `/reload` swaps the
   `ContentSnapshot` atomically, but `InstanceRegistry.bySlot` may
   reference rooms, themes, affixes, or recipes that the new snapshot
   no longer carries. Read `ContentReload.reconcileActiveFloors` and
   every field on `InstanceRecord` that could hold a stale reference.
   Check whether the reconciliation clears everything, or whether a
   field survives the swap and is later read as if it were valid.

2. **Recipe escrow and custody.** Read `CubeRecipe.apply`,
   `CubeRecipe.restoreCatalyst`, `CubeRecipe.clearCatalystEscrow`, and
   `CubeRecipe.pendingCatalystId`. Check whether any path can reach
   `restoreCatalyst` after the keystone has been modified by another
   operation (a second recipe apply, a keystone level change, a
   keystone transfer to another player). Check whether the escrow
   write and the off-hand shrink have a crash window that leaves
   inconsistent state.

3. **Discovery floor idempotence.** Read `DiscoveryFloor.fire` and
   the `DungeonLog` methods it calls (`discoveryOf`,
   `markFloorDelivered`, `recordIngredientEncountered`). Check whether
   two concurrent lobby entries can race the `floorDelivered` flag
   check. Verify the call path is server-thread-only and cannot be
   re-entered.

4. **Recipe guarantee and plan solvability.** Read
   `RoomSelector.applyRecipeGuarantees`, `RoomSelector.forceRoom`, and
   `RoomSelector.validate`. Check whether a third-party recipe with a
   `guaranteed_rooms` group that forces a room providing a tag another
   cell requires can break the plan. Read the built-in room data files
   under `src/main/resources/data/pocketdungeons/dungeon_room/` to
   check the `provides` and `requires` fields.

5. **RunSession phase transitions.** Read `RunSession.transition`,
   `RunSession.require`, and every `RunLifecycle` method that calls
   `transition`. Check whether any path can skip a required transition
   (for example, a player disconnecting during `SAFE_RETURN` leaving
   the record in `SAFE_RETURN` instead of transitioning to `HOME`).
   Read `RunSession.derivePhase` and check whether it reconstructs
   the correct phase for every persisted record shape.

6. **PackValidator jar: filesystem.** Read
   `PackValidator.copyResourceTree` and `PackValidator.copyDirectory`.
   Check whether the `FileSystem` close path can leak if
   `copyDirectory` throws. Check whether the
   `FileSystemNotFoundException` catch is the right pattern for a
   shared filesystem. Read `DatapackExporter` (if it exists) to see
   how the same pattern is used elsewhere.

7. **ContentSnapshot coverage gate.** Read
   `ContentSnapshot.build` and `CubeRecipeManifest.hasBuiltInCoverage`.
   Check whether a built-in recipe id that exists in the manifest but
   has a null or malformed `effects` field can pass the coverage gate
   but fail at match time in `CubeRecipe.match`. Read
   `CubeRecipeMeta.fromJson` to see how `effects` is parsed and
   whether a null effects can survive into the manifest.

8. **DungeonLog RecipeDiscovery codec.** Read the `RecipeDiscovery`
   codec in `DungeonLog` and the `markFloorDelivered`,
   `floorDelivered`, `recordRecipeDiscovery`, and `discoveryOf`
   methods. Check whether a save that has a partial discovery state
   (some recipes discovered, but the `floor_delivered` field missing)
   loads correctly or whether the missing field defaults to false and
   triggers a second discovery floor delivery.

## Output format

For each finding, report:

```
FINDING N
Severity: CRITICAL / MAJOR / MINOR / INFO
Files: file1.java, file2.java
Lines: approximate line numbers
Description: what is wrong
Evidence: the code path that triggers it, with file and line
Suggested fix: one or two sentences
```

If you find no bugs in a category, say "No findings" for that
category. Do not invent bugs to fill space. False positives waste more
time than missing a real bug.

After all findings, add a summary:

```
SUMMARY
Total findings: N
Critical: N
Major: N
Minor: N
Info: N
Highest-risk subsystem: name
```

## Punctuation rule

This workspace forbids em dashes and double hyphens as punctuation in
all prose. Use colons, semicolons, commas, periods, or parentheses
instead. Single hyphens in compound words (server-side, cross-file)
are fine. Command line flags (--offline) are fine. Apply this rule to
everything you write.
