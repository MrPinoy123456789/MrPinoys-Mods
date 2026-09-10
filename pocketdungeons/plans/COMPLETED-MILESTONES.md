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
  the door (
eturnPos) and purges the child. Death inside the pocket routes
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

## M29: No-backwards propagation

**Goal:** dungeons never wrap around behind the player's entrance room. If
the entrance door opens SOUTH, no cell may exist at z < 0 relative to the
entrance; likewise for the other three directions.

- `LayoutGraphGenerator.validate` computes `shape.entranceDirection()` and,
  for every cell in the shape, flags one that falls on the wrong side of
  the entrance axis (`EAST`: `x < 0`; `WEST`: `x > 0`; `NORTH`: `z > 0`;
  `SOUTH`: `z < 0`) with a problem string carrying the
  `NO_BACKWARDS_MARKER` phrase. The check applies to every cell (critical
  path, branches, loops), runs pre-rotation, and is rotation-invariant:
  `DungeonShape.rotate` is a rigid turn about the entrance, which always
  sits at the origin, so nothing behind the entrance before rotation can
  end up behind it after.
- A shape with cells behind the entrance is an expected, unlucky output of
  the backtracker's free branch/loop placement, not a generator bug. The
  handoff assumed `LayoutPlanner.plan`'s 16-attempt retry budget already
  absorbed a `validate` failure this way; it did not; every `validate`
  failure returned immediately as a hard "generator bug" outcome with zero
  retries. `plan` now checks whether every problem in the list carries the
  no-backwards marker: if so, it burns a retry and moves to the next seed,
  exactly like a room-resolution miss; any other problem still hard-fails
  the call immediately, unchanged from before.
- No config toggle shipped (the handoff's step 3 was explicitly optional).
  The check always applies; there was no case for skipping it once the
  retry budget proved to absorb the failures cheaply.

**Headless-verified:** `./gradlew build` green, all existing suites
including `PipelineProofTest` (200/200 seeds, full-library planning
through the same `LayoutPlanner.plan` retry path this milestone changed).
The live-play-profile sweep (M40: moved to `LayoutGraphGeneratorHarness`,
the test-source-set harness) now retries each of
5000 logical seeds through the same 16-attempt budget as `LayoutPlanner`
(non-overlapping seed windows, seed+attempt scheme) and asserts the
resolution rate stays above 95%; it resolves at 100% on the 5-8 path /
0.35 branch / 0.15 loop live-play profile. The heavier 8-12 path / 0.55 /
0.30 survey profile shows a much higher per-attempt rejection rate (7/20
valid on first try), which is expected and exactly what the retry budget
exists to absorb.

**Live-only, not yet verified:** entering a dungeon from each of the four
door directions and confirming no rooms appear behind the player's
entrance room on the reverse axis; recorded as section 33 in
LIVE_TEST_PASS.md.

## M30: Connector variations

**Goal:** varied connector patterns at door openings (wide door, double
door, single door, iron door, bars, open wall with pillars, arch with
lintel), seeded per-edge, without changing cell size, room template
format, or the canonical door slot position.

- The handoff's first draft assumed a runtime door carve in
  `LayoutStamper.stamp` that no longer exists: doors are baked into each
  room's `.nbt` template as `pocketdungeons:door` jigsaws, resolved to
  air by `JigsawReplacementProcessor` when `TemplateStamper.place` stamps
  the room. The revised handoff (after this was surfaced) designed the
  feature as a post-placement overlay instead, which is what shipped.
- `ConnectorType` (new, pure JDK): the 7-value enum with the weighted
  table (DOOR_WIDE 45, DOOR_SINGLE 15, DOOR_DOUBLE 10, IRON_DOOR 10,
  OPEN 10, ARCH 5, BARS 5) and `pick(Random)`, cumulative-weight style
  matching `LayoutGraphGenerator`'s role roll. `rngFor(seed, edge)` seeds
  a `Random` from `planSeed ^ edge.hashCode()` -- `PlanEdge`'s canonical
  `(a, b)` ordering makes this direction-independent, so both cells on an
  edge can derive the same roll.
- `ConnectorGeometry` (new, pure JDK): `rect(cellOrigin, wall, iFrom, iTo,
  yFrom, yTo)`, the same (column index, height) convention as
  `CellGeometry.doorSlotPositions` -- the pure math/level-write split
  `CellGeometry` already keeps, extended to connector rectangles instead
  of just the one canonical slot.
- `ConnectorStamper` (new): applies each type on top of whatever
  `TemplateStamper.place` already wrote. Reads the wall's live block
  (5 columns outside the door slot) rather than hardcoding stone brick,
  so a themed room's re-skin is respected. IRON_DOOR caps the door
  slot's third (unused) row with that same live material rather than
  leaving a gap above the 2-tall door. OPEN clears the middle 12 columns
  and leaves a 2-wide pillar standing at each end of the 16-wide wall.
- `LayoutStamper.stamp`: new `applyConnectors` pass runs after the
  per-cell stamp loop, before `BedrockEnvelope.apply`. Skips the
  entrance's edge entirely (always the default wide opening, no roll) so
  a fresh run can never gate the one door a player is guaranteed to
  reach. One `Random` per edge, reused for the connector-type roll and
  (for DOOR_SINGLE) the left/right column choice, then applied to both
  cells on the edge so the opening stays aligned across the two-block
  partition between them.
- No offset: the door slot stays at the canonical position (7-8) the
  templates' doorway lane rule is authored against, per the handoff's
  constraint.
- `BedrockEnvelope` needed no change: `applyToCell`'s occupied-neighbour
  check already skips every face with a live neighbour, which covers
  OPEN and ARCH's wider clearing the same way it already covered the
  original 2-wide door slot.
- M38 (PD-27/PD-28) later found and fixed two defects in IRON_DOOR
  specifically: nothing anywhere placed a redstone source, so a run
  could roll an unopenable iron door on its critical path (a lever on
  the door frame now ships with it), and the two leaves shared one
  `HINGE`/`FACING` state instead of alternating and facing inward like
  every other door in the pipeline.

**Headless-verified:** `./gradlew build` and `./gradlew test` green
(including the full existing suite) in a clean build taken before an
unrelated, concurrent M32 work-in-progress session temporarily broke
compilation elsewhere in the tree (`RoomSelector.java` not yet updated
for `DungeonPlan`'s new `anomalyCell` field -- unrelated to this
milestone). New `ConnectorTest` (`connectorTest` Gradle task, wired into
`tasks.test`): weight table sums to 100, `pick` is deterministic for a
given seed and covers all seven types over a large sample, `rngFor` is
edge-direction-independent and distinct per edge, `rect`'s bounds/count
match `CellGeometryTest`'s door-slot pattern, and a both-sides test
proving `rect` on cell A's SOUTH wall and cell B's NORTH wall (a
north/south edge) return the same X columns one block apart in Z --
the property `applyConnectors` relies on to keep an opening aligned
across the partition.

**Live-only, not yet verified:** `adminBuild` showing varied connectors
across door edges; each connector type's actual block layout in-world
(OPEN's pillars, DOOR_SINGLE's offset opening, ARCH's lintel, IRON_DOOR
opening on redstone, BARS floor-to-wall-top, DOOR_DOUBLE's 4-wide
opening); the entrance edge always rendering as DOOR_WIDE. Blocked at
the time this milestone's code landed by an unrelated concurrent
session leaving the tree mid-edit; not recorded in LIVE_TEST_PASS.md
yet -- add a new numbered section there once verified.

## M31: Dungeon shell protection

**Goal:** during an active run, the dungeon cells outside any player
room are shell-protected the same way a player room is: floor, walls
and ceiling immutable to everyone, protection lifting automatically
the moment the first member completes the run. Enables M30's
`IRON_DOOR` connector as a real gate rather than a cosmetic one.

- `Instances.dungeonCellOriginAt` (and the shared private
  `dungeonCellLookupAt` it and `dungeonRecordAt` both call): the cell
  origin of the active dungeon cell occupying a position, or `null`
  if the position is not in any active run's dungeon cells or is the
  room cell itself (`roomOwnerAt`'s job, kept out to avoid the two
  lookups double-protecting the same cell). "Active" means the record
  has a layout and `completed` is still empty; once the first member
  completes, every cell of that run returns `null` and the quarry
  becomes breakable again.
- **Scope note: the shipped behavior is narrower than the original
  handoff's plan.** The handoff called for the whole dungeon cell
  (interior included: spawners, chests, everything) to be unbreakable
  during an active run. What shipped instead mirrors the player-room
  model exactly: only the shell (floor, walls, ceiling; see
  `RoomProtection.isShell`) is immutable, and the interior stays
  breakable and placeable throughout the run so a player can dig,
  loot and fight their way through as normal. `RoomProtection.beforeBlockBreak`
  and `RitualListener`'s placement check both branch on
  `dungeonCellOriginAt` and then defer to `isShell`, not a blanket
  denial.
- Protection-lift indicator: the moment `completeRun` reaches the
  `firstCompletion` branch, every member in the dungeon dimension gets
  a green "The dungeon's shell has weakened. You can break blocks
  now." chat line, once, not repeated on a later completion or
  re-entry.
- Container use, redstone interaction (levers, buttons), and trial
  spawner activation were never gated by this check to begin with;
  only block break and block place go through it.

**Headless-verified:** `./gradlew build` green, full existing suite
passing. New `DungeonShellProtectionTest`
(`dungeonShellProtectionTest` Gradle task, wired into `tasks.test`):
break denied inside an active run's dungeon shell, allowed in the
interior and allowed everywhere after completion; `dungeonCellOriginAt`
returns the right cell origin inside an active run, `null` outside and
`null` after completion, and correctly skips the room cell so it never
overlaps `roomOwnerAt`'s territory; an unowned `adminBuild` instance's
shell is denied the same as an owned run's.

**Live-only, not yet verified:** entering a dungeon, confirming shell
blocks are denied and interior blocks are minable during an active
run; completing the run and confirming the shell opens up; the
protection-lift chat message firing once per run. Not recorded in
LIVE_TEST_PASS.md yet; add a new numbered section there once verified.

## M32: Tutorial screen and engine label

**Goal:** the engine screen's title reads "ECHO SHARDS" instead of
"ENGINE"; a first-time (keystone level 1) player sees tutorial prompts on
the door screen instead of the normal idle/preview text.

- `DungeonScreen.engineContent` title literal changed to "ECHO SHARDS".
- `DungeonScreen.idleContent` gains nullable `ServerLevel level` and
  `UUID owner` params; when both are present and
  `DungeonLog.forServer(level.getServer()).get(owner).keystoneLevel() <= 1`,
  it returns "Select the Oak Door / Then pull the lever to descend"
  instead of the normal idle text. All four call sites (`Instances.stampLobby`,
  `RunLifecycle` twice, `RoomBuilder`) pass through the `level`/`owner` (or
  `record.owner`) already in scope.
- `DungeonScreen.previewContent` appends "Pull the lever to descend!" in
  green when `offerLevel <= 1`; no signature change, since it already
  derives `offerLevel` from `DungeonLog`.

**Headless-verified:** `./gradlew build` green, all existing suites
passing. No new test added; `idleContent`/`previewContent` are simple
enough that the existing `DungeonLogTest` coverage of `keystoneLevel()`
is what would need to change to break this, and it didn't.

**Live-only, not yet verified:** the engine screen title, and the
level-1 tutorial prompts on the idle and preview door screens, both
disappearing at level 2+; recorded as section 34 in LIVE_TEST_PASS.md.

## M33: Guided tasks via tracker screen

**Goal:** ten sequential guided tasks teaching the core loops (select a
door, descend, complete a run, feed the engine, visit a friend, open a
Greater door, the three stations, tame a wolf), one active at a time,
surfaced on the door screen and the tracker screen in the player's room.

- `TaskTracker` (new): the `Task` enum (id, label, targetCount, minLevel)
  in sequence order. `activeTask(DungeonLog, UUID, int)` is the pure
  core -- the lowest task not yet at its target, skipping (and marking
  complete) any task whose `minLevel` sits strictly below the player's
  current keystone level, a grandfather clause for a player adopting the
  feature well past where a task would normally introduce them to it.
  `minLevel == 0` (TAME_WOLF) opts out of that clause entirely.
  `activeTask(ServerPlayer)`/`progress(ServerPlayer, Task, int)`/
  `taskLine(ServerPlayer)` are the live wrappers; `progress` only
  advances `task` if it is actually this player's active task right
  now, so a hook firing while a different task is active is a no-op.
- `DungeonLog` gains a `Map<UUID, Map<String, Integer>> taskProgress`
  sidecar (own codec entry, `task_progress`, optional/empty-default) and
  `taskProgress`/`setTaskProgress` accessors, kept separate from `Entry`
  since `Entry`'s codec is already split across two 16-field groups.
- Tracker screen: a physical screen on the wall opposite the engine
  screen (the selector wall's right), showing the owner's active task
  with its progress. Replaces the originally planned scoreboard sidebar:
  instead of a global per-player sidebar, the progress is a third
  physical screen in the room, visible only to whoever is standing in
  it. `DungeonScreen.updateTracker` / `refreshTracker` run whenever the
  door screen recomputes a task line or a task completes.
- Mechanic hooks (`TaskTracker.progress`, all naming their own task):
  `RitualListener.selectDoor` (SELECT_DOOR), the engine terminal's
  `Fuel.bank` branch (FEED_ENGINE), `RunLifecycle.chooseOffer`
  (DESCEND, and GREATER_DOOR when `step >= 2`), `RunLifecycle.completeRun`
  (COMPLETE_RUN), `VisitService.recordVisit` (VISIT_FRIEND, covering all
  three of `visit`'s success paths), `GambleStation.onUse`/
  `RerollStation.onUse` success paths (GAMBLE, REROLL), and
  `CubeStation.extract` (EXTRACT_POWER). TAME_WOLF has no Fabric event to
  hook (fabric-api ships none for `TamableAnimal#tame`, and the
  one-mixin budget is already spent on `CustomClickMixin`) so it is a
  reconciliation scan in `Instances.onTick`'s per-member loop instead: any
  watch interval a member's active task is TAME_WOLF and a wolf they own
  is within 8 blocks, `progress` fires. `progress` is idempotent past a
  task's target, so scanning every interval rather than only on a real
  taming edge costs nothing once the task is done.
- Door screen: `idleContent`/`previewContent`/`runContent` all append
  the active task's line (e.g. "Feed the Engine 2/3") through a shared
  private `appendTaskLine` helper, which resolves the owner's online
  `ServerPlayer` and refreshes the tracker screen in the same call.
  `runContent` gained a `ServerLevel level` parameter (its three call
  sites already had one in scope) to resolve that player.
- Login: a new `ServerPlayConnectionEvents.JOIN` handler (dimension-
  agnostic, unlike the existing recovery handler) refreshes the tracker
  screen and chats the active task.
- GAMBLE and REROLL originally progressed on interacting with their
  station (opening the picker) rather than on a confirmed spend, so
  "Spend Emeralds at Kadala x16" could complete on 16 station
  interactions with nothing actually spent. Fixed in M38 (PD-25): both
  now progress at the point the item is actually debited
  (`GambleStation.handleTrade`, `RerollStation.handleReroll`), matching
  `CubeStation.extract`'s already-correct EXTRACT_POWER hook.

**Headless-verified:** `./gradlew build` green, full existing suite
passing. New `TaskTrackerTest` (`taskTrackerTest` Gradle task, wired
into `tasks.test`), exercising `TaskTracker`'s pure `DungeonLog`+`UUID`
overloads the same way `DungeonLogTest` exercises `DungeonLog` itself:
sequencing through the ungated tasks, the level-gate grandfather clause
(including that TAME_WOLF's `minLevel == 0` is never grandfathered),
an inactive-task hook being a no-op, progress capping at `targetCount`
without overshooting, and the task-progress sidecar's codec round trip
(including a pre-M33 save with no `task_progress` field defaulting
every task to 0).

**Live-only, not yet verified:** every task surfacing and advancing in
order on the door screen and the tracker screen as a fresh
player actually plays through them; the wolf-taming reconciliation scan
actually catching a taming in the dungeon dimension; task progress and
the grandfather clause surviving a server restart. Not recorded in
LIVE_TEST_PASS.md yet -- add a new numbered section there once verified.

## M34: Weekly bounties for party leaders

**Goal:** three weekly bounties per dungeon host (instance owner), seeded
from the owner UUID and the ISO week key. Party members contribute
progress; all online members get rewards on completion. Inspired by the
archived dailyquests mod's turn-in pattern, adapted to dungeon activities
and party play.

- `BountyTracker` (new): the `Bounty` enum (id, label, targetCount) with
  seven bounty types: Clear the Halls (20 spawners), Echo Harvester (9
  shards banked), Speedrunner (3 timed runs), High Roller (32 emeralds
  gambled), Spelunker (2 Greater-door runs), Pack Hunter (3 multi-member
  runs), Keystone Climber (3 keystone levels gained). `weekKey()` returns
  the ISO week as `"yyyy-Www"` using `WeekFields.ISO` over UTC real time,
  resetting every Monday. `bountiesFor(owner, weekKey)` picks three
  distinct bounties via a seeded shuffle of the seven, the seed derived
  from `owner.hashCode() ^ weekKey.hashCode()` through the same
  SplitMix64 finaliser `AffixMath.seed` uses, so the same owner in the
  same week always gets the same three and a different owner or week
  gets a different pick.
- `BountyState` record (weekKey, bountyId, progress, completed) with its
  own codec, stored as a `Map<UUID, List<BountyState>>` sidecar on
  `DungeonLog` (codec key `bounties`, optional/empty-default), mirroring
  the M33 task-progress sidecar: `Entry`'s codec is already split across
  two 16-field groups, and bounty states are read and written on every
  mechanic hook, so keeping them out of that record avoids a third split
  for a value that has nothing in common with a player's campaign
  history. A `dungeon_log.dat` written before M34 loads unchanged.
- `currentBounties(DungeonLog, owner)` materialises fresh zero-progress
  states when the sidecar is empty or stale: a state whose `weekKey`
  does not match the current week is replaced with a fresh one for the
  same bounty id, so a week rollover resets progress without losing the
  pick. The materialised states are persisted back so the sidecar
  carries this week's key rather than last week's stale one.
- `progress(server, owner, bountyId, amount)` advances the owner's
  bounty, capping at `targetCount`. On the call that reaches the target,
  marks the bounty completed and delivers the reward (2 echo shards + 4
  emeralds via `Payout.deliver`) to every online member of the owner's
  party (`InstanceRegistry.byMember` for the roster), with the owner
  getting one bonus shard. Offline members miss out, by design: the
  bounty is a party activity, and the reward is for showing up. A green
  "Bounty complete: <label>!" broadcast goes to every online member.
- Tracker screen: the same physical screen from M33, now also showing
  the weekly bounty lines below the task line once the tutorial tasks
  are done. Replaces the originally planned bounty scoreboard sidebar:
  the bounty lines show label, progress and target on the room wall,
  not a global sidebar. `DungeonScreen.refreshTracker` runs whenever
  the door screen recomputes its bounty lines.
- Mechanic hooks (all progress the instance owner's bounty, found via
  `InstanceRegistry.byMember`):
  `RunLifecycle.completeRun` (CLEAR_HALLS by spawners cleared,
  SPEEDRUNNER if timed and not late, SPELUNKER if `chosenStep >= 2`,
  PACK_HUNTER if `members.size() >= 2`), `RitualListener` after
  `Fuel.bank` (ECHO_HARVESTER), `GambleStation.handleGamble` after
  emeralds are spent (HIGH_ROLLER by the emerald cost), and
  `Keystones.grantOffer` (KEYSTONE_CLIMBER by the level delta, computed
  as `offer.level() - previousLevel` read before `setKeystone` writes).
- Door screen: `appendTaskLine` (M33's shared helper) now also appends
  the bounty lines below the task line, one per weekly bounty, and
  syncs the bounty tracker screen in the same call. The bounty lines show
  label, progress and target (e.g. "Clear the Halls 12/20"), with
  " (done)" appended on a completed bounty.

**Headless-verified:** `./gradlew build` green, full existing suite
passing. New `BountyTrackerTest` (`bountyTrackerTest` Gradle task, wired
into `tasks.test`): week key format, seeded pick stability and
no-dupes, different owner/week picks, fresh materialisation, stale-week
reset, sidecar codec round trip, and legacy save defaults.

**Live-only, not yet verified:** an owner seeing their three weekly
bounties on the door screen and the tracker screen; a party member's
completion counting toward the owner's bounty; a bounty completing and
all online members receiving the reward; the week rolling over and
fresh bounties appearing. Not recorded in LIVE_TEST_PASS.md yet -- add
a new numbered section there once verified.

## M35: Anomaly rooms

**Goal:** rarely, a themed run contains one room that does not belong
to its theme: a "wrong room" from a dedicated anomaly room set, loaded
separately from the themed room manifest. The room sits on the
critical path, so the player is guaranteed to walk through it; its
palette and geometry read as foreign to the run around it. Tied
tonally to Entry 2 (The Wrong Rooms) without any in-game text naming
it.

- `RoomManifest.currentAnomaly()`: a second manifest instance loading
  `data/<namespace>/anomaly_room/*.json`, same loader shape and
  validation as the themed `dungeon_room` manifest, separate index.
  Shipped with three anomaly room templates (foreign palette, slightly
  off geometry), stamped in a shell palette foreign to every run theme.
- `RoomSelector.rollAnomaly`, called from `resolveDetailed` after the
  plan resolves normally: rolls `anomalyRoomChance` (config, default
  0.08, same pattern as `pocket2DoorChance`) off the plan seed, gated
  on `AdventureGraphs.current().graph().node(theme) != null`, the same
  gate the Pocket2 door uses. On a successful roll, picks one
  non-entrance, non-terminal critical-path cell and swaps its room for
  an anomaly room satisfying the same mask and role. If none matches,
  the run stays normal; the anomaly is a bonus, never a requirement.
  One per run, never more.
- `DungeonPlan.anomalyCell` carries the swapped cell so
  `LayoutStamper` and `RoomContent` can skip the run theme's
  processors, loot suffix and themed spawners on that one cell.
- Anomaly rooms carry their own loose chests and 0-1 spawners, no
  theme suffix, from the `pocketdungeons:chests/anomaly` loot table
  (M36/PD-48 later removed an unconditional echo-shard pool from that
  table; it was granting fuel currency outside the "door 1 is the only
  source" invariant). No keystone, no completion pad, no lodestone: a
  pass-through cell on the critical path, not a destination.
- `RoomSelector.rollAnomaly` passes its own fresh, per-cell-cap map
  rather than the main selection loop's `used` map, so the anomaly
  swap is not counted against `maxPerDungeon` for either the room it
  replaces or the anomaly room itself.

**Headless-verified:** `./gradlew build` green, full existing suite
passing, including the plan-resolution paths this milestone extends
(`PlanSelectorTest`, `PipelineProofTest`).

**Live-only, not yet verified:** a themed run occasionally containing
one visibly wrong room on the critical path; the rest of the run
completing normally with one in it; the anomaly's own loot table
delivering echo shards, shell unlock tokens and rare materials without
themed gear. Not recorded in LIVE_TEST_PASS.md yet; add a new numbered
section there once verified.

## M36: Critical bug fixes from the audit

**Goal:** close every finding from the 2026-08-31 six-pass static audit
that crashes a player, destroys a player's room, or leaks a resource
without bound. Seven bugs (`docs/reference/BUGS.md` PD-9, PD-10, PD-11,
PD-12, PD-13, PD-14, PD-48).

- PD-9: `DungeonCommands.keyInfo` tested `Keystone.findHeld(player).isEmpty()`
  on a method that returns `null`, not an empty stack, for a player
  carrying no keystone. Every other call site tested `== null`; this one
  now does too.
- PD-10: `InstanceTeardown.retireOrPurge` re-entered on an already-lingering
  record (the path `RunLifecycle.enter()`'s lingering-quarry check takes
  when an owner starts a new run) fell past the purge branch and just
  re-marked the record lingering, never freeing the slot. Added an early
  branch: a record that is already lingering purges on the next call
  instead of retiring again.
- PD-11: a stamp failure behind the lobby queued a clear whose origin
  resolved to the player's own persistent room cell (plan cell (0,0)
  always maps back to `record.roomCellOrigin`), and left the record in
  `InstanceRegistry.bySlot` while the async clear freed `usedSlots`,
  opening a slot-collision window. Replaced the `InstanceTeardown.teardown`
  call with a synchronous clear of every attempted cell except the room,
  mirroring `resetForNextDungeon`'s existing pattern, and released the
  attempt's force-load tickets directly. The registry is never touched;
  the room and its slot survive a failed regeneration exactly as before.
- PD-12: the `DISCONNECT` handler ran its entire body, including writes to
  `InstanceRegistry.byMember`, `pendingReturns`, and reachable calls into
  `InstanceTeardown.purge`, on Netty's IO thread for an abrupt disconnect,
  racing the server thread's own reads and writes of the same state.
  Extracted the body into `Instances.handleDisconnect` and wrapped the
  whole thing in `server.execute` (a no-op wrap on the rare path where it
  already fires on the server thread).
- PD-13: force-load tickets from one dungeon behind a lobby were never
  released before the next door choice force-loaded its own set, since
  `resetForNextDungeon` clears blocks but never touched tickets.
  `generateBehindLobby` now releases the previous `record.layout`'s
  chunks before force-loading the new plan's.
- PD-14: a crash or kill skipped `SERVER_STOPPING`'s teardown-and-drain
  pass entirely, leaving stamped geometry and force-load tickets behind
  with no in-memory record to reconcile them against on restart
  (`InstanceRecord` is deliberately not persisted). Added
  `Instances.reconcileAfterUncleanShutdown`, run once on `SERVER_STARTED`:
  if the dungeon dimension has any still-forced chunk (a clean shutdown
  always leaves none, since `SERVER_STOPPING` releases every ticket it
  set), maps each one back to its slot-grid cell via the same arithmetic
  `InstanceRegistry.originForSlot` uses in reverse, and tears that slot
  down through the same budgeted-clear path `SERVER_STOPPING`'s own
  orphan branch already uses for a record-less slot. A no-op on every
  clean-shutdown restart, which is the common case.
- PD-48: `Fuel.isFuel`/`count`/`spend` matched on item type alone, so a
  kamutotems Boss Stone (an echo shard re-skinned via `set_custom_data`
  and `set_name` in the ominous chest/vault tables) counted as spendable
  fuel. `Fuel.grant`, the sole mint point, now stamps every fuel stack
  with a `custom_data.pocketdungeons.fuel` marker (the same convention
  `CubeStation`'s tier tag uses), and every check requires it.
  `chests/anomaly.json` and `chests/pocket2.json` also granted plain echo
  shards directly, falsifying the class's own "door 1 is the only source"
  invariant; both pools are removed. The dead, unread `ritualKeyItem`/
  `ritualKeyCount` config keys (same default item, same confusion risk)
  are removed from `config/pocketdungeons.default.json`.

**Headless-verified:** `compileJava` and the full existing test suite
pass. No new test coverage added in this milestone (see M44).

**Live-only, not yet verified:** PD-11's room-preservation on a forced
stamp failure, PD-12's thread-safety under a real abrupt disconnect,
PD-13's force-load ticket count staying bounded across several
in-session door choices, PD-14's reconciliation pass after an actual
unclean shutdown, and PD-48's marker check against a live kamutotems
Boss Stone. Not recorded in LIVE_TEST_PASS.md yet; add a new numbered
section there once verified.

## M37: High-severity bug fixes from the audit

**Goal:** close the audit's economy and progression correctness bugs
plus the generation-pipeline reproducibility bugs. Eight items
(`docs/reference/BUGS.md` PD-15 through PD-22).

- PD-15: the M34 bounty block sat outside `completeRun`'s `firstCompletion`
  guard, so `CLEAR_HALLS`, `SPELUNKER` and `PACK_HUNTER` advanced once per
  party member instead of once per run, and it read `record.rewardChests`
  before `completeDungeon` (which sets it) ever ran, so `SPEEDRUNNER`
  never advanced at all. Moved the whole block inside `firstCompletion`,
  after `completeDungeon`.
- PD-16: `/dungeon admin experiment`'s loot-level override skipped the
  `[1, keystoneMaxLevel]` clamp every other offer goes through, so an
  operator's typo on door 3 could permanently downgrade whoever completed
  it. Added a range check in the command executor, and `DungeonLog.setKeystone`
  now clamps positive levels itself rather than only flooring at zero
  (zero still clears the keystone, unchanged).
- PD-17: the reroll station's replacement pool was filtered on
  `isSupportedItem` alone, so a curse or an enchantment exclusive with
  one already on the item could come up as a "reroll," breaking the
  station's own "never strictly worse" guarantee. The pool now excludes
  `EnchantmentTags.CURSE` and anything sharing an exclusive set with a
  surviving enchantment. Treasure-only enchantments stay reachable by
  design; that is a windfall, not the failure this closes.
- PD-18: the room directory could admit a visitor into an owner's live
  keystone run, since `VisitService.visit` never consulted its own
  `statusOf`, which already knew the difference. `visit` now refuses with
  a message when `statusOf` reports "run in progress."
- PD-19: `RoomManifest.matchesTheme` was confirmed to fail safe (matches
  everything) when no room or theme declares the relevant field; the
  filter is inert until M42.2 pairs content, not broken.
- PD-20: the voided-cell pass iterated `plan.cells()` (a `Set.copyOf`
  with per-JVM-instance salted order) directly, unlike every other
  consumer in the generation pipeline, so the same seed voided a
  different cell set on every restart. Now sorted the same way
  `LayoutStamper.stampOrder` already sorts.
- PD-21: routed dialog clicks trusted a `ServerPlayer` captured before a
  `server.execute` defer, with no check that the player was still
  connected by the time the deferred call ran. `DialogRouter.handle` now
  refuses at the top if `player.hasDisconnected()`.
- PD-22: `AdventureGraphs`' node-validation pass tested `nodes.containsKey`
  against the live map while removing entries from it in the same loop,
  so a cascading dangling edge (node A depends on node B, which is
  itself invalid) was only caught if `HashMap` happened to iterate B
  before A. Extracted the fixpoint loop into
  `AdventureGraphs.removeUnresolvedTransitions`, testable independent of
  the resource-manager plumbing around it, and added
  `AdventureGraphTest.testUnresolvedTransitionsCascade` asserting the
  cascade is caught regardless of iteration order.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full
test suite (including the new cascade test) pass.

**Live-only, not yet verified:** PD-16's clamp against a live experiment
command, PD-17's exclusion set against real gear enchanted in a live
world, PD-18's refusal message against a real visit attempt mid-run,
PD-21's guard against an actual disconnect racing a dialog click. Not
recorded in LIVE_TEST_PASS.md yet; add a new numbered section there once
verified.

## M38: Medium-severity bug fixes from the audit

**Goal:** close the audit's gate-bypass, task-tracking, and
operator-tooling-correctness bugs. Thirteen items
(`docs/reference/BUGS.md` PD-23 through PD-35); PD-31 and PD-32 are
content/design decisions deferred to M42, not fixed here.

- PD-23: `GambleStation.onUse` and `CubeStation.onUse` never checked
  their own unlock level; only `RerollStation` did. Both now check the
  same way, refusing below `gambleUnlockLevel`/`cubeUnlockLevel`.
- PD-24: the dialog path into reroll and imbue never re-checked the
  unlock level `onUse` does, so a stale dialog with no station present
  could still act. `RerollStation.handleReroll` and
  `CubeStation.handleImbue` now re-check.
- PD-25: `RerollStation` and `GambleStation` fired their task progress on
  opening the picker, before anything was spent, so 16 right-clicks with
  an empty inventory could complete "Spend Emeralds at Kadala." Moved
  both to the actual spend point.
- PD-26: `Instances.rescue` hand-rolled a fifth partial copy of "detach a
  member," skipping the T2.6 leadership rule. Now routes through
  `RunLifecycle.dropMember`, falling back to the member's own
  `ReturnPoint` (or world spawn) when dropMember's leadership branch ends
  the whole run instead of leaving the player standing in a room that is
  no longer theirs.
- PD-27/PD-28: `ConnectorStamper.applyIronDoor` now places a lever on the
  door frame (nothing else in the mod supplied a redstone source, and
  this connector can land on the critical path), alternates
  `DoorHingeSide` across its two columns instead of defaulting both to
  `LEFT`, and uses the inward `CellGeometry.opposite` facing convention
  every other door in the pipeline already uses.
- PD-29/PD-30: `/dungeon admin manifest reload` reloaded rooms only,
  while three other commands pointed operators here as the fix for stale
  themes, adventure nodes, diaries, or anomaly rooms. Now reloads and
  reports all five. `Diaries.load` is also registered against the
  datapack reload listener alongside `ThemeManifest`/`AdventureGraphs`
  (which, on inspection, already reloaded together), so an edited diary
  no longer needs a restart.
- PD-33: `CubeStation.extract` wrote the extracted-power state before
  shrinking the input item, not after, so a crash between the two is now
  a harmless duplicate grant instead of a destroyed item.
- PD-34: `InstanceTeardown.purge` nulls `record.roomCellOrigin` right
  after its synchronous room save, so `Instances.eject`'s deferred save
  (triggered for each remaining member in purge's own loop) sees the
  null guard both `saveRoomIfOwner` and `saveRoomIfOwnerSync` already
  had and no-ops, instead of racing the `PendingClear` queued moments
  later.
- PD-35: the lobby directory caps at 8 rows (matching this suite's own
  convention) with an overflow line, instead of shipping an unbounded
  `MultiActionDialog`.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full
test suite pass, including `ConnectorTest` and `LobbyBrowserTest`.

**Live-only, not yet verified:** PD-23/PD-24's refusal messages against
a real low-level player, PD-27's lever against a live iron-door
connector, PD-29's five-manifest reload against real edited datapack
content, PD-30's diary reload via `/reload`, PD-34's room-save race
under a real purge with online members. Not recorded in
LIVE_TEST_PASS.md yet; add a new numbered section there once verified.

## M39: Low-severity bug fixes and config validation gaps from the audit

**Goal:** close the remaining small correctness bugs and the three
config cross-field validation gaps. Twelve items
(`docs/reference/BUGS.md` PD-36 through PD-47), the last of the four
audit bug-fix milestones. All 40 bugs from the 2026-08-31 audit are now
closed (three deferred to M42 as content or design decisions, not
bugs).

- PD-36: `/dungeon admin experiment` echoed the operator's raw affix
  string instead of what `AffixMath.parse` actually kept, so a typo
  confirmed an affix that was never applied. Now echoes
  `AffixMath.join` of the parsed set.
- PD-37: room names reached the lobby directory with legacy formatting
  codes intact. Both the command and the routed dialog path now strip
  the section sign before storing.
- PD-38: `RunLifecycle.exit` and five `PartyService` methods (`party`,
  `stageKick`, `confirmKick`, `invite`, `join`) always returned success
  from their command executors even when they refused internally. All
  six now return `boolean`, and every executor reflects it.
- PD-39: the `stamptest` dev command force-loaded four chunks with no
  release, leaking a ticket per invocation. Now released once the
  report is sent; the stamped blocks stay for manual inspection exactly
  as before.
- PD-40: `GambleStation.draw` silently discarded every rolled stack past
  the first. Kept single-item by design (every shipped table rolls
  exactly one, matching the single-pull trade metaphor), now with a log
  warning if a table is ever authored with more than one roll.
- PD-41: `Keystone.reconcile` always minted a count-1 replacement,
  destructive only if `keystoneItem` were ever reconfigured to a
  stackable item. Now preserves the original stack's count, capped at
  the replacement's max stack size.
- PD-42: `PayoutMath.chestCount` overflowed `int` past roughly 21.4
  million seconds, a value `KeystoneMath.timerSeconds` explicitly
  allows. Widened to `long` before multiplying; `PayoutMathTest` gained
  a large-value regression case.
- PD-43: `TrimListener` and `PowerListener` each kept a static per-UUID
  map with nothing removing a stale entry. Both now clear it on
  disconnect.
- PD-44: `pendingReturns` never expired. Entries now carry a game-time
  expiry, swept in the existing tick watcher pass alongside
  `reconcileKeystones` (both need to run whether or not any instance is
  live).
- PD-45/PD-46/PD-47: three config cross-field gaps.
  `inviteTtlSeconds` now requires at least 1 (0 silently disabled party
  kicks and invites). `pathLengthMax` is clamped to
  `maxGridSpan` squared, the hard upper bound the grid can never exceed
  regardless of shape, with a log warning; unlike PD-42/43/44 this is a
  worst-case safety net, not a tight fit-guarantee, since a real
  generated path can still fail well below it depending on branching.
  `keystoneMaxLevel` is cross-checked (warn, not clamp, since a low cap
  can be a legitimate server choice) against the four level gates it
  can silently make unreachable.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full
test suite pass, including `PayoutMathTest`'s new overflow case.

**Live-only, not yet verified:** PD-38's `execute if` behavior against
a real refusal, PD-43's map staying bounded across many connect and
disconnect cycles, PD-44's expiry sweep against a real 24-hour wait,
PD-46/PD-47's warnings against a real misconfigured
`pocketdungeons.json`. Not recorded in LIVE_TEST_PASS.md yet; add a new
numbered section there once verified.

## M40: Dead code and stale-shipped-defaults cleanup

**Goal:** remove verified-dead code, the reward hall and selector room
(superseded, per the mod owner: folded into the final room and the
player's own room), and the `discoverable` flag (cut, no theme-listing
surface exists to consume it). Independent of M36 through M39.

- 40.1: removed thirteen confirmed-dead members after a fresh
  zero-caller grep at implementation time: `AdventureGraph.nodeForReward`,
  `Diaries.byNumber` and its backing index (`Diaries.rejections()` was
  dropped from the list; M38's PD-29/PD-30 fix wired it into the manifest
  reload command, so it is live now), `InstanceRecord.rewardRoomStamped`,
  `LayoutPlanner.planOptional`, `RoomStore.has`, the 1-arg
  `RunLifecycle.enter(ServerPlayer)` overload, `RoomBuilder.buildCell`,
  the 2-arg `BedrockEnvelope.apply` overload, `Fuel.count` (and demoted
  `Fuel.spend` to `private`), `StaticLayout`'s three unused direction
  constants, `TrialContent.REWARD_CHEST_SPOTS` (its javadoc, which
  actually documented `placeCompletionChests`, moved to that method), and
  `RoomSelector.MIN_ROOMS`'s unreachable branch. `Instances.dungeonRecordAt`
  stayed: `DungeonShellProtectionTest` genuinely exercises it, so instead
  of deleting it the redundant private `dungeonRecordAndCellAt` wrapper
  was collapsed into a direct `dungeonCellLookupAt` call.
- 40.2: moved `LayoutGraphGenerator.main` and `verifyLivePlayProfile`
  (184 lines, the only `System.out` calls in the production source set)
  to a new `LayoutGraphGeneratorHarness` in the test source set,
  unchanged. `Counter`, which the harness does not use, stayed in
  production; it backs the real critical-path recursion.
  `build.gradle.kts`'s `layoutGraphTest` task now points at
  `sourceSets["test"]`.
- 40.3: removed the `discoverable` field end to end:
  `DungeonThemeMeta`, its now-dead `booleanOr` helper,
  `ThemeManifest.discoverableIds()`, `DungeonThemeMetaTest`'s two
  assertions, and the key from both `drowned_vault.json` (which set it
  `false`) and `infestation.json` (which set it `true`, a no-op value
  but still present).
- 40.4: removed the reward hall and selector room: their two specs in
  `RoomTemplateGenerator.specs()`, `TemplateStamper.REWARD_HALL`/
  `SELECTOR_ROOM`, and the two orphaned `.nbt` structure files under
  `structure/rooms/`. `placeSelectorDoors`/`placeWallLodestone`, which
  the selector room's decor lambda called, stayed: both are the lobby
  stamping path's own real machinery, called from `Instances`,
  `RoomBuilder`, `RunLifecycle` and `VisitService` independently of the
  removed spec.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full
test suite pass, including the relocated `layoutGraphTest` task run
directly. `grep`-confirmed zero remaining references to `discoverable`,
`reward_hall`/`REWARD_HALL`, and `selector_room`/`SELECTOR_ROOM` across
`src/main/java` and `src/main/resources`, and zero `System.out` calls
left in the production source set.

**Live-only, not yet verified:** none. This milestone is pure removal
with no behavior change; the existing headless suite is the whole
verification surface.

## M41: Documentation drift correction

**Goal:** make every top-level and reference doc agree with what is
actually built. Pure documentation; no source changes.

- 41.1: the scoreboard-vs-tracker-screen drift the audit flagged turned
  out smaller than claimed on re-reading. `COMPLETED-MILESTONES.md`'s
  M33/M34 sections, `ROADMAP.md`'s, and `ROOM_UX_PLAN.md`'s already
  correctly describe the tracker screen with "replaces the originally
  planned scoreboard sidebar" as accurate history. Fixed two real
  staleness spots found while checking: M33's note that GAMBLE/REROLL
  progressed on station interaction rather than a spend (true when
  written, fixed by M38's PD-25) and one leftover "sidebar" word in
  M34's live-only list.
- 41.2: `README.md`'s milestone range and active-handoff pointer both
  named specific values that had already drifted once (M0-M34, then
  M22). Replaced both with self-describing pointers (the highest
  `## M{n}` heading in `COMPLETED-MILESTONES.md`; any handoff file
  without the `-completed` suffix) so they cannot go stale the same
  way again. Also corrected the `INTEGRATION.md` one-line description
  to name the five surfaces it now actually documents.
- 41.3: `docs/INTEGRATION.md` corrected to name the real
  `pocketdungeons.Affix` (eight constants) instead of a `Keystone.Affix`
  that never existed with that shape; removed the `ritualKeyItem`
  caveat entirely now that M36 deleted the key; added the `theme` field
  to the `dungeon_room` schema table; and documented the four
  namespace-scanned surfaces the "five extensible surfaces" heading
  never counted (`dungeon_theme`, `dungeon_adventure`, `anomaly_room`,
  `diary`).
- 41.4: renamed `M29-handoff.md`, `M31-handoff.md`, `M35-handoff.md` to
  `-completed.md` and wrote their `COMPLETED-MILESTONES.md` sections
  from the actual shipped code, not the original handoff plan. M31 in
  particular shipped narrower than planned: the handoff called for the
  whole dungeon cell (interior included) to be unbreakable during a
  run; what shipped mirrors the player-room model exactly, protecting
  only the shell and leaving the interior minable throughout. Also
  fixed `COMPLETED-MILESTONES.md`'s M30/M32 ordering (M32 was appearing
  before M30) and a stale `LayoutGraphGenerator.main` reference in
  M29's entry that M40 orphaned.
- 41.5: `docs/DIALOGS_SPEC.md`'s status header claimed section 7 was
  spec-only, blocked on a `listed` flag and a shared visit method that
  do not exist; both shipped
  (`DungeonLog.Entry.publicListed`, `VisitService.visit`, verified live
  in the tree). Conversely section 1 did not ship in the form the spec
  describes: `sendDoorOffer` no longer exists anywhere, replaced by
  M19's physical `DungeonScreen` display. Rewrote the header to match
  both facts and fixed the broken relative link to `DIALOGS.md`
  (actually at `docs/reference/DIALOGS.md`, one level down from
  `docs/DIALOGS_SPEC.md`).
- 41.6: `plans/STATION_PICKER_PLAN.md` section 7 claimed the in-dungeon
  visitor menu grows to 4 options with a "Stations" entry visitors can
  use; `LodestoneMenuTest.java` confirms the shipped visitor menu is 3
  options with no Stations entry at all, matching "Resolved decisions"'
  owner-only call. Corrected section 7 to match what shipped. Also
  renamed "Cube" to "Herobrine Cube" throughout, matching the code and
  player-facing text.

**Headless-verified:** no source files changed in this milestone (three
files showing as modified in `git status` predate this session and are
unrelated); `compileJava` still green.

**Live-only, not yet verified:** not applicable; this is a
documentation-only milestone.

## M42: Half-built feature content and design work

**Goal:** implement the six half-built findings the mod owner decided to
ship, and record the one they decided to leave. All decisions were made
2026-08-31, ahead of this milestone.

- 42.2 (PD-19): paired specialized rooms to themes via `theme` (on
  `dungeon_room`) and `room_theme` (on `dungeon_theme`), previously
  parsed but never populated by any shipped file, so the filter always
  matched everything. `crypt_corner` is now exclusive to `deepslate`
  and `infestation` (both reuse `theme_deepslate`'s processors and read
  as crypt-like underground). `treasure_alcove`, `grove`, and
  `mossy_tee` are exclusive to `drowned_vault` and `prismarine` (both
  aquatic; algae/moss growth reads as underwater). The other eleven
  rooms (the generic halls, `entrance_hall`, `exit_hall`,
  `encounter_zombie`, `loot_vault`, `pillar_cross`, `spawner_den`) keep
  `theme` unset, matching every theme, per `RoomManifest.matchesTheme`'s
  existing empty-list fallback; none had a strong enough biome identity
  to justify restricting them, and every theme still has full role and
  mask coverage from the unset pool alone.
- 42.3 (PD-31): authored `dungeon_adventure/infestation.json` as a
  `descent` node (matching `blackstone`'s shape: one weighted path to
  the boss, one each back to the two entries), and added `infestation`
  as a third option alongside `blackstone` in both `deepslate.json`'s
  and `prismarine.json`'s own `next` lists. The theme's six authored
  tier 1-3 trial-spawner configs are reachable as a normal door offer
  now, not only through the admin command.
- 42.4 (PD-32): rather than restructure `TrialContent.resolveLootTable`
  in code, added six small loot tables
  (`chests`/`vaults` × tier 1-3 `_ominous_drowned`) that reference the
  existing base ominous table via a `minecraft:loot_table` pool entry
  (verified against the jar: `NestedLootTable`, registered id
  `loot_table`) and layer a small drowned-flavored bonus pool
  (nautilus shells, tridents, a heart of the sea at tier 3) on top.
  `resolveLootTable`'s existing suffix-fallback already finds these
  automatically, the same mechanism that already served the non-ominous
  `tier_N_drowned` tables; no Java changed. Scales to future themed
  suffixes as a handful of small wrapper files per theme rather than
  full duplicates of the 500+ line base tables.
- 42.5: `CubeStation.sortedUnlocked`'s javadoc promised a filter
  excluding powers already active on worn gear; the body only sorted.
  Implemented the filter for real: `PowerListener.activePowersOf`
  (extracted from `reconcile`'s existing slot-scan, so both share one
  computation) is now threaded through `CubeStation.showPicker` into
  `DialogScreens.imbuePicker`, and `sortedUnlocked` takes the active set
  as a second parameter and excludes it. The empty-picker message now
  distinguishes "nothing extracted yet" from "everything you have is
  already active." New `CubeStationTest`
  (`cubeStationTest` Gradle task, wired into `tasks.test`).
- 42.6: `AdventureGraph.pick` could offer the same theme on two or three
  doors, since it expanded transitions by weight and took indices 0/1/2
  from a shuffled list with no dedup. Now dedupes by theme (keeping
  first-occurrence order in the already-weighted, already-shuffled
  list, which preserves the weighting bias) before taking the top
  three, falling back to repeating only when a node has fewer than
  three distinct transitions. `AdventureGraphTest` gained two cases: a
  4-transition node swept across 200 owners and 5 depths each shows no
  duplicate, and a 1-transition node still repeats across all three
  doors. Fixing this reduced the output space for the shared
  `KeystoneOfferTest` graph enough that its two fixed test UUIDs
  started colliding at 2 distinct transitions; added a third
  transition to keep that check meaningful.
- 42.7: reviewed and left as-is. `/dungeon party kick <target>` cannot
  resolve an offline player, and the roster screen skips them; `kick
  all` stays the only escape. No code change. Recorded here so a
  future audit does not re-flag it as an open question.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full
test suite pass, including `./gradlew build`'s jar assembly. All new
and edited JSON validated for syntax. `KeystoneOfferTest`'s regression
from 42.6 was caught and fixed in this same milestone, not left for a
later one.

**Live-only, not yet verified:** 42.2's room mix actually reading
differently per theme in a seeded sweep; 42.3's infestation theme
appearing as a real door offer in play; 42.4's themed bonus items
actually appearing in an ominous drowned_vault chest; 42.5's imbue
picker actually hiding an active power in a live inventory. Not
recorded in LIVE_TEST_PASS.md yet; add a new numbered section there
once verified.


## M43: Refactor backlog

**Goal:** land the eight structural findings from the audit follow-up plan
that were safe to do without changing observable behavior. Each subsection
is scoped independently; three were deliberately scoped down from the plan
doc's literal ask after weighing risk against the value actually left to
capture, and one bug (PD-49) was found mid-refactor and fixed as its own
change before the structural work that found it continued.

- 43.1 (`InstanceRecord` per-run state): scoped down from the plan's full
  `InstanceRecord` -> `RunState` migration (hundreds of call sites) to a
  colocated `InstanceRecord.clearPreviousRunState()` method, called from
  `InstanceTeardown`, replacing the inline field-reset block that used to
  live there. The full split's main benefit (making a forgotten reset
  impossible to write) was already captured by finding and fixing PD-49
  (`timedOutPenaltyApplied` was never reset between runs behind the same
  lobby) during this same subsection; the wider migration's remaining
  value did not clear the bar for its own risk.
- 43.2 (offline teardown parity): `InstanceTeardown`'s two offline-player
  branches now route through a new `Instances.detach` primitive instead of
  duplicating `RunLifecycle.dropMember`'s cleanup by hand; `dropMember`
  itself was rewritten to call `detach` too, so there is exactly one place
  a member leaves an instance's bookkeeping now, online or not.
- 43.3 (`extraOccupiedCellOrigin`): reviewed and left as a direct read of
  `record.roomCellOrigin` at its one call site. An accessor was written,
  then reverted: it was pure aliasing with no behavioral or clarity gain
  over the field read it would have wrapped.
- 43.4 (redundant same-position rescans): the plan's literal ask (a
  maintained `Map<ChunkPos, InstanceRecord>` spatial index) was assessed as
  adding real staleness risk to block-protection code for an uncertain
  performance win at this mod's small live-instance count. Fixed the same
  audit finding (up to four scans per block break) more narrowly instead:
  `Instances.roomRecordAt` made package-visible, and `RoomProtection` and
  `RitualListener` each now resolve the record once per call and reuse it,
  rather than rescanning at every step.
- 43.5 (datapack-loader helper): scoped down from a fully generic
  `JsonPackLoader<T>` to a small `JsonPackSupport` class holding just the
  two pieces that were byte-for-byte identical across loaders:
  `baseName(Identifier)` (shared by `AdventureGraphs`, `Diaries`,
  `ThemeManifest`, and `RoomManifest`'s own inline variant, which kept its
  extra empty/underscore-prefix skip as a post-check) and `requiredString`
  (shared by `AdventureGraphs` and `Diaries`, which had it byte-identical;
  `DungeonThemeMeta` and `DungeonRoomMeta` each have a same-named helper
  with different behavior (no trim, no blank check), so those two were
  left alone rather than unified, since that would change what a malformed
  datapack entry does). The rest of each loader's control flow stays where
  it is: parse step, rejection wording, and published object differ enough
  per loader that a fully generic loader would need to abstract those
  differences away rather than remove real duplication.
- 43.6 (`DungeonLog.Entry` withers): added twelve `withX(...)` methods to
  `Entry`, grouped by what a given mutator actually changes together
  (`withRunStats` for the three fields `recordCompletion` bumps at once,
  `withThemeProgress` for the three `recordTheme` advances at once, one
  wither per single field everywhere else). All fourteen mutators in
  `DungeonLog.java` now call a wither instead of restating all sixteen
  other record components by hand.
- 43.7 (shared station shape): new `StationSupport` class holding the three
  pieces that were duplicated across `RerollStation`, `GambleStation`, and
  `CubeStation`: matching the configured station block (`matchesBlock`),
  refusing a click below a station's unlock level with the standard
  message (`levelTooLow`), and reading a string or int marker out of
  `custom_data.pocketdungeons` (`readStringMarker`/`readIntMarker`). A full
  shared `onUse` template was considered and rejected: the gamble
  station's flow is an SGUI merchant callback with no held-item check at
  all, structurally unlike the reroll and cube stations' direct
  block-click dispatch, so forcing all three through one template would
  need to abstract that difference away rather than remove real
  duplication. PD-23 and PD-25, the two steps that had actually gone
  missing on two of the three stations, were already fixed independently
  in M36-M39; this only removes the boilerplate around them.
- 43.8 (geometry and facing helpers, partial): the "wall-to-opposite-facing"
  switch that appeared five times in `RoomTemplateGenerator.java` is now
  one method, `CellGeometry.facingIntoRoom(DoorMask.Direction)`. The
  "world position on a cell wall" arithmetic was left alone beyond that:
  three of its four copies (`CellGeometry.doorSlotPositions`,
  `ConnectorGeometry.wallPos`, `RoomTemplateGenerator.wallRingPos`) already
  operate on the same `DoorMask.Direction` type and are deliberately kept
  as separate pure-math/level-writing twins per `ConnectorGeometry`'s own
  javadoc, while the fourth (`RoomBuilder.doorSlot`) is keyed on vanilla's
  own `Direction` instead, a real type boundary a "dedup" would have to
  convert across in a door-sealing path used on every room reset. Judged
  not worth the risk for a cosmetic move. Separately, `Instances.stampLobby`
  and `VisitService.createVisitInstance`'s verbatim eight-call room-shell
  sequence (including the comment warning the two "must not drift apart")
  is now one shared `Instances.stampRoomShell(level, server, owner,
  origin)`, called from both.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full test
suite pass after every subsection, including a final `./gradlew build`'s
jar assembly. PD-49 was filed, fixed, and verified before the 43.1 refactor
that found it continued, per the plan doc's own rule for this situation.

**Live-only, not yet verified:** none of this milestone's changes are
behavior changes (PD-49 aside, which is a `BUGS.md` entry in its own
right), so nothing here needs a live playtest beyond what PD-49 already
calls for.


## M44: Test coverage for world-mutating and economy classes

**Goal:** add coverage for the highest-risk classes the audit found
completely untested, prioritizing persistence and currency/economy classes.

**Scope note:** this suite is pure-JDK headless (`main(String[])` throwing
`AssertionError`, no Fabric test framework, no mocking library, no running
server). Every class in this milestone's scope mixes some pure logic with
real `ServerLevel`/`ServerPlayer`/`Inventory` mutation; only the pure half
of each is reachable here. Constructing a bare `ItemStack` was tried and
found unreachable too: in this Minecraft version, `ItemStack`'s constructor
requires its item holder's default components to already be bound, and
that bind is driven by `DataComponentInitializers.build(HolderLookup.Provider)`,
which needs a full registry-access build this suite has never assembled
(`Bootstrap.bootStrap()` alone leaves it unbound: confirmed by bytecode
inspection of `Holder.Reference.components()`/`bindComponents`, not by
guessing). Building that infrastructure was judged out of this milestone's
risk budget, the same class of call as 43.1/43.4's scope-downs.

- 44.1 (`RoomStore`): `liveFile`/`backupFile` changed to take a `Path`
  directly instead of a `MinecraftServer` argument, and `save`/`load`/
  `backupTime`/`restoreFromBackup`/`reset` each gained a `Path`-taking
  overload alongside the existing `MinecraftServer`-taking one (which now
  just resolves the directory and delegates). None of this changes what any
  existing caller does; it only makes the file's actual claimed risk
  (backup-then-atomic-write, corrupted-file handling) reachable from a
  plain temp directory with no server. New `RoomStoreTest`: save-then-load
  round trip, a second save backing up the first, a corrupted live file
  reading as `null` instead of throwing, `restoreFromBackup` actually
  bringing the backed-up tag back live (and itself leaving a fresh
  backup), and `reset` backing up before removing the live file. Also
  resolved the plan doc's open question: `restoreFromBackup` is not dead
  code; `DungeonCommands`'s `admin baserestore confirm` branch calls it.
  `capture`/`place` (the `ServerLevel`-writing half) are not covered.
- 44.2 (`Fuel`): investigated; `isFuel`/`isMarked` are pure and were the
  intended target (PD-48's own fix), but exercising them needs a real
  `ItemStack`, which hit the `ItemStack`-construction wall described above.
  No test added. `bank`/`spendBanked`/`grant` need a `ServerPlayer` and
  were never in reach either way.
- 44.3 (`RerollStation`, `GambleStation`, `CubeStation`): investigated; the
  unlock-level gate and marker-reading paths (`StationSupport`, M43.7) are
  the pieces the plan wanted confirmed, but both take an `ItemStack` or a
  `ServerPlayer` and hit the same wall. `RerollMath`/`GambleMath` (the pure
  math these stations wire into) are already covered by their own existing
  tests. No new test added.
- 44.4 (`Payout`): extracted the `%player%`/`%level%`/`%chests%` template
  fill out of `runPayoutCommand` into a pure `Payout.substitute(String,
  String, int, int)`, called from the same site with no behavior change.
  New `PayoutTest`: all three placeholders, a repeated placeholder, a
  template missing some placeholders, and a template with none at all.
  `deliver` (needs a `ServerPlayer`'s inventory) is not covered.
- 44.5 (`InstanceTeardown`): investigated; the per-tick clear budget and
  the purge/retire branch selection both operate directly on a
  `ServerLevel`, with no pure seam to extract without restructuring the
  class's actual control flow, which is exactly what M43 (the milestone
  right before this one) drew the line against doing without a concrete
  behavior reason. No test added.
- 44.6 (`Pocket2Test.java`): kept the existing file and its name (it
  already covers `InstanceRecord`'s child-instance shape and
  `InstanceRegistry.allocateSlotNear`, both of which are genuinely part of
  Pocket2's own mechanics even though they route through other classes),
  and expanded it with real `Pocket2` coverage: `doorWall`, `returnPos`,
  `returnYaw`, and `isDoorBlock` opened from `private` to package-visible
  for the test, plus `childFor` (already package-visible). All four wall
  directions checked for the position math; `isDoorBlock`'s two-wide,
  two-tall pair checked against one block outside it on each axis.
  `openChild`/`tickChild`/`dieInChild`/`placeDoor` (all `ServerLevel`- or
  `ServerPlayer`-bound) are not covered.

**Headless-verified:** `compileJava`, `compileTestJava`, and the full test
suite pass, including `./gradlew build`'s jar assembly. `RoomStoreTest`
logs one expected `ERROR` line (the corrupted-file case exercising
`RoomStore.load`'s own catch-and-log path) that is not a test failure.

**Live-only, not yet verified:** everything this milestone could not cover
headlessly (44.2, 44.3 in full; 44.1's capture/place; 44.5 in full; 44.6's
`ServerLevel`/`ServerPlayer`-bound methods) has no coverage of any kind
yet, headless or live. A future milestone that wants real coverage of
those would need to build genuine test infrastructure first (a fake or
harnessed `ServerLevel`, and a way to bind item components without a full
server), not just write more tests against what exists today.


## M61: Vertical room span (spec 13)

A room may declare `spanY: 2` to own the 16 x 16 x 9 volume directly beneath
its own cell, private to it, with no doorways and no presence in the layout
graph. The planner stays two dimensional and the door mask stays four bits.

### Naming

The field is `spanY`, paralleling `PlanGeometry.spanX`/`spanZ`. The user
rejected the handoff's `stories` name and asked for something compatible
with a future vertical layout dimension. `spanY` reads as "the room's span
along the y axis of the layout," which is the direction the user described.

### Constants

`RoomGeometry.STORY_HEIGHT = CEILING_Y + 2` is the floor-to-floor pitch.
`RoomGeometry.MAX_SPAN_Y = 2` is the largest value `spanY` may take.
`RoomGeometry.storyOffset(int spanY)` returns `(spanY - 1) * STORY_HEIGHT`,
the number of blocks a multi-story room's capture origin sits below its
cell origin. It lives on `RoomGeometry` (pure constants, no Minecraft
bootstrap) rather than `RoomTemplateGenerator` so tests and geometry helpers
can call it without triggering the template generator's static initializer.

### Schema

`DungeonRoomMeta` gains a `spanY` field (int, default 1). The parser
rejects values outside 1..MAX_SPAN_Y with the room named, following the
same pattern as `access` and `window`. The convenience constructor chain
defaults spanY to 1.

### Template capture

`RoomTemplateGenerator.buildAndQueue` computes `captureOrigin` and `size`
from `spec.spanY`. For spanY=1 the origin is the cell origin and the size
is `TEMPLATE_SIZE` (16 x 7 x 16), byte-identical to before. For spanY=2
the origin moves down by `STORY_HEIGHT` and the height grows by the same
amount. `buildLowerStories` stamps a plain shell for each lower story and
fills the over-ceiling gap (the 2-block space between one story's ceiling
and the story above's floor) with solid wall material. The room's decor
carves shaft holes through this filler.

### Door slot offset (trap 20)

`RoomManifest.canonicalDoorSlots` offsets template-local door y by
`storyOffset(meta.spanY)`. A spanY=2 template's doors sit at y=9..11
(template-local), not y=1..3, because the capture origin moved down. The
manifest validation reads at the offset position. One-spanY rooms have
offset 0 and are unaffected.

### Bedrock envelope

`BedrockEnvelope.apply` takes a per-cell spanY map from `LayoutStamper`.
The sub-floor bedrock drops to under the lowest story. Wall rings extend
from the new sub-floor to the unchanged over-ceiling. The standalone
`applyToCell(reservedSides)` (lobby and relocated rooms) is unchanged
because those are always single-story.

### Bounds and teardown

`PlanGeometry` gains a `storyFloorOffset` field, computed by
`LayoutStamper` from the deepest spanY in the layout. `bounds()` lowers
its floor by this offset. `InstanceRegistry.maximalBounds` lowers by the
max possible offset (layout-less teardown covers any two-story room).
`InstanceTeardown.PendingClear` and `Instances.clearCellSync` clear the
max possible vertical extent. `CellGeometry.insideAnyCell` and
`RoomContent.inCell` extend their y range downward by the same max.
`RoomProtection.isShell` checks every possible story's shell pattern.
`Instances.dungeonCellLookupAt` widens its y guard to cover lower stories.
`TemplateStamper.place` derives the story offset from the template's own
size and places from the lowered origin.

### Return path validator (spec 13.4)

`ReturnPathValidator.validate` runs at stamp time for any room with
spanY > 1. It inspects the stamped blocks structurally (not metadata) for a
climbable route from the lowest story floor to the upper floor. Supported
patterns: ladder column, water source column, soul sand bubble column, and
staircase of solid blocks (flood fill). If no route is found, the stamp is
refused with an error naming the room.

### Pilot room

Slime Pit (`KnowledgeSpecs.slimePit()`) declares `spanY(2)`. The upper
level keeps the slime block floor and the north-wall ledge. The lower
level has a chest at (8, -8, 8). A ladder shaft at (8, z=1) against the
north wall connects the two: the player drops in, loots the chest, and
climbs the ladder back. The `slime_pit.json` metadata adds `"spanY": 2`.

### Verification

- `./gradlew.bat build --offline`: all tests pass.
- `/dungeon admin gentemplates`: 52 templates generated, no errors.
- Server restart: "Loaded 49 dungeon rooms" (0 rejected).
- `/dungeon admin coverage`: all 53 (mask, role) pairs satisfied.
- `/dungeon admin plansurvey 30`: 30 of 30 succeeded.
- Door jigsaw audit: every room has the correct count (multiples of 6 per
  door edge, i.e. 3 per door position).
- Byte-identical check: 48 of 52 templates match the pre-M61 backup. The
  4 diffs are slime_pit (expected, spanY=2) and 3 rooms with pre-existing
  entity UUID/Motion non-determinism (rising_lava, rotation_lock,
  the_altar), not caused by M61.
- Live verification (falling into the pit and climbing the ladder) requires
  a Minecraft client and is not headless-verified.

## M62: Establish executable acceptance

### Harness boundary

M62 proves runner plumbing, not custody or fault coverage. Two dedicated
server routes now exist side by side:

- `runGameTest` (`GameTestServer`): fast, but bakes an empty `LEVEL_STEM`
  registry against the flat world preset, so `pocketdungeons:void` is never
  created there (DISCOVERIES trap 18). `InventorySwapGameTest` and
  `HarnessGameTest` live here.
- `dungeonIntegrationTest` (a real `loom.runs` dedicated-server
  configuration, aliased from a thin `verification`-group task): slower,
  boots an ordinary server against this project's own bundled datapack in
  an isolated `run-dungeonIntegrationTest` directory, and does create the
  real dimension. `DungeonIntegrationEntrypoint` is its only entrypoint,
  registered under the `pocketdungeons-gametest` module's `main` key so it
  never reaches the production jar, and it is a no-op outside its own task
  (gated on the `pocketdungeons.integrationtest` system property, so
  `runServer`/`runGameTest` load it harmlessly).

`DungeonTestFixtures` adds shared plumbing for M63 to build on: bound
`ItemStack` builders, a `corruptPrimaryStore`/`restorePrimaryStore` pair
scoped to `world/data` only, a guaranteed-cleanup mock-player fixture, and
`requireLevel`. Nothing in it exercises a fault yet; M62 does not claim
M44's or M46's missing tests are now covered.

### A deadlock found and fixed during verification

The first `dungeonIntegrationTest` run reported `BUILD SUCCESSFUL` while the
server had actually failed to start at all — `run-dungeonIntegrationTest`'s
`world/session.lock` was held by a stale run, `MinecraftServer` never got
created, `SERVER_STARTED` never fired, and the task exited 0 having checked
nothing. That is the exact false positive M62 exists to make impossible, so
it was treated as a real bug rather than an environment quirk to route
around: the scratch directory was cleared and the run repeated clean.

The repeat run then hung indefinitely instead: `DungeonIntegrationEntrypoint`
called `System.exit(exitCode)` directly from inside the `SERVER_STARTED`
callback, which runs on the server thread. Minecraft's own JVM shutdown hook
needs that same thread to notice a stop flag and unwind its tick loop before
the hook can return, so calling `System.exit` synchronously from the server
thread blocks it inside the hook it is waiting on — confirmed empirically
(36 minutes, no further log output, until the process was killed by hand).
Fixed by moving the `System.exit` call onto a separate daemon thread, so the
callback returns, the server thread proceeds into its normal tick loop, and
the shutdown hook's wait resolves. Re-verified clean: `BUILD SUCCESSFUL in
20s`, log shows `pocketdungeons:void` saved and `dungeonIntegrationTest:
PASS`, and no java process was left running afterward.

### Break-one-assertion proof

`DungeonIntegrationEntrypoint.INVERT_DUNGEON_LEVEL_ASSERTION_FOR_PROOF`
exists for the step 5 proof (invert, confirm the task goes red, restore to
`false`); `false` is the only value that should ever be committed.

### Acceptance index

Appendix "36. M62 acceptance index" appended to `LIVE_TEST_PASS.md`: a
disposition (`current`, `superseded`, `passed`, or `blocked`) for every
existing subsection plus the seven M45–M61 omissions M62's handoff named
by name (floor bank timing, recipes, frozen preview membership,
post-selection tool depletion, return paths, room preservation, silence
about room movement). One row moved to `passed`: 35.1's dimension-key half,
on `dungeonIntegrationTest` evidence. One surfaced as a live tension worth
a human decision rather than a missing row: "silence about room movement"
finds `NEXT_ROADMAP.md` asking for no room-movement sound against a
already-shipped `STONE_PLACE` cue in section 26.3.

### Verification

- `./gradlew.bat dungeonIntegrationTest --offline`: `BUILD SUCCESSFUL` in
  20s. Log confirms `server.getLevel(pocketdungeons:void)` non-null on a
  real dedicated server (not `GameTestServer`), a forced save, and
  `world/data` present on disk afterward.
- `./gradlew.bat build --offline`: full suite, including
  `dungeonIntegrationTest` as a `check` dependency.
- Production jar: `dungeonIntegrationTest`'s only entrypoint is registered
  in `src/gametest/resources/fabric.mod.json`, not the main
  `fabric.mod.json`, so it never ships.

## M63: make custody and teardown recoverable

Goal: no tested transition silently loses or duplicates a player's room,
currency or either inventory. Delivered as executable coverage first, then
the fixes that coverage forced.

### What the tests found

Four conservation bugs, each reproduced by a failing test before it was
fixed.

- **`Fuel.bank` minted currency.** It removed what it could from the
  inventory, then credited the full amount it was asked for regardless, on
  the documented assumption that the caller had already checked the carried
  count. Banking 10 while carrying 3 credited 10. `spend` now returns what
  `clearOrCountMatchingItems` actually took and `bank` credits only that.
  The same hole let an untagged lookalike stack bank 64 fuel while being
  consumed by nothing, which is PD-48 reopened through a different door.
- **Teardown leaked slots permanently.** `processClears` and `drainClears`
  both dropped the whole queue with a bare `pendingClears.clear()` when the
  dungeon level was missing. A `PendingClear` is the only thing that ever
  removes its slot from `InstanceRegistry.usedSlots`, so every abandoned
  teardown leaked one slot for the life of the server, with nothing left to
  free it. Both now route through `abandonClears`, which releases the slots
  and logs why.
- **A failed room save was invisible to the caller about to destroy the
  room.** `RoomStore.save` swallowed its `IOException` and returned void.
  It returns a boolean now, propagated through `RoomStore.capture`,
  `RunLifecycle.saveRoom` and `saveRoomIfOwnerSync` to the two teardown
  sites, which log a named recoverable record rather than clearing in
  silence.
- **An all-air capture could destroy a room twice.** This is PD-8 with the
  race already lost: if a clear reaches the room cell first, `fillFromWorld`
  succeeds and returns a blob of nothing but air. Writing it emptied the
  live file, and the *next* save then copied that empty file over the good
  backup. `capture` now refuses an all-air blob and leaves what is on disk
  alone. A room is never legitimately empty, so this can only ever be a bug.

### Recovery protocol

Three layers, in the order they are consulted.

1. **The tick invariant** (`InventorySwap.reconcile`). Converges on
   "stashed if and only if in the dungeon dimension" regardless of which
   events fired. Unchanged by this milestone except for where the journal
   check sits.
2. **The journal** (`InventoryJournal`, new). Repairs a stash record that
   the saved data lost. Consulted once per player per server run, from
   inside the reconciliation pass and *before* the invariant is read, so a
   lost record cannot be mistaken for a player who was never stashed and
   stashed a second time over an inventory already taken.
3. **Lost and Found** (`LostAndFound`, unchanged). Plain text, for a human
   with `/give`, when both of the above are gone. Spec 11.10 is explicit
   that it is not an automatic restore; the journal is the automatic half
   for the one case where automatic is provably safe.

Operator steps when a player reports missing gear: check the server log for
`Repaired a lost stash record` (layer 2 already handled it), then for
`Could not save ... room before tearing down` (the room reverted to its
previous save, and `/dungeon admin baserestore` is the next move), then read
`world/data/pocketdungeons/lostandfound/<uuid>/` newest-first and `/give`
from it.

### Fault matrix

| Fault | Behaviour | Covered by |
|---|---|---|
| Entry or exit with all 41 slots full and a stack on the cursor | Every item conserved across the round trip | `custody_game_test_full_inventory_round_trip_conserves_every_item` |
| Orphan larger than the 35 slots it restores into | Overflow dropped at the player's feet, orphan cleared only once every stack is placed | `custody_game_test_orphan_overflow_is_dropped_not_voided` |
| Room delivery that only partly fits | Delivered plus returned equals offered, caller's stacks not mutated | `custody_game_test_partial_room_delivery_returns_exactly_what_did_not_fit` |
| Stash record lost to a non-atomic saved-data write | Repaired from the journal, idempotently | `custody_game_test_a_lost_stash_record_is_repaired_from_the_journal` |
| Banking more fuel than is carried | Credits only what was removed | `economy_game_test_banking_more_than_carried_does_not_mint_fuel` |
| Untagged lookalike offered as currency | Not fuel, not banked, not consumed | `economy_game_test_an_untagged_lookalike_is_not_fuel` |
| Reward delivered to a full inventory | Dropped with components intact, exactly once | `economy_game_test_payout_overflow_drops_rather_than_voids` |
| Stale station click after the gear left the player's hand | Nothing spent at either dialog station | `economy_game_test_stale_station_clicks_spend_nothing` |
| Teardown queued with no dungeon level to write into | Queue abandoned, slots released | `teardown_game_test_a_clear_abandoned_for_amissing_level_still_frees_its_slot` |
| Parent and child teardown drained together | Both slots released | `teardown_game_test_draining_clears_frees_every_slot_it_drops` |
| Slot reuse after release | Freed slot is the next one allocated, claimed one is not | `teardown_game_test_afreed_slot_is_allocated_again_and_aclaimed_one_is_not` |
| Room save that cannot write its file | Reports failure instead of swallowing it | `RoomStoreTest.testSaveReportsSuccessAndFailure` |

### Residual limits

Stated plainly, because the constraint on this milestone was to make no
"failsafe under all circumstances" claim.

- **Disk corruption of a completed file is not covered.** The journal and
  the room store both write through a temp file and an atomic rename, so
  neither can be left half written by a crash. Neither survives a
  filesystem that loses or mangles a file it already accepted. Lost and
  Found is the answer there, and it is manual by design.
- **The saved data itself is still not atomic.** `SavedDataStorage` writes
  `dungeon_log.dat` with a bare `NbtIo.writeCompressed` onto the live path
  on `Util.ioPool()`: no temp file, no rename, no backup. Fixing that needs
  a mixin into vanilla's storage layer and this mod's mixin budget is spent
  (DISCOVERIES trap 9). The journal backstops the one field whose loss costs
  a player their gear; every other field on the dungeon log (fuel balance,
  task progress, bounties, extracted powers) would still be lost to a
  truncated write, and would need either that mixin or a wider journal.
- **The leaving branch is deliberately not journalled.** A void inventory
  has two possible destinations, the room's containers or the orphan record,
  and a full delivery clears the orphan on purpose. A recovery pass finding
  a journal entry and an empty orphan could not distinguish "delivered, the
  items are in a chest" from "the write was lost", and restoring on that
  ambiguity would duplicate every delivered item. Inferring a committed
  transfer from an absent record is the inference this milestone forbids.
- **Process termination is not tested at every boundary.** The crash window
  is staged in-process, by writing the journal record and then putting the
  world into the state a kill would have left. A real kill between the
  clear and the saved-data write, and the reload after it, remains a live
  check.
- **The gamble station's money path has no test.** `handleTrade` is a
  private callback reachable only by clicking an SGUI merchant screen, and
  DISCOVERIES trap 10 rules that out headlessly. Driving it artificially
  would assert the harness, not the station. It is a `LIVE_TEST_PASS` row.
- **Interoperability with another inventory-management mod is untested.**
  Server-only branding is not compatibility, and nothing here proves a
  second mod moving stacks mid-swap is safe.

### Verification

- `./gradlew.bat runGameTest --offline`: `All 19 required tests passed`, up
  from 7. Every one of the twelve new scenarios was observed failing before
  it passed, which is DISCOVERIES trap 19's rule for proving a gametest
  actually runs rather than being silently unregistered.
- `./gradlew.bat build --offline`: `BUILD SUCCESSFUL`, full suite including
  `dungeonIntegrationTest`, run against a deleted scratch run directory so
  trap 22's stale-lock false pass could not apply.

## M64: Prove rooms as played, not merely selected

Goal: every offered situation has a physically reachable answer and an
honest resource contract. The selector's 6.6 subset invariant proves a
cell's `requires` is satisfiable on the graph; M64 proves the blocks do
what the metadata claims, and that spending an optional tool can never
eliminate the mandatory exit.

### What was added

Four test classes, one production change to the supply tables, and one
production visibility widening for testing.

**`SituationSupplyTest`** (offline, pure JDK): synthetic manifests and
shapes that pin down four cases where the selector's boolean capability
model diverges from the physical world. Sapper TNT is finite, not
reusable masonry. Shepherd leads do not guarantee a mob. Solo Pilgrim
lacks `mob`, so Plate Pair falls back; a party of two grants `mob`
through party size, so Plate Pair is selected. Removing a tool provider
leaves the plan solvable: the mandatory spine does not depend on an
optional finite tool.

**`SituationGameTest`** (live, Fabric GameTest): `spentOptionalToolStillHasExit`
places an iron door and a chest, arms an `ITEM_ANY` lock, opens the door
with a stick, removes the stick, and asserts the door stays open. The
door is the exit; the stick is the optional tool; the lock's
persistence-after-spend rule is what keeps the exit open.

**`HandlerGameTest`** (live, Fabric GameTest): block-level handler
lifecycle coverage. Locks: `ITEM_KEY` opens for the correct item only,
stale locks purge when their door is removed, duplicate arming replaces
the old lock, and a cleared slot can be re-armed. RisingLavaHandler:
pulling the lever drains the lava and drops the room from the active
map, and a room whose lever is removed is purged as stale.
CollapsingBridgeHandler: a room whose pistons are removed is purged as
stale, and duplicate arming replaces the old bridge. ReturnPathValidator:
a ladder column validates, a water column validates, a staircase with
headroom validates, and a room with no climbable route does not
validate.

**`OmenGameTest`** (live, Fabric GameTest): four edge cases in
`OmenSources.spurTaken`, which currently reads `container.isEmpty()`.
Partial loot: false negative. Inserted junk: false negative. Initially
empty: false positive. Alternate container (barrel): correct by
construction. These are documented, not fixed; a fix is a separate
milestone.

**`SupplySeparationTest`** (offline, pure JDK plus Gson): reads each
supply chest table and verifies food and light are in guaranteed pools
(rolls = 1) while treasure stays weighted. The supply tables
(`chests/supply_tier_1.json`, `supply_tier_2.json`, `supply_tier_3.json`)
were split into three pools: guaranteed food, guaranteed light, and
weighted everything else. Treasure stays weighted, so tool scarcity and
treasure rarity are preserved.

**`GraphSolvabilityTest.testTierSweep`** (offline, pure JDK): the 6.6
invariant over seeds 0 through 499 for each admitted loot tier (1, 2,
3), using solo Pilgrim. Each tier uses a manifest that includes only
rooms whose `tier` field admits them at that level. The sweep asserts
zero unresolved plans, zero inaccessible mandatory exits (checked by
`RoomSelector.validate`), and the 6.6 subset invariant on every
non-fallback cell. Results: 496 floors per tier, 0 fallback cells, 0
unresolved, 0 inaccessible exits.

### Production changes

- `RisingLavaHandler.isArmed` and `CollapsingBridgeHandler.isArmed`:
  package-private testing helpers so tests can inspect the static active
  map without opening production visibility to other packages.
- `OmenSources.spurTaken`: widened from `private` to package-private so
  `OmenGameTest` can call it directly.
- `chests/supply_tier_1.json`, `supply_tier_2.json`,
  `supply_tier_3.json`: split the single weighted pool into three
  pools (guaranteed food, guaranteed light, weighted everything else)
  so a player who fights badly still walks out with food and light.

### What is proven and what is not

**Graph proof** (automated, headless): the selector's 6.6 subset
invariant holds over 500 seeds and three tiers. Every cell's `requires`
is a subset of what upstream `provides` plus the bag. The terminal is
reachable. No consumable gates the spine. No unsatisfiable room lands.

**Physical reachability** (automated, live world): the handler tests
prove the blocks do what the metadata claims. The door opens, the lava
drains, the bridge collapses and re-extends, the return path climbs.
The spent-tool test proves the exit stays open after the optional item
is spent.

**Human player mastery** (not automated): readability, route-finding,
combat difficulty, and the moment-to-moment experience of playing the
room. The graph proof says the room is solvable; the physical proof
says the blocks work; neither says a player will understand the room on
first sight. That is a live-play check, recorded in
`LIVE_TEST_PASS.md` section 38, not a test.

### Verification

- `./gradlew.bat runGameTest --offline`: `All 36 required tests passed`,
  up from 20. The 16 new scenarios (one Situation, twelve Handler, one
  staircase, two Omen barrel/chest) were observed failing before they
  passed, per DISCOVERIES trap 19.
- `./gradlew.bat graphSolvabilityTest --offline`: `GraphSolvabilityTest
  passed`, including the new tier sweep (496 floors per tier, 0
  unresolved, 0 inaccessible exits).
- `./gradlew.bat supplySeparationTest --offline`:
  `SupplySeparationTest passed`.
- `./gradlew.bat situationSupplyTest --offline`:
  `SituationSupplyTest passed`.
- `./gradlew.bat build --offline`: `BUILD SUCCESSFUL`, full suite
  including `dungeonIntegrationTest`.


## M65: one floor lifecycle, one silent homecoming

Goal: each floor advances once; the safe visit banks once; the last door
opens onto the player's actual room without announcing it.

### Settlement and restart table

| Event | Phase transition | Settlement | Restart |
|---|---|---|---|
| Door preview | HOME or FLOOR_CLEARED to PREVIEW | none | none |
| Door commit | PREVIEW to ACTIVE | none | none |
| Floor complete (pad) | ACTIVE to FLOOR_CLEARED | per-floor only: omen banked, chests placed, completion logged | M63 recovery |
| Safe door selected | FLOOR_CLEARED to SAFE_RETURN | interval-level: keystone level up, payout, prestige, bounty, diary | M63 recovery |
| Safe room entered | SAFE_RETURN to HOME | omen cleared, floor index reset, record reset for next visit | M63 recovery |
| Stamp failure | SAFE_RETURN to FLOOR_CLEARED (fallback) | none (staging stays usable) | fallback teleport |
| Reconnect during ACTIVE | RECOVERY to ACTIVE | none | M63 recovery |
| Reconnect during FLOOR_CLEARED | RECOVERY to FLOOR_CLEARED | none | M63 recovery |
| Reconnect during HOME | RECOVERY to HOME | none | M63 recovery |
| Duplicate completion | rejected (phase is not ACTIVE) | none | none |
| Reordered completion | rejected (phase is not ACTIVE) | none | none |
| Double safe visit | rejected (phase is HOME, not FLOOR_CLEARED) | none | none |

### What changed

- RunSession: new state machine with six phases (HOME, PREVIEW,
  ACTIVE, FLOOR_CLEARED, SAFE_RETURN, RECOVERY) and a closed transition
  table. Every lifecycle method checks or transitions the phase before
  doing its work.
- FloorLoopGameTest: three scenarios covering every legal and illegal
  edge, the safe-visit settlement contract, and omen bands at floor
  counts 1, 3 and 5.
- Instances.onTick: ordinary-floor timeout depletion removed. The
  clock still ticks for display, but no longer depletes the keystone.
  The omen system replaces the clock as the penalty.
- RunLifecycle.completeRun: per-floor observations only. Keystone
  level up, payout, prestige, bounty and diary delivery moved to
  settleSafeVisit, called once from 
eturnToSafe.
- RunLifecycle.advanceFloor: extracted from completeDungeon.
  Physical floor advance only: increment floor index, bank omen, place
  chests, stamp new staging room, transition to FLOOR_CLEARED.
- RunLifecycle.settleSafeVisit: new method. Interval-level
  settlement once per safe visit. SPEEDRUNNER bounty is now low-omen
  completion (band 0, 3 chests) rather than finished before the clock.
- RunLifecycle.returnToSafe: silent homecoming. The saved room is
  stamped behind the final staging door, the door opens, and the party
  walks through physically. No teleport, no Chime.roomRelocated, no
  explanation message. Falls back to the old teleport path if stamping
  fails.
- Instances.onTick: homecoming cleanup. After 
eturnToSafe stamps
  the room and opens the door, onTick checks whether all members have
  crossed. Once crossed, old floor cells and old staging room are
  released, and a new staging room is set up adjacent to the room.
- Instances.previewDoor: conservative live capabilities on later
  floors. The mob tag (party size >= 2) uses the live party size, not
  the original party size, since a disconnected member is not standing
  on the other plate.
- RunLifecycle.commitDoor: staging readiness gate now fires on every
  floor advance, not only the first one.
- VisitService.statusOf: tests HOME explicitly via
  RunSession.isHome rather than inferring home status from
  waitingDoorChoice.
- DialogScreens: door-chosen state tests RunSession.isActive
  rather than !awaitingDoorChoice.

### Verification

- ./gradlew.bat omenMathTest --offline: OmenMathTest passed.
- ./gradlew.bat bountyTrackerTest --offline:
  BountyTrackerTest passed.
- ./gradlew.bat taskTrackerTest --offline: TaskTrackerTest passed.
- ./gradlew.bat pocket2Test --offline: Pocket2Test passed.
- ./gradlew.bat runGameTest --offline: All 39 required tests passed
  (36 from M64 plus 3 new FloorLoopGameTest scenarios).
- ./gradlew.bat dungeonIntegrationTest --offline: loaded 5 themes, 5
  adventure nodes, 7 diary entries, 49 rooms.
- ./gradlew.bat build --offline: BUILD SUCCESSFUL in 1m 32s, full
  suite including all unit tests, GameTests and dungeonIntegrationTest.

## M66: Cube Recipe Reliability

A consumed catalyst changes exactly the floor previewed, or remains
recoverable without charging for nothing.

### Recipe effect coverage

Every promised recipe effect now influences layout planning, room
selection, and completion reporting:

- Ominous: adds Affix.OMINOUS to the effective affix set at preview
  and commit.
- Feral: adds Affix.FERAL to the effective affix set at preview and
  commit.
- Infested guarantee: forces infested_wall or creeper_kennel onto an
  eligible non-entrance, non-terminal cell during room selection.
- Flooded/Chasm weighting: flagged on the RunRecipePlan; the
  selection pass receives the plan (wiring point for future
  weight tuning).
- Deep Dark guarantee: forces deep_dark_landing onto an eligible
  cell. RunRecipePlan.resolve refuses below tier 3 (level 10) before
  the catalyst is spent.
- Compass: populates record.situations at commit and emits a
  completion study list on the first floor completion.
- Store spur: forces the_store onto an eligible cell during room
  selection.
- Bounded supply: flagged on the plan; the new BOUNDED_SUPPLY recipe
  (keystone + string) replaces BAG_OVERRIDE.
- Path extension (+2): applied to minPath and maxPath before shape
  generation, so the shape itself is longer. The new PATH_EXTENSION
  recipe (keystone + amethyst shard) replaces DOUBLE_KEY.

### Catalyst escrow and recovery

The catalyst is escrowed in the keystone custom data
(pending_catalyst) at recipe application time. On successful commit,
the escrow is cleared (the catalyst is permanently spent). On
preview cancellation (clearPreview) or commit failure, the escrowed
catalyst is restored to the owner via Payout.deliver. This follows
the M63 prepare/commit/recover pattern.

### Legacy pending-item migration

- BAG_OVERRIDE: no longer matched by any new catalyst. Legacy tags
  decode as bounded supply in RunRecipePlan. A legacy tag on commit
  produces a visible owner notice with the original bag id and a
  refund suggestion. The old bag swap is no longer performed for new
  runs; the bag_original restore path remains as a safety net for
  pre-M66 runs.
- DOUBLE_KEY: no longer matched by a second keystone. Legacy tags
  decode as +2 path length at the committed offer level. The old
  lower-key level was never stored and is not invented; the migration
  uses the current offer level with explicit notice.

### Costs that cannot be reconstructed

- The original BAG_OVERRIDE catalyst (a bag headline item) is not
  recoverable from the legacy tag. The tag stores the bag id, not the
  item id. The refund path is owner-approved and manual.
- The original DOUBLE_KEY catalyst (a second keystone) is not
  recoverable from the legacy tag. The tag does not store the
  keystone level or item. The refund path is owner-approved and
  manual.
- The lower-key level for legacy DOUBLE_KEY was never stored. M66
  does not invent it; the migration extends at the current offer
  level only.

### Verification

- ./gradlew.bat cubeStationTest --offline: CubeStationTest passed.
- ./gradlew.bat graphSolvabilityTest --offline:
  GraphSolvabilityTest passed (596 floors, 0 fallbacks).
- ./gradlew.bat keystoneOfferTest --offline: KeystoneOfferTest
  passed.
- ./gradlew.bat runGameTest --offline: All 55 required tests passed
  (39 from M65 plus 16 new CubeRecipeGameTest scenarios).
- ./gradlew.bat dungeonIntegrationTest --offline: loaded 5 themes, 5
  adventure nodes, 7 diary entries, 49 rooms.
- ./gradlew.bat build --offline: BUILD SUCCESSFUL in 1m 33s, full
  suite including all unit tests, GameTests and
  dungeonIntegrationTest.

## M67: Close the live pass and teach only the verbs

M67 is a human gate milestone. The code-side work is complete; the human
pass (tester recruitment, live observation, honest dispositions) is not.
This entry records the code-side changes only.

### What shipped

1. **Chime.roomRelocated removed.** Two call sites in RunLifecycle.java
   (post-completion relocation at L1189, safe return at L1580) and the
   method in Chime.java are deleted. The room relocation is now silent,
   honouring both the M67 constraint "No sound for room movement" and
   VISION.md section 4: "No message, no sound, no lore entry." The M62
   disposition index row "Silence about room movement" is resolved: the
   STONE_PLACE cue was the thing "silence" meant to remove, and it is
   removed.

2. **OMINOUS affix coloured in door screen (Q5).** DungeonScreen.affixLine
   refactored from returning a plain String to returning a Component.
   The OMINOUS affix label is styled DARK_PURPLE, matching the chat
   message at run start. This gives a sound-off player a visible
   environmental signal beyond the Trial Omen HUD icon: the purple
   "Cooked" text on the door screen during the run. No numeric HUD
   added, per the handoff constraint.

3. **First-time idle prompt fixed.** The level-1 idle door screen said
   "Select the Oak Door" without mentioning right-clicking. It now says
   "Right-click the Oak Door", matching the returning-player prompt's
   clarity. A first-time player who has never interacted with a
   selector door now has the verb they need.

### Chime audit

All 47 Chime call sites across 9 files audited. No recipient errors, no
duplicate playback, no significant competition with vanilla hazard
sounds (all chimes use SoundSource.RECORDS, hazard sounds use HOSTILE
or NEUTRAL, volumes 0.2 to 0.5). The one constraint violation
(roomRelocated) is fixed. All other chimes are correct.

### What did not ship

The human pass. M67's implementation plan steps 1 (recruit testers), 2
(observe fresh players), and 4 (close the pass with honest dispositions)
require human testers on a live client. No fake player evidence is
presented. Every current row in the M62 disposition index stays
current until the human pass runs. Round II waits for the human gate,
not just the build.

### Verification

- ./gradlew.bat lodestoneMenuTest: LodestoneMenuTest passed.
- ./gradlew.bat lobbyBrowserTest: LobbyBrowserTest passed.
- ./gradlew.bat taskTrackerTest: TaskTrackerTest passed.
- ./gradlew.bat runGameTest: All 55 required tests passed.
- ./gradlew.bat dungeonIntegrationTest: PASS (5 themes, 5 adventure
  nodes, 7 diary entries, 49 rooms, 6 anomaly rooms, 40 core loot
  tables).
- ./gradlew.bat build: BUILD SUCCESSFUL in 1m 40s, full suite green.


## M68: Namespaced versioned content contracts

Two packs with the same local names coexist, and reload never mixes
incompatible generations.

### What changed

- **Namespaced identity.** Every content manifest (rooms, anomaly rooms,
  themes, adventure graphs, diaries) is now keyed by 
amespace:path
  instead of the bare filename. JsonPackSupport.resourceId strips the
  content-type folder prefix and the .json suffix, producing ids like
  pocketdungeons:hall_tee or mypack:sub/dir/entry. Two packs with the
  same local name in different namespaces both load.

- **Legacy reference resolution.** JsonPackSupport.qualify maps a legacy
  bare reference (no colon) to the pocketdungeons namespace. The lookup
  methods (RoomManifest.byName, ThemeManifest.byId,
  AdventureGraph.node) resolve a bare id to pocketdungeons:<name> when
  the direct lookup misses, so existing call sites that pass a bare name
  keep working and a pre-M68 dungeon_log.dat with a bare currentTheme
  still drives the graph pick. A bare name that is not a pocketdungeons
  built-in returns null, the deterministic rejection the schema promises
  in place of last-file-wins.

- **Versioned schemas.** Five JSON schemas published in docs/schema/:
  dungeon_room, dungeon_theme, dungeon_adventure, nomaly_room,
  diary. Each declares a ersion field (default 1 when absent). The
  parsers call JsonPackSupport.parseVersion to reject an unsupported
  generation up front with the file named.

- **Namespaced theme references.** DungeonThemeMeta adds three optional
  fields alongside the legacy loot_suffix and spawner_prefix:
  loot_table, 
ormal_spawner, ominous_spawner. Each is a fully
  qualified id that overrides the legacy composition. A third-party theme
  can now point at its own namespace's loot table and trial spawner configs
  instead of secretly requiring pocketdungeons data. The namespaced
  spawner configs are validated against the trial spawner config registry
  at load time (trap 6: a misspelt id does not throw at the block entity).

- **ContentSnapshot and ContentReload.** A single reload listener
  (ContentReload) owns the atomic build-then-commit of all five content
  surfaces. ContentSnapshot.build parses all five without publishing,
  runs the cross-resource validation (adventure graph transitions resolved
  against the snapshot's own theme set), and checks the required coverage
  gate (at least one entrance and one exit room). A candidate that fails
  the gate is discarded; the last valid snapshot's manifests stay
  published so active floors keep resolving. The per-loader reload
  listeners (RoomManifest.register, ThemeManifest.register) are now
  no-ops; ContentReload.register is the single listener.

- **Generation prohibition during reload.**
  ContentReload.generationAllowed() is false while a build is in
  progress. Instances.previewDoor and Instances.commitDoor check it
  and refuse to start a new floor, so a floor is never stamped against a
  half-published manifest.

- **Active-floor pinning.** After a coherent reload,
  ContentReload.reconcileActiveFloors walks the live instances and
  invalidates any preview whose plan references a room or theme the new
  snapshot no longer carries. An already-stamped floor is left in place:
  it is pinned to the geometry already in the world, and the generation
  prohibition above is what kept a new generation from racing the swap.

- **DungeonLog legacy migration.** DungeonLog.recordTheme migrates a
  legacy bare completedThemes key into the namespaced key when a
  qualified completion arrives for the same theme, merging the count so
  the theme is not double-counted. Old saved fields are preserved; unknown
  references are kept for recovery.

### Verification

- contentSnapshotTest: namespaced identity, legacy qualification,
  version parser, DungeonLog legacy migration.
- 
unGameTest: 60 tests (55 original + 5 M68 live server checks for
  snapshot validity, legacy lookup resolution, and generation gate).
- dungeonIntegrationTest: PASS (5 themes, 5 adventure nodes, 7 diaries,
  49 rooms, 6 anomaly rooms, 40 core loot tables, 0 rejections).
- uild: BUILD SUCCESSFUL, full suite green.

## M69: Data-driven affixes

A new namespaced affix works without adding a Java enum constant. The
Affix enum is deleted; every affix is a JSON definition loaded from
data/<namespace>/dungeon_affix/*.json.

### What changed

- **AffixDefinition and AffixEffects.** A definition carries its
  namespaced id, label, blurb, stable order, min level, weight,
  depletion multiplier, incompatibilities, and a bounded AffixEffects
  payload. The effects model covers: ominous blockstate, trial count
  multiplier, cooldown factor, player range, consumable rule, neutral
  wolf spawn, interior hazard kind and count, voided floor, extra trial
  bodies, bonus tool pool, and decor pool. Every field is bounded and
  validated at load; an unsupported operation is silently ignored by
  the parser, and a definition that bends nothing is rejected outright.

- **AffixManifest.** A reloadable manifest that scans
  dungeon_affix/*.json across every namespace, parses each file into an
  AffixDefinition, validates incompatibility symmetry, validates loot
  table references against the server reloadable registries, and
  publishes atomically through ContentSnapshot. A pack that drops the
  pocketdungeons pack cannot silently remove Ominous: the built-in
  coverage gate fails the candidate and the last valid snapshot stands.

- **AffixIds.** Namespaced id constants for every built-in affix, plus
  the legacy bare-name to namespaced-id bridge. A pre-M69 save that
  holds "ominous" loads as "pocketdungeons:ominous" without a codec
  migration. A third-party id ("theirpack:their_affix") flows through
  every site the built-ins do.

- **AffixMath migration.** AffixMath no longer depends on
  Affix.values() or EnumSet<Affix>. It takes a stable
  List<AffixDefinition> as a parameter, keeping the class pure Java
  (no Minecraft imports) so the plain-javac test builds fixtures
  without the server classpath. Seeding, naming, depletion, and the
  no-weekly-rotation contract are preserved. Depletion remains the
  max multiplier, capped at 2.

- **Set<String> carriers.** Every carrier type (Keystone.Offer,
  InstanceRecord, InstanceLayout, RunRecipePlan, ExperimentalDungeon,
  DungeonLog) stores affixes as Set<String> namespaced ids. EnumSet is
  gone from the affix path. The Affix enum is deleted.

- **Built-in JSON data.** Nine JSON files under
  data/pocketdungeons/dungeon_affix/ define Ominous, Feral, Swarming,
  Overclocked, Molten, Silenced, Explosive, Voided, and Loaded. Each
  carries real effect operations, not just a label rename. The Loaded
  bonus tool pool draws from
  data/pocketdungeons/loot_table/affixes/loaded_tools.json.

- **Loaded affix.** A data-only affix that grants extra trial bodies
  and a guaranteed bonus tool cache in loot cells. Works through the
  JSON/effect system; no Java enum constant was added.

- **Unified post-content phase.** RoomContent.apply no longer returns
  early for situation cells before affix effects. The situation
  handler anchor is collected and the cell falls through to the
  post-content affix phase, so a Molten/Explosive/Voided run still
  stamps its hazards in a situation cell. The store anomaly (a safe
  room) still returns early, since a hazard there would make it
  impassable.

- **Content revision pinning.** The affix manifest participates in
  the M68 atomic snapshot and reload contract. A presented offer
  affix set is frozen at preview time through the existing
  previewRecipePlan revision; a reload that changes affix definitions
  invalidates stale previews through the existing
  reconcileActiveFloors path, extended to the affix surface.

### Supported operations and extension limits

A third-party affix may declare any combination of the bounded
operations in AffixEffects. The supported set is closed: a JSON field
the parser does not read is silently ignored, and a definition that
declares no operation is rejected. Genuinely new operations (a new
hazard kind, a new consumable rule, a new effect type) still require
reviewed engine work. The bounds are:

- trial_count_multiplier: [1.0, 4.0]
- cooldown_factor: [0.25, 1.0]
- player_range: [4, 14]
- hazards_per_cell: [0, 16]
- depletion_multiplier: 1 or 2
- hazard_kind: NONE, LAVA, TNT
- consumable_rule: ALLOW, BLOCK

### Frozen caller list

The exhaustive caller list at migration time:

  TrialContent, RunLifecycle, LayoutStamper, RoomContent, DungeonLog,
  Instances, Keystone, Keystones, AffixMath, ExperimentalDungeon,
  SilenceListener, InstanceLayout, StaticLayout, Pocket2,
  RunRecipePlan, VisitService, DungeonScreen, DialogScreens,
  DungeonCommands, InventorySwap, Situations, TraversalSpecs.

### Verification

- affixMathTest: parse compat, join round trip, seeded count
  thresholds, seeded stability, depletion multiplier, name, third-party
  id. All passed.
- keystoneMathTest: passed.
- keystoneOfferTest: passed.
- difficultyProfileTest: passed.
- runGameTest: compileGameTestJava passed.
- dungeonIntegrationTest: build passed.
- build: BUILD SUCCESSFUL in 1m 30s, full suite green.

## M70: Author roles and bags, keep geometry honest

An author adds a supply-room role and a bag without modifying a role
switch or enum. The Bags enum is deleted; every bag is a JSON definition
loaded from `data/<namespace>/dungeon_bag/*.json`. The hard-coded role
switch in RoomContent and the role-string checks in LayoutStamper are
replaced with data-driven `RoomRoleDefinition`s loaded from
`data/<namespace>/dungeon_role/*.json`. A third-party role or bag flows
through every site the built-ins do, with no Java edit.

### What changed

- **BagDefinition, BagMeta, BagIds, BagManifest.** The bag side mirrors
  the M69 affix pattern. `BagManifest.parse` loads every
  `dungeon_bag/*.json` resource into a `BagDefinition` keyed by namespaced
  id, validates the capability tag vocabulary against `SituationTags` and
  the loot table reference against the server's reloadable registries, and
  publishes atomically through `ContentSnapshot`. `BagIds` holds the
  built-in id constants and the legacy bare-name bridge; `BagMeta` is the
  JSON parser.

- **RoomRoleDefinition, RoleMeta, RoleIds, RoleManifest.** The role side
  mirrors the bag side. `RoleManifest.parse` loads every
  `dungeon_role/*.json` resource into a `RoomRoleDefinition` keyed by
  namespaced id, rejects `stage: "structural"` (engine-owned), and
  validates the `operation` against a closed set: `TRIAL_ENCOUNTER`,
  `TOOL_CACHE`, `NONE`. `RoleIds` holds the structural and built-in
  population role id constants and the legacy bare-name bridge.

- **Bags facade.** The `Bags` enum is replaced with a final class that
  delegates to `BagManifest.current()`. `byId`, `tagsFor`,
  `headlineItems`, `displayName`, `blurb`, `tableId`, and `apply` all read
  from the manifest. `ids()` and `paths()` read from `BagIds` directly so
  `LootTables` has its list at class-init time, before the manifest loads.

- **LayoutGraphGenerator.** `assignRoles` draws from a
  `List<RoomRoleDefinition>` by weight. The 5-argument `generate` overload
  uses the built-in definitions (encounter 45, loot 25, corridor 30) so
  pure-JDK tests need no server. The 6-argument overload takes the loaded
  manifest, so a third-party role is assigned without a Java edit. The
  guarantee and balance passes still force an encounter and a loot cell
  onto the critical path by their built-in ids.

- **RoomContent.** The role `switch` is replaced by operation dispatch:
  `TRIAL_ENCOUNTER` stamps a trial spawner, `TOOL_CACHE` stamps a vault,
  `NONE` removes the chest. The affix hazard checks (Molten, Explosive,
  Feral, Loaded) read the role's operation rather than the role string,
  so a third-party role with any of the three operations is eligible.

- **LayoutStamper.** The pocket-door placement check reads the role's
  operation rather than `"encounter".equals(role)`.

- **RoomSelector.** The selector's `prepare` method rejects an unknown
  role id with a named validation error (`"unknown role: <id>"`) rather
  than silently falling through to generic room behaviour. Structural
  roles (entrance, exit) are accepted; every population role must be in
  the loaded `RoleManifest`.

- **DungeonRoomMeta.parseRoles.** Bare role names in room JSON files
  are qualified to namespaced ids at parse time, so a room file's
  `"encounter"` matches the `"pocketdungeons:encounter"` the generator
  assigns. A qualified id is returned as-is, so a third-party room can
  declare `"theirpack:their_role"`.

- **ContentSnapshot.** The snapshot now carries eight surfaces: rooms,
  anomaly rooms, themes, adventure, diaries, affixes, bags, and roles.
  The coverage gate requires every built-in bag id and every built-in
  population role id to be present; a candidate that fails is not
  published.

### Bounded operations

A population role composes one of three bounded operations:
`TRIAL_ENCOUNTER`, `TOOL_CACHE`, `NONE`. The set is closed; a JSON file
that declares an operation outside it is rejected at load. Genuinely new
operations still require reviewed engine work. This is the same gate
`AffixEffects` holds for affix operations: JSON composes existing bounded
operations, it does not define new ones.

A role never changes topology. Structural roles (entrance, exit) are
engine-owned and not loaded from JSON. A pack cannot add a structural
role, and naming one in a `dungeon_role` file is rejected at load.

### Built-in data files

Eight bag definitions in `data/pocketdungeons/dungeon_bag/` (mason,
plumber, sapper, magician, ranger, shepherd, innkeeper, pilgrim) and
four role definitions in `data/pocketdungeons/dungeon_role/` (encounter,
loot, corridor, field_cache). The `field_cache` role demonstrates
author-extensible supply-room content: a `TOOL_CACHE` operation with
`min_depth: 2` and weight 10, so it appears on deeper floors without a
Java edit.

A test-only foreign bag (`data/pocketdungeons-gametest/dungeon_bag/
foreign.json`) proves a namespaced third-party bag loads without Java
changes. It loads alongside the eight built-ins in the game test server
(9 bags, 0 rejected).

### Frozen caller list

The exhaustive bag caller list at migration time:

  DialogScreens, DialogRouter, CubeRecipe, BagTags, InventorySwap,
  LootTables, Bags.

The exhaustive role caller list at migration time:

  LayoutGraphGenerator, LayoutPlanner, RoomSelector, RoomContent,
  LayoutStamper, DungeonCommands, RoomValidator, RoomEditorMetadata,
  DungeonRoomMeta, ContentSnapshot, ContentReload.

### Verification

- bagSelectionTest: 8 bags in stable order, picker and confirm dialog.
- bagTableTest: 8 bags, food floor, power ceiling, tag rules.
- graphSolvabilityTest: 596 floors, 6873 cells, 0 fallback cells.
- situationSupplyTest: bag supplies, finite tool, party size, access.
- supplySeparationTest: passed.
- contentSnapshotTest: namespaced identity, legacy qualification.
- planSelectorTest: straight resolves, branch fails, theme filter.
- pipelineProof: 200 of 200 seeds planned.
- runGameTest: 60 tests passed (9 bags, 4 roles loaded).
- build: BUILD SUCCESSFUL in 1m 56s, full suite green.
## M71: Data recipes with a discovery floor

A pack author adds a real recipe; a player has a dependable first
experiment without being handed the catalogue. The `CubeRecipe` enum is
deleted; every recipe is a JSON definition loaded from
`data/<namespace>/cube_recipe/*.json`. A third-party recipe flows through
every site the built-ins do, with no Java edit. The Cube station
interception stays in Java because component-aware keystone validation
cannot be expressed by ordinary `Ingredient` matching. There is no
crafting mixin.

### Recipe schema

Each `cube_recipe/*.json` file defines one recipe with: a namespaced id
(from the file path), a confirmation message, a catalyst predicate (item
id or item tag id, mutually exclusive), a cost, a priority, a keystone
level eligibility, and a closed set of typed effects.

The supported effect set:
- `ominous`, `feral`, `completion_study_list`, `bounded_supply`
  (booleans): the M66 typed effects, lifted into data.
- `path_length_bonus` (integer 0 to 8): added to both min and max path
  bounds.
- `weighted_rooms` (array of room names): rooms to weight up in the
  selection pass. A named room draws three times its declared weight.
  Generalises M66's hardcoded flooded/chasm weighting.
- `guaranteed_rooms` (array of groups): room groups to force onto an
  eligible cell after the main pass. Each group has `names` and
  `min_tier`. A group whose `min_tier` the offer cannot satisfy refuses
  before the catalyst is spent. Generalises M66's hardcoded infested,
  Deep Dark, and Store guarantees.

A recipe that declares no effect is rejected at load. Two recipes whose
catalyst predicates can match the same stack is an ambiguity the match
path refuses at use time. The manifest catches the static case at load.

### Personal discovery rules

A recipe is discovered the first time a player successfully applies it at
the Cube. The discovery is personal, never broadcast, and never browsed.
A recipe the player has not discovered is never listed, never
auto-completed, and never shown in any catalogue, because no catalogue
exists (VISION 5.4).

The discovery floor guarantees a catalyst (a bone, the Feral recipe's
catalyst) by the first eligible safe visit (lobby entry at the Cube
unlock level) and presents a terse "A bone. Try this at the Cube."
message. After that the floor never fires again. A player who drops the
catalyst gets no second floor; the floor is a dependable first
experiment, not a supply line.

Knowledge spreads through conversation, not through server-wide
discovery broadcasts. Optional handwritten books and cards may carry
player knowledge, but are never required keys or mandatory clues (VISION
9). Failed experiments do not destroy essential progression supplies:
the catalyst escrow restores a cancelled catalyst, and a refused recipe
does not consume one at all.

The personal state (discovered recipes, ingredients encountered, floor
delivered flag) is held as a sidecar on `DungeonLog` and survives save
and reload. A pre-M71 save loads with empty discovery state. A
removed-pack recipe id stays in the discovery set: knowledge survives
removal, even if the recipe is no longer in the manifest.

### What changed

- **RecipeEffects, CubeRecipeDefinition, CubeRecipeMeta, CubeRecipeManifest,
  RecipeIds.** The recipe side mirrors the M69/M70 manifest pattern.
  `CubeRecipeManifest.parse` loads every `cube_recipe/*.json` resource
  into a `CubeRecipeDefinition` keyed by namespaced id, validates catalyst
  references, checks for duplicate catalyst declarations, and publishes
  atomically through `ContentSnapshot`.
- **RunRecipePlan generalised.** The hardcoded boolean effects
  (`infestedGuarantee`, `deepDarkGuarantee`, `storeSpur`,
  `floodedChasmWeighted`) are replaced with data-driven lists
  (`weightedRooms`, `guaranteedRooms`). The tier gate is generalised:
  any guaranteed room group with a `min_tier` the offer cannot satisfy
  refuses before the catalyst is spent.
- **RoomSelector weighted rooms.** The selection pass boosts the weight
  of rooms named in the recipe plan's `weightedRooms` set by 3x, applied
  in `CellState.next()` so a reload that removes the recipe restores the
  room's original distribution without a manifest republish.
- **CubeRecipe rewritten.** The enum is replaced with a final class. The
  match path iterates the live manifest's definitions in priority order,
  checks the keystone level, and rejects an ambiguous match. The apply
  path writes the namespaced recipe id into the keystone tag, escrows the
  catalyst, records the discovery, and records the ingredient. The
  keystone tag readers and catalyst escrow helpers are preserved for M63
  custody.
- **RecipeDiscovery sidecar on DungeonLog.** A per-player sidecar map
  (like task progress and bounties) holding discovered recipes,
  ingredients encountered, and the floor-delivered flag. The codec uses
  `optionalFieldOf` so a pre-M71 save loads unchanged.
- **DiscoveryFloor.** Fires once on lobby entry at the Cube unlock level,
  delivers a bone, sends a terse message, and marks the floor delivered.
- **ContentSnapshot, ContentReload.** The recipe manifest is parsed
  alongside the other surfaces, checked for built-in coverage (all 9
  built-in recipe ids must be present), and published atomically.

### Built-in data files

Nine built-in recipe definitions in `data/pocketdungeons/cube_recipe/`
(ominous, feral, bounded_supply, infested, flooded, deep_dark, compass,
path_extension, store). Three shipped experiments (blaze_bias, bazaar_bias,
slime_guarantee) demonstrate weighted and guaranteed room effects using
existing M66 operations without new effect types. A test-only foreign
recipe (`data/pocketdungeons-gametest/cube_recipe/foreign.json`) proves a
namespaced third-party recipe combining existing effects (path length
bonus + completion study list) loads without Java changes.

### Verification

- cubeStationTest: passed.
- taskTrackerTest: passed.
- dungeonLogTest: passed (discovered-recipe codec round trips, pre-M71
  save defaults, removed-pack recipe id survival, duplicate discovery
  no-op, floor delivered flag round trips).
- runGameTest: 61 tests passed (13 recipes loaded: 9 built-ins, 3
  experiments, 1 foreign).
- build: BUILD SUCCESSFUL in 1m 56s, full suite green.

## M72: the first external pack

M72 closes the loop for a non-developer author: author, validate, distribute
and upgrade a pack using only a release jar. No source-tree workflow is
advertised as pack tooling. The bundled `DatapackExporter` bulk export stays
available for operators, unchanged; PackValidator adds the authoring surface
on top of it.

### What changed

- **PackValidator.** A new final class that does three jobs the bulk exporter
  does not. (1) `validate` builds a candidate `ContentSnapshot` from the live
  server and reports every actionable finding as `file / field: cause`, with a
  trailing `(seed=N)` for plan-level findings the operator can replay with
  `/dungeon admin plan N`. The checks are: parse rejections from all nine
  surfaces, required coverage and the (mask, role) pairs, reachable adventure
  nodes (BFS from entry themes; a node nothing reaches is a dead branch),
  Cube recipe eligibility and guarantees (every weighted and guaranteed room
  reference must resolve; a guarantee group with no resolvable room can never
  fire), missing loot (a theme's namespaced `loot_table`, which ThemeManifest
  does not validate at load, is checked against the reloadable loot registry
  here), and a door/return-path plan check that generates a shape, validates
  it (the return path is the BFS from the entrance reaching every cell), and
  resolves a room for every cell, sweeping the first 16 seeds. (2)
  `exportStarter` writes a small namespaced starter pack to a fresh
  destination. (3) `exportAuthorWorkspace` ships the rooms an author captured
  with buildroom/saveroom as a distributable pack. Both exports refuse to
  overwrite an existing destination; an explicit, destination-specific
  `confirm` backs the existing destination up to a timestamped sibling first.
- **Starter pack.** A bundled resource tree under `/pack_starter` (outside
  `data/`, so the game never loads it as live content), with one working
  example of every content type under the `starter` namespace, each referencing
  real built-in resources so the pack loads cleanly as a starting point. A
  README in the root walks the author through renaming the namespace and
  editing.
- **Validation command.** `/dungeon admin validate [seed]` runs the validator
  and prints findings; a seed argument runs the plan check against that one
  seed for reproducing a specific failure. `/dungeon admin exportstarter` and
  `/dungeon admin exportworkspace` (each with a `confirm` literal) invoke the
  two exports.
- **packValidationTest.** A headless `JavaExec` test (`PackValidatorTest`)
  covering the pure-JDK surface: the Finding seed rule, the safe-replace
  decision, the pack.mcmeta format (it boots the game's constants the way the
  other headless tests do and asserts the written `pack_format` equals
  `SharedConstants.DATA_PACK_FORMAT_MAJOR`, the 26.2 format for this build),
  and that the starter resource tree is bundled and reachable on the
  classpath (which holds in both the development file: and packaged jar:
  paths). Registered in `build.gradle.kts` and wired so both `test` and
  `build` depend on it.
- **AdventureGraph.nodeThemes.** A small accessor so the validator can
  enumerate every node for its reachability sweep.
- **LICENSE.** The workspace-root MIT licence is copied to
  `pocketdungeons/LICENSE` for standalone distribution.

### Built-in data files

The starter pack in `src/main/resources/pack_starter/`: a README and one
example file per content surface (dungeon_room, dungeon_theme,
dungeon_adventure, dungeon_affix, dungeon_bag, dungeon_role, cube_recipe,
diary, anomaly_room) under the `starter` namespace. The author worksheet is
`docs/AUTHOR-EXERCISE.md`.

### Verification

- packValidationTest: passed (Finding seed rule, safe-replace decision,
  pack.mcmeta format equals SharedConstants.DATA_PACK_FORMAT_MAJOR, starter
  resources bundled and readable on the classpath).
- dungeonIntegrationTest: PASS (all nine surfaces loaded, 0 rejected; the
  starter tree is outside `data/` so it adds no live content).
- runGameTest: 61 tests passed.
- build: BUILD SUCCESSFUL in 2m 19s, full suite green.

### Independent author exercise

The author worksheet (`docs/AUTHOR-EXERCISE.md`) is ready for an independent
author to run against the published jar. The exercise's observed authoring
failures and fixes will be appended here once a human author has run it; that
report is the beta-ready gate for the API.

### Run 1 (2026-09-09): observed authoring failures and fixes

A human author ran the exercise against the published jar. The API is
beta-ready: every step completed from the worksheet and diagnostics alone,
with no source access. Five issues were found and fixed during the run.

1. **No in-game namespace rename.** The initial `exportstarter` wrote the
   `starter` namespace verbatim, forcing the author to rename directories and
   edit files by hand. Fixed: `exportstarter` now takes an optional namespace
   argument and rewrites `starter:` into file paths and contents during
   export. The author runs `/dungeon admin exportstarter mypack` and gets a
   pack under `mypack:` with no file editing.
2. **Backup directory loaded as a live pack.** The backup went to
   `<world>/datapacks/<name>.backup-<millis>/` with a `pack.mcmeta`, so
   Minecraft loaded it alongside the new pack, causing diary band and catalyst
   collisions. Fixed: backups now go to `<world>/pocketdungeons_backups/`
   (outside `datapacks/`), so Minecraft never scans them.
3. **Plan check false failure on custom roles.** The starter role shipped with
   `weight: 10` but no room supported it, so the planner tried to place it and
   failed. The plan check reported the first failing seed instead of checking
   whether any seed succeeds. Fixed: starter role uses `weight: 0` (never
   assigned to a cell), and the plan check only reports if every seed fails.
4. **Starter diary band collision.** The starter diary used `band: 1`,
   colliding with the built-in `entry_1`. Fixed: starter diary uses
   `band: 100`.
5. **Worksheet gaps.** Steps 2, 3, and 5 lacked concrete examples (no affix
   JSON, no processor list, no catalyst list). Fixed: the worksheet now
   includes a Molten affix example, the five built-in processor lists, and the
   full built-in catalyst table.

The author also discovered that `minecraft:gold_nugget` is a duplicate
catalyst (used by built-in `bazaar_bias`). The validator correctly rejected
it and named the collision. The author switched to `minecraft:gold_ingot` and
passed. This is the validator working as designed.

The `/reload` silence (no success message) was confusing on first encounter.
It is a vanilla Minecraft limitation; adding a success message would require
spending the mod's one mixin on a vanilla command, which the conventions
forbid. Noted in the worksheet.

## M73: six new identities from the existing geometry

The composition space doubles without a single new `.nbt` template. Six
themes ship as data only: theme metadata, adventure nodes, decorative
processor lists, theme-specific trial spawner rosters, layered chest loot,
and one signature room each. Every identity reuses the existing 14-template
geometry and the M69/M70 affix and role operation sets. No engine operation
was added.

### The six themes

Each pair is contrasted on palette, roster, loot, and signature room. A
pair that played identically would have been rejected; none did.

- **Rootworks** (overgrowth) versus **Frostworks** (footing). Rootworks
  recolours shell to moss, rooted dirt, and shroomlight; its roster leans
  spider plus witch, its loot is vine, string, moss, shears, spore
  blossoms. Frostworks recolours to packed and blue ice; its roster leans
  stray plus zombie, its loot is snowballs, ice, packed ice. The signature
  rooms are `rootworks_grove` (mossy tee under a grove processor) and
  `frostworks_glaze` (mossy tee under a glaze processor).
- **Copper Works** (mechanisms) versus **Ossuary** (ranged threats).
  Copper Works recolours to copper, cut copper, and a redstone lamp; its
  roster leans zombie plus creeper, its loot is redstone, repeaters,
  pistons, copper. Ossuary recolours to bone blocks and soul lanterns;
  its roster leans skeleton plus stray, its loot is arrows, bows, bones.
  The signature rooms are `copper_works_forge` and `ossuary_crypt`.
- **Basalt Foundry** (heat) versus **Ender Archive** (displacement and
  darkness). Basalt Foundry recolours to nether bricks, basalt, blackstone,
  glowstone; its roster leans blaze plus magma cube, its loot is nether
  bricks, magma cream, blaze rods. Ender Archive recolours to end stone
  bricks, end stone, crying obsidian, end rods; its roster leans enderman
  plus silverfish, its loot is ender pearls, chorus fruit, end rods. The
  signature rooms are `basalt_foundry_crucible` and `ender_archive_vault`.

### What shipped

- `dungeon_theme/{rootworks,frostworks,copper_works,ossuary,basalt_foundry,ender_archive}.json`
  with `processors`, `spawner_prefix`, `loot_suffix`, and `room_theme`.
- `dungeon_adventure/{rootworks,frostworks,copper_works,ossuary,basalt_foundry,ender_archive}.json`
  as `descent` nodes, plus updated `deepslate.json` and `prismarine.json`
  entry edges so all six are reachable from both entry themes.
- `worldgen/processor_list/theme_{rootworks,frostworks,copper_works,ossuary,basalt_foundry,ender_archive}.json`
  and a `_grove`, `_glaze`, `_forge`, `_crypt`, `_crucible`, `_vault`
  signature variant per theme. All processors target only shell blocks
  (`stone_bricks`, `polished_andesite`, `mossy_stone_bricks`,
  `sea_lantern`), so functional blocks, doors, and provider blocks are
  never rewritten.
- `trial_spawner/{theme}_tier_{1,2,3}/{normal,ominous}.json` for all six
  themes (36 files), each with a theme-specific roster and tier-scaled
  counts.
- `loot_table/chests/tier_{1,2,3}_{theme}.json` and
  `loot_table/chests/tier_{1,2,3}_ominous_{theme}.json` (36 files), each
  layering a base chest or ominous table with a themed pool.
- `dungeon_room/{rootworks_grove,frostworks_glaze,copper_works_forge,ossuary_crypt,basalt_foundry_crucible,ender_archive_vault}.json`
  signature rooms, each reusing an existing template under a
  room-specific processor list (M1 room-specific precedence).

### Affixes deferred

The handoff asked for two data-only affixes, `jumpy` and `clingy`. Both
are deferred, not shipped, because the M69 operation set cannot express
their intended behaviour without new engine work, and the milestone
forbids smuggling engine work into data authoring.

- `jumpy` (breeze-heavy roster exchanged for wind charges): a spawner
  roster change is a theme-content concern, not an affix operation. The
  M69 `AffixEffects` set has no field that rewrites a trial spawner's
  `spawn_potentials`. `bonus_tool_pool` could carry wind charges as a
  gift, but its only application site (`RoomContent.placeLoadedToolCache`)
  is gated by `affixes.contains(AffixIds.LOADED)` and looks up
  `AffixIds.LOADED` by id, so a third-party affix declaring
  `bonus_tool_pool` would have its pool validated at load and then never
  placed. Shipping `jumpy` as data would hide non-behaviour.
- `clingy` (webs exchanged for guaranteed shears and recoverable string):
  `HazardKind` is closed at `NONE`, `LAVA`, `TNT`; there is no `WEB`
  hazard. `decor_pool` is parsed and validated against the loot registry
  but has no application site in `RoomContent` or anywhere else; it is a
  stored field with no runtime effect. `bonus_tool_pool` could carry
  shears, but the same Loaded-gate problem applies. Shipping `clingy` as
  data would hide non-behaviour.

The deferral is recorded in `docs/DISCOVERIES.md` as trap 34.

### Verification

- packValidationTest: passed.
- adventureGraphTest: all checks passed (11 themes, 11 adventure nodes,
  0 rejected, all six new themes reachable from both entry edges).
- trialContentConfigIdTest: passed (all 36 new spawner configs resolve).
- graphSolvabilityTest: passed (0 unresolved transitions, 0
  inaccessible exits, 0 fallback cells for standard tier sweeps).
- runGameTest: 61 tests passed. Live load confirmed 11 themes (0
  rejected), 11 adventure nodes (0 rejected), 9 affixes, 9 bags, 4 roles,
  13 recipes, 40 core loot tables.
- build: BUILD SUCCESSFUL, full suite green.
## M74: six situation rooms from private lower stories

Six new situation rooms ship using private lower stories rather than a new
layout engine. Each room is a `RoomSpec` in `SituationSpecs.java` with a
matching `dungeon_room` JSON, a generated `.nbt` template, and (for combat
rooms) a `trial_spawner` config. No new engine operation was added.

### The six rooms

Each pair is contrasted on situation, solution, and lower-story use. A
room that failed admission would have been rejected; none did.

- **Sump** (tier 1, corridor, spanY 2) versus **Ropewalk** (tier 2,
  corridor, spanY 2). Sump offers water and current redirection with a
  dry stair return. Ropewalk offers a high crossing and a slower lower
  path. Both use `spanY: 2` with a permanent staircase return path,
  verified by `ReturnPathValidator` after stamping.
- **Sorting Floor** (tier 2, corridor, gated) versus **Sensor Gallery**
  (tier 2, encounter, gated). Sorting Floor routes a returned item
  through water to a hopper filter. Sensor Gallery uses sculk sensors
  activated by vibrations (snowballs or mob footsteps) to open the door.
  Each owes doorway readability, two useful solutions, a real slow path,
  and two-of-three utility.
- **Kennel Crossing** (tier 2, encounter, open) versus **Blaze Cellar**
  (tier 3, encounter, spanY 2, open). Kennel Crossing offers a steerable
  contained hazard (wolves behind a fence gate) with a tool-free bypass.
  Blaze Cellar offers the snowball or water advantage over a lower-story
  blaze threat with a permanent staircase. Blaze Cellar uses `spanY: 2`
  with a permanent return path, verified by `ReturnPathValidator`.

### What shipped

- `src/main/java/pocketdungeons/SituationSpecs.java`: six `RoomSpec`
  definitions with `decor` callbacks, `spanY`, `spawner`, and `chests`
  fields. Shared helpers for iron doors, hoppers, comparators, dust, pots,
  and filter hoppers.
- `dungeon_room/{sump,ropewalk,sorting_floor,sensor_gallery,kennel_crossing,blaze_cellar}.json`
  room metadata.
- `structure/rooms/{sump,ropewalk,sorting_floor,sensor_gallery,kennel_crossing,blaze_cellar}.nbt`
  generated templates.
- `trial_spawner/{sensor_gallery,kennel_crossing,blaze_cellar}/{normal,ominous}.json`
  spawner configs (6 files). Sump, Ropewalk, and Sorting Floor are
  non-combat and need no spawner config.

### Composition measurement

`/dungeon admin plansurvey 1000`: 1000 succeeded, 0 failed. Each room
has `maxPerDungeon: 1`, so no room repeats within a single dungeon. The
rooms are spread across tiers, so no single dungeon contains all six.
No bounded composition constraint was added; the evidence does not
require one.

### Verification

- graphSolvabilityTest: passed (0 unresolved, 0 inaccessible exits, 0
  fallback cells for standard tier sweeps).
- dungeonRoomMetaTest: passed.
- packValidationTest: passed (no findings).
- runGameTest: 61 tests passed. Live load confirmed 61 rooms (0
  rejected), 6 anomaly rooms, 11 themes, 40 core loot tables.
- plansurvey 1000: 1000 succeeded, 0 failed.
- 15 admin builds across seeds 1 to 500: all succeeded, zero
  return-path failures.
- build: BUILD SUCCESSFUL, full suite green.
## M75: whitelist-gated private visits, run mementos, retuned bounties

Players can show a physical record, pass a private address and learn from
each other without a browse-only popularity contest. The original plan
proposed a `CallingCard` item for private visiting; the owner rejected that
as annoying for buddy visits and chose whitelist-gated visits instead. No
new item, no revocation epoch, no card custody or copying.

### Whitelist-gated private visits

A new "Visit a Friend" menu entry on the lodestone menu lists rooms whose
owner has whitelisted the clicker, the private counterpart of the public
lobby directory. The two channels share one `VisitService.visit` routing
call, never two parallel admit paths that could disagree. The admit check
differs: the public directory checks `publicListed`, the private directory
checks `RoomWhitelist.isPermitted`. Both revalidate at click time and
re-show the directory with a reason line on a stale click, per
`DIALOGS_SPEC` section 7.

- `RoomWhitelist.roomsPermittedFor(UUID clicker)`: reverse lookup returning
  owner UUIDs whose whitelist admits the clicker.
- `DialogScreens.friendBrowser` / `friendRows` / `friendBrowserDialog`:
  the private directory, mirroring `lobbyBrowser` / `lobbyRows` /
  `lobbyBrowserDialog`. Both delegate to a shared `visitListDialog` builder.
- `DialogRouter.visitFriend` / `browseFriends` / `reshowFriends`: the
  private click handler, mirroring `visitRoom` / `browseLobbies` /
  `reshowLobby`.
- New actions: `ACTION_BROWSE_FRIENDS`, `ACTION_VISIT_FRIEND`.

No room name or UUID enumeration for arbitrary private rooms. The list
only includes online owners who have whitelisted the clicker. Whitelist
editing semantics are unchanged: the whitelist still governs room editing
and container permissions; visit authorization is an additional use of
whitelist membership, not a replacement.

### Run memento

An optional written book memento of a completed run, minted on demand with
`/dungeon memento`. The memento is a vanilla written book the owner can
place on a lectern beside their display. It carries the run's theme, key
band (keystone level and loot tier), affixes and a discovery id. No
progression credit, no auto-delivery, no auto-furnished trophy wall, no
compulsory museum slots.

The server keeps the authoritative run record (the evidence) on every safe
visit in a new `runRecords` sidecar on `DungeonLog`, capped at 20 records
per player. A placed memento loses its components (DISCOVERIES trap 16), so
the record is the only durable proof; the item is a label a player chooses
to put on a run the server already remembers.

- `RunMemento.RunRecord`: the evidence record (discovery id, theme,
  keystone level, loot tier, affixes, timestamp).
- `RunMemento.mint`: builds the written book with title, pages, name and
  lore.
- `RunMemento.isMemento` / `discoveryIdOf`: readback from a stack.
- `DungeonLog.addRunRecord` / `runRecordsOf` / `latestRunRecord`: sidecar
  accessors.
- `/dungeon memento` command: mints from the latest record.

### Bounty retuning

The weekly bounty pool is retuned entirely toward exploration, clearing
and low-omen safe visits. Every entry is solo-achievable. No bounty
requires a party, gambles, banks fuel, or climbs keystone levels.

Removed (non-exploration or not solo-achievable):
- `ECHO_HARVESTER` (fuel banking, routine crafting).
- `HIGH_ROLLER` (gambling, 32 emeralds).
- `KEYSTONE_CLIMBER` (keystone level grind).
- `PACK_HUNTER` (requires a party of 2+).

Added (exploration, clearing, all solo-achievable):
- `EXPLORER` (5): complete any safe visit.
- `TIDY` (3): full clear all spawners on a floor.
- `DEEP_DIVER` (3): reach the second safe-visit depth.

Kept (already exploration or low-omen oriented):
- `CLEAR_HALLS` (15, lowered from 20): clear trial spawners.
- `SPEEDRUNNER` (3): low-omen completions.
- `SPELUNKER` (2): deeper exploration (chosenStep >= 2).

Rewards unchanged: 2 echo shards + 4 emeralds per online member, 1 bonus
shard for the owner. Materials only, no tradable progression credit, no
public ranks, no streak punishment, no escalating mandatory grind.

### Verification

- lobbyBrowserTest: passed (friend rows, friend dialog, action id, empty
  state).
- lodestoneMenuTest: passed (7 overworld options, Visit a Friend at index
  2).
- runMementoTest: passed (RunRecord codec round trip, title, lore,
  nextDiscoveryId uniqueness).
- dungeonLogTest: passed (run-record sidecar add, latest, cap, round trip,
  legacy save).
- bountyTrackerTest: passed (seeded pick, stale reset, sidecar round trip,
  legacy save, unlock gate).
- runGameTest: 61 tests passed.
- dungeonIntegrationTest: 61 tests passed.
- build: BUILD SUCCESSFUL, full suite green.

## M76: publish the operating envelope

An operator knows how many simultaneous floors, previews and visits this
release can sustain and recover from.

### What landed

- Three operator-tunable caps in `config/pocketdungeons.json`:
  `maxConcurrentInstances` (default 32), `maxConcurrentVisits` (default 16),
  `maxConcurrentPreviews` (default 16). A value of `0` disables that cap.
- Caps are enforced before any fuel or catalyst is charged:
  - `RunLifecycle.enter` refuses a new run once the instance cap is reached.
  - `RunLifecycle.previewDoor` refuses a new preview once the preview cap is
    reached (a player switching doors does not count against the cap; their
    own existing preview is reused or purged).
  - `VisitService.createVisitInstance` refuses a new visit copy once the
    visit cap is reached (joining an existing visit or the owner's own live
    room never creates a slot, so those paths are not gated).
- `InstanceRegistry` exposes `liveInstanceCount`, `visitCount` and
  `previewCount` so the caps and the diagnostics read the same state.
- `/dungeon admin diagnostics` prints the operating envelope: instances,
  visits, previews and queued clears against their caps, plus used slots,
  heap, dungeon-dimension force-loaded chunks and entity count. No
  player-facing output, no external telemetry.
- `dungeonLoadTest` Gradle task: a repeatable, headless load test that boots
  the same real dedicated server route as `dungeonIntegrationTest` (so
  `pocketdungeons:void` loads for real) and drives concurrent instances
  through it. `dungeonLoadTestSoak` is the distinct long-soak invocation
  with wider defaults. Profiles are overridable from the command line:
  `-PloadtestInstances=32 -PloadtestCycles=3 -PloadtestSteadyTicks=400`.

### Measured capacity table

Hardware: Intel Core i5-10600K @ 4.10 GHz, 16 GB RAM, Windows 10 Pro.
JVM: Temurin OpenJDK 25.0.4+7 LTS, 4076 MB max heap (Loom default).
Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0.
View distance: server default (10). Content: 61 rooms, 6 anomaly rooms,
11 themes, 11 adventure nodes, 9 affixes, 9 bags, 4 roles, 13 cube recipes.

The load test drives instances via `Instances.adminBuild`, which plans and
stamps the same procedural layout the real `/dungeon` path stamps, against
the same room manifest, force-load and bedrock-envelope code. It bypasses
the M76 instance cap on purpose: the load test measures raw headroom, not
the cap's refusal behaviour. A real `/dungeon` run goes through a lobby and
a door choice, so its per-run world work is a subset of what this measures.

| Instances | Steady tick p50 | p95 | p99 | Gen latency p50 | p95 | p99 | Heap (steady) | Forced chunks |
|-----------|-----------------|-----|-----|------------------|-----|-----|---------------|--------------|
| 8         | 1 ms            | 4   | 8   | 189 ms           | 441 | 441 | 435 MB        | 80           |
| 16        | 1 ms            | 9   | 18  | 209 ms           | 807 | 807 | 372 MB        | 171          |
| 32        | 5 ms            | 9   | 17  | 136 ms           | 341 | 823 | 612 MB        | 357          |

Candidate advertised-cap threshold: p95 below 40 ms and p99 below 50 ms.
At 32 concurrent instances, steady tick p95 = 9 ms and p99 = 17 ms, both
well inside the threshold. The default `maxConcurrentInstances = 32` is
therefore the declared cap for this hardware and JVM. These are
measurement points, not promised capacity: a mixed-mod server's real
ceiling depends on its own hardware, population and other mods.

### Overload behaviour

Above the cap, the server refuses new work before it charges anything:
- A `/dungeon` past the instance cap tells the player "The dungeon is at
  capacity (N concurrent runs). Try again shortly." and logs a warning.
  No keystone is spent.
- A door preview past the preview cap tells the player "Too many door
  previews are open right now. Close one or try again shortly." No
  catalyst is escrowed.
- A visit past the visit cap tells the visitor "Too many rooms are being
  visited right now. Try again shortly." No slot is allocated.

The load test's "Can't keep up" warnings during the build phase are a
harness artifact (one full dungeon stamped per tick), not production
behaviour: a real `/dungeon` stamps a lobby on entry and defers the rest
to a door choice. The steady-state measurements (the rows above) are
taken after the build phase completes, with all instances live and
ticking, and show no overload.

### Lookup and clear: no optimisation warranted

The linear lookups (`dungeonCellLookupAt`, `roomRecordAt`, `instanceAt`)
scan `InstanceRegistry.bySlot.values()`. At 32 entries the steady tick
p95 is 9 ms, so the linear scan is not the bottleneck. The rejected M43
spatial-index approach is not revived. `InstanceWorkQueue.java` is not
added: clears are already globally budgeted through
`InstanceTeardown.processClears` (one shared `clearBlocksPerTick` budget,
floor 1024), and the M63 slot lease is preserved until `finishClear`
releases the slot.

### Remaining version-specific hot spots

- One `sump` room stamp failed on an unlucky seed during the 16-instance
  run with "spanY 2 but no climbable return path from its lower story to
  the upper floor (spec 13.4)". This is a pre-existing M74 content issue
  in that room's lower-story authoring, not an M76 regression: the
  stamper caught it, cleared the partial geometry, and the run continued.
  It is recorded here as a content hot spot to fix in room authoring, not
  in the lifecycle code.
- No 26.2 API surface in this milestone required `UNVERIFIED` recording.
  `level.getChunkSource().getForceLoadedChunks()` and
  `level.getEntitiesOfClass(...)` were used as documented and behaved as
  expected against the 26.2 jar.

### Verification

- runGameTest: 61 tests passed.
- dungeonIntegrationTest: passed (real dedicated server, `pocketdungeons:void`
  loaded and saved).
- packValidationTest: passed.
- dungeonLoadTest: PASS at 8, 16 and 32 instances.
- build: BUILD SUCCESSFUL, full suite green.

## M78: the Endless Mine, bounded in memory

M78 unholds the one deferred "rule-breaking dungeon" item from `ROADMAP.md`
M8 Deferred, conditionally. Its goal was to prove one special dungeon rule
without maintaining an infinite loaded corridor: an unbounded sequence of
floors with no compulsory final floor, where each checkpoint offers continue
or a voluntary cash-out, previous floors are released and can never be
revisited, and only the current floor plus its bounded transition work stay
loaded.

### M77 gate and the owner override

The handoff required stopping if M77 adoption evidence, owner approval, or
repeat-player demand were absent. M77 is not completed: no
`M77-handoff-completed.md` exists, no M77 entry is in this file, and no
pilot-host, release-jar, adoption or retention evidence was found. The owner
explicitly overrode that gate and selected "build M78" anyway. That override
is recorded here so the missing evidence stays visible: M78 is built on an
owner-approved exception, not on demonstrated M77 adoption or repeat-player
demand.

### Did the exception improve repeat play enough to retain?

Not yet determinable. Automated verification proves the Mine is bounded in
memory and that its transition policy is separate from ordinary three-floor
settlement, but it cannot prove repeat-play retention. The two human checks
the handoff names (voluntary cash-out behaviour and long-run constant-residency
evidence) are still pending live sessions. The Mine is retained in the codebase
on the owner's override; whether it stays past the next roadmap decision
depends on those live checks showing repeat play that the ordinary loop alone
does not produce. No automatic sequel milestones follow from M78.

### What changed

- **EndlessMineRules.** A new final class holding the Mine's pure policy
  half: the Mine theme id, `isMine` predicates for a record and a resolved
  recipe plan, `effectiveTheme` (a Mine run or Mine recipe plan forces the
  Mine theme on every floor, so the recipe being cleared after the first
  commit does not drop the Mine look on later floors), `shouldForceSafeStaging`
  (the Mine never forces a safe staging room, so every checkpoint offers
  continue doors and the cash-out stays voluntary), `completionLootTier` (the
  Mine escalates the loot tier one step per `floorsPerSafeVisit` floors, capped
  at tier 3, so the materials get better but never leave the table the
  ordinary loop draws from), `cashOutDepth` (the floor index reached, for the
  displayable Mine record), and the commitment-surface strings. No Minecraft
  state lives here; the lifecycle hooks read these helpers so the Mine's
  transition policy stays separate from ordinary three-floor settlement.
- **Recipe flag.** `RecipeEffects` gains an `endless_mine` boolean (a run
  mode, not a room-shaping operation); `CubeRecipeMeta` parses
  `effects.endless_mine`; `RunRecipePlan` accumulates it in `resolve` and
  carries it as a field; `hasAnyOperation` and `hasEffects` count it so a
  Mine-only recipe is not rejected for bending nothing.
- **InstanceRecord.endlessMine.** Set on the first floor's commit when the
  resolved recipe plan opens the Mine, and held for the rest of the run so the
  Mine transition policy stays active after the recipe tags are cleared. Reset
  alongside `theme` when the run ends (returnToSafe, fallback homecoming,
  resetToLobby). The record stays in memory for the server process lifetime,
  the same as every other InstanceRecord, so the flag needs no NBT sidecar;
  M63 reconnect recovery finds it intact.
- **Instances hooks.** `previewDoor` and `commitDoor` resolve the effective
  theme through `EndlessMineRules.effectiveTheme` and stamp at it, so a Mine
  floor uses the Mine theme on every floor. `commitDoor` sets
  `record.endlessMine` on the first Mine commit and publishes the Mine start
  message to the owner, the commitment surface that names the risk (no final
  floor, previous floors close) and the reward (voluntary cash-out) without
  explaining the spatial room movement.
- **RunLifecycle hooks.** `advanceFloor` asks `shouldForceSafeStaging` (the
  Mine never forces a safe visit) and `completionLootTier` (the Mine
  escalates). `completeRun` publishes the Mine checkpoint message (depth and
  tier) at the commitment surface. `returnToSafe` relaxes its `safeStaging`
  gate to accept a Mine cash-out, so the Mine reuses the M65 silent physical
  exit and the ordinary `settleSafeVisit` settlement; the Mine's keystone
  progression therefore keys off the same omen finish table as an ordinary
  safe visit, so a deep cash-out (large omen sum, high band) yields no level
  change and the Mine cannot raise the power ceiling. `settleSafeVisit`
  records `cashOutDepth` on the run record. A new `cashOutMine` entry method
  validates the Mine context and delegates to `returnToSafe`.
- **/dungeon cashout.** A new command, owner-only, that calls
  `cashOutMine`. The Mine never forces a safe staging room, so this is the
  voluntary exit between floors.
- **RunMemento.RunRecord.depth.** An additive optional codec field (default
  0) so older saved records decode unchanged. A positive depth means a Mine
  cash-out; the memento's pages and lore render "Mine depth: N". It grants no
  power. A legacy 6-arg constructor keeps the existing test passing.
- **Data.** `dungeon_theme/endless_mine.json` (reuses the deepslate processor
  and room theme for the mining look), `dungeon_adventure/endless_mine.json`
  (a descent node so the Mine theme is a valid graph node), and
  `cube_recipe/endless_mine.json` (catalyst `minecraft:raw_iron`, `min_level`
  5, `effects.endless_mine`). The deepslate adventure node gains a weight-1
  transition to `endless_mine` so the bundled pack validates clean (the Mine
  node is reachable, not a dead branch). The Mine recipe is an optional
  bundled recipe, not added to `RecipeIds.BUILT_IN_ORDER`, so a third-party
  pack is not forced to ship it.
- **endlessMineRulesTest.** A headless `JavaExec` test covering the policy
  helpers, the commitment-surface strings (including the no-double-hyphen
  rule), and the recipe flag's accumulation through `RunRecipePlan.resolve`
  via a `CubeRecipeManifest.create` fixture. Registered in `build.gradle.kts`
  and wired so `test` depends on it.

### Why the Mine is bounded in memory

The Mine reuses the ordinary loop's per-floor release: `Instances.commitDoor`
already clears the previous floor's cells (via `resetForNextDungeon`) and
releases its force-load tickets before the next floor is stamped. The Mine
only removes the forced safe visit that would otherwise interrupt the chain,
so chaining Mine floors never retains more than the current floor, the
transition cell, and the bounded prepared preview work. Previous floors are
cleared, not merely force-unloaded, so they cannot be revisited.
Current-floor topology and return-path guarantees stay unchanged because the
Mine reuses the same planner and stamper. M76's capacity caps and bounded
teardown still apply unchanged.

### Verification

- endlessMineRulesTest: passed (policy helpers, strings, recipe flag
  accumulation).
- runMementoTest: passed (6-arg legacy constructor and new depth field).
- floorShapeTest: passed.
- graphSolvabilityTest: passed (Pilgrim and tier sweeps clean with the new
  adventure node).
- adventureGraphTest: passed (the deepslate to endless_mine transition
  resolves).
- packValidationTest: passed.
- runGameTest: passed.
- dungeonIntegrationTest: PASS (real dedicated server, 12 themes, 12
  adventure nodes, 14 cube recipes loaded, 0 rejected).
- dungeonLoadTest: PASS (constant-residency at 8 instances; the Mine adds no
  retained corridor because it reuses the ordinary per-floor release).
- build: BUILD SUCCESSFUL, full suite green.

### Pending human checks

- Voluntary cash-out: apply the Mine recipe at the Cube, descend, and confirm
  `/dungeon cashout` between floors returns the party to the room with the
  banked haul and a depth-carrying memento, with no teleport, sound or lore
  on the successful path.
- Long-run constant-residency: chain many Mine floors and confirm only the
  current floor plus the transition cell remain loaded (forced-chunk count
  stays bounded, heap does not grow per floor).
- No new 26.2 API surface was left unconfirmed; M78 reused only existing,
  already-verified API surfaces, so no new UNVERIFIED trap was added to
  `DISCOVERIES.md`.
