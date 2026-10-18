# Pocket Dungeons: design review (2026-10-17)

Design only. Nothing here is approved, and no code, data or `DECISIONS.md` was touched. The owner
picks; the `design-brief` loop records what is picked.

> **Follow-up 2026-10-18:** `2026-10-18-campaign-and-endgame-brief.md` splits the game into a campaign and an
> endgame and frames it in three layers (floor, trip, home). If approved, it narrows item 2 to the endgame,
> amends item 5 so the release ships an endgame, and moves item 8 into the endgame room.

Read for this review: `docs/reference/VISION.md`, `docs/DECISIONS.md` to its last section (Gold may open a
vault), every playtest from 2026-09-26-1 to 2026-10-16-1 plus `AGENDA.md`, `BALANCE.md` and `LIVE_CHECKS.md`,
`docs/reference/BUGS.md` to PD-217, the code map and the source named below, the two plans dated today, and
`git log` on master, `feature/act-0-first-night` and `feature/payoff-rooms`.

---

## The one thing to know before the list

Every playtest in `docs/playtests/` has the same player: the owner, solo, with one two-player session
(2026-10-03-2 and 2026-10-07-2, with SirAegerus). No stranger has played. No party of three or four has
played. So:

- "Newcomer" evidence is the owner role-playing a first login (2026-09-27-3, 19:41 onward) and the owner's own
  tutorial script (2026-10-03-1, 06:15 to 06:27). It is good evidence of what an expert thinks a newcomer
  needs, not of what a newcomer does.
- "Veteran" evidence is strong and consistent: the same expert at compass 13 to 23 in diamond and Mending.
- Party-of-four evidence does not exist. Every party claim below is reasoned from code and marked as such.

That is not a criticism of the testing; it is the reason the highest ranked items are the ones that protect a
first night and a fourth session, and the reason the review recommends a release gate (item 5).

---

## Playing it in my head

### Solo newcomer, first login to the Act 1 capstone

1. **Join.** Lemon's Act 0 guidance lives on `feature/act-0-first-night` and payoff-rooms, not master. A
   newcomer on master still meets the old tour. On the branch: a chest, the compass, Lemon steps out. Good,
   tangible, warm.
2. **The First Night, floors 1 to 4** (`dungeon/first_night.json`). Eight oak logs, twelve stone, eight coal
   ore, six iron ore. No spawner, no choice of door (one node per layer, one edge each), lives hidden and
   infinite, Home lever locked, no loot. The player is learning to punch trees, which every Minecraft player
   already knows. What they are not learning: the spawner gate, the pad, the door board, lives, the haul, the
   bank. **Flat.** The decision record itself asks "whether gathering without a fight holds attention".
3. **Floor 5, The Cave Mouth.** One zombie, one spawner. Finish: 2 scrap, Compass 2, Act 1 opens. The first
   time the player sees scrap is the moment it is spent. **Confusing:** the model's central verb, carrying a
   haul home, has never happened.
4. **Act 1, first dungeon.** Now everything arrives at once: five lives, the sidebar, the door board with a
   name line, a Loot percent, a pay line, a promised gear piece, a deal sheet, the floor title, vault keys,
   copper chests, a reward barrel, Lemon's first-floor lives speech. The owner's own script asked for the
   opposite: "tell the player some information and then let them play a floor before they get the board"
   (2026-10-03-1, 06:27:38). **Overload.**
5. **First death.** "You come to at the Doors, with the feeling of a bad omen." A life gone, and every mob on the
   floor now 15 percent stronger (`omenDangerScalePerOmen` 0.15, `MobScaling.java:59`). A struggling newcomer
   is made to struggle more. **Unfair in the wrong direction.**
6. **Act 1 to the capstone.** Five dungeons, every one required, plus the Endless Mine to its first guardian,
   before the Spawner Dungeon opens (`ActProgress.capstoneReady`, `ActProgress.java:200`). If one of them is
   broken on the night (the Kennels was, three sessions running: PD-194, PD-195, PD-204), the campaign stops.
   **Soft-lock at campaign scale**, which the "never soft-lock" value forbids at floor scale.
7. **Spawner Dungeon at lives 1.** "I felt like I had just barely enough resources to succeed" (2026-10-08-1,
   04:31:52). This is the game working.

### A party of four regulars, mid Act 1

1. Four players, **five lives between them** (`Omen.LIVES`, `Omen.java:28`, a constant). A solo player gets five
   deaths; four players get five deaths. Each death also hardens the floor for all four. The owner asked for
   the fix himself: "we should also have extra lives with more people" (2026-10-07-2, 04:07:53).
2. Act progress is per member present at a finish. The regular who missed Tuesday is a dungeon behind and the
   group splits at the astrolabe or carries them. **Two systems fight:** party play and per-member gating.
3. Trial spawners add mobs per nearby player (vanilla), boss health scales per member, vaults pay per player.
   This part is sound.
4. The board is read by one person and the rest wait. Seven facts on a door board is a solo read; four
   people need two.

### A veteran pushing a high compass in Act 2

1. Compass 15 to 23, full diamond and netherite, Mending on everything, bought at 64 emeralds from an
   unlimited Librarian stock (`LibrarianNPC.java:224`, `Integer.MAX_VALUE` uses).
2. Mobs at floor level 12 are 0.75 of vanilla strength: `mobScaleBase` 0.65 plus `mobScalePerLevel` 0.008 per
   level (`PocketDungeonsConfig.java:245` to `:248`). Herobrine at level 25 fields mobs at 0.85 of vanilla.
   Nothing in the campaign reaches vanilla strength until level 44.
3. Result: zero rescues across fifteen floors and three trips (2026-10-13-1, A1 evidence: "lives never dropped
   below 5 all session"); five blackstone floors, all five lives held, rated 3 of 5, one word "simple"
   (2026-10-16-1). The Act 2 guardians died before their mechanics showed: "The foreman was just a very fast
   pillager" (2026-10-13-1, 05:56:31, PD-208).
4. Failing would cost half a haul (`failHaulKeepPercent` 50) and nothing carried: "The dungeon claims you. You
   keep what you carry." (`Instances.java:2233`). The compass never drops. With no deaths, the risk the owner
   asked for is never on the table. **Grindy and riskless at once.**
5. Salvage at home turns spare gear into diamonds: "5 gear -> 194 xp + 9 diamonds" (2026-10-13-1, Balance
   observations). Durability caps (2026-09-27) mean nothing beside Mending. "all my gear has mending on it now
   so I don't need materials anymore" (2026-10-16-1, Design feedback). **Scarcity has collapsed.**
6. Content outruns play: 83 live checks in `LIVE_CHECKS.md` with 118 open boxes; Cow Pits, Copper Works rooms,
   Lush rooms, the Act 1 guardians and the Brood Chamber were still unobserved after 2026-10-13. The veteran
   plays Act 3 and 4 because his save has them open, so the release acts get the least play.

---

## The ranked list

Ranked by how much each change raises the chance a player comes back for another run, against build cost.

| Rank | Change | Weight | Cost |
|---|---|---|---|
| 1 | Act 0 teaches the dungeon, one verb a floor | heavy (redesign) | M |
| 2 | Your compass sets the fight | heavy (reversal) | M |
| 3 | Mending leaves the shop; salvage stops minting diamonds | tuning | S |
| 4 | The capstone opens after three dungeons; the mine is optional | heavy (reversal) | S |
| 5 | Release ships Acts 0 to 2 only | heavy (cut) | S |
| 6 | Lives grow with the party | tuning | S |
| 7 | The door board says three things | small | S |
| 8 | Mine guardian floors become the Proving's arena; one special door | heavy (merge) | M |
| 9 | The last hopper rooms leave the pool | small | S |

---

## 1. Act 0 teaches the dungeon, one verb a floor

**The change:** "The First Night still has me gathering, but every floor also shows me one new piece of how a
dungeon works, and I carry my first haul home and pull the lever myself."

**The problem it solves.**
- The loudest theme of the whole test history. "We need a pretty strong tutorial." (2026-10-03-1, 06:22:38).
  "How do I pick a door?" (2026-10-03-1, 06:20:03). "I don't know what that is, what do I need to do?"
  (2026-09-27-3, 19:42:28). "I failed my first run (it's way too hard)" (2026-10-03-1, Failure and reset).
- The owner's own script: "introduce the front wall with the door on it, describe everything on that wall
  starting with the door, then the board, then to right click the door, then describe the lever" (2026-10-03-1,
  06:25:17) and "let them play a floor before they get the board" (06:27:38).
- Code fact: as built, floors 1 to 4 of `dungeon/first_night.json` are `objective: gather` with no spawner, the
  graph is a single path (no door choice), and the Home lever is locked until the finish (DECISIONS 2026-10-17,
  rule 15). So the five things a newcomer cannot guess (spawner gate, door board, lives, haul, bank) all arrive
  together on Act 1 floor 1.
- The owner's open question in the same record: "whether gathering without a fight holds attention".

**The shape.** Keep the five floors, the thematic rooms and the gather counts (they are good: they are the
bootstrap for wood, the oldest scarcity complaint). Add one verb per floor:

| Floor | Gather | The new verb | What the player sees |
|---|---|---|---|
| 1 Treeline | 8 oak logs | the pad: walk on it to leave | the sidebar with one line, "Oak Log 3/8" |
| 2 Stone Rise | 12 stone | one weak trial spawner gates the pad | "Spawners 0/1" joins the sidebar |
| 3 Black Seam | 8 coal | **the first door board**: two doors, step 0 and step 1, the second pays 1 more scrap | the board, and the haul line appears on the sidebar |
| 4 Rust Ravine | 6 iron | **lives become visible**: "Lives 5". A death here shows the life go, then gives it back at the floor's end ("Lemon caught you.") | lives on the sidebar |
| 5 Cave Mouth | none | the finish, then **the Home lever pays**: the haul banks into the compass bar in front of the player | the bank title, Compass 2 |

The anti-farm rules stay: no loot rolls before the finish, quit resets the act, the act is done once. The Home
lever opens only on the finish, which is what the owner already ruled; the change is that the lever is where
the 2 scrap is paid, not an automatic bank, because "pull the lever is the payout ritual" (2026-10-07-2,
design feedback).

**Stories.**

*Solo newcomer, first night.* Priya has never seen the mod. She chops eight logs, steps on a glowing pad
because the sidebar says "Oak Log 8/8" and Lemon says "Step on the pad", and the floor moves on. On Stone
Rise a cage spits a slow zombie at her; she breaks the cage and the sidebar goes from "Spawners 0/1" to done,
so she now knows what a spawner is for. On Black Seam two doors stand in front of her and one says "+1
scrap"; she takes it because it is the only difference she can read, and she has learned the board by using
it. At the Cave Mouth she pulls the lever herself and watches the bar fill to Compass 2. She logs off knowing
the loop, not just the controls.

*Four regulars, mid Act 1.* Dev brings his cousin, who is new. The cousin is in Act 0 alone; the three
regulars wait in Dev's room. Twenty minutes later the cousin joins them already saying "lives" and "haul" in
the right places, so on the group's Ossuary run nobody stops at every board to explain. The regulars lose
nothing; they never see Act 0 again. The cousin's first group death costs a life and he knows it did.

*Veteran, high compass in Act 2.* Nothing changes for her; she finished Act 0 weeks ago (the migration marks
any recorded progress as done). What she notices is indirect: the new players on her server stop asking her
what the haul is.

*Where it goes wrong.* Sam is a speedrunner. On Rust Ravine he learns a death is refunded, so he jumps into
the ravine to skip walking back. Nothing is gained (no loot, infinite lives already), so the exploit is
harmless; the real risk is the reverse: a newcomer who dies on floor 4 and then dies in Act 1 is surprised the
second life stays gone. The floor 4 line has to say it once: "In the real dungeons, a life stays gone."

**What it replaces or reverses.** DECISIONS 2026-10-17 "Act 0", rule 5 (floors 2 to 4 teach "another common
mechanic", proposal open), rule 8 (lives hidden in Act 0; reversed to "shown, refunded on floor 4"), rule 15
(the Home lever stays locked until the finish; amended so the finish pays at the lever), and the open question
on which mechanic each floor teaches. It is still unmerged and unplayed, which is why it is cheap to reshape now.

**Cost.** Medium. `first_night.json` (a spawner cell on floors 2 and 5, a two-edge layer 3), the gather
objective sharing the bar with a spawner count, a floor-4 "refund" rule in the Act 0 lives path, Lemon's Act 0
lines, the Home lever unlock on finish. No save migration (Act 0 state is unchanged).

**Risks and how to tell.** Watch a real newcomer, not the owner: can they say what the haul is before Act 1
floor 2, unprompted? Does the floor 3 board take them under 30 seconds? Do they take the +1 door? A new live
check: "Act 0 newcomer can name lives, haul and the Home lever after the finish."

---

## 2. Your compass sets the fight

**The change:** "My compass is how hard the dungeon fights me; I can always choose a door that is harder, but
never one that is a walkover."

**The problem it solves.**
- Code fact: a floor's level is the node's level plus the door step (D27, `FloorLevels.of`); the player's
  compass never enters it. Mobs scale `0.65 + 0.008 x level` (`PocketDungeonsConfig.java:245` to `:248`,
  `MobScaling.java:58`): level 12 is 0.75 of vanilla, level 25 is 0.85. The whole release runs below vanilla.
- Evidence: zero rescues in 15 floors (2026-10-13-1, A1); 5 lives held on 5 floors, "simple", 3 of 5
  (2026-10-16-1); PD-208 "The foreman was just a very fast pillager" (2026-10-13-1, 05:56:31).
- The compass today is a gate and a pay modifier (half pay below it, `ScrapMath.java:83`). A number that only
  gates is the "two competing levels" problem PD-162 named, in a quieter form. VISION section 2 promised the
  opposite: "Progress is a key level."
- Deaths harden the floor (`omenDangerScalePerOmen` 0.15, `MobScaling.java:59`), which punishes the newcomer
  who is already losing and never touches the veteran who never dies.

**The shape.**
1. A floor runs at `max(nodeLevel, compass - 3) + step`. In a party, `compass` is the **lowest** present
   member's. The node level still rises with depth, so new dungeons still start harder than the player.
2. Retune the curve so the release crosses vanilla: starting values `mobScaleBase` 0.85 and
   `mobScalePerLevel` 0.02 (level 3 is 0.91, level 8 is 1.01, level 12 is 1.09, level 20 is 1.25). Starting
   values only, to tune live.
3. Bosses, guardians and finale elites read the same floor level, so PD-208's fix lands at its root and the
   phased-boss spine (2026-10-15) gets health that lasts.
4. Deaths stop hardening the floor in Act 1 (`omenDangerScalePerOmen` 0 there, 0.1 from Act 2). The life is
   the cost.
5. Half pay below compass stays only for its real case: a member in a party whose lowest compass is far below
   theirs.

**Stories.**

*Solo newcomer, first night.* Priya hits Compass 2 and opens the Mineshaft. Its floors run at their own level
(2 and up), since her compass minus three is below that. Mobs hit a little softer than vanilla, which is right
for a first dungeon; she dies once, keeps the same floor, and wins. She never feels the new rule, which is the
point of the `compass - 3` margin.

*Four regulars, mid Act 1.* Compasses 6, 7, 7 and 3. The floor runs at the 3's level plus the step they pick;
the three higher members are paid at the below-compass rate on the easy door, so they pick step 2 to keep full
pay. The fight is pitched at the weakest of them, and all four can feel why. The low member is protected; the high members chose
the heat.

*Veteran, high compass in Act 2.* Mara is Compass 21 and replays Deepslate (node level 6) for diamonds.
Before, its mobs were 0.7 of vanilla and she ran it half asleep; now it runs at level 18, mobs at 1.21, and the
Deep Sentry's Heard phase actually plays because the fight lasts. She dies twice and has to decide whether
lives 3 is worth floor 4. The haul at risk is finally at risk.

*Where it goes wrong.* Kai over-climbs: he banks three good trips, hits Compass 14, and the Act 2 dungeons he
still needs now fight at 11 plus the step, harder than he is geared for. The compass never drops (an owner
value), so he could be walled. Two guards: the `compass - 3` margin means step 0 is always three below his
compass, and the exploit guard (lowest member sets the level) is what stops the opposite abuse, a veteran
dragging along a Compass 1 friend to farm: the veteran is paid at the below rate, so it is a carry, not a farm.

**What it replaces or reverses.** Reverses D27 in part (floor level is the node level plus the step; now the
larger of that and compass minus three, plus the step). Amends the 2026-10-09 scrap curve's "below your compass
it pays half" to apply only inside mixed parties. Retunes `mobScaleBase`, `mobScalePerLevel` and
`omenDangerScalePerOmen`. Touches every finale and guardian through their level input.

**Cost.** Medium. `FloorLevels.of` and the door offer (`Keystone.Offer` level), `DifficultyProfile`, the party
compass read, `ScrapMath.floorPay` callers, the board's level line, three knobs. No save migration: the
compass is already stored; only what it does changes.

**Risks and how to tell.** Watch deaths per trip at compass 15+ (target: one or two, BALANCE.md shows zero);
watch whether a player under compass 6 ever fails a floor 1 (target: rarely). A live check: "A veteran at
compass 15+ loses at least one life in a three-floor Act 2 trip, and the Foreman's vents fire before he dies."

---

## 3. Mending leaves the shop; salvage stops minting diamonds

**The change:** "Gear wears out again, so the iron and diamonds I find matter; I can buy experience at home and
enchant my own things."

**The problem it solves.**
- "mending is too powerful and should be removed, currently it's sold by the home merchant. that way tools
  armour weapons become material sinks again" and "all my gear has mending on it now so I don't need materials
  anymore" (2026-10-16-1, Design feedback).
- Code fact: the Librarian keeps the Mending book at 64 emeralds with `Integer.MAX_VALUE` uses
  (`LibrarianNPC.java:224`, `VendorMath`). Bought live twice (2026-10-09-1, 04:17:40; 2026-10-13-1, home visit).
- Salvage pays diamonds back: "5 gear -> 194 xp + 9 diamonds", about 30 diamonds in one session
  (2026-10-13-1, Balance observations). The owner's scarcity value (diamonds finite) leaks through the bench.
- The 2026-09-27 durability caps were the scarcity lever; Mending switches them off.

**The shape.**
1. The Librarian stops selling Mending. Mending stays out of random loot and rerolls (it already is).
2. The Librarian sells bottles o' enchanting for emeralds (the owner's own idea, 2026-10-16-1), so emeralds buy
   a service, not a solution.
3. Salvage pays experience and emeralds; material back is capped at one ingot or gem per three pieces of that
   tier (a knob).
4. Optional, the owner's fuller position: enchantments and trims on dropped gear become rare (floor rewards
   only), books stay. Ship 1 to 3 first and see whether 4 is needed.

**Stories.**

*Solo newcomer, first night.* No change she can see; she will not afford a 64-emerald book for weeks. What
she gets is an economy that still means something when she is a veteran.

*Four regulars, mid Act 1.* Their iron swords hit the 64 durability cap and break on floor 3 of Ossuary. The
group's iron becomes a real conversation: who gets the ingot for the anvil. Someone buys two bottles o'
enchanting with the junk emeralds and puts Unbreaking on the tank's chestplate. The materials they carry home
are now the plan, not a pile.

*Veteran, high compass in Act 2.* Mara's netherite pickaxe is at 40 of 128 on the Deep Mine's floor 5. She
has two diamonds and a decision: repair it at the anvil tonight, or push to the floor 6 guardian whose tier B
table carries the diamonds. The mine's loot table finally prices her time.

*Where it goes wrong.* Existing saves hold Mending gear. Strip it and the owner's test character loses weeks of
setup and feels robbed; leave it and the veteran is exempt from the change forever. Recommendation in the
questions below. Second failure: XP bottles become the new Mending if the price is low; the price is the knob
to watch.

**What it replaces or reverses.** The J5a Mending offer and "Mending is sold as a book by the librarian"
(2026-10-02-2, A3 note); the 2026-10-01 "lock in" owner direction (already gone); `SALVAGE_PROPOSAL` material
returns. Restores the 2026-09-27 durability caps as the working sink.

**Cost.** Small. `LibrarianNPC` stock, `VendorMath`, `SalvageMath` and its knobs, one new offer. Migration:
an owner decision (see questions).

**Risks and how to tell.** Count anvil uses and gear breaks per session in the journal; watch for "my gear broke
and I could not replace it" (too harsh) and for a pile of unspent diamonds (still too loose).

---

## 4. The capstone opens after three dungeons; the mine is optional

**The change:** "When I have finished three of the act's dungeons, the capstone opens; the rest are there for
the loot and the story, and the mine is a dive I take when I want."

**The problem it solves.**
- Code fact: `ActProgress.capstoneReady` (`ActProgress.java:200`) requires every non-capstone dungeon of the act
  finished and the act's Endless Mine cleared to floor 3. Act 1 is seven completions before Act 2.
- A broken dungeon blocks the campaign: the Kennels soft-locked its floor (2026-10-09-1, 04:38:36, "I can't
  because the wolf spawner is needed to progress"), burned down on the next trip (PD-195), then spawned passive
  wolves (PD-204). For three sessions, Act 1 could not be completed without an admin.
- The owner chose dungeons over random doors because "players should be able to choose the act and dungeon"
  (2026-10-08-1, 04:00:33). A checklist that requires all of them gives the choice back with the other hand.
- The mine as a gate is an open question in its own record: "Does a guardian every 3 floors feel like a rhythm
  or a chore?" (DECISIONS 2026-10-11, Endless Mine, Open until played). The veteran never went past F6 in
  either mine (2026-10-13-1).
- For a party (reasoned from code, no live evidence): per-member gating means the more dungeons an act demands,
  the more often the group is out of step.

**The shape.**
1. The capstone opens when **three** of the act's dungeons are finished (Act 1: 3 of 5; Act 2: 3 of 4). A knob,
   `capstoneDungeonsNeeded`.
2. The Endless Mine is not a gate. It stays on the astrolabe's side wall as the act's dive.
3. Finishing the rest still matters: an oxidized bulb over each finished door (already built) and a short
   "Act 1: 5 of 5" line on the astrolabe for the completionist. No reward is gated on it.
4. Party: the capstone opens for the party if the **leader** qualifies and every member has Act N open; members
   who have not met three get their finish recorded normally.

**Stories.**

*Solo newcomer, first night.* Priya finishes the Mineshaft, Lush Caves and Infestation over two nights and the
astrolabe lights the Spawner Dungeon. She did not like the Ossuary's first floor, so she never went back, and
nothing punished her. The Kennels and Ossuary bulbs stay dark, which reads as "more to find", not "you are
stuck".

*Four regulars, mid Act 1.* One regular missed a night, so she has two finishes to the others' four. The leader
qualifies, so the group runs the capstone together and she counts it. Before, she would have needed two solo
trips first, and the group would have run without her.

*Veteran, high compass in Act 2.* Mara wants the Ancient City. She has Cow Pits, Deepslate and Copper Works;
Frostworks's freeze finale has not been built yet. She goes now. Later she dives the Deep Mine to floor 9 for
the tier C diamonds because she wants them, not because a gate said so.

*Where it goes wrong.* A player rushes the three easiest dungeons in each act and never sees the dungeons
that carry the act's best rooms (Copper Works's forge, the "coolest room so far", 2026-10-08-1, 04:42:25).
Mitigation: the capstone's echoes (DECISIONS 2026-10-11, Spawner Dungeon structure, each spawner releases an
earlier dungeon's mechanic) still show every dungeon's idea once, and the dark bulbs advertise the rest.

**What it replaces or reverses.** D29 (act completion needs every dungeon), the 2026-10-11 "Endless Mine act
gate" row and the matching rule 6 of "Endless Mine, one per act" (floor 3 counts toward the next act), and
`ActProgress.capstoneReady`. Keeps D23 (the compass gates dungeons) and the mine's floor records.

**Cost.** Small. `ActProgress` (`capstoneReady`, `complete`, `progressLine`, `remainingLine`), the astrolabe
sign text, one knob, the tests that pin the old gate. Migration: none; every player only gains access.

**Risks and how to tell.** Watch which two Act 1 dungeons get skipped most; if one is never played, it is a
content problem to fix, not a gate to restore. A live check: "a player with 3 of 5 sees the capstone open and
says why".

---

## 5. Release ships Acts 0 to 2 only

**The change:** "The game I get is Acts 0, 1 and 2, and everything in them works; the astrolabe says there is
more to come."

**The problem it solves.**
- The release target is Acts 0 to 2 (`project_pocketdungeons_release_goal_act12`), but the last two sessions
  were spent in Acts 3 and 4 (prismarine, drowned_vault, blackstone: 2026-10-13-1, 2026-10-16-1) because the
  owner's save has them open.
- `LIVE_CHECKS.md` has 83 checks and 118 open boxes. After 2026-10-13: "Cow Pits, Copper Works, Lush rooms,
  Act 1 guardians, and the Brood Chamber all remain unobserved live". The Act 2 capstone was seen once.
- New systems keep landing before the old ones are played: heist, claim and haunt floors (2026-10-13), the
  Proving and phased bosses (2026-10-15), Act 0, vertical floors and payoff rooms (2026-10-17). Owner,
  2026-10-11: "Test Acts 1 and 2 first and see what works before the remaining acts."

**The shape.**
1. A server knob, `releaseMaxAct` (2 for release), hides acts above it on the astrolabe. Finishing the Act 2
   capstone shows "The way down is not open yet." and keeps Act 3 locked for every save, including migrated
   ones. Admins keep a flag to see the rest.
2. A content freeze for the release branch: no new dungeon, floor type or mode until the Acts 0 to 2 live
   checks are green. Fixes, tuning and the items above are in; new content goes on a feature branch.
3. The owner's test save gets its act set to 2 for release testing (an admin command, not a migration).

**Stories.**

*Solo newcomer, first night.* No visible change, except that every door on the astrolabe works.

*Four regulars, mid Act 1.* They finish the Ancient City in week three and the astrolabe's next wheel position
says the way down is not open yet. They argue about what Act 3 is. That anticipation is worth more than a half
built Act 3 floor would have been.

*Veteran, high compass in Act 2.* The owner plays the Brood Chamber, Frostbite and the Copper Works forge
finale, which he has not seen live, instead of the Gilded Seam, and finds the Act 2 bugs before a stranger
does.

*Where it goes wrong.* The owner's best recent design energy is in the late game (the economy riff, sinks,
"merchants seem to be less relevant ... in later acts (act 3+)", 2026-10-16-1). Hiding Act 3 hides the place
those ideas were born. Answer: keep designing there; just do not ship it, and play it on a dev flag.

**What it replaces or reverses.** Nothing in `DECISIONS.md`; it narrows what ships. Pauses the 2026-10-13
reopening of Act 3 and 4 for release, and Phases 3 and 4 of vertical floors (drops, overlooks, map) until after
release; Phases 1 and 2 (Mineshaft stair rooms) can ship, since geometry that demands movement is an owner value.

**Cost.** Small. `ActProgress` and the astrolabe's act cycle read one knob; a capstone line; the admin flag.
No migration.

**Risks and how to tell.** The test is the live-check count: all Acts 0 to 2 boxes closed before release.

---

## 6. Lives grow with the party

**The change:** "Four of us get more lives than one of me, so one teammate's bad night does not end everyone's."

**The problem it solves.**
- "we should also have extra lives with more people" (2026-10-07-2, 04:07:53). "I think I got kicked out of the
  dungeon even with lives to spare" (04:05:45, the PD-167 case, since fixed).
- Code fact: `Omen.LIVES` is a constant, five for any party (`Omen.java:28`). Four players get the same five
  deaths as one, and four players produce deaths four times as often.
- No party of four has played; this is reasoned from code and from the two-player session.

**The shape.** Lives at the start of a trip are `4 + members` (solo 5, unchanged; two 6; four 8), a knob
`livesPerExtraMember` (1). Blood doors and the Blood affix still cost a flat life. A member who joins mid-trip
adds nothing (no farming lives by inviting).

**Stories.**

*Solo newcomer, first night.* Unchanged: five lives.

*Four regulars, mid Act 1.* Eight lives on the sidebar. The new player dies twice on Kennels floor 1 against
the pack, and the group still has six going into the Alpha's Den. They feel the loss (eight to six is a third of
the cushion gone) but the night goes on.

*Veteran, high compass in Act 2.* Mara and one friend at Compass 20 (item 2 makes that hurt): six lives. The
Ancient City capstone at lives 2 is the same knife edge a solo player gets at lives 2.

*Where it goes wrong.* A party of four invites a fifth to the lobby for the extra life and has them stand on
the pad. Guard: only members present at the first door commit count, and the bonus stops at four members. The
other edge: eight lives makes the side door (1 life) nearly free for a four-stack; watch how often groups take
Blood doors.

**What it replaces or reverses.** J3 "a trip starts at five lives" and the haul record's "five deaths total".
Amends the Proving record's "lives 5 shared".

**Cost.** Small. `Omen.LIVES` becomes a function of the party at commit, `IntervalState` keeps the trip's max,
the sidebar colour bands read a fraction instead of a count. No save migration (lives are trip state).

**Risks and how to tell.** In the first party-of-four session: do they reach the capstone's final floor with
lives left, and do they take more blood doors than a solo player does?

---

## 7. The door board says three things

**The change:** "I read a door in two seconds: where, how hard and what it pays, and the one thing worth having."

**The problem it solves.**
- PD-211: "2 scrap, gilded blackstone, nether gold, ancient debris, warped stem, crimson stem, warped wart block,
  nether wart block, enchanted diamond pickaxe". "Most of those are useless too." (2026-10-16-1).
- PD-207: "What is loot 250% and loot x3?" (2026-10-13-1, 05:32:03). Fixed by merging, but the board still
  carries a name line, a pay line, a rolls line, a lives cost, notices and a deal sheet that repeats them.
- PD-151 "Door screen text is too much and too technical"; the owner reads every board before a commit
  (2026-10-16-1, Delight / decisions) and still trips. A newcomer will not.
- Code fact: `DungeonScreen.previewGets` (`DungeonScreen.java:450`) stacks scrap, every palette word, every node
  reward and the wager piece on one line.

**The shape.** Three lines, in this order. Short version first, which I prefer:

| Line | Short | Clear |
|---|---|---|
| 1 | `Deep Quarry . Overclocked . Loot 200%` | `Deep Quarry . Overclocked . Loot 200%` |
| 2 | `+3 scrap . Enchanted diamond pickaxe` | `Pays 3 scrap and an enchanted diamond pickaxe` |
| 3 | `Costs 1 life` (only when it does) | `This door costs 1 life` |

Rules: line 2 names scrap and at most one item, the best promised piece; palette words move to the floor
start title (where they tell the player what to mine, not why to choose). The rolls count leaves the board
(the percent already says it). The deal sheet shows the same three lines, larger, and nothing else.

**Stories.**

*Solo newcomer, first night.* On Black Seam (item 1) her first board is two lines long. Act 1's boards are the
same shape with one more word. She never meets a wall of text.

*Four regulars, mid Act 1.* Three doors, three short boards; someone calls "the pickaxe one" and everyone
agrees in five seconds. The game's pace stays in the floor, not in the staging room.

*Veteran, high compass in Act 2.* Mara compares "Loot 300% . costs 1 life" against a free "Loot 100%" with an
enchanted crossbow and makes a real decision on two numbers and one item.

*Where it goes wrong.* The ore player who wanted to know the Endless Mine floor holds lapis now finds out on
arrival. Answer: the floor title names the palette in its first line, and a mine door may keep one ore word
in place of the item.

**What it replaces or reverses.** PD-211's fix (2026-10-16, "board words name mining tiers") is superseded by
dropping the palette from the board. Amends DECISIONS 2026-10-13 "What the player sees now" (the green rolls
line goes).

**Cost.** Small. `DungeonScreen.previewGets`, `BoardText`, the deal sheet builder, `previewNames`. No migration.

**Risks and how to tell.** Time a board read in the journal (preview to commit); ask "what does that door
pay?" and see whether the answer matches.

---

## 8. Mine guardian floors become the Proving's arena; one special door per act

**The change:** "The mine is the dive, and every third floor is a ring: hold out through the waves, then the
guardian walks in."

**The problem it solves.**
- Two special doors per act do the same thing: descend floor by floor, bank between, a guardian every third
  floor, tier A, B, C pay (DECISIONS 2026-10-11 Endless Mine, 2026-10-15 The Proving, rule 5: "the Proving
  reuses the mine's guardian content"). Its own record asks "whether the Proving cannibalises the mines".
- PD-208: the mine guardians die before their mechanics show, in an ordinary floor's terminal cell. The Proving
  is the arena they need; the phased-boss spine (2026-10-15) is the fight.
- Two half-tested modes cost twice the live checks (L101 and the mine checks) for one idea.

**The shape.** The Proving door is retired. On a mine's guardian floor (every `endlessGuardianEvery`), the
terminal cell is the proving arena: `provingWavesPerFloor` waves themed to the act, then the guardian under
`BossFight` phases, then the pad. Floors between guardians stay mining floors (hidden ore, the faucet). The
wave arena's knobs move to the mine.

**Stories.**

*Solo newcomer, first night.* She first meets the mine at Compass 3. Two floors of digging and fighting, then a
round room, three waves, and a small silverfish queen. She banks, a little shaken, with tier A loot. The mine
has a rhythm she can describe: dig, dig, ring.

*Four regulars, mid Act 1.* They dive the Act 1 mine for an evening. Floors 3, 6 and 9 are the moments they
call each other over for; the pack with a handler on floor 6 gets the group's first wipe and the best story of
the week.

*Veteran, high compass in Act 2.* Mara's Foreman fight now starts after three waves have spent her potions,
and with item 2 it lasts long enough for the vents. "Just a very fast pillager" is gone.

*Where it goes wrong.* The owner asked for the Proving ("a gamemode where the player is given a big 4x4 chunk
room and have to survive waves", 2026-10-16-1). A player who only wants waves, no digging, loses their mode.
Answer offered in the questions: a later "Ring only" lever on the mine door that skips to the next guardian
floor at no pay for the skipped floors.

**What it replaces or reverses.** Retires the 2026-10-15 "The Proving" door (its arena, waves and knobs
survive inside the mine). Amends "Endless Mine, one per act" rule 3 (the guardian spawns in an arena terminal
cell). Keeps the 2026-10-15 phased bosses.

**Cost.** Medium. `the_proving.json` off the astrolabe, the guardian floor's terminal room bias to
`proving_arena`, the wave ladder hooked before `MineGuardians` spawns, the guardian under `BossFight`. No save
migration (the Proving persists nothing).

**Risks and how to tell.** Floor time on guardian floors (target 4 to 8 minutes, today 162 to 502 s with the
guardian a non-event), and whether the player names the guardian unprompted afterwards.

---

## 9. The last hopper rooms leave the pool

**The change:** "Every room tells me what to do by looking at it; I never feed items into a hopper."

**The problem it solves.**
- "The room with the key in the hopper to open the iron doors" was the worst room of a session (2026-10-08-1,
  05:43:21, PD-186). The owner on 2026-10-17: the hopper interface, not the payment, was the problem.
- Still in the pool: "the one that requires food to be thrown in a hopper makes no sense from a ui
  perspective" (the_altar) and "some rooms have chains of hoppers when the player can just place the item
  directly in the final chest" (sorting_floor) (2026-10-16-1, Design feedback). Both at `weight` 1
  (`dungeon_room/the_altar.json:5`, `dungeon_room/sorting_floor.json:5`). PD-214: the sorting channel's water
  leaks into the next room.
- Hopper blocks also appear in `MechanismSpecs`, `SituationSpecs`, `SpurSpecs` and `CopperWorksRoomSpecs`;
  each should be read for whether the hopper is a verb or dressing.

**The shape.** `the_altar` becomes a campfire offering: place raw food on the altar's campfire, it cooks, the
altar opens (a vanilla, visible, tangible verb). `sorting_floor` loses its channel or leaves the pool. Any other
room whose verb is "put an item in a hopper" is rebuilt around a vanilla vault (as the Gilded Vault does) or
leaves the release pool.

**Stories.**

*Solo newcomer, first night.* A small room with a smoking campfire and raw mutton on a shelf. She puts the
mutton on the fire because that is what campfires are for, and a door grinds open. She feels clever.

*Four regulars, mid Act 1.* Nobody stops to explain the altar; it explains itself.

*Veteran, high compass in Act 2.* Fewer rooms she skims past as broken dressing.

*Where it goes wrong.* A player with no food cannot open the altar. It is a dead-end bonus room (spur), so the
floor is still finishable, which is the never soft-lock rule working; the shut room must look like a bonus, not
a blocker.

**What it replaces or reverses.** The altar's hopper key (PD-143 fix) and `sorting_floor`'s gate (PD-144 fix).

**Cost.** Small. Two room specs and templates, manifest weights.

**Risks and how to tell.** Whether a player solves the altar without Lemon; the worst-room question at the next
wrap.

---

## What I would cut

- **Acts 3 to 5 from the release.** Item 5. They cost more live checks than any single feature and they are
  where the veteran plays instead of the release.
- **The Proving as its own door.** Item 8. One idea, two doors, two sets of checks.
- **The mine as an act gate.** Item 4. It turns the act's best optional thrill into homework.
- **The Mending book.** Item 3. One shop line switched off the scarcity value.
- **Below-compass half pay as a general rule.** Item 2 makes it a corner case; the general rule only existed
  because difficulty ignored the compass.
- **Vertical floors Phases 3 and 4 before release.** Drops, overlooks and the map board are the expensive part
  and nobody has walked a stair room yet. Phases 1 and 2 are fine.
- **Floor objectives that are not spawners, in the release acts.** They are Act 3 and 4 only today; keep it so.
  Act 0's gather objective is the one exception, and it earns it.

## What not to touch

- **The trick.** The staging room moves behind the terminal in silence (`RunLifecycle.java:1426`, VISION
  section 4). It is still the most memorable thing in the design.
- **The Astrolabe Room.** The owner's strongest door verdict led to it ("players should be able to choose the
  act and dungeon"). Choosing in the world, with the preview through the glass, is the right shape.
- **The haul and the lever.** "It was definitely pressure" at lives 2 (2026-10-08-1, 03:02:25); the sidebar
  answered "I never read any of the run messages in chat". The model works; items 2 and 6 only make it bite.
- **One loot number.** The 2026-10-13 merge of the wager into "Loot n%" is the right instinct; item 7 builds on
  it.
- **Ordeal rooms and the Plate Relay.** "new relay room is good" (2026-10-08-1, 02:41:44); "the 'Ordeal' rooms
  structure seems to be pretty functional" (2026-10-02-1, 13:15:46). One idea per room, readable, geometric.
- **The phased-boss spine.** Gating phases on what the player does, not on damage dealt, is the correct fix for
  PD-208, and items 2 and 8 depend on it.
- **Vault keys for the dungeon's life, per-player vaults, the Gilded Vault's walk-up vault.** Tangible, fair in
  parties, no hoppers.
- **Never soft-lock on the main path.** The payoff-room rule (the spine never requires a payoff room) is right;
  keep the soak test.

## Questions for the owner

1. **Can two or three people who have never seen the mod play Act 0 and Act 1 before release?** Every playtest
   so far is you. Items 1, 6 and 7 cannot be verified any other way. *Recommended: yes; one session each, with
   Lemon in guide mode and the interviewer only watching.*
2. **Existing Mending gear (item 3): strip, keep, or strip and refund?** *Recommended: keep on existing saves,
   since the release audience starts fresh, and stop selling it; revisit if your own veteran save stops being a
   useful test of the economy.*
3. **In a mixed party (item 2), does the floor follow the lowest compass or the leader's?** *Recommended: the
   lowest, so a veteran can always bring a friend; the veteran's pay drops, not the friend's life.*
4. **How many dungeons open the capstone (item 4)?** *Recommended: three in both acts, as a knob.*
5. **The Proving (item 8) was your request. Is it the waves you wanted, or a separate door?** *Recommended:
   the waves, inside the mine's guardian floors; add a "ring only" lever later if you miss the pure mode.*
6. **Act 0 lives (item 1): shown and refunded on floor 4, or still hidden until Act 1?** *Recommended: shown
   and refunded, so the first real lost life is not the first time the player sees the word.*
