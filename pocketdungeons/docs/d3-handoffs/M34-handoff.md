# M34 - Weekly bounties for party leaders - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

1. `DungeonLog.java` lines 127-131, 235-260: `Entry`,
   `recordCompletion`. Bounty progress persists here.
2. `InstanceRecord.java` lines 36, 51: `owner`, `members`.
   Party structure.
3. `RunLifecycle.java` lines 750-876: `completeRun`. Hook.
   Has `record.owner`, `record.members`.
4. `archived/dailyquests/.../DailyState.java` lines 75-94:
   `dayKey` pattern. Adapt to ISO week.
5. `archived/dailyquests/.../Quests.java` lines 41, 146-198:
   `Quest` record, pool pattern.

## Dependencies

Grep `weekly` in `src/main/java/`: one comment, no code.
Grep `DungeonLog.Entry`: 16 fields. Bounty progress as
sidecar `Map<UUID, List<BountyState>>`, codec `bounties`.
`BountyState`: weekKey, bountyId, progress, completed.
Grep `Payout.deliver`: used in `Fuel.grant`. Pattern for
multi-player rewards.

## Goal

Weekly bounties for dungeon host (instance owner). Party
contributes progress. All online members get reward on
completion. Three bounties per week, seeded from owner UUID +
week key. Inspired by archived dailyquests.

## Bounty pool (three per week per owner)

1. Clear the Halls: clear 20 spawners this week.
2. Echo Harvester: bank 9 echo shards.
3. Speedrunner: complete 3 timed runs.
4. High Roller: spend 32 emeralds at gamble station.
5. Spelunker: complete 2 runs via Greater door.
6. Pack Hunter: complete 3 runs with 2+ members.
7. Keystone Climber: gain 3 keystone levels.

Three seeded from `owner.hashCode() ^ weekKey`. No dupes
within week. Reward: 2 echo shards + 4 emeralds per online
member. Owner +1 shard bonus.

## Implementation plan

Each step one commit. Run `build_mod` after each.

### Step 1: BountyTracker and sidecar

1. New `BountyTracker.java`: enum `Bounty(id, label,
   targetCount)`. `weekKey()`: `YearWeek.now(UTC)`,
   `"yyyy-Www"`. `bountiesFor(owner)`: three seeded.
2. `BountyState(weekKey, bountyId, progress, completed)`.
3. `progress(server, owner, bountyId, amount, members)`:
   increments, checks completion, `Payout.deliver` to online
   members, owner bonus.
4. `DungeonLog`: add `Map<UUID, List<BountyState>> bounties`.
   Codec: optional `bounties`, default empty.
   `bountiesOf(owner)`: list, empty if stale weekKey.
   `setBounty(owner, index, state)`.
   `bountyLine(owner)`: `Component` list, `AQUA`.

### Step 2: Scoreboard

1. `syncScoreboard(server, owner)`: three objectives
   `pd_bounty_0/1/2`, `Criteria.DUMMY`, slot `SIDEBAR`.
   Score: progress. Name: bounty label.
2. Created when owner online in dungeon. Removed on logout.

### Step 3: Hook into events

1. `completeRun` line 750: progress `CLEAR_HALLS` by
   spawners. `SPEEDRUNNER` if timed. `SPELUNKER` if
   `step>=2`. `PACK_HUNTER` if `members.size()>=2`.
2. RitualListener line 168: after `Fuel.bank`, progress
   `ECHO_HARVESTER`.
3. `GambleStation.onUse`: progress `HIGH_ROLLER` by
   emeralds. Owner from `byMember.get(player).owner`.
4. `DungeonLog.setKeystone`: on level up, progress
   `KEYSTONE_CLIMBER` by delta.

### Step 4: Door screen and login

1. `idleContent`, `previewContent`: append
   `bountyLine(owner)` after task line (M33).
2. Join: if owner, sync scoreboard, chat with bounties.
3. Completion: broadcast to party in green.

## Constraints

- Bounties belong to owner. Party member contribution counts
  toward owner's bounties.
- Three per week. Seeded, not chosen. No reroll.
- ISO week, resets Monday UTC.
- Rewards to online members at completion. Offline miss out.
  Owner +1 shard.
- Stale weekKey: reset.
- No new commands. Door screen + scoreboard only.
- `BountyTracker` new class. Existing: `progress` calls at
  hooks only.
- M33 tasks and M34 bounties coexist.

## Verification

- `build_mod` default `build` after each step.
- Headless: `BountyTrackerTest`: week key, seeding,
  progress, rewards, stale reset. `DungeonLogTest`:
  sidecar round-trip. Existing pass.
- Live: owner sees three bounties. Party member contributes.
  Bounty completes: all online members rewarded. Next week:
  new bounties. Record in `LIVE_TEST_PASS.md`.
- Done when: three weekly bounties per owner, party
  contribution, shared rewards, scoreboard, door screen,
  week reset.

## Completion

1. Append summary to `plans/COMPLETED-MILESTONES.md` (append only).
2. Append section to `docs/reference/LIVE_TEST_PASS.md` (live-only).
3. Update `docs/reference/ROADMAP.md` if scope diverged.
4. Rename to `M34-handoff-completed.md` once landed.
