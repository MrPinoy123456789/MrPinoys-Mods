# MrPinoys Back (Valheim 1.0, server-side only)

Lets a player travel to their own tombstones. Installed on the dedicated server
only; every client stays vanilla.

## How it works

- Tombstones are the source of truth. Each one is a ZDO the server keeps, stamped by
  the vanilla client with the owner's player ID, name and time of death, and it
  vanishes on its own once looted. Nothing to record, and deaths from before the
  plugin was installed work too.
- To move the player, the server invokes the vanilla routed RPC `RPC_TeleportPlayer`
  on that peer. An unmodified client handles it exactly like a portal trip (loading
  screen included). The client performs no sender check, so no client mod is needed.
- Deaths that leave no tombstone (keep-inventory worlds, empty inventory) fall back to
  the position where the server saw the player die, saved to
  `BepInEx/config/mrpinoys.valheim.back.deaths.txt` and consumed on use.

## Triggers

| Trigger | Default | Alone on the server? | Status |
|---|---|---|---|
| **Middle-click** the map on your skull twice within 5 s | on | yes | verified on a live 1.0.12 server |
| Type `/back` in chat (goes to the newest tombstone) | on | no (see below) | untested, needs two players online |
| Server console: `back <player>` | always | yes | needs a server with an attached stdin (see below) |

The ping is a **middle-click** on the map. Left double-clicking the skull does nothing;
the game ignores double clicks on death pins.

Why chat is unreliable: since 1.0 the client sends each chat line to each recipient
separately and never to the server itself. With two or more players online the copies
are relayed through the server and the plugin sees them; a solo player's chat never
leaves their machine. The map ping is the one chat path that still goes to everybody,
which is why it is the primary trigger. It also lets a player with several tombstones
pick which one to visit.

## Console commands (server console)

The vanilla dedicated server does not read its own console window and prints Terminal
output nowhere headless. The plugin adds both: it reads lines typed into the server's
stdin and mirrors Terminal output into the log. This only works when the process
actually has a stdin (run under `screen`/`tmux`, or `StandardInput=tty` in a systemd
unit). If the log says `stdin reached end of stream` at startup, typed commands cannot
reach the process and this section does not apply. Type `help` to check; an unknown
command answers with "is not a recognized command".

```
back <player>          send a connected player to their newest tombstone (ignores cooldown and trip limit)
back list              every tombstone in the world, plus any fallback death points
back forget <player>   drop a fallback death point (tombstones are cleared by looting them)
```

## Config (`BepInEx/config/mrpinoys.valheim.back.cfg`)

| Key | Default | Meaning |
|---|---|---|
| General.CooldownSeconds | 60 | minimum gap between player-triggered trips |
| General.TripsPerTombstone | 0 | trips allowed per tombstone, 0 = unlimited (resets on server restart) |
| General.FallbackToDeathPoint | true | use the recorded death position when there is no tombstone (only on servers that instantiate Player objects; see NOTES.md) |
| General.VerboseLog | true | trace every ping, chat packet and tombstone lookup; turn off once it works |
| Trigger.Ping.Enabled | true | |
| Trigger.Ping.PingRadiusMeters | 150 | how close the ping must be to the tombstone (map pings are coarse) |
| Trigger.Ping.PingsRequired | 2 | 1 for a single ping, 2 for a double ping |
| Trigger.Ping.WindowSeconds | 5 | pings must land inside this window |
| Trigger.Chat.Enabled | true | |
| Trigger.Chat.Command | /back | |

## Install

1. Build: `dotnet build -c Release` (needs a Valheim install to reference; set
   `ValheimManaged` in `Back.csproj` or pass `-p:ValheimManaged=<path>`).
2. Copy `bin/MrPinoys_Back.dll` to the server's `BepInEx/plugins/`.
3. Restart the server. The log prints `MrPinoys Back 0.1.0 loaded`.

See `NOTES.md` for the current status, the decompile findings behind each design
decision, and open items.

## Known limits

- `Player.TeleportTo` does not check for non-portal items, so a trip carries ore.
  Set `TripsPerTombstone` to 1 if that bothers you.
- A tombstone in a deadly spot can be visited repeatedly (bed, tombstone, die, bed).
  Cooldown and `TripsPerTombstone` are the levers.
- The console `back <player>` command targets connected peers only; on a
  player-hosted (non-dedicated) world the host is not a peer and cannot target
  themself.
- Built against Valheim 1.0.0 (build 25253764). The routed RPC signatures it reads
  (`ChatMessage`, `Say`, `RPC_TeleportPlayer`) are version specific; re-check them
  after a game update.
