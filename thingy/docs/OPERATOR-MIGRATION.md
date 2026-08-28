# Wondrous to Thingy: operator migration notes

For whoever runs the server this suite is installed on. Read this before
swapping the `wondrous` jar for the `thingy` jar.

## What changes

- **The jar.** `MrPinoys_wonders-0.1.0.jar` is replaced by
  `MrPinoys_thingy-0.1.0.jar`. Both are server-side only; vanilla clients need
  nothing installed either way.
- **The command root.** `/wondrous` becomes `/thingy`. Every subcommand keeps
  its name: `/thingy give`, `/thingy list`, `/thingy help`, `/thingy links`.
  If you have a console script, a permissions config, or a keybind macro that
  types `/wondrous ...`, update it to `/thingy ...`.
- **Nothing else.** Every item keeps its id, its name, its lore, and its
  behaviour. A Pocket Crafter, a pair of Up Up And Bye Boots, a placed
  Left It Out Crafter with a grid full of ingredients: all of it is
  byte-identical before and after the swap, because Thingy stamps the exact
  same `wondrous:<id>` tag the old jar did.

## What does not change

- **Item ids in `shop.json`.** cobbleeconomy's shop listings still name these
  items `wondrous:<id>` (e.g. `wondrous:flying_boots`). That is deliberate:
  the prefix names the item's own identity, not which mod currently resolves
  it. No shop config needs editing.
- **Placed stations, sprinklers, and links.** These live in a world-data file
  keyed `wondrous:wondrous_state`, not on the item stack. Thingy reads and
  writes the exact same file wondrous did, so a Left It Out Crafter's saved
  grid, a Lazy Sprinkler's bone meal, and a Put It There Wand link all survive
  the swap with nothing to run and nothing to migrate.
- **The suite_items datapack.** Thingy's jar ships its own copy of
  `data/wondrous/suite_items/*.json`, the same 29 entries wondrous shipped.
  Removing the wondrous jar does not remove these from the server; the merged
  datapack view cobbleeconomy reads from is unaffected.

## How to do the swap

1. Stop the server.
2. Remove `MrPinoys_wonders-0.1.0.jar` from `mods/`.
3. Add `MrPinoys_thingy-0.1.0.jar` to `mods/`.
4. Start the server.
5. Confirm: `/thingy list` shows all 29 items; a previously placed pocket
   crafting station opens with its grid intact; `allow-flight=true` is still
   set in `server.properties` if anyone uses the flying boots (unchanged
   requirement, same as before).

## Rollback

Reverse steps 2 and 3: remove the thingy jar, add the wondrous jar back,
restart. Every stack in every inventory is unchanged, because the tag shape
never moved. Every placed station, sprinkler, and link is unchanged, because
both jars read the same world-data file.

## Not yet done

This document exists ahead of the actual swap. As of this writing, the swap
has not been run against a live server (production or otherwise); the
byte-identical-stack and same-world-data-file claims above are verified at
the code level (identical `custom_data` shape, identical `SavedDataType` id)
but not yet exercised end to end on a running world. Run the swap on a copy
of the world first, confirm every item above, and only then run it on the
production server.
