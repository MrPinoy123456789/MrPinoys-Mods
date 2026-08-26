# Zombie Villager Cure Dialogue — Plan

> **Status:** draft, 2026-08-25. Not implemented.
>
> **Reads with:** `SPEC.md` §4, §6, §8 and `PLAN.md` M2–M4. This plan
> amends one SPEC decision (§8 "Zombie villager | Silent") — see §1
> below.

## 0. The idea

Zombie villagers currently say nothing — `SPEC.md` §8 made that
deliberate, because `ZombieVillager` is a different class from
`Villager` and the mod's `Listeners` scan only finds `Villager.class`.

This plan gives them a **three-stage dialogue arc** that tracks the
cure process the player initiates with a weakness potion and a golden
apple:

1. **Hopeless** — ambient lines while still a zombie villager. They
   know what they were, they know what they've become, and they have
   hit rock bottom.
2. **Gaining hope** — lines fired as the cure takes hold, becoming
   more coherent and less despairing as conversion progresses.
3. **Gratitude** — a line from the newly-cured villager thanking the
   player, in the mod's understated register rather than saccharine.

The voice matches the rest of the mod: dry, deadpan, self-aware,
Oblivion/Skyrim-town-NPC in tone. The despair is real but never
melodramatic — a villager who has become a monster and is annoyed
about it as much as anything.

---

## 1. SPEC amendment

`SPEC.md` §8 currently reads:

> | Zombie villager | Silent. It is not a villager for this purpose |

This plan changes that row to:

> | Zombie villager | Speaks via a separate `ZombieVillagers` handler
>   (not the `Villager`-only `Listeners` scan). Cure-arc lines are
>   event-driven and obey the same per-listener budget as reactions.
>   The mod still spawns nothing, changes no AI, and pays nothing —
>   the cure itself is vanilla behaviour, this mod only comments on
>   it. |

Everything else in §7 ("What this mod must never do") still holds:
no item, no effect, no AI change, no spawn. The cure is the player's
action; hearsay only narrates it.

---

## 2. Verified API

Checked with `javap` against `minecraft-merged-deobf-26.2.jar`:

```
net.minecraft.world.entity.monster.zombie.ZombieVillager
    extends net.minecraft.world.entity.monster.zombie.Zombie
    implements net.minecraft.world.entity.npc.villager.VillagerDataHolder
```

| Method | Visibility | Use |
|---|---|---|
| `boolean isConverting()` | public | Poll to detect cure in progress |
| `void setVillagerConversionTime(int)` | public | Not needed (read-only here) |
| `VillagerData getVillagerData()` | public | Profession/type the cured villager will have |
| `InteractionResult mobInteract(Player, InteractionHand)` | public | Where vanilla starts the cure (golden apple) — not hooked |
| `private void startConverting(UUID, int)` | private | Called internally by `mobInteract` — no Fabric event |
| `private void finishConversion(ServerLevel)` | private | Transforms the zombie into a `Villager` and discards self |

**No Fabric event exists** for zombie-villager cure start or finish
(checked `fabric-api-0.156.0+26.2.jar` — no zombie/convert/cure
event classes). Detection is by **polling `isConverting()`** on
tracked entities, which is cheap because the tracked set is tiny
(only zombie villagers near a listening player, and only the ones
currently converting need state).

`Bubbles.say(ServerLevel, Entity, String)` already takes `Entity`,
not `Villager` — so speech bubbles work on a `ZombieVillager` with
no changes to `Bubbles.java`.

---

## 3. Architecture

A new fabric-side class `ZombieVillagers.java`, wired into
`HearsayMod` alongside `Reactions`. It owns three jobs:

### 3.1 Scanning

Each tick, for each `ServerPlayer`, find `ZombieVillager` entities
within `hearingRange` (same range as `Listeners`). This is a
parallel scan to `Listeners.tick()` — it does **not** extend
`Listeners` itself, because `Listeners.Candidate` is typed to
`Villager` and the SPEC's separation between the two speaker kinds
is worth keeping.

```java
// ZombieVillagers.java — sketch
private final Map<UUID, ConversionState> converting = new HashMap<>();

public void tick(MinecraftServer server) {
    // 1. Scan for zombie villagers near players
    // 2. For each, check isConverting() transitions
    // 3. Fire cure_started / cure_progress / cured pools via Speech.offer
    // 4. Clean up state for entities that are gone
}
```

### 3.2 Conversion tracking

```java
record ConversionState(
    UUID zombieId,
    BlockPos pos,
    String professionId,   // from getVillagerData().profession()
    int startTick,
    int lastProgressTick
) {}
```

State transitions:

| Edge | Detection | Pool fired |
|---|---|---|
| Not converting → converting | `isConverting()` false→true on a tracked entity | `zombie_villager.cure_started` |
| Converting (periodic) | `isConverting()` true, `lastProgressTick` older than `cureProgressSeconds` | `zombie_villager.cure_progress` |
| Converting → gone | Tracked entity removed/despawned while in `converting` map | `zombie_villager.cured` (from the new `Villager` if one spawned at `pos`, else as chat narration) |

The "gone" edge is the cure completing: `finishConversion` discards
the `ZombieVillager` and spawns a `Villager` at the same position.
We detect this by checking whether the tracked entity is still in
the level. If it's gone and a `Villager` spawned within a few blocks
of the stored `pos` in the last few ticks, the cured line is
attributed to that new villager (bubble above them). If no villager
is found (the zombie died rather than being cured), no cured line
fires — the state is just cleaned up.

### 3.3 Budget

Cure-arc lines go through `Speech.offer()`, the same path reactions
use. This means:

- Per-listener rate limit applies (one line per `quietSeconds`)
- Damage hush applies (no lines during combat)
- Raid suppression applies
- Mute applies

The cure-started and cure-progress lines are **bubble** channel
(in-world speech from the zombie villager). The cured line is
**chat** channel (a milestone worth keeping in scrollback), delivered
from the new villager.

`cure_progress` fires at most once per `cureProgressSeconds` (new
setting, default **30s**) per converting zombie villager, so a
~100s cure produces roughly 3 progress lines — enough to feel the
arc without spamming.

---

## 4. New settings

One new setting in `HearsayConfig.Settings`:

| Field | Default | Purpose |
|---|---|---|
| `cureProgressSeconds` | 30 | Min interval between `cure_progress` lines per zombie villager |

No new channel entries needed — the `zombie_villager` family
inherits from the `say`/`narrate` defaults:

| Pool | Channel | Why |
|---|---|---|
| `zombie_villager.ambient` | bubble | In-world speech from the zombie |
| `zombie_villager.cure_started` | bubble | Same — the zombie speaks |
| `zombie_villager.cure_progress` | bubble | Same |
| `zombie_villager.cured` | chat | Milestone moment, worth scrollback |

Add to `DEFAULT_CHANNELS`:
```java
"zombie_villager.ambient", "bubble",
"zombie_villager.cure_started", "bubble",
"zombie_villager.cure_progress", "bubble",
"zombie_villager.cured", "chat",
```

---

## 5. New pools in `DefaultLines.java`

Four pools, written in the mod's existing voice. The arc goes from
despair → confusion → dawning hope → understated gratitude.

> **Note on line structure.** The dialogue was generated as flowing
> monologues broken into short fragments. The pool system draws
> **single lines at random** from a shuffle bag (`LinePools.pick`),
> so fragments that only make sense in sequence ("I was a butcher."
> / "Now I am the meat." / "Funny.") have been consolidated into
> standalone lines that work as individual random draws. Every beat
> is preserved; only the line boundaries moved. Contractions have
> also been expanded to match the mod's slightly archaic formal
> diction ("do not" not "don't", "shall" not "will" where it fits).

### 5.1 `zombie_villager.ambient` — hopeless, aware

The zombie villager knows what they were and knows what they have
become. The voice is the mod's dry deadpan, but darker. They
struggle to speak through the zombie state — lines are shorter and
more fragmented than a healthy villager's.

```
"I was a butcher. Now I am the meat. Funny."
"Do not let me near the children. I mean that sincerely."
"I remember my wife. She screamed when she saw me. Good woman. Excellent instincts."
"I tried to eat an iron golem. My teeth hurt. His did not."
"I used to fear zombies. Now they respect me. I do not enjoy this arrangement."
"My hands keep reaching for people. I tell them no. They do not listen."
"I saw myself in a pond. ...Ugly."
"I have become the thing beneath the bed. There was a time when I had a bed. There was a time when I had trousers. I miss the trousers."
"My thoughts are becoming soup. I would like my thoughts back."
"The hunger is speaking again. It has terrible opinions."
"I am going to bite someone. ...Probably you. No offense."
"I have very little control over my manners."
"I remember selling carrots. Now I crave brains. Carrots were better. I should have appreciated them."
"Everything seems funny now. I do not think that is good."
```

### 5.2 `zombie_villager.cure_started` — the first spark

Fired once when `isConverting()` goes true. The zombie villager
feels something change — confusion, a crack in the despair, not
yet hope but the absence of total hopelessness.

```
"What did you do? The hunger stopped. ...Oh."
"Something is inside me. No. Something is leaving."
"My bones feel strange. I can feel my fingers. I have fingers. I had forgotten those."
"Do not move away. I think this may be working."
"The hunger is... quieter. Not gone. But quieter."
"Something is happening. I have not felt 'something happening' in a very long time."
"What is that? Golden apple? ...It feels warm. I had forgotten warm."
"I can think. A little. I can think a little. That is more than yesterday."
"Are you... fixing me? Can this be fixed?"
"...do not stop. Whatever you are doing. Do not stop."
```

### 5.3 `zombie_villager.cure_progress` — gaining hope

Fired periodically during the cure. The voice becomes more
coherent, the despair recedes, and something like the mod's usual
wit starts to come back. Lines should feel like the villager is
surfacing.

```
"I remember the market. I remember the baker. He cheated me. Good. I am still angry."
"My tongue feels less... dead. I nearly said something. It was a word. Probably 'brains.' No. It was 'bread.' Bread. That is encouraging."
"I remember Gerald. The blue sheep. I disliked him before all this. ...I am relieved."
"My sense of humor has returned. This is unfortunate for everyone."
"I can see the village again. I mean I could always see it. But now I can look at it."
"I keep waiting for the hunger to come back. It does not. It is... strange. Good strange."
"My thoughts are faster now. Like wading through dirt instead of cobblestone. Progress."
"I looked at a villager and I did not... I did not want to. That is the best thing that has happened to me in weeks."
"I feel like I am waking up from a nightmare I was also walking around in."
"The golem looked at me today. He did not walk away. I think that is his way of saying hello."
```

### 5.4 `zombie_villager.cured` — gratitude, understated

Fired once when the cure completes, from the new `Villager`. This
is the payoff. The voice is fully the mod's voice again — dry,
self-aware, a little awkward about the whole thing. The gratitude
is real but never said straight; it comes out sideways, the way
the mod's other villagers say everything.

```
"I have returned. My nose is terrible again. Excellent."
"I can smell bread. I had forgotten how good bread smells. I had also forgotten how bad Gerald smells. The blue sheep."
"I remember everything. I remember eating a farmer. ...I shall not be doing that again."
"You saved my life. Do not become proud. I am merely stating a fact."
"I owe you a great deal. If you require anything, ask the farmer. He owes me three emeralds anyway."
"I would shake your hand. But I have recently recovered from being a zombie. ...Perhaps tomorrow."
"You have done a strange and wonderful thing. Now, if you will excuse me, I have a door to repair. It has been broken for some time. I broke it. We shall not speak of that."
"I am not going to say I owe you my life. That is dramatic. But I owe you something and it might be that."
"I can think straight. Do you know how long it has been since I could think straight? ...Do not answer that. Thank you. For the thinking."
"I am going to go back to my job and pretend none of this happened. But I shall remember. Thank you."
"...if you ever need a favour. A small one. Not involving apples. You know where I am."
"I stood at the bottom for a long time. You reached down. That is all I am going to say about it."
```

---

## 6. Wiring

### 6.1 `ZombieVillagers.java` (new, fabric side)

```java
public final class ZombieVillagers {
    private final HearsayConfig config;
    private final Speech speech;
    private final Map<UUID, ConversionState> converting = new HashMap<>();
    private int tick;

    public ZombieVillagers(HearsayConfig config, Speech speech) { ... }

    public void tick(MinecraftServer server) {
        tick++;
        // 1. Scan ZombieVillager entities near each ServerPlayer
        // 2. For each: read isConverting(), getVillagerData().profession()
        // 3. Detect false→true edge → fire cure_started, record state
        // 4. For tracked converting entities: fire cure_progress on interval
        // 5. For tracked entities no longer in the world: fire cured
        //    from the nearest new Villager at the stored pos, then clean up
    }
}
```

### 6.2 `HearsayMod.java` changes

```java
// In onInitialize(), after reactions:
zombieVillagers = new ZombieVillagers(config, speech);

// In the tick registration:
ServerTickEvents.END_SERVER_TICK.register(server -> {
    listeners.tick(server);
    scenes.tick(server, listeners);
    speech.tick(server, listeners);
    reactions.tick(server, listeners);
    zombieVillagers.tick(server);          // new
});
```

### 6.3 `HearsayConfig.java` changes

- Add `cureProgressSeconds` to `Settings` record, `defaultSettings()`,
  `parseSettings()`, `renderSettings()`.
- Add the four `zombie_villager.*` channel entries to
  `DEFAULT_CHANNELS`.

### 6.4 `DefaultLines.java` changes

- Add the four new pools (`zombie_villager.ambient`,
  `zombie_villager.cure_started`, `zombie_villager.cure_progress`,
  `zombie_villager.cured`) to the `POOLS` map.
- Update the class-level doc comment to mention the new pools and
  that they are wired (unlike the unwired `reaction.*` pools noted
  there).

### 6.5 `Speech.java` — no changes needed

`Speech.offer()` already takes a `Listeners.Candidate`, which is
typed to `Villager`. For the cure-started and cure-progress lines,
the speaker is a `ZombieVillager`, not a `Villager`. Two options:

**Option A (preferred):** Add a parallel `offer` overload that
takes a generic `Entity` speaker and a `String professionId`,
factoring the budget logic out of the existing `offer`. The
existing `offer(ServerPlayer, Listeners.Candidate, String)` becomes
a thin wrapper. This keeps the budget logic in one place.

**Option B:** `ZombieVillagers` calls `Bubbles.say()` and
`player.sendSystemMessage()` directly, duplicating the budget
checks. Rejected — the budget logic must stay in one place per
`SPEC.md` §3.

### 6.6 `Listeners.java` — no changes

The existing `Listeners` scan stays `Villager`-only. Zombie
villagers are scanned by `ZombieVillagers` independently. This
keeps the SPEC's separation intact and avoids changing the type of
`Listeners.Candidate`.

---

## 7. Edge cases

| Case | Behaviour |
|---|---|
| Zombie villager dies mid-cure (killed by player or mob) | `converting` state cleaned up, no `cured` line fires |
| Zombie villager despawns mid-cure (too far from players) | Same — state cleaned up, no line |
| Two players curing two zombie villagers at once | Each tracked independently; per-listener budget still applies |
| Player cures a zombie villager with no other villagers nearby | `cured` line fires as chat narration (no new villager found within range — unlikely, since the cure spawns one, but handle it) |
| Server restart mid-cure | `converting` map is in-memory only — state lost, no harm. The zombie villager resumes converting on reload but we don't fire `cure_started` again (no false→true edge) |
| Zombie villager is converting but no player is in range | Not scanned, not tracked — no lines fire, which is correct (nobody is listening) |
| The cure finishes between two ticks | The `cured` line fires on the next tick after the entity is gone — at most 50ms late, imperceptible |

---

## 8. Build order

One milestone, since the feature is self-contained:

### M-ZV — Zombie villager cure dialogue

1. Add the four pools to `DefaultLines.java` and update the doc comment
2. Add `cureProgressSeconds` to `HearsayConfig` (record, default, parse, render)
3. Add the four channel entries to `DEFAULT_CHANNELS`
4. Add the `offer` overload to `Speech` that takes `Entity` + `professionId`
5. Create `ZombieVillagers.java` with scan, track, fire logic
6. Wire `ZombieVillagers` into `HearsayMod.onInitialize()` and the tick loop
7. Add a `/hearsay say <player> zombie_villager.cured` test path (already works via existing `forceSay` — just needs a `Candidate` for the new villager, or the new `offer` overload)

**Acceptance:**
- [ ] `./gradlew build` clean
- [ ] A zombie villager near a player produces hopeless ambient lines
- [ ] Applying weakness + golden apple fires a `cure_started` line
- [ ] During the cure, `cure_progress` lines fire every ~30s, growing more hopeful
- [ ] When the cure completes, a `cured` line fires from the new villager in chat
- [ ] Killing the zombie villager mid-cure produces no `cured` line
- [ ] Per-listener rate limit still applies — the cure arc doesn't spam
- [ ] Damage hush and raid suppression still apply
- [ ] No item, effect, or AI change — grep the diff to prove it
- [ ] A vanilla client sees all of it with nothing installed

---

## 9. What this plan does NOT do

- **No profession-specific zombie villager pools.** The zombie
  villager retains `VillagerData` with a profession, so
  `minecraft:farmer.zombie_villager.ambient` etc. could exist. They
  don't yet — start with the four generic pools. Profession pools
  can be added later the same way the existing
  `minecraft:farmer.ambient` pools were.
- **No two-hander scenes for zombie villagers.** A zombie villager
  and a villager having a conversation is a good idea but it's a
  separate feature — it needs scene matching against a
  `ZombieVillager` speaker, which `Scenes.java` doesn't support.
- **No persistence of cure state across restarts.** In-memory only,
  matching the mod's "nothing is worth remembering" rule (§2).
- **No new sounds.** The SPEC says villager sounds are used
  sparingly. Zombie villager lines are text-only; no
  `ZombieVillager.AMBIENT` sound hook is added.
