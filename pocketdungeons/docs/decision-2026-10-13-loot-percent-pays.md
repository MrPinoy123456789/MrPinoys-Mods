# Decision 2026-10-13: one loot system, the wager percent pays

PD-207 (playtest 2026-10-13-1). The door board printed two numbers that both said "loot":
`Loot 250%` (the affix wager, display only) and `loot x3` (the floor's barrel roll count). The
player asked which was which on sight.

Owner call: "lets just have the one loot system using the affix wager and %", then picked
"Percent scales chest rolls" from the design options.

## The rule

`Loot n%` is the floor's loot multiplier and the only loot number. A cleared floor pays

    rolls = round((baseRewardChests + depthBonus) x n / 100)

with the won-finale chest still added flat on top (`FinaleWave.rewardChests`, not scaled; it is
a completion prize, not odds). At `lootPercentPerWager` 50 that reads: Loot 100% pays the base,
Loot 250% pays 8 rolls on a base of 3, Loot 400% pays 12. Rounding is half up
(`Wager.scaledChests`).

The wagered floor's extra copper chest piece (`WagerGear`) stays: it is gear, not loot rolls,
and the deal sheet names it with the other promises.

## What the player sees now

- Door board name line: `Hollow Walls . Overclocked . Loot 250%` (white at 100, gold above).
- Door board green line: `8 rolls . costs 2 scrap`, the count the percent implies for this
  floor's depth. The word "loot" appears once.
- Deal sheet and floor title: the same Loot percent, unchanged.
- Cleared verdict and the bar between floors: `8 loot rolls, chart scrap` reads the real stored
  count (`FloorState.rewardChests`).
- HOME: `N chests in your reward barrel` is now the deepest floor's actual roll count
  (`IntervalState.floorChests`, recorded at each clear), not a formula recomputed at settle.

## Not done

- The vault's finish rolls are not scaled; the percent wagers on the floor, not the finish.
- No knob for the scaling itself: the shape is fixed by `lootPercentPerWager` plus
  `baseRewardChests` and the zone's `depthBonus`. A separate scaling knob would split the one
  system again.
- `Payout.payoutCommand` still takes the chest count; it now receives the deepest floor's count.

## Tests

- `WagerTest.scaledChests`: 100% pays base, 200% doubles, 250% rounds 7.5 up to 8, a zero knob
  and a negative wager never pay below base.
- `IntervalBankingTest`: the settle count is the deepest floor's rolls; empty trip still banks
  the base.
- `BankAnywhereGameTest` builds its records with per-floor `floorChests`, mirroring production.

Supersedes: nothing. Amends `docs/wager-draft-2026-10-12.md`'s "display only" line.

Open question: whether the deal sheet should also state the scaled count (`8 rolls`) on floors
the player has not committed yet, or only the percent. Both are on the board already.
