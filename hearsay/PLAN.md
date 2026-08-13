# Hearsay — Implementation Plan

> **Reads with:** `SPEC.md` in this folder. The spec is the design authority;
> this is the build order. Where they disagree, the spec wins and the
> disagreement gets reported.
>
> **Audience:** written for an agent with no prior context on this repo or
> conversation. Every file reference below was checked against the files that
> actually exist on disk, and every Minecraft/Fabric API referenced below was
> checked with `javap` against the real jars in the Gradle cache — see §0.5.
> Trust the signatures printed there over anything SPEC.md guessed at.
>
> **Written:** 2026-08-13.

---

## 0. Copy, don't invent

**Most of this mod already exists, working, in `wayfarers`.** `wayfarers`
implements the exact dialogue engine `SPEC.md` §6 says is "deliberately
identical" to its own §5.3 — same `Script` shape, same shuffle-bag `LinePools`,
same `TextDisplay` bubble trick, same per-listener rate limit. Copying is the
correct move here (`DESIGN.md` §2: duplicate, never share a library), not a
shortcut.

**Read the source file before writing the new one, every time.**

| New file | Copy from | What changes |
|---|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew*` | `wayfarers/` root + `fabric/build.gradle.kts` + `core/build.gradle.kts` | Names only. **Drop the sgui dependency and the `maven("nucleoid.xyz")` repo** — hearsay has no GUI |
| `core/ReadOrCreate.java` | `wayfarers/core/src/main/java/wayfarers/core/ReadOrCreate.java` | Package declaration only. Do not touch the logic |
| `core/RateLimit.java` | `wayfarers/core/.../RateLimit.java` | Package only. It's already exactly "has enough time passed, in ticks" |
| `core/Script.java` | `wayfarers/core/.../Script.java` | Package only. `say`/`narrate`/`wait`/`action` steps with speaker index — copy wholesale |
| `core/LinePools.java` | `wayfarers/core/.../LinePools.java` | Package only. Shuffle-bag draw, `withDefaults` merges missing pools only, `pickFor(id, moment)` fallback to bare `moment` — this **is** the `<profession>.<moment>` scheme SPEC.md §6.1 wants, just rename the concept in the doc comment |
| `fabric/Bubbles.java` | `wayfarers/fabric/.../Bubbles.java` | Package only, at first. It rides the speaker entity via `startRiding`, so it already follows a moving villager with no extra work. Retune `SPEECH_VIEW_RANGE` in play per SPEC.md §3's "first thing to tune" note |
| Test harness style | `wayfarers/core/src/test/java/wayfarers/core/WayfarersTest.java` | **No JUnit.** A `main()` that prints `N passed, 0 failed` |
| `HearsayConfig.java` shape | `wayfarers/fabric/.../WayfarersConfig.java` | The `readOrCreate` + Gson glue pattern — default JSON as a Java text block, `reload()` loading three files. Content differs completely |

**Do not copy:** `TradeGui`, `Payment`, `TraderRegistry`, `ItemComponents`,
`Wallet`, `Currencies`, `Wager`, `Drops`, `Hostile`, `Spawns`, `OrphanSweep`.
None of it applies — hearsay spawns nothing and pays nothing (`SPEC.md` §1).

---

## 0.5 Verified APIs — read this before writing any fabric-side code

`SPEC.md` §10 lists six things to verify before coding. All six were checked
just now with `javap` against the real jars: the game jar at
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
and Fabric API at
`~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.156.0+26.2/.../fabric-api-0.156.0+26.2.jar`
(Fabric API ships as one jar with each module nested under `META-INF/jars/*.jar`
— `unzip -p outer.jar META-INF/jars/fabric-entity-events-v1-....jar > x.jar`
to get at one module).

**⚠ The one correction that matters most: the villager classes moved package.**
SPEC.md assumed `net.minecraft.world.entity.npc.Villager`. In 26.2 it is:

```java
net.minecraft.world.entity.npc.villager.Villager
net.minecraft.world.entity.npc.villager.AbstractVillager
net.minecraft.world.entity.npc.villager.VillagerData      // record: type(), profession(), level()
net.minecraft.world.entity.npc.villager.VillagerProfession
net.minecraft.world.entity.npc.villager.VillagerDataHolder
```

`IronGolem` also moved: `net.minecraft.world.entity.animal.golem.IronGolem`
(§4 reaction table). `Creeper` did **not** move:
`net.minecraft.world.entity.monster.Creeper`.

| # | Question | Verified answer |
|---|---|---|
| 1 | Actionbar delivery | `ServerPlayer.sendSystemMessage(Component, boolean)` exists (confirmed via `javap`). `true` = actionbar overlay, `false` = chat. No packet-building needed |
| 2 | Raid state accessor | `ServerLevel.isRaided(BlockPos)` exists — exactly what §4/§7 need, no manual raid-list scan required. `ServerLevel.getRaidAt(BlockPos)` also exists returning a nullable `Raid` with `isStarted()`/`isOver()`/`isBetweenWaves()` if finer state is ever wanted |
| 3 | Sleep event | Fabric API has a real event: `EntitySleepEvents.START_SLEEPING` (`net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents$StartSleeping`, method `onStartSleeping(LivingEntity, BlockPos)`). No mixin, no polling needed. Filter to `instanceof ServerPlayer` |
| 4 | Villager idle test | `AbstractVillager.isTrading()` exists (returns true while a trade screen is open with them) — use `!villager.isTrading()` as the idle gate for two-hander candidates. Combine with `!villager.isSleeping()` (on `LivingEntity`, also exists) |
| 5 | `VillagerProfession` registry id | `BuiltInRegistries.VILLAGER_PROFESSION` is a `DefaultedRegistry<VillagerProfession>` (confirmed). Resolve with `BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.profession().value())` — a plain registry reverse lookup, same shape `chatdonkey`'s `Interactions.java` uses for items. **Do not** use `Holder.unwrapKey()` chains; the direct registry lookup is what the rest of the suite already does and is cheap enough for a per-tick proximity poll |
| 6 | Explosion hook | **No dedicated Fabric API explosion event exists** in `fabric-entity-events-v1` (checked; only `ServerLivingEntityEvents`, `ServerEntityCombatEvents`, `EntitySleepEvents`, `ServerPlayerEvents`, `ServerEntityLevelChangeEvents`, effect events). Use `ServerLivingEntityEvents.AFTER_DEATH` filtered to `entity instanceof Creeper` — the same trick SPEC.md itself suggested as the cheaper alternative. No mixin required |
| 7 | Villager sounds | `SoundEvents.VILLAGER_YES` / `VILLAGER_NO` / `VILLAGER_TRADE` / `VILLAGER_AMBIENT` all confirmed as bare `SoundEvent` constants (not `Holder`). Play with `level.playSound(null, pos, event, SoundSource.NEUTRAL)` |

**One thing SPEC.md didn't ask about but the mod needs: detecting a completed
trade** (§4's `traded` trigger). There is no Fabric API "trade completed"
event either. `MerchantOffer` has `getUses()` / `increaseUses()` (confirmed).
Cheapest mixin-free approach: watch `AbstractVillager.getTradingPlayer()`.
When it transitions from non-null to null (the trade screen closed), compare
the sum of `getUses()` across `getOffers()` against a snapshot taken when the
screen opened; if it went up, the `traded` pool fires for that player. Track
this only for villagers currently being traded with — a tiny set, checked once
per tick.

---

## 1. Milestones

Seven, each independently buildable and verifiable. **Do one per session.**
Do not start the next until the current one's acceptance list is fully checked.

---

### M0 — Scaffold

**Goal:** a jar that loads, logs one line, and lands in `dist/`.

**Create:** `settings.gradle.kts`, `gradle.properties`, root/`core`/`fabric`
`build.gradle.kts`, the Gradle wrapper, `fabric/src/main/resources/fabric.mod.json`,
and `fabric/src/main/java/hearsay/HearsayMod.java` (entrypoint, logs mod name
and version on init, nothing else yet).

**Copy the build from `wayfarers`**, then:
- `archivesName = "MrPinoys_hearsay"`, `maven_group=hearsay`
- Same `minecraft_version=26.2` / `loader_version=0.19.3` /
  `fabric_api_version=0.156.0+26.2` as every other mod — do not "update" them
- **No sgui dependency, no Nucleoid maven repo.** `fabric/build.gradle.kts`
  needs only `fabric-loader`, `fabric-api`, and `implementation(project(":core"))`
  / `include(project(":core"))`
- `core/build.gradle.kts` stays **empty of dependencies** — that emptiness is
  what makes a stray `import net.minecraft.*` fail the build, deliberately
- `fabric.mod.json`: `"environment": "server"`, entrypoint `hearsay.HearsayMod`,
  `depends` on fabricloader / minecraft / java / fabric-api. No `suggests`, no
  other mod named anywhere
- `loom { }` block stays empty — no client source set, this mod is server-side
  only, same as `wayfarers`

> **⚠ The bug every mod in this suite has hit — nine times now.** The `dist`
> Copy task must `dependsOn("jar")`, **never `remapJar`**. 26.2 ships
> unobfuscated so Loom registers no `remapJar` task and depending on it fails
> the build outright.

**Acceptance:**
- [ ] `./gradlew build` succeeds from `a:/MrPinoys Mods/hearsay`
- [ ] `dist/MrPinoys_hearsay-0.1.0.jar` exists
- [ ] `core/build.gradle.kts` declares no dependencies
- [ ] `fabric/build.gradle.kts` has no sgui dependency and no Nucleoid repo
- [ ] Zero compiler warnings

---

### M1 — The core model, fully tested, no Minecraft

**Goal:** every pure-arithmetic rule in `SPEC.md` §3, §5, §6 implemented and
under test, with no game running.

**Create in `core/src/main/java/hearsay/core/`:**

| File | Owns |
|---|---|
| `ReadOrCreate.java` | Copied verbatim from `wayfarers/core` |
| `RateLimit.java` | Copied verbatim. Used for **both** the per-listener `quietSeconds` budget and the per-speaker `speakerCooldownSeconds` cooldown — two different instances of the same class, keyed by different UUID maps fabric-side |
| `Script.java` | Copied verbatim. Ordered `say`/`narrate`/`wait`/`action` steps, speaker index preserved |
| `LinePools.java` | Copied verbatim. Pool key is `"<profession>.<moment>"`; `pickFor(professionId, moment, random)` already falls back to the bare `moment` pool exactly per `SPEC.md` §6.1. `withDefaults` merges missing **pools**, never lines within an existing pool — keep that doc comment, it's the trap §6.1 calls out |
| `Scene.java` | A two-hander definition: two speaker slots (`"minecraft:shepherd"`, `"*"`, etc.), a `channel`, and a `Script`. New logic, not copied — see below |
| `Triggers.java` | Pure edge-detection helpers — see below |
| `DefaultLines.java` | The shipped content as plain Java data — see below |

**`Scene.java` — the new piece.** A scene applies to a candidate pair of
villagers (profession id A, profession id B, as encountered — order is
whatever order the proximity scan found them in, not meaningful on its own).
Matching must try **both assignments** because a shepherd-farmer pair can be
walked up to from either side:

```java
public record Scene(String speaker0, String speaker1, String channel, Script script) {

    /** Which candidate plays which script speaker, or empty if this scene doesn't apply. */
    public Optional<Assignment> match(String professionA, String professionB) {
        if (slotMatches(speaker0, professionA) && slotMatches(speaker1, professionB)) {
            return Optional.of(new Assignment(false)); // A is speaker 0, B is speaker 1
        }
        if (slotMatches(speaker0, professionB) && slotMatches(speaker1, professionA)) {
            return Optional.of(new Assignment(true));  // B is speaker 0, A is speaker 1
        }
        return Optional.empty();
    }

    private static boolean slotMatches(String slot, String profession) {
        return "*".equals(slot) || slot.equals(profession);
    }

    public record Assignment(boolean swapped) {}
}
```

A generic `["*", "*"]` scene matches any pair in either order (trivially, both
branches succeed — pick the first). A `["minecraft:shepherd", "minecraft:farmer"]`
scene only matches a shepherd+farmer pair, and tells the caller which physical
villager reads which line.

**`Triggers.java` — pure edge detection**, no Minecraft types, just longs and
booleans, so it's testable without a server:

```java
public final class Triggers {
    private Triggers() {}

    /** Minecraft day time wraps at 24000; dawn is ~23000->1000, dusk is ~12000->13000. */
    public static boolean crossedDawn(long prevTimeOfDay, long nowTimeOfDay) { ... }
    public static boolean crossedDusk(long prevTimeOfDay, long nowTimeOfDay) { ... }

    public static boolean weatherStarted(boolean wasRaining, boolean isRaining) {
        return isRaining && !wasRaining;
    }
}
```

Fabric-side code owns *calling* these with `level.getDayTime() % 24000` and
`level.isRaining()` snapshots taken once per tick; the edge-detection math
itself is what's under test here.

**`DefaultLines.java`** — plain Java data (`Map<String, List<String>>` for
pools, `List<Scene>`-shaped records for scenes... but `Scene` needs `Script`
which is fine, both are in `core`). This is the single source of truth; the
fabric config's JSON defaults are rendered *from* this data (see M2), not
hand-duplicated. Cover at minimum: `ambient`, `greeting`, `traded`, `morning`,
`night`, `weather`, and each `reaction.*` pool named in `SPEC.md` §4 (a
`reaction.creeper`, `reaction.golem_death`, `reaction.raid_end`,
`reaction.villager_death`, `reaction.player_slept`, `reaction.night_outside`),
one bare pool per moment as the fallback, plus a `minecraft:farmer` or
`minecraft:shepherd` profession-specific pool as a worked example, plus at
least the two scenes from `SPEC.md` §5's example JSON.

**Tests** in `core/src/test/java/hearsay/core/HearsayTest.java`, house style —
a `main()` printing `N passed, 0 failed`. Cover at minimum:
- `RateLimit`: first call always allowed; a second call before the cooldown is
  refused; a call after the cooldown is allowed
- `LinePools`: fallback to bare moment when `<profession>.<moment>` is
  missing; shuffle bag yields every line once before repeating; `withDefaults`
  fills a missing pool but never touches an existing one, even a shorter one
- `Script`: steps yield in order; `speaker` index is preserved; `"..."` is a
  legal `say` line
- `Scene.match`: direct order match, swapped order match, one wildcard slot,
  both wildcard slots (matches anything, either order), a genuine mismatch
  returns empty
- `Triggers.crossedDawn` / `crossedDusk`: true exactly once across the
  midnight-adjacent wraparound, false for two times on the same side of the
  boundary
- `Triggers.weatherStarted`: true only on the false→true edge

**Acceptance:**
- [ ] `javac --release 25 -d build core/src/main/java/hearsay/core/*.java core/src/test/java/hearsay/core/*.java` compiles clean
- [ ] `java -cp build hearsay.core.HearsayTest` prints `0 failed`
- [ ] At least 40 assertions
- [ ] `grep -r "net.minecraft" core/src` returns nothing
- [ ] `./gradlew build` clean

---

### M2 — Config, entrypoint wiring, the proximity scan

**Goal:** prove the candidate-finding scan works and profession resolution is
correct, before any line of dialogue is ever spoken. No player-visible output
yet beyond a status command.

**Create in `fabric/src/main/java/hearsay/`:** `HearsayConfig.java`,
`Listeners.java`, `HearsayCommands.java` (just `/hearsay status` and
`/hearsay reload` for now).

**`HearsayConfig.java`**, modelled on `WayfarersConfig.java`: `settings.json`
(`quietSeconds` 45, `hearingRange` 16, `speakerCooldownSeconds` 120,
`maxSimultaneousScenes`, `sceneChance`, `ambientChance`,
`suppressAfterDamageSeconds` 8, per-pool-kind `channel` defaults — `SPEC.md`
§9) and `lines.json` (pools + `scenes` array, rendered from `DefaultLines` so
there is exactly one place the shipped content lives). Both via
`ReadOrCreate.load`, never overwriting a file that failed to parse.

**`Listeners.java`** — the proximity scan, **from players outward**, never a
world-wide villager scan (`SPEC.md` §8's 200-villager row):

```java
for (ServerPlayer player : level.players()) {
    AABB box = player.getBoundingBox().inflate(config.settings().hearingRange());
    List<Villager> nearby = level.getEntitiesOfClass(Villager.class, box);
    for (Villager v : nearby) {
        String professionId = professionId(v); // BuiltInRegistries.VILLAGER_PROFESSION.getKey(...)
        // candidate list per player, ready for M3/M4/M5 to consume
    }
}
```

`professionId(Villager v)`:

```java
Holder<VillagerProfession> profession = v.getVillagerData().profession();
ResourceLocation id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession.value());
// "minecraft:none" for an unemployed villager / nitwit -> falls straight
// through to LinePools' bare-pool fallback, which is already correct
```

**Note the import:** `net.minecraft.world.entity.npc.villager.Villager`, not
`net.minecraft.world.entity.npc.Villager` — see §0.5.

**Filter out** zombie villagers (`ZombieVillager` is a different class
entirely — a plain `getEntitiesOfClass(Villager.class, ...)` already excludes
it, nothing to do) and sleeping villagers (`v.isSleeping()`) at the point
where a line would be chosen, not at scan time — the scan itself stays cheap
and dumb.

**Acceptance:**
- [ ] `./gradlew build` clean
- [ ] `/hearsay status` reports villager count and resolved profession per
      player, on a dev server (set one up under `fabric/run/`, following
      `wayfarers/fabric/run/` — EULA accepted, flat world, offline mode)
- [ ] An unemployed villager resolves to `minecraft:none` and is logged as
      falling back to the bare pool
- [ ] `config/hearsay/settings.json` and `lines.json` are created on first
      boot; deleting and restarting recreates them; hand-breaking the JSON and
      restarting logs one line and leaves the broken file alone
- [ ] Nothing about vanilla villager behaviour is touched — no AI, no trades,
      confirm by trading with a villager during the scan

---

### M3 — Speech delivery and the chat budget (the load-bearing milestone)

**Goal:** ambient lines and greetings actually reach players, correctly
budgeted. **This is the milestone `SPEC.md` §3 calls "the mod lives or dies
on."** Get the per-listener rate limit wrong here and the mod is worse than
silence — this milestone's acceptance list is the one most likely to fail
quietly (looks fine with three villagers, unreadable with twenty).

**Create:** `Bubbles.java` (copied per §0), `Speech.java`, wire `Listeners`
into a tick loop.

**`Speech.java`** owns the whole budget, modelled directly on `wayfarers`'
`Dialogue.java`:

```java
private final Map<UUID, RateLimit> perListener = new HashMap<>();      // quietSeconds
private final Map<UUID, RateLimit> perSpeaker = new HashMap<>();       // speakerCooldownSeconds
private final Map<UUID, Integer> damagedUntil = new HashMap<>();       // suppressAfterDamageSeconds
```

**Rule order, exactly `SPEC.md` §3's priority list:**
1. **Per-listener rate limit first.** If `perListener.get(listenerId)` isn't
   allowed this tick, nothing is even considered for that player — this is
   the rule that must never be checked per-speaker instead, because doing so
   is "the intuitive implementation... and it is the one that will be written
   by accident." Write the test for this before writing the code that could
   get it wrong: twenty `RateLimit` instances passing independently is the
   bug; one `RateLimit` per listener gating everything is the fix.
2. **Damage hush**: `tick < damagedUntil.getOrDefault(listenerId, 0)` skips.
   Hook via `ServerLivingEntityEvents.AFTER_DAMAGE`, same as `wayfarers` —
   `if (entity instanceof ServerPlayer p) speech.hush(p.getUUID(), suppressAfterDamageSeconds * 20)`.
3. **Raid suppression**: `level.isRaided(villager.blockPosition())` — see
   §0.5 #2. Skip entirely while raided; `reaction.raid_end` may fire once on
   the true→false edge (M4).
4. **Per-speaker cooldown** on top: `perSpeaker.get(villagerId)`.
5. **A running two-hander holds the floor** — a listener currently hearing a
   scene (tracked by `Scenes`, built in M5) gets no ambient line at all,
   checked before anything above even rolls dice. M3 doesn't have scenes yet,
   so stub this check as always-false and revisit it when M5 lands — do not
   forget to wire it in then.

**Channel routing**, exactly `SPEC.md` §3's table:
- Ambient line / greeting → **actionbar**: `listener.sendSystemMessage(component, true)`
- `say` (villager speech, including scene lines routed as `say`) → **bubble**
  above the speaking villager, via `Bubbles.say(level, villagerEntity, line)`
- `narrate` / rumour / two-hander narration → **chat**, italic grey, no
  speaker name: `Component.literal(line).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY)`,
  sent with `sendSystemMessage(component, false)`

**Greeting cooldown** is separate from ambient: a per-villager-per-player seen
set (or just reuse `perSpeaker` keyed by a composite — simplest is a
`Map<UUID, RateLimit>` keyed by `listenerId.toString() + ":" + villagerId`,
long enough a cooldown that "passing twice is not two greetings," per
`SPEC.md` §4).

**Acceptance — this is the §3.1 test, verify it doesn't just look fine:**
- [ ] Stand a player next to **twenty** villagers (spawn them in creative for
      the test, or find/build a large village) and confirm **one line total**
      reaches the player per `quietSeconds`, not one per villager
- [ ] Two players standing together each get their own normal cadence — the
      budget is per listener, not shared
- [ ] Nothing is said within `suppressAfterDamageSeconds` of the player taking
      any damage
- [ ] Nothing is said while `isRaided` is true at the speaking villager's
      position; confirm by starting a raid near a village
- [ ] A single villager does not become "the voice of the village" — walk
      away and back within `speakerCooldownSeconds`, confirm silence from
      that one villager while others still speak
- [ ] Ambient/greeting render as actionbar text (not chat, not a bubble)
- [ ] A forced `say`-style line (stub it in `/hearsay status` output or a
      temporary debug branch) renders as a bubble above the correct villager
      and follows it if it walks
- [ ] Trading with a villager mid-conversation opens the trade screen
      instantly — nothing about vanilla trading is slowed or blocked
- [ ] `./gradlew build` clean; core tests still `0 failed`

---

### M4 — Reactions, time, weather, and the traded trigger

**Goal:** every row of `SPEC.md` §4's two tables fires correctly, each at most
once per village per event.

**Create:** `Reactions.java`. Extend `HearsayConfig`/`DefaultLines` if a pool
was missed in M1.

| Trigger | Wiring |
|---|---|
| Dawn / dusk | Once per tick per loaded level, snapshot `level.getDayTime() % 24000` and call `Triggers.crossedDawn`/`crossedDusk` from M1 against the previous snapshot |
| Weather start | Same shape with `level.isRaining()` and `Triggers.weatherStarted` |
| Creeper explosion | `ServerLivingEntityEvents.AFTER_DEATH.register(...)`, filter `entity instanceof Creeper` — §0.5 #6 |
| Iron golem died | Same event, filter `entity instanceof IronGolem` (import from `net.minecraft.world.entity.animal.golem.IronGolem` — §0.5) |
| Villager died | Same event, filter `entity instanceof Villager` |
| Raid started/ended | Poll `level.isRaided(pos)` per active village cluster (reuse the candidate-villager positions `Listeners` already gathers) on a slow interval (e.g. every 20 ticks is plenty); fire `reaction.raid_end` on the true→false edge only |
| Player slept | `EntitySleepEvents.START_SLEEPING.register((entity, pos) -> { if (entity instanceof ServerPlayer p) ... })` — §0.5 #3, no polling, no mixin |
| Night fell, player still outside | At the dusk-crossing edge, for each online player check `!player.isSleeping() && level.canSeeSky(player.blockPosition())` |
| Trade completed | Poll `AbstractVillager.getTradingPlayer()` transitions non-null→null on villagers currently open for trade (a tiny, self-limiting set); compare summed `MerchantOffer.getUses()` against a snapshot taken when the screen opened — §0.5's addendum |

**"Once per village per event"**: key a short-lived dedupe set by a rounded
position (e.g. block position divided by 32) plus the event kind, cleared
after a cooldown (a few minutes) rather than tracked forever — a plain
`Map<String, Integer /* expiry tick */>` is enough, no persistence needed
(`SPEC.md` persistence: none).

All reaction lines still route through `Speech`'s full budget from M3 —
reactions are not exempt from the per-listener rate limit or the damage/raid
hush, they just add another way a `narrate`/`say` gets *offered*.

**Acceptance:**
- [ ] A creeper exploding near a village produces a reaction line, once, not
      once per nearby villager
- [ ] An iron golem death produces a reaction line
- [ ] Dawn and dusk each produce a `morning`/`night` line once per crossing,
      not every tick the check happens to run
- [ ] Rain starting produces a `weather` line; it does not re-fire every tick
      while still raining
- [ ] Sleeping in a bed produces the sleep reaction; nothing fires again for
      the same sleep
- [ ] Completing a trade produces a `traded` line from that villager, and only
      that villager
- [ ] A raid ending produces exactly one reaction line for that raid
- [ ] Everything above still respects §3's budget — flood the trigger (many
      creepers) and confirm the player still hears at most one line per
      `quietSeconds`
- [ ] `./gradlew build` clean; core tests still `0 failed`

---

### M5 — Two-hander scenes

**Goal:** the reason the mod exists. Two idle villagers near a listening
player play an ordered script that neither interleaves with itself nor with
ambient chatter.

**Create:** `Scenes.java`.

**Selection**, run on the same tick loop, gated behind `sceneChance` and the
server-wide `maxSimultaneousScenes` cap **before** doing any pairing work
(cheapest check first):

1. For each listening player with candidate villagers (from `Listeners`),
   find pairs within a small radius of each other (a few blocks — tune in
   play) where **both** are idle: `!v.isTrading() && !v.isSleeping()`
   (§0.5 #4), and both are off their `perSpeaker` cooldown (the same map
   `Speech` already owns — a villager mid-scene or just finished one is not
   picked again immediately).
2. Resolve each candidate's profession id (reuse `Listeners`' helper), call
   `Scene.match(profA, profB)` from M1 across the configured scene list, first
   match wins per the config file's order — profession-tagged scenes should
   simply be listed before the generic `["*","*"]` catch-all in `lines.json`
   so specific scenes get first refusal.
3. Claim the floor: register both villager UUIDs as "in a scene" and mark the
   listener(s) currently in range as "hearing a scene" — this is exactly the
   flag M3 stubbed as always-false; wire it for real now. While claimed,
   `Speech` must refuse ambient lines to those listeners and refuse to start
   any other scene that would reuse either villager.

**Playback**: schedule `Script.steps()` across ticks — `wait` steps are a
tick delay, `say` steps route to `Bubbles.say` on the correct physical
villager per the `Assignment.swapped()` from `Scene.match`, `narrate` routes
to chat exactly as in M3, `action` steps execute the six verbs:
`look_at_player` / `look_away` / `swing` / `shake_head` do something small and
visible (e.g. `villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.position())`
for the look verbs, a swing animation packet or `villager.swing(InteractionHand.MAIN_HAND)`
for `swing`); `vanish` and `walk_away` are **ignored** here, per `SPEC.md` §6 —
they're meaningful for `wayfarers`' spawned bodies, meaningless for a villager
that isn't going anywhere.

**Abort quietly** if, at any point mid-scene, either speaker `isRemoved()`,
`isDeadOrDying()`, or has moved out of a sane proximity of the other — stop
immediately, release both floor claims, no error logged, no partial line left
hanging (this mirrors `wayfarers`' "speaker dies mid-scene, no error" rule
from its M3).

**If the player walks away but a second listener is still in range**, the
scene continues for them; it only aborts if *no* listener remains in range —
`SPEC.md` §8's edge case table.

**Acceptance:**
- [ ] Two idle villagers near a player occasionally start a scripted
      exchange; profession-tagged scenes only fire with a matching pair
- [ ] The scene plays in order with its `wait` beats intact, in **chat**
- [ ] Two scenes never run at once for overlapping listeners; a scene never
      interleaves with an ambient line for a listener currently hearing it
- [ ] Attacking or otherwise killing a speaker mid-scene aborts it with no
      error and no dangling bubble
- [ ] One speaker walking far enough away aborts the scene if no listener
      remains in range, and continues if one does
- [ ] `maxSimultaneousScenes` is enforced server-wide
- [ ] `"..."` plays correctly as a beat, not as an empty/skipped line
- [ ] Villager trading remains completely unaffected during an active scene
- [ ] `./gradlew build` clean; core tests still `0 failed`

---

### M6 — Commands, mute, and the definition of done

**Goal:** the operator- and player-facing surface, then `SPEC.md` §12 in full.

**Create:** finish `HearsayCommands.java`.

Commands, all gated `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`
except `/hearsay mute`, which is a player command with no permission gate
(it's the player silencing the mod for themselves):

- `/hearsay reload` — `config.reload()`, log the outcome per file
  (`CREATED`/`LOADED`/`KEPT_BROKEN` from `ReadOrCreate.Outcome`)
- `/hearsay status` — candidate counts, active scene count, per-player mute
  state
- `/hearsay say <player> <pool>` — force a line through the real `Speech`
  path (so it's still budgeted/routed correctly) for line-writing iteration
- `/hearsay scene <player>` — force a two-hander for the nearest eligible pair
  near that player, bypassing `sceneChance` but not the eligibility checks
- `/hearsay mute <player>` — toggles a `Set<UUID>` held in `Speech`, checked
  as the very first gate before the per-listener rate limit. **In-memory
  only** — `SPEC.md` §5 says persistence is none, and §12 says mute must be
  "remembered for their session," not forever. Do not write it to disk.

**Final acceptance is `SPEC.md` §12 in full**, on a running server with a
fully vanilla client attached. Reproduced here so nothing is missed:

- [ ] `./gradlew build` clean; `MrPinoys_hearsay-0.1.0.jar` in `dist/`
- [ ] Walking through a village produces occasional lines, not a wall of text
- [ ] Twenty villagers in range → one line per `quietSeconds` (§3.1's test)
- [ ] Two-hander scenes play in order, in chat, with beats intact
- [ ] Two scenes never interleave; a scene never interleaves with an ambient
      line
- [ ] A speaker dying mid-scene aborts it quietly
- [ ] Profession pools resolve; unemployed villager falls back to bare pool
- [ ] Nothing said within `suppressAfterDamageSeconds` of player damage, or
      during a raid
- [ ] Reaction lines fire for a creeper explosion and a dead iron golem
- [ ] Villager trading is completely unaffected — mid-scene trade opens
      instantly
- [ ] No item, currency, or effect is ever granted — `grep -rn "give\|addItem\|GameMode.*survival" fabric/src` and confirm nothing found does that
- [ ] `/hearsay say` and `/hearsay scene` force output for iteration
- [ ] `/hearsay mute` silences the mod for one player, remembered for their
      session
- [ ] A broken `lines.json` leaves the file alone and logs once
- [ ] A fully vanilla client sees all of it with nothing installed

---

## 2. Deliberately not in v1

`SPEC.md` §13, verbatim scope: quests, rewards of any kind, reputation,
villager memory of the player, dialogue trees or player replies, voice or
animalese, spawning villagers, naming villagers, illager or mob chatter,
per-village personalities, and any interaction with the other mods in the
suite — including the `ROADMAP.md` §3 facts contract, should it ever ship.

If it ships later, `SPEC.md` §7 already says rumour text stays
operator-authored for v1 and the facts contract is a separate, later decision
— keep line selection behind `LinePools.pickFor`, a single method, so swapping
in live facts is a one-site change and not a rewrite.
