# Fortune's Reels: a daily login bonus for the kingdom server

Draft spec, 2026-11-03. Not built. Owner decisions are marked **Decide** and collected at the end.

A separate server-side Fabric mod (`fortune`, Minecraft 26.3, fabric_api 0.165.0+26.3) designed for the kingdom
server (d212ecae). Once a day each player pulls a three-reel slot machine. Every pull pays, the week is laid out in
front of them, their kingdom gains from it, and the big wins are seen by everyone. Java and Bedrock (Geyser) players
get the same machine. Vanilla clients install nothing.

## 1. What it is for

The goal is a server that stays alive: players who come back tomorrow, who log in at the same time as their friends,
and who come back after a break. The machine is the excuse to log in; Charter is the reason to stay. So:

- **A reason to log in, not a replacement for play.** A day's spin is worth about one modest dungeon run in Valor
  (section 6), never more than an evening of play.
- **Forgiving, never punishing.** No streak that resets on one missed day (the same rule as the old dailyquests:
  a streak survives a gap of up to a week). Players who feel their run is doomed quit; players who feel it is worth
  protecting come back.
- **Social over solo.** Kingdom muster, visible wins and Discord posts do more for a small server than any reward
  size. Concurrency is what new players judge a server by.
- **Fair.** No paid spins, ever. The odds are on the screen. Every pull pays something. The outcome is decided before
  the reels move, and the animation never fakes near misses.

## 2. Research behind the design

Most published material on login rewards is vendor documentation and commentary rather than controlled studies, so
treat the numbers in it as claims. The patterns below recur across sources and match what this workspace already
learned from dailyquests.

| Finding | Where | What we take from it |
|---|---|---|
| A visible 7 day calendar that starts small and ends on a rarer reward pulls players back through the week | [Game Wisdom](https://game-wisdom.com/general/xp-logging-age-daily-gaming-habits), [Unity Daily Rewards](https://docs.unity.com/en-us/services/solutions/daily-rewards.md) | The week strip across the top of the machine, with a Golden Spin on day 7 |
| Calendars that wait for the player (a missed day keeps today's reward) feel fair | [Unity Daily Rewards](https://docs.unity.com/en-us/services/solutions/daily-rewards.md) | The calendar advances per claim, never per date |
| A new player calendar whose gifts teach the game's systems helps onboarding | [GameRefinery](https://gamerefinery.com/keep-your-players-coming-back-introducing-onboarding-best-practices-part-2) | The First Week calendar (section 5.3) |
| Brittle streaks drive quitting; freezes and grace days keep players | [VP0](https://vp0.com/blogs/daily-check-in-calendar-ui-mobile-app), [Duolingo](https://blog.duolingo.com/how-duolingo-streak-builds-habit) | Streak grace of 7 days, plus a comeback spin |
| A guaranteed base plus a random bonus is the common shape | [Nerdbot](https://nerdbot.com/2026/08/18/the-psychology-behind-daily-rewards-in-video-games/) | Every pull pays; the reels decide the bonus |
| Selling streak protection monetises the loss aversion the design creates | [Nerdbot](https://nerdbot.com/?p=293784) | Nothing in this mod is ever sold |
| Rewards should add to play, not be the main reason to log in | [Game Wisdom](https://game-wisdom.com/?p=32285) | Payout sized against a dungeon run |
| Minecraft servers pair daily streak rewards with milestones (7, 30, 100) and monthly community goals | [Daily Streak Rewards](https://builtbybit.com/resources/daily-streak-rewards.115868/), [Loka wiki](https://wiki.lokamc.com/w/Voting) | Milestone broadcasts and the kingdom muster |
| Crate plugins animate chest GUIs and report they show to Bedrock players through Geyser | [Phoenix Crates](https://www.phoenixplugins.com/docs/phoenix-crates/main-features/animations/opening-animations/readme) | A chest GUI machine; Bedrock smoothness to be checked live |

## 3. How a player meets it

1. **On join**, after the same short delay `LoginSnapshot` uses (so it lands below the MOTD and pack prompt):
   - Java: one chat line, `Your reels are loaded. [Spin] [Later]`, and the `block.note_block.chime` sound. Clicking
     Spin opens the machine. A countdown line instead when today's spin is used: `Next spin in 6 h 12 min.`
   - Bedrock: chat links cannot be clicked, so Floodgate players get a Cumulus modal form: "Your reels are loaded."
     with **Spin** and **Later**. Spin opens the machine.
2. **Anywhere**: `/spin` (alias `/daily`) opens the machine.
3. **At a Fortune Machine**: any block an operator registers (`/fortune admin machine here`, `add`, `remove`, `list`,
   as Charter's terminals). Suggested: one in the hub and one beside each keep's Nexus. It sparks with end rod
   particles (the particle Geyser already shows for Charter's pads). Right-click opens the machine. A win pulled at a
   machine plays its particles and sounds there for everyone nearby, so the hub has a place where people are seen
   winning. No floating label: display entities break Geyser.

## 4. The machine (one chest GUI for both clients)

A 9 by 6 sgui chest, the same library Charter Currency's shop uses, so it already works for Java and Bedrock
players. Only vanilla items and Foundry items already in the Bedrock pack (such as `kingdoms:gold_coin`) are drawn,
so Bedrock shows every symbol.

```
row 0  [Mon][Tue][Wed][Thu][Fri][Sat][GOLD ]  [   ] [STREAK]
row 1  [ = ][ = ][ ▼ ][ = ][ ▼ ][ = ][ ▼ ][ = ][ = ]
row 2  [ = ][ = ][ A ][ = ][ B ][ = ][ C ][ = ][ = ]
row 3  [ ► ][ = ][ A ][ = ][ B ][ = ][ C ][ = ][ ◄ ]    payline
row 4  [ = ][ = ][ A ][ = ][ B ][ = ][ C ][ = ][ = ]
row 5  [ODDS][   ][MUSTER][ ][LEVER][ ][ANNALS][ ][CLOSE]
```

- **Row 0, the week.** Seven slots. Claimed days show the symbol that day landed on, enchanted. Today glows (an
  enchanted item) with "Today" in its name. Future days are grey glass with the day's reward floor in their lore.
  Day 7 is a gold block: the Golden Spin. Slot 8 is the streak: a candle whose count is the streak (capped display
  at 64), lore with the streak, the best streak and "Your streak is safe until <date>."
- **Rows 2 to 4, the reels.** Three columns (2, 4, 6) of three symbols each; the middle row is the payline, marked by
  yellow glass arrows at both ends. Black glass frames everything else.
- **Row 5, the buttons.** The lever (`minecraft:lever`, "Pull the lever"); Odds (a book; the full paytable, section 6,
  with real percentages); Muster (the player's kingdom banner, section 7); Annals (a clock; this week's biggest wins
  on the server); Close.

### 4.1 The spin

1. The player pulls the lever. The server rolls the outcome, writes it to the player's state and pays it **before**
   any animation, then saves. Closing the window, logging out or a server crash mid spin loses nothing and cannot
   be used to re-roll.
2. The reels scroll: each column shifts its symbols down one row per frame. Reel 1 stops at about 1.5 s, reel 2 at
   2.2 s, reel 3 at 3 s, each slowing over its last frames (frame gaps of 2, 2, 2, 3, 3, 4, 5, 7 ticks), and each
   lands on the rolled symbol.
3. Sounds: a `note_block.hat` tick per frame with rising pitch; `note_block.bell` as each reel stops; on a win,
   `entity.player.levelup`; on a Golden Spin or jackpot, `ui.toast.challenge_complete` and a firework at the player
   (and at the machine, when pulled at one).
4. The reveal: a title and subtitle (both clients show them), for example **Three Diamonds** / "+24 Valor and a
   diamond". The payline symbols are enchanted so they glow. A chat line repeats it.
5. A second press of the lever skips to the result.
6. Filler symbols on the reels are drawn from the symbol weights, never placed to fake a near miss. A jackpot symbol
   showing above or below the payline happens only as often as chance puts it there.

### 4.2 Java and Bedrock

| | Java | Bedrock (Geyser and Floodgate) |
|---|---|---|
| Join prompt | Clickable chat line | Cumulus modal form |
| Machine | sgui chest | Same sgui chest, translated by Geyser |
| Animation | Every 2 ticks at full speed | `frameTicksBedrock`, default 3, so Geyser is not flooded with inventory updates |
| Reveal | Title, sounds, firework | Same |
| Fallback | | `bedrockMode: "form"` skips the chest and shows a Cumulus form with the three symbols in words and the payout |

Floodgate and Cumulus are `compileOnly`, loaded only when Floodgate is present, the same pattern as Charter's
`FloodgateAdapter`. Java dialogs are not used for the machine because they cannot animate.

Unverified until a live check: how smooth the scroll looks on a Bedrock console and phone through Geyser, and that
enchanted glint shows on Bedrock in a container.

## 5. Calendar, streak and comeback

### 5.1 The day

A day runs from `rolloverHourUtc` to the next. **Decide** the hour: pick the server's quietest hour, so nobody
loses a spin mid session. One spin a day; the Second Wind (5.4) is the only extra.

### 5.2 The week and the streak

- **The week advances per claim, not per date.** Miss Tuesday and Wednesday is still day 2. Day 7 is the Golden
  Spin, after which the strip starts again at day 1.
- **The streak counts claims.** It survives a gap of up to `streakGraceDays` (default 7, as dailyquests). Only a
  longer gap resets it, and then the comeback spin softens it.
- **The reward floor rises with the week:** days 1 and 2 pay at least the no match line; days 3 to 6 at least a
  pair; day 7 at least three of a kind (the Golden Spin uses its own reel weights, section 6).
- **Milestones** at 7, 30, 60, 100, 200 and 365 claims: a server broadcast, a Discord post, and a keepsake item
  (a named, enchanted vanilla item; for example a "Charm of the Hundredth Day" heart of the sea). **Decide** whether
  milestones also give a Charter title.

### 5.3 The First Week (new players)

A player's first seven claims follow a fixed calendar whose gifts point at Charter's systems, each with a one line
tip (in chat on Java, in the reveal form on Bedrock):

| Claim | Gift | Tip |
|---|---|---|
| 1 | 16 bread, 16 torches | "Pick a kingdom at the hub terminal, or ride as a mercenary." |
| 2 | Iron pickaxe | "Your race has its own weapon. Check your recipe book." |
| 3 | 8 iron ingots | "Every keep has a lectern terminal by its Nexus." |
| 4 | Pair floor and a Valor bonus | "Valor buys from /shop. Coin is struck at a Mint." |
| 5 | Ender pearls (4) | "Pads in the hub take you to the keeps." |
| 6 | Pair floor | "Spin with your kingdom: muster bonus." |
| 7 | Golden Spin | "Back tomorrow: the reels never close." |

The reels still spin and the payout still applies; the gift is added on top. **Decide** the exact items with the
owner (the table is a first pass).

### 5.4 Second Wind (optional, recommended on)

After `secondWindMinutes` (default 20) online in the same day, the player gets one half value spin and a chat or
form nudge. It rewards staying on, which is what makes the server look busy to whoever joins next. It never stacks
and is lost at rollover.

### 5.5 Welcome Back

A player who has been away `comebackDays` (default 14) or longer gets a Welcome Back spin on their return: at least
three of a kind, a chat line "The kingdom kept your seat warm.", and their old best streak shown so they know what
to chase. This is the strongest lever for a small server: lapsed players are easier to win back than new ones are
to find.

## 6. Symbols, odds and payout

Payouts go through Charter Currency's existing award path, never a new faucet:

```
chartercurrency earn <player> fortune <level> fortune-<date>-<player>
```

A new `fortune` source in `earn.json`: `base 4`, `perLevel 2`, `softCap 60`, `renownMultiplier 1.0`. The `run` id is
per player per day so each spin credits the kingdom separately. This gives Valor (which cannot be withdrawn or paid,
so alts gain nothing by funnelling it), the per-source soft cap, and Renown for the player's kingdom (which already
waits 7 days of membership). If Charter Currency is absent the mod still runs and pays only item rewards.

**No Coin by default.** Coin enters the world only at a Mint; a daily machine printing Marks would be a second
faucet. **Decide** whether a small Coin line is ever wanted (it would be `rewardCommands`, off by default).

Symbols, lowest to highest (all render on Bedrock):

| Symbol | Item |
|---|---|
| Bread | `minecraft:bread` |
| Iron | `minecraft:iron_ingot` |
| Gold | `minecraft:gold_ingot` |
| Emerald | `minecraft:emerald` |
| Diamond | `minecraft:diamond` |
| Crown | `kingdoms:gold_coin` (falls back to `minecraft:nether_star` if Foundry content is absent) |

Normal spin paytable (first pass, all in `fortune.json`):

| Result | Chance | Level | Valor at the starting numbers | Extra |
|---|---|---|---|---|
| No match | 46% | 1 | 6 | |
| Any pair | 36% | 2 | 8 | |
| Three Bread or Iron | 11% | 4 | 12 | 4 iron ingots |
| Three Gold or Emerald | 5.5% | 6 | 16 | 2 emeralds |
| Three Diamonds | 1.3% | 10 | 24 | 1 diamond, server broadcast |
| Three Crowns | 0.2% | 16 | 36 | jackpot: broadcast, Discord, firework, keepsake |

Expected value about 8 Valor a day, close to one low level dungeon run (`dungeon` is base 10, perLevel 2). The
Golden Spin uses a table with no "no match" and no pair line (three of a kind guaranteed, Crowns at 2%). The Second
Wind pays half the level, rounded down, minimum 1.

The roll is a server side weighted pick of the result line first, then symbols that make that line, then filler. The
Odds button shows this table with the live percentages, read from the same config the roll uses.

## 7. Kingdom muster

Each day, when `musterSize` (default 3) members of the same kingdom have spun, every member who spins that day gets
a muster bonus: one extra level on their spin (paid at once to those who already spun) and a server line
"Azrak mustered: 3 of their people answered the reels today." The Muster button shows today's count for the
player's kingdom and the others ("Azrak 4, Aurelia 2, Azurius 1").

- Kingdom comes from the `race:<kingdom>` permission node through the Fabric permission API (Charter's `RaceNodes`
  answers it), so this mod imports nothing from Charter. Mercenaries muster with their race's kingdom; a player with
  no race skips the muster.
- Renown already flows through the `fortune` source, so the muster gives kingdoms a daily reason to nag their members
  to log in, which is the social pull the server needs.
- **Decide** `musterSize`: 3 suits the current population; raise it as the server grows.

## 8. Discord and broadcasts

- Server chat: Three Diamonds, jackpots, milestones and musters. One line each, with the player's kingdom tag
  colour if the config gives one.
- Discord: jackpots, milestones of 30 or more, and a daily summary at rollover ("Yesterday 14 players spun. Azrak
  mustered. Best pull: Three Crowns by Bob."). Through a webhook URL in `fortune.json`, throttled like Charter's
  Discord notices (no more than `discordMaxPerHour`, default 6). The daily summary is what brings lapsed players
  back: they see the server is alive.

## 9. Abuse and safety

- One spin per account per day, keyed by UUID; Floodgate UUIDs are stable per Xbox account.
- Valor cannot move between players, so alts gain nothing from it. Item rewards are small. A new account's first
  spin waits `firstSpinMinutes` (default 5) online, which stops join and quit farming.
- The state file is written atomically (temp file and move, as Charter's terminals file), on every spin.
- The chest is read only: sgui refuses taking, placing, shift click and number keys, on both clients.
- The roll uses a server side `SecureRandom`; nothing about the outcome is sent before the reveal frames.
- Every spin is appended to `<world>/fortune/spins.jsonl` (UTC time, UUID, day, result, level, payout).

## 10. Measuring whether it works

`/fortune admin stats` (operators) reads `spins.jsonl` and shows: daily spinners for the last 14 days, the share of
yesterday's spinners who came back today (D1), the share of last week's who came back this week (D7), streak spread,
comeback count, and muster count per kingdom. Retune the paytable and muster size from those numbers, not from
guesses. Run it for two weeks before changing anything.

## 11. Commands and config

| Command | Who | What |
|---|---|---|
| `/spin`, `/daily` | Everyone | Open the machine |
| `/fortune odds` | Everyone | The paytable in chat |
| `/fortune admin machine here\|add\|remove\|list` | `fortune.admin` (op 2) | Fortune Machine blocks |
| `/fortune admin reset <player>` | `fortune.admin` | Give a player today's spin back |
| `/fortune admin stats` | `fortune.admin` | Section 10 |
| `/fortune admin reload` | `fortune.admin` | Reread `fortune.json` |

`config/fortune/fortune.json`: `rolloverHourUtc`, `streakGraceDays`, `secondWindMinutes`, `comebackDays`,
`firstSpinMinutes`, `musterSize`, `frameTicks`, `frameTicksBedrock`, `bedrockMode`, `payoutCommand`, the symbol list,
the normal, Golden and Welcome Back paytables, `firstWeek`, `milestones`, `discordWebhook`, `discordMaxPerHour`.
State: `<world>/fortune/players.json` (per UUID: last claim day, week position, streak, best streak, total claims,
second wind used, first week position).

## 12. Build shape

- Layout as chartercurrency: `core` (pure Java: the roll, the calendar, the streak, the paytable, with plain JDK tests)
  and `fabric` (sgui, commands, Floodgate adapter, gametests).
- `sgui 2.2.1+26.3` (`include`d, as Charter and Currency do). Floodgate API 2.2.4 and Cumulus 1.1.2 `compileOnly`.
- `fabric.mod.json`: Charter Currency and Charter as `suggests`, never `depends`.
- Pure tests: the paytable sums to 100%; every result line is reachable; filler never puts three payline matches on
  a no match result; the week advances per claim; the streak survives a 7 day gap and resets on an 8 day gap;
  rollover hour boundaries; Second Wind once a day.
- Gametests: the payout runs before the animation; closing mid spin keeps the reward and blocks a second spin; the
  chest refuses every take.
- Live checks owed after deploy (only a live server can show them): the Bedrock animation on a console and a phone,
  the modal join prompt, glint in a Bedrock container, and the Discord summary.
- Deploying to d212ecae follows the `deploy-shipped-server` skill and is the master session's call.

## 13. Open questions for the owner

1. **Name.** "Fortune's Reels" with `/spin`, or another (a kingdom flavoured one such as "The Crown's Favour")?
2. **Rollover hour** (5.1).
3. **Coin** from the reels: none (recommended), or a small line.
4. **Second Wind** on (recommended) or off.
5. **First Week gifts** (5.3).
6. **Milestone titles** through Charter's titles, or keepsake items only.
7. **Muster size** (start at 3).
8. **Where the machines go**: the hub only, or the hub and every keep (recommended, it puts people at their keeps).

Recommended first build: sections 3, 4, 5.1, 5.2, 5.5, 6 and 9 to 11. Then the First Week, Second Wind, muster and
Discord once the stats show who is coming back.
