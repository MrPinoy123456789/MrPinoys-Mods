---
name: add-foundry-mechanic
description: Add or change a mechanic on a Foundry item (trigger, conditions, actions, cooldown, chance). Covers the JSON shape, the registered type list, the target selector, the safety limits, and when Java is required instead of JSON. Use when asked to make a Foundry item do something (sound, effect, give, cooldown, on-hit behaviour).
---

# Add a Foundry mechanic

House rule for everything you write: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Work in
`A:\MrPinoys Mods\foundry`. A mechanic lives in the item's `mechanics` array; see the `add-foundry-item` skill for
the file layout. Mechanics reload with `/foundry reload`, no restart.

## Shape

```json
"mechanics": [
  {
    "trigger": "on_use",
    "cooldown_ticks": 100,
    "chance": 0.5,
    "conditions": [ { "type": "has_permission", "level": 0 } ],
    "actions": [ { "type": "play_sound", "sound": "minecraft:block.amethyst_block.chime" } ]
  }
]
```

A working example: `foundry/examples/content/demo/items/crystal_sword.json`. The schema is
`foundry/schema/item.v1.schema.json`; the authoritative type list is `foundry.core.mechanics.MechanicTypes`.

## What is legal

Triggers: `on_use` (right click with the item), `on_hit_entity` (hit a living entity with it).

Actions:
- `play_sound`: `sound` (id), optional `volume` (0 to 10), `pitch` (0.5 to 2), `target`.
- `apply_effect`: `effect` (id), `duration` (ticks, 20 per second), optional `amplifier`, `target`.
- `give_item`: `item` (id), optional `count`, `target`. Skipped with a warning when the target is not a player.

Conditions: `has_permission`: `level` (0 to 4 command level), optional `target`.

`target`: `self` (default, the acting player) or `hit` (the entity struck). `hit` is an ERROR on any trigger but
`on_hit_entity`.

Limits (hard errors, not warnings): 8 mechanics per item, 8 conditions and 16 actions per mechanic,
20 firings per second per player, no recursive triggers. `cooldown_ticks` is a mechanic field; there is no
cooldown condition to write.

## Check it

`cd "A:/MrPinoys Mods/foundry" && ./gradlew.bat coreTest` for the pure checks, `/foundry validate` on a live
server. Fix every ERROR; a bad mechanic unloads the whole item.

## When JSON is not enough

New triggers, action or condition types need Java: a row in `foundry.core.mechanics.MechanicTypes`, a branch in
`foundry.mc.MechanicHost` (and a new hook in `foundry.mc.FoundryItem` for a new trigger), plus a `CoreSuite` check
and a gametest. Say that is what is needed instead of faking it with parameters.
