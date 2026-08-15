# Kamu Totems — Outward Item API

> **Do not add a dependency on `kamutotems`.** The API is an item id plus `minecraft:custom_data`. Both mods stay strangers; no Java imports, no API module, no mixin.

This document is for authors of other server-side mods (for example, an NPC mod like `wayfarers`) who want to hand a player a quest or a boss fight. Everything below works with a vanilla client and requires nothing from the player.

---

## 1. How it works

Kamu Totems watches for vanilla items carrying a specific `minecraft:custom_data` blob. When a player right-clicks one, the mod reads the blob, validates it, and does the rest. Your mod only needs to create the item stack.

Supported vanilla host items: any normal `Item`. Paper, echo shards, name tags, books, and so on all work. Choose whichever fits your mod's fiction.

---

## 2. Quest scroll

Shape of `minecraft:custom_data`:

```json
{
  "kamutotems": {
    "quest_scroll": "wayfarer_lost_cargo"
  }
}
```

- `quest_scroll` is the id of a quest defined in `config/kamutotems/quests.json`.
- The item is consumed only if the quest is granted.
- Refusals consume nothing and tell the player why.

### Refusal conditions

- The player already has as many active errands as the cap allows.
- The quest id is unknown.
- The quest is already active and is not repeatable.
- The quest expired before the scroll was read.

### What a quest looks like

A quest has three segments, in order. Each segment is one of:

- `kill` — kill a specific entity (e.g., `minecraft:skeleton`).
- `turn_in` — hand in a specific item with `/quests turnin`.
- `scan` — sneak + right-click the matching block with a Kamu Totem.

The player uses `/quests` to see active errands, `/quests progress <#>` for details, and `/quests turnin` to hand in items.

### Reward kinds

Quests may reward:

- `"diamond"` — plain diamonds.
- `"cobblestone"` — plain cobblestone.
- `"sigil"` — a sealed sigil; `amount` is the tier (1–4).
- `"item"` — any registered item by id; `itemId` is required.

**A quest reward may never grant a kamu.** Kamu are boss-drop only. If you want to reward power, grant a sigil.

---

## 3. Boss stone

A boss stone skips the quest and hands over a working, already-rolled sigil.

Shape of `minecraft:custom_data`:

```json
{
  "kamutotems": {
    "boss_stone": 2
  }
}
```

- `boss_stone` is the trial tier, 1–4.
- Tier 1 is the same trial players earn for free from the daily chain.
- The stone is consumed and replaced by a rolled sigil of that tier.
- If the inventory is full, the sigil drops at the player's feet.

---

## 4. Worked example: a `wayfarers` loot table

Suppose `wayfarers` wants a rescued merchant to sometimes hand the player a scroll for `wayfarer_lost_cargo`. The loot table entry is just a vanilla item stack with `components`:

```json
{
  "type": "minecraft:item",
  "name": "minecraft:paper",
  "functions": [
    {
      "function": "minecraft:set_name",
      "name": "A Crumpled Waybill"
    },
    {
      "function": "minecraft:set_components",
      "components": {
        "minecraft:custom_data": {
          "kamutotems": {
            "quest_scroll": "wayfarer_lost_cargo"
          }
        },
        "minecraft:lore": [
          "A wayfarer asked you to settle this."
        ]
      }
    }
  ]
}
```

Or, if an NPC trades a boss fight directly:

```json
{
  "type": "minecraft:item",
  "name": "minecraft:echo_shard",
  "functions": [
    {
      "function": "minecraft:set_name",
      "name": "Etched Trial Stone"
    },
    {
      "function": "minecraft:set_components",
      "components": {
        "minecraft:custom_data": {
          "kamutotems": {
            "boss_stone": 2
          }
        },
        "minecraft:lore": [
          "Right-click to receive a sealed Second Trial sigil."
        ]
      }
    }
  ]
}
```

No code dependency. No mixin. No client requirement.

---

## 5. For players: `/quests` commands

The host registers these commands automatically:

| Command | What it does |
|---|---|
| `/quests` | List active errands. |
| `/quests progress <#>` | Show segment progress and rewards for the errand at that list position. |
| `/quests turnin` | Hand over items for the first active turn-in segment you can satisfy. |
| `/quests abandon <#>` | Abandon the active errand at that list position. |
| `/quests help` | Show the command list. |

---

## 6. Adding new quests

Server operators can add quests by editing `config/kamutotems/quests.json` and reloading, or by placing a file with the same shape in that folder. A malformed file falls back to the built-in defaults and is left on disk for the operator to fix.

Quest ids used in your mod's items must match ids in that file.
