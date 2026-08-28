# M26 - Lore delivery - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `DungeonLog.java`: `Entry` record. New `diaryBandsSeen` set goes here,
   same shape as `completedThemes`.
2. `Instances.java`: `reconcileKeystones` method. Compass pointing reuses
   this hook.
3. `ROOM_UX_PLAN.md`'s `## M26` section ONLY: authoritative scope, including
   the VISION.md section 4 tension on compass pointing.

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
3. Book content: pre-authored pages, title "Alex's Diary, Entry N". Ship
   one proof entry. Full authoring is a separate content pass.

### Step 2: Compass pointing during runs

1. `Instances.reconcileKeystones`: during a run, set keystone's
   `LODESTONE_TRACKER` to terminal cell's pad position. Existing hook runs
   on watcher interval, rewrites every online player's keystone in place.
2. On run completion: re-point `LODESTONE_TRACKER` at the room's own
   lodestone (the one stamped in front of the player).
3. In overworld (no instance): compass spins. No target in that dimension.

### Step 3: VISION.md update

1. Doc edit: update `VISION.md` section 9's "not a lore project" line.
   Lore is allowed to exist and be found, on its own tonal register,
   without touching mechanical naming's voice.

### Step 4: Cosmology reference

1. No code. Internal reference doc for content authoring: Herobrine's
   fractured memory, the containment arc. Retroactively explains existing
   palettes and mechanics without any in-game text.

## Constraints

- No lore text in mechanical naming (item names, affix names, chat
  messages). Lore lives in books only.
- Diary books: one per intensifier band, no duplicates. `diaryBandsSeen`
  prevents re-drops.
- Compass pointing: verify against 26.2 jar whether mod-placed lodestones
  register in POI manager. If yes, `tracked: true` self-heals on teardown.
  If no, `tracked: false` and mod owns staleness.
- VISION.md section 4 tension: compass that swings to a new bearing is a
  signal, not a message. Deliberate call: this enhances discovery without
  breaking silence.

## Verification

- `build_mod` default `build` after each step.
- Headless: `diaryBandsSeen` codec round-trips. `reconcileKeystones`
  compiles with new pointing logic.
- Live: enter new band, find diary in next completion chest. Keystone
  compass points at terminal pad during run, room lodestone after
  completion, spins in overworld. Record in `LIVE_TEST_PASS.md`.
- Done when: diary drops on band crossing, compass points correctly in
  all three states, no lore in mechanical naming.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M26-handoff-completed.md` once landed.
