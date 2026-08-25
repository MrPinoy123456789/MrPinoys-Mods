# M5 — Wolves and Feral

> Roadmap: `../ROADMAP.md` · Why: `../MYTHIC_PLUS_RECONCILIATION.md` §7.4 ·
> Status: `../PROGRESS.md`

**Goal:** the first kiss/curse proven end to end, and the mod's first cross-mod
surface.

**Blocked on:** M4 (affix set). **Blocks:** nothing.

---

## Verified in the 26.2 bytecode — do not tune by guess

| Fact | Verified |
|---|---|
| Catch rate | `Wolf.tryToTame` is `random.nextInt(3) == 0` — **exactly 1 in 3 per bone** |
| Angry wolves | `Wolf.mobInteract` checks `isAngry()` and **refuses the bone entirely** |
| On success | `tame(player)`, then `setOrderedToSit(true)` — **the wolf sits down** |
| Variant | `DataComponents.WOLF_VARIANT` is `Holder<WolfVariant>`; `Entity.setComponent(type, value)` is public |
| Coats | `WolfVariants` exposes 9 keys: pale, spotted, snowy, black, ashen, rusty, woods, chestnut, striped |
| Pinning | `Mob.setHomeTo(BlockPos, int)` exists |

### Three forced consequences

1. **Spawn neutral, never angered.** Do not reach for
   `startPersistentAngerTimer()`. An angry wolf is an untameable wolf — angering
   them converts the kiss into nothing.
2. **The readable rule is "don't hit it, feed it."** Vanilla wolves anger when
   attacked, so a player who swings first is locked out until the timer expires.
   That is a real choice, and it needs **no code to enforce**.
3. **A caught wolf sits, and stays sat.** Decision (§7.4): **accept it.** Costs no
   code, stops a six-player party trailing eighteen wolves through a timed run,
   and "collect them on the way back" fits forward-only traversal.

---

## T5.1 — Spawn at stamp time

`RoomContent.spawnMobs` was deleted in T17 along with the `trialsEnabled: false`
rollback path it belonged to. The deletion was justified at the time by "two
spawners in one room is two difficulty curves" — a real concern, but one that
applies specifically to *encounter* cells, where `TrialContent` owns the combat
loop and a parallel classic spawner would double the difficulty. It does not
apply to a tameable wolf placed as a feature rather than a fight, and it does
not apply to the broader set of direct-spawn use cases the mod will accumulate
as it grows.

So this milestone **brings `RoomContent.spawnMobs` back** — restored from commit
history (the parent of the T17 deletion, `8dbb006^`, via
`git show 8dbb006^:pocketdungeons/src/main/java/pocketdungeons/RoomContent.java`)
— as a **general-purpose direct entity-spawn path** on `RoomContent`, not a
Feral-only special case. The boundary that actually matters is the one T17 was
protecting in spirit: `TrialContent` still owns *encounter* cells, and
`spawnMobs` is not called there. Direct spawn is for content that is not the
room's combat loop — Feral wolves first, and whatever else follows.

Pin each wolf with `setHomeTo(cellCentre, radius)` so it cannot wander into the
next cell and break the room-geometry invariant.

---

## T5.2 — Coats by tier

Gate the 9 coats on `DifficultyProfile.lootTier()`, so a deep run yields coats a
shallow one never does. This is `VISION.md` §3.6.1's provenance argument applied
to a living thing: *where did you get that wolf* has a real answer.

Set with `entity.setComponent(DataComponents.WOLF_VARIANT, holder)`.

---

## T5.3 — Bones

**Bones must be a guaranteed floor, not a weighted roll.** 1-in-3 odds against a
weighted bone drop is a taming economy that fails silently — a player can do
everything right and catch nothing. `VISION.md` §3.7.4 already demanded floors
for consumables; wolves are what make it load-bearing.

Tier-1 spawners already run skeletons, so the drop exists. The floor lands in M6;
until then, note the dependency rather than duplicating the loot work here.

---

## T5.4 — Permanence via Spirit Stone

**Verified, zero coupling.** `spiritwolves`' `WolfCapture.capture(Wolf,
ServerLevel)` is origin-agnostic and uses `saveWithoutId`, so **the coat survives
Spirit Stone binding**. `TameWatcher` polls `isTame()` on `END_SERVER_TICK`.
Recharge is **vanilla anvil + diamonds, no code**. The Kamu Station is **any
fletching table**.

Nothing needs writing on either side. Follow `kamutotems/INTEGRATION.md`'s
stranger rule: **no compile-time coupling, no shared vocabulary.** PD does not
mention Spirit Stones; it spawns tameable wolves and the other mod does what it
already does.

Run-scoped by default. A wolf left behind is left behind.

---

## Done when

- [ ] A Feral run spawns coat-appropriate wolves, pinned to their cells
- [ ] A player tames one with bones acquired in the same run
- [ ] Hitting a wolf first makes it untameable, and that reads as a rule rather
      than a bug
- [ ] Binding a caught wolf to a Spirit Stone preserves its coat
- [ ] No `spiritwolves` import appears anywhere in `pocketdungeons`
- [ ] `./gradlew build` green
