# Charter Currency: Build Spec

> **For the agent building this mod.** This document is the whole brief. Build
> what is here and nothing else; section 2 lists what is deliberately out of
> scope. Where this spec and `REVIEW.md` disagree, this spec wins (`REVIEW.md` is
> background on why the design looks the way it does).
>
> **House rules.** Read `/CLAUDE.md` first. In particular: never write an em dash
> or a double hyphen as punctuation, in code comments, strings, docs or commit
> messages.
>
> **Status:** approved design, 2026-10-31. Not implemented.

---

## 1. What it is

A server-side Fabric mod for the Kingdoms (charter) server: the currency system.
Minecraft 26.2, Fabric, JDK 25, Mojang mappings. **Vanilla clients install
nothing**: no custom items, no resource pack, no synced registries.

Three currencies:

| | Coin | Valor | Renown |
|---|---|---|---|
| Held by | Players | Players | Kingdoms |
| Physical | Yes: three coin items | No: balance only | No: ledger only |
| Tradeable | Yes (`/pay`, hand to hand) | No | No (spend-only) |
| Earned by | Minting metal at a Mint; trade | Dungeons and events | The same awards as Valor, at the same moment |
| Spent on | The shop; player trade | The shop (Valor listings) | The Renown catalogue (charter tiers and anything else the owner lists) |

Kingdoms also hold **coin** in a kingdom treasury, filled by the Mint tax
(section 8.3). That is the same coin currency, not a fourth one.

**Charter**, the server's existing kingdom system, keeps deciding the rights of
players in each kingdom, including who may spend the treasury. This mod holds the
money; Charter holds the rights (section 4.4).

Mod id `chartercurrency`, display name **Charter Currency**, Java package
`chartercurrency`, config folder `config/chartercurrency/`.

## 2. Scope

**In scope:** the three currencies; the bank; the Mint and its tax; kingdom
treasuries; the earning endpoint and
its anti-abuse rules; the shop (coin and Valor prices); the Renown catalogue;
leaderboards; logs and stats.

**Out of scope, do not build:** land claims or protection; kingdom creation,
membership management, roles or ranks; plots; tier perks or effects (the Renown
catalogue only runs configured commands; what they do is someone else's job);
war; upkeep; seasons; any change to another mod except the optional one in
section 13.

**Kingdoms already exist on the server** and are created only by admins. This
mod does not manage them. It only needs to know which kingdom a player belongs
to (section 6.4).

## 3. Starting point: fork Cobble Economy

`cobbleeconomy/` in this repo is a working two-currency bank and shop for 26.2
with the same architecture this mod needs. Do not depend on it; **copy it**,
then change it. The suite prefers a few duplicated files over a shared library
(`docs/DESIGN.md` §2). Cobble Economy itself must not be modified.

1. Copy `cobbleeconomy/` to `chartercurrency/` (keep this SPEC, README and
   REVIEW), rename packages `cobbleeconomy` to `chartercurrency`, the mod id, the
   mixin config, the main class (`CharterCurrencyMod`), the API class, the config
   folder and the advancement namespace.
2. Get the renamed core test suite (`./gradlew :core:coreTest`) and the build
   (`./gradlew build`) passing **before** changing behaviour. Commit that as its
   own step.
3. Remove what this server does not use: `WondrousShop` and the `wondrous` api
   dependency, the `thingy` suggests entry. Keep the `components` block on shop
   listings (it is how a listing sells a stamped item). Keep the dialogs and
   their mixin only if you keep the dialog shop UI; otherwise drop both.
4. Read `cobbleeconomy/README.md` "Before you trust it on a live server" for the
   26.2 API notes already verified (`Identifier`, the permissions rebuild,
   `Commands.hasPermission`).

Keep the fork's rules, which are tested and correct: deposits take items before
crediting; withdrawals and purchases check space before charging; nothing is ever
partially executed; `accounts.json` refuses to load over a bad file; atomic
debounced writes; `giveOrDrop` for anything that cannot fit.

`core/` must stay free of Minecraft (its `build.gradle.kts` has no dependencies,
so a stray import fails the build). All money, mint and earn arithmetic lives in
`core/` under test.

## 4. Currencies

### 4.1 Coin: one currency, three denominations

| Denomination | Base item | Marker (`custom_data`) | Face value |
|---|---|---|---|
| Copper Mark | `minecraft:copper_nugget` | `{"chartercurrency":{"coin":"copper"}}` | 1 |
| Iron Crown | `minecraft:iron_nugget` | `{"chartercurrency":{"coin":"iron"}}` | 8 |
| Gold Sovereign | `minecraft:gold_nugget` | `{"chartercurrency":{"coin":"gold"}}` | 64 |

- **One balance, stored in Marks.** A Sovereign is 64 Marks; a Crown is 8.
- **Identity is the marker, never the item or the name.** Counting, removing and
  free-space checks only match stacks whose `custom_data` contains the marker
  (match the marker key, not the whole component patch, so extra components added
  later do not break recognition). A plain nugget is not coin; an anvil-renamed
  nugget is not coin.
- Every coin the mod creates carries the marker plus `minecraft:item_name`
  (`Copper Mark`, `Iron Crown`, `Gold Sovereign`) and one lore line
  (`Charter coin, worth 8 Marks`).
- The three are defined in `currencies.json` (section 9), not hard-coded, but the
  defaults above are what ships.
- Display: `3 Sovereigns, 2 Crowns, 5 Marks (213 Marks)`. Always show the Marks
  total. Zero parts are omitted (`2 Crowns (16 Marks)`).

Fixed exchange between denominations is correct here even though Cobble Economy
forbids exchange rates: every denomination comes from the same Mint under the
same cap, so they are packaging, not different goods. Do not add an exchange rate
between coin and Valor or Renown, ever.

### 4.2 Valor

- A currency with **no item**: `withdrawable: false`, `payable: false`.
  `/withdraw` and `/pay` refuse it with one clear line.
- It appears in `/balance` and has its own leaderboard.
- Credited only by the earn endpoint (section 6) and the admin `add` command.

### 4.3 Renown

- Held per kingdom, plus one special ledger called **Commons** for Renown earned
  by players who are in no kingdom.
- Never withdrawn, transferred, paid or converted. Only spent on the Renown
  catalogue (section 7.2) or adjusted by an admin.
- Each kingdom stores: current balance, lifetime earned, and an append-only
  ledger of every credit and spend.

### 4.4 Kingdom treasury (coin)

**The split:** Charter Currency holds every coin balance, players' and kingdoms',
in one store with one set of rules (atomic, never negative, never partially
executed, one transaction log). Charter keeps owning the rights: who may spend a
kingdom's treasury and on what. The owner chose this so the Mint tax is an
in-process transfer that cannot fail half way, and so total coin supply is
counted in one place.

- Each kingdom has a coin balance, stored in Marks, filled by the Mint tax and by
  voluntary deposits.
- Anyone in the kingdom can deposit. Every movement is in the kingdom's ledger
  and visible to its members, so the treasury can be spent but never quietly.
- What the kingdom spends it on is the kingdom's business. This mod only holds
  it and moves it when told to by someone with the right.

**Who may withdraw:** the kingdom's **Lord** only, for now (owner, 2026-10-31),
plus ops. Who the Lord is comes from Charter (section 6.4). Charter may later
widen this; when it does, it is a change to the one permission check, nothing
else.

Charter (or an admin) can also move treasury coin with a console command:
`/chartercurrency treasury pay <kingdom> <player> <amount> [denomination]` moves
coin into that player's bank balance, and
`/chartercurrency treasury give <kingdom> <player> <amount> [denomination]` hands
it over as physical coin (bank rules: all-or-nothing on space).

| Command | Who | What |
|---|---|---|
| `/treasury` | Members, ops | Balance and the last few movements |
| `/treasury deposit <amount> [denomination]` | Members | Same rules as `/bank` |
| `/treasury withdraw <amount> [denomination]` | The Lord, ops | Same rules as `/withdraw` |
| `/treasury ledger [page]` | Members, ops | Full history (shared with the Renown ledger, filterable by currency) |

**Existing Charter treasuries.** If Charter's treasuries already hold coin-like
value, an admin moves each balance over once with
`/chartercurrency treasury add <kingdom> <amount>`, and Charter's own treasury
stops taking new money.

## 5. Bank and player commands

The forked commands, with these changes:

| Command | Behaviour |
|---|---|
| `/balance`, `/bal` | Coin (in the display format above) and Valor, with rank in each; plus the player's kingdom and its Renown |
| `/bank all` | Deposits every marked coin in the main 36 slots at face value. If plain (unmarked) copper, iron or gold nuggets are present, add one line: `Plain nuggets are not coin and were left alone.` |
| `/bank <amount> [denomination]` | Deposit an amount. Amount is in Marks unless a denomination word follows |
| `/withdraw <amount> [denomination]` | With a denomination, gives that many of it. Without one (or with `coin`), the amount is Marks and the mod gives the fewest coins (greedy: Sovereigns, then Crowns, then Marks). All-or-nothing on inventory space |
| `/pay <player> <amount> [denomination]` | Coin only. Online or offline. Valor refused |
| `/baltop [coin\|valor]` | Leaderboards from the fork |
| `/shop`, `/buy` | Section 7.1 |

Denomination words (case-insensitive, tab-completed): `mark`, `marks`,
`copper`; `crown`, `crowns`, `iron`; `sovereign`, `sovereigns`, `gold`, `sov`;
`coin`, `coins`.

Depositing removes coins across denominations; withdrawing makes change. Both
belong in `core/` as pure functions over counts (extend `Wallet`):

- `planDeposit(int[] countsPerDenomination, long amountMarks)`: which coins to
  take for an exact amount; if exact change is impossible from what is carried,
  take the smallest overpayment and return the difference as change coins. Return
  null if the player does not carry enough in total.
- `makeChange(long amountMarks, int[] faceValues)`: greedy split into
  denominations.

## 6. Earning

### 6.1 The earn endpoint

Every Valor and Renown award goes through one console or op command:

```
/chartercurrency earn <player> <source> [level] [run]
```

- `source` is a key in `earn.json` (section 9).
- `level` is an optional integer used by the source's formula.
- `run` is an optional string that identifies one shared activity (one dungeon
  run). See 6.3.

It computes the award, applies the caps, credits the player's Valor and their
kingdom's Renown (or Commons) in one step, and logs both. It prints one line to
the player: `+24 Valor. Your kingdom gained 24 Renown.` (or the capped or
probation variant, section 6.5).

Pocket Dungeons calls it through its existing `payoutCommand` config, which runs
a console command once for each online party member when a run settles:

```
"payoutCommand": "chartercurrency earn %player% dungeon %level%"
```

Events and build competitions are awarded by an admin typing the same command
(`chartercurrency earn Steve event`).

### 6.2 Valor per award

`valor = base + perLevel * level` (level 0 when absent), then caps:

- **Soft daily cap per source.** Once a player has earned `softCap` Valor from a
  source today, further awards from that source are halved (rounded down,
  minimum 1).
- **Hard daily cap per source** (optional). Once reached, further awards from that
  source pay 0 Valor and 0 Renown.
- **Carry-over.** Unused soft cap carries over for up to 2 extra days, so a player
  who skips a day is not behind. Store per player per source:
  `softCapRemaining`, refilled by `softCap` at each daily reset, capped at
  `3 * softCap`.
- "Today" resets at midnight in the timezone in `settings.json`
  (default: the server's system timezone).

### 6.3 Renown per award

`renown = round(valor * renownMultiplier)` (default multiplier 1.0), computed from
the Valor actually paid after caps, then:

- **Per player daily Renown cap:** a player can generate at most
  `renown.perPlayerDailyCap` (default 120) Renown per day across all sources.
- **Once per run per kingdom.** A dungeon party of four from one kingdom earns
  that kingdom one Renown credit, not four. Each player still gets their own
  Valor.
  - If `run` is given: the first award for a given `(kingdom, source, run)`
    credits Renown; later ones in the same key credit none.
  - If `run` is absent (today's Pocket Dungeons): awards for the same
    `(kingdom, source, level)` within `renown.runWindowSeconds` (default 30) of
    the first count as one run.
  - The Renown credited for a run is the largest single award computed in that
    run, not the first, so who settled first does not matter. Implement by
    crediting the first award, then topping up when a larger one arrives within
    the same key.
- **Probation:** a player generates no Renown for their kingdom until they have
  been seen in that kingdom for `renown.probationDays` (default 7). The mod
  stores, per player, the kingdom it last resolved them to and when it first saw
  them there; a change of kingdom restarts the clock. Valor is unaffected.
- **No kingdom:** the Renown goes to Commons instead (probation does not apply
  to Commons). It is never held for a kingdom the player joins later.

### 6.4 Kingdoms come from Charter

**Charter is the source of truth** for which kingdoms exist, which kingdom a
player is in, and who each kingdom's Lord is (owner, 2026-10-31). This mod never
stores its own copy of membership; it asks Charter every time it needs to know.

Put every question behind one interface so only one class knows how Charter is
reached:

```java
interface KingdomSource {
    Optional<String> kingdomOf(UUID player);   // kingdom id, empty if none
    Optional<UUID> lordOf(String kingdomId);
    String displayName(String kingdomId);
    Collection<String> kingdomIds();
}
```

How the `CharterKingdomSource` implementation reaches Charter (a Java API, a
command, a data file it writes) depends on what Charter is; settle it with the
owner first (section 15). Until it is wired, ship a `ManualKingdomSource` behind
the same interface so the rest of the mod can be built and tested: kingdoms,
members and Lords set by an op with
`/chartercurrency kingdom set <player> <kingdom|none>` and
`/chartercurrency kingdom lord <kingdom> <player>`, stored in `kingdoms.json`.
Core tests use an in-memory fake.

### 6.5 Player-facing lines

Keep them short and in this voice:

- `+24 Valor. Your kingdom gained 24 Renown.`
- `+12 Valor (daily limit reached, half rate). Your kingdom gained 12 Renown.`
- `+24 Valor. Renown starts counting for your kingdom in 3 days.`
- `+24 Valor. You are not in a kingdom, so 24 Renown went to the Commons.`
- `+24 Valor.` (when the kingdom already got this run's Renown or the player's
  daily Renown cap is spent)

## 7. Spending

### 7.1 The shop

The forked shop and shop editor. A listing's price may be in coin (an amount in
Marks; the editor and `/shop` display it in the coin format) or in Valor, or
both (charged both or neither, as in the fork). The confirm threshold
(`shop.confirmAt`) works per currency as in the fork.

Ship a small starter catalog in `shop.json` so the shop is not empty: common
building blocks priced in Marks (ice, sand, glass, terracotta, quartz, concrete
powder), a few working items in Crowns (rockets, enchanted books), and a few Valor
listings using the `components` block (a named and trimmed armour piece, a
custom-named player head). Every price is a placeholder the owner will retune.

### 7.2 The Renown catalogue

`renown_catalog.json` lists what a kingdom can buy with Renown. Each entry:

| Field | Meaning |
|---|---|
| `key` | What is typed after `/renown buy` |
| `name`, `description` | Shown in `/renown catalog` |
| `cost` | Renown, destroyed on purchase |
| `repeatable` | If false, a kingdom can buy it once |
| `requires` | Optional key that must already be bought |
| `commands` | Console commands run on purchase. Placeholders: `%kingdom%` (id), `%kingdom_name%`, `%buyer%` |

Ship the charter tiers as entries, with the costs below and only a broadcast
command each, because what a tier does belongs to the existing kingdom setup, not
to this mod:

| key | cost | requires |
|---|---|---|
| `tier_hamlet` | 300 | none |
| `tier_village` | 900 | `tier_hamlet` |
| `tier_town` | 2000 | `tier_village` |
| `tier_city` | 4000 | `tier_town` |
| `tier_realm` | 8000 | `tier_city` |

Example entry:

```json
{
  "key": "tier_hamlet",
  "name": "Hamlet Charter",
  "description": "Raise your kingdom to a Hamlet.",
  "cost": 300,
  "repeatable": false,
  "requires": null,
  "commands": [
    "tellraw @a {\"text\":\"%kingdom_name% is now a Hamlet.\",\"color\":\"gold\"}"
  ]
}
```

**Who can buy:** the kingdom's Lord (from Charter, section 6.4) and ops. A purchase asks for confirmation (the fork's confirm
pattern), checks balance and `requires`, deducts, records it, then runs the
commands. If a command fails, log it loudly and keep the purchase (the Renown is
spent; an admin can refund with `renown add`). Commons is bought from by ops only.

**Player commands:**

| Command | Who | What |
|---|---|---|
| `/renown` | Anyone | Your kingdom's Renown balance, lifetime earned, and what was bought |
| `/renown catalog` | Anyone | Entries, costs, bought or not, affordable or not |
| `/renown buy <key>` | The Lord, ops | Purchase, with confirmation |
| `/renown ledger [page]` | Members of that kingdom, ops | Recent credits and spends |
| `/renown top` | Anyone | Kingdoms by lifetime Renown earned |

## 8. The Mint

Coin enters the world only here. There is no crafting recipe for coins (a recipe
cannot be capped, and recipe ingredients cannot check a marker).

### 8.1 Mint blocks

- An op looks at a smithing table and runs `/chartercurrency mint bind` (also
  `unbind`, `list`). Bound positions are stored in `mints.json`.
- Right-clicking a bound smithing table **does not open the smithing UI**
  (cancel through Fabric API `UseBlockCallback`, returning a consuming result).
  Instead:
  - **Holding copper, iron or gold ingots:** strikes coins from the held stack,
    as many as the allowance permits.
  - **Sneak and right-click while holding ingots:** strikes from every stack of
    that metal in the main 36 slots.
  - **Empty hand or anything else:** shows the player's remaining allowance.
- Rates: 1 copper ingot = 1 Mark, 1 iron ingot = 1 Crown, 1 gold ingot =
  1 Sovereign.
- Coins go into the inventory with `giveOrDrop`. A small sound on success (the
  fork has a `Chime` class).
- Every strike is taxed for the striker's kingdom (section 8.3).
- `/mint` anywhere shows the remaining allowance.

### 8.2 The allowance (counted in Marks of face value, per player)

| Band | Yield |
|---|---|
| Full band: 192 Marks per day | 1 ingot per coin |
| Half band: next 192 Marks per day | 2 ingots per coin |
| Beyond | Nothing until the daily reset |

- Unused full band carries over for up to 2 extra days (store
  `fullRemaining`, refilled by 192 each reset, capped at 576). The half band does
  not carry over (refilled to 192 each reset).
- The full band is used first. A strike never splits a coin across bands: if a
  Sovereign (64) does not fit in what remains of the full band, it is struck from
  the half band if it fits there; otherwise it is not struck.
- All numbers live in `settings.json` under `mint`.
- Put this in `core/` as a pure planner:
  `MintPlan plan(MintState state, int faceValue, int ingotsOffered)` returning
  ingots used, coins out and the new state. The fabric side only removes the
  ingots, gives the coins and saves the state.

Line examples:
- `Struck 10 Iron Crowns: 8 to you, 2 to Northreach. Today: 112 of 192 left at full rate, then 192 at half.`
- `Half rate now: 2 ingots per coin.`
- `The Mint is done with you for today. It reopens at midnight.`

### 8.3 The Mint tax: 2 of every 10 coins

The kingdom takes 2 of every 10 coins a member strikes. The player keeps 8. The
rate is fixed in code at 20 percent and **is not configurable** (owner,
2026-10-31): no config key, no command, no per-kingdom setting.

- **Every fifth coin goes to the kingdom.** Keep a per-player, per-denomination
  counter that persists between strikes; each coin struck increments it, and every
  fifth coin is credited to the kingdom's treasury (as its face value in Marks)
  instead of being given to the player. This is exactly 2 in 10 over time, never
  rounds, and works for a strike of one Sovereign as well as a stack of copper.
- **The allowance counts every coin struck, taxed or not.** The tax does not let
  anyone mint more, and the daily supply cap is unchanged. A player's own take
  from a full band is about 154 Marks a day, not 192.
- **Players in no kingdom pay the same 20 percent, and it is destroyed** (never
  struck). Leaving a kingdom therefore never makes minting cheaper, and there is
  no server account that piles up coin.
- The counter belongs to the player, not to the kingdom; it carries across a
  change of kingdom so nobody can reset it by hopping.
- The tax is part of the core mint planner:
  `MintPlan plan(MintState state, int faceValue, int ingotsOffered)` also returns
  coins to the player, coins to the kingdom and the new tax counter.

### 8.4 Melting

Marked coins still fit the vanilla `9 nuggets -> 1 ingot` recipe. Leave it.
Blocking it needs a mixin, and it loses value (nine Sovereigns become one gold
ingot), so nobody does it for profit.

## 9. Config files (`config/chartercurrency/`)

All written with defaults on first run, all reloadable with
`/chartercurrency reload` except `accounts.json`.

**`currencies.json`**

```json
{
  "coin": {
    "displayName": "Coin", "baseUnit": "Mark",
    "withdrawable": true, "payable": true,
    "denominations": [
      { "id": "copper", "singular": "Copper Mark", "plural": "Copper Marks",
        "item": "minecraft:copper_nugget", "value": 1,
        "aliases": ["mark", "marks", "copper"] },
      { "id": "iron", "singular": "Iron Crown", "plural": "Iron Crowns",
        "item": "minecraft:iron_nugget", "value": 8,
        "aliases": ["crown", "crowns", "iron"] },
      { "id": "gold", "singular": "Gold Sovereign", "plural": "Gold Sovereigns",
        "item": "minecraft:gold_nugget", "value": 64,
        "aliases": ["sovereign", "sovereigns", "gold", "sov"] }
    ]
  },
  "valor": {
    "displayName": "Valor", "withdrawable": false, "payable": false,
    "aliases": ["valor"]
  }
}
```

The marker for each denomination is always
`{"chartercurrency":{"coin":"<id>"}}`; it is not configurable, so a config edit
cannot make plain nuggets into money.

**`earn.json`**

```json
{
  "sources": {
    "dungeon": { "base": 10, "perLevel": 2, "softCap": 150, "hardCap": 0, "renownMultiplier": 1.0 },
    "event":   { "base": 20, "perLevel": 0, "softCap": 0,   "hardCap": 0, "renownMultiplier": 1.0 },
    "buildoff_1st": { "base": 100, "perLevel": 0, "softCap": 0, "hardCap": 0, "renownMultiplier": 1.0 },
    "buildoff_2nd": { "base": 60,  "perLevel": 0, "softCap": 0, "hardCap": 0, "renownMultiplier": 1.0 },
    "buildoff_3rd": { "base": 30,  "perLevel": 0, "softCap": 0, "hardCap": 0, "renownMultiplier": 1.0 }
  },
  "renown": { "perPlayerDailyCap": 120, "runWindowSeconds": 30, "probationDays": 7 }
}
```

`0` means no cap.

**`kingdoms.json`**: used only by the `ManualKingdomSource` stand-in (section
6.4). Once Charter is wired, kingdoms, members and Lords come from Charter and
this file is ignored.

```json
{
  "source": "manual",
  "kingdoms": {
    "northreach": { "name": "Northreach", "lord": "uuid", "members": ["uuid"] }
  }
}
```

**`renown_catalog.json`**: section 7.2. **`shop.json`**: as the fork, prices in
`coin` or `valor`. **`mints.json`**: bound positions (dimension and block pos).

**`settings.json`**: the fork's settings plus:

```json
{
  "timezone": "",
  "mint": { "fullBandPerDay": 192, "halfBandPerDay": 192, "carryOverDays": 2 },
  "shop": { "confirmAt": { "coin": 512, "valor": 100 } }
}
```

## 10. State and logs

| File | Holds |
|---|---|
| `accounts.json` | Per player: coin (Marks) and Valor. The fork's atomic, debounced store and its refuse-to-overwrite rule |
| `players.json` | Per player: mint state and tax counters, per-source soft cap remaining, Renown generated today, last resolved kingdom and since when, last reset day |
| `kingdoms_state.json` | Per kingdom and Commons: Renown balance, lifetime earned, bought catalog keys; per kingdom: coin treasury in Marks, lifetime Mint tax collected; one ledger for both |
| `transactions.log` | Append-only: every deposit, withdraw, pay, purchase, strike, earn and Renown purchase, with actor and amounts |

Write all state files the same way the fork writes `accounts.json`.

## 11. Admin commands (op level 2, `Commands.LEVEL_GAMEMASTERS`)

| Command | What |
|---|---|
| `/chartercurrency earn <player> <source> [level] [run]` | Section 6 |
| `/chartercurrency balance <player>` | Coin and Valor |
| `/chartercurrency set\|add\|remove <player> <amount> <coin\|valor>` | Same code path as players, never negative |
| `/chartercurrency renown add\|remove <kingdom\|commons> <amount>` | Logged with the operator's name |
| `/chartercurrency treasury add\|remove <kingdom> <amount>` | Coin, in Marks. Logged with the operator's name |
| `/chartercurrency treasury pay\|give <kingdom> <player> <amount> [denomination]` | The route Charter uses to spend a treasury (section 4.4) |
| `/chartercurrency kingdom set <player> <kingdom\|none>` | Manual membership override |
| `/chartercurrency kingdom list` | Kingdoms, Lords and member counts as this mod currently sees them (from Charter, or the stand-in) |
| `/chartercurrency mint bind\|unbind\|list` | Section 8.1 |
| `/chartercurrency shop ...` | The fork's shop admin and editor |
| `/chartercurrency stats` | Coin supply (banked plus a note that carried coin is not counted), Marks minted today and this week, Mint tax collected and destroyed, Valor earned today by source, Renown and treasury by kingdom |
| `/chartercurrency reload` | Reload every config except accounts |

Every admin action is logged with the operator's name. The startup log prints
coin supply and Valor supply, as the fork does.

## 12. Tests (pure Java, `core/`, in the fork's test style)

Keep every forked test passing under the new names, then add:

- **Denominations:** deposit counts mixed coins at face value; exact deposit;
  overpay with change; not enough in total returns null; `makeChange` gives the
  fewest coins; withdraw rolls back with no inventory space.
- **Mint planner:** full band first; half band at 2 ingots per coin; no coin
  split across bands; carry-over refills and caps at 576; nothing past the half
  band; an odd ingot left in the half band is not consumed.
- **Mint tax:** every fifth coin per denomination goes to the kingdom; the
  counter persists across strikes (strikes of 3 then 2 tax the fifth); exactly 20
  percent over 1,000 random strikes; allowance counts taxed coins; no kingdom
  means the fifth coin is destroyed; the counter survives a kingdom change.
- **Treasury:** deposit and withdraw follow bank rules; only the Lord withdraws;
  never negative under concurrent withdrawals.
- **Earn rules:** formula with and without level; soft cap halving with minimum
  1; hard cap pays 0 and 0; soft cap carry-over; per player Renown cap; run
  de-duplication with and without `run`, including the top-up to the largest
  award; probation, and its restart on a kingdom change; Commons for no kingdom.
- **Renown catalogue:** insufficient balance refused; `requires` enforced;
  non-repeatable bought once; balance never negative under concurrent buys (the
  fork's multi-thread pattern).
- **Concurrency:** eight threads awarding to one kingdom never lose or duplicate
  Renown.

## 13. Optional change in Pocket Dungeons (ask the owner first)

Not required: section 6.3's time window handles today's `payoutCommand`. If the
owner wants exact run de-duplication, add `%uuid%` and `%run%` placeholders in
`pocketdungeons/src/main/java/pocketdungeons/Payout.java` (where `%player%`,
`%level%` and `%chests%` are substituted) and change the config to
`chartercurrency earn %player% dungeon %level% %run%`. Do this only with the
owner's go-ahead and following `pocketdungeons/CONVENTIONS.md`.

## 14. Milestones and done-when

1. **Fork builds.** Renamed copy, forked tests pass, `./gradlew build` produces
   `chartercurrency-0.1.0.jar`. Nothing else changed.
2. **Coin.** `currencies.json`, markers, denominations, deposit and change.
   Done when `/bank all` with a mixed coin inventory credits the right Marks,
   plain nuggets are left with the line from section 5, `/withdraw 200` gives
   3 Sovereigns 1 Crown, and `/pay` moves coin.
3. **Valor.** Item-less currency; `/withdraw` and `/pay` refuse it; shop
   listings in Valor work.
4. **Mint and treasury.** Bind, strike, allowance, carry-over, the 20 percent
   tax, kingdom treasuries and `/treasury`, messages, `/mint`. Needs the kingdom
   resolver from milestone 5, so build that resolver first in this milestone.
5. **Earn and Renown.** Earn endpoint, caps, run de-duplication, probation,
   Commons, kingdom resolver and manual override, `/renown` commands, catalogue
   with the five tiers.
6. **Stats and polish.** `/chartercurrency stats`, startup supply lines,
   README written for operators (commands, config, how to set up kingdoms and
   Mints).

Commit after each milestone with the core tests green.

## 15. Verify against the 26.2 jar before relying on it

**First, settle with the owner how this mod talks to Charter**: what Charter is
(a mod, a plugin, a datapack), where its code lives, and how it can answer the
four `KingdomSource` questions in section 6.4. Known so far: Charter identifies
kingdoms and members itself, and only the Lord may spend the treasury and buy
from the Renown catalogue.

Then check:

Check with `javap` against the Loom cache, as the fork's README shows:

- `minecraft:copper_nugget` exists in 26.2.
- Reading `custom_data` from an `ItemStack` and testing for a key (the
  component type and accessor names).
- `UseBlockCallback` return type for "handled, do not open the UI".
- The inventory and player-list calls the fork's README lists as unverified.

## 16. Do not

- Do not add an exchange rate between coin and Valor or Renown.
- Do not add a crafting recipe that produces coins.
- Do not let Valor or Renown be withdrawn, paid or transferred.
- Do not make the Mint tax configurable.
- Do not build claims, kingdom management, kingdom rights, tier effects, war or
  upkeep. The treasury holds coin and moves it when told; Charter decides who may
  tell it, and it never charges kingdoms anything.
- Do not modify `cobbleeconomy/` or any other mod (except section 13, with
  permission).
- Do not add mixins. None are needed. (The fork's dialog mixin may stay only if
  the dialog UI stays.)
- Do not deploy to a server or push without the owner asking.
