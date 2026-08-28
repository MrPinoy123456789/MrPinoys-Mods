# Known bugs

A running list of reported bugs, with status. Ordered oldest first.

## Open

### PD-1: Timer boss bar keeps ticking after dungeon completion (UI)

**Reported:** 2026-08-26
**Severity:** Low (cosmetic)
**Status:** Open

After completing a dungeon (reaching the exit pad, room moved to terminal
cell), the keystone timer boss bar keeps counting down on screen. The run
itself behaves correctly: completion is recorded, the reward room grace
window starts, and the run eventually retires or purges on its own. The
bar just never stops or hides on completion; it only goes away when the
instance is torn down.

Suspect: `RunTimer` is ticked by `Instances.onTick` for every live
`InstanceRecord` that has a non-null `timer`, with no check for
`record.completed` being non-empty. The bar is only closed in
`InstanceTeardown.purge` / `retireOrPurge` via `record.timer.close()`,
and in `generateBehindLobby` when a second run reuses the record. A
completed-but-not-yet-retired run keeps ticking the bar through its
entire reward-room grace window.

Likely fix: stop ticking (or freeze the display) once
`record.completed` is non-empty, or close the bar at completion and let
the grace window run without a visible clock.

### PD-2: Same affixes every run at the same keystone level

**Reported:** 2026-08-26
**Severity:** Medium (gameplay monotony)
**Status:** Open

A player feels like they get the same affixes every run. They do: the
seeded affix set is a pure function of `(owner UUID, keystone level)`,
so every run at the same level produces the same affixes for the same
player.

Root cause: `AffixMath.seededFor(owner, level)` shuffles the 6 seeded
affixes with `new Random(seed(owner, level))` and picks the first
`seededCount(level)` of them. `seededCount` is `level / 20`, so:

- Levels 1-19: 0 affixes (every run is plain)
- Levels 20-39: 1 affix, always the same one
- Levels 40-59: 2 affixes, always the same pair
- Levels 60-79: 3 affixes, always the same triple
- ...

The progression system moves levels by +1 to +3 per completion and -1
to -2 per timeout, so a player spends roughly 20 consecutive levels
seeing the exact same affix set. That is 10-20 runs of identical
modifiers, which reads as "stuck".

This is by design (`AffixMath` lines 26-30: "no weekly rotation;
seeding from the key keeps the name a pure function of (level,
affixSet)" so the watcher can reconcile stale remotes). The constraint
is real, but the monotony is also real.

Player follow-up: "always feral for some reason." A bias probe
(`AffixBiasProbe`, run and deleted) confirmed there is no statistical
bias toward FERAL: across 2 million `(uuid, level)` pairs in the
20-39 band, all 6 affixes land at pool position 0 at ~16.67% each, and
no UUID gets the same affix across all 20 levels in the band. FERAL is
correctly gated by `affixes.contains(Affix.FERAL)` in `RoomContent`.

Two factors amplify the perception:
1. The player is likely stuck at a level where FERAL is their
   `seededFor(uuid, level)` pick, and slow progression keeps them
   there for many consecutive runs (this is PD-2 itself).
2. FERAL is the most visually obvious affix: wolves walking around in
   corridors and loot rooms are impossible to miss. The other 5 are
   subtle (faster cooldowns, more mobs, no consumables, lava blocks,
   ominous spawner appearance), so even at higher levels where 2-3
   affixes are active, FERAL is the one the player remembers.

Possible fixes (none taken yet):
- Narrow the bands: `level / 10` instead of `level / 20`, so the set
  changes twice as often. Raises the affix count at high levels, which
  may need the pool to grow or the cap to be explicit.
- Re-roll on level change only: seed from `(owner, level)` as now, but
  also mix in a per-run salt stored on the `InstanceRecord` so two runs
  at the same level can differ. Breaks the "name is a pure function of
  (level, affixSet)" invariant the watcher relies on, so the remote
  would need to be reconciled against the run's actual affixes rather
  than re-derived.
- Rotate the pool: weight the shuffle so an affix that appeared on the
  player's last run is less likely to appear again. Still a pure
  function of `(owner, level)` if the "last run" is read from
  `DungeonLog.recentThemes`-style history, but adds a codec field.

### PD-3: Feral wolves spawn angry, are glitchy, and do not attack

**Reported:** 2026-08-26
**Severity:** Medium (affix feels broken)
**Status:** Open

Feral wolves spawn angry, behave glitchy (stuttering back and forth),
and do not meaningfully attack the player or other mobs. Tamed wolves
are also reluctant to attack.

Two root causes in `FeralContent.java`:

1. **Anger state is never cleared.** `FeralContent.apply` (line 128)
   spawns wolves via `RoomContent.spawnMobs`, which uses
   `EntitySpawnReason.TRIGGERED`. The callback (lines 128-139) sets the
   home position, applies mob scaling, and sets the wolf variant, but
   never calls `wolf.setAngry(false)` or equivalent. The class javadoc
   (lines 35-42) claims "wolves spawn neutral and are never angered"
   but that is an assumption about the default spawn state, not
   something the code enforces. In the current MC version a TRIGGERED
   wolf may spawn angry by default, contradicting the design intent.

2. **`setHomeTo(centre, HOME_RADIUS)` with radius 6 pins the wolf so
   tightly it cannot pursue anything.** A wolf that aggroes a target
   moves toward it, hits the 6-block home radius boundary, and turns
   back. This produces the "glitchy" back-and-forth stutter the player
   sees, and explains why wolves "do not really attack": they reach the
   edge of their leash and give up before closing to melee range. The
   same restriction persists after taming, so a tamed wolf still has
   `setHomeTo` set and will not follow the player out of the cell or
   chase distant targets.

The class comment at line 83-86 justifies the 6-block radius as a
room-geometry invariant: "a wolf that strolls into the next cell is a
mob the teardown pass does not expect to find there." That invariant is
real, but `setHomeTo` is the wrong tool for it: it clamps the wolf's
pathfinding target, which kills combat behavior. The teardown entity
sweep (`Instances.clearCellSync`) already uses a 1-block margin beyond
the cell, so a wolf slightly outside the cell is still caught.

Likely fix:
- Explicitly clear anger on spawn: `wolf.setAngry(false)` (or the
  26.x equivalent) in the spawn callback, so the design intent is
  enforced rather than assumed.
- Remove `setHomeTo` and rely on the cell's bedrock envelope and sealed
  doors to keep wolves in. A wolf that pathfinds through an open door
  into the next cell is still within the dungeon's overall bounds and
  is caught by teardown. If containment is still needed, use a larger
  radius (the full layout bounds) rather than 6 blocks from one cell.
- After taming, clear the home position so the wolf follows the player.

### PD-4: Stale FRAGILE references after M10 reframe

**Reported:** 2026-08-26 (found in M10-M14 handoff review)
**Severity:** Low (cosmetic, no behavioral impact)
**Status:** Open

M10 removed `Affix.FRAGILE` and `Affix.Kind.ELECTIVE`. The M10
handoff explicitly said "Grep `FRAGILE` across the tree before you
consider this done." Seven matches remain today.

Active naming issue (misleading):

- `RoomTemplateGenerator.java` line 357: `DOOR_FRAGILE` constant still
  names the exposed-copper door block. Door 3 is now the +3 greater
  door with `EnumSet.noneOf(Affix.class)` (Keystone.java line 132), so
  the name describes an affix that no longer exists. The door block
  itself is fine as a visual distinguisher; only the constant name is
  wrong.

Stale comments describing FRAGILE's depletion as if it still exists:

- `InstanceRecord.java` line 62: "`{@code FRAGILE}` doubles every
  depletion; the rest bend the run itself." FRAGILE is gone, so this
  sentence describes behavior that cannot happen.
- `KeystoneMath.java` line 27: "A fragile keystone doubles every
  figure, which is the entire cost of having taken the `+3`." Same
  issue: the fragile multiplier is always 1 now since no affix
  carries a depletion multiplier above 1.

Historical references (fine, leave alone):

- `Affix.java` lines 38, 80: explain that FRAGILE was removed. These
  are documentation of the removal, not stale descriptions.
- `DungeonLog.java` line 44: references legacy save format carrying
  `"fragile"`. Correct for migration context.
- `RoomTemplateGenerator.java` line 384: uses `DOOR_FRAGILE` in the
  door array. This is the usage of the misnamed constant above, not a
  separate issue.

Fix: rename `DOOR_FRAGILE` to something neutral (e.g. `DOOR_GREATER_3`
or `DOOR_EXPOSED_COPPER`), and rewrite the two stale comments in
`InstanceRecord` and `KeystoneMath` to describe the current depletion
model (no affix multiplies depletion above 1).
