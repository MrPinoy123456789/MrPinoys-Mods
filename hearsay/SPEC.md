# MrPinoy's Hearsay — Build Spec

> **Status:** design draft, 2026-08-12. Not implemented.
>
> **What it is.** Villages that talk. Ambient chatter, overheard two-handers, and
> rumours, attached to the villagers vanilla already generated. It spawns
> nothing, changes no behaviour, and pays nothing.
>
> **Verification:** signatures marked ✅ were checked against
> `minecraft-merged.jar` in the Gradle cache on 2026-08-12.

---

## 1. The problem this solves

Every player visits villages. Villages are silent — a villager hums, opens a
door, and that is the entire performance. The most-visited location in the game
is also the least alive.

Hearsay gives them something to say to you, and more importantly something to say
to **each other**. That second half is the point: Skyrim's cities feel inhabited
because you walk past two people mid-argument, not because anyone greets you.

> **The load-bearing rule: hearsay never interrupts, never blocks, never
> rewards, and never changes what a villager does. It is atmosphere. The moment
> it pays out, players farm it, and a thing you farm is not atmosphere.**

That rule is why this mod is cheap and safe: no economy impact, no exploit
surface, no balance risk, no interaction with any other mod. It can afford to be
generous with content precisely because it is generous with nothing else.

---

## 2. Locked design decisions

| Decision | Value | Rationale |
|---|---|---|
| Speakers | **Vanilla villagers that already exist** | Spawns nothing. No placement, no expiry, no orphan sweep |
| Rewards | **None. Ever.** | §1. Non-negotiable |
| Villager behaviour | **Untouched** — no AI, trades, professions or pathing changed | A mod that breaks villager trading gets uninstalled the same day |
| Delivery | **Bubbles for `say`, chat for `narrate`, per-player, ~16 blocks** | §3 |
| Budget | **Rate-limited per *listener*, not per speaker** | §3 — the decision the mod lives or dies on |
| Two-handers | **Yes — the reason the mod exists** | §5 |
| Persistence | **None** | Nothing is worth remembering |
| Cross-mod | **None** | Names no other mod. Reads no other mod's config |
| Mixins | **Zero** | Nothing here needs one |
| Client requirement | **None.** `"environment": "server"` | Suite rule |

---

## 3. The chat budget — the load-bearing section

Elder Scrolls dialogue is voiced and positional: you hear it as you pass and it
costs you nothing. Minecraft has no voice, so every line lands as **text in the
same window as real player conversation**, in a village containing twenty
villagers. Get this wrong and the mod is worse than silence.

**The rules, ordered by how much damage getting them wrong does:**

1. **Rate-limit per listening player, not per speaker.** One line reaches a given
   player every `quietSeconds` (default **45**) no matter how many villagers are
   in range. Limiting each villager independently is the failure that makes a
   village unreadable, and it is the intuitive implementation, so it is the one
   that will be written by accident.
2. **A running two-hander holds the floor** for its whole duration. Scenes never
   interleave with each other or with ambient lines — the timing *is* the joke.
3. **Only players within `hearingRange` (default 16) hear it**, as per-player
   packets at each listener's own position, so everyone hears it equally rather
   than it fading for whoever stood furthest away. Copy `chatdonkey`'s `Voice`.
4. **Silence during trouble.** Nothing is said to a player who has taken damage
   in the last 8 seconds, or while a raid is active in that village. A punchline
   during a creeper attack is noise. `chatdonkey` already tracks damage recency
   via `ServerLivingEntityEvents.AFTER_DAMAGE` for `donkeyCanKill`.
5. **Per-villager cooldown** on top (default 120s) so one villager standing next
   to you does not become the voice of the village.
6. **A server-wide cap** on simultaneous scenes, matching `chatdonkey`'s
   `maxSimultaneousEventsServerWide` shape.

**Delivery channel is a per-pool choice**, because the two cases genuinely
differ. `wayfarers` M2 proved that `Display$TextDisplay` bubbles work for in-world
speech, so `hearsay` adopts the same split:

| Kind | Channel | Why |
|---|---|---|
| Single ambient line | **Actionbar** (default) | Transient, does not pollute scrollback |
| Greeting on approach | Actionbar | Same |
| `say` / any villager speech | **Bubble** above the speaking villager | World-readable, positional, respects `wayfarers`'s `Bubbles` budget rules |
| `narrate` / rumour / two-hander | **Chat** | Multi-line or omniscient; kept in scrollback |

`channel` is a field on the pool with a sensible default, and it is the **first
thing to tune in play** — this is a feel question, not a code question.

The bubble rules are copied from `wayfarers/SPEC.md` §5.3: one bubble per speaker,
a new line replaces rather than stacks, `view_range` tuned to ~16 blocks, and
`narrate` always falls back to chat because narration has no head to float above.

---

## 4. When a villager speaks

All triggers are observable server-side with no coupling to anything.

| Trigger | Pool | Notes |
|---|---|---|
| Player enters `hearingRange` of a villager not seen recently | `greeting` | Per-villager-per-player cooldown so passing twice is not two greetings |
| Player lingers nearby | `ambient` | The bulk of the content |
| Player completes a trade with that villager | `traded` | The one reactive line tied to a player action |
| Time of day crosses dawn / dusk | `morning` / `night` | |
| Rain or thunder starts | `weather` | |
| Something happened near the village | `reaction` | See below |

**Reaction lines are what make it feel alive**, and they are all cheap because
the events are already broadcast:

| Event | Hook |
|---|---|
| A creeper exploded nearby | Explosion event, or entity death of a creeper |
| An iron golem died | `ServerLivingEntityEvents.AFTER_DEATH` |
| A raid started or ended | Raid state, ✅ verify accessor |
| A villager died | `AFTER_DEATH` filtered to `Villager` |
| The player slept | `ServerPlayerEvents` sleep hook, ✅ verify |
| Night fell with the player still outside | Day-time check |

A reaction line fires once per village per event, at most, and still obeys §3.

---

## 5. Two-hander scenes

Two villagers, both within a few blocks of each other and both currently idle,
play an ordered script while the player is in earshot.

**Selection:** find a candidate pair near a listening player, check both are off
cooldown, check the server-wide scene cap, pick a scene whose `speakers` match
the pair's professions where the pool specifies them, and claim the floor.

**Scenes are profession-tagged where it matters.** The shepherd/farmer exchange
only lands with a shepherd and a farmer; a generic scene can run between any two
villagers. Both forms live in the same pool file:

```json
{
  "scenes": [
    { "speakers": ["minecraft:shepherd", "minecraft:farmer"],
      "channel": "chat",
      "script": [
        { "speaker": 0, "say": "I think Gerald is sick." },
        { "speaker": 1, "say": "Who's Gerald?" },
        { "speaker": 0, "say": "The blue sheep." },
        { "speaker": 1, "say": "You have seventeen blue sheep." },
        { "wait": 30 },
        { "speaker": 0, "say": "...Exactly." }
      ]},

    { "speakers": ["*", "*"],
      "script": [
        { "speaker": 0, "say": "Did you hear the noise last night?" },
        { "speaker": 1, "say": "Which noise?" },
        { "speaker": 0, "say": "The explosion." },
        { "speaker": 1, "say": "There were three explosions." },
        { "wait": 20 },
        { "speaker": 0, "say": "Exactly." }
      ]}
  ]
}
```

**If a speaker walks off, dies, or unloads mid-scene**, the scene aborts quietly.
No error, no half-finished exchange re-attempted, no line delivered by a corpse.

---

## 6. The line schema

**Deliberately identical to `wayfarers/SPEC.md` §5.3.** A pool entry is a plain
string or a `script` object; steps are `say` / `narrate` / `wait` / `speaker` /
`action`. Lines written for one mod can be moved to the other by copy-paste,
which matters because a lot of this content works in both places.

The two mods **duplicate the implementation rather than sharing a library**, per
`DESIGN.md` §2. A second copy of a script sequencer is cheaper than a shared
dependency both mods must version against.

`action` accepts the same closed verb set — `look_at_player`, `look_away`,
`walk_away`, `vanish`, `swing`, `shake_head` — with `vanish` and `walk_away`
meaningless here and ignored. **Adding a verb is a code change, deliberately.**

### 6.1 Pools

Keyed `<profession>.<moment>` with a bare `<moment>` fallback, the same shape and
same fallback rule as `chatdonkey`'s `LinePools`. A missing
`minecraft:librarian.ambient` falls back to a bare `ambient`, so nothing is ever
mute.

Professions are resolved from the villager's own data rather than a hardcoded
switch — verified ✅:

```java
VillagerData data = villager.getVillagerData();         // ✅ public
Holder<VillagerProfession> profession = data.profession();
// pool key = the profession's registry id, e.g. "minecraft:farmer"
```

`VillagerData` is a record with `type()`, `profession()` and `level()` ✅. An
unemployed villager falls straight through to the bare pool, which is correct —
a nitwit has nothing professional to say.

**`withDefaults` semantics, copied from `chatdonkey`:** a pool missing from the
operator's file is merged from the stock set **in memory only**, and their file
is never rewritten. Without this, a server that generated `lines.json` on an
early version comes up silent for every pool added later.

> **The trap that follows from it:** `withDefaults` merges missing *pools*, not
> missing *lines within an existing pool*. New content added to a pool an
> operator already has will never reach them. Ship new content as new pools, or
> document that they must delete the file.

### 6.2 Conventions worth protecting

- **Recurring names.** A name appearing across several pools as a running joke is
  deliberate, not an inconsistency. Record them in a comment block at the top of
  `lines.json` so nobody "fixes" them.
- **The player-as-natural-disaster register** — villagers discussing the player
  as an unpredictable weather event — gets its own pool rather than being diluted
  into general chatter. It is the strongest voice available.
- **`"..."` is a legitimate line.** Silence with a beat around it is content.
- **No animalese.** That is `chatdonkey`'s voice and it stays there. Villagers
  use `VILLAGER_YES` / `VILLAGER_NO` / `VILLAGER_TRADE` ✅ sparingly, or nothing.

---

## 7. What this mod must never do

Written as prohibitions because each one is a plausible "improvement" that would
ruin it:

- **Never give an item, currency, or effect.** §1.
- **Never modify a villager's AI, trades, profession, level, or inventory.**
- **Never prevent or delay a trade.** A villager mid-conversation still trades
  instantly.
- **Never spawn, name, or despawn a villager.**
- **Never speak for a villager that is dead, unloaded, or asleep.**
- **Never read another mod's config**, including a facts file. Rumours here are
  operator-authored text. (If the `ROADMAP.md` §3 facts contract ships, this
  becomes a natural third consumer — but that is a later decision and it changes
  the §2 story, so it is out of scope here.)

---

## 8. Edge cases — decided, not discovered

| Case | Behaviour |
|---|---|
| Twenty villagers around one player | One line per `quietSeconds`. §3.1 |
| Two players in one village | Budgets are per listener, so both hear a normal amount; the same scene may be heard by both |
| Player walks away mid-scene | Scene continues for anyone still in range; aborts if nobody is |
| A speaker dies mid-scene | Scene aborts quietly |
| A speaker is a nitwit / unemployed | Bare pool fallback |
| Zombie villager | Silent. It is not a villager for this purpose |
| Raid in progress | Everything suppressed until it ends, then `reaction` may fire once |
| Player is in a boat/minecart passing through | Normal — movement is not a gate here, unlike `chatdonkey` |
| Villager inside a player's base with no village | Works. There is no "village" concept required, only a villager |
| `lines.json` fails to parse | Defaults in memory, file untouched, one log line. `readOrCreate` |
| Server has 200 villagers loaded | Candidate scan is proximity-first from *players*, never a scan over all villagers |

---

## 9. Module layout

Core/fabric split. The rate limiter, the script sequencer, the pool fallback and
the scene-pair selection are all pure arithmetic, all easy to get subtly wrong,
and all testable without a game.

```
hearsay/
  core/src/main/java/hearsay/core/
      LinePools.java     — pool lookup, fallback, withDefaults
      Script.java        — ordered steps, speaker attribution, beats
      Scene.java         — a two-hander definition and its speaker matching
      RateLimit.java     — per-listener budget, per-speaker cooldown, floor claim
      Triggers.java      — which moment fires, and whether it is allowed to
      ReadOrCreate.java  — copied from chatdonkey
      DefaultLines.java  — the shipped content, pure data
  core/src/test/java/hearsay/core/HearsayTest.java
  fabric/src/main/java/hearsay/
      HearsayMod.java    — entrypoint
      Listeners.java     — proximity scan from players, candidate villagers
      Scenes.java        — pair finding, floor claiming, playback
      Speech.java        — chat/actionbar rendering, per-player packets
      Reactions.java     — the §4 event hooks
      HearsayCommands.java
  fabric/src/main/resources/fabric.mod.json
```

Config at `config/hearsay/`: `lines.json`, `settings.json`. Both via
`readOrCreate` — write defaults if missing, **never overwrite a file that failed
to parse**.

`settings.json`: `quietSeconds` (45), `hearingRange` (16), `speakerCooldownSeconds`
(120), `maxSimultaneousScenes`, `sceneChance`, `ambientChance`,
`suppressAfterDamageSeconds` (8), and a `channel` default per pool kind.

Commands, gated `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`:
`/hearsay reload`, `/hearsay status`, `/hearsay say <player> <pool>` (force a line,
for testing and for line-writing iteration), `/hearsay scene <player>` (force a
two-hander), `/hearsay mute <player>` (per-player opt-out — see §12).

---

## 10. Verify before writing code

1. **Actionbar delivery** — the packet or `sendSystemMessage(component, true)`
   form in 26.2, and what happens when two arrive close together.
2. **Raid state accessor** — how to ask whether a raid is active near a position.
3. **Sleep event** — the Fabric hook for a player entering a bed.
4. **Villager idle test** — is there a cheap way to know a villager is not
   mid-task, or is "not moving and not trading" good enough?
5. **`VillagerProfession` registry id** from the `Holder` — `unwrapKey()` versus a
   registry reverse lookup, and which is cheap enough for a proximity poll.
6. **Explosion hook** for creeper reactions — a Fabric event, or filtering
   `AFTER_DEATH` on creepers, whichever is cheaper.
7. **Villager sounds** as `SoundEvent` versus `Holder.Reference` — the split
   `chatdonkey` documented. `VILLAGER_YES` / `_NO` / `_TRADE` are bare
   `SoundEvent` ✅ and need `Level.playSound`.

---

## 11. Build configuration

Copy `chatdonkey`'s (it already has the core/fabric split) and change names.

- Minecraft **26.2**, Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"`, `archivesName = "MrPinoys_hearsay"`
- **No Mixins. No sgui.** This mod has no screens
- Loom `1.17-SNAPSHOT` applied in `settings.gradle.kts`, not the `plugins {}` block
- `dist` Copy task into the shared `dist/`, `build` `finalizedBy("dist")`
- `Identifier`, not `ResourceLocation`. Mojang names, no Yarn

> **⚠ The bug every mod in this suite has hit — nine times now.** The `dist` task
> must depend on **`jar`**, *not* `remapJar`. 26.2 ships unobfuscated so Loom
> registers no `remapJar` task and depending on it fails the build outright.

---

## 12. Definition of done — v1

- [ ] `./gradlew build` clean; `MrPinoys_hearsay-0.1.0.jar` in `dist/`
- [ ] Walking through a village produces occasional lines, **not a wall of text**
- [ ] With twenty villagers in range, the player hears **one line per
      `quietSeconds`** — the §3.1 test, and the one most likely to be wrong
- [ ] Two-hander scenes play in order, in chat, with beats intact
- [ ] Two scenes never interleave; a scene never interleaves with an ambient line
- [ ] A speaker dying mid-scene aborts it quietly
- [ ] Profession pools resolve; an unemployed villager falls back to the bare pool
- [ ] Nothing is said within 8 seconds of the player taking damage, or during a raid
- [ ] Reaction lines fire for a creeper explosion and a dead iron golem
- [ ] **Villager trading is completely unaffected** — trade with a villager
      mid-scene and confirm the screen opens instantly
- [ ] **No item, currency, or effect is ever granted** — grep the source to prove it
- [ ] `/hearsay say` and `/hearsay scene` force output for line-writing iteration
- [ ] `/hearsay mute` silences the mod for one player, and is remembered for
      their session
- [ ] A `lines.json` that fails to parse leaves the file alone and logs once
- [ ] A fully vanilla client sees all of it with nothing installed

**`/hearsay mute` is not optional.** Some players will find ambient chatter
irritating, and a mod that cannot be turned off by the person it is talking to
gets the whole thing uninstalled instead.

---

## 13. Out of scope for v1

Quests, rewards of any kind, reputation, villager memory of the player, dialogue
trees or player replies, voice or animalese, spawning villagers, naming
villagers, illager or mob chatter, per-village personalities, and **any
interaction with the other eleven mods** including the facts contract.

Ambient lines, greetings, reactions, and two-handers. Make a village feel
inhabited for the length of one walk through it, then stop.

---

## 14. Why this might be worth building before `wayfarers`

Recorded as an argument, not a decision.

**Cost.** No spawning, no placement search, no expiry, no orphan sweep, no
persistence, no trading, no payment, no GUI, no combat. It is the dialogue engine
and a proximity check. That is a fraction of `wayfarers` v1.

**Reach.** Every player visits villages. A wayfarer must be stumbled upon.

**Sequencing.** `wayfarers` needs the same script sequencer, speaker attribution,
narration rendering and per-listener rate limit (`wayfarers/SPEC.md` §5.3).
Building it here first means `wayfarers` M3 copies **working, play-tested** code
instead of inventing it — and the chat-budget numbers in §3, which can only be
found in play, arrive already tuned.

The argument against: `wayfarers` is already specced and planned, and this is a
second unbuilt mod on a suite whose stated problem is finishing what exists.
That is a real objection and `ROADMAP.md` §1 would side with it.
