# Live checks: RM-T11 (Overclocked pays for speed, Loaded is trapped)

Fragment for the build's live checks list. Needs a server with a keystone high enough to deal Loaded (level 25 or more).

## Overclocked

1. Take a door whose floor carries Overclocked. The floor title lists OVERCLOCKED, then "Under 6:00: +1 scrap".
2. Clear the floor in under 6 minutes of the commit. The pay line reads one scrap more than the same floor without the affix (act and step as dealt). The haul agrees.
3. Repeat and clear it slower than 6 minutes. The pay is the plain dealt step.
4. Spawners still come back fast (cooldown factor 0.4 unchanged).
5. Check the knobs `overclockedParSeconds` and `overclockedBonus` in `config/pocketdungeons.json`. A value there overrides the default. The affix blurb names 6 minutes and +1 in fixed words, so change the blurb with the knob.

## Loaded

1. On a Loaded floor, find the tool chest in a loot room. Walk up to it: nothing happens until you open it.
2. Open it. You hear the soul-escape sound, smoke puffs at several spots near the chest, and "Trapped." shows red on the action bar. About a second later 3 mobs (zombies and skeletons) stand at those spots and come for you. The chest still holds its tools and bread or torches.
3. Close it and open it again: no second ambush.
4. Party of 2 and 3: 4 and 5 mobs.
5. The mobs stand in the room corner by the chest, never in the middle cross where the doors line up. Walk out of the room by every door: none is blocked.
6. The ambush mobs drop nothing.
