# Foundry examples

Copy `content/` into `config/foundry/content/` and `assets/` into `config/foundry/assets/` to try these on a server.

## Items (`content/demo/items/`)

- `crystal_sword.json`: diamond sword base with durability, rarity, glint and an `on_use` speed mechanic.
- `glow_gem.json`: flat `texture` item; Foundry generates the model and client item definition.
- `wizard_staff.json`: hand-written `model` (`assets/demo/models/item/wizard_staff.json`) referencing an authored
  texture, plus a matching PNG.

## Recipes (`content/demo/recipes/`)

- `glow_gem.json`: shapeless, three amethyst shards and a glow ink sac.
- `wizard_staff.json`: shaped, a glow gem over two sticks.

## Assets (`assets/demo/`)

- `textures/item/glow_gem.png`, `textures/item/wizard_staff.png`: 16 by 16 PNGs.
- `models/item/wizard_staff.json`: a hand-written item model; its texture reference is validated at load.
