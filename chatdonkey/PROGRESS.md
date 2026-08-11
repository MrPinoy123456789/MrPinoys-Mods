# Chat Donkey — progress

## Session 3 — M3 (SPEC.md §13, "The pool") + full audit

**Status: v1 feature-complete. All six behaviors, weighted pool, full drop
table, sounds, orphan sweep. Not yet play-tested.**

`./gradlew build` clean with zero warnings, **247 core tests pass**, server boots
and generates 30 line pools across 6 events.

### What M3 added

| §13 item | Where |
|---|---|
| Roadblock | `RoadblockBehavior` — drift + interval-floor re-planting |
| Serenade | `SerenadeBehavior` — orbits, brays every 3s, sings |
| False Alarm | `FalseAlarmBehavior` — fast wide orbit, shortest and chattiest |
| Clingy | `ClingyBehavior` — zero distance, teleports past 10 blocks |
| Weights | `events.json` via `EventPool` / `EventTuning` |
| Full drop table | Gift numbers moved into `settings.json` (§5 "numbers are config") |
| Orphan sweep | `OrphanSweep` — startup sweep + `ENTITY_LOAD` |

**The orphan sweep no longer has the caveat §6 hedged about.** A chunk-entity-load
event does exist (`ServerEntityEvents.ENTITY_LOAD`, in the *lifecycle* package,
not `entity.event`), so stragglers in unloaded chunks are cleaned whenever their
chunk next loads. No permanently accepted orphans.

### Refactor: `AbstractBehavior`

Six behaviors were about to carry six copies of the same bray-announce-look-
schedule-say-exit boilerplate. That is now `AbstractBehavior`, and each behavior
supplies only its movement and its line cadence. Lecture and Food Critic were
moved onto it too. A seventh behavior is now a class plus one line in
`Behaviors.REGISTRY`.

---

## The audit

### Critical — would have broken normal play

1. **The orphan sweep would have discarded every donkey the instant it spawned.**
   `ENTITY_LOAD` fires from *inside* `addFreshEntity`, so it fired for our own
   donkey while `Events.start` was still mid-flight — tagged already, but not yet
   registered as an active event. The sweep would have judged it an orphan and
   binned it, every single time. Found by tracing the call order rather than by
   test, since it needs a live world to reproduce.
   **Fix:** `ENTITY_LOAD` now only *queues* a candidate; ownership is judged on
   the next server tick, by which point the event exists. The delay is
   load-bearing and commented as such.

2. **Two donkeys with the same name cut each other off.** `Voice` was keyed by
   donkey *name*, and names are drawn at random from a ten-name pool with up to
   two events running — so roughly one pair in ten was two Duncans, and each
   one's lines silenced the other's for an unrelated player.
   **Fix:** keyed by donkey UUID. The name still seeds the voice, which is what
   it was actually for.

3. **Right-click spam had no rate limit.** The `hit` pool is limited to one line
   per three seconds; the `deny` pool, which fires on every refused click, was
   not. Clicking is much faster than swinging, so this flooded chat *and* the
   animalese.
   **Fix:** extracted `RateLimit` from `HitReactions` and applied it to refusals
   too. Both now share one implementation and one test.

### Real edge cases, fixed

4. **Walking through a portal stranded the donkey.** The event kept running with
   the donkey in the old dimension, steering toward coordinates in a world it was
   not in. Now `ActiveEvent.separated()` ends the event as `ABORTED` — no gift,
   no cooldown penalty.

5. **Spectators got events.** A spectator cannot be obstructed, cannot be handed
   a gift, and cannot see the joke land. Now skipped in the trigger loop.

6. **`gracePeriodCommand` was parsed but never read.** It is now the operator's
   off switch for `/donkey grace`, so a server that wants the donkey genuinely
   unavoidable can have that.

### Noted, deliberately not changed

- **`/donkey trigger` bypasses the server-wide cap.** Correct for an admin
  command and for the Twitch bridge, which needs to start an event on demand.
- **A diamond in the off-hand gets the refusal line**, because the bribe only
  reads the main hand. Reading both would reopen the double-consume risk the
  main-hand guard closes. Minor papercut; the fix is not obviously worth it.
- **`/donkey grace` is op-gated with no way to open it to all players**, which
  §7 says should be configurable. `gracePeriodCommand` can't be that knob —
  §7 shows it defaulting to `true` while also saying grace is op-gated by
  default, so `true` cannot mean "everyone". **This needs a second boolean
  (`gracePeriodAllPlayers`) and a decision from you**, since it changes the
  spec'd config shape. Left alone rather than invented.
- **`ClingyBehavior` can teleport a donkey into a tight space.** It is immortal
  for the event and discarded after, so nothing is harmed; it just looks silly,
  which is on-brand.

### Not verified — still needs a client

Everything entity-facing remains unplayed. In rough risk order:

1. **Vanilla interaction suppression** (§10) — still the highest risk. Every path
   returns a consuming result, but only play proves a player cannot mount and
   ride the event away.
2. **Roadblock's feel** — §4 says the repositioning cadence is what decides
   comedy vs. broken pathfinding, and it cannot be found in code review. The two
   knobs are `REPLANT_INTERVAL_TICKS` (15) and `REPLANT_DISTANCE` (1.5).
3. **The restart / orphan sweep test.** I could not run it: this environment's
   dev server does not accept console commands (`list`, `stop` and `summon` all
   fail identically before reaching the mod), so I could not plant a tagged
   donkey, stop cleanly, and reboot. **To test:** trigger an event, `kill` the
   server process mid-event, restart, confirm the log reports a swept donkey.
4. Clingy's teleport beat, the Serenade bray rhythm, and whether False Alarm at
   2–4s per line is funny or exhausting.
5. The Apology Carrot and lored diamond rendering on a vanilla client.

---

## Session 2 — M2 (SPEC.md §13, "The verbs")

**Status: M2 code complete and building. M1 was play-tested and confirmed
working before this was started. M2 itself is not yet play-tested.**

`./gradlew build` succeeds with zero warnings, **166 core tests pass**, jar in
the shared `dist/`. Server boots clean and generates 15 line pools.

### What M2 added

| §13 item | Where | Notes |
|---|---|---|
| Bribe | `Interactions.java` | Diamond consumed, `BRIBED`, gracious gift |
| Hit reactions | `HitReactions.java` + `ALLOW_DAMAGE` | Damage cancelled, 1 line / 3s |
| Gift downgrade | `GiftTable.downgrade` | 3+ hits drops one tier |
| Food Critic | `FoodCriticBehavior.java` | Carrot → satisfied, golden carrot → golden |
| `/donkey` set | `DonkeyCommands.java` | trigger/end/grace/reload, plus `status` |
| Server-wide cap | `TriggerRules` | New `SERVER_BUSY` decision |
| Grace period | `PlayerTriggerState` | New `IN_GRACE_PERIOD` decision |

### One new API surprise in 26.2

**`ItemStack.is(Item)` no longer exists** — only `is(Predicate<Holder<Item>>)`.
Item comparison is `stack.getItem() == Items.DIAMOND`. Everything else M2 needed
(`ServerLivingEntityEvents.ALLOW_DAMAGE`, `UseEntityCallback`,
`DataComponents.ITEM_NAME`/`LORE`, `ItemLore`, `Abilities.instabuild`) matched
expectations.

### A spec inconsistency I had to resolve

SPEC.md §4 says three hits "downgrades the gift one tier"; §5's table says the
grudge tier simply *is* what you get for hitting it three times. These disagree
for a player who bribed *and* was rude.

I implemented the **one-tier ladder** (`GRUDGE < STANDARD < GRACIOUS < SATISFIED
< GOLDEN`), because it satisfies both readings where they overlap — a downgraded
`STANDARD` *is* `GRUDGE`, which is §5's row exactly — and gives the kinder answer
where they don't: a player who paid a diamond and then got shirty drops to
`STANDARD` rather than losing the diamond for nothing. **If you'd rather 3 hits
always mean "nothing at all", that's one line in `GiftTable.select`.**

Also decided: the golden roll cannot rescue a grudge (it is applied after the
downgrade, and skipped at `GRUDGE`), and a fed Food Critic pays by *treat* rather
than by roll — a golden carrot is the stated way to earn the golden tier, so it
must not be left to a 2% chance.

### A real bug the tests caught, and the guard added for it

**Bribing a Food Critic asked for `foodcritic.exit_bribed`, which did not
exist** — the donkey would have left in total silence. The Food Critic is
bribeable like anything else, but its pools were written around feeding.

Two fixes:

1. `LinePools.pickFor(behaviorId, moment, random)` now falls back from
   `foodcritic.exit_bribed` to a bare `exit_bribed` — so a missing pool degrades
   to a generic line rather than silence.
2. A **coverage test** walks every registered behavior × every reachable
   `EndReason` × hit/no-hit and asserts a pool exists. It generates 10 assertions
   today and will generate 30 when M3's four behaviors land. I verified it is not
   vacuous by deleting a pool and watching it fail.

`DefaultLines.java` **moved from `fabric` to `core`** to make that test possible.
It was always pure data with no Minecraft imports, so it belongs there anyway,
and the test now asserts against the genuinely shipped lines rather than a copy.

### Also fixed

`HitReactions` originally used `Integer.MIN_VALUE` as the "never spoken" sentinel;
`currentTick - MIN_VALUE` overflows and wraps negative, which silently swallowed
the *first* hit reaction of every event. Now a boolean flag. Caught by test.

`Events.tickAll` derived the end reason after collecting finished events, which
would report `WAITED` for a behavior that ended early. Now the reason is decided
where the ending is detected.

### Deliberate scope calls

- **Behavior selection is an even coin-flip between the two.** Weighted selection
  from `events.json` is explicitly M3; shipping a weighting system with two
  entries and nothing to weigh would be pretending.
- **`/donkey status` is not in §9.** Added anyway — with a server-wide cap now
  enforced, "why is nothing triggering" needs an answer that isn't reading logs.
- **Orphan sweep still not implemented** (M3). Still not urgent, for the same
  reason as M1: immortality keys off the active-event registry, not the tag, so
  an orphan is an ordinary killable donkey.

### Not verified

M2 is **unplayed**. Everything below needs a client and a second pair of hands:

- the bribe actually consuming exactly one diamond (the off-hand double-fire
  guard is reasoned, not observed),
- vanilla donkey interactions being fully suppressed — **the highest-risk item**:
  §10 warns that letting one click through means the player mounts and rides the
  event away. Every path returns a consuming result, but only play proves it,
- hit reactions firing at a sane rate while a player swings,
- the Apology Carrot and lored diamond rendering on a vanilla client,
- `/donkey grace` actually preventing the passive timer.

---

## Session 1 — M1 (SPEC.md §13, "The visit")

**Status: M1 code complete and building. Not yet play-tested with a real
client — see "What is NOT verified" below before starting M2.**

`./gradlew build` succeeds, 67 core tests pass, jar lands in the shared
`a:\MrPinoys Mods\dist\MrPinoys_chatdonkey-0.1.0.jar`. Compiles with zero
warnings.

---

## §12 verification results — all seven items checked against the merged jar

Checked with `javap -cp ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`.
**Three of the spec's guesses were wrong.** Do not trust the spec's names over
this table.

| § | Spec said | Reality in 26.2 | Verdict |
|---|---|---|---|
| 12.1 | `Mob.getNavigation()`, `PathNavigation.moveTo(d,d,d,d)` | Both exist, both public. Returns `boolean` | ✅ **as specced** |
| 12.2 | `getLookControl().setLookAt(Entity)` | Exists, plus four more overloads | ✅ **as specced** |
| 12.3 | `Entity.addTag(String)` / `getTags()` | `addTag(String)` ✅ — but the getter is **`entityTags()`**, not `getTags()` | ⚠️ **renamed** |
| 12.4 | `EntitySpawnReason.EVENT` (guess) vs `COMMAND` | **`EVENT` does exist** — 19 constants, both present. Using `EVENT` | ✅ **spec's guess was right** |
| 12.5 | `AbstractHorse` interaction methods | `mobInteract`, `setTamed`, `isTamed`, `setOwner` all public | ✅ (only matters in M2) |
| 12.6 | Knockback hook | Not pursued — healing on `ALLOW_DEATH` alone is sufficient, as §6 permits | ➖ deferred |
| 12.7 | Chunk-entity-load event for orphan sweep | Not pursued — orphan sweep is M3 | ➖ deferred |

### Three package/name changes that will bite you if you trust the spec

1. **`EntityType.DONKEY` does not exist.** The per-entity constants moved out of
   `EntityType` into a separate holder class: it is now
   **`net.minecraft.world.entity.EntityTypes.DONKEY`**. `EntityType` itself
   retains only `CODEC` and `STREAM_CODEC`.
2. **The donkey class moved packages.** It is
   **`net.minecraft.world.entity.animal.equine.Donkey`**, not
   `...animal.horse.Donkey`. The whole `horse` package is now `equine`
   (`AbstractHorse`, `Horse`, `SkeletonHorse`, `AbstractChestedHorse` all moved).
3. **`Entity.getTags()` is `Entity.entityTags()`.** Only matters for the M3
   orphan sweep, but it is spec'd as `getTags()` in §6 and §12.3.

### Two more worth knowing

- **`SoundEvents` fields have two different types.** The note-block sounds are
  `Holder.Reference<SoundEvent>` (what `ClientboundSoundPacket` wants), but
  `SoundEvents.DONKEY_AMBIENT` is a **bare `SoundEvent`**. So the quiet chimes
  and the loud bray genuinely need different call paths — `Chime.java` takes a
  `Holder.Reference`, and the bray goes through
  `Level.playSound(..., SoundEvent, ...)`. This is not an inconsistency in our
  code; it is the vanilla API.
- **`BlockState.blocksMotion()` is deprecated** in 26.2. `DonkeySpawn` uses
  `isFaceSturdy(level, pos, Direction.UP)` for the ground test and
  `getCollisionShape(level, pos).isEmpty()` for the clearance test instead —
  both non-deprecated and position-aware, so slabs and stairs answer honestly.

---

## Movement: no fallback needed

**External steering works exactly as §6 hoped — no teleport fallback, no access
widener, no Mixin.** `getNavigation().moveTo(x, y, z, speed)` is public and
behaves as documented, so `ActiveEvent.steerTowardPlayer` drives the vanilla
donkey directly.

Cadence currently: re-path every **10 ticks** (`LectureBehavior.STEER_INTERVAL_TICKS`),
walk speed `1.0`, catch-up speed `1.35` beyond 6 blocks, stop distance 1.5.
**These are guesses and are the single most likely thing to need tuning in play**
(§13 says as much). They are all constants at the top of `LectureBehavior`.

One deliberate simplification: `steerTowardPlayer` ignores its `stopDistance`
argument and paths at the player's exact position. Navigation stops on contact
anyway, and aiming at a computed stand-off point behind the player is what makes
external steering read as jittery. The parameter is kept because Roadblock (M3)
will need it.

---

## What is NOT verified — read this first

**The passive timer has not been observed firing in a real play session.** The
handoff asks for this explicitly, and it is the one "done" criterion this session
did not meet: verifying it needs a real client connection, which this session had
no way to make.

What *was* verified instead, on a real dedicated server (`./gradlew :fabric:runServer`):

- the mod loads and initialises with no errors,
- `config/chatdonkey/settings.json` and `lines.json` generate on first boot with
  the exact §7 values, and the UTF-8 name "Señor Burro" round-trips correctly,
- the server runs a full tick loop with the tick handler installed and does not
  crash or lag.

**Still unobserved, and all of it entity-facing:** the spawn search finding a real
surface, the donkey actually pathing toward a player, chat lines landing at the
right cadence, the gift arriving, and the poof-and-discard. The `run/` directory
is left configured (EULA accepted, flat world, offline mode) so
`./gradlew :fabric:runServer` starts a test server immediately.

**To make the passive timer testable in minutes rather than half an hour**, edit
`fabric/run/config/chatdonkey/settings.json`:

```json
{ "checkIntervalSeconds": 10, "chancePerCheck": 1.0,
  "cooldownMinutesPerPlayer": 0, "minSessionMinutesBeforeFirst": 0 }
```

Then move around — standing still trips the activity gate by design.

---

## Deliberate scope decisions

- **`/donkey trigger [player]` is the only command.** The rest of the §9 set
  (`end`, `grace`, `reload`) is M2. Consequence worth knowing: **there is no
  `reload`, so tuning `lines.json` currently needs a server restart.** If
  play-testing turns out to need fast line iteration, `reload` is ~5 lines and
  worth pulling forward.
- **Immortality is keyed off the active-event registry, not the `chatdonkey`
  tag.** So a donkey orphaned by a crash is an ordinary killable vanilla donkey —
  which is exactly the harmless failure §6 describes, and it means the missing
  orphan sweep is not urgent.
- **`maxSimultaneousEventsServerWide`, `gracePeriodCommand` and
  `gracePeriodMinutes` are parsed and round-tripped but not enforced** (M2).
  Parsing them now means an operator who tunes them today does not lose the edit.
- **`GiftTable` implements the whole §5 drop table** — hit downgrade and the 2%
  golden roll included — because it is pure arithmetic and cheap to test. But M1
  can only ever reach `WAITED`/`ABORTED` with a hit count of zero, and the golden
  tier still pays cobblestone rather than a diamond; wiring the diamond is M3.

---

## One thing added beyond the spec

`LinePools.withDefaults(fallback)` merges any pool missing from the operator's
`lines.json` from the stock set, **in memory only** — their file is never
rewritten.

Without this, the never-overwrite rule has a nasty edge: a server that generated
`lines.json` in M1 would come up with silently empty `hit` and `exit_bribed`
pools the moment M2 starts reading them, and the donkey would just go quiet. This
closes that trap in advance. An operator who deliberately empties a pool gets the
default back — an emptied pool and a missing one are indistinguishable once blank
lines are stripped. Documented and tested.

---

## The voice — animalese (added beyond the spec)

The donkey **audibly talks**: every chat line plays as a burst of short pitched
blips, one per syllable-ish letter, the way an Animal Crossing villager does. A
direct request, and not something SPEC.md §8 anticipated — §8 only budgets a
single quiet `NOTE_BLOCK_HAT` per line.

- `core/Animalese.java` — pure arithmetic: turns a line into `Blip(tickOffset,
  pitch)`. 20 tests.
- `fabric/Voice.java` — schedules the blips across ticks and sends the packets.

Design decisions worth not re-deriving:

- **`NOTE_BLOCK_BIT`** is the square-wave chiptune sound and is by far the closest
  vanilla sample to animalese. The other note blocks are all too musical.
- **One blip per tick (50ms)** happens to be almost exactly Animal Crossing's
  rate, so a line takes about as long to say as it takes to read — which is the
  whole trick.
- **The voice is seeded from the donkey's name**, so Duncan always sounds like
  Duncan across restarts and servers. Measured base pitches: The Auditor 1.196,
  Duncan 1.304, Persimmon 1.374 — audibly different from each other.
- **Doubled vowels collapse to one blip** ("Hee" is two blips, not three).
  Without this it sounds like the donkey is spelling the word out.
- **Terminal intonation**: `?` rises across the line, `!` pushes up, a plain
  sentence settles. Capitals get a small bump, so the donkey's frequent SHOUTING
  is audible.
- **Capped at 28 blips (~1.4s)**, well clear of the 6–8s line cadence, so two
  lines can never blip over each other. A new line from the same donkey also
  interrupts the previous one.
- Volume 0.25, per-player packets at each listener's own position — everyone who
  was sent the line hears it at the same volume, rather than it fading out for
  whoever stood furthest away.

`Chime.spoke()` is gone; the animalese replaced it. `Chime` now only carries the
gift confirmation.

**Not yet heard by a human.** The pitch sequences and timing are verified
numerically and by test, but whether it actually *sounds* like animalese in game
is exactly the kind of thing only playing it will settle. The tuning knobs are
all constants at the top of `Animalese.java` (base pitch range, wobble width,
blip rate, cap) and `Voice.VOLUME`.

### Line-writing voice

The stock lines are written to match: sing-song, over-punctuated, calling the
player "hoofball" and "sport". That was my reading of the original request before
it was clarified as being about the *sound* — it fits §8's "aggrieved,
self-important, convinced it is helping" so I left it, but it is all data in
`DefaultLines.java` → `lines.json`, so dialling the cutesiness back to a
straighter aggrieved donkey needs no code change.

---

## Next session

v1 is feature-complete, so the next session is **play-testing and tuning**, not
building. §13 is explicit that the tuning that matters — event frequency,
Roadblock's cadence, line rhythm — can only be found in play with someone who did
not want to be interrupted.

After that, the open threads in rough value order:

1. The `gracePeriodAllPlayers` decision above.
2. **Twitch integration** (§11). The seams are cut: `/donkey trigger [player]
  [event]` starts anything, `EventPool` makes weights data, and grace is already
  a first-class path rather than a debug flag. A bridge mod runs commands, so it
  stays decoupled per DESIGN.md §2.
3. **The name question you raised** — names are per-event random, so no donkey
   ever recurs. Sticky-per-player would give the suite's strongest retention hook
   (DESIGN.md §6, attachment) something to work with here. Three options are in
   that conversation; nothing in the code blocks any of them.
