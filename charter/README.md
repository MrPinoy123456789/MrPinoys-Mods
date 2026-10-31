# Charter

A server-side Fabric mod for Minecraft 26.2: organizations govern themselves, hold
land through keeps and camps, and take land by supply-line sieges instead of
breaking blocks. Vanilla clients install nothing. The design is `DESIGN.md`, the
plan `PLAN.md`, how to build and test `BUILDING.md`, the rules that must never
break `INVARIANTS.md`.

## Mixins, and why no Fabric API event covers each

The suite rule: every Mixin is listed here with the reason an event would not do.
All live in `fabric/src/main/java/charter/mc/mixin` and are listed in
`charter.mixins.json`. Each is a few lines; the logic is in `charter.mc.land`
(`Protection`, `Hazards`, `Placement`, `HopperGate`) and `charter.mc.justice`
(`CommandGate`), and every land check goes through `ClaimIndex` and core's
`SiegeRules.protectedClaim`.

| # | Mixin | Target | Why no event covers it | What it does |
|---|---|---|---|---|
| 1 | `BlockItemMixin` | `BlockItem.place` (wrap) | Fabric API has no server side block place event. `UseBlockCallback` would mean guessing the final position from the click face, sneaking and replaceable blocks | Refuses placement into protected land; turns a nexus item into a keep and a war banner into a camp, taking the block back if the engine refuses |
| 2 | `ServerExplosionMixin` | `ServerExplosion.calculateExplodedPositions` (return) | Explosions fire no break event | Removes protected blocks and the nexus from the list of blocks a blast destroys, unless a permitted player lit it. Entity damage is untouched |
| 3 | `PistonBaseBlockMixin` | `PistonBaseBlock.moveBlocks` (head) | Piston pushes and pulls fire no event | Refuses the move if any block pushed, destroyed or pushed into lies in another owner's protected land, or is the nexus |
| 4 | `FireBlockMixin` | `FireBlock.tick` (spread) and `checkBurnOut` | Fire ticking has no event | Fire cannot spread into, or burn blocks in, protected land of a different owner than the land it stands in |
| 5 | `LavaFluidMixin` | `LavaFluid.randomTick` (ignition) and `spreadTo` | Fluid ticking has no event | Lava cannot set fires in, or turn water to stone in, protected land of another owner |
| 6 | `FlowingFluidMixin` | `FlowingFluid.spreadTo` (was `canSpreadTo` before 26.2) | Fluid flow has no event | Water and lava do not flow across a border into protected land of another owner |
| 7 | `DispenserBlockMixin` | `DispenserBlock.dispenseFrom` | Dispensers fire no event, and a dispenser outside a border can place lava or fire into a claim | Refuses to dispense into protected land the dispenser's own chunk owner does not share |
| 8 | `HopperBlockEntityMixin` | `HopperBlockEntity.addItem(Container, Container, ItemStack, Direction)` (static) | Container transfers fire no event | The gate only: every insert is offered to `HopperGate`. M8 registers nothing; the granary and supply lines register their interceptors |
| 9 | `VehicleEntityMixin` | `VehicleEntity.hurtServer` | `AttackEntityCallback` covers melee only; arrows, tridents and fire charges hurt a cart through `hurtServer` | In protected land only a permitted player can hurt a minecart or boat (test K4) |
| 10 | `FarmlandBlockMixin` | `FarmlandBlock.fallOn` (the `turnToDirt` call; the class was `FarmBlock` before 26.2) | Trampling fires no event | Outsiders and mobs cannot trample farmland in protected land; fall damage still works |
| 11 | `CommandsMixin` | `Commands.performCommand` (head) | No Fabric API event sees every command a player runs, and a prisoner must not `/home`, `/tpa` or `/spawn` out of a cell | Asks `CommandGate` first: a player in custody or held by a sentence may run `/charter` and nothing else. The console and command blocks pass |

Protection that does use a Fabric API event (`Guard`): `PlayerBlockBreakEvents.BEFORE`,
`AttackBlockCallback`, `UseBlockCallback`, `UseItemCallback` (buckets, flint and
steel, fire charges, spawn eggs), `AttackEntityCallback`, `UseEntityCallback`.

Not protected on purpose: mob griefing (endermen, ravagers) follows the vanilla
`mobGriefing` rule, and withers breaking blocks are a server rule problem.

## Land in the world

- A **nexus** is a lodestone item tagged `charter: nexus`; placing it runs
  `PlaceNexus`. A **war banner** is a red banner tagged `charter: war_banner`;
  placing it runs `PlantCamp` and lights a signal campfire on a hay bale beside it.
  Operators hand them out with `/charter admin give nexus|banner [player]`.
- The **Charter of Rights and Freedoms** is a paper item drawn as a book and quill
  (`charter: book`), handed to every player who joins without one. Using it, or
  typing `/charter`, opens a dialog menu: Rights, Freedoms, your situation, your
  faction, its Chronicle; a player with no faction sees Join and Found instead.
  `/charter page` is the old chat page. Buttons run the one-shot
  `/charter act <token>` links, so there is no Mixin for it. Operators can hand one
  out with `/charter admin give book [player]`.
- A **named Nexus** is crafted from a lodestone and a banner that has a name
  (`charter:nexus` special recipe). Placing it with no faction founds a Lordship
  named after the banner and raises its keep, paid in diamonds from the
  player's inventory (`FoundKeep`).
- `/charter map` draws 11 by 11 chunks in chat; the `+` cells and your own farms are
  links that claim and give up land through `ClaimLand` and `Unclaim`.
- Holding a war banner or a compass draws the chunk edges around you in dust: green
  yours, red theirs, yellow the open sides of a besieged keep.
- Admin: `/charter admin faction spawn <name> <player>`, `camp raise-now`,
  `perimeter show`, `give`.

## Discord

A built in bot lets a player link their Discord account and answer their
faction's join requests from Discord. It is off until an operator turns it on, and
it needs nothing installed beside Charter: it talks to Discord with the JDK's own
HTTP and WebSocket clients. Code: `charter.mc.discord`; tests: `DiscordTest`.

**Setting it up**

1. In the Discord developer portal, create an application, add a Bot, and copy its
   token. No privileged intents are needed.
2. Invite the bot to your Discord server with the `bot` and
   `applications.commands` scopes (OAuth2, URL Generator). It needs no channel
   permissions: it only answers its own commands and sends DMs.
3. Edit `config/charter/discord.json` (written on first start):

   | Key | Default | What it does |
   |---|---|---|
   | `enabled` | `false` | Nothing connects until this is true |
   | `botToken` | `""` | The bot token. Leave blank to read the `CHARTER_DISCORD_TOKEN` environment variable instead |
   | `guildId` | `""` | Your Discord server's id. Set it and the commands show there at once; blank registers them everywhere, which Discord can take up to an hour to show |
   | `notifyJoinRequests` | `true` | DM linked members who may take players in when someone asks to join |
   | `linkCodeMinutes` | `10` | How long a link code works |

4. Restart the server. The log says `Discord connected as <bot name>`.

**Linking (players)**

1. In game, `/charter discord link` shows a six character code (click it to copy).
2. In Discord, `/link code:<code>`. The reply is only visible to them.
3. `/charter discord` shows whether you are linked; `/charter discord unlink` in
   game or `/unlink` in Discord undoes it.

A code works once, runs out after `linkCodeMinutes`, and only the player who
asked sees it. Five wrong codes from one Discord account make it wait 15 minutes.
One Discord account per player: linking again replaces the old link. Links are
kept in the world's `charter/discord-links.json`; codes are not saved.

**Join requests**

`/requests` in Discord lists who asks to join a faction the player may take players
in to, with **Admit** and **Turn away** buttons (Turn away asks first). The rule is
the Faction page's own: the `MANAGE_MEMBERS` power, which the Lord of a Lordship
holds and a faction can grant. Every press runs `Admit` or `Reject` through the
engine as the linked player, so it is checked at that moment exactly as a click in
the book would be; a council that decides together gets a proposal, as in game.
When someone asks to join, each linked member who may answer gets a DM with the
same buttons. Acting from Discord does not count as being seen in game (the absent
Lord rule still needs the Lord to log in).

## The playtest kit

Everything an operator needs for the first session (PLAN.md §12).

### Scenarios

Six scenario files ship in the jar and are written to
`config/charter/scenarios/` the first time a scenario command runs; edit or copy
them there, and a file already there is never replaced.

| Scenario | Sets up |
|---|---|
| `tutorial` | One new player's first hour: they name a banner at an anvil, craft a Nexus, place it at an emerald pillar to found a faction and raise its keep, then plant a banner at a gold pillar beside Brinewatch's nearly empty keep and starve it out. A 15 page book guides them in game; `TUTORIAL.md` is the long version |
| `raid` | Ashfall's keep and three farms with wheat and cows; a raised Brinewatch camp beside the farthest farm |
| `partial-siege` | Ashfall's keep at half; two Brinewatch camps close the east side; a food chest to the west with rails and a chest cart into the keep |
| `encirclement` | Ashfall's keep at 30 percent; four camps with two claims each hold all twelve sides (the plan said three camps, but a camp holds three sides, so the ring takes four) |
| `sally` | The encirclement, with the defenders beside the east camp and food beyond it |
| `jail` | Ashfall's keep with a stone cell, a Sheriff with arrest and judge powers and leads; an outsider and a Brinewatch member stand in the keep |

```
/charter admin scenario list
/charter admin scenario run <name> [with <player> ...]      around your chunk
/charter admin scenario runat <x> <z> <name> [with ...]     around a block, overworld (from the console)
/charter admin scenario clear                               take down every faction a scenario made
/charter admin note <text>                                  a check-in, written to the siege log
```

Start the tutorial with `/charter admin scenario run tutorial with <player>`; `scenario
clear` afterwards also takes down the faction that player founded in it.

Players are named for the scenario's roles in order (`scenario list` shows them:
`A1 A2 A3 B1 B2` for the raid); a role nobody is named for is an offline
stand-in. Every scenario sets `timeScale` 24, `carryInWindowMinutes` 240 and
`presenceMinMemberSeconds` 0, waives new-faction protection for its factions, and
backdates their membership 30 days. Each step runs through the engine as the
admin, so a refused step stops the run and says why. Named players must not
already belong to an organization; `scenario clear` frees them.

Run one scenario at a time and clear it before the next. The siege clock is shared, so
raising a later scenario's camps moves every earlier siege on by the raising time
too: run back to back, the raid's far farm was empty before anyone arrived.

### The siege log and the debrief report

`world/charter/siege-log/<yyyy-mm-dd>.jsonl` holds the debrief events (PLAN.md
§8.3). On top of the history events, M14 writes a `drain_hour` snapshot of every
keep under siege each siege hour (provisions, limit, percent, debt, drain per hour,
multiplier, mouths), a `presence` line when a keep's multiplier moves, a
`crossing` line for every carrier that entered a besieged site or was turned back
at a closed side, and the operator's `note` lines.

```
python charter/tools/siege_report.py <siege-log folder or files> --state <world>/charter/state.json --out report.md
python charter/tools/siege_report.py --selftest
```

The report has a timeline per besieged keep (provisions hour by hour, crossings
counted and refused with reasons, deposits, the fall and the choice), then camps,
land, justice, operator notes and every admin command. `--state` turns ids into
faction names and places. Standard library only.

### Load check

| Check | Size | Result (2026-10-25, the build machine) |
|---|---|---|
| `gradlew :core:loadCheck` | 10 sieges, 110 claims, 40 players walking at random, every crossing checked every tick, the sim stepped every tick at time scale 24, 6,000 ticks | mean 0.65 ms a tick, median 0.47 ms, p99 3.7 ms (budget 10 ms) |
| `LoadGameTest` (dev server) | an encirclement, 20 mock players moving every tick for 300 ticks | sim step 0.3 to 2.2 ms each (one every 100 ticks), crossing checks 0.1 to 0.4 ms a tick |

The core check is not part of `check`, since timing on a busy machine is not a
rule; the gametest fails only above 5 ms on average. Risk R1's fallback (check
players outside any active site every 4 ticks) is not needed at this size.

### Deploying to the test server

1. From `charter/`: `gradlew build` (every suite, then the jar into the suite's
   `dist/`). Check `dist/MrPinoys_Charter-<version>.jar` is newer than the last
   source edit.
2. The target is the Kinetic test server **`714a0605`**, never `365f9682`. Check
   `pocketdungeons/tools/server/kinetic.env` names `714a0605` first.
3. `node pocketdungeons/tools/server/pdserver.mjs deploy --jar MrPinoys_Charter --restart`
   (the `--jar` option is decision D15). Pocket Dungeons stays on the same server:
   Charter claims only the overworld and the dungeons live in their own
   dimension.
4. In the server log: `Charter initialised`, the config written to
   `config/charter/charter.json`, no Mixin errors. Then, as a smoke check:
   `pdserver.mjs cmd charter admin supply show` and
   `pdserver.mjs cmd charter admin scenario list`.
5. Before players join, an op claims spawn and the Pocket Dungeons lobby for a
   "Crown" organization (`/charter admin faction spawn Crown <op>`, then a nexus
   at spawn), so nobody can claim them.
