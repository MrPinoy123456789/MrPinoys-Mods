# M28 - Themed mob spawners - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `TrialContent.java` lines 124-232: `applyEncounter`, `configId` -- spawner
   config id built and written here.
2. `DungeonThemeMeta.java` lines 1-49: theme metadata record. New
   `spawnerPrefix` field, parallel to `lootSuffix`.
3. `RoomContent.java` lines 84-121: `apply` -- call site that must pass theme
   to `TrialContent.applyEncounter`.

## Dependencies

Grep `applyEncounter` in `src/main/java/`. Signature
`(ServerLevel, BlockPos, List<BlockPos>, int, Set<Affix>)` = chain intact.

Grep `configId` in `TrialContent.java`. Method at line 229 = target to extend.

## Goal

Themes control which mobs spawn from trial spawners, not just wall blocks.
Crypt: skeletons and zombies only. Infestation: spiders only. Data-driven:
themed rosters are JSON under `data/pocketdungeons/trial_spawner/`, no Java
needed to add themes.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: `spawner_prefix` in `DungeonThemeMeta`

1. Add `final String spawnerPrefix` after `lootSuffix`.
2. `fromJson`: read `spawner_prefix` via `stringOrNull`, same as `loot_suffix`.
3. Null = default tier configs. No existing theme JSON breaks.

### Step 2: Thread theme to `TrialContent`

1. `RoomContent.apply`: add `String theme` after `lootSuffix`. Pass to
   `TrialContent.applyEncounter`.
2. `TrialContent.applyEncounter`: add `String theme` after `affixes`. Resolve
   `ThemeManifest.current().byId(theme)`, read `meta.spawnerPrefix`.
3. `LayoutStamper.stamp` line 123: pass `theme` already in scope. Admin/test
   callers: pass `null`.

### Step 3: `configId` accepts prefix

1. `configId(int tier, boolean ominous)` -> `configId(String prefix, int tier, boolean ominous)`.
2. Null/empty prefix: `pocketdungeons:tier_{tier}/{normal|ominous}`.
3. Non-null: `pocketdungeons:{prefix}_tier_{tier}/{normal|ominous}`.
4. Update 2 call sites in `applyEncounter` (lines 182-183), 2 in
   `writeInlineConfig` (line 246, swarming path).

### Step 4: Themed spawner JSONs

6 files per theme (tier 1-3, normal + ominous). Copy counts/ticks/eject
from `tier_{n}` files. Only `spawn_potentials` changes.

**Crypt** (`spawner_prefix: "crypt"` in `dungeon_theme/deepslate.json`):
zombie (weight 5) + skeleton (weight 4). Equipment: reuse
`pocketdungeons:equipment/tier_{n}_melee` and `_ranged`.

**Infestation** (new `dungeon_theme/infestation.json`):
spider (weight 3) + cave spider (weight 2). No equipment. Reuse
`pocketdungeons:theme_deepslate` processors. `discoverable: true`.

### Step 5: Update theme JSONs

`dungeon_theme/deepslate.json`: add `"spawner_prefix": "crypt"`.
Others unchanged (null = default roster).

### Step 6: Tests

`TrialContentConfigIdTest`:
- `configId(null, 1, false)` = `pocketdungeons:tier_1/normal`
- `configId("crypt", 1, false)` = `pocketdungeons:crypt_tier_1/normal`
- `configId("", 3, true)` = `pocketdungeons:tier_3/ominous`
- `configId("infestation", 2, false)` = `pocketdungeons:infestation_tier_2/normal`

## Constraints

- `spawnerPrefix` optional. Missing/null = current behavior.
- Themed configs: self-contained JSON, copy full tier file structure.
- Only `spawn_potentials` changes in themed configs. No count/tick tuning.
- Swarming `writeInlineConfig` must use prefix.
- `BossContent.spawn` not affected (not a trial spawner).

## Verification

- `build_mod` default `build` after each step.
- Headless: `configId` test passes; themed JSON parses clean.
- Live (deferred): deepslate spawns zombies+skeletons only; blackstone spawns
  default mix. Record in `LIVE_TEST_PASS.md`.
- Done when: themed `spawner_prefix` spawns only its mobs; null prefix
  unchanged.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M28-handoff-completed.md` once landed.
