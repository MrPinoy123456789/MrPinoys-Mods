# MrPinoys Sort (Valheim 1.0, server-side only)

Sorts player-built chests. Installed on the dedicated server only; every client stays
vanilla.

## How it works

- A chest's contents live on its ZDO as a byte array (`items`). The client that has
  the chest open writes that array on every change; the server keeps a copy.
- Clients only ever send the ZDOs they changed. The plugin hooks the one place the
  server receives them (`ZDO.Deserialize`, called from `ZDOMan.RPC_ZDOData`) and
  watches chest ZDOs go from "in use" to "not in use", or change contents while not in
  use. Half a second later it rewrites the `items` array: grouped by type, then name,
  then quality, with partial stacks merged.
- The write bumps the ZDO's data revision, the server syncs it to the nearby clients,
  and the vanilla `Container.CheckForChanges` (once a second) reloads it. Reopen the
  chest and it is sorted.
- Ownership is left alone. The client that last opened the chest stays the owner, so
  the next open request is answered as usual. If the server ever finds itself the
  owner it releases the chest after writing.

## Triggers

| Trigger | Default | Alone on the server? | Status |
|---|---|---|---|
| Close a chest (also hover stack-all and take-all: any change while nobody has it open) | on | yes | untested on the live server, see NOTES.md |
| **`/point` emote** (sorts the chest you opened last; or every closed chest within 10 m with `LastOpenedOnly = false`) | on | yes | untested; emotes are written to the player's own ZDO, which the client syncs |
| Middle-click the map near your chests (sorts every closed chest within 40 m) | on | yes | untested; the ping sniffing is the same as valheim-back's, which is verified |

"Place stacks" from inside the open chest cannot be a trigger on its own: the button
sends `RPC_RequestStack` to the chest's owner, which is the player who has it open, so
it is handled on their machine and never reaches the server. It does not need to be;
the change it makes is saved to the ZDO and sorted when the chest closes.

## Sort order

Items are grouped by `ItemType` in the order given by `TypeOrder`, then by name, then
by quality (highest first), then by stack size (largest first). Partial stacks of the
same item are merged up to the item's max stack size. The grid is filled row by row
from the top left.

Nothing is ever dropped, duplicated or changed: the plugin counts every item by
prefab, quality, world level, variant and cheated flag before and after, re-reads the
bytes it is about to write and counts again, and refuses to write on any mismatch
(logged as `REFUSED`). Items whose prefab `ObjectDB` cannot resolve are left
untouched at the end of the chest.

## Config (`BepInEx/config/mrpinoys.valheim.sort.cfg`)

| Key | Default | Meaning |
|---|---|---|
| General.SortOnClose | true | sort when a client changes a chest while nobody has it open |
| General.SortDelaySeconds | 0.5 | settle time after the last change; a reopen inside it cancels the sort |
| General.MergeStacks | true | combine partial stacks |
| General.TypeOrder | Material,Consumable,Fish,... | `ItemDrop.ItemData.ItemType` names, comma separated; unlisted types go last |
| General.Prefabs | (empty) | comma separated prefab names to treat as chests; empty means auto-detect (prefabs with `Container` and `Piece`, not carts) |
| General.Notify | true | small top-left message on the client after a sort |
| General.VerboseLog | true | trace every open, close, sort and skip; turn off once it works |
| Trigger.Ping.Enabled | true | map ping sorts nearby closed chests |
| Trigger.Ping.PingRadiusMeters | 40 | radius around the ping (max 64) |
| Trigger.Emote.Enabled | true | an emote sorts closed chests around the player |
| Trigger.Emote.Emote | point | which emote (chat command without the slash, lowercase as the game writes it) |
| Trigger.Emote.LastOpenedOnly | true | sort only the chest this player opened most recently; false sorts every closed chest within the radius |
| Trigger.Emote.MaxDistanceMeters | 0 | with LastOpenedOnly, refuse if the chest is farther than this; 0 = no limit |
| Trigger.Emote.EmoteRadiusMeters | 10 | radius around the player when LastOpenedOnly is false (max 64) |

## Install

1. Build: `dotnet build -c Release` (needs a Valheim install to reference; set
   `ValheimManaged` in `Sort.csproj` or pass `-p:ValheimManaged=<path>`).
2. Copy `bin/MrPinoys_Sort.dll` to the server's `BepInEx/plugins/`.
3. Restart the server. The log prints `MrPinoys Sort 0.1.0 loaded.` and, the first
   time a chest is touched, `Sortable chest prefabs (N): ...`.

## Known limits

- **Player inventories are out of scope.** The player's own bag is saved in the
  character file on the client (`PlayerProfile` writes it into the profile's
  `ZPackage`, `Player.Save`), not in any ZDO the server holds. A server-only mod
  cannot see or change it. Nothing in the 1.0 decompile contradicts this.
- Carts, ships and dungeon/loot containers are skipped on purpose (no `Piece`, or a
  `Vagon` / parent object involved).
- "Last opened" is remembered in memory (keyed by the profile's player ID, so it
  survives a reconnect) from the `opened by` events the server sees. After a server
  restart you have to open a chest once before the emote knows which one. A chest
  that was destroyed or picked up answers "Your last opened chest is gone"; one that
  is far away is still sorted unless `MaxDistanceMeters` says otherwise (the client
  loads the new layout when it comes near).
- A chest closed and reopened within the settle delay is left alone until the next
  close. A chest reopened just after the server wrote it (inside the sync round trip)
  keeps the client's version; the server catches up on the next change.
- Chests saved in the pre-1.0 item format are read and rewritten in the 1.0 format
  (the same thing the vanilla client does on its next save).
- Built against Valheim 1.0.0 (build 25253764); the item byte format (`Version.Item`
  109) and `ZDOVars` names are version specific. Re-check after a game update.
