> Archived 2026-08-25 (M9 C5): U8 shipped; superseded by the milestone plans
> under `plans/` and their status in `PROGRESS.md`.

# U8 implementation plan — for an implementing agent

Work through this **in order**. Every task is self-contained: it compiles on its
own, has its own verification step, and does not depend on any later task. Do not
skip ahead; later tasks assume earlier ones landed.

**Read first:** `UPDATE_PLAN.md`'s U8 section (the design and its rationale),
`DISCOVERIES.md` (bugs found in play), and this file's "Traps" section below.
`UPDATE_PLAN.md` is the source of truth for *why*; this file is *how*.

**Build command, after every task:**

```bash
cd "A:/MrPinoys Mods/pocketdungeons" && ./gradlew.bat build --console=plain
```

All six pure-JDK tests must pass: `DoorMaskTest`, `PlanSelectorTest`,
`PipelineProofTest`, `DifficultyProfileTest`, `PayoutMathTest`,
`KeystoneMathTest`. **If a task makes a test obsolete, update the test in that
same task** — never leave the build red and never delete a test to make it pass
without saying so in your report.

**Reporting.** After each task, record in `DISCOVERIES.md` anything you found that
this plan did not predict. At the end, write a summary of: what landed, what did
not, every deviation from this plan and why, and every check you could not run.

---

## Traps — read before writing any code

These are all real, all cost hours previously, and all are invisible until they
bite. Every one is documented in `UPDATE_PLAN.md` or `DISCOVERIES.md`.

1. **Verify every Minecraft API shape against the real jar before using it.**
   ```bash
   JAR="/c/Users/Kriss/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar"
   javap -cp "$JAR" net.minecraft.some.Class          # signatures
   javap -c -p -cp "$JAR" net.minecraft.some.Class    # bytecode, when behaviour matters
   ```
   Checking a method *exists* is not the same as checking what it *does*. Three
   shipped bugs came from that distinction.

2. **Never use a vanilla `minecraft:vault` for a reward this mod computes.**
   `VaultBlockEntity$Server.tryInsertKey` rolls the vault's loot table *first* and
   returns early if it comes back empty — before consuming the key, before
   `unlock()`, before `addToRewardedPlayers()`. This silently broke U7's entire
   choice mechanic. U8 replaces the vaults with doors partly for this reason.

3. **`Inventory.add(ItemStack)` returns "did I move *any* of this" and mutates the
   stack down to the remainder.** The idiom `if (!player.getInventory().add(stack))`
   silently destroys overflow. Always use `Payout.deliver`, which checks
   `stack.isEmpty()` afterwards.

4. **`Block.UPDATE_SUPPRESS_DROPS` does not stop a container dropping its
   contents.** That needs `Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` as well.
   Use `TrialContent.FLAGS` / `RoomContent.FLAGS`, which already OR both.

5. **`ClickEvent` is a sealed interface in 26.2, not a class with a constructor.**
   Verified: use `new ClickEvent.RunCommand("/dungeon choose 1")` and
   `style.withClickEvent(event)`. The old `new ClickEvent(Action.RUN_COMMAND, ...)`
   form will not compile.

6. **A misspelt trial-spawner config id does not throw** — the codec drops the
   field and the block silently keeps `FullConfig.DEFAULT`. Always read ids back
   out of the block entity (`/dungeon admin cellreport`) rather than trusting the
   write.

7. **`clearBlocksPerTick` has a hard floor of 1024** in `PocketDungeonsConfig.apply`.

8. **`dungeon_log.dat` lives at**
   `run/world/dimensions/minecraft/overworld/data/pocketdungeons/dungeon_log.dat`,
   not `run/world/data/`. "No file in `world/data`" looks exactly like a failed
   save and is not one.

9. **This mod has zero mixins and must keep it that way.** Everything runs on
   stock Fabric API events. If you think you need a mixin, you have taken a wrong
   turn — say so in your report instead.

10. **Headless testing cannot right-click anything.** Anything involving a click,
    a GUI, or a player standing somewhere goes in `CLIENT_TEST_CHECKLIST.md` as
    unverified. Do not claim it works.

---

## Testing harness

There is no Minecraft client here. Drive `./gradlew.bat runServer --offline`
headlessly from a Python script that pipes timed console commands into stdin and
reads stdout for `Done (` as the boot marker. Details, including two gotchas
about Windows paths and forceload, are in `CONTINUE.md` under "Operational notes".

Useful dev-only commands: `/dungeon admin build [seed] [level] [ominous]`,
`admin cellreport <slot>`, `admin stamptest`, `admin coverage`,
`admin plansurvey <n>`, `admin purge <slot>`, `admin list`.

---

# Phase 0 — known bugs, independent of U8

Do these first. They are small, they are real, and they are not entangled with
anything else.

## T1 — gate the choice vaults on `trialsEnabled`

**Bug:** `TrialContent.applyChoiceVaults` is called unconditionally from
`Instances.java` (two call sites, search `applyChoiceVaults`). With
`trialsEnabled: false` the kill switch is supposed to leave "zero vaults in the
whole instance" — it now leaves U7's three behind.

**Change:** both call sites already sit inside `if (record.isKeystoneRun() && ...)`
blocks. Confirm each also tests `TrialContent.enabled()`; add it where missing.

**Verify:** headless, `trialsEnabled: false`, `/dungeon admin build 4242 3 false`,
then `/fill` sweep or `admin cellreport` showing **zero** `minecraft:vault` in the
instance volume.

**Note:** T10 deletes the choice vaults entirely. Do this anyway — it is two lines
and it keeps the kill switch honest in the meantime.

## T2 — the boss bar's room counter is always 0

**Bug:** `RunTimer.notePresence(int)` exists at `RunTimer.java:57` and is **never
called**, so the bar reads `0/8 rooms` for an entire run. Confirmed in play.

**Change:** in `Instances.onTick`, inside the per-member loop, work out which cell
the player is standing in and record it. Add to `InstanceRecord`:

```java
/** Cells any member has stood in, for the timer's progress readout. */
final Set<PlanCell> visited = new HashSet<>();
```

In the member loop, convert the player's position to a cell via
`record.layout.geometry()` (see `PlanGeometry.cellOrigin`; you need the inverse —
add a `PlanGeometry.cellAt(BlockPos)` returning the `PlanCell` or null if outside).
Add it to `record.visited`, then call
`record.timer.notePresence(record.visited.size())`.

**Verify:** headless cannot walk. Add to `CLIENT_TEST_CHECKLIST.md` §36 that the
counter must advance as rooms are entered, and note it unverified.

---

# Phase 1 — the timer becomes the run (U8 Stage 1)

The foundation. Everything after this depends on it.

## T3 — the timer ticks with nobody inside

**File:** `Instances.java`, `onTick`.

**Change:** the guard is currently

```java
if (record.timer != null && !record.members.isEmpty()) {
    record.timer.tick(interval);
}
```

Drop the membership half:

```java
if (record.timer != null) {
    record.timer.tick(interval);
}
```

**Also:** `RunTimer` adds players to its `ServerBossEvent` on `admit` and removes
on eject. That stays — a bar is only shown to people present — but the clock
underneath it is now independent of who is watching.

**Verify:** headless. Open a run with `/dungeon admin build`, note the time,
`admin list` after 60s, confirm remaining time fell with no player anywhere.

## T4 — instance lifetime: expiry and completion grace

**This is the largest single task in the plan.** Read all of it before starting.

**Today:** an instance dies when its last member leaves (`closeIfEmpty`).
**After:** an instance dies on a clock, and membership is irrelevant.

### T4a — add the two end conditions

In `InstanceRecord`, add:

```java
/** Game tick at which this instance tears itself down, or 0 while the run is live. */
long expiresAtTick;
```

In `Instances.onTick`, per record, after the timer tick:

- **If the run timed out and was never completed** (`record.timer != null &&
  record.timer.overTime() && record.completed.isEmpty()`): deplete the owner's
  keystone by `timedOutDepletion`, message them wherever they are (online check;
  if offline the state write is enough — the remote reconciles on login), eject
  any members still inside, and `purge(server, record, "timed out")`.
- **If completed** (`!record.completed.isEmpty()` and `expiresAtTick == 0`): set
  `expiresAtTick = level.getGameTime() + rewardRoomGraceSeconds * 20`.
- **If `expiresAtTick != 0` and now past it**: eject any members and purge.

**The owner.** `InstanceRecord` has no owner field. Add one — set it to the
opening player's UUID in `enter`:

```java
final UUID owner;
```

Depletion on expiry applies to the **owner only**. Party members riding along
never had a key at stake.

### T4b — delete `closeIfEmpty`

Remove both overloads and every call. An empty instance is now normal — it is a
dungeon waiting for its owner to come back.

**Careful:** `exit` currently ends with `closeIfEmpty(server, record, "last member
left")`. Just delete that line; the instance stays up.

### T4c — `dropMember` stops being a lifetime event

`dropMember` currently removes a member and may trigger `closeIfEmpty`. Keep the
member removal (they are genuinely not in the dungeon any more), drop the
lifetime consequence.

### T4d — remove the disconnect depletion path

Search `Keystones.Outcome.DISCONNECT` in `Instances.java` (the
`ServerPlayConnectionEvents.DISCONNECT` handler). Disconnecting is now free:
remove the `returnKeystone` call from that path entirely. The run keeps running.

**Verify (headless, all four):**
1. Open a run, purge nothing, wait past the timer → instance is gone from
   `admin list` on its own and the owner's level dropped by one.
2. Open a run, `/dungeon exit`, wait → instance still listed until it expires.
3. `admin purge` mid-run → owner's level **unchanged** (the `SERVER` rule).
4. `admin list` shows an empty-but-live instance without error.

## T5 — free re-entry

**File:** `Instances.enter` (the `int keystoneLevel` overload, line ~257).

**Today:** `hasInstance(player)` → refuse with "You are already in a dungeon."

**After:** if the player **owns a live instance**, teleport them back into it
instead of opening a new one. Only refuse if they are already physically inside.

```java
InstanceRecord existing = bySlot.values().stream()
        .filter(r -> player.getUUID().equals(r.owner))
        .findFirst().orElse(null);
if (existing != null) {
    if (existing.members.containsKey(player.getUUID())) {
        // already inside; nothing to do
        return false;
    }
    admit(server, existing, player);
    return true;
}
```

`admit` already handles the teleport, the return point and the boss bar. Put this
**before** the keystone spend — re-entering an existing run must not cost anything
and must not re-roll a layout.

**Verify:** headless. `/dungeon admin build`, then have a fake player enter, exit,
enter again; confirm `admin list` shows one instance in the same slot throughout.

## T6 — collapse the depletion model

**Files:** `Keystones.java`, `PocketDungeonsConfig.java`, `KeystoneMath.java`,
`KeystoneMathTest.java`, `Instances.java`.

**`Keystones.Outcome`** goes from six constants to two:

```java
enum Outcome {
    /** The clock ran out before the run was completed. The only way to lose a level. */
    TIMED_OUT,
    /** Anything else -- completed, left, died, disconnected, purged, restarted. */
    NO_CHANGE;

    int depletion() {
        return this == TIMED_OUT ? PocketDungeonsConfig.timedOutDepletion() : 0;
    }
}
```

**Config:** delete `depletionOnDeath`, `depletionOnExit`, `depletionOnDisconnect`,
`overtimeDepletion`. Add `timedOutDepletion` (default 1, `>= 0`). Remember all five
places a field appears in `PocketDungeonsConfig`: the private field, the getter,
the reset in `apply`'s defaults, the `readInt`/`readString` call, and
`defaultsJson`'s `addProperty`.

**`KeystoneMath.deplete`** keeps its fragile-doubling and its floor-of-1 clamp —
those still hold. Update `KeystoneMathTest` to drop the per-outcome cases and keep
the clamp, fragile, and `upgrade` cases.

**`Instances.returnKeystone`**: every call site that passed `DEATH`, `COMMAND` or
`DISCONNECT` now passes `NO_CHANGE`. The "a completed run can never be charged a
failure" upgrade logic becomes unnecessary — delete it, but leave a comment saying
why it is gone, because it was a real bug fix and someone will wonder.

**Verify:** `KeystoneMathTest` passes; headless death/exit/disconnect all leave the
level untouched.

---

# Phase 2 — the payout cull (U8 Stage 0 and 5)

## T7 — delete the streak

**Files:** `PayoutMath.java`, `PayoutMathTest.java`, `DungeonLog.java`,
`DungeonCommands.java`.

- `PayoutMath.nextStreak` and `streakBonusPercent` — delete, with their tests.
- `DungeonLog.Entry`: drop `streak` and `lastCompletedDateKey`. Keep
  `runsCompleted`, `bestPathLength`, `bestKeystoneLevel`, `keystoneLevel`,
  `keystoneAffix`. Codec fields become optional so old files still load.
- `recordCompletion`'s date-injecting overload and the `/dungeon admin log
  record|show` dev commands existed **only** to test the streak. Delete both.
- `/dungeon log` output: runs completed, best keystone level, longest dungeon.
- Config: delete `streakBonusPercent`, `streakBonusCapPercent`.

**Verify:** build green; `/dungeon log` prints without the streak line; an existing
`dungeon_log.dat` still loads.

## T8 — chests replace the item payout

**Files:** `Payout.java`, `PocketDungeonsConfig.java`, `Instances.java`.

- Delete `Payout.grant` and its callers (`Instances.pay`). **Keep
  `Payout.deliver`** — it is the safe `Inventory.add` wrapper and T13 still needs it.
- Config: delete `payoutBaseCount`, `payoutPerTier`, `payoutPerLevelPercent`,
  `ominousPayoutPercent`, `payoutItem`.
- **Keep `payoutCommand`**, and change its substitutions. It is one config read
  and it is the only remaining way an operator routes rewards through
  `cobbleeconomy` without this mod taking a Java dependency — which is the suite's
  whole coupling model (cross-cutting section 6: never a hard reference). Deleting
  it and re-adding it later is strictly worse than keeping it correct now.

  **Drop `%amount%`** — there is no item count any more and a chest count is not a
  currency amount, so the old name would be a lie. Substitute instead:

  | Token | Value |
  |---|---|
  | `%player%` | the completing player's name |
  | `%level%` | the keystone level the run was finished at |
  | `%chests%` | 1-3, how well they did |

  That gives an operator everything needed to compute their own reward, e.g.
  `cobbleeconomy give %player% %chests%00`. Fire it once per completing member,
  from the same place the completion is recorded.
- `PayoutMath` is now empty except the chest-count helper T10 adds. Move it there
  or keep the file as the home for that arithmetic — your call, but say which.

**Verify:** build green, no dead references, `payoutCommand` still substitutes.

---

# Phase 3 — the reward room (U8 Stage 2)

## T9 — author the reward room template

**File:** `RoomTemplateGenerator.java`, producing one new
`data/pocketdungeons/structure/rooms/reward_hall.nbt`.

**Do NOT write a `dungeon_room/*.json` for this room, and do not give it a
`reward` role.** It is reached by teleport, so it has no doors, so it has no door
mask and the planner has nothing to match it on. Loading it through the manifest
would put it in the coverage grid and risk the one subsystem with measured
guarantees behind it (53/53 coverage, 200/200 plan success) for no benefit.

Load it the way `StaticLayout` already loads the fallback rooms — directly by
`Identifier` through `TemplateStamper.place(level, manager, cellOrigin,
templateId, rotation)`, which calls `manager.get(templateId)` and never touches
`RoomManifest`. Add the id as a constant next to the existing
`ENTRANCE_HALL` / `EXIT_HALL` ones in `TemplateStamper`.

Same 16x16x7 contract as every other room (`y=0` floor, `y=1..5` walls, `y=6`
ceiling, doors 2 wide by 3 tall at local `i` in `[7,8]`). Follow the **doorway lane
rule**: keep `x` in `[7,8]` clear for `z` in `[1,3]` and `[12,14]`, and `z` in
`[7,8]` clear for `x` in `[1,3]` and `[12,14]`.

**Contents:**
- **No doors at all** — this room is reached by teleport, not by walking. That
  makes it mask-family-independent and keeps it out of the planner's coverage
  grid entirely.
- Three chest positions at local `(4,1,4)`, `(8,1,4)`, `(12,1,4)` — a row facing
  the player as they arrive. Author real `minecraft:chest` blocks; T10 removes
  the ones that are not earned.
- A **lodestone** at local `(7..8, 0, 7..8)` (the rotation-invariant 2x2 pad) as
  the way out.
- Decorate freely, but nothing in the chest row's approach.

**Verify:** `/dungeon admin gentemplates` writes the new `.nbt`; rebuild. Then
`/dungeon admin manifest reload` must still report **14 loaded, 0 rejected** and
`/dungeon admin coverage` still **0 holes** — both *unchanged*. If the count went
to 15, you added `dungeon_room` metadata you were told not to add; remove it.

## T10 — stamp the reward room, teleport to it, scale chests by time

**Do not touch the planner.** The reward room is stamped at a **fixed offset
inside the same slot**, outside the planned grid. Slot pitch is 2048 and
`maxGridSpan` is 12 cells (192 blocks), so there is room to spare.

**Offset:** `record.origin.offset(0, 0, -32)` — two cells north of the grid
origin, comfortably clear of any layout. Define it as a constant with a comment
saying why it is safe.

**When:** stamp it lazily on first completion (in `completeRun`), not at build
time. A run nobody finishes never needs one.

**Chest count**, from the timer:

```java
static int chestCount(int secondsRemaining, int totalSeconds,
                      int threePercent, int twoPercent) {
    if (secondsRemaining <= 0) return 0;
    int usedPercent = 100 - (secondsRemaining * 100 / Math.max(1, totalSeconds));
    if (usedPercent <= threePercent) return 3;
    if (usedPercent <= twoPercent) return 2;
    return 1;
}
```

Put this in `PayoutMath` (or wherever T8 left the arithmetic) **with unit tests**:
boundaries at exactly 60% and 80%, over-time returning 0, and a zero-total guard.

**Contents:** set each surviving chest's loot table to
`pocketdungeons:chests/tier_N` — or `tier_N_ominous` if `record.affix ==
Keystone.Affix.OMINOUS` — where `N` is `DifficultyProfile.lootTier()` for the run.
Set `setLootTableSeed(seed ^ pos.asLong())` as `RoomContent` already does. Remove
unearned chests with `TrialContent.FLAGS` (**trap 4**).

**Teleport:** in `completeRun`, after recording completion, teleport the player to
the reward room's centre instead of leaving them on the pad. The **reward room's
own lodestone** then exits the dungeon normally (`exit(player,
ExitReason.EXIT_PAD)`), which means `isOnExitPad` must now match either pad —
it tests "the block under you is a lodestone and you are inside the instance", so
extending the instance bounds to include the reward room is enough.

**Teardown:** the reward room's cells must be in the clear list. `InstanceLayout`
carries the geometry; add the reward room's cell origin to whatever `teardown`
iterates, or extend `bounds()`. **Verify with an `admin purge` followed by a block
census — a leaked reward room is a permanent scar on that slot.**

**Delete the three choice vaults** here: `TrialContent.applyChoiceVaults`,
`Instances.tryClaimOffer`, `InstanceRecord.choiceVaults`, and the
`RitualListener` branch that routes to it. The choice moves to Phase 4.

**Verify (headless):** `admin build`, force a completion, `admin cellreport` or a
`/fill` census showing exactly the expected chest count with the right loot table;
`admin purge` leaving zero blocks behind.

---

# Phase 4 — the selector room and the three doors (U8 Stage 3)

## T11 — pending-offer state

**File:** `DungeonLog.java`.

Add to `Entry`: `int pendingOfferLevel` (0 = none). Optional codec field, same
pattern as the others. Add `setPendingOffer(UUID, int)` and
`clearPendingOffer(UUID)`.

Set it in `completeRun` (the level the run was finished at). Clear it when a door
is taken (T13).

**Verify:** headless, survives a restart.

## T12 — author the selector room

Same contract as T9, no doors to the outside, reached only by teleport. Contents:

- Three **door blocks** at local `(4,1,8)`, `(8,1,8)`, `(12,1,8)`, all facing the
  player's arrival point. A door is two blocks — author `HALF=LOWER` and
  `HALF=UPPER` with matching `FACING` and `HINGE`.
- A **lodestone** pad as the way out (leaving without choosing is allowed).

**Door blocks, by offer** — look these up by identifier rather than through
`Blocks`, because `Blocks.COPPER_DOOR` is a `WeatheringCopperCollection`, not a
`Block`, and indexing it is more trouble than it is worth:

```java
BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:oak_door"))
BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:crimson_door"))
BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:exposed_copper_door"))
```

| Offer | Door | Affix |
|---|---|---|
| `+1` | `oak_door` | none |
| `+2` | `crimson_door` | ominous |
| `+3` | `exposed_copper_door` | fragile |

Copper for fragile is the one to keep whatever else moves: a door that is visibly
part-way through weathering is the affix, and the other three oxidation states are
the obvious ladder when a fourth and fifth affix arrive. Crimson for ominous is
the swappable one — it reads "nether, dangerous" rather than matching vanilla's
blue-grey ominous palette exactly, and nothing depends on it.

Put the three ids in constants with a comment that they are the visual language
for affixes and are expected to grow.

## T13 — door interaction and the clickable accept

**Files:** `RitualListener.java` (it already owns the mod's `UseBlockCallback`),
`Instances.java`, `DungeonCommands.java`.

**Intercept the click.** In `onUseBlock`, before the lodestone branch, check
whether the clicked position is one of the selector room's three door positions
for this player's selector instance. If so:

1. Return `InteractionResult.SUCCESS_SERVER` so **vanilla never opens the door**.
   A door that swings is a door that looks like it did something.
2. Send the offer as chat with a clickable accept.

**The message** (verified API — see trap 5):

```java
Component accept = Component.literal("[ Take this key ]")
        .withStyle(s -> s.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/dungeon choose " + step)));
player.sendSystemMessage(Component.literal("Ominous Door -- Keystone [" + level + "], ominous.")
        .withStyle(ChatFormatting.LIGHT_PURPLE));
player.sendSystemMessage(Component.literal("Every room runs ominous. The reward room rolls the ominous tables.")
        .withStyle(ChatFormatting.GRAY));
player.sendSystemMessage(accept);
```

**The command.** Add `/dungeon choose <1|2|3>`, player-only, **not** op-gated.
It must validate server-side and not trust the click:

- the player has a pending offer (`DungeonLog` — not an item, not the room)
- the step is 1..3

Then: compute the offer with `Keystone.offers(pendingLevel)[step-1]`, write it via
`Keystones.grantOffer`, clear the pending offer, message the player, and send them
home (`exit`, or a direct teleport if the selector room is its own instance).

**Do not gate the command on being in the selector room.** The pending offer is
the authority; the room is the presentation. That also means a player who
disconnects with the chat message on screen can still accept it later.

**Verify:** headless can run `/dungeon choose 2` directly — confirm it writes the
right level and affix and refuses without a pending offer. The click itself goes
in `CLIENT_TEST_CHECKLIST.md`.

## T14 — route the compass to the selector room

**File:** `RitualListener.java`.

Right-clicking a lodestone with the remote now branches on server state:

1. Pending offer (`pendingOfferLevel > 0`) → open/enter the **selector room**.
2. Owns a live instance → **re-enter it** (T5).
3. Otherwise → **start a new run**.

The selector room is its own instance in its own slot, built on demand through
the existing `allocateSlot` / `forceLoad` / stamp / `teardown` machinery. Tear it
down when the player leaves or chooses.

**Verify:** headless drive of each branch through `admin list`.

---

# Phase 5 — the remote and the ominous collapse

## T15 — recovery compass

**File:** `PocketDungeonsConfig.java`.

`keystoneItem` default changes from `minecraft:trial_key` to
`minecraft:recovery_compass`, in both the field initialiser and `defaultsJson`.

**Why it matters:** the trial key is the item this mod's own spawners eject for
its own vaults. Moving the remote off it removes a real collision.

**Verify:** boot a fresh `run/`, confirm `/dungeon key` mints a recovery compass
and the config file writes the new default.

## T16 — one source of ominous

**Files:** `TrialContent.java`, `Instances.java`, `RitualListener.java`,
`DungeonCommands.java`, `PocketDungeonsConfig.java`.

A run is ominous **iff the keystone carries the ominous affix**. Delete:

- `TrialContent.ominousAt`'s depth rule — every cell in an ominous run is ominous,
  every cell in a plain run is not. The method becomes
  `return record.affix == Keystone.Affix.OMINOUS;` at the call site, or goes away.
- `ominousFromLevel` and `ominousRequiresBottle` config.
- `/dungeon ominous` (`DungeonCommands.enterOminous`).
- The off-hand ominous-bottle branch in `RitualListener`.
- The `boolean ominousRequested` / `ominousRun` parameter threaded through
  `Instances.enter`, `buildLayout`, `LayoutStamper.stamp` and `TrialContent` —
  replace with the record's affix.
- `/dungeon admin build`'s `ominous` argument can stay as a dev override; if you
  keep it, say so.

**Keep:** the `ominous` blockstate stamping itself, the `tier_N_ominous` loot
tables, the `ominous_plain_key` trial spawner configs, and the Trial Omen
grant/clear. Only the *routes into* ominous collapse.

**Careful:** U6 shipped one key kind per run precisely because ominous varied by
depth. With ominous now uniform per run, the `ominous_plain_key.json` files may
become redundant — **check before deleting** and report what you find.

**Verify:** `admin build` at an ominous affix → every spawner and vault ominous;
plain affix → none. `plansurvey 200` unchanged.

---

# Phase 6 — cleanup, gated on client testing

## T17 — delete the U3 rollback path

**DO NOT DO THIS until a human confirms the trial loop works in a real client.**
Ask before starting; if the answer has not come, stop and report.

When cleared: delete `RoomContent.spawnMobs` and its helpers,
`DifficultyProfile.mobCount` / `effectiveTier` / `mobRoster` and their tests, the
`partySize` parameter thread (`Instances` → `buildLayout` → `LayoutStamper` →
`DifficultyProfile`), `spawnerDensEnabled`, and `trialsEnabled` with its fallback
branches in `TrialContent`.

`/dungeon party` **stays** — it teleports pre-registered companions in together,
which is real functionality independent of difficulty scaling.

## T18 — documentation

- `UPDATE_PLAN.md`: add a `Shipped:` subsection to U8 recording what was built,
  every deviation, and the measured verification results. Match the style of the
  U1–U7 sections — they are the model.
- `CLIENT_TEST_CHECKLIST.md`: replace §34–43 (they test machinery U8 deleted) with
  checks for the new flow: three-chesting, free re-entry, timing out while
  offline, each of the three doors, walking out without choosing.
- `CONTINUE.md`: rewrite the status table and "what to do next".
- `DISCOVERIES.md`: move anything fixed into the Fixed section.

## T19 — final regression

Headless, on a **fresh `run/`** (a stale one produced a false-positive jigsaw
count once):

1. `gradlew build` — all tests pass.
2. `manifest reload` → **14 loaded, 0 rejected** (unchanged — the reward and
   selector rooms are not manifest rooms). `coverage` → 0 holes.
   `plansurvey 200` → at least 95% first-attempt.
3. `admin stamptest` → all four rotations correct.
4. `admin build 4242` → geometry intact, **zero `minecraft:jigsaw` blocks** in the
   whole volume, every open edge open on both sides.
5. `admin purge` → every block and non-player entity gone, slot returned,
   force-load tickets released. Build again on the same slot and find clean ground.
6. Full config round-trip: delete `run/config/pocketdungeons.json`, boot, confirm
   it regenerates with exactly the surviving fields and no removed ones.

---

## Open decisions — ask, do not guess

1. ~~**Fragile's door.**~~ **Settled:** `exposed_copper_door`. See T12.
2. ~~**`payoutCommand`.**~~ **Settled:** it survives, with `%player%` / `%level%` /
   `%chests%` replacing `%amount%`. See T8.
3. ~~**Reward room placement.**~~ **Settled:** teleport-reached, stamped at a fixed
   offset in the same slot, **not** in the planner and **not** in the manifest.
   See T9 and T10. The offset itself (`0, 0, -32`) is chosen to be clear of any
   layout — if you find a case where a layout reaches it, say so in your report
   rather than nudging it, because that would mean `maxGridSpan` is not holding.
4. **`ominous_plain_key.json`** may be redundant after T16 (see the warning
   there). Report, do not delete unasked.
