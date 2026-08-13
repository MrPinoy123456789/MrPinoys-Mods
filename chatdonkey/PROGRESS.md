# Chat Donkey — progress

## The voice is the XP blip now

`Voice` plays `EXPERIENCE_ORB_PICKUP` instead of `NOTE_BLOCK_BIT`, superseding
the note-block reasoning further down this file. The chiptune bit was the
closest *instrument*, but the orb pickup is the closest *voice*: shorter, less
musical, and so familiar that a string of them reads as chatter rather than as
someone playing a tune at you. Nothing in `Animalese` changed — the per-donkey
base pitch, the letter wobble and the terminal intonation all ride on top of the
new sample, so Duncan still sounds like Duncan.

Worth knowing, and the exact trap §"`SoundEvents` fields have two different
types" warned about: `EXPERIENCE_ORB_PICKUP` is a **bare `SoundEvent`**, not the
`Holder.Reference` the note-block fields are, so it needs `Holder.direct(...)`
for `ClientboundSoundPacket`. That wrap is hoisted to a constant rather than done
per packet — a talking donkey sends one every tick, per listener.

Volume is unchanged at `0.08`. The orb ding has more attack than the bit blip,
so if it reads as too sharp in play that constant is the dial.

---

## Random events are opt-in

**370 tests. Clean build.**

Random events now only pick players who have run `/donkey optin`. Nothing about
an event changed — the ambush, the interruption, the donkey getting you killed
are all intact. What changed is who is standing in the blast radius: someone who
said yes.

### Where the gate lives

`TriggerRules.decide` returns a new `NOT_OPTED_IN`, checked immediately after
`enabled` and **before** the interval, and it does not consume the check. That
ordering is the whole design:

- Before the interval, so `/donkey status`-style diagnosis gets the useful
  answer (`NOT_OPTED_IN`) instead of the useless one (`NOT_TIME_YET`).
- Non-consuming, so a player who opts in is a candidate on the next poll rather
  than serving out an interval they were never in the running for.

`PlayerTriggerState.setOptedIn` is refreshed from the roster on every poll, so
an opt-out lands on the next check rather than the next login, and the rules
stay a pure function of one struct.

### The roster is the one persistent thing

`OptIns` → `config/chatdonkey/optin.json`, written the moment it changes (not on
a flush timer — opt-ins are rare and consent is not something to lose in a
crash), temp-file-plus-rename like `BountyState`. A corrupt file is quarantined
and the roster starts empty rather than taking the server down: §2's "harmless
failure direction" rule points the other way for consent, but "nobody gets a
donkey" is still the safe end of it.

This puts a dent in §2's **Persistence: None** row, which is now
"the opt-in roster only" with the reasoning written down.

### `requireOptIn` is a boxed `Boolean` on purpose

A missing JSON boolean parses as `false`. For every other flag in `Settings`
that is harmless; for this one it would sign an entire existing server up for
ambushes the moment they upgraded. `null` means "the operator never expressed an
opinion", and `requiresOptIn()` answers that with consent. `sanitised()`
normalises it so the round-tripped file states the answer out loud. Tested.

### Commands

The permission gate moved off the `/donkey` root onto each admin branch (an
`admin(name)` helper), because `optin` and `optout` hang off the same literal
and consent only an operator can give is not consent.

- `/donkey optin` — grants eligibility and nothing else. Interval, session age
  and cooldown all still apply, so the first donkey is still minutes away and
  still a surprise.
- `/donkey optout` — leaves the roster *and* dismisses any donkey currently out,
  as `ABORTED`: no gift (withdrawing consent is not a way to farm one), no
  cooldown (and not a punishment either).
- `/donkey status` reports the roster size, because an empty roster and a broken
  trigger loop look identical from the outside.

Op-forced events (`/donkey trigger`, and therefore the Twitch bridge) ignore the
roster entirely.

### Discoverability

One grey line on join, only to players who are not on the list, and only while
the server actually requires opting in. An opt-in mod nobody knows about is a
mod nobody uses.

---

## Play-feedback pass: merge, music, enchanted gifts, goofy face

**353 tests. Clean build. 10 events, 53 line pools.**

### Lecture and Clingy are one event

They were two behaviors doing the same thing at different distances — follow the
player and talk — and the pool was spending two slots on one idea. Merged:
Lecture now closes to **zero** distance and keeps Clingy's teleport-onto-you at
10 blocks. The clinging is what makes the lecture inescapable, rather than a
second event that also happens to follow you.

`ClingyBehavior` is deleted; its `during` lines are folded into `lecture.during`
and its teleport lines became `lecture.teleport`.

**Knock-on the tests caught:** the catch-up leash test was using Lecture, whose
own 10-block teleport now fires long before the 24-block leash — so it was
testing the wrong mechanism. Switched to Roadblock, which has no teleport of its
own. Also rewrote "catching up says nothing" to assert on *content* rather than a
line count, because an ordinary `during` line can land on the same tick and made
the count lie.

### Serenade brings a record

`ctx.startMusic()` plays a real vanilla music disc from the donkey, and
`ctx.singNote(step)` sings over it every 10 ticks.

The melody is a **seven-note phrase**, not random noise — random would just sound
like a mistake, whereas a tune delivered confidently slightly wrong sounds like
singing. Each note is detuned by an alternating sharp/flat wobble, the phrase
length does not divide into the backing track's bar, and the note interval (10)
deliberately does not match the bray interval (60), so nothing ever settles into
a rhythm together. There is a test asserting those two intervals differ.

The disc is fire-and-forget: it outlasts the event, and cutting it off would need
a stop-sound packet with the disc id tracked. Letting it play on for whoever is
still standing there is funnier anyway.

### Cobblestone is gone; gifts are enchanted junk

Cobblestone was an anticlimax that landed and then got binned — a punchline
nobody keeps is one nobody sees twice.

Gifts are now a cheap silly item (`GiftItems.THEMATIC` — fishing rod, bowl,
bone, lead, brush, clock…) carrying **nonsense enchantments**. The enchantment is
written straight into the component rather than applied via
`EnchantmentHelper.enchantItem`, which deliberately bypasses applicability — that
is the whole joke. A correctly-enchanted fishing rod is a loot table; a Bowl of
Bane of Arthropods is a character.

Tier controls **absurdity, not power**: 1 enchantment at standard, 2 at
gracious/satisfied, 3 at golden. It stays a punchline while the good endings are
still visibly better to receive.

They are real enchantments, so a grindstone — or the `wondrous` disenchanter —
lifts them onto a book. **This is a compliment to that mod, not a dependency on
it** (DESIGN.md §2): nothing here knows `wondrous` exists.

### Premium rewards now stack

`GOLDEN` used to *replace* the tier below. Now every tier above grudge gets the
enchanted item, and gracious adds the Apology Carrot, and golden adds the carrot
**and** the diamond. Earning the best ending should never mean losing what the
ending below would have given you.

### The goofy face — partial

`donkey.setEating(true)`, re-asserted every tick because vanilla clears it when
its own eating timer lapses.

**This is not quite what was asked for.** A genuinely hanging-open jaw is
`FLAG_OPEN_MOUTH`, which is a **private** constant behind a **protected**
`setFlag` — unreachable without an access widener, which SPEC.md §6 permits only
as a last resort and which is a lot of machinery for a cosmetic. `setEating` is
the only public mouth animation on a horse and gets most of the way there.
If the chewing face is not goofy enough in play, the access widener is about
four lines — say so and I will add it.

---

## Demand events are now a template, not a class

**354 tests. Clean build. 11 events, 56 line pools.**

Play feedback: the Food Critic is the best of the seven, especially the diamond
for a golden carrot. So the shape got promoted from "one hardcoded behavior" to
**the way you add events**.

### What changed

`FoodCriticBehavior` and the `Treat` enum are **gone**. In their place:

- `DemandBehavior` — one generic class: follow the player, ask for a thing, take
  it and leave happy.
- `Demand` — what it wants, as *item ids* so `core` still names no Minecraft
  item (the trick `bounties` uses for mob ids).
- `Offering` — `ORDINARY` / `PREMIUM` / `DUPLICATED`, replacing carrot-specific
  values.

**A demand event is now an `events.json` entry.** Anything with a `wants` field
is one, no code involved:

```json
"foodcritic": { "weight": 20, "minSeconds": 30, "maxSeconds": 45,
                "wants": "minecraft:carrot",
                "wantsPremium": "minecraft:golden_carrot" }
```

Adding an eighth is that plus lines. `EventPool.of` deliberately does *not*
reject an unknown behavior id when the entry has a `wants` — that is what makes
demands data rather than a fixed list.

### Four new demand events

| Event | Wants | Premium (pays a diamond) |
|---|---|---|
| **Sweet Tooth** | apple | golden apple |
| **Bookworm** | book | enchanted book |
| **Magpie** | iron ingot | gold ingot |
| **Alchemist** | *(see below)* | — |

### The Magician — the duplicator

Donkey-from-Shrek, relentlessly trying to show you his one magic trick. Hand him
a raw resource and he hands back **double**. Crafted goods are refused, loudly.

The allowlist is `EventPool.DUPLICATABLE`, and the exclusion is **structural, not
economic**. Nine ingots make a block; doubling the block and unpacking it returns
eighteen, so allowing any crafted item turns **every crafting recipe into a
multiplier** and the trick into a machine. There is a test asserting nothing on
the list ends in `_block`, `_helmet`, `_sword`, `_pickaxe` or `_chestplate`.

**Diamonds, emeralds, ancient debris and netherite scrap are all on the list** —
they are mined, not crafted, so they double like any other rock. Per DESIGN.md §5
(revised after play) sinks out-run faucets here, so a generous payout is the safer
error. There are tests asserting they are present, so nobody quietly removes them
out of habit.

Two guards remain, and both are about rate rather than size:

- **Intake is capped at 16 per click** (`Interactions.MAX_INTAKE`). The payout is
  a multiple of what it was given, so an uncapped intake would make one
  right-click worth a shulker box.
- **Hitting him three times drops the multiplier to 1** — he hands back exactly
  what he was given. That is the duplicator's version of the grudge tier: no
  loss, no gain, and a pointed silence.

### A bug caught mid-refactor

`Events.start` still resolved the chosen event through `Behaviors.byId`, which
after the refactor only knows built-ins — so **every demand event would have
been picked by the weighted pool and then silently failed to start**, including
the Food Critic that already worked. Now resolved via `Behaviors.forDefinition`,
and there is a test asserting every pool entry resolves to a runnable behavior.

Also: `events.json` was writing `"multiplier": 0` onto ordinary events. Harmless
but exactly the sort of thing that makes an operator wonder what they broke.
Fields that do not apply are now omitted.

### Economy stance — settled, do not re-litigate

Earlier notes in this file hedged about diamond inflation. **That worry was
wrong and DESIGN.md §5 has been rewritten to say so.** Play showed sinks
comfortably out-running faucets — diamonds were being handed out manually during
testing just to keep other things testable.

The standing rule is now: judge a payout by *how often a real player can reach
it*, not by how big it is, and err on the side of generous. What is still worth
refusing is anything that scales with a **machine** rather than with playing —
an AFK farm, or a duplication rule a crafting recipe can be laundered through.

That is why the Magician takes raw materials only and why intake is capped, and
it is the *whole* reason. Nothing in this mod is tuned down out of fear that a
diamond is worth too much.

---

## Session 5 — M5 (new): the control surface

**Status: built and building. 314 core tests. Not play-tested.**

M4 was the last milestone SPEC.md defined, so I specced M5 into §13 and §9 rather
than inventing scope silently.

**The problem it solves.** §11 names four seams a Twitch bridge needs, all
designed into v1 — and **not one of them could actually be driven from outside.**
Duration overrides, `extend`, revoking a grace period, and a moderated
chat-written line all existed as shapes with no command to reach them. M5 adds no
new donkey behaviour; it is purely the API.

| §11 seam | Now reachable as |
|---|---|
| "takes duration overrides" | `/donkey trigger <player> <event> [seconds]` |
| "chat pays to extend" | `/donkey extend <player> <seconds>` |
| "chat writes a line (moderated)" | `/donkey say <player> <line>` |
| "grace purchasable *and overridable*" | `/donkey ungrace <player>` |

**It stays a command surface on purpose.** That is what keeps the bridge a
separate mod with no compile-time coupling (DESIGN.md §2). **The Twitch bridge
itself is not this milestone and is not this mod** — it needs decisions about
platform, auth, and what chat can buy that belong to whoever builds it.

### Chat-written lines are treated as hostile input

`/donkey say` ultimately carries a string from Twitch chat into every nearby
player's chat window, under a name they trust. However well a bridge moderates,
by the time it arrives here it is untrusted. `core/ChatLine` sanitises it:

- **Formatting escapes stripped whole**, `§` and the code letter together. This
  is the one that matters: left in, a viewer could colour their text, hide it, or
  forge a convincing second `<Duncan>` prefix.
- **Control characters become spaces**, so one line cannot become several.
- **Whitespace runs collapse**, so padding cannot scroll chat.
- **Length capped at 120**, because a wall of text is a denial-of-chat and the
  animalese would blip for its entire length.

Anything that does not survive yields `""`, which callers treat as "say nothing"
— the failure direction is a duller donkey, never a dropped guard. 16 tests,
including one asserting the output is *always* printable whatever goes in.

My first cut stripped only the `§` and left the code letter as visible junk,
which also let a formatting-only string survive as garbage instead of being
refused. The tests caught it.

### The extend ceiling

`extend` is clamped to `Settings.MAX_EVENT_SECONDS` (600) in total. An unbounded
extend is a griefing tool with a price tag — enough redemptions and one player
never gets their screen back, which matters much more now that Burrs exists.

The command **returns the seconds actually granted**, not just success, so a
bridge can refund the difference when a redemption hits the ceiling.

### Not verified

Unplayed, like everything since M1. Specifically: whether `/donkey say` reads as
the donkey talking or as an obvious injection, and whether 600s is the right
ceiling given a Burrs event can hold a screen for all of it.

---

## Catch-up leash — the donkey can no longer be outrun

295 tests, clean build.

Raising `donkeySpeed` helped, but a player on a horse, under a speed potion, or
on an elytra still leaves the donkey standing — and an event where the donkey is a
dot on the horizon is not an event, it is a chat log.

**Every behavior now catches up.** Past **24 blocks** (`AbstractBehavior.LEASH_DISTANCE`)
the donkey stops walking and simply turns up beside you again.

- Lives in `AbstractBehavior.tick`, before `onTick`, so all seven inherit it — no
  per-behavior wiring, and the test asserts it for every registered id.
- **Lands *near* you, not on top.** That is Clingy's move, and 24 is well beyond
  Clingy's 10 so its gag is never pre-empted. There is a test pinning that
  relationship.
- **Silent.** No line, just particles at both ends. A donkey that is simply
  *there* again when you thought you had lost it is funnier than one that
  announces itself — and announcing it would tread on Clingy's joke.
- Rate-limited to one every 2s, so a laggy chase cannot make it thrash.
- Reuses `DonkeySpawn`'s footing search via a new `relocateNear`, so it never
  arrives inside a wall, in lava, or over a drop. If there is nowhere valid it
  stays put and retries — a failed relocate is explicitly not counted as a
  catch-up, and there is a test for that path.

Also folded the duplicated teleport-particle code in `ActiveEvent` into one
`puff()` used by both teleports.

---

## Tuning pass — faster donkey, deeper voices

Both from play feedback. 278 tests, clean build.

### Movement

The donkey was too slow, and the cause was not the navigation multipliers — a
vanilla donkey's `MOVEMENT_SPEED` attribute is genuinely slower than a sprinting
player, and the per-behavior multiplier *scales* that number, so a slow base
capped everything no matter how high the multipliers went.

Two changes:

- **`DonkeySpawn` now sets `MOVEMENT_SPEED` at spawn**, from the new
  **`donkeySpeed`** setting (default `0.3`, roughly horse-tier). This is the
  master dial and it is config, so it can be tuned without a rebuild.
- **Every per-behavior multiplier raised ~25%**, keeping the relative ordering
  intact so each event keeps its character:

| Behavior | was | now |
|---|---|---|
| Lecture | 1.0 / 1.35 | 1.25 / 1.7 |
| Food Critic | 1.05 / 1.4 | 1.3 / 1.75 |
| Roadblock | 1.3 | 1.6 |
| Serenade | 1.4 | 1.7 |
| False Alarm | 1.7 | 2.0 |
| Clingy | 1.25 | 1.55 |
| Burrs | 1.15 | 1.4 |

Above roughly `donkeySpeed: 0.4` the pathfinder starts overshooting corners,
which reads as broken rather than fast. Noted in the setting's javadoc.

### Voices

The animalese band moved from **0.90–1.50 down to 0.60–1.05** — donkeys, not
chipmunks. Measured effect on the stock names: Duncan 1.304 → 0.903, Señor Burro
→ 0.624, Clopsworth → 1.039. Still a wide spread, so two donkeys in earshot
remain distinguishable.

The floor was chosen, not guessed. Wobble and a falling sentence each pull a blip
*below* its base, so the deepest reachable pitch is about
`MIN_BASE_PITCH * 0.94 * 0.92`. Measured across every stock name and several
lines, actual blips span **0.562–1.306** against Minecraft's 0.5–2.0 limits — no
clipping. There is now a test asserting that headroom directly, so dropping the
band further will fail loudly instead of silently flattening the deep voices.

### A test that was quietly wrong

`FakeContext` detected "is it sprinting?" with a hardcoded `speed > 1.0`, which
broke the moment walking got faster than 1.0. It now records the actual speed and
the tests **compare** walk against catch-up, so future retuning cannot silently
break the assertion — or worse, keep passing while meaning nothing.

---

## Session 4 — M4 (SPEC.md §13, "The screen")

**Status: built and building. 276 core tests pass. Not play-tested.**

Server boots with **36 line pools across 7 events**. Zero warnings. **No new
dependency** — the coat is the donkey's own chest inventory, as you called it.

### How it works

`DonkeyCoat` implements the `core` `Coat` seam over the real container:

| Step | Call |
|---|---|
| Give him the 15 slots | `setChest(true)` |
| Make the screen openable | `setTamed(true)` — **required**, it checks `isTamed()` |
| Force it open (and reopen) | `openCustomInventoryScreen(player)` |
| Read / write a slot | `getSlot(500 + i)` → `SlotAccess` |

The `inventory` field is `protected`; `getSlot(500 + i)` is the public route to
it, which is what keeps M4 free of a Mixin or access widener. Burrs are named
dead bushes (`item_name` + lore, no `custom_data` per DESIGN.md §3), counted back
out by that name.

Three loops run at once and their interaction is the event: the player pulls
burrs out, the donkey finds more every 8s while the coat is open, and if the
player closes it he reopens it after 5s with a nag line.

### The item-loss guard is wired and unconditional

`Events.end` calls `coat.returnEverything()` **before** the donkey is discarded,
and outside the `playerStillHere` branch. A real container means a player can put
their own things in it, and discarding the donkey would destroy them.

If the player has logged out or died, the contents **drop on the ground** at the
donkey rather than being handed to a removed player. Losing track of a stack is
not an option in either direction.

It also doubles as the joke: burrs he never got round to losing go home with you.

### A bug the tests caught

The reopen and refind timers were never primed at start, so their first
allowance was still unspent — the donkey re-opened the coat and found a new burr
on the **very next tick** after the event began, instead of after the beat. Reads
as a stuck loop rather than a rhythm. Both are now primed in `onStart`, and the
tests assert the timing precisely (nothing happens before the beat, then exactly
one thing on it) rather than only that it eventually happens.

### `donkeyCanKill`

New in `settings.json`, default `true` — the shipped behaviour is that the donkey
can get you killed. Setting it `false` suppresses *screen-holding* only, and only
while the player has taken damage in the last 8 seconds: the donkey still
interrupts, he just stops doing it at knife-point. Damage recency is tracked
unconditionally via `AFTER_DAMAGE` so `/donkey reload` takes effect immediately
rather than after the next fight.

### Not verified

Unplayed, like the rest. In risk order:

1. **`isOpen()` uses `player.containerMenu != player.inventoryMenu`** — that
   assumes any open menu during the event is the coat. True in practice, but a
   player who opens a chest mid-event would read as "coat open", suppressing
   reopens until they close it. Harmless, slightly wrong, easy to tighten if it
   shows up in play.
2. Whether taming the donkey has any visible side effect worth caring about
   (it should not — he is discarded at event end).
3. Whether 5s reopen / 8s refind is funny or infuriating. Both are constants at
   the top of `BurrsBehavior`.
4. That the burr count reads well at 15 slots — `MIN_BURRS`/`MAX_BURRS` are 5–9.

---

## Design change — SPEC.md v1.1: the donkey is allowed to be costly

**The "annoying, never harmful" rule is withdrawn.** It was never wanted. The
donkey getting a player killed is now an accepted and desirable outcome.

SPEC.md has been updated so this does not get re-derived from an older draft:

- **§1** — the load-bearing rule is rewritten, with a revision note explaining
  what changed and why, since the old rule was load-bearing for two other
  sections.
- **§2** — new locked row: *may get the player killed: yes*.
- **§7** — the no-danger-gate reasoning no longer rests on harmlessness. Same
  conclusion, honest reasoning. New `donkeyCanKill` setting (default `true`)
  as a per-server dial-back, not a design hedge.
- **§4** — the **Burrs** screen event added as v2.
- **§13** — new **M4 — The screen**.

**The distinction that survives, and it matters for M4:** the donkey still never
*attacks*. No damage, no aggro, no projectiles. What it costs a player it costs
by stealing their attention at a bad moment. That is why the Burrs screen is
allowed to hold their view but the donkey will never be given a kick attack — the
comedy is that it is oblivious to the trouble it causes, not that it is
malicious. A donkey that knows it is endangering you is a villain; one that is
merely desperate to discuss your choices while a creeper approaches is funny.

**No code changed.** Nothing in the implementation ever enforced the old rule —
it was shaping future design, not current behaviour. Three doc/javadoc comments
that restated it were corrected (`README`, `ChatDonkeyMod`, `DefaultLines`).

### The Burrs event, as specced (M4, not built)

**No SGUI. No new dependency at all** — it uses the donkey's own chest
inventory. An earlier draft of §4 specced a virtual SGUI screen; the real
container is better on every axis and the spec now says so. All four calls
verified against the merged jar:

| Need | API |
|---|---|
| Give him an inventory | `setChest(true)` — 15 slots |
| Force the screen open (and reopen) | `openCustomInventoryScreen(Player)` — **public** |
| Read / write a burr slot | `getSlot(500 + i)` → `SlotAccess.get()/set()` |
| Screen will open at all | `setTamed(true)` — **required**, it checks `isTamed()` |

The `inventory` field is `protected`, but `getSlot(500 + i)` is the public route
to it, so this needs no Mixin and no access widener.

- 5–9 burrs in random slots; the player drags them out.
- **Closable, and he reopens it** after `burrReopenSeconds` (default 5). Your
  call and the right one — the annoyance is the nagging loop, not being trapped.
  No reopen cap; the event duration ends it.
- **He keeps finding more** every ~8s while the screen is open. This replaces the
  mis-click gag from the SGUI draft, which does not survive the port (in a real
  container the player can rearrange freely, so there is no "wrong click" to
  punish). The time-driven version is better anyway, and it is the natural
  difficulty knob for the Twitch bridge.
- Clear it → `SATISFIED`. Time out → `WAITED`. Both already exist.

Three things written into §4 because they will otherwise be got wrong:

1. **A real container means the player can put items in**, and `discard()` at
   event end would destroy them. **Sweep all 15 slots and `giveOrDrop` back on
   end.** Framed as part of the joke — the burrs he never lost get shoved into
   your inventory on the way out.
2. **Taming is required and specific to this event.** `setTamed(true)`
   contradicts §6 for every other behavior. Player-initiated taming and mounting
   stay blocked by the existing `UseEntityCallback`.
3. **Bribing requires closing the screen first.** Good beat, not a bug — preserve
   it deliberately.

Puzzle state is arithmetic and belongs in `core`; only the container calls are
fabric-side.

---

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

**`ItemStack.is(Item)` still exists in 26.2** — verified by compiling `stack.is(item)`
against `minecraft-merged-deobf-26.2.jar`. `stack.getItem() == Items.DIAMOND` also
works. Everything else M2 needed
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
