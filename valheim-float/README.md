# MrPinoys Float (Valheim 1.0, server-side only)

Items dropped into water are not lost. The moment an item sinks below the surface,
the server replaces it with the game's own floating cargo crate holding that item.
Installed on the dedicated server only; every client stays vanilla.

## What it does

- Drop a stone off a dock and it sinks. Do the `/wave` emote: a cargo crate bobs up
  at the surface right above where it went down. Swim to it, open it, take the
  stone; the empty crate destroys itself. Every sunk item within 30 m of you is
  crated by the one wave, and a small top-left message says how many were found.
- With `AutoConvert = true` no emote is needed: items are crated the moment they
  sink, and items already on the seabed are picked up as soon as a player is near.
- Items that float in vanilla (wood, most things with a `Floating` component) are
  left alone. So are live fish: a fish dropped into water swims away, as in vanilla.
- Several stacks dropped together within a second and within a few metres of each
  other share one crate when possible, and like items stack inside it (ten feathers
  from a bird make one stack of ten, not ten slots).

## How it works

Floating is client physics, and a server-only mod cannot bolt a `Floating` component
onto a vanilla client's item prefab. So the item is never made to float. Instead the
plugin uses the crate the game already has: when a ship is wrecked, vanilla spawns a
`Container` with `Floating`, a `Rigidbody` and `m_autoDestroyEmpty`, and moves the
cargo into it. It floats, drifts, opens from the water and vanishes when emptied.
All of that runs on the clients. The server's only job is a swap at the ZDO level:

1. **Detect.** The `/wave` emote is written to the player's own ZDO (`emoteID`
   and `emote`), which the client syncs, so it reaches the server even with one
   player online (chat does not). On a wave the server looks at every item ZDO in
   the zones around the player and queues the ones within `EmoteRadiusMeters` that
   are more than `TriggerDepthMeters` below the water level (30), on a spot where
   the generated terrain is under water, and whose prefab lacks `Floating`. With
   `AutoConvert` on, the same test runs on every item ZDO a client changes (a
   Harmony postfix on `ZDO.Deserialize`; a falling item bumps its data revision on
   every position change, so a sinking item arrives there by itself) and, for items
   already resting on the seabed, on a postfix of `ZDOMan.ReleaseNearbyZDOS`, which
   the server runs per peer every two seconds over exactly the ZDOs near that player.
2. **Read.** The item ZDO carries one item record under `itemData` (a version byte
   and the same compact layout chests use). It is parsed with the codec verified in
   `valheim-sort`; anything that does not parse completely is logged as `REFUSED`
   and left where it is. Nothing is destroyed that was not fully copied.
3. **Find or make a crate.** If a crate of ours is within `MergeRadiusMeters`, has
   room, nobody has it open and nobody owns it yet, the record is appended to it.
   Otherwise a new crate ZDO is created at the surface above the item with the same
   flags a client would set on it (`Persistent`, `Distant`, `Type`, rotation), the
   items blob, a marker that the default loot table has already been rolled, and a
   plugin stamp. Ownership is released; within two seconds the server hands the
   crate to a nearby player, whose client instantiates it and runs its physics.
4. **Destroy the item.** Only after the crate exists with the blob written, the
   server takes ownership of the item ZDO and destroys it. A crash between steps 3
   and 4 duplicates the item; it never loses it.

## Config (`BepInEx/config/mrpinoys.valheim.float.cfg`)

| Key | Default | Meaning |
|---|---|---|
| General.Enabled | true | master switch |
| General.AutoConvert | false | true: items are crated the moment they sink and seabed items are swept up near players, no emote needed. false: only the emote converts |
| Trigger.Emote.Emote | wave | which emote converts the sunk items around the player (chat command without the slash, case insensitive) |
| Trigger.Emote.EmoteRadiusMeters | 30 | radius around the player for the emote (max 64) |
| Trigger.Emote.Notify | true | small top-left message with how many sunk items the emote found |
| General.CratePrefab | CargoCrate | prefab name of the floating loot crate. If the startup log says it was not found, it lists every prefab with a `Container` and `Floating` to pick from |
| General.TriggerDepthMeters | 0.5 | an item counts as sunk this far below the water level, so a splash does not trigger |
| General.SurfaceOffset | 0.2 | the crate is created this far above the water level; the client's buoyancy settles it |
| General.MergeRadiusMeters | 6 | append to an existing crate of ours within this distance when it has room, is closed and unowned; 0 disables merging |
| General.BatchWindowSeconds | 1.0 | wait this long after first seeing a sunk item before converting it, so stacks dropped together share a crate; the item must still be under water when the window closes |
| General.ReleaseFishFromCrates | true | one-time cleanup: fish found in a crate of ours near a player are put back in the water as live fish and the crate is rewritten without them; each crate is done once and stamped |
| General.IgnorePrefabs | (empty) | comma separated prefab names never to crate, on top of the built-in rules (floating items, fish, placed pieces) |
| General.VerboseLog | true | trace every sunk item, crate created, item destroyed, crate opened and crate gone; turn off once it works |

## Install

1. Build: `dotnet build -c Release` (needs a Valheim install to reference; set
   `ValheimManaged` in `Float.csproj` or pass `-p:ValheimManaged=<path>`).
2. Copy `bin/MrPinoys_Float.dll` to the server's `BepInEx/plugins/`.
3. Restart the server. The log prints `MrPinoys Float 0.1.0 loaded.` and, once the
   world is up, `Item prefabs known: N` and `Crate prefab CargoCrate: Container WxH, ...`.

## Test script

Install, restart, and check the startup line for the crate prefab (`Container 4x2,
Floating yes, autoDestroyEmpty yes` or similar; if it says the prefab was not found,
pick a candidate from the list it prints and restart). Stand on a dock, drop a stone
into deep water and watch it sink, then type `/wave`; the top-left message should say
`Crating 1 sunk item(s) nearby` and within about two seconds a cargo crate should
appear at the surface where the stone went down. Swim to it, open it, take the stone;
the crate should vanish. Then drop a piece of wood and wave again: it floats in
vanilla and must be left untouched (`No sunk items within 30 m`). Log lines that
prove each step: `emote wave by Adolp at ...: 1 sunk item(s) within 30 m, 1 queued`
(trigger), `Item ... seen 1.6 m under water (emote wave); converting in 1 s`
(detected), `Item ...
is 1.6 m under water, owner Adolp, read from itemData v109; no crate of ours nearby`
(read), `Crate ... created at ...; owner released` (crate exists), `Item ...
destroyed` (swap done), `Crate ... now owned by Adolp` (a client is simulating it),
`Crate ... opened by Adolp` (openable), `Crate ... gone (emptied or destroyed);
forgotten` (self-destructed). For the wood, `0 sunk item(s) within 30 m` and, if it dipped
below 29.5, `left alone: prefab floats on its own`.

## Cleanup of crates from before the fish fix

The first build crated swimming fish. With `ReleaseFishFromCrates` on, every crate of
ours that still holds fish is fixed the next time a player is near it: the fish are
recreated as live fish just under the surface next to the crate, the crate is
rewritten without them, and it is stamped so this never runs on it again. A crate left
empty by this destroys itself on the client like any emptied crate. Log lines: `Fish
... created at ...; owner released` per fish and `Crate ...: released Fish1 x1, ...
back into the water; N stack(s) left`. This is the one place the plugin writes to a
crate a client owns; it does so by taking the crate for a moment and pushing its
revision far ahead so the client's stale updates are ignored, and only while nobody
has the crate open. Set the option to false once the log shows no more releases.

## Known limits

- **Items only.** `ItemDrop` prefabs (the ones in `ObjectDB.m_items`). Corpses,
  carts, logs and destructibles are not touched. Live fish are `ItemDrop` prefabs
  as well but carry a `Fish` component and are skipped; anything else that should
  be left in the water goes in `IgnorePrefabs`.
- **Tar pits are ignored.** Tar sits above the sea level and has its own
  `WaterVolume`; an item in tar is only converted if it really is below 30, like
  any other item, and no special case is made for it.
- **Merging is best effort.** A crate that a client already owns cannot be safely
  written to (the owning client bumps its revision every frame it bobs, so a server
  write loses the race and the item with it). So the plugin only merges into a crate
  nobody owns, which in practice means a crate created within the last two seconds
  or one nobody is near. Items dropped together land in one crate thanks to the
  batch window; items dropped a while later get their own crate next to it.
- **Duplicates, never losses.** Two windows exist where an item can be doubled: a
  crash after the crate was written but before the item was destroyed, and a player
  picking the item up from the water in the same second the server converts it. In
  both the item ends up both in the crate and wherever it was. Nothing in the swap
  path destroys before the copy is written and re-read.
- **No cleanup of crates nobody collects.** A crate that is never opened floats
  forever (it is persistent, like the item it replaced would have been on the
  seabed). Open item; the fix is a creation time on the crate ZDO and a sweep in the
  same `ReleaseNearbyZDOS` postfix.
- **Land is decided by the generated terrain.** An item counts as sunk only where
  the world generator's terrain height at that spot is below the water level and
  the item is not more than a metre under that terrain. This keeps items that clip
  through the ground on land (they fall until vanilla lifts them back) out of
  crates. A hole a player dug below sea level near the shore reads as land, so an
  item dropped into such a pool is left alone.
- **World edge.** Beyond 10500 m from the centre the water level is 100 m lower;
  items there are skipped.
- **Untested on the live server as of this version.** See NOTES.md for what is
  verified and what rests on the decompile.
