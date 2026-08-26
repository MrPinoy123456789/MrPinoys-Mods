# Pocket Dungeons — Mythic+ Reconciliation

Design decisions and their reasoning, reconciled against an external Mythic+
spec. This is the *why* behind the affix system, the keystone ladder, the
closed loop, and the bedrock envelope. `VISION.md` says what the mod is for;
this document says why the mechanics are shaped the way they are.

---

## 3. The room and the closed loop

### 3.2 The persistent room

The selector room becomes a persistent, owned, decoratable room, persisted as a
`StructureTemplate` blob (not resident in the world). Owner + whitelist for
breaking and containers; anyone may use stations; ender chest open to all.

#### 3.2.2 Bedrock envelope

A one-block bedrock shell just outside every cell's existing shell, so a player
who digs through a sealed room's polished-andesite floor or stone-brick ceiling
meets bedrock instead of dropping into open void. The dungeon deliberately has
no break restrictions (the quarry depends on that staying true), so this is the
backstop, not a lock.

The envelope does **not** fill the negative space between cells — that volume is
enormous, and every cell already owns all four of its own walls. An
unconditional ring would put bedrock *between* two connected rooms, so the outer
wall ring is added only on faces with no adjacent occupied cell. `voidGuardDepth`
stays on as the backstop for whatever this misses (a reward room or lingering
quarry that predates it, an admin layout with a hole in its cell set); this
costs nothing extra to keep.

#### 3.2.4 The closed loop

On completion, capture the room from the entrance cell, clear it, re-stamp it at
the terminal cell behind a closed door. Order is **capture -> persist -> clear
-> stamp**. The finished dungeon lingers as a quarry (release force-load
tickets, keep the blocks).

**The mod says nothing about the closed loop.** Everything it *does* say is
slang. Silent-vs-slang, not clinical-vs-slang. This is the most memorable thing
in the design and the cheapest to accidentally rationalise away with a UI, a
message, or a loading screen.

**Hazards:**
- A stamp exception mid-plan could orphan blocks. Both failure branches in
  `buildLayout` now route through `teardown`, which sweeps the partial write and
  only then frees the slot.
- A duplicate teardown on the same slot could silently overwrite a different,
  later instance. A `isClearing(slot)` guard rejects a second teardown for a
  slot already mid-clear.

---

## 4. No weekly rotation

Affixes are seeded from the keystone itself — `hash(owner, level)` — never a
weekly rotation. A wall-clock seed would make the keystone's name drift under
the instance watcher, which rewrites stale remotes in place on its interval;
seeding from the key keeps the name a pure function of `(level, affixSet)`.

This design has procedural themes and recipes — strictly more variety than a
fixed dungeon pool — so the rotation was solving a problem this design does not
have, at the cost of a server-wide time-seed nobody wanted to own.

The seeded affixes are not persisted. `DungeonLog` stores the *elective* affixes
only. The seeded ones are re-derived from `(owner, level)` on every read, which
costs nothing and buys two things: no codec field to migrate, and a depleted key
that correctly stops carrying affixes its new level no longer earns.

---

## 5. The affix system

### 5.0 Every affix bends a rule and pays for it with a gift

**Every affix bends a rule and pays for it with a gift.** The `blurb` is where
that debt is settled: it is the line a player reads on the item, and it has to
say both halves in one sentence. An affix whose blurb cannot be written is an
affix that should not ship.

Two kinds:
- **Elective** — taken deliberately at a door, in exchange for extra levels.
  Ominous (Cooked) and Fragile (Big L).
- **Seeded** — handed out by the level thresholds, picked by the key itself.
  Feral, Swarming, Overclocked, Molten, Silenced.

### 5.1 Level thresholds

How many seeded affixes a key carries: `5 / 11 / 17`. Not `5 / 10 / 15` — that
leaves levels 15-25 flat, ten levels in which nothing about a run changes,
sitting exactly where the most invested players live. These three spread the
changes across the whole ladder, with a largest gap of six.

### 5.5 Naming

`<intensifier> <affix> Keystone [<level>]`, with any remaining affixes in a
bracketed subtitle. The intensifier ladder: Baby (1-5), Lowkey (6-10), Highkey
(11-15), Menace (16-20), Unhinged (21+).

Kamu Totems' *convention*, with entirely separate words — same machinery, no
shared code and no shared vocabulary, per the stranger rule.

---

## 7. Affix-by-affix design

### 7.1 Depletion

What a failure costs, as a multiplier over the whole set. **The max, capped at
two. Never the sum, never the product.** A level-20 key must not shed most of a
ladder on one bad night: loss aversion already runs at roughly twice the felt
weight of an equivalent gain, so a doubled depletion is felt as roughly
quadrupled, and a product of two doubling affixes would be unrecoverable in an
evening.

### 7.2 Leadership purge

Leadership does not transfer. If the owner is the one leaving *and someone else
is still in the party*, the whole run ends with them — stricter than plain
purge-when-empty, and deliberately so: transferring ownership is real work
(whose room is it? whose keystone paid?) and the simplest correct rule is "the
run belongs to the person who opened it, full stop."

### 7.3 Molten and Silenced

**Molten** is the game's only lava faucet. A sealed dungeon has none, and lava
gates furnace fuel and, with water, obsidian. The hazard blocks *are* the
reward. Lava and magma are stamped in cell interiors, clear of doors and spawn
anchors, and the dungeon is still passable.

**Silenced** denies consumables (`CONSUMABLE` blocked) and deafens the mobs it
silences you against — trial spawners have tighter `required_player_range`, so
they activate only when you are closer. The kiss is that the mobs cannot hear
you coming; the curse is that you cannot eat or drink.

### 7.4 Feral — wolves

Wolves as run-scoped tameable helpers, 9 coats gated on `DifficultyProfile.lootTier()`,
spawned untamed at stamp time, pinned with `setHomeTo`, bones as the catch
resource (vanilla roll = the catch rate). Optional permanence via Spirit Stone
in the `spiritwolves` mod, with zero coupling.

Verified wolf behaviour, read out of the 26.2 bytecode:

| Fact | Verified behaviour |
|---|---|
| Catch rate | `Wolf.tryToTame` is `random.nextInt(3) == 0` — exactly 1 in 3 per bone |
| Angry wolves | `Wolf.mobInteract` tests `isAngry()` and refuses the bone entirely |
| On success | `tame(player)` then `setOrderedToSit(true)` — the wolf sits down |
| Coat component | `DataComponents.WOLF_VARIANT` is a `Holder<WolfVariant>`; `Entity.setComponent` is public |
| Coat variants | `WolfVariants` exposes 9 `ResourceKey`s (pale, spotted, snowy, black, ashen, rusty, woods, chestnut, striped) |
| Pinning | `Mob.setHomeTo(BlockPos, int)` exists |
| Taming | `TamableAnimal.tame(Player)` exists |
| Anger | `Wolf implements NeutralMob` with `startPersistentAngerTimer()` |

All three taming facts force the design. **The wolves spawn neutral and are
never angered by this mod** — there is no `startPersistentAngerTimer` call
anywhere, because an angry wolf is an untameable wolf and the affix's whole kiss
is that you keep them. The rule a player reads, "don't hit it, feed it", is
enforced by vanilla's anger-on-hit for free and needs no code. And a caught wolf
sits and stays sat: `setOrderedToSit` is deliberately *not* cleared, which is
what stops a six-player party trailing eighteen wolves through a timed run.

The nine vanilla coats are split into three exclusive bands by loot tier, so a
deep run yields coats a shallow one never does and "where did you get that wolf"
has a real answer. Exclusive, not cumulative: a cumulative ladder makes a tier-3
wolf merely *likelier* to be rare, which reads as luck rather than as evidence.

**Cross-mod (zero coupling, all verified):** `spiritwolves`'
`WolfCapture.capture(Wolf, ServerLevel)` is origin-agnostic and uses
`saveWithoutId`, so the wolf variant survives Spirit Stone binding. Spirit Stone
recharge is vanilla anvil + diamonds, no code.
