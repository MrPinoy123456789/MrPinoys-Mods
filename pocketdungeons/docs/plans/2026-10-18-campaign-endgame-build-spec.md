# Campaign and endgame: build spec (2026-10-18)

The build plan for `docs/DECISIONS.md` "2026-10-18: Campaign and endgame". The decision is the rule; this spec is how
to build it, task by task, with file ownership so parallel agents do not collide. Where this spec and the decision
disagree, the decision wins and the builder says so in the report. Background and stories:
`docs/plans/2026-10-18-campaign-and-endgame-brief.md`.

**House rules:** every agent follows `.claude/skills/agent-brief/SKILL.md`; the orchestrator follows
`.claude/skills/orchestrate-build/SKILL.md` and `.claude/skills/integrate-branches/SKILL.md`. No em dashes or spaced
double hyphens anywhere. Player-facing text follows the `player-text` skill and uses the exact strings below. Knobs via
`tools/new_knob.py` (`tune-knobs` skill). Gametests via `tools/new_gametest.py` (`add-tests` skill). Live checks via
`tools/livecheck.py new` fragments; L104 to L106 already exist in `LIVE_CHECKS.md`, so add only new ones.

## Scope

- **Part A** (approved 2026-10-18): tasks T0 to T8. Build all of it.
- **Part B** (review items 1, 3, 7 and 9 of `docs/plans/2026-10-17-design-review.md`): tasks B1 to B4. Build only if the
  prompt's scope line says "Parts A and B". The owner approves them by saying so. If built, the integrator appends a
  short dated section to `docs/DECISIONS.md` recording that the owner approved those items through the build prompt.
- **Not in scope:** the timed trial (held in the decision), Acts 3 to 5 content, vertical floors Phases 2 to 4, any
  deploy, any push, any server or Lemon tool.

## Branches

- **Base:** `feature/campaign-endgame-design` (it holds master, the unmerged Act 0 work, the payoff rooms and these
  docs). Check first: the tip of `feature/campaign-endgame-design` must contain this spec file and the decision of 2026-10-18.
- **Integration:** `feature/campaign-endgame` off the base, in its own worktree. Every task branch
  (`feature/rm-<id>-<topic>`, made with `tools/new_worktree.sh <id> <topic>` with `PD_BASE=feature/campaign-endgame`)
  merges the integration branch first, and again before its final suite run.
- Author every commit as `MrPinoy123456789` (`-c user.name=MrPinoy123456789
  -c user.email=MrPinoy123456789@users.noreply.github.com`). Never commit to `master`. Never push.

## Waves and file ownership

At most four agents at once. A task starts only when everything in its "needs" column is merged into the integration
branch. Hot files (`PocketDungeonsConfig`, `PocketDungeonsMod`, `RunLifecycle`, `fabric.mod.json`) take minimal, local
edits from anyone; every other file is owned by one task per wave.

| Wave | Task | Size | Needs | Owns |
|---|---|---|---|---|
| 0 | T0 Campaign seam | M | none | `ActProgress`, `DungeonProgress`, `HallOffers`, `HallLayout`, `TripDoors`, new `Campaign` |
| 1 | T1 Mob curve | S | T0 | `MobScaling`, `DifficultyProfile` |
| 1 | T2 Quest-line compass | M | T0 | `Keystone`, `SidebarLines`, `SidebarDisplay`, `DungeonScreen`, `HallRoom`, `DungeonMapText`, `EndlessMineRules` (gates only), Act 0 data |
| 1 | T3 Lives grow with the party | S | T0 | `Omen`, `OmenBar`, `OmenBarText`, `IntervalState` (one field), `DoorLives` |
| 2 | T4 The pack and the pot | L | T2, T3 | new `PackPot`, `PackHalf`, `PackPots`; `Instances` (rescue and fail), `RunStorage`, `RitualListener` (storage branch), `RoomProtection` (one fixture check) |
| 3 | T5 Chart Room and uncharted doors | L | T2, T4 | new `UnchartedDoors`, `UnchartedGen`; `HallOffers`, `HallLayout`, `HallRoom`, `StagingFurniture`, `TripView`, `DungeonDefs` (lookup), `DoorAffixes` |
| 3 | T6 Endgame dive | M | T5's seam commit (see T5 step 1) | `EndlessMineRules`, `MineGuardians*`, `ProvingWaves`, `ProvingSpecs`, `dungeon/the_proving.json`, new `dungeon/endless_mine_chart.json` |
| 4 | T7 One heavy thing | M | T4, T5 | new `RelicObjective`; `FloorObjectives` (dispatch), `FloorObjectiveRules` |
| 5 | T8 Integration and docs | M | all | `CODEMAP.md`, `INTEGRATION.md`, `LIVE_CHECKS.md` folding, Lemon knowledge pack note, `DUNGEON_STRUCTURE_DESIGN.md` |
| B | B1 to B4 | see Part B | Part A wave 1 | see Part B |

## Shared definitions (T0 builds these; everyone else uses them)

- **`releaseMaxAct`** (knob, default 2): the last act on the server. Acts above it never open, never show on the
  astrolabe, and a save that already has them keeps them stored but unreachable (never delete stored acts).
- **`Campaign.done(entry)`**: true when the player has finished the capstone of act `releaseMaxAct` (read
  `entry.dungeonsFinished()` against `ActProgress`; add a stored flag only if this cannot answer it, with a golden
  fixture). Everything that differs between campaign and endgame branches on this one method.
- **`Campaign.isCampaignTrip(record)`**: the trip is not an uncharted door and not the endgame dive.
- **`capstoneDungeonsNeeded`** (knob, default 3): `ActProgress.capstoneReady` becomes "at least this many of the act's
  non-capstone, non-descending dungeons finished", capped at the act's count. The mine leg no longer gates
  (`mineLegDone` stays for the Endless Mine's own record, but `capstoneReady` and `complete` stop reading it).
  `complete(act)` (act opens the next) becomes "the act's capstone is finished".
- **Migration of acts:** opened acts are stored sets and are never taken away. A player who has a later act open under
  the old rule keeps it.

---

## Part A

### T0: Campaign seam (wave 0)

1. Add `Campaign` (pure helpers plus the two methods above) and the knobs `releaseMaxAct` and `capstoneDungeonsNeeded`.
2. Rewrite `ActProgress.capstoneReady`, `complete`, `progressLine`, `remainingLine` to the three-dungeon rule. Keep
   method names; callers keep working.
3. Cap acts at `releaseMaxAct` in `DungeonProgress.unlockedActs` and in the astrolabe's act cycle (`HallOffers.build`,
   `HallLayout`). Finishing the capstone of `releaseMaxAct` unlocks nothing further.
4. Campaign gates: in `HallLayout` (around `:166` to `:190`), `TripDoors.eligibleFirst` / `pendingCapstone`, and
   `EndlessMineRules.openMines` (`:160`), a campaign door is open when its act is open (and, for a capstone, when
   `capstoneReady`); `unlockLevel` (compass) no longer gates campaign doors. The Act 1 mine opens with Act 1.
5. Tests: update `ActUnlockTest`, `HallLayoutTest`, `TripDoorsTest`, `EndlessMineRulesTest` to the new rule (rewrite the
   expectations; never delete a test's intent). New pure cases: 3 of 5 opens the capstone, 2 of 5 does not, the mine
   is not needed, `releaseMaxAct` 2 hides act 3 even when stored.

### T1: Mob curve (wave 1)

1. Defaults: `mobScaleBase` 0.65 to **0.85**, `mobScalePerLevel` 0.008 to **0.02** (`PocketDungeonsConfig.java:245` to
   `:248`). Update the javadoc numbers.
2. `omenDangerScalePerOmen` becomes per act: a new knob `omenDangerScaleActOne` (0.0) used when the trip's act is 0 or 1,
   the existing knob (default 0.1, was 0.15) from Act 2. `MobScaling.applyMobScale` needs the act; thread it from the
   callers (the record's dungeon via `TripView.def`). Uncharted doors and the endgame dive use the Act 2 value.
3. Regenerate `config/pocketdungeons.default.json` (`DefaultConfigFileTest` fails until you do) and update
   `ConfigCharacterizationTest` expectations.
4. Tests: pure `DifficultyProfile.mobScale` at levels 3, 8, 12, 20; the act split of the omen bonus.

### T2: Quest-line compass, no campaign scrap (wave 1)

**Player strings (exact):**
- compass item name in the campaign: `Compass`
- lore line 1: `Next: Mineshaft, Lush Caves or Infestation` (the open, unfinished, non-capstone dungeons of the lowest
  open act, joined `, ` with ` or ` before the last; at most three named, then `and N more`)
- lore line 2: `2 more for the Spawner Dungeon` (or `1 more for ...`); once the capstone is open: `Next: the Spawner
  Dungeon`; when every open act's capstone is done and `Campaign.done` is false (cannot happen with the cap, but guard
  it): `Next: the Astrolabe`
- locked astrolabe door sign: `Finish Act 1` or `Finish 3 dungeons` (never `Compass N` in the campaign)
- the wake beat, title on finishing the capstone of `releaseMaxAct`: title `Your compass wakes up.`, subtitle
  `The Chart Room is open.`
- endgame lore (existing): `Compass 1: 0/4 scrap to 2`
- migration, one chat block on the first join after the update (a `DungeonLog` boolean, `optionalFieldOf`, default
  false, golden fixture): `Your compass now shows the way. It will count again when the campaign is done.` then
  `What's in your pack is at risk. Your hotbar, armour and Dungeon Storage are safe.` (send the second line only once
  T4 is merged; T2 adds the flag and the first line, T4 adds the second line to the same message)

**Changes:**
1. `Keystone` lore (`:323` to `:345`): campaign branch writes the quest line; endgame branch keeps today's compass line.
2. No scrap in the campaign: in `RunLifecycle` floor pay (`:1700` to `:1730`), a campaign trip pays 0 scrap (keys and
   emerald redemption unchanged). Remove the Act 0 `actZeroScrap` branch (`:1710`) and retire the knobs `actZeroScrap`
   and `compassCostFirstLevel` through the config's retired-keys list (see how `echoShardsPerInterval` was retired,
   `PocketDungeonsConfig.java:1882`).
3. The board (`DungeonScreen.previewGets`, `:450`): a campaign door shows no scrap part; the item parts stay.
4. The sidebar (`SidebarLines.lines`): a campaign trip shows no `Compass` line and no haul line; an endgame trip keeps
   both. Keep `Lives`, floor and spawner lines.
5. `HallRoom` (`:246`, `:252`) and `DungeonMapText` (`:150`, `:181`): campaign labels per the strings above.
6. Act 0 finish: the compass's first quest line appears (`Next: the Mineshaft` and the other Act 1 dungeons). The Home
   lever still unlocks on the finish (Act 0 rule 15 stands).
7. The wake beat fires once, on the finish that makes `Campaign.done` true, and leaves the stored compass as is. A
   fresh player wakes at Compass 1 with 0 progress (the stored default). A migrated player keeps their stored level and
   bar.
8. Tests: `SidebarLinesTest`, `KeystoneOfferTest` and new pure tests for the quest line text (0, 1, 2, 3 finished;
   capstone open; mixed acts). A gametest: a campaign floor clear pays no scrap; an endgame floor clear does.

### T3: Lives grow with the party (wave 1)

1. `Omen.LIVES` (5) stays as the solo base; add `Omen.maxLives(members)` =
   `5 + (min(members, 4) - 1) x livesPerExtraMember`, with `livesPerExtraMember` a knob (default 1): solo 5, two 6,
   four 8, and a fifth member adds nothing.
2. The trip's max is fixed at the first door commit and stored on `IntervalState` (in memory, like the omen). Members
   who join later add nothing. `Omen.lives(omen)` becomes `lives(omen, max)`; `nextDeathFails` likewise. Update every
   caller found by `grep -n "Omen.lives(\|Omen.LIVES"` (OmenBar `:118`, OmenBarText `:48` `:167` `:190`, PlayerContext
   `:158`, RitualListener `:678` `:717`, RunLifecycle `:502` `:651` `:784`, SidebarDisplay `:114`).
3. `SidebarLines.livesColour` reads the fraction of the max (green above 3/5, yellow above 1/5, red at 1). Coordinate:
   T2 owns `SidebarLines`; T3 changes only `livesColour`'s signature and its one call. Merge T2 first.
4. Blood doors and the Blood affix still cost a flat life and are refused unless one would remain.
5. Tests: `OmenMathTest` (solo 5, two 6, four 8, a fifth member still 8), `DoorLivesTest`, `OmenBarTextTest`.

### T4: The pack and the pot (wave 2)

Read the decision's rules 1 to 8 first; they are the contract.

1. **`PackHalf`** (pure, no Minecraft imports beyond what `ItemStack`-free tests allow; model stacks as id, count,
   max stack size, tier): each stack keeps `count / 2` rounded down; the single items left over (max stack size 1, or
   stacks of 1) keep every other one, sorted best tier first, seeded by the trip salt so a retry cannot reroll. A pure
   test pins the rounding, the seed and the order.
2. **The rescue hook.** In `Instances.rescue` (`:2086`), when the run has a room to return to (`hadRoom`), the trip is
   not Act 0, and the record is not admin, untimed or a visit: before the teleport, move every stack in the 27 main
   inventory slots (not the hotbar 0 to 8, not armour, not the offhand) into a new pot. Do it before `revive`'s
   teleport and after the life is spent, in the same tick.
3. **The pot.** A `minecraft:decorated_pot` block placed by the mod, registered as a fixture through the existing
   fixture path (`FloorObjectives.isFixture` style or a `PackPots.isPot(pos)` check in `RoomProtection`), so it cannot
   be broken. Named for the menu: `<player>'s pack`. Right-click opens a 27 slot virtual menu the way `RunStorage`
   opens its ender chest menu (`RunStorage.java`); the pot's own block entity is never used. Its owner and any member
   of the party may open it and take items; nobody may put items in.
4. **Where it stands.** The block where the player fell (the death position, floored), if it is air above a solid
   block inside the floor's bounds and not lava, fire, the void, water deeper than one block, a voided cell, a door
   lane, the pad or a sealed ordeal room the player cannot re-enter. Otherwise the nearest safe block next to the
   inside of that cell's nearest door. Never block a path: check against the floor's door lanes and the pad.
5. **Persistence (player data is sacred).** Pot contents are written to `DungeonLog` under the owner (a list of pots:
   id, run slot, floor index, stacks), the way Dungeon Storage is stored, with a golden fixture. If a pot's run is gone
   when the owner next joins (crash, restart, purge), its contents return **in full** to the owner's Dungeon Storage
   (overflow into the pack, then a chat line `Some of your things were too many to carry. They wait in Dungeon
   Storage.` only if that still overflows, and never delete an item; if Dungeon Storage and the pack are both full,
   keep the remainder in the record and retry on the next join). A server fault never costs items.
6. **Floor end** (descend, the HOME lever, a finish: `advanceFloor` `:1381`, `completeRun` `:1178`, the HOME bank in
   `bankHaul` `:1806`): every unemptied pot of that floor settles through `PackHalf` at `potKeepPercent` (knob, default
   50); the kept half goes into its owner's pack, overflow into Dungeon Storage, as in step 5. The pot block is removed.
7. **Failing** (`Instances.failRunOmen` `:2200`, and the leave-fail paths in `RunLifecycle` around `:1750` to `:1761`):
   the fifth rescue makes its pot first (step 2 runs before the fail check), then every pot of the dungeon and every
   member's pack (main 27 slots only, present or detached) settles through `PackHalf` at `failHaulKeepPercent`. The kept
   half goes home with the party (`failReturnsHome`). Change the announce lines at `:2219` and `:2233` to `The dungeon
   claims you. You keep half of your pack.`
8. **Legibility.** `SidebarLines` gains `Pots N` (white) while any of the player's party's pots stand on the floor. The
   first pot of a trip sends the player one action bar line and one chat line: `Your pack stayed where you fell.`
9. **Dungeon Storage at the Doors only.** During a trip (`RunSession` phase not IDLE), `RunStorage.onUse` opens only
   when the ender chest is in the staging room cell or the player's Home room; an ender chest anywhere else in a run
   returns `FAIL` with the action bar line `Dungeon Storage opens at the Doors.` Outside a trip nothing changes.
10. **Act 0** makes no pots (rule 8). A disconnect is not a rescue (unchanged reconnect grace).
11. **Endgame:** the same rules on uncharted doors and the dive; nothing extra.
12. Tests: `PackHalfTest` (pure). Gametests `PackPotGameTest`: a rescue moves exactly the 27 slots, hotbar and armour
    untouched; the pot stands at the fall spot, and at the door when the spot is lava; a party member can open it;
    floor end keeps half; a fail keeps half of pots plus packs; a restart with a live pot returns it in full; an
    ender chest mid-floor refuses and the staging one opens. Extend `RescueGameTest` and `DungeonStorageGameTest`
    rather than duplicating them. Golden fixture for the pots record.

### T5: The Chart Room and uncharted doors (wave 3)

**Step 1, the seam (commit and merge first so T6 can start):** add `DungeonDef.Kind.UNCHARTED` and an endgame dive
slot on the Chart Room hall that points at the dungeon id `pocketdungeons:endless_mine_chart` (T6 creates it; until it
exists the slot is empty and logs nothing worse than a debug line).

**The Chart Room.** The astrolabe's last position, shown only when `Campaign.done`: right-click cycles acts as now, and
after the last open act comes `Chart Room`. Its main wall holds three uncharted doors; its side wall holds the dive.
Reuse `HallLayout` and `HallRoom` (a new hall kind, not a new structure). Sign text: `Uncharted` on each door, the
door's level and its Loot percent on the board as for any door.

**Uncharted doors (`UnchartedGen`).**
1. A generated `DungeonDef` built in memory, never written to data: id `pocketdungeons:uncharted_<salt>`, `kind`
   UNCHARTED, 3 nodes on 3 layers, a single path, the last node `final` with a guardian finale.
2. Seeded by owner, the trip counter and the door slot, so the three doors are stable while the player looks and
   reroll after each trip (`entry.campaign().tripCounter()`).
3. Themes: each node draws its `mainTheme` from the themes of acts the player has finished (`DungeonDefs.actOfTheme`),
   respecting the deviation guardrails (the entry and final floors share one theme). Loot band: the highest act
   finished (D10).
4. Level: a node's level is the player's compass (the leader's; in a party, the lowest present member's) and the door
   step (0 to 3) as today; `floorPay` pays at the dealt step as in the 2026-10-09 curve (the compass is never above the
   floor, so no below-compass halving). Mob scale uses T1's curve at that level.
5. Affixes: the door's affix count follows the existing level thresholds; at least half (rounded up) are drawn from the
   **rule** set: `blackout`, `restless`, `molten`, `explosive`, `voided`, `cooked`, `blood`. The **stat** set:
   `swarming`, `overclocked`, `silenced`, `loaded`. Respect `minLevel` and `incompatible`. Add the rule/stat tag as a
   field in the affix JSON (`kind: "rule" | "stat"`, default `stat`), validated by `AffixManifest`; follow the
   `add-affix` skill for the field.
6. Finale: the final node's terminal cell runs a guardian chosen from `MineGuardians` for the final floor's act (lap
   slot by the door salt), under `BossFight` phases, scaled to the node's level.
7. Lookups: `TripView.def` and any `DungeonDefs.current().byId` call that can see an uncharted id must find the
   generated def (keep the active trip's generated def on `IntervalState`; a restart ends the trip as today).
8. Scrap: uncharted doors pay scrap into the haul and bank into the compass exactly as the 2026-10-07 and 2026-10-09
   records say, plus the pack rules of T4.
9. Uncharted doors never count toward acts, never appear in the campaign, and are hidden from `DungeonMapText`'s
   checklist.
10. Tests: pure `UnchartedGenTest` (same seed same door; three distinct doors; themes only from finished acts; at least
    half rule affixes; level = compass + step; the final node is final). `RoomSelector` solvability passes on a seed
    sweep of generated plans (reuse `GraphSolvabilityTest`'s pattern). Gametest `ChartRoomGameTest`: the astrolabe
    shows the Chart Room only after `Campaign.done`; a door commits and stamps; the guardian spawns on floor 3.

### T6: The endgame dive (wave 3, after T5 step 1)

1. Retire the Proving's door: `the_proving.json` leaves the astrolabe (set `hall` to a value the hall skips, or a
   `hidden` flag; keep the file so old references load). Keep `ProvingWaves` and `ProvingSpecs`: they become the
   dive's guardian floor.
2. Create `dungeon/endless_mine_chart.json` (kind `endless`, act = the highest released act for validation, hall
   `chart`), theme and processor list reusing the Deep Mine's, `hiddenOre` as the Deep Mine.
3. The dive's level: `compass + floor` (the leader's or the lowest member's compass), no cap; floors between guardians
   are mining floors (hidden ore, the faucet), tier tables from the highest finished act's `act<N>_a/b/c`.
4. Guardian floors (every `endlessGuardianEvery`, 3): the terminal cell is the `proving_arena` room; `provingWavesPerFloor`
   waves themed to the act, then the act's guardian for that lap slot under `BossFight`, then the pad. Knobs move with
   it: the Proving knobs keep their names and now drive the dive's arena.
5. The act mines in the campaign keep their current behaviour (optional dives; T0 already removed the gate).
6. Tests: `EndlessMineRulesTest` cases for the chart mine's level; `ProvingGameTest` adapted to the dive's guardian
   floor; `MineGuardianGameTest` still green.

### T7: One heavy thing (wave 4)

1. A new floor objective kind `relic` (`FloorObjectives` dispatch, `ObjectiveState`), used only by uncharted doors:
   `UnchartedGen` gives a middle node `objective: relic` with chance `relicFloorChance` (knob, default 0.25).
2. The relic: a vanilla `minecraft:heavy_core` item named `Relic`, placed on a plinth in a branch cell (never the
   entrance or terminal). Carrying it to the pad adds `relicLootPercent` (knob, default 100) to the floor's Loot
   percent. The holder gets Slowness I while it is in their inventory. It cannot be put in Dungeon Storage, a pot or any
   container (refuse with `The relic will not fit.`). A rescue drops it into the holder's pot even from the hotbar (the
   one exception to the spared slots). If it leaves the floor any other way it returns to its plinth.
3. The pad does not wait for the relic: it is greed, never the gate (the floor's spawner gate is unchanged).
4. Tests: pure rules in `FloorObjectiveRulesTest`; gametest `RelicFloorGameTest` (carry pays, rescue drops it in the
   pot, storage refuses, the pad opens without it).

### T8: Integration and docs (wave 5, the orchestrator)

1. Merge every branch per `integrate-branches`; run the full suite (`tools/ship.sh --quick --no-dist`) green.
2. Fold live-check fragments; add a fragment for the relic (L107) and any new behaviour without a check.
3. Regenerate `docs/CODEMAP.md` (`python tools/gen_codemap.py`); update `INTEGRATION.md` (new kinds `UNCHARTED`, hall
   `chart`, the affix `kind` field, new knobs); update the knobs list in the `tune-knobs` skill if it enumerates them;
   note in the report which Lemon knowledge pack lines describe the old rules (do not run Lemon tools).
4. Update `docs/DUNGEON_STRUCTURE_DESIGN.md` section 1 to the built rule (remove the "approved, not built" line) and
   add a short "Built" status line under the decision in `DECISIONS.md` naming the commit range and any deviation.
5. Write `docs/reports/campaign-endgame-build.md`: per task, what was built, deviations from this spec, test counts
   before and after, and what only a live server can confirm (L104 to L107).

---

## Part B (build only if the scope line says "Parts A and B")

### B1: Act 0 teaches the dungeon (review item 1)

Floors as the review's table: floor 1 the pad, floor 2 one weak trial spawner gating the pad (with the gather count),
floor 3 a two-edge layer (step 0 and step 1; with T2 the board names items, so the step 1 door names one extra item
instead of `+1 scrap`), floor 4 lives shown and a rescue refunded at the floor's end with `Lemon caught you.` and once
`In the real dungeons, a life stays gone.`, floor 5 the finish banks the pack at the Home lever. Files: `first_night.json`,
the gather objective and spawner cap code, the Act 0 lives path, Lemon's Act 0 lines. Needs T2 and T4 merged.

### B2: Mending leaves the shop (review item 3)

Remove the Mending offer (`LibrarianNPC.java:224`, `VendorMath`); add `Bottle o' Enchanting` at `xpBottleEmeralds`
(knob, default 3) with limited daily uses; cap salvage material returns at one ingot or gem per three pieces of a tier
(`salvageMaterialPerPieces`, default 3, `SalvageMath`). Existing Mending gear is kept (owner recommendation, question 2
of the review). Tests: vendor stock, salvage math.

### B3: The door board says three things (review item 7)

Line 1 name, affixes, `Loot n%`; line 2 at most scrap (endgame only) and one item, the best promised piece; line 3 the
lives cost only when there is one. Palette words move to the floor start title. The rolls line leaves the board; the
deal sheet shows the same three lines. Files: `DungeonScreen.previewGets` and `previewNames`, `BoardText`. Coordinate
with T2 (merge after it).

### B4: The last hopper rooms leave the pool (review item 9)

`the_altar` becomes a campfire offering (place raw food on its campfire; when cooked, the altar opens); `sorting_floor`
loses its hopper channel (keep the room as dressing with a water lip per PD-214, or weight 0 if it no longer has a
verb). Read every hopper in `MechanismSpecs`, `SituationSpecs`, `SpurSpecs`, `CopperWorksRoomSpecs`: a hopper that is a
player verb is rebuilt around a vanilla vault or leaves the release pool; dressing stays. Follow the `room-building`
skill; regenerate the templates.

---

## Definition of done

- Every task in scope merged into `feature/campaign-endgame`; `tools/ship.sh --quick --no-dist` prints `SUITE GREEN`
  with a test count higher than the base's.
- `tools/check_dashes.sh` clean on every added line.
- No save format changed without a reader for the old shape and a golden fixture.
- The report in `docs/reports/campaign-endgame-build.md` lists deviations and live-only checks.
- Nothing pushed, nothing deployed, `master` untouched.
