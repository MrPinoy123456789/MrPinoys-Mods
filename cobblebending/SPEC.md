# MrPinoy's Cobble Bending — Build Spec

> **Status:** design complete, not yet implemented. Every Minecraft signature and
> component behaviour marked ✅ below was verified against the actual 26.2 merged
> jar (`minecraft-merged-deobf-26.2.jar`) in the Gradle cache on 2026-08-08.
> Where something is *not* verified, it says so explicitly.

---

## 1. The problem this solves

The suite needs **drains**, not taps ([DESIGN.md §5](../DESIGN.md)). Cobble
bending is a combat/utility power fantasy whose ammunition *is* the common
currency. Every ability consumes cobblestone from the player's inventory and
nothing ever returns more than it consumed.

> **The load-bearing rule: cobble bending is net-negative on cobblestone.
> Always. No ability breaks a block into a drop, and no ability yields more
> cobble than it cost.**

Sink shapes hit (§5 table): *consumed by playing* ✓, and — once repair costs
land — *destruction / risk* ✓.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Focuses in v1 | **Two: Hurl and Wall** | One verb each. Ship, then extend |
| Control scheme | **Hold → charge → release** | One button, three outcomes per focus |
| Hold mechanic | **`minecraft:consumable` component** | Vanilla, packless, no Mixin — see §4 |
| Hurl host | **`minecraft:firework_star`** | Plain `Item`, no `use`/`useOn` override ✅ |
| Wall host | **`minecraft:heart_of_the_sea`** | Plain `Item`, no `use`/`useOn` override ✅ |
| Fire charge | **Rejected** | `FireChargeItem.useOn` ignites blocks ✅ — fatal |
| Ability cooldown | **Vanilla `USE_COOLDOWN`** | Client renders the sweep natively. Free HUD |
| Client requirement | **None.** `"environment": "server"` | §6, non-negotiable |
| Mixins | **Zero** | The consumable trick removes the need. Keep it |
| Cross-mod | **None** | Fully standalone |

**Scope note.** Earlier drafts had Stone Armor, Boulder-as-separate-ability, and
mobility focuses. All deferred — see §14.

---

## 3. Core loop

1. Player buys a **Focus** from `/shop` (a vanilla item stamped with
   `custom_data`, per the [DESIGN.md §3](../DESIGN.md) `components` pattern).
2. Player holds cobblestone anywhere in their inventory as ammunition.
3. **Hold right-click** → the Focus enters a charge state. The player sees the
   vanilla item-use pose.
4. **Release** → the ability fires, scaled by how long it was held, aimed where
   the player is looking.
5. Cobble is deducted. A cooldown is applied via `USE_COOLDOWN`, which the
   vanilla client renders as a sweep over the item.
6. Out of cobble → nothing fires, no cooldown burned, actionbar says why.

---

## 4. The hold mechanic — verified, and it removes the Mixin

This was the single biggest technical risk. **It is resolved: any item can be
given a hold-and-release state with zero client mods, no resource pack, and no
Mixin.**

`Item.use` checks the `CONSUMABLE` component on the *stack*, before `EQUIPPABLE`
and `BLOCKS_ATTACKS` — verified bytecode:

```java
// net.minecraft.world.item.Item.use(Level, Player, InteractionHand)
Consumable consumable = stack.get(DataComponents.CONSUMABLE);
if (consumable != null) return consumable.startConsuming(player, stack, hand);
```

```java
// net.minecraft.world.item.Item.getUseDuration(ItemStack, LivingEntity)
Consumable consumable = stack.get(DataComponents.CONSUMABLE);
if (consumable != null) return consumable.consumeTicks();
```

So stamping `CONSUMABLE` onto a firework star makes the client enter the using
state, hold the pose, and send the release packet on let-go. Verified component
record:

```java
DataComponents.CONSUMABLE  // DataComponentType<Consumable>

new Consumable(float consumeSeconds,
               ItemUseAnimation animation,
               Holder<SoundEvent> sound,
               boolean hasConsumeParticles,
               List<ConsumeEffect> onConsumeEffects);

Consumable.builder()
    .consumeSeconds(3600.0F)
    .animation(ItemUseAnimation.BRUSH)
    .hasConsumeParticles(false)
    .build();
```

`ItemUseAnimation` values ✅: `NONE, EAT, DRINK, BLOCK, BOW, TRIDENT, CROSSBOW,
SPYGLASS, TOOT_HORN, BRUSH, BUNDLE, SPEAR`.

### The three findings that make this clean

**1. `consumeSeconds = 3600.0F` is exactly the vanilla "infinite" duration.**
`3600 × 20 = 72000` ticks, which is the same constant vanilla uses for shields
and spyglasses (`Item.APPROXIMATELY_INFINITE_USE_DURATION = 72000` ✅). The use
never self-completes, so the item is never consumed and no
`USE_REMAINDER` is needed.

**2. There are no consume sounds or particles at all.** Verified bytecode of
`Consumable.shouldEmitParticlesAndSounds(int remaining)`:

```java
int elapsed = consumeTicks() - remaining;
boolean past = elapsed > (int)(consumeTicks() * 0.21875F);
return past && remaining % 4 == 0;
```

With `consumeTicks() == 72000` the threshold is **15750 ticks (≈13 minutes)** of
held charge before anything is emitted. Charges here are 5–30 ticks, so the
branch is never taken. `hasConsumeParticles` and `sound` are irrelevant — set
them anyway for hygiene, but they will never fire.

**3. `canConsume` only gates on `FOOD`.** Verified bytecode — it reads
`DataComponents.FOOD` and returns true when absent. A non-food item always
passes.

### Reading the charge server-side

All present on `LivingEntity` ✅:

```java
boolean   isUsingItem();
ItemStack getUseItem();
int       getUseItemRemainingTicks();
int       getTicksUsingItem();      // ← this is the charge duration
void      releaseUsingItem();
void      stopUsingItem();
```

Poll in `ServerTickEvents.END_SERVER_TICK`, tracking a per-player
`wasUsing` flag. On the **true → false** transition, read `getTicksUsingItem()`
from the previous tick's snapshot and fire the ability. No Mixin, no
`ServerPlayNetworkHandler` hook.

> **Cache the charge each tick.** `getTicksUsingItem()` is derived from
> `useItemRemaining`, which is reset when the use ends. Snapshot the tick count
> and the held stack *every* tick while using, and read the snapshot on release.

### Unverified — confirm in playtest

- **Movement slowdown while using.** Vanilla slows the player while
  `isUsingItem()`. The exact multiplier in 26.2 was **not** confirmed from
  bytecode. This matters for Bridge (§7) — if the crawl feels bad, cancel it
  with a Speed effect or a transient attribute modifier for the duration.
- **Right-clicking while aimed at a nearby block must still start the charge.**
  Vanilla food works this way (you can eat while facing a wall), and both hosts
  are plain `Item`s with no `useOn`, so it should fall through. Test it anyway —
  bending is aimed at blocks most of the time. If it fails, `UseBlockCallback`
  is the hook.

---

## 5. Item construction

Both Focuses follow the `wondrous`/`spiritwolves` convention: a vanilla item
stamped with `custom_data`, never a registry entry.

### Shared components

| Component | Value |
|---|---|
| `custom_data` | `{ cobblebending: { focus: "hurl" \| "wall" } }` |
| `consumable` | `consumeSeconds 3600.0F`, animation per focus, `hasConsumeParticles false` |
| `max_stack_size` | `1` |
| `item_name` | "Hurl Focus" / "Wall Focus" |
| `lore` | Gesture hint — see below |

`DataComponents.ITEM_MODEL` ✅ exists (`DataComponentType<Identifier>`) and can
retarget a stack at any *vanilla* item model without a resource pack. **Not
needed in v1** — the two hosts already look nothing alike — but it is the escape
hatch if a third focus needs a distinct icon later.

Writing `custom_data` — verified API, same as `spiritwolves`:

```java
CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> { ... });
stack.get(DataComponents.CUSTOM_DATA).copyTag();
```

### Hurl Focus

- Base: **`minecraft:firework_star`**
- Animation: `ItemUseAnimation.BRUSH` (a low sweeping motion — reads as gathering)
- Lore: *"Hold to gather stone. Release to throw."* / *"Longer holds throw heavier."*
- The firework star renders tinted by its `firework_explosion` component. Set a
  grey explosion so it reads as stone rather than a leftover firework.

### Wall Focus

- Base: **`minecraft:heart_of_the_sea`**
- Animation: `ItemUseAnimation.BOW` (a braced, two-handed pull)
- Lore: *"Hold and release to raise a wall."* / *"Look down to lay a bridge."* /
  *"Sneak-use to recall your wall."*

---

## 6. The Hurl Focus

Three boulder tiers selected purely by charge duration. Pitch is **aim only** —
it does not change the ability.

| Tier | Released at | Cobble | Damage | Speed | Gravity/tick | Display scale | Cooldown |
|---|---|---|---|---|---|---|---|
| **Light** | < 10 ticks | 1 | 3.0 (1½♥) | 1.6 b/t | 0.02 | 0.5 | 8t |
| **Medium** | 10–24 ticks | 3 | 6.0 (3♥) | 1.3 b/t | 0.04 | 0.9 | 20t |
| **Heavy** | ≥ 25 ticks | 6 | 9.0 (4½♥) | 1.0 b/t | 0.06 | 1.4 | 60t |

Charging past 25 ticks adds nothing — that is deliberate, so the ceiling is
learnable by feel rather than by reading a bar.

**Heavy additionally applies** strong knockback along the travel vector plus
`Slowness III` for 30 ticks. That is the stagger; it is what makes Heavy worth
the 60-tick cooldown against a mob that is already on top of you.

### Projectile implementation

Use **`Display$BlockDisplay`** ✅ moved manually with a raycast each tick, not
`FallingBlockEntity`. `FallingBlockEntity` brings its own gravity, has no entity
hit detection, and *places a block or drops an item* when it lands — all three
are wrong here, and the last one breaks §1.

> **⚠ Verified gotcha:** `Display$BlockDisplay` has **no public block-state
> setter.** Its only accessors are `blockRenderState()` (getter) and NBT
> save/load via the `TAG_BLOCK_STATE` key. Spawn it by building a `CompoundTag`
> and loading it, exactly the way `spiritwolves` restores a wolf:
>
> ```java
> var display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
> ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
> display.load(in);   // tag carries block_state + transformation scale
> ```

Per-tick projectile step:

1. Raycast from current position along the velocity vector for this tick's
   distance (`ClipContext`, blocks + entities).
2. Entity hit → damage, knockback, tier effects, despawn the display.
3. Block hit → burst of `ParticleTypes.BLOCK` with `Blocks.COBBLESTONE`
   default state, play a stone break sound, despawn. **Place nothing.**
4. Otherwise advance the display and apply gravity to the velocity.
5. Hard despawn after 60 ticks or 64 blocks travelled, whichever is first.

Never damage the caster. Player-vs-player damage is behind a config flag,
default **off**.

---

## 7. The Wall Focus

Pitch selects the ability at the moment of **release**:

| Pitch | Ability |
|---|---|
| More than **60° down** | **Bridge** |
| Anything else | **Wall** |

60°, not 90° — nobody reliably hits straight down, and anything past 60 reads
unambiguously as "down."

**Sneak + release** → **Recall**: dissolve your standing wall, refund ⅔ of the
cobble it cost (rounded down). Recall is free and has no cooldown.

### Wall tiers

| Tier | Released at | Size (W×H) | Cobble | Cooldown |
|---|---|---|---|---|
| **Light** | < 10 ticks | 3 × 2 | 6 | 20t |
| **Medium** | 10–24 ticks | 3 × 3 | 9 | 30t |
| **Heavy** | ≥ 25 ticks | 5 × 3 | 15 | 60t |

**One wall per player.** Casting again while a wall stands recalls the old one
first (with refund), then builds the new one. This single rule is what makes the
ability safe — it caps concurrent bent blocks at 15 per player with no separate
budget system.

Wall lifetime: **240 ticks (12s)**, then decays.

### Placement algorithm

```
1. Raycast from eyes, max 6 blocks (blocks only, ignore entities).
   - Hit    → anchor = hitResult.getBlockPos().relative(hitResult.getDirection())
              ...the AIR block adjacent to the face, NOT the solid block hit.
   - Miss   → anchor = eyePos + look * 6
   - Clamp  → if the anchor is within 2 blocks of the player, push it out to 2.
2. Snap player yaw to the nearest cardinal (N/S/E/W).
   The wall runs perpendicular to that facing.
3. FOR EACH COLUMN independently:
   - scan DOWN up to 4 for the first solid block → base = thatPos.above()
   - if the anchor is buried, scan UP to the first air instead
   - clamp each column's base to within ±2 of the centre column's base
4. Build upward from each column's own base.
   Replace ONLY air or replaceable blocks. Skip anything else.
5. Charge cobble only for blocks actually placed.
   If zero blocks placed → no cost, no cooldown, actionbar explains.
```

**The per-column ground scan is the important step.** A single shared ground
level makes the wall float at one end and bury itself at the other on any slope,
which reads as "a structure spawned" rather than "the earth heaved up." The ±2
clamp stops it shearing apart at a cliff edge.

Use **4 cardinals, not 8.** A diagonal wall voxelises into an offset staircase
with diagonal shoot-through gaps and doubles the placement code for a worse
result.

Do **not** skip blocks that intersect a mob — place anyway and let vanilla shove
it out. A wall with mob-shaped holes is a broken wall. Never place inside the
casting player's own bounding box.

### Bridge

A **sustained channel**, not a charge-and-release. While the Focus is held *and*
pitch is past 60° down:

- Each tick the player's horizontal position enters a new block column, place
  cobblestone at the block below their feet **if it is air or replaceable**.
- Cost **1 cobble per block placed**. Out of cobble → the channel ends.
- Bridge blocks decay after **200 ticks (10s)** — but the timer **refreshes
  while any player's bounding box is standing on them.** A bridge crumbles
  behind you, never underneath you. Dropping a player into a ravine because they
  paused is the wrong kind of risk.
- Bridge blocks count against the same tracked-block ledger as walls but are
  exempt from the one-wall rule.

---

## 8. Block tracking and anti-grief

This section decides whether the mod is installable on a real server. All of it
is mandatory.

1. **Only ever replace air or replaceable blocks.** Never break, never overwrite
   a player's build.
2. **Every placed block is tracked** with its prior `BlockState` and reverted on
   decay, not merely set to air.
3. **Full cleanup on `ServerLifecycleEvents.SERVER_STOPPING`.** Revert every
   tracked block. Simpler and far safer than persisting a block ledger across
   restarts — a crash leaves at most a few seconds of cobble.
4. **Per-player cap** on tracked blocks (default 64). Oldest decays first.
5. **No-bend radius around world spawn**, coordinate-based and configurable, so
   there is no dependency on a claims mod ([DESIGN.md §2](../DESIGN.md) holds).
6. **PvP damage default off**, config toggle.
7. Bending never fires while the player is in spectator or adventure mode.

---

## 9. Ammunition

Cobblestone is consumed from **anywhere in the player's inventory**, searched
offhand → hotbar left-to-right → main inventory. Count first, fire second: an
ability that cannot be fully paid for does not fire at all, costs nothing, and
burns no cooldown.

Actionbar on failure: `Not enough cobblestone (need 9, have 4)`.

---

## 10. Events — all verified present

| Purpose | API | Jar |
|---|---|---|
| Charge start / release polling | `ServerTickEvents.END_SERVER_TICK` | fabric-lifecycle-events-v1 ✅ |
| Shutdown block cleanup | `ServerLifecycleEvents.SERVER_STOPPING` | fabric-lifecycle-events-v1 ✅ |
| Fallback if block-aimed use fails | `UseBlockCallback` | fabric-events-interaction-**v0** |

Note the interaction module is **v0**, not v1 — same trap `spiritwolves`
documents. `UseItemCallback` returns `InteractionResult` in 26.2, not
`TypedActionResult`.

Cooldowns use vanilla:

```java
player.getCooldowns().addCooldown(stack, ticks);
```

Set `DataComponents.USE_COOLDOWN` on the Focus so the vanilla client renders the
sweep. **This is the whole cooldown HUD** — no actionbar bar, no custom
rendering, nothing installed.

---

## 11. Commands

Gate admin commands with `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`,
per suite convention.

- `/cobblebending give <targets> <hurl|wall>` — admin, grants a Focus
- `/cobblebending reload` — re-read config
- `/cobblebending clear [targets]` — force-revert tracked blocks (ops, for when
  playtesting goes wrong)

---

## 12. Sound cues

Match the suite's `Chime.java` pattern — `ClientboundSoundPacket` to the
player's connection, `SoundSource.RECORDS`, quiet. These are confirmations, not
fanfares ([DESIGN.md §4.8](../DESIGN.md)).

| Event | Sound | Volume |
|---|---|---|
| Charge reaches Medium | `NOTE_BLOCK_BASS` | 0.15 |
| Charge reaches Heavy | `NOTE_BLOCK_BASS`, higher pitch | 0.2 |
| Hurl released | `STONE_BREAK`, pitch by tier | 0.3 |
| Boulder impact | `STONE_HIT`, low pitch | 0.4 |
| Wall raised | `STONE_PLACE` | 0.35 |
| Wall recalled | `NOTE_BLOCK_HAT` | 0.2 |
| Out of cobble | `NOTE_BLOCK_DIDGERIDOO`, low | 0.25 |

The two charge-tier chimes are load-bearing — they are how the player learns the
timing windows without a HUD. Get them right before anything else.

---

## 13. Module layout

Single module, no core split — matches `spiritwolves` and `dailyquests`.

```
a:\MrPinoys Mods\cobblebending\
    build.gradle.kts
    settings.gradle.kts
    gradle/ + gradlew            (copy from spiritwolves)
    src/main/java/cobblebending/
        CobbleBendingMod.java    — entrypoint, event + command registration
        Focus.java               — stack construction, custom_data read/write
        ChargeTracker.java       — per-player use polling, release detection
        Hurl.java                — tier resolution, projectile spawn
        Boulder.java             — BlockDisplay projectile tick + raycast
        Wall.java                — placement algorithm, recall
        Bridge.java              — channel tick, per-block placement
        BentBlocks.java          — tracked-block ledger, decay, revert, cleanup
        Ammo.java                — inventory cobble count + consume
        BendConfig.java          — readOrCreate config
        BendCommands.java        — /cobblebending tree
        Chime.java               — per-player sound cues
    src/main/resources/
        fabric.mod.json
```

Config at `config/cobblebending/config.json`, generated on first boot via
`readOrCreate` — write defaults if missing, log it, **never overwrite a file
that failed to parse** ([DESIGN.md §4.5](../DESIGN.md)). Every number in §6, §7,
§8 is a config key.

---

## 14. Build configuration

Copy from `spiritwolves` and change the names.

- **Minecraft 26.2**, Fabric Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"` in `fabric.mod.json`
- **No Mixins.** §4 is why this is achievable — keep it that way
- Loom `1.17-SNAPSHOT`, applied in `settings.gradle.kts`, **not** in the
  `build.gradle.kts` `plugins {}` block
- `archivesName = "MrPinoys_cobblebending"`
- A `dist` `Copy` task from `build/libs/` to
  `rootProject.projectDir.parentFile.resolve("dist")`, excluding sources jars,
  with `build` `finalizedBy("dist")`

> **⚠ The one bug every mod in this suite hit:** the `dist` task must depend on
> **`jar`**, *not* `remapJar`. Loom does not register a `remapJar` task because
> 26.2 ships unobfuscated. Depending on `remapJar` makes `./gradlew build` fail
> outright. This has now cost time in seven mods — do not repeat it.

- No Yarn mappings. Mojang official names throughout.
- `Identifier`, **not** `ResourceLocation` (renamed in 26.1).

---

## 15. Shop integration

Sell both Focuses through `cobbleeconomy`'s `shop.json` `components` block —
never an integration file, never an api module ([DESIGN.md §3](../DESIGN.md)).

```json
"hurl_focus": {
  "item": "minecraft:firework_star",
  "quantity": 1, "price": 18, "currency": "diamond", "category": "Bending",
  "components": {
    "minecraft:custom_data": { "cobblebending": { "focus": "hurl" } },
    "minecraft:item_name": "Hurl Focus",
    "minecraft:max_stack_size": 1
  }
}
```

> **Open question.** The `consumable` component is what makes the Focus *work*,
> and it must be present on the sold stack. Confirm `shop.json`'s `components`
> parser can express a `minecraft:consumable` block. If it cannot, the fallback
> is for `cobblebending` to stamp the component on any correctly-marked stack it
> sees in a player's hand — a one-line fix-up in `ChargeTracker`, and arguably
> more robust anyway since it self-heals Focuses from older versions.

---

## 16. Definition of done

- [ ] `./gradlew build` succeeds; `MrPinoys_cobblebending-0.1.0.jar` lands in `dist/`
- [ ] Right-click with either Focus enters a visible hold pose; release fires
- [ ] **Holding for 13 minutes never consumes the Focus** — the §4 infinite-duration test
- [ ] No eating particles and no consume sound at any charge length
- [ ] Charge tier chimes fire at 10 and 25 ticks and are learnable by ear
- [ ] Hurl Light/Medium/Heavy differ visibly in size, speed, arc and damage
- [ ] Heavy staggers a zombie — knockback plus visible slow
- [ ] A boulder impact **places no block and drops no item**
- [ ] Wall forms perpendicular to facing, snapped to cardinal
- [ ] **Wall on a slope hugs the terrain** — per-column ground scan works
- [ ] Wall aimed at the sky from a hilltop still forms at 6 blocks out
- [ ] Wall aimed at a cliff face 1 block away does not seal the player in
- [ ] Wall never overwrites a player-placed block
- [ ] Casting a second wall recalls the first and refunds ⅔
- [ ] Wall decays after 12s and **reverts to the prior block state**, not to air
- [ ] Bridge lays blocks while walking with pitch past 60°
- [ ] **Standing still on a bridge block does not drop the player** — timer refreshes
- [ ] Ability with insufficient cobble fires nothing and burns no cooldown
- [ ] Vanilla cooldown sweep renders on the Focus after each cast
- [ ] `SERVER_STOPPING` reverts every tracked block
- [ ] Right-clicking while aimed at a nearby block still starts the charge
- [ ] A fully vanilla client can do all of the above with nothing installed

---

## 17. Explicitly out of scope for v1

Stone Armor, Shelter/dome, Surf, Seismic Sense, Root, Tremor, mobility focuses,
deepslate/sand/metal/magma disciplines, focus durability and diamond repair
costs, mastery or progression of any kind, PvP balance tuning beyond the on/off
flag, crafting recipes, and **any interaction with the other seven mods**.

Two focuses, two verbs, three tiers each. Prove the charge-and-release control
scheme is fun before building anything on top of it.

> **The next thing to build, if v1 lands well,** is focus durability with anvil
> repair in diamonds — it reuses the verified `spiritwolves` anvil mechanic
> wholesale and converts this mod from a cobble sink into a diamond sink as
> well, which is what [DESIGN.md §5](../DESIGN.md) actually asks for.
