---
name: add-foundry-recipe
description: Add a crafting recipe to the Foundry mod by writing a JSON definition. Covers the file location, the shaped and shapeless fields, ingredient and tag rules, the validate and reload commands, and why a recipe change needs no restart. Use when asked to add, change or remove a Foundry recipe or a way to craft a Foundry item.
---

# Add a Foundry recipe

House rule for everything you write: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Work in
`A:\MrPinoys Mods\foundry`. Foundry recipes are data; do not write Java for one.

Add or change a crafting recipe for a Foundry or vanilla item by writing a JSON definition. Recipes are validated
in pure core, generated as vanilla recipe JSON into `config/foundry/datapack/`, and reach the recipe book through a
data reload, so a working recipe needs no server restart.

## Where the file goes

`config/foundry/content/<namespace>/recipes/<name>.json`, one recipe per file. Repository examples live in
`foundry/examples/content/demo/recipes/`.

## The JSON

`schema_version: 1`, `id` (`namespace:name`, lowercase), `type` (`shaped` or `shapeless`), `result` (item id),
optional `count` (1-99, default 1), optional `group`. The authoritative field list is
`foundry/schema/recipe.v1.schema.json`; the parser is `foundry.core.RecipeParser`.

Shaped needs `pattern` (1-3 rows of 1-3 characters, all rows the same length, space is empty) and `key` mapping each
symbol to an item id or `#tag`. Every key symbol must appear in the pattern and every non-space pattern character must
have a key. Shapeless needs `ingredients` (1-9 item ids or `#tags`, repeats allowed).

Ingredients may reference vanilla items, Foundry items, or `#` tags; `result` must be a single item, never a tag.
A result pointing at a Foundry item resolves against the loaded set, so the item must validate too.

## Validate and reload

`/foundry validate` lists every problem with the file, field and fix. `/foundry reload` refuses the whole set while
any file has an error and keeps the old recipes live; on a clean swap it rewrites the generated datapack and runs the
data reload, so new and changed recipes appear in the recipe book at once. Removing a recipe file and reloading
removes the recipe.

## Checks

CoreSuite's `recipes()` block covers parsing errors, duplicate ids, unknown ingredients and generated JSON. The
gametest `recipesLoadThroughTheGeneratedDatapack` proves the pack delivers recipes into the live RecipeManager. When
changing this pipeline, run `./gradlew.bat coreTest` then `./gradlew.bat build`.
