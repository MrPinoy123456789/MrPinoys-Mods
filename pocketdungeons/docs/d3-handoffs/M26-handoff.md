# M26 - Lore delivery - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `DungeonLog.java`: `Entry` record. New `diaryBandsSeen` set goes here,
   same shape as `completedThemes`.
2. `Instances.java`: `reconcileKeystones` method. Compass pointing reuses
   this hook.
3. `ROOM_UX_PLAN.md`'s `## M26` section ONLY: authoritative scope, including
   the VISION.md section 4 tension on compass pointing.
4. `docs/reference/LORE-DIARIES.md`: all 7 diary entries are already
   authored here. This milestone wires them into the drop mechanism; it
   does not author new prose.

## Dependencies

Grep `reconcileKeystones` in `Instances.java`. If method exists, compass
update hook is ready.

Grep `LODESTONE_TRACKER` in `src/main/java/`. If keystone items carry this
component, compass pointing is viable.

## Goal

Lore lands as fragmentary, discoverable content, not an exposition dump.
Diary books as loot at intensifier-band crossings. Keystone compass points
at terminal pad during runs, at room lodestone after completion. Mechanical
naming stays terse per VISION.md section 4.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: Diary storage and delivery

1. `DungeonLog.Entry`: add `diaryBandsSeen` (Set<Integer>, default empty).
   Codec with safe defaults, same pattern as `completedThemes`.
2. `RunLifecycle.completeRun`: after chest generation, check if player
   crossed a new intensifier band. If yes and band not in `diaryBandsSeen`:
   inject a `written_book` into the completion chest. Add band to set.
3. Book content is already authored in `docs/reference/LORE-DIARIES.md`
   (7 entries: The Gift, The Wrong Rooms, The Whole Room, The Seal, The
   Other, The Becoming, The Work). The missing piece is a data file that
   maps intensifier band to entry text, so `completeRun` can inject the
   right book without hardcoding prose into Java. Ship as a resource
   (JSON or datapack book) under `pocketdungeons:diaries/`.
4. Band-to-entry mapping: the diaries' own header says "Found out of
   order. Out of order, on purpose." The mapping is intentionally
   non-sequential: band 1 does not necessarily drop Entry 1. Decide and
   document the mapping before wiring the drop.

### Step 2: Diary reader in the lodestone menu

1. `DialogScreens.menuOptions`: add a "Diaries" option to both the
   overworld and in-dungeon menus. New `ACTION_DIARIES` constant, same
   pattern as the existing `ACTION_MANAGE_ROOM` etc.
2. New diary list screen: shows all 7 entries. Discovered entries show
   their title (e.g. "Entry 3: The Whole Room"); undiscovered entries
   show "???" as both label and tooltip. Clicking a discovered entry
   opens a reader sub-screen; clicking "???" is a no-op or greyed-out
   button. Back button returns to the lodestone menu, same pattern as
   the existing sub-screens (`whitelistAdd`, `manageRoom`, etc.).
3. Reader sub-screen: shows the entry's pages as dialog body text, one
   page per body section. The source text is the same data file from
   Step 1's `pocketdungeons:diaries/` resource, so the reader and the
   physical book never diverge. Back returns to the diary list.
4. Discovery tracking: an entry is "discovered" when its book has been
   found in a completion chest. `diaryBandsSeen` already tracks which
   bands have dropped; the band-to-entry mapping (Step 1 item 4)
   resolves which entry each band unlocks. The diary list screen reads
   this to decide title vs "???".
5. The physical `written_book` drop (Step 1) and the lodestone reader
   are two views of the same discovery: the book is the in-world
   moment of finding, the menu is the permanent re-readable collection.
   A player who loses or drops the book can still re-read any entry
   they have discovered from the lodestone menu.

### Step 3: "Alex's Room" shell unlock on Entry 6

1. `RoomBuilder`: add a new `ShellPalette` named `alexs_room`, display
   name "Alex's Room". Block choices evoke a childhood home: warm wood
   floor and walls (spruce or birch), a domestic ceiling. Add to
   `SHELL_PALETTES` alongside the existing four.
2. Unlock trigger: when the diary drop in `RunLifecycle.completeRun`
   adds a band to `diaryBandsSeen` and that band maps to Entry 6
   (The Becoming), also call `log.unlockShell(player, "alexs_room")`.
   This reuses the existing `DungeonLog.unlockShell` path verbatim;
   no new unlock mechanism, just a new trigger for it.
3. The unlock is permanent and per-player, same as every other shell
   unlock: it survives a room reset. The player can swap to it from
   the "Change Shell" menu option (M24) any time after discovery.
4. No token item: this shell is never a loot drop and never a
   right-click unlock. The only path to it is finding Entry 6.
   `DialogRouter.unlockShell` (the token path) is not involved.
5. Thematic link: Entry 6 describes "a room that looked like my
   childhood home. It was almost perfect. The window was on the
   wrong wall." The shell is the player's room becoming that
   remembered room, after Alex's words about becoming the place.

### Step 4: Compass pointing during runs

1. `Instances.reconcileKeystones`: during a run, set keystone's
   `LODESTONE_TRACKER` to terminal cell's pad position. Existing hook runs
   on watcher interval, rewrites every online player's keystone in place.
2. On run completion: re-point `LODESTONE_TRACKER` at the room's own
   lodestone (the one stamped in front of the player).
3. In overworld (no instance): compass spins. No target in that dimension.

### Step 5: VISION.md update

1. Doc edit: update `VISION.md` section 9's "not a lore project" line.
   Lore is allowed to exist and be found, on its own tonal register,
   without touching mechanical naming's voice.

### Step 6: Cosmology reference

1. No code. Already covered by `docs/reference/LORE-DIARIES.md`: the
   containment arc (Steve/Herobrine's fractured memory, the seal, the
   recursive "you become the seal" loop) is implicit across entries 3-7.
   If a separate internal reference doc is still wanted for content
   authoring (mechanics-to-lore cross-references, palette rationale),
   create it; otherwise this step is done.

## Constraints

- No lore text in mechanical naming (item names, affix names, chat
  messages). Lore lives in books and the lodestone diary reader only.
- Diary books: one per intensifier band, no duplicates. `diaryBandsSeen`
  prevents re-drops.
- Diary reader: undiscovered entries show "???" for both title and
  pages. Discovered entries are re-readable from the lodestone menu
  even after the physical book is lost. The reader and the book share
  the same source text; they never diverge.
- Alex's Room shell: unlocked only by discovering Entry 6, never by
  token or prestige. Permanent and per-player, same as all shell
  unlocks. No lore text in the shell name or tooltip beyond
  "Alex's Room"; the thematic link is implicit.
- Compass pointing: verify against 26.2 jar whether mod-placed lodestones
  register in POI manager. If yes, `tracked: true` self-heals on teardown.
  If no, `tracked: false` and mod owns staleness.
- VISION.md section 4 tension: compass that swings to a new bearing is a
  signal, not a message. Deliberate call: this enhances discovery without
  breaking silence.

## Verification

- `build_mod` default `build` after each step.
- Headless: `diaryBandsSeen` codec round-trips. `reconcileKeystones`
  compiles with new pointing logic. Diary list screen shows "???"
  for undiscovered entries and real titles for discovered ones.
- Live: enter new band, find diary in next completion chest. Open
  lodestone menu, Diaries option shows the new entry as discovered
  and the rest as "???". Read the discovered entry's pages from the
  reader. When Entry 6 is the one discovered, "Alex's Room" appears
  in the Change Shell menu. Keystone compass points at terminal pad
  during run, room lodestone after completion, spins in overworld.
  Record in `LIVE_TEST_PASS.md`.
- Done when: diary drops on band crossing, lodestone menu shows
  discovered entries readable and undiscovered as "???", Alex's Room
  shell unlocks on Entry 6 discovery and is selectable from Change
  Shell, compass points correctly in all three states, no lore in
  mechanical naming.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M26-handoff-completed.md` once landed.
