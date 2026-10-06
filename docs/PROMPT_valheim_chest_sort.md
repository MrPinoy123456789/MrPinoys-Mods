# Prompt: server-side only chest sorting for Valheim 1.0

Copy everything below the line into a fresh Claude Code session started in
`A:\MrPinoys Mods`.

---

Build a **server-side only** Valheim 1.0 BepInEx plugin that sorts chest contents.
It is installed only in the dedicated server's `BepInEx/plugins/`; every client stays
vanilla and must not need anything installed. Work in a new folder
`A:\MrPinoys Mods\valheim-sort\`, and copy the finished dll to `A:\MrPinoys Mods\dist\`.

Read `A:\MrPinoys Mods\CLAUDE.md` first (house rule: no em dashes or double hyphens
anywhere you write, including code comments and log strings).

## Start from the sibling project, it already solved the hard parts

`A:\MrPinoys Mods\valheim-back\` is a working server-side only plugin (teleport to
tombstone) verified on the same server. Read its `NOTES.md` and `README.md` before
writing anything; they record what is true about 1.0 networking and this specific
server. Reuse directly:

- `Back.csproj` and `nuget.config`: net48, BepInEx.Core 5.4.21 from the BepInEx NuGet
  feed, HarmonyX 2.10.2, references to `assembly_valheim.dll`, `assembly_utils.dll`
  (that is where `GetStableHashCode` lives), `Splatform.dll`, `UnityEngine*.dll` from
  the local client install at `A:\Steam\steamapps\common\Valheim\valheim_Data\Managed`.
  `dotnet build -c Release` works with the installed .NET 6 SDK. Copy these two files
  and rename.
- `src/BackPlugin.cs`: plugin skeleton, config binding, Harmony bootstrap, the
  `ServerSide.IsServer()` gate and the `ServerSide.Trace()` verbose logger. Keep the
  `VerboseLog` config and trace generously; the only feedback loop with the server is
  the user pasting `BepInEx/LogOutput.log` back to you.
- `src/Patches.cs`: the `ZRoutedRpc.RPC_RoutedRPC` prefix that sniffs chat and map
  pings (deserialize a `RoutedRPCData` copy, reset `pkg` position in `finally`).
- `src/Teleporter.cs`: `PlayerIdForPeer` (read `ZDOVars.s_playerID` off the character
  ZDO), `Message()` (routed RPC `"Message"` aimed at the character ZDOID shows a
  centre-screen message on a vanilla client).
- `src/Tombstones.cs`: sweeping ZDOs by prefab with
  `ZDOMan.GetAllZDOsWithPrefabIterative(prefab, list, ref index)`, loop until it
  returns true.

## Facts about the target server (do not rediscover these)

- Linux dedicated server, Valheim `l-1.0.12` (network version 40), BepInExPack
  Valheim 5.4.2350 (BepInEx 5.4.23.5), Unity 6000.0.75f1. The user's local client
  assembly is 1.0.0; method names have matched so far but re-check anything you
  depend on.
- The server has **no `Player` GameObjects** for connected players.
  `Player.GetAllPlayers()` is empty. Assume the same may be true for `Container`
  objects: design at the **ZDO level** (`ZDOMan.instance.GetZDO(id)`, `GetByteArray`,
  `Set`) and treat any `ZNetScene.FindInstance` dependency as something to verify in
  the log before relying on it.
- `ZNetPeer.m_playerID` is always 0 in 1.0; the profile ID is on the character ZDO
  (`s_playerID`), the name in `peer.m_playerName`.
- **Chat never reaches the server when one player is online** (sent per recipient,
  self-targeted routed RPCs are handled locally). With two or more online it is
  relayed through `ZRoutedRpc.RPC_RoutedRPC` and can be sniffed. Do not make a chat
  command the only trigger.
- **Map pings do reach the server** (`Chat.SendPing` targets everybody). Middle-click
  on the map. Pings are coarse (tens of meters off).
- **The server console is not usable**: stdin is closed on this server
  (`stdin reached end of stream`), and Terminal output goes nowhere headless. Do not
  spend time on console commands. Registering one is harmless but unverifiable.
- The user tests by installing the dll, restarting, doing the action in game, and
  pasting the log. Budget for several round trips; make every failure path log why.

## Decompile before you guess

`ilspycmd` (8.2.x) is installed at `~/.dotnet/tools/ilspycmd`. Dump a class with:

```bash
~/.dotnet/tools/ilspycmd -t Container "/a/Steam/steamapps/common/Valheim/valheim_Data/Managed/assembly_valheim.dll" > /a/tmp/Container.cs
```

Classes you will need: `Container`, `Inventory`, `ItemDrop` (`ItemData`,
`SharedData`), `ZDOVars`, `ZDO`, `ZDOMan`, `ZNetView`, `ZRoutedRpc`, `Chat`,
`Player`, `ObjectDB`. Things already confirmed in the 1.0 assembly:

- Container contents live on the container ZDO as a **byte array** under
  `ZDOVars.s_items` ("items"). `Container.Save()` does
  `zdo.Set(s_items, pkg.GetArray())` after `m_inventory.Save(pkg)`;
  `Container.Load()` reads it with `GetByteArray` and `m_inventory.Load(pkg)`, but
  only when `zdo.DataRevision` changed **and the chest is not in use**.
- `ZDOVars.s_inUse` ("InUse", int 0/1) marks a chest as open. `Container.CheckForChanges`
  runs once a second on clients and calls `Load()`.
- Opening a chest: the client sends `RPC_RequestOpen` to the ZDO; the **owner** of the
  ZDO answers and transfers ownership to the requester (`zdo.SetOwner(uid)`). If the
  owner is the server and the server has no `Container` instance for that ZDO, the
  request is silently dropped and nobody can open the chest. **Never leave the
  server as the owner of a container ZDO.** After writing, release it
  (`SetOwner(0L)`) so a nearby client claims it, and verify in game that the chest
  still opens.
- `Inventory` can be constructed standalone (`new Inventory(name, sprite, w, h)`) and
  has `Load(ZPackage)` / `Save(ZPackage)`. Item identity for sorting comes from
  `ItemData.m_shared.m_name`, `m_itemType`, `m_quality`, `m_stack`, `m_maxStackSize`.
  `Inventory.Load` resolves prefabs through `ObjectDB.instance`; confirm `ObjectDB`
  exists on the dedicated server (it should, the server loads prefabs), and log if
  `GetItemPrefab` returns null for anything.

## Scope decisions (make these, do not ask)

1. **Player inventories are out of scope for a server-only mod.** The player's bag is
   saved in the character file on the client and is not in any ZDO the server holds.
   State this plainly in the README rather than building a half solution. If you
   find evidence in the 1.0 decompile that contradicts this, say so with the class
   and field you found.
2. **Trigger: sort a chest automatically when it is closed.** The server can watch
   container ZDOs for `s_inUse` going 1 to 0 (poll container ZDOs near connected
   peers on a short interval, or find a cheaper hook in the decompile) and sort right
   after. This works for a solo player and needs no command. Make it a config
   toggle, default on.
3. **Secondary trigger, optional:** a map ping near a chest sorts it (or all chests
   within N meters), reusing the ping sniffing from valheim-back. Only if it costs
   little.
4. **Sort order:** group by `m_itemType` in a sensible order (materials, consumables,
   weapons, armor, tools, trophies, misc), then by name, then by quality descending;
   merge partial stacks up to `m_maxStackSize`. Keep a config for the type order as a
   comma-separated list. Never drop, duplicate, or change the total count of any
   item: assert total counts before and after and refuse to write if they differ,
   logging both.
5. **Safety:** skip chests that are in use, skip wagons and ships' storage if their
   `Container` differs, skip containers whose ZDO owner is a connected peer that
   currently has it open, and skip any ZDO whose prefab is not a player-built chest
   (check `piece_chest`, `piece_chest_wood`, `piece_chest_private`,
   `piece_chest_blackmetal`, and whatever else 1.0 has; enumerate from the decompile
   or by sweeping prefabs and logging names once).
6. Names, guid `mrpinoys.valheim.sort`, assembly `MrPinoys_Sort`, plugin name
   `MrPinoys Sort`, version 0.1.0. Config file `mrpinoys.valheim.sort.cfg`.

## Deliverables

- `valheim-sort/` with csproj, nuget.config, `src/`, `.gitignore` (`bin/`, `obj/`),
  a `README.md` (what it does, triggers, config table, install steps, known limits)
  and a `NOTES.md` in the same style as `valheim-back/NOTES.md`: what is verified,
  what is assumed, what to look for in the log.
- `dist/MrPinoys_Sort.dll`.
- A one-paragraph test script for the user: install, restart, open a chest, drop
  items in unsorted, close it, reopen; then which log lines prove each step.

## Expected log narrative on success

```
[Info :MrPinoys Sort] MrPinoys Sort 0.1.0 loaded.
[Info :MrPinoys Sort] [trace] Chest 123456:78 (piece_chest_wood) opened by Adolp
[Info :MrPinoys Sort] [trace] Chest 123456:78 closed; 14 stacks, 312 items; owner 3035769512
[Info :MrPinoys Sort] [trace] Sorted 123456:78: 14 -> 11 stacks, 312 items, wrote 1.2 KB, owner released
```

If the log shows the sort wrote but the chest reopens unchanged, the client did not
reload: check `DataRevision` actually advanced and that `s_inUse` was 0 at write
time. If the chest will not open at all afterwards, ownership was left on the server.
