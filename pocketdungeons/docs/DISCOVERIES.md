# Pocket Dungeons — discoveries, traps, and operational notes

Verified 26.2 API findings, development pitfalls, and dev-server operational
notes. So nobody re-derives them the hard way.

---

## Traps — read before writing any code

These are all real, all cost hours previously, and all are invisible until they
bite.

1. **Verify every Minecraft API shape against the real jar before using it.**
   ```bash
   JAR="/c/Users/Kriss/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar"
   javap -cp "$JAR" net.minecraft.some.Class          # signatures
   javap -c -p -cp "$JAR" net.minecraft.some.Class    # bytecode, when behaviour matters
   ```
   Checking a method *exists* is not the same as checking what it *does*. Three
   shipped bugs came from that distinction.

2. **Never use a vanilla `minecraft:vault` for a reward this mod computes.**
   `VaultBlockEntity$Server.tryInsertKey` rolls the vault's loot table *first* and
   returns early if it comes back empty — before consuming the key, before
   `unlock()`, before `addToRewardedPlayers()`. This silently broke U7's entire
   choice mechanic. U8 replaces the vaults with doors partly for this reason.

3. **`Inventory.add(ItemStack)` returns "did I move *any* of this" and mutates the
   stack down to the remainder.** The idiom `if (!player.getInventory().add(stack))`
   silently destroys overflow. Always use `Payout.deliver`, which checks
   `stack.isEmpty()` afterwards. The same idiom appears in at least eight other
   files across the suite (`bounties/Rewards`, `chatdonkey/Rewards`,
   `cobbleeconomy/ItemBank`, `kamutotems/AssignedQuestHost`, `ballot/BallotCommands`,
   and more) with the same hole.

4. **`Block.UPDATE_SUPPRESS_DROPS` does not stop a container dropping its
   contents.** That needs `Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` as well.
   Use `TrialContent.FLAGS` / `RoomContent.FLAGS`, which already OR both.

5. **`ClickEvent` is a sealed interface in 26.2, not a class with a constructor.**
   Use `new ClickEvent.RunCommand("/dungeon choose 1")` and
   `style.withClickEvent(event)`. The old `new ClickEvent(Action.RUN_COMMAND, ...)`
   form will not compile.

6. **A misspelt trial-spawner config id does not throw** — the codec drops the
   field and the block silently keeps `FullConfig.DEFAULT`. Always read ids back
   out of the block entity (`/dungeon admin cellreport`) rather than trusting the
   write.

7. **`clearBlocksPerTick` has a hard floor of 1024** in
   `PocketDungeonsConfig.apply`.

8. **`dungeon_log.dat` lives at**
   `run/world/dimensions/minecraft/overworld/data/pocketdungeons/dungeon_log.dat`,
   not `run/world/data/`. "No file in `world/data`" looks exactly like a failed
   save and is not one.

9. **This mod has exactly one mixin, and that is the budget.** `CustomClickMixin`
   catches dialog button payloads, because Fabric API has no event for
   `ServerboundCustomClickActionPacket` and vanilla's own hook has already
   discarded which player clicked. Everything else runs on stock Fabric API
   events. If you think you need a second mixin, you have probably taken a wrong
   turn: say so in your report and look for the event or the datapack route
   first. (Traps 14 and 15 are a worked example of that search paying off.)

10. **Headless testing cannot right-click anything.** Anything involving a click,
    a GUI, or a player standing somewhere goes in `LIVE_TEST_PASS.md` as
    unverified. Do not claim it works.

11. **`VaultServerData.getRewardedPlayers()` is package-private**, so the
    vault-claim check goes through `saveWithoutMetadata` +
    `UUIDUtil.CODEC_LINKED_SET` on `server_data.rewarded_players` instead.

12. **`ItemStack.is(Item)` no longer appears in `javap ItemStack`** — it is
    inherited from `TypedInstance.is(T)`.

13. **There is no diamond, obsidian, netherite or ender door in vanilla.**
    Verified against the 26.2 jar's `block/doors.json` and `block/wooden_doors.json`
    tags. The complete list is 21 blocks: twelve wooden (oak, spruce, birch,
    jungle, acacia, dark_oak, pale_oak, crimson, warped, mangrove, bamboo,
    cherry), eight copper (plain, exposed, weathered, oxidized, and the four
    waxed variants), and `iron_door`. Any design that wants a material ladder of
    doors has to express the material somewhere other than the door block,
    because section 9 forbids adding one.

14. **Crafting recipe results support arbitrary `components`.** Vanilla's own
    `suspicious_stew_from_*.json` recipes prove it. A datapack recipe can
    therefore produce a renamed, marked vanilla item with no Java at all, which
    is almost always cheaper than mixing into `CraftingMenu`. For the record, if
    a mixin ever is unavoidable, the server-side hook is
    `CraftingMenu.slotChangedCraftingGrid(AbstractContainerMenu, ServerLevel,
    Player, CraftingContainer, ResultContainer, RecipeHolder<CraftingRecipe>)`,
    and the rule there is to inject only when vanilla produced no result, so a
    real recipe can never be shadowed. Note that "eight around one" is already a
    vanilla shape (golden apple, enchanted golden apple).

15. **A datapack recipe with no unlock advancement is craftable but invisible.**
    It never appears in the recipe book and autofill never offers it, yet anyone
    who knows the pattern can still craft it by hand. That is a hidden-recipe
    discovery mechanic for free, with no delivery mechanism to build.

16. **Placing a door loses every component it carried.** A door has no block
    entity, so `custom_data` on the item does not survive becoming a block, and
    the block type alone cannot prove provenance (a warped door is craftable
    from planks). Read the marker at use-on-block time instead, the way
    `RitualListener` already handles placement: there is no generic
    before-a-block-is-placed event in this mod's Fabric API surface, so the
    use-on-block interaction that a placement begins from is the hook.

17. **Anything placed in the room while a door choice is pending must be cleared
    before capture.** `RunLifecycle.saveRoom` clears the selector doors, calls
    `RoomStore.capture`, and puts them straight back, because `LayoutStamper`
    re-skins a generated dungeon's entrance cell from the same blob and baked-in
    furniture would leak into every run forever. Frame blocks, decorations or
    any future lobby fixture inherit that requirement. Note the asymmetry:
    selector doors clear to `AIR` because they stand in open room space, but
    anything that replaces wall must clear back to `RoomBuilder.WALL` or it
    punches a hole in the seal.

18. **Gametests work here, and vanilla's own mock player is enough. No Carpet.**
    Verified against fabric-api 0.156.0+26.2, Loom 1.17.20 and the 26.2 jar, by
    running the harness, not from memory.

    - `fabric-gametest-api-v1` version `4.0.21+4a7fa0819e` is already a
      dependency of the `fabric-api` artifact this project depends on, so no new
      version property and no new repository are needed. It is the annotation
      based rewrite: `net.fabricmc.fabric.api.gametest.v1.GameTest` on a method
      that is public, non-static, returns `void` and takes one
      `GameTestHelper`. `TestAnnotationLocator` finds those methods on classes
      listed under the `fabric-gametest` entrypoint key, and derives the test id
      from class and method name, so nothing is registered by hand. Watch the id
      shape: `placesABlock` on `HarnessGameTest` becomes
      `harness_game_test_places_ablock`, not `..._places_a_block`.
    - Loom 1.17 already knows how to do all of the wiring:
      `fabricApi.configureTests { }` (extension `fabricApi`, interface
      `net.fabricmc.loom.api.fabricapi.FabricApiExtension`) creates the
      `gametest` source set, a `gameTest` run config that inherits `server` and
      sets `-Dfabric-api.gametest`, a run directory at `build/run/gameTest`, and
      the `runGameTest` task, and makes `check` depend on it. Two settings
      matter here: `createSourceSet = true`, and `enableClientGameTests = false`
      because this mod is server-side only. Note the knock-on: `build` runs
      `check`, so `./gradlew build` now boots a headless test server for about
      fifteen seconds. That is intended.
    - `getEula()` and `getClearRunDirectory()` on `GameTestSettings` only take
      effect in the client gametest branch, so with client tests off they are
      no-ops. The server path needs no `eula.txt`: the gametest module's
      `MainMixin` forces `Eula.hasAgreedToEULA` true whenever the
      `fabric-api.gametest` property is set.
    - The entrypoint lives in `src/gametest/resources/fabric.mod.json` under its
      own mod id, `pocketdungeons-gametest`, which depends on `pocketdungeons`.
      That is deliberate. Putting the `fabric-gametest` entrypoint in the main
      `fabric.mod.json` would ship a dangling entrypoint class name in the
      release jar. Verified with `unzip -l`: the built jar contains zero
      gametest entries and its `fabric.mod.json` is byte for byte what it was
      before this milestone.
    - **Vanilla can carry the player scenarios.** `GameTestHelper` has three
      mock players; only `makeMockServerPlayerInLevel()` is a real one. It
      builds a `ServerPlayer` over an `EmbeddedChannel` backed `Connection` and
      calls `PlayerList.placeNewPlayer`, which is the same code path a real
      login takes. A probe run confirmed, in one test: the player appears in the
      `PlayerList` (count 1), `teleportTo(nether, ...)` returns true and moves
      it across dimensions, `PlayerList.respawn` returns a *different*
      `ServerPlayer` instance back in the overworld at full health, and
      `PlayerList.remove` takes the count back to 0. That is the whole "enters a
      dimension, disconnects, reconnects, dies, respawns" surface, with no third
      party dependency. Carpet is not needed and should not be added.
    - Two catches on that mock player. First, it is
      `@Deprecated(forRemoval = true)` in 26.2, verified in the constant pool
      with `javap -v`, and it is the only helper that registers a player with
      the `PlayerList`, so there is currently no supported replacement. Use it,
      annotate the call site `@SuppressWarnings("removal")`, and expect to
      revisit on the next version bump. Second, `player.die(source)` alone
      leaves `isDeadOrDying()` false because it does not zero health. Drive the
      health down first if a scenario depends on the death actually taking.

19. **How to add a gametest.** Put a class in
    `src/gametest/java/pocketdungeons/gametest/`, give it public non-static
    `void` methods taking a single `GameTestHelper` and annotated `@GameTest`,
    then add the class name to the `fabric-gametest` entrypoint list in
    `src/gametest/resources/fabric.mod.json`. That list is the only registration
    step; forgetting it is the one silent failure mode, and it shows up as
    nothing more than a lower test count in the run output. Run with
    `./gradlew runGameTest --offline`. The output ends with either
    `All N required tests passed :)` or a per-test failure line naming
    `pocketdungeons-gametest:<test_id>`, and a failure fails the gradle build.
    The default structure is an empty 8x8 with one block of padding, which is
    what `HarnessGameTest` uses; `@GameTest(structure = "...")` points at an
    `.snbt` under `<modid>/gametest/structure/` if a scenario needs real
    geometry. Note that the passing case does not print test names, so to prove
    a new test actually ran, break its assertion once and read the failure line.

22. **A stale `world/session.lock` produces `BUILD SUCCESSFUL` with nothing
    checked, not a build failure.** `dungeonIntegrationTest` (M62) launches a
    real dedicated server against an isolated run directory; if that
    directory's world is already locked by a leftover process, `Main.main`
    throws before `MinecraftServer` is ever constructed, `SERVER_STARTED`
    never fires, `onInitialize`'s checks never run, and the JVM exits 0
    anyway. The task reports green having verified nothing. Confirmed by
    reproducing it: no assertion ran, yet the gradle task passed. There is no
    generic fix here beyond treating a scratch run directory as disposable —
    delete it and retry rather than trusting a green result from a directory
    that was already in use.

23. **Never call `System.exit()` from the server thread itself.** Doing so
    inside a `ServerLifecycleEvents.SERVER_STARTED` (or any other) callback
    that runs on the server thread deadlocks the process rather than exiting
    it: Minecraft's own JVM shutdown hook needs the server thread to notice a
    stop flag and unwind its tick loop before the hook returns, and a
    `System.exit` call from that same thread blocks inside the hook it is
    waiting on. Confirmed empirically in `DungeonIntegrationEntrypoint`
    (M62): the process hung for 36 minutes with no further log output after
    logging its own "PASS", until killed by hand. The fix is to call
    `System.exit` from a separate (daemon) thread, so the callback returns
    and the server thread reaches its normal tick loop, where the shutdown
    hook can actually make progress against it.

24. **A gametest mock player is in creative, and `Inventory.add` voids
    overflow in creative.** Verified in the 26.2 bytecode: `Inventory.add`
    has a `Player.hasInfiniteMaterials` branch guarded by
    `ItemStack.setCount(0)`, which empties a stack that will not fit and
    reports success. That is right for a creative player, who needs no
    remainder, but it means `Payout.deliver`'s `stack.isEmpty()` test passes
    with the item having gone nowhere. An overflow scenario written against
    a default mock player therefore cannot tell a working payout from a
    voided one, whichever way the production code behaves. Call
    `player.setGameMode(GameType.SURVIVAL)` on any mock player whose
    scenario asserts on a drop or a remainder. Cost an hour in M63, reading
    as a mod bug the whole time.

25. **A mock player stands at the world origin, which is not a loaded
    chunk.** `makeMockServerPlayerInLevel` does not place the player
    anywhere near the test structure, and a gametest structure sits millions
    of blocks out. `LivingEntity.drop` ends in `Level.addFreshEntity`, which
    discards an entity destined for an unloaded chunk and returns false
    rather than throwing, so every dropped item silently disappears.
    Teleport the player to `helper.absolutePos(...)` first. Two further
    wrinkles found the same way: a `setChunkForced` ticket is not honoured in
    the tick it is added (the chunk source processes tickets on its own
    tick), and in another dimension the matching chunk has never been
    generated at all, so `getChunk` is needed to block until it exists.
    Even then a freshly generated far-out chunk is not queryable through
    `getEntitiesOfClass` for several ticks. If a scenario only needs the
    entering branch and not real dungeon geometry, point
    `InventorySwap.Probe.useDimensionForTesting` at the level the player is
    already standing in and skip the whole problem.

26. **Gametests run concurrently against one server, so any static test
    seam is shared state.** `InventorySwap.Probe.useDimensionForTesting`
    writes a single static field. A scenario that finishes and resets it to
    `null` does so while sibling scenarios are still mid-flight, and their
    next reconcile then decides the player was never in the dungeon and does
    nothing, which reads as the swap declining to run. Two rules fall out:
    set the field immediately before the pass that reads it, not once at the
    top of the scenario, and do not reset it in cleanup. The same applies to
    coordinates: two scenarios teleporting to one fixed position land in each
    other's item sweeps, so derive per-scenario positions from
    `helper.absolutePos`.

27. **`SavedDataStorage` does not write atomically.** Verified in the 26.2
    jar: it persists a `SavedData` with a bare `NbtIo.writeCompressed`
    straight onto the live path, on `Util.ioPool()`, with no temp file, no
    rename and no `.bak` (contrast `RoomStore.save`, which does all three).
    A crash or kill part way through leaves `dungeon_log.dat` truncated, and
    a truncated dungeon log is a player whose survival inventory was taken
    and whose stash record no longer exists. M63 backstops the stash field
    with `InventoryJournal`; every other field on the log (fuel balance, task
    progress, bounties, extracted powers) is still exposed, and closing that
    generally would need a mixin into vanilla's storage layer, which trap 9's
    budget forbids. Do not assume a `SavedData` write is a durability
    boundary.

28. **UNVERIFIED: process termination between the inventory clear and the
    saved-data write.** M63 stages the *state* a kill leaves and proves the
    repair from it, but never kills a real process and reloads. Whether a
    genuine `SIGKILL` at that instant leaves the journal record intact and
    readable on the next boot, and whether the player-data write behaves the
    same way, is untested in any harness here. `LIVE_TEST_PASS` 37.2 is the
    row. Treat the journal as closing a named hole, not as proven against a
    real crash.

---

20. **The doorway plane belongs to the manifest, not to the template.**
    `RoomManifest.deriveMask` reads a room's door mask from the jigsaw blocks
    at the canonical doorway slots (x=15, y=1..3, z=7..8 at rotation 0). A
    template that writes anything over all three of them ships a room the
    planner reads as having no door on that wall, so it is placed as a dead
    end and whatever the template put there gates nothing. Writing over only
    some of them is a partial door and the manifest rejects the room outright,
    which is the only case that produces a log line. Five rooms shipped this
    way and four were silent. Stand doors, seals and lintels one block inside,
    at x=14; the door still covers both doorway columns because z=7 and z=8
    are the only way through. Audit it with a jigsaw count: every template
    must carry exactly three `pocketdungeons:door` jigsaws per declared door.

21. **Flowing water never plugs a hole; only a solid block or a source does.**
    Water scans about five blocks for a drop and, finding one, commits its
    whole flow to it and ignores every other direction. Flowing water in that
    hole does not restore the terrain: the scan looks straight through it and
    still sees the drop, so the diversion is permanent until a player places a
    solid block (or a true source block levels the surface). This is what makes
    Flow Puzzle's redirect a real gate, and it means a one block deep hole is
    enough. Do not reach for extra depth to make a drain work.

## Carried-forward lessons (all still current)

- **Verify Minecraft API shapes against the real jar with `javap`, and check
  what a method *does*, not just that it exists.** Multiple shipped bugs came
  from checking existence without checking behaviour.

- **Headless testing has a hard blind spot.** Nothing in a console session ever
  right-clicks a block, opens a chest, or clicks a GUI button. That is what hid
  container-drop bugs and GUI bugs, and it is why all client-interactive checks
  are in `LIVE_TEST_PASS.md` rather than marked verified.

- **Check the generated data, not just that generation ran.** User-reported bugs
  survived passes that confirmed loot tables parsed and the right item type
  appeared, but never inspected an enchantment component or compared a roll's
  size to the plan's own narrative.

- **Trace cross-cutting interactions by hand, not by diff.** Teardown/slot-reuse
  passes, mob-spawn undercounts, and payout ordering (eject first, pay second,
  so a dropped reward lands in the overworld and not inside a dungeon teardown
  is already clearing) were each found or confirmed by hand-tracing.

---

## Operational notes

### Dev server testing harness

There is no Minecraft client in this environment. Verification happens by
driving `./gradlew.bat runServer --offline` headlessly from a Python driver
that pipes timed console commands into its stdin and reads stdout for markers
(`Done (` for boot). A working driver is written fresh each session; the shape
that works is: launch
`["cmd","/c", r"A:\MrPinoys Mods\pocketdungeons\gradlew.bat", "runServer", "--offline"]`
with `cwd` set to the mod root, a reader thread pumping stdout into a queue,
wait for `Done (`, then write commands to stdin with a short drain after each,
then `stop`.

Two gotchas, worth not re-discovering:

- The background shell's `python` is native Windows Python, not Git Bash's —
  POSIX-style paths like `/a/tmp/...` do **not** resolve from inside the
  script. Use Windows paths for anything the Python process itself opens, and
  an absolute Windows path for `gradlew.bat` (`cmd /c gradlew.bat` alone does
  not find it even with `cwd` set correctly).
- Any block/entity console command targeting a scratch position needs
  `/execute in pocketdungeons:void run forceload add <x1> <z1> <x2> <z2>`
  first, or every command silently no-ops with "That position is not loaded".

### Useful console commands

- `/loot insert <pos> loot <table>` (not `/loot give`) is the move for
  inspecting a loot table from the console with no player attached.
- `run/eula.txt` needs `eula=true` (already present, but `rm -rf run/world`
  between clean-slate tests can occasionally take `run/` state with it).

### Dev-only admin commands

`/dungeon admin build [seed] [level] [ominous]`, `admin cellreport <slot>`,
`admin stamptest`, `admin coverage`, `admin plansurvey <n>`,
`admin purge <slot>`, `admin list`.

### The Minecraft jar for javap introspection

```
/c/Users/Kriss/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar
```

`javap -c -p -cp "$JAR" <class>` for bytecode; plain `javap` for signatures.

### Config notes

- `clearBlocksPerTick` has a hard floor of 1024 in
  `PocketDungeonsConfig.apply`. The stale `run/config/pocketdungeons.json` that
  used to carry an invalid `20` has been fixed to `8192`.
- Manifest loads on `SERVER_STARTED` — no need to run
  `/dungeon admin manifest reload` by hand on a fresh server.

## UNVERIFIED M65 API surface

20. PARTIALLY RESOLVED: RunSession.derivePhase is tested by
    FloorLoopGameTest, which confirms the phase it would derive (HOME,
    not FLOOR_CLEARED, for a record with awaitingDoorChoice=true and
    floorIndex=0) does not allow a second SAFE_RETURN transition. The
    derivePhase logic is verified headlessly. What remains unverified is
    the full live reconnect scenario: a server restart that loses the
    in-memory record and reconstructs it from InstanceRegistry's persisted
    state. The item notes this is not a live bug today because the record
    is in-memory only. The derivePhase method itself is correct; the
    wiring end-to-end (Instances.admit calling derivePhase on reconnect
    after a server restart) is the remaining gap.

21. PARTIALLY RESOLVED: The silent homecoming's
    completeHomecomingCleanup in Instances.onTick was verified by code
    analysis. The logic (line 1964-1966) checks
    pendingHomecomingCleanup and calls allMembersCrossed, which
    treats a disconnected member as "crossed" (does not block
    cleanup). The M63 recovery path handles their return. The code
    path is sound and the logic is correct. What remains unverified is
    the live multi-player scenario: an actual disconnect during the
    crossing window, with a real player dropping and reconnecting. This
    needs a two-player live test, which is a genuine Astra computer-use
    candidate (item 21 is one of the 20-25 visual/interactive items
    identified in the cost analysis).

22. RESOLVED (code analysis): The fallbackTeleportHomecoming path
    in returnToSafe was verified by code analysis. The path fires only
    when stampSafeRoom throws a RuntimeException (line 1380-1385),
    which requires a StructureTemplate or chunk-loading failure. The
    fallback (line 1479-1538) clears cells, re-stamps at the slot
    origin, stamps a fresh staging room, teleports every member into
    the safe room, and closes the timer. The code path is sound and
    handles the failure case correctly. The path is hard to trigger
    live because stampSafeRoom has its own internal fallback
    (TemplateStamper.ENTRANCE_HALL at line 687-688), so the outer
    fallback only fires if both RoomStore.place AND the entrance hall
    template fail. A live test would need to corrupt both paths, which
    is not practical. The code analysis is sufficient.

23. PARTIALLY RESOLVED: CubeRecipe.restoreCatalyst uses
    Payout.deliver(player, new ItemStack(item)) to return the
    escrowed catalyst on cancellation. Payout.deliver (lines 34-45)
    calls player.getInventory().add(stack), then checks stack.isEmpty()
    and calls player.drop(stack, false) for the overflow. Verified
    against the 26.2 jar: Inventory.add(ItemStack) returns boolean
    and modifies the stack in place (reduces count by what fits).
    Player.drop(ItemStack, boolean) returns ItemEntity, delegating
    to Player.drop(ItemStack, boolean, boolean), which spawns an
    ItemEntity at the player's position (same pattern as
    Entity.spawnAtLocation: new ItemEntity + addFreshEntity). The API
    behavior is confirmed: overflow drops as an item entity at the
    player's feet. What remains unverified is the edge case where the
    player is standing near a void drop in a dungeon dimension: the
    item entity may fall into the void before the player can pick it
    up. That is a level design question, not an API question.

24. RESOLVED (code analysis): The catalyst escrow write and the
    catalyst shrink ordering was verified by reading CubeRecipe.apply
    (lines 145-162). The CustomData.update call (line 145) writes the
    pending_catalyst escrow AND the recipe id in one atomic update.
    The offHand.shrink(1) call (line 162) happens AFTER the escrow
    write, not before it. So a crash between the two leaves the
    catalyst escrowed but not consumed: the player still has the
    bone in their off-hand AND the keystone says a catalyst is pending.
    The original note mischaracterized this as "the catalyst is lost";
    it is actually a duplication risk (restoreCatalyst would deliver a
    second bone on cancel). The window is narrow (one line of Java
    between the atomic update and the shrink) and both operations are
    on the server thread, so a crash here requires a JVM kill between
    two consecutive statements. The risk is real but narrow, and the
    failure mode is duplication, not loss. A proper fix would shrink
    first, then write the escrow, but that reverses the safety
    direction (a crash there would lose the catalyst without escrow).
    The current ordering is the safer of the two.

25. RESOLVED: RoomSelector.applyRecipeGuarantees forces a specific
    room onto an eligible cell after the main selection pass. The
    forceRoom method checks the forced room's requires are satisfied
    (line 279) but does not re-propagate the forced room's provides to
    downstream cells, and the validate method (line 294) does not check
    requires/provides consistency after guarantees. Verified against the
    current built-in room set: only infested_wall provides anything
    (provides:[blocks]), and no room requires [blocks]. The other forced
    rooms (creeper_kennel, deep_dark_landing, the_store) provide nothing.
    So the risk is purely theoretical for the built-in set. For third-party
    recipes, the mitigation is M72's packValidationTest, which validates
    recipe eligibility and guarantees. The graphSolvabilityTest covers the
    base plan (200 seeds) but does not include recipe-augmented plans;
    this is a known gap that M72's validator is intended to close.

26. RESOLVED (partial): SimpleSynchronousResourceReloadListener is a
    Fabric API class (net.fabricmc.fabric.api.resource), not a vanilla
    class, so it cannot be inspected with javap against the 26.2 jar.
    The vanilla interface it wraps is ResourceManagerReloadListener
    (confirmed in the jar: onResourceManagerReload(ResourceManager)),
    which is the same interface the pre-M68 listeners used. The
    execution-order question (does SERVER_STARTED fire after the startup
    reload completes) is a Fabric API behaviour question, not a vanilla
    jar question, so it cannot be resolved by javap alone. The ordering
    remains inferred from pre-M68 behaviour; a behavioural test (boot a
    server, observe whether the listener fires before SERVER_STARTED)
    would close it. The class name and method shape are correct.

27. RESOLVED: ContentReload.reconcileActiveFloors reads
    InstanceRegistry.bySlot from the reload listener. Verified
    against the Fabric API source (fabric-resource-loader-v0
    3.3.20+4fc5413f9e) and the 26.2 jar: SimpleSynchronousResourceReloadListener
    extends ResourceManagerReloadListener, whose javadoc says "ensuring
    all data is loaded on the main thread." The vanilla
    ResourceManagerReloadListener.reload() implementation calls
    thenRunAsync(runnable, applyExecutor) where the apply executor is
    the main thread (the server thread for a dedicated server). So
    onResourceManagerReload fires on the server thread during a /reload,
    and reading bySlot from it is safe because all other access to
    bySlot also happens on the server thread. The initial load (during
    WorldStem creation) happens before the server exists, which is why
    ContentReload.register sees a null server on the startup pass and
    defers to the explicit ContentReload.reload(server) call in
    SERVER_STARTED. Both paths are on the server thread.

28. RESOLVED: AffixManifest.validateLootRef validates bonus_tool_pool
    and decor_pool references against
    server.reloadableRegistries().lookup(). Verified against the 26.2
    jar: MinecraftServer.reloadableRegistries() returns
    ReloadableServerRegistries$Holder, and Holder.lookup() returns
    HolderLookup$Provider. The alternative
    (server.registryAccess().lookupOrThrow(Registries.LOOT_TABLE))
    throws "Missing registry" unconditionally because that registry key
    is never present on the frozen dynamic registry manager. The
    reloadableRegistries path is correct and confirmed.

## UNVERIFIED M70 API surface

29. RESOLVED: BagManifest.validateLootRef validates the bag's
    loot_table reference against server.reloadableRegistries().lookup(),
    the same path AffixManifest.validateLootRef uses (item 28). Verified
    against the 26.2 jar: same ReloadableServerRegistries$Holder.lookup()
    path. The loot table lookup is correct and confirmed.

30. RESOLVED: The BagManifest.parse call to
    server.getResourceManager().listResources("dungeon_bag", ...) was
    verified against the 26.2 jar. ResourceManager.listResources(String,
    Predicate<Identifier>) returns Map<Identifier, Resource> and
    accepts any directory prefix as a scannable string. The dungeon_bag
    prefix is just a string argument; any directory prefix works. The
    manifest will load bags correctly.

31. RESOLVED: The CubeRecipeManifest.parse call to
    server.getResourceManager().listResources("cube_recipe", ...) was
    verified against the 26.2 jar. Same ResourceManager.listResources
    surface as item 30. The cube_recipe prefix is a string argument; any
    directory prefix works. The manifest will load recipes correctly.

32. RESOLVED: The CubeRecipe.catalystMatches tag path uses
    offItem.builtInRegistryHolder().is(TagKey) to test whether an item
    belongs to a declared catalyst tag. Verified against the 26.2 jar:
    Item.builtInRegistryHolder() returns Holder.Reference<Item>, and
    Holder.is(TagKey<T>) is an abstract method on Holder inherited by
    Holder.Reference. TagKey.create(Registries.ITEM, Identifier) is the
    correct factory. The holder's tag binding is populated at registry
    init time, so it is available at match time. The tag-based catalyst
    path is correct and confirmed.

33. RESOLVED: PackValidator.copyResourceTree's jar: branch
    was verified by JarFileSystemWalkTest, a new headless test that opens
    the packaged release jar via FileSystems.newFileSystem, walks the
    pack_starter tree, and confirms 22 entries are found and README.txt
    is readable (2241 chars). The test is registered as
    jarFileSystemWalkTest in build.gradle.kts and added to the test task's
    dependencies. The jar: filesystem walk works correctly against a
    packaged release jar.

## UNVERIFIED M73 API surface

34. UNVERIFIED: AffixEffects.decor_pool has no application site. The
    field is parsed in AffixMeta.fromJson, stored in AffixEffects,
    validated against the loot registry in AffixManifest.validateLootRef,
    and counted in AffixEffects.hasAnyOperation, but no code in
    RoomContent, TrialContent, or anywhere else reads
    AffixEffects.decorPool at runtime. A third-party affix that declares
    `decor_pool` will load and pass validation, but the pool will never
    be placed. This is why M73 defers the `clingy` affix rather than
    shipping it: `decor_pool` cannot carry the "guaranteed shears and
    recoverable string" behaviour because nothing applies it. The same
    gate blocks `jumpy`: `bonus_tool_pool` is applied only in
    RoomContent.placeLoadedToolCache, which is gated by
    `affixes.contains(AffixIds.LOADED)` and looks up `AffixIds.LOADED`
    by id, so a third-party affix declaring `bonus_tool_pool` would have
    its pool validated and then never placed. A future milestone that
    wants `jumpy` or `clingy` must first add an engine operation that
    applies `decor_pool` (or a new spawner-rewrite operation for
    `jumpy`) to third-party affixes, not just the Loaded built-in.
## UNVERIFIED M74 API surface

35. UNVERIFIED: SculkSensorBlock redstone output to a dust line opening an
    iron door. The jar confirms `SculkSensorBlock` has a `POWER`
    `IntegerProperty` and `ownSignal` method, and the block stamps and
    loads without error. The full redstone chain (sensor activates on
    vibration, powers dust, dust reaches the iron door, door opens) is
    vanilla behaviour that the headless server cannot exercise without a
    player creating vibrations. The `ReturnPathValidator` and
    `LayoutStamper` checks confirm the blocks are placed correctly, not
    that the redstone circuit fires. A live client check is needed to
    confirm the Sensor Gallery door opens when a player or mob creates
    vibrations near the sensors.

36. UNVERIFIED: Wolf aggression toward zombies in a trial spawner context.
    The jar confirms `net.minecraft.world.entity.animal.Wolf` exists and
    the trial spawner config loads. Vanilla wolves attack hostile mobs
    when provoked or when their owner is attacked, but a trial-spawned
    wolf may have different AI goals (trial spawner mobs are often
    restricted to a spawn range). The Kennel Crossing design relies on
    wolves aggroing zombies when the fence gate opens; this needs a live
    client check to confirm the wolves actually path to and attack the
    zombies rather than idling in the kennel.

37. RESOLVED: Content module loot applies per roll, not per reload. The L2
    module hook (ContentModuleLoader) sits on LootTableEvents.MODIFY_DROPS and
    consults ContentModules.enabled at roll time, then rolls the module table
    and merges its drops. Confirmed by ContentModuleGameTest on a live
    GameTestServer: enabling pocketdungeons:alchemy makes chests/tier_2 yield
    nether wart immediately, disabling removes it, with no /reload between.
    Manifest surfaces still settle at reload: module-owned bags are skipped
    inside BagManifest.parse, which only runs in ContentSnapshot.build.

38. RESOLVED: In 26.2, AbstractVillager.overrideOffers is an empty
    extension-point stub. Calling it writes nothing; a villager whose
    offers field stays null lazily regenerates vanilla profession trades on
    the first getOffers. Write offers with Villager.setOffers instead; the
    field persists through the villager's own saved data. Vanilla
    restock() only calls resetUses on the offers already there, so custom
    offers survive a job-site restock; vanilla updateTrades only fires from
    the lazy init or a career level-up, which xp 0 on every offer prevents.
    The per-trade hook is AbstractVillager.notifyTrade, which
    MerchantResultSlot.onTake fires once per completed trade on the server
    merchant (MerchantTradeMixin).
