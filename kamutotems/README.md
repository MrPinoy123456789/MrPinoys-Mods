# MrPinoy's Kamu Totems

Collect **kamu** from bosses, imbue them into a named **Kamuy** that lives in a
rechargeable totem, and discover what they do together.

> **Status:** implemented and build-verified 2026-08-13, **not play-verified.**
> See [SPEC.md](SPEC.md) for the design and [PLAN.md](PLAN.md) for how it was
> built and what is still open.

- Minecraft **26.2**, Fabric Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"` — **vanilla clients install nothing**
- **Zero Mixins**, zero compile-time dependencies on any other mod

---

## The loop

```
   boss drops a kamu it was carrying  ──▶  kamu is imbued into your totem
            ▲                                          │
            │                                          ▼
   diamonds buy more boss attempts        your Kamuy makes you strong enough
            ▲                                   for the next boss
            └──────────  dying burns totem charges  ◀──────────┘
```

**Kamu** are loot — they drop, they trade, they fuse. Your **Kamuy** is the named
power in your totem: one per player, unique to you, and it remembers what it did.

## Three hosts, one engine

| Host | What it does |
|---|---|
| **Totem** | Offhand item. Modifies *whatever* attack you make — sword, bow, fists, a steak. Also a rechargeable totem of undying |
| **Boss** | Summonable elite carrying random kamu, named on its boss bar. Drops one of them |
| **Quest** | Three light daily segments — kill, turn in, scan — with a streak |

## Commands

**Everything lives in one block: right-click any fletching table.** That opens
the Kamu Station — Carving, Fusion, Discoveries, Naming, Journal and Quests,
with a back button on every panel.

| Command | Does |
|---|---|
| `/totem` | Open the carving panel directly |
| `/kamu book` | The discovery book |
| `/kamu forge` | The Kamu Forge (fusion) |
| `/quests` | Errands other mods have given you |
| `/totem name <text>` | Name or rename your Kamuy |
| `/kamuy` | Your Kamuy's journal |
| `/daily` · `/daily top` | Today's chain and progress; the streak leaderboard |
| `/boss summon <tier>` | Consumes a sigil |
| `/kamutotems give <targets> <kamu>` | Admin |
| `/kamutotems reload` · `cleanup` | Admin |

## Rules worth knowing

- **At zero charges the totem goes dormant** — no save, no attack modification,
  but **your kamu are never lost** and you get no penalty. A dormant totem makes
  you exactly a vanilla player, never worse than one.
- Charges burn on **death only**, never on ability use. A vanilla anvil restores
  **4 charges per diamond** (16 max) — no code, just vanilla repair.
- **Imbuing and removing a kamu costs cobblestone**, scaled by tier. Removing
  returns the kamu intact — you are paying to change your mind.
- **Fusion is the only way up a tier.** Two identical kamu of the same tier make
  one of the next: `t1 + t1 = t2`, `t2 + t2 = t3`. A tier 3 costs four drops.
- **Order matters.** `fire → frost` and `frost → fire` are not the same thing.

### Handling kamu

A kamu is always in one of three states:

```
   item  --right-click-->  bound  --click in panel-->  slotted
         <--shift-click--         <--click in panel--
```

**Right-click holding a kamu** to bind it to your Kamuy — this works anywhere,
so you can pocket loot without walking home. Then visit a **fletching table**
and choose *Carving* to place bound kamu into slots. Slots are typed — Action,
Modifier, Modifier, and an optional **When** — and a kamu only goes where it
fits. Empty slots hold a harmless default, so the totem always reads as a
sentence.

**Fusion** is the *Fusion* entry at the station: two identical kamu of the same
tier make one of the next. It works on the item form, before binding.

Two things deliberately still work in the field, with no station needed:
**binding a kamu** and **using a sigil** to summon a boss.

---

## Shop integration

Paste into `cobbleeconomy`'s `shop.json`. **These three listings are the only
thing this mod sells.**

> **No kamu and no totem are ever purchasable.** Everything that makes a player
> stronger is earned off a boss's corpse; diamonds buy only the *opportunity* to
> try. If a player can buy a kamu, the boss loop is decorative and the suite's
> one collection mechanic becomes a price list.

A sigil arrives **sealed** — the kamu its boss carries are unknown until it is
rolled, which this mod does within one sweep tick of it entering an inventory,
rewriting the item's name and lore in place. The shop cannot roll it: a
per-purchase random roll is not expressible in a static `components` block, and
coupling `cobbleeconomy` to this mod is forbidden by
[DESIGN.md §3](../DESIGN.md).

```json
"sigil_ii": {
  "item": "minecraft:echo_shard",
  "quantity": 1, "price": 6, "currency": "diamond", "category": "Kamu Totems",
  "components": {
    "minecraft:custom_data": { "kamutotems": { "sigil": 2, "rolled": false } },
    "minecraft:item_name": "Sealed Sigil — Second Trial",
    "minecraft:lore": ["The spirits within are not yet known."]
  }
},
"sigil_iii": {
  "item": "minecraft:echo_shard",
  "quantity": 1, "price": 14, "currency": "diamond", "category": "Kamu Totems",
  "components": {
    "minecraft:custom_data": { "kamutotems": { "sigil": 3, "rolled": false } },
    "minecraft:item_name": "Sealed Sigil — Third Trial",
    "minecraft:lore": ["The spirits within are not yet known."]
  }
},
"sigil_iv": {
  "item": "minecraft:echo_shard",
  "quantity": 1, "price": 28, "currency": "diamond", "category": "Kamu Totems",
  "components": {
    "minecraft:custom_data": { "kamutotems": { "sigil": 4, "rolled": false } },
    "minecraft:item_name": "Sealed Sigil — Fourth Trial",
    "minecraft:lore": ["The spirits within are not yet known."]
  }
}
```

**Tier I is not sold** — it is the reward for finishing the daily quest chain,
handed over as a Sigil of the First Trial. It is derived from the date, so it is
the same boss for everyone that day, and worth talking about. That is why the
shop starts at the Second Trial.

---

## Config

Everything lives in `config/kamutotems/`, generated on first boot.

| File | Contains | If it fails to parse |
|---|---|---|
| `config.json` | All tuning: costs, charges, boss stats, quest rewards | Defaults in memory, **file untouched** |
| `components.json` | 15 collectible kamu + 2 baseline defaults | **The server refuses to start** |
| `reactions.json` | The 6 reactions, order-significant | Built-in table, **file untouched** |
| `daily_state.json`, `boss_state.json`, `kamuy/*.dat` | Player and world state | Renamed `.corrupt`, skipped, server still boots |

**The asymmetry between the two content files is deliberate.** A broken
reaction table costs nothing — reactions keep working from the built-in set. A
broken *catalog* would make every slotted kamu resolve to "unknown", and the
next totem write would erase every player's Kamuy. Refusing to boot is the
recoverable failure; booting is not.

### The numbers most likely to need tuning

```jsonc
"totem": {
  "imbue_t1": 32,  "imbue_t2": 96,  "imbue_t3": 256,   // cobblestone
  "remove_t1": 16, "remove_t2": 48, "remove_t3": 128,
  "tier_multiplier_1": 1.0, "tier_multiplier_2": 1.6, "tier_multiplier_3": 2.5
}
```

> **If in doubt, cut the cobblestone costs in half.** The failure that matters is
> not "players spend too little cobble" — it is **a player who stops
> experimenting because rearranging feels expensive**, which strangles the
> discovery mechanic the whole mod is built on.

---

## For other mod authors

Kamu Totems has an outward API made of **items, not Java**. Another mod can hand
a player a quest scroll or a boss stone by giving them a vanilla item with the
right `custom_data` — no dependency, no api module, no load order. See
[INTEGRATION.md](INTEGRATION.md).

---

## Two mods retire into this one

`cobblebending` and `dailyquests` are **still installed and untouched.** Neither
is deleted until its replacement here is play-verified — see [SPEC.md §20](SPEC.md).

### ⚠ `dailyquests` and the quest host cannot both run

**Both register `/daily`.** Brigadier does not reject a duplicate root — it
*merges* it. With both mods installed, `/daily`, `/daily turnin` and
`/daily top` silently resolve to whichever mod loaded second. No crash, no log
line, just the wrong quest system answering and two unrelated streaks.

So the quest host is **off by default**:

```jsonc
"quest": { "enabled": false }
```

| You want | Do this |
|---|---|
| **Now** — totem + boss, daily loop unchanged | Leave `enabled: false`. Keep `dailyquests` installed. This is SPEC §20 phases 1–2 |
| **Later** — switch the daily loop over | Set `enabled: true` **and delete `dailyquests` in the same restart** |

Never both. The startup log states which mode it is in.

`cobblebending` has no such conflict — it shares no command and no item with this
mod, so it can retire whenever its replacement constructs are ready.

> **When `cobblebending` retires, the cobblestone imbue/remove costs must already
> be live.** That mod is the suite's only cobblestone sink; these costs are what
> replace it. Ship the retirement without them and the common currency is
> worthless for however long the gap lasts.
