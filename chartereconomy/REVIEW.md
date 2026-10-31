# Adversarial Review Record

> 2026-10-31. A separate reviewer was briefed as a Minecraft Java lead designer
> (nation servers, EarthMC, CivCraft, Wynncraft, Hypixel SkyBlock) and told to
> attack the first draft and check feasibility against this repo. Two passes: the
> first on the draft, the second on the owner's request to split the special
> currency between players and kingdoms. This file records what it said, what was
> verified, and what the spec did about it.

## Verified against the code

| Claim | Where | Result |
|---|---|---|
| A currency is a bare item id, with no component matching | `cobbleeconomy/core/.../Currency.java` | True |
| The bank matches with `stack.is(item)`, so a plain nugget counts as a coin | `cobbleeconomy/fabric/.../ItemBank.java` | True |
| `currencies.json` (ROADMAP P3.6) is not built | `CobbleEconomyMod.java:62` calls `CurrencyRegistry.defaults()` | True |
| Faucets hardcode diamonds | `bounties/.../Rewards.java:18`, `quizengine/.../Orchestrator.java:283`, `chatdonkey/.../Rewards.java:115`, `wayfarers/.../Payment.java:33` | True |
| `payoutCommand` knows only `%player%`, `%level%`, `%chests%` | `pocketdungeons/.../Payout.java:89` | True |
| Recipe ingredients cannot require `custom_data` | Vanilla recipe format | True |
| A 10 into 1 coin recipe is impossible anyway (9 slots, one item each) | Vanilla crafting grid | True |

## What real servers taught (reviewer's account)

- **Metal-backed money** (EarthMC gold, Towny emeralds) inflates at the rate of
  its least-limited farm. CivCraft held up only because ore supply was controlled.
- **Craftable coins** make the best farmer the central bank within weeks.
- **Fixed denominations** work when every denomination comes from one source
  (Wynncraft's emerald ladder).
- **Account-bound currencies** resist real-money trading because they cannot
  move; anything transferable gets farmed by alts.
- **Upkeep that removes protection** becomes a griefing calendar (Towny).
  Superlinear upkeep breeds alt satellite towns. Withdrawable treasuries get
  embezzled.
- **Map wipes** killed CivCraft communities; "leaderboards reset, the world
  stays" survives.

## What changed in the spec

| Reviewer said | Spec section | Decision |
|---|---|---|
| Never back the top coin with gold; gold is Java's most farmable valuable | 3 | Accepted in spirit: the cap is counted in face value, so the metal stops mattering |
| One coin with fixed denominations beats three independent coins | 2.1 | Accepted; it was also the owner's instinct |
| Mint, not recipe; recipes cannot cap or check markers | 3 | Accepted |
| Only copper should be mintable | 3.1 | **Overruled.** The owner wants copper, iron and gold coins struck from their metals. With a face-value cap, letting all three mint gives the fantasy at no supply cost |
| Mint allowance tied to charter tier is a snowball | 3.2 | Accepted: per player only |
| Mirrored Valor and Renown, not a zero-sum split | 2.3 | Accepted |
| Normalise by active citizens, `^0.8` scaling | 4.2, 4.3 | Accepted |
| Kingdomless Renown to a Commons pot, never escrow | 5.3 | Accepted |
| Once per run per kingdom; probation; 7 day hop wait | 5.3 | Accepted |
| Renown spend-only, two-officer approval for big spends, absent leader succession | 4.5 | Accepted |
| Deposit-only treasury | 4.4 | Accepted |
| Unpaid upkeep never removes protection | 4.4 | Accepted |
| Power items out of the Valor shop | 6.1, 6.2 | Accepted: power in coin, convenience and cosmetics in Valor |
| Season crown split by bracket, scored per active citizen | 7 | Accepted |
| Cut the market tax and the weekly "Valor pledged" board | n/a | Accepted; both removed |
| Collapse back to cobblestone and diamonds plus one kingdom unit | header | **Overruled.** The charter server is its own economy (owner confirmed 2026-10-31), and the owner asked for metal coins |
| Adopt a claims mod; building claims needs mixins | 9 | Accepted |
