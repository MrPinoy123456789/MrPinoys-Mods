# RM T34: Haunt director and The Counted (Ossuary)

Branch `feature/rm-t34-haunts`, off `feature/act-1-2-roadmap` (merged twice; conflicts in `build.gradle.kts`,
`fabric.mod.json` and `Restless.java` resolved by keeping both sides).

## What was built

- Data: `dungeon_haunt/the_counted.json` (label, dungeons, `min_compass` 4, closed `behaviour` set with only
  `STALK_WHEN_UNSEEN`, body `minecraft:skeleton`, tell particle `soul_fire_flame`, tell sound
  `block.bone_block.hit`, rule line, diary `entry_27`). Loader: `HauntDefinition`, `HauntMeta` (pure parse),
  `HauntManifest` (registry checks for mob, particle, sound; rejections reported), wired into `ContentSnapshot` and
  `ContentReload`. Dungeon ids are qualified, so `ossuary` matches `pocketdungeons:ossuary`.
- `HauntRules` (pure): floor to stage, look cone dot product, pressure, never-lethal strike damage, the stalker
  decision. `Haunts` (runtime): the director on the existing watch tick (`Instances.tickRecord`, beside
  `CapstoneFights.tick`) and a per-tick body step (`Haunts.register`, as `Restless` does).
- Ladder by floor (node layer): floor 1 sighting (stalks, never attacks, vanishes when a member is within 7 blocks
  or after 45 s; three vanishes and it gives up for the floor), floor 2 near miss (swipe sound, no damage), floor 3
  onward strike. The strike floor knob is at least 3, so a two floor dungeon never strikes, and the pure rule
  enforces it whatever the knobs say.
- Fairness: tell (soul flames at its feet and a bone knock) starts when it moves within 18 blocks and must run
  `hauntTellSeconds` (min 2) before any act; a strike is fixed damage with a plain damage source (a mob attack
  scales on Hard) capped to leave 1 health, plus slowness; after any act the body is removed and it stays away
  `hauntRetreatSeconds`. Look test: cone around the look vector and a block raycast, so walls and doorways count.
- Exclusions: entrance room, terminal (pad) room, a room with an armed Ordeal, outside any cell; no haunt on a
  capstone dungeon or while `FinaleWave.stirring` (new small method) is true; the body is banished then.
- Body: tagged `pocketdungeons.haunt` (`PocketDungeonsMod.HAUNT_TAG`), unscaled, never counted by clear gates
  (they count spawners), no drops or xp (`OmenWaveNoDropsMixin`), never rises (`Restless` never-rise tags), swept at
  `CapstoneFights.floorEnded` and `teardown`. A killed Counted stays gone for the floor (open question 3: no
  return, no pay).
- Teach: title line "Something walks here." added in `FloorStartTitle`; first tell writes "It moves when you look
  away." to chat and the action bar once per player per trip. Diary: `entry_27` "The Counted", number 27, band 31
  (past 30, so only the first finish delivers it, in `RunLifecycle` beside the dungeon page).
- Knobs (13, `PocketDungeonsConfig`, fixtures and default file regenerated): `hauntsEnabled`,
  `hauntPressurePerSecond` 1.0, `hauntWakePressure` 90, `hauntLookConeDegrees` 60 (half angle),
  `hauntNearMissFloor` 2, `hauntStrikeFloor` 3, `hauntStrikeDamage` 3.0, `hauntSlownessSeconds` 3,
  `hauntRetreatSeconds` 40, `hauntTellSeconds` 2, `hauntLingerSeconds` 45, `hauntSightingVanishes` 3,
  `hauntSightingApproachBlocks` 7.

## Decisions taken

- Pressure rises with time only; "falls when rooms are cleared" from the draft is not built (one knob is enough to
  tune first). Cap one haunt per floor; party size does not raise it yet.
- Staging counts the node layer, falling back to path length.

## Tests

- Pure `HauntRulesTest` (staging incl. two floor dungeon never striking over a knob grid, cone maths, pressure,
  strike never leaves under 1 health, decision table, parser rejections, shipped Counted and diary data).
- `HauntGameTest` (3, registered in `fabric.mod.json`): wakes on floor 3 out of sight, freezes in view, walks when
  unseen, gone at floor end; strike hurts and slows without killing (2 health to 1) and retreats; title line,
  stage ladder, excluded rooms. Rig uses `Haunts.registerForTesting`; the mock player reports creative, so the test
  clears its abilities.
- Counts: before 248 (task figure, branch before merging T01 to T13); after the merge and T34, ship --quick --no-dist is green with 259 required tests (T34 adds 3). Pure tasks: 112 green.

## Only a live server can confirm

- Whether the soul flames and bone knock read in a dim Ossuary room, and from how far.
- Look detection through a doorway or across a corner: the cone plus raycast uses the body centre at 60 percent
  height, so a half hidden body may count as unseen.
- Pathing: it relies on the vanilla navigation with the body goals left in place and target cleared each tick and
  attack damage set to 0; watch for a skeleton that strays or shoots (it has no bow).
- Whether 90 s wake, 40 s retreat and 3 damage feel right; whether chat plus action bar line is noticed.
- Diary delivery of Entry 27 on a first finish (two books in one finish).
