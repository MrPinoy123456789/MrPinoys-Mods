# MrPinoy's Kamu Totems — Parallel Implementation Plan & Progress

> **This is the single shared progress document.** Three agents work in parallel
> against the frozen contract in §3, each owning a disjoint set of files (§2),
> each reporting only into its own section of §7. The master agent owns the
> build files, the entrypoint, and the final integration pass (§8).
>
> **Spec:** [SPEC.md](SPEC.md). Read it. This document does not repeat it —
> it partitions it.
>
> **Started / integrated:** 2026-08-13 · **Status:** all phases complete through integration. Builds, tests green, jar in `dist/`. **Not play-verified** — see §7.M.

---

## 0. Vocabulary — read this first

| Term | Means | In code |
|---|---|---|
| **Totem** | The offhand item. A rechargeable totem of undying that houses a Kamuy | `Totem`, `HostType.TOTEM` |
| **Kamu** | A spirit dropped by a boss. The **collectible unit** | `Kamu`, `KamuCatalog` |
| **Kamuy** | The **named power in a player's totem**. One per player, unique, remembers its history | `Kamuy` |
| **Construct** | The host-agnostic arrangement a resolver evaluates. Never player-facing | `Construct` |
| **Sigil** | The item that summons a boss | `Sigil` |
| **Tier** | A kamu's strength, 1–3. Raised only by fusion | `Slot.tier` |
| **Fusion** | Two identical kamu of the same tier become one of the next | `Fusion` |
| **Imbue** | Slotting a kamu into a totem. Costs cobblestone | `ImbueCost` |

Kamu is both singular and plural. *One kamu, three kamu.*

> **⚠ `Kamu` and `Kamuy` are DIFFERENT CLASSES and the one-letter difference is
> load-bearing.** `Kamu` = a catalog definition of a collectible spirit.
> `Slot` = one owned kamu at a tier. `Kamuy` = the named, per-player,
> persistent wrapper around a `Construct`, with a journal. Kamu are fungible
> loot; a Kamuy is a character. Read a type name twice before you use it.

> **⚠ The word "component" is reserved for Minecraft.** In this codebase
> `component` always means a `DataComponent` — `minecraft:custom_data`,
> `DataComponentPatch`, `shop.json`'s `components` block. A game-design
> component is a **kamu**. Never name a class `Component`; the fabric module
> imports `net.minecraft.network.chat.Component` almost everywhere and the
> collision would force fully-qualified names across the module.

---

## 1. How this is partitioned, and why

| Agent | Owns | Can compile? | Risk |
|---|---|---|---|
| **A — Core** | `core/` — the whole pure-Java engine and its tests | **Yes**, with plain `javac`/`java`. No Gradle, no Minecraft | Low. Fully self-verifying |
| **B — Totem** | The totem host: item, charges, damage funnel, GUIs | **No** | **High** — every §16 verify item lives here |
| **C — Boss + Quest** | The boss host, sigils, the daily chain | **No** | Medium |
| **Master** | Build files, entrypoint, config, shared helpers, integration | Yes | — |

**Three rules that make the parallelism safe:**

1. **The contract in §3 is frozen.** Agent A implements it exactly. Agents B and
   C code against it as written, without waiting for A. If A believes a
   signature is wrong, A **does not change it** — A logs it in §7 and the master
   reconciles during integration.
2. **Only the master runs Gradle.** Three concurrent Gradle daemons on one
   project will fight over `.gradle/*.lock` and produce failures that look like
   code bugs. Agent A verifies with `javac` directly; B and C do not compile at
   all and are verified during §8.
3. **No agent edits a file it does not own.** The ownership table in §2 is
   exhaustive. A file not listed is the master's.

**Note on build order vs. ship order.** [SPEC.md §20](SPEC.md) stages the
*releases* totem → boss → quest, with `cobblebending` and `dailyquests` retiring
at different times. That is unchanged. This plan builds all three hosts
concurrently; the staged retirement still gates on play-verification. **No agent
deletes or edits `cobblebending/` or `dailyquests/`.**

---

## 2. File ownership — exhaustive

### Agent A owns

```
kamutotems/core/src/main/java/kamutotems/core/
    Category.java  Rarity.java  HostType.java
    Kamu.java  KamuCatalog.java  Slot.java
    Fusion.java  FusionResult.java  ImbueCost.java
    Construct.java  Kamuy.java  KamuyName.java
    EventMatcher.java  Context.java
    Effect.java  ResolutionResult.java  Resolver.java
    ReactionRule.java  ReactionOutcome.java  ReactionEngine.java
    QuestSegment.java  QuestChain.java  QuestProgress.java
    StreakState.java  Streak.java
    BossRoll.java
    DiscoveryTier.java  DiscoveryBook.java
kamutotems/core/src/test/java/kamutotems/core/
    KamuTotemsTest.java
```

### Agent B owns

```
kamutotems/fabric/src/main/java/kamutotems/
    TotemHost.java        — register(), registerCommands()
    Totem.java            — stack construction, custom_data read/write
    TotemCharges.java     — damage, dormancy, the anvil-combine guard
    DamageFunnel.java     — the ALLOW_DAMAGE funnel + re-entrancy guard
    SlotMenu.java         — sgui three-slot panel
    BookMenu.java         — sgui discovery book
```

### Agent C owns

```
kamutotems/fabric/src/main/java/kamutotems/
    BossHost.java         — register(), registerCommands()
    Boss.java             — spawn, attributes, boss bar, kamu naming
    BossDrops.java        — the kamu drop
    Sigil.java            — sigil item, summon, refund
    QuestHost.java        — register(), registerCommands()
    DailyChain.java       — chain binding, progress persistence
    TurnIn.java           — item turn-in handling
```

### Master owns (do not touch)

```
kamutotems/build.gradle.kts       settings.gradle.kts       gradle.properties
kamutotems/core/build.gradle.kts  kamutotems/fabric/build.gradle.kts
kamutotems/fabric/src/main/java/kamutotems/
    KamuTotemsMod.java     KamuTotemsConfig.java     Chime.java
    EffectsMc.java         Persist.java
kamutotems/fabric/src/main/resources/
    fabric.mod.json        components.json           reactions.json
kamutotems/SPEC.md        kamutotems/PLAN.md (§7 excepted)
```

---

## 3. THE FROZEN CONTRACT

Everything below is settled. Agent A implements it verbatim. Agents B and C call
it as written. Package `kamutotems.core` throughout; **no `net.minecraft.*`
anywhere in this package** — Minecraft things are referred to by **string id**
(`"minecraft:zombie"`), exactly as `bounties` and `chatdonkey` do.

> **Deviation from [SPEC.md §13](SPEC.md), deliberate:** there is no
> `EffectRegistry` in core. The resolver *emits* an ordered `List<Effect>`;
> translating an effect id into actual Minecraft behaviour is `EffectsMc`'s job
> on the fabric side. This keeps core provably pure and removes a layer of
> indirection that buys nothing. The master will reconcile SPEC §13.

### 3.1 Enums and the kamu model

```java
public enum Category { ACTION, ELEMENT, BEHAVIOUR, TRIGGER }
public enum Rarity   { COMMON, UNCOMMON, RARE }
public enum HostType { TOTEM, BOSS, QUEST }

public record Kamu(
        String id,                      // "fire"
        String displayName,             // "Ember"
        Category category,
        Rarity rarity,
        int complexity,
        String effectId,                // dispatch key for EffectsMc
        Map<String, Double> parameters, // never null; may be empty
        Set<String> tags,
        Set<HostType> allowedHosts) {}

public final class KamuCatalog {
    public KamuCatalog(List<Kamu> kamu);
    public static KamuCatalog defaults();   // exactly the 15 of SPEC §5.5
    public Kamu get(String id);             // null if unknown — not an exception
    public List<Kamu> byCategory(Category c);
    public List<Kamu> all();
    /** Element + behaviour only — the 9 a boss may carry. SPEC §7.1. */
    public List<Kamu> bossPool();
}

/** An OWNED kamu: a definition plus a tier. Tier is 1..3. SPEC §5.7. */
public record Slot(String kamuId, int tier) {
    public static final int MAX_TIER = 3;
}
```

> **`Kamu` is the definition; `Slot` is the instance.** `fire` at tier 3 is the
> same kamu with a bigger multiplier — not a separate id. The pool stays 15 and
> the reaction table stays 6 no matter how high tiers go.

### 3.2 Construct, matcher, context

```java
public record Construct(
        HostType host,
        List<Slot> slots,        // size 3 exactly; null entries mean empty
        EventMatcher trigger) {  // nullable; null means "on_hit"
    public int complexity(KamuCatalog catalog);
}

/** The named power in ONE player's totem. A Construct plus identity. SPEC §5.3.
 *  Bosses and quests have Constructs; only a player has a Kamuy. */
public record Kamuy(
        String name,             // null until first imbue → "an unnamed spirit"
        Construct construct,
        String bornDateKey,      // ISO date it was first named
        int kamuDrunk,           // total kamu ever imbued
        int saves,               // deaths prevented
        int bossesSlain) {
    public Kamuy withName(String sanitised);
    public Kamuy withConstruct(Construct c);
    /** Journal lines for the totem's lore. Biography, not a stat block. */
    public List<String> journal();
}

public final class KamuyName {
    /** Hostile-input rules from chatdonkey (SUITE_AUDIT §4.4): strip § WITH its
     *  code letter, control chars → spaces, collapse whitespace, cap length.
     *  Returns null if nothing usable survives — never a dropped guard. */
    public static String sanitise(String raw, int maxLength);
}

/** SPEC §4 — the half the quest host uses and the totem host barely touches. */
public record EventMatcher(
        String eventId,                  // "entity_killed" | "item_turned_in" | "block_scanned"
                                         // | "player_damaged" | "player_dealt_damage"
        Map<String, String> predicate,   // e.g. {"entity":"minecraft:zombie"}
        int requiredCount) {
    /** True when every predicate key is present in event and equal. */
    public boolean matches(Map<String, String> event);
}

public record Context(
        String sourceId,                 // "player:<uuid>" or an entity id
        String targetId,                 // nullable
        Set<String> targetTags,          // "wet", "burning", "frozen", "undead", ...
        Set<String> environmentTags,     // "raining", "night", "nether", ...
        List<String> previousEffects,    // effect ids already applied this resolution
        long seed) {}
```

### 3.3 Effects and resolution

```java
public record Effect(
        String effectId,
        String targetId,                 // nullable — null means "the construct's target"
        Map<String, Double> parameters) {}

public record ResolutionResult(
        boolean valid,
        List<String> faults,             // PLAYER-FACING PROSE. See below
        List<Effect> effects,            // ordered
        List<String> reactions,          // fired ReactionRule ids
        List<String> discoveries) {}     // newly-seen combination keys

public final class Resolver {
    public Resolver(KamuCatalog catalog, ReactionEngine reactions);
    /** Deterministic: same construct + context + seed ⇒ identical result. */
    public ResolutionResult resolve(Construct construct, Context context, long seed);
}
```

**Faults are prose a player reads.** Never `"NPE at slot 2"`, never
`"INVALID_SLOT"`. The required fault cases:

| Condition | Fault text |
|---|---|
| All slots empty | `"Nothing is slotted. Add a kamu to begin."` |
| Unknown kamu id | `"One of these spirits isn't something this totem recognises."` |
| Kamu not allowed on host | `"<Name> can't be used here."` |
| Trigger in a non-trigger slot with no action | `"This needs something to set it off. Try adding a trigger."` |

**Tier scaling happens here, in the resolver.** Each `Slot`'s tier multiplies
the emitted `Effect`'s parameters by `tierMultiplier[tier-1]`, default
`{1.0, 1.6, 2.5}`. Doing it in core keeps it pure, deterministic and testable —
never scatter tier maths through the fabric layer.

### 3.3b Fusion and costs (SPEC §5.7, §5.8)

```java
public record FusionResult(boolean ok, Slot output, String message) {}

public final class Fusion {
    /** Same kamuId AND same tier AND tier < MAX_TIER. Both inputs consumed. */
    public static FusionResult fuse(Slot a, Slot b);
}

public final class ImbueCost {
    /** Cobblestone to slot a kamu in. SPEC §5.8 defaults: 32 / 96 / 256. */
    public static int imbue(int tier, int[] table);
    /** Cobblestone to take it back out. Defaults: 16 / 48 / 128. */
    public static int remove(int tier, int[] table);
}
```

`Fusion.fuse` **never throws and never partially consumes.** It returns
`ok = false` with a player-facing `message` for: different kamu, different
tiers, already at tier 3. The caller consumes the inputs only when `ok` is true.

### 3.4 Reactions

```java
public record ReactionRule(
        String id,                       // "thermal_shock"
        String a,                        // effect id, or "tag:wet" to match a context tag
        String b,
        Set<String> conditions,          // context tags that must all be present; may be empty
        String outcomeEffectId,
        int priority,                    // higher wins
        boolean terminal) {}             // stop evaluating further rules

public record ReactionOutcome(
        List<Effect> effects,
        List<String> firedRuleIds,
        boolean truncated) {}            // true if the depth cap was hit

public final class ReactionEngine {
    public ReactionEngine(List<ReactionRule> rules, int depthCap);
    public static ReactionEngine defaults();     // the 6 rules of SPEC §5.6, depthCap 4
    /** ORDER-SIGNIFICANT: react(fire,frost) != react(frost,fire). SPEC §5.6. */
    public ReactionOutcome react(List<Effect> ordered, Context context);
}
```

> **The depth cap is a shipping requirement, not a nicety.** On exceeding it the
> engine **truncates and sets `truncated = true`**. It never throws. Half an
> applied effect is worse than a short one ([SPEC.md §17](SPEC.md)).

### 3.5 Quests and streaks

```java
public record QuestSegment(
        String kind,                     // "kill" | "turn_in" | "scan"
        EventMatcher matcher,
        String displayText) {}           // "Put down 6 skeletons"

public record QuestChain(String dateKey, List<QuestSegment> segments) {
    /** dateKey is ISO "2026-08-13". Derived, never stored. SPEC §8. */
    public static QuestChain forDate(String dateKey, long serverSeed, KamuCatalog catalog);
}

public record QuestProgress(String dateKey, List<Integer> counts) {
    public QuestProgress advance(int segmentIndex, int by, QuestChain chain);
    public boolean segmentComplete(int i, QuestChain chain);
    public boolean complete(QuestChain chain);
}

public record StreakState(
        int streak,
        String lastCompletedDateKey,        // nullable
        List<String> graceUsedDateKeys) {}  // missed days forgiven, for the rolling window

public final class Streak {
    /** Called when today's chain completes. */
    public static StreakState onComplete(StreakState prev, String todayKey);
    /** Called at rollover for a day NOT completed. Decides break vs. grace.
     *  SPEC §8: one missed day per rolling 7 is forgiven. */
    public static StreakState onMissedDay(StreakState prev, String missedKey);
    /** Reward curve. Higher base than the old 1/day, cap well above the old 3. */
    public static int rewardDiamonds(int streak, int base, int perDay, int cap);
}
```

**Streak edge cases Agent A must test:** rollover across a month boundary; a
clock that jumps backwards; a 40-day streak returning after exactly 1 missed
day (survives) and after 2 in the same week (breaks); an unreadable prior state
(treated as a **grace day, never a break** — SPEC §17).

### 3.6 Boss rolls

```java
public record BossRoll(int tier, List<String> kamuIds) {
    /** Tier 1..4 → 1-2, 2, 3, 4 kamu, distinct, from catalog.bossPool(). */
    public static BossRoll forSeed(long seed, int tier, KamuCatalog catalog);
    /** The free daily. Same for every player on a given date. SPEC §7.1. */
    public static BossRoll forDate(String dateKey, long serverSeed, KamuCatalog catalog);
    /** One of THIS boss's own kamu. Never a pull from the global pool. SPEC §7.2.
     *  Always tier 1 in v1 — fusion is the only ladder. */
    public String dropKamu(long seed);
}
```

### 3.7 Discovery

```java
public enum DiscoveryTier { UNKNOWN, HINTED, KNOWN, SECRET }

public final class DiscoveryBook {
    public DiscoveryBook(Map<String, DiscoveryTier> initial);
    public DiscoveryTier tierOf(String key);          // UNKNOWN if absent
    /** Upgrades only, never downgrades. Returns true if the tier actually rose. */
    public boolean record(String key, DiscoveryTier tier);
    public Map<String, DiscoveryTier> snapshot();
}
```

### 3.8 Fabric-side contract (master-owned, frozen for B and C)

These exist for B and C to call. **The master writes them.** Do not create them.

```java
package kamutotems;

public final class EffectsMc {
    /** Translate resolved core effects into real Minecraft behaviour. */
    public static void apply(List<Effect> effects, ServerLevel level,
                             ServerPlayer source, Entity target);
}

public final class Chime {
    public static void play(ServerPlayer p, Holder.Reference<SoundEvent> sound,
                            float volume, float pitch);
}

public final class KamuTotemsConfig {
    public static JsonObject section(String name);   // "totem" | "boss" | "quest"
    public static int i(String section, String key, int fallback);
    public static double d(String section, String key, double fallback);
    public static boolean b(String section, String key, boolean fallback);
}

public final class Persist {
    /** Atomic debounced write: <name>.tmp then rename. Suite pattern. */
    public static void save(String relativePath, JsonObject data);
    public static JsonObject load(String relativePath);   // null if absent
}
```

**Each host exposes exactly these two static methods**, and nothing else is
called from outside:

```java
public static void register();                                       // event registration
public static void registerCommands(CommandDispatcher<CommandSourceStack> d);
```

`TotemHost`, `BossHost`, `QuestHost`. The master's `KamuTotemsMod` calls all
six. **No agent edits `KamuTotemsMod.java`.**

---

## 4. Phases

| Phase | Who | What | Gate |
|---|---|---|---|
| **0** | Master | Gradle scaffolding, `fabric.mod.json`, entrypoint, `EffectsMc`/`Chime`/`KamuTotemsConfig`/`Persist`, default JSON | Runs concurrently with agent work — B and C code against §3.8 as written |
| **1** | A, B, C | Implement owned files against §3 | Each agent's §7 checklist complete |
| **2** | Master | Integration: first `./gradlew build`, fix signature drift, wire commands | `MrPinoys_kamutotems-0.1.0.jar` in `dist/` |
| **3** | Master | Run the §16 verify list against the real jar, fix what it invalidates | SPEC §16 answers recorded ✅/❌ with dates |
| **4** | Master | Play-verification against [SPEC.md §18](SPEC.md) | Definition of done |

---

## 5. Rules every agent follows

1. **Do not run `gradle` / `gradlew`.** Ever. Only the master builds.
2. **Do not touch `git`.** No commits, no branches, no stashes.
3. **Do not edit files you do not own** (§2), including `SPEC.md`.
4. **Do not edit `cobblebending/` or `dailyquests/`.** They stay running until
   [SPEC.md §20](SPEC.md) retires them.
5. **Never invent a Minecraft API call.** Before writing any `net.minecraft.*`
   line, `grep` for the same call in `spiritwolves/src/` or `cobblebending/src/`
   and copy the shape. If it does not exist in either, write the call, mark it
   `// ⚠ UNVERIFIED` on the line above, and log it in §7. This suite targets
   **26.2**, which renamed a lot — see [SUITE_AUDIT.md §4.6](../SUITE_AUDIT.md).
6. **Report into your own §7 subsection only.** Replace your `<!-- AGENT-x -->`
   block. Never edit another agent's block; you will collide.
7. **When blocked, log and continue.** Write the blocker into §7, stub the piece
   with `// TODO(master):` and a one-line description, and move to the next
   item. Do not stop and wait.
8. **Finish everything you own.** A partially-implemented file with no §7 note
   is the one failure mode that wastes the integration pass.

### The 26.2 traps, condensed

| Wrong | Right |
|---|---|
| `ResourceLocation` | `Identifier` |
| `hasPermission(int)` | `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` |
| `TypedActionResult` | `InteractionResult` |
| `EntityType.DONKEY` | `EntityTypes.X` |
| `...animal.Wolf` | `...animal.wolf.Wolf` |
| interaction events "v1" | **v0** (`UseItemCallback`, `UseBlockCallback`) |
| `Repairable` in `world.item.component` | `world.item.enchantment` |

Custom items are **never registry entries** — they are vanilla items carrying
`minecraft:custom_data`. A vanilla client installs nothing. This is
non-negotiable.

---

## 6. Definition of done per agent

**Agent A** — `java kamutotems.core.KamuTotemsTest` prints `N passed, 0 failed`
with N ≥ 75, covering: determinism over 1000 iterations; `fire→frost` ≠
`frost→fire`; the depth cap truncating rather than hanging; every fault string
in §3.3; all streak edge cases in §3.5; `forDate` stability for the same date;
`dropKamu` only ever returning a kamu the boss actually carried; **tier
scaling applied in the resolver**; and every `Fusion` refusal case (different
kamu, different tier, already tier 3) returning `ok = false` **without
producing an output**; and `KamuyName.sanitise` stripping `§` together with its
code letter, capping length, and returning `null` when nothing usable survives.

**Agent B** — all six files complete, every `net.minecraft.*` call either
grep-matched to an existing suite call site or marked `// ⚠ UNVERIFIED` and
logged. The four §16 risks (totem strip, ALLOW_DEATH ordering, funnel
re-entrancy, anvil combine) each have an explicit code path with a comment
naming the risk. The slot panel charges cobblestone for imbue and remove, shows
every price before charging it, and consumes nothing on a refusal. The Kamuy
persists **per player, not per item** — destroying the totem and running
`/totem` returns it named, with its journal and slots intact.

**Agent C** — all seven files complete, same verification discipline. The free
daily boss and the daily chain both derive from `dateKey` and store nothing that
a restart could change. A sealed sigil is rolled and its lore rewritten within
one sweep interval; an unrolled sigil cannot summon; bosses drop tier 1 only.

---

## 7. Progress reports

> Each agent replaces **only** its own block. Append, don't rewrite history.

### 7.A — Core engine

<!-- AGENT-A: done -->

**Files written (all under `kamutotems/core/`)**

`src/main/java/kamutotems/core/`
- `Category.java`, `Rarity.java`, `HostType.java`
- `Kamu.java`, `KamuCatalog.java`, `Slot.java`
- `Fusion.java`, `FusionResult.java`, `ImbueCost.java`
- `Construct.java`, `Kamuy.java`, `KamuyName.java`
- `EventMatcher.java`, `Context.java`
- `Effect.java`, `ResolutionResult.java`, `Resolver.java`
- `ReactionRule.java`, `ReactionOutcome.java`, `ReactionEngine.java`
- `QuestSegment.java`, `QuestChain.java`, `QuestProgress.java`
- `StreakState.java`, `Streak.java`
- `BossRoll.java`
- `DiscoveryTier.java`, `DiscoveryBook.java`

`src/test/java/kamutotems/core/`
- `KamuTotemsTest.java`

**Verification**

```
cd "a:\MrPinoys Mods\kamutotems\core"
javac -d out src\main\java\kamutotems\core\*.java src\test\java\kamutotems\core\*.java
java -cp out kamutotems.core.KamuTotemsTest
110 passed, 0 failed
```

**Signature objections**

None. All signatures in §3 are implemented as written.

**Master notes**

- `KamuCatalog.defaults()` uses the exact 15 ids/categories/complexities from SPEC §5.5. `rarity`, `tags`, `parameters` and `allowedHosts` are not specified there; I chose sensible defaults (`allowedHosts` = `TOTEM` for actions/triggers, `TOTEM+BOSS` for elements/behaviours).
- `ReactionEngine.defaults()` uses the 6 rules from SPEC §5.6. I assigned `melt` an `outcomeEffectId` of `null` so the engine removes the frost and leaves the fire; all other reactions emit an `Effect` with their rule id as the `effectId`.
- `Resolver.discoveries` is set to the fired reaction rule ids because the resolver has no `DiscoveryBook` in its constructor. If the master wants true first-discoverer tracking, wire a `DiscoveryBook` in the fabric layer.
- `QuestChain.forDate()` uses a small hard-coded vanilla mob/item/block pool for the three segment kinds; this is enough to keep it deterministic and restart-proof.
- `Kamuy.withName()` uses `java.time.LocalDate.now()` to set `bornDateKey` only on first naming. It does not affect resolver determinism.
- Zero `net.minecraft.*`, Gson, logging or test framework imports in `core/`.

### 7.B — Totem host

<!-- AGENT-B: done -->

**Files written** (all under `kamutotems/fabric/src/main/java/kamutotems/`)

- `TotemHost.java` — `register()`/`registerCommands()`, per-player Kamuy persistence (`KamuyStore`), first-join/re-issue wiring, `/totem`, `/totem name`, `/kamuy`, and scan/use callbacks.
- `Totem.java` — totem stack construction, `custom_data` read/write, `DEATH_PROTECTION` strip, lore/journal rebuild.
- `TotemCharges.java` — `ServerLivingEntityEvents.ALLOW_DEATH` save/burn, charge count, anvil-combine guard, dormant nag.
- `DamageFunnel.java` — `ServerLivingEntityEvents.ALLOW_DAMAGE` hook, re-entrancy guard, resolver/effect wiring.
- `SlotMenu.java` — sgui slot/fuse/imbue/remove panel with cobblestone costs shown before charge.
- `BookMenu.java` — sgui discovery book showing `KamuCatalog` entries and `DiscoveryTier` state.

**`// ⚠ UNVERIFIED` calls left**

- `Totem.java`: `Items.TOTEM_OF_UNDYING` as the host item; `DataComponents.DEATH_PROTECTION` removal.
- `TotemCharges.java`: `AnvilMenu` slot indices and `Slot#set` shape; `SoundEvents.TOTEM_USE` as a `Holder<SoundEvent>` for `Chime.play`.
- `DamageFunnel.java`: `ServerLivingEntityEvents.ALLOW_DAMAGE` lambda shape; `Resolver`/`ReactionEngine`/`KamuCatalog.defaults()` wiring; `EffectsMc.apply` effect-id coverage; `entity.isOnFire()`/`isInWater()`/`isUnderWater()`/`isFreezing()`; `level.dimension().equals(Level.NETHER)`.
- `TotemHost.java`: `UseBlockCallback` `BlockHitResult` parameter and `InteractionResult.CONSUME`; `KamuyName.sanitise`/`Kamuy` field/accessor shapes; `KamuyStore` NBT read/write; `LocalDate.now(ZoneOffset.UTC)` for `bornDateKey`.
- `SlotMenu.java`: sgui 2.1.0+26.2 slot-click semantics; `KamuTotemsConfig` cost keys; `ImbueCost`/`Fusion` core call shapes; `GuiElementBuilder.setLore` etc.
- `BookMenu.java`: `Persist.load` discovery JSON shape; `DiscoveryBook` constructor and `KamuCatalog.all()`.

**`// TODO(master):` left**

- Replace the `TotemHost.warn()` stub with the mod's `Logger`.
- Align `Totem.createKamu()` host item with `BossDrops` so loose kamu are identifiable across the mod.
- Wire `TotemHost.onUseBlock()` to `QuestHost` for scan-segment progress.
- Reconcile against Agent A's actual `KamuCatalog`, `ImbueCost`, `Fusion`, `KamuyName`, and `Kamuy` record when the core is available.

**Confidence on the four §16 risks**

1. **TOTEM STRIP (`DEATH_PROTECTION` removal)** — low. The `stack.remove(DataComponents.DEATH_PROTECTION)` path is present and marked, but I cannot confirm it works without a 26.2 compile/runtime.
2. **ALLOW_DEATH ordering** — medium. A real `minecraft:totem_of_undying` in the main hand is explicitly allowed to let vanilla consume it before the offhand Kamu Totem is considered.
3. **FUNNEL RE-ENTRANCY** — medium. A per-UUID `resolving` set guards re-entry, but the exact `ALLOW_DAMAGE` signature/behaviour is unverified.
4. **ANVIL COMBINE** — low. The `AnvilMenu` slot-clearing guard is present, but the slot indices and `Slot#set` semantics are unverified.

### 7.C — Boss + Quest hosts

<!-- AGENT-C COMPLETED -->

**Files written** (all under `kamutotems/fabric/src/main/java/kamutotems/`):
- `BossHost.java`
- `Boss.java`
- `BossDrops.java`
- `Sigil.java`
- `QuestHost.java`
- `DailyChain.java`
- `TurnIn.java`

**Public APIs** (master wires these):
- `BossHost.register()` and `BossHost.registerCommands(CommandDispatcher<CommandSourceStack>)`
- `QuestHost.register()` and `QuestHost.registerCommands(CommandDispatcher<CommandSourceStack>)`
- `QuestHost.onScan(ServerPlayer, Identifier)` — exposed for the totem host to call for scan segments.

**⚠ UNVERIFIED calls** (all against the 26.2 jar — master should verify/fix during the first `./gradlew build`):
- `Boss.java`: `Entity.snapTo(...)`; `new ServerBossEvent(Component, BossEvent.BossBarColor, BossEvent.BossBarOverlay)`.
- `BossDrops.java`: `new ItemEntity(ServerLevel, double, double, double, ItemStack)`.
- `Sigil.java`: `BuiltInRegistries.SOUND_EVENT.getHolderOrThrow(Identifier)`; `Chime.play(...)`.
- `BossHost.java`: `ServerEntityEvents.ENTITY_LOAD` registration; `Entity.addTag(String)`, `Entity.entityTags()`, `Entity.removeTag(String)`; `MinecraftServer.getWorldData().worldGenOptions().seed()`; `ServerLevel.getRandom().nextLong()`; `BossRoll.forDate(...)`.
- `QuestHost.java`: `MinecraftServer.getWorldData().worldGenOptions().seed()`.
- `DailyChain.java`: `Chime.play(...)` (two call sites).

**TODO(master):** None. The gaps are the unverified calls listed above.

**Notes for the master:**
- `BossDrops` hard-codes `minecraft:amethyst_shard` as the kamu host item, with a config override key `boss.kamu_item`. Keep this in sync with the totem host's `isKamu` / `createKamu` logic.
- `Boss.java` spawns `minecraft:zombie` by default, overridable via `KamuTotemsConfig.section("boss").get("mob")`.
- `BossHost` persists only free-claim dates and sigil counters in `boss_state.json`. Live bosses are not persisted; an orphan on reload reverts to a vanilla mob.
- `DailyChain` persists streaks and today's progress in `daily_state.json`, but progress is only written when the chain completes (a lost write therefore rolls back, never forward).
- `KamuTotemsConfig` keys used by C: `quest.rollover_hour_utc`, `quest.announce_on_join`, `quest.reward_base`, `quest.reward_per_day`, `quest.reward_cap`, `boss.no_summon_radius`, `boss.kamu_item`, `boss.mob`.
- The free daily boss and the daily chain both use `todayKey()` derived from the same UTC rollover hour.


### 7.M — Master / integration

<!-- MASTER: phases 0-3 complete 2026-08-13 -->

**Result: `./gradlew build` succeeds. `MrPinoys_kamutotems-0.1.0.jar` (221 KB) is
in `dist/`. Core: 113 passed, 0 failed. Zero Mixins, zero cross-mod imports.**

**Phase 0 written** (master-owned): `settings.gradle.kts`, `gradle.properties`,
`core/build.gradle.kts` (deliberately empty deps), `fabric/build.gradle.kts`
(sgui + `dist` depending on `jar`, never `remapJar`), `fabric.mod.json`,
`KamuTotemsMod`, `KamuTotemsConfig`, `Persist`, `Chime`, `EffectsMc`. Gradle
wrapper copied from `spiritwolves`.

**Contract drift — one deviation accepted.** Agent A reported no signature
objections and implemented §3 verbatim. `Chime.play` needed a **second
overload**: §3.8 specified `Holder<SoundEvent>`, but `SoundEvents` is not a
uniform type in 26.2 and Agent B passed a bare `SoundEvent`. Both overloads now
exist so no caller has to know which kind a sound is.

**A real bug in the core, found by reading rather than by tests.**
`Resolver` faulted on `hasTrigger && !hasAction`, which rejected the perfectly
valid **`on_kill` + `leech`** shape — no action, so the player's own attack is
the action (SPEC §5.5), and the headline artifact of SPEC §26. The core's 110
assertions were all green because the only test exercised a *trigger-only*
construct, which must still fault. Fixed to `hasTrigger && !hasVerb`, and two
regression tests added (110 → 113). **This is the class of bug parallel agents
produce: each half was locally correct and the spec sentence that reconciled
them was in neither agent's file.**

**Cross-agent drift reconciled:**

| Drift | Resolution |
|---|---|
| Kamu item built twice — `Totem.createKamu` made **paper**, `BossDrops` made an **amethyst shard** | Single implementation in `Totem.createKamu`, config-driven, with tier numeral + colour. `BossDrops` delegates. NBT keys already matched, so this was cosmetic-but-real: the two would not have stacked or looked alike |
| Duplicate config key `boss.kamu_item` vs `totem.kamu_item` | One key, `totem.kamu_item` |
| `QuestHost.onScan` was exposed but never called — **scan segments were dead** | `TotemHost.onUseBlock` now resolves the block id and forwards. The one deliberate cross-host call |
| `TotemHost.warn()` logging stub | Wired to `KamuTotemsMod.LOG` |
| `TotemCharges.tick()` / `tick(MinecraftServer)` arity mismatch | Caller fixed |

**26 compile errors fixed**, all in the fabric layer, all API-shape rather than
logic — the full list is in SPEC §16 under "Other 26.2 corrections". Agents B and
C could not compile by design, and the `// ⚠ UNVERIFIED` discipline made every
one of them a worklist entry instead of a hunt. That trade paid.

**All 20 `UNVERIFIED` markers and all 4 `TODO(master)` items resolved.** Markers
that compilation settled were replaced with what was verified; the three that
genuinely need a running server are now labelled `PLAY-VERIFY` and point at §18.

**Still open — this is what "not play-verified" means:**

1. **§16.1, the one that can hurt.** The `DEATH_PROTECTION` strip compiles, but
   only a live death proves vanilla does not eat the stack. If it does, the
   player loses their totem *and* every kamu in it. Test before anything else;
   `nautilus_shell` is the fallback and costs only theme.
2. Every §18 box. Nothing has been run in a game.
3. Balance is entirely first guesses: cobblestone costs, tier multipliers
   (1.0 / 1.6 / 2.5), boss health and damage multipliers, streak rewards.
4. `EffectsMc` covers all 15 kamu ids and all 5 reaction outcomes, but the
   *feel* of each — `bolt` vs `burst`, chain range, leech ratio — is untuned.

---

## 8. Integration checklist (master, phase 2)

- [x] Phase 0 files exist and compile standalone
- [x] `./gradlew build` — expect signature drift between §3 as written and as
      implemented; reconcile in favour of **A's implementation** where A logged
      a reasoned objection, otherwise in favour of §3
- [x] `core/build.gradle.kts` is empty; confirm a stray `import net.minecraft.*`
      in core fails the build
- [x] No class anywhere in core is named `Component` (§0)
- [x] `KamuTotemsMod` calls all six `register`/`registerCommands` methods
- [x] Every `// ⚠ UNVERIFIED` line resolved against the 26.2 jar
- [x] Every `// TODO(master):` resolved
- [x] `EffectsMc` handles every effect id emitted by `KamuCatalog.defaults()`
      and every `outcomeEffectId` in `ReactionEngine.defaults()` — **this is the
      most likely gap**, since A defines the ids and the master implements them
- [x] `EffectsMc` honours the tier multiplier already baked into each `Effect`'s
      parameters — it must not scale a second time
- [x] `shop.json` guidance ships sigils only: **no kamu, no totem for sale** — in [README.md](README.md)
- [ ] Cobblestone imbue/remove costs are live **in the same release that retires
      `cobblebending`** — that mod is the suite's only cobble sink (SPEC §20)
- [x] Run SPEC §16 verify list; record ✅/❌ + date in SPEC.md
- [x] `MrPinoys_kamutotems-0.1.0.jar` lands in `dist/`
- [x] Zero Mixins; zero compile-time deps on another mod

---

## Appendix — Handoff prompts

Copy one per agent. Each is self-contained.

---

### AGENT A — Core engine

```
You are implementing the pure-Java core engine for a Minecraft Fabric mod called
"Kamu Totems" (mod id: kamutotems), in the repo at a:\MrPinoys Mods.

VOCABULARY: a "Kamu" is a spirit a player slots into a Totem — it is the
collectible unit of this mod, and the class is called Kamu. NEVER name a class
"Component"; in this codebase "component" always means a Minecraft DataComponent
and the name collides with net.minecraft.network.chat.Component.

READ FIRST, in this order:
  1. a:\MrPinoys Mods\kamutotems\PLAN.md — especially §0 (vocabulary), §2 (your
     files), §3 (the frozen contract you implement verbatim), §5 (rules), §6
     (your definition of done). §3 IS YOUR SPECIFICATION.
  2. a:\MrPinoys Mods\kamutotems\SPEC.md — §4, §5, §7.1, §7.2, §8, §9, §17.
  3. a:\MrPinoys Mods\bounties\core\src\  and
     a:\MrPinoys Mods\chatdonkey\core\src\ — for house style. Note especially
     that their tests are a plain main() printing "N passed, 0 failed" with no
     test framework, and that core code refers to Minecraft things as STRINGS.

YOUR JOB: implement every file listed under "Agent A owns" in PLAN.md §2,
exactly matching the signatures in PLAN.md §3.

HARD CONSTRAINTS:
  - Package kamutotems.core. ZERO imports of net.minecraft.*. Zero Gson. Zero
    logging frameworks. Zero test frameworks. java.* only.
  - Implement §3's signatures VERBATIM. If you believe one is wrong, DO NOT
    change it — implement it as written, and log your objection in PLAN.md §7.A.
    The master reconciles later.
  - KamuCatalog.defaults() must be exactly the 15 kamu of SPEC §5.5, with the
    ids, categories and complexities given there.
  - ReactionEngine.defaults() must be exactly the 6 rules of SPEC §5.6, and
    ORDER MUST MATTER: fire→frost and frost→fire produce different results.
  - TIERS (SPEC §5.7): a Kamu is a DEFINITION; a Slot is an OWNED INSTANCE =
    kamuId + tier (1..3). "fire" at tier 3 is the same kamu with a bigger
    multiplier, NOT a separate id. Tier scaling is applied IN THE RESOLVER
    (multipliers 1.0 / 1.6 / 2.5) so it stays pure and testable.
  - FUSION (SPEC §5.7): Fusion.fuse(a, b) succeeds only when both slots have the
    same kamuId AND the same tier AND tier < 3. It NEVER throws and NEVER
    returns a partial result — on refusal it returns ok=false with a
    player-facing message and no output. The caller decides what to consume.
  - COSTS (SPEC §5.8): ImbueCost.imbue / .remove are pure lookups into a
    config-supplied int[]. Defaults 32/96/256 and 16/48/128.
  - KAMU vs KAMUY (SPEC §5.3) — DIFFERENT CLASSES, one letter apart, and you
    will write both in the same file. Kamu = a catalog definition of a
    collectible. Slot = one owned kamu at a tier. Kamuy = the NAMED, per-player
    wrapper around a Construct with a journal. Bosses and quests have
    Constructs; only a player has a Kamuy.
  - KamuyName.sanitise treats the name as HOSTILE INPUT: strip § together with
    the code letter that follows it, control chars to spaces, collapse
    whitespace, cap length. Return null if nothing usable survives — the caller
    falls back to "an unnamed spirit". Test the § case explicitly.
  - The reaction depth cap TRUNCATES and sets truncated=true. It never throws.
  - Resolver faults are player-facing prose. The exact required strings are in
    PLAN.md §3.3.
  - Determinism is the point: resolve(construct, context, seed) must be
    byte-identical across runs. No HashMap iteration order dependence, no
    System.currentTimeMillis, no unseeded Random.

VERIFY YOUR OWN WORK — you are the only agent who can:
    cd "a:\MrPinoys Mods\kamutotems\core"
    javac -d out (all your .java files under src/main and src/test)
    java -cp out kamutotems.core.KamuTotemsTest
  It must print "N passed, 0 failed" with N >= 60. Cover every case listed in
  PLAN.md §6 under "Agent A".

DO NOT: run gradle or gradlew (the master owns all builds); touch git; edit any
file outside kamutotems/core/ except your own §7.A block in PLAN.md; edit
SPEC.md; edit cobblebending/ or dailyquests/.

WHEN DONE: replace the "<!-- AGENT-A ... -->" block in PLAN.md §7.A with: files
written, final test count, any signature you think is wrong and why, and
anything the master must know. Do not edit §7.B, §7.C or §7.M.
```

---

### AGENT B — Totem host

```
You are implementing the "totem" host of a Minecraft Fabric server-side mod
called "Kamu Totems" (mod id: kamutotems), in the repo at a:\MrPinoys Mods.
Target: Minecraft 26.2, Fabric, JDK 25, server-side only, vanilla clients
install nothing.

VOCABULARY: the player wears a Kamu Totem in the offhand. Spirits called Kamu
slot into it and modify whatever attack the player makes. NEVER name a class
"Component"; in this codebase "component" always means a Minecraft DataComponent
and the name collides with net.minecraft.network.chat.Component.

READ FIRST, in this order:
  1. a:\MrPinoys Mods\kamutotems\PLAN.md — §0 (vocabulary), §2 (your files), §3
     (the core API you CALL — someone else implements it, code against it as
     written), §3.8 (the fabric helpers the master provides — code against them,
     DO NOT create them), §5 (rules), §6 (your definition of done).
  2. a:\MrPinoys Mods\kamutotems\SPEC.md — §6 IS YOUR SPEC. Also read §1
     (especially the load-bearing rule), §10, §12, §16, §17.
  3. a:\MrPinoys Mods\spiritwolves\src\ — the death-save via
     ServerLivingEntityEvents.ALLOW_DEATH and the anvil/max_damage/Repairable
     charge mechanic are ALREADY SOLVED THERE. Copy the call shapes.
  4. a:\MrPinoys Mods\cobblebending\src\ — custom_data read/write on a stack.
  5. a:\MrPinoys Mods\SUITE_AUDIT.md §4.6 — the 26.2 API changes. This suite is
     on 26.2 and most tutorials online are wrong for it.

YOUR JOB: write the six files under "Agent B owns" in PLAN.md §2.

THE FOUR THINGS THAT MATTER MOST (SPEC §16 — each can break the mod):
  1. TOTEM STRIP. The host item is minecraft:totem_of_undying, and vanilla's
     DEATH_PROTECTION component must be STRIPPED or vanilla will consume the
     stack on death and destroy the player's totem and every kamu in it. Write
     the strip, comment it "// ⚠ SPEC §16.1", and if you cannot confirm it
     works, log it in PLAN.md §7.B.
  2. ALLOW_DEATH ORDERING. A player with a real totem of undying in the main
     hand and a Kamu Totem in the offhand must burn only ONE. Handle it
     explicitly.
  3. FUNNEL RE-ENTRANCY. DamageFunnel hooks
     ServerLivingEntityEvents.ALLOW_DAMAGE. A construct's own effect deals
     damage, which re-enters the same hook. YOU MUST WRITE A RE-ENTRANCY GUARD
     (a per-player "currently resolving" flag). This is the most likely infinite
     loop in the whole mod.
  4. ANVIL COMBINE. Two Kamu Totems on a vanilla anvil repair each other for
     free and silently discard one kamu set. Block it explicitly.

KEY DESIGN RULE YOU MUST NOT BREAK (SPEC §1): at 0 charges the totem goes
DORMANT — no save, no attack modification, but KAMU ARE NEVER LOST and the
player gets no debuff. A dormant totem makes the player exactly a vanilla
player, never worse than one.

THE KAMUY (SPEC §5.3) — this is the mod's attachment mechanic, treat it as
first-class, not decoration:
  - KAMU and KAMUY are DIFFERENT THINGS, one letter apart. A Kamu is a
    collectible a boss dropped. The KAMUY is the NAMED power in the player's
    totem — one per player, unique to them, with a history. You will write both
    words in the same file; read them twice.
  - THE KAMUY IS BOUND TO THE PLAYER, NOT THE ITEM. Persist it server-side per
    player (the spiritwolves PlayerWolfRegistry .dat-per-player pattern is the
    reference — read it). If a player loses or destroys their totem, /totem
    re-issues one and their Kamuy comes back NAMED, with its journal and its
    slots intact. Losing an item must never cost a relationship.
  - Prompt the player to name it on FIRST IMBUE, not on first join — an empty
    totem is not yet anything. Until then it is "an unnamed spirit".
  - Run every name through kamutotems.core.KamuyName.sanitise. Do not write your
    own sanitiser and do not skip it: the name is rendered into item lore.
  - The totem's lore IS the journal. Three or four lines, written in the Kamuy's
    voice, not a stat block: "Ember-of-Nine-Winters. Forged 14 days ago. Has
    drunk from 31 spirits. Pulled you back from death 7 times." Read
    spiritwolves' living-journal implementation first — it is the reference and
    it works because it reads like a biography.
  - Advance the counters: kamuDrunk on imbue, saves on a death save. (bossesSlain
    is agent C's to advance; just make sure the field round-trips.)
  - A corrupt Kamuy record is renamed .dat.corrupt, skipped, and THE SERVER STILL
    BOOTS. If the record is missing but the totem has slots, rebuild an UNNAMED
    Kamuy from those slots — never delete a build to fix a name.

CHARGES: max_damage 16, one charge = one damage point, vanilla anvil restores 4
charges per diamond. Charges burn on DEATH ONLY, never on ability use.

THE SLOT PANEL (SPEC §6.5) does three jobs — slot, fuse, and read:
  - IMBUING AND REMOVING A KAMU COST COBBLESTONE, scaled by tier (SPEC §5.8:
    imbue 32/96/256, remove 16/48/128 — all config). Removing RETURNS THE KAMU
    INTACT; the player is paying to change their mind, not destroying anything.
    THIS COST IS LOAD-BEARING: it is how this mod inherits cobblebending's job as
    the suite's only cobblestone sink when that mod retires.
  - Copy the inventory-cobblestone counting and consuming pattern from
    cobblebending/src/main/java/cobblebending/Ammo.java. Do not reinvent it.
    Only the main 36 inventory slots are ever read (suite-wide rule).
  - EVERY PRICE IS SHOWN BEFORE IT IS CHARGED, in the hover text. A player must
    never discover a price by paying it.
  - If the player cannot afford it: refuse, show a plain message, CONSUME
    NOTHING. Rejections are return values, not exceptions.
  - FUSION: two identical kamu of the same tier fuse into one of the next tier
    (t1+t1=t2, t2+t2=t3, cap 3). Call kamutotems.core.Fusion.fuse — DO NOT
    reimplement the rules. Consume the two inputs ONLY when it returns ok=true.
    Ordering matters for crash safety: on a crash mid-fusion both inputs must
    survive and no output exist. Never the reverse.
  - Ordering for imbue: take the cobblestone FIRST, then move the kamu. A crash
    then costs the player cobble rather than duplicating a kamu.

NEVER INVENT A MINECRAFT API CALL. Before writing any net.minecraft.* line, grep
spiritwolves/src and cobblebending/src for the same call and copy its shape. If
it appears in neither, write it, put "// ⚠ UNVERIFIED" on the line above, and
list it in PLAN.md §7.B. The master compiles and fixes these.

DO NOT: run gradle or gradlew (you cannot compile — that is expected and fine,
the master does it); touch git; create or edit KamuTotemsMod.java, EffectsMc,
Chime, KamuTotemsConfig, Persist, any build file, or any file owned by agents A
or C (PLAN.md §2); edit SPEC.md; edit cobblebending/ or dailyquests/.

COMMANDS YOU OWN: /totem (open panel, re-issue if missing),
/totem name <text> (name or rename the Kamuy), /kamuy (the journal).

EXPOSE EXACTLY: TotemHost.register() and
TotemHost.registerCommands(CommandDispatcher<CommandSourceStack>). The master
wires them into the entrypoint.

WHEN DONE: replace the "<!-- AGENT-B ... -->" block in PLAN.md §7.B with: files
written, every "⚠ UNVERIFIED" call you left, every "TODO(master):" you left, and
your confidence on each of the four risks above. Do not edit §7.A, §7.C or §7.M.
```

---

### AGENT C — Boss and Quest hosts

```
You are implementing the "boss" and "daily quest" hosts of a Minecraft Fabric
server-side mod called "Kamu Totems" (mod id: kamutotems), in the repo at
a:\MrPinoys Mods. Target: Minecraft 26.2, Fabric, JDK 25, server-side only,
vanilla clients install nothing.

VOCABULARY: spirits called Kamu are the collectible unit of this mod. A boss
CARRIES kamu and drops one on death. NEVER name a class "Component"; in this
codebase "component" always means a Minecraft DataComponent and the name
collides with net.minecraft.network.chat.Component.

READ FIRST, in this order:
  1. a:\MrPinoys Mods\kamutotems\PLAN.md — §0 (vocabulary), §2 (your files), §3
     (the core API you CALL — someone else implements it, code against it as
     written), §3.8 (the fabric helpers the master provides — code against them,
     DO NOT create them), §5 (rules), §6 (your definition of done).
  2. a:\MrPinoys Mods\kamutotems\SPEC.md — §7 and §8 ARE YOUR SPEC. Also read
     §5.5, §10, §12, §17.
  3. a:\MrPinoys Mods\dailyquests\src\ — YOU ARE REPLACING THIS MOD. Read
     Quests.java and DailyState.java closely: the date-derivation pattern
     (today's content derived from the date, never stored, restart-proof, no
     rollover reset) is the thing worth keeping and you must preserve it. Also
     read TurnIn.java. DO NOT EDIT THAT MOD — it keeps running until this ships.
  4. a:\MrPinoys Mods\bounties\src\ — deriving content from wall-clock time
     without storing it, and the announce pattern.
  5. a:\MrPinoys Mods\SUITE_AUDIT.md §4.6 — the 26.2 API changes. This suite is
     on 26.2 and most tutorials online are wrong for it.

YOUR JOB: write the seven files under "Agent C owns" in PLAN.md §2.

BOSS (SPEC §7):
  - A boss is a vanilla mob + a rolled set of kamu + boosted attributes + a
    boss bar THAT NAMES EVERY KAMU IT CARRIES. Naming them is not flavour — it
    is the mod's only tutorial, so a player learns what a kamu does before
    owning it. Do not skip it.
  - Tiers I-IV carry 1-2 / 2 / 3 / 4 kamu from KamuCatalog.bossPool().
  - THE DROP IS THE WHOLE ECONOMY: on death the boss drops ONE OF THE KAMU IT
    WAS ACTUALLY CARRYING (BossRoll.dropKamu), never a random pull from the
    global pool. ALWAYS AT TIER 1 — fusion is the only ladder (SPEC §5.7).
  - Tier I is free, once per player per day, and DERIVED FROM THE DATE so it is
    the same boss for every player that day. Its contents are PUBLIC.

SIGILS — THE BLIND PURCHASE (SPEC §7.3). This is the only thing the shop sells;
no kamu and no totem are ever purchasable.
  - shop.json sells an UNROLLED sigil: custom_data {"sigil": 2, "rolled": false},
    named "Sealed Sigil" with lore saying the spirits are unknown.
  - YOU roll it: sweep player inventories on a tick counter for unrolled sigils,
    call BossRoll.forSeed, write the roll into custom_data, and REWRITE THE
    ITEM'S LORE to name every kamu the boss will carry. The shop cannot do this
    — a per-purchase random roll is not expressible in a static components block,
    and coupling cobbleeconomy to this mod is forbidden.
  - Derive and STORE the seed: hash(playerUuid, purchaseCounter, worldSeed). A
    roll you cannot reproduce is a support ticket you cannot answer.
  - AN UNROLLED SIGIL CANNOT SUMMON. A rolled sigil is tradeable and still works
    for whoever holds it — do NOT make sigils soulbound; tradeability is what
    makes the blind purchase fair.
  - Unreadable sigil custom_data is treated as unrolled and RE-ROLLED. The player
    keeps a usable sigil; the alternative is a dead item they paid for.
  - Chosen failure directions (SPEC §17): a boss orphaned by a crash reverts to
    an ordinary killable vanilla mob; a boss despawned by shutdown or logout
    REFUNDS THE SIGIL; a kamu drop that won't fit drops at the player's feet
    and is logged, never deleted.

QUEST (SPEC §8):
  - Three LIGHT segments per day, derived from the date via QuestChain.forDate.
    Kinds: kill / turn_in / scan. The "scan" input verb is the player
    right-clicking a block while holding the totem — agent B owns the totem, so
    just expose a handler the master can wire.
  - The streak is the most valuable thing you are carrying over. Weekly grace
    (one missed day per rolling 7 forgiven), a higher base reward than the old
    1 diamond/day, a cap well above the old 3, and it is PUBLIC via /daily top.
  - Quest progress must roll BACK on a lost write, never forward. Unreadable
    streak state is treated as a GRACE DAY, never a break.
  - All streak and chain arithmetic lives in kamutotems.core (Streak,
    QuestChain, QuestProgress — PLAN.md §3.5). CALL IT. Do not reimplement it.

NEVER INVENT A MINECRAFT API CALL. Before writing any net.minecraft.* line, grep
spiritwolves/src, bounties/src and dailyquests/src for the same call and copy its
shape. If it appears in none, write it, put "// ⚠ UNVERIFIED" on the line above,
and list it in PLAN.md §7.C. The master compiles and fixes these.

DO NOT: run gradle or gradlew (you cannot compile — that is expected and fine,
the master does it); touch git; create or edit KamuTotemsMod.java, EffectsMc,
Chime, KamuTotemsConfig, Persist, any build file, or any file owned by agents A
or B (PLAN.md §2); edit SPEC.md; edit cobblebending/ or dailyquests/.

EXPOSE EXACTLY: BossHost.register(), BossHost.registerCommands(...),
QuestHost.register(), QuestHost.registerCommands(...), all taking
CommandDispatcher<CommandSourceStack>. The master wires them.

WHEN DONE: replace the "<!-- AGENT-C ... -->" block in PLAN.md §7.C with: files
written, every "⚠ UNVERIFIED" call you left, every "TODO(master):" you left, and
anything the master must know. Do not edit §7.A, §7.B or §7.M.
```
