# Charter Economy: Design Spec

> **Status:** design draft, 2026-10-31. Not implemented. Reviewed adversarially by a
> Minecraft lead designer pass (`REVIEW.md`); every change that review forced is
> marked **[R]** below. The build order is in `PLAN.md`.
>
> **Mod:** `chartereconomy`, display name **Charter Economy**. One standalone mod
> owns the whole system: bank, shop, Mint, kingdoms, charters and all three
> currencies. It is a sibling of `cobbleeconomy`, built the same way, and does not
> depend on it or change it (owner, 2026-10-31).
>
> **Server:** the Kingdoms (charter) server, a separate server from the cobblestone
> and diamond one (owner, 2026-10-31). Coin replaces cobblestone and diamonds here.
> Minecraft 26.2, Fabric, JDK 25, server-side only. Vanilla clients install nothing, the same rule as every mod in
> this suite (`docs/DESIGN.md`).

---

## 1. The one-paragraph pitch

Players mine metal and strike it into **coin** at a Mint. Coin buys things from
other players, from the shop, and keeps a kingdom running. When a player does
something brave (clears a dungeon, wins an event) they earn **Valor** for
themselves, and at the same moment their kingdom earns **Renown**. Valor buys
personal prestige; Renown is the only thing that raises a kingdom's **charter**
from a camp to a realm. A solo player is never punished, a dungeon runner is
never taxed, and a kingdom grows because its people go out and do things, not
because one member owns a gold farm.

## 2. The three currencies

The owner's brief: two currencies for players, one for kingdoms.

| | Coin | Valor | Renown |
|---|---|---|---|
| **Who holds it** | Players | Players | Kingdoms |
| **Physical?** | Yes: three denominations | No: bank balance only | No: kingdom ledger only |
| **Tradeable** | Yes (`/pay`, hand to hand) | No | No (spend-only) |
| **Earned by** | Minting metal, bounties, quests, trade | Dungeons, events | The same events as Valor, mirrored |
| **Spent on** | Shop, services, upkeep, player trade | Personal prestige and convenience | Charter tiers, kingdom perks |
| **Stored as** | Player bank balance | Player bank balance | Kingdom ledger |
| **Feels like** | Money | A medal | A kingdom's reputation |

### 2.1 Coin: one currency, three denominations **[R]**

Copper, iron and gold coins are **one currency** with fixed face values, not three
independent currencies.

| Denomination | Base item | Face value |
|---|---|---|
| Copper Mark | `minecraft:copper_nugget` + marker | 1 |
| Iron Crown | `minecraft:iron_nugget` + marker | 8 |
| Gold Sovereign | `minecraft:gold_nugget` + marker | 64 |

- The bank stores **one balance, counted in Marks**. Depositing counts every marked
  coin at face value. Withdrawing makes change in whatever denominations the
  player asks for (`/withdraw 3 sovereign`, `/withdraw 200 coin` gives the fewest
  coins).
- **Why a fixed rate is safe here when Cobble Economy forbids one.** Cobble Economy
  bans exchange rates because cobblestone and diamonds are different goods from
  different sources, so a rate gets arbitraged. Here every denomination comes out
  of the same Mint under the same cap (section 3). A Sovereign is 64 Marks in
  different packaging, not a different good, so there is nothing to arbitrage.
  This is the Wynncraft emerald ladder, and it works for exactly this reason.
- **Why not three independent coins.** Three currencies need three sets of sinks
  that only they pay for. On every server that tried it, one became the real money
  and the others died.
- **Identity is the marker, never the look.** Each coin carries
  `custom_data: {charter: {coin: "copper"|"iron"|"gold"}}` plus `item_name` and
  lore. An anvil cannot add `custom_data`, so a renamed plain nugget is worthless
  and the bank says so. This is the `SUITE_ITEMS.md` §6 rule.
- Display: `3 Sovereigns, 2 Crowns, 5 Marks (213 Marks)`. The total in Marks is
  always shown, so nobody has to do arithmetic.

### 2.2 Valor: the player's medal

- A bank balance with no item: it cannot be withdrawn or sent with `/pay`. It cannot be dropped, sold, given or stolen. That is what keeps
  it honest; anything that moves between accounts gets farmed by alts.
- Shown in `/balance` and on its own `/baltop valor` board, because a medal nobody
  sees is not a medal.
- **No weekly hard cap.** Hard caps turned WoW's Valor points into a chore list.
  Valor uses soft daily caps per source instead (section 5), with unused allowance
  carrying over for up to three days so a weekend player is not punished.

### 2.3 Renown: the kingdom's reputation

- Stored per kingdom in the charter ledger.
- **Mirrored, not split** **[R]**. One earning event mints both: Valor to the
  player and Renown to their kingdom. The player's share is never reduced. A
  zero-sum split creates tithe politics ("my kingdom took my reward"), and that is
  a reason to quit. Doubling the faucet is safe because Renown cannot be turned
  into anything a player can spend.
- **Spend-only.** Renown cannot be withdrawn, transferred, or converted. The worst
  a bad leader can do is spend it on the wrong thing; they can never steal it.

## 3. Minting: how coin enters the world

The owner's idea was craftable coins. The design keeps the fantasy (you turn
copper, iron and gold into their coin) but moves it from the crafting table to a
**Mint**, for two reasons the review confirmed against the code:

1. **A crafting recipe cannot be capped** without a mixin. An uncapped recipe makes
   whoever owns the best farm the central bank. Gold is the most farmable valuable
   in Java (a nether-roof piglin farm produces tens of thousands of nuggets an
   hour), so a gold-backed top coin would be the *least* scarce coin on the server.
2. **A recipe cannot check a marker.** Vanilla ingredients match an item or a tag,
   never `custom_data`, so any recipe that takes coins as input also takes plain
   nuggets. A recipe for coins is only safe as an output.

### 3.1 The Mint

- A Mint is a **bound block**: a smithing table an operator or a kingdom leader
  binds with `/charter mint bind` (the same pattern as ballot boxes and
  Cobble Economy shopkeepers). Right-click opens a chest menu that reads like a
  crafting grid: ingots in, coins out.
- Mint rates: **1 copper ingot = 1 Mark, 1 iron ingot = 1 Crown, 1 gold ingot =
  1 Sovereign.** The metal is the material cost; which metal you use is a
  convenience, not an advantage, because the cap below is counted in face value.
- **The Royal Mint** stands at spawn and is open to everyone, kingdom or not.
  A kingdom at Hamlet tier or above can bind its own Mint in its seat (a
  convenience, never a bigger allowance) **[R]**.

### 3.2 The minting allowance

| Band (face value per day) | Yield |
|---|---|
| First 192 Marks (3 Sovereigns' worth) | Full: 1 ingot per coin |
| Next 192 Marks | Half: 2 ingots per coin |
| Beyond 384 Marks | The Mint is closed to you until tomorrow |

- Unused full-rate allowance carries over for up to three days (576 Marks).
- The allowance is **per player, never per kingdom** **[R]**. Tying it to charter
  tier was a snowball: big kingdoms mint more, pay upkeep easily, and grow faster.
- Every strike is logged with the player, metal, coins and band, and the startup
  log prints total coin supply, as Cobble Economy does.

### 3.3 Melting

Coins can go back into the vanilla `9 nuggets -> 1 ingot` recipe; blocking that
would need a mixin. It is a deliberate leak with a floor: nine Sovereigns (576
Marks) become one gold ingot, so nobody melts coin by accident or for profit.

## 4. Kingdoms and charters

### 4.1 What a charter is

A kingdom exists because it holds a **charter**. Founding one costs coin, needs a
seat (a bound lectern, the kingdom's centre), and creates a vanilla scoreboard
team for the name colour and chat tag. The charter has a tier, a Renown ledger, a
coin treasury, and a member list with roles: **Sovereign** (leader), **Steward**
(officer), **Citizen**.

### 4.2 Active citizens **[R]**

Every number that scales with kingdom size counts **active citizens** only: members
with at least 2 hours of playtime in the last 14 days, read from vanilla
statistics. Raw member counts are where alt padding starts.

### 4.3 Charter tiers

Costs scale with active citizens, `cost = base * max(1, activeCitizens)^0.8`, so a
large kingdom progresses slightly faster than a small one but cannot run away.
Starting numbers, all in config and all expected to be retuned:

| Tier | Renown (base) | Coin (base, Sovereigns) | Min active citizens | Unlocks |
|---|---|---|---|---|
| Camp | 0 | 4 (founding fee) | 1 | Seat, team colour, chat tag, claim area 1 |
| Hamlet | 300 | 8 | 2 | Kingdom Mint, claim area 2, seat warp |
| Village | 900 | 16 | 3 | Claim area 3, kingdom banner on the seat, kingdom bounty board |
| Town | 2,000 | 32 | 4 | Claim area 4, territory aura (Haste I near the seat) |
| City | 4,000 | 64 | 6 | Claim area 5, second seat warp, kingdom shop listing slot |
| Realm | 8,000 | 128 | 8 | Claim area 6, Realm title, season crown eligibility in the top bracket |

Renown and coin spent on a tier are destroyed. Tier effects go out through config
command templates (`onTierUp`), so the claims mod, LuckPerms or scoreboard commands
do the work and this mod never names them.

### 4.4 Upkeep

- Due weekly, in coin, from the kingdom treasury: `base * activeCitizens^0.8`,
  starting at 1 Sovereign base for Camp and doubling per tier.
- The treasury is **deposit-only** **[R]**. Members hand coin in at the seat lectern;
  it can only be spent on upkeep and charter costs. A treasury a leader can
  withdraw from gets emptied the day that leader rage-quits.
- **Unpaid upkeep never removes protection** **[R]**. Towny's lesson: when failing
  upkeep unprotects builds, griefers keep a calendar. Unpaid upkeep pauses the
  convenience and cosmetic perks (aura, warps, Mint, banner) until paid. Claims and
  builds are untouched, forever.
- No superlinear penalty for size. That rule is what made Towny players found alt
  satellite towns.

### 4.5 Who can spend Renown **[R]**

- **Small spends** (perks under 10 percent of the next tier cost): the Sovereign or
  any Steward.
- **Tier upgrades and large spends:** two of Sovereign and Stewards approve, or a
  member vote once ballot supports a voter list (`PLAN.md` phase 5).
- Every spend is logged and readable by every member (`/charter ledger`).
- **Absent leader:** if the Sovereign has not logged in for 30 days, the most
  active Steward inherits the role automatically.

## 5. Earning: the faucets

### 5.1 One earning endpoint

Every Valor and Renown award goes through one operator-only command:

```
/charter earn <player> <source> [level] [run]
```

Charter Economy owns every anti-abuse rule in one place: caps, probation, run
de-duplication. Valor goes to the player's bank and Renown to their kingdom's
ledger in the same transaction, so the two can never drift apart. Faucet mods only
need a command template, which is the pattern pocketdungeons' `payoutCommand`
already uses; none of them imports Charter Economy.

### 5.2 Sources and starting numbers

| Source | Valor | Renown | Soft cap (Valor per day) |
|---|---|---|---|
| Dungeon run settled (`dungeon`) | 10 + 2 per keystone level | same as Valor | 150, then half |
| Dungeon capstone or boss first clear (`capstone`) | 50 | 50 | once per dungeon per week |
| Quiz round win (`quiz`) | 15 | 15 | 60 |
| Bounty completed (`bounty`) | 10 | 10 | 40 |
| Build competition placing (`buildoff`) | 100 / 60 / 30 | same | per event |
| Server event participation (`event`) | 20 | 20 | per event |

Coin faucets (paid as physical coin items, the suite's normal way): bounties,
daily quests, the chat donkey's gift, dungeon reward chests, Wayfarer traders.

### 5.3 Renown rules **[R]**

- **Per player cap:** each active citizen can generate at most 120 Renown a day.
- **Once per run per kingdom:** four alts in one dungeon party earn the kingdom one
  credit, not four. Needs `%uuid%` and `%run%` placeholders in pocketdungeons
  (`PLAN.md` phase 3).
- **Probation:** a new member generates no Renown for their first 7 days or until
  they have 4 hours of playtime, whichever is later.
- **Credited on earning, never moves:** leaving a kingdom takes nothing with you.
  After leaving, a player waits 7 days before joining another kingdom.
- **Kingdomless players** keep full Valor. Their Renown goes to the **Commons**, a
  server-wide pot that funds a monthly server prize (ROADMAP P3.3). It is never
  held in escrow for the next kingdom they join, because escrow launders hopping.

### 5.4 Target income for an active player (about 2 hours a day)

| Currency | Per day | Per week |
|---|---|---|
| Coin | 400 to 600 Marks (6 to 9 Sovereigns) | about 50 Sovereigns |
| Valor | 100 to 200 | 800 to 1,200 |
| Renown generated for the kingdom | 100 to 120 | about 750 |

A five-citizen kingdom of active players reaches Hamlet in its first week, Town in
about a month, and Realm in about a season. A solo kingdom gets to Village in
about a month. Those are the dials to watch.

## 6. Sinks: what each currency buys

### 6.1 Coin (the general shop and player trade)

| Tier of goods | Priced in | Examples |
|---|---|---|
| Everyday | Marks | Building blocks (ice, sand, terracotta, quartz), dyes, food |
| Working | Crowns | Tools, enchanted books, potions, rockets, services |
| Capital | Sovereigns | Charter founding and tiers, upkeep, power items: Up Up And Bye Boots, Big Hole Pick and Shovel, Spirit Stones |

Power items are priced in coin, never Valor **[R]**: a Valor shop that sells power
stops being a medal and becomes a grind.

### 6.2 Valor (prestige and convenience)

What a vanilla client can show without a resource pack:

- Titles (scoreboard prefix or placeholder tag), name colour within your team
- Armour trim templates, banner patterns, custom-named player heads
- Particle trails (server-side particles), firework kits
- Convenience Wondrous items that save time without adding power: pocket crafting
  table, pocket smelter, pocket disenchanter
- Spirit wolf cosmetics, a rotating "this week only" Valor shelf (ROADMAP P2.2)

Starting prices: 150 for a title, 300 to 600 for trims and patterns, 1,000 to
3,000 for a convenience Wondrous item. The weekly shelf is where the chase lives.

### 6.3 Renown (the charter catalogue)

Charter tiers first. Then perks, bought by the kingdom and paused by unpaid upkeep:
an extra seat warp, a territory aura upgrade, a kingdom monument banner, an extra
kingdom shop slot, a kingdom bounty board posting.

## 7. The loops

| Loop | What the player does | What they feel |
|---|---|---|
| **Session** | Mine, mint, build, trade; run a dungeon for Valor and loot | "I made money" and "I did something brave" |
| **Daily** | Fill today's mint allowance; bounties and quests; one dungeon run | A short, finishable list, with carry-over so missing a day costs nothing |
| **Weekly** | Kingdom upkeep (Sunday), the Valor shelf rotates, a scheduled event or build-off | "Our kingdom is moving" |
| **Season (8 weeks)** | Climb the Valor board; your kingdom climbs its bracket | A title and a crown. The world never resets |

Season rankings are split into **brackets by charter tier** and scored on Renown
earned **per active citizen** **[R]**, so a five-person Village can win its bracket
and the biggest kingdom does not win every season.

## 8. What stops it going wrong

| Threat | What stops it |
|---|---|
| Gold or iron farm prints money | Minting is capped by face value per player per day; the metal does not matter |
| Counterfeit coins | Identity is a `custom_data` marker; anvils cannot add it; recipes never take coins as input |
| Alts farming Valor | Valor cannot leave the account it was earned on |
| Alts farming Renown | Per player daily cap, once per run per kingdom, 7 day probation, active citizen counting |
| Kingdom hopping | Renown never follows a player; 7 day wait between kingdoms; no escrow |
| Leader steals the treasury | Treasury is deposit-only; Renown is spend-only |
| Upkeep griefing of inactive kingdoms | Unpaid upkeep pauses perks only; protection never lapses |
| Big kingdom snowball | Costs and upkeep scale with `activeCitizens^0.8`; season brackets by tier |
| Inflation | Every shop, tier, perk and upkeep payment destroys currency; supply is printed at startup and in `/baltop` |
| Chores | Soft caps with three day carry-over, no weekly hard caps |

## 9. Adjacent mods

**Cobble Economy: a sibling, not a dependency.** Charter Economy starts from a copy
of Cobble Economy's proven pieces (the pure-Java `core` with `Wallet`,
`EconomyService` and the purchase transaction and their tests; the atomic
`accounts.json` store; the shop, shop editor and dialogs), renamed into its own
package and then extended with denominations, markers, Valor, the Mint and
charters. The suite already prefers a few duplicated files over a shared library
every mod must version against (`docs/DESIGN.md` §2). The two mods never run on the
same server, share no config, and Cobble Economy is not changed.


**Claims: adopt, do not build.** Protecting land from explosions, pistons, fluids,
fire and mob griefing has no Fabric API events, so a home-made claims system means
mixins. Candidates (26.2 availability unverified for all of them): **Open Parties
and Claims** and **Flan** (both vanilla-client friendly), **Get Off My Lawn**.
FTB Chunks expects a client install, so it is out. Kingdom perks reach the claims
mod through `onTierUp` command templates; if a mod needs to ask "may this player
build here", **Common Protection API** is the seam that does not name the claims
mod.

**Also useful:** vanilla scoreboard teams (colour, tag, team chat, no mod needed),
Placeholder API (kingdom and title tags in chat), LuckPerms (rank meta).

**Suite mods that plug in:**

| Mod | Role on the charter server |
|---|---|
| pocketdungeons | Main Valor and Renown faucet through `payoutCommand` |
| quizengine, bounties | Valor faucet via a command template; coin rewards as configurable items |
| ballot | Build-offs and season prizes now; kingdom votes once it has a voter list |
| wayfarers | Traders priced in coin; sell kingdom cosmetics by suite tag |
| wondrous, spiritwolves | Sink targets: power in coin, convenience and cosmetics in Valor |
| chatdonkey | Small coin gift |

## 10. Open questions for the owner

1. **Which claims mod?** Open Parties and Claims is the recommendation, pending a
   26.2 build.
2. **Denominations 1:8:64, or 1:10:100?** Eights match a Minecraft stack (64 Marks
   is a stack of copper and one Sovereign); tens are easier mental arithmetic.
3. **Should a kingdom's Mint pay a small fee into its treasury?** The review argued
   against any fee a leader controls. Since the treasury is deposit-only, a fee is
   safe; the question is whether players will mint at home at all with a fee.
4. **PvP and war between kingdoms:** out of scope here. If wanted, Renown is the
   natural stake, and that is its own design pass.
