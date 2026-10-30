# Charter land map (public API)

Other mods that place things at random (dungeons, camps, events) must keep clear of kingdoms. Charter publishes the land
that keeps and outposts hold so they can ask before placing.

## From Java

Add Charter as a dependency (`"charter": "*"` in `fabric.mod.json`) and call `charter.api.CharterAreas`. Every method is
read only and safe on any thread, worldgen threads included.

```java
if (CharterAreas.intersects("minecraft:overworld", minX, minZ, maxX, maxZ)) return; // choose another spot
```

| Method | Answers |
|---|---|
| `isHeld(dimension, chunkX, chunkZ)` | whether a kingdom holds the chunk |
| `isHeldAtBlock(dimension, blockX, blockZ)` | the same, for the chunk a block is in |
| `areaAt(dimension, chunkX, chunkZ)` | the `Area` holding the chunk, if any |
| `intersects(dimension, x1, z1, x2, z2)` | whether a box of blocks touches held land (corners inclusive, either order) |
| `areas()` / `areas(dimension)` | every area, all dimensions or one |
| `version()` | a number that rises each time the map is replaced |

An `Area` has a `kind` (`KEEP`, `OUTPOST` or `CLAIM`), the kingdom's name, the dimension, the chunk bounds, block bounds
and every chunk it covers. `CLAIM` is land a kingdom holds that no keep or outpost currently backs.

The map is an immutable snapshot that Charter replaces whole after land changes, at most once a tick. An `Area` you keep
never changes. Before a world runs, and after it stops, the map is empty.

## From anything else

`<world>/charter/areas.json` carries the same data, rewritten whenever land changes:

```json
{ "version": 12, "areas": [
  { "kind": "keep", "kingdom": "Azrak", "dimension": "minecraft:overworld",
    "bounds": { "minX": -1008, "minZ": -1760, "maxX": -865, "maxZ": -1617 },
    "chunks": [[-63, -110], [-63, -109]] } ] }
```

Operators can list it in game with `/charter admin areas`.

## What Charter does with it

- Raider strongholds: a site's centre is never within 8 chunks of any kingdom's land, and a stronghold keeps the
  usual keep gap (4 chunks) from every keep. Raiders stay inside their own walls.
- Structures: a structure start that would overlap held land is dropped before it generates, so dungeons and villages
  stay out of kingdoms. Set `structuresInKingdoms` to `true` in `charter.json` to allow them. It only affects chunks
  generated after the land is held.
