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
| 4. Mint and treasury | not started (waiting on owner answers) |
| 5. Earn and Renown | not started |
| 6. Stats and polish | not started |

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

| Command | What it does |
|---|---|
| `/balance`, `/bal` | Coin and Valor, with rank |
| `/bank all [denomination]` | Deposit every marked coin in the main 36 slots |
| `/bank <amount> [denomination]` | Deposit; Marks unless a denomination word follows |
| `/withdraw <amount> [denomination]` | Fewest coins for a Marks amount, or that many of one coin |
| `/pay <player> <amount> [denomination]` | Pay coin, online or offline |
| `/baltop [coin\|valor]` | Leaderboards |
| `/shop`, `/buy` | The shop; prices in coin, Valor or both |

Denomination words: `mark marks copper`, `crown crowns iron`, `sovereign sovereigns
gold sov`, `coin coins`.

Admin (op level 2): `/chartercurrency balance|set|add|remove`, and the shop editor
under `/chartercurrency shop`.

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
- A coin amount made only of Marks is shown as `5 Marks`, not `5 Marks (5 Marks)`.
  Every other amount shows the Marks total in brackets.
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
