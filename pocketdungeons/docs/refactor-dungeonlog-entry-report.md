# Refactor report: DungeonLog.Entry

Branch: `refactor/dungeonlog-entry` (off `feature/bug-testing-and-refinement`, nothing pushed).

## Commits

1. `37d8f24` Golden fixtures for the player save record (test only, written against the old flat code).
2. `7e276a5` `EntryWitherTest`: pins what every `withX` and `resetCampaign` change (old code).
3. `a85d79c` Entry regrouped into six sub-records.
4. `779975b` Dead `bounties` sidecar removed from the in-memory log.
5. `6e67d0f` `EntrySubRecordTest`: groups round trip alone; unknown fields ignored.

## Sub-record layout (in memory only)

`Entry(Ladder ladder, ThemeTrail themes, Compass compass, RoomSettings room, Unlocks unlocks, Campaign campaign)`

| Group | Fields |
|---|---|
| Ladder | runsCompleted, bestPathLength, bestKeystoneLevel, keystoneLevel, keystoneAffix, pendingOfferLevel, keyProgress |
| ThemeTrail | recentThemes, completedThemes, currentTheme, depth |
| Compass | highestCharts, chartProgress, haul, haulIntroSeen (owns the bar settling rule) |
| RoomSettings | publicListed, roomName, roomCompletions, recentVisitors |
| Unlocks | extractedPowers, unlockedShells, diaryBandsSeen, dungeonsFinished, bag, kitGranted, fuel |
| Campaign | unchanged existing record |

Every old accessor (`entry.runsCompleted()` etc.) and every `withX` keeps its name and signature, delegating
to the group, so no caller outside `DungeonLog.java` changed. Each record clamp moved into the group that
owns the field. A wither now copies one small group and shares the other five. The wire codec
(`PartA`, `PartB`, `PartC`, `ENTRY_CODEC` encode side) is untouched; only the decode lambda builds the
groups instead of the flat record. `NONE` and `resetCampaign` use per-group `EMPTY` constants.

## Fixtures (`src/test/resources/dungeonlog/`)

Each has an input file and a `.written.json`, the form the OLD code wrote after loading it.

| Fixture | Shape covered |
|---|---|
| `full_current` | every key, non default; a player row plus a task_progress sidecar; input equals the written form (minus the read only `scrap`) |
| `oldest` | only the two required keys (`runs`, `best_path`), pre-U8 `streak` / `last_completed`, and unknown keys at entry and top level |
| `legacy_fields` | superseded keystone_affix, recent_themes, current_theme, depth, fuel; old `scrap` pool migrating the compass bar; pre-acts save at keystone 20 running the acts migration |
| `clamps` | negative numbers, a malformed decide_list id, a bar holding whole levels that settles into the compass |
| `with_bounties` | live task_progress plus the dead `bounties` and retired `storage_returns` lists |

## Compatibility proof (`DungeonLogGoldenTest`)

For every fixture: (a) decode the input and compare a snapshot of every accessor (all 27 plus the campaign
fields) to hand computed values; (b) re-encode and compare to the checked in written form; (c) decode the
written form and get the same snapshot, so a rollback reader (same fixture logic) accepts what the new
code writes; (d) encode that again and get the same written form; (e) the entries are equal; (f) the NBT
round trip keeps the values. Because the fixtures were committed before the refactor and unchanged by it
(except the deliberate bounties case below), (b) and (c) passing at the tip proves the written form is
byte for byte the old one (sets and player rows are order normalised, as hash order is not stable).

## One deliberate format change, as the task directed

Bounties: the old code wrote the `bounties` list back; the new code accepts any value in that field
(`Codec.PASSTHROUGH`, so even a malformed one loads) and writes nothing. Verified: a save carrying it loads,
is written without it, and the rest of the save is identical. An older mod version still reads the new save
(the field was optional). `with_bounties.written.json` and `FloorHistoryTest.testLegacySidecarsSurvive` were
changed to assert this (that assertion previously said bounties are kept; it is outside my listed files,
edit kept to one assertion and its comment). Bounty data in existing saves is lost on the next write; it was
dead data.

## Notes

- `EntryWitherTest` did not exist in this tree; I wrote it (commit 2) against the old code and it passes
  unchanged on the new.
- A pre-existing decode rule shows in `EntrySubRecordTest`: loading raises the compass to the keystone, so a
  log with keystone above `highestCharts` is not entry-equal after a reload. Old behaviour, kept; the test
  states it.
- The fixtures assume the default scrap price knobs (base 4, every 3) for bar settling and the old pool
  migration. If the config agent changes those defaults the `clamps` and `legacy_fields` expectations move.

## Test counts

- Gametests: 241 run before and after. Two fail identically at the starting HEAD and at every commit here:
  `vendor_game_test_vendor_offers_carry_the_cap_mending_and_half_rate_buys` and
  `playtest_fix_game_test_handed_over_gear_is_durability_capped` (dungeon durability cap on gear). They
  are unrelated to DungeonLog; I did not touch them, so the usual `All 241 required tests passed` line
  is not reached on this tree. Likely environment or config (the run dir logs a missing config field).
- Plain JDK test tasks: three added (`dungeonLogGoldenTest`, `entryWitherTest`, `entrySubRecordTest`);
  all of them, and every other `test` task, pass at the tip.

## Not verifiable without a live server

Loading a real `dungeon_log.dat` from the Kinetic test server. The fixtures are hand built from the codec,
not copied from a live save; a diff of a real save before and after the new jar would be the last check.
