# Pocket Dungeons pack author exercise (M72)

> For an independent author who has not read the mod's source. You need only
> the published release jar, this file, and `docs/INTEGRATION.md`. No Java
> imports, no npm setup, no client resource pack. Everything runs on a server
> with the jar installed.

This exercise decides whether the authoring API is beta-ready. Work through it
once, then fill in the report at the end. If you cannot finish a step from the
diagnostic the mod gives you, that is a finding against the API, not your
mistake: write down where you got stuck.

## What you are authoring

A small datapack that adds four things, all under your own namespace:

1. one affix (a run modifier)
2. one theme (a room palette)
3. one role (a population role)
4. one Cube recipe (a catalyst effect)

Then you will deliberately break a reference, fix it from the diagnostic, and
upgrade a legacy fixture.

## Prerequisites

- A dedicated server running the Pocket Dungeons release jar (this build).
- Operator access (`/dungeon admin ...`).
- The server's world directory is where your pack will live. You do not touch
  the source tree.

## Step 0: export the starter

Export the starter pack under your own namespace. The command takes the pack
directory name and an optional namespace (defaults to the pack name). The
namespace is rewritten into every file path and file content during export, so
you never have to edit files by hand just to rename a namespace.

```
/dungeon admin exportstarter mypack
```

This writes `<world>/datapacks/mypack/` with one example of every content type
under the `mypack` namespace, plus a `README.txt`. Read the README.

If you want the directory name and namespace to differ (for example, a
directory called `my_dungeon_pack` with namespace `mydungeons`):

```
/dungeon admin exportstarter my_dungeon_pack mydungeons
```

If you are re-running after a previous attempt, add `confirm` to back up the
old pack and replace it:

```
/dungeon admin exportstarter mypack confirm
```

> If you exported a previous attempt under the wrong namespace (for example the
> default `starter` namespace), delete that old pack directory from
> `<world>/datapacks/` before reloading. Two packs with the same content ids
> collide: duplicate catalysts, duplicate diary bands, and so on. The
> validator will name every collision, but the fix is to remove the old pack.

Run `/reload`. The starter loads as live content under `mypack`.

> Note: `/reload` is a vanilla command. It prints "Reloading!" and then
> finishes silently. There is no "reload succeeded" message in vanilla
> Minecraft; the absence of an error is the success signal. Pocket Dungeons
> adds no reload feedback to avoid spending its one mixin on a vanilla
> command.

## Step 1: validate the clean starter

```
/dungeon admin validate
```

Expected: `Pack validation passed: no findings.`

If you see findings, read each line. The format is `file / field: cause` and,
for plan-level findings, a trailing `(seed=N)` you can replay with
`/dungeon admin validate <seed>` (which runs only that one seed, for
reproducing a specific failure). The no-seed form tries multiple seeds and
only reports if every one fails.

The most common cause of findings here is a leftover pack from a previous
export still loaded alongside the new one. If you see collisions (duplicate
catalysts, duplicate diary bands), delete the old pack directory from
`<world>/datapacks/` and run `/reload` again.

## Step 2: author one affix

Edit `data/mypack/dungeon_affix/example_affix.json`. Change the `label` and
`blurb`, and change the `effects` to something other than `ominous`.

For example, to make a "Molten" affix that places lava hazards in cells:

```json
{"version": 1, "label": "Molten", "blurb": "Lava seeps through the floor.", "order": 100, "min_level": 0, "weight": 1, "depletion_multiplier": 1, "incompatible": [], "effects": {"hazard_kind": "LAVA", "hazards_per_cell": 2}}
```

The `effects` fields you can set (all optional, defaults shown in
`docs/INTEGRATION.md` M69 section):

| Field | Type | Values |
|---|---|---|
| `ominous` | boolean | stamps every spawner and vault ominous |
| `hazard_kind` | string | `LAVA`, `TNT`, or `NONE` (default) |
| `hazards_per_cell` | int | 0 to 16; must be > 0 when `hazard_kind` is set |
| `trial_count_multiplier` | double | 1.0 to 4.0 |
| `cooldown_factor` | double | 0.25 to 1.0 |
| `voided_floor` | boolean | removes the floor under cells |

Pick one or two, not all. An affix with no effect is rejected at load.

Run `/reload`, then `/dungeon admin validate`. It should still pass.

## Step 3: author one theme

Edit `data/mypack/dungeon_theme/example_theme.json`. The `processors` field
names a processor list. Point it at a different built-in. The available
built-in processor lists are:

- `pocketdungeons:theme_deepslate`
- `pocketdungeons:theme_blackstone`
- `pocketdungeons:theme_prismarine`
- `pocketdungeons:theme_drowned_vault`
- `pocketdungeons:theme_grove`

Keep `room_theme` as `mypack:example_theme`.

Run `/reload`, then `/dungeon admin validate`.

## Step 4: author one role

Edit `data/mypack/dungeon_role/example_role.json`. Change `weight` and pick an
`operation` from the closed set (see `docs/INTEGRATION.md`, "M70: Role
schema"):

- `TRIAL_ENCOUNTER`: stamp a trial spawner (the built-in encounter operation)
- `TOOL_CACHE`: place a tool cache chest
- `NONE`: no operation (the role exists for weighting only)

The starter ships with `weight: 0`, which means the planner never assigns this
role to any cell. That is intentional: a role with weight > 0 needs at least one
room listing it in its `roles` array, or the planner will try to place it and
find no room. Set `weight` to a positive number only after you have a room
that supports this role.

Run `/reload`, then `/dungeon admin validate`.

## Step 5: author one recipe

Edit `data/mypack/cube_recipe/example_recipe.json`. Change the `catalyst` to an
item the built-in recipes do not use. The built-in catalysts are:

| Recipe | Catalyst |
|---|---|
| ominous | `minecraft:ominous_bottle` |
| feral | `minecraft:bone` |
| bounded_supply | `minecraft:string` |
| infested | `minecraft:tnt` |
| flooded | `minecraft:water_bucket` |
| deep_dark | `#minecraft:wool` (tag) |
| compass | `minecraft:compass` |
| path_extension | `minecraft:amethyst_shard` |
| store | `minecraft:emerald` |
| blaze_bias | `minecraft:snowball` |
| bazaar_bias | `minecraft:gold_nugget` |
| slime_guarantee | `minecraft:slime_ball` |

Free catalysts (not used by any built-in) include `minecraft:diamond`,
`minecraft:iron_ingot`, `minecraft:gold_ingot`, `minecraft:netherite_ingot`,
and most other vanilla items. Keep the `guaranteed_rooms` group pointing at
`the_store` for now.

Run `/reload`, then `/dungeon admin validate`.

## Step 6: deliberately break a reference

In your recipe file, change the `guaranteed_rooms` group's `names` from
`["the_store"]` to `["does_not_exist"]`. Run `/reload`, then:

```
/dungeon admin validate
```

Expected: a finding whose `file` is your recipe id (`mypack:example_recipe`),
`field` is `guaranteed_rooms`, and `cause` says the room was not found. There
may also be a "group can never fire" finding. The output looks like:

```
2 finding(s):
  mypack:example_recipe / guaranteed_rooms: room not found: does_not_exist
  mypack:example_recipe / guaranteed_rooms: group can never fire: no named room exists (min_tier=0)
```

This is the diagnostic. Read it. It names the file, the field, and the cause.

## Step 7: fix it from the diagnostic

Using only the finding text, restore a valid room name. Put `["the_store"]`
back (or name a room you actually added). Run `/reload`, then validate. It
should pass again.

## Step 8: upgrade a legacy fixture

A legacy bare reference (no colon) resolves to the `pocketdungeons` namespace.
Your recipe's `["the_store"]` is one: it silently means
`pocketdungeons:the_store`. Upgrade it to the explicit, namespaced form
`["pocketdungeons:the_store"]`. Run `/reload`, then validate. It should still
pass, because the explicit form means exactly what the bare form resolved to.

This is the upgrade path a pre-M68 pack takes: bare names keep working, and an
author can qualify them at any time without changing behaviour.

## Step 9: ship your rooms (optional, if you built one in-world)

If you built a room with `/dungeon admin buildroom` and captured it with
`/dungeon admin saveroom <name>`, ship it with:

```
/dungeon admin exportworkspace mypack_rooms
```

This writes a distributable datapack at `<world>/datapacks/mypack_rooms/`
containing your captured templates, with a `pack.mcmeta` for the current pack
format. It refuses to overwrite an existing destination unless you add
`confirm`:

```
/dungeon admin exportworkspace mypack_rooms confirm
```

## Report

Fill this in. Your report decides whether the API is beta-ready.

- Did step 1 pass on the first try? (yes / no)
- Which effect did you pick for your affix in step 2?
- Which processor list did you point your theme at in step 3?
- Which operation did you pick for your role in step 4?
- Which catalyst did you pick for your recipe in step 5?
- In step 6, paste the exact finding line(s) the validator gave you.
- In step 7, did the finding text alone tell you enough to fix it? (yes / no)
- In step 8, did the qualified form validate the same as the bare form? (yes / no)
- Was the `/reload` silence (no success message) confusing? (yes / no)
- Any step where you had to read the mod's source to proceed? (list them, or "none")
- Any diagnostic that did not name a file, a field, and a cause? (list them, or "none")
- Any step where the worksheet did not give enough information to proceed
  without guessing? (list them, or "none")

### Run 1 (2026-09-09)

- Step 1: no, not on the first try. Initial export used the `starter` namespace
  with no in-game rename command, and a backup directory inside `datapacks/`
  was loaded as a live pack, causing diary band and catalyst collisions. Fixed
  by adding a namespace argument to `exportstarter` and moving backups outside
  `datapacks/`. After the fix, step 1 passed.
- Step 2: `hazard_kind: LAVA`, `hazards_per_cell: 2` (Molten affix). Passed.
- Step 3: changed `processors` from `theme_deepslate` to another built-in.
  Passed.
- Step 4: kept `weight: 0`, changed `operation`. Passed.
- Step 5: `minecraft:gold_nugget` was rejected as a duplicate catalyst
  (collides with built-in `bazaar_bias`). Switched to `minecraft:gold_ingot`.
  Passed. The duplicate catalyst diagnostic was correct and actionable.
- Step 6: `2 findings: guaranteed_rooms: room not found; group can never fire:
  no named room exists (min_tier=0)`. Both findings named the file, field, and
  cause.
- Step 7: yes, the finding text alone was enough to fix it. Restored
  `["the_store"]`, passed.
- Step 8: yes, the qualified form `["pocketdungeons:the_store"]` validated the
  same as the bare form. Passed.
- `/reload` silence: confusing on first encounter. Noted in the worksheet.
- Steps requiring source access: none, after the worksheet was updated with
  the processor list, catalyst list, and effects table.
- Diagnostics missing file/field/cause: none.
- Steps lacking worksheet info: step 0 initially (no in-game rename command),
  step 2 initially (no concrete example), step 3 initially (no processor
  list), step 5 initially (no catalyst list). All fixed in the worksheet.

### API changes from run 1

- `exportstarter` now takes an optional namespace argument and rewrites the
  `starter` namespace into file paths and contents during export, so no manual
  file editing is needed.
- Backups now go to `<world>/pocketdungeons_backups/` (outside `datapacks/`),
  so Minecraft never loads them as live packs.
- The plan check now only reports if every seed fails, not the first one.
- The starter diary uses `band: 100` to avoid colliding with built-in diaries.
- The starter role uses `weight: 0` so the planner never assigns it to a cell
  without a supporting room.

## What is not extensible

You cannot add a new situation handler, a new affix effect, a new role
operation, or a new recipe effect without a Java edit. Those are closed sets,
listed in `docs/INTEGRATION.md` section 3 ("What is *not* extensible yet"). If
you tried one and the validator rejected it, that is by design.
