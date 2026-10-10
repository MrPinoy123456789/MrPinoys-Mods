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
**Status:** Fixed. Resolved 2026-10-06 by PD-160: the lever moved off the frame onto another wall of the same cell (shape 1 below), and the far side gained its own button.

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

### PD-78: `blaze_cellar` fails to stamp because it has no climbable return path (High)

**Reported:** 2026-09-28 during live verification of the omen/Lemon changes.
**Severity:** High (blocks a run from starting whenever the room is selected).
**Status:** Fixed 2026-09-30, live roll pending. The room's "staircase" (`SituationSpecs.blazeCellar`) was a solid stone pillar at (8, z 1) with no steps, so the validator was right. It is now a ladder column at (1, z 1), y -7 to 0, hung on the north wall and clear of every doorway and the drop shaft; `blaze_cellar.nbt` regenerated with `gentemplates`. `HandlerGameTest.blazeCellarTemplateHasAReturnPath` stamps the baked template at all four rotations and runs `ReturnPathValidator`; it fails on the old template and passes on the new one. The validator is unchanged. Live check: bias `blaze_cellar` and commit a door. Found alongside: `slime_pit`'s ladder faces north against the north wall, so it has no support and would pop on a block update.
**Expected:** pulling the lever generates the dungeon behind the staging room.
**Actual:** the server throws `IllegalStateException: room pocketdungeons:blaze_cellar has spanY 2 but no climbable return path from its lower story to the upper floor (spec 13.4)` and the player sees "The dungeon failed to build. Try another door." The same room can be rolled repeatedly, so retrying the same door can keep failing.
**Live evidence 2026-09-30:** at keystone 5 the player had only door 1 available; its preview rolled `blaze_cellar`, and six commits failed at 21:28 to 21:29. With no alternate door to choose, the session ended.
**Likely cause:** the `blaze_cellar` room template is two stories tall but does not contain a ladder, scaffolding, water column, or other climbable path back to the upper floor. `LayoutStamper.stamp` validates this per spec 13.4 and aborts.
**Fix ideas:**
1. Edit the `blaze_cellar.nbt` structure to add a climbable return path (ladder or water) from the lower story to the upper floor.
2. Remove `blaze_cellar` from the room pool until it is fixed.
3. Relax the stamper validation for this room if the lower story is optional/dead-end, but that contradicts spec 13.4.
**References:**
- `LayoutStamper.java` line 246: the validation that throws.
- `src/main/resources/data/pocketdungeons/dungeon_room/blaze_cellar.json`
- `src/main/resources/data/pocketdungeons/structure/rooms/blaze_cellar.nbt`

**Workaround:** choose a different door; the three offers roll different room sets, and the failure only occurs when `blaze_cellar` is part of the selected layout.

### PD-79: `server wait` drops the player's questions to Lemon (High)

**Reported:** 2026-09-27 23:21, live session `docs/playtests/2026-09-27-4.md`. The player's question never reached the agent and timed out into the built-in "I do not know that one yet."; the player stopped the session to get it fixed.
**Severity:** High (the Lemon harness cannot hear the player; it ended a session).
**Status:** Fixed 2026-10-01, live check owed (`LIVE_CHECKS.md` L6). Diagnosis from the 2026-09-30 and 2026-10-01 logs: the original filter bug was already gone (player `lemon ask` lines wake `wait`). When the agent sat in `wait`, asks were answered in 8 to 14 s (23:16:08 to 23:16:22, 23:22:50 to 23:23:01). Every timeout had the agent busy elsewhere: replies landed 50 s to 6 min after the ask, often answering the previous question, and none was preceded by a `think`. Two real harness bugs made that worse. `Lemon.think` set `thinkingUntil` and nothing read it, so a think never held the 45 s fallback; `Lemon.fallbackDue` now waits for whichever is later (`LemonGameTest.thinkHoldsTheFallback`). `pdserver.mjs readNew` moved the cursor to the file size while parsing only whole lines, so a line caught mid-write was skipped for good; `wholeLines` now advances by the bytes consumed (`wait-filter.test.mjs`). `LEMON_AGENT.md` section 4 step 4 now tells the agent to reply or think before investigating.
**Expected:** `wait` wakes on `lemon ask <Name> ...` (the player speaking to Lemon) and ignores only the agent's own echoes.
**Actual:** `pdserver.mjs:333` filters `/^\S+ lemon (mode|say|ask|reply|think) /`. The player's line `lemon ask <Name> ...` matches, so it is dropped. The agent's echoes are logged as `lemon says|asks|replies|thinks`, which do not match, so they wake `wait` instead.
**Live evidence 2026-09-30-2:** several player asks still timed out before an llm reply, including the merchant-room, two-way merchant, and Lemon-persuasion suggestions. The active wait surfaced some asks only alongside their `lemon unanswered` fallback. 2026-10-01-1 reproduced one more timeout on the torch-drop suggestion, while later asks were answered in 8 to 12 seconds. Needs a fresh diagnosis: this may be a wait-wake bug, agent response latency, or both.
**Fix idea:** filter `/^\S+ lemon (mode|says|replies|thinks) /` plus `lemon asks` lines the agent sent, and never `lemon ask` or `lemon answer`. Then test that a player ask wakes a 240 s wait immediately and leaves enough time for `lemon reply`.
**Workaround:** poll `server chat` and re-send `lemon mode <p> llm` every minute.

### PD-80: `server wait` often reports `server is down (timeout)` while the server is up (Low)
**Status:** Fixed again 2026-09-30, verified by one live quiet wait in `2026-09-30-2.md` and two more in `2026-10-01-1.md`; keep watching during Lemon sessions. The verdict at the timeout no longer trusts the checks made during the wait: `quietVerdict` (`wait-filter.mjs`) calls the server up on any fresh proof, either an RCON `list` at the reporting point (8 s, tried twice), the log growing during the wait, log output in the last 20 s, or a held `session.lock`. `wait-filter.test.mjs` covers it. The session log showed no RCON connections at all from 21:23:28 to 21:26:58, so the failing checks never reached the server; the root cause of that is still unknown.
**History:** a 2026-09-30-1 quiet wait still printed `server is down (timeout)` while `status` immediately showed the server UP with the player online, so the 2026-09-29 fix did not hold in live use.
**Expected:** a quiet timeout on a running server prints `no new events (timeout)`.
**Actual:** in playtest 2026-09-27-5, 4 of 6 quiet `wait --player MrPinoy123456789 --timeout 240` calls printed `server is down (timeout)`, while `server status` run right after printed `UP.` and the log showed RCON clients connecting every 15 s. The player was offline the whole time.
**Likely cause:** `wait` (`pdserver.mjs`, around line 298) re-checks `up = await isUp()` every 15 s and trusts only the last result; one slow or failed `list` (3 s RCON timeout) near the deadline flips the final message. Possibly the offline `lemon mode <p> llm` refresh on the same cycle interferes.
**Fix idea:** report down only after two consecutive failed checks, or re-check once at the timeout before printing.
**Impact:** an agent following the runbook could wrongly end a session after "server down for 10 minutes".
### PD-81: `/dungeon quit` still costs 2 keystone levels; the decided cost is 1 (Medium)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-1.md`. A quit on floor 1 logged `penalty: 2` in the playtest journal.
**Severity:** Medium (economy value contradicts the 2026-09-27 owner decision; the quit confirmation shows the wrong cost).
**Status:** Fixed 2026-09-29 (configs updated; code default already 1).
**Expected:** quit on an active floor costs 1 keystone level (`timedOutDepletion`, decision 2026-09-27), and the confirmation states it.
**Actual:** cost 2.
**Likely cause:** the decision was applied only as a code default (`PocketDungeonsConfig.java:143`, `:1205`). Existing `pocketdungeons.json` files keep their stored value (`run/config/pocketdungeons.json:40` still has 2; missing-key updates never rewrite a present key), and the checked-in `config/pocketdungeons.default.json:27` still ships 2.
**Fix idea:** set `timedOutDepletion` to 1 in `pocketdungeons.default.json` and migrate or rewrite the value in existing configs, or accept per-server divergence and document it.
### PD-82: Lemon can be handed equippable items; allay give animates client-side (Low)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-1.md`. Player: "I'm able to give you items since you're an Allay, we should remove that."
**Severity:** Low (item actually lost onto Lemon only for equippable items; for anything else it is a client-side prediction that corrects itself).
**Status:** Fixed 2026-09-29 (pending restart to verify in game).
**Expected:** interacting with Lemon never transfers or equips an item.
**Actual:** `LemonBody.interact` returned PASS, so `Player.interactOn` fell through to `ItemStack.interactLivingEntity`; an item with an `equippable` component (armour, pumpkin, ...) ran `equipOnTarget` and was equipped onto Lemon's slot server-side. For non-equippable items the vanilla allay give (`Allay.mobInteract`, already bypassed) still played client-side prediction: item appears in Lemon's hand, then snaps back.
**Cause:** PASS left the item fallback path open; `LivingEntity.isEquippableInSlot` was not overridden.
**Fix:** `LemonBody.interact` now returns `InteractionResult.FAIL` (consumes the interaction, kills the item fallback) and `isEquippableInSlot` returns false. The client prediction on the vanilla-allay wire entity cannot be fully removed; the item never actually leaves the player's inventory now.
### PD-83: `breeze_arena` breezes hop off the spawn platform into lava and die (Medium)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-1.md`. Player: "The breezes just spawned, went in the lava and died."
**Severity:** Medium (the encounter resolves itself without the player).
**Status:** Fixed 2026-09-29, verified in game 2026-09-29-2 ("Breeze arena was good"; floor logged 383 durability spent on a real fight). The centre pad is now 4x4 (x,z 6..9) and the stone walkways bridge straight across the lava to it, leaving an L-shaped lava pit in each corner of the centre (12 cells, was 32). A hop off the pad lands on stone; knockback off the pad or a bridge still lands in lava. `breeze_arena.nbt` regenerated with the new `/dungeon admin gentemplates <room>` (one template, not the library). Spawns stay on stone: breeze spawn placement needs solid ground below.
**Expected:** breezes stay a threat; lava is danger for the player via knockback, not a mob disposal.
**Actual:** breezes spawn on the small platform and hop into the lava centre shortly after.
**Likely cause:** `KnowledgeSpecs.java:breezeArena` builds lava across the middle (x,z 5-10 at y 0) with a 2x2 stone platform under the spawner (comment at :446 acknowledges spawn-death only). Once the breeze jumps it lands in lava; most of the room floor is lava.
**Fix idea:** widen the spawn platform, add landing pads so a hop lands on stone, or gate the arena's lava cells.

### PD-84: GO HOME lever stands beside door 3; a player reaching for a door banked by accident (High)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-1.md`. "I accidentally pulled it, I didn't want to go home"; "I was trying to change my door selection." The 19:29 bank was a misclick (A5 regression after PD-70).
**Severity:** High (a misclick ends the interval; there was no confirm).
**Status:** Fixed 2026-09-29, verified in game 2026-09-29-2: two deliberate `home_lever` banks journal `trigger: home_lever` end to end with no misclick reported. Two changes. (1) The lever moved: `HOME_LEVER_ALONG` 5 to 2, the go-home screen from along 2..4 to 3..5, the bulb over the lever (`RoomTemplateGenerator`, `RoomProtection.isFurniture`, `DungeonScreen.HOME_SCREEN_ALONG`). The screen now sits between the lever and the doors, three clear blocks of door row on every wall; `RoomFurnitureTest` pins the gap. Not along 1: on a NORTH selector wall that is in front of the wall lodestone. (2) The pull asks first: `RitualListener` opens `DialogScreens.goHomeConfirm` (what going home banks, Go Home / Stay) for the owner; Go Home runs `/dungeon cashout`, which now plays the chime the lever used to. A non-owner still gets goHome's refusal.
**Expected:** door select and GO HOME cannot be confused, and one stray click cannot end a run.
**Actual:** on a SOUTH or WEST selector wall the viewer-mirrored HOME lever (along 5 seen, absolute 10) stood directly beside selector door 3 (absolute 9), which keeps its absolute slot; the lever banked on the first pull.
**Cause:** PD-70 mirrored the levers but not the doors, so on mirrored walls the one free block in the row fell on the DESCEND side and GO HOME touched the doors. `RitualListener` called `RunLifecycle.goHome` directly on the pull.

### PD-85: The door-preview glass sits behind the selector doors; the doors block the view (Medium)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-2.md`. Player: "It's kind of awkward having the preview glass behind the doors since the doors cover up what I can see. We should move the glass and ensure that there isn't a wall blocking the preview into the room from the new glass position."
**Severity:** Medium (the preview's job is showing the next room; the doors occlude exactly that sight line).
**Status:** Fixed 2026-09-29 (pending in-game look). The preview now also cuts a side window, 1 wide by 3 tall, at absolute along 6 (`RoomBuilder.PREVIEW_WINDOW_ALONG`, the window band's column) through both the staging room's wall and the entrance cell's wall, so the glass is beside door 1, not behind the doors. Along 6 is the one column beside the door slot that nothing stands in front of on every selector wall (the commit lever and go-home control are viewer mirrored and land at 5 or 10 and 2..5 or 10..13); `RoomFurnitureTest.testPreviewSideWindowIsClear` pins that. The door-slot glass stays as the seal (its top course is still a view). `clearPreview` hands the staging half back to the shell wall; a commit seals the staging half and patches the entrance half by copying the course beside it.
**Expected:** the preview window is beside, above, or otherwise offset from the doors so the whole glass shows the previewed room, and nothing but glass stands between the player and the room.
**Actual:** the glass pane is directly behind the selector doors, so a closed door covers most of the window.
**Fix idea:** move the preview window out of the door row (above the doors, or on a wall segment between selector wall and HOME screen), and verify the wall on the far side does not block the view into the room either.

### PD-86: `thicket` cave spider spawner never fires (Medium)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-2.md`. Player spent ~3 min in the room (21:37:23 to 21:40:37): "the spawner in the room filled webs doesn't spawn anything."
**Severity:** Medium (the room's only encounter is dead).
**Status:** Fixed 2026-09-29, verified in game 2026-09-29-3 ("yes in a good way, it works": cave spiders spawn; the room is now "pretty difficult"). Root cause found by reading, not by the suspects below: `TraversalSpecs` registered its situation handlers (`thicket`, `ice_run`, `flooded_hall`, `chasm`, `powder_snow_field`) in a `static {}` block, and on a live server nothing initialises that class except `/dungeon admin gentemplates` (`TraversalSpecs.list`). So `Situations.isRegistered("thicket")` was false, the cell fell through to the plain corridor dispatch, and `ClassicSpawners.configure` never ran: the baked zombie spawner (confirmed in `thicket.nbt` at 8,1,8) stayed a zombie spawner with no custom rules in a lit room. The same bug silenced `ice_run`'s breezes and `flooded_hall`'s iron doors and drowned. Now `TraversalSpecs.registerHandlers()` runs from `TrialContent.warmUp()` like every other family. Gametests `HandlerGameTest.traversalHandlersAreRegistered` and `thicketSpawnerBecomesCaveSpider` pin it.
**Expected:** the classic spawner produces cave spiders while a player is in range.
**Actual:** nothing spawned.
**Likely cause:** unknown. `ClassicSpawners.configure` rewrites the baked spawner to `CAVE_SPIDER` with `custom_spawn_rules` 0..15 at stamp time (PD-68), and the tuning caps (`SpawnCount` 2, delays 200 to 400) should fire within seconds. Suspects: the spawn-position check failing inside the cobweb checkerboard, the baked block not being a `SPAWNER`, or the spawn rules not applying. Verification attempt in `2026-09-29-2.md` could not inspect the block entity before the cells released; a clone-scan of the suspected cell found no spawner block at all, so template placement is also in doubt.
**Fix idea:** stamp a thicket on a test slot and `/data get` the spawner's block entity; check `SpawnData` id and rules, then watch whether `BaseSpawner` attempts fire.

### PD-87: A trial key does not stack with other trial keys and does not work (Medium)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-2.md`. Player: "I have trial key in my inventory that doesn't work and doesn't stack with the other ones."
**Severity:** Medium (a duped-looking, unstackable key reads as broken loot).
**Status:** Fixed 2026-09-29. Cause confirmed: the nine chest tables (`chests/tier_N`, `_drowned`, `_ominous`) and two spur vault tables stamped `custom_data {pocketdungeons:{bag:1}}` on `minecraft:trial_key`, while trial spawners eject a bare key (`spawners/trial_key`) and the vault's `key_item` is a bare key (`TrialContent.keyStack`). A vault matches item and components, so the tagged key opened nothing, and the two kinds never stacked. The bag tag on keys only fed an informational "untagged stack" notice. All eleven entries are bare now; `TrialKeyLootTest` walks every loot table and fails on any key entry with a function. Keys already in packs are stripped of custom data when used on a vault (`RitualListener`, `TrialContent.bareKey`).
**Expected:** all trial keys are identical items that stack and open vaults.
**Actual:** at least one key carries different data: it refuses to stack and "doesn't work".
**Likely cause:** a loot source writes a component onto the key (a name, tag or custom data) that vanilla keys lack; non-identical components block stacking, and if the vault checks the key by components the odd key may also fail to open.
**Fix idea:** find every loot path that creates `minecraft:trial_key` (vault `keytag`, chest loot tables, mob drops) and confirm they emit a bare item; check what the vault requires.

### PD-88: The explosive hazard's plate support makes the trap untriggerable by accident (Low)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-2.md`. Player: "Why is there a stone block between the tnt and the pressure plates, I won't accidentally step on the tnt like this" and later "the stoneblock between the tnt is a bug".
**Severity:** Low (a hazard that can never catch anyone is decoration, but it does not break a run).
**Status:** Fixed 2026-09-29 (pending in-game look). The stone block was not the intended design but its fallback: `findExplosiveSupport` looked for an air neighbour at floor level, which a solid floor never has, so every hazard stacked stone on the TNT with the plate on top. Now the plate lies flush on a conductive floor block beside the TNT (`RoomContent.findFlushPlate`): the pressed plate strongly powers that floor block, which ignites the TNT next to it, and the plate still rests on ordinary floor when the TNT turns into a primed entity. A TNT with no qualifying neighbour is skipped, never given a pillar. The plate watch that removes a triggered plate without a drop used to lapse 30 s after the stamp (long before a player arrives); it now lasts an hour and ends when the chunk unloads. Floor obstruction scatter: not done, since there is no decor pass to hang it on; noted in the playtest file for the room pass.
**Expected:** the explosive hazard can catch a careless player.
**Actual:** `RoomContent.placeExplosiveHazards` puts the pressure plate on a stone support block adjacent to the TNT (so the plate survives ignition), which reads as a separated, avoidable mechanism.
**Fix idea:** hide the support (put the plate flush at floor level over the TNT, or embed the support in the floor) while keeping the plate alive after ignition; or replace the readable pair with a floor that gives way. The player also suggested scattered floor obstructions as general room texture, worth a separate decor pass.

### PD-89: Crafted diamond gear sidesteps the durability economy (Medium)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-3.md`. Player: "this diamond sword I've crafted has lasted a really long time"; "i've used it for at least a few floors and it's hardly damaged."
**Severity:** Medium (one crafted item removes the weapon wear loop that the loot tables are tuned around).
**Status:** Fixed 2026-09-30, pending in-game verify. Not a design gap: a craft-path leak. The mod already caps crafted and dropped weapons and armour (`DungeonTools.durabilityCap`: diamond 96 each, vanilla 1561 sword, 363 to 528 armour). The player's save showed both crafted diamond swords with no `max_damage` (full 1561, damage 90) while his diamond armour and dropped iron sword carried the caps (96, 64). Cause: shift-clicking a result runs `CraftingMenu.quickMoveStack`, which moves the stack without `ResultSlot.remove`, the only place `ResultSlotMixin` capped it. Fix: `CraftedDurabilityMixin` caps in place at `Item.onCraftedBy`, which that path calls on the stack just before moving it (and every other craft path calls too); `DungeonTools.capInPlace`. The armour counterpoint is the cap working as tuned: 96 per piece against vanilla's 363 to 528, drained a point or more per hit taken, so it goes about four to five times faster than vanilla. Swords already crafted before the fix keep 1561 until they break.
**Expected:** crafted gear competes with dungeon drops inside the same churn.
**Actual:** a vanilla diamond sword (~1500 durability) outlasts every dungeon drop, so once the player crafts one the short-lived-gear economy stops applying to their main weapon.
**Counterpoint:** same session, "my diamond armour broke VERY quickly", "probably combat". Armour drains a point per hit taken, so the sword's longevity may be vanilla asymmetry rather than a mod issue on its own; check whether the armour drain rate is vanilla before retuning either direction.
**Fix idea:** decide the intended ceiling: shorter-lived crafting results inside the dungeon, a durability tax on crafted gear at the anvil/kit level, or accept the leak and rebalance drops around it.

### PD-90: `room_bias` has no lockout, slow apply feedback, no timeout, and the door sign does not show it (Low)

**Reported:** 2026-09-29, live session `docs/playtests/2026-09-29-3.md`. Player (after consenting to a bias and watching it take ~2 min because the MCP server was mid-restart): "next time you use the generation-bias feature, you should lock me out of starting the next run while you set it up, do it faster and have an appropriate timeout time in case you get stuck, also have the sign above the doors reflect this."
**Severity:** Low (harness feature; the gap between consent and application let him reach the door while setup was still in flight).
**Status:** Fixed 2026-09-30, pending in-game verify. (1) Lockout: `/dungeon admin bias hold <player> [seconds]` (MCP `room_bias_hold`) refuses door preview and commit with the seconds left. (2) Feedback: setting or clearing a bias releases every hold and tells each player choosing a door what changed ("Lemon has set up the next floor: it leans toward thicket (x20)"); the server side was already instant, the 2 minutes were the MCP restart. (3) Timeout: a hold defaults to 90 s, is capped at 180, and runs out on its own with a message; the MCP bias calls now time out at 10 s. (4) Door screen: a countdown line while held, then "Leaning toward: thicket x20" while a bias is in force, refreshed on every change. Also closed a gap the report implied: a preview planned before the bias changed is refused at commit ("Right-click the door again"), since a preview plans its floor when it opens.
**Expected:** while a bias is being arranged the next door is held; the bias applies quickly or fails loudly inside a timeout; the staging room sign shows what is currently biased.
**Actual:** the bias is applied at once with no player-facing hold or acknowledgement, and nothing in the staging room shows a bias is in force.
**Fix idea:** a `bias` flag the door/commit path checks (hold commit while set, or show "preparing"), plus a sign line listing active biases; on the MCP side a bounded apply so a stuck agent does not leave the player waiting.

### PD-91: Rising Lava never armed in a real run; its lava never spread (Medium)

**Found:** 2026-09-30, moving the room handlers onto the Ordeal framework (`docs/reference/ORDEALS.md`).
**Severity:** Medium (a whole room's danger silently missing; nobody had seen the room work in play).
**Status:** Fixed 2026-09-30, pending in-game verify.
**Actual:** the `rising_lava` situation handler returned `null` without calling `RisingLavaHandler.arm`, and nothing else armed it. Only `HandlerGameTest` did, so the tests passed while live rooms stayed inert: no lava, a lever that did nothing.
**Fix:** the room is now `RisingLavaOrdeal`, armed from `LayoutStamper.applyDirectionalGates` right after its lever is placed on the exit side. The baked lever came out of the template: at the old spot it faced into the open exit doorway, and a layout turned round would have put it at the entrance. Note for the next playtest: this is the first time the lava actually runs, filling the room in about seven seconds unless the lever is reached.

### PD-92: Max-omen ejection duplicates interval-start items into the kept pack (High)

**Reported:** 2026-09-30, live session `docs/playtests/2026-09-30-1.md`. Player: "some of my items may have been duplicated between either server resets or when the dungeon closed while I was away."
**Severity:** High (real item duplication, confirmed from Lost and Found records).
**Status:** Fixed 2026-09-30, verified in game 2026-10-01. `restoreIntervalSnapshot` restores only the live inventory; the leave that follows the ejection keeps it once, with the record's existing loose stacks carried along (the old write also wiped those). A cursor stack with no free slot is kept loose instead of dropped. `InventorySwapGameTest.maxOmenEjectionKeepsEachStackOnce` reverts, leaves and re-enters and counts every stack (including one that must go loose and an earlier loose stack); `FloorIntervalGameTest.maxOmenDeathOnFirstIntervalRevertsInventory` now checks the kept pack after the leave, sword exactly once. The offline-member branch of `failRunOmen` still writes the snapshot over the record, but a disconnect detaches the member first, so it should be unreachable. Copies already duplicated in a player's pack are not removed. Live pass: the 2026-10-01T08:10:53Z max-omen `LEAVING` record and the inventory after 08:11:08 re-entry contain each recorded stack once.
**Expected:** a max-omen run failure restores the interval-start dungeon inventory once, then stores that same pack for the next entry.
**Actual:** the 2026-10-01T04:23:11Z `LEAVING` Lost and Found record contains the interval-start stacks plus copies of the first six non-empty stacks in later free slots: oak log, shears, diamond sword, cooked beef, arrows and a bow. The 2026-09-30T06:18:31Z `LEAVING` record has each stack once. The player's live inventory after re-entry shows the same duplicated-looking result.
**Likely cause:** `Instances.failRunOmen` calls `InventorySwap.restoreIntervalSnapshot` before `eject`. `restoreIntervalSnapshot` clears and restores the live inventory, then writes the same snapshot into `log.setOrphan` (`InventorySwap.java` lines 768 to 778). The subsequent `leaveVoid` snapshots that restored inventory and calls `keptInventory(voidInventory, log.orphanOf(...).items(), ...)`. Because the live inventory and `alreadyHeld` now contain the same stack in the same slot, `keptInventory` keeps the live stack in place and appends the held copy as loose (`InventorySwap.java` lines 465 to 489). The next `restoreKept` places those loose copies into free slots.
**Fix idea:** on the max-omen path, do not let the snapshot restored by `restoreIntervalSnapshot` remain in `alreadyHeld` when `leaveVoid` merges the pack. Either have `leaveVoid` ignore an `alreadyHeld` stack identical to the live stack in that slot, or split `restoreIntervalSnapshot` into a live restore used before ejection and a record write used only when the player is offline. Add a test that a max-omen ejection followed by leave and re-entry produces exactly one copy of each snapshot stack.

### PD-93: An anomaly room in the plan cannot stamp because the stamper reads the wrong manifest (High)

**Reported:** 2026-09-30, live session `docs/playtests/2026-09-30-2.md`.
**Severity:** High (about 8 percent of themed plans can roll an anomaly room, then every commit on that door fails).
**Status:** Fixed 2026-10-01, live commit pending. `LayoutStamper.entryAt` resolves the anomaly cell's room from `RoomManifest.currentAnomaly()` (falling back to the themed manifest), and the stamp loop, `cellSpanY` and the window band use it. `Instances` (the floor's room list) and `ContentReload` (stale-preview check) do the same, so a `/reload` no longer orphans a preview holding an anomaly room. Found alongside: a recipe guarantee applied after `rollAnomaly` could overwrite the anomaly cell and leave `anomalyCell` pointing at a themed room; `RoomSelector` now clears it in that case. `HandlerGameTest.anomalyRoomStampsFromTheAnomalyManifest` plans frostworks seeds until one rolls an anomaly room, proves the room is absent from the themed manifest, and stamps the whole plan.
**Expected:** a plan whose anomaly roll selects an anomaly room stamps that room from `RoomManifest.currentAnomaly()`.
**Actual:** floor 2 preview (frostworks, ominous, level 6) rolled `pocketdungeons:anomaly_store_straight`; three commit attempts at 22:05:34, 22:05:36 and 22:06:11 threw `IllegalStateException: manifest has no room named pocketdungeons:anomaly_store_straight` and showed "The dungeon failed to build. Try another door."
**Likely cause:** `RoomSelector.rollAnomaly` mutates `placed` with a `RoomManifest.currentAnomaly()` match (`RoomSelector.java` lines 156 to 189), but `LayoutStamper.stamp` resolves every placed room through `RoomManifest.current()` (`LayoutStamper.java` lines 220 to 223). Anomaly rooms are stored in the separate anomaly manifest, so the lookup always fails.
**Fix idea:** resolve `entry` from `RoomManifest.currentAnomaly()` when `cell.equals(plan.anomalyCell())`, or merge the anomaly manifest into the stamp-time lookup only for anomaly cells. Keep anomaly-specific processor and loot rules working. Add a stamper test that stamps a plan with an anomaly cell and proves `anomaly_store_straight` resolves from the anomaly manifest.

### PD-94: Ominous loot grants inert Boss Stones when Kamu Totems is absent (Medium)

**Reported:** 2026-09-30, live session `docs/playtests/2026-09-30-2.md`.
**Severity:** Medium (a reward looks important but has no behavior on a Pocket Dungeons-only server).
**Status:** Fixed 2026-10-01 by removing the integration (owner decision). The eight Boss Stone entries (`kamutotems:{boss_stone}` echo shards) are gone from `chests/tier_*_ominous.json` and `vaults/tier_*_ominous.json`; their weight falls to the rest of each pool. `LootTagGameTest.noLootCarriesAnotherModsToken` fails if any loot table names `kamutotems` again. `LIVE_TEST_PASS.md` 4.3 no longer expects a Boss Stone. Boss Stones already in packs stay inert echo shards.
**Expected:** a named boss-fight token either works in the current mod set or is not offered as dungeon loot.
**Actual:** ominous loot granted an echo shard named Boss Stone. The player reported it "does nothing". The server mod list has Pocket Dungeons, Fabric API, and sgui, but no Kamu Totems. `kamutotems/INTEGRATION.md` documents the `kamutotems.boss_stone` custom-data contract, while Pocket Dungeons ominous chest and vault tables still emit those tokens (`src/main/resources/data/pocketdungeons/loot_table/chests/tier_*_ominous.json`, `src/main/resources/data/pocketdungeons/loot_table/vaults/tier_*_ominous.json`).
**Likely cause:** the stranger-mod integration is optional, but the loot tables do not condition or feature-flag the Boss Stone entries on that mod being present.
**Fix idea:** add an explicit integration switch or detect the optional item contract at startup and omit Boss Stone pools when unavailable. If keeping the items for future integration, lore should clearly say they are dormant on this server. Add a loot validation warning when Kamu Totems tokens are enabled but the companion mod is absent.

### PD-95: Bag-tagged common loot does not stack with identical plain loot (Medium)

**Reported:** 2026-09-30, live session `docs/playtests/2026-09-30-2.md`.
**Severity:** Medium (inventory churn and false duplication signals on normal materials).
**Status:** Fixed 2026-10-01, partially verified in game. Only items that stack to 1 carry the bag tag now: 645 bare `{pocketdungeons:{bag:1}}` entries on stackable items were stripped across 72 loot-table files (gear, and tokens carrying a tier, shell unlock or cube reward, keep theirs). `KitTopUp` tags only unstackable grants. On entry, `InventorySwap.withoutStackableBagTag` strips the tag from stackables in the kept pack, so old split stacks become identical and combine by hand; the 2026-10-01 max-omen save shows stackables restored without fresh `bag:1` splits. The leave-time stray notice checks unstackable items only. `LootTagGameTest` walks every shipped table against the live item registry and checks the strip; `BagTableTest` now requires the tag on bag tools and forbids it on stackables. `tools/gen_themed_content.py` follows the same rule, but it is stale against the shipped tables (spawner weights and pools were hand edited since), so do not rerun it blind. Not changed: the spec's `max_stack_size` 8 caps still keep capped loot from merging with vanilla 64-stacks. Still pending: a fresh reward and an identical existing stack merging in live play.
**Expected:** two otherwise identical stacks of common loot, such as iron ingots, stack together when the player wants them to.
**Actual:** a vault granted 3 iron ingots carrying `custom_data.pocketdungeons.bag = 1`, while a separate stack of 14 plain iron ingots stayed unmerged. Earlier in the same session a tagged 2-ingot stack also failed to merge with 15 plain ingots. A source sweep found 860 `bag: 1` reward entries across 75 loot-table files, plus kit top-up tagging in `KitTopUp.java` lines 378 to 385, so this is systemic rather than one bad table.
**Likely cause:** the M49 bag tag uses visible `custom_data`, which makes components differ and prevents stacking. The tag exists so `InventorySwap.isOurs` can distinguish mod-granted stacks (`InventorySwap.java` lines 593 to 615), but it has the same player-visible side effect documented for trial keys in PD-56 and PD-87.
**Fix idea:** replace the item-component provenance tag with out-of-band run loot tracking, or exempt ordinary stackable materials from tagging and teach `isOurs` a narrower provenance rule. If no safe alternative exists, merge tagged common materials into matching plain stacks after the ownership check. Add a regression test that a tagged material reward merges with an identical plain stack without weakening the survival-inventory boundary.

### PD-96: Salvage Bench is implemented but has no in-game discovery path (Medium)

**Reported:** 2026-09-30, live session `docs/playtests/2026-09-30-2.md`.
**Severity:** Medium (the economy feature exists, but a normal player cannot discover it without outside knowledge).
**Status:** Fixed 2026-10-01, live use observed but discovery still unverified. In the dungeon dimension, any non-sneak use of the salvage block opens the bench, whatever the hand holds (below the unlock level an empty hand still gets the vanilla grindstone). Sneaking is the vanilla grindstone, and the summary says so. A Disenchant button (slot 26) lights up when exactly one enchanted item sits alone in the bench and moves it into a real `GrindstoneMenu`. Outside the dungeon every grindstone is vanilla, including with gear or a key in hand (owner decision 2026-10-01). `SalvageGameTest.anyUseOpensTheBenchAndASneakDoesNot` and `disenchantNeedsOneEnchantedItemAlone` cover it. 2026-10-01-1 logged one successful 1 XP salvage, but the player already knew the feature, so it does not prove first-time discovery.
**Expected:** the grindstone tells the player that it can salvage, or any ordinary non-sneak use opens a screen that presents salvage clearly.
**Actual:** the player asked whether the salvager was in the game, then said there was no indication in game. `SalvageStation.onUse` only claims the click when the main hand already holds an accepted item; an empty hand or unrelated item falls through to the vanilla grindstone. After the interaction was explained, four salvage events paid correctly.
**Likely cause:** `SalvageStation.java` lines 143 to 159 intentionally refuse the click unless `classify(held).takes()`, and no room text, tooltip, guide line, or nearby sign explains the alternate use.
**Fix idea:** open the Salvage Bench on any non-sneak grindstone use, then include vanilla disenchanting as a separate action when exactly one disenchantable item is present. If preserving vanilla access matters more, add an obvious prompt near the grindstone and a first-use hint when the player holds eligible loot. Add a use-block test for an empty hand and a loot-table or journal test proving a first-time player sees the hint.

### PD-97: `sump` fails to stamp because it has no climbable return path (High)

**Reported:** 2026-10-01, live session `docs/playtests/2026-10-01-1.md`.
**Severity:** High (blocks the selected door whenever the room is rolled).
**Status:** Fixed 2026-10-01, live commit owed (`LIVE_CHECKS.md` L1). The cause matched PD-78: `SituationSpecs.sump` built a solid stone pillar at (8, z 1) and called it a staircase. It is now a ladder column at (14, z 1), y -7 to 0, on the north wall, clear of the NW water source, the east doorway and the drop shaft; `sump.nbt` regenerated with `gentemplates sump`. `HandlerGameTest.sumpTemplateHasAReturnPath` checks all four rotations, and `everyMultiStoryRoomHasAReturnPath` stamps every `spanY > 1` room in the manifest at every rotation, so a third room with this defect fails the build. Validator unchanged.
**Expected:** pulling the commit lever generates the dungeon behind the staging room.
**Actual:** the level 9 basalt_foundry preview committed at 00:51:17 and threw `IllegalStateException: room pocketdungeons:sump has spanY 2 but no climbable return path from its lower story to the upper floor (spec 13.4)` at `LayoutStamper.java:246`. The player saw "The dungeon failed to build. Try another door." The follow-up basalt_foundry commit at 00:52:00 succeeded, so the failure is room-specific.
**Likely cause:** the `sump` template is two stories tall but lacks a ladder, water column, staircase, or other return route accepted by `ReturnPathValidator`. This is the same defect class as PD-78, on a different room.
**Fix idea:** inspect `src/main/resources/data/pocketdungeons/structure/rooms/sump.nbt` and the `sump` situation spec. Add a real return path and regenerate the template, or remove `sump` from the selectable pool until repaired. Add a `ReturnPathValidator` regression test for `sump` at all rotations, like the `blaze_cellar` test.

### PD-98: `kennel_crossing` situation room stamps with no spawner at all (High)

**Reported:** 2026-10-01, live session `docs/playtests/2026-10-01-2.md`. Player: "Why is nothing spawning from this spawner?"
**Severity:** High (an encounter room provides no fight and no clear-gate spawner whenever it rolls).
**Status:** Fixed 2026-10-01, pending in-game verify. The 21:27:11 log line was not kennel_crossing: the kennel stamped a spawner fine (no warning at 20:44). It was `blaze_cellar`, whose spawner sits at y -8 in the lower story, below the range `TrialContent.cellBlockEntities` scanned (dy >= 0), so no anchor was found. The range now reaches down through a two-story room. The kennel's real defect: a trial spawner runs the mob's own placement rules, and `Wolf.checkWolfSpawnRules` needs `WOLVES_SPAWNABLE_ON` ground, so on stone brick the spawner never produced a wolf. The pen floor is now grass and `kennel_crossing` spawn_range is 1, so the pack spawns inside the pen and the gate is the release. Template recaptured. Gametests `situationCombatRoomsStampATrialSpawner` and `kennelPenLetsWolvesSpawn`.
**Expected:** `kennel_crossing` places a working trial spawner (wolves) like its sibling situation rooms.
**Actual:** the player stood in `kennel_crossing` (infestation floor 1, cell -2,-1) and nothing spawned. The server log confirms the room stamped with no spawner: `Situation encounter cell at 160, 64, 512 has no spawner anchor; no trial spawner placed` at 21:27:11 local.
**Likely cause:** `SituationSpecs.kennelCrossing` declares `.spawner(new BlockPos(8, 1, 8))` but no `.spawns()` anchor list and the regenerated NBT carries no authored trial spawner. In `TrialContent.applyEncounter` (`TrialContent.java` lines 293 to 327), `authoredTrialSpawners` finds nothing, `encounterAnchor` returns null, the warn fires, and the method returns without placing anything. The early return also skips `clearClassicSpawners`, so the classic spawner (if stamped by the spec) may sit unconfigured. The same defect class probably affects `sensor_gallery` and `blaze_cellar` if their NBTs lack authored trial spawners too.
**Fix idea:** either give the situation NBTs authored trial spawner blocks, or fall back to the spec's `.spawner()` position when `encounterAnchor` finds no spawn list. Add a stamper test that stamps each situation combat room and asserts a configured trial spawner exists.

### PD-99: `rotation_lock` comparator appears not to open the door (Medium)

**Reported:** 2026-10-01, live session `docs/playtests/2026-10-01-2.md`. Player: "I think the comparator was backwards and wasn't working, also there was a redstone block, I think the room design is not correct."
**Severity:** Medium (a mechanism room may be unsolvable in some orientations).
**Status:** Fixed 2026-10-01, pending in-game verify. The player was right: a comparator or repeater `FACING` is the INPUT side in vanilla, and the room passed the output direction. The comparator read the output dust instead of the frame, and the door repeater took input from the wall block. Both now face WEST. Not a rotation problem. Template recaptured. Gametest `rotationLockSolvesAtEveryRotation` (frame on 8 opens the door, frame on 1 does not, all four rotations). Same inversion suspected in `sorting_floor` (`SituationSpecs` comparator at 14,1,9 facing NORTH, chest to its south), `barred_vault`, `ominous_bargain` and `the_altar` (`SpurSpecs`); not touched, see PD-104.
**Expected:** rotating the item frame to position 8 passes the comparator subtract check and opens the door, at every room rotation.
**Actual:** the player clicked through rotations and the door did not open; he suspects the comparator faces the wrong way.
**Likely cause:** `MechanismSpecs.rotationLock` (around line 343) places the comparator, redstone block and dust at fixed offsets and facings; if the room template or stamp rotates the cell, the comparator or the side input may not rotate with it. Needs live inspection of a rotated stamp or a gametest that solves the room at each rotation.
**Fix idea:** stamp `rotation_lock` at all four rotations in a gametest, set the frame to rotation 8, and assert the door opens.

### PD-100: Omen sensor line flashes too briefly to read (Low)

**Reported:** 2026-10-01, live session `docs/playtests/2026-10-01-2.md`. Player: "There was text saying 'A sculk stalks my steps' or something but it went away before I could read it all."
**Severity:** Low (readability; the mechanic's only explanation is unreadable).
**Status:** Fixed 2026-10-01, pending in-game verify. The sensor line is repainted on the action bar every 2 seconds for 8 seconds (`OmenBarText.holdTicks`, `OmenBar.repaintHeldLine`).
**Expected:** the SENSOR omen line ("The sculk counts your steps; the next fight will be harder.", `OmenBarText.java:116`) stays up long enough to read, or repeats while sensors keep pulsing.
**Actual:** the line disappears before the player can finish reading it.

### PD-101: Salvage Bench swallows the held item on open (Medium)

**Reported:** 2026-10-01, live session `docs/playtests/2026-10-01-2.md`. Player: "Now that the Salvage Bench opens up on right-click, it shouldn't automatically put my item in hand into the station, it should just open the station."
**Severity:** Medium (surprise item movement on a UI open; risks accidental salvage of held gear).
**Status:** Fixed 2026-10-01, pending in-game verify. `SalvageStation.onUse` no longer moves the held stack; it only opens the bench. Gametest `openingTheBenchLeavesTheHeldItemAlone`.
**Expected:** a plain right-click opens the bench UI without touching the held stack; depositing an item is a deliberate second action.
**Actual:** right-click with an item in hand places it into the bench as part of the open.

### PD-102: Commit stamp failed behind the staging room, then disconnect in PREVIEW (High)

**Reported:** 2026-10-01, earlier session digest (`docs/playtests/2026-10-01-2.md` summary; journal 07:41 to 08:13 UTC).
**Severity:** High (a failed commit plus a disconnect during preview).
**Status:** Fixed 2026-10-01 (cause was PD-97). The logged exception is `IllegalStateException: room pocketdungeons:sump has spanY 2 but no climbable return path` (LayoutStamper.java:246) at 00:51:17 local; the sump ladder fix already covers it, and `everyMultiStoryRoomHasAReturnPath` guards the class. The failure path restores the catalyst and tears down the half-stamped cells; the disconnect at 01:13 was a normal close of slot 0 (log shows "member disconnected"). No separate preview-state bug found.
**Expected:** committing a door always stamps or fails with a clear player-facing reason; disconnects during PREVIEW clean up gracefully.
**Actual:** log shows `Commit stamp failed behind the staging room at 0, 64, 16` (`Instances.java:1410`) at 07:51, and the player disconnected while still in PREVIEW at 08:13.
**Likely cause:** unknown; `Instances.java` around line 1410 logs this when the staging-room commit stamper throws. Needs the exception text from that log window.

### PD-103: Blacksmiths wander into the staging room and multiply (Medium)

**Reported:** 2026-10-01, owner report. Player: blacksmiths are entering the staging room and triggering new blacksmiths to spawn, so several exist at once.
**Severity:** Medium (NPC duplication; also means a blacksmith can loiter in the staging room where it does not belong).
**Status:** Fixed 2026-10-01, pending in-game verify. `BlacksmithNPC.findAllBlacksmiths` now scans 24 blocks each way horizontally from the room centre (was 12), which covers the staging room, so an escapee is found, anchored and deduplicated. Gametest `blacksmithSweepSeesIntoTheStagingRoom`.
**Expected:** exactly one blacksmith per room with a smithing table, anchored near the table.
**Actual:** multiple tagged blacksmiths accumulate when one wanders into the staging room.
**Likely cause:** `BlacksmithNPC.sweep` finds existing blacksmiths with `findAllBlacksmiths`, which scans an `AABB` of `SCAN_RADIUS` 12 around the room centre (`BlacksmithNPC.java` lines 190 to 196). A blacksmith that wanders out through the door into the staging room leaves that box. The sweep then sees `blacksmiths.isEmpty()` and spawns a replacement (line 146), while the escaped villager is never found again: the duplicate cleanup at line 150 only discards extras inside the scan box, and `anchorBlacksmith` only pulls back the first found. The staging room's adjacency makes "past the door" reliably outside 12 blocks of the room centre. The class javadoc already anticipated duplicates "if the villager wandered out of the scan radius and a new one spawned", but the fix only dedups inside the same too-small box.
**Fix idea:** enlarge the search to cover the staging room too (or find blacksmiths by tag across the whole instance cell block rather than a radius around centre), anchor by distance to the door threshold before it crosses, or give the blacksmith `setNoAi` when it approaches the staging doorway. A gametest could simulate a blacksmith entity just past the door, run the sweep, and assert it is discarded rather than left while a second one spawns.

### PD-104: other rooms wire comparators and repeaters backwards (Medium)

**Reported:** 2026-10-01, found while fixing PD-99.
**Status:** Open, unverified in play.
**Likely cause:** vanilla `FACING` on a comparator or repeater is the input side. `SituationSpecs.sortingFloor` (comparator at 14,1,9 facing NORTH, chest at 14,1,10 to its south), `SpurSpecs.barredVault` and `ominousBargain` (comparator at 9,1,10 facing NORTH, chest to its west) and `theAltar` pass an "output direction" like `rotation_lock` did. If these doors are meant to open from the redstone rather than from `Locks`, they never will.
**Fix idea:** check which of these rooms `Locks` already drives; for the rest, flip the facing, recapture, and add a solve gametest like `rotationLockSolvesAtEveryRotation`.

### PD-105: Lemon's journal can be taken from her menu (Low)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "correctly has the book, but when I right clicked on her, she gave me the book. I should not be able to take her diary/journal."
**Severity:** Low (cosmetic, but lets the player remove Lemon's prop).
**Status:** Fixed 2026-10-02 (second pass), pending in-game verify. Cause (review 2026-10-02, F4): client-side prediction. The client knows Lemon only as a vanilla allay, so an empty-hand click runs vanilla `Allay.mobInteract` on the client, which empties her hand and adds the journal to the player's hotbar on their screen alone. Nothing moved on the server, which is why the gametest could not see it; it began when Lemon was given the `WRITABLE_BOOK`. `LemonBody.interact` now resyncs the player's slots and Lemon's held item after every click (`undoClientPrediction`). First pass notes: the current `LemonBody` already refuses every vanilla hand-off path (`interact` opens the menu in the main hand and FAILs everything else; the lodestone menu is a dialog with no item slots), so the cause could not be reproduced in the tree. New gametest `lemonKeepsHerJournalWhateverTheClick` clicks Lemon with either hand, empty and holding an item, and asserts the book stays and nothing is handed over. If a player still gets the book, capture the exact click and what the menu showed.
**Expected:** right-clicking Lemon opens her lodestone menu / journal UI, but the book item is locked in place (or the menu is read-only).
**Actual:** the player can take the book out of the menu.

### PD-106: Mob spawners drop too much trimmed armour (Medium)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "The mobs from this spawner dropped A LOT of trimmed armour. Too much armour in general too."
**Severity:** Medium (loot inflation; trimmed armour loses its special identity).
**Status:** Fixed 2026-10-02, pending in-game verify. Cause: five tier 3 spawner configs (`copper_works`, `crypt`, `frostworks`, `ossuary`, generic `tier_3`) equipped mobs from vanilla `equipment/trial_chamber_*` (full trimmed armour) and `DungeonDrops` floored every slot at a 0.2 drop chance. Now all spawners use our own untrimmed `equipment/tier_3_*` tables and armour slots floor at `ARMOUR_DROP_CHANCE` 0.08. Gametest `spawnerEquipmentIsOursAndExists`.
**Expected:** armour drops are paced so trimmed pieces feel rare and valuable.
**Actual:** one combat room produced multiple trimmed armour pieces.

### PD-107: A single spawner mixes unrelated mob types (Medium)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "This spawner was spawning zombies, breezes, spiders, skeletons, just too many different kinds of mobs. On occasion this is okay like a 'chaotic' spawner but the spawners should typically be themed."
**Severity:** Medium (theme readability; the room's danger becomes noise).
**Status:** Fixed 2026-10-02, pending in-game verify. Cause: themed spawners were already two mob types, but themeless rooms (themes `blackstone`, `drowned_vault`, `prismarine`, and any room with no theme prefix) used the broad generic `tier_N` pool (tier 3 had 11 mob types). New narrow families `undead`, `bones`, `spiders` (two related mobs each, three tiers) are picked per cell by `TrialContent.genericPrefix`; `chaoticSpawnerChance` (default 0.05) keeps the broad pool as an explicit chaotic spawner.
**Expected:** a non-chaotic spawner in a themed room draws from a narrow, theme-appropriate pool.
**Actual:** one spawner produced zombies, breezes, spiders and skeletons together.
**Likely cause:** either a generic spawner config is being reused across rooms, or a room-specific config was made too broad. The player is fine with a rare "chaotic" exception, but ordinary rooms should read as themed.

### PD-108: Some gear cannot be salvaged (Medium)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "There's gear like the diamond leggings in my inventory that I can't salvage."
**Severity:** Medium (feature inconsistency; player has a sink but some loot refuses it).
**Status:** Fixed 2026-10-02, pending in-game verify. Cause: almost certainly trimmed vanilla trial chamber armour, which the bench refuses on purpose. Trimmed armour is still refused, but the reason now reads "trimmed armour is kept safe, never scrapped" (and the other refusals say why too), and the PD-106 fix stops most trimmed drops. Gametest `plainDiamondLeggingsSalvageAndTrimmedAreExplained`.
**Expected:** all dropped/looted gear that the player would reasonably want to convert is accepted by the Salvage Bench.
**Actual:** diamond leggings were rejected.
**Likely cause:** `SalvageStation` classifies items by some rule (durability threshold, tag, origin) that excludes certain drops. Check `SalvageStation.classify` and whether the leggings carry a rejected tag or zero durability.

### PD-109: Tripwire traps in the tripwire room are deleted by the room shell (Medium)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "This tripwire room is cool but I think the traps were deleted by the room's bedrock shell or something."
**Severity:** Medium (a themed room loses its mechanic).
**Status:** Fixed 2026-10-03 as PD-136 (the corridor role removed the dispensers). Earlier notes: open, not reproduced 2026-10-02. The bedrock shell ring sits one block OUTSIDE the cell (`BedrockEnvelope.ringPos`), so it cannot overwrite the hooks (z=1,14) or dispensers (z=0,15). New gametest `tripwireHallKeepsItsTrapsAtEveryRotation` stamps the template at all four rotations and finds 6 hooks, 36 string and 6 dispensers every time. Kept as a regression guard. Second pass (review 2026-10-02): the doorway theory (F5) was wrong; the room is only picked where its east/west jigsaw doors match the plan, so no doorway is ever carved into the dispenser walls. The playtest floor was `infestation` (theme_deepslate processors) with only Silenced, so no Explosive, Molten or Voided hazard touched it. New gametest `tripwireHallTrapsFireUnderTheTheme` stamps the room with that theme, steps on a wire and sees the dispenser spend an arrow. So the traps are built and work; the likely cause is that they read as missing: thin string on the dark deepslate floor and dispensers flush in deepslate brick. Needs a live look: ask the player what they expected to see. The player's own suggestion (a narrower room, so the traps read at a glance) is an owner design call.
**Expected:** tripwire hooks, string and dispensers survive stamping and are reachable.
**Actual:** the traps appeared missing.
**Likely cause:** the mechanism blocks sit on or near the room shell; the stamper's immutable shell pass overwrites them.
**Fix idea:** move the tripwire assembly inward, or make the room narrower as the player suggested, so the shell does not clip it.

### PD-110: Spiders climb walls and get stuck in room corners (Low)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "Spider ai often randomly climbs up the walls in the corner and gets stuck up there" and "usually corners, might be a pathing bug."
**Severity:** Low (aesthetics / occasional soft-lock for the mob, not the player).
**Status:** Fixed 2026-10-02, pending in-game verify. Chose the tick check over template geometry (no template recapture): `SpiderUnstick` sets a dungeon spider back on the floor beneath it after 5 seconds of untargeted climbing more than 3 blocks above the floor.
**Expected:** spiders can navigate the room corners without freezing in the ceiling.
**Actual:** spiders climb into corners and stay there.
**Likely cause:** vanilla spider wall-climb AI plus the room's tight ceiling/wall junction; the mob's hitbox or pathing target ends up in an unresolvable spot.
**Fix idea:** add a small ledge or overhang that spiders cannot cling to, or cap ceiling climbing height below the corner seam.

### PD-111: Spawner gate message should state how many remain, not just "go finish the rest" (Low)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "It says, 'Not yet, 3/5 trial spawners cleared. Go finish the rest.' When it should just tell me to finish x needed amount more."
**Severity:** Low (readability).
**Status:** Fixed 2026-10-02. The gate now says "Not yet: clear N more trial spawner(s).", using `DifficultyProfile.spawnersStillNeeded` (the same threshold the gate uses, so 3 of 5 cleared says 1 more). Unit test in `OmenBarTextTest`.
**Expected:** the message reads "Clear 2 more spawners" (or similar) rather than a fraction plus a vague directive.
**Actual:** message shows a fraction and "Go finish the rest."

### PD-112: SENSOR omen line needs a sound cue (Low)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-1.md`. Player: "'The sculk counts your steps...' line should come with a sound effect. Maybe one of the warden ambient sounds."
**Severity:** Low (feedback; complements PD-100).
**Status:** Fixed 2026-10-02, pending in-game verify. A quiet `SCULK_CLICKING` already played; it is now `WARDEN_NEARBY_CLOSER` at 0.7 volume, and every omen gain is audible (a gain inside a line's cooldown plays its cue at most once a second, `OmenBar.omenRose`).
**Expected:** a distinct sound plays when the sculk-sensor omen line appears.
**Actual:** only text appears.

## Found in the live Lemon playtest (2026-10-02, session 2)

### PD-113: An enderman teleports out of its room and keeps a trial spawner from clearing (High)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "I think at least one enderman teleported out of bound and now I can't complete the floor."
**Severity:** High (the floor cannot be completed; a manual console cleanup was needed).
**Status:** Open. Worked around live by killing the endermen in `pocketdungeons:void`.
**Repro:** ender_archive floor 3, spawners 2 of 3, the open one the hall_tee at cell 3,1. Two endermen were alive: one at (-11.6, 39, 14.5), one at (128.2, 72, 414.2), over 400 blocks away. After killing both, the gate read 3 of 3 and the player finished the floor.
**Expected:** a trial spawner reaches cooldown once its mobs are dead, or the mob cannot leave the instance.
**Actual:** the gate counts only spawners in `COOLDOWN` (`TrialContent.countCleared`), and vanilla holds a spawner out of cooldown while a tracked mob is alive, so an enderman that teleports far away stalls it.
**Fix idea:** cancel enderman teleports that land outside the cell or instance bounds, or count a spawner cleared when its remaining mobs are outside the floor.

### PD-114: The frame_lock exit opens onto a nether brick wall (High, needs repro)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "It correctly opens the door after being rotated 8 times, but on the other side of the door is netherbricks blocking my path." He said a second room had the same problem ("both ordeal rooms had this problem").
**Severity:** High (a gated route that opens onto solid wall).
**Status:** Open, not reproduced. The floor was `basalt_foundry`, whose processor turns every stone brick into nether brick, so the wall is the room shell; rubble is cobblestone, tuff and deepslate, so this is not the L11 rubble feature. The plan was cell -1,2 `frame_lock` between `hall_tee` cells at -1,1 and -1,3.
**Expected:** the unlocked door leads into the next room.
**Actual:** a solid wall stands behind it.
**Fix idea:** `dungeon admin bias frame_lock 20` and stand at the exit with the player; compare the room's door slots with the plan's connectors at every rotation (a gametest like the multi-story one).

### PD-115: Tridents cannot be salvaged (Medium)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "Tridents can't be salvaged."
**Severity:** Medium (surplus gear has no outlet; related to PD-108).
**Status:** Open. Check `SalvageStation.classify` for the trident; PD-108's refusal text may or may not name it.

### PD-116: Selecting a staging door replaces its light with the preview glass; door 3 has no glass (Medium)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "Two of the lights for the selection doors disappeared and were replaced by the preview glass", on the two lowest-level doors when one is selected for preview; "this new 3rd door doesn't have preview glass behind it currently."
**Severity:** Medium (related to PD-85).
**Status:** Open.
**Player's proposed fix:** the selected door temporarily vanishes while its light stays on so he looks through it; changing the selection restores that door and hides the new one.
**Fix idea:** put preview glass behind all three doors and stop the preview from overwriting the door light blocks.

### PD-117: The floor history board's row text is still too small (Low)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`. Player: "Floor history look good but the row text should be 20% bigger."
**Severity:** Low. **Status:** Open. Raise the row scale (`HISTORY_SCALE`) by about 20 percent and check the rows still fit the wall.

### PD-118: Lemon tooling hides and delays player questions (High, harness)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`; the owner also reported "you're missing my messages".
**Severity:** High (at least 8 questions hit the 45 s fallback "I do not know that one yet"; the owner nearly lost data).
**Status:** Open. Worked around live with a persistent `tail -F latest.log | grep` monitor, after which every ask arrived within seconds.
**Repro:** a background `server wait --player <p> --timeout 240` loop printed `no new events (timeout)` while `Lemon ask` lines sat in `run/logs/latest.log`; later a wait printed the whole log from `server ready` (offset 0) twice. At 20:44 the cursor file `run/.agent-chat-cursor.json` held offset 457312, equal to the log size, while two asks (20:42:09, 20:44:11) had never been printed. Some asks never produced a `lemon ask` line at all before the fallback (`Lemon unanswered` only).
**Likely cause:** `tools/server/pdserver.mjs` lines 138 to 153: a cursor parse failure silently resets the offset to 0 (`catch { cursor = { offset: 0 } }`), the cursor write is not atomic, and `wait` and `chat --all` share it, so overlapping or concurrent runs can corrupt or advance it past lines that were never printed.
**Fix idea:** write the cursor atomically (temp file then rename), keep the old offset when it cannot be parsed, never let `chat --all` move it, and refuse a second concurrent `wait` for the same player. Add a test with a corrupted cursor.

### PD-119: No join event and no llm mode when the player is already online (Medium, harness)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`.
**Repro:** the player joined at 19:22:37, before the loop's `server sync`; no `join` event reached the agent, `lemon mode llm` failed with "No player was found" while the server had no players, and the first greeting was held for a fight until 19:36:56.
**Fix idea:** have `wait --player` assert llm mode as soon as the player is online (not only on a join event), and have `status` report whether Lemon is in llm mode for each player.

### PD-120: The session had no Lemon MCP tools (Low, process)

**Reported:** 2026-10-02, live session `docs/playtests/2026-10-02-2.md`.
**Status:** Fixed 2026-10-02: `.mcp.json` at the workspace root registers `lemon` (`tools/server/mcp.mjs --admin`), and `docs/LEMON_AGENT.md` and the playtest skill tell agents to check for the `mcp__lemon__*` tools first and ask for a restart rather than silently using the shell loop.

## Found in the live Lemon playtest (2026-10-03, session 1, Kinetic server)

Report: `docs/playtests/2026-10-03-1.md`. First-ever session of a fresh player (keystone 1, Sapper's Bag).

### PD-121: A failed first run empties a new player's kit (High)

**Reported:** 2026-10-03. Player: "I failed my first run (it's way too hard), but even worse I lost all my items and don't have my kit."
**Severity:** High (the one-time kit is gone for good; `kit_granted` is already set, so nothing re-grants it).
**Status:** Fixed 2026-10-03. The interval snapshot is now retaken at the first door commit, after the bag has been applied.
**Repro:** first entry 06:14:16, bag chosen 06:16:09, first door committed 06:27:41, `run_failed` at 06:37:19 (omen 4). Before: flint and steel 64, blocks 3, food 8. After: tools, blocks and food all 0.
**Cause:** `InventorySwap.snapshotOnEntry` took the first interval snapshot when the dungeon pack was first swapped in, which was before the bag was chosen, so the snapshot was an empty pack. Nothing refreshed it after `Bags.apply`, and `Instances.failRunOmen` restored that snapshot.
**Fix:** `RunLifecycle.commitDoor` calls `InventorySwap.snapshotAtFirstCommit`, which overwrites the entry snapshot on the first door commit of the interval. `FloorIntervalGameTest.maxOmenDeathOnFirstIntervalRevertsInventory` covers the case.
**Live check:** a fresh player's kit survives a max-omen fail on the first interval.

### PD-122: Keystone 1 rolls tier 2 and 3 rooms (Medium)

**Reported:** 2026-10-03. Player: "Definitely shouldn't be getting witherskeletons, blazes and other challenging rooms on the first floor", later "Breeze also very difficult for this level."
**Severity:** Medium (a new player hit three rescues and omen 4 in about nine minutes on floor 1).
**Status:** Fixed 2026-10-03. Room selection now enforces `room.tier <= lootTier(offerLevel)`.
**Rooms seen at keystone 1 and 2:** `wither_loft` (tier 3), `blaze_loft`, `rising_lava`, `breeze_arena` (tier 2).
**Fix:** `RoomSelector.preferred` filters out rooms whose `tier` exceeds the floor's loot tier (`KeystoneMath.lootTier(recipePlan.offerLevel)`). If that leaves no room for a cell, it falls back to the full list and logs a warning, so a catalogue gap is visible. `PlanSelectorTest.testTierGate` covers the filter and fallback. Recipe-guaranteed rooms are not capped.
**Live check:** keystone 1 floors contain only tier-1 rooms.

### PD-123: The collapsing_bridge ordeal never arms (Medium)

**Reported:** 2026-10-03 (server log). `Ordeal collapsing_bridge at -16, 64, 128 found nothing to arm` at 06:46:30 and `... at 0, 64, 304 found nothing to arm` at 07:08:53.
**Severity:** Medium (two of two occurrences; the ordeal does nothing).
**Status:** Fixed 2026-10-03.
**Cause:** the `collapsing_bridge` template was captured while the bridge was retracting, so every piston position was a `minecraft:moving_piston` block instead of a `sticky_piston`. `CollapsingBridgeOrdeal.arm` only scanned for `STICKY_PISTON`, found nothing, and logged "found nothing to arm".
**Fix:** `CollapsingBridgeOrdeal.arm` now normalises `moving_piston` blocks back to sticky pistons before scanning, then re-extends each segment so the bridge is active. `HandlerGameTest.collapsingBridgeTemplateArmsAtEveryRotation` stamps the real template and asserts it arms at all four rotations.
**Live check:** the collapsing bridge ordeal arms, planks drop and return, and the lever opens the way back.

### PD-124: The explosive affix's TNT breaks room mechanisms (Medium)

**Reported:** 2026-10-03. Player: "The tnt blew up and destroyed two surround pressure plates." His fix: TNT that flashes and bangs when stepped on but breaks no blocks.
**Severity:** Medium (a puzzle room can lose parts; the floor stayed finishable this time).
**Status:** Fixed 2026-10-03.
**Fix:** the `explosive` affix now places fake TNT mines. A mine triggers when a player stands on the TNT block: it hisses for one second, then explodes with `Level.ExplosionInteraction.NONE`, which damages and knocks back entities but breaks no blocks. After a cooldown it re-arms. Flint and steel and fire charges are refused on affix TNT by `RitualListener` with a message. In addition, `ServerExplosionMixin` no longer allows any TNT source to break blocks inside dungeon cells: only `RubbleOrdeal.blast` reacts to explosions. Sapper TNT can still clear rubble but cannot destroy pressure plates, chests, doors or other mechanisms.
**Live check:** affix TNT bangs but leaves plates, chests and doors intact; Sapper TNT clears rubble and nothing else.

### PD-125: hold_the_plate with ranged skeletons pins the player (Low)

**Reported:** 2026-10-03. Player: "I can't dodge the arrows if I'm stuck standing on the plate. I can't really hit the skeletons either since they are range", and "I can't dodge the arrows while loading up" (crossbow).
**Status:** Fixed 2026-10-03.
**Fix:** `HoldThePlateOrdeal.tickDanger` now pauses the hold count when the player steps off the plate, instead of resetting it. A paused overlay tells the player how long is left and to step back on. Already-raised waves do not fire again, and the spawn cap still prevents farming.
**Live check:** the player can step off to dodge, then back on to resume the hold.

### PD-126: A protected door gives no reason (Low, needs repro)

**Reported:** 2026-10-03. Player: "There's a door in front of me and there's no way to open, I can't mine it... It doesn't say anything because I can normally mine iron doors with a pickaxe but there's room protection on this door from somewhere else."
**Status:** Fixed 2026-10-03.
**Fix:** `RoomProtection.onAttackBlock` now intercepts left-clicks on iron doors in dungeon cells and sends an action bar reason. If the cell has an armed lock, `Locks.hint` explains the condition ("Put any item in the chest to open it.", "Put <key> in the chest to open it.", or "Hold every pressure plate down at once."). If the cell has an armed Ordeal, the Ordeal's objective is shown ("Opens when you hold the plate for 30 seconds", etc.). Otherwise a generic redstone hint is shown.
**Live check:** hitting a protected iron door explains why it will not break.

### PD-127: The salvage bench's wording reads as a rule (Low)

**Reported:** 2026-10-03. He read "Nothing to salvage yet" (shown whenever the input slots are empty) and "Stays: 1 (Leather Cap: ...)" (the item name starts the line) as a refusal and a cap. One leather piece did salvage (journal `salvage`, mob_gear 1). The refusal reason itself was never captured.
**Status:** Fixed 2026-10-03.
**Fix:** `SalvageStation.refresh` now shows "Put gear here to salvage" with "Click gear in your pack to move it in." when the input slots are empty. When items are present but nothing is salvageable, it shows "Nothing here can be salvaged". Refusal text now leads with the reason: "Kept, not salvaged: <reason> (<item name>)", and appends "and N more" when several items are refused.
**Live check:** the bench's empty state and refusals read as explanations, not rules.

### PD-128: Lemon agent tooling gaps seen on the remote server (Medium, harness)

**Reported:** 2026-10-03 (agent notes).
- PD-118 recurred: the ask "How do I pick a door?" at 06:20:03 never reached `wait_events` (`answered_by` none, `wait_s` 91) and was found only in `context` recent.
- `lemon_mode llm` lapses after 300 s, so the agent must refresh about every 4 minutes or the asks fall to the scripted guide (seen 06:44).
- `lemon_say` with no `player` returns "No player was found" in remote mode.
- `session_notes` is read-only (it returns the newest playtest file), so an agent cannot save notes through it.
**Fix idea:** have `wait` assert llm mode on its own, make `player` optional when exactly one is online, and add a `note` tool or say so plainly in the tool description.

### PD-109 update

A second live sighting on 2026-10-03: in `tripwire_hall` (frostworks floor 1, keystone 3) the player found no dispensers and "bedrock through one of the sides", which supports the shell overwrite cause above.

## Playtest 2026-10-03-2 (first online client session)

### PD-129: Home room greets as "Uncharted" (Low)
**Reported:** 2026-10-03-2. "Why does it say Uncharted when I enter this room?" The home room said Uncharted, expected Home.
**Likely cause:** `DungeonScreen.themeName` returns "Uncharted" for an empty theme; the home title path passes no theme.
**Status:** Fixed 2026-10-03, live check owed. Cause confirmed: entering through the lodestone lands in the home lobby, which has no floor theme, and `FloorStartTitle.show` printed the fallback of `DungeonScreen.themeName`. `FloorStartTitle.titleFor` now says HOME (no affix lines) when there is no theme; `themeName` itself is unchanged for the history board.

### PD-130: Public room still says nobody may enter (Low)
**Reported:** 2026-10-03-2. Switched the room to public, the manage screen still says "Nobody may enter your room but you."
**Likely cause:** `DialogScreens.manageRoom` shows listing and whitelist state together; they are separate channels by design.
**Status:** Fixed 2026-10-03. The listing and the whitelist are two lines now (`DialogScreens.listingLine`, `whitelistLine`) on both the manage and whitelist screens: "Listed in the lobby directory: anyone can drop in to look around." and "No trusted players. Your party can build here while they are with you." `LodestoneMenuTest` checks the wording.

### PD-131: Bag chest vanishes after first pick; party member cannot use it (High)
**Reported:** 2026-10-03-2. After the leader picks a bag the chest disappears, so a joiner cannot pick. SirAegerus saw it but could not use it though it worked once earlier. Operator hand-gave a shield, stone sword and bread.
**Status:** Fixed 2026-10-03, live check owed. The server log shows SirAegerus joining slot 0 at 03:52:47 and again at 03:54:44, with the leader's bag pick (03:54:13) in between, so he was not a member when `confirmBag` cleared the chest (it cleared once every member present had a bag). The chest is no longer cleared on a pick; door choice and save still clear it. A click from someone who already carries a bag used to fall through to the container denial and do nothing; it now names their bag (`RitualListener`). Gametest `PartyAccessGameTest.bagChestAnswersEveryMember`. The playtest journal on the Kinetic server could not be read (panel 500s), so the exact click is unconfirmed. Owner idea under discussion: make the bag chest a permanent per-player station refilled with a fresh kit, in place of the top-up.

### PD-132: Party member intermittently locked out of stations and building (High)
**Reported:** 2026-10-03-2. SirAegerus could not use stations or blocks even in the staging room; the dungeon rooms were fine; later he could. Guests also cannot build in the owner's home (feature request: let party members modify it).
**Status:** Fixed 2026-10-03, live check owed. Two causes. (1) `RoomProtection.isPermitted` allowed only the owner and the whitelist in the owner's safe and staging rooms, so a companion could not open chests or place blocks until whitelisted (the owner was changing room access during the session, which is the "later he could"). Companions now share the owner's permission while they are in the run (`RoomProtection.isCompanion`); lobby-directory guests are tracked in `InstanceRecord.guests` and stay read-only. This is the owner's request to let party members modify the leader's room. (2) The placement guard in `RitualListener` cancelled the whole right-click for anyone not permitted who held a block, so a station opened with an empty hand and not with blocks in hand: the intermittent part. A click on any block with a menu or on a mod station (`RitualListener.opensStation`) is now a use, not a placement. Gametest `PartyAccessGameTest.companionBuildsAndGuestUsesStations` drives the real `UseBlockCallback`.

### PD-133: Entrance leads only into a sealed rotation_lock (Blocker)
**Reported:** 2026-10-03-2, 05:25, floor 2 infestation with the explosive affix, party 2. The ordeal door was closed on the only way in; the player used creative to break it.
**Owner decision (2026-10-03):** puzzles on the path are fine, but each must be oriented so the player can always enter the puzzle. The fault is the orientation or door mask, not the placement.
**Status:** Fixed 2026-10-03, live check owed. Cause: every gated room is a straight pass-through authored with its entry on the west and its gate on the east, so a straight cell fits it at two rotations 180 degrees apart, and every placement route (main pass, anomaly roll, recipe guarantee) kept whichever it rolled. Half the time the iron door faced the approach. `RoomSelector.orientGatedRooms` now turns each gated room so its entry faces the cell it is approached from (the critical path predecessor, else the shallowest neighbour); the mask is unchanged, so solvability is unaffected. Found alongside: `SituationSpecs.placeIronDoor` stood the door in the doorway plane (x=15), overwriting the east jigsaws, so `sorting_floor` and `sensor_gallery` were read as west-only dead ends whose gates led into a wall. The door is at x=14 now, the backwards comparator in sorting_floor is a `Locks` chest latch, the dust in sensor_gallery drives a repeater into a block beside the door, and both templates were regenerated. Tests: `GraphSolvabilityTest.testGatedRoomsFaceTheApproach` (fails without the fix: "entered from W but its entry faces E at rotation 2") and gametest `HandlerGameTest.gatedRoomsCanBeEnteredFromEveryApproach`, which stamps every gated room in the manifest for all four approach directions and both rolled rotations and walks the entry lane.

### PD-134: Tamed wolf does not count as defeated (Medium)
**Reported:** 2026-10-03-2. Taming a wolf from the wolf spawner left the spawner uncounted.
**Status:** Fixed 2026-10-03. A trial spawner finishes only when every mob it spawned is dead, and a tamed wolf never dies. `TrialContent.releaseTamed`, run from the floor's spawner watch, drops tamed animals from each spawner's waiting set (`mixin/TrialSpawnerStateDataAccessor`, an accessor like the existing `DisplayAccessor`). Gametest `HandlerGameTest.tamedWolfIsReleasedFromItsSpawner`.

### PD-135: Salvage bench refuses a leather cap; disenchant needs an item (Medium)
**Reported:** 2026-10-03-2. "Kept, not salvaged" on a leather cap, reason not read out. Mode switch to disenchant needs an item in the input. Related to PD-127.
**Status:** Fixed 2026-10-03. Reason found in the tables: chest loot is tagged `{tier, bag}` (the bag tag keeps it in the dungeon inventory), and `SalvageStation.classify` checked "bag tagged, part of your kit" before tier, so every chest armour piece, a leather cap included, was refused. Only untiered bag items count as kit now, and keys are sorted first. The Disenchant button opens the plain grindstone with an empty bench. Gametest `SalvageGameTest.chestLeatherCapIsSalvaged`.

### PD-136: Tripwire hall bedrock holes (Medium)
**Reported:** 2026-10-03-2, second live sighting of PD-109: bedrock visible through the holes above the tripwires.
**Status:** Fixed 2026-10-03, also closes PD-109. `RoomContent.containers` returned every randomizable container, so the corridor role's `removeChests` deleted the six wall dispensers of the tripwire hall, leaving holes onto the bedrock envelope. The same test let a loot cell promote a hopper or dispenser to its vault. It now returns chests and barrels only. The stamp-only gametest never ran the role; `HandlerGameTest.tripwireHallKeepsItsDispensersThroughTheCorridorRole` does.

### PD-137: Store bias never produced a Store (Medium)
**Reported:** 2026-10-03-2. `room_bias the_store` x20 then x50 across about 7 floors, none appeared. Check depth gating and corridor cell fit for the_store (tier 1, depth 1+, pressure omen).
**Status:** Fixed 2026-10-03. Cause: the four spur rooms (`the_store`, `the_altar`, `barred_vault`, `ominous_bargain`) have one door and only the `corridor` role, but the generator gives every dead-end cell the `loot` role and every corridor cell has two or more doors, so no cell could ever take them: no bias, and no Store cube recipe guarantee, could place one. They are `loot` rooms now. Gametest `HandlerGameTest.everySpurRoomCanBePlaced` (each spur appears in 400 floors, the Store on at least a third of floors at x50).

### PD-138: Lemon harness gaps (Medium, harness)
**Reported:** 2026-10-03-2.
- Party chat arrives as `chat`, not `lemon_heard` with a `from` name.
- The operator cannot see which tour lines fired; the player saw Lemon's tips only later in chat.
- `room_bias_hold` without `player` errors with "undefined is not online".
- The first ask after join logged `answered_by: none`, wait 0.
**Status:** Fixed 2026-10-03. Party chat now also logs `Lemon heard <speaker> text`, which `wait` prints in place of the matching `chat` line (`dedupeHeard`). Tour lines from `FirstVisitTutorial` and `StationTutorial` log as `Lemon tour`, which wakes `wait`, so the operator sees which fired. `room_bias_hold` without `player` holds the only online player, or says who is online. A question asked before the agent's mode refresh reached a new player now waits the fallback time when the agent is serving anyone (`Lemon.agentServing`). Bubble delivery of a six-line tour in one tick (the player saw the tips only in chat) is not addressed.

### PD-139: Log noise (Low)
**Reported:** 2026-10-03-2. Bursts of "Mismatch in destroy block pos", "Room tier gap ... no rooms at tier <= 1; falling back" on deepslate, infestation, prismarine and frostworks encounters, and one "Block-attached entity at invalid position z=1000710".
**Status:** Fixed 2026-10-03 where it was ours. "Room tier gap": the designated gate cell narrowed its pool to gated rooms, all tier 2 or 3 for encounters, so keystone 1 and 2 floors fell back above their tier; gate designation and the gate preference now consider only gated rooms within the floor's tier. "Mismatch in destroy block pos" is vanilla's dig start and stop landing on different blocks; the harness filter drops it. The one "Block-attached entity at invalid position" came at 05:24:48 while the player broke into the rotation_lock in creative (its item frame hangs on a block); not pursued.

### PD-140: The Store is entered from the wrong side (Medium)
**Reported:** 2026-10-04-1, 03:55, floor 1 frostworks. "This room looks like it's backwards. The doorway I entered in entered facing a wall of barrels."
**Status:** Fixed 2026-10-05. It was not the rotation: a one-door spur has one rotation per cell and its template turns with it. `StoreShop.build` ran at stamp time in world coordinates (and the template baked a second copy), so with the door on the north wall the counter and a row of barrels stood in the doorway. The shop is now built only at stamp time, turned so its counter is opposite the room's one open door (`StoreShop.turnFor`), and the `the_store` template carries no shop. The altar, barred vault and ominous bargain were already open at their door at every rotation (the same gametest checks all four). Gametest `oneDoorLootRoomsAreOpenAtTheirDoor`.

### PD-141: The Store's vendor wanders out of the shop (Medium)
**Reported:** 2026-10-04-1, 03:56. The villager was in the next room, "fairly far away from the store".
**Status:** Fixed 2026-10-05. The shopkeeper is `NoAI` and stays where it spawns; it still turns to a customer. Gametest `theStoreVendorStaysPut`.

### PD-142: Store purchase and vendor naming do not feel vanilla (Low)
**Reported:** 2026-10-04-1, 04:00 and 04:02. Clicking an offer sends the item straight into the inventory and shows "Bought Potion of Fire Resistance", instead of the vanilla trading screen output slot. The vendor is called "Frost Peddler" on a frostworks floor but sells bones, rotten flesh and emeralds that drop in every theme; the owner wants him "named from what he buys and not the floor theme".
**Status:** Fixed 2026-10-05, owner calls taken. (1) The vendor is named for what he buys: the first of his currencies carries the title ("Bone Collector", "Blaze Trader", "Web Trader", ...), emeralds only is still the Wandering Merchant; the floor theme no longer names him. (2) A purchase is the vanilla trading screen: the screen takes the payment and the player picks the item up from the output slot; `onTrade` only claims the stock, strips the listing's name and lore from the output stack and says yes (no "Bought" line, nothing teleported into the pack). Not exercised through a client; the gametest covers the claim and the clean-up. (3) Journal event `shop_purchase` (`item`, `name`, `price`, `currency`, `vendor`), in `PLAYTEST_EVENTS.md`. Gametests `aVendorIsNamedForWhatHeBuys`, `aStorePurchaseIsClaimedJournaledAndCleanOfLore`.

### PD-143: The Altar's redstone falls off; its key is free (High)
**Reported:** 2026-10-04-1, 04:08 to 04:09, floor 2 infestation. "A dropper, a couple hoppers and some redstone repeaters that broke"; the comparator, repeater and dust lay on the ground to loot. "I can still use the cauldron and get the key out of the dispenser."
**Status:** Fixed 2026-10-05. Support blocks stand under the comparator, repeater and dust at x 9, the dropper starts empty, and `AltarOffering` pays one trial key out of the dropper's mouth for a food item in the offering hopper, once per altar. Two more faults found on the way: the redstone chain never worked (the comparator read the wrong side), and a second hopper stood under the dropper's mouth and would have swallowed the key; it is a plain step now. The redstone parts are scenery; the payout is code, like the PD-133 sorting floor lock. Gametest `theAltarPaysOneKeyForAFoodOfferingOnly`.

### PD-144: sorting_floor is not a working gate (High)
**Reported:** 2026-10-04-1, 04:51 to 04:58, floor 1 basalt_foundry, entered unsteered. Water "spread all over the place"; the room "seems like it's already solved, there's 4 stacks of sticks in the chest connected to the hopper. But the other side has another set of iron doors that are closed. Two sets of doors, this side's set is open." The player broke the near doors and placed a lever to open the far ones. He also noted the four stacks of sticks are "a massive leak".
**Status:** Fixed 2026-10-05, three causes. The filter hopper was pre-loaded with four stacks of sticks, which it pushed into the return chest at stamp time, so the lock read the room as solved (and the sticks were free): the hopper starts empty, and the lock now asks for a stick in the chest. The channel was only partly fenced, so water spread past it: every open side of the channel is fenced at build time and the source starts at x 4 so the doorway lane keeps an open column. The connector pass stacked a second iron door set on the room's edge: an iron door connector on an edge touching a gated room is now a plain doorway (`ConnectorStamper.effectiveType`). Gametests `sortingFloorIsAClosedGateWithConfinedWater` (all four rotations), `anIronDoorConnectorYieldsToAGatedRoom`.

### PD-145: kennel_crossing spawner spawns nothing on ender_archive (High)
**Reported:** 2026-10-04-1, 05:18 to 05:19, floor 2 ender_archive (feral). "Nothing is spawning from this spawner? ... I can't read the counter, there's six grass blocks around it, nothing has spawned."
**Status:** Fixed 2026-10-05. The light guess was right, and it was every theme, not only ender_archive: the pen stood at light 6 under the ceiling lamps and a wolf needs more than 8. Two `minecraft:light` blocks over the pen now light it. Gametest `theKennelPenSpawnsWolvesOnEveryTheme` runs the real spawn placement rule for every theme.

### PD-146: Gear above the dungeon durability cap (Medium)
**Reported:** 2026-10-04-1, journal snapshots. An iron sword 250/250 (04:15 onward, kept in run storage) and an iron helmet 165/165 (05:37) sit in the pack; the cap for iron is 64 and PD-69 says every item that appears in the dungeon is capped.
**Status:** Fixed 2026-10-05. The entry was the Store: it sells iron swords and helmets, and a sale reached the pack through `Payout.deliver`, which never passed the item-entity or loot-table caps. `Payout.deliver` now caps every stack it hands over (the gamble, salvage and cube grants too). Gametest `handedOverGearIsDurabilityCapped`.

### PD-147: collapsing_bridge arrives collapsed and is wooden on a fire floor (Medium)
**Reported:** 2026-10-04-1, 04:50, basalt_foundry. "Some of this bridge looks like it was already collapsed by the time I found it. Also the bridge being made of wood is problematic since it also burns."
**Status:** Fixed 2026-10-05. Two causes. The bridge was oak over a lava floor, which lava lights; it is crimson planks now (not flammable). And its pistons had no power, so an extended piston retracted on the next neighbour update and pulled its plank: a redstone block under each base keeps it extended (the collapse is code). A bridge in a cell with no deeper neighbour was also never armed and kept the template's retracted state; it is armed without a lever now. Gametest `theCollapsingBridgeStandsAndDoesNotBurn` (all rotations, with a neighbour update storm).

### PD-148: Lemon harness and log noise (Low, harness)
**Reported:** 2026-10-04-1.
- `context.recent` is capped at 20 events, so the 05:37 `bank` row had scrolled out by the time the player asked how his keystone changed; the operator could not state the levels gained. A "last bank" summary field would fix it.
- The server console receives `pdmark-<id>` lines about every 40 s and logs "Unknown or incomplete command" for each (harness markers sent as commands).
- The PD-139 "Block-attached entity at invalid position: BlockPos{x=9, y=65, z=1000710}" error fired again at 03:50:22, with no creative mode break this time (the player entered a room at 03:50:27, five seconds later; cause not found).
**Status:** Partly fixed 2026-10-05. The context snapshot carries `last_bank` (the latest `bank` event, read back from the journal, so it survives the 20 event `recent` window). The `pdmark-...` console lines: a remote command costs a marker command after it, and the llm keepalive sent one every minute; keepalive and `say` now fire without a reply (`lemon ... --quiet`), so the lines stay only for commands whose reply is read. Not done: the "Block-attached entity at invalid position" error, cause still unknown. Unit test `JournalFormatTest` covers `last_bank`; the console change has no automated test.
### PD-149: Resource dungeon floors can roll zero nodes (High)
**Reported:** 2026-10-05-1, 00:05 to 00:26. Mineshaft floors 1 (mine_mouth) and 2 (main_drift) generated only generic halls (entrance_hall, hall_corner/tee/dead_end, the_altar, exit_hall) and `floor_complete` showed `nodes_total` 0 on both. The player asked "are there supposed to be resource nodes in this floor?". A resource dungeon pays only what you mine, so these floors paid nothing under D20.
**Status:** Fixed again 2026-10-06, live check owed. Not reproduced in a running server (the session that fixed it had no Gradle build); found by reading what differs live from the unit test. Every Mineshaft room with ore (`mineshaft_seam`) is a two-door west and east straight, and a room is only placed on a cell whose door mask it matches at some rotation. The live floor 1 was entrance, corner, tee, dead end and exit: no straight cell, so `forceRoom` found no host and `ensureResourceRooms` gave up silently. The 40-seed unit test used only straight layouts, so it could never miss. Now `ensureResourceRooms` reports whether the floor pays (`RoomSelector.Result.resourceShort`), and `LayoutPlanner.plan` treats a short resource floor like an unsolvable one: it tries the next layout seed (32 by default) and settles for the short plan only when none fits. A dungeon with no node rooms (the Cow Pits) still counts one of its own rooms. Test `PlanSelectorTest.testResourceFloorGuaranteesANodeRoom` gains a staircase layout of corners that must report short.
Reopened 2026-10-06. The first live floor after the fix (Mineshaft floor 1, ~04:58) again rolled only generic halls with `nodes_total` 0, while floor 2 of the same trip did place `mineshaft_seam`. The 40-seed unit test passes, so the miss is in whatever differs live (eligible-cell check, placement order, or a resource flag not set on that floor).
Original fix 2026-10-05: `RoomEligibility.weightFactor` now gives a room bound to the floor's dungeon or main theme (`dungeons`, or legacy `theme`) a x5 `DUNGEON_BOOST`, so a dungeon's own rooms carry its floors instead of losing the draw to generic halls; the boost stacks with the entry and side_reward boosts and does not apply to rooms only eligible through a borrowed theme or `borrowableBy`. On top of the preference, `RoomSelector.ensureResourceRooms` forces a resource-bearing room onto an eligible cell after the pass when a resource floor placed none: a declared-`nodes` room first, else any room bound to the dungeon (the Cow Pits pay cows, not ore), using the same solvability revalidation as a recipe guarantee. `Floor` carries a `resource` flag so the rule is scoped to D12 dungeons. Tests: `PlanSelectorTest.testResourceFloorGuaranteesANodeRoom` (40 seeds all land the ore room) and `RoomEligibilityTest.testDungeonBoost`.

### PD-150: Run storage reads as wiped when the run closes offline (Medium)
**Reported:** 2026-10-05-1, 00:05: "My run storage was reset, this happened last time as well. Maybe when the server goes down." Second report (first: 2026-10-04-1 at 04:16, then not reproduced).
**Status:** Fixed 2026-10-05, live check owed. Mechanism confirmed, no data loss: `InstanceTeardown.purge` calls `RunStorage.returnAll`, which moves the contents into the dungeon pack via `InventorySwap.keepForNextEntry`, but the notice only reached an online player. `returnAll` now records the returned stack count for an offline member in a persisted `storage_returns` sidecar on `DungeonLog`, and `InventorySwap.enterVoid` sends "Your run storage went back into your dungeon pack while you were away." on the next dungeon entry, once, then clears it. Superseded 2026-10-06 by Dungeon Storage (design item 5): the contents now live in `DungeonLog` (`storageOf` and `setStorage`, 27 slots per player, saved on every change), so a run closing, a logout and a restart move nothing, and `RunStorage.returnAll`, `noteStorageReturn`, the `storage_returns` sidecar (still read from an old save, never written) and the offline notice in `InventorySwap.enterVoid` are retired. An omen death rolls the storage back through the log (`RunStorage.rollBackToInterval`) after closing any open menu, so a late close cannot write stale stacks back. Anything an old live run's storage still held when it closes moves into the new storage (`migrateLegacy`), the overflow into the dungeon pack. The picker and menu say Dungeon Storage. Tests: `DungeonStorageGameTest` (saved on every change, survives a teardown, the next open finds it, the rollback).

### PD-151: Door screen text is too much and too technical (Low)
**Reported:** 2026-10-05-1, 00:04 ("There's too much text on the screen above the selector doors") and 00:19 ("'Loot Tier 2' sounds too back endy, should say something that means more to the player, '+0 toward your key, +0 so far' is very verbose").
**Status:** Fixed 2026-10-05, live check owed. The door screen drops the "KEYSTONE n" restatement and the step line reads "+3 toward your key (4 banked)" (`IntervalBanking.doorLine`), with a resource door saying "Pays what you mine" instead of "+0 toward your key, +0 so far". "Loot tier n" is a word now: modest, fair, rich or lavish (`DungeonScreen.lootWord`). Test: `IntervalBankingTest` wording lines. Reworked again 2026-10-06 (design item 1, `BoardText`, `DungeonScreen.previewContent`): the door board is a title display and a body display now, in colour groups with no labels (`INFESTATION · floor 3 of 4`, the floor name with magenta affixes, a cyan line of what you get, `loot ×3` with `costs N echo shards` in yellow). `Loot tier`, `lootWord`, `Rewards:`, `Resources:`, `Loot:`, `Cost:` and `floors remaining` are gone, and `IntervalBanking.doorLine`'s `Pays what you mine` is long gone with it. A resource dungeon shows its ore as plain words (`coal · copper · iron · gold`) and its floors pay scrap like any other. Tests: `BoardTextTest`, `BoardGameTest`. Live check: the Wither's Keep and Spawner Dungeon titles measure about 8.4 and 9.3 blocks at scale 2.0 on an 8 block backdrop. Amended 2026-10-06 (plan 2026-10-06-2): the price reads `costs N scrap`, the pay line is `N scrap` or `N emeralds` above the floor's level, and the GO HOME board shows `Lives N` plus what leaving forfeits (`Unfinished: vault, page`) instead of carried scrap (`IntervalBanking.homeScreen`).

### PD-152: Floor-start title restates the keystone (Low)
**Reported:** 2026-10-05-1, 00:16. "Keystone 9 Floor 2 of Mineshaft seems too verbose, Keystone shouldn't be mentioned since it's just restating the player's keystone level"; the wanted form is "Mineshaft: Floor 2".
**Status:** Fixed 2026-10-05, live check owed. `OmenBarText.previewFloor` now reads "MINESHAFT: FLOOR 2" (and "MINESHAFT: FINAL FLOOR" / "MINE FLOOR n"), and the keystone level is gone from the door screen's first line. Test: `OmenBarTextTest.testPreviewFloor`. The door board's first line was reworked again 2026-10-06 and is now `DUNGEON · floor N of M` (see PD-151); `OmenBarText.previewFloor` stays for floors with no dungeon graph. The GO HOME board gained the `Lives N` and `Unfinished: vault, page` lines the same day (J1, J3).

### PD-153: Spare doors are identical when steps are flat (Medium)
**Reported:** 2026-10-05-1, 00:27: "All the doors seem to say 'The Deep Face' with no difference even in modifiers or anything." At main_drift the only edge is to deep_face, so the spare-doors rule (D4) filled all three doors with the same branch; with every step +0 in a resource dungeon the offers are exact copies. "It feels arbitrary."
**Status:** Superseded 2026-10-06. The owner rejected the label after seeing it live: "if they are the same then they should have different affixes." The new rule is no truly identical doors: reroll affixes until the deal differs. Original fix 2026-10-05: a door whose offer is identical to an earlier door's in every respect the player can weigh (floor, step, shard cost) now says "Same as door n" in dark gray on its screen (`DungeonScreen.identicalDoor`), so the spare-door repeat reads as a repeat, not a bug. The doors stay physical and choosable; nothing about the deal changes.
 Fixed 2026-10-06 by `TripDoors.Door.variant` (the count of earlier doors to the same floor), `Keystone.dealtAffixes` (one helper behind the door preview, the commit and the confirmation line) and the pure `DoorAffixes` reroll: a repeated floor's seeded affixes are redrawn under a salt until the set differs from every earlier copy's (at most 16 tries). The "Same as door n" line and `DungeonScreen.identicalDoor` are gone. Under compass 5 the seeded count is 0, so copies there differ by step only (owner approved). Resource dungeons now deal steps 1 to 3 like any other, which already separates their doors. Tests: `TripDoorsTest` variants, `AffixMathTest` salted pick, `DoorAffixesTest` (every node of every shipped dungeon for 200 owners at levels 5, 11 and 30: no two doors share floor, step, cost and affixes).

### PD-154: Dark floors arrive with no warning (Low)
**Reported:** 2026-10-05-1, 00:20: "This dungeon is very dark, should be a very brief 1 line warning before I choose to go into this dark dungeon."
**Status:** Fixed 2026-10-05, live check owed. The door screen reads the node's `light` and shows one line: "Dark floor: bring torches" in gold for a dark node, "Dim light" in gray for a dim one.

### PD-155: Mineshaft ore rooms are too generous (Low, balance)
**Reported:** 2026-10-05-1, 00:29 to 00:30: "too much ore inside, it should be more scarce and more random"; "maybe some iron, maybe some gold instead, maybe both, maybe a lot of iron"; "4 raw iron being consider medium-high amount".
**Status:** Fixed 2026-10-05, live check owed. `NodeSpec` gained an optional `chance` (seeded probability the whole pocket spawns, default 1), and `mineshaft_seam` is scarcer and mixed: coal 3 always, iron 3 at 70% plus a second iron pocket 3 at 25% (the "maybe a lot of iron" case), copper 2 at 60%, deepslate gold 2 at 35%. A seam now ranges from a bare coal pocket to about 13 ore. Test: `DungeonRoomMetaTest` chance parsing and rejections.

### PD-156: Vanilla warnings leak into MCP tool replies (Low, harness)
**Reported:** 2026-10-05-1, ~00:29. A "Mismatch in destroy block pos" server log line appeared inside the `lemon_reply` tool result. `wait-filter` drops that line from the event stream (PD-139 note), but it still surfaces appended to an MCP reply, which is confusing to read.
**Status:** Fixed 2026-10-05. `wait-filter.mjs` exports `isVanillaNoise`, and `remoteCommand` in `pdserver.mjs` drops those lines from the reply it reads out of latest.log (they were kept because command feedback is a non-event line and the noise was too). A mod warning and a real vanilla error still come through. Test: `wait-filter.test.mjs` noise cases.

## 2026-10-06 (session 2026-10-06-1)

### PD-157: Diary book pages clip text (Medium)
**Reported:** 2026-10-06-1, ~05:33. Entry 8 "The First Pick" "seems like it gets cut off after the first page"; the player asked for the other entries checked.
**Status:** Fixed 2026-10-06, live check owed. `DiaryDelivery.book` fits each authored page onto as many book pages as it needs (`BookPages.paginate`): split at a paragraph break first, then a sentence end, then a word; no page starts blank; the budget is 13 lines of 114 px measured with the default font's advances (one line spare, since the server cannot measure the client font). The found copy's shuffle moves authored pages with their split pages kept together and in order. Six shipped entries split (1, 2, 6, 8, 15, 16 by the estimate); books already found keep their clipped pages, and the Diaries menu shows the authored text whole. Unit test `BookPagesTest` (every shipped page fits after pagination, no word lost or reordered).

### PD-158: Omen bar does not track interval omen (Medium)
**Reported:** 2026-10-06-1, ~05:29. "My omen was 4/4 but the bar wasn't full, it didn't seem to progress properly with deaths and eating." The journal confirms interval omen reached 4 on that floor, so the bar's fill or denominator is not keyed to it.
**Status:** Fixed 2026-10-06, live check owed. Cause: `OmenBar.sync` filled the bar with `Omen.bandProgress(sum, floorCount)` (the trip's omen sum against the next band) while the title printed the floor omen ("Omen 4/4"), so a floor at 4/4 could show a part-filled bar, and the death warning keyed off band >= 2 instead of the floor omen. During a floor the bar now fills `clamp(floor omen) / 4` and colours by floor omen (0 to 1 green, 2 to 3 yellow, 4 red, `OmenBarText.omenColourIndex`); between floors it keeps the band fill, since banking still uses bands. `OmenBarText.activeTitle` shows "one more fall ends the run" from floor omen 3 (a death adds 1 and 4 ends the run) and no longer takes the band. Death and eating omen sources already reached the bar through `omenRose`; the fill was the fault. Tests: `OmenBarTextTest` (warning at omen 3, not at band 2; colour mapping). `Omen.bandProgress` and its tests are unchanged.

### PD-159: Store left-click can take stock without delivering (High)
**Reported:** 2026-10-06-1, ~05:21. "If I left click an item in the shop, I don't get the item sometimes and it runs out of stock." Same session, earlier: a bought oak log would not stack with plain logs and kept its store name/lore.
**Status:** Fixed 2026-10-06, live check owed. Both faults lived in the trading screen: the clean-up ran on the output slot after the take, so it cleaned the next result and the player's stack kept the listing's name and lore, and the stock was claimed while the client still held its own copy of the offer. Owner call (design `docs/design-2026-10-06-1.md` item 4): the Store leaves the vanilla trading screen. It is one chest row of the real items, each with its price (gold, red when the pack cannot pay) and stock (`12 left`) in the lore; any click buys one through `StoreNPC.buy`, which takes the price from the pack, hands over a freshly built plain stack (`Payout.deliver`, capped) and saves the stock in the same tick. Nothing is ever picked up from the shop, so no click can lose stock. A sold out line is a gray "sold out" pane; the villager's yes and no sounds replace the chat lines. Gametests `aSaleTakesExactlyThePriceAndLowersStockUntilSoldOut`, `aLineShowsPriceAndStockAndSellsOutAsAPane`, `aStorePurchaseIsDeliveredCleanAndJournaled`.

### PD-160: Iron door connector with no reachable opener (High)
**Reported:** 2026-10-06-1, ~05:00. A `flooded_hall` room behind an iron door connector: the player could not break the door and could not place a redstone signal close enough.
**Status:** Fixed 2026-10-06, live check owed. The reported door was almost certainly not the connector: `TraversalSpecs.floodedHall` stamps its own containment iron doors in every open doorway, with a stone button on the inside only. A player arriving from outside had no opener, and the east door's inside button sat in a water column (the hall is water from x 2 to 14 at every height) and was washed off, since buttons are not waterloggable. Rule (owner approved): every iron door opens from the side you arrive on; no connector is locked by design, only `access: gated` rooms lock.
Fix: (1) the containment doors are latches (`IronDoorLatch`, `InstanceLayout.latchDoors`): using the door from either side opens both leaves for 60 ticks, then they close. Plates and buttons in the neighbour's doorway slot are unreliable (the neighbour's connector or template can overwrite them, and water washes them off), so it is code, not redstone. (2) Connector doors (`ConnectorStamper.applyIronDoor`): the lever moves off the frame to a seeded spot on one of the near room's other three walls at y 2 (solid wall, plain air in front, outside every doorway lane; the frame position stays as the fallback), and `applyFarSideButton` puts a stone button in the far room beside the doorway. Neither touches the door, so `InstanceLayout.doorOpeners` maps each to its door and `IronDoorLatch` sets the leaves on use: a lever is a standing switch, a button opens the door for good. Doors are opened with `OPEN` alone, never `POWERED`, so a neighbour update cannot shut them. The PD-62 far-side placement exemption stays. (3) A shut door that is neither explains itself on use, throttled to once per 3 s: "The lever is in this room." for a connector door, "Opens when the room is solved." for a gated room's door.
Tests: `IronDoorGameTest.floodedHallDoorsAreLatchesThatOpenAndClose` (every doorway door is a latch, no button in the water, opens on use, still open at 30 ticks, shut after 60) and `aConnectorDoorHasItsLeverOffTheFrameAndAButtonOnTheFarSide`. PD-55 is resolved by this: the lever is off the frame and the far side has its own opener.

## 2026-10-07 (session 2026-10-07-1)

### PD-161: Door board advertises ore the floor never had (Medium)
**Reported:** 2026-10-07-1, 00:19 to 00:24. The left sheet read "Mine the walls for iron and coal" and the pay line listed "iron coal" on a Copper Works floor; the player said "I don't think there's any coal or iron minable in the walls on this floor", then added the fiction angle: "Copper works and Boiler Room doesn't sound like somewhere you'd be going to to mine iron or coal." All four Copper Works floors journaled `nodes_total` 0. Copper Works declares `nodePalette` [iron_ore, coal_ore] but no `hiddenOre` section, and none of the rooms it rolled expose palette blocks.
**Status:** Fixed (honest board, 2026-10-07). `DungeonScreen.previewContent` asks `planHoldsNodes` of the plan the preview just stamped: true when the dungeon buries hidden ore (the Mineshaft) or a placed room declares nodes, false otherwise. On false the ore words leave the pay line and the notes sentence falls back to the next fact ("Clear the spawners to open the way."), so Copper Works no longer promises iron and coal it never holds. Not done: giving ore-palette dungeons a `hiddenOre` section or extending the PD-149 guarantee; that is a content decision, and without it Copper Works floors simply have no ore. Test: `BoardGameTest.theBoardOnlyPromisesOreThePlanCanDeliver`.

### PD-162: Scrap lore reads off the spendable pool, not the level (Medium)
**Reported:** 2026-10-07-1, 00:55. Compass lore verbatim: "Scrap 2/5 to chart 1 (2 held)" on a player whose compass is level 12. `Keystone.showScrap` computes the next chart from the pool (`ScrapMath.chartLevel(scrap) + 1`), so a migrated or spent-down player reads "to chart 1" under a level-12 compass. Related friction in the same ten minutes: the side-branch refusal "2 scrap short" (`SideBranchPay`) names the gap but never the holding, and the GO HOME board shows no scrap at all by design, which the player explicitly wanted ("The home board should be showing me my current scrap").
**Status:** Fixed by the model change (2026-10-07): "Haul and Blood Doors", see docs/decision-2026-10-07-haul-and-blood-doors.md. The compass is the one lasting number, with a bar to the next level; scrap rides in a per-trip haul that banks at home or a finish and is half lost to a failed dungeon; side doors cost lives, so an overleveled player can always pay. Live check owed: L54.

### PD-163: MCP lemon tools intermittently report "No player was found" (Medium, harness)
**Reported:** 2026-10-07-1, ~00:36:40 and ~00:50:5x. `lemon_reply` returned "No player was found" while `status` showed MrPinoy123456789 online; the first failure let the ask hit `lemon unanswered` at 00:37:23 (45 s fallback) even though a reply was attempted inside the window. A minute later the same call succeeded. On the second occurrence `lemon_say` failed the same way before a retry landed. Both times the player was mid-fight or just out of one.
**Status:** Mitigated (not reproduced; 2026-10-07). On the remote path a command's reply is read back out of the shared server log between a size mark and a marker command, and the MCP server shells out to a separate `pdserver.mjs` process per call, so two commands in flight (a `lemon_reply` and the llm keepalive's `lemon mode`, or a `wait_events` poll) could read each other's feedback: a stray "No player was found" from one lands in the other's reply window, which fits a success that reads as a failure and a failure that was not the player's. Every panel command, `run` and `fire`, now takes a cross process lock file in the temp directory (stale after 30 s) while its reply is read. If a real "No player was found" recurs with the player online, capture the matching lines of `latest.log`; the command's own reply and the server's disagree only if the selector truly failed.

### PD-164: barred_vault is broken and its filter hopper is lootable (High)
**Reported:** 2026-10-07-1, 00:50: "The vault is totally wrong, I can pull the stuff out of the hopper, there a double door in the middle of the room with some redstone and none of it does anything and looks like it's missing inner walls." `SpurSpecs.fillFilter` stocks the barred_vault hopper with 4x64 trial keys as a vanilla item filter; the player can open the hopper and take them, and under J7 unused trial keys redeem for emeralds at the floor clear, so this is an emerald exploit as well as a broken room. The door/redstone mechanism itself does nothing, so the vault cannot be completed as designed either.
**Status:** Fixed (2026-10-07). The old template could not work: the hopper pointed into the door block, the comparator and dust drove nothing, the 2 wide door stood in a four block partition that the room could be walked around, and the filter was four stacks of the toll item in a hopper a player could empty. `SpurToll` (called from `PressureSources.arm` for `barred_vault` and `ominous_bargain`) repairs each stamp: it empties the hopper, removes the dead comparator and dust, closes the partition wall to wall with the iron door the only way through, and arms a new `Locks.Kind.HOPPER_KEY` lock that opens the door when the toll item (trial key; gold ingot for the bargain) is dropped in the hopper and takes one. The lock does not count as an unsolved cell for the dwell clock. `SpurSpecs` bakes the same shape for the next template regeneration; the committed `.nbt` is unchanged because it is generated by a dev command. Test: `TollRoomGameTest.aTollRoomIsRepairedWhenItIsStamped`. Live check owed: the vault in play.

### PD-165: First-clear milestone title not noticed on either finish (Medium)
**Reported:** 2026-10-07-1, 00:54. Asked right after two first-time clears (`dungeon_finished first:true` for copper_works at 00:42:25 and cow_pits at 00:52:59): "I don't think I see the dungeon completion message, maybe I just didn't notice it." The build spec calls for a big "<Dungeon> cleared" title with "Act N: x of y dungeons, Mine floor a of b" under it.
**Status:** Fixed, verified live 2026-10-08 (`2026-10-08-1.md`): on the Spawner Dungeon capstone finish he reported "'The Spawner Dungeon Cleared Go Home' worked". Caveat: he did not notice the Infestation milestone earlier in the same session ("No I didn't notice a Big Ingestation cleared"), so the title lands but is still missable. The title did fire, but it was shown in the same tick as the floor clear's own title and a new `StaggeredTitle` sequence replaces a running one, so it was overwritten unseen. Milestones (dungeon cleared, Mine depth, trials complete, act open) now go through `StaggeredTitle.showMilestone`: queued 100 ticks, shown with a 90 tick stay (a floor start holds 50), with the chime or fanfare played at that moment. Tests: `HandlerGameTest.aMilestoneTitleWaitsBehindTheFloorClear`.

### PD-166: No admin command can grant or set scrap (Low, harness)
**Reported:** 2026-10-07-1, 00:32. The player asked "can you give me the 60 scrap?"; there is no command path. `DungeonLog.addScrap` is only called from floor pay and `spendScrap` only from door commits.
**Status:** Fixed, then reshaped by Haul and Blood Doors (2026-10-07): `/dungeon admin compass <player> set <level> [progress]` and `/dungeon admin haul <player> set <n>` replace `admin scrap`. Test: `HaulGameTest.theOperatorCanSetTheCompassAndTheHaul`.

## 2026-10-08 (session 2026-10-07-2, Haul and Blood Doors build e97db42)

### PD-167: Owner death ends the run for the whole party (High)
**Reported:** 2026-10-07-2, 04:05. MrPinoy (owner) died once on Rootworks floor 2 (indirect_magic, omen 1 of 5). Journal: `rescue_eject` for him and `purge` for SirAegerus in the same tick: run over at one of five lives. Player at 04:05:45: "I think I got kicked out of the dungeon even with lives to spare", and at 05:40 he diagnosed it himself: "if the leader dies even just once the whole dungeon run ends. Only happens in multiplayer." `Instances.rescue` routes through `RunLifecycle.dropMember`, whose PD-26 leadership branch purges the record when the owner detaches with members present. A non-owner death later spent exactly one life and the run continued (lives 5 to 4 at 04:18), and a solo owner's rescue re-admits, so the intended shape already exists on both sides of this case.
**Status:** Fixed (2026-10-08). `Instances.rescue` detaches and re-admits a player who has a room to be rescued into instead of routing through `RunLifecycle.dropMember`, so the PD-26 leadership purge no longer fires on a death. A voluntary owner exit and a rescue with no room still end the run. Test: `RescueGameTest.theOwnersDeathDoesNotEndThePartysRun`, `aMembersDeathDoesNotEndItEither`. Unverifiable without a live party: the fifth-death path for an owner death with members present.

### PD-168: Quit and purge exits never settle the haul (High, design)
**Reported:** 2026-10-07-2. `RunLifecycle.quitDoor` applies the keystone penalty and resets to lobby without calling `bankHaul`, so a quit keeps the whole haul; the orphan rule then banks it at 100 percent on the next join (his deepslate haul of 2 survived the 03:46 quit and banked whole at relog). The 04:05 death-purge behaved the same: run 2's floor pay carried into run 3, and the mid-trip lore read "Haul 2" where only 1 had been earned that trip. The fail-half rule (`failHaulKeepPercent`, journaled context "fail" at 04:37: banked 4 of 8) is only reachable through the fifth-death path, so quitting or dying-as-owner is strictly better than failing on every axis. Player asked the same thing: "shouldn't quitting just fail the run instead of depleting the compass?"
**Status:** Fixed for quits (2026-10-08, owner ruling: a quit is a fail). `RunLifecycle.applyQuitPenalty` banks every member's haul as a fail (`failHaulKeepPercent`) and no longer lowers the compass; the quit dialog and chat line say so. Purges are unchanged: they also cover server shutdowns and voluntary leader exits, which should cost nothing, and the death purge is gone with PD-167. Covered by the existing `HaulGameTest` fail-bank tests; `quitDoor` itself has no full-run test. Verify live.

### PD-169: Quit leaves the player in the overworld wearing dungeon gear (Medium)
**Reported:** 2026-10-07-2, ~03:52. After `/dungeon quit` on Ossuary floor 3 and leaving the lobby, he stood in the overworld still holding the dungeon pack while `survival_stashed: 2` showed his survival inventory parked. A relog reconciled it.
**Status:** Open, not reproduced. `InventorySwap.reconcile` runs every tick and on every level change, keyed on the dimension alone, so a lasting mismatch of overworld plus dungeon gear cannot come from the swap logic; `survival_stashed: 2` is also the normal reading while still inside the dungeon dimension. Needs the server log around 03:52 (an `Inventory swap failed` line would explain it) or a repeat with `survival_stashed` read in the overworld.

### PD-170: barred_vault toll rejects ominous trial keys (High)
**Reported:** 2026-10-07-2, 03:27. Deepslate floor 1 rolled ominous and `barred_vault` held the last required spawner. Player: "this floor is ominous so the trial keys that drop are ominous and don't count towards this toll." Confirmed in code: `SpurToll.tollFor` returns `Items.TRIAL_KEY` and `Locks.holdsKey` is an exact `stack.is` check, so `ominous_trial_key` never pays. The floor was uncompletable until he self-issued a plain key and TNT. Discoverability is also thin: the lock's hint line only shows on door use, which he never reached before assuming it was broken.
**Status:** Fixed (2026-10-08). `Locks.matches` accepts an ominous trial key for the trial key toll (and takes it), the iron door's use hint now names what the toll wants, and a player within 7 blocks of the hopper gets an action bar line (once per 10 seconds). Tests: `TollRoomGameTest.anOminousTrialKeyPaysTheTrialKeyToll`.

### PD-171: Rubble sat on the route toward the required vault (Medium)
**Reported:** 2026-10-07-2, 03:23. "There's rubble blocking me and I have no explosives" on deepslate f1, whose last required spawner was behind `barred_vault`; he held gunpowder but no sand, so no TNT. The vault's own entrance was a different doorway, so the rubble cell may have been a legal bonus gate (which `RubbleOrdeal` allows), but on a floor where the vault door already gated progression it read as a second required wall.
**Status:** Fixed (2026-10-08). Cause: `RoomSelector` only treated cells with a TRIAL_ENCOUNTER role as required, but spawners also come from rooms (the Barred Vault and the other combat situations), so rubble could plug the way to the vault holding the last required spawner. `RoomSelector.ENCOUNTER_ROOMS` now marks those cells required. Test: `RubbleRulesTest` (reads the sources and fails if a spawner room is missing from the set). The cell in the report was not recoverable from the logs; the rule is closed either way.

### PD-172: the_herd's sunk gold is unmineable (Medium)
**Reported:** 2026-10-07-2, ~03:44 on deepslate floor 2. The room sinks its gold blocks at y=0, the shell floor row, which `RoomProtection` makes immutable to everyone. The spec's quiet-mining beat ("a player who mines quietly carries gold") cannot happen; the gold is set dressing the fiction says to take.
**Status:** Fixed (2026-10-08). `KnowledgeSpecs.placeHerdGold` stands the four gold blocks on the floor (y=1) and, at stamp time, restores the floor block where the baked template still has them sunk at y=0. Test: `HerdGameTest.theHerdsGoldIsAboveTheShellFloor`. Rooms authored the same way elsewhere were not audited.

### PD-173: Floor identity is invisible and oversold (Medium)
**Reported:** 2026-10-07-2. 04:03: "each new floor just says the dungeon name, when it should also say the floor name." Confirmed: `FloorStartTitle.titleFor` renders `themeName` only, so every floor's big title repeats the dungeon name. At 04:27 he had to ask Lemon "what's this floor called?" on a floor he had just committed (great_taproot). Then 04:28: "it doesn't look like a drip cavern, it looks like a generic dungeon": "The Great Drip Cavern" generated eight generic hall_* rooms plus lush_hollow, so the named final floor has no distinct identity.
**Status:** Title half fixed (2026-10-08): the floor-start title is the floor's node name, with the dungeon name under it on the first floor of a trip (`FloorStartTitle`, test `FloorTitleTest`). Content half: owner ruling 2026-10-08 is to bias final nodes toward rooms that sell their name, which needs more rooms designed first. Open as a content task; until then named final floors can still generate generic halls.

### PD-174: Floor-clear title line runs off screen (Medium)
**Reported:** 2026-10-07-2, 04:55: "that floor finishing text was way too long and went off the screen." `OmenBarText.clearedTitle` joins headline, loot outcome and lives with " | " into one title line ("Floor 2 of Mineshaft cleared | 3 loot rolls | 4 lives left").
**Status:** Fixed (2026-10-08). The bar title is the headline plus lives; the loot outcome is dropped (it is in the completion chat line); the big screen title no longer carries the final floor tail, which moved to the subtitle. Test: `OmenBarTextTest`.

### PD-175: Vault ejects a blank enchanted book (Medium)
**Reported:** 2026-10-07-2, 05:36: "I just got an enchanted book with no enchants", source the vault room (`barred_vault` on the infestation-deviated Rootworks f3).
**Status:** Fixed (2026-10-08). Cause: `spur_barred_vault` and `spur_ominous_bargain` named `minecraft:enchanted_book` and enchanted it with `enchant_with_levels`, which only turns a plain `minecraft:book` into an enchanted one. Both now name `minecraft:book`. Test: `BookLootTest` (no table may do it again). Not rolled live.

### PD-176: lemon_reply does not cancel the unanswered fallback (Medium, harness)
**Reported:** 2026-10-07-2. At least five times (03:28:43, 04:04:17, 04:06:30, 04:14:18, 04:45:37) a `lemon_reply` that returned success still produced a `lemon unanswered` journal entry when the 45 s window expired; the delivered reply and the fallback both fire, and the log claims nobody answered. Distinct from PD-163 (which is the "No player was found" resolution fault). Also seen: an ask answered via ambient chat (`lemon heard`) leaves the formal ask pending to timeout.
**Status:** Mitigated (2026-10-08), root cause not shown. The server already clears every pending question when a reply lands (`LemonGameTest.aDeliveredReplyClearsThePendingQuestion`); the notes' own timeline has the reply at 04:06:5x after the fallback at 04:06:30, so these look like replies slower than the 45 s window (remote command round trips) rather than a cancel that failed. A reply that lands within two minutes of the fallback giving up is now logged `Lemon late reply` and journaled as `lemon_ask` with `answered_by: llm_late`, so the journal stops reading as unanswered. Raising `lemonFallbackSeconds` or calling `lemon_think` first is the harness side.

### PD-177: pdmark marker commands error in latest.log (Low, harness)
**Reported:** 2026-10-07-2. `latest.log` on the remote server repeatedly shows `Unknown or incomplete command` for `pdmark-*` commands (04:03:25, 05:41:31, 05:41:40). If these markers belong to the remote command-reply reader, they may explain PD-163's resolution failures.
**Status:** Not a bug (2026-10-08). `pdserver.mjs remoteCommand` sends an unknown `pdmark-*` command after each command whose reply it reads, on purpose (PD-148), to find the end of the reply in `latest.log`; the server logs it as unknown. It is filtered from replies and from wait events. It did not cause PD-163's "No player was found", which is the server's answer to a player argument that did not resolve. Silencing it would need a harmless command that echoes a unique string.

## 2026-10-09 (session 2026-10-08-1, post-Haul build)

### PD-178: Witch poison still lasts about 30 seconds (Medium)
**Reported:** 2026-10-08-1, 02:16 (repeat of 2026-10-07-2): "Poison still lasts way too long, I end up having to hide for 30 seconds every time I get poisoned. Poison should last 10 seconds at the most." The earlier report's tuning note was never acted on; vanilla witch poison duration is unchanged.
**Status:** Fixed 2026-10-08 (code, awaiting live check). New `PoisonCap` polls every 5 ticks and replaces any poison longer than `poisonMaxSeconds` (config knob, default 10, 0 = off) on a player in the dungeon dimension with a copy at the cap and the same amplifier; witch splashes, cave spiders and the rest all use the vanilla effect, so one clamp covers them. A fresh hit can run at full length for up to 5 ticks. Tests: `PoisonCapTest` (pure), `PoisonCapGameTest` (clamp keeps amplifier, short poison untouched). Unverified live: the feel of the capped poison from a real witch. Generalised 2026-10-09 into `EffectCaps` (poison 10, wither 8, slowness 6, mining fatigue 30; `witherMaxSeconds`, `slownessMaxSeconds`, `miningFatigueMaxSeconds`). Darkness is left alone because the shrieker uses it.

### PD-179: GO HOME board shows "Haul 0 scrap" after the finish auto-bank (Medium)
**Reported:** 2026-10-08-1, 02:35: "This 'Haul 0 scrap' is very misleading, it should say how much scrap I've collected in the dungeon" and "a player gets to the Go home and sees that they have 0 scrap, of course they'll think it's a bug". `dungeon_finished` banks the haul in the same tick, so the end-of-dungeon board reads an empty haul the player just earned. Confirmed live: he pulled the lever at 02:36 and journaled `scrap_left: 0`.
**Status:** Fixed 2026-10-08 (code, awaiting live check). Instant payout kept. The finish now records what it banked per member (`IntervalState.finishBanked`); the finished GO HOME board reads "Banked 7 scrap" (party: "Banked: Kris 7, Bob 4") instead of "Haul 0 scrap", the lever confirm says the finish already banked N scrap, and the HOME title shows the scrap banked on that pull. Tests: `IntervalBankingTest` (Banked wording, Haul wording unchanged before a finish). Unverified live: the board on a real finish.

### PD-180: "Home 4 chests" end line is unreadable (Low)
**Reported:** 2026-10-08-1, 05:17: "'Home 4 chests' what does that mean?" The dungeon-finished screen's Home line names the chest count with no units or verb.
**Status:** Fixed 2026-10-08 (code, awaiting live check). The HOME title subtitle is now "Banked 7 scrap. 4 chests in your reward barrel." (`IntervalBanking.homeSubtitle`). Test: `IntervalBankingTest`. Unverified live: the title on screen.

### PD-181: Three random doors conflict with the act system; players want to choose act and dungeon (High, design)
**Reported:** 2026-10-08-1, 04:00: "The 3 random doors doesn't really work with the new dungeon act system" then "players should be able to choose the act and dungeon". Confirmed as his single top pick at wrap (05:43): asked for "the first thing you'd change", he answered "Redesigning the dungeon selection." The random-offer model predates the act graph; under acts the offer can hide the dungeon he wants or push a dungeon he has finished, and the Spawner-Dungeon-first-door rule is a band-aid over the same problem.
**Status:** Wave B built 2026-10-09 (code, awaiting live check): the Astrolabe Room. The first staging room of a trip shows one act at a time as a row of 1 by 2 doors on the owner's pattern (`HallLayout`: spaces 2, 4, 6, 9, 11 and 13 filled middle outward, dungeons left to right by compass, the doorway slot at 7 and 8 empty); the owner right-clicks the astrolabe (a clock display over a copper plinth, with a click box) to turn to the next open act, sneak to turn back. Each door has the dungeon's token block as a doormat, a waxed sign naming it, and a copper bulb for its state (lit, oxidized when finished, dark with an iron door when locked by compass or by an unfinished act). Selecting a door dims the other bulbs and previews the dungeon exactly as before; the lever commits. The Endless Mine stands at an end of the same row once it opens. `Keystone.offers` asks `HallOffers` first (knob `hallEnabled`, default true; off restores the three random doors), `/dungeon reroll` says the doors are no longer dealt, and a repeat finish pays `repeatFinishEmeraldPercent` (50) of the finish emeralds. Tests: `HallLayoutTest`, `HallDataTest`, `HallRoomGameTest` (both wall orientations: doors, iron when locked, signs, plinth, three astrolabe parts, dismantle; offers match the act), `RoomFurnitureTest` updated. Deviations from the design reply, for the owner: special doors share the main row (its two ends) instead of the side walls; the wide glass preview wall is not built (the existing slot window and side window show the first room); the DESCEND lever stays where it was rather than on the astrolabe plinth; any member may open a door (the existing party decide rules apply) rather than only wishing; the astrolabe does not animate, it plays a chime and particles and shows an Act title; no Next line on a lobby sidebar yet; `act_turned` and `dungeon_selected` journal events are not written. Unverified live: all of it, especially how the row, signs and astrolabe read on screen and that the door previews still show the first room.

### PD-182: Scrap progression is flat; owner proposes rising costs and scaled rewards (Medium, design)
**Reported:** 2026-10-08-1, 05:02: "make it so each level requires more scrap than the previous and then make higher level floors reward more scrap appropriately." Current model charges a flat scrap-per-chart and pays flat per floor, so high-level floors feel identical to floor 1 and deep runs are not better paid.
**Status:** Wave C built 2026-10-09 (code, awaiting live check): the scrap curve. Going from compass c to c+1 costs `scrapCostBase + floor(c / scrapCostEvery)` (4 and 3: compass 1 costs 4, 5 costs 5, 10 costs 7, 25 costs 12, 50 costs 20), and a big haul crosses the rising prices level by level (`ScrapMath.bank`). A floor pays its dealt step plus `scrapPerAct` for each act above the first plus `scrapFinalBonus` on a final floor (`FloorPay.bonus`); an Endless Mine floor pays +1 per `endlessDepthEvery` floors down, capped at `endlessDepthMax`, instead of the act bonus; below the player\u0027s compass a floor pays `belowCompassPercent` (50) of that, at least 1. The bar shows the live price everywhere (`Compass 10: 4/7`: compass lore, sidebar, bank line, `/dungeon` compass command). Migration: a saved bar that already meets the new price settles into a level when it loads (`DungeonLog.Entry`), so a compass never drops; the pool-era conversion clamps to the new bar. The floor-pay chat line now says "(below your compass)". Tests: `ScrapMathTest` (cost table, rising-price banking, bonus, half below compass, migration), `DungeonLogTest`. Not built: the one-time "Levels now cost more" title on first join (it needs a persisted flag; the compass lore shows the new price instead). Unverified live: levels per trip at compass 10 to 15 (target about 2 per dungeon in the right act) and how "+1 scrap (below your compass)" reads.

### PD-183: Sculk mechanics read as two systems; sensor_gallery should be replaced (Medium, design)
**Reported:** 2026-10-08-1, 02:20: "two things, I don't like how these sculks act differently then the ones that spawn enemies, two this room needs to be replaced with something else" and "sculk-like things like the shrieker and sensor should read as stealth mechanics I think". The sensor gallery's sensors feed the omen bar while shriekers elsewhere spawn mobs; one block family does two unrelated things and the room itself did not land.
**Status:** Wave E built 2026-10-09 (code, awaiting live check): sculk is one stealth rule. Any room that holds a sculk sensor or shrieker listens (`PressureSources`): each sensor pulse fills the room's Heard meter, and a full meter (`sculkHeardMax` 4, the Ancient City `sculkHeardMaxAncient` 2) is an answer: darkness on the party, a wave, a Heard title, and the meter starts again; a shriek is an answer at once. A room with a spawner that is cleared without ever answering pays `sculkUnheardScrap` (1) into the haul of the members in it ("Unheard. +1 scrap"). The Warden wakes at the `ancientWardenAnswers`-th (2) answer on the Ancient City's final floor. The sidebar shows `Heard 2/4` while a member stands in a sculk room. `sensor_gallery` is retired (its manifest is gone; the spec and template stay for the situation tests) and the **Hush Gallery** replaces it in the Deepslate and Ancient City biases: a hall with a loud dripstone strip under two shriekers and a longer quiet wool ring, six sensors between, a spawner at the far end, nothing needed to cross. Tests: `SculkHeardTest`, `CapstoneRulesTest`, `SidebarLinesTest`, `RoomLibraryGameTest`. A full meter now makes the room's nearest shrieker scream through vanilla `tryShriek` (`PressureSources.shriekAt`); vanilla's own sensor-to-shrieker relay (8 blocks, wool blocks it) also shrieks and is counted by the same shriek handler. A sculk answer sends a **Warden whelp** (`Whelp`) in place of an omen wave: half-size Warden, melee only (`SonicBoomWhelpMixin`), 20 hp, 4 damage, speed 0.3, always angry at the nearest member, no loot, gone after 15 s, one at a time per instance, and the Heard meter cannot rise while it is out (config `whelpHealth`, `whelpDamage`, `whelpSpeed`, `whelpSeconds`). Unverified live: whether it skips the roar and how fast 0.3 feels against the player. Not built: the catalyst adding to the meter. Unverified live: whether `Heard 2/4` reads as stealth, and whether +1 scrap is worth sneaking for.

### PD-184: Capstone floors end flat; no finale fight (Medium, design)
**Reported:** 2026-10-08-1, 05:17: "There wasn't a boss for copper works" and 05:28: "There should be some extra challenge in the last floor". The Spawner Dungeon has its brood wave, but ordinary story dungeons (Copper Works cleared at 05:16) end on a normal floor with no escalation.
**Status:** Wave D built 2026-10-09 (code, awaiting live check): the finale. An ordinary dungeon whose JSON has a `finale` ends its final floor in a last stand (`FinaleWave`): when the final floor\u0027s spawners are cleared the title says the floor stirs, `finaleCountdownSeconds` (3) later a wave of the dungeon\u0027s own mobs stands up around the party, sized to it (`FinaleRules`: +`perMemberPercent` per member past the first, never over 40), and from Act 2 a named elite leads it with a red boss bar (health grows `finaleEliteHealthPercentPerMember` per extra member). The terminal pad stays shut until all of it is dead (the brood chamber\u0027s gate), and a won finale pays `finaleRewardChests` (1) extra reward chest. Authored: Mineshaft, Infestation, Ossuary, Rootworks (waves), Copper Works (The Foreman), Deepslate (The Deep Sentry), Frostworks (The Frost Warden); Cow Pits stays short; capstones keep their own fights and the loader refuses a finale on one. Knob `finaleEnabled`. Tests: `FinaleRulesTest`, `FinaleDataTest`, `FinaleWaveGameTest`. **Reworked 2026-10-09 (owner direction):** the Kennels are pillagers raising wolves to be cruel. Every untamed wolf in a Kennels instance goes for the nearest player in sight and keeps going (`HostileWolves`); only a tamed wolf, the Lost Dog's wolf and a pack whose guard has fallen are calm (tag `calm_wolf`). The Kennel Run's gates stand open and its wolves charge; no more feeding. New room **Guard Tower**: a pillager with a crossbow on a fenced tower with a ladder, wolves loose on the floor; kill the guard and the pack goes quiet. The Kennels' spawners (`kennels_tier_N`) are pillagers and wolves, with vindicators from tier 2 (no evokers, no ravagers); the finale adds a pillager handler. Feral is retired: no signature affix anywhere, its cube recipe gated out (`min_level` 999); Wolf Hollow in Rootworks is now the lost wolves floor (bias `lost_dog`). Differences from the design reply: no iron-door seal on the cell (the pad is the gate), the wave stands up around the party instead of at the last spawner\u0027s room, and no slime size is set. Unverified live: whether it lands like the brood wave ("barely enough") and whether an Act 1 wave is enough of an ending.

### PD-185: Copper Works mobs should wear copper gear (Low, content)
**Reported:** 2026-10-08-1, 04:55: "could we thematically make it so that all mobs wear two pieces of copper, including weapons?" scoped at 04:55: "just in this copper dungeon".
**Status:** Wave D built 2026-10-09 (code, awaiting live check): a dungeon can carry a `mobUniform` (armour pieces to draw from, pieces for melee and ranged mobs, a melee weapon, a chance). Copper Works has one: melee mobs (zombie family, vindicator, piglins) wear one random copper piece and a copper sword, skeletons and strays and pillagers keep their bow and wear two pieces, creepers and spiders wear nothing; nothing it wears drops. Applied when a mob loads into the dungeon (`MobUniforms.apply` from the entity-load hook), so the finale wave wears it too; mobs placed while a floor is being stamped, before the trip is known, do not. Knob `mobUniformEnabled`. Tests: `MobUniformsTest`, `FinaleDataTest`, `FinaleWaveGameTest`. Unverified live: whether copper armour lengthens a Copper Works clear noticeably.

### PD-186: Hopper-key toll room named worst room of the session (Low, design)
**Reported:** 2026-10-08-1, 05:43, in answer to "worst room tonight?": "The room with the key in the hopper to open the iron doors." That describes the `SpurToll` HOPPER_KEY rooms (barred_vault, ominous_bargain) or the Frame Lock room; all three ask the player to throw a specific item into a hopper to open an iron door.
**Status:** Wave E built 2026-10-09 (code, awaiting live check): the hopper-key toll is gone. `barred_vault` is now a vanilla vault block on a dais behind iron bars (`SpurVault`): an ominous floor stamps an ominous vault that takes the ominous trial key, any other floor the plain key, and the reward is the room's own vault table (`vaults/barred_vault`); using the key on it pays the relief life (`PressureSources.vaultPaid`). `ominous_bargain` lost its gold toll and its partition: the chest stands open behind the bottle, and taking from it is the bargain. `SpurToll` and its `TollRoomGameTest` are retired; `Locks.Kind.HOPPER_KEY` has no users left and stays in the code. The floor never needs the key, so the soft-lock rule holds. Tests: `VaultRoomGameTest`. Unverified live: that a trial key used on the vault pays out and reads as a vanilla vault.

### PD-187: Player wants a persistent floor/scrap display, not chat (Medium, design)
**Reported:** 2026-10-08-1, 02:14: "Is it possible to create a HUD? like maybe having the floor information and Chart/scrap information in the top left or right corner of the screen?" then 02:22: "persistent text should be more than enough". Same session at 02:12: "I never read any of the run messages in chat, I usually use them as a log if I think I've missed some information."
**Status:** Open, design. Server-side options: a scoreboard sidebar, a boss-bar style persistent text display, or an in-world display at the player. A real HUD needs a client mod; the player ruled persistent text sufficient. Design decided 2026-10-09 (`docs/decision-2026-10-09-playtest-design-pass.md`, wave A); not yet built. Wave A built 2026-10-09: `SidebarDisplay` and `SidebarLines` (floor, lives, party, spawners, haul or banked, compass; per-player packets on a private scoreboard; `/dungeon display on|off`; knobs `sidebarEnabled`, `sidebarRepaintTicks`). Tests: `SidebarLinesTest`. Lobby sidebar and the "Next:" line wait for the Astrolabe Room (wave B). Unverified live: how it sits next to the omen bar.

### PD-188: Feral affix feels out of place; wants wolf-themed content and a replacement (Low, design)
**Reported:** 2026-10-08-1, 02:31: "Feral seems out of place, we should instead make a wolf themed dungeon, make a wolf/pet themed floor for another dungeon, and create a rare generic wolf themed room. And then create a new affix to replace feral."
**Status:** Wave F built 2026-10-09 (code, awaiting live check): **Restless** replaces Feral in the rolls. A new affix operation `undead_rise_chance` (`AffixEffects.withUndeadRise`): a slain undead mob (zombie family, skeletons, wither skeletons, zombified piglins) on a Restless floor rises once more where it fell after two seconds, with souls and a groan first; 30 percent, 50 on an ominous floor; fire keeps it down and a risen mob does not rise again (`Restless`, `RestlessRules`). Feral stays as a floor's own signature and as the bone cube recipe, but is no longer rolled (`min_level` 999). Wolf content: **The Kennels**, a new Act 1 dungeon (Lodge Gate, Kennel Rows, Hunting Grounds, Bone Yard, The Alpha's Den; theme `kennels` on the Ossuary's spawners and loot; diary entry 26, band 10; the Spawner Dungeon capstone now waits for it too); a wolf floor, the **Wolf Hollow**, in Rootworks (it was to be the Ossuary's Hound Crypt, but the Ossuary's entry floor already has the most exits a floor may have); three rooms: `kennel_run` (pens with stray wolves), `alphas_den` (the Kennels' final room) and the rare generic **Lost Dog** (clear the camp and the dog is yours; any Act 1 or 2 dungeon may draw it). The Kennels' finale is five angry wolves led by The Alpha. Pets: a pack cap, `petCap` 3 per player, the extras sit (`PetCap`). Tests: `RestlessRulesTest`, `FinaleDataTest`, `FloorNotesTest`, `RoomLibraryGameTest` (the new rooms' connectivity). Differences from the design reply: the Kennels' stone and wood come from a copy of the Ossuary's processor list with spruce for the Ossuary's bone; no tier-by-tier new loot. Unverified live: whether Restless reads from the board and whether fire counterplay is reachable without fire aspect.

### PD-189: Mine-themed floors set an ore expectation the content does not meet (Low, design)
**Reported:** 2026-10-08-1, 02:49 on Endless Mine floor 1: "Still yet to see any hidden ores in this room." Hidden ore is Mineshaft-only (`hiddenOre` in the dungeon def); the player generalises it to anything that looks like a mine. Also 02:43 on `deep_shaft_landing`: "This room is very good, but it didn't have enough ore in it."
**Status:** Wave G built 2026-10-09 (code, awaiting live check): the Endless Mine now hides ore (`hiddenOre`: one or two pockets of one to three coal or iron in the rock of every room) and gets richer with depth: every `deepEvery` (4) floors down the mix gains deepslate iron and gold and a room may bury one more pocket, up to two more (`DungeonDef.HiddenOre.atDepth`, `NodeStamper.contextFor(..., floorNumber)`). Its door-board line now reads "Ore hides in the walls. Deeper pays better." `deep_shaft_landing` holds more ore (iron 3 to 5, plus two coal). The per-floor block cap from the reply was not built (rooms are capped by their pockets). `FloorNotesTest` already fails a floor note that names an ore its floor cannot deliver. Tests: `HiddenOreDepthTest`, `BoardTextTest`. Unverified live: that the first Endless Mine floor shows ore in the walls and that digging finds it.

### PD-190: Players do not read run messages in chat live (Medium, design)
**Reported:** 2026-10-08-1, 02:12: "I never read any of the run messages in chat, I usually use them as a log if I think I've missed some information." Every system that delivers critical state through chat lines (floor pay, key redemption, omen notices, milestone fanfare) is invisible in the moment for this player.
**Status:** Open, design. Audit which critical lines are chat-only and move them to titles, the omen bar, action bar, boards or the persistent display from PD-187. Chat stays as the log, by his own framing. Design decided 2026-10-09 (`docs/decision-2026-10-09-playtest-design-pass.md`, wave A); not yet built. Wave A built 2026-10-09 (first slice): a failed dungeon shows a title ("The dungeon claims you", what was kept), a bank shows an action bar line, the finished dungeon title carries the fanfare. The rest of the audit (shop and salvage results, refusals, key redemption) is still chat-only.

### PD-191: A player should be able to join another party from inside their own dungeon (Medium, design)
**Reported:** 2026-10-09, owner: "Someone should be able to join another party from within their own dungeon." Today `PartyService.join` refuses when the player already has an instance ("You are already in a dungeon. Use /dungeon exit first.") and `invite` refuses a target who is in any dungeon (`InstanceRegistry.byMember`), so a player mid-run must quit first, and a quit is a fail for the haul.
**Status:** Built 2026-10-09. The owner's leaving rule (2026-10-09): **leaving a floor in progress for any reason (exit, kick, disconnect, joining another party, going to a different lobby) is a failed dungeon for the leaver, the fail share (`failHaulKeepPercent`); leaving between floors for any reason cashes out in full.** There is no separate forfeit: cashing out is the only thing that keeps the whole haul. Joining another party is leaving the current one, so the same rule applies. An owner who leaves a floor in progress fails the dungeon for everyone; between floors the run ends as at a checkpoint. `PartyService.invite` accepts a target in a different dungeon and warns them; `join` validates the invite, then `leaveOwnDungeon`. Tests: `JoinFromDungeonGameTest`. Unverified live: the full `/dungeon invite` then `/dungeon join` flow between two real accounts and the invite wording.

### PD-192: Leaving mid-floor was a free, full bank for riders and for a party's owner (High)
**Reported:** 2026-10-09, found in an audit of the owner and rider leave paths after the PD-191 ruling (leaving counts as failing). `RunLifecycle.exit` ejected without touching the haul, and the haul then banked in full at the leaver's next login as an "orphan" (`onJoinHaul`), so walking out of a floor in progress beat failing for a rider, and for an owner with a party the run ended for everyone with every haul banked whole. Separately, a solo owner who stepped out of a run that still waits for them (free re-entry, or the reconnect grace) had the haul banked in full at the next login while the run stayed resumable: bank and keep playing.
**Status:** Fixed 2026-10-09 (code, awaiting live check). The owner's leaving rule (2026-10-09): **leaving a floor in progress for any reason (exit, kick, disconnect, joining another party, going to a different lobby) is a failed dungeon for the leaver, the fail share (`failHaulKeepPercent`); leaving between floors for any reason cashes out in full.** There is no separate forfeit: cashing out is the only thing that keeps the whole haul. Implemented by `RunLifecycle.leaveSettle` (riders: exit, drop, left the dimension, join) and `settleLeaderLeft` (an owner leaving a floor in progress fails everyone, also at reconnect-grace expiry); an owner leaving at a checkpoint settles everyone in full as before; a solo owner stepping out of a run that waits for them keeps the haul at risk (`ownsLiveRun` stops the orphan bank). No rider can rejoin a run, so a dropped rider is simply out; if rider rejoin is ever added this rule needs revisiting. `/dungeon party kick` only removes people pre-registered before a dungeon starts, so no haul is at stake there; an in-run kick would go through the same rule. Tests: `JoinFromDungeonGameTest`. Unverified live: all of these with real accounts.

### PD-193: A failed dungeon boots the party out and the lobby has to be reopened (Medium)
**Reported:** 2026-10-09, owner: "failed dungeon should also send them Home instead of booting them out, it's annoying having to reopen the lobby. So if the group fails, they should get sent back home instead of booted." A fifth death ran `Instances.failRunOmen`, which ejected every member to their return point and purged the instance.
**Status:** Fixed 2026-10-09 (code, awaiting live check). When the run has a staging room (`Instances.failReturnsHome`), a fifth death now banks the fail share, revives the dying member, hands keystones back and calls `resetToLobby`, the same path `/dungeon quit` uses: the party stays together in the Home room with the pack as carried and the doors re-armed. If the owner then leaves the lobby the existing leader-left rule closes it and sends everyone out. Admin, untimed and visit runs keep the old exit. A host who leaves a floor, joins another party or never reconnects still closes the dungeon for the party (the host is not there to keep a lobby open). Tests: `JoinFromDungeonGameTest.aFailedDungeonWithARoomReturnsTheParty` (the choice), the existing `failRunOmen` tests (the old exit); the lobby reset itself is the quit path and is verified live only.

### PD-194: Kennels wolf trial spawners soft-lock floors in stone rooms (High)
**Reported:** 2026-10-09-1, Kennels floor 1 (lodge_gate, all generic halls): "the wolves aren't spawning", then "I can't because the wolf spawner is needed to progress". Live block inspection: the uncleared `kennels_tier_2/normal` trial spawner at (484,65,276) had `spawn_data.entity.id=minecraft:wolf` and `next_mob_spawns_at` ~17,000 ticks in the past: the spawn attempt fails the placement check every retry. `kennels_tier_1/2` configs mix `minecraft:wolf` with pillagers at weight 3/3, and wolves can only spawn on `animals_spawnable_on` ground with light above 8; the generic hall floors are stone and dim, so a spawner whose rolls land on wolf can never finish and the floor gate holds forever. Verified by placing grass and torches under the spawner: a wolf spawned ("grass worked"). Admin removed the dead spawner; the gate read 1/1 and the floor ended. The same class of stall can hit any theme config whose potentials include a mob with restrictive spawn rules (wolf, possibly others) spawning into generic rooms.
**Status:** Fixed 2026-10-10 (code, awaiting live check). Read from the 26.2 bytecode: `TrialSpawner.spawnMob` checks the spawn data, free space and line of sight, then ALWAYS calls `SpawnPlacements.checkSpawnRules(type, level, TRIAL_SPAWNER, pos, random)` (custom spawn rules only add to it), so a wolf needs grass and light 9 or more whatever the config says. `TrialSpawnPlacementMixin` now skips the natural placement rules for the `TRIAL_SPAWNER` reason in the dungeon dimension (`DungeonWorldRules.applies`), the general case: any restricted mob (animals, anything needing a surface) can finish a spawner on a stone floor. Free space and line of sight are still checked by the spawner itself. Test: `DungeonWorldRulesGameTest.aTrialSpawnerWolfNeedsNoGrass` (a TRIAL_SPAWNER wolf passes on bare stone, a NATURAL one still does not). Unverified live: a Kennels floor whose spawner rolls wolves clears without grass.

### PD-195: Fire spread destroys Kennels rooms (High)
**Reported:** 2026-10-09-1, two complaints in one session. 04:52 on the Kennels staging break: "Fire damage needs to be turn off" (source never identified). 07:22 on a second Kennels floor with real kennel rooms (kennel_run, guard_tower): "need to turn fire spread off", then he left and ended the session: "the kennels doesn't work since ... fire spreads destroying the rooms". The kennel theme builds in spruce; torches, lava or campfires in the room templates (or omen fire hazards) ignite it and `fireTick` spreads it through the build.
**Status:** Fixed 2026-10-10 (code, awaiting live check). The ignition source was never identified from the log (a burning mob, a lava drip or a fire charge are the candidates); the fix does not need it. `FireBlockNoSpreadMixin` replaces `FireBlock.tick` in the dungeon dimension: a fire block never burns a neighbour or lights another, it flickers 1 to 2 seconds and goes out. Fire damage to mobs and players, burning mobs and the Restless counterplay (a burning undead stays down) are untouched; only the fire BLOCK stops spreading, and the rest of the world keeps vanilla fire. Test: `DungeonWorldRulesGameTest.fireDoesNotSpreadInTheDungeon` (400 fire ticks beside spruce planks burn none). Unverified live: a Kennels room survives a fire charge or a burning wolf.

### PD-196: Vault keys should last the dungeon's life, not one floor (Medium, design)
**Reported:** 2026-10-09-1, deepslate floor 4: "Vault keys should last the dungeon lifespan not the floor's lifespan. I should be able to carry a key from floor 2 into floor 3 of the same dungeon and use the key in floor 3." Today trial keys never leave their floor (L53/J7: unused keys redeem for emeralds at the clear).
**Status:** Fixed 2026-10-10 (code, awaiting live check), owner ruling: vault keys last the dungeon's life and settle for emeralds when the haul banks, the same moment scrap is paid out (`RunLifecycle.bankHaul` calls `redeemKeys(player, keep)`): at Home, at a finish, and at a failed dungeon (which pays the same fail share as the haul). Plain and ominous keys stay separate, as do their vaults. The floor clear no longer takes keys. Players only go Home between dungeons, so no bank sits between two floors of one dungeon. Salvage refuses keys with 'vault keys cash in when you bank'. Test: `PromisedGearGameTest.aFailedDungeonPaysItsShareOfTheKeys` (the share), `EconomyGameTest` (the full pay). Not covered: a member who is offline when the haul banks keeps their keys in the saved pack (they were not redeemed). Unverified live: a key carried from floor 2 to floor 3 opens the floor 3 vault, and the bank line reads '+N emeralds for vault keys'.

### PD-197: Copper chests should be able to pay armour or items (Low, content)
**Reported:** 2026-10-09-1, after the deepslate run: "We should add armour or items to be possible rewards in the copper chest."
**Status:** Fixed 2026-10-10 (code, awaiting live check), owner ruling: a floor can promise a rolled piece of gear. A node's `rewards` may carry `gear:<slot>:<tier>` (`gear:weapon:2`); the piece is rolled from `gear/<slot>_<tier>` when the door is generated, shown on the door board by name ('enchanted iron sword') and paid into the copper chest at the clear, the same piece (`PromisedGear`, seeded by owner, `IntervalState.rewardSalt` and node, so nothing is stored and a new trip rolls a new piece). Added to six detour floors that already promised emeralds: Ossuary Sealed Reliquary (weapon 1), Infestation Hollow Walls (tool 1), Rootworks Sapping Den (armour 1), Copper Works Gear Loft (armour 2), Deepslate Collapsed Landing (weapon 2, the iron sword case), Frostworks Glaze Vault (tool 2). Test: `PromisedGearGameTest.theBoardAndTheChestAgreeOnThePromisedPiece`. Unverified live: the board names the piece and the chest holds the same one.

### PD-198: Power and trim disconnect cleanup raced the server thread (Low)
**Reported:** 2026-10-10, code audit (`docs/audit-2026-10-10-report.md`). `PowerListener` and `TrimListener` each removed the leaver's entry from a plain `HashMap` (`applied`) directly inside their `ServerPlayConnectionEvents.DISCONNECT` handler. PD-12 established that DISCONNECT can fire on Netty's IO thread for an abrupt disconnect, while the server thread calls `applied.computeIfAbsent` for every online player every tick. Two threads on an unsynchronised `HashMap` can lose an entry, throw, or corrupt the table. Scenario: a player's connection drops (timeout, killed client) in the same moment the server thread reconciles another player's worn gear; the removal and the `computeIfAbsent` interleave.
**Status:** Fixed 2026-10-10 (code, awaiting live check). Both handlers now capture the UUID and hand the removal to `server.execute`, the pattern `Instances`, `Lemon` and `PlaytestJournal` already use. Test: `DisconnectHandlerTest` (pure; reads the sources and fails if any DISCONNECT handler does not defer to `server.execute`). Unverified live: the race itself is timing dependent and cannot be reproduced on demand.

### PD-199: A Silenced player's consumable count carried into their next run (Low)
**Reported:** 2026-10-10, code audit (`docs/audit-2026-10-10-report.md`). `PressureSources.silencedUse` counts a Silenced member's consumable uses in the static map `SILENCED_USES` and sends an omen wave on every `silencedConsumablesPerOmen`-th use (default 3). `PressureSources.forget`, called by `Instances.detach` whenever a member leaves an instance, cleared the dwell timer but not this count. Scenario: a Silenced member eats twice in one run and leaves; in a later run, on any instance, their first consumable is the third use and sends a wave. The map also held one entry per player who ever used a consumable while Silenced, for the life of the process.
**Status:** Fixed 2026-10-10 (code, awaiting live check). `forget` now removes the player's count too, so the count lives for one stay in an instance, the same as the dwell timer. Test: `PressureForgetTest` (pure; seeds the private map, calls `forget`, checks only the leaver's entry went). Unverified live: a Silenced player's wave cadence across two runs.

### PD-200: A kill during the config rewrite could leave pocketdungeons.json truncated (Low)
**Reported:** 2026-10-10, code audit (`docs/audit-2026-10-10-report.md`). `PocketDungeonsConfig.load` rewrote the operator's `pocketdungeons.json` in place (open with truncate, then write) on every boot that found a missing or retired key, and again on every `setModuleOverride`. A process kill or full disk between the truncate and the last byte left an empty or partial file; the next boot logs "is empty" or a parse error, runs on in-memory defaults and leaves the broken file, so every tuned knob the operator had is gone. `RoomStore` and `InventoryJournal` already write through a temp file and an atomic move; the config did not.
**Status:** Fixed 2026-10-10 (code, awaiting live check). One `writeAtomically` helper (sibling `.tmp`, then `ATOMIC_MOVE` over the live file) now serves the first-boot write, the boot rewrite and the module override. The boot rewrite used to run while the reader was still open on the same file; the read now finishes first, which an atomic move over an open file needs on Windows. Test: `ConfigSaveTest` (existing round trips plus a check that no `.tmp` is left). Unverified live: a real kill mid-write cannot be staged.
