# Completed Milestones (M0–M7, M9)

> **Status:** All milestones below are code-complete (`./gradlew build` green,
> unit tests passing). Live multiplayer verification is deferred to a single
> suite-wide pass, per `../PROGRESS.md`.
>
> M8 (Deferred) is not here — its items are recorded in
> `../DOOR_LADDER_BRAINSTORM.md` alongside the future design work they relate to.
>
> This file replaces the individual `M0`–`M7` and `M9` plan files, which have
> been deleted. `PROGRESS.md` remains the source of truth for task-level status;
> this document is the architectural summary of what was built and why.

---

## M0 — Entry fee and safety

**Goal:** a third party can write a datapack against this mod without reading
its source, and the repo states its own licence.

- `RoomManifest` reloads on `/reload` via a Fabric server-data-pack listener,
  no restart needed. Verified live over RCON.
- Root `LICENSE` (MIT, suite-wide).
- `INTEGRATION.md` documents five extensible surfaces and three non-extensible
  items, following `kamutotems/INTEGRATION.md`'s structure.
- Published `dungeon_room` schema with validation failures verbatim.
- Owner check on selector doors (`selectorDoorStep` gates on `record.owner`).

---

## M1 — Themes foundation

**Goal:** 14 templates × N processor lists instead of 140 hand-authored
`.nbt` files. A theme is datapack JSON and needs no recompile.

- `processors` wired through `TemplateStamper` → `LayoutStamper`; processor
  lists applied after `JigsawReplacementProcessor` so themes see final states.
- `theme` field on `DungeonRoomMeta` (optional string array, symmetric with
  `roles`); `RoomSelector` filters on it with a legible planner failure.
- Three proof themes: `theme_deepslate`, `theme_prismarine`,
  `theme_blackstone` — each a `rule` processor rewriting the shell palette.
- Verified live: three visibly different dungeons from the same templates.

---

## M2 — The room

**Goal:** the selector room becomes a persistent, owned, decoratable room, and
the run ends by walking into it. The lifecycle rework with real architectural
risk.

- **Room persistence:** `RoomStore` saves one `StructureTemplate` NBT blob per
  owner under `<world>/data/pocketdungeons/rooms/<uuid>.dat`. Backup-on-write
  (`.bak` copy before atomic temp-write-rename). `/dungeon admin baserestore`
  restores from backup. Entities captured, filtered to item frames / armour
  stands on load.
- **Permission mask:** `RoomProtection` — positional `PlayerBlockBreakEvents.
  BEFORE` and use-block handler. Owner + `RoomWhitelist` can break/open
  containers; anyone else can use stations and ender chests. Editable via
  `/dungeon room whitelist add/remove/list`.
- **Bedrock envelope:** sub-floor and over-ceiling always; outer wall ring only
  on faces with no adjacent cell. Applied after every stamp; cleared on
  teardown.
- **Closed loop (capture → persist → clear → stamp):** on completion, the
  entrance cell is captured, persisted, cleared, then re-stamped at the
  terminal cell behind a sealed door. No early returns between capture and
  persist. Player opens the door and walks into their room.
- **Lobby-first entry:** a keystone run opens into the owner's room alone
  (saved blob or `entrance_hall`), sealed door, bedrock envelope. Three
  selector doors render against the connecting wall. Choosing a door rotates
  the abstract shape so its entrance aligns with the room's fixed door, stamps
  every other cell, opens the seal, starts the timer.
- **Lingering quarry:** finished dungeons linger — force-load tickets
  released, blocks stay. Players walk back to mine. Purged on next entry or
  leadership change.
- **Purge on leadership change:** owner leaving while party members remain
  purges the whole instance (both `dropMember` and `exit` paths).

---

## M3 — The calling card

**Goal:** other people can stand in your room. The multiplayer thesis (§2.1).

- `CallingCard.mint(owner)` — plain compass + `CUSTOM_DATA` owner UUID,
  optional `LODESTONE_TRACKER` (glint). `/dungeon room card` command.
- Use-on-lodestone: `RitualListener` positive test for `CallingCard.isCard`
  before the keystone branch; foreign items PASS to other mods. Card not
  consumed.
- One shared visit instance per owner, refcounted. Owner home → admit into
  live instance. Owner away → stamp read-only copy, seal door, place selector
  doors. Last visitor leaves → purge. `InstanceRecord.visitInstance` flag.
- Visitor permissions: reuses M2's mask — visitor is "not whitelisted," cannot
  break or open lootable containers, can use stations and ender chests.

---

## M4 — Affixes

**Goal:** the ladder gets texture. Affixes stack by level, seed from the key,
and every one hands you something.

- `Affix` enum → stackable `EnumSet`; `NONE` deleted. `AffixMath` owns
  parse/join/thresholds/seeding/naming/depletion. `DungeonLog` stores only
  elective affixes; seeded ones re-derived from `(owner, level)` on every read.
- Thresholds 5 / 11 / 17 decide how many; seed `hash(owner, level)` decides
  which. Door's elective affix sits on top, not inside the count.
- Depletion takes `max` across the set, capped at 2×.
- Naming: `<intensifier> <affix> Keystone [<level>]` + bracketed subtitle.
  Pure function of `(level, affixSet)` — watcher-stable.
- Four new affixes: **Swarming** (scales `total_mobs`/`simultaneous_mobs` via
  inline `TrialSpawnerConfig`), **Overclocked** (scales cooldown down),
  **Molten** (stamps lava/magma in cell interior — only lava faucet),
  **Silenced** (denies `CONSUMABLE` use via `SilenceListener`; tightens
  `required_player_range`).

---

## M5 — Wolves and Feral

**Goal:** the first kiss/curse proven end to end, and the mod's first
cross-mod surface (spiritwolves).

- `Affix.FERAL` added (seeded, arrives off 5/11/17 thresholds).
- `RoomContent.spawnMobs` restored as a general-purpose
  `(EntityType, count, Consumer<Entity>)` direct spawn path — never called for
  `encounter` cells (`TrialContent` owns those). Wolves in `corridor`/`loot`
  cells only, pinned with `setHomeTo(cellCentre, 6)`. Neutral, never angered.
- Coats by tier: 9 `WolfVariants` split into three exclusive bands by
  `DifficultyProfile.lootTier()`. Set via `DataComponents.WOLF_VARIANT`.
- Bones as guaranteed floor (landed via M6 T6.1). 1-in-3 catch rate runs
  against a floor, not a weighted drop.
- Spirit Stone permanence: zero code, zero coupling. No `spiritwolves`
  import anywhere. Run-scoped by default — teardown `discard()`s all
  non-player entities.

---

## M6 — Supply

**Goal:** "could a player progress without ever leaving?" stops being
aspirational.

- **Guaranteed floors** inlined into all six tier tables (food, torch, bone,
  tier blocks, dirt, sapling, seeds). `chests/supply` split into three tiered
  tables so the ungated container carries the floor too. Verified live: 180
  draws, zero floor misses.
- **Tiered building blocks:** three exclusive palettes — tier 1 stone/wood/
  iron/moss, tier 2 deepslate/copper/prismarine/crying obsidian, tier 3 end
  stone/ancient-city/rare decoratives. Zero cross-tier bleed verified.
- **Grove room:** two JSON files, no Java — `theme_grove` processor list +
  `grove.json` room entry over existing `mossy_tee` template. No water
  (doorways leak; bone meal is a floor). Sapling guaranteed, not weighted.
- **Nether/End products, not ingredients:** tier 2 gets a 40% workshop pool
  (brewing stand, cauldron, anvil, etc.); tier 3 gets an ungated pool, one
  product per chest (ender chest, enchanting table, obsidian, etc.).
- **Plumbing:** `/dungeon` mints the first keystone itself. `sendHome`
  prefers the room over world spawn on every fallback path. Preference order,
  not a mode — suite behaviour unchanged with an overworld.

---

## M7 — Recipes

**Goal:** server folklore. Someone comes back with *New Dungeon Discovered*
and their friends have to ask what they did.

- **T7.0 — Run themes:** `ThemeManifest` loads `dungeon_theme/*.json` across
  all namespaces. `ThemeOfferMath.pick` seeds three theme picks per door off
  `(owner, level)`. `Keystone.offers` returns themed offers; `InstanceRecord.
  theme` carries the chosen theme through the run. Room's own processors win
  over run theme; player's room cell never themed.
- **T7.1 — Theme history:** `DungeonLog.Entry.recentThemes` (last 3,
  `optionalFieldOf`, silent migration). Completed theme pushed in `completeRun`
  per completing member against their own entry.
- **T7.2 — Recipe table:** `dungeon_recipe/*.json` loaded across all
  namespaces. `RecipeMatcher` matches the tail of the player's window against
  a recipe's ordered `themes` list. A match replaces door 3's theme only,
  keeping its level and `FRAGILE`. Unloaded theme → rejection at load.
- **T7.3 — Discovery floor:** `/dungeon log` lists completed themes with
  counts. Nothing shows the window, a match, or a hint.
- **T7.4 — First recipe dungeon:** `drowned_vault` theme (`discoverable:
  false`, `loot_suffix: "_drowned"`). Loot resolver tries
  `chests/tier_N_drowned` first, falls back to unsuffixed. Recipe:
  `[deepslate, prismarine, blackstone] → drowned_vault`.

---

## M9 — Refactor and cleanup

**Goal:** the codebase can absorb the door/ladder reframe without the reframe
having to be written inside a 3,000-line class. Nothing a player can see
changes.

- **C0:** Landed the uncommitted M5/M6/M7 working tree (~5,700 lines) as six
  coherent commits.
- **C1:** Deleted dead code: `InstanceRecord.selectorRoom` (field, guards,
  unused constructor overload) and `ritualKeyItem`/`ritualKeyCount` config.
- **C2:** Unified `RoomBuilder`/`RoomTemplateGenerator`'s duplicated shell
  palette behind a shared `buildShell`. One palette, one cell builder.
- **C3:** Split `Instances.java` (3,008 lines) into six classes: `CellGeometry`
  (pure coordinate math, testable), `InstanceRegistry` (slot bookkeeping),
  `PartyService` (party/invite), `InstanceTeardown` (async clear queue),
  `VisitService` (calling card entry), `RunLifecycle` (game loop). One
  extraction per commit, moves only, no behaviour change.
- **C4:** `CellGeometryTest` landed. `InstanceRecord` down to one constructor.
  New `LootTables` validates nine core loot table ids at `SERVER_STARTED`.
- **C5:** Six superseded design docs archived to `docs/archive/`. New root
  `README.md`. `PLAN.md` kept (referenced by four live documents).
