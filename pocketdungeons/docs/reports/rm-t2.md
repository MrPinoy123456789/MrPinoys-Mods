# T2 Quest-line compass, no campaign scrap: report

Branch `feature/rm-t2-quest-compass` (off `feature/campaign-endgame`, merged at the start; nothing new to merge before the
final run). Spec: `docs/plans/2026-10-18-campaign-endgame-build-spec.md` T2. Rule: `docs/DECISIONS.md` "2026-10-18:
Campaign and endgame".

## What changed

- **Quest line** (`QuestLine`, new, pure): `Next: A, B or C` (three named, then `and N more`), `N more for the <capstone>`,
  `Next: the <capstone>` once it is open, `Next: the Astrolabe` when every open act is done. The lowest open act that is not
  complete drives it; the row order is the astrolabe's (level, then id) so the line and `Start here` agree. Also the locked
  door text `Finish N dungeons` / `Finish Act N`.
- **Keystone**: `showCompass` writes the quest line and the plain name `Compass` in the campaign, and the old compass bar
  and haul in the endgame (name and lore restored after the wake). `paintCampaign` covers a freshly minted stack: the
  hotbar copy a trip mints (`InventorySwap.keystoneFor`, one line) and a reconcile re-mint. The old "Clear floors for scrap"
  hint is dropped in the campaign.
- **No campaign scrap**: `RunLifecycle.payFloorMembers` pays 0 on a campaign trip (Act 0 included; keys and emerald
  redemption untouched). The board (`DungeonScreen.previewGets`) names no scrap for a campaign door. The sidebar
  (`SidebarLines.Facts.campaign`) drops the spacer, Haul and Compass lines. The finish milestone title drops its
  `Banked N scrap` line on a campaign trip.
- **Locked doors**: `HallRoom` signs and `DungeonMapText` lines read `Finish 3 dungeons` / `Finish Act 1`; open non-start
  doors show an empty second sign line instead of `Compass N`. The map's checklist marks the Endless Mine `(optional)`.
- **Retired knobs** (retired-keys list, fixtures and `config/pocketdungeons.default.json` regenerated): `actZeroScrap`,
  `firstLevelScrapCost` and `endlessMineUnlockLevel`. The spec's `compassCostFirstLevel` does not exist in code; the real
  knob was `firstLevelScrapCost` (the record's name is also retired). Compass 1 costs the curve's 4 again (`ScrapMath`).
  `ScrapMathTest`, `DungeonLogTest` and the `GatherFloorGameTest` assertion went back to the pre-Act-0 numbers.
- **Migration flag**: `DungeonLog.Compass.questLineSeen`, codec `quest_line_seen`, `optionalFieldOf` default false (omitted
  when false). Golden fixture `quest_line_seen.json` plus `.written.json`; every older fixture reads it as false. First
  join after the update (`RunLifecycle.onJoinHaul`): a player who finished Act 0 and has not finished the campaign gets
  `Your compass now shows the way. It will count again when the campaign is done.` once. T4 appends its line in
  `RunLifecycle.questLineJoinMessage()`. The old "Scrap now rides in your haul" intro is sent only to campaign-done players.
- **Wake beat** (`DungeonProgress.onProgress`): on the first finish that makes `Campaign.done` true, title `Your compass
  wakes up.` / `The Chart Room is open.`; the stored compass is untouched. `onProgress` also repaints the compass after
  every finish (so the Act 0 finish brings the first quest line).

## Decisions and flags for the integrator

- The pay rule is by trip, not by player: a player who has finished the campaign and replays an Act 1 dungeon earns no
  scrap there (`Campaign.isCampaignTrip(record)`). Say so if the owner wants replays to pay.
- `FirstVisitTutorial.Step.SCRAP` ("Two scrap bought you Compass 2") can no longer fire (Act 0 pays none); left in place,
  its test still passes. Lemon's knowledge pack should drop that line.
- `DialogScreens.compassScreen` (Inspect Compass) still prints the level and bar to a campaign player; not in T2's files.
- `rm-t0.md`'s note that a `Compass N` sign is expected is now moot.
- T3: only `livesColour` in `SidebarLines` is left for it; my `Facts` edit adds one trailing component with compatibility
  constructors, so its call site is unchanged.

## Tests

| | before | after |
|---|---|---|
| Pure test tasks (`--pure-fast`) | 124 green | 125 green (new `QuestLineTest`; cases added to `SidebarLinesTest`, `DungeonLogGoldenTest`) |
| Gametests | 357 (full run count minus mine) | 362 (new `QuestCompassGameTest`, 5 methods) |
| Full suite (`--quick --no-dist`, one run) | | 361 of 362 gametests passed, pure green; `trapped_cache_game_test_opening_the_cache_springs_one_ambush...` failed in the full run ("found 2 on tick 33") and passes alone (rerun once, green). The same test flaked in T0's runs. |

## Only a live server can confirm

See `docs/playtests/live-checks/rm-t2.md`: the item name and lore in hand after Act 0 and after each finish, the astrolabe
signs, no scrap anywhere in a campaign trip, the wake title once on the Ancient City, the endgame compass afterwards, and
the migration line once for an account with progress.
