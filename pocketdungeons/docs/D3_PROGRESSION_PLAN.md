# D3 Progression Plan

> **What this is:** the design pass after M9, promoted from
> `DOOR_LADDER_BRAINSTORM.md` sections 1-4 into a sequenced, buildable plan. It
> turns the keystone ladder from "the same three doors, scarier" into a two-tier
> map system where the level gates *which doors you can reach*, and adds the five
> Diablo-3-inspired sinks that drain the faucet the dungeons have been.
>
> **What this is not:** a status file. No checkboxes here. Status lives in
> `PROGRESS.md`'s successor (the README index + `plans/COMPLETED-MILESTONES.md`).
> This document defines the milestones; it does not track them.
>
> **Relationship to the brainstorm:** the brainstorm stays the scratchpad. Where
> this plan and the brainstorm disagree, this plan wins, because the decisions
> below are the ones that were actually made. The brainstorm's open questions
> (section 5) are resolved here where they shape structure, and carried forward
> as in-milestone tuning where they do not.

---

## Decisions locked before this plan was written

These four were the forks that change the plan's structure. They were settled
with the user before drafting; the rest of the open questions are tuning and
live inside the milestones below.

1. **Scope: the full package.** Two-tier doors, the level cap and mob scaling
   that give the ladder meaning past 10, and all five sinks. This is multiple
   milestones (M10-M17), sequenced in this document.
2. **The reframe is adopted.** The keystone level gates *which doors/maps are
   available*, not just how hard the same door is. Doors become maps; the recipe
   system becomes the adventure graph; `Affix.Kind.ELECTIVE` is retired. This is
   the load-bearing decision; everything else follows from it.
3. **Ominous becomes a map property.** It is a property of the door/map you are
   offered, not a modifier you elect. Both electives go: `FRAGILE` is deleted,
   `OMINOUS` migrates, `Affix.Kind.ELECTIVE` is removed. The enum becomes purely
   seeded.
4. **Fuel currency: left open.** The two-tier door mechanism is
   currency-agnostic. Echo shards (diegetically perfect, need loot-table work)
   and diamonds (already in tables, risk self-funding) are both still in play.
   M12 specifies the mechanism and leaves the currency as a config-selected
   variable, settled during implementation. See "Fuel currency" under M12.

**Not blocked by the lore decision.** `VISION.md` §9 says "no system may depend
on the fiction to make sense." Nothing in this plan does. The Herobrine Cube
(M17) borrows a lore-flavoured *name*, but its mechanic (extract a power, imbue
it elsewhere) is self-contained and would work under any name. If the §9
"not a lore project" line is revised, the Cube can keep its name; if it is not,
the Cube ships under a neutral name and nothing else changes. The plan does not
wait on `LORE.md`.

---

## Implementation context (read this before touching any file)

The rest of this plan names classes and fields freely. This section is the
ground truth for an implementer who has never opened the repo: where things
live, which patterns to copy, which traps already bit, and how to verify a
change without a Minecraft client. Read it once; every milestone below assumes
it.

### Toolchain and commands

- **Platform:** Minecraft `26.2`, Fabric Loader `0.19.3`, Fabric API
  `0.156.0+26.2`, Java 25 (`sourceCompatibility`/`targetCompatibility` =
  `VERSION_25`, `options.release = 25`). Minecraft ships unobfuscated since
  26.1, so there are **no mappings**; do not add Yarn or Mojang mappings.
- **Server-side only.** `fabric.mod.json` declares `"environment": "server"`
  and there is no `src/main/resources/assets/` directory. Nothing in this plan
  may introduce a client component. The vanilla dialog API
  (`net.minecraft.server.dialog`) is the one exception that is already in use:
  dialogs are sent as runtime `Holder.direct` values with no registry entry and
  no client install. See `DialogKit` and the "Dialogs" note below.
- **Build:** `./gradlew.bat build` (Windows) or `./gradlew build`. The mod
  root is `A:\MrPinoys Mods\pocketdungeons`.
- **Headless run:** `./gradlew.bat runServer --offline`. There is no client in
  this environment. The dev-server harness is a Python driver that pipes timed
  console commands into `runServer`'s stdin and reads stdout for the `Done (`
  boot marker; see `docs/DISCOVERIES.md` "Operational notes" for the exact
  shape and the two gotchas (native Windows Python, POSIX paths).
- **Pure-Java unit tests:** `./gradlew.bat doorMaskTest` runs the
  `DoorMaskTest` regression. The same `tasks.register<JavaExec>` shape is how
  any new pure-Java maths (affix thresholds, intensifier bands, fuel curves)
  should get a test task. Prefer adding a test over a live-only check.
- **Verify against the 26.2 jar before using any Minecraft API shape.** This is
  trap 1 in `DISCOVERIES.md` and it has shipped three bugs. The jar lives at
  `C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
  (POSIX form for Git Bash: `/c/Users/Kriss/.gradle/...`). Inspect with:
  `javap -cp "$JAR" net.minecraft.some.Class` for signatures,
  `javap -c -p -cp "$JAR" net.minecraft.some.Class` for bytecode when behaviour
  matters. Checking a method exists is not the same as checking what it does.

### The one-mixin budget

This mod has exactly one mixin: `mixin/CustomClickMixin`, which catches dialog
button payloads because Fabric API has no event for
`ServerboundCustomClickActionPacket` and vanilla's own hook has already
discarded which player clicked. **Everything else runs on stock Fabric API
events.** If a milestone seems to need a second mixin, say so in the report and
look for the Fabric event or the datapack route first; traps 14 and 15 in
`DISCOVERIES.md` are a worked example of that search paying off. The milestones
below flag the two places a mixin-shaped surface might tempt (M15 trim
duplication recipe, M17 craft-result interception); both have a datapack or
event alternative that should be exhausted first.

### Package layout

Every Java file lives in one package, `pocketdungeons`, with one sub-package
`pocketdungeons.mixin` for the single mixin. There are no sub-packages per
feature; do not introduce any. The full file list is under
`src/main/java/pocketdungeons/`. Resources live under
`src/main/resources/data/pocketdungeons/` with these subdirectories that the
milestones touch:

- `loot_table/chests/` : the tiered chest tables (`tier_1`, `tier_2`,
  `tier_3`, their `_ominous` variants, `supply_tier_*`, and the optional
  themed `_drowned` suffix tables). M13 authors gear here; M12 adds fuel
  entries here.
- `dungeon_theme/` : one JSON per theme (`name`, `processors`, optional
  `discoverable`, optional `loot_suffix`). M11's adventure graph references
  these ids.
- `dungeon_recipe/` : the recipe files M11 deletes and replaces with the
  adventure graph.
- `dungeon_room/` : room template metadata JSON. M11's boss room adds one
  here, plus a `.nbt` under `structures/`.
- `trial_spawner/` : data-driven trial spawner configs keyed by id, written
  from Java by `TrialContent`.

### The pure-Java discipline (copy this shape for any new maths)

`AffixMath`, `KeystoneMath`, `DoorMask`, `DifficultyProfile`, and `PayoutMath`
carry **no Minecraft imports**. They are plain `javac` classes so the
threshold/clamp/curve logic is trivial to test and impossible to get
subtly wrong inside a Minecraft import tangle. Any new arithmetic this plan
needs (fuel cost curves, reroll lapis curves, gamble emerald curves, equip-cap
counts, intensifier bands past 25, spawner-clear percentages) belongs in a new
or existing pure-Java class in this style, with a `doorMaskTest`-style task for
verification. The rule is: if it is a pure function of its arguments and has
no registry or world dependency, it does not import Minecraft.

### Patterns to copy verbatim

- **Config-named item:** `ConfiguredItem` wraps a config string id, resolves it
  lazily against `BuiltInRegistries.ITEM`, caches against the string, and logs
  once at first ask. Any new "what block/item does this feature use" field
  (`fuelItem`, reroll station block, gamble station block) is a
  `ConfiguredItem` resolved at `SERVER_STARTED` from a `warmUp()` call, the way
  `Keystone.warmUp()`, `CallingCard.warmUp()`, and `TrialContent.warmUp()` are
  registered in `RitualListener.register()`. The fallback convention is "X
  will fall back to minecraft:Y" in the consequence string.
- **Reloadable datapack resource:** `ThemeManifest`, `DungeonRecipes`, and
  `RoomManifest` all follow one shape: a `volatile current` holder, a
  `load(MinecraftServer)` that calls `server.getResourceManager()
  .listResources("<path>", id -> id.getPath().endsWith(".json"))`, sorts
  entries by id, parses each in a try/catch that collects rejections, and a
  `SimpleSynchronousResourceReloadListener` registered via
  `ResourceManagerHelper.get(PackType.SERVER_DATA)` that re-`load`s on
  `/reload`. M11's `AdventureGraph` loader copies this shape exactly, under a
  new resource path (`dungeon_adventure`).
- **Block-use interception (a station):** `RitualListener` owns this mod's one
  `UseBlockCallback.EVENT` registration. A new station (M14 reroll, M16
  gamble) that intercepts a specific block's right-click should either branch
  inside `RitualListener.onUseBlock` ahead of the lodestone check (the way the
  selector-door and calling-card branches already do), or, if the question is
  cleanly separable, register its own `UseBlockCallback` keyed on the
  configured block. Study `RitualListener` before adding either; it already
  handles the both-hands gate, the shift-to-place escape hatch, and the
  `RoomProtection` container/placement denial that must stay ahead of any new
  branch.
- **Item-use interception:** `SilenceListener` owns the one
  `UseItemCallback.EVENT` registration. M15's equip-time trim bonus is a
  different hook (slot change, not item use); see the M15 notes.
- **Dialog screen:** `DialogKit` is the narrowed vanilla-dialog API. Build a
  screen with `DialogKit.confirm` / `DialogKit.list` / `DialogKit.notice`,
  send it with `DialogKit.show(player, dialog)` only as the direct response to
  a click or command (never unprompted; a dialog is modal and seizes the
  screen). Buttons either run a fixed `/dungeon ...` command
  (`DialogKit.command`, the "tier A" path, no round trip) or submit a
  `CustomAll` payload to `DialogRouter` (`DialogKit.submit`, the "tier B" path,
  for "which list entry was clicked"). Context keys are `pd_`-prefixed because
  input keys overwrite context keys on collision. There is no dialog history
  stack: every "back to the list" is the handler rebuilding the list from
  current state. `DialogScreens` is where every screen lives; add new ones
  there.
- **Per-player persistent state:** `DungeonLog extends SavedData`, keyed by
  UUID, stored in the overworld's data storage (not per-dimension). Its
  `Entry` record is the schema. **Codec migration discipline:** every new
  field is added as an `optionalFieldOf` with a sane default, so an old save
  loads unchanged. When a field is retired, mark it superseded in the
  javadoc and keep the codec field; do not delete it on the first pass. This
  plan retires `keystoneAffix` (elective storage) and `recentThemes` (theme
  history) that way. The save file lives at
  `run/world/dimensions/minecraft/overworld/data/pocketdungeons/dungeon_log.dat`
  (trap 8 in `DISCOVERIES.md`); "no file in `world/data`" is normal, not a
  failure.
- **Loot table registry access:** `LootTables` holds the named table path
  constants and `validateAtStartup` confirms each one resolves. A table is
  resolved through `level.registryAccess().lookupOrThrow(Registries.LOOT_TABLE)`
  and `registry.getValue(Identifier)`. `TrialContent.applyLoot` and
  `placeCompletionChests` are the call sites to copy for "draw from a table by
  key". M13's new gear tables and M16's slot-keyed gamble tables register
  their path constants here and add them to the `ALL` startup check.
- **The keystone is a remote, not a save file.** `DungeonLog` is the
  authority for level and affix; the item stack renders that state and is
  never the source of truth. `Keystone.reconcile(player, level, affixes)`
  rewrites every stale remote in a player's inventory and ender chest on the
  instance watcher's interval. **The keystone name must stay a pure function
  of `(level, affixSet)`** (`AffixMath.name`); any new label that rolled
  randomness would churn on every reconciliation. This is why intensifier
  bands and affix thresholds are deterministic functions of level.

### Conventions that will bite if ignored

- **Punctuation:** no em dash and no double hyphen as punctuation, anywhere a
  person reads (chat, dialogs, item lore, command output, log lines, markdown,
  javadoc, commits). Use `:`, `;`, `,`, `()`, or two sentences. Command-line
  flags (`--offline`) and code operators (`i--`) are not punctuation and stay
  as they are. See `A:\MrPinoys Mods\CLAUDE.md`.
- **`Inventory.add(ItemStack)` returns "did I move any" and mutates the stack
  down to the remainder** (trap 3). Use `Payout.deliver` for any item grant;
  never the bare `if (!inv.add(stack))` idiom.
- **Container block replacement needs both `UPDATE_SUPPRESS_DROPS` and
  `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`** (trap 4). Use `TrialContent.FLAGS`
  / `RoomContent.FLAGS`.
- **`ClickEvent` is a sealed interface in 26.2** (trap 5). Use
  `new ClickEvent.RunCommand("/dungeon choose 1")` and
  `new ClickEvent.ShowDialog(Holder.direct(dialog))`; the old
  `new ClickEvent(Action.RUN_COMMAND, ...)` form will not compile.
- **A misspelt trial-spawner config id does not throw** (trap 6); the codec
  drops the field and the spawner silently keeps `FullConfig.DEFAULT`. Read
  ids back out (`/dungeon admin cellreport`) rather than trusting the write.
- **Headless testing cannot right-click, open a chest, or click a GUI button**
  (trap 10). Anything client-interactive goes in `docs/LIVE_TEST_PASS.md` as
  unverified, not claimed working. Every milestone's "Done when" is a mix of
  headless-verifiable (commands, registry, save state) and live-only (dialogs,
  station right-clicks, trim bonus in combat).
- **Check generated data, not just that generation ran** (carried lesson):
  user-reported bugs survived passes that confirmed a table parsed and the
  right item type appeared, but never inspected an enchantment component or
  compared a roll's size to the plan. For M13 specifically, inspect a drawn
  stack's `DataComponents.ENCHANTMENTS`, not just that a chestplate appeared.

### Key files map

| File | Role |
|---|---|
| `PocketDungeonsMod` | Entrypoint; registers every listener; `DUNGEON_LEVEL` key |
| `PocketDungeonsConfig` | All config fields, `apply`/`applyDefaults`, `readInt`/`readDouble`/`readString` helpers |
| `Instances` | Slot/party tick watcher, pad-contact detection, `completeRun` call site, `affixesFor`, `roomOwnerAt` |
| `RunLifecycle` | Entry, `chooseOffer`, `completeRun`, `exit`, `dropMember`; the game loop |
| `InstanceRecord` | One live instance's state (affixes, theme, timer, `chosenStep`, `completed`) |
| `Keystone` / `Keystones` | The remote item; `offers`, `mint`, `reconcile`; `grantOffer`/`returnTo` settlement |
| `Affix` / `AffixMath` | The affix enum, thresholds, intensifier bands, naming, depletion |
| `DungeonLog` | Per-player `SavedData`; `Entry` record and codec |
| `DungeonRecipes` / `RecipeMatcher` / `DungeonRecipe` / `ThemeHistory` | The recipe system M11 deletes |
| `ThemeManifest` / `ThemeOfferMath` | Theme registry and the deterministic three-theme deal |
| `TrialContent` / `LootTables` | Trial spawner + vault stamping, and the named loot-table constants |
| `RitualListener` / `SilenceListener` / `RoomProtection` | The block-use, item-use, and break/place hooks |
| `DialogKit` / `DialogScreens` / `DialogRouter` | The dialog API, the screens, and the `CustomAll` payload router |
| `ConfiguredItem` | Config-named item resolution pattern |
| `RoomStore` / `RoomBuilder` / `LayoutStamper` | Room capture, sealing, and stamping (M11 boss room, M12 door plumbing) |

---

## The dependency graph

```
M10  Ladder reframe          cap 25->100, mob scaling, affixes past 25,
     │                       drop Fragile, Ominous->map property, spawner gating
     │
M11  Adventures              recipe system -> adventure graph + currentTheme;
     │                       boss terminator room (new content)
     │
M12  Two-tier doors + fuel   door 1 = free/fuel-source/no-deplete;
     │                       doors 2/3 = fuel-cost/timed/depleting/level-gated
     │
M13  Gear loot pool          author tiered armour/weapon tables (CONTENT ONLY)
     │  (can run parallel to M10-M12)
     │
     ├── M14  Gear reroll (3.2, lapis)        depends on M13
     ├── M15  Armor trims (3.3)              depends on M13; builds equip-time
     │         │                              attribute plumbing M17 reuses
     │         └── M17  Herobrine Cube (3.5)  depends on M15 + M11
     └── M16  Kadala gamble (3.4, emeralds)  depends on M13
```

Two things to read off that graph before any work starts:

- **M13 is the long pole for the sinks, and it is content, not code.** None of
  the gear-touching sinks (3.2 reroll, 3.4 gamble, 3.5 Cube extraction) have
  anything to operate on until tiered armour/weapon loot tables exist. Today
  every `chests/tier_*.json` carries materials, blocks, food and consumables,
  and zero wearable or wieldable items (verified by grep across all twelve tier
  tables). M13 is pure JSON authoring with sensible enchantment weighting, and
  it can run in parallel with M10-M12 so it is ready when the sinks are.
- **M15 before M17 is not a scheduling preference, it is a code dependency.**
  The Cube's equipped-power check is the same equip-time attribute-modifier
  shape the trim bonus needs, generalised. M17 literally reuses M15's plumbing,
  so M15 must land first.

---

## M10 - Ladder reframe: the level gates access, difficulty lives on the map

**Goal:** raise the cap to 100, scale mob strength with the level, spread affix
thresholds across the new range, retire the elective affixes, and gate run
completion on spawner clearance. After M10, a level-100 key is not "the same
doors, scarier"; the ladder has room to climb and the run cannot be sprinted.

**Depends on:** nothing (M9 is code-complete, the tree is clean).

**Scope:**

- **Cap 25 -> 100.** `PocketDungeonsConfig.keystoneMaxLevel` from 25 to 100.
  `KeystoneMath.clampLevel` already takes `maxLevel` as a parameter, so the clamp
  itself needs no change; the config bump is the edit.
- **Mob strength scaling.** Apply attribute modifiers to mobs that spawn in the
  dungeon dimension, scaling with the run's keystone level. The mod already
  imports `ServerLivingEntityEvents` (`Instances.java:3`); the listener is the
  clean shape, one hook, no per-spawner NBT surgery. +1% per level is the
  starting curve, so a level-100 key faces roughly twice-strong mobs. The
  listener reads the level off the instance the mob spawned inside, not a global.
- **Affix thresholds past 25.** `AffixMath.seededCount` is hardcoded at
  `FIRST=5, SECOND=11, THIRD=17` and caps at three seeded affixes. With a 100
  cap, three thresholds leave 83 levels flat. Two options, decided in-milestone:
  add more thresholds (e.g. 5/11/17/25/40/60), or move to a percentage-based
  count (`level / 20`, capped). The percentage system is cleaner against an
  arbitrary cap; the fixed-threshold system preserves the "named band" feel.
- **Intensifier bands past 25.** `AffixMath.intensifier` caps at "Unhinged 21+".
  Extend the bands across the new range. The bands are a pure function of level
  and must stay watcher-stable (the instance watcher rewrites stale remotes in
  place; a label that rolled would churn on every reconciliation).
- **Drop `FRAGILE`, migrate `OMINOUS`.** Delete `Affix.FRAGILE` (it existed only
  as the cost of the +3 door, which the reframe removes). Move `OMINOUS` from
  `Kind.ELECTIVE` to `Kind.SEEDED`, then delete `Affix.Kind.ELECTIVE` entirely.
  `AffixMath.elective` and `DungeonLog`'s elective-affix storage become dead
  code: the elective set is always empty once the enum has no elective members.
  Mark the storage field superseded rather than deleting it on the first pass
  (save-format safety), and remove it once a migration confirms no live save
  carries elective data.
- **Spawner-gated completion.** Today `completeRun` fires on first pad contact.
  Gate it on clearing a fraction of the run's trial spawners first. Track
  spawner positions at stamp time (collect across encounter cells into
  `InstanceRecord`); on pad contact, check that N% have reached
  `TrialSpawnerState.COOLDOWN` (verified in 26.2: `TrialSpawnerBlockEntity`
  implements `TrialSpawner.StateAccessor` and exposes `getState()` returning the
  `TrialSpawnerState` enum, which has a `COOLDOWN` constant). 70-80% is the
  starting band. A total-completion seeded affix raises the threshold to 100%;
  its kiss is open (see open questions).

**Touch points:** `PocketDungeonsConfig.keystoneMaxLevel`; `Affix.java` (delete
`FRAGILE`, migrate `OMINOUS`, remove `Kind.ELECTIVE`); `AffixMath.java`
(thresholds, intensifier bands, `elective` becomes dead); `DungeonLog.java`
(elective storage superseded); `Instances.completeRun` (spawner gate); a new mob
scaling listener; `InstanceRecord` (spawner position tracking).

**Done when:** a level-100 key is mintable, mobs in its run hit roughly 2x
strength, the keystone name reads a sensible intensifier across the whole range,
`Affix.Kind.ELECTIVE` no longer exists, and a run that sprints to the pad
without clearing spawners is refused completion with a message.

**Open (tuning, in-milestone):** the threshold system (fixed vs percentage); the
exact intensifier band boundaries past 25; the spawner-clear percentage; the
total-completion affix's kiss (Q5 from the brainstorm; the curse is clear, the
gift is not, and the M4 rule says no kiss means no ship).

**Verify against the 26.2 jar:** the mob-scaling listener's exact event
signature (`ServerLivingEntityEvents` is imported already; confirm the
after-spawn hook that can mutate attributes before the mob ticks); the
`TrialSpawnerState` transition into `COOLDOWN` (does it require the spawner to
have been activated, or does an untouched spawner sit at `INACTIVE` and need
excluding from the count?).

**Implementation notes:**

- **Cap bump is one line and one default.** `PocketDungeonsConfig.keystoneMaxLevel`
  is `25` in both the field initialiser and `applyDefaults`; `apply` reads it
  with `readInt(root, "keystoneMaxLevel", 25, v -> v >= 1, ...)`. Change both
  `25`s to `100`. `KeystoneMath.clampLevel(level, maxLevel)` already takes the
  cap as a parameter, so no other clamp site needs editing; grep
  `keystoneMaxLevel()` to confirm no caller hardcodes 25.
- **Mob scaling is a new listener, not a per-spawner NBT write.** The clean
  shape is one Fabric event hook that mutates attributes on spawn, reading the
  level off the instance the mob is inside. `Instances.java:3` already imports
  `ServerLivingEntityEvents`; the existing registration is
  `ServerLivingEntityEvents.ALLOW_DEATH.register(...)` in `Instances.register`.
  Verify against the jar which `ServerLivingEntityEvents` callback fires
  after a mob is placed but before it first ticks (the candidate is
  `AFTER_LIVING_DEATH`/`MOB_SPAWN`-shaped; confirm the exact method name and
  whether it gives a `ServerLevel` you can map back to an `InstanceRecord` via
  `InstanceRegistry.bySlot`/origin bounds). The scale is a pure function:
  put `+1% per level` as `static double mobScale(int level)` in a new pure-Java
  class (e.g. `DifficultyProfile` already exists; extend it or add a sibling),
  with a `doorMaskTest`-style task. Apply it as
  `MutableAttributeInstance` modifiers on `max_health`, `attack_damage`, and
  `movement_speed` via `net.minecraft.world.entity.ai.attributes`
  (`Attributes.MAX_HEALTH` etc.); verify the modifier UUID and operation
  against the jar. The listener must no-op outside
  `PocketDungeonsMod.DUNGEON_LEVEL` and must read the level from the instance
  (the mob's chunk sits inside one slot's origin bounds; use
  `InstanceRegistry.bySlot` and `InstanceRecord.layout.keystoneLevel()`), never
  from a global.
- **Affix thresholds: prefer the percentage system, but keep it deterministic.**
  `AffixMath.seededCount(int level)` is the function to change. The fixed
  thresholds are `FIRST=5, SECOND=11, THIRD=17` (private static finals). A
  percentage form `min(level / 20, cap)` keeps the function pure and
  watcher-stable. Whichever is chosen, `AffixMath.seededFor(UUID, int)` calls
  `seededCount` and shuffles the `Kind.SEEDED` pool with
  `AffixMath.seed(owner, level)`; that shuffle must keep using the same
  `(owner, level)` seed so a given key's affixes stay stable across
  reconciliations. Add a test that asserts `seededCount` is monotonic and
  caps at the pool size.
- **Intensifier bands: extend `AffixMath.intensifier`.** Today it returns
  `"Baby"` (1-5), `"Lowkey"` (6-10), `"Highkey"` (11-15), `"Menace"` (16-20),
  `"Unhinged"` (21+). Extend the `21+` branch into named bands across 21-100.
  The function is pure and feeds `AffixMath.name`, which the watcher renders;
  keep it a pure function of `level` with no randomness. Add a test covering
  every band boundary.
- **Drop `FRAGILE`, migrate `OMINOUS`, remove `Kind.ELECTIVE`:** this touches
  three files in order.
  1. `Affix.java`: delete the `FRAGILE(...)` enum entry; change `OMINOUS`'s
     `Kind.ELECTIVE` to `Kind.SEEDED`; delete `ELECTIVE` from the `Kind` enum.
     `OMINOUS`'s `depletionMultiplier` stays `1` (it was never the depleting
     one; `FRAGILE` was). `Keystone.colourOf` has a `case FRAGILE ->` arm that
     must go, and the `switch` must stay exhaustive; verify the compiler
     catches any other `case FRAGILE` (grep `FRAGILE` across the tree).
  2. `AffixMath.java`: `elective(Set)` becomes a function that always returns
     an empty set (it filters on `Kind.ELECTIVE`, which no longer exists).
     `effective(UUID, level, elective)` still works because the elective
     argument is now always empty. Mark `elective` `@Deprecated` rather than
     deleting on the first pass.
  3. `DungeonLog.java`: `Entry.keystoneAffix` and its codec field
     `Codec.STRING.optionalFieldOf("keystone_affix", "")` stay (save-format
     safety); the `setKeystone` write still calls `AffixMath.join(AffixMath
     .elective(affixes))`, which now writes `""` always. Mark the field
     superseded in its javadoc. Do not remove the codec field until a migration
     confirms no live save carries elective data.
  `Keystones.grantOffer` and `Keystones.returnTo` both call
  `AffixMath.elective`; they keep compiling and now pass empty sets through.
  `Keystone.offers` builds `EnumSet.of(Affix.OMINOUS)` and
  `EnumSet.of(Affix.FRAGILE)` for doors 2 and 3; the `FRAGILE` line must go
  and the door-2/3 offer shape changes again in M12, so for M10 just make it
  compile (door 2 keeps `OMINOUS` as a seeded-style marker; door 3 becomes a
  plain `EnumSet.noneOf(Affix.class)` until M12 rewrites the offer shape).
- **Spawner-gated completion: the gate goes in `completeRun`, not the pad
  check.** `Instances`'s watcher calls `RunLifecycle.completeRun(server,
  record, player)` on first pad contact when `record.isKeystoneRun()` and the
  member is not already in `record.completed` (see the `stepped` block in
  `Instances`). The gate is: before `completeRun` does its work, check the
  spawner-clear fraction and refuse with a chat message if it is below
  threshold. Two pieces of state are needed:
  1. **Spawner positions, collected at stamp time.** `TrialContent
     .applyEncounter` places each trial spawner; it already knows `anchor`.
     Collect these positions onto `InstanceRecord` (a new `final
     Set<BlockPos> trialSpawners` field, populated by `TrialContent` as it
     stamps). Verify against the jar that
     `TrialSpawnerBlockEntity` exposes `getState()` returning
     `TrialSpawnerState` and that `TrialSpawnerState.COOLDOWN` is the
     "cleared" state (the plan's 26.2 note says it does; re-confirm, and
     confirm whether an untouched spawner sits at `INACTIVE` and must be
     excluded from the denominator, or counts as "not cleared").
  2. **The fraction check.** A new pure-Java helper
     `static boolean spawnersCleared(int cleared, int total, double
     threshold)` (or extend `DifficultyProfile`), with a test. The threshold
     (0.7-0.8) is a config field (`spawnerClearThreshold`, a `readDouble`).
     A seeded affix that raises the threshold to 100% is a new `Affix` member
     of `Kind.SEEDED`; its kiss is open (Q5). If it ships, its
     `depletionMultiplier` is `1` and it joins the `seededFor` pool.
  The refusal message goes to the player via `sendSystemMessage`; the run
  stays live and the pad stays contactable, so the player can go clear more
  spawners and step back on. This is live-only verification (a pad contact);
  record it in `LIVE_TEST_PASS.md`.

---

## M11 - Adventures: the recipe system becomes a descent graph

**Goal:** replace the backward-looking recipe match (`RecipeMatcher`'s ordered
tail over the last three themes) with a forward-looking adventure graph. Each
theme defines its possible next themes; the doors draw from the current theme's
transition set; a boss terminator ends the adventure and returns the player to
the entry-point themes. The folklore dynamic is preserved through a hidden
graph rather than hidden compositions.

**Depends on:** nothing strictly, but it is the door-selection mechanism M12
builds on, so it lands before M12.

**Scope:**

- **`AdventureGraph` replaces `DungeonRecipes` + `RecipeMatcher`.** A transition
  lookup is `currentTheme -> weighted set of nextThemes`, one map read. The new
  class is smaller than the two it replaces. Loaded the same way
  `DungeonRecipes.load` loads today (reloadable datapack resource under a new
  `dungeon_adventure` path), with the same rejection logging.
- **`currentTheme` replaces `recentThemes`.** `DungeonLog`'s 3-deep ordered
  window (`ThemeHistory.RECENT_LIMIT = 3`, with its codec field and `push`
  maintenance) becomes a single `currentTheme` string. `ThemeHistory` is deleted.
  The `recentThemes` codec field is kept and marked superseded for save-format
  safety, then removed once migration confirms.
- **Entry-point, descent, and boss nodes.** The graph has three node kinds:
  entry-point themes (the "overworld" set you return to after a boss), descent
  nodes (themes that branch further), and boss terminators (themes that end the
  adventure and reset `currentTheme` to an entry point). Depth is how many floors
  deep you are; the deeper you go, the rarer the nodes, per the brainstorm's
  weighted-rare-node argument.
- **The boss room is new content.** Today there is no boss; there are trial
  spawners and a terminal pad. A boss terminator is a new room type, a new
  encounter, and a new completion condition. This is net-new content work, not a
  reframe. It ties into M10's spawner gating: the boss could be the ultimate
  gated completion. Scope the boss as one proof encounter in this milestone;
  more can follow.
- **The graph stays hidden in-game.** The doors show three next options and
  nothing else. No "you are here" map, no node list, no depth counter. This is
  the same principle as the recipe system's "you can see the ingredients, not
  the recipe," extended to a graph. Surfacing the graph collapses the
  word-of-mouth dynamic the whole design reaches for.

**Touch points:** `DungeonRecipes.java`, `RecipeMatcher.java`,
`ThemeHistory.java` (deleted, replaced by `AdventureGraph`); `DungeonLog.java`
(`recentThemes` -> `currentTheme`); `DungeonRecipe.java` (replaced by a
transition record); the door-offer selection path that currently calls
`DungeonRecipes.match` to override the third door's theme; a new boss room
template and `dungeon_room/*.json` entry.

**Done when:** completing a theme offers doors drawn from that theme's
transition set, a boss terminator ends the adventure and resets to an entry
theme, and the recipe system's three files no longer exist.

**Open (tuning, in-milestone):** the authored transition graph (which themes
lead to which); rare-node weights; boss encounter design; whether the boss room
is a single proof or a small set.

**Verify against the 26.2 jar:** nothing new; the graph is pure JDK and the
loading path mirrors the existing one.

**Implementation notes:**

- **`AdventureGraph` is a new pure-Java class plus a new reloadable loader.**
  The graph itself is pure JDK (a `Map<String, List<Transition>>` where
  `Transition(String theme, int weight)` is a record), so put the graph model
  and the weighted-pick arithmetic in a no-Minecraft class in the
  `AffixMath`/`KeystoneMath` style, with a `doorMaskTest`-style task. The
  loader (`AdventureGraphs` or similar) copies `DungeonRecipes.load` exactly:
  `volatile current` holder, `load(MinecraftServer)` calling
  `server.getResourceManager().listResources("dungeon_adventure", id ->
  id.getPath().endsWith(".json"))`, sorted entries, try/catch rejection
  collection, and a `SimpleSynchronousResourceReloadListener` registered via
  `ResourceManagerHelper.get(PackType.SERVER_DATA)` (mirror
  `ThemeManifest.register`). Validate every transition's theme id against
  `ThemeManifest.current().byId(...)` the way `DungeonRecipes.validateThemes`
  does. Register the load in `Instances.register`'s `SERVER_STARTED` block
  alongside `DungeonRecipes.load(server)` (which itself goes away), and in
  the reload listener alongside `DungeonRecipes.load(value)`.
- **Three node kinds are fields on the transition record, not separate
  classes.** A node is `entry`, `descent`, or `boss`. The graph JSON is one
  file per theme: `{ "kind": "descent", "next": [ {"theme": "prismarine",
  "weight": 3}, ... ] }`. Entry nodes have `next` but no boss; boss nodes
  have no `next` and instead reset `currentTheme` to a weighted entry set.
  Depth-based rare-node weighting is a property of the pick function: the
  deeper the current descent chain, the lower the weight on further descent
  and the higher on a boss. Keep the pick deterministic from
  `(owner, currentTheme, depth)` using `AffixMath.seed` so a given player's
  offered doors are stable across the watcher interval (the same
  watcher-stability rule that governs the keystone name).
- **`currentTheme` replaces `recentThemes` on `DungeonLog.Entry`.** Add a new
  `String currentTheme` field to the `Entry` record with
  `Codec.STRING.optionalFieldOf("current_theme", "")`. Keep the existing
  `recentThemes` field and its codec (`optionalFieldOf("recent_themes",
  List.of())`) marked superseded; do not delete on the first pass. The
  `recordTheme(player, theme)` method currently calls
  `ThemeHistory.push(previous.recentThemes(), theme)`; replace its body with
  a write of the new `currentTheme` (and, on a boss node, the reset to the
  chosen entry theme). `ThemeHistory` becomes dead; mark it superseded, do
  not delete yet.
- **The door-offer selection path is `Keystone.offers`.** Today it calls
  `ThemeOfferMath.pick(owner, level, discoverableIds())` for the three themes
  and `DungeonRecipes.current().match(recentThemes)` to override the third.
  Replace the `match` call with `AdventureGraphs.current().pick(owner,
  currentTheme, depth)` returning three next-themes from the current theme's
  transition set. `ThemeOfferMath.pick` may stay as the fallback for entry
  nodes (the "deal three discoverable themes" shape) or be folded into the
  graph; either way the three offers come from the graph once a
  `currentTheme` is set. `Keystone.offers`'s `recentThemes` parameter becomes
  `currentTheme`; update the two call sites (`Keystones.grantOffer` via
  `completeRun`, and `RunLifecycle.chooseOffer`).
- **Delete the recipe system only after the graph is wired and verified.**
  `DungeonRecipes`, `RecipeMatcher`, `DungeonRecipe`, and `ThemeHistory` are
  the four files to delete. Delete the `dungeon_recipe/` resource directory
  and its one shipped file (`drowned_vault.json`). Do this in the same
  milestone once the adventure graph produces correct door offers, not before;
  the recipe system is the only thing keeping door offers working while the
  graph is half-built.
- **Landed divergence: the boss lives in the terminal cell, not a new
  authored room.** This plan originally called for one hand-authored `.nbt`
  plus one `dungeon_room/*.json` entry (copying `encounter_zombie.json`'s
  shape). The implementing session had no live Minecraft client and no way to
  open a structure block to author or capture a new structure, so
  `BossContent.spawn` places the boss directly into the terminal cell's
  existing `exit_hall` template instead, the one room every procedural
  dungeon already stamps at a single fixed rotation (the same fact
  `TrialContent`'s reward-chest placement leans on). Everything else shipped
  as written: the boss entity is a tagged vanilla mob (`minecraft:ravager`)
  with scaled attributes reusing M10's `Instances.applyMobScale` machinery
  (a steeper multiplier, `BossContent.BOSS_SCALE_FACTOR`), not a custom
  entity, and the completion condition ties into M10's spawner gate exactly
  as specified: reaching the terminal pad on a boss-themed run with the boss
  alive refuses completion the same way an uncleared spawner does
  (`RunLifecycle.completeRun`). A bespoke boss arena, and a `dungeon_room`
  entry of its own, remain future work for whoever next has a client
  attached to author or capture one. The boss room is still live-only
  verification.
- **Keep the graph hidden.** No "you are here", no node list, no depth
  counter in any dialog or chat. `DialogScreens.doorOffer` already shows only
  the one door's theme and affix; it stays that way.

---

## M12 - Two-tier doors: the Nephalem/Greater Rift split, plus fuel

**Goal:** door 1 becomes the free, fuel-producing, non-depleting farming tier;
doors 2 and 3 become the timed, fuel-consuming, depleting, level-gated Greater
tier drawn from the adventure graph. This is what makes "the level gates which
doors are available" concrete rather than aspirational: a level-100 key reaches
door-2/3 offers a level-10 key cannot open at all.

**Depends on:** M10 (level gates access) and M11 (adventure graph for door
selection).

**Scope:**

- **Door 1: free, fuel-producing, non-depleting.** Untimed or generously timed,
  spends no fuel, never depletes the keystone on failure. Its second job (the
  first is being the safe option) is producing fuel: a door-1 run pays out the
  fuel currency at a modest rate. `KeystoneMath.deplete`'s "a keystone never goes
  to zero" principle is already load-bearing; the free door is the same
  principle applied to fuel.
- **Doors 2 and 3: Greater tier.** Keep the clock, the depletion, and the level
  gate they have today, and additionally cost fuel. Drawn from the adventure
  graph (M11). Level-gated: a door-2/3 offer only appears if the keystone level
  clears its threshold, so a low key literally cannot reach the premium path.
- **Fuel currency (open variable).** The mechanism is currency-agnostic. Two
  candidates remain:
  - **Echo shards:** diegetically perfect (the keystone is a recovery compass,
    crafted from echo shards, so the same item that mints the key powers the
    premium doors). Need adding to tier 2/3 loot tables first, weighted low. A
    content edit, not a code change.
  - **Diamonds:** already in the tables, no content prerequisite. Risk:
    self-funding, since premium rooms drop diamonds. Mitigation is a loot-table
    edit either way (premium rooms must drop *different* loot, not more fuel).
  The plan settles the currency during M12 implementation by making it a
  config-selected `fuelItem` (the `ConfiguredItem` pattern `Keystone` and
  `TrialContent` already use), defaulting to whichever the loot-table work
  supports first. The two ladder-scale and gear-scale sinks stay on different
  currencies regardless (emeralds for the gamble, lapis for the reroll), per the
  orthogonality argument.
- **Loot-table edits.** Add the fuel currency to the free door's payout (and to
  tier 2/3 tables if echo shards are chosen). Ensure premium rooms drop
  *different* loot (decoratives, theme blocks, the provenance materials) rather
  than more fuel, so the loop does not feed itself.
- **Pacing the free door.** An unlimited, non-depleting door risks becoming the
  only door anyone takes. Whether door 1 gets its own shorter timer, and how
  much fuel a door-1 run produces relative to what a door-2/3 run costs, are
  settled in-milestone so the loop neither stalls (never enough fuel to push)
  nor trivializes (door 1 alone drowns the sink).
- **Deferred: a crafted fourth door.** A design pass considered a Herobrine-Cube
  ritual (8 obsidian plus flint and steel, in the shape of a nether portal) that
  crafts a vanilla door item marked with a tier, placed by the player at a
  fourth selector position and read by `RitualListener` before the placement
  consumes the marker (a door has no block entity, so `custom_data` does not
  survive becoming a block). The recipe ships with no unlock advancement, so it
  is craftable but never listed, which is a hidden-recipe discovery mechanic
  for free. Deliberately out of scope for this milestone: it is additive and
  blocks nothing here, and what the door offers depends on the adventure graph
  (M11) and the cap curve (M10) settling first. If it is picked up later, it
  should gate `lootTier` itself (`min(levelTier, clearedTier)`, both landing
  together, since a tier gate with no door to clear it strands the ladder at
  tier 1) rather than shipping as a cosmetic extra. See `DISCOVERIES.md` traps
  13 to 17 for what was verified against the 26.2 jar before deferring: the
  door-block material list, the recipe-component and hidden-recipe mechanics,
  and the placement/capture pitfalls.

**Touch points:** the door-offer plumbing (`Keystone.offers`,
`Keystones.grantOffer`, `DialogScreens.doorOffer`, the `chooseOffer` path);
`KeystoneMath.deplete` (door 1 exempt); `InstanceRecord` (fuel cost / fuel
produced); `PocketDungeonsConfig` (fuel item, door-1 timer, fuel rates); the
`chests/tier_*.json` tables (fuel currency entries, premium-room loot
differentiation).

**Done when:** a broke player always has the free door, the free door pays out
fuel, doors 2/3 refuse entry without enough fuel, and a level-10 key does not
see the door-2/3 offers a level-100 key does.

**Open (tuning, in-milestone):** the fuel currency choice; the fuel cost per
Greater door; the fuel production rate of the free door; whether door 1 has its
own timer and how long.

**Verify against the 26.2 jar:** nothing new; the `ConfiguredItem` pattern and
the door-offer plumbing already exist.

**Implementation notes:**

- **The offer shape changes here, not in M10.** M10 left `Keystone.offers`
  returning three offers with `OMINOUS` on door 2 and a plain set on door 3.
  M12 rewrites `Keystone.offers` so door 1 is the free/fuel-source tier and
  doors 2/3 are the Greater tier. The `Offer` record (`int level, EnumSet<Affix>
  affixes, int step, String theme`) gains the tier distinction; the clean way
  is a new field (`boolean free` or an `enum Tier { FREE, GREATER }`) rather
  than overloading `step`. `Keystone.Offer.ominous()` stays (ominous is now a
  map property from M10, but the offer still carries it for rendering).
- **Door-offer plumbing to edit, in call order:**
  1. `Keystone.offers(UUID, int, ...)` builds the three offers; this is where
     door 1 is marked free and doors 2/3 are marked Greater, level-gated, and
     fuel-costed.
  2. `DialogScreens.doorOffer(Offer, step, heading)` renders one door; add the
     tier, the fuel cost, and the level gate to the body text. Door 1's dialog
     says "free, pays fuel"; doors 2/3 say "costs N fuel, level-gated at L".
  3. `RitualListener.sendDoorOffer` is the entry from the lobby selector
     door; it stays the same shape, just passes the new `Offer`.
  4. `RunLifecycle.chooseOffer(player, step)` settles the choice. This is
     where the fuel spend and the level-gate refusal happen: before
     `Keystones.grantOffer`, check the player's fuel inventory (count of
     `fuelItem`) and refuse with a message if doors 2/3 are chosen without
     enough; check the keystone level against the door's threshold and refuse
     if the level is too low. Door 1 never refuses on fuel.
  5. `Keystones.grantOffer` writes the chosen level/affix; it does not need
     the tier, but the fuel spend must happen here (or in `chooseOffer`
     immediately before it) so a successful choice is atomic with the spend.
     Use `Payout.deliver`'s inverse (a stack shrink) for the spend, not bare
     `inventory.add`.
- **`KeystoneMath.deplete` already encodes "a keystone never goes to zero".**
  Door 1's non-depleting property is the same principle applied to fuel: a
  door-1 run never costs fuel, and a failed door-1 run never depletes the
  keystone. The existing `Keystones.Outcome` enum (`TIMED_OUT`, `LATE`,
  `NO_CHANGE`) drives `Keystones.returnTo`; door 1's runs should settle as
  `NO_CHANGE` always (no depletion, no fuel cost), which may mean carrying the
  tier onto `InstanceRecord` so `returnTo` can skip depletion for free-door
  runs. Add a `boolean freeDoor` (or the tier enum) to `InstanceRecord`,
  set at `chooseOffer` time.
- **Fuel currency is a `ConfiguredItem`, config-selected.** Add
  `fuelItem` to `PocketDungeonsConfig` (string id, default
  `minecraft:echo_shard` or `minecraft:diamond`), a `ConfiguredItem` for it
  resolved in a new `warmUp()` registered from `RitualListener.register` (or
  wherever the door plumbing lives). Add `door1TimerSeconds`,
  `fuelCostPerGreaterDoor`, `fuelPerFreeRun` (or a small curve) as config
  fields with `readInt`/`readDouble`. The fuel count check reads the
  resolved item and counts it in the player's inventory via
  `player.getInventory().countItem(...)`.
- **Loot-table edits are JSON only.** Add the fuel currency to the free
  door's payout table (the `supply_tier_*` tables are the natural home, or a
  new `chests/fuel_payout.json`) at a low weight. If echo shards are chosen,
  add them to `tier_2`/`tier_3` and their `_ominous` variants. Ensure
  premium (Greater) rooms drop *different* loot (decoratives, theme blocks,
  provenance materials), not more fuel, so the loop does not feed itself;
  this is an edit to the `tier_*` tables' pool composition. All new table
  path constants go in `LootTables` and its `ALL` startup check.
- **Landed decision: door 1's payout is a guaranteed direct grant
  (`Fuel.grant`), not a weighted loot-table entry.** This is a mechanism
  choice within the currency decision this bullet already delegates to
  implementation, not a scope cut: the plan's currency table lists
  self-funding as the risk to design around, and a guaranteed grant closes it
  completely rather than mitigating it with weight tuning. It also keeps
  `fuelPerFreeRun` an actual, load-bearing config field: a JSON `set_count`
  cannot read `PocketDungeonsConfig` at runtime, so a config-driven amount
  needs the grant to happen in Java regardless of loot weight. Consequence:
  echo shards were never added to any loot table, tier 2/3 included, since
  door 1 is the currency's only source by construction. If the fuel
  production rate ever needs to feel less like a fixed allowance and more
  like a drop, revisit this as a loot-table change rather than assuming the
  code path stays a flat grant forever.
- **Pacing the free door is config tuning, not code.** `door1TimerSeconds`
  (if door 1 gets its own shorter clock) and the `fuelPerFreeRun` /
  `fuelCostPerGreaterDoor` ratio are settled in-milestone by playtesting.
  Start with a ratio where roughly N free runs fund one Greater run, and
  adjust. The free door's timer, if separate, is a new branch in
  `KeystoneMath.timerSeconds` or a second config field read at
  `RunLifecycle.enter` time based on the chosen tier.
- **The deferred fourth door is out of scope; do not build it here.** Its
  design (a crafted, marked vanilla door, placed at a fourth selector
  position, read by `RitualListener` before placement consumes the marker)
  is recorded above and in `DISCOVERIES.md` traps 13-17. It depends on M10
  and M11 settling first. If picked up later, it gates `lootTier` itself.

---

## M13 - Gear loot pool (content, no Java)

**Goal:** author tiered armour and weapon loot tables so the gear-touching sinks
have something to operate on. This is the one prerequisite in the whole plan
that is "author new loot content before any code is worth writing," not "verify
against the jar."

**Depends on:** nothing. Can run in parallel with M10-M12.

**Scope:**

- **Per-slot, per-tier gear pools.** Author armour and weapon entries across the
  three tiers, with sensible enchantment weighting per tier. A tier-1 pool is
  iron-grade gear with low enchantment rolls; a tier-3 pool is diamond/netherite
  with higher rolls. The pools are new JSON under
  `data/pocketdungeons/loot_table/chests/`, or new tables the sinks draw from by
  `ResourceKey<LootTable>` (the same mechanism `TrialContent`/`LootTables`
  already use for chests, keyed by slot for the gamble).
- **Provenance holds.** The gear follows the `VISION.md` §3.6.1 tier palette:
  tier 1 is stone/iron/wood, tier 2 is deepslate/copper, tier 3 is end
  stone/ancient-city materials. A piece's tier is readable off the table it came
  from, which the reroll station (M14) and the gamble (M16) both need.
- **Self-sufficiency check.** `VISION.md` §3.7: nothing required for progression
  may live outside the dungeon loop. Gear from loot tables satisfies this. The
  enchantment weighting must not assume an enchanting table exists outside the
  dungeon (it does not, under §3.7); pre-rolled enchantments on the gear are the
  supply, and the reroll station (M14) is the player's lever over them.

**Touch points:** new `chests/*.json` loot tables; possibly new
`loot_table/gear/*.json` tables for the slot-keyed pools the gamble draws from.

**Done when:** a tier-3 run can drop a diamond chestplate with enchantments, a
tier-1 run drops iron-grade gear, and the gamble (M16) and reroll (M14) have
tables to read from.

**Open (tuning, in-milestone):** the exact item and enchantment distribution per
tier; whether the gamble draws from the chest tables or its own slot-keyed
tables; enchantment weighting curves.

**Implementation notes:**

- **This milestone writes zero Java.** It is JSON authoring under
  `src/main/resources/data/pocketdungeons/loot_table/`. The only Java-adjacent
  touch is registering any new table path constants in `LootTables` and its
  `ALL` startup check, so a missing table is a boot-time error rather than a
  silently empty chest (trap 6's cousin).
- **Copy the existing table shape.** `tier_1.json` is the template: a
  `minecraft:chest` table with `pools` of `entries`, each entry a
  `minecraft:item` with `weight` and `functions` (`minecraft:set_count`,
  `minecraft:set_enchantments`). Author gear entries the same way: a
  `minecraft:item` entry for `minecraft:iron_chestplate` etc., with a
  `minecraft:set_enchantments` function carrying weighted enchantment levels.
  Verify the `set_enchantments` function shape against the jar (the
  `enchantments` map and the `levels` provider form) before authoring; a
  misspelt enchantment id in a loot function is silently dropped, the same
  way a misspelt trial-spawner config id is (trap 6).
- **Per-slot, per-tier pools.** Decide early whether the gamble (M16) draws
  from the chest tables or its own slot-keyed tables, because it changes what
  to author. The cleaner answer for the gamble is separate slot-keyed tables
  (`loot_table/gear/<slot>_<tier>.json`, e.g. `gear/chestplate_1.json`), so a
  gamble draw cannot out-produce a run's chests and the chest tables stay
  general. If so, author both: gear entries folded into the existing
  `chests/tier_*` tables (so runs drop gear), and the slot-keyed
  `gear/*` tables (so the gamble has a clean draw source). The slot-keyed
  tables can `extend` or simply duplicate the relevant entries; vanilla loot
  tables have no inheritance, so duplication is the mechanism.
- **Provenance and the tier palette.** `VISION.md` §3.6.1 fixes the palette:
  tier 1 is stone/wood/iron/moss, tier 2 is deepslate/copper/prismarine/crying
  obsidian, tier 3 is end stone/ancient-city materials. Gear follows the same
  tiering: tier 1 iron-grade, tier 2 iron/diamond-grade with copper accents,
  tier 3 diamond/netherite. A piece's tier must be readable off the table it
  came from, because M14 (reroll cost) and M16 (gamble cost) scale with tier.
  The clean signal is "which table did this drop from"; carry that as a
  `custom_data` tag on the gear (`pocketdungeons.tier = N`) written by a
  `minecraft:set_components`-style function, so the reroll and gamble stations
  can read it off the stack without a registry lookup. Verify the
  `set_components`/`custom_data` loot function shape against the jar.
- **Self-sufficiency (§3.7): pre-rolled enchantments are the supply.** Do not
  assume an enchanting table exists outside the dungeon. The gear ships with
  its enchantments already rolled; the reroll station (M14) is the player's
  lever over them. Author enchantment weighting per tier: tier 1 low rolls
  (level 1-2 single enchantments), tier 3 higher rolls (level 3-4, multiple).
  Use vanilla's enchantment ids (`minecraft:sharpness`, `minecraft:protection`,
  etc.); verify any id against the jar before using it.
- **Landed correction: the enchanting mechanism is `enchant_with_levels`, not
  `set_enchantments`.** The bullets above assumed `set_enchantments` could
  carry "weighted enchantment levels". Verified against the 26.2 jar, it
  cannot: `SetEnchantmentsFunction` holds a
  `Map<Holder<Enchantment>, NumberProvider>` and its `run` applies **every**
  entry in that map through `EnchantmentHelper.updateEnchantments`. The levels
  can be ranges, but the *set* of enchantments is fixed, so every tier-3
  chestplate would have come out with an identical enchantment list. Three
  problems with that, which is why the tables ship on
  `minecraft:enchant_with_levels` with `options: "#minecraft:on_random_loot"`
  (the shape vanilla's own `chests/trial_chambers/*` tables use) instead:
  1. It would have made M14's reroll station pointless. Rerolling is only a
     decision if you do not already know what every drop of that item carries.
  2. `set_enchantments` does not check what the item supports, so keeping a
     bow from rolling Protection would have meant hand-partitioning the
     enchantment pool per slot and never making a mistake in it.
     `enchant_with_levels` is slot-correct by construction.
  3. The plan's own stated intent ("tier 1 low rolls, tier 3 higher rolls,
     multiple") is exactly what an enchanting-level curve expresses natively:
     tiers ship as uniform level ranges 5-15, 15-25 and 25-35 against vanilla's
     1-30 enchanting scale.
  The `tier` provenance marker is still written with `set_components`, which
  was verified to work as the plan assumed.
- **Verification is data inspection, not just "it parsed".** After a headless
  run that loots a chest, inspect a drawn stack's `DataComponents
  .ENCHANTMENTS` and `CUSTOM_DATA` (via a `/dungeon admin cellreport`-style
  command or a temporary debug command), not just that a chestplate appeared.
  This is the carried lesson in `DISCOVERIES.md`: bugs survived passes that
  confirmed the item type but never inspected the components. Add a temporary
  command that dumps a stack's components if one does not exist.

---

## M14 - Gear reroll station (3.2): a lapis sink, orthogonal to fuel

**Goal:** a room station that rerolls one enchantment on a piece of gear, the
player's choice of which, costing lapis that scales with the item's tier. This
is the gear-scale sink; fuel (M12) is the ladder-scale sink. The two answer
different halves of "what is the level-100 player working toward": fuel is why
level 100 is worth reaching, lapis is why the gear it drops is worth using.

**Depends on:** M13 (gear exists in the loot).

**Scope:**

- **Block interception, not vanilla enchanting.** A themed block (the
  `ConfiguredItem` pattern covers "what block, configurable, sane default")
  right-clicked opens a reroll dialog, following the `RitualListener` house
  pattern of intercepting a specific block's use rather than replacing a vanilla
  one. A player's actual enchanting table keeps doing exactly what vanilla
  enchanting tables do. The reroll is additive.
- **One enchantment at a time.** The dialog (`DialogKit`/`DialogScreens`, the
  same vanilla-dialog mechanism the door offers and party roster already use)
  lists the item's current enchantments; the player picks one. That enchantment
  is replaced with a different one drawn from the valid pool for that item type
  (excluding the one just removed and any already on the item), at a random
  level within that enchantment's normal range. Every other enchantment is
  untouched. A full reroll was considered and rejected: one at a time reads as a
  controllable decision with a target, and it cannot produce a strictly worse
  item than the one that walked in.
- **Cost scales with item tier, not enchantment level.** The gear carries a tier
  signal (the table it came from). A tier-1 drop rerolls cheap; deep-key gear
  costs more lapis. Same shape as `KeystoneMath.deplete`'s cost curve: the
  further in you are, the more each decision costs, never so much that the door
  closes.
- **Gating.** Whether the station is available from the first room or unlocked
  behind a keystone level / completed adventure is open. The fuel-gated-door
  precedent argues for *some* gate, since an ungated sink stops sinking once
  lapis overflows.

**Touch points:** a new station class (the `RitualListener` block-use shape); a
new `DialogScreens` screen (the reroll picker); `PocketDungeonsConfig` (reroll
block, lapis cost curve); the `chests/tier_*.json` tables (lapis entries, if not
already present).

**Done when:** a player right-clicks the station with a piece of gear, picks an
enchantment, pays lapis, and gets back the same item with that one enchantment
replaced and the rest untouched.

**Open (tuning, in-milestone):** the lapis-per-tier curve; whether the station is
gated and how; whether premium doors should avoid dropping extra lapis (the same
self-funding warning as fuel).

**Verify against the 26.2 jar:** the enchantment pool lookup for an item type
(how vanilla's own table avoids offering a duplicate; the data structure behind
valid-enchantments-per-item); reading and writing `DataComponents.ENCHANTMENTS`.

**Implementation notes:**

- **The station is a `UseBlockCallback` branch, study `RitualListener` first.**
  A new station class (e.g. `RerollStation`) intercepts right-click on a
  configured block. The cleanest path that respects the one-`UseBlockCallback`
  ownership in `RitualListener` is to add a branch in
  `RitualListener.onUseBlock` ahead of the lodestone check (the way the
  selector-door and calling-card branches already do), keyed on the configured
  reroll block id. If the question is cleanly separable, a second
  `UseBlockCallback.EVENT.register(...)` in `RerollStation.register` is also
  acceptable; either way, the both-hands gate, the shift-to-place escape, and
  the `RoomProtection` denial order in `RitualListener` are the pattern to
  preserve. Add `RerollStation.register()` to `PocketDungeonsMod.onInitialize`.
- **The block is a `ConfiguredItem`-resolved block id.** Add `rerollBlock`
  (string id, default e.g. `minecraft:smithing_table` or a themed block) to
  `PocketDungeonsConfig`, resolve it via a `ConfiguredItem` in a
  `RerollStation.warmUp()` registered from `RitualListener.register`. The
  station must not intercept a vanilla smithing table's normal behaviour for
  non-gear clicks; the branch fires only when the held item carries the
  `pocketdungeons.tier` custom_data tag from M13 (the positive test, the way
  `Keystone.isKeystone` is a positive test).
- **The dialog is a `DialogScreens` screen built with `DialogKit`.** List the
  held item's current enchantments (read from `DataComponents.ENCHANTMENTS`,
  which is an `ItemEnchantments` component; verify its accessor shape against
  the jar). Each enchantment is a button that submits a `CustomAll` payload to
  `DialogRouter` (the "tier B" path, because the click carries "which
  enchantment") with the slot index in a `pd_`-prefixed context key. The
  router dispatches to a `RerollStation` handler that performs the reroll.
  Use `DialogKit.list` for the enchantment list and `DialogKit.closeButton`
  for the exit.
- **One enchantment at a time, never a full reroll.** The handler: read the
  item's `ENCHANTMENTS`, remove the chosen enchantment, pick a different one
  from the valid pool for that item type (verify the
  valid-enchantments-per-item data structure against the jar; vanilla's
  `Enchantment` has item-type predicates), excluding the one just removed and
  any already on the item, at a random level within that enchantment's normal
  range. Write the modified `ItemEnchantments` back via
  `stack.set(DataComponents.ENCHANTMENTS, ...)`. Use `Payout.deliver` to
  return the modified stack (it replaces the held stack). The "never strictly
  worse" property is a test: add a pure-Java helper that, given the current
  set and the replacement, asserts the new set is not a subset of the old.
- **Cost scales with the item's tier, read off the `pocketdungeons.tier`
  custom_data tag** (set by M13). A new pure-Java helper
  `static int rerollCost(int tier)` (or extend `PayoutMath`), with a test.
  The config field is `rerollLapisPerTier` (a `readInt` curve or a small
  list). Spend lapis by counting `minecraft:lapis_lazuli` in the inventory
  and shrinking via the `Payout.deliver` inverse; refuse with a message if
  the count is short. Add lapis entries to the `chests/tier_*` tables if they
  are not already present (check first; the supply tables may already carry
  some).
- **Gating is open; if gated, gate on keystone level.** The fuel-gated-door
  precedent argues for a gate so an ungated sink stops sinking once lapis
  overflows. If gated, the gate is a keystone-level threshold (read from
  `DungeonLog.get(player).keystoneLevel()`), config-fielded as
  `rerollUnlockLevel`. An ungated station is also acceptable for the first
  pass; the gate can be added in-milestone.
- **Live-only verification.** The station right-click, the dialog, and the
  returned stack are all client-interactive; record them in
  `LIVE_TEST_PASS.md`. Headless-verifiable parts: the cost helper, the
  "never strictly worse" assertion, and the lapis-count arithmetic.

---

## M15 - Armor trims (3.3): consumed templates that grant a real bonus

**Goal:** armor trim templates drop as dungeon loot, applying one at a smithing
table permanently consumes it (the vanilla duplication recipe is removed for
these), and the trim material grants the piece a real combat bonus, not just a
colour. This is the first idea in the plan that reaches past the dungeon loop
into ordinary overworld combat, and that is a deliberate commitment worth naming.

**Depends on:** M13 (templates and materials in the loot).

**Scope:**

- **Templates as a sink, not a toll.** Vanilla lets a player duplicate a trim
  template after applying it (template + diamond + seven material at a crafting
  table). If dungeon-found templates go through that recipe, they are not a
  sink. The duplication recipe must be removed for these templates specifically,
  or globally if there is no clean way to distinguish a dungeon-found template
  from an ordinary one at the recipe level.
- **Material decides the bonus, pattern stays cosmetic.** Vanilla ships roughly
  ten trim materials crossed with roughly seventeen patterns, too many cells to
  author a distinct bonus for each. Split the axes: the material determines the
  bonus (an attribute modifier: diamond trims toughness, netherite knockback
  resistance, gold a small speed bonus, lapis trims XP gain, etc.) and the
  pattern stays cosmetic and a rarity signal. That is "ten bonuses, tune once"
  instead of "170 combinations, tune all."
- **Equip-time attribute plumbing.** Reading which trim sits on a worn piece is
  a `DataComponents.TRIM` read (verified in 26.2: `DataComponents.TRIM` is
  `DataComponentType<ArmorTrim>`, and `ArmorTrim` is a record with `material()`
  returning `Holder<TrimMaterial>` and `pattern()` returning
  `Holder<TrimPattern>`). Turning that into an attribute modifier is an
  equip-time or slot-change check, the same shape as `SilenceListener`'s
  item-use interception but keyed on armour slots changing. **This plumbing is
  the part M17 reuses**, generalised from "what trim is on this piece" to "which
  of my extracted powers am I slotting."
- **The overworld-combat tension.** `VISION.md` §3.7 says nothing *required for
  progression* may live outside the dungeon loop; the templates and materials
  satisfy that (both are dungeon-loot-gated). But a worn trim's bonus applies
  wherever the player wears the armour, overworld included. That is consistent
  with the letter of §3.7 but is a bigger commitment than M14's reroll station.
  The alternative is a dungeon-only bonus (checked against the player's
  dimension, the same guard `RoomProtection` already reads), which resolves the
  tension at the cost of a trimmed piece feeling like a prop outside the
  dungeon. This is a real call, settled in-milestone.

**Touch points:** the `chests/tier_*.json` tables (trim template and material
entries); a new equip-time listener (the `SilenceListener` shape, keyed on
armour slot changes); the vanilla trim-template duplication recipe (removed or
conditionally excluded); `PocketDungeonsConfig` (the material-to-attribute
table, the dungeon-only flag if chosen).

**Done when:** a dungeon-found trim template applies at a smithing table,
consumes the template, grants the piece a material-based combat bonus, and
cannot be duplicated.

**Open (tuning, in-milestone):** the material-to-attribute table; whether
patterns carry real rarity weights or are cosmetic variety; whether the bonus is
dungeon-only or global; the duplication-recipe removal mechanism.

**Verify against the 26.2 jar (blocking for this milestone):** whether trim
template duplication is its own data-driven recipe type that can be conditionally
excluded, and whether a template carries any distinguishing data (an NBT tag, a
custom component) once it leaves the loot table, or whether every copy of a given
template is identical and the removal has to be global. This was flagged in the
brainstorm and is the one jar-verification task that can change the milestone's
shape rather than just its tuning.

**Implementation notes:**

- **Blocking jar work first; it changes the shape.** Before any code, run
  `javap` against the jar to answer the duplication-recipe question: is
  `minecraft:smithing_trim` (or the template-duplication recipe) a
  data-driven recipe type that can be overridden by a datapack recipe
  returning air/no-result, and does a `TrimTemplate` item carry any
  distinguishing component (`custom_data`, a tag) once it leaves the loot
  table? If every copy of a template is identical, the removal has to be
  global (override the duplication recipe for that template id in a
  datapack recipe JSON, not Java). If a dungeon-found template can carry a
  `pocketdungeons` custom_data tag, the removal can be conditional. The
  datapack-recipe route is strongly preferred (trap 14: crafting recipe
  results support arbitrary `components`, and a datapack recipe can produce a
  marked item with no Java); reach for a mixin only if the datapack route is
  provably insufficient, and say so in the report.
- **Templates and materials are M13 loot-table entries.** Add trim template
  entries (`minecraft:bolt_armor_trim_smithing_template` etc.) and trim
  material entries (`minecraft:diamond`, `minecraft:netherite_ingot`,
  `minecraft:lapis_lazuli`, `minecraft:gold_ingot`, etc.) to the
  `chests/tier_*` tables, weighted by tier. The template's `custom_data`
  tag (if the jar work says one survives) marks it dungeon-found; otherwise
  the duplication recipe is removed globally for the relevant template ids.
- **Material decides the bonus; pattern stays cosmetic.** Author a
  material-to-attribute table in `PocketDungeonsConfig` (a config-driven map
  from trim material id to an attribute modifier: diamond -> toughness,
  netherite -> knockback resistance, gold -> small speed, lapis -> XP gain,
  etc.). The clean shape is a config list of entries
  `{material: "minecraft:diamond", attribute: "minecraft:armor_toughness",
  amount: 1.0, operation: "add_value"}`, parsed in `apply` and exposed via a
  lookup accessor. Ten materials, tuned once, not 170 pattern x material
  cells. The pattern is cosmetic and a rarity signal only.
- **Equip-time attribute plumbing is the load-bearing new code, and M17
  reuses it.** This is a new listener keyed on armour slot changes, not item
  use (`SilenceListener`'s hook) and not block use (`RitualListener`'s hook).
  Verify against the jar the Fabric event for "an armour slot's contents
  changed" (the candidate is a tick-based check in the existing instance
  watcher, or a `ServerPlayerEvents`-shaped hook; if none exists, the
  fallback is a per-tick scan of the four armour slots in the watcher, which
  is cheap and already runs every `watchIntervalTicks`). On a slot change,
  read `DataComponents.TRIM` off the worn piece (verified: it is
  `DataComponentType<ArmorTrim>`, `ArmorTrim.material()` returns
  `Holder<TrimMaterial>`), look up the material in the config table, and
  apply/remove an `AttributeModifier` on the player for that attribute. The
  modifier UUID must be stable per (player, material, slot) so removing and
  re-adding is idempotent. **Generalise this plumbing:** the listener should
  be written as "read a worn-piece signal, apply a configured attribute
  modifier," so M17 generalises it from "what trim is on this piece" to
  "which extracted power am I slotting" by swapping the signal source. Do
  not hardcode the trim read inside the modifier-application loop; keep the
  signal read and the modifier application as two steps.
- **Dungeon-only vs global bonus is a config flag.** `RoomProtection` already
  reads the player's dimension for the dungeon guard; the same check
  (`player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)`)
  gates a dungeon-only bonus. Add `trimBonusDungeonOnly` (boolean) to
  `PocketDungeonsConfig`. If true, the modifier applies only inside the
  dungeon dimension; if false, it applies globally (the bigger commitment
  §3.7 permits by its letter). Settled in-milestone.
- **Live-only verification.** The smithing-table apply, the worn bonus in
  combat, and the duplication refusal are all client-interactive; record
  them in `LIVE_TEST_PASS.md`. Headless-verifiable: the
  material-to-attribute config parse, the modifier UUID stability, and the
  equip-time signal read against a synthetic worn stack.
- **Landed answer to the blocking jar question: removal is global, not
  conditional.** `Ingredient` (verified in the 26.2 jar) matches only by item
  id or tag, with no data-component predicate; a datapack recipe cannot
  require "this exact dungeon-found copy," so the duplication recipe
  (a separate `minecraft:crafting_shaped` recipe per pattern, not the
  `minecraft:smithing_trim` recipe that applies the trim) is overridden for
  every copy of a given template id. This is the plan's own named fallback,
  not a new decision.
- **Landed correction: eighteen patterns, not "roughly seventeen," and eleven
  materials, not "roughly ten."** The 26.2 jar's `TrimMaterials` includes
  `RESIN` alongside the plan's assumed ten; immaterial to scope, corrected
  here for accuracy.
- **Landed correction: lapis is `safe_fall_distance`, not "XP gain."** There
  is no vanilla player attribute for experience gain rate, and the milestone
  commits every material to the same `AttributeModifier` shape (the plumbing
  M17 reuses); an XP-gain bonus would need a different mechanism entirely.
  `PocketDungeonsConfig.trimBonuses` is a config list, so this is retunable
  without touching code.
- **Landed correction: trim templates and materials carry no
  `pocketdungeons.tier` custom_data tag**, contrary to this section's own
  "Implementation notes" above. `RerollStation` (M14) reads exactly that tag
  off the *held* item to decide whether a smithing-table right-click opens
  the reroll picker instead of vanilla's own screen; tagging a template or a
  stack of trim material the same way would have misrouted a player simply
  trying to open the smithing table with one in hand. Found and fixed during
  M15's own implementation, before it shipped as a live bug.
- **Landed scope decision: the `_drowned` table variants are not touched.**
  M13 added its gear pool to all nine chest tables (base, `_ominous`, and
  `_drowned`) because gear is required-path loot under `VISION.md` §3.7.
  Trims are an overworld-facing bonus layer on top of that, not something a
  run needs to stay self-sufficient, so M15's pools were added only to the
  six base and `_ominous` tables.

---

## M16 - Kadala gamble (3.4): emeralds for a random piece in a chosen slot

**Goal:** spend emeralds, pick a slot, get back a random item that fits it. No
guarantee of quality within the slot, which is the whole point: a currency sink
that trades certainty for volume, sitting next to M14's guaranteed, targeted
reroll. A player who knows what they want rerolls it; a player who just wants
more shots at *something* for that slot gambles.

**Depends on:** M13 (the gear pool exists).

**Scope:**

- **Emeralds are the cleanest fit.** The gamble's output is gear, not more
  currency, so there is no self-funding loop (unlike an emerald-gated door would
  have). Emeralds get a real second job (a currency spent here, a material found
  everywhere else) without the trap. Keeping emeralds here and echo shards or
  diamonds as the door fuel keeps the ladder-scale and gear-scale sinks on
  different currencies.
- **Slot first, tier second.** The player picks one of the eight equipment
  slots, the way Kadala's menu does. The cost scales with which tier's gear pool
  the gamble draws from. A tier-1 gamble is cheap and draws from the tier-1 pool;
  a tier-3 gamble costs more and draws from better gear, still with no guarantee
  of which piece or what it rolls.
- **The gear pool is M13's work.** The gamble draws from the slot-keyed,
  tier-keyed tables M13 authors, via the same `ResourceKey<LootTable>` mechanism
  `TrialContent`/`LootTables` already use for chests, keyed by slot instead of by
  container. If M13 chose to fold the gear into the existing chest tables, the
  gamble reads a subset; if M13 authored separate slot tables, the gamble reads
  those. Either way, M13 must land first.
- **Implementation shape.** Same house pattern as the rest: a block
  interception (`RitualListener`'s shape) opens a dialog (`DialogKit`/
  `DialogScreens`) offering the eight slots and whichever tiers the player's
  keystone level has unlocked; confirming a slot and tier spends the emeralds
  and draws one item.

**Touch points:** a new station class (the `RitualListener` block-use shape); a
new `DialogScreens` screen (slot and tier picker); `PocketDungeonsConfig`
(emerald cost per tier, slot weighting); the M13 gear tables (the draw source).

**Done when:** a player right-clicks the gamble station, picks a slot and tier,
pays emeralds, and receives one random item from that slot's tier pool.

**Open (tuning, in-milestone):** the emerald cost per tier; whether every slot
costs the same at a given tier or weapon slots cost more (D3 weighs different
Kadala pulls differently); whether the gear pool is a subset of the chest tables
or its own thing so a lucky gamble cannot out-produce opening the run's chests.

**Verify against the 26.2 jar:** nothing new beyond M13's pool work.

**Implementation notes:**

- **Same house pattern as M14: a block interception plus a `DialogScreens`
  screen.** A `GambleStation` class intercepts right-click on a configured
  block (the `RitualListener` branch pattern or its own
  `UseBlockCallback`), opens a dialog offering the eight equipment slots
  (helmet, chestplate, leggings, boots, sword, pickaxe, bow, shield; or
  whichever set M13 authored) and the tiers the player's keystone level has
  unlocked. The slot-and-tier picker is a `DialogKit.list` of buttons, each
  submitting a `CustomAll` payload to `DialogRouter` with `pd_slot` and
  `pd_tier` context keys. The router dispatches to a `GambleStation` handler.
  Add `GambleStation.register()` to `PocketDungeonsMod.onInitialize`.
- **The block is a `ConfiguredItem`-resolved block id** (`gambleBlock`,
  default a themed block), resolved in a `GambleStation.warmUp()` registered
  from `RitualListener.register`.
- **The draw is a `ResourceKey<LootTable>` lookup, keyed by slot and tier.**
  This is the `TrialContent`/`LootTables` mechanism: build the table id from
  the slot and tier (e.g. `chests/gear/<slot>_<tier>` or whatever M13
  authored), resolve it through
  `level.registryAccess().lookupOrThrow(Registries.LOOT_TABLE)`, and roll
  one item. If M13 folded gear into the chest tables, the gamble reads a
  subset (a dedicated pool within the table, or a separate slot-keyed table
  that duplicates the relevant entries). The gamble must not out-produce
  opening the run's chests, so a dedicated slot-keyed table with a single
  roll is the cleaner shape. Add the gamble table path constants to
  `LootTables.ALL` so a missing one is a boot-time error.
- **Cost scales with tier, emeralds only.** A new pure-Java helper
  `static int gambleCost(int tier, String slot)` (or extend `PayoutMath`),
  with a test. Config fields: `gambleEmeraldsPerTier` (the base curve) and
  optionally `gambleSlotMultiplier` (whether weapon slots cost more, the D3
  weighting). Spend `minecraft:emerald` by counting and shrinking via the
  `Payout.deliver` inverse; refuse with a message if short. The output is
  gear, not currency, so there is no self-funding loop; emeralds get a
  second job (currency here, material elsewhere) without the trap.
- **Tier unlock gates which tiers the dialog offers.** Read the player's
  keystone level from `DungeonLog.get(player).keystoneLevel()` and offer
  only the tiers whose threshold the level clears (the same level-gates-
  access principle from M10/M12). A low-level player sees only tier-1
  gambles; a high-level player sees all three.
- **Deliver the drawn item via `Payout.deliver`** (trap 3), never bare
  `inventory.add`. The drawn item carries the `pocketdungeons.tier`
  custom_data tag from M13 so its tier is readable downstream.
- **Live-only verification.** The station right-click, the slot/tier dialog,
  and the delivered item are client-interactive; record in
  `LIVE_TEST_PASS.md`. Headless-verifiable: the cost helper, the tier-unlock
  gate, and the table-id resolution against the registry.

---

## M17 - The Herobrine Cube (3.5): extract a power, imbue it anywhere

**Goal:** a crafting-table ritual that consumes a rare item once, permanently
remembers one fixed power it carried, and re-applies that power to any future
item cheaply. Two rituals: extract (consume the rare item, add its power to the
player's permanent set) and imbue (apply a previously extracted power to an
ordinary item for a small material cost). Capped at equip time so "which powers
do I run" stays a real choice, not a checklist.

**Depends on:** M15 (the equip-time attribute-modifier plumbing, generalised)
and M11 (rare adventure-graph nodes as the extractable-item source).

**Scope:**

- **Crafting-table ritual interception (new plumbing).** `RitualListener`
  intercepts a right-click today, not a craft result. The Cube intercepts a
  craft result: the right combination of items in a crafting grid does not
  produce a normal crafted result but performs a ritual instead. A crafting
  table anywhere still crafts normally for anything that is not a recognised
  ritual combination. This is the one genuinely new plumbing pattern in the
  plan; the closest existing pattern (`RitualListener`) is the one to study, not
  copy verbatim.
- **Extract.** Place a qualifying item in the grid; its one fixed power is added
  to the player's permanently remembered set (persisted the same way
  `DungeonLog.Entry` tracks `completedThemes`, a per-player, never-truncated
  collection) and the item is consumed. This is the real sink: a hard-won item
  is gone for good, in exchange for never needing another like it.
- **Imbue.** Place an ordinary item of the matching slot plus a smaller material
  cost in the grid; one previously extracted power (player's choice) is applied
  to that item. Cheap and repeatable; the expensive part already happened at
  extraction.
- **What is extractable stays rare.** Not ordinary tier loot, not gamble (M16)
  or reroll (M14) output: a fixed, build-defining effect is a different kind of
  reward. The source is the rare adventure-graph nodes M11 describes (a
  "pharaoh's chamber" drop, found once in a long while, down a path most players
  never walk). This gives M11's rare nodes a concrete reason to matter beyond a
  shell unlock or a theme. M11's rare-node reward list, M16's gear pool, and any
  shell-token list should be reconciled into one authored list rather than three
  separate ones.
- **Equip cap.** D3 caps how many extracted powers can be worn at once (three
  slots) even though the library grows arbitrarily. Without a cap, the Cube
  becomes a checklist: extract everything, wear everything, done. A small cap
  keeps "which powers do I run" a real choice every time a new one is found.
- **Reversibility.** D3's extraction is irreversible: once extracted, an item's
  power is a permanent unlock, never an item again. Whether that is too harsh a
  sink for a server where a mis-click costs a genuinely rare drop forever is a
  real call, settled in-milestone.

**Touch points:** a new craft-result interception listener (new plumbing); a new
extracted-power persistence field on `DungeonLog.Entry` (the `completedThemes`
shape); the M15 equip-time listener (generalised to read "which extracted powers
am I slotting" instead of "what trim is on this piece"); `PocketDungeonsConfig`
(imbue cost, equip cap, reversibility flag); the M11 rare-node reward list.

**Done when:** a player places a rare extractable item in a crafting grid, the
item is consumed and its power joins their permanent set, and they can later
imbue that power onto an ordinary item at a crafting table for a material cost,
with the equipped-power count capped.

**Open (tuning, in-milestone):** the exact extractable-item source (rare
adventure nodes only, or also a very-low-weight Cube-only gamble slot in M16);
the equip slot count and whether it is per-armour-piece or a fixed pool; the
imbue cost and currency; whether extraction is reversible.

**Verify against the 26.2 jar (blocking for this milestone):** the craft-result
interception point. `RitualListener` intercepts a right-click; the Cube needs to
intercept a `CraftingMenu`/`ResultContainer` craft. Confirm the Fabric API or
mixin surface that lets a server-side mod substitute a craft result before it
reaches the player, without a client mod. This is the second jar-verification
task that can change the milestone's shape.

**Answered, and it changed the shape:** `Ingredient` has no component-value
match, so a datapack recipe cannot express "any item, plus my chosen one of an
open-ended power library." The Cube shipped as a block-use ritual station
instead (`RitualListener`-adjacent, like `RerollStation`/`GambleStation`), not
a crafting-grid interception at all, no mixin, second or otherwise. See the
implementation notes below and `plans/COMPLETED-MILESTONES.md`'s M17 entry.

**Implementation notes:**

- **Landed divergence, recorded here per house rule 6 rather than silently
  changed: no crafting-grid interception, no second mixin.** This section
  originally called for a crafting-table ritual. Verified against the 26.2
  jar: `net.minecraft.world.item.crafting.Ingredient` (what every crafting
  recipe's grid slots match against) is a plain item/tag predicate with no
  component-value matching at all; `DataComponentMatchers` exists only on
  `ItemPredicate` (loot and advancement conditions, never a crafting grid).
  Imbue needs "any weapon or armour piece, plus whichever one of an
  open-ended power library the player chooses"; a datapack recipe cannot
  express that without one recipe per (item type x power) pair, which is
  unbounded since the power library "grows arbitrarily" by design. The named
  mixin candidate, `CraftingMenu.slotChangedCraftingGrid`, was confirmed
  `protected static` (would need injecting into) but landed unnecessary: a
  block-use ritual station, the same `RitualListener`-adjacent shape M14's
  `RerollStation` and M16's `GambleStation` already use: it reads and writes
  the held stack directly in Java, so item identity and an arbitrary chosen
  power both fall out for free, with no interception of any kind. This is
  the milestone's blocking jar-verification answer: not "which mixin," but
  "no crafting-grid mechanism at all, and therefore no second mixin either."
  See `plans/COMPLETED-MILESTONES.md`'s M17 entry for the shipped shape
  (`CubeStation`, `PowerListener`, `PowerEquipMath`).
- **Superseded below, kept for the historical reasoning:** the original
  blocking-jar-work note assumed a crafting-table ritual and is left as
  written rather than rewritten, since the note above already explains why
  the assumption did not survive verification.
- Before any code, exhaust the datapack-recipe route (traps 14 and 15 in
  `DISCOVERIES.md`): a crafting recipe with the right combination can produce
  a marked result item with arbitrary `components` and no Java, and a recipe
  with no unlock advancement is craftable but invisible (a hidden-recipe
  discovery mechanic for free). The Cube's extract and imbue are recognisable
  ritual combinations; the question is whether a datapack recipe alone can
  "consume the input and produce a marked output" the way the extract needs
  (consume the rare item, produce nothing but a player-state write), or
  whether that requires a server-side hook. A datapack recipe produces an
  item result; it cannot directly write player state. So the likely shape
  is: a datapack recipe produces a "extracted power token" item (the rare
  item consumed, a token emitted), and a Java listener on the craft-pickup
  (or a `CustomAll` dialog confirmation) writes the power to the player's
  permanent set. Verify against the jar the exact server-side hook: the
  candidate is `CraftingMenu.slotChangedCraftingGrid(...)` (trap 14 names
  it), injected only when vanilla produced no result, so a real recipe is
  never shadowed. **This is the one milestone where a second mixin is
  plausible;** exhaust the datapack + event route first, and if a mixin is
  truly required, say so in the report and justify it against the one-mixin
  budget. The existing `CustomClickMixin` is the model for "a mixin that does
  one thing nothing else can."
- **Extract and imbue as two datapack recipes plus one Java listener.**
  Author two hidden (no-unlock-advancement) recipes under
  `data/pocketdungeons/recipe/`: one for extract (rare item + a marker
  component -> an "extracted power" token item), one for imbue (ordinary
  item + material + token -> imbued item). The Java listener intercepts the
  craft result, performs the player-state write (extract: add the power to
  the permanent set; imbue: apply the chosen power to the output item), and
  consumes the inputs. The "eight around one" vanilla shape (golden apple)
  is the recipe form to copy.
- **Permanent extracted-power set is a new `DungeonLog.Entry` field.** Add
  `Set<String> extractedPowers` (or a list of power ids) to the `Entry`
  record with an `optionalFieldOf` codec and a sane default (`List.of()`),
  the same shape as `completedThemes`. Persisted per-player, never
  truncated, the way `completedThemes` is. The power id is a stable string
  keyed off the rare item's source (the M11 rare-node reward list).
- **Reconcile the rare-item source lists into one authored list.** M11's
  rare-node reward list, M16's gear pool, and any shell-token list should be
  one authored list, not three. The extractable items are a strict subset of
  M11's rare adventure-graph nodes (a "pharaoh's chamber" drop), not
  ordinary tier loot and not gamble/reroll output. This gives M11's rare
  nodes a concrete reason to matter. The reconciliation is content work
  (JSON), done here in coordination with M11's rare-node authoring.
- **Equip cap reuses M15's equip-time plumbing, generalised.** M15's
  listener is "read a worn-piece signal, apply a configured attribute
  modifier." M17 generalises the signal source from "what trim is on this
  piece" to "which extracted power am I slotting" (read off the item's
  `custom_data` tag, written by the imbue recipe). The equip cap is a
  count: at most N extracted powers active at once (D3 caps three). The
  listener counts the active powers across the player's equipped items and
  refuses (or silently disables) the excess. Config fields: `imbueCost`,
  `imbueMaterial` (the imbue currency item, a `ConfiguredItem`), `equipCap`
  (the active-power limit), `extractionReversible` (boolean, whether a
  mis-click can be undone; D3 says no, a server with genuinely rare drops
  may say yes).
- **Reversibility is a config flag, settled in-milestone.** If reversible,
  the extract writes the power and keeps a reference to the consumed item's
  identity so an undo can restore it (costly, probably another ritual). If
  irreversible, the extract is a hard sink: the rare item is gone for good,
  in exchange for never needing another like it. Default to irreversible
  (D3's shape); make it configurable.
- **The name is lore-flavoured but the mechanic is self-contained (VISION §9).**
  If §9's "not a lore project" line stands, ship the Cube under a neutral
  name (e.g. "the Cube" or "the Extractor"); if §9 is revised, keep
  "Herobrine Cube." Nothing else changes either way. Do not block on
  `LORE.md`.
- **Live-only verification.** The crafting-grid ritual, the imbued item in
  combat, and the equip-cap enforcement are client-interactive; record in
  `LIVE_TEST_PASS.md`. Headless-verifiable: the extracted-power persistence
  (write and read back from `DungeonLog`), the equip-cap count, and the
  power-id reconciliation against the rare-node list.

---

## Cross-cutting: what this rewrites in the existing design

When any of this lands, these are the touch points outside the milestones
themselves. The superseded-design rule (house rule 6) applies: mark superseded,
do not delete, where a design document is being revised rather than implemented.

- `VISION.md` §1: the "safe / greedy / reckless" framing of the three doors.
  The doors stay three; what distinguishes them changes.
- `VISION.md` §3.1.1: "the three doors are runs and stay runs, not up for
  renegotiation." The doors stay runs; the +1/+2/+3 upgrade choice goes.
- `VISION.md` §3.2: the ladder's difficulty source moves from the keystone level
  to the map. The "meta-progression grants no power" line still holds: the room
  and the number still persist, and neither makes the next run easier. Mob
  scaling makes the run *harder* at higher levels, not the player stronger.
- `VISION.md` §5.4: the recipe system's folklore argument. Adventures preserve
  the dynamic through a hidden graph rather than hidden compositions; the
  principle holds, the mechanism changes. Mark §5.4 superseded by the adventure
  graph, do not delete it.
- `VISION.md` §8: "State of play" updates as each milestone lands.
- `ROADMAP.md`: M8 (Deferred) already points at `DOOR_LADDER_BRAINSTORM.md`;
  this plan supersedes that pointer for the D3 progression work. The roadmap's
  own rule is that it carries order, not status, so it gains the M10-M17
  sequence and no checkboxes.

**What this plan does not touch:** the visiting rework (brainstorm §8), the
immutable room shell (§9), the physical door selection (§10), or the
lodestone-menu consolidation (§11). Those are separate design tracks in the
brainstorm and are not in scope here. If any of them lands first, the door-offer
plumbing this plan depends on may have moved; reconcile at that time.

---

## Fuel currency (the one open structural variable)

M12 leaves the fuel currency as a config-selected `fuelItem`. The two candidates
and their tradeoffs, recorded here so the in-milestone decision has the context:

| | Echo shards | Diamonds |
|---|---|---|
| Diegetic fit | Perfect (keystone = recovery compass, crafted from echo shards) | None |
| Already in loot | No, need adding to tier 2/3 (content edit) | Yes |
| Self-funding risk | Low (premium rooms drop different loot) | High (premium rooms drop diamonds) |
| Gives a near-unused vanilla item a second purpose | Yes | No |

The mechanism is currency-agnostic, so the decision does not block the code. It
blocks only the loot-table work in M12. Whichever is chosen, the orthogonality
rule holds: fuel (doors) and emeralds (gamble) and lapis (reroll) stay on three
different currencies.
