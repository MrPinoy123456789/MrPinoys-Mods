# Hand-off: Spirit Wolves v3 + v4 — player-bound wolf and soul-forged progression

You are implementing **sections 16 and 18** of `SPEC.md` in this repo
(`a:\MrPinoys Mods\spiritwolves\SPEC.md`). Read the entire spec first — §16
is the v3 binding/storage redesign, §18 is the v4 permanent progression
(souls, levels, equippable combat verbs). This hand-off is a map, not a
replacement for the spec.

## What already exists and works

v1 and v2 are fully implemented and build clean (`./gradlew build`, jar lands
in `dist/`). Do not break v1/v2 behavior; you may rewrite or extend v1 modules
where the spec explicitly says they change in v3.

Key modules you will touch:

- `SpiritStone.java` — currently holds wolf NBT in `custom_data`. v3 strips it
to a thin marker item (`{ spiritwolves: { bound } }`) plus charges/repairable.
Lore rendering moves to reading the `PlayerWolfRegistry` record.
- `WolfCapture.java` — full NBT capture/restore. Still the authority for
converting a live wolf to a tag and back. Use it for registry writes/reads.
- `Binding.java` — right-click bind. v3 adds `PlayerWolfRegistry.has()` check.
- `Summoning.java` — right-click summon/recall. v3 reads registry, not stone.
- `Deaths.java` — `ALLOW_DEATH` interception. v3 reads/writes registry for
death-save and handles the new **true death** branch (0 charges → delete
registry record, revert stones).
- `Tracker.java` — 30-tick poll. v3 polls registry, not stone `custom_data`.
Logout/dimension-change/owner-death recall now lives here.
- `Chime.java` — sound cues. v4 reuses it for level/verb tier-ups.
- `SpiritCommands.java` — v3/v4 adds `/spiritwolves release [confirm]`,
`/spiritwolves info`, `/spiritwolves verbs`, verb subcommands, and admin
helpers.
- `WolfKill.java` (created for v2) — `AFTER_DEATH` attribution hook. v4 adds
`Souls` and `Verbs` as listeners.

## What to build (build order matters)

### Phase 1 — §16 v3: player-bound registry

Do not start §18 until §16 is solid. §18's entire state lives in the
`PlayerWolfRegistry` record.

1. `PlayerWolfRegistry` (new)
   - Static class, loads on `SERVER_STARTED`, flushes dirty in `Tracker` poll,
     flushes all on `SERVER_STOPPING`.
   - `WolfRecord` mutable class mapping to `CompoundTag` with the §16.1 schema.
   - `.dat` persistence per player in `world/data/spiritwolves/<uuid>.dat`.
   - Verify `NbtIo` method names in 26.2 before writing.

2. `SpiritStone.java` changes
   - Remove `wolfTag`, `wolfUuid`, `isSummoned`, `setSummoned`, `updateWolfTag`.
   - New schema: `custom_data: { spiritwolves: { bound: true/false } }`.
   - `refreshLore` accepts a nullable `WolfRecord` instead of reading from the
     stack. Render name/collar/charges/journal from it.
   - Provide `isUnbound`, `isBound`, `setBound`, charge helpers. Keep all anvil
     components untouched.

3. `Binding.java`
   - Check `PlayerWolfRegistry.has(player)`; reject if yes.
   - Capture with `WolfCapture`, put `WolfRecord`, mark stone bound, consume
     one stone from stack.

4. `Summoning.java`
   - Look up registry by player UUID.
   - Orphan stone, recall, summon, dormant: exact §16.4 flow.
   - Remove any stone-`custom_data` wolf data reads.

5. `Tracker.java`
   - Poll records where `summoned == true` instead of scanning stones.
   - Recall on logout, owner death, owner dimension change. Remove the §8
     stone-in-inventory invariant.

6. `Deaths.java`
   - Read/writes registry. True death on 0 charges: remove record, revert all
     bound stones to unbound.

7. `SpiritCommands.java`
   - Add `/spiritwolves release [confirm]` (30s chat-click confirm).
   - Add `/spiritwolves info` from registry.
   - Add admin `stone`, `wipe`, `souls` (v4).
   - Implement all §16.8 message strings and colors exactly.

8. v3 definition of done (§16.11) — build must be clean before continuing.

### Phase 2 — §18 v4: souls and verbs

1. `Souls.java` (new)
   - Static soul-values table by `EntityType` + category fallback.
   - `levelFor(long)` derived from thresholds.
   - Listener on `WolfKill`, awards souls/family kills, detects level-ups,
     sends messages, writes journal, plays chime.
   - Add fields to `WolfRecord` and `PlayerWolfRegistry`.

2. `Verbs.java` (new)
   - Static definitions for the six verbs (Emberfang, Venomfang, Ravenous,
     Bonechill, Witherbite, Blinkstrike): family set, tier effect text, costs,
     flavor strings.
   - State machine: HIDDEN → SCENTED → UNLOCKED → ATTUNED → FILLED.
   - Listener on `WolfKill` to increment family kills and fill progress.
   - `attune`/`equip`/`unequip` validation helpers.

3. `VerbCommands.java` (new)
   - `/spiritwolves verbs` panel as described in §18.3.
   - Hidden subcommands `verbs equip <id>`, `verbs unequip <id>`,
     `verbs attune <id>`.
   - Build `Component` rows with `ClickEvent` and `HoverEvent`; re-print the
     panel after every mutation.
   - Diamond removal uses the same 36-slot iteration pattern as `Tracker`.

4. `VerbProcs.java` (new)
   - On-hit/on-kill combat hooks for equipped verbs.
   - Read equipped state from registry, cache per wolf UUID, invalidate on
     recall.
   - All effects applied as transient state only — nothing leaks into
     `wolfTag` via capture.

5. Integrate with `WolfKill`
   - Dispatch to `Streak`, `Fetch`, `Souls`, `Verbs` in one pass.

6. v4 definition of done (§18.9) — build must be clean.

## New files to create

- `src/main/java/spiritwolves/PlayerWolfRegistry.java`
- `src/main/java/spiritwolves/WolfRecord.java` (or nested in registry)
- `src/main/java/spiritwolves/Souls.java`
- `src/main/java/spiritwolves/Verbs.java`
- `src/main/java/spiritwolves/VerbCommands.java`
- `src/main/java/spiritwolves/VerbProcs.java`

You may also create `WolfKill.java` if it doesn't exist from v2.

## Files to modify

- `src/main/java/spiritwolves/SpiritStone.java`
- `src/main/java/spiritwolves/Binding.java`
- `src/main/java/spiritwolves/Summoning.java`
- `src/main/java/spiritwolves/Tracker.java`
- `src/main/java/spiritwolves/Deaths.java`
- `src/main/java/spiritwolves/SpiritCommands.java`
- `src/main/java/spiritwolves/Chime.java` (add cues if you want; not required)
- `src/main/resources/fabric.mod.json` if needed for new commands (it
  shouldn't be — commands are registered in code)

## Critical design constraints to not ignore

- **Player-bound, not stone-bound.** The registry is the source of truth.
  Stones are replaceable remotes. Two stones on one player are the same wolf.
- **One wolf per player.** Enforced by `PlayerWolfRegistry.has(player)` at
  bind time.
- **No stone-in-inventory invariant.** A summoned wolf does not recall when
  the stone leaves inventory. Recalls are: right-click, logout, owner death,
  owner dimension change, death-save.
- **True death at 0 charges.** Delete the registry record, revert all the
  player's stones. Progression is gone. This is the emotional core.
- **No migration.** v1/v2 stones are treated as unbound on first touch.
- **No GUI screens.** All UX is chat `Component` click/hover events.
- **No permanent progression on the stone.** Progression is on the wolf.
- **All verbs passive.** No keybinds. Procs on the wolf's own attacks/kills.
- **Build order is enforced by data flow.** Do §18 after §16 works.

## Verify in jar before coding

Do not trust memory of 26.2's API. Use `javap` on the merged jar as in prior
sessions.

1. `NbtIo` exact method names for reading/writing `CompoundTag` `.dat` files
   (look for `read`, `write`, `readCompressed`, `writeCompressed`).
2. Entity ignition method: `setRemainingFireTicks` vs `igniteForSeconds`.
3. Fabric API event for on-hit damage (not just on-kill), if you want
   Bonechill/Ravenous to proc on every attack. If it doesn't exist in the
   pinned version, redesign those verbs to proc on-kill only — do not
   introduce a Mixin.
4. `LivingEntity.heal(float)` and `MobEffectInstance` constructors — verify
   the exact overloads exist.
5. `Wolf.getNavigation()` and related pathfinding, if implementing
   Blinkstrike fully — the spec allows a simpler positional teleport first.

## Conventions (same as this repo)

- No Mixins. Fabric API events only.
- Static utility classes with private constructors, matching v1 style.
- Verify every Minecraft/Fabric API signature against the actual merged jar
  before using it.
- Run `./gradlew build` after each sub-feature and confirm the jar in `dist/`
  updates. Clean builds before declaring a phase done.
- Keep exact message strings and colors from §16.8 and §18.6 unless the spec
  explicitly says they're first guesses.
- Do not touch §1–§14 of `SPEC.md`. Update §16/§18 DoD checkboxes if helpful,
  but don't rewrite them.

## Out of scope for you

- cobbleeconomy integration (stones are sold there, but this mod just exposes
  `SpiritStone.createUnbound()` or equivalent for shop use).
- §17 expansion candidates (emotional moments, guard post, etc.) — not
  included in "everything" for this hand-off; the user said to focus on wolves
  and the core progression system.
- Datapack recipes, screen UIs, other creature types.

## Definition of done for this hand-off

- [ ] v3 DoD (§16.11) — build clean
- [ ] v4 DoD (§18.9) — build clean
- [ ] `./gradlew build` produces a jar in `dist/`
- [ ] No v1/v2 regressions that break the existing commands or lore
