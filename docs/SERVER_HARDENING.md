# Server hardening for the kingdom server (d212ecae)

Plan, decisions, staged files and runbooks to make the live kingdom server harder to grief, cheat on or break.
Written 2026-11-02 on branch `feature/server-hardening`. Nothing here has been deployed: the owner restarts and
approves every deploy (see `docs/HANDOFF_2026-11-01.md` section 1). Read `docs/KINGDOM_SERVER.md` first.

Everything about the live server below comes from read only panel calls made on 2026-11-02 (file listings, file
reads of `server.properties`, `ops.json`, `level.dat`, `config/ledger.toml`, `latest.log`, the backups and schedules
listings). No command was sent to the server and nothing was uploaded.

## 1. Do these first (in this order)

| # | What | Who | Why |
|---|---|---|---|
| 1 | Take a panel backup of the current world now, and lock it | owner, panel | The newest panel backup is from 2026-10-21, before the 26.3 port and the 2026-10-30 world wipe. The current world has **no backup at all**. See section 5. |
| 2 | Re-arm the two stuck schedules (Daily Backup, Daily Restart) | owner, panel | Both show "processing" since 2026-10-23 with a next run date in the past; neither has run since 2026-10-21. |
| 3 | Cut `ops.json` down to the owner (and at most one co-owner) | owner, in game `/deop` | Seven accounts hold operator level 4, including three Bedrock accounts. An operator can use Axiom (which is installed and live), bypasses Charter's spawn and keep rules, can `/op` others and `/stop` the server. No permission mod can take Axiom from an operator. |
| 4 | Set `spawn-protection=0` | stage script | Vanilla spawn protection blocks every right click within 16 blocks of world spawn (-248 64 44) for non operators, which includes the hub's lectern terminal at -247 64 44, its doors and buttons. Charter's spawn rules already protect the hub. |
| 5 | Install LuckPerms, Vanilla Permissions, AntiXray, BanHammer, spark with the Charter build from this branch | stage script, then owner restart | Staff roles without operator, grief investigation and rollback by staff, anti-xray, tempban and mute, lag profiling. |
| 6 | Load the LuckPerms groups, put staff in them | owner, console | Section 4. |

## 2. What the live server is (2026-11-02)

- Kinetic (Pterodactyl based panel), server "big server", node USW-CA-I2-A2-S, 10 GB RAM, 6 CPU cores (600%), 49.7 GB
  disk (6.1 GB used), Java 25 image, Aikar's flags, 5 backup slots on S3, 5 allocations (25597 Java, 25598 Geyser UDP).
- Minecraft 26.3, Fabric loader 0.20.1, Fabric API 0.165.0+26.3. Mods in `/mods`: Axiom 6.1.5 (**enabled**; the
  handoff calling it disabled is out of date), Chunky 1.5.3, DimensionDaddy 2.0.0 (nether and end off), Floodgate
  2.2.7-b69, Geyser 2.12.1-b1279, Incendium 5.5.4, Structory 1.3.18, Dungeons and Taverns 6.0.2, revault 1.3.3,
  serverside-carryon (26.3, 1.0.2), Trinkets 4.2.1 and its Polymer patch, polymer-bundled 0.18.5, fabric-language-kotlin
  1.14.2, Ledger 1.3.25, and the house mods (Charter, Charter Currency, Foundry, Pocket Dungeons, Lemon, GoldGate,
  Excellent Enchants, Instant Restock).
- `ops.json`: `.Worstly9237`, `.StarlessTuna`, `MrPinoy123456789`, `Voidaimbot`, `kixra027`, `HowMadYouNow`,
  `.Jokerboi131039`, all level 4. `op-permission-level=4`. `white-list=false`, `enforce-secure-profile=false`,
  `online-mode=true`, `rate-limit=0`, `spawn-protection=16`, `view-distance=10`, `simulation-distance=10`,
  `max-tick-time=60000`, `log-ips=true`, RCON, query and the management server off.
- World spawn (from `level.dat`): -248 64 44 in the overworld. Hub keep terminal: -247 64 44. Keep pads near -275 66 44.
- Ledger 1.3.25 with `autoPurgeDays = -1` (keeps everything forever); its database `world/ledger.sqlite` was 14 MB
  after three days.
- Bedrock players reach the server through Geyser with their real address (the log shows `.Worstly9237` at the same
  address as `MrPinoy123456789`, port 0), so IP bans do work for Bedrock players, and they hit whole households.

## 3. How permissions work on this server (read this before touching LuckPerms)

There are three separate permission systems on Fabric 26.3, and each mod uses one of them:

1. **Vanilla operator levels.** `Commands.hasPermission(LEVEL_GAMEMASTERS)` and `Permissions.COMMANDS_GAMEMASTER` check the
   player's level from `ops.json`. Vanilla commands (`/gamemode`, `/give`, `/tp`), Pocket Dungeons' `/dungeon admin`,
   Foundry's `/foundry`, Lemon's admin commands, serverside-carryon's `/servercarry`, and (before this branch) every
   Charter operator check use this.
2. **The Fabric permission API v1** (`fabric-permission-api-v1`, part of Fabric API 0.165). An event,
   `PermissionEvents.ON_REQUEST`, asked in registration order; the first handler that returns a value wins. Charter's
   `RaceNodes` answers `race:<kingdom>` here, and Foundry's race weapons, looks, glide, double jump and swim dash ask it.
3. **lucko's fabric-permissions-api v0** (`me.lucko.fabric.api.permissions.v0`). Ledger, Axiom (by reflection),
   AntiXray, BanHammer, spark and Vanilla Permissions ask nodes through it, with an operator level fallback.

What LuckPerms 5.5.85 does (checked by decompiling `me.lucko.luckperms.fabric.listeners`):

- It answers v0 (every string node) and v1 (`race:azrak` becomes the string `race.azrak`; a `/` in the path becomes a
  `.`). It returns nothing for a node it has no value for, so another handler can still answer.
- It wraps each player's vanilla `PermissionSet`, but only consults its own nodes for `Permission.Atom` checks (such
  as `minecraft.chat.send_messages` or `minecraft.commands.entity_selectors`). Operator level checks
  (`HasCommandLevel`) go straight to `ops.json`. **So LuckPerms alone cannot give or take `/gamemode`, `/give`, Charter
  admin or Pocket Dungeons admin.**
- **Vanilla Permissions** (DrexHD) fills that gap for vanilla commands: it wraps every vanilla command node with
  `minecraft.command.<name>` (and sub nodes such as `minecraft.command.gamemode.creative.target`), falling back to the
  vanilla level when LuckPerms says nothing. It only wraps commands registered before its own phase, which is the
  vanilla set, so mod commands are untouched.

### Charter change on this branch

Charter now asks two nodes through the v1 API, with operator level 2 as the fallback, so nothing changes until a
permissions mod says something (`charter/fabric/src/main/java/charter/mc/CharterPerms.java`):

| Node (LuckPerms spelling) | What it opens | Was |
|---|---|---|
| `charter.admin` | Every `/charter admin ...` command (faction, pad, terminal, mapmarker, npc, granary, supply, rank titles, chronicle admin, land admin) and the operators' menu in the Charter book | operator level 2 |
| `charter.bypass` | The operator pass in the world: spawn rules (`SpawnRules.isOp`), walking into closed keeps (`KeepGate`), moving or lifting any kingdom's Nexus (`NexusMove`, `NexusLift`, `BoundItems`), and the `requireAffiliation` look only rule | operator level 2 |
| `charter.locatorbar` | Seeing other players on the locator bar while `locatorBarAdminsOnly` is on (`LocatorBar`); everyone without it has a waypoint receive range of 0 but still shows on an admin's bar | operator level 2 |

Either node can be given to someone who is not an operator, or set to false for an operator (LuckPerms' value wins over
the level).

**The admin kingdom pass (`adminKingdomBypass`, default on).** Every member of an admin kingdom, at any rank, also has
`charter.bypass`, decided by Charter from membership and not by LuckPerms, so setting the node to false in LuckPerms does
not take it from them; turn the knob off or take them out of the kingdom. On top of the node they may build, break and
use things on any kingdom's claims (the node alone does not allow that). It never gives `charter.admin`, and it does not
count as staff for Lord discipline (`LordModeration.staff` reads the node only). Anyone the operators put in the admin
kingdom gets this, so keep its membership to trusted staff. See "The admin kingdom pass" in `docs/KINGDOM_SERVER.md`.

Two more small changes:

- **Race nodes are answered first.** `RaceNodes` now registers in its own event phase `charter:race_nodes`, ordered
  before the default phase where LuckPerms answers. A member of Azrak holds `race:azrak` whatever LuckPerms says. A
  node Charter leaves undecided (another kingdom's race) can still be granted by LuckPerms, as `RaceNodes` always
  intended for a future race mod.
- **Protection is asked first for using an entity.** `Guard`'s use entity check now runs in the phase
  `charter:protection`, before the default phase. Serverside CarryOn picks mobs up with a sneaking empty hand and
  answers "success" from its own handler; if it ran first, a stranger could carry a kingdom's villagers, horses and
  pets out of its keep. Before this change the order depended on the order mods happened to initialise.

Tests: `charter/fabric/src/gametest/java/charter/gametest/HardeningGameTest.java` (registered in the gametest
`fabric.mod.json`): the admin node opens `/charter admin` for a non operator through every merged registration; the
bypass node exempts a builder; both can be taken from an operator; a stand in permissions mod returning false for a
member's race node does not take the race; a stand in carry handler cannot carry an entity out of a keep. The last two
pin the outcome but would also pass without the phase change (the stand in handlers register after Charter's, so
Charter was asked first anyway in the test); the real check of the ordering is live tests T2 and T8.

### The race node rule (mandatory)

**Never grant `*`, `race.*` or any `race.<kingdom>` in LuckPerms, and never give anyone `luckperms.*` except the owner.**
Foundry's `MechanicHost.permissionCheck` also falls back to the player's vanilla `PermissionSet` for an atom
`race:<kingdom>`, which LuckPerms answers directly; a wildcard there would give a staff member every race look and
trait at once (beard, banner, antlers, fins, wings, glide). Charter's early phase fixes only the v1 path.

### Who may do what

| Ability | default | helper | builder | moderator | admin | operator (owner) |
|---|---|---|---|---|---|---|
| Play, Charter book, `/trinkets`, `/keep`, `/hub` | yes | yes | yes | yes | yes | yes |
| `/ledger inspect`, `search`, `status` | | yes | | yes | yes | yes |
| `/ledger tp`, `preview`, `player`, `rollback`, `restore` (100 block range) | | | | yes | yes | yes |
| `/ledger purge` | | | | | | yes (level 4) |
| `/kick`, `/tempmute` (up to 1 hour) | | yes | | yes | yes | yes |
| `/mute`, `/tempban` (up to 7 days), `/unmute`, `/unban` | | | | yes | yes | yes |
| `/ban`, `/ban-ip`, `/tempban-ip`, `/pardon` | | | | | yes | yes |
| `/gamemode spectator` and `survival` (self) | | | yes | yes | yes | yes |
| `/gamemode creative` (self) | | | yes | | yes | yes |
| `/gamemode` on other players | | | | | yes | yes |
| `/tp` (self to a place or player) | | | | yes | yes | yes |
| `/tp` other players, `@` selectors | | | | | yes | yes |
| `/give`, `/effect`, `/clear`, `/time`, `/weather` | | | | | yes | yes |
| Axiom | | | yes (`axiom.default`) | | | yes (always) |
| Build in the hub, enter closed keeps, move any Nexus (`charter.bypass`) | | | only if given for the day (O11) | | yes | yes |
| `/charter admin ...` and the operators' menu (`charter.admin`) | | | | | yes | yes |
| `/spark` | | | | | yes | yes |
| `/dungeon admin`, `/foundry`, Lemon admin, `/servercarry` | | | | | | yes (level 2 or 3) |
| `/op`, `/deop`, `/stop`, `/whitelist`, `/lp` | | | | | | yes |

Pocket Dungeons, Foundry, Lemon and serverside-carryon check operator levels directly; LuckPerms cannot open them to
staff without a code change in those mods. That is deliberate for now: they are owner tools.

The builder group is meant to be given for a few hours (`lp user Name parent addtemp builder 4h`), because Axiom edits
the world directly: they skip Charter's claim checks and are most likely not recorded by Ledger (unverified; assume
not). A creative builder can also make items for others, which matters once Charter Currency is in play.

### Who is staff, as the owner decided (2026-11-02)

The groups in section 4 all exist. This is who is in them; nothing in section 4 changed.

| Role | Who | How |
|---|---|---|
| admin | MrPinoy123456789 and HowMadYouNow | `lp user <name> parent add admin`, once each has joined the server for the first time (not assigned yet) |
| builder | nobody standing | granted by the owner on demand, for hours: `lp user Name parent addtemp builder 4h` |
| moderator | nobody, and not used | the Kingdom Lords moderate their own citizens instead (below) |
| helper | nobody; probably never | the group stays for a future need |
| Kingdom Lord | each kingdom's Lord or two Lords, appointed by admins in Charter | no LuckPerms group at all |

Ledger and BanHammer are global: neither can be limited to one kingdom. So a Lord's moderation lives in Charter, and a
Lord gets **no BanHammer node and no lasting Ledger node**. What a Lord can do (all checked by Charter against the
target's kingdom, and never to another kingdom's people, another Lord, or anyone with `charter.admin` or
`charter.bypass`):

- `/charter lord mute|unmute <player> ...`: up to 1 hour.
- `/charter lord suspend|unsuspend <player> ...`: barred from the kingdom's land, up to 12 hours; they stay a member.
- `/charter lord kick <player> <reason>`: a disconnect; they may rejoin at once.

Every one is journaled with its reason, sent to online admins, and listed by `/charter admin moderation`. Details and
the exact rules: `docs/KINGDOM_SERVER.md`, "Lords discipline their own citizens".

**Temporary Ledger read access for a Lord.** An admin can run `/charter admin ledger grant <player>` for a current
Lord. Charter runs the LuckPerms temporary nodes `ledger.commands.root`, `.inspect`, `.search` and `.status` for
exactly 1 hour (`settemp ... true 1h replace`), tells the admin the expiry and tells the Lord. It cannot be extended
while it stands, it lapses on its own, it is taken back if the player stops being a Lord, and `ledger revoke` ends it
early. **Ledger cannot restrict those lookups to the Lord's kingdom**: for that hour they can read the whole server's
block history. Grant it only to a Lord you trust with that, for one investigation. Rollback, restore, preview, teleport
and player stay with admins.

## 4. Permission groups (staged as console commands)

`deploy/server-hardening/luckperms-groups.txt` holds every command (paste into the panel console after the restart that
loads LuckPerms, or run `node tools/playtest/hardening-stage.mjs --lp-setup --owner-approved`). Weights decide which
group wins a conflict: admin 30, moderator 20, builder 15, helper 10, default 0. Moderator inherits helper; admin
inherits moderator. Full node table:

| Group | Nodes set |
|---|---|
| helper | `ledger.commands.root`, `ledger.commands.inspect`, `ledger.commands.search`, `ledger.commands.status`, `banhammer.punish.kick`, `banhammer.punish.tempmute`, `banhammer.duration.helper` |
| moderator | `ledger.commands.tp`, `ledger.commands.preview`, `ledger.commands.player`, `ledger.commands.rollback` (also covers restore), `banhammer.punish.mute`, `banhammer.punish.tempban`, `banhammer.unpunish.unmute`, `banhammer.unpunish.unban`, `banhammer.duration.moderator`, `minecraft.command.gamemode`, `minecraft.command.gamemode.spectator`, `minecraft.command.gamemode.survival`, `minecraft.command.teleport`, `minecraft.command.tp`; set false: `minecraft.command.gamemode.creative`, `minecraft.command.gamemode.adventure`, `minecraft.command.gamemode.spectator.target`, `minecraft.command.gamemode.survival.target`, `minecraft.command.teleport.targets` |
| admin | `charter.admin`, `charter.bypass`, `charter.locatorbar`, `banhammer.punish.ban`, `banhammer.punish.banip`, `banhammer.punish.tempbanip`, `banhammer.unpunish.unbanip`, `banhammer.unpunish.pardon`, `banhammer.duration.admin`, `minecraft.command.gamemode.creative`, `minecraft.command.gamemode.adventure`, `minecraft.command.gamemode.spectator.target`, `minecraft.command.gamemode.survival.target`, `minecraft.command.teleport.targets`, `minecraft.command.give`, `minecraft.command.effect`, `minecraft.command.clear`, `minecraft.command.time`, `minecraft.command.weather`, `minecraft.commands.entity_selectors`, `spark` |
| builder | `axiom.default`, `minecraft.command.gamemode`, `minecraft.command.gamemode.creative`, `minecraft.command.gamemode.survival`, `minecraft.command.gamemode.spectator`; set false: `minecraft.command.gamemode.adventure` and every `minecraft.command.gamemode.<mode>.target` |
| (owner) | operator level 4, plus `lp user MrPinoy123456789 parent add admin` |

Node names were taken from the jars: Ledger's from its `subcommands/*Command` classes (all default to level 3; purge to
`purgePermissionLevel` 4), Axiom's from `AxiomPermission` (operators always get everything; `axiom.default` covers use,
build, editor, chunk, player and world tools), BanHammer's from its README and config, Vanilla Permissions' from its
`Permission` class and its rebuilt `/gamemode` tree. **The `minecraft.command.*` sub node names (`.targets`, `.target`)
must be confirmed with `/lp verbose on` on the live server** (section 10, test T5).

Optional strict step, `deploy/server-hardening/luckperms-strict.txt` (owner decision O1): sets `charter.bypass` and
`charter.admin` false on the default group, so operators who are not admins are held to Charter's rules too. Run it only
after every operator who should keep the pass is in `admin` or `builder`.

How a moderator mutes without BanHammer, if it is ever removed: `lp user Name permission settemp
minecraft.chat.send_messages false 1h` (26.3's chat permission atom, which LuckPerms answers).

## 5. Backups

### What exists today

- Panel backups: 5 slots, all used, all from 2026-10-17 to 2026-10-21 (2.33 GB each, stored on S3, so already off the
  game host). They hold the world from **before** the 26.3 port and the world wipe of 2026-10-30.
- Schedule "Daily Backup" (08:20 UTC) and "Daily Restart" (12:00 UTC): active, but stuck in "processing" since
  2026-10-23 with next runs in the past. Nothing has run since 2026-10-21. "Chunky overnight" is inactive.
- Charter writes its own state snapshots to `world/charter/backups/` and `state.json.bak`.
- Rollback material in the server root: `world-before-26.3.tar.gz` (596 MB), `Single_world.zip` (835 MB),
  `world_before_wipe/`, `world_before_packs/`, `/mods_26.2_set`, `/mods_backup`.
- `.pteroignore` excludes only `recycle_bin`, `workspaces` and `PanelStorage`, so every panel backup also carries the
  1.4 GB of old archives.

### Decision: the panel's S3 backups are the backbone; no backup mod

Reasons: the panel already stores backups off the game host (S3) with checksums, restores the whole server in one
click, and costs no game tick time; a mod's zip backups would sit on the same disk as the world, compete for the 500 IO
limit and the 49.7 GB quota, and lag the server while compressing a 2.5 GB world. Ledger covers the everyday case
(undo one griefer) far better than any world backup. The weak point of the panel is that a stuck schedule fails
silently, so `tools/playtest/kinetic-backups.mjs status` now reports exactly that (it found the current problem).

Candidates checked and not chosen (all list 26.3): Fast Backups 0.x (`fastback`, GPL-2.0, git based, restore needs git
skills), Simple Backups for Fabric (Apache-2.0, unofficial port), Just Enough Backups (MIT), Chronos Backups (MIT),
Simple Server Backups (MIT), MineBackup (needs a desktop app). Any of them can be added later if hourly restore points
become important.

### The schedule to set in the panel (owner)

1. Free a slot: the five old backups are of the old world. Keep the newest one (2026-10-21) **locked** as the last copy
   of the pre wipe world if it matters (owner decision O7), delete the other four, or pull them first (below).
2. Create a backup now ("Create Backup", name "first 26.3 world", lock it).
3. Delete and recreate "Daily Backup" (editing a stuck schedule may not clear it). Cron 20 8 * * * (08:20 UTC, the
   existing slot; one day of logs suggests it is quiet, check `minecraft_joinlog` in the panel), "only when online" off, tasks:
   1. command `save-all flush`, offset 0
   2. backup, offset 30 seconds
   With five slots and no locked backups this keeps five days. A scheduled backup deletes the oldest
   unlocked backup when the slots are full (the five consecutive dailies from a schedule that has run since July show
   the rotation working here); a locked backup takes a slot away from the rotation. If the owner keeps two
   locked backups, only three days rotate.
   Not chosen: `save-off` around the backup. The panel's backup task returns at once while the archive runs for about
   13 minutes, so a `save-on` task would have to be a second schedule; forgetting it stops the world saving. A flush just
   before is enough for a world this size.
4. Delete and recreate "Daily Restart" (cron 0 12 * * *, "only when online" on, tasks: command `say Restarting in 1
   minute.` offset 0, command `save-all flush` offset 50 seconds, power restart offset 10 seconds).
5. Optional, after pulling the old archives off the server (step below): add these lines to `/.pteroignore` so each
   backup shrinks by about 1.4 GB and runs faster: `/Single_world.zip`, `/world-before-26.3.tar.gz`,
   `/world_before_wipe`, `/world_before_packs`, `/mods_26.2_set`, `/mods_26.3` (owner decision O8).

### Off site copies (owner's PC)

`node tools/playtest/kinetic-backups.mjs pull latest --out D:\kingdom-backups` downloads the newest panel backup through
the panel's signed link and checks the panel's sha1. Keep: the last 4 weekly pulls and the first of each month for six
months (about 2.3 GB each; delete by hand). `status` shows ages, slots and stuck schedules and changes nothing.

### Restore procedures

**A. One griefer, one area (most cases): Ledger, no restart.** See section 9.

**B. Whole server back to a panel backup.**
1. Tell players, `save-all flush`, then stop the server from the panel.
2. Panel, Backups, the backup's menu, Restore. Tick "Delete all files before restoring" only when the whole server
   should look exactly like the backup (it also restores old jars and configs).
3. Start. Check the log for `Done (`, `Charter state ready`, Geyser's start line, and no `Mixin apply failed`.
4. Ledger's database is inside the world, so it goes back too; anything after the backup is gone from it.

**C. Only the world from a panel backup (keep newer jars and configs).**
1. Pull the backup to the owner's PC (`kinetic-backups.mjs pull <uuid> --out ...`) and check the sha1 line.
2. Stop the server. In the file manager rename `/world` to `/world_broken_<date>`.
3. Upload the backup's `world/` folder (SFTP is fastest for 2.5 GB) and start.

**D. A restore drill (do once, then after every big change).** Kinetic's panel offers extra instances on this plan
(the `instances` feature, limit 5): restore the newest backup into a spare instance, start it, join, check a keep, the
hub, `/ledger status`, and one Charter command, then delete the instance. This proves the archive restores. **Not
done:** no agent has restored a real panel backup; the procedure above is untested end to end.

## 6. Anti-xray

### Decision: AntiXray by DrexHD, fabric-1.4.17+26.3, engine mode 1 (hide) in the overworld below y 64

Candidates for 26.3 that exist: AntiXray (DrexHD, MIT, 640k downloads, a port of Paper's anti-xray engine), MinerTrack
(GPL-3.0, detects suspicious mining and punishes; it guesses, so it can punish honest miners), Meow Anti-Xray (MIT,
7.7k downloads), Viper AntiXray (All Rights Reserved). AntiXray is the established one, MIT, and its mode 1 cannot
punish anyone by mistake.

Why mode 1 and y 64: mode 1 sends a hidden ore that touches no air, water or lava as stone or deepslate. It defeats
xray resource packs on Java and Bedrock and adds nothing to chunk packets. Modes 2 and 3 also fill the hidden area with
fake ores, which defeats smarter ore finders but makes every chunk packet bigger, which costs bandwidth for 67 players
and more chunk translation work in Geyser for every Bedrock player. Processing only sections below 64 keeps the cost
low and covers diamonds, redstone, lapis, deepslate gold and ancient cities. Raise to mode 3 later if xrayers are
still caught.

Interactions:
- **Geyser and Bedrock:** obfuscation happens when the server builds the Java chunk packet; Geyser translates that
  packet, so Bedrock players see the same hidden ores. Bedrock xray packs are defeated the same way.
- **Pocket Dungeons:** the top level `enabled = false` leaves every dimension not listed alone, so `pocketdungeons:void`
  (dungeon rooms with placed ore nodes) is never touched. Wild lodestones in the overworld are lodestone and bedrock,
  not ores.
- **Charter:** no interaction (Charter never reads chunk packets). Lodestones, banners and lecterns are not hidden.
- **Chunky:** pregeneration writes chunks to disk; AntiXray changes only what is sent to players. Its
  `SerializableChunkDataMixin` touches chunk saving, so the local load test (section 11) and the live log after a Chunky
  run are the checks.
- **Polymer and Foundry:** Polymer also rewrites block states in chunk packets. Both mods load together locally
  (section 11, "Local load test"); how they look together needs a Java client (test T3).
- **Freecam mining to find ores:** AntiXray reveals blocks around a broken block (`updateRadius 2`). A player who mines
  blind can still find ores the honest way.

Config: `deploy/server-hardening/config/antixray.toml` (full text in section 8). The Nether section only matters
if DimensionDaddy's `netherEnabled` is turned back on.

## 7. Everything else

### server.properties (`deploy/server-hardening/server.properties.changes`)

| Key | Live | New | Why |
|---|---|---|---|
| `spawn-protection` | 16 | **0** | Vanilla blocks every block right click within 16 blocks of world spawn for non operators (`ServerGamePacketListenerImpl.handleUseItemOn` checks `isUnderSpawnProtection` first). The hub terminal is 1 block from world spawn. Charter's spawn rules protect the hub with an allow list (doors, buttons, crafting tables). With fewer operators, more players hit this. |
| `rate-limit` | 0 | **1000** | Vanilla kicks a connection whose averaged packets per second exceed this. Normal play is under 100; 1000 leaves room for Axiom pastes and Geyser bursts and still stops packet floods. Watch for `exceeded rate-limit` in the log; raise if a builder is kicked. |
| `simulation-distance` | 10 | **8** | Entities, crops and redstone tick in a 17 by 17 chunk square per player instead of 21 by 21 (about 35 percent fewer chunks). Farms must be within 8 chunks of their owner. View distance stays 10. |
| `enforce-secure-profile` | false | false | Floodgate (Bedrock) players have no Mojang chat signing key; true would block their chat. |
| `white-list` | false | false | `requireAffiliation` already lets a newcomer only look until a kingdom accepts them or they sign on as a mercenary. Owner decision O3. |
| `max-tick-time` | 60000 | 60000 | Vanilla's watchdog kills a server stuck on one tick for 60 seconds (it ended the Pocket Dungeons chunk load deadlock on 2026-11-01). Lower values kill the server during big saves. |
| `op-permission-level` | 4 | 4 | Only the owner should be an operator; the owner needs level 4. |
| `log-ips` | true | true | Needed to spot ban evasion. |
| `prevent-proxy-connections` | false | false | True compares the joining address with the one Mojang saw; Floodgate players never pass Mojang, and many players are on mobile networks. |
| `view-distance` | 10 | 10 | Lower it to 8 only if spark shows chunk sending as the bottleneck. |
| `enable-rcon`, `enable-query`, `management-server-enabled` | false | false | Already closed; keep them closed. |

### Command and exploit mitigations

- Operators: section 1 step 3. This is the biggest single risk on the server: any of seven accounts (three of them
  Xbox accounts, where account security is outside the owner's control) can wipe areas with Axiom unlogged.
- Command blocks: `enable-command-block` is not set (default false). Keep it so.
- Book and sign exploits: 26.3's vanilla limits on book size and Charter's own book handling cover the known book ban;
  not checked further.
- Packet floods: `rate-limit=1000` above. Chat spam: vanilla kicks after `chat-spam-threshold-seconds` worth of rapid
  messages; BanHammer adds mutes.
- Panda Anti-Exploit (2.1.13+26.3, CC0) was looked at and **rejected for now**: its packet hardening
  (`removeEquipmentTags`, `removeDroppedItemTags`) strips item components from what other players see, which would
  break Foundry's race looks served as head items and Polymer item models; its hit and placement angle checks are known
  to misfire on Bedrock (touch controls, different reach) through Geyser. If wanted later: packet options off, combat
  and placement checks tested with a Bedrock player first.
- serverside-carryon: player carrying is off by default (`/servercarry players status`); keep it off. Mob carrying is
  now refused by Charter inside protected land (section 3).

### Lag and crash watchdog

- Vanilla watchdog: `max-tick-time` above. The panel's crash manager (`crash_manager` egg feature) restarts a crashed
  server; check it is on in the panel's settings (owner).
- spark 1.10.187 (GPL-3.0, lucko): `/spark tps`, `/spark health`, `/spark profiler start --timeout 120` then open the
  link. The panel has a spark profile analyser. Admin group and operators only.
- The Daily Restart schedule (section 5) clears slow leaks; recreate it.

### Entity and chunk limits

No entity limiter for Fabric 26.3 was found that is both maintained and redistributable (Easy Mob Spawn Control is All
Rights Reserved; nothing else matched). What exists today: vanilla `maxEntityCramming` (24), Charter's spawn rules
(no hostile mobs in the hub), and `simulation-distance=8`. Plan: watch `/spark profiler` for entity ticking; if farms
become a problem, a small Charter knob (mobs per chunk inside claims) is a one file job. Chunk loading: Chunky's
`continueOnRestart` is false, so a crash does not resume a pregen by itself.

### Anti-grief gaps Charter and Ledger do not cover

| Gap | Covered by |
|---|---|
| The wild is unprotected on purpose (anyone can break anything outside claims) | Ledger rollback by moderators |
| Axiom edits bypass Charter and probably Ledger | Only owner and temporary builders get Axiom; de-op the rest |
| Operators bypass spawn rules and closed keeps | De-op; `charter.bypass` (strict step O1) |
| Carrying a kingdom's mobs out with serverside-carryon | Fixed on this branch (Charter asked first) |
| Explosions in the wild (TNT, creepers) | Ledger rolls back `block-break` by source; `explosionsInKingdoms=false` protects claims |
| Lava and fire spread in the wild | Ledger logs and rolls back `lava`, `fire` sources |
| Item theft from chests in the wild | Ledger logs `item-remove`, `/ledger search action:item-remove ...` |
| Bedrock alt accounts (free Xbox accounts) evading bans | BanHammer `showAssociatedAccounts` (same address); `/fwhitelist` if it gets bad (owner decision O3) |
| Ledger database grows forever | `autoPurgeDays = 90` staged (owner decision O6) |

### Joins, bans and evasion

- Ban by name or UUID for single players. IP bans (`/ban-ip`, `/tempban-ip`) hit every account at that address: the
  owner and `.Worstly9237` share one. Use IP bans only after `/ledger player` and BanHammer's associated accounts show
  only the offender there.
- A Bedrock player's Floodgate UUID is stable per Xbox account (`00000000-0000-0000-0009-...`), so a ban sticks to the
  account; a new Xbox account is a new player. `requireAffiliation` means a new account can only look until accepted or
  signed on as a mercenary (mercenary sign on is self service, so it slows an evader rather than stopping one).
- Floodgate allow list, if ever needed: `fwhitelist add <gamertag>` (Floodgate's own command), with `white-list=true`.

### DDoS posture

- What the host does: Kinetic advertises network level DDoS protection on all plans and a firewall manager (allow or
  block addresses) in the panel. Their capacity is not published; not verified.
- What a mod cannot do: nothing running inside the Java process can absorb a volumetric attack; by the time packets
  reach Minecraft the line is already full. Mods can only limit per connection abuse (`rate-limit`).
- Practical steps: share the panel's subdomain, never the node's raw address; keep RCON, query and the management server
  off (they are); use the panel firewall to block an attacking address range when one shows in the log. Java only
  proxies such as TCPShield do not help Bedrock players (Geyser listens on UDP 25598 directly), so they are not
  recommended here.

## 8. Mod list and staged files

### Mods to add

| Mod | Version | File | Licence | Source | Staged in git | sha512 (first 32) / sha1 |
|---|---|---|---|---|---|---|
| LuckPerms | 5.5.85 (Modrinth `v5.5.85-fabric`, 2026-09-19, lists 26.1 to 26.3) | `LuckPerms-Fabric-5.5.85.jar` (1,634,800 bytes) | MIT | modrinth.com/mod/luckperms, github.com/lucko/LuckPerms | yes, `deploy/server-hardening/jars/` | `37de9e16272b6bbf82f422ddf012fa81` / `b3398b22215bcec18813a5cf764b3a8c153fe8ba` |
| Vanilla Permissions | 0.3.6+26.1.2 (2026-05-18; Modrinth lists 26.3) | `vanilla-permissions-0.3.6+26.1.2.jar` (99,126) | MIT | modrinth.com/mod/vanilla-permissions, github.com/DrexHD/VanillaPermissions | yes | `f541fcb459e6b315c87b8de446a1c6bb` / `4de07ac050d7ba8c2faff0f6ac549d2052425282` |
| AntiXray | fabric-1.4.17+26.3 (2026-10-16) | `antixray-fabric-1.4.17+26.3.jar` (147,542) | MIT | modrinth.com/mod/anti-xray, github.com/DrexHD/FabricAntiXray | yes | `09d4db1afefdc0db3936d8bd0e6a7a91` / `8a82f57e3924752b6bc7023aba144a06490b059e` |
| BanHammer | 0.19.0+26.3 (2026-09-11) | `banhammer-0.19.0+26.3.jar` (16,031,496) | LGPL-3.0-only | modrinth.com/mod/banhammer, github.com/Patbox/BanHammer | no (16 MB); the script downloads it from cdn.modrinth.com and checks the hashes | `63f146aff7670909e8b87e1903e55ae9` / `58d7086d7cde35793b903de82e99cf20ba75e031` |
| spark | 1.10.187 (Modrinth `1.10.187-fabric`, 2026-09-19) | `spark-1.10.187-fabric.jar` (3,838,499) | GPL-3.0-only | modrinth.com/mod/spark, github.com/lucko/spark | no (GPL: fetched from the official page, not redistributed) | `c74bf5d5a16b2445ec6ea717eac75641` / `0940b4089affdfca37d6b6d0ea5ecae4dbd8fe98` |

Full sha512 values and download links are in `deploy/server-hardening/manifest.json`; the stage script refuses any
file whose size, sha1 or sha512 differ. Every jar was downloaded into its own empty folder, checked against Modrinth's
hashes, and its `fabric.mod.json` read:

- LuckPerms: `luckperms` 5.5.85, server only, depends on loader 0.18.4+, Minecraft 26.1+, Fabric API modules including
  `fabric-permission-api-v1`, bundles fabric-permissions-api 0.7.0.
- Vanilla Permissions: `vanilla-permissions` 0.3.6+26.1.2, depends on Fabric API and fabric-permissions-api v0
  (bundled); **built against 26.1.2**, mixins with `defaultRequire 1` (a missing target would stop the server at start).
  Its mixin targets were checked against 26.3 by loading it (section 11).
- AntiXray: `antixray` 1.4.17, Java 25, loader 0.18.4+, bundles fabric-permissions-api 0.7.0 and toml4j.
- BanHammer: `banhammer` 0.19.0+26.3, server only, Minecraft 26.1+, bundles sqlite, mysql and postgres drivers,
  placeholder-api 3.2.0 and sgui 2.2.0 for 26.3. It replaces vanilla `/ban`, `/ban-ip`, `/kick`, `/pardon` (mixins).
- spark: `spark` 1.10.187, Minecraft 26.1+, bundles fabric-permissions-api 0.7.0.

All five depend only on what the server already has (Fabric API 0.165.0+26.3, loader 0.20.1, Java 25).

Already installed and relevant: Ledger 1.3.25 (`ledger`, LGPL, depends on loader 0.19.5+, fabric-language-kotlin
1.13.9+, Minecraft ~26.3, Fabric API 0.160.5+; the live jar is byte for byte the Modrinth file, sha1
`14612f68fa374093808f9f2f7a699e13e5e07adb`). Axiom 6.1.5 (All Rights Reserved; never staged or copied).

### Staged config files (full content)

`/config/antixray.toml` (new):

```toml
enabled = false
usePermission = false

[overworld]
enabled = true
engineMode = 1
maxBlockHeight = 64
updateRadius = 2
lavaObscures = false
hiddenBlocks = ["#c:ores", "raw_copper_block", "raw_iron_block", "raw_gold_block"]

[the_nether]
enabled = true
engineMode = 1
maxBlockHeight = 128
updateRadius = 2
lavaObscures = true
hiddenBlocks = ["ancient_debris", "nether_quartz_ore", "nether_gold_ore", "gilded_blackstone"]
```

(The staged file also carries comments explaining each value.)

`/config/ledger.toml` (replaces the live file; the only change is under `[database]`):

```toml
# Kingdom server: 90 days of history (docs/SERVER_HARDENING.md, owner decision O6). -1 was the live value.
autoPurgeDays = 90
```

Every other line is the live file as read on 2026-11-02 (`maxRange = 100`, `purgePermissionLevel = 4`,
`networking = true`, no blacklists).

`/config/banhammer/config.json` (new; BanHammer writes its other files, `messages.json` and `discord-messages.json`,
on first start):

```json
{
  "CONFIG_VERSION_DONT_TOUCH_THIS": 1,
  "punishmentsAreSilent": false,
  "storeAllPunishmentsInHistory": true,
  "muteBlockedCommands": ["me", "msg", "tell", "w", "teammsg"],
  "defaultTempPunishmentDurationLimit": "-1",
  "permissionTempLimit": { "helper": "1h", "moderator": "7d", "admin": "365d" },
  "warnActions": [],
  "standardBanPlayersWithBannedIps": false,
  "autoBansFromIpBansAreSilent": true,
  "defaultOpPermissionLevel": 3,
  "cachePunishmentsLocally": true,
  "showAssociatedAccounts": true,
  "discordWebhookUrls": [],
  "databaseType": "sqlite",
  "sqliteDatabaseLocation": "banhammer-sqlite.db",
  "databaseAddress": "", "databaseName": "", "databaseUsername": "", "databasePassword": "",
  "databaseArgs": { "autoReconnect": "true", "useUnicode": "true" },
  "databasePrefix": "",
  "databaseMaxConnections": 4,
  "blockPunishments": []
}
```

`/server.properties`: only the three keys in the table in section 7 change; the script edits those lines in the live
file and leaves every other line (passwords, ports, seed) untouched.

`/config/luckperms/luckperms.conf` (new): the default file LuckPerms 5.5.85 writes on first start, with one change,
`allow-invalid-usernames = true`. Bedrock names start with a dot (`.Worstly9237`), which LuckPerms rejects by default,
so `lp user .Name ...` would not resolve for half the players. Storage stays H2 in `config/luckperms/` (inside panel
backups), `server = "global"`. Groups come from `luckperms-groups.txt`. A Bedrock player can always be named by
Floodgate UUID instead: `lp user 00000000-0000-0000-0009-01f99022d2b1 info`.

## 9. Admin runbook: who did what, and putting it right

Ledger (`/lg` is short for `/ledger`). Parameters: `range:<blocks>`, `source:<player or @lava, @fire, @tnt ...>`,
`action:<block-break|block-place|item-remove|item-insert|entity-kill|...>`, `object:<id>`, `before:<time>`,
`after:<time>`, `world:<dimension>`. Times read like `2h`, `3d`.

| Task | Command |
|---|---|
| Who touched this block | `/lg inspect` toggles inspect mode; then left click a block (or right click a container) |
| Everything near me in the last day | `/lg search range:20 after:1d` |
| What a player did | `/lg search source:Name after:3d` (Bedrock names keep the leading dot: `source:.Name`) |
| Who took from chests here | `/lg search range:15 action:item-remove after:2d` |
| Go to an entry | `/lg tp <entry number>` |
| See a rollback before doing it | `/lg preview rollback source:Name range:40 after:6h`, then `/lg preview cancel` or `/lg preview apply` |
| Undo a griefer | `/lg rollback source:Name range:40 after:6h` (100 block range at most) |
| Undo a rollback | `/lg restore source:Name range:40 after:6h` |
| Database health | `/lg status` |

Punishments (BanHammer): `/kick Name reason`, `/tempmute Name 30m reason`, `/mute`, `/tempban Name 3d reason`,
`/ban`, `/unban Name`, `/unmute Name`, `/pardon Name` (everything). Add `-s` before the reason for a silent one.
`/banhammer` shows its version; history is in `banhammer-sqlite.db` in the server root.

Staff (owner, console or in game as operator):

| Task | Command |
|---|---|
| Make someone a helper | `lp user Name parent add helper` |
| Promote to moderator | `lp user Name parent add moderator` (keep helper; it is inherited anyway) |
| Remove from staff | `lp user Name parent remove moderator` |
| Builder for an afternoon | `lp user Name parent addtemp builder 4h` |
| What can this person do | `lp user Name permission check charter.admin`; `lp user Name info` |
| Why was a command refused | `lp verbose on Name`, have them try, `lp verbose off` |
| Mute without BanHammer | `lp user Name permission settemp minecraft.chat.send_messages false 1h` |
| Take an operator | `deop Name` in the console (never edit `ops.json` while the server runs; it rewrites the file from memory) |

Lag: `/spark tps`, `/spark health`, `/spark profiler start --timeout 120` (the link opens a report).

Backups: `node tools/playtest/kinetic-backups.mjs status` from the workspace on the owner's PC; restore paths in
section 5.

Charter's own operator tools are unchanged (`/charter admin`, the operators' menu); staff in the admin group reach them
without operator.

## 10. Deploy runbook

Prerequisites: the owner has said yes to this deploy in the conversation; Charter is built from this branch
(`cd charter && JAVA_TOOL_OPTIONS=-Xmx1200m ./gradlew.bat build --no-daemon --console=plain`, all green); the branch is
merged to master first (the deploy skill's rule that master never lags the server).

1. Backups first (section 5): create a panel backup and lock it. Do not continue without one.
2. From the workspace root on the owner's PC:
   ```
   node tools/playtest/hardening-stage.mjs --dry
   ```
   It must print `server mods are for Minecraft 26.3; this set is for 26.3`, the three `server.properties` lines, the
   five jars and four configs, and `Hashes of every jar matched`.
3. Stage without a restart:
   ```
   node tools/playtest/hardening-stage.mjs --no-restart
   ```
   It backs up `server.properties`, `config/ledger.toml` and any replaced jar to `/mods_backup` with suffix
   `before-<time>` (write it down), then uploads. Nothing loads yet.
4. Upload the Charter jar from this branch the usual way, also without a restart:
   ```
   CHARTER_DIR="<worktree>/charter" node tools/playtest/shipped-deploy.mjs --jar "<worktree>/charter/fabric/build/libs/MrPinoys_Charter-0.1.0.jar" --no-restart
   ```
5. Owner restarts from the panel when players are told (or `node tools/playtest/hardening-stage.mjs --restart
   --owner-approved --allow "[Mercenary] MrPinoy123456789"` which refuses if others are online).
6. Read the log: `Done (`, the `Loading N mods` list naming luckperms, vanilla-permissions, antixray, banhammer and spark, LuckPerms' enable line, `Loaded Fabric Permissions`
   (Vanilla Permissions), `Charter state ready`, Geyser started, no `Mixin apply failed`, no `Incompatible mods`.
7. Load the groups: `node tools/playtest/hardening-stage.mjs --lp-setup --owner-approved` (or paste
   `luckperms-groups.txt` into the console). Put staff into groups (section 9).
8. `/deop` the operators the owner chose to remove (owner decision O2). Then, if chosen, the strict step:
   `node tools/playtest/hardening-stage.mjs --lp-setup --owner-approved --strict`.
9. Run the test plan (section 11) with the owner and one Bedrock player.

### Rollback

- Quick (keep LuckPerms data, undo everything else): `node tools/playtest/hardening-stage.mjs --rollback
  before-<time> --no-restart` puts back `server.properties` and the configs saved under that suffix and moves the five
  new jars into `/mods_backup` (`<name>.removed-<time>.jar`). Put the previous Charter jar back from `/mods_backup`
  (`MrPinoys_Charter-0.1.0.before-<time>.jar`) by renaming it. Owner restarts.
- One mod only: rename its jar in `/mods` to end in `.disabled` and restart (Fabric loads only `.jar`).
- If the server will not start: the panel's console shows the failing mod (`Mixin apply failed ... <mod>`), disable that
  jar as above and start again. Worst case restore the locked panel backup taken in step 1.
- LuckPerms leftovers (`config/luckperms/`) and `banhammer-sqlite.db` are harmless without their jars.

## 11. Test plan

What was run here (2026-11-02):

| Check | Result |
|---|---|
| Charter compile, core tests (`CoreSuite`) and fabric tests (`FabricSuite`) on this branch | passed: core 2539, fabric 526 |
| Charter gametests including `HardeningGameTest` (4 tests) | passed: "All 218 required tests passed" (run with `-XX:TieredStopAtLevel=1` because the machine was short of memory) |
| Every jar's sha1 and sha512 against Modrinth, `fabric.mod.json` read | done, all match |
| `hardening-stage.mjs --dry` against the live server (read only: listings and a file read) | passed: version check, property diff, hashes |
| `kinetic-backups.mjs status` against the live server (read only) | passed; it reported the stale backups and both stuck schedules |
| Throwaway local server with the live mod set plus the five new mods and this Charter jar | passed, with limits: see "Local load test" below |

### Local load test (2026-11-02)

A throwaway server folder in the scratchpad, started with the official Fabric server launcher for 26.3 and loader
0.20.1, Fabric API 0.165.0+26.3, every jar from the live `/mods` (copied read only from the panel, sizes checked), the
Charter jar from this branch, the five new mods, the staged configs, and a throwaway probe mod. It ran in Fabric's
gametest mode (`-Dfabric-api.gametest` with `fabric-gametest-api-v1` added), because a normal dedicated start needs
`eula=true` and agreeing to Mojang's EULA is the owner's call. The probe called Mixin's audit after the server started,
which force loads every class any mod's mixins target, then loaded `DedicatedServer` and `DedicatedPlayerList`
(gametest mode does not use them, and Vanilla Permissions, BanHammer and Floodgate patch them).

Results:
- 177 mods (with nested jars) loaded and initialised together. LuckPerms v5.5.85 enabled, `Loaded Fabric Permissions`
  (Vanilla Permissions), `Successfully initialized antixray`, `BanHammer connected successfully to SQLite database`,
  `Charter state ready`, Ledger `Purging actions older than 90 days` (the staged config was read), spark and Axiom
  initialised.
- The mixin audit applied every mod's mixins with no apply failure; the only audit errors were three
  `ClassAlreadyLoadedException` notes for classes whose Trinkets mixins the Polymer patch cancels (the same "Cancelled
  mixin" warnings the live log shows). Vanilla Permissions' targets, built for 26.1.2, all exist in 26.3.
- The first run found that AntiXray reads `config/antixray.toml`, not the `antixray-fabric.toml` its jar carries; the
  staged file was renamed and a second run read it with no unknown block warnings.
- Geyser 2.12.1 cannot run in gametest mode (it casts the server to a dedicated server and throws), so the last run
  left Geyser out and kept Floodgate: Floodgate booted (`Took 20ms to boot Floodgate`) and the audit was clean with it.
  Geyser's own mixins are therefore unchecked here; it is unchanged from the live server, where it runs.
- LuckPerms' default `allow-invalid-usernames = false` would refuse Bedrock names; the staged `luckperms.conf` turns it
  on (section 8).
- The staged `ledger.toml` and BanHammer `config.json` came out of a start and stop unchanged.

Not tested here: a real dedicated server start, players joining (Java or Bedrock), LuckPerms commands and node
names at run time, AntiXray's effect on chunks a client sees, Polymer and AntiXray together on a client, BanHammer's
commands, a panel backup restore. These are tests T1 to T11 above, on the live server after the owner's restart.

On the live server after the deploy (owner with one Java and one Bedrock account):

- T1 Boot: log lines in deploy step 6; `/lp info` answers; `/spark tps` answers for the owner; re-read
  `/server.properties` and check the three changed keys stuck (the panel may rewrite that file from its own settings);
  a Bedrock player joins and `lp user .Name info` resolves.
- T2 Race nodes: a member of each kingdom still shows their race look and trait (Fairy glides, Fae double jumps,
  Merfolk swim dash); an admin group member who is in no kingdom shows **no** race look; a Foundry race weapon still
  refuses someone of another race.
- T3 AntiXray: on Java, F3 and an xray pack (or spectator inside stone) shows no diamonds behind stone below y 64;
  mined tunnels still show exposed ores; Polymer blocks and Foundry items look the same as before; on Bedrock, chunks
  load normally near the hub and in caves; Chunky `chunky start` on a small radius completes without errors in the log.
- T4 Charter nodes: a helper (no op) cannot open `/charter admin`; an admin group member (no op) can, and can build in
  the hub; with the strict step, a deopped former operator cannot build in the hub.
- T5 Vanilla Permissions names: `lp verbose on` while a moderator runs `/gamemode spectator`, `/gamemode creative`,
  `/gamemode survival OtherName`, `/tp OtherName`, `/tp OtherName Name`; the verbose output must show the node names
  used in `luckperms-groups.txt` (`minecraft.command.gamemode.creative`, `...survival.target`, `...teleport.targets`).
  Fix the group file if a name differs.
- T6 Ledger and BanHammer as a helper: `/lg inspect`, `/lg search range:10`, `/tempmute` a test account for 1 minute,
  then `/tempmute ... 2h` must be refused (1 hour limit).
- T7 Spawn protection off: a non operator opens the hub lectern terminal and a hub door; still cannot break a hub block.
- T8 serverside-carryon: a stranger sneak right clicks a kingdom's villager inside its keep: refused; on neutral ground
  it still works.
- T9 Rate limit: a builder pastes a large Axiom blueprint; nobody is kicked with `exceeded_packet_rate`.
- T10 Axiom: a non operator with no builder group gets Axiom's "You don't have permission" goodbye; with `builder` it
  works.
- T11 Backups: next morning `kinetic-backups.mjs status` shows a backup from 08:20 UTC and no STUCK schedule.

## 12. Owner decisions

| # | Decision | Recommendation |
|---|---|---|
| O1 | Run the strict step (default group loses `charter.bypass` and `charter.admin`) | Yes, after O2 |
| O2 | Who stays operator (today: `.Worstly9237`, `.StarlessTuna`, `MrPinoy123456789`, `Voidaimbot`, `kixra027`, `HowMadYouNow`, `.Jokerboi131039`, all level 4) | Owner only (plus one co-owner at most); others into admin, moderator or helper. `.Worstly9237` shares the owner's address; if it is the owner's own Bedrock account, it can stay in admin instead |
| O3 | Allow list for everyone, or Bedrock only (`fwhitelist`) | Not now; `requireAffiliation` already gates newcomers. Revisit if ban evasion starts |
| O4 | Keep Axiom installed | Keep, owner and temporary builders only |
| O5 | Keep BanHammer (16 MB, replaces vanilla `/ban` and `/kick`) | Keep: tempban and mute are what non operator staff need |
| O6 | Ledger history window (`autoPurgeDays`) | 90 days (staged); -1 keeps everything and the database grows without limit |
| O7 | Keep the newest pre wipe backup locked | Pull it to the PC first (`kinetic-backups.mjs pull 38cbf316-...`), then delete it to free the slot |
| O8 | Exclude the old archives from panel backups (`.pteroignore`) | Yes, after pulling them off the server once |
| O9 | AntiXray strength | Mode 1 now; mode 3 if xrayers are still caught |
| O10 | simulation-distance 8 | Yes; back to 10 if farm players complain and spark shows room |
| O11 | Builders also get `charter.bypass` (build by hand in the hub, but also enter closed keeps and move any Nexus) | No by default; give it for the day when a builder needs it |
