# D4 live checks: T41 Act 1 Endless Mine guardians

Fragment for `docs/playtests/LIVE_CHECKS.md`. What the tests cannot prove and a player should.

## Floor 3, the young queen

- [ ] Entering floor 3 of the Act 1 mine, the terminal room shows crack dust and a rumble, then a
      silverfish about one and a half times normal size named "The Princess" surfaces and fights.
- [ ] Mossy bricks around the room ripen and hatch silverfish adds while she fights.
- [ ] The exit pad refuses while she or any of her brood lives: "The guardian still stands. Finish it
      first."
- [ ] With the last fish dead the pad opens and the floor pays the tier A guardian table.

## Floor 6, the packmaster

- [ ] A named pillager ("The Packmaster") waits with a ring of wolves; a horn sounds on entry.
- [ ] The wolves are angry from the start, not just on hit.
- [ ] Killing the packmaster first quiets the pack ("The pack goes quiet") but the pad stays shut
      until the wolves are dead too.
- [ ] Tier B guardian loot on clear.

## Floor 9, the coffins

- [ ] Upright dark coffins stand around the terminal room; "Stone lids shift in the dark." plays.
- [ ] They crack open one at a time, never two at once, and each sleeper stands up and hunts.
- [ ] Breaking a coffin's blocks opens it early.
- [ ] The pad stays shut until every sleeper is dead; tier C guardian loot on clear.

## Lap two

- [ ] Floors 12, 15, 18 repeat queen, pack, coffins in order, noticeably harder: more bricks, more
      wolves, more coffins, faster tells (but tells never feel under two seconds).

## Edges

- [ ] Leaving and re-entering a guardian floor mid fight does not duplicate or lose mobs.
- [ ] `mineGuardianMobCap` lowered to a small number trims waves without errors.
- [ ] Act 2's mine still plays the plain placeholder wave (skeletons) on its guardian floors.
