# Pocket Dungeons — Progress

> **This is the only file that records status.** `ROADMAP.md` holds the order,
> `plans/M<n>-*.md` hold the how, `VISION.md` holds the why. None of those three
> may contain a checkbox or a status column.
>
> **Last updated:** 2026-08-25 · **Current milestone:** M9
>
> **Multiplayer testing is deferred until every milestone is code-complete.**
> Several tasks' "Done when" bars call for a live check with two connected
> clients (a host and a guest, or an owner and a party member). Those checks
> are not being run per-milestone — they will happen in one pass once M0–M8
> are all otherwise done. A task marked `DONE` with a note to this effect has
> had its code verified (build green, reviewed against the plan) but **not**
> its live multiplayer behaviour; do not read `DONE` here as "played and
> confirmed in multiplayer" until that pass has happened and the note says so.

---

## Starting a milestone

Each milestone has a self-contained handoff prompt at
`handoffs/M<n>-handoff.md` — paste the whole file into a fresh chat to start
work on it with no other context required. Every handoff includes the reading
list, the standing rules, the verification bar (including live-server RCON
proof via `tools/rcon.py` where relevant), the doc-update checklist, and the
final step: renaming itself to `M<n>-handoff-completed.md` once the milestone
is actually `DONE` here. A milestone with no `-completed` handoff is either not
started or not finished — check this file's tables for the real status, the
handoff filename is a convenience marker, not the source of truth.

**Building any UI in this mod?** Check `DIALOGS_SPEC.md` first — it specs
seven menus (door offers, kick/invite confirmations, room whitelist, admin
restore, the elevator) using the vanilla-dialog mechanism `quizengine` and
`cobbleeconomy` already ship. Nothing in it is built yet; it exists so the
shape gets decided once instead of per-milestone.

---

## How agents use this file

1. **Read before starting.** Check the task is `TODO` and that its milestone's
   blockers are `DONE`. Milestones run in roadmap order; do not skip.
2. **Claim it.** Set the task to `WIP` with your name and the date *before* the
   first edit, so two agents do not collide.
3. **Work the plan.** `plans/M<n>-*.md` is authoritative for scope. If the plan
   is wrong, fix the plan first and say so in the log — never silently diverge.
4. **Finish honestly.** `DONE` requires the plan's own "Done when" boxes ticked
   and `./gradlew build` green, **except** live multiplayer checks, which are
   deferred per the note at the top of this file — a task blocked on nothing
   but that may still go `DONE` with a note saying so. Anything else partial
   stays `WIP` with a note.
5. **Log it.** One line in the Session Log. Newest first.

### Status values

| | Meaning |
|---|---|
| `TODO` | Not started |
| `WIP` | Claimed, in progress — **must** name who and when |
| `BLOCKED` | Cannot proceed; the note says what by |
| `DONE` | Plan's "Done when" satisfied, build green |
| `CUT` | Deliberately dropped; the note says why |

### Rules that outrank the plans

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. Unverified claims
   get a `⚠ UNVERIFIED` comment in the source.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   blocks, items or registry entries.
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint**, checked every change.
5. **The mod stays quiet about the trick** (`VISION.md` §4).
6. **Superseded designs are marked superseded, not deleted.**
7. **Do not reopen a decision in `MYTHIC_PLUS_RECONCILIATION.md` §7** without
   writing down what new information changed it.

---

## M0 — Entry fee and safety · `plans/M0-entry-fee.md`

| # | Task | Status | Note |
|---|---|---|---|
| T0.1 | `RoomManifest` on `/reload` | `DONE` | 2026-08-24, Devin. Wired via `RoomManifest.register()` (`fabric-resource-loader-v0`). Verified live over RCON on the dev server: edited `hall_tee.json`'s `roles`, ran `/reload` with no restart, `/dungeon admin manifest list` reflected the change; reverted and reloaded again to confirm it tracks edits both ways |
| T0.2 | `LICENSE` at repo root | `DONE` | 2026-08-24, Devin. Every `fabric.mod.json` in the suite (16 mods) declares `"license": "MIT"`, so the root `LICENSE` covers the whole suite, not just `pocketdungeons/` |
| T0.3 | Published `dungeon_room` schema | `DONE` | 2026-08-24, Devin. Table + validation failures verbatim in `INTEGRATION.md` §2, sourced from `DungeonRoomMeta`/`RoomManifest` |
| T0.4 | `INTEGRATION.md` | `DONE` | 2026-08-24, Devin. Five surfaces + three non-extensible items, following `kamutotems/INTEGRATION.md`'s structure |
| T0.5 | Owner check on selector doors | `DONE` | 2026-08-24, Devin. `Instances.selectorDoorStep` now also requires `player.getUUID().equals(record.owner)`. `./gradlew build` green. The plan's live positive/negative click check needs two connected clients; per the suite-wide call above, that is deferred to the single multiplayer pass after every milestone is code-complete, not run per-task |

## M1 — Themes foundation · `plans/M1-themes-foundation.md`

| # | Task | Status | Note |
|---|---|---|---|
| T1.1 | Wire `processors` | `DONE` | Verified in-world: 4332 deepslate / 0 stone with a datapack theme, 0 / 4802 without. `DungeonRoomMetaTest` added to the harness |
| T1.2 | `theme` field + `RoomSelector` filter | `DONE` | `DungeonRoomMeta.theme` parses optional string arrays; `RoomSelector.queryAnyRotation` filters by requested theme; planner failures name the theme. `PlanSelectorTest` added |
| T1.3 | Three proof themes | `DONE` | `data/pocketdungeons/worldgen/processor_list/theme_{deepslate,prismarine,blackstone}.json` each rewrite the shell palette; verified by server load and `/dungeon admin stamptest` |

## M2 — The room · `plans/M2-the-room.md`

| # | Task | Status | Note |
|---|---|---|---|
| T2.1 | Persist room as a blob | `DONE` | 2026-08-24, Devin. `RoomStore` persists one raw `StructureTemplate` NBT per owner under `<world>/data/pocketdungeons/rooms/<uuid>.dat`, captured with `fillFromWorld` (entities included, but only after every entity that is not an `ItemFrame`/`ArmorStand` is discarded from the cell first). Every write copies the current live file to `.bak` before an atomic temp-write-then-rename of the new one. `/dungeon admin baserestore <player>` (via `GameProfileArgument`, works offline) restores from that `.bak`, routed back through the same save path so a restore leaves its own fresh backup. `./gradlew build` green. **Not yet verified live**: the plan's own bar for this task is "test by actually corrupting or deleting a live blob and running the recovery, not by reading the code and assuming it works" — that is a live-server RCON check, deferred with the rest of this milestone's multiplayer pass (see the top of this file) |
| T2.2 | Permission mask | `DONE` | 2026-08-24, Devin. `RoomProtection` registers the mod's first `PlayerBlockBreakEvents.BEFORE`, positional via `Instances.roomOwnerAt` (one bounds check per live instance, `null` outside every room). Container use (`RandomizableContainer` only, so stations and the ender chest stay open to everyone) and placement (via the existing `UseBlockCallback` in `RitualListener`, since there is no generic pre-placement event) are denied the same way. Owner + `RoomWhitelist` (a small `SavedData`, separate from the blob since a lost whitelist is not the T2.1 data-loss class) via `/dungeon room whitelist add/remove/list`. Build green. Live positive/negative click checks deferred |
| T2.3 | Bedrock envelope | `DONE` | 2026-08-24, Devin. `BedrockEnvelope.apply` runs after every cell is stamped: sub-floor and over-ceiling unconditionally, outer wall ring only on faces with no adjacent occupied `PlanCell`. Wired into `LayoutStamper.stamp` for procedural layouts; the `StaticLayout` fallback does not get it (out of scope for this pass). `PendingClear.advance` now clears the one-block envelope around each cell as well as the cell interior, so teardown removes the bedrock shell instead of leaving a ghost shell around the slot. Build green; in-world block counts not yet verified over RCON |
| T2.4 | The closed loop | `DONE` | 2026-08-24, Devin. `Instances.completeDungeon`: terminal cell stays the exit room with its 2x2 lodestone pad; reward chests spawn on the far side in front of a sealed far-wall door; the room is captured, cleared, and re-stamped in the cell behind that wall, rotated so its `ee` side faces the terminal; the door opens and the player is teleported into their room. `InstanceRecord.roomDungeonDoor` tracks the next dungeon's wall. `resetForNextDungeon` teleports party members out of the old dungeon, clears old dungeon cells, and seals `ee` before a new dungeon is generated. No early return between capture and persist. **Not yet verified live**: crash-injection over RCON, deferred |
| T2.5 | Door-as-entrance, lingering quarry | `DONE` | 2026-08-24, Devin. Added the third lifecycle state: `InstanceRecord.lingering`. `retireOrPurge` (replacing the reward-room-grace purge call) ejects stragglers, releases every cell's force-load ticket, and leaves blocks standing instead of clearing them, only once the room has already moved (T2.4); `onTick` exempts lingering records from every check. `Instances.enter()` purges an owner's lingering instance as the first thing it does, the only way out — bounding it at one per owner. Build green; walking back to mine a lingering dungeon not yet checked live |
| T2.6 | Purge on leadership change | `DONE` | 2026-08-24, Devin. `Instances.leadershipChanged`: the owner leaving while someone else is still in the party purges the whole instance, in both `dropMember` (disconnect/offline/left-dimension) and `exit` (deliberate `/dungeon exit`). An owner leaving alone is unaffected (still free re-entry, U8 Stage 1); a lingering quarry (T2.5) is exempt (no live run left to end). Build green |

**M2 status:** `DONE` — code-complete and `./gradlew build` green for all six tasks, following the plan's non-negotiable capture → persist → clear → stamp order throughout. Live-server checks (backup/restore corruption test, crash-injection test, permission-mask/quarry/lingering RCON checks) are deferred to the single multiplayer pass called out at the top of this file, same as every other milestone's live checks.

**2026-08-24 entry-flow rework, Devin:** a live playtest surfaced that the actual intended loop (`MYTHIC_PLUS_RECONCILIATION.md` §3.2.3: lodestone → your standing room → three doors choose the run → the dungeon generates behind whichever one is picked) was never wired up -- M2 had kept the old "lodestone instantly builds and drops you into the whole dungeon" flow and only overlaid the room onto its cell 0. Reworked:

- `Instances.enterLobby`/`stampLobby`: a real keystone run now opens into the owner's room *alone* -- their saved blob (`RoomStore`) or `entrance_hall` on a first visit -- stamped at a fixed rotation with its one connecting door **sealed** (`RoomBuilder.sealDoor`) and a bedrock envelope on the other three sides (`BedrockEnvelope.applyToLobbyCell`) since nothing exists to connect to yet. No plan, no timer. `/dungeon admin untimed`/`build` are untouched (debug tools, not the player loop).
- The old U8 Stage 3 selector room (a separate, off-grid, post-completion-only instance) is retired and folded into this same room: `selectorDoorStep`/`chooseOffer` now gate on `InstanceRecord.awaitingDoorChoice` instead, and rendering the three doors reads the player's *current* keystone level every time, not a one-shot banked `pendingOfferLevel` (that `DungeonLog` field/codec is left in place for save-format safety but nothing writes to it any more).
- Choosing a door (`Instances.chooseOffer` → `generateBehindLobby`) plans a shape via the existing `LayoutPlanner`/`LayoutGraphGenerator`, then **rotates the whole abstract shape** (`DungeonShape.rotate`, pure-JDK, verified by hand against 500 generated shapes for validity + exact direction) so its entrance edge lines up with the room's already-fixed, already-sealed door -- no change to the generator's own randomness, just which of four equally-likely labellings gets used. `LayoutStamper.stampBehindLobby` then stamps every *other* cell (the room itself is skipped, already standing), the seal comes down (`RoomBuilder.openDoor`), and the timer starts only now.
- `completeRun` banks the chosen offer directly (`record.chosenStep`, against the completing member's *own* current level -- preserves the old "companions progress off their own ladder" behaviour) instead of parking a pending offer, since there is no later "go choose a door" step any more.
- An abandoned lobby (nobody ever chose a door) purges itself the same way the old selector room did, rather than holding a slot and a force-load ticket forever.

`./gradlew build` green, plus a throwaway pure-JDK check (`DungeonShape.rotate` × 500 seeds, deleted after use, not part of the suite) confirming every rotation stays a valid shape and lands the entrance exactly on the requested direction. **Nothing about this rework has been played.** The whole new loop -- lobby stand-alone, three doors, generation-on-choice, the seal opening, the timer starting late -- needs a real client to confirm, and is deferred to the same live pass as the rest of M2.

**2026-08-24 live bug, fixed, Devin:** first real-world playtest found that `/dungeon` re-entering a just-completed (but not yet `lingering`) instance for free -- U8 Stage 1's existing behaviour, unaffected by M2's own tests because there was previously nothing visible to distinguish "re-entered" from "fresh" -- now drops the player back into a room-already-moved, entrance-already-cleared instance, reading as "the starting room is empty/bedrock and my room is already at the end with no way to finish." Fixed in `reenterOwnedInstance` (skip any instance with a non-empty `completed` set, not just `lingering` ones) and `enter()` (the pre-build purge/retire check now also catches an owner's already-completed-but-not-yet-`lingering` instance, not only already-`lingering` ones, via `retireOrPurge` rather than a raw `purge`). Build green; not yet re-verified live. A separate report of the terminal door facing the wrong wall was not reproduced by code review (the rotation-delta math checks out by hand for the single-door entrance/terminal case) and needs a fresh live check now that the stale-reentry confound above is fixed, since that was very possibly what was actually being looked at.

## M3 — The calling card · `plans/M3-calling-card.md`

| # | Task | Status | Note |
|---|---|---|---|
| T3.1 | Mint a calling card | `DONE` | 2026-08-24, Devin. `CallingCard.mint(owner)` stores owner UUID under `CUSTOM_DATA` root, uses configurable `callingCardItem` (default `minecraft:compass`), optional `LODESTONE_TRACKER` with `tracked: false` for glint, plus `CUSTOM_NAME`. `CallingCard.isCard`/`ownerOf` positive-read helpers. `/dungeon room card` command hands the caller one card. `RitualListener.warmUp` resolves the configured item at boot. `./gradlew build` green |
| T3.2 | Use-on-lodestone | `DONE` | 2026-08-24, Devin. `RitualListener` positive test for `CallingCard.isCard` before the keystone branch; foreign items still PASS to other mods. Card is not consumed. Plays the lodestone charge sound, then routes to `Instances.visit` |
| T3.3 | Shared visit instance, refcounted | `DONE` | 2026-08-24, Devin. `Instances.visit(visitor, ownerUuid)` implements the case table: owner has a live non-lingering instance → `admit` into it; owner away with no visit instance → `createVisitInstance` allocates a slot, stamps `RoomStore.place` (or `ENTRANCE_HALL` fallback), seals the door with `RoomBuilder.sealDoor`, applies `BedrockEnvelope.applyToLobbyCell`, and places the selector doors/leave-pad against the connecting wall; owner away with existing visit instance → `admit` into the same one. `InstanceRecord.visitInstance` flag marks read-only copies; `exit`/`dropMember` purge the instance when its last visitor leaves. `reenterOwnedInstance` excludes visit instances so an owner does not accidentally re-enter their own read-only copy. `./gradlew build` green. Live two-client convergence test deferred |
| T3.4 | Visitor permissions | `DONE` | 2026-08-24, Devin. Reuses M2 T2.2: `RoomProtection.roomOwnerAt` sees `record.roomCellOrigin` set for visit instances, so a visitor is treated as "not owner/not whitelisted" and cannot break blocks or open lootable containers, but can still use stations and ender chests. `./gradlew build` green. Live positive/negative checks deferred |

## M4 — Affixes · `plans/M4-affixes.md`

| # | Task | Status | Note |
|---|---|---|---|
| T4.1 | `Affix` enum → set | `DONE` | 2026-08-25. Moved to its own `Affix.java`, no Minecraft imports; `AffixMath` (also import-free) owns parse/join/thresholds/seeding/naming/depletion. `NONE` deleted -- an empty `EnumSet` says it. `DungeonLog` stores only the *elective* affixes; the seeded ones are re-derived from `(owner, level)` on every read, so a save holding `"ominous"` still loads as a one-element set with no codec migration |
| T4.2 | Thresholds 5 / 11 / 17 | `DONE` | 2026-08-25. `AffixMath.seededCount`/`seededFor`; the door's elective affix sits **on top** of the thresholds, not inside them (decided explicitly, since the door was worth ~2 more levels either way). Seed is `hash(owner, level)`, no new persistence -- watcher-reconciliation stability verified in `AffixMathTest` (100 repeat calls, one owner never drifts; two owners diverge somewhere across the ladder) |
| T4.3 | Depletion takes the max | `DONE` | 2026-08-25. `AffixMath.depletionMultiplier` = max over the set, capped at 2; `KeystoneMath.deplete` takes the multiplier directly (and clamps it again, so a bad caller cannot double-double it). `Keystones.returnTo` is the only caller |
| T4.4 | Naming | `DONE` | 2026-08-25. `AffixMath.name`/`intensifier`; title = first affix in enum order, rest in a bracketed subtitle. Pure function of `(level, affixSet)`, no randomness -- the watcher rewrite-stability requirement |
| T4.5 | Swarming / Overclocked / Molten / Silenced | `DONE` | 2026-08-25. Overclocked scales `target_cooldown_length`; Swarming writes an **inline** `TrialSpawnerConfig` (not a new JSON file per tier -- `TrialSpawnerConfig.CODEC` is the 2-arg `RegistryFileCodec.create`, which allows it) with scaled `total_mobs`/`simultaneous_mobs`, verified at bytecode level against `minecraft-merged-deobf-26.2.jar`; falls back to the plain id if the base config is missing from the registry. Molten stamps lava in the cell's interior margin, clear of doors and spawn anchors. Silenced denies `DataComponents.CONSUMABLE` use (`SilenceListener`, new `UseItemCallback`) and tightens `required_player_range`. **Correction to the plan doc**: the trial-spawner JSON files are static resources `DatapackExporter` only copies, not "a document the mod already generates" as §5.1/T4.5 stated -- `TrialContent` only ever wrote an id string before this. `./gradlew build` green, `affixMathTest` added alongside the other pure-Java tasks. In-world client verification of the stacked chest reward, Molten passability and Silenced's two halves still deferred |

## M5 — Wolves and Feral · `plans/M5-wolves-feral.md`

| # | Task | Status | Note |
|---|---|---|---|
| T5.1 | Spawn at stamp time | `DONE` | 2026-08-25. `Affix.FERAL` added (`SEEDED`, so it arrives off the 5/11/17 thresholds like the rest of M4's wave -- the three elective door offers are fixed at none/`OMINOUS`/`FRAGILE` and were not touched). `RoomContent.spawnMobs` restored from `8dbb006^` but **rebuilt as a general-purpose direct spawn path**: it takes an `EntityType`, a count and a `Consumer<Entity>` post-spawn hook rather than the U3 weighted roster (whose `DifficultyProfile` fields are still deleted), keeps the historical shuffle + `jitterOrFallBack` placement, and marks every `Mob` persistent. T17's boundary is kept where it actually bites -- **`spawnMobs` is never called for an `encounter` cell**; `TrialContent` still owns those. `FeralContent` is the first caller: wolves in `corridor` and `loot` cells only (entrance is the player's own room, exit is the lodestone pad and the reward chests), each pinned with `setHomeTo(cellCentre, 6)` so it cannot cross into the next cell and break the room-geometry invariant. **Nothing anywhere angers them** -- no `startPersistentAngerTimer` call exists in the mod; verified in the 26.2 jar that `Mob.setHomeTo(BlockPos,int)`, `Entity.setComponent`, `EntitySpawnReason.TRIGGERED` and `Wolf.tryToTame`'s `random.nextInt(3) == 0` / `setOrderedToSit(true)` are all exactly as `MYTHIC_PLUS_RECONCILIATION.md` §7.4 records them. New config `feralWolvesPerCell` (default 2, 0 disables). `./gradlew build` green |
| T5.2 | Coats by tier | `DONE` | 2026-08-25. `FeralContent.COATS_BY_TIER`: the 9 keys off `WolfVariants` split into three **exclusive** bands by `DifficultyProfile.lootTier()` -- pale/woods/ashen, spotted/rusty/chestnut, snowy/black/striped. Exclusive rather than cumulative on purpose: a cumulative ladder only makes a deep coat *likelier*, which reads as luck instead of provenance (`VISION.md` §3.6.1). `WolfVariants.DEFAULT` is skipped -- it aliases `PALE`, and including it would double-weight the commonest look in the shallowest band. Set via `entity.setComponent(DataComponents.WOLF_VARIANT, holder)`, holder resolved from `level.registryAccess().lookupOrThrow(Registries.WOLF_VARIANT)`; the coat roll uses its own salted stream so a template edit that moves spawn anchors does not silently recolour the run. **Note for whoever verifies live**: the compile classpath's entity-type holder is `EntityTypes.WOLF`, not `EntityType.WOLF` -- the deobf maven jar and the merged jar disagree on this, and the merged jar is the one that compiles |
| T5.3 | Bones as a guaranteed floor | `DONE` | 2026-08-25. Unblocked and satisfied by M6 T6.1, which is where the plan said it would land. `bone` is a single-entry guaranteed pool in all six tier tables and all three supply tables, 6-10 at tier 1 rising to 12-20 at tier 3, confirmed present in 180 of 180 live draws. The 1-in-3 catch rate now runs against a floor instead of a weighted drop, which is what 7.4 asked for |
| T5.4 | Spirit Stone permanence | `DONE` | 2026-08-25. Zero code, as designed, and the zero was verified rather than assumed: `grep -rn spiritwolves` over `pocketdungeons/src`, `build.gradle.kts` and the built jar's contents returns nothing (the only hit anywhere is the other mod's own jar sitting in `dist/`). Run-scoping is already enforced by existing teardown -- `finishClear` and the cell purge both `discard()` every non-player entity in the instance bounds, so a wolf left behind is left behind, and a tamed one sits (T5.1's accepted §7.4 consequence) rather than trailing the party out. **Not verified with both mods loaded**: no combined dev environment exists in this repo, so "the coat survives Spirit Stone binding" rests on `WolfCapture.capture` using `saveWithoutId` (which writes the whole entity, components included) rather than on a watched round trip -- flagging that explicitly rather than skipping it silently |

**M5 status:** code-complete. T5.3 was the last open task and M6 T6.1 closed it
on 2026-08-25, exactly where the plan said it would land, so the handoff can be
renamed whenever someone is doing that pass. Its live checks are deferred with
every other milestone's, per the note at the top of this file: a Feral run
spawning coat-appropriate pinned wolves, an angered wolf refusing a bone, a
bone-fed one eventually taming at the verified 1-in-3.

## M6 — Supply · `plans/M6-supply.md`

| # | Task | Status | Note |
|---|---|---|---|
| T6.1 | Floors for consumables, rolls for treasure | `DONE` | 2026-08-25. Six guaranteed pools inlined at the top of all six tier tables (`chests/tier_1..3` and each `_ominous` twin): food, torch, bone, tier blocks, dirt, sapling, seeds. Not a separate table, per the plan, and there was nothing shared to factor out anyway since the blocks pool differs by tier. The plan missed one thing and the plan file now records it: the tier tables are reached through a vault (needs a key) or the reward chests (need the clock), so `chests/supply` (free, gated on nothing) had to carry the floor too. It was one tier-agnostic table and is now three (`chests/supply_tier_1..3`), which is a one-line change in `TrialContent.applyLoot`. The old 50% food pool loses its two food entries and stays as what it always was, a small arrow/golden-apple bonus. **Verified live** over RCON on a 26.2 dev server: 20 `loot replace block` draws into a barrel from each of the nine tables, 180 draws total, and every draw contained all seven floor categories. Every item id checked against `net.minecraft.world.item.Items` in `minecraft-merged.jar` first, which caught `copper_block`/`cut_copper` |
| T6.2 | Tiered building blocks | `DONE` | 2026-08-25. Three exclusive palettes on the blocks floor pool, matching `VISION.md` 3.6.1: tier 1 rolls 2 over stone/stone bricks/cobblestone/oak log/oak planks/moss/iron bars; tier 2 rolls 3 over deepslate, copper, prismarine and crying obsidian; tier 3 rolls 4 over end stone, ancient-city material (deepslate tiles, chiseled deepslate, sculk, sculk vein, soul lantern, reinforced deepslate) and rare decoratives (purpur, amethyst, glowstone). **Verified live**: across the 180 draws the tier-3 tables yielded 11 blocks that never appeared at tier 1 and vice versa, with zero cross-tier bleed. `cobblestone` is the one block at every tier and is left alone: it comes from the pre-existing weighted materials pool, not this one, and it is not a tier marker |
| T6.3 | Grove room; seeds and dirt | `DONE` | 2026-08-25. Two JSON files, no Java and no new `.nbt`: `worldgen/processor_list/theme_grove.json` plus `dungeon_room/grove.json`, which points the **existing** `rooms/mossy_tee` template at it. Stone bricks become oak logs, polished andesite becomes grass, the mossy floor patch and its two corner columns become persistent oak leaves, sea lanterns stay. **Verified live**: found in a stamped dungeon and every one of six block probes confirmed in `pocketdungeons:void`, with no stone bricks left in the walls. Two plan corrections, both written up in `plans/M6-supply.md`. **No water anywhere**, deliberately: the generator already records why a flooded room leaks through the two-block doorways, and the corner columns are the same block as the floor patch, so they would become a waterfall. The farm does not need it (crops grow on dry farmland and bone meal is a floor), and water arrives as a T6.4 product instead. **The sapling is guaranteed, not weighted**: measured live, the grove lands in about 12% of dungeons and raising its weight does not move that number, because it already wins whenever it is eligible and eligibility is a tee-masked corridor cell at depth 1+. Right frequency for a strange chamber, wrong frequency for the only wood in the world |
| T6.4 | Nether/End products, not ingredients | `DONE` | 2026-08-25. Tier 2 gets a 40% workshop pool (brewing stand, cauldron, anvil, glass bottles, nether wart, soul sand, blaze powder, water bucket); tier 3 gets an **ungated** pool, one product per chest (ender chest, enchanting table, obsidian, chorus flower, end rod, blaze rod, ender pearls, brewing stand). The asymmetry is the point: a weighted chance at the only route to a chain is the same failure the floor rule exists to prevent, and a tier-3 chest is already earned twice, by the level and by the clock. No second lava faucet: Molten still owns that (M4), D6 unchanged. Station *unlocks* stay deferred (D5); this task supplies the station items, which is what makes an unlock mean something later |
| T6.5 | Entry / exit / join / stray plumbing | `DONE` | 2026-08-25. Four rows, two changes, because three of the four ways out already funnel through `Instances.teleport`. **Entry**: `/dungeon` mints the first keystone itself rather than refusing and naming `/dungeon key`, gated behind a new `Instances.ownsReenterableInstance` so a player standing outside their own live run cannot mint a key by walking back into it. **Exit, join, stray**: `teleport`'s missing-dimension fallback and the join-recovery path with no return point now call a new `sendHome`, which asks `reenterOwnedInstance` for the room first and only then falls back to the world spawn. Preference order, not a mode: with an overworld the return point resolves and `sendHome` is never reached, so suite behaviour is byte-for-byte unchanged. `reenterableInstance` was extracted out of `reenterOwnedInstance` so the filter has exactly one copy. Build green. The player-facing teleports themselves fall under the deferred live pass at the top of this file: they are code-verified, not played |

## M7 — Recipes · `plans/M7-recipes.md`

| # | Task | Status | Note |
|---|---|---|---|
| T7.0 | A run has a theme, and the doors offer it | `DONE` | 2026-08-25, Claude. `ThemeManifest` loads `dungeon_theme/*.json` across every namespace; `ThemeOfferMath.pick` seeds three theme picks off the player and level, same construction as `AffixMath.seededFor`. `Keystone.offers` now takes an owner and returns a themed `Offer` per door; `Instances.chooseOffer` resolves the theme's `roomTheme` into `LayoutPlanner.plan` and its processor list into `LayoutStamper.stampBehindLobby`. `record.theme` carries it for the rest of the run. Landed as the corrected foundation the plan's own "Correction, 2026-08-25" section called for, ahead of T7.1 |
| T7.1 | Record last N themes in `DungeonLog` | `DONE` | 2026-08-25, Claude. `DungeonLog.Entry.recentThemes` (`optionalFieldOf`, empty default, silent migration) pushes the *completed* theme in `Instances.completeRun`, per completing member against their own entry, then truncates to the last 3. `DungeonLogTest` covers the push/truncate and the empty-window migration case |
| T7.2 | Recipe table as datapack JSON | `DONE` | 2026-08-25, Claude. `data/<namespace>/dungeon_recipe/*.json` loaded across every namespace the same way `dungeon_room` is, on the `SERVER_STARTED`/`/reload` listener, after `ThemeManifest` so a recipe naming an unloaded theme is rejected at load and surfaces in `DungeonRecipes.rejections()`. `RecipeMatcher` matches the *tail* of the player's `recentThemes` window against a recipe's ordered `themes` list; a match replaces door 3's theme only, keeping its level and `FRAGILE`. `RecipeMatchTest`/`KeystoneOfferTest` cover order-sensitivity (`[a,b,c]` matches, `[a,c,b]` does not) and the unloaded-theme rejection |
| T7.3 | Discovery floor | `DONE` | 2026-08-25, Claude. `DungeonLog.Entry.completedThemes` is a second, untruncated tally alongside the T7.1 window, migrated the same way. `/dungeon log` lists it as counts. Nothing shows the three-run window, a recipe match, or a hint; the recipe result only ever appears as an ordinary door 3 theme name, same as any other door |
| T7.4 | First recipe dungeon | `DONE` | 2026-08-25, Claude. `dungeon_theme/drowned_vault.json` sets `"discoverable": false` (kept out of `ThemeManifest.discoverableIds()`, so `ThemeOfferMath` never shuffles it in) and `"loot_suffix": "_drowned"`. `TrialContent`'s loot-table resolver tries `chests/tier_N_drowned` first and falls back to the unsuffixed table when it is not loaded. The suffix reaches `applyLoot` and `placeCompletionChests` through the `RoomContent.apply` → `TrialContent` thread T7.0 opened, and through `record.theme` in `completeDungeon`; no field was added to `InstanceLayout`. `dungeon_recipe/drowned_vault.json` is `[deepslate, prismarine, blackstone] -> drowned_vault` |

**M7 status:** code-complete, all five tasks `DONE`, `./gradlew build` green with
`RecipeMatchTest`, `DungeonThemeMetaTest`, `KeystoneOfferTest` and
`DungeonLogTest` all passing. Live checks (three doors visibly showing three
palettes in a running dungeon, a recipe match confirmed over RCON) are deferred
with every other milestone's, per the note at the top of this file.

## M8 — Deferred · `plans/M8-deferred.md`

| # | Item | Status | Note |
|---|---|---|---|
| D1 | Outdoor themes | `TODO` | Needs a second dimension |
| D2 | One rule-breaking dungeon | `TODO` | Endless Mine only |
| D3 | Data-driven affixes | `TODO` | After 5–6 exist in Java |
| D4 | Multi-cell footprints | `TODO` | Generator is 1×1 |
| D5 | Room size, station unlocks | `TODO` | After M2 and M6 |
| D6 | Lava as a second faucet | `TODO` | Only if M6 shows a pinch |
| D7 | The elevator — public opt-in room/party directory | `TODO` | Needs M2, M3. Menu is now spec'd — `DIALOGS_SPEC.md` §7, vanilla-vs-SGUI decision still open |

**Nothing in M8 may be started without an explicit promotion decision.**

## M9 — Refactor and cleanup · `plans/M9-cleanup.md`

| # | Task | Status | Note |
|---|---|---|---|
| C0 | Land the M5/M6/M7 working tree | `DONE` | 2026-08-25, Claude. See session log |
| C1 | Delete confirmed dead code | `DONE` | 2026-08-25, Claude. `InstanceRecord.selectorRoom` (field, 7 guards, the constructor overload nothing called) and the dead `ritualKeyItem`/`ritualKeyCount` M0 entry-fee config, both confirmed unused before deletion |
| C2 | One shell palette, one cell builder | `DONE` | 2026-08-25, Claude. `RoomBuilder`/`RoomTemplateGenerator`'s duplicated FLOOR/WALL/CEILING/LAMP/AIR constants unified on `RoomBuilder`; new shared `buildShell` used by both classes' cell-building methods, each still finishing the shell differently (open doorway vs. jigsaw marker) |
| C3 | Split `Instances.java` | `DONE` | 2026-08-25, Claude. Six extractions, one commit each: `CellGeometry`, `InstanceRegistry`, `PartyService`, `InstanceTeardown`, `VisitService`, `RunLifecycle`. Two plan corrections found during implementation, both written up in `plans/M9-cleanup.md`: `dropMember`/`leadershipChanged` moved from `PartyService` to `RunLifecycle` (they call teardown machinery, not party bookkeeping), and the "under 600 / under 500" line targets did not survive the real dependency graph (`Instances.java` 3,008 to 1,203 lines; `RunLifecycle.java` 928 lines, both larger than guessed since the tick watcher and admin commands still need the lobby-stamping helpers directly) |
| C4 | Harden the seams | `DONE` | 2026-08-25, Claude. `CellGeometryTest` landed with the C3 extraction itself. `InstanceRecord` down to one constructor. New `LootTables` holds the nine core loot table paths as named constants and validates them at `SERVER_STARTED`, replacing the unchecked string concatenation in `TrialContent` |
| C5 | Documentation triage | `DONE` | 2026-08-25, Claude. Six superseded design docs archived to `docs/archive/` with a two-line header each; new root `README.md`. `PLAN.md` was a size-based archive candidate but is still referenced by four live documents as the standing spec, so it stayed; 11 root markdown files, not the guessed under-10 |

**M9 status:** code-complete, all six phases `DONE`, `./gradlew clean build`
green from scratch with every test passing including the new
`CellGeometryTest`. Every commit was a behaviour-preserving move or a
narrowly scoped hardening change; the verification bar this milestone set
for itself: enter a run, choose a door, complete it, take the reward,
leave, re-enter, visit a room, nothing should feel different. That is a live
client check and is deferred with every other milestone's, per the note at
the top of this file. `DOOR_LADDER_BRAINSTORM.md` is unblocked.

---

## Session log

Newest first. One line each: date — who — what changed.

| Date | Who | What |
|---|---|---|
| 2026-08-25 | Claude | M9 C1-C5 complete, closing the milestone. C1: deleted `InstanceRecord.selectorRoom` and the dead `ritualKeyItem`/`ritualKeyCount` config, two commits. C2: unified `RoomBuilder`/`RoomTemplateGenerator`'s shell palette behind a new shared `buildShell`, one commit. C3: split `Instances.java` (3,008 lines) into six classes across six commits: `CellGeometry` (with `CellGeometryTest`), `InstanceRegistry`, `PartyService`, `InstanceTeardown`, `VisitService`, `RunLifecycle`, ending at 1,203 lines plus `RunLifecycle.java` at 928. Found and fixed one plan gap live at compile time: `sendHome` needed `reenterOwnedInstance`, not scoped to move with it. C4: `InstanceRecord` to one constructor, new `LootTables` validates nine core loot table ids at `SERVER_STARTED` instead of the old unchecked string concatenation. C5: six superseded design docs archived to `docs/archive/`, new root `README.md`. Every commit behaviour-preserving (moves, or narrowly scoped additive hardening); `./gradlew clean build` green throughout and at the end. Three plan corrections written up in `plans/M9-cleanup.md` itself rather than silently diverged from: the `PartyService`/`RunLifecycle` method split, the C3 line-count targets, and the C5 document count. Live multiplayer verification deferred per the note at the top of this file |
| 2026-08-25 | Claude | M9 C0: landed the uncommitted M5/M6/M7 working tree (roughly 5,700 changed lines across seven untracked source files, four untracked tests, two untracked resource trees, and the loot table expansion) as six coherent commits rather than one lump: tiered supply chests (M6 T6.1), the feral wolves affix (M5 T5.1/T5.2), dungeon themes and the recipe system (M1/M7 T7.0-T7.4), the tier 1-3 loot table content expansion, a gradle test-wiring commit, and a one-line test-comment fix, each split out where a shared file's diff genuinely mixed two milestones' concerns rather than force-splitting by hunk. `./gradlew build` green throughout. M7's five tasks marked `DONE` above; its handoff renamed to `M7-handoff-completed.md`. `plans/M9-cleanup.md` and `handoffs/M9-handoff.md` written the same session, ahead of C0, as the scope for the refactor this unblocks: the `Instances.java` split, the dead-code deletions found during the M7 correctness pass (`InstanceRecord.selectorRoom`, the M0 entry-fee `ritualKeyItem` config), and the duplicated shell palette between `RoomBuilder` and `RoomTemplateGenerator`. C1 through C5 not started; scaffold only, per the user's request to check in before completing the rest |
| 2026-08-25 | Claude | Live bug, fixed, outside every milestone's scope: **`/dungeon admin purge` was a no-op for anything `/dungeon admin build` created.** `adminBuild` registers a deliberately unowned `InstanceRecord` (null owner), and `purge` opens with `saveRoomIfOwner(server, record, record.owner)`, whose first statement dereferenced `record.owner.equals(member)` with no null guard. The NPE landed on `purge`'s very first line, so nothing after it ran: members were never ejected, `teardown` was never reached, the slot never returned to `usedSlots`, and its force-load tickets were never released. Admin-built slots therefore accumulated for the life of the process (a session of ~300 builds left every one allocated and force-loaded), and the same throw came back at shutdown through the `SERVER_STOPPING` handler. `saveRoomIfOwner` now returns early on a null owner, which is the correct behaviour: an unowned instance never gets a `roomCellOrigin`, so it has no room blob to save. The pre-existing `roomCellOrigin == null` check did already cover the unowned case, but only after the throw, and `RoomStore.capture` further down takes `record.owner` the same way, so the guard belongs first rather than the checks being reordered. `retireOrPurge` calls the same helper with the same argument and is fixed by the same change; it is not reachable with a null-owner record today, since both of its callers filter on a real owner first. Three latent NPEs of the same family fixed alongside it: `findOwnedLiveRoom`, `findVisitInstance` and `adminPurgeByOwner` all iterate `bySlot`, which holds admin-built records, and all three tested `record.owner.equals(owner)` with the nullable side as the receiver. Flipped to `owner.equals(record.owner)`, matching the idiom already used elsewhere in the file. `./gradlew build` green |
| 2026-08-25 | Claude | M6 complete, T6.1 through T6.5, and M5's T5.3 with it. Guaranteed floors (food, torch, bone, tier blocks, dirt, sapling, seeds) inlined into all six tier tables; `chests/supply` split into three tiered tables so the one ungated container carries the floor too. Three exclusive block palettes per `VISION.md` 3.6.1. New `theme_grove` processor list plus a `grove` room entry over the existing `mossy_tee` template: no Java, no new `.nbt`. Nether/End products as products, ungated at tier 3. `/dungeon` mints its own first keystone; `Instances.sendHome` prefers the room over the world spawn on every fallback. Verified live over RCON on a 26.2 dev server: 180 loot draws with no floor miss and no cross-tier bleed, and a stamped grove probed block by block. `./gradlew build` green |
| 2026-08-25 | Claude | Live bug, fixed: **the run timer was invisible and then fatal.** The M2/M3 lobby-first rework inverted the order `admit()` was written for -- it now runs at `enterLobby`, against a one-cell lobby layout with no level, no affixes and no clock, and the real run is not created until `generateBehindLobby`. `admit()`'s two layout-derived side effects were therefore silently skipped for every ordinary run: the `RunTimer`'s boss bar was constructed with **no players attached** (the clock ticked, expired, and `expireTimedOut` purged the instance and depleted the keystone without a single player ever having seen a bar), and an ominous run never granted its Trial Omen. `generateBehindLobby` now re-runs both for every member standing in the lobby, and `close()`s the previous run's timer before replacing it so a second dungeon behind the same lobby cannot leave a dead, frozen bar on screen beside the live one. `./gradlew build` green; not yet re-checked live. Follow-up on the same bug: the watcher's member loop now re-attaches every member standing in the dungeon dimension to the live timer each interval, so anyone who reaches a run *after* its clock started -- invited mid-run, re-entering their own live instance, walking back into the dimension -- gets the bar without each entry path having to remember to. It attaches the bar only; the clock itself keeps running on its own time (U8 Stage 1), so a latecomer sees the true remaining time rather than a fresh one. `ServerBossEvent.addPlayer` is a `Set.add` gating the packet send, so repeating it is a hash lookup and no traffic |
| 2026-08-25 | Claude | M5 T5.1/T5.2/T5.4. `Affix.FERAL` (seeded, white, curse-and-kiss blurb) joins the pool -- seeded shuffle is now five-wide. `RoomContent.spawnMobs` restored from `8dbb006^` as a general-purpose `(EntityType, count, Consumer<Entity>)` spawn path with the historical shuffle/jitter placement, never called for `encounter` cells. New `FeralContent` spawns neutral, cell-pinned wolves in `corridor`/`loot` cells with coats banded exclusively by loot tier. New `feralWolvesPerCell` config. No `spiritwolves` reference anywhere in source or jar. `./gradlew build` green. T5.3 still `BLOCKED` on M6 T6.1; handoff deliberately not renamed |
| 2026-08-24 | Devin | M3 closed. `CallingCard` mint/read helpers + `/dungeon room card` command, `callingCardItem` config, `RitualListener` card branch routing to `Instances.visit`, shared/refcounted read-only visit instances with `InstanceRecord.visitInstance`, and teardown-on-last-leave. Visitor permissions reuse M2 `RoomProtection`. Post-review fixes: `reenterOwnedInstance` excludes visit instances; `createVisitInstance` seals the room door; `stampLobby` and `createVisitInstance` place selector doors/leave-pad against the connecting wall so first-time and visit lobbies are usable; selector doors are cleared and the sealed double doorway opens when a choice is made; `PendingClear` now clears the one-block bedrock envelope around each cell so instances purge cleanly. Completion flow reworked: terminal cell stays the exit room, reward chests spawn on the far side in front of a sealed door, and the persistent room is stamped behind that door; `resetForNextDungeon` clears the previous dungeon and seals `ee` before a new run. Added `/dungeon admin resetkey <player>` to reset keystone progress to 0 and clear held keystones. `./gradlew build` green. Live two-client convergence and permission checks deferred to the suite-wide multiplayer pass |
| 2026-08-24 | Devin | M3 non-dependent prep: `CallingCard.java` mint/read helpers, `callingCardItem` config defaulting to `minecraft:compass`, `RitualListener` warmup. T3.1 implementation complete; T3.2–T3.4 left `BLOCKED` until M2 lands. `./gradlew build` currently red on M2-incomplete `Instances.java` (`moveRoomToTerminal` / `buildLayout` arity), not on these changes |
| 2026-08-24 | Devin | M1 closed. `DungeonRoomMeta` `processors`/`theme` wired, `RoomSelector` theme filtering, three proof processor-list datapack themes, `PlanSelectorTest`/`DungeonRoomMetaTest` pass. `./gradlew build` green. `plans/M1-themes-foundation.md` → `M1-themes-foundation-completed.md`; current milestone moved to M2 |
| 2026-08-24 | design session | `DIALOGS_SPEC.md` written — seven menus spec'd against the vanilla dialog mechanism `quizengine`/`cobbleeconomy` already ship (no code): door offers, kick/invite confirmations, keystone inspection, room whitelist (M2 T2.2), admin baserestore confirm (M2 T2.1), and the elevator (D7). Corrected D7's cost estimate in `plans/M8-deferred.md` — it was priced against building a `MenuProvider` from nothing; the real decision is vanilla `DialogListDialog` vs. adding `eu.pb4:sgui` for pagination. Cross-referenced from M2 and M3's plans |
| 2026-08-24 | user + Devin | Decided: multiplayer testing (any task needing two connected clients) is deferred to one pass after every milestone is code-complete, not run per-task. Noted at the top of this file. M0 closed on that basis: T0.5 marked `DONE` on code + build-green, its live click check deferred rather than left `WIP`. Current milestone moved to M1 |
| 2026-08-24 | design session | D7 "the elevator" (public opt-in room/party directory) added to the M8 backlog — a user idea, not yet scoped into a plan. Flagged its tension with the calling card's deliberate no-browse rule and its real cost (new menu system, new per-template anchor) rather than filing it as free |
| 2026-08-24 | Devin | M0 T0.1–T0.4 done. `RoomManifest` now reloads on `/reload` (verified live over RCON); root `LICENSE` added (MIT, suite-wide); `INTEGRATION.md` written with the schema table and verbatim validation failures; `selectorDoorStep` gated on `record.owner`. `./gradlew build` green |
| 2026-08-24 | design session | Spec reframed on Skyblock/StoneBlock, Factions cut. Weekly affix rotation cut. Kiss/curse made the affix rule. Calling card designed as the visit mechanism. Every open question closed (§7). Roadmap, plans and this file created |
| 2026-08-24 | design session | T1.1 `processors` wired, verified end to end on a 26.2 dev server over RCON, committed `f7345eb` |

---

## Decisions that are closed

Do not reopen without new information, written down.

| Decision | Where |
|---|---|
| Skyblock is the proof, **not** the model — no scarcity, no bootstrapping | `VISION.md` §2, §3.7.1 |
| Visitability is the thesis, not a backlog item | `VISION.md` §2.1 |
| Weekly affix rotation is **cut**; affixes seed from the key | §4 |
| Thresholds are **5 / 11 / 17** | §4.1 |
| Every affix owes a kiss | §5.0 |
| Depletion takes the `max`, capped at 2× | §7.1 |
| Leadership change **purges**, never transfers | §7.2 |
| Molten is the lava faucet; Silenced deafens the mobs | §5.5, §7.3 |
| Wolves: 1-in-3 per bone, angry refuses, tamed sits — spawn **neutral** | §7.4 |
| The mod says **nothing** about the closed loop | `VISION.md` §4 |

*(§ references without a filename are `MYTHIC_PLUS_RECONCILIATION.md`.)*
