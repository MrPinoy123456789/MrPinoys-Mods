# MrPinoy's Disenchanter — Build Spec

> **Status:** design draft. UI section is the load-bearing part of this
> document, per request. sgui behaviors marked *(verify)* must be checked
> against `eu.pb4:sgui:2.1.0+26.2` before implementation — the suite has only
> ever used sgui with chest menus (`GENERIC_9x3`/`9x6`) and the anvil input
> gui; `GRINDSTONE` is new territory.

---

## 1. The problem this solves

Enchanted gear accumulates. Mob drops, fishing loot, villager trades, gear a
player has outgrown — all of it carries enchantments locked to items nobody
wants. Vanilla's only answer is the grindstone, which *destroys* the
enchantments and refunds a trickle of XP.

The Disenchanter inverts that: it destroys the **item** and saves the
**enchantments**, writing them onto a book. The book then travels through the
normal anvil economy onto gear the player actually wants.

**The load-bearing rule: the item is always destroyed. Enchantments become
transferable, but gear does not become duplicable. You trade the body for the
soul.**

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Extraction | **All enchantments at once, onto one book** | One input, one output, no partial-pick UI complexity |
| Interface | **Grindstone screen via sgui** | 2-input/1-output layout is exactly our shape; vanilla clients see a familiar screen. Chest GUIs read as menus, not machines |
| Cost | **XP levels, scaled like anvil enchanting costs** | Sum of level × rarity multiplier per enchantment (§5) |
| Item fate | **Destroyed** | Locked. No stripped-item refund, no partial salvage |
| Curses | **Not transferred, cost nothing** | Curses die with the item. Disenchanting is also curse disposal — that is a feature, priced at losing the item |
| Book input | **Exactly one vanilla `minecraft:book`** | Stack of books in the slot: one is consumed, rest returned |
| Enchanted books as input | **Rejected** | Book-to-book is a no-op under all-at-once extraction |
| Persistence | **None** | The machine holds nothing between sessions; open GUIs return items on close |
| Cross-mod | **None** | Standalone, per DESIGN.md §2 |
| Mixins | **Zero target** | Everything hangs off a use-block event and sgui |
| Client requirement | **None.** `"environment": "server"` | Non-negotiable, suite rule |

---

## 3. Opening the machine

There is no custom block — server-side mods do not get one without a resource
pack, and the suite rule is packless. The machine **is** the vanilla
grindstone, accessed one gesture away from its vanilla function:

- **Right-click a grindstone** → vanilla grindstone, untouched.
- **Sneak + right-click a grindstone** → the Disenchanter opens.

One block, two machines, and the gesture is discoverable by accident. A
`UseBlockCallback` handler checks `player.isShiftKeyDown()` and the block
state; on a hit it opens the sgui screen and returns `InteractionResult`
consume/success so vanilla never sees the click. *(verify: exact
`InteractionResult` constant names in 26.2.)*

`/disenchant` also opens it, for servers that want it gesture-free. No
permission needed — the machine charges its own cost.

---

## 4. The UI — grindstone screen, three slots

This is the heart of the spec. The vanilla grindstone screen has exactly three
slots and we use all three for their visual meaning:

```
┌─────────────────────────────┐
│  Repair & Disenchant        │   ← title, set by sgui
│                             │
│   [top input]    ─►  [out]  │
│   [bottom input]            │
│                             │
│   (player inventory below)  │
└─────────────────────────────┘
```

| Slot | Index | Role | Accepts |
|---|---|---|---|
| Top input | 0 | **The victim** — the enchanted item | Any item with ≥1 non-curse enchantment |
| Bottom input | 1 | **The vessel** — the blank book | `minecraft:book` only |
| Output | 2 | **The preview / result** | Never accepts insertion; take-only |

### 4.1 Slot behavior

Both input slots are **real slots** — the player places and removes actual
items from their inventory, sgui's slot-redirect mechanism pointing them at a
3-slot `SimpleContainer` owned by the open GUI. *(verify: `setSlotRedirect`
exists on `SimpleGui` in 2.1.0+26.2 and works under `MenuType.GRINDSTONE`;
this is the single biggest implementation risk — §8.)*

The output slot is **virtual**. It shows a preview but holds nothing real
until the moment of the click that takes it.

### 4.2 The preview — the output slot as the price tag

The grindstone screen has no cost readout the way the anvil does, so the
output item's tooltip **is** the display surface. Recompute the preview on
every input-slot change:

- **Both inputs valid, player can afford it** → the output shows the finished
  enchanted book, glinting, with its real enchantment list, plus lore lines:

  > `Cost: 12 levels` *(green)*
  > `The Iron Sword is destroyed.` *(dark red)*
  > `Curse of Vanishing will not transfer.` *(gray — only when a curse is present)*

- **Both inputs valid, player too poor** → the output shows the same book but
  as a **barrier-item stand-in** *(or the book without glint — pick whichever
  reads better in play)* with:

  > `Cost: 12 levels — you have 7` *(red)*

  Clicking it does nothing but a `NOTE_BLOCK_BASS`-style deny sound.

- **Inputs missing or invalid** → output is empty. If the top item is
  unenchanted or curse-only, show a gray hint item in the output:

  > `Nothing here to save.` *(curse-only: "Curses cannot be saved — only buried.")*

The preview is always the truth: what you see in slot 2 is exactly what
clicking it produces, or exactly why it won't.

### 4.3 The transaction

Taking the output is the commit point, and it is atomic, validated
server-side at click time (never trust the preview's staleness):

1. Re-validate: top item still enchanted, bottom slot still has a book,
   player still has the levels. Any failure → refresh preview, deny sound,
   nothing moves.
2. Deduct the levels (`player.giveExperienceLevels(-cost)` *(verify name)*).
3. Consume the victim entirely. Consume **one** book from the bottom stack.
4. Place the enchanted book in the player's cursor (normal take-from-slot
   feel) or inventory via the suite's `giveOrDrop` pattern if cursor logic
   fights us.
5. Sound: `BLOCK_GRINDSTONE_USE` then `BLOCK_ENCHANTMENT_TABLE_USE` layered —
   the grind, then the magic leaving the metal. *(verify both sound event
   constants.)*
6. Preview clears (top slot is now empty).

### 4.4 Closing the screen

On close, anything in the two input slots goes back to the player
(`giveOrDrop`). The machine never keeps items. Server stop with the screen
open follows the same path via the GUI's close handler.

---

## 5. The cost formula

Anvil-style: each enchantment costs `level × rarity multiplier`, using the
same per-enchantment multipliers the anvil uses for books (the *book* rates,
not the item rates — we are producing a book):

```
cost = Σ over non-curse enchantments ( level × bookMultiplier(enchantment) )
```

In 26.2 enchantments carry an `anvil_cost` in their definition — read it from
the enchantment holder rather than hardcoding a rarity table. *(verify: field
name and accessor on the `Enchantment` record in the 26.2 jar; §8.)*

Worked examples at vanilla rates:

| Item | Cost |
|---|---|
| Sharpness V, Unbreaking III, Mending | 5×1 + 3×1 + 1×2 = **10 levels** |
| Silk Touch | 1×4 = **4 levels** |
| Protection IV ×4 pieces | 4 levels each — disenchanting a full set costs like enchanting one |

Cheap enough to use, expensive enough that XP stays a real currency. The
deeper price is always the item itself.

---

## 6. Edge cases, decided now

| Case | Ruling |
|---|---|
| Item with only curses | Rejected — preview explains (§4.2). Nothing to save |
| Curses alongside real enchants | Real enchants transfer, curses vanish, curses cost 0 |
| Damaged item | Fine. Durability is the body's problem and the body is dying anyway |
| Stack of enchantable items in top slot | Only single items accepted; stacks refused at the slot *(enchanted items don't stack in practice, but belt and braces)* |
| Writable/written book as vessel | Rejected. `minecraft:book` exactly |
| Creative-mode player | Same flow, cost check auto-passes (creative XP semantics) |
| Two players, one grindstone | Independent GUIs, independent containers. No shared state, no conflict |
| Hopper interaction | None — there is no real block entity. Nothing to automate, deliberately |

---

## 7. Module layout

Single-module like spiritwolves (no `core`/`fabric` split — there is no
platform-independent logic worth isolating):

```
disenchanter/
  src/main/java/disenchanter/
    Disenchanter.java        // ModInitializer: event + command registration
    DisenchantGui.java       // the grindstone sgui screen, §4 entire
    Extraction.java          // pure logic: validate, cost, build result book
  src/main/resources/fabric.mod.json
  build.gradle.kts           // sgui included, same coordinates as ballot
```

`Extraction` is pure and unit-testable: `(ItemStack victim, ItemStack vessel,
int playerLevels) → Result(book, cost) | Rejection(reason)`. The GUI renders
whatever `Extraction` says; it decides nothing itself. Same
rules-live-in-one-place discipline as ballot's `Menus`.

---

## 8. Verify before writing code

1. `MenuType.GRINDSTONE` with sgui `SimpleGui` — does it open, does the
   client render it, do the three slot indices land where §4 assumes.
2. `setSlotRedirect` (or equivalent) on grindstone slots — can players place
   real items. **If this fails, the fallback is `GENERIC_3x3` (dispenser
   screen), not a chest** — it still reads as a machine.
3. Enchantment anvil-cost accessor on the 26.2 `Enchantment` record.
4. `ItemEnchantments` / `DataComponents.ENCHANTMENTS` vs `STORED_ENCHANTMENTS`
   component names for reading the item and writing the book in 26.2.
5. Curse detection — `Enchantment` curse predicate or tag
   (`minecraft:curse` enchantment tag) in 26.2.
6. Sound event constant names (§4.3).
7. `UseBlockCallback` signature against installed Fabric API 0.156.0+26.2.

---

## 9. Out of scope for v1

- Partial extraction (pick one enchantment) — contradicts a locked decision,
  recorded only so nobody proposes it as a "small addition."
- XP-bottle or item-based payment alternatives.
- Any economy-mod bridge (cobbleeconomy pricing) — possible later, not now.
- Custom block / block entity. Packless rule forbids it regardless.
