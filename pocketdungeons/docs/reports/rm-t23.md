# RM-T23 report: Lush Caves potion drip and giant slime

Branch `feature/rm-t23-lush-drip-slime`, off `feature/act-1-2-roadmap` (T01, T11, T12, T13 merged in).
Design record: `docs/boss-workshop-2026-10-11.md` row 2 and decision 2b, `docs/concepts-2026-10-11.md`.

## What was built

- `PotionDripRules` (pure): which floors drip, the rate (base on the start floor, plus a ramp per later floor, times a
  boss multiplier), interval with a minimum and jitter, the brew pool (about 70 percent harmful: poison, slowness,
  weakness, nausea; good: regeneration, speed, jump boost), the harming gate (floor level knob, 0 never, and the
  player needs 14 health), and the fall speed that makes a drag-slowed potion take a chosen number of ticks.
- `PotionDrip`: the effect on `pocketdungeons:rootworks` floors, run from `Instances.tickRecord` beside
  `CapstoneFights.tick`. Per player schedule, jittered. A vanilla `ThrownSplashPotion` is released under the
  ceiling (3 to 8 blocks of clearance, else the drip is skipped and retried), has gravity off and a set speed so it
  falls about 40 ticks, with a brewing sound at the target and witch sparks and drips re-marked every watch tick.
  No drip in the entrance room, safe or staging room, a door preview, near a lodestone, before 10 seconds into a
  floor, while anyone is on the pad, or in any phase but ACTIVE. Poison is vanilla poison (never lethal) and the
  existing `EffectCaps` clamps it. Every potion is tagged and tracked; removed in `floorEnded` and `teardown`
  (hooked in `CapstoneFights` beside `FinaleWave`). State is in a `SessionState.clearedAtStop` map.
- `LushFinale` plus `LushFinaleRules`: `spawnWave` returns true and spawns one slime through `ctx.spawnElite`
  (named The Great Drip, boss bar), `Slime.setSize` to the knob size, health from a knob scaled by the existing
  elite party rule. If its body does not fit it moves around the party, then steps down two sizes. The first
  split is detected by looking for unclaimed slimes of half size around where the boss died; they are counted
  toward the wave, then topped up to double (extra slimes tagged and counted). Later splits are claimed only.
  Slimes beyond `lushSlimeMaxAlive` are discarded (vanilla children) or not created (top up).
  `onWaveSpawned` turns the boss drip rate on; `onWon` and `onFloorEnded` turn it off.
- Registered with one line in `FinaleScripts.registerAll()`. `FinaleWave` is untouched.

## The pad gap, and the guard

Vanilla splits a slime as its body is removed, about 20 ticks after it dies, but the finale counts a slime as dead
the moment its health is zero. Without care the last slime to die would open the pad before its children existed.
When a tracked slime that splits dies, the script adds an invisible, silent, invulnerable bat to the wave (the
guard) and discards it once the children have been found and counted (or 80 ticks pass). The pad line may read one
higher than the visible slimes for that moment.

## Knobs (all in `PocketDungeonsConfig`, defaults in `config/pocketdungeons.default.json`)

`potionDripEnabled`, `potionDripIntervalSeconds` 45, `potionDripJitterPercent` 30, `potionDripStartFloor` 3,
`potionDripRampPercent` 50, `potionDripBossPercent` 200, `potionDripHarmfulPercent` 70,
`potionDripHarmingMinLevel` 5 (0 never), `potionDripEffectSeconds` 8, `potionDripFallTicks` 40,
`potionDripMinSeconds` 8, `lushSlimeSize` 8, `lushSlimeHealth` 120, `lushSlimeMaxAlive` 40. The three config
fixtures were regenerated (`PD_CONFIG_REGEN`).

"Compass" for the harming gate is the floor level the mobs scale to (`keystoneLevel + levelBonus`), not a single
player's chart level, because a party can mix compasses. Floor number is `interval.floorIndex + 1`.

## Tests

- Pure: `PotionDripRulesTest` (staging by floor, interval, jitter, pool split, harming gate, fall speed),
  `LushFinaleRulesTest` (split top up to double for 2, 3 and 4 children, the cap, size ladder). Both wired in
  `build.gradle.kts`.
- Gametest `LushFinaleGameTest` (listed in `src/gametest/resources/fabric.mod.json`): boss replaces the wave and
  carries the bar and boss drip rate; a dying boss does not win the finale before its children exist; vanilla
  children are claimed and topped up to six; a later split is not doubled; the last death wins and the drip
  rate returns; a boss with no children ends the finale; no drip in the entrance room, a tagged slow splash potion
  elsewhere, removed by floor end.
- Counts: the final ship run could not complete (see Status). Last full result before the merge: pure tests and config tests green; gametests had 2 failures in `LushFinaleGameTest` (stray natural slimes in the test world inflated the child count; fixed by a freshness and radius filter in `LushFinale` and a stray clear in the test) and one in the drip test (sweep did not reach above the layout; fixed). Expected after the fix: gametest total rises by 3 over the base, pure test tasks by 2.

## Status

Not confirmed green. After merging `feature/act-1-2-roadmap` the post fix `tools/ship.sh --quick` run died twice on the host (JVM out of memory, then Loom cache lock, then no disk space left on the drive), before any test ran. The merge conflicts were resolved by keeping both sides and regenerating the config fixtures (`ConfigKnobsTest passed (153 knobs)`, `ConfigCharacterizationTest passed`). It needs one more `tools/ship.sh --quick` once the host has disk and memory.

Also touched outside the T23 files: `FinaleWave.step` now iterates a copy of the mob list, because an `onMobDied` hook that adds mobs (the guard, split children) threw a ConcurrentModificationException in the seam. Separate commit.

## Only a live server can confirm

- Does the falling potion read (sound, sparks, 2 second fall) and can a quick player step out of it.
- Are 70 percent harmful drips fair at compass 1, and is the floor 3 and 4 pace right.
- Does a real slime death produce the children the way the gametest imitates (the top up and the guard), and
  does the giant slime fit and move well in the Great Drip Cavern. Its melee damage was left at the vanilla
  value for its size; check it is not too harsh.
- Ceiling clearance in real rooms: rooms with low ceilings skip drips, so some rooms may never drip.
