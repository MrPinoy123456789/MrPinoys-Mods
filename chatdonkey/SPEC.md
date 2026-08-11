# MrPinoy's Chat Donkey — Build Spec

> **Status:** design complete, not yet implemented. Unlike the cobblebending
> and spiritwolves specs, most signatures here have **not** been individually
> verified against the 26.2 merged jar. Where an API was already verified by
> another spec in this suite, it is cited. Where it is a guess, it says so.
> §12 lists everything that must be `javap`-checked before writing code.

---

## 1. The problem this solves

The suite is a set of systems. It has no *characters*. Nothing in it is funny,
and nothing in it ever happens **to** a player rather than because of them.

Chat Donkey is a RuneScape-style random event: a named, immortal, extremely
annoying donkey that spawns near an active player, obstructs them for up to a
minute while lecturing them in chat, leaves a small sarcastic gift, and
vanishes. It is deliberately frustrating — the frustration *is* the content.
On a streamed server it manufactures clips; a later Twitch bridge will let
viewers pay to trigger and amplify it, which is where the frustration converts
to revenue.

> **The load-bearing rule: the donkey is annoying, and it is allowed to be
> costly. It takes a player's time, dignity, attention, and (voluntarily) a
> diamond. If the interruption gets them killed, that is an acceptable outcome
> and frequently the best clip of the session.**

**Revision note (v1.1).** This rule used to read "annoying, never harmful", and
forbade the donkey from ever costing a player health, items, or a fight. That
constraint was never wanted and is withdrawn. It is recorded here because it was
load-bearing for §2's combat row and §7's danger-gate reasoning, both of which
have been rewritten to match — anyone reading an older draft should treat this
paragraph as the authority.

What is *still* true, and is a different claim: the donkey never attacks. It
deals no melee damage, fires nothing, and has no aggro. What it costs a player it
costs by **stealing their attention at a bad moment** — standing in the doorway,
or holding their screen — never by dealing damage itself. The donkey is a
hazard the way a ringing phone is a hazard.

It is a (very small) faucet before Twitch integration, and a sink the moment
the bribe mechanic exists — the diamond paid to make it leave is destroyed.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Model | **Random event, not companion** | No persistent AI, no binding, no mood state. An interrupt, not a pet |
| Entity | **Vanilla `minecraft:donkey`** | Packless, vanilla clients see a normal donkey |
| Duration | **10–60 seconds per event** | A moment, not a slog |
| Immortal during event | **Yes** — damage cancelled, hits fuel dialogue | Hitting it is a *dialogue trigger*, not a solution |
| Spawns during combat | **Yes, by default** | Frustration is the feature. §7 has the one escape hatch |
| May get the player killed | **Yes** | An interruption that can never cost anything is not an interruption (§1). The donkey still never attacks |
| Ends with | **A gift, dropped at feet** | The insult needs a punchline. `giveOrDrop` pattern |
| Early exit | **Right-click with a diamond** | The bribe. Diamond is consumed — a real sink |
| Persistence | **None** | Cooldowns are in-memory; a restart resets them. Harmless failure direction |
| Cross-mod | **None** | Fully standalone, per DESIGN.md §2 |
| Mixins | **Zero target** | Movement is driven externally via navigation, §5 |
| Client requirement | **None.** `"environment": "server"` | Non-negotiable, suite rule |

**Scope note.** Earlier drafts had a persistent companion with mood meters,
resentment, and lecture-mode combat debuffs. All cut. The event model ships
first; anything persistent is a later milestone that reuses the same event
runner. Twitch integration is explicitly **out of scope for v1** but §11
records the hooks it will use, so nothing here paints over them.

---

## 3. Core loop

1. A per-player timer rolls while the player is **active** (moved recently).
2. On a hit: a donkey spawns 4–8 blocks away, brays, and announces itself in
   chat. One event from the weighted pool (§4) begins.
3. For the event's duration the donkey obstructs, follows, blocks, or lectures
   per its event script. Chat lines fire on a cooldown. Hitting it cancels the
   damage and triggers an indignant line.
4. The player either **waits it out**, **bribes it** (right-click with a
   diamond — consumed, event ends immediately, donkey leaves *graciously*), or
   satisfies an event-specific demand (e.g. Food Critic wants a carrot).
5. The donkey drops its gift at the player's feet, delivers an exit line, and
   despawns in a puff of particles.
6. The player's cooldown starts. Default: no event for 20+ minutes.

There is no step where the player can lose anything involuntarily.

---

## 4. The event pool

Events are entries in `config/chatdonkey/events.json` with a weight, a
duration range, a behavior id, and line-pool references. The behavior ids are
code; everything else is data, so operators can retune the whole mod without a
rebuild.

### v1 behaviors — six, one verb each

| Event | Behavior | Duration | Exit condition beyond wait/bribe |
|---|---|---|---|
| **Roadblock** | Paths to a point ~2 blocks along the player's look vector and re-plants itself there each time the player turns or moves | 20–40s | none |
| **Lecture** | Follows at 1–2 blocks, holds eye contact, fires a sass line every 6–8s | 30–60s | none |
| **Serenade** | Circles the player, brays every 3s, chat-lines "song lyrics" | 15–30s | none |
| **Food Critic** | Follows and demands a carrot. Fed any carrot → leaves early with a *better* gift. Golden carrot → best gift | 30–45s | feed it |
| **False Alarm** | Sprints circles around the player shouting about a creeper / lava / "the void" that does not exist | 10–20s | none |
| **Clingy** | Follows at 0 distance; if the player gets >10 blocks away it teleports directly on top of them, with a line | 30–45s | none |
| **Burrs** *(v2)* | Opens a screen full of burrs stuck in the donkey's coat and demands the player pick them out | 30–60s | clear every burr |

Roadblock is the flagship and the hardest to get right — the repositioning
cadence (not every tick; every ~15 ticks, or on player-moved-2-blocks) decides
whether it reads as "comedy obstacle" or "broken pathfinding." Tune in play.

### Burrs — the screen event (v2, not in v1)

The first behavior that occupies the player's **hands** rather than their
position. Six of the seven v1 behaviors are things that happen *around* the
player; this one happens *to* their screen, and it is the most watchable thing
in the mod by a distance.

**No SGUI, and no library at all — this uses the donkey's own chest inventory.**
An earlier draft of this section specced a virtual SGUI screen; using the real
container is better on every axis and is what should be built. The burrs are
literally *in his coat*, the screen is a vanilla horse inventory, and the mod
gains no new dependency.

Verified against the 26.2 merged jar:

| Need | API | Notes |
|---|---|---|
| Give him an inventory | `setChest(true)` | 3 rows × `getInventoryColumns()` = 15 slots |
| Force the screen open | `openCustomInventoryScreen(Player)` | **public** — call it again to reopen |
| Read / write a burr slot | `getSlot(500 + i)` → `SlotAccess.get()/set()` | the `inventory` field is protected; this is the public route |
| Screen will actually open | `setTamed(true)` **required** | `openCustomInventoryScreen` checks `isTamed()` |

No Mixin, no access widener.

- 5–9 burrs are placed in random slots. The player drags them out; that is the
  whole interaction.
- **The screen is closable, and the donkey reopens it** after
  `burrReopenSeconds` (default 5). Closing is always allowed — the annoyance is
  the nagging loop, not being trapped. No reopen cap; the event duration ends it.
- **He keeps finding more.** Every `burrRefindSeconds` (default ~8) with the
  screen open, he adds a burr to a random empty slot and says so. This is the
  joke, and it is the difficulty knob a Twitch bridge would eventually turn
  (§11).
- Clear every burr → `SATISFIED` and the satisfied tier (§5). Run out the clock
  → `WAITED`, and he leaves complaining about your work ethic.

### Three things that will be got wrong if they are not written down

1. **A real container means the player can put items IN.** If the donkey is
   `discard()`ed at event end with a player's diamonds inside, the mod has
   destroyed their items — the one thing that must never happen. **On event end,
   sweep all 15 slots and `giveOrDrop` everything back to the player.** This is
   not a safeguard bolted on, it is part of the event: the burrs he never got
   round to losing are shoved into your inventory as a parting insult.

2. **Taming is required and is specific to this event.** `setTamed(true)`
   contradicts §6's "not tamed" for every other behavior. §10's rule that
   *player* taming attempts are refused is unaffected — the `UseEntityCallback`
   already consumes every click on a tagged donkey, which also blocks mounting,
   and `openCustomInventoryScreen` refuses to open on a ridden horse anyway.

3. **Bribing requires closing the screen first**, since the player cannot
   right-click the donkey while it is open. That is a good beat, not a bug: the
   diamond buys you out of the nagging loop. Preserve it deliberately.

The puzzle state — which slots hold burrs, how many remain, when to re-find one,
completion — is plain arithmetic and belongs in `core`. Only the container calls
are fabric-side.

### Behavior contract

Every behavior is a class implementing one interface:

```java
interface DonkeyBehavior {
    void start(EventContext ctx);          // spawn positioning, opening line
    void tick(EventContext ctx);           // called every server tick while active
    boolean wantsEarlyEnd(EventContext ctx); // e.g. Food Critic was fed
    void end(EventContext ctx, EndReason reason); // WAITED, BRIBED, SATISFIED
}
```

`EventContext` carries the donkey, the target player, elapsed ticks, and the
line pools. `EndReason` selects the exit line and the gift tier. This is the
seam the Twitch bridge will eventually drive — a redemption is just another
way to start an event.

### Hit reactions

Any player damage to the event donkey is cancelled (§6) and fires a line from
the `hit` pool, rate-limited to one per 3 seconds:

- *"OW! You hit ME! Right in my feelings!"*
- *"Chat saw that. Chat. Saw. That."* (works pre-Twitch; funnier post-Twitch)
- *"I'm not moving until you say you're sorry."*

Three or more hits in one event downgrades the gift one tier (§5) and swaps
the exit line for the grudge variant. That is the entire "consequence" system
— it costs the player nothing but a worse punchline.

---

## 5. The gift — an insult with a drop table

The gift must feel underwhelming relative to the hassle. It is the punchline,
not a reward. Dropped at the player's feet via the standard `giveOrDrop`
pattern (add to inventory, drop if full — never destroy).

| Tier | When | Contents |
|---|---|---|
| **Grudge** | player hit the donkey 3+ times | nothing, exit line: *"You're welcome for the company."* |
| **Standard** | waited it out | 2–8 cobblestone |
| **Gracious** | bribed with a diamond | 4–8 cobblestone and the *"Apology Carrot"* (a carrot with `item_name` set) |
| **Satisfied** | met the event's demand (Food Critic fed a carrot) | 8–16 cobblestone |
| **Golden** | golden carrot to Food Critic, or a rare (2%) roll on any tier above Grudge | 1 diamond, named lore: *"The donkey felt bad. Not really."* |

Numbers are config. The economics: an event pays a few cobble — negligible
against the quizengine/bounties faucets — while the bribe destroys a whole
diamond. Net direction depends entirely on how often players pay to skip,
which is the correct incentive structure and becomes the Twitch monetization
seam later.

Named items use `item_name` / `lore` components only — no `custom_data`
needed, nothing here has behavior.

---

## 6. The donkey entity

### Spawning

- `EntityType.DONKEY.create(level, EntitySpawnReason.EVENT)` — spawn-reason
  enum member **unverified**; spiritwolves used `EntitySpawnReason.COMMAND`
  (verified). Use whichever exists.
- Position: a navigable surface block 4–8 blocks from the player. Try up to 10
  random candidates; if none is valid, skip the event silently and re-roll the
  timer. Never spawn inside walls, over the void, or in lava — the donkey is
  immortal but a donkey bobbing in lava is the wrong kind of funny.
- `setPersistenceRequired()` so it cannot despawn naturally mid-event
  (verified in spiritwolves §8).
- Name: `custom_name` visible, drawn from a config name pool ("Duncan",
  "Señor Burro", "The Auditor"...). The name is per-event, not persistent.
- Tagged via `Entity.addTag("chatdonkey")` (command tag — **unverified**, but
  command tags round-trip NBT and survive restarts) so orphans are findable.
- No saddle, no chest, not tamed, `setBaby(false)`. Leashing is cancelled.

### Immortality and non-interference

- `ServerLivingEntityEvents.ALLOW_DEATH` → return `false` for tagged donkeys
  (event verified in spiritwolves §7). Heal to max on the same hook.
- Also cancel knockback if there is a clean hook; if not, healing alone is
  acceptable — a donkey that gets knocked around but won't die still works.
- The donkey must never be a mob-aggro shield: it takes environment and player
  damage as dialogue fuel, but hostile mobs should not target it (donkeys are
  passive; vanilla already gives this for free).
- It never damages anything, blocks no projectiles deliberately, and body
  collision is vanilla-standard — the obstruction is positional, not physical
  force.

### Movement — the biggest technical risk

`Mob.goalSelector` is protected, and per suite rules a Mixin (or access
widener) is the last resort. The plan is **external steering**: a server tick
handler drives the vanilla donkey via its public navigation and look control:

```java
// UNVERIFIED signatures — javap these first (§12)
mob.getNavigation().moveTo(x, y, z, speed);
mob.getLookControl().setLookAt(player);
```

Called at the behavior's cadence (every 10–20 ticks), this overrides whatever
the idle goals were doing without touching the goal selector. Vanilla wander
goals will fight back between calls; for this mod the resulting jitter is
*characterful* rather than broken. If external steering proves insufficient
for Roadblock, the fallback ladder is: (1) short teleports masked with
particles, (2) an access widener for `goalSelector` — never a Mixin.

### Cleanup

- Event end: `discard()` the entity, cloud of `POOF` particles, exit bray.
- `SERVER_STARTED`: sweep loaded entities carrying the `chatdonkey` tag and
  discard them — an event never survives a restart, and this handles a crash
  mid-event. (Only loaded chunks are sweepable; a stragglers-in-unloaded-chunks
  case is handled by the same sweep whenever the chunk next loads, if a chunk
  entity-load event exists — otherwise accept the rare orphan, it is a
  harmless vanilla donkey.)
- Player logs out or dies mid-event: end the event immediately with no gift
  and no cooldown penalty. Death especially — the donkey looting-dancing on a
  corpse is a v2 idea, not a v1 default.

---

## 7. Trigger rules

All in `config/chatdonkey/settings.json`:

```json
{
  "enabled": true,
  "checkIntervalSeconds": 120,
  "chancePerCheck": 0.08,
  "cooldownMinutesPerPlayer": 25,
  "minSessionMinutesBeforeFirst": 10,
  "maxSimultaneousEventsServerWide": 2,
  "requireRecentActivitySeconds": 60,
  "gracePeriodCommand": true,
  "gracePeriodMinutes": 10,
  "donkeyCanKill": true
}
```

- **Activity gate.** A player qualifies only if they moved a meaningful
  distance in the last `requireRecentActivitySeconds`. AFK players get no
  events — an audience is required for comedy.
- **No danger gate — deliberate.** The donkey *will* spawn mid-boss-fight,
  mid-parkour, mid-raid, and it may well get the player killed. That is the
  feature, not a tolerated side effect (§1). The donkey still never attacks —
  it costs the player their attention at the worst possible moment, which in a
  fight is worth more than hit points anyway.
- **`donkeyCanKill`** exists so an operator can dial this back per server
  without a rebuild. Default `true`. When `false`, screen-holding events are
  suppressed while the player has taken damage recently — the donkey still
  interrupts, it just stops doing it at knife-point. This is a server-owner
  knob, not a design hedge: the shipped default is that the donkey can get you
  killed.
- **The one escape hatch.** `/donkey grace` — op-gated by default,
  configurable down to all players — buys `gracePeriodMinutes` of immunity.
  This exists for the genuinely unfair moment (hardcore-adjacent stunts,
  timed challenges). Under Twitch integration this becomes purchasable by the
  streamer and *override-able* by chat paying more, so keep it a first-class
  code path, not a debug flag.
- **Server-wide cap.** At most `maxSimultaneousEventsServerWide` events at
  once, so a full server does not turn into a donkey sanctuary.

Determinism is *not* wanted here, unlike bounties' clock-derived board —
surprise is the point. Plain `Random` per check is correct.

---

## 8. Chat lines

All player-visible text lives in `config/chatdonkey/lines.json`, keyed by
pool: per-event `open` / `during` / `exit_waited` / `exit_bribed` /
`exit_grudge`, plus shared `hit` and `names`. Ship 6–10 lines per pool.

Delivery: chat message prefixed with the donkey's name in gold —
`<Duncan the Donkey> I'm not moving.` — sent to the target player and anyone
within 16 blocks. Not server-wide; the event is *for* someone, and the suite's
other mods are already chatty.

Line-writing rules (Ellie's voice, per wondrous):

- Short. One sentence, two max. It fires every ~7 seconds.
- The donkey is aggrieved, self-important, and convinced it is helping.
- It never swears, never references real people, and never breaks the rule
  that it cannot actually threaten anything.

Sounds follow the suite `Chime.java` pattern — quiet, per-player — **except**
the bray, which is the point of the Serenade event and is played as a normal
world sound from the entity at standard volume. Confirmations quiet,
performances loud.

| Moment | Sound | Volume |
|---|---|---|
| Event start | donkey ambient bray (world sound) | vanilla |
| Chat line fired | `NOTE_BLOCK_HAT` to target player | 0.2 |
| Bribe accepted | `NOTE_BLOCK_BELL` | 0.3 |
| Gift dropped | `NOTE_BLOCK_CHIME` | 0.3 |
| Despawn | `POOF` particles + bray | vanilla |

---

## 9. Commands

Suite convention — admin gated with
`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` (verified pattern,
cobbleeconomy README).

| Command | Who | What |
|---|---|---|
| `/donkey trigger [player] [event] [seconds]` | op | Force an event now, ignoring cooldowns. The test command, and the Twitch bridge's entry point. `[seconds]` overrides the event's rolled duration |
| `/donkey end [player]` | op | End an active event, no gift |
| `/donkey extend <player> <seconds>` | op | Lengthen a running event. "Chat pays to extend" (§11) — the bribe with the sign flipped |
| `/donkey say <player> <line>` | op | Put a line in the donkey's mouth. Chat-written dialogue, moderated by the bridge and sanitised here |
| `/donkey grace [player]` | op (configurable) | Immunity for `gracePeriodMinutes` |
| `/donkey ungrace <player>` | op | Revoke immunity — chat outbidding the streamer (§7's designed bidding war) |
| `/donkey reload` | op | Re-read all three config files |
| `/donkey status` | op | What is running right now |

**On `/donkey say`.** The line ultimately originates in Twitch chat, so it is
untrusted no matter how well the bridge moderates. It is sanitised on arrival:
section signs stripped so nobody can inject colour codes or forge a fake
`<Duncan>` prefix, length capped, blank lines refused. A bridge that forgets to
moderate produces a rude donkey, not a compromised chat window.

No player-facing commands in v1. The player's verbs are physical: wait, bribe,
feed. A `/donkey sorry` command was considered and cut — right-clicking with a
diamond is funnier than typing, and it keeps the interaction on-camera.

---

## 10. Interaction handling

- **Bribe:** `UseEntityCallback.EVENT` (verified, spiritwolves §7 —
  `fabric-events-interaction-v0`). Diamond in hand + tagged donkey → consume
  one diamond, end event with `BRIBED`, return `SUCCESS` to eat the click.
- **Food Critic feed:** same callback, carrot or golden carrot → `SATISFIED`.
- **Everything else** (saddle attempts, leads, taming carrots outside Food
  Critic): cancel with a line — *"You can't ride me. We're not there yet."*
- Vanilla donkey right-click opens taming/mounting interactions, so the
  callback must return a consuming result for *all* clicks on a tagged donkey
  or vanilla behavior leaks through.

---

## 11. Twitch integration — out of scope, but the seams are cut here

Nothing below is built in v1. It is recorded so v1's shapes survive contact
with it.

- `/donkey trigger` is the entire API surface a bridge needs to *start*
  events. A bridge mod runs commands; no compile-time coupling, per DESIGN.md.
- The event runner takes duration and line-pool overrides in `EventContext`,
  so "chat pays to extend" and "chat writes a line" (moderated) are parameter
  changes, not new systems.
- `EndReason.BRIBED` consuming a diamond is the template for every paid
  escape; `!donkey extend` is the same mechanism with the sign flipped.
- The grace period being purchasable-and-overridable (§7) is the designed
  bidding war between streamer and chat.

---

## 12. Verification checklist — do this before writing code

`javap` against the 26.2 merged jar, per the cobbleeconomy README recipe:

1. `Mob.getNavigation()` / `PathNavigation.moveTo(double,double,double,double)` — the whole movement plan rests on this.
2. `Mob.getLookControl().setLookAt(Entity)` — nice-to-have; Roadblock survives without it.
3. `Entity.addTag(String)` / `getTags()` — orphan cleanup keys off this.
4. `EntitySpawnReason` members — `EVENT` vs `COMMAND` vs whatever exists.
5. `AbstractHorse` / donkey-specific interaction methods — what exactly must be suppressed in §10.
6. Knockback hook existence — optional, §6.
7. A chunk-entity-load event for the orphan sweep — optional, §6.

Already verified by other specs in this suite, safe to rely on:
`UseEntityCallback` (v0 module), `ServerLivingEntityEvents.ALLOW_DEATH`,
`setPersistenceRequired()`, `custom_data` read/write via `CustomData`,
`Commands.hasPermission` with `LEVEL_GAMEMASTERS`.

---

## 13. Milestones

**M1 — The visit.** Timer, spawn, one behavior (Lecture), lines from config,
gift, despawn, cooldown. No bribe, no hits, no cap. Proves the loop.

**M2 — The verbs.** Bribe, hit reactions with gift downgrade, Food Critic,
`/donkey` command set, server-wide cap, grace period.

**M3 — The pool.** Remaining four behaviors, weights, the full drop table,
sounds, orphan sweep, restart testing.

**M4 — The screen.** The Burrs event (§4) and the `donkeyCanKill` knob (§7). No
new dependency — it uses the donkey's own chest inventory. Gated on v1 being
play-tested first: M4 is the first milestone that can genuinely cost a player
something, and it should not be built on top of six behaviors nobody has played
yet.

**M5 — The control surface.** Makes §11's seams actually reachable. Every one of
them was designed into v1 and none of them could be *driven* from outside:
duration overrides, `extend`, revoking a grace period, and a moderated
chat-written line all existed as shapes with no command to reach them.

M5 adds no new donkey behaviour. It is the API a bridge needs, and it stays a
command surface precisely so the bridge remains a separate mod with no
compile-time coupling (DESIGN.md §2). **The Twitch bridge itself is not this
milestone and is not this mod** — it needs decisions about platform, auth and
what chat can buy that belong to whoever builds it.

Each milestone is play-tested before the next. The tuning that matters —
event frequency, Roadblock's repositioning cadence, line cooldowns — cannot
be found in code review, only in play with a player who did not want to be
interrupted.

---

## 14. Testing

Suite convention is a pure-Java core with no Minecraft on its classpath. Chat
donkey's testable core is thinner than bounties' or ballot's, but it exists:

- **Trigger arithmetic** — cooldowns, activity gating, chance rolls with a
  seeded RNG, the server-wide cap.
- **Event lifecycle** — state machine from start through the three end
  reasons, gift-tier selection including the hit-count downgrade and the 2%
  golden roll.
- **Config parsing** — `readOrCreate` semantics: defaults written when
  missing, a file that fails to parse is never overwritten (suite rule).

Everything entity-facing (navigation, spawning, the interaction callback) is
verified in play per §13. That boundary — rules in core, Minecraft in fabric
— is the same split every other mod in the suite uses.
