# Charter Economy: Design Spec

> **Status:** design draft, 2026-10-31. Not implemented. Reviewed adversarially by a
> Minecraft lead designer pass (`REVIEW.md`); every change that review forced is
> marked **[R]** below. The build order is in `PLAN.md`.
>
> **Mod:** `chartereconomy`, display name **Charter Economy**. One standalone mod
> owns the whole system: bank, shop, Mint, kingdoms, charters, land claims and
> all three currencies. It is a sibling of `cobbleeconomy`, built the same way, and does not
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
| **Earned by** | Minting metal, dungeon chests, trade | Dungeons, events | The same events as Valor, mirrored |
| **Spent on** | Shop, services, upkeep, player trade | Personal prestige and convenience | Charter tiers, kingdom perks |
| **Stored as** | Player bank balance | Player bank balance | Kingdom ledger |
| **Feels like** | Money | A medal | A kingdom's reputation |

### 2.1 Coin: one currency, three denominations **[R]**

Copper, iron and gold coins are **one currency** with fixed face values, not three
independent currencies. The ratio is 1:8:64 (owner, 2026-10-31): a stack of 64
Marks is one Sovereign.

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
- **Minting is free.** No fee, at the Royal Mint or a kingdom's. The allowance
  already controls supply, the metal is already the cost, and a fee would only
  push players back to the Royal Mint and make the Hamlet perk worthless.

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

A kingdom exists because it holds a **charter**. The roster is fixed: the
server's existing kingdoms are the kingdoms, only admins can create one, and more
are unlikely (owner, 2026-10-31). Players choose which kingdom to join; they never
found their own. Each charter has a seat (a bound lectern at the kingdom's centre)
and a vanilla scoreboard team for the name colour and chat tag. The charter has a tier, a Renown ledger, a
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
| Camp | 0 | 0 (every kingdom starts here) | 1 | The 9x9 plot and its protection, seat, team colour, chat tag |
| Hamlet | 300 | 8 | 2 | Seat warp, a title card when anyone enters the plot, Kingdom Mint |
| Village | 900 | 16 | 3 | Respawn at the seat, kingdom banner on the seat, ally pacts |
| Town | 2,000 | 32 | 4 | Haste I inside the plot, a kingdom shop stall |
| City | 4,000 | 64 | 6 | No natural hostile spawns inside the plot (to verify without a mixin; else swapped for another perk), second warp |
| Realm | 8,000 | 128 | 8 | Realm title, plot border drawn in the kingdom's colour, season crown eligibility in the top bracket |

**Land never grows with tier** (owner, 2026-10-31). Every kingdom has the same
9x9 plot from Camp to Realm, so tiers buy depth (what the plot does) instead of
width (how big it is). A kingdom climbs because its people play, and a Realm and a
Camp still stand on equal ground.

Renown and coin spent on a tier are destroyed. Claims and the in-mod perks are
applied directly. Anything outside the mod goes out through config command
templates (`onTierUp`), so LuckPerms or scoreboard commands
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

### 4.6 Claims: the charter is the deed

Charter Economy is partly a claims mod (owner, 2026-10-31): a kingdom's land is
part of its charter, not a separate plugin's business. This overrules the
review's "adopt a claims mod" advice; section 9 records why that is workable.

- **One fixed plot per kingdom: 9 by 9 chunks** (144 by 144 blocks), centred on
  the seat's chunk, the same for every kingdom at every tier. This is how the
  Kingdoms server runs today (owner confirmed 9x9 chunks), and the spec keeps it.
- **Existing plots are registered, not created.** On install an admin runs
  `/charter admin register <name> <leader>` standing at each kingdom's current
  centre, so nobody moves and no land changes hands. The same command is the only
  way a new kingdom could ever appear. Registration refuses a plot that overlaps
  another.
- **No per-chunk price.** With fixed plots there is nothing to buy chunk by chunk,
  so the coin sinks are building materials in
  the shop (a fixed plot fills up, so kingdoms build up and build dense), upkeep
  and tiers (section 6.1). Watch total coin supply closely in the first weeks.
- **Who may do what** inside a claim, by role, with per-kingdom toggles: build and
  break, open containers, use doors and redstone, hurt animals, ride. Citizens get
  everything by default; outsiders get nothing; **allies** (a kingdom-to-kingdom
  pact, both sides agree) get doors and doors only unless granted more.
- **Visible borders.** Walking into a plot shows the kingdom's name on the action
  bar in its team colour; `/charter map` prints the surrounding chunks in chat;
  `/charter plot show` draws the border with particles for 10 seconds.
- **Kingdomless players** own no protected land. They can still mint, trade, run
  dungeons and earn Valor; joining a kingdom is how you get a home behind walls.
- **Protection never lapses for unpaid upkeep** (section 4.4). A kingdom whose
  every member has been inactive for 60 days becomes **ruined**: its claims stay
  protected against building and breaking, but its containers open to anyone. The
  builds are never deleted; what was left inside becomes loot. With a fixed roster
  a ruined kingdom is a real loss to the server, so the timer is off by default
  and admins switch it on per kingdom if they want it.

What protection covers, and how:

| Threat | How it is stopped | Mixin? |
|---|---|---|
| Players breaking, placing, using blocks, hitting animals or item frames | Fabric API events (`PlayerBlockBreakEvents`, `UseBlockCallback`, `AttackBlockCallback`, `UseEntityCallback`, `AttackEntityCallback`) | No |
| Creeper and ghast block damage | The `mobGriefing` game rule, server-wide | No |
| TNT and other explosions from outside a claim | Filter the explosion's block list to drop claimed blocks | Yes, one |
| Pistons pushing across a claim border | Cancel the move if any block crosses into a different owner's claim | Yes, one |
| Lava and water flowing across a claim border | Cancel the spread when source and target differ in owner | Yes, one |
| Fire spreading into a claim | Cancel ignition of a claimed block from an unclaimed source | Yes, one |

Four small, single-purpose mixins, each behind a config switch, are the
justification `docs/DESIGN.md` asks for: without them a claim is a sign, not a
wall. They are the only mixins in the mod.

### 4.7 Limited war: what it pays and costs

Limited war between kingdoms is in scope for the server, but it can never touch a
kingdom's 9x9 plot (owner, 2026-10-31). How war is fought (when, where, how a
side wins) is its own design and not part of this spec. This section only defines
the currency hooks, so whatever war system is built plugs into the economy the
same way dungeons do.

**Holdings** are the thing a war is fought over: a site in the open land between
kingdoms (a watchtower, a ruined fort, a mine head) that one kingdom controls at a
time. Kingdoms take Holdings from each other; nobody ever takes a plot.

| Hook | Currency | Starting number |
|---|---|---|
| Declaring a war on a Holding | Renown, destroyed (same approval rule as a large spend, section 4.5) | 150 |
| Controlling a Holding, paid daily to the kingdom | Renown | 40 per Holding |
| Controlling a Holding, paid daily to the treasury | Coin (new money, helps pay upkeep) | 2 Sovereigns per Holding |
| Taking part in a war | Valor and Renown through `/charter earn` (source `war`) | 30, winners 50 |

Every Holding adds a daily payout, so the number of Holdings is an economy dial:
total coin supply should be checked whenever one is added.

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
| Build competition placing (`buildoff`) | 100 / 60 / 30 | same | per event |
| Server event participation (`event`) | 20 | 20 | per event |
| Took part in a war (`war`) | 30, winners 50 | same | per war |
| Holding a Holding (`holding`, paid daily to the kingdom) | none | 40 per Holding | n/a |

Coin faucets: the Mint, coin items in dungeon reward chests, and trade. Other suite
mods (bounties, quizengine, chatdonkey) are not on the charter server; if one is
added later, it joins through the same `/charter earn` template and a source row
here.

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
| Everyday | Marks | Building blocks (ice, sand, terracotta, quartz, glass, concrete), dyes, food. The biggest coin sink on a fixed-plot server: every kingdom builds up, not out |
| Working | Crowns | Tools, enchanted books, potions, rockets, services |
| Capital | Sovereigns | Charter tiers, upkeep, power items: Up Up And Bye Boots, Big Hole Pick and Shovel, Spirit Stones |

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
an extra seat warp, a plot aura upgrade, a kingdom monument banner, an extra
kingdom shop stall. And **war declarations** on a Holding (section 4.7). Never extra land: the 9x9 plot is the same for everyone.

## 7. The loops

| Loop | What the player does | What they feel |
|---|---|---|
| **Session** | Mine, mint, build, trade; run a dungeon for Valor and loot | "I made money" and "I did something brave" |
| **Daily** | Fill today's mint allowance; one dungeon run; tend the kingdom's land | A short, finishable list, with carry-over so missing a day costs nothing |
| **Weekly** | Kingdom upkeep (Sunday), the Valor shelf rotates, a war over a Holding, a scheduled event or build-off | "Our kingdom is moving" and "we took the tower" |
| **Season (8 weeks)** | Climb the Valor board; your kingdom climbs its bracket | A title and a crown. The world never resets |

With a fixed roster the same kingdoms meet every season, so rivalries build over
time; that is the point of keeping the roster small. Season rankings are split into **brackets by charter tier** and scored on Renown
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
| Big kingdom snowball | Every kingdom has the same 9x9 plot, so size cannot buy land; costs and upkeep scale with `activeCitizens^0.8`; season brackets by tier |
| Land grabs and alt kingdoms | The kingdom roster is fixed and only admins can add one; land is never bought |
| Inflation | Every shop, tier, perk and upkeep payment destroys currency; supply is printed at startup and in `/baltop` |
| Chores | Soft caps with three day carry-over, no weekly hard caps |
| War spam | Declaring costs Renown, destroyed; plots are never at stake |

## 9. Adjacent mods

**Cobble Economy: a sibling, not a dependency.** Charter Economy starts from a copy
of Cobble Economy's proven pieces (the pure-Java `core` with `Wallet`,
`EconomyService` and the purchase transaction and their tests; the atomic
`accounts.json` store; the shop, shop editor and dialogs), renamed into its own
package and then extended with denominations, markers, Valor, the Mint and
charters. The suite already prefers a few duplicated files over a shared library
every mod must version against (`docs/DESIGN.md` §2). The two mods never run on the
same server, share no config, and Cobble Economy is not changed.


**Claims: built in, not adopted.** The review recommended adopting Open Parties
and Claims or Flan because environmental protection has no Fabric API events. The
owner chose to make claims part of the charter (section 4.6). The cost is the four
small mixins listed there; the gain is one mod that owns land, money and kingdoms
together, with no outside mod to wait on for a 26.2 build. If another mod ever
needs to ask "may this player build here", Charter Economy can register with
**Common Protection API** (if it has a 26.2 build) without that mod naming it.

**Also useful:** vanilla scoreboard teams (colour, tag, team chat, no mod needed),
Placeholder API (kingdom and title tags in chat), LuckPerms (rank meta).

**Suite mods that plug in:**

| Mod | Role on the charter server |
|---|---|
| pocketdungeons | Main Valor and Renown faucet through `payoutCommand` |
| ballot | (if added) Build-offs and season prizes now; kingdom votes once it has a voter list |
| wayfarers | (if added) Traders priced in coin; sell kingdom cosmetics by suite tag |
| wondrous, spiritwolves | Sink targets: power in coin, convenience and cosmetics in Valor |

## 10. Open questions for the owner

1. **Ruined kingdoms:** off by default. If switched on, 60 days of total
   inactivity opens a kingdom's containers but never its blocks. Wanted at all?
2. **Plot expansion (parked):** the owner will revisit it only if players ask for
   permanent expansion in numbers (2026-10-31). If it comes back, the lever is a
   Renown perk that adds one ring of chunks, with a hard cap. Not in the build.
