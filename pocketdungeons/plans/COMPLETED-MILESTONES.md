# Completed Milestones (M0–M7, M9–M18)

> **Status:** All milestones below are code-complete (`./gradlew build` green,
> unit tests passing). Live multiplayer verification is deferred to a single
> suite-wide pass, per `../docs/reference/LIVE_TEST_PASS.md`.
>
> M8 (Deferred) is not here — its items are recorded in
> `../docs/reference/DOOR_LADDER_BRAINSTORM.md` alongside the future design work they relate to.
>
> This file replaces the individual `M0`–`M7` and `M9` plan files, which have
> been deleted. `../docs/reference/LIVE_TEST_PASS.md` covers the outstanding live verification;
> this document is the architectural summary of what was built and why.

---

## M0 — Entry fee and safety

**Goal:** a third party can write a datapack against this mod without reading
its source, and the repo states its own licence.

- `RoomManifest` reloads on `/reload` via a Fabric server-data-pack listener,
  no restart needed. Verified live over RCON.
- Root `LICENSE` (MIT, suite-wide).
- `docs/INTEGRATION.md` documents five extensible surfaces and three non-extensible
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
- **C5:** Six superseded design docs deleted from `docs/archive/`. All live
  docs consolidated into `docs/`. `README.md` at root.

---

## M10: Ladder reframe

**Goal:** the keystone level gates access; difficulty lives on the map. The
first milestone of `docs/reference/D3_PROGRESSION_PLAN.md`, raising the cap from 25 to
100 and giving the ladder somewhere to go.

- **Cap 25 → 100:** `PocketDungeonsConfig.keystoneMaxLevel` bumped in its
  field initialiser, `applyDefaults`, `apply`'s default, and the written
  defaults JSON. `KeystoneMath.clampLevel` needed no change; it already takes
  the cap as a parameter.
- **Mob strength scaling:** a new `Instances`-registered
  `ServerEntityEvents.ENTITY_LOAD` listener (fabric-lifecycle-events-v1, not
  `ServerLivingEntityEvents` as the plan first guessed; verified against the
  jar that the latter carries no after-spawn hook) applies `+1% per keystone
  level` (`DifficultyProfile.mobScale`, config `mobScalePerLevel`) to a
  spawned mob's max health, attack damage and movement speed via
  `ADD_MULTIPLIED_TOTAL` attribute modifiers, keyed by a stable id so a
  repeat load never stacks the bonus. `Instances.applyMobScale` is
  package-visible rather than private, because `FeralContent`'s wolves spawn
  synchronously at stamp time, before the listener's registry lookup would
  ever find a matching `InstanceRecord` (it still carries the lobby's
  placeholder layout at that point); `FeralContent.apply` gained a
  `keystoneLevel` parameter and calls the same scaling directly. The health
  top-up after raising max health only fires when the mob was already at full
  health beforehand, so a mob re-entering tracking mid-fight (a chunk reload)
  keeps its damage instead of healing back up.
- **Affix thresholds and intensifier bands:** `AffixMath.seededCount` moved
  from fixed `5/11/17` thresholds to `(level + 15) / 20`, uncapped (the
  pool-size clamp already lived in `seededFor`): first affix still at level 5,
  matching the old system, then one more every 20 levels (25, 45, 65, 85). A
  bare `level / 20` was tried first and rejected during self-review: it left
  the entire 1-19 range with zero seeded affixes, an early-game regression the
  plan never asked for. `AffixMath.intensifier` grew eight new ten-level bands
  from `Unhinged` (21-30) to `Transcendent` (91-100).
- **`Affix.Kind.ELECTIVE` removed:** `FRAGILE` is deleted outright; `OMINOUS`
  moved to `Kind.SEEDED`. `AffixMath.elective` is `@Deprecated` and always
  returns the empty set; `DungeonLog.Entry.keystoneAffix` and its codec field
  are marked superseded rather than deleted, for save-format safety.
  `Keystone.offers`'s door 3 drops its `FRAGILE` set for a plain empty one;
  door 2 keeps `OMINOUS` as a rendering marker. The real free/Greater door
  split is M12's job.
- **Spawner-gated completion:** `InstanceLayout` carries a new
  `trialSpawners` set, collected by `LayoutStamper`/`RoomContent`/
  `TrialContent.applyEncounter` (now returning the anchor it placed) as each
  encounter cell stamps. `RunLifecycle.completeRun` refuses a pad contact,
  with a chat message and no state change, until
  `TrialContent.countCleared` against that set clears the configured
  fraction (`PocketDungeonsConfig.spawnerClearThreshold`, default 0.75).
  Verified against the jar: an untouched spawner sits at `INACTIVE`, not
  `COOLDOWN`, and counts against the denominator on purpose, so a run cannot
  sprint past its own content.
- **New pure-Java math:** `DifficultyProfile.mobScale` and
  `DifficultyProfile.spawnersCleared`, both covered by
  `difficultyProfileTest`; `AffixMathTest` updated for the new threshold
  curve, the wider intensifier table, and `FRAGILE`'s removal.

---

## M11: Adventures

**Goal:** replace the recipe system's backward-looking tail match with a
forward-looking descent graph, and give the ladder its first boss.

- **`AdventureGraph` (pure Java) replaces `DungeonRecipes` + `RecipeMatcher`.**
  A theme is a `Node(theme, Kind, List<Transition>)`; `Kind` is `ENTRY`,
  `DESCENT`, or `BOSS`. `pick(owner, currentTheme, depth)` draws three
  weighted next-theme offers from the current node's transitions, falling
  back to a uniform pick across every `ENTRY`-kind theme when
  `currentTheme` is blank, unknown, or the graph is empty (a fresh server, or
  nothing loaded yet) rather than throwing. `resetTheme` picks the one
  entry theme a completed boss run resets into. Both are seeded through
  `AffixMath.seed`, so a given `(owner, currentTheme, depth)` always picks
  the same three doors across the instance watcher's reconciliations.
  `AdventureGraphs` is the loader, mirroring `DungeonRecipes.load` exactly:
  reloadable resource under `dungeon_adventure/*.json`, one node per file
  named for its theme, rejecting a transition to a theme `ThemeManifest`
  never loaded or that has no node of its own, and rejecting the whole
  graph (with a named reason) if it ends up with no `ENTRY`-kind theme at
  all, since a boss run would have nowhere to reset to.
- **`DungeonLog.Entry` gains `currentTheme` and `depth`.** `recentThemes`
  (the old 3-deep window) is kept and marked superseded, for save-format
  safety; nothing writes it any more. `recordTheme` advances
  `currentTheme`/`depth` off the just-completed theme's graph node: a boss
  theme resets to a weighted entry theme at depth 0, an entry theme restarts
  the descent count at 1, and anything else (a descent theme, or an unknown
  one, which degrades gracefully rather than getting stuck) advances to it
  and increments depth. `ThemeHistory` is deleted outright, not superseded;
  nothing persisted through it, only through the codec field it fed.
- **`Keystone.offers` rewired onto the graph.** `ThemeOfferMath.pick` and
  `DungeonRecipes.current().match(recentThemes)`'s door-3 override both
  deleted; one `AdventureGraphs.current().graph().pick(owner, currentTheme,
  depth)` call produces all three door themes now, so a recipe no longer
  needs to reach in and override a single door after the fact.
- **Recipe system deleted:** `DungeonRecipes.java`, `RecipeMatcher.java`,
  `DungeonRecipe.java`, `ThemeHistory.java`, `ThemeOfferMath.java`, and the
  `dungeon_recipe/` resource directory (one file, `drowned_vault.json`).
  Four themes re-authored as the graph: `deepslate` and `prismarine` are
  `ENTRY` nodes that transition into each other and into `blackstone`;
  `blackstone` is `DESCENT`, transitioning onward to the other two or to
  `drowned_vault`; `drowned_vault` is `BOSS`, unreachable as a starting pick
  (`discoverable: false`, unchanged from M7) and reached only through
  `blackstone`'s transition.
- **One proof boss encounter, with a landed scope divergence.** The plan
  called for a hand-authored `.nbt` plus a new `dungeon_room` entry; this
  session had no live client to author or capture one with, so
  `BossContent.spawn` places a tagged, heavily scaled `minecraft:ravager`
  directly into the terminal cell's existing `exit_hall` template instead
  (documented in `docs/reference/D3_PROGRESSION_PLAN.md`'s M11 section). The
  completion gate (`RunLifecycle.completeRun`) refuses a boss-themed run's
  pad contact while `BossContent.bossAlive` finds the tagged mob still alive
  in the terminal cell, the same shape M10's spawner-clear gate uses. The
  boss is spawned once, when the run generates (`Instances
  .generateBehindLobby`), not reactively on pad contact.
- **New tests:** `AdventureGraphTest` (node validation, the boss-reset pool,
  seed stability) and a rewritten `KeystoneOfferTest` (the graph's weighted
  pick, the entry-pool fallback, the empty-graph case) replace
  `RecipeMatchTest`. `DungeonLogTest` now covers `currentTheme`/`depth`
  advancing and surviving every other `DungeonLog` mutation, in place of the
  `ThemeHistory` window it used to test.

---

## Fixed alongside M11: a pre-existing boot crash in `LootTables`

Not part of the D3 plan; found while trying to get M11 live-verified, and
worth its own entry since it silently broke every server this mod ever ran
on. `LootTables.validateAtStartup` (and `TrialContent.resolveLootTable` at
runtime) called `level.registryAccess().lookupOrThrow(Registries.LOOT_TABLE)`.
Loot tables are a reloadable, datapack-driven registry
(`MinecraftServer.reloadableRegistries()`), not part of a `ServerLevel`'s
frozen dynamic registry access, so that call threw
`IllegalStateException: Missing registry` unconditionally, crashing
`SERVER_STARTED` before any of this mod's own checks could run. Independently
reproduced on a real 169-mod production server, not just this session's dev
harness. Both call sites now go through `LootTables.exists(MinecraftServer,
ResourceKey<LootTable>)`, which reads the loot table through
`reloadableRegistries().lookup()` instead. Confirmed fixed against this
session's dev harness: a clean boot logs `All 9 core loot tables verified
present`, and `/dungeon admin build`/`admin list` both work headlessly.

---

## M12: Two-tier doors and fuel

**Goal:** door 1 becomes the free, fuel-producing, non-depleting tier; doors
2 and 3 become the fuel-costed, level-gated Greater tier. A level-10 key
cannot take a door-2/3 offer a level-100 key can.

- **`Keystone.Offer` gains a `Tier` (`FREE`/`GREATER`).** Door 1 is `FREE`;
  doors 2 and 3 are `GREATER`. The tier only marks what a door *is*; the
  refusal logic lives entirely in `RunLifecycle.chooseOffer`, ahead of
  `Instances.generateBehindLobby`, so a refused choice never spends fuel or
  attempts to stamp anything.
- **Fuel is echo shards, and door 1 is the only source.** A new `Fuel` class
  (the `ConfiguredItem` pattern) wraps `PocketDungeonsConfig.fuelItem()`.
  Landed decision, recorded in `D3_PROGRESSION_PLAN.md`'s M12 section:
  `Fuel.grant` pays out `fuelPerFreeRun` (default 1) as a guaranteed direct
  grant on every completed door-1 run, not a weighted loot-table entry.
  Nothing else in the mod's loot tables grants echo shards, so the
  self-funding risk the plan's currency table warns about (a premium room
  dropping its own cost) does not exist by construction, and
  `fuelPerFreeRun` stays a real, load-bearing config value rather than a
  number a static JSON `set_count` could not have read anyway.
- **The two Greater-door refusals, both in `chooseOffer`:** a keystone below
  `greaterDoorMinLevel` (default 15) is refused with the required level
  named; fewer than `fuelCostPerGreaterDoor` (default 3) echo shards is
  refused with the cost named. Both checks read the player's *current*
  keystone level/inventory, before `generateBehindLobby` runs, so a refusal
  costs nothing. `Fuel.spend` (an `Inventory.clearOrCountMatchingItems` call,
  verified against the jar's bytecode for its "simulate vs remove" and
  "per-slot cap" semantics) only fires after the choice actually succeeds.
- **Door 1 never depletes.** `InstanceRecord.freeDoor`, set at the same
  point `chosenStep` is, is checked in `RunLifecycle.expireTimedOut` and
  `completeRun`'s late-completion branch: a free-door run that times out or
  finishes late settles as `Keystones.Outcome.NO_CHANGE` instead of
  `TIMED_OUT`/`LATE`. `KeystoneMath.deplete` already guaranteed a keystone
  never reaches zero; this is the same guarantee applied to door 1's whole
  tier.
- **Door 1's own clock.** `door1TimerSeconds` (default 300s), a flat
  allowance read at `generateBehindLobby` time instead of the
  base-plus-per-room formula doors 2/3 keep using, "farming" should not
  feel like racing.
- **Door-offer dialog text.** `DialogScreens.doorOffer` now states door 1's
  free/pays-fuel status or a Greater door's cost and level requirement,
  ahead of the existing theme/affix lines.

**Live-only, not yet verified:** the door-offer dialogs' new text and the
actual refusal behaviour at a door; recorded as section 16 in
`LIVE_TEST_PASS.md`.

---

## M13: Gear loot pool

**Goal:** author tiered armour and weapon loot so the gear-touching sinks
(M14 reroll, M16 gamble, M17 extraction) have something to operate on. Content
only: 21 loot tables and one small `LootTables` registration, no other Java.

- **Confirmed the plan's premise first.** Every one of the twelve existing
  `chests/tier_*` tables carried materials, blocks, food and consumables and
  exactly zero wearable or wieldable items, so the sinks genuinely had nothing
  to work on.
- **15 slot-keyed tables, `gear/<slot>_<tier>.json`,** across five slots
  (helmet, chestplate, leggings, boots, weapon) and three tiers. These are
  M16's clean draw source. `weapon` is deliberately one slot rather than one
  per weapon type. Registered through a new `LootTables.gearTable(slot, tier)`
  and `LootTables.GEAR_SLOTS`, with all 15 added to the `ALL` startup check,
  so a missing gear table is a boot-time error rather than an empty gamble.
- **One gear pool appended to all nine chest tables** (base, `_ominous` and
  `_drowned` at each tier), behind a `random_chance` of 0.35/0.45/0.55 by tier
  plus 0.15 on the ominous variants, so gear is a treat rather than filler.
  The `_drowned` variants were included deliberately even though they are
  unregistered and optional: they are what a `drowned_vault` run resolves to,
  and skipping them would have made M11's boss theme the one path in the game
  that drops no gear.
- **Tier palette follows `VISION.md` 3.6.1:** tier 1 iron-grade (iron, with
  chainmail and leather as lesser rolls), tier 2 iron/diamond, tier 3
  diamond/netherite. Netherite gear is weight 1 against diamond's 5, so it
  reads as a genuine find.
- **Landed correction, recorded in `D3_PROGRESSION_PLAN.md`: the enchanting
  mechanism is `enchant_with_levels`, not `set_enchantments`.** The plan
  assumed `set_enchantments` could carry "weighted enchantment levels"; the
  26.2 bytecode says otherwise, as it applies every entry in its map through
  `EnchantmentHelper.updateEnchantments`. Fixed enchantment sets would have
  made M14's reroll station pointless (you would already know what every drop
  carries), and would have needed a hand-partitioned pool per slot to stop a
  bow rolling Protection. `enchant_with_levels` with
  `options: "#minecraft:on_random_loot"` is slot-correct by construction and
  expresses the plan's own intent natively as level ranges: uniform 5-15,
  15-25 and 25-35 against vanilla's 1-30 enchanting scale.
- **Provenance marker:** `set_components` writes
  `custom_data {pocketdungeons: {tier: N}}`, nested under the mod's root key
  to match `Keystone`/`CallingCard`'s existing convention rather than the
  plan's `pocketdungeons.tier` shorthand, which would have read as a flat
  dotted key.

**Verified headlessly, not merely parsed.** `/loot insert` into a real chest
plus `/data get block` drew actual stacks and read their actual components:
tier-1 helmet with `{unbreaking 1, protection 2}` and `tier 1b`, tier-3
chestplate with `{protection 3}` and `tier 3b`, tier-2 weapon, tier-3 boots,
and a `chests/tier_3` roll that produced an enchanted diamond axe carrying the
tier tag alongside ordinary loot. Note the marker serialises as an NBT byte
(`tier: 3b`) rather than an int; harmless, since `CompoundTag.getIntOr` tests
`instanceof NumericTag` and `ByteTag` is one. Recorded as section 18 in
`LIVE_TEST_PASS.md` along with what still wants a live loot pass.

---

## M14: Gear reroll station

**Goal:** a room station rerolls one enchantment on a piece of gear, the
player's choice of which, at a lapis cost that scales with the item's tier.
The gear-scale sink, paired with M12's fuel as the ladder-scale sink: fuel is
why level 100 is worth reaching, lapis is why the gear it drops is worth
using. Depends on M13's tiered gear loot and its `pocketdungeons.tier`
custom_data tag.

- **`RerollStation` is a positive-test branch in `RitualListener.onUseBlock`**,
  ahead of the selector-door check, the same shape the calling-card and
  keystone branches already use. It fires only when the right-clicked block
  matches the configured `rerollBlock` (a `ConfiguredItem`-resolved item id
  resolved back to a `Block` via `Block.byItem`, since `ConfiguredItem` only
  ever resolves items) **and** the held item carries a `pocketdungeons.tier`
  custom_data tag greater than zero. Anything else at the same block (by
  default a plain `minecraft:smithing_table`) falls straight through to
  vanilla's own behaviour, untouched; the station never overrides a real
  smithing-table interaction for a non-gear item.
- **Gating landed as keystone-level, not ungated.** `rerollUnlockLevel`
  (default 5) matches `AffixMath`'s first seeded-affix threshold rather than
  `greaterDoorMinLevel` (M12's Greater-door gate, default 15): the lapis sink
  should be available well before a player has fuel to spend on doors 2/3,
  since it operates on gear that starts dropping immediately, not on a
  currency gated behind the ladder. Settled during implementation per the
  plan's own "Open (tuning, in-milestone)" note; not a scope divergence.
- **One enchantment at a time, via `ItemEnchantments.Mutable`.** The picker
  (`DialogScreens.rerollPicker`, tier B: the click carries which
  enchantment) lists the held item's current enchantments; each button
  removes exactly that one and replaces it with a uniformly-random pick from
  the item's valid pool (every registered enchantment where
  `Enchantment.isSupportedItem(stack)` holds, excluding the one just removed
  and anything already on the item), at a random level within that
  enchantment's own `getMinLevel()`-`getMaxLevel()` range. Every other
  enchantment survives untouched. `RerollStation.handleReroll` re-reads the
  player's live main-hand item at resolution time rather than trusting the
  dialog's snapshot, the same staleness discipline `DialogRouter`'s whitelist
  handlers already follow, since the round trip is a real gap in which the
  held item or the player's lapis count can change.
- **"Never strictly worse" is `RerollMath.isValidReroll`, checked before
  anything is spent or written, not assumed.** A same-count, genuinely
  different enchantment set: automatically what "not a subset of
  the old set" (the plan's own phrasing) reduces to once the pool has already
  excluded the removed enchantment and every enchantment already present.
  `RerollStation.handleReroll` computes both sides and refuses the swap
  (logging an error, spending nothing) if it is ever violated, though the
  pool construction should make that structurally impossible.
- **Cost is `RerollMath.cost(tier, rerollLapisPerTier)`:** linear in tier
  (default 4/8/12 lapis for tiers 1/2/3), the same "further in costs more,
  never so much the door closes" shape `KeystoneMath.deplete` uses for the
  ladder. Lapis is spent via `Inventory.clearOrCountMatchingItems` (the same
  verified idiom `Fuel.spend` uses), only after the swap has already passed
  the validity check.
- **`RerollMath` (pure Java, no Minecraft imports)** carries `cost` and
  `isValidReroll`, covered by `RerollMathTest` (`rerollMathTest` Gradle task,
  wired into `tasks.test`): the cost curve's boundaries and every shape of
  the set-swap property (a genuine swap, a no-op, a shrink) are asserted
  headlessly.

**Live-only, not yet verified:** the station right-click (including that a
non-gear item at the same block still gets vanilla's own smithing screen),
the reroll picker dialog, the level-gate and lapis-cost refusal messages, and
the returned stack's actual enchantment components; recorded as section 17 in
`LIVE_TEST_PASS.md`.

---

## M15: Armor trims

**Goal:** dungeon-found armor trim templates and materials, consumed
templates (the vanilla duplication recipe removed), and a material-based
combat bonus while the trim is worn. Depends on M13's gear loot pool for a
place to author the new entries; is itself the dependency M17 needs, since
the Herobrine Cube's equipped-power check reuses this milestone's equip-time
attribute plumbing.

- **The blocking jar question, answered: removal has to be global.**
  `javap` against `net.minecraft.world.item.crafting.Ingredient` (26.2)
  confirms it matches only by item id or tag (`HolderSet<Item>`); there is no
  data-component predicate on a crafting ingredient. The "duplication" a
  dungeon-found template needs sunk is a separate `minecraft:crafting_shaped`
  recipe per pattern (`data/minecraft/recipe/<pattern>_armor_trim_smithing_
  template.json`, template + a pattern-specific block + a diamond -> 2
  templates), not the `minecraft:smithing_trim` recipe that actually applies
  a trim (that recipe is untouched; applying a trim at a smithing table works
  exactly like vanilla). Since an ingredient cannot require "this exact
  dungeon-found copy," a datapack recipe cannot exclude only dungeon-found
  templates and keep ordinary ones duplicable; the plan's own fallback
  ("or globally if there is no clean way to distinguish") is the only
  available shape. All 18 pattern duplication recipes are overridden (same
  `data/minecraft/recipe/` path, so the mod's own resources shadow vanilla's)
  with their pattern-specific block swapped for `minecraft:barrier`,
  unobtainable in survival: the recipe still parses and displays, it simply
  can never be completed. This is trap 14's route (a datapack recipe can
  produce, or in this case withhold, an outcome with no Java and no second
  mixin), confirmed against the one-mixin budget: no mixin was needed.
- **Trim templates and materials carry no `pocketdungeons.tier` tag,
  a deliberate divergence from the plan's own implementation notes.**
  The plan suggested tagging them the way M13 tags gear, for provenance.
  `RerollStation` (M14) reads exactly that tag off the *held* item to decide
  whether a smithing-table right-click opens the reroll picker instead of
  vanilla's own screen; a player holding a found template (or a stack of
  loose trim material) to open the smithing table would have been silently
  redirected into the reroll picker instead of the vanilla trim UI. Found
  during this milestone's own headless verification pass, before it could
  ship as a live bug. Recorded here, and the plan section should be treated
  as corrected by this note rather than by editing the historical text.
- **Eighteen patterns, not the plan's "roughly seventeen."** The 26.2 jar
  ships bolt, coast, dune, eye, flow, host, raiser, rib, sentry, shaper,
  silence, snout, spire, tide, vex, ward, wayfinder, and wild: eighteen.
  Immaterial to the milestone's shape (pattern stays cosmetic either way);
  noted only because the plan's approximate count undercounts by one.
- **Ten materials plus resin: eleven.** `TrimMaterials` (26.2) adds `RESIN`
  to the plan's assumed ten. All eleven get a bonus, one entry each, in
  `PocketDungeonsConfig.trimBonuses` (a config-editable list of
  `{material, attribute, amount, operation}`, `operation` an
  `AttributeModifier.Operation` serialized name). Landed mapping: diamond
  armor toughness, netherite knockback resistance, gold movement speed
  (the plan's own three worked examples), iron armor, copper mining
  efficiency, redstone attack speed, emerald luck, amethyst entity
  interaction range, quartz max health, resin fall damage multiplier
  (reduced). **Lapis lands as safe fall distance, not the plan's suggested
  "XP gain":** there is no vanilla player attribute for experience gain
  rate, and every other material's bonus is an `AttributeModifier` by
  design (the plumbing M17 reuses is attribute-shaped, not event-shaped), so
  an XP-gain lapis bonus would need its own separate mechanism outside that
  shape. Recorded here as the plan's correction; `trimBonuses` is config, so
  an operator who wants XP gain badly enough can still build it as a
  separate feature without touching this milestone's code.
- **The equip-time plumbing is `TrimListener`, a new class, not folded into
  `Instances`.** Fabric API 0.156.0+26.2 has no "an armour slot's contents
  changed" event (checked: no matching hook in the jar), so this is the
  plan's own anticipated fallback: a per-tick scan of the four armour slots,
  gated by `PocketDungeonsConfig.watchIntervalTicks()` the same cadence the
  instance watcher uses, but in its own `ServerTickEvents.END_SERVER_TICK`
  registration scanning every online player, not just dungeon members
  (`Instances`'s watcher is instance-scoped; a trim bonus can apply
  everywhere, per the dungeon-only flag below). Kept as two explicit steps
  on purpose, exactly as the plan asks: `trimMaterialOf` reads the worn-piece
  signal (which configured material, if any, is on a slot's trim), and
  `reconcile` applies the modifier for that signal. M17 reuses `reconcile`'s
  half unchanged and only needs to swap the signal source.
- **Reconciliation, not an equip hook, and idempotent by construction.** The
  `AttributeModifier` id is stable per (player, slot), namely `pocketdungeons:
  trim_bonus_<slot>`, not per material, so a material swap is remove-then-
  add against a small in-memory `Map<UUID, EnumMap<EquipmentSlot,
  Holder<Attribute>>>` tracking which attribute is currently active per
  slot, not a persisted one: recomputing every watch tick self-heals the
  same way `Keystone.reconcile` already trusts a periodic pass over catching
  every mutation site.
- **`trimBonusDungeonOnly` landed as `false` (global), the plan's "bigger
  commitment" reading.** `VISION.md` section 3.7 permits it by its letter,
  since both the template and the material are dungeon-loot-gated; a global
  bonus is also what makes a trimmed piece feel worth wearing outside the
  dungeon loop, rather than reading as a prop the moment a run ends. An
  operator who wants the narrower, dungeon-only reading can flip the config
  flag; the check (`player.level().dimension().equals(PocketDungeonsMod
  .DUNGEON_LEVEL)`) is the same one `RoomProtection` already uses.
- **Templates and materials are new pools on `chests/tier_1`, `tier_2`,
  `tier_3`, and their `_ominous` variants (six tables), not the `_drowned`
  variants.** Every one of the eighteen patterns appears in every tier's
  template pool at even weight (pattern is cosmetic; no reason to gate it by
  tier), behind a `random_chance` that rises with tier and again on ominous
  (0.20/0.27 at tier 1, 0.28/0.35 at tier 2, 0.35/0.42 at tier 3). Materials
  are tiered by rarity, additively over the pre-existing economy pool's
  iron/gold/lapis/diamond/emerald (left untouched): tier 1 adds quartz,
  redstone, copper; tier 2 adds amethyst on top of tier 1's three; tier 3 is
  amethyst, netherite, and resin. Confirmed against a live dev-server pass:
  `/loot insert` into real chests and `/data get block` on the result showed
  `wayfinder_armor_trim_smithing_template`, `ward_armor_trim_smithing_
  template`, `silence_armor_trim_smithing_template`, `spire_armor_trim_
  smithing_template`, and `sentry_armor_trim_smithing_template` drawn with no
  `custom_data` (confirming the M14 divergence above actually took), and
  `redstone`, `copper_ingot`, `quartz`, `amethyst_shard`, `netherite_ingot`,
  and `resin_brick` all drawn at the configured tiers, alongside the
  pre-existing gear and economy entries, untouched. Skipping the `_drowned`
  variants (the boss theme's optional reskins) is a scope decision, not an
  oversight: M13 covered them for gear because gear is required-path loot,
  while trims are an overworld-facing bonus layer on top, not something a run
  needs to stay self-sufficient (`VISION.md` §3.7).
- **`./gradlew build` verified green after every change**, including the
  loot-table splice (validated as real JSON via `json.load`, spliced with
  CRLF line endings preserved to match the repo's existing files rather than
  the platform default) and the recipe overrides (confirmed by a live
  `/reload`: recipe count unchanged before and after, meaning the 18
  overrides replaced their vanilla originals in place rather than erroring
  or duplicating).

**Live-only, not yet verified:** applying a template at a smithing table by
hand (the recipe-level mechanics are confirmed; the GUI interaction is not),
the worn bonus's actual effect in combat or on the relevant vanilla stat
display, and that the duplication recipe genuinely refuses a crafting attempt
in a real inventory grid (headlessly confirmed only that the override loads
without error, not that a player-facing craft attempt is refused); recorded
as section 19 in `LIVE_TEST_PASS.md`.

---

## M16: Gear gamble station

**Goal:** a room station that spends emeralds for one random piece of gear in
a chosen slot and tier, with no guarantee of quality within the slot. The
volume-over-certainty sink that pairs with M14's targeted, guaranteed reroll:
a player who knows what they want rerolls it; a player who wants more shots
at *something* for a slot gambles. Depends on M13's slot-keyed gear tables.

- **`GambleStation` always claims its configured block, unlike `RerollStation`
  (M14).** The reroll station is a positive test on the held item, so a
  non-gear item at the same block still falls through to vanilla; a gamble
  draw has nothing to check about what is held, so the branch in
  `RitualListener.onUseBlock` (immediately after the reroll branch) claims
  the configured block unconditionally the moment it matches, the same way
  a selector door or a calling-card lodestone claims theirs. The default
  block is `minecraft:emerald_block`, distinct from the reroll station's
  `minecraft:smithing_table`, specifically so the two stations do not
  collide on the same vanilla block by default.
- **The draw is a direct `LootTable` roll, not a chest.** `GambleStation.draw`
  resolves M13's `gear/<slot>_<tier>` table via
  `server.reloadableRegistries().getLootTable(key)` and rolls it with
  `table.getRandomItems(params, seed)`, `params` built from
  `LootContextParamSets.CHEST` with `LootContextParams.ORIGIN` set to the
  player's position; verified against the 26.2 bytecode of vanilla's own
  `/loot` give-to-player path, which uses the same shape. One roll, handed
  straight to the player via `Payout.deliver`, never a chest: M13's
  slot-keyed tables exist specifically so a gamble draw is one piece of one
  slot and cannot out-produce opening the run's own chests.
- **Tier unlock reuses `KeystoneMath.lootTier`, not a new threshold.** The
  picker (`DialogScreens.gamblePicker`) offers slot/tier buttons only up to
  `KeystoneMath.lootTier(keystoneLevel)`, the same level-to-tier mapping a
  run's own loot already resolves against, rather than a fourth config
  threshold alongside `greaterDoorMinLevel` and `rerollUnlockLevel`: a
  player's current level already names the highest tier their own runs draw
  from, so it names the highest tier the gamble offers too. A low-level
  player sees only tier-1 buttons; a level-10+ player sees all three.
- **Cost is `GambleMath.cost(tier, slot, emeraldsPerTier, slotMultiplier,
  weightedSlot)`:** linear in tier by default (6/12/18 emeralds for tiers
  1/2/3), the same shape `RerollMath.cost` uses, then scaled by
  `gambleSlotMultiplier` (default 1.5) if the slot equals
  `gambleWeightedSlot` (default `weapon`): D3's own Kadala menu prices a
  weapon pull above an armour pull, and this is that same weighting rather
  than every slot costing the same at a given tier (the plan's own "Open
  (tuning)" question, settled during implementation). The multiplier floors
  at 1.0 so a misconfigured value under that can never make the weighted
  slot the cheap one. Emeralds are spent via
  `Inventory.clearOrCountMatchingItems`, the same verified idiom `Fuel.spend`
  and `RerollStation.handleReroll` already use, only after a live draw has
  actually produced an item.
- **`GambleMath` (pure Java, no Minecraft imports)** carries `cost`, covered
  by `GambleMathTest` (`gambleMathTest` Gradle task, wired into `tasks.test`):
  the cost curve's boundaries, the weighted-slot multiplier, and a
  misconfigured multiplier under 1.0 never producing a cheaper weighted slot
  are all asserted headlessly.
- **Tier B dialog dispatch, like M14's reroll picker.** `DialogScreens
  .gamblePicker` lists every unlocked slot/tier combination as one button
  each, carrying the slot name and tier in a `CustomAll` payload
  (`pd_slot`, `pd_tier`); `DialogRouter` dispatches `gamble` to
  `GambleStation.handleGamble`, which re-validates the slot, the tier's
  unlock, and the emerald count against the player's *live* state rather
  than the screen's snapshot, the same staleness discipline
  `RerollStation.handleReroll` already follows.

**Live-only, not yet verified:** the station right-click, the slot/tier
picker dialog, the emerald-cost and tier-unlock refusal messages, and the
delivered item's actual components; recorded as section 20 in
`LIVE_TEST_PASS.md`. Headless-verifiable: the cost helper (`gambleMathTest`),
the tier-unlock gate (`KeystoneMath.lootTier`), and the gear-table id
resolution against the registry (`LootTables.exists`).

---

## M17: The Herobrine Cube

**Goal:** a ritual that consumes a rare item once, permanently remembers one
fixed power it carried, and re-applies that power to any future item cheaply.
Depends on M15's equip-time attribute plumbing (generalised) and M11's
adventure graph (a rare-node reward is the extractable-item source).

- **The blocking jar question changed the milestone's shape: no crafting-grid
  interception, no second mixin.** The plan's working title was a
  crafting-table ritual, matching the "eight around one" special-recipe shape
  a golden apple uses. `javap` against `net.minecraft.world.item.crafting
  .Ingredient` (26.2) confirms it is a plain item/tag predicate with no
  component-value matching at all (`DataComponentMatchers` exists only on
  `ItemPredicate`, used by loot and advancement conditions, never a crafting
  grid). Imbue needs "any weapon or armour piece, plus whichever one of an
  open-ended power library the player chooses"; expressing that as datapack
  recipes would need one recipe per (item type x power) pair, unbounded,
  since the power library "grows arbitrarily" by design. `CraftingMenu
  .slotChangedCraftingGrid`, the mixin candidate the plan named for exactly
  this case, was confirmed `protected static` (would need injecting into),
  but landed unnecessary: a block-use ritual station, the same
  `RitualListener`-adjacent shape `RerollStation` and `GambleStation` already
  use, reads and writes the held stack directly in Java, so item identity and
  an arbitrary chosen power both fall out for free with no interception of
  any kind. This is a scope divergence from the plan's own working title,
  recorded here per house rule 6 rather than silently changed; the plan
  section itself should be read as corrected by this note.
- **`CubeStation` is the ritual, two positive tests on the held item at one
  configured block (`cubeBlock`, default `minecraft:beacon`), dispatched from
  `RitualListener.onUseBlock` ahead of the lodestone branch, same as
  `RerollStation`.** Holding a rare item (`custom_data.pocketdungeons
  .cubeReward` set) extracts: the item is consumed one-for-one and the reward
  id joins the player's permanent set. Holding an ordinary piece of tiered
  gear (M13's `pocketdungeons.tier` tag, read via `RerollStation.tierOf`)
  with no power yet opens the imbue picker. Anything else at the same block
  falls straight through to vanilla's own behaviour (a plain beacon screen by
  default), the same positive-test discipline every other station in this
  mod already follows.
- **Extracted powers are a new `DungeonLog.Entry` field, the `completedThemes`
  shape.** `Set<String> extractedPowers`, `optionalFieldOf` with a
  `Set.of()` default, never truncated. `DungeonLog.addExtractedPower` is a
  no-op if the power is already unlocked, since the equip-time listener that
  reads this set is a reconciliation pass, not a one-shot event, and a
  duplicate scan must never be observable as anything happening twice.
- **Reconciled into the adventure graph itself, not a separate list.**
  `AdventureGraph.Node` gained an optional `reward` field (a power id, blank
  for most nodes); `AdventureGraphs`'s loader parses it the same way it
  parses `next`. One proof pairing ships end to end: `drowned_vault` (M11's
  boss node) names `reward: "warden_ward"`, and a new pool in
  `tier_3_drowned.json` drops one `minecraft:heart_of_the_sea` (renamed
  "Warden's Ward", 5% chance, carrying `custom_data.pocketdungeons
  .cubeReward`) as the extractable source. More rewards are content work,
  authored the same way once M11 names more rare nodes; the plan's own call
  to reconcile M11's reward list, M16's gear pool, and any shell-token list
  into one authored place is satisfied by putting the reward directly on the
  node that grants it, rather than a fourth list alongside the other three.
- **Imbue writes `custom_data.pocketdungeons.power` onto the held stack
  directly**, via `CustomData.update`, merging into whatever compound is
  already there (M13's `tier` tag survives an imbue) rather than replacing
  it outright. Costs `imbueCost` (default 4) of `imbueMaterial` (default
  `minecraft:iron_ingot`, deliberately a fourth, otherwise-unused currency:
  fuel/lapis/emeralds are already spoken for by M12/M14/M16 and the
  orthogonality rule keeps every sink on its own currency), spent via
  `Inventory.clearOrCountMatchingItems`, the same verified idiom every other
  sink in this mod uses, only after the chosen power is validated against
  the player's *live* extracted set. Tier B dialog dispatch, like M14/M16:
  `DialogScreens.imbuePicker` lists every unlocked power as one button,
  carrying the power id (`pd_power`) in a `CustomAll` payload; `DialogRouter`
  dispatches `imbue` to `CubeStation.handleImbue`, which re-reads the held
  item and the live extracted-power set rather than the screen's snapshot.
- **The equip cap is `PowerEquipMath` (pure Java, no Minecraft imports),
  covered by `PowerEquipMathTest`** (`powerEquipMathTest` Gradle task, wired
  into `tasks.test`): the first `cap` *distinct* power ids encountered in
  slot order are active; a power worn on two slots at once still counts once,
  and a misconfigured negative cap floors at zero rather than reading as
  unlimited. `PowerListener` applies it: `TrimListener`'s equip-time
  plumbing generalised exactly as the plan describes (signal source swapped
  from "what trim is on this piece" to "which power does `CubeStation
  .powerOf` read off this piece", the modifier-application half untouched),
  as a sibling class rather than a shared one (this mod's one-listener-per-
  feature convention), scanning five slots (four armour plus main hand,
  trims stay armour-only) every watch tick. `PocketDungeonsConfig
  .powerBonuses` mirrors `trimBonuses` exactly, keyed by power id instead of
  trim material; one default entry (`warden_ward` -> knockback resistance)
  matches the one proof reward.
- **Reversibility landed as a config flag with no undo ritual yet.**
  `extractionReversible` defaults to `false` (D3's own shape: a hard sink,
  the rare item gone for good). No reversal mechanism ships in this
  milestone either way, so the flag currently only documents the intent for
  whoever builds one; recorded here rather than silently defaulted, per the
  plan's explicit call to settle this in-milestone rather than skip past it.
- **`./gradlew build` verified green after every change**, including the
  loot-table splice (JSON-validated) and the adventure-graph node's new
  `reward` field (a compact secondary constructor keeps every pre-M17
  `AdventureGraph.Node` call site, main and test, compiling unchanged).

**Live-only, not yet verified:** the station right-click for both extract and
imbue, the imbue picker dialog, the material-cost and already-imbued refusal
messages, the equip cap's actual effect on combat stats, and the delivered
Warden's Ward drop's name/lore/marker; recorded as section 21 in
`LIVE_TEST_PASS.md`. Headless-verifiable: the equip-cap arithmetic
(`powerEquipMathTest`), the extracted-power persistence (`DungeonLog`
read/write round trip), and the reward-id reconciliation against the
adventure graph (`AdventureGraph.nodeForReward`).

---

## M18: Room shell pass

**Goal:** the room's shell (floor, walls, ceiling, lamps) becomes immutable to
everyone, the owner included; the lodestone moves from the floor to the wall;
the selector opening gets physical double doors; and the ceiling gets top
slabs with stair-framed light fixtures. First milestone of
`docs/reference/ROOM_UX_PLAN.md`'s Room UX pass, and the prerequisite for M24's room
skins: a shell no player can edit is one the mod is free to swap.

- **Immutable shell:** `RoomProtection.isShell(pos, roomOrigin)` is a pure
  coordinate test against the room origin: the floor row (Y=0), the wall ring
  (x=0, x=15, z=0, z=15 at Y=1..5), the ceiling row (Y=6, which covers the
  slabs, the stair fixtures and the lamps) and nothing else. The interior
  (x=1..14, z=1..14, Y=1..5) stays the owner's build space. The break guard
  (`RoomProtection.beforeBlockBreak`) and the placement guard
  (`RitualListener.onUseBlock`, the existing use-on-block interception a
  placement begins from) both deny shell positions ahead of the permission
  mask, so the owner cannot modify the shell and a whitelisted guest cannot
  either. Decorations are unaffected by construction: a wall sign, torch,
  banner or button sits on the face of a wall (target x=1..14, interior), and
  a carpet lands on the floor's top face (target Y=1, interior), so their
  target positions are never shell.
- **Wall lodestone:** the room's lodestone moved from the floor (NW corner,
  with its chiselled ring) to a fixed position in the north wall at eye
  height, local (1, 2, 0). That is the plan's own example position, chosen
  because it is visible and reachable for right-click, sits behind a player
  facing the selector doors, and at every rotation of the template lands on a
  wall clear of the door slots and the selector-door strip (checked against
  `TemplateStamper`'s rotation table). It sits in the wall ring, so it is
  part of the immutable shell and cannot be broken. `placeCornerLeavePad`
  became `placeWallLodestone`; `Instances.stampLobby`, `VisitService`, and
  the `entrance_hall`/`selector_room` template decor all stamp it.
  `Instances.isOnRoomLeavePad` now asks whether a lodestone sits in the wall
  one block up and one horizontal step from the player's feet
  (rotation-proof, the same shape as the block-below test
  `isOnExitPad` uses), while still honouring a pre-M18 saved blob's floor
  lodestone; the stand-on trigger itself stays until M21 replaces it.
- **Double doors on the selector opening:** `Instances.generateBehindLobby`
  places two vanilla oak doors side by side in the freshly punched doorway
  (Y=1..2) with a wall lintel (Y=3) the moment a door is chosen, replacing
  the bare 2x3 air hole. Opposite hinges make them meet in the middle like a
  real double door. They are plain vanilla doors once the run is underway:
  `selectorDoorStep` no longer claims clicks, so right-clicking opens them by
  hand, and closed doors stop mobs walking into the room. They sit in the
  wall ring, so the shell protection keeps them from being broken mid-run.
- **Ceiling: top slabs and stair-framed fixtures:** `buildShell` now writes
  `CEILING_SLAB` (stone-brick slab, `type=top`) on interior ceiling positions
  and keeps full blocks on the edge ring, and `placeFixture` frames each of
  the four sea lanterns with four stone-brick stairs at Y=6, tall back
  against the lantern, short stepped side facing outward (the brainstorm's
  code sketch had the facings backwards; the plan's own prose, "tall back
  toward the lantern", is what shipped). Stairs are transparent to light, so
  the room stays fully lit. Verified against the 26.2 jar that
  `StructureTemplate.placeInWorld` applies `BlockState.mirror` and `rotate`
  to every placed block, so stair `FACING` rotates with the template and a
  captured and re-placed room keeps its fixtures at any rotation.
- **Capture hygiene for the double doors (trap 17):** the post-selection
  doors are run-scoped mod furniture, not part of the room, so
  `RunLifecycle.saveRoom` and `completeDungeon` clear them (restoring the
  punched slot to air) around every `RoomStore.capture`, and `saveRoom` puts
  them straight back for a live room. Without this they would bake into the
  owner's blob and leak into the entrance cell of any dungeon stamped from it
  (`LayoutStamper`'s room overlay path).
- **New pure-Java test:** `RoomShellTest` (`roomShellTest` Gradle task, wired
  into `tasks.test`) sweeps every interior position (never shell), the full
  floor and ceiling rows (always shell), the wall ring, the out-of-box
  positions, and a non-zero room origin, so the coordinate logic is verified
  headlessly.

**Headless-verified:** `./gradlew build` green, `RoomShellTest` passing, and
all seventeen room templates regenerated through the dev server
(`/dungeon admin gentemplates`) with the new ceiling, fixtures and wall
lodestone baked into the `.nbt` files.

**Live-only, not yet verified:** breaking a wall block (refused), breaking an
interior block (works), opening the double doors, standing in front of the
wall lodestone, and looking up at the ceiling; recorded as section 22 in
`LIVE_TEST_PASS.md`.

## M19: Physical door selection

**Goal:** the three selector doors, a `text_display` screen above them, copper
bulbs as selection indicators, and a lever as the commit replace the
dialog-based door offer, and a separate engine terminal (a respawn anchor)
manages fuel. No popups: the walk between doors is the browse, the lever pull
is the commit. Second milestone of `docs/reference/ROOM_UX_PLAN.md`'s Room UX pass.

- **`selectedStep` on `InstanceRecord`:** 0 = no selection, 1/2/3 = the
  selector door the owner last right-clicked. In-memory like every other
  field on the record (no codec, no migration); reset the moment a run starts
  (`Instances.generateBehindLobby`) and when the room is re-armed behind the
  terminal cell (`RunLifecycle.completeDungeon`); a fresh lobby always news a
  record, so the default never carries over.
- **`isFurniture` protection:** a wall-relative coordinate test alongside
  `isShell`, covering the four copper bulbs and the commit lever in the row
  in front of the selector wall, the black concrete door screen set into that
  wall, and the engine block with its own screen on the wall to the left
  (`RoomGeometry.leftOf`). Wired into the break guard
  (`RoomProtection.beforeBlockBreak`) and the placement guard
  (`RitualListener.onUseBlock`); the mod itself writes through
  `RoomBuilder.set`, which bypasses `RoomProtection` entirely.
- **`DungeonScreen`:** the door screen and the engine screen are
  `text_display` entities following Hearsay's `Bubbles` pattern, verified
  against the 26.2 jar: `see_through=false` (MC-277982), forced brightness,
  the `text` tag as an NBT object via `ComponentSerialization.CODEC` (the
  shape since 1.21.5), billboard `fixed` with a wall-matching `Rotation`, a
  transformation scale sized so the door screen fills its 8x2 backdrop at two
  lines per block, and an entity tag for orphan cleanup. Five door contexts
  (idle, preview, run in progress, room mode, refusal) plus the engine
  screen's fuel/cost lines are built here; the room-mode context's name and
  visibility lines wait on M20's fields. The screens are transient: summoned
  at every stamp, re-summoned after every capture sweep that discards them,
  and killed by the teardown entity sweep.
- **Furniture placement and capture hygiene (trap 17):**
  `placeFurniture`/`clearFurniture` on `RoomTemplateGenerator` stamp and
  remove the bulbs, lever, screens and engine relative to the selector wall,
  restoring wall-ring positions to wall and door-row positions to air. Every
  `RoomStore.capture` (`RunLifecycle.saveRoom`, `completeDungeon`) clears the
  furniture first so it never bakes into the owner's blob and leaks into the
  entrance cell of a dungeon stamped from it; every stamp re-places it, and
  `saveRoom` restores the selection state (bulb and preview) for an owner who
  left mid-choice.
- **Selection and commit:** right-clicking a door (`RitualListener.selectDoor`)
  lights that door's copper bulb, darkens the previous selection, lights the
  ready bulb above the lever, and puts the offer on the door screen. Pulling
  the lever (`pullLever`) starts the run through `RunLifecycle.chooseOffer`
  with `record.selectedStep`, or writes the refusal to the door screen
  ("Select a door first", "Not enough fuel", the level gate) and consumes the
  click so vanilla's lever toggle never runs.
- **Engine terminal:** right-clicking the anchor with the fuel item spends
  one shard and raises the anchor's charge level (purely visual: fuel is
  inventory-based since M12), and every click refreshes the engine screen
  with the viewer's fuel count and the per-door cost. The intercept fires
  ahead of vanilla's Nether glowstone charge; a player's own anchor anywhere
  else is untouched.
- **The dialog path is deleted:** `DialogScreens.doorOffer` and
  `RitualListener.sendDoorOffer` are gone, and with them the "Take this key"
  button. There was never a `DialogRouter` dispatch for the offer: the dialog
  used a command button, so nothing moved in `DialogRouter`. `/dungeon choose
  <step>` stays as a power-user shortcut.
- **New pure-Java test:** `RoomFurnitureTest` (`roomFurnitureTest` Gradle
  task, wired into `tasks.test`) pins the `isFurniture` coordinates per
  selector wall: the bulb row and lever, the door screen, the engine block
  and screen on the left wall, the interior-never-furniture rule, the
  outside-the-box rule, the selector-wall dependence of the same world
  position, and a non-zero room origin.

**Headless-verified:** `./gradlew build` green, `RoomFurnitureTest` passing,
`selectedStep` as a plain in-memory field (no `DungeonLog.Entry` codec change,
so no migration), and the lever/engine call paths compiling against the live
`RunLifecycle.chooseOffer`.

**Live-only, not yet verified:** bulb toggling, the screens' rendering and
positioning (the fixed-billboard yaw and the transformation scale are derived
from the 26.2 renderer bytecode but need a client to confirm), the lever pull
flow, the engine feed flow, and breaking each furniture block (refused);
recorded as section 23 in `LIVE_TEST_PASS.md`.

## M20: Visiting rework: lobby directory, no calling card

**Goal:** the hand-traded calling card is replaced by a lobby directory: a
`MultiActionDialog` listing every online player whose room is publicly
listed, with room name and occupancy, opened from the room's wall lodestone.
Privacy becomes a host-set `publicListed` toggle, not a token. Third
milestone of `docs/reference/ROOM_UX_PLAN.md`'s Room UX pass.

- **`publicListed` and `roomName` on `DungeonLog.Entry`:** two new record
  components persisted via `optionalFieldOf` with safe defaults (`false`,
  `""`), the same shape as `completedThemes`. No migration: a save written
  before M20 loads unchanged and simply gets the defaults. New setters
  `setPublicListed`/`setRoomName` on `DungeonLog` follow the
  `setKeystone`/`clearPendingOffer` shape (live map write plus `setDirty`).
- **The lobby directory (`DialogScreens.lobbyBrowser`):** a `MultiActionDialog`
  with one button per online public-listed room, the same shape as
  `partyRoster`. Each button's label is the room name (or the owner's name
  when unset) plus the occupancy count; the body line shows the owner's name
  and live status (`open`, `run in progress`, `away`). Each button carries the
  owner UUID as `KEY_TARGET` and the clicker UUID as `KEY_OWNER`, the same
  keys the whitelist dialog uses, so `DialogRouter`'s owner-check applies
  unchanged. An empty directory shows a `NoticeDialog` ("No public rooms
  right now.") rather than an empty button grid. The pure
  `lobbyRows`/`lobbyBrowserDialog` half keeps the listing rule testable
  headless. The pagination decision from `DIALOGS_SPEC.md` section 7
  (`DialogListDialog` vs SGUI) stays open: this mod ships `MultiActionDialog`
  and switches only if a live server's room count overflows it, per the
  plan's own instruction.
- **`VisitService.statusOf`/`occupancyOf`:** the live status read for the
  directory, computed from the same instance state the visit routing uses
  (`InstanceRegistry.byMember` for "run in progress", `findOwnedLiveRoom`
  for "open", otherwise "away") so the directory can never show a room as
  visitable that a click could not enter. Occupancy is the live room's
  `members` size, or 0 when no live instance exists.
- **`DialogRouter` dispatch (`pd_visit_room`):** parses `KEY_TARGET` as a
  UUID, re-verifies `KEY_OWNER` against the clicking player (the same
  owner-check the whitelist actions use), and calls `VisitService.visit`
  (the unchanged M3 visit call, now invoked from a dialog button instead of
  card use-on-lodestone). On failure the directory is re-shown from live
  state with a reason line, per the stale-state guard in `DIALOGS_SPEC.md`
  section 7: a rejected click still ends on a screen the player can act from.
- **Room management commands:** `/dungeon room public`, `/dungeon room
  private`, and `/dungeon room name <text>` (16-char cap, matching the
  whitelist-name dialog) replace `/dungeon room card`. Player-only, not
  op-gated, same as the old card command. If M21's menu lands later, its
  Manage Room option calls the same setters; the commands stay as power-user
  shortcuts either way.
- **Invocation:** the browser opens on a right-click of the room's own wall
  lodestone with anything but a keystone (a keystone still starts a dungeon,
  so the keystone branch stays ahead). The branch carries the
  `// M21: replace this with lodestoneMenu` marker; M21's menu will call the
  same `lobbyBrowser` from its Browse Lobbies option.
- **The calling card is deleted:** `CallingCard.java` entirely, the card
  branch in `RitualListener.onUseBlock`, `CallingCard.warmUp()`, the
  `callingCardItem` config field, the `/dungeon room card` command, and the
  `Payout.deliver(owner, CallingCard.mint(...))` delivery. Doc references in
  `RerollStation`, `GambleStation`, and `InstanceRecord` were updated. The
  self-visit chat line in `VisitService.visit` no longer mentions the card.
  What stays: `VisitService.visit`/`createVisitInstance`, `RoomWhitelist`,
  and `RoomProtection` all unchanged; only the invocation path changed.
- **New pure-Java tests:** `LobbyBrowserTest` (`lobbyBrowserTest` Gradle
  task, wired into `tasks.test`) pins the row filter against a mock player
  list: listed players appear, unlisted do not, an empty directory is a
  notice, and each button's payload carries the owner and clicker UUIDs.
  `DungeonLogTest` gains a `publicListed`/`roomName` codec round trip and a
  pre-M20-save decode that gets the safe defaults. The dialog construction
  needs `SharedConstants.setVersion` + `Bootstrap.bootStrap()` because the
  dialog codecs resolve vanilla ids in static initializers, even headless.

**Headless-verified:** `./gradlew build` green, `LobbyBrowserTest` and the
extended `DungeonLogTest` passing, the codec round trip confirmed, and the
pre-M20 save format still decoding to the new defaults.

**Live-only, not yet verified:** the lobby browser dialog itself, clicking a
room button and teleporting in, the `/dungeon room public|private|name`
commands, the wall-lodestone right-click invocation, and confirming
`/dungeon room card` is gone; recorded as section 24 in `LIVE_TEST_PASS.md`.

## M21: UX consolidation: one lodestone, one menu

**Goal:** five distinct lodestone interactions collapse into one right-click
menu on the wall lodestone. The stand-on leave-pad is deleted. The
`hasInstance` guard inverts: right-clicking while in a dungeon opens the
in-dungeon menu instead of blocking. Fourth milestone of
`docs/reference/ROOM_UX_PLAN.md`'s Room UX pass.

- **`DialogScreens.lodestoneMenu`:** a `MultiActionDialog` whose buttons
  depend on context, the same shape as `partyRoster` and `lobbyBrowser`, with
  a pure `menuOptions(inDungeon, roomOwner)` half for the headless test.
  Overworld (not in the dungeon dimension): Start Dungeon, Browse Lobbies,
  Manage Room, Inspect Keystone. In dungeon: Leave, Manage Room (only when
  `InstanceRegistry.byMember`'s record owner is the player), Inspect
  Keystone. Every button carries `KEY_OWNER` so `DialogRouter`'s owner check
  applies unchanged.
- **Context detection is the dimension, exactly as the plan's implementation
  notes dictate:** `player.level().dimension().equals(PocketDungeonsMod
  .DUNGEON_LEVEL)`, the same check `RoomProtection` uses. Note on the plan's
  done-when wording: the room is stamped in the dungeon dimension, so the
  wall lodestone lives there and "right-click the wall lodestone in the
  overworld" reads as "right-click a lodestone while not in the dungeon":
  the overworld menu opens on any lodestone outside the void, and the
  in-dungeon menu opens on the room's wall terminal.
- **Position check:** the in-dungeon menu opens only on the room's wall
  terminal. Implemented as "the clicked lodestone sits inside a live room
  cell" (`Instances.roomOriginAt(pos) != null`) rather than a literal
  comparison against `origin + (1, 2, 0)`: `completeDungeon` relocates the
  room at an arbitrary rotation (M2's closed loop), so the baked-in wall
  lodestone lands at a rotated world position a fixed local-coordinate check
  would miss. The wall lodestone is the only lodestone inside a room cell
  (the same fact M20's transient branch comment relied on), and the terminal
  pad's lodestone at the dungeon's end sits outside every room cell, so the
  test distinguishes exactly the two surfaces the plan wants distinguished.
- **The keystone is checked on the click, not on menu open.**
  `ACTION_START_DUNGEON` verifies the main hand (`Keystone.isKeystone`) and
  refuses with "Hold a keystone to start a dungeon." when it is missing,
  then calls `RunLifecycle.enterWithKeystone` (which still handles free
  re-entry). The `RESPAWN_ANCHOR_CHARGE` cue and the "The lodestone pulls
  you under." line moved from the block-use handler onto this dispatch, so
  the entry cue survives the two-step flow.
- **`RitualListener` rewrite:** the keystone-only lodestone handler, M20's
  transient lobby-browser branch (its `// M21: replace this with
  lodestoneMenu` marker) and the `hasInstance` refusal are all gone. The
  lodestone branch is now: ritual enabled, the block is a lodestone, not
  sneaking, then the menu, with the in-dungeon position gate ahead of it.
  The reroll, gamble, cube, engine, lever and selector-door branches stay
  ahead of the lodestone check, untouched: door selection stays physical
  (M19), never a menu option.
- **`Instances.isOnRoomLeavePad` deleted** along with its watcher branch;
  `onPad` now reads only `isOnExitPad`. The terminal pad's stand-on
  completion (`completeRun`, the `completed.contains(member)` edge) and the
  untimed/admin exit branch are unchanged; only the room's leave pad went.
- **Manage Room sub-dialog (`DialogScreens.manageRoom`):** the whitelist's
  remove buttons plus "Add a player..." (reusing `whitelistAdd`), a "Set
  room name..." text-input form (`roomNameInput`, the `whitelistAdd` shape,
  16-char cap matching `/dungeon room name`), and a "Room is public / Room
  is private" toggle showing the current `publicListed` state.
  `ACTION_SET_ROOM_NAME` and `ACTION_TOGGLE_PUBLIC` call
  `DungeonLog.setRoomName` / `setPublicListed` and re-show the manager, so a
  click never ends on a closed screen with the change unmade.
  `/dungeon room public|private|name` stay as shortcuts and call the same
  setters.
- **Inspect Keystone shares one builder:** `DialogScreens.inspectKeystone`
  backs both the menu option and `/dungeon key info`; the
  "You are not carrying a keystone." refusal stays in the two call sites.
- **Commands unchanged:** `/dungeon`, `/dungeon exit`, `/dungeon key`,
  `/dungeon choose`, and the `/dungeon room ...` subtree all stay; the menu
  calls the same underlying methods, per the plan's "commands stay as
  power-user shortcuts".
- **New pure-Java test:** `LodestoneMenuTest` (`lodestoneMenuTest` Gradle
  task, wired into `tasks.test`) pins the option lists (4 overworld, 3
  in-dungeon owner, 2 in-dungeon visitor), the action ids, and the
  button payloads' `KEY_OWNER`.

**Headless-verified:** `./gradlew build` green after every commit,
`LodestoneMenuTest` passing, and the full dispatch chain compiling against
the live `RunLifecycle.exit`/`enterWithKeystone` signatures.

**Live-only, not yet verified:** the menu dialog itself, the four context
button lists on a real client, clicking each option, the leave-pad deletion
(walking over the old position does nothing), and the terminal pad still
completing; recorded as section 25 in `LIVE_TEST_PASS.md`.

## M22: Sound pass

**Goal:** a `Chime.java` class following the spiritwolves/wondrous pattern,
one static method per event, each sending a `ClientboundSoundPacket` to the
player's connection so only they hear it. All vanilla `SoundEvents`, no
custom sound files, no assets, server-side only. Final milestone of
`docs/reference/ROOM_UX_PLAN.md`'s Room UX pass.

- **`Chime.java` (new, ~150 lines):** one `public static void <cue>
  (ServerPlayer)` per event, each a one-line call to a private `play`
  helper that builds the packet at the player's position with
  `SoundSource.RECORDS` and a fresh seed, and sends it down
  `player.connection.send(...)`. The `NOTE_BLOCK_*` constants are typed
  `Holder.Reference<SoundEvent>` and pass straight into the packet's
  `Holder<SoundEvent>` parameter; the plain `SoundEvent` constants
  (`RESPAWN_ANCHOR_CHARGE`, `ENDERMAN_TELEPORT`, `STONE_PLACE`) are wrapped
  in `Holder.direct(...)` — the wondrous line-45 shape, verified against the
  26.2 jar's `ClientboundSoundPacket` constructor
  (`Holder<SoundEvent>, SoundSource, double, double, double, float, float,
  long`). Multi-note cues are two back-to-back `play` calls: run complete
  (bell then chime, rising), keystone depleted (bass, descending 0.8 to
  0.6), visitor arrives (bell, 1.0 then 1.2).
- **Every cue table event has a call site.** Door selection: `doorSelected`
  at the end of `RitualListener.selectDoor`, `noSelection` in
  `pullLever`'s selectedStep == 0 branch, `runStarts` in `pullLever` after
  `chooseOffer` returns true. Run lifecycle: `runComplete` at the end of
  `RunLifecycle.completeRun`, `runTimedOut` inside `expireTimedOut`'s
  `owner != null` block (never fires for an offline owner),
  `keystoneLevelUp` after `Keystones.grantOffer`'s reconcile,
  `keystoneDepleted` inside `returnTo`'s `returned < level` block when the
  outcome is `TIMED_OUT` or `LATE`. Room and menu: `menuOpens` after the
  lodestone menu's `DialogKit.show`, `roomListed`/`roomUnlisted` at all
  three `setPublicListed` call sites that have a player
  (`DialogRouter.togglePublic`, `DungeonCommands.roomPublic`,
  `roomPrivate`), `visitorArrives` in `VisitService.createVisitInstance`
  sent to the room owner, not the visitor, `roomRelocated` in
  `completeDungeon` after the room is re-placed behind the terminal cell.
  Lobby visiting: `lobbyOpens` after the lobby browser's `DialogKit.show`,
  `visitStarts` on all three of `VisitService.visit`'s success paths,
  `visitEnds` in `RunLifecycle.exit` when `record.visitInstance`.
- **The one broadcast migrated:** the `RESPAWN_ANCHOR_CHARGE`
  `level.playSound(null, ...)` in `DialogRouter.startDungeon` is gone,
  replaced by `Chime.runStarts(player)` — the cue now plays only for the
  player who started the run, not the whole room, and the now-unused
  `SoundEvents`/`SoundSource` imports were removed from the file.
- **Landed divergence: the spawner-cleared cue is a small watcher, not a
  one-liner.** There is no per-cell spawner-clear event anywhere in the
  codebase to hook (the only clear read is `TrialContent.countCleared`'s
  completion-gate call on pad contact), so the plan's "no new hooks, no new
  listeners, no new state" cannot apply to that one cue. `InstanceRecord`
  gains an in-memory `clearedCells` set (dies with the instance, never
  persisted, same as `visited`), and `Instances.onTick`'s per-record loop
  watches `layout.trialSpawners()` grouped by cell via
  `layout.geometry().cellAt(pos)` for keystone runs that are not lingering
  and not already completed; the moment a cell's every spawner sits at
  `COOLDOWN` (the exact `TrialContent.countCleared` predicate), the cell is
  added to `clearedCells` once and `Chime.spawnerCleared` fires to each
  member currently standing in that cell. One field and one watcher block,
  no codec, no migration. The plan's M22 touch-points were fixed to say so;
  the cue could not be faked by firing on completion (that would mislead the
  player into thinking the gate had passed).
- **The chime is a side effect, never a gate:** no `connection.send`
  try/catch, no return-value check, no config toggle, no second mixin.

**Headless-verified:** `./gradlew build` green after every commit, every
`SoundEvents` constant and the `ClientboundSoundPacket` constructor verified
against the 26.2 jar (`javap`), and each cue method compiling against the
live call sites. No runtime test is possible without a client.

**Live-only, not yet verified:** every cue playing, each heard only by the
relevant player (except `visitorArrives`, heard by the owner), at a sensible
volume; the volumes and pitches are starting points to tune during live
testing; recorded as section 26 in `LIVE_TEST_PASS.md`.

## M18-M22 review fixes

Five findings from the M18-M22 review, one commit each, all green on
`./gradlew build` (roomShellTest, roomFurnitureTest, lobbyBrowserTest and
lodestoneMenuTest included).

- **Stale directory clicks now refuse.** `DialogRouter.visitRoom` re-reads
  the target's `publicListed` flag and the owner's online state before
  routing to `VisitService.visit`; a room that went private, or an owner
  who logged off, while the directory sat open gets a re-shown directory
  with a reason line instead of a visit on stale faith.
- **Lodestone-menu start now reaches free re-entry.** `startDungeon` no
  longer gates on a keystone in the main hand; it routes straight into
  `RunLifecycle.enterWithKeystone`, which re-enters a live owned instance
  for free and reads the keystone from the inventory, matching `/dungeon`.
- **Spawner-cleared watcher caches its grouping.** `InstanceRecord` now
  carries the per-cell spawner grouping, rebuilt only when the layout
  identity changes (a second run behind the same lobby reuses the record)
  instead of on every watch tick. `clearedCells` is also cleared in
  `generateBehindLobby`'s per-run reset block, so the once-per-cell-per-run
  cue fires again for the next run.
- **Fluid placement is gated like block placement.** The room-protection
  placement gate now covers `BucketItem` as well as `BlockItem`
  (`RitualListener.isPlacementSource`): a bucket aimed at the shell or the
  furniture is denied to everyone, and a non-permitted player can no longer
  pour water or lava in a room they are only visiting.
- **One edited comment line lost its double hyphen** (the reroll-station
  note in `RitualListener`), per house style.

**Headless-verified:** `./gradlew build` green after each commit.

**Live-only, not yet verified:** the stale-click refusals (fix 1) and free
re-entry without a keystone in hand (fix 2); recorded as section 27 in
`LIVE_TEST_PASS.md`.

## M23: Room template editor (buildroom / saveroom)

**Goal:** a dev tool for hand-authoring room templates in Minecraft. An
operator opens an empty shell in the void dimension, builds a room by hand,
and saves it to a `.nbt` file in the mod's own template directory, where an
AI (or a human) can read it and generate matching code. Two commands, both
op-gated under the existing `admin` literal and both development-only like
`gentemplates` and `stamptest`, since writing into the mod's source tree is
a dev-environment act.

- `/dungeon admin buildroom` stamps an empty 16x16x6 cell
  (`RoomBuilder.buildShell`) at the next free slot, registers it as an
  `InstanceRecord` carrying a new `adminBuild` flag, and teleports the
  operator into the shell's centre. No doors, no timer, no keystone, no
  protection, no room-store capture. The record's `roomCellOrigin` stays
  null, so nothing ever treats the shell as a player room: teardown never
  saves it back to the owner's blob, `RoomProtection.roomOwnerAt` never
  matches it, and the shell is fully breakable by anyone. One per player:
  opening a build room while one exists tears the old one down first. It
  refuses while the operator is inside a live instance, so `admit` can never
  clobber another record's membership. `admin list` marks the slot
  `(BUILD ROOM)`.
- `/dungeon admin saveroom <name>` finds the player's build room, captures
  the cell with `StructureTemplate.fillFromWorld` (entities included, like
  the generator's own captures), writes `pd_author` (the player's UUID) and
  `pd_saved_at` (epoch millis) onto the blob, and writes
  `data/pocketdungeons/structure/rooms/<name>_<author>_<timestamp>.nbt`
  through the same `templateOutDir()` the generator uses. Both tags are
  unknown to `StructureTemplate.load`, so the file stays loadable by the
  room manifest system like any shipped template. It then drops the member
  (so the teardown has nobody to eject back to the old return point), sends
  the operator to the overworld spawn, and purges the instance.
- **Landed divergences from the handoff:** the plan's dependency check for a
  `RoomStore.captureAndSave` came back negative (the capture machinery lives
  privately in `RoomTemplateGenerator`, which already owns the resources
  output directory), so the saveroom capture was placed there as
  `captureRoomToFile` rather than added to `RoomStore`; and the commands
  register in `DungeonCommands.java`, which is the actual name of the file
  the handoff calls `PocketDungeonsCommands.java`.

**Headless-verified:** `./gradlew build` green; both subcommands register
under the op-gated `admin` literal and compile against the live call sites.

**Live-only, not yet verified:** the full open/build/save loop; recorded as
section 28 in `LIVE_TEST_PASS.md`.

---

## M24: Room shells and prestige

**Goal:** players discover and unlock alternate shell materials (sandstone,
deepslate, nether brick) from rare adventure nodes and long-term room
ownership. A shell swap replaces only the immutable shell blocks; the
interior stays untouched.

- **Shell storage on `DungeonLog.Entry`.** `unlockedShells` (a never-shrinking
  set of palette names, the same shape as `extractedPowers`) and
  `roomCompletions` (the prestige count) persist with safe defaults, so a
  pre-M24 save loads unchanged. Unlocks are per-player, not per-room, and
  survive room resets; the prestige count is the one thing a reset wipes.
- **`RoomBuilder.rebuildShell`.** The orchestration the handoff asked for,
  with one real refinement. The handoff sketched "capture the interior,
  stamp the new shell, re-place the interior"; the shipped sequence captures
  the whole cell as the safety net (`RoomStore.capture`), stamps the new
  shell (`stampShell`, interior becomes air), re-places the blob
  (`RoomStore.place`), then re-stamps only the shell positions
  (`stampShellBlocks`). The last pass is the refinement: the blob carries
  the old frame, so re-placing it first would put the old material back, and
  re-stamping the frame positions afterwards is what leaves the interior
  restored and the frame in the new material. Run-scoped furniture and doors
  get the same capture hygiene `saveRoom` applies (clear before capture,
  re-arm after); the wall lodestone is re-placed (it is shell); and the
  doorway slots are re-opened or re-sealed from the room's live state,
  because the post-completion room's ee door stands open while a lobby's is
  sealed and both carry `awaitingDoorChoice=true`, so the flag alone cannot
  tell them apart.
- **Four palettes, two unlock paths.** `oak` is the always-available default
  (the menu option is the tutorial). `sandstone` and `deepslate` are rare
  tokens: vanilla material blocks, renamed and marked with
  `custom_data.pocketdungeons.shellUnlock`, added as a chance pool in the
  drowned vault's themed completion chests
  (`chests/tier_{1,2,3}_drowned.json`), the one rare node the loaded graph
  has. `nether_brick` is the prestige reward: 10 completions while holding
  the same room without a reset, counted in `RunLifecycle.completeRun` for
  the owner and zeroed by `/dungeon admin resetroom`.
- **The Change Shell screen.** The in-dungeon owner's wall-lodestone menu
  gains a Change Shell option (in-dungeon only: the frame exists only while
  the room is stamped). The picker shows the current frame, one Apply button
  per usable palette (default plus unlocks), grayed-out hint lines for the
  locked ones, and an Unlock button when the player is holding a token.
  Apply dispatches `RoomBuilder.rebuildShell`; Unlock consumes the held
  token one-for-one. Both re-validate against live state on the click,
  the same staleness discipline as every other routed dialog action.

**Headless-verified:** `./gradlew build` green after each commit;
`unlockedShells`/`roomCompletions` codec round-trips and pre-M24 defaults in
`DungeonLogTest`; the four-palette registry in `ShellPaletteTest`; the
four-option in-dungeon owner menu in `LodestoneMenuTest`.

**Live-only, not yet verified:** finding a token in a rare room's completion
chest, unlocking it, and swapping a furnished room's frame with the interior
intact; recorded as section 29 in `LIVE_TEST_PASS.md`.

---

## M25: Pocket2 Dungeon

**Goal:** a dungeon within a dungeon. During a run, the player finds a rare
door in a cleared encounter room leading to a short, intense sub-dungeon with
a hard timer. Grab what you can before the clock runs out.

- **Child instance records.** InstanceRecord gains parentSlot and
  eturnPos (plus deadlineTick), so a child is linked to its parent and
  knows exactly where to send its members back to. InstanceRegistry gains
  llocateSlotNear, which keeps the child's slot adjacent to the parent's on
  the grid. Children carry keystone level 0, no affixes and no owner room, so
  isKeystoneRun() is false for them: no spawner gate, no completion pad, no
  keystone settlement, nothing to pay for entry.
- **Rare door placement.** LayoutStamper rolls one door per run off the plan
  seed, gated on the run's theme having an adventure-graph node (an unthemed
  run, or one whose theme the datapack never declared, never hosts a door).
  The first encounter cell with a sealed wall hosts a 2-wide iron door; the
  position rides on InstanceLayout.pocket2Door so the right-click handler
  never scans the world. Entry additionally requires the door's cell to be
  fully cleared (all trial spawners at COOLDOWN), which is the M10 dependency
  the milestone lists: the pocket cannot be used to dodge a fight.
- **Door interaction.** RitualListener hands the click to
  Pocket2.tryEnter, which enforces one child per parent (childFor), then
  plans a 4-5 cell child (straight path, no branches or loops: a 3-path has a
  single interior cell that the role guarantee can only give to encounter or
  loot, never both, so 4-5 is the low end that always yields a spawner and a
  chest), stamps it into the adjacent slot, and teleports the player in. The
  player never leaves the parent's roster: parent.members still holds them, so
  the outer run's clock keeps ticking and the pocket's time counts against the
  outer run. The child's own roster is a copy, kept so a teardown that
  outlives the parent still knows where to send everyone home.
- **Countdown and teardown.** Instances.onTick routes child records to
  Pocket2.tickChild, which ticks the child's own boss bar (RunTimer gains
  a title variant so it reads "Pocket - 0:45" instead of a keystone label)
  and expires on deadlineTick. Expiry returns every member to the parent at
  the door (eturnPos) and purges the child. Death inside the pocket routes
  through Pocket2.dieInChild instead of a dungeon-wide rescue: same reset,
  but the destination is the parent at the door, and the parent's keystone is
  settled NO_CHANGE so the outer run's death penalty still applies. A parent
  teardown now cascades to its children (purgeChildren in
  InstanceTeardown), so the pocket never outlives the run it hangs off.
- **Loose loot.** The child's content pass converts every vault to a plain
  chest and points every chest at the new pocketdungeons:chests/pocket2
  table: shell unlock tokens (M24), echo shards for fuel, and valuables. The
  1-2 trial spawners the small plan naturally produces stay as the pocket's
  loose drops.

**Headless-verified:** ./gradlew build green after each of the four commits;
Pocket2Test round-trips the child record, its parent linkage, its deadline
and the near-parent slot allocation.

**Live-only, not yet verified:** finding a rolled door in a cleared encounter
room, stepping through, watching the 60-second countdown, and being ejected
back to the parent at the door when the clock hits zero; recorded as section
30 in LIVE_TEST_PASS.md.

## M26: Lore delivery

**Goal:** the lore (Alex's diaries, Alex's Room, the keystone's own recovery-
compass framing) lands as fragmentary, discoverable content, never touching
mechanical naming. `VISION.md` section 9 and `docs/reference/LORE.md` were
already updated to permit this before this milestone started; nothing
further changed there.

- **Diary storage and delivery.** `DungeonLog.Entry` gains `diaryBandsSeen`
  (`Set<Integer>`, never-shrinks, same shape as `unlockedShells`). `Diaries`
  loads `data/pocketdungeons/diary/*.json` the same way `AdventureGraphs`
  loads adventure nodes: one file per entry, malformed or colliding files
  rejected loudly rather than silently picked between. The seven already-
  authored entries (`docs/reference/LORE-DIARIES.md`) ship as `entry_1.json`
  through `entry_7.json`.
- **Band-to-entry mapping.** Journals unlock in their own numeric order as
  the ladder climbs (Entry 1 first, Entry 7 last) but spread across seven of
  the twelve intensifier bands (Baby, Highkey, Unhinged, Unholy, Forsaken,
  Apocalyptic, Transcendent) rather than packed into the first seven, so a
  normal climb does not clear the whole set in one stretch. "Found out of
  order" instead comes from the physical book: `DiaryDelivery` shuffles its
  page order per drop, while the lodestone reader always shows the canonical
  order from the same source data.
- **Delivery mechanism.** `DiaryDelivery.deliverIfEligible`, called from
  `RunLifecycle.completeRun` after every keystone-level change a run can
  still make has settled, hands the book to the completing player directly
  (`Payout.deliver`) rather than into the shared completion chest the
  handoff described: that chest is instance-shared and lazily loot-table-
  filled for the whole party, with no slot that belongs to one player's own
  band crossing.
- **Action-bar read-out.** `DiaryReading` scrolls a found diary's pages
  across the action bar the way Hearsay's villagers speak over the same
  line, one character-width step at a time. Same
  `ServerTickEvents.END_SERVER_TICK` registration shape as `TrimListener`
  and `PowerListener`. Purely ambient; the physical book and the lodestone
  reader are unaffected.
- **Diary reader.** A "Diaries" option on both the overworld and in-dungeon
  lodestone menus (`ACTION_DIARIES`) opens a list of all seven entries: a
  discovered one is a button into a reader screen; an undiscovered one is a
  plain grey "Entry N: ???" line, the same locked-content shape
  `shellPicker` already uses for an unfound shell rather than a clickable
  dead end. The reader's Back button round-trips through `DialogRouter`
  rather than holding a client-side reference to the list, so a diary found
  mid-read shows up discovered the moment the player backs out.
- **Alex's Room shell.** A new spruce `ShellPalette` (`RoomBuilder.ALEXS_ROOM`),
  registered in both `SHELL_PALETTES` and the separate `shellOrder()` list
  (missing it from the latter is what `ShellPaletteTest` caught). Its
  `unlockHint` is `"???"` rather than a real hint, unlike every other locked
  palette: the only path to it is `DiaryDelivery`'s Entry 6 trigger, never a
  token or prestige, and the menu should not spell that out.
- **Compass pointing, revised.** The handoff's original plan
  (`LODESTONE_TRACKER`, updated live by `Instances.reconcileKeystones` as
  the player moves through selector doors, the terminal pad, and the room
  lodestone) turned out not to work for the actual keystone item.
  `PocketDungeonsConfig.keystoneItem` defaults to `minecraft:recovery_compass`
  deliberately (`VISION.md` section 3.1.1, `LORE.md` section 5's whole
  narrative), and decompiling `CompassAngleState$CompassTarget` confirmed its
  client model reads only `Player#lastDeathLocation`, never
  `LODESTONE_TRACKER` -- that component only drives the plain
  `minecraft:compass` model. `lastDeathLocation` itself only reaches the
  client inside a login or respawn packet, which only fires on an actual
  dimension change, never on a same-dimension teleport -- and every move
  within a session (room, lobby, run, back) stays inside `DUNGEON_LEVEL`.
  Matching the recovery-compass look to live re-pointing would need a
  custom item-model resource pack (confirmed by comparing `compass.json`
  and `recovery_compass.json`'s texture references), which `VISION.md`
  section 9 rules out outright ("vanilla clients, no resource pack").
  Resolution, decided explicitly rather than defaulted into:
  `Instances.teleport` now sets `Player#lastDeathLocation` at the one moment
  a fresh packet is guaranteed regardless -- crossing into or out of
  `DUNGEON_LEVEL` -- pointing at wherever that teleport is taking the player
  on the way in (their room, most of the time) and clearing back to
  vanilla's own behaviour on the way out, so the compass still spins in the
  overworld exactly as designed. No re-pointing through the selector doors,
  mid-run terminal, or post-completion room individually.

**Headless-verified:** `./gradlew build` green, including the full test
suite (`LodestoneMenuTest` and `ShellPaletteTest` updated for the new menu
option and fifth palette; `DungeonLogTest` covers the codec's
`diary_bands_seen` field through the same `PartA`/`PartB` split M27's
`recentVisitors` addition required).

**Live-only, not yet verified:** entering a new band and finding a diary in
the next completion chest (in practice, handed over directly -- see above);
opening the lodestone menu's Diaries option and confirming discovered
entries are readable and undiscovered ones show "???"; confirming the
action-bar scroll plays on pickup; confirming Alex's Room appears in Change
Shell only after Entry 6 is found; confirming the keystone compass points at
the player's room on entering the dungeon dimension and spins in the
overworld. Not yet recorded in `LIVE_TEST_PASS.md`.

## M27: Extra features

**Goal:** three small features enhancing the existing loop without being
load-bearing. 27.1 and 27.2 ship; 27.3 is deferred.

- **27.1 MrPinoy's Experimental Dungeon.** Rather than a physical fourth
  door, door 3 gets a special state: ExperimentalDungeon holds an in-memory,
  unpersisted offer (theme, elective affixes, an optional loot-level
  override) set by `/dungeon admin experiment <theme> [affixes]
  [lootOverride]` and cleared by `/dungeon admin experiment clear`. Keystone
  gains an EXPERIMENTAL tier; `Keystone.offers` substitutes it for door 3's
  normal Offer whenever one is active, so the preview screen, the commit
  lever's gate, and `RunLifecycle.chooseOffer` all read the same
  substitution without any of them needing to know about
  ExperimentalDungeon directly. EXPERIMENTAL counts as free the same as
  FREE, so an operator testing a fixed offer never needs to bank fuel or
  hold a level first. `DungeonScreen.previewContent` adds a red "CAUTION:
  EXPERIMENTAL" line to the door's text_display whenever the offer showing
  is the experimental one. No per-player daily reward tracking rides on
  this yet, matching the handoff's scope.
- **27.2 Room visitor log.** `DungeonLog.Entry` gains `recentVisitors`, a
  ring buffer of up to `MAX_RECENT_VISITORS` (10) `VisitorEntry(name,
  timestamp)` records, newest first. `VisitService.visit` pushes one entry
  on every path that actually lands a visitor in the owner's room (the
  owner's own live room, an existing visit copy, or a freshly stamped one),
  never on a bare lobby-directory browse. The wall terminal's Manage Room
  screen gains a "Recent visitors..." option; the screen it opens shows
  each entry's name, a rough "how long ago", and whether that visitor is
  still online and standing in the room right now
  (`VisitService.isStillInside`, resolved by name since the log stores no
  UUID). Host-visible only: the screen is reachable only through the
  owner-only Manage Room, and a visitor never sees who else has visited.
- **27.3 Death checkpoint.** Deferred, as directed. A comment in
  `Instances.register`'s death-rescue handler marks where it would hook in;
  no checkpoint logic exists. Contingent on M25's Pocket2 or a future
  extended-dungeon milestone making re-traversal after a death a real
  frustration, which the current short layout does not.
- **The 16-field codec limit.** `DungeonLog.Entry` crossed
  `RecordCodecBuilder.group`'s 16-argument ceiling once `recentVisitors`
  landed alongside M26's `diaryBandsSeen`. `ENTRY_CODEC` now splits the
  record into two intermediate halves (`PartA`, `PartB`), each built with
  its own `group`, and recombines them with `Codec.mapPair` so every field
  still lands as a flat, top-level key: an old `dungeon_log.dat` reads
  exactly as it did before the split.

**Headless-verified:** `./gradlew build` green, including `DungeonLogTest`
(the split-codec round trip) and every other existing suite.

**Live-only, not yet verified:** setting an experimental offer and watching
door 3 show the caution indicator and generate the fixed theme; visiting a
room and confirming the visit shows up in Recent visitors with the right
"still inside" state; recorded as section 31 in LIVE_TEST_PASS.md.

## M28: Themed mob spawners

**Goal:** dungeon themes control which mobs their trial spawners roll, not
just their wall blocks, with new themes added by JSON alone.

- `DungeonThemeMeta` gains an optional `spawnerPrefix` field (`spawner_prefix`
  in JSON), parsed the same way as `lootSuffix`. Null keeps a theme on the
  default tier configs.
- `TrialContent.applyEncounter` takes the run's theme, resolves it through
  `ThemeManifest`, and reads `spawnerPrefix` off its meta before building the
  spawner's config ids. `RoomContent.apply` and `LayoutStamper.stamp` thread
  the theme through from the existing in-scope variable; no new plumbing.
- `TrialContent.configId(prefix, tier, ominous)` (widened from private to
  package-private for the test) produces
  `pocketdungeons:tier_{n}/{normal,ominous}` for a null or blank prefix, and
  `pocketdungeons:{prefix}_tier_{n}/{normal,ominous}` otherwise. The swarming
  affix's inline-config path (`writeInlineConfig`) takes the same prefix, so
  a swarming run on a themed encounter still scales the themed roster rather
  than falling back to the default one.
- Two themed spawner rosters ship as proof of concept, one config file per
  tier per ominous state, six files each: **Crypt** (`spawner_prefix: "crypt"`
  on `deepslate.json`) is zombie (weight 5) and skeleton (weight 4) only,
  reusing each tier's own equipment loot table; **Infestation** (a new
  `dungeon_theme/infestation.json`, discoverable, reusing deepslate's wall
  processors) is spider (weight 3) and cave spider (weight 2) only, no
  equipment. Every other field (counts, ticks, eject tables) is copied
  verbatim from the matching default tier file; only `spawn_potentials`
  changes.
- No other theme sets `spawner_prefix`, so every run except Deepslate and
  Infestation still resolves the pre-M28 default tier configs.

**Headless-verified:** `./gradlew build` green (aside from two pre-existing
failures in `lodestoneMenuTest` and `shellPaletteTest`, unrelated to this
milestone and reproduced on the pre-M28 commit as well, from the in-flight
M26 diary-reader menu work). `TrialContentConfigIdTest` covers a null
prefix, a themed prefix, a blank prefix, and a second theme. Both themed
JSON directories load without a `ThemeManifest`/config-registry rejection.

**Live-only, not yet verified:** running a Deepslate-themed dungeon and
confirming its trial spawners eject only zombies and skeletons; running an
Infestation-themed dungeon and confirming spiders and cave spiders only;
confirming a Swarming-affix run on either theme still spawns only that
theme's roster, scaled up; recorded as section 32 in LIVE_TEST_PASS.md.
