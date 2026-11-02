# Charter Maps (client mod for Xaero's maps)

A small client-only Fabric mod for Minecraft 26.3 (mod id `charter_xaero`, jar `MrPinoys_CharterMaps-0.1.0.jar`). On a
server running Charter it shows the kingdoms on Xaero's World Map and Xaero's Minimap:

- every held chunk tinted in its kingdom's colour (the `tagColors` colours: Azrak gold, Aurelia orange, Everdelle pink,
  Azurius baby blue, Faeloria purple), with a stronger, slightly darker rim where the kingdom ends;
- the keep's core chunk filled a little more strongly;
- the kingdom's name under the cursor (World Map short tooltip), and a full line such as "Keep of Azrak (Dwarf)",
  "Outpost of Aurelia (Human)", "Held by Faeloria (Fae)" or "Spawn: Hey Dol Hollow" (World Map long tooltip and the
  Minimap's info line);
- map markers (supply drops, events) as Xaero waypoints with their label, initials and the nearest waypoint colour.
  Without the Minimap there are no waypoints, so a marker is drawn as a solid coloured chunk on the World Map instead.

On any other server, or without Xaero's maps, it does nothing visible. `/chartermap` (client side, any player) prints
what it holds: how many kingdoms, chunks and markers it was sent, and whether each Xaero map is drawing.

## Installing (players)

Java Edition only. Bedrock has no Xaero's maps, and Bedrock players are never sent the map.

1. Fabric Loader 0.19.5 or newer and Fabric API for 26.3.
2. Xaero's World Map (1.47.0 or newer) and/or Xaero's Minimap (26.6.0 or newer) for 26.3, from Modrinth
   (`xaeros-world-map`, `xaeros-minimap`). Either one alone works; both together is best.
3. `MrPinoys_CharterMaps-0.1.0.jar` in the `mods` folder.

Nothing is needed on the server beyond Charter itself; the server only sends the map to clients that have this mod.

## Building

```
cd charter-xaero
export JAVA_TOOL_OPTIONS="-Xmx1200m"
./gradlew.bat build --no-daemon --console=plain -PxaeroJars=<folder with the Xaero jars>
```

The build compiles against Xaero's jars, which are "All Rights Reserved": they are never committed to this repository,
never put in our jar and never redistributed. Download them yourself into a folder outside the repository (or set the
`XAERO_JARS` environment variable to it). The build fails with a message naming the files if the folder is missing.
The versions it was built and checked against (sizes and SHA-512 from the Modrinth API):

| File | Modrinth URL | Size | SHA-512 |
|---|---|---|---|
| `xaeroworldmap-fabric-26.3-1.47.2.jar` | https://cdn.modrinth.com/data/NcUtCpym/versions/VM8lNSSM/xaeroworldmap-fabric-26.3-1.47.2.jar | 1488669 | `cac3c0fb3121ddebc7f073571dcbce5f1811ca001ea94ef03c1da667e8a3413144f570c4b593f632358eff98fa138631ed7907fd1bd5da7d43c037ea782125fb` |
| `xaerominimap-fabric-26.3-26.6.2.jar` | https://cdn.modrinth.com/data/1bokaNcj/versions/MfxfgXaO/xaerominimap-fabric-26.3-26.6.2.jar | 2233189 | `edf57b80cfe0e0fe229728a6babc3b25c364c07a0406bcdb3a21f73fc225b98954c68ff456c7b1af3182c48512f29d47238ddc501dbfd5eaca0a4609fddcc280` |

XaeroLib has no separate 26.3 release: both jars carry `xaerolib-fabric-26.3-1.8.5.jar` inside (`META-INF/jars`), and
the build takes it from there. The jar lands in `build/libs/` and is copied to the workspace `dist/`.

Tests: `pureTest` (run by `check` and `build`) covers the wire format (this mod's copy), the chunk index, border
detection, region hashes, colour maths, waypoint colour choice, tooltips, version checks and the message store.

## How it works

- **Wire format.** One source: Charter's `charter/fabric/src/main/java/charter/mapwire/`. The build copies it into
  `build/generated/mapwire` with the package renamed to `charterxaero.wire`, so the server and this mod write and read
  the same bytes and never load the same class name twice. The layout is documented in `MapWire`.
- **Channel.** The payload is `charter:map`, a byte array. Registering a receiver for it is what tells the server this
  client wants the map. Decoding only copies the bytes; a message that does not parse is dropped (logged once), never
  a disconnect. A delta that does not follow on from what is held is dropped too; the next join sends a fresh snapshot.
- **Data.** `model/MapStore` applies messages on the game thread and swaps an immutable `KingdomIndex` that Xaero's map
  threads read. When the index changes it works out which 512 block regions draw differently (including the edge
  chunks of neighbouring regions) and asks only those to redraw.
- **Xaero binding** (`xaero/`). Neither World Map nor Minimap has a public way to add a highlighter: each fills its
  `HighlighterRegistry` in its session (with Open Parties and Claims only) and freezes it with `end()`. Two small
  mixins add our highlighter at the head of `end()`, one per mod, each applied only when that mod is loaded and new
  enough (`XaeroMixinPlugin`, `XaeroSupport`). In World Map 1.47.2 `registerMinimapHighlighters` is empty, so the World
  Map's highlighters never reach the Minimap and registering in both does not tint a chunk twice; if a later Xaero
  release bridges them, drop `MinimapRegistryMixin`. Colours go to Xaero as `0xBBGGRRAA` with the fill, north, east,
  south and west edges, as Xaero's own claim highlighter does. Redraws use `DimensionHighlighterHandler.clearCachedHash`
  (World Map) and `requestRefresh` (Minimap). Markers use the Minimap's third party waypoints (`ThirdPartyWaypointManager`,
  origin `charter_xaero:markers`), re-sent on a dimension change because the Minimap keeps waypoints per dimension.
- **No Xaero.** Every class that names a Xaero type is only reached behind `XaeroSupport`; without Xaero the mod keeps
  the data and draws nothing. An older Xaero than the minimums above is logged once and skipped.

Open Parties and Claims (LGPL-3.0-only) was read for how claims reach Xaero's maps; its highlighter lives in Xaero's
own jar. No code from either was copied.

## What only a live client can confirm

Nothing here has run in a real game yet. To check on a Java client with Xaero's maps against the kingdom server:

- the colours, fill strength and border rim look right on the World Map and the Minimap, and the keep heart shows;
- the tooltips appear (World Map: hover a kingdom; Minimap: the info line, if enabled in its settings);
- a claim, a keep move (`/charter admin faction move`) or a `/charter admin mapmarker add` shows within a few seconds
  without reopening the map;
- markers show as waypoints; Xaero only lists third party waypoints while the player is on the default waypoint set or
  has "all sets" on, so a player on a custom set may not see them;
- cost on a large map (the index is rebuilt per message and region redraws are limited to what changed).
