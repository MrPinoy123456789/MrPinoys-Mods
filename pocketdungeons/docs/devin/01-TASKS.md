# Tasks for Devin

Order matters where a task says "Needs". Everything is Java plus data in `pocketdungeons/`. Sizes: S small, M medium, L large.
Section C lists unfinished carry-over from the sub-agents; do those first, because later tasks reuse them.

## What already exists (merged on `feature/act-1-2-roadmap`)

- `FinaleScript` seam: `FinaleScript`, `FinaleContext`, `FinaleScripts.registerAll()`, `FinaleWave` (task T01). Read the
  `finale-script` skill. One class per dungeon, one registration line. Hooks: `onTriggered`, `spawnWave` (return true to
  replace the data wave), `onWaveSpawned`, `onTick`, `onMobDied`, `onHalfDead`, `onWon`, `onFloorEnded`. Extra mobs added
  through the context count toward the wave, so the pad stays shut.
- Finished finale scripts to reuse: `KennelsFinale` (handler windup, pack scale and anger, scoreboard team),
  `InfestationFinale` plus `CreepingBricks` (the Queen, burrow, brick adds), `OssuaryFinale` (coffins), `LushFinale` plus
  `PotionDrip` (giant slime, potion rain), `MineshaftFinale` (dark phase, may still have failing tests, see C).
- `Haunts` director with `dungeon_haunt/the_counted.json` (Ossuary).
- `MineGuardians` (the Endless Mine guardian hook), `EndlessMineRules`, the Deep Mine as data (`endless_mine_deep`), tier
  tables `loot_table/endless_mine/`.
- `Wager` and the Blood affix; the copper chest pays an extra piece by wager; the board shows "Loot n%".
- Blackout affix, widened Restless, Overclocked par pay, trapped Loaded caches.

## A. Tasks

### D1. Cow Pits: Specimen 0 and the Milking Parlour (id T30, M)

Design: `docs/boss-workshop-2026-10-11.md` row 8; `docs/concepts-2026-10-11.md` (Cow Pits). Keep the tone deadpan and
contained-anomaly (the owner loves it): numbered cows, quartz wards, "Specimen 0 (do not feed)".
Read `CowPits.java`, `CowWardSpecs.java`, the cow section of `ResourceBiomeSpecs.java`, `dungeon/cow_pits.json`.

1. The Milking Parlour floor. `cow_pits.json` becomes three floors: Pen Gate (layer 1), the Milking Parlour (layer 2,
   new), The Big Barn (layer 3, final); every path exactly 3 floors. A clinical dairy: quartz and white tile, milking stalls,
   numbered specimens (match `CowPits.WARD_CELL_NAMES`). Two or three new rooms (room-building skill). Remove the
   borrowed mineshaft rooms from the Cow Pits `roomBias`. The finite herd rule stays: update `CowPits.FLOOR_MIN`,
   `FLOOR_CAP`, `ROOM_COWS`, `ROOM_LIMITS` and `CowPitsTest`. The parlour pays a small, finite number of milk buckets
   (a knob); no infinite milking.
2. Specimen 0 as an optional final beat. The Big Barn holds the pedestal cow "Specimen 0 (do not feed)" with a sign
   saying so (today the pedestal exists only in `cow_ward`; add it to a Big Barn room). Nothing gates the pad at the
   start. If a member feeds Specimen 0 (right-click with any food; wheat is banned dungeon wide), a finale wakes:
   Specimen 0 becomes a huge hostile cow (`Attributes.SCALE`, knob start 3.0, boss bar), and the herd cows turn on the
   party (tagged, capped by the floor cap). While it lives the pad stays shut. Beating it pays the extra reward chest
   (`FinaleWave.rewardChests`). Obeying the sign skips the fight and the reward chest. A tell of at least 2 seconds
   precedes the herd turning (a moo, the title line "Specimen 0 wakes.").
3. The seam may need an optional trigger: today a finale triggers when the floor's spawners are cleared. Add a small
   separate commit that gives `FinaleScript` an optional `shouldTrigger` hook (and, if needed, adopting an existing mob as
   the elite). A partial version of exactly this exists on the sub-agent branch `feature/rm-t30-cow-pits` (commit
   `a15a3c04`, files `FinaleScript`, `FinaleContext`, `FinaleWave`, `FinaleScriptGameTest`); use it if you can reach it.
4. Tests: `CowPitsTest` for caps; a gametest that every path through the dungeon is 3 floors; a gametest for the pedestal
   trigger; seam tests for the optional trigger.
Acceptance: all tests green; pad opens at once when the sign is obeyed; pad stays shut while an awakened Specimen 0
lives; nothing placed remains after the floor ends.

### D2. Copper Works: clock, vents and vats (id T32, M)

Design: `docs/boss-workshop-2026-10-11.md` row 10; `docs/concepts-2026-10-11.md` (Copper Works). `hiddenOre` is already
declared. Dungeon id `copper_works`, a 4 floor run; mobs wear copper; final floor The Master Furnace (room
`master_furnace`); data wave 4 husks and 2 skeletons; elite "The Foreman" (vindicator with a copper axe, 60 health).

1. `CopperClock` (new) plus pure `CopperClockRules`: a dungeon wide countdown (diary entry_13: "It was counting down to
   something"). Starts when the trip enters the dungeon (knob, 20 minutes). Shown to members on the action bar or a boss
   bar ("The furnace: 12:40"), red in the last two minutes. Each cleared spawner adds time back (knob). At zero the
   furnace blows: a soft failure, not a run failure: the finale wave arrives early (the Foreman's wave spawns as soon as
   the party reaches the final floor, without the usual clear-the-spawners trigger or countdown) and the vents run
   faster. Never kills anyone; explained on the first floor by a title line. Tick it from the same watch tick other floor
   systems use (see `Haunts.tick`, `FinaleWave.tick`). If the clock cannot survive a server restart, say so in the report.
2. `CopperFinale` (a `FinaleScript`): the `master_furnace` room gets vents that pulse on a readable rhythm (a hiss and
   rising smoke 2 seconds before each pulse, then fire for 1 second), burning anyone on a vent tile, sweeping in a fixed
   order, plus water vats (cauldrons or pools). The Foreman weakens (Weakness and Slowness, extra damage, knobs) when he
   steps into a vat. Keep an exit path clear of the vents and the pad; never put a vent where a member spawns. Everything
   placed is removed or restored in `onFloorEnded`. If the clock ran out the pulses run faster.
3. Tests: pure tests for the clock arithmetic and pulse schedule; gametests per the `finale-script` skill.
Acceptance: green tests; every tell at least 2 seconds; the room can always be exited.

### D3. Lush Caves rooms: the swamp floor (id T25, M to L)

Needs the Lush Caves engine (already merged: `PotionDrip`, `LushFinale`). Design: `docs/concepts-2026-10-11.md` (Lush Caves).
Dungeon id `rootworks` (shown as Lush Caves), a 4 floor run from a 7 floor pool. Story: witches brew potions and poisons on
the upper floors; the lower floors take the brews dripping in from above (the drip starts on floor 3 of the run).
Build the upper floors' rooms (floors 1 and 2 of the run, the pool nodes Moss Gate, Glow Berry Hollow, Clay Burrow,
Wolf Hollow): a swamp with witch haven rooms (witch huts, cauldrons; vanilla witch spawners or a witch encounter), fungal
skeleton archers (the vanilla `bogged`), clay shelves with vines to climb and navigate (movement, not combat), mushrooms,
dead end pond rooms. Use the `room-building` skill (Java `RoomSpec`, template `.nbt` generated through a throwaway
gametest, a `dungeon_room` manifest JSON, node entries in `rootworks.json` `roomBias`). Four to six rooms is enough.
Also: the `sapping_den` node's `infested_wall` room belongs to the Infestation dungeon; replace it in `rootworks.json`.
Do not change `PotionDrip` or `LushFinale`. Tests: `RoomLibraryGameTest`-style checks for each new room (door lanes,
no hazard on the lanes, the manifest loads); `StampDeterminismGameTest` fingerprint will move: re-pin in its own commit.
Acceptance: rooms stamp without errors in the existing stamping tests; every room has a clear route from entrance to exit.

### D4. Act 1 Endless Mine guardians (id T41, L)

Needs T20, T21, T22, T23, T24 merged (they are, apart from carry-over fixes in section C). Design:
`docs/concepts-2026-10-11.md` (Endless Mine rows, "Act 1 Endless Mine"), `docs/decision-2026-10-11-endless-mine-per-act.md`.
Register Act 1's three guardian slots with `MineGuardians.register(1, slot, ...)` (read `MineGuardians` first: a guardian
gets a `Spawn` with `number`, `slot`, `lap`, `hardness`; it must tag every mob it spawns with `MineGuardians.tag`). Each is
a mini of an Act 1 boss, harder every guardian with no cap, repeating each lap:
- slot 1 (floor 3, 6th, ...): a small Silverfish Queen (reuse `InfestationFinale`/`CreepingBricks` pieces: scaled silverfish,
  a short burrow and brick adds phase).
- slot 2: a pack with a handler (reuse `KennelsFinale` ideas: pillager handler, wolves that scale and anger).
- slot 3: a coffin wave (reuse `OssuaryFinale` ideas: sealed coffins rising one at a time).
Hardness scales health, damage multipliers, counts and speed of the tells but NEVER shortens a tell below 2 seconds and
never exceeds a mob cap (knob). The `MineGuardians` placeholder runs until a real guardian is registered. Put each guardian
in its own class (`MineGuardianQueen`, `MineGuardianPack`, `MineGuardianCoffins`) and register in one place.
Tests: pure tests for how hardness maps to counts; a gametest per slot that the guardian spawns tagged and the pad stays
shut while any is alive (see `MineGuardianGameTest`). Acceptance: guardian floors 3, 6, 9, 12 behave and get harder;
the placeholder is used only for acts without registered guardians.

### D5. Act 2 Endless Mine (the Deep Mine) guardians (id T42, L)

Needs D2 (Copper Works), the Deepslate Hush (carry-over C) and the Frostworks cold (carry-over C). Same hook as D4 with
`MineGuardians.register(2, slot, ...)`. Slots: 1 the Deep Sentry (a bow skeleton in a silent, listening room: Heard meter,
reuse the Deepslate script's meter handling), 2 the Foreman (vent pulses, reuse `CopperFinale` pieces), 3 the Frost Warden
(cold and braziers, reuse `FrostFinale` pieces). The Deep Mine data (`endless_mine_deep`, tier tables) already exists. Tests
and acceptance as D4.

### D6. Spawner Dungeon capstone (id T43, L)

Needs all of the above Act 1 finale scripts. Design: `docs/boss-workshop-2026-10-11.md` decision 6b;
`docs/concepts-2026-10-11.md` (Spawner Dungeon). Dungeon id `spawner_dungeon` (a capstone, 3 floor run), final floor The Brood
Chamber (room `brood_chamber`, four caged spawners at `CapstoneSpecs.BROOD_SPAWNERS`). Capstone fights run in
`CapstoneFights`/`BroodWave`, not in `FinaleWave`, so `FinaleScript`s do not apply directly; write a small adapter that lets
the capstone drive the same script classes, or extract the reusable parts. Behaviour (owner decision): "spawner as phase".
Each of the four spawners, once broken, releases one earlier Act 1 mechanic that then stays on until the floor ends:
1. Ossuary coffins rise (reuse the `OssuaryFinale` logic),
2. Infestation bricks creep and burst (`CreepingBricks`),
3. the Mineshaft lights fail and cave spiders come (`MineshaftFinale`),
4. the Lush Caves potion drip falls (`PotionDrip`).
After the fourth spawner breaks, the brood mother arrives (a scaled spawner-born mob with a boss bar) with the Kennels
handler beat (a windup, then the pack scales and angers; reuse `KennelsFinale` rules) and a sped-up drip. The pad stays
shut until the brood mother and every brood mob are dead. Never trap a player; all effects are removed when the floor
ends; the existing spawner rules (soft breakable blocks, the douse rule when enough brood is killed) stay. Order of the
spawner to mechanic mapping is your choice but document it. Tests: pure tests for phase order; a gametest that breaking a
spawner starts exactly its mechanic and that the pad is shut until the end. This is the biggest fight of Act 1: keep
every tell at least 2 seconds and cap total alive mobs (knob).

## B. After all tasks (the integrator does this, not you)

Fold live check fragments into `LIVE_CHECKS.md`, regenerate `CODEMAP.md`, fix stale design docs, one full suite run.
Do not do these yourself; just make sure your fragments exist.

## C. Carry-over from the sub-agents

All of T20 (Mineshaft), T21 (Ossuary), T22 (Kennels), T23 (Lush Caves), T24 (Infestation), T31 (Deepslate Hush) and T33
(Frostworks) are merged on `feature/act-1-2-roadmap`. Their gametests passed in isolation; the full suite status is in
`03-KNOWN-ISSUES.md`. Remaining carry-over:

1. **T33 Frostworks, the room template.** `big_freeze.nbt` was generated from a run that crashed on memory right after
   saving, so it may be incomplete. Regenerate it (room-building skill, throwaway gametest) and check the five brazier
   plinths and two ice pillars are present and the door lanes and pad are clear. `FrostFinaleRulesTest` (pure) was never run
   by its author; run it. Also: the elite is still called "The Frost Warden"; the owner may want it renamed because of the
   one-Warden rule (the Drowned Vault boss rename was a decision; this one is open): ask, do not rename.
2. **T31 Deepslate, the room template.** `silent_deep.nbt` was regenerated but `dungeonIntegrationTest` has not been seen to
   pass with it. `DeepslateFinaleRulesTest` (pure) was written but never run by its author; run it. If a final floor draws no
   `silent_deep` room, the finale falls back to the plain data wave; confirm that path has a test.
3. **T21 Ossuary, T20 Mineshaft, T22 Kennels.** Each has a "Handoff" section in `docs/reports/rm-t2x.md`. The known
   failures were fixed (slot collisions, a torch inside a spare radius, a sleeper's invisible flag); re-run the three classes
   in the full suite and confirm. T22 has one open judgement: with a party of two or more the data wave holds several
   pillagers; only the first is the handler.
4. **T30 Cow Pits partial work** lives on `feature/rm-t30-cow-pits` (one WIP commit, seam extension only). Task D1 includes it.
5. **Economy flake.** `economy_game_test_clear_floor_redeems_the_keys_it_finds` failed once on an earlier run. If it fails
   again on a clean tree, investigate whether it depends on affix draws (many affixes were added), as one Blood test did.
