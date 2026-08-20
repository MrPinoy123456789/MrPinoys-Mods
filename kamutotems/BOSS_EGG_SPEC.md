# Boss Spawn Egg — Spec

Status: implemented.

## 1. Problem with the current sigil

Today a rolled sigil is `Items.ECHO_SHARD` plus a `minecraft:custom_data` tag
(`Sigil.java`). Right-click is caught by a global `UseItemCallback` and always
spawns the single `EntityType` configured at `boss.mob` (`Boss.java:109-114`,
default `minecraft:zombie`). Two things fall out of that:

- **No mob variety.** Every boss, at every tier, is whatever one mob the
  server operator picked. Tier only changes numbers, never the fight's shape.
- **No dispenser/vanilla-egg interop.** `Items.ECHO_SHARD` has no dispense
  behavior and isn't `instanceof SpawnEggItem`, so other mods' "is this a
  spawn egg" checks (and vanilla's own, e.g. `Mob`'s spawn-egg breeding hook)
  never see it. Discussed and ruled out as unfixable without changing the
  base item — see chat history.

## 2. Goal

A rolled sigil becomes **a real spawn egg item**, carrying the same rolled
payload it does today. At summon time:

1. The mob that spawns is **whatever `EntityType` that specific spawn egg is
   bound to** — not a global config value.
2. The kamu roll (tier, seed, kamu list) is then **applied on top** as an
   affix layer: HP/damage/armour/knockback scaling, aura, boss-bar name,
   target-lock. Exactly the stat-scaling block already in `Boss.spawn`
   (`Boss.java:137-182`), just decoupled from a single hardcoded mob.

This means a tier-III sigil minted from a skeleton egg and one minted from a
zombie egg are both legitimate tier-III bosses with the same numbers, but a
different base moveset/resistances/hitbox. It also means **any**
`SpawnEggItem` — vanilla or from another mod — becomes a valid carrier the
moment it's stamped with our tag, which is what buys the interop the user
asked for.

## 3. Item shape

Unchanged tag shape, different base item:

```
minecraft:custom_data = {
  kamutotems: {
    sigil: <tier:int>,
    rolled: <bool>,
    seed: <long>,
    counter: <int>,
    kamu: [<string>, ...]
  }
}
```

Base `ItemStack` is no longer always `Items.ECHO_SHARD`. It's any
`ItemStack` whose `getItem()` is `instanceof SpawnEggItem` (vanilla eggs,
modded eggs — anything that resolves an `EntityType`). The mod does not need
its own egg item; it decorates existing ones.

`Sigil.isSigil` / `isRolled` / `needsRoll` / `tier` / `seed` / `counter` /
`rollFrom` stay exactly as they are today — they only read the tag, never the
base item.

## 4. Minting

Every place that currently does `new ItemStack(Items.ECHO_SHARD, 1)` picks a
spawn egg item instead:

- `Sigil.makeRolledSigil` (`Sigil.java:172-176`)
- `Sigil.makeFirstTrial` (`Sigil.java:187-199`)
- `BossStone.onUseItem` — via `Sigil.makeRolledSigil` (`BossStone.java:84`)

**Which egg to use** is config-driven, replacing the single `boss.mob`
string with a pool:

```
boss.mob_pool = ["minecraft:zombie", "minecraft:skeleton", "minecraft:spider", ...]
```

Pick one uniformly (or weighted) using the same roll `seed` so the choice is
deterministic and reproducible from the tag alone — no new field needed.
Resolve the entry to its vanilla spawn egg item
(`BuiltInRegistries.ITEM.getOptional(Identifier.parse(entityId + "_spawn_egg"))`,
falling back to the current `zombie` default) at mint time, and construct the
`ItemStack` from that item instead of `ECHO_SHARD`.

`mob_pool` only governs what *this mod's own* shop/quest/daily paths mint —
it is consulted nowhere else. It is not a validation gate: `Sigil.onUseItem`
(§5) accepts any `instanceof SpawnEggItem` stack carrying our tag,
regardless of entity type. A different mod pre-stamping its own eggs (a
`BossStone`-style exchange, an admin command, another mod's reward system)
picks its own `EntityType` directly and never touches this config.

`BossHost.summon` (the `/boss summon` command path, `BossHost.java:245-269`)
mints via the same `Sigil.makeRolledSigil`-adjacent path — no separate change
needed there since it operates on an already-rolled stack, not a fresh mint.

## 5. Use-time resolution

`Sigil.onUseItem` (`Sigil.java:201-256`) changes its entry guard from
"is this an echo shard with our tag" to "is `stack.getItem() instanceof
SpawnEggItem` and `isRolled(stack)`". Everything else in that method —
radius check, active-boss check, roll lookup, message text — is unchanged.

The one real change is inside `Boss.spawn`: instead of reading `boss.mob`
from config, it resolves the `EntityType` **from the item in hand**:

```
EntityType<?> type = SpawnEggItem-equivalent-accessor(heldStack)
        .orElse(EntityTypes.ZOMBIE);
```

(Exact accessor name needs verifying against this mapping set/version before
implementation — vanilla exposes a way to read the bound `EntityType` off a
spawn-egg `ItemStack`; comments elsewhere in this codebase note verifying
such APIs against the merged jar, e.g. `Boss.java:123` and `Boss.java:191`.)

`Boss.spawn`'s signature grows one parameter (the held `ItemStack`, or just
the resolved `EntityType`) so it no longer needs `KamuTotemsConfig` for the
mob id at all in the sigil path. The `/boss summon 1` free-daily path
(`BossHost.java:223-243`) still has no item in hand — it keeps using
`boss.mob_pool` (seeded off the date key, same determinism argument as §4)
since there's nothing to read a type from.

The stat-scaling block (`Boss.java:137-182`) is already written against
`LivingEntity`/`Mob`, not against zombie specifically, so it needs **no
changes** — it already works for any mob type.

## 6. Dispenser support

Because the base item is now a genuine `SpawnEggItem`, vanilla's own
dispenser behavior registration already fires on it — but it runs the
*vanilla* "spawn plain entity" behavior, which knows nothing about our tag,
tier, or ritual gates (radius, one-boss-at-a-time). To get the *ritual*
version from a dispenser, register a custom `DispenseItemBehavior` via
`DispenserBlock.registerBehavior` that:

1. Checks `Sigil.isRolled(stack)`.
2. If true, runs the same summon path as `Sigil.onUseItem` (minus the
   `Player`-specific messaging — dispensers have no player to check radius
   against, so this needs a stance: either skip the radius check when fired
   from a dispenser, or require a nearby tracked owner. **Open question**,
   see §9.)
3. If false (a plain, un-rolled spawn egg of that type), fall through to
   vanilla behavior so ordinary eggs of that entity type are unaffected.

This must be registered per vanilla egg item actually used in `mob_pool`
(and, for full "other mods' eggs work too" interop, ideally hook every
registered `SpawnEggItem` at startup rather than an explicit list).

## 7. Vanilla side effect: mob-breeding hook

`Mob.checkAndHandleImportantInteractions` fires `SpawnEggItem
.spawnOffspringFromSpawnEgg` whenever the held item is `instanceof
SpawnEggItem`, on right-click of **any** living mob — including a rolled
sigil. This is vanilla code we can't override from an `Item` subclass; it
runs on the `Mob` side before/around our `UseItemCallback`. Net effect:
right-clicking a rolled boss-egg onto an unrelated cow will attempt vanilla
breeding (a harmless no-op for mismatched types, a real breeding attempt for
a matching type). Accepted as a minor, cosmetic side effect unless play
testing says otherwise.

## 8. Config changes

| Key | Before | After |
|---|---|---|
| `boss.mob` | single entity id string | removed |
| `boss.mob_pool` | — | list of entity ids; egg choice seeded off roll/date |

## 9. Closed questions

- **Dispenser + radius gate.** Resolved: dispensers always allow the summon
  (no player position to check). A rolled sigil dispensed from a dispenser
  runs the ritual through `BossHost.registerDispenserBehaviors` and ignores
  the `no_summon_radius` check; un-rolled or plain eggs fall through to the
  original vanilla dispense behavior.
- **Existing in-the-wild sigils.** Resolved: legacy `ECHO_SHARD` sigils are
  migrated on first inventory sweep (and on manual right-click) to a real
  `SpawnEggItem` base by `Sigil.migrate`, preserving the existing roll tag.

## 10. Non-goals

- No new custom `Item` class. This spec deliberately avoids inventing a mod
  spawn egg — the whole point is riding vanilla's (and other mods')
  existing `SpawnEggItem` instances.
- No change to `BossDrops`, `BossAura`, kamu roll math, or the totem/quest
  hosts.

## 11. Outward API / integration for other mods

The spawn-egg sigil is exposed through vanilla `minecraft:custom_data` on a
vanilla `SpawnEggItem`. Other mods can read or grant Kamu Totems boss fights
without a code dependency. The full format, loot-table examples, and
interaction rules are in `INTEGRATION.md` §7 "Spawn-egg sigils (boss fight
tokens)".

Key points:

- The base item must be a `SpawnEggItem` (or a legacy `minecraft:echo_shard`
  with the same tag, which will be migrated on first use).
- The tag is `minecraft:custom_data.kamutotems` and must contain at least
  `sigil` (tier, 1–4). A `rolled: true` field makes it usable immediately.
- The mob that spawns is the egg's bound `EntityType`; the tag carries the
  tier/seed/counter used to compute the kamu affix layer.
- Right-clicking a usable block (chest, dispenser, crafting table, etc.) opens
  the block's menu instead of summoning.

## 12. Rolling, random mobs, and command imbuing

### Random mob per sigil

Rolled sigils minted by the shop, boss stones, refunds, and quest rewards no
longer pick a single mob from `boss.mob_pool` deterministically. Each sigil
now uses an independent random seed when selecting its spawn-egg base, so
`minecraft:zombie_spawn_egg` is not the only outcome.

### Mob name in the lore

The roll data lore now lists the resolved mob name as its first line, followed
by the kamu affixes. Example for a skeleton egg rolled into a Second Trial
sigil:

```
Skeleton
Chilled
Vampiric
```

This is written by `Sigil.roll` and requires no manual setup.

### `/kamu egg imbue <tier>`

Players (or operators) can hold any vanilla spawn egg and run:

```
/kamu egg imbue 2
```

The held egg keeps its bound `EntityType` and gains a random Trial II kamu
affix layer. It becomes a rolled sigil immediately. This is implemented in
`TotemHost.registerCommands` and uses `Sigil.roll`.

### Per-tier death loot tables

Each boss tier drops an extra loot table on death, in addition to the mob's
normal drops and the guaranteed tier-1 kamu. Config keys:

| Tier | Config key | Default loot table |
|---|---|---|
| I | `boss.tier_1_loot_table` | `kamutotems:entities/boss_tier_1` |
| II | `boss.tier_2_loot_table` | `kamutotems:entities/boss_tier_2` |
| III | `boss.tier_3_loot_table` | `kamutotems:entities/boss_tier_3` |
| IV | `boss.tier_4_loot_table` | `kamutotems:entities/boss_tier_4` |

Set a key to an empty string to disable extra loot for that tier. The default
tables include scaling amounts of iron/gold/emeralds/diamonds plus an
enchanted armour piece:

- Tier I/II: enchanted iron armour (helm/boots for I, chest/legs for II).
- Tier III/IV: enchanted diamond armour (helm/boots for III, chest/legs for IV).
- Tier IV also has a chance for a Mending enchanted book and an enchanted golden
  apple.

The loot table is triggered from `BossHost.onDeath` using
`LivingEntity.dropFromLootTable(...)`.

### `/kamu egg random <tier>` and mystery random-sigil eggs

`/kamu egg random <tier>` gives the player a sealed "mystery egg" item. The
player knows the trial tier, but the mob is hidden until they right-click
the item. Right-clicking consumes the mystery egg and gives a rolled sigil of
that tier with a random mob from `boss.mob_pool`. This is the recommended
shape for shop listings. The tag is `minecraft:custom_data.kamutotems.random_sigil`.
