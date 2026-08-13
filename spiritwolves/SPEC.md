# MrPinoy's Spirit Wolves — Build Spec

> **Status:** v1 implemented and tested in play, 2026-08-09 — see §13. v2
> implemented 2026-08-09, build-verified but not yet exercised in play — see
> §15. All Minecraft/Fabric API signatures in this document were verified
> against the actual 26.2 merged jar and the Fabric API jars in the Gradle
> cache. Where something is *not* verified, it says so explicitly.

---

## 1. The problem this solves

Tamed wolves die and players are heartbroken. A **Spirit Stone** binds a wolf's
soul permanently. The wolf can never truly die — lethal damage instead pulls it
back into the stone and burns one charge. Charges are restored at an anvil with
diamonds.

The stone *is* the wolf. Lose the stone, lose the wolf. The bond is permanent
and cannot be released.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Bond | **Permanent** — no release, no rebind | The stone is the wolf's home |
| Base item | **`minecraft:echo_shard`** | Soul/echo flavor, packless |
| `max_damage` | **4** | Vanilla anvil math makes 1 diamond = exactly 1 charge |
| Charges represent | **Lives, not summons** | Summon/recall are free and unlimited |
| Scope | **Base wolf only** | Upgrades are a later milestone, out of scope |
| Cross-mod | **None** | Fully standalone. No cobbleeconomy, no bounties, nothing |

---

## 3. Core loop

1. Player tames a wolf normally (vanilla).
2. Player right-clicks that wolf with an **unbound Spirit Stone**.
   Wolf vanishes into the stone. Stone becomes **bound**, gains 4 charges.
3. Right-click the stone in hand → **summon**. Wolf appears at the player.
4. Right-click again → **recall**. Wolf returns to the stone. Free.
5. Wolf takes lethal damage → **death is cancelled**, wolf is restored to full
   health, stored back into the stone, **one charge consumed**.
6. At **0 charges** the wolf cannot be summoned. It is not dead — it is dormant
   inside the stone until repaired.
7. **Anvil + diamonds** restores charges. 1 diamond = 1 charge, up to 4.

---

## 4. The anvil repair is free — verified

This was the single biggest technical risk. It is resolved: **vanilla handles
the entire repair with no code, no event hook, and no Mixin.**

`AnvilMenu.createResult()` bytecode, verified:

```java
if (input.isDamageableItem() && input.isValidRepairItem(material)) {
    int perUnit = Math.min(input.getDamageValue(), input.getMaxDamage() / 4);
    // loops, consuming up to material.getCount() items, perUnit damage each
}
```

With `max_damage = 4`, `getMaxDamage() / 4 == 1`. One diamond repairs exactly
one point of damage — **one charge**. Insert up to 4 diamonds to fully restore.
The vanilla XP level cost applies on top, which is a free second sink.

Both preconditions are purely component-driven — **no `Item` subclass required,
and notably no max-stack-size check**:

```java
// ItemStack.isDamageableItem() — verified bytecode
has(DataComponents.MAX_DAMAGE) && !has(DataComponents.UNBREAKABLE) && has(DataComponents.DAMAGE)

// ItemStack.isValidRepairItem(other) — verified bytecode
get(DataComponents.REPAIRABLE) != null && repairable.isValidRepairItem(other)
```

**Gotcha:** `Repairable` lives in `net.minecraft.world.item.enchantment`, *not*
`world.item.component` as you'd expect. Its constructor takes a
`HolderSet<Item>`:

```java
DataComponents.REPAIRABLE  // DataComponentType<net.minecraft.world.item.enchantment.Repairable>
new Repairable(HolderSet<Item> items)
```

---

## 5. Item construction

### Unbound stone

Follows the `wondrous` convention exactly — a vanilla item marked with
`custom_data`, never a registry entry, so vanilla clients need nothing.

- Base: `minecraft:echo_shard`
- `custom_data`: `{ spiritwolves: { bound: false } }`
- `item_name`: "Spirit Stone"
- `lore`: a hint line, e.g. *"Use on a tamed wolf to bind its soul."*
- **Stackable, no durability.** Do not add damage components until bound.

### Bound stone

On successful binding, apply:

| Component | Value |
|---|---|
| `custom_data` | `{ spiritwolves: { bound: true, wolf: <CompoundTag>, summoned: false, uuid: <string> } }` |
| `max_damage` | `4` |
| `damage` | `0` |
| `max_stack_size` | `1` |
| `repairable` | `HolderSet.direct(Items.DIAMOND.builtInRegistryHolder())` |
| `custom_name` | `"<wolf name>'s Spirit Stone"`, or "Spirit Stone" if unnamed |
| `lore` | wolf name, collar colour, charges remaining |

`max_stack_size: 1` is not strictly required by `isDamageableItem()` — verified
— but set it anyway. A stack of 64 damageable items is asking for trouble.

**Writing custom_data** — verified API:

```java
CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> { ... });
CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
stack.get(DataComponents.CUSTOM_DATA).copyTag();   // read
```

---

## 6. Storing the wolf — use full NBT, not a curated record

**This reverses an earlier design instinct, and the reason matters.**

In 26.2, `Wolf` no longer exposes `setCollarColor()` or `getVariant()`. Collar,
variant, and sound variant are now *entity data components*:

```java
DataComponents.WOLF_VARIANT        // DataComponentType<Holder<WolfVariant>>
DataComponents.WOLF_SOUND_VARIANT  // DataComponentType<Holder<WolfSoundVariant>>
DataComponents.WOLF_COLLAR         // DataComponentType<DyeColor>
```

`Wolf` implements `get(DataComponentType)` and `applyImplicitComponent(...)`,
but hand-rolling a curated record means reimplementing component plumbing for
every field and getting it subtly wrong. Full-entity NBT capture is both
**more faithful and less code**, and it automatically preserves things a
curated record would miss — armor, age, anger state, attributes.

Verified round-trip API:

```java
// Capture
ProblemReporter reporter = new ProblemReporter.ScopedCollector(...); // or Discarding
TagValueOutput out = TagValueOutput.createWithContext(reporter, level.registryAccess());
wolf.saveWithoutId(out);
CompoundTag wolfTag = out.buildResult();

// Restore
ValueInput in = TagValueInput.create(reporter, level.registryAccess(), wolfTag);
Wolf wolf = EntityType.WOLF.create(level, EntitySpawnReason.COMMAND);
wolf.load(in);
wolf.setUUID(storedUuid);   // load() restores it, but be explicit
wolf.snapTo(x, y, z, yaw, pitch);  // override the stored position
level.addFreshEntity(wolf);
```

`saveWithoutId` includes the UUID, which is what makes the duplicate check in
§8 work.

**Size note:** this puts a few hundred bytes of NBT on the item stack, which is
synced to clients. Negligible, and vanilla clients ignore unknown `custom_data`
happily. If it ever becomes a problem, the fallback is to move the wolf tag
into a server-side per-player JSON (your standard debounced-atomic-write
pattern) keyed by the stone's UUID, leaving only that UUID on the item. Charges
must stay on the item either way, because the anvil reads them.

---

## 7. Events — all verified present

| Purpose | API | Jar |
|---|---|---|
| Right-click wolf to bind | `UseEntityCallback.EVENT` | fabric-events-interaction-**v0** |
| Right-click stone to summon/recall | `UseItemCallback.EVENT` | fabric-events-interaction-**v0** |
| Intercept wolf death | `ServerLivingEntityEvents.ALLOW_DEATH` | fabric-entity-events-v1 |

Signatures:

```java
InteractionResult interact(Player, Level, InteractionHand, Entity, EntityHitResult);  // UseEntityCallback
InteractionResult interact(Player, Level, InteractionHand);                            // UseItemCallback
boolean allowDeath(LivingEntity, DamageSource, float);                                 // AllowDeath
```

Note the interaction module is **v0**, not v1 — `fabric-events-interaction-v0`.
`UseEntityCallback` fires before vanilla `Wolf.mobInteract`, and an echo shard
is neither food nor dye, so there is no interaction conflict.

Relevant `TamableAnimal` API, verified:

```java
boolean isTame();
EntityReference<LivingEntity> getOwnerReference();   // NOT getOwner() returning an entity
void setOwner(LivingEntity);
void setOrderedToSit(boolean);
boolean isOrderedToSit();
```

`Wolf` moved package in 26.x — it is now
**`net.minecraft.world.entity.animal.wolf.Wolf`**.

---

## 8. Rules and edge cases

**Binding requires** all of: stone is unbound; target is a `Wolf`; `isTame()`;
owner resolves to the interacting player. Reject with a red chat message
otherwise. Consume one stone from the stack.

**Duplicate prevention.** Before spawning, look up the stored UUID in the level.
If a wolf with that UUID already exists, **teleport it to the player instead of
spawning a new one**. This one rule handles item duplication, server restarts
with the wolf still out, and chunk-unload weirdness — all through the same code
path. Do not skip it.

**Ownership invariant.** The wolf despawns (recalls) if the owning stone is not
in the owner's inventory, or the owner logs out. Poll the owner's inventory
every 20–40 ticks; one wolf per player makes this trivially cheap. This is what
prevents orphaned wolves with no stone to decrement on death.

**Persistence.** Call `setPersistenceRequired()` on the summoned wolf so it
never despawns naturally.

**Death interception.** In `ALLOW_DEATH`: if the entity is a bound spirit wolf
and charges remain, return `false` to cancel, then heal to max, serialize back
into the stone, decrement damage by 1, and notify. If charges are exhausted the
wolf still should not die — it should already be inside the stone, since it can
never be summoned at 0 charges.

**`/kill` and the void.** `ALLOW_DEATH` fires for these too, so the stone saves
the wolf. That is the correct behaviour for this feature — verify in play, but
do not add special-casing.

**Server crash mid-session.** Stone says `summoned: true`, wolf exists as a
normal entity. The UUID lookup on next summon reconciles it. If the entity is
genuinely gone, allow a fresh spawn.

---

## 9. Commands

Follow the suite convention — gate admin commands with
`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.

- `/spiritwolves give <targets> [count]` — admin, grants unbound stones
- `/spiritwolves info` — prints the held stone's wolf name, collar, charges

**Acquisition is an open question for v1.** The admin command is enough to
test. A datapack crafting recipe with a component-carrying result is the
natural follow-up, but it is not needed to ship the base feature.

---

## 10. Sound cues

Match the suite's `Chime.java` pattern — `ClientboundSoundPacket` sent directly
to the player's connection, `SoundSource.RECORDS`, note block sounds, quiet.

| Event | Sound | Volume |
|---|---|---|
| Bind succeeded | `NOTE_BLOCK_BELL` | 0.3 |
| Summon | `NOTE_BLOCK_CHIME` | 0.3 |
| Recall | `NOTE_BLOCK_HAT` | 0.2 |
| Death save (charge burned) | `NOTE_BLOCK_BASS`, low | 0.4 |
| Last charge consumed | `NOTE_BLOCK_DIDGERIDOO` | 0.4 |

The death save should feel like a *near miss*, not a reward. Make it the most
noticeable cue in the mod.

---

## 11. Module layout

Single module, no core split — matches `dailyquests`. Almost everything here is
Minecraft-facing, so a pure-Java module would hold nothing worth testing.

```
a:\MrPinoys Mods\spiritwolves\
    build.gradle.kts
    settings.gradle.kts
    gradle/ + gradlew   (copy from quizengine)
    src/main/java/spiritwolves/
        SpiritWolvesMod.java   — entrypoint, event + command registration
        SpiritStone.java       — stack construction, custom_data read/write
        WolfCapture.java       — NBT save/restore round-trip
        Binding.java           — UseEntityCallback: wolf -> stone
        Summoning.java         — UseItemCallback: summon / recall / teleport-existing
        Deaths.java            — ALLOW_DEATH interception, charge decrement
        Tracker.java           — ownership invariant poll, logout recall
        SpiritCommands.java    — /spiritwolves tree
        Chime.java             — per-player sound cues
    src/main/resources/
        fabric.mod.json
```

---

## 12. Build configuration

Copy from `dailyquests` and change the names. Suite conventions that matter:

- **Minecraft 26.2**, Fabric Loader 0.19.3+, **JDK 25**
- `"environment": "server"` in `fabric.mod.json` — vanilla clients need nothing
- **No Mixins.** This mod does not need one, and §4 is why. Keep it that way.
- Loom `1.17-SNAPSHOT`, applied in `settings.gradle.kts`, **not** in the
  `build.gradle.kts` `plugins {}` block (Kotlin DSL restricted scope can't
  resolve `property()` there)
- `archivesName = "MrPinoys_spiritwolves"`
- A `dist` `Copy` task from `build/libs/` to
  `rootProject.projectDir.parentFile.resolve("dist")`, excluding sources jars,
  with `build` `finalizedBy("dist")`

> **⚠ The one bug every mod in this suite hit:** the `dist` task must depend on
> **`jar`**, *not* `remapJar`. Loom does not register a `remapJar` task, because
> 26.2 ships unobfuscated. Depending on `remapJar` makes `./gradlew build` fail
> outright. This cost time in all six existing mods — do not repeat it.

- No Yarn mappings. Mojang official names throughout.
- `Identifier`, **not** `ResourceLocation` (renamed in 26.1).

---

## 13. Definition of done

> **Tested in play and confirmed 2026-08-09.**

- [x] `./gradlew build` succeeds; `MrPinoys_spiritwolves-0.1.0.jar` lands in `dist/`
- [x] Right-clicking a tamed wolf with an unbound stone binds it; wolf vanishes
- [x] Binding a wolf tamed to someone else is rejected
- [x] Right-click summons; right-click again recalls; both free and repeatable
- [x] Summoned wolf keeps its **name, collar colour, and variant**
- [x] Killing the wolf consumes exactly one charge and returns it to the stone
- [x] Wolf cannot be summoned at 0 charges; message explains why
- [x] Anvil + 1 diamond restores exactly 1 charge — **the critical §4 test**
- [x] Anvil + 4 diamonds fully restores a 0-charge stone
- [x] Wolf despawns on logout and can be resummoned on return
- [x] Duplicating the stone does not produce two wolves
- [x] Wolf survives a server restart while summoned

---

## 14. Explicitly out of scope for v1

Upgrades, XP/levels, multiple wolves per player, bears, allays, rituals beyond
the right-click bind, crafting recipes, and **any interaction with the other
five mods**. Get the base wolf robust first.

---

## 15. v2 — the wolf grows (implemented)

> **Status:** implemented 2026-08-09. Both open verification questions resolved
> against the 26.2 merged jar — see §15.5. New modules: `Journal.java`,
> `Senses.java`, `Fetch.java`.


The wolf is a combat pet — of course it should get stronger over time. What
was rejected was *permanent* progression: an XP bar, a talent tree, a stone
tier ladder. Those make a veteran wolf permanently superior to a fresh one,
which is a WoW-hunter pattern, not a Minecraft one. What was kept is
*per-outing* power (the killstreak in §15.7) and *presence* (the journal,
senses, and veterancy cosmetics below) — the wolf grows within each outing and
feels known, but two wolves summoned side by side are always identical.

Also rejected reinventing wolf gear: **vanilla wolf armor already exists**
(`EquipmentSlot.BODY`, armadillo scutes) and round-trips through `WolfCapture`
for free since capture/restore already snapshots the whole entity, armor slot
included. No new code needed there — it just works.

All server-side-safe, none of it touches binding, death-save, or the anvil
math in §4.

### 15.1 The living journal

The stone's lore accumulates the wolf's history automatically, appended (not
replaced) on top of the name/collar/charges lines `SpiritStone.refreshLore`
already writes. Trigger points, all hooks that already exist in this mod:

| Event | Lore line appended | Hook |
|---|---|---|
| First bind | *"Bound `<date>`."* | `Binding` |
| Death saved | *"Cheated death, `<n>` time(s)."* (update count, don't duplicate) | `Deaths` |
| Fall damage source saved a life | *"Fell and lived."* | `Deaths`, check `damageSource` |
| `/kill` or void saved a life | *"Walked back from the void."* | `Deaths`, check `damageSource.is(DamageTypes....)` |
| Long time bound, never below half charges | *"Never truly tested."* | periodic in `Tracker` poll (cosmetic only) |

Cap total lore lines (e.g. 6) and drop the oldest achievement-style line first,
keeping name/collar/charges pinned. Pure text — zero balance risk, the whole
feature is the tooltip.

### 15.2 Senses

- **Growl on nearby danger.** In the `Tracker` poll, for each summoned wolf
  check `level.getEntitiesOfClass(Monster.class, wolf.getBoundingBox().inflate(16), Monster::isAlive)`.
  If non-empty and not on cooldown, play the wolf's *own* growl —
  `wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().growlSound()`
  via `wolf.playSound(holder.value(), volume, pitch)` — not a generic sound
  event. Verified: `WolfSoundVariant.WolfSoundSet` exposes `growlSound()`;
  there is no dedicated "wolf howl" sound in vanilla, so meaningful-moment
  audio reuses growl/ambient rather than inventing one.
- **Mark prey.** Sneak right-click the summoned wolf (empty hand) → nearest
  live `Monster` within ~16 blocks gets `MobEffects.GLOWING` for a few
  seconds via `target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks))`.
  Confirmed present in `net.minecraft.world.effect.MobEffects`.
- **Fetch.** On a kill by the summoned wolf (needs a kill-attribution hook —
  likely `ServerLivingEntityEvents.AFTER_DEATH` checking
  `damageSource.getEntity() instanceof Wolf`, matched against the tracked
  wolf UUID), pick up any resulting drops within a short window and hand them
  to the owner via the same `player.getInventory().add(...)` pattern used
  elsewhere in this suite.

### 15.3 Visible veterancy via scale

`Attributes.SCALE` is a real, verified generic attribute
(`net.minecraft.world.entity.ai.attributes.Attributes.SCALE`) readable via
`wolf.getAttribute(Attributes.SCALE)` and settable with
`.setBaseValue(double)`. On summon, after restoring the wolf, set scale based
on bound-duration or death-save count — e.g. `1.0 + min(0.15, saves * 0.03)`.
Small, capped, cosmetic; vanilla clients render it with no extra work.

> **As built, scale is driven by the killstreak in §15.7, not by bound duration
> or death-save count** — see §15.5 for why both original formulas were
> discarded.

### 15.4 What stays cut

No permanent XP bar, no talent tree, no charm-socket UI, no stone tier ladder.
Permanent progression that makes a veteran wolf strictly superior to a fresh
one is the WoW-hunter pattern this mod rejects. Per-outing power (the
killstreak) and presence (journal, senses, scale) are the progression model.
If a diamond sink beyond anvil repair is ever wanted later, it's a trivial
follow-up.

> **Superseded by §18.** The permanence objection was reconsidered: the wolf
> is a combat pet and permanent growth is the point. §18 defines the
> soul-forged progression system. This section stands as the record of what v2
> shipped and why, not as current policy.

### 15.5 As-built notes

The two items flagged unverified at spec-writing time were both checked against
the merged jar before coding, and both came back workable:

1. **Fall/void damage type constants.** `DamageTypes.FALL`,
   `DamageTypes.FELL_OUT_OF_WORLD`, and `DamageTypes.GENERIC_KILL` all exist as
   `ResourceKey<DamageType>`, and `DamageSource.is(ResourceKey<DamageType>)` is
   a real overload alongside `is(TagKey<DamageType>)`. `/kill` and the void are
   folded into one "walked back from the void" line.
2. **Empty-hand right-click collision.** Real, and handled.
   `Wolf.mobInteract` *does* toggle sit/stand on an empty-hand click from the
   owner, and it does **not** check sneaking. But Fabric's
   `ServerGamePacketListenerImplMixin` calls `CallbackInfo.cancel()` on any
   non-PASS `UseEntityCallback` result, so vanilla never runs. Mark-prey claims
   the *sneak* variant of the click and returns SUCCESS; the plain click still
   sits the wolf as normal. Nothing was descoped.

Implementation details worth knowing:

- **Journal entries are keyed**, stored as a `ListTag` of `{k, t}` compounds
  under `custom_data`. Rewriting a key replaces in place — that is what makes
  "Cheated death, `<n>` times" a running count instead of a pile of duplicates.
  Stored entries cap at 4; the lore render caps the *total* at 6 and drops the
  oldest journal lines first, leaving name/collar/charges pinned.
- **The pinned lines are cached on the stone** (`name`/`collar` in
  `custom_data`). `refreshLore` can now be called with nulls — as the `Tracker`
  poll does for the cosmetic "never truly tested" line, with no live wolf to
  read from — without blanking the header.
- **Fetch sweeps the kill site** rather than hooking loot generation: a 60-tick
  window, 3-block radius, re-swept every 10 ticks. Version-proof, and it does
  not care whether drops spawn before or after `AFTER_DEATH` fires. Items with
  a pickup owner set are skipped so player-tossed items are never vacuumed.
  Note `Inventory.add` shrinks the stack it is handed *in place* and returns
  whether **any** of it fit, not all of it — the partial case pushes the
  remainder back with `setItem` so ground counts resync.
- **Scale is driven by the killstreak in §15.7, not by bound duration or
  death-save count.** Both of §15.3's original suggestions were tried and
  discarded — see §15.7 for the reasoning and the final formula.
- **Hot-path cost.** `Journal.checkUntested` runs for every bound stone on
  every poll, so its guards are ordered cheapest-first (the charge check reads
  components only; the age check rejects nearly every call before any further
  `custom_data` copy). `Fetch` pre-filters on `wolf.isTame()` before
  `Tracker.findOwner`, since wild wolves hunt too and `findOwner` walks every
  player's inventory.

### 15.6 v2 definition of done

- [x] `./gradlew build` clean; jar updates in `dist/`
- [x] Journal schema (`journal` list, `saveCount`, `boundAt`) added without
      disturbing the v1 `{ bound, wolf, summoned, uuid }` fields
- [x] Bind, death-save, fall, and void trigger points wired
- [x] Growl on nearby monsters, per-wolf cooldown, wolf's own sound variant
- [x] Sneak + empty-hand mark-prey, no collision with vanilla sit/stand
- [x] Fetch delivers kill drops to the owner
- [x] Scale applied post-restore, driven by the §15.7 killstreak, capped at +0.15
- [x] Killstreak tiers grant capped attack damage; streak resets on any return
- [x] Recall locked for 5s after summon and after damage
- [ ] **In-play verification still outstanding** — none of the above has been
      exercised on a running server yet

### 15.7 Killstreak — per-outing combat progression

The wolf is a combat pet. It should hit harder when it's been fighting well.
The design constraint is not "no combat power" — it's **no permanent combat
power**. A killstreak delivers per-outing power that resets on return, so a
veteran wolf and a fresh wolf are identical at summon time.

**What it does.** While the wolf is out it accumulates a killstreak. Discrete
tiers, because readable milestones beat a smooth 0.03-per-kill ramp:

| Streak | Size | Attack bonus |
|---|---|---|
| 0–2 | 1.00× | — |
| 3–5 | 1.05× | +0.5 |
| 6–9 | 1.09× | +1.0 |
| 10–14 | 1.13× | +1.5 |
| 15+ | 1.15× | +2.0 (cap) |

A vanilla wolf's base attack is **4.0** (verified in `Wolf.createAttributes`),
so the cap is a +50% swing. Size stays inside §15.3's original ±0.15 budget.

**Why a killstreak instead of an XP ladder.** An XP ladder makes a wolf that
is *permanently* stronger than a fresh one — the WoW-hunter pattern §15
rejects. A killstreak is the opposite: it is **per-outing state**,
it is never written to the stone, and it is gone the moment the wolf goes back
inside by any route — recall, death-save, logout, or reconcile. Two wolves
summoned side by side are always identical. Nothing accumulates.

**The three rules are load-bearing together.** A killstreak on its own would be
a bad mechanic, because the correct play on spotting danger becomes *recall the
wolf to protect the streak* — a buff that punishes you for using the thing it
buffs. What defuses that:

1. **Recall resets the streak.** There is nothing to bank, so pulling the wolf
   out never preserves anything.
2. **Recall is locked for 5s after summon and 5s after any damage taken.** You
   cannot yank the wolf out of a fight it is losing. Committing it is a real
   commitment.
3. **Death resets it too**, but costs a charge as it always did — the streak
   loss is not an extra fine, because there was never anything saved to lose.

Remove any one of the three and the mechanic goes bad. They ship together.

**Implementation notes.**

- `Streak` keeps the live count in a transient `Map<UUID, Integer>`. Not NBT —
  per-outing state has no business being persisted, and keeping it out of the
  stone makes "resets on return" the default rather than something four call
  sites have to remember.
- Only the high-water mark persists, as `bestStreak` on the stone plus a
  journal line (*"Felled 12 in a single outing."*), written when a streak ends.
- Buffs are applied as **transient `AttributeModifier`s**, never
  `setBaseValue`. `WolfCapture` round-trips the whole entity through NBT, and a
  clobbered base value would persist into the stone and compound on every
  summon. Transient modifiers are not serialised, so the wolf always goes into
  the stone clean and buffs are rebuilt from the live streak.
  Verified: `AttributeModifier(Identifier, double, Operation)`,
  `AttributeInstance.addOrUpdateTransientModifier`, `Operation.ADD_VALUE`.
- Buffs re-apply **on every kill**, not just on summon, so the wolf visibly
  swells mid-fight. A chime fires on tier-up only.
- `RecallLock` blocks **only** the voluntary right-click recall. The §8
  ownership invariant — stone leaves the inventory, owner logs out — always
  wins, because an orphaned wolf has no stone to decrement on death.
- `WolfKill` owns the single `AFTER_DEATH` kill-attribution hook and dispatches
  to both `Streak` and `Fetch`, so resolving the owner (which walks every
  online player's inventory copying `custom_data`) happens once per kill.

**Still untested in play.** The tier numbers are a first guess. +2.0 at streak
15 is the number to watch — if a maxed wolf trivialises overworld mobs, cut the
damage column before touching the tiers.

---

## 16. Binding redesign — player-owned wolf (Option A, not yet implemented)

> **Status:** design decided 2026-08-09; fleshed to build-ready detail the
> same day. This is the biggest architectural change since v1 and touches
> every module. Not yet built. **No migration** — v3 starts fresh (see §16.9).

### What changes

v1 and v2 bind the wolf to the **stone** — the stone carries the wolf NBT in
its `custom_data`, and losing the stone loses the wolf. Option A inverts this:
the wolf is bound to the **player**, and the stone becomes a replaceable
remote control.

| Aspect | v1/v2 (stone-bound) | v3 (player-bound) |
|---|---|---|
| Wolf NBT lives in | Stone's `custom_data` | Server-side per-player file |
| Stone's role | The wolf's soul prison | A remote/summoning focus |
| Losing a stone | Lose the wolf | Make/cheat a new stone — wolf is fine |
| Multiple stones | Each stone = separate wolf | All stones control the same wolf |
| One wolf per player | No (one per stone) | **Yes** — enforced by registry key |
| Release | Not possible | `/spiritwolves release` or ritual |
| Bond permanence | Permanent | Breakable via deliberate release |

### 16.1 New module: `PlayerWolfRegistry`

Server-side storage keyed by player UUID. One record per player:

```json
{
  "wolfUuid": "<uuid>",
  "wolfTag": { /* full entity NBT, same as v1's stone-embedded tag */ },
  "wolfName": "Ghost",          /* cached for lore/messages, may be null */
  "collar": "RED",              /* cached for lore, may be null */
  "summoned": false,
  "boundAt": <epoch millis>,
  "lastSummonedAt": <epoch millis>,
  "saveCount": 0,
  "bestStreak": 0,
  "journal": [ /* same schema as §15.5: list of {k, t} */ ],
  "souls": 0,
  "familyKills": { /* "blaze": 12, "spider": 40, ... — §18 */ },
  "fangs": { /* fangId → { tier, attunedTier, fillKills, equipped } — §18 */ }
}
```

**Storage format: NBT, not JSON.** `wolfTag` is a `CompoundTag`; serialising
it through JSON would mean an SNBT round-trip for no benefit. Each record is
written as one `.dat` file: `world/data/spiritwolves/<playerUuid>.dat` via
`NbtIo.write` / `NbtIo.read` — **verify exact method names and whether the
compressed variants (`writeCompressed`/`readCompressed`) are preferred in
26.2**. The record maps 1:1 to a `CompoundTag` using the same
`getBooleanOr`/`getStringOr`/`getCompoundOrEmpty` accessors used everywhere
else in this mod.

**Lifecycle.**

- `ServerLifecycleEvents.SERVER_STARTED` → scan the directory, load every
  `.dat` into a transient `Map<UUID, WolfRecord>`. Malformed files: log a
  warning, rename to `.dat.corrupt`, skip. Never crash the server on one bad
  record.
- Mutations call `markDirty(playerUuid)`. A tick-counter flush in the
  existing `Tracker` poll (every 30 ticks) writes dirty records — no separate
  scheduler. Writes are atomic: write to `<uuid>.dat.tmp`, then move over the
  real file.
- `ServerLifecycleEvents.SERVER_STOPPING` → flush all dirty records
  synchronously.
- Records for offline players stay in memory; the map is small (one record
  per player who ever bound a wolf) and eviction is not worth the complexity.

**API surface** (static, like every other module in the suite):

```java
final class PlayerWolfRegistry {
    static void load(MinecraftServer server);      // SERVER_STARTED
    static void flushDirty();                      // called from Tracker poll
    static void flushAll();                        // SERVER_STOPPING
    static boolean has(UUID player);
    static WolfRecord get(UUID player);            // null if none
    static void put(UUID player, WolfRecord rec);  // insert + markDirty
    static void remove(UUID player);               // release/true death; deletes file
    static void markDirty(UUID player);
}
```

`WolfRecord` is a plain mutable class (not a record — it mutates constantly)
with public fields matching the schema above plus `toTag()`/`fromTag()`.

### 16.2 What moves where

| Data | v1/v2 location | v3 location |
|---|---|---|
| Wolf NBT | `SpiritStone.custom_data.wolf` | `PlayerWolfRegistry.wolfTag` |
| `summoned` flag | `SpiritStone.custom_data.summoned` | `PlayerWolfRegistry.summoned` |
| `uuid` | `SpiritStone.custom_data.uuid` | `PlayerWolfRegistry.wolfUuid` |
| Journal (`journal`, `saveCount`, `boundAt`) | `SpiritStone.custom_data` | `PlayerWolfRegistry` |
| `bestStreak` | `SpiritStone.custom_data` | `PlayerWolfRegistry` |
| Charges (`max_damage`/`damage`) | **Stays on the stone** | **Stays on the stone** — anvil reads them |
| `repairable` component | Stays on the stone | Stays on the stone |

Charges must stay on the item because the vanilla anvil repair in §4 reads
`ItemStack` components directly. But the wolf itself, its journal, and its
state move to the registry. The stone becomes a thin item: charges + a
`custom_data` tag marking it as a spirit stone, nothing more.

**The stone's new schema.** `custom_data: { spiritwolves: { bound: true } }`.
An unbound stone has `bound: false`. That is the entire per-item state besides
the vanilla `max_damage`/`damage`/`repairable` components. There is no wolf
data, no UUID, no journal on the item. Two bound stones held by the same
player are interchangeable; a bound stone held by a player with no registry
record is an *orphan* (see edge cases, §16.7).

**Stone lore** is still rendered by `SpiritStone.refreshLore`, but it now
takes the holder's `WolfRecord` (nullable) instead of reading the stack:

- Unbound: item name *"Spirit Stone"*, lore *"Right-click your tamed wolf to
  bind its soul."*
- Bound + record: name *"Spirit Stone — <wolfName>"* (or just *"Spirit
  Stone"* if unnamed), then charges line, then journal lines per §15.1's
  render rules (cap 6 total, oldest journal lines dropped first).
- Orphan (bound, no record): lore *"The stone is silent."*
- Lore refresh happens at the same call sites as v2 (bind, summon, recall,
  death-save, journal writes) plus a cheap pass in the `Tracker` poll that
  only rewrites when the record is dirty — do not rebuild lore for every
  stone every poll.

### 16.3 Binding flow (revised)

1. Player tames a wolf normally.
2. Right-click with an unbound stone → `Binding` checks
   `PlayerWolfRegistry.has(player.getUUID())`. If yes, reject: *"You already
   have a spirit wolf. Release it first."*
3. If no: capture wolf NBT, write to `PlayerWolfRegistry`, discard the wolf,
   mark the stone as bound (it just needs `custom_data: { spiritwolves: {} }`
   to identify it as a spirit stone — the wolf data is no longer on it).
4. Consume one stone from the stack, same as v1.

### 16.4 Summoning flow (revised)

1. Right-click a bound stone → `Summoning` looks up
   `PlayerWolfRegistry.get(player.getUUID())`.
2. If no record: *"The stone is silent. You have no spirit wolf."* (orphan
   stone — a leftover from a released or truly dead wolf; it stays usable for
   a future bind).
3. If `summoned == true`: recall (same UUID-lookup teleport-or-discard logic
   as v1, but state lives in the registry not the stone).
4. If `summoned == false` and charges > 0: restore from `wolfTag`, set
   `summoned = true` in the registry.
5. If charges == 0: *"Your wolf is dormant. Repair the stone at an anvil."*

### 16.5 Release

Two-step confirmation via chat click event — the suite's `dailyquests`
pattern, no typing the command twice:

1. `/spiritwolves release` → if no record: *"You have no spirit wolf."*
   Otherwise print a warning with the stakes spelled out:

   ```
   Release Ghost? Its soul — and everything it has become — will be lost
   forever. 207 souls. Emberfang II. This cannot be undone.
   [ Release ]        (red, bold, click → /spiritwolves release confirm)
   ```

2. `/spiritwolves release confirm` → only valid within **30 seconds** of step
   1 (track a `Map<UUID, Long>` of pending confirmations; expired → *"Release
   request expired. Run /spiritwolves release again."*). On confirm:
   - If the wolf is currently summoned, discard the entity.
   - `PlayerWolfRegistry.remove(player)` — record and file deleted. Souls,
     fangs, journal: gone. This is what makes release a real decision.
   - Every bound stone in the player's inventory reverts to unbound
     (`bound: false`, lore refreshed). Stones are not consumed — they were
     bought, they stay useful.
   - Message: *"<name>'s soul slips free. The stone is silent."* + the
     `Chime.recall` sound (or a dedicated sadder cue if one is added).

`release confirm` is registered as a hidden subcommand (no tab-complete
suggestion if avoidable; if Brigadier can't hide it, it simply no-ops without
a pending confirmation, which is safe).

A flavor ritual alternative (throw the stone into lava/fire) is possible
later; the command is the v3 path.

### 16.6 One-wolf-per-player enforcement

The registry key *is* the player UUID. Binding checks `has(player.getUUID())`
before allowing a new bind. This is the entire enforcement mechanism — simple,
no edge cases, no per-stone tracking.

### 16.7 Edge cases — decided, not discovered

| Case | Behaviour |
|---|---|
| Bind attempt with existing record | Reject: *"You already have a spirit wolf. Release it first."* Stone unconsumed. |
| Bind attempt on someone else's tamed wolf | Reject, unchanged from v1: *"That wolf isn't yours."* |
| Summon with orphan stone (no record) | *"The stone is silent. You have no spirit wolf."* Nothing consumed. |
| Summon while already summoned | Acts as recall, same as v1. |
| Summon with 0 charges | Reject: *"Your wolf is dormant. Repair the stone at an anvil."* |
| Wolf dies with charges > 0 | Death-save, unchanged: charge consumed, wolf saved to registry (`wolfTag` updated, `summoned = false`), journal updated. |
| **Wolf dies with 0 charges on every held stone** | The wolf **truly dies**. `PlayerWolfRegistry.remove(player)`. Stones revert to unbound. Message: *"<name> is gone."* The stakes of v1 survive into v3 — progression makes this loss *matter more*, which is the point. |
| Owner logs out with wolf summoned | Recall to registry (`summoned = false`, `wolfTag` updated), unchanged pattern from v1 `Tracker`. |
| Stone leaves inventory while summoned (drop/chest/death) | Wolf stays out — the bond is to the *player* now, not the stone. The §8 stone-in-inventory invariant is **deleted**. The wolf only recalls on: right-click recall, owner logout, owner death, or wolf death-save. This is a deliberate simplification the registry buys us. |
| Owner dies while wolf summoned | Recall to registry (silently, no charge cost). Prevents an ownerless wolf fighting on at the death site, and pairs with the grave-howl idea in §17 if that ships later. |
| Dimension change by owner while wolf summoned | Recall to registry silently. Simpler and cheaper than cross-dimension teleport, and re-summoning on the other side costs nothing. |
| Two bound stones in inventory | Interchangeable. Right-clicking either operates on the single registry record. Charges are per-stone; a summon checks the *held* stone's charges. |
| Player offline, record dirty | Flushed by the poll regardless — records are keyed by UUID, not by online status. |

### 16.8 Message strings — canonical

All player-facing strings for v3 core, so agents don't improvise tone.
Aqua for ceremony, gray for information, red for refusal — the v1 palette.

| Event | Message | Style |
|---|---|---|
| Bind success | *"<name>'s soul is bound to you. The stone hums."* | aqua |
| Bind, already have wolf | *"You already have a spirit wolf. Release it first."* | red |
| Bind, wolf not yours | *"That wolf isn't yours."* | red |
| Bind, wolf not tamed | *"That wolf hasn't been tamed."* | red |
| Summon | *"<name> answers."* | aqua |
| Recall | *"<name> returns to the stone."* | gray |
| Summon, dormant | *"Your wolf is dormant. Repair the stone at an anvil."* | red |
| Summon, orphan stone | *"The stone is silent. You have no spirit wolf."* | gray |
| Death-save (charges left) | *"The stone flares — <name> is saved. <n> charge(s) remain."* | aqua |
| Death-save (last charge) | *"The stone cracks — <name> is saved, but the stone is spent."* | gold |
| True death | *"<name> is gone."* | dark_red |
| Release prompt | see §16.5 | red/gray |
| Release done | *"<name>'s soul slips free. The stone is silent."* | gray |
| Release expired | *"Release request expired. Run /spiritwolves release again."* | gray |

### 16.9 No migration — fresh start

v3 does not read v1/v2 stone data. Any legacy stone with wolf NBT in its
`custom_data` is treated as **unbound** (the legacy fields are ignored and
stripped on first interaction). Existing test wolves are abandoned; the
server starts fresh. This deletes an entire class of migration bugs and is
explicitly accepted.

### 16.10 Impact on existing modules

| Module | Change |
|---|---|
| `SpiritStone` | Loses `wolfTag`, `wolfUuid`, `isSummoned`, `setSummoned`, `updateWolfTag`. Gains a simpler schema: just `{ spiritwolves: {} }` as a marker. Charge methods stay. `refreshLore` reads from the registry, not the stack. |
| `Binding` | Adds `PlayerWolfRegistry.has()` check. Writes to registry instead of stone. |
| `Summoning` | Reads from registry instead of stone. `summoned` flag lives in registry. |
| `Deaths` | Reads/writes registry instead of stone for wolf NBT update. Charge consumption stays on the stone (needs `Tracker.findStoneFor` to locate the player's stone). |
| `Tracker` | Polls registry for `summoned == true` entries, not stone `custom_data`. Still needs to find the player's stone for charge operations. Logout recall clears registry flag. |
| `Journal` | Reads/writes registry journal list, not stone `custom_data`. |
| `Streak` | `bestStreak` persists to registry, not stone. |
| `Senses` / `Fetch` | No change — they operate on the live wolf entity, not the storage. |
| `SpiritCommands` | `/spiritwolves release [confirm]` added. `/spiritwolves info` reads from registry. Admin: `/spiritwolves admin wipe <player>` (delete record), `/spiritwolves admin stone <player>` (give unbound stone — renamed from the v1 give). |
| `Deaths` | Also handles the true-death branch (0 charges): registry removal, stone reversion. |

### 16.11 v3 definition of done

- [x] `./gradlew build` clean; jar in `dist/`
- [x] Registry loads/saves `.dat` records; atomic writes; corrupt files
      quarantined, never crash
- [x] Bind writes registry, rejects second wolf, stone becomes thin marker
- [x] Summon/recall driven entirely by registry state; per-stone charges
      respected
- [x] Death-save updates registry; true death removes record and reverts
      stones
- [x] Release: two-step chat confirm, 30s expiry, full record deletion,
      stones revert unbound
- [x] §8 stone-in-inventory invariant removed; recall triggers are exactly:
      right-click, logout, owner death, dimension change, death-save
- [x] All §16.8 message strings as written
- [x] Journal/streak/senses/fetch keep working against registry storage
- [x] Legacy stones treated as unbound, legacy fields stripped on touch

### 16.12 Why Option A over B or C

- **Option B** (wolf stays in stone, add owner tracking + release) preserves
  more v1 code but doesn't fix the core problem: losing the stone still loses
  the wolf. Release is also destructive — you lose the wolf to release it.
- **Option C** (hybrid registry + stone pointer) is the most engineering
  surface for the least emotional gain — attunement, migration, and two
  sources of truth to keep in sync.
- **Option A** is the cleanest: one source of truth (the registry), the stone
  is replaceable, release is non-destructive to the wolf data (it just clears
  the binding), and one-wolf-per-player is free.

---

## 17. Expansion candidates — phased, wolves only

> **Status:** brainstormed 2026-08-09. Not yet spec'd in detail or
> implemented. Creature expansion (spirit cats, parrots, etc.) is explicitly
> excluded — focus stays on wolves. Items are grouped into phases by
> engineering size and dependency order. None of these depend on §16 being
> built first, but several are *simpler* after the registry exists.

### Phase 1 — emotional moments (smallest, pure flavour)

All server-side-safe, zero combat impact, leverage existing hooks.

- **Dream whimpers.** While the wolf is inside the stone, the stone holder
  occasionally hears a faint whine/pant from their own position. Triggered
  in the `Tracker` poll with a low random chance. Uses the wolf's own sound
  variant pant/whine from `WolfSoundVariant.WolfSoundSet`.
- **Reunion excitement.** Summon after a long absence (>1 MC day): wolf
  plays happy sounds, emits heart particles via `EntityEvent` (client-side
  `handleEntityEvent` already renders hearts for wolves). Track last-summon
  timestamp on the stone or registry.
- **Grave howl.** If the owner dies near the summoned wolf, the wolf sits at
  the death point and whines until the player returns within a few blocks.
  Hook: `ServerPlayerEvents.AFTER_RESPAWN` or `ServerLivingEntityEvents.
  AFTER_DEATH` for the *player*, checking proximity to the wolf.
- **Journal milestones.** Extend §15.1's journal with rare event-driven
  lines: *"Has stood in three dimensions."* (track dimension visits),
  *"Watched its owner defeat the Ender Dragon."* (hook dragon death event).
  Cheap to add — the journal schema already exists.

### Phase 2 — utility fangs (the wolf does things)

- **Guard post.** Sneak-click a block with the stone: wolf stays and guards
  that spot, attacking hostiles in a radius, until recalled. Needs a
  per-wolf "guard position" stored transiently (not in NBT — same reasoning
  as streak). `UseItemCallback` on a block-click, or `UseEntityCallback`
  on the wolf with a target block.
- **Bloodhound.** Give the wolf a mob drop (bone → skeletons, rotten flesh
  → zombies): it pathfinds toward the nearest matching mob cluster and
  growls. Uses existing `level.getEntitiesOfClass` + wolf navigation API.
  Needs `Mob.getNavigation().moveTo(...)` — **verify in jar**.
- **Warden canary.** In the deep dark biome, the wolf refuses to move and
  whines when a Warden is within ~30 blocks, before shriekers trigger.
  Check `level.getEntitiesOfClass(Warden.class, ...)` in the `Tracker` poll
  when the wolf is in a deep-dark biome. High drama, trivially cheap.
- **Pack mule lite.** Shift-click items onto the wolf to stash a small
  virtual inventory (9 slots) in the stone's `custom_data` or the registry.
  Retrivable anytime. **Dupe-proofing required** — the inventory must be
  cleared from the wolf on capture and restored on summon, same as wolf NBT.
  If §16 is built, this lives in the registry; if not, it lives on the stone.

### Phase 3 — economy, survival, and QoL

- **Acquisition via cobbleeconomy.** No crafting recipe — spirit stones are
  (or will be) sold through the `cobbleeconomy` shop. That is the survival
  acquisition path. Nothing to build in this mod beyond making sure the shop
  can vend a component-carrying `ItemStack` (it sells the unbound stone from
  `SpiritStone.createUnbound()` or an equivalent shop definition).
- **Netherite inlay.** Anvil + netherite ingot, one-time: stone gains
  `DataComponents.DAMAGE_RESISTANT` (or equivalent) so it survives lava/fire
  as a dropped item. **Verify the component name in jar** — likely
  `DataComponents.DAMAGE_RESISTANT` or a fire-immunity tag. One component
  flag, big peace of mind.
- **Charge overflow.** Repairing at full charges banks one bonus "echo"
  (max 1) consumed before real charges. Small sink for rich players.
  Requires intercepting the anvil repair — **may need a Mixin or a
  `ServerTickEvents` post-repair check**; verify whether the anvil result
  can be detected without one.
- **`/spiritwolves locate`.** Points toward your summoned wolf if you lost
  it: chat message with compass direction + distance. Trivial — `wolf.
  position().subtract(player.position())` → cardinal direction.
- **Death-save grace period.** After a save, brief cooldown (10–15s) before
  re-summon: *"The stone is still warm."* Prevents summon-die-summon spam
  against a boss. One timestamp check in `Summoning`.
- **Stone in ender chest / shulker.** Decide and enforce whether the
  ownership invariant treats these as "in inventory." Currently they
  aren't — the wolf would recall. Either extend `Tracker.forEachStone` to
  scan ender chest contents, or document the behaviour as intentional.

### Phase 4 — social and multiplayer

- **Wolf meets wolf.** Two summoned spirit wolves near each other: both
  play happy ambient sounds, heart particles. Check in `Tracker` poll:
  for each summoned wolf, check if another spirit wolf is within ~5 blocks.
  Cheap, community flavour.
- **Previous owners in journal.** If §16's release retains the previous
  wolf's name briefly, the next bind's journal starts with *"Once ran with
  `<name>`."* Gives traded/released wolves a lineage. Only meaningful if
  stones are tradeable, which they already are in v1/v2.
- **`/spiritwolves elders`.** Lists the server's oldest bound wolves by
  bind date (reads `boundAt` from all registry entries or all stones).
  Fits the suite's command style. Pure chat output, no mechanics.

### What stays cut

- **Creature expansion** (spirit cats, parrots, allays, bears). The mod is
  about wolves. Expanding to other tamable mobs multiplies testing surface
  for little gain at this stage.
- **Crafting recipes.** Stones are sold via `cobbleeconomy`, not crafted.
- **Stone tier ladder.** Progression lives on the wolf (§18), not the item.
  The stone stays a flat remote/charge-holder.
- **Screen-based talent UI.** §18's fangs are managed through chat click
  events, not a container screen — vanilla clients, no GUI code.

---

## 18. Soul-forged progression — permanent growth and equippable combat fangs

> **Status:** designed 2026-08-09, not yet implemented. Supersedes the
> "no permanent progression" rule in §15/§15.4 — the wolf is a combat pet, and
> permanent growth is the point. Depends on §16 (the registry is where all of
> this state lives; building this on stone `custom_data` would be rework).

**Theme: the wolf absorbs the souls of those it kills.** Every kill feeds it.
Killing enough of a mob family teaches the wolf that family's nature — blazes
give it fire, spiders give it venom. Diamonds are the catalyst that lets the
wolf digest what it has absorbed into the next tier of power.

### 18.1 Souls and levels

Every kill by the wolf grants **souls** (kill attribution already exists —
`WolfKill` from §15.7). Total souls determine **wolf level**. Levels exist
only as slot/tier gates — there is no stat-per-level; raw power comes from
fangs and the §15.7 killstreak.

**Soul values** — one static table in `Souls.java`, keyed by `EntityType`,
with a category fallback so unlisted mobs still resolve:

| Category / mob | Souls |
|---|---|
| Passive & ambient (cow, bat, villager…) | 0 |
| Common hostiles (zombie, skeleton, spider, creeper, drowned, husk, stray, slime, silverfish, phantom…) | 1 |
| Tough hostiles (enderman, blaze, magma cube, witch, piglin brute, vindicator, guardian, shulker, breeze) | 3 |
| Elites (wither skeleton, ravager, evoker, hoglin, ghast) | 5 |
| Elder guardian | 10 |
| Warden | 25 |
| Wither, Ender Dragon | 50 |
| Anything not listed and not `Monster` | 0 |
| Anything not listed but `instanceof Monster` | 1 |

Player kills grant **0** — no PvP soul farming, no incentive to sic wolves on
players. Baby variants count the same as adults (not worth the check).

**Level thresholds** — constants in `Souls.java`:

| Level | Souls (cumulative) | Grants |
|---|---|---|
| 1 | 0 | 1 fang slot |
| 2 | 50 | fang tier II attunable |
| 3 | 150 | 2nd fang slot |
| 4 | 400 | fang tier III attunable |
| 5 | 1000 | 3rd fang slot |

Level is always **derived from souls**, never stored — one less field to
desync. `Souls.levelFor(long souls)` is the single authority.

**Level-up UX.** When a kill crosses a threshold:

- Chat: *"<name> has grown. Level 3 — a second fang breaks through."* (gold)
- `Chime` tier-up sound (reuse the §15.7 tier-up cue at a lower pitch, or a
  dedicated one).
- Journal line: level 2 → *"Tasted its first fifty souls."*, level 3 →
  *"A hundred and fifty souls strong."*, level 4 → *"Four hundred souls
  strong."*, level 5 → *"A thousand souls. An elder spirit."*
- All state persists in the §16 `PlayerWolfRegistry` record. Nothing on the
  stone.

### 18.2 Fangs — unlocked by deed, tiered by diamonds

> **Renamed and split — done, no migration work outstanding.** This system was
> called **verbs** in early drafts, then **fangs**, and is now **two categories
> under one model**: **Fangs** (combat) and **Tricks** (utility). Implemented in
> `Abilities.java` (`Ability`, `Ability.Category`) with `AbilityCommands`,
> `AbilityProcs`, `AbilityGui`; commands `/spiritwolves fangs` and
> `/spiritwolves tricks` both open the one panel; save key `abilities`.
> Renamed along the way: **Witherbite → Witherfang**, **Blinkstrike → Voidfang**
> ("strike" implied an activated ability, which this section forbids),
> **Scavenger → Fetch**, **Light → Shine**. **Predator** was deleted as a
> duplicate of Ravenous. Saves from any earlier naming are migrated on read in
> `WolfRecord.fromTag`. The word "verb" should not appear anywhere in
> `spiritwolves`; `chatdonkey`, `hearsay`, and `wayfarers` use it for their own
> unrelated dialogue systems — do not rename those.

**The two categories.** A fang is what the wolf does in a fight; a trick is what
it does for you. They share one progression model — free tier I at an unlock
goal, diamonds to attune the next tier, more of the same deed to fill it — but
**they draw on separate slot pools**, so carrying a nose never costs you a bite.
Fang slots follow §18.1 (1/2/3 at levels 1/3/5); trick slots are 2, rising to 3
at level 4. Utility is deliberately the cheaper pool: making a player drop Shine
to try Dig punishes them for exploring the gentler half of the mod.

Each fang is themed to a mob family. Killing that family accumulates a
per-family kill count; hitting the threshold **unlocks tier I free**. Each
subsequent tier requires **diamonds to attune** (opens the tier as a goal)
and then **more kills of that family to fill it**. Money buys the unlock,
deeds buy the power.

| Fang | Soul source | Tier I (unlock: ~20 kills) | Tier II | Tier III |
|---|---|---|---|---|
| **Emberfang** | blazes, magma cubes | bite ignites 2s | 4s + small fire resist aura for owner nearby | 6s, wolf immune to fire |
| **Venomfang** | spiders, cave spiders | bite poisons 3s | 5s, Poison II | 8s, also Slowness |
| **Ravenous** | zombies, drowned, husks | wolf heals 1♥ per kill | heals on hit (0.5♥) | overheal to absorption, cap 2♥ |
| **Bonechill** | skeletons, strays | bite slows 2s | 4s, Slowness II | also Weakness on target |
| **Witherfang** | wither skeletons | bite withers 2s | 4s | 6s, Wither II |
| **Voidfang** | endermen | wolf teleports to its target when >8 blocks away | cooldown halved | owner's marked prey (§15.2) is a valid blink target |

- Attunement costs: tier II = 4 diamonds, tier III = 16. Constants, tune later.
- Fill requirements after attunement: tier II = 50 family kills, III = 150.
- Effects reuse verified APIs: `addEffect(new MobEffectInstance(...))` is
  confirmed; ignition needs **verify in jar** (`setRemainingFireTicks` /
  `igniteForSeconds` — check the 26.2 name); `LivingEntity.heal(float)` and
  teleport via `snapTo` are already used or verified elsewhere in the suite.
- All fangs are **passive procs on the wolf's own attacks or kills** — no
  activation keybind exists on a vanilla client, so nothing requires one.
  Voidfang's trigger is positional; Ravenous triggers on kill/hit.
- **Predator was removed — do not re-add it.** It healed the wolf for killing an
  edible animal, but Ravenous already heals on *every* kill including animals
  and fires on the same event, so Predator was a strict subset that double-dipped
  when both were equipped. Any `predator` entry in an old save is discarded on
  load by `WolfRecord.fromTag` (an orphan record would otherwise eat a slot).

#### The tricks

| Trick | Trained by | Tier I | Tier II | Tier III |
|---|---|---|---|---|
| **Fetch** | items the wolf brings you (100) | also gathers loose unowned items within 5 blocks | 8 blocks | 12 blocks |
| **Shine** | lights you place near the wolf (50) | wolf glows, visible through walls | Night Vision for the owner within 8 blocks | 16 blocks |
| **Dig** | ore you mine near the wolf (50) | show it an ore/ingot: exposed matching ore within 12 blocks is revealed | 18 blocks | 24 blocks |
| **Speak** | mobs that turn on you (20) | show it a mob drop: matching mobs within 24 blocks glow | 32 blocks | 48 blocks |

**Tricks are trained, not fed.** Fangs eat a mob family; tricks have no family,
so each is trained by the deed it resembles, and **only while the wolf is out and
within 24 blocks** — the wolf has to be present for the lesson. See
`Training.java`. Fetch's tally lives in `Fetch.java`, where retrievals are
already counted; the kill-site sweep stays free and always-on, and the trick
extends it to loose items in the world.

**Dig and Speak are performed, not proc'd.** Both are used by **sneak +
right-click on your own summoned wolf while holding the item**, which is never
consumed. The rule underneath them: *Dig finds things by their material, Speak
finds things by their scent.* Two constraints that are load-bearing:

- **Sneak, not a plain right-click.** Plain right-click on a tamed wolf is
  vanilla feeding, and rotten flesh — a Speak reagent — is wolf food.
  Intercepting the plain click would silently break healing your wolf. Sneak +
  empty hand is already mark-prey (§15.2), so sneak + item slots in beside it.
- **Dig only reveals *exposed* ore** — a block with at least one face open to
  air or another see-through block (`Tricks.isExposed`). A wolf that sniffs out
  a seam you could walk to is a bloodhound; a wolf that sees through forty
  blocks of stone is an X-ray cheat. Do not relax this check.

Revealed **mobs** get vanilla Glowing, a true outline. Revealed **ore** gets
`ParticleTypes.GLOW` pulsing on its exposed face instead: outlining a *block*
server-side means spawning and syncing a display entity per ore, which is a lot
of moving parts for a cosmetic — and marking the reachable face says something
truer about what Dig selected for. Reveals last 10s, are capped at 32 blocks
nearest-first, and a use costs a 2s cooldown (`Tricks.USE_COOLDOWN_TICKS`)
because a tier III Dig scans a 49-block cube.

**Ability state machine.** Each fang, per wolf, is in exactly one state. The
per-fang record in the registry is `{ tier, attunedTier, fillKills, equipped }`:

| State | Condition | Meaning |
|---|---|---|
| `HIDDEN` | progress = 0 | Not shown in the UI at all — discovery is part of the game. |
| `SCENTED` | 0 < progress < unlock goal | Shown as a mystery row with progress: *"???  — something stirs (7/20)"*. Name revealed only on unlock. |
| `UNLOCKED` | progress ≥ unlock goal | `tier = 1`. Usable. |
| `ATTUNED` | diamonds paid for tier n+1 | `attunedTier = n+1`, `fillKills` counts progress since attunement. |
| `FILLED` | `fillKills` ≥ requirement | `tier = attunedTier`, `fillKills` reset, state back to `UNLOCKED` at the new tier. |

Rules the state machine enforces:

- Progress always counts — family kills for a fang, the trained deed for a
  trick — whether or not it is equipped, whether or Nothing is ever wasted; only *fill* progress
  requires prior attunement.
- Attunement requires: fang `UNLOCKED`, wolf level gate met (§18.1), tier
  sequential (can't attune III before II is filled), diamonds in inventory.
- Attunement is not refundable. Release (§16.5) deletes everything anyway.
- Tier fills announce themselves: chat *"Emberfang burns hotter. Tier II."*
  (gold) + chime + journal line (*"Mastered the fire of fifty blazes."* — one
  line per fang per tier, keyed so re-renders don't duplicate).
- Unlock announces itself the same way: *"<name> has absorbed enough burning
  souls. Emberfang unlocked."* + journal *"Learned Emberfang from the
  Nether's flames."*

### 18.3 Slots, equipping, and the chat UI

A wolf knows every ability it has unlocked, but only **equipped** ones are
active. **Fangs and tricks have separate pools**: fang slots come from level
(§18.1) at 1 → 2 → 3, trick slots are 2 rising to 3 at level 4. Swapping is free
but only allowed while the wolf is **in the stone** — you set the loadout, then
summon. Prevents mid-fight juggling and keeps proc hooks static per outing.

**Command tree** (all under the existing `/spiritwolves` root; player-level
permission except `admin`):

```
/spiritwolves
  info                      — wolf summary (name, level, souls, streak best,
                              charges on held stone, one line per category)
  fangs | tricks            — the ability panel (below); both open the same
                              screen, so whichever word you reach for works
  <either> equip <id>       — hidden; driven by click events
  <either> unequip <id>     — hidden; driven by click events
  <either> attune <id>      — hidden; driven by click events
  release                   — §16.5
  release confirm           — hidden; §16.5
  admin stone <player>      — give unbound stone (LEVEL_GAMEMASTERS)
  admin wipe <player>       — delete registry record (LEVEL_GAMEMASTERS)
  admin souls <player> <n>  — grant souls, for testing (LEVEL_GAMEMASTERS)
```

**The ability panel** — `/spiritwolves fangs` or `/spiritwolves tricks` opens a
server-side chest GUI (`AbilityGui`, sgui `GENERIC_9x6`), not the chat layout
this spec originally described. Two rows, one per category, so the pool tradeoff
is visible where it is made:

```
row 0        [4] Ghost — Level 3 · 207 souls · Fangs 2/2  Tricks 2/2
row 1  [9] FANGS   [11..16] ◆ Emberfang II  ◇ Venomfang I  ◇ Bonechill I  ? ???
row 3  [27] TRICKS [29..32] ◆ Fetch I  ◆ Shine I  ◇ Dig I  ? ???
row 5                                                            [53] Close
```

Rendering rules:

- **Left-click** equips or sets aside; **right-click** attunes the next tier.
  Every click re-validates server-side in `Abilities` and reopens the panel, so
  a stale screen can never grant anything.
- Equipped entries are aqua, bold, enchant-glowing, prefixed ◆; unequipped are
  white with ◇.
- Lore per entry: current tier effect, category + progress count, fill progress
  when `ATTUNED`, and either the attune offer (aqua, with the diamond cost) or
  the blocker (dark gray — *"Requires Level 4"*, *"Requires 16 diamonds — you
  have 9"*, *"Fill Tier II first"*).
- `HIDDEN` entries render as a barrier reading `???` / *"Something stirs..."*
  with the progress count in near-black. `SCENTED` entries show the real icon
  and name grayed with progress — the trail is visible once it has started.
- The category label items (bone for Fangs, lead for Tricks) carry that pool's
  *n/m carried* count, red when full.
- While the wolf is summoned, equip attempts are rejected with *"Recall your
  wolf to change its fangs/tricks."*

**Attunement flow.** Right-click an entry → re-validate everything server-side
(never trust the rendered state — the panel may be minutes old): ability
unlocked, level gate, sequential tier, diamond count via the same whole-inventory
iteration `Tracker` uses. Remove diamonds with the standard shrink-in-place loop,
`markDirty`, confirm, chime, reopen. The confirmation splits by category, since
"feed it ores mined nearby" is nonsense: a fang says *"The diamonds crumble.
Emberfang reaches for Tier II — feed it blazes."*, a trick says *"... — keep
working at it: ores mined nearby."* (`AbilityCommands.attuneMessage`). Every
mutating action ends by reopening the panel — the panel *is* the UI, keep it
current.

### 18.4 How the pieces interact

- **Killstreak (§15.7) stacks on top.** The streak is per-outing spice; fangs
  are the permanent build. A maxed streak on a fang-equipped wolf is the
  ceiling case to balance-test.
- **Kill attribution is shared.** `WolfKill` already dispatches to `Streak`
  and `Fetch`; `Souls` becomes the third consumer. One hook, three listeners.
- **Death-saves don't touch progression.** Souls and fangs never reset —
  losing charges is the stone's problem, not the wolf's.
- **Release (§16) destroys progression.** Releasing the wolf deletes the
  registry record, souls and fangs included. This is what makes release a real
  decision and old wolves genuinely precious.
- **Scale (§15.3/§15.7).** Killstreak keeps driving the in-outing swell. If a
  permanent baseline is wanted later, level could add a small resting scale
  (+0.02/level, inside the ±0.15 budget) — optional, decide at build time.
- **Proc implementation.** Fang procs hook `ServerLivingEntityEvents`
  damage/death events filtering on `damageSource.getEntity()` being the
  tracked wolf — same attribution pattern as `WolfKill`. **Verify** that the
  Fabric `ALLOW_DAMAGE` or `AFTER_DAMAGE` event exists in the pinned Fabric
  API version for on-hit (not just on-kill) procs; if not, on-hit fangs
  (Bonechill, Ravenous II) may need the damage event from a newer Fabric API
  or a redesign to on-kill triggers.

### 18.5 Edge cases

| Case | Behaviour |
|---|---|
| Kill by wild (unbound) wolf | Ignored — same `isTame()` pre-filter `Fetch` uses. |
| Kill while fang fill not attuned | Family kill count still increments (it always does); fill progress doesn't. |
| Souls overflow | `long`. A player would need 9 quintillion kills. Not a case. |
| Attune click with panel stale (already attuned / diamonds spent) | Server-side re-validation catches it; message explains; panel re-printed. |
| Equip click while wolf summoned | Reject: *"Recall your wolf to change its fangs."* |
| Equip beyond slot count | Reject: *"No free fang slots. Unequip one first."* |
| Fang equipped, then level requirements change in a later balance patch | Equipped fangs are never force-unequipped; gates apply at attune/equip time only. |
| True death / release | Registry record deleted (§16) — souls, fangs, everything. No partial refunds. |
| Two family-listed mobs killed by one sweep (e.g. TNT assist) | Only kills attributed to the wolf via `damageSource.getEntity()` count — same rule as streak/fetch, no new logic. |
| Mob family lists | Constants in `Abilities.java` as `Set<EntityType<?>>`. Strays count for Bonechill, husks/drowned for Ravenous — the tables in §18.2 are authoritative. |

### 18.6 Message strings — canonical

| Event | Message | Style |
|---|---|---|
| Fang unlocked | *"<name> has absorbed enough <flavor> souls. <Fang> unlocked."* | gold |
| Tier attuned | *"The diamonds crumble. <Fang> reaches for Tier <n> — feed it <family>."* | aqua |
| Tier filled | *"<Fang> <flavor-fang>. Tier <n>."* (e.g. *"Emberfang burns hotter. Tier II."*) | gold |
| Level up | *"<name> has grown. Level <n> — <what it grants>."* | gold |
| Equip | *"<Fang> equipped."* | green |
| Unequip | *"<Fang> set aside."* | gray |
| Equip, no slots | *"No free fang slots. Unequip one first."* | red |
| Equip/unequip while summoned | *"Recall your wolf to change its fangs."* | red |
| Attune, not enough diamonds | *"Requires <n> diamonds — you have <m>."* | red |
| Attune, level gate | *"Requires Level <n>."* | red |
| Attune, previous tier unfilled | *"Fill Tier <n> first."* | red |

Per-fang flavor words (unlock line + fill line) live next to the fang
definitions in `Abilities.java` so adding an ability is one table row, not a hunt
through message code.

### 18.7 Module layout (new code)

| File | Owns |
|---|---|
| `Souls.java` | Soul values table, level thresholds, `levelFor`, level-up detection + UX. Registered as a `WolfKill` listener. |
| `Abilities.java` | Ability definitions for both categories (id, category, family set or training deed, tier effects, costs, flavor strings), state machine transitions, unlock/fill detection, per-pool slot validation. Also a `WolfKill` listener. |
| `Tricks.java` | Dig and Speak: the sneak+item interaction, the exposed-ore scan, the mob scan, reveal upkeep, and the wolf's sniff/bark. |
| `Training.java` | How tricks are trained: ore mined nearby (Dig), lights placed nearby (Shine), mobs that turn on you (Speak). Fetch is counted in `Fetch.java`. |
| `AbilityProcs.java` | The combat hooks: on-hit/on-kill effect application for equipped fangs. Reads equipped state from the registry once per summon, caches per wolf UUID, invalidates on recall. |
| `AbilityCommands.java` | The `/spiritwolves fangs` and `/spiritwolves tricks` literals and their hidden subcommands. Kept out of `SpiritCommands`. |
| `AbilityGui.java` | The two-row chest panel itself (sgui). |

### 18.8 Build order

1. §16 registry first — everything here persists in it.
2. `Souls.java` (kill values, level thresholds, level-up UX) + registry
   fields.
3. `Abilities.java` (definitions, state machine, unlock/fill detection).
4. `AbilityCommands.java` + `AbilityGui.java` — the panel, equip/unequip/attune.
5. `AbilityProcs.java`, one fang at a time — Emberfang first (simplest:
   on-kill/on-hit ignite), Voidfang last (positional logic).
6. Balance pass with killstreak stacking.

### 18.9 v4 definition of done

- [x] `./gradlew build` clean; jar in `dist/`
- [x] Souls accrue per the §18.1 table; level derived, never stored
- [x] Level-ups announce + journal + chime at exact thresholds
- [x] Fang states progress HIDDEN → SCENTED → UNLOCKED → ATTUNED → FILLED
      with family kills counting at all times
- [x] Attune validates level gate, sequential tier, diamond count
      server-side; removes diamonds correctly from 36-slot inventory
- [x] Panel renders all states per §18.3 including hover text, stale-click
      safety, and re-print after every mutation
- [x] Equip/unequip only while wolf is in the stone; slot limits enforced
- [x] All six fangs proc correctly at all three tiers; effects match §18.2
      (Voidfang III uses a nearest-monster fallback rather than reading
      Senses' marked-prey target, since that reference isn't persisted --
      documented simplification, not a gap in the other five fangs)
- [x] Procs use transient state only — nothing fang-related leaks into
      `wolfTag` via capture (fire ticks, effects on the wolf itself are
      acceptable NBT noise; attribute modifiers must stay transient like
      §15.7's)
- [ ] Killstreak + fangs stack without conflict (both apply, ceiling case
      play-tested) -- implemented (independent systems, no shared state) but
      not yet play-tested in a running server
- [ ] All §18.6 message strings as written
