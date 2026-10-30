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

Triggers: `on_use` (right click), `on_hit_entity` (melee hit on a living entity), `on_swing` (left click, including
at air; not a melee hit and not mining), `on_bolt_hit` (a bolt this item launched landed; needs a `tag`).

Actions:
- `play_sound`: `sound` (id), optional `volume` (0 to 10), `pitch` (0.5 to 2), `target`.
- `apply_effect`: `effect` (id), `duration` (ticks, 20 per second), optional `amplifier`, `target`. With
  `"target": "area"` it needs `radius` (0.5 to 16) and affects players within that radius, the caster included, narrowed
  by optional `require_permission` and `exclude_permission` (node ids such as `race:fairy`).
- `give_item`: `item` (id), optional `count`, `target`. Skipped with a warning when the target is not a player.
- `damage`: `amount` (0.5 to 100), `target`. Magic damage credited to the caster, so protection mods can intercept it.
- `spawn_particles`: `particle` (a plain particle id), optional `count`, `spread`, `speed`, `target`.
- `send_message`: `message` (up to 80 characters, shown on the action bar), `target`.
- `launch_bolt`: `tag` (name), optional `range` (1 to 64 blocks, default 10), `speed` (0.2 to 3 blocks per tick,
  default 1), `radius` (hit size, default 0.4), `particle`, `require_permission`, `exclude_permission`. Flies along the
  look direction, stops at the first block, never hits the caster, and passes through entities that fail the filters.
  Not allowed under on_bolt_hit. At most 8 live bolts per player, 256 in all.

Conditions: `has_permission`: `level` (0 to 4 command level), optional `target`. `is_sneaking`: optional `value`
(true by default; false means "not sneaking").

Mechanic fields besides trigger, conditions, actions: `cooldown_ticks`, `chance`, `name` (a label; when the power is on
cooldown the player sees "<name> is recharging: 42s" on the action bar) and `tag` (on_bolt_hit only).

`target`: `self` (default, the acting player), `hit` (the entity struck; only on `on_hit_entity` and `on_bolt_hit`) or
`area` (apply_effect only).

Conditions are checked before the cooldown, so a sneak only power on cooldown stays quiet on a plain right click.
Cooldowns are per player and per mechanic, held in memory (a restart clears them), and independent of each other, so
one item can carry several powers with separate timers. Foundry never names a permission node itself; a race or
kingdom mod grants the nodes.

A worked example with all of it: `foundry/examples/content/demo/items/fairy_wand.json` (left click sparkle bolt,
right click Mischief bolt, sneak right click Healing Light).

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
