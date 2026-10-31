# Charter Economy: Build Plan

> Reads with `SPEC.md`. Phases are ordered so each one ships something playable
> and nothing waits on an unverified outside mod until phase 4.

## Where the code lives

| Piece | Mod | Why there |
|---|---|---|
| Coin denominations, Valor balance, bank, shop | `cobbleeconomy` | It already is the bank and shop; the charter server uses it by config |
| Mint, kingdoms, charters, Renown, upkeep, `/charter earn` | `chartereconomy` (new) | Charter-server rules; must not leak into the cobblestone server |
| Claims | Adopted mod (Open Parties and Claims, pending a 26.2 build) | Protection needs mixins; do not build it |

`chartereconomy` never imports `cobbleeconomy`. They meet through coin items (the
marker) and command templates, the suite's usual contract.

## Phase 1: cobbleeconomy learns coin (code, core tested)

1. **P3.6 `currencies.json`**: the currency list moves to config, defaulting to
   today's two so the cobblestone server is unchanged.
2. **Marker matching**: a currency (or denomination) may name a `custom_data`
   marker; `ItemBank` counts, removes and frees space only for stacks carrying it.
   Withdraw stamps the full component set onto what it hands out.
3. **Denominations**: a currency may list `{item, marker, components, value}`
   entries. `Wallet` gains change-making across denominations (core, pure Java,
   tests for exact change, insufficient change, full inventory rollback).
4. **Flags**: `withdrawable` and `payable` per currency, and a currency with no
   item at all (Valor).
5. Display: mixed denominations with the total in base units.

Done when `/bank all` with a mixed coin inventory credits the right Marks, a plain
nugget is refused with a clear line, `/withdraw 200 coin` makes the fewest coins,
and `/pay` on Valor is refused.

## Phase 2: chartereconomy, Mint and earn (new mod)

1. Mod skeleton in the house layout (`core/` pure Java, `fabric/`), JDK 25, 26.2,
   `"environment": "server"`, zero mixins.
2. **Mint**: bind a smithing table, chest-menu UI, face-value allowance with bands
   and three day carry-over (core tested), strike log, supply line at startup.
3. **`/charter earn`**: sources and caps from `earn.json`, Valor paid through a
   command template, Renown to the Commons until kingdoms exist.

Done when a player can strike all three coins up to the allowance and a test
`/charter earn` pays Valor through cobbleeconomy.

## Phase 3: faucets speak coin and Valor (small changes in four mods)

1. **pocketdungeons**: add `%uuid%` and `%run%` to `Payout.substitute`; set
   `payoutCommand` to `/charter earn %player% dungeon %level% %run%`.
2. **bounties, quizengine, chatdonkey**: the hardcoded `Items.DIAMOND` reward
   becomes a configurable reward item (`item`, `components`, `count`), defaulting
   to diamonds so the cobblestone server is unchanged; plus an optional
   `rewardCommand` template for Valor.
3. **wayfarers**: `Payment` generalised from its two-currency wallet to a
   configured currency with markers (the largest of the four; can trail).

## Phase 4: kingdoms and charters

1. Charter founding, seat lectern, roles, scoreboard team, active citizen count
   from vanilla playtime stats.
2. Tiers, `^0.8` cost scaling, `onTierUp` and `onPerkPaused` command templates.
3. Deposit-only treasury, weekly upkeep, perk pause on non-payment.
4. Renown rules: per player cap, once per run per kingdom, probation, hop wait,
   Commons for kingdomless players.
5. Renown spend approvals, `/charter ledger`, absent leader succession.
6. Wire the adopted claims mod through templates (claim area per tier).

## Phase 5: seasons and polish

1. Season boards: Valor per player; Renown per active citizen, bracketed by tier.
2. ballot voter-list file, so kingdom votes can approve large Renown spends.
3. The weekly Valor shelf (ROADMAP P2.2 rotation).

## What to measure from day one

- Total coin supply and Marks minted per day (startup log, `/baltop`).
- Valor earned per active player per week, split by source.
- Days for each kingdom to reach each tier, by active citizen count.
- Share of Renown coming from the top 10 percent of players (a snowball alarm).

Retune with `SPEC.md` §5.4 as the target, not the starting prices.
