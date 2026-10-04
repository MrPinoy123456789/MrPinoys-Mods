# Review request for Opus 5.5: playtest 2026-10-02 fix batch

Written 2026-10-02 by Sonnet 5.5 for a full review. Everything below is **uncommitted**
in the working tree on branch `wip-lemon-harness` (the tree also held a lot of earlier
uncommitted work by the owner and another agent; see "Not mine" at the end). Read
`../CLAUDE.md` first (no em dashes, no spaced double hyphens as punctuation).

## State of the build

- `gradlew test`: passes.
- `gradlew runGameTest`: 139 tests, **1 failing**: `HandlerGameTest.sealedTwoStoryRoomsOpenToABlast`
  ("slime_pit has a shaft and a ladder hole, found" one position). It belongs to the earlier
  uncommitted L11 rubble work, not to this batch. Worth a look as part of the review.
- Nothing has been played. Every "fixed" row is `pending in-game verify`; the live-check rows
  are `docs/playtests/LIVE_CHECKS.md` L12 to L18.
- The machine ran out of memory twice during `runGameTest`; `./gradlew --stop` and a retry fixed it.

## Where to start

1. `docs/plan-2026-10-02-1.md` (the plan and its status section, including the owner's later answers).
2. `docs/handoff-2026-10-02-1.md` (the original request) and `docs/playtests/2026-10-02-1.md` (the evidence).
3. This file, then the diff.

## What changed, by area

**Bugs PD-105 to PD-112** (entries in `docs/reference/BUGS.md`)
- PD-106/108: tier 3 spawners used vanilla `equipment/trial_chamber_*` (always trimmed). Now our own
  `equipment/tier_3_*` tables (new). `DungeonDrops.ARMOUR_DROP_CHANCE` 0.08. Salvage refusal texts rewritten.
  `the_raid` pointed at a table that did not exist.
- PD-107: `TrialContent.genericPrefix` picks one of `undead`, `bones`, `spiders` (new narrow configs) per cell
  for themeless rooms; `chaoticSpawnerChance` 0.05 keeps the old broad pool.
- PD-110: `SpiderUnstick` (tick check, teleports a long-climbing untargeted spider to the floor).
- PD-111: `DifficultyProfile.spawnersStillNeeded`, message in `RunLifecycle`.
- PD-112 and omen sound: `OmenBar.omenRose` plays a cue for every gain (throttled 1 s); sensor uses a warden sound.
- **PD-105 and PD-109 were not reproduced.** Regression tests only. Please check my reasoning in the BUGS entries.

**Economy and design**
- All 237 `max_stack_size` sets removed from loot data (files were re-serialised with `JSON.stringify`, so the
  diff on 76 JSON files is noisy; semantically only the cap function was removed). `InventorySwap.withoutLegacyStackCap`
  resets old capped stacks on entry.
- Ender chest loot became 8 obsidian (6 tables plus two tier 4 vault tables).
- Echo shards: per full interval, 50 percent per floor, 25 percent per Ordeal (`Fuel.grantFrom`, `PlaytestJournal.echoShards`).
- Silence: `ConsumableRule.OMEN`, `OmenSources.silencedUse` (1 omen per 3 consumables), new `Omen.Source.SILENCE`.
- Dead-end fountain (`Fountain`, hooked in `LayoutStamper` and `RitualListener`); pedestal block encodes the boon.
- Floor history board rewritten (`FloorHistory.Board`, uniform font for columns, `DungeonScreen.summonHistory`).
- Floor-start title (`FloorStartTitle`, tick-driven).
- Guard's Bag (new bag data, `BagIds.GUARD`, four tests updated for nine bags).
- Go-home pitch no longer mentions reward chests (`IntervalBanking`, `DialogScreens`, `RunLifecycle`, test strings updated).

**Owner decisions made during the session**
- Mending lock-in: `LibrarianNPC` (copy of `BlacksmithNPC` with a lectern), `LockInStation` (merchant GUI, one trade),
  salvage and reroll refuse locked gear, Mending removed from loot via the enchantment tag
  `#pocketdungeons:random_loot` (50 loot files now use it) and from rerolls.
- The gamble block was a duplicate of the blacksmith: `GambleStation` no longer has a block entry point.
- Run storage is now the **ender chest** in a run (`RunStorage`, claimed before the M46 ender denial in
  `RitualListener`); the bag chest became the waxed oxidized copper chest (`Instances.bagChestBlock()`, looked up
  by id because copper chests live in a `WeatheringCopperCollection`). Contents return on `InstanceTeardown.purge`.
- Affix short names are contractions of at most five letters, only for longer labels (validated in `AffixMeta`).
- Diaries: read in the book screen (`DiaryReading.openBook`, main-hand swap for two ticks) and Lemon's archive
  (`LemonArchive`: hand a tagged diary to Lemon; stored as band + 100 inside the existing diary band set;
  all seven gives +1 echo shard per interval). The tip texts are placeholders.

**Docs**: `THEME_TABLE.md` (design proposal, nothing implemented), ROADMAP backlog, BALANCE changes, LIVE_CHECKS L12 to L18,
Lemon pack rebuilt.

## Where I most want a second pair of eyes

1. **`RitualListener.onUseBlock` ordering.** `RunStorage.onUse` now sits ahead of the M46 ender denial and the
   room permission mask. Confirm no path lets a visitor reach another player's storage and that a non-run player
   still gets the old denial.
2. **Run storage return on purge.** `RunStorage.returnAll` runs after members are ejected and delivers via
   `Payout.deliver`; offline members get a Lost and Found entry. Is the purge the only way a run ends? What about
   a crash or a `/dungeon quit`? In-memory only, so a server crash loses the contents. Is that acceptable?
3. **`LemonArchive` storage trick** (band + 100 in `diaryBandsSeen`). Any consumer that iterates the set or
   counts it will see the extra values. I checked `diaryList` and `DiaryDelivery`; please grep again.
4. **`DiaryReading.openBook`.** It assumes the client opens the book from the hand item and that two ticks is enough
   before the real item is restored. A player who changes slot or logs out inside the window could lose the item.
   The restore list is not cleaned on disconnect.
5. **`FloorHistory` board.** Uses `FontDescription.Resource(uniform)`. I could not see it. Width and wall fit are
   guesses (`HISTORY_SCALE` 0.72, line width 1000). Also a `FloorHistory` unit test file
   (`src/test/.../FloorHistoryTest.java`) exists that I did not write; check it still matches the new `Board` API.
6. **`FloorStartTitle`** statics (`RUNNING` list, not cleared on server stop) and one title packet burst per beat.
7. **`SpiderUnstick`** uses two `WeakHashMap`s keyed by entity from the server thread only; confirm that is safe
   and that tracking via `ENTITY_LOAD` catches spiders loaded from disk.
8. **`Fountain` and `LayoutStamper`.** The dead-end test is "exactly one door edge, no spawner, not entrance,
   terminal or anomaly". Rooms with a vault count as dead ends only if they have no trial spawner anchors.
   `SPOTS` assume free corners at y=1 and a solid floor; unverified against the real templates.
9. **`TrialContent.genericPrefix`** hashes the cell origin; check the preview and the built floor agree and that
   `normal_config`/`ominous_config` always get the same prefix.
10. **`PD-105` reasoning.** `LemonBody.interact` returns `SUCCESS_SERVER` on a main-hand click and runs the archive
    hand-over first. Confirm no other code path (Fabric callbacks, Allay item hand-off) can still take her book.
11. **Config churn.** New keys: `chaoticSpawnerChance`, `silencedConsumablesPerOmen`, `fountainChance`,
    `fountainOmenRelief`, `echoShardsPerInterval`, `echoShardFloorChance`, `echoShardOrdealChance`, `lockInEmeralds`,
    `lockInUnlockLevel`, `storageBlock` (replaces `gambleBlock`). Check read, write and reset all agree.
12. **Echo shard balance.** Expected income is now roughly 1 + 1.5 per interval plus Ordeals (and +1 with the full
    archive). Is that too generous next to a 3 shard Greater door?

## The other agent's work (themed merchants), reviewed

Files: `MerchantThemes.java`, `StorePricing.java`, `StoreNPC.java` (166 insertions, 60 deletions),
`docs/reference/THEMED_MERCHANTS.md`, `StorePricingTest`, `ThemedMerchantGameTest`.
- Stores are now themed by floor theme and priced in mob drops (bones, string, blaze rods and so on); a theme
  with no entry keeps the emerald shopkeeper. Currency arithmetic is pure and unit tested.
- **Finding 1:** `ThemedMerchantGameTest` was never registered in `src/gametest/resources/fabric.mod.json`, so it
  never ran (their doc says the game test died at JVM start). I registered it; all four tests pass.
- **Finding 2:** the surplus-sink item in my ROADMAP backlog (string, bones, blaze powder, magma cream) is now
  partly addressed by this work; the backlog line should be trimmed once the owner agrees.
- **Finding 3:** `MerchantThemes.BY_THEME` keys on the theme path; `blackstone`, `prismarine` and `drowned_vault`
  fall back to the emerald merchant, consistent with my `THEME_TABLE.md` (those themes have no mob family of
  their own).
- **For you:** the inventory tag change in `StoreNPC` (saved inventory now written to a single entity tag; legacy
  read path matches `INVENTORY_TAG + ":"`). Check old villagers in saved worlds still load.
- Live check added as L18.

## Not mine (do not attribute to this batch)

The tree held, before I started: a large set of uncommitted changes (about 230 files in `git status`), the
deleted `BountyTracker`, Lemon harness changes, `RubbleOrdeal`, tier 4 loot tables, `FloorState` and
`IntervalState` edits, `StationTutorial`, and logs and `run-*` directories that should not be committed.
Also stray `hs_err_pid54028.log` and `replay_pid54028.log` in the module root from the memory crashes: delete them.
No commit was made. When committing, split by area and keep logs, the jar and `run-*` out.

## Known gaps

- Item 14: the home sign progress bar and diagram are not built (wording only). Needs an owner mock first.
- `THEME_TABLE.md` is a proposal; no implementation.
- Tip texts in `LemonArchive.TIPS` and the placeholder fountain pedestal materials are unreviewed by the owner.
