# rm-t03: phased finales, Act 1

Branch `feature/rm-t03-phased-finales-act1`, worktree `.claude/worktrees/rm-t03`. Plan: Wave 1, F1.

## Spine change (first commit, bf2b2cba, merged as eff72e83)

`BossFight` only polled health once a watch tick, so one killing blow could skip every phase. It now hooks
`ALLOW_DEATH` lazily (first tick, no hot file touched): a killing blow on a boss with a phase still to come is
cancelled and health is pinned at the next bound (never above the health at the last poll). The last phase
still dies. Stagger also sends crit particles; `staggering()` added. `BossFightClampGameTest` covers it. A boss
is registered for the clamp on its first poll, so the first watch tick after the wave stands is unclamped.

## Mapping (plan table to code)

| Dungeon | Boss | Phase 2 (2/3) | Phase 3 (1/3) | Notes |
|---|---|---|---|---|
| Mineshaft | The Pit Boss: the wave's first zombie, adopted with `adoptElite` (40 hp, iron pickaxe); wave size unchanged | the creak, then the lights fail after the dark tell (was `onHalfDead`) | the second batch of glowing cave spiders (was bundled with the dark) | no JSON change; `FinaleDataTest` still says Act 1 has no data elite |
| Lush Caves | The Great Drip (already an elite) | `PotionDrip.setBoss(true)` (was on from the wave spawn) | arms the doubling of the first split | the boss can only die in phase 3 under the clamp, so its split is always the doubled one; a death outside the spine stays vanilla |
| Infestation | The Queen (already an elite) | the burrow cycle starts (phase 1 never digs) | brick clock at 200 percent (`CreepingBricks.Field.speedPercent`) | a stagger holds the cycle timer so it never overlaps a burrow |
| The Kennels | The Alpha (already an elite) | handler windup (was Alpha at half health) | pack grows if the handler lived, after the 5 s windup has run | new `KennelsFinaleRules.phasedStep`; `step` stays for the Brood Chamber |
| Ossuary | The Sexton: the first group's mob (skeleton), taken out of the coffins; wave size unchanged | coffins crack as a member passes | the skip rule (time alone cracks a coffin) | Sexton gone by any other route wakes every coffin at once (no soft lock) |

Pad gate unchanged: the pad opens when every wave mob is dead (Infestation: boss death clears the brood).

## Other edits outside the owned classes

`CreepingBricks.Field.speedPercent` (default 100, one line in the stage clock); `OssuaryRules.shouldWake` and
`choose` gained skip-rule overloads (old ones delegate); `FinaleScripts`, config and `fabric.mod.json`
untouched except the scaffold's one line. Names ("The Pit Boss", "The Sexton") are placeholders for the owner.

## Tests

Gametests (slots 10000 to 10046): `BossFightClampGameTest`, `MineshaftFinaleGameTest` (2),
`LushFinaleGameTest` (4), `InfestationFinaleGameTest` (3), `KennelsFinaleGameTest` (5),
`OssuaryFinaleGameTest` (7). Each dungeon has a three phases in order under burst damage test plus the pad
opening on the kill. Pure: `KennelsFinaleRulesTest` and `OssuaryRulesTest` extended. `CopperFinaleGameTest`
(the other `BossFight` user) passes, 8 of 8.

## Counts

See the final line of the reply; baseline not taken (shared machine OOM), the coordinator takes counts.

## Environment note

The Gradle OOM was the Jansi cleanup listing `A:/tmp`, which holds about 670,000 entries. Setting
`PD_TMP=A:/tmp/t03tmp` before `tools/ship.sh` avoids it. Nothing was deleted.

## Live only

`docs/playtests/live-checks/rm-t03.md`: the real feel of the stagger, real slime splitting under the doubled
split, the Queen's burrow with real pathing, wolf growth and the red team, real coffin lids, torch restoration.
One Infestation gametest failed once in five runs (phase three stagger assertion) and then passed four times;
not reproduced.
