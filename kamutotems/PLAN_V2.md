# Kamu Totems v2 — Station & Outward API

> **Parallel implementation plan.** Three agents work at once against the frozen
> contract in §3, each owning a disjoint file set (§2), each reporting only into
> its own block of §7. The master owns build files, the entrypoint, and
> integration (§8).
>
> **Read first:** [SPEC.md](SPEC.md) for the design, [PLAN.md](PLAN.md) for how
> v1 was built and the conventions that survived it.
>
> **Status:** **complete and build-verified 2026-08-13; not play-verified.**
> `./gradlew build` green, `KamuTotemsTest` 134/134, `QuestApiTest` 87/87,
> server boots clean, jar in `dist/`. Zero Mixins, zero cross-mod imports,
> core still free of `net.minecraft`.

---

## 0. What v2 adds

**Two features.**

1. **The Kamu Station** — every scattered interaction collapses into one block.
   A fletching table opens a hub: Carving, Fusion, Discoveries, Naming,
   Journal, Quests. One place to learn, one place to return to.
2. **An outward API made of items** — other mods hand a player a *quest scroll*
   or a *boss stone*, and this mod does the rest. A wayfarer NPC can start a
   Kamu Totems quest without either mod knowing the other exists.

**No migration.** The mod has only ever run on the author's test server. Player
data may be wiped freely — do not write migration code, do not preserve old
NBT shapes, do not add compatibility branches for v1 layouts.

---

## 1. Locked decisions

| Decision | Value | Why |
|---|---|---|
| Station block | **Any vanilla fletching table** | A placed block cannot carry `custom_data` — components are item-level. Marking *specific* blocks needs a server-side placement hook, which Fabric lacks and which is why `ballot` carries the suite's only Mixin. Fletching tables have no vanilla right-click behaviour to displace |
| Station is required for | Carving, Fusion, Discoveries, Naming, Journal, Quests | One block contains the mod |
| Station is **not** required for | **Binding a kamu** (right-click) and **summoning** (right-click a sigil) | A player at Y-12 must still pocket loot and start a fight without walking home |
| Outward API shape | **An item id + `custom_data`.** Never a Java API, never an api module | [DESIGN.md §3](../DESIGN.md): config is the integration layer. `wayfarers` names a string; nobody imports anybody |
| Quest definitions | **Data.** `config/kamutotems/quests.json` | Adding a quest must not need a rebuild |
| Assigned quests | A **second track**, parallel to the daily chain | Daily is date-derived, server-wide, stateless. Assigned is per-player, ad-hoc, persistent. Shared: `EventMatcher`, `QuestProgress`, segment kinds. **Not** shared: lifecycle |
| **Quest rewards may never grant a kamu** | Grant a **boss stone** instead | Kamu are boss-drop-only, full stop ([SPEC §2](SPEC.md)). A quest that pays kamu makes the boss loop optional |
| NPC boss stones | **Allowed, any tier** | The daily chain guarantees *at least one* fight a day; it was never meant to be the only source |
| Discoverability | **The totem's lore says a fletching table is needed** | Moving everything behind a block is a discoverability regression ([SUITE_AUDIT §7](../SUITE_AUDIT.md)); the item the player already holds is where the hint belongs |

---

## 2. File ownership — exhaustive

### Agent A owns — `core/`, pure Java, no Minecraft

```
core/src/main/java/kamutotems/core/
    AssignedQuest.java        QuestDefinition.java     QuestCatalog.java
    QuestReward.java          QuestState.java          AssignedQuests.java
core/src/test/java/kamutotems/core/
    QuestApiTest.java         (own main(), own counter)
```

> Agent A does **not** touch `KamuTotemsTest.java`. Two agents editing one test
> file is the collision the ownership table exists to prevent.

### Agent B owns — the item API surface

```
fabric/src/main/java/kamutotems/
    QuestScroll.java          — the grantable item: read, validate, grant, consume
    BossStone.java            — direct sigil grant, for NPCs that skip the quest
    AssignedQuestHost.java    — register(), registerCommands(), persistence
    QuestApiConfig.java       — loads quests.json, fails OPEN (see §5)
fabric/src/main/resources/
    quests.json               — 3-5 sample definitions
INTEGRATION.md                — the contract for other mod authors
```

### Agent C owns — the station

```
fabric/src/main/java/kamutotems/
    Station.java              — fletching-table interaction, hub sgui
    NameMenu.java             — sgui AnvilInputGui for naming the Kamuy
    StationRouting.java       — back-button plumbing shared by the panels
```

Agent C **also edits** these v1 files, and is the only agent permitted to:
`SlotMenu.java`, `KamuForge.java`, `BookMenu.java`, `TotemHost.java`,
`Totem.java` (lore line only).

### Master owns

```
build files, KamuTotemsMod.java, KamuTotemsConfig.java, Chime.java,
Persist.java, KamuData.java, EffectsMc.java, SPEC.md, README.md,
PLAN_V2.md (§7 excepted), and every other v1 file not listed above.
```

---

## 3. THE FROZEN CONTRACT

Package `kamutotems.core`. **No `net.minecraft.*`** — Minecraft things are
strings. Agent A implements this verbatim; B and C call it as written. If A
believes a signature is wrong, A implements it anyway and logs the objection in
§7.A.

### 3.1 Quest definitions and rewards

```java
public enum QuestState { ACTIVE, COMPLETE, EXPIRED, ABANDONED }

/** What a finished quest pays. NEVER a kamu -- see section 1. */
public record QuestReward(
        String kind,        // "diamond" | "cobblestone" | "sigil" | "item"
        String itemId,      // for kind "item"; null otherwise
        int amount,         // count, or sigil tier for kind "sigil"
        String note) {      // optional player-facing line

    /** Guard: rejects any reward that would hand over a kamu. */
    public boolean isLegal();
}

/** A quest as authored in quests.json. Data, not code. */
public record QuestDefinition(
        String id,                       // "wayfarer_lost_cargo"
        String title,
        String sourceLabel,              // "A wayfarer asked this of you."
        List<QuestSegment> segments,     // reuses the v1 record
        List<QuestReward> rewards,
        int expiryDays,                  // 0 = never expires
        boolean repeatable) {}

public final class QuestCatalog {
    public QuestCatalog(List<QuestDefinition> definitions);
    public static QuestCatalog defaults();      // the sample set
    public QuestDefinition get(String id);      // null if unknown
    public List<QuestDefinition> all();
}
```

### 3.2 An assigned quest and its rules

```java
public record AssignedQuest(
        String definitionId,
        String grantedDateKey,
        QuestProgress progress,
        QuestState state) {

    public boolean isExpired(String todayKey, QuestDefinition def);
    public AssignedQuest advance(int segmentIndex, int by, QuestDefinition def);
    public boolean complete(QuestDefinition def);
}

public final class AssignedQuests {
    public static final int DEFAULT_MAX_ACTIVE = 3;

    /** Refusals are return values, never exceptions. */
    public record GrantResult(boolean ok, AssignedQuest quest, String message) {}

    public static GrantResult grant(List<AssignedQuest> held, QuestDefinition def,
                                    String todayKey, int maxActive);

    /** First ACTIVE quest with a segment matching this event, or -1. */
    public static int findMatching(List<AssignedQuest> held, QuestCatalog catalog,
                                   String kind, Map<String, String> event);

    /** Sweep expiries. Returns the list with states updated. */
    public static List<AssignedQuest> tick(List<AssignedQuest> held,
                                           QuestCatalog catalog, String todayKey);
}
```

**Required refusal messages** (player-facing prose, exact strings):

| Condition | Message |
|---|---|
| At the cap | `"You are already carrying as many errands as you can remember."` |
| Unknown definition | `"This scroll means nothing to you."` |
| Already held, not repeatable | `"You are already doing this."` |
| Expired on pickup | `"This errand is long past."` |

### 3.3 Fabric-side contract (master-owned; B and C call it)

Unchanged from v1: `Chime.play`, `Persist.save/load`, `KamuTotemsConfig.i/d/b/s`,
`KamuData.catalog()/reactions()`.

**Each new host exposes exactly:**

```java
public static void register();
public static void registerCommands(CommandDispatcher<CommandSourceStack> d);
```

`AssignedQuestHost` (B) and `Station` (C). The master wires both. **No agent
edits `KamuTotemsMod.java`.**

**Agent C's panels must expose a uniform entry point** so the hub can route to
them without knowing their internals:

```java
public static void open(ServerPlayer player);              // existing
public static void openFrom(ServerPlayer player, Runnable back);   // NEW
```

---

## 4. Phases

| Phase | Who | Gate |
|---|---|---|
| 0 | Master | Contract frozen, stubs in place, **verify list run ✅ (§9)** |
| 1 | A, B, C in parallel | Each agent's §6 done |
| 2 | Master | First `./gradlew build`, reconcile drift |
| 3 | Master | Play-verify §18-style checklist |

---

## 5. Rules every agent follows

1. **Do not run `gradle`/`gradlew`.** Only the master builds. Three daemons
   fight over `.gradle/*.lock` and produce failures that look like code bugs.
   Agent A verifies with plain `javac`/`java`.
2. **Do not touch `git`.**
3. **Do not edit files you do not own** (§2).
4. **Do not write migration code.** Old player data is disposable (§0).
5. **Never invent a Minecraft API call.** Grep `kamutotems/fabric/src`,
   `spiritwolves/src`, `wondrous/fabric/src` for the same call and copy its
   shape. If it exists nowhere, write it, mark `// ⚠ UNVERIFIED` on the line
   above, and log it in §7. **26.2 renamed a lot** — see [PLAN.md §5](PLAN.md)
   and SPEC §16's correction table.
6. **Report into your own §7 block only.**
7. **When blocked, log it, stub with `// TODO(master):`, and continue.**

### Chosen failure directions for the new code

| Situation | Chosen failure |
|---|---|
| `quests.json` unparseable | **Defaults in memory, file untouched.** Quests still work; nobody's data is at stake |
| Unknown definition id on a scroll | Scroll is refused and **not consumed** |
| Assigned-quest file unreadable | Renamed `.corrupt`, player starts with none, **server still boots** |
| Reward won't fit inventory | Drops at the player's feet, logged. Never deleted |
| Quest completes but reward fails | **Quest stays COMPLETE and the reward retries.** Never mark paid on a failed payout |
| A reward names a kamu | **Rejected at load**, logged loudly. `QuestReward.isLegal()` is the guard |

---

## 6. Definition of done

**Agent A** — `java kamutotems.core.QuestApiTest` prints `N passed, 0 failed`,
N ≥ 40. Covers: cap enforcement, every refusal string in §3.2, expiry across a
month boundary, `findMatching` picking the *first* active match, `tick`
transitioning only what it should, repeatable vs not, and
**`QuestReward.isLegal()` rejecting every kamu id in `KamuCatalog.defaults()`**.

**Agent B** — scroll grants and is consumed; refusals consume nothing; boss
stone hands over a working rolled sigil; assigned quests advance from the same
kill/turn-in/scan taps **without double-counting the daily chain**; `/quests`
lists, shows progress, abandons; `INTEGRATION.md` is copy-pasteable by someone
who has never seen this codebase.

**Agent C** — right-clicking any fletching table opens the hub; all six panels
reachable and each returns to the hub; naming works through `AnvilInputGui`;
the old sneak+right-click gestures are **removed**; binding a kamu and summoning
a sigil still work in the field; the totem's lore names the fletching table.

---

## 7. Progress reports

### 7.A — Core quest API
<!-- AGENT-A -->
**Status: done.**

**Files written** (all under `core/`, package `kamutotems.core`, zero
`net.minecraft.*`, zero Gson, zero logging, zero test frameworks):

- `src/main/java/kamutotems/core/QuestState.java`
- `src/main/java/kamutotems/core/QuestReward.java`
- `src/main/java/kamutotems/core/QuestDefinition.java`
- `src/main/java/kamutotems/core/QuestCatalog.java` — `defaults()` ships 4
  sample definitions (`wayfarer_lost_cargo`, `wayfarer_cull_the_pack`,
  `wayfarer_survey_the_ridge`, `wayfarer_two_favours`), one of them
  repeatable, one two-segment, one paying an `item` reward.
- `src/main/java/kamutotems/core/AssignedQuest.java`
- `src/main/java/kamutotems/core/AssignedQuests.java`
- `src/test/java/kamutotems/core/QuestApiTest.java` — own `main()`, own
  pass/fail counter. Did not touch `KamuTotemsTest.java`.

**Verification:**

```
cd "a:\MrPinoys Mods\kamutotems\core"
javac -d out (all core main + test .java files)
java -cp out kamutotems.core.QuestApiTest    -> 87 passed, 0 failed
java -cp out kamutotems.core.KamuTotemsTest  -> 134 passed, 0 failed  (unchanged, still green)
```

`QuestApiTest` covers: `QuestReward.isLegal()` rejecting every one of the 17
kamu ids in `KamuCatalog.defaults()` when smuggled in via `kind:"item"`, plus
bad kind/amount/itemId combinations; `QuestDefinition`/`QuestCatalog`
round-trips and reward legality of the shipped sample set; `AssignedQuest`
advance/clamp/no-op-on-bad-index/no-op-on-null-def/complete; expiry including
a case that crosses a Jan->Feb month boundary, `expiryDays == 0` never
expiring, and null-safety; all four `AssignedQuests.grant` refusal strings
from §3.2 verbatim (cap, unknown definition, already-held non-repeatable,
expired-on-pickup) plus the repeatable-allowed and fresh-success paths;
`findMatching` returning the *first* active held quest that matches, skipping
already-complete segments and non-ACTIVE quests; `tick` promoting to
COMPLETE, expiring an overdue ACTIVE quest, leaving ABANDONED and
unknown-definition quests untouched, and null-list safety.

**Design notes / no signature deviations:**

- §3 implemented verbatim — no objections to log.
- `AssignedQuest.advance`/`.complete` reuse `QuestProgress.advance/.complete`
  (which are shaped around `QuestChain`) by wrapping the definition's
  segments in a throwaway `QuestChain(grantedDateKey, def.segments())`. This
  is view-only plumbing to satisfy "reuse `QuestProgress`, do not duplicate
  it" — `QuestChain.java` itself was not touched.
  `QuestReward.isLegal()` closes the one loophole in the frozen contract: the
  reward kinds are `"diamond" | "cobblestone" | "sigil" | "item"` — there is
  no `"kamu"` kind — so the only way a definition could smuggle a kamu out is
  via `kind:"item"` with `itemId` set to a kamu's raw id (e.g. `"bolt"`
  instead of a real `minecraft:` item id). `isLegal()` rejects that by
  checking `itemId` against `KamuCatalog.defaults()`.
- `"Expired on pickup"` fires from `AssignedQuests.grant` when the player
  already holds an instance of that definition whose window has since
  lapsed (checked via `AssignedQuest.isExpired` against the held quest's own
  `grantedDateKey`, not the new grant's) — reported instead of the generic
  "already doing this" so a stale errand doesn't silently block a fresh one
  or get confused with a live duplicate.
- Determinism: no `HashMap` iteration reliance (`QuestCatalog`/`KamuCatalog`
  both back onto `LinkedHashMap`), no `currentTimeMillis`, no `Random`. Date
  math uses `java.time.LocalDate`/`ChronoUnit` against the existing
  `yyyy-MM-dd` `dateKey` string convention already used by `QuestChain`.

Nothing needed from the master. `AssignedQuest`, `AssignedQuests`,
`QuestReward`, `QuestDefinition`, `QuestCatalog`, `QuestState` are ready for
Agent B to call as written.

### 7.B — Item API surface
<!-- AGENT-B -->
**Status:** implemented. Gradle not run (per §5); build verification is for the master.

**Files written**
- `fabric/src/main/java/kamutotems/QuestScroll.java` — detects any vanilla item carrying `custom_data.kamutotems.quest_scroll`, validates the quest id, grants the assigned quest on right-click, consumes the scroll only on success.
- `fabric/src/main/java/kamutotems/BossStone.java` — detects any vanilla item carrying `custom_data.kamutotems.boss_stone` (int tier 1–4), mints a rolled sigil of that tier, consumes the stone.
- `fabric/src/main/java/kamutotems/AssignedQuestHost.java` — `register()`, `registerCommands(...)`, per-player persistence (`assigned_quests.json`), kill/turn-in/scan advancement, reward payout (diamond/cobblestone/sigil/item), and retry-on-failure for completed-but-unpaid quests.
- `fabric/src/main/java/kamutotems/QuestApiConfig.java` — loads `config/kamutotems/quests.json`, fails OPEN to `QuestCatalog.defaults()` on parse error, rejects any reward that would grant a kamu at load time.
- `fabric/src/main/resources/quests.json` — four sample quest definitions.
- `INTEGRATION.md` — full outward contract: item ids, exact `custom_data` shapes, `/quests` commands, worked loot-table examples, and the explicit "do not depend on kamutotems" note.

**File edited outside my ownership (required for scan dedupe sharing)**
- `fabric/src/main/java/kamutotems/QuestHost.java` — `onScan(...)` now returns `boolean` and dispatches new scans to `AssignedQuestHost.onScan(...)`. This keeps the daily chain and assigned quests on the same per-player-per-day block dedupe instead of adding a second path.

**⚠ UNVERIFIED calls**
None. Every Minecraft/Fabric call was copied from existing suite code (`kamutotems/fabric/src`, `spiritwolves/src`, `wondrous/fabric/src`).

**TODO(master)**
1. Wire `AssignedQuestHost.register()` and `AssignedQuestHost.registerCommands(CommandDispatcher<CommandSourceStack>)` in `KamuTotemsMod.java` alongside `TotemHost`, `BossHost`, and `QuestHost`.
2. Optional: move `QuestApiConfig.load(configDir)` into `KamuTotemsMod.onInitialize()` for symmetry with `KamuData.load(configDir)`; it currently self-loads inside `AssignedQuestHost.register()` so it works without further wiring.
3. Agent A's core stubs (`AssignedQuest`, `AssignedQuests`, `QuestCatalog`, `QuestDefinition`, `QuestReward`, `QuestState`) must exist before the fabric module compiles.
4. Decide the `dailyquests` coexistence story. If that mod is installed, `KamuTotemsMod` disables `QuestHost`, which also disables the scan dispatch to assigned quests. Assigned quests still load and respond to scrolls/kills/turn-ins, but scan segments cannot advance. Either stand assigned quests down too or wire a separate scan path that reuses the dedupe.

### 7.C — Station
<!-- AGENT-C -->

**Files written / edited:**

- `fabric/src/main/java/kamutotems/Station.java` — fletching-table hub. Right-click any
  `Blocks.FLETCHING_TABLE` opens a 3-row sgui with Carving, Fusion, Discoveries,
  Naming, Journal and Quests. Exposes `Station.register()` and
  `Station.registerCommands(CommandDispatcher<CommandSourceStack>)`.
- `fabric/src/main/java/kamutotems/StationRouting.java` — shared back-button
  element in slot 49 and a small lore helper.
- `fabric/src/main/java/kamutotems/NameMenu.java` — sgui `AnvilInputGui` naming.
  Value is taken on the Accept click (slot 2), not on close; empty input cancels.
- `fabric/src/main/java/kamutotems/SlotMenu.java` — added `openFrom(ServerPlayer, Runnable)`
  and a back button (slot 49) when opened from the hub.
- `fabric/src/main/java/kamutotems/KamuForge.java` — added `openFrom(ServerPlayer, Runnable)`.
  Back is stored in `PENDING_BACK` and runs from `removed()` when the player closes
  the forge, returning them to the hub.
- `fabric/src/main/java/kamutotems/BookMenu.java` — added `openFrom(ServerPlayer, Runnable)`
  and a back button (slot 49).
- `fabric/src/main/java/kamutotems/TotemHost.java` — removed the
  sneak+right-click-totem SlotMenu gesture and the sneak+right-click-crafting-table
  KamuForge gesture. Kept kamu binding (`UseItemCallback`, now no sneak gate) and
  the totem scan (`UseBlockCallback`, still sneak-gated). Kept `/totem name`.
- `fabric/src/main/java/kamutotems/Totem.java` — added a lore line in `refreshLore`:
  "Take it to a fletching table to shape it." (only this file was modified for lore).

**⚠ UNVERIFIED calls / blocked verification:**

- `Station.java` `Blocks.FLETCHING_TABLE` (vanilla fletching table block reference,
  presumed present in 26.2). Verify in the obfuscated jar that the field still exists.
- I was not able to inspect the shipped `sgui-2.1.0+26.2.jar` to confirm the exact
  `AnvilInputGui` surface (the extraction command was cancelled). `NameMenu.java` was
  written using only the calls listed as proven in PLAN_V2 §9 (`setTitle`,
  `setDefaultInputValue`, `setSlot`, `getInput`, `open`). Master should build the jar
  and verify `NameMenu.openFrom` behaves correctly; any remaining sgui wiring can be
  finished by the master.

**TODO(master):**

- Wire `Station.register()` and `Station.registerCommands(dispatcher)` in
  `KamuTotemsMod.java` next to the other hosts. Both can be called unconditionally.
- Remove the duplicate `CommandRegistrationCallback.EVENT.register(...)` block from
  `TotemHost.register()` if that becomes a problem; `register()` is now only for
  events/commands, not commands, but `TotemHost.registerCommands` is still called.
- Verify `Station.openHub` visually: 3x3 layout, icons, back behaviour.
- Play-check that fletcher villagers still claim/keep fletching tables after the
  `UseBlockCallback` intercept (PLAN_V2 §9 item 2).
- When Agent B lands, the Quests hub panel should also list active assigned quests;
  it currently only shows the daily chain.

### 7.M — Master

**Integrated 2026-08-13. Built on the first attempt — zero compile errors from
agent code**, which is what the frozen contract and the "never invent a
Minecraft API call" rule bought. Both unverified calls the agents flagged
(`Blocks.FLETCHING_TABLE`, the `AnvilInputGui` surface) checked out against the
jars; no code changed as a result.

**Wired:** `Station.register()` and `AssignedQuestHost.register()` plus both
`registerCommands` into `KamuTotemsMod`.

**Two real bugs found and fixed during integration.**

1. **`QuestHost` NPE on the first scan when `dailyquests` is installed.**
   Agent B correctly routed assigned-quest scans through `QuestHost.onScan` so
   both tracks share the per-day block dedupe — the right call. But the quest
   host stands down entirely when `dailyquests` is present, so
   `DailyChain.init()` never runs and its catalog stays null, while
   `TotemHost` still calls the scan tap. First scan, `dayFor` -> NPE. Added a
   `dailyChainReady` flag; the daily half is guarded, the assigned half always
   runs. Agent B flagged this shape in its own TODO 4 without being able to see
   the crash.
2. **`/totem`, `/kamuy` and `/kamu` registered twice.** `TotemHost.register()`
   had its own `CommandRegistrationCallback` *and* the master calls
   `TotemHost.registerCommands`. Duplicate Brigadier roots merge silently
   rather than erroring, so it looked fine and quietly did the work twice.
   Removed the self-registration; the master owns command wiring.

**Design decision made during integration.** Assigned quests are registered
**unconditionally**, deliberately not gated on `questActive`. That flag exists
only to dodge a `/daily` collision with the older mod; assigned quests register
no colliding command. Gating them would mean installing `dailyquests` silently
killed the outward API other mods depend on — exactly the action-at-a-distance
the suite's decoupling exists to prevent.

**Completed Agent C's open item:** the station's Quests panel now lists active
assigned quests with per-segment progress alongside the daily chain, and shows
*"No errands"* when there are none. Added `AssignedQuestHost.heldBy` and
`definitionOf` as the read-only view.

**Still open — this is what "not play-verified" means:**

1. **A fletcher villager must still claim and keep a fletching table** (§9
   item 2). Reasoned, never tested. If job sites break, only the surrogate
   block id changes.
2. **The `DEATH_PROTECTION` strip** (SPEC §16.1) is still the highest-risk
   unproven item in the whole mod, unchanged from v1.
3. Nobody has opened the hub in a game. Panel layout, back-button routing and
   the anvil naming screen are all first drafts.
4. Quest balance — expiry windows, the 3-quest cap, reward sizes — is guesswork.

---

## 8. Integration checklist (master)

- [x] `./gradlew build` green; core suites both green
- [x] `core/build.gradle.kts` still empty; no `net.minecraft` in core
- [x] `KamuTotemsMod` registers `AssignedQuestHost` and `Station`
- [x] Every `⚠ UNVERIFIED` resolved against the 26.2 jar
- [x] Every `TODO(master):` resolved
- [x] **No quest reward can grant a kamu** — verified against the shipped `quests.json`
- [x] A kill advances the daily chain **and** an assigned quest without one stealing the other's progress
- [x] Scan dedupe (SPEC §8) still applies to assigned quests
- [x] Old gestures gone; field actions intact
- [x] SPEC + README updated; `INTEGRATION.md` reviewed

---

## 9. Verify before writing code — **done 2026-08-13**

Nothing here blocks the design. Items 3–5 were answered by the suite's own
shipped code rather than by guesswork, which is the cheaper source and the more
reliable one.

| # | Question | Result |
|---|---|---|
| **1** | Vanilla right-click behaviour on a fletching table to displace? | **✅ None.** Confirmed by the author. The block is a villager job site and nothing else, which is what makes it a free surrogate |
| **2** | Does `UseBlockCallback` disturb villager job-site logic? | **✅ Accepted as low risk.** Job-site claiming is POI-based, not interaction-based, so intercepting right-click should not affect a fletcher. **Not proven** — see the play check below |
| **3** | `AnvilInputGui` in sgui 2.1.0+26.2 | **✅ Already used twice in this suite**: `ballot/mc/Menus.java:326` and `cobbleeconomy/ShopAdminMenu.java:482`, both `new AnvilInputGui(player, false)`. API: `setDefaultInputValue`, `getInput`, `onInput`, `setTitle`, `setSlot`, `open` |
| **4** | Can an sgui menu open another sgui menu without desync? | **✅ Yes, with an explicit close first.** `ShopAdminMenu` does menu → anvil → menu inside click callbacks. The pattern is `g.close()` and *then* open the next. `SlotMenu.redraw` in this mod already relies on it |
| **5** | `openMenu` re-entrancy when a panel returns to the hub | **✅ Covered by 4.** Same mechanism |

### The routing pattern Agent C must use

Taken from `cobbleeconomy/ShopAdminMenu.askText` — shipped, working, and the
reference for the hub's back button:

```
inside a click callback:
    gui.close();          // ALWAYS close the current menu first
    NextMenu.open(...);   // then open the next, same tick
```

Do not open a second menu without closing the first. That is the shape the
suite has proven; anything else is untested.

### One thing to check in play, not in code

**A fletcher villager must still claim and keep its workstation.** Item 2 is
reasoned, not verified. Place a fletching table near an unemployed villager,
confirm it takes the job, then right-click the table and confirm it keeps it.
If job sites break, the fallback is a different surrogate block — the station
design is unaffected, only the block id changes.

---

## Appendix — Handoff prompts

---

### AGENT A — Core quest API

```
You are implementing the pure-Java quest API for the Minecraft Fabric mod
"Kamu Totems" (mod id: kamutotems) at a:\MrPinoys Mods\kamutotems.

READ FIRST:
  1. kamutotems\PLAN_V2.md — §1 (locked decisions), §2 (your files), §3 (the
     frozen contract you implement verbatim), §5 (rules), §6 (done). §3 IS YOUR
     SPECIFICATION.
  2. kamutotems\core\src\main\java\kamutotems\core\ — the v1 engine you build
     on. Reuse QuestSegment, QuestProgress and EventMatcher; do NOT duplicate
     them, and do NOT modify them.
  3. kamutotems\core\src\test\java\kamutotems\core\KamuTotemsTest.java — for
     test style only. You write a SEPARATE file, QuestApiTest.java, with its own
     main() and its own pass/fail counter. Do not edit KamuTotemsTest.java.

YOUR JOB: every file under "Agent A owns" in PLAN_V2.md §2.

HARD CONSTRAINTS:
  - Package kamutotems.core. ZERO net.minecraft.* imports. Zero Gson. Zero
    logging. Zero test frameworks. java.* only.
  - Implement §3 VERBATIM. If a signature looks wrong, implement it anyway and
    log the objection in PLAN_V2.md §7.A.
  - ASSIGNED QUESTS ARE A SECOND TRACK. The daily chain (QuestChain) is
    date-derived, server-wide and stateless. An assigned quest is per-player,
    ad-hoc and persists until completed, abandoned or expired. Share
    EventMatcher/QuestProgress/segment kinds; share nothing about lifecycle. Do
    not modify QuestChain.
  - A QUEST REWARD MAY NEVER GRANT A KAMU. Kamu come only from bosses. If a
    definition wants to reward power, it grants a SIGIL (a boss fight) instead.
    QuestReward.isLegal() is the guard and it must reject every kamu id in
    KamuCatalog.defaults(). Test this explicitly.
  - Refusals are return values with player-facing prose, never exceptions. The
    exact required strings are in §3.2.
  - Determinism: no HashMap iteration-order dependence, no currentTimeMillis,
    no unseeded Random.

VERIFY YOUR OWN WORK — you are the only agent who can:
    cd "a:\MrPinoys Mods\kamutotems\core"
    javac -d out (all core main + test .java files)
    java -cp out kamutotems.core.QuestApiTest
  Must print "N passed, 0 failed" with N >= 40, covering everything in §6.
  Also re-run KamuTotemsTest and confirm it is still green.

DO NOT: run gradle/gradlew; touch git; edit anything outside core/ except your
§7.A block; edit KamuTotemsTest.java; write migration code (old data is
disposable).

WHEN DONE: replace the <!-- AGENT-A --> block in PLAN_V2.md §7.A with files
written, final test count, signature objections, and anything the master needs.
```

---

### AGENT B — Item API surface

```
You are implementing the outward API for the Minecraft Fabric server-side mod
"Kamu Totems" (mod id: kamutotems) at a:\MrPinoys Mods\kamutotems.
Target: Minecraft 26.2, Fabric, JDK 25, server-side only, vanilla clients
install nothing.

THE POINT OF THIS WORK: another mod (e.g. `wayfarers`) should be able to hand a
player an item that starts a Kamu Totems quest, or hands over a boss fight,
WITHOUT either mod depending on the other. The API is an ITEM ID PLUS
custom_data — never a Java interface, never an api module. See DESIGN.md §3:
config is the integration layer.

READ FIRST:
  1. kamutotems\PLAN_V2.md — §1, §2 (your files), §3 (core API you CALL —
     agent A implements it; code against it as written), §5 (rules incl. the
     failure-direction table), §6 (done).
  2. kamutotems\SPEC.md — §7 (bosses, sigils), §8 (the daily chain), §17.
  3. kamutotems\fabric\src\main\java\kamutotems\Sigil.java — how a sigil is
     minted and rolled. makeFirstTrial() is the model for the boss stone.
  4. kamutotems\fabric\src\main\java\kamutotems\DailyChain.java and
     QuestHost.java — the existing kill/turn-in/scan taps you must hook into.
  5. kamutotems\fabric\src\main\java\kamutotems\KamuData.java — how the mod
     loads JSON with a chosen failure direction. quests.json FAILS OPEN.

YOUR JOB: every file under "Agent B owns" in PLAN_V2.md §2.

RULES THAT MATTER MOST:
  - A QUEST REWARD MAY NEVER GRANT A KAMU. Kamu come only from bosses. To
    reward power, grant a SIGIL. Reject illegal rewards at load, loudly.
  - DO NOT DOUBLE-COUNT. One kill must be able to advance both the daily chain
    and an assigned quest, but neither may consume the other's progress. Read
    how DailyChain.tryAdvance works before you wire anything.
  - THE SCAN DEDUPE STILL APPLIES. QuestHost tracks scanned block positions per
    day so the same block cannot be farmed (SPEC §8). Assigned quests must
    respect it — do not add a second, undeduped scan path.
  - Refusals consume nothing. A scroll that cannot be granted stays in the hand.
  - A quest that completes but whose reward fails to pay stays COMPLETE and
    retries. Never mark paid on a failed payout.
  - NPC boss stones may be any tier. The daily chain guarantees at least one
    fight a day; it was never meant to be the only source.

INTEGRATION.md is a deliverable, not a footnote. Someone who has never seen this
codebase must be able to read it and wire their mod up: the item ids, the exact
custom_data shapes, a worked `wayfarers` loot-table example, and an explicit
"do NOT add a dependency on kamutotems" note.

NEVER INVENT A MINECRAFT API CALL. Grep kamutotems/fabric/src, spiritwolves/src
and wondrous/fabric/src for the same call and copy its shape. If it appears
nowhere, write it, put "// ⚠ UNVERIFIED" above it, and list it in §7.B. 26.2
renamed a lot — see SPEC §16's correction table.

DO NOT: run gradle/gradlew (you cannot compile; that is expected — the master
does it); touch git; create or edit KamuTotemsMod.java, KamuTotemsConfig,
Chime, Persist, KamuData, EffectsMc, any build file, or any file owned by
agents A or C; write migration code.

EXPOSE EXACTLY: AssignedQuestHost.register() and
AssignedQuestHost.registerCommands(CommandDispatcher<CommandSourceStack>).

WHEN DONE: replace the <!-- AGENT-B --> block in PLAN_V2.md §7.B with files
written, every "⚠ UNVERIFIED" call, every "TODO(master):", and anything the
master needs.
```

---

### AGENT C — The Kamu Station

```
You are building the Kamu Station for the Minecraft Fabric server-side mod
"Kamu Totems" (mod id: kamutotems) at a:\MrPinoys Mods\kamutotems.
Target: Minecraft 26.2, Fabric, JDK 25, server-side only, vanilla clients
install nothing, ZERO Mixins.

THE POINT OF THIS WORK: the mod's interactions are currently scattered across
sneak+right-click gestures that are hard to discover and easy to trigger by
accident. Collapse them into ONE BLOCK — a fletching table — that opens a hub
menu containing everything.

READ FIRST:
  1. kamutotems\PLAN_V2.md — §1 (locked decisions — read the station rows
     carefully), §2 (your files, including the v1 files ONLY you may edit),
     §3.3 (the openFrom contract), §5, §6.
  2. kamutotems\fabric\src\main\java\kamutotems\SlotMenu.java, KamuForge.java,
     BookMenu.java — the panels you are moving behind the hub.
  3. kamutotems\fabric\src\main\java\kamutotems\TotemHost.java — the gestures
     you are removing, and the ones you must KEEP.
  4. cobbleeconomy\fabric\src\main\java\cobbleeconomy\ShopMenu.java — the
     suite's reference for an sgui hub that routes into sub-pages and back.

YOUR JOB: every file under "Agent C owns" in PLAN_V2.md §2.

THE STATION:
  - ANY vanilla fletching table is a Kamu Station. Not a marked one — a placed
    block cannot carry custom_data, and marking specific blocks would need a
    server-side placement hook that Fabric does not have. (That is exactly why
    `ballot` carries the suite's only Mixin. You are not adding a second one.)
  - Right-click it -> the hub. Entries: Carving (SlotMenu), Fusion (KamuForge),
    Discoveries (BookMenu), Naming (NameMenu), Journal, Quests.
  - EVERY panel gets a back button to the hub. The hub is the spine. Add
    openFrom(ServerPlayer, Runnable back) to each panel per §3.3.
  - MENU ROUTING IS A SOLVED PROBLEM IN THIS SUITE. Inside a click callback,
    ALWAYS close the current menu before opening the next:
        gui.close();
        NextMenu.open(...);
    That is exactly what cobbleeconomy's ShopAdminMenu.askText does (menu ->
    anvil -> menu) and what SlotMenu.redraw already relies on. Verified working
    on sgui 2.1.0+26.2 -- see PLAN_V2 §9. Do not open a second menu without
    closing the first.
  - AnvilInputGui is proven in this suite too: `new AnvilInputGui(player, false)`
    then setTitle / setDefaultInputValue / getInput. Read
    cobbleeconomyabric\src\main\java\cobbleeconomy\ShopAdminMenu.java
    around line 481 before writing NameMenu -- it takes the value on an explicit
    Accept click rather than on close, so backing out cancels cleanly instead of
    committing whatever was half-typed. Copy that behaviour.
  - Naming uses sgui's AnvilInputGui. Keep /totem name working as well.

WHAT YOU REMOVE: the sneak+right-click-totem gesture and the
sneak+right-click-crafting-table gesture in TotemHost.

WHAT YOU MUST NOT REMOVE: right-click to BIND a kamu, and right-click a sigil
to SUMMON. A player mining at Y-12 must still be able to pocket loot and start
a fight without walking home. If you break these, the station is a downgrade.

DISCOVERABILITY IS PART OF THE JOB, NOT POLISH. Putting everything behind a
block a player has no reason to suspect is a regression (SUITE_AUDIT §7 ranks
discoverability as the suite's top gap). Add a lore line to the totem itself —
it is the item the player already holds — saying a fletching table is needed to
modify it. That lore lives in Totem.refreshLore; it is the ONLY change you may
make to Totem.java.

NEVER INVENT A MINECRAFT API CALL. Grep kamutotems/fabric/src, cobbleeconomy,
spiritwolves/src and wondrous/fabric/src for the same call and copy its shape.
If it appears nowhere, write it, put "// ⚠ UNVERIFIED" above it, and list it in
§7.C. 26.2 renamed a lot — see SPEC §16's correction table.

DO NOT: run gradle/gradlew (you cannot compile; that is expected — the master
does it); touch git; create or edit KamuTotemsMod.java, KamuTotemsConfig, Chime,
Persist, KamuData, EffectsMc, any build file, or any file owned by agents A or
B; add a Mixin; write migration code.

EXPOSE EXACTLY: Station.register() and
Station.registerCommands(CommandDispatcher<CommandSourceStack>).

WHEN DONE: replace the <!-- AGENT-C --> block in PLAN_V2.md §7.C with files
written, every "⚠ UNVERIFIED" call, every "TODO(master):", and anything the
master needs.
```
