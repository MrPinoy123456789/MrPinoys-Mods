# Economy: sources, sinks, exploits and tuning

Audit of master 478cce26, 2026-11-04. Every amount is in Gold Sovereigns (Sov). The code stores Marks (64 to a Sovereign); one gold ingot strikes 10 Sovereigns at the Mint. UNVERIFIED marks what depends on live config or a live client.

## 1. Flow diagram: every source and sink

```
GOLD ITEMS (vanilla and GoldGate)
  overworld gold ore: raw gold, Fortune III about 2.2 raw per ore (about 22 Sov an ore)   ungated
  nether gold ore: 2 to 6 nuggets, Fortune III about one ingot a block (about 10 Sov)    ungated, Nether OFF today
  bastion chests and gold blocks (9 ingots a block)                                        ungated, Nether OFF today
  zombified piglin nuggets and ingots: player kill only (GoldGate)                         Nether OFF: never fires
  buried treasure, shipwrecks, mineshafts, Dungeons and Taverns, Incendium, Structory chests small, ungated
  Pocket Dungeons wild clear: about 23 ingots at tier 1, about 47 at tier 3, per lodestone cooldown 2 to 12 h
  piglin bartering takes ingots and returns no gold                                        gold SINK (Nether off)
  shop: gold ingot for 10 Sov                                                               coin to gold, neutral
        |
        v  tap a Mint (a bound smithing table, or any smithing table in a Bank's chunk)
MINT  (MintListener.java:128-227, core/Mint.java)
  1 ingot -> 10 Sov struck while under softCapIngots (320) today; then 2, 4, 8 ... 64 ingots per 10 Sov every falloffEvery (320)
  every fifth coin -> the striker's kingdom treasury (fixed 20 percent); no kingdom -> destroyed
  full day at full rate: 3,200 Sov struck, 2,560 to the player, 640 to the treasury
  a full inventory of 2,304 ingots: 6,360 Sov struck (SPEC.md:499-500)
  coins -> pocket; overflow -> bank; bank refuses -> dropped
        |
        v
POCKET <-> BANK (no value change)
  /bank all, /bank <n>, any item use with a coin stack held (CoinBankClick.java:34-41)
  /withdraw <n> [gold]; Bank block buttons 1, 8, 64 Sov
  /pay <player> <n>: bank to bank, offline allowed
  death: pocket coin drops as items (Mossbeard race keeps inventory)
        |
        v
SHOP (buy only, no sell side: core/Shop.java:56-92)                                           COIN SINK
  105 starter listings (ShopStarter.java:57-120): sand/gravel/wool 64 = 20; glass 30; logs 64 = 20;
  glowstone 64 = 80; rockets 64 = 80; saddle 80; name tag 40; enchanted books 60 to 200 (Sharpness V 200,
  Silk Touch 120); gold ingot 10
  confirm at or above shop.confirmAt.coin = 320 Sov (a Java dialog; Geyser rendering UNVERIFIED)
  Valor listings: Champion's Helm 150, Champion's Trophy 80, Banner of Valor 40 (the ONLY Valor sinks, 270 total)

KINGDOM TREASURY (core/Treasury.java)
  in:  20 percent Mint tax; /treasury deposit; Charter credits: outpost refund, plunder (a captured outpost's
       deposit, a fallen stronghold's share), transfer, npc_treasury 8.25 / 16.5 / 33 Sov per raider stronghold
       razed (CREATED FROM NOTHING, SiegeSim.java:230, Knobs.java:323)
  out: /treasury withdraw by a Lord or op, no limit, no vote, not in Charter's history (TreasuryCommands.java:239-252)
       Charter's Withdraw action -> `treasury give|pay`, governed by lordSpendLimitMarks (-1: unlimited)
       Charter charges: keep 32 Sov; outpost 4 + 2 per held; bail 0.5 Sov an hour; transfer; plunder
  player wallet charge: custody fine up to 8 Sov (Justice.java:272)

VALOR (a balance; never moves between players)
  in:  /chartercurrency earn <player> <source> [level] [run] (RenownCommands.java:52-64)
       dungeon: 10 + 2 per level, soft cap 150 a day then halved, carry-over to 450
       event 20; buildoff 100 / 60 / 30 (typed by an admin)
       fortune: NOT in the shipped defaults (EarnConfigFile.java:38-42); README asks the operator to add 4 + 2 per level, soft cap 60
       Fortune spin levels: no match 1 (6 Valor), pair 2 (8), low triple 4 (12), mid triple 6 (16), Three Diamonds 10 (24), Three Crowns 16 (36); a stake raises the level
  out: the three Valor listings; admin remove

RENOWN (per kingdom and the Commons)
  in:  round(Valor paid x multiplier) per award, once per (kingdom, source, run); per player cap 120 a day;
       7 day probation per kingdom; a Fortune run id is per player per day, so every spin credits
  out: /renown buy: hamlet 300, village 900, town 2000, city 4000, realm 8000 (a broadcast only)

FORTUNE (fortune/core/Settings.java:72-92)
  stake in:  5 / 10 / 20 Sov from the BANK (+25 / +50 / +100 percent on Valor level and items), charged before the roll,
             never returned, no transaction log or analytics line                              COIN SINK
  prizes out: Valor via earn (above); low triple 4 iron (11 percent), mid triple 2 emeralds (5.5), Three Diamonds
              1 diamond (1.3), Three Crowns 2 diamonds and a keepsake clock (0.2); milestone keepsakes at 7 / 30 / 60 / 100 / 200 / 365 claims
  one spin per UUID per machine day (08:00 UTC rollover), first spin after 5 minutes of playtime

XP: no house mod touches XP. ITEMS of value outside coin: Pocket Dungeons wild loot (above), Excellent Enchants
Silk Spawner (a spawner block, 25 percent), villager trades (Instant Restock removes the demand brake; Humans pay 10 percent less).
```

Fortune expected value per spin at the shipped tables: plain day 8.2 Valor, 0.44 iron, 0.11 emerald, 0.017 diamond; days 3 to 6 10.1 Valor; Golden Spin 14.6 Valor, 2.4 iron, 0.6 emerald, 0.12 diamond. A full week: about 72 Valor, 6.5 iron, 1.6 emeralds, 0.28 diamonds. At 60 spins a day server-wide, one Three Crowns about every eight days. In coin the machine keeps 100 percent of every stake; the "return" is Valor and items. The daily cap holds across midnight, timezones and restarts (`PlayerRecord.java:54-56`, `Calendar.java:21-23`, saved before the reveal). The only extra spins are `/fortune admin reset` and a second account.

## 2. The exploit list

| # | Exploit or loop | Evidence | Size of the problem | Status |
|---|---|---|---|---|
| 1 | **Mint volume.** 320 ingots a day at full rate per player. | `Mint.java:50-65`, `SPEC.md:499-500` | 2,560 Sov a day kept; a keep is 32. Gold is plentiful once the Nether is on; overworld ore with Fortune III is about 22 Sov an ore today. | Real, open (EC-1) |
| 2 | **Treasury back door.** `/treasury withdraw` needs Lord-or-op only. | `TreasuryCommands.java:239-252`, `Treasury.java:87-90` | A Lord moves every member's tax to themselves with no vote, limit or Charter record. | Real, open (EC-3) |
| 3 | **Charter's own door is unlimited too.** `lordSpendLimitMarks -1`, per action, `Withdraw.to` any player; a personal SPEND grant survives leaving. | `Knobs.java:344`, `Withdraw.java:336`, `AuthorityResolver.java:69` | Same drain through the governed door; an ex-member in a rival kingdom can do it. | Real, open (CC-5, CC-1) |
| 4 | **War Pick for everyone.** Mining is not race locked; the pick is unbreakable diamond tier with Fortune III, Tunnel III, Silk Touch; the Dwarf bonus stacks past level III; nether gold ore is on the Dwarf list. | `Gate.java:57-61`, `Swings.java:34-37`, `Bonuses.java:69-81`, `azrak_fortune.json:9-11` | Free mining for all; about 10 Sov per nether gold block, 9 a swing, once the Nether is on. | Real, open (FO-2, FO-11, FO-12, EE-4) |
| 5 | **Wild dungeon gold.** About 23 ingots per tier 1 clear (230 Sov), 47 at tier 3 (470), golden apples by the stack; per lodestone cooldown, no per player limit; five near-spawn lodestones an evening. | `tools/gen_wild_loot.py:25-74`, `docs/DECISIONS.md:1456-1470` | About 1,000 Sov of gold a night for one crew in a world with the Nether off. Possibly the largest faucet live today. | Real, open, UNVERIFIED against live shop prices (PD-W5) |
| 6 | **Fortune stake as a Valor exchange.** +100 percent for 20 Sov is about 2.4 Sov per extra Valor; the tier persists and is re-charged each pull. | `Stake.java:34-43`, `PlayerRecord.java:23-24`, `FortuneService.java:221-225` | Gold farmers buy Valor the spec says cannot be bought; phone players pay a stake daily without seeing it. | Real, open (EC-5) |
| 7 | **Alt accounts and Renown.** 5 minutes of playtime gates the first spin; each alt earns about 8 Renown a day after probation; Xbox accounts are free. | `FortuneService.java:212-218`, `EarnService.java:203` | Ten alts: Hamlet in four days, Realm in six months, no play. | Real, open (EC-10) |
| 8 | **Crash duplication.** Withdraw, shop and stake are item-first inside a 15 s write debounce. | `JsonAccountStore.java:38-45`, `EconomyCommands.java:431-448`, `ShopPurchase.java:57-78` | A crash within 15 s: items kept, balance restored. The host crashes. | Real, open (EC-6) |
| 9 | **Coin to ingot recipe.** Blocked by `RecipeMeltMixin` on two `getRecipeFor` signatures; no gametest proves it applies on 26.3. | `RecipeMeltMixin.java:29-45` | If it misses: 9 Sov craft an ingot that strikes 10 (8 to you, 2 tax), bounded by the soft cap; about 320 Sov a day for a Lord who withdraws the tax. | Unproven, probably fine (EC-7) |
| 10 | **Villager demand removed.** Instant Restock resets uses every trade; Humans pay 10 percent less. | `VillagerMixin.java:19-23`, `RaceTraits.java:299-302` | One librarian sells Mending and Sharpness books forever at base price; emeralds are not Sovereigns, so the Mint is untouched, but emerald-priced goods are free for the patient. | Real, design choice to revisit (IR-1) |
| 11 | **npc_treasury faucet.** Razing a raider stronghold credits 8.25 to 33 Sov from nothing. | `SiegeSim.java:230`, `Knobs.java:323` | Small; contradicts "coin enters only at the Mint" (`SPEC.md:430`). | Accepted, list it in analytics (EC-13) |
| 12 | **Two Mint days in one session.** Currency's day is the host zone midnight; a US player strikes 320 before and 320 after. | `EconomySettings.java:56`, `AnalyticsRecorder.java:88` | Doubles the cap once a day for anyone awake at the boundary. | Real, config (EC-13) |
| 13 | **GoldGate loopholes.** `killed_by_player` counts a punch then lava within 5 s, TNT, tridents. | `zombified_piglin.json:85-94` | Moot while the Nether is off. | Accept (EC-14, GG-1) |
| 14 | **Silk Spawner farms.** 25 percent chance, non ops may place with the mob kept. | `BlockDrops.java:40-46`, `SpawnerPlacing.java:44-56` | Blaze and skeleton farms move into keeps; XP and drops, not coin. | By design (EE-1) |
| 15 | **Checked and clean.** Stack splitting and shift-click (`ItemBank.java:138-178` re-reads slots), cursor stacks (not slots 0 to 35), offhand (ignored on purpose), hoppers, dispensers, item frames, bundles, shulkers, villagers and piglins (the marked nugget moves unchanged), anvil (keeps the marker), grindstone, brewing, stonecutter, crafter, furnaces, smithing (all through `getRecipeFor`), death (items drop, balance untouched), disconnect mid command (server thread, atomic), two players at one Bank (one lock). The gold ingot shop loop is neutral for a Lord (buy 10, strike 8 + 2 taxed, withdraw 2) and loses 2 for everyone else, and spends soft cap. | economy review | No inventory dupe found. | Clean |

## 3. Tuning proposals (owner to set; all amounts in Sovereigns)

| Knob | File | Now | Proposed | Why |
|---|---|---|---|---|
| `mint.softCapIngots` | `config/chartercurrency/settings.json` | 320 (3,200 struck, 2,560 kept a day) | 64 (640 struck, 512 kept, 128 tax) | 512 a day still buys two enchanted books or 25 stacks of sand; a newcomer's first ingot is still worth 10; a kingdom of ten strikers banks 1,280 a day in tax |
| `mint.falloffEvery` | same | 320 | 64 | keeps the doubling curve's shape; a full inventory of gold strikes about 1,270 instead of 6,360 |
| `keepPriceMarks` | Charter `charter.json` | 2048 (32) | 20480 (320) | half a day of one striker's tax at the new cap; the knob comment's "five days of a ten member kingdom's tax" becomes true again |
| `outpostDepositMarks` / `outpostDepositStepMarks` | same | 256 / 128 (4 / 2) | 2560 / 1280 (40 / 20) | an outpost should cost an evening, not four coins; a captured outpost should be worth capturing |
| `custodyFineCapMarks` | same | 512 (8) | 5120 (80) | a fine must sting someone who banks 500 a day |
| `bailMarksPerHour` | same | 32 (0.5) | 320 (5) | same ratio to the cap as before |
| `npcTreasuryMarks` (three tiers) | same | 528 / 1056 / 2112 (8.25 / 16.5 / 33) | 2560 / 5120 / 10240 (40 / 80 / 160) | a stronghold must be worth the siege at the new scale; still about one Mint minute |
| `lordSpendLimitMarks` | same | -1 (no limit) | 6400 (100) per action, and enforced in `/treasury withdraw` too | closes the drain for everyday spending without a vote; a Lord who needs more proposes it |
| war declaration stake (new) | same | none | half the target's deposit (20 at the proposed deposit), forfeited on a lapse | a free declaration is a free grief (CC-2) |
| `shop.confirmAt.coin` | `settings.json` | 20480 (320) | 12800 (200) | at 512 a day income, 200 is "think twice"; give Bedrock a chest confirm |
| shop coin prices | `shop.json` | 10 to 200 per listing | leave for two weeks | already repriced for the 10 Sov ingot; retune from `snapshots.jsonl` shop lines |
| `fortune` earn source | `earn.json` | absent | `base 4, perLevel 2, softCap 60, hardCap 0, renownMultiplier 0.5` | ships the missing wire; 0.5 halves alt Renown while a real player's Valor is unchanged |
| Fortune `stakeTiers` | `config/fortune/fortune.json` | 5 / 10 / 20 for +25 / +50 / +100 percent | keep prices; reset to `none` after each pull; bonus on items only unless the owner wants coin to buy Valor | 20 Sov is two ingots: cheap for a farmer, a day's work for a newcomer |
| Valor listings | `shop.json` | 3 (40, 80, 150) | 8 to 10 from 20 to 300 Valor: trims, named tools, a title through a LuckPerms command listing | about 72 Valor a week from Fortune alone needs sinks or Valor is dead in a month |
| Renown catalogue | `renown_catalog.json` | five broadcasts | at least one entry Charter honours: a kingdom Haste hour, a keep Fortune machine, a second outpost allowance | Renown must do something members feel |
| `renown.perPlayerDailyCap` | `earn.json` | 120 | 120 plus a per kingdom per source daily cap (new) | alts |
| `timezone` | `settings.json` | "" (host zone) | the players' zone, `America/New_York` is the guess (UNVERIFIED) | the Mint day turns while the server sleeps, as Fortune's 08:00 UTC does |
| wild loot `SUPPLY_SCALE` and per tier gold | `tools/gen_wild_loot.py` (make them runtime knobs) | 0.5; about 23 / 47 ingots | 0.3 at tier 1 (about 14 ingots, 140 Sov) until the first week of clear analytics is read; a per player daily clear count that halves the barrel after 3 | the largest faucet live today should be measurable and tunable without a rebuild |
| Dwarf Fortune ore list | `bonuses/azrak_fortune.json` | includes nether gold ore | remove it | 10 Sov a block, nine a swing, no tool cost once the Nether is on |

Gear meets coin (design, not a knob): today weapon upgrades cost diamonds, gold blocks, netherite and amethyst; coin buys shop goods and stakes. Put a Sovereign cost on the tier 2 and tier 3 upgrade (for example 50 and 200 Sov paid at a Mint or Bank, or a Renown unlock the Lord buys for the kingdom), so the Mint, the Bank and the thing players actually want are in one loop (GP-4).

## 4. What the analytics can and cannot tell you

- `config/chartercurrency/analytics/events-<day>.jsonl` and `snapshots.jsonl` record every bank movement, strike, shop sale and Charter charge or credit, plus a daily supply snapshot with top ten, concentration and treasuries. Good enough to see inflation within a week.
- Not recorded: Fortune stakes (EC-4), pocket and chest coin by design (`Analytics.java:305-307`), Pocket Dungeons loot (no line per clear), villager trades. Charter transfers inflate both created and destroyed (EC-13). Currency and Fortune use different day boundaries.
- Read after one week live: struck per day and per player (is the cap binding), `destroyedBy.shop` (which listings sell), treasury balances (is the 20 percent tax meaningful), the Gini line (is wealth concentrating). If struck per day stays near 320 ingots for any player, the cap is too high; if shop sales are under a tenth of strikes, coin has no sink.
