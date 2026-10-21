# Tasks

Build in this order. Each task is one branch, created from the previous task's branch. Section numbers point into
`01-SPEC.md`. A task is done when its acceptance list holds and `build`, `test` and `runGameTest` are green.

| Id | Branch | Spec | Depends on |
|---|---|---|---|
| T1 | `devin/t1-crew-progression` | 1 | base |
| T2 | `devin/t2-leader-grace` | 2 | T1 |
| T3 | `devin/t3-glyphs` | 3 | T2 |
| T4 | `devin/t4-astrolabe-glyph` | 4 | T3 |
| T5 | `devin/t5-act-data-rewards` | 5, 6 | T4 |
| T6 | `devin/t6-terminal` | 7 | T5 |

## T1: crew progression

Make campaign progress the party leader's only (spec 1).

- Files: `RunLifecycle` (`finishDungeon`), `DungeonProgress`, `DungeonLog` (new `finishBonusClaimed`),
  `EndlessMineRules`, the Endless Mine floor clear path, a join hook for an offline leader's evaluation.
- Acceptance:
  - A guest's `dungeonsFinished`, `actsUnlocked`, mine depth and campaign flags never change from a run they join.
  - First finish bonuses are paid once per player per dungeon, guests included.
  - The act opened line and title reach every member present.
  - Existing saves migrate (`finish_bonus_claimed` seeded from `dungeonsFinished`).
  - Tests from spec 1.5.

## T2: leader grace

Spec 2. `PocketDungeonsConfig` default 600, `RunLifecycle.holdForOwner` and `watchOwnerGrace`, the new strings.

- Acceptance: the hold lasts 600 s by default; guests all leaving during a hold ends the run within one watch tick; a
  leader alone in their run is unaffected; no door can be committed while the leader is away; the 5 and 1 minute warnings fire once each; test from spec 2.

## T3: glyphs

Spec 3. New `Glyph`, `GlyphHandler`, `GlyphHandlers`, `GlyphContext`, `GlyphResult`; the attachment; the single
dispatcher; the five ready-made handlers; `record.glyphState`; the `/dungeon admin glyph require <item>` command.

- Acceptance: tests from spec 3.7; a glyph spawned by the admin command survives a server restart and still works.

## T4: astrolabe as a glyph

Spec 4. `HallRoom` only (plus the dispatcher registration).

- Acceptance: the astrolabe looks and behaves as before, except any member may turn it; the old callback is gone;
  `HallRoomGameTest` updated and green. The terminal hook (set `terminalAct`, refresh) is a no-op stub until T6.

## T5: act data and rewards

Spec 5 and 6. The `act_reward` data type and its six files, validation, the `old_mine` shell, `actRewardsClaimed`,
`titlesEarned`, delivery, titles as teams, back pay on join, the reward title and chat lines.

- Acceptance: tests from spec 6.5; the shell menu shows Old Mine locked with `Finish Act 1` until earned.

## T6: the terminal

Spec 7. New `Terminal`; `StagingFurniture` panel area (12 by 5); `DungeonScreen.updateHistory` and `summonHistory`
delegate to it; `InstanceRecord.terminalTab` and `terminalAct`; handlers `terminal_tab` and `terminal_page`; reward
icons with `inspect`; the astrolabe hook from T4 made real.

- Before building, check the wall (spec 7.2 "Check first") and report what is on along 2, 3, 12 and 13.
- Acceptance: tests from spec 7.8; every element in spec 7.3 to 7.6 at the stated position, scale and colour;
  screenshots are not possible headless, so in the report list each element with the coordinates you used.
