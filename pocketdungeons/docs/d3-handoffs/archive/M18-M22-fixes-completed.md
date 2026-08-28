# M18-M22 review fixes - Handoff

> Paste this whole file into a fresh chat to start work on these fixes.

## Read before writing anything

1. `pocketdungeons/CONVENTIONS.md`: mod-specific rules.
2. `docs/DISCOVERIES.md`: verified 26.2 API findings.

## Goal

Fix five findings from the M18-M22 code review. Two warnings (bugs), two
notes (cleanup + perf), one note (pre-existing hardening).

## Implementation plan

One commit per fix. Run `build_mod` after each.

### Fix 1: Stale-click guard in `visitRoom`

`DialogRouter.java:212-233` routes `visitRoom` to `VisitService.visit`
without re-checking `publicListed` or owner-online state. A directory left
open before a room went private still visits it.

1. In `DialogRouter.visitRoom` (or in `VisitService.visit`, whichever is
   the cleaner gate), before stamping a visit instance:
   - Re-read `DungeonLog.get(targetUuid).publicListed()`. If false, show
     "That room is no longer public." and return.
   - Check the owner is online: `server.getPlayer(targetUuid) != null`.
     If offline, show "The room owner is offline." and return.
2. Do not change `VisitService.visit`'s signature. Add the guard at the
   call site in `DialogRouter`, or as the first lines of `visit` if that
   is cleaner.
3. Update the `visitRoom` javadoc to match the guarantee actually
   provided.

### Fix 2: Free re-entry regression through lodestone menu

`DialogRouter.java:150-164` gates `startDungeon` on
`Keystone.isKeystone(player.getMainHandItem())` before reaching
`RunLifecycle.enterWithKeystone`. An owner who owns a reenterable instance
and steps out without the keystone in their main hand gets "Hold a keystone"
instead of free re-entry.

1. In `DialogRouter.startDungeon`, remove the main-hand keystone gate.
2. Route straight into `RunLifecycle.enterWithKeystone(player)`, which
   already handles free re-entry (`reenterOwnedInstance`) and reads the
   keystone from inventory, not the hand.
3. Verify `/dungeon` and the old lodestone ritual path still behave the
   same (they already call `enterWithKeystone` directly).

### Fix 3: `watchSpawnerClears` per-tick allocation

`Instances.java:1071-1108` rebuilds a `HashMap<PlanCell, List<BlockPos>>`
and re-scans every trial spawner on every watch tick for every active run.

1. Cache the per-cell spawner grouping once, either in
   `generateBehindLobby` or as a field on the run's instance record.
2. Short-circuit cells already in `clearedCells` before grouping.
3. Do not change the detection logic or the `COOLDOWN` predicate. Only
   change where the grouping is computed.

### Fix 4: Double hyphen on edited line

`RitualListener.java:123`: `// the keystone branch below -- anything that
is not tagged tiered gear`.

1. Replace `--` with `:`: `// the keystone branch below: anything that is
   not tagged tiered gear`.

### Fix 5: Fluid placement bypasses `RoomProtection`

`RoomProtection.java:42-66`: the shell/furniture protection check is inside
the `instanceof BlockItem` branch. Fluid placement (water/lava buckets)
bypasses it.

1. Move the mask check outside the `BlockItem` branch, or add a separate
   guard for `BucketItem` and other placement sources.
2. The check should cover: block placement, fluid placement, and any other
   `ItemStack`-driven placement that could modify the room shell or
   furniture.
3. Do not change the protection logic for existing `BlockItem` placements.
   Extend it to cover the gap.

## Constraints

- Do not change `VisitService.visit`'s method signature.
- Do not change the spawner-clear detection logic, only the grouping cache.
- Do not change `BlockItem` protection behavior, only extend to fluids.
- Each fix is one commit. Message explains why.

## Verification

Run via `build_mod`:

1. Task `build`: full compile + all tests green.
2. Task `roomShellTest`: M18 shell tests still pass.
3. Task `roomFurnitureTest`: M18 furniture tests still pass.
4. Task `lobbyBrowserTest`: M20 lobby tests still pass (fix 1 touches
   visit routing).
5. Task `lodestoneMenuTest`: M21 menu tests still pass (fix 2 touches
   `startDungeon` dispatch).

## Completion

1. Append a summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append to `docs/reference/LIVE_TEST_PASS.md`: note that fixes 1 and 2
   need live verification (stale-click behavior, free re-entry without
   keystone in hand).
3. Rename this file to `M18-M22-fixes-completed.md` when done.
