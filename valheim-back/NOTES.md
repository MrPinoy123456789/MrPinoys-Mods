# MrPinoys Back: status and findings

Last updated 2026-09-14. Tested against a Linux dedicated server running Valheim
l-1.0.12 (network version 40) with BepInExPack Valheim 5.4.2350, and built against
the local client assembly (1.0.0 build 25253764). Client stays vanilla.

## Where things stand

| Piece | Status |
|---|---|
| Plugin loads on the 1.0.12 dedicated server, all Harmony patches apply | working |
| Tombstone discovery from ZDOs (owner ID, owner name, position, time of death) | working |
| Map ping trigger (middle-click the skull twice within 5 s) | **working, verified in game** |
| Teleport via vanilla `RPC_TeleportPlayer` routed RPC | working |
| Chat trigger `/back` | unverified; cannot reach the server with one player online (see below) |
| Server console commands `back ...` | not usable on this server; stdin is not attached (see below) |
| Fallback death-position record | inactive on this server (no server-side Player objects, see below) |

## Findings that shaped the design

These were confirmed by decompiling `assembly_valheim.dll` (ilspycmd) and by the
trace log from the live server. They are worth re-checking after any game update.

1. **Clients accept a server-sent teleport with no sender check.**
   `Chat.RPC_TeleportPlayer(sender, pos, rot, distant)` calls
   `Player.m_localPlayer.TeleportTo(...)` unconditionally. `Character.RPC_TeleportTo`
   on the player's ZNetView does the same, checking only that the receiver is the
   owner. Either lets a server-only plugin move a vanilla client.

2. **Since 1.0, chat is sent per recipient, never to the server.**
   `Chat.CheckPermissionsAndSendChatMessageRPCsAsync` loops over the player list and
   sends one routed RPC per player. A routed RPC addressed to yourself is handled
   locally and never serialised (`ZRoutedRpc.InvokeRoutedRPC`). Consequences: with one
   player online the server sees no chat at all; with two or more, the copies are
   relayed through `ZRoutedRpc.RPC_RoutedRPC` on the server and a Harmony prefix can
   read them. The plugin does this but it has not been exercised yet.

3. **Map pings still go to everybody.** `Chat.SendPing` targets `0L`, so the server
   receives them even from a solo player. That is why the ping is the primary trigger.
   The ping is **middle-click** on the map (`Minimap.OnMapMiddleClick`), or double
   click with the ping pin type selected. Left double-click on a death pin is ignored
   by the game entirely.

4. **Map pings are coarse.** Deliberate pings on the skull landed 36 to 65 m away from
   the tombstone. The match radius is 150 m (config `PingRadiusMeters`); 25 m failed.

5. **`ZNetPeer.m_playerID` is always 0 in 1.0.** The server still registers the
   `PlayerID` ZRpc but no client code sends it any more. The profile's player ID is
   available on the character ZDO as `ZDOVars.s_playerID` (set by
   `PlayerProfile.LoadPlayerData` via `Player.SetPlayerID`), and the tombstone ZDO
   carries the same ID in `s_owner` plus the name in `s_ownerName`. The plugin reads
   the character ZDO first and falls back to matching tombstones by owner name.

6. **This dedicated server has no `Player` GameObjects for connected players.**
   `Player.GetAllPlayers()` never matched the peer's character ID. Anything that
   needs the player must go through the ZDO (`ZDOMan.instance.GetZDO(peer.m_characterID)`)
   or a routed RPC aimed at that ZDOID. This also means the `Player.RPC_OnDeath`
   postfix never fires here, so the fallback death-position record is dead weight on
   this server. It stays in the code for servers that do instantiate players (a
   hosting client, for example).

7. **The vanilla dedicated server neither reads its console nor prints Terminal
   output.** `Terminal.AddString` writes to a UI text component. The plugin mirrors
   Terminal output into the BepInEx log (that part works: the `help` banner shows in
   the log) and reads stdin on a background thread. On this server stdin is closed
   (`stdin reached end of stream` at startup), which is normal under systemd or a
   hosting panel, so typed commands never reach the process. Fixing that is a launch
   side change (`screen`/`tmux`, or `StandardInput=tty` in the unit); nothing in the
   plugin can do it.

8. **Tombstone lookup API.** `ZDOMan.GetAllZDOsWithPrefabIterative("Player_tombstone",
   list, ref index)` returns true when the sweep is complete; loop until it does. The
   world in question holds ~267k ZDOs and the sweep is fast enough to run per ping.

See `../VALHEIM-SERVER-SIDE.md` for the combined findings of this and later
server-side mods.

## Open items

- Verify the `/back` chat trigger with two players online. The trace line to look for
  is `ChatMessage from uid ... type 2 '/back'` (shout) or `Say from uid ...` (normal chat).
- Decide whether the console path is worth keeping. Options: leave it for servers
  with an attached stdin, or replace it with an admin-only chat command
  (`/back <name>`) checked against the server's admin list. The latter has the same
  two-players-online limitation as any chat.
- `TripsPerTombstone` counter is in memory only and resets on restart. Fine for now.
- Turn `VerboseLog` off once the trace is no longer needed; it logs every tombstone in
  the world on each ping.

## Files

- `src/BackPlugin.cs`: entry point, config, Harmony bootstrap.
- `src/Tombstones.cs`: ZDO sweep and matching (by ID, then by name).
- `src/Teleporter.cs`: destination selection, cooldown, ping counting, the teleport RPC,
  on-screen messages.
- `src/Patches.cs`: `RPC_OnDeath` postfix, `RPC_RoutedRPC` prefix (chat and ping
  sniffing), console command registration.
- `src/ConsoleInput.cs`: stdin reader and Terminal output mirror.
- `src/DeathBook.cs`: fallback death-position store.
- Output: `bin/MrPinoys_Back.dll`, copied to `../dist/`.
