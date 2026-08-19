# Pocket Dungeons — Handoff to Opus

## What you're being asked to do

Flesh out `FEATURE_PROPOSAL.md` (provided alongside this handoff) into an implementation-ready plan for the next player-visible update of Pocket Dungeons. The feature proposal is a high-level outline — your job is to turn it into something an agent can execute milestone by milestone, at the specificity level of `PLAN.md` (which you should read in full — it's in `pocketdungeons/PLAN.md`).

---

## What Pocket Dungeons is

A server-side Fabric 1.21.5 mod (`pocketdungeons`) that creates on-demand, private dungeon instances in a custom void dimension (`pocketdungeons:void`). A player runs `/dungeon`, gets teleported into a sealed 16×16-cell room grid, fights through it, and exits via a lodestone pad or `/dungeon exit`. Death inside the dungeon is non-lethal — a killing blow ejects the player with inventory intact. Parties of up to 6 (config) are supported via `/dungeon invite` + `/dungeon join`.

**Current state:** a single fixed four-room dungeon:
```
[entrance/skeleton] -- [zombie encounter] -- [loot vault (1 diamond)] -- [exit pad]
```

The pipeline is proven end-to-end: dimension allocation, slot grid, force-loading, template stamping (real `.nbt` via `StructureTemplateManager`), jigsaw door processing, teleport lifecycle (`TeleportTransition`), party system, death rescue, disconnect recovery, teardown. Config is live. Room manifest (M2) is shipped — it loads `dungeon_room/*.json`, validates jigsaw doors against the 16×16 cell contract, derives door masks, precomputes rotations, and indexes rooms for query.

**What doesn't exist yet:** procedural layout, room variety, difficulty scaling, loot beyond a single diamond, any form of progression or persistence, any in-world entry point besides a chat command.

---

## Suite context (read `docs/DESIGN.md` for full version)

- **Currency-only coupling.** Mods never depend on each other in Java. They share diamonds/cobblestone as physical items. `cobbleeconomy` is the hub.
- **Tap-forward.** Play testing showed sinks out-running faucets. The mandate is: be generous, err on the side of paying out. Pocket Dungeons is a **faucet**.
- **Server-side only.** Vanilla clients, no GUI, no custom blocks/items unless absolutely necessary.
- **Config is the integration layer.** Cross-mod data goes through JSON, not Java imports.

---

## Codebase orientation

All source is under `pocketdungeons/src/main/java/pocketdungeons/`. Key files:

### Core pipeline (shipped, do not redesign)
- **`Instances.java`** — Slot allocation, party membership, entry/exit, death rescue, disconnect handling, teardown, tick watcher. The central lifecycle manager. ~700 lines.
- **`StaticLayout.java`** — The M0 four-room fallback. Delegates to `TemplateStamper.stamp()`. Provides `entrance()`, `exitPad()`, `bounds()`. The procedural planner replaces its role as the default but it must remain as a fallback (PLAN.md §7.2 step 8).
- **`TemplateStamper.java`** — Loads `.nbt` templates via `StructureTemplateManager`, places them with jigsaw processing + fallback scan. This is what writes rooms into the world.
- **`RoomBuilder.java`** — Block-by-block geometry helper used by `RoomTemplateGenerator`. Still used for `cellCentre()`.
- **`RoomTemplateGenerator.java`** — Admin command (`/dungeon admin gentemplates`) that programmatically builds room templates into a scratch region and saves them as `.nbt`. The pipeline for authoring new rooms without a Minecraft client.

### Planner scaffolding (exists, needs integration)
- **`LayoutGraphGenerator.java`** — Pure-JDK. Generates `DungeonShape` from a seed: self-avoiding critical path (configurable length), branch spurs, loop edges, role assignment. Has `validate()` and a `main()` test harness. ~425 lines, fully implemented.
- **`RoomSelector.java`** — Resolves a `DungeonShape` into a `DungeonPlan` by querying `RoomManifest` for matching templates. Has `validate()` (reachability, budget). Currently picks deterministically (alphabetical first match) — needs seeded weighted random.
- **`DungeonPlan.java`** — Record: seed, cells, roles, placed rooms (name + rotation), doors, entrance, terminal, critical path, gate.
- **`DungeonShape.java`** — Record: seed, cells, open edges, entrance, terminal, critical path, roles.
- **`PlanCell.java`** — Record: `(x, z)` grid coords. Has `neighbor(Direction)` and `directionTo(PlanCell)`.
- **`PlanEdge.java`** — Record: canonical ordered pair of `PlanCell`s. Has `touches()`, `other()`.
- **`PlanRenderer.java`** — ASCII renderer for plans and failures. Used by `/dungeon admin plan <seed>`.

### Manifest and metadata (shipped)
- **`RoomManifest.java`** — Loads `dungeon_room/*.json`, validates templates' jigsaw doors, builds door mask index. Key method: `queryAnyRotation(int requiredMask, String role)` returns `List<Match>` (entry + rotation). ~272 lines.
- **`DungeonRoomMeta.java`** — Parsed from JSON: template id, footprint, roles, weight, minDepth, maxPerDungeon, processors.
- **`DoorMask.java`** — Pure bit math. 4-bit mask (N=0x1, E=0x2, S=0x4, W=0x8). `rotateClockwise()`, `fromEdges()`, `hasEdge()`, `toLetters()`. Has its own `Direction` enum independent of Minecraft.
- **`RoomGeometry.java`** — Constants: `CELL=16`, `WALL_HEIGHT=5`, `CEILING_Y=6`, `DOOR_MIN=7`, `DOOR_MAX=8`, `DOOR_HEIGHT=3`. `wallDirection(x, z)` helper.

### Config
- **`PocketDungeonsConfig.java`** — `readOrCreate` contract. Currently: `slotPitch`, `slotsPerRow`, `watchIntervalTicks`, `voidGuardDepth`, `maxPartyMembers`, `inviteTtlSeconds`. New fields will be needed for path length, key item, payout, etc.

### Data files
```
data/pocketdungeons/
├── dimension/void.json
├── dimension_type/void.json
├── dungeon_room/
│   ├── encounter_zombie.json    (roles: ["encounter"], WE mask)
│   ├── entrance_hall.json       (roles: ["entrance"], E mask)
│   ├── exit_hall.json           (roles: ["exit"], W mask)
│   └── loot_vault.json          (roles: ["loot"], WE mask)
├── loot_table/chests/
│   └── tier_1.json              (1 guaranteed diamond — placeholder)
└── structure/rooms/
    ├── encounter_zombie.nbt
    ├── entrance_hall.nbt
    ├── exit_hall.nbt
    ├── jig_room.nbt             (authoring reference, all 4 doors)
    └── loot_vault.nbt
```

Room JSON shape (example):
```json
{
  "template": "pocketdungeons:rooms/encounter_zombie",
  "footprint": [1, 1],
  "roles": ["encounter"],
  "weight": 1,
  "minDepth": 0,
  "maxPerDungeon": -1
}
```

---

## What the feature proposal says (summary — read `FEATURE_PROPOSAL.md` in full)

Five features, three in the first shippable update:

1. **Seeded procedural layout** — 5–8 room critical path + 0–2 spurs. `LayoutGraphGenerator` exists; glue it into `Instances.enter()` with retry + `StaticLayout` fallback.
2. **Room library expansion to ~8–10 templates** — junction (NESW), corner, corridor, second encounter, spawner den (vanilla mob spawner in template). Via `RoomTemplateGenerator`.
3. **Tiered loot + scaled mobs** — `tier_1`/`tier_2`/`tier_3` loot tables (datapack JSON). Mob count scales with path length + party size. Boss stones for kamutotems in tier_2/tier_3 tables.

Fast follows (features 4–5):
4. **Entry ritual** — lodestone + echo_shard right-click via `UseBlockCallback`.
5. **Dungeon log** — per-player `SavedData` (runs, streak, best depth), `/dungeon log`, streak-multiplied payout.

---

## Specific design decisions already made

### Mob spawners
The owner wants a **vanilla dungeon feel**. Encounter rooms keep pre-spawned mobs (scalable count), but a new **spawner den** room variant uses a real `minecraft:spawner` block authored into the `.nbt` template. `maxPerDungeon: 1` on the spawner den prevents AFK farming in force-loaded instances. Teardown's entity purge handles any extra spawns.

### Kamutotems integration
The owner's kamutotems mod has a `BossStone` system — any vanilla item with `custom_data = {"kamutotems": {"boss_stone": <tier>}}` can be right-clicked to receive a rolled sigil → boss fight. This is the documented cross-mod integration point (see `kamutotems/fabric/src/main/java/kamutotems/BossStone.java`).

Pocket Dungeons drops boss stones from tier_2/tier_3 loot tables via the vanilla `set_custom_data` loot function. **Pure datapack JSON, zero Java dependency.** If kamutotems is absent, the stone is a harmless named vanilla item.

**In-dungeon boss fights are deferred.** `Boss.spawn`/`BossHost.track` would be a hard Java dep, boss bar and sigil-refund logic won't survive `Instances` purge/teardown, and kamutotems' open `DEATH_PROTECTION` risk conflicts with non-lethal death rescue.

### Echo shard collision
The entry ritual defaults to `minecraft:echo_shard` as its key item. Kamutotems sigils are *also* echo shards (with `custom_data`). The ritual listener **must only consume shards without kamutotems custom_data** — check for this explicitly, or use a different default key item. Flag this in the plan.

### Gate/key system
`DungeonPlan.Gate` scaffolding exists but is **skipped for v1**. No lock/key content exists yet, and it adds a stuck-player failure mode. Defer until there's a key item worth holding.

### Loops
`LayoutGraphGenerator` generates loop edges, but loops require junction templates (3+ door mask) that don't exist yet. The retry/fallback handles this correctly. The proposal says keep generating them but accept frequent failure as a test of the fallback path.

---

## What you should produce

A detailed implementation plan covering:

1. **Milestone breakdown** — which features go in which milestone, implementation order, what blocks what.
2. **Per-feature specification** — at PLAN.md's level of detail:
   - Exact classes to create or modify, method signatures where non-obvious.
   - Data files to create (room JSONs, loot table JSONs with actual item pools).
   - Config fields to add.
   - How each feature integrates with the existing pipeline.
3. **New room templates** — specify what each new room contains (block layout, mob types/counts, spawner config, chest placement, door positions), roles, door masks. Keep to the 16×16×7 cell contract.
4. **Loot table design** — actual item pools for tier_1 (improve from 1 diamond), tier_2, tier_3. Include boss stone entries with `set_custom_data`. Be generous per DESIGN.md §5.
5. **Difficulty curve** — concrete formulas for mob count, mob type selection, and loot tier as functions of path length and party size.
6. **Integration points** — how `Instances.enter()` changes, how `StaticLayout` becomes the fallback, how `forceLoad` adapts to variable layout size, how `clear`/`bounds`/`exitPad` adapt.
7. **Verification plan** — headless checks per milestone, matching this project's discipline.

### Constraints
- Server-side only, vanilla clients.
- 16×16 cell contract with jigsaw door slots (`RoomGeometry`, `RoomManifest`).
- Party size capped by config — do not redesign.
- Death rescue stays non-lethal.
- No new dimensions.
- No hard Java dependency on kamutotems.
- `giveOrDrop` pattern for any item rewards (add to inventory, drop at feet if full).
- Follow the existing code style — package-private classes, records for data, minimal Minecraft imports in pure-logic classes.
