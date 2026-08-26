> Archived 2026-08-25 (M9 C5): superseded by `UPDATE_PLAN.md` (also archived),
> which expanded this proposal to full specificity, and then by the shipped
> milestones under `plans/`.

# Pocket Dungeons — Lean Feature Proposal (next player-visible update)

Goal: turn the one-off four-room demo into a **repeatable, generous faucet** with the
smallest possible surface. One update = "every run looks different, pays out, and
I can start it from the world." Everything else defers.

---

## Prioritized feature table

| # | Feature | Milestone | Why | Scope | Depends on | Cut/defer |
|---|---------|-----------|-----|-------|------------|-----------|
| 1 | **Seeded procedural layout** (5–8 room path + 0–2 spurs) | M3 | Replayability is the #1 gap; nothing else matters until runs differ | `LayoutGraphGenerator` (new), glue in `Instances`; `DungeonShape`/`PlanCell`/`PlanEdge` exist | M2 manifest (done) | Loops, gate/key edges, footprints > 1×1 |
| 2 | **Room library to ~8–10 templates** | M3 content | Planner is useless with 4 rooms; need junction (NESW), corner (2-door L), 2nd/3rd encounter, empty corridor, **spawner den** (vanilla mob spawner + chest — the classic vanilla-dungeon room, zero Java) | `RoomTemplateGenerator` additions + `dungeon_room/*.json` | Nothing new — pipeline exists | Multi-cell rooms, secret rooms, processors |
| 3 | **Tiered loot + scaled mobs** | M3.5 (new, collapses old "difficulty" work) | Faucet: payout must feel worth repeating; DESIGN.md says err generous | Loot table JSONs (`tier_1..3`), mob count = f(path length, party size) in stamping; ~1 small `DifficultyProfile` class | #1 (length to scale on) | Elaborate curves, per-room hand-tuning, boss (see hook below) |
| 4 | **Entry ritual: lodestone + item sacrifice** | M9→pulled forward | Discovery is zero today; `/dungeon` gates the whole mod behind chat | Server-side use-block listener: right-click a lodestone with a config-defined key item (default: `minecraft:echo_shard`) consumes it and starts a run. No custom blocks/items | None (parallel to #1) | Multiblock ritual, custom key item, in-world portal FX |
| 5 | **Dungeon log + completion payout** | lightest slice of M5/retention | Streak/counter = cheapest retention; payout = economy hook | One small `SavedData` (per-player: runs completed, best depth, current streak); `/dungeon log`; completion drops a config-defined payout stack at the exit pad | #1 finishing cleanly | Leaderboards, cross-server stats, keys/unlock trees |

**First shippable update = #1 + #2 + #3.** #4 and #5 are the fast follow, both small.

---

## Area answers

### Procedural layout (M3)
- **5–8 room critical path**, straight-line-with-turns self-avoiding walk (shrink the spec's 8–12 default — 4 templates worth of variety doesn't sustain 12 rooms yet).
- **0–2 branch spurs** of depth 1, holding bonus loot rooms. No loops in v1 — loops need the junction template *and* buy little at this length.
- **Guarantees:** entrance at cell 0, exit at terminal, ≥1 encounter and ≥1 loot on the path, every cell reachable (already validated in `RoomSelector.validate`).
- Retry-with-seed+1, fall back to `StaticLayout` — keep exactly as PLAN.md describes.
- **Skip the gate/key system for v1** (`DungeonPlan.Gate` exists but adds a stuck-player failure mode; defer until there's a key item worth holding).

### Room selection (M3)
- `RoomSelector` largely exists. Two changes:
  - **Weighted random pick** seeded from `DungeonPlan.seed` instead of deterministic-first-alphabetical (line 55–57) — otherwise every WE cell is the same room.
  - **Honor `maxPerDungeon`** from `DungeonRoomMeta` (avoid three identical zombie rooms in a row).
- Roles stay: `entrance`, `encounter`, `loot`, `exit`. Add `corridor` (filler, no mobs, keeps encounter density from feeling like a slog). That's it.

### Difficulty / reward scaling
- One scalar: **path length** (rolled 5–8). Mobs per encounter = `base + length/3 + partySize-1`. Loot tier: length ≤5 → tier_1, 6–7 → tier_2, 8 → tier_3.
- **Mob sourcing is hybrid for vanilla feel:** standard encounter rooms use pre-spawned mobs (count scalable as above); the *spawner den* room variant uses a real vanilla mob spawner authored into its template (`BaseSpawner` NBT, verified in M1 notes). Spawners self-pace but never gate completion — exit stays the lodestone pad — and teardown's entity purge already handles the extra spawns. Keep spawner dens at `maxPerDungeon: 1` so a private force-loaded instance can't become an AFK farm.
- Tier tables are datapack JSON only — zero Java beyond picking the table id at stamp time. Tables should be *generous* (iron/gold/emeralds/diamond, occasional enchanted book) per the tap-forward mandate.

### Entry ritual / discovery (M9, pulled forward)
- **Lodestone + echo shard**: right-click any lodestone with the configured key item → item consumed → run starts for the player (and nearby party members already invited). Uses `UseBlockCallback`, ~60 lines, no blocks/items/GUI. The lodestone is already the mod's exit-pad motif, so it reads as "the dungeon block."
- `/dungeon` stays as the power-user/admin path. Key item + count are config.
- This makes the mod *discoverable* (villager trades and structure loot already distribute echo shards' recipe surface) and makes each run cost something — a tiny drain attached to a big tap.

### Persistence (M5)
- **Defer the heavy version.** Live instances staying memory-only is fine — the join-handler orphan recovery already covers restarts, and runs are short. What ships instead is the **tiny per-player stats `SavedData`** in feature #5 (a few ints per UUID). Full instance persistence buys nothing until runs are long enough that losing one to a restart hurts.

### Progression / retention
- **Dungeon log**: runs completed, current daily streak, longest dungeon cleared. `/dungeon log` prints it; completion message shows "Run #14 — streak 3". Streak multiplies the completion payout (e.g. +10%/day, cap 2×). That is the entire system. No leaderboard, no keys, no unlocks — lightest thing that creates a reason to come back tomorrow.

### Economy hook
- Completion drops a **config-defined payout stack** (default: emeralds scaled by tier × streak) at the exit pad — vanilla items, so it works with or without cobbleeconomy. If cobbleeconomy prices emeralds, the faucet feeds it automatically; **no Java dependency, no API call**. Optionally later: a config string for a command to run on completion (`payout_command = "eco give %player% %amount%"`), still dependency-free.

### Kamutotems hook — boss stones as top-tier loot (in the first update, zero-dependency)
- kamutotems already ships the exact integration point: **`BossStone`** — any vanilla item carrying
  `custom_data = {"kamutotems": {"boss_stone": <tier>}}`, explicitly documented as "another mod can
  hand this out as a reward that skips the quest and goes straight to a boss fight."
- Pocket Dungeons drops boss stones from tier_2/tier_3 loot tables via the vanilla
  `set_custom_data` loot function — **pure datapack JSON, no kamutotems Java touched**. Dungeon
  tier maps to stone tier (tier_2 → stone I–II low chance, tier_3 → stone II–III). If kamutotems is
  absent the stone is just a named vanilla item — harmless fallback, no crash, no config needed.
- **In-dungeon boss fights deferred**: `Boss.spawn`/`BossHost.track` would be a hard Java dep, the
  boss bar and sigil-refund logic don't survive `Instances` purge/teardown, and kamutotems' open
  DEATH_PROTECTION risk conflicts with non-lethal death rescue. The boss stone delivers the
  "dungeons feed my boss fights" loop now; revisit an in-instance boss room once kamutotems is
  play-verified.

---

## File / class map

| File | New? | Change |
|---|---|---|
| `LayoutGraphGenerator.java` | new | Seeded walk + spurs + role assignment + validate (pure logic, per PLAN.md Agent A) |
| `RoomSelector.java` | exists | Seeded weighted pick, `maxPerDungeon`, drop gate for v1 |
| `Instances.java` | exists | Glue: shape → resolve → retry → stamp; mob-count scaling; completion payout drop |
| `RoomTemplateGenerator.java` | exists | +5–7 templates (junction NESW, corner NE, corridor WE, encounter_skeleton, loot_small, spawner_den) |
| `data/.../dungeon_room/*.json` | new files | Metadata for new rooms |
| `data/.../loot_table/chests/tier_2.json`, `tier_3.json` | new | Generous tiered tables, incl. boss-stone entries via `set_custom_data` |
| `RitualListener.java` | new | Lodestone + key item `UseBlockCallback` |
| `DungeonLog.java` | new | Per-player `SavedData` (runs, streak, best) + `/dungeon log` |
| `PocketDungeonsConfig.java` | exists | key item, payout stack, streak cap, boss-stone drop chance/tier |

## What gets cut / collapsed
- **M5 full instance persistence** → collapsed to the stats-only `SavedData`.
- **Gate/key edges, loops, multi-cell footprints** → cut from M3 v1 (scaffolding kept).
- **Leaderboards / key systems / unlock trees** → cut; streak covers retention.
- **In-dungeon kamutotems boss room** → deferred until kamutotems is play-verified; boss-stone loot drops ship now instead (datapack-only, zero dependency).
- **Party scaling redesign** → none; only mob-count `+partySize-1`.
