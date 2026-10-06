# Server-side only Valheim 1.0 modding: what we know

Everything learned building `valheim-back` (teleport to tombstone, verified in game),
`valheim-sort` (chest sorting, built from the decompile) and `valheim-float` (sunk
items swapped for floating crates, built from the decompile). Read this before
starting another plugin for the same server. Findings marked **verified** were seen
in the live log; the rest come from the 1.0 decompile and should be re-checked in
the log the first time they matter.

Last updated 2026-09-19.

## 1. Environment

| Item | Value |
|---|---|
| Server | Linux dedicated, Valheim `l-1.0.12`, network version 40 |
| Loader | BepInExPack Valheim 5.4.2350 (BepInEx 5.4.23.5), Unity 6000.0.75f1 |
| Local client (build reference) | Valheim 1.0.0, build 25253764, at `A:\Steam\steamapps\common\Valheim\valheim_Data\Managed` |
| SDK | .NET 6 SDK installed; target `net48`, `LangVersion 9` |
| Packages | `BepInEx.Core 5.4.21`, `HarmonyX 2.10.2` from `https://nuget.bepinex.dev/v3/index.json`, `Microsoft.NETFramework.ReferenceAssemblies 1.0.3` |
| References | `assembly_valheim.dll`, `assembly_utils.dll` (holds `GetStableHashCode`), `Splatform.dll`, `UnityEngine.dll`, `UnityEngine.CoreModule.dll`, all `Private=false` |
| Decompiler | `~/.dotnet/tools/ilspycmd` 8.2 |
| Clients | vanilla, always; nothing may depend on a client-side patch |

The method names in the 1.0.0 client assembly have matched the 1.0.12 server so far.
Re-check any signature you depend on after a game update.

### Starting a new plugin

1. Copy `valheim-sort/Sort.csproj`, `nuget.config` and `.gitignore` (`bin/`, `obj/`),
   rename `AssemblyName` and `RootNamespace`.
2. Copy the skeleton from `valheim-sort/src/SortPlugin.cs`: `[BepInPlugin]`, config
   binding in `Awake`, `Harmony.PatchAll`, `ServerSide.IsServer()`,
   `ServerSide.Trace()` behind a `VerboseLog` config, `ServerSide.PeerName()`,
   `ServerSide.Message()`, `ServerSide.PlayerKey()`.
3. Naming: guid `mrpinoys.valheim.<name>`, assembly `MrPinoys_<Name>`, plugin name
   `MrPinoys <Name>`, config `mrpinoys.valheim.<name>.cfg`, output copied to `dist/`.
4. `dotnet build -c Release` puts the dll in `bin/` (no framework subfolder).
5. Write `README.md` (what, triggers, config table, install, limits) and `NOTES.md`
   (verified vs assumed, findings, what to look for in the log, open items).

### Decompile before guessing

```bash
~/.dotnet/tools/ilspycmd -t Container "/a/Steam/steamapps/common/Valheim/valheim_Data/Managed/assembly_valheim.dll" > /a/tmp/Container.cs
# whole assembly (one big file, ~150k lines, grep it):
~/.dotnet/tools/ilspycmd "/a/Steam/.../assembly_valheim.dll" -o /a/tmp/full
```

Helper classes live in `assembly_utils.dll` (`BinarySearchDictionary`, string
hashing). Valheim declares its own `Console` class, so a test console app must write
`System.Console`.

### Testing offline

Pure data code (byte formats, sorting) can be unit tested outside Unity: a `net48`
console project referencing the same dlls, with `assembly_valheim.dll`,
`assembly_utils.dll`, `UnityEngine*.dll` and `Splatform.dll` copied next to the exe.
`ZPackage`, `ZDOID`, `Vector2i`, `GetStableHashCode` all work without a running game.
`valheim-sort` did this for its item codec (see the scratch project pattern in its
NOTES). Anything touching `ZNet`, `ZDOMan`, `ObjectDB` or a `MonoBehaviour` needs the
live server.

## 2. What the dedicated server is and is not

- **No `Player` GameObjects.** `Player.GetAllPlayers()` is empty for connected
  peers (**verified**). `Player.RPC_OnDeath` postfixes never fire. Assume the same
  for `Container`, `Piece` and any other component: the server does not instantiate
  the scene objects it syncs. Work at the ZDO level.
- **`ObjectDB.instance` and `ZNetScene.instance` exist** (the server needs prefabs
  to spawn things). Use them for prefab lookups (`ObjectDB.GetItemPrefab(hash)`,
  `ZNetScene.GetPrefab(hash)`, `ZNetScene.m_prefabs`) and read component fields
  off the prefab (`prefab.GetComponent<Container>().m_width`), never off instances.
  Verify with a log line the first time.
- **Console is unusable.** stdin is closed (`stdin reached end of stream` at
  startup, **verified**) and `Terminal` output goes to a UI text component that does
  not exist headless. Registering a `Terminal.ConsoleCommand` is harmless but cannot
  be exercised. `valheim-back/src/ConsoleInput.cs` mirrors Terminal output into the
  BepInEx log and reads stdin on a thread, useful only if the launch script attaches
  a tty.
- **The only feedback loop is `BepInEx/LogOutput.log`** pasted back by the user.
  Log every decision and every failure path with its reason. Budget several
  install/restart/paste round trips per feature.

## 3. Identity: peers, players, ZDOs

| Want | How |
|---|---|
| Connected peers | `ZNet.instance.GetPeers()`, `ZNet.instance.GetPeer(uid)` |
| Peer display name | `peer.m_playerName` |
| Peer's character ZDO | `ZDOMan.instance.GetZDO(peer.m_characterID)` (the server always holds it) |
| Profile player ID | `characterZdo.GetLong(ZDOVars.s_playerID)` (**verified**). `ZNetPeer.m_playerID` is always 0 in 1.0; no client sends the `PlayerID` RPC any more. |
| Stable key across reconnects | the profile player ID; the session uid (`peer.m_uid`) changes every connection |
| Is the peer dead | `characterZdo.GetBool(ZDOVars.s_dead)` |
| Player position | `characterZdo.GetPosition()` (exact) |
| Server's own uid | `ZDOMan.GetSessionID()` |
| Who owns a ZDO | `zdo.GetOwner()` (0 = nobody), `zdo.HasOwner()`, `zdo.IsOwner()` (owner is this process) |

`ZDOID.ToString()` prints `user:id`, fine for logs. ZDOIDs are never reused within a
world, so a remembered ID can safely be looked up later; `GetZDO` returns null once
the object is gone.

## 4. How data moves (the ZDO model)

- A ZDO is a bag of typed values keyed by string hash (`ZDOVars.s_*` are the
  precomputed hashes). Read with `GetInt/GetLong/GetFloat/GetString/GetByteArray/
  GetBool(hash, default)`, write with `Set(hash, value)`.
- **Every `Set` bumps `DataRevision`** (except when the value is equal; byte arrays
  compare by reference, so a fresh array always counts). `SetOwner` bumps
  `OwnerRevision`. On a client, either also queues the ZDO in
  `ZDOMan.m_clientChangeQueue`.
- **Clients only ever send the ZDOs they changed.** `ZDOMan.CreateSyncList` on the
  client walks `m_clientChangeQueue`; the server side walks the peer's sector and
  sends anything with a newer revision than the peer has seen.
- **Receivers accept purely by revision.** `ZDOMan.RPC_ZDOData` applies an incoming
  ZDO when its `DataRevision` is higher than the local one, whoever sent it and
  whoever owns it. So the server can `Set` a value on a client-owned ZDO and the
  owning client will apply it (`valheim-sort` relies on this; unverified in game as
  of this writing, decompile is unambiguous).
- **Revision race.** If the client changes the ZDO again before the server's write
  arrives, both sit at N+1 and each ignores the other until the client's next change
  (N+2) wins. Do not force a bigger revision jump to win: that overwrites a real
  player action. Debounce instead (a short settle delay after the last client
  change) and re-check preconditions right before writing.
- **`ZDO.Deserialize` is only called from `RPC_ZDOData`.** A Harmony postfix on it,
  gated on `IsServer()`, is an exact and cheap "a client changed this ZDO" hook. Filter
  by `zdo.GetPrefab()` (an int hash) against a `HashSet<int>`. No polling needed.
- **Sweeping ZDOs by prefab:** `ZDOMan.instance.GetAllZDOsWithPrefabIterative(name,
  list, ref index)` returns true when done; loop until it does (**verified**, fast
  enough per event on a 267k ZDO world). By area:
  `ZDOMan.instance.FindSectorObjects(ZoneSystem.GetZone(pos), new SimulationDistance(1, 0, classic: true), list)`
  covers the 3x3 zones (64 m each) around a point.
- **Ownership.** The server's `ZDOMan.ReleaseNearbyZDOS` hands unowned persistent
  ZDOs to a peer whose active area contains them, and releases ZDOs whose owner
  walked away. Component RPCs (`ZNetView.InvokeRPC`) are routed to the ZDO owner and
  answered there; an owner with no instance of the component (the server, see
  section 2) drops them silently. **Never leave the server as owner of an
  interactable ZDO.** Prefer not to touch ownership at all; if you must, `SetOwner(0L)`
  and let the server reassign it.
- `ZDOVars.s_sessionHashes` lists per-session values that are not persisted
  (`InUse`, velocities, `support`, ...). Everything else survives a save.

## 5. What reaches the server from a vanilla client

| Client action | Reaches the server solo? | Where to hook |
|---|---|---|
| Chat (`Say`, `ChatMessage` text, shouts) | **no** when one player is online (**verified**); since 1.0 chat is sent per recipient and a self-targeted routed RPC is handled locally. With 2+ online the copies relay through `ZRoutedRpc.RPC_RoutedRPC` | `RPC_RoutedRPC` prefix, method hash `"ChatMessage"` (target ZDO none) or `"Say"` (target ZDO set) |
| Map ping (middle-click on the map) | **yes** (**verified**); `Chat.SendPing` targets everybody | `RPC_RoutedRPC` prefix, `ChatMessage` with type 3 (`Talker.Type.Ping`); position is coarse, 36 to 65 m off in practice, use a 150 m match radius for tombstones, 40 m for chests |
| Emote (`/point`, `/wave`, ...) | yes; `Player.StartEmote` sets `s_emoteID` (counter) and `s_emote` (lowercased name) on the player's own ZDO, `StopEmote` bumps the counter with an empty name | `ZDO.Deserialize` postfix on prefab `Player`; act when the counter changes and the name matches. Exact position. |
| Opening / closing a chest | yes; `s_inUse` 1/0 on the container ZDO, items saved on every change while open | `ZDO.Deserialize` postfix on chest prefabs |
| Any other ZDO write by the owner (sign text, health, attach, sleep, ...) | yes | same postfix |
| "Place stacks" / take-all / open requests (`RPC_RequestStack`, `RPC_RequestTakeAll`, `RPC_RequestOpen`) | only when the requester does not own the ZDO; otherwise handled locally | not reliable; watch the resulting ZDO change instead |
| Death | the `OnDeath` broadcast reaches the server but there is no `Player` instance to run the handler; tombstones are ZDOs (`Player_tombstone`, `s_owner`, `s_ownerName`, `s_timeOfDeath`) and are the reliable record (**verified**) | prefab sweep |
| Client console commands | no | |

Sniffing a routed RPC without consuming it:

```csharp
[HarmonyPatch(typeof(ZRoutedRpc), "RPC_RoutedRPC")]
static class Sniff {
    static void Prefix(ZRpc rpc, ZPackage pkg) {
        if (!ServerSide.IsServer() || pkg == null) return;
        int pos = pkg.GetPos();
        try {
            var data = new ZRoutedRpc.RoutedRPCData();
            data.Deserialize(pkg);
            // data.m_methodHash, m_senderPeerID, m_targetPeerID, m_targetZDO, m_parameters (a ZPackage)
        } finally { pkg.SetPos(pos); }
    }
}
```

`ChatMessage` parameters: `Vector3 pos, int type, UserInfo (Deserialize(ref pkg)),
string text`. `Say`: `int type, UserInfo, string text`. The same chat line arrives
once per recipient; collapse duplicates by sender and text within ~1.5 s.

## 6. What the server can do to a vanilla client

| Effect | Mechanism | Status |
|---|---|---|
| Teleport the player (loading screen, like a portal) | `ZRoutedRpc.instance.InvokeRoutedRPC(peer.m_uid, "RPC_TeleportPlayer", pos, rot, true)`; `Chat.RPC_TeleportPlayer` calls `Player.m_localPlayer.TeleportTo` with no sender check. Carries ore. | **verified** |
| Centre or top-left screen message | `InvokeRoutedRPC(peer.m_uid, peer.m_characterID, "Message", (int)MessageHud.MessageType.Center /* or TopLeft */, text, 0)` | **verified** (Center) |
| Change a synced object's state | `zdo.Set(...)` on the server copy; the client applies it by revision and the owning component reloads when it notices (`Container.Load` polls once a second while not in use) | decompile only |
| Chat lines to players | possible via the `ChatMessage` routed RPC, untried; the screen message is quieter | untried |

Anything else the client does only on its own `Player` instance (inventory, skills,
known recipes) lives in the character file on the client (`Player.Save` into
`PlayerProfile.SavePlayerData`) and is out of reach.

## 7. Formats worth not rediscovering

**Chest items** (`ZDOVars.s_items` = `"items"`): `int version` (109 in 1.0),
`ushort count`, then per stack `int durability*100`, `byte gridX`, `byte gridY`,
`byte worldLevel`, `byte flags`, optional fields by flag (4 quality `ushort`,
8 stack `ushort`, 0x10 variant `int`, 0x20 crafter `long` + `string`, 0x40 prefab
hash `int`, 0x80 custom data `NumItems` + pairs), then `byte cheated`. Versions 101
to 107 are the old name based layout. A tested reader and writer for both is
`valheim-sort/src/ItemCodec.cs`; reuse it. Item identity for grouping:
`ItemDrop.ItemData.SharedData` off `ObjectDB.GetItemPrefab(hash).GetComponent<ItemDrop>().m_itemData.m_shared`
(`m_name`, `m_itemType`, `m_maxStackSize`, `m_maxQuality`). Container grid size is
`Container.m_width/m_height` on the prefab.

**Tombstone ZDO** (`Player_tombstone`): `s_owner` (player ID, long), `s_ownerName`,
`s_timeOfDeath` (ticks; `ZNet.instance.GetTime()` for the current time). Vanishes
when looted.

**Player ZDO** (`Player`): `s_playerID`, `s_dead`, `s_emote`, `s_emoteID`,
`s_emoteOneshot`, position, and the usual character values. Owned by its client.

**Item ZDO** (`ItemDrop` prefabs, the ones in `ObjectDB.m_items`): one stack under
`ZDOVars.s_itemData` = `byte 109` + one `ItemData.Save` record, the same compact
record the chest blob uses (`ItemCodec.ReadCompact` after reading the version byte;
decompile only, offline parse verified). Flag 0x40 carries the prefab hash; when it
is unset use the ZDO's own `GetPrefab()`, because the client's `Inventory.Load` drops
chest records with hash 0. `ItemDrop.LoadFromZDO` has no legacy fallback in 1.0: an
absent blob means prefab defaults. The old per-field keys (`s_durability`, `s_stack`,
`s_quality`, `s_variant`, `s_crafterID`, `s_crafterName`, `s_dataCount` +
`data_i`/`data__i`, `s_worldLevel`, `s_pickedUp`, `s_cheated`) are migrated by
`ZDOMan.ConvertInventories` at world load. `s_piece` marks an item placed as a piece,
`s_spawnTime` the drop time (1 h auto destroy when no player is within 25 m).

**Crate prefab** (config default `CargoCrate`, unconfirmed name): a root `Container`
with `m_autoDestroyEmpty`, `Floating`, `Rigidbody`, `ZNetView`. Born state when
vanilla spawns one (`Piece.DropResources`, `Container.OnDestroyed`): prefab, flags
and rotation from `ZNetView.Awake`, `s_addedDefaultItems = true` and `s_items` from
`Container.Awake` + `Save`. `valheim-float/src/Crates.cs` logs the real facts at
startup and lists every prefab with `Container` + `Floating` if the name is wrong.

**Chest prefabs** in 1.0 (from localization keys; the auto-detect in
`valheim-sort/src/Chests.cs` is the truth): `piece_chest_wood`, `piece_chest`,
`piece_chest_private`, `piece_chest_blackmetal`, `piece_chest_barrel`,
`piece_chest_treasure`, `piece_chest_grausten`, a wardrobe. Carts have a `Vagon`
and `Container.m_wagon`; ship storage sits on a child object under a root without a
`Container`.

## 8. Patterns that worked

- **Gate everything on `ZNet.instance.IsServer()`.** Also true on a hosting client,
  where `Player` objects do exist; code should tolerate both.
- **Trace generously behind a config flag**, default on for the first deployment.
  One line per event with the ZDOID, prefab name, peer name and the decision taken.
  The "expected log narrative" in each NOTES.md is what the user compares against.
- **Wrap every Harmony hook body in try/catch** that logs and returns. A throw
  inside `RPC_ZDOData` or `RPC_RoutedRPC` would take the server's networking with
  it. Restore `pkg` position in `finally` when sniffing.
- **Debounce client changes** (dictionary of ZDOID to due time, drained in the
  plugin's `Update`) and re-check preconditions right before writing.
- **Refuse rather than repair.** For anything that rewrites player data, count
  before and after, re-parse what you are about to write, and log `REFUSED` with
  both tallies instead of writing on a mismatch.
- **Notice your own echo.** Remember a hash of what you wrote per ZDO so the
  client's later copy of it does not look like a new change.
- **Key per-player memory by profile ID**, not session uid.
- **Provide a second trigger.** Chat is unreliable solo; pair every feature with a
  ping or emote path, and make the automatic path (if any) a config toggle.
- **Prefer reading prefabs over instances** for any component data.

## 9. Things that did not work or were ruled out

- Chat commands as the only trigger (solo players never reach the server).
- Console commands on this host (no stdin).
- `Player.RPC_OnDeath` and any other instance-side patch on the server.
- Sorting the player's own inventory (client file only).
- Hitting a chest as a trigger (destructive), sign text (clunky), stack-all button
  (local when the player owns the chest).
- Taking ownership of a ZDO on the server to answer component RPCs (nothing is
  there to answer them).

## 10. Where the code is

| Reusable piece | File |
|---|---|
| csproj, nuget.config, .gitignore | `valheim-sort/Sort.csproj`, `valheim-sort/nuget.config` |
| Plugin skeleton, config, `ServerSide` helpers (Trace, IsServer, PeerName, Message, PlayerKey) | `valheim-sort/src/SortPlugin.cs` |
| Routed RPC sniff (chat and ping) | `valheim-back/src/Patches.cs`, `valheim-sort/src/Patches.cs` |
| `ZDO.Deserialize` change hook with open/close/change edge detection | `valheim-sort/src/Patches.cs` |
| Emote trigger | `valheim-sort/src/Patches.cs` (`WatchEmote`) |
| Prefab sweep and area query | `valheim-back/src/Tombstones.cs`, `valheim-sort/src/Chests.cs` |
| Items blob codec with count assertions | `valheim-sort/src/ItemCodec.cs` |
| Teleport and screen message RPCs | `valheim-back/src/Teleporter.cs` |
| stdin reader and Terminal mirror (only with a tty) | `valheim-back/src/ConsoleInput.cs` |
| Offline codec test pattern | described in `valheim-sort/NOTES.md` and `valheim-float/NOTES.md` (which real game methods run offline and which do not) |
| Creating a ZDO on the server, releasing it, destroying an item ZDO | `valheim-float/src/Crates.cs` (`Create`, `Converter.Convert`) |
| Reading an item ZDO's record with all three fallbacks | `valheim-float/src/Items.cs` |
| `ReleaseNearbyZDOS` postfix as a periodic per-peer sweep | `valheim-float/src/Patches.cs` |

## 11. Creating and destroying ZDOs from the server (valheim-float, decompile only)

Everything here is from the 1.0 decompile; none of it had run on the live server
when written. Mark it verified in `valheim-float/NOTES.md` once the log shows it.

- **Create:** `ZDOMan.instance.CreateNewZDO(position, prefabHash)` is public,
  registers the ZDO in its sector (`ZDO.Initialize`) and makes the server the owner,
  **but does not set the prefab**; the hash is only used for the portal check. Do
  what `ZNetView.Awake` does: `Persistent`, `Distant`, `Type` from the prefab's
  `ZNetView` (`m_persistent`, `m_distant`, `m_type`), `SetPrefab(hash)`,
  `SetRotation`, then the component data a client would write in `Awake`. Set the
  flags before the first `Set`, because `IncreaseDataRevision` only marks the save
  chunk dirty when `Persistent` is already true. The server's `CreateSyncList` sends
  any ZDO in the peer's sectors the peer has not seen; the client's `RPC_ZDOData`
  creates it and `ZNetScene.CreateObjectsSorted` instantiates it once the zone is
  loaded and `GetPrefab(hash)` resolves.
- **Then release it:** `SetOwner(0L)`. `ReleaseNearbyZDOS` hands unowned
  **Persistent** ZDOs inside a peer's active area to that peer within two seconds.
  A non-persistent ZDO never gets an owner this way, so nobody would run its
  physics or answer its RPCs: force `Persistent` on anything the server creates
  that a client must simulate.
- **Destroy:** `ZDOMan.instance.DestroyZDO(zdo)` only queues when `zdo.IsOwner()`,
  so `zdo.SetOwner(ZDOMan.GetSessionID())` first. `SendDestroyed` broadcasts
  `DestroyZDO` to everybody and handles it locally too (`HandleDestroyedZDO`
  removes the server copy and records the ID in `m_deadZDOs`). If the old owner
  sends one more update before the destroy reaches it, `RPC_ZDOData` recreates the
  ZDO and, seeing a dead ID, destroys it again. Order matters for safety: write the
  replacement, re-read it, and only then destroy the original.
- **Do not write to a moving, client-owned ZDO.** The owner's `ZSyncTransform`
  calls `SetPosition` every frame the body moves, and `InternalSetPosition` bumps
  `DataRevision` on the owner. A server write at N+1 arrives when the client is at
  N+5, is ignored, and the client's next update overwrites the server copy. This is
  the section 4 revision race in its worst form. Section 4's "the server can `Set`
  on a client-owned ZDO" holds for things that sit still (chests). For anything
  with a `Rigidbody` or `Floating`, write only while it has no owner, or create a
  new ZDO instead.
- **`ReleaseNearbyZDOS` as a sweep.** The server runs it every two seconds for its
  own session (reference position, the world origin on a dedicated server) and once
  per peer; the private `m_tempNearObjects` list (`AccessTools.Field`) holds the
  ZDOs in that peer's near sectors and is still populated in a postfix. Skip the
  call where `uid == ZDOMan.GetSessionID()`. Cheap enough to scan against a
  `HashSet<int>` of prefab hashes.
- **Position is applied before `Deserialize`.** `RPC_ZDOData` calls
  `InternalSetPosition(vector)` and then `zdo.Deserialize(pkg)`, so a `Deserialize`
  postfix sees the new position; it does not see the old one.
- **Water level** is a flat 30 (`ZoneSystem.m_waterLevel`, `c_WaterLevel`) except
  beyond 10500 m from the centre (100 m lower). Tar pits are their own
  `WaterVolume`s above sea level.
- **What runs offline for a codec test:** `ZPackage`, `ItemDrop.ItemData.Load` and
  `Inventory`-style parsing do. `ItemDrop.ItemData.Save` does not (it compares
  `m_dropPrefab` through `UnityEngine.Object`'s operator, which needs the native
  runtime); hand-write the bytes from the decompile for that direction.
- **Authoritative rewrite (the exception to "never force the revision").** When a
  one-off repair must land on a ZDO a client owns and keeps bumping (a bobbing
  crate), the only write that sticks is: `SetOwner(ZDOMan.GetSessionID())`, the
  `Set` calls, `DataRevision += 1000`, `SetOwner(0L)`. Every in-flight client
  packet is then stale on both counts (`RPC_ZDOData` ignores data at or below the
  server's revision and only takes an owner from a newer `OwnerRevision`), the
  client applies the server copy, and `ReleaseNearbyZDOS` gives the ZDO back within
  two seconds. Cost: any change the player made inside one round trip is lost, so
  guard on `s_inUse == 0`, run it once per ZDO and stamp the ZDO so a restart does
  not repeat it. `valheim-float/src/FishRelease.cs` is the reference; decompile
  only until its log lines show up.
- **Terrain height on the server:** `ZoneSystem.GetGroundHeight` is a
  `Physics.Raycast` and needs terrain colliders; do not rely on it headless.
  `WorldGenerator.instance.GetHeight(x, z)` is the generated terrain from the seed,
  pure math, always available on the server. It ignores player terraforming
  (hoe, pickaxe), so treat it as "what the land was", good enough for "is there
  water here". Items on land clip through the ground now and then and fall until
  `ItemDrop.TerrainCheck` (owner only, every 10 s) lifts them back; a position
  check alone will mistake them for underwater or underground objects.
