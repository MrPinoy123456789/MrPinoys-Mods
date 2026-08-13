# Wayfarers — Implementation Plan

> **Reads with:** `SPEC.md` in this folder. The spec is the design authority;
> this is the build order. Where they disagree, the spec wins and the
> disagreement gets reported.
>
> **Scope:** v1 only — three encounters (Wayfarer, Pillager Patrol, Stray
> Donkey), two Java templates, one persisted record. `SPEC.md` §16.
>
> **Written:** 2026-08-12.

---

## 0. Copy, don't invent

**Most of this mod already exists in the other ten.** Roughly 60% of v1 is
proven, play-tested code sitting in `chatdonkey`, `cobbleeconomy` and
`spiritwolves`. The suite deliberately duplicates rather than sharing libraries
(`DESIGN.md` §2), so copying is the *correct* move here, not a shortcut.

**Read the source file before writing the new one, every time.**

| New file | Copy from | What changes |
|---|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew*` | `chatdonkey/` (it already has the core/fabric split) | Names, `archivesName`, add the sgui dependency |
| `core/ReadOrCreate.java` | `chatdonkey/core/src/main/java/chatdonkey/core/ReadOrCreate.java` | Package declaration only. Do not modify the logic |
| `core/LinePools.java` | `chatdonkey/core/.../LinePools.java` | Pool naming (`<encounter>.<moment>`), keep the fallback behaviour and `withDefaults` |
| `core/TriggerRules.java` | `chatdonkey/core/.../TriggerRules.java` | Add the "not near an encounter already running for this player" gate (`SPEC.md` §8) |
| `fabric/Spawns.java` | `chatdonkey/fabric/.../DonkeySpawn.java` | `MIN_DISTANCE` 4→**28**, `MAX_DISTANCE` 8→**64**. Keep `findFooting` and `isClear` exactly |
| `fabric/OrphanSweep.java` | `chatdonkey/fabric/.../OrphanSweep.java` | Tag name. **Keep the queue-and-judge-next-tick logic** — see §M2 |
| `fabric/Chime.java` | `dailyquests/src/main/java/dailyquests/Chime.java` (smallest) | Sound events per `SPEC.md` §12 |
| `fabric/TradeGui.java` | `cobbleeconomy/fabric/.../ShopMenu.java` | Buy *and* sell; no bank balance; physical payment |
| `fabric/ItemComponents.java` | `cobbleeconomy/fabric/.../ItemComponents.java` | **Copy wholesale.** This is what parses a listing's `components` block into a `DataComponentPatch`. Do not rewrite it |
| `fabric/TraderRegistry.java` | `spiritwolves/src/main/java/spiritwolves/PlayerWolfRegistry.java` | Record shape (`SPEC.md` §9.1). Keep the atomic write, dirty-flush and `.dat.corrupt` quarantine |
| Donkey chest calls in `Patient.java` | `chatdonkey/fabric/.../DonkeyCoat.java` | Nothing — the four calls are identical |
| Payment semantics | `cobbleeconomy/core/.../Wallet.java` (`planRemoval`) | Physical items, main 36 slots only. Plan-then-apply, never partial |
| Test harness style | `chatdonkey/core/src/test/java/chatdonkey/core/ChatDonkeyTest.java` | **No JUnit.** A `main()` that prints `N passed, 0 failed` |

---

## 1. Milestones

Seven, each independently buildable and verifiable. **Do one per session.**
Do not start the next until the current one's acceptance list is fully checked.

---

### M0 — Scaffold

**Goal:** a jar that loads, logs one line, and lands in `dist/`.

**Create:** `settings.gradle.kts`, `gradle.properties`, root/`core`/`fabric`
`build.gradle.kts`, the Gradle wrapper, `fabric/src/main/resources/fabric.mod.json`,
and `fabric/src/main/java/wayfarers/WayfarersMod.java` (entrypoint, logs the mod
name and version on init).

**Copy the build from `chatdonkey`.** Then:
- `archivesName = "MrPinoys_wayfarers"`
- `maven_group=wayfarers`, same `minecraft_version` / `loader_version` /
  `fabric_api_version` as every other mod — do not "update" them
- Add sgui to the **fabric** module only:
  `implementation("eu.pb4:sgui:2.1.0+26.2")` and `include(...)`, with
  `maven("https://maven.nucleoid.xyz")` in `repositories`
- `core/build.gradle.kts` must stay **empty of dependencies**. That emptiness is
  what makes a stray `import net.minecraft.*` fail to compile, and it is
  deliberate
- `fabric.mod.json`: `"environment": "server"`, entrypoint
  `wayfarers.WayfarersMod`, `depends` on fabricloader / minecraft / java /
  fabric-api. **No `suggests`. No other mod named anywhere.**

> **⚠ The bug every mod in this suite has hit — eight times now.** The `dist`
> Copy task must `dependsOn("jar")`, **never `remapJar`**. 26.2 ships
> unobfuscated so Loom registers no `remapJar` task and depending on it fails the
> build outright. If you see a build error mentioning `remapJar`, you added it.

**Acceptance:**
- [ ] `./gradlew build` succeeds from `a:/MrPinoys Mods/wayfarers`
- [ ] `dist/MrPinoys_wayfarers-0.1.0.jar` exists
- [ ] `core/build.gradle.kts` declares no dependencies
- [ ] Zero compiler warnings

---

### M1 — The core model, fully tested, no Minecraft

**Goal:** every rule in `SPEC.md` §5 and §7 implemented as pure Java and under
test. This is the largest milestone and the one that needs no game to verify.

**Create in `core/src/main/java/wayfarers/core/`:**

| File | Owns |
|---|---|
| `EncounterDefinition.java` | The §5 schema as a record. Unknown blocks tolerated |
| `EncounterPool.java` | Weighted selection; `weight: 0` disables; biome/night/weather/`once` filters; skips unknown `template` with a warning rather than throwing |
| `Listing.java` | One `sells`/`buys` row — `item`, `quantity`, `price`, `currency`, `stock`, `components` (as a raw JSON string, parsed fabric-side) |
| `Purchase.java` | Count-first pricing, stock decrement, `coin` depletion, and the refusal reasons as a returned enum |
| `TriggerRules.java` | Cooldowns, server-wide cap, activity gate, proximity gate |
| `Wager.java` | Odds and payout (defined now, unused in v1) |
| `LinePools.java` | Dialogue with pool fallback. **A pool entry is a string *or* a script** — `SPEC.md` §5.3 |
| `Script.java` | Ordered steps, `say`/`narrate`/`wait`/`speaker`/`action`, beat timing |
| `RateLimit.java` | The §5.3 budget — floor claiming, one bubble per speaker, area caps. Copy `chatdonkey/core/.../RateLimit.java` as the starting shape |
| `ReadOrCreate.java` | Copied |
| `TraderRecord.java` | The §9.1 record as plain fields. **No NBT here** — `core` has no Minecraft |
| `Currencies.java` | The two allowed currencies as an enum or constants. **Diamond and cobblestone only** |

**Rules that must be encoded here, not fabric-side:**
1. **Count first, remove second.** A purchase that cannot be paid in full returns
   a refusal and changes nothing. Model the removal as a *plan* that a caller
   applies, the way `cobbleeconomy`'s `Wallet.planRemoval` returns `null` rather
   than a short plan.
2. **Cobblestone prices over 640 produce a config warning** at load (`SPEC.md`
   §7.3). Ten stacks is the practical carry ceiling.
3. **Only diamond and cobblestone are valid currencies.** Any other value in a
   listing is a config error that disables that listing, not the encounter.
4. `core` names items as **id strings**. It must not know what a `minecraft:diamond`
   is — that is the trick `bounties` uses for mob ids and it is what keeps
   Minecraft off this classpath.

**Tests** in `core/src/test/java/wayfarers/core/WayfarersTest.java`, house style —
a `main()` printing `N passed, 0 failed`. Cover at minimum:
- Weighted selection is proportional over many rolls; `weight: 0` never selected
- An unknown `template` is skipped, not thrown
- `once` encounters are never re-selected for a record that has seen them
- Purchase: exact payment, overpayment, underpayment, zero stock, exhausted coin
- **Purchase leaves state untouched on every refusal path** — the important one
- Cobble price over 640 warns; a listing in an unknown currency is disabled
- Trigger: cooldown, cap, activity gate, proximity gate
- Line pool falls back when a moment's pool is missing
- **A pool mixing plain strings and script objects parses; both kinds are
  selectable** (`SPEC.md` §5.3)
- **A script yields its steps in order with the right beat between them**, and
  `speaker` indices are preserved
- **A running script holds the floor** — a second script requested mid-play is
  refused, not interleaved
- **One bubble per speaker:** a new line replaces, never stacks
- **A line over the character cap splits** into sequential bubbles at sensible
  word boundaries
- **Shuffle bag:** drawing N times from an N-line pool yields every line once
  before any repeats (`SPEC.md` §5.3)

**Acceptance:**
- [ ] `javac --release 25 -d build core/src/main/java/wayfarers/core/*.java core/src/test/java/wayfarers/core/*.java` compiles clean
- [ ] `java -cp build wayfarers.core.WayfarersTest` prints `0 failed`
- [ ] At least 60 assertions
- [ ] `grep -r "net.minecraft" core/src` returns nothing
- [ ] `./gradlew build` clean

---

### M2 — Spawn a silent body, and prove the load-bearing rule

**Goal:** an entity appears at the right distance, stands there, and expires.
No dialogue, no trading, no interaction. **This milestone exists to prove
`SPEC.md` §1 before any content is built on top of it.**

**Create in `fabric/src/main/java/wayfarers/`:** `Spawns.java`,
`Encounters.java` (the active registry, tick, expiry), `Bodies.java`,
`Bubbles.java`, `OrphanSweep.java`, `WayfarerCommands.java`, `Chime.java`, and
the config plumbing (`encounters.json`, `settings.json`) via `readOrCreate`.

**Bubbles land here, not in M3** — the standing bubble is what makes an
encounter findable at all (`SPEC.md` §8.1), so M2 is not really testable without
it. Build `Bubbles.java` now and reuse it for speech in M3.

`Display$TextDisplay` has **no public setters** — build a `CompoundTag` and
`load()` it, the same way `cobblebending`'s `Boulder.java` spawns a
`BlockDisplay`. Read that file first. Set `brightness` or the bubble is
unreadable at night, and `billboard` or it faces one direction forever.

> **This is the biggest technical risk in the mod.** If `TextDisplay` does not
> work as expected on a vanilla client, stop and report before building anything
> on top of it — §8.1's discovery mechanic and all of M3's dialogue depend on it.
> The fallback is chat, and that is a design conversation, not a code fix.

**Bodies.** Resolve `body.type` by `Identifier` through the entity type registry
rather than a hardcoded switch. Set `setCustomName(...)`,
`setCustomNameVisible(body.nameVisible)`, `setPersistenceRequired(true)`, and add
the `wayfarers` entity tag. Per `chatdonkey`, per-entity constants live on
**`EntityTypes`**, not `EntityType`.

**The entity must not move.** Disable AI movement (`setNoAi(true)` or clear the
navigation, whichever verifies) so §1 holds by construction rather than by
hoping the pathfinder stays put. Looking at a nearby player is fine and desirable.

> **The trap `chatdonkey` documented, and you will hit it too.**
> `ServerEntityEvents.ENTITY_LOAD` fires from *inside* `addFreshEntity`, so it
> fires for your own encounter while it is still being registered — and the
> orphan sweep will bin it every single time. `OrphanSweep` must **queue** a
> candidate and judge it on the **next server tick**. Copy that logic; the delay
> is load-bearing and is commented as such in the original.

**Commands:** `/wayfarer trigger [player] [encounter]`, `/wayfarer end [player]`,
`/wayfarer status`, `/wayfarer reload`. All gated
`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.

**Acceptance** (needs a dev server — set one up under `fabric/run/` following
`chatdonkey/fabric/run/`, EULA accepted, flat world, offline mode):
- [ ] `/wayfarer trigger` spawns an entity **28–64 blocks away**, on real ground,
      never in lava, water or inside a block
- [ ] **It never moves toward the player**, observed over several minutes
- [ ] It despawns silently at `expireMinutes`
- [ ] It despawns after the player is >128 blocks away for a minute
- [ ] **A standing bubble is legible from spawn distance** — walk to 28 and to
      64 blocks and confirm you can read it. §8.1 rests entirely on this
- [ ] The bubble faces the player from every angle, and is readable at night
- [ ] The bubble follows the body if it is moved, and despawns with it
- [ ] Killing the server mid-encounter leaves no orphaned floating text
- [ ] `/wayfarer status` reports what is running
- [ ] Kill the server process mid-encounter, restart → the orphan is swept, and
      the log says so
- [ ] A freshly spawned encounter is **not** swept (the ENTITY_LOAD trap)
- [ ] `./gradlew build` clean

---

### M3 — The Wayfarer: dialogue, gossip, trading, payment

**Goal:** the first complete encounter.

**Create:** `Patient.java`, `Dialogue.java`, `TradeGui.java`, `Payment.java`,
`ItemComponents.java` (copied), and `lines.json` defaults.

**Dialogue delivery** (`SPEC.md` §5.3). `Script` decided the *what* and *when* in
M1; `Dialogue.java` only plays it: schedule steps across ticks, then route each
step to its channel — **`say` becomes a bubble** via M2's `Bubbles.java`,
**`narrate` goes to chat** as italic grey with no speaker, because narration has
no head to float above. Execute the six `action` verbs. `walk_away` and `vanish`
must route through the normal expiry path so nothing is orphaned.

Speech bubbles use a **shorter `view_range`** than the standing bubble, so
conversation stays local while the lure carries. A line over the character cap
splits across sequential bubbles at word boundaries rather than rendering a
billboard.

**Payment — the rules that matter:**
- Read and write **only the main 36 slots** (hotbar + three rows). Not armour,
  not offhand. `cobbleeconomy`'s README explains why: those indices have moved
  between versions and a mod that trusts `getContainerSize()` eventually inserts
  cobblestone into a helmet slot.
- **Count first, then remove.** Refuse whole; never partially execute.
- **Check inventory space before taking payment**, so a full inventory refuses
  the purchase rather than consuming diamonds and dropping the goods.
- Payouts use a local `giveOrDrop` — add to inventory, drop at feet if full,
  never destroy.

> **Do not call `EconomyApi`.** `cobbleeconomy/README.md` has a *"For other
> mods"* section showing `EconomyApi.economy().withdraw(...)` and it looks like
> the intended path. It is not. Payment here is **physical items only**; the
> player withdraws from the bank themselves before coming. This is `DESIGN.md`
> §2 and it is what lets this mod run on a server with no economy mod installed.
> If you find yourself importing anything from `cobbleeconomy`, stop.

**Trade GUI:** sgui, modelled on `cobbleeconomy`'s `ShopMenu`. Show what the
player is *carrying* — there is no balance to look up. Sold-out listings grey out
with a reason. Re-validate everything server-side on click; never trust the
rendered state.

**Acceptance:**
- [ ] Right-click opens the trade screen; **vanilla trading never appears**
- [ ] A purchase takes exactly the right diamonds from the main 36 slots
- [ ] A purchase with insufficient payment is refused and takes nothing
- [ ] A purchase with a full inventory is refused **before** payment is taken
- [ ] Stock decrements and the listing greys out at zero
- [ ] Selling to a buyer depletes their `coin`, and they say so when empty
- [ ] A listing with a `components` block delivers a stamped stack — test with the
      Spirit Stone JSON in `SPEC.md` §5, and confirm it works with `spiritwolves`
      installed and is skipped cleanly without it
- [ ] Dialogue fires on a cadence; gossip lines appear at `gossipChance`
- [ ] A script plays in order with its beats intact; `"..."` is a valid line
- [ ] `say` renders as a bubble above the speaker; `narrate` renders in chat as
      italic grey with no speaker
- [ ] A long line splits across sequential bubbles at word boundaries
- [ ] One bubble per speaker — a new line replaces rather than stacks
- [ ] Two scripts never interleave
- [ ] The same line does not repeat until its pool is exhausted
- [ ] Nothing is said while a nearby player is in combat
- [ ] Attacking the trader ends the encounter early with a line (not immortal)
- [ ] `./gradlew build` clean; core tests still `0 failed`

---

### M4 — The Stray Donkey

**Goal:** the second encounter, and the `inventory` block.

Chested donkey, no owner, standing where somebody stopped. The four container
calls are identical to `chatdonkey`'s Burrs event — copy them from `DonkeyCoat`:
`setChest(true)`, `setTamed(true)` (**required**, the screen checks `isTamed()`),
`openCustomInventoryScreen(player)`, `getSlot(500 + i)` for slot access. No
Mixin, no access widener.

Contents: some loot plus a **written book** via
`DataComponents.WRITTEN_BOOK_CONTENT`.

**Two decisions from the spec, both easy to get wrong:**
1. **Return everything on expiry, unconditionally.** A real container means the
   player can put their own items in it, and discarding the entity would destroy
   them. `chatdonkey`'s `Events.end` calls `returnEverything()` *outside* the
   player-still-here branch — if they logged out, the contents drop at the
   donkey. Copy that shape.
2. **The player keeps the donkey.** On expiry, do not discard it — strip the tag
   and leave it as an ordinary vanilla donkey. Not-discarding is less code than
   discarding.

**Acceptance:**
- [ ] The donkey spawns chested and its screen opens on right-click
- [ ] It cannot be mounted or ridden during the encounter
- [ ] The written book is readable and keepable
- [ ] Items the player puts in are returned on expiry
- [ ] If the player logs out, contents drop at the donkey rather than vanishing
- [ ] After expiry the donkey remains, leadable and rideable
- [ ] `./gradlew build` clean

---

### M5 — The Pillager Patrol

**Goal:** the `hostile` template and the parley.

**Create:** `Hostile.java`, plus `drops` loot-table resolution.

Three pillagers. On approach or right-click they **parley before fighting**:
- Wearing the `placatedBy` item → they stand down with a line
- Carrying the demanded currency → pay and pass, or refuse
- Refuse or attack first → a real fight with the configured drop table

**The guards are mandatory, not polish.** No spawn within a configurable radius
of world spawn; none for a player below a configurable play-time threshold; none
on peaceful. These are the difference between tension and a new player quitting.

**Acceptance:**
- [ ] The patrol parleys before any hostility
- [ ] Gold armour changes the greeting and lets the player pass
- [ ] Paying the demand ends it peacefully and takes exactly the right items
- [ ] Refusing starts a real fight with the configured drops
- [ ] Never spawns near world spawn, for a new player, or on peaceful
- [ ] `./gradlew build` clean

---

### M6 — The recurring trader

**Goal:** the only thing this mod persists.

**Create:** `TraderRegistry.java`, modelled on `spiritwolves`'
`PlayerWolfRegistry` — one `.dat` per player under `world/data/wayfarers/`,
loaded into a transient map on `SERVER_STARTED`, `markDirty` on mutation, dirty
records flushed on a tick counter, all flushed on `SERVER_STOPPING`, atomic
`.tmp`-then-move writes, and a malformed file renamed `.dat.corrupt` and skipped
so one bad record never stops the server booting.

Record shape is `SPEC.md` §9.1. Nothing persists in the world — only the
relationship. Each meeting: greet by reference to the last, gate better stock on
`meetings`, and never guarantee he appears.

**Acceptance:**
- [ ] Meeting count survives a restart
- [ ] A hand-corrupted `.dat` is quarantined and the server still boots
- [ ] Stock improves with `meetings`
- [ ] `marvelsSeen` prevents a `once` encounter recurring
- [ ] `/wayfarer forget <player>` deletes a record
- [ ] `./gradlew build` clean; core tests still `0 failed`

---

## 2. Definition of done

`SPEC.md` §16's checklist, in full. The phase is not done until every box there
is checked on a running server with a vanilla client.

---

## 3. Deliberately not in v1

The Marvel, the Wager, the Zombie Villager cure, and the Caged Villager. All four
are config-and-content on top of the two templates M3–M5 build, and none of them
teach the framework anything new. `SPEC.md` §17.

Also not in v1: the facts contract. `gossipChance` reads **static
operator-authored lines** from `lines.json`. Keep the line selection behind a
single method so swapping in live facts later is a one-site change.
