# Prompt: server-side only floating crates for sunk items, Valheim 1.0

Copy everything below the line into a fresh Claude Code session started in
`A:\MrPinoys Mods`.

---

Build a **server-side only** Valheim 1.0 BepInEx plugin so that items dropped into
water are not lost: the moment an item sinks below the surface, the server replaces it
with a vanilla floating cargo crate holding that item. It is installed only in the
dedicated server's `BepInEx/plugins/`; every client stays vanilla. Work in a new
folder `A:\MrPinoys Mods\valheim-float\`, and copy the finished dll to
`A:\MrPinoys Mods\dist\`.

Read these first, in this order:

1. `A:\MrPinoys Mods\CLAUDE.md` (house rule: no em dashes or double hyphens anywhere
   you write, including code comments and log strings).
2. `A:\MrPinoys Mods\VALHEIM-SERVER-SIDE.md`: everything learned from the two
   previous server-side mods. Do not rediscover anything in it: no scene instances
   on the server, ZDO writes accepted by revision, `ZDO.Deserialize` as the change
   hook, never leave the server owning an interactable ZDO, the log is the only
   feedback loop.
3. `A:\MrPinoys Mods\valheim-sort\`: copy `Sort.csproj`, `nuget.config`,
   `.gitignore` and the `SortPlugin.cs` skeleton (`ServerSide` helpers, config,
   verbose trace). **Reuse `src/ItemCodec.cs` as is** (copy the file, keep the
   namespace change minimal); it is verified byte for byte against the real
   `ZPackage`. Reuse the `ZDO.Deserialize` postfix pattern from
   `valheim-sort/src/Patches.cs`.

## The design

Floating is client physics, and a server-only mod cannot add a `Floating` component
to a vanilla client's item prefab. So do not try to make the item float. Instead use
the crate the game already has: when a ship or a piece with `m_destroyedLootPrefab`
is destroyed, vanilla spawns that prefab (community name `CargoCrate`: a `Container`
with `m_autoDestroyEmpty`, a `Floating` component and a `Rigidbody`) and moves the
loot into it. It floats, drifts, can be opened from the water, and destroys itself
when emptied. All of that runs on the clients.

The server's job is only to **swap a sunk item ZDO for a crate ZDO carrying the same
item**:

1. **Detect.** In a `ZDO.Deserialize` postfix (server only), for ZDOs whose prefab
   is an item (`ObjectDB.instance.m_items` names hashed into a `HashSet<int>`, built
   lazily once): if `zdo.GetPosition().y < ZoneSystem.instance.m_waterLevel` (30;
   also `ZoneSystem.c_WaterLevel`) minus a small config depth (default 0.5 m so a
   splash does not trigger), and the prefab has no `Floating` component
   (`ZNetScene.instance.GetPrefab(hash).GetComponent<Floating>()`), queue it.
   Falling items send position updates because `ZDO.InternalSetPosition` bumps the
   data revision on the owner, so the sinking item arrives here by itself.
   Items already resting on the seabed do not send anything: also hook
   `ZDOMan.ReleaseNearbyZDOS(Vector3, long)` with a postfix and scan the private
   `m_tempNearObjects` list (via `AccessTools.Field`) for the same condition; the
   server runs it per peer every couple of seconds with exactly the ZDOs near that
   player, so it is a free periodic sweep.
2. **Read the item.** `ItemDrop.SaveToZDO` stores the stack on the item ZDO under
   `ZDOVars.s_itemData` as `byte 109` followed by `ItemData.Save`, which is the
   same per-record layout `ItemCodec.ReadCompact` parses (read the leading byte
   yourself, then one record). If the record has no prefab hash (flag 0x40 unset),
   use the item ZDO's own `GetPrefab()`. Decompile `ItemDrop.LoadFromZDO` and
   handle whatever legacy fallback it has (older per-field vars such as
   `s_durability`, `s_stack`, `s_quality`, `s_variant`, `s_crafterID`,
   `s_crafterName`, `s_worldLevel`, `s_pickedUp`, `s_cheated`) so pre-1.0 items on
   the seabed are not skipped. Log and skip anything you cannot read; never destroy
   an item you could not fully copy.
3. **Find or make a crate.** Look for an existing crate ZDO of ours within
   `MergeRadiusMeters` (default 6) that is not in use (`s_inUse == 0`) and has a
   free slot (parse its `s_items`, compare against the crate `Container`'s
   `m_width * m_height` read off the prefab); append the record there. Otherwise
   `ZDOMan.instance.CreateNewZDO(surfacePos, cratePrefabHash)` with
   `surfacePos = (x, waterLevel + SurfaceOffset, z)`, then copy the flags the
   client would have set in `ZNetView.Awake`: `Persistent`, `Distant`, `Type` from
   the prefab's `ZNetView` (`m_persistent`, `m_distant`, `m_type`), `SetRotation`,
   `Set(ZDOVars.s_addedDefaultItems, true)` so the container does not roll its
   default drop table, and `Set(ZDOVars.s_items, blob)` built with
   `ItemCodec.Serialize` (grid position 0,0 for the first record, then row by row).
   Finally `SetOwner(0L)`: a nearby peer is handed the crate by
   `ReleaseNearbyZDOS`, instantiates it, and runs its physics and `RPC_RequestOpen`.
   **Never leave the server as owner** (see VALHEIM-SERVER-SIDE.md section 4).
4. **Remove the item.** Only after the crate ZDO exists with the blob written:
   `itemZdo.SetOwner(ZDOMan.GetSessionID())` then
   `ZDOMan.instance.DestroyZDO(itemZdo)` (it requires `IsOwner()` and queues the
   destroy broadcast). Crash between 3 and 4 means a duplicate, never a loss; say
   so in the README.
5. **Recognise our crates.** Stamp every crate we create with a plugin-specific
   ZDO key (`zdo.Set("mrpinoys_float".GetStableHashCode(), 1)`) so the merge step
   and the trace only touch ours, and so a crate the game spawned from a wrecked
   ship is left alone.

Verify at startup and log: the crate prefab exists (`ZNetScene.GetPrefab(name)`),
its `Container` size, whether it has `Floating`, `m_autoDestroyEmpty`, and its
`ZNetView` flags. The prefab name is a config value (default `CargoCrate`) because
it could not be confirmed from the client install; if the lookup fails, log the
names of every prefab that has both `Container` and `Floating` so the user can pick.

## Facts already established (do not re-verify, do re-check signatures)

- Server: Linux dedicated, Valheim `l-1.0.12`, network version 40, BepInExPack
  Valheim 5.4.2350. No `Player`, `ItemDrop`, `Container` or other scene instances
  on the server. `ObjectDB.instance` and `ZNetScene.instance` exist. Console
  unusable. The only feedback is `BepInEx/LogOutput.log` pasted back.
- `ZDOMan.RPC_ZDOData` on a client applies any incoming ZDO with a higher
  `DataRevision` or `OwnerRevision`, whoever sent it; a new ZDO is created and
  instantiated by `ZNetScene` when it is inside the client's area.
- `ZDOMan.CreateNewZDO(Vector3, int prefabHash)` is public and sets the server as
  owner; `DestroyZDO` requires ownership and broadcasts through `m_destroySendList`.
- `ZDO.Deserialize` is only called from `RPC_ZDOData`; clients only send ZDOs they
  changed; `InternalSetPosition` bumps the revision on the owner.
- `ItemCodec` (chest blob, versions 101 to 109) is verified offline. The single
  item record on an item ZDO uses the same compact layout behind a one-byte version.
- Map ping and emotes reach the server from a solo player; chat does not. No
  trigger is needed for this mod. Optional: the `/point` emote logs a diagnostic
  (crates created, items converted, nearest crate) if it costs nothing.

## Decompile before you guess

`~/.dotnet/tools/ilspycmd -t <Class> "/a/Steam/steamapps/common/Valheim/valheim_Data/Managed/assembly_valheim.dll"`.
Classes: `ItemDrop` (`SaveToZDO`, `LoadFromZDO`, `Awake`, `m_autoDestroy`),
`Container` (`Awake`, `AddDefaultItems`, `CheckForChanges`, `m_autoDestroyEmpty`),
`Floating`, `ZNetView` (`Awake`: which ZDO fields it initialises from the prefab),
`ZNetScene` (`CreateObjects`, `CreateObject`), `ZDOMan` (`CreateNewZDO`,
`DestroyZDO`, `ReleaseNearbyZDOS`, `m_tempNearObjects`), `ZDO` (`Persistent`,
`Distant`, `Type`, `SetRotation`), `ZoneSystem` (`m_waterLevel`), `Piece`
(`DropResources`, the vanilla crate spawn site, to see exactly what state a crate is
born with), `WaterVolume.GetWaterLevel` (whether any water differs from 30 in 1.0).

## Scope decisions (make these, do not ask)

1. Items only (`ItemDrop` prefabs). Not corpses, carts, logs or destructibles.
2. Items whose prefab already has `Floating` are left alone.
3. Tar pits are ignored (an item in tar is treated like any item below 30 only if
   it really is below 30; do not special-case tar). State it in the README.
4. One crate per drop spot: merge into an existing crate of ours within
   `MergeRadiusMeters` when it has room and nobody has it open.
5. No cleanup of never-collected crates in this version. Note it as an open item
   with the obvious fix (a max age stored on the crate ZDO, swept in the
   `ReleaseNearbyZDOS` postfix).
6. Config: `Enabled` (true), `CratePrefab` (`CargoCrate`), `TriggerDepthMeters`
   (0.5), `SurfaceOffset` (0.2), `MergeRadiusMeters` (6), `VerboseLog` (true).
7. Names: guid `mrpinoys.valheim.float`, assembly `MrPinoys_Float`, plugin name
   `MrPinoys Float`, version 0.1.0, config `mrpinoys.valheim.float.cfg`.

## Rejected alternative, for the record

Holding ownership of the sunk item on the server (no instance anywhere, so no
physics) and pinning it at the surface. It needs a guard against
`ZDOMan.ReleaseNearbyZDOS` handing the ZDO back to a client every two seconds, and
the plugin would have to answer `RPC_RequestOwn` (the pickup handshake
`ItemDrop.RequestOwn` sends to the owner) itself; a mistake leaves unpickable items
in the world. The crate needs none of that. Mention it in NOTES.md so it is not
re-proposed.

## Deliverables

- `valheim-float/` with csproj, nuget.config, `src/`, `.gitignore`, `README.md`
  (what it does, how it works, config table, install, known limits) and `NOTES.md`
  in the style of `valheim-sort/NOTES.md`: verified vs assumed, findings with the
  class and method behind each, expected log narrative, symptom table, open items.
- `dist/MrPinoys_Float.dll`.
- Append what you learn to `A:\MrPinoys Mods\VALHEIM-SERVER-SIDE.md` (server-side
  ZDO creation and destruction, the item ZDO layout, the crate prefab facts),
  marking verified vs decompile-only.
- A one-paragraph test script: install, restart, check the startup prefab line,
  drop a stone off a dock, watch a crate appear at the surface where it sank, swim
  to it and open it, take the stone (crate should vanish), drop a piece of wood
  (floats in vanilla, must be untouched); then which log lines prove each step.

## Expected log narrative on success

```
[Info :MrPinoys Float] MrPinoys Float 0.1.0 loaded.
[Info :MrPinoys Float] Crate prefab CargoCrate: Container 4x2, Floating yes, autoDestroyEmpty yes, persistent yes, distant no
[Info :MrPinoys Float] [trace] Item 123456:78 (Stone x3) at 812,28,-140 is 1.6 m under water, owner Adolp; no crate within 6 m
[Info :MrPinoys Float] [trace] Crate 900001:5 created at 812,30.2,-140 with Stone x3; owner released
[Info :MrPinoys Float] [trace] Item 123456:78 destroyed
[Info :MrPinoys Float] [trace] Crate 900001:5 opened by Adolp
[Info :MrPinoys Float] [trace] Crate 900001:5 gone (emptied or destroyed); forgotten
```

If the crate line appears but nothing shows up in game, the client did not
instantiate it: check the `Persistent`/`Distant`/`Type` flags and that the position
sector is set (`CreateNewZDO` does it from the position). If it appears but cannot
be opened or does not float, ownership stayed on the server: the `owner released`
line must be there and `ReleaseNearbyZDOS` must have a peer in range. If the item
stays on the seabed and the crate also appears, the destroy did not go out: the
server must own the item ZDO when `DestroyZDO` is called.
