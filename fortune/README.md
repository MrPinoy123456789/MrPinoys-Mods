# Fantasy's Fortune

A daily slot machine login bonus for the kingdom server (mod id `fortune`, Minecraft 26.3, fabric_api 0.165.0+26.3).
Once a day each player pulls a three reel machine. Every pull pays, the week is laid out across the top, a forgiving
streak keeps count, and a Welcome Back spin greets lapsed players. Server side only: vanilla Java clients and Bedrock
players through Geyser install nothing. The design is in [SPEC.md](SPEC.md); this is the first build (spec section 13).

## Build

```bash
JAVA_TOOL_OPTIONS=-Xmx1200m ./gradlew.bat build --no-daemon --console=plain
```

```bash
JAVA_TOOL_OPTIONS=-Xmx1200m ./gradlew.bat :fabric:runGameTest --no-daemon --console=plain
```

Run one Gradle build at a time on this machine. `build` runs the pure suite (`:core:coreTest`) and the gametests
(Loom wires `runGameTest` into `check`); `runGameTest` alone reruns just the gametests. Both write
`fabric/build/gametest-report.xml`. The jar is `fabric/build/libs/MrPinoys_Fortune-0.1.0.jar` (sgui and the core are
inside it).

Layout, as chartercurrency:

- `core/`: plain Java, no Minecraft. The roll, the paytables, the reels, the calendar and streak, the stake maths, the
  state model and the stats. `FortuneCoreTest` is a plain `main()`.
- `fabric/`: the sgui chest, commands, persistence, payout, the Floodgate adapter and the gametests.
- `currency-api-stub/`: compile time stand-ins for the five Charter Currency signatures the stake calls. Never shipped.

## Install

Drop the jar in `mods/`. It starts and runs without Charter Currency, Charter or Floodgate (all three are
`suggests`):

- **With Charter Currency** a spin pays Valor and Renown through its own `earn` command, and the coin stake is offered.
  Add the `fortune` source to Charter Currency's `config/chartercurrency/earn.json` (under `sources`), then
  `/chartercurrency reload`:

  ```json
  "fortune": { "base": 4, "perLevel": 2, "softCap": 60, "hardCap": 0, "renownMultiplier": 1.0 }
  ```

  Without that source the `earn` command refuses the unknown source and the spin pays only its items.
- **Without Charter Currency** a spin pays only its item extras and the stake buttons are hidden.
- **With Floodgate** Bedrock players get the join prompt as a modal form.

## How it pays

- Valor and Renown only through `payoutCommand`, run as the server console:
  `chartercurrency earn %player% fortune %level% fortune-%date%-%player%`. The run id is per player per day.
- Items: the paytable's extras (iron, emeralds, diamonds), the jackpot keepsake and the milestone keepsakes. Vanilla
  items only. Coin is never created: a paytable that lists a Coin item (copper, iron or gold nugget, or
  `kingdoms:gold_coin`) is refused at load.
- The coin stake is the only Coin path, and it only takes: Copper 8 Marks for +25%, Iron 24 for +50%, Gold 64 for
  +100%, charged from the banked balance through `EconomyApi.economy()` before the roll. A failed charge means the spin
  goes ahead unstaked. The bonus is applied to the Valor (as the nearest level to Valor times the bonus) and to item
  counts, rounding halves up.
- Order of a pull: charge the stake, roll, write the claim, the result and the reward (marked pending) to
  `players.json` (temp file then move), animate, then pay at the reveal. Paying at the reveal keeps Charter Currency's
  own "+N Valor" chat line and the items from giving the result away while the reels turn (spec section 9). The reward
  is fixed and on disk before the first frame: closing the window reveals and pays at once, logging out or a crash mid
  spin leaves it pending and it is paid on the next join, and nothing allows a second roll that day.

## Commands

| Command | Who | What |
|---|---|---|
| `/spin`, `/daily` | Everyone | Open the machine |
| `/fortune odds` | Everyone | The paytable in chat |
| `/fortune admin machine here` | admin | Place a gold block at your feet and make it a Fortune Machine |
| `/fortune admin machine add` / `remove` / `list` | admin | Register the block you look at, unregister it, list them |
| `/fortune admin reset <player>` | admin | Give a player today's spin back (the reward stays theirs) |
| `/fortune admin stats` | admin | Daily spinners, D1 and D7 return, streaks, Welcome Backs, Coin drained by stakes |
| `/fortune admin reload` | admin | Reread `fortune.json` (a file that fails the checks is refused whole) |

"admin" is the permission node `fortune:admin` through the Fabric permission API (LuckPerms), falling back to
operator level 2.

## Files

- `config/fortune/fortune.json`: every knob, written with defaults on first start. Paytable weights are basis points
  and each table must sum to 10000.
- `<world>/fortune/players.json`: per player calendar, streak, last spin and stake choice, plus the Annals.
- `<world>/fortune/machines.json`: the Fortune Machine blocks.
- `<world>/fortune/spins.jsonl`: one line per spin (UTC time, UUID, day, result, level, extras, stake). Stakes carry
  the reason `fortune_stake` here and in the server log.

## Not built yet

The knobs exist in `fortune.json` and default off; turning them on does nothing yet: the First Week
(`firstWeekEnabled`), Second Wind (`secondWindEnabled`), kingdom muster (`musterEnabled`), Discord posts
(`discordWebhook`), and `/fortune admin machine keeps` (it needs Charter's areas API, and Fortune imports nothing from
Charter).
