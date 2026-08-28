# Thingy: discoveries, traps, and verified API shapes

Verified 26.2 API findings and traps hit while building Thingy, so nobody
re-derives them the hard way. Numbered entries, newest phase last.

---

## 1. The boss identity record (Phase 7 target shape)

Written in Phase 0 as `kamutotems.BossRecords`
(`kamutotems/fabric/src/main/java/kamutotems/BossRecords.java`). Phase 7 absorbs
this shape by reference for `VirtualEntity` persistence: it is not a throwaway,
and there is no migration budget for changing it later.

### Why it exists

`BossHost` tracked bosses only in memory. An orderly shutdown despawns and
refunds first, so a clean stop left nothing behind. A crash left the tagged
entity in the world with no in-memory record, and `onEntityLoad` stripped the
`kamutotems_boss` tag on reload, silently demoting the boss to a vanilla mob
with scaled stats and a leftover custom name. Tier, kamu, aura, loot table, and
boss bar were all lost. This is crash recovery, not a per-restart mechanism.

### The record

```java
public record BossRecord(
        UUID entity,          // the carrier entity's UUID: the join key
        UUID owner,           // player UUID, or a random UUID for dispenser spawns
        int tier,
        BossRoll roll,        // mint-time roll: tier + kamu ids
        List<String> kamuIds, // what the catalog actually resolved at spawn
        AuraSpec aura,
        boolean fromSigil,    // drives the refund on despawn
        int purchaseCounter,
        long seed) {}
```

`kamuIds` is stored alongside `roll` rather than derived from it. The roll is
what the sigil promised; `kamuIds` is what the catalog resolved at spawn time.
They diverge the moment an entry leaves the catalog, and a live boss must keep
carrying what it was carrying rather than silently regaining a kamu on restart.

Field names on disk: `entity`, `owner`, `tier`, `roll`, `kamu`, `aura`,
`from_sigil`, `purchase_counter`, `seed`.

### What is deliberately not in it

- The `ServerBossEvent` bar. It is not serializable. It is rebuilt on reattach
  from the entity's own custom name, falling back to `BossNames.build` when the
  name is gone.
- The entity reference. The record is keyed by UUID; the entity arrives from
  `ServerEntityEvents.ENTITY_LOAD`.
- `FREE_CLAIMS` and `SIGIL_COUNTERS`. Daily player economy, not entity
  identity, with a different lifetime. They stay in `boss_state.json` via
  `Persist`.

### Codec shape

`AuraSpec.none()` is `(null, null, 0)`, so both halves of a present aura are
optional fields rather than a nullable compound:

```java
public static final Codec<AuraSpec> AURA_SPEC_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        AURA_KIND_CODEC.optionalFieldOf("kind").forGetter(a -> Optional.ofNullable(a.kind())),
        Codec.STRING.optionalFieldOf("modifier_kamu").forGetter(a -> Optional.ofNullable(a.modifierKamuId())),
        Codec.INT.optionalFieldOf("tier", 0).forGetter(AuraSpec::tier)
).apply(instance, (kind, modifier, tier) ->
        new AuraSpec(kind.orElse(null), modifier.orElse(null), tier)));
```

`AuraKind` is an enum in `kamutotems-core`, which has no Minecraft and no DFU on
its classpath by design, so it cannot implement `StringRepresentable` and cannot
carry its own codec. The codec lives in the fabric module and resolves by name
with an explicit failure:

```java
private static final Codec<AuraKind> AURA_KIND_CODEC = Codec.STRING.comapFlatMap(
        name -> {
            try {
                return DataResult.success(AuraKind.valueOf(name));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "Unknown aura kind: " + name);
            }
        },
        AuraKind::name);
```

The same constraint applies to `BossRoll`: pure-core record, codec built in the
fabric module out of `Codec.INT` and `Codec.STRING.listOf()`. Phase 7 inherits
this split. A dependency-free core module means every codec for a core type
lives on the Minecraft side.

UUIDs use `UUIDUtil.STRING_CODEC`, not `UUIDUtil.CODEC`. `CODEC` is the int-array
form; the string form keeps the file readable when an operator has to look.

### The store

```java
public static final SavedDataType<BossRecords> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("kamutotems", "boss_records"),
        BossRecords::new,
        CODEC,
        DataFixTypes.LEVEL);
```

Namespace is `kamutotems`, not `thingy`, per the plan's namespace rule: the
framework name is never the identity namespace of content it hosts.

The store is always the overworld's: `server.overworld().getDataStorage()
.computeIfAbsent(TYPE)`. `ServerLevel.getDataStorage()` is per-dimension, and a
per-dimension store would make the reattach lookup guess which dimension the
entity loaded in.

Records are stored as a **list**, not `Codec.unboundedMap`, even though the key
is a UUID that encodes to a bare string. This is the WondrousState lesson: NbtOps
builds maps through `RecordBuilder.AbstractStringBuilder`, which errors with
"key is not a string" on anything that does not encode to a bare string, and the
failure is silent at write time and loses the whole file. A list also keeps the
shape stable if the key ever grows into a compound.

On disk at
`run/world/dimensions/minecraft/overworld/data/kamutotems/boss_records.dat`, not
`run/world/data/`.

### Reattachment logic

`BossHost.onEntityLoad` (`ServerEntityEvents.ENTITY_LOAD`):

1. Not tagged `kamutotems_boss`, or already in `BY_ENTITY`: return. The
   already-tracked check matters because `ENTITY_LOAD` also fires for a boss
   that was just spawned this session.
2. Look up `BossRecords.forLevel(level).get(entity.getUUID())`.
3. No record: **log a warning and leave the tag alone.** Stripping it is the
   demotion this fix removes. A tagged mob with no record is either a
   pre-fix orphan or a record lost with a corrupt region file, and it costs
   nothing but a log line.
4. Record found: resolve `kamuIds` against the catalog, take the bar name from
   `entity.getCustomName()` (falling back to `BossNames.build`), build a fresh
   `ServerBossEvent`, add the owner if online, construct the `Boss`, and put it
   into `BY_ENTITY` and `BY_PLAYER`.

Write points, all of them: `BossHost.track` writes the record (so every spawn
path, including the dispenser and `Sigil`, is covered by one call site). The
record is dropped in `onDeath`, in the `onTick` sweep of removed or dead
entities, in `despawnFor`, and in `/boss cleanup`.

Spawn order matters and is safe: `Boss.spawn` calls `level.addFreshEntity`
before `track` adds the tag, so the `ENTITY_LOAD` fired by the spawn itself sees
an untagged entity and returns at step 1.

`ServerPlayConnectionEvents.JOIN` was added to `BossHost`: a boss reattached
while its owner was offline has a bar with nobody on it, and the owner is added
when they next connect. Without this the bar is invisible to exactly the player
the crash affected.

---

## 2. Verified 26.2 API shapes

All checked with `javap` against
`C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`.
Checking that a method exists is not the same as checking what it does.

```
net.minecraft.world.level.saveddata.SavedData
  public void setDirty();
  public void setDirty(boolean);
  public boolean isDirty();
```
No `save(CompoundTag)` and no constructor argument. Serialization is entirely
the codec's job; the class only tracks the dirty flag.

```
net.minecraft.world.level.saveddata.SavedDataType<T>
  public SavedDataType(Identifier, Supplier<T>, Codec<T>, DataFixTypes);
```
The supplier is the empty-state constructor, used when the file is absent.

```
net.minecraft.world.level.storage.SavedDataStorage
  public <T extends SavedData> T computeIfAbsent(SavedDataType<T>);
  public <T extends SavedData> T get(SavedDataType<T>);
  public <T extends SavedData> void set(SavedDataType<T>, T);
```
`computeIfAbsent` is the only one worth using at a call site: `get` returns null
for a world that has never written the file.

```
net.minecraft.server.level.ServerLevel
  public SavedDataStorage getDataStorage();   // per-dimension
  public MinecraftServer getServer();
net.minecraft.server.players.PlayerList
  public ServerPlayer getPlayer(UUID);        // null when offline
net.minecraft.server.level.ServerBossEvent
  public ServerBossEvent(UUID, Component, BossEvent$BossBarColor, BossEvent$BossBarOverlay);
net.minecraft.world.entity.Entity
  public Set<String> entityTags();            // renamed from getTags()
  public Component getCustomName();           // null when unnamed
net.minecraft.core.UUIDUtil
  public static final Codec<UUID> CODEC;         // int array
  public static final Codec<UUID> STRING_CODEC;  // string
```

---

## 3. Traps

1. **`Codec.unboundedMap` and NbtOps do not mix.** Covered above: it fails
   silently at write time and takes the whole file with it. Store keyed
   collections as lists of entries.

2. **A dependency-free core module means no codecs in core.** `kamutotems-core`
   has an empty `dependencies {}` block on purpose, and a stray
   `import net.minecraft.*` there fails the build. Every codec for a core type
   (`BossRoll`, `AuraSpec`, `AuraKind`) is built in the fabric module. Phase 7
   should expect the same shape wherever a mod keeps a pure rules core.

3. **`ENTITY_LOAD` fires on fresh spawns too**, not only on chunk load. Any
   reattach handler hung off it needs an "already tracked" guard, and any
   identity tag must be applied after `addFreshEntity` or the handler will
   race its own spawn.

4. **Test codecs through `NbtOps`, not JSON.** A codec that round-trips through
   `JsonOps` can still fail against NBT, which is what `SavedDataStorage`
   actually uses. `kamutotems/fabric/src/test/java/kamutotems/BossRecordsRegressionTest.java`
   is a plain `main()` wired to `check` through a `JavaExec` task, matching the
   suite's existing test convention, and it round-trips through `NbtOps.INSTANCE`.

---

## 4. Phase 1: VirtualItem layer and Wondrous parity

`thingy/api` and `thingy/fabric` now exist. `thingy/fabric` is a line-for-line
behavioural port of `wondrous/fabric`: same 29 items (`SmashyMortar` was dead
code in wondrous, colliding with `Mortar` on `ID = "smashy_mortar"` and never
registered, and stays skipped here), same ids, same `custom_data` shape, same
`wondrous:` attribute modifier ids. `ItemRegistry`, `Definitions`, `Aura`, and
every item behavior file (`Stations`, `CraftStation`, `LazySprinkler`,
`LinkWand`, `CarryGlove`, `FlyingBoots`, `PeekBox`, `VoidBin`, `RecipeGuard`,
`ChuckIt`, `TidyUp`, `BigLazyHoe`, `Growth`, `Mortar`, `Restock`,
`AuraEffects`, `AreaBreak`, `BoomerangBall`, `DisenchantMenu`, `SmelterMenu`)
were ported; `ThingyCommands` replaces `WondrousCommands` (`/thingy` instead
of `/wondrous`, same `give`/`list`/`help`/`links`/`void_confirm`/`void_cancel`
subcommands, bare-id `give` argument unchanged since Phase 1 hosts only one
namespace).

### Shape preservation, in code

`VirtualTag.register("wondrous", VirtualTag.BARE_STRING)` runs first in
`ThingyMod.onInitialize`, before anything can stamp or read a stack. The
`BARE_STRING` reader reproduces `WondrousTag`'s exact behaviour: a bare string
under key `"wondrous"`, so a stack made by the old wondrous jar and a stack
made by this one are byte-identical. Everywhere the old code called
`WondrousTag.is(stack, id)` or `WondrousTag.read(stack)`, the port calls
`VirtualTag.is(stack, "wondrous", id)` or `VirtualTag.read(stack, "wondrous")`.
Attribute modifier ids stay `Identifier.fromNamespaceAndPath("wondrous", ...)`
in `Definitions`, unchanged, because `withModifierAdded` does not deduplicate
by value and a `thingy:` id would double-buff a stack made by the old jar.

### Two id shapes, on purpose

`VirtualItem.id()` and `VirtualItems.byId(String)` use the namespaced id
(`"wondrous:flying_boots"`), the public contract every future namespace will
share. Internal dispatch (`Stations`, `AreaBreak`, `ChuckIt`, everything that
reads a held stack to decide what to do with it) uses the bare id
(`"flying_boots"`), because that is what `VirtualTag.read` for the `wondrous`
namespace actually returns; the namespace prefix only exists at the API
boundary. `ItemRegistry` keeps two maps, `byNamespacedId` and `byBareId`, so
neither caller has to translate.

### `Aura` is namespaced now

`wondrous.Aura` kept bare ids in its `Carried` snapshot because there was only
one namespace to check. `thingy.Aura` builds `Carried` from
`VirtualTag.readAny(stack)`, which returns the namespaced id, so a future
namespace's passive effects (Phase 5) can share the one polling loop without a
bare id from one namespace colliding with another's. Every caller that reads
`Carried` (`FlyingBoots`, `Restock`, `AuraEffects`) was updated to check
`"wondrous:<id>"` instead of `"<id>"`; `ItemRegistry.namespaced(String)` is the
one place that mapping lives.

### `RecipeGuard` is a per-namespace opt-in registry

Not a hardcoded `wondrous` check: `RecipeGuard.optIn(String namespace)` is the
registration call, and `wondrous` calls it once from `RecipeGuard.register()`.
There is no "any tagged item" default, because that would silently make a
future kamutotems totem or spiritwolves stone uncraftable as an ingredient the
moment those mods migrate, without either mod choosing that (PLAN.md Phase 1).

### `WondrousState` keeps its class name and its `SavedDataType` id

`thingy.WondrousState` is a verbatim port, including the
`wondrous:wondrous_state` id. Placed-station, sprinkler, and link-wand content
belongs to the `wondrous` namespace under the namespace rule regardless of
which mod's code hosts it, and keeping the id extends the swap test's rollback
story to world data, not just item stacks: a station's saved grid survives a
jar swap in either direction because both jars read the same file. Changing
the id would have been a silent data-loss trap on the very rollback path the
swap test exists to protect.

### `suite_items` is generated, not hand-copied

`SuiteItemsGenerator` (a `main()` wired to the `:fabric:generateSuiteItems`
Gradle task, itself wired into `processResources`) writes
`data/wondrous/suite_items/*.json` from `Definitions.ALL` directly: the
`item`, `name`, and `minecraft:custom_data` fields cannot drift from the code
that actually builds the stack, because they are read from the same `Def`
record. `tags` and `rarity` are not derivable from `Definitions` (they are
shop/economy metadata, not gameplay behaviour) and stay in a sidecar,
`SuiteMetadata`, copied once from the hand-authored wondrous files and not
touched again by the generator. Regenerating requires
`Bootstrap.bootStrap()`: a standalone `main()` has no launcher to populate
`Items.*`, so `SharedConstants.tryDetectVersion()` then `Bootstrap.bootStrap()`
must run first, in that order.

One Gradle trap hit while wiring this: `generateSuiteItems`'s classpath must
be `sourceSets["main"].compileClasspath + files(...classesDirs)`, not
`runtimeClasspath`. `runtimeClasspath` includes the source set's own resources
output, which `processResources` produces, so depending on it from a task that
`processResources` itself depends on is a cycle
(`processResources -> generateSuiteItems -> classes -> processResources`).

### Traps

5. **A standalone `main()` needs `Bootstrap.bootStrap()` before touching
   `Items.*`.** Static fields like `Items.LEATHER_BOOTS` are populated by
   Minecraft's own bootstrap sequence, which a normal server or client launch
   runs automatically and a bare `JavaExec` does not. Call
   `SharedConstants.tryDetectVersion()` then `Bootstrap.bootStrap()` at the top
   of any generator or tool `main()` that touches vanilla registries.

6. **A Gradle task's classpath can create a dependency cycle through resources
   output.** `sourceSets["main"].runtimeClasspath` is not "the classes", it is
   "everything needed to run this source set", which includes its own
   `processResources` output. A code generator that a `processResources`-time
   task depends on must use `compileClasspath` plus the classes directory
   explicitly, or the task graph cycles back on itself.

---

## 5. Phase 2: CobbleEconomy integration

`cobbleeconomy/fabric/src/main/java/cobbleeconomy/WondrousShop.java` (the one
file in that mod that touches a wondrous/thingy API class) now imports
`thingy.api` instead of `wondrous.api`: `VirtualItems` instead of
`WondrousItems`, `VirtualItem` instead of `WondrousItem`, `Give` instead of
`WondrousGive`, `FabricLoader.isModLoaded("thingy")` instead of
`isModLoaded("wondrous")`. The `compileOnly` dependency in
`cobbleeconomy/fabric/build.gradle.kts` moved from `wondrous:wondrous-api:0.1.0`
to `thingy:thingy-api:0.1.0`, and `fabric.mod.json`'s `"suggests"` moved from
`"wondrous"` to `"thingy"`.

### The `wondrous:` config prefix: kept, permanently

PLAN.md Phase 2 calls for an explicit decision here, not a default. The
decision: **`shop.json` keeps naming these items `"wondrous:<id>"` forever.**
Nothing in `ShopConfig`, `ShopMenu`, `ShopDisplay`, or `ShopPurchase` changed;
`WondrousShop.isWondrousItemId` and `.idFrom` still check and strip the same
literal prefix they always did.

The reasoning: the prefix names the item's own identity namespace (the value
already inside its `minecraft:custom_data` tag), not the mod currently
resolving that id. That is exactly the namespace rule PLAN.md states for
Thingy itself, applied one layer up: a shop config is content, and content
keeps the namespace it was authored under regardless of which framework jar
is installed. Migrating every `shop.json` in production to a `thingy:` prefix
would have bought nothing (the items are not renamed, only re-hosted) and cost
a rewrite of every server owner's config the moment they update. `WondrousShop`
itself absorbs the one real change: `VirtualItems.byId` wants the namespaced
id, so `lookup` re-prepends `"wondrous:"` before calling it, rather than
pushing that translation out to every call site.

### What did not need to change

`ShopConfig`'s load-time validation (`WondrousShop.available()` plus
`.baseItem(id).isEmpty()`, warning and skipping an entry that fails either)
already treated a missing or unresolvable wondrous item as "reported, not
fatal" before this phase. Nothing about that behaviour is Thingy-specific, so
none of it moved. Same for `SuiteItems`: it reads `data/*/suite_items` as a
generic vanilla datapack merge, unaware of which mod's jar the files came
from, so Phase 1's switch from hand-authored to generated `suite_items` JSON
is invisible to it.

---

## 6. Phase 3: satisfied without new code; the `VirtualBlock` abstraction is deferred

PLAN.md's Phase 3 names two things: a world-data migration risk, and a
`VirtualBlock`/`VirtualStation` API layer. Only the first has a concrete shape
in the plan text; the second is named in the phase title with no methods, no
fields, and (as of Phase 3) no second namespace anywhere to design it
against.

### The migration risk: already closed, by a Phase 1 decision

PLAN.md calls this "the largest concrete gap in the plan": if a new
`ThingyState` used a different `SavedDataType` id than `WondrousState`'s
`wondrous:wondrous_state`, archiving wondrous would silently orphan every
placed station, sprinkler, and link, since nothing would read the old file
anymore. The stated exit criterion is a migration step, dry-run against a
copy of the production world.

That gap does not exist here. Phase 1 kept `WondrousState`'s class name and
its `wondrous:wondrous_state` id verbatim in `thingy.WondrousState`
(documented in §4 above), specifically so both jars read the same file. There
is no format change, so there is nothing to migrate and nothing to dry-run.
This was a Phase 1 decision made for the swap test's sake, and it happens to
close Phase 3's stated risk as a side effect.

### The `VirtualBlock`/`VirtualStation` abstraction: deferred to Phase 5

Asked and decided with the user (2026-08-27): **do not invent this interface
now.** Reasoning, in order:

- The plan gives `VirtualItem` a full spec in Phase 1 (four methods, a
  registry contract, a namespace-reader strategy). It gives `VirtualBlock`
  none of that; the phase heading is the only place it is named.
- The only three consumers that would use it (`CraftStation`,
  `LazySprinkler`, `LinkWand`) are already fully working in `thingy.fabric`
  from Phase 1, built directly against `WondrousState`. Nothing is broken or
  blocked by their not going through an abstraction.
- There is no second namespace yet to shape the interface against. PLAN.md's
  own "What we are not building" section cuts `VirtualProjectile` for exactly
  this reason ("generalizing from n=1"), and separately warns against
  building "a 'dynamic virtual block' concept for one tool" right inside
  Phase 3's own implementation notes. An invented `VirtualBlock` today would
  be shaped by guesswork, not by a real second consumer, and Phase 5
  (spiritwolves and kamutotems item migration) is the first point such a
  consumer actually exists.

Phase 5 is where this interface should be designed, against spiritwolves'
and kamutotems' real block-backed items, not before. Until then,
`CraftStation`, `LazySprinkler`, and `LinkWand` stay exactly as Phase 1 left
them.

### Exit criteria, revisited

- Migration step run against a copy of the production world: **not
  applicable.** No migration exists because no format changed.
- `ThingyState` compiles and persists across restart: satisfied by
  `thingy.WondrousState`, unchanged since Phase 1, already exercised by the
  Phase 1 build.
- Chunk-aware sweep functional: `WondrousState.sweep`'s `hasChunkAt` checks
  were ported verbatim in Phase 1 and have not been touched since. (`hasChunkAt`
  is deprecated in the 26.2 jar and produces a compiler note; that note is
  pre-existing in wondrous itself, not something this port introduced.)
