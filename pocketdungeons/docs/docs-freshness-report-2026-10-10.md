# Docs and lore freshness report, 2026-10-10

Branch `docs/freshness-2026-10-10`, cut from `feature/bug-testing-and-refinement` (HEAD 8a24a23). Scope: living
references, the Lemon pack, the ten skills, diaries, dungeon and floor notes, affix and bag blurbs, `LIVE_CHECKS.md` and
`AGENDA.md`. Nothing under `src/main/java`, `src/test`, `src/gametest` or any `.nbt` was touched. Every claim added was
checked against the code or data first; the places where that was not possible are listed at the end.

## 1. The most important finding

`pocketdungeons/docs/INTEGRATION.md` on this branch's HEAD is a 45.9 MB, 955,356 line file that contains no document at
all, only one paragraph about `kit_baseline` repeated tens of thousands of times with a stray character between copies.
Commit 1dea309 ("Fix review findings ... INTEGRATION.md and the schemas drop the kit top-up") grew it from 45,689 bytes
(the 9cf44c0 version) to 44,947,130 bytes: a find and replace that ran away. The published datapack reference was
therefore unreadable. Fixed by restoring the 9cf44c0 text and re-applying what 1dea309 meant to do (the bag section
now says the kit is handed over once and nothing tops it up; the `kit_top_up_scale` zone rule row is gone), then
bringing the rest up to date (section 3 below). The bad 45 MB blob stays in git history.

## 2. Inventory

Status key: fresh = checked, no change needed; fixed = stale and corrected; owner = needs an owner decision (see 5);
historical = left alone on purpose.

### Living references and tools

| File | Status |
|---|---|
| `docs/INTEGRATION.md` | fixed (corrupted file restored, new sections, see 3) |
| `docs/schema/dungeon_room.schema.json` | fixed (nine fields rooms use were missing) |
| `docs/schema/dungeon_bag.schema.json` | fresh (1dea309 had already corrected it) |
| `docs/schema/dungeon_theme.schema.json`, `dungeon_adventure`, `diary`, `anomaly_room` | fresh |
| `docs/DUNGEON_STRUCTURE_DESIGN.md` | fixed (current-state banner; body is the 2026-10-05 design) |
| `docs/ZONES_SPEC.md` | fixed (status banner; Mansion still unbuilt) |
| `docs/DIALOGS_SPEC.md` | fixed (pointer to what was built) |
| `docs/reference/DIALOGS.md` | fixed (current screens block: Home menu, Manage Room, Inspect Compass, Manage Party, bans) |
| `docs/LEMON_AGENT.md` | fixed (context fields, mechanics list) |
| `docs/LEMON_SPEC.md` | fixed (status said "Not built"; vocabulary) |
| `docs/PLAYTEST_EVENTS.md` | fixed (eight undocumented events added, eight rows corrected) |
| `docs/reference/BUGS.md` | fixed in one place (PD-181 update, literal `'` escapes removed); otherwise fresh |
| `docs/reference/THEME_TABLE.md` | fixed (sensor_gallery retired, Kennels spawner row) |
| `docs/reference/THEMED_MERCHANTS.md` | fixed (merchant table rebuilt from `MerchantThemes`, lapis) |
| `docs/reference/FINAL_FLOOR_ROOMS.md` | fixed (Kennels, finale, Endless Mine note appended) |
| `docs/reference/ORDEALS.md` | fresh |
| `docs/DISCOVERIES.md` | fixed (trap 9, one mixin budget, now 18) |
| `README.md` | fixed (key features were keystone era; tracker screen and Cube station no longer exist) |
| `CONVENTIONS.md` | fixed (the one mixin budget; 18 are registered) |
| `plans/COMPLETED-MILESTONES.md` | fixed (appended the haul, Astrolabe and design pass section) |
| `tools/server/README.md` | fixed (deploy never builds, one jar in `dist/`) |
| `tools/lemon/GUIDE.md` | fixed (rewritten; it described keystones, bands, refills and "quit costs a level") |
| `tools/lemon/out/pack.md` | regenerated with `node tools/lemon/build-pack.mjs` (13k tokens) |
| `tools/lemon/README.md`, `tools/room-editor/README.md` | fresh |
| `tools/lemon/HINTS.md` | owner (empty by design; no room has a hint ladder) |
| `docs/playtests/LIVE_CHECKS.md` | fixed (see 4) |
| `docs/playtests/AGENDA.md` | fixed (A4 retired, notes on A1, A2, A5, A6) |
| `docs/playtests/BALANCE.md` | fixed (the "Band at bank" column is explained) |
| `docs/AUDIT_2026-09.md` | historical; one supersession note added under section 11 because the Lemon pack quotes that section |
| `docs/decision-2026-10-09-playtest-design-pass.md` | historical; only literal `'` escapes repaired |

### Skills (`.claude/skills`, at the workspace root)

| Skill | Status |
|---|---|
| `tune-knobs` | fixed (whelp knobs added; `SCRAP_PER_CHART` and the "else 1" pay rule were gone; Feral knob marked retired) |
| `dungeon-content` | fixed (`'` escapes, node `level` and `minNodeRooms`, `deepBlocks`, finale limits, the "pack is stale" line) |
| `room-building` | fixed (new section on rules every room now meets: sculk arming, spawn rules, fire, wolves, finale) |
| `player-text` | fixed (omen added to the banned words; FAILED, QUIT, CLEARED, Heard and Leave examples) |
| `playtest` | fixed (BALANCE row guidance) |
| `add-tests` | fixed (pointed at the retired `TollRoomGameTest`) |
| `design-brief` | fixed (DUNGEON_STRUCTURE_DESIGN is no longer "the current reference") |
| `dialog-screens`, `deploy-test-server`, `playtest-fix` | fresh |

### Lore and prose data

| Item | Status |
|---|---|
| `docs/reference/LORE.md` | fixed (status block: diaries shipped, echo shards retired, Cube off, Feral retired) |
| `docs/reference/LORE-STORY.md` | fixed (one sentence said the compass is minted from echo shards) |
| `docs/reference/LORE-DIARIES.md` | fixed (note: only entries 1 to 7 are drafted here; 26 ship) |
| `diary/entry_1..26.json` | fresh (checked all 26 for wolves, sensors, tolls, keystone; entry 26 fits the Kennels, entry 17 fits the Heard rule) |
| `dungeon/*.json` notes (20 dungeons, about 90 floor notes) | one fixed (`kennels` Bone Yard), the rest fresh |
| `dungeon_affix/*.json` blurbs | one fixed (`silenced`), `feral` left (retired, see 5) |
| `dungeon_bag`, `content_module` blurbs | fresh |
| `docs/reference/SITUATIONS_*`, `ROOM_AUTHORING_SPEC`, `ROOM_UX_PLAN`, `D3_PROGRESSION_PLAN`, `DOOR_LADDER_BRAINSTORM`, `PLAN`, `ROADMAP`, `VISION`, `PMD_BRAINSTORM`, `ROGUELITE_CONTENT_BRAINSTORM`, `SALVAGE_PROPOSAL`, `MYTHIC_PLUS_RECONCILIATION`, `LIVE_TEST_PASS`, `AUDIT_FOLLOWUP_PLAN`, `DUNGEON_FORMATS_SPEC` | historical |
| All `docs/plan-*`, `handoff-*`, `review-*`, `design-*`, `decision-*`, `d3-handoffs/*`, `playtests/20*.md`, `AUDIT.md`, prompts, `BAG_*`, `ROOM_FIXES.md`, `AUTHOR-EXERCISE.md`, `DISCOVERIES.md` body | historical |

## 3. What changed, per file

- `INTEGRATION.md`: restored and de-duplicated as above. New section 1.10 documents `dungeon/*.json` (name, act, kind,
  `mainTheme`, `baseLevel`, `lootBand`, `nodePalette`, `hall`, `token`, `notes`, `hiddenOre` with `deepBlocks` and
  `deepEvery`, `finale`, `mobUniform`, nodes with `gear:<slot>:<tier>` rewards, edges with `lives`, the adventure-node
  rule). Section 2 table gained `spanY`, `dungeons`, `acts`, `graphRole`, `borrowableBy`, `light`, `requiresLight`,
  `corridor`, `biome`, `nodes`. Affix section gained `undead_rise_chance` and `_ominous` (Restless), the `OMEN`
  consumable rule (Silenced) and the list of shipped affixes with Feral retired. Cube recipe section says the station
  is not registered and the `feral` recipe is gated at level 999. A mangled `\n` that split "keyed by namespace:path"
  was repaired.
- `README.md`: key features rewritten (compass, haul, lives, Astrolabe Room, sidebar, lodestone menu, keys); removed the
  Herobrine Cube extract and the tracker screen (removed 2026-10-02) and the smithing table claim (reroll is the
  enchanting table).
- `LIVE_CHECKS.md`: see 4.
- `PLAYTEST_EVENTS.md`: added `floor_pay`, `haul_banked`, `emeralds`, `hazard`, `shop_sale`, `fountain`, `diary_handed`,
  `room_scan`. Corrected: `omen_rise` sources are `death` and `door` only (dwell, sensor, shriek, bargain, silence and
  vault write `hazard`); `edge_taken.cost` and `door_commit.fuel_spent` now hold lives; `dungeon_finished` has
  `emeralds`, not `shards`; `salvage` fields; `quit_floor` penalty is always 0.
- `GUIDE.md` (and so `pack.md`): the whole current model in Lemon's words, with an explicit "this guide wins" over the
  September log that the pack still embeds.
- Everything else as the inventory says; each edit is a banner, a pointer or a table row, in the document's own voice.

## 4. LIVE_CHECKS.md and AGENDA.md

- L55 to L60 and L62 had no `Status:` line, so `build-pack.mjs` (which lists only `owed` rows) never showed them to
  Lemon. All now say `owed`; the pack lists them.
- Retired or superseded rows that still said `owed` and so were still being handed to Lemon: L15 (Lock in is gone), L23
  (the heading said retired, the status said owed), L27 (echo shard branches). L53 marked superseded by L62. L20 notes
  `sensor_gallery` is gone. L17, L36, L51, L54 had stale wording corrected (echo shard reward, door 3 Mine, anvil, `/5`).
- L58 and L59 questions rewritten for the whelp and the hostile Kennels wolves ("do the stray wolves take bones" is
  now meaningless).
- New rows for things built and never checked: L63 (lodestone menu, bans), L64 (leaving, failing, Home room), L65
  (lapis trades, trimmed scrap, floor history words, sidebar). The wide preview wall and the glinting lever were added to
  L55; the price check to L56.
- No `owed` row was marked passed: none of the newer playtest reports settles them (2026-10-09-1.md still lists L1, L4,
  L8, L9, L11, L12, L14 to L20, L21, L27, L29, L31 to L36, L39 to L54 as owed).
- AGENDA: A4 (kit top-up) retired; dated notes added to A1 (lives and sidebar), A2 (haul, no bands), A5 (Astrolabe Room),
  A6 (no fuel, no random front doors). Evidence entries untouched.

## 5. Lore proposals awaiting the owner (not applied)

1. **Ancient City `sensor_hall` floor note**, now "Sensor halls. One loud step and they come." One step does not bring
   anything any more; the meter fills and a whelp comes. Suggested: "Sensor halls. Too much noise sends a small Warden."
   (Ancient City is Act 2, so `FloorNotesTest` covers it; the suggestion names no ore.)
2. **`dungeon_affix/feral.json` blurb** still says "Swing and they are lost, feed them and they are yours." Feral is
   retired (`min_level` 999) so no player sees it. Suggested: leave it, or reword to "Feral: retired. Restless took
   its place."
3. **Silenced blurb**, fixed to "every third consumable you use sends a wave" because the old text said it raised the omen
   (omen is lives lost now). That ties the words to the `silencedConsumablesPerOmen` default of 3. Alternative that does not:
   "Silenced: consumables are loud. Use them and a wave comes."
4. **Kennels Bone Yard note**, changed from "Skeletons drop the bones the hounds want." (feeding is gone) to "Skeletons in
   the yard and hounds at your back." Owner may prefer something with more bite, for example "Skeletons on the walls and
   wolves in the yard."
5. **Diary 17, The Quiet City**, still lands, but it could acknowledge the whelp: after "It only ever wakes itself" a page
   such as "Sometimes it sends something small to look first. Do not run." Story direction, so left.
6. **LORE.md section 2 and 7** present the Herobrine Cube as "his instrument" and a Designed coupling, but the Cube
   station is switched off. Either keep the fiction waiting for the J8 redesign or note it as cut.
7. **Gear-loft style floor notes** (Copper Works Gear Loft, Deepslate Collapsed Landing and four more) now promise rolled
   gear on the door board; the skill says notes should not repeat the deal sheet, so no change proposed.
8. **`tools/lemon/HINTS.md`** is empty, so Lemon has no nudge or clue for any puzzle room (rotation_lock, frame_lock,
   hold_the_plate, sorting_floor, Hush Gallery and the rest). Owner writes these.

## 6. Found outside my scope (for the code audit)

- `DialogScreens.menuOptions`: the in-dungeon Quit Door tooltip reads "Fail the dungeon, downgrade your compass, pick a new
  door". The confirm screen and `RunLifecycle.quitDoor` leave the compass alone.
- `HostileWolves`, `KennelSpecs` javadoc carry literal `'` sequences (cosmetic; they read as an apostrophe to javac).
- Stale comments: `ScrapMath` class javadoc ("chart progress 0 to 4"), `SalvageStation.classify` ("keys never leave their
  floor", "the safe room tops the kit back up"), `PressureSources` class javadoc ("one-mixin budget", "a wave per five
  pulses", "the fourth pulse wakes the Warden"), `Instances` around line 2103 ("unbanked floors pay nothing"), `FeralContent`.
- `BUGS.md` PD-187 and PD-190 still open with their wave A slices built; the ledger entries read "Open".

## 7. Could not verify

- Everything marked "awaiting live check" in `BUGS.md` and the new LIVE_CHECKS rows; I only confirmed the code says so.
- That the Home room after a failed dungeon is the same room the lodestone menu calls Home (the ledger and
  `Instances.failReturnsHome` say so).
- Test counts for the COMPLETED-MILESTONES section (not re-run, as the section says).
- The mixin count of 18 is the entries in `pocketdungeons.mixins.json`, not a check that each one is active.
- I did not run any build or test. JSON edited (`kennels.json`, `silenced.json`, `dungeon_room.schema.json`) parses with
  Python `json.load`; `FloorNotesTest` was not run on the new Kennels note.
