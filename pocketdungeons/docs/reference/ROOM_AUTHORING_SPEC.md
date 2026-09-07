# Room Authoring Spec: In-World Room Editor

> **What this is:** the design document for letting an operator create, author,
> and edit a room from scratch inside Minecraft, with direct control over
> every gameplay object: trial spawners, vaults, chests, spawn anchors, doors,
> and decor. The stamp-time pipeline is changed so authored blocks are the
> source of truth for placement, not just anchors that get replaced.
>
> **What this is not:** a build order or milestone assignment. The order and
> dependencies live in `ROOM_UX_PLAN.md` once this spec is approved.
>
> **Inspiration:** Portal 2's Puzzle Maker (PeTI). The PeTI lesson that
> matters here is: the author works in the same space the player will play,
> places real objects by pointing at the world, and can flip to a test run
> without leaving the editor. No external file editing, no jigsaw gymnastics,
> no "place a chest and hope the sorter picks it as the vault."

---

## 1. Vision and goals

### 1.1 End state

An operator with creative-mode-style permissions can, entirely from inside
Minecraft:

1. Open a fresh build shell sized to a 1x1 or 2-story cell.
2. Place and remove blocks freely in the interior, including gameplay objects:
   trial spawners, vaults, chests, supply chests, spawn anchors, omen sources,
   door jigsaws, and decor.
3. Set per-object properties (tier, ominous flag, loot table override, spawner
   config prefix, mob type) through an in-world UI, not by editing JSON.
4. Set room-level metadata (roles, footprint, weight, tier, content situation,
   access, window, spanY) through a chest GUI, not by editing JSON.
5. Validate the room against the stamp pipeline's expectations and get a
   readable list of problems before saving.
6. Save the room, overwriting the previous version, with an automatic backup.
7. Optionally test-stamp the room: run it through the real stamp pipeline at a
   chosen tier and theme, walk through it as a player would, then return to
   the editor with the room intact.
8. Publish the room to the manifest so the dungeon planner can select it.

### 1.2 What changes from today

Today the editor can only place *anchors*: a `pocketdungeons:spawn` jigsaw
hints where a trial spawner should go, and the first sorted chest hints where
a vault should go. The stamp-time code in `TrialContent` replaces or creates
the real blocks and applies per-run configuration. The author never sees the
real block in the editor and cannot place multiple spawners or multiple
vaults in one room.

After this spec, the author places the real blocks. The stamp-time code
*reconfigures* them in place (applies the run's tier, ominous state, loot
table, spawner config) but does not move them, does not replace them, and
does not invent positions when the author already provided them.

### 1.3 Non-goals

- This spec does not change the dungeon planner, the graph layout, or how
  rooms are selected. Room metadata fields stay the same shape.
- This spec does not add new gameplay object types. It makes the existing
  ones (trial spawner, vault, chest, supply chest, spawn anchor, omen
  source) directly authorable.
- This spec does not replace the Java-authored `BaseRoomSpecs` rooms. Those
  stay as the shipped defaults. The editor is for operator-authored and
  community-authored rooms on top of the defaults.

---

## 2. Design principles (Portal 2 PeTI lessons)

### 2.1 WYSIWYG: what you place is what plays

In PeTI, you place a button, and the button is there when you test. You do
not place a "button anchor" that gets replaced by a real button at test
time. The pocketdungeons editor follows the same rule: the author places a
`minecraft:trial_spawner` block, and that block is the trial spawner the
player fights. The author places a `minecraft:vault` block, and that block
is the vault the player loots.

The stamp-time pipeline is allowed to *reconfigure* authored blocks (set the
spawner's config id, set the vault's loot table, apply the ominous
blockstate). It is not allowed to *relocate* or *replace* them.

### 2.2 Point at the world, not at a menu

PeTI places objects by selecting from a palette and clicking on the world.
The pocketdungeons editor provides a palette GUI, but the primary authoring
surface is the build shell itself: the operator is teleported into the cell,
in creative-ish mode, and places blocks with their own hand. The palette is
for the gameplay objects that are awkward to find in the creative inventory
(trial spawner, vault, omen spawner, door jigsaw, spawn jigsaw) and for
setting per-object properties by right-clicking the placed block.

### 2.3 Test without leaving

PeTI's single best feature is the test toggle: press a key, drop into the
puzzle as the player, press it again, return to the editor with your work
intact. The pocketdungeons editor provides `test`: run the current build
shell through the real `RoomContent.apply` and `TrialContent` pipeline at a
chosen tier and theme, teleport the operator in as a test player, then
`revert` to restore the pre-test build shell. The author never saves to
test, and never loses work by testing.

### 2.4 Fail loudly, before save

PeTI validates the puzzle before publishing: are all buttons connected, is
there a door, is the exit reachable. The pocketdungeons editor validates
the room before save: is there at least one door jigsaw per open edge, does
every encounter role room have at least one trial spawner, does every loot
role room have at least one vault or chest. Validation reports in chat with
block coordinates, not as a silent rejection.

### 2.5 No JSON required for the common case

An operator should be able to author, save, and publish a room without
opening a text editor. Room-level metadata (roles, tier, weight, access,
window, spanY, content situation) is set through a chest GUI at save time.
The JSON is still written to disk for version control and hand-editing, but
the editor is the primary authoring path.

---

## 3. Authoring model: what each block means

### 3.1 Gameplay objects and their authored block

| Gameplay object | Authored block | Stamp-time behavior |
|---|---|---|
| Trial spawner | `minecraft:trial_spawner` | Reconfigure: set `OMINOUS` blockstate, load `normal_config` / `ominous_config` from run tier and theme prefix, set `target_cooldown_length`, set `required_player_range`. Do not move. |
| Vault | `minecraft:vault` | Reconfigure: set `VaultConfig` with run-tier loot table, key item, activation range. Do not move. |
| Supply chest | `minecraft:chest` (or any `RandomizableContainer`) | Reconfigure: set loot table to `LootTables.supplyTable(tier)`, set seed. Do not move. |
| Loot chest (anomaly, store, etc.) | `minecraft:chest` | Reconfigure per role/situation. Do not move. |
| Spawn anchor (for `RoomContent.spawnMobs`) | `pocketdungeons:spawn` jigsaw | Unchanged: jigsaw is replaced by air, position is passed to `spawnMobs` and `encounterAnchor` as a fallback. |
| Omen source | `minecraft:sculk_sensor`, `minecraft:calibrated_sculk_sensor`, or `minecraft:sculk_shrieker` | `OmenSources.arm` scans the cell for these blocks when `pressure: "omen"`. No replacement; the blocks are active as-is. |
| Door | `pocketdungeons:door` jigsaw | Unchanged: jigsaw is replaced by air, mask is derived by `RoomManifest.buildEntry`. |
| Decor | Any block | Unchanged. |

### 3.2 The new precedence in `TrialContent.encounterAnchor`

The current precedence is:

1. Classic `minecraft:spawner` in the cell.
2. Lowest-sorted `pocketdungeons:spawn` jigsaw.
3. Nothing (warn, return null).

The new precedence is:

1. Authored `minecraft:trial_spawner` in the cell. If one or more exist,
   each is reconfigured in place. The first (by sorted order) is returned as
   the anchor for the completion gate. Additional authored trial spawners
   are also reconfigured and also counted by the completion gate.
2. Classic `minecraft:spawner` in the cell. Replaced by a trial spawner at
   that position (backward compatibility with `spawner_den` and older
   templates).
3. Lowest-sorted `pocketdungeons:spawn` jigsaw. A trial spawner is placed
   at that position (backward compatibility with current templates).
4. Nothing. Warn and return null.

### 3.3 The new precedence in `TrialContent.applyLoot`

The current precedence is:

1. First sorted `RandomizableContainer` becomes the vault.
2. Remaining containers become supply chests.

The new precedence is:

1. Authored `minecraft:vault` blocks in the cell. Each is reconfigured in
   place with the run's loot table and key item. The first (by sorted
   order) is the primary vault. Additional authored vaults are also
   reconfigured and are lootable.
2. If no authored vault exists, the first sorted `RandomizableContainer`
   (that is not a trial spawner or omen source) is promoted to a vault, as
   today. This preserves backward compatibility with current templates.
3. Remaining `RandomizableContainer` blocks become supply chests, as today.

### 3.4 Multiple spawners and multiple vaults

The current architecture assumes one trial spawner and one vault per cell.
This spec relaxes that:

- A room may have multiple authored trial spawners. All are reconfigured.
  All are added to `InstanceLayout.trialSpawners` and counted by the
  `activeSpawners` completion gate. The 75% threshold applies to the total.
- A room may have multiple authored vaults. All are reconfigured with the
  same tiered loot table. (Future work could allow per-vault loot overrides
  via the property UI; this spec uses one table per run.)
- A room may have zero trial spawners if it is not an encounter room. A
  room may have zero vaults if it is not a loot room. Validation warns but
  does not block, because some situations (corridor, entrance, exit) do not
  need them.

### 3.5 What `RoomContent.containers` must exclude

`RoomContent.containers` currently returns every `RandomizableContainer` in
the cell. After this spec, it must exclude:

- `VaultBlockEntity` positions. These are vaults, not chests, and must not
  be double-promoted or retargeted as supply chests.
- `TrialSpawnerBlockEntity` positions. (Already not a
  `RandomizableContainer`, but listed for clarity.)

The method gains a filter: `if (be instanceof VaultBlockEntity) continue;`.

### 3.6 Omen sources: sculk, not spawners

`OmenSources.arm(level, origin, meta)` scans the cell for
`minecraft:sculk_sensor`, `minecraft:calibrated_sculk_sensor`, and
`minecraft:sculk_shrieker` blocks. It only arms them if the room metadata
`pressure` field is set to `"omen"`. A classic `minecraft:spawner` is not
an omen source and has nothing to do with the omen system.

Therefore the editor's omen source block is a sculk sensor or shrieker,
not a spawner. The operator places sculk blocks from the kit GUI (or the
creative inventory) and sets `pressure: "omen"` in the metadata GUI. The
stamp pipeline does not replace or reconfigure these blocks; they are
active as placed.

The kit GUI stocks sculk sensors and shriekers for convenience, but these
are vanilla blocks and can also be placed from the creative inventory.

---

## 4. UI/UX: the editor surface

### 4.1 Entry and the editor kit

The editor is entered with `/dungeon roombuilder new` or
`/dungeon roombuilder load <name>`, as today. The operator is teleported
into a build shell in the dungeon dimension. On arrival, the editor
automatically places a single item in the operator's inventory: the
**Room Editor Kit**. No command is needed to get it.

The kit is one item in the operator's inventory (a nether star renamed
"Room Editor Kit", or a custom item if the mod adds one later). It is not
pinned to a hotbar slot. The operator can hold it, drop it in a chest,
move it around. If lost, `/dungeon roombuilder kit` gives it back, but the
normal path is: enter the build room, the kit is already in your
inventory.

Right-clicking the kit in the air (or on a non-interactive block) opens the
**Editor Kit GUI**: a 6-row chest GUI that behaves like an enderchest. It
is a persistent, server-side inventory attached to the build room, not to
the player. The operator can take items out, place blocks in the build
room, and put items back. The GUI restocks its special blocks on open so
the operator never runs out.

The operator is in creative mode with the build shell's interior editable.
The shell (floor, walls, ceiling) is protected by the existing
`RoomProtection.isShell` check, extended to cover build rooms. The
operator's normal creative inventory is untouched and is used for decor
placement.

### 4.2 The Editor Kit GUI

The kit GUI has three regions: special blocks (top two rows), tools (third
row), and functions (bottom row).

**Special blocks (slots 0-17):** These are the real blocks, pre-tagged with
`pd_authored`, that the operator places in the build room. They restock on
every GUI open. Taking one and placing it is the same as creative-mode
placement; the block is real and the stamp pipeline recognizes it.

| Slot | Item | Block placed | Notes |
|---|---|---|---|
| 0-3 | Trial Spawner | `minecraft:trial_spawner` | Stack of 4. `OMINOUS=false`, placeholder tier-1 config, `INACTIVE` state so no mobs spawn in the editor. |
| 4-7 | Vault | `minecraft:vault` | Stack of 4. Sealed, placeholder loot table, cannot be opened in the editor. |
| 8-11 | Chest | `minecraft:chest` | Stack of 4. Empty, no loot table. Loot is set at stamp time. |
| 12-13 | Spawn Jigsaw | `pocketdungeons:spawn` jigsaw | Stack of 2. `final_state=minecraft:air`. |
| 14-15 | Door Jigsaw | `pocketdungeons:door` jigsaw | Stack of 2. `final_state=minecraft:air`. Snaps to canonical door slots on placement. |
| 16 | Sculk Sensor | `minecraft:sculk_sensor` | Stack of 4. Omen source. Active as-is when `pressure: "omen"` is set in metadata. |
| 17 | Sculk Shrieker | `minecraft:sculk_shrieker` | Stack of 2. Omen source. Active as-is when `pressure: "omen"` is set in metadata. |
| 18 | Barrier | `minecraft:barrier` | Stack of 16. Non-stamping annotation marker. Not saved with the template. |

**Tools (slots 19-26):** These are items the operator holds and right-clicks
on placed blocks in the build room. They do not get consumed.

| Slot | Item | Tool | Behavior |
|---|---|---|---|
| 19 | Stick (renamed "Inspector") | Inspector | Right-click a placed gameplay object to open its properties GUI (section 4.4). |
| 20 | Book (renamed "Annotations") | Annotation | Right-click a block to open a text input; the note is stored as a floating `text_display` at that position. |

**Functions (slots 27-35):** These are clickable GUI elements, not items to
take. Clicking one closes the kit GUI and opens the corresponding function.

| Slot | Item | Function |
|---|---|---|
| 27 | Book (enchant) | Room Metadata: opens the metadata chest GUI (section 4.5). |
| 28 | Emerald | Test: opens the test-stamp dialog (section 4.6). |
| 29 | Sunflower | Validate: runs the validation pass, reports in chat (section 4.7). |
| 30 | Nether Star | Save: opens a chat text input for the room name, then runs validation and writes. Same flow as `/dungeon roombuilder save <name>`. Defaults to the loaded room's name if the room was loaded. |
| 31 | Barrier | Close: closes the kit GUI. |

Slots 36-53 are empty (gray glass panes) for visual separation.

**Restock behavior:**

- **Special blocks (slots 0-18):** restock to their full stack count on
  every GUI open, regardless of what the operator took or left. The
  operator can take a stack, place blocks, reopen the kit, and take more.
- **Tools (slots 19-20):** restock only if the slot is empty. If the
  operator took the Inspector and still has it in their inventory, the
  slot stays empty (no duplicate). If the operator dropped or lost the
  Inspector, the slot restocks on next open. This prevents the operator
  from accumulating 10 Inspectors by reopening the kit.

### 4.3 Placing gameplay objects

The operator takes a special block from the kit GUI, walks to the build
room, and places it as in normal creative mode. The placed block is the
real block with a default configuration:

- Trial spawner: `OMINOUS=false`, placeholder `normal_config` and
  `ominous_config` set to `pocketdungeons:tier_1/normal` and
  `pocketdungeons:tier_1/ominous`. The author will not see mobs spawn in
  the editor because the spawner is in `INACTIVE` state until stamp-time
  reconfiguration.
- Vault: default `VaultConfig` with a placeholder loot table, no key item.
  The vault is sealed and cannot be opened in the editor.
- Chest: empty, no loot table. The author can place items in it for decor
  inspection, but the loot table is set at stamp time.
- Spawn jigsaw: `pocketdungeons:spawn`, `final_state=minecraft:air`, as
  today.
- Door jigsaw: `pocketdungeons:door`, `final_state=minecraft:air`. Must be
  placed at canonical door slots (columns 7-8, rows 1-3, on a wall edge).
  The editor snaps the placement to the nearest valid slot and warns if the
  operator tries to place one off-slot.
- Omen source: `minecraft:sculk_sensor`, `minecraft:calibrated_sculk_sensor`,
  or `minecraft:sculk_shrieker`. These are the blocks `OmenSources.arm`
  scans for. They are vanilla blocks and can also be placed from the
  creative inventory; the kit stocks them for convenience. The room's
  `pressure` metadata field must be set to `omen` for these to activate at
  stamp time. See section 3.6.
- Barrier: a non-stamping marker. The editor strips barriers before
  capture. Right-click with the Annotation tool to attach a note.

The operator can carry a stack back to the build room, place several, then
return to the kit for more. The kit restocks on open.

### 4.3.1 Loading legacy rooms: raw vs stamped view

When the operator loads an existing room with `/dungeon roombuilder load`,
the editor stamps the raw template into the build shell. For rooms
authored with the new editor (trial spawners and vaults placed directly),
this shows the real blocks. For legacy rooms authored with `BaseRoomSpecs`
or the old admin commands (which use `pocketdungeons:spawn` jigsaws and
chests as anchors), the editor shows the raw anchors, not the final
gameplay objects.

This is confusing for an operator editing a room they did not author. To
address this, the load flow offers two modes:

- **Raw (default):** stamps the template as-is. The operator sees jigsaws
  and chests. This is the editing view: the operator modifies the anchors
  and decor, then saves.
- **Stamped preview:** stamps the template, then runs the real
  `RoomContent.apply` pipeline at a default tier and theme. The operator
  sees trial spawners and vaults as a player would. This is a read-only
  view: the operator cannot edit the stamped blocks (they are
  stamp-time-generated, not authored). A banner in chat says "Stamped
  preview: changes will not be saved. Run `/dungeon roombuilder raw` to
  return to the editable view."

The operator switches between raw and stamped preview with
`/dungeon roombuilder raw` and `/dungeon roombuilder stamped`. The raw
view is the default on load. The stamped preview is for understanding what
the legacy room produces without running a full test stamp.

### 4.4 Per-object properties: the inspector

The operator takes the Inspector (slot 19) from the kit GUI, then
right-clicks a placed gameplay object in the build room. This opens a
chest GUI showing that object's authorable properties. This is the PeTI
"item properties" panel equivalent.

**Trial spawner properties:**

| Slot | Property | Values |
|---|---|---|
| 0 | Spawner config prefix | Paper icons, one per registered prefix. See prefix enumeration below. |
| 1 | Ominous | Sunflower (yes) / poppy (no). |
| 2 | Cooldown | Paper with number, click to cycle 30/60/90/120 seconds. |
| 3 | Required player range | Paper with number, click to cycle 4/8/14/32. |
| 4 | Gated (situation flag) | Iron door (gated) / wooden door (not gated). |

**Prefix enumeration:** The available prefixes are collected from two
sources at editor open time:

1. Theme prefixes: `ThemeManifest.Entry.meta().spawnerPrefix` for every
   loaded theme.
2. Situation prefixes: the `configPrefix` argument passed to
   `TrialContent.applyEncounter` by each registered situation handler.
   These are hardcoded in the handler registrations (e.g.
   `breeze_arena`, `spawner_den`).

The editor collects both into a sorted, deduplicated list. If the list has
fewer than 9 entries, they fit in one row of the inspector GUI. If the
list is longer, the prefix slot opens a second paginated GUI listing all
prefixes, same pattern as the rooms browser (section 4.6). The operator
clicks a prefix to select it and returns to the inspector.

**Vault properties:**

| Slot | Property | Values |
|---|---|---|
| 0 | Facing | Paper, click to cycle north / south / east / west. Rotates the block in place without breaking. |
| 1 | Loot tier override | Paper, tier 1/2/3, or "inherit from room". |
| 2 | Ominous | Sunflower / poppy. |
| 3 | Loot table override | Paper, type the loot table id in chat. Blank = inherit from run tier. |
| 4 | Key item | Item slot; place an item to set the key. Blank = default trial key. |

**Chest properties:**

| Slot | Property | Values |
|---|---|---|
| 0 | Facing | Paper, click to cycle north / south / east / west. Rotates the block in place. |
| 1 | Role | Paper: supply / anomaly / store / inherit. Determines which loot table is applied at stamp time. |
| 2 | Loot table override | Paper, type the id. Blank = inherit from role and tier. |

**Spawn jigsaw properties:**

| Slot | Property | Values |
|---|---|---|
| 0 | Spawn type | Paper: mob / passive / ambush. Determines which `spawnMobs` call uses this anchor. |

Properties are stored as NBT on the block entity (for trial spawners and
vaults, which have block entities) or as a companion marker entity (for
chests and jigsaws, which do not have a general-purpose NBT slot for mod
data). The companion marker is a silent `text_display` entity at the block
position, tagged `pocketdungeons_author_marker`, carrying the property NBT.
It is not saved with the template and is stripped before capture.

### 4.5 Room metadata

Clicking the Metadata function (slot 27) in the kit GUI opens a 6-row
chest GUI titled "Room Metadata" with the room-level fields from
`DungeonRoomMeta`:

| Slot | Field | Control |
|---|---|---|
| 0-4 | Roles | One toggle per role: encounter, loot, corridor, entrance, exit. Green pane = on, gray pane = off. At least one must be on. |
| 5 | Footprint X | Paper, click to cycle 1/2/3. |
| 6 | Footprint Z | Paper, click to cycle 1/2/3. |
| 7 | Tier | Paper, click to cycle 1/2/3. |
| 8 | Weight | Paper, click to cycle 1/2/3/5. |
| 9 | Min depth | Paper, click to cycle 0/1/2/3. |
| 10 | Max per dungeon | Paper, click to cycle -1/1/2/3. |
| 11 | Access | Iron door (open) / oak door (gated). |
| 12 | Window | Glass pane variants: bars / glass / tinted / none. |
| 13 | Span Y | Paper, click to cycle 1/2. |
| 14 | Content situation | Paper, click to cycle through registered `Situations` ids, or "none". |
| 15 | Theme restriction | Paper, click to cycle through loaded theme ids, or "any". |
| 16 | Pressure | Paper: none / omen / local. |
| 17 | Provides | Paper, click to cycle registered `SituationTags` tool tags, or "none". Multi-select. |
| 18 | Requires | Paper, click to cycle registered `SituationTags` tool tags, or "none". Multi-select. |
| 49 | Save | Nether star. Runs validation, then saves. |
| 50 | Cancel | Barrier. Closes the GUI without saving. |

The GUI is a live preview: changes are held in memory until Save is
clicked. Closing without Save discards.

### 4.6 The rooms browser (existing, extended)

The existing `/dungeon roombuilder rooms` GUI stays. Each room entry gains
two new lore lines:

- "Spawners: N" (count of authored trial spawners in the template)
- "Vaults: N" (count of authored vaults in the template)

This helps the operator pick a room to load based on its content shape, not
just its name and roles.

### 4.7 Test mode

Clicking the Test function (slot 28) in the kit GUI opens a small dialog:

| Slot | Field | Control |
|---|---|---|
| 0 | Tier | Paper, 1/2/3. |
| 1 | Theme | Paper, cycle loaded themes. |
| 2 | Ominous | Sunflower / poppy. |
| 3 | Rotation | Paper, cycle 0 / 90 / 180 / 270 degrees. |
| 4 | Test | Emerald. Runs the test stamp. |
| 5 | Cancel | Barrier. |

Clicking Test:

1. Captures the current build shell to an in-memory template (not written
   to disk).
2. Stamps the in-memory template into a second build shell at a different
   slot, using the real `LayoutStamper.stamp` path with the chosen tier,
   theme, and ominous flag.
3. Teleports the operator into the test shell as a player.
4. The operator can fight the spawners, open the vault (with a key given in
   creative), and walk the room.
5. The operator runs `/dungeon roombuilder revert` to return to the editor
   shell. The test shell is purged. The editor shell is untouched.

The test stamp uses the real pipeline, so it exercises
`RoomContent.apply`, `TrialContent.applyEncounter`, `TrialContent.applyLoot`,
`OmenSources.arm`, and the completion gate. This is the PeTI "test as the
player" loop.

### 4.8 Validation

Clicking the Validate function (slot 29) in the kit GUI, or running
`/dungeon roombuilder save <name>`, runs a validation pass and reports in
chat before writing anything:

| Check | Severity | Message |
|---|---|---|
| At least one role set. | Error | "No roles selected. Set at least one in the metadata GUI." |
| Room has zero door jigsaws and is not an entrance room. | Error | "Room has no door jigsaws. It will be unreachable in a dungeon. Place door jigsaws on at least one wall, or set the role to entrance." |
| Door jigsaw set is incomplete (fewer than 6 blocks for a 2-wide, 3-tall slot). | Error | "Edge <dir> has a partial door jigsaw set (<n>/6 blocks) at <pos>. Complete the set or remove the stray jigsaws." |
| No door jigsaw off canonical slot. | Error | "Door jigsaw at <pos> is not at a canonical slot." |
| Encounter role with zero trial spawners and zero spawn jigsaws. | Warning | "Encounter room has no spawner or spawn anchor. Stamp will skip the encounter." |
| Loot role with zero vaults and zero chests. | Warning | "Loot room has no vault or chest. Stamp will skip the loot." |
| Multiple trial spawners. | Info | "Room has N trial spawners. All count toward the completion gate." |
| Multiple vaults. | Info | "Room has N vaults. All use the same tiered loot table." |
| spanY=2 but no lower-story return path. | Error | "spanY=2 but no stairs/ladder from Y=0 to Y=8. Multi-story return path validation failed." |
| Entity count above 50 in the cell. | Warning | "Room has N entities. High entity counts may cause lag when stamped." |

Errors block save. Warnings and info do not. The operator can fix and
retry without losing the build shell.

### 4.9 Annotations

The operator takes the Annotation tool (slot 20) from the kit GUI, then
right-clicks a block in the build room to open a text input. The note is
stored on a companion `text_display` entity tagged
`pocketdungeons_author_note`. Annotations are visible in the editor as
floating text, are not saved with the template, and are stripped before
capture. This is the PeTI "annotation" feature: leave notes for yourself or
the next editor without affecting the room.

Barriers (slot 18) serve as visible annotation anchors. The operator can
place a barrier, right-click it with the Annotation tool, and type a note.
The barrier itself is stripped before capture; the note entity is also
stripped.

### 4.10 Leaving and abandoning

The operator can leave the build room without saving:

- `/dungeon roombuilder exit`: opens a chat prompt: "Save your work as an
  unfinished room for later, or discard?" The operator clicks "Save for
  later" or "Discard."
  - **Save for later:** writes the unfinished room to disk (same path as
    disconnect, section 4.12), tears down the build room, sends the
    operator to world spawn, removes the kit.
  - **Discard:** tears down the build room without writing anything, sends
    the operator to world spawn, removes the kit. The on-disk unfinished
    room (if any from a previous disconnect) is also deleted.
- Disconnecting from the server always saves the unfinished room to disk
  (section 4.12). There is no "discard" option on disconnect because the
  server cannot ask.

The Save function (kit GUI slot 30) and the existing
`/dungeon roombuilder save <name>` command both save and then exit. There
is no "save and stay" path; saving always tears down the build room and
sends the operator home, as today.

### 4.11 Clear and reset

`/dungeon roombuilder clear`: clears the build room interior back to a
fresh empty shell (floor, walls, ceiling, no content). This is the PeTI
"delete all" equivalent. It does not tear down the build room; the
operator stays in it with a blank canvas. The kit stays in the inventory.

Clear does not affect the saved version of the room on disk. If the
operator loaded an existing room and then clears, the on-disk template is
untouched until they save.

### 4.12 Undo, redo, and crash recovery

**Undo/redo:** The editor maintains a block-change history for the build
room interior. `/dungeon roombuilder undo` reverts the last block change;
`/dungeon roombuilder redo` re-applies it. The history is capped at 50
operations to bound memory. An "operation" is a single block place or
break, or a contiguous burst of places/breaks within a 1-second window,
so that placing a wall is one undo step, not 64.

This is not a full creative-mode undo. It only tracks changes made inside
the build room interior. Changes to the shell are not tracked (the shell
is protected and should not change).

**Disconnect and crash recovery:** The editor saves the player's
in-progress build room to disk as an "unfinished room" tied to the
player's UUID. The save trigger is:

- **On disconnect:** the server's player disconnect hook writes the
  unfinished room to disk immediately. This is a synchronous write on the
  disconnect event, before the player object is cleaned up.
- **On server stop:** the server shutdown hook writes all active build
  rooms to disk.
- **Periodic autosave:** every 60 seconds, if the build room has changed
  since the last autosave, the editor writes the unfinished room to disk.
  This bounds data loss to 60 seconds in case of a hard crash that
  bypasses the disconnect and shutdown hooks.

The 5-minute in-memory grace period is separate from the disk save. The
disk save happens immediately on disconnect; the grace period only
determines whether the operator can resume the in-memory build room
(fast, no reload) or must reload from the on-disk unfinished room
(slower, but no data loss).

If the operator does not reconnect within the grace period, or the server
restarts, the in-memory build room is gone but the on-disk unfinished room
remains. The next time the operator runs `/dungeon roombuilder new` or
`load`, the editor detects the unfinished room and offers to restore it:
" You have an unfinished room from <timestamp>. Resume it, or start
fresh?" The operator chooses via a chest GUI or chat prompt.

If the operator starts fresh or loads a different room, the unfinished
room is discarded (overwritten by the new build room). The unfinished room
is also discarded on a successful save.

The unfinished room is stored in the same template output directory under
`unfinished/<uuid>.nbt`, with a companion `unfinished/<uuid>.json`
carrying the timestamp and any in-progress metadata. These files are not
loaded by the manifest and do not appear in the rooms browser.

### 4.13 Entity authoring

The operator can place entities in the build room for decor: armor stands,
item frames, paintings, and any other entity they can summon in creative
mode. These entities are captured with the template if
`includeEntities=true` is used for the capture.

The save flow distinguishes between authored entities and editor marker
entities:

- **Authored entities** (armor stands, item frames, paintings, etc.) are
  captured with the template and stamped with the room. They are part of
  the room.
- **Editor marker entities** (`pocketdungeons_author_marker`,
  `pocketdungeons_author_note`) are stripped before capture and are not
  part of the room.

The save flow: sweep the cell for marker entities and remove them, capture
with `includeEntities=true` (so authored entities are saved), then
re-add the marker entities for continued editing.

---

## 5. Stamp-time changes

### 5.1 `TrialContent.applyEncounter`

The method is restructured to handle authored trial spawners first.

```
applyEncounter(level, cellOrigin, spawns, tier, affixes, prefix, gated):
    ominous = affixes.contains(OMINOUS)
    swarming = affixes.contains(SWARMING)
    silenced = affixes.contains(SILENCED)

    authored = authoredTrialSpawners(level, cellOrigin)
    if (!authored.isEmpty()):
        BlockPos anchor = null
        for pos in authored (sorted):
            reconfigureTrialSpawner(level, pos, tier, prefix, ominous,
                                    swarming, silenced, cooldown)
            if anchor == null: anchor = pos
        clearClassicSpawners(level, cellOrigin)
        return anchor

    // Fall through to the legacy path.
    BlockPos anchor = encounterAnchor(level, cellOrigin, spawns)
    if anchor == null: return null
    // ... existing place-and-configure code ...
    return anchor
```

`authoredTrialSpawners` scans the cell for `TrialSpawnerBlockEntity`
whose `pd_authored` flag is set. Only blocks with the flag are treated
as authored. The flag is written by the editor when the operator places
the block (the kit item carries the flag in its NBT, and placement
transfers it to the block entity), so the stamp code can distinguish an
authored spawner from one placed by a previous stamp pass in a reused
chunk.

`reconfigureTrialSpawner` loads the same `CompoundTag` config as today
(`normal_config`, `ominous_config`, `target_cooldown_length`,
`required_player_range`) into the existing block entity, without replacing
the block. It sets the `OMINOUS` blockstate in place.

### 5.2 `TrialContent.applyLoot`

```
applyLoot(level, cellOrigin, tier, affixes, lootSuffix, seed):
    ominous = affixes.contains(OMINOUS)

    authored = authoredVaults(level, cellOrigin)
    if (!authored.isEmpty()):
        for pos in authored (sorted):
            reconfigureVault(level, pos, tier, ominous, lootSuffix, keyItem)
        // Remaining chests become supply chests.
        for pos in nonVaultContainers(level, cellOrigin):
            retargetSupply(level, pos, tier, seed)
        return true

    // Fall through to the legacy path: promote first chest.
    // ... existing code ...
```

`authoredVaults` scans for `VaultBlockEntity`. `nonVaultContainers` is
`RoomContent.containers` with the new vault-exclusion filter.

`reconfigureVault` sets the `VaultConfig` on the existing block entity, as
`placeVault` does today, but without replacing the block or reading
`ChestBlock.FACING` (the vault block already has its own facing).

### 5.3 `RoomContent.containers`

Add a filter to skip `VaultBlockEntity`:

```java
if (entry.getValue() instanceof VaultBlockEntity) continue;
```

This prevents authored vaults from being treated as promotable chests or
supply chests.

### 5.4 `RoomContent.apply` role dispatch

The role dispatch in `RoomContent.apply` (lines 122-133) does not change
structurally. `encounter` still calls `TrialContent.applyEncounter`, `loot`
still calls `TrialContent.applyLoot`. The change is inside those methods.

One new behavior: if the role is `encounter` and the room has authored
trial spawners, `removeChests` is still called first (encounter rooms do
not keep chests), but it must not remove authored vaults. The
`removeChests` method gains the same vault-exclusion filter.

### 5.5 `LayoutStamper` and the completion gate

`LayoutStamper` collects trial spawner anchors from `RoomContent.apply` as
today. With multiple authored spawners, `RoomContent.apply` must return
*all* of them, not just the first. The return type changes from
`BlockPos` to `List<BlockPos>`, or a new side-channel is added.

`InstanceLayout.trialSpawners` already accepts multiple entries per cell
(it is a list). The completion gate in `TrialContent.activeSpawners`
already iterates the list. No change to the gate logic is needed; the
threshold math already handles N spawners per cell.

**SituationHandler interface migration:** The `SituationHandler` interface
returns `BlockPos`, and all registered handlers (KnowledgeSpecs,
MechanismSpecs, PressureSpecs, SpurSpecs) implement that signature.
Changing `RoomContent.apply`'s return type to `List<BlockPos>` does not
require changing `SituationHandler`, because situation handlers bypass
`RoomContent.apply`'s return value: `RoomContent.apply` calls
`Situations.apply(...)` and forwards its return directly to
`LayoutStamper`. The migration path is:

1. `RoomContent.apply` returns `List<BlockPos>` instead of `BlockPos`.
2. `LayoutStamper` collects all entries from the list, not just one.
3. `Situations.apply` still returns `BlockPos` (one anchor). The situation
   path wraps it: `return List.of(spawnerAnchor)`.
4. The non-situation path (role switch) returns the full list of authored
   spawners from `TrialContent.applyEncounter`.
5. `SituationHandler` is unchanged. No handler code needs to be modified.

This keeps the handler API stable while allowing the role-dispatch path to
return multiple anchors.

### 5.6 `JigsawFallback` and authored blocks

`JigsawFallback.replaceRemaining` runs after `TemplateStamper.place` and
replaces any remaining jigsaw with its `final_state`. Authored trial
spawners and vaults are not jigsaws, so they are unaffected. Authored
spawn and door jigsaws are still replaced by air, as today.

No change to `JigsawFallback`.

### 5.7 Template capture

`RoomBuilderCommands.saveRoom` and `RoomTemplateGenerator.captureRoomToFile`
capture the 16x7x16 (or 16x14x16 for spanY=2) volume with
`StructureTemplate.fillFromWorld`. Authored trial spawners and vaults are
real blocks with real block entities, so they are captured correctly by
the vanilla template system. No change to capture is needed.

The companion marker entities (`pocketdungeons_author_marker`,
`pocketdungeons_author_note`) are entities, and
`fillFromWorld` with `includeEntities=true` would capture them. The save
flow strips marker entities before capture, then captures with
`includeEntities=true` so authored entities (armor stands, item frames,
paintings) are saved. After capture, the marker entities are re-added for
continued editing. See section 4.13.

### 5.8 Rotation of authored blocks

When a room stamps at a rotation other than 0, `StructureTemplate` rotates
block positions and blockstates. Authored trial spawners and vaults must
rotate correctly:

- `TrialSpawnerBlock` has no facing property; rotation does not change its
  blockstate. The block entity NBT (config ids, cooldown, range) is
  position-independent and survives rotation. No special handling needed.
- `VaultBlock` has a `FACING` property. `StructureTemplate` rotates
  `FACING` with the template. The vault's `VaultConfig` is position-
  independent and survives rotation. No special handling needed.
- `ChestBlock.FACING` rotates with the template, as today.
- `JigsawBlock` facing rotates with the template, as today.

This is a requirement, not an assumption: any authored block that does not
rotate correctly must be fixed before the phase ships. The test mode
(section 4.7) must include a rotation test: stamp the room at 90, 180, and
270 degrees and verify the authored blocks are in the correct positions
with the correct facing.

### 5.9 Affix hazards and authored decor

`RoomContent.apply` places affix hazards (molten, explosive, voided floor)
after role dispatch. These scatter hazards in the interior margin
(columns 3-12, rows 3-12) and could overwrite carefully placed authored
decor.

After this spec, the hazard placement methods must skip positions that
contain authored gameplay objects:

- `placeMoltenHazards`: skip any position where the existing block is a
  trial spawner, vault, chest, or a block tagged `pd_authored_decor`.
- `placeExplosiveHazards`: same skip list.
- `placeVoidedFloor`: skip any position where the existing block is a
  trial spawner, vault, or chest. Voided floor only replaces floor blocks,
  so it should not hit wall-mounted decor, but it could hit a floor-level
  chest or spawner.

The skip check is a simple block-type test, not a full `pd_authored` flag
read, because the hazards run after stamping and the flag may have been
cleared. The block type is sufficient: a trial spawner or vault in the
interior is always authored (the stamp pipeline does not place them in
the interior margin).

---

## 6. The `pd_authored` flag and chunk reuse

The dungeon dimension reuses chunks for build rooms. A trial spawner placed
by a previous stamp pass could be mistaken for an authored spawner by the
next stamp pass in the same chunk. To prevent this:

- The editor writes a `pd_authored` byte to the trial spawner's block
  entity NBT when the operator places it. The stamp code checks this flag
  in `authoredTrialSpawners`: only spawners with the flag are treated as
  authored.
- The flag is preserved through template capture and placement (it is in
  the block entity NBT, which `StructureTemplate` serializes).
- The stamp code does **not** clear the flag after reconfiguration. The
  reconfiguration is idempotent: applying the same config twice produces
  the same result. This is simpler and safer than clearing the flag,
  because clearing would cause a re-stamp of the same chunk to fall
  through to the legacy path and place a second spawner.

The same flag is written to vault block entities.

The flag is also written to the kit item's NBT. When the operator takes a
trial spawner from the kit GUI and places it, the item's `pd_authored` flag
is transferred to the placed block's entity. This is how the editor
distinguishes a kit-placed spawner from a creative-inventory-placed
spawner: only kit items carry the flag.

---

## 7. Migration and backward compatibility

### 7.1 Existing templates

All existing room templates (the `BaseRoomSpecs` rooms and any
operator-authored rooms saved before this spec) use jigsaws and chests, not
authored trial spawners and vaults. They continue to work through the
legacy fallback paths in `applyEncounter` and `applyLoot`. No template
migration is required.

### 7.2 The `spawner_den` situation

`spawner_den` uses a classic `minecraft:spawner` as its anchor. The classic
spawner path in `encounterAnchor` is preserved as the second precedence
level. `spawner_den` continues to work unchanged.

### 7.3 The `treasure_alcove` situation

`treasure_alcove` has two chests: the first becomes the vault, the second
stays a supply chest. With this spec, the author *could* place a vault and
a chest directly. The legacy two-chest template continues to work through
the `applyLoot` fallback. No change to `treasure_alcove` is required.

### 7.4 Situation handlers

Situation handlers (`Situations.register`) that call
`TrialContent.applyEncounter` with a fixed config prefix continue to work.
The authored-spawner path runs first; if the room has authored spawners,
the situation handler's prefix is applied to them. If not, the legacy path
runs. Situation handlers that call `RoomContent.spawnMobs` directly are
unaffected; they still read `pocketdungeons:spawn` jigsaws.

### 7.5 The old admin commands

`/dungeon admin buildroom` and `/dungeon admin saveroom` remain for
compatibility. They do not get the new palette, inspector, or metadata GUI.
They are the "quick and dirty" path; `/dungeon roombuilder` is the full
editor.

---

## 8. Command surface

### 8.1 Existing commands (unchanged)

- `/dungeon roombuilder new`
- `/dungeon roombuilder load <name>`
- `/dungeon roombuilder save <name>`
- `/dungeon roombuilder delete <name>`
- `/dungeon roombuilder rooms`
- `/dungeon roombuilder versions <name>`

### 8.2 New commands

- `/dungeon roombuilder kit`: re-issue the Room Editor Kit item if lost.
- `/dungeon roombuilder metadata`: open the room metadata GUI without
  opening the kit GUI.
- `/dungeon roombuilder validate`: run the validation pass and report in
  chat without saving.
- `/dungeon roombuilder test`: open the test-stamp dialog without opening
  the kit GUI.
- `/dungeon roombuilder revert`: return from a test stamp to the editor
  shell. Only valid during a test stamp.
- `/dungeon roombuilder raw`: switch the build room to the raw
  (editable) view. Only meaningful after a stamped preview.
- `/dungeon roombuilder stamped`: switch the build room to the stamped
  preview (read-only) view. Only meaningful in a raw view.
- `/dungeon roombuilder exit`: tear down the build room and return to
  world spawn. Prompts to save as unfinished or discard.
- `/dungeon roombuilder clear`: clear the build room interior to a fresh
  empty shell. The operator stays in the room.
- `/dungeon roombuilder undo`: revert the last block change in the build
  room.
- `/dungeon roombuilder redo`: re-apply the last undone block change.
- `/dungeon roombuilder resume`: re-enter a preserved build room after a
  disconnect, if within the grace period.
- `/dungeon roombuilder annotate <text>`: place an annotation marker at the
  player's target block with the given text.
- `/dungeon roombuilder clear-annotations`: remove all annotation markers
  in the build shell.

All commands are operator-only (`Commands.LEVEL_GAMEMASTERS`).

---

## 9. Implementation phases

This spec is large. It should be built in phases, each of which is
independently shippable and testable.

### Phase A: Authored block recognition in stamp pipeline

Change `TrialContent.applyEncounter` and `TrialContent.applyLoot` to
recognize authored trial spawners and vaults, reconfigure them in place,
and fall back to the legacy path. Change `RoomContent.containers` and
`removeChests` to exclude vaults. Change `RoomContent.apply` to return
multiple spawner anchors.

**Touch points:** `TrialContent.java`, `RoomContent.java`,
`LayoutStamper.java`, `InstanceLayout.java` (if the return type changes).

**Done when:** a hand-placed trial spawner in a template is reconfigured,
not replaced. A hand-placed vault is reconfigured, not replaced. Legacy
templates still work.

### Phase B: Editor kit and placement

Add the Room Editor Kit item and its enderchest-style GUI to the build
room. Issue the kit automatically when the operator enters the build room
via `/dungeon roombuilder new` or `load`. Stock the GUI with the special
blocks (trial spawner, vault, chest, spawn jigsaw, door jigsaw, sculk
sensor, sculk shrieker, barrier). Each kit item carries the `pd_authored`
flag in its NBT; when the operator places the block, a placement listener
transfers the flag from the item to the placed block's entity. Add the
snap-to-canonical-slot behavior for door jigsaws. Add the
`/dungeon roombuilder kit` command as a lost-kit restorer only. Add the
raw and stamped view switching (`/dungeon roombuilder raw` and `stamped`).

**Touch points:** new `RoomEditorKit.java`, `RoomBuilderCommands.java`,
`Instances.java` (build room setup, automatic kit issuance on entry,
placement listener for `pd_authored` transfer, raw/stamped view
switching), `RoomBuilder.java` (shell protection for build rooms).

**Done when:** the operator enters a build room and the kit is already in
their inventory. Opening the kit GUI, taking a trial spawner, vault,
chest, spawn jigsaw, door jigsaw, sculk sensor, or sculk shrieker, and
placing it in the build room all work. The placed block's entity has
`pd_authored` set. The kit restocks on reopen. Loading a legacy room and
switching to stamped preview shows the stamp-time blocks; switching back
to raw shows the editable anchors.

### Phase C: Inspector and per-object properties

Add the Inspector (stick) GUI for trial spawners, vaults, chests, and
spawn jigsaws. Add the companion marker entity for property storage on
chests and jigsaws. Add the property-readback in the stamp pipeline (read
the `pd_authored` flag and any override fields).

**Touch points:** new `RoomEditorInspector.java`, `TrialContent.java`
(read overrides), `RoomContent.java` (read overrides).

**Done when:** the operator can set spawner config prefix, ominous flag,
cooldown, range, vault loot tier, chest role, and spawn type through the
GUI, and the stamp pipeline respects them.

### Phase D: Room metadata GUI

Add the Metadata function (kit GUI slot 27) and its chest GUI. Wire it to
the save flow so metadata is written from the GUI, not from the hardcoded
`buildRoomJson` defaults.

**Touch points:** new `RoomEditorMetadata.java`, `RoomBuilderCommands.java`
(save flow), `DungeonRoomMeta.java` (no change to fields, just the
authoring path).

**Done when:** the operator can set all `DungeonRoomMeta` fields from the
GUI, and saving writes them to the JSON.

### Phase E: Validation

Add the validation pass. Report in chat with coordinates. Block save on
errors.

**Touch points:** new `RoomValidator.java`, `RoomBuilderCommands.java`
(save flow).

**Done when:** saving a room with no roles, no doors, or a broken
multi-story return path is blocked with a readable message.

### Phase F: Test mode

Add the Test function (kit GUI slot 28) and its dialog. Add the in-memory
capture, test stamp, and revert flow.

**Touch points:** new `RoomEditorTest.java`, `Instances.java` (second
build slot for test), `LayoutStamper.java` (call with test parameters).

**Done when:** the operator can test-stamp a room at a chosen tier and
theme, walk through it, and revert to the editor without losing work.

### Phase G: Annotations and polish

Add the Annotation tool (kit GUI slot 19) and barrier marker. Add the
rooms-browser lore extensions (spawner count, vault count). Add the
`/dungeon roombuilder annotate` and `clear-annotations` commands.

**Touch points:** new `RoomEditorAnnotations.java`,
`RoomBuilderCommands.java` (rooms GUI lore).

**Done when:** the operator can leave floating notes in the editor and see
content counts in the rooms browser.

### Phase H: Undo, redo, exit, clear, and crash recovery

Add the block-change history and `/dungeon roombuilder undo` / `redo`
commands. Add `/dungeon roombuilder exit` with the save-unfinished-or-
discard prompt. Add `/dungeon roombuilder clear`. Add the 5-minute
disconnect grace period, the immediate disk save on disconnect, the
60-second periodic autosave, and `/dungeon roombuilder resume`. Add the
persistent unfinished-room save to `unfinished/<uuid>.nbt` and the
restore prompt on next editor entry. Add the server-shutdown hook that
saves all active build rooms.

**Touch points:** new `RoomEditorHistory.java`,
`RoomEditorLifecycle.java`, `RoomBuilderCommands.java`,
`Instances.java` (grace period timer, resume, unfinished-room save and
restore, autosave timer, disconnect and shutdown hooks),
`RoomTemplateGenerator.java` (unfinished-room capture path).

**Done when:** the operator can undo and redo block changes, exit with a
save-or-discard prompt, clear to a blank shell, resume after a short
disconnect, and restore an unfinished room after a server restart.
Autosave writes the unfinished room every 60 seconds if changed.

---

## 10. Resolved decisions

These design decisions have been made and are reflected in the spec above.
They are listed here for reference.

### 10.1 Kit GUI restocks blocks on every open, tools only if empty

The kit is a palette, not a chest. Special blocks restock to their full
stack count on every open. Tools (Inspector, Annotation) restock only if
the slot is empty, to prevent the operator from accumulating duplicates.
If the operator wants storage, they use a normal chest in the build room.

### 10.2 Metadata GUI is editable after save

A separate `/dungeon roombuilder metadata <name>` command opens the GUI
for an already-saved room without opening a build shell. This lets the
operator tweak metadata without re-loading the room.

### 10.3 No per-vault loot overrides in the initial phases

All vaults in a room use the same tiered loot table, derived from the run.
Per-vault overrides via the inspector's "loot table override" field are a
future enhancement, not in Phases A through H.

### 10.4 Test stamp disables the completion gate

The operator is alone, not in a run. The spawners still activate and spawn
mobs so the combat feel is real, but the 75% completion gate does not
block exploration.

### 10.5 Multi-cell rooms are out of scope for the initial phases

Phases A through H target 1x1 rooms only. Multi-cell support (2x1, 2x2)
is a later phase.

### 10.6 No community sharing in this spec

The current `save` writes to disk. A future `export` command could package
the `.nbt` and `.json` into a single shareable file. Not in this spec.

### 10.7 Undo/redo is bounded by 50 operations, burst-grouped

50 operations, where an operation is a single block place or break, or a
contiguous burst of places or breaks within a 1-second window. If authors
hit the cap, raise it in a later phase.

### 10.8 Disconnect grace period is 5 minutes, with persistent unfinished-room save

5-minute in-memory grace period for quick reconnects. If the operator does
not reconnect in time, or the server restarts, the build room is saved to
disk as an unfinished room tied to the player's UUID. The next time the
operator enters the editor, they are offered to resume the unfinished room
or start fresh. See section 4.12.

### 10.9 Entity count validation, if simple to implement

The validation pass warns on entity counts above 50 per cell. This is a
simple count check during the existing validation sweep. If the check
proves non-trivial to implement (e.g. entity counting is expensive in the
build room), it is deferred to a later phase. See section 4.8.

### 10.10 No copy/paste or schematic import

The editor does not support WorldEdit-style clipboard operations. The
supported workflow for reusing an existing room is: load it with
`/dungeon roombuilder load <name>`, make modifications, and save it with
a different name via `/dungeon roombuilder save <new_name>`. This creates
a new room without overwriting the original.

---

## 11. Open questions

These are design decisions that have not yet been made. They are listed
here so they are not forgotten during implementation.

### 11.1 Should the metadata GUI support custom processor lists?

`DungeonRoomMeta` has a `processors` field for theme tinting. The metadata
GUI (section 4.5) does not currently expose it. If authors need per-room
processor overrides, the GUI would need a processor picker. No
recommendation yet; defer until an author asks for it.

### 11.2 Should the inspector support bulk editing?

If an operator places 5 trial spawners and wants them all to use the same
config prefix, they must open the inspector 5 times. A bulk-edit mode
(select multiple blocks, set one property) would help. No recommendation
yet; defer until authors report the friction.

---

## 12. User scenarios

These scenarios walk through how different operators use the editor. They
are derived from the spec above and cover the full authoring lifecycle.
Each scenario names the operator's goal, the steps they take, and what the
system does at each point.

### 12.1 Scenario A: First-time operator creates a loot room from scratch

**Operator:** Alex, a server admin who has never used the room editor.
They want a simple loot room with one vault and two supply chests.

**Steps:**

1. Alex runs `/dungeon roombuilder new`.
   - The system creates a build shell in the dungeon dimension and
     teleports Alex into it. A Room Editor Kit appears in their inventory.
   - The shell is a blank 16x7x16 room: stone brick floor, walls, ceiling
     with light fixtures. The interior is empty.

2. Alex right-clicks the kit in the air. The Editor Kit GUI opens.
   - They see the special blocks (trial spawners, vaults, chests, jigsaws,
     barriers), tools (inspector, annotations), and functions (metadata,
     test, validate, save, close).

3. Alex takes a Vault from slot 4 and closes the GUI.
   - The vault item is in their hand. They walk to the back wall and
     right-click to place it. The vault block appears, sealed and
     inactive. Its block entity has `pd_authored` set.

4. Alex reopens the kit, takes a Chest from slot 8, closes the GUI, and
   places it next to the vault. They repeat for a second chest on the
   other side of the vault.
   - Three gameplay objects are now in the room: one vault, two chests.

5. Alex wants the vault to face south. They reopen the kit, take the
   Inspector from slot 19, and right-click the vault.
   - The Vault properties GUI opens. They see facing, loot tier override,
     ominous flag, loot table override, and key item. They click the
     Facing slot to cycle it to south. The vault block rotates in place.
     They set the loot tier to "inherit from room" and close the GUI.

6. Alex places decor: a few stairs as a pedestal under the vault, some
   cracked stone bricks for atmosphere, and a sea lantern on the wall.
   They use their normal creative inventory for this. No kit needed.

7. Alex opens the kit and clicks the Metadata function (slot 27).
   - The Room Metadata GUI opens. Alex toggles the "loot" role on (green
     pane), sets tier to 2, and leaves everything else at defaults. They
     click Save (slot 49).

8. Validation runs. Chat reports:
   - Error: "Room has no door jigsaws. It will be unreachable in a
     dungeon. Place door jigsaws on at least one wall, or set the role
     to entrance."
   - Alex realizes they need doors. They open the kit, take a Door Jigsaw
     from slot 14, and place it on the north wall. The editor snaps it to
     the canonical slot. Alex places 5 more to fill the 2-wide, 3-tall
     slot. They repeat on the south wall.
   - Alex clicks Save again. Validation runs:
   - "Loot room has 1 vault and 2 chests. OK."
   - No errors. The save proceeds.

9. A chat prompt asks for the room name. Alex types "loot_shrine".
   - The system backs up any existing `loot_shrine.nbt` to `versions/`,
     captures the build shell to `loot_shrine.nbt`, writes
     `loot_shrine.json` with the metadata, reloads the manifest, tears
     down the build room, and teleports Alex to world spawn. The kit is
     removed from their inventory.

10. The room is now in the manifest and can be selected by the dungeon
    planner.

**What Alex learned:** open the kit, take blocks, place them, set
properties with the inspector, set metadata, save. No JSON, no commands
beyond `new` and the name prompt.

### 12.2 Scenario B: Experienced operator edits an existing encounter room

**Operator:** Sam, who has used the editor before. They want to add a
second trial spawner to `breeze_arena` and adjust the cooldown.

**Steps:**

1. Sam runs `/dungeon roombuilder rooms`.
   - The paginated rooms GUI opens. Sam finds `breeze_arena` in the list.
     The lore shows "Roles: encounter", "Spawners: 1", "Vaults: 0". Sam
     clicks it.

2. The system opens a build shell and stamps `breeze_arena` into it at
   rotation 0. Sam is teleported in. The kit appears in their inventory.
   - `breeze_arena` is a legacy room authored with `BaseRoomSpecs`, so the
     raw view shows a `pocketdungeons:spawn` jigsaw (not a trial spawner)
     and chests (not a vault). Sam runs `/dungeon roombuilder stamped` to
     see what the room looks like after stamping. The stamped preview
     shows the trial spawner and configured blocks. Sam runs
     `/dungeon roombuilder raw` to return to the editable view.
   - In the raw view, Sam sees the spawn jigsaw in the center and door
     jigsaws in the walls. This is what they edit.

3. Sam opens the kit, takes a Trial Spawner from slot 0, and places it
   in the northeast corner of the room.
   - The new spawner appears, inactive. Its block entity has
     `pd_authored` set. The existing spawn jigsaw remains in the center;
     Sam leaves it as a fallback anchor. At stamp time, the authored
     spawner takes precedence (section 3.2).

4. Sam takes the Inspector (slot 19) and right-clicks the new spawner.
   - The Trial Spawner properties GUI opens. Sam sets the config prefix
     to `breeze_arena` (matching the existing one), cooldown to 60
     seconds, and required player range to 14. They close the GUI.

5. Sam right-clicks the original spawner with the Inspector and changes
   its cooldown from 90 to 60 to match.
   - Both spawners now have the same config.

6. Sam opens the kit and clicks Test (slot 28).
   - The test dialog opens. Sam picks tier 2, the default theme, ominous
     off, and clicks Test.

7. The system captures the build shell to memory, stamps it into a second
   build shell through the real `LayoutStamper` pipeline, and teleports
   Sam into the test shell.
   - Both spawners activate. Sam fights the breeze mobs, checks that both
     spawners count toward the completion gate (the gate is disabled in
     test mode, but the spawners still spawn), and walks the room to
     verify layout.

8. Sam runs `/dungeon roombuilder revert`.
   - The test shell is purged. Sam is teleported back to the editor shell.
     The room is unchanged from before the test.

9. Sam opens the kit, clicks Metadata (slot 27), and clicks Save (slot
   49). Validation runs:
   - "Room has 2 trial spawners. All count toward the completion gate."
   - No errors. Sam types "breeze_arena" as the name.
   - The system backs up the previous `breeze_arena.nbt` to `versions/`,
     captures the new version, writes the metadata, reloads the manifest,
     tears down the build room, and sends Sam home.

**What Sam learned:** load from the rooms browser, place additional
spawners, adjust properties, test before saving, save over the existing
name with automatic backup.

### 12.3 Scenario C: Operator disconnects mid-build and resumes

**Operator:** Jordan, who is mid-way through building a complex corridor
room with traps and decor. Their internet drops.

**Steps:**

1. Jordan has been building for 20 minutes. They have placed 3 chests, 2
   spawn jigsaws, decor stairs, and a partial wall. They have not saved.

2. Jordan disconnects.
   - The system saves the build shell to `unfinished/<jordan_uuid>.nbt`
     and `unfinished/<jordan_uuid>.json` with a timestamp. The in-memory
     build room is preserved for 5 minutes.

3. Jordan reconnects 2 minutes later.
   - The in-memory build room is still alive. Jordan runs
     `/dungeon roombuilder resume`.
   - The system teleports Jordan back into the build room and re-issues
     the kit. All blocks, entities, and marker data are intact. Jordan
     continues building.

**Alternative: server restarts while Jordan is away.**

1. Jordan disconnects. The unfinished room is saved to disk.

2. The server restarts. The in-memory build room is gone.

3. Jordan reconnects the next day and runs
   `/dungeon roombuilder new`.
   - The system detects `unfinished/<jordan_uuid>.nbt` and opens a chat
     prompt: "You have an unfinished room from 2026-09-06T14:32:00Z.
     Resume it, or start fresh?"

4. Jordan clicks "Resume".
   - The system loads the unfinished template into a new build shell,
     teleports Jordan in, and re-issues the kit. The room is exactly as
     Jordan left it.

5. Jordan finishes the room and saves it as "trap_corridor". The
   unfinished room file is deleted on successful save.

**What Jordan learned:** the editor preserves work across disconnects and
restarts. No need to save before quitting.

### 12.4 Scenario D: Operator makes a mistake and uses undo

**Operator:** Riley, who is building an entrance hall. They accidentally
break a stair they spent time placing.

**Steps:**

1. Riley has placed a decorative stair wall along the east side of the
   room. They switch to creative mode to place a painting and
   accidentally break a stair in the wall.

2. Riley runs `/dungeon roombuilder undo`.
   - The system reverts the last block change. The stair is restored.

3. Riley places the painting and continues. Later, they realize they
   undid one stair too many. They run `/dungeon roombuilder redo`.
   - The last undone change is re-applied.

4. Riley finishes and saves. The undo history is discarded on save.

**What Riley learned:** undo and redo work like creative mode, but only
inside the build room. Burst-grouping means a wall placement is one undo
step, not 64.

### 12.5 Scenario E: Operator creates a room from an existing room

**Operator:** Morgan, who likes `entrance_hall` but wants a variant with
a different decor theme and an extra chest. They do not want to overwrite
the original.

**Steps:**

1. Morgan runs `/dungeon roombuilder rooms` and clicks `entrance_hall`.
   - The system stamps `entrance_hall` into a build shell. Morgan is
     teleported in. The kit appears.

2. Morgan removes the existing decor (using creative mode break) and
   places new decor: deepslate bricks, soul lanterns, and a different
   floor pattern. They use undo when they make a mistake.

3. Morgan opens the kit, takes a Chest from slot 8, and places it next to
   the existing chest. They take the Inspector and set the new chest's
   role to "supply".

4. Morgan opens the kit, clicks Metadata (slot 27), sets the roles to
   "entrance", and clicks Save. When prompted for the name, they type
   "entrance_hall_deepslate".
   - The system writes a new `entrance_hall_deepslate.nbt` and
     `entrance_hall_deepslate.json`. The original `entrance_hall` is
     untouched. The manifest is reloaded. Both rooms are now available.

**What Morgan learned:** load, modify, save as a different name is the
copy workflow. No copy/paste command needed.

### 12.6 Scenario F: Operator hits validation errors

**Operator:** Casey, who is building an encounter room but forgot to add
doors and set no roles.

**Steps:**

1. Casey builds an encounter room: places 2 trial spawners, decor, and a
   few chests. They do not place any door jigsaws.

2. Casey opens the kit and clicks Save (slot 30). Validation runs. Chat
   reports:
   - Error: "No roles selected. Set at least one in the metadata GUI."
   - Error: "Room has no door jigsaws. It will be unreachable in a
     dungeon. Place door jigsaws on at least one wall, or set the role
     to entrance."
   - Warning: "Encounter room has chests. They will be removed at stamp
     time unless the role is loot."

3. The save is blocked. Casey is still in the build room with all work
   intact.

4. Casey opens the kit, clicks Metadata (slot 27), and toggles the
   "encounter" role on. They click Save. Validation runs again:
   - Error: "Room has no door jigsaws. It will be unreachable in a
     dungeon."
   - The role error is gone, but the door error remains.

5. Casey closes the metadata GUI, opens the kit, takes a Door Jigsaw from
   slot 14, and places it on the north wall. The editor snaps it to the
   canonical slot (column 7, row 1). Casey places 5 more door jigsaws to
   fill the 2-wide, 3-tall slot. They repeat on the east wall.

6. Casey clicks Save again. Validation runs:
   - "Room has 2 trial spawners. All count toward the completion gate."
   - No errors. Casey types the name and the save completes.

**What Casey learned:** validation catches problems before save, reports
them with coordinates, and does not destroy work. Fix and retry.

### 12.7 Scenario G: Operator annotates a room for the next editor

**Operator:** Quinn, who is handing off a half-finished room to another
operator. They want to leave notes about what still needs to be done.

**Steps:**

1. Quinn runs `/dungeon roombuilder load unfinished_bridge` and is
   teleported into the build shell with the kit.

2. Quinn opens the kit, takes the Annotation tool from slot 19, and
   right-clicks the floor where a chest is missing.
   - A text input opens in chat. Quinn types "Add a supply chest here,
     tier 2." and presses Enter.
   - A floating `text_display` appears at that position with the note.
     It is tagged `pocketdungeons_author_note` and will not be saved with
     the template.

3. Quinn places a Barrier from slot 16 next to a wall and annotates it:
   "This wall needs a door jigsaw, facing east."

4. Quinn repeats for two more notes around the room.

5. Quinn runs `/dungeon roombuilder exit` to leave without saving.
   - A chat prompt appears: "Save your work as an unfinished room for
     later, or discard?" Quinn clicks "Save for later."
   - The system saves the unfinished room to disk (section 4.12), tears
     down the build room, and sends Quinn home.

6. Later, the next operator (Pat) runs `/dungeon roombuilder load
   unfinished_bridge`.
   - Pat sees the floating annotations in the room and reads Quinn's
     notes. Pat places the missing chest and door jigsaw, removes the
     barriers, and saves.

7. Pat runs `/dungeon roombuilder clear-annotations` to remove all
   annotation markers before saving.
   - The `text_display` entities are removed. The save captures a clean
     room with no annotations.

**What Quinn learned:** annotations are visible floating text, do not
affect the saved room, and can be cleared before save. Handoff is
self-documenting.

### 12.8 Scenario H: Operator tests a room at different rotations

**Operator:** Avery, who built a room with a vault facing south and wants
to verify it works at all rotations.

**Steps:**

1. Avery finishes building the room and opens the kit, clicks Test (slot
   28), picks tier 2, default theme, ominous off, rotation 0, and clicks
   Test.
   - The test stamp runs at rotation 0. Avery walks in, checks the vault
     facing, and confirms it faces south.

2. Avery runs `/dungeon roombuilder revert` to return to the editor.

3. Avery reopens the test dialog, sets rotation to 90, and clicks Test.
   - The test stamp runs at rotation 90. The vault now faces east, as
     expected from the structure template's rotation of `VaultBlock.FACING`.
     Avery confirms the spawners and decor are also correctly rotated.

4. Avery repeats for 180 and 270, confirming each time. All rotations
   produce a correct room.

5. Avery reverts to the editor and saves.

**What Avery learned:** the test dialog includes a rotation selector, so
all four rotations can be verified without running a full dungeon. This
validates the structure template's rotation handling for authored blocks
(section 5.8).

### 12.9 Scenario I: Operator clears a room to start over

**Operator:** Taylor, who loaded a room, made extensive changes, and
decides none of it works. They want a blank canvas without leaving the
editor.

**Steps:**

1. Taylor has been modifying a room for 15 minutes. The changes are bad.
   They do not want to save any of it.

2. Taylor runs `/dungeon roombuilder clear`.
   - The system clears the build room interior to a fresh empty shell:
     floor, walls, ceiling, no content. Taylor stays in the room. The kit
     stays in their inventory.

3. The on-disk template is untouched. Taylor's changes were only in the
   build shell and are now gone.

4. Taylor starts building from scratch with a clear plan.

**What Taylor learned:** clear gives a blank canvas without leaving the
editor or affecting the saved room. It is destructive to the current
build shell but not to disk.

### 12.10 Scenario J: Two operators collaborate via annotations

**Operator:** Drew and Sky, two server admins building a large room
together. The editor supports one build room per player, so they work
sequentially with annotations as the handoff.

**Steps:**

1. Drew runs `/dungeon roombuilder new` and builds the first half of the
   room: floor pattern, walls, two trial spawners.

2. Drew annotates the empty half: "Sky, build the loot section here.
   Vault on the south wall, two supply chests flanking it."

3. Drew runs `/dungeon roombuilder exit`. The unfinished room is saved to
   Drew's UUID.

4. Sky runs `/dungeon roombuilder load <drews_room_name>`. But the
   unfinished room is tied to Drew's UUID, not to a room name. Sky cannot
   load it directly.

   **This is a gap.** The unfinished-room system (section 4.12) is
   per-player, not shared. For collaboration, Drew would need to save the
   room under a name first, then Sky loads that name.

5. Drew re-enters, saves the room as "collab_wip", and exits. Sky runs
   `/dungeon roombuilder rooms`, clicks "collab_wip", and continues
   building. Sky sees Drew's annotations (they were saved as part of the
   template? No, annotations are stripped before capture).

   **Second gap.** Annotations are stripped before capture (section 4.9).
   They do not survive save. For collaboration, the operators would need
   to use an out-of-band channel (chat, signs in the room, a shared doc)
   or the annotation system would need a "persist as saved entity" mode.

**Note for implementers:** This scenario reveals two collaboration gaps:
(1) unfinished rooms are per-player, not shared; (2) annotations do not
survive save. Neither is a blocker for the initial phases, but both
should be noted for future work. A "save as WIP with annotations" mode
could address both.

**What Drew and Sky learned:** the editor is designed for single-operator
sessions. Collaboration is sequential via named saves, not via shared
unfinished rooms or persistent annotations.

---

## 13. What this spec does not change

- The dungeon planner, graph layout, and room selection.
- The `DungeonRoomMeta` field set (only the authoring path changes).
- The `BaseRoomSpecs` Java-authored rooms (they stay as shipped defaults).
- The `Situations` handler interface (handlers still receive the same
  context; they just get authored blocks in the cell when the author
  placed them).
- The `JigsawReplacementProcessor` pipeline. `JigsawFallback` is
  unchanged for authored blocks; only the hazard placement methods gain
  the skip-authored-blocks check (section 5.9).
- The `BedrockEnvelope`, `RoomProtection` shell check (extended to build
  rooms but not changed structurally).
- The completion gate math (75% threshold, `activeSpawners` set).
- The affix system itself (OMINOUS, SWARMING, OVERCLOCKED, SILENCED,
  MOLTEN, EXPLOSIVE, FERAL, voided floor). Only the hazard placement
  methods change to skip authored blocks.
