# D5 live checks: T42 Act 2 Endless Mine guardians

Fragment for `docs/playtests/LIVE_CHECKS.md`. What the tests cannot prove and a player should.

## Floor 3, the Deep Sentry

- [ ] Entering floor 3 of the Deep Mine (Act 2 endless mine), the terminal room holds sculk sensors
      and a named bow skeleton, "The Deep Sentry".
- [ ] Fighting fills an invisible meter: shooting a bow, taking hits and sprinting push it faster,
      and "The sculk stirs." shows as it nears full.
- [ ] A full meter plays a heartbeat and "The deep heard you.", then about three seconds later
      darkness lands and a small Warden (a whelp, not the Warden) comes for the nearest player.
- [ ] While the whelp is out the meter is quiet; a second answer only comes after it is gone.
- [ ] The exit pad refuses while the sentry or any answer lives: "The guardian still stands. Finish
      it first." Tier A guardian loot on clear.

## Floor 6, the Foreman

- [ ] The terminal floor shows copper grates and one water cauldron; a named vindicator, "The
      Foreman", stands among them.
- [ ] A vent hisses and smokes for two seconds, then fires; standing on it ignites.
- [ ] The vents sweep the floor in a serpentine order, faster the deeper the guardian.
- [ ] Pushing or kiting the Foreman into the cauldron splashes, weakens and slows him ("The vat
      weakens him. Keep him in it." shows once).
- [ ] When he dies the vents stop and the floor looks untouched (no leftover grates or cauldron);
      tier B guardian loot on clear.

## Floor 9, the Frostbite

- [ ] Lit campfires stand around the terminal room and a named stray, "The Frostbite", hunts with
      her kin.
- [ ] A wind sound and "The cold is coming. Find a fire." play, then about three seconds later the
      freeze starts: the blue overlay builds on anyone away from a fire.
- [ ] Standing near a lit campfire thaws fast; breaking or dousing a fire removes that shelter.
- [ ] The cold cannot kill on its own (the same health floor as the Frostworks finale).
- [ ] Killing the last stray lifts the cold at once and the pad opens; tier C guardian loot.

## Lap two and edges

- [ ] Floors 12, 15, 18 repeat Sentry, Foreman, Frostbite noticeably harder: the meter fills sooner,
      more vents firing faster, more strays, faster tells (never under about two seconds).
- [ ] Leaving and re-entering a guardian floor mid fight does not duplicate or lose mobs, and does
      not restart a tell that already ran.
- [ ] `mineGuardianMobCap` lowered keeps the wave sizes sane (the sentry's answer waits instead of
      spawning over the cap).
- [ ] Act 1's mine still plays its own guardians (queen, packmaster, coffins) untouched.
- [ ] The Frostbite is never announced, named or described as a Warden anywhere.
