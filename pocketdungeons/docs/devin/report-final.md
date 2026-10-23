# Final report: party campaign, glyphs, rewards, grace and terminal

Six chained tasks, one branch each, built in order. Reports per task are
`report-t1.md` through `report-t6.md` in this directory.

## Branches

| Task | Branch | Tip | Report |
|---|---|---|---|
| T1 crew progression | `devin/t1-crew-progression` | `8e63e76a` | report-t1.md |
| T2 leader reconnect grace | `devin/t2-leader-grace` | `dd3984f6` | report-t2.md |
| T3 glyph framework | `devin/t3-glyphs` | `81bc839c` | report-t3.md |
| T4 astrolabe as glyph | `devin/t4-astrolabe-glyph` | `72e4a3a1` | report-t4.md |
| T5 act data and rewards | `devin/t5-act-data-rewards` | `5573f26e` | report-t5.md |
| T6 terminal | `devin/t6-terminal` | `5470829d` | report-t6.md |

Each branch was created from the previous task's branch, so `devin/t6-terminal`
contains the whole feature set.

## Final test counts (t6 tip)

| Suite | Base commit `4fc4422c` | Final |
|---|---|---|
| `./gradlew build` | green | green |
| `./gradlew test` (pure) | 130 tasks | 132 tasks, green |
| `./gradlew runGameTest` | All 397 required tests passed | All 413 required tests passed |
| `./gradlew dungeonIntegrationTest` | PASS | PASS |

New GameTests added across the six tasks: `CrewProgressGameTest`,
`LeaderGraceGameTest`, `GlyphGameTest`, the guest-turn test in
`HallRoomGameTest`, `ActRewardGameTest`, `TerminalGameTest`. New pure tests:
`ActRewardDataTest`, `TerminalSourceTest`, the codec migration checks in
`DungeonLogTest`, and updated `ShellPaletteTest` / `RoomFurnitureTest`.

## What the six tasks delivered

- T1: the party plays the leader's campaign. Only the leader's `DungeonLog`
  records dungeon finishes, mine depth and act progress; present members share
  the credit line and quest tracker; deferred evaluation runs when the leader
  rejoins.
- T2: 10 minute leader reconnect grace. Run close is held while the leader is
  inside the grace window; the commit gate checks it; early close still works
  when everyone is out; new strings per spec.
- T3: the `Glyph` framework: an interaction anchor plus persistent attachment
  and tagged visuals, one right-click dispatcher, named handlers
  (`require_item`, `cycle`, `toggle`, `run_once`, `inspect`), per-run
  `record.glyphState`, and `/dungeon admin glyph require`.
- T4: the astrolabe rebuilt as a glyph. Any party member can turn it; the old
  owner-only `UseEntityCallback` path is gone.
- T5: `act_reward` data manifest plus `ActRewards` delivery: every present
  member gets title, music disc, window kit, decor kit, and Act 1 the Old Mine
  room shell, once per player, with back pay on rejoin.
- T6: the staging-room terminal: a 12 by 5 wall left of the selector with
  Objectives, Progress and History tabs driven by glyph handlers, one lit
  stained-glass window per open act, and the floor history board's content
  preserved on the History tab.

## Deviations from the spec

- `minecraft:chain` does not exist in 26.2: Act 1 decor uses
  `minecraft:iron_chain` (same item, current registry name).
- `minecraft:blackstone_bricks` does not exist in 26.2: Act 4 decor uses
  `minecraft:polished_blackstone_bricks`.
- No other deviations; every task reports "None" besides these two item-id
  substitutions forced by the registry.

## TODO(owner) entries

None. No task needed a `TODO(owner)` placeholder; all player-facing words came
from the spec.

## Live checks for the owner

- Party of two: only the leader's log advances, but both players' Compass
  quest lines move together and both get the credit line on each finish.
- Leader disconnects mid-run: the run holds for 10 minutes; commit is gated;
  rejoin inside the window resumes cleanly, after it the run closes.
- `/dungeon admin glyph require <item>`: right-click with the item consumes
  it, anything else shakes and refuses; clearing the room removes the glyph.
- Staging room: any member right-clicks the astrolabe and the act turns.
- Finish Act 1 with a party: everyone present gets the toast, chat lines,
  Delver nameplate prefix, disc, both bundles and the Old Mine shell; rejoin
  back-pays once.
- Terminal wall: 12 by 5 panel with three tabs; Objectives shows the shared
  quest line, Progress shows acts/mine/lives/haul, History shows the old
  ledger; lit windows mark open acts; locked-act clicks refuse.
- Teardown: restamping a staging room leaves no stray displays or glyph
  entities.

## Note on the AoE telegraph work

Earlier commits on `master` (notably `4fc4422c`, pushed before this six-task
effort branched) added `AoEShapes`/`AoETelegraph`, the shared party Compass,
and telegraphed attacks for the Queen, the Princess, the Brood Mother and the
Great Drip. That work predates the task chain and is on `master`, not on the
`devin/` branches.
