# Item ideas

Sorted by what they cost to build, not by how fun they sound. Names are noun
phrases in Ellie's voice, same rules as the locked seven.

The tiers matter more than the list: **Tier 0 items need no code at all**, and
that's not obvious until you notice `ATTRIBUTE_MODIFIERS` came back in the recon.

---

## Tier 0 — pure components, zero logic

Set a component in `decorate` and you're done. No tick loop, no event hook, no
entry in any dispatch table. These work while worn or held because vanilla already
handles that.

| Name | Component | What it does |
|---|---|---|
| **Stompy Stair Boots** | `ATTRIBUTE_MODIFIERS` → `step_height` | Walk up full blocks, no jumping |
| **Long Arm Gloves** | `ATTRIBUTE_MODIFIERS` → `block_interaction_range` | Reach blocks from further away |
| **Chonky Heart Charm** | `ATTRIBUTE_MODIFIERS` → `max_health` | Extra hearts while held |
| **Floaty Feet** | `ATTRIBUTE_MODIFIERS` → `safe_fall_distance` | Fall further before it hurts |
| **Sticky Grip Boots** | `ATTRIBUTE_MODIFIERS` → `movement_speed` (negative), plus knockback resist | Hold your ground |

Base items are whatever reads right — leather armour dyed a colour each, or a
trinket-shaped vanilla item held in hand.

**This is the cheapest fun per line in the whole mod.** An afternoon gets you five
items that are pure data.

Worth confirming the exact attribute ids on 26.2 with a `javap` on `Attributes`
before committing to names — `safe_fall_distance` and `block_interaction_range` are
recent additions and may have moved.

---

## Tier 1 — one more row in the station dispatch table

Same six lines as the existing stations. Genuinely trivial.

| Name | Menu | Notes |
|---|---|---|
| **Fancy-Upper** | `SmithingMenu` | Armour trims and netherite upgrades |
| **Map Fixer** | `CartographyTableMenu` | Copy, expand, lock maps |
| **Peek Box** | `ChestMenu` over a held shulker box | Open a shulker without placing it — reads its contents from the stack's `container` component |

**Peek Box is the pick of these.** It's the single most-requested quality-of-life
thing on any server and it's an afternoon's work.

---

## Tier 2 — the aura poll

`FlyingBoots` already runs a 10-tick loop over online players. Generalise it into
an "aura" pass — walk each player's equipment and hotbar once, apply effects — and
every item below is a table entry rather than a new system.

| Name | Effect |
|---|---|
| **Owl Eye Goggles** | Night vision while worn |
| **Fishy Necklace** | Water breathing and dolphin's grace while held |
| **Toasty Scarf** | Fire resistance while worn |
| **Cozy Blanket** | Regeneration while held, but only when standing still |

Do the refactor before the second one of these, not after. Four separate tick
handlers each iterating every player is how a server starts stuttering.

Effects need re-applying before they expire — a 200-tick effect refreshed every 10
ticks is fine and won't flicker on the client.

---

## Tier 3 — event hooks, one class each

Real work, but each is self-contained and none needs a Mixin.

**Grabby Hands** — `ServerTickEvents`, pull nearby `ItemEntity`s toward the player
while it's in the inventory. A magnet. Universally loved, ~40 lines. Watch the
radius; a big one turns a mob farm into a lag spike.

**Go Bed Stone** — hearthstone. Right-click, stand still for ~5 seconds, teleport
to your bed or world spawn. Cancel on damage or movement. The cast time is what
stops it being a combat-escape button. Her *"go bed??"* is right there.

**One And Done Pick** — vein miner. `PlayerBlockBreakEvents.AFTER`, flood-fill
connected blocks of the same type, cap at ~64. Cap is not optional — an uncapped
vein miner on a stone floor eats a chunk.

**Toasty Pick** — auto-smelt. Same hook, swap the drop for its smelting result via
the server's `RecipeManager`. Pairs badly with the vein miner unless you cap hard.

**Where Did I Die Compass** — store the death position in the stack's `custom_data`
on `ServerPlayerEvents.AFTER_RESPAWN`, point the compass at it. Vanilla already
renders a compass pointing at an arbitrary position via the `lodestone_tracker`
component, so the needle works with no client mod.

**Bye Forever Bin** — opens a chest GUI whose contents are voided on close. Six
lines plus a confirmation, because someone will misclick their netherite into it.

---

## Tier 4 — possible, but they're projects

**Big Purse** — a backpack with persistent contents. Serialise a `SimpleContainer`
into the stack's `container` component on menu close. The trap is stacking: two
backpacks with different contents must never merge, and a backpack inside a
backpack is a recursion bug waiting to happen. Block both explicitly.

**Brain Juice Jar** — XP bank. Store and withdraw experience, count in
`custom_data`. Easy logic, fiddly UI — there's no vanilla menu shaped like this, so
it's either chat commands or your first real sgui use.

**Pocket Trader** — a `MerchantMenu` with offers you define. Genuinely interesting
for quest rewards, and the one place a custom menu earns its keep.

---

## Tier 5 — don't

- **Pocket Furnace, Brewing Stand** — logic lives in the block entity, not the
  menu. Already covered.
- **Pocket Enchanting Table** — caps at level 1 with a null level access.
- **Pocket Beacon** — needs a real pyramid in the world.
- **Anything with a new model, block, or entity** — that's the Polymer decision,
  and it's still not worth the dependency.

---

## If you want three

**Peek Box** (Tier 1), the **Tier 0 batch** (five items, one afternoon), and
**Grabby Hands** (Tier 3). Together that's seven new items for roughly the effort
of one hard one, and it exercises every part of the architecture that already
exists.

Then **Go Bed Stone**, because it's the best-feeling item on the list and it's
named already.
