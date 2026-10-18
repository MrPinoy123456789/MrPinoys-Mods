# T3 Lives grow with the party: report

Branch `feature/rm-t3-party-lives` (off `feature/campaign-endgame`, T0 and T1 merged). Spec: `docs/plans/2026-10-18-campaign-endgame-build-spec.md` T3.

## What changed

- **`Omen`**: `maxLives(members, perExtraMember)` (5 solo, plus the knob per extra member up to four members; a fifth adds nothing),
  `lives(omen, max)`, `nextDeathFails(omen, max)`, `clampTo(omen, max)`, `add(current, n, max)` and `livesColourIndex(lives, max)`.
  The one argument `lives` and `nextDeathFails` are gone. `LIVES` stays as the solo base.
- **`IntervalState`**: field `maxLives` (default 5) and `settleMaxLives(members)`, which follows the party size until the first door is
  stamped (`dungeonId` set, or an Endless Mine trip started) and then does nothing, so later joiners add nothing. `omenSum` and
  `bankedOmenSum` clamp to the trip's max.
- **Knob** `livesPerExtraMember` (default 1, 0 to 5) via `tools/new_knob.py`; fixtures and `config/pocketdungeons.default.json` updated.
- **Callers**: `RunLifecycle.livesLeft(record)` (settles, then reads) replaces the `Omen.lives(...)` calls in `RunLifecycle` (3),
  `RitualListener` (2) and `SidebarDisplay`. `Instances` death path and `RunLifecycle` door cost use the trip max. `OmenBar`,
  `OmenBarText`, `IntervalBanking.homeScreen`, `DungeonScreen`, `PlayerContext` pass the max. The "run failed" omen journal total is now
  the max (it was `MAX_OMEN + 1`, the same 5 for a solo trip).
- **Colour**: green above 3/5 of the lives, yellow above 1/5, red below. Solo five is unchanged (5 and 4 green, 3 and 2 yellow, 1 red).
- **`SidebarLines`** (T2's file, kept tiny): `livesColour(lives, max)`, one new trailing `Facts.maxLives` component with a 19 argument
  overload defaulting to 5 so existing calls compile, and its one call. `SidebarDisplay` passes the max.
- Blood doors and the Blood affix still cost a flat life and the last life is never for sale (`DoorLives` rule unchanged).

## Design notes

- Danger is unchanged: `MobScaling` and the ominous roll still read `Omen.clamp`, which caps at four deaths, so a bigger party that
  dies five or more times does not make mobs harder than four deaths do. The ominous roll chance is still deaths over four, capped at 1.
- The max is in memory like the omen (a restart loses both, as before). A fresh trip builds a fresh `IntervalState`.
- Settling also runs from `OmenBar.sync` and the door gates so the bar reads the right number before the first commit.

## Tests

| | before | after |
|---|---|---|
| pure tasks (`--pure-fast`) | 124 green | 124 green |
| `FloorLoopGameTest` | 4 | 5 (new `livesFreezeAtTheFirstDoor`) |
| `HaulGameTest` | 7 | 7 |

Extended: `OmenMathTest` (solo 5, two 6, four 8, a fifth still 8, the knob, colour bands, caps), `OmenBarTextTest` (an eight life trip),
`DoorLivesTest` (bigger party), and the existing tests updated for the new signatures. Final full run result is in the reply.

## Live only

Fragment `docs/playtests/live-checks/rm-t3.md`: two accounts, bar shows 6, later joiner adds nothing, colour bands, run ends after the last life.
