# RM-T24: Infestation creeping bricks and The Queen

Branch `feature/rm-t24-infestation-bricks` (base `feature/act-1-2-roadmap`, merged twice, last after the final edit).

## Built
- `CreepingBricks` (+ pure `CreepingRules`): mossy, stone, infested stone brick, then a burst that removes the block with a plain setBlock (no drop) and spawns one silverfish. Sound and crack particles at each change; an infested brick shakes and chitters each second. A brick whose block is no longer the expected one is dropped silently (no silverfish). The break handler (`PlayerBlockBreakEvents.BEFORE`, registered after `RoomProtection`) takes over a permitted break (dungeon tool rule via `RoomProtection.verdict`) so nothing drops and an infested brick wakes no silverfish. Caps per room and per floor, a release budget (a ripe brick waits while the silverfish cap is full), `Field.close()` restores every standing brick.
- Rooms: `burrow_tunnel`, `gnawed_vein` (Chewed Corridors) and `slime_pit` (Silverfish Nests), Infestation theme only. Seeds are marked at stamp time from the plan seed (open floor, off door lanes, spread out); a member entering the cell starts them, staggered. `infested_wall` is untouched.
- `InfestationFinale` (+ pure `InfestationRules`): replaces the data wave with The Queen (silverfish, `Attributes.SCALE`, named, boss bar via `ctx.spawnElite`). Cycle: surface, 3 s rear-up tell, burrow (invisible, invulnerable, no AI, 4 blocks down, dust), 2 s floor-crack tell, adds phase (bricks in the arena cell, clear of members and the pad), ends when all bricks are gone or the timer runs out, 2 s ground-heave tell, emerge near the party. All tells are clamped to at least 40 ticks. Her silverfish go through `ctx.addMob`, so the pad stays shut; alive cap by knob. Her death discards the brood and restores bricks; `onFloorEnded` and `onWon` restore bricks and un-burrow a living boss. Silverfish with no target are re-pointed at the nearest member each watch tick (stops them merging into stone and vanishing).
- 19 knobs (`creeping*`, `infestation*`) via `new_knob.py`; fixtures and `config/pocketdungeons.default.json` regenerated.

## Tests
- Pure: `CreepingRulesTest`, `InfestationRulesTest` (clock, caps, lanes, seed picks, room list, phase schedule, tell floor, brick counts, silverfish cap). `--pure-fast`: 113 tasks green.
- Gametests: `CreepingBricksGameTest` (6), `InfestationFinaleGameTest` (3), both registered. Full suite: 270 required tests passed after, 261 before (270 minus the 9 added).

## Edits outside my files (all minimal)
`PocketDungeonsMod` (+1 register line), `LayoutStamper` (+2 lines arming rooms in `stampCell`), `InstanceTeardown.forgetCellState` (+1 line), `FinaleScripts.registerAll` (+1 line), `build.gradle.kts` (pure test wiring), `PocketDungeonsConfig` (knobs), config fixtures and default json, `fabric.mod.json`.

## Only a live server can confirm
- Whether the brick clock reads (sound and particles at 5/5/3 s) and whether bare-hand smashing is too slow (the tool rule still applies, pickaxe needed).
- How the burrow looks: dust puff, the boss 4 blocks below the floor, the emerge spot.
- Whether the adds phase is fair (6 bricks, cap 10 silverfish, 40 s max) and whether the 1 s watch tick makes tells feel coarse.
- Silverfish already merge into stone in vanilla; the retarget helps but real play may still lose one.
- Final-floor room geometry: bricks use the terminal cell's open floor at y+1; a room with little open floor plants fewer bricks (the phase then shortens itself).
