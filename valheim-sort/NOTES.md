# MrPinoys Sort: status and findings

Last updated 2026-09-16. Built against the local client assembly (Valheim 1.0.0, build
25253764) for the same Linux dedicated server valheim-back runs on (l-1.0.12, network
version 40, BepInExPack Valheim 5.4.2350). Client stays vanilla.

## Where things stand

| Piece | Status |
|---|---|
| Plugin builds, Harmony patches target methods that exist in the 1.0 assembly | done, not yet loaded on the live server |
| Item blob codec (parse and rewrite the `items` byte array, formats 101 to 109) | verified offline: byte-for-byte round trip against the real `ZPackage` |
| Chest prefab auto-detection from `ZNetScene.m_prefabs` | unverified; look for the `Sortable chest prefabs` line |
| Close trigger via `ZDO.Deserialize` postfix | unverified |
| Client reload after the server write | unverified; the decompile says it should (see finding 4) |
| Map ping trigger | unverified; same sniff as valheim-back's verified ping |
| Emote trigger (`/point` sorts the chest the player opened last) | unverified; rests on finding 9 |
| Chest still opens after a sort | **must verify in game** (see finding 5) |

## Findings that shaped the design

From decompiling `assembly_valheim.dll` (ilspycmd 8.2) and from what valheim-back
learned about this server. Re-check after any game update.

1. **The items blob is self-describing and does not need `Inventory` to rewrite.**
   `Container.Save` calls `Inventory.Save`, which writes `int 109`, `ushort count`,
   then `ItemDrop.ItemData.Save` per stack: `int durability*100`, `byte gridX`,
   `byte gridY`, `byte worldLevel`, `byte flags`, then only the fields whose flag is
   set (quality, stack, variant, crafter id and name, prefab hash, custom data), then a
   `byte cheated`. Versions 101 to 107 are the older name based layout that
   `Inventory.LoadOld` reads. `src/ItemCodec.cs` mirrors both and always writes 109.
   Going through `Inventory.Load` instead would instantiate a prefab `GameObject` per
   stack on the server and would need a `Container` to hold the width and height.
   The plugin reads those off the prefab's `Container` component instead.

2. **Every `ZDO.Deserialize` on the server is a client change.** It is only called
   from `ZDOMan.RPC_ZDOData`, and a client's `CreateSyncList` only sends the IDs in
   `m_clientChangeQueue`, which `ZDO.IncreaseDataRevision` / `IncreaseOwnerRevision`
   fill. So a Harmony postfix on `ZDO.Deserialize` sees exactly the chests a client
   opened (`InUse` 0 to 1), closed (1 to 0), changed, or took ownership of, and
   nothing else. No polling of 267k ZDOs.

3. **"In use" is a ZDO int.** `Container.SetInUse` (called by `InventoryGui` on
   open and close, owner only) runs `UpdateUseVisual`, which does
   `zdo.Set(ZDOVars.s_inUse, m_inUse ? 1 : 0)`. Items are saved on every change
   (`OnContainerChanged` -> `Save`) while the chest is open, so by the time `InUse`
   drops to 0 the blob is final.

4. **A non-owner write is accepted by the client.** `ZDOMan.RPC_ZDOData` on the
   receiving side only compares revisions: a ZDO with a higher `DataRevision` is
   applied whoever sent it, and `ZDO.Set(int, byte[])` on the server bumps the
   revision (byte arrays compare by reference in `BinarySearchDictionary.SetValue`,
   so a fresh array always counts as a change). Then `Container.Load` on the client
   reloads when `DataRevision != m_lastRevision` and the chest is not in use. One
   caveat: if the client changes the chest again before the server's write reaches
   it, both sides are at revision N+1 and each ignores the other until the client's
   next change (N+2), which wins. The 0.5 s settle delay narrows that window; the
   plugin never bumps the revision by more than the natural +1 on purpose, because
   winning that race would overwrite a real player action.

5. **Ownership must stay with a client.** `Container.RPC_RequestOpen` is answered
   only by the ZDO owner (`if (!m_nview.IsOwner()) "but im not the owner"`), and the
   owner hands the ZDO to the requester with `SetOwner(uid)`. This server has no
   `Container` instances, so a chest owned by the server could never be opened. The
   plugin therefore never takes ownership; it writes the blob and leaves the owner
   as is. It only calls `SetOwner(0L)` when the owner already is the server's own
   session ID, and logs `owner was the server, released` when that happens. Unowned
   ZDOs are handed to a nearby peer by `ZDOMan.ReleaseNearbyZDOS` within a couple of
   seconds.

6. **"Place stacks" is invisible to the server.** `Container.StackAll` sends
   `RPC_RequestStack` to the ZDO owner. Inside the open chest UI the owner is the
   player pressing the button, and `ZRoutedRpc` handles self-targeted RPCs locally.
   The hover version (stack-all key while looking at a closed chest) is the same
   story when the player already owns the chest. What the server does see is the
   resulting blob change with `InUse` 0, which is why the trigger is "contents
   changed while nobody has it open", not just the 1 to 0 edge.

7. **Player bags are not on the server.** `Player.Save(ZPackage)` writes
   `m_inventory.Save(pkg)` into the package `PlayerProfile.SavePlayerData` stores in
   the character file. No ZDO carries it. Out of scope, stated in the README.

8. **Prefab discovery.** The prefab names are not greppable from the client install
   (the bundles are compressed), so the plugin builds its list at runtime: every
   entry of `ZNetScene.m_prefabs` whose root has a `Container` and a `Piece`, no
   `Vagon`, `m_wagon == null` and `m_rootObjectOverride == null`. Ships keep their
   `Container` on a child object under a root without one, so they never match.
   Localization keys present in the client suggest the 1.0 set is roughly
   `piece_chest_wood`, `piece_chest`, `piece_chest_private`, `piece_chest_blackmetal`,
   `piece_chest_barrel`, `piece_chest_treasure`, `piece_chest_grausten` and a
   wardrobe. The log line `Sortable chest prefabs (N): ...` is the truth; the config
   `General.Prefabs` overrides it if the auto list is wrong.

9. **Emotes are the one deliberate solo action that reaches the server cleanly.**
   `Player.StartEmote` does `zdo.Set(s_emoteID, id + 1)` and
   `zdo.Set(s_emote, name)` on the player's own ZDO (`StopEmote` bumps the ID again
   with an empty name); the chat command lowercases the enum name (`point`,
   `thumbsup`, `nononono`). The client owns its player ZDO, so the change is synced
   and lands in the same `ZDO.Deserialize` postfix as the chests. The plugin keeps
   the last `emoteID` per player ZDO and treats a new ID with the configured name as
   one trigger. The target is the chest whose `InUse` 0 to 1 edge the server last
   saw with that peer as owner (the opener becomes owner in `RPC_RequestOpen`, so
   the owner at that moment is the player who opened it); `LastOpenedOnly = false`
   falls back to a radius around the player. Other candidates rejected: sign text (works, clunky), hitting the
   chest (`health` drops, destructive), hover stack-all or take-all (local when the
   player already owns the chest).

## What to look for in the log

Expected narrative for one chest, verbose on:

```
[Info   :MrPinoys Sort] MrPinoys Sort 0.1.0 loaded.
[Info   :MrPinoys Sort] Sortable chest prefabs (8): piece_chest (6x3), piece_chest_wood (3x2), ...
[Info   :MrPinoys Sort] [trace] Chest 123456:78 (piece_chest_wood) opened by Adolp
[Info   :MrPinoys Sort] [trace] Chest 123456:78 (piece_chest_wood) closed; 14 stacks, 312 items; owner Adolp
[Info   :MrPinoys Sort] Sorted 123456:78 (piece_chest_wood, closed): 14 -> 11 stacks, 312 items, format 109 -> 109, wrote 0.3 KB, revision 41 -> 42, owner Adolp kept
```

| Symptom | Meaning |
|---|---|
| no `Sortable chest prefabs` line ever | `ZNetScene.instance` is null on the server, or no chest ZDO was ever deserialized. Check the `opened by` lines first. |
| `Sortable chest prefabs (0)` | the auto-detect filter is wrong for 1.0; set `General.Prefabs` and paste the trace lines `Prefab ... has a Container but is skipped` |
| `opened by` but never `closed` | `InUse` is not written the way finding 3 says, or the close packet is coalesced with something else; the `changed while closed` path should still fire |
| `closed` but no `Sorted` | look for `skipped` with a reason, `REFUSED` (count mismatch, never written), or `already sorted` |
| `Sorted` but the chest reopens unsorted | the client did not reload: compare the `revision a -> b` numbers with what the client sends next; if the next `closed` line shows the old layout the write was ignored (finding 4 race) |
| `Sorted` and the chest will not open | ownership problem; the `owner ...` part of the `Sorted` line says who held it. Should never say `the server`. |
| `ObjectDB has no item for prefab hash` | a modded or removed item; it is left in place and the rest is sorted |
| `could not parse the items blob` | an item format the codec does not know; paste the line, nothing was written |

## Offline codec test

`ItemCodec` was checked without the game: a throwaway `net48` console project that
compiles `src/ItemCodec.cs`, references the same Valheim dlls, and copies
`assembly_valheim.dll`, `assembly_utils.dll`, `Splatform.dll`, `UnityEngine.dll` and
`UnityEngine.CoreModule.dll` next to the exe. It writes a version 109 blob with every
flag set through the real `ZPackage`, parses it, re-serializes it and compares byte
for byte, then does the same for a version 107 blob re-encoded to 109. Valheim has its
own `Console` class, so write `System.Console.WriteLine`. Repeat this after any change
to the codec or a game update that touches `Version.Item`.

See `../VALHEIM-SERVER-SIDE.md` for everything learned across the server-side mods.

## Open items

- First live test. The whole close trigger and the client reload rest on the
  decompile; the ping sniff is the only path with a precedent.
- If `ZNetScene.m_prefabs` on the dedicated server does not include piece prefabs
  (it should, the server spawns them for clients), fall back to `General.Prefabs`.
- `Seen` and `LastWritten` maps grow by one entry per chest touched since start. Fine
  for a private server; bounded cleanup is trivial to add if it ever matters.
- Turn `VerboseLog` off once it works; it logs every chest open and close.

## Files

- `src/SortPlugin.cs`: entry point, config, Harmony bootstrap, the settle-delay queue.
- `src/Chests.cs`: chest prefab table from `ZNetScene`, `InUse`, nearby chest sweep.
- `src/ItemCodec.cs`: the items blob in and out, count assertions, blob hash.
- `src/Sorter.cs`: group, merge, lay out, verify, write.
- `src/Patches.cs`: `ZDO.Deserialize` postfix (the trigger), `RPC_RoutedRPC` prefix (map ping).
- Output: `bin/MrPinoys_Sort.dll`, copied to `../dist/`.
