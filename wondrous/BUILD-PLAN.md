# Wondrous Expansion — Build Plan

**Audience: the coding agent implementing `SPEC-EXPANSION.md`.**

`SPEC-EXPANSION.md` is *what* to build and *why*. This document is the *order*, the
*exact files*, and the *stop conditions*. Where the two disagree, this one wins —
it was written after the Phase 0 cut and it accounts for the real state of the tree.

---

## 0. How to work through this document

**One task at a time. Compile after every task. Never batch tasks.**

```bash
cd "a:/MrPinoys Mods/wondrous"
./gradlew :fabric:compileJava --console=plain -q
```

That command is the gate. It currently passes on a clean tree — if it fails, the
failure is yours and you fix it before starting the next task. Do **not** run
`./gradlew build` between tasks; it's slower and adds nothing here.

Each task below has the same five parts:

| Part | Meaning |
|---|---|
| **Files** | Exactly which files you create or edit. Touch nothing else. |
| **Do** | What to implement. |
| **Don't** | Failure modes that have already been thought about. These are not suggestions. |
| **Wire** | The registration line in `WondrousMod.onInitialize()`, if any. |
| **Done when** | The check that closes the task. |

Tick tasks off in §2 as you finish them, in this file, as part of the task.

---

## 1. Ground rules — read once, apply always

**1. Never invent a Minecraft API.** This is the single way this build goes wrong.
If a method you expect isn't there, you do **not** guess a similar name, you do not
try three spellings, and you do not work around it with reflection. You **stop**,
write what you found in `NOTES.md` under "Signatures", and move to the next task.
An unfinished task is recoverable; a plausible-looking wrong call is not.

**2. Server-side only. No Mixins. No registry entries.** Every item is a vanilla
item carrying `{wondrous: "<id>"}` in `minecraft:custom_data`. If a design seems to
need a new block, item, entity type or menu type, you have misread it — re-read the
spec section. The only new entities permitted anywhere are vanilla `block_display`
entities in §4.3, and there's a particle fallback if those fight back.

**3. Follow the existing shape.** `AreaBreak.java` and `FlyingBoots.java` are the
reference implementations. Each behaviour class:
- lives in `fabric/src/main/java/wondrous/`
- is `public final` with a private constructor
- exposes `public static void register(...)`
- reads its own id with `WondrousTag.is(stack, ID)` — a `public static final String
  ID` constant on the class
- returns `InteractionResult.SUCCESS_SERVER` when it acted, `PASS` when it didn't

**4. `Definitions.Def` does not gain fields.** Not one. Behaviour lives in the
behaviour class, dispatched on the id. The `area` field was the last exception and
it will not get a sibling.

**5. Guard every event handler with the same two lines.** Every callback in this
codebase starts by rejecting the client side and non-server players:

```java
if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
    return InteractionResult.PASS;
}
```

**6. Comment the *why*, never the *what*.** Match the density in `AreaBreak.java`.
A comment explaining that a loop iterates will be rejected; a comment explaining
why the hardness tolerance is `0.5F` is the standard.

**7. Don't edit anything under `build/` or `.gradle/`.** Those are generated.

**8. If a task's "Done when" can't be met, stop and say so.** Do not mark a task
complete because it compiles. Compiling is table stakes, not the bar.

---

## 2. Task list

Order is load-bearing. T0 blocks everything; T1 unblocks feedback for every item
after it; the shared infrastructure tasks block their dependents.

| # | Task | Blocks | Status |
|---|---|---|---|
| T0 | Signature sweep (javap) | everything | ☑ |
| T0.5 | Tier 0 attribute batch (Long Arm Gloves, Sticky Grip Boots, Frog Boots) | — | ☑ |
| T1 | `Chime` — the five cues | every task after | ☑ |
| T2 | Peek Box | — | ☑ |
| T3 | Bye Forever Bin | — | ☑ |
| T4 | `RecipeGuard` + retrofit to shipped items | — | ☑ |
| T5 | `Nearby` — radius container scan | T6, T7, T16 | ☑ |
| T6 | Chuck It Wand | — | ☑ |
| T7 | Tidy Up Stick | — | ☑ |
| T8 | Big Lazy Hoe | — | ☑ |
| T9 | `Aura` + `FlyingBoots` migration | T10, T12, T12.5 | ☑ |
| T10 | Growy Can + Lazy Sprinkler | — | ☑ |
| T11 | Smashy Mortar | — | ☑ |
| T12 | Never Empty Charm | — | ☑ |
| T12.5 | Aura effect batch (Owl Eye, Fishy Necklace, Toasty Scarf, Floaty Feet, Zoomies Boots) | — | ☑ |
| T13 | `Visuals` | T16 | ☑ |
| T14 | `WondrousState` | T15, T16 | ☑ |
| T15 | Left It Out Crafter | — | ☑ |
| T16 | Put It There Wand | — | ☑ |
| T17 | Piggyback Glove | — | ☑ |
| T18 | Shop JSON + `/wondrous links` | — | ☑ |

**Natural stopping points:** after T0.5 (three items shipped before Phase 0 even
starts), after T4 (two more items shipped, shipped-item bug fixed), after T8
(Phase 1 complete), after T12.5 (Phase 2 complete). Each is a releasable state.
T13–T17 are the expensive half and nothing before them depends on them.

**T0.5 has no dependency on T0 beyond the one 🔍 in its own task** — it can be
done first if you want an immediate win, but do the T0 signature sweep in full
regardless; T2 onward needs it.

---

## 3. T0 — the signature sweep

**Do this first, in one sitting, before writing any code.** Every 🔍 in
`SPEC-EXPANSION.md` is an API stated from memory and not checked against 26.2. The
merged jar is already in the tree:

```
.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar
```

Set it once:

```bash
cd "a:/MrPinoys Mods/wondrous"
JAR=".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar"
```

Run each of these and **paste the real output into `NOTES.md`** under a new
`## 26.2 signatures — verified <date>` heading. Not a summary. The signatures.

```bash
# 1. Saved data — blocks all of Phase 3. Most likely to have moved.
javap -cp "$JAR" net.minecraft.world.level.saveddata.SavedData
javap -cp "$JAR" net.minecraft.world.level.storage.DimensionDataStorage
javap -cp "$JAR" net.minecraft.server.level.ServerLevel | grep -i datastorage

# 2. The container component — blocks T2 (Peek Box).
javap -cp "$JAR" net.minecraft.world.item.component.ItemContainerContents

# 3. Bone meal — blocks T10.
javap -cp "$JAR" net.minecraft.world.level.block.BonemealableBlock

# 4. Crops and drops — blocks T8.
javap -cp "$JAR" net.minecraft.world.level.block.CropBlock
javap -cp "$JAR" net.minecraft.world.level.block.Block | grep -i dropresources
javap -cp "$JAR" net.minecraft.world.level.block.FarmBlock

# 5. Hopper-style extraction — blocks T16's "every machine works free" property.
javap -cp "$JAR" net.minecraft.world.WorldlyContainer
javap -cp "$JAR" net.minecraft.world.Container

# 6. Display entities — T13. Particle fallback if this is awkward.
javap -cp "$JAR" net.minecraft.world.entity.Display
javap -cp "$JAR" net.minecraft.server.level.ServerLevel | grep -i sendparticles

# 7. Odds and ends.
javap -cp "$JAR" net.minecraft.server.level.ServerPlayer | grep -iE "cooldown|systemmessage|displayclient"
javap -cp "$JAR" net.minecraft.world.item.ItemCooldowns
javap -cp "$JAR" net.minecraft.world.inventory.CraftingMenu
javap -cp "$JAR" net.minecraft.world.inventory.ChestMenu
javap -cp "$JAR" net.minecraft.world.SimpleContainer

# 8. Attributes for T0.5 and T12.5 — several are recent additions and may have
# moved or renamed since the ITEM-IDEAS.md recon.
javap -cp "$JAR" net.minecraft.world.entity.ai.attributes.Attributes | grep -iE "range|jump|speed|step_height|safe_fall"
javap -cp "$JAR" net.minecraft.world.effect.MobEffects | grep -iE "night_vision|water_breathing|dolphins_grace|fire_resistance|slow_falling|speed"
```

**Then fill in this table in `NOTES.md`** — it is the contract the rest of the
build reads from:

| Spec claim | Real 26.2 signature | Task affected |
|---|---|---|
| `SavedData` / `getDataStorage().computeIfAbsent(...)` | | T14 |
| `ItemContainerContents` read/write, slot cap | | T2 |
| `BonemealableBlock.performBonemeal(...)` | | T10 |
| `CropBlock.isMaxAge` / `getAge` / `getStateForAge` | | T8 |
| `Block.dropResources(...)` overloads | | T8 |
| `WorldlyContainer.getSlotsForFace` / `canTakeItemThroughFace` | | T16 |
| `Display.BlockDisplay` + glowing + team colour | | T13 |
| Action bar overload | | T1 |
| `ServerPlayer.getCooldowns().addCooldown(...)` — stack or item? | | T11 |
| `Attributes.BLOCK_INTERACTION_RANGE` id | | T0.5 |
| `Attributes.JUMP_STRENGTH` id (may be `GENERIC_JUMP_STRENGTH`) | | T0.5 |
| `MobEffects.SLOW_FALLING`, `.DOLPHINS_GRACE` ids | | T12.5 |

**Two of these change the plan if they come back wrong:**

- If `ItemContainerContents` has no usable write path, **T2 is cut**, not
  worked around. Say so and move to T3.
- If `WorldlyContainer` changed shape, T16 loses its "every machine free"
  property and needs a per-block table. That's a redesign — stop and flag it,
  don't start writing the table.

**Done when:** `NOTES.md` has the raw output and the filled table, and you have
named which tasks (if any) are now blocked.

---

## 3½. T0.5 — the Tier 0 attribute batch

**Files:** `fabric/src/main/java/wondrous/Definitions.java` (edit)

**Do this before or right after T0 — it's the cheapest task in the whole plan and
it ships three items with no new file.** `Definitions.java` already has one
attribute-mutating `decorate` helper, `areaTool()`, used by the two Big Hole
tools. Add a second, small and generic:

```java
/** Sets one attribute modifier. The whole trick behind the Phase -1 batch. */
private static UnaryOperator<ItemStack> attributeItem(
        String modifierName, Holder<Attribute> attribute,
        double amount, AttributeModifier.Operation op, EquipmentSlotGroup slot) {
    return stack -> {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        modifiers = modifiers.withModifierAdded(
                attribute,
                new AttributeModifier(
                        Identifier.fromNamespaceAndPath("wondrous", modifierName),
                        amount, op),
                slot);
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
        return stack;
    };
}
```

(Signature is illustrative — match it to whatever `Attributes` actually looks
like per T0's sweep; `areaTool()` in the same file is the ground truth for the
exact types `ItemAttributeModifiers.withModifierAdded` wants on 26.2.)

Then three `Def` entries in `Definitions.ALL`, each a one-line call to this
helper:

| id | Name | Attribute | Amount | Slot |
|---|---|---|---|---|
| `long_arm_gloves` | Long Arm Gloves | `block_interaction_range` | `+3.0`, `ADD_VALUE` | mainhand |
| `sticky_grip_boots` | Sticky Grip Boots | `movement_speed` | `-0.3`, `ADD_MULTIPLIED_TOTAL`, plus knockback resistance `+0.6` | feet |
| `frog_boots` | Frog Boots | `jump_strength` | tune in-game; start around `+0.4`, `ADD_VALUE` | feet |

Voice lines: Long Arm Gloves — pick something that reads as "reach further,"
matching the noun-phrase-plus-voice-line convention in `NAMES.md`. Sticky Grip
Boots and Frog Boots already have names; write flavour lines in the same voice as
the rest of `Definitions.java` (lowercase, no punctuation at the end, reads like
someone talking).

**Don't.**
- Don't give `sticky_grip_boots` a second `Def` field or a new file — it's two
  modifiers (speed, knockback resistance) in one `decorate` lambda, same pattern
  as `areaTool()` chaining nothing extra.
- Don't skip the jump-strength range check in `SPEC-EXPANSION.md` §4½.3 — too high
  a value turns "jumps higher" into "takes fall damage on landing," which reads as
  a bug, not a feature.
- No `register()` call, no behaviour class, no entry in `WondrousMod`. If you find
  yourself writing an event handler for one of these three, stop — the whole point
  of Tier 0 is that vanilla already does the work.

**Wire.** Nothing beyond the three `Def` entries. `ItemRegistry` and
`WondrousCommands` already pick up everything in `Definitions.ALL` automatically.

**Done when:** all three items grant their effect the instant they're worn/held,
with zero code outside `Definitions.java`, and compile is clean.

---

## 4. T1 — `Chime`, the five cues

**Files:** `fabric/src/main/java/wondrous/Chime.java` (edit)

**Do.** `Chime` currently has one method, `itemGiven`. Generalise it to a private
helper plus the five named cues from `SPEC-EXPANSION.md` §10, keeping `itemGiven`
working exactly as it does now (it's called from `WondrousCommands`).

```java
private static void note(ServerPlayer player, SoundEvent sound, float pitch) {
    player.connection.send(new ClientboundSoundPacket(
            sound, SoundSource.RECORDS,
            player.getX(), player.getY(), player.getZ(),
            0.3f, pitch, player.getRandom().nextLong()));
}

public static void select(ServerPlayer player)  { note(player, SoundEvents.NOTE_BLOCK_BELL,  1.2f); }
public static void confirm(ServerPlayer player) { note(player, SoundEvents.NOTE_BLOCK_CHIME, 1.4f); }
public static void reject(ServerPlayer player)  { note(player, SoundEvents.NOTE_BLOCK_BASS,  0.8f); }
public static void unlink(ServerPlayer player)  { note(player, SoundEvents.NOTE_BLOCK_BASS,  1.0f); }
public static void crush(ServerPlayer player)   { note(player, SoundEvents.BLOCK_STONE_BREAK, 1.0f); }
```

Add an action-bar helper here too, so every item sends feedback the same way and
there's one place to fix if the overload is wrong:

```java
/** Action bar, not chat. One line, replaced by the next one. */
public static void say(ServerPlayer player, String text) { ... }
```

Use whichever of `sendSystemMessage(Component, boolean)` or
`displayClientMessage(Component, boolean)` T0 confirmed. **`true` is the
action-bar flag** — getting it wrong spams chat and every item looks broken.

**Don't.** One note per *action*, never one per item moved. A Chuck It Wand that
moves 47 items plays one chime. `SoundSource.RECORDS` is deliberate — it dodges the
player's ambient/block volume sliders.

**Done when:** compiles, and `SoundEvents` constant names are confirmed real (they
are the most commonly mistyped thing in this file — check them against the jar,
not against memory).

---

## 5. T2 — Peek Box (`peek_box`, 12💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/PeekBox.java` (new)

**Do.** A tagged `shulker_box` that opens its own contents as a `ChestMenu`, from
the hotbar, without being placed.

1. Add the `Def` to `Definitions.ALL`. Base `Items.SHULKER_BOX`, name
   `"Peek Box"`, voice line `"just checking what i packed"`, plain lines
   `"Opens right where you are."` / `"Never needs putting down."`
2. `PeekBox.register(ItemRegistry)` on `UseBlockCallback` **and** `UseItemCallback`,
   the same double registration `Stations.java` uses.
3. On use: read `minecraft:container` off the stack into a `SimpleContainer`, open
   a `ChestMenu` over it, write back on close.

**The write-back is the whole task.** Getting it wrong dupes items. The safe shape:
resolve the held stack once, keep a reference, and on `removed(Player)` write the
container's contents back to *that* stack — after confirming it's still in the
player's inventory in the slot you found it in.

**Don't — all three of these are live bugs, not hypotheticals:**

1. **The box inside itself.** The stack sits in the player's inventory while its
   own menu is open, so it's reachable in the lower grid. Block the move. Compare
   by stack *identity* (`==`), not by id — a player may legitimately own two Peek
   Boxes and putting one inside the other is fine.
2. **The holding slot changing while open.** Simplest correct answer: close the
   menu if the stack leaves the slot it was opened from. Do that rather than
   attempting a clever write-back to a moved stack.
3. **A shulker inside a shulker.** Vanilla blocks this for placed boxes; a
   component-backed `SimpleContainer` does not inherit the check. Add it — reject
   any stack whose item is a shulker box.

**Wire.** `PeekBox.register(registry);` in `WondrousMod.onInitialize()`.

**Done when:** contents survive close → relog → reopen; the box can't go inside
itself; a shulker can't go in it; dropping it with the menu open loses and
duplicates nothing.

---

## 6. T3 — Bye Forever Bin (`void_bin`, 8💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/VoidBin.java` (new)

**Do.** A tagged `composter` that opens an empty chest GUI. Contents are destroyed
on close, after a confirmation.

1. `Def` on `Items.COMPOSTER`, name `"Bye Forever Bin"`, voice
   `"i dont want it. its gone. dont ask"`.
2. Open a `ChestMenu` over a fresh in-memory `SimpleContainer`. Nothing persists.
3. On close, if the container is non-empty, send a chat message — chat, not action
   bar, this one needs to stick around — naming the item count, with a
   `run_command` click event to confirm. Cancel, or disconnect before confirming,
   returns everything to the player.

Wait: read that ordering carefully. **Confirm-then-void, and the items live in the
container until confirmed.** A bin that voids first and asks second is a bin that
eats someone's netherite.

**Don't.**
- Don't drop contents on disconnect *after* confirmation — confirmed is gone.
- Don't let an ordinary composter stop composting. `PASS` unless the tag is there.
- Don't persist the container. If the server restarts mid-confirmation, the items
  are lost; that's acceptable and it's why the price is 8💎.

**Wire.** `VoidBin.register(registry);`

**Done when:** items void on confirm; cancelling returns every item including
components; an ordinary composter still works.

---

## 7. T4 — `RecipeGuard`, and the retrofit that matters more

**Files:** `fabric/src/main/java/wondrous/RecipeGuard.java` (new), `Definitions.java` (edit)

**Read this before starting: the spec frames §4.5 as protecting the new blaze-rod
wand. That undersells it.** Two *already shipped* items are affected right now:

- **Pocket Crafter** is a `crafting_table` — **300-tick furnace fuel**.
- Any tagged item whose base has a recipe can be crafted away for its ingredients.

So this task fixes a bug in items players may already own, and that's why it sits
in the first four rather than in Phase 1.

**Do.** Reject any crafting attempt where an input stack carries a `wondrous` tag.
Find the cheapest hook that works, in this order of preference:

1. A Fabric API recipe/crafting event, if one exists in this API version.
2. The crafting menu's slot-change / result path.
3. A `ServerRecipeManager` filter.

**Fuel is the harder half and it may not be solvable.** Burn time comes from the
registry, per item, and can't be overridden per stack without a Mixin — and we
don't do Mixins. If T0 and a search find no clean hook:

- Accept it, and **say so in the lore**: add `"dont put me in a furnace"` as a
  lore line on every tagged item whose base is fuel. In-voice, and it does about
  60% of the job.
- Record the decision in `NOTES.md`.

**Don't** spend more than a short timebox hunting for a fuel hook. The lore
fallback is a legitimate outcome of this task, not a failure.

**Wire.** `RecipeGuard.register();`

**Done when:** a tagged item can't be crafted into its ingredients, **and** the
fuel question is either fixed or explicitly documented as accepted risk.

---

## 8. T5 — `Nearby`

**Files:** `fabric/src/main/java/wondrous/Nearby.java` (new)

**Do.** One method:

```java
/** Loaded block entities implementing Container within `radius` of `centre`. */
public static List<Container> containers(ServerLevel level, BlockPos centre, int radius);
```

Iterate the block positions in the radius box and call `level.getBlockEntity(pos)`.
Do **not** walk every block entity in the chunk and filter by distance — the box
walk is bounded and predictable, the chunk walk isn't.

Skip unloaded positions. Skip containers the player can't use.

**Don't.** Radius 8 is a 17³ box — 4913 positions. That is fine for a manual
right-click and **catastrophic on a tick loop**. Put that number in a comment on
the method. **No caller in this entire plan may call `Nearby` from `Aura` or from
any scheduled pass.** T16 has its own position-keyed pass and it must not use this.

**Done when:** compiles, and the comment naming the 4913 figure and the tick-loop
prohibition is present.

---

## 9. T6 — Chuck It Wand (`chuck_it_wand`, 12💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/ChuckIt.java` (new)

**Do.** Base `Items.BLAZE_ROD`, voice `"put yourself away"`. On right-click (both
use callbacks):

1. `Nearby.containers(level, player.blockPosition(), 8)`
2. For each stack in the player's inventory **excluding hotbar and armour**, check
   whether any nearby container *already holds a matching item*.
3. If so, move as much as fits.
4. `Chime.say(player, "put away 47 items")` or `"nothing to put away"`, then one
   `Chime.confirm`.

**Don't — this is the rule that makes it a tool rather than a griefing device:**

**Only top up homes that already exist.** It must *never* create a new stack in a
container. Without that rule it scatters your inventory into the nearest furnace,
chest and hopper indiscriminately. Matching means item **and** components.

The **hotbar exclusion** is what stops it eating your pickaxe. Not optional.

Skip containers the player couldn't open. Skip furnace fuel/output slots and
anything with fixed slot roles — a brewing stand stuffed with cobblestone is the
canonical bug report.

**Done when:** items with a home go home; items without one stay in your inventory;
hotbar untouched; furnace and brewing stand unharmed.

---

## 10. T7 — Tidy Up Stick (`sorting_wand`, 12💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/TidyUp.java` (new)

**Do.** Base `Items.BRUSH` (damageable, so it costs one durability per use). Voice
`"in ORDER. thank you"`. Right-click a container: read all slots, merge partial
stacks of identical item+components, sort by registry id
(`BuiltInRegistries.ITEM.getKey`), write back.

**Don't.**
- **Skip the interaction entirely if the container is open for anyone else.**
  Sorting a chest out from under another player's cursor duplicates items. This is
  the one bug in this task that costs real money on a live server.
- No effect on fixed-role inventories: furnaces, brewing stands, anything that
  isn't a plain container. `PASS`, don't half-sort.

**Done when:** sorts a chest; refuses a chest another player has open; costs one
durability; leaves a furnace alone.

---

## 11. T8 — Big Lazy Hoe (`big_lazy_hoe`, 24💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/LazyHoe.java` (new)

**Do.** Base `Items.DIAMOND_HOE`, voice `"i'll get to it. ok i got to it"`. One
`UseBlockCallback` branching on the clicked block. Sneak = single block, either
branch.

**Mature crop** → for each of the 3×3 columns in the same plane and same Y:
1. Skip anything that isn't a `CropBlock` at max age.
2. `Block.dropResources(state, level, pos, null, player, heldStack)` — the same
   call `AreaBreak` relies on, and the reason Fortune, XP and drops are
   vanilla-accurate instead of hand-rolled. Do not hand-roll them.
3. **Set the block back to age 0, not to air.** Replant, don't re-place: no seed is
   consumed and no drop is stolen.
4. One durability per block actually harvested, not per swing.

**Dirt / grass / coarse dirt / rooted dirt / bare farmland** → till the 3×3, then
plant the first seed found in the player's inventory into each tilled block,
consuming it. Detect seeds by tag or by checking the block item's block is a
`CropBlock` — **not** a hardcoded item list.

**Don't.**
- **Never break a non-crop block. Ever.** The 3×3 is a filter, not a radius.
- Never till a block with something on top of it.
- Stop at the first failure *per block*; never abort the whole swing.
- `SUCCESS_SERVER` when it acted, `PASS` when it didn't, so an ordinary diamond hoe
  still behaves normally.

**Done when:** all six checks in `SPEC-EXPANSION.md` §13 Phase 1 for the hoe pass,
including "a non-crop in the plane survives" and "an ordinary diamond hoe is
unaffected".

---

## 12. T9 — `Aura`, and migrating `FlyingBoots` onto it

**Files:** `fabric/src/main/java/wondrous/Aura.java` (new), `FlyingBoots.java` (edit),
`WondrousMod.java` (edit)

**⚠ The spec's proposed interface is wrong and will break a shipped item.**
`SPEC-EXPANSION.md` §4.1 suggests:

```java
void apply(ServerPlayer player, Set<String> heldIds);   // DO NOT USE
```

That set carries no slot information. `FlyingBoots.evaluate` specifically reads
`EquipmentSlot.FEET` — port it to that signature and **boots sitting in a backpack
grant flight**. The same gap breaks any future "while worn" vs "while held" item.
Use this instead:

```java
/** What one player is carrying, and where. Built once per player per pass. */
public record Carried(Map<String, EquipmentSlot> equipped, Set<String> inInventory) {

    /** Worn in a specific slot -- the flying boots check. */
    public boolean wornAt(String id, EquipmentSlot slot) {
        return equipped.get(id) == slot;
    }

    /** In either hand. */
    public boolean held(String id) {
        EquipmentSlot slot = equipped.get(id);
        return slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
    }

    /** Anywhere at all -- equipment or inventory. The sprinkler's check. */
    public boolean anywhere(String id) {
        return equipped.containsKey(id) || inInventory.contains(id);
    }
}

public interface Pass {
    void apply(ServerPlayer player, Carried carried);
}
```

**Do.** One `ServerTickEvents.END_SERVER_TICK` handler. Every 10 ticks, for each
online player: walk equipment and inventory **once**, build one `Carried`, then run
every registered pass against it.

Then move `FlyingBoots` onto it as the first pass, with `evaluate` reworked to take
`Carried` and call `carried.wornAt(FLYING_BOOTS_ID, EquipmentSlot.FEET)`. Keep the
`granted` set, the creative/spectator handling, the `resetFallDistance()` on
removal, and the `AFTER_RESPAWN` hook exactly as they are — that class has shipped
and its edge cases are paid for.

**Don't.**
- Don't do this refactor *after* adding a second passive item. Three handlers each
  iterating every player every 10 ticks is how a server starts stuttering.
- Don't call `Nearby` from any pass. Ever. See T5.
- Don't change `FlyingBoots`' observable behaviour by even a tick.

**Wire.** `Aura.register();` replaces `FlyingBoots.register();`, and `FlyingBoots`
registers itself as a pass.

**Done when:** every boots check in `NOTES.md`'s existing test gate still passes —
wear, fly, remove mid-air and land unhurt, creative round-trip, die and respawn
wearing them. **This is a regression test on a shipped item and it is the most
important check in Phase 2.**

---

## 13. T10 — Growy Can + Lazy Sprinkler

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/Growth.java` (new)

One class, both items — they share the growable-block scan.

**`growy_can` (24💎, `Items.BUCKET`, "drink up babes")** — active, held
right-click:
1. Find growable blocks in a 5×5×3 box around the looked-at position.
2. `BonemealableBlock.performBonemeal` on up to N of them. **Do not hand-roll age
   increments** — vanilla's behaviour differs per crop and hand-rolling gets
   sugarcane and bamboo wrong.
3. Consume **one `minecraft:bone_meal` per use, not per block**. No bone meal →
   `Chime.reject` and `"need bone meal"`.
4. Green sparkle particles so it reads as bone meal.
5. ~4-tick cooldown, so holding right-click streams rather than floods.

**`lazy_sprinkler` (32💎, `Items.HEART_OF_THE_SEA`, "i got it, go do something
else")** — an `Aura` pass, active anywhere in the inventory (`carried.anywhere`).
Every ~60 ticks: scan 9×9×3, grow **one** random growable, consume one bone meal.
No bone meal → do nothing, silently. No nag.

**Cost control, and this is the item most likely to cause a lag complaint:**
243 positions per player per 60 ticks. **Check the player has bone meal *before*
scanning.** Cache the last result per UUID and skip players who haven't moved and
found nothing last pass.

**Don't.** Don't make the sprinkler faster per-crop than the can. The pair is the
shop's first upgrade rung: the can is fast and manual, the sprinkler is slow and
free. Put a line in the can's lore pointing at the sprinkler.

**Done when:** can consumes exactly one bone meal per use and fails cleanly at
zero; sprinkler grows one crop per cycle while you stand in a field; **20 players
online each holding a sprinkler costs nothing measurable.**

---

## 14. T11 — Smashy Mortar (`smashy_mortar`, 32💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/Mortar.java` (new)

**Do.** Base `Items.BOWL`, voice `"ugh fine, ill break it smaller"`. Right-click
with a stack in the **off-hand** — mortar stays in the main hand, interaction is
unambiguous. Conversions from one static map, so tuning is a one-line edit:

| In | Out |
|---|---|
| raw iron / gold / copper | 6 nuggets |
| gravel | 1 flint, always |
| bone | 6 bone meal (vanilla gives 3) |
| blackstone | gold nuggets, occasionally |

Bone → meal is deliberate: it feeds T10, so the crushing branch and the farming
branch buy each other.

Non-damageable base, so throughput is gated by a **cooldown**, not durability.
`Chime.crush` on success.

**Don't.** Nothing may convert to itself — an entry where in and out share an item
is an infinite loop of free items. Assert it. The conversion table is the one thing
in this whole plan worth a unit test; write one.

**Done when:** every table row converts; cooldown holds; nothing converts to
itself.

---

## 15. T12 — Never Empty Charm (`restock_ring`, 24💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/Restock.java` (new)

**Do.** Base `Items.NAUTILUS_SHELL`, voice `"i packed spares"`. An `Aura` pass,
~30 lines. If the main-hand stack is empty **and** the slot was non-empty on the
previous pass, move an identical item from elsewhere in the inventory into it.

- "What was in your hand last pass" is per-UUID, in memory only. It does not need
  to survive a restart. Clear it on disconnect — `AreaBreak.forget` is the pattern.
- Match on item **and** components, so an enchanted pickaxe is never replaced by a
  plain one.
- Never pull from armour or the off-hand.

**Note for the shop copy** (T18): this is the Tweakeroo feature that gets people
banned elsewhere. "The legal version" is good marketing and it's true.

**Done when:** restocks a mined-out stack; never swaps an enchanted tool for a
plain one; the UUID map doesn't leak on disconnect.

---

## 15½. T12.5 — the `Aura` effect batch

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/AuraEffects.java` (new)

Five items, one file. Each is an `Aura.Pass` that applies a vanilla
`MobEffectInstance` and lets `Aura`'s 10-tick cadence keep it topped up. This is
the batch that needs T9's corrected `Carried` interface (`BUILD-PLAN.md` §12) —
four of the five items are slot-conditional and cannot be expressed with the
spec's original `Set<String>` signature.

**Do.** Add five `Def` entries, then one behaviour class with one `register()`
that adds five passes to `Aura`:

| id | Name | 💎 | Effect (short, amplifier 0) | Condition |
|---|---|---|---|---|
| `owl_eye_goggles` | Owl Eye Goggles | 16 | `NIGHT_VISION` | `carried.wornAt(ID, EquipmentSlot.HEAD)` |
| `fishy_necklace` | Fishy Necklace | 16 | `WATER_BREATHING` + `DOLPHINS_GRACE` | `carried.held(ID)` |
| `toasty_scarf` | Toasty Scarf | 16 | `FIRE_RESISTANCE` | `carried.wornAt(ID, EquipmentSlot.CHEST)` |
| `floaty_feet` | Floaty Feet | 16 | `SLOW_FALLING` | `carried.wornAt(ID, EquipmentSlot.FEET)` |
| `zoomies_boots` | Zoomies Boots | 16 | `SPEED` | `carried.wornAt(ID, EquipmentSlot.FEET) && player.isSprinting()` |

Give each effect a duration comfortably longer than the 10-tick poll (200 ticks
is the number `SPEC-EXPANSION.md` §6.5–6.9 uses) and re-apply every pass while
the condition holds — `MobEffectInstance` re-application on an already-present
effect just refreshes the timer, it doesn't stack or flicker.

**`zoomies_boots` is the one with an actual condition to get right.** Read
`player.isSprinting()` fresh every pass; don't cache it. When the condition goes
from true to false (stopped sprinting, or took the boots off), let the effect run
out naturally rather than force-removing it — the remaining duration is at most
one poll interval, so it reads as instant and you avoid a `removeEffect` call
racing anything else that might be extending the same effect.

**`floaty_feet` replaces `ITEM-IDEAS.md`'s original proposal.** That version was
a `safe_fall_distance` attribute — a Tier 0 item, no `Aura` needed. This version
is Slow Falling instead, because "drift down and take no damage" beat "fall
slightly further before it hurts." Don't build the attribute version too; this
supersedes it.

**Don't.**
- Don't skip the slot check on any of the four worn items. Without it,
  `owl_eye_goggles` in a shulker box in your inventory (not worn) still grants
  night vision, which is the exact bug T9's `Carried` redesign exists to prevent.
- Don't give `fishy_necklace` a slot check — "held, either hand" is deliberate,
  confirmed in the spec, not an oversight.
- Don't hand-roll a duration/refresh scheme per item. One shared constant, one
  shared apply-if-absent-or-expiring helper, five one-line call sites.

**Wire.** `AuraEffects.register();` in `WondrousMod.onInitialize()`, after
`Aura.register()`. Each of the five passes registers itself with `Aura` inside
`AuraEffects.register()` — `WondrousMod` doesn't need to know there are five.

**Done when:** all five checks in `SPEC-EXPANSION.md` §13 Phase 2 for this batch
pass, in particular: no flicker across the refresh window while held/worn
continuously, effect ends within one poll of removal, and Zoomies never buffs
walking speed — only sprinting.

---

## 16. T13 — `Visuals`

**Files:** `fabric/src/main/java/wondrous/Visuals.java` (new)

**Do.** Three helpers, all vanilla-client-safe:

- **Outline a block** — spawn a `block_display` at the position, scale ~1.01,
  glowing flag set, on a scoreboard team whose colour sets the outline colour.
  Despawn to clear.
- **Trail** — `end_rod` or `DustParticleOptions` stepped along a vector, ~8 points.
- **Text** — delegate to `Chime.say` from T1. Don't add a second action-bar path.

**The escape hatch, and you are expected to take it if needed:** if display
entities fight back, draw the twelve block edges with `ServerLevel.sendParticles`
on the same 10-tick cadence. Cheaper, no entity lifecycle, slightly less crisp.
**Both are acceptable. Pick one, be consistent, don't spend a day on the first.**

**Don't.** Nothing here runs per-tick globally. Visuals run only for players
currently holding the wand, and only for links within ~48 blocks. An orphaned
`block_display` is a permanent glowing box in someone's base — every spawn needs a
guaranteed despawn path, including on disconnect and server stop.

**Done when:** an outline appears and reliably disappears, including after a
disconnect mid-selection.

---

## 17. T14 — `WondrousState`

**Files:** `fabric/src/main/java/wondrous/WondrousState.java` (new)

**Do.** Per-world saved data, keyed by position rather than by item:

- `crafting_station`: dimension + pos → nine `ItemStack`s
- `link_wand`: dimension + source pos → dest pos + owner UUID

Use whatever `SavedData` shape T0 confirmed. **This API moved in recent versions;
if T0 came back inconclusive, go back and finish T0 rather than guessing here.**

**The validation sweep is mandatory, not optional.** Once a minute, drop entries
whose block no longer matches what was registered. This covers explosions, pistons,
world-edit, `/setblock` and chunk deletion with one mechanism instead of a hook per
cause. It is the difference between "the link broke" and "a new chest inherited a
ghost inventory".

**Done when:** entries survive a restart, and a `/setblock air` over a registered
position is cleaned up within a minute.

---

## 18. T15 — Left It Out Crafter (`crafting_station`, 32💎)

**Files:** `Definitions.java` (edit), `CraftStation.java` (new), `StationMenu.java` (new)

**The one Wondrous item that places, on purpose.** It doesn't replace the Pocket
Crafter — both stay in the shop. Pocket Crafter works anywhere and empties on
close; this one only works where you left it and **keeps the grid exactly as you
left it**.

**Do.**
- It's a tagged `crafting_table`, so it places with no code. On placement, record
  dimension + pos in `WondrousState`.
- `UseBlockCallback` on any crafting table: registered position → open the
  persistent menu; anything else → `PASS`, vanilla behaviour.
- `StationMenu extends CraftingMenu`, passing a real
  `ContainerLevelAccess.at(level, pos)` instead of the `ContainerLevelAccess.NULL`
  that `Definitions.java` uses for the pocket version. Override `removed(Player)`
  to write the nine grid slots into `WondrousState` instead of dropping them.
  Pre-fill the slots on open.
- **The menu type stays vanilla `minecraft:crafting`.** Nothing new reaches the
  client.

**Don't — this is the trap.** `PlayerBlockBreakEvents.AFTER` must do all three of:
drop the station item back, drop the grid contents, and deregister the position.
Miss any one and either the contents vanish or the next table built on that spot
inherits a ghost inventory. The T14 sweep is the backstop, **not** the primary path.

**Out of scope for v1:** crafting from adjacent chests. It needs a hand-rolled sgui
3×3 with `RecipeManager` matching and no recipe book. Ship persistence first —
that's the part players notice every session.

**Done when:** place, fill grid, close, log out, log in, reopen — grid intact.
Break it: item and contents both drop, position deregisters.

---

## 19. T16 — Put It There Wand (`link_wand`, 32💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/LinkWand.java` (new)

Create's belts, without belts. Base `Items.BREEZE_ROD` — **not** a blaze rod,
because breeze rods aren't furnace fuel and this is the expensive item.

**Interaction:**

| Step | Action | Feedback |
|---|---|---|
| 1 | Right-click a machine | Outline, `Chime.select`, `"pick a chest"` |
| 2 | Right-click a container | Link forms. `Chime.confirm`, particle trail |
| 3 | Right-click the machine again | Unlinks. `Chime.unlink`, outline clears |
| — | Right-click air | Cancels a half-made selection |
| — | **Hold the wand** | Every link you own within ~48 blocks shows outlines + trail |

That last row is the item. Holstered, the world looks normal.

**Transfer.** Its own scheduled pass, ~every 8 ticks. **Not an `Aura` pass** — it's
position-keyed, not player-keyed.

**Do not hardcode which slot is the output.** Vanilla already answers it:

```java
WorldlyContainer.getSlotsForFace(Direction.DOWN)
WorldlyContainer.canTakeItemThroughFace(slot, stack, Direction.DOWN)
```

That's what a hopper underneath uses. Read the machine the way a hopper would and
**every machine works, including modded ones**, with no per-block table. Furnace,
blast furnace, smoker, brewing stand: free. Plain `Container` with no faces: take
from any slot.

**Locked rules, all of them:**
- **Loaded chunks only**, same as a real hopper.
- **Same dimension, ≤16 blocks.**
- **8 links per player.** This is the item's only real performance risk and the cap
  is what makes it a non-issue. Refuse the 9th with `Chime.reject`.
- Breaking either end kills the link, tells the owner, drops the entry.
- Record ownership so `/wondrous links` can audit a lag complaint.

**Done when:** furnace output reaches the chest; **outline and trail are visible on
a fully vanilla client with no mods — test on a clean profile, this is the whole
point of the item**; breaking either end unlinks with a message; the 9th link is
refused; nothing transfers across an unloaded chunk.

---

## 20. T17 — Piggyback Glove (`carry_glove`, 32💎)

**Files:** `Definitions.java` (edit), `fabric/src/main/java/wondrous/CarryGlove.java` (new)

**Do.** Base `Items.LEATHER`, voice `"come here you"`. Sneak-right-click a
container → it goes into your hand as a single item **with its contents**, block
becomes air. Place it back to restore both.

Same for a mob: sneak-right-click stores the entity with full NBT. **Before
designing that half, read `spiritwolves`' `SPEC.md` §6** — it solved this exact
problem for a stored wolf and there is no reason to solve it twice.

**Don't — the three that will bite:**
1. **A carried container inside a carried container** is a recursion bug. Refuse to
   pick up a chest while already carrying one.
2. **Dropping.** Take damage → drop the carry. Die → it drops as an item with
   contents intact, not into the void.
3. Refuse containers currently open for anyone, and any block entity that isn't a
   plain inventory.

**One correction to the spec.** §7.3 lists "two carried chests must never stack" as
a trap requiring `max_stack_size = 1`. It isn't one — stacking already requires
identical components, and two chests with different contents have different
`custom_data` by construction. Keep the assertion as a test; don't build machinery
for it.

**Done when:** a chest carries with contents; two carried chests don't stack; you
can't carry while carrying; damage drops it; death drops it with contents.

---

## 21. T18 — Shop and commands

**Files:** `cobbleeconomy` `shop.json` (edit), `WondrousCommands.java` (edit)

**Shop.** Per `DESIGN.md` §3, new items sell through `cobbleeconomy`'s `shop.json`
`components` block — **not** the frozen api-module pattern. One entry per item:

```json
"chuck_it_wand": {
  "item": "minecraft:blaze_rod",
  "quantity": 1,
  "price": 12,
  "currency": "diamond",
  "category": "Wondrous",
  "components": {
    "minecraft:custom_data":    { "wondrous": "chuck_it_wand" },
    "minecraft:item_name":      "Chuck It Wand",
    "minecraft:max_stack_size": 1,
    "minecraft:lore": [
      "put yourself away",
      "Right-click: everything with a home nearby goes home."
    ]
  }
}
```

`max_stack_size` is the one field here not already in use elsewhere in `shop.json`
— confirm the parser accepts it before writing all twenty entries (T0.5 and
T12.5's eight included — they sell the same way, just with no `menu` behaviour
behind them). **If it doesn't, drop the field rather than fighting it**:
components already prevent these from merging with ordinary items, so its real
value is cosmetic. (The spec's stated rationale for it — "stops a 32💎 item
merging into a stack of ordinary rods" — is not correct; differing `custom_data`
already guarantees that.)

`bone_meal` is already stocked, so T10's ammo needs no new line.

**Ladder copy matters as much as the code.** The can's lore should make you aware
the sprinkler exists; the Pocket Crafter's should point at the station.

**Commands.** No new tree. `/wondrous give` and `/wondrous list` pick everything up
from `Definitions.ALL` for free. One addition, after T16:

```
/wondrous links [player]     op level 2+, lists active machine→chest links
```

Gated by `Gate.mayAdminister`, same as `give`. It exists so a lag complaint can be
checked without reading saved data by hand.

**Done when:** every item is buyable, and `/wondrous list` shows the full set.

---

## 22. Final gate — before calling any of this done

Run the whole existing test gate in `NOTES.md` §"Test gate — everything" **plus**:

**Connect with a fully vanilla client, no mods.** Confirm nothing in the world
looks wrong, no registry sync error, and no "unknown entity" in the log. Every
item in this plan is worthless if it costs the server its vanilla-client promise —
that promise is the mod's whole architecture and it's the one thing that can't be
patched later.

Update `README.md`'s item table and `NAMES.md` with everything shipped. Both are
currently accurate as of the Phase 0 cut; keep them that way.
