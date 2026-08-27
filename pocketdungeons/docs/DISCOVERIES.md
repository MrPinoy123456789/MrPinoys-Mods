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

---

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
