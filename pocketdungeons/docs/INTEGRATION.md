# Pocket Dungeons — Datapack Integration

> **Do not add a dependency on `pocketdungeons`.** Every surface below is a
> datapack file or a config string, never a Java import, an API module or a
> mixin. This mirrors `kamutotems/INTEGRATION.md`'s stranger rule — mods in
> this suite stay strangers.

This document is for authors of datapacks (or other server-side mods) who
want to add content to Pocket Dungeons — new rooms, new encounters, new loot,
or a reward hook into another mod — without reading this mod's source.

---

## 1. The extensible surfaces

### 1.1 `dungeon_room/*.json` — new rooms, from any namespace

`RoomManifest` calls `listResources("dungeon_room", ...)`, which scans **every**
namespace, not just `pocketdungeons`. A datapack can ship
`data/mypack/dungeon_room/my_room.json` and it is picked up exactly like a
built-in room, provided its `template` resolves and its doors validate (§2).

Rooms are hot-reloadable: editing a `dungeon_room/*.json` file and running
`/reload` rebuilds the manifest immediately, no restart required (T0.1). The
`/dungeon admin manifest reload` command still exists and still works; it is
useful for reading back the rejection list (`/dungeon admin manifest list`)
without needing server logs.

### 1.2 `trial_spawner/tier_N/{normal,ominous}.json`

Standard vanilla trial spawner config, one per tier (1–3) and mode (normal /
ominous). `spawn_potentials` takes any entity id with arbitrary NBT — this is
a vanilla trial spawner file, not a Pocket-Dungeons-specific shape, so
anything the base game's trial chamber format accepts here works, including
mobs from other mods. Example (`trial_spawner/tier_1/normal.json`):

```json
{
  "spawn_range": 4,
  "total_mobs": 4.0,
  "simultaneous_mobs": 2.0,
  "total_mobs_added_per_player": 2.0,
  "simultaneous_mobs_added_per_player": 1.0,
  "ticks_between_spawn": 40,
  "spawn_potentials": [
    { "data": { "entity": { "id": "minecraft:zombie" } }, "weight": 5 }
  ],
  "loot_tables_to_eject": [
    { "data": "pocketdungeons:spawners/trial_key", "weight": 7 }
  ]
}
```

### 1.3 The `tier_1..3` chest loot tables

`loot_table/chests/tier_{1,2,3}.json` and their `_ominous` counterparts are
plain vanilla loot tables. Overriding one with a datapack (same id, higher
pack priority) replaces the reward room's contents for that tier/mode outright
— no code change needed.

### 1.4 `payoutCommand` — arbitrary command execution on completion

Set in `config/pocketdungeons.json`. **This runs a real console command with
full operator permissions on every completed run; treat it as arbitrary
command execution, because that is exactly what it is.** It is the one
remaining reward hook for routing a payout through another mod's own command
surface (e.g. `cobbleeconomy`) without a Java dependency. Placeholders:

| Placeholder | Meaning |
|---|---|
| `%player%` | The completing player's name |
| `%level%` | The keystone level the run was finished at |
| `%chests%` | How many of the reward room's three chests were earned |

Runs from the server console's own command source (not the player's), so it
is not limited by the player's permission level. Output is not suppressed —
a broken command is logged loudly rather than swallowed.

### 1.5 `keystoneItem` / `vaultKeyItem` re-pointing

Also in `config/pocketdungeons.json`. Both are plain item ids, resolved
against the registry at first use (not at config load), so any modded item id
works:

| Config key | Default | What it gates |
|---|---|---|
| `keystoneItem` | `minecraft:recovery_compass` | The item minted as a keystone and required to open a run |
| `vaultKeyItem` | `minecraft:trial_key` | The normal-mode vault key ejected by trial spawners |
| `ominousVaultKeyItem` | `minecraft:ominous_trial_key` | The ominous-mode vault key |

If a configured id fails to resolve, the affected feature falls back to its
vanilla default rather than failing outright — see the log line named in
`ConfiguredItem`'s usage at startup.

### 1.6 `dungeon_theme/*.json` — new themes, from any namespace

`ThemeManifest` calls `listResources("dungeon_theme", ...)`, scanning every
namespace the same way `dungeon_room` does. Each file names a theme (the
processor list its rooms stamp with, an optional `room_theme` filter, an
optional `loot_suffix` and `spawner_prefix`); see `DungeonThemeMeta` for the
full field list. Hot-reloadable via `/reload`, alongside `dungeon_room`.

### 1.7 `dungeon_adventure/*.json` — the theme graph

`AdventureGraphs` calls `listResources("dungeon_adventure", ...)`. One file
per theme, named for that theme's own id, describing which themes a run can
transition into from here and at what weight (`AdventureGraph.Node`). A
theme with no adventure node is never offered as a door choice in normal
play, regardless of whether its `dungeon_theme` file exists.

### 1.8 `anomaly_room/*.json` — the anomaly room pool

`RoomManifest.loadAnomaly` calls `listResources("anomaly_room", ...)`, same
JSON shape as `dungeon_room` (§2), loaded into a separate manifest. A rare
roll (`anomalyRoomChance`) swaps one critical-path cell for a room out of
this pool instead of the run's own theme, deliberately foreign in palette.

### 1.9 `diary/*.json` — collectible lore entries

`Diaries` calls `listResources("diary", ...)`. Each file is one numbered
diary entry (title, pages, the intensifier band that drops it, an optional
shell unlock). See `Diaries.Entry` for the full field list.

---

## 2. The `dungeon_room` schema

Everything below is parsed by `DungeonRoomMeta.fromJson`; this table is
documentation of existing behaviour, not a new contract.

| Field | Type | Default | Notes |
|---|---|---|---|
| `template` | string | **required** | Structure template id (e.g. `pocketdungeons:rooms/hall_tee`) |
| `footprint` | `[x, z]` | `[1, 1]` | ⚠ **only `[1, 1]` works today** — multi-cell footprints are M8 (D4) |
| `roles` | string[] | **required, non-empty** | Any of `entrance`, `exit`, `encounter`, `loot`, `corridor` |
| `theme` | string[] | `[]` (empty) | Theme ids this room is eligible for; empty matches every theme. Paired with a theme's own `room_theme` field (§1.6) |
| `weight` | int | `1` | Selection weight; higher is more likely |
| `minDepth` | int | `0` | Earliest depth (cells from the entrance) this room may appear |
| `maxPerDungeon` | int | `-1` | `-1` is unlimited; otherwise a hard cap per generated dungeon |
| `processors` | string | none | Id of a `processor_list` — see M1; a typo here is rejected at load rather than silently ignored |
| `content` | string | none | Situation id dispatched by `RoomContent` at stamp time |
| `tier` | int | `1` | Minimum loot tier at which this room may appear |
| `provides` | string[] | `[]` (empty) | Tool tags this room guarantees to make available. Closed vocabulary, see §2.2 |
| `requires` | string[] | `[]` (empty) | Tool tags at least one cell before this one must have provided. Closed vocabulary, see §2.2 |
| `pressure` | string | none | `local`, `omen` or absent. Informational: the selector uses it to space pressure rooms apart |
| `access` | string | `open` | `open` (exits reachable without engaging) or `gated` (must be solved to pass). Any other value is rejected at load |
| `window` | string | `bars` | What fills the window band beside this room's doorways: `bars`, `glass`, `tinted_glass` or `none`. Any other value is rejected at load. The band itself is fixed geometry, see §2.3 |

### 2.1 Validation rules, and their failures verbatim

A room is loaded only if its structure template resolves and its door jigsaw
layout is internally consistent. Every door must be a
`pocketdungeons:door` jigsaw, sitting on a cell edge, facing that edge, and
**complete** — every canonical slot on any wall that has at least one door
must also have one. A room whose door mask needs `[NORTH, EAST]` but is
missing one jigsaw on the east wall is rejected outright rather than stamped
with a hole in it.

**Masks match exactly, not as a superset.** A room carrying a spare door — one
more than the cell it is stamped into actually needs — punches a doorway
straight into the void on that wall, so an exact match is required rather
than "has at least these doors."

Rejections are collected per file and read back with
`/dungeon admin manifest list`; a rejected room does not take the manifest
down, the other rooms still load. The exact messages a validator will see
(from `DungeonRoomMeta` and `RoomManifest`):

| Failure | Message |
|---|---|
| Missing `template` | `missing required field: template` |
| `footprint` present but not a 2-element array | `footprint must be a 2-element array [x,z]` |
| `roles` missing or not an array | `roles must be a JSON array of strings` |
| `roles` is an empty array | `roles array must not be empty` |
| `access` is neither `open` nor `gated` | `room <template>: access must be "open" or "gated", not "<value>"` |
| `window` is not one of the four materials | `room <template>: window must be one of "bars", "glass", "tinted_glass" or "none", not "<value>"` |
| `template` does not resolve to a loaded structure | `template not found: <template>` |
| `processors` names a processor list that is not loaded | `processor list not found: <processors>` |
| A door jigsaw is not on any cell edge | `door jigsaw at <pos> is not on a cell edge` |
| A door jigsaw's facing does not match the wall it sits on | `door jigsaw at <pos> faces <facing> but sits on <edge> wall` |
| A wall has some doors but is missing a canonical slot | `partial door on <edge> wall: missing jigsaw at <pos>` |
| A canonical door slot exists but faces the wrong way | `partial door on <edge> wall: jigsaw at <pos> faces <facing>` |

### 2.2 The situation tag vocabulary

`provides` and `requires` draw from a closed list of fifteen tags. Anything
else is rejected at load with the room and the offending tag named:

`blocks`, `water`, `lava`, `lead`, `mob`, `trial_key`, `boat`, `gold`,
`snowballs`, `shears`, `pearl`, `wind_charge`, `milk`, `bow`, `redstone`

These are tags, not item ids. `water` is satisfied by a water bucket or by a
bag carrying one; `mob` is satisfied by an upstream room that spawns a
leashable mob, or by a party of two or more. The list is closed on purpose: an
open one cannot be tested, and a typo in a datapack would otherwise make a room
silently unselectable with nothing to say so.


### 2.3 The window band

Cells tile at 16 and each template owns its whole 16 by 16 footprint, wall ring
included, so two adjacent room interiors are separated by **two** wall blocks,
one per cell. A window is therefore not something one template can author: a
template that carved glass out of its own wall would be looking at the
neighbour's stone.

The band is fixed geometry instead, in the same lane as the doorway: the
doorway's 2 wide by 3 tall opening, widened by one block on each side at eye
height only. Both cells of a connected pair open the same four columns by
construction, the way they already agree on the doorway. A wall with no
connected neighbour keeps its solid wall and its bedrock ring.

`window` names the material for the two outer columns only. The middle two are
the doorway's own and stay open, or the band would plug a doorway the player
walks through. `none` leaves the wall solid.

Door masks are unaffected: `RoomManifest` derives a room's mask from its
`pocketdungeons:door` jigsaws at the canonical door slots, and the band sits
outside that range and carries no jigsaws.

---

## 3. What is *not* extensible yet

Naming these so nobody spends a weekend on them before they are datapack-driven:

1. **Affixes are a Java enum** (top-level `pocketdungeons.Affix`: `OMINOUS`,
   `FERAL`, `SWARMING`, `OVERCLOCKED`, `MOLTEN`, `SILENCED`, `EXPLOSIVE`,
   `VOIDED`). There is no datapack surface for adding a new one.
2. **Room roles are a Java concept**, not a registry — `entrance`, `exit`,
   `encounter`, `loot`, `corridor` are the fixed set the generator's own
   `switch`-shaped logic understands. A `dungeon_room` file may only use these
   five strings in its `roles` array; inventing a sixth does nothing.

---

## 4. For players: how the surfaces above show up in play

None of the above changes player-facing commands. `/dungeon`, `/dungeon exit`,
and the lodestone ritual all work exactly as before regardless of which
datapack rooms, loot tables, or spawners are currently loaded.


## 5. M68: namespaced content contracts and reload behaviour

### Namespaced identity

Every content manifest is now keyed by 
amespace:path instead of the bare
filename. Two datapacks that define the same local filename (e.g. both ship
data/<ns>/dungeon_room/hall_tee.json) both load; they coexist as
pack_a:hall_tee and pack_b:hall_tee.

### Legacy reference compatibility

A reference that does not contain a colon (a legacy bare name like
deepslate) resolves to the pocketdungeons namespace. This applies to:

- Room names in plans and force-room guarantees.
- Theme ids in adventure graph transitions and room theme lists.
- Theme ids read from a pre-M68 dungeon_log.dat (current_theme,
  completed_themes, ecent_themes).

A bare name that is not a pocketdungeons built-in returns null at lookup
time. Third-party content that relied on last-file-wins behaviour for a bare
name must either use an explicit namespaced id or provide a unique alias.

### Versioned content contracts

Each content type declares a ersion field (default 1 when absent). A file
that declares a version other than 1 is rejected at load with the file named.
This build supports version 1 only.

### Theme references: legacy vs namespaced

A theme's loot_suffix and spawner_prefix compose ids under the
pocketdungeons namespace only. A third-party theme using them secretly
requires pocketdungeons data. The optional namespaced fields loot_table,

ormal_spawner, and ominous_spawner override the legacy composition and
let a third-party theme point at its own namespace. The namespaced spawner
config ids are validated against the trial spawner config registry at load
time.

### Reload behaviour and rollback

A /reload or /dungeon admin manifest reload builds a candidate
ContentSnapshot from all five content surfaces, validates it as a whole,
and either publishes all five manifests atomically or keeps the last valid
snapshot.

- **Required coverage gate.** The snapshot must contain at least one
  entrance role room and one exit role room. A reload that drops the
  pocketdungeons pack (or rejects every entrance/exit room) fails this
  gate and is not published. The last valid snapshot stands.

- **Optional content rejection.** A rejected room, theme, adventure node,
  or diary entry is reported in the rejections list but does not sink the
  snapshot as long as the required coverage survives. A rejected room is
  one fewer room, not a failed reload.

- **Cross-resource validation.** Adventure graph transitions are resolved
  against the snapshot's own theme set before the graph is published. A
  transition to a theme this candidate never loaded is dropped; if the
  fixpoint leaves no entry-kind node, the graph is rejected.

- **Generation prohibition.** New floor generation (door preview and door
  commit) is refused while a content build is in progress, so a floor is
  never stamped against a half-published manifest.

- **Active-floor pinning.** An already-stamped floor is pinned to the
  geometry already in the world; it is not torn down by a reload. A door
  preview whose plan references a room or theme the new snapshot no longer
  carries is invalidated, so a stale preview can never commit against
  definitions the player never saw.

- **Vanilla registry references.** Processor lists, loot tables, and trial
  spawner configs are vanilla reloadable registries. Their references are
  validated at snapshot build time. A theme whose 
ormal_spawner or
  ominous_spawner id does not resolve in the trial spawner config
  registry is rejected at load with the theme named.

### Save compatibility

DungeonLog preserves every existing codec field. A pre-M68 save with bare
completed_themes keys loads unchanged. When a post-M68 qualified completion
arrives for the same theme, the legacy bare count is migrated into the
namespaced key and merged, so the theme is not double-counted. Unknown
references in a save are kept for recovery.

## M69: Affix schema

Affixes are data-driven. A new namespaced affix works without adding a
Java enum constant. The Affix enum is deleted; every affix is a JSON
definition loaded from data/<namespace>/dungeon_affix/*.json.

### Schema

Each file under data/<namespace>/dungeon_affix/*.json defines one
affix. The file name (minus .json) under its namespace becomes the
affix id. Required fields: version, label, blurb, effects. Optional
fields: order (default 0), min_level (default 0), weight (default 1),
depletion_multiplier (default 1, must be 1 or 2), incompatible
(default empty).

The effects object carries the bounded operation set. Every field is
optional; the defaults are the no-op values. A definition that
declares no operation is rejected at load.

Supported effect fields:

- ominous (boolean, default false): the whole run stamps ominous.
- trial_count_multiplier (double, default 1.0, range [1.0, 4.0]):
  multiplier on trial spawner mob counts.
- cooldown_factor (double, default 1.0, range [0.25, 1.0]):
  multiplier on the spawner cooldown.
- player_range (int, default 14, range [4, 14]): the trial spawner
  required_player_range.
- consumable_rule (string, default ALLOW, one of ALLOW, BLOCK):
  whether consumables are blocked for this run.
- neutral_wolf_spawn (boolean, default false): neutral wolves spawn
  in non-encounter cells.
- hazard_kind (string, default NONE, one of NONE, LAVA, TNT): the
  interior hazard placed underfoot.
- hazards_per_cell (int, default 0, range [0, 16]): how many hazard
  blocks per cell. Must be > 0 when hazard_kind is set.
- voided_floor (boolean, default false): the floor is ripped open in
  scattered cells.
- extra_trial_bodies (boolean, default false): extra trial bodies are
  placed (Loaded).
- bonus_tool_pool (string, default null): a namespaced loot table id
  for a guaranteed bonus tool pool. Validated against the server
  reloadable registries at load.
- decor_pool (string, default null): a namespaced loot table id for a
  decor pool.

### Extension limits

The supported operation set is closed. A JSON field the parser does
not read is silently ignored. A definition that declares no operation
is rejected. Genuinely new operations (a new hazard kind, a new
consumable rule, a new effect type) still require reviewed engine
work. The bounds are enforced in AffixEffects.build, the single
chokepoint for the supported operations gate.

### Legacy migration

A pre-M69 save that holds a bare affix name ("ominous") loads as the
namespaced id ("pocketdungeons:ominous") through AffixIds.resolve,
without a codec migration. A namespaced id parses as is. An unknown
bare name is dropped, the same login-safe lenience the enum parser
had.

### Reload contract

The affix manifest participates in the M68 atomic snapshot and reload
contract. ContentSnapshot.build parses the affix manifest alongside
the other five surfaces and checks the built-in coverage gate (every
built-in affix id must be present). A candidate that fails the gate is
not published; the last valid snapshot stands. ContentReload publishes
the affix manifest in the same atomic commit as the other surfaces.