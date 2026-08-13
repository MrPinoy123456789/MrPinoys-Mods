# Wayfarers — handoff prompt for Windsurf SWE

> Paste the block below into Windsurf/Cascade in a fresh conversation, with
> `<MILESTONE>` replaced. **One milestone per conversation.** These are sized so
> the agent never has to hold the whole suite in context.
>
> Start at M0. Do not skip to a later milestone — M1's tests are what make M3
> safe, and M2 proves the design rule the whole mod rests on.

---

```
You are implementing ONE milestone of a new Minecraft Fabric mod inside an
existing ten-mod suite. Two documents are authoritative and you follow them
rather than your own judgement:

  a:\MrPinoys Mods\wayfarers\SPEC.md   — the design. What and why.
  a:\MrPinoys Mods\wayfarers\PLAN.md   — the build order. How and in what order.

YOUR MILESTONE: <MILESTONE>        e.g. "M0 — Scaffold"

PLATFORM: Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, JDK 25,
Windows. Build with .\gradlew.bat build (or ./gradlew build) from
a:\MrPinoys Mods\wayfarers.

READ BEFORE WRITING ANY CODE, IN THIS ORDER:
  1. wayfarers\PLAN.md  — §0 "Copy, don't invent", then your milestone in §1.
  2. wayfarers\SPEC.md  — §1, §2, §5, and any section your milestone cites.
  3. a:\MrPinoys Mods\DESIGN.md — §2 (the coupling rule) and §9 (principles).
  4. Every file listed as a "copy from" source for your milestone in PLAN.md §0.
     Read the original before writing the new one. Every time.

=== FILE ACCESS RULE — THE MOST IMPORTANT ONE ===
You may READ any file in a:\MrPinoys Mods.
You may only WRITE inside a:\MrPinoys Mods\wayfarers\.
Do not edit, refactor, reformat or "improve" any other mod. Not one line. If
your milestone appears to require a change outside wayfarers\, stop and report
it instead of making it.

=== HARD RULES — violating any of these fails the milestone ===
1. "environment": "server". Vanilla clients install nothing. No new registry
   entries, no resource packs. Custom items are vanilla items carrying
   minecraft:custom_data.
2. NO DEPENDENCY ON ANY OTHER MOD. Not a Java import, not a Gradle dependency,
   not a "depends" or "suggests" in fabric.mod.json. This suite duplicates code
   on purpose rather than sharing libraries.
   In particular: cobbleeconomy\README.md advertises
   `EconomyApi.economy().withdraw(...)` in a "For other mods" section. IGNORE IT.
   Payment in this mod is physical diamond and cobblestone items taken from the
   player's inventory. If you find yourself importing cobbleeconomy, stop.
3. No Mixins. Nothing in v1 needs one.
4. The core module has NO dependencies and its build.gradle.kts stays empty of
   them. That emptiness is what makes `import net.minecraft.*` fail to compile
   in core, and it is deliberate. Anything testable without Minecraft goes in
   core and gets a test. core refers to Minecraft things as ID STRINGS only.
5. Tests are a main() method printing "N passed, 0 failed". No JUnit, no test
   framework, no new dependency. Model: chatdonkey\core\src\test\java\
   chatdonkey\core\ChatDonkeyTest.java
6. Config: missing file -> write defaults and log. File that FAILS TO PARSE ->
   use defaults in memory and NEVER overwrite the file. Copy
   chatdonkey\core\...\ReadOrCreate.java rather than reimplementing it.
7. Never destroy a player item. If it will not fit, drop it at their feet and
   log it. Read and write ONLY the main 36 inventory slots (hotbar + 3 rows) —
   never armour or offhand.
8. Count first, remove second. Any transaction that cannot complete in full
   changes nothing at all. No partial execution, ever.
9. Rejections are return values, not exceptions. A player doing something
   ordinary-but-invalid gets a message.
10. Match the surrounding code style. These files comment WHY a decision was
    made and what the failure mode is — not what the line does. Write in that
    voice or write nothing.

=== THE BUILD TRAP THAT HAS BITTEN EIGHT MODS ===
The `dist` Gradle Copy task must dependsOn("jar"), NEVER "remapJar". Minecraft
26.2 ships unobfuscated so Loom registers no remapJar task, and depending on it
fails the build outright. If a build error mentions remapJar, you introduced it.

=== VERIFY SIGNATURES, DO NOT GUESS ===
26.2 renamed a great deal and almost every tutorial online is wrong for it.
The compile-classpath jar is:
  C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar
Check anything you are unsure of:
  javap -cp "<that jar>" net.minecraft.world.entity.npc.villager.Villager

Already verified — use these, they are correct:
  net.minecraft.world.entity.npc.villager.Villager
  net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader
  net.minecraft.world.entity.monster.zombie.ZombieVillager
  net.minecraft.world.entity.monster.illager.Pillager / Vindicator
  net.minecraft.world.entity.animal.equine.Donkey / TraderLlama
  net.minecraft.world.entity.animal.golem.IronGolem
  net.minecraft.world.entity.monster.Witch
Per-entity constants are on EntityTypes, NOT EntityType.
It is Identifier, not ResourceLocation.
Permissions are Commands.hasPermission(...) with level constants on Commands —
hasPermission(int) does not exist.
UseItemCallback/UseEntityCallback return InteractionResult, not TypedActionResult.
The Fabric interaction events module is v0, not v1.
Note-block sounds are Holder.Reference<SoundEvent> (for ClientboundSoundPacket);
mob sounds like VILLAGER_TRADE are bare SoundEvent and need Level.playSound.
ItemStack.is(Item) DOES work, despite what chatdonkey\PROGRESS.md claims.

=== WHEN YOU ARE DONE, REPORT ===
- Every file you created or changed, and why.
- The build output and test counts, PASTED, not summarised.
- Each acceptance checkbox from your milestone in PLAN.md, marked met or not
  met. If one is not met, say so plainly. Do not report a milestone complete
  when it is partly complete.
- Everything you could not verify without a running game client and a real
  player, listed explicitly as unverified. Most of M2-M6 falls in here; that is
  expected and honest.
- Any place SPEC.md or PLAN.md was wrong, ambiguous, or contradicted the code.
  Report it rather than silently choosing an interpretation.

=== IF YOU GET STUCK ===
Stop and report. Do not invent a design decision, do not refactor another mod to
make your change fit, and do not expand scope beyond the milestone. A milestone
that stops halfway with an honest report is far more useful than one that
finishes by guessing.
```

---

## Driving notes

**Order is not optional.** M0 → M1 → M2 → M3 → M4 → M5 → M6. M1 is the biggest
milestone and the only one fully verifiable without a game — it is also what
makes every later milestone's transaction logic safe. M2 exists specifically to
prove `SPEC.md` §1 (*the encounter never moves toward the player*) before any
content is built on top of it.

**Milestones needing a human afterwards.** M2 onward all touch entities, and
nothing entity-facing can be verified without a client. Budget a play session
after M2, M3 and M5. `chatdonkey`'s whole history is a warning here: it reached
milestone five with 353 passing tests and almost nothing play-verified.

**Three things to check personally, whoever implements them:**

1. **M2's orphan sweep.** `ServerEntityEvents.ENTITY_LOAD` fires from *inside*
   `addFreshEntity`, so a naive sweep discards your own encounter the instant it
   spawns — every single time, and only in a live world, so no test catches it.
   Confirm the implementation queues the candidate and judges it on the next
   tick. `chatdonkey` documents this as a critical find.

2. **M3's payment path.** The order must be: count → check inventory space →
   take payment → deliver. Any other order either eats diamonds without
   delivering or delivers without charging. Read the code, don't trust the report.

3. **M0's `core/build.gradle.kts`.** If an implementer adds Gson or anything else
   to it "to make the config work", the module's entire purpose is gone. It must
   stay empty of dependencies; parsing belongs fabric-side and is passed into
   `core` as lambdas, which is exactly what `ReadOrCreate` is shaped for.

**If Windsurf starts editing other mods**, that is the failure mode this prompt
is most defended against, and it is worth stopping the run immediately rather
than reviewing the diff afterwards.

---

# Phase 1 — Current Progress and Review Handoff

> This section was added after the M5/M6 implementation work. It is a report of
> what is in place, what is missing, and what the next agent should verify or
> finish.

## Completion status

All code for v1 milestones M0–M6 is in place and compiles cleanly. The core test
suite and the Gradle build both pass. One M5 acceptance item remains unwired: the
configured hostile drop table.

## What was implemented

### M0 — Scaffold
- `settings.gradle.kts`, `core/build.gradle.kts`, `fabric/build.gradle.kts`,
  Gradle wrapper, `fabric.mod.json`, `WayfarersMod.java`.
- `dist/MrPinoys_wayfarers-0.1.0.jar` is produced by `./gradlew build`.

### M1 — Core model
- Pure-Java records/utilities in `core/src/main/java/wayfarers/core/`.
- `WayfarersTest.java` prints `153 passed, 0 failed`.
- `core/` contains no `net.minecraft` imports.

### M2 — Spawn, expiry, bubbles, commands
- `Spawns.java`, `Encounters.java`, `Bodies.java`, `Bubbles.java`,
  `OrphanSweep.java`, `WayfarerCommands.java`, `Chime.java`.
- `/wayfarer trigger|end|forget|status|reload` implemented.

### M3 — Wayfarer trading and dialogue
- `Patient.java`, `Dialogue.java`, `TradeGui.java`, `Payment.java`,
  `ItemComponents.java`.
- Physical payment from the main 36 slots; count-first, full-or-nothing.
- Bubbles and chat delivery, scripts, splitting, and rate limits.

### M4 — Stray Donkey
- Tamed, chested donkey with loot including a written `Last Words` book.
- Donkey persists after the encounter.

### M5 — Pillager Patrol (parley/payment)
- `Hostile.java` handles right-click and first-strike parley.
- `Dialogue.java` chooses `placated`/`open` based on gold armour.
- Right-click branches: gold → dismiss; demand (`wants`/`coin`) → pay and pass;
  otherwise → release the patrol to attack.
- M5 spawn guards in `Encounters.start` (peaceful, session time, world-spawn
  distance).
- `Bodies.java` freezes every `Mob` on spawn; `Hostile` thaws hostiles on a fight.

### M6 — Recurring trader persistence
- `TraderRegistry.java` per-player `.dat` files under `world/data/wayfarers/`.
- Load on `SERVER_STARTED`, dirty flush every 600 ticks, flush all on
  `SERVER_STOPPING`.
- Atomic writes and `.dat.corrupt` quarantine.
- `/wayfarer forget <player>` removes records.
- `TraderRecord` tracks `traderId`, `meetings`, `lastMetAt`, `spentWith`,
  `marvelsSeen`, `encountersMet`.

## Build verification

```text
./gradlew build
```

Result: `BUILD SUCCESSFUL` with `153 passed, 0 failed`.

## Phase 1 sign-off — drop table wired, M5 closed on code

> Added after the review pass requested in the handoff prompt below. The prompt
> is left in place as the record of what was asked.

### Build

```text
./gradlew build
BUILD SUCCESSFUL
156 passed, 0 failed
```

### M5 — Configured hostile drop table (done)

- `EncounterDefinition` gained a `drops` field (`""` when absent), parsed by
  `WayfarersConfig` and excluded from `extras`.
- The default `pillager_patrol` entry now carries
  `"drops": "minecraft:chests/pillager_outpost"` — a real reward for a real
  fight, on top of the mob's own death drops.
- New `Drops.java` resolves the id, rolls the table at the dead body's position
  and spawns the stacks. It builds `LootParams` filtered against the table's own
  `ContextKeySet`, so entity tables and chest tables both work and neither
  throws. A missing or unusable table warns once and is otherwise ignored.
- `WayfarersMod.AFTER_DEATH` calls `Drops.roll` before ending the encounter.
- `config.reload()` clears the warned-table set, so a corrected id goes quiet.

The table is rolled once, on the death of the entity the encounter tracks — not
once per pillager in the group.

### Other fixes made during the review

- `Spawns.spawnNear` discarded only the first entity when a group could not be
  fully placed, orphaning the rest in the world. It now discards every one that
  landed.
- The M5 guards were half-hardcoded. `hostileMinPlayMinutes` (default 60) and
  `hostileWorldSpawnBlocks` (default 128) are now real settings, as `PLAN.md`
  M5 requires. The play-time guard previously reused `minSessionMinutes`, whose
  default of 1 made it no guard at all.
- `Hostile.onDamage` re-spoke the attack line on every swing. `Active.released`
  now makes it fire once.
- `Bodies.java` imported `Component` twice.

### M5 acceptance

- [x] The patrol parleys before any hostility — code path verified, not play-tested
- [x] Gold armour changes the greeting and lets the player pass — code path only
- [x] Paying the demand ends it peacefully and takes exactly the right items —
      count-first, full-or-nothing via `Wallet.planRemoval`
- [x] Refusing starts a real fight with the configured drops
- [x] Never spawns near world spawn, for a new player, or on peaceful
- [x] `./gradlew build` clean

### Explicitly unverified — needs a live server

Nothing below was run in a game. All of it is code-path reasoning only.

- The whole play-test list in the prompt below: wayfarer trade/buy/sell/payout,
  the three patrol branches, donkey chest and book, `/wayfarer forget`.
- That `minecraft:chests/pillager_outpost` rolls and drops as intended, and that
  the loot feels proportionate.
- `Display$TextDisplay` bubbles (`SPEC.md` §5.3) — still the largest
  implementation risk in the mod and still unconfirmed against a vanilla client.
- The donkey chest slot offset of 500 in `Bodies.fillDonkeyChest`.

## Review handoff prompt

Please take the following steps before declaring M5 complete and the whole
milestone set green:

1. **Build and tests** — run `./gradlew build` in `a:/MrPinoys Mods/wayfarers`
   and paste the result.
2. **Code review** — read `Hostile.java`, `Encounters.java`, `Dialogue.java`,
   `Bodies.java`, `Patient.java`, `TradeGui.java`, `Payment.java`,
   `TraderRegistry.java`, `WayfarerCommands.java`, `WayfarersMod.java`.
3. **Spec trace** — compare against `SPEC.md` §5, §7, §8, §9, §10 and `PLAN.md`
   M5 acceptance.
4. **Play-test** in a dev server with a vanilla client:
   - Wayfarer: right-click, trade, buy, sell, refusal, payout.
   - Pillager Patrol: with gold armour; with the demanded emeralds; without; and
     attacking first.
   - Stray Donkey: chest, book, persistence.
   - `/wayfarer forget <player>` and verify `world/data/wayfarers/` file handling.
5. **Implement the configured hostile drop table** as described above.
6. **Update this section** with a final sign-off block when done.

Report every file you changed, the build output, the acceptance checkboxes, and
anything that could only be verified in a live game explicitly as unverified.
