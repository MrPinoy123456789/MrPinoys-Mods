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
