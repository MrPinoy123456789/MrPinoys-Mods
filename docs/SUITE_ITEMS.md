# Combined Suite Items

> Merged from SUITE_ITEMS.md and SUITE_ITEMS_PLAN.md. Originals archived in docs/archive/.

# Suite Items

> **What this is.** The one format every mod in the suite uses to publish a custom
> item so that other mods can sell it, gift it, drop it or price it — without
> depending on the mod that defined it, and without anybody editing a shared file.
>
> **What this is not.** A shop spec or a Wayfarers spec. Those own what an item
> *costs* and how often it *appears*. This owns only what an item **is**.

---

## 1. The rule

**A suite item is a vanilla item plus a component patch, published as read-only
JSON inside the defining mod's own jar, and discovered by everyone else through
the vanilla datapack merge.**

Nothing writes. Nothing depends. Remove a mod and its items disappear from every
consumer on the next start, with no stale entries left behind to clean up.

---

## 2. Why this does not break "currency is the only coupling"

`DESIGN.md` §2 says every mod is decoupled and the only shared thing is the
currency. This adds a second shared thing, so it has to earn it.

It earns it because the coupling is **data-only, one-way, and optional**:

- No mod gains a compile dependency on another. A producer ships a JSON file; a
  consumer reads whatever JSON files happen to exist.
- No mod needs to know another exists. `cobbleeconomy` never names
  `spiritwolves` in Java. It reads an index and finds six entries, or zero.
- A consumer with an empty index behaves exactly as it does today.

The thing §2 was protecting against was a dependency graph — a mod that fails to
load because another is missing, or a change in one that forces a rebuild of
another. Neither is possible here. Currency is still the only coupling *in code*.
This is a coupling in **content**, which is the thing a server owner is allowed to
assemble however they like.

---

## 3. The two surfaces

The single most important decision in this document is that these are separate
files with separate owners, and it is the reason the obvious design — one shared
`config/suite/items.json` that every mod appends to — is wrong.

| | **Definition** | **Use** |
|---|---|---|
| Answers | What *is* a Spirit Stone | What does it cost, how often does it drop |
| Owned by | The mod that invented it | The server owner |
| Lives in | `spiritwolves.jar`, read-only | `config/cobbleeconomy/shop.json` |
| Written by | Nobody at runtime | The admin, and the GUIs that edit for them |
| On mod removal | Vanishes | Entry degrades and hides, is not deleted |

A shared append-file collapses those two into one, and every failure follows from
that collapse:

- **Two writers, one file.** Load order decides who wins, and an owner's
  hand-edits are clobbered the next time a mod re-asserts its block.
- **Nobody owns it.** Config is per-mod by convention here. A `config/suite/`
  file belongs to no mod, so no mod may safely rewrite it.
- **Removal leaves corpses.** Uninstall a mod and its lines remain, needing
  provenance fields and a garbage-collection pass that nothing else needs.
- **It puts prices in the wrong hands.** `spiritwolves` would be writing a shop
  price. What a Spirit Stone costs is the server owner's decision, and
  `ShopConfig`'s own header says so: *"anything that requires a rebuild to change
  is a price nobody will change."* A mod-authored price is worse — it requires a
  *reinstall*.

---

## 4. Definition format

### 4.1 Where it lives

```
<modid>.jar
  data/<namespace>/suite_items/<path>.json      one item
  data/<namespace>/suite_items/_source.json     optional, describes the namespace
```

The id is `namespace:path`, derived from the file location exactly the way every
other datapack type derives one. `data/spiritwolves/suite_items/spirit_stone.json`
is `spiritwolves:spirit_stone`.

**Use the vanilla resource manager, not a directory scan.** A
`SimpleJsonResourceReloadListener` on `suite_items` gets the merged view of every
loaded mod's `data/` for free, honours `/reload`, and lets a server owner add or
override entries with an ordinary datapack in `world/datapacks/` without touching
a jar. Walking mod jars by hand gets none of that and breaks the override path.

### 4.2 An item

```json
{
  "item": "minecraft:echo_shard",
  "name": "Spirit Stone",
  "tags": ["trinket", "tier2", "bindable"],
  "rarity": "uncommon",
  "components": {
    "minecraft:custom_data": { "spiritwolves": { "bound": false } },
    "minecraft:item_name": "Spirit Stone",
    "minecraft:lore": ["A wolf's name, unspoken."]
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `item` | yes | The vanilla registry item it is stamped onto |
| `name` | yes | Plain-text display name, for logs, chat listings and menu titles |
| `tags` | no | Suite tags. Free-form strings. See §5 |
| `rarity` | no | `common` \| `uncommon` \| `rare` \| `legendary`. A **hint**, never a price |
| `requires` | no | A mod id; consumers omit the definition when it is not loaded (§12.2). Service items only |
| `components` | yes | Data components, in the exact shape vanilla `/give` accepts |

`components` is deliberately identical to the block `ShopEntry.components` and
`Listing.components` already carry, and to what the duplicated `ItemComponents`
parsers in `cobbleeconomy` and `wayfarers` already read. This format is not new —
it is the existing one, given a name and an owner.

### 4.3 A source

```json
{
  "name": "Spirit Wolves",
  "icon": "minecraft:bone",
  "description": "Wolves bound to a Spirit Stone."
}
```

Purely cosmetic, for the admin browser's source picker (§9). Absent, a consumer
falls back to the namespace and the first item's base.

### 4.4 `item` must be a vanilla item

Not a convention — a constraint, and the reason the whole suite still runs on
unmodified clients. `cobbleeconomy`'s README promises vanilla clients need
nothing installed: no custom items, no synced registry entries, no resource pack.
A definition naming a non-vanilla registry id would break that promise the moment
it appeared in a shop GUI.

A loader that meets a non-vanilla `item` **rejects the entry with a warning**. It
does not substitute a placeholder and it does not fail the server.

---

## 5. Tags, and why vanilla item tags cannot do this

The instinct is `#suite:trinket` as a real item tag. It does not work, and it is
worth being blunt about why: **a Spirit Stone and a plain echo shard are the same
registry item.** A vanilla tag matches the registry id, so `#suite:trinket` would
match every echo shard on the server, including the one a player just picked up
in an ancient city.

So suite tags are a field on the definition, resolved by the loader into an index
of `tag -> [id]`. They are strings, lowercase, no namespace. Three kinds are
worth keeping distinct by convention:

| Kind | Examples | Used by |
|---|---|---|
| **Category** | `trinket`, `tool`, `consumable`, `cosmetic` | Shop categories, browser filters |
| **Tier** | `tier1` … `tier4` | Wayfarers gift weighting, dungeon loot depth |
| **Trait** | `bindable`, `questgiver`, `one_per_player` | Consumer-specific behaviour |

This is what makes discovery work. Wayfarers asks for *"something tagged
`trinket`, tier 2"* and gets whatever is installed — three mods present means
three possible gifts, one mod means one, and nobody edits a config either way.
`Listing.tag` in `wayfarers/core` is already declared and currently unused; it is
the slot this fills.

---

## 6. Identity: match the marker, never the patch

**Every suite item must carry `minecraft:custom_data` containing a key equal to
its namespace.** It is the only required component.

**The value's shape is deliberately unconstrained**, and an earlier draft of this
section got that wrong — it mandated `<namespace>.id = <path>`, a shape neither
real publisher uses:

```jsonc
// wondrous, via WondrousTag.stamp — a string
"minecraft:custom_data": { "wondrous": "pocket_workbench" }

// spiritwolves, via SpiritStone.createUnbound — an object, no id
"minecraft:custom_data": { "spiritwolves": { "bound": false } }
```

Both are correct. The marker has to be whatever the publishing mod **already
stamps and already reads**, because that is the entire point of the rule below —
and mandating a shape would have meant asking mods to restamp, breaking every
such item already in a player's inventory. That is the exact harm §6 exists to
prevent, so the spec, not the mods, was what had to change.

A publisher with more than one suite item needs something inside the value to
tell them apart. Wondrous's string does it; a nested `"id"` is the recommended
way for new mods. It is the publisher's concern, not the loader's — the loader
requires only that the namespace key is present.

The rule that follows is the one that will save real player inventories:

> To recognise a suite item on a stack — selling it back, checking ownership,
> gating a behaviour — compare **only** the custom-data marker. Never compare the
> component patch, and never compare lore or name.

Component equality is fragile in exactly the way that hurts most. Fix a typo in a
lore line and every Spirit Stone already sitting in a player's ender chest stops
matching, becomes unsellable, and stops binding its wolf. The marker is stable
across every cosmetic change the item will ever get.

`wondrous.api.WondrousTag` already implements precisely this — `stamp`, `read`,
`is` over a `custom_data` string key. It is the reference implementation; a
consumer's version is a dozen lines and does not need the `wondrous` jar.

---

## 7. Loader contract

Every consuming mod carries its own copy. It is roughly eighty lines and it must:

1. Register a `SimpleJsonResourceReloadListener` on `suite_items`, so `/reload`
   rebuilds the index.
2. Skip `_source.json` when building the item index; read it into a separate
   source index.
3. Reject and log any entry missing `item`, `name` or `components`, naming a
   non-vanilla `item` (§4.4), or lacking the namespace-keyed custom-data marker
   (§6). **Reject the entry, never the reload.**
4. Build two indexes: `id -> definition`, and `tag -> [id]`.
5. Expose an `ItemStack build(String id, int count, HolderLookup.Provider)` that
   applies the component patch — the existing `ItemComponents.apply` is already
   this function.

**Do not build a shared Gradle module for it.** Every mod here is a standalone
build with its own `settings.gradle.kts` and there is no root aggregate, so
sharing eighty lines would mean cross-build publishing — more machinery than the
duplication costs, and it reintroduces exactly the dependency this document
exists to avoid. `ItemComponents` is already duplicated verbatim between
`cobbleeconomy` and `wayfarers` for the same reason. That is the correct trade.

The contract is the JSON schema and the resource path. It is not a Java type.

---

## 8. Consuming

### 8.1 By id — cobbleeconomy

`shop.json` gains one alternative to `item`:

```json
"spirit_stone": {
  "suite_item": "spiritwolves:spirit_stone",
  "quantity": 1, "price": 3, "currency": "diamond",
  "category": "Curios"
}
```

`item` and `suite_item` are mutually exclusive. An entry naming an id that does
not resolve is **hidden and logged**, not deleted — the same degrade-don't-crash
rule `ShopConfig.load` already applies to a malformed line, and for the same
reason: uninstalling a mod for an evening must not silently destroy the prices an
owner spent an hour tuning.

### 8.2 By tag — wayfarers

Listings reference a tag, and the wayfarer carries whatever is installed:

```json
{ "gives": { "tag": "trinket", "tier": "tier2", "weight": 3 } }
```

This is the form that makes Wayfarers a discovery mechanism rather than a second
shop. A tag that resolves to nothing means the wayfarer offers nothing from that
line — not an error, just a quieter encounter.

### 8.3 The rarity hint

`rarity` exists so a consumer can stock or weight a fresh install without the
owner hand-writing every line — `/cobbleeconomy shop import <tag>` picking a
default price per rarity band. It is a **starting point that the owner
immediately owns**: once imported, the price lives in `shop.json` and the hint is
never consulted again. A producing mod must never be able to change a price on a
server that has already set one.

---

## 9. The admin interface

Adding a suite item to a shop is **browsing, not typing**. `ShopAdminMenu`
already states the division of labour: sgui keeps the two things a dialog cannot
do — take the item out of your hand, and nudge a price without typing a number. A
browser is the third. It is a paged grid of item icons, which is what a chest
menu *is*; dialogs are a vertical stack of fields with no grid and no paging.

The payoff is that a suite item's icon is its real stamped stack, with its name,
lore and glow. `ShopMenu.iconFor` already renders component-stamped entries and
`WondrousShop.iconStack` exists for exactly this. The browser is WYSIWYG for free
— you see the Spirit Stone, not the words `minecraft:echo_shard`.

### 9.1 Source picker

One icon per namespace that contributed items, from `_source.json`, with the item
count in the lore. "Spirit Wolves · 6 items" beats "spiritwolves".

### 9.2 Item grid

Paged at the existing `PAGE_SIZE` of 45 with the same ninth-row nav, so it moves
like the list screen an admin already knows. Each slot is the stamped stack. The
bottom row doubles as tag chips — one button per tag present in this source,
click to toggle the filter. The tag index comes from the loader (§7), not from
the menu, because Wayfarers wants the same answer.

### 9.3 Click adds, then edits

No third screen. Clicking creates the entry immediately with defaults derived
from the definition — key from the path, quantity 1, category from the first
category tag or the source name, price from the rarity hint — saves it, and opens
the existing per-entry editor on the price row. The zero-typing path ends on the
price nudge buttons, which are already a no-typing interaction.

The browser **owns no rules**, in the sense `ShopAdminMenu`'s header already
means: every click builds a `ShopEntry` and calls `ShopConfig.save`, the same call
the chat commands make. The two GUIs and the commands cannot drift because there
is only one of them underneath.

### 9.4 Already-added state is not optional

Build it in from the start; it is painful to retrofit. The grid shows which items
are already listed — green name, a checkmark in the lore — and clicking one jumps
to its editor rather than creating a duplicate. Without it, an admin browsing a
forty-item mod cannot tell what they have already stocked.

On key collision, namespace-qualify: `spiritwolves.spirit_stone`, not
`spirit_stone_2`. It reads better in `/buy` tab-completion, which is where a
player meets it.

### 9.5 Add all visible

One button on the filtered grid, so stocking a fresh install is ten seconds
rather than forty clicks. Follow it with a dialog confirm — *"Added 6 items from
Spirit Wolves"*, Done / Add more — which is the summary work dialogs are good at.

---

## 10. Migration

### 10.1 Retiring the wondrous dependency

`WondrousShop` is the only file in `cobbleeconomy` that imports `wondrous.api`,
and its own header describes its removal as *"deleting this file and the branches
in ShopConfig and ShopCommands that call into it."* Publishing wondrous items as
`suite_items` makes that deletion real:

1. Emit one `suite_items` file per wondrous item, with the `WondrousTag` marker
   already in `custom_data` — the format wondrous uses **is** the format in §6.
2. Repoint any `"wondrous:<id>"` shop entry at `"suite_item": "wondrous:<id>"`.
3. Delete `WondrousShop`, its branches, and the `wondrous.api` dependency from
   `cobbleeconomy`'s build.

Note that `WondrousGive.giveOrDrop` goes with it; a consumer needs its own
give-or-drop, which is a handful of lines it very likely already has.

### 10.2 Existing inline `components` entries stay valid

`shop.json` entries with a hand-written `components` block keep working forever.
`suite_item` is an addition, not a replacement. An owner who has already stamped
a Spirit Stone by hand is not asked to migrate, and their entry and the published
definition will match anyway if both follow §6.

---

## 11. Adding a producing mod

- [ ] One `data/<modid>/suite_items/<id>.json` per custom item
- [ ] Every one carries a `custom_data.<namespace>` marker matching what the mod
      already stamps today — never a new shape (§6)
- [ ] Every `item` is a vanilla registry id (§4.4)
- [ ] Tags applied from the three conventional kinds (§5)
- [ ] `rarity` set where it is meaningful, and understood to be a hint (§8.3)
- [ ] A `_source.json` with a display name and icon
- [ ] `requires` set if — and only if — the item is a service item (§12.2)
- [ ] No dependency added, in either direction

The mod gains nothing at runtime by doing this and loses nothing by skipping it.
That asymmetry is the point: a producing mod publishes and forgets, and a
consuming mod finds it or does not.

---

## 12. Roles

Sections 1–11 assume one consumer and one kind of producer. Three consumers are
now in view — `cobbleeconomy`, `wayfarers`, `pocketdungeons` — and one mod,
`kamutotems`, that is a producer of a different kind. This section is what that
plurality requires and nothing more.

**There is deliberately no roster of which mod plays which role.** That list is
derivable (`grep -rl suite_items */` for producers, `grep -rl SuiteItems */` for
consumers) and a hand-maintained one rots: `DESIGN.md` §1's suite table is
already wrong by seven mods. Roles are contracts a mod satisfies, not labels it
is assigned, and a mod may hold more than one.

### 12.1 The three roles

| Role | Publishes | Contract | Example |
|---|---|---|---|
| **Producer** | Item definitions, inert data | §11 | `wondrous`, `spiritwolves` |
| **Consumer** | Nothing; reads the index | §7 | `cobbleeconomy` |
| **Service** | Items that make *it* act when used | §12.5 | `kamutotems` |

The first two already have their contracts written; they simply were not named
as roles. The third is new, and is the one worth writing down while there is
exactly one example of it.

A **service item** is a producer item whose `custom_data` marker is also a live
API call. `kamutotems/INTEGRATION.md` describes one without using the term: a
quest scroll is an ordinary vanilla item, and kamutotems watches for the blob and
acts when a player uses it. That document opens by saying not to add a dependency
on kamutotems, because the API is an item id plus `minecraft:custom_data` and
both mods stay strangers — which is §6 of this document, arrived at
independently, for a different purpose, before this document existed. Two
independent arrivals is the strongest evidence available that the shape is right.

A service carries one obligation the other roles do not: **when the service mod
is absent, its items must be inert, not broken.** Nothing is watching for the
blob, so nothing happens. That falls out for free and must not be given away — no
service item may depend on a component the base item cannot carry alone.

### 12.2 `requires`

An optional field on a definition, naming a mod id:

```json
{ "item": "minecraft:paper", "name": "Kamu Errand", "requires": "kamutotems" }
```

A consumer **omits the definition from its index entirely** when that mod is not
loaded, checked via the loader (`FabricLoader.isModLoaded`) and never by touching
the other mod's classes — see the comment on `WondrousShop.available` for what
happens when that rule is broken.

Without this, a service item degrades into litter rather than an error: a dungeon
in an install with no kamutotems drops a quest scroll that will never do
anything, and a shop cheerfully sells it. Three lines in the loader, and far
cheaper to add before three consumers exist than after.

`requires` is for **service** items. A plain producer item must not use it — its
definition is self-contained by construction, and a `requires` there would
re-introduce the load-order coupling §2 exists to prevent.

### 12.3 The canonical loader

§7 says each consumer carries its own copy of the loader rather than sharing a
Gradle module, and that is still right: the mods are standalone builds with no
root aggregate. But §7 was written for one consumer. Three copies, written
independently, will disagree — and the failure mode is nasty, because a
definition that loads in the shop would silently fail in a dungeon, with no error
anywhere pointing at the disagreement.

> **`cobbleeconomy/fabric/src/main/java/cobbleeconomy/SuiteItems.java` is the
> canonical implementation.** A new consumer copies it verbatim, changing only
> the package declaration and the logger target. Nothing else.

It earns that status by having been tested: it survived a specification bug in §6
that would otherwise have rejected every published item on the server, and the
reasoning behind the marker rule is written into the file where the next person
to touch it will see it.

Copy, do not reimplement. Drift then shows up as a diff instead of as a bug, and
the next fix ports mechanically. Each copy carries a header line naming the
canonical source. This is the same discipline `giveOrDrop` and `ItemComponents`
already get informally; it is stated here because this file is ten times larger
and correspondingly easier to get subtly wrong.

### 12.4 Weighted tag selection

`cobbleeconomy` picks by id, decided once by an admin. `wayfarers` and
`pocketdungeons` both want the same runtime operation — *a random suite item
tagged `trinket` at tier 2, weighted* — and if they implement it separately they
will disagree about the edge cases, which are all content-balance decisions that
players feel without being able to name. So it is specified once, here.

**Weights live on the consumer's reference, never on the definition.**

```json
{ "gives": { "tag": "trinket", "tier": "tier2", "weight": 3 } }
```

A producing mod must not be able to set its own drop rate, for the same reason it
must not set its own price (§8.3): that is the server owner's decision, and a mod
that could raise its own appearance rate by shipping an update has a lever it
should not have.

The rules, in order:

1. **`tier` selects, `rarity` does not.** The `tierN` tag (§5) is the only input
   to selection. `rarity` is a price hint and nothing else. The two never
   interact, so there is no precedence question to get wrong.
2. **Tier falls back downward, never upward.** A tier-3 request against a pool
   holding only tier-1 and tier-2 yields tier-2. It never yields tier-4. Handing
   out something above the requested tier is the direction that breaks
   progression, and it breaks it silently.
3. **An empty result is not an error.** A tag matching nothing yields nothing:
   the wayfarer offers nothing from that line, the chest gets its vanilla
   fallback. It is a quieter encounter, not a failure. This is the ordinary case
   on an install with few mods and must never log at error level.
4. **Without replacement within one draw, with replacement across draws.** One
   chest does not contain the same trinket twice; the next chest may.
5. **Seeded per instance.** A dungeon's loot is rolled from the instance seed, so
   it does not reroll when the chunk unloads or the server restarts.
   `pocketdungeons` M5 persists instances, and loot that changes underneath a
   player mid-run is a bug they will report as item loss.

### 12.5 Service trigger surfaces

A service acts when it observes its own marker. There are two supported ways for
it to observe one, and a consumer picks whichever suits it.

**On use.** The player right-clicks the item. This is what
`kamutotems/INTEGRATION.md` documents today. The trigger is explicit, the owner
is unambiguous — it is the player who used it — and it costs the service nothing
beyond an existing `UseItemCallback`.

**On proximity.** The consumer places a `minecraft:marker` entity carrying the
blob, and the service fires when a player comes near. This is what a dungeon
wants: the generator places markers as part of a room template and the encounter
happens without the player ever handling an item. `pocketdungeons` M6 already
lists "marker-driven boss summons", so this is a convergence rather than a new
idea.

Two constraints on the proximity form:

- **A marker entity, not a custom block.** The suite is server-side only and
  vanilla clients install nothing (`DESIGN.md` §4 rule 6), so a new block registry
  entry is not available. `minecraft:marker` is vanilla, invisible, collisionless,
  persistent, holds arbitrary NBT, and travels inside a structure template exactly
  as a block does. A consumer using this form must confirm its stamper places
  template *entities*, not only blocks.
- **The scan needs a budget.** Markers × players every tick will not survive the
  scale `pocketdungeons` M7 is already planning for. Check on an interval, use
  squared distance, and consider only markers in loaded chunks — a dungeon nobody
  is inside must cost nothing.

Multiple markers in one room is the point, not an edge case: four of them is a
four-boss fight, placed by the generator, with no new concept anywhere.

### 12.6 Decision — a proximity-spawned boss is owned by its marker

**Decided. An arena boss has no player owner.** It belongs to the marker that
summoned it, and its lifetime is the marker's lifetime.

The alternatives were party-owned (fits `pocketdungeons` M4.5, but `kamutotems`
cannot learn what a party is without a dependency) and first-to-enter (cheapest,
but inherits the disconnect bug below wholesale). Both were rejected. A dungeon
boss is a property of the room; the moment it belongs to a person, every question
that person's connection state raises becomes a boss-lifecycle question.

#### The rule that makes it work

> **An arena boss lives exactly as long as its marker entity exists.**

This is not a metaphor about ownership — it is the actual cleanup mechanism, and
it is why this option needs no coordination between the two mods.
`pocketdungeons` tears an instance down by clearing its entities, which removes
the marker along with everything else. `kamutotems` sees the marker is gone and
despawns the boss. Neither mod calls the other, neither knows the other exists,
and the teardown-mid-fight problem solves itself rather than needing a protocol.

It also answers the questions a player-owned boss cannot: the summoning player
disconnecting does nothing, because they were never the owner; a party wiping and
re-entering finds the boss still there, because the room still exists; and a
boss cannot outlive the dungeon it was placed in.

#### What this costs in `kamutotems`

Less than first estimated. `Boss.owner` is already a bare `UUID` field, not a
player reference, so an arena boss stores its marker's UUID there and the type
never changes. The daily-free-tier and sigil-counter paths need **no** changes at
all: `FREE_CLAIMS` and `SIGIL_COUNTERS` are keyed by player, and an arena boss is
neither a free claim nor a sigil summon, so it simply never enters them.

The real touch points:

| Site | Change |
|---|---|
| `Boss` | An origin discriminator beside the existing `fromSigil` — player or arena |
| `BossHost.track` | Index into `BY_PLAYER` only for player-owned bosses; arena bosses go to `BY_ENTITY` plus a new marker index |
| `BossHost.hasActive` | Unchanged, and that is the point — it consults `BY_PLAYER`, so arena bosses no longer trip *"You already have an active boss"* and four markers spawn four bosses |
| `BossHost.onDisconnect` | Unchanged — it works through `BY_PLAYER`, so arena bosses are untouched by construction. The disconnect bug is fixed by not applying |
| `BossHost.onTick` | New: despawn any arena boss whose marker no longer exists |
| `BossHost.onDeath` | Rewards currently resolve through the owner; an arena boss has no player owner, so they must resolve to the killer or to players in range |
| The boss bar | Player-owned shows to the owner; arena must add and remove viewers by proximity. `removeBarAll` already exists, so the all-players concept is there |
| Startup sweep | `onEntityLoad` must re-link a persisted arena boss to its marker, since both survive a restart |

The reward path is the only genuinely new design work. Everything else is
narrowing an existing index or adding a tick check.

#### Still open

**Whether a marker re-arms.** A boss killed and a player leaving and returning
must not re-trigger the same marker, so a marker needs a spent flag — but a
dungeon instance that resets for a new party may want the opposite. This is
`pocketdungeons`' call, since it owns the marker.

---
# Suite Items — Implementation Plan

> **What this is.** A two-agent parallel build of `SUITE_ITEMS.md`, phase one:
> the loader, `shop.json` support, the admin browser, and definitions for two
> producing mods. Sized so two agents never touch the same file.
>
> **Scope boundary.** Phase one is *cobbleeconomy consumes, spiritwolves and
> wondrous produce.* Wayfarers tag-consumption (`SUITE_ITEMS.md` §8.2) and the
> `WondrousShop` retirement (§10.1) are **not** in this plan.

---

## 0. The two decisions that make this parallelisable

### 0.1 No new field on `ShopEntry`

A suite listing is stored in the **existing** `ShopEntry.itemId`, prefixed
`suite:` — precisely how `wondrous:` already works. `ShopConfig` translates at
the file boundary in both directions:

```
shop.json  "suite_item": "spiritwolves:spirit_stone"
   ↕
ShopEntry.itemId  "suite:spiritwolves:spirit_stone"
```

This buys three things. The JSON surface stays exactly as `SUITE_ITEMS.md` §8.1
specifies. The **`core` module and all of its tests are untouched**, so neither
agent enters `cobbleeconomy/core/`. And every consumer becomes a prefix branch
next to the `WondrousShop` branch that already exists at that line — six of them,
listed in A4, each two or three lines.

### 0.2 `SuiteItems` mirrors `WondrousShop` method-for-method

`WondrousShop.java` is the shape of the answer: `isWondrousItemId`, `idFrom`,
`available`, `baseItem`, `displayName`, `iconStack`, `deliver`. `SuiteItems`
exposes the same seven plus what the browser needs. Agent A is cloning a
reviewed file rather than inventing an architecture, and §10.1's eventual
deletion of `WondrousShop` becomes mechanical.

---

## 1. Step 0 — the seam, written before either agent starts

**Owner: me, not an agent.** Both agents must compile independently, so the
facade exists — with real signatures and stub bodies — before either is spawned.
Agent A fills the bodies in; agent B calls them as-is and must not edit them.

Two new files in `cobbleeconomy/fabric/src/main/java/cobbleeconomy/`:

```java
/** One entry from data/<namespace>/suite_items/<path>.json. */
public record SuiteItemDefinition(
        String id,            // "spiritwolves:spirit_stone"
        String namespace,     // "spiritwolves"
        String path,          // "spirit_stone"
        String item,          // "minecraft:echo_shard"
        String name,          // "Spirit Stone"
        List<String> tags,
        String rarity,        // may be null
        String components) {} // raw JSON, same convention as ShopEntry.components

/** One _source.json. Cosmetic; namespace-only fallback if absent. */
public record SuiteSource(
        String namespace, String name, String icon, String description) {}
```

```java
public final class SuiteItems {

    public static final String PREFIX = "suite:";

    // --- mirrors WondrousShop, for the six branch sites ---
    public static boolean isSuiteItemId(String itemId);
    public static String  idFrom(String itemId);
    public static boolean available();
    public static Optional<Item> baseItem(String id);
    public static Optional<Component> displayName(String id);
    public static Optional<ItemStack> iconStack(String id, HolderLookup.Provider registries);
    public static boolean deliver(ServerPlayer player, String id, int count,
                                  HolderLookup.Provider registries);

    // --- the index, for the browser ---
    public static Optional<SuiteItemDefinition> byId(String id);
    public static List<String> namespaces();               // sorted, only non-empty ones
    public static SuiteSource source(String namespace);    // never null; falls back
    public static List<SuiteItemDefinition> inNamespace(String namespace);
    public static List<String> tagsIn(String namespace);   // sorted, deduped
    public static List<SuiteItemDefinition> byTag(String tag);
    public static int size();
}
```

**Stub bodies:** empty `Optional`/`List`, `false`, `0`, and `source()` returning
a namespace-derived fallback. The stub must compile and must never throw — agent
B will run against it.

---

## 2. Agent A — loader and resolution

**Owns, exclusively:** `SuiteItems.java`, `SuiteItemDefinition.java`,
`SuiteSource.java`, `ShopConfig.java`, `ShopDisplay.java`, `ShopMenu.java`,
`ShopPurchase.java`, `AdminCommands.java`, `CobbleEconomyMod.java`.

**Must not touch:** `ShopAdminMenu.java`, anything under `core/`, anything
outside `cobbleeconomy/`.

### A1 — the loader

Implement `SuiteItems` against `SUITE_ITEMS.md` §4 and §7. A
`SimpleJsonResourceReloadListener` on the `suite_items` directory, registered
through `ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener`,
so the merged view of every loaded mod's `data/` arrives for free and `/reload`
rebuilds the index. **Do not walk mod jars** — that breaks the datapack override
path §4.1 requires.

Build two indexes (`id -> definition`, `tag -> [id]`) plus the source index.
Skip `_source.json` when indexing items.

Reject-and-log, never fail the reload, when an entry:

- is missing `item`, `name`, or `components`
- names an `item` that is not in `BuiltInRegistries.ITEM` (§4.4 — this is the
  vanilla-client guarantee, not a nicety)
- has no `minecraft:custom_data` containing `<namespace>.id` (§6)
- has a `components` block `ItemComponents.parse` cannot parse

Log one summary line at the end in the existing house style —
`ShopConfig.load`'s `"Loaded {} shop entries ({} rejected)"` is the model.

`available()` returns `size() > 0`. `iconStack` and `deliver` build through the
existing `ItemComponents.apply`; do not write a second component parser.

### A2 — `ShopConfig.readEntry` accepts `suite_item`

At [ShopConfig.java:105](cobbleeconomy/fabric/src/main/java/cobbleeconomy/ShopConfig.java#L105).
`item` and `suite_item` are mutually exclusive — reject the entry with a warning
if both or neither are present. On `suite_item`, store `SuiteItems.PREFIX + id`
into `itemId` and validate it resolves, in the same block and the same style as
the existing `WondrousShop` check at line 139. The comment there states the rule
to preserve: a typo is one startup warning, not a player whose money vanished.

### A3 — `ShopConfig.save` round-trips it

At [ShopConfig.java:170](cobbleeconomy/fabric/src/main/java/cobbleeconomy/ShopConfig.java#L170).
A prefixed `itemId` writes `"suite_item": "<id>"` **instead of** `"item"`. The
existing `components` line right below carries the comment explaining why this
matters: every admin command rebuilds the entry and calls `save()`, so anything
not written here is silently stripped by one `/cobbleeconomy shop setprice`.

### A4 — the six branch sites

Add a `SuiteItems` branch beside the existing `WondrousShop` branch at each. Do
not refactor the two into a shared abstraction — that is §10.1's job, and doing
it now collides with the migration.

| File | Line | What the branch returns |
|---|---|---|
| `ShopConfig.java` | 139 | validation (A2) |
| `ShopDisplay.java` | 26 | `displayName`, falling back to `prettyName(idFrom(...))` |
| `ShopMenu.java` | 279 | `iconStack`, then `baseItem`, then `BARRIER` |
| `ShopPurchase.java` | 34 | base item, for the capacity check |
| `ShopPurchase.java` | 62 | `deliver` — returns false ⇒ caller refunds |
| `AdminCommands.java` | 318 | the same validation as A2 |

### A5 — registration

Register the reload listener in `CobbleEconomyMod` during init. Note the comment
at [CobbleEconomyMod.java:71](cobbleeconomy/fabric/src/main/java/cobbleeconomy/CobbleEconomyMod.java#L71):
the catalog deliberately loads on `SERVER_STARTED`, not at init, because of the
load-order bug §3 of `DESIGN.md` describes. Datapack reload completes before
`SERVER_STARTED` fires, so the index is populated in time — **verify this and say
so in your report.** If it does not hold, report it rather than working around it.

**Do not** make `/reload` re-run `shopConfig.load`. That interaction is decided in
the stitch pass (§5.3).

### A6 — verify

`./gradlew build` in `cobbleeconomy/`. Report the log lines produced by a server
start with zero suite items present — the empty-index case is the one every
existing server will hit.

---

## 3. Agent B — browser and producer data

**Owns, exclusively:** `SuiteItemBrowser.java` (new), `ShopAdminMenu.java`, and
all new JSON under `spiritwolves/` and `wondrous/`.

**Must not touch:** `SuiteItems.java` or any file in agent A's list. Call the
facade as given; if a signature seems wrong, report it — do not edit it.

Against the stub the browser renders an empty state. That is correct and
expected; build for it.

### B1 — `SuiteItemBrowser.java`

`SUITE_ITEMS.md` §9. Three screens, sgui, `SimpleGui` + `GuiElementBuilder`.
`ShopAdminMenu.java` is the reference for every convention — `PAGE_SIZE` of 45,
ninth-row nav, the `button(...)` and `dim(...)` helpers, arrow/barrier slots.
Match it; an admin should not be able to tell the two apart.

1. **Source picker.** One slot per `SuiteItems.namespaces()`, icon and name from
   `SuiteItems.source(ns)`, item count in lore.
2. **Item grid.** `SuiteItems.inNamespace(ns)`, paged. Each slot is
   `SuiteItems.iconStack(...)` — the real stamped stack, which is the whole point
   (§9). Bottom row is one toggle chip per `SuiteItems.tagsIn(ns)`.
3. **Click adds, then edits** (§9.3). Build a `ShopEntry`, `catalog.put`,
   `ShopConfig.save`, then hand off to the existing per-entry editor. **Every
   mutation goes through the same `save` the chat commands use** — the header on
   `ShopAdminMenu` states this rule and the browser is bound by it.

Defaults for a new entry: key from the definition's `path`; quantity 1; category
from the first category-kind tag, else the source name; price from `rarity` via a
single table in one place (`common` 1 / `uncommon` 3 / `rare` 8 / `legendary` 16
diamonds is a fine first guess — it is a hint the owner immediately owns, §8.3).

**§9.4 is not optional.** The grid marks items already listed (green name,
checkmark lore) and clicking one opens its existing entry rather than creating a
duplicate. On key collision namespace-qualify — `spiritwolves.spirit_stone`, not
`spirit_stone_2`.

**§9.5:** an "Add all visible" button on the filtered grid.

### B2 — one button in `ShopAdminMenu`

In `openList`, slot 48, beside the existing "New listing" at 47. Opens the
browser. This is the **only** edit to that file — do not restructure it.

### B3 — producer definitions

Author the JSON. No Java, no build changes.

- `spiritwolves/src/main/resources/data/spiritwolves/suite_items/` — the Spirit
  Stone, plus any other component-marked item the mod stamps. Read the mod's
  source for the real `custom_data` shape; **do not invent the marker**, it must
  match byte-for-byte what the mod already writes and reads, or existing stacks
  stop matching (§6).
- `wondrous/.../data/wondrous/suite_items/` — one per wondrous item. `WondrousTag`
  and the item registry in `wondrous` are the source of truth for ids and base
  items. The marker wondrous already uses **is** the §6 marker.
- A `_source.json` for each.

Tag every entry from the three conventional kinds (§5). Every `item` must be a
vanilla registry id (§4.4).

### B4 — verify

`./gradlew build` in `cobbleeconomy/`. Confirm every JSON file parses and each
`item` is a real vanilla id. Report the full list of ids you published.

---

## 4. Isolation

Spawn both with `isolation: "worktree"`. They edit disjoint file sets, but two
agents in one working tree will still collide over Gradle's lock files under
`.gradle/` — which are tracked in this repo and already show as modified.

Step 0's facade must be committed before either agent forks, or they will each
fork a tree in which the other's dependency does not exist.

---

## 5. My stitch pass

### 5.1 Merge

A's worktree first (it owns the facade), then B's. The only expected conflict is
`SuiteItems.java` itself, where **A's implementation always wins** — if B has
modified it, that is a contract breach and B's version is discarded, not merged.

### 5.2 Contract audit

Re-check every `SuiteItems` call in B's browser against A's final signatures.
This is the seam, so it is where a cheap-agent build actually breaks: a
`List` that A made `Optional`, a null where B assumed a fallback.

### 5.3 The decisions I deliberately withheld

- **Does `/reload` re-validate `shop.json`?** If an admin adds a mod and reloads,
  a previously-rejected `suite_item` entry should come back — but only if the
  entry survived in the file, which §5.4 is about.
- **Rarity → price table placement.** B puts it somewhere; it likely belongs in
  `EconomySettings` so an owner can retune the import defaults.

### 5.4 The one real risk, and it has bitten this codebase before

`ShopConfig.save()` writes only what is in the live catalog. An entry rejected at
load is not in the catalog, so **the next save silently deletes it from the
file.** That is verbatim the load-order bug `DESIGN.md` §3 documents: the shop
loaded before `wondrous` initialised, dropped every `wondrous:` entry, and
rewrote `shop.json` without them.

Every `suite_item` entry has exactly this exposure, and `SUITE_ITEMS.md` §8.1
promises the opposite — *hidden and logged, not deleted*, because uninstalling a
mod for an evening must not destroy an hour of price tuning.

Neither agent is asked to solve this; A's A5 is explicitly told not to. I verify
it in the stitch pass and fix it there, most likely by having the catalog retain
rejected entries as disabled-and-unresolvable rather than dropping them — which
would fix the wondrous case at the same time.

### 5.5 End-to-end

Start a server with spiritwolves and wondrous present: browse, add, price, buy,
confirm the stamped stack is what lands in the inventory. Then remove
spiritwolves and restart, and confirm the listing hides rather than vanishing.

---

## 6. Deliberately out of scope

| Deferred | Why |
|---|---|
| Wayfarers tag consumption (§8.2) | Separate mod, separate loader copy; needs the format proven in one consumer first |
| `WondrousShop` retirement (§10.1) | Touches all six of A4's sites; do it as one clean commit afterwards, not tangled with the feature |
| `/cobbleeconomy shop import <tag>` (§8.3) | The browser's "add all visible" covers the same need from the GUI |
| Per-mod README updates | Documents shipped behaviour; write them once this lands |
