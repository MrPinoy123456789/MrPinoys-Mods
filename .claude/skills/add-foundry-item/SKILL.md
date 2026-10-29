---
name: add-foundry-item
description: Add a custom item to the Foundry mod by writing a JSON definition, validating it, and giving it in game. Covers the file location, the fields, the base item choice, the validate and give commands, and why a new item needs a server restart. Use when asked to add, change or remove a Foundry item, weapon or tool.
---

# Add a Foundry item

House rule for everything you write: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Work in
`A:\MrPinoys Mods\foundry`. Foundry items are data; do not write Java for one.

## 1. Write the definition

File: `config/foundry/content/<namespace>/items/<name>.json` on the server; in the repo, put samples under
`foundry/examples/content/<namespace>/items/`. The file name should match the id path. Field list and ranges are in
`foundry/schema/item.v1.schema.json`; an example is `foundry/examples/content/demo/items/crystal_sword.json`.

Required: `schema_version` (1), `id` (`namespace:name`, lowercase), `display_name`, `base_item` (a real vanilla item).
Optional: `lore`, `model`, `stack_size` (1 to 99), `durability`, `glint`, `rarity` (common, uncommon, rare, epic).

Rules the validator enforces: ids are unique across all files; an item with durability must have `stack_size` 1;
`base_item` must exist. Pick the base item for what the item should do natively (a sword base gives sword
behaviour); the custom look comes from `model`.

Never change an existing `id`: stacks already in inventories use it. To retire an item, tell the owner; do not
delete the file without saying so (orphans, DECISIONS.md D5).

## 2. Validate

```bash
cd "A:/MrPinoys Mods/foundry" && ./gradlew.bat coreTest
```

For a real server: `/foundry validate` prints every problem with the content id, the field and a fix. Fix all
ERRORs; an item with any ERROR is not loaded at all.

## 3. Try it

Registering a new item needs a server restart; `/foundry validate` says so. After restart:
`/foundry give <player> <namespace:name> [count]`. Changing an existing item's look or stats also needs a restart; changing its mechanics only needs `/foundry reload`
(see `docs/ARCHITECTURE.md`, "What can change at runtime").

## 4. Textures and models

Easiest: a flat texture. Put a 16 by 16 PNG at `config/foundry/assets/<ns>/textures/item/<name>.png` (samples in
`foundry/examples/assets/`) and set `"texture": "<ns>:item/<name>"`. Foundry generates the model and the client item
definition. For a hand-made model use `"model": "<ns>:item/<name>"` with the file at
`assets/<ns>/models/item/<name>.json`. Never both. A missing file is an ERROR (the item is not loaded). New
textures change the pack, so players re-download it; restart the server to rebuild.

## 4b. Mechanics

`mechanics` is a list; each has `trigger` (`on_use`, `on_hit_entity`), optional `cooldown_ticks` (20 per second),
`chance` (0 to 1), `conditions` and `actions`. Actions: `play_sound` (sound, volume, pitch), `apply_effect` (effect,
duration, amplifier), `give_item` (item, count). Conditions: `has_permission` (level 0 to 4). Effects and sounds apply
to the player using the item. Limits: 8 mechanics per item, 16 actions, 8 conditions. Anything else (a new action
type, a target selector) needs Java in `foundry.core.mechanics.MechanicTypes` and `foundry.mc.MechanicHost`; say so
instead of improvising. Mechanics change with `/foundry reload`, no restart.

## 5. Report

Say what you added, the validation result you actually saw, and what you could not check (a vanilla client has not
been used unless the owner did it).
