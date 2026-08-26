> Archived 2026-08-25 (M9 C5): superseded by `handoffs/M<n>-handoff.md`, the
> per-milestone handoff format `PROGRESS.md` now uses.

# Handoff prompt — Pocket Dungeons design session

Paste everything below the line into a fresh chat.

---

I'm working on **Pocket Dungeons**, a server-side Fabric mod in my multi-mod
suite at `A:\MrPinoys Mods`. The last session was **pure design — no code was
written or changed.** Everything landed in documentation.

## Read these first

- `pocketdungeons/VISION.md` — purpose, hook, pillars, platform thesis. **Start
  here.** It is the product definition.
- `pocketdungeons/MYTHIC_PLUS_RECONCILIATION.md` — design decisions and their
  reasoning, reconciled against an external Mythic+ spec.
- `pocketdungeons/PLAN.md` — how the shipped mod was actually built.
- `docs/SUITE_AUDIT.md`, `docs/DESIGN.md` — suite-wide context.
- `kamutotems/INTEGRATION.md` — the suite's "mods stay strangers" rule.

## What Pocket Dungeons is

A run-based Minecraft dungeon crawler, positioned as a **server format** (the
Factions comparison) rather than a content mod. The hook: *your room has three
doors, and everything you own came from behind them.* You walk forward through
5–8 procedural rooms and emerge into your own persistent room.

Influences, internal only: Mythic+ (keystone ladder, timer, affixes), Nephalem
Rifts (procedural disposable runs, end-of-run upgrade choice), REPO (6-player
excursion), Habbo (owned decoratable room), Skyblock (sealed-economy proof).

## Verified code facts — do not re-derive or contradict these

These were established by reading the source and the 26.2 merged jar. The jar is
at `/c/Users/Kriss/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` —
inspect with `javap -cp` and `unzip -l`.

**Platform:** MC 26.2, Fabric loader 0.19.3, Fabric API 0.156.0+26.2, JDK 25,
Loom 1.17. `"environment": "server"`, no `assets/` directory, no custom blocks or
items. MIT in `fabric.mod.json`, but **no `LICENSE` file at the repo root.**

**Instances:** one dimension `pocketdungeons:void` with a slot grid
(`slotPitch: 2048`, `slotsPerRow: 64`). `allocateSlot()` is a linear scan from 0
over a `usedSlots` TreeSet. Instances are **force-loaded** (`setChunkForced`),
which is why teardown is eager. `PendingClear` is a budgeted tick-spread clear
(`advance(level, budget)` / `done()`) at `clearBlocksPerTick: 8192`;
`finishClear` sweeps entities and releases force-load per cell.

**Geometry:** `RoomBuilder` stamps sealed 16×16 boxes — `POLISHED_ANDESITE`
floor, `STONE_BRICKS` walls and ceiling, `SEA_LANTERN` lighting. Cells are
chunk-aligned (`slotPitch` validated as a multiple of 16), so **a cell is a
chunk**. `LayoutGraphGenerator` does a self-avoiding walk, 5–8 rooms, 0–2 spurs,
`maxGridSpan: 12`, 1×1 footprints only (multi-cell deferred).

**Dimension type:** `has_skylight: false`, `ambient_light: 0.0`,
`effects: minecraft:the_end`, `fixed_time: 6000`, `bed_works: false`,
`respawn_anchor_works: false`. Sky is **per-dimension** — it cannot vary per room.

**Keystone:** the item is *"a remote, not a save file"* (its own javadoc).
`DungeonLog` (a `SavedData` keyed by UUID) is the authority. `Keystone.Affix` is
an enum `NONE / OMINOUS / FRAGILE`. Only **three** sites branch on it:
`Instances:360`, `Instances:1137` (`== OMINOUS`), `Keystones:63` (`== FRAGILE`).
Stored as `Codec.STRING.optionalFieldOf("keystone_affix", "")` — comma-joining a
set into that field is backward compatible with no codec migration.

**Selector room:** `Keystone.offers(level)` returns exactly 3.
`selectorDoorStep` reads **hardcoded offsets** (`dz == 8`, `dy == 1 || 2`) with no
rotation transform. ⚠ **It has no owner check** — a party guest can click the
host's doors and spend their offer. Gate on `record.owner`.

**Data-driven surfaces:** `RoomManifest` calls
`listResources("dungeon_room", ...)` across **all** namespaces — but it is
**not `/reload`-driven** (its javadoc admits this). `TrialContent` writes the
trial-spawner config at stamp time from
`data/pocketdungeons/trial_spawner/tier_N/{normal,ominous}.json`, which carries
`total_mobs`, `simultaneous_mobs`, `ticks_between_spawn`, `spawn_potentials[]`.
`payoutCommand` runs `server.getCommands().performPrefixedCommand(...)` — an
arbitrary command hook.

**⚠ `DungeonRoomMeta.java:45` parses a `processors` field and NOTHING USES IT.**
It appears nowhere in `TemplateStamper` or `LayoutStamper`. Wiring it is the
highest-leverage small job in the codebase — it turns "140 hand-authored `.nbt`
files for 10 themes" into "14 templates + 10 processor lists".

**Other:** `RoomContent`'s `spawnMobs` was deleted in T17 — there is **no direct
entity-spawn path** left; trial spawners and vaults are the only content, and
`TrialContent` deliberately *replaces* classic spawners. There is **no
`PlayerBlockBreakEvents` registration anywhere** — the dungeon is fully
breakable. `removeAllEffects()` fires only in `rescue()`, not on entry, so
potions survive. `ritualKeyItem` is **dead config** (referenced only in a
comment; the real gate is `Keystone.isKeystone`).
`ServerPlayConnectionEvents.JOIN` is registered with
`JOIN_RECOVERY_DELAY_TICKS = 20`. `Instances:1867` uses `getRespawnData()` as the
stray fallback.

**Wolves (verified in the jar):** `DataComponents.WOLF_VARIANT` is a
`Holder<WolfVariant>`; `Entity.setComponent(type, value)` is public;
`WolfVariants` exposes 9 `ResourceKey`s (pale, spotted, snowy, black, ashen,
rusty, woods, chestnut, striped). `Mob.setHomeTo(BlockPos, int)` and
`TamableAnimal.tame(Player)` exist. `Wolf implements NeutralMob` with
`startPersistentAngerTimer()`. `StructureTemplate` has `fillFromWorld`, `save`,
`load`, `placeInWorld`.

**Cross-mod (zero coupling, all verified):** `spiritwolves`'
`WolfCapture.capture(Wolf, ServerLevel)` is origin-agnostic and uses
`saveWithoutId`, so **the wolf variant survives Spirit Stone binding**.
`TameWatcher` polls `isTame()` on `END_SERVER_TICK`. Spirit Stone recharge is
**vanilla anvil + diamonds, no code**. The Kamu Station is **any fletching
table**. PD's ominous loot tables already drop `{kamutotems:{boss_stone:N}}`.

⚠ One thing I could not verify: the exact bone-taming probability inside
`Wolf.mobInteract`, and whether an *angry* wolf accepts a bone. Check the
bytecode before tuning anything around it.

## Design decisions settled last session

All recorded in the two docs. Headlines:

- **Wolves** as run-scoped tameable helpers, 9 coats gated on
  `DifficultyProfile.lootTier()`, spawned untamed at stamp time, pinned with
  `setHomeTo`, bones as the catch resource (vanilla roll = the catch rate).
  Optional permanence via Spirit Stone.
- **Affixes** become a stackable set. Level thresholds (5/10/15) decide *how
  many*; a weekly rotation decides *which*; the door choice is the elective one.
  Naming follows Kamu Totems' *convention* with entirely separate vocabulary:
  intensifier ladder Baby / Lowkey / Highkey / Menace / Unhinged, labels
  *Cooked* (ominous), *Big L* (fragile), *Feral* (wolves).
- **The persistent room** replaces the selector room, persisted as a
  `StructureTemplate` blob (not resident in the world). Owner + whitelist for
  breaking and containers; anyone may use stations; ender chest open to all.
- **The closed loop:** on completion, capture the room from the entrance cell,
  clear it, re-stamp it at the terminal cell behind a closed door. Order is
  **capture → persist → clear → stamp**. The finished dungeon lingers as a quarry
  (release force-load tickets, keep the blocks).
- **Bedrock envelope** outside the shell — sub-floor and over-ceiling always,
  outer wall ring only on faces with no adjacent cell.
- **Self-sufficiency is a constraint, not a mode**: nothing required for
  progression may live outside the dungeon loop.
- **Voice:** the mod says nothing about the closed loop. Everything it *does* say
  is slang. Silent-vs-slang, not clinical-vs-slang.

## Next actions, roughly in order

1. Wire `processors` (§5.2 of VISION.md) — small, unblocks all theme work.
2. Make `RoomManifest` `/reload`-driven — small, unblocks datapack authors.
3. Add `LICENSE`, `INTEGRATION.md`, and a published `dungeon_room` schema.
4. Build the cheap affixes (Feral, Swarming, Overclocked, Molten, Silenced) in
   Java. **Do not data-drive affixes until 5–6 exist and the varying knobs are
   known.**
5. Loot-table pass under "guaranteed floors for consumables, weighted rolls for
   treasure", including tiered building blocks and a grove/garden room for wood.
6. The room milestone (persistence, build mask, permissions, closed loop).

## Open questions

- Do rewards scale with **affix count**, or only `lootTier()`? If not, players
  will park just below the third threshold.
- Depletion must not compound when depletion-touching affixes stack — take the
  max or cap it.
- Weekly rotation seed source (wall-clock week index drifts across timezones).
- Making the room **visitable** — flagged as the highest-value unbuilt thing, no
  mechanism designed yet.

## How I like to work

- Verify API claims against the 26.2 jar rather than from memory; say explicitly
  when something is unverified (the codebase uses `⚠ UNVERIFIED` comments).
- Design decisions get written into the `.md` docs as they land, with the
  reasoning, and superseded designs are marked as superseded rather than deleted.
- Push back on my ideas when the code says otherwise — several decisions last
  session reversed after checking the source.
