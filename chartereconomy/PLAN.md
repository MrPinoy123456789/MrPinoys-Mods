# Charter Economy: Build Plan

> Reads with `SPEC.md`. Phases are ordered so each one ships something playable
> and nothing waits on an outside mod.

## Where the code lives

Everything is one new mod, `chartereconomy` (**Charter Economy**), in the house
layout: `core/` (pure Java, no Minecraft on the classpath, tested in about a
second) and `fabric/` (the mod). JDK 25, Minecraft 26.2,
`"environment": "server"`. Four small mixins, all for claim protection
(explosions, pistons, fluids, fire), each behind a config switch; nothing else
uses one. Commands: `/bank`, `/balance`, `/pay`,
`/shop`, `/buy`, `/baltop` for players, `/charter` for kingdoms and the Mint.

| Piece | Where |
|---|---|
| Coin denominations, Valor, bank, shop | `chartereconomy` (forked from Cobble Economy, then extended) |
| Mint, kingdoms, charters, Renown, upkeep, `/charter earn` | `chartereconomy` |
| Land claims and protection | `chartereconomy` (SPEC section 4.6) |

`cobbleeconomy` is not touched; it keeps running the cobblestone server.

## Phase 1: fork the bank and teach it coin (core tested)

1. Copy Cobble Economy's `core` and `fabric` into `chartereconomy`, rename the
   packages and config folder (`config/chartereconomy/`), and get its 200 core
   assertions passing under the new name before changing anything.
2. **Currencies from config** (`currencies.json`): coin and Valor ship as the
   defaults. Cobblestone and diamonds are removed.
3. **Marker matching**: a denomination names a `custom_data` marker; the item bank
   counts, removes and frees space only for stacks carrying it. Withdraw stamps
   the full component set onto what it hands out.
4. **Denominations**: coin lists `{item, marker, components, value}` entries.
   `Wallet` gains change-making across denominations (tests for exact change,
   insufficient change, full inventory rollback).
5. **Valor**: a currency with no item; `/withdraw` and `/pay` refuse it.
6. Display: mixed denominations with the total in Marks.

Done when `/bank all` with a mixed coin inventory credits the right Marks, a plain
nugget is refused with a clear line, `/withdraw 200 coin` makes the fewest coins,
and `/pay` on Valor is refused.

## Phase 2: the Mint and `/charter earn`

1. **Mint**: bind a smithing table, chest-menu UI, face-value allowance with bands
   and three day carry-over (core tested), strike log, supply line at startup.
2. **`/charter earn`**: sources and caps from `earn.json`; Valor to the bank,
   Renown to the Commons until kingdoms exist (phase 4).
3. **Shop**: the starter catalog in coin and Valor (SPEC section 6).

Done when a player can strike all three coins up to the allowance, buy from the
shop, and a test `/charter earn` pays Valor.

## Phase 3: dungeons pay Valor and Renown

**pocketdungeons** is the only faucet mod on the charter server: add `%uuid%` and
`%run%` to `Payout.substitute`, then set `payoutCommand` to
`/charter earn %player% dungeon %level% %run%`. Bounties, quizengine and
chatdonkey are not on this server and need no changes.

## Phase 4: kingdoms and charters

1. Charter registration for the existing kingdoms, seat lectern, roles, scoreboard team, active citizen count
   from vanilla playtime stats.
2. Tiers, `^0.8` cost scaling, `onTierUp` and `onPerkPaused` command templates.
3. Deposit-only treasury, weekly upkeep, perk pause on non-payment.
4. Renown rules: per player cap, once per run per kingdom, probation, hop wait,
   Commons for kingdomless players.
5. Renown spend approvals, `/charter ledger`, absent leader succession.
6. **Plots**: one fixed 9x9-chunk plot per kingdom,
   registered by `/charter admin register` at each existing kingdom's current
   centre (overlap check); role permissions and ally pacts; Fabric API event
   protection for player actions; then the four protection mixins (core tests for
   the plot map and permission rules; gametests for each mixin).
7. Borders: action bar on entry, `/charter map`, particle outline.
8. Ruined kingdoms (off by default, per-kingdom admin switch).

## Phase 5: seasons and polish

1. Season boards: Valor per player; Renown per active citizen, bracketed by tier.
2. ballot voter-list file, so kingdom votes can approve large Renown spends.
3. The weekly Valor shelf (ROADMAP P2.2 rotation).

## Phase 6: war hooks

The currency side of limited war only (SPEC section 4.7): the Holding record
(which kingdom controls it), the daily Renown and coin payout to its controller,
the Renown cost of a declaration, and the `war` earn source. How war is fought is
a separate design.

## What to measure from day one

- Total coin supply and Marks minted per day (startup log, `/baltop`).
- Valor earned per active player per week, split by source.
- Days for each kingdom to reach each tier, by active citizen count.
- Share of Renown coming from the top 10 percent of players (a snowball alarm).
- Coin and Renown paid out by Holdings per week, and who controls each one.

Retune with `SPEC.md` §5.4 as the target, not the starting prices.
