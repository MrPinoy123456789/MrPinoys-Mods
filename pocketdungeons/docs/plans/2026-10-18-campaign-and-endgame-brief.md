# Campaign and endgame: option brief (2026-10-18)

Design only, for the owner to approve. Nothing is built and `DECISIONS.md` is untouched. Follows the
`design-brief` loop: frame, research, options with one recommendation, then a spec ready to record once
approved. Builds on `docs/plans/2026-10-17-design-review.md` and changes three of its items (see Supersedes).

**Approved by the owner on 2026-10-18**, with every recommendation below and option a for Dungeon Storage
(question 1). Recorded in `docs/DECISIONS.md` as "2026-10-18: Campaign and endgame". The owner's own choices:
the compass is a quest line in the campaign and starts collecting scrap only after the campaign; the campaign's
haul is what you carry, and dying drops it into a pot; Dungeon Storage, the hotbar and equipped items are
spared; all of it applies to the dungeon's own death (the rescue), not a vanilla death. Nothing is built.

## The frame: three layers, one feeling each

Pocket Dungeons sits between the Diablo family (power that climbs forever) and Lethal Company and REPO
(vulnerability, physical loot, rule monsters, stories). It does not have to pick one. Each layer of the game
owns one feeling, and a feature must stay in its layer.

| Layer | Plays like | The feeling | What lives here today |
|---|---|---|---|
| **The floor** | Lethal Company, REPO | I might not make it out | haunts, the Keeper, the Heard meter, Blackout, dark floors, ordeals, one idea per room |
| **The trip** | Darkest Dungeon | do I push or go home | the haul at risk, shared lives, the lever between floors, the finish |
| **Home and the compass** | Diablo, Path of Exile | I am getting stronger | the compass, gear, the decorated room, the campaign, the endgame below |

**The rule that keeps them apart:** gear lets you fight; it never makes you safe. A threat on a floor should
be something a veteran in netherite can still fear, which means a rule (light, sound, water, sight), not only
a bigger health bar. Most problems in the 2026-10-17 review are one layer leaking into another: Mending
(home) made floors safe; mobs at 0.65 to 0.85 of vanilla (`PocketDungeonsConfig.java:245` to `:248`) let gear
settle every fight; the Foreman was a health bar, so he died before his rule showed (PD-208).

**The test for any new feature:**
- Floor: can a veteran in netherite still be scared by it?
- Trip: does it change the push or go home decision?
- Home: does it make the next trip possible without making the floor safe?

**This brief's pieces, by layer:**

| Piece | Layer | Test it passes |
|---|---|---|
| Quest-line compass | Home | points you to the next trip without touching the fight |
| The pack as the haul | Trip | changes push or go home |
| The pot on a rescue | Floor, serving the trip | a death costs something you can see and win back |
| Scrap and compass levels (endgame) | Home | the uncapped score, after the campaign |

**What we refuse from each side:** from Diablo, buildcraft and drafted abilities (VISION non-goal); from
Lethal Company, losing everything and a quota paid only in currency (rejected 2026-10-07); from both, a threat
that is only a bigger health bar.

## Research: how others split campaign and endgame

From memory of the games, not a fresh search; the shapes are stable and well known.

| Game | Campaign | Endgame | Lesson |
|---|---|---|---|
| Diablo 3 | scales to the character; the player picks a difficulty tier | Paragon levels (uncapped); Nephalem Rifts (random layout and mobs, fill a bar, a guardian); Greater Rifts (pick a level, beat a 15 minute timer, the level is the score) | random doors for loot, a chosen level for the score |
| Path of Exile | 10 acts at fixed levels; being overgeared in an old act is fine | maps, tiers 1 to 16, each with modifiers that add danger and quantity and rarity | modifiers for loot percent is our Loot n% |
| Diablo 4 | story, capstone dungeons open higher tiers | the Pit (timed tiers), Nightmare dungeons keyed by sigils carrying affixes | a capstone opening a tier is our act capstone |
| WoW Mythic+ | leveling | keystone up on time, down on a miss, affixes at thresholds | VISION's original ladder |
| Hades, Slay the Spire | beat it once | Heat, Ascension: chosen, stacking modifiers | the endgame opens on the first full clear |
| Warframe | Star Chart | Steel Path: the same planets, much harder | reused content is accepted and cheap |
| Lethal Company, REPO | none | rising quotas; loot is an object you carry and can drop; rule monsters; death leaves your loot behind | loss you can see, stories over numbers |

The common pattern: a fixed campaign that teaches, then an uncapped endgame the player chooses, built from the
campaign's own parts, where the number is the score. Every game that keeps fear in its endgame does it with
rules, not stats.

## The structure

1. **The campaign** is Acts 0 to 5 as today. Each dungeon keeps its own level (`baseLevel` plus layer plus
   step, D27). It does not scale to the player; being overgeared in an old act is allowed, as in Path of Exile.
   It must still fight at full strength, so the mob curve is retuned (starting values `mobScaleBase` 0.85,
   `mobScalePerLevel` 0.02: level 8 about vanilla, level 20 at 1.25). Review item 4 stands: a capstone opens
   after three of the act's dungeons, and the act's Endless Mine is an optional dive.
2. **The Chart Room** (working name) opens after the capstone of the last act on the server (Act 2 for release,
   `releaseMaxAct`). It is reached from the Astrolabe Room the way the side doors are today. It holds three
   **uncharted doors** (working name) and the endgame dive.
3. **An uncharted door** is a generated dungeon, Nephalem Rift style: 3 floors, rooms drawn from the themes of
   every act the player has finished, affixes rolled by level (half of them rules such as Blackout, Restless or a
   haunt, not only stat affixes), a guardian finale under the phased-boss spine. Its level is the compass plus
   the step the player picks (+0 to +3). It pays scrap into the haul and "Loot n%" rolls like any floor. The
   three doors reroll after each trip, which is VISION's hook, "Your room has three doors", returned.
4. **The endgame dive** is the Endless Mine and the Proving merged (review item 8): mining floors, and every third
   floor an arena with waves and then the guardian. In the Chart Room it scales with the compass and has no
   depth cap.
5. **Loot band** stays capped by the highest act finished (D10). The endgame chase is not stronger gear; it is
   the compass as a score, the Loot percent, and tiered building blocks and shell tokens for the room (VISION
   section 3.6.1, provenance). Your room shows how deep you have been.

## 1. The compass: a quest line, then a level (owner choice)

**In the player's words:** *"In the campaign my compass tells me which dungeons to do next. When I finish the
campaign it wakes up, and from then on clearing floors fills it with scrap."*

### In the campaign

- The compass item's name and lore read the quest line, not a number:
  - `Next: Mineshaft, Lush Caves or Infestation` (the dungeons the capstone rule still wants);
  - `2 more for the Spawner Dungeon`;
  - once the capstone is open: `Next: the Spawner Dungeon`.
- Dungeons are gated by act order and the capstone rule only. `unlockLevel` stops gating campaign doors
  (`HallLayout.java:166` to `:190`, `DungeonMapText.java:181`); the astrolabe's locked doors read `Finish Act 1`
  or `Finish 3 dungeons`, never `Compass N`.
- No scrap is paid in the campaign. The door board's pay line names items instead (item 7 of the review, line 2:
  `Enchanted diamond pickaxe`). A higher step means harder mobs for better weights inside the loot band.
- The campaign's haul is the pack (section 2).

### When the campaign ends

- On the last capstone on the server: one title beat, `Your compass wakes up.`, and the compass reads
  `Compass 1: 0/4 scrap to 2`. The Chart Room opens.
- From then on scrap works as the 2026-10-07 and 2026-10-09 records say: floors pay scrap into the haul, the
  lever banks it, a failed dungeon keeps half, the compass never drops, the level cost rises with the compass.
- Scrap is the endgame's own verb. It is introduced where it starts to matter, like Paragon levels in Diablo 3.

### Pointing, verified

A live needle at the next door is not possible with the keystone as it is: a recovery compass reads
`lastDeathLocation`, which only updates on a dimension change (`Instances.java:3223`). The quest line lives in
the item's name and lore, which update live. Pointing stays a bonus for entering the dungeon dimension.

### Considered and not taken

- **A, stored scrap:** scrap banked all campaign and released at the end. Replaced by the owner's choice:
  a number nobody sees for hours is weaker than a verb that starts fresh.
- **B, capped per act:** a second number beside act progress, and a capped number reads as broken.

## 2. The pack is the haul; a rescue drops it into a pot (owner choice)

**In the player's words:** *"What's in my bag is at risk. What's in my hands, on my body and in Dungeon Storage
is safe. If I go down, my bag stays where I fell, in a pot I can go back for. If the dungeon claims me, I keep
half of everything I left in pots and everything still in my bag."*

### Our death, not vanilla's

Nobody dies in a dungeon. A killing blow is cancelled and becomes a **rescue** (`Instances.rescue`,
`Instances.java:2086`): the player is revived (`revive`, `:2058`), spends a life, and comes to at the Doors with
everything ("dying inside costs nothing", `:2168`). Every rule below happens inside the rescue, at the moment
the killing blow is cancelled, before the teleport. Vanilla death rules (drops, keepInventory) are never
involved.

### What is safe, what is at risk

| Where | On a rescue | On a failed dungeon |
|---|---|---|
| **Equipped**: armour slots and offhand | stays on you | stays on you |
| **Hotbar**: the 9 slots | stays on you | stays on you |
| **The pack**: the 27 inventory slots | goes into a pot where you fell | settles at half |
| **Pots left on this dungeon's floors** | | settle at half |
| **Dungeon Storage** (opens only at the Doors and at home during a trip) | safe | safe |
| **Home room chests** | never in reach | never at risk |

The pack holds the haul whatever its items' origin: a stack carried in from an earlier trip is at risk like a
find. That is the Lethal Company rule (what you carry can be lost) and needs no tagging. The safe places are the
"not everything" in "failing costs progress, not everything".

### The rules

1. **The pot.** On a rescue, every stack in the pack moves into a **pot**: a decorated pot block named
   `<player>'s pack`, opening a 27 slot menu the way the Dungeon Storage ender chest opens a virtual one (a
   vanilla decorated pot holds only one stack). It is a fixture (`RoomProtection`), so it cannot be broken.
2. **Where it stands.** On the block where the player fell. If that is lava, the void, water deeper than one
   block, a voided cell or a sealed ordeal room the player cannot re-enter, it stands at the nearest door of that
   cell instead. Never on the pad, never in a doorway lane.
3. **Who opens it.** Its owner and any member of the party. A teammate may carry a pack home for a friend.
4. **More than one.** Each rescue makes its own pot. The sidebar shows `Pots 2` while any stand on the floor, and
   the first pot of a trip gets one line: `Your pack stayed where you fell.`
5. **When the floor ends** (descend, go home, a finish): a pot nobody emptied settles at half, and the kept half
   goes into its owner's pack, or Dungeon Storage if the pack is full. Going back for it is worth the other half.
6. **When the dungeon fails** (the fifth rescue, `failRunOmen`): the fifth rescue makes its pot like any other,
   then the dungeon settles: every pot of the dungeon plus every member's pack keeps half, and the kept half goes
   home with them (`failReturnsHome`). This replaces "You keep what you carry" (`Instances.java:2233`).
7. **Half, exactly.** Each stack keeps half, rounded down (a stack of 1 keeps nothing on its own); the single
   items left over (swords, tools, armour carried in the pack) keep every other one, seeded by the trip so a
   retry cannot reroll it, best item first by tier.
8. **Leaving a floor in progress** already fails the dungeon for the leaver (PD-192), so rule 6 applies to
   them. A disconnect is not a rescue: the reconnect grace (2026-09-26) still holds the run.
9. **Act 0 is exempt** (infinite lives, nothing to lose). A rescue with no room to return to (admin, untimed,
   visit runs) makes no pot.
10. **Endgame too.** In the Chart Room the same pack and pot rules apply, beside scrap.

### Stories

*Solo newcomer, first Act 1 dungeon.* Priya goes down to a skeleton in the Mineshaft's seam room with six raw
iron in her pack. She comes to at the Doors with her sword and armour, and a pot stands in the seam room with
her name on it. She walks back in, more careful now, kills the skeleton and opens the pot. She has learned the
pack rule by living it, and the iron is worth more to her than it was a minute ago.

*Four regulars, mid Act 1.* The tank falls in the Ossuary's coffin hall and his pot sits in the middle of the
room. The healer runs in under the coffins, empties it into her own pack and runs out; now her pack is at risk.
The group argues over whether the tank's diamond is worth the healer's life. That argument is the game.

*Veteran, Chart Room.* Mara carries a full pack into an uncharted door to save trips home. A Blackout floor
drops her once and her pot is in a dark cell where a haunt walks. She can leave it for half, or walk into the
dark with a torch for the rest. Her netherite does not decide this; the rule does.

*Where it goes wrong.* Kai fills his hotbar with diamonds and carries his sword in his offhand; nine slots of
loot are now safe forever. That is the price the hotbar pays (fewer tools in reach), and Lethal Company's own
four slots make the same trade, so it is accepted. The worse case is Dungeon Storage: reachable from any ender
chest mid-run (J6, `RunStorage`), it lets a careful player bank every find the moment they make it, and the haul
risk goes away. Answered by question 1, option a: during a trip it opens only at the Doors and at home.

## 3. Endgame door types

1. **Uncharted doors** (Nephalem style): first. Built almost entirely from what exists: the room selector,
   themes, affixes, the wager, guardians, the haul.
2. **The endgame dive** (mine plus arena): second, merged per review item 8.
3. **A timed trial** (Greater Rift style: pick a level, a timer, the compass rises on a win): held. It is a second
   way to raise the compass; add it only if the doors do not create enough of a push for a score.
4. **One heavy thing**, later, as an uncharted floor type: a relic (a vanilla heavy core) worth a big Loot percent
   bonus if carried to the pad; the holder is slowed, it cannot go into storage, and a rescue drops it in the pot.

## The numbers (starting values, to tune)

| Number | Value | Where it lives | At risk |
|---|---|---|---|
| Campaign mob curve | 0.85 + 0.02 per level | `mobScaleBase`, `mobScalePerLevel` | |
| Deaths harden the floor | 0 in Act 1, 0.1 from Act 2 | `omenDangerScalePerOmen` (per act) | |
| Capstone opens after | 3 dungeons of the act | `capstoneDungeonsNeeded` (review item 4) | |
| Campaign scrap | none | | |
| Endgame starts at | Compass 1, 0 scrap | `DungeonLog` compass record | |
| Safe on a rescue | armour, offhand, hotbar, Dungeon Storage | the pot rule | |
| The pack | 27 slots | the pot rule | moves to a pot on a rescue |
| An unemptied pot when the floor ends | keeps half | `potKeepPercent` (50) | half |
| A failed dungeon | packs and pots keep half | `failHaulKeepPercent` (50), reused | half |
| Endgame opens | last act on the server | `releaseMaxAct` (review item 5) | |
| Uncharted door | 3 floors, level compass + step 0 to 3 | new door generator | the pack, and half the scrap haul on a fail |
| Doors in the Chart Room | 3, reroll per trip | new | |
| Rule affixes on a door | at least half | door generator | |
| Lives | 4 + members | review item 6 | |

## The rules, in one place

1. The campaign never scales to the player; the endgame always does.
2. In the campaign the compass is a quest line and pays no scrap; after the campaign it levels on scrap.
3. Every floor keeps the lever, shared lives and the half-loss fail. In the campaign the half is the pack and the
   pots; in the endgame it is the pack, the pots and the scrap haul.
4. A rescue never touches armour, the offhand, the hotbar or Dungeon Storage.
5. The compass never drops (unchanged).
6. The endgame's loot band is the highest act finished; the endgame chase is the score and the room, not power.
7. No floor needs an item the player might not have; an uncharted door's spine follows the same solvability pass,
   and a pot never stands where it could block a path.
8. Choices stay tangible: doors with boards, a pot you walk back to, a lever.

## Migration

- **A player mid campaign:** the compass level they hold is kept in the save, unread, and becomes their starting
  endgame compass when they finish the campaign (never taken away, per the compass rule); the bar and any haul
  carry over the same way. The compass item switches to the quest line on the next join, with one message:
  `Your compass now shows the way. It will count again when the campaign is done.`
- **A player who has finished the last shipped act's capstone:** the Chart Room opens at once, and the compass
  keeps its level and bar.
- **The pack rule** needs no migration: it starts with the first rescue after the update. Items in packs today
  are simply at risk from then on; the join message says so in one more line: `What's in your pack is at risk.
  Your hotbar, armour and Dungeon Storage are safe.`
- Saves: no new persistent field for pots (they are floor state, gone with the floor); a flag for "campaign
  done" if the existing act set does not already answer it.

## Supersedes (if approved)

- D23, the compass gates dungeons: campaign only, replaced by act order and the capstone rule.
- The 2026-10-07 haul record's "Failing" rule and `Instances.java:2233` "You keep what you carry": a failed
  dungeon now settles packs and pots at half (and, in the endgame, the scrap haul as before).
- U8 Stage 1, "dying inside costs nothing" (`Instances.java:2168`): a rescue now moves the pack into a pot.
- Act 0 (2026-10-17): rule 7 of the table ("finishing Act 0 banks 2 scrap") and `compassCostFirstLevel`,
  `actZeroScrap`: Act 0 ends on the compass's first quest line, `Next: the Mineshaft`, not on Compass 2.
- VISION section 2, "progress is a key level": now true of the endgame only.
- Review 2026-10-17 item 1: the floor 5 payout at the lever banks the pack, not scrap.
- Review item 2, "Your compass sets the fight": narrowed to the endgame, where a door's level is the compass plus
  the step. The campaign keeps dungeon levels with the retuned curve.
- Review item 5, release gate: amended so the release ships an endgame (the Chart Room opens after Act 2).
- Review item 7: the campaign board's line 2 names items, not scrap.
- Review item 8, mine plus Proving merge: its home becomes the Chart Room; the act mines stay as optional
  campaign dives.
- DECISIONS 2026-10-15, The Proving: retired as a door, kept as the endgame dive's arena floors.

## Build order

| Phase | Scope | Owed before the next |
|---|---|---|
| 1 | campaign curve retune, capstone after three, the quest-line compass, campaign floors pay no scrap, migration | one Act 1 trip: the compass names the next dungeons |
| 2 | the pack and the pot (rescue hook, pot fixture and menu, settle at floor end and on a fail) | a pot retrieved under pressure, and one left for half |
| 3 | the Chart Room and uncharted doors (generator, rule affixes, guardian finale), scrap wakes up | one uncharted door cleared live |
| 4 | the endgame dive (mine plus arena) | a guardian that lives long enough for its rule |
| 5 | one heavy thing, as an uncharted floor type | a relic carried to the pad by a party |

## Knobs

`mobScaleBase`, `mobScalePerLevel`, `omenDangerScalePerOmen` (per act), `capstoneDungeonsNeeded`,
`releaseMaxAct`, `potKeepPercent`, `failHaulKeepPercent`, uncharted door floor count and step range, the rule
affix share, the Chart Room door count, the endgame level cost curve (`scrapCostBase`, `scrapCostEvery`),
`livesPerExtraMember`.

## Owner answers (2026-10-18)

1. **Dungeon Storage mid-run.** It is spared from a rescue, as you ruled, but today it opens from any ender chest
   mid-run (J6), so a player can bank every find the moment they make it and nothing in the pack is ever at risk.
   Options:
   - **a. Reachable only at the Doors and at home during a trip** (recommended; **chosen**). Dungeon Storage is the safe
     ship, Lethal Company style: a run back to the Doors is the price of safety, so carrying loot through the
     next room is a choice. Mid-run ender chests stop opening it.
   - **b. Reachable anywhere, but what a trip puts in counts as pack until the trip banks.** Keeps the access,
     costs tracking deposits per trip, and is harder to explain.
   - **c. Leave it as it is.** The pack rule then only bites on loot picked up since the last ender chest.
Questions 2 to 5: the recommendations were approved as written.

2. **Is half the right price for a pot left behind at the end of a floor?** *Recommended: yes, the same as a
   failed dungeon, so there is one number to learn.*
3. **Names.** "Chart Room" and "uncharted doors" build on the settled word charts, and "pack" and "pot" are new
   player-facing words; naming is settled law, so they need your approval. *Recommended: approve all four, or
   give the words you want.*
4. **The timed trial (Greater Rift style): held, as proposed?** *Recommended: held until the doors have been
   played.*
5. **Does finishing a campaign act still matter after the endgame opens?** *Recommended: yes, finished acts add
   their themes to the uncharted doors' pool, so the rest of the campaign widens the endgame without gating it.*

## Open until played

Whether the quest line reads as guidance or as a chore list; whether "Your compass wakes up" lands as a reward;
whether walking back for a pot is a story or a chore, and whether players read "Pots 2" on the sidebar; whether
hotbar stuffing becomes the default; whether three uncharted floors is the right length for a session's single
trip; whether rule affixes keep a veteran afraid; whether a party argues over who carries the relic (that is the
hope).
