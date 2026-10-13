# D6 live checks: T43 Spawner Dungeon capstone

Fragment for `docs/playtests/LIVE_CHECKS.md`. What the tests cannot prove and a player should.

## The Brood Chamber

- [ ] The final floor of the Spawner Dungeon is the Brood Chamber: four caged spawners, and the
      exit pad refuses entry to the next floor while any cage stands.
- [ ] Each cage freed releases exactly one earlier Act 1 mechanic, and the tell names it: coffins
      cracking ("Stone lids shift in the dark."), creeping bricks ("The mortar twitches."), the
      dark ("The beams creak." then "Light a torch."), then the drip ("Something drips from
      above.").
- [ ] A cage doused by the wave's exhaust rule frees its mechanic the same as a broken one.
- [ ] Breaking cages in different orders changes the fight: whichever cage goes first frees its
      mechanic first, and every earlier mechanic keeps running until the floor ends.
- [ ] The dark phase spares the torches near the pad and the room's middle; lights come back when
      the floor ends (win or loss).

## The Brood Mother

- [ ] The fourth cage calls "The Brood Mother hauls herself out of the dark." plus a title card,
      and a scaled up cave spider with a red boss bar arrives beside the last wave.
- [ ] Her bar fills the screen top, tracks her health, and vanishes when she dies.
- [ ] At half health a raid horn plays and "The Brood Mother shrieks for her brood!"; a few
      seconds later every surviving brood mob grows, glows and turns on the party. If she dies
      during the windup the pack stays its old size.
- [ ] Once she is up, the drip falls about twice as fast.
- [ ] The exit pad refuses until the mother and every tagged brood mob are dead, including any
      that chased a player out of the room.

## Edges and tuning

- [ ] A full chamber does not pile mobs over `broodMobCap`: new coffins, fish, spiders and wave
      mobs wait for room rather than crowding in.
- [ ] Leaving and re-entering the floor mid fight does not duplicate the mother, the bar, or any
      phase's mobs.
- [ ] The chamber works solo and at a party of four or more; her health and the headcounts scale
      but the fight stays readable.
- [ ] The other three capstones (Ancient City Warden, Wither Keep, Herobrine) play unchanged.
