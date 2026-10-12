# D3: T25 Lush Caves rooms (the swamp floor)

## Branch

- Branch: `devin/d3-lush-caves`
- Base: `devin/d2-copper-works` @ `02c2fc9f` (stacked on D1 + D2 + carry-over)
- Status: implementation complete, tested; ready for the integrator.

## What was built

Five new rooms for the upper floors of `rootworks` (Lush Caves), matching
the brief's list: witch havens, fungal archers, clay shelves to climb,
mushrooms, a dead end pond. `PotionDrip` and `LushFinale` untouched.

| Room | Mask | Roles | Idea |
|---|---|---|---|
| `witch_haven` | W+E | encounter | Plank hut on the marsh, brewing cauldron, a resident witch spawned in the hut plus a witch-heavy trial spawner config (`witch_haven`, 3 witch : 2 spider, bogged in ominous). |
| `clay_shelves` | W+E | loot, corridor | Terraced clay banks in one-block steps along the north wall, vines down the step faces, chest on the top shelf. Movement, no combat. |
| `fungal_hollow` | W+E | encounter, loot, corridor | A red and a brown giant mushroom over mycelium and podzol, spore blossom under the red cap. |
| `still_pond` | W (dead end) | loot, corridor | Water over clay wall to wall, a low clay bar mid-pond carrying the chest, lily pads and dripleaf. |
| `hollow_log` | W+E | loot, corridor | A fallen giant log the path runs through; a bark nook hides the chest. |

`bogged_marsh` (existing generic combat room: mud floor, bogged trial
spawner) joins the Glow Berry Hollow `roomBias` rather than a new build,
covering the fungal-archer requirement.

## Files changed

### New

- `src/main/resources/data/pocketdungeons/dungeon_room/{witch_haven,
  clay_shelves,fungal_hollow,still_pond,hollow_log}.json` (manifests)
- `src/main/resources/data/pocketdungeons/structure/rooms/{same five}.nbt`
  (generated through a throwaway `GenRoomsTmpGameTest`, copied in, the
  generator class removed again)
- `src/main/resources/data/pocketdungeons/trial_spawner/witch_haven/
  {normal,ominous}.json` (witch-heavy spawner config)

### Edited

- `src/main/java/pocketdungeons/ResourceBiomeSpecs.java`: five specs in a
  new "Lush Caves swamp floor (T25)" section, added to `list()`, plus the
  `witch_haven` situation handler in `registerHandlers` (spawns the
  resident witch at the last spawn point, then
  `TrialContent.applyEncounter` with the `witch_haven` config prefix).
- `src/main/java/pocketdungeons/RoomSelector.java`: `witch_haven` added
  to `ENCOUNTER_ROOMS` (its handler places a trial spawner).
- `src/gametest/java/pocketdungeons/RoomLibraryGameTest.java`: the five
  names added to `NAMED`, so the connectivity check covers them.
- `src/main/resources/data/pocketdungeons/dungeon/rootworks.json`:
  `sapping_den` roomBias `infested_wall` -> `burrow_tunnel` (the bricks
  belong to the Infestation; the burrow tunnel is bound to it, so only
  the borrowed-theme floor can draw it). Biases added: Moss Gate gets
  `fungal_hollow` + `hollow_log` and a notes line, Glow Berry Hollow gets
  `witch_haven` + `bogged_marsh`, Clay Burrow gets `clay_shelves` +
  `still_pond`, Wolf Hollow gets `hollow_log`.
- `src/gametest/java/pocketdungeons/StampDeterminismGameTest.java`:
  fingerprint re-pinned in its own commit (`8849237b`).

## Verification

- `compileJava + compileGametestJava + compileTestJava`: clean.
- Generation run: `Saved room template to ...` for all five.
- `room_library*` gametests: pass; the connectivity check covers all
  five new rooms with no findings (the 13 logged findings are the known
  pre-existing ones: gated rooms, blaze_loft, lower-story rooms).
- `stamp_determinism*`: fingerprint moved `-1655107814` -> `-1975741233`
  as expected; re-pinned and rerun green.
- Full pure suite (`./gradlew test --continue`): BUILD SUCCESSFUL.
  Includes `RubbleRulesTest` (the new `ENCOUNTER_ROOMS` entry),
  `BoardGameTest`, `FloorNotesTest` and pack validation: 134 rooms,
  0 rejected.
- `situation*` gametest: pass. `dungeonIntegrationTest`: PASS.
- Dash check: clean.

## What only a live server can verify

- The witch haven reads as a witch's home in game (hut scale, cauldron,
  the resident witch inside) and the fight is fair at tier 1.
- The clay shelves climb comfortably and the top chest is worth it.
- The still pond wades/swims as intended and the bar is reachable.
- `burrow_tunnel` actually draws on the Sapping Den floor (eligibility
  says it can; only a real stamp shows it often enough).
- `bogged_marsh` draws often enough in Glow Berry Hollow.

## Merge notes

Hot files: `ResourceBiomeSpecs.java` (new section + list + handler, all
additive), `RoomSelector.java` (one string in a set), `rootworks.json`
(roomBias edits the D4-D6 agents are unlikely to touch), the two test
files (one list entry, one pin). Expect at most line-adjacent conflicts
in `rootworks.json` if a sibling edits node notes.
