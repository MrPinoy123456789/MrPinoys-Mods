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
| `%chests%` | The settled chest count when the interval banks: the omen band's 3, 2 or 1, plus the zone's depth bonus |

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

#### The optional `rules` block: zone rules

A theme is also a **zone**: its optional `rules` object says how its floors
play, as opposed to how they look (`docs/ZONES_SPEC.md` section 2). A theme
without one is the default zone, the ordinary dungeon. The rules in force are
the ones of the floor in progress, or of the floor just cleared while the
party stands between floors. Schema: `docs/schema/dungeon_theme.schema.json`.
Parser: `ZoneRules.fromJson`.

Why the theme and not the adventure node: the theme is what every floor is
stamped with and what the run record keeps, the Endless Mine recipe forces a
theme (not a node) onto its floors, and a theme without an adventure node is
still a valid place to be. The node keeps its one job, the door graph.

| Field | Type, range | Default | Effect |
|---|---|---|---|
| `floor_kind` | `"standard"` | `"standard"` | How a floor is planned. Only `standard` is built; any other kind rejects the theme. |
| `floor_sequence` | array of floor kinds, non-empty | `[floor_kind]` | The pattern of floor kinds within an interval, repeating. Same restriction. |
| `capstone` | `"none"` or `"boss"` | from the adventure node | `boss`: the Drowned Warden spawns at the terminal and the pad waits for it. Left out, a `boss` adventure node is a boss floor, as before. |
| `depth_bonus` | number, 0 to 3 | 0.3333 | Bonus completion chests per floor since the last bank, counted from the second floor: `floor(depth_bonus * (floor - 1))`, at most 3, stacked on top of the three chest spots. The default pays nothing on floors 1 to 3, one chest from floor 4, two from 7, three from 10. Also added to `%chests%`. |
| `omen_base` | `{"after": int >= 0, "amount": 0 to 4}` | after = `floorsPerSafeVisit`, amount 1 | Every floor of an interval after the `after`th starts with `amount` omen already on it. Not cumulative. |
| `omen_scale` | number, 0 to 4 | 1.0 | Multiplier on every omen rise (dwell, sensors, shrieks), rounded. Relief and the Ominous Bargain are not scaled. |
| `loot_role` | `"gear"`, `"materials"`, `"trophy"` | `"gear"` | The zone's faucet. Declared and validated; nothing pays differently by role yet. |
| `loot_tier_every` | int, 0 to 1000 | 0 | One completion loot tier up every N floors since the last bank, never past tier 3. 0 is off. |
| `unlock_level` | int >= 1 | 1 | A door into this zone is only dealt once the key reaches this level. If every candidate is locked, the full pool is dealt instead. |
| `kit_top_up_scale` | number, 0 to 10 | 1.0 | Multiplier on the safe-visit kit top-up (see "The kit and the safe-visit top-up" under the bag schema). The grant never exceeds the kit baseline. |

An unknown field, a floor kind that is not built, or a value out of range
rejects the theme at load with the reason, the same way a missing processor
list does, so a typo never quietly becomes the default. `/dungeon admin
validate` (PackValidator) also reports rules that load but misbehave: an
`unlock_level` above `keystoneMaxLevel`, a `boss` capstone off a boss node
(the boss gates the pad but does not reset the descent), a `none` capstone on
a boss node, and an entry pool locked above level 1 throughout.

The bundled Endless Mine expresses its escalating haul this way
(`data/pocketdungeons/dungeon_theme/endless_mine.json`):

```json
"rules": { "loot_role": "materials", "loot_tier_every": 3 }
```

One difference from the Mine before the hook: its escalation step is now its
own `3` rather than following `floorsPerSafeVisit`, and a Mine-themed floor
reached through the adventure graph (not the recipe) escalates too, since the
theme is the zone.

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

1. **Situation handlers are a Java registry** (`Situations`). A
   `dungeon_room` file may name a registered situation in its `content`
   field, but adding a new situation handler requires Java. The situation
   tag vocabulary (`SituationTags`) is also a closed set.

Structural geometry (entrance and exit placement, door masks, the grid
contract) remains engine-owned. A `dungeon_role` file may not declare
`stage: "structural"`; only the built-in `entrance` and `exit` roles
control topology, and a pack cannot add a new one.

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
  completed_themes, 
ecent_themes).

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

## M70: Bag schema

A bag is a data-driven definition loaded from
data/<namespace>/dungeon_bag/*.json. The Bags enum is deleted; every
bag is a JSON file. A third-party bag flows through the picker, the
solvability seed, the cube catalyst lookup, and the loot roll, with no
Java edit.

### Schema

Each file under data/<namespace>/dungeon_bag/*.json defines one bag:

```json
{
  "version": 1,
  "label": "Mason's Bag",
  "blurb": "Stone, a pick, and the patience to use them.",
  "order": 0,
  "headline": ["minecraft:cobblestone", "minecraft:stone_pickaxe", "minecraft:torch"],
  "tags": ["blocks"],
  "loot_table": "pocketdungeons:bags/mason",
  "kit_baseline": [
    {"item": "minecraft:cobblestone", "count": 16},
    {"item": "minecraft:stone_pickaxe", "count": 1, "durability": true},
    {"item": "minecraft:torch", "count": 4},
    {"item": "minecraft:bread", "count": 4}
  ]
}
```

Fields:

- `version` (int, default 1): the schema generation. Only 1 is accepted.
- `label` (string, required): the display name in the picker.
- `blurb` (string, required): the tooltip in the picker.
- `order` (int, default 0): the stable picker order. Bags sort by order
  then id; the built-ins use 0 through 7.
- `headline` (array of strings, default []): the catalyst items the cube
  recipe matches against the player's off-hand. Each string is a
  namespaced item id.
- `tags` (array of strings, default []): the capability tags this bag
  seeds at depth 0. Each must be one of the fifteen `SituationTags`; an
  unknown tag is rejected at load.
- `loot_table` (string, default derived): the namespaced loot table id
  the bag rolls. Defaults to `<namespace>:bags/<path>`, so a bag at
  data/mypack/dungeon_bag/my_bag.json rolls from mypack:bags/my_bag
  unless it names its own table.
- `kit_baseline` (array, default []): what a safe visit tops the kit up
  toward. Each line is `item` (namespaced id, once per bag), `count`
  (at least 1, default 1), `durability` (default false; true for a tool)
  and optionally `empties_into` (the item a used one leaves behind, such as
  `minecraft:bucket` for a water bucket). Empty or omitted means the bag
  is never topped up. Schema: `docs/schema/dungeon_bag.schema.json`.

### The kit and the safe-visit top-up

The loot table is the kit, and it is handed over **once**: when the player
picks the bag at the bag chest. Entering and leaving the dungeon never grant
items; the void inventory is the player's dungeon inventory, kept between
visits and restored slot for slot on the next entry.

At each interval's settlement (going home, or a checkpoint exit, which
banks one band worse), every settling member's kit is topped up toward
`kit_baseline`:

- The deficit is `count` minus what the member holds of that item type,
  whether or not it carries the bag tag, counted in their dungeon inventory
  and in the containers and item-holding entities of the saved room the
  party returns to and of the member's own saved room (shulker boxes and
  bundles included). Stashing a kit in a chest does not create a deficit.
- A stackable line restores `floor(deficit x band fraction x kit_top_up_scale)`.
  The band fractions are config (`kitTopUpBandLow`, `kitTopUpBandMid`,
  `kitTopUpBandHigh`, default 1.0, 0.5, 0.0).
- A `durability` line is replaced only when missing, only at the calm band,
  and a damaged one is never repaired.
- An `empties_into` line turns a held empty back into the kit item; a new
  one is minted only when no empty is held anywhere.
- Nothing ever goes above the baseline, and every granted stack carries the
  bag tag. Kit blocks placed in the world are not counted: that is an
  accepted, band-bounded source of room-building material.

Keep the baseline at or below what one roll of the table gives (the
built-ins are exactly their tables' guaranteed entries). A table may be
random; the baseline may not, which is why it is declared here rather than
read off the table.

### Extension limits

The capability tag vocabulary is closed (`SituationTags`). A bag that
declares a tag outside the set is rejected at load with the bag and the
offending tag named. The loot table reference is validated against the
server's reloadable registries; a missing table is rejected at load.

### Legacy migration

A pre-M70 save or config that holds a bare bag id ("mason") resolves to
"pocketdungeons:mason" through the legacy bridge in `BagIds.resolve`.
A qualified id ("theirpack:their_bag") is returned as-is.

### Reload contract

The bag manifest participates in the M68 atomic snapshot and reload
contract. ContentSnapshot.build parses the bag manifest alongside the
other surfaces and checks the built-in coverage gate (every built-in
bag id must be present). A candidate that fails the gate is not
published; the last valid snapshot stands.

## M70: Role schema

A room role is a data-driven definition loaded from
data/<namespace>/dungeon_role/*.json. The hard-coded role switch in
RoomContent is deleted; every population role is a JSON file. A
third-party role flows through assignment, selection, and dispatch,
with no Java edit.

### Schema

Each file under data/<namespace>/dungeon_role/*.json defines one role:

```json
{
  "version": 1,
  "stage": "interior",
  "weight": 45,
  "min_depth": 0,
  "max_depth": -1,
  "operation": "TRIAL_ENCOUNTER"
}
```

Fields:

- `version` (int, default 1): the schema generation. Only 1 is accepted.
- `stage` (string, required): must be `"interior"`. Structural roles
  (`entrance`, `exit`) are engine-owned; a file that declares
  `stage: "structural"` is rejected at load.
- `weight` (int, default 1): the assignment weight. The generator draws
  interior critical-path roles by weight; a role with weight 0 is never
  picked by the draw.
- `min_depth` (int, default 0): the minimum BFS depth at which this role
  may be assigned.
- `max_depth` (int, default -1): the maximum BFS depth, or -1 for
  unbounded.
- `operation` (string, default NONE): one of `TRIAL_ENCOUNTER`,
  `TOOL_CACHE`, `NONE`. The set is closed; an unknown operation is
  rejected at load.

### Bounded operations

A population role composes one of three bounded operations:

- `TRIAL_ENCOUNTER`: remove the placeholder chest and stamp a trial
  spawner (the built-in encounter operation).
- `TOOL_CACHE`: stamp a vault (the built-in loot operation).
- `NONE`: remove the placeholder chest and place no content (the
  built-in corridor operation).

Genuinely new operations still require reviewed engine work. This is
the same gate AffixEffects holds for affix operations: JSON composes
existing bounded operations, it does not define new ones.

A role never changes topology. Structural roles are engine-owned and
not loaded from JSON. A pack cannot add a structural role.

### Legacy migration

A pre-M70 room file that holds a bare role name ("encounter") in its
`roles` array is qualified to "pocketdungeons:encounter" at parse time.
A qualified id ("theirpack:their_role") is returned as-is.

### Reload contract

The role manifest participates in the M68 atomic snapshot and reload
contract. ContentSnapshot.build parses the role manifest alongside the
other surfaces and checks the built-in coverage gate (every built-in
population role id must be present). A candidate that fails the gate is
not published; the last valid snapshot stands.

## M71: Cube recipe schema

Each file under `data/<namespace>/cube_recipe/*.json` defines one Cube
recipe. The `CubeRecipe` enum is deleted; every recipe is data-driven,
keyed by namespaced id (`pocketdungeons:store`). A third-party recipe is
`theirpack:their_recipe` and flows through every site the built-ins do,
with no Java edit.

### Schema

Each file under `data/<namespace>/cube_recipe/*.json` defines one recipe:

```json
{
  "version": 1,
  "confirmation": "The next run guarantees a Store.",
  "catalyst": "minecraft:emerald",
  "cost": 1,
  "priority": 0,
  "min_level": 0,
  "effects": {
    "guaranteed_rooms": [
      {"names": ["the_store"], "min_tier": 0}
    ]
  }
}
```

Fields:
- `version`: must be `1`.
- `confirmation`: the chat message sent on a successful apply. Terse;
  does not explain the mechanic.
- `catalyst`: the item id that matches this recipe in the off-hand.
  Mutually exclusive with `catalyst_tag`.
- `catalyst_tag`: an item tag id (with `#` prefix) that matches this
  recipe. Mutually exclusive with `catalyst`.
- `cost`: how many of the catalyst are consumed (default 1, minimum 1).
- `priority`: match order; lower numbers checked first (default 0).
  Two recipes with the same priority that both match the same stack is an
  ambiguous match and is refused.
- `min_level`: the keystone level required before this recipe matches
  (default 0). The Cube station's own unlock level still gates the station
  as a whole; this is the per-recipe gate.
- `effects`: the recipe's typed effects (see below).

### Effect fields

The supported effect set is closed. A recipe that declares an effect
outside this set is rejected at load. A recipe that bends nothing is
rejected outright.

- `ominous` (boolean): the run starts ominous.
- `feral` (boolean): the Feral affix is guaranteed.
- `completion_study_list` (boolean): the completion line lists the run's
  situations by name afterward.
- `bounded_supply` (boolean): one guaranteed tool cache is supplied.
- `path_length_bonus` (integer, 0 to 8): added to both min and max path
  bounds.
- `weighted_rooms` (array of room names): rooms to weight up in the
  selection pass. A named room draws three times its declared weight.
  Generalises M66's hardcoded flooded/chasm weighting.
- `guaranteed_rooms` (array of groups): room groups to force onto an
  eligible cell after the main pass. Each group has `names` (array of
  alternative room names) and `min_tier` (0 to 3). A group whose
  `min_tier` the offer's loot tier cannot satisfy refuses before the
  catalyst is spent. Generalises M66's hardcoded infested, Deep Dark,
  and Store guarantees.

### Extension limits

The supported effect set is closed. Genuinely new operations still
require reviewed engine work. A recipe that declares no effect is
rejected at load: a definition that bends nothing has no business
shipping.

Two recipes whose catalyst predicates can match the same off-hand
stack is an ambiguity the match path refuses at use time rather than
silently picking one. The manifest catches the static case at load
(two definitions naming the same catalyst item or tag).

### Legacy migration

Before M71 a recipe was a Java enum constant (`CubeRecipe.OMINOUS`) and
the on-keystone form was the enum's tag key (`"ominous"`). M71 replaces
the enum with data-driven definitions keyed by namespaced id
(`"pocketdungeons:ominous"`). A legacy bare tag on a keystone is resolved
by `RecipeIds.resolve` to its namespaced id. The two M66 legacy tags
(`bag_override`, `double_key`) map to their replacements
(`bounded_supply`, `path_extension`).

### Reload contract

The recipe manifest participates in the M68 atomic snapshot and reload
contract. ContentSnapshot.build parses the recipe manifest alongside the
other surfaces and checks the built-in coverage gate (every built-in
recipe id must be present). A candidate that fails the gate is not
published; the last valid snapshot stands.

### Personal discovery

A recipe is discovered the first time a player successfully applies it
at the Cube. The discovery is personal, never broadcast, and never
browsed. A recipe the player has not discovered is never listed, never
auto-completed, and never shown in any catalogue, because no catalogue
exists (VISION 5.4: "you can see the items, not the recipe").

The discovery floor guarantees a catalyst by the first eligible safe
visit (lobby entry at the Cube unlock level) and presents a terse "try
this at the Cube" opportunity. After that the floor never fires again.

Knowledge spreads through conversation, not through server-wide
discovery broadcasts. Optional handwritten books and cards may carry
player knowledge, but are never required keys or mandatory clues
(VISION 9).

## M72: pack authoring, validation and distribution

A non-developer author can author, validate, distribute and upgrade a pack
using only a release jar. No Java import, no source-tree workflow, no client
resource pack. Three admin commands cover the loop.

### Authoring

`/dungeon admin exportstarter <packname> [namespace] [confirm]` writes a small
namespaced starter pack to `<world>/datapacks/<packname>/` with every file
rewritten to use `<namespace>` (or `<packname>` if the namespace is omitted)
instead of `starter`. It ships one working
example of every content surface (room, theme, adventure node, affix, bag,
role, recipe, diary, anomaly room), each referencing real built-in resources
so the pack loads cleanly as a starting point. The namespace is rewritten
into file paths and file contents during export, so no manual file editing is
needed to rename a namespace. The starter is bundled
inside the jar under `/pack_starter` (outside `data/`, so the game never loads
it as live content until you export it).

`/dungeon admin exportworkspace <name> [confirm]` ships the rooms an author
captured in-world with `/dungeon admin buildroom` and
`/dungeon admin saveroom <name>` as a distributable datapack, with a
`pack.mcmeta` for the current pack format. The live saveroom datapack is left
untouched.

Both exports refuse to overwrite an existing destination. Re-run with the
`confirm` literal to back the existing destination up to a timestamped
`.backup-<millis>` sibling and replace it. The existing
`/dungeon admin exportdata` bulk export stays available for operators and is
unchanged.

### Validation

`/dungeon admin validate [seed]` builds a candidate snapshot from the live
server and reports every finding as `file / field: cause`, with a trailing
`(seed=N)` for plan-level findings you can replay with `/dungeon admin plan N`.
A `seed` argument runs the plan check against that one seed, for reproducing a
specific failure. The checks:

- Parse rejections from every surface (file and cause).
- Required coverage: entrance and exit rooms, and every built-in affix, bag,
  role and recipe id must be present.
- The (mask, role) pairs the planner can ask for: a pair with no room is a
  content gap.
- Reachable adventure nodes: a node no entry theme can reach is a dead branch
  no door ever offers; a theme with no adventure node is never offered as a
  door choice.
- Cube recipe eligibility and guarantees: every weighted and guaranteed room
  reference must resolve; a guarantee group with no resolvable room can never
  fire.
- Missing loot: a theme's namespaced `loot_table` is checked against the
  reloadable loot registry (a typo here would otherwise fail at run time, not
  load time).
- Kit baselines: a `kit_baseline` line naming an unknown item or empty, a
  tool not marked `durability` (or a mark on an item with no durability),
  or a count above what one roll of the bag's `loot_table` gives.
- Door and return-path validation: a plan is generated, validated (the
  return path is the BFS from the entrance reaching every cell), and a room
  is resolved for every cell, sweeping the first 16 seeds.

### Versioned compatibility examples

The starter pack's example files are the compatibility contract for this
build. Each one is the minimal valid shape a content file takes under the
M68 to M71 schemas, all `version: 1`:

| Surface | Example file | Key fields |
|---|---|---|
| room | `dungeon_room/example_room.json` | `template`, `footprint`, `roles`, `theme`, `access`, `window` |
| theme | `dungeon_theme/example_theme.json` | `name`, `processors`, `room_theme` |
| adventure | `dungeon_adventure/example_theme.json` | `kind`, `next` (weighted transitions) |
| affix | `dungeon_affix/example_affix.json` | `label`, `blurb`, `order`, `effects` (closed set) |
| bag | `dungeon_bag/example_bag.json` | `label`, `headline`, `tags`, `loot_table`, `kit_baseline` |
| role | `dungeon_role/example_role.json` | `stage`, `weight`, `operation` (closed set) |
| recipe | `cube_recipe/example_recipe.json` | `catalyst`, `cost`, `priority`, `effects.guaranteed_rooms` |
| diary | `diary/example_diary.json` | `number`, `band`, `title`, `pages` |
| anomaly | `anomaly_room/example_anomaly.json` | same shape as `dungeon_room` |

A reference that does not contain a colon (a legacy bare name like
`the_store`) resolves to the `pocketdungeons` namespace; an explicit
namespaced id (`pocketdungeons:the_store`) means exactly the same thing and
is the form an upgrading pack should move to. The starter recipe's
`guaranteed_rooms` uses the bare form on purpose, to show both.

### Pack format

The `pack.mcmeta` both exports write uses the game's current data pack
format (`SharedConstants.DATA_PACK_FORMAT_MAJOR`), which is the 26.2 format
for this build. A release jar for a future Minecraft version writes that
version's format automatically; no pack author edits `pack_format` by hand.

## M76: operating envelope

An operator can see how close the server is to its declared caps and tune
them. This is the operator-facing surface; players see only the refusal
messages when a cap is hit.

### Caps

Three fields in `config/pocketdungeons.json` bound concurrent world work.
A value of `0` disables that cap.

| Field | Default | What it bounds |
|-------|---------|----------------|
| `maxConcurrentInstances` | 32 | Live (non-lingering, non-admin-build) dungeon instances. |
| `maxConcurrentVisits` | 16 | Read-only visit copies of other players' rooms. |
| `maxConcurrentPreviews` | 16 | Door-preview cells standing at once. |

Caps are enforced before any fuel or catalyst is charged. A player
switching doors does not count against the preview cap: their own existing
preview is reused or purged, so the net footprint does not grow. Joining an
existing visit copy or the owner's own live room never creates a slot, so
those paths are not gated by the visit cap.

### Diagnostics

`/dungeon admin diagnostics` (game-master permission) prints:

- `instances: N/CAP`, `visits: N/CAP`, `previews: N/CAP` against the
  declared caps.
- `queued clears: N` (pending teardown work).
- `used slots: N` (allocated slots, including admin builds).
- `heap: N MB used / M MB max`.
- `dungeon chunks force-loaded: N` and `dungeon entities (excl. players): N`
  when the dungeon dimension is loaded.

No player-facing spam, no external telemetry. This is the command an
operator runs instead of attaching a profiler.

### Load test

`./gradlew dungeonLoadTest` boots a real dedicated server (the same route
as `dungeonIntegrationTest`, so `pocketdungeons:void` loads for real) and
drives concurrent instances through it, reporting generation latency,
clear latency, steady tick duration (p50/p95/p99), heap and forced-chunk
footprint. `./gradlew dungeonLoadTestSoak` is the distinct long-soak
invocation with wider defaults (32 instances, 20 cycles, 1000 steady
ticks).

Profiles are overridable from the command line without a code change:

```
./gradlew runDungeonLoadTest -PloadtestInstances=16 -PloadtestCycles=3 -PloadtestSteadyTicks=400
```

The load test drives instances via `Instances.adminBuild`, which stamps
the same procedural layout the real `/dungeon` path stamps. It bypasses
the M76 instance cap on purpose: it measures raw headroom, not the cap's
refusal behaviour. A real `/dungeon` run goes through a lobby and a door
choice, so its per-run world work is a subset of what this measures.

### Setting the caps on your own hardware

The default caps are measurement points from one declared JVM and world,
not promised capacity. A mixed-mod server's real ceiling depends on its
own hardware, population and other mods. To set your own caps:

1. Run `./gradlew dungeonLoadTest -PloadtestInstances=N` sweeping `N`
   up until steady tick p95 approaches 40 ms or p99 approaches 50 ms.
2. Set `maxConcurrentInstances` to the largest `N` that stayed inside
   that threshold.
3. Repeat for visits and previews if your server is visit- or
   preview-heavy; the load test does not drive those paths, so an
   operator should set them conservatively and raise them only with
   `/dungeon admin diagnostics` watching the live counts.

## Content modules (L2, D41)

A content module is a named, toggleable bundle of optional content. Each
module is a file at `data/<namespace>/content_module/<name>.json`:

```json
{
  "id": "alchemy",
  "label": "Alchemy",
  "description": "Nether wart, blaze powder and the brewing stand.",
  "default": false,
  "loot": {
    "chests/tier_2": "modules/alchemy/chests/tier_2",
    "chests/supply_tier_2": "modules/alchemy/chests/supply_tier_2"
  },
  "allow": ["nether_wart", "blaze_powder", "blaze_rod", "magma_cream", "glass_bottle"],
  "stations": ["brewing_stand"],
  "merchantStock": ["nether_wart"],
  "bags": []
}
```

The fields:

| Field | Effect while the module is on |
|---|---|
| `loot` | target table to module table. Each roll of the target also rolls the module table and merges the drops. Module tables live under `loot_table/modules/<module>/`, so a disabled module's tables stay loaded but never roll |
| `allow` | item ids added to the loot allow list. The loot rule check validates a module table against the core allow list plus its own `allow` |
| `stations` | station block names gated on the module; a claimed station refuses politely while the module is off |
| `merchantStock` | item ids merchant stock may list only while the module is on |
| `bags` | bag ids that exist only while the module is on; a disabled module's bags are skipped at bag-manifest load |

`"id"` must resolve to the file's namespaced path, `"default"` is the state
used when the config names no override. Overrides live in
`pocketdungeons.json` under `"modules"`:

```json
"modules": {
  "pocketdungeons:alchemy": true
}
```

A missing entry means the manifest default; a non-boolean value is ignored
with a warning; an override naming a module no manifest declares has no
effect and is warned about at content reload. Operators can list and toggle
modules at runtime:

```
/dungeon admin modules
/dungeon admin module alchemy on
/dungeon admin module alchemy off
```

Toggling writes `pocketdungeons.json`. Loot changes apply to the next roll
immediately; manifest surfaces (module bags, gated stations) settle on the
next `/reload`. The shipped modules are `alchemy`, `trims`, `redstone`,
`gardening`, `decor` and `extra_bags`, all defaulting off.
