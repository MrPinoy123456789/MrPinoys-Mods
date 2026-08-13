# Phase 1 — Implementation Plan

> **Scope.** `ROADMAP.md` Phase 1 (P1.1–P1.7), broken into seven independently
> shippable tasks with file paths, exact insertion points, and acceptance
> criteria. Nothing from Phase 2 or 3 is in scope.
>
> **Goal this phase serves.** Make the four systems that already announce
> themselves *true and good*, then signpost the five that are gated behind
> `/shop`. Per `ROADMAP.md` §1, that alone plausibly delivers outcome (1):
> a new player meeting ≥5 of 10 systems in their first session.
>
> **Written:** 2026-08-11, against `ROADMAP.md` and `SUITE_AUDIT.md`. Three of
> the roadmap's premises were re-verified against source before planning; two
> did not hold (§0).

---

## 0. Pre-flight corrections — verified against source, 2026-08-11

**These change the shape of two Phase 1 tasks. Read before starting.**

1. **`ROADMAP.md` §0.3 — "CONFIRMED: `dailyquests` has never been compiled or
   run" — is WRONG.** It compiles and ships:
   - `dailyquests/build/classes/java/main/dailyquests/*.class` — 2026-08-08 00:36
   - `dailyquests/build/libs/MrPinoys_dailyquests-0.1.0.jar` — 2026-08-08 00:36
   - `dist/MrPinoys_dailyquests-0.1.0.jar` — same build

   Source mtimes (00:34) precede the class mtimes, so the current sources are
   what compiled. The README's "never been compiled or run" line is stale.

   **Consequence:** **P1.1 is not a compile rescue. It is a live-play
   verification pass**, and it is the one task in this phase that needs a human
   with a game client. That is why it is scheduled last (T7), not first.

2. **`ItemStack.is(Item)` works fine in 26.2.** `chatdonkey/PROGRESS.md` claims
   it "no longer exists"; `SUITE_AUDIT.md` repeated the claim. Verified by
   compiling `stack.is(item)` against
   `minecraft-merged-deobf-26.2.jar` — clean, emitting
   `ItemStack.is:(Ljava/lang/Object;)Z`. `dailyquests/TurnIn.java` already uses
   it in shipped bytecode. `stack.getItem() == Items.X` also works.
   **Do not "fix" working `is(...)` call sites.** Both audit files have been
   corrected; `chatdonkey/PROGRESS.md` is corrected in T1.

3. **`ROADMAP.md` §0.1 and §0.2 confirmed.** `quizengine/QuizMod.java` does wire
   `ServerPlayConnectionEvents.JOIN` → `orchestrator.greet(...)`, and
   `bounties/BountyMod.java` lines 60–66 do broadcast on window change. Both
   remain as the roadmap describes.

---

## 1. Ground rules for whoever implements this

Non-negotiable, inherited from `DESIGN.md` and confirmed against ten mods:

1. **`"environment": "server"`. Vanilla clients install nothing.** No registry
   entries, no resource packs. Custom items are vanilla items carrying
   `minecraft:custom_data`.
2. **No mod may import or depend on another mod.** Not in Java, not in
   `fabric.mod.json`. Phase 1 adds zero cross-mod coupling — a mod may *mention*
   a command name in a string it writes itself, which is text, not a dependency.
3. **No Mixins.** Nothing in Phase 1 needs one.
4. **`readOrCreate` semantics.** Missing config → write defaults and log. Config
   that fails to parse → use defaults in memory and **never overwrite the file**.
   Canonical implementation: `chatdonkey/core/.../ReadOrCreate.java`.
5. **`giveOrDrop`.** Add to inventory, drop at feet if full. Never destroy a
   reward.
6. **Anything testable without Minecraft belongs in the `core` module**, and gets
   a test. The `core` build script is deliberately empty so a Minecraft import
   fails compilation.
7. **Sounds are confirmations, not fanfares.** Reuse the mod's existing
   `Chime.java`, volume 0.15–0.4.
8. **Rejections are return values, not exceptions.**

**Build, per mod:**

```bash
cd "a:/MrPinoys Mods/<mod>"
./gradlew build          # jar auto-copies into ../dist/
```

> **⚠ The trap that has bitten every mod in this suite.** The `dist` task must
> depend on **`jar`**, never `remapJar`. 26.2 ships unobfuscated so Loom
> registers no `remapJar` task. Don't "fix" a `dist` task by adding it.

**Core tests, no Gradle needed** (each is a `main()` printing `N passed, 0 failed`):

```bash
# chatdonkey
javac --release 25 -d build core/src/main/java/chatdonkey/core/*.java core/src/test/java/chatdonkey/core/*.java
java -cp build chatdonkey.core.ChatDonkeyTest      # expect 353+ tests, 0 failed
```

**Per-task discipline:** one task per session. Build after each. Do not refactor
neighbouring code. Do not touch a mod the task doesn't name. If a task turns out
to need a design decision that isn't written down here, stop and ask rather than
inventing one.

---

## 2. Task cards

Ordered for execution. T1–T6 are implementable without a game client; T7 needs
a human and a server.

---

### T1 — Correct the stale documentation *(P1.7)*

**Why first.** Every task after this, and every future agent session, inherits
these files as fact. Two of them currently assert things that are false.

**Files and exact edits:**

| File | Edit |
|---|---|
| `cobblebending/SPEC.md` | Status header says *"design complete, not yet implemented."* It **is** implemented — all 12 specced classes exist in `src/main/java/cobblebending/` and `dist/MrPinoys_cobblebending-0.1.0.jar` exists. Change to: implemented 2026-08-08, build-verified, **not play-verified**; §16 Definition-of-done boxes remain unchecked pending play. |
| `disenchanter/SPEC.md` | Add a status block at the top: this shipped **inside `wondrous`** as the `pocket_disenchanter` item (`wondrous/fabric/src/main/java/wondrous/DisenchantMenu.java`), not as a standalone mod, and per `ROADMAP.md` §6.2 it will never be built as one. Keep §8's verify-list — it is a useful record. |
| `DESIGN.md` §1 | The suite table lists 7 mods. Add `chatdonkey` (random-event interrupt / faucet / session loop), `cobblebending` (power fantasy / cobblestone sink / session loop). Note the Disenchanter shipped inside `wondrous`. |
| `wondrous/README.md` | Item table lists 9; the code has 12 (`Definitions.ALL`). Add `pocket_disenchanter` (Soul Grinder), `pocket_smelter` (Melty Pocket), `boomerang_ball` (Boomerang Pet Ball). In the "Using it from another mod" section, add a note that the api-module pattern **is frozen and should not be copied** — new items sell through `cobbleeconomy`'s `shop.json` `components` block (`DESIGN.md` §3). |
| `chatdonkey/PROGRESS.md` | The M2 section states *"`ItemStack.is(Item)` no longer exists — only `is(Predicate<Holder<Item>>)`."* This is false (§0.2 above). Correct it in place, noting the verification method, so nobody "fixes" working call sites. |
| `dailyquests/README.md` | The "Untested" section says it has never been compiled or run. It compiles and ships a jar. Rewrite to: builds clean as of 2026-08-08; **never verified with players on a live server** — which is T7. |
| `SUITE_AUDIT.md` | Already corrected for the two items above. Additionally fold in `ROADMAP.md` §0.1 and §0.2 so §2.2 stops claiming `greet()` is unwired and §2.3 stops claiming bounties never announces rotation. |

**Acceptance:** no file in the repo asserts any of: dailyquests never compiled;
cobblebending unimplemented; `ItemStack.is(Item)` missing; disenchanter unbuilt;
bounties silent on rotation; `greet()` unwired.

**Do not:** restructure any document, or change `DESIGN.md` §2's coupling rule
(the facts-contract amendment is Phase 2, not now).

---

### T2 — Fill the quiz content pool *(P1.2)*

**Why.** 26 trivia questions against ~48 auto rounds/day repeats within hours.
Per `ROADMAP.md` §1, signposting a system that disappoints is negative leverage
— so this precedes every discoverability task that points at quizengine.

**This is a content task. Zero Java.**

**Files:** `quizengine/fabric/src/main/java/quizengine/mc/Content.java` holds the
seeded defaults (`sampleQuestions()` / `samplePrompts()`), but the live files
are `config/quizengine/trivia.json` and `prompts.json`, hot-reloadable via
`/quiz reload`. **Write the new content into the seeded defaults** so a fresh
server gets it, and also produce standalone `trivia.json` / `prompts.json` files
in the repo (suggest `quizengine/content/`) that an operator can drop into an
existing server's config directory — the never-overwrite rule means an existing
server will not pick up new defaults on its own.

**Schemas — exact, from `Content.java`:**

```json
// trivia.json
{ "questions": [
  { "prompt": "…", "options": ["a","b","c","d"], "correct": 2 }
]}
```
`correct` is a **0-based index** into `options`. Verify this against
`Content.QuestionEntry` and `Engine` scoring before generating 300 of them —
an off-by-one here silently marks every answer wrong.

```json
// prompts.json
{ "prompts": [ { "prompt": "…" } ]}
```

**Targets:** trivia 26 → **300+**, Quiplash prompts 4 → **100+**.

**Content rules:**
- Trivia must be answerable by a Minecraft player without a browser. Mix
  Minecraft knowledge, general knowledge, and pop culture. Avoid anything with a
  time-sensitive answer.
- Exactly 4 options. No "all of the above". Wrong options should be plausible.
- Quiplash prompts are fill-in-the-blank setups players write punchlines for —
  short, absurd, no correct answer. Match the existing four in tone.
- No duplicate prompts. No answer that appears in the prompt text.

**Acceptance:**
- Both JSON files parse (`python -m json.tool` or equivalent).
- ≥300 questions, ≥100 prompts, zero duplicate `prompt` strings.
- Every question has exactly 4 options and `0 <= correct <= 3`.
- Write a throwaway validation script for the above; report the counts.
- `./gradlew build` in `quizengine/` still clean.

**Do not:** change the schema, the scoring, or `timings.json`.

---

### T3 — Enrich the bounty rotation announcement *(P1.5)*

**Why.** The broadcast exists but says nothing useful: *"A new bounty is
available on the bounty board!"* — no target, no reward, and no way to act on it
without knowing `/bounty` exists.

**File:** `bounties/fabric/src/main/java/bounties/BountyMod.java`, the
`ServerTickEvents.END_SERVER_TICK` block at ~lines 58–67 (the
`window != lastWindow` branch).

**Change:** replace the single literal with a component that includes:
1. What the new board slot actually is — pull the definition via the same
   `BountyMath`/`Board` path `/bounty` uses to render the board, and include its
   `displayDescription()` and `rewardDiamonds()`.
2. A **clickable** `[ View board ]` suffix running `/bounty`, via
   `ClickEvent` + `HoverEvent` on a `Component`. Chat-as-UI is house style —
   copy the construction pattern from `bounties/BountyCommands.java` or
   `dailyquests/DailyCommands.java`.

**Also (per `ROADMAP.md` §5, "bounty slots" pre-monetization seam):** move the
hard-coded 3-held-bounty cap into `bounties.json`'s settings (or a new
`settings.json` in `config/bounties/`, following `readOrCreate`). **Ship the
default as 3.** No slot-expansion item, no shop listing — only the config seam,
so a later phase can raise it or sell it without touching code.

**Acceptance:**
- Rotation broadcast names the bounty and its diamond reward, and the
  `[ View board ]` button runs `/bounty`.
- The held-bounty cap is read from config, defaults to 3, and the existing core
  tests still pass (`javac`-and-run per §1; expect 31 tests, 0 failed).
- If the cap lives in `core`, add a test that a config value of 5 admits five
  held bounties and a 6th is rejected.
- `./gradlew build` clean.

**Do not:** change the rotation cadence or the :15/:45 stagger — that offset
exists so bounty announcements never collide with quizengine's.

---

### T4 — First-join signpost for `/shop` *(P1.3)*

**Why.** This is the highest-leverage single change in Phase 1. Five systems
(spiritwolves, cobblebending, wondrous, the disenchanter, cobbleeconomy itself)
are reachable **only** through `/shop`, and nothing anywhere tells a player
`/shop` exists.

**Files:**
- `cobbleeconomy/fabric/src/main/java/cobbleeconomy/LoginSnapshot.java` — already
  owns delayed post-join messaging, keyed by UUID, with a tick-based due time and
  safe handling of a player who disconnects during the delay. **Extend this
  class; do not build a second scheduler.**
- `cobbleeconomy/fabric/src/main/java/cobbleeconomy/CobbleEconomyMod.java` —
  the `ServerPlayConnectionEvents.JOIN` handler at ~lines 91–94.

**First-join detection.** `names.see(uuid, name)` is called on every join, one
line *before* `loginSnapshot.onJoin(...)`. A UUID absent from `NameCache` before
that call has never joined this server. **Capture "was this UUID already known?"
by querying `NameCache` before `see()` is called**, and pass the boolean into
`onJoin`. Do not add a schema field to `accounts.json` — that file refuses to
load if it fails to parse, and there is no reason to put a cosmetic flag behind
that risk.

Add a `NameCache.knows(UUID)` accessor if one doesn't exist (`byId.containsKey`).

**What to send.** On first join only, after the existing delay (reuse
`loginDelayTicks`; send the welcome *instead of* the wealth snapshot, since a
brand-new player has no rank worth showing):

```
─────────────────────────────
Welcome. Two things are money here.
  🪨 Cobblestone — common. Bank it with /bank all
  💎 Diamond — premium. Earned from quizzes, bounties and riddles
Type /shop to see what the server sells.
  [ Open the shop ]        ← clickable, runs /shop
─────────────────────────────
```

Reuse `Messages.rule()` / `Messages.title()` / the existing styles rather than
inventing formatting. One `Chime` note at the end (volume ≤0.3).

**Make the copy config.** Add a `welcome` block to `settings.json`
(via the existing `EconomySettings` + `readOrCreate` path):
`enabled` (default true), `delayTicks` (default: reuse `loginDelayTicks`), and
`lines` (a list of strings, defaulting to the above). An operator must be able to
retune the wording without a rebuild — and later phases will want to add a line
per new system.

**The coupling boundary, stated explicitly:** cobbleeconomy describes **its own
shop and its own currencies**. It must not name `spiritwolves`,
`cobblebending`, or any other mod in code. The hook line ("wolves that cheat
death are in there") belongs in the *shop listing's lore*, which is already
config, not in this welcome message.

**Acceptance:**
- A UUID never seen before gets the welcome; the same UUID on second join gets
  the normal wealth snapshot instead.
- A player who disconnects during the delay causes no error (existing `pending`
  logic already handles this — confirm your path goes through it).
- `welcome.enabled = false` restores exactly the current behaviour.
- Add core-side tests if any of the logic lands in `core`; otherwise state
  plainly in the PR notes that this path is untested and needs T7's live pass.
- `./gradlew :core:coreTest` still passes; `./gradlew build` clean.

**Do not:** send this on every join, put it before the delay (it will scroll away
behind the MOTD — that's the documented reason the delay exists), or add a field
to `accounts.json`.

---

### T5 — The donkey as herald: static lines *(P1.4)*

**Why.** The donkey is the only system that walks up to the player, targets
*active* players specifically, and cannot be outrun. It is the best delivery
vehicle in the suite for "here is what else exists."

**⚠ The roadmap calls this "config only." It isn't, quite — and the reason
matters.** `LinePools.withDefaults(fallback)` merges **missing pools** from the
stock set in memory, but it does **not** merge missing *lines into a pool that
already exists*. So adding herald lines to `lecture.during` would never reach any
server whose `lines.json` was already generated. Herald lines must live in a
**new pool**, which `withDefaults` *will* merge in.

**Files:**
- `chatdonkey/core/src/main/java/chatdonkey/core/DefaultLines.java` — add the
  new pool.
- `chatdonkey/core/src/main/java/chatdonkey/core/AbstractBehavior.java` — the
  `during`-line scheduling all behaviours inherit.
- `chatdonkey/core/src/main/java/chatdonkey/core/Settings.java` — one new knob.
- `chatdonkey/core/src/test/java/chatdonkey/core/ChatDonkeyTest.java` — tests.

**Implementation:**

1. **New shared pool `herald`** in `DefaultLines` (bare, not behaviour-scoped —
   same category as `names`, `hit`, `deny`). Ship 10–14 lines. In character:
   aggrieved, self-important, convinced it is helping. They must mention a
   system *without* reading as an advertisement:
   - *"They sell ghost-wolf stones in the shop, you know. Not that YOU could afford one."*
   - *"There's a riddle every day. You look like someone who loses at riddles."*
   - *"Somebody told me you can throw ROCKS with your MIND now. I said that's MY act."*
   - *"There's a bounty board. Full of things that would eat you."*

2. **Draw from it occasionally.** In `AbstractBehavior`'s `during`-line
   selection, with probability `heraldChance` pick from `herald` instead of the
   behaviour's own `during` pool. Default **0.15**. Guard: never as the event's
   *first* line (the opener establishes the bit), and at most **one** herald line
   per event.

3. **New setting** `heraldChance` in `settings.json` via the existing
   `Settings` record + `readOrCreate`. `0.0` disables the feature entirely.

**Why this shape.** It is §2-safe — the donkey speaks operator-authored text and
imports nothing. And it builds the exact seam Phase 2's facts contract
(`ROADMAP.md` §3) plugs into: P2.4 replaces "pick a static line from `herald`"
with "pick a live fact, falling back to `herald`." Keep the selection behind one
method so P2.4 is a one-site change.

**Acceptance:**
- New core tests: (a) `heraldChance = 0` never emits a herald line;
  (b) `heraldChance = 1.0` emits at most one per event and never as the opener;
  (c) the coverage test that walks every behaviour × every `EndReason` still
  passes; (d) a server whose `lines.json` predates this release still gets the
  `herald` pool via `withDefaults`.
- Full core suite passes (expect **353 + your new tests**, 0 failed).
- `./gradlew build` clean.

**Do not:** add a facts file, a config-directory scan, or any reading of another
mod's state. That is P2.4 and it requires the `DESIGN.md` §2 amendment first.

---

### T6 — Streak visibility and reward ladder *(P1.6)*

**Why.** Streaks are the strongest retention primitive in the suite after
attachment, and they are currently **private** (nobody sees anyone's streak) and
**flat** (the reward stops growing at day 3 while the streak keeps counting —
the stake stops rising exactly when loss aversion should start biting).

**Files:** `dailyquests/src/main/java/dailyquests/` — `TurnIn.java` (payout and
messages), `DailyCommands.java` (new subcommand), `DailyState.java` (already has
`streakOf`, `entryOf`, `Entry(name, lastDay, streak, totalDone)` — enough to
build a board from), `Quests.java` (`Settings`).

**Three changes:**

1. **Broadcast milestones.** In `TurnIn.attempt`, on a completion whose new
   streak hits a milestone (7, 14, 30, then every 30), broadcast to the server:
   *"Kris is on a 7-day streak."* in gold. Milestones list in config. Ordinary
   days stay private — broadcasting every turn-in is spam, and the scarcity is
   what makes the milestone land.

2. **`/daily top`.** A paginated board of current streaks, built from
   `DailyState`'s entries, sorted by streak then `totalDone`. Follow
   `cobbleeconomy`'s `/baltop` presentation conventions (rule, title, rank lines,
   caller highlighted). Player-level permission.

3. **Ladder the reward instead of capping it flat.** Replace the single
   `streakDiamondCap` with a config ladder in `settings.json`, e.g.
   `streakRewards: [{"day": 1, "diamonds": 1}, {"day": 7, "diamonds": 3},
   {"day": 14, "diamonds": 5}, {"day": 30, "diamonds": 8}]` — the payout is the
   highest entry whose `day` ≤ current streak.
   - **Migration:** an existing `settings.json` carrying only `streakDiamondCap`
     must keep working. Extra/absent keys are ignored by Gson, so read the old
     field when the ladder is absent and synthesise a flat ladder from it. Never
     rewrite the operator's file (§1.4).
   - Per `DESIGN.md` §5, err generous: a 30-day streak is genuinely rare, and
     the payout is gated by a hard once-per-day rate limit, so it cannot scale
     with a machine.

**Acceptance:**
- Turn-in at streaks 1/6/7/8/14/30 pays the ladder's value; milestone broadcasts
  fire at 7/14/30 and nowhere else.
- A `settings.json` containing only the old `streakDiamondCap` loads and behaves
  as before.
- `/daily top` renders with 0, 1, and many players.
- `./gradlew build` clean.

**Note:** `dailyquests` is single-module, so there is no `core` to test in. Keep
the ladder lookup and the milestone predicate as **static pure functions** so
they can be tested later if a core module ever appears, and state in the PR notes
that they are unit-untested.

**Do not:** change the date-derivation of today's quest, or the completion-as-
date-comparison design. Both are load-bearing against restarts.

---

### T7 — Live play-verification of `dailyquests` *(P1.1)*

**Owner: the human, not the coding agent.** This needs a real client. The agent's
job is to make it a 20-minute job instead of a two-hour one.

**Agent prepares:**
1. A `dailyquests/run/` dev-server directory following the pattern already set up
   in `chatdonkey/fabric/run/` (EULA accepted, flat world, offline mode), so
   `./gradlew runServer` starts something immediately.
2. A `config/dailyquests/settings.json` tuned for fast testing, with a comment in
   the checklist explaining what to set back.
3. A written **test checklist**, in execution order, in
   `dailyquests/VERIFY.md`:
   - Server boots; `config/dailyquests/` generates with `quests.json`,
     `settings.json`, `state.json`.
   - `/daily` renders today's riddle; the turn-in button is clickable.
   - Turn-in with too few items → refusal, **nothing consumed** (check inventory).
   - Turn-in with enough → correct count consumed, diamonds granted, answer
     revealed, chime plays.
   - Turn-in twice the same day → refused.
   - Turn-in with a full inventory → diamonds **drop at feet**, nothing lost.
   - `announceOnJoin` fires on reconnect.
   - Restart mid-day → same riddle, streak intact.
   - Advance `rolloverHourUtc` to force a rollover → new riddle, streak increments.
   - `/daily streak`, `/daily top` (T6), `/daily reload`.
   - **`TurnIn.lookup()`** with a deliberately bogus item id in `quests.json` →
     logged error and the "misconfigured" message, **not** a crash.

**Acceptance:** `VERIFY.md` exists and every box is checked by a human on a
running server. Any failure becomes a bug fix in this task before the phase is
called done.

**Why last:** T6 changes this mod, so verifying before T6 would mean verifying
twice.

---

## 3. Sequencing

```
T1 (docs)  ──►  T2 (quiz content)  ──┐
                                     ├──►  T7 (live verify)
T3 (bounty)  ──►                     │
T4 (welcome) ──►                     │
T5 (herald)  ──►                     │
T6 (streaks) ─────────────────────────┘
```

T1 first (everything downstream inherits its facts). T2 before any signposting
that points at quizengine. T3–T6 are mutually independent and can be done in any
order or in parallel sessions. T7 last, after T6 has finished changing
`dailyquests`.

**Suggested commit granularity:** one commit per task, message naming the task
(`T4: first-join /shop signpost`). The repo is currently one commit on `master`
with a large dirty tree, much of it Gradle caches and build output —
**consider a `.gitignore` for `build/`, `.gradle/`, and `run/` before starting**,
otherwise every task's diff is unreadable. That cleanup is not part of any task
above; do it deliberately or not at all.

---

## 4. Phase 1 definition of done

- [ ] No repo document asserts any of the six false facts listed in T1.
- [ ] `trivia.json` ≥300 questions, `prompts.json` ≥100 prompts, both validated.
- [ ] Bounty rotation broadcast names the bounty and reward and has a working
      `[ View board ]` button; held-slot cap is config, default 3.
- [ ] A first-time player is told what the currencies are and that `/shop`
      exists, once, after the join delay, with a working button.
- [ ] The donkey occasionally mentions another system, from a `herald` pool that
      reaches pre-existing servers via `withDefaults`, at a configurable rate,
      never as an opener, at most once per event.
- [ ] Streak milestones broadcast at 7/14/30; `/daily top` works; the reward
      ladders instead of flat-capping; old `settings.json` files still load.
- [ ] `dailyquests/VERIFY.md` fully checked on a live server.
- [ ] All ten mods still build; every core suite still passes at ≥ its previous
      count (chatdonkey 353, ballot 148, quizengine 68, bounties 31,
      cobbleeconomy ~200 assertions).
- [ ] No new cross-mod dependency in any `fabric.mod.json`. No new Mixin.

**What Phase 1 does not deliver, deliberately:** the facts contract and live
heralding (P2.4), ballot automation (P2.1), rotating shop stock (P2.2), sticky
donkey names (P2.3), streak freeze (P2.5). All Phase 2.
