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

---

## 7. Spawn-egg sigils (boss fight tokens)

A rolled sigil is a vanilla `SpawnEggItem` stack carrying Kamu Totems roll data under `minecraft:custom_data`. The entity type that spawns is the egg's own bound entity type; the NBT only carries the kamu/tier affix layer.

### NBT shape

```json
{
  "minecraft:custom_data": {
    "kamutotems": {
      "sigil": 2,
      "seed": 12345,
      "counter": 0,
      "rolled": true
    }
  }
}
```

- `sigil`: integer tier, 1–4. Required to identify it as a sigil.
- `seed`: long used to derive the kamu affix roll.
- `counter`: integer used with `seed` to keep rolls unique per player.
- `rolled`: boolean; if `true` the sigil is ready to summon. If absent or `false`, the sigil is sealed and will be rolled automatically during the next inventory sweep.

The mob that spawns is determined solely by the base `SpawnEggItem` (for example `minecraft:skeleton_spawn_egg`). If you want a specific boss mob, choose that spawn egg; the roll data does not override the entity type.

### Random mob from the pool

Sigils that are rolled automatically (shop sales, boss stones, daily rewards,
refunds, quest rewards) pick their spawn egg from `boss.mob_pool` using an
independent random seed. They are no longer forced to a single mob. The
resolved mob's display name is written as the first line of the item's lore,
followed by the kamu affixes:

```
Skeleton
Chilled
Vampiric
```

### How to grant one from another mod

Set the base item to a spawn egg and attach the `minecraft:custom_data` blob. The tier controls difficulty; the `seed`/`counter` should be unique per issuance. Example loot-table function:

```json
{
  "type": "minecraft:item",
  "name": "minecraft:skeleton_spawn_egg",
  "functions": [
    {
      "function": "minecraft:set_components",
      "components": {
        "minecraft:custom_data": {
          "kamutotems": {
            "sigil": 2,
            "seed": 12345,
            "counter": 0,
            "rolled": true
          }
        },
        "minecraft:item_name": "Sigil of the Second Trial",
        "minecraft:max_stack_size": 1
      }
    }
  ]
}
```

Kamu Totems computes the kamu list from the seed and refreshes the lore automatically when the item is rolled or used.

### Interactions

- Right-click in the air or on a non-usable block: summons the boss.
- Right-click on a usable block (chest, crafting table, dispenser, etc.): opens the block's menu; the sigil is **not** consumed.
- **Dispenser:** a rolled sigil dispensed from a dispenser summons the boss in front of the dispenser. This ignores the normal `no_summon_radius` check because there is no player position to validate.
- `/boss daily`: grants a free sealed sigil that is rolled automatically and uses a mob from the configured `boss.mob_pool`.
- `/boss summon <tier>`: for operators, finds a rolled sigil in the player's inventory and summons it.
- Legacy `minecraft:echo_shard` sigils are migrated to real spawn eggs on first inventory sweep or manual right-click.

### Imbuing a held spawn egg

A player or operator can hold any vanilla spawn egg and run:

```
/kamu egg imbue 2
```

The held egg keeps its bound `EntityType` and gains a random Trial II kamu
affix layer, becoming a rolled sigil immediately. This is a convenience
command; it does not go through the shop or quest reward path.

### Mystery random-sigil eggs (shop-friendly)

For shops, you can sell a sealed "mystery egg" that only reveals its mob when
the player opens it. Give the player an item with this tag:

```json
{
  "type": "minecraft:item",
  "name": "minecraft:egg",
  "functions": [
    {
      "function": "minecraft:set_components",
      "components": {
        "minecraft:custom_data": {
          "kamutotems": {
            "random_sigil": 2
          }
        },
        "minecraft:item_name": "Mystery Sigil Egg — Trial II",
        "minecraft:max_stack_size": 1
      }
    }
  ]
}
```

- `random_sigil` is the trial tier, 1–4.
- The item is a plain `minecraft:egg` (or any vanilla item) until the player
  right-clicks it.
- Right-click consumes the item and gives the player a rolled sigil of that tier
  with a random mob from `boss.mob_pool`.
- Usable blocks are not opened; the egg is always consumed on right-click.

Operators can also give one directly with:

```
/kamu egg random 2
```

### Reading a sigil from your own mod

No code dependency is required. Check the item stack:

- It must be a `SpawnEggItem` (or a legacy `minecraft:echo_shard` with the tag).
- `minecraft:custom_data.kamutotems.sigil` exists and is greater than `0`.
- `minecraft:custom_data.kamutotems.rolled` is `true` for a ready sigil, absent or `false` for a sealed one.

The `kamu` list inside the tag is informative; if it is missing, Kamu Totems recomputes it from `seed`, `counter`, and `sigil` using its own catalog.

### Config

Server operators control which spawn eggs can be rolled through `config/kamutotems/config.json`:

```json
{
  "boss": {
    "mob_pool": ["minecraft:zombie", "minecraft:skeleton", "minecraft:spider"]
  }
}
```

This only affects sigils rolled automatically (boss stones, daily reward, sealed sigils). A mod that hands out a specific spawn egg with the NBT always gets that exact mob.

### Boss death loot tables

Each tiered boss drops from a configurable extra loot table on death, in
addition to its normal mob drops and the guaranteed tier-1 kamu. The config
keys are:

| Tier | Key | Default |
|---|---|---|
| I | `boss.tier_1_loot_table` | `kamutotems:entities/boss_tier_1` |
| II | `boss.tier_2_loot_table` | `kamutotems:entities/boss_tier_2` |
| III | `boss.tier_3_loot_table` | `kamutotems:entities/boss_tier_3` |
| IV | `boss.tier_4_loot_table` | `kamutotems:entities/boss_tier_4` |

Set a key to `""` to disable extra loot for that tier. Server operators or
companion data packs can override the default JSONs at
`data/kamutotems/loot_table/entities/`.
