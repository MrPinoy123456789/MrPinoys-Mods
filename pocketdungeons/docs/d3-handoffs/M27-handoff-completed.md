# M27 - Extra features - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `PocketDungeonsCommands.java`: admin command registration. 27.1's
   experimental dungeon command registers here.
2. `DialogScreens.java`: wall terminal menu (M21). 27.2's visitor log
   adds a menu option here.
3. `ROOM_UX_PLAN.md`'s `## M27` section ONLY: authoritative scope for all
   three sub-features.

## Dependencies

Grep `RitualListener` in `src/main/java/`. If lever handler and door screen
exist, M19 landed: 27.1 can ship.

Grep `VisitService` in `src/main/java/`. If visit method exists, M20 landed:
27.2 can ship.

Grep `DialogScreens` in `src/main/java/`. If wall terminal menu exists, M21
landed: 27.2's menu option can ship.

27.3 (death checkpoint) is **deferred**. Current dungeon layout is short
enough that re-traversal is trivial. Ships when M25 (Pocket2) or extended
dungeons make re-traversal a real frustration.

## Goal

Three small features enhancing the existing loop without being load-bearing.
Ship 27.1 and 27.2; 27.3 is deferred.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: 27.1 Experimental dungeon (needs M19)

1. `/dungeon admin experiment <theme> [affixes] [lootOverride]`: operator
   command sets a fixed offer on a fourth door (or special state on one of
   the three).
2. Door screen shows a caution indicator (M19's `text_display` entity).
3. No per-player daily reward tracking. That comes once the feature
   graduates from testing.
4. Store the experimental offer in `PocketDungeonsConfig` or a runtime
   field on `MinecraftServer` (not persisted across restarts).

### Step 2: 27.2 Room visitor log (needs M20, M21)

1. `DungeonLog.Entry`: add `recentVisitors` ring buffer (max 10 entries).
   Each entry: visitor name (String) + timestamp (long, epoch millis).
2. `VisitService.visit` or `Instances.visit`: push entry on each visit.
3. Wall terminal menu: add "Recent visitors" option. Dialog lists last N
   visitors: name, time, whether still inside.
4. "Still inside" check: query `InstanceRecord` for active visit instance
   matching visitor UUID.

### Step 3: 27.3 Death checkpoint (DEFERRED)

1. Do not implement. Leave a `// M27.3: deferred until extended dungeons`
   comment in `Instances.onTick` near the death-handling logic.
2. Design note in `COMPLETED-MILESTONES.md`: deferred, depends on M25 or
   future extended dungeon work.

## Constraints

- 27.1: operator-only command. No daily reward tracking this milestone.
- 27.2: ring buffer capped at 10. No pagination. If 10 is too few after
  live testing, raise the cap.
- 27.2: visitor log is host-visible only. Visitors do not see who else
  visited.
- 27.3: do not ship. Current layout does not justify it.
- All three are independent. Ship 27.1 without 27.2 if M20/M21 have not
  landed.

## Verification

- `build_mod` default `build` after each step.
- Headless: commands register, `recentVisitors` codec round-trips, dialog
  compiles.
- Live: admin sets experimental offer, door shows caution indicator.
  Visitor log shows recent visitors in menu. Record in
  `LIVE_TEST_PASS.md`.
- Done when: 27.1 and 27.2 work end to end. 27.3 documented as deferred.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M27-handoff-completed.md` once landed.
