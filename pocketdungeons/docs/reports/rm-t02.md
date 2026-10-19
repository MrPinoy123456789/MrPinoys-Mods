# rm-t02: PD-219, block-attached entity at an invalid position

Branch: feature/rm-t02-invalid-entity-pos (off feature/backlog-2026-10-19).

## Trace
- The same BlockPos{x=9, y=65, z=1000710} was logged on 2026-10-03, 2026-10-04 and 2026-10-19. A fixed position across
  sessions and before the Act 0 rooms existed rules out a per-run or per-slot offset, and rules out Act 0.
- z=1000000 is `RoomTemplateGenerator.SCRATCH_Z`, the dev-time template capture region; rooms sit every 32 blocks, so
  z=1000710 is the room row at 704 plus local z=6. The only room whose decor spawns a hanging entity is
  `rotation_lock` (`MechanismSpecs.rotationLock`, item frame at local 9,1,6).
- `buildAndQueue` built the room, captured it with `fillFromWorld` (which copies the frame into the template), then
  `clear` set only blocks to air. The original frame stayed, unsupported, in the scratch chunk and logged the vanilla
  error whenever that chunk loaded.
- Ruled out: live stamping (`RoomStore` and the template placement put entities relative to the cell); no other code
  spawns ItemFrame, Painting or LeashFenceKnotEntity in `src/main`.

## Fix
- `RoomTemplateGenerator.clear` (now package-private) discards non-player entities in the box before clearing blocks;
  `buildAndQueue` discards stale ones in the cell before building.

## Test
- New `ScratchEntityGameTest` (registered in the gametest fabric.mod.json): stamps `rotation_lock` with
  `buildForCheck`, asserts the frame exists and every block-attached entity is inside the cell, then asserts `clear`
  leaves none. Verified red with the discard disabled.

## Files
`src/main/java/pocketdungeons/RoomTemplateGenerator.java`, `src/gametest/java/pocketdungeons/ScratchEntityGameTest.java`,
`src/gametest/resources/fabric.mod.json`, `docs/reference/BUGS.md` (PD-219 status).

## Live only
- The vanilla message no longer appears. A frame already saved in an old test world persists until
  `/dungeon admin generate rotation_lock` is run once (the pre-build discard removes it) or that chunk is wiped.
- The world-border explanation for why vanilla calls the position invalid is inferred, not confirmed.
