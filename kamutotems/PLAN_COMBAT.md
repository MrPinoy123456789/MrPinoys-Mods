# PLAN_COMBAT — Combat identity, auras, and boss encounters

Implementation plan for [BRAINSTORM_COMBAT.md](BRAINSTORM_COMBAT.md).
Three agents work in parallel; the master stitches and compiles.

**Target:** Minecraft 26.2, Fabric, JDK 25, server-side only, vanilla clients
install nothing, ZERO Mixins (`ballot` holds the suite's only one).

**Read [BRAINSTORM_COMBAT.md](BRAINSTORM_COMBAT.md) before this file.** This plan
assumes its decisions and does not re-argue them.

---

## 1. Locked decisions

Do not relitigate these. If one looks wrong, implement it anyway and log the
objection in your §7 block.

| # | Decision | Source |
|---|---|---|
| L1 | The construct has **3 slots**: one DELIVERY, two MODIFIER. The WHEN/TRIGGER slot is gone. | §4.4 |
| L2 | Deliveries are **3**: `hit`, `echo`, `splash`. `echo` fires the effect **1 s (20 ticks) after** the hit. `reap` (on-kill) is cut — no vanilla term exists for it. | §4.4 |
| L3 | Catalog is **11 entries**: 7 kamu (`fire`, `ice`, `poison`, `wither`, `lightning`, `heal`, `absorption`), 3 deliveries (`hit`, `echo`, `splash`), and the `plain` modifier default. `hit` doubles as the delivery default. **Nothing is aura-only and nothing is construct-only** — all 7 kamu work in both systems. | §4.1, §9.4 |
| L4 | `bolt`, `burst`, `pierce`, `blink`, `knock`, `chain`, `frost`, `shock`, `venom`, `strike`, `leech`, `reap`, `on_hit`, `on_kill`, `on_hurt` are **removed as kamu**. Note `fire` survives as the id for Fire, and `bloom` is now an **aura**, not a kamu. | §4.4, §4.5 |
| L5 | Elements trade DoT against a secondary effect. Fire = highest DoT, **no** secondary. | §4.1 |
| L6 | `shatter` rekeys from `frost`+`knock` to **`ice`+`lightning`**. `tainted` rekeys from `leech`+`venom` to **`heal`+`poison`**. The `lightning`+`ice` conduction rule is dropped. | §6 |
| L7 | Auras are a **second face of the same totem**. No new item. `TotemCharges` governs both faces. | §9.8 |
| L8 | "Aura" names the whole passive system. An aura = **one AURA + one MODIFIER**. The four auras are `bloom`, `focus`, `momentum`, `rebuke`. **One active at a time.** | §9.3, §9.6 |
| L9 | **`bloom` pulses on an interval — for boons and banes alike, like a beacon.** Interval by tier: **30 s / 22 s / 15 s**. Tier buys frequency, not power. Every pulse gets a wind-up telegraph. | §9.3 |
| L10 | Polarity is a field on `Kamu`. Every non-`NONE` polarity is legal on every aura kind — Focus and Momentum release a radius pulse exactly like Bloom's (§L26), so there is no "bane on a self-only aura" case left to refuse. `NONE` is the only exclusion. | §9.4 |
| L11 | `focus` and `momentum` are **inverse charge meters** — Focus charges standing still, Momentum charges moving, both fill ~4–5 s / drain ~2 s. Superseded by L26 for what happens at full charge: it is a radius pulse, not a self-only continuous drip. | §9.3, §9.6 |
| L12 | Bosses **run the construct engine** and cast their carried kamu. Every special is telegraphed: particles → sound → payoff. | §5.1, §5.2 |
| L14 | Boss names come from a **config-driven template + word lists**. Mechanical ids stay stable in code. | §5.8 |
| L15 | **No migration code.** Old totems and old saved constructs are disposable — fail open to a fresh default. | PLAN_V2 §5 |
| L16 | `EffectsMc` source widens from `ServerPlayer` to `LivingEntity` so bosses can cast. | §5.1 |
| L17 | **There is no always-on aura.** `self` is cut: every aura has a condition the player plays into. | §9.3 |
| L18 | **`leech` is cut; `heal` absorbs it.** On a construct `heal` is lifesteal; as an aura it is health over time. `absorption` works the same way — on a construct, hitting grants absorption hearts. One kamu, two readings, split by system. | §4.3, §4.4 |
| L19 | **Splash sums self-benefit, scales down damage.** For `heal`/`absorption`, Splash gives less per target but **accumulates across every target hit**, capped at 6 (config). For damage modifiers Splash only scales down — it never sums. | §4.4 |
| L20 | **Echo delays everything, including self-benefit.** `echo` + `heal` heals 20 ticks after the swing, not on it. | §4.4 |
| L21 | **`lightning` loses its chain.** It is burst damage plus a knockback jolt, nothing else — `splash` already covers multi-target, in the delivery slot where it belongs. This gives Lightning an aura reading: pulsed it shoves, held it grants knockback resistance and no fall damage. | §4.1, §4.5 |
| L22 | **The admission law (§2.1).** Every kamu has a construct reading AND an aura reading, and both behave identically for a player and a boss. No special cases, no per-entity branching. | §2.1 |
| L23 | **Kamu and attack patterns are separate layers (§2.2).** Bosses cast kamu through the *same* pipeline as players; what differs is the attack pattern (mob AI, wind-ups, phases). Phases / goals / minions are **monster design, deferred to a separate plan** — not part of this one. | §2.2 |
| L24 | **`wither` is tier-capped on boss auras.** Not banned — a boss aura must change how the player fights, never punish them for fighting; unrestricted anti-heal at tier IV crosses that line. Cap in config. | §9.9 |
| L25 | **Boss auras are rolled by Agent C, not Agent A.** `AuraSpec` is a public core record — Agent C constructs one directly from the boss's existing roll data (tier, seed, carried kamu ids) inside `Boss.java`/`BossAura.java`. `BossRoll.java` (core) is NOT touched and is NOT owned by any agent in this plan. | §9.9 |
| L26 | **Focus and Momentum charge, then release — one pulse, not a continuous drip.** No effect applies while charge is building. At full charge (1.0) it fires exactly one radius pulse — same targeting, same code path as a Bloom pulse (`AuraHost#applyPulse`), boon on self+allies, bane outward, dual both — then resets to 0. Draining below full before release forfeits the charge; there is no partial payoff. This is why L10's illegal pairing is gone: a bane reading on Focus/Momentum lands on whatever is in range, never on the bearer, so there was never a "curse yourself" case once release became a pulse. Focus and Momentum differ from Bloom and from each other **only** in what gates the pulse — a fixed interval for Bloom, stillness for Focus, motion for Momentum. Nothing else about them is special-cased. | §9.3 |

---

## 2. File ownership

**Touch only your own files.** If you need a change in someone else's file,
write `TODO(master):` in your own code and list it in your §7 block.

### Agent A owns — core (pure Java, zero Minecraft)

```
core/src/main/java/kamutotems/core/
  SlotRole.java          (rewrite)
  Category.java          (rewrite)
  Construct.java         (rewrite — 4 slots → 3)
  Kamu.java              (add polarity field)
  KamuCatalog.java       (rewrite roster)
  ReactionEngine.java    (rekey shatter)
  Resolver.java          (expose deliveryId)
  ResolutionResult.java  (add deliveryId)
  Polarity.java          (NEW)
  AuraKind.java          (NEW)
  AuraSpec.java          (NEW)
  AuraOutcome.java       (NEW)
  AuraResolver.java      (NEW)
  Delivery.java          (NEW)
core/src/test/java/kamutotems/core/
  CombatApiTest.java     (NEW — your own main(), your own counter)
```

### Agent B owns — player-side runtime

```
fabric/src/main/java/kamutotems/
  EffectsMc.java         (rewrite — new effect ids, LivingEntity source)
  DamageFunnel.java      (rewrite — delivery dispatch, no trigger gating)
  EchoQueue.java         (NEW — delayed effect scheduler)
  AuraHost.java          (NEW — pulse loop, Focus/Momentum meters)
  AuraTelegraph.java     (NEW — wind-up particles/sound before a Bloom pulse)
  Totem.java             (aura face read/write + lore ONLY)
  SlotMenu.java          (3 slots + aura tab)
```

### Agent C owns — boss parity with the shared vocabulary

**Scope is deliberately narrow (L23).** Agent C's job is to make a boss speak the
same kamu vocabulary a player does — nothing more. Boss *attack patterns* are a
separate discipline and a separate plan.

```
fabric/src/main/java/kamutotems/
  BossBrain.java         (NEW — cast cycle, cooldown, target selection)
  BossCastTelegraph.java (NEW — wind-up before a cast; see note)
  BossNames.java         (NEW — config-driven name generator)
  BossAura.java          (NEW — rolls the boss's AuraSpec, L25, and runs it
                          through Agent B's AuraHost unchanged)
  Boss.java              (rewrite)
  BossHost.java          (brain wiring ONLY)
```

**The telegraph stays in scope, and only this telegraph.** A cast wind-up is the
boss's equivalent of the player's weapon swing — it is how the *shared* effect is
announced, so it belongs with the cast. Elaborate encounter telegraphing (phase
transitions, ground markers, arena effects) does not.

**One thing that IS in scope despite touching "roll" logic: the boss's own
`AuraSpec` (L25).** `AuraSpec` is a plain public record in core — `BossAura.java`
builds one directly (pick an `AuraKind`, pick one of the boss's already-rolled
kamu ids as the modifier, seeded off the same `BossRoll` seed for reproducibility)
without needing any change to `BossRoll.java` itself. This is boss-side wiring,
not a core change, so it stays inside Agent C's file list.

**Explicitly OUT of scope — deferred to a future monster-design plan:**

| Deferred | Why it is not kamu work |
|---|---|
| `BossPhases` — HP thresholds, rule changes | Attack-pattern design (§2.2). Improves fights whether or not kamu exist. |
| `BossGoals` — leap/charge, reposition, face-nearest | Mob AI. Same. |
| Minion waves | Encounter design. Same. |
| Per-tier mob type lists | Encounter design — though it is cheap, and §5.6 argues it is the first tool to reach for. |

Do not create these files. If the work feels blocked without them, say so in
§7.C rather than building them.

### Master owns — nobody else touches

`KamuTotemsMod.java` · `KamuTotemsConfig.java` · `KamuData.java` · `Persist.java` ·
`Chime.java` · `Station.java` · `StationRouting.java` · all build files · all
`.json` defaults · `SPEC.md` · `PLAN_V2.md`

---

## 3. Frozen contract

**Agent A implements this verbatim. Agents B and C code against it as written,
before A has finished.** If a signature looks wrong, implement/call it anyway and
log the objection.

### 3.1 Enums

```java
package kamutotems.core;

public enum SlotRole {
    DELIVERY,          // slot 0
    MODIFIER;          // slots 1 and 2
    public boolean accepts(Category c);
    public String label();          // "Delivery" | "Modifier"
}

public enum Category { DELIVERY, ELEMENT, BEHAVIOUR }

public enum Polarity {
    BOON,              // lands on you / allies
    BANE,              // lands on enemies
    DUAL,              // both readings
    NONE;              // not an aura modifier at all — deliveries and `plain` only
    public boolean legalOn(AuraKind kind);
}

public enum AuraKind {                 // the four auras; "Aura" names the system
    BLOOM, FOCUS, MOMENTUM, REBUKE;
    public double magnitude();         // BLOOM 0.55, FOCUS 1.50, MOMENTUM 1.50, REBUKE 1.00
    public boolean pulses();           // true for BLOOM only, regardless of polarity
    public boolean isMetered();        // true for FOCUS and MOMENTUM
    public boolean chargesWhileMoving();  // MOMENTUM true, FOCUS false; undefined otherwise
    public int pulseIntervalTicks(int tier);  // BLOOM: 600 / 440 / 300 (tier 1/2/3); 0 otherwise
    public String label();             // "Bloom" | "Focus" | "Momentum" | "Rebuke"
}

public enum Delivery {
    HIT, ECHO, SPLASH;
    public static Delivery fromKamuId(String id);   // null if not a delivery
    public int delayTicks();        // ECHO 20, others 0
    public double areaRadius();     // SPLASH 3.5, others 0
    public double powerScale();     // SPLASH 0.6, others 1.0
    public int maxTargets();        // SPLASH 6, others 1
    public boolean sumsSelfBenefit();  // SPLASH true, others false  (L19)
}
```

### 3.2 Kamu and catalog

```java
public record Kamu(
        String id, String displayName, Category category, Rarity rarity,
        int complexity, String effectId, Map<String,Double> parameters,
        Set<String> tags, Set<HostType> allowedHosts,
        Polarity polarity) {}         // <-- NEW, last position
```

`KamuCatalog.defaults()` returns exactly these, in this order:

| id | display | category | rarity | cx | polarity | hosts |
|---|---|---|---|---|---|---|
| `hit` | Hit | DELIVERY | COMMON | 0 | NONE | TOTEM |
| `plain` | Plain | BEHAVIOUR | COMMON | 0 | NONE | TOTEM |
| `echo` | Echo | DELIVERY | UNCOMMON | 2 | NONE | TOTEM |
| `splash` | Splash | DELIVERY | UNCOMMON | 2 | NONE | TOTEM |
| `fire` | Fire | ELEMENT | COMMON | 1 | DUAL | TOTEM, BOSS |
| `ice` | Ice | ELEMENT | COMMON | 1 | DUAL | TOTEM, BOSS |
| `poison` | Poison | ELEMENT | RARE | 1 | DUAL | TOTEM, BOSS |
| `wither` | Wither | ELEMENT | RARE | 2 | BANE | TOTEM, BOSS |
| `lightning` | Lightning | ELEMENT | UNCOMMON | 1 | DUAL | TOTEM, BOSS |
| `heal` | Heal | BEHAVIOUR | RARE | 2 | BOON | TOTEM, BOSS |
| `absorption` | Absorption | BEHAVIOUR | UNCOMMON | 1 | BOON | TOTEM, BOSS |

`hit` and `plain` carry tag `"default"` and must never appear in `bossPool()`.
**Nothing is aura-only.** `heal` and `absorption` are legal in BOTH systems and
mean different things in each (L18) — lifesteal / hit-to-shield on a construct,
health-over-time / held hearts as an aura. `EffectsMc` therefore needs **two arms
for each of these two kamu**, selected by which system called it. Getting this
wrong is the single most likely correctness bug in Agent B's work.

**No kamu is construct-only either.** `lightning` was the last one; cutting its
chain (L21) gave it an aura reading. Every kamu in the catalog has a polarity
that is not `NONE`, so `Polarity.NONE` is now reachable only by the deliveries
and `plain`.

New accessors:
```java
public List<Kamu> auraModifiers();   // polarity != NONE — i.e. all 7 kamu
public List<Kamu> deliveries();      // category == DELIVERY
```

### 3.3 Construct

```java
public record Construct(HostType host, List<Slot> slots) {
    public static final int SLOT_COUNT = 3;
    public static SlotRole roleOf(int index);        // 0 DELIVERY, 1|2 MODIFIER
    public static final String DEFAULT_DELIVERY = "hit";
    public static final String DEFAULT_MODIFIER = "plain";
    public static Slot defaultFor(int index);
    public static boolean isDefault(int index, Slot slot);
    public static Construct empty(HostType host);
    public static Construct blank(HostType host);
    public String deliveryKamuId();                  // never null; "hit" if empty
    public List<Slot> modifiers();                   // slots 1..2
    public int complexity(KamuCatalog catalog);
}
```

`EventMatcher` and `QuestSegment` are **untouched** — the quest system does not
use triggers from this slot.

### 3.4 Resolution

```java
public record ResolutionResult(
        boolean valid, String fault, List<Effect> effects,
        String deliveryId) {}                        // <-- NEW

// Resolver signature is UNCHANGED:
public ResolutionResult resolve(Construct c, Context ctx, long seed);
```

`Resolver` no longer gates on any trigger. It resolves the two modifiers, runs
reactions, and reports which delivery the host should use.

### 3.5 Auras

```java
public record AuraSpec(AuraKind kind, String modifierKamuId, int tier) {
    public static AuraSpec none();
    public boolean isPresent();
}

public record AuraOutcome(
        boolean valid,
        String refusal,          // player-facing prose, null when valid
        String effectId,
        Polarity applied,        // BOON or BANE — the half that actually lands
        boolean pulses,          // true iff kind == BLOOM (L9)
        double magnitude) {}     // AuraKind.magnitude() x tier scale

public final class AuraResolver {
    public static AuraOutcome resolve(AuraSpec spec, KamuCatalog catalog);
}
```

**Refusal strings — exact, do not reword:**

| Condition | `refusal` |
|---|---|
| unknown modifier id | `"That kamu is not known to the totem."` |
| modifier with `Polarity.NONE` | `"That kamu only answers to a strike."` (deliveries and `plain`) |
| `AuraSpec.none()` | `"No aura is bound."` |

There is no longer a "bane on Focus/Momentum" refusal (L10, L26) — every non-`NONE` polarity
is legal on every aura kind. `Polarity.legalOn(AuraKind)` still exists and `AuraResolver` still
calls it, but it cannot currently return `false` for a non-`NONE` polarity; it is kept as an
extension point, not deleted, per the same "keep the mechanism, don't force a caller through it"
approach as `Polarity.DUAL` (L4). If it is ever exercised, its refusal string is
`"That kamu cannot be bound to this aura."` — distinct from the unknown-id string above.

**Resolution table** (§9.4) — `AuraResolver` must produce exactly this:

| Polarity | BLOOM (pulsed radius) | FOCUS (pulsed radius, release-gated by stillness) | MOMENTUM (pulsed radius, release-gated by motion) | REBUKE (on hurt) |
|---|---|---|---|---|
| BOON | you + allies | you + allies | you + allies | you |
| BANE | enemies | enemies | enemies | the attacker |
| DUAL | **both halves** | **both halves** | **both halves** | bane half → attacker |

**BLOOM, FOCUS and MOMENTUM's columns are identical** (L26) — `AuraResolver` passes the
kamu's polarity straight through unchanged for all three (`case BLOOM, FOCUS, MOMENTUM ->
kamu.polarity()`). Only Rebuke's column differs, because it has no radius — its target is
the attacker, singular, so DUAL has to collapse to one reading rather than hitting both.

For DUAL on any pulsed kind, `AuraResolver` returns `applied = DUAL` and the host applies
**both readings on the same pulse** — allies get the boon, enemies get the bane, one event.
`pulses()` (the `AuraKind` method) is still true for BLOOM only (L9) — Focus/Momentum's
release is a pulse in effect, not in the enum's own bookkeeping, since it isn't timer-driven.

---

## 4. Cross-agent interfaces

These are the only calls that cross an ownership boundary. Both sides code
against the signature; the master verifies they meet.

```java
// Agent B provides, Agent C calls:
public final class EffectsMc {
    public static void apply(List<Effect> effects, ServerLevel level,
                             LivingEntity source, Entity target);
}

// Agent B provides, Agent C calls (boss auras reuse the player pipeline
// unchanged — a boss aura is not a special case, L22):
public final class AuraHost {
    // continuous, self-only application. Unused as of L26 -- Focus and
    // Momentum release a pulse on full charge, not a per-tick drip -- but
    // kept public for a future aura kind that genuinely wants one.
    public static void applyHeld(ServerLevel level, LivingEntity bearer,
                                 AuraOutcome outcome);
    // one radius pulse: telegraph has already fired, this is the payoff.
    // Called for every BLOOM pulse, and for a FOCUS/MOMENTUM release (L26).
    public static void applyPulse(ServerLevel level, LivingEntity bearer,
                                  AuraOutcome outcome, double radius);
}

// Agent C provides, master wires:
public final class BossBrain    { public static void register(); }
public final class BossNames    { public static Component build(int tier, EntityType<?> type,
                                                                AuraSpec aura, List<Kamu> carried); }

// Agent B provides, master wires:
public final class AuraHost     { public static void register(); }
public final class EchoQueue    { public static void register(); }
```

**Agent B owns `EffectsMc` and `AuraHost`. Agent C must not edit either.**

---

## 5. Rules

- **Never invent a Minecraft API call.** Grep `kamutotems/fabric/src`,
  `spiritwolves/src`, `wondrous/fabric/src`, `cobbleeconomy/fabric/src` for the
  same call and copy its shape. If it appears nowhere, write it, put
  `// ⚠ UNVERIFIED` above it, and list it in your §7 block. 26.2 renamed a lot —
  see SPEC §16's correction table.
- **Do not run gradle or gradlew.** Agents B and C cannot compile; that is
  expected. The master compiles.
- **Do not touch git.**
- **Determinism:** no `HashMap` iteration-order dependence, no
  `System.currentTimeMillis()`, no unseeded `Random`.
- **Config-first.** Every number in §1's locked decisions is a
  `KamuTotemsConfig.d(...)` / `.i(...)` lookup with the locked value as the
  default. Never hardcode a tuning number.
- **Failure direction:** a refusal consumes nothing and says why in player-facing
  prose. Exceptions are never the refusal mechanism.
- **No migration code.** A totem carrying an unreadable old construct falls open
  to `Construct.empty(HostType.TOTEM)`. Log once at INFO, never throw.

---

## 6. Definition of done

### Agent A
`CombatApiTest.main()` prints `N passed, 0 failed` with **N ≥ 45**, covering:
- `Construct` is 3 slots; `roleOf` maps 0→DELIVERY, 1|2→MODIFIER
- `deliveryKamuId()` returns `"hit"` for an empty slot 0
- every `SlotRole.accepts` pairing, positive and negative
- `KamuCatalog.defaults()` matches §3.2 exactly — ids, count, polarity
- `bossPool()` excludes `hit`, `plain`, and every DELIVERY; `heal` and `absorption` ARE in it
- `heal` and `absorption` accepted in BOTH a construct MODIFIER slot and an aura
- every non-delivery, non-`plain` kamu is accepted in BOTH a construct MODIFIER
  slot and an aura; no kamu is exclusive to one system
- every cell of §3.5's resolution table
- all four refusal strings, matched exactly
- `Polarity.NONE` never yields a valid aura
- `shatter` fires on `ice`+`lightning` and **not** on `ice`+`heal`;
  `tainted` fires on `heal`+`poison` (L6)
- `Delivery` timings: `ECHO.delayTicks() == 20`, `SPLASH.powerScale() < 1.0`,
  `Delivery.values().length == 3`
- `AuraKind.magnitude()` ordering: FOCUS == MOMENTUM > REBUKE > BLOOM
- `AuraKind.pulseIntervalTicks` returns 600/440/300 for BLOOM tiers 1/2/3, 0 for the rest
- `FOCUS.chargesWhileMoving()` is false; `MOMENTUM.chargesWhileMoving()` is true
- determinism: same seed ⇒ same result, twice

`KamuTotemsTest` must still be green. Run both.

### Agent B
- `EffectsMc` handles exactly: `fire`, `ice`, `poison`, `wither`, `lightning`,
  `heal`, `absorption`, plus reaction outcomes `thermal_shock`, `melt`, `conduct`,
  `shatter`, `toxic_flame`, `tainted`, plus no-ops `hit`, `plain`. Any other
  id logs a warning and does nothing.
- Source is `LivingEntity`, never `ServerPlayer`. `heal` on a construct heals the
  source whatever it is — get this wrong and a boss heals the player it attacks.
- `lightning` applies burst damage **and** a knockback jolt. It does NOT chain —
  chaining was cut (L21) and `splash` covers multi-target.
- `fire` is plain vanilla ignite — no spreading, no ground patches (L5).
- `EchoQueue` survives the target dying before the delay expires, and drops the
  effect if the level unloads. **A delayed `heal`/`absorption` still pays out to
  the caster even if the target died in the interval** (L20) — the swing landed.
- **Splash sums self-benefit but never sums damage** (L19). `heal` across 6 mobs
  pays 6 reduced heals to the caster; `fire` across 6 mobs is just 6 reduced
  ignites. Cap contributing targets at `Delivery.SPLASH.maxTargets()`.
- `AuraHost` enforces one aura at a time, and drops the aura when
  `Totem.chargesRemaining() == 0` (L7).
- Focus and Momentum charge and drain at the configured rates, in opposite
  directions, and scale magnitude by charge.
- Bloom pulses on its tier interval with a telegraph, for boons and banes alike.
- Same-modifier auras from different players do not stack — strongest wins.

### Agent C
- A boss casts one carried kamu per cycle through `EffectsMc.apply` — **the same
  call a player's construct makes, with no boss-specific branch anywhere** (L22).
- Every cast is telegraphed: particles → sound → payoff, wind-up configurable,
  defaulting to 30 ticks.
- A boss's aura runs through Agent B's `AuraHost` unmodified. If it cannot, that
  is a bug in the shared pipeline — report it, do not work around it.
- `BossNames` reads its template and word lists from config; ids stay mechanical.
- Boss aura cap enforced: `wither` tier-capped.
- Everything the boss spawns carries the cleanup tag and dies with it.
- **No phase code, no custom goals, no minions** — deferred (L23).

---

## 7. Agent reports — replace your block when done

### 7.A — Core

<!-- AGENT-A
Files written:
  - core/src/main/java/kamutotems/core/SlotRole.java
  - core/src/main/java/kamutotems/core/Category.java
  - core/src/main/java/kamutotems/core/Construct.java
  - core/src/main/java/kamutotems/core/Kamu.java
  - core/src/main/java/kamutotems/core/KamuCatalog.java
  - core/src/main/java/kamutotems/core/ReactionEngine.java
  - core/src/main/java/kamutotems/core/Resolver.java
  - core/src/main/java/kamutotems/core/ResolutionResult.java
  - core/src/main/java/kamutotems/core/Polarity.java (new)
  - core/src/main/java/kamutotems/core/AuraKind.java (new)
  - core/src/main/java/kamutotems/core/AuraSpec.java (new)
  - core/src/main/java/kamutotems/core/AuraOutcome.java (new)
  - core/src/main/java/kamutotems/core/AuraResolver.java (new)
  - core/src/main/java/kamutotems/core/Delivery.java (new)
  - core/src/test/java/kamutotems/core/CombatApiTest.java (new)

Final test count:
  - CombatApiTest: 136 passed, 0 failed (N >= 45)
  - QuestApiTest: 81 passed, 0 failed (unchanged, still green)
  - KamuTotemsTest: does not compile against the new core because it still
    references the removed trigger API (Construct.TRIGGER_INDEX, triggerKamuId,
    Category.TRIGGER, Category.ACTION, four-slot constructs) and the old
    17-entry catalog. I did not edit it per the ownership boundary.

Signature objections:
  - ResolutionResult now has a single String fault rather than List<String>
    faults, as shown in §3.4. This was a deliberate contract change and the
    only clean way to expose the new deliveryId field.
  - AuraOutcome.magnitude uses tier directly as the multiplier
    (AuraKind.magnitude() * spec.tier()). The contract says "tier scale" but
    does not specify the curve, so I used the simplest deterministic scale.
  - The Resolver.resolve seed parameter is still unused (it was already unused
    in the previous implementation). This preserves the frozen signature.
  - Kamu kept a nine-argument compatibility constructor defaulting Polarity.NONE
    to avoid breaking any records still constructed that way in the quest API.

TODO(master):
  - Update core/src/test/java/kamutotems/core/KamuTotemsTest.java to the three-
    slot model, the 11-entry catalog, and the single-fault ResolutionResult.
  - Wire AuraHost.register(), EchoQueue.register() and BossBrain.register() in
    the mod entry point.
  - Add the locked-value config keys from §1 to kamutotems.json.
  - Remove the legacy KamuTotemsTest.java or reconcile its assertions before
    the master gradle build.

Anything the master needs:
  - Core now compiles cleanly with CombatApiTest and QuestApiTest. The master
    compile command should exclude the stale KamuTotemsTest until it is updated.
  - The reaction table is re-keyed to fire/ice/thermal_shock, ice/fire/melt,
    lightning/wet/conduct, ice/lightning/shatter, poison/fire/toxic_flame, and
    heal/poison/tainted.
-->

### 7.B — Player runtime

<!-- AGENT-B
Files written:
  - fabric/src/main/java/kamutotems/EffectsMc.java (rewritten to LivingEntity source)
  - fabric/src/main/java/kamutotems/DamageFunnel.java (rewritten for 3-slot delivery dispatch)
  - fabric/src/main/java/kamutotems/EchoQueue.java (NEW)
  - fabric/src/main/java/kamutotems/AuraHost.java (NEW)
  - fabric/src/main/java/kamutotems/AuraTelegraph.java (NEW)
  - fabric/src/main/java/kamutotems/Totem.java (aura face read/write + lore line)
  - fabric/src/main/java/kamutotems/SlotMenu.java (3 slots + aura tab)

⚠ UNVERIFIED calls (one line each, with the file and line):
  - EffectsMc.java: `victim.damageSources().mobAttack(source)` for non-player casters
  - EffectsMc.java: `victim.push(away.x, 0.35, away.z)` and `victim.hurtMarked`
  - EchoQueue.java: `MinecraftServer.getTickCount()` and `MinecraftServer.getAllLevels()`
  - EchoQueue.java: `ServerLevel.getEntity(UUID)` for target re-resolution
  - AuraHost.java: `MinecraftServer.getTickCount()` in `tickNow()`
  - AuraHost.java: `bearer.getDeltaMovement()` and `Vec3.horizontalDistanceSqr()`
  - AuraHost.java: `source.isAlliedTo(target)` for boon/bane target filtering
  - AuraTelegraph.java: `ServerLevel.sendParticles(ParticleOptions, boolean, boolean, double, double, double, int, double, double, double, double)`
  - AuraTelegraph.java: `SoundEvents.BEACON_POWER_SELECT` for wind-up sound
  - DamageFunnel.java: `Resolver.resolve(...)` returning a `ResolutionResult` with `deliveryId()` and a single `String fault()`

TODO(master):
  - Update `Construct` in core to 3 slots (DELIVERY / MODIFIER / MODIFIER), with `SLOT_COUNT=3` and `DEFAULT_ACTION`/`DEFAULT_MODIFIER` renaming if desired.
  - Provide the new `core` classes used by Agent B: `Delivery`, `Context`, `ResolutionResult` (with `deliveryId()` and `String fault()`), `AuraSpec`, `AuraKind`, `Polarity`, `AuraOutcome`, `AuraResolver`.
  - Add new kamu to `KamuCatalog` (deliveries `hit`, `echo`, `splash`; modifiers `fire`, `ice`, `poison`, `wither`, `lightning`, `heal`, `absorption`; defaults `hit` and `plain` with `Category.DELIVERY`/`BEHAVIOUR`).
  - Add a `combat` section to `KamuTotemsConfig` with all the locked fallback values listed below.
  - Same-modifier cross-player aura stacking is documented but not fully wired; add global strongest-wins tracking if the design stays hard.
  - Friendly half of `lightning` aura (knockback resistance / no fall damage) needs a clean vanilla or custom effect shape.
  - `AuraResolver` should refuse bane-only or illegal shape/modifier combinations with the exact refusal strings from §3.5.

Config keys introduced (all in `combat` section):
  - `base_damage` (double, 2.0)
  - `fire_resistance_ticks` (int, 200)
  - `fire_seconds` (double, 4.0)
  - `ice_ticks` (int, 120)
  - `poison_ticks` (int, 200)
  - `wither_ticks` (int, 160)
  - `lightning_jolt` (double, 0.8)
  - `heal_aura_ticks` (int, 60)
  - `absorption_cap` (double, 10.0)
  - `conduct_radius` (double, 5.0)
  - `shatter_jolt` (double, 0.6)
  - `toxic_flame_seconds` (double, 2.0)
  - `bloom_radius` (double, 3.5)
  - `bloom_telegraph_ticks` (int, 30)
  - `meter_fill_ticks` (int, 100)
  - `meter_drain_ticks` (int, 40)
  - `movement_threshold` (double, 0.001)
  - `rebuke_cooldown_ticks` (int, 20)
  - `telegraph_particles` (int, 20)

Anything the master needs:
  - `TotemHost.register()` now calls `EchoQueue.register()` and `AuraHost.register()`.
  - `EffectsMc.apply(List<Effect>, ServerLevel, LivingEntity, Entity)` is the one Minecraft translation entry point.
  - `DamageFunnel` calls `Resolver.resolve()` and dispatches `HIT`, `ECHO`, `SPLASH` via `Delivery.fromKamuId()`.
  - `AuraHost` calls `AuraResolver.resolve()` and uses `KamuData.catalog()` for base modifier parameters.
  - `SlotMenu` assumes `Construct.roleOf(0) == DELIVERY`; align `Construct` and `KamuCatalog` accordingly.
  - `Totem.find()` is used for player totem lookup only; `AuraHost` is deliberately player-only on the totem tick path and falls back to `applyHeld`/`applyPulse` for `BossAura`.
-->

### 7.C — Boss runtime

<!-- AGENT-C
Files written:
  - fabric/src/main/java/kamutotems/BossBrain.java
  - fabric/src/main/java/kamutotems/BossCastTelegraph.java
  - fabric/src/main/java/kamutotems/BossNames.java
  - fabric/src/main/java/kamutotems/BossAura.java
  - fabric/src/main/java/kamutotems/Boss.java (rewritten)
  - fabric/src/main/java/kamutotems/BossHost.java (activeBosses accessor)
⚠ UNVERIFIED calls (one line each, with the file and line):
  - BossCastTelegraph.java: particleFor() and soundFor() use ParticleTypes/SoundEvents constants not duplicated in the suite's verified call sites.
  - BossNames.java: BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath() for mob key.
  - BossBrain.java: AuraHost.applyHeld/applyPulse and AuraResolver.resolve are called per the §4 contract but are not yet implemented by Agent B.
TODO(master):
  - Ensure KamuTotemsMod.register wires BossBrain.register() after KamuData.load.
  - Add the new config keys to the shipped config.json defaults:
      boss.cast_windup_ticks (default 30)
      boss.cast_target_range (default 24.0)
      boss.cast_cooldown_tier1 (120), tier2 (100), tier3 (90), tier4 (80)
      boss.bloom_pulse_radius (default 4.0)
      boss.wither_aura_cap (default 1)
      boss.name_template ("<epithet> of <intensifier> <aura>")
      boss.mob_epithets, boss.intensifiers, boss.aura_names (optional; fallbacks provided)
  - Resolve the unverified ParticleTypes/SoundEvents against the merged 26.2 jar.
  - Verify Agent B's EffectsMc.apply and AuraHost signatures match §4 at compile time.
Config keys introduced:
  boss.cast_windup_ticks
  boss.cast_target_range
  boss.cast_cooldown_tier1-4
  boss.bloom_pulse_radius
  boss.wither_aura_cap
  boss.name_template
  boss.mob_epithets
  boss.intensifiers
  boss.aura_names
Anything the master needs:
  - BossBrain uses KamuData.catalog() and KamuData.reactions(); the catalog must load before BossBrain.register() is called.
  - No phase code, custom goals, or minions were added (out of scope per L23).
-->

---

## 8. Master stitch checklist

Run in this order. Do not skip ahead — a failure early invalidates later checks.

1. **Core alone.** `javac` core main + test; run `CombatApiTest` and
   `KamuTotemsTest`. Both green before touching fabric.
2. **Contract drift.** Diff what A actually wrote against §3. Every deviation is
   a compile break in B or C — find them by reading, not by compiling.
3. **Collect every `⚠ UNVERIFIED`.** Grep the whole fabric tree. Resolve each
   against the merged jar or `_tmp_mcsrc6/`. This is the single largest source of
   build failures.
4. **Resolve every `TODO(master):`.** These are the cross-boundary changes the
   agents could not make themselves.
5. **Wire registration** in `KamuTotemsMod`: `AuraHost.register()`,
   `EchoQueue.register()`, `BossBrain.register()`. Confirm ordering — `KamuData`
   must load before anything reads the catalog.
6. **Config defaults.** Add every key from §7.B and §7.C to the shipped
   `kamutotems.json` with the locked values from §1.
7. **Compile.** `./gradlew build`. Expect breaks at the B/C seam on
   `EffectsMc.apply` and `AuraHost` signatures.
8. **Ownership audit.** Confirm no agent edited a file it did not own —
   especially `EffectsMc` (B only) and `Boss.java` (C only).
9. **Verify the admission law holds (L22).** Grep the whole fabric tree for
   `instanceof ServerPlayer` and `instanceof Player` inside effect and aura code.
   Every hit is either a bug or needs an explicit justification in a comment. This
   is the check that keeps the boss a tutorial rather than a separate system.
10. **Double-count check.** One kill must advance the daily chain *and* an
    assigned quest, with neither consuming the other's progress. Read
    `DailyChain.tryAdvance` before signing this off. (`reap` no longer exists,
    L2 — this check is now about the quest systems only, not a delivery.)
11. **Cross-cutting reads the agents could not do:**
    - `Totem.refreshLore` shows both faces without overflowing the tooltip.
    - `TotemCharges` dormancy kills the aura as well as the construct (L7).
    - `Sigil.makeRolledSigil` lore still names carried kamu after the roster change.
    - `BossDrops.drop` cannot drop `plain` or any delivery. It CAN drop `heal` and `absorption`.
    - `KamuData` fails open to the new defaults, not the old roster.
12. **Play-verify list** — nothing below can be proven by compiling:
    - `echo` lands 1 s later, on a target that has since moved
    - a bloom is visible and audible before it hurts
    - the Focus and Momentum charge meters are legible without a client mod,
      and a player can tell which one they have
    - a tier-I boss is still solo-beatable
    - a boss's pulse does not machine-gun a player's `rebuke`
    - a boss carrying Fire burns you the way your Fire burns a mob — same feel,
      not merely the same code path

---

## Appendix — Handoff prompts

---

### AGENT A — Core combat API

```
You are implementing the pure-Java core for the Minecraft Fabric mod
"Kamu Totems" (mod id: kamutotems) at a:\MrPinoys Mods\kamutotems.

READ FIRST:
  1. kamutotems\PLAN_COMBAT.md — §1 (locked decisions), §2 (your files), §3 (the
     frozen contract you implement VERBATIM), §5 (rules), §6 (done). §3 IS YOUR
     SPECIFICATION.
  2. kamutotems\BRAINSTORM_COMBAT.md — §2.1 (the admission law — it explains why
     the catalog looks the way it does), then §4.1, §4.4, §9.3, §9.4. This is the
     reasoning behind §3. Read it so you understand intent, but §3 wins on any
     conflict.
  3. kamutotems\core\src\main\java\kamutotems\core\ — the code you are changing.
  4. kamutotems\core\src\test\java\kamutotems\core\KamuTotemsTest.java — for test
     STYLE only. You write a SEPARATE file, CombatApiTest.java, with its own
     main() and its own pass/fail counter. Do NOT edit KamuTotemsTest.java, but
     DO keep it compiling and green — if your changes break it, fix it minimally
     and say so in §7.A.

YOUR JOB: every file under "Agent A owns" in §2.

HARD CONSTRAINTS:
  - Package kamutotems.core. ZERO net.minecraft.* imports. Zero Gson. Zero
    logging. Zero test frameworks. java.* only.
  - Implement §3 VERBATIM. If a signature looks wrong, implement it anyway and
    log the objection in §7.A.
  - THE CONSTRUCT IS NOW THREE SLOTS. TRIGGER_INDEX, triggerKamuId() and
    actionAndModifiers() are DELETED, not deprecated. The WHEN slot does not
    exist. Anything that reads a trigger from a construct is wrong.
  - DO NOT TOUCH EventMatcher, QuestSegment, QuestProgress, QuestChain or
    anything else the quest system owns. They do not use construct triggers.
  - Refusals are RETURN VALUES with player-facing prose, never exceptions. The
    exact strings are in §3.5 and are matched character-for-character by tests.
  - Polarity lives on the Kamu record. There is no second aura registry.
  - NO KAMU IS EXCLUSIVE TO ONE SYSTEM (the admission law, §2.1). Every one of the
    7 kamu has a polarity that is not NONE, so every one is legal both as a
    construct MODIFIER and as an aura modifier. Polarity.NONE is reachable ONLY by
    the 3 deliveries and `plain`. If your catalog produces any other arrangement,
    you have mistyped the table in §3.2 — re-read it rather than "fixing" it.
  - Determinism: no HashMap iteration-order dependence, no currentTimeMillis, no
    unseeded Random.

VERIFY YOUR OWN WORK — you are the only agent who can:
    cd "a:\MrPinoys Mods\kamutotems\core"
    javac -d out (all core main + test .java files)
    java -cp out kamutotems.core.CombatApiTest
    java -cp out kamutotems.core.KamuTotemsTest
  CombatApiTest must print "N passed, 0 failed" with N >= 45 covering everything
  in §6. KamuTotemsTest must still be green.

DO NOT: run gradle/gradlew; touch git; edit anything outside core/ except your
§7.A block; write migration code (old data is disposable, §1 L15).

WHEN DONE: replace the <!-- AGENT-A --> block in PLAN_COMBAT.md §7.A.
```

---

### AGENT B — Player-side combat runtime

```
You are implementing the player-facing combat runtime for the Minecraft Fabric
server-side mod "Kamu Totems" (mod id: kamutotems) at
a:\MrPinoys Mods\kamutotems.
Target: Minecraft 26.2, Fabric, JDK 25, server-side only, vanilla clients install
nothing, ZERO Mixins.

READ FIRST:
  1. kamutotems\PLAN_COMBAT.md — §1, §2 (your files), §3 (core API you CALL —
     agent A implements it; code against it EXACTLY as written), §4 (the
     interfaces you PROVIDE to agent C), §5, §6.
  2. kamutotems\BRAINSTORM_COMBAT.md — §4.1 (what each element does), §4.4
     (deliveries and the Splash/Echo self-benefit rules), §9.3 (the four auras,
     the Bloom pulse, the Focus/Momentum meters), §9.6
     (magnitude budget).
  3. kamutotems\fabric\src\main\java\kamutotems\EffectsMc.java and
     DamageFunnel.java — what you are rewriting. Read them fully first.
  4. spiritwolves\src\main\java\spiritwolves\AbilityProcs.java and Tricks.java —
     the suite's proven particle and sound calls. Copy their shape.

YOUR JOB: every file under "Agent B owns" in §2.

RULES THAT MATTER MOST:
  - NO SPECIAL CASES (the admission law, PLAN_COMBAT §2.1 / BRAINSTORM §2.2).
    EffectsMc.apply takes LivingEntity source, NOT ServerPlayer, and every arm
    must produce IDENTICAL behaviour whether the source is a player or a boss.
    heal's construct reading is lifesteal and must heal the SOURCE whatever it
    is. Getting this wrong means a boss heals the player it is attacking, and it
    breaks the one rule this whole plan exists to enforce.
  - heal AND absorption ARE LEGAL IN BOTH SYSTEMS AND MEAN DIFFERENT THINGS IN
    EACH (L18). On a construct: heal is lifesteal, absorption is hit-to-shield.
    As an aura: heal is health-over-time, absorption is held/pulsed hearts. This
    is ONE kamu id needing TWO code arms each, selected by which system called
    in. This is the single most likely correctness bug in your half of the plan
    — get the dispatch right before anything else.
  - FIRE IS PLAIN VANILLA IGNITE. No spreading, no ground patches, no lingering.
    It has the highest DoT precisely BECAUSE it has no secondary effect. Do not
    add one back.
  - LIGHTNING DOES NOT CHAIN. It is burst damage plus a knockback jolt, nothing
    else — chaining was cut, splash covers multi-target instead. The jolt is not
    cosmetic: the shatter reaction (ice + lightning) depends on it.
  - BLOOM PULSES, FOR BOONS AND BANES ALIKE, LIKE A BEACON (L9). There is no
    "holds continuously" behaviour for a radius aura — that was cut with the
    `self` aura. Interval is set by tier (600/440/300 ticks), not by polarity.
    Every pulse gets a wind-up telegraph BEFORE it lands. Ship the telegraph
    with the pulse or neither works.
  - FOCUS AND MOMENTUM CHARGE, THEN RELEASE A PULSE (L11, L17, L26). Focus
    charges standing still and drains moving; Momentum is the exact opposite.
    Neither applies anything while charging — at full charge (1.0) each fires
    ONE radius pulse, same code path as a Bloom pulse (applyPulse), then
    resets to 0. They must be tunable as a PAIR — if one is stronger the
    other is dead weight, since there is no baseline `self` aura left to
    compare either one against.
  - SPLASH SUMS SELF-BENEFIT BUT NEVER SUMS DAMAGE (L19). heal/absorption under
    Splash pay less per target but accumulate across every target hit, capped at
    Delivery.SPLASH.maxTargets(). Fire under Splash just scales down — summing
    would double-dip, since spreading damage over many targets is already its
    own reward.
  - ECHO DELAYS EVERYTHING, INCLUDING SELF-BENEFIT (L20), AND THE WORLD MOVES ON.
    A delayed heal/absorption still pays out to the caster even if the target
    died in the interval — the swing landed. The target may also log out, change
    dimension, or have its level unload before the 20 ticks expire; drop the
    EFFECT silently in those cases, but never the caster's own payout. Never hold
    a hard Entity reference across ticks — store UUIDs and re-resolve.
  - ONE AURA ACTIVE AT A TIME, and same-modifier auras from different players do
    not stack — strongest wins. Without this a group of players is a death field.
  - THE AURA DIES WITH THE TOTEM. At zero charges the totem is dormant and the
    aura stops. This is the entire death penalty for the aura system; do not add
    a second one.
  - Refusals consume nothing and say why, using the exact strings in §3.5.

NEVER INVENT A MINECRAFT API CALL. Grep kamutotems/fabric/src, spiritwolves/src,
wondrous/fabric/src and cobbleeconomy/fabric/src for the same call and copy its
shape. If it appears nowhere, write it, put "// ⚠ UNVERIFIED" above it, and list
it in §7.B. 26.2 renamed a lot — see SPEC §16's correction table.

DO NOT: run gradle/gradlew (you cannot compile; that is expected — the master
does it); touch git; create or edit KamuTotemsMod.java, KamuTotemsConfig, Chime,
Persist, KamuData, Station, any build file, or any file owned by agents A or C.
In particular DO NOT touch Boss.java or BossHost.java. Write no migration code.

EXPOSE EXACTLY: AuraHost.register(), EchoQueue.register(), and the two AuraHost
methods in §4 that agent C calls.

WHEN DONE: replace the <!-- AGENT-B --> block in PLAN_COMBAT.md §7.B.
```

---

### AGENT C — Boss encounters

```
You are implementing boss encounters for the Minecraft Fabric server-side mod
"Kamu Totems" (mod id: kamutotems) at a:\MrPinoys Mods\kamutotems.
Target: Minecraft 26.2, Fabric, JDK 25, server-side only, vanilla clients install
nothing, ZERO Mixins.

THE POINT OF THIS WORK: today a boss is a zombie with more health that never uses
the kamu it carries — Boss.kamu is read by exactly one thing, the boss bar label.
SPEC §7.1 claims the boss bar is the mod's only tutorial ("a player who fights a
boss carrying a kamu learns what it does before they ever own it"). That claim is
currently false. You are making it true.

READ FIRST:
  1. kamutotems\PLAN_COMBAT.md — §1, §2 (your files), §3 (core API you CALL),
     §4 (the interfaces agent B PROVIDES to you), §5, §6.
  2. kamutotems\BRAINSTORM_COMBAT.md — §2.1 and §2.2 FIRST (the admission law and
     the kamu/attack-pattern split — these define your scope), then §5.1 (the boss
     brain), §9.9 (boss auras), §5.8 (naming). §5.3-§5.6 describe the DEFERRED
     attack-pattern layer; read for context, do not implement.
  3. kamutotems\fabric\src\main\java\kamutotems\Boss.java and BossHost.java —
     what you are rewriting. Read both fully first.
  4. kamutotems\SPEC.md — §7 (tiers, sigils, drops).
  5. spiritwolves\src\main\java\spiritwolves\AbilityProcs.java — proven particle
     and sound calls. Copy their shape.

YOUR JOB: every file under "Agent C owns" in §2.

RULES THAT MATTER MOST:
  - TELEGRAPH OR IT IS NOT DIFFICULTY. Every boss special is three beats in the
    same order every time: particles accumulate (~30 ticks, config) -> a
    distinctive sound -> the effect lands. The boss also stops advancing during
    the wind-up; with no client mod, THE PAUSE IS THE ANIMATION. A cast without a
    telegraph is unblockable chip damage from an invisible source. Ship them
    together or ship neither.
  - NO SPECIAL CASES. THIS IS THE WHOLE JOB. A boss casts kamu through exactly
    the same EffectsMc.apply call a player's construct uses. If you find yourself
    writing "if (source instanceof ServerPlayer)" or a boss-only effect branch,
    stop — that is a bug in the shared pipeline, and it belongs to agent B. Report
    it in §7.C instead of working around it. See PLAN_COMBAT §2.1 (the admission
    law) and BRAINSTORM §2.2.
  - YOUR SCOPE IS PARITY, NOT ENCOUNTER DESIGN. Phases, custom AI goals and minion
    waves are ATTACK-PATTERN work, which is a separate discipline and a separate
    plan (L23). Do not write BossPhases, BossGoals, or minion code. If parity
    feels impossible without them, say so in §7.C.
  - TIER I MUST STAY SOLO-BEATABLE. SPEC §7.1 promises it. Adding real casts to a
    boss that previously had none is already a large difficulty jump; keep the
    tier-I cast cooldown generous. If you break the free daily you have broken the
    mod's retention hook.
  - YOU DO NOT OWN EffectsMc OR AuraHost. Agent B does. Call them per §4. If you
    need a change in either, write TODO(master): in your own file and list it.
  - BOSS AURA BLACKLIST: wither is tier-capped (L24). A boss aura must change how the
    player fights, never punish them for fighting.
  - EVERYTHING YOU SPAWN MUST DIE WITH THE BOSS. BossHost.java:90 tags the boss
    entity and cleanup sweeps it; minions ride the same tag. An orphaned tier-IV
    add wave is a server incident.
  - NAMES COME FROM CONFIG. BossNames reads a template plus word lists from JSON.
    Mechanical ids stay stable in code — never write shape or kamu ids in meme
    form. See BRAINSTORM §5.8 for the template and the reasoning.

NEVER INVENT A MINECRAFT API CALL. Grep kamutotems/fabric/src, spiritwolves/src,
wondrous/fabric/src and cobbleeconomy/fabric/src for the same call and copy its
shape. If it appears nowhere, write it, put "// ⚠ UNVERIFIED" above it, and list
it in §7.C. 26.2 renamed a lot — see SPEC §16's correction table.

DO NOT: run gradle/gradlew (you cannot compile; that is expected — the master
does it); touch git; create or edit KamuTotemsMod.java, KamuTotemsConfig, Chime,
Persist, KamuData, Station, Sigil, BossDrops, any build file, or any file owned
by agents A or B. Write no migration code.

EXPOSE EXACTLY: BossBrain.register() and the BossNames.build(...) signature in §4.

THE POINT, RESTATED: when you are done, a boss carrying Fire should burn a player
in exactly the way a player carrying Fire burns a mob — same code path, same
numbers, same feel. That is what makes the boss bar a tutorial.

WHEN DONE: replace the <!-- AGENT-C --> block in PLAN_COMBAT.md §7.C.
```
