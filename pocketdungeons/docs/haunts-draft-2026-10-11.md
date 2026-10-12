# Haunts: a floor wide stalker mechanic (draft, 2026-10-11)

Status: draft for the owner. Nothing is built. Born in the concepts workshop, replacing the one room hunter ("The Wake" as a single Ordeal).

## One sentence

A haunt is a creature that roams a whole floor, follows one simple rule the player can learn, and engages the player now and then, the way a Lethal Company monster does.

## How it differs from what exists

| Mechanic | Scope | Lives in |
|---|---|---|
| Ordeal | one room, ends with a lever | `Ordeal.java`, `Ordeals.java` |
| Affix | the whole run, a rule bent | `dungeon_affix/*.json` |
| Heard meter | one sculk room, sound fills a bar | `SculkOmen` |
| **Haunt** | **the whole floor, a creature with a rule** | new |

## The parts

1. **A definition per haunt** (data, a closed set of behaviour kinds, like affixes): id, label, which themes and compass levels, its behaviour kind, its tell (particles, a sound), its strike (damage or an effect), its counter, how long it retreats after a strike.
2. **A director** that runs per floor. It decides when a haunt wakes, from a pressure number that rises with time on the floor, omen and noise, and falls when the party clears rooms. One haunt per floor at a start, capped by party size.
3. **A closed set of behaviour kinds**, each a few lines of server tick code:
   - `HUNT_IN_WATER`: only targets players standing in water (the Wake).
   - `STALK_WHEN_UNSEEN`: moves only while no player looks at it (dot product of the look vector); strikes if reached.
   - `DRAWN_BY_SOUND`: walks toward the loudest recent noise (ties to the Heard meter).
   - `FOLLOWS_LIGHT`: drawn to lit torches and lanterns, avoids the dark.
4. **Fairness rules, fixed for every haunt:**
   - always a tell before a strike (a sound or particles, at least about 2 seconds);
   - always a counter the player can learn without dying;
   - after a strike it retreats, so it never chain kills;
   - never active in the entrance room, the lodestone pad, an armed Ordeal's lever room, or during a finale;
   - a death is an ordinary death: a life, as everywhere.
5. **Legibility (owner decision 2026-10-11).** The floor title says "Something walks here." The first time a haunt gives a real tell, one chat line states the rule in few words (for example "It hunts in water."). A diary entry per haunt gives the story. Per the player-text skill. The rule is learned on first contact, not by dying.
5a. **Slow introduction (owner decision 2026-10-11).** Horror game pacing. On the first floor of a dungeon the haunt stalks the player but does not attack, and vanishes, several times: seen at the edge of a room, gone when approached, its tell (particles, a sound) left behind. Only later does it strike. How the ladder is counted (by floor or by encounter) is open, see Open questions.
6. **Scaling.** Compass sets the strike and the number of haunts; ominous adds one; party size raises the cap.

## Vanilla limits (all server side, no client mod)

- Bodies are ordinary mobs (drowned, skeleton, zombie, enderman) with the invisibility effect or a held pose; the tell is particles and sounds sent from the server.
- Looking detection is the server comparing the look vector with the direction to the mob.
- No custom model or animation. Anything that needs one is out.
- Needs a tick hook per active haunt; the cap keeps cost low. No mixin is expected.

## First haunts (one per theme, to test one at a time)

| Haunt | Theme | Rule | Counter |
|---|---|---|---|
| The Wake | Prismarine | hunts players in water, shown by bubbles and a hum | stay on dry tile, or hit the ripples |
| The Counted | Ossuary | moves only when unseen | keep it in view, back away |
| The Listener | Deepslate and Ancient City | walks toward noise | go quiet, sneak, throw bait |
| The Tenant | Lush Caves | follows light | douse torches, use the dark |

## Build cost

- Director and definition loader: M.
- Each behaviour kind plus its tell: S to M.
- First test: The Wake only, in Prismarine: M in total.

## Risks

- Feels like a second Heard meter or omen; keep the pressure number separate and visible, or fold it into omen. Open.
- A haunt that cannot be seen or heard is unfair; every haunt needs a playtest of its tell.
- Griefing a party by pulling a haunt onto a teammate; the retreat rule covers it.

## Open questions

1. DECIDED 2026-10-11: counted by floor (floor 1 sighting, floor 2 near miss, floor 3 onwards strikes). Consequence: a 2 floor dungeon (Cow Pits) never reaches a strike, so it carries no haunt, or only teases. Options considered: by floor, by encounter, by pressure. How the slow introduction is counted: by floor (floor 1 sighting, floor 2 near miss, floor 3 on strikes), by encounter (the first few meetings are teases whatever the floor), or by pressure. Run length varies from 2 to 5 floors, which favours counting by encounter.
2. Whether pressure is its own number or the existing omen.
3. Whether a killed haunt returns, and what killing one pays.
