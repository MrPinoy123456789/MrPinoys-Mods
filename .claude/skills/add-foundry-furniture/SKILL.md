---
name: add-foundry-furniture
description: Add a piece of furniture to the Foundry mod. Covers the two files (a placing item and a furniture definition), what it looks like (a vanilla block or any item drawn as a display), solid versus walk-through, and how a player places and removes it. Use when asked to add a chair, table, lamp, decoration or other placeable.
---

# Add Foundry furniture

House rule for everything you write: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Work in
`A:\MrPinoys Mods\foundry`. Read `foundry/docs/FURNITURE_DECISION.md` once; the owner's rules are in it.

## What furniture is

A placed piece is a real block (a barrier when solid, an invisible light block when walk-through) with a display drawn
over it. It behaves like a vanilla block: it sits in one block space, faces one of the four cardinal directions the way
most blocks do, and Foundry adds no movement or interaction beyond what vanilla blocks already do. Do not promise
rotation by angle, free-floating placement, or special cases such as stairs or armor stands.

## Two files

1. A placing item, per the `add-foundry-item` skill: `<ns>/items/<name>.json`. Its look is what players hold.
2. The furniture: `<ns>/furniture/<name>.json`. Example: `foundry/examples/content/demo/furniture/desk.json`.

Fields (see `foundry/schema/furniture.v1.schema.json`): `id`, `placed_by` (the item above), `display` with exactly one
of `block` (a vanilla block id, such as `minecraft:crafting_table`) or `item` (any item id, including your own items
with textures), optional `scale` (0.1 to 4), `offset_y` (-2 to 2), `solid` (default true), `interaction.width` and
`interaction.height` (the punch box, 0.1 to 4). One placing item places one piece.

No art is needed: draw a vanilla block scaled up or down, or reuse an item that already has a texture.

## Try it

`/foundry validate`, restart the server (new items need one), `/foundry give @s <ns>:<item>`, right-click a block to
place. Punch the piece to pick it up (it drops the item). Solid pieces block movement; walk-through ones do not.

## Limits to say out loud

Pieces are not mineable in survival (punch them); pistons cannot move them; there are no click actions yet; holders
are rebuilt when a chunk loads, so editing a definition shows on the next chunk load or restart, not instantly.
