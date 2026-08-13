# Wondrous — Expansion Build Spec (ten new items)

> **Status:** design locked, nothing implemented. **Verification status is not
> the usual house standard and you should know that before you start:** the
> signatures quoted from files already in this tree (`Definitions.java`,
> `Stations.java`, `AreaBreak.java`, `WondrousTag.java`, `Chime.java`) are read
> from the source and are accurate. Everything else — display entities, saved
> data, `WorldlyContainer`, `RecipeManager` — is **stated from knowledge and
> not yet checked against the 26.2 merged jar.** Each is flagged 🔍 where it
> appears. Do the `javap` pass in §12 before writing the first line of Phase 3.
>
> Companion to `AUTOFARM-IDEAS.md` (why these items) and `ITEM-IDEAS.md` (the
> tier scale). This document is *how*.
>
> **If you are implementing this, read `BUILD-PLAN.md` instead and start there.**
> It carries the task order, the exact files, the `javap` commands for every 🔍
> above, and the stop conditions. Where the two disagree, `BUILD-PLAN.md` wins —
> it was written after the Phase 0 cut. Two known corrections it makes to this
> document: the `Aura` interface in §4.1 loses slot information and would break
> `FlyingBoots`, and §2's stacking rationale for `max_stack_size` is wrong.

---

## 1. What this adds

Twenty items across five phases, all server-side, all vanilla-client-safe, no
Mixins. Phase −1 (§4½) is three pure-component items and needs no code beyond a
`decorate` lambda. Phase 0 (§4A) is two dispatch rows that need none of the §4
infrastructure. Both ship before the ten from the original ten-item spec, which
fall into three groups:

- **Farming** — the loop compression that client mods are downloaded 80M+ times
  for, sold instead of installed.
- **Create outcomes** — what Create's machines *end up doing*, minus the
  machinery we can't render.
- **Sanctioned conveniences** — features players currently sneak in with
  Tweakeroo and friends.

The architecture doesn't change. Every item is still a vanilla item carrying
`{wondrous: "<id>"}` in `minecraft:custom_data`, behaviour still lives in Fabric
API callbacks, and `Definitions.ALL` is still the catalogue.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Registry entries | **None, still** | Vanilla clients connect unchanged |
| Mixins | **None, still** | Ten mods, one Mixin. Keep it that way |
| Dispatch | **Each behaviour class reads its own id** | Matches `FlyingBoots`/`AreaBreak`. `Def` gains no new fields |
| Stack size | **`max_stack_size = 1` on all ten** | Stops a 32💎 item merging into a stack of ordinary rods |
| Tagged items in recipes | **Blocked centrally** (§4.5) | Otherwise a blaze-rod wand crafts into blaze powder |
| Offline behaviour | **None of them act offline** | Cut with the Chugging Mill. Loaded chunks only, like a hopper |
| Growth ammo | **Literal `minecraft:bone_meal` from inventory** | Already a shop line; bone farms feed crop farms; no charge counter to explain |
| Area size | **3×3 for the hoe, sneak for single** | Same contract players already learned from the Big Hole tools |
| New blocks/models/entities | **Still none** — display entities excepted (§4.3) | Display entities are vanilla and render on a vanilla client |
| Ids | **Frozen on first ship** | Quest JSON and `/wondrous give` reference them |

---

## 3. The twenty items

| id | Name | Base item | Phase | 💎 | Damageable |
|---|---|---|---|---|---|
| `long_arm_gloves` | Long Arm Gloves | `leather_gloves`-shaped 🔍 | −1 | 8 | no |
| `sticky_grip_boots` | Sticky Grip Boots | `leather_boots` | −1 | 8 | yes (vanilla) |
| `frog_boots` | Frog Boots | `leather_boots` | −1 | 8 | yes (vanilla) |
| `peek_box` | Peek Box | `shulker_box` | 0 | 12 | no |
| `void_bin` | Bye Forever Bin | `composter` | 0 | 8 | no |
| `big_lazy_hoe` | Big Lazy Hoe | `diamond_hoe` | 1 | 24 | yes (vanilla) |
| `chuck_it_wand` | Chuck It Wand | `blaze_rod` | 1 | 12 | no |
| `sorting_wand` | Tidy Up Stick | `brush` | 1 | 12 | yes (vanilla) |
| `growy_can` | Growy Watering Can | `bucket` | 2 | 24 | no |
| `lazy_sprinkler` | Set And Forget Sprinkler | `heart_of_the_sea` | 2 | 32 | no |
| `smashy_mortar` | Smashy Mortar | `bowl` | 2 | 32 | no |
| `restock_ring` | Never Empty Charm | `nautilus_shell` | 2 | 24 | no |
| `owl_eye_goggles` | Owl Eye Goggles | `leather_helmet` | 2 | 16 | no |
| `fishy_necklace` | Fishy Necklace | `nautilus_shell` | 2 | 16 | no |
| `toasty_scarf` | Toasty Scarf | `leather_boots` (renders as chest slot 🔍) | 2 | 16 | no |
| `floaty_feet` | Floaty Feet | `leather_boots` | 2 | 16 | no |
| `zoomies_boots` | Zoomies Boots | `leather_boots` | 2 | 16 | no |
| `crafting_station` | Left It Out Crafter | `crafting_table` | 3 | 32 | no |
| `link_wand` | Put It There Wand | `breeze_rod` | 3 | 32 | no |
| `carry_glove` | Piggyback Glove | `leather` | 3 | 32 | no |

**"Phase −1"** is the Tier 0 batch — see §4½ below. It ships before Phase 0 and
needs nothing from §4.

**On the base items for the Phase −1 and effect batch:** most are dyed leather
armour worn in the slot the effect makes sense in (boots for movement, helmet for
vision). `long_arm_gloves` has no vanilla glove item, so it's whatever reads as
"worn on the hands" — 🔍 check what non-armour-slot item this suite already uses
for a similar trinket before inventing a new convention. `toasty_scarf` is
chestplate-shaped; the id says scarf, the model says chestplate, same trade the
"Never Empty Ring"/nautilus-shell trade already made in §3 below.

**Base-item notes, because two of these have teeth:**

- **`blaze_rod` is furnace fuel** (2400 ticks) *and* the blaze-powder ingredient.
  A player can burn a Chuck It Wand. Fuel values come from the registry and
  can't be overridden per-stack without a Mixin, so this is accepted risk —
  which is why the *cheap* wand gets the blaze rod and the 32💎 one gets the
  breeze rod. **`breeze_rod` is not fuel**; its only recipes are wind charges
  and the mace, both covered by §4.5.
- **Non-placeable bases were chosen deliberately.** A passive item on a block
  base (dripstone, end rod) would place itself on right-click, and suppressing
  that means another `UseBlockCallback` branch per item. `heart_of_the_sea`,
  `nautilus_shell`, `bowl`, `leather` and the rods all avoid the problem for
  free. `crafting_station` is the single exception and it places **on purpose**.
- **"Never Empty Ring" is renamed "Never Empty Charm"** — there is no vanilla
  ring, and a name that lies about what you're holding is exactly what
  `Definitions`' class comment warns against.
- Only two are damageable, so §5's durability sink applies to the hoe and the
  brush and nothing else. The other eight are permanent one-time purchases. If
  that's wrong for the economy, the fix is charges on the item, not a base-item
  swap.

---

## 4. Shared infrastructure — build this first

Five pieces. Four of the ten items are cheap *only* if these exist.

### 4.1 `Aura.java` — one poll for all passive items

`FlyingBoots` already runs a 10-tick `ServerTickEvents` loop over online
players. Generalise it:

```java
// Aura.register(registry) -- one ServerTickEvents.END_SERVER_TICK handler.
// Every 10 ticks: for each online player, walk equipment + inventory ONCE,
// collect which wondrous ids are present, then run each registered pass.
public interface Pass { void apply(ServerPlayer player, Set<String> heldIds); }
```

Move `FlyingBoots` onto it as the first pass, unchanged in behaviour. Then
`lazy_sprinkler` and `restock_ring` are table entries.

**Do this refactor before the second passive item, not after.** Three handlers
each iterating every player every 10 ticks is how a server starts stuttering.

### 4.2 `Nearby.java` — the radius container scan

Shared by `chuck_it_wand`, `sorting_wand`, `link_wand`, and later the crafting
station's chest sourcing.

```java
/** Loaded block entities implementing Container within `radius` of `centre`. */
static List<Container> containers(ServerLevel level, BlockPos centre, int radius);
```

Iterate the block positions in the radius box and call `level.getBlockEntity`,
rather than walking every block entity in the chunk. Radius 8 is a 17³ box —
4913 positions, fine for a manual right-click, **not** fine on a tick loop. No
item in this spec may call it from `Aura`.

### 4.3 `Visuals.java` — outlines, trails, action bar 🔍

The Put It There Wand's whole appeal is that you can see it working, and all of
it is vanilla-client-safe.

- **Outline a block** — spawn a `block_display` entity at the position, scale
  ~1.01, glowing flag set, on a scoreboard team whose colour sets the outline
  colour. Despawn to clear. 🔍 *Verify:* `Display.BlockDisplay`, the transform
  component, `Entity.setGlowingTag`, and team colour plumbing on 26.2.
  **Fallback if display entities fight you:** draw the twelve block edges with
  `ServerLevel.sendParticles` on the same 10-tick cadence. Cheaper, no entity
  lifecycle, slightly less crisp. Either is acceptable; pick one and be
  consistent across the mod.
- **Trail** — `end_rod` or `DustParticleOptions` stepped along the machine→chest
  vector, ~8 points, only while the wand is held.
- **Text** — `ServerPlayer.sendSystemMessage(msg, true)` for the action bar 🔍
  (confirm the overload; `displayClientMessage` is the other candidate).
- **Sound** — extend `Chime.java`. It already sends
  `ClientboundSoundPacket` straight down one player's connection, which is
  exactly right: nobody else hears your wand.

**Nothing here is per-tick global.** Visuals run only for players currently
holding the wand, and only for links within ~48 blocks.

### 4.4 `WondrousState.java` — per-world saved data 🔍

Phase 3 needs persistence keyed by position, not by item:

- `crafting_station`: dimension + pos → nine `ItemStack`s
- `link_wand`: dimension + source pos → dest pos, plus owner UUID

🔍 *Verify:* `SavedData` / `SavedDataType` and
`ServerLevel.getDataStorage().computeIfAbsent(...)` on 26.2 — this API moved in
recent versions and the shape is the thing most likely to have changed.

**A validation sweep is mandatory, not optional.** Once a minute, drop entries
whose block no longer matches what was registered. This covers explosions,
pistons, world-edit, `/setblock`, and chunk deletion without a hook per cause,
and it's the difference between "the link broke" and "a new chest inherited a
ghost inventory".

### 4.5 Recipe protection

A tagged blaze rod is still `minecraft:blaze_rod` to the recipe system. Without
this, a 12💎 wand crafts into two blaze powder and the player writes an angry
message.

Reject any crafting where an input carries a `wondrous` tag. Cheapest hook that
works: on the crafting menu's slot-change/result path, or a
`ServerRecipeManager` filter. 🔍 **This one needs research before Phase 1** —
if no clean event exists, the fallback is to accept the risk on the rods and
say so in the item lore (*"dont put me in a furnace"* is on-voice and does
about 60% of the job).

---

## 4½. Phase −1 — the Tier 0 batch, zero logic

`ITEM-IDEAS.md` §"Tier 0" called this the cheapest fun per line in the mod: set a
component in `decorate` and the item is done. No hook, no tick, no dispatch row.
`areaTool()` in `Definitions.java` is already this exact pattern — a stack-mutating
`UnaryOperator<ItemStack>` that sets `ATTRIBUTE_MODIFIERS` — with a different
attribute and sign.

Two of the five original Tier 0 candidates (Stompy Stair Boots, Chonky Heart
Charm) are cut — not enough payoff to be worth a shop slot next to items that
actually solve a problem. A third, Floaty Feet, is **not** the attribute item
`ITEM-IDEAS.md` proposed; see §6.5 below, it needs `Aura` and moves to Phase 2.

### 4½.1 `long_arm_gloves` — Long Arm Gloves (8💎)

`ATTRIBUTE_MODIFIERS` → `player.block_interaction_range`, positive. Reach blocks
from further away. 🔍 Confirm the exact attribute id on 26.2 — recent addition,
may have moved.

### 4½.2 `sticky_grip_boots` — Sticky Grip Boots (8💎)

`ATTRIBUTE_MODIFIERS` → `movement_speed` (negative) plus knockback resistance.
Hold your ground — the trade-off pairs it against Zoomies Boots (§6.9) the same
way the two Big Hole tools already trade speed for area.

### 4½.3 `frog_boots` — Frog Boots (8💎)

`ATTRIBUTE_MODIFIERS` → `jump_strength`, positive. Terraria's frog-gear jump
boost, ported as one attribute line. 🔍 Confirm `Attributes.JUMP_STRENGTH` (it may
be `GENERIC_JUMP_STRENGTH` depending on how far the attribute rename went by
26.2) and its usable range — vanilla's own potion effect caps jump height
somewhere before it turns into fall damage on landing, and the modifier should
land inside that band, not past it.

**All three:** one `Def` entry, one `decorate` lambda modelled on `areaTool()`,
zero new files. `long_arm_gloves`, `sticky_grip_boots` and `frog_boots` should
share one small helper (`attributeItem(Attribute, double, Operation)`) rather than
three copy-pasted lambdas — this is the one place in the whole expansion where a
tiny shared helper is worth it, because it's three near-identical call sites in
the same list literal.

---

## 4A. Phase 0 — two items, no new infrastructure at all

Both are dispatch rows in the shape `Stations.java` already handles: a tagged item,
a menu, `SUCCESS_SERVER` so it doesn't place. They ship before Phase 1 because
neither waits on §4.

**Also cut in this pass, and the reason matters for what gets added later:**
`pocket_grindstone`, `pocket_stonecutter` and `pocket_loom` were removed from
`Definitions.ALL`. A pocket station only earns a slot if it saves a trip you would
actually make, and those three replace blocks you use rarely and never far from
base. Smithing and cartography tables were considered as additions and rejected on
the same test. **The retired ids are not reused.**

### 4A.1 `peek_box` — Peek Box (12💎)

*"just checking what i packed"*

A tagged `shulker_box`. Right-click anywhere opens a `ChestMenu` over the stack's
own `minecraft:container` component; contents write back on close. Never placed,
never on the ground, works from the hotbar mid-mine.

**Three things to block explicitly, all of them dupe or loss bugs:**

1. **The box inside itself.** The stack is in the player's inventory *while its own
   menu is open*, so it's reachable in the lower grid. Putting it into its own
   menu either duplicates it or destroys it. Refuse the slot move — check the
   stack identity, not just the id, since a player may own two.
2. **The stack moving mid-menu.** Dropped, swapped, or shift-clicked to another
   slot while open, the write-back on close targets a stack that isn't there any
   more. Write back by stack identity and no-op if it's gone, or close the menu on
   any change to the holding slot. The second is simpler and worth preferring.
3. **Nesting a shulker in a shulker.** Vanilla already blocks this for placed
   boxes; the component-backed menu does not inherit that check. Add it.

🔍 *Verify:* the `minecraft:container` component's read/write API on 26.2
(`ItemContainerContents` — codec shape, and whether it caps at 27 slots), and
whether `ChestMenu` over a `SimpleContainer` gives a usable `removed(Player)` hook
or needs a `ContainerListener`.

### 4A.2 `void_bin` — Bye Forever Bin (8💎)

*"i dont want it. its gone. dont ask"*

Right-click opens a chest GUI whose contents are destroyed on close. A tagged
`composter` — reads as somewhere you throw things, isn't fuel, and isn't a
container base that could be confused with the Peek Box.

- **Void on close *and* on disconnect.** Logging out with it open must not leave
  items in limbo, and must not drop them back either — the whole contract is that
  what goes in is gone.
- **Confirm before voiding.** Someone will misclick netherite into it. A chat
  message listing the item count with a `run_command` click to confirm is enough;
  cancelling drops the contents back to the player.
- Backed by a plain `SimpleContainer` in memory, keyed to the open menu. Nothing
  persists, nothing is saved, and that's the point.

**Priced at 8💎, the cheapest thing in the shop**, deliberately: it's the item a
new player buys first and it teaches them the shop's components work.

---

## 5. Phase 1 — three items, no new infrastructure but §4.2

### 5.1 `big_lazy_hoe` — Big Lazy Hoe (24💎)

*"i'll get to it. ok i got to it"*

One `UseBlockCallback`, dispatching on the clicked block:

**Mature crop** → for each of the 3×3 columns centred on it (same plane, same Y):
1. Skip anything that isn't a `CropBlock` at max age.
2. `Block.dropResources(state, level, pos, null, player, heldStack)` — this is
   the same choice `AreaBreak` makes and it's why Fortune, XP and drops are
   vanilla-accurate rather than hand-rolled.
3. Set the block back to age 0 rather than air. **Replant, don't re-place** —
   no seed is consumed and no drop is stolen.
4. One durability per block actually harvested, not per swing.

**Dirt / grass / coarse dirt / rooted dirt, or bare farmland** → till the 3×3
(`hoe` behaviour, or set `farmland` directly and play the vanilla sound), then
for each tilled block plant the **first seed found in the player's inventory**,
consuming it. Seed = anything whose placement is a `CropBlock` 🔍 (a tag check
against `c:seeds` or an instanceof on the block item's block is cleaner than a
hardcoded list).

**Sneak** → single block, either action.

**Rules:**
- Never break a non-crop block. Ever. The 3×3 is a filter, not a radius.
- Never till a block with something on top of it.
- Stop at the first failure per block, never abort the whole swing.
- Return `SUCCESS_SERVER` when it did something, `PASS` when it didn't, so an
  ordinary hoe still works normally.

🔍 *Verify:* `CropBlock.isMaxAge` / `getAge` / `getStateForAge`,
`Block.dropResources` overloads, and the farmland conversion path.

### 5.2 `chuck_it_wand` — Chuck It Wand (12💎)

*"put yourself away"*

Right-click (`UseItemCallback` and `UseBlockCallback`, same double registration
`Stations` uses):

1. `Nearby.containers(level, player.blockPosition(), 8)`.
2. For each stack in the player's inventory **excluding the hotbar and armour**
   — the hotbar exclusion is what stops it eating your pickaxe — check whether
   any nearby container already holds a matching item.
3. If so, move as much as fits. **Only top up homes that already exist.** It
   must never create a new stack in a chest, or it scatters your inventory into
   the nearest furnace and becomes a griefing tool against your own base.
4. Action bar: *"put away 47 items"*, or *"nothing to put away"*.
5. One confirmation note. Not one per item.

Skip containers the player can't open 🔍 (locked containers / `Container.stillValid`).

### 5.3 `sorting_wand` — Tidy Up Stick (12💎)

*"in ORDER. thank you"*

Right-click a container block: read all slots, merge partial stacks of the same
item+components, sort by registry id 🔍 (`BuiltInRegistries.ITEM.getKey`), write
back. Skip the interaction entirely if the container is open for anyone else —
sorting a chest out from under another player's cursor duplicates items.

Costs one durability (brush is damageable). No effect on containers with fixed
slot roles: furnaces, brewing stands, anything that isn't a plain inventory.

---

## 6. Phase 2 — needs `Aura`

### 6.1 `growy_can` — Growy Watering Can (24💎)

*"drink up babes"*

Active, held right-click. On use:
1. Find growable blocks in a 5×5×3 box around the looked-at position.
2. Apply one bone-meal-equivalent growth tick to up to N of them 🔍
   (`BonemealableBlock.performBonemeal` is the right entry point and matches
   vanilla behaviour per crop type — do not hand-roll age increments).
3. Consume **one `minecraft:bone_meal` from the inventory per use**, not per
   block. Fail with a *bass* note and *"need bone meal"* if there's none.
4. Green sparkle particles, same as vanilla bone meal, so it reads correctly.

Cooldown ~4 ticks so holding right-click is a stream rather than a flood.

### 6.2 `lazy_sprinkler` — Set And Forget Sprinkler (32💎)

*"i got it, go do something else"*

Passive `Aura` pass. Every ~60 ticks, while it's anywhere in the player's
inventory:
1. Scan a 9×9×3 box around the player for growable blocks.
2. Grow **one** of them (random pick), consuming one bone meal.
3. If there's no bone meal, do nothing silently. No nag.

Slower per crop than the can, far wider, and it works while you do something
else. The pair is the shop's first real upgrade rung — make sure the can's lore
mentions the sprinkler exists.

**Cost control:** the 9×9×3 scan is 243 positions per player per 60 ticks. Bail
out early if the player has no bone meal *before* scanning, and skip players who
haven't moved and had no growable block last pass 🔍 (cache the last result per
player UUID).

### 6.3 `smashy_mortar` — Smashy Mortar (32💎)

*"ugh fine, ill break it smaller"*

Right-click with a stack in the **off-hand** (keeps the mortar in your main hand
and makes the interaction unambiguous). Converts, from a small table:

| In | Out |
|---|---|
| raw iron / gold / copper | 6 nuggets (a partial upgrade, not a clean double — tune) |
| gravel | 1 flint, always |
| bone | 6 bone meal (vanilla gives 3) |
| blackstone | gold nuggets, occasionally |

Bone → meal is deliberate: it feeds the two growth items, so the crushing branch
and the farming branch buy each other. Keep the table in one static map so
tuning is a one-line edit.

Non-damageable base, so gate throughput with a **cooldown**, not durability 🔍
(`ServerPlayer.getCooldowns().addCooldown(...)` — confirm it takes a stack or an
item on 26.2).

### 6.4 `restock_ring` — Never Empty Charm (24💎)

*"i packed spares"*

`Aura` pass. If the player's main-hand stack is empty **and** the slot was
non-empty on the previous pass, find an identical item elsewhere in the
inventory and move it into the hand slot.

- Track "what was in your hand last pass" per player UUID in memory only. It
  does not need to survive a restart.
- Match on item **and** components, so an enchanted pickaxe isn't replaced by a
  plain one.
- Never pull from armour or the off-hand.

This is the Tweakeroo feature that gets people banned elsewhere. Say so in the
shop copy — "the legal version" is good marketing and it's true.

### 6.5–6.9 The `Aura` effect batch

`ITEM-IDEAS.md`'s Tier 2 in full, plus two more. Once `Aura` exists (§4.1) each of
these is a pass that applies a vanilla `MobEffectInstance` and re-applies it before
it expires — a 200-tick effect refreshed on the existing 10-tick cadence doesn't
flicker on the client. No new mechanic, no particle work, no menu. Table entries,
same as `lazy_sprinkler` and `restock_ring` above.

| id | Name | 💎 | Effect | Condition |
|---|---|---|---|---|
| `owl_eye_goggles` | Owl Eye Goggles | 16 | Night vision | worn (head) |
| `fishy_necklace` | Fishy Necklace | 16 | Water breathing + dolphin's grace | held, either hand |
| `toasty_scarf` | Toasty Scarf | 16 | Fire resistance | worn (chest) |
| `floaty_feet` | Floaty Feet | 16 | Slow falling | worn (feet) |
| `zoomies_boots` | Zoomies Boots | 16 | Speed, **sprinting only** | worn (feet) + `player.isSprinting()` |

**`floaty_feet` is not the item `ITEM-IDEAS.md` proposed.** That version set the
`safe_fall_distance` attribute — a pure Tier 0 component, no `Aura` needed — but
"further before it hurts" is a worse fantasy than "you drift down and take no
damage at all." Slow Falling is the correct vanilla primitive for that and it's
effect-based, which is why this item lives here instead of in §4½.

**`zoomies_boots` needs the conditional check, not a static attribute.** A plain
`ATTRIBUTE_MODIFIERS` speed boost (the Tier 0 shape) buffs walking too, and the
ask here is specifically a sprint buff — Terraria's Hermes-Boots fantasy is "you
go fast when you choose to run," not "you're just generally faster." So this is
an `Aura` pass, not a `decorate` lambda: apply the Speed effect only on ticks
where `carried.wornAt(ZOOMIES_ID, EquipmentSlot.FEET)` **and**
`player.isSprinting()` are both true, and let the effect lapse (don't
force-remove it) the moment either goes false — the 10-tick refresh window is
short enough that this reads as instant.

**Slot-conditional effects are exactly why `Aura`'s interface must carry slot
data** (see `BUILD-PLAN.md` T9 — the spec's original `Set<String> heldIds>`
signature can't express "worn in this slot," and four of these five items need
it). `fishy_necklace` is the one exception in this batch — "held, either hand"
is deliberate, so a player can keep it in the off-hand permanently without
giving up a main-hand tool slot.

---

## 7. Phase 3 — the three projects

### 7.1 `crafting_station` — Left It Out Crafter (32💎)

*"dont clean that up, i was using it"*

**The one Wondrous item that places.** Not a Pocket Crafter replacement — both
stay in the shop, and the trade is real:

| | Pocket Crafter | Left It Out Crafter |
|---|---|---|
| Works | anywhere, from your inventory | only where you placed it |
| Grid | empties on close, like vanilla | **stays exactly as you left it** |
| For | one recipe on a mining trip | the project you're mid-way through |

**Placement.** It's a `minecraft:crafting_table` with the tag, so it places as a
crafting table with no code. On placement 🔍 (`BlockPlacedCallback` or
`UseBlockCallback` observing the result), record dimension + pos in
`WondrousState`.

**Opening.** `UseBlockCallback` on a crafting table checks the store: registered
position → open the persistent menu; anything else → `PASS`, vanilla behaviour.

**The menu.** A `CraftingMenu` subclass. `Definitions.java:119` already reads:

```java
new CraftingMenu(id, inv, ContainerLevelAccess.NULL)
```

The station version passes a real `ContainerLevelAccess.at(level, pos)` and
overrides `removed(Player)` to write the nine grid slots into `WondrousState`
instead of dropping them, with the slots pre-filled on open. **The menu type is
still vanilla `minecraft:crafting`, so nothing new reaches the client.**

**Breaking**, and this is the trap: `PlayerBlockBreakEvents.AFTER` must drop the
station item back, drop the grid contents, and deregister the position. Miss any
of the three and either the contents vanish or the next table built on that spot
inherits a ghost inventory. The §4.4 sweep is the backstop, not the primary path.

**Out of scope for v1:** crafting from adjacent chests. It's the other half of
what Tinkers' station is famous for and it needs a hand-rolled sgui 3×3 with
`RecipeManager` matching and no recipe book. Ship persistence first — it's the
part players notice every session.

### 7.2 `link_wand` — Put It There Wand (32💎)

*"the chest. that one. THAT one"*

Create's belts, without belts. Link a machine's output to a chest.

**Interaction:**

| Step | Action | Feedback |
|---|---|---|
| 1 | Right-click a machine | Outline on the block, bell note, action bar *"pick a chest"* |
| 2 | Right-click a container | Link forms. Chime, particle trail machine → chest |
| 3 | Right-click the machine again | Unlinks. Bass note, outline clears |
| — | Right-click air | Cancels a half-made selection |
| — | **Hold the wand** | Every link you own within ~48 blocks shows outlines + slow trail |

That last row is the item. Holstered, the world looks normal.

**Transfer.** A scheduled pass (its own handler, ~every 8 ticks, **not**
`Aura` — it's position-keyed, not player-keyed) moves items from source to
destination at roughly hopper speed.

**Do not hardcode which slot is the output.** Vanilla already answers that:

```java
WorldlyContainer.getSlotsForFace(Direction.DOWN)
WorldlyContainer.canTakeItemThroughFace(slot, stack, Direction.DOWN)
```

🔍 That's exactly what a hopper underneath uses. Read the machine the way a
hopper would and **every machine works, including modded ones**, with no
per-block table. Furnace, blast furnace, smoker, brewing stand: free. For a
plain `Container` with no faces, take from any slot.

**Locked rules:**
- **Loaded chunks only**, same as a real hopper. Offline transfer would be a
  different item with different economics, and we cut that item.
- **Same dimension, ≤16 blocks.** Longer range is the premium version later.
- **8 links per player.** This is the item's only real performance risk and the
  cap is what makes it a non-issue. Refuse the 9th with a bass note.
- **Breaking either end kills the link**, tells the owner, and drops the entry.
- Ownership is recorded so `/wondrous links` can list them and an admin can
  audit a lag complaint.

### 7.3 `carry_glove` — Piggyback Glove (32💎)

*"come here you"*

Sneak-right-click a container → it goes into your hand as a single item **with
its contents**, and the block becomes air. Place it back down to restore both
block and contents. Same for a mob: sneak-right-click stores the entity 🔍
(full NBT via the same approach `spiritwolves` uses for a stored wolf — read
`SPEC.md` §6 there before designing this, it solved the identical problem).

**The three things that will bite, all of which need explicit blocks:**
1. **Two carried chests must never stack.** `max_stack_size = 1` plus differing
   `custom_data` handles it, but assert it in a test.
2. **A carried container inside a carried container** is a recursion bug. Refuse
   to pick up a chest while carrying one.
3. **Dropping.** Take damage → drop the carry. Die → it drops as an item with
   contents intact, not into the void.

Also refuse: containers with something open, other players' claimed blocks 🔍
(no claim system exists in the suite — if one lands later, this is its first
consumer), and any block entity that isn't a plain inventory.

---

## 8. Shop integration

Per `DESIGN.md` §3, new items are sold through `cobbleeconomy`'s `shop.json`
`components` block — **not** through the frozen api-module pattern. The full
form for one item, which the other nine copy:

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

🔍 Confirm `max_stack_size` is accepted by the `components` parser and by 26.2's
component codec — it's the one field here that isn't already in use elsewhere in
`shop.json`.

`bone_meal` is already stocked (64 for 256 cobblestone), so the two growth items
need no new shop line for ammo.

**Ladder copy matters as much as the code.** The can's lore should make you aware
the sprinkler exists; the Pocket Crafter's should point at the station. That's
the free half of the Create lesson in `AUTOFARM-IDEAS.md` §4.

---

## 9. Commands

No new command tree. `/wondrous give <player> <id>` picks all twenty up for free
via `Definitions.ALL`, and `/wondrous list` grows by twenty rows — and loses
three, `pocket_grindstone`/`pocket_stonecutter`/`pocket_loom`, cut ahead of this
expansion (see `NAMES.md` §"Retired"; the ids stay retired, not reused).

One addition, Phase 3 only:

```
/wondrous links [player]     op level 2+, lists active machine→chest links
```

Gated by `Gate.mayAdminister`, same as `give`. This exists so that when someone
reports lag you can see how many links are live without reading saved data by
hand.

---

## 10. Sound cues

Extend `Chime.java` — it already sends `ClientboundSoundPacket` down one
player's connection, so these are private by construction.

| Cue | Sound | Pitch |
|---|---|---|
| Select (link wand) | `NOTE_BLOCK_BELL` | 1.2 |
| Confirm (link formed, items chucked, sorted) | `NOTE_BLOCK_CHIME` | 1.4 |
| Reject (no bone meal, cap hit, refused carry) | `NOTE_BLOCK_BASS` | 0.8 |
| Crush (mortar) | `BLOCK_STONE_BREAK` | 1.0 |
| Unlink | `NOTE_BLOCK_BASS` | 1.0 |

One note per action, never one per item moved.

---

## 11. Module layout

New files under `fabric/src/main/java/wondrous/`:

```
PeekBox.java          §4A.1 (may be a Definitions row plus a small menu class)
VoidBin.java          §4A.2

Aura.java             §4.1  generalised passive poll; FlyingBoots moves onto it
Nearby.java           §4.2  radius container scan
Visuals.java          §4.3  outlines, trails, action bar
WondrousState.java    §4.4  per-world saved data + validation sweep
RecipeGuard.java      §4.5  tagged items can't be crafted away

PeekBox.java           §4A.1
VoidBin.java           §4A.2

LazyHoe.java          §5.1
ChuckIt.java          §5.2
TidyUp.java           §5.3
Growth.java           §6.1 + §6.2  (can and sprinkler share the growable scan)
Mortar.java           §6.3
Restock.java          §6.4  (an Aura pass, ~30 lines)
AuraEffects.java       §6.5-6.9 (five effect passes, one file -- each is
                       ~5 lines of MobEffectInstance application; splitting
                       them into five files would be five headers around one
                       line of logic each)
CraftStation.java     §7.1  + StationMenu.java
LinkWand.java         §7.2
CarryGlove.java       §7.3
```

`long_arm_gloves`, `sticky_grip_boots` and `frog_boots` (§4½) need no file at
all — they're three more entries in `Definitions.ALL` sharing one small
`attributeItem(...)` helper alongside the existing `areaTool()`.

`Definitions.ALL` gains twenty entries. `WondrousMod` gains ten new `register`
calls (the three §4½ items and the three original stations don't register
anything — no menu, no hook, just a `Def`).
**`Definitions.Def` does not change** — no new fields, no behaviour enum. Each
class checks `WondrousTag.is(stack, ID)` for its own id, exactly as
`FlyingBoots` and `AreaBreak` already do.

The `core`/`fabric` split doesn't apply here — `wondrous` has no pure-Java core
module and these items are all Minecraft-shaped. The mortar's conversion table
is the one thing worth extracting for a unit test if you want one.

---

## 12. Do this before Phase 3 — the `javap` pass

Every 🔍 in this document, in one sitting, against the merged jar in the Gradle
cache. In rough order of how badly a surprise would hurt:

1. `SavedData` / `SavedDataType` / `getDataStorage()` — §4.4. **Most likely to
   have moved.** Everything in Phase 3 sits on it.
2. `Display.BlockDisplay` construction, transform, glowing, team colour — §4.3.
   If this fights back, take the particle fallback and move on; don't spend a
   day on it.
3. `WorldlyContainer.getSlotsForFace` / `canTakeItemThroughFace` — §7.2. If the
   shape changed, the "every machine works for free" property is what you lose.
4. `BonemealableBlock.performBonemeal` signature — §6.1.
5. `CropBlock` age helpers and `Block.dropResources` overloads — §5.1.
6. Recipe-input interception — §4.5. If there's no clean hook, decide *before*
   Phase 1 ships whether the rods are acceptable as-is.
7. Action bar overload, cooldown API, `max_stack_size` in the shop parser.

`NOTES.md` already lists seven signatures that bit during the first build. Add
what you find here to it.

---

## 13. Definition of done

**Per phase**, not all at the end.

**Phase −1**
- Each of the three grants its effect immediately on wear/hold with no delay and
  no tick loop — it's a component, so it's live the instant the item is equipped.
- Removing the item removes the effect immediately (vanilla's own attribute
  system handles this; confirm it rather than assume it).
- No interaction with `Aura` at all — these three should compile and work even
  before Phase 2 exists.

**Phase 0**
- Peek Box: contents survive close, relog, and being handed to another player;
  cannot be put inside itself; cannot hold another shulker; dropping it while the
  menu is open loses nothing and duplicates nothing.
- Bye Forever Bin: contents void on close after confirmation; cancelling returns
  every item; logging out with it open voids rather than dropping; an ordinary
  composter still composts.

**Phase 1**
- Hoe: 3×3 harvest replants and consumes no seeds; Fortune applies across all
  nine; sneak = one block; a non-crop in the plane survives; till+plant fills a
  3×3 from inventory; an ordinary diamond hoe is unaffected.
- Chuck It: items with a home go home; items without one **stay in your
  inventory**; hotbar untouched; furnace/brewing outputs aren't stuffed with
  cobblestone.
- Tidy Up: sorts a chest; refuses a chest another player has open; costs one
  durability.

**Phase 2**
- `FlyingBoots` behaviour is bit-identical after the `Aura` refactor — this is
  the regression that matters most, and it's an existing shipped item.
- Can grows crops and consumes exactly one bone meal per use; fails cleanly at
  zero.
- Sprinkler grows one crop per cycle while you stand in a field; costs nothing
  measurable with 20 players online holding one.
- Mortar converts each table row; cooldown holds; nothing converts to itself.
- Charm restocks a mined-out stack; never swaps an enchanted tool for a plain
  one.
- Owl Eye / Fishy / Toasty / Floaty: effect never lapses visibly while worn/held
  continuously (no flicker across the 10-tick refresh); effect ends within one
  poll of removal.
- Zoomies: Speed applies only while both worn and sprinting; stopping sprinting
  drops the effect within one poll without needing to remove the boots; walking
  speed is unaffected.

**Phase 3**
- Station: place, fill grid, close, log out, log in, reopen — grid intact.
  Break it: item and contents both drop, position deregisters. `/setblock` air
  over it: sweep cleans up within a minute.
- Link wand: furnace output reaches the chest; outline and trail visible on a
  **vanilla client with no mods** (test this on a clean profile, it's the whole
  point); breaking either end unlinks with a message; 9th link refused; nothing
  transfers across an unloaded chunk.
- Glove: chest carries with contents; two carried chests don't stack; can't
  carry while carrying; damage drops it; death drops it with contents.

**Across all three:** connect with a fully vanilla client and confirm nothing
in the world looks wrong, no registry sync error, no "unknown entity" in the log.

---

## 14. Explicitly out of scope

- Chest-sourcing for the crafting station (§7.1) — v2 of that item.
- Cross-dimension links, longer link range — premium variants later.
- Anything acting while the player is offline. Cut with the Chugging Mill, and
  cutting it leaves `dailyquests` alone again on "bring them back tomorrow"
  (`SUITE_AUDIT.md`). That gap needs solving somewhere else in the suite; it is
  not this mod's job.
- Grabby Hands, Toasty Pick, Snack Bag, Huffy Bellows, Go Bed Stone, One Chop
  Axe, Big Swipe Sickle, Straight To The Bag, Do It Again Crafter — cut, with
  reasons, in `AUTOFARM-IDEAS.md` §6.
- Schematic/paste wand, trains, rotational power — need a client mod, or lose
  the point without one.
