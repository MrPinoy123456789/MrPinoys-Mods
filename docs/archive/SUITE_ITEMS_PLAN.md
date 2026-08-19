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
