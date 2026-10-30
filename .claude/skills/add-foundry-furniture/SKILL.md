---
name: add-foundry-furniture
description: Add a piece of furniture to the Foundry mod. Covers the two files (a placing item and a furniture definition), what it looks like (a vanilla block or any item drawn as a display), solid versus walk-through, and how a player places and removes it. Use when asked to add a chair, table, lamp, decoration or other placeable.
---

# Add Foundry furniture

House rule for everything you write: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Work in
`A:\MrPinoys Mods\foundry`. Read `foundry/docs/FURNITURE_DECISION.md` once; the owner's rules are in it.

## What furniture is

Each piece is a real registered block with a display entity drawn over it. It behaves like a vanilla block: it is
placed with the normal block rules, faces one of the four cardinal directions, has hardness, takes time to mine, drops
its placing item, makes wood sounds, and is saved with the chunk. Foundry adds no movement or interaction beyond what
vanilla blocks already do. Do not promise
rotation by angle, free-floating placement, or special cases such as stairs or armor stands.

## Two files

1. A placing item, per the `add-foundry-item` skill: `<ns>/items/<name>.json`. Its look is what players hold.
2. The furniture: `<ns>/furniture/<name>.json`. Example: `foundry/examples/content/demo/furniture/desk.json`.

Fields (see `foundry/schema/furniture.v1.schema.json`): `id`, `placed_by` (the item above), `display` with exactly one
of `block` (a vanilla block id, such as `minecraft:crafting_table`) or `item` (any item id, including your own items
with textures), optional `scale` (0.1 to 4), `offset_y` (-2 to 2), `solid` (default true), `hardness` (0 to 50, default 2, like
wood). One placing item places one piece.

No art is needed: draw a vanilla block scaled up or down, or reuse an item that already has a texture.

## Try it

`/foundry validate`, restart the server (new items need one), `/foundry give @s <ns>:<item>`, right-click a block to
place. Mine it like a block to get the item back. Solid pieces block movement; walk-through ones do not.

## Known behaviour (found in a client on 2026-10-29)

- Mining works and takes the definition's hardness, but there is no crack animation. The client draws cracks onto a
  block's model, and the visible part here is a display entity over a model-less stand-in. Cracks would need real block
  models with a pack model per piece; not built, not a bug.
- Give the placing item a base item that is NOT a block (`minecraft:paper`, `minecraft:glowstone_dust`) and a
  `texture`. A block base item makes the client predict its own placement and play a second place sound; the loader
  warns about it. Placement still works either way.
- Break particles and sound follow the displayed block (planks for an item display), not the stand-in.

## Limits to say out loud

Each piece is a registry entry, so adding or changing one needs a server restart. There are no click actions yet.
Pieces placed by the first (barrier) build stay as plain barrier blocks; remove them by hand. The stand-in the client
sees is a barrier (solid) or structure void (walk-through), so mining is driven by the server and should be checked in
a client.
