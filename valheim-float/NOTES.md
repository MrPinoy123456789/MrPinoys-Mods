# MrPinoys Float: status and findings

Last updated 2026-09-19. Built against the local client assembly (Valheim 1.0.0, build
25253764) for the same Linux dedicated server the other mods run on (l-1.0.12, network
version 40, BepInExPack Valheim 5.4.2350). Client stays vanilla.

## Where things stand

| Piece | Status |
|---|---|
| Plugin builds, Harmony targets exist in the 1.0 assembly (`ZDO.Deserialize(ZPackage)`, `ZDOMan.ReleaseNearbyZDOS(Vector3, long)`) | done; loaded on the live server, first report: crates were made (for fish, see finding 11) |
| Item record read (`itemData` = byte 109 + one `ItemData.Save` record) | verified offline: a hand-written record in the decompiled layout parses with nothing left over |
| Crate blob write (`items`, version 109) read by the game's own `ItemDrop.ItemData.Load` | verified offline: prefab hash, stack, quality, grid, durability, crafter, custom data and cheated all round trip, nothing left over |
| Crate prefab name `CargoCrate`, its grid size, `Floating`, `m_autoDestroyEmpty` | **assumed**; the startup line is the truth, and the candidate list is printed if the name is wrong |
| Sinking items arrive in `ZDO.Deserialize` | decompile only (finding 1) |
| Seabed sweep from `ReleaseNearbyZDOS` | decompile only (finding 2) |
| A server-created ZDO is instantiated by the client | decompile only (finding 3) |
| The released crate gets an owner and floats, opens, self-destructs | decompile only (findings 4 and 5) |
| Item destroy reaches the client | decompile only (finding 7) |

## Findings that shaped the design

From decompiling `assembly_valheim.dll` (ilspycmd 8.2). Re-check after any game update.

1. **A sinking item reports its own position.** `ZDO.InternalSetPosition` calls
   `IncreaseDataRevision` when the caller owns the ZDO, and the client's
   `ZSyncTransform` sets the position every frame the `Rigidbody` moves. Changed ZDOs
   go out through `m_clientChangeQueue` and land in `ZDOMan.RPC_ZDOData` on the
   server, which applies the position (`InternalSetPosition`) *before* calling
   `ZDO.Deserialize`. So a postfix on `Deserialize` sees the item at its new depth.

2. **`ReleaseNearbyZDOS` is a free sweep.** `ZDOMan.ReleaseZDOS` runs it every two
   seconds for the server's own session (reference position, world origin on a
   dedicated server) and once per peer with the peer's reference position. It fills
   the private `m_tempNearObjects` with `FindSectorObjects(zone, near distance)` and
   only touches `Persistent` ZDOs. The list is still populated when a postfix runs.
   The plugin skips the server's own call (`uid == ZDOMan.GetSessionID()`).

3. **What a client needs to instantiate a server-made ZDO.** `ZDOMan.CreateSyncList`
   on the server sends any ZDO in the peer's sectors that `peer.ShouldSend` has not
   seen at that revision; a new one always qualifies. On the client `RPC_ZDOData`
   creates it, and `ZNetScene.CreateObjectsSorted` instantiates it when
   `IsZoneReadyForType(sector, Type)` holds and `GetPrefab(zdo.GetPrefab())` resolves.
   `ZDOMan.CreateNewZDO(Vector3, int)` registers the ZDO in its sector via
   `ZDO.Initialize` and sets the server as owner, **but does not set the prefab**:
   the hash it takes is only used for the portal check. `ZNetView.Awake` calls
   `SetPrefab` itself, and also sets `Persistent`, `Type`, `Distant` from its own
   fields and `SetRotation`. The plugin does all five.

4. **Ownership goes to whoever is near.** `ReleaseNearbyZDOS` gives an unowned
   `Persistent` ZDO inside a peer's active area to that peer (`SetOwner(uid)`). Only
   `Persistent` ones: a non-persistent crate would never get an owner, never float
   and never open. The plugin forces `Persistent` on its crates regardless of the
   prefab flag and says so in the startup line. `Floating.CustomFixedUpdate`,
   `Container.RPC_RequestOpen` and `Container.CheckForChanges`' auto destroy all
   gate on `m_nview.IsOwner()`, so the owning client does all of it. The trace line
   `Crate ... now owned by X` is the proof; a warning fires if a crate is still
   unowned 15 s after creation with players online.

5. **What a crate is born with.** `Piece.DropResources` (and `Container.OnDestroyed`
   via `DropAllItems(m_destroyedLootPrefab)`) instantiate the loot prefab on the
   client: `ZNetView.Awake` creates the ZDO with prefab, flags and rotation;
   `Container.Awake` runs `AddDefaultItems` unless `s_addedDefaultItems` is set and
   then sets it; `AddItem` then `Save` writes `s_items`. The plugin writes the same
   three things (`s_addedDefaultItems = true`, `s_items`, rotation) plus its stamp.
   `Container.CheckForChanges` runs every second, reloads when the revision moved
   and, when `m_autoDestroyEmpty` and the owner sees zero items while not in use,
   calls `m_nview.Destroy()`. `Inventory.Load` drops any record with prefab hash 0,
   so the plugin always writes the hash (from the record, else the item ZDO's own
   prefab).

6. **A client-owned crate cannot be merged into.** Same physics as finding 1: the
   owning client bumps `DataRevision` every frame the crate bobs. A server write at
   N+1 lands after the client is already at N+5 and is ignored; the client's next
   update overwrites the server copy; the appended item, whose ZDO was already
   destroyed, is gone. Taking ownership first does not help: the client keeps its
   own `s_items` at the same revision and reasserts it once it owns the crate again.
   The plugin therefore merges only into a crate that has **no owner** (in practice
   one created less than two seconds ago, or one nobody is near), and instead makes
   items dropped together share a crate through the batch window. Do not "fix" this
   by forcing a bigger revision jump on the server: it would also overwrite an
   `s_items` change by a player who opened the crate in the same window.

7. **Destroy needs ownership and is a broadcast.** `ZDOMan.DestroyZDO` only queues
   the ID when `zdo.IsOwner()`. `SendDestroyed` sends `DestroyZDO` to everybody via
   `ZRoutedRpc`, which also handles it locally, so `HandleDestroyedZDO` removes the
   server copy and records the ID in `m_deadZDOs`. If the dropping client, still
   believing it owns the item, sends another update before the destroy reaches it,
   `RPC_ZDOData` recreates the ZDO and, because the ID is dead, destroys it again.
   No guard needed on our side.

8. **Item ZDO layout in 1.0.** `ItemDrop.SaveToZDO` writes `s_itemData` = `byte 109`
   + `ItemData.Save`, whose per-record layout is exactly the chest record
   (`ItemCodec.ReadCompact`): `int durability*100`, `byte gridX`, `byte gridY`,
   `byte worldLevel`, `byte flags`, optional fields by flag (4 quality `ushort`, 8
   stack `ushort`, 0x10 variant `int`, 0x20 crafter `long` + `string`, 0x40 prefab
   hash `int`, 0x80 custom data), `byte cheated`. `LoadFromZDO` has **no** legacy
   fallback in 1.0: an absent blob means the client keeps the prefab's `ItemData`
   (stack 1, prefab quality and durability). The pre-ChunkedSave per-field layout
   (`s_durability`, `s_stack`, `s_quality`, `s_variant`, `s_crafterID`,
   `s_crafterName`, `s_dataCount` + `data_i`/`data__i`, `s_worldLevel`,
   `s_pickedUp`, `s_cheated`) is migrated by `ZDOMan.ConvertInventories` at world
   load and the old keys are removed. The plugin still reads it if met (cheap), and
   otherwise copies the prefab defaults, logging which of the three sources it used.

9. **Water level.** `ZoneSystem.m_waterLevel` is 30 (`c_WaterLevel`). In 1.0
   `WaterVolume.GetWaterLevel` adds waves and `m_surfaceOffset` for rendering, and
   drops by 100 m beyond 10500 m from the centre. Tar pits are separate
   `WaterVolume`s with `m_forceDepth` at their own height, above sea level. So a flat
   30 minus the trigger depth is correct everywhere the plugin acts, and it skips
   the world edge.

10. **`ItemDrop` on the client will not fight the swap.** `ItemDrop.Awake` registers
    `RPC_RequestOwn` and `SlowUpdate` (terrain check, 1 h auto destroy) but nothing
    re-creates a destroyed item. Pickup goes `RequestOwn` -> owner's
    `RPC_RequestOwn` -> `SetOwner(requester)`; once the server has taken ownership
    the requester gets "neither I nor the requesting player are the owners" until the
    destroy arrives. That is the duplicate window mentioned in the README.

11. **Fish are items.** Live fish (`Fish1`, `Fish2`, ...) are `ItemDrop` prefabs in
    `ObjectDB.m_items` with a `Fish` component on the same object, no `Floating`,
    and they swim below the surface, so the sunk test matched them and the first
    live run crated swimming fish (**verified**, the one live finding so far).
    `Fish.Awake` hooks `ItemDrop.m_onDrop`: dropping a caught fish into water is
    how vanilla releases it. `Items.PrefabIsFish` skips any prefab with a `Fish`
    component (cached per hash); `General.IgnorePrefabs` covers anything else.

12. **Stack inside the crate.** Items that die over water (a bird's feathers, a
    neck's tail) drop as one single-item ZDO each, and the first live run put one
    feather per slot until the crate was "full" (**verified**). `Crates.Place` now
    tops up an existing record of the same item first (same prefab, quality, world
    level, variant, cheated flag and custom data, up to `m_maxStackSize` off the
    prefab's `SharedData`, which is what `Inventory.AddItem` does through
    `IsSameType`) and only takes a free cell for the remainder; `CanFit` counts stack
    room as well as free cells. The count assertion checks old contents plus this
    item, so the stacking cannot lose or double anything silently.

13. **Authoritative rewrite of an owned crate (the fish cleanup only).** Finding 6
    says a plain server write to a bobbing, client-owned crate loses. For the one-off
    fish cleanup (`src/FishRelease.cs`) the plugin does the thing section 4 of the
    playbook warns against, deliberately and with guards: `SetOwner(session)`, write
    `s_items` and the `mrpinoys_float_nofish` stamp, `DataRevision += 1000`,
    `SetOwner(0L)`. The jump makes every in-flight client packet stale
    (`RPC_ZDOData` drops data at or below the server's revision, and only applies an
    owner from a packet with a newer `OwnerRevision`, which the client's are not),
    the client applies ours, `Container.Load` reloads, and `ReleaseNearbyZDOS` hands
    the crate back within two seconds. What can be lost: a change the player made to
    the crate's contents inside one round trip of the rewrite. The guard is
    `s_inUse == 0` on the server copy, and the pass runs once per crate. Fish go
    back as item ZDOs made by `Crates.CreateItem` (prefab flags, rotation,
    `itemData` = byte 109 + record, `s_spawnTime`), created before the crate is
    rewritten so a crash duplicates fish instead of losing them. `Fish.Start` takes
    its spawn point from the position, so nothing else is needed for it to swim.

14. **Depth alone is not water.** The second live run crated items lying on land
    (**verified**). Valheim drops clip through terrain on slopes and rocks and fall
    until `ItemDrop.TerrainCheck` (owner only, every 10 s in `SlowUpdate`, threshold
    0.5 m under `ZoneSystem.GetGroundHeight`) puts them back; one second under 30 was
    enough for the batch window. `ZoneSystem.GetGroundHeight` is a `Physics.Raycast`
    against terrain colliders the server may not have, so the plugin asks
    `WorldGenerator.instance.GetHeight(x, z)` instead: the generated terrain, pure
    math off the seed, present on the server because it generates the world. Two
    guards in `Items.IsSunkItem`: terrain at or above the water level means land
    (skip), and an item more than 1 m below the terrain has fallen through (skip; if
    it really is in the sea, vanilla lifts it back onto the seabed and the sweep
    catches it then). Player terraforming is invisible to this (a dug pool below sea
    level reads as land, raised ground over water reads as water but the item is
    above 30 anyway); both err on the side of leaving the item alone.

15. **Emote trigger instead of automatic conversion.** After the land and fish
    surprises the automatic path was made opt-in (`General.AutoConvert`, default
    false) and the deliberate trigger is the `/wave` emote, read the same way
    valheim-sort reads `/point`: `Player.StartEmote` writes `s_emoteID` and
    `s_emote` on the player's own ZDO, which lands in the `ZDO.Deserialize`
    postfix. `Sweep.ConvertAround` runs `FindSectorObjects` on the 3x3 zones around
    the player (exact position off the player ZDO), applies the same sunk test to
    every item within `EmoteRadiusMeters` and queues the hits through the normal
    batch window, so several items wave-converted together share crates. The player
    gets a top-left count through the `Message` routed RPC (verified in
    valheim-back). The `Deserialize` and `ReleaseNearbyZDOS` item paths are gated on
    `AutoConvert`; the fish cleanup in the sweep is not.

## What to look for in the log

Expected narrative for one stone and a wave, verbose on:

```
[Info   :MrPinoys Float] MrPinoys Float 0.1.0 loaded.
[Info   :MrPinoys Float] Item prefabs known: 1234 (from ObjectDB).
[Info   :MrPinoys Float] Crate prefab CargoCrate: Container 4x2, Floating yes, autoDestroyEmpty yes, persistent yes, distant no, type Default, rigidbody yes
[Info   :MrPinoys Float] [trace] Item 123456:78 (Stone) at 812,28.4,-140 seen 1.6 m under water (emote wave); converting in 1 s
[Info   :MrPinoys Float] [trace] emote wave by Adolp at 810,31,-138: 1 sunk item(s) within 30 m, 1 queued, 0 left alone; converting in 1 s
[Info   :MrPinoys Float] [trace] Item 123456:78 (Stone x3) at 812,27.9,-140 is 2.1 m under water, owner Adolp, read from itemData v109; no crate of ours nearby
[Info   :MrPinoys Float] [trace] Crate 900001:5 created at 812,30.2,-140 with Stone x3; owner released (persistent True, distant False, type Default, revision 5)
[Info   :MrPinoys Float] [trace] Item 123456:78 destroyed
[Info   :MrPinoys Float] [trace] Crate 900001:5 now owned by Adolp (1.8 s after creation); the client runs its physics from here
[Info   :MrPinoys Float] [trace] Crate 900001:5 opened by Adolp
[Info   :MrPinoys Float] [trace] Crate 900001:5 closed by Adolp; 0 stack(s) left
[Info   :MrPinoys Float] [trace] Crate 900001:5 gone (emptied or destroyed); forgotten
```

A second stack dropped in the same second: `merging into crate 900001:5 at ..., 0.4
m away, 1/8 slots used` then `Crate 900001:5 now holds 2 stack(s) ...`. A piece of
wood: nothing, or `is under water but left alone: prefab floats on its own`. A fish:
`is under water but left alone: it is a fish`, once per fish. An item that clipped
through the ground on land: `left alone: on land, terrain at 41.3 (fell through the
ground; vanilla lifts it back)`.
An old crate with fish, once a player is near: `Fish 900010:3 (Fish1 x1) created
at ...; owner released` per fish, then `Crate 900001:5 at ...: released Fish1 x1
back into the water; 1 stack(s) left; was owned by Adolp, revision now 1043, owner
released`. `/point` prints `Diagnostic (/point by Adolp at ...): 3 crate(s) created, 4 item(s)
converted (1 merged), 0 pending, 0 refused; 1 crate(s) of ours in the surrounding
zones; nearest ...`.

| Symptom | Meaning |
|---|---|
| no `Crate prefab` line, `Crate prefab 'CargoCrate' not found` | wrong prefab name; set `General.CratePrefab` to one of the listed candidates |
| `Item prefabs known` never appears | `ObjectDB.instance.m_items` is empty on the server; nothing else can work, paste the log |
| no `emote wave by` line after a wave | the emote did not reach the server (first emote after a restart is swallowed on purpose: the counter has to be seen once); wave again |
| `emote wave ...: 0 sunk item(s)` but the item is visibly under water | it is inside the radius but failed the sunk test: the `left alone:` lines under it say why (land by the generator, fish, floats, too shallow) |
| no `seen ... under water` line after a drop with AutoConvert on | the item's updates did not reach `ZDO.Deserialize`, or it never got 0.5 m under (shallow water); try deeper, check the sweep line two seconds later |
| `seen` but `is no longer a sunk item` | it bounced back above the trigger depth in the batch window; harmless, it is re-queued next update |
| `REFUSED ... itemData ...` | a record layout the codec does not know; paste the line, nothing was written, the item stays on the seabed |
| `created` but nothing shows in game | the client did not instantiate it: compare the flags in the `created` line with the prefab line; `Persistent` must be True and the sector comes from the position |
| `created`, visible, but no `now owned by` within a few seconds, or the crate hangs in the air and cannot be opened | ownership stayed unassigned: is the player inside the active area? the 15 s warning prints `Persistent` and the sector |
| `created` and `destroyed` but the item is still on the seabed | the destroy did not go out: `SetOwner(session)` before `DestroyZDO` is the requirement; check for `Item watch failed` or `Converting item ... failed` exceptions |
| crate appears but is empty or has the default loot | `s_items` or `s_addedDefaultItems` did not take: compare the `revision` number in the `created` line with what the crate reports on its first `opened by` line |
| `merging into crate` then the item is missing from that crate | finding 6 was wrong about unowned crates too; set `MergeRadiusMeters` to 0 and paste the lines |
| `still has no owner 15 s after creation` | see finding 4 |

## Rejected alternative, for the record

Holding ownership of the sunk item on the server (no instance anywhere, so no
physics) and pinning it at the surface. It needs a guard against
`ZDOMan.ReleaseNearbyZDOS` handing the ZDO back to a client every two seconds, and
the plugin would have to answer `RPC_RequestOwn` (the pickup handshake
`ItemDrop.RequestOwn` sends to the owner) itself; a mistake leaves unpickable items
in the world. The crate needs none of that. Do not re-propose it.

Also rejected: forcing a large `DataRevision` jump to win the merge race against an
owned crate (finding 6). It would overwrite a real change by a player who has the
crate open.

## Open items

- First live test. Everything past the codec rests on the decompile.
- Crate prefab name. `CargoCrate` is the community name; the startup line confirms
  or the candidate list corrects it.
- No cleanup of crates nobody collects. Obvious fix: store the creation time on the
  crate ZDO (`ZNet.instance.GetTime().Ticks` under our own key) and sweep crates older
  than a configured age in the `ReleaseNearbyZDOS` postfix, destroying only unowned,
  not-in-use ones (take ownership, `DestroyZDO`, same as the item).
- The dedicated server also runs `ReleaseNearbyZDOS` for its own session at the
  world origin and, if `ZoneSystem` has the origin zones loaded, `ZNetScene` may
  instantiate objects there. A crate created near 0,0 might then be owned and
  simulated by the server itself. Harmless if so (the server would then have a real
  `Container` to answer with), but watch the `now owned by the server` trace.
- `Tracked`, `Noted` and `Refused` sets grow by one entry per crate or item seen
  since start. Fine for a private server.
- Turn `VerboseLog` off once it works; every sunk item and crate event is a line.

## Offline codec test

Same pattern as `valheim-sort/NOTES.md`: a throwaway `net48` console project that
compiles `src/ItemCodec.cs`, references the Valheim dlls and copies
`assembly_valheim.dll`, `assembly_utils.dll`, `Splatform.dll`, `UnityEngine.dll` and
`UnityEngine.CoreModule.dll` next to the exe. `ItemDrop.ItemData.Save` cannot run
offline (it compares `m_dropPrefab` with `UnityEngine.Object`'s operator, which
needs the native runtime), so the item record was hand-written in the decompiled
layout and parsed with the plugin's path; `ItemDrop.ItemData.Load` does run offline
and was used to read the crate blob the plugin writes. Both consumed every byte and
matched field for field. Repeat after any change to the codec or a game update that
touches `Version.Item`.

## Files

- `src/FloatPlugin.cs`: entry point, config, Harmony bootstrap, the batch window queue, `ServerSide` helpers.
- `src/Items.cs`: item prefab set, the sunk test, reading the item record (three sources).
- `src/Crates.cs`: crate prefab check, merge target search, crate creation, tracking and housekeeping; `Converter` does the swap.
- `src/ItemCodec.cs`: copied from `valheim-sort` (namespace only, `ReadCompact` made internal).
- `src/FishRelease.cs`: one-time pass that takes fish out of old crates and puts them back in the water.
- `src/Patches.cs`: `ZDO.Deserialize` postfix (falling items, crate open/close, `/point` diagnostic), `ZDOMan.ReleaseNearbyZDOS` postfix (seabed sweep).
- Output: `bin/MrPinoys_Float.dll`, copied to `../dist/`.
