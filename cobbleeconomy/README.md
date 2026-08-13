# Cobble Economy

**Cobblestone is money. Diamonds are better money.**

A server-side Fabric economy for Minecraft 26.2. Vanilla clients need nothing
installed — no custom items, no synced registry entries, no resource pack.

Two currencies, both ordinary vanilla items:

| Currency | Item | Role |
|---|---|---|
| Cobblestone | `minecraft:cobblestone` | Common. Abundant on purpose. |
| Diamond | `minecraft:diamond` | Premium. Scarce on purpose. |

The balances are **completely independent**. There is no exchange rate, no
conversion command, and no field anywhere in the code that says what a diamond is
worth in cobblestone. What things cost is decided by your shop prices and by your
players — never by the mod.

## Commands

| Command | What it does |
|---|---|
| `/balance`, `/bal` | Every balance, with your rank in each |
| `/balance <currency>` | One balance |
| `/bank` | Balance panel and command list |
| `/bank all` | Deposit **every** currency you're carrying |
| `/bank all <currency>` | Deposit all of one |
| `/bank <amount> <currency>` | Deposit a specific amount |
| `/withdraw <amount\|all> <currency>` | Take physical items back out |
| `/pay <player> <amount> <currency>` | Send banked currency, online or offline |
| `/shop` | What the server sells |
| `/buy <item>` | Purchase, paid from your bank |
| `/buy <item> confirm` | Skip the "are you sure?" on an expensive one |
| `/baltop` | Top 3 in every currency |
| `/baltop <currency>` | Full paginated board |
| `/baltop me` | Your rank in each currency |

Currency names accept aliases: `cobble`, `cobblestone`, `stone`, `diamond`,
`diamonds`, `dia`. Everything tab-completes.

### Admin (permission level 2)

```
/cobbleeconomy balance <player>
/cobbleeconomy set|add|remove <player> <amount> <currency>

/cobbleeconomy shop edit
/cobbleeconomy shop list
/cobbleeconomy shop reload
/cobbleeconomy shop add <key> <item> <quantity> <price> <currency>
/cobbleeconomy shop remove <key>
/cobbleeconomy shop setprice <key> <price> <currency>
/cobbleeconomy shop setquantity <key> <quantity>
/cobbleeconomy shop enable|disable <key>
```

`add` and `remove` are the same code path players use, so an admin can't push an
account negative either. Every admin action is logged with the operator's name.

### The shop editor

`/cobbleeconomy shop edit` is `shop.json` as a chest menu — the whole catalog, with
the item you are pricing in your hand.

| | |
|---|---|
| **New listing** | Sells whatever you're holding. The stack size becomes the quantity |
| **Click a listing** | Opens its editor |
| **Shift-click a listing** | Puts it on sale / hides it |
| **Item** | Click to repoint the listing at what you're holding |
| **Key** | Anvil text field — what players type after `/buy` |
| **Quantity** | Left/right ±1, shift ±16, middle-click to type |
| **Price** | One button per currency. Left/right ±1, shift ±100, middle-click to type. Set one to 0 to drop it; set two to charge in both |
| **Category** | Anvil text field |
| **Delete** | Shift-click to confirm |

Every change is written to `config/cobbleeconomy/shop.json` immediately — there is
no save button and no unsaved state to lose. **Reload from disk** is for after you
have hand-edited the file outside the game.

It owns no rules of its own: each action builds a shop entry and calls the same save
the chat commands above call, so the two cannot drift apart. The chat commands are
all still there, and are what console has to use.

## Building

Requires **JDK 25** — Minecraft 26.2 targets Java 25, not 21. The Gradle wrapper is
included.

```
./gradlew build
```

The jar lands in `fabric/build/libs/cobbleeconomy-0.1.0.jar`. Drop it in your
server's `mods` folder alongside Fabric API. Ignore the `-sources` jar.

Run the economy tests without Minecraft:

```
./gradlew :core:coreTest
```

Versions in `gradle.properties` came from the FabricMC example mod's 26.2 branch and
match the other mods in this project. That file is the only one that needs touching
on an update.

Note the Loom plugin version is applied in `settings.gradle.kts`, not in
`fabric/build.gradle.kts`. A `plugins {}` block in a Kotlin build script is a
restricted scope compiled before the project exists, so `property("loom_version")`
cannot resolve there — the official example mod gets away with it only because it's
Groovy DSL. Settings scripts can read `gradle.properties` via the `by settings`
delegate, so the version still lives in one place.

## Configuration

Everything lives in `config/cobbleeconomy/`.

**`accounts.json`** — balances, keyed by UUID. Written atomically (temp file, then
rename) on a 15-second debounce and on clean shutdown.

```json
{ "accounts": { "uuid-1": { "balances": { "cobblestone": 12480, "diamond": 37 } } } }
```

**`shop.json`** — prices. Written with a starter catalog on first run; every price in
it is a guess and is meant to be changed. Two accepted forms:

```json
{
  "shop": {
    "ice":    { "item": "minecraft:ice", "quantity": 64,
                "price": 640, "currency": "cobblestone",
                "category": "Building Materials" },

    "elytra": { "item": "minecraft:elytra", "quantity": 1,
                "price": { "cobblestone": 500, "diamond": 1 },
                "category": "Premium" }
  }
}
```

The second form — a price in two currencies at once — works today. `/buy` charges
both or neither.

An entry's `item` may also name a [Wondrous Items](../wondrous) id instead of a
registry item, prefixed with `wondrous:`:

```json
"big_hole_shovel": { "item": "wondrous:big_hole_shovel", "quantity": 1,
                      "price": 12, "currency": "diamond", "category": "Rare Resources" }
```

The stack delivered comes from the wondrous mod's own `createStack`, fully tagged
and decorated — not a plain vanilla item. If the wondrous mod isn't installed, or
the id is unknown, the entry is skipped at shop-load with one warning; it is never
sold as a broken item. `wondrous` is a `suggests`, not a `depends` — cobbleeconomy
starts and the rest of the shop works with it absent.

**`settings.json`**

```json
{
  "leaderboards": {
    "enabled": true,
    "showOnLogin": true,
    "loginTopCount": 3,
    "loginDelayTicks": 40,
    "showRankOnBalance": true
  },
  "logging": { "transactionFile": true },
  "shop": {
    "confirmAt": { "cobblestone": 1000, "diamond": 1 }
  }
}
```

`showOnLogin: false` keeps `/baltop` working but stops the join snapshot.

`shop.confirmAt` is the "are you sure?" threshold, per currency. A purchase whose
total cost in a currency reaches its number asks first — an extra screen in the shop
GUI, a `[ Confirm purchase ]` button after `/buy`. The diamond default is `1`, so
every diamond purchase confirms: diamonds are premium, there is no way to buy them
back, and there is no such thing as a cheap one. Cobblestone is recoverable by
mining, so only a large spend asks.

A shift-click bulk buy is priced *before* anything is charged and confirms against
the whole total, not one lot — otherwise a shift-click could spend a stack of
diamonds having only ever confirmed the first.

Set a currency to `0`, or leave it out, and it never asks. `"confirmAt": {}` turns
the prompt off entirely. The block is replaced wholesale rather than merged over the
defaults, so turning it off actually turns it off.

**`names.json`** — UUID to last-seen username, so `/pay Steve` works while Steve is
offline. **`transactions.log`** — append-only audit trail.

## How it's put together

```
commands  ──►  EconomyService     ──►  AccountStore
               LeaderboardService
               Shop
(fabric)       (core module)           (fabric)
```

The `core` module has **no dependency on Minecraft** — its `build.gradle.kts` is
deliberately empty, so a stray `import net.minecraft.*` fails the build. That's not
tidiness for its own sake. The money rules, the ranking rules, and the purchase
transaction are all in there, which means all three are under test in a suite that
runs in about a second with no game, no network, and no test framework.

**200 assertions, currently all passing.** Including the ones that actually matter:
eight threads racing to drain one account, eight threads buying from a balance that
only covers ten purchases, and the delivery-failed rollback — a case that's nearly
impossible to provoke deliberately inside a running server, but is a one-line
callback here.

### Where the money can go wrong, and what stops it

**Deposits take items first, then credit the bank.** If the server dies between the
two, a player has lost cobblestone. That's bad, but the reverse ordering loses
cobblestone *into existence*, and only one of those two failure modes is worth
farming. The same logic drives the debounced writes: a crash costs up to 15 seconds
of transactions, and since deposits are the common case, a crash makes the economy
slightly *smaller*. An economy that fails toward less currency can't be farmed.

**Withdrawals and purchases check space before touching the balance.** If it won't
fit, the whole thing is cancelled — never partially executed. `Wallet.planRemoval`
returns `null` rather than a short plan when an inventory is short, so there is no
partial removal for a careless caller to apply.

**Nothing is ever deleted.** In the unreachable case where an item won't fit after
capacity was already checked, it drops at the player's feet and the server log says
so — the same `giveOrDrop` rule the other mods in this set use.

**`accounts.json` refuses to be overwritten if it can't be read.** The server won't
start. Loading an empty economy instead would look fine right up until the next flush
wiped every balance on the server. A bad `shop.json`, by contrast, just gives you an
empty shop — nobody's money is at stake.

### Inventory slots

Only the main 36 slots (hotbar + three rows) are read. That range has been stable far
longer than the surrounding API, whereas armour and offhand indices have moved between
versions — and a mod that trusts `getContainerSize()` across two versions is a mod
that eventually inserts cobblestone into a helmet slot. The cost is that a stack in
your offhand isn't seen by `/bank all`. The failure in the other direction is a dupe.

## Currency sinks

Cobblestone generators are money printers. That's fine — it's the whole premise — but
it means **you are responsible for the sinks.** `/buy` is the first one: money spent
in the shop is destroyed, not moved to an admin account. If your total banked supply
only ever climbs, add more things to spend it on.

`/baltop` and the startup log both print total supply per currency, which is the
number to watch.

## For other mods

```java
EconomyService economy = EconomyApi.economy();
economy.withdraw(player.getUUID(), CurrencyRegistry.DIAMOND, 5);

int rank = EconomyApi.leaderboard().rankOf(player.getUUID(), CurrencyRegistry.COBBLESTONE);
```

`EconomyApi.currencies()` and `EconomyApi.shop()` are also exposed. All four return
null if this mod isn't loaded, so declare it as `suggests` rather than `depends` if
you want to degrade gracefully. Don't sort balances yourself — use the leaderboard
service, or your rankings will disagree with `/baltop`.

## Before you trust it on a live server

The `core` module is compiled and tested here. **The `fabric` module has not been
compiled** — Loom needs `maven.fabricmc.net`, which wasn't reachable from where this
was written. It parses cleanly with zero syntax errors, but expect to fix a few
signatures on first build.

Confirmed against the 26.2 jar:

- `net.minecraft.resources.Identifier` — this is what 26.1 renamed `ResourceLocation`
  to. Mojang adopted the name Yarn had used for years. Every tutorial written before
  26.1 uses the old name. `Identifier.tryParse(String)` survived the rename.
- `Registry.getOptional(Identifier)` returns `Optional<T>` — used for item lookup.
- Permissions were rebuilt. `CommandSourceStack.hasPermission(int)` is gone. The
  replacement is `Commands.hasPermission(PermissionCheck)`, which builds the
  `Predicate<CommandSourceStack>` that `.requires()` wants, with level constants on
  `Commands` itself: `LEVEL_ALL`, `LEVEL_MODERATORS`, `LEVEL_GAMEMASTERS`,
  `LEVEL_ADMINS`, `LEVEL_OWNERS`. `LEVEL_GAMEMASTERS` is the old op level 2.

Still unverified — these are reached at runtime rather than compile time, so a clean
build does not prove them:

- `Inventory.getContainerSize()` / `getItem(int)` / `setItem(int, ItemStack)`
- `player.containerMenu.broadcastChanges()`
- `MinecraftServer.getTickCount()`
- `CommandSourceStack.getOnlinePlayerNames()` and `PlayerList.getPlayerByName(String)`

`javap` against the jar Loom downloaded settles any of these quickly:

```bash
MC=~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar
javap -cp "$MC" net.minecraft.commands.CommandSourceStack
```

### Finer-grained permissions, later

`/cobbleeconomy` currently sits behind one gate. Because 26.x permissions are real
objects rather than integers, the subcommands could eventually be split — letting a
trusted builder run `shop setprice` without being handed full op. Nothing in the
command layer needs restructuring for that; each `.then(...)` branch can carry its
own `.requires(...)`.

Then test in this order, because it's roughly cheapest-to-catch first: `/bank all`
with a mixed inventory, `/withdraw` with a nearly full inventory, `/pay` to an offline
player, `/buy` with a full inventory, and a restart to confirm balances reload.

A first release upgraded from the single-balance format migrates automatically — the
old unnamed number was always cobblestone, so it reads as cobblestone and is rewritten
in the new shape on the next flush.
