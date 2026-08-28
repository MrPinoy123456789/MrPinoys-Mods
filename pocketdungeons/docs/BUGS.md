# Known bugs

A running list of reported bugs, with status. Ordered oldest first.
Each entry includes the root cause, exact file and line references, and
a step by step fix plan detailed enough for another agent to implement
without re investigating.

## Fixed

PD-1, PD-3, PD-4, PD-5, and PD-6 are fixed (2026-08-27); `compileJava`
and the full test suite pass. PD-2 stays open below as a content
update, deferred by request.

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
**Status:** Open, root cause identified, fix plan ready

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
**Status:** Fixed (2026-08-27), parts 1 and 2. Part 3 (`setHomeTo` radius) stays deferred as agreed.

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

**Part 3 (deferred): The `setHomeTo` radius issue.**

The `setHomeTo` radius issue (stuttering, refusal to pursue, tamed
wolves not following) is still open. The player has not asked for it
yet. When requested, the fix is to remove `setHomeTo` entirely and
rely on the cell's bedrock envelope and sealed doors to keep wolves
in. A wolf that pathfinds through an open door into the next cell is
still within the dungeon's overall bounds and is caught by teardown.
If containment is still needed, use a larger radius (the full layout
bounds) rather than 6 blocks from one cell. After taming, clear the
home position so the wolf follows the player.

#### Verification

After the fix:
1. Enter a FERAL affix dungeon.
2. Wolves should spawn neutral (not angry, no red eyes).
3. Wolves should be tamable with bones (angry wolves refuse bones).
4. With the default config of 2, only 1 wolf should spawn per cell.
5. Hitting a wolf should still anger it (vanilla anger on hit).

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
**Status:** Open, fix plan ready

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
**Status:** Open, root cause identified, fix plan ready

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

