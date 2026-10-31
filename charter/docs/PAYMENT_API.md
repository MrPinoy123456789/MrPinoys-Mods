# The payment API

Charter has no treasury of its own. Every balance an organization holds is Marks (the coin currency) kept by a
payment provider, a separate mod. Charter keeps the rights (who may spend, and how much without a vote); the provider
keeps the money. Charter runs without a provider: every cost is then waived.

The API is the `charter.api` package of the `core` module. It has no Minecraft imports.

| Type | What it is |
|---|---|
| `PaymentPort` | `balance(orgId)`, `charge(orgId, marks, reason)`, `credit(orgId, marks, reason)`. Server thread only. |
| `PaymentProvider` | What a provider implements: `port()` and `bind(KingdomView)`. |
| `KingdomView` | Charter's answers to the provider: `kingdomOf(player)`, `lordOf(orgId)`, `displayName(orgId)`, `orgIds()`. |
| `NoPayment` | The port used when no provider is installed. |
| `StateKingdomView` | Charter's own `KingdomView`, read from the live state. |

## Registering a provider

Add the entrypoint key `charter:payment` to the provider's `fabric.mod.json`, pointing at a class that implements
`PaymentProvider`. At server start Charter calls `FabricLoader.getEntrypoints("charter:payment", PaymentProvider.class)`,
takes the first, calls `bind(view)` once, and uses `port()` from then on. A second provider is logged and ignored. A
provider that throws while starting is logged and replaced by `NoPayment`. Neither mod needs the other to load, and
Charter never names a provider class.

## The organization id

Every id is the string form of Charter's `OrgId`: `org:` followed by the organization's UUID, for example
`org:3f2a6c1e-8b7d-4c52-9a10-5d0b7e4f1a22`. The id is permanent; renaming never changes it. Every organization of the
tree (a kingdom, a sub-faction, an outpost owner) is a valid key, so the port may hold a balance for each. Where a
provider says "kingdom" (Renown, Mint tax) it means the top level organization that `kingdomOf` returns.

## What Charter does with the port

| Reason passed | When | Port calls |
|---|---|---|
| `keep` | `PlaceNexus`, a keep placed by an organization that already exists | charge `keepPriceMarks` |
| `outpost` | `PlantCamp` | charge the deposit (`outpostDepositMarks` plus `outpostDepositStepMarks` for each outpost the nation holds) |
| `outpost_refund` | an outpost lost without a capture | credit the deposit it held |
| `plunder` | a captured outpost's deposit; a fallen raider stronghold | credit the attackers; for a stronghold, charge the site then credit the winner a quarter of its balance |
| `fine` | a custody fine | charge the prisoner's own kingdom, then credit the fining faction |
| `transfer` | `Transfer` | charge the payer, then credit the payee (the credit follows a successful charge) |
| `deposit` | `Deposit`, operators and scenarios only | credit |
| `npc_treasury` | a raider site is raised | credit `npcTreasuryMarks` for its tier |

`Withdraw` does not call the port. Charter checks the spend right and the balance, records the withdrawal, then asks the
provider to pay out with its own console route (`chartercurrency treasury give|pay <orgId> <player> <marks>`, the id
quoted), which takes the Marks out of the treasury itself. With no provider a withdrawal is refused.

Founding a kingdom's first keep (`FoundKeep`) is free: the new organization has nothing to pay from.

Marks only move. Every move between organizations is a charge followed by a credit, and a failed charge stops it.

## With no provider

`NoPayment` reports a balance of 0, accepts every charge (the cost is waived) and ignores every credit. The first
waiver of each reason is logged once at WARN. No page shows a balance, `Withdraw` and `Transfer` are refused, and a
waived outpost deposit is recorded as 0 so losing the outpost later refunds nothing.

## The old treasury

The old per organization ledger (cobblestone and diamonds), the players' personal safes, the per outpost diamond deposit
(`Core.deposit`), the raider crumble loot fields and the per currency spend limits inside `GrantScope` are still saved
and loaded exactly as they were. Nothing reads, spends or shows them. A grant saved with the old per currency limit
keeps its diamond limit as the same spend in Marks at the reference rate of 32 Marks to the diamond; that rate is used
only to carry an old limit over and is never an exchange rate. The old action and event records (`treasury.deposit`,
`treasury.withdraw`, `treasury.transfer`, the three `TreasuryEvents` with a currency, `ContractEvents.Fined`,
`Term.Fine`) stay registered so stored history and pending votes still read back.

## Knobs, in Marks

| Knob | Default | Replaces |
|---|---|---|
| `keepPriceMarks` | 2048 | `keepPriceDiamonds` 64 |
| `outpostDepositMarks` | 256 | `outpostDeposit` 8 |
| `outpostDepositStepMarks` | 128 | `outpostDepositStep` 4 |
| `lordSpendLimitMarks` | 1024 | `lordSpendLimitDiamonds` 32 and `lordSpendLimitCobblestone` 2048 |
| `custodyFineCapMarks` | 512 | `custodyFineCapDiamonds` 16 |
| `custodyFinesFromKingdom` | true | new: whether a fine is charged to the prisoner's kingdom |
| `npcTreasuryMarks` | 528, 1056, 2112 | `npcDiamonds` and `npcCobblestone` |

`npcFallLoot` is gone: the plunder of a fallen stronghold is credited to the winning kingdom through the port.
