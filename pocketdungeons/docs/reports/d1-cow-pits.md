# Report d1: Cow Pits Milking Parlour and the Specimen 0 fight (T30)

Branch: devin/d1-cow-pits   Base: feature/act-1-2-roadmap at ef261a27
Status: done

## Tests
- Gametests: 302 before, 308 after (5 new in CowPitsFinaleGameTest, 1 new in
  FinaleScriptGameTest; verified per class filtered, full suite pending the integrator's run).
- Pure test tasks: all green before and after; CowPitsTest extended, DungeonDefTest layer count
  updated 2 to 3.
- New test classes: `CowPitsFinaleGameTest` (fabric.mod.json entrypoint list). New method
  `anOwningScriptIgnoresClearedSpawners` in `FinaleScriptGameTest`.
- Failures seen and fixed: StampDeterminismGameTest fingerprint re-pinned to -1655107814 (new room
  templates and the 3 floor dungeon change the stamp, as expected). DungeonDefTest pinned cow_pits
  at 2 layers; now 3. Two gametest padRefusal assertions failed until padRefusal learned to take
  the level directly (the gametest server has no dungeon dimension).

## What changed
- Seam first (separate commits): `FinaleScript.shouldTrigger` and `ownsTrigger` (from the WIP
  cherry-pick d58e6ead plus its completion), `FinaleContext.adoptElite`, `FinaleWave` learns a
  script with no data wave behind it (`scriptedFinale`) and a `padRefusal(ServerLevel, record)`
  overload so callers holding the floor's level skip the dimension lookup.
- `CowPitsFinale` (new): owns the trigger (`CowPits.fedOn`), moo plus "Specimen 0 wakes." title,
  scales the pedestal cow to `cowSpecimenZeroScale` as the `cowSpecimenZeroHealth` elite, angers
  every tagged floor cow up to `FLOOR_CAP`, forgets the fed mark on floor end.
- `CowPits` (edited): floor numbers 5/21, three parlour rooms in `ROOM_COWS`/`ROOM_LIMITS`, named
  specimen spawns, `fillChurns` (barrels get milk buckets), the fed mark (`FED`, slot to layout
  seed, `SessionState.clearedAtStop`), bucket refusal on tagged cows, and the feed path in the
  existing use-entity callback.
- `CowParlourSpecs` (new): milking_stalls, specimen_byre, zero_bay. Registered in
  `RoomTemplateGenerator.specs()` and `RoomLibraryGameTest.CHECKED_SPEC_CLASSES`.
- `mixin/MobGoalsAccessor` (new): exposes goalSelector/targetSelector so a placid cow can turn
  hostile. Registered in pocketdungeons.mixins.json.
- `ResourceBiomeSpecs.registerHandlers`: routes the three new content ids.
- Config knobs added: `cowSpecimenZeroScale` (3.0, the grown size), `cowSpecimenZeroHealth` (60,
  before the party bonus), `cowParlourMilkBuckets` (2, per churn). Fixtures and
  config/pocketdungeons.default.json regenerated (172 knobs).
- Data: `dungeon/cow_pits.json` (3 nodes, milking_parlour on layer 2, borrowed mineshaft and
  root_sandbar rooms out of every roomBias), 3 new `dungeon_room` manifests, 3 generated
  `structure/rooms/*.nbt` (throwaway generator class used and removed).
- Tests edited: CowPitsTest (new numbers, 3 floor shape, no borrowed rooms), DungeonDefTest,
  StampDeterminismGameTest, RoomLibraryGameTest, FinaleScriptGameTest, fabric.mod.json.

## Decisions I made
- Herd anger needed hostile goals on a plain Cow; a MobGoalsAccessor mixin is the honest seam
  (goalSelector is protected in 26.2). Specimen 0 wakes through `adoptElite`, not a fresh spawn,
  so the pedestal cow itself is the boss.
- The fed mark keys slot to layout seed so a regenerated floor cannot inherit it.
- Buckets are refused on every tagged cow; the churns are the only milk, keeping the rule finite.
- zero_bay is `graphRole: final` so Specimen 0 only ever stands on the last floor; the ward's
  pedestal cow on floor 1 shares the name and look (foreshadowing) but cannot be fed there since
  the mark checks `onFinal`.
- FLOOR_MIN 5, FLOOR_CAP 21: the parlour pair reaches the new minimum, and the cap bounds the
  whole room set drawn at limits.
- Rooms the brief calls "2 or 3" got 3: the parlour needed both a stalls room and a holding pen.
- `borrowableBy: ["cow_pits"]` on the four foreign rooms was left alone; only the roomBias was
  required to change.

## What only a live server can confirm
- The "Specimen 0 wakes." title and the moo land at the right volume; the 2 second tell reads.
- A scaled cow is legible as a boss (size, name bar, reach).
- The herd actually reaches the party in a cluttered barn (pathing out of the bay's bars).
- The sign reads at eye height; players find it before they find the cow.
- zero_bay appears often enough on the Big Barn that the optional beat is seen.
- Feeding a cow by accident vs deliberately: the consume animation and result.

## Conflicts expected when merging
- `FinaleScript.java`, `FinaleContext.java`, `FinaleWave.java`, `FinaleScripts.java`: the seam is
  shared; keep both sides' hook additions and register() lines.
- `PocketDungeonsConfig.java`, config fixtures, pocketdungeons.default.json: knob order only,
  regenerate fixtures if other branches add knobs.
- `RoomTemplateGenerator.specs()`, `RoomLibraryGameTest`, `StampDeterminismGameTest`,
  `fabric.mod.json`: append lines from both sides; the stamp fingerprint must be re-pinned after
  every room-data merge.
- `ResourceBiomeSpecs.registerHandlers`: switch branch lines from both sides.
- `mixins.json`: keep every mixin entry.

## Fragments
- docs/playtests/live-checks/d1-cow-pits.md written: yes
- tools/check_dashes.sh clean: yes
