# RM-T11: Overclocked pays for speed, Loaded becomes trapped chests

Branch `feature/rm-t11-overclocked-loaded` (off `feature/act-1-2-roadmap`). Design: `docs/affix-workshop-2026-10-11.md`, rows 3 and 4.

## Part A: Overclocked

- A floor cleared within `overclockedParSeconds` (default 360) of its commit adds `overclockedBonus` (default 1) scrap. Both are config knobs in `PocketDungeonsConfig`, written to the default config and pinned by regenerated config fixtures.
- Where it pays: `RunLifecycle.payFloorMembers` already pays every member through `ScrapMath.floorPay(step, level, compass, bonus)`. The speed bonus is added to that `bonus` argument (`FloorPay.speedBonus`, pure rule in `OverclockedPay`). The dealt step and the act and final bonuses are untouched; a floor dealt no step still pays nothing. Elapsed time is overworld game time now minus `record.floor.startedAtTick`, which `Instances` stamps at the commit (the clock the floor history and journal already read).
- Below the member's compass the bonus rides inside the existing below-compass share, as every other bonus does.
- The door board's "what you get" line does not include the speed bonus (it is conditional); the floor title shows it. `FloorStartTitle` adds the line "Under 6:00: +1 scrap" under OVERCLOCKED.
- The cooldown factor 0.4 is unchanged. Blurb: "Overclocked: the waves come back fast. Clear the floor in 6 minutes for +1 scrap." The blurb is fixed text; if the knobs are retuned the blurb must follow.

## Part B: Loaded

- New class `TrappedCache` (a Fabric `UseBlockCallback`, no mixin). The cache is the chest still holding the Loaded loot table (`affixes/loaded_tools`); vanilla clears the table on the open that follows our PASS, so a second open finds nothing. No stored state, restart safe, and the open is never cancelled, so the loot is intact.
- Ambush size: `min(8, 2 + party)` (3 at party 1, +1 each, max 8), party from `record.members`.
- Telegraph: soul-escape sound, smoke bursts at each spot, red action bar "Trapped."; the mobs appear 20 ticks later via a server tick queue.
- Safety: mobs only stand on free floor (air above, sturdy floor below) in the chest's own cell, within 5 blocks of the chest, and never in the door lane band (cell-local x or z in 6..9, which covers every doorway and its approach). The chest sits in a corner, so each corner leaves 24 candidate spots (the pure test requires at least 8). If the room is cluttered the wave is smaller.
- Mobs: zombies and skeletons. The floor's theme family is baked into per-cell spawner configs and was not easy to reach, so this is the allowed fallback. They carry the omen wave tag (no drops) and the run's mob scaling.
- `extra_trial_bodies`: grepped first; only `hasAnyOperation` read it. Removed from `loaded.json`, `AffixEffects`, `AffixMeta`, the affix test fixtures and `INTEGRATION.md`. Third party JSON that still sets it is ignored (unknown keys are skipped).
- Blurb: "Loaded: a free tool chest, but opening it springs an ambush."

## Tests

- Pure: `OverclockedPayTest`, `TrappedCacheTest` (both wired in `build.gradle.kts`), `AffixMathTest` updated for the removed parameter, config fixtures regenerated.
- Gametest: `TrappedCacheGameTest` (registered in `fabric.mod.json`): opening the cache passes through, the loot is intact, exactly 3 ambush mobs appear after the delay, none on a lane, and a second open adds none.
- Counts: see the foot of this file.

## Needs a live server

See `docs/playtests/live-checks/rm-t11.md`. In short: the sound and smoke read as a warning, one second is enough to react, real dungeon rooms leave free spots near the corner chest, the title line fits, and a real clear time lands either side of 6 minutes as expected. Gametests have no dungeon dimension, so the record based paths (party size, mob scaling, cell lookup from the plan) are only exercised live.

## Merge note

Another branch (Restless and Blackout work) also edits `AffixEffects`, `AffixMeta` and `AffixIds`; expect small conflicts in the `AffixEffects` constructor and `build` signature where `extraTrialBodies` was removed here.
