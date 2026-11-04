# Charter Currency

The currency system for the Kingdoms server: Coin, Valor and Renown. Server-side
Fabric mod for Minecraft 26.2; vanilla clients install nothing. The build brief is
[SPEC.md](SPEC.md). This README is the operator guide and grows with each milestone;
it currently covers milestones 1 to 3 (the fork, Coin and Valor).

## Status

| Milestone | State |
|---|---|
| 1. Fork builds | done |
| 2. Coin | done |
| 3. Valor | done |
| 4. Mint and treasury | done (Charter wiring built against a stub of its api; see below) |
| 5. Earn and Renown | done |
| 6. Stats and polish | done |

## Coin and Valor

Coin is physical: three marked nuggets, one balance stored in Marks.

| Coin | Item | Face value |
|---|---|---|
| Copper Mark | copper nugget | 1 |
| Iron Crown | iron nugget | 8 |
| Gold Sovereign | gold nugget | 64 |

A coin is a nugget carrying `{"chartercurrency":{"coin":"<id>"}}` in custom_data. A
plain nugget, or a renamed one, is not coin. Valor has no item: it is a balance that
cannot be withdrawn or paid.

Players only ever read coin in Gold Sovereigns: `2,261 Sovereigns`, and a remainder as
a decimal cut to two places, `2,261.5 Sovereigns` (never rounded up). Marks stay the
unit in code, config, state files and the logs. One formatter, `Wallet.sovereigns`,
does it everywhere; a source scan in the core suite fails on any new player string
that names Marks or Crowns.

| Command | What it does |
|---|---|
| `/balance`, `/bal` | Coin and Valor, with rank |
| `/bank all [denomination]` | Deposit every marked coin in the main 36 slots |
| `/bank <sovereigns> [denomination]` | Deposit; whole Sovereigns unless a coin word follows |
| `/withdraw <sovereigns> [denomination]` | Fewest coins for that many Sovereigns, or that many of one coin |
| `/pay <player> <sovereigns> [denomination]` | Pay coin, online or offline |
| `/baltop [coin\|valor]` | Leaderboards |
| `/shop`, `/buy` | The shop; prices in coin, Valor or both |

A bare number is whole Gold Sovereigns (`CoinInput`, owner 2026-11-04): `/pay Bob 5`
pays 5 Sovereigns. A coin word still names its coin: `/pay Bob 3 marks` pays 3 Marks.
The same holds for `/treasury deposit|withdraw` and Charter's `/charter vault withdraw`
and book. The operator commands other mods script (`/chartercurrency set|add|remove`,
`/chartercurrency treasury add|remove|pay|give`) stay in Marks.

Denomination words: `mark marks copper`, `crown crowns iron`, `sovereign sovereigns
gold sov`, `coin coins` (the currency: read as Sovereigns).

Admin (op level 2): `/chartercurrency balance|set|add|remove`, and the shop editor
under `/chartercurrency shop` (including `shop restock`, which brings an existing shop
up to the starter stock).

## The Mint and kingdom treasuries

Coin enters the world only at a Mint. The usual Mint is any smithing table in the
same chunk as a **Bank**: a vault block a kingdom's Lord sets on their own land, one per
kingdom (ops hand them out with `/chartercurrency bank give [player]`; `bank list`,
`bank forget <kingdom>`). Right-clicking a Bank opens a chest menu with the player's
money, Bank all, Take out 1, 8 or 64 Sovereigns, the Mint's rules for today and the
shop; looking at a Bank or a Mint shows one line on the action bar. Breaking a Bank
drops it back as an item. SPEC section 8.0 has the rules.

An op can also bind any smithing table as a Mint: look at it and run
`/chartercurrency mint bind` (also `unbind`, `list`; sites are in `mints.json`).
Right-clicking a bound table never opens the smithing screen:

- holding gold ingots strikes Gold Sovereigns from them (1 ingot per coin up to the
  daily soft cap, more past it); sneaking strikes from every stack of gold;
- holding copper or iron ingots strikes nothing: "The Mint strikes gold only. Copper and
  iron are not money here." Mob farms make copper and iron without limit (drowned, iron
  golems), so from 2026-11-03 only gold is money at the Mint (`Mint.STRUCK`). Copper
  Marks and Iron Crowns still circulate as change, from withdrawals and payouts;
- an empty hand, or anything else, shows today's rate. `/mint` shows it anywhere.

The soft cap is counted in gold ingots per player per day (owner, 2026-11-03: gold may
be farmed, since it is the only thing farmed into money and has other sinks). One gold
ingot strikes 10 Sovereigns (`Mint.COINS_PER_STRIKE`, fixed in code). The first 320
ingots strike at that rate; past that 10 Sovereigns cost 2 ingots, then 4 after another
320, doubling every 320 to at most 64. Coins that do not fit in the player's pockets go
straight into their bank. There is no hard stop and nothing carries
over. `softCapIngots` and `falloffEvery` live in `settings.json` under `mint` (an old
file with the band keys is rewritten at load); `timezone` there sets when the day turns
over.

Coin is never melted: no recipe matches while a marked coin is in its input
(`mixin/RecipeMeltMixin`), so the vanilla nine nuggets recipe cannot turn 9 coins
into an ingot. The way back to metal is the shop's gold ingot at 10 Sovereigns, the
rate it was struck at. Starter shop coin prices are ten times what they were at one
Sovereign an ingot; `/chartercurrency shop restock` reprices a live shop's untouched
starter listings and adds the ingot.

The Mint tax is fixed in code and cannot be configured: every fifth coin struck, per
player and per denomination, goes to the player's kingdom treasury; the player keeps
the other four. The counter belongs to the player and survives a change of kingdom.
The soft cap counts taxed coins. A player in no kingdom pays the same share and it is
destroyed. If the kingdom lookup fails, the strike is refused and nothing is taken.

| Command | Who | What |
|---|---|---|
| `/treasury [kingdom]` | Members, ops | Balance and recent movements |
| `/treasury deposit <amount\|all> [denomination]` | Members | Same rules as `/bank` |
| `/treasury withdraw <amount> [denomination] [kingdom]` | The Lord, ops | Same rules as `/withdraw` |
| `/treasury ledger [coin\|renown] [page] [kingdom]` | Members, ops | Full history |

The kingdom name is optional and for ops (who belong to no kingdom). Treasuries are
kept per organization id in `kingdoms_state.json`, in Marks, with the last 500 ledger
lines each (older ones stay in `transactions.log`).

Op commands: `/chartercurrency mint bind|unbind|list`,
`/chartercurrency treasury add|remove <kingdom> <amount>`,
`/chartercurrency treasury pay <kingdom> <player> <amount> [denomination]` (into the
player's bank) and `give` (as coin; the player must be online and have room).

### Kingdoms: Charter, or the manual stand-in

Charter is the source of truth for which kingdoms exist, who is in them and who the
Lord is. Charter finds this mod through the Fabric entrypoint key `charter:payment`
(`CharterPaymentProvider`), takes its payment port (balance, charge, credit, in Marks,
keyed by organization id) and binds it with a kingdom view. From then on `kingdoms.json`
is ignored. Neither mod needs the other to load.

Without Charter, ops use the manual source (stored in `kingdoms.json`):
`/chartercurrency kingdom create <id> [name]`, `remove <id>`, `set <player>
<kingdom|none>`, `lord <kingdom> <player>`, `list`. These refuse while Charter is
answering.

## Earning Valor and Renown

Every award goes through one console or op command:

```
/chartercurrency earn <player> <source> [level] [run]
```

`source` is a key in `earn.json`; `level` feeds the source's `base + perLevel * level`;
`run` names one shared activity. Pocket Dungeons reaches it through its existing config:

```
"payoutCommand": "chartercurrency earn %player% dungeon %level%"
```

Events and build-offs are the same command typed by an admin
(`chartercurrency earn Steve event`, `chartercurrency earn Steve buildoff_1st`).

Valor: a soft daily cap per source halves further awards (rounded down, minimum 1; unused
cap carries over for up to two extra days); an optional hard cap per source stops them.
Renown is the Valor paid times the source's multiplier, credited to the player's kingdom
(or the Commons when they are in none). Per player, 120 Renown a day at most; a kingdom
gets one credit per run (with a `run` id: one per id; without: one per source and level
inside 30 seconds), raised to the largest award in the run. A player earns no Renown
for a kingdom until they have been in it 7 days; Valor is unaffected. All of these are in
`earn.json`, `0` meaning no cap.

| Command | Who | What |
|---|---|---|
| `/renown` | Anyone | Your kingdom's Renown, lifetime earned, and what it bought |
| `/renown catalog` | Anyone | The catalogue with costs and whether each is bought or affordable |
| `/renown buy <key>` | The Lord, ops | Asks first, then spends and runs the entry's commands |
| `/renown ledger [page]` | Members, ops | Credits and spends |
| `/renown top` | Anyone | Kingdoms by lifetime Renown |
| `/chartercurrency renown add\|remove <kingdom\|commons> <amount>` | Ops | Adjust; an add does not count as earned |

`renown_catalog.json` ships the five charter tiers (Hamlet 300, Village 900, Town 2000,
City 4000, Realm 8000, each needing the one before) with a broadcast command only; what
a tier does belongs to Charter. Placeholders in commands: `%kingdom%`, `%kingdom_name%`,
`%buyer%`. A command that fails is logged at ERROR and the purchase stands.

## Setting up a server

1. Drop `chartercurrency-0.1.0.jar` in `mods/` next to Fabric API (and Charter, if used).
   Start once; the config folder `config/chartercurrency/` fills with defaults.
2. Kingdoms. With Charter installed there is nothing to do: it supplies kingdoms,
   members and Lords. Without it, as an op: `/chartercurrency kingdom create northreach
   Northreach`, `kingdom set <player> northreach`, `kingdom lord northreach <player>`.
3. Mints. Look at a smithing table and run `/chartercurrency mint bind`. Players
   right-click it with ingots.
4. Pocket Dungeons: set its `payoutCommand` to
   `chartercurrency earn %player% dungeon %level%`.
5. Retune `shop.json` (every starter price is a placeholder) and `earn.json`. The
   starter has 104 listings on eight shelves (`core/ShopStarter`, SPEC 7.1). A server
   with an older `shop.json` gets the new stock with `/chartercurrency shop restock`:
   it adds what is missing and reprices only listings still at the first starter
   price, so prices you set are kept. Nothing gold is ever sold (it would loop
   through the Mint).
   `/chartercurrency reload` applies settings, earn, the Renown catalogue and the shop;
   `currencies.json` needs a restart.
6. `/chartercurrency stats` shows the coin supply (banked plus treasuries), minting,
   Mint tax collected and destroyed, Valor by source, and each kingdom's Renown and
   treasury. Coin still carried in inventories is not counted.

## Config files (`config/chartercurrency/`)

| File | Holds |
|---|---|
| `currencies.json` | Coin denominations (items, face values, words) and Valor. The coin marker is not configurable |
| `settings.json` | Leaderboards, login snapshot, logging, `timezone`, `mint` soft cap numbers, `coins.models` (coin looks from Foundry's pack; off until that pack is live), `shop.confirmAt` per currency. There is no Mint tax setting: it is fixed at one coin in five |
| `earn.json` | Earn sources and the Renown rules |
| `renown_catalog.json` | What Renown buys |
| `shop.json` | The shop; prices in `coin`, `valor` or both |
| `kingdoms.json` | The manual kingdom source only; ignored while Charter answers |
| `mints.json` | Bound Mint blocks |
| `accounts.json` | Player coin and Valor. Refuses to load over a bad file |
| `players.json` | Mint state and tax counters, earn caps, probation clocks, daily totals. Refuses to load over a bad file |
| `kingdoms_state.json` | Per organization: treasury, Renown, purchases, last 500 ledger lines. Refuses to load over a bad file |
| `transactions.log` | Append-only audit trail of every movement |
| `analytics/events-YYYY-MM-DD.jsonl` | The same movements as JSON lines, one file a day (`analytics.enabled` in `settings.json`, default on) |
| `analytics/snapshots.jsonl` | One JSON line a day: supply, top ten, concentration, the day's flows, Valor, treasuries, shop sales. `/chartercurrency stats history [days]` shows them |

All state files are written atomically on a 15 second debounce and at clean shutdown.

## Building

JDK 25. `./gradlew build` writes `fabric/build/libs/chartercurrency-0.1.0.jar`.
`./gradlew :core:coreTest` runs the Minecraft-free test suite.

## Decisions made while building

Places where the spec was silent or ambiguous, and the reading taken.

- Dialogs and their mixin are kept (SPEC section 3.3 allows it): the shop editor's
  text input, delete confirmation and purchase confirmation are dialog screens, and
  dropping them would mean rebuilding that UI.
- Suite items (SuiteItems, SuiteItemBrowser) are kept; the spec did not list them for
  removal. Their default price currency is now Valor.
- No `REVIEW.md` was supplied, so none is in this folder.
- Every coin amount is shown in Gold Sovereigns (owner, 2026-11-04; this replaces the
  older Marks totals in brackets and the coin by coin breakdown). A shop listing reads: `Price`, `You have`, `Click: buy 16`,
  `Shift-click: buy all you can afford`, and `Asks you to confirm first` when a
  purchase needs confirming.
- `/bank <amount> <denomination>` takes exactly that many of that coin and touches no
  other. `/bank <amount>` (Marks) picks coins with `Wallet.planDeposit`, taking the
  smallest overpayment and returning change as coins. Among plans of equal total,
  fewer coins removed wins.
- `Wallet.planDeposit` also has a three argument form taking face values, because the
  two argument form in the spec cannot know them; the two argument form uses 1, 8, 64.
- `/withdraw all` from the fork is gone; amounts are explicit.
- Free space for coin counts a partial stack only when it merges with a fresh coin
  (same item, identical components). Counting and removing match the marker only.
- `Currency.payable` is enforced in core (`TxStatus.NOT_PAYABLE`); `withdrawable` is
  enforced by the command layer, because admin `remove` must work on Valor.
- The fork's generic multi-currency core tests now run against a test-only payable
  second currency (`gem`), because Valor is deliberately not payable. Coin replaced
  cobblestone in those tests.

Milestone 5 decisions:

- Soft cap: an award is paid in full while any pool is left, even if it crosses zero
  (the literal reading of "once a player has earned the soft cap"); then halved.
- Hard cap: an award that would cross it is clipped to the room left.
- A Renown top-up counts against the topping player's daily Renown cap.
- Probation, the player cap and Commons routing are decided before a run key is
  claimed, so an ineligible first settler cannot block an eligible teammate.
- Run de-duplication applies to the Commons too. Run claims live in memory only.
- `commons` is a reserved id; a manual kingdom cannot be created with it.
- If the kingdom lookup fails during an award, nothing is awarded.
- Ledger lines for Renown purchases carry the catalogue key as the note.

Milestone 4 decisions:

- Charter's api is not yet on a branch I could read, so `charter-api-stub/` holds a
  compile-time copy of the three interfaces from the integration contract
  (`KingdomView`, `PaymentPort`, `PaymentProvider`). It is `compileOnly` and never
  shipped. When Charter publishes its real api, check the signatures match and delete
  the stub module. The Charter wiring has not been run against real Charter.
- Held ingots: a plain click offers the held stack's count; the ingots removed come from
  the smallest matching stacks across the main 36 slots, by item only.
- A treasury balance counts in the coin supply (stats, milestone 6).
- Removing a manual kingdom keeps its treasury balance and ledger.
- The Renown and treasury ledger is one list per organization with a currency tag.
- `/treasury ledger` takes its options as free words: `coin` or `renown`, a page
  number, and (ops) a kingdom, in any order.

## Section 15 checks (26.2 jar, javap)

Verified at signature level against `minecraft-merged.jar` and the Fabric API jar:

- `Items.COPPER_NUGGET`, `IRON_NUGGET`, `GOLD_NUGGET`, `COPPER_INGOT`, `SMITHING_TABLE`
  exist.
- `DataComponents.CUSTOM_DATA`, `ITEM_NAME`, `LORE`; `CustomData.copyTag()`,
  `CompoundTag.contains`, `getCompoundOrEmpty`, `getString` (returns `Optional`).
- `UseBlockCallback.interact(...)` returns `InteractionResult` (`CONSUME` and `SUCCESS`
  exist).
- `Inventory.getContainerSize/getItem/setItem/add`, `AbstractContainerMenu.broadcastChanges`,
  `PlayerList.getPlayerByName`, `CommandSourceStack.getOnlinePlayerNames`,
  `ItemStack.isSameItemSameComponents`, `Player.drop`.

Not verifiable without a live server: runtime behaviour of those calls, that the
starter shop's component JSON (`stored_enchantments`, `trim`, `item_name`) parses
under 26.2's codecs (an entry that fails is skipped with one warning at load), and the
sounds and chat layout.
