# Report: T1 crew progression

## Branch

- Branch: `devin/t1-crew-progression`
- Base: `4fc4422c` (`master`)
- Tip: `80656c64`

## Test counts

| Suite | Before | After |
|---|---|---|
| `./gradlew test` (pure) | all green | all green (DungeonLogTest, DungeonLogGoldenTest, EntrySubRecordTest, EndlessMineRulesTest updated) |
| `./gradlew runGameTest` (All N required tests passed) | 397 | 399 |
| `./gradlew dungeonIntegrationTest` | PASS | PASS |

Counts confirmed by the full suite run on `devin/t2-leader-grace`, which contains every T1 commit; the per-branch numbers below that run are identical for T1's code.

## What changed

- `DungeonLog.java`: new `finish_bonus_claimed` sidecar (codec key `finish_bonus_claimed`, default empty), `claimFinishBonus`, `finishBonusClaimedOf`, and the migration seed from `dungeons_finished` when a player is not listed (skipping empty sets so old saves do not grow rows).
- `DungeonProgress.java`: `onProgress` evaluates the record owner's save and repaints every online member's compass; act-opened chat and milestone titles go to every member present; added the pending evaluation key used when the owner is offline at finish time.
- `RunLifecycle.java`: `finishDungeon` and `recordMineDepth` write campaign progress to `record.owner` only; every member claims their own first finish bonus through `claimFinishBonus`; `onJoinHaul`/`join` runs the pending leader evaluation when the owner returns.
- `EndlessMineRules.java`: dive difficulty reads the leader's `highestCharts`, not the party minimum; the collection overload stays for pure tests.
- `CrewProgressGameTest.java` (new, registered): two tests pinning leader-only writes and once-each finish bonuses.
- `LeaderGraceGameTest` registration slot was added on T2; not in this branch.
- `DungeonLogTest.java`, `DungeonLogGoldenTest.java`, `EndlessMineRulesTest.java`, `full_current.written.json`, `quest_line_seen.written.json`: fixtures and assertions for the new field and the seed.

## Decisions I made

- Seeding skips players with no finished dungeons, so a save that predates the field does not grow empty `dungeons: []` rows. An explicit `finish_bonus_claimed: []` decodes identically and is still seeded; distinguishing the two would need a different codec shape for no semantic gain.
- `finishBonusClaimedOf` is package-private, matching the other sidecar accessors used by tests.
- No `TODO(owner)` entries.

## Deviations from the spec

None.

## Only a live server can confirm

- Live check T1.1: two players in one run finish a dungeon; both get the first finish payout, and only the leader's journal/compasss progress moves. Re-run the same dungeon: both get the repeat rate.
- Live check T1.2: the leader disconnects mid-run and a guest finishes a dungeon that opens an act; when the leader rejoins, their save shows the act opened (deferred evaluation) and every member's compass shows the same next objective.
- Live check T1.3: an Endless Mine dive offers floors at the leader's compass depth even when a guest's compass is lower.

## Expected merge conflicts

- `DungeonLog.java` codec parts and sidecar maps; `RunLifecycle.finishDungeon`/`recordMineDepth`; `DungeonProgress.onProgress`; `EndlessMineRules.diveCompass`; the dungeonlog fixtures; `fabric.mod.json` gametest list (every task appends a test).
