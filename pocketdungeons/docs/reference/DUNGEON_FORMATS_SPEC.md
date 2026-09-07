# Dungeon Formats: a run with a different win condition

> Design spec. Status: proposal, nothing here is built. Companion to
> `VISION.md` (what the mod is for), `SITUATIONS_SPEC.md` (what goes inside a
> run's cells), and `NEXT_ROADMAP.md` (the milestone sequence this extends).
> This document covers what changes when the dungeon itself has a different
> shape, win condition, and pressure source than the standard floor loop.

---

## 0. The problem this solves

The shipped dungeon is one format: a self-avoiding walk of rooms on a flat cell
grid, completion gated by spawner-clear percentage, pressure from a run-level
clock. The three doors offer three difficulty levels of that one format.
Replay value comes from theme, affix, and room composition, not from the
dungeon's shape or objective.

Players who run the same format repeatedly report monotony. The composition
space (themes, affixes, recipes) adds variety to the same underlying activity,
but the activity itself never changes: walk forward, clear spawners, reach the
terminal, go home.

Warframe solved this with format variety. Survival, Defense, Excavation,
Interception, Spy, Exterminate, Mobile Defense, Assassination, Defection,
Index, and Sabotage are all the same engine wearing different win
conditions and pressure sources. The tilesets are shared. The combat is shared.
What changes is what the player is trying to do and what is pushing back.

Pocket Dungeons can do the same thing. The cell-grid engine, the room
templates, the trial spawners, the vaults, the loot tables, the pressure
handlers, the custody system, and the homecoming trick are all reusable. What
changes per format is:

1. **Generation**: how the dungeon is laid out (cell grid, open field, linear
   chain, single arena).
2. **Win condition**: what completes the run (reach terminal, clear all
   spawners, kill a target, defend a point, survive N sets, collect N items).
3. **Pressure source**: what pushes back (clock, wave escalation, resource
   attrition, alarm state, target movement).
4. **Phase structure**: how many distinct stages the run has (one continuous
   floor, repeated sets, scripted sequence, two-phase assault).

---

## 1. Vocabulary

- **Format**: the dungeon's shape, win condition, pressure source, and phase
  structure. Distinct from theme (what it looks like) and affix (what rules
  bend). A door offer composes level + affix + theme + format.
- **Standard format**: the shipped floor loop. Three floors per safe-visit
  interval, spawner-clear gate, terminal exit, silent homecoming. The first
  format; not the only one.
- **Set**: a unit of play in formats that repeat. One set is one floor batch
  in Endless Mine, one wave batch in Village Defense, one round in Collection.
- **Checkpoint**: a moment between sets where the player chooses to continue
  or extract. The format's equivalent of the standard format's terminal pad.
- **Phase**: a distinct stage in a scripted format. The Mansion Assault has
  five phases (courtyard, breach, floor 1, floor 2, floor 3). The standard
  format has one phase (active).
- **Format transition table**: the set of legal state transitions for a
  format, replacing the standard format's floor-loop transitions in
  `RunSession`.

---

## 2. Engine constraints this spec lives inside

Taken from `RoomGeometry`, `SITUATIONS_SPEC` section 2, and the current
working tree:

- A cell is 16 x 16, walls 5 high, ceiling at Y=6. Interior is 14 x 14 x 5.
- `STORY_HEIGHT` is `CEILING_Y + 2` = 9. A `spanY: 2` room owns the 16 x 16 x 9
  volume beneath its cell. This is private volume, not a graph-connected
  vertical edge.
- The layout graph is strictly 2D. The door mask is 4 bits (N, E, S, W). There
  is no UP door direction. `LayoutPlanner`, `RoomSelector`, and `DungeonShape`
  do not reason about vertical adjacency.
- The dimension has no skylight (`has_skylight: false`, `ambient_light: 0.0`).
  Daylight sensors are dead. Everything else works.
- Server-side only. No custom blocks, items, or client mod. Redstone, water,
  lava, mob behaviour, and item components are the entire toolbox.
- The slot system (`allocateSlot`, `originForSlot`, `InstanceRegistry`)
  assumes compact cell-grid instances. Larger footprints need wider slot
  spacing or a separate allocation pool.
- `TemplateStamper` stamps captured NBT templates. `RoomStore.capture` and
  `RoomStore.place` capture and restore room blobs. Both work at arbitrary
  scales, not just 16 x 16.
- Trial spawner configs accept any entity with NBT. Chest and vault loot
  tables are plain vanilla tables and can set components.
- `RunSession` (proposed in M65) provides explicit phase transitions. A
  format declares its own transition table; the standard format's transitions
  become one implementation of that contract.
- The homecoming trick (capture, clear, re-stamp behind a closed door, no
  message, sound, or loading screen) applies to every format. The room moves
  silently regardless of what the player was doing when the run completed.

---

## 3. The format contract

Every format declares:

| Field | Description |
|---|---|
| `id` | Namespaced identifier, same pattern as room/affix/theme IDs. |
| `generation` | How the dungeon is laid out. One of: `cell_grid` (standard), `open_field` (stamped terrain plus structures), `linear_chain` (sequential single rooms), `single_arena` (one room or cell). |
| `footprint` | The instance's chunk footprint. Determines slot spacing and force-load bounds. |
| `phases` | The format's transition table. Each phase has an entry condition, an exit condition, and a failure condition. |
| `winCondition` | What completes the run. Evaluated by the format, not the standard terminal-pad check. |
| `pressureSource` | What pushes back. May reuse existing handlers (`RisingLavaHandler`, `CollapsingBridgeHandler`, `Locks`, `OmenSources`) or declare format-specific pressure. |
| `homecoming` | How the silent room relocation applies. Always silent, always physical, but the trigger point and staging cell reservation may differ per format. |
| `extractionEligible` | Whether the random extraction event (section 4.13) may fire on this format. True for formats with a lodestone exit pad; false for formats that complete on a non-pad win condition or that already have their own end-phase pressure. |

The standard format is the first implementation:

- `generation`: `cell_grid`
- `footprint`: current cell grid (9 to 24 chunks)
- `phases`: HOME, PREVIEW, ACTIVE, FLOOR_CLEARED, SAFE_RETURN, RECOVERY
- `winCondition`: player stands on terminal exit pad after spawner-clear gate
- `pressureSource`: omen bands (post-M65), Pocket2 countdown (Pocket2 only)
- `homecoming`: room stamped behind the final staging door, opened for walk

A format does not declare its own theme, affix, or bag. Those compose
orthogonally on the door offer, same as the standard format. A format may
restrict which themes or affixes are eligible (some formats do not make sense
with certain affixes), but it does not own them.

---

## 4. The format catalogue

### 4.1 Standard (shipped)

The floor loop. Three floors per safe-visit interval, spawner-clear gate,
terminal exit. Detailed in `SITUATIONS_SPEC` sections 5 and 12. This spec does
not repeat it.

**Claustrophobic flavour:** a theme variant of the standard format where room
templates ship with lower ceilings and narrower corridors. The cell geometry
is unchanged (16 x 16, walls 5 high, ceiling at Y=6): the cell contract stays
intact, door masks stay 4 bits, and the planner does not change. The
difference is entirely in the room templates. Interior ceilings sit at Y=3 or
Y=4 instead of Y=5, with wall material filling the space above. Corridors and
doorways narrow to 2 wide by 3 tall instead of the default 3 to 4 wide,
matching the doorway plane's own footprint (`RoomManifest.deriveMask` reads
the door jigsaws at y=1..3, z=7..8; see `DISCOVERIES.md` trap 20). A Y=3
interior ceiling then has no dead space above the door: the corridor is the
doorway, carried through the room. Rooms are smaller, tighter, and more
oppressive.

This is a theme, not a format. It uses the standard format's win condition,
pressure source, and phase structure. The only thing that changes is how the
rooms look and feel. A claustrophobic theme ships its own room templates
(`dungeon_room/*.json` entries with narrower interiors and lower ceilings)
and its own processor list (if palette changes are needed). The 53-pair
coverage floor applies: enough claustrophobic tiles must exist to cover every
mask/role combination.

The pressure shift is atmospheric, not mechanical. Lower ceilings mean less
room to dodge projectiles. Narrower corridors mean mobs are harder to bypass.
The spawner-clear gate and omen system are unchanged, but the physical space
makes the same encounters feel more cramped and dangerous.

### 4.2 Endless Mine (Survival)

> Warframe parallel: Survival.

Complete a set of rooms. A checkpoint offers continue or extract. Choosing
continue locks the choice: the player must complete another set. Each set is
harder than the last. There is no compulsory final floor.

**Generation**: `cell_grid`, same as standard. Each set is one floor batch.

**Phases**:
1. ACTIVE: the current set is live. Spawner-clear gate applies.
2. CHECKPOINT: the current set is cleared. The player stands on a checkpoint
   pad. Two options: continue or extract.
3. ESCALATION: if continue, the next set generates at a higher tier (roster
   scaling, affix intensity, omen band). The previous set's cells are
   released. Previous floors cannot be revisited.
4. EXTRACT: if extract, or if the player fails, the run completes and
   homecoming applies.

**Win condition**: voluntary extraction at a checkpoint. There is no forced
completion. The reward scales with the number of sets cleared before
extraction.

**Pressure source**: resource attrition across sets. No per-set timer. The
player's tools, food, and durability carry forward. Omen accumulates across
sets and clears only on extraction.

**Memory bound**: only the current set, the checkpoint, and bounded prepared
work for the next set are loaded. Previous sets are torn down immediately on
escalation. This is the bounded-memory rule from `NEXT_ROADMAP.md` M78.

**Homecoming**: on extraction, the room is stamped behind the checkpoint's
exit door. Same silent relocation as the standard format.

**Reward**: a displayable Mine record or material, not a higher permanent
keystone level. The Mine's value is measured against the standard loop to
prevent it becoming the only sensible supply route.

**Distinct from standard because**: no fixed endpoint, no retained history,
voluntary exit, escalating composition, bounded memory. The standard format's
three-floor interval is a fixed contract; Endless Mine is an open-ended
contract with a voluntary exit.

---

### 4.3 Mansion Assault (scripted raid)

> Warframe parallel: Assassination. Vanilla parallel: Woodland Mansion plus
> Pillager Raid.

A walled courtyard with a mansion at the far corner. The player must kill
every target: generals in the courtyard, evokers in the mansion, and the
head pillager at the top. No gating, no sealed doors, no phase locks. The
player chooses the order. The run completes when the last target dies.

Detailed in section 5 below.

---

### 4.4 Village Defense (Defense)

> Warframe parallel: Defense. Vanilla parallel: Village Raid.

The dungeon is a village: a cluster of small structures with villagers
living in them. Waves of enemies spawn at the perimeter and path toward the
villagers. The player must defend the villagers. If all villagers die, the
run fails. Survive N waves with at least one villager alive.

**Generation**: `open_field`. A static village template stamped at the
instance origin, same approach as Mansion Assault's courtyard. The village
is the same every time; the player learns it. Houses, paths, lamp posts, and
villager beds are placed at fixed positions. The village is small: 3 to 5
houses, 4 to 6 villagers, fitting in a 3 x 3 chunk area.

**Phases**:
1. PREP: before each wave. The player has a short window to position, check
   supplies, and choose which houses to defend. Villagers retreat indoors.
2. WAVE: enemies spawn at the village perimeter and path toward the
   villagers. The player must intercept and kill them before they reach the
   houses. Each wave is harder: more mobs, more dangerous types.
3. CHECKPOINT: after N waves, a checkpoint offers continue or extract, same
   as Endless Mine. Continuing starts the next wave at higher difficulty.

**Win condition**: survive N waves with at least one villager alive, or
voluntary extraction at a checkpoint.

**Pressure source**: the villagers are the stakes. The player cannot just
survive; the villagers must survive. A villager that dies is gone for the
rest of the run. More survivors at extraction means a better reward. The
player must split attention between multiple houses as waves grow.

**Failure condition**: all villagers dead. The run ends as a failure, same
as ejection. This is the only format with a failure condition beyond player
ejection, because the villagers are the objective and they cannot be
replaced.

**Vanilla fit**: villagers are vanilla entities with vanilla pathing
(retreat to beds at night, which the wave phase can simulate by forcing a
"night" state). Enemies are pillagers, vindicators, and ravagers, same as
vanilla raids. Wave spawning uses trial spawners placed at the perimeter.
The village template is a captured NBT structure, same as Mansion Assault's
courtyard. No custom blocks, items, or client assets.

**Reward**: scales with surviving villager count. A perfect defense (all
villagers alive) pays the most. This incentivises the player to protect
every house, not just one.

**Distinct from standard because**: the player defends a fixed position with
NPCs at stake, rather than exploring a dungeon. The village is learned; the
wave composition is the variety. The failure condition (all villagers dead)
is unique to this format.

---

### 4.5 Burglary (Spy)

> Warframe parallel: Spy.

Rooms contain sculk-sensor alarm networks. Triggering them locks vaults or
spawns reinforcements. The goal is to reach specific vault rooms and extract
their contents without triggering alarms.

**Generation**: `cell_grid`, same as standard.

**Phases**: same as standard, but the floor-clear gate is "all vaults opened"
rather than "spawner-clear threshold."

**Win condition**: open all vaults in the current floor, then reach the
terminal.

**Pressure source**: alarm state. A triggered alarm degrades the run
permanently: vaults lock, reinforcements spawn, and the alarm cannot be
cleared. The player must avoid sculk sensors, tripwires, and shriekers.

**Vanilla fit**: sculk sensors, sculk shriekers, and tripwires are all
vanilla server-side. Locking containers is already done through
`RitualListener`. The alarm state is a per-run boolean, in-memory.

**Distinct from standard because**: stealth and careful movement matter more
than combat speed. The player can fail without dying: a triggered alarm
degrades the reward but does not end the run.

---

### 4.6 Excavation

> Warframe parallel: Excavation.

Find and charge specific blocks (crying obsidian or respawn anchors needing
fuel). Defend each while it charges. Extract the reward when it completes.
Multiple excavations can run concurrently, forcing the player to choose how
many to attempt at once.

**Generation**: `cell_grid`, same as standard. Objective blocks placed in
specific cells.

**Phases**: same as standard, but the floor-clear gate is "all excavations
completed."

**Win condition**: all objective blocks on the current floor fully charged,
then reach the terminal.

**Pressure source**: splitting attention between active excavations and
managing fuel supply. Each excavation needs fuel items delivered, then runs
on a timer while enemies spawn to contest it.

**Vanilla fit**: respawn anchors take crying obsidian charges as fuel.
Crying obsidian is already in the mod's tier-2 palette. Enemy spawning
during charge is trial spawners. The charge state is a per-block counter,
server-side.

---

### 4.7 Collection (Index)

> Warframe parallel: Index.

Enemies drop tokens (vanilla items with custom data). Deposit them at a
central station to score. Carrying tokens marks the player for more
aggressive spawning. Dying drops carried tokens.

**Generation**: `cell_grid`, same as standard. A central deposit station is
placed in the entrance or staging cell.

**Phases**: same as standard, but the floor-clear gate is "score threshold
reached."

**Win condition**: deposit enough tokens to reach the score threshold, then
reach the terminal.

**Pressure source**: risk-reward on carrying more tokens versus banking
often. Carrying N tokens increases spawn rate or aggression. Dying (ejection)
drops carried tokens on the ground.

**Vanilla fit**: token items use `CUSTOM_DATA` on gold nuggets or emeralds,
same minting pattern as keystones and calling cards. Deposit is
`UseBlockCallback`, already registered. Spawn scaling reads the carried count.

---

### 4.8 Sabotage (two-phase assault)

> Warframe parallel: Sabotage.

Reach a core room, destroy or activate something, then escape before the
dungeon collapses. The exit path degrades after sabotage: blocks fall, lava
rises, or passages close.

**Generation**: `cell_grid`, same as standard.

**Phases**:
1. INFILTRATION: navigate to the core room. Standard spawner-clear and
   exploration pressure.
2. SABOTAGE: interact with the core (a placed block). This triggers the
   collapse.
3. ESCAPE: the return path is degraded. `RisingLavaHandler` or
   `CollapsingBridgeHandler` is active on the path back to the entrance.
   The player must reach the entrance cell, not the terminal.

**Win condition**: reach the entrance cell after sabotage.

**Pressure source**: time pressure only in phase 3. Phase 1 is exploration;
phase 3 is panic.

**Vanilla fit**: both pressure handlers already exist. The two-phase state is
a per-run phase enum, same shape as the proposed `RunSession` transitions.

**Distinct from standard because**: the exit is the entrance, not the
terminal. The player retraces their path under collapse pressure. The dungeon
fights back on the way out, not on the way in.

---

### 4.9 Hunt (roaming target)

> Warframe parallel: Assassination, with a roaming target.

One elite mob roams the dungeon. The player must track it down and kill it.
It moves between rooms on a timer or when damaged. The room you fight it in
is the room you catch it in, so the dungeon's hazards affect the fight.

**Generation**: `cell_grid`, same as standard.

**Phases**: same as standard, but the floor-clear gate is "target killed."

**Win condition**: kill the target mob, then reach the terminal.

**Pressure source**: pursuit under resource constraint. The player cannot
just camp; they have to chase. The target relocates every N ticks or on
damage threshold.

**Vanilla fit**: the mob is a vanilla entity with attribute modifiers (the
mod already does mob scaling). The roaming handler follows the same pattern
as `RisingLavaHandler` and `CollapsingBridgeHandler`. The target may need
teleportation between cells rather than vanilla pathing to be reliable.

---

### 4.10 Scavenger Hunt

> No direct Warframe parallel.

Specific items are hidden across rooms. Collect all to unlock the exit. The
items are vanilla items identifiable by name or custom data, but there is no
UI listing them. The player must explore and recognize what to take.

**Generation**: `cell_grid`, same as standard.

**Phases**: same as standard, but the floor-clear gate is "all required items
collected."

**Win condition**: all required items in the player's inventory, then reach
the terminal.

**Pressure source**: exploration and knowledge. Combat is secondary. The
challenge is finding and identifying the right items among decoys.

**Vanilla fit**: items use `CUSTOM_DATA` and `CUSTOM_NAME`. The gate is an
inventory scan, same pattern as `InventorySwap`'s tag filtering. Items are
placed in containers or as item frames in specific cells.

---

### 4.11 Extraction (random event)

> Warframe parallel: Extraction. No direct vanilla parallel.

A random event that fires on formats with a lodestone exit pad. When the
player steps on the pad expecting to complete the run, there is a chance the
pad does not fire immediately. Instead, the extraction event begins.

**What the player experiences:**

The player steps on the lodestone. Instead of the run ending, a boss-bar
timer appears: "Extraction under way." Hordes of enemies stream in from the
dungeon perimeter. The player must survive for 30 seconds. When the timer
reaches zero, the lodestone activates and the player can pull the lever (or
step off and back on) to complete the run. Homecoming applies as normal.

If any party member is not on the pad when the timer finishes, the
extraction does not complete. All members must be present, same as the
standard format's pad check. The party must survive together.

**What makes this a surprise:**

The event is random, not guaranteed. Most runs end normally: the player
steps on the pad, the run completes, the room is behind the door. But some
runs do not. The player cannot predict which. The relief of finishing turns
into panic without warning. The player who assumed they were done must now
fight for the exit.

**Mechanics:**

- **Trigger**: when the player steps on the exit pad and the format's win
  condition is met, roll against the extraction probability. If the roll
  fails, the run completes normally. If it succeeds, the extraction event
  begins instead.
- **Timer**: 30 seconds, shown as a boss bar. The timer does not start until
  the event begins, not when the player entered the dungeon.
- **Spawning**: trial spawners in the dungeon reactivate or new spawners
  activate at the perimeter. The enemies are from the same theme's roster.
  Spawn rate escalates during the 30 seconds. The player is not expected to
  clear them; the player is expected to survive them.
- **Completion**: when the timer reaches zero and all party members are on
  the pad, the run completes. If a member is off the pad, the timer waits
  (same as the standard pad check). If the player steps off the pad during
  the timer, the timer pauses, not resets. Enemies keep spawning.
- **Failure**: there is no extraction failure state. The player cannot fail
  the extraction; they can only die (ejection, no death in PD). If ejected,
  the run ends as a failure, same as any other format. The extraction does
  not add a new failure condition.

**Generation**: none. The extraction event happens in the dungeon the player
already cleared. No new rooms, no new layout. The spawners are the same
spawners the player already defeated, reactivated, or new perimeter spawners
placed at generation time but dormant until the event fires.

**Pressure source**: horde survival. The player is not clearing rooms or
hunting targets. The player is surviving a timed onslaught in a space they
already cleared. The pressure is the timer and the spawn rate, not resource
attrition or routing.

**Vanilla fit**: the boss bar is the existing `ServerBossEvent` used by the
run timer. The spawners are trial spawners with the same configs the dungeon
already uses. The lodestone pad check is the existing `isOnExitPad` logic.
The extraction probability is a per-run roll, in-memory. No new blocks,
items, or client assets.

**Tuning:**

- **Probability**: not every run. The event should be uncommon enough to be
  a surprise, not so rare it never happens. A baseline of 1 in 4 to 1 in 3
  runs keeps it unpredictable without making it routine. This is a config
  value, not a hardcoded constant.
- **Timer**: 30 seconds is the baseline. Longer at higher keystone levels
  adds pressure to high-level play. This is a per-level parameter.
- **Spawn rate**: escalates during the 30 seconds. The escalation curve is
  a config value. The goal is "survivable but scary," not "guaranteed wipe."
- **Party scaling**: spawn count scales with party size, same as the
  existing mob scaling in `Instances`.

**Which formats are eligible:**

Formats with a lodestone exit pad where all party members gather to complete:
- Standard: yes. The pad is the terminal exit.
- Endless Mine: yes, at the checkpoint pad.
- Mansion Assault: no. The win condition is target death, not a pad. The
  extraction event does not apply.
- Village Defense: no. The win condition is wave survival with NPCs at
  stake. Extraction would be a second survival phase on top of the first.
- Burglary: yes. The pad is the terminal exit.
- Excavation: yes. The pad is the terminal exit.
- Collection: yes. The pad is the terminal exit.
- Sabotage: no. The escape phase is already an extraction. Adding a random
  extraction on top would be redundant.
- Hunt: yes. The pad is the terminal exit after the target is killed.
- Scavenger Hunt: yes. The pad is the terminal exit.

A format declares `extractionEligible: true` or `false` in its contract. The
event only fires on eligible formats.

**What this is not:**

- Not a format. It is an event that fires on top of an eligible format. It
  does not change the format's generation, win condition, or pressure source
  before the event fires.
- Not a guaranteed phase. Most runs end normally. The event is a surprise,
  not a routine.
- Not a new failure condition. The player cannot fail the extraction; they
  can only be ejected, same as any other point in the run.
- Not a new UI. The boss bar is the existing timer bar. The lodestone is the
  existing exit pad. The lever is a vanilla lever.

---

## 5. Mansion Assault (full design)

> Warframe parallel: Assassination. Vanilla parallel: Woodland Mansion plus
> Pillager Raid.

### 5.1 The experience

The player steps through the door and arrives in a corner of a walled
courtyard. Across the courtyard, in the opposite corner, a mansion rises
behind closed doors. Between the player and the mansion, tents and campfires
dot the field. Pillagers patrol.

The player crosses the courtyard, enters the mansion, and kills every
target: the generals in the courtyard camps, the evokers inside the mansion,
and the head pillager at the top. The order is the player's choice. Nothing
is locked, sealed, or gated. The mansion doors are open from the start. The
staircases are walkable from the start. The player can run straight to the
top if they want, but every target must die to complete the run. The run
completes when the last target falls. The room is waiting behind the final
door, silently.

### 5.2 Generation

The instance is a 10 x 10 chunk (160 x 160 block) area with four regions:

| Region | Size | Generation |
|---|---|---|
| Castle walls | Perimeter, 1-2 blocks thick | Static NBT template, stamped once |
| Courtyard | Interior, minus the mansion footprint | Static NBT template, stamped once |
| Mansion exterior | 4 x 4 cells (64 x 64 blocks) | Static NBT shell, stamped once |
| Mansion interior | 4 x 4 cells per floor, 3 floors | Procedural, cell-grid engine with mansion tiles |

The courtyard is the same every time. The player learns it: where the tents
are, which general is dangerous, which path to the mansion doors is safest.
This is mastery of a fixed space, like learning a Warframe tileset. The
mansion interior is the procedural variety on top of that fixed foundation.

The courtyard template includes:
- Flat terrain (grass or coarse dirt, dark as the dimension has no skylight).
- Tent structures at fixed positions, each with a trial spawner cluster and a
  named general mob (a buffed pillager or vindicator with a custom name).
- Campfires, hay bales, and supply crates for cover and atmosphere.
- The castle wall perimeter with a gate facing the mansion doors.
- The mansion exterior shell: dark oak walls, an open front door, and the
  visible upper floors.

The mansion interior uses the existing cell-grid engine:
- 4 x 4 cells per floor, same 16 x 16 cell size, same door masks, same room
  selection.
- Mansion-themed room templates (dark oak walls, stone floors, carpeted
  rooms, bookshelves, chandeliers, cobwebs). These are new `.nbt` templates
  and `dungeon_room/*.json` entries, not new engine code.
- The 53-pair coverage floor applies: enough mansion tiles must exist to
  cover every mask/role combination the 4 x 4 layout can produce.
- A mansion theme data file defines the roster (vindicators, pillagers,
  evokers), processors (dark oak palette), and loot tables.

### 5.3 Stacked floors

The mansion has three floors. Each floor is an independent 2D layout graph
generated by the existing planner. Floors are stacked vertically, not
connected by a 3D graph edge.

**Floor placement:**
- Floor 1: stamped at the mansion's base Y (the courtyard ground level).
- Floor 2: stamped at base Y + `STORY_HEIGHT` (9 blocks higher).
- Floor 3: stamped at base Y + 2 * `STORY_HEIGHT` (18 blocks higher).

**Staircase tiles:** a special room template contains a staircase leading up.
The player walks up it to reach the next floor. No trigger plate, no
activation condition. The next floor is already generated and stamped when
the instance starts; the staircase is just a physical connection between
floors. This is simpler than the `spanY` mechanism: `spanY` adds private
volume below a cell; stacked floors add independent plans above. The stamping
machinery (`TemplateStamper`, `LayoutStamper`) already handles Y offsets
through `storyOffset`. The only new work is generating three plans at
different Y levels in one instance.

**Why not a 3D layout graph:** the format does not need the planner to reason
about vertical edges. The three floors are independent plans. The player
moves between them on staircases, which are physical connections, not graph
edges. A 3D graph would change the door mask, the planner, and the selector
for one format's benefit. That scope is not justified here.

### 5.4 Phase structure

The format has two phases:

| Phase | Entry | Exit | Failure |
|---|---|---|---|
| ACTIVE | Instance created | All targets killed | Player ejected (no death in PD) |
| COMPLETE | All targets killed | Homecoming applies | N/A |

No phase gates. No sealed doors. No trigger plates. No spawner-clear
requirements. The player is free to move through the entire instance from the
moment it starts: courtyard, all three mansion floors, all staircases. The
only thing that completes the run is killing every target.

**Targets:**
- Generals: 3 to 5 named, buffed pillagers or vindicators, one per tent camp
  in the courtyard.
- Evokers: named evokers placed in specific rooms on floor 2.
- Head pillager: one named, buffed mob in the top room on floor 3.

The format tracks target deaths. When the last target dies, the run
completes.

### 5.5 Win condition

Kill every target. When the last target dies, `completeRun` fires, the room
is captured from its current cell, and re-stamped behind the final door. The
player walks into their own room. No teleport, no sound, no message. The
homecoming trick applies unchanged.

### 5.6 Pressure source

The format has no run-level timer and no gating. Pressure comes from two
sources:

1. **Courtyard exposure**: crossing open ground under ranged pillager fire
   with limited cover. The player can skip the courtyard and run for the
   mansion, but the generals must die eventually to complete the run.
2. **Resource attrition across three floors**: tools, food, and durability
   carry forward. There is no safe room between mansion floors. The player
   chooses the order: clear courtyard first, rush the mansion first, or
   alternate. Every target must die regardless of order.

### 5.7 Content requirements

| Content | Type | Count |
|---|---|---|
| Courtyard template | Captured NBT, stamped once | 1 (static) |
| Castle wall templates | Captured NBT, stamped per edge | 2 (straight, corner) |
| Mansion exterior shell | Captured NBT, stamped once | 1 (static) |
| Mansion room templates | Authored `.nbt` + `dungeon_room/*.json` | 14+ for 53-pair coverage |
| Staircase room template | Authored `.nbt` + `dungeon_room/*.json` | 1 (special, physical staircase between floors) |
| Mansion theme | `dungeon_theme/*.json` | 1 |
| Mansion adventure node | `dungeon_adventure/*.json` | 1 |
| General mobs | Named, buffed vanilla entities | 3 to 5 (one per tent camp) |
| Head pillager | Named, buffed vanilla entity | 1 (boss) |
| Mansion loot tables | `loot_table/themes/mansion/*` | Per tier |

All content is vanilla blocks, items, and entities. No custom registrations.
The mansion theme uses vindicators and pillagers as the base roster, evokers
as named targets on floor 2, and the head pillager as the floor-3 boss.

### 5.8 Engine work required

| Piece | New or existing | Effort |
|---|---|---|
| Courtyard + walls + mansion shell stamping | Existing (`TemplateStamper`) | None; stamp a larger template |
| Mansion interior generation | Existing (cell-grid engine) | None; new tiles, same engine |
| Stacked floor stamping (three plans at different Y levels) | New, format-specific | Moderate: generate and stamp three independent plans in one instance |
| Target death tracking | New, format-specific | Small: per-run set of target UUIDs, checked on entity death |
| Larger slot allocation for 10 x 10 chunk footprint | New, registry-level | `InstanceRegistry` spacing change |
| General and boss mobs | Existing mob scaling | None; named entities with attributes |

The one piece of real engine work is stacked floor stamping: generating
three independent 2D plans and stamping them at different Y levels in one
instance. Everything else is content authoring or configuration on existing
machinery.

### 5.9 What this format is not

- Not a true woodland mansion. The mansion interior is the mod's cell-grid
  engine with mansion-themed tiles, not a vanilla jigsaw-generated mansion.
  The layout, room shapes, and door positions follow the mod's existing
  geometry contract.
- Not an outdoor format. The dimension has no skylight. The courtyard is dark
  (night raid atmosphere). A sky-lit courtyard requires the held outdoor
  dimension work.
- Not a 3D layout graph. Floors are stacked independent plans, not
  graph-connected vertical edges. The door mask stays 4 bits.
- Not a separate engine. The format shares the mod's dimension, slot
  registry, custody system, stamping, spawners, and homecoming. Only the
  generation path (courtyard stamp plus stacked floors) and the phase table
  are format-specific.

### 5.10 Replay value

The courtyard is fixed. The player learns it. The mansion interior is
procedural. The player does not know what is behind each door. The
composition space (theme, affix, recipe) applies to the mansion interior,
same as the standard format. A different affix on the same mansion layout
produces a different experience: Feral mansion has wolves in the halls,
Molten mansion has lava in the rooms, Voided mansion has blocked routes.

The scripted sequence (courtyard, mansion, boss) is the same every time.
The variety is in what the player finds inside the mansion and how the
composition space changes it. This is the same split as Warframe's
Assassination missions: the approach is learned, the interior is procedural,
the composition (loadout, mods, team) changes the experience.

The player's freedom of order adds replay variety on top of the procedural
mansion. One run the player clears the courtyard first, then climbs slowly
through all three floors. Another run they rush the mansion, kill the boss,
and clean up the courtyard last. The targets are the same; the route is the
player's choice.

---

## 6. Theme and room concepts

These are not formats. They are theme and room content ideas that compose
with any format. A theme defines palette, roster, processors, and loot. A
room is a template plus a `content` id. Both are data, not engine work.

### 6.1 Containment Lab (SCP laboratory)

> Tone: clinical, uncanny, containment-breach. Vanilla blocks only.

A theme where the dungeon is a research facility containing anomalous
exhibits behind observation glass. The aesthetic is clean and sterile: smooth
nether quartz walls, quartz pillars, white concrete floors, glass panes, iron
doors, and redstone lamps providing flat even lighting. The feeling is a
laboratory, not a dungeon.

**Room concept: observation cell.** A room whose central feature is a tall
glass enclosure (glass panes or glass blocks, 3 to 5 blocks high) containing
an exhibit. The player walks a narrow quartz corridor along the observation
window, looking in. The exhibit behind the glass is one of:

- A hostile mob in a sealed enclosure (a vindicator, a creeper, a warden
  enclosed in deepslate). The mob is visible but cannot reach the player
  unless the glass breaks.
- A strange block arrangement: a crying obsidian pillar, a spore blossom on
  moss, a soul sand patch with soul fire, a sculk catalyst surrounded by
  sculk sensors. Decorative, atmospheric, not hostile.
- A contained hazard: a lava tank, a water column, a powder snow pit. The
  glass contains it; breaking the glass releases it into the room.
- An empty cell with a broken glass panel and an open door. Something was
  here. It is not here now. The spawner in the corridor behind you activates.

**Room concept: breach corridor.** A corridor where one or more observation
cells have broken glass. The exhibits are loose. Hostile mobs from the
theme's roster patrol the corridor. The player must push through or sneak
past. Broken glass on the floor (glass panes laid flat as decoration, or
just absent panes where the enclosure failed) signals which cells breached.

**Room concept: containment control.** A room with levers and redstone lamps
that control doors to adjacent cells. Pulling a lever opens or closes a
containment door, releasing or sealing a mob. The player can use this
tactically: release a mob to fight another mob, or seal a mob to bypass it.
The levers are vanilla levers on iron doors, same redstone the mod already
uses.

**Theme definition:**

| Field | Value |
|---|---|
| Palette | Smooth nether quartz, quartz pillars, white concrete, glass panes, iron doors, redstone lamps, light gray concrete |
| Roster | Any vanilla mob, chosen for uncanny effect: creepers, vindicators, wardens (display only), skeletons, silverfish, endermites |
| Processors | Quartz palette replacement for standard stone/deepslate templates |
| Loot | Lab supplies: redstone, quartz, glass, iron, glowstone, occasional anomalous items (named vanilla items with custom data) |
| Lighting | Redstone lamps providing flat even light, no torches |

**As a theme on any format:** the Containment Lab theme applies to the
standard format, Endless Mine, or any cell-grid format. The rooms are
lab-themed observation cells, corridors, and control rooms. The win
condition and pressure source come from the format; the theme only changes
what the rooms look like and what is inside them.

**As a standalone dungeon concept:** a Containment Lab dungeon uses the
standard format (or a variant) with the lab theme throughout. The narrative
is implicit: the player has entered a facility where something went wrong.
Broken glass, empty cells, loose exhibits. No lore text explains this. The
environment tells the story the same way the silent homecoming tells its
story: through the space itself, not through messages.

**Vanilla fit:** every block is vanilla. Smooth nether quartz, quartz
pillars, glass panes, iron doors, redstone lamps, white concrete, light gray
concrete. Mobs are vanilla entities. Levers and iron doors are vanilla
redstone. No custom blocks, items, or client assets. The containment control
room uses the same redstone mechanics the mod already supports in its room
templates.

**The three-way test** (from `SITUATIONS_SPEC` section 0): the glass
enclosure is a hazard (breaking it releases the exhibit), a tool (the player
can break it to release a mob on another mob), and a resource (glass blocks
are mineable and carry forward). A containment cell passes the test.

**Coverage:** the lab theme needs its own room templates for the 53-pair
coverage floor, same as any theme. Observation cells, breach corridors, and
control rooms must cover every mask/role combination the layout can produce.
This is content authoring, not engine work.

---

## 7. Format selection on the door

A door offer composes level + affix + theme + format. The standard format is
the default. Other formats are offered when:

- The player has unlocked them through discovery (a recipe, a keystone level,
  or a completed-theme count, per `NEXT_ROADMAP.md` M71's discovery system).
- The format is eligible for the door's level and theme (some formats may
  have level or theme restrictions).
- The format's footprint fits an available slot (large-footprint formats like
  Mansion Assault need wider slot spacing).

The three doors may offer different formats at the same level, giving the
player a choice between a standard run, an Endless Mine, and a Mansion
Assault at the same difficulty. This is the variety the format system is for.

Format selection does not replace bag, theme, or affix selection. It is a
fourth axis on the same door. The player still picks a bag, still sees a
theme, still faces affixes. The format changes what they do with all three.

---

## 8. What stays the same across all formats

- **Server-side only.** No custom blocks, items, sounds, or client mod.
- **Vanilla content.** Trial spawners, vaults, mobs, blocks, items. No custom
  registrations.
- **The homecoming trick.** Every format ends with the silent room relocation.
  No teleport, no sound, no message, no loading screen. The room moves; the
  player walks into it.
- **Custody.** `InventorySwap` and the custody system (M63) apply to every
  format. No format can lose or duplicate a player's items.
- **The room.** The player's persistent, decoratable, owned room is the same
  regardless of which format they ran. The room is the product; the format is
  the activity.
- **The keystone.** One keystone, spent on a door. The format changes what
  the door leads to, not how the door is opened.
- **No death.** A killing blow ejects the player with inventory intact, same
  as the standard format. No format adds death.
- **Silence about room movement.** No format explains the trick. The
  advancement on first completion is the only acknowledgement, same as the
  standard format.

---

## 9. What this changes in the roadmap

The current roadmap has M78 as the single bounded rule-breaking dungeon
(Endless Mine), conditional on M77 adoption evidence. This spec proposes a
format system that subsumes M78 and adds more formats.

The roadmap impact:

1. **A format abstraction milestone** is needed before individual formats.
   The format contract (section 3) must exist before Mansion Assault or
   Endless Mine can be built as formats rather than engine forks. This is
   new work not currently in the roadmap. It depends on M65's `RunSession`
   transition model and M68's content contracts.
2. **Endless Mine becomes the first format** on the format contract, not a
   one-off exception. M78's scope is unchanged (Endless Mine, bounded memory,
   conditional on adoption) but its implementation is now "first format on
   the contract" rather than "one rule-breaking variant."
3. **Mansion Assault is a strong candidate for the second format** because it
   reuses the cell-grid engine with new content and only two pieces of new
   engine work (stacked floors, phase table). Its courtyard is a static
   template, so it does not require open-field generation.
4. **Other formats (Village Defense, Burglary, Excavation, Collection,
   Sabotage, Hunt, Scavenger Hunt) are content on the format
   contract**, not separate milestones. Each needs its own win condition,
   pressure source, and phase table, but shares the format abstraction.
5. **The outdoor dimension work stays held.** Mansion Assault's courtyard is
   dark, not sky-lit. A sky-lit courtyard or true outdoor format requires the
   held outdoor dimension from `NEXT_ROADMAP.md` section 5.

This spec does not schedule the format abstraction or individual formats. It
defines the contract and the catalogue so the owner can decide when and how
many to build. The first release (M77) does not require any format beyond the
standard. Formats are post-release variety, not release blockers.

---

## 10. Open questions for the owner

1. **Is format a fourth axis on the door, or a separate selection?** This
   spec assumes format composes on the door offer alongside level, affix, and
   theme. An alternative is a separate format selection screen before the
   door. The door composition is simpler and consistent with the existing
   design; a separate screen is more legible but adds UI.
2. **How are formats unlocked?** Discovery (M71's recipe discovery system),
   keystone level gates, or completed-theme counts? The recommendation is
   discovery: the player finds a recipe that opens a new format, same as
   finding a recipe that opens a new dungeon. This keeps the folklore dynamic
   intact.
3. **Should large-footprint formats (Mansion Assault) share the slot pool
   with standard runs, or have a separate pool?** Sharing is simpler but
   reduces concurrent capacity. A separate pool preserves capacity but adds
   registry complexity.
4. **How many formats ship before the format system is worth the abstraction
   cost?** One format (Endless Mine) can be a one-off. Two formats
   (Endless Mine plus Mansion Assault) justify the contract. Three or more
   make the contract essential. The recommendation is to build the contract
   with Endless Mine, then add Mansion Assault as the proof that the contract
   generalizes.
5. **Should formats restrict which affixes or themes are eligible?** Some
   combinations may not make sense (Feral Village Defense is wolves
   attacking a village, which may be trivial or impossible). The recommendation is to let formats
   declare eligibility restrictions, same as themes declare roster
   restrictions, rather than testing every combination.
6. **Does the homecoming trick need a different staging cell for
   large-footprint formats?** Mansion Assault's 10 x 10 chunk footprint is
   larger than the standard cell grid. The room must still be stamped behind
   a closed door the player walks through. The staging cell may need to be
   reserved inside the mansion (behind the boss room door) rather than at the
   instance origin. This is a format-specific homecoming detail, not a
   contract change.
