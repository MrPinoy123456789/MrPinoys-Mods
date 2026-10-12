# D2: T32 Copper Works (copper clock, vents, vats)

## Branch

- Branch: `devin/d2-copper-works`
- Base: `feature/act-1-2-roadmap` @ `f8b1b63b` (with `devin/d1-cow-pits` merged in as `1a01d1b6`)
- Status: implementation complete, tested; ready for the integrator.

## Design (confirmed against the roadmap T32 entry)

Three moves on the already-shipped three-floor Copper Works dungeon:

1. **A trip-scoped copper clock.** Entering the dungeon starts a
   configurable countdown (`copperClockMinutes`, default 20). Cleared
   spawners add time (`copperClockBonusSeconds`, default 60s). The clock
   rides the action bar of every online member every watch tick and goes
   dark red inside the last two minutes. It lives on `IntervalState`, so
   it survives floor changes and dies with the trip.
2. **A soft failure at zero, not a wipe.** Time-out arms the run: when
   the party reaches the final floor the finale triggers on its own
   without the five-second countdown (`CopperFinale.shouldTrigger` plus
   the new `FinaleContext.skipCountdown()`), the Foreman's line calls it
   out, and the vent rhythm runs faster via `copperVentBlownPercent`
   (default 50% of the normal cycle). `ownsTrigger()` stays unset on
   purpose: it takes no context, so it cannot be conditioned on the
   clock; `go = due || shouldTrigger` already gives both the normal
   pre-timeout trigger and the post-timeout self-trigger.
3. **A readable final room.** `master_furnace` gains six floor grates in
   a fixed serpentine band and two water-cauldron vats. Each vent pulse
   sweeps the grates in a fixed order: hiss plus smoke for a full
   two-second tell, then a burst of flame that ignites whoever stands on
   the tile for `copperVentBurnSeconds`. A Foreman lured into a vat
   takes drown damage every second and carries Weakness plus Slowness
   while he is in it. The exit path, the reward pad, and the
   member spawn line stay clear of the band; the south half of the room
   is always vent-free.

## Files changed

### New

- `src/main/java/pocketdungeons/CopperClockRules.java`: pure clock
  math: start on first tick, decrement per watch tick, bonus-time cap,
  red-window rule, pulse interval under `copperVentBlownPercent`,
  fixed-order swept positions.
- `src/main/java/pocketdungeons/CopperClock.java`: runtime clock:
  starts when `interval.dungeonId` matches `copper_works`, action-bar
  display, red last two minutes, first-floor `StaggeredTitle` hint,
  spawner-clear bonus, `expire()` flag, `pulseTick` accessor.
- `src/main/java/pocketdungeons/CopperFinale.java`: finale script:
  `shouldTrigger` on an expired clock at the final floor,
  `ownsTrigger()`, the timed-out `triggerLines`, `onTriggered` skips the
  countdown and arms the vent sweeper plus the vat trap. Vent tell and
  burn ticks are pinned to the watch grid (tell at T, burn at T+40)
  because `onTick` only runs every `watchIntervalTicks`.
- `src/test/java/pocketdungeons/CopperClockRulesTest.java`: pure clock
  and pulse math (21 checks).
- `src/gametest/java/pocketdungeons/CopperFinaleGameTest.java`: five
  finale tests.

### Edited

- `src/main/java/pocketdungeons/IntervalState.java`: added the
  trip-scoped `CopperClock` field.
- `src/main/java/pocketdungeons/FinaleContext.java`: added
  `skipCountdown()` (separate commit `374ecb01`, the only new seam).
- `src/main/java/pocketdungeons/FinaleScripts.java`: one line:
  `register(CopperClock.DUNGEON, new CopperFinale());`
- `src/main/java/pocketdungeons/Instances.java`: the shared watch tick
  calls `CopperClock.tick` once per interval, deliberately outside the
  `isKeystoneRun && floor.completed.isEmpty()` guard so the countdown
  keeps running through pad phases and floor transitions (the clock is
  the trip's, not the floor's).
- `src/main/java/pocketdungeons/FinalFloorEarlySpecs.java`:
  `masterFurnace()` decor: six grate positions in the serpentine band,
  two water-cauldron vat positions.
- `src/main/java/pocketdungeons/PocketDungeonsConfig.java`: seven knobs
  (`copperClockMinutes`, `copperClockBonusSeconds`,
  `copperVentPulseSeconds`, `copperVentBlownPercent`,
  `copperVentBurnSeconds`, `copperForemanVatDamage`,
  `copperForemanVatWeakSeconds`) grouped after `finaleCountdownSeconds`.
- `config/pocketdungeons.default.json`: seven defaults (the checked
  snapshot; `defaultConfigFileTest` caught the miss).
- `src/gametest/resources/fabric.mod.json`: registered
  `CopperFinaleGameTest`.
- `build.gradle.kts`: `copperClockRulesTest` in `pureTestTasks`.
- `src/test/resources/config-fixtures/*` (6 files): regenerated for the
  seven knobs (172 -> 179).
- `src/main/resources/data/pocketdungeons/structure/rooms/master_furnace.nbt`
 : regenerated via a throwaway `GenRoomsTmpGameTest` run (1.4 KB ->
  2.4 KB).

## How each requirement is met

- **Clock starts on entry**: `CopperClock.tick` runs on the watch tick;
  `interval.dungeonId` is set by `beginInterval` before the first tick,
  so `ticksLeft` initializes to `copperClockMinutes` the moment the trip
  lands. No-op for every other dungeon.
- **20 min configurable**: `copperClockMinutes` knob.
- **Action bar / red last two minutes**: `sendOverlayMessage` to every
  `CapstoneFights.onlineMembers` each watch tick; red inside
  `CopperClockRules.RED_TICKS` (2400 ticks, the last two minutes) and
  permanently once blown ("The furnace blew.").
- **Spawner clears add time**: `CopperClock.credit` re-reads the floor's
  `TrialContent.countCleared` set every watch tick and pays
  `copperClockBonusSeconds` only for the growth since last seen, so a
  spawner can never pay twice; the per-floor baseline resets on a new
  `layout.seed()`. No cap: every cleared spawner honestly buys time.
- **Soft failure at zero**: the `blown` latch sets once and never unsets
  (never kills or damages). On the final floor `CopperFinale.shouldTrigger` fires the
  finale itself (`FinaleWave.step` only runs where `TripView.onFinal`
  holds), `skipCountdown()` drops the drum, and `triggerLines` swaps in
  the blow-out wording. Before timeout the normal spawner-clear path
  (`due`) still triggers the finale with its countdown.
- **Vents run faster after timeout**: `CopperFinale.period` uses
  `CopperClockRules.ventPeriodTicks(copperVentPulseSeconds, blown,
  copperVentBlownPercent)`; at the default 50% the 8s cycle becomes 4s,
  and the rule floors the result at `TELL_TICKS + BURN_TICKS` so the
  tell can never be squeezed out by aggressive knob values.
- **Two-second tell**: tell and burn are pinned to the watch grid:
  `toldAtTick = now`, `burnTick = now + 40`; the burn only executes on a
  watch tick at or after `burnTick`. Since `onTick` is watch-gated, a
  naive per-tick countdown could have fired the burn early or skipped it
  entirely; the pinned grid guarantees exactly the 40-tick gap.
- **Fixed order sweep**: `CopperClockRules.ventOrder` walks the band in
  a serpentine (rows north to south, alternating east/west), so the burn
  visibly crosses the room and the next tile is readable.
- **Foreman weakens in a vat**: `onTick` checks `elite()` (the finale
  elite only; other mobs are untouched) against the scanned vat tiles
  once a second; Weakness plus Slowness for
  `copperForemanVatWeakSeconds` and `copperForemanVatDamage` drown
  damage per second while he is in it.
- **Exit / reward pad clear, no vent on member spawn**: the band sits in
  the room's north/center rows, the south exit row has no grate, and no
  grate shares a position with the member spawn line or the door lanes.
  `RoomLibraryGameTest` connectivity passes (no `master_furnace`
  finding; the 13 findings are pre-existing in other rooms).
- **Nothing left behind**: the finale places no blocks (grates and vats
  are baked into the template), so `onFloorEnded` is a documented no-op.
- **Explain the clock on the first floor**: `StaggeredTitle` hint line
  on the first floor's first watch tick ("The furnace is counting
  down." / "Every cleared spawner buys it time."). Diary `entry_13`
  text already in place
  ("It was counting down to something.").
- **Live from the same watch tick**: `CopperClock.tick` is called inside
  `Instances.onTick` alongside `CapstoneFights.tick` and `PotionDrip`.

## Tests

New:

- `CopperClockRulesTest` (pure): countdown, bonus clamp, red window,
  pulse interval under blown-percent, sweep order.
- `CopperFinaleGameTest` (gametest, slots 9935-9939): timeout triggers
  the finale without the countdown, cleared-spawner ownership, the tell
  precedes the burn by at least 40 ticks, fixed vent order, vat
  weakens the Foreman, the south exit row never burns.

Runs:

- `./gradlew copperClockRulesTest`: all checks passed.
- `./gradlew configCharacterizationTest configKnobsTest
  defaultConfigFileTest finaleScriptsTest`: pass (179 knobs).
- `./gradlew test` (full pure suite): BUILD SUCCESSFUL, all green.
- `PD_GAMETEST_FILTER='pocketdungeons-gametest:copper*'`: 5/5.
- `PD_GAMETEST_FILTER='pocketdungeons-gametest:*finale*'`: 48/48.
- `PD_GAMETEST_FILTER='pocketdungeons-gametest:room_library*'`: pass,
  no `master_furnace` finding.
- `PD_GAMETEST_FILTER='pocketdungeons-gametest:stamp_determinism*'`:
  fingerprint `-1655107814` unchanged (same decor, same order).
- `./gradlew dungeonIntegrationTest`: PASS (real server boot).

Fixed during the run:

- `defaultConfigFileTest` failed on the first full-suite pass because
  `config/pocketdungeons.default.json` lacked the new keys; added.
- `finaleScriptsTest` hit `IllegalArgumentException: Not bootstrapped`
  from `CopperFinale.<clinit>`: static `Blocks.COPPER_GRATE` /
  `Blocks.WATER_CAULDRON` fields forced registries in a pure JVM. Made
  the block lookups lazy (resolved inside methods); matches how other
  scripts hold MC references.
- `CopperClockRulesTest` expected 0% blown to mean "no change"; the rule
  treats it as the fastest permitted value. Fixed the expectation.

## What only a live server can verify

- The action-bar cadence and readability (one message per watch tick).
- The `StaggeredTitle` hint actually renders on floor one.
- Vent hiss audibility and the smoke particles' visibility inside the
  tell window.
- The Foreman's vat behavior during a real fight (the test drives the
  state machine; pathing and knockback are live concerns).
- The timeout path end to end: expire mid-trip, ride to the final
  floor, confirm the Foreman arrives without the drum.

## Merge notes

Hot files touched: `Instances.java` (two-line call additions inside the
existing watch tick), `FinaleContext.java` (one method, own commit),
`FinaleScripts.java` (one registration line), `IntervalState.java`
(one field), `FinalFloorEarlySpecs.java` (decor only), config plus its
six fixtures, `build.gradle.kts` (one entry). Expect at most
line-adjacent conflicts in `Instances.onTick` and `FinaleScripts`
if the D3-D6 agents land first.

Dash check: clean (`tools/check_dashes.sh` vs base).
No commits pushed; no deploy attempted.
