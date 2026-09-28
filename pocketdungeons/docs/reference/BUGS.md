# Known bugs

A running list of reported bugs, with status. Ordered oldest first.
Each entry includes the root cause, exact file and line references, and
a step by step fix plan detailed enough for another agent to implement
without re investigating.

## Fixed

PD-1, PD-3, PD-4, PD-5, PD-6, PD-7, and PD-8 are fixed (2026-08-27);
`compileJava` and the full test suite pass. PD-2 stays open below as a
content update, deferred by request.

PD-9 through PD-48 (2026-08-31) are from a six-pass static audit of the
whole mod. M36 through M39 in `ROADMAP.md` group them by severity for
implementation order.

All 48 bugs from the 2026-08-31 audit are closed: M36 (PD-9 through
PD-14, PD-48), M37 (PD-15 through PD-22), M38 (PD-23 through PD-30,
PD-33 through PD-35), M39 (PD-36 through PD-47), and M42 (PD-19, PD-31,
PD-32, the three that were content or design decisions rather than pure
code fixes) are fixed; `compileJava` and the full test suite pass after
each.

## Open

### PD-1: Timer boss bar keeps ticking after dungeon completion (UI)

**Reported:** 2026-08-26
**Severity:** Low (cosmetic)
**Status:** Fixed (2026-08-27)

After completing a dungeon (reaching the exit pad, room moved to terminal
cell), the keystone timer boss bar keeps counting down on screen. The run
itself behaves correctly: completion is recorded, the reward room grace
window starts, and the run eventually retires or purges on its own. The
bar just never stops or hides on completion; it only goes away when the
instance is torn down.

#### Root cause

`Instances.onTick` ticks the timer for every live record that has a
non null `timer`, with no check for `record.completed` being non empty.
The bar is only closed in `InstanceTeardown.purge` / `retireOrPurge`
via `record.timer.close()`, and in `generateBehindLobby` when a second
run reuses the record. A completed but not yet retired run keeps
ticking the bar through its entire reward room grace window.

#### Exact code references

**The tick call with no completion guard:**
`Instances.java` line 893:
```java
if (record.timer != null) {
    record.timer.tick(interval);
}
```
This runs unconditionally for every non lingering record with a timer,
before the completion checks below it (lines 897, 901, 904).

**`RunTimer.tick` advances the clock and refreshes the bar:**
`RunTimer.java` lines 82-88:
```java
void tick(int ticks) {
    elapsedTicks += Math.max(0, ticks);
    if (!overTime && secondsRemaining() <= 0) {
        overTime = true;
    }
    refresh();
}
```

**`RunTimer.refresh` updates the boss bar title and progress:**
`RunTimer.java` lines 90-107:
```java
private void refresh() {
    int remaining = secondsRemaining();
    String title = "Keystone [" + keystoneLevel + "] - "
            + (overTime ? "OVER TIME" : KeystoneMath.formatClock(remaining))
            + " - " + roomsSeen + "/" + roomCount + " rooms";
    // ... sets bar name, progress, color ...
}
```

**`RunTimer.close` is the only way to hide the bar:**
`RunTimer.java` lines 50-53:
```java
void close() {
    bar.removeAllPlayers();
    bar.setVisible(false);
}
```
There is no `freeze()`, `stop()`, or `pause()` method.

**Completion sets `record.completed` but does not touch the timer:**
`RunLifecycle.java` lines 708-713:
```java
boolean firstCompletion = record.completed.isEmpty();
record.completed.add(player.getUUID());

if (firstCompletion) {
    completeDungeon(server, record);
}
```

**`record.timer.close()` is only called in teardown:**
`InstanceTeardown.java` lines 74-76 (retireOrPurge) and lines 109-111
(purge).

**`record.completed` field:**
`InstanceRecord.java` line 121:
```java
final Set<UUID> completed = new HashSet<>();
```

#### Fix plan

The cleanest fix is to stop ticking the timer once the run is
completed, and freeze the bar at its last reading so the player sees a
stopped clock rather than a live one counting into overtime.

**Step 1: Add a `completed` flag to `RunTimer`.**

In `RunTimer.java`, add a private boolean field:
```java
private boolean completed;
```

Add a method:
```java
void markCompleted() {
    completed = true;
}
```

**Step 2: Guard `tick` against advancing once completed.**

In `RunTimer.tick` (line 82), add an early return:
```java
void tick(int ticks) {
    if (completed) {
        return;
    }
    elapsedTicks += Math.max(0, ticks);
    if (!overTime && secondsRemaining() <= 0) {
        overTime = true;
    }
    refresh();
}
```

**Step 3: Update the bar title to show completion.**

In `RunTimer.refresh` (line 90), change the title to indicate the run
is done when `completed` is true:
```java
private void refresh() {
    if (completed) {
        bar.setName(Component.literal(
                "Keystone [" + keystoneLevel + "] - COMPLETE"
                + " - " + roomsSeen + "/" + roomCount + " rooms")
                .withStyle(ChatFormatting.GREEN));
        bar.setProgress(1.0f);
        bar.setColor(BossEvent.BossBarColor.GREEN);
        return;
    }
    // ... existing refresh logic ...
}
```

Call `refresh()` at the end of `markCompleted()` so the bar updates
immediately:
```java
void markCompleted() {
    completed = true;
    refresh();
}
```

**Step 4: Call `markCompleted` at first completion.**

In `RunLifecycle.completeRun` (line 708), after
`firstCompletion` is confirmed and before `completeDungeon`:
```java
boolean firstCompletion = record.completed.isEmpty();
record.completed.add(player.getUUID());

if (firstCompletion) {
    if (record.timer != null) {
        record.timer.markCompleted();
    }
    completeDungeon(server, record);
}
```

**Step 5: No changes needed in teardown.**

`record.timer.close()` in `InstanceTeardown.purge` /
`retireOrPurge` already removes the bar from all players. The
`completed` flag just stops the clock from advancing between
completion and teardown.

**Step 6: No changes needed in `generateBehindLobby`.**

`generateBehindLobby` already closes and nulls the old timer (lines
639-642) before creating a new one (line 650), so a second run behind
the same lobby gets a fresh, uncompleted timer.

#### Verification

After the fix:
1. Complete a keystone run by reaching the exit pad.
2. The boss bar should freeze at "COMPLETE" with a green color.
3. The bar should remain visible but not advancing during the reward
   room grace window.
4. When the grace window expires and the instance retires or purges,
   the bar should disappear.
5. Starting a second run behind the same lobby should produce a fresh,
   ticking bar.

---

### PD-2: Same affixes every run at the same keystone level

**Reported:** 2026-08-26
**Severity:** Medium (gameplay monotony)
**Status:** Fixed

A player feels like they get the same affixes every run. They do: the
seeded affix set is a pure function of `(owner UUID, keystone level)`,
so every run at the same level produces the same affixes for the same
player.

#### Root cause

`AffixMath.seededFor(owner, level)` shuffles the 6 seeded affixes with
`new Random(seed(owner, level))` and picks the first
`seededCount(level)` of them. `seededCount` is
`(level + LEVELS_PER_AFFIX - FIRST_AFFIX_LEVEL) / LEVELS_PER_AFFIX`,
so the affix set only changes when the level crosses a band boundary
(every 20 levels).

The progression system moves levels by +1 to +3 per completion and -1
to -2 per timeout, so a player spends roughly 20 consecutive levels
seeing the exact same affix set. That is 10 to 20 runs of identical
modifiers, which reads as "stuck".

This is by design: `AffixMath` lines 26-30 state "no weekly rotation;
seeding from the key keeps the name a pure function of (level,
affixSet)" so the watcher can reconcile stale remotes. The constraint
is real, but the monotony is also real.

Player follow-up: "always feral for some reason." A bias probe
(`AffixBiasProbe`) confirmed there is no statistical bias toward
FERAL: across 2 million `(uuid, level)` pairs in the 20 to 39 band,
all 6 affixes land at pool position 0 at roughly 16.67% each, and no
UUID gets the same affix across all 20 levels in the band. FERAL is
correctly gated by `affixes.contains(Affix.FERAL)` in `RoomContent`.

Two factors amplify the perception:
1. The player is likely stuck at a level where FERAL is their
   `seededFor(uuid, level)` pick, and slow progression keeps them
   there for many consecutive runs.
2. FERAL is the most visually obvious affix: wolves walking around in
   corridors and loot rooms are impossible to miss. The other 5 are
   subtle (faster cooldowns, more mobs, no consumables, lava blocks,
   ominous spawner appearance), so even at higher levels where 2 or 3
   affixes are active, FERAL is the one the player remembers.

#### Exact code references

**`seededCount(level)`:**
`AffixMath.java` lines 118-129:
```java
static int seededCount(int level) {
    return (Math.max(0, level) + (LEVELS_PER_AFFIX - FIRST_AFFIX_LEVEL)) / LEVELS_PER_AFFIX;
}
```

**`seededFor(owner, level)`:**
`AffixMath.java` lines 131-155:
```java
static EnumSet<Affix> seededFor(UUID owner, int level) {
    EnumSet<Affix> picked = EnumSet.noneOf(Affix.class);
    int count = seededCount(level);
    if (count <= 0) {
        return picked;
    }
    List<Affix> pool = new ArrayList<>();
    for (Affix affix : Affix.values()) {
        if (affix.kind == Affix.Kind.SEEDED) {
            pool.add(affix);
        }
    }
    Collections.shuffle(pool, new Random(seed(owner, level)));
    for (int i = 0; i < Math.min(count, pool.size()); i++) {
        picked.add(pool.get(i));
    }
    return picked;
}
```

**`seed(owner, level)`:**
`AffixMath.java` lines 191-199:
```java
static long seed(UUID owner, int level) {
    long mixed = owner == null ? 0L
            : owner.getMostSignificantBits() * 31L + owner.getLeastSignificantBits();
    mixed = mixed * 0x9E3779B97F4A7C15L + level;
    mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
    return mixed ^ (mixed >>> 31);
}
```

**The watcher invariant:**
`AffixMath.java` lines 26-30:
```java
// There is deliberately no weekly rotation
// ... seeding from the key keeps the name a pure function of
// (level, affixSet)
```

**The watcher reconciliation path:**
`Instances.java` lines 837-867: `reconcileKeystones` calls
`Keystone.reconcile` on every watcher tick.
`Keystone.java` lines 237-279: `reconcile` re derives affixes from
`AffixMath.effective(player.getUUID(), level, ...)` and rewrites the
keystone item if they differ.

**Where affixes are stored on the record:**
`InstanceRecord.java` line 66: `Set<Affix> affixes;`
Set at construction (line 197), at lobby entry (`Instances.java` line
395, empty set), and at door choice (`Instances.java` line 598).

**Where affixes are derived for entry:**
`RunLifecycle.java` lines 88-90:
```java
EnumSet<Affix> affixes = AffixMath.effective(player.getUUID(), level,
        AffixMath.elective(Keystone.affixOf(keystone)));
```

**Available persisted per run data in `DungeonLog.Entry`:**
`DungeonLog.java` lines 31-44: `runsCompleted`, `completedThemes`,
`currentTheme`, `depth`, `extractedPowers`, `keystoneLevel`. All
watcher stable (they are stored, not re derived).

#### Fix plan

The watcher invariant requires that `seededFor(owner, level)` returns
the same result for the same `(owner, level)` on every call, or the
keystone item name will churn on every reconciliation tick. The fix
must therefore use a watcher stable input that changes between runs at
the same level.

The simplest watcher stable input that changes between runs is
`runsCompleted` from `DungeonLog.Entry`. A player who finishes a run
increments `runsCompleted`, so two consecutive runs at the same level
have different `runsCompleted` values.

**Step 1: Add `runsCompleted` to the seed.**

In `AffixMath.seed`, add an optional `runNonce` parameter:
```java
static long seed(UUID owner, int level, int runNonce) {
    long mixed = owner == null ? 0L
            : owner.getMostSignificantBits() * 31L + owner.getLeastSignificantBits();
    mixed = mixed * 0x9E3779B97F4A7C15L + level;
    mixed = mixed * 31L + runNonce;
    mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
    return mixed ^ (mixed >>> 31);
}
```

Keep the existing two argument overload as a delegate that passes
`runNonce = 0` for backward compatibility:
```java
static long seed(UUID owner, int level) {
    return seed(owner, level, 0);
}
```

**Step 2: Thread `runsCompleted` through `seededFor`.**

Add an overload:
```java
static EnumSet<Affix> seededFor(UUID owner, int level, int runNonce) {
    // ... same body as existing seededFor, but call seed(owner, level, runNonce) ...
}
```

Keep the existing two argument overload delegating with `runNonce = 0`.

**Step 3: Thread `runsCompleted` through `effective`.**

`AffixMath.effective` currently calls `seededFor(owner, level)`. Add
an overload that accepts `runNonce` and passes it through. Keep the
existing overload delegating with `runNonce = 0`.

**Step 4: Pass `runsCompleted` at entry.**

In `RunLifecycle.enterWithKeystone` (line 88), read the player's
`runsCompleted` from `DungeonLog` and pass it through:
```java
DungeonLog log = DungeonLog.forServer(player.level().getServer());
DungeonLog.Entry entry = log.get(player.getUUID());
int runNonce = entry.runsCompleted();
EnumSet<Affix> affixes = AffixMath.effective(player.getUUID(), level,
        AffixMath.elective(Keystone.affixOf(keystone)), runNonce);
```

Do the same in `Instances.generateBehindLobby` (line 566):
```java
DungeonLog log = DungeonLog.forServer(server);
DungeonLog.Entry entry = log.get(record.owner);
int runNonce = entry.runsCompleted();
EnumSet<Affix> affixes = AffixMath.effective(record.owner, offer.level(),
        offer.affixes(), runNonce);
```

**Step 5: Update the watcher to use the same nonce.**

In `Keystone.reconcile` (`Keystone.java` lines 237-279), the watcher
re derives affixes from `AffixMath.effective`. It must pass the same
`runsCompleted` value. Since the watcher reads from `DungeonLog`
already, pass `entry.runsCompleted()` as the nonce. This keeps the
watcher stable: for a given saved state, `runsCompleted` is fixed, so
the re derived affixes match what the run used.

**Step 6: Update `AffixMath.name` callers if needed.**

`AffixMath.name` (line 271) takes `(level, affixes)` and is a pure
function of its arguments, so it does not need the nonce. The affixes
set it receives already reflects the nonce via `seededFor`.

**Step 7: Update tests.**

`AffixMathTest` and `AffixBiasProbe` call `seededFor` with two
arguments. They will continue to work via the delegating overload
(with `runNonce = 0`), but new tests should cover the three argument
form to verify that different `runNonce` values produce different
affix sets at the same level.

#### Alternative (simpler, less invasive)

If the watcher threading is too complex, a simpler alternative is to
narrow the bands: change `LEVELS_PER_AFFIX` from 20 to 10, so the
affix set changes twice as often. This does not require any new
parameters or watcher changes. The tradeoff is that affix counts rise
faster at high levels (level 60 would have 6 affixes instead of 3),
which may need the pool to grow or a cap to be added in `seededFor`.

#### Verification

After the fix (nonce approach):
1. Play two consecutive runs at the same keystone level.
2. The affix sets should differ between the two runs.
3. The keystone item name should not churn on watcher ticks (it
   should match the affixes of the most recent run).
4. Logging out and back in should not change the keystone's displayed
   affixes.

---

### PD-3: Feral wolves spawn angry, are glitchy, and do not attack

**Reported:** 2026-08-26
**Severity:** Medium (affix feels broken)
**Status:** Fixed (2026-08-27), parts 1 and 2. Part 3 (`setHomeTo` radius) fixed 2026-09-03: player reported wolves still moving very slowly and not working properly, matching the stutter this section already diagnosed.

Feral wolves spawn angry, behave glitchy (stuttering back and forth),
and do not meaningfully attack the player or other mobs. Tamed wolves
are also reluctant to attack.

#### Root cause

Two root causes in `FeralContent.java`:

1. **Anger state is never cleared.** `FeralContent.apply` (line 128)
   spawns wolves via `RoomContent.spawnMobs`, which uses
   `EntitySpawnReason.TRIGGERED`. The callback (lines 128-139) sets
   the home position, applies mob scaling, and sets the wolf variant,
   but never calls `wolf.stopBeingAngry()` or equivalent. The class
   javadoc (lines 35-42) claims "wolves spawn neutral and are never
   angered" but that is an assumption about the default spawn state,
   not something the code enforces. In the current MC version a
   TRIGGERED wolf may spawn angry by default, contradicting the design
   intent.

2. **`setHomeTo(centre, HOME_RADIUS)` with radius 6 pins the wolf so
   tightly it cannot pursue anything.** A wolf that aggroes a target
   moves toward it, hits the 6 block home radius boundary, and turns
   back. This produces the "glitchy" back and forth stutter the player
   sees, and explains why wolves "do not really attack": they reach
   the edge of their leash and give up before closing to melee range.
   The same restriction persists after taming, so a tamed wolf still
   has `setHomeTo` set and will not follow the player out of the cell
   or chase distant targets.

The class comment at lines 83-86 justifies the 6 block radius as a
room geometry invariant: "a wolf that strolls into the next cell is a
mob the teardown pass does not expect to find there." That invariant
is real, but `setHomeTo` is the wrong tool for it: it clamps the
wolf's pathfinding target, which kills combat behavior. The teardown
entity sweep (`Instances.clearCellSync`) already uses a 1 block margin
beyond the cell, so a wolf slightly outside the cell is still caught.

#### Exact code references

**`FeralContent.apply` (the spawn callback):**
`FeralContent.java` lines 114-140:
```java
static void apply(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns,
                  int lootTier, int keystoneLevel, long seed) {
    int count = PocketDungeonsConfig.feralWolvesPerCell();
    if (count <= 0) {
        return;
    }
    BlockPos centre = cellOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
    List<BlockPos> anchors = spawns.isEmpty() ? List.of(centre) : spawns;

    List<ResourceKey<WolfVariant>> coats = COATS_BY_TIER.get(
            Math.clamp(lootTier, 1, COATS_BY_TIER.size()) - 1);
    Registry<WolfVariant> variants = level.registryAccess().lookupOrThrow(Registries.WOLF_VARIANT);
    RandomSource coatRandom = RandomSource.create(seed ^ cellOrigin.asLong() ^ COAT_SALT);

    RoomContent.spawnMobs(level, cellOrigin, EntityTypes.WOLF, count, anchors, seed, entity -> {
        if (!(entity instanceof Wolf wolf)) {
            return;
        }
        // Pinned, never angered. See the class note: an angry wolf refuses the
        // bone outright, which would delete the affix's kiss.
        wolf.setHomeTo(centre, HOME_RADIUS);
        Instances.applyMobScale(wolf, keystoneLevel);
        Optional<Holder.Reference<WolfVariant>> coat =
                variants.get(coats.get(coatRandom.nextInt(coats.size())));
        coat.ifPresent(holder -> wolf.setComponent(DataComponents.WOLF_VARIANT, holder));
    });
}
```

**`HOME_RADIUS` constant:**
`FeralContent.java` line 87: `private static final int HOME_RADIUS = 6;`

**`RoomContent.spawnMobs` uses `EntitySpawnReason.TRIGGERED`:**
`RoomContent.java` line 152:
```java
Entity entity = type.spawn(level, pos, EntitySpawnReason.TRIGGERED);
```

**`Wolf` implements `NeutralMob` (anger API available):**
Verified from 26.2 bytecode: `Wolf` implements `NeutralMob`, which
provides `stopBeingAngry()`, `isAngry()`, `setPersistentAngerTarget()`,
`startPersistentAngerTimer()`, etc.

**Config default for wolf count:**
`PocketDungeonsConfig.java` line 93:
`private static int feralWolvesPerCell = 2;`

#### Agreed fix (per player, 2026-08-27)

**Part 1: Clear anger on spawn.**

In `FeralContent.java`, inside the spawn callback (line 138, after the
`instanceof Wolf` check and before `setHomeTo`), add:
```java
wolf.stopBeingAngry();
```

This clears the persistent anger target and resets the anger timer, so
the wolf starts neutral and stays that way until a player hits it
(vanilla anger on hit). The class javadoc's claim becomes true by
code, not by assumption.

`stopBeingAngry()` is a default method on `NeutralMob` (verified in
the 26.2 bytecode). No import changes needed: `Wolf` already imports
through `NeutralMob` via its class hierarchy.

**Part 2: Reduce spawn count by one third.**

In `FeralContent.apply` (line 116), change the count calculation:
```java
int configured = PocketDungeonsConfig.feralWolvesPerCell();
if (configured <= 0) {
    return;
}
int count = configured * 2 / 3;
if (count <= 0) {
    count = 1;
}
```

With the default `feralWolvesPerCell = 2`, this drops to 1 wolf per
cell. Fewer angry wolves makes the affix calmer and less visually
chaotic while the anger clear does the real work. The floor of 1
ensures a configured minimum of 1 still spawns.

**Part 3 (fixed 2026-09-03): The `setHomeTo` radius issue.**

`setHomeTo(centre, HOME_RADIUS)` and the `HOME_RADIUS` constant are
removed from `FeralContent.apply`. Containment relies on the cell's
sealed walls and doors instead: a wolf that pathfinds through an open
door into the next cell is still within the dungeon's overall bounds
and is caught by `Instances.clearCellSync`'s 1 block margin at
teardown. No home position is ever set, so there is nothing to clear
after taming; a tamed wolf follows the player immediately.

#### Verification

After the fix:
1. Enter a FERAL affix dungeon.
2. Wolves should spawn neutral (not angry, no red eyes).
3. Wolves should be tamable with bones (angry wolves refuse bones).
4. With the default config of 2, only 1 wolf should spawn per cell.
5. Hitting a wolf should still anger it (vanilla anger on hit).
6. A wolf that spots a target should close to melee range without
   stuttering back and forth, and should stay inside its cell on its
   own (walls and doors, not a home pin).
7. A tamed wolf should follow the player out of the cell immediately.

---

### PD-4: Stale FRAGILE references after M10 reframe

**Reported:** 2026-08-26 (found in M10 to M14 handoff review)
**Severity:** Low (cosmetic, no behavioral impact)
**Status:** Fixed (2026-08-27)

M10 removed `Affix.FRAGILE` and `Affix.Kind.ELECTIVE`. The M10
handoff explicitly said "Grep `FRAGILE` across the tree before you
consider this done." Seven matches remain today.

#### Active naming issue (misleading)

**`DOOR_FRAGILE` constant:**
`RoomTemplateGenerator.java` line 357:
```java
private static final Identifier DOOR_FRAGILE = Identifier.parse("minecraft:exposed_copper_door");
```
Door 3 is now the +3 greater door with
`EnumSet.noneOf(Affix.class)` (`Keystone.java` line 132), so the name
describes an affix that no longer exists. The door block itself is
fine as a visual distinguisher; only the constant name is wrong.

**`DOOR_FRAGILE` usage:**
`RoomTemplateGenerator.java` line 384:
```java
Identifier[] blocks = {DOOR_NONE, DOOR_OMINOUS, DOOR_FRAGILE};
```

#### Stale comments describing FRAGILE's depletion as if it still exists

**`InstanceRecord.java` lines 60-65:**
```java
/**
 * The affixes riding on this run -- the elective one the door bought plus
 * whatever the key's level seeded. {@code FRAGILE} doubles every depletion;
 * the rest bend the run itself. Not {@code final} for the same reason
 * {@link #layout} is not: unknown until a door is chosen out of the lobby.
 */
Set<Affix> affixes;
```
FRAGILE is gone, so "FRAGILE doubles every depletion" describes
behavior that cannot happen. Also, "the elective one the door bought"
is stale since `ELECTIVE` was also removed.

**`KeystoneMath.java` lines 24-35:**
```java
/**
 * The level a keystone comes back at after a failure.
 *
 * <p>A fragile keystone doubles every figure, which is the entire cost of
 * having taken the {@code +3}. The floor is 1, always: a keystone never
 * disappears and never goes to zero.
 *
 * <p>{@code multiplier} arrives from {@link AffixMath#depletionMultiplier}
 * -- the {@code max} across the run's whole affix set, never a product -- and is
 * clamped to {@code [1, 2]} here as well, so no caller can double a key twice
 * over by passing a figure the set never produced.
 */
static int deplete(int level, int amount, int multiplier, int maxLevel) {
```
Same issue: "a fragile keystone doubles every figure" describes
behavior that cannot happen. The multiplier is always 1 now since no
affix carries a depletion multiplier above 1.

#### Historical references (fine, leave alone)

- `Affix.java` lines 38, 80: explain that FRAGILE was removed. These
  are documentation of the removal, not stale descriptions.
- `DungeonLog.java` line 44: references legacy save format carrying
  `"fragile"`. Correct for migration context.

#### Fix plan

**Step 1: Rename `DOOR_FRAGILE` to `DOOR_GREATER_3`.**

In `RoomTemplateGenerator.java`:
- Line 357: rename the constant declaration from `DOOR_FRAGILE` to
  `DOOR_GREATER_3`. The value (`minecraft:exposed_copper_door`) stays
  the same.
- Line 384: update the usage in the `blocks` array from `DOOR_FRAGILE`
  to `DOOR_GREATER_3`.

**Step 2: Rewrite the stale comment in `InstanceRecord.java`.**

Replace lines 60-65 with:
```java
/**
 * The affixes riding on this run: whatever the key's level seeded.
 * Not {@code final} for the same reason {@link #layout} is not:
 * unknown until a door is chosen out of the lobby.
 */
Set<Affix> affixes;
```
This removes the stale "elective one the door bought" and "FRAGILE
doubles every depletion" references. The elective door affix system
was removed in M10; every affix is now seeded by level thresholds.

**Step 3: Rewrite the stale comment in `KeystoneMath.java`.**

Replace lines 24-35 with:
```java
/**
 * The level a keystone comes back at after a failure.
 *
 * <p>The floor is 1, always: a keystone never disappears and never
 * goes to zero.
 *
 * <p>{@code multiplier} arrives from {@link AffixMath#depletionMultiplier}
 * -- the {@code max} across the run's whole affix set, never a product -- and is
 * clamped to {@code [1, 2]} here as well, so no caller can double a key twice
 * over by passing a figure the set never produced.
 */
```
This removes the "fragile keystone doubles every figure" reference.
The multiplier is always 1 now since no affix carries a depletion
multiplier above 1, but the clamping logic is still correct and worth
documenting.

**Step 4: Do not touch the historical references.**

`Affix.java` lines 38, 80 and `DungeonLog.java` line 44 are
intentional documentation of the M10 removal and legacy save format.
Leave them alone.

#### Verification

After the fix:
1. `grep -rn "FRAGILE" src/main/java` should return only the
   historical references in `Affix.java` and `DungeonLog.java`.
2. `grep -rn "DOOR_FRAGILE" src/main/java` should return zero matches.
3. `compileJava` should pass (the rename is mechanical).
4. All verification tasks should pass (no behavioral change).

---

### PD-5: resetroom deletes room but lobby regenerates without proper shell

**Reported:** 2026-08-27
**Severity:** Medium (room is unusable or looks broken)
**Status:** Fixed (2026-08-27)

After `/dungeon admin resetroom <name>`, the saved room is deleted.
When the player next enters a dungeon, the lobby is supposed to stamp
a fresh `entrance_hall` template (which includes the full shell: floor,
walls, ceiling, lamps) with the bedrock envelope applied on top.

**Player clarification:** the missing layer is the room's own shell
(stone brick walls, polished andesite floor, stone brick ceiling, sea
lantern lamps), NOT the bedrock envelope. The bedrock is placing
correctly. The shell that sits just inside the bedrock is absent.

**Player follow-up:** the bug only reproduces when `resetroom` is run
from INSIDE the dungeon world. Running it from the overworld works
correctly. Stale exported datapack was ruled out (player deleted it).

#### Root cause

Deferred room save races with synchronous file deletion.

When `resetroom` is run from inside the dungeon:

1. `adminPurgeByOwner` calls `InstanceTeardown.purge` for the
   player's live instance.
2. `purge` calls `RunLifecycle.saveRoomIfOwner` (line 93), which
   queues a deferred `server.execute(() -> saveRoom(...))` for the
   next tick (`RunLifecycle.java` lines 516-521).
3. `purge` continues: ejects the player, queues teardown.
4. `resetRoom` then calls `RoomStore.reset` synchronously (line 952),
   which deletes the `.dat` file immediately.
5. Next tick: the deferred `saveRoom` runs. It captures whatever is
   at `record.roomCellOrigin` (which is being cleared by
   `PendingClear`, so it is partially or fully air) and writes a
   NEW `.dat` file.
6. Player re enters with `/dungeon`. `stampLobby` calls
   `RoomStore.place`, which finds the newly written (but empty or
   partial) room file and places it. The `entrance_hall` fallback
   never runs. The player gets a room with bedrock walls, selector
   doors, and leave pad (all stamped after `RoomStore.place`), but
   no shell (the "room" that was placed was empty air).

When `resetroom` is run from outside the dungeon,
`adminPurgeByOwner` finds no live instances (purged == 0), so
`saveRoomIfOwner` is never queued. `RoomStore.reset` deletes the
file, no deferred save overwrites it, and the next entry correctly
falls through to `entrance_hall`.

#### Exact code references

**`adminPurgeByOwner` calls `purge` but does not null out
`roomCellOrigin`:**
`Instances.java` lines 1207-1216:
```java
static int adminPurgeByOwner(MinecraftServer server, UUID owner) {
    int purged = 0;
    for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
        if (owner.equals(record.owner)) {
            InstanceTeardown.purge(server, record, "room reset by an operator");
            purged++;
        }
    }
    return purged;
}
```

**`purge` queues the deferred save:**
`InstanceTeardown.java` line 93:
```java
RunLifecycle.saveRoomIfOwner(server, record, record.owner);
```

**`saveRoomIfOwner` defers to `server.execute`:**
`RunLifecycle.java` lines 506-522:
```java
static void saveRoomIfOwner(MinecraftServer server, InstanceRecord record, UUID member) {
    if (record.owner == null || !record.owner.equals(member)
            || record.roomCellOrigin == null || record.visitInstance) {
        return;
    }
    server.execute(() -> {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null) {
            saveRoom(level, server, record);
        }
    });
}
```

**`saveRoom` checks `roomCellOrigin == null` at execution time:**
`RunLifecycle.java` lines 463-466:
```java
static void saveRoom(ServerLevel level, MinecraftServer server, InstanceRecord record) {
    if (record.roomCellOrigin == null || record.visitInstance) {
        return;
    }
```

**`resetRoom` calls purge then reset synchronously:**
`DungeonCommands.java` lines 951-952:
```java
int purged = Instances.adminPurgeByOwner(server, target.id());
boolean hadRoom = RoomStore.reset(server, target.id());
```

**`RoomStore.reset` deletes the file immediately:**
`RoomStore.java` lines 265-277:
```java
static boolean reset(MinecraftServer server, UUID owner) {
    Path live = liveFile(server, owner);
    if (!Files.exists(live)) {
        return false;
    }
    try {
        Files.copy(live, backupFile(server, owner), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(live);
    } catch (IOException e) {
        PocketDungeonsMod.LOG.error("Could not reset the room for {}", owner, e);
    }
    return true;
}
```

**`stampLobby` falls through to `entrance_hall` only if
`RoomStore.place` returns false:**
`Instances.java` lines 424-429:
```java
boolean placedOwnRoom = RoomStore.place(level, server, owner, origin, 0,
        RandomSource.create(level.getRandom().nextLong()));
if (!placedOwnRoom) {
    TemplateStamper.place(level, level.getStructureManager(), origin,
            TemplateStamper.ENTRANCE_HALL, 0, level.getRandom().nextLong());
}
```

#### Fix plan

The fix exploits the existing `roomCellOrigin == null` guard in
`saveRoom` (line 464). By nulling `roomCellOrigin` on each purged
record before the deferred save runs, the save becomes a no op.

**Step 1: Null `roomCellOrigin` after purge in `adminPurgeByOwner`.**

In `Instances.java` lines 1207-1216, add
`record.roomCellOrigin = null;` after each purge call:
```java
static int adminPurgeByOwner(MinecraftServer server, UUID owner) {
    int purged = 0;
    for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
        if (owner.equals(record.owner)) {
            InstanceTeardown.purge(server, record, "room reset by an operator");
            // PD-5: prevent the deferred saveRoom queued by purge from
            // overwriting the file reset is about to delete. saveRoom
            // checks roomCellOrigin == null at execution time and returns
            // early, so no capture runs after the reset.
            record.roomCellOrigin = null;
            purged++;
        }
    }
    return purged;
}
```

**Step 2: Verify `roomCellOrigin` is accessible.**

`InstanceRecord.java` line 66 shows `Set<Affix> affixes;` is package
visible (no `private`). Check that `roomCellOrigin` is also package
visible. From the subagent report, the field is declared without
`private`, so it is accessible from `Instances` in the same package.

**Step 3: No changes needed in `DungeonCommands.resetRoom`.**

The existing order (purge, then reset) is correct. The purge queues
the deferred save, the null prevents it from running, and the reset
deletes the file. The next entry finds no file and falls through to
`entrance_hall`.

**Step 4: No changes needed in `RunLifecycle.saveRoomIfOwner` or
`saveRoom`.**

The existing `roomCellOrigin == null` guard in `saveRoom` (line 464)
is the mechanism that makes the fix work. The guard in
`saveRoomIfOwner` (line 513) checks `roomCellOrigin == null` before
queuing, but since `purge` calls `saveRoomIfOwner` before
`adminPurgeByOwner` nulls the field, the save is already queued by
that point. The execution time check in `saveRoom` is what catches
it.

#### Verification

After the fix:
1. Stand inside the dungeon world.
2. Run `/dungeon admin resetroom <your name>`.
3. Re enter with `/dungeon`.
4. The lobby should have the full shell: stone brick walls, polished
   andesite floor, stone brick ceiling, sea lantern lamps, selector
   doors, leave pad, and bedrock envelope.
5. Verify the `.dat` file does not reappear after the reset by
   checking the world's `data/pocketdungeons/rooms/` directory.
6. Repeat from the overworld to confirm the non dungeon path still
   works.

---

### PD-6: Lobby bedrock envelope leaves selector door side open to void

**Reported:** 2026-08-27
**Severity:** High (player can fall into the void)
**Status:** Fixed (2026-08-27)

The lobby's bedrock envelope does not generate bedrock on the SOUTH
side (the selector door side, where the dungeon will eventually
connect). The other three sides get bedrock, but SOUTH is left open.
A player who breaks through the sealed stone brick wall on that side
before choosing a door can fall into the void.

#### Root cause

`stampLobby` calls
`BedrockEnvelope.applyToLobbyCell(level, origin, DoorMask.Direction.SOUTH)`
(`Instances.java` line 439), which passes SOUTH as the
`reservedSide`. `applyToLobbyCell` calls
`applyToCell(level, o, Set.of(reservedSide))` (`BedrockEnvelope.java`
line 103), which skips the bedrock ring on any side in the reserved
set (line 132: the SOUTH branch is gated on
`!reservedSides.contains(SOUTH)`).

The design intent (documented at `BedrockEnvelope.java` lines 92-101)
is that the reserved side is left open because "when the real plan is
stamped, the ordinary `apply` runs again over the complete geometry
and correctly leaves the (by then genuinely occupied) reserved side
alone." In other words, the SOUTH side is left bedrock free so that
`BedrockEnvelope.apply` (called from `LayoutStamper.stamp` /
`stampBehindLobby`) does not put bedrock where the dungeon connection
needs to be.

The problem: between lobby stamp and door choice, there is no dungeon
behind the SOUTH wall. There is only the sealed stone brick door
(`RoomBuilder.sealDoor` at `Instances.java` line 448) and the selector
doors standing one block in front of it. If a player breaks through
that sealed wall (the owner can break their own room blocks), there is
nothing behind it but open void, because the bedrock backstop that
guards every other side is absent on SOUTH.

The player's intended design: bedrock should be generated on ALL four
sides of the lobby initially. The SOUTH bedrock face should only be
removed when a dungeon is actually generated behind it (i.e. when
`generateBehindLobby` / `LayoutStamper.stampBehindLobby` runs after a
door choice). Before that moment, the player should never be able to
fall out into the void from any side.

#### Exact code references

**`stampLobby` passes SOUTH as reserved side:**
`Instances.java` line 439:
```java
BedrockEnvelope.applyToLobbyCell(level, origin, DoorMask.Direction.SOUTH);
```

**`applyToLobbyCell` delegates to `applyToCell` with the reserved
side:**
`BedrockEnvelope.java` lines 102-104:
```java
static void applyToLobbyCell(ServerLevel level, BlockPos o, DoorMask.Direction reservedSide) {
    applyToCell(level, o, Set.of(reservedSide));
}
```

**`applyToCell` skips bedrock on reserved sides:**
`BedrockEnvelope.java` lines 118-153:
```java
static void applyToCell(ServerLevel level, BlockPos o, Set<DoorMask.Direction> reservedSides) {
    // ... sub floor and over ceiling always ...
    if (!reservedSides.contains(DoorMask.Direction.SOUTH)) {
        for (int x = 0; x < CELL; x++) {
            for (int y = -1; y <= CEILING_Y + 1; y++) {
                set(level, o.offset(x, y, CELL));
            }
        }
    }
    // ... other sides ...
}
```

**`generateBehindLobby` opens the SOUTH door and stamps the dungeon:**
`Instances.java` lines 594-595:
```java
RoomBuilder.openDoor(level, record.roomCellOrigin, mcDirection(dungeonDoor));
RoomTemplateGenerator.clearSelectorDoors(level, record.roomCellOrigin, dungeonDoor);
```
Then `LayoutStamper.stampBehindLobby` (line 578) stamps the dungeon
and calls `BedrockEnvelope.apply` (line 144), which only adds bedrock
and never removes it.

**`VisitService.createVisitInstance` has the same issue:**
`VisitService.java` line 121:
```java
BedrockEnvelope.applyToLobbyCell(level, origin, DoorMask.Direction.SOUTH);
```

**`BedrockEnvelope.apply` (the full geometry version) skips faces with
occupied neighbours:**
`BedrockEnvelope.java` lines 39-44:
```java
static void apply(ServerLevel level, PlanGeometry geometry) {
    Set<PlanCell> occupied = Set.copyOf(geometry.cells());
    for (PlanCell cell : geometry.cells()) {
        applyToCell(level, geometry.cellOrigin(cell), occupied, cell);
    }
}
```
When the dungeon is stamped behind the lobby, the lobby cell's SOUTH
neighbour is occupied (the first dungeon cell), so `apply` correctly
skips bedrock on that face. This is why the SOUTH side was originally
left open: `apply` would skip it anyway once the dungeon exists. The
problem is the window before the dungeon exists.

#### Fix plan

**Step 1: Stamp all four sides with bedrock in `stampLobby`.**

In `Instances.java` line 439, change the call from
`applyToLobbyCell` (which reserves one side) to `applyToCell` with an
empty reserved set:
```java
BedrockEnvelope.applyToCell(level, origin, java.util.Set.of());
```

This ensures all four sides of the lobby get bedrock, including SOUTH.
The player can no longer fall into the void from any side before
choosing a door.

**Step 2: Remove the SOUTH bedrock face when the dungeon is generated.**

In `Instances.generateBehindLobby`, after `RoomBuilder.openDoor` and
`RoomTemplateGenerator.clearSelectorDoors` (lines 594-595), and before
`LayoutStamper.stampBehindLobby` stamps the dungeon (line 578), add a
call to clear the SOUTH bedrock face so the dungeon can connect
through.

Add a helper to `BedrockEnvelope`:
```java
/**
 * Clears the bedrock ring on one face of a cell, so a dungeon
 * connection can pass through. Called when a door is chosen and
 * the dungeon is about to be stamped behind the lobby.
 */
static void clearFace(ServerLevel level, BlockPos o, DoorMask.Direction face) {
    BlockState air = Blocks.AIR.defaultBlockState();
    switch (face) {
        case NORTH -> {
            for (int x = 0; x < CELL; x++)
                for (int y = -1; y <= CEILING_Y + 1; y++)
                    level.setBlock(o.offset(x, y, -1), air, STAMP_FLAGS);
        }
        case SOUTH -> {
            for (int x = 0; x < CELL; x++)
                for (int y = -1; y <= CEILING_Y + 1; y++)
                    level.setBlock(o.offset(x, y, CELL), air, STAMP_FLAGS);
        }
        case WEST -> {
            for (int z = 0; z < CELL; z++)
                for (int y = -1; y <= CEILING_Y + 1; y++)
                    level.setBlock(o.offset(-1, y, z), air, STAMP_FLAGS);
        }
        case EAST -> {
            for (int z = 0; z < CELL; z++)
                for (int y = -1; y <= CEILING_Y + 1; y++)
                    level.setBlock(o.offset(CELL, y, z), air, STAMP_FLAGS);
        }
    }
}
```

Then in `generateBehindLobby`, before the stamp (line 578), add:
```java
BedrockEnvelope.clearFace(level, record.roomCellOrigin, dungeonDoor);
```

This removes the bedrock on the SOUTH face so the dungeon cells can
connect through. `LayoutStamper.stampBehindLobby` then stamps the
dungeon and `BedrockEnvelope.apply` (line 144) correctly skips the
SOUTH face because the first dungeon cell is now an occupied neighbour.

**Step 3: Apply the same fix to `VisitService.createVisitInstance`.**

In `VisitService.java` line 121, change:
```java
BedrockEnvelope.applyToLobbyCell(level, origin, DoorMask.Direction.SOUTH);
```
to:
```java
BedrockEnvelope.applyToCell(level, origin, java.util.Set.of());
```

Visit instances never generate a dungeon behind the lobby, so the
SOUTH bedrock stays in place permanently, which is correct: a visit
room should be sealed on all four sides.

**Step 4: Update the comments in `stampLobby` and
`createVisitInstance`.**

The existing comments at `Instances.java` lines 431-438 and
`VisitService.java` lines 113-120 explain why SOUTH is reserved.
Replace them with comments explaining that all four sides are now
bedrocked, and the SOUTH face is cleared in `generateBehindLobby` when
a dungeon is actually generated.

**Step 5: Consider whether `applyToLobbyCell` is still needed.**

After this fix, `applyToLobbyCell` is no longer called by
`stampLobby` or `createVisitInstance`. Check if it has any other
callers. If not, it can be removed or deprecated. The
`applyToCell(level, o, Set<DoorMask.Direction>)` overload remains
useful for `RunLifecycle`'s room relocation path (lines 886-895 in
`RunLifecycle.java`), which reserves two sides.

#### Verification

After the fix:
1. Enter a dungeon lobby (no saved room).
2. Break through the SOUTH wall (the selector door wall).
3. There should be bedrock behind it, not open void.
4. Choose a door (1, 2, or 3).
5. The SOUTH bedrock should be cleared and the dungeon should connect
   through.
6. The dungeon cells behind the lobby should be accessible.
7. Visit a room (`/dungeon visit <name>`).
8. All four sides of the visit room should have bedrock behind the
   walls.

---

### PD-7: Timeout closes the dungeon instead of just downgrading the key

**Reported:** 2026-08-27
**Severity:** High (gameplay frustration)
**Status:** Fixed (2026-08-27)

When the keystone timer runs out, the dungeon closes immediately and
the player is ejected. The player wants the dungeon to stay open so
they can still finish, with the timeout being a keystone downgrade
rather than a run ending.

**Player's intended design:**
- Timeout depletes the keystone by 2 levels (not 1).
- The dungeon stays open. The player can continue in overtime.
- If the player finishes after the timeout, they still get a door
  offer. Door 1 gives +1, so the net loss is only -1.
- If the keystone is level 1 or 2, it floors at 1 (never goes to zero
  or disappears).
- Free door (door 1) runs should still be exempt from timeout
  depletion, as they are now.

#### Root cause

`expireTimedOut` calls `InstanceTeardown.purge` at the end, which
closes the dungeon, ejects all members, and tears down the instance.
The depletion happens before the purge, but the purge ends the run
regardless.

Additionally, `timedOutDepletion` defaults to 1, not 2. And late
completion (`LATE` outcome) depletes by 2 again via
`lateCompletionDepletion`, which would double penalize if the timeout
penalty is already applied and the player then finishes late.

#### Exact code references

**`onTick` calls `expireTimedOut` when overtime starts and nobody has
completed:**
`Instances.java` lines 897-900:
```java
if (record.timer != null && record.timer.overTime() && record.completed.isEmpty()) {
    RunLifecycle.expireTimedOut(server, record);
    continue;
}
```
This check fires every watcher tick once `overTime()` is true. It
currently works because `expireTimedOut` purges the record (removing
it from `bySlot`), so it is not seen again. Without the purge, this
would call `expireTimedOut` every tick, depleting the key repeatedly.

**`expireTimedOut` depletes then purges:**
`RunLifecycle.java` lines 997-1005:
```java
static void expireTimedOut(MinecraftServer server, InstanceRecord record) {
    ServerPlayer owner = record.owner != null ? server.getPlayerList().getPlayer(record.owner) : null;
    returnKeystone(server, record, record.owner, owner,
            record.freeDoor ? Keystones.Outcome.NO_CHANGE : Keystones.Outcome.TIMED_OUT);
    if (owner == null) {
        PocketDungeonsMod.LOG.info("Dungeon slot {} timed out with its owner offline", record.slot);
    }
    InstanceTeardown.purge(server, record, "timed out");
}
```

**`TIMED_OUT` depletion default is 1:**
`PocketDungeonsConfig.java` line 79:
```java
private static int timedOutDepletion = 1;
```
Also at line 540 (defaults reset) and line 635 (config read).

**`LATE` depletion default is 2:**
`PocketDungeonsConfig.java` line 81:
```java
private static int lateCompletionDepletion = 2;
```
Also at line 541 (defaults reset) and line 636 (config read).

**Late completion applies `LATE` outcome:**
`RunLifecycle.java` lines 736-737:
```java
returnKeystone(server, record, player.getUUID(), player,
        record.freeDoor ? Keystones.Outcome.NO_CHANGE : Keystones.Outcome.LATE);
```
If the timeout already depleted by 2, this would deplete by another
2, for a total of -4 before the door offer. That is not the intended
design.

**`Keystones.Outcome.depletion`:**
`Keystones.java` lines 36-42:
```java
int depletion() {
    return switch (this) {
        case TIMED_OUT -> PocketDungeonsConfig.timedOutDepletion();
        case LATE -> PocketDungeonsConfig.lateCompletionDepletion();
        case NO_CHANGE -> 0;
    };
}
```

**`KeystoneMath.deplete` already floors at 1:**
`KeystoneMath.java` lines 36-44 (approximate):
```java
static int deplete(int level, int amount, int multiplier, int maxLevel) {
    // ... multiplier clamped to [1, 2] ...
    int depleted = level - amount * multiplier;
    return Math.max(1, depleted);
}
```
The floor of 1 is already enforced, so a level 1 or 2 key going down
by 2 will land at 1, not 0 or negative.

**`returnKeystone` calls `Keystones.returnTo`:**
`RunLifecycle.java` (the `returnKeystone` method delegates to
`Keystones.returnTo` with the record's affixes and the given outcome).

**`keystoneReturned` guard prevents double settlement:**
`InstanceRecord.java` lines 137-141:
```java
final Set<UUID> keystoneReturned = new HashSet<>();
```
`returnKeystone` checks this set and skips if the member is already in
it. This prevents a late completion and a subsequent exit from both
depleting.

#### Fix plan

**Step 1: Add a `timedOutPenaltyApplied` flag to `InstanceRecord`.**

In `InstanceRecord.java`, add a new field near the other run state
fields (around line 102, near `freeDoor`):
```java
/**
 * True once the timeout depletion has been applied to the owner's
 * keystone. Prevents repeated depletion on every watcher tick while
 * the run continues in overtime (PD-7: timeout no longer closes the
 * dungeon).
 */
boolean timedOutPenaltyApplied;
```

**Step 2: Change `timedOutDepletion` default from 1 to 2.**

In `PocketDungeonsConfig.java`:
- Line 79: change `private static int timedOutDepletion = 1;` to
  `private static int timedOutDepletion = 2;`
- Line 540: change `timedOutDepletion = 1;` to `timedOutDepletion = 2;`
- Line 635: change the default in `readInt` from `1` to `2`
- Line 865: change `root.addProperty("timedOutDepletion", 1);` to
  `root.addProperty("timedOutDepletion", 2);`

**Step 3: Guard `onTick` against repeated `expireTimedOut` calls.**

In `Instances.java` line 897, add the flag check:
```java
if (record.timer != null && record.timer.overTime()
        && record.completed.isEmpty()
        && !record.timedOutPenaltyApplied) {
    RunLifecycle.expireTimedOut(server, record);
    continue;
}
```

**Step 4: Change `expireTimedOut` to not purge.**

In `RunLifecycle.java` lines 997-1005, remove the
`InstanceTeardown.purge` call and set the flag instead:
```java
static void expireTimedOut(MinecraftServer server, InstanceRecord record) {
    ServerPlayer owner = record.owner != null ? server.getPlayerList().getPlayer(record.owner) : null;
    returnKeystone(server, record, record.owner, owner,
            record.freeDoor ? Keystones.Outcome.NO_CHANGE : Keystones.Outcome.TIMED_OUT);
    record.timedOutPenaltyApplied = true;
    if (owner != null) {
        owner.sendSystemMessage(Component.literal(
                "The clock ran out. Your keystone is downgraded by "
                        + PocketDungeonsConfig.timedOutDepletion()
                        + ", but the dungeon stays open. Finish it for a door.")
                .withStyle(ChatFormatting.YELLOW));
    } else {
        PocketDungeonsMod.LOG.info("Dungeon slot {} timed out with its owner offline", record.slot);
    }
}
```

The dungeon stays open. The timer continues ticking in overtime (the
bar shows "OVER TIME" in red). The player can still reach the exit
pad and complete the run.

**Step 5: Change late completion to `NO_CHANGE` when timeout penalty
was already applied.**

In `RunLifecycle.completeRun` (line 736), check the flag before
applying the late penalty:
```java
if (late) {
    Keystones.Outcome outcome;
    if (record.freeDoor || record.timedOutPenaltyApplied) {
        // Free door never depletes. Timeout already depletes, so a
        // late finish after timeout does not deplete again.
        outcome = Keystones.Outcome.NO_CHANGE;
    } else {
        outcome = Keystones.Outcome.LATE;
    }
    returnKeystone(server, record, player.getUUID(), player, outcome);
}
```

This prevents the double penalty: timeout depletes by 2, late
completion depletes by 0, door 1 gives +1, net is -1.

For a late completion WITHOUT timeout (the player finishes in
overtime but the timeout penalty was never applied because
`expireTimedOut` was never called, e.g. the player completed on the
same tick overtime started), the `LATE` penalty of 2 still applies.
This preserves the existing behavior for the edge case where a player
finishes just as the clock runs out.

**Edge case: player completes on the same tick overtime starts.**
With the new guard in Step 3, `expireTimedOut` fires on the first
tick where `overTime()` is true and `completed` is empty.
`completeRun` is called from the member loop below the timeout check,
so the order within one tick is: timeout check first, then member
loop. If the player is on the pad and completes on that same tick,
`expireTimedOut` fires first (applying the penalty and setting the
flag), then the player completes. The flag prevents the timeout from
firing again on the next tick, and the late completion check sees
`timedOutPenaltyApplied = true` and uses `NO_CHANGE`.

If the player completes BEFORE overtime starts (in time), `late` is
false (chests > 0), so the late penalty branch is never entered.

If the player completes after overtime but `expireTimedOut` has not
yet fired (e.g. the watcher tick has not run yet), `late` is true
(chests <= 0) and `timedOutPenaltyApplied` is false, so `LATE`
applies. This is the same as the current behavior for a late
completion.

**Step 6: Update the `Keystones` class javadoc.**

`Keystones.java` lines 18-23 describe the old behavior. Update to
reflect that timeout no longer closes the dungeon:
```java
// Running out of time downgrades the keystone by 2 levels but does
// not close the dungeon: the player can still finish in overtime,
// and a door 1 finish gives +1, so the net loss is only 1 level.
// A completion finished after the clock adds no further penalty
// since the timeout already applied it.
```

**Step 7: Update the `RunTimer` class javadoc.**

`RunTimer.java` lines 15-20 say "The run only ends on its own once
the clock runs out and nobody has completed it yet." This is no longer
true. Update to:
```java
// Expiry does not end the run. The bar turns red, reads OVER TIME,
// and the run continues. The keystone is downgraded by 2 levels
// once when the clock runs out, but the player can still finish
// and earn a door offer to mitigate the loss.
```

**Step 8: Handle the `freeDoor` exemption.**

The existing code exempts free door runs from timeout depletion
(`record.freeDoor ? NO_CHANGE : TIMED_OUT`). This is preserved: a
free door timeout still applies `NO_CHANGE` and sets
`timedOutPenaltyApplied = true`, so a late free door completion also
gets `NO_CHANGE`. No change needed here beyond what Step 4 and Step 5
already do.

**Step 9: Consider what happens if the player never finishes.**

Without the purge, a timed out dungeon stays open indefinitely. The
existing reward room grace timer (`expiresAtTick`) only starts after
completion. A timed out but never completed run would hold the slot
forever.

Add a grace timer for timed out but uncompleted runs. In `onTick`,
after the timeout penalty is applied, start a grace countdown:
```java
if (record.timedOutPenaltyApplied && record.expiresAtTick == 0
        && record.completed.isEmpty()) {
    record.expiresAtTick = now
            + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
}
```
This reuses the existing grace seconds config. When it expires, the
existing check at line 904 calls `retireOrPurge`, which closes the
dungeon. The player gets a message when the timeout penalty is
applied (Step 4) telling them to finish, and the grace window gives
them time to do so.

Alternatively, add a separate config `overtimeGraceSeconds` if the
overtime window should be different from the reward room grace
window. For simplicity, reusing `rewardRoomGraceSeconds` is
recommended for the first implementation.

#### Verification

After the fix:
1. Enter a keystone run at level 5.
2. Let the timer run out.
3. The dungeon should NOT close. The bar should show "OVER TIME" in
   red.
4. The keystone should be downgraded by 2 (from 5 to 3).
5. A chat message should tell the player the timeout happened and
   the dungeon is still open.
6. Continue and reach the exit pad.
7. The run completes. Door 1 offers +1 (from level 3 to 4). Net
   change: -1 (from 5 to 4).
8. The keystone should never go below 1. Test with level 1: timeout
   depletes by 2 but floors at 1. Late completion gives +1, so the
   key goes to 2.
9. Test with a free door (door 1) run: timeout should NOT deplete.
   Late completion should NOT deplete. The key stays at its current
   level.
10. Test not finishing: after the overtime grace window, the dungeon
    should close on its own.

---

### PD-8: Room changes lost when dungeon closes via timeout or teardown

**Reported:** 2026-08-27
**Severity:** High (player loses room edits on every timeout/disconnect/teardown)
**Status:** Fixed (2026-08-27)

When a player times out of a keystone run (or disconnects, or the
instance is purged for any other reason), the room they decorated
during the run is not saved. The next time they enter, the room is
either the previous saved version or the default `entrance_hall`
template, with none of their changes.

#### Root cause

The room save is deferred via `server.execute`, but the block clear
that erases the room cell is queued in the same `purge` call. The
deferred save and the `PendingClear` race: if the clear processes the
room cell before the deferred save captures it, the room is written
as empty air.

The exact sequence in `InstanceTeardown.purge`:

1. `purge` calls `RunLifecycle.saveRoomIfOwner` (line 93), which
   queues `server.execute(() -> saveRoom(...))`. This does NOT run
   immediately; it is posted to the server thread's task queue for
   the next tick.
2. `purge` calls `Instances.eject` for each member (line 97). `eject`
   calls `saveRoomIfOwner` again (Instances line 755), queuing a
   second deferred save. The comment at line 754 says "Before the
   teleport, while the room is still exactly as they left it" but the
   save is deferred, so it does not run before the teleport.
3. `purge` calls `teardown` (line 129), which adds a `PendingClear`
   to `pendingClears` (line 215). The room cell is included as the
   `extraCellOrigin` (line 130, 197-199).
4. Next tick: the deferred `server.execute` tasks drain, then
   `END_SERVER_TICK` fires and `processClears` starts erasing blocks.
   The save and clear are both queued in the same tick and both
   execute in the next tick. The save drains first, but
   `processClears` runs in `END_SERVER_TICK` which is the same tick
   phase. Whether the save completes before the clear reaches the
   room cell depends on the `PendingClear`'s position in the queue
   and the `clearBlocksPerTick` budget. With the default budget of
   8192 and a cell volume of ~2916 blocks, a small dungeon (1-2
   cells) can clear the room cell in the first `processClears` call,
   potentially before the save's `RoomStore.capture` reads the
   blocks.

The core problem is that the save is deferred at all. The comment on
`saveRoomIfOwner` says "last chance to read the room," but a deferred
save is not a last chance; it is a next-tick chance. Any teardown
path that queues a `PendingClear` in the same call must save
synchronously, before the clear is queued, not after.

This affects every path that calls `purge`:
- `expireTimedOut` (timeout): `RunLifecycle.java` line 1004.
- `RunLifecycle.exit` (party leader left): line 618.
- `RunLifecycle.dropMember` (party leader disconnected): line 955.
- `RunLifecycle.dropMember` (abandoned lobby / visit ended): line 961.
- `Instances.purgeIfAbandonedLobby`: line 784.
- `Instances.adminPurgeByOwner`: line 1211.

And every path that calls `retireOrPurge`:
- `onTick` (reward room grace elapsed): `Instances.java` line 905.
  Note: `retireOrPurge` only calls `purge` for non-keystone runs or
  runs with no room; keystone runs with a room become lingering
  quarries and are not cleared. But the deferred save in
  `retireOrPurge` (line 58) still races if the path does reach
  `purge`.

#### Exact code references

**`saveRoomIfOwner` defers via `server.execute`:**
`RunLifecycle.java` lines 506-522:
```java
static void saveRoomIfOwner(MinecraftServer server, InstanceRecord record, UUID member) {
    if (record.owner == null || !record.owner.equals(member)
            || record.roomCellOrigin == null || record.visitInstance) {
        return;
    }
    server.execute(() -> {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null) {
            saveRoom(level, server, record);
        }
    });
}
```

**`saveRoom` checks `roomCellOrigin` at execution time:**
`RunLifecycle.java` lines 463-466:
```java
static void saveRoom(ServerLevel level, MinecraftServer server, InstanceRecord record) {
    if (record.roomCellOrigin == null || record.visitInstance) {
        return;
    }
```

**`purge` queues deferred save, then queues clear:**
`InstanceTeardown.java` lines 90-130:
```java
static void purge(MinecraftServer server, InstanceRecord record,
                  String reason, UUID excludeFromStraySweep) {
    RunLifecycle.saveRoomIfOwner(server, record, record.owner);  // line 93: deferred
    for (UUID member : new ArrayList<>(record.members.keySet())) {
        ServerPlayer player = server.getPlayerList().getPlayer(member);
        if (player != null) {
            Instances.eject(server, record, player);              // line 97: also deferred
            ...
        }
        ...
    }
    ...
    teardown(server, record.slot, record.origin, record.layout, reason,  // line 129
             excludeFromStraySweep, record.roomCellOrigin);              // line 130: queues clear
}
```

**`eject` also defers the save:**
`Instances.java` lines 753-755:
```java
static void eject(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
    // Before the teleport, while the room is still exactly as they left it.
    RunLifecycle.saveRoomIfOwner(server, record, player.getUUID());
    ...
```

**`teardown` queues the `PendingClear` that erases the room cell:**
`InstanceTeardown.java` lines 197-215:
```java
if (extraCellOrigin != null) {
    cellOrigins.add(extraCellOrigin);
    bounds = bounds.minmax(CellGeometry.cellBounds(extraCellOrigin));
}
// ...
pendingClears.add(new PendingClear(slot, bounds, cellOrigins, reason));
```

**`processClears` runs at the start of `END_SERVER_TICK`:**
`Instances.java` lines 94-98:
```java
ServerTickEvents.END_SERVER_TICK.register(server -> {
    InstanceTeardown.processClears(server);  // runs before onTick
    onTick(server);
    processJoinRecoveries(server);
});
```

**`retireOrPurge` also defers the save:**
`InstanceTeardown.java` lines 55-62:
```java
static void retireOrPurge(MinecraftServer server, InstanceRecord record, String reason) {
    RunLifecycle.saveRoomIfOwner(server, record, record.owner);  // line 58: deferred
    if (!record.isKeystoneRun() || record.roomCellOrigin == null) {
        purge(server, record, reason);  // line 60: queues clear
        return;
    }
    // ... keystone run with room: becomes lingering, no clear queued ...
}
```

**`expireTimedOut` calls `purge`:**
`RunLifecycle.java` lines 997-1005:
```java
static void expireTimedOut(MinecraftServer server, InstanceRecord record) {
    ServerPlayer owner = record.owner != null ? server.getPlayerList().getPlayer(record.owner) : null;
    returnKeystone(server, record, record.owner, owner,
            record.freeDoor ? Keystones.Outcome.NO_CHANGE : Keystones.Outcome.TIMED_OUT);
    ...
    InstanceTeardown.purge(server, record, "timed out");
}
```

**`RoomStore.capture` reads blocks at `cellOrigin`:**
`RoomStore.java` lines 118-134:
```java
static void capture(ServerLevel level, MinecraftServer server, UUID owner,
                    BlockPos cellOrigin, int capturedQuarterTurns) {
    // ... discard non-decoration entities ...
    StructureTemplate template = new StructureTemplate();
    template.fillFromWorld(level, cellOrigin, TemplateStamper.TEMPLATE_SIZE, true, List.of());
    CompoundTag tag = template.save(new CompoundTag());
    // ...
    save(server, owner, tag);
}
```

#### Fix plan

Make the room save synchronous in every path that queues a
`PendingClear` for the room cell. The save must run before
`teardown` is called, not after.

**Step 1: Add a synchronous save method to `RunLifecycle`.**

In `RunLifecycle.java`, add a method that saves immediately on the
current thread rather than deferring:

```java
/**
 * Synchronous version of {@link #saveRoomIfOwner}, for paths where
 * the room cell is about to be cleared (purge, retireOrPurge). The
 * deferred {@code server.execute} in {@link #saveRoomIfOwner} can
 * race with the {@code PendingClear} queued by {@code teardown},
 * so callers that are about to destroy the room must save it now,
 * before the clear is queued, not after.
 */
static void saveRoomIfOwnerSync(ServerLevel level, MinecraftServer server,
                                InstanceRecord record, UUID member) {
    if (record.owner == null || !record.owner.equals(member)
            || record.roomCellOrigin == null || record.visitInstance) {
        return;
    }
    saveRoom(level, server, record);
}
```

This method is called only from the server thread (all purge and
retireOrPurge callers run on the server thread), so there is no
threading concern. `saveRoom` reads a 16x7x16 volume and writes a
small NBT file, so the synchronous call is cheap.

**Step 2: Call the synchronous save in `purge`.**

In `InstanceTeardown.purge` (line 93), replace the deferred call:

```java
static void purge(MinecraftServer server, InstanceRecord record,
                  String reason, UUID excludeFromStraySweep) {
    // Safety net, as in retireOrPurge: last chance to read the room.
    // Synchronous because teardown (below) queues a PendingClear that
    // will erase the room cell. A deferred save could race with it.
    ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
    if (level != null) {
        RunLifecycle.saveRoomIfOwnerSync(level, server, record, record.owner);
    }
    for (UUID member : new ArrayList<>(record.members.keySet())) {
        ServerPlayer player = server.getPlayerList().getPlayer(member);
        if (player != null) {
            Instances.eject(server, record, player);
            // ... rest unchanged ...
```

The `eject` call inside the loop still calls the deferred
`saveRoomIfOwner` (Instances line 755). That deferred save is now
redundant (the synchronous save already captured the room), but it
is harmless: `saveRoom` writes the same file again with the same
content. Leave `eject` unchanged to avoid breaking the non-purge
paths where `eject` is called without a teardown (voluntary exit,
death rescue).

**Step 3: Call the synchronous save in `retireOrPurge`.**

In `InstanceTeardown.retireOrPurge` (line 58), make the same change:

```java
static void retireOrPurge(MinecraftServer server, InstanceRecord record, String reason) {
    // Safety net: eject/dropMember have almost certainly saved already,
    // but a teardown is the last moment the room exists to be read.
    // Synchronous because purge (below) queues a PendingClear.
    ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
    if (level != null) {
        RunLifecycle.saveRoomIfOwnerSync(level, server, record, record.owner);
    }
    if (!record.isKeystoneRun() || record.roomCellOrigin == null) {
        purge(server, record, reason);
        return;
    }
    // ... rest unchanged ...
```

Note: `retireOrPurge` only reaches `purge` for non-keystone runs or
runs with no room. For keystone runs with a room, it becomes a
lingering quarry and no clear is queued. The synchronous save is
still correct in the lingering case: it captures the room before
force-load tickets are released, which is the last reliable moment
the chunks are guaranteed loaded.

**Step 4: Keep `saveRoomIfOwner` deferred for non-teardown paths.**

The deferred `saveRoomIfOwner` is still used by:
- `eject` (Instances line 755): player leaving voluntarily, dungeon
  stays open. No `PendingClear` is queued, so the deferred save is
  safe.
- `dropMember` (RunLifecycle line 926): member offline or left
  dimension, dungeon stays open. No `PendingClear` is queued unless
  the leader left and `purge` is called, but `purge` now saves
  synchronously first.

Leave the deferred method and its existing callers unchanged.

**Step 5: No changes needed in `eject` or `dropMember`.**

`eject` calls `saveRoomIfOwner` (deferred) for the non-purge case.
When `eject` is called from `purge`, the synchronous save has already
run, so the deferred save is redundant but harmless (same content,
same file). When `eject` is called from `RunLifecycle.exit` or death
rescue, the dungeon stays open, so the deferred save is correct.

`dropMember` calls `saveRoomIfOwner` (deferred) before potentially
calling `purge`. If `purge` is called, it saves synchronously first.
If `purge` is not called (someone else is still in the party), the
deferred save is correct.

#### Verification

After the fix:
1. Enter a keystone run.
2. Decorate the room (place blocks, break blocks, add items).
3. Let the timer run out (timeout).
4. Re-enter with `/dungeon`.
5. The room should have all the changes made during the run.
6. Repeat with disconnect: decorate, close the client, reconnect,
   re-enter. The room should be saved.
7. Repeat with `/dungeon exit`: decorate, exit voluntarily, re-enter.
   The room should be saved.
8. Repeat with admin purge: decorate, have an admin run
   `/dungeon admin resetroom`. The room file should be deleted (this
   is the intended reset behavior, not a save failure).
9. Repeat with completion: decorate, complete the run, re-enter. The
   room should be saved at the terminal cell location (this is
   handled by `completeDungeon`, not by the purge save).


---

## Audit batch (2026-08-31)

PD-9 through PD-51 come from a six-pass static audit of the whole mod
(generation pipeline, instance lifecycle, commands/config/UI, content/economy,
progression/data, cross-cutting). Severity labels (Critical/High/Medium/Low)
are carried over from that audit. Fix these roughly in severity order; M36
through M39 in the roadmap group them that way.

### PD-9: `/dungeon key info` throws an NPE for any player without a keystone (Critical)

**Reported:** 2026-08-31
**Severity:** Critical (unprivileged command crashes for any player)
**Status:** Fixed (2026-08-31)

`Keystone.findHeld` returns `null` when the player carries no keystone, not
an empty stack (`Keystone.java` lines 309-322, explicit `return null`). The
`keyInfo` command tests `.isEmpty()` on that result instead of `== null`.

#### Exact code references

`DungeonCommands.java` line 489:
```java
private static int keyInfo(ServerPlayer player) {
    if (Keystone.findHeld(player).isEmpty()) {
```

Every other call site in the tree correctly tests for null:
`DialogRouter.java:181`, `DialogRouter.java:207`, `DungeonCommands.java:346`,
`DungeonCommands.java:405`, `RunLifecycle.java:82`.

#### Fix

```java
private static int keyInfo(ServerPlayer player) {
    if (Keystone.findHeld(player) == null) {
```

No other change needed; the refusal message and `DialogKit.show` call below
it are already correct.

#### Verification

Run `/dungeon key info` while holding no keystone. Should print "You are not
carrying a keystone." with no exception in the log. Run it again while
holding one; should open the inspect screen as before.

---

### PD-10: Lingering quarries are never purged, leaking a slot per completed run (Critical)

**Reported:** 2026-08-31
**Severity:** Critical (unbounded slot and world-footprint growth)
**Status:** Fixed (2026-08-31)

`InstanceTeardown` marks a completed keystone run `lingering = true` and
leaves it in `InstanceRegistry.bySlot` with its slot still held. The
documented escape hatch is `RunLifecycle.enter`'s lingering-quarry check,
which calls `InstanceTeardown.retireOrPurge` on the old record. But
`retireOrPurge` on an already-lingering record with a non-null
`roomCellOrigin` falls past the purge branch and just re-marks it lingering.
Nothing else clears the flag except an admin purge or `SERVER_STOPPING`.

#### Root cause

`InstanceTeardown.java` lines 69-110 (`retireOrPurge`): the branch that would
purge only fires `if (!record.isKeystoneRun() || record.roomCellOrigin ==
null)`. A completed keystone run with a saved room fails both conditions
every time it is re-evaluated, so it retires again instead of purging.

`RunLifecycle.java` lines 224-230 is the only call site that re-invokes
`retireOrPurge` on a lingering record, and it does so unconditionally as
part of starting the player's next run, expecting this call to free the slot.

#### Consequences

`InstanceRegistry.allocateSlot()` walks upward from 0 forever
(`InstanceRegistry.java`), so slot indices and the on-disk footprint grow
without bound, contradicting the class comment in `Instances.java` lines
52-57. `Instances.onTick` (line 1061) skips lingering records, so there is
no expiry either. `roomRecordAt` (line 1339) does not filter `lingering`,
so `RoomProtection` keeps protecting an abandoned cell indefinitely.

#### Fix

In `InstanceTeardown.retireOrPurge`, a record that is already `lingering`
when re-entered (i.e. this is the second time a run has ended for this
slot without an intervening purge) should purge rather than re-retire:

```java
static void retireOrPurge(MinecraftServer server, InstanceRecord record, String reason) {
    if (record.lingering) {
        purge(server, record, reason);
        return;
    }
    if (!record.isKeystoneRun() || record.roomCellOrigin == null) {
        purge(server, record, reason);
        return;
    }
    // existing retire-to-lingering path
    ...
    record.lingering = true;
}
```

This makes the RunLifecycle.enter call site's re-invocation actually free
the slot, since by the time it runs the record is already lingering from
the first retire.

#### Verification

Complete a keystone run and leave the lobby (do not start a new one).
Confirm the record stays `lingering` and the slot stays held (existing
behavior, room remains browsable). Then start a second run as the same
owner. Confirm the old slot is purged (`usedSlots` no longer contains it,
`bySlot` no longer has the record) rather than re-marked lingering. Repeat
across several runs and confirm slot indices stop climbing.

---

### PD-11: Stamp failure behind the lobby erases the player's room and double-frees the slot (Critical)

**Reported:** 2026-08-31
**Severity:** Critical (data loss plus slot corruption)
**Status:** Fixed (2026-08-31)

When `generateBehindLobby`'s stamping throws, the catch block queues a
`PendingClear` whose origin resolves to the player's own persistent room
cell, and does not remove the record from `InstanceRegistry.bySlot` even
though the clear's completion frees the slot.

#### Exact code references

`Instances.java` lines 666-675:
```java
} catch (RuntimeException e) {
    InstanceTeardown.teardown(server, record.slot, record.origin,
            InstanceLayout.forClearingOnly(planOrigin, geometry), "stamp failed");
    return false;
}
```

`planOrigin` at line 659 is `record.roomCellOrigin.offset(minX*CELL, 0,
minZ*CELL)`, and `PlanGeometry.cellOrigin` (`PlanGeometry.java` lines 56-60)
maps cell `(0,0)` back to exactly `record.roomCellOrigin`. So the clear
blanks the room cell, not scratch space.

`InstanceTeardown.finishClear` (line 296) does `usedSlots.remove(clear.slot)`
once the clear completes, but nothing in this catch block removes the
record from `bySlot`. The next `allocateSlot()` can hand out the freed slot
while `bySlot` still maps it to the orphaned record, and `bySlot.put` then
silently overwrites it.

#### Fix

Two changes, both in the catch block at `Instances.java:666`:

1. Do not clear the room cell. Build the `InstanceLayout` for clearing from
   `geometry` alone (the failed dungeon cells), excluding
   `record.roomCellOrigin`. Pass the dungeon own origin (`record.origin`),
   not `planOrigin`.
2. Remove the record from the registry as part of the teardown, matching
   what `InstanceTeardown.purge` does for every other failure path:
   `InstanceRegistry.bySlot.remove(record.slot)` and the corresponding
   `byMember` entries.

The cleanest fix is to route this through `InstanceTeardown.purge` (which
already handles the room-preserving case correctly, see PD-10 fix)
instead of the ad hoc `teardown` call.

#### Verification

Force a stamp failure (e.g. temporarily corrupt a room template reference)
behind a lobby with a previously-decorated room. Confirm the room survives
the failure and the slot is either fully freed or fully retained, never
both.

---

### PD-12: Disconnect handler mutates instance state off the server thread (Critical)

**Reported:** 2026-08-31
**Severity:** Critical (data race, possible corruption or crash)
**Status:** Fixed (2026-08-31)

`ServerPlayConnectionEvents.DISCONNECT` fires on Netty IO thread for an
abrupt disconnect. The handler comment acknowledges this and routes one
operation (`RoomStore.capture`) through `server.execute`, but the rest of
the call chain runs directly on the disconnect thread and mutates
collections the server thread iterates concurrently.

#### Exact code references

`RunLifecycle.java` lines 542-554 (the disconnect handler, mitigating only
the room capture). From `Instances.java:157-172` the same call chain reaches,
all off-thread:

- `PartyService.clearFor` (`PartyService.java:65-69`): three `HashMap.remove`
- `Instances.java:166`: `pendingReturns.put` (plain `HashMap`)
- `RunLifecycle.dropMember` (lines 1114-1153): `record.members.remove`,
  `InstanceRegistry.byMember.remove`, `record.onPad.remove`,
  `RunTimer.removePlayer`
- possibly `InstanceTeardown.purge`: `InstanceRegistry.bySlot.remove`
  (line 149), `pendingClears.add` (line 247), `usedSlots` writes (line 196)

Concurrently, the server thread reads these same structures every tick
(`Instances.onTick` line 1057, copying `bySlot.values()`) and on every
block break (`roomRecordAt` line 1340, `dungeonCellLookupAt` line 1392) and
in `processClears` (line 275, iterating `pendingClears`).

#### Fix

Wrap the entire disconnect handler body in `server.execute` lambda, the
same way the room-capture call already is:

```java
ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
    ServerPlayer player = listener.player;
    server.execute(() -> handleDisconnect(server, player));
});
```

Move the existing body into `handleDisconnect`.

#### Verification

No direct headless test (this is a threading defect). After the fix, stress
test by having several players disconnect abruptly (kill client, not
`/dungeon exit`) while mid-run, repeated many times, and confirm no
`ConcurrentModificationException` or corrupted `bySlot`/`byMember` state in
the logs across a long play session.

---

### PD-13: Force-load tickets leak across runs and survive server restarts (Critical)

**Reported:** 2026-08-31
**Severity:** Critical (unbounded forced-chunk accumulation, persists across restarts)
**Status:** Fixed (2026-08-31)

Every door choice force-loads the new geometry chunks
(`Instances.generateBehindLobby`, line 661), but
`RunLifecycle.resetForNextDungeon` (lines 596-644) contains no matching
`setChunkForced` release call. Tickets are only released in
`InstanceTeardown.finishClear` (lines 293-295) and `retireOrPurge` (lines
101-105), both of which walk the current layout cells only.

#### Consequences

Any chunk occupied by run N but not by run N plus 1 stays force-loaded
permanently. `setChunkForced` persists into the level forced-chunk saved
data, so this survives a server restart with nothing in memory that knows
to release it.

#### Fix

Before force-loading the new geometry chunks in `generateBehindLobby`,
release the previous run chunks first. Capture
`record.layout == null ? Set.of() : record.layout.geometry().chunks()` at
the top of `generateBehindLobby` before the new layout is built, release
those chunks, then proceed as today.

#### Verification

Instrument (temporarily) a log line on every `setChunkForced` call with the
boolean and chunk pos. Run several dungeons in sequence behind one lobby
and confirm the count of currently-forced chunks stays bounded rather than
growing every run.

---

### PD-14: No startup reconciliation after a crash orphans geometry and forced chunks (Critical)

**Reported:** 2026-08-31
**Severity:** Critical (permanent world corruption after any unclean shutdown)
**Status:** Fixed (2026-08-31)

`SERVER_STOPPING` (`Instances.java` lines 235-248) is the only cleanup path
and only covers a clean shutdown. After a crash, `usedSlots` comes back
empty on restart while the blocks from the previous session are still on
disk and force-load tickets (PD-13) are still set. The next player
`/dungeon` takes slot 0 and stamps over cell 0, leaving the previous run
remaining cells standing and connected to it.

#### Fix

This needs a real reconciliation pass. On `SERVER_STARTED`
(`Instances.java` lines 216-233, alongside the manifest loads), before
accepting any player into a dungeon:

1. Scan the dungeon dimension region files for chunks in the mod slot grid
   range that show a bedrock envelope or stamped geometry but no
   corresponding `InstanceRecord` in memory (`InstanceRecord` is
   deliberately not persisted, per its own class comment).
2. For each such orphaned region, clear it via `InstanceTeardown` budgeted
   clear (reuse `processClears` per-tick budget rather than blocking
   startup).
3. Release any force-load tickets in the slot grid range that have no live
   record backing them.

This is large enough to be its own milestone rather than a quick patch; see
M40 in the roadmap.

#### Verification

Force-kill the server mid-run (not graceful shutdown) with an active
decorated room and an active dungeon. Restart. Confirm the reconciliation
pass cleans up orphaned geometry and a fresh `/dungeon` from slot 0 does
not collide with leftover blocks.

---

### PD-15: M34 bounty block over-counts three bounties and never advances a fourth (High)

**Reported:** 2026-08-31
**Severity:** High (economy and progression correctness, single-run exploit)
**Status:** Fixed (2026-08-31)

`RunLifecycle.completeRun` M34 bounty hooks sit outside the
`firstCompletion` guard that gates every other once-per-run effect, so they
run once per party member. Independently, they read `record.rewardChests`
before it is ever written for the first completer, so the timed bounty
never fires at all.

#### Exact code references

`RunLifecycle.java` lines 790-820:
```java
boolean firstCompletion = record.completed.isEmpty();
record.completed.add(player.getUUID());
TaskTracker.progress(player, TaskTracker.Task.COMPLETE_RUN, 1);

if (record.isKeystoneRun()) {
    int chests = record.rewardChests;                    // always -1 here
    boolean timed = record.timer != null && chests > 0;   // always false
    Set<BlockPos> spawners = record.layout.trialSpawners();
    if (!spawners.isEmpty()) {
        int cleared = TrialContent.countCleared(player.level(), spawners);
        BountyTracker.progress(server, record.owner, Bounty.CLEAR_HALLS.id, cleared);
    }
    if (timed) { BountyTracker.progress(server, record.owner, Bounty.SPEEDRUNNER.id, 1); }
    if (record.chosenStep >= 2) { BountyTracker.progress(server, record.owner, Bounty.SPELUNKER.id, 1); }
    if (record.members.size() >= 2) { BountyTracker.progress(server, record.owner, Bounty.PACK_HUNTER.id, 1); }
}

if (firstCompletion) {           // bounty block above is NOT inside this
    completeDungeon(server, record);   // line 974 sets record.rewardChests here
}
```

`record.rewardChests` is initialized to `-1` (`InstanceRecord.java:192`) and
has exactly one write, at `RunLifecycle.java:974` inside `completeDungeon`,
called from inside the `firstCompletion` branch that starts at line 820,
after the bounty block already ran.

#### Consequences

A three-or-more player party completing one run advances `CLEAR_HALLS` by
`members` times `cleared`, and `SPELUNKER`/`PACK_HUNTER` by one per member
instead of once, completing `PACK_HUNTER` (target 3) outright in a single
run. `SPEEDRUNNER` never advances at all, for any run size.

#### Fix

Move the entire `if (record.isKeystoneRun())` bounty block down, inside the
existing `if (firstCompletion)` block, placed after the
`completeDungeon(server, record);` call so `record.rewardChests` is
populated by the time `chests`/`timed` are computed.

#### Verification

Complete a timed keystone run solo. Confirm `SPEEDRUNNER` advances by 1.
Complete a run with 3 party members. Confirm `CLEAR_HALLS` advances by
`cleared` (not `cleared` times 3), and `PACK_HUNTER`/`SPELUNKER` each
advance by exactly 1, not 3.

---

### PD-16: Operator lootOverride writes an unclamped keystone level (High)

**Reported:** 2026-08-31
**Severity:** High (can permanently and irreversibly downgrade a player progression)
**Status:** Fixed (2026-08-31)

`/dungeon admin experiment` builds door 3 experimental offer from the
operator raw integer instead of routing it through the same clamp every
other offer uses, and the write path has no clamp of its own either.

#### Exact code references

`Keystone.java` lines 144-148:
```java
int expLevel = experimental.lootLevel() != null
        ? experimental.lootLevel() : KeystoneMath.upgrade(level, 3, max);
doorThree = new Offer(expLevel, experimental.affixes(), 3, experimental.theme(), Tier.EXPERIMENTAL);
```

`Keystones.java` line 107 (`grantOffer`) writes it unconditionally.
`DungeonLog.java` line 410 (`setKeystone`) only floors at zero despite its
own javadoc claiming the caller clamps. `DungeonCommands.java` line 1150
passes the brigadier integer through with no range validation.

#### Consequences

`/dungeon admin experiment <theme> "" 1` followed by a door-3 completion
takes any player, at any level, down to level 1 permanently. A value of `0`
reaches a state `Keystone.reconcile` explicitly refuses to repair.

#### Fix

Two clamps, both should land:

1. In `DungeonCommands.java` experiment command executor, validate the
   loot-level argument against 1 through `PocketDungeonsConfig.keystoneMaxLevel()`
   before constructing the `ExperimentalDungeon`, refusing with a clear
   message if out of range.
2. In `DungeonLog.setKeystone`, actually clamp: `keystoneLevel =
   Math.max(1, Math.min(level, PocketDungeonsConfig.keystoneMaxLevel()));`

#### Verification

Run `/dungeon admin experiment <theme> "" 0` and confirm it is refused. Run
it with a value above `keystoneMaxLevel` and confirm it clamps. Run it with
a valid value and confirm a door-3 completion grants exactly that level.

---

### PD-17: Reroll station can produce a strictly worse enchantment (High)

**Reported:** 2026-08-31
**Severity:** High (breaks the station documented guarantee, unintended Mending source)
**Status:** Fixed (2026-08-31)

The replacement pool for a reroll is the entire enchantment registry
filtered only by `isSupportedItem`, with no curse exclusion and no
mutually-exclusive-set check.

#### Exact code references

`RerollStation.java` lines 162-171:
```java
for (Identifier id : registry.keySet()) {
    Holder.Reference<Enchantment> candidate = registry.get(id).orElse(null);
    if (candidate == null || candidate.equals(chosen) || current.getLevel(candidate) > 0) { continue; }
    if (candidate.value().isSupportedItem(held)) { pool.add(candidate); }
}
```
`minecraft:vanishing_curse` supports every item; `minecraft:binding_curse`
supports every armor piece. Neither is excluded. `RerollMath.isValidReroll`
(`RerollMath.java:43`) only compares ids and counts, so it passes silently.
`RerollStation.java:44-50` and `RerollMath.java:36-42` both document the
opposite guarantee (never strictly worse).

#### Fix

Add an exclusion filter to the pool-building loop in `RerollStation.java`:

1. Exclude curses explicitly: skip any candidate whose id is
   `minecraft:vanishing_curse` or `minecraft:binding_curse`, or any
   enchantment in the curse tag if one exists on this MC version (verify
   against the jar).
2. Exclude mutually-exclusive pairs already present on `held`: build the
   set of enchantment exclusive-set tags already on the item and skip any
   candidate sharing one with an enchantment already on `current`.
3. Decide whether treasure-only enchantments (Mending, Soul Speed, Swift
   Sneak) should be reachable via reroll at all; if not, exclude
   `candidate.value().isTreasureOnly()`.

#### Verification

Reroll a Sharpness sword repeatedly and confirm Curse of Vanishing or
Binding never appear as a result. Reroll a Silk Touch tool and confirm
Fortune never appears (and vice versa). Confirm the pool is never empty for
a normal enchanted item.

---

### PD-18: Room directory admits a visitor into the owner live keystone run (High)

**Reported:** 2026-08-31
**Severity:** High (bypasses party cap, corrupts bounty and prestige counts)
**Status:** Fixed (2026-08-31)

`VisitService.findOwnedLiveRoom` matches any non-lingering, non-visit
record regardless of whether a keystone run is in progress. `statusOf`
correctly reports "run in progress" for this case, but its only caller
never checks it before admitting.

#### Exact code references

`VisitService.java` lines 85-92 (`findOwnedLiveRoom`, no run-state check),
lines 62-72 (`visit`, calls `Instances.admit` unconditionally), lines
215-222 (`statusOf`, has the correct check but nothing reads it for
gating). `DialogRouter.java` lines 366-381 (`visitRoom`), the only caller,
checks `publicListed()` and owner-online only.

#### Fix

In `VisitService.visit`, call `statusOf` first and refuse with the same
message it already builds, before calling `Instances.admit`.

#### Verification

Start a keystone run as player A. As player B, open the room directory and
attempt to visit A room while the run is active. Confirm B is refused with
a "run in progress" message and not teleported in. Confirm visiting still
works normally once A is back in their idle room.

---

### PD-19: Room-theme filter is inert, no shipped JSON supplies either half (High)

**Reported:** 2026-08-31
**Severity:** High (a built selection feature never actually filters)
**Status:** Fixed (2026-08-31); M42.2 paired four rooms (crypt_corner;
treasure_alcove, grove, mossy_tee) to five themes via `theme`/`room_theme`

The pipeline exists end to end in code, but no `dungeon_theme` JSON sets
`room_theme` and no `dungeon_room` JSON sets `theme`, so
`RoomManifest.matchesTheme` first branch always short-circuits true.

#### Exact code references

`Instances.java:645` passes `DungeonThemeMeta.roomTheme` into
`RoomSelector.pick`. `RoomSelector.java:78` calls
`manifest.queryAnyRotation(mask, role, theme)`. `RoomManifest.java:257-262`
(`matchesTheme`) is the dead filter. All five theme files and all fifteen
room files omit the relevant field.

#### Fix

This is a content decision, not a pure code fix: either wire real theming
(assign each room a `theme` and each theme a matching `room_theme`, e.g.
deepslate pulling `crypt_corner` preferentially) or remove the dead fields
and the filter code if room-per-theme variety is not wanted. The existing
`spawner_prefix: crypt` on deepslate alongside `crypt_corner.json` suggests
the former was intended. Track under M42 alongside PD-21 style content
items; needs a content-design decision, not just a code change.

#### Verification

Once content is added: generate several dungeons in a themed adventure and
confirm the room mix visibly differs by theme, not uniform across all five.

---

### PD-20: Voided-cell selection is not reproducible across JVM restarts (High)

**Reported:** 2026-08-31
**Severity:** High (breaks the same-seed-same-dungeon invariant)
**Status:** Fixed (2026-08-31)

The voided-cell pass draws one `nextDouble()` per cell while iterating
`plan.cells()`, a `Set.copyOf` whose iteration order is randomized per JVM
instance.

#### Exact code references

`LayoutStamper.java` lines 212-224:
```java
Random rng = new Random(plan.seed() ^ 0xB01DL);
for (PlanCell cell : plan.cells()) {
    if (cell.equals(entrance) || cell.equals(terminal)) continue;
    if (rng.nextDouble() < PocketDungeonsConfig.voidedCellChance()) voided.add(cell);
}
```
`DungeonPlan.cells` (`RoomSelector.java:99`) is `Set.copyOf(shape.cells())`.
Every other consumer of `plan.cells()` in the pipeline sorts first.

#### Fix

Iterate a sorted view instead: `geometry.cells()` (already sorted
elsewhere) or sort `plan.cells()` locally by a stable key before the loop.

#### Verification

Generate the same seed on two separate JVM runs and diff the resulting
voided-cell sets; they should match.

---

### PD-21: Routed dialog clicks act on a possibly disconnected player (High)

**Reported:** 2026-08-31
**Severity:** High (token consumed with no unlock granted, or world mutation for a gone player)
**Status:** Fixed (2026-08-31)

The custom-click mixin defers routing through `server.execute` but the
router performs no liveness check on the captured `ServerPlayer` before
acting on it.

#### Exact code references

`CustomClickMixin.java` lines 96-101 (captures `listener.player`, defers).
`DialogRouter.java` line 40 (`handle`, no `hasDisconnected()` check
anywhere in the method). Worst-case mutating call sites:
`DialogRouter.java:332` (`unlockShell`, shrinks held item then writes the
unlock by UUID), `:301` (`applyShell`, world mutation), `:163`
(`startDungeon`, teleports).

#### Fix

Add one guard at the top of `DialogRouter.handle`:
```java
static void handle(ServerPlayer player, ...) {
    if (player.hasDisconnected()) {
        return;
    }
}
```

#### Verification

Not easily reproducible on demand (timing-dependent). Confirm via code
review that the guard is present and unconditional at the top of `handle`.

---

### PD-22: Adventure graph validation is single-pass and order-dependent (High)

**Reported:** 2026-08-31
**Severity:** High (silently ships dangling theme transitions depending on hash order)
**Status:** Fixed (2026-08-31)

The validation loop iterates a snapshot of `nodes.entrySet()` while calling
`nodes.remove` and testing `containsKey` against the live map being
mutated in the same pass.

#### Exact code references

`AdventureGraphs.java` lines 60-76:
```java
for (Map.Entry<String, Node> entry : new ArrayList<>(nodes.entrySet())) {
    ...
    } else if (!nodes.containsKey(transition.theme())) { valid = false; }
    if (!valid) { nodes.remove(entry.getKey()); }
}
```

#### Fix

Replace the single pass with a fixpoint loop that repeats validation over
the current map until a full pass removes nothing, snapshotting the
valid-key set once per pass:

```java
boolean changed = true;
while (changed) {
    changed = false;
    Set<String> validKeys = Set.copyOf(nodes.keySet());
    for (Map.Entry<String, Node> entry : new ArrayList<>(nodes.entrySet())) {
        boolean valid = true;
        for (Transition transition : entry.getValue().transitions()) {
            if (!validKeys.contains(transition.theme())) { valid = false; break; }
        }
        if (!valid) {
            nodes.remove(entry.getKey());
            changed = true;
        }
    }
}
```

#### Verification

Add a three-node test pack to `AdventureGraphTest.java` where node A points
at node B, and node B points at a nonexistent theme. Assert the fixpoint
property directly: after `load`, every remaining node every transition
target exists in the remaining node set. Confirm both A and B are removed.

---

### PD-23: Two of three stations enforce no unlock level at use time (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (progression gate is cosmetic for 2 of 3 stations)
**Status:** Fixed (2026-08-31)

Only `RerollStation.onUse` checks its unlock level. `GambleStation.onUse`
and `CubeStation.onUse` check nothing; `gambleUnlockLevel` and
`cubeUnlockLevel` are referenced only by `StationPicker.java`, never at the
point of use.

#### Exact code references

`RerollStation.java:111-118` (has the check). `GambleStation.java:108-115`
and `CubeStation.java:119-133` (missing it). `StationPicker.java:87,98`
(the only readers of the two unlock-level knobs).

#### Fix

Add the same unlock-level check `RerollStation.onUse` already has to
`GambleStation.onUse` and `CubeStation.onUse`, reading
`PocketDungeonsConfig.gambleUnlockLevel()` and `cubeUnlockLevel()` against
the player keystone level, refusing with a message before proceeding.

#### Verification

At a low keystone level, place a beacon or cube block by any means and
attempt to use it. Confirm both are refused the same way the reroll
station already refuses.

---

### PD-24: Dialog actions bypass the station block and the level gate entirely (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (gate bypass, materials still spent so not free value)
**Status:** Fixed (2026-08-31)

`DialogRouter` validates only that the owner key matches the sender for the
reroll and imbue actions; there is no station-proximity check, and
`RerollStation.handleReroll` never re-checks the unlock level `onUse` does.

#### Exact code references

`DialogRouter.java:54-55` (owner-only check, no station or level check).
`RerollStation.java:137` (`handleReroll`, re-checks tier and lapis, not
unlock level).

#### Fix

In `RerollStation.handleReroll` (and the equivalent gamble and cube dialog
handlers once PD-23 adds their level checks), re-check the relevant unlock
level the same way `onUse` does.

#### Verification

Depends on PD-23 landing first for the gamble and cube half. For reroll: at
a low keystone level, confirm `handleReroll` refuses without a station
present.

---

### PD-25: Task progress is awarded for opening a station, not for using it (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (task can complete without doing the thing its label says)
**Status:** Fixed (2026-08-31)

The reroll and gamble stations fire `TaskTracker.progress` immediately
after showing their UI, before anything is charged.

#### Exact code references

`RerollStation.java:121` and `GambleStation.java:114`, both call
`TaskTracker.progress` right after `showPicker`/`openGui`, before any
spend. `TaskTracker.Task.GAMBLE` (`TaskTracker.java:37`) is labelled
"Spend Emeralds at Kadala" with target 16. Contrast `CubeStation.java:147`
(`EXTRACT_POWER` fired after `held.shrink(1)`) and
`RitualListener.java:182` (`FEED_ENGINE` fired after the spend), both
correct.

#### Fix

Move the `TaskTracker.progress` call in `RerollStation` to `handleReroll`,
after the lapis spend succeeds. Move the one in `GambleStation` to
`handleTrade`, after the emerald debit succeeds, not at `openGui` time.

#### Verification

Right-click a reroll or gamble station repeatedly with no lapis or
emeralds on hand. Confirm the task does not progress. Complete an actual
reroll or trade and confirm it does.

---

### PD-26: Death rescue detaches a member by hand and skips the leadership rule (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (owner death mid-run silently desyncs from their own party)
**Status:** Fixed (2026-08-31)

`Instances.rescue` open-codes member removal instead of routing through
`RunLifecycle.dropMember`, so `leadershipChanged` is never evaluated for
this path.

#### Exact code references

`Instances.java` lines 841-883 (`rescue`): does `record.members.remove`,
`byMember.remove`, `onPad.remove`, `timer.removePlayer`,
`clearTrialOmen` by hand, discarding the `ReturnPoint` at line 858. Compare
`RunLifecycle.dropMember` (lines 1114-1153), which performs the same set of
removals plus the leadership check at line 1173.

#### Fix

Replace the hand-rolled detach block in `rescue` with a call to
`RunLifecycle.dropMember`, then perform the rescue own teleport and
respawn handling after. Keep the `ReturnPoint` result rather than
discarding it.

#### Verification

As a party owner, die inside a shared run. Confirm the leadership-changed
behavior fires the same way it does for a voluntary exit or disconnect.

---

### PD-27: IRON_DOOR connector can gate the critical path with no redstone source in the mod (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (roughly 10 percent chance of an unopenable dead end on the golden path)
**Status:** Fixed (2026-08-31)

Only edges touching the entrance cell are excluded from the IRON_DOOR roll.
Every other edge, including the sole critical-path edge to the terminal,
can roll it, and nothing in the mod places a button, lever, or plate to
open it.

#### Exact code references

`LayoutStamper.java:249-278` (edge exclusion only covers the entrance).
`ConnectorStamper.java:64-66` comment says room content or the player
supplies it, but grepping `RoomContent`/`TrialContent` for anything placing
a redstone source near an IRON_DOOR connector returns nothing.

#### Fix

Pick one of two approaches:

1. Exclude the critical path from the IRON_DOOR roll, reusing whatever
   critical-path data the graph generator already computes for the
   loot-guarantee pass.
2. Have `ConnectorStamper.applyIronDoor` place a lever or button on the
   door frame itself, so the door is always self-openable regardless of
   which edge it lands on.

Prefer option 2 unless a lever on every iron door reads as visually
cluttered, in which case fall back to option 1.

#### Verification

Generate several hundred seeds and confirm no run requires redstone the
player cannot obtain in-dungeon to reach the terminal.

---

### PD-28: Iron door pair does not alternate hinge and uses the wrong facing convention (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (visual and consistency defect, not a blocker)
**Status:** Fixed (2026-08-31)

`ConnectorStamper.applyIronDoor` builds one door state and fills both leaf
positions with it, so both get the default LEFT hinge instead of
alternating like every other double door in the mod, and sets FACING
outward instead of the inward convention every other door uses.

#### Exact code references

`ConnectorStamper.java:74-79` (single `lower` state filled into both
`DOOR_MIN` and `DOOR_MAX`, `FACING = Instances.mcDirection(wall)`).
Compare `RoomTemplateGenerator.placePostSelectionDoors` (line 575,
alternates LEFT and RIGHT so the two doors meet in the middle), and
`placeSelectorDoors`/`leverState`/`signState`/`frameState` (all use the
opposite-of-wall facing convention).

#### Fix

In `applyIronDoor`, build two states, one LEFT hinge for the first leaf,
one RIGHT hinge for the second, matching `placePostSelectionDoors`
pattern. Change FACING to `CellGeometry.opposite(wall)` instead of `wall`
directly.

#### Verification

Generate a dungeon with an iron-door connector and inspect it visually:
the two leaves should meet in the middle, and the door should face the
same direction every other door in an equivalent position faces.

---

### PD-29: manifest reload command reloads one of five manifests (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (operator tooling lies about what it did)
**Status:** Fixed (2026-08-31)

Found independently by two passes of the audit. Startup loads five
manifests; the reload command touches only rooms.

#### Exact code references

`Instances.java:217-226` (`SERVER_STARTED`, loads all five: themes,
adventure graphs, diaries, rooms, anomaly rooms). `DungeonCommands.java`
lines 1167-1182 (`manifestReload`) calls only `RoomManifest.load(server)`
and reports "Loaded N room(s) into manifest." as if everything reloaded.
Three other commands tell operators to run this command as the fix for
stale state it cannot actually clear for themes, adventure, diaries, or
anomaly.

#### Fix

Call all five loaders in `manifestReload`: `ThemeManifest.load(server)`,
`AdventureGraphs.load(server)`, `Diaries.load(server)` (needs PD-30 landed
alongside), `RoomManifest.load(server)`, `RoomManifest.loadAnomaly(server)`.
Update the success message to name all five counts.

#### Verification

Edit a theme JSON and an anomaly room JSON on disk. Run
`/dungeon admin manifest reload`. Confirm both changes take effect without
a server restart.

---

### PD-30: Diaries never reload on /reload (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (operator content-editing workflow silently requires a restart)
**Status:** Fixed (2026-08-31)

`Diaries.load` has exactly one call site, inside `SERVER_STARTED`. The
theme and room manifests both register a datapack-reload listener;
diaries do not.

#### Exact code references

`Instances.java:219` (the sole call site). `ThemeManifest.java:50-51` and
`RoomManifest.java:100-101` show the pattern to follow. `Diaries.rejections()`
exists but is never called by any command, unlike the room and graph
equivalents.

#### Fix

Register `Diaries.load` the same way `ThemeManifest`/`RoomManifest`
register theirs, against the datapack reload listener (verify the exact
API against the jar). Wire `Diaries.rejections()` into the same admin
diagnostic command that surfaces room and graph rejections, for parity.

#### Verification

Edit a diary entry JSON. Run `/reload` or the manifest reload command from
PD-29. Confirm the edited diary content is served without a restart.

---

### PD-31: infestation theme has no adventure node and is unreachable (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (authored content behind an unreachable theme)
**Status:** Fixed (2026-08-31); M42.3 authored
`dungeon_adventure/infestation.json` as a descent node reachable from
both entry themes

Five theme files ship; only four adventure files exist. `infestation` has
no node, so `AdventureGraph.pick` can never offer it, and its six
trial-spawner configs are unreachable through normal play.

#### Exact code references

`dungeon_theme/infestation.json` exists; `dungeon_adventure/` has
blackstone, deepslate, drowned_vault, prismarine only.
`DungeonCommands.java:1140-1147` (`admin experiment`) deliberately does not
validate the theme id, so it is the only path in.

#### Fix

Content work: author `dungeon_adventure/infestation.json` following the
shape of the four existing files, deciding where in the adventure graph it
should sit. This is a content-design decision, not a code change; track
under M42 alongside PD-19.

#### Verification

Once added: play through the adventure graph enough times to confirm
infestation is reachable as a normal door offer, not only via the admin
command.

---

### PD-32: Themed loot table suffix never resolves on an ominous run (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (the boss theme themed loot is likely never seen)
**Status:** Fixed (2026-08-31); M42.4 added six small loot tables that
reference the base ominous table via a `minecraft:loot_table` entry and
layer a themed bonus pool on top, resolved automatically by the
existing suffix-fallback in `resolveLootTable`

`TrialContent.resolveLootTable` appends the theme `loot_suffix` to an
already-decorated ominous path, which does not exist as a shipped table,
so it silently falls back to the untheme'd ominous table.

#### Exact code references

`TrialContent.java:485-495`. `dungeon_theme/drowned_vault.json` is the
only theme with `loot_suffix` set. Ominous runs ask for a table that does
not exist; only the base and plain-ominous tables ship.

#### Fix

Either author the missing themed-ominous loot tables, or change
`resolveLootTable` composition order so a themed table and the ominous
modifier can combine without a full cross-product of tables. The latter
scales better if more themed suffixes are added later; note under M42 as a
design choice.

#### Verification

Run an ominous drowned_vault dungeon and confirm the chest and vault loot
includes the theme intended flavor, not a silent fallback to generic
ominous loot.

---

### PD-33: Cube extraction consumes the item before writing the state (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (rare-item destruction on a crash between two lines)
**Status:** Fixed (2026-08-31)

`CubeStation` extract path shrinks the held stack before recording the
extraction in `DungeonLog`.

#### Exact code references

`CubeStation.java:143-144`:
```java
held.shrink(1);
log.addExtractedPower(owner, reward);
```

#### Fix

Swap the order:
```java
log.addExtractedPower(owner, reward);
held.shrink(1);
```

#### Verification

Code review is sufficient; confirm the two lines are swapped and no other
consumer of `held` runs between them.

---

### PD-34: Deferred room save in eject races the clear that purge just queued (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (a room save can be silently overwritten by a stale deferred write)
**Status:** Fixed (2026-08-31)

`InstanceTeardown.purge` saves the room synchronously specifically to beat
its own queued `PendingClear`, then calls `Instances.eject`, whose
`saveRoomIfOwner` defers through `server.execute` and can fire on a later
tick while the clear is actively blanking the same cell.

#### Exact code references

`InstanceTeardown.java:122-125` (the synchronous save), line 129 (calls
`Instances.eject` per member), line 161 (queues the `PendingClear` last).
`RunLifecycle.java:565-570` (`saveRoomIfOwner`, deferred). It currently
survives only because the room cell happens to be the last thing cleared
in the sequence.

#### Fix

In `Instances.eject`, when called from within `purge` (the room has
already been synchronously saved and a clear is already queued), skip the
deferred save. The cleanest way: `purge` should null out
`record.roomCellOrigin` after its synchronous save, the same way
`adminPurgeByOwner` (`Instances.java:1690`) already does, so
`saveRoomIfOwner` own existing null-check guard makes the deferred call a
no-op naturally.

#### Verification

Decorate a room, then trigger an admin purge. Confirm the room saved state
matches what it looked like right before the purge, not a stale or
partially-cleared version from a deferred write racing the clear.

---

### PD-35: Lobby directory is unbounded, contradicting the suite own pagination convention (Medium)

**Reported:** 2026-08-31
**Severity:** Medium (a large server can produce an oversized single-packet dialog)
**Status:** Fixed (2026-08-31)

`DialogScreens.lobbyBrowser` builds one button per public online room with
no cap, in a dialog type that ships whole in one packet. The project own
dialog spec names an existing convention (cap at a fixed count, drop the
rest to chat) that this screen does not follow.

#### Exact code references

`DialogScreens.java:499-509`. `docs/DIALOGS_SPEC.md:414-415` names
Ballot's own settings dialog, capped at 8, as the convention to follow.

#### Fix

Cap the row count (8, matching the cited convention) and append a text
line below the cap listing how many more rooms exist. The zero-row case is
already handled correctly and does not need to change.

#### Verification

Populate more than the cap worth of public listed rooms (test server or a
temporary lowered cap) and confirm the dialog renders the capped list plus
an overflow indicator instead of growing unbounded.

---

### PD-36: admin experiment echoes the raw affix string, not what was actually applied (Low)

**Reported:** 2026-08-31
**Severity:** Low (misleading operator feedback only)
**Status:** Fixed (2026-08-31)

`AffixMath.parse` silently drops unrecognized tokens; the command success
message echoes the operator original input string regardless.

#### Exact code references

`DungeonCommands.java:1150-1156`. `AffixMath.parse` (`AffixMath.java:70-88`,
silent drop). `AffixMath.join` (`AffixMath.java:90`) already exists and is
unused here.

#### Fix

Replace the echoed raw string with `AffixMath.join(AffixMath.parse(affixes))`
in the success message.

#### Verification

Run `/dungeon admin experiment <theme> "FERAL,NOTAREALAFFIX"` and confirm
the confirmation message shows only FERAL.

---

### PD-37: Room names are unvalidated legacy-formatting text shown to other players (Low)

**Reported:** 2026-08-31
**Severity:** Low (cosmetic griefing via obfuscated or formatted room names)
**Status:** Fixed (2026-08-31)

Both the command and the routed room-rename path trim and truncate to 16
characters and do nothing else; the value renders as a literal component
with legacy format codes active.

#### Exact code references

`DungeonCommands.java:623-632`, `DialogRouter.java:228-234` (both paths).
`DialogScreens.java:423` (`LobbyRow.label()`, renders as literal).

#### Fix

Strip the format-code prefix character from the input before storing it,
or restrict the accepted character set to alphanumerics, spaces, and a
small punctuation allowlist.

#### Verification

Attempt to set a room name containing an obfuscation or formatting code.
Confirm the stored name has the codes stripped and renders as plain text.

---

### PD-38: Player-facing commands return success unconditionally (Low)

**Reported:** 2026-08-31
**Severity:** Low (command-block integration only, chat text is correct)
**Status:** Fixed (2026-08-31)

`exit` and the six party subcommands return 1 even when the underlying
service call refused the action.

#### Exact code references

`DungeonCommands.java:434-438` (`exit`), lines 508, 513, 518, 523, 528, 539
(party subcommands). All call into void-returning service methods that can
refuse internally.

#### Fix

Have the relevant service methods return a boolean instead of void, and
have each command executor return 1 or 0 based on that result.

#### Verification

Use execute-if wrapping one of these commands in a scenario where it
should refuse and confirm the branch now correctly treats it as failed.

---

### PD-39: stamptest force-loads chunks and never releases the tickets (Low)

**Reported:** 2026-08-31
**Severity:** Low (dev-only command, same root cause as PD-13)
**Status:** Fixed (2026-08-31)

`DungeonCommands.java:803-806` force-loads chunks in a loop with no
matching release; the command own closing message tells the operator to
purge by hand, which does not cover the tickets.

#### Fix

Add a matching release loop, either as part of the purge-by-hand cleanup
this dev tool already provides, or automatically when the test structures
are torn down.

#### Verification

Run stamptest several times in a dev environment and confirm the server
forced-chunk count does not climb across invocations.

---

### PD-40: Gamble draw silently discards every stack past the first (Low)

**Reported:** 2026-08-31
**Severity:** Low (silent today, becomes a real loot-loss bug once a second pool is authored)
**Status:** Fixed (2026-08-31)

`GambleStation.draw` takes only the first entry from the loot table result
with no loop.

#### Exact code references

`GambleStation.java:314-315`.

#### Fix

Loop over the result and hand back the full list, or keep single-item by
design and add a log warning when more than one stack rolls, so a future
table author notices the truncation.

#### Verification

Temporarily author a gear table with two rolls and confirm both items are
delivered, or confirm the warning log fires if keeping single-item.

---

### PD-41: Keystone reconcile discards stack count on a stackable configured item (Low)

**Reported:** 2026-08-31
**Severity:** Low (harmless with the default item, destructive if reconfigured)
**Status:** Fixed (2026-08-31)

`Keystone.mint` always returns a count-1 stack; `reconcile` replaces the
existing stack with it wholesale, discarding any larger count.

#### Exact code references

`Keystone.java:291-303` (the replace). The configured keystone item is a
free-form config string with no max-stack validation.

#### Fix

Either validate at config-load time that the configured keystone item has
a max stack size of 1, or change `reconcile` to preserve the existing
stack count when minting the replacement.

#### Verification

Temporarily configure the keystone item to a stackable item, stack several
in an inventory slot, and trigger a reconcile. Confirm either the config is
refused at load, or the stack count survives the reconcile.

---

### PD-42: Integer overflow in PayoutMath chestCount (Low)

**Reported:** 2026-08-31
**Severity:** Low (requires a misconfigured huge timer to trigger)
**Status:** Fixed (2026-08-31)

`secondsRemaining * 100` overflows int past roughly 21.4 million seconds.

#### Exact code references

`PayoutMath.java:33`. `KeystoneMath.timerSeconds` deliberately computes in
long and clamps to Integer.MAX_VALUE, so the one guard against a huge
timer config feeds a value this line cannot hold safely.

#### Fix

```java
int usedPercent = 100 - (int) ((long) secondsRemaining * 100 / Math.max(1, totalSeconds));
```

#### Verification

Add an assertion in `PayoutMathTest.java` with a large `secondsRemaining`
and confirm `chestCount` returns a sane, non-negative result instead of a
value derived from overflow.

---

### PD-43: Attribute listener maps grow without a disconnect hook (Low)

**Reported:** 2026-08-31
**Severity:** Low (self-healing on relog, grows for the process lifetime)
**Status:** Fixed (2026-08-31)

`TrimListener` and `PowerListener` each keep a static per-UUID map
populated by `computeIfAbsent` in their reconcile pass, with no removal
anywhere.

#### Exact code references

`TrimListener.java:79`, `PowerListener.java:68`. No remove or clear call
and no disconnect registration in either file.

#### Fix

Register a disconnect handler in both files (or one shared handler if the
station-shape refactor in M43 lands first) that removes the disconnecting
player UUID from the map.

#### Verification

Log the map size periodically in a dev environment across many connect and
disconnect cycles and confirm it stops growing once the fix lands.

---

### PD-44: pendingReturns never expires (Low)

**Reported:** 2026-08-31
**Severity:** Low (bounded by unique players, unbounded in time)
**Status:** Fixed (2026-08-31)

Entries are added on disconnect-inside-a-dungeon and removed only by
admit or a rejoin landing in the dungeon dimension. A player who
disconnects and never returns leaves an entry forever.

#### Exact code references

`Instances.java:65` (the map), line 166 (add), lines 342 and 188 (remove
sites).

#### Fix

Add a timestamp to each entry and sweep expired ones during the existing
tick watcher pass.

#### Verification

Not easily testable headlessly; code review plus a manual long-session
check that the map size is bounded is sufficient.

---

### PD-45: Config accepts inviteTtlSeconds of 0, silently disabling invites and kick confirmations (Low)

**Reported:** 2026-08-31
**Severity:** Low (misconfiguration footgun, not reachable with default config)
**Status:** Fixed (2026-08-31)

Validated at 0 or above; both consumers compute an expiry from the current
time plus the ttl, so a value of 0 makes the window sub-millisecond in
practice.

#### Exact code references

`PocketDungeonsConfig.java:685` (validation). `PartyService.java:174,280`
(both consumers).

#### Fix

Change the validation lower bound to at least 1.

#### Verification

Set the config to 0, restart, and confirm the loader now clamps it rather
than silently accepting it.

---

### PD-46: pathLengthMax is never cross-checked against maxGridSpan (Low)

**Reported:** 2026-08-31
**Severity:** Low (misconfiguration causes every seed to fall back to the static layout forever)
**Status:** Fixed (2026-08-31)

Both are validated independently. A path length that cannot fit the grid
span fails `RoomSelector.validate` for every seed with no diagnostic,
silently falling back to StaticLayout.

#### Exact code references

`PocketDungeonsConfig.java:688,700`. The file already does this kind of
cross-field repair for the `pathLengthMin`/`pathLengthMax` pair and the
chest-percent pair; this pair was missed.

#### Fix

Add a cross-check in the same validation pass: if `pathLengthMax` cannot
fit within `maxGridSpan`, clamp it down and log a warning, matching the
existing pattern.

#### Verification

Set `pathLengthMax` far beyond what `maxGridSpan` can support, restart, and
confirm the config loader clamps and warns.

---

### PD-47: keystoneMaxLevel is never cross-checked against the level gates it caps (Low)

**Reported:** 2026-08-31
**Severity:** Low (misconfiguration silently makes late-game features unreachable)
**Status:** Fixed (2026-08-31)

`keystoneMaxLevel` bounds every player achievable level. Setting it below
`greaterDoorMinLevel` (15), `cubeUnlockLevel` (15), `gambleUnlockLevel`
(10), or `rerollUnlockLevel` (5) makes those features permanently
unreachable with no log line.

#### Fix

Add a validation-time check: if `keystoneMaxLevel` is below any of the four
named gate values, log a warning naming which gate is now unreachable.

#### Verification

Set `keystoneMaxLevel` below `rerollUnlockLevel`, restart, and confirm a
warning is logged naming the unreachable feature.

---

### PD-48: Fuel matches by item type only and destroys custom-data items from other mods (Critical)

**Reported:** 2026-08-31
**Severity:** Critical
**Status:** Fixed (2026-08-31)

`Fuel.isFuel`, `count`, and `spend` all test the item type alone with
`stack.is(item)`, ignoring custom data.

#### Exact code references

`Fuel.java:56` (`isFuel`), and the identical type-only test used by
`count` and `spend`. Confirmed by grep: the default fuel item (echo
shards) appears in 13 loot entries across 7 tables including
`chests/anomaly.json` (pool 0, unconditional 1-3 shards, no chance
condition) and `chests/pocket2.json`. Ominous chest and vault tables
re-skin echo shards as kamutotems "Boss Stone II/III" via
`set_custom_data` and `set_name` (`chests/tier_2_ominous.json:426,457`,
`chests/tier_3_ominous.json:458`, `vaults/tier_1_ominous.json:132,163`,
`vaults/tier_2_ominous.json:189,220,251`, `vaults/tier_3_ominous.json:185,216`).

The `Fuel` javadoc states "Door 1 is the only source. Nothing else in this
mod loot tables grants the fuel item," citing the M12 self-funding risk
the design deliberately avoided. That claim is false against the shipped
data.

Related dead config: `ritualKeyItem` in the default config is also set to
the same echo-shard item and is read by zero Java code anywhere in the
tree.

#### Fix

Three parts:

1. Stop matching by type alone. `Fuel.isFuel`, `count`, and `spend` should
   also require the stack carry the mod own marker (the same
   custom-data-tag pattern the three stations already use for tier tags),
   set when fuel is granted via `Fuel.grant`, and required for a stack to
   count as spendable fuel. This also protects against any future
   echo-shard-based item another mod adds.
2. Remove echo shards from the mod own loot tables, or replace them with a
   different item, so the "Door 1 is the only source" invariant holds.
   Fix `chests/anomaly.json` pool 0 and `chests/pocket2.json` specifically;
   confirm no other loot table grants the configured fuel item.
3. Remove the dead `ritualKeyItem` and `ritualKeyCount` keys from the
   default config, since no code reads them.

#### Verification

Obtain a kamutotems Boss Stone via the ominous loot path and attempt to
bank it at an engine terminal. Confirm it is refused. Confirm normal echo
shards from `Fuel.grant` still bank and spend correctly. Confirm no
dungeon loot table grants a plain echo shard anymore.

---

## Found while working M43 (2026-08-31)

### PD-49: `timedOutPenaltyApplied` still not reset between runs behind the same lobby (High)

**Reported:** 2026-08-31
**Severity:** High (timeout economy bypass, false-positive grace-window
arming; the original audit found this as its own finding, but it was
dropped during BUGS.md consolidation and never actually got a fix
landed in M36-M39 despite the record showing otherwise)
**Status:** Fixed (2026-08-31)

`Instances.generateBehindLobby`'s per-run reset block clears
`completed`, `keystoneReturned`, `onPad`, `visited`, `clearedCells`,
`rewardChests`, and `expiresAtTick`, but not `timedOutPenaltyApplied`.
Surfaced again while starting M43.1's `InstanceRecord` refactor,
which is exactly the kind of gap that refactor is meant to make
structurally impossible.

#### Exact code references

`Instances.java`, the reset block inside `generateBehindLobby` (the
block clearing `record.completed`, `record.keystoneReturned`, etc.):
`timedOutPenaltyApplied` is absent from it. Consumers:
`Instances.java` (the timeout-expiry check and the reward-room grace
arming check, both gated on this field) and `RunLifecycle.java` (the
late-finish downgrade check).

#### Consequences

Any second run behind the same lobby after a first run that timed out
skips its own timeout penalty entirely (the guard
`!record.timedOutPenaltyApplied` is already false), arms the
reward-room grace countdown on the very first watcher tick of the new
run (a second check keys off exactly this stale flag), and downgrades
a genuinely late finish from `LATE` to `NO_CHANGE`.

#### Fix

Added `record.timedOutPenaltyApplied = false;` to the reset block,
alongside the other per-run fields it already clears.

#### Verification

Let a run time out behind a lobby (timeout penalty applies, existing
behavior). Start a second run behind the same lobby. Let it finish
normally within time. Confirm no timeout penalty applies to the second
run and the reward-room grace window does not arm prematurely on its
first tick.

---

## Found live testing the M45 through M49 situations round (2026-09-03)

### PD-50: Void inventory is dropped on the ground on exit, and not restored on re-entry (Critical)

**Reported:** 2026-09-03
**Severity:** Critical (the situations round's whole stash-and-swap
design exists to make exactly this not happen)
**Status:** Fixed (2026-09-03)

Reported from live play. Two symptoms, observed together:

1. Entering `pocketdungeons:void` correctly stashes the overworld
   inventory (the survival side of the M46 swap appears to work).
2. On leaving the void, the inventory the player was carrying **inside**
   the dungeon is dropped on the ground at the teleport-back position
   instead of being delivered to the room, and the player's *survival*
   inventory is restored correctly at that point.
3. On the next entry into the void, the player starts with only the
   keystone compass. Whatever the player had picked up or been given
   inside the previous dungeon session is gone: not in the dropped
   pile from step 2, not carried forward.

Net effect: the void-side inventory is not being persisted, delivered,
or regenerated at all. It behaves as if `InventorySwap`'s leaving
branch is dropping the void snapshot on the ground (a `deliverToRoom`
fallback path, going by `RunLifecycle.java`'s new M46 code) instead of
routing it into the room's containers, and the entering branch always
treats the player as unstashed for the void side rather than restoring
a previous void state.

Root cause: symptom 2 exactly, confirmed by reading the code.
`Instances.detach` (the one primitive every exit path routes through:
`eject`, `dropMember`, both `InstanceTeardown` purge paths) removes
the player from `InstanceRegistry.byMember` *before* the teleport that
triggers `InventorySwap`'s leaving branch. By the time
`RunLifecycle.deliverVoidInventory` looked the room up to deliver items
to, the record was already gone (`InstanceRegistry.byMember.get(...)`
returned `null`), so it fell straight through to the "drop at the
player's feet" branch on every single exit. Symptom 3 (reset to just the
keystone on re-entry) turned out to be intended per spec 11.6, which
deliberately does not apply a bag on entry; it only read as a bug
because the delivery in symptom 2 was silently failing every time.

#### Fix

`Instances.detach` now caches `record.roomCellOrigin` into a new
consume-once map (`Instances.lastRoomCellOrigin` /
`consumeLastRoomCellOrigin`) before clearing `byMember`.
`RunLifecycle.deliverVoidInventory` falls back to that cache when the
live record is already gone, which is the common case on every normal
exit path. The cache entry is read once and removed, so a stale value
cannot outlive the one delivery it was written for.

#### Verification

Full test suite green after the change (`./gradlew build --offline`).
No dedicated regression test added: the fix is two small additions to
existing methods with no new pure-logic surface to unit test against,
so this is a live-play item. Walk: enter a dungeon, pick up a chest item,
leave via the pad/`/dungeon exit`/the "Leave" menu option, confirm the
item lands in the room's containers (or on the floor if the room has no
container, or at the player's feet only as the last resort) rather than
on the ground at the teleport-out position; re-enter and confirm the
keystone-only start is now the only thing left (expected, not a bug).

---

### PD-51: Station blocks are usable-gated but not obtainably-gated (Medium)

**Reported:** 2026-09-03
**Severity:** Medium (unlock progression can be bypassed for stations
the player should not have yet, though the block does nothing useful
until the level check passes)
**Status:** Fixed (2026-09-03)

Reported from live play as "stations require Tier 1 or something but
shouldn't even be obtainable until unlocked."

Confirmed while reading the code (not yet confirmed live): the picker
GUI itself gates correctly. `StationPicker.stationElement` only wires a
"click to take" callback onto the `GuiElementBuilder` when `unlocked`
is true (`StationPicker.java` around line 138); a locked station shows
only the "Unlocks at keystone level N" lore line and no callback, so
the picker cannot hand out a station item early.

Root cause, confirmed: not a placement-gating gap at all. Stations are
deliberately plain vanilla blocks with no capture hygiene
(`StationPicker`'s own javadoc: "player places them wherever they
want... no capture hygiene"), so obtaining and placing the block was
never meant to be restricted to the picker, and gating placement of a
plain smithing table would have blocked unrelated vanilla use of the
same block. The real gap: `GambleStation.onUse` (right-clicking the
block) checks `gambleUnlockLevel` via `StationSupport.levelTooLow`, with
a comment describing this exact bug class from an earlier fix (PD-23).
But `BlacksmithNPC`'s villager right-click handler called
`GambleStation.openGui` directly, bypassing that check entirely --
anyone, at any keystone level, could trade at the blacksmith. Its trade
tier scaling (`KeystoneMath.lootTier(Math.max(1, level))`) already
shows real Tier 1 gear even at level 0, which is what the "Tier 1"
wording in the original report was describing.

#### Fix

`GambleStation.openGui` now performs the `gambleUnlockLevel` check
itself (and returns `boolean`, whether it actually opened) instead of
trusting each caller to have already checked. `onUse` simplified to just
call it. `BlacksmithNPC` now checks the return value before crediting
`TaskTracker.Task.GAMBLE` progress, so an under-level player gets the
refusal message and no progress credit instead of a trade screen the
block itself would have refused them. One gate, checked once, cannot be
forgotten by a future third caller.

#### Verification

Full test suite green after the change. Live item: right-click the
blacksmith villager below `gambleUnlockLevel`, confirm the refusal
message instead of the trade screen, and confirm trading still works
normally once past the level.

---

### PD-52: The blacksmith villager can open the room door and let dungeon mobs in (Medium-High)

**Reported:** 2026-09-03
**Severity:** Medium-High (breaches the room's safety, the same class
of problem M25/M27 class fixes exist for elsewhere in this mod)
**Status:** Fixed (2026-09-03)

`BlacksmithNPC` spawns a plain vanilla `Villager` (`EntityTypes.VILLAGER`,
`BlacksmithNPC.java` register/spawn path) with no AI goals stripped and
no `setNoAi(true)`; the class javadoc says this is deliberate, to get
"wanders near the smithing table" wandering behavior for free. The same
javadoc already admits the villager "can still drift out of the room
through an open door" and relies on a periodic (`SWEEP_INTERVAL_TICKS`,
every 5 seconds) tether that only pulls it back after it has wandered
more than 8 blocks from the smithing table.

What that javadoc does not account for: an unmodified villager will
open a wooden door itself while pathing, not just wander through one
already open. The original theory here named `OpenDoorGoal` (the old
goal-selector mechanism some other mobs use); verified against the
26.2 jar this is wrong for a modern villager, whose door interaction is
brain-driven (`net.minecraft.world.entity.ai.behavior.InteractWithDoor`,
wired in via `VillagerGoalPackages`), not a `Goal` at all, so stripping
goals would not have touched it. If the blacksmith's smithing table is
anywhere near the door between the player's room and the dungeon/lobby
side, the villager's own AI can open that door, and the 5-second/8-block
tether does nothing to stop the door from staying open once opened. This
would let dungeon monsters wander into the safe room independently of
whether the villager itself ever leaves.

#### Fix

`Brain` has no fine-grained per-behavior removal API
(`removeAllBehaviors()` is all-or-nothing and would have killed the
wandering AI this class deliberately keeps), so stripping
`InteractWithDoor` out of the brain was not the way in. The actual
vanilla switch, verified in the jar: `PathNavigation.setCanOpenDoors(boolean)`,
which `InteractWithDoor` itself checks before acting.
`BlacksmithNPC.spawnBlacksmith` now calls
`villager.getNavigation().setCanOpenDoors(false)` once, at spawn.
Surgical: does not touch the goal selector, the brain, or the wandering
AI the class exists to keep.

#### Verification

Full test suite green after the change. Live item: place a smithing
table near the door between the room and the dungeon/lobby side, let
the blacksmith spawn, wait past a sweep interval, and confirm the door
stays closed even if the villager paths toward it (a player or another
mob opening the door and the blacksmith walking through the opening is
still expected and out of scope, per the class's own javadoc).

---

### PD-53: `/dungeon admin resetroom` leaves the player with an unselectable stone brick shell (Low)

**Reported:** 2026-09-03
**Severity:** Low (cosmetic/quality-of-life; the reset itself works as
designed)
**Status:** Fixed (2026-09-03). User confirmed the reset behavior itself
is fine; the ask was to also make the reset shell selectable, not to
change what resetroom resets to.

`/dungeon admin resetroom` (`DungeonCommands.resetRoom`) wipes the
player's saved room via `RoomStore.reset` and, the next time they open
a lobby, `RoomBuilder.buildShell` stamps a fresh room. `buildShell`
hardcodes its palette as `new ShellPalette("built_in", "Stone Brick",
null, floor, WALL, CEILING, CEILING_SLAB, STAIR)` (`RoomBuilder.java`,
`buildShell`), a `ShellPalette` value built inline and never entered
into `RoomBuilder.SHELL_PALETTES` (the `Map.of(OAK.name(), OAK,
SANDSTONE.name(), SANDSTONE, DEEPSLATE.name(), DEEPSLATE,
NETHER_BRICK.name(), NETHER_BRICK, ALEXS_ROOM.name(), ALEXS_ROOM)` table
that backs the M24 shell-selection system, `unlockedShells`, and
whatever menu offers "Apply Oak" etc.). Because `"built_in"` is not a
key in that map, the stone brick shell the player starts with (and
lands back on after a reset) cannot be re-selected once they switch to
another unlocked palette; it is only ever reachable by being a fresh
room's default or a post-reset default, never by player choice.

#### Fix

Added `RoomBuilder.STONE_BRICK`, a real `ShellPalette` entry built
directly from the existing `FLOOR`/`WALL`/`CEILING`/`CEILING_SLAB`/`STAIR`
constants so it keeps its exact original look (polished andesite floor
under stone brick walls, not homogenized to one material the way the
uniform `palette(...)` helper would). Entered into `SHELL_PALETTES` and
`shellOrder()`. `buildShell` now builds off this shared constant instead
of an inline, unregistered `ShellPalette`. Made it free like `OAK`
(`isDefaultShell` now returns true for both): every room already starts
on it without unlocking anything, so treating it as locked would have
been a regression relative to today, not neutral.

#### Verification

Full test suite green after the change, including updated
`ShellPaletteTest` and `RoomShellTest` assertions covering the new
palette (six shipped palettes, `stone_brick` free and resolvable, menu
order, and that its floor/wall materials match `buildShell`'s originals
exactly). Live item: open the shell picker on a fresh or reset room,
confirm "Apply Stone Brick" appears and is not locked, and confirm
applying another shell then switching back to Stone Brick reproduces
the original look.

---

## Found live testing after the situations round's wave 1 (2026-09-03)

### PD-54: Chests and vaults yield far more items than intended, and vaults carry a resources pool that should be chest-only (Medium)

**Reported:** 2026-09-03
**Severity:** Medium (economy/balance, not correctness; every roll is
individually well formed, there are just too many of them)
**Status:** Fixed (2026-09-03), user-directed and confirmed a second
time live before the fix landed ("still way to many items... should be
1 or 2 instead of the near full chest slots having items").

Reported from live play: "getting too many items from chests and
vaults, probably just 1 or 2 items/stacks per chest/vault, vaults give
gear or emeralds, chests give utility items and resources."

Confirmed by counting pools (`grep -c '"rolls"'`) across every table
under `loot_table/chests/` and `loot_table/vaults/`. Datapack loot
tables execute *every pool*, and none of these pools are mutually
exclusive alternatives inside one pool the way "1 or 2 items" implies;
each pool with no `conditions` block (the common case here) always
fires, and each fired pool yields one roll's worth of items (or more,
where `rolls` is itself a range) independent of every other pool. Pool
counts, one open per chest/vault:

| Table | Pools | Table | Pools |
|---|---|---|---|
| `chests/tier_1` | 13 | `vaults/tier_1` | 5 |
| `chests/tier_1_ominous` | 14 | `vaults/tier_1_ominous` | 6 |
| `chests/tier_2` | 15 | `vaults/tier_2` | 6 |
| `chests/tier_2_ominous` | 17 | `vaults/tier_2_ominous` | 7 |
| `chests/tier_3` | 15 | `vaults/tier_3` | 6 |
| `chests/tier_3_ominous` | 17 | `vaults/tier_3_ominous` | 7 |
| `chests/supply_tier_*` | 9 each | | |

The `_drowned` and `_ominous_drowned` variants run much leaner (2 to 8),
which reads as a structural gap rather than a design choice: they look
like supplemental/reference tables layered on top of the base tables
(the M49 completion report flagged the four `*_ominous_drowned` tables
by name as ones the stale `tools/gen_vault_tables.py` script chokes on
for the same reason), so their low counts are likely an accident of
being written differently, not evidence the base tables' counts are
intentional.

Reading `chests/tier_1.json` pool by pool shows most of these predate
the M49 situations-round loot rework entirely: food, torches, bone,
building blocks, a sapling, seeds/vegetables, a diamond pool, a
mining-resources pool that itself rolls 2 to 3 items, an
arrow-or-golden-apple pool, and a gear pool, stacked as separate
always-firing pools rather than weighted entries inside one or two
pools. This is a long-accumulated pattern across many milestones, not a
single regression, though M49 did make it measurably worse for two
specific reasons:

1. **M49 added a "tools" pool to every gated and supply chest, at two to
   three rolls for gated chests** (per M49's own completion report,
   commit d99811d), on top of every pool that was already there,
   compounding rather than replacing the existing count problem.
2. **M49 also added that same tools pool to every vault**
   (`water_bucket`, `stone`, `snowball`, `lead`, `shears`,
   `golden_boots`, `oak_boat`, `trial_key`: confirmed by reading
   `vaults/tier_1.json` directly), which is exactly the "utility items
   and resources" category the user says belongs in chests only.
   Vaults today mix a diamond pool, a mining-resources-and-gear pool, a
   gear (armor/weapon) pool, an armor trim pool, *and* this tools pool,
   not "gear or emeralds" alone.

#### Fix

Every base and `_drowned` table (13 files under `loot_table/chests/`,
9 under `loot_table/vaults/`) collapsed from its many always-firing
pools into a single pool with `rolls: {min: 1, max: 2}`, every existing
entry (weight, `functions`, everything) carried over unchanged as a
weighted alternative inside that one pool. On vaults, ten specific
utility item names (`water_bucket`, `stone` and its three per-theme
block reskins `prismarine_bricks`/`dark_prismarine`/`oxidized_cut_copper`,
`snowball`, `lead`, `shears`, `golden_boots`, `oak_boat`) were dropped
outright rather than carried over, so a vault is left with only its
gear, mining-resource/currency, and armor-trim pools, per "vaults give
gear or emeralds." Chests kept every entry, just fewer total rolls of
them, since utility items and resources are exactly what belongs there.

The six `*_ominous_drowned` reference tables (three chest, three vault)
were left untouched: each is a `minecraft:loot_table` reference to its
base table plus one or two of its own bonus item pools, so flattening
the base it points to already fixes it, and its own 1-2 extra pools
were already within the target count. `chests/anomaly.json` (2 pools,
both `rolls: 1`) was also left alone: already at 2 items, the top of
the stated range. `chests/pocket2.json` (4 pools) was flattened the
same way as everything else.

**Design call made without asking, worth knowing:** the base tables
carried an explicit guaranteed-floor design (food and torches in every
chest, called out in code comments and `VISION.md` 3.7.4). Flattening
everything into one pool with only 1 or 2 total rolls makes that floor
no longer guaranteed: food, torches, and every other former pool now
just compete on weight for the same 1-2 draws, so a chest can come up
with neither. The user's own restated ask ("should be 1 or 2... instead
of the near full chest slots") was specific and repeated enough to read
as intentional, so this was implemented as the literal, strict reading
rather than preserving the floor as a guaranteed extra on top (which
would have made the effective count 3-4, not 1-2). If the food/torch
guarantee turns out to matter more than the count, that is a one-line
change: split the flattened pool back into a guaranteed floor pool
(`rolls: 1`, food and torches only) plus a bonus pool
(`rolls: {min: 1, max: 2}`, everything else).

#### Verification

`./gradlew build --offline` green. All 22 rewritten files parse as
valid JSON (`node -e "JSON.parse(...)"` per file) and were spot-checked
for correct entry counts and no accidental data loss (e.g.
`vaults/tier_3.json`: 42 entries survived the vault utility filter, no
duplicate-looking near-misses). No Java code in the mod reads loot
table pool structure directly (`grep`'d for it), so nothing outside the
datapack itself needed to change. Live item, not yet walked: open
several chests and vaults across tiers and confirm 1-2 items each,
gear/currency only from vaults.

---

### PD-55: Every iron door connector ships with its own lever attached, defeating the point of a locked door (Low-Medium)

**Reported:** 2026-09-03
**Severity:** Low-Medium (not a correctness bug: the lever's presence
is itself a deliberate, documented fix for a real problem, PD-27, but
it does defeat the intended obstacle every time)
**Status:** Fixed

Reported from live play: "iron doors seem to all have the switches
preattached when the switches should either be in chests or on other
walls not attached to the door (or even in a different but accessible
room)."

Confirmed exactly, in `ConnectorStamper.applyIronDoor`
(`ConnectorStamper.java`): every `IRON_DOOR` connector (10 of the
connector weight table's roughly 95 total, so roughly 1 in 10 open
edges) places its own lever one column beside the door, on the same
wall, at `DOOR_MIN - 1`. The javadoc directly above it names the reason:
"PD-27: ... nothing else in the mod places a redstone source, and this
connector can land on the critical path, so a run with no lever would
have no way through." So the lever's placement is not an oversight; it
is a previous fix for a previous bug (an iron door with literally no way
to open it, since the mod has no other redstone source anywhere), and
moving the lever away without something else replacing its guarantee
would reopen that exact bug.

**The real tension**, worth reading before this is actioned: the
situations round (M45 through M53, already landed) built a proper
mechanism for exactly this kind of gate: `access: gated` plus
`provides`/`requires` tags, one of which is already `redstone`
(`SituationTags.REDSTONE`), checked by M47's root-distance solvability
pass so a `requires: redstone` gate is guaranteed satisfiable by some
upstream `provides: redstone` cell or the bag. The `IRON_DOOR` connector
predates all of that and sits entirely outside it: it is a
`LayoutStamper`-level connector between any two cells (not a
`dungeon_room` template's own gate), it carries no `access`/`requires`
tag of its own, and the situations round's solvability guarantee says
nothing about it. Relocating the lever "into a chest, on another wall,
or in a different room" as asked is exactly what the `requires: redstone`
tag exists to guarantee is reachable, but wiring `IRON_DOOR` into that
system, rather than just moving the block, is real integration work
across two features built at different times, not a two-line fix.

#### Fix

Not planned yet. Recorded for investigation only. Three shapes worth
weighing once this is picked up, roughly in order of how much they
disturb the existing PD-27 guarantee:
1. Move the lever off the door frame onto a different wall of the
   *same* cell (still trivially guaranteed reachable, since it never
   leaves the room the door is in, but no longer looks pre-opened).
2. Put the lever (or a button, or an item the door consumes) in a chest
   in the same cell, guaranteed reachable the same way.
3. Put it in a different cell entirely, which is only safe if that cell
   is wired through the situations round's `provides: redstone` /
   `requires: redstone` tags and M47's solvability pass, so a run can
   never generate an `IRON_DOOR` edge with no reachable `redstone`
   source upstream of it. The option the user's phrasing most directly
   asked for, and also the one with the most real work behind it.

#### Verification

Not applicable yet.

---

## Found live testing, follow-up on the trial key report (2026-09-03)

### PD-56: Every trial key this mod grants is permanently non-functional: the M49 bag tag breaks vanilla's strict vault-key match (High)

**Reported:** 2026-09-03
**Severity:** High (the mod's own trial keys cannot open any vault, ever,
which breaks the trial-key economy the situations round's Pot Room /
spur-vault design depends on, not just an item-count nuisance)
**Status:** Partially fixed live (vault-side drops removed); the deeper
tagging bug is open, record only, no further fix until told to proceed.
It needs a design call, not a mechanical one (see below).

Follow-up to the earlier "trial key doesn't work on other vaults"
exchange, which this replaces with the real, verified cause. The
original vanilla-single-use explanation was true but incomplete: a
freshly *mod-granted* trial key was reported as not working on *any*
vault, including a brand new one, and not stacking with other trial
keys the player held.

Root cause, verified against the 26.2 jar's bytecode, not guessed:
`VaultBlockEntity$Server.isValidToInsert` checks
`ItemStack.isSameItemSameComponents(heldStack, vaultConfig.keyItem())`,
a strict match on item type *and every component*. `VaultConfig.keyItem()`
is a plain `minecraft:trial_key` with no components. Every `trial_key`
this mod's loot tables grant carries a `minecraft:set_components`
function stamping `custom_data.pocketdungeons.bag = 1` on it (the M49
tag `InventorySwap.isBagTagged` reads to know an item came from the
run). That tag makes `isSameItemSameComponents` return `false`
unconditionally: a mod-granted trial key cannot open *any* vault,
freshly opened or otherwise, and it cannot stack with an untagged key
either, for the same reason (stacking also requires identical
components). This is not a per-vault bug; it is the item itself being
broken at the moment it is created, confirmed with `javap -c` on
`VaultBlockEntity$Server`, not inferred.

Checked and ruled out: `minecraft:ominous_trial_key` is never granted by
any loot table in this mod (`grep` across `chests/` and `vaults/` found
zero matches), so it is not affected. Plain `trial_key` is granted from
17 files total: all 9 tier/drowned/ominous variants under both
`chests/` and `vaults/`.

#### Fix

**Done, live:** the `trial_key` entry was removed outright from all 9
`loot_table/vaults/*.json` files (tier 1 through 3, base/drowned/ominous),
per the user's explicit "I'd rather not receive a trial key from a vault
anyways." This also resolves PD-54's vault-should-be-gear-or-emeralds
finding for this one item specifically. Verified: no `trial_key`
reference remains anywhere under `loot_table/vaults/`, and every touched
file still parses as valid JSON.

**Still open, and deliberately not touched:** the same broken tag is
still on every `trial_key` granted from `loot_table/chests/*.json` (9
files), where trial keys are supposed to stay per the original design
(Pot Room `provides: trial_key`, gated-chest tool rewards). Those keys
are just as non-functional as the vault-granted ones were. Stripping the
tag outright is not a safe mechanical fix on its own: the tag is also
what `InventorySwap.isOurs` (spec 11.9's "belt and braces" check) uses
to decide an item legitimately came from the run rather than from
somewhere suspicious, on the way out of the void. An untagged trial key
in a player's inventory when they leave would trip the "these items came
into the dungeon from outside the run" warning and get diverted, even
though it is entirely legitimate loot. This needs one of:
1. Exempt `trial_key` (and any other vanilla item whose function depends
   on exact component equality) from the `set_components` tagging pass
   specifically, and teach `InventorySwap.isOurs` to also recognize a
   plain, untagged `trial_key`/`ominous_trial_key` as "ours" by item type
   alone, the same way it already special-cases `Keystone.isKeystone`.
2. Find a tagging mechanism that does not touch `custom_data` at all (a
   different component vanilla's vault check ignores, if one exists) --
   unconfirmed whether such a component exists; not checked.
3. Track "this trial key came from this run" out of band (e.g., in
   `DungeonLog` or the instance record) instead of on the item, and stop
   tagging `trial_key` entirely.
Whoever picks this up should also decide whether the same tagging
pattern is silently breaking any *other* vanilla item this mod grants
whose function depends on component equality (a comparator reading an
item frame, a specific enchanted book match, etc.); `trial_key` is
simply the one a player happened to notice and report, and the tagging pass
was applied uniformly to everything, so it is worth an audit rather than
treating this as an isolated case.

#### Verification

Vault removal: confirmed by grep (no `trial_key` reference remains under
`loot_table/vaults/`) and by parsing all 9 touched files as JSON. Live
item, not yet walked: open a vault, confirm it never offers a trial key
as a reward. The deeper chest-side bug is unverified beyond the jar
bytecode read; a live check would be opening a chest for a tagged trial
key and confirming it still fails to open a fresh vault.

---

## Found live testing, same session as the free re-entry fix (2026-09-03)

### PD-57: Free re-entry duplicated the keystone compass once per re-entry (High)

**Reported:** 2026-09-03 ("now I get an additional compass each time I
enter the dungeon, I entered and left 3 times, now I have 3 compasses")
**Severity:** High while it lasted (a real regression in a fix from the
same session, not a pre-existing bug; item duplication)
**Status:** Fixed (2026-09-03), same session as the report

A self-inflicted regression in the free-re-entry inventory fix built
earlier this session (the one that stopped a step-out-and-come-back run
from resetting the player's void inventory to just the keystone).
`InventorySwap.enterVoid` calls `applyKeystoneItem` (mints a fresh
keystone into slot 0) and then, for a matching re-entry,
`restorePauseIfMatching` (hands back everything that was paused on the
way out). `PauseRecord`'s snapshot is the full 42 slot capture
`snapshotPlayer` always takes, and slot 0 of that capture is whatever
keystone the player was holding at the moment they stepped out.
Restoring it back duplicated the one `applyKeystoneItem` had just
placed: a second compass on the first re-entry, a third on the second,
exactly the 1-per-re-entry pattern reported.

#### Fix

`InventorySwap.withoutKeystoneSlot` strips slot 0 (replaces it with
`ItemStack.EMPTY`) at the moment a pause is created, before it is
stored, rather than filtering it out later at restore time. The
invariant (a `PauseRecord` never carries slot 0) holds no matter which
of the two sites, pause creation in `leaveVoid` or restore in
`restorePauseIfMatching`, a future reader looks at first.

#### Verification

Full test suite green (`./gradlew build --offline`). No dedicated
regression test added: same reasoning as PD-50, this is state-machine
behaviour with no new pure-logic surface, so it is a live-play item.
Walk: enter a dungeon, leave via a path that keeps it re-enterable
(pad, `/dungeon exit`, the wall lodestone's Leave), re-enter, confirm
exactly one compass; repeat three times in a row and confirm the count
never grows.

#### A related question this surfaced, not chased

`RunLifecycle.deliverVoidInventory`'s `carried` list is built from every
non-empty slot in the full snapshot, slot 0 included, same as
`PauseRecord` was before this fix. That means a genuinely **final** exit
(not a free re-entry, `shouldPause` says no) still delivers the
keystone item into the room's containers along with everything else,
rather than it following the player out. Whether that is actually wrong
is unclear and not confirmed either way: `DungeonCommands.mintKey`'s own
javadoc calls the keystone item "a view onto server state" that costs
nothing to replace ("losing it in lava is no longer a way to lose a
keystone"), which suggests this may be harmless by design: the player
can just run `/dungeon key` again. Not touched, since it has not been
reported and the design intent is genuinely ambiguous rather than
verified either way. Worth a quick look if a "my compass vanished when
I left for good" report ever comes in.

---

## Design change, same session (2026-09-03)

### PD-58: Crash/restart recovery holds the void inventory instead of dropping it on the ground

**Reported:** 2026-09-03 ("seems if I log off and restart the server
while being in a pocket dungeon, when I log back in it puts my items
on the ground in the overworld instead of restoring them in the
dungeon when I reenter"). Traced to spec 11.6's own documented
last-resort fallback firing correctly, not a defect: a crash or a hard
restart wipes `InstanceRegistry` (in-memory only), and
`Instances.processJoinRecoveries` teleports the player out on rejoin
through a raw `teleport` call that never runs through `Instances.detach`,
so `InventorySwap`'s leaving branch has no room and no cached slot to
find. Confirmed nothing was actually lost: the items land on the
ground, visible, exactly as spec 11.6 promises ("nothing is voided
silently").

The user's follow-up reframed this correctly: dropping was never
required just because there was no room to speak of. "If we're able to
drop them on the ground, why can't we instead just save them into the
pocket dungeon inventory?" The specific old instance is genuinely gone
(a fresh `/dungeon` after a restart opens a brand new one, not the old
one), so restoring *into that instance* is not possible. Holding the
items and handing them back the next time the player enters *any*
dungeon is possible, and is a straightforward extension of the
`PauseRecord` mechanism PD-57 already built.

#### Fix

New `InventorySwap.OrphanRecord` (list of items, no slot: unlike
`PauseRecord` there is no instance to match against) with its own
`DungeonLog` sidecar (`orphans`, mirroring `stashes`/`pauses`
exactly). `RunLifecycle.deliverVoidInventory` gained a `knownInstance`
parameter, set by `InventorySwap.leaveVoid` from whether
`Instances.consumeLastDetachedSlot` returned anything at all:

- `knownInstance` true, no room found: a normal, tracked exit from a
  run that legitimately has no room (untimed, `/dungeon admin build`).
  Unchanged: drop at the player's still-visible feet.
- `knownInstance` false: nothing in this mod ever saw the player
  leave. `InventorySwap.stashOrphan` holds the items (slot 0 stripped
  the same way `PauseRecord` strips it, same reasoning as PD-57: a
  future `applyKeystoneItem` owns slot 0) instead of dropping them.

`InventorySwap.enterVoid` gained `restoreOrphanIfAny`, run after the
existing pause restore: on the player's next entry into any dungeon,
whatever was orphaned is placed into their inventory and the record is
cleared, with a chat line telling them what happened
("Your items from a dungeon that closed while you were away have been
returned to you."). The Lost and Found entry for the original leave is
written before this branch runs either way, unaffected.

#### Verification

Full test suite green (`./gradlew build --offline`). No dedicated
regression test: same reasoning as PD-50 and PD-57, this is
state-machine behaviour with no new pure-logic surface, so it is a
live-play item. Walk: enter a dungeon holding some items, kill the
server process (not a graceful shutdown, to skip the disconnect path
the same way a real crash would), restart, log back in, confirm no
items on the ground; enter any dungeon and confirm the held items
appear in the main inventory with the return message, and that slot 0
still holds exactly one keystone.

---

## Loot tuning, follow-up on PD-54 (2026-09-03)

### PD-59: Reward room still felt loot-heavy after PD-54; durability items always spawned pristine; coal too common

**Reported:** 2026-09-03. "Everything in priority 1 and priority 2 is
good except for the loot counts, it's still too much. Also coal can be
a lot more scarce", followed mid-turn by "Anything with durability
should have random damage."

**Status:** Fixed (2026-09-03). Item 1 was flagged rather than fixed
when this entry was first written; the user's follow-up ("cut the
reward chests down") settled it.

Three separate findings under one report:

1. **The reward room places up to three completion chests**
   (`TrialContent.placeCompletionChests`, called from
   `RunLifecycle.completeRun`'s payout step), each independently set to
   the same tier table and each rolling PD-54's `{min: 1, max: 2}`.
   PD-54 fixed *per-table* roll count; it did not touch *how many
   chests get that table*. Three chests at 1-2 items each is 3-6 items
   from the reward room alone, which is almost certainly what still
   read as "too much" even though every individual chest is within
   spec. **Not changed**: whether each reward chest's roll count should
   drop further specifically when multiple chests are placed, or
   whether the three-chest reward shape itself should change, is a
   design call (`PayoutMath.chestCount` already varies 1 to 3 chests by
   how quickly the run finished, which is deliberate reward-shape
   design predating this session) that was not made unilaterally.
   Flagged for the user rather than acted on.
2. **No item with durability had a `minecraft:set_damage` function
   anywhere in the mod's loot tables.** Every sword, tool, and armour
   piece always spawned at full durability, in chests, vaults, the
   per-slot `gear/*.json` tables the gamble/reroll stations draw from,
   and `equipment/*.json`. Counted 215 matching entries before the fix.
3. **Coal was weighted 6 (4 on supply tables) against pools whose total
   weight ran 167 to 193**, a 3 to 4 percent pick chance per roll on its
   own, low as a single number but compounding across the many chests
   and vaults a run passes through, and its stack size (`set_count`)
   ran as high as 8 to 30 depending on tier, well above every other
   resource entry's range.

#### Fix

**Durability (item 2).** Added `minecraft:set_damage` with a
`{"type": "minecraft:uniform", "min": 0.1, "max": 0.6}` range to every
loot table entry matching a durable item pattern (swords, axes,
pickaxes, shovels, hoes, the four armour pieces, bows, crossbows,
shears, tridents, flint and steel, fishing rods, shields, elytra)
across `loot_table/chests/`, `loot_table/vaults/`,
`loot_table/equipment/`, and `loot_table/gear/`: 212 entries across 46
files (three of the 215 counted already had some other functions
touching damage-adjacent state and were left alone rather than risk a
double-application; not re-verified which three). **Deliberately
excluded `loot_table/bags/*.json`**: those are the deliberately scarce
starting kit, not found loot, and giving a player's starting tool
random pre-existing wear reads as a different kind of scarcity than
what was asked for. Flag if bags should be included too.

**Coal (item 3).** Weight cut to 2 everywhere it appears (13 files:
`chests/tier_1`, `tier_1_ominous`, `tier_2`, `tier_2_ominous`,
`tier_3_ominous`, `supply_tier_1/2/3`, and the same five vault tiers).
Stack size cut roughly in half to two-thirds depending on tier, e.g.
`chests/tier_1` 8-16 down to 2-4, `chests/tier_3_ominous` 12-30 down to
4-9. Full before/after per file is in the commit; not reproduced here.

**Reward room multiplier (item 1).** `PayoutMath.chestCount` capped at
2, not 3: the fast-finish tier (`usedPercent <= threePercent`, ≤60% of
the clock used) and the medium tier (`usedPercent <= twoPercent`, ≤80%)
now both return 2, collapsing rather than each losing one, so medium
and slow finishers stay distinguishable from each other. The physical
third chest slot stays in `TrialContent.placeCompletionChests`'s room
template; it just never gets filled (already how an under-earned slot
is handled: set to air, per the existing `else` branch). The
`threePercent` parameter and the `threeChestPercent` config value it
reads from are kept, unused, rather than deleted outright, the same
codec migration discipline `CONVENTIONS.md` asks for on a superseded
`DungeonLog` field: an existing server's `pocketdungeons.json` should
not need edits to load cleanly, and the threshold is still there if a
future pass wants the fast/medium distinction back.

#### Verification

All 63 files under `loot_table/` parse as valid JSON. `PayoutMathTest`
updated for the new cap (every case that used to expect the maximum
now expects 2, one new comment explaining why) and passes; full test
suite green (`./gradlew build --offline`). Checked by hand that nothing
else in the mod assumes a maximum of 3 reward chests: every
`record.rewardChests` read is a `> 0` / `<= 0` check, never an equality
against 3, so the cap needed no changes outside `PayoutMath` and its
test. Live items, none yet walked: open several chests/vaults and
confirm damaged gear (not always pristine); confirm coal shows up
markedly less often and in smaller stacks; finish a run comfortably
within the clock and confirm the reward room hands out at most 2 filled
chests, not 3.

---

## Follow-up on PD-59: stack sizes, not just item counts (2026-09-03)

### PD-60: Individual stacks still read as too generous even at 1-2 items per chest

**Reported:** 2026-09-03. "Still too many items, for example I got 6
steaks from a chest when I should really only get 1 or maybe 2. So
reduce reward sizes by about a third or quarter of it's current size
and make unstackable items like water buckets, armour, tools, etc. half
as common."

**Status:** Fixed (2026-09-03).

PD-54 and PD-59 both worked at the pool level (how many rolls, how many
chests); this report is about what a single roll produces. "6 steaks"
is one roll landing on `cooked_beef` with its `set_count` range, not
six separate items, so the fix is stack size, not roll count.

#### Fix

One pass over every `.json` under `loot_table/chests/` and
`loot_table/vaults/` (both explicitly asked for by name in earlier
reports; `bags/` stays excluded, same reasoning as PD-59: a starter kit
is not "found loot"):

- **Every `minecraft:set_count` range cut to roughly two-thirds**
  (`round(value * 0.65)`, floored at 1, `max` never allowed below the
  new `min`). `chests/tier_1.json`'s `cooked_beef`, the reported
  example, went from 6-10 down to 4-7. 295 count ranges touched.
- **Every unstackable item's pick weight halved** (`round(weight / 2)`,
  floored at 1): all armour, all tools and weapons, `bow`, `crossbow`,
  `trident`, `shears`, `shield`, `elytra`, `flint_and_steel`,
  `fishing_rod`, the three filled buckets, `oak_boat`, and `book`
  (every occurrence in this dataset carries `enchant_with_levels`,
  which turns it into an effectively unstackable enchanted book at roll
  time even though the base id is plain `book`). 165 weights touched.
  Verified against vanilla max-stack-size knowledge item by item rather
  than pattern-matched blind: block items that happen to share a
  naming quirk (`oak_planks`, `iron_bars`, `lapis_lazuli`, the armour
  trim smithing templates, `heart_of_the_sea`, `echo_shard`,
  `netherite_scrap`/`ingot`, both golden apples) all stack normally in
  vanilla and were deliberately left out of the halving.
- Not touched: `loot_table/gear/` and `loot_table/equipment/`. Every
  entry in those tables is already an armour or weapon piece, so
  halving every weight equally inside a pool that is entirely
  unstackable items is a no-op in relative terms; the halving only
  does something where unstackable items compete against stackable
  resources in the same pool, which is exactly the chests/vaults case.

#### Verification

All 63 files under `loot_table/` parse as valid JSON. Full test suite
green (`./gradlew build --offline`). No dedicated regression test:
pure datapack content. Live item, not yet walked: open several chests
across tiers and confirm food/resource stacks read as noticeably
smaller, and that armour, tools, and buckets show up less often
relative to stackable resources.

---

### PD-61: PD-60's cut was not aggressive enough

**Reported:** 2026-09-03. "no, you need to cut more, 6-10 should be
down to 1-3." (`cooked_beef` in `chests/tier_1.json`, the same entry
PD-60 used as its own example, originally 6-10, cut by PD-60 to 4-7.)

**Status:** Fixed (2026-09-03).

#### Fix

Rather than guess at a second multiplier, solved directly for the ratio
the report specifies. Only the current on-disk value (4-7, post PD-60)
was actually available to transform, so the ratio was derived from that
step, not the original 6-10: `1/4 = 0.25` for the low end,
`3/7 ≈ 0.4286` for the high end. Applied uniformly to every remaining
`minecraft:set_count` range in `loot_table/chests/` and
`loot_table/vaults/` (`bags/` still excluded, same reasoning as PD-59
and PD-60): `new_min = max(1, round(old_min * 0.25))`,
`new_max = max(new_min, round(old_max * 0.4286))`. 283 ranges scaled
across 17 files. `cooked_beef` in `chests/tier_1.json` now reads
`{"min": 1, "max": 3}`, confirmed by reading the file directly, matching
the report exactly.

This is now two compounding cuts on top of the original values (PD-60's
~0.65, then this pass's ~0.25-0.43), so a stack that started at 6-10 is
now 1-3, roughly a quarter to a third of where it began, in line with
"reduce reward sizes by about a third or quarter" from the PD-60 report
that this one is a correction to.

#### Verification

All 63 files under `loot_table/` parse as valid JSON. Full test suite
green (`./gradlew build --offline`). Calibration point verified by
reading the file back, not just trusting the script's own log line (its
log line had an unrelated bug and printed `null` for the sample; the
file itself is correct, checked directly). Live item, not yet walked:
open several chests and confirm stack sizes now read as small,
single-digit amounts rather than a meaningful haul.

---

### PD-62: Player permanently locked in a room, trapped by an iron door with no lever on their side (High)

**Reported:** 2026-09-03. "got locked in a room since the lever was on
one side of the door and the other side was protected in all areas
close to the door."

**Status:** Fixed (2026-09-03).

Confirmed in `ConnectorStamper.applyIronDoor`: an `IRON_DOOR` connector
only stamps its door and lever on the cell nearer the entrance
(`LayoutStamper.applyConnectors`, `nearerToEntrance`), on purpose, so a
fresh arrival meets an already-openable door rather than two closed
doors facing each other. But backtracking through an already-open door
is allowed by design, and once it swings shut behind a player standing
on the far side, that side has no redstone source anywhere in reach:
there is no lever, no other block placed there, and nothing else in the
mod generates one. The player is stuck.

Three wrong turns before landing on the real cause, each corrected by a
precise live report:
1. First attempt placed a second, floor-mounted lever on the far side.
   Rejected: "the solutioon is not extra levers, think about the door
   placement." The actual ask was narrower: the doorway strip between
   the two cells is a 2-wide-by-3-tall column of open air on the far
   side (the near side's `applyIronDoor` stamps the door there; the far
   side's matching column was already resolved to air by the door
   jigsaw and never touched again), and that strip should simply allow
   the player to place their own redstone source in it.
2. Second attempt misread that as "let the door itself be broken
   through." Corrected: "no digging through, the iron doors stay
   protected, the empty space is what's unprotected." The door leaves
   stay exactly as immutable as they are today; only the open threshold
   in front of them, on the far side, needed to change.
3. Third attempt, having correctly narrowed the target to a placement
   exemption rather than a break exemption, searched only
   `RoomProtection.java`, found no block-place event registered there at
   all, and concluded placement in dungeon cells was already
   unrestricted everywhere, so nothing needed to change. Corrected: "I
   could not place blocks in that space." `RoomProtection` only
   registers `PlayerBlockBreakEvents`; the actual placement gate lives
   in `RitualListener.onUseBlock`, which fakes Fabric's missing
   "before block place" event off `UseBlockCallback` and denies
   placement whenever the target position is shell
   (`RoomProtection.isShell`), a pure floor/wall/ceiling coordinate rule
   with no notion of "this particular wall square is actually open air".
   The far side's doorway threshold is shell by that rule (it sits on
   the cell's wall ring), even though nothing is built there, which is
   exactly what was blocking the player.

#### Fix

Added a new per-instance set, `InstanceLayout.ironDoorFarSideSlots`,
threaded the same way `trialSpawners` already is: collected during
stamping, carried immutably on the record, read back at the point that
needs it.

`LayoutStamper.applyConnectors` now takes a `Set<BlockPos>` to populate.
For every `IRON_DOOR` edge, alongside the existing near-side door stamp
it also resolves the far cell and its wall
(`PlanCell farCell = edge.other(nearCell); DoorMask.Direction farWall =
farCell.directionTo(nearCell);`) and records that wall's full doorway
rectangle (`ConnectorGeometry.rect(farOrigin, farWall, DOOR_MIN,
DOOR_MAX, 1, DOOR_HEIGHT)`, the same two-wide, three-tall footprint the
near side's door occupies) into the set. No block is placed there; only
the positions are recorded.

`RitualListener.onUseBlock`'s dungeon-cell placement check now reads
this set before denying a shell placement: if the target position is in
`Instances.dungeonRecordAt(placementPos).layout.ironDoorFarSideSlots()`,
the denial is skipped and the placement (a lever, a button, redstone
dust, anything) is allowed through, exactly as though that one square
were not shell. Every other shell position, including the door leaves
themselves and every other connector type's doorway, is untouched.

`InstanceLayout` gained the new field as its last record component,
normalized to `Set.of()` when null the same way `trialSpawners` is. Its
three other construction sites (`Instances.lobbyLayout`,
`StaticLayout.build`'s `forClearingOnly` path via
`InstanceLayout.forClearingOnly`, and `StaticLayout.build` itself) pass
`Set.of()`, since none of them ever place an `IRON_DOOR` connector.

#### Verification

`./gradlew build --offline` green, all tests passing, including the
full `LayoutGraphGenerator` and `GraphSolvabilityTest` sweeps.
Live item, not yet walked: generate a run with an `IRON_DOOR` connector
on the critical path, cross it, let it close, and confirm a lever placed
on the far-side threshold opens it and nowhere else in that cell's walls
accepts a placement.

#### Follow-up: the same threshold was still unbreakable (2026-09-03)

Reported from live play: "I can place blocks now (correct) but I can't
mine them up again. What if I place the wrong block in the doorway, I
can't fix it with the current setup." Placement went through
`RitualListener.onUseBlock`, which the fix above updated; breaking goes
through the separate `RoomProtection.beforeBlockBreak`, which still only
checked `isShell` and had no idea `ironDoorFarSideSlots` existed. A
player who placed the wrong block, or simply wanted the threshold empty
again, had no way to undo it.

**Fix:** `beforeBlockBreak`'s dungeon-cell branch now carries the same
exemption placement got: a shell position still denies breaking by
default, but if it is also in `Instances.dungeonRecordAt(pos).layout
.ironDoorFarSideSlots()`, breaking is allowed. Every other shell
position, door leaves included, is unaffected.

**Verification:** `./gradlew build --offline` green, all tests passing.
Live item, not yet walked: place a block on the far-side threshold, then
mine it back up, and confirm every other wall position in that cell
(including the door itself) still refuses to break.

---

### PD-63: Trial-spawner and exit-pad completion silently stops registering for a player who died mid-run (High)

**Reported:** 2026-09-03. "Sometimes I get a glitch where even when I
complete all the trail spawners and stand on the lodestones to mark the
finish, it doesn't register and then I'm just stuck... I think the
problem with the dungeons not registering completion comes from when I
die." The report correctly named the trigger; the mechanism took a full
read of the death path to confirm.

**Status:** Fixed (2026-09-03).

Confirmed in `Instances.rescue`, the handler `ALLOW_DEATH` calls instead
of letting a player actually die inside the dungeon. It always calls
`RunLifecycle.dropMember` first, which routes through `Instances.detach`:
that removes the player from `record.members`, `InstanceRegistry.byMember`,
the timer and Trial Omen unconditionally, the same full detachment a
normal `/dungeon exit` performs. For a solo player (no leadership change,
the record survives instead of being purged, U8 Stage 1), `rescue` then
checks `stillLive && hadRoom` and, when true, teleports the player right
back into the same still-live instance's entrance room, as a courtesy
rather than throwing them all the way back to the overworld. But nothing
in that branch ever re-added the player to `record.members` or
`InstanceRegistry.byMember` after `detach` removed them. The player ends
up standing inside a dungeon that, as far as the mod's own bookkeeping is
concerned, they are not in: `Instances.onTick`'s watcher (`watchSpawnerClears`,
the exit-pad `stepped` check, the timer) only ever iterates
`record.members`, so from the moment of death onward it silently stops
seeing this player at all. Every trial spawner they clear still counts
(that state lives on the record's own world/content tracking, not on
membership), and standing on the exit pad afterward still physically
happens, but the watcher that would turn either into a completion never
runs for them again. Block protection was unaffected (`RoomProtection`/
`RitualListener`'s checks are position-based, not membership-based), which
is why the run otherwise felt normal and the cause was easy to miss.

#### Fix

`rescue`'s `stillLive && hadRoom` branch (`Instances.java`) now re-admits
the player the same way `admit()` does for a fresh entry, before
teleporting them back in: `record.members.put` with the same `ReturnPoint`
already captured earlier in the method (before `detach` cleared it, so
the player's current in-dungeon position is never mistaken for a return
point), `InstanceRegistry.byMember.put`, `record.timer.addPlayer` when a
timer exists, and `applyTrialOmen` to restore what `detach` had just
cleared for an ominous run. Every other branch of `rescue` (a run that
ended with the death, an admin build or untimed run with no room, the
`else` fallback to world spawn) is unchanged: only the one path that
teleports the player back into a still-live instance needed the re-admit.

#### Verification

`./gradlew build --offline` green, all tests passing. Live item, not yet
walked: die inside a dungeon run, confirm the rescue message and the
teleport back into the entrance room, then clear the remaining trial
spawners and step on the exit pad, and confirm completion now registers
normally.

---

### PD-64: No self-service way to despawn a stuck or unwanted run and start over

**Reported:** 2026-09-03, alongside PD-63: "you still haven't added the
reset feature I asked a while ago... if a player fails a dungeon they
'can' complete it anyways but they should also be able to despawn the
whole dungeon and start fresh."

**Status:** Fixed (2026-09-03).

Confirmed there was no command for this. `/dungeon exit` and `/dungeon
quit` both only detach the caller (`RunLifecycle.exit`/`quitDoor`); for a
solo player neither one purges the instance, so the same layout, PD-63's
soft-lock included, sits there waiting for free re-entry (U8 Stage 1: "an
empty instance is now normal"). `/dungeon resetkey` is self-service but
answers a different question: it wipes the caller's keystone *level*
progress back to 0 and mints a fresh `[1]`, which is not what "start over
on this run" means and is a much bigger reset than a stuck player is
asking for. Only `/dungeon admin purge`/`resetroom` could actually despawn
a live instance, and both are operator-only.

#### Fix

Added `/dungeon abandon`, player-facing, owner-only. It resolves the
caller's own run (the one they are currently standing in, or, if they
already stepped out of it, the idle one `RunLifecycle.isReenterable`
would otherwise hand them back into for free) and calls
`InstanceTeardown.purge`, the same routine an admin purge, a timeout and a
normal completion all already funnel through: every stamped block clears,
the timer closes, any party members still inside are ejected, and the
keystone is refunded the same cost-free way a death already is. The
refund is the point: the same keystone opens a brand new layout. A
non-owner (a party guest) is refused with a pointer to `/dungeon exit`
instead, since purging ends the run for the whole party, not just the
caller.

#### Verification

`./gradlew build --offline` green, all tests passing. Live item, not yet
walked: open a run, run `/dungeon abandon`, confirm the dungeon's blocks
clear and the keystone is back in hand, and confirm a guest party member
running the same command is refused rather than ending the run for the
owner.

---

### PD-65: Void inventory lost on every exit, including "Leave Dungeon" (Critical)

**Reported:** 2026-09-03. Initially reported as "lost in all cases
except Leave Dungeon"; corrected the same day to "lost even if I press
Leave Dungeon." The user also noted the contrast that matters: "The
overworld inventory seems to survive no matter how the player logs out,
disconnects, changes worlds." That is the design principle the void
inventory violates.
**Severity:** Critical
**Status:** Fixed. The void inventory is now persisted as an
`OrphanRecord` on `DungeonLog` unconditionally on every leave, the same
guarantee `StashRecord` gives the overworld inventory. Room delivery is
a bonus that clears the orphan only on full success. `shouldPause`,
`PauseRecord`, `flushOrphanedPause`, `deliverVoidInventory`, and the
`lastDetachedSlot` cache have been removed. `./gradlew build --offline`
green, all tests passing. Live verification not yet performed.

The void inventory (everything the player was carrying inside
`pocketdungeons:void`) is supposed to be preserved no matter how the
player leaves: "Leave Dungeon", getting kicked, disconnecting, the
server closing, or the dungeon being purged. The overworld (survival)
inventory already has this guarantee: it is persisted as a
`StashRecord` on `DungeonLog` (saved to disk), and restored on the
player's next entry into any dungeon, regardless of how they left or
whether the instance they were in still exists. The void inventory
does not have the same guarantee. It is routed through a tangle of
in-memory caches and physical blocks in the dungeon dimension, all of
which can be gone by the time the delivery runs.

#### Root cause: a regression introduced by the `shouldPause` /
`tearingDown` / `flushOrphanedPause` changes

The last committed version (97dfa4e, "stash and swap, the void
inventory invariant") had a single leaving branch:
`leaveVoid` always called
`RunLifecycle.deliverVoidInventory(server, player, voidInventory)`.
That method looked up the room through `InstanceRegistry.byMember`,
which was already null (detach removed the player before the
teleport that triggers the leave), so `roomOrigin` was null, and
items were dropped at the player's feet. That was not great (items
scattered at the return point), but it was not a total loss: the
player could see and pick them up. The user considers this "working."

The uncommitted changes added three mechanisms on top of that
committed baseline, each well-intentioned, together a regression:

1. **PD-50 fix** (`lastRoomCellOrigin` cache in `Instances.detach`):
   made `deliverVoidInventory` fall back to a cached room origin when
   `byMember` is already null, so items reach the room's containers
   instead of the player's feet. This is what made "Leave Dungeon"
   actually deliver to the room.

2. **`shouldPause` / `PauseRecord`** (new branch in `leaveVoid`):
   for a solo owner leaving a mid-run, not-yet-completed, still-live
   instance, pause the void inventory in `DungeonLog` and restore it
   on free re-entry into that exact instance, instead of delivering
   it to the room. The intent was to avoid emptying the player's
   hands on a brief step-out-and-come-back. The side effect is that
   items are no longer delivered to the room, so a player who leaves
   mid-run and then starts a *new* dungeon (instead of re-entering
   the old one) finds nothing: the items are stuck in a
   `PauseRecord` tied to a slot the new dungeon does not match, and
   `restorePauseIfMatching` silently skips them.

3. **`tearingDown` flag** (new field on `InstanceRecord`, set at the
   top of `purge` and `retireOrPurge`): prevents `shouldPause` from
   pausing items for an instance that is seconds away from being
   removed from `bySlot`. Without it, a purge that ejects an online
   member would pause their items for a re-entry that is never
   coming. With it, `shouldPause` returns false for any exit where a
   purge fires in the same tick, which includes "Leave Dungeon" from
   the lobby (see below). Those items fall through to
   `deliverVoidInventory`, which targets a room being torn down.

4. **`flushOrphanedPause`** (new safety net in `InstanceTeardown`):
   supposed to flush a paused `PauseRecord` to the room when the
   instance is purged. It only runs inside `purge`'s member loop
   (`record.members.keySet()`), so a member who already left or
   disconnected (removed from `members` by `detach`) is never
   reached. The `PauseRecord` sits in `DungeonLog` forever.

The net effect: every exit path either pauses items for an instance
the player might never re-enter, or delivers to a room that might
already be gone. The overworld inventory has none of these problems
because `StashRecord` is written unconditionally on entry and restored
unconditionally on the next entry, with no dependency on which
instance the player was in or whether it still exists.

#### How each exit loses items

**"Leave Dungeon" from the lobby** (the most common "Leave Dungeon"
case, since the dialog is available before any door is chosen):
`RunLifecycle.exit` calls `Instances.eject`, which calls `detach`,
which caches `lastRoomCellOrigin` and `lastDetachedSlot` and
teleports the player out. Then `exit` calls
`Instances.purgeIfAbandonedLobby` (`Instances.java` lines 1210-1214),
which sees `awaitingDoorChoice && chosenStep == 0 && members.isEmpty()`
and fires `InstanceTeardown.purge`. `purge` sets `tearingDown = true`,
nulls `record.roomCellOrigin` at line 155, captures it for its own
teardown use, and queues a `PendingClear` to erase the room blocks.
The player was already removed from `record.members` by `detach`, so
`purge`'s member loop does not execute for them and
`flushOrphanedPause` is never called. On the next tick, `leaveVoid`
runs: `shouldPause` is false (the record is gone from `bySlot`, and
`tearingDown` would have blocked it anyway), so `deliverVoidInventory`
runs with the cached `roomOrigin` from `detach` time.
`deliverToRoom` (`RunLifecycle.java` lines 183-202) scans the room
cell for containers, but the `PendingClear` may have already erased
them. Any items that do not fit in surviving containers are dropped
via `Block.popResource` at the pad position inside the dungeon
dimension, where the floor is being cleared. The items fall into the
void and are destroyed.

**"Leave Dungeon" mid-run, then start a new dungeon:**
`shouldPause` is true (instance is live, owner, reenterable), so the
items go into a `PauseRecord` tied to this instance's slot. The
player then runs `/dungeon` to start fresh.
`reenterOwnedInstance` finds the old instance is still reenterable
and teleports the player back into it (free re-entry), which does
restore the pause. But if the player instead runs `/dungeon abandon`
(PD-64) first, or lets the instance expire, or the server closes,
the instance is purged. `purge`'s member loop does not include the
player (detach already removed them), so `flushOrphanedPause` is
never called. The `PauseRecord` sits in `DungeonLog` with the items,
never delivered to the room, never restored on any future entry. The
items are orphaned in saved data that nothing ever reads again.

**"Leave Dungeon" after completing the run:** `shouldPause` is false
because `isReenterable` checks `record.completed.isEmpty()` and the
player has completed. `deliverVoidInventory` runs with the cached
`roomOrigin`, which `completeDungeon` updated to the terminal cell
(`RunLifecycle.java` line 1251: `record.roomCellOrigin =
newRoomOrigin`). `deliverToRoom` delivers to the room at the terminal
cell. This is the one variant that might actually work, if the room
containers survived and the player knows to look there on their next
run's lobby. But the player has no way to know the items went to a
room they cannot see until they start another run, and any overflow
is still dropped at the pad in the dungeon dimension.

**Purge while the player is online** (kick that triggers a leadership
change, `SERVER_STOPPING`): `InstanceTeardown.purge` sets
`tearingDown = true` and nulls `record.roomCellOrigin` at line 155
*before* the member loop calls `eject`/`detach`. `detach`
(`Instances.java` line 1149) only writes `lastRoomCellOrigin` when
`record.roomCellOrigin != null`, so it is never written. By the time
`leaveVoid` runs on the next tick, `teardown` has already removed the
record from `bySlot`, so `shouldPause` is false.
`deliverVoidInventory` gets `knownInstance = true` (the slot was
recorded) but `roomOrigin = null` (never cached), so it falls through
to the "drop at the player's feet" branch (`RunLifecycle.java` lines
119-125). The items are scattered at the return point in the
overworld, easily missed or despawned, and never handed to the room
the player expected.

**Disconnect, then the instance is purged while the player is
offline:** `handleDisconnect` calls `dropMember`/`detach` while the
room still exists, so `lastRoomCellOrigin` *is* cached with a valid
origin. The instance is purged later (abandoned lobby, timeout
retirement, etc.); the member was already removed from
`record.members` at disconnect, so `purge`'s member loop never
reaches them and `flushOrphanedPause` is never called for them (it
iterates `record.members.keySet()`, `InstanceTeardown.java` line
156). The room blocks are torn down. On reconnect, the join recovery
teleport (`Instances.java` lines 260-275, `processJoinRecoveries`
lines 1229-1263) sends the player out of the void, `leaveVoid` runs,
`shouldPause` is false (bySlot empty), and `deliverVoidInventory`
gets the stale `roomOrigin` from disconnect time. `deliverToRoom`
(`RunLifecycle.java` lines 183-202) scans the room cell for
containers, finds none (the cell was cleared by teardown), and calls
`Block.popResource` at the pad position *inside the dungeon
dimension*, where there is no floor left. The items fall into the
void and are destroyed. This is the worst variant: the items are not
just scattered, they are gone.

**Server close / hard restart:** `SERVER_STOPPING` calls
`InstanceTeardown.purge` for every live instance (`Instances.java`
lines 325-338), which ejects online members. Whether `leaveVoid` runs
depends on whether a tick fires after those teleports before the
server finishes stopping; if it does, the items hit one of the broken
paths above. If it does not, the player reconnects into the void
still carrying the void inventory, the join recovery teleports them
out, and `leaveVoid` runs with the in-memory `lastDetachedSlot` /
`lastRoomCellOrigin` maps cleared by the restart, so `knownInstance`
is false and `stashOrphan` finally does the right thing. The outcome
is race dependent, which matches "it seems I lose my items" rather
than a deterministic save.

#### Exact code references

**The committed baseline (97dfa4e), before the regression:**
`InventorySwap.leaveVoid` had a single branch:
```java
LostAndFound.write(server, player, LostAndFound.LEAVING, voidInventory);
RunLifecycle.deliverVoidInventory(server, player, voidInventory);
```
`RunLifecycle.deliverVoidInventory` looked up the room through
`byMember` (already null after detach), got `roomOrigin = null`, and
dropped items at the player's feet. Not ideal, but not a total loss.

**The current `leaveVoid` with the `shouldPause` split:**
`InventorySwap.java` lines 726-757:
```java
private static void leaveVoid(MinecraftServer server, DungeonLog log, ServerPlayer player,
                              StashRecord stash) {
    PlayerSlots slots = new PlayerSlots(player);
    List<ItemStack> voidInventory = snapshotPlayer(player, slots);
    LostAndFound.write(server, player, LostAndFound.LEAVING, voidInventory);
    if (shouldPause(player)) {
        Integer slot = Instances.consumeLastDetachedSlot(player.getUUID());
        log.setPause(player.getUUID(), new PauseRecord(slot, withoutKeystoneSlot(voidInventory)));
    } else {
        boolean knownInstance = Instances.consumeLastDetachedSlot(player.getUUID()) != null;
        RunLifecycle.deliverVoidInventory(server, player, voidInventory, knownInstance);
    }
    clear(slots);
    // ... restore survival ...
}
```

**`shouldPause`, the gate that only one narrow case passes:**
`InventorySwap.java` lines 772-780:
```java
private static boolean shouldPause(ServerPlayer player) {
    Integer slot = Instances.peekLastDetachedSlot(player.getUUID());
    if (slot == null) {
        return false;
    }
    InstanceRecord record = InstanceRegistry.bySlot.get(slot);
    return record != null && player.getUUID().equals(record.owner)
            && RunLifecycle.isReenterable(record);
}
```

**`isReenterable`, false the moment a purge starts or a run
completes:**
`RunLifecycle.java` lines 346-349:
```java
static boolean isReenterable(InstanceRecord record) {
    return !record.tearingDown && !record.lingering && !record.visitInstance
            && record.completed.isEmpty();
}
```

**`exit` calls `purgeIfAbandonedLobby` after `eject`, purging the
lobby before `leaveVoid` runs:**
`RunLifecycle.java` lines 881, 908:
```java
Instances.eject(server, record, player);
// ...
Instances.purgeIfAbandonedLobby(server, record);
```

**`purgeIfAbandonedLobby` fires for any lobby with no members left:**
`Instances.java` lines 1210-1214:
```java
static void purgeIfAbandonedLobby(MinecraftServer server, InstanceRecord record) {
    if (record.awaitingDoorChoice && record.chosenStep == 0 && record.members.isEmpty()) {
        InstanceTeardown.purge(server, record, "lobby abandoned");
    }
}
```

**`purge` sets `tearingDown` and nulls `roomCellOrigin` before the
member loop:**
`InstanceTeardown.java` lines 131, 154-156:
```java
record.tearingDown = true;
// ...
BlockPos roomCellOrigin = record.roomCellOrigin;
record.roomCellOrigin = null;
for (UUID member : new ArrayList<>(record.members.keySet())) {
```

**`detach` only caches the room origin when it is still set:**
`Instances.java` lines 1149-1152:
```java
if (record.roomCellOrigin != null) {
    lastRoomCellOrigin.put(member, record.roomCellOrigin);
}
lastDetachedSlot.put(member, record.slot);
```

**`deliverVoidInventory`'s fallback ladder:**
`RunLifecycle.java` lines 97-125:
```java
ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
BlockPos roomOrigin = record != null ? record.roomCellOrigin
        : Instances.consumeLastRoomCellOrigin(player.getUUID());
if (dungeon != null && roomOrigin != null) {
    deliverToRoom(dungeon, roomOrigin, carried);
    return;
}
if (!knownInstance) {
    InventorySwap.stashOrphan(server, player, snapshot);
    return;
}
// ... drop at the player's feet ...
for (ItemStack stack : carried) {
    player.drop(stack, false);
}
```

**`deliverToRoom` drops leftovers into the dungeon dimension:**
`RunLifecycle.java` lines 195-201:
```java
BlockPos pad = roomOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
for (ItemStack stack : carried) {
    ItemStack remainder = insertInto(containers, stack);
    if (!remainder.isEmpty()) {
        Block.popResource(level, pad, remainder);
    }
}
```

**`flushOrphanedPause` only runs for members still in
`record.members`:** `InstanceTeardown.java` line 156 (the loop) and
line 171 (the call): the loop iterates `record.members.keySet()`, so
a member who disconnected or left earlier (already removed from
`members` by `detach`) is never flushed. This is the gap that orphans
every `PauseRecord` whose owner left before the purge.

**The contrast that matters: `StashRecord` (overworld) is
unconditional, `PauseRecord` / `OrphanRecord` (void) are
conditional.** `StashRecord` is written on every entry
(`enterVoid`, line 582: `log.setStash(player.getUUID(), new
StashRecord(true, survival))`) and restored on every leave
(`leaveVoid`, line 751: `restore(slots, stash.backup())`), with no
dependency on which instance the player was in or whether it still
exists. `PauseRecord` is only written when `shouldPause` passes
(one narrow case), only restored when the player re-enters the exact
same slot, and never flushed if the instance is purged after the
player left. `OrphanRecord` is only written when `knownInstance` is
false (crash/restart), not for any normal tracked exit. The void
inventory needs the same unconditional write-and-restore guarantee
the overworld inventory already has.

#### Fix (implemented)

The overworld inventory survives everything because it is persisted
to `DungeonLog` unconditionally and restored unconditionally. The
void inventory now has the same guarantee. `leaveVoid` always
persists the void inventory to `DungeonLog` as an `OrphanRecord`
via `stashOrphan`, then treats delivery to the room as a bonus that
clears the orphan only when the room is provably still live and
delivery fully succeeds.

**Step 1: Always hold the void inventory as an orphan, first.**

`leaveVoid` unconditionally writes the void inventory to
`DungeonLog` as an `OrphanRecord` via `stashOrphan` before any
delivery attempt. This is the same guarantee `StashRecord` gives
the overworld inventory: the items are on disk, and
`restoreOrphanIfAny` (which runs in `enterVoid`) hands them back on
the player's next entry into any dungeon, no matter what happened to
the instance they left.

**Step 2: Attempt delivery to the room as a bonus, clear the orphan
only on full success.**

After persisting, `leaveVoid` attempts to deliver to the room if one
is provably still live (the pad block is not air). If delivery fully
succeeds (all items placed in containers, no overflow), the orphan is
cleared so the items are not duplicated on re-entry. If delivery
fails or partially fails (overflow, no room, room gone), the orphan
keeps the remainder and `restoreOrphanIfAny` hands it back on the
next entry instead.

`deliverToRoom` was changed to return the leftover stacks instead of
dropping them at the pad position in the dungeon dimension. The old
`Block.popResource` drop was the path that lost items to a teardown
clearing the floor under the pad.

**Step 3: Verify the room is live before delivering.**

`leaveVoid` checks `dungeon.getBlockState(pad).isAir()` before
calling `deliverToRoom`. If the room is gone (teardown cleared it),
delivery is skipped and the orphan keeps the items.

**Step 4: Removed `shouldPause`, `PauseRecord`, `flushOrphanedPause`,
`deliverVoidInventory`, and the `lastDetachedSlot` cache.**

With the orphan record covering every exit unconditionally, none of
these are needed:
- `shouldPause` is gone: every exit holds as orphan first, then
  attempts delivery.
- `PauseRecord` is gone: the orphan record replaces it, and is not
  tied to a specific slot.
- `flushOrphanedPause` is gone: there is no pause to flush.
- `deliverVoidInventory` is gone: its logic moved into `leaveVoid`.
- `lastDetachedSlot` / `consumeLastDetachedSlot` /
  `peekLastDetachedSlot` are gone: they were only used by
  `shouldPause` and the `knownInstance` parameter, both gone.
- `lastRoomCellOrigin` / `consumeLastRoomCellOrigin` stay: the
  delivery attempt still uses them.
- `knownInstance` parameter on `deliverVoidInventory` is gone with
  the method itself.

**Kept: `tearingDown` and `isReenterable`.**

The original fix plan proposed removing `tearingDown` as well, since
it was only checked by `isReenterable`, which was only called by
`shouldPause`. But `isReenterable` is also called by
`reenterableInstance` (the free re-entry search) and
`DungeonCommands.abandon` (find the run to abandon). Both need to
exclude records that are mid-purge, so `tearingDown` and
`isReenterable` stay. Their javadocs were updated to reflect the
current callers.

This is a net reduction in code surface, which is the right sign for
a fix that replaces a tangle of conditional paths with one
unconditional one.

#### Verification

After the fix:
1. Enter a dungeon, pick up a chest item, pick "Leave Dungeon" from
   the lobby (before choosing a door). Items should be held as an
   orphan and restored on the next dungeon entry, not dropped into
   the void dimension or at the player's feet.
2. Enter a dungeon, pick up a chest item, pick "Leave Dungeon"
   mid-run (after choosing a door, before completing). Items should
   be held as an orphan and restored on the next entry into any
   dungeon (same instance via free re-entry, or a new one via
   `/dungeon`), not stuck in a slot-specific `PauseRecord`.
3. Enter a dungeon, complete the run, pick "Leave Dungeon" from the
   reward room. Items should be delivered to the room at the terminal
   cell (if it survived) or held as an orphan (if it did not).
4. Enter a dungeon, pick up an item, get kicked (or trigger a
   leadership-change purge). Items should be held as an orphan and
   restored on the next dungeon entry, not dropped at the return
   point.
5. Enter a dungeon, pick up an item, disconnect. Have the instance
   purged while offline (wait out the abandoned-lobby timer, or
   admin purge). Reconnect, get teleported out by join recovery,
   then start a new dungeon. The item should reappear at the new
   entry, not be lost into the void.
6. Enter a dungeon, pick up an item, stop the server. Restart, log
   back in, start a new dungeon. The item should reappear at entry.
7. Enter a dungeon, pick up an item, let the dungeon purge on its
   own (timer expiry and retirement while still inside). Items
   should be held as an orphan, not dropped into the void dimension.
8. The overworld inventory should still survive every exit exactly
   as it did before (the `StashRecord` path is untouched).

## Found in the live playtest interview (2026-09-26 to 2026-09-27)

Source: `docs/playtests/2026-09-26-1.md` and `docs/playtests/2026-09-27-1.md`. Causes below are from reading the code during the session; none is fixed yet.

### PD-66: Directional gates are never placed in any room (High)

**Reported:** 2026-09-27 00:07, in Hold the Plate: "the exit door does not exist", with an open doorway to the next room.
**Severity:** High. Four gated rooms (Hold the Plate, Infested Wall, Elders Chamber, Don't Look) play ungated, so their situations can be skipped.
**Status:** Fixed 2026-09-27. `applyDirectionalGates` qualifies the name before matching (`JsonPackSupport.qualify`). Verified on the test server: built layouts now carry Elders Chamber gravel gates and Infested Wall gates. Live check: `LIVE_TEST_PASS.md` section 47.
**Expected:** `LayoutStamper.applyDirectionalGates` places each room's gate on its exit side.
**Actual:** no gate anywhere.
**Likely cause:** manifest room names are namespaced (`JsonPackSupport.resourceId` returns `namespace:path`, JsonPackSupport.java:79; `RoomSelector` builds `PlacedRoom` from `entry.name`), but the switch in LayoutStamper.java:368 to 372 matches bare names (`"hold_the_plate"` and so on), so no case matches.
**Note:** fix together with PD-67, or Hold the Plate will lock players behind a door that never opens.

### PD-67: Hold the Plate's redstone cannot open its door (High once PD-66 is fixed)

**Reported:** 2026-09-27 00:06: "two hoppers passing redstone back and forth, touch a comparator connected to a repeater via a redstone trail, there's a pressure plate in the middle and nothing seems to do anything."
**Severity:** High (a blocker as soon as the gate from PD-66 is placed).
**Status:** Fixed 2026-09-27. The count moved into code (`HoldThePlateHandler`): stand on the plate 30 s, the action bar counts down, stepping off resets it, and the exit iron door opens and stays open. The broken circuit is gone from the spec and the regenerated template.
**Likely cause:** `PressureSpecs.holdThePlate` (PressureSpecs.java:293):
1. The comparator at (9,1,11) and the repeater at (13,1,6) are placed with FACING EAST. A diode's FACING is its input side (output goes to the opposite), so the comparator reads the dust and outputs into the hopper, and the repeater reads the wall block. Both should face WEST.
2. The pressure plate at (8,1,8) is not wired to anything, so standing on it does nothing (the spec says step off and the clock resets).
3. The two hoppers facing each other have no lock, so the items just shuffle and the comparator never makes a clean 30 second edge.

### PD-68: Thicket's spawner never spawns (Medium)

**Reported:** 2026-09-27 00:00: "spawner didnt seem to work"; 00:01: "no mobs at all".
**Severity:** Medium (the room is a free pass; nothing is lost).
**Status:** Fixed 2026-09-27. The Thicket and Ice Run handlers reconfigure the classic spawner at stamp time (`ClassicSpawners`): cave spiders and breezes, with `custom_spawn_rules` so the room light no longer blocks spawns. Thicket webs halved (3D checkerboard, template regenerated).
**Likely cause:** `RoomTemplateGenerator.placeSpawner` (RoomTemplateGenerator.java:999) always sets ZOMBIE; the TraversalSpecs comment (TraversalSpecs.java:131) says cave spider, "authored by M52", which never happened (the thicket situation handler returns null). The template is lit by sea lanterns, and the pocket dimension type sets `monster_spawn_block_light_limit: 0`, so a classic spawner's monster spawn check always fails there. Ice Run (breeze per its comment) uses the same `placeSpawner` and is probably affected too.
**Also:** the player asked for about half as many cobwebs.

### PD-69: Tools from mobs or loot keep full vanilla durability (Medium)

**Reported:** 2026-09-27 00:33: an axe from a pillager-type mob "isn't following the same durability rule as the other tools spawned by the dungeon".
**Severity:** Medium (leaks the strict durability economy; an iron axe has 250 uses against the dungeon cap of 16).
**Status:** Fixed 2026-09-27. `DungeonDrops` caps every item that appears in the dungeon (loot table drops and dropped item entities, mob equipment included), and `DungeonTools.limitDurability` now also covers weapons and armour with a gentler cap, never raises an item and keeps wear proportional.
**Likely cause:** `DungeonTools.limitDurability` is applied only to craft results (via `ResultSlotMixin`), and bag loot tables set their own `max_damage`. Mob equipment drops and any chest tool without a `set_components` max damage bypass the cap.

### PD-70: HOME and Descend levers swap sides between floors (Medium)

**Reported:** 2026-09-27 00:47: "do the home and descend switches change sides?"
**Severity:** Medium (risk of pulling the wrong lever at the go-home decision).
**Status:** Fixed 2026-09-27. The levers, the go-home screen and its bulb go through `RoomGeometry.viewerAlong`, which mirrors along on a SOUTH or WEST selector wall, so GO HOME is always left of the doors. `RoomProtection.isFurniture` and `RoomFurnitureTest` follow the same frame.
**Likely cause:** `RoomTemplateGenerator.doorPlanePos` (RoomTemplateGenerator.java:722) maps `along` to absolute +x (NORTH and SOUTH walls) or +z (EAST and WEST walls), not to the viewer's left and right. With `HOME_LEVER_ALONG = 5` and `LEVER_ALONG = 10`, HOME is left of Descend on a NORTH or EAST selector wall and right of it on SOUTH or WEST. The selector wall (`record.roomDungeonDoor`) changes between floors. The home screen and bulb mirror the same way.

### PD-71: The spyglass is an ungated dev tool that eats block clicks (Low)

**Reported:** 2026-09-27 00:16 (indirectly: "spyglass in my kit seems completely useless").
**Severity:** Low.
**Status:** Fixed 2026-09-27. The coordinate tool is operator only, and crouching skips it.
**Likely cause:** RitualListener.java:88 turns any right-click on a block with a spyglass in the main hand into a coordinate report and returns success, for every player. The Ranger bag kit includes a spyglass, so holding it blocks chests, buttons and levers. Gate it behind operator permission or the admin build mode.

### PD-72: Ledge Archers: skeletons spawn on the floor and loot ejects out of reach (High)

**Reported:** 2026-09-26 (first session, about 19:1x); not retested on 2026-09-27.
**Severity:** High (keys and emeralds unreachable).
**Status:** Fixed 2026-09-27. The ledges are barred at both ends and the spawner top is barred (template regenerated), and `DungeonDrops` moves anything that appears on top of a raised trial spawner to the floor below it (this covers Wither Loft and Blaze Loft if their spawners hang too).
**Actual:** skeletons spawn on the ground instead of the ledges; the trial key or emeralds eject at roof height.
**Likely cause:** `trial_spawner/ledge_archers` has `spawn_range` 6 from a roof-mounted spawner, which reaches the floor; vanilla ejects loot from the spawner's top face. Shrink `spawn_range` to ledge scale or move the spawner to ledge height, and route ejected loot to the floor (TrialContent). Check the other high spawners (`wither_loft`, `blaze_loft`) for the same shape.

### PD-73: A layout with Sump fails to stamp: "no climbable return path" (Medium)

**Found:** 2026-09-27, while verifying PD-66 on the test server (`dungeon admin build 39 8`).
**Severity:** Medium (the build is refused and cleared, so nobody is stranded; a player would see the dungeon fail to open).
**Status:** Open. Not caused by the playtest fixes.
**Actual:** `LayoutStamper.stamp` (LayoutStamper.java:246) throws `room pocketdungeons:sump has spanY 2 but no climbable return path from its lower story to the upper floor (spec 13.4)` and the plan is cleared. Seen once in 60 builds at keystone 8.
**Next step:** check the Sump spec and template for its ladder or stair column, and whether a rotation drops it.

## Found in the live Lemon playtest (2026-09-27, session 3)

Source: `docs/playtests/2026-09-27-3.md`.

### PD-74: A death rescue does not shake off the mob; an enderman follows into the staging room (High)

**Reported:** 2026-09-27 20:32: "I "died"/ got teleported back to the start from the enderman and it teleported to me again, potentially spawn camping me". Journal: `rescue` at 20:31:17, cause `minecraft:mob_attack`, in hall_corner next to Don't Look (Frostworks, floor 3).
**Severity:** High (the safe regroup point can be camped; a rescue loop is possible).
**Status:** Fixed (2026-09-28).
**Expected:** the rescue ends the fight; the staging room is safe.
**Actual:** the enderman reappears next to the player after the rescue teleport.
**Fix:** `Instances.rescue` now calls `Instances.clearMobTargets` before teleporting the player. Every mob in the dungeon dimension that targets a rescued party member has its target cleared, and the rescue itself happens before the teleport so the mob loses interest. `Instances.failRunOmen` also clears targets before ejecting the party.

### PD-75: Explosive affix pressure plates drop as free items (Low)

**Reported:** 2026-09-27 20:37: "stepping on the pressure plate above the tnt drops the pressure plate, the pressure plate should disappear instead so the player doesn't get free pressure plates".
**Severity:** Low (small economy leak).
**Status:** Fixed (2026-09-28).
**Fix:** `RoomContent.placeExplosiveHazards` now places the stone pressure plate on a separate stone support block next to the TNT, not on top of it, and registers a pending cleanup. Once the TNT block is gone, the support and the plate are removed with `level.destroyBlock(..., false)` so they drop nothing. The plate never becomes an item even if it is stepped on or mined.

### PD-76: ECHO SHARDS engine screen: overlapping stale text, all gold, no fuel name (Low)

**Reported:** 2026-09-27 19:43: "There's text overlap on the Echo Shards, per premium door: 3 board", then "overlapping words under "Echo shards", same yellow font". Cleared after walking away and back.
**Severity:** Low (readability).
**Status:** Fixed (2026-09-28).
**Fix:** `DungeonScreen.update` now finds an existing tagged text display and updates its text and position in place instead of killing and re-summoning it, so the client cannot keep a stale copy. `DungeonScreen.engineContent` applies `ChatFormatting.WHITE` to the balance line and `ChatFormatting.GRAY` to the cost line, and uses `Component.translatable(fuel.getDescriptionId())` so the fuel name renders correctly.

### PD-77: Lemon lingers up to a minute after the agent has answered (Low)

**Reported:** 2026-09-27 20:22: "After you gave that response, you weren't waiting for feedback from me but you stuck around anyways, if you have no reason to be infront of me then you should hide away". Also 20:33: vanish while looking something up.
**Severity:** Low (polish, but it is in the player's face during combat).
**Status:** Fixed (2026-09-28).
**Fix:** Added `lemonLingerSeconds` config (default 4). A reply that resolves the pending question sets `idleUntil` to now plus the linger time. When the last bubble expires and there is no pending question, Lemon despawns after the linger time instead of waiting for the full idle/fallback window. The timer is paused while waiting on an `ask`.
