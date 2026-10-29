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
ERRORs; read WARNINGs (a missing model is a warning).

## 3. Try it

Registering a new item needs a server restart; `/foundry validate` says so. After restart:
`/foundry give <player> <namespace:name> [count]`. Changing only a definition that already exists still needs a
restart today (see `docs/ARCHITECTURE.md`, "What can change at runtime").

## 4. Models and textures

Not available until task F1 lands. Until then leave out `model` and the item looks like its base item.

## 5. Report

Say what you added, the validation result you actually saw, and what you could not check (a vanilla client has not
been used unless the owner did it).
