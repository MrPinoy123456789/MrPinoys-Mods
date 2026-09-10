Pocket Dungeons starter pack
===========================

This is a small, namespaced starter exported by `/dungeon admin exportstarter`.
It ships one example of every content surface under the `starter` namespace,
so you can author a pack by editing these files instead of overriding every
built-in the mod bundles.

Quick start
-----------

1. Run `/dungeon admin exportstarter mypack` on your server. This writes the
   pack to `<world>/datapacks/mypack/`.
2. Rename the `starter` namespace to your own: move `data/starter/` to
   `data/<yourpack>/` and edit every file's references from `starter:` to
   `<yourpack>:`.
3. Edit the example files. Each one is a working minimal definition that
   references real built-in resources (templates, processor lists, loot
   tables) so the pack loads cleanly as a starting point.
4. Run `/reload`, then `/dungeon admin validate` to check your pack. Every
   finding names a file, a field, a cause, and (for plan-level findings) a
   reproducible seed you can replay with `/dungeon admin plan <seed>`.
5. When your rooms are captured in-world with `/dungeon admin buildroom` and
   `/dungeon admin saveroom <name>`, ship them with
   `/dungeon admin exportworkspace <yourpack>`.

What each file is
-----------------

- data/starter/dungeon_room/example_room.json   a room (template + roles)
- data/starter/dungeon_theme/example_theme.json a theme (processor list)
- data/starter/dungeon_adventure/example_theme.json  the theme graph node
- data/starter/dungeon_affix/example_affix.json  an affix (run modifier)
- data/starter/dungeon_bag/example_bag.json      a starting bag
- data/starter/dungeon_role/example_role.json   a population role
- data/starter/cube_recipe/example_recipe.json  a Cube recipe
- data/starter/diary/example_diary.json         a collectible diary entry
- data/starter/anomaly_room/example_anomaly.json an anomaly room

The full field reference is in docs/INTEGRATION.md. The closed vocabularies
(situation tags, role operations, recipe effects) are listed there too; a
value outside them is rejected at load with the file named.

This starter is a skeleton. Installing it as-is adds one working example of
each content type to your server; customise it before going live.
