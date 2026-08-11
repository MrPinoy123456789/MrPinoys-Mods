# Hand-off: Spirit Wolves v2 — "presence, not power"

You're implementing **section 15** of `SPEC.md` in this repo
(`a:\MrPinoys Mods\spiritwolves\SPEC.md`). Read the whole spec first — section
15 is the part you're building, but sections 1–14 describe the existing v1
mod you're extending, and the conventions in there (verified API style, no
Mixins, module layout) apply to your work too.

## What already exists and works

v1 is fully implemented and builds clean (`./gradlew build`, jar lands in
`../dist/`). Do not break it. The pieces you'll touch or call into:

- `SpiritStone.java` — stack construction, `custom_data` read/write,
  `refreshLore(stack, wolfName, collar)` rebuilds the lore list from scratch
  each call (name/collar/charges lines). **You need to extend this** to
  preserve/append journal lines instead of overwriting them — see below.
- `WolfCapture.java` — full-entity NBT capture/restore. Wolf armor in
  `EquipmentSlot.BODY` already round-trips through this for free; you don't
  need to touch it for that.
- `Tracker.java` — polls every 30 ticks (`POLL_INTERVAL_TICKS`), one pass per
  online player, `forEachStone` iterates their first 36 inventory slots. This
  is where the growl check belongs.
- `Deaths.java` — `ALLOW_DEATH` interception. This is where lore-journal
  triggers for death-saves/fall/void belong — it already computes `remaining`
  charges and has `damageSource` in scope.
- `Binding.java` — where the "Bound `<date>`" journal line gets its start.
- `Summoning.java` — where post-restore scale-setting belongs (right after
  `WolfCapture.restore(...)` succeeds).

## What to build (SPEC.md §15, all three sub-features are independent — order doesn't matter, but 15.1 is smallest)

### 15.1 Living journal
- Extend the stone's `custom_data` schema (currently
  `{ bound, wolf, summoned, uuid }`) with a `journal` string list and a
  `saveCount` int.
- `SpiritStone.refreshLore` currently rebuilds lore from scratch each call —
  change it to append journal lines after the name/collar/charges lines, cap
  total lines (spec suggests 6), drop oldest journal line first if over cap.
- Wire trigger points in `Binding.java` (first bind) and `Deaths.java`
  (death-save, fall, void) as listed in the SPEC.md §15.1 table. Use
  `damageSource.is(...)` against damage type tags to distinguish fall/void —
  **verify the exact `DamageTypes` constant names against the merged jar
  before using them**, they are not confirmed in the spec.

### 15.2 Senses
- Growl: add to `Tracker`'s existing poll loop. Per summoned wolf, check
  `level.getEntitiesOfClass(Monster.class, wolf.getBoundingBox().inflate(16), Monster::isAlive)`.
  Cooldown per-wolf (a tick counter map, or reuse the stone's NBT) so it
  doesn't growl every poll. Play via
  `wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().growlSound()`
  → `wolf.playSound(holder.value(), volume, pitch)`.
- Mark prey: needs a new interaction hook — sneak + empty-hand right-click on
  a summoned wolf. Check whether `UseEntityCallback` (already used in
  `Binding.java`) is the right place, or whether it conflicts with vanilla
  `Wolf.mobInteract` sit/stand toggling on empty-hand right-click — **verify
  this interaction doesn't collide before wiring it**, since vanilla wolves
  already do something on empty-hand click.
- Fetch: needs kill-attribution. `ServerLivingEntityEvents.AFTER_DEATH` is
  already used elsewhere in the suite (see `bounties` mod for the pattern) —
  check `damageSource.getEntity() instanceof Wolf` and match UUID against
  `Tracker`-tracked wolves.

### 15.3 Visible veterancy
- In `Summoning.java`, right after `WolfCapture.restore(...)` returns a
  non-null wolf, set `wolf.getAttribute(Attributes.SCALE).setBaseValue(...)`
  based on `saveCount` (from 15.1's counter) or bound-duration. Small and
  capped per spec (e.g. `1.0 + min(0.15, saves * 0.03)`).

## Verified vs. not-yet-verified

Everything cited above with a class/method name was checked against the
actual 26.2 merged jar (`javap` on
`C:\Users\Kriss\.gradle\caches\fabric-loom\26.2\minecraft-merged.jar`) during
spec-writing. Two things in the plan are **not yet verified** — check them
before coding:

1. Exact `DamageTypes` constant names for fall/void (needed for 15.1's
   fall/void journal lines).
2. Whether empty-hand right-click on a summoned wolf collides with vanilla
   `Wolf.mobInteract` (sit/stand toggle) — needed for 15.2's mark-prey.

If either turns out awkward, it's fine to descope just that one line item —
these are independent, low-risk additions on top of a working mod.

## Conventions to follow (already established in this repo)

- No Mixins. Fabric API events only.
- Verify Minecraft/Fabric API signatures against the actual jar before using
  them — don't trust training-data memory of MC's API, it churns every
  version. `javap` extraction pattern: see any prior session's terminal
  history, or just `jar xf minecraft-merged.jar <path>.class` then
  `javap -p <path>.class`.
- Keep `SpiritStone`, `Deaths`, `Tracker`, etc. as static-method utility
  classes with private constructors, matching existing style.
- Run `./gradlew build` after each sub-feature; confirm the jar in `dist/`
  still updates and the build stays clean before moving to the next one.
- Do not touch sections 1–14 of SPEC.md or the DoD checklist in §13 — v1 is
  done and should stay that way. Update §15 checkboxes/notes as you go if
  useful, but don't rewrite it.
